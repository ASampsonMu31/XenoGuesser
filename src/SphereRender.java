import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;

/**
 * Shared camera and lighting uniforms for the articulated meshes drawn with the organism
 * shader (creatures, people, vehicles, the landing pod), which are drawn relative to the
 * camera: each is placed by an anchor matrix taking its own frame to the world less the
 * camera's position.
 */
public final class SphereRender {
    // How far off the sun is put, for shaders that light from a point
    private static final float SUN_DISTANCE = 2350f;
    private static final float[] IDENTITY = new Matrix4(1).toFloatArrayForGLSL();

    private SphereRender() {
    }

    /** A direction given in the local east/up/south frame at a position, as a world direction. */
    public static Vector3 sunDirection(SpherePosition position, float[] localDirection) {
        double[] d = SphereFrame.at(position).worldDirection(localDirection[0], localDirection[1], localDirection[2]);
        Vector3 direction = new Vector3((float) d[0], (float) d[1], (float) d[2]);
        direction.normalize();
        return direction;
    }

    /** The sun, as a point SUN_DISTANCE away along a direction from the eyes (at the origin). */
    public static Vector3 sunPoint(Vector3 sunDirection) {
        return new Vector3(sunDirection.x * SUN_DISTANCE, sunDirection.y * SUN_DISTANCE, sunDirection.z * SUN_DISTANCE);
    }

    public static void configure(GL3 gl, Shader shader, Matrix4 viewProjection,
            Vector3 sunDirection, float[] sunColour, Vector3 ambient, Matrix4 skyRotation) {
        shader.use(gl);
        shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        shader.setVec3(gl, "viewPos", new Vector3(0f, 0f, 0f));
        shader.setVec3(gl, "sunPos", sunPoint(sunDirection));
        shader.setVec3(gl, "sunColour", new Vector3(sunColour[0], sunColour[1], sunColour[2]));
        shader.setVec3(gl, "ambientLight", ambient);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1,
                false, skyRotation.toFloatArrayForGLSL(), 0);
        setAnchor(gl, shader, null);
    }

    /** Places what is drawn next with the organism shader: its frame relative to the camera (null for the identity). */
    public static void setAnchor(GL3 gl, Shader shader, Matrix4 anchor) {
        shader.setFloatArray(gl, "anchor", anchor == null ? IDENTITY : anchor.toFloatArrayForGLSL());
    }
}
