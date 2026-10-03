/**
 * Small helpers for 4x4 affine transforms stored column-major in float[16], the layout
 * glUniformMatrix4fv takes without transposing. Used to pose organisms every frame without
 * allocating a Matrix4 per bone.
 */
public final class Affine {

    private Affine() {}

    public static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1f;
        return m;
    }

    public static float[] translation(float x, float y, float z) {
        float[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    /** Rotation about +Y by the given angle in radians (+Z turns towards +X). */
    public static float[] rotationY(float radians) {
        float[] m = identity();
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        m[0] = c;
        m[2] = -s;
        m[8] = s;
        m[10] = c;
        return m;
    }

    /** Rotation about +X by the given angle in radians (+Z turns towards -Y). */
    public static float[] rotationX(float radians) {
        float[] m = identity();
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        m[5] = c;
        m[6] = s;
        m[9] = -s;
        m[10] = c;
        return m;
    }

    public static float[] scale(float x, float y, float z) {
        float[] m = identity();
        m[0] = x;
        m[5] = y;
        m[10] = z;
        return m;
    }

    public static float[] multiply(float[] a, float[] b) {
        float[] out = new float[16];
        multiply(a, b, out, 0);
        return out;
    }

    /** a * b, written into out at the given offset (so many bones can share one array). */
    public static void multiply(float[] a, float[] b, float[] out, int offset) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                out[offset + col * 4 + row] = a[row] * b[col * 4] + a[4 + row] * b[col * 4 + 1]
                        + a[8 + row] * b[col * 4 + 2] + a[12 + row] * b[col * 4 + 3];
            }
        }
    }

    public static float[] transformPoint(float[] m, float x, float y, float z) {
        return new float[] {
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14]
        };
    }

    public static float[] transformDirection(float[] m, float x, float y, float z) {
        return new float[] {
            m[0] * x + m[4] * y + m[8] * z,
            m[1] * x + m[5] * y + m[9] * z,
            m[2] * x + m[6] * y + m[10] * z
        };
    }

    /**
     * A frame at origin whose +Z points along dir, with +Y as close to upHint as possible;
     * the axes are scaled by sx, sy and sz. Parts are modelled along +Z, so this lays a part
     * from one joint towards the next.
     */
    public static float[] frame(float[] origin, float[] dir, float[] upHint, float sx, float sy, float sz) {
        float[] z = normalise(dir);
        float[] x = cross(upHint, z);
        if (length(x) < 1e-5f) {
            x = cross(new float[] { 0f, 0f, 1f }, z);
            if (length(x) < 1e-5f) x = new float[] { 1f, 0f, 0f };
        }
        x = normalise(x);
        float[] y = cross(z, x);
        float[] m = new float[16];
        m[0] = x[0] * sx; m[1] = x[1] * sx; m[2] = x[2] * sx;
        m[4] = y[0] * sy; m[5] = y[1] * sy; m[6] = y[2] * sy;
        m[8] = z[0] * sz; m[9] = z[1] * sz; m[10] = z[2] * sz;
        m[12] = origin[0]; m[13] = origin[1]; m[14] = origin[2];
        m[15] = 1f;
        return m;
    }

    public static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    public static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static float length(float[] v) {
        return (float) Math.sqrt(dot(v, v));
    }

    public static float[] normalise(float[] v) {
        float l = length(v);
        return l < 1e-9f ? new float[] { 0f, 0f, 1f } : new float[] { v[0] / l, v[1] / l, v[2] / l };
    }

    public static float[] add(float[] a, float[] b, float scale) {
        return new float[] { a[0] + b[0] * scale, a[1] + b[1] * scale, a[2] + b[2] * scale };
    }

    public static float[] subtract(float[] a, float[] b) {
        return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    /**
     * The middle joint (knee or elbow) of a two-part limb reaching from root to tip, bent
     * towards bendHint. If the tip is out of reach the limb is simply straightened.
     */
    public static float[] middleJoint(float[] root, float[] tip, float upper, float lower, float[] bendHint) {
        float[] toTip = subtract(tip, root);
        float distance = Math.min(length(toTip), (upper + lower) * 0.999f);
        distance = Math.max(distance, Math.abs(upper - lower) + 1e-3f);
        float[] dir = normalise(toTip);
        float along = (upper * upper - lower * lower + distance * distance) / (2f * distance);
        float rise = (float) Math.sqrt(Math.max(0f, upper * upper - along * along));
        float h = dot(bendHint, dir);
        float[] bend = normalise(new float[] { bendHint[0] - dir[0] * h, bendHint[1] - dir[1] * h, bendHint[2] - dir[2] * h });
        return new float[] {
            root[0] + dir[0] * along + bend[0] * rise,
            root[1] + dir[1] * along + bend[1] * rise,
            root[2] + dir[2] * along + bend[2] * rise
        };
    }
}
