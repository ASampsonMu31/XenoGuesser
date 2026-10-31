import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;

/**
 * The light the landing pod's beacon shines, so the player can find their way back to it from
 * wherever it's in view: bright white (to stand out against any sky), gathered by the dish on its roof (kept level, see
 * LandingPod) into a ball over it, and from the ball a beam straight up into the sky, however
 * the pod leans.
 *
 * Built camera-relative each frame: the light rising off the dish's bowl towards its focus, the
 * ball (turned to face the eyes), and the beam (a ribbon turned to face them, never narrower
 * than a few pixels however far off, brightest along its middle and fading as it climbs). It's
 * drawn without the far plane's clipping (depth clamped), so the beam reaches up past the far
 * plane into the sky, and is hidden by anything solid in front of it.
 */
public class PodBeacon {

    // How far up the beam reaches, and how many lengths it's built from (shorter low down, where it's nearest)
    private static final double LENGTH = 12000.0;
    private static final int SEGMENTS = 32;
    // The beam's half width up close, and the least it's ever seen at (radians either side of its middle)
    private static final double RADIUS = 5.0, LEAST_ANGLE = 0.008;
    // The ball's radius (its glow's: its bright middle is about a third of it), and the least it's seen at
    private static final double BALL_RADIUS = 12.0, BALL_LEAST_ANGLE = 0.02;
    // How many slices the light off the dish is built from
    private static final int CONE_SLICES = 24;
    // Each vertex: camera-relative position, where it is across the light (-1 to 1 each way: 0, 0
    // at its middle) and how bright it is there
    private static final int FLOATS = 6;
    // How much more vivid the fringe is than the sky's complementary colour, and how dark
    private static final float FRINGE_VIVIDNESS = 2.5f, FRINGE_DARKNESS = 0.45f;

    private Shader shader;
    private int vao, vbo;
    private final float[] vertices = new float[(SEGMENTS * 6 + 6 + CONE_SLICES * 3) * FLOATS];
    private int count;

    public void initialise(GL3 gl) {
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_pod_beacon.txt", GamePaths.HOME + "assets/shaders/fs_pod_beacon.txt");
        int[] ids = new int[1];
        gl.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        gl.glGenBuffers(1, ids, 0);
        vbo = ids[0];
        gl.glBindVertexArray(vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, FLOATS * 4, 0);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, FLOATS * 4, 12);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    public void dispose(GL3 gl) {
        if (shader == null) return;
        gl.glDeleteVertexArrays(1, new int[] { vao }, 0);
        gl.glDeleteBuffers(1, new int[] { vbo }, 0);
    }

    /**
     * Draws the pod's beacon light (if it's been placed) over what's behind it, its fringe in
     * the sky's complementary colour.
     */
    public void render(GL3 gl, Matrix4 viewProjection, SpherePosition eye, LandingPod pod, Vector3 sky) {
        SpherePosition focus = shader == null ? null : pod.beaconFocus();
        if (focus == null) return;
        SphereFrame frame = pod.frame();
        double[] up = frame.up(), x = frame.xAxis(), z = frame.zAxis();
        // The focus, camera-relative (in doubles, so nothing's rounded however far off it is)
        double fx = focus.x() - eye.x(), fy = focus.y() - eye.y(), fz = focus.z() - eye.z();
        count = 0;

        // The light off the dish: rising faintly from its rim and brightening into the focus
        double rimDown = LandingPod.DISH_FOCUS - LandingPod.DISH_RIM;
        for (int i = 0; i < CONE_SLICES; i++) {
            double a0 = i * Math.PI * 2.0 / CONE_SLICES, a1 = (i + 1) * Math.PI * 2.0 / CONE_SLICES;
            rimPoint(fx, fy, fz, up, x, z, a0, rimDown);
            rimPoint(fx, fy, fz, up, x, z, a1, rimDown);
            put(fx, fy, fz, 0.0, 0.0, 0.55);
        }

        // The ball, facing the eyes
        double distance = Math.sqrt(fx * fx + fy * fy + fz * fz);
        double[] across = unit(cross(up, new double[] { fx, fy, fz }), x);
        double[] rise = unit(cross(new double[] { fx, fy, fz }, across), up);
        double r = Math.max(BALL_RADIUS, distance * BALL_LEAST_ANGLE);
        double[][] corners = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, -1 }, { 1, 1 }, { -1, 1 } };
        for (double[] c : corners) {
            put(fx + (across[0] * c[0] + rise[0] * c[1]) * r, fy + (across[1] * c[0] + rise[1] * c[1]) * r,
                    fz + (across[2] * c[0] + rise[2] * c[1]) * r, c[0], c[1], 1.0);
        }

        // The beam, straight up from the ball
        double[] previous = null;
        for (int i = 0; i <= SEGMENTS; i++) {
            double share = (double) i / SEGMENTS, height = LENGTH * share * share;
            double px = fx + up[0] * height, py = fy + up[1] * height, pz = fz + up[2] * height;
            // (across it, square to the eyes' line to it)
            double[] side = unit(cross(up, new double[] { px, py, pz }), x);
            double half = Math.max(RADIUS, Math.sqrt(px * px + py * py + pz * pz) * LEAST_ANGLE);
            double bright = Math.pow(1.0 - share, 1.5);
            double[] here = { px - side[0] * half, py - side[1] * half, pz - side[2] * half,
                              px + side[0] * half, py + side[1] * half, pz + side[2] * half, bright };
            if (previous != null) {
                put(previous[0], previous[1], previous[2], -1.0, 0.0, previous[6]);
                put(previous[3], previous[4], previous[5], 1.0, 0.0, previous[6]);
                put(here[3], here[4], here[5], 1.0, 0.0, here[6]);
                put(previous[0], previous[1], previous[2], -1.0, 0.0, previous[6]);
                put(here[3], here[4], here[5], 1.0, 0.0, here[6]);
                put(here[0], here[1], here[2], -1.0, 0.0, here[6]);
            }
            previous = here;
        }

        gl.glBindVertexArray(vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, count * 4L, java.nio.FloatBuffer.wrap(vertices, 0, count), GL3.GL_STREAM_DRAW);
        shader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "viewProjection"), 1, false, viewProjection.toFloatArrayForGLSL(), 0);
        float[] fringe = complement(sky);
        shader.setFloat(gl, "fringeColour", fringe[0], fringe[1], fringe[2]);
        gl.glEnable(GL.GL_BLEND);
        // (premultiplied: its white over whatever's behind, rather than added to an already bright sky)
        gl.glBlendFunc(GL.GL_ONE, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        gl.glDepthFunc(GL.GL_LEQUAL);
        gl.glEnable(GL3.GL_DEPTH_CLAMP);
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, count / FLOATS);
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glDisable(GL3.GL_DEPTH_CLAMP);
        gl.glDepthFunc(GL.GL_LESS);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    /** A point on the dish's rim, rimDown below the focus, faint. */
    private void rimPoint(double fx, double fy, double fz, double[] up, double[] x, double[] z, double angle, double rimDown) {
        double s = Math.sin(angle) * LandingPod.DISH_RADIUS * 0.95, c = Math.cos(angle) * LandingPod.DISH_RADIUS * 0.95;
        put(fx - up[0] * rimDown + x[0] * s + z[0] * c, fy - up[1] * rimDown + x[1] * s + z[1] * c,
                fz - up[2] * rimDown + x[2] * s + z[2] * c, 0.0, 0.0, 0.04);
    }

    /**
     * The sky's complementary colour for the beam's fringe: its hue turned half way round (each
     * channel flipped about the middle of the brightest and dimmest), made more vivid, and
     * darkened so it stands apart in brightness as well. A grey sky's is just a dark grey.
     */
    private static float[] complement(Vector3 sky) {
        float[] c = { sky.x, sky.y, sky.z };
        float most = Math.max(c[0], Math.max(c[1], c[2])), least = Math.min(c[0], Math.min(c[1], c[2]));
        float grey = (most + least) * 0.5f;
        for (int i = 0; i < 3; i++) {
            float flipped = most + least - c[i];
            c[i] = Math.max(0f, Math.min(1f, grey + (flipped - grey) * FRINGE_VIVIDNESS)) * FRINGE_DARKNESS;
        }
        return c;
    }

    private void put(double px, double py, double pz, double u, double v, double bright) {
        vertices[count++] = (float) px;
        vertices[count++] = (float) py;
        vertices[count++] = (float) pz;
        vertices[count++] = (float) u;
        vertices[count++] = (float) v;
        vertices[count++] = (float) bright;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    /** a made unit length, or fallback where it's too short to have a way (looking straight along the beam). */
    private static double[] unit(double[] a, double[] fallback) {
        double length = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        if (length < 1e-6) return fallback;
        return new double[] { a[0] / length, a[1] / length, a[2] / length };
    }
}
