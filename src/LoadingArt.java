import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import javax.imageio.ImageIO;
import com.xenoguesser.math.Vector3;

/**
 * The fixed art of the loading screen and the player's suit, pre-rendered once and kept in
 * assets/art (it is not part of the procedural world, so it never needs regenerating):
 * <ul>
 *   <li>the spaceship's cockpit, seen from just behind the pilots' seats, with its front
 *       window left transparent so stars can stream past behind it;</li>
 *   <li>the spaceman turntable: the player in their suit seen from 48 angles;</li>
 *   <li>the suit's crumpled white fabric, also used on the player's body in the game;</li>
 *   <li>a layout file saying where the old computer's screen and the spaceman sit.</li>
 * </ul>
 * Anything missing is generated when first asked for; run main() to regenerate it all.
 */
public final class LoadingArt {

    public static final String DIR = "assets/art";
    public static final String COCKPIT = DIR + "/cockpit.png";
    public static final String SPACEMAN = DIR + "/spaceman_turntable.png";
    // The main menu's background: a frame of the game itself, made with -Dxenoguesser.menushot
    public static final String MENU = DIR + "/main_menu.png";
    public static final String FABRIC = DIR + "/suit_fabric.png";
    public static final String LAYOUT = DIR + "/cockpit_layout.properties";

    public static final int WIDTH = 1920, HEIGHT = 1080;
    public static final int FRAMES = 48, FRAME_COLUMNS = 8, FRAME_WIDTH = 256, FRAME_HEIGHT = 512;

    private LoadingArt() {}

    public static void main(String[] args) throws Exception {
        new File(DIR).mkdirs();
        long start = System.currentTimeMillis();
        ImageIO.write(fabric(), "png", new File(FABRIC));
        renderAll();
        System.out.printf("Loading art written in %d ms%n", System.currentTimeMillis() - start);
    }

    /** Makes any of the art that is missing. */
    public static synchronized void ensure() {
        try {
            new File(DIR).mkdirs();
            if (!new File(FABRIC).exists()) ImageIO.write(fabric(), "png", new File(FABRIC));
            if (!new File(COCKPIT).exists() || !new File(SPACEMAN).exists() || !new File(LAYOUT).exists()) renderAll();
        } catch (Exception e) {
            System.err.println("Could not make the loading screen art: " + e.getMessage());
        }
    }

    public static boolean isReady() {
        return new File(FABRIC).exists() && new File(COCKPIT).exists() && new File(SPACEMAN).exists() && new File(LAYOUT).exists();
    }

    private static void renderAll() throws Exception {
        BufferedImage fabric = ImageIO.read(new File(FABRIC));
        Properties layout = new Properties();
        ImageIO.write(cockpit(layout), "png", new File(COCKPIT));
        ImageIO.write(spaceman(fabric), "png", new File(SPACEMAN));
        try (FileWriter out = new FileWriter(LAYOUT)) {
            layout.store(out, "Where things sit in cockpit.png (pixels)");
        }
    }

    public static Properties readLayout() {
        Properties layout = new Properties();
        try (FileReader in = new FileReader(LAYOUT)) {
            layout.load(in);
        } catch (Exception e) {
            // Missing layout: the screen falls back to defaults
        }
        return layout;
    }

    // ==========================================
    //          FABRIC
    // ==========================================

    /**
     * Crumpled white suit fabric: creases from ridged noise at a few scales, stretched along
     * different directions like folds, lit from the upper left. Tiles in both directions.
     */
    public static BufferedImage fabric() {
        int n = 512;
        Random rng = new Random(0x5017L);
        float[] height = new float[n * n];
        double[][] layers = { { 6, 0.55, 0.3, 1.0 }, { 14, 0.35, 1.4, 0.55 }, { 30, 0.2, 2.3, 0.3 } };
        for (double[] layer : layers) {
            float[] noise = TileableNoise.spectral(rng, n, 2.2, 1.0 + layer[1] * 3, layer[2], layer[0] * 0.5, layer[0] * 1.6);
            for (int i = 0; i < height.length; i++) {
                // Ridged: sharp creases where the noise crosses its middle
                float ridge = 1f - Math.abs(noise[i] * 2f - 1f);
                height[i] += (float) (ridge * ridge * layer[3]);
            }
        }
        BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        float[] light = Affine.normalise(new float[] { -0.5f, 0.6f, 0.65f });
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float dx = height[y * n + (x + 1) % n] - height[y * n + (x + n - 1) % n];
                float dy = height[((y + 1) % n) * n + x] - height[((y + n - 1) % n) * n + x];
                float[] normal = Affine.normalise(new float[] { -dx * 6f, dy * 6f, 1f });
                float shade = 0.78f + 0.3f * Affine.dot(normal, light) - 0.08f * (height[y * n + x] - 0.6f);
                shade = Math.max(0.55f, Math.min(1.0f, shade));
                int r = Math.round(shade * 250), g = Math.round(shade * 249), b = Math.round(shade * 244);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    // ==========================================
    //          LIGHTING
    // ==========================================

    /** A point light: position, colour (linear) and reach. */
    private record Light(float[] position, float[] colour, float reach) {}

    private static final float[] AMBIENT = { 0.05f, 0.055f, 0.07f };

    /** Plain lit material with optional sheen and glow, coloured per point by the given function. */
    private static SoftwareRenderer.SurfaceShader lit(List<Light> lights, float[] eye, float shine, float glow,
                                                       SoftwareRenderer.SurfaceShader albedo) {
        return (p, n, u, v) -> {
            float[] base = albedo.shade(p, n, u, v);
            float[] viewDir = Affine.normalise(Affine.subtract(eye, p));
            float r = base[0] * AMBIENT[0] * 4f, g = base[1] * AMBIENT[1] * 4f, b = base[2] * AMBIENT[2] * 4f;
            for (Light light : lights) {
                float[] toLight = Affine.subtract(light.position, p);
                float distance = Affine.length(toLight);
                float[] l = Affine.normalise(toLight);
                float falloff = 1f / (1f + (distance / light.reach) * (distance / light.reach));
                float diffuse = Math.max(0f, Affine.dot(n, l)) * falloff;
                float[] half = Affine.normalise(Affine.add(l, viewDir, 1f));
                float spec = (float) Math.pow(Math.max(0f, Affine.dot(n, half)), 40) * shine * falloff;
                r += light.colour[0] * (base[0] * diffuse + spec);
                g += light.colour[1] * (base[1] * diffuse + spec);
                b += light.colour[2] * (base[2] * diffuse + spec);
            }
            return new float[] { r + base[0] * glow, g + base[1] * glow, b + base[2] * glow };
        };
    }

    private static SoftwareRenderer.SurfaceShader flat(float r, float g, float b) {
        float[] c = { r, g, b };
        return (p, n, u, v) -> c;
    }

    /** Metal panelling: seams every few units and a little grime. */
    private static SoftwareRenderer.SurfaceShader panels(float r, float g, float b, float size) {
        return (p, n, u, v) -> {
            float fu = frac(u / size), fv = frac(v / size);
            boolean seam = fu < 0.025f || fv < 0.025f;
            boolean rivet = (Math.abs(fu - 0.06f) < 0.015f || Math.abs(fu - 0.94f) < 0.015f)
                    && (Math.abs(fv - 0.06f) < 0.015f || Math.abs(fv - 0.94f) < 0.015f);
            float grime = 0.88f + 0.12f * (float) Math.sin(u * 0.37 + Math.sin(v * 0.21) * 3);
            float k = seam ? 0.45f : rivet ? 1.25f : grime;
            return new float[] { r * k, g * k, b * k };
        };
    }

    /** Floor grating: a grid of dark slots. */
    private static SoftwareRenderer.SurfaceShader grating(float r, float g, float b) {
        return (p, n, u, v) -> {
            boolean slot = frac(u / 1.6f) > 0.75f || frac(v / 6f) < 0.04f;
            float k = slot ? 0.25f : 1f;
            return new float[] { r * k, g * k, b * k };
        };
    }

    /** A glowing display full of made-up readouts: lines, bars and a trace. */
    private static SoftwareRenderer.SurfaceShader display(float r, float g, float b, long seed) {
        return (p, n, u, v) -> {
            float row = (float) Math.floor(v / 0.9f);
            double hash = Math.sin(row * 12.9898 + seed * 78.233) * 43758.5453;
            float lineLength = (float) (hash - Math.floor(hash));
            boolean text = frac(v / 0.9f) > 0.35f && frac(u / 0.45f) > 0.3f && frac(u / 6f) < lineLength * 0.9f;
            float wave = (float) Math.sin(u * 1.3 + seed) * 1.2f + 3f;
            boolean trace = Math.abs(v - wave) < 0.12f && seed % 2 == 0;
            float k = text || trace ? 1.0f : 0.12f;
            return new float[] { r * k, g * k, b * k };
        };
    }

    private static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    // ==========================================
    //          COCKPIT
    // ==========================================

    /**
     * The cockpit, the front of a ship: a panelled room with a grated floor and light strips
     * that narrows to a pointed nose, its window in three joined panes (one straight ahead and
     * one either side angled outwards). Beneath them the pilots' instruments face the seats:
     * an upright panel of gauges and screens, a sloping desk, consoles under the side windows,
     * flight sticks and an overhead switch panel. Two small pilot seats, side consoles and
     * pipes along the walls, and an old beige computer on a desk to the right.
     */
    private static BufferedImage cockpit(Properties layout) {
        SoftwareRenderer r = new SoftwareRenderer(WIDTH, HEIGHT, 2);
        float[] eye = { 0f, 24f, -6f };
        r.lookAt(eye, new float[] { 0f, 13.5f, 60f }, 62f);

        List<Light> lights = new ArrayList<>();
        lights.add(new Light(new float[] { -18f, 28f, 10f }, new float[] { 1.3f, 1.15f, 0.95f }, 34f));
        lights.add(new Light(new float[] { 18f, 28f, 10f }, new float[] { 1.3f, 1.15f, 0.95f }, 34f));
        lights.add(new Light(new float[] { 0f, 25f, 42f }, new float[] { 0.9f, 0.85f, 0.8f }, 30f));
        lights.add(new Light(new float[] { 0f, 20f, 85f }, new float[] { 0.35f, 0.5f, 0.9f }, 60f));   // starlight from the window
        lights.add(new Light(new float[] { 0f, 9f, 58f }, new float[] { 0.2f, 0.9f, 0.8f }, 16f));     // instrument glow
        lights.add(new Light(new float[] { -31f, 22f, 26f }, new float[] { 0.9f, 0.15f, 0.1f }, 12f)); // warning lamp

        SoftwareRenderer.SurfaceShader wall = lit(lights, eye, 0.2f, 0f, panels(0.12f, 0.13f, 0.15f, 8f));
        SoftwareRenderer.SurfaceShader darkWall = lit(lights, eye, 0.2f, 0f, panels(0.1f, 0.11f, 0.13f, 6f));
        SoftwareRenderer.SurfaceShader floor = lit(lights, eye, 0.3f, 0f, grating(0.12f, 0.125f, 0.135f));
        SoftwareRenderer.SurfaceShader trimMetal = lit(lights, eye, 0.6f, 0f, flat(0.16f, 0.17f, 0.2f));
        SoftwareRenderer.SurfaceShader panelFace = lit(lights, eye, 0.4f, 0f, flat(0.11f, 0.12f, 0.14f));
        SoftwareRenderer.SurfaceShader frame = lit(lights, eye, 0.5f, 0f, flat(0.28f, 0.29f, 0.33f));
        SoftwareRenderer.SurfaceShader seat = lit(lights, eye, 0.3f, 0f, flat(0.32f, 0.12f, 0.1f));
        SoftwareRenderer.SurfaceShader beige = lit(lights, eye, 0.2f, 0f, flat(0.72f, 0.66f, 0.52f));
        SoftwareRenderer.SurfaceShader pipe = lit(lights, eye, 0.7f, 0f, flat(0.45f, 0.38f, 0.3f));
        SoftwareRenderer.SurfaceShader gaugeFace = lit(lights, eye, 0.5f, 0.6f, (p, n, u, v) -> new float[] { 0.85f, 0.8f, 0.6f });
        float[] inside = { 0f, 15f, 30f };

        // The room, seen from above: straight walls to the shoulders, then the nose closing in
        // to a flat front; the ceiling coming down towards the front
        float backZ = -14f, shoulderZ = 30f, noseZ = 72f;
        float backHalf = 40f, shoulderHalf = 35f, noseHalf = 10f;
        float backTop = 32f, shoulderTop = 30f, noseTop = 26.5f;
        float[][] outline = {   // left to right round the room, at floor level: {x, z, ceiling}
            { -backHalf, backZ, backTop }, { -shoulderHalf, shoulderZ, shoulderTop }, { -noseHalf, noseZ, noseTop },
            { noseHalf, noseZ, noseTop }, { shoulderHalf, shoulderZ, shoulderTop }, { backHalf, backZ, backTop } };
        // Floor and ceiling, a strip at a time
        float[][][] strips = { { outline[0], outline[1], outline[4], outline[5] }, { outline[1], outline[2], outline[3], outline[4] } };
        for (float[][] q : strips) {
            inward(r, inside, floor, at(q[0], 0f), at(q[3], 0f), at(q[2], 0f), at(q[1], 0f));
            inward(r, inside, darkWall, at(q[0], q[0][2]), at(q[1], q[1][2]), at(q[2], q[2][2]), at(q[3], q[3][2]));
        }
        // The walls round from back left to back right; the nose's three facets darker
        for (int i = 0; i + 1 < outline.length; i++) {
            float[] a = outline[i], b = outline[i + 1];
            inward(r, inside, i == 0 || i == outline.length - 2 ? wall : darkWall, at(a, 0f), at(b, 0f), at(b, b[2]), at(a, a[2]));
        }

        // Light strips along the ceiling
        for (int side = -1; side <= 1; side += 2) {
            r.box(new float[] { side * 18f, 30.4f, 14f }, 1.2f, 0.4f, 22f, 0f, lit(lights, eye, 0f, 1.6f, flat(1f, 0.92f, 0.75f)));
        }
        // Pipes along the tops of the walls, back to the shoulders
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < 3; k++) {
                float x0 = side * (38.5f - k * 1.6f), x1 = side * (32.5f - k * 1.6f);
                r.cylinder(new float[] { x0, 27.5f - k * 1.5f, backZ }, new float[] { x1, 25.5f - k * 1.5f, shoulderZ - 1f }, 0.6f, 10, pipe);
            }
        }

        // The window: a pane in each of the nose's three facets, side by side; the holes are
        // cut once the walls are drawn, then the frames go round them
        float bottom = 11.5f, top = 24.5f;
        float[] noseL = { -noseHalf, noseZ }, noseR = { noseHalf, noseZ };
        float[] shoulderL = { -shoulderHalf, shoulderZ }, shoulderR = { shoulderHalf, shoulderZ };
        float[][][] panes = {
            pane(along(shoulderL, noseL, 0.22f), noseL, bottom + 1f, top - 0.5f),
            pane(noseL, noseR, bottom, top),
            pane(noseR, along(shoulderR, noseR, 0.22f), bottom + 1f, top - 0.5f) };
        List<float[][]> paneScreens = new ArrayList<>();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[][] pane : panes) {
            float[][] onScreen = new float[4][];
            for (int i = 0; i < 4; i++) {
                onScreen[i] = r.project(nudge(pane[i], inside, 0.25f));
                minX = Math.min(minX, onScreen[i][0]);
                maxX = Math.max(maxX, onScreen[i][0]);
                minY = Math.min(minY, onScreen[i][1]);
                maxY = Math.max(maxY, onScreen[i][1]);
            }
            paneScreens.add(onScreen);
        }
        r.cutOut((x, y) -> {
            for (float[][] screen : paneScreens) if (insidePolygon(screen, x, y)) return true;
            return false;
        });
        for (float[][] pane : panes) {
            for (int i = 0; i < 4; i++) r.cylinder(nudge(pane[i], inside, 0.6f), nudge(pane[(i + 1) % 4], inside, 0.6f), 0.9f, 8, frame);
        }
        // Thicker posts where the panes meet
        for (float[] corner : new float[][] { noseL, noseR }) {
            r.cylinder(nudge(new float[] { corner[0], bottom - 1f, corner[1] }, inside, 0.8f),
                    nudge(new float[] { corner[0], noseTop, corner[1] }, inside, 0.8f), 1.3f, 10, frame);
        }

        Random rand = new Random(7L);
        float[][] palette = { { 0.2f, 1f, 0.5f }, { 1f, 0.6f, 0.1f }, { 0.2f, 0.7f, 1f }, { 1f, 0.2f, 0.25f }, { 0.9f, 0.9f, 0.3f } };

        // The upright instrument panel beneath the forward window, facing the seats: screens
        // across the middle, round gauges either side, a row of lit buttons beneath
        float panelZ = noseZ - 1.2f;
        r.box(new float[] { 0f, 6f, panelZ }, noseHalf - 0.5f, 5.5f, 0.8f, 0f, panelFace);
        for (int i = 0; i < 3; i++) {
            float[] c = palette[(i * 2) % palette.length];
            r.box(new float[] { -6.5f + i * 6.5f, 8f, panelZ - 0.9f }, 2.6f, 2.0f, 0.1f, 0f, lit(lights, eye, 0.8f, 1.4f, display(c[0], c[1], c[2], 40 + i)));
        }
        for (int i = 0; i < 9; i++) {
            float gx = -9.6f + i * 2.4f;
            r.cylinder(new float[] { gx, 4.2f, panelZ - 0.85f }, new float[] { gx, 4.2f, panelZ - 1.25f }, 0.95f, 14, gaugeFace);
        }
        lightRow(r, lights, eye, rand, palette, new float[] { -10f, 2.0f, panelZ - 0.9f }, new float[] { 1f, 0f, 0f }, 18, 1.15f, 0f);

        // The sloping desk in front of it
        float deskTilt = -0.5f;
        float[] deskNormal = Affine.transformDirection(Affine.rotationX(deskTilt), 0f, 1f, 0f);
        float[] deskAlong = Affine.transformDirection(Affine.rotationX(deskTilt), 0f, 0f, 1f);
        r.box(new float[] { 0f, 2.6f, noseZ - 6f }, noseHalf - 1f, 2.6f, 3.6f, 0f, trimMetal);
        float[] deskCentre = { 0f, 5.6f, noseZ - 6.5f };
        r.box(deskCentre, noseHalf - 1f, 0.5f, 4f, 0f, deskTilt, darkWall);
        for (int i = 0; i < 4; i++) {
            float[] c = palette[rand.nextInt(palette.length)];
            float[] centre = Affine.add(Affine.add(new float[] { -7.5f + i * 5f, 5.6f, noseZ - 6.5f }, deskNormal, 0.55f), deskAlong, 1.4f);
            r.box(centre, 1.9f, 0.08f, 1.5f, 0f, deskTilt, lit(lights, eye, 0.8f, 1.4f, display(c[0], c[1], c[2], i)));
        }
        for (int row = 0; row < 2; row++) {
            float[] start = Affine.add(Affine.add(new float[] { -9.5f, 5.6f, noseZ - 6.5f }, deskNormal, 0.65f), deskAlong, -1.2f - row * 1.1f);
            lightRow(r, lights, eye, rand, palette, start, new float[] { 1f, 0f, 0f }, 16, 1.25f, deskTilt);
        }
        // Throttle levers in the middle of the desk
        for (int k = -1; k <= 1; k++) {
            float[] base = Affine.add(Affine.add(new float[] { k * 1.2f, 5.6f, noseZ - 6.5f }, deskNormal, 0.6f), deskAlong, -3.0f);
            r.cylinder(base, new float[] { base[0], base[1] + 2.6f, base[2] - 0.8f }, 0.22f, 6, trimMetal);
            r.box(new float[] { base[0], base[1] + 2.8f, base[2] - 0.85f }, 0.45f, 0.45f, 0.45f, 0f, lit(lights, eye, 0.6f, 0.3f, flat(0.8f, 0.15f, 0.1f)));
        }

        // Consoles under the side windows, angled along the nose, screens tipped up to the pilots
        for (int side = -1; side <= 1; side += 2) {
            float[] from = side < 0 ? shoulderL : noseR, to = side < 0 ? noseL : shoulderR;
            float[] middle = along(from, to, 0.6f);
            float yaw = (float) Math.atan2(to[0] - from[0], to[1] - from[1]) - (float) Math.PI / 2f;
            float[] out = nudge(new float[] { middle[0], 0f, middle[1] }, inside, 4.2f);
            r.box(new float[] { out[0], 3.6f, out[2] }, 9f, 3.6f, 3f, yaw, trimMetal);
            r.box(new float[] { out[0], 7.6f, out[2] }, 9f, 0.45f, 3.4f, yaw, -0.45f, darkWall);
            for (int i = 0; i < 2; i++) {
                float[] c = palette[(i + (side > 0 ? 3 : 1)) % palette.length];
                float[] at = Affine.add(new float[] { out[0], 8.1f, out[2] }, Affine.transformDirection(Affine.rotationY(yaw), 1f, 0f, 0f), -4.2f + i * 8.4f);
                r.box(at, 3f, 0.08f, 2f, yaw, -0.45f, lit(lights, eye, 0.8f, 1.3f, display(c[0], c[1], c[2], 60 + i + side)));
            }
            float[] rowStart = Affine.add(new float[] { out[0], 8.15f, out[2] }, Affine.transformDirection(Affine.rotationY(yaw), 1f, 0f, 0f), -1.0f);
            lightRow(r, lights, eye, rand, palette, rowStart, Affine.transformDirection(Affine.rotationY(yaw), 1f, 0f, 0f), 3, 1.0f, -0.45f);
        }

        // Overhead switch panel, sloping down towards the pilots from the ceiling at the front
        float[] overhead = { 0f, noseTop - 1.6f, noseZ - 9f };
        r.box(overhead, 9f, 0.6f, 5f, 0f, 0.35f, panelFace);
        for (int row = 0; row < 3; row++) {
            float[] start = { -7.6f, overhead[1] - 0.75f + row * 0.55f, overhead[2] + 2.8f - row * 1.6f };
            lightRow(r, lights, eye, rand, palette, start, new float[] { 1f, 0f, 0f }, 12, 1.4f, 0.35f);
        }

        // Two small pilot seats facing the window, each with a flight stick before it
        for (int side = -1; side <= 1; side += 2) {
            float sx = side * 10.5f, sz = 55f;
            r.cylinder(new float[] { sx, 0f, sz }, new float[] { sx, 3f, sz }, 0.55f, 8, trimMetal);
            r.box(new float[] { sx, 3.6f, sz }, 2.6f, 0.8f, 2.6f, 0f, seat);
            r.box(new float[] { sx, 7.6f, sz - 2.3f }, 2.6f, 4.2f, 0.8f, 0f, -0.15f, seat);
            r.box(new float[] { sx, 12.6f, sz - 2.9f }, 1.7f, 1.0f, 0.7f, 0f, -0.15f, seat);
            r.cylinder(new float[] { sx, 0f, sz + 5.2f }, new float[] { sx, 6.2f, sz + 4.6f }, 0.3f, 6, trimMetal);
            r.box(new float[] { sx, 6.6f, sz + 4.55f }, 0.55f, 0.8f, 0.5f, 0f, lit(lights, eye, 0.5f, 0f, flat(0.1f, 0.1f, 0.11f)));
            r.box(new float[] { sx, 7.45f, sz + 4.55f }, 0.18f, 0.12f, 0.18f, 0f, lit(lights, eye, 0.6f, 1.5f, flat(1f, 0.25f, 0.1f)));
        }

        // Side consoles with screens and gauges along both walls, back of the shoulders
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < 2; k++) {
                float z = 2f + k * 15f;
                float wallX = side * (backHalf - (z - backZ) / (shoulderZ - backZ) * (backHalf - shoulderHalf));
                float yaw = side * (float) Math.atan2(backHalf - shoulderHalf, shoulderZ - backZ);
                r.box(new float[] { wallX - side * 3f, 6f, z }, 3f, 6f, 6.5f, yaw, trimMetal);
                float[] c = palette[(k + (side > 0 ? 2 : 0)) % palette.length];
                r.box(new float[] { wallX - side * 0.6f, 18f, z }, 0.3f, 3.4f, 5f, yaw, lit(lights, eye, 0.8f, 1.2f, display(c[0], c[1], c[2], 20 + k + side)));
                for (int gauge = 0; gauge < 3; gauge++) {
                    float gz = z - 4f + gauge * 4f;
                    r.cylinder(new float[] { wallX - side * 0.4f, 11f, gz }, new float[] { wallX - side * 0.9f, 11f, gz }, 1.3f, 14, gaugeFace);
                }
            }
        }
        // A red warning lamp high on the left wall
        r.box(new float[] { -33.6f, 22f, 26f }, 0.6f, 0.9f, 0.9f, 0f, lit(lights, eye, 0f, 2f, flat(1f, 0.15f, 0.1f)));

        // The computer: an old beige monitor and keyboard on a desk to the right, out of the
        // pilots' way, built at scale k so its screen is large enough to read
        float cx = 15.5f, cz = 24f, k = 1.35f;
        r.box(new float[] { cx, 3f * k, cz + 1f * k }, 5f * k, 3f * k, 4f * k, 0f, trimMetal);
        r.box(new float[] { cx, 6.3f * k, cz - 0.5f * k }, 7.5f * k, 0.35f * k, 5.5f * k, 0f, trimMetal);
        r.box(new float[] { cx, 11.0f * k, cz + 1.8f * k }, 4.6f * k, 4.1f * k, 4.0f * k, 0f, beige);
        r.box(new float[] { cx, 10.8f * k, cz + 5.0f * k }, 3.3f * k, 3.0f * k, 2.0f * k, 0f, beige);
        r.box(new float[] { cx, 6.9f * k, cz + 1.8f * k }, 2.6f * k, 0.35f * k, 2.2f * k, 0f, beige);
        r.box(new float[] { cx, 6.9f * k, cz - 3.6f * k }, 4.2f * k, 0.35f * k, 1.4f * k, 0f, -0.12f, beige);
        for (int key = 0; key < 24; key++) {
            float kx = cx + (-3.6f + (key % 12) * 0.65f) * k, kz = cz + (-4.1f + (key / 12) * 0.85f) * k;
            r.box(new float[] { kx, 7.35f * k, kz }, 0.24f * k, 0.1f * k, 0.27f * k, 0f, -0.12f, lit(lights, eye, 0.3f, 0f, flat(0.55f, 0.5f, 0.42f)));
        }
        // The screen: dark phosphor glass set in its bezel; the text is drawn live
        float screenZ = cz + (1.8f - 4.0f) * k - 0.05f;
        float[][] screen = { { cx - 3.6f * k, 7.9f * k, screenZ }, { cx + 3.6f * k, 7.9f * k, screenZ },
                { cx + 3.6f * k, 13.9f * k, screenZ }, { cx - 3.6f * k, 13.9f * k, screenZ } };
        r.quad(screen[0], screen[1], screen[2], screen[3], lit(lights, eye, 1.2f, 0.25f, flat(0.03f, 0.12f, 0.05f)));
        String[] names = { "screen.bottomLeft", "screen.bottomRight", "screen.topRight", "screen.topLeft" };
        for (int i = 0; i < 4; i++) {
            float[] sp = r.project(screen[i]);
            layout.setProperty(names[i], sp[0] + "," + sp[1]);
        }
        // The stars are drawn within the box round all three panes
        layout.setProperty("window.bottomLeft", minX + "," + maxY);
        layout.setProperty("window.bottomRight", maxX + "," + maxY);
        layout.setProperty("window.topRight", maxX + "," + minY);
        layout.setProperty("window.topLeft", minX + "," + minY);

        // Where the spaceman stands: on the floor to the left
        float[] feet = r.project(new float[] { -18f, 0f, 36f });
        float[] head = r.project(new float[] { -18f, 22.5f, 36f });
        layout.setProperty("spaceman.feet", feet[0] + "," + feet[1]);
        layout.setProperty("spaceman.height", String.valueOf(feet[1] - head[1]));
        return r.image();
    }

    /** A point of the room's outline ({x, z, ...}) at height y. */
    private static float[] at(float[] outline, float y) {
        return new float[] { outline[0], y, outline[1] };
    }

    /** The point t of the way from a to b, both {x, z}. */
    private static float[] along(float[] a, float[] b, float t) {
        return new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t };
    }

    /** A window pane in a wall from a to b ({x, z}), between two heights: its four corners. */
    private static float[][] pane(float[] a, float[] b, float bottom, float top) {
        return new float[][] { { a[0], bottom, a[1] }, { b[0], bottom, b[1] }, { b[0], top, b[1] }, { a[0], top, a[1] } };
    }

    /** A point moved a little way towards the inside of the room, horizontally. */
    private static float[] nudge(float[] point, float[] inside, float by) {
        float dx = inside[0] - point[0], dz = inside[2] - point[2];
        float length = (float) Math.hypot(dx, dz);
        return new float[] { point[0] + dx / length * by, point[1], point[2] + dz / length * by };
    }

    /** A quad facing into the room, whichever way round its corners are given. */
    private static void inward(SoftwareRenderer r, float[] inside, SoftwareRenderer.SurfaceShader shader,
                               float[] a, float[] b, float[] c, float[] d) {
        float[] n = Affine.cross(Affine.subtract(b, a), Affine.subtract(d, a));
        float[] centre = { (a[0] + c[0]) * 0.5f, (a[1] + c[1]) * 0.5f, (a[2] + c[2]) * 0.5f };
        if (Affine.dot(n, Affine.subtract(inside, centre)) < 0f) r.quad(a, d, c, b, shader);
        else r.quad(a, b, c, d, shader);
    }

    /** A row of small lit (or unlit) buttons from start along a direction, tipped back by pitch. */
    private static void lightRow(SoftwareRenderer r, List<Light> lights, float[] eye, Random rand, float[][] palette,
                                 float[] start, float[] direction, int count, float spacing, float pitch) {
        float yaw = (float) Math.atan2(direction[2], direction[0]) * -1f;
        for (int i = 0; i < count; i++) {
            float[] bc = palette[rand.nextInt(palette.length)];
            boolean on = rand.nextFloat() < 0.6f;
            float[] at = Affine.add(start, direction, i * spacing);
            r.box(at, 0.34f, 0.18f, 0.34f, yaw, pitch, lit(lights, eye, 0.6f, on ? 1.5f : 0.1f,
                    flat(bc[0] * (on ? 1f : 0.3f), bc[1] * (on ? 1f : 0.3f), bc[2] * (on ? 1f : 0.3f))));
        }
    }

    private static boolean insidePolygon(float[][] polygon, float x, float y) {
        boolean inside = false;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            float xi = polygon[i][0], yi = polygon[i][1], xj = polygon[j][0], yj = polygon[j][1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }

    // ==========================================
    //          SPACEMAN
    // ==========================================

    /**
     * The player in a white spacesuit with a helmet and dark visor, standing, seen from 48
     * evenly spaced angles; frames run left to right, top to bottom in an 8-wide grid.
     * Lit like the cockpit's left side: warm from above, cool from the window ahead.
     */
    private static BufferedImage spaceman(BufferedImage fabric) {
        OrganismMesh.Builder builder = PlayerBody.buildMesh(true);
        float[] vertices = builder.vertices();
        int[] indices = builder.indices();
        int rows = (FRAMES + FRAME_COLUMNS - 1) / FRAME_COLUMNS;
        BufferedImage sheet = new BufferedImage(FRAME_COLUMNS * FRAME_WIDTH, rows * FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        PlayerBody body = new PlayerBody();
        for (int frame = 0; frame < FRAMES; frame++) {
            float heading = (float) (frame * Math.PI * 2 / FRAMES);
            float[] bones = body.standingPose(new Vector3(0f, 20f, 0f), heading);
            SoftwareRenderer r = new SoftwareRenderer(FRAME_WIDTH, FRAME_HEIGHT, 2);
            // The camera looks at the figure from in front (-Z); heading 0 faces it away, so
            // frame 0 shows the back and frame 24 the front
            float[] eye = { 0f, 13f, -62f };
            r.lookAt(eye, new float[] { 0f, 11.4f, 0f }, 22f);
            List<Light> lights = new ArrayList<>();
            lights.add(new Light(new float[] { -18f, 40f, -25f }, new float[] { 1.25f, 1.12f, 0.95f }, 70f));
            lights.add(new Light(new float[] { 30f, 18f, -10f }, new float[] { 0.35f, 0.5f, 0.85f }, 60f));
            lights.add(new Light(new float[] { 0f, 25f, 30f }, new float[] { 0.5f, 0.5f, 0.6f }, 50f));
            SoftwareRenderer.SurfaceShader suit = lit(lights, eye, 0.03f, 0f, (p, n, u, v) -> {
                float t = sample(fabric, u * 2f, v * 3f);
                return new float[] { 0.97f * t, 0.97f * t, 0.95f * t };
            });
            SoftwareRenderer.SurfaceShader trim = lit(lights, eye, 0.35f, 0f, flat(0.2f, 0.21f, 0.23f));
            SoftwareRenderer.SurfaceShader visor = lit(lights, eye, 2.5f, 0f, flat(0.12f, 0.09f, 0.03f));
            SoftwareRenderer.SurfaceShader stripe = lit(lights, eye, 0.2f, 0f, flat(0.9f, 0.4f, 0.08f));
            SoftwareRenderer.SurfaceShader shell = lit(lights, eye, 1.2f, 0f, flat(0.9f, 0.9f, 0.9f));
            for (int i = 0; i + 2 < indices.length; i += 3) {
                float[][] tri = new float[3][];
                int part = 0;
                for (int k = 0; k < 3; k++) {
                    int base = indices[i + k] * OrganismMesh.STRIDE;
                    int bone = Math.round(vertices[base + 8]);
                    part = Math.round(vertices[base + 9]);
                    float[] m = new float[16];
                    System.arraycopy(bones, bone * 16, m, 0, 16);
                    float[] p = Affine.transformPoint(m, vertices[base], vertices[base + 1], vertices[base + 2]);
                    float[] n = Affine.normalise(Affine.transformDirection(m, vertices[base + 3], vertices[base + 4], vertices[base + 5]));
                    tri[k] = SoftwareRenderer.vertex(p, n, vertices[base + 6], vertices[base + 7]);
                }
                SoftwareRenderer.SurfaceShader shader = switch (part) {
                    case OrganismMesh.PART_TRIM -> trim;
                    case OrganismMesh.PART_EYE -> visor;
                    case OrganismMesh.PART_SKIN -> shell;
                    default -> suit;
                };
                // An orange band round the middle of the torso, as the suit wears in the game
                if (part == OrganismMesh.PART_BODY && Math.round(vertices[indices[i] * OrganismMesh.STRIDE + 8]) == 0) {
                    float v = vertices[indices[i] * OrganismMesh.STRIDE + 7];
                    if (v > 0.5f && v < 0.58f) shader = stripe;
                }
                r.triangle(tri[0], tri[1], tri[2], shader);
            }
            sheet.getGraphics().drawImage(r.image(), (frame % FRAME_COLUMNS) * FRAME_WIDTH, (frame / FRAME_COLUMNS) * FRAME_HEIGHT, null);
        }
        return sheet;
    }

    /** Wrapping bilinear lookup of an image's brightness, 0 to 1. */
    private static float sample(BufferedImage image, float u, float v) {
        int w = image.getWidth(), h = image.getHeight();
        float x = frac(u) * w, y = frac(v) * h;
        int x0 = (int) x % w, y0 = (int) y % h, x1 = (x0 + 1) % w, y1 = (y0 + 1) % h;
        float fx = x - (int) x, fy = y - (int) y;
        float a = (image.getRGB(x0, y0) & 255) / 255f, b = (image.getRGB(x1, y0) & 255) / 255f;
        float c = (image.getRGB(x0, y1) & 255) / 255f, d = (image.getRGB(x1, y1) & 255) / 255f;
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }
}
