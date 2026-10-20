import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * The planet as the pod sees it coming in (see PodSpaceView): land and sea only, no cloud and no
 * relief, in the plain map's colours (MapColours), its coasts where the game's map has them. An
 * equirectangular picture (longitude across, latitude down from the north pole), made once the
 * rivers are cut, on its own low-priority thread.
 */
public final class PlanetGlobeTexture {

    static final int WIDTH = 1024, HEIGHT = 512;

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
}
