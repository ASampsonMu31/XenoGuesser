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
 */
public final class RegionalDesigns {

    // Each nation's designs (the first its main one, any others its neighbours'), and for each
    // other one the neighbour it's from and how far towards its own middle it reaches (see designAt)
    private final int[][] designs;
    private final int[][] fromNation;
    private final float[][] reach;
    private final NationKinship kinship;
    public final int poolSize;

    /**
     * Deals the designs: a pool of about one for every perNation nations (at least two); each
     * nation in turn takes a neighbour's main design (the more likely the more kindred the
     * neighbours already dealt, see the weights) or else one from the pool (unused ones first);
     * then each may build a neighbour's main design too, near that neighbour.
     */
    public RegionalDesigns(long seed, int numNations, NationKinship kinship, float perNation) {
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
    }

    // How strongly a nation takes after a neighbour already dealt: kindred, any other bordering,
    // hostile; against how strongly it goes its own way
    private static final float KIN = 3f, NEIGHBOUR = 1f, HOSTILE = 0.15f, OWN_WAY = 1.2f;

    /** How the designs went round, for the log: designs used of the pool, the nations, and those building more than one. */
    public String summary() {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        int nations = 0, several = 0;
        for (int n = 1; n < designs.length; n++) {
            nations++;
            for (int d : designs[n]) used.add(d);
            if (designs[n].length > 1) several++;
        }
        return String.format("%d designs (of %d) over %d nations, %d of them building more than one", used.size(), poolSize, nations, several);
    }

    /** A nation's designs, its main one first. */
    public int[] designsOf(int nation) {
        return nation >= 1 && nation < designs.length ? designs[nation] : new int[0];
    }

    /** For each of a nation's designs, the neighbour it's from (0 for its main one). */
    public int[] fromNationsOf(int nation) {
        return nation >= 1 && nation < fromNation.length ? fromNation[nation] : new int[0];
    }

    /**
     * Which design a nation builds at a place (a direction from the planet's middle): a
     * neighbour's where the place is nearer that neighbour's middle than reach times its own
     * nation's (the nearest such, relatively), else its main one; -1 for no nation.
     */
    public int designAt(int nation, GeodesicChunkGrid.Direction place) {
        if (nation < 1 || nation >= designs.length) return -1;
        int[] mine = designs[nation];
        if (mine.length == 1) return mine[0];
        double own = kinship.angleToCentre(nation, place);
        int best = mine[0];
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
