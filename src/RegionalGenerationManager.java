import java.util.HashMap;
import java.util.Map;
import java.util.LinkedList;
import java.util.Queue;
import java.awt.image.BufferedImage;
import java.awt.Color;

public class RegionalGenerationManager {
    
    private final float halfRegion;
    private final float seaLevelHeight;
    private final Map<String, Integer> chunkDistanceToWaterField = new HashMap<>();
    private final long worldSeed;
    private int assignedNoiseTracks = 0;

    // Direct structural object handles replacing old functional callback lambdas
    public final RegionalFactor temperatureMap;
    public final RegionalFactor coldMap;

    public RegionalGenerationManager(long seed, float totalRegionWidth, float seaLevelHeight) {
        this.halfRegion = totalRegionWidth / 2.0f; 
        this.seaLevelHeight = seaLevelHeight;
        this.worldSeed = seed;

        // Latitudinal Baseline Configuration
        this.temperatureMap = new RegionalFactor(1.0f, (cx, cz, worldX, worldZ) -> {
            float normalizedZ = worldZ / this.halfRegion;
            float temp = (float) Math.cos(normalizedZ * (Math.PI / 2.0));
            return Math.max(0.0f, Math.min(1.0f, temp));
        });

        this.coldMap = new RegionalFactor(1.0f, (cx, cz, worldX, worldZ) -> 
            1.0f - this.temperatureMap.evaluate(cx, cz, worldX, worldZ)
        );
    }

    // ==========================================
    //        UNIVERSAL FACTORY DESIGNERS
    // ==========================================

    public RegionalFactor createTemperaturePreference(float optimalTemp, float standardDeviation) {
        return new RegionalFactor(1.0f, (cx, cz, worldX, worldZ) -> {
            float currentTemp = this.temperatureMap.evaluate(cx, cz, worldX, worldZ);
            float diff = currentTemp - optimalTemp;
            double exponent = -(diff * diff) / (2.0 * standardDeviation * standardDeviation);
            return (float) Math.exp(exponent);
        });
    }

    public RegionalFactor createWaterPreference(int optimalDistance, float standardDeviation) {
        return new RegionalFactor(1.0f, (cx, cz, worldX, worldZ) -> {
            int chunksAway = chunkDistanceToWaterField.getOrDefault(cx + "_" + cz, 999);
            float diff = (float) chunksAway - optimalDistance;
            double exponent = -(diff * diff) / (2.0 * standardDeviation * standardDeviation);
            return (float) Math.exp(exponent);
        });
    }

    public RegionalFactor createWaterProximityMap(int maxChunkDistance) {
        return new RegionalFactor(1.0f, (cx, cz, worldX, worldZ) -> {
            int chunksAway = chunkDistanceToWaterField.getOrDefault(cx + "_" + cz, maxChunkDistance);
            return 1.0f - ((float) Math.min(chunksAway, maxChunkDistance) / maxChunkDistance);
        });
    }

    public RegionalFactor createDeepInlandMap(int maxChunkDistance) {
        return new RegionalFactor(1.0f, (cx, cz, worldX, worldZ) -> {
            int chunksAway = chunkDistanceToWaterField.getOrDefault(cx + "_" + cz, maxChunkDistance);
            return (float) Math.min(chunksAway, maxChunkDistance) / maxChunkDistance;
        });
    }

    public RegionalFactor createNoiseMap(float scale) {
        int uniqueOffset = 33333 + (assignedNoiseTracks * 11111);
        assignedNoiseTracks++;
        
        PerlinNoise noise = new PerlinNoise(worldSeed + uniqueOffset);
        return new RegionalFactor(1.0f, (cx, cz, worldX, worldZ) -> 
            (noise.eval(worldX * scale, worldZ * scale) + 1.0f) / 2.0f
        );
    }

    // ==========================================
    //      SIMPLIFIED COUNT EVALUATION ENGINES
    // ==========================================

    /**
     * Evaluates the absolute generation element counts for an entire chunk.
     */
    public int evaluateChunkAssetCount(int cx, int cz, float chunkSize, float baseMultiplier, int maxLimit, RegionalFactor factorNode) {
        float worldX = (cx + 0.5f) * chunkSize;
        float worldZ = (cz + 0.5f) * chunkSize;
        
        float distributionDensity = factorNode.evaluate(cx, cz, worldX, worldZ);
        return Math.min(maxLimit, Math.round(distributionDensity * baseMultiplier)); 
    }

    // ==========================================
    //       REFACTORED VISUALIZATION ENGINE
    // ==========================================

    public BufferedImage generateHeatmap(float totalRegionWidth, float physicalChunkSize, RegionalFactor factorToVisualize) {
        int minChunkX = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);
        int maxChunkX = (int) Math.ceil((totalRegionWidth / 2.0f) / physicalChunkSize);
        int minChunkZ = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);
        int maxChunkZ = (int) Math.ceil((totalRegionWidth / 2.0f) / physicalChunkSize);

        int width = maxChunkX - minChunkX + 1;
        int height = maxChunkZ - minChunkZ + 1;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        float[][] spectrum = {
            {0.85f, 0.00f, 0.00f}, // Red
            {1.00f, 0.50f, 0.00f}, // Orange
            {1.00f, 0.90f, 0.00f}, // Yellow
            {0.00f, 0.70f, 0.10f}  // Green
        };

        for (int z = 0; z < height; z++) {
            int cz = minChunkZ + z;
            for (int x = 0; x < width; x++) {
                int cx = minChunkX + x;
                
                float worldX = (cx + 0.5f) * physicalChunkSize;
                float worldZ = (cz + 0.5f) * physicalChunkSize;

                // Leverage the encapsulation property directly
                float factor = factorToVisualize.evaluate(cx, cz, worldX, worldZ);

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
    //   BREADTH-FIRST-SEARCH DISTANCE PRE-SCAN
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