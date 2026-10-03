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

        // Bodies of water smaller than this (in chunks) are calm; waves reach full size at the upper bound
    private static final double CALM_BODY_CHUNKS = 400.0;
    private static final double FULL_WAVE_BODY_CHUNKS = 400_000.0;
    private static final float SHORE_WAVE_FLOOR = 0.2f;
    private static final float OPEN_WATER_CHUNKS = 8.0f;

        private float[] waveStrength;

    /** The wave map's value at a point (0 calm to 1 full ocean swell), or 0 before it is built. */
    public float waveStrengthAt(float worldX, float worldZ, float physicalChunkSize) {
        if (waveStrength == null) return 0f;
        int i = Math.max(0, Math.min(waterFieldSize - 1, (int) Math.floor(worldX / physicalChunkSize) - waterFieldMin));
        int j = Math.max(0, Math.min(waterFieldSize - 1, (int) Math.floor(worldZ / physicalChunkSize) - waterFieldMin));
        return waveStrength[j * waterFieldSize + i];
    }

    /** World coordinate of the wave map's first texel edge, and the width it covers. */
    public float waveMapOrigin(float physicalChunkSize) {
        return waterFieldMin * physicalChunkSize;
    }

    public float waveMapWidth(float physicalChunkSize) {
        return waterFieldSize * physicalChunkSize;
    }

    /**
     * How big the waves are across every body of water, one texel per chunk in the red
     * channel: nothing on ponds and small lakes, rising with the size of the body to full
     * ocean swell, and easing off near the shore. Rows run along +Z like the soil map.
     */
    public void buildWaveMap(float physicalChunkSize) {
        int n = waterFieldSize;
        int[] body = new int[n * n];
        java.util.Arrays.fill(body, -1);
        int[] queue = new int[n * n];
        java.util.List<Integer> bodySizes = new java.util.ArrayList<>();

        // Label each connected body of water and count its chunks
        for (int start = 0; start < n * n; start++) {
            if (waterField[start] != 0 || body[start] >= 0) continue;
            int label = bodySizes.size();
            int head = 0, tail = 0;
            queue[tail++] = start;
            body[start] = label;
            while (head < tail) {
                int cell = queue[head++];
                int i = cell % n, j = cell / n;
                if (i > 0) tail = joinBody(cell - 1, label, body, queue, tail);
                if (i < n - 1) tail = joinBody(cell + 1, label, body, queue, tail);
                if (j > 0) tail = joinBody(cell - n, label, body, queue, tail);
                if (j < n - 1) tail = joinBody(cell + n, label, body, queue, tail);
            }
            bodySizes.add(tail);
        }

        // Distance from the shore out into the water
        short[] offshore = new short[n * n];
        java.util.Arrays.fill(offshore, (short) -1);
        int head = 0, tail = 0;
        for (int cell = 0; cell < n * n; cell++) {
            if (waterField[cell] != 0) {
                offshore[cell] = 0;
                queue[tail++] = cell;
            }
        }
        while (head < tail) {
            int cell = queue[head++];
            int i = cell % n, j = cell / n;
            short next = (short) Math.min(Short.MAX_VALUE, offshore[cell] + 1);
            int[] neighbours = { i > 0 ? cell - 1 : -1, i < n - 1 ? cell + 1 : -1, j > 0 ? cell - n : -1, j < n - 1 ? cell + n : -1 };
            for (int other : neighbours) {
                if (other >= 0 && offshore[other] < 0) {
                    offshore[other] = next;
                    queue[tail++] = other;
                }
            }
        }

        float[] strength = new float[n * n];
        double low = Math.log(CALM_BODY_CHUNKS), high = Math.log(FULL_WAVE_BODY_CHUNKS);
        for (int cell = 0; cell < n * n; cell++) {
            if (body[cell] < 0) continue;
            float size = (float) ((Math.log(bodySizes.get(body[cell])) - low) / (high - low));
            size = Math.max(0f, Math.min(1f, size));
            size = size * size * (3 - 2 * size);
            float open = Math.min(1f, offshore[cell] / OPEN_WATER_CHUNKS);
            strength[cell] = size * (SHORE_WAVE_FLOOR + (1 - SHORE_WAVE_FLOOR) * open * open * (3 - 2 * open));
        }
                        strength = boxBlur(boxBlur(strength, n, 2), n, 2);
        waveStrength = strength;

        // True (Euclidean) distance to the nearest land, smoothed so its contours are gentle
        // curves: wave crests follow these contours, so they always roll in towards the shore
        float[] squared = new float[n * n];
        for (int cell = 0; cell < n * n; cell++) {
            squared[cell] = waterField[cell] != 0 ? 0f : 1e12f;
        }
        distanceTransform2D(squared, n);
        float[] distance = new float[n * n];
        for (int cell = 0; cell < n * n; cell++) {
            distance[cell] = (float) Math.sqrt(squared[cell]) * physicalChunkSize;
        }
        shoreDistance = boxBlur(boxBlur(distance, n, 2), n, 2);
    }

    private float[] shoreDistance;

    /**
     * The wave map as interleaved pairs per texel, row by row along +Z: wave size (0 to 1)
     * and distance to the nearest shore in world units. Built by buildWaveMap.
     */
    public float[] waveMapTexels() {
        int n = waterFieldSize;
        float[] texels = new float[n * n * 2];
        for (int cell = 0; cell < n * n; cell++) {
            texels[cell * 2] = waveStrength[cell];
            texels[cell * 2 + 1] = shoreDistance[cell];
        }
        return texels;
    }

    // The wave set in vs_water.txt, which this must match: wavelength, share of its set's
    // height, and whether it is swell (0) or chop (1)
    private static final float[][] WAVES = {
        { 620f, 1.00f, 0f }, { 410f, 0.60f, 0f }, { 150f, 1.00f, 1f },
        { 108f, 0.70f, 1f }, { 84f, 0.55f, 1f }, { 128f, 0.60f, 1f }
    };
    private static final float WAVE_GRAVITY = 140f, SWELL_HEIGHT = 7f, CHOP_HEIGHT = 2.2f;

    /**
     * Height of the sea surface at a point right beside the viewer at the given time, as the
     * water shader draws it there, so a swimmer can bob on the waves.
     */
    public float waveSurfaceAt(float worldX, float worldZ, float time, float seaLevel, float physicalChunkSize) {
        if (waveStrength == null || shoreDistance == null) return seaLevel;
        float texel = physicalChunkSize;
        float strength = bilinear(waveStrength, worldX, worldZ, texel);
        float distance = bilinear(shoreDistance, worldX, worldZ, texel);
        float slopeX = (bilinear(shoreDistance, worldX + texel, worldZ, texel) - bilinear(shoreDistance, worldX - texel, worldZ, texel)) / (2 * texel);
        float slopeZ = (bilinear(shoreDistance, worldX, worldZ + texel, texel) - bilinear(shoreDistance, worldX, worldZ - texel, texel)) / (2 * texel);
        float coherence = smoothstep(0.15f, 0.55f, (float) Math.sqrt(slopeX * slopeX + slopeZ * slopeZ));
        float swell = SWELL_HEIGHT * smoothstep(0.55f, 1.0f, strength) * coherence;
        float chop = CHOP_HEIGHT * smoothstep(0.0f, 0.45f, strength) * (0.5f + 0.5f * coherence);
        float height = 0f;
        for (int i = 0; i < WAVES.length; i++) {
            float amplitude = WAVES[i][1] * (WAVES[i][2] < 0.5f ? swell : chop);
            float k = (float) (2 * Math.PI / WAVES[i][0]);
            float omega = (float) Math.sqrt(WAVE_GRAVITY * k);
            height += amplitude * (float) Math.sin(-k * distance - omega * time + i * 1.7f);
        }
        return seaLevel - 0.05f + height;
    }

    private float bilinear(float[] grid, float worldX, float worldZ, float texel) {
        int n = waterFieldSize;
        float gx = Math.max(0f, Math.min(n - 1.001f, (worldX - waterFieldMin * texel) / texel - 0.5f));
        float gz = Math.max(0f, Math.min(n - 1.001f, (worldZ - waterFieldMin * texel) / texel - 0.5f));
        int i = (int) gx, j = (int) gz;
        float fx = gx - i, fz = gz - j;
        float top = grid[j * n + i] + (grid[j * n + i + 1] - grid[j * n + i]) * fx;
        float bottom = grid[(j + 1) * n + i] + (grid[(j + 1) * n + i + 1] - grid[(j + 1) * n + i]) * fx;
        return top + (bottom - top) * fz;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0f, Math.min(1f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    public int waveMapResolution() {
        return waterFieldSize;
    }

    /** Squared Euclidean distance transform in place (Felzenszwalb and Huttenlocher), in cells. */
    private static void distanceTransform2D(float[] grid, int n) {
        float[] line = new float[n], result = new float[n], z = new float[n + 1];
        int[] v = new int[n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) line[i] = grid[j * n + i];
            distanceTransform1D(line, result, v, z, n);
            for (int i = 0; i < n; i++) grid[j * n + i] = result[i];
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) line[j] = grid[j * n + i];
            distanceTransform1D(line, result, v, z, n);
            for (int j = 0; j < n; j++) grid[j * n + i] = result[j];
        }
    }

    private static void distanceTransform1D(float[] f, float[] d, int[] v, float[] z, int n) {
        // Lower envelope of parabolas rooted at each cell; doubles keep the far-off "no land"
        // values from swamping the arithmetic
        double[] zz = new double[n + 1];
        int k = 0;
        v[0] = 0;
        zz[0] = Double.NEGATIVE_INFINITY;
        zz[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            double s = intersection(f, v[k], q);
            while (s <= zz[k]) {
                k--;
                s = intersection(f, v[k], q);
            }
            k++;
            v[k] = q;
            zz[k] = s;
            zz[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (zz[k + 1] < q) k++;
            double dq = q - v[k];
            d[q] = (float) Math.min(1e12, dq * dq + f[v[k]]);
        }
    }

    private static double intersection(float[] f, int p, int q) {
        return (((double) f[q] + (double) q * q) - ((double) f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
    }

    private int joinBody(int cell, int label, int[] body, int[] queue, int tail) {
        if (waterField[cell] != 0 || body[cell] >= 0) return tail;
        body[cell] = label;
        queue[tail] = cell;
        return tail + 1;
    }

    private static float[] boxBlur(float[] values, int n, int radius) {
        float[] across = new float[n * n], result = new float[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float sum = 0;
                int count = 0;
                for (int d = -radius; d <= radius; d++) {
                    int x = i + d;
                    if (x >= 0 && x < n) { sum += values[j * n + x]; count++; }
                }
                across[j * n + i] = sum / count;
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float sum = 0;
                int count = 0;
                for (int d = -radius; d <= radius; d++) {
                    int y = j + d;
                    if (y >= 0 && y < n) { sum += across[y * n + i]; count++; }
                }
                result[j * n + i] = sum / count;
            }
        }
        return result;
    }

    private int visit(int cell, short distance, int[] queue, int tail) {
        if (waterField[cell] >= 0) return tail;
        waterField[cell] = distance;
        queue[tail] = cell;
        return tail + 1;
    }
}
