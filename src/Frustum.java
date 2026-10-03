import com.xenoguesser.math.Matrix4;

/**
 * The camera's view volume as six planes, extracted from a view-projection matrix
 * (Gribb and Hartmann's method), for skipping objects that can't appear on screen.
 */
public final class Frustum {

    private final float[][] planes = new float[6][4];

    public void update(Matrix4 viewProjection) {
        float[] m = viewProjection.toFloatArrayForGLSL();   // column-major: m[col * 4 + row]
        for (int i = 0; i < 3; i++) {
            for (int sign = 0; sign < 2; sign++) {
                float[] plane = planes[i * 2 + sign];
                float s = sign == 0 ? 1f : -1f;
                for (int c = 0; c < 4; c++) {
                    plane[c] = m[c * 4 + 3] + s * m[c * 4 + i];
                }
                float length = (float) Math.sqrt(plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]);
                for (int c = 0; c < 4; c++) plane[c] /= length;
            }
        }
    }

    /** False only when the sphere lies entirely outside one of the planes. */
    public boolean intersectsSphere(float x, float y, float z, float radius) {
        for (float[] p : planes) {
            if (p[0] * x + p[1] * y + p[2] * z + p[3] < -radius) {
                return false;
            }
        }
        return true;
    }
}
