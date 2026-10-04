import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class NationGenerationManager {
    
    public final int[][] nationMap;
    private final int resolution;
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
    
    private float savedTotalRegionWidth = 150000.0f; 
    private final float physicalChunkSize = 100.0f;   

    // --- Configuration (No Sea Modifiers) ---
    // Crossing a cell of sea costs this many cells of lowland
    private static final float SEA_COST = 9f;
    private static final int POINTS_PER_NATION = 6;
    private static final int CLUSTER_SPREAD = 2;
    private float seaLevelHeight;

    // A temporary reference cache to capture the engine's sampler for the overlay drawer
    private PerlinNoise globalHeightSamplerFallback;

    public NationGenerationManager(long seed, int numNations, int mapResolution, float seaLevelHeight, PerlinNoise noise) {
        this.numNations = numNations;
        this.resolution = mapResolution;
        this.seaLevelHeight = seaLevelHeight;
        this.globalHeightSamplerFallback = noise;
        
        this.nationMap = new int[resolution][resolution];
        this.nationColors = new HashMap<>();
        this.nationNoiseMaps = new HashMap<>();
        
        Random noiseSeedRand = new Random(seed + 404);
        
        for (int i = 1; i <= numNations; i++) {
            // The map's colours, chosen beforehand so every nation stands out from every other
            nationColors.put(i, new Color(225 << 24 | MAP_COLOURS[(i - 1) % MAP_COLOURS.length], true));
            
            // Assign a unique Perlin noise generator per country using your class
            nationNoiseMaps.put(i, new PerlinNoise(noiseSeedRand.nextLong()));
        }
    }

    /**
     * Grows the nations from their starting clusters by the cheapest way outwards (a
     * many-source shortest-path spread), each cell going to whichever nation reaches it at
     * least cost. The costs are what give the borders their character:
     * <ul>
     *   <li>winding corridors (the valleys of ridged noise) are cheap, so nations reach along
     *       them in long panhandles, and the borders between follow them;</li>
     *   <li>high ground costs more, so borders tend to run along mountains, and sea far more,
     *       so nations rarely leap across it;</li>
     *   <li>each nation has its own lumpy friction, so it bulges one way and holds back another;</li>
     *   <li>and every cell a little roughness, so the borders are ragged rather than smooth arcs.</li>
     * </ul>
     * Afterwards a majority filter tidies away lone stray cells.
     */
    public void generateTerritories(long seed, float totalRegionWidth) {
        this.savedTotalRegionWidth = totalRegionWidth;
        float halfRegion = totalRegionWidth / 2.0f;
        Random rand = new Random(seed);
        int cells = resolution * resolution;

        // The cost of crossing each cell, before the nation crossing it is known
        PerlinNoise corridors = new PerlinNoise(seed * 7919L + 13L);
        float[] baseCost = new float[cells];
        boolean[] land = new boolean[cells];
        for (int z = 0; z < resolution; z++) {
            for (int x = 0; x < resolution; x++) {
                float worldX = ((x + 0.5f) / resolution) * totalRegionWidth - halfRegion;
                float worldZ = ((z + 0.5f) / resolution) * totalRegionWidth - halfRegion;
                float[] surface = Planet.surface(worldX, worldZ);
                float height = globalHeightSamplerFallback != null ? TerrainMesh.getLayeredHeight(worldX, worldZ, globalHeightSamplerFallback) : 0f;
                float cost;
                land[z * resolution + x] = height > seaLevelHeight;
                if (height <= seaLevelHeight) {
                    cost = SEA_COST;
                } else {
                    // Mountains slow a nation down
                    cost = 1f + Math.min(3f, (height - seaLevelHeight) / 450f);
                }
                // Corridors: near a zero of the noise is cheap, at two scales
                float big = Math.abs(corridors.onSphere(surface, 6f / totalRegionWidth * 10f, 0f, 0f));
                float small = Math.abs(corridors.onSphere(surface, 20f / totalRegionWidth * 10f, 31.7f, 11.3f));
                float corridor = 0.15f + 0.85f * smoothstep(0.02f, 0.25f, Math.min(big, small * 1.4f));
                // Roughness, cell by cell
                cost *= corridor * (0.35f + 1.3f * rand.nextFloat());
                baseCost[z * resolution + x] = cost;
            }
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
        List<int[]> centres = new java.util.ArrayList<>();
        int margin = resolution / 7;
        for (int k = 1; k <= numNations; k++) {
            int centreI = 0, centreJ = 0;
            float spacing = resolution / 6f;
            for (int attempt = 0; attempt < 4000; attempt++) {
                centreI = rand.nextInt(resolution);
                centreJ = margin + rand.nextInt(resolution - 2 * margin);
                // In the midst of a good stretch of land, not on an islet or the coast
                int dry = 0;
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dx = -3; dx <= 3; dx++) {
                        int z = centreJ + dz;
                        if (z >= 0 && z < resolution && land[z * resolution + Math.floorMod(centreI + dx, resolution)]) dry++;
                    }
                }
                if (dry < 40 - attempt / 200) continue;
                boolean clear = true;
                for (int[] other : centres) {
                    int dx = Math.abs(other[0] - centreI);
                    dx = Math.min(dx, resolution - dx);
                    int dz = other[1] - centreJ;
                    if (dx * dx + dz * dz < spacing * spacing) { clear = false; break; }
                }
                if (clear) break;
                if (attempt % 400 == 399) spacing *= 0.75f;
            }
            centres.add(new int[] { centreI, centreJ });
            for (int attempt = 0, placed = 0; placed < POINTS_PER_NATION && attempt < 100; attempt++) {
                int i = Math.floorMod(centreI + rand.nextInt(2 * CLUSTER_SPREAD + 1) - CLUSTER_SPREAD, resolution);
                int j = centreJ + rand.nextInt(2 * CLUSTER_SPREAD + 1) - CLUSTER_SPREAD;
                if (j < 0 || j >= resolution) continue;
                int c = j * resolution + i;
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
            float here = Float.intBitsToFloat((int) next[0]);
            int cx = c % resolution, cz = c / resolution;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nz = cz + dz;
                    if (nz < 0 || nz >= resolution) continue;
                    int n = nz * resolution + Math.floorMod(cx + dx, resolution);
                    if (settled[n]) continue;
                    float step = (dx != 0 && dz != 0 ? 1.41421f : 1f) * 0.5f * (baseCost[c] + baseCost[n]) * frictionOf(friction, nation, n, totalRegionWidth);
                    float cost = here + step;
                    if (cost < reached[n]) {
                        reached[n] = cost;
                        frontier.add(new long[] { Float.floatToIntBits(cost), n, nation });
                    }
                }
            }
        }

        // Tidying: a cell outvoted by its neighbours goes over to them (twice, to catch pairs)
        for (int pass = 0; pass < 2; pass++) {
            int[] tidied = owner.clone();
            int[] votes = new int[numNations + 1];
            for (int z = 1; z + 1 < resolution; z++) {
                for (int x = 0; x < resolution; x++) {
                    java.util.Arrays.fill(votes, 0);
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            votes[owner[(z + dz) * resolution + Math.floorMod(x + dx, resolution)]]++;
                        }
                    }
                    int mine = owner[z * resolution + x], best = mine;
                    for (int k = 1; k <= numNations; k++) if (votes[k] > votes[best]) best = k;
                    if (votes[mine] <= 2 && votes[best] >= 5) tidied[z * resolution + x] = best;
                }
            }
            owner = tidied;
        }

        for (int x = 0; x < resolution; x++) {
            for (int z = 0; z < resolution; z++) {
                int id = owner[z * resolution + x];
                nationMap[x][z] = id != 0 ? id : 1;
            }
        }
    }

    /** How hard a nation finds a cell to cross (its own lumpy preferences), from about 0.35 to 2.9. */
    private float frictionOf(float[][] friction, int nation, int cell, float totalRegionWidth) {
        float[] own = friction[nation];
        if (own == null) {
            own = new float[resolution * resolution];
            java.util.Arrays.fill(own, Float.NaN);
            friction[nation] = own;
        }
        if (Float.isNaN(own[cell])) {
            PerlinNoise noise = nationNoiseMaps.get(nation);
            float worldX = ((cell % resolution + 0.5f) / resolution) * totalRegionWidth - totalRegionWidth * 0.5f;
            float worldZ = ((cell / resolution + 0.5f) / resolution) * totalRegionWidth - totalRegionWidth * 0.5f;
            float value = noise == null ? 0f : noise.onSphere(Planet.surface(worldX, worldZ), COUNTRY_NOISE_SCALE * resolution / totalRegionWidth, 0f, 0f);
            own[cell] = (float) Math.exp(1.05f * value);
        }
        return own[cell];
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    private int findNearestClaimedNation(int[][] grid, int startX, int startZ) {
        int searchRadius = 1;
        while (searchRadius < resolution) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    int nx = Math.floorMod(startX + dx, resolution);
                    int nz = startZ + dz;
                    if (nz >= 0 && nz < resolution) {
                        if (grid[nx][nz] != 0) {
                            return grid[nx][nz];
                        }
                    }
                }
            }
            searchRadius += 3;
        }
        return 1; 
    }

    // Wobbles the borders below the scale of the territory grid
    private final PerlinNoise borderWarp = new PerlinNoise(0x5EA51DE5L);

    /**
     * Which nation a place belongs to. The territory grid is looked up through a small
     * wobble (noise at two scales, so the borders are ragged at every scale) and by a vote of
     * the four nearest cells weighted by nearness, so the borders run smoothly between the
     * cells rather than stepping along them: zooming in never shows the grid.
     */
    public int getNationAtWorld(float worldX, float worldZ, float totalRegionWidth) {
        float cell = totalRegionWidth / resolution;
        float[] surface = Planet.surface(worldX, worldZ);
        float frequency = 1f / (cell * 2.5f), fine = 1f / (cell * 0.6f);
        float warpX = (borderWarp.onSphere(surface, frequency, 0f, 0f) * 1.4f + borderWarp.onSphere(surface, fine, 17.1f, 3.3f) * 0.45f) * cell;
        float warpZ = (borderWarp.onSphere(surface, frequency, 41.9f, 7.7f) * 1.4f + borderWarp.onSphere(surface, fine, 5.3f, 29.4f) * 0.45f) * cell;
        float gx = (worldX + warpX + totalRegionWidth * 0.5f) / cell - 0.5f;
        float gz = (worldZ + warpZ + totalRegionWidth * 0.5f) / cell - 0.5f;
        int x0 = (int) Math.floor(gx), z0 = (int) Math.floor(gz);
        float fx = gx - x0, fz = gz - z0;
        int best = 0;
        float bestWeight = -1f;
        int[] ids = new int[4];
        float[] weights = new float[4];
        for (int k = 0; k < 4; k++) {
            int dx = k & 1, dz = k >> 1;
            int id = nationMap[Math.floorMod(x0 + dx, resolution)][Math.max(0, Math.min(resolution - 1, z0 + dz))];
            float w = (dx == 0 ? 1f - fx : fx) * (dz == 0 ? 1f - fz : fz);
            ids[k] = id;
            for (int j = 0; j <= k; j++) {
                if (ids[j] == id) {
                    weights[j] += w;
                    if (weights[j] > bestWeight) {
                        bestWeight = weights[j];
                        best = id;
                    }
                    break;
                }
            }
        }
        return best;
    }

    /**
     * Safely fetches the mapped color for rendering.
     */
    public Color getNationColor(int nationId) {
        return nationColors.getOrDefault(nationId, new Color(150, 150, 150)); // Fallback grey
    }
}