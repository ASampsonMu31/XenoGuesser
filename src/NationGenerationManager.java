import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The nations' territories, over the whole sphere: a SphereRaster of which nation holds each
 * texel (a few hundred units across), grown from each nation's starting places by the
 * cheapest way out across the ground, and looked up by direction. The map draws them by
 * looking up each of its pixels' directions, like every other overlay.
 */
public class NationGenerationManager {

    // Texels along each cube face's side: about 370 units across at a face's middle and half
    // that at its corners (the old chart's cells were 390 at the equator)
    private static final int RASTER_SIDE = 128;
    // The size of a territory cell the costs and distances are measured in (the old chart's
    // cells at the equator, so the tuning below keeps its meaning)
    private static final float CELL = 150_000f / 384f;

    private final SphereRaster raster = new SphereRaster(RASTER_SIDE);
    // Which nation holds each texel (1 to numNations)
    private final int[] territory = new int[raster.size()];
    public final int numNations;
    private final Map<Integer, Color> nationColors;
    // Fourteen colours far apart from one another (as many as there can be nations), the
    // most distinct first; the same every game, as they belong to the map, not the world
    private static final int[] MAP_COLOURS = {
        0xE6194B, 0x3CB44B, 0xFFE119, 0x4363D8, 0xF58231, 0x911EB4, 0x42D4F4,
        0xF032E6, 0xBFEF45, 0xFABED4, 0x469990, 0x9A6324, 0x800000, 0xAAFFC3
    };

    // --- Per-Country Noise Maps ---
    private final Map<Integer, PerlinNoise> nationNoiseMaps;
    private static final float COUNTRY_NOISE_SCALE = 0.01f; // Scale of favorable/unfavorable expansion terrain

    // Crossing a cell of sea costs this many cells of lowland
    private static final float SEA_COST = 9f;
    private static final int POINTS_PER_NATION = 6;
    // How many texel steps from its centre a nation's starting places may be
    private static final int CLUSTER_SPREAD = 2;
    // Nations start no further from the equator than this, and at first at least this far
    // apart along the ground (in world units; relaxed if the land runs short)
    private static final double START_LATITUDE = Math.toRadians(70.0);
    private static final double START_SPACING = 25_000.0;
    private final float seaLevelHeight;
    private final PerlinNoise terrainNoise;

    public NationGenerationManager(long seed, int numNations, float seaLevelHeight, PerlinNoise noise) {
        this.numNations = numNations;
        this.seaLevelHeight = seaLevelHeight;
        this.terrainNoise = noise;
        this.nationColors = new HashMap<>();
        this.nationNoiseMaps = new HashMap<>();

        Random noiseSeedRand = new Random(seed + 404);
        for (int i = 1; i <= numNations; i++) {
            // The map's colours, chosen beforehand so every nation stands out from every other
            nationColors.put(i, new Color(225 << 24 | MAP_COLOURS[(i - 1) % MAP_COLOURS.length], true));
            // Each country its own noise, for its likes and dislikes of where to spread
            nationNoiseMaps.put(i, new PerlinNoise(noiseSeedRand.nextLong()));
        }
    }

    /** The size of a territory cell in world units: borders' distances (see InfrastructureManager) are counted in these. */
    public static float cellSize() {
        return CELL;
    }

    /** The raster the territories are held on, and which nation holds each of its texels. */
    public SphereRaster raster() {
        return raster;
    }

    public int[] territory() {
        return territory;
    }

    /**
     * Grows the nations from their starting clusters by the cheapest way outwards (a
     * many-source shortest-path spread over the raster, each step costing its true length in
     * cells), each texel going to whichever nation reaches it at least cost. The costs are
     * what give the borders their character:
     * <ul>
     *   <li>winding corridors (the valleys of ridged noise) are cheap, so nations reach along
     *       them in long panhandles, and the borders between follow them;</li>
     *   <li>high ground costs more, so borders tend to run along mountains, and sea far more,
     *       so nations rarely leap across it;</li>
     *   <li>each nation has its own lumpy friction, so it bulges one way and holds back another;</li>
     *   <li>and every texel a little roughness, so the borders are ragged rather than smooth arcs.</li>
     * </ul>
     * Afterwards a majority filter tidies away lone stray texels.
     */
    public void generateTerritories(long seed) {
        Random rand = new Random(seed);
        int cells = raster.size();
        float width = Planet.width();

        // Each texel's neighbours: across its four edges, then its four corners (a step
        // across one edge and then the other), and how far each is in cells
        int[][] around = new int[cells][8];
        float[][] step = new float[cells][8];
        double[] here = new double[3], there = new double[3];
        for (int c = 0; c < cells; c++) {
            for (int k = 0; k < 4; k++) around[c][k] = raster.neighbour(c, k);
            around[c][4] = raster.neighbour(around[c][0], 2);
            around[c][5] = raster.neighbour(around[c][0], 3);
            around[c][6] = raster.neighbour(around[c][1], 2);
            around[c][7] = raster.neighbour(around[c][1], 3);
            raster.direction(c, here);
            for (int k = 0; k < 8; k++) {
                raster.direction(around[c][k], there);
                double dot = here[0] * there[0] + here[1] * there[1] + here[2] * there[2];
                step[c][k] = (float) (Math.acos(Math.max(-1.0, Math.min(1.0, dot))) * Planet.radius() / CELL);
            }
        }

        // The cost of crossing each texel, before the nation crossing it is known
        PerlinNoise corridors = new PerlinNoise(seed * 7919L + 13L);
        float[] baseCost = new float[cells];
        boolean[] land = new boolean[cells];
        for (int c = 0; c < cells; c++) {
            GeodesicChunkGrid.Direction d = raster.direction(c);
            float[] surface = surface(d);
            float height = TerrainMesh.getLayeredHeight(d, terrainNoise);
            float cost;
            land[c] = height > seaLevelHeight;
            if (height <= seaLevelHeight) {
                cost = SEA_COST;
            } else {
                // Mountains slow a nation down
                cost = 1f + Math.min(3f, (height - seaLevelHeight) / 450f);
            }
            // Corridors: near a zero of the noise is cheap, at two scales
            float big = Math.abs(corridors.onSphere(surface, 6f / width * 10f, 0f, 0f));
            float small = Math.abs(corridors.onSphere(surface, 20f / width * 10f, 31.7f, 11.3f));
            float corridor = 0.15f + 0.85f * smoothstep(0.02f, 0.25f, Math.min(big, small * 1.4f));
            // Roughness, texel by texel
            cost *= corridor * (0.35f + 1.3f * rand.nextFloat());
            baseCost[c] = cost;
        }

        // Each nation's own friction, sampled where it's needed and kept
        float[][] friction = new float[numNations + 1][];

        // The starting clusters
        int[] owner = new int[cells];
        float[] reached = new float[cells];
        java.util.Arrays.fill(reached, Float.MAX_VALUE);
        java.util.PriorityQueue<long[]> frontier = new java.util.PriorityQueue<>((p, q) -> Float.compare(Float.intBitsToFloat((int) p[0]), Float.intBitsToFloat((int) q[0])));
        // Each nation starts on land, well away from the others (the spacing relaxed if the
        // land runs short), and away from the poles
        List<GeodesicChunkGrid.Direction> centres = new ArrayList<>();
        for (int k = 1; k <= numNations; k++) {
            int centre = 0;
            double spacing = START_SPACING / Planet.radius();
            for (int attempt = 0; attempt < 4000; attempt++) {
                centre = raster.index(SphereMath.direction(SphereMath.randomPlace(rand, START_LATITUDE)));
                // In the midst of a good stretch of land, not on an islet or the coast
                int dry = 0;
                for (int c : block(centre, 3)) if (land[c]) dry++;
                if (dry < 40 - attempt / 200) continue;
                boolean clear = true;
                for (GeodesicChunkGrid.Direction other : centres) {
                    if (other.angleTo(raster.direction(centre)) < spacing) { clear = false; break; }
                }
                if (clear) break;
                if (attempt % 400 == 399) spacing *= 0.75;
            }
            centres.add(raster.direction(centre));
            List<Integer> cluster = block(centre, CLUSTER_SPREAD);
            for (int attempt = 0, placed = 0; placed < POINTS_PER_NATION && attempt < 100; attempt++) {
                int c = cluster.get(rand.nextInt(cluster.size()));
                if (reached[c] == 0f) continue;
                reached[c] = 0f;
                frontier.add(new long[] { Float.floatToIntBits(0f), c, k });
                placed++;
            }
        }

        // The spread: always on from the cheapest place reached so far
        boolean[] settled = new boolean[cells];
        while (!frontier.isEmpty()) {
            long[] next = frontier.poll();
            int c = (int) next[1], nation = (int) next[2];
            if (settled[c]) continue;
            settled[c] = true;
            owner[c] = nation;
            float sofar = Float.intBitsToFloat((int) next[0]);
            for (int k = 0; k < 8; k++) {
                int n = around[c][k];
                if (settled[n]) continue;
                float cost = sofar + step[c][k] * 0.5f * (baseCost[c] + baseCost[n]) * frictionOf(friction, nation, n);
                if (cost < reached[n]) {
                    reached[n] = cost;
                    frontier.add(new long[] { Float.floatToIntBits(cost), n, nation });
                }
            }
        }

        // Tidying: a texel outvoted by its neighbours goes over to them (twice, to catch pairs)
        for (int pass = 0; pass < 2; pass++) {
            int[] tidied = owner.clone();
            int[] votes = new int[numNations + 1];
            for (int c = 0; c < cells; c++) {
                java.util.Arrays.fill(votes, 0);
                votes[owner[c]]++;
                for (int k = 0; k < 8; k++) votes[owner[around[c][k]]]++;
                int mine = owner[c], best = mine;
                for (int k = 1; k <= numNations; k++) if (votes[k] > votes[best]) best = k;
                if (votes[mine] <= 2 && votes[best] >= 5) tidied[c] = best;
            }
            owner = tidied;
        }

        for (int c = 0; c < cells; c++) territory[c] = owner[c] != 0 ? owner[c] : 1;
    }

    /** The texels within reach steps of one across the raster (a square of them on its face, carried over face edges). */
    private List<Integer> block(int centre, int reach) {
        List<Integer> out = new ArrayList<>();
        int row = centre;
        for (int k = 0; k < reach; k++) row = raster.neighbour(row, 3);
        for (int j = -reach; j <= reach; j++) {
            int c = row;
            for (int k = 0; k < reach; k++) c = raster.neighbour(c, 1);
            for (int i = -reach; i <= reach; i++) {
                out.add(c);
                c = raster.neighbour(c, 0);
            }
            row = raster.neighbour(row, 2);
        }
        return out;
    }

    /** How hard a nation finds a texel to cross (its own lumpy preferences), from about 0.35 to 2.9. */
    private float frictionOf(float[][] friction, int nation, int cell) {
        float[] own = friction[nation];
        if (own == null) {
            own = new float[raster.size()];
            java.util.Arrays.fill(own, Float.NaN);
            friction[nation] = own;
        }
        if (Float.isNaN(own[cell])) {
            PerlinNoise noise = nationNoiseMaps.get(nation);
            float value = noise == null ? 0f : noise.onSphere(surface(raster.direction(cell)), COUNTRY_NOISE_SCALE / CELL, 0f, 0f);
            own[cell] = (float) Math.exp(1.05f * value);
        }
        return own[cell];
    }

    /** The point on the datum in a direction, as noise is sampled. */
    private static float[] surface(GeodesicChunkGrid.Direction d) {
        double r = Planet.radius() / Math.sqrt(d.x() * d.x() + d.y() * d.y() + d.z() * d.z());
        return new float[] { (float) (d.x() * r), (float) (d.y() * r), (float) (d.z() * r) };
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    // Wobbles the borders below the scale of the territory raster
    private final PerlinNoise borderWarp = new PerlinNoise(0x5EA51DE5L);

    /**
     * Which nation a place belongs to. The territory raster is looked up through a small
     * wobble along the ground (noise at two scales, so the borders are ragged at every scale)
     * and by a vote of the four nearest texels weighted by nearness, so the borders run
     * smoothly between the texels rather than stepping along them: zooming in never shows them.
     */
    public int getNationAt(GeodesicChunkGrid.Direction direction) {
        return getNationAt(SphereMath.atAltitude(direction, 0.0));
    }

    public int getNationAt(SpherePosition place) {
        SpherePosition onDatum = SphereMath.atAltitude(place, 0.0);
        float[] surface = { (float) onDatum.x(), (float) onDatum.y(), (float) onDatum.z() };
        float frequency = 1f / (CELL * 2.5f), fine = 1f / (CELL * 0.6f);
        float warpEast = (borderWarp.onSphere(surface, frequency, 0f, 0f) * 1.4f + borderWarp.onSphere(surface, fine, 17.1f, 3.3f) * 0.45f) * CELL;
        float warpSouth = (borderWarp.onSphere(surface, frequency, 41.9f, 7.7f) * 1.4f + borderWarp.onSphere(surface, fine, 5.3f, 29.4f) * 0.45f) * CELL;
        SpherePosition warped = SphereFrame.at(onDatum).toSphere(warpEast, 0.0, warpSouth);
        int[] texels = new int[4];
        float[] weights = new float[4];
        raster.corners(warped.x(), warped.y(), warped.z(), texels, weights);
        int best = 0;
        float bestWeight = -1f;
        int[] ids = new int[4];
        float[] votes = new float[4];
        for (int k = 0; k < 4; k++) {
            int id = territory[texels[k]];
            ids[k] = id;
            for (int j = 0; j <= k; j++) {
                if (ids[j] == id) {
                    votes[j] += weights[k];
                    if (votes[j] > bestWeight) {
                        bestWeight = votes[j];
                        best = id;
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** Which nation holds a point on the map (see MapProjection): the place under it. */
    public int getNationAtMap(float mapX, float mapZ) {
        return getNationAt(MapProjection.direction(mapX, mapZ));
    }

    /**
     * Safely fetches the mapped color for rendering.
     */
    // How far (a weighted distance, see colourDistance) a nation's colour must be from the sea's
    private static final double SEA_COLOUR_CLEARANCE = 120.0;

    /**
     * Colours the nations again from the palette without those too like the sea's on the map
     * (M56: the sea's colour is the planet's own, so any of the palette may be close to it).
     */
    public void avoidSeaColour(Color sea) {
        java.util.List<Integer> palette = new java.util.ArrayList<>();
        for (int rgb : MAP_COLOURS) if (colourDistance(new Color(rgb), sea) >= SEA_COLOUR_CLEARANCE) palette.add(rgb);
        if (palette.isEmpty()) return;
        for (Integer id : new java.util.ArrayList<>(nationColors.keySet())) {
            nationColors.put(id, new Color(225 << 24 | palette.get((id - 1) % palette.size()), true));
        }
    }

    /** How different two colours look: the RGB distance weighted towards how the eye sees it ("redmean"). */
    private static double colourDistance(Color a, Color b) {
        double r = (a.getRed() + b.getRed()) / 2.0;
        double dr = a.getRed() - b.getRed(), dg = a.getGreen() - b.getGreen(), db = a.getBlue() - b.getBlue();
        return Math.sqrt((2 + r / 256) * dr * dr + 4 * dg * dg + (2 + (255 - r) / 256) * db * db);
    }

    public Color getNationColor(int nationId) {
        return nationColors.getOrDefault(nationId, new Color(150, 150, 150)); // Fallback grey
    }
}
