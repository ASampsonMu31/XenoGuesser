import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import java.util.ArrayList;
import java.util.List;

/**
 * The inside of the landing pod, where the player (and in multiplayer the partner) waits as
 * the world loads: a small round cabin no bigger than the pod seen outside, its walls leaning
 * in with the hull, an oval glass door at the front, the old computer at the back, a console
 * on either side (the lights switch on the left's, as one faces the door, and the wheeled
 * chair by the right's).
 *
 * In the pod's own frame, as LandingPod's: y up from the ground under its middle, the door
 * towards +z, x to the left as one faces the door. 10 units to the metre.
 */
public class PodInterior {

    // Part kinds, read by fs_pod to choose colour, gloss and glow
    static final int PART_WALL = 0, PART_FLOOR = 1, PART_METAL = 2, PART_DARK = 3, PART_TRIM = 4, PART_LAMP = 5,
            PART_SCOPE = 6, PART_LIGHT = 7, PART_BEIGE = 8, PART_FABRIC = 9, PART_RUBBER = 10,
            PART_TERMINAL = 11, PART_BUTTON = 12, PART_PANEL = 13, PART_DIAL = 14, PART_SWITCH = 15,
            PART_KEYS = 16, PART_PIPE = 17;
    private static final int PARTS = 20;
    // The indicator lights' and buttons' colours, by v: red, green, amber, blue
    static final int RED = 0, GREEN = 1, AMBER = 2, BLUE = 3;

    // Bones: the cabin itself, the wheeled chair, the lights switch's lever and the hatch
    static final int CABIN = 0, CHAIR = 1, SWITCH = 2, HATCH = 3, BONES = 8;

    // The floor (the hatch's platform outside), and how far in from the hull the walls stand
    static final float FLOOR = LandingPod.PLATFORM_HEIGHT;
    static final float WALL = 2f;
    // Where the hull's cone gives way to its rounded top, and the top of the cabin inside it
    private static final float CONE_TOP = LandingPod.HULL_TOP - 9f;
    static final float CEILING = LandingPod.HULL_TOP - WALL;
    // How far out from the middle the ceiling lamp's ring reaches
    static final float LAMP_REACH = 4.5f;

    // The door: an oval taller than wide, its foot just above the floor (singleplayer's, and
    // multiplayer's, wider for two side by side, as the doorway outside is)
    private final float doorHalfWidth, doorHalfHeight, doorMiddle;

    private Shader shader, glassShader;
    private OrganismMesh mesh, glass;
    private final float[] bones = new float[BONES * 16];
    private final float[] partColours = new float[PARTS * 3];
    private final float[] partShine = new float[PARTS];
    private final float[] partGlow = new float[PARTS];
    private int terminalTexture;

    public PodInterior(boolean multiplayer) {
        doorHalfWidth = multiplayer ? 8f : 6f;
        doorHalfHeight = multiplayer ? 12f : 11f;
        doorMiddle = FLOOR + 1.2f + doorHalfHeight;
        for (int bone = 0; bone < BONES; bone++) System.arraycopy(Affine.identity(), 0, bones, bone * 16, 16);
        colour(PART_WALL, 0.78f, 0.79f, 0.8f, 0.25f, 0f);
        colour(PART_FLOOR, 0.36f, 0.37f, 0.39f, 0.4f, 0f);
        colour(PART_METAL, 0.55f, 0.57f, 0.6f, 0.6f, 0f);
        colour(PART_DARK, 0.06f, 0.065f, 0.075f, 0.5f, 0f);
        colour(PART_TRIM, 0.92f, 0.45f, 0.1f, 0.3f, 0f);
        colour(PART_LAMP, 1f, 0.95f, 0.85f, 0f, 1.2f);
        colour(PART_SCOPE, 0f, 0f, 0f, 0.9f, 1f);
        colour(PART_LIGHT, 0f, 0f, 0f, 0.9f, 1.6f);
        colour(PART_BEIGE, 0.78f, 0.73f, 0.6f, 0.2f, 0f);
        colour(PART_FABRIC, 0.18f, 0.2f, 0.26f, 0.05f, 0f);
        colour(PART_RUBBER, 0.05f, 0.05f, 0.055f, 0.15f, 0f);
        colour(PART_TERMINAL, 0f, 0f, 0f, 0.9f, 1.1f);
        colour(PART_BUTTON, 0f, 0f, 0f, 0.7f, 0.7f);
        colour(PART_PANEL, 0.2f, 0.215f, 0.235f, 0.35f, 0f);
        colour(PART_DIAL, 0.85f, 0.83f, 0.75f, 0.5f, 0.12f);
        colour(PART_SWITCH, 0.75f, 0.1f, 0.08f, 0.5f, 0f);
        colour(PART_KEYS, 0.7f, 0.66f, 0.55f, 0.15f, 0f);
        colour(PART_PIPE, 0.42f, 0.44f, 0.46f, 0.5f, 0f);
    }

    private void colour(int part, float r, float g, float b, float shine, float glow) {
        partColours[part * 3] = r;
        partColours[part * 3 + 1] = g;
        partColours[part * 3 + 2] = b;
        partShine[part] = shine;
        partGlow[part] = glow;
    }

    /** The cabin's radius inside at a height (0 at the very top). */
    static float radiusAt(float y) {
        if (y <= CONE_TOP) return LandingPod.radiusAtHeight(y) - WALL;
        // Under the hull's rounded top (an ellipse 9 high over the top's radius), less the wall
        float a = (float) Math.asin(Math.min(1f, (y - CONE_TOP) / (9f - WALL)));
        return Math.max(0f, (LandingPod.TOP_RADIUS - WALL) * (float) Math.cos(a));
    }

    /** The door's half width and half height, and the height of its middle. */
    float doorHalfWidth() {
        return doorHalfWidth;
    }

    float doorHalfHeight() {
        return doorHalfHeight;
    }

    float doorMiddle() {
        return doorMiddle;
    }

    public void initialise(GL3 gl) {
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_pod.txt", GamePaths.HOME + "assets/shaders/fs_pod.txt");
        OrganismMesh.Builder b = new OrganismMesh.Builder();
        buildShell(b);
        PodFurniture.build(b);
        mesh = b.build(gl);
        glassShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_pod.txt", GamePaths.HOME + "assets/shaders/fs_pod_window.txt");
        glass = buildGlass().build(gl);
        int[] id = new int[1];
        gl.glGenTextures(1, id, 0);
        terminalTexture = id[0];
    }

    public void dispose(GL3 gl) {
        if (mesh != null) mesh.dispose(gl);
        if (glass != null) glass.dispose(gl);
        if (terminalTexture != 0) gl.glDeleteTextures(1, new int[] { terminalTexture }, 0);
    }

    // ==========================================
    //          THE SHELL
    // ==========================================

    /** The cabin's profile from the floor to the top, as {height, radius} rows. */
    private static List<float[]> profile() {
        List<float[]> rows = new ArrayList<>();
        for (float y = FLOOR; y < CONE_TOP; y += 1f) rows.add(new float[] { y, radiusAt(y) });
        int dome = 10;
        for (int i = 0; i <= dome; i++) {
            float a = (float) (i / (double) dome * Math.PI * 0.5);
            float y = CONE_TOP + 9f * (float) Math.sin(a) - WALL * (float) Math.sin(a);
            float r = LandingPod.TOP_RADIUS * (float) Math.cos(a) - WALL * (float) Math.cos(a);
            rows.add(new float[] { y, Math.max(0f, r) });
        }
        return rows;
    }

    /** How far round from the front (either way, radians) the doorway reaches at a height and radius; 0 where it doesn't. */
    private float doorReach(float y, float r) {
        float up = (y - doorMiddle) / doorHalfHeight;
        if (Math.abs(up) >= 1f || r <= 0f) return 0f;
        float across = doorHalfWidth * (float) Math.sqrt(1f - up * up);
        return (float) Math.asin(Math.min(1f, across / r));
    }

    private void buildShell(OrganismMesh.Builder b) {
        b.bone(CABIN).resetTransform();
        List<float[]> rows = profile();
        int around = 64;
        // The walls and the domed top, row by row, each row stopping short either side of the
        // doorway where it crosses it, so the doorway's edge is the oval itself
        b.part(PART_WALL);
        for (int j = 0; j + 1 < rows.size(); j++) {
            float y0 = rows.get(j)[0], r0 = rows.get(j)[1], y1 = rows.get(j + 1)[0], r1 = rows.get(j + 1)[1];
            float h0 = doorReach(y0, r0), h1 = doorReach(y1, r1);
            float slope = (r1 - r0) / Math.max(1e-4f, y1 - y0);
            int first = -1;
            for (int k = 0; k <= around; k++) {
                float t = k / (float) around;
                float a0 = h0 + (float) (2 * Math.PI - 2 * h0) * t, a1 = h1 + (float) (2 * Math.PI - 2 * h1) * t;
                int v0 = wallVertex(b, a0, y0, r0, slope), v1 = wallVertex(b, a1, y1, r1, slope);
                if (first >= 0) {
                    b.triangle(first, v0, first + 1);
                    b.triangle(v0, v1, first + 1);
                }
                first = v0;
            }
        }
        // The floor: grating out to the walls
        b.part(PART_FLOOR);
        float floorR = radiusAt(FLOOR) + 0.3f;
        int centre = b.vertex(0f, FLOOR, 0f, 0f, 1f, 0f, 0.5f, 0.5f);
        int previous = -1;
        for (int k = 0; k <= around; k++) {
            double a = k / (double) around * Math.PI * 2;
            int v = b.vertex((float) Math.sin(a) * floorR, FLOOR, (float) Math.cos(a) * floorR, 0f, 1f, 0f, 0f, 0f);
            if (previous >= 0) b.triangle(centre, previous, v);
            previous = v;
        }
        buildDoorFrame(b);
        buildRibs(b);
        buildLamp(b);
    }

    private static int wallVertex(OrganismMesh.Builder b, float angle, float y, float r, float slope) {
        float s = (float) Math.sin(angle), c = (float) Math.cos(angle);
        float[] n = Affine.normalise(new float[] { s, -slope, c });
        return b.vertex(s * r, y, c * r, n[0], n[1], n[2], angle / (float) (2 * Math.PI), (y - FLOOR) / (CEILING - FLOOR));
    }

    /** A point on the doorway's edge, φ round the oval, scale times its size, out from the wall by depth (towards the hull). */
    private float[] doorPoint(float phi, float scale, float depth) {
        float y = doorMiddle + doorHalfHeight * scale * (float) Math.sin(phi);
        float r = radiusAt(y) + depth;
        float across = doorHalfWidth * scale * (float) Math.cos(phi);
        float a = (float) Math.asin(Math.max(-1f, Math.min(1f, across / Math.max(1f, r))));
        return new float[] { (float) Math.sin(a) * r, y, (float) Math.cos(a) * r };
    }

    /** The door's metal frame: a lip round it on the wall inside, and its sides through the wall's thickness. */
    private void buildDoorFrame(OrganismMesh.Builder b) {
        b.part(PART_METAL);
        int steps = 48;
        float[][] lipOuter = new float[steps + 1][], lipInner = new float[steps + 1][], edge = new float[steps + 1][], hull = new float[steps + 1][];
        for (int i = 0; i <= steps; i++) {
            float phi = (float) (i / (double) steps * Math.PI * 2);
            lipOuter[i] = doorPoint(phi, 1.16f, -0.15f);
            lipInner[i] = doorPoint(phi, 1.0f, -0.9f);
            edge[i] = doorPoint(phi, 1.0f, 0f);
            hull[i] = doorPoint(phi, 1.0f, WALL);
        }
        band(b, lipOuter, lipInner);
        band(b, lipInner, edge);
        b.part(PART_DARK);
        band(b, edge, hull);
        // Bolts round the lip
        b.part(PART_TRIM);
        for (int i = 0; i < 16; i++) {
            float phi = (float) (i / 16.0 * Math.PI * 2);
            float[] p = doorPoint(phi, 1.09f, -0.75f);
            b.box(p[0], p[1], p[2], 0.6f, 0.6f, 0.6f);
        }
    }

    /** A strip of quads between two rows of points, its normals worked out from the quads. */
    private static void band(OrganismMesh.Builder b, float[][] from, float[][] to) {
        int previousFrom = -1, previousTo = -1;
        for (int i = 0; i < from.length; i++) {
            int j = Math.min(from.length - 1, i + 1), k = Math.max(0, i - 1);
            float[] along = Affine.subtract(from[j], from[k]);
            float[] across = Affine.subtract(to[i], from[i]);
            float[] n = Affine.normalise(Affine.cross(along, across));
            int f = b.vertex(from[i][0], from[i][1], from[i][2], n[0], n[1], n[2], i / (float) (from.length - 1), 0f);
            int t = b.vertex(to[i][0], to[i][1], to[i][2], n[0], n[1], n[2], i / (float) (from.length - 1), 1f);
            if (previousFrom >= 0) {
                b.triangle(previousFrom, f, previousTo);
                b.triangle(f, t, previousTo);
            }
            previousFrom = f;
            previousTo = t;
        }
    }

    /** Ribs up the walls between the consoles, the computer and the door; a pipe round under the dome; a kick plate round the floor. */
    private void buildRibs(OrganismMesh.Builder b) {
        for (int i = 0; i < 4; i++) {
            float a = (float) ((i + 0.5) * Math.PI * 0.5);
            float s = (float) Math.sin(a), c = (float) Math.cos(a);
            float y0 = FLOOR + 0.5f, y1 = CONE_TOP - 0.5f;
            float r0 = radiusAt(y0) - 0.4f, r1 = radiusAt(y1) - 0.4f;
            strut(b, PART_METAL, new float[] { s * r0, y0, c * r0 }, new float[] { s * r1, y1, c * r1 }, 0.55f, 0.5f);
        }
        b.resetTransform().part(PART_PIPE);
        float doorGap = doorReach(doorMiddle, radiusAt(doorMiddle)) + 0.1f;
        for (float[] ring : new float[][] { { CONE_TOP - 2f, 0.55f, 0f }, { FLOOR + 0.8f, 0.8f, doorGap } }) {
            float y = ring[0], r = radiusAt(y) - ring[1];
            int steps = 72;
            java.util.List<float[]> path = new java.util.ArrayList<>();
            for (int i = 0; i <= steps; i++) {
                double a = ring[2] + i / (double) steps * (Math.PI * 2 - 2 * ring[2]);
                path.add(new float[] { (float) Math.sin(a) * r, y, (float) Math.cos(a) * r });
            }
            tube(b, path, ring[1]);
        }
        b.resetTransform();
    }

    /** A smooth round tube along a path. */
    static void tube(OrganismMesh.Builder b, java.util.List<float[]> path, float radius) {
        int around = 10, previous = -1;
        for (int i = 0; i < path.size(); i++) {
            float[] p = path.get(i);
            float[] along = Affine.normalise(Affine.subtract(path.get(Math.min(path.size() - 1, i + 1)), path.get(Math.max(0, i - 1))));
            float[] side = Affine.normalise(Affine.cross(along, Math.abs(along[1]) > 0.9f ? new float[] { 1f, 0f, 0f } : new float[] { 0f, 1f, 0f }));
            float[] up = Affine.cross(side, along);
            int first = -1;
            for (int k = 0; k <= around; k++) {
                double a = k / (double) around * Math.PI * 2;
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                float[] n = { side[0] * cs + up[0] * sn, side[1] * cs + up[1] * sn, side[2] * cs + up[2] * sn };
                int v = b.vertex(p[0] + n[0] * radius, p[1] + n[1] * radius, p[2] + n[2] * radius, n[0], n[1], n[2], k / (float) around, i);
                if (first < 0) first = v;
            }
            if (previous >= 0) {
                for (int k = 0; k < around; k++) {
                    b.triangle(previous + k, first + k, previous + k + 1);
                    b.triangle(first + k, first + k + 1, previous + k + 1);
                }
            }
            previous = first;
        }
    }

    /** The ceiling lamp: a glowing disc in a metal ring at the top of the dome. */
    private void buildLamp(OrganismMesh.Builder b) {
        float y = CEILING - 1.8f;
        b.resetTransform().part(PART_METAL);
        b.box(0f, y + 0.5f, 0f, LAMP_REACH * 2f, 0.8f, LAMP_REACH * 2f);
        b.part(PART_LAMP);
        int centre = b.vertex(0f, y, 0f, 0f, -1f, 0f, 0.5f, 0.5f);
        int previous = -1;
        for (int k = 0; k <= 32; k++) {
            double a = k / 32.0 * Math.PI * 2;
            int v = b.vertex((float) Math.sin(a) * 3.8f, y, (float) Math.cos(a) * 3.8f, 0f, -1f, 0f, 0f, 0f);
            if (previous >= 0) b.triangle(centre, v, previous);
            previous = v;
        }
    }

    static void strut(OrganismMesh.Builder b, int part, float[] from, float[] to, float r0, float r1) {
        float[] span = Affine.subtract(to, from);
        float[] hint = Math.abs(span[1]) > 0.9f * Affine.length(span) ? new float[] { 0f, 0f, 1f } : new float[] { 0f, 1f, 0f };
        b.transform(Affine.frame(from, span, hint, 1f, 1f, 1f));
        OrganismParts.limb(b, part, Affine.length(span), r0, r1);
        b.resetTransform();
    }

    // ==========================================
    //          DRAWING
    // ==========================================

    /** Moves a bone (the chair, the switch's lever, the hatch) to a pose in the cabin's frame. */
    void setBone(int bone, float[] matrix) {
        System.arraycopy(matrix, 0, bones, bone * 16, 16);
    }

    /** The lights inside, as fs_pod takes them: four points, their colours and how far each reaches. */
    static final class Lighting {
        final float[] positions = new float[12], colours = new float[12], reach = new float[4];
        final float[] ambient = new float[3];
        float interference;
        // How bright the ceiling lamp's own disc is, 0 (off) to 1
        float lamp = 1f;

        void set(int i, float x, float y, float z, float r, float g, float b, float distance) {
            positions[i * 3] = x;
            positions[i * 3 + 1] = y;
            positions[i * 3 + 2] = z;
            colours[i * 3] = r;
            colours[i * 3 + 1] = g;
            colours[i * 3 + 2] = b;
            reach[i] = distance;
        }
    }

    private static final float[] INDICATORS = { 1f, 0.12f, 0.08f, 0.2f, 1f, 0.3f, 1f, 0.68f, 0.1f, 0.25f, 0.55f, 1f };

    public void render(GL3 gl, Matrix4 viewProjection, float[] eye, Lighting lighting, float time) {
        if (mesh == null) return;
        shader.use(gl);
        int id = shader.getID();
        shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        shader.setFloat(gl, "viewPos", eye[0], eye[1], eye[2]);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(id, "bones"), BONES, false, bones, 0);
        gl.glUniform3fv(gl.glGetUniformLocation(id, "lightPos"), 4, lighting.positions, 0);
        gl.glUniform3fv(gl.glGetUniformLocation(id, "lightColour"), 4, lighting.colours, 0);
        gl.glUniform1fv(gl.glGetUniformLocation(id, "lightReach"), 4, lighting.reach, 0);
        gl.glUniform3fv(gl.glGetUniformLocation(id, "partColour"), PARTS, partColours, 0);
        gl.glUniform1fv(gl.glGetUniformLocation(id, "partShine"), PARTS, partShine, 0);
        float[] glow = partGlow.clone();
        glow[PART_LAMP] *= lighting.lamp;
        gl.glUniform1fv(gl.glGetUniformLocation(id, "partGlow"), PARTS, glow, 0);
        gl.glUniform3fv(gl.glGetUniformLocation(id, "indicatorColours"), 4, INDICATORS, 0);
        shader.setFloat(gl, "ambientLight", lighting.ambient[0], lighting.ambient[1], lighting.ambient[2]);
        shader.setFloat(gl, "time", time);
        shader.setFloat(gl, "interference", lighting.interference);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, terminalTexture);
        shader.setInt(gl, "terminalTexture", 0);
        gl.glDisable(GL.GL_CULL_FACE);
        mesh.render(gl);
        gl.glEnable(GL.GL_CULL_FACE);
    }

    /** The door's glass, filling the oval part way through the wall; u and v run across it, (0.5, 0.5) its middle. */
    private OrganismMesh.Builder buildGlass() {
        OrganismMesh.Builder b = new OrganismMesh.Builder();
        b.bone(HATCH).part(PART_DARK);
        int rings = 10, around = 48;
        int[][] at = new int[rings + 1][around + 1];
        for (int i = 0; i <= rings; i++) {
            float scale = i / (float) rings;
            for (int k = 0; k <= around; k++) {
                float phi = (float) (k / (double) around * Math.PI * 2);
                float[] p = doorPoint(phi, Math.max(1e-3f, scale), WALL * 0.45f);
                float[] n = Affine.normalise(new float[] { p[0], 0f, p[2] });
                at[i][k] = b.vertex(p[0], p[1], p[2], n[0], n[1], n[2], 0.5f + 0.5f * scale * (float) Math.cos(phi), 0.5f + 0.5f * scale * (float) Math.sin(phi));
            }
        }
        for (int i = 0; i < rings; i++) {
            for (int k = 0; k < around; k++) {
                b.triangle(at[i][k], at[i + 1][k], at[i][k + 1]);
                b.triangle(at[i][k + 1], at[i + 1][k], at[i + 1][k + 1]);
            }
        }
        return b;
    }

    /**
     * The door's glass, over everything else: fog and plasma 0..1, cabin the lamp, fogLight the
     * light through the fog, gravity as felt in the cabin (for which way its drops run).
     */
    public void renderGlass(GL3 gl, Matrix4 viewProjection, float[] eye, float fog, float plasma, float cabin, float[] fogLight,
                            float[] gravity, float time) {
        if (glass == null) return;
        glassShader.use(gl);
        glassShader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        glassShader.setFloat(gl, "viewPos", eye[0], eye[1], eye[2]);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(glassShader.getID(), "bones"), BONES, false, bones, 0);
        glassShader.setFloat(gl, "fog", fog);
        glassShader.setFloat(gl, "plasma", plasma);
        glassShader.setFloat(gl, "cabinLight", cabin);
        glassShader.setFloat(gl, "fogLight", fogLight[0], fogLight[1], fogLight[2]);
        glassShader.setFloat(gl, "time", time);
        glassShader.setFloat(gl, "glassSize", doorHalfWidth, doorHalfHeight);
        glassShader.setFloat(gl, "waterTint", waterTint[0], waterTint[1], waterTint[2]);
        // (down the glass as gravity pulls, across it as much as gravity leans; on a still door, down)
        float across = gravity[0], down = gravity[1];
        if (across * across + down * down < 1f) down = -1f;
        glassShader.setFloat(gl, "slide", across, down);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        gl.glDisable(GL.GL_CULL_FACE);
        glass.render(gl);
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
    }

    // The colour the condensation on the door's faintly tinted (the planet's seas')
    private final float[] waterTint = { 0.5f, 0.6f, 0.7f };

    /** Tints the door's condensation (RGB, as MapColours gives the sea). */
    void setWaterTint(int rgb) {
        waterTint[0] = ((rgb >> 16) & 255) / 255f;
        waterTint[1] = ((rgb >> 8) & 255) / 255f;
        waterTint[2] = (rgb & 255) / 255f;
    }

    /** Shows a picture on the computer's screen (see PodTerminal). */
    void showOnTerminal(GL3 gl, java.awt.image.BufferedImage picture) {
        int w = picture.getWidth(), h = picture.getHeight();
        int[] argb = picture.getRGB(0, 0, w, h, null, 0, w);
        gl.glBindTexture(GL.GL_TEXTURE_2D, terminalTexture);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
        // ARGB ints read as BGRA bytes on a little-endian machine; rows top down
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, w, h, 0, GL.GL_BGRA, GL.GL_UNSIGNED_BYTE, java.nio.IntBuffer.wrap(argb));
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
    }
}
