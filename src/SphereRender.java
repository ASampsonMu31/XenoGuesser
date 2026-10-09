import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;

/** Shared camera and lighting uniforms for sphere-native articulated meshes. */
public final class SphereRender {
    private SphereRender() {
    }

    public static Vector3 sunDirection(SpherePosition position, float[] localDirection) {
        double length = position.length();
        double latitude = Math.asin(position.y() / length);
        double longitude = Math.atan2(position.z(), position.x());
        double eastX = -Math.sin(longitude), eastZ = Math.cos(longitude);
        double southX = Math.sin(latitude) * Math.cos(longitude);
        double southY = -Math.cos(latitude);
        double southZ = Math.sin(latitude) * Math.sin(longitude);
        double upX = Math.cos(latitude) * Math.cos(longitude);
        double upY = Math.sin(latitude);
        double upZ = Math.cos(latitude) * Math.sin(longitude);
        Vector3 direction = new Vector3(
                (float) (eastX * localDirection[0] + upX * localDirection[1] + southX * localDirection[2]),
                (float) (upY * localDirection[1] + southY * localDirection[2]),
                (float) (eastZ * localDirection[0] + upZ * localDirection[1] + southZ * localDirection[2]));
        direction.normalize();
        return direction;
    }

    public static void configure(GL3 gl, Shader shader, Camera camera, Matrix4 viewProjection,
            Vector3 sunDirection, float[] sunColour, Vector3 ambient, Matrix4 skyRotation) {
        SpherePosition position = camera.getSpherePosition();
        Vector3 mapPosition = camera.getPosition();
        double longitudeOffset = Math.atan2(position.z(), position.x()) - mapPosition.x / Planet.radius();
        longitudeOffset = Math.atan2(Math.sin(longitudeOffset), Math.cos(longitudeOffset));

        shader.use(gl);
        shader.setInt(gl, "sphereNativeWorld", 1);
        shader.setInt(gl, "sphereNativeAnchor", 0);
        shader.setVec3(gl, "sphereCamera", new Vector3((float) position.x(),
                (float) position.y(), (float) position.z()));
        shader.setFloat(gl, "sphereRadius", (float) Planet.radius());
        shader.setFloat(gl, "sphereLongitudeOffset", (float) longitudeOffset);
        shader.setVec3(gl, "sphereChartAnchor", mapPosition);
        shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        shader.setVec3(gl, "viewPos", new Vector3(0f, 0f, 0f));
        shader.setVec3(gl, "sunPos", new Vector3(sunDirection.x * 2350f,
                sunDirection.y * 2350f, sunDirection.z * 2350f));
        shader.setVec3(gl, "sunColour", new Vector3(sunColour[0], sunColour[1], sunColour[2]));
        shader.setVec3(gl, "ambientLight", ambient);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1,
                false, skyRotation.toFloatArrayForGLSL(), 0);
    }

    public static void reset(GL3 gl, Shader shader) {
        shader.use(gl);
        shader.setInt(gl, "sphereNativeWorld", 0);
    }
}
