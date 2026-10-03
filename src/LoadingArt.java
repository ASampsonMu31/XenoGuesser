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
     * The cockpit: a narrowing room of panelled walls, a grated floor and a ceiling with light
     * strips, a long instrument console beneath a big three-pane window, pilot seats, side
     * consoles, pipes, and an old beige computer on a pedestal in the middle of the room.
     */
    private static BufferedImage cockpit(Properties layout) {
        SoftwareRenderer r = new SoftwareRenderer(WIDTH, HEIGHT, 2);
        float[] eye = { 0f, 21f, -6f };
        r.lookAt(eye, new float[] { 0f, 16f, 60f }, 62f);

        List<Light> lights = new ArrayList<>();
        lights.add(new Light(new float[] { -18f, 28f, 14f }, new float[] { 1.3f, 1.15f, 0.95f }, 34f));
        lights.add(new Light(new float[] { 18f, 28f, 14f }, new float[] { 1.3f, 1.15f, 0.95f }, 34f));
        lights.add(new Light(new float[] { 0f, 27f, 44f }, new float[] { 0.9f, 0.85f, 0.8f }, 30f));
        lights.add(new Light(new float[] { 0f, 20f, 80f }, new float[] { 0.35f, 0.5f, 0.9f }, 60f));   // starlight from the window
        lights.add(new Light(new float[] { 0f, 12f, 55f }, new float[] { 0.2f, 0.9f, 0.8f }, 14f));    // console glow
        lights.add(new Light(new float[] { -33f, 22f, 40f }, new float[] { 0.9f, 0.15f, 0.1f }, 12f)); // warning lamp

        SoftwareRenderer.SurfaceShader wall = lit(lights, eye, 0.25f, 0f, panels(0.42f, 0.45f, 0.5f, 8f));
        SoftwareRenderer.SurfaceShader darkWall = lit(lights, eye, 0.2f, 0f, panels(0.22f, 0.24f, 0.28f, 6f));
        SoftwareRenderer.SurfaceShader floor = lit(lights, eye, 0.35f, 0f, grating(0.3f, 0.31f, 0.33f));
        SoftwareRenderer.SurfaceShader trimMetal = lit(lights, eye, 0.6f, 0f, flat(0.16f, 0.17f, 0.2f));
        SoftwareRenderer.SurfaceShader frame = lit(lights, eye, 0.5f, 0f, flat(0.28f, 0.29f, 0.33f));
        SoftwareRenderer.SurfaceShader seat = lit(lights, eye, 0.3f, 0f, flat(0.32f, 0.12f, 0.1f));
        SoftwareRenderer.SurfaceShader beige = lit(lights, eye, 0.2f, 0f, flat(0.72f, 0.66f, 0.52f));
        SoftwareRenderer.SurfaceShader pipe = lit(lights, eye, 0.7f, 0f, flat(0.45f, 0.38f, 0.3f));

        // Room: the walls narrow towards the window; the ceiling dips at the front
        float backZ = -14f, frontZ = 70f;
        float[] floorBL = { -42f, 0f, backZ }, floorBR = { 42f, 0f, backZ }, floorFR = { 32f, 0f, frontZ }, floorFL = { -32f, 0f, frontZ };
        float[] ceilBL = { -42f, 32f, backZ }, ceilBR = { 42f, 32f, backZ }, ceilFR = { 32f, 27f, frontZ }, ceilFL = { -32f, 27f, frontZ };
        r.quad(floorBL, floorBR, floorFR, floorFL, floor);
        r.quad(ceilBL, ceilFL, ceilFR, ceilBR, darkWall);
        r.quad(floorBL, floorFL, ceilFL, ceilBL, wall);
        r.quad(floorBR, ceilBR, ceilFR, floorFR, wall);
        r.quad(floorFL, floorFR, ceilFR, ceilFL, darkWall);

        // Light strips along the ceiling
        for (int side = -1; side <= 1; side += 2) {
            r.box(new float[] { side * 18f, 30.6f, 24f }, 1.2f, 0.4f, 30f, 0f, lit(lights, eye, 0f, 1.6f, flat(1f, 0.92f, 0.75f)));
        }
        // Pipes along the tops of the walls
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < 3; k++) {
                float x0 = side * (40f - k * 1.6f), x1 = side * (30.5f - k * 1.6f);
                r.cylinder(new float[] { x0, 27.5f - k * 1.5f, backZ }, new float[] { x1, 24.5f - k * 1.5f, frontZ - 2f }, 0.6f, 10, pipe);
            }
        }

        // Window: three panes between struts; the hole is cut once the room is drawn
        float[][] window = { { -27f, 11f, frontZ - 0.2f }, { 27f, 11f, frontZ - 0.2f }, { 22f, 25.5f, frontZ - 0.2f }, { -22f, 25.5f, frontZ - 0.2f } };
        float[][] windowScreen = new float[4][];
        for (int i = 0; i < 4; i++) windowScreen[i] = r.project(window[i]);
        r.cutOut((x, y) -> insidePolygon(windowScreen, x, y));
        // Frame and struts
        for (int i = 0; i < 4; i++) {
            float[] a = window[i], b = window[(i + 1) % 4];
            r.cylinder(a, b, 1.1f, 8, frame);
        }
        for (int s = -1; s <= 1; s += 2) {
            r.cylinder(new float[] { s * 9.5f, 11f, frontZ - 0.6f }, new float[] { s * 7.8f, 25.5f, frontZ - 0.6f }, 0.9f, 8, frame);
        }

        // Main console under the window: a sloping desk covered in instruments
        float consoleTilt = -0.55f;
        r.box(new float[] { 0f, 4.5f, 64f }, 30f, 4.5f, 6f, 0f, trimMetal);
        r.box(new float[] { 0f, 9.4f, 60.5f }, 29f, 0.6f, 6.5f, 0f, consoleTilt, darkWall);
        Random rand = new Random(7L);
        float[][] palette = { { 0.2f, 1f, 0.5f }, { 1f, 0.6f, 0.1f }, { 0.2f, 0.7f, 1f }, { 1f, 0.2f, 0.25f }, { 0.9f, 0.9f, 0.3f } };
        float[] consoleNormal = Affine.transformDirection(Affine.rotationX(consoleTilt), 0f, 1f, 0f);
        float[] consoleAlong = Affine.transformDirection(Affine.rotationX(consoleTilt), 0f, 0f, 1f);
        for (int i = 0; i < 7; i++) {
            float x = -24f + i * 8f;
            float[] centre = Affine.add(Affine.add(new float[] { x, 9.4f, 60.5f }, consoleNormal, 0.65f), consoleAlong, 1.2f);
            float[] c = palette[rand.nextInt(palette.length)];
            r.box(centre, 3.0f, 0.1f, 2.2f, 0f, consoleTilt, lit(lights, eye, 0.8f, 1.4f, display(c[0], c[1], c[2], i)));
            // Rows of lit buttons below each display
            for (int bRow = 0; bRow < 2; bRow++) {
                for (int bCol = 0; bCol < 5; bCol++) {
                    float[] button = Affine.add(Affine.add(new float[] { x - 2.4f + bCol * 1.2f, 9.4f, 60.5f }, consoleNormal, 0.75f),
                            consoleAlong, -2.6f - bRow * 1.1f);
                    float[] bc = palette[rand.nextInt(palette.length)];
                    boolean on = rand.nextFloat() < 0.6f;
                    r.box(button, 0.38f, 0.2f, 0.38f, 0f, consoleTilt, lit(lights, eye, 0.6f, on ? 1.5f : 0.1f,
                            flat(bc[0] * (on ? 1f : 0.3f), bc[1] * (on ? 1f : 0.3f), bc[2] * (on ? 1f : 0.3f))));
                }
            }
        }
        // Throttle levers in the middle
        for (int k = -1; k <= 1; k++) {
            r.cylinder(new float[] { k * 1.4f, 10f, 57f }, new float[] { k * 1.4f + 0.3f, 13.5f, 55.5f }, 0.25f, 6, trimMetal);
            r.box(new float[] { k * 1.4f + 0.3f, 13.8f, 55.4f }, 0.5f, 0.5f, 0.5f, 0f, lit(lights, eye, 0.6f, 0.3f, flat(0.8f, 0.15f, 0.1f)));
        }

        // Pilot seats facing the window
        for (int side = -1; side <= 1; side += 2) {
            float sx = side * 15f;
            r.box(new float[] { sx, 5f, 47f }, 4f, 1.2f, 4f, 0f, seat);
            r.box(new float[] { sx, 11f, 43.5f }, 4f, 6.5f, 1.2f, 0f, -0.15f, seat);
            r.box(new float[] { sx, 18.6f, 43f }, 2.6f, 1.6f, 1.0f, 0f, -0.15f, seat);
            r.cylinder(new float[] { sx, 0f, 47f }, new float[] { sx, 4f, 47f }, 0.8f, 8, trimMetal);
        }

        // Side consoles with screens and gauges along both walls
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < 3; k++) {
                float z = 14f + k * 16f;
                float wallX = side * (42f - (z - backZ) / (frontZ - backZ) * 10f);
                float yaw = side * (float) Math.atan2(10f, frontZ - backZ);
                r.box(new float[] { wallX - side * 3f, 6f, z }, 3f, 6f, 6.5f, yaw, trimMetal);
                float[] c = palette[(k + (side > 0 ? 2 : 0)) % palette.length];
                r.box(new float[] { wallX - side * 0.6f, 18f, z }, 0.3f, 3.4f, 5f, yaw, lit(lights, eye, 0.8f, 1.2f, display(c[0], c[1], c[2], 20 + k + side)));
                for (int gauge = 0; gauge < 3; gauge++) {
                    float gz = z - 4f + gauge * 4f;
                    r.cylinder(new float[] { wallX - side * 0.4f, 11f, gz }, new float[] { wallX - side * 0.9f, 11f, gz }, 1.3f, 14,
                            lit(lights, eye, 0.5f, 0.6f, (p, n, u, v) -> new float[] { 0.85f, 0.8f, 0.6f }));
                }
            }
        }
        // A red warning lamp high on the left wall
        r.box(new float[] { -33.5f, 22f, 40f }, 0.6f, 0.9f, 0.9f, 0f, lit(lights, eye, 0f, 2f, flat(1f, 0.15f, 0.1f)));

        // The computer: an old beige monitor and keyboard on a desk in the middle of the room,
        // built at scale k so its screen is large enough to read
        float cz = 23f, k = 1.35f;
        r.box(new float[] { 0f, 3f * k, cz + 1f * k }, 5f * k, 3f * k, 4f * k, 0f, trimMetal);
        r.box(new float[] { 0f, 6.3f * k, cz - 0.5f * k }, 7.5f * k, 0.35f * k, 5.5f * k, 0f, trimMetal);
        r.box(new float[] { 0f, 11.0f * k, cz + 1.8f * k }, 4.6f * k, 4.1f * k, 4.0f * k, 0f, beige);
        r.box(new float[] { 0f, 10.8f * k, cz + 5.0f * k }, 3.3f * k, 3.0f * k, 2.0f * k, 0f, beige);
        r.box(new float[] { 0f, 6.9f * k, cz + 1.8f * k }, 2.6f * k, 0.35f * k, 2.2f * k, 0f, beige);
        r.box(new float[] { 0f, 6.9f * k, cz - 3.6f * k }, 4.2f * k, 0.35f * k, 1.4f * k, 0f, -0.12f, beige);
        for (int key = 0; key < 24; key++) {
            float kx = (-3.6f + (key % 12) * 0.65f) * k, kz = cz + (-4.1f + (key / 12) * 0.85f) * k;
            r.box(new float[] { kx, 7.35f * k, kz }, 0.24f * k, 0.1f * k, 0.27f * k, 0f, -0.12f, lit(lights, eye, 0.3f, 0f, flat(0.55f, 0.5f, 0.42f)));
        }
        // The screen: dark phosphor glass set in its bezel; the text is drawn live
        float screenZ = cz + (1.8f - 4.0f) * k - 0.05f;
        float[][] screen = { { -3.6f * k, 7.9f * k, screenZ }, { 3.6f * k, 7.9f * k, screenZ }, { 3.6f * k, 13.9f * k, screenZ }, { -3.6f * k, 13.9f * k, screenZ } };
        r.quad(screen[0], screen[1], screen[2], screen[3], lit(lights, eye, 1.2f, 0.25f, flat(0.03f, 0.12f, 0.05f)));
        String[] names = { "screen.bottomLeft", "screen.bottomRight", "screen.topRight", "screen.topLeft" };
        for (int i = 0; i < 4; i++) {
            float[] sp = r.project(screen[i]);
            layout.setProperty(names[i], sp[0] + "," + sp[1]);
        }
        String[] windowNames = { "window.bottomLeft", "window.bottomRight", "window.topRight", "window.topLeft" };
        for (int i = 0; i < 4; i++) layout.setProperty(windowNames[i], windowScreen[i][0] + "," + windowScreen[i][1]);

        // Where the spaceman stands: on the floor to the left
        float[] feet = r.project(new float[] { -22f, 0f, 30f });
        float[] head = r.project(new float[] { -22f, 22.5f, 30f });
        layout.setProperty("spaceman.feet", feet[0] + "," + feet[1]);
        layout.setProperty("spaceman.height", String.valueOf(feet[1] - head[1]));
        return r.image();
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
