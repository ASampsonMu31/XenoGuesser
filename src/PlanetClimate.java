/**
 * How rainy and cloudy a planet is on the whole, drawn from its seed: one world mostly desert
 * under clear skies, another wet nearly everywhere with the sky seldom clear. The rainfall map
 * (RegionalGenerationManager.rainfallAt), and so the rivers, the grass, the dry lands' towns and
 * the snow, the sky's clouds and rain (SphereWeather) and the clouds seen from space
 * (PlanetGlobeTexture) all follow it, so a wet world looks and plays wet everywhere.
 *
 * @param wetness       how much more (or less) rain falls everywhere than on a middling world,
 *                      about 0.55 to 1.3
 * @param rainyShare    how much more of the land lies in the rainy regions: added to the regional
 *                      pattern, so above 0 the wet regions spread and below it the deserts do
 * @param rainContrast  how far the rainy regions stand out from the dry: the regional pattern's
 *                      strength, about 0.75 to 1.35
 * @param cloudiness    how much more of the sky is clouded than the rain alone gives, about
 *                      -0.17 to 0.1 of the whole sky
 * @param moistureReach how far inland the sea's moisture carries, as a share of a middling
 *                      world's (the moisture map the rain and the plants follow)
 */
record PlanetClimate(float wetness, float rainyShare, float rainContrast, float cloudiness, float moistureReach) {

    /**
     * This planet's rain and cloud. One draw sets how wet the world is on the whole (the middle
     * of two, so most worlds are near the middle and a few are deserts or drenched), and every
     * trait leans the same way with it, a little varied each on its own: a wet world's rainy
     * regions are wider and wetter and its sky cloudier, never one without the others.
     */
    static PlanetClimate of(long worldSeed) {
        java.util.Random r = new java.util.Random(worldSeed * 104_729L + 2_718L);
        float wet = r.nextFloat() + r.nextFloat() - 1f;
        // (gentler on the wet side: a middling world is already rainy over half its land, and the
        // rain tops out, so as strong a step wetter would leave a world under cloud and rain
        // nearly everywhere)
        float lean = wet < 0f ? wet : 0.45f * wet;
        float wetness = (float) Math.pow(1.8, lean);
        float rainyShare = 0.45f * lean + 0.1f * (r.nextFloat() * 2f - 1f);
        float rainContrast = 0.75f + 0.6f * r.nextFloat();
        float cloudiness = 0.12f * lean + 0.05f * (r.nextFloat() * 2f - 1f);
        float moistureReach = (float) Math.pow(1.5, lean);
        return new PlanetClimate(wetness, rainyShare, rainContrast, cloudiness, moistureReach);
    }

    /** A line for the log: how this planet's rain and cloud were drawn. */
    String describe() {
        return String.format("wetness %.2f, rainy share %+.2f, contrast %.2f, cloudiness %+.2f, moisture reach %.2f",
                wetness, rainyShare, rainContrast, cloudiness, moistureReach);
    }
}
