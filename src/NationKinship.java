import java.util.Random;

/**
 * Makes nation-by-nation choices geographically coherent: neighbouring nations are more
 * likely to share a style, and continuous traits drift gradually across the map, the way
 * building traditions and road conventions spread between neighbours on Earth.
 *
 * Built from each nation's territorial centre on the nation map.
 */
public final class NationKinship {

    private final int numNations;
    private final float[][] centres;   // [nationId] = {x, z} in [0, 1] map space

    public NationKinship(NationGenerationManager nations) {
        this.numNations = nations.numNations;
        int[][] map = nations.nationMap;
        int res = map.length;
        double[] sumX = new double[numNations + 1], sumZ = new double[numNations + 1];
        int[] count = new int[numNations + 1];
        for (int x = 0; x < res; x++) {
            for (int z = 0; z < map[x].length; z++) {
                int id = map[x][z];
                if (id < 1 || id > numNations) continue;
                sumX[id] += x;
                sumZ[id] += z;
                count[id]++;
            }
        }
        centres = new float[numNations + 1][];
        for (int n = 1; n <= numNations; n++) {
            // A nation that never claimed land still needs a position; park it mid-map
            centres[n] = count[n] == 0 ? new float[] {0.5f, 0.5f}
                    : new float[] {(float) (sumX[n] / count[n] / res), (float) (sumZ[n] / count[n] / res)};
        }
    }

    /**
     * Assigns each nation one of {@code groups} choices: a few nations are picked as
     * founders, and every other nation adopts the choice of the nearest founder, so
     * choices form contiguous blocs. With {@code independence} probability a nation
     * picks freely instead, so blocs aren't perfectly uniform.
     *
     * @return choice index per nation id (index 0 unused)
     */
    public int[] clusters(Random rng, int groups, float independence) {
        groups = Math.max(1, Math.min(groups, numNations));
        int[] founders = new int[groups];
        int[] founderChoice = new int[groups];
        boolean[] taken = new boolean[numNations + 1];
        for (int g = 0; g < groups; g++) {
            int n;
            do { n = 1 + rng.nextInt(numNations); } while (taken[n]);
            taken[n] = true;
            founders[g] = n;
            founderChoice[g] = g;
        }
        int[] result = new int[numNations + 1];
        for (int n = 1; n <= numNations; n++) {
            float best = Float.MAX_VALUE;
            for (int g = 0; g < groups; g++) {
                float d = distance(n, founders[g]);
                if (d < best) {
                    best = d;
                    result[n] = founderChoice[g];
                }
            }
            if (rng.nextFloat() < independence) {
                result[n] = rng.nextInt(groups);
            }
        }
        return result;
    }

    /**
     * A value in [0, 1] per nation that varies smoothly with geography (a random slope
     * across the map plus a gentle bump), with a little individual jitter.
     */
    public float[] gradient(Random rng, float jitter) {
        double angle = rng.nextDouble() * Math.PI * 2;
        float dx = (float) Math.cos(angle), dz = (float) Math.sin(angle);
        float bumpX = rng.nextFloat(), bumpZ = rng.nextFloat();
        float[] raw = new float[numNations + 1];
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int n = 1; n <= numNations; n++) {
            float[] c = centres[n];
            float bx = c[0] - bumpX, bz = c[1] - bumpZ;
            raw[n] = (c[0] - 0.5f) * dx + (c[1] - 0.5f) * dz + 0.4f * (float) Math.exp(-(bx * bx + bz * bz) * 8);
            min = Math.min(min, raw[n]);
            max = Math.max(max, raw[n]);
        }
        float[] result = new float[numNations + 1];
        for (int n = 1; n <= numNations; n++) {
            float t = max > min ? (raw[n] - min) / (max - min) : 0.5f;
            result[n] = Math.max(0f, Math.min(1f, t + (rng.nextFloat() - 0.5f) * 2f * jitter));
        }
        return result;
    }

    private float distance(int a, int b) {
        float dx = centres[a][0] - centres[b][0], dz = centres[a][1] - centres[b][1];
        return (float) Math.sqrt(dx * dx + dz * dz);
    }
}
