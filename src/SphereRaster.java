/**
 * A raster over the whole sphere, looked up by direction (D14 in GEODESIC_MIGRATION_PLAN.txt):
 * a cube map, n by n texels on each of six faces, laid out as OpenGL's cube maps are (faces
 * +x, -x, +y, -y, +z, -z; texel (i, j) of a face at s = (i + 1/2) / n, t = (j + 1/2) / n), so
 * the same values can be sampled here or uploaded as a GL_TEXTURE_CUBE_MAP and sampled by
 * direction in a shader. A texel is about 2 / n radians across at a face's middle and about
 * half that at its corners; nothing is pinched at the poles and there is no seam to wrap.
 * <p>
 * Values are kept by the caller in arrays of size() (index = (face * n + j) * n + i).
 */
public final class SphereRaster {

    private final int n;

    public SphereRaster(int n) {
        if (n < 2) throw new IllegalArgumentException("A sphere raster needs at least 2 texels a side");
        this.n = n;
    }

    /** Texels along a face's side. */
    public int side() {
        return n;
    }

    /** Texels in all, over the six faces. */
    public int size() {
        return 6 * n * n;
    }

    /** The texel a direction (need not be unit length) falls in. */
    public int index(double x, double y, double z) {
        double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        int face;
        double sc, tc, ma;
        if (ax >= ay && ax >= az) {
            face = x >= 0 ? 0 : 1;
            sc = x >= 0 ? -z : z;
            tc = -y;
            ma = ax;
        } else if (ay >= az) {
            face = y >= 0 ? 2 : 3;
            sc = x;
            tc = y >= 0 ? z : -z;
            ma = ay;
        } else {
            face = z >= 0 ? 4 : 5;
            sc = z >= 0 ? x : -x;
            tc = -y;
            ma = az;
        }
        if (!(ma > 0.0)) throw new IllegalArgumentException("A zero direction has no texel");
        int i = Math.min(n - 1, Math.max(0, (int) ((sc / ma + 1.0) * 0.5 * n)));
        int j = Math.min(n - 1, Math.max(0, (int) ((tc / ma + 1.0) * 0.5 * n)));
        return (face * n + j) * n + i;
    }

    public int index(GeodesicChunkGrid.Direction d) {
        return index(d.x(), d.y(), d.z());
    }

    /** The unit direction through a texel's middle, into out[0..2]. */
    public void direction(int index, double[] out) {
        int i = index % n, j = (index / n) % n, face = index / (n * n);
        faceDirection(face, (2.0 * i + 1.0) / n - 1.0, (2.0 * j + 1.0) / n - 1.0, out);
        double length = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
        out[0] /= length;
        out[1] /= length;
        out[2] /= length;
    }

    public GeodesicChunkGrid.Direction direction(int index) {
        double[] d = new double[3];
        direction(index, d);
        return new GeodesicChunkGrid.Direction(d[0], d[1], d[2]);
    }

    /**
     * The texel beside one, across its edge k (0 +s, 1 -s, 2 +t, 3 -t); at a face's edge, the
     * texel on the next face that the step lands in.
     */
    public int neighbour(int index, int k) {
        int i = index % n, j = (index / n) % n, face = index / (n * n);
        int ni = i + (k == 0 ? 1 : k == 1 ? -1 : 0), nj = j + (k == 2 ? 1 : k == 3 ? -1 : 0);
        if (ni >= 0 && ni < n && nj >= 0 && nj < n) return (face * n + nj) * n + ni;
        double[] d = new double[3];
        faceDirection(face, (2.0 * ni + 1.0) / n - 1.0, (2.0 * nj + 1.0) / n - 1.0, d);
        return index(d[0], d[1], d[2]);
    }

    /**
     * A value at a direction, blended between the four nearest texels' (bilinear on the face,
     * the texels past a face's edge taken from the next face, so there is no seam).
     */
    public float sample(float[] values, GeodesicChunkGrid.Direction direction) {
        return sample(values, direction.x(), direction.y(), direction.z());
    }

    public float sample(float[] values, double x, double y, double z) {
        int[] texels = new int[4];
        float[] weights = new float[4];
        corners(x, y, z, texels, weights);
        float sum = 0f;
        for (int k = 0; k < 4; k++) if (weights[k] != 0f) sum += values[texels[k]] * weights[k];
        return sum;
    }

    /**
     * The four texels nearest a direction and how much each counts in a blend between them
     * (bilinear on the face; past a face's edge, the texel on the next face), into texels and
     * weights (four each; the weights sum to 1).
     */
    public void corners(double x, double y, double z, int[] texels, float[] weights) {
        int at = index(x, y, z), face = at / (n * n);
        double[] st = faceCoordinates(face, x, y, z);
        double gx = (st[0] + 1.0) * 0.5 * n - 0.5, gy = (st[1] + 1.0) * 0.5 * n - 0.5;
        int i0 = (int) Math.floor(gx), j0 = (int) Math.floor(gy);
        float fx = (float) (gx - i0), fy = (float) (gy - j0);
        for (int k = 0; k < 4; k++) {
            int i = i0 + (k & 1), j = j0 + (k >> 1);
            weights[k] = ((k & 1) == 0 ? 1f - fx : fx) * ((k >> 1) == 0 ? 1f - fy : fy);
            if (i >= 0 && i < n && j >= 0 && j < n) texels[k] = (face * n + j) * n + i;
            else {
                double[] d = new double[3];
                faceDirection(face, (2.0 * i + 1.0) / n - 1.0, (2.0 * j + 1.0) / n - 1.0, d);
                texels[k] = index(d[0], d[1], d[2]);
            }
        }
    }

    /** The share of the whole sphere's surface (4 pi steradians) a texel covers, in steradians. */
    public double solidAngle(int index) {
        int i = index % n, j = (index / n) % n;
        double s = (2.0 * i + 1.0) / n - 1.0, t = (2.0 * j + 1.0) / n - 1.0, step = 2.0 / n;
        return step * step / Math.pow(1.0 + s * s + t * t, 1.5);
    }

    /** A face's (sc, tc), -1 to 1 across it, for a direction on that face. */
    private static double[] faceCoordinates(int face, double x, double y, double z) {
        return switch (face) {
            case 0 -> new double[] { -z / x, -y / x };
            case 1 -> new double[] { z / -x, -y / -x };
            case 2 -> new double[] { x / y, z / y };
            case 3 -> new double[] { x / -y, -z / -y };
            case 4 -> new double[] { x / z, -y / z };
            default -> new double[] { -x / -z, -y / -z };
        };
    }

    /** The (not unit) direction at a face's (sc, tc), which may run past its edges. */
    private static void faceDirection(int face, double sc, double tc, double[] out) {
        switch (face) {
            case 0 -> { out[0] = 1; out[1] = -tc; out[2] = -sc; }
            case 1 -> { out[0] = -1; out[1] = -tc; out[2] = sc; }
            case 2 -> { out[0] = sc; out[1] = 1; out[2] = tc; }
            case 3 -> { out[0] = sc; out[1] = -1; out[2] = -tc; }
            case 4 -> { out[0] = sc; out[1] = -tc; out[2] = 1; }
            default -> { out[0] = -sc; out[1] = -tc; out[2] = -1; }
        }
    }

    /**
     * The great-circle distance (in radians) from every texel to the nearest texel marked
     * true in from, worked out by passing each texel's nearest marked texel on to its
     * neighbours until none can be bettered (so the distances are true distances on the
     * sphere, not counts of steps). Texels marked true are 0.
     */
    public float[] distanceTo(boolean[] from) {
        int size = size();
        int[] nearest = new int[size];
        float[] chord = new float[size];
        boolean[] queued = new boolean[size];
        int[] queue = new int[size];
        int head = 0, count = 0;
        for (int t = 0; t < size; t++) {
            if (from[t]) {
                nearest[t] = t;
                chord[t] = 0f;
                queue[(head + count++) % size] = t;
                queued[t] = true;
            } else {
                nearest[t] = -1;
                chord[t] = Float.MAX_VALUE;
            }
        }
        double[] here = new double[3], seed = new double[3];
        while (count > 0) {
            int t = queue[head];
            head = (head + 1) % size;
            count--;
            queued[t] = false;
            direction(nearest[t], seed);
            for (int k = 0; k < 4; k++) {
                int q = neighbour(t, k);
                if (nearest[q] == nearest[t]) continue;
                direction(q, here);
                double dx = here[0] - seed[0], dy = here[1] - seed[1], dz = here[2] - seed[2];
                float c = (float) (dx * dx + dy * dy + dz * dz);
                if (c < chord[q]) {
                    chord[q] = c;
                    nearest[q] = nearest[t];
                    if (!queued[q]) {
                        queued[q] = true;
                        queue[(head + count++) % size] = q;
                    }
                }
            }
        }
        float[] angle = new float[size];
        for (int t = 0; t < size; t++) {
            angle[t] = nearest[t] < 0 ? Float.MAX_VALUE : (float) (2.0 * Math.asin(Math.min(1.0, Math.sqrt(chord[t]) * 0.5)));
        }
        return angle;
    }
}
