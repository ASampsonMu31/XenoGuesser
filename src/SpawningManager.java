import java.util.HashMap;
import java.util.Map;
import java.util.LinkedList;
import java.util.Queue;
import java.awt.image.BufferedImage;
import java.awt.Color;

public class SpawningManager {
    
    // --- CORE WORLD DATA ---
    private final float halfRegion;
    private final float seaLevelHeight;
    
    // Maps chunk coordinate strings "cx_cz" to distance values (in chunks) away from nearest water
    private final Map<String, Integer> chunkDistanceToWaterField = new HashMap<>();

    // --- REUSABLE NOISE OBJECTS ---
    private final PerlinNoise climateNoise1;
    private final PerlinNoise climateNoise2;

    // --- MODULAR INTERFACE FOR INFLUENCE FACTORS ---
    @FunctionalInterface
    public interface SpawningFactor {
        float evaluate(int cx, int cz, float worldX, float worldZ);
    }

    // Global environmental indicators
    public final SpawningFactor temperatureMap;
    public final SpawningFactor coldMap;

    public SpawningManager(long seed, float totalRegionWidth, float seaLevelHeight) {
        this.halfRegion = totalRegionWidth / 2.0f; 
        this.seaLevelHeight = seaLevelHeight;
        
        this.climateNoise1 = new PerlinNoise(seed + 11111L);
        this.climateNoise2 = new PerlinNoise(seed + 22222L);

        // Raw temperature mapping: Cosine curve over latitude (1.0 at equator, 0.0 at poles)
        this.temperatureMap = (cx, cz, worldX, worldZ) -> {
            float normalizedZ = worldZ / this.halfRegion;
            float temp = (float) Math.cos(normalizedZ * (Math.PI / 2.0));
            return Math.max(0.0f, Math.min(1.0f, temp));
        };

        this.coldMap = (cx, cz, worldX, worldZ) -> {
            return 1.0f - this.temperatureMap.evaluate(cx, cz, worldX, worldZ);
        };
    }

    // ==========================================
    //    FLORA FACTORY DESIGNER (DYNAMIC PARAMETERS)
    // ==========================================

    public SpawningFactor createFloraTemperaturePreference(float optimalTemp, float standardDeviation) {
        return (cx, cz, worldX, worldZ) -> {
            // Reads raw temperature dynamically based on the correct world position
            float currentTemp = this.temperatureMap.evaluate(cx, cz, worldX, worldZ);
            
            // Gaussian Bell Curve Equation
            float diff = currentTemp - optimalTemp;
            double exponent = -(diff * diff) / (2.0 * standardDeviation * standardDeviation);
            float fitness = (float) Math.exp(exponent);
            
            return Math.max(0.0f, Math.min(1.0f, fitness));
        };
    }

    /**
     * Creates a customized proximity map targeting a specific water distance field.
     * @param optimalDistance Target distance away from water measured in chunks. (e.g. 0 for shore lines).
     * @param standardDeviation Controls how strict this preference is. Small values mean a strict boundary line.
     */
    public SpawningFactor createFloraWaterPreference(int optimalDistance, float standardDeviation) {
        return (cx, cz, worldX, worldZ) -> {
            // Default to max distance if uncalculated or out of bounds
            int chunksAway = chunkDistanceToWaterField.getOrDefault(cx + "_" + cz, 999);
            
            float diff = (float) chunksAway - optimalDistance;
            double exponent = -(diff * diff) / (2.0 * standardDeviation * standardDeviation);
            float fitness = (float) Math.exp(exponent);
            
            return Math.max(0.0f, Math.min(1.0f, fitness));
        };
    }

    // ==========================================
    //    PRE-DEFINED REUSABLE FACTOR COMPONENT MAPS
    // ==========================================

    /**
     * Legacy linear proximity map. 1.0 right at the coast line, dropping to 0.0 inland.
     */
    public SpawningFactor createWaterProximityMap(int maxChunkDistance) {
        return (cx, cz, worldX, worldZ) -> {
            int chunksAway = chunkDistanceToWaterField.getOrDefault(cx + "_" + cz, maxChunkDistance);
            float weight = 1.0f - ((float) Math.min(chunksAway, maxChunkDistance) / maxChunkDistance);
            return Math.max(0.0f, Math.min(1.0f, weight));
        };
    }

    /**
     * Distance away from water. 0.0 at coastlines, climbing up to 1.0 deep inland.
     */
    public SpawningFactor createDeepInlandMap(int maxChunkDistance) {
        return (cx, cz, worldX, worldZ) -> {
            int chunksAway = chunkDistanceToWaterField.getOrDefault(cx + "_" + cz, maxChunkDistance);
            float weight = (float) Math.min(chunksAway, maxChunkDistance) / maxChunkDistance;
            return Math.max(0.0f, Math.min(1.0f, weight));
        };
    }

    /**
     * Raw Perlin Noise patchiness layer.
     */
    public SpawningFactor createNoiseMap(float scale, boolean useAlternativeNoise) {
        PerlinNoise noise = useAlternativeNoise ? climateNoise2 : climateNoise1;
        return (cx, cz, worldX, worldZ) -> {
            float raw = noise.eval(worldX * scale, worldZ * scale);
            return (raw + 1.0f) / 2.0f; // Remap from [-1, 1] to [0, 1]
        };
    }

    // ==========================================
    //    EVALUATION ENGINE
    // ==========================================

    public int evaluateMultiplicativeCount(int cx, int cz, float chunkSize, float baseMultiplier, float powerCurve, int maxLimit, SpawningFactor... factors) {
        float finalProbability = 1.0f;
        float worldX = cx * chunkSize;
        float worldZ = cz * chunkSize;

        // 1. Gather environmental fitness (Result is between 0.0f and 1.0f)
        for (SpawningFactor factor : factors) {
            finalProbability *= factor.evaluate(cx, cz, worldX, worldZ);
        }

        // 2. Apply the power rule to the FRACTION (e.g., 0.5^2 = 0.25)
        // This perfectly contains the math so numbers never explode unintentionally!
        float shapedProbability = (float) Math.pow(finalProbability, powerCurve);

        // 3. Scale by base multiplier and apply the absolute limit
        int calculatedCount = Math.round(shapedProbability * baseMultiplier);
        return Math.min(maxLimit, calculatedCount); 
    }


    public int evaluateBlendedCount(int cx, int cz, float chunkPhysicalSize, float baseMultiplier, float powerCurve, int maxLimit, SpawningFactor... factors) {
        if (factors.length == 0) return Math.min(maxLimit, Math.round(baseMultiplier));
        
        float worldX = cx * chunkPhysicalSize;
        float worldZ = cz * chunkPhysicalSize;
        
        float totalWeight = 0.0f;
        for (SpawningFactor factor : factors) {
            totalWeight += factor.evaluate(cx, cz, worldX, worldZ);
        }
        
        // 1. Gather environmental fitness via an average (Result is between 0.0f and 1.0f)
        float finalWeight = totalWeight / factors.length;
        
        // 2. Apply the power rule to the FRACTION (e.g., 0.5^2 = 0.25)
        float shapedWeight = (float) Math.pow(finalWeight, powerCurve);
        
        // 3. Scale by base multiplier and apply the absolute performance limit
        int calculatedCount = Math.round(shapedWeight * baseMultiplier);
        return Math.min(maxLimit, calculatedCount);
    }

    /**
     * Generates a chunk heatmap image utilizing dynamic min/max scaling constraints.
     * Can evaluate layers using either a blended linear average or a strict multiplicative pass.
     */
    public BufferedImage generateHeatmap(float totalRegionWidth, float physicalChunkSize, boolean useMultiplicative, float powerCurve, SpawningFactor... factors) {
        int minChunkX = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);
        int maxChunkX = (int) Math.ceil((totalRegionWidth / 2.0f) / physicalChunkSize);
        int minChunkZ = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);
        int maxChunkZ = (int) Math.ceil((totalRegionWidth / 2.0f) / physicalChunkSize);

        int widthCh = maxChunkX - minChunkX + 1;
        int heightCh = maxChunkZ - minChunkZ + 1;

        float[][] rawGrid = new float[widthCh][heightCh];
        float maxFound = 0.0001f; 
        float minFound = 1.0f;

        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                float worldX = cx * physicalChunkSize;
                float worldZ = cz * physicalChunkSize;
                float combinedValue = useMultiplicative ? 1.0f : 0.0f;
                
                if (factors.length > 0) {
                    if (useMultiplicative) {
                        for (SpawningFactor factor : factors) {
                            combinedValue *= factor.evaluate(cx, cz, worldX, worldZ);
                        }
                        // Apply power rule to the final combined fractional weight
                        combinedValue = (float) Math.pow(combinedValue, powerCurve);
                    } else {
                        for (SpawningFactor factor : factors) {
                            combinedValue += factor.evaluate(cx, cz, worldX, worldZ);
                        }
                        combinedValue /= factors.length;
                        combinedValue = (float) Math.pow(combinedValue, powerCurve);
                    }
                } else {
                    combinedValue = 0.0f;
                }

                int arrayX = cx - minChunkX;
                int arrayY = cz - minChunkZ;
                rawGrid[arrayX][arrayY] = combinedValue;

                if (combinedValue > maxFound) maxFound = combinedValue;
                if (combinedValue < minFound) minFound = combinedValue;
            }
        }

        // Second Pass: Draw normalized color map buffers
        BufferedImage mapImage = new BufferedImage(widthCh, heightCh, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < heightCh; y++) {
            for (int x = 0; x < widthCh; x++) {
                float rawVal = rawGrid[x][y];
                
                // Dynamic Normalization calculation
                float normalizedValue = (rawVal - minFound) / (maxFound - minFound);
                if (maxFound - minFound == 0) normalizedValue = 0.0f;
                normalizedValue = Math.max(0.0f, Math.min(1.0f, normalizedValue));

                Color finalColor;

                if (rawVal <= 0.001f) {
                    finalColor = Color.BLACK; // Dead zone
                } else if (normalizedValue < 0.33f) {
                    float t = normalizedValue / 0.33f;
                    finalColor = lerpColor(new Color(255, 0, 0), new Color(255, 127, 0), t);
                } else if (normalizedValue < 0.66f) {
                    float t = (normalizedValue - 0.33f) / 0.33f;
                    finalColor = lerpColor(new Color(255, 127, 0), new Color(255, 255, 0), t);
                } else {
                    float t = (normalizedValue - 0.66f) / 0.34f;
                    finalColor = lerpColor(new Color(255, 255, 0), new Color(0, 200, 0), t);
                }

                mapImage.setRGB(x, y, finalColor.getRGB());
            }
        }
        
        String evaluationType = useMultiplicative ? "Multiplicative" : "Blended";
        System.out.printf("[SpawningManager] Heatmap Bounds (%s) -> Min Weight: %.4f | Max Weight: %.4f%n", 
                        evaluationType, minFound, maxFound);
                        
        return mapImage;
    }

    /**
     * Linearly interpolates between two colors based on a value 't' between 0.0 and 1.0.
     */
    private Color lerpColor(Color c1, Color c2, float t) {
        int r = (int) (c1.getRed() + (c2.getRed() - c1.getRed()) * t);
        int g = (int) (c1.getGreen() + (c2.getGreen() - c1.getGreen()) * t);
        int b = (int) (c1.getBlue() + (c2.getBlue() - c1.getBlue()) * t);
        return new Color(r, g, b);
    }

    // ==========================================
    //    INITIALIZATION DATA PRE-CALCULATOR
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
                float worldX = cx * physicalChunkSize;
                float worldZ = cz * physicalChunkSize;
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