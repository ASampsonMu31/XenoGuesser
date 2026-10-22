import com.jogamp.opengl.*;


/**
 * The snow lying on the ground: where it's cold and wet enough for it, worked out over the
 * whole sphere in the background (smoothed so it thins out gradually), and handed to the
 * shaders as a cube map.
 */
final class SnowCover extends GamePart {

    SnowCover(XenoGuesser_GLEventListener game) {
        super(game);
    }

    /**
     * What a thermometer reads at (x, z), in degrees Celsius: the place's warmth as the
     * temperature overlay shows it (warmer towards the equator and inland in the tropics, colder
     * up high, varying from region to region), warmer in that hemisphere's summer and colder in
     * its winter (the more so the further from the equator), and warmest in the mid afternoon,
     * coolest round dawn, by this round's sun.
     */
    // The climate for the snow over the whole world (see snowClimate): a cube map (SphereRaster)
    // this many texels a face's side, its texture unit, and how many degrees below freezing it
    // takes for snow to lie thickly
    private static final int SNOW_COVER_SIDE = 256, SNOW_UNIT = Shader.SNOW_UNIT;

    private static final float SNOW_LIES_THICK = 5f;

    private java.util.concurrent.Future<byte[]> snowCoverJob;

    private int snowCoverTexture;

    /**
     * The climate in a direction as the snow needs it, each 0 to 1 for the texture: the
     * temperature as it would be at sea level (from -60 to 40 Celsius; the shader takes off for
     * height), and how much rain or snow falls.
     */
    private float[] snowClimate(GeodesicChunkGrid.Direction d) {
        float warmth = game.regionalManager.temperatureMap.evaluate(d) + game.regionalManager.altitudeChill(d);
        float celsius = -30f + 70f * warmth;
        return new float[] { Math.max(0f, Math.min(1f, (celsius + 60f) / 100f)), game.regionalManager.rainfallMap.evaluate(d) };
    }

    // How many times the snow's climate is averaged with its neighbours' (each texel a couple of hundred units)
    private static final int SNOW_SMOOTHING = 9;

    /** Averages the snow's climate (both channels, two floats a texel) with each texel's four neighbours, SNOW_SMOOTHING times over. */
    private static float[] smoothSnowClimate(SphereRaster raster, float[] climate) {
        int size = raster.size();
        float[] a = climate, b = new float[climate.length];
        for (int pass = 0; pass < SNOW_SMOOTHING; pass++) {
            float[] from = a, to = b;
            java.util.stream.IntStream.range(0, size).parallel().forEach(t -> {
                for (int c = 0; c < 2; c++) {
                    float sum = from[t * 2 + c];
                    for (int k = 0; k < 4; k++) sum += from[raster.neighbour(t, k) * 2 + c];
                    to[t * 2 + c] = sum / 5f;
                }
            });
            a = to;
            b = from;
        }
        return a;
    }

    /** The colour of snow: mostly white, with something of the planet's oceans in it. */
    private float[] snowColour() {
        float[] ocean = game.worldArt.palette().seaShallowTint();
        return new float[] { 0.45f * ocean[0] + 0.55f, 0.45f * ocean[1] + 0.55f, 0.45f * ocean[2] + 0.55f };
    }

    /** Works out the lying snow over the world in the background, then hands it to every shader. */
    void bindSnowCover(GL3 gl) {
        if (snowCoverJob == null) {
            snowCoverJob = java.util.concurrent.ForkJoinPool.commonPool().submit(() -> {
                SphereRaster raster = new SphereRaster(SNOW_COVER_SIDE);
                float[] climate = new float[raster.size() * 2];
                java.util.stream.IntStream.range(0, raster.size()).parallel().forEach(t -> {
                    float[] here = snowClimate(raster.direction(t));
                    climate[t * 2] = here[0];
                    climate[t * 2 + 1] = here[1];
                });
                float[] smoothed = smoothSnowClimate(raster, climate);
                byte[] cover = new byte[smoothed.length];
                for (int k = 0; k < cover.length; k++) cover[k] = (byte) Math.round(Math.max(0f, Math.min(1f, smoothed[k])) * 255f);
                return cover;
            });
        }
        if (snowCoverTexture == 0) {
            if (!snowCoverJob.isDone()) return;
            try {
                snowCoverTexture = game.uploadCubeMap(SNOW_COVER_SIDE, 2, snowCoverJob.get());
            } catch (Exception e) {
                return;
            }
        }
        gl.glActiveTexture(GL3.GL_TEXTURE0 + SNOW_UNIT);
        gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, snowCoverTexture);
        gl.glActiveTexture(GL3.GL_TEXTURE0);
        // (every frame, so shaders made since pick it up too)
        // (today's temperature here against the usual, so fresh snow lies all round wherever it's snowing)
        SpherePosition here = game.camera.getSpherePosition();
        float usual = snowClimate(SphereMath.direction(here))[0] * 100f - 60f
                - Math.max(0f, game.camera.getAltitude() - game.seaLevelHeight) * 70f * RegionalGenerationManager.ALTITUDE_CHILL;
        Shader.setSnowCover(gl, SNOW_UNIT, game.sphereWeather.freshSnow(), game.temperatureAt(here) - usual,
                new float[] { game.freezingPoint, SNOW_LIES_THICK, 70f * RegionalGenerationManager.ALTITUDE_CHILL, game.seaLevelHeight }, snowColour(),
                (float) Planet.radius());
    }
}
