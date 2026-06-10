import java.util.HashMap;
import java.util.Map;
import java.util.LinkedList;
import java.util.Queue;
import java.awt.image.BufferedImage;
import java.awt.Color;

public class RegionalGenerationManager {
    
    // --- CORE WORLD DATA ---
    private final float halfRegion;
    private final float seaLevelHeight;
    private final Map<String, Integer> chunkDistanceToWaterField = new HashMap<>();

    @FunctionalInterface
    public interface GenerationFactor {
        float evaluate(int cx, int cz, float worldX, float worldZ);
    }

    // Global environmental baselines
    public final GenerationFactor temperatureMap;
    public final GenerationFactor coldMap;

    private long worldSeed;

    private int assignedNoiseTracks = 0;

    public RegionalGenerationManager(long seed, float totalRegionWidth, float seaLevelHeight) {
        this.halfRegion = totalRegionWidth / 2.0f; 
        this.seaLevelHeight = seaLevelHeight;
        this.worldSeed = seed;

        // Latitudinal Cosine Temperature curve (Equator -> Poles)
        this.temperatureMap = (cx, cz, worldX, worldZ) -> {
            float normalizedZ = worldZ / this.halfRegion;
            float temp = (float) Math.cos(normalizedZ * (Math.PI / 2.0));
            return Math.max(0.0f, Math.min(1.0f, temp));
        };

        this.coldMap = (cx, cz, worldX, worldZ) -> 1.0f - this.temperatureMap.evaluate(cx, cz, worldX, worldZ);
    }

    // ==========================================
    //    UNIVERSAL FACTORY DESIGNERS
    // ==========================================

    public GenerationFactor createTemperaturePreference(float optimalTemp, float standardDeviation) {
        return (cx, cz, worldX, worldZ) -> {
            float currentTemp = this.temperatureMap.evaluate(cx, cz, worldX, worldZ);
            float diff = currentTemp - optimalTemp;
            double exponent = -(diff * diff) / (2.0 * standardDeviation * standardDeviation);
            return Math.max(0.0f, Math.min(1.0f, (float) Math.exp(exponent)));
        };
    }

    public GenerationFactor createWaterPreference(int optimalDistance, float standardDeviation) {
        return (cx, cz, worldX, worldZ) -> {
            int chunksAway = chunkDistanceToWaterField.getOrDefault(cx + "_" + cz, 999);
            float diff = (float) chunksAway - optimalDistance;
            double exponent = -(diff * diff) / (2.0 * standardDeviation * standardDeviation);
            return Math.max(0.0f, Math.min(1.0f, (float) Math.exp(exponent)));
        };
    }

    public GenerationFactor createWaterProximityMap(int maxChunkDistance) {
        return (cx, cz, worldX, worldZ) -> {
            int chunksAway = chunkDistanceToWaterField.getOrDefault(cx + "_" + cz, maxChunkDistance);
            float weight = 1.0f - ((float) Math.min(chunksAway, maxChunkDistance) / maxChunkDistance);
            return Math.max(0.0f, Math.min(1.0f, weight));
        };
    }

    public GenerationFactor createDeepInlandMap(int maxChunkDistance) {
        return (cx, cz, worldX, worldZ) -> {
            int chunksAway = chunkDistanceToWaterField.getOrDefault(cx + "_" + cz, maxChunkDistance);
            float weight = (float) Math.min(chunksAway, maxChunkDistance) / maxChunkDistance;
            return Math.max(0.0f, Math.min(1.0f, weight));
        };
    }

    // ==========================================
    //    RAW STRUCTURAL NOISE MAPPERS
    // ==========================================

    public GenerationFactor createNoiseMap(float scale) {
        // Automatically guarantee every generated channel gets a completely unique seed track
        int uniqueOffset = 33333 + (assignedNoiseTracks * 11111);
        assignedNoiseTracks++;
        
        PerlinNoise noise = new PerlinNoise(worldSeed + uniqueOffset);
        return (cx, cz, worldX, worldZ) -> (noise.eval(worldX * scale, worldZ * scale) + 1.0f) / 2.0f;
    }

    // ==========================================
    //    LOW-LEVEL CONTINUOUS EVALUATION ENGINES
    // ==========================================

    public float evaluateFactorsMultiplicative(int cx, int cz, float worldX, float worldZ, float powerCurve, GenerationFactor... factors) {
        float finalProbability = 1.0f;
        for (GenerationFactor factor : factors) {
            if (factor != null) {
                finalProbability *= factor.evaluate(cx, cz, worldX, worldZ);
            }
        }
        return (float) Math.pow(finalProbability, powerCurve);
    }

    public float evaluateFactorsBlended(int cx, int cz, float worldX, float worldZ, float powerCurve, GenerationFactor... factors) {
        if (factors.length == 0) return 0.0f;
        float totalWeight = 0.0f;
        int activeFactors = 0;
        for (GenerationFactor factor : factors) {
            if (factor != null) {
                totalWeight += factor.evaluate(cx, cz, worldX, worldZ);
                activeFactors++;
            }
        }
        if (activeFactors == 0) return 0.0f;
        return (float) Math.pow(totalWeight / activeFactors, powerCurve);
    }

    // ==========================================
    //    HIGH-LEVEL COUPLING WRAPPERS (COUNTS)
    // ==========================================

    public int evaluateMultiplicativeCount(int cx, int cz, float chunkSize, float baseMultiplier, float powerCurve, int maxLimit, GenerationFactor... factors) {
        // FIX: Sample from the center of the chunk
        float worldX = (cx + 0.5f) * chunkSize;
        float worldZ = (cz + 0.5f) * chunkSize;
        float factor = evaluateFactorsMultiplicative(cx, cz, worldX, worldZ, powerCurve, factors);
        return Math.min(maxLimit, Math.round(factor * baseMultiplier)); 
    }

    public int evaluateBlendedCount(int cx, int cz, float chunkPhysicalSize, float baseMultiplier, float powerCurve, int maxLimit, GenerationFactor... factors) {
        // FIX: Sample from the center of the chunk
        float worldX = (cx + 0.5f) * chunkPhysicalSize;
        float worldZ = (cz + 0.5f) * chunkPhysicalSize;
        float factor = evaluateFactorsBlended(cx, cz, worldX, worldZ, powerCurve, factors);
        return Math.min(maxLimit, Math.round(factor * baseMultiplier));
    }

    public enum HeatmapMode {
        MULTIPLICATIVE,
        BLENDED
    }


 // ==========================================
    //       STATIC HEATMAP SNAPSHOT GENERATOR
    // ==========================================

    public BufferedImage generateHeatmap(float totalRegionWidth, float physicalChunkSize, HeatmapMode mode, float powerCurve, GenerationFactor... factors) {
        int minChunkX = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);
        int maxChunkX = (int) Math.ceil((totalRegionWidth / 2.0f) / physicalChunkSize);
        int minChunkZ = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);
        int maxChunkZ = (int) Math.ceil((totalRegionWidth / 2.0f) / physicalChunkSize);

        int width = maxChunkX - minChunkX + 1;
        int height = maxChunkZ - minChunkZ + 1;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        float[][] spectrum = {
            {0.85f, 0.00f, 0.00f}, // 0: Red
            {1.00f, 0.50f, 0.00f}, // 1: Orange
            {1.00f, 0.90f, 0.00f}, // 2: Yellow
            {0.00f, 0.70f, 0.10f}  // 3: Green
        };

        for (int z = 0; z < height; z++) {
            int cz = minChunkZ + z;
            for (int x = 0; x < width; x++) {
                int cx = minChunkX + x;
                
                // Sample from the center of the chunk for accurate alignment
                float worldX = (cx + 0.5f) * physicalChunkSize;
                float worldZ = (cz + 0.5f) * physicalChunkSize;

                // 1. Evaluate the raw values directly
                float rawValue = switch (mode) {
                    case MULTIPLICATIVE -> evaluateFactorsMultiplicative(cx, cz, worldX, worldZ, powerCurve, factors);
                    case BLENDED        -> evaluateFactorsBlended(cx, cz, worldX, worldZ, powerCurve, factors);
                };

                // 2. FIXED: Use the raw value directly as the factor (Absolute 0.0 to 1.0)
                float factor = Math.max(0.0f, Math.min(1.0f, rawValue)); 

                // Map the normalized 0.0 - 1.0 factor across the 4-color multi-stage ramp
                float r, g, b;
                if (factor <= 0.333f) {
                    float t = factor / 0.333f;
                    r = spectrum[0][0] + t * (spectrum[1][0] - spectrum[0][0]);
                    g = spectrum[0][1] + t * (spectrum[1][1] - spectrum[0][1]);
                    b = spectrum[0][2] + t * (spectrum[1][2] - spectrum[0][2]);
                } else if (factor <= 0.666f) {
                    float t = (factor - 0.333f) / 0.333f;
                    r = spectrum[1][0] + t * (spectrum[2][0] - spectrum[1][0]);
                    g = spectrum[1][1] + t * (spectrum[2][1] - spectrum[1][1]);
                    b = spectrum[1][2] + t * (spectrum[2][2] - spectrum[1][2]);
                } else {
                    float t = (factor - 0.666f) / 0.334f;
                    r = spectrum[2][0] + t * (spectrum[3][0] - spectrum[2][0]);
                    g = spectrum[2][1] + t * (spectrum[3][1] - spectrum[2][1]);
                    b = spectrum[2][2] + t * (spectrum[3][2] - spectrum[2][2]);
                }

                int ir = Math.max(0, Math.min(255, Math.round(r * 255.0f)));
                int ig = Math.max(0, Math.min(255, Math.round(g * 255.0f)));
                int ib = Math.max(0, Math.min(255, Math.round(b * 255.0f)));

                Color pixelColor = new Color(ir, ig, ib, 255);
                image.setRGB(x, z, pixelColor.getRGB());
            }
        }
        return image;
    }
    
    // ==========================================
    //    BREADTH-FIRST-SEARCH TOPOGRAPHY SCANNER
    // ==========================================

    public void precalculateWaterDistanceField(float totalRegionWidth, PerlinNoise worldNoise, float physicalChunkSize){
        chunkDistanceToWaterField.clear();
        
        int minChunkX = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);
        int maxChunkX = (int) Math.ceil((totalRegionWidth / 2.0f) / physicalChunkSize);
        int minChunkZ = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);
        int maxChunkZ = (int) Math.ceil((totalRegionWidth / 2.0f) / physicalChunkSize);

        Queue<ChunkNode> queue = new LinkedList<>();

        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                // FIX: Check water layout at the center of the chunk as well
                float worldX = (cx + 0.5f) * physicalChunkSize;
                float worldZ = (cz + 0.5f) * physicalChunkSize;
                float terrainHeight = TerrainMesh.getLayeredHeight(worldX, worldZ, worldNoise);

                if (terrainHeight <= seaLevelHeight) {
                    String key = cx + "_" + cz;
                    chunkDistanceToWaterField.put(key, 0);
                    queue.add(new ChunkNode(cx, cz, 0));
                }
            }
        }

        int[] dX = {1, -1, 0, 0};
        int[] dZ = {0, 0, 1, -1};

        while (!queue.isEmpty()) {
            ChunkNode current = queue.poll();

            for (int i = 0; i < 4; i++) {
                int neighborX = current.cx + dX[i];
                int neighborZ = current.cz + dZ[i];

                if (neighborX < minChunkX || neighborX > maxChunkX || neighborZ < minChunkZ || neighborZ > maxChunkZ) {
                    continue;
                }

                String neighborKey = neighborX + "_" + neighborZ;
                int nextDistance = current.distance + 1;

                if (!chunkDistanceToWaterField.containsKey(neighborKey)) {
                    chunkDistanceToWaterField.put(neighborKey, nextDistance);
                    queue.add(new ChunkNode(neighborX, neighborZ, nextDistance));
                }
            }
        }
    }

    private static class ChunkNode {
        int cx, cz, distance;
        ChunkNode(int cx, int cz, int distance) { 
            this.cx = cx; 
            this.cz = cz; 
            this.distance = distance; 
        }
    }
}