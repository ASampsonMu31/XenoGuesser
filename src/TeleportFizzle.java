import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;

/**
 * Returning to the pod's fizzle: the finished view (before the HUD) copied off the screen and
 * drawn back through fs_fizzle, torn into jittering horizontal bands and lost in static by a
 * strength, as OxygenBlur draws it back light-headed.
 */
public final class TeleportFizzle {
    private Shader shader;
    private final int[] texture = new int[1], vao = new int[1];
    private int textureW, textureH;

    /** Draws the screen (w by h, already showing the view) again, broken up by strength (0 to 1). */
    public void apply(GL3 gl, int w, int h, float strength) {
        if (strength <= 0.001f || w <= 0 || h <= 0) return;
        if (shader == null) {
            shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_oxygen.txt", GamePaths.HOME + "assets/shaders/fs_fizzle.txt");
            gl.glGenVertexArrays(1, vao, 0);
            gl.glGenTextures(1, texture, 0);
        }
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, texture[0]);
        if (w != textureW || h != textureH) {
            gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, w, h, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
            // (repeating across, so a band torn off one side comes back in at the other)
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
            textureW = w;
            textureH = h;
        }
        // (what's on the screen now: the view)
        gl.glBindFramebuffer(GL3.GL_READ_FRAMEBUFFER, 0);
        gl.glReadBuffer(GL.GL_BACK);
        gl.glCopyTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_BLEND);
        gl.glDisable(GL.GL_CULL_FACE);
        shader.use(gl);
        shader.setInt(gl, "scene", 0);
        shader.setFloat(gl, "strength", Math.min(1f, strength));
        // (kept small, so the shader's float still tells one frame from the next)
        shader.setFloat(gl, "time", (float) ((System.nanoTime() * 1e-9) % 1000.0));
        shader.setFloat(gl, "texel", 1f / w, 1f / h);
        gl.glBindVertexArray(vao[0]);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
        gl.glBindVertexArray(0);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_CULL_FACE);
    }
}
