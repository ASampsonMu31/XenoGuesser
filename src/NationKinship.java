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
    // Whether two nations share a land border, and their cultures (see assignCultures)
    private final boolean[][] bordering;
    private int[] culture;

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
        bordering = new boolean[numNations + 1][numNations + 1];
        for (int x = 0; x < res; x++) {
            for (int z = 0; z + 1 < map[x].length; z++) {
                int id = map[x][z], east = map[(x + 1) % res][z], south = map[x][z + 1];
                if (id < 1 || id > numNations) continue;
                if (east != id && east >= 1 && east <= numNations) bordering[id][east] = bordering[east][id] = true;
                if (south != id && south >= 1 && south <= numNations) bordering[id][south] = bordering[south][id] = true;
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
     * Groups the nations into {@code count} cultures, each a run of neighbouring nations: the
     * founders are spread as far apart as they can be, and each culture grows out from its
     * founder across shared borders, the nearest nation first, so a culture never jumps over
     * another's land. (A nation with no land route to any founder, an island, joins whichever
     * culture's nation lies nearest it.) Nations of a culture write alike and fly similar flags.
     */
    public int[] assignCultures(Random rng, int count) {
        count = Math.max(1, Math.min(count, numNations));
        int[] result = new int[numNations + 1];
        java.util.Arrays.fill(result, -1);
        float[] reached = new float[numNations + 1];
        java.util.Arrays.fill(reached, Float.MAX_VALUE);
        // Founders: the first at random, then each the farthest from those chosen so far
        int[] founders = new int[count];
        founders[0] = 1 + rng.nextInt(numNations);
        for (int g = 1; g < count; g++) {
            int best = 1;
            float bestDistance = -1f;
            for (int n = 1; n <= numNations; n++) {
                float nearest = Float.MAX_VALUE;
                for (int k = 0; k < g; k++) nearest = Math.min(nearest, distance(n, founders[k]));
                if (nearest > bestDistance) {
                    bestDistance = nearest;
                    best = n;
                }
            }
            founders[g] = best;
        }
        java.util.PriorityQueue<float[]> frontier = new java.util.PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
        for (int g = 0; g < count; g++) {
            reached[founders[g]] = 0f;
            frontier.add(new float[] { 0f, founders[g], g });
        }
        while (!frontier.isEmpty()) {
            float[] next = frontier.poll();
            int n = (int) next[1];
            if (result[n] >= 0) continue;
            result[n] = (int) next[2];
            for (int m = 1; m <= numNations; m++) {
                if (!bordering[n][m] || result[m] >= 0) continue;
                float cost = next[0] + distance(n, m);
                if (cost < reached[m]) {
                    reached[m] = cost;
                    frontier.add(new float[] { cost, m, next[2] });
                }
            }
        }
        // Islands: the culture of the nearest nation that has one
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int n = 1; n <= numNations; n++) {
                if (result[n] >= 0) continue;
                int nearest = -1;
                for (int m = 1; m <= numNations; m++) {
                    if (result[m] >= 0 && (nearest < 0 || distance(n, m) < distance(n, nearest))) nearest = m;
                }
                if (nearest >= 0) {
                    result[n] = result[nearest];
                    changed = true;
                }
            }
        }
        result[0] = 0;
        culture = result;
        return result.clone();
    }

    /** Which culture a nation belongs to (see assignCultures), 0 before they're assigned. */
    public int cultureOf(int nation) {
        return culture == null || nation < 1 || nation > numNations ? 0 : culture[nation];
    }

    /**
     * Whether two nations are at odds: they share a land border but not a culture, so the
     * border between them is fenced and the land along it poor.
     */
    public boolean hostile(int a, int b) {
        if (a == b || a < 1 || b < 1 || a > numNations || b > numNations || !bordering[a][b]) return false;
        return cultureOf(a) != cultureOf(b);
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
