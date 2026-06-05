import java.util.Random;
import gmaths.*;

public class PerlinNoise {
    private final int[] p = new int[512];
    private final long seed;

    public PerlinNoise(long seed) {
        this.seed = seed;
        int[] permutation = new int[256];
        
        // 1. Fill the baseline array
        for (int i = 0; i < 256; i++) {
            permutation[i] = i;
        }

        // 2. Deterministically shuffle it based on your distinct world seed
        Random rand = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            int temp = permutation[i];
            permutation[i] = permutation[j];
            permutation[j] = temp;
        }

        // 3. Duplicate into the look-up table to prevent index out of bounds
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
    
    private float grad(int hash, float x, float y, float z) {
        int h = hash & 15;
        float u = h < 8 ? x : y;
        float v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /**
     * Public 3D Perlin Noise sampler using the dynamically seeded permutation table.
     */
    public float noise3D(float x, float y, float z) {
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        int Z = (int) Math.floor(z) & 255;
        
        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);
        
        float u = fade(x);
        float v = fade(y);
        float w = fade(z);
        
        int A = p[X] + Y;
        int AA = p[A] + Z;
        int AB = p[A + 1] + Z;
        int B = p[X + 1] + Y;
        int BA = p[B] + Z;
        int BB = p[B + 1] + Z;
        
        return lerp(w, lerp(v, lerp(u, grad(p[AA], x, y, z),        grad(p[BA], x - 1, y, z)),
                               lerp(u, grad(p[AB], x, y - 1, z),    grad(p[BB], x - 1, y - 1, z))),
                       lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
                               lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
    }

    public long getSeed() {
        return seed;
    }
}