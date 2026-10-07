import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.NoopTranslator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Makes the world's writing systems. A model trained on the pen strokes of real alphabets
 * (stroke_glyphs.py, in the procedural_character_generation project) turns a latent code and
 * a style into a character's strokes; the strokes are drawn as clean lines of even width, so
 * letters are made of strokes and curves rather than blobs.
 *
 * Each system gets a style of its own, chosen as far as can be from every real alphabet's
 * (so it looks like none of them) and from the world's other systems, and a look of its own
 * laid over the model's strokes: how thick and how capped the pen is, whether its strokes
 * run in straight snapped lines or round curves, its slant and proportions, how many strokes
 * its letters tend to have, and habits like a bar along the top, dots, closed loops, joined-up
 * strokes or mirror symmetry. Looks are chosen to differ from one another too.
 */
public class GlyphGenerator implements AutoCloseable {

    public static final String MODEL = GamePaths.HOME + "models/stroke_glyph_generator.pt";
    public static final String STYLES = GamePaths.HOME + "models/stroke_glyph_styles.json";
    // Glyph images are this many pixels square
    private static final int SIZE = 64;

    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;
    private final NDManager manager;
    private final int latent, styleDim, strokes, points;
    private final float[][] realStyles;
    private final float[] styleMean, styleSpread;

    public GlyphGenerator(String modelPath, String stylesPath) throws Exception {
        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(Paths.get(modelPath))
                .optEngine("PyTorch")
                .optTranslator(new NoopTranslator())
                .build();
        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
        this.manager = NDManager.newBaseManager();
        try (FileReader in = new FileReader(stylesPath, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(in).getAsJsonObject();
            latent = json.get("latent").getAsInt();
            styleDim = json.get("style").getAsInt();
            strokes = json.get("strokes").getAsInt();
            points = json.get("points").getAsInt();
            JsonArray embeddings = json.getAsJsonArray("embeddings");
            realStyles = new float[embeddings.size()][];
            for (int i = 0; i < realStyles.length; i++) realStyles[i] = floats(embeddings.get(i).getAsJsonArray());
            styleMean = floats(json.getAsJsonArray("mean"));
            styleSpread = floats(json.getAsJsonArray("std"));
        }
    }

    public GlyphGenerator() throws Exception {
        this(MODEL, STYLES);
    }

    private static float[] floats(JsonArray array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsFloat();
        return values;
    }

    // ------------------------------------------------------------------ a system's style and look

    /**
     * A style for a new system: of the candidates tried, the one furthest from every real
     * alphabet's and from the systems already made (distances measured in each dimension's spread).
     */
    private float[] chooseStyle(Random rand, List<float[]> made) {
        float[] best = null;
        float bestScore = -1f;
        for (int attempt = 0; attempt < 60; attempt++) {
            float[] candidate = new float[styleDim];
            for (int i = 0; i < styleDim; i++) candidate[i] = styleMean[i] + styleSpread[i] * (float) rand.nextGaussian() * 1.5f;
            float nearestReal = Float.MAX_VALUE, nearestMade = Float.MAX_VALUE;
            for (float[] real : realStyles) nearestReal = Math.min(nearestReal, styleDistance(candidate, real));
            for (float[] other : made) nearestMade = Math.min(nearestMade, styleDistance(candidate, other));
            float score = Math.min(nearestReal, nearestMade * 0.8f);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private float styleDistance(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float d = (a[i] - b[i]) / Math.max(1e-3f, styleSpread[i]);
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    /** How a system's letters are drawn, over and above the strokes the model gives. */
    private static final class Look {
        float width;            // pen width, as a share of the letter's height
        int caps;               // 0 round, 1 square, 2 a ball at each end
        float angular;          // 0 flowing curves; above 0.5, straight lines between corners
        int snapDegrees;        // angular strokes run at multiples of this (0 for any angle)
        float slant, aspect;    // lean (shear) and width-to-height
        float strokeThreshold;  // how sure a stroke must be to be kept: higher, simpler letters
        boolean headline, mirror, loops, joined;
        float dotChance;        // how often a letter has a dot, and where (0 above, 1 to the right)
        int dotPlace;
        float anchorWeight;     // how alike the letters are: the share of each one's code common to all

        float[] traits() {
            return new float[] { width * 8f, caps / 2f, angular, snapDegrees / 45f, slant * 2f, aspect, strokeThreshold * 2f,
                    headline ? 1 : 0, mirror ? 1 : 0, loops ? 1 : 0, joined ? 1 : 0, dotChance * 2f };
        }

        static Look random(Random rand) {
            Look look = new Look();
            look.width = 0.045f + rand.nextFloat() * 0.055f;
            // (balls on the ends only with a lighter pen, or the letters clot into blobs)
            look.caps = rand.nextInt(look.width < 0.075f ? 3 : 2);
            look.angular = rand.nextFloat();
            look.snapDegrees = look.angular > 0.5f ? new int[] { 0, 30, 45, 90 }[rand.nextInt(4)] : 0;
            look.slant = (rand.nextFloat() - 0.5f) * 0.6f;
            look.aspect = 0.7f + rand.nextFloat() * 0.6f;
            look.strokeThreshold = 0.3f + rand.nextFloat() * 0.4f;
            look.headline = rand.nextFloat() < 0.18f;
            look.mirror = rand.nextFloat() < 0.15f;
            look.loops = rand.nextFloat() < 0.25f;
            look.joined = rand.nextFloat() < 0.3f;
            look.dotChance = rand.nextFloat() < 0.3f ? 0.2f + rand.nextFloat() * 0.4f : 0f;
            look.dotPlace = rand.nextInt(2);
            look.anchorWeight = 0.35f + rand.nextFloat() * 0.3f;
            return look;
        }
    }

    /** A look for a new system: of a few tried, the one least like the looks already given. */
    private static Look chooseLook(Random rand, List<Look> made) {
        Look best = null;
        float bestScore = -1f;
        for (int attempt = 0; attempt < 24; attempt++) {
            Look candidate = Look.random(rand);
            float nearest = Float.MAX_VALUE;
            for (Look other : made) {
                float[] a = candidate.traits(), b = other.traits();
                float sum = 0f;
                for (int i = 0; i < a.length; i++) sum += (a[i] - b[i]) * (a[i] - b[i]);
                nearest = Math.min(nearest, sum);
            }
            if (made.isEmpty()) return candidate;
            if (nearest > bestScore) {
                bestScore = nearest;
                best = candidate;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ making the systems

    /**
     * Makes the world's writing systems (two to six of them, with ten to forty letters each),
     * as glyph_0.png, glyph_1.png ... in alphabet1, alphabet2 ... under the given directory,
     * the same every time for the same seed.
     */
    public void generateAllSystems(String baseOutputDirectory, long worldSeed) throws Exception {
        File baseDir = new File(baseOutputDirectory);
        if (baseDir.exists()) deleteDirectory(baseDir);
        baseDir.mkdirs();
        Random rand = new Random(worldSeed);
        // (enough for each culture to have its own: see NationScripts)
        int systemCount = 4 + rand.nextInt(4);
        List<float[]> styles = new ArrayList<>();
        List<Look> looks = new ArrayList<>();
        for (int sysId = 1; sysId <= systemCount; sysId++) {
            int letters = 10 + rand.nextInt(31);
            float[] style = chooseStyle(rand, styles);
            Look look = chooseLook(rand, looks);
            styles.add(style);
            looks.add(look);
            File systemDir = new File(baseDir, "alphabet" + sysId);
            systemDir.mkdirs();
            List<BufferedImage> glyphs = makeSystem(style, look, letters, new Random(rand.nextLong()));
            for (int i = 0; i < glyphs.size(); i++) ImageIO.write(glyphs.get(i), "png", new File(systemDir, "glyph_" + i + ".png"));
        }
    }

    /** One system's letters: from codes sharing some of a common anchor, none too like another. */
    private List<BufferedImage> makeSystem(float[] style, Look look, int letters, Random rand) throws Exception {
        float[] anchor = new float[latent];
        for (int i = 0; i < latent; i++) anchor[i] = (float) rand.nextGaussian();
        List<BufferedImage> kept = new ArrayList<>();
        List<boolean[]> inks = new ArrayList<>();
        for (int round = 0; round < 6 && kept.size() < letters; round++) {
            int batch = letters * 2;
            float[] codes = new float[batch * latent];
            float own = (float) Math.sqrt(1f - look.anchorWeight * look.anchorWeight);
            for (int g = 0; g < batch; g++) {
                for (int i = 0; i < latent; i++) codes[g * latent + i] = anchor[i] * look.anchorWeight + (float) rand.nextGaussian() * own;
            }
            float[][][][] strokePoints;
            float[][] presence;
            try (NDManager scope = manager.newSubManager()) {
                NDArray z = scope.create(codes, new Shape(batch, latent));
                NDArray s = scope.create(style, new Shape(1, styleDim));
                NDList out = predictor.predict(new NDList(z, s));
                strokePoints = unpackPoints(out.get(0).toFloatArray(), batch);
                presence = unpackPresence(out.get(1).toFloatArray(), batch);
            }
            for (int g = 0; g < batch && kept.size() < letters; g++) {
                List<float[][]> shape = shapeLetter(strokePoints[g], presence[g], look, rand);
                if (shape.isEmpty()) continue;
                BufferedImage image = draw(shape, look, rand);
                boolean[] ink = inkOf(image);
                if (!wellFormed(ink)) continue;
                boolean repeat = false;
                for (boolean[] other : inks) {
                    if (overlap(ink, other) > 0.62f) {
                        repeat = true;
                        break;
                    }
                }
                if (repeat) continue;
                kept.add(image);
                inks.add(ink);
            }
        }
        return kept;
    }

    private float[][][][] unpackPoints(float[] flat, int batch) {
        float[][][][] out = new float[batch][strokes][points][2];
        int k = 0;
        for (int g = 0; g < batch; g++)
            for (int s = 0; s < strokes; s++)
                for (int p = 0; p < points; p++) {
                    out[g][s][p][0] = flat[k++];
                    out[g][s][p][1] = flat[k++];
                }
        return out;
    }

    private float[][] unpackPresence(float[] flat, int batch) {
        float[][] out = new float[batch][strokes];
        for (int g = 0; g < batch; g++) System.arraycopy(flat, g * strokes, out[g], 0, strokes);
        return out;
    }

    // ------------------------------------------------------------------ a letter in a system's look

    /**
     * The letter's strokes as the system draws them: those the model is sure enough of (always at
     * least one), straightened and snapped or smoothed, closed into loops or joined up as is its
     * habit, mirrored if symmetrical, then slanted and stretched. Points in [-1, 1] or so.
     */
    private List<float[][]> shapeLetter(float[][][] raw, float[] presence, Look look, Random rand) {
        List<float[][]> out = new ArrayList<>();
        int surest = 0;
        for (int s = 1; s < raw.length; s++) if (presence[s] > presence[surest]) surest = s;
        for (int s = 0; s < raw.length; s++) {
            if (s != surest && presence[s] < look.strokeThreshold) continue;
            float[][] stroke = raw[s];
            if (length(stroke) < 0.15f) {
                out.add(new float[][] { stroke[0].clone(), stroke[0].clone() });
                continue;
            }
            stroke = look.angular > 0.5f ? straighten(stroke, 0.08f + 0.12f * look.angular, look.snapDegrees) : smooth(stroke);
            out.add(stroke);
        }
        if (look.loops) {
            for (int i = 0; i < out.size(); i++) {
                float[][] st = out.get(i);
                float[] a = st[0], b = st[st.length - 1];
                if (st.length > 3 && Math.hypot(a[0] - b[0], a[1] - b[1]) < 0.45f) {
                    float[][] closed = java.util.Arrays.copyOf(st, st.length + 1);
                    closed[st.length] = a.clone();
                    out.set(i, closed);
                }
            }
        }
        if (look.joined) {
            // Loose ends near another stroke are drawn on to meet it
            for (int i = 0; i < out.size(); i++) {
                float[][] st = out.get(i);
                for (int end = 0; end < 2; end++) {
                    float[] tip = end == 0 ? st[0] : st[st.length - 1];
                    float[] nearest = null;
                    float best = 0.3f;
                    for (int j = 0; j < out.size(); j++) {
                        if (j == i) continue;
                        for (float[] q : out.get(j)) {
                            float d = (float) Math.hypot(q[0] - tip[0], q[1] - tip[1]);
                            if (d < best) { best = d; nearest = q; }
                        }
                    }
                    if (nearest != null) {
                        tip[0] = nearest[0];
                        tip[1] = nearest[1];
                    }
                }
            }
        }
        if (look.mirror && out.size() <= 2) {
            // Symmetrical: each stroke and its reflection, the whole kept centred
            List<float[][]> both = new ArrayList<>(out);
            for (float[][] st : out) {
                float[][] m = new float[st.length][];
                for (int p = 0; p < st.length; p++) m[p] = new float[] { -st[p][0], st[p][1] };
                both.add(m);
            }
            out = both;
        }
        for (float[][] st : out) {
            for (float[] p : st) {
                p[0] = (p[0] + p[1] * look.slant) * look.aspect;
            }
        }
        return out;
    }

    private static float length(float[][] stroke) {
        float total = 0f;
        for (int i = 1; i < stroke.length; i++) total += (float) Math.hypot(stroke[i][0] - stroke[i - 1][0], stroke[i][1] - stroke[i - 1][1]);
        return total;
    }

    /** Rounded off: each corner cut twice (Chaikin), the ends kept where they were. */
    private static float[][] smooth(float[][] stroke) {
        float[][] pts = stroke;
        for (int pass = 0; pass < 2; pass++) {
            List<float[]> next = new ArrayList<>();
            next.add(pts[0].clone());
            for (int i = 0; i + 1 < pts.length; i++) {
                float[] a = pts[i], b = pts[i + 1];
                next.add(new float[] { a[0] * 0.75f + b[0] * 0.25f, a[1] * 0.75f + b[1] * 0.25f });
                next.add(new float[] { a[0] * 0.25f + b[0] * 0.75f, a[1] * 0.25f + b[1] * 0.75f });
            }
            next.add(pts[pts.length - 1].clone());
            pts = next.toArray(new float[0][]);
        }
        return pts;
    }

    /**
     * Straight lines between its few real corners (the rest of the wobble dropped), each turned
     * to the nearest multiple of snapDegrees if given, keeping its length.
     */
    private static float[][] straighten(float[][] stroke, float tolerance, int snapDegrees) {
        List<float[]> corners = new ArrayList<>();
        simplify(stroke, 0, stroke.length - 1, tolerance, corners);
        corners.add(stroke[stroke.length - 1].clone());
        if (snapDegrees > 0) {
            double step = Math.toRadians(snapDegrees);
            for (int i = 1; i < corners.size(); i++) {
                float[] a = corners.get(i - 1), b = corners.get(i);
                double angle = Math.atan2(b[1] - a[1], b[0] - a[0]);
                double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
                double snapped = Math.round(angle / step) * step;
                b[0] = a[0] + (float) (Math.cos(snapped) * length);
                b[1] = a[1] + (float) (Math.sin(snapped) * length);
            }
        }
        return corners.toArray(new float[0][]);
    }

    /** Ramer-Douglas-Peucker: the points between a and b that stand out more than tolerance from the line. */
    private static void simplify(float[][] pts, int a, int b, float tolerance, List<float[]> out) {
        float ax = pts[a][0], ay = pts[a][1], bx = pts[b][0], by = pts[b][1];
        float len = (float) Math.hypot(bx - ax, by - ay);
        int far = -1;
        float farthest = tolerance;
        for (int i = a + 1; i < b; i++) {
            float d = len < 1e-6f ? (float) Math.hypot(pts[i][0] - ax, pts[i][1] - ay)
                    : Math.abs((bx - ax) * (ay - pts[i][1]) - (ax - pts[i][0]) * (by - ay)) / len;
            if (d > farthest) {
                farthest = d;
                far = i;
            }
        }
        if (far < 0) {
            out.add(pts[a].clone());
            return;
        }
        simplify(pts, a, far, tolerance, out);
        simplify(pts, far, b, tolerance, out);
    }

    // ------------------------------------------------------------------ drawing

    /** The letter drawn in even strokes, dark on light, fitted to the square with its look's extras. */
    private BufferedImage draw(List<float[][]> shape, Look look, Random rand) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[][] st : shape) {
            for (float[] p : st) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        float pen = look.width * SIZE;
        float margin = pen * 0.5f + SIZE * 0.08f;
        float span = Math.max(maxX - minX, maxY - minY);
        float scale = (SIZE - 2f * margin) / Math.max(0.6f, span);
        float ox = SIZE * 0.5f - (minX + maxX) * 0.5f * scale, oy = SIZE * 0.5f - (minY + maxY) * 0.5f * scale;

        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, SIZE, SIZE);
        g.setColor(Color.BLACK);
        int cap = look.caps == 1 ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_ROUND;
        int join = look.angular > 0.5f ? BasicStroke.JOIN_MITER : BasicStroke.JOIN_ROUND;
        g.setStroke(new BasicStroke(pen, cap, join, 4f));
        for (float[][] st : shape) {
            Path2D.Float path = new Path2D.Float();
            path.moveTo(ox + st[0][0] * scale, oy + st[0][1] * scale);
            for (int i = 1; i < st.length; i++) path.lineTo(ox + st[i][0] * scale, oy + st[i][1] * scale);
            g.draw(path);
            if (look.caps == 2 && st.length > 1) {
                // A ball at each end
                float r = pen * 0.7f;
                for (float[] tip : new float[][] { st[0], st[st.length - 1] }) {
                    g.fill(new Ellipse2D.Float(ox + tip[0] * scale - r, oy + tip[1] * scale - r, r * 2f, r * 2f));
                }
            }
        }
        float top = oy + minY * scale, bottom = oy + maxY * scale, left = ox + minX * scale, right = ox + maxX * scale;
        if (look.headline) {
            // A bar along the top, the letters hanging from it
            g.setStroke(new BasicStroke(pen, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(new java.awt.geom.Line2D.Float(Math.max(1f, left - pen), top, Math.min(SIZE - 1f, right + pen), top));
        }
        if (look.dotChance > 0f && rand.nextFloat() < look.dotChance) {
            float r = pen * 0.9f;
            float dx = look.dotPlace == 0 ? (left + right) * 0.5f : Math.min(SIZE - r - 1f, right + pen * 1.6f);
            float dy = look.dotPlace == 0 ? Math.max(r + 1f, top - pen * 1.8f) : (top + bottom) * 0.5f;
            g.fill(new Ellipse2D.Float(dx - r, dy - r, r * 2f, r * 2f));
        }
        g.dispose();
        return image;
    }

    private static boolean[] inkOf(BufferedImage image) {
        boolean[] ink = new boolean[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) ink[y * SIZE + x] = (image.getRGB(x, y) & 0xFF) < 128;
        return ink;
    }

    /** Enough ink, spread over enough of the square, to read as a letter rather than a speck. */
    private static boolean wellFormed(boolean[] ink) {
        int count = 0, minX = SIZE, maxX = -1, minY = SIZE, maxY = -1;
        for (int i = 0; i < ink.length; i++) {
            if (!ink[i]) continue;
            count++;
            int x = i % SIZE, y = i / SIZE;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return count > SIZE * SIZE * 0.03f && Math.max(maxX - minX, maxY - minY) > SIZE * 0.4f;
    }

    /** How much two letters' ink overlaps (intersection over union). */
    private static float overlap(boolean[] a, boolean[] b) {
        int both = 0, either = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] && b[i]) both++;
            if (a[i] || b[i]) either++;
        }
        return either == 0 ? 1f : both / (float) either;
    }

    private void deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) for (File file : allContents) deleteDirectory(file);
        directoryToBeDeleted.delete();
    }

    @Override
    public void close() {
        if (predictor != null) predictor.close();
        if (model != null) model.close();
        if (manager != null) manager.close();
    }

    /**
     * Developer aid: a sheet of the systems a seed makes, one per row, as written to the given
     * file. Arguments: seed, output .png.
     */
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        File dir = java.nio.file.Files.createTempDirectory("glyphs").toFile();
        try (GlyphGenerator generator = new GlyphGenerator()) {
            generator.generateAllSystems(dir.getPath(), seed);
        }
        File[] systems = dir.listFiles(File::isDirectory);
        java.util.Arrays.sort(systems);
        int columns = 16, cell = SIZE + 4;
        BufferedImage sheet = new BufferedImage(columns * cell, systems.length * cell, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int r = 0; r < systems.length; r++) {
            for (int c = 0; c < columns; c++) {
                File f = new File(systems[r], "glyph_" + c + ".png");
                if (f.exists()) g.drawImage(ImageIO.read(f), c * cell + 2, r * cell + 2, null);
            }
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(args[1]));
    }
}
