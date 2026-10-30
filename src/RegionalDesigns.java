import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Which of a small world-wide pool of designs (of bollard, of utility pole...) each nation
 * builds, and where in it. Few designs go round the whole world, so a design alone seldom gives a
 * nation away: neighbours tend to share them, kindred neighbours (bordering, one culture) most of
 * all, hostile ones seldom.
 *
 * A nation builds its main design over most of its land, and may build a neighbour's as well in
 * the part of it nearer that neighbour (where a place is nearer the neighbour's middle than its
 * own, by a share drawn for each), so a design runs on over a border into the land beside it.
 * Where asked for (see the constructor), a nation may also build another of the pool's designs in
 * a region of its own: one side of its land, cut off by a wavering line across it.
 */
public final class RegionalDesigns {

    // Each nation's designs (the first its main one, any others its neighbours'), and for each
    // other one the neighbour it's from and how far towards its own middle it reaches (see designAt)
    private final int[][] designs;
    private final int[][] fromNation;
    private final float[][] reach;
    // Each nation's regions of its own (see designAt): the design built there, the way across the
    // land the region lies (a unit direction, square to the nation's middle), and how far that way
    // (a sine of the angle from its middle) its edge is
    private final int[][] regionDesign;
    private final double[][][] regionAxis;
    private final double[][] regionReach;
    private final NationKinship kinship;
    public final int poolSize;

    /**
     * Deals the designs: a pool of about one for every perNation nations (at least two); each
     * nation in turn takes a neighbour's main design (the more likely the more kindred the
     * neighbours already dealt, see the weights) or else one from the pool (unused ones first);
     * then each may build a neighbour's main design too, near that neighbour.
     */
    public RegionalDesigns(long seed, int numNations, NationKinship kinship, float perNation) {
        this(seed, numNations, kinship, perNation, 0f);
    }

    /**
     * As above, and each nation has a region of its own with a chance of regions (and a second
     * as well, nearly half the time), building another of the pool's designs there (dealt with a
     * Random of their own, so the rest is dealt as it would be without them).
     */
    public RegionalDesigns(long seed, int numNations, NationKinship kinship, float perNation, float regions) {
        this.kinship = kinship;
        Random rand = new Random(seed);
        poolSize = Math.max(2, Math.round(numNations / perNation));
        int[] main = new int[numNations + 1];
        int[] used = new int[poolSize];
        for (int n = 1; n <= numNations; n++) {
            List<Integer> offered = new ArrayList<>();
            List<Float> weights = new ArrayList<>();
            float total = 0f;
            for (int m = 1; m < n; m++) {
                float w = kinship.kin(n, m) ? KIN : kinship.hostile(n, m) ? HOSTILE : kinship.bordering(n, m) ? NEIGHBOUR : 0f;
                if (w <= 0f) continue;
                offered.add(main[m]);
                weights.add(w);
                total += w;
            }
            if (total > 0f && rand.nextFloat() < total / (total + OWN_WAY)) {
                float roll = rand.nextFloat() * total;
                int k = 0;
                while (k < weights.size() - 1 && (roll -= weights.get(k)) > 0f) k++;
                main[n] = offered.get(k);
            } else {
                // (one of the least used yet)
                int least = Integer.MAX_VALUE;
                for (int u : used) least = Math.min(least, u);
                List<Integer> fresh = new ArrayList<>();
                for (int d = 0; d < poolSize; d++) if (used[d] == least) fresh.add(d);
                main[n] = fresh.get(rand.nextInt(fresh.size()));
            }
            used[main[n]]++;
        }
        designs = new int[numNations + 1][];
        fromNation = new int[numNations + 1][];
        reach = new float[numNations + 1][];
        for (int n = 1; n <= numNations; n++) {
            List<int[]> others = new ArrayList<>();
            List<Float> reaches = new ArrayList<>();
            for (int m = 1; m <= numNations && others.size() < 2; m++) {
                if (m == n || main[m] == main[n] || !kinship.bordering(n, m)) continue;
                boolean already = false;
                for (int[] o : others) already |= o[0] == main[m];
                if (already) continue;
                float chance = kinship.kin(n, m) ? 0.4f : kinship.hostile(n, m) ? 0.05f : 0.2f;
                if (rand.nextFloat() >= chance) continue;
                others.add(new int[] { main[m], m });
                reaches.add(0.7f + rand.nextFloat() * 0.3f);
            }
            designs[n] = new int[others.size() + 1];
            fromNation[n] = new int[others.size() + 1];
            reach[n] = new float[others.size() + 1];
            designs[n][0] = main[n];
            for (int k = 0; k < others.size(); k++) {
                designs[n][k + 1] = others.get(k)[0];
                fromNation[n][k + 1] = others.get(k)[1];
                reach[n][k + 1] = reaches.get(k);
            }
        }
        regionDesign = new int[numNations + 1][];
        regionAxis = new double[numNations + 1][][];
        regionReach = new double[numNations + 1][];
        Random regionRand = new Random(seed ^ 0x5E610A5L);
        for (int n = 1; n <= numNations; n++) {
            int count = regions <= 0f || regionRand.nextFloat() >= regions ? 0 : regionRand.nextFloat() < 0.45f ? 2 : 1;
            double[] c = kinship.centre(n);
            // How wide the nation is: half the way to its nearest bordering neighbour's middle
            double radius = 0.12;
            for (int m = 1; m <= numNations; m++) {
                if (!kinship.bordering(n, m)) continue;
                double[] o = kinship.centre(m);
                radius = Math.min(radius, 0.5 * Math.acos(Math.max(-1.0, Math.min(1.0, c[0] * o[0] + c[1] * o[1] + c[2] * o[2]))));
            }
            List<Integer> chosen = new ArrayList<>();
            List<double[]> axes = new ArrayList<>();
            List<Double> edges = new ArrayList<>();
            for (int r = 0; r < count; r++) {
                // (a design the nation doesn't build already, if the pool has one)
                List<Integer> free = new ArrayList<>();
                for (int d = 0; d < poolSize; d++) {
                    boolean taken = chosen.contains(d);
                    for (int own : designs[n]) taken |= own == d;
                    if (!taken) free.add(d);
                }
                if (free.isEmpty()) break;
                int design = free.get(regionRand.nextInt(free.size()));
                // (along the land: a random direction's part across the nation's middle, a second
                // region on the far side from the first)
                double[] axis = null;
                for (int attempt = 0; attempt < 8 && axis == null; attempt++) {
                    double[] dir = { regionRand.nextGaussian(), regionRand.nextGaussian(), regionRand.nextGaussian() };
                    double along = dir[0] * c[0] + dir[1] * c[1] + dir[2] * c[2];
                    double[] t = { dir[0] - c[0] * along, dir[1] - c[1] * along, dir[2] - c[2] * along };
                    double l = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
                    if (l < 1e-9) continue;
                    t = new double[] { t[0] / l, t[1] / l, t[2] / l };
                    boolean apart = true;
                    for (double[] a : axes) apart &= a[0] * t[0] + a[1] * t[1] + a[2] * t[2] < -0.2;
                    if (apart) axis = t;
                }
                if (axis == null) break;
                chosen.add(design);
                axes.add(axis);
                edges.add(Math.sin(radius * (0.05 + 0.4 * regionRand.nextDouble())));
            }
            regionDesign[n] = new int[chosen.size()];
            regionAxis[n] = axes.toArray(new double[0][]);
            regionReach[n] = new double[chosen.size()];
            for (int r = 0; r < chosen.size(); r++) {
                regionDesign[n][r] = chosen.get(r);
                regionReach[n][r] = edges.get(r);
            }
        }
    }

    // How strongly a nation takes after a neighbour already dealt: kindred, any other bordering,
    // hostile; against how strongly it goes its own way
    private static final float KIN = 3f, NEIGHBOUR = 1f, HOSTILE = 0.15f, OWN_WAY = 1.2f;

    /** How the designs went round, for the log: designs used of the pool, the nations, and those building more than one. */
    public String summary() {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        int nations = 0, several = 0, regions = 0;
        for (int n = 1; n < designs.length; n++) {
            nations++;
            for (int d : designsOf(n)) used.add(d);
            if (designsOf(n).length > 1) several++;
            regions += regionDesign[n].length;
        }
        return String.format("%d designs (of %d) over %d nations, %d of them building more than one, %d regions of their own",
                used.size(), poolSize, nations, several, regions);
    }

    /** A nation's designs, its main one first, then its neighbours', then its own regions'. */
    public int[] designsOf(int nation) {
        if (nation < 1 || nation >= designs.length) return new int[0];
        int[] all = java.util.Arrays.copyOf(designs[nation], designs[nation].length + regionDesign[nation].length);
        System.arraycopy(regionDesign[nation], 0, all, designs[nation].length, regionDesign[nation].length);
        return all;
    }

    /** For each of a nation's designs (see designsOf), the neighbour it's from (0 for its main one, -1 for a region of its own). */
    public int[] fromNationsOf(int nation) {
        if (nation < 1 || nation >= fromNation.length) return new int[0];
        int[] all = java.util.Arrays.copyOf(fromNation[nation], fromNation[nation].length + regionDesign[nation].length);
        java.util.Arrays.fill(all, fromNation[nation].length, all.length, -1);
        return all;
    }

    /**
     * Which design a nation builds at a place (a direction from the planet's middle): a
     * neighbour's where the place is nearer that neighbour's middle than reach times its own
     * nation's (the nearest such, relatively), else a region of its own's where it's in one, else
     * its main one; -1 for no nation.
     */
    public int designAt(int nation, GeodesicChunkGrid.Direction place) {
        if (nation < 1 || nation >= designs.length) return -1;
        int[] mine = designs[nation];
        int base = mine[0];
        if (regionDesign[nation].length > 0) {
            double x = place.x(), y = place.y(), z = place.z(), l = Math.sqrt(x * x + y * y + z * z);
            x /= l;
            y /= l;
            z /= l;
            // (the edge wavers: a few gentle swells across the land)
            double waver = 0.006 * (Math.sin(x * 23.0 + y * 7.0 + nation) + Math.sin(y * 19.0 - z * 11.0 + nation * 1.7) + Math.sin(z * 29.0 + x * 13.0));
            for (int r = 0; r < regionDesign[nation].length; r++) {
                double[] a = regionAxis[nation][r];
                if (x * a[0] + y * a[1] + z * a[2] + waver > regionReach[nation][r]) {
                    base = regionDesign[nation][r];
                    break;
                }
            }
        }
        if (mine.length == 1) return base;
        double own = kinship.angleToCentre(nation, place);
        int best = base;
        double bestRatio = 1.0;
        for (int k = 1; k < mine.length; k++) {
            double ratio = kinship.angleToCentre(fromNation[nation][k], place) / Math.max(1e-9, own * reach[nation][k]);
            if (ratio < bestRatio) {
                bestRatio = ratio;
                best = mine[k];
            }
        }
        return best;
    }
}
