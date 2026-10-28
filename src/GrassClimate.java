/**
 * The grass's three regional maps: how much of it grows, how tall it stands and how lush its
 * colour is. They come from the same climate, so they go together loosely (no lush tall grass on
 * a frozen plateau), but each has its own leanings and its own broad patches, so one map is never
 * just another redrawn:
 * <ul>
 *   <li>abundance follows mildness and nearness to the sea, broken into regions of thick and
 *       thin turf;</li>
 *   <li>colour follows the rain more than the sea (the rain has regional weather of its own) and
 *       likes it cool: hot inland grass is sun-bleached however thick it is;</li>
 *   <li>height likes it warm, as savanna grasses do, and is mostly down to its own patches.</li>
 * </ul>
 * Each patch map is its own broad noise, regions tens of thousands of units across (a handful
 * round the planet), stretched to span the scale and weighed heavily enough to have as much say
 * as the climate.
 */
record GrassClimate(RegionalFactor abundance, RegionalFactor height, RegionalFactor colour) {

    // Grass dies back from this many degrees above the water's freezing point, and is gone by
    // this many below it
    private static final float FROST_START = 6f, FROST_DEATH = 6f;

    // The patch maps' sizes (world units across a blob, about) and their salts (see createNoiseMap)
    private static final float ABUNDANCE_PATCH = 25000f, HEIGHT_PATCH = 30000f, COLOUR_PATCH = 40000f;
    private static final long ABUNDANCE_SALT = 9101L, HEIGHT_SALT = 9203L, COLOUR_SALT = 9307L;

    /**
     * The grass's maps on a world, its water freezing at freezingPoint Celsius, with moisture its
     * nearness to the sea (as the rainfall is worked out from).
     */
    static GrassClimate of(RegionalGenerationManager climate, float freezingPoint, RegionalFactor moisture) {
        // From a few degrees above the water's freezing point down to several below it thins out
        // to almost nothing, and what's left is dry and dead, whatever else the grass likes
        RegionalFactor frost = new RegionalFactor(1f, d -> {
            float celsius = -30f + 70f * climate.temperatureMap.evaluate(d);
            float t = Math.max(0f, Math.min(1f, (celsius - (freezingPoint - FROST_DEATH)) / (FROST_DEATH + FROST_START)));
            return t * t * (3f - 2f * t);
        });
        RegionalFactor mild = frosted(climate.createTemperaturePreference(0.4f, 0.5f), frost);
        RegionalFactor cool = climate.createTemperaturePreference(0.3f, 0.3f);
        RegionalFactor warm = climate.createTemperaturePreference(0.7f, 0.35f);

        RegionalFactor abundance = new RegionalFactor.Builder()
            .setWeight(3f)
            .setPowerCurve(5.0f)
            .addFactor(mild, 0.3f)
            .addFactor(moisture, 0.3f)
            .addFactor(patches(climate, ABUNDANCE_PATCH, ABUNDANCE_SALT), 0.4f)
            .build();

        // (frozen grass is short dead stubble, however wet: the rain is heaviest towards the poles)
        RegionalFactor height = frosted(new RegionalFactor.Builder()
            .setWeight(1.5f)
            .setPowerCurve(3.0f)
            .addFactor(warm, 0.3f)
            .addFactor(climate.rainfallMap, 0.1f)
            .addFactor(patches(climate, HEIGHT_PATCH, HEIGHT_SALT), 0.6f)
            .build(), frost);

        RegionalFactor colour = frosted(new RegionalFactor.Builder()
            .setWeight(1.5f)
            .setPowerCurve(3.0f)
            .addFactor(climate.rainfallMap, 0.3f)
            .addFactor(moisture, 0.1f)
            .addFactor(cool, 0.2f)
            .addFactor(patches(climate, COLOUR_PATCH, COLOUR_SALT), 0.4f)
            .build(), frost);

        return new GrassClimate(abundance, height, colour);
    }

    /** A preference that the frost kills off all the same. */
    private static RegionalFactor frosted(RegionalFactor preference, RegionalFactor frost) {
        return new RegionalFactor(1f, d -> preference.evaluate(d) * frost.evaluate(d));
    }

    /**
     * Broad patches about size units across, 0 to 1 and spanning most of that (one octave of
     * noise alone sits mostly round the middle), with a finer octave to rough their edges.
     */
    private static RegionalFactor patches(RegionalGenerationManager climate, float size, long salt) {
        RegionalFactor broad = climate.createNoiseMap(1f / size, salt);
        RegionalFactor fine = climate.createNoiseMap(2.5f / size, salt + 1L);
        return new RegionalFactor(1f, d -> {
            float n = (broad.evaluate(d) - 0.5f) + 0.25f * (fine.evaluate(d) - 0.5f);
            return Math.max(0f, Math.min(1f, 0.5f + 2.2f * n));
        });
    }
}
