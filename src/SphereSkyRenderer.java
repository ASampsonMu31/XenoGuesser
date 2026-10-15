import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;

/** Draws the sky behind everything: the sky texture turned by the sky's rotation (the clouds go over it: SphereWeather). */
public final class SphereSkyRenderer {
    private final Shader shader;
    private final int[] vertexArray = new int[1];

    public SphereSkyRenderer(GL3 gl) {
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_sphere_sky.txt",
                GamePaths.HOME + "assets/shaders/fs_sphere_sky.txt");
        gl.glGenVertexArrays(1, vertexArray, 0);
    }

    public void render(GL3 gl, Camera camera, Texture skyTexture, Vector3 fallbackSky,
            float aspect, Matrix4 skyRotation) {
        Vector3 forward = camera.getSphereForwardDirection();
        Vector3 up = camera.getSphereUpDirection();
        Vector3 right = Vector3.crossProduct(forward, up);
        right.normalize();
        Vector3 correctedUp = Vector3.crossProduct(right, forward);
        correctedUp.normalize();

        gl.glDisable(GL.GL_BLEND);
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glDepthMask(true);
        shader.use(gl);
        shader.setInt(gl, "skyTexture", 0);
        shader.setInt(gl, "hasSkyTexture", skyTexture == null ? 0 : 1);
        if (skyTexture != null) {
            gl.glActiveTexture(GL.GL_TEXTURE0);
            skyTexture.bind(gl);
        }
        shader.setVec3(gl, "cameraForward", forward);
        shader.setVec3(gl, "cameraRight", right);
        shader.setVec3(gl, "cameraUp", correctedUp);
        shader.setVec3(gl, "fallbackSky", fallbackSky);
        shader.setFloat(gl, "aspectRatio", aspect);
        shader.setFloat(gl, "tanHalfFov", camera.tanHalfFov());
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        gl.glBindVertexArray(vertexArray[0]);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
        gl.glBindVertexArray(0);
    }

    public void dispose(GL3 gl) {
        if (vertexArray[0] != 0) {
            gl.glDeleteVertexArrays(1, vertexArray, 0);
            vertexArray[0] = 0;
        }
    }
}
