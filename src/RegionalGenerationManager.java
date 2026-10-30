import java.awt.image.BufferedImage;

/**
 * The world's climate and the regional factors built on it (temperature, rainfall, distance to
 * the sea, noise maps), each sampled in a direction from the planet's centre (D7): the same
 * rules at every latitude, nothing pinched at the poles. The map's heatmaps sample the same
 * factors at each of their pixels' directions (generateHeatmap).
 */
public class RegionalGenerationManager {

    private final float seaLevelHeight;
    private final long worldSeed;
    private int assignedNoiseTracks = 0;

    // The distance to the sea and the ground's height, over the whole sphere (see
    // precalculateWaterDistanceField): a texel about 90 units across at most
    private static final int FIELD_SIDE = 512;
    private final SphereRaster field = new SphereRaster(FIELD_SIDE);
    // World units from each texel to the nearest sea (0 on the sea), and its ground above the sea
    private float[] waterDistance;
    private float[] altitude;

    // Direct structural object handles replacing old functional callback lambdas
    public final RegionalFactor temperatureMap;
    public final RegionalFactor coldMap;
    // How much rain falls, 0 driest to 1 wettest (see rainfallAt)
    public final RegionalFactor rainfallMap;
    private final PerlinNoise rainfallNoise;

    public RegionalGenerationManager(long seed, float seaLevelHeight) {
        this.seaLevelHeight = seaLevelHeight;
        this.worldSeed = seed;

        // How warm it is, 0 coldest to 1 hottest (see temperatureAt)
        this.temperatureNoise = new PerlinNoise(seed * 977L + 31L);
        // This planet's own climate (M47): warmer or colder overall, falling off towards the
        // poles more gently or more sharply, its poles milder or harsher, one hemisphere a
        // little warmer than the other, and more or less varied from region to region. The
        // overall warmth and the equator-to-pole contrast each take the middle of two draws,
        // so most worlds are temperate but a few are frozen nearly to the tropics (an equator
        // under 10 Celsius, snow over most of the land) or baking (40 at the equator, the
        // poles barely freezing)
        java.util.Random climate = new java.util.Random(seed * 7919L + 4242L);
        this.warmthShift = -0.45f + 0.55f * (climate.nextFloat() + climate.nextFloat()) * 0.5f;
        this.falloffPower = 1.1f + climate.nextFloat() * 1.2f;
        this.polarMildness = 0.1f + climate.nextFloat() * 0.2f;
        this.hemisphereShift = (climate.nextFloat() * 2f - 1f) * 0.07f;
        this.regionalVariety = 0.7f + climate.nextFloat() * 0.7f;
        this.contrast = 0.6f + 0.75f * (climate.nextFloat() + climate.nextFloat()) * 0.5f;
        this.temperatureMap = new RegionalFactor(1.0f, this::temperatureAt);
        this.rainfallNoise = new PerlinNoise(seed * 613L + 97L);
        this.rainfallMap = new RegionalFactor(1.0f, this::rainfallAt);

        this.coldMap = new RegionalFactor(1.0f, d -> 1.0f - this.temperatureMap.evaluate(d));
    }

    // ==========================================
    //        UNIVERSAL FACTORY DESIGNERS
    // ==========================================

    private final PerlinNoise temperatureNoise;

    // This planet's climate (drawn from its seed: see the constructor). How much warmer (on the
    // 0 to 1 scale, 70 Celsius to the whole) it is overall, from about 30 Celsius colder than a
    // baking world to a little hotter; how sharply it cools away from the equator (the power of
    // the cosine of the latitude); how much warmer the poles are kept than that fall-off alone
    // gives; how much warmer the north is than the south (the south as much colder); how much
    // the regions vary; and how much of the usual drop from the equator to the poles it has
    // (0.6, about 30 Celsius, to 1.35, the poles bitter however warm the tropics)
    private final float warmthShift, falloffPower, polarMildness, hemisphereShift, regionalVariety, contrast;

    // How far inland (world units) the climate is fully continental (see temperatureAt)
    private static final float FULLY_INLAND = 8000f;

    /** The place on the datum in a direction, {x, y, z} in world units, for sampling noise there. */
    private static float[] surface(GeodesicChunkGrid.Direction d) {
        double r = Planet.radius();
        return new float[] { (float) (d.x() * r), (float) (d.y() * r), (float) (d.z() * r) };
    }

    private static double latitude(GeodesicChunkGrid.Direction d) {
        return Math.asin(Math.max(-1.0, Math.min(1.0, d.y())));
    }

    /**
     * How warm a place is, 0 coldest to 1 hottest: warmest at the equator and coldest at
     * the poles; inland hotter than the coast in the tropics and colder towards the poles (the
     * sea evens temperatures out); colder the higher the ground; and varying from region to
     * region besides.
     */
    private float temperatureAt(GeodesicChunkGrid.Direction d) {
        float warmth = (float) Math.cos(latitude(d));
        float temp = 0.06f + 0.94f * (float) Math.pow(Math.max(0.0, warmth), falloffPower);
        // (the far north and south kept milder than that alone, so the frozen polar regions
        // aren't vast: the poles a few tenths of the scale warmer, the equator no different)
        temp += polarMildness * (1f - warmth);
        // (this world's own contrast: the equator as warm, the drop to the poles stretched or squeezed)
        temp = 1f - contrast * (1f - temp);
        temp += warmthShift + hemisphereShift * (float) d.y();
        if (waterDistance != null) {
            float distance = field.sample(waterDistance, d);
            if (distance > 0f) {
                // The further inland, the more extreme: hotter where it's warm, colder where it's cool
                float inland = Math.min(1f, distance / FULLY_INLAND);
                temp += 0.14f * inland * (warmth - 0.55f) * 2f;
            }
            // Over half the scale colder for every thousand units up
            temp -= altitudeChill(d);
        }
        float[] surface = surface(d);
        temp += regionalVariety * (0.09f * temperatureNoise.onSphere(surface, 1f / 9000f, 0f, 0f) + 0.04f * temperatureNoise.onSphere(surface, 1f / 2500f, 7.3f, 1.9f));
        return Math.max(0.0f, Math.min(1.0f, temp));
    }

    // The moisture map (wettest at the water's edge, drying out inland), which the rainfall follows
    private RegionalFactor moistureMap;

    /** Sets the moisture map the rainfall is worked out from (the map's Moisture overlay). */
    public void setMoistureMap(RegionalFactor moisture) {
        this.moistureMap = moisture;
    }

    /**
     * How much rain falls at a place over a year on average, 0 driest to 1 wettest (the
     * weather on any one day is drawn from this: see SphereWeather). Mostly it's:
     * <ul>
     *   <li>moisture: rain comes from water evaporated off the sea, so it falls most where the
     *       moisture map is high and is dry far inland;</li>
     *   <li>and broad regional patterns (noise at the scale of countries), as the prevailing
     *       winds and mountains make some regions wet and others dry at any latitude, like
     *       rainy Britain or dry Sudan;</li>
     *   <li>with a little temperature: warm air can hold more water, but it's only a nudge,
     *       and the coldest places are drier.</li>
     * </ul>
     */
    private float rainfallAt(GeodesicChunkGrid.Direction d) {
        float moisture = moistureMap != null ? moistureMap.evaluate(d) : 0.5f;
        float warmth = temperatureMap.evaluate(d);
        float supply = 0.1f + 0.9f * moisture;
        float capacity = 0.7f + 0.3f * (float) Math.sqrt(warmth);
        float[] surface = surface(d);
        float regional = 1f + 1.6f * rainfallNoise.onSphere(surface, 1f / 22000f, 0f, 0f) + 0.2f * rainfallNoise.onSphere(surface, 1f / 5000f, 5.1f, 2.7f);
        // The polar regions are wetter: storms track along the polar front, and the snow piles up
        double latitude = Math.abs(latitude(d));
        float polar = (float) Math.max(0.0, Math.min(1.0, (latitude - Math.toRadians(50)) / Math.toRadians(25)));
        regional += POLAR_WETNESS * polar * polar * (3f - 2f * polar);
        float rain = RAINFALL_SCALE * (float) Math.pow(supply, 1.3) * capacity * Math.max(0f, regional);
        return (float) Math.pow(Math.max(0f, Math.min(1f, rain)), 0.8);
    }

    // Brings the rainfall to span the scale
    private static final float RAINFALL_SCALE = 0.62f;
    // How much wetter it gets towards the poles (from 50 degrees of latitude to 75)
    private static final float POLAR_WETNESS = 0.8f;

    // The rainfall overlay's colours, driest to wettest: sand, pale green, blue, deep indigo
    public static final float[][] RAINFALL_SPECTRUM = {
        { 0.86f, 0.74f, 0.50f }, { 0.62f, 0.80f, 0.55f }, { 0.25f, 0.55f, 0.90f }, { 0.12f, 0.12f, 0.55f }
    };

    /** How much colder (on the temperature map's 0 to 1 scale) a place is for the height of the ground round it (see temperatureAt). */
    public float altitudeChill(GeodesicChunkGrid.Direction d) {
        if (altitude == null) return 0f;
        return Math.max(0f, field.sample(altitude, d)) * ALTITUDE_CHILL;
    }

    // How much colder it gets for each unit of height, on the temperature map's scale (about
    // 40 Celsius colder a thousand units up, so the high peaks are frozen even near the equator)
    public static final float ALTITUDE_CHILL = 0.00057f;

    public RegionalFactor createTemperaturePreference(float optimalTemp, float standardDeviation) {
        return createTemperaturePreference(optimalTemp, standardDeviation, 1.0f);
    }

    /**
     * A preference for a temperature, its tolerance of places hotter than it likes stretched by
     * hotStretch (1 the same either side; more for a species that copes with the heat).
     */
    public RegionalFactor createTemperaturePreference(float optimalTemp, float standardDeviation, float hotStretch) {
        return createTemperaturePreference(optimalTemp, standardDeviation, hotStretch, 1.0f);
    }

    /**
     * A preference for a temperature, its tolerance of hotter places stretched by hotStretch and
     * of colder ones by coldStretch (more for a species that copes with the cold).
     */
    public RegionalFactor createTemperaturePreference(float optimalTemp, float standardDeviation, float hotStretch, float coldStretch) {
        return new RegionalFactor(1.0f, d -> {
            float currentTemp = this.temperatureMap.evaluate(d);
            float diff = currentTemp - optimalTemp;
            float spread = standardDeviation * (diff > 0f ? hotStretch : coldStretch);
            double exponent = -(diff * diff) / (2.0 * spread * spread);
            return (float) Math.exp(exponent);
        });
    }

    /** A preference for being a certain distance from the sea, both in world units. */
    public RegionalFactor createWaterPreference(float optimalDistance, float standardDeviation) {
        return new RegionalFactor(1.0f, d -> {
            float diff = moistureDistance(d) - optimalDistance;
            double exponent = -(diff * diff) / (2.0 * standardDeviation * standardDeviation);
            return (float) Math.exp(exponent);
        });
    }

    // What distanceToWater gives before the field is worked out
    private static final float UNKNOWN_DISTANCE = 99_900f;

    // Rivers' water as moisture (see addRivers): by texel of the field, how much nearer the water a
    // place by a river is as moist as, as a share of its distance from the sea (0 far from any)
    private float[] riverWetting;
    // Right by a stream a place is as moist as if it were this share of the way nearer the sea, by
    // the biggest rivers this (going smoothly between with the river's half width over RIVER_SIZE),
    // wearing off over this distance from a stream and this much further for each unit of its
    // half width; looked at no further out than where it's this little
    private static final float STREAM_WETTING = 0.35f, RIVER_WETTING = 0.85f, RIVER_SIZE = 12f;
    private static final float STREAM_MOISTURE_REACH = 150f, MOISTURE_REACH_PER_WIDTH = 25f, LEAST_WETTING = 0.02f;

    /**
     * Lets the rivers moisten the land round them: by a river a place is as moist as if it were
     * nearer the sea (see moistureDistance), much nearer by a great river, a little by a stream,
     * wearing off with distance from it, the further the bigger the river.
     */
    public void addRivers(Rivers rivers) {
        int size = field.size();
        float[] wetting = new float[size], keep = new float[size];
        java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>((a, b) -> Long.compare(b[0], a[0]));
        for (Rivers.Reach reach : rivers.reaches()) {
            for (int k = 0; k < reach.size(); k += 2) {
                if (reach.inLake[k]) continue;
                double[] p = reach.points[k];
                int t = field.index(p[0], p[1], p[2]);
                float half = reach.halfWidth[k];
                float size01 = half / (half + RIVER_SIZE);
                float start = STREAM_WETTING + (RIVER_WETTING - STREAM_WETTING) * size01;
                if (start > wetting[t]) {
                    wetting[t] = start;
                    // (how much of it is kept over each texel out from the river)
                    keep[t] = (float) Math.exp(-(2.0 / FIELD_SIDE * Planet.radius()) / (STREAM_MOISTURE_REACH + MOISTURE_REACH_PER_WIDTH * half));
                    open.add(new long[] { Float.floatToIntBits(start), t });
                }
            }
        }
        // Out from them, wearing off, the wettest first
        while (!open.isEmpty()) {
            long[] e = open.poll();
            int t = (int) e[1];
            float here = Float.intBitsToFloat((int) e[0]);
            if (here < wetting[t]) continue;
            float next = here * keep[t];
            if (next < LEAST_WETTING) continue;
            for (int k = 0; k < 4; k++) {
                int n = field.neighbour(t, k);
                if (next > wetting[n]) {
                    wetting[n] = next;
                    keep[n] = keep[t];
                    open.add(new long[] { Float.floatToIntBits(next), n });
                }
            }
        }
        riverWetting = wetting;
    }

    /** How far from the sea a place is as moist as: its own distance, or nearer if it's by a river (see addRivers). */
    public float moistureDistance(GeodesicChunkGrid.Direction d) {
        float sea = distanceToWater(d);
        return riverWetting == null ? sea : sea * (1f - field.sample(riverWetting, d));
    }

    /** World units along the ground from a place to the nearest sea (0 on the sea), blended between texels. */
    public float distanceToWater(GeodesicChunkGrid.Direction d) {
        return waterDistance == null ? UNKNOWN_DISTANCE : field.sample(waterDistance, d);
    }

    public float distanceToWater(SpherePosition place) {
        return distanceToWater(SphereMath.direction(place));
    }

    /**
     * A noise map of its own, seeded by salt rather than by how many maps came before (so
     * making one leaves every other map as it was): for re-drawing a species' range.
     */
    public RegionalFactor createNoiseMap(float scale, long salt) {
        PerlinNoise noise = new PerlinNoise(worldSeed * 0x9E3779B97F4A7C15L + salt * 1_000_003L + 77_777L);
        return new RegionalFactor(1.0f, d -> (noise.onSphere(surface(d), scale, 0f, 0f) + 1.0f) / 2.0f);
    }

    public RegionalFactor createNoiseMap(float scale) {
        int uniqueOffset = 33333 + (assignedNoiseTracks * 11111);
        assignedNoiseTracks++;

        PerlinNoise noise = new PerlinNoise(worldSeed + uniqueOffset);
        return new RegionalFactor(1.0f, d -> (noise.onSphere(surface(d), scale, 0f, 0f) + 1.0f) / 2.0f);
    }

    // ==========================================
    //       REFACTORED VISUALIZATION ENGINE
    // ==========================================

    /** The colours of a temperature map, coldest to hottest: deep blue through pale to red. */
    public static final float[][] TEMPERATURE_SPECTRUM = {
        { 0.10f, 0.20f, 0.75f }, { 0.45f, 0.75f, 0.95f }, { 1.00f, 0.80f, 0.35f }, { 0.85f, 0.10f, 0.05f }
    };

    public BufferedImage generateHeatmap(float totalRegionWidth, float pixelSize, RegionalFactor factorToVisualize, String label) {
        return generateHeatmap(totalRegionWidth, pixelSize, factorToVisualize, label, null);
    }

    /**
     * A heatmap of a factor over the whole map, a pixel every pixelSize map units (square, as
     * the map's images are), each the factor in that pixel's middle's direction; in the given
     * four colours from lowest to highest (null for the usual blue to red).
     */
    public BufferedImage generateHeatmap(float totalRegionWidth, float pixelSize, RegionalFactor factorToVisualize, String label,
                                         float[][] colours) {
        int min = (int) Math.floor((-totalRegionWidth / 2.0f) / pixelSize);
        int max = (int) Math.ceil((totalRegionWidth / 2.0f) / pixelSize);
        int size = max - min + 1;

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        float[][] spectrum = colours != null ? colours : new float[][] {
            {0.10f, 0.20f, 0.85f}, // Blue
            {0.40f, 0.80f, 0.95f}, // Light blue
            {1.00f, 0.75f, 0.20f}, // Amber
            {0.85f, 0.00f, 0.00f}  // Red
        };

        float[] lows = new float[size], highs = new float[size];
        java.util.stream.IntStream.range(0, size).parallel().forEach(z -> {
            float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
            double mapZ = (min + z + 0.5) * pixelSize;
            for (int x = 0; x < size; x++) {
                float factor = factorToVisualize.evaluate(MapProjection.direction((min + x + 0.5) * pixelSize, mapZ));
                low = Math.min(low, factor);
                high = Math.max(high, factor);
                image.setRGB(x, z, spectrumColour(spectrum, factor));
            }
            lows[z] = low;
            highs[z] = high;
        });

        float minObservedFactor = Float.MAX_VALUE, maxObservedFactor = -Float.MAX_VALUE;
        for (int z = 0; z < size; z++) {
            minObservedFactor = Math.min(minObservedFactor, lows[z]);
            maxObservedFactor = Math.max(maxObservedFactor, highs[z]);
        }
        // Print the statistical range straight into your development console terminal
        System.out.printf("[%s METRICS] Range observed: Min = %.4f | Max = %.4f%n", label, minObservedFactor, maxObservedFactor);

        return image;
    }

    /** A value 0 to 1 in a four-colour spectrum, as opaque ARGB. */
    private static int spectrumColour(float[][] spectrum, float factor) {
        int from = factor <= 0.333f ? 0 : factor <= 0.666f ? 1 : 2;
        float t = from == 0 ? factor / 0.333f : from == 1 ? (factor - 0.333f) / 0.333f : (factor - 0.666f) / 0.334f;
        int rgb = 0xFF;
        for (int c = 0; c < 3; c++) {
            float v = spectrum[from][c] + t * (spectrum[from + 1][c] - spectrum[from][c]);
            rgb = (rgb << 8) | Math.max(0, Math.min(255, Math.round(v * 255.0f)));
        }
        return rgb;
    }

    // ==========================================
    //        DISTANCE TO THE SEA
    // ==========================================

    /**
     * The ground's height at every texel of the field over the whole sphere, and the true
     * distance along the ground from each to the nearest sea (SphereRaster.distanceTo), so
     * moisture-driven vegetation and the inland climate follow the same rules at every latitude.
     */
    public void precalculateWaterDistanceField(PerlinNoise worldNoise) {
        int size = field.size();
        float[] heights = new float[size];
        boolean[] sea = new boolean[size];
        java.util.stream.IntStream.range(0, size).parallel().forEach(t -> {
            float h = TerrainMesh.getLayeredHeight(field.direction(t), worldNoise);
            heights[t] = h - seaLevelHeight;
            sea[t] = h <= seaLevelHeight;
        });
        float[] angle = field.distanceTo(sea);
        float radius = (float) Planet.radius();
        float[] distance = new float[size];
        for (int t = 0; t < size; t++) distance[t] = angle[t] == Float.MAX_VALUE ? UNKNOWN_DISTANCE : angle[t] * radius;
        altitude = heights;
        waterDistance = distance;
        buildWaveField(sea);
    }

    // ==========================================
    //        THE SEA'S WAVES
    // ==========================================

    // The sizes below were tuned on the old chart's chunks, this many world units square
    private static final float WAVE_CHUNK = 100f;
    // Bodies of water smaller than this (in chunks) are calm; waves reach full size at the upper bound
    private static final double CALM_BODY_CHUNKS = 400.0;
    private static final double FULL_WAVE_BODY_CHUNKS = 400_000.0;
    private static final float SHORE_WAVE_FLOOR = 0.2f;
    private static final float OPEN_WATER_CHUNKS = 8.0f;
    // Passes of the four-neighbour blur over the field (about the old map's two box blurs of
    // radius two, in its chunks, as this field's texels are smaller)
    private static final int WAVE_BLUR_PASSES = 20;

    // Per texel of the field: how big the waves are (0 calm to 1 full ocean swell), and the
    // distance to the nearest shore in world units, smoothed (wave crests follow its contours)
    private float[] waveStrength, shoreDistance;

    /**
     * How big the waves are over every body of water: nothing on ponds and small lakes,
     * rising with the size of the body (its true area) to full ocean swell, and easing off
     * near the shore; and the true distance to the shore, smoothed so its contours are gentle
     * curves, so the waves always roll in towards land.
     */
    private void buildWaveField(boolean[] sea) {
        int size = field.size();
        int[] neighbours = new int[size * 4];
        java.util.stream.IntStream.range(0, size).parallel().forEach(t -> {
            for (int k = 0; k < 4; k++) neighbours[t * 4 + k] = field.neighbour(t, k);
        });
        // Label each connected body of water and count its texels
        int[] body = new int[size];
        java.util.Arrays.fill(body, -1);
        int[] queue = new int[size];
        java.util.List<Integer> bodySizes = new java.util.ArrayList<>();
        for (int start = 0; start < size; start++) {
            if (!sea[start] || body[start] >= 0) continue;
            int label = bodySizes.size();
            int head = 0, tail = 0;
            queue[tail++] = start;
            body[start] = label;
            while (head < tail) {
                int t = queue[head++];
                for (int k = 0; k < 4; k++) {
                    int q = neighbours[t * 4 + k];
                    if (sea[q] && body[q] < 0) {
                        body[q] = label;
                        queue[tail++] = q;
                    }
                }
            }
            bodySizes.add(tail);
        }
        boolean[] land = new boolean[size];
        for (int t = 0; t < size; t++) land[t] = !sea[t];
        float[] offshore = field.distanceTo(land);
        float radius = (float) Planet.radius();
        double chunksPerTexel = 4.0 * Math.PI * radius * radius / size / (WAVE_CHUNK * WAVE_CHUNK);
        double low = Math.log(CALM_BODY_CHUNKS), high = Math.log(FULL_WAVE_BODY_CHUNKS);
        float[] strength = new float[size], distance = new float[size];
        for (int t = 0; t < size; t++) {
            // (no land anywhere: as far as the planet goes)
            distance[t] = offshore[t] == Float.MAX_VALUE ? (float) Math.PI * radius : offshore[t] * radius;
            if (body[t] < 0) continue;
            float bodySize = (float) ((Math.log(bodySizes.get(body[t]) * chunksPerTexel) - low) / (high - low));
            bodySize = Math.max(0f, Math.min(1f, bodySize));
            bodySize = bodySize * bodySize * (3 - 2 * bodySize);
            float open = Math.min(1f, distance[t] / (OPEN_WATER_CHUNKS * WAVE_CHUNK));
            strength[t] = bodySize * (SHORE_WAVE_FLOOR + (1 - SHORE_WAVE_FLOOR) * open * open * (3 - 2 * open));
        }
        waveStrength = blur(strength, neighbours, WAVE_BLUR_PASSES);
        shoreDistance = blur(distance, neighbours, WAVE_BLUR_PASSES);
    }

    /** Passes of averaging each texel with its four neighbours. */
    private static float[] blur(float[] values, int[] neighbours, int passes) {
        float[] from = values.clone(), to = new float[values.length];
        for (int pass = 0; pass < passes; pass++) {
            float[] source = from, target = to;
            java.util.stream.IntStream.range(0, values.length).parallel().forEach(t -> {
                int n = t * 4;
                target[t] = (source[t] + source[neighbours[n]] + source[neighbours[n + 1]]
                        + source[neighbours[n + 2]] + source[neighbours[n + 3]]) * 0.2f;
            });
            from = target;
            to = source;
        }
        return from;
    }

    /** Texels along a side of the wave field's cube map. */
    public int waveFieldSide() {
        return field.side();
    }

    /** How far apart the wave field's texels are at most, in world units (the step for its slopes). */
    public float waveFieldTexel() {
        return (float) (2.0 / field.side() * Planet.radius());
    }

    /**
     * The wave field as interleaved pairs per texel in SphereRaster's order (as a cube map's
     * faces): wave size (0 to 1) and distance to the nearest shore in world units; null until built.
     */
    public float[] waveFieldTexels() {
        if (waveStrength == null) return null;
        float[] texels = new float[waveStrength.length * 2];
        for (int t = 0; t < waveStrength.length; t++) {
            texels[t * 2] = waveStrength[t];
            texels[t * 2 + 1] = shoreDistance[t];
        }
        return texels;
    }

    // The wave set in vs_sphere_water.txt, which this must match: wavelength, share of its
    // set's height, and whether it is swell (0) or chop (1)
    private static final float[][] WAVES = {
        { 620f, 1.00f, 0f }, { 410f, 0.60f, 0f }, { 150f, 1.00f, 1f },
        { 108f, 0.70f, 1f }, { 84f, 0.55f, 1f }, { 128f, 0.60f, 1f }
    };
    private static final float WAVE_GRAVITY = 140f, SWELL_HEIGHT = 7f, CHOP_HEIGHT = 2.2f;

    /**
     * The sea surface's altitude at a place right beside the viewer at the given time, as the
     * water shader draws it there, so a swimmer can bob on the waves.
     */
    public float waveSurfaceAt(SpherePosition place, float time, float seaLevel) {
        if (waveStrength == null) return seaLevel;
        SphereFrame frame = SphereFrame.at(SphereMath.atAltitude(place, 0.0));
        float h = waveFieldTexel() * 4f;   // (as vs_sphere_water measures the slope)
        float strength = field.sample(waveStrength, SphereMath.direction(place));
        float distance = shoreAt(frame, 0f, 0f);
        float slopeX = (shoreAt(frame, h, 0f) - shoreAt(frame, -h, 0f)) / (2 * h);
        float slopeZ = (shoreAt(frame, 0f, h) - shoreAt(frame, 0f, -h)) / (2 * h);
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
        return seaLevel + height;
    }

    /** The smoothed distance to the shore at a point (x east, z south) in a frame on the datum. */
    private float shoreAt(SphereFrame frame, float x, float z) {
        SpherePosition at = frame.toWorld(x, 0.0, z);
        return field.sample(shoreDistance, at.x(), at.y(), at.z());
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0f, Math.min(1f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}
