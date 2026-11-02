import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Vector3;
import java.nio.FloatBuffer;

/**
 * The planet's moon in the game's sky, on worlds that have one (StarSystem): as big as it looks
 * from the ground and the same rock as it was out of the pod's window, lit by the sun from where
 * the sun is, so its phase is the one its place by the sun gives it (a thin crescent near the sun,
 * full opposite it). Where it is goes by the round's sun and the seed alone (StarSystem.elongation),
 * so both multiplayer PCs see it in the same place. Drawn just after the sun, over the sky and
 * under everything else (the clouds go over it), adding its light to the sky's: its dark side is
 * only sky, as a moon in daylight is.
 */
final class SkyMoon {

    // How far off it's drawn (just inside the sun, which is drawn at SphereRender's distance)
    private static final float DISTANCE = 2300f;

    private final StarSystem system;
    private final Shader shader;
    private final int[] vao = new int[1], vbo = new int[1];
    private final int texture;
    // The round the moon was last placed for (its sun), and where round from the sun it is
    private double placedDeclination = Double.NaN, placedNoon = Double.NaN, elongation;

    SkyMoon(GL3 gl, StarSystem system) {
        this.system = system;
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_sky_moon.txt", GamePaths.HOME + "assets/shaders/fs_sky_moon.txt");
        float[] square = { -1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1 };
        gl.glGenVertexArrays(1, vao, 0);
        gl.glGenBuffers(1, vbo, 0);
        gl.glBindVertexArray(vao[0]);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) square.length * Float.BYTES, FloatBuffer.wrap(square), GL.GL_STATIC_DRAW);
        gl.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 2 * Float.BYTES, 0);
        gl.glEnableVertexAttribArray(0);
        gl.glBindVertexArray(0);
        texture = PodSpaceView.upload(gl, 0, system.moonSurface(), true);
    }

    /**
     * Draws it, if it's up: eye where the camera is, sun the way to the sun (in the world), the
     * round's sun (declination, noon longitude) and where the pod came down (null if nowhere yet),
     * night how far into dusk it is (0 full day), sunTint the sunlight's colour.
     */
    void render(GL3 gl, Camera camera, SpherePosition eye, Vector3 sun, double declination, double noonLongitude,
            SpherePosition landedAt, float night, float[] sunTint) {
        if (declination != placedDeclination || noonLongitude != placedNoon) {
            placedDeclination = declination;
            placedNoon = noonLongitude;
            elongation = system.elongation(declination, noonLongitude, landedAt);
        }
        float[] local = system.moonDirection(eye, declination, noonLongitude, elongation);
        // (below the horizon, and a little more for the hills: not drawn)
        if (local[1] < -0.05f) return;
        Vector3 d = SphereRender.sunDirection(eye, local);
        float half = (float) Math.tan(system.moonAngle) * DISTANCE;
        // A square facing the eyes, about the moon's middle
        float[] forward = { d.x, d.y, d.z };
        float[] side = Affine.normalise(Affine.cross(forward, Math.abs(forward[1]) > 0.95f ? new float[] { 1f, 0f, 0f } : new float[] { 0f, 1f, 0f }));
        float[] up = Affine.cross(side, forward);

        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_ONE, GL.GL_ONE);
        gl.glDepthMask(false);
        gl.glDisable(GL.GL_CULL_FACE);
        shader.use(gl);
        shader.setFloatArray(gl, "viewProjection", camera.getSphereViewProjection().toFloatArrayForGLSL());
        shader.setFloat(gl, "centre", d.x * DISTANCE, d.y * DISTANCE, d.z * DISTANCE);
        shader.setFloat(gl, "side", side[0] * half, side[1] * half, side[2] * half);
        shader.setFloat(gl, "up", up[0] * half, up[1] * half, up[2] * half);
        shader.setFloat(gl, "sunDirection", sun.x, sun.y, sun.z);
        shader.setFloat(gl, "lightColour", 0.75f + 0.25f * sunTint[0], 0.75f + 0.25f * sunTint[1], 0.75f + 0.25f * sunTint[2]);
        // (paler against the full day's sky, clearer as the sun sinks; faint while it's still low over the hills)
        float rise = Math.max(0f, Math.min(1f, (local[1] + 0.05f) / 0.12f));
        shader.setFloat(gl, "strength", (0.55f + 0.6f * night) * rise);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, texture);
        shader.setInt(gl, "surface", 0);
        gl.glBindVertexArray(vao[0]);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, 6);
        gl.glBindVertexArray(0);
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
    }

    void dispose(GL3 gl) {
        gl.glDeleteBuffers(1, vbo, 0);
        gl.glDeleteVertexArrays(1, vao, 0);
        gl.glDeleteTextures(1, new int[] { texture }, 0);
    }
}
