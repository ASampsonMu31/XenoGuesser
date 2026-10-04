import java.util.Random;

public class PerlinNoise {
    private final int[] p = new int[512];

    public PerlinNoise(long seed) {
        int[] permutation = new int[256];
        for (int i = 0; i < 256; i++) {
            permutation[i] = i;
        }

        Random rand = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            int temp = permutation[i];
            permutation[i] = permutation[j];
            permutation[j] = temp;
        }

        for (int i = 0; i < 256; i++) {
            p[i] = permutation[i];
            p[256 + i] = permutation[i];
        }
    }

    private float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    private float grad(int hash, float x, float z) {
        int h = hash & 7; 
        float u = h < 4 ? x : z;
        float v = h < 4 ? z : x;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    public float eval(float x, float z) {
        int X = (int) Math.floor(x) & 255;
        int Z = (int) Math.floor(z) & 255;

        x -= Math.floor(x);
        z -= Math.floor(z);

        float u = fade(x);
        float v = fade(z);

        int aa = p[p[X] + Z];
        int ab = p[p[X] + Z + 1];
        int ba = p[p[X + 1] + Z];
        int bb = p[p[X + 1] + Z + 1];

        return lerp(v, lerp(u, grad(aa, x, z),     grad(ba, x - 1, z)),
                       lerp(u, grad(ab, x, z - 1), grad(bb, x - 1, z - 1)));
    }

    private static float grad3(int hash, float x, float y, float z) {
        int h = hash & 15;
        float u = h < 8 ? x : y;
        float v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** Three-dimensional noise, roughly -1 to 1; the planet samples it on its surface so nothing has a seam. */
    public float eval3(float x, float y, float z) {
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        int Z = (int) Math.floor(z) & 255;
        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);
        float u = fade(x), v = fade(y), w = fade(z);
        int a = p[X] + Y, aa = p[a] + Z, ab = p[a + 1] + Z;
        int b = p[X + 1] + Y, ba = p[b] + Z, bb = p[b + 1] + Z;
        return lerp(w, lerp(v, lerp(u, grad3(p[aa], x, y, z), grad3(p[ba], x - 1, y, z)),
                               lerp(u, grad3(p[ab], x, y - 1, z), grad3(p[bb], x - 1, y - 1, z))),
                       lerp(v, lerp(u, grad3(p[aa + 1], x, y, z - 1), grad3(p[ba + 1], x - 1, y, z - 1)),
                               lerp(u, grad3(p[ab + 1], x, y - 1, z - 1), grad3(p[bb + 1], x - 1, y - 1, z - 1))));
    }

    /**
     * Noise over the planet's surface at chart point (x, z): sampled at that place on the
     * sphere (see Planet), so east and west edges of the chart meet without a seam. At the
     * equator, frequency means the same as for eval(x * frequency, z * frequency).
     */
    public float onSphere(float[] surface, float frequency, float offsetA, float offsetB) {
        return eval3(surface[0] * frequency + offsetA, surface[1] * frequency + 0.37f * offsetA - 0.21f * offsetB, surface[2] * frequency + offsetB);
    }
}
