import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * The planet seen from space. make: land and sea only, in the plain map's colours (MapColours),
 * its coasts where the game's map has them (the trailer's globes, and a fingerprint of the world).
 * relief: as the pod sees it coming in (see PodSpaceView), the same colours with snow lying where
 * the game's snow lies, the height of the ground everywhere (to raise the globe's surface by, to
 * scale, and shade its hills and mountains), and clouds of its own: drawn from noise, never from
 * the rainfall (which the clouds would give away before its map is bought). Equirectangular
 * pictures (longitude across, latitude down from the north pole), made once the rivers are cut,
 * on their own low-priority thread.
 */
public final class PlanetGlobeTexture {

    static final int WIDTH = 1024, HEIGHT = 512;
    // The clouds' picture's size
    private static final int CLOUD_WIDTH = 1024, CLOUD_HEIGHT = 512;

    /** The globe as the pod sees it: its colours (snow and all), the ground's height above the sea everywhere (units, WIDTH by HEIGHT, 0 at sea), and its clouds (white, by alpha). */
    record Relief(BufferedImage colours, float[] heights, BufferedImage clouds) {
    }

    private PlanetGlobeTexture() {
    }

    /** Makes the picture (several cores, a second or so). */
    static BufferedImage make(PerlinNoise noise, float seaLevel, long worldSeed) {
        long started = System.currentTimeMillis();
        int[] colours = MapColours.of(worldSeed);
        int sea = colours[0], land = colours[1];
        int[] pixels = new int[WIDTH * HEIGHT];
        // (the map stops short of the poles: beyond that the ground's as it is there)
        double clip = MapProjection.CLIP_LATITUDE;
        IntStream.range(0, HEIGHT).parallel().forEach(row -> {
            double latitude = Math.max(-clip, Math.min(clip, Math.PI * 0.5 - (row + 0.5) / HEIGHT * Math.PI));
            float mapZ = (float) MapProjection.z(latitude);
            for (int col = 0; col < WIDTH; col++) {
                double longitude = (col + 0.5) / WIDTH * Math.PI * 2.0 - Math.PI;
                float mapX = (float) MapProjection.x(longitude);
                boolean isLand = TerrainMesh.getLayeredHeight(mapX, mapZ, noise) > seaLevel;
                pixels[row * WIDTH + col] = isLand ? land : sea;
            }
        });
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        // Developer aid: its picture saved beside the run's files, to compare with the map
        if (Dev.ENABLED) {
            try {
                javax.imageio.ImageIO.write(image, "png", new java.io.File(new java.io.File(RunFiles.WORLD_DIR).getParentFile(), "planet_globe.png"));
            } catch (java.io.IOException ignored) {
                // (only a developer's aid)
            }
        }
        System.out.printf("[POD] The planet's globe made in %d ms (sea #%06x, land #%06x)%n", System.currentTimeMillis() - started, sea, land);
        return image;
    }

    /**
     * The globe as the pod sees it (several cores, a few seconds): sampled on the sphere itself,
     * poles and all, the ground's height from the noise as the game has it, and snow by the
     * game's own rule (SnowCover and the ground's shader): where it's colder than the freezing
     * point at that height and enough falls, thickest a few degrees below it.
     */
    static Relief relief(PerlinNoise noise, float seaLevel, long worldSeed, RegionalGenerationManager climate) {
        long started = System.currentTimeMillis();
        int[] colours = MapColours.of(worldSeed);
        int sea = colours[0], land = colours[1];
        float freezing = XenoGuesser_GLEventListener.freezingPoint(worldSeed);
        float[] ocean = new WorldPalette(worldSeed).seaShallowTint();
        float[] snow = { 0.45f * ocean[0] + 0.55f, 0.45f * ocean[1] + 0.55f, 0.45f * ocean[2] + 0.55f };
        float chill = 70f * RegionalGenerationManager.ALTITUDE_CHILL;
        int[] pixels = new int[WIDTH * HEIGHT];
        float[] heights = new float[WIDTH * HEIGHT];
        IntStream.range(0, HEIGHT).parallel().forEach(row -> {
            double latitude = Math.PI * 0.5 - (row + 0.5) / HEIGHT * Math.PI;
            for (int col = 0; col < WIDTH; col++) {
                double longitude = (col + 0.5) / WIDTH * Math.PI * 2.0 - Math.PI;
                GeodesicChunkGrid.Direction d = SphereMath.direction(Planet.position(latitude, longitude, 0.0));
                float above = TerrainMesh.getLayeredHeight(d, noise) - seaLevel;
                int i = row * WIDTH + col;
                if (above <= 0f) {
                    pixels[i] = sea;
                    continue;
                }
                heights[i] = above;
                // (the temperature as at sea level, less the chill of this ground's own height)
                float celsius = -30f + 70f * (climate.temperatureMap.evaluate(d) + climate.altitudeChill(d)) - above * chill;
                float falls = Math.max(0f, Math.min(1f, (climate.rainfallMap.evaluate(d) - 0.05f) / 0.3f));
                float t = Math.max(0f, Math.min(1f, (freezing - celsius) / 5f));
                float lying = Math.min(0.8f, t * t * (3f - 2f * t) * falls) / 0.8f;
                pixels[i] = lying <= 0f ? land : mix(land, snow, lying);
            }
        });
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        BufferedImage clouds = clouds(worldSeed);
        // Developer aid: its pictures saved beside the run's files
        if (Dev.ENABLED) {
            try {
                java.io.File dir = new java.io.File(RunFiles.WORLD_DIR).getParentFile();
                javax.imageio.ImageIO.write(image, "png", new java.io.File(dir, "planet_relief.png"));
                javax.imageio.ImageIO.write(clouds, "png", new java.io.File(dir, "planet_clouds.png"));
            } catch (java.io.IOException ignored) {
                // (only a developer's aid)
            }
        }
        float highest = 0f;
        for (float h : heights) highest = Math.max(highest, h);
        System.out.printf("[POD] The planet's relief (its highest %.0f units up, %.2f%% of its radius), snow and clouds made in %d ms%n",
                highest, highest / Planet.radius() * 100.0, System.currentTimeMillis() - started);
        return new Relief(image, heights, clouds);
    }

    /**
     * Clouds over the globe from noise of their own: drifts and swirls (the noise bent by more
     * noise, stretched along the lines of latitude as the winds go), banks thickest round the
     * equator and the middle latitudes and thinnest over the subtropics and the poles, as on any
     * turning world with air; nothing to do with where rain falls on this one.
     */
    private static BufferedImage clouds(long worldSeed) {
        PerlinNoise noise = new PerlinNoise(worldSeed * 7_368_787L + 31L);
        float cover = 0.4f + 0.25f * new java.util.Random(worldSeed * 5_051L + 3L).nextFloat();
        int[] pixels = new int[CLOUD_WIDTH * CLOUD_HEIGHT];
        IntStream.range(0, CLOUD_HEIGHT).parallel().forEach(row -> {
            double latitude = Math.PI * 0.5 - (row + 0.5) / CLOUD_HEIGHT * Math.PI;
            double banks = 0.55 + 0.25 * Math.cos(latitude * 6.0) + 0.2 * Math.cos(latitude * 2.0);
            for (int col = 0; col < CLOUD_WIDTH; col++) {
                double longitude = (col + 0.5) / CLOUD_WIDTH * Math.PI * 2.0 - Math.PI;
                float[] p = { (float) (Math.cos(latitude) * Math.cos(longitude)), (float) Math.sin(latitude), (float) (-Math.cos(latitude) * Math.sin(longitude)) };
                float wx = noise.onSphere(p, 1.6f, 11f, 3f), wy = noise.onSphere(p, 1.6f, 23f, 7f);
                float[] q = { p[0] + 0.35f * wx, p[1] * 1.8f + 0.2f * wy, p[2] - 0.35f * wy };
                float n = 0f, amplitude = 0.55f, frequency = 2.2f;
                for (int octave = 0; octave < 6; octave++) {
                    n += amplitude * noise.onSphere(q, frequency, octave * 5.1f, octave * 2.3f);
                    amplitude *= 0.5f;
                    frequency *= 2.1f;
                }
                float density = (float) ((n * 0.5f + 0.5f) * banks) - (1f - cover) * 0.75f;
                float a = Math.max(0f, Math.min(1f, density * 3.2f));
                int alpha = Math.round(a * a * (3f - 2f * a) * 235f);
                pixels[row * CLOUD_WIDTH + col] = (alpha << 24) | 0xFFFFFF;
            }
        });
        BufferedImage image = new BufferedImage(CLOUD_WIDTH, CLOUD_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, CLOUD_WIDTH, CLOUD_HEIGHT, pixels, 0, CLOUD_WIDTH);
        return image;
    }

    private static int mix(int rgb, float[] to, float share) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        r += (to[0] - r) * share;
        g += (to[1] - g) * share;
        b += (to[2] - b) * share;
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }
}
