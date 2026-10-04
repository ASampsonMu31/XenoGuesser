import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Decides where settlements sit and how urban every point of the region is.
 * Sites are chosen by habitability (close to water, low lying, not hot and
 * dry), ranked so the best sites tend to become the largest cities, and each
 * settlement spreads a Gaussian bump of urbanness around its centre.
 * Everything outside those bumps is countryside.
 */
public class SettlementManager {

    public static final class Settlement {
        public final float x;
        public final float z;
        /** 0 is the largest settlement in the region. */
        public final int rank;
        /** Gaussian sigma of the urban footprint, in world units. */
        public final float radius;
        /** Urbanness at the centre, between 0 and 1. */
        public final float intensity;

        private Settlement(float x, float z, int rank, float radius, float intensity) {
            this.x = x;
            this.z = z;
            this.rank = rank;
            this.radius = radius;
            this.intensity = intensity;
        }

        public float influenceAt(float worldX, float worldZ) {
            float dx = worldX - x;
            float dz = worldZ - z;
            return intensity * (float) Math.exp(-(dx * dx + dz * dz) / (2.0f * radius * radius));
        }

        /** Distance from the centre at which this settlement's own urbanness falls to the given level. */
        public float radiusAtUrbanness(float urbanness) {
            if (urbanness >= intensity) {
                return 0.0f;
            }
            return radius * (float) Math.sqrt(2.0 * Math.log(intensity / urbanness));
        }
    }

        // A few cities, many towns and a long tail of small villages
    private static final int SETTLEMENT_COUNT = 340;
    private static final float SMALLEST_RADIUS = 190.0f;
    private static final int PLACEMENT_DRAWS = 600;
    private static final int CANDIDATE_SAMPLES = 14000;
    private static final float LARGEST_RADIUS = 3200.0f;
        private static final float RADIUS_RANK_FALLOFF = 0.5f;
    // Settlements keep this many (summed) sigmas apart so they read as separate places
    private static final float SPACING_FACTOR = 2.1f;
    private static final float SITE_PREFERENCE_POWER = 2.0f;
    private static final float INFLUENCE_CUTOFF_SIGMAS = 3.5f;

    private static final int URBAN_GRID_RESOLUTION = 750;
    private static final int HABITABILITY_GRID_RESOLUTION = 375;

    // Habitability shaping
    private static final float COAST_DISTANCE_CHUNKS = 15.0f;
    private static final float MOISTURE_DISTANCE_CHUNKS = 90.0f;
    private static final float HOT_TEMPERATURE_START = 0.6f;
    private static final float HOT_TEMPERATURE_FULL = 0.97f;
    private static final float HIGHLAND_START = 250.0f;
    private static final float HIGHLAND_FULL = 900.0f;
    // How much of the habitability each harsh condition takes away at its worst
    private static final float HEAT_PENALTY = 0.55f;
    private static final float DESERT_PENALTY = 0.6f;
    private static final float HIGHLAND_PENALTY = 0.85f;
    private static final float RUGGED_PENALTY = 0.95f;
    // Where the terrain turns to ridged mountains (see TerrainMesh)
    private static final float MOUNTAIN_START = 140.0f;
    private static final float MOUNTAIN_FULL = 450.0f;

    private final float halfRegion;
    private final float physicalChunkSize;
    private final float seaLevelHeight;
    private final PerlinNoise terrainNoise;
        private final RegionalGenerationManager regionalManager;
    private final NationGenerationManager nations;
    private final float playableWidth;
    // A settlement's whole built-up area, out to this many sigmas, must lie in one nation
    private static final float ONE_NATION_SIGMAS = 2.2f;


    private final float urbanCellSize;
    private final float[] urbanGrid;
    private final float habitabilityCellSize;
    private final float[] habitabilityGrid;
    private final List<Settlement> settlements;

        public SettlementManager(long seed, float totalRegionWidth, float physicalChunkSize, float seaLevelHeight,
                             PerlinNoise terrainNoise, RegionalGenerationManager regionalManager,
                             NationGenerationManager nations) {
        this.nations = nations;
        this.playableWidth = totalRegionWidth;
                // Grids and sites extend past the playable edge so towns carry on beyond it
        this.halfRegion = totalRegionWidth * 0.5f + RegionalGenerationManager.GENERATION_MARGIN;
        totalRegionWidth = halfRegion * 2.0f;
        this.physicalChunkSize = physicalChunkSize;
        this.seaLevelHeight = seaLevelHeight;
        this.terrainNoise = terrainNoise;
        this.regionalManager = regionalManager;

        this.habitabilityCellSize = totalRegionWidth / HABITABILITY_GRID_RESOLUTION;
        this.habitabilityGrid = new float[(HABITABILITY_GRID_RESOLUTION + 1) * (HABITABILITY_GRID_RESOLUTION + 1)];
        for (int j = 0; j <= HABITABILITY_GRID_RESOLUTION; j++) {
            for (int i = 0; i <= HABITABILITY_GRID_RESOLUTION; i++) {
                float worldX = i * habitabilityCellSize - halfRegion;
                float worldZ = j * habitabilityCellSize - halfRegion;
                habitabilityGrid[j * (HABITABILITY_GRID_RESOLUTION + 1) + i] = computeHabitability(worldX, worldZ);
            }
        }

        this.settlements = Collections.unmodifiableList(placeSettlements(new Random(seed + 24680L)));

        this.urbanCellSize = totalRegionWidth / URBAN_GRID_RESOLUTION;
        this.urbanGrid = new float[(URBAN_GRID_RESOLUTION + 1) * (URBAN_GRID_RESOLUTION + 1)];
        for (Settlement settlement : settlements) {
            stampSettlement(settlement);
        }

        System.out.printf("[SETTLEMENTS] Placed %d settlements (largest radius %.0f)%n",
                settlements.size(), settlements.isEmpty() ? 0.0f : settlements.get(0).radius);
    }

    public List<Settlement> getSettlements() {
        return settlements;
    }

    /** 0 for open countryside up to 1 in the heart of a large city. */
    public float getUrbanness(float worldX, float worldZ) {
        return sampleGrid(urbanGrid, URBAN_GRID_RESOLUTION, urbanCellSize, worldX, worldZ);
    }

    /** How suitable the land is for people to live on, 0 (sea, hot desert, high mountains) to 1. */
    public float getHabitability(float worldX, float worldZ) {
        return sampleGrid(habitabilityGrid, HABITABILITY_GRID_RESOLUTION, habitabilityCellSize, worldX, worldZ);
    }

    /** The settlement contributing the most urbanness at a point, or null if none reaches it. */
    public Settlement dominantSettlementAt(float worldX, float worldZ) {
        Settlement best = null;
        float bestInfluence = 0.0f;
        for (Settlement settlement : settlements) {
            float influence = Math.max(settlement.influenceAt(worldX, worldZ),
                    Math.max(settlement.influenceAt(worldX - Planet.width(), worldZ), settlement.influenceAt(worldX + Planet.width(), worldZ)));
            if (influence > bestInfluence) {
                bestInfluence = influence;
                best = settlement;
            }
        }
        return best;
    }

    /** Wraps urbanness as a RegionalFactor so it can drive or be visualised like any other factor. */
    public RegionalFactor asRegionalFactor() {
        return new RegionalFactor(1.0f, (cx, cz, worldX, worldZ) -> getUrbanness(worldX, worldZ));
    }

    private float computeHabitability(float worldX, float worldZ) {
        float height = TerrainMesh.getLayeredHeight(worldX, worldZ, terrainNoise);
        if (height <= seaLevelHeight) {
            return 0.0f;
        }

        int cx = (int) Math.floor(worldX / physicalChunkSize);
        int cz = (int) Math.floor(worldZ / physicalChunkSize);
        float waterDistance = regionalManager.getChunkDistanceToWater(cx, cz);
        float coast = gaussianFalloff(waterDistance, COAST_DISTANCE_CHUNKS);
        float moisture = gaussianFalloff(waterDistance, MOISTURE_DISTANCE_CHUNKS);

        float temperature = regionalManager.temperatureMap.evaluate(cx, cz, worldX, worldZ);
        float heat = smoothstep(HOT_TEMPERATURE_START, HOT_TEMPERATURE_FULL, temperature);
        // Deserts are where it is both hot and far from water
        float desert = heat * (1.0f - moisture);
        float highland = smoothstep(HIGHLAND_START, HIGHLAND_FULL, height - seaLevelHeight);

        // Rugged, mountainous ground: steep slopes nearby, or the heights where the terrain
        // breaks into ridges, are hardly settled at all
        float step = 200.0f;
        float dx = TerrainMesh.getLayeredHeight(worldX + step, worldZ, terrainNoise) - TerrainMesh.getLayeredHeight(worldX - step, worldZ, terrainNoise);
        float dz = TerrainMesh.getLayeredHeight(worldX, worldZ + step, terrainNoise) - TerrainMesh.getLayeredHeight(worldX, worldZ - step, terrainNoise);
        float slope = (float) Math.hypot(dx, dz) / (2.0f * step);
        // ...and closer in, so a steep hillside counts even on a gentle range
        float near = 60.0f;
        float ndx = TerrainMesh.getLayeredHeight(worldX + near, worldZ, terrainNoise) - TerrainMesh.getLayeredHeight(worldX - near, worldZ, terrainNoise);
        float ndz = TerrainMesh.getLayeredHeight(worldX, worldZ + near, terrainNoise) - TerrainMesh.getLayeredHeight(worldX, worldZ - near, terrainNoise);
        float localSlope = (float) Math.hypot(ndx, ndz) / (2.0f * near);
        float rugged = Math.max(Math.max(smoothstep(0.05f, 0.22f, slope), smoothstep(0.08f, 0.3f, localSlope)),
                smoothstep(MOUNTAIN_START, MOUNTAIN_FULL, height));

        float waterAppeal = 0.2f + 0.5f * coast + 0.3f * moisture;
        return waterAppeal
                * (1.0f - HEAT_PENALTY * heat)
                * (1.0f - DESERT_PENALTY * desert)
                * (1.0f - HIGHLAND_PENALTY * highland)
                * (1.0f - RUGGED_PENALTY * rugged) * (1.0f - RUGGED_PENALTY * rugged);
    }

    /**
     * Ranks are handed out in selection order, so the first (largest)
     * settlements claim the most habitable sites and force the widest spacing.
     */
    private List<Settlement> placeSettlements(Random rand) {
        List<float[]> candidates = new ArrayList<>();
        for (int i = 0; i < CANDIDATE_SAMPLES; i++) {
            float x = (rand.nextFloat() * 2.0f - 1.0f) * halfRegion * 0.97f;
            float z = (rand.nextFloat() * 2.0f - 1.0f) * halfRegion * 0.97f;
            // Towns are founded on the map (they may spread over its join, see stampSettlement)
            // and away from the clipped poles
            if (Math.abs(x) > Planet.width() * 0.5f || Math.abs(z) > Planet.clipHalfHeight() - 3000f) continue;
            float habitability = computeHabitability(x, z);
            if (habitability > 0.02f) {
                candidates.add(new float[] { x, z, (float) Math.pow(habitability, SITE_PREFERENCE_POWER) });
            }
        }

        // Sites are drawn at random in proportion to how appealing they are, rejecting any too
        // close to an existing settlement; scanning every candidate for every settlement
        // grew too slow once there were hundreds of villages
        float[] cumulative = new float[candidates.size()];
        float running = 0.0f;
        for (int c = 0; c < candidates.size(); c++) {
            running += candidates.get(c)[2];
            cumulative[c] = running;
        }
        List<Settlement> placed = new ArrayList<>();
        boolean[] used = new boolean[candidates.size()];
        if (candidates.isEmpty()) {
            return placed;
        }

        for (int rank = 0; rank < SETTLEMENT_COUNT; rank++) {
            float radius = Math.max(SMALLEST_RADIUS, LARGEST_RADIUS * (float) Math.pow(rank + 1, -RADIUS_RANK_FALLOFF));
            float intensity = 0.55f + 0.45f * (float) Math.pow(rank + 1, -0.35);

            int chosen = -1;
            for (int draw = 0; draw < PLACEMENT_DRAWS && chosen < 0; draw++) {
                float pick = rand.nextFloat() * running;
                int lo = 0, hi = cumulative.length - 1;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (cumulative[mid] < pick) lo = mid + 1; else hi = mid;
                }
                float[] candidate = candidates.get(lo);
                                if (!used[lo] && isClearOfSettlements(candidate[0], candidate[1], radius, placed)
                        && liesWithinOneNation(candidate[0], candidate[1], radius)) {
                    chosen = lo;
                }
            }
            if (chosen < 0) {
                continue;
            }
            used[chosen] = true;
            float[] site = candidates.get(chosen);
            placed.add(new Settlement(site[0], site[1], placed.size(), radius, intensity));
        }
        return placed;
    }

        /**
     * True if every bit of land the settlement would build on belongs to the nation at its
     * centre, so cities never straddle a border. Sea inside the footprint doesn't matter.
     */
    private boolean liesWithinOneNation(float x, float z, float radius) {
        int home = nations.getNationAtWorld(x, z, playableWidth);
        float reach = radius * ONE_NATION_SIGMAS;
        int rings = 4;
        for (int ring = 1; ring <= rings; ring++) {
            float r = reach * ring / rings;
            int spokes = 8 * ring;
            for (int s = 0; s < spokes; s++) {
                double angle = (s + 0.5 * (ring & 1)) * Math.PI * 2.0 / spokes;
                float px = x + r * (float) Math.cos(angle);
                float pz = z + r * (float) Math.sin(angle);
                if (nations.getNationAtWorld(px, pz, playableWidth) != home
                        && TerrainMesh.getLayeredHeight(px, pz, terrainNoise) > seaLevelHeight) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isClearOfSettlements(float x, float z, float radius, List<Settlement> placed) {
        for (Settlement other : placed) {
            float spacing = SPACING_FACTOR * (radius + other.radius);
            // Measured round the planet, so towns either side of the map's join keep apart too
            float dx = (float) Planet.wrapX(x - other.x);
            float dz = z - other.z;
            if (dx * dx + dz * dz < spacing * spacing) {
                return false;
            }
        }
        return true;
    }

    /** Combines overlapping settlements as a probabilistic union so urbanness never exceeds 1. */
    private void stampSettlement(Settlement settlement) {
        // A town near the map's join spreads over it: it's stamped where it is, and again a
        // whole way round east and west, which lands in the margin beyond the other edge
        for (float shift : new float[] { 0f, Planet.width(), -Planet.width() }) {
            stampSettlement(settlement, shift);
        }
    }

    private void stampSettlement(Settlement settlement, float shift) {
        float reach = settlement.radius * INFLUENCE_CUTOFF_SIGMAS;
        float sx = settlement.x + shift;
        if (sx + reach < -halfRegion || sx - reach > halfRegion) return;
        int minI = Math.max(0, (int) Math.floor((sx - reach + halfRegion) / urbanCellSize));
        int maxI = Math.min(URBAN_GRID_RESOLUTION, (int) Math.ceil((sx + reach + halfRegion) / urbanCellSize));
        int minJ = Math.max(0, (int) Math.floor((settlement.z - reach + halfRegion) / urbanCellSize));
        int maxJ = Math.min(URBAN_GRID_RESOLUTION, (int) Math.ceil((settlement.z + reach + halfRegion) / urbanCellSize));

        for (int j = minJ; j <= maxJ; j++) {
            float worldZ = j * urbanCellSize - halfRegion;
            for (int i = minI; i <= maxI; i++) {
                float worldX = i * urbanCellSize - halfRegion;
                int index = j * (URBAN_GRID_RESOLUTION + 1) + i;
                float influence = settlement.influenceAt(worldX - shift, worldZ);
                urbanGrid[index] = 1.0f - (1.0f - urbanGrid[index]) * (1.0f - influence);
            }
        }
    }

    private float sampleGrid(float[] grid, int resolution, float cellSize, float worldX, float worldZ) {
        float gx = (worldX + halfRegion) / cellSize;
        float gz = (worldZ + halfRegion) / cellSize;
        gx = Math.max(0.0f, Math.min(resolution - 0.001f, gx));
        gz = Math.max(0.0f, Math.min(resolution - 0.001f, gz));
        int i = (int) gx;
        int j = (int) gz;
        float fx = gx - i;
        float fz = gz - j;
        int stride = resolution + 1;
        int index = j * stride + i;
        float top = grid[index] + (grid[index + 1] - grid[index]) * fx;
        float bottom = grid[index + stride] + (grid[index + stride + 1] - grid[index + stride]) * fx;
        return top + (bottom - top) * fz;
    }

    private static float gaussianFalloff(float distance, float scale) {
        float ratio = distance / scale;
        return (float) Math.exp(-ratio * ratio);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }
}
