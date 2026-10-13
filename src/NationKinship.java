import java.util.Random;

/**
 * Makes nation-by-nation choices geographically coherent: neighbouring nations are more
 * likely to share a style, and continuous traits drift gradually across the map, the way
 * building traditions and road conventions spread between neighbours on Earth.
 *
 * Built from each nation's territory on the sphere: its middle (the mean direction of its
 * territory, by area) and which nations it touches.
 */
public final class NationKinship {

    private final int numNations;
    private final double[][] centres;   // [nationId] = unit {x, y, z}
    // Whether two nations share a land border, and their cultures (see assignCultures)
    private final boolean[][] bordering;
    private int[] culture;

    public NationKinship(NationGenerationManager nations) {
        this.numNations = nations.numNations;
        SphereRaster raster = nations.raster();
        int[] territory = nations.territory();
        double[][] sum = new double[numNations + 1][3];
        bordering = new boolean[numNations + 1][numNations + 1];
        double[] d = new double[3];
        for (int t = 0; t < territory.length; t++) {
            int id = territory[t];
            if (id < 1 || id > numNations) continue;
            raster.direction(t, d);
            double area = raster.solidAngle(t);
            for (int k = 0; k < 3; k++) sum[id][k] += d[k] * area;
            for (int k = 0; k < 4; k++) {
                int other = territory[raster.neighbour(t, k)];
                if (other != id && other >= 1 && other <= numNations) bordering[id][other] = bordering[other][id] = true;
            }
        }
        centres = new double[numNations + 1][];
        for (int n = 1; n <= numNations; n++) {
            double length = Math.sqrt(sum[n][0] * sum[n][0] + sum[n][1] * sum[n][1] + sum[n][2] * sum[n][2]);
            // A nation that never claimed land still needs a position; park it on the equator
            centres[n] = length > 0.0 ? new double[] { sum[n][0] / length, sum[n][1] / length, sum[n][2] / length }
                    : new double[] { 1.0, 0.0, 0.0 };
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
     * across the planet plus a gentle bump), with a little individual jitter.
     */
    public float[] gradient(Random rng, float jitter) {
        double[] slope = randomDirection(rng), bump = randomDirection(rng);
        float[] raw = new float[numNations + 1];
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int n = 1; n <= numNations; n++) {
            double[] c = centres[n];
            // (the bump a sixth of the way round the planet across)
            double fromBump = angle(c, bump) / (2.0 * Math.PI);
            raw[n] = (float) (0.5 * dot(c, slope) + 0.4 * Math.exp(-fromBump * fromBump * 8.0));
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

    /** How far apart two nations' middles are: the angle between them, radians. */
    private float distance(int a, int b) {
        return (float) angle(centres[a], centres[b]);
    }

    private static double angle(double[] a, double[] b) {
        return Math.acos(Math.max(-1.0, Math.min(1.0, dot(a, b))));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] randomDirection(Random rng) {
        double y = rng.nextDouble() * 2.0 - 1.0, around = rng.nextDouble() * 2.0 * Math.PI, across = Math.sqrt(1.0 - y * y);
        return new double[] { across * Math.cos(around), y, across * Math.sin(around) };
    }
}
