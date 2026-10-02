import java.util.HashMap;
import java.util.Map;
import java.util.LinkedList;
import java.util.Queue;
import java.awt.image.BufferedImage;
import java.awt.Color;

public class RegionalGenerationManager {
    
    private final float halfRegion;
    private final float seaLevelHeight;
    /** Everything that shapes the landscape is generated this far past the playable edge. */
    public static final float GENERATION_MARGIN = 5000.0f;

    private short[] waterField = new short[0];
    private int waterFieldMin;
    private int waterFieldSize;
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
            int chunksAway = getChunkDistanceToWater(cx, cz);
            float diff = (float) chunksAway - optimalDistance;
            double exponent = -(diff * diff) / (2.0 * standardDeviation * standardDeviation);
            return (float) Math.exp(exponent);
        });
    }

    /** Chunks (4-connected steps) from the chunk to the nearest water chunk; 999 if unknown. */
    public int getChunkDistanceToWater(int cx, int cz) {
        if (waterFieldSize == 0) return 999;
        // Beyond the generated margin, carry on with the value at its edge
        int i = Math.max(0, Math.min(waterFieldSize - 1, cx - waterFieldMin));
        int j = Math.max(0, Math.min(waterFieldSize - 1, cz - waterFieldMin));
        int d = waterField[j * waterFieldSize + i];
        return d < 0 ? 999 : d;
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
    //      COUNT EVALUATION ENGINE
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

    public BufferedImage generateHeatmap(float totalRegionWidth, float physicalChunkSize, RegionalFactor factorToVisualize, String label) {
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

        // Track minimum and maximum limits observed across the region
        float minObservedFactor = Float.MAX_VALUE;
        float maxObservedFactor = -Float.MAX_VALUE;

        for (int z = 0; z < height; z++) {
            int cz = minChunkZ + z;
            for (int x = 0; x < width; x++) {
                int cx = minChunkX + x;
                
                float worldX = (cx + 0.5f) * physicalChunkSize;
                float worldZ = (cz + 0.5f) * physicalChunkSize;

                float factor = factorToVisualize.evaluate(cx, cz, worldX, worldZ);

                // Update min/max metrics
                if (factor < minObservedFactor) minObservedFactor = factor;
                if (factor > maxObservedFactor) maxObservedFactor = factor;

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

                Color pixelColour = new Color(ir, ig, ib, 255);
                image.setRGB(x, z, pixelColour.getRGB());
            }
        }

        // Print the statistical range straight into your development console terminal
        System.out.printf("[%s METRICS] Range observed: Min = %.4f | Max = %.4f%n", label, minObservedFactor, maxObservedFactor);

        return image;
    }
    
    // ==========================================
    //   BREADTH-FIRST-SEARCH DISTANCE PRE-SCAN
    // ==========================================

    /**
     * Distance in chunks from every chunk to the nearest sea chunk, over the playable
     * region plus GENERATION_MARGIN on every side, so moisture-driven vegetation looks the
     * same just past the edge of the map as inside it. Stored as a flat grid: a string-keyed
     * map of the 2.25 million chunks was slow to build and slow to query every frame.
     */
    public void precalculateWaterDistanceField(float totalRegionWidth, PerlinNoise worldNoise, float physicalChunkSize){
        float half = totalRegionWidth / 2.0f + GENERATION_MARGIN;
        waterFieldMin = (int) Math.floor(-half / physicalChunkSize);
        waterFieldSize = (int) Math.ceil(half / physicalChunkSize) - waterFieldMin + 1;
        waterField = new short[waterFieldSize * waterFieldSize];
        java.util.Arrays.fill(waterField, (short) -1);

        int[] queue = new int[waterFieldSize * waterFieldSize];
        int head = 0, tail = 0;
        for (int j = 0; j < waterFieldSize; j++) {
            for (int i = 0; i < waterFieldSize; i++) {
                float worldX = (waterFieldMin + i + 0.5f) * physicalChunkSize;
                float worldZ = (waterFieldMin + j + 0.5f) * physicalChunkSize;
                if (TerrainMesh.getLayeredHeight(worldX, worldZ, worldNoise) <= seaLevelHeight) {
                    waterField[j * waterFieldSize + i] = 0;
                    queue[tail++] = j * waterFieldSize + i;
                }
            }
        }
        while (head < tail) {
            int cell = queue[head++];
            int i = cell % waterFieldSize, j = cell / waterFieldSize;
            short next = (short) Math.min(Short.MAX_VALUE, waterField[cell] + 1);
            if (i > 0) tail = visit(cell - 1, next, queue, tail);
            if (i < waterFieldSize - 1) tail = visit(cell + 1, next, queue, tail);
            if (j > 0) tail = visit(cell - waterFieldSize, next, queue, tail);
            if (j < waterFieldSize - 1) tail = visit(cell + waterFieldSize, next, queue, tail);
        }
    }

    private int visit(int cell, short distance, int[] queue, int tail) {
        if (waterField[cell] >= 0) return tail;
        waterField[cell] = distance;
        queue[tail] = cell;
        return tail + 1;
    }
}
