import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import com.xenoguesser.math.Vector3;

/**
 * The fixed art of the main menu and the player's suit, pre-rendered once and kept in
 * assets/art (it is not part of the procedural world, so it never needs regenerating):
 * <ul>
 *   <li>the spaceman turntable: the player in their suit seen from 48 angles, with grey
 *       boots, gloves, pack and collar, and again in multiplayer's blue (Employee 119-A,
 *       the host) and red (119-B, the joiner);</li>
 *   <li>the suit's crumpled white fabric, also used on the player's body in the game.</li>
 * </ul>
 * Anything missing is generated when first asked for; run main() to regenerate it all.
 */
public final class LoadingArt {

    public static final String DIR = GamePaths.HOME + "assets/art";
    public static final String SPACEMAN = DIR + "/spaceman_turntable.png";
    // Multiplayer's two players (M6): their packs and helmet collar in each one's colour, as lit in the world
    public static final String SPACEMAN_BLUE = DIR + "/spaceman_turntable_blue.png";
    public static final String SPACEMAN_RED = DIR + "/spaceman_turntable_red.png";
    private static final float[] TRIM_GREY = { 0.2f, 0.21f, 0.23f };
    // The main menu's background: a frame of the game itself, made with -Dxenoguesser.menushot
    public static final String MENU = DIR + "/main_menu.png";
    // The backgrounds the main menu chooses between (background_1.png and on; see MainMenu)
    public static final String MENU_BACKGROUNDS = DIR + "/menu";
    public static final String FABRIC = DIR + "/suit_fabric.png";

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
            if (!new File(SPACEMAN).exists()) renderAll();
            ensureColoured();
        } catch (Exception e) {
            System.err.println("Could not make the loading screen art: " + e.getMessage());
        }
    }

    /** Makes the blue and red turntables if they're missing (a few seconds each). */
    private static void ensureColoured() throws Exception {
        if (new File(SPACEMAN_BLUE).exists() && new File(SPACEMAN_RED).exists()) return;
        BufferedImage fabric = ImageIO.read(new File(FABRIC));
        if (!new File(SPACEMAN_BLUE).exists()) {
            ImageIO.write(spaceman(fabric, Multiplayer.Role.HOST.litTrimColour()), "png", new File(SPACEMAN_BLUE));
        }
        if (!new File(SPACEMAN_RED).exists()) {
            ImageIO.write(spaceman(fabric, Multiplayer.Role.JOIN.litTrimColour()), "png", new File(SPACEMAN_RED));
        }
    }

    /** The turntable for a multiplayer role's colour. */
    public static String spaceman(Multiplayer.Role role) {
        return role == Multiplayer.Role.HOST ? SPACEMAN_BLUE : SPACEMAN_RED;
    }

    public static boolean isReady() {
        return new File(FABRIC).exists() && new File(SPACEMAN).exists();
    }

    private static void renderAll() throws Exception {
        BufferedImage fabric = ImageIO.read(new File(FABRIC));
        ImageIO.write(spaceman(fabric, TRIM_GREY), "png", new File(SPACEMAN));
        ImageIO.write(spaceman(fabric, Multiplayer.Role.HOST.litTrimColour()), "png", new File(SPACEMAN_BLUE));
        ImageIO.write(spaceman(fabric, Multiplayer.Role.JOIN.litTrimColour()), "png", new File(SPACEMAN_RED));
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

    private static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    // ==========================================
    //          SPACEMAN
    // ==========================================

    /**
     * The player in a white spacesuit with a helmet and dark visor, standing, seen from 48
     * evenly spaced angles; frames run left to right, top to bottom in an 8-wide grid.
     * Lit like the cockpit's left side: warm from above, cool from the window ahead. The boots,
     * gloves, back pack and helmet collar are grey; in multiplayer's sheets the packs and
     * collar (OrganismMesh.PART_SUIT_COLOUR) are in trimColour instead.
     */
    private static BufferedImage spaceman(BufferedImage fabric, float[] trimColour) {
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
            SoftwareRenderer.SurfaceShader trim = lit(lights, eye, 0.35f, 0f, flat(TRIM_GREY[0], TRIM_GREY[1], TRIM_GREY[2]));
            SoftwareRenderer.SurfaceShader colouredTrim = lit(lights, eye, 0.35f, 0f, flat(trimColour[0], trimColour[1], trimColour[2]));
            SoftwareRenderer.SurfaceShader visor = lit(lights, eye, 2.5f, 0f, flat(0.12f, 0.09f, 0.03f));
            SoftwareRenderer.SurfaceShader stripe = lit(lights, eye, 0.2f, 0f, flat(0.9f, 0.4f, 0.08f));
            SoftwareRenderer.SurfaceShader shell = lit(lights, eye, 1.2f, 0f, flat(0.9f, 0.9f, 0.9f));
            for (int i = 0; i + 2 < indices.length; i += 3) {
                float[][] tri = new float[3][];
                int part = 0, bone = 0;
                for (int k = 0; k < 3; k++) {
                    int base = indices[i + k] * OrganismMesh.STRIDE;
                    bone = Math.round(vertices[base + 8]);
                    part = Math.round(vertices[base + 9]);
                    float[] m = new float[16];
                    System.arraycopy(bones, bone * 16, m, 0, 16);
                    float[] p = Affine.transformPoint(m, vertices[base], vertices[base + 1], vertices[base + 2]);
                    float[] n = Affine.normalise(Affine.transformDirection(m, vertices[base + 3], vertices[base + 4], vertices[base + 5]));
                    tri[k] = SoftwareRenderer.vertex(p, n, vertices[base + 6], vertices[base + 7]);
                }
                SoftwareRenderer.SurfaceShader shader = switch (part) {
                    case OrganismMesh.PART_TRIM -> trim;
                    case OrganismMesh.PART_SUIT_COLOUR -> colouredTrim;
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
