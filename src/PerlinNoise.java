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
}