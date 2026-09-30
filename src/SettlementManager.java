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

    private static final int SETTLEMENT_COUNT = 120;
    private static final int CANDIDATE_SAMPLES = 14000;
    private static final float LARGEST_RADIUS = 3200.0f;
    private static final float RADIUS_RANK_FALLOFF = 0.55f;
    // Settlements keep this many (summed) sigmas apart so they read as separate places
    private static final float SPACING_FACTOR = 2.4f;
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

    private final float halfRegion;
    private final float physicalChunkSize;
    private final float seaLevelHeight;
    private final PerlinNoise terrainNoise;
    private final RegionalGenerationManager regionalManager;

    private final float urbanCellSize;
    private final float[] urbanGrid;
    private final float habitabilityCellSize;
    private final float[] habitabilityGrid;
    private final List<Settlement> settlements;

    public SettlementManager(long seed, float totalRegionWidth, float physicalChunkSize, float seaLevelHeight,
                             PerlinNoise terrainNoise, RegionalGenerationManager regionalManager) {
        this.halfRegion = totalRegionWidth * 0.5f;
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
            float influence = settlement.influenceAt(worldX, worldZ);
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

        float waterAppeal = 0.2f + 0.5f * coast + 0.3f * moisture;
        return waterAppeal
                * (1.0f - HEAT_PENALTY * heat)
                * (1.0f - DESERT_PENALTY * desert)
                * (1.0f - HIGHLAND_PENALTY * highland);
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
            float habitability = computeHabitability(x, z);
            if (habitability > 0.02f) {
                candidates.add(new float[] { x, z, (float) Math.pow(habitability, SITE_PREFERENCE_POWER) });
            }
        }

        List<Settlement> placed = new ArrayList<>();
        boolean[] used = new boolean[candidates.size()];
        float[] weights = new float[candidates.size()];

        for (int rank = 0; rank < SETTLEMENT_COUNT; rank++) {
            float radius = LARGEST_RADIUS * (float) Math.pow(rank + 1, -RADIUS_RANK_FALLOFF);
            float intensity = 0.55f + 0.45f * (float) Math.pow(rank + 1, -0.35);

            float totalWeight = 0.0f;
            for (int c = 0; c < candidates.size(); c++) {
                weights[c] = 0.0f;
                if (used[c]) {
                    continue;
                }
                float[] candidate = candidates.get(c);
                if (isClearOfSettlements(candidate[0], candidate[1], radius, placed)) {
                    weights[c] = candidate[2];
                    totalWeight += candidate[2];
                }
            }
            if (totalWeight <= 0.0f) {
                break;
            }

            float pick = rand.nextFloat() * totalWeight;
            int chosen = -1;
            for (int c = 0; c < candidates.size(); c++) {
                if (weights[c] <= 0.0f) {
                    continue;
                }
                chosen = c;
                pick -= weights[c];
                if (pick <= 0.0f) {
                    break;
                }
            }

            used[chosen] = true;
            float[] site = candidates.get(chosen);
            placed.add(new Settlement(site[0], site[1], rank, radius, intensity));
        }
        return placed;
    }

    private boolean isClearOfSettlements(float x, float z, float radius, List<Settlement> placed) {
        for (Settlement other : placed) {
            float spacing = SPACING_FACTOR * (radius + other.radius);
            float dx = x - other.x;
            float dz = z - other.z;
            if (dx * dx + dz * dz < spacing * spacing) {
                return false;
            }
        }
        return true;
    }

    /** Combines overlapping settlements as a probabilistic union so urbanness never exceeds 1. */
    private void stampSettlement(Settlement settlement) {
        float reach = settlement.radius * INFLUENCE_CUTOFF_SIGMAS;
        int minI = Math.max(0, (int) Math.floor((settlement.x - reach + halfRegion) / urbanCellSize));
        int maxI = Math.min(URBAN_GRID_RESOLUTION, (int) Math.ceil((settlement.x + reach + halfRegion) / urbanCellSize));
        int minJ = Math.max(0, (int) Math.floor((settlement.z - reach + halfRegion) / urbanCellSize));
        int maxJ = Math.min(URBAN_GRID_RESOLUTION, (int) Math.ceil((settlement.z + reach + halfRegion) / urbanCellSize));

        for (int j = minJ; j <= maxJ; j++) {
            float worldZ = j * urbanCellSize - halfRegion;
            for (int i = minI; i <= maxI; i++) {
                float worldX = i * urbanCellSize - halfRegion;
                int index = j * (URBAN_GRID_RESOLUTION + 1) + i;
                float influence = settlement.influenceAt(worldX, worldZ);
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
