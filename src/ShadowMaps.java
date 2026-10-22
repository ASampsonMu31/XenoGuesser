import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;

/**
 * The sun's shadows: two maps, each of how near to the sun the nearest thing is across a square
 * seen straight down from the sun, centred on the player. A sharp one close round them (NEAR) and
 * a coarser one further out (FAR), which every lit shader looks up (shadows_include) to see
 * whether something stands between a point and the sun.
 *
 * Each frame (the far map every third one) the world's drawn into them from the sun, by the
 * game's own drawing with the camera's view swapped for the sun's (see XenoGuesser_GLEventListener.
 * renderShadows). Everything is drawn relative to the eyes, so the views are too; each map's
 * square is held on whole texels of the world (not the eyes), so shadows don't crawl as the
 * player moves. A map not drawn this frame is still used, its view moved on by however far the
 * eyes have since.
 */
public class ShadowMaps {

    // Each map's size (texels), how far it reaches either side of the eyes, and how far towards
    // and away from the sun it takes things in (so tall things and hills off to the sun's side cast)
    // (as the graphics chosen when the maps are made: see GameSettings.Quality)
    private final int SIZE = GameSettings.quality().shadowMapSize;
    static final float NEAR_REACH = 260f, FAR_REACH = 1500f;
    private static final float NEAR_DEPTH = 1200f, FAR_DEPTH = 3000f;
    // How far (units) a surface is pushed out from itself before it's looked up, against shading itself
    private static final float NEAR_BIAS = 0.6f, FAR_BIAS = 3f;
    // The sun's way as the shadows take it: moved on only once the sun has moved this far (radians),
    // so the maps don't shift a fraction every frame as the day goes on, the shadows shimmering
    private static final double SUN_STEP = Math.toRadians(0.25);
    private float[] steadySun;

    private final int[] fbo = new int[2], texture = new int[2];
    // Each map's view from the sun, relative to the eyes where it was drawn, and those eyes
    private final float[][] matrix = new float[2][];
    private final double[][] drawnAt = new double[2][];
    private int frame;

    public void initialise(GL3 gl) {
        gl.glGenFramebuffers(2, fbo, 0);
        gl.glGenTextures(2, texture, 0);
        for (int i = 0; i < 2; i++) {
            gl.glBindTexture(GL.GL_TEXTURE_2D, texture[i]);
            gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_DEPTH_COMPONENT24, SIZE, SIZE, 0, GL3.GL_DEPTH_COMPONENT, GL.GL_UNSIGNED_INT, null);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
            // (compared as it's looked up, the hardware softening each look-up over four texels)
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL3.GL_TEXTURE_COMPARE_MODE, GL3.GL_COMPARE_REF_TO_TEXTURE);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL3.GL_TEXTURE_COMPARE_FUNC, GL.GL_LEQUAL);
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo[i]);
            gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_TEXTURE_2D, texture[i], 0);
            gl.glDrawBuffer(GL.GL_NONE);
            gl.glReadBuffer(GL.GL_NONE);
        }
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
    }

    /** The maps' size (texels each way). */
    int size() {
        return SIZE;
    }

    public void dispose(GL3 gl) {
        gl.glDeleteFramebuffers(2, fbo, 0);
        gl.glDeleteTextures(2, texture, 0);
    }

    /** Which maps are drawn this frame: the near one always, the far one every third frame (or when there's none yet). */
    boolean[] dueThisFrame() {
        frame++;
        return new boolean[] { true, matrix[1] == null || frame % 3 == 0 };
    }

    /**
     * Starts drawing map (0 near, 1 far) from the sun, sunDirection (towards it, in the world),
     * round the eyes at eye: returns the view-projection to draw through (relative to the eyes).
     * Draw into it, then call end.
     */
    Matrix4 begin(GL3 gl, int map, SpherePosition eye, Vector3 sunDirection) {
        float reach = map == 0 ? NEAR_REACH : FAR_REACH, depth = map == 0 ? NEAR_DEPTH : FAR_DEPTH;
        float[] toSun = steady(sunDirection);
        // The sun's view: looking along -toSun, up as near the planet's own north as it can be
        float[] hint = Math.abs(toSun[1]) > 0.95f ? new float[] { 1f, 0f, 0f } : new float[] { 0f, 1f, 0f };
        float[] right = Affine.normalise(Affine.cross(hint, toSun));
        float[] up = Affine.cross(toSun, right);
        // Held on whole texels of the world: the eyes' own place along right and up, to the texel
        double texel = 2.0 * reach / SIZE;
        double ex = right[0] * eye.x() + right[1] * eye.y() + right[2] * eye.z();
        double ey = up[0] * eye.x() + up[1] * eye.y() + up[2] * eye.z();
        float offX = (float) (Math.floor(ex / texel) * texel - ex), offY = (float) (Math.floor(ey / texel) * texel - ey);
        // Orthographic, from the sun's side: x across, y up, z towards the sun (-1 near it)
        float[] m = new float[16];
        float sx = 1f / reach, sz = 1f / depth;
        m[0] = right[0] * sx;
        m[4] = right[1] * sx;
        m[8] = right[2] * sx;
        m[12] = -offX * sx;
        m[1] = up[0] * sx;
        m[5] = up[1] * sx;
        m[9] = up[2] * sx;
        m[13] = -offY * sx;
        m[2] = -toSun[0] * sz;
        m[6] = -toSun[1] * sz;
        m[10] = -toSun[2] * sz;
        m[14] = 0f;
        m[15] = 1f;
        matrix[map] = m;
        drawnAt[map] = new double[] { eye.x(), eye.y(), eye.z() };

        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo[map]);
        gl.glViewport(0, 0, SIZE, SIZE);
        gl.glClear(GL.GL_DEPTH_BUFFER_BIT);
        gl.glColorMask(false, false, false, false);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(true);
        // (drawn a little further from the sun than it is, the more so the more it slopes away,
        // so a surface lit at a slant doesn't shade itself in speckles)
        gl.glEnable(GL.GL_POLYGON_OFFSET_FILL);
        gl.glPolygonOffset(2.5f, 4f);
        return Matrix4.fromColumns(m);
    }

    /** Finishes drawing a map: back to drawing the world into target (a framebuffer, 0 the screen), width by height. */
    void end(GL3 gl, int target, int width, int height) {
        gl.glDisable(GL.GL_POLYGON_OFFSET_FILL);
        gl.glPolygonOffset(0f, 0f);
        gl.glColorMask(true, true, true, true);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, target);
        gl.glViewport(0, 0, width, height);
    }

    /**
     * Hands the maps to every shader that draws shadows, for eyes now at eye: each map's view
     * moved on by however far the eyes have gone since it was drawn. strength is how dark they
     * are (0: none).
     */
    void publish(GL3 gl, SpherePosition eye, Vector3 sunDirection, float strength) {
        if (matrix[0] == null || matrix[1] == null || strength <= 0f) {
            Shader.clearShadows(gl);
            return;
        }
        float[] toSun = steady(sunDirection);
        Shader.setShadows(gl, texture[0], texture[1], movedOn(0, eye), movedOn(1, eye), strength,
                NEAR_REACH, FAR_REACH, 1f / SIZE, 1f / SIZE, NEAR_BIAS, FAR_BIAS, toSun);
    }

    /** The way to the sun as the shadows take it: the last one used, until the sun's moved SUN_STEP from it. */
    private float[] steady(Vector3 sunDirection) {
        float[] now = Affine.normalise(new float[] { sunDirection.x, sunDirection.y, sunDirection.z });
        if (steadySun == null || Affine.dot(now, steadySun) < Math.cos(SUN_STEP)) steadySun = now;
        return steadySun;
    }

    /** A map's view for eyes at eye: the one it was drawn with, less how far the eyes have gone since. */
    private float[] movedOn(int map, SpherePosition eye) {
        float[] m = matrix[map];
        double[] at = drawnAt[map];
        // Relative to the old eyes, a point is (relative to the new) plus how far they've moved
        float dx = (float) (eye.x() - at[0]), dy = (float) (eye.y() - at[1]), dz = (float) (eye.z() - at[2]);
        float[] out = m.clone();
        for (int row = 0; row < 3; row++) {
            out[12 + row] = m[12 + row] + m[row] * dx + m[4 + row] * dy + m[8 + row] * dz;
        }
        return out;
    }
}
