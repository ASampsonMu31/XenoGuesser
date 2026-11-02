import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The planet's neighbourhood, from the world's seed: how big its sun looks (its colour is
 * WorldPalette's), whether it has a moon and what that moon's like, and what the landing pod
 * meets on its way in (an asteroid belt round the star, a gas giant to swing round). Each world
 * has its own; both multiplayer PCs make the same one. The sun and the moon are drawn alike out of
 * the pod's window (PodSpaceView) and in the game's sky (SkyMoon), and the moon's lit by the sun
 * wherever it's seen, so its phase is always the one its place by the sun gives it.
 *
 * Nothing here moves the sun: where it stands (the season, the time of day, the hemisphere) is
 * Planet's alone. The moon is placed from it.
 */
final class StarSystem {

    /** How wide the sun has always looked (its disc's half angle, radians: 137.5 units at 2350). */
    static final float SUN_ANGLE = 0.0585f;

    // How much bigger or smaller than that a world's sun may look
    private static final float SMALLEST_SUN = 0.7f, LARGEST_SUN = 1.35f;
    // The share of worlds with a moon; how far off it goes round (planet radii) and how big it
    // may be (radii); and the most it's tipped from the sun's path across the sky (radians)
    private static final float MOON_CHANCE = 0.7f, MOON_NEAREST = 6f, MOON_FURTHEST = 12f,
            MOON_SMALLEST = 0.2f, MOON_LARGEST = 0.4f, MOON_TILT = 0.12f;
    // The share of ways in through a belt of asteroids round the star, and past a gas giant
    private static final float BELT_CHANCE = 0.55f, GIANT_CHANCE = 0.5f, RINGS_CHANCE = 0.45f;
    // How many tries at a round's moon being up where the pod lands (most rounds it is, as
    // anyone looking up would hope; now and then it's not)
    private static final int MOON_TRIES = 4;

    private static final Map<Long, StarSystem> MADE = new ConcurrentHashMap<>();

    final long worldSeed;
    /** How big the sun looks, as a share of SUN_ANGLE. */
    final float sunScale;
    /** The star's temperature (WorldPalette's) and its spectral class, for the pod's computer. */
    final double starTemperatureK;
    final char starClass;

    /** Whether there's a moon; how wide it looks from the ground (half angle, radians); how far off it is and how big (planet radii). */
    final boolean hasMoon;
    final float moonAngle, moonDistance, moonRadius;
    /** The moon's rock, its colour, and how tipped its path is from the sun's. */
    final float[] moonColour;
    final float moonTilt, moonNode;
    /** Where the moon is from the planet as the pod comes in (a unit direction in the frame the pod flies in, the planet ahead). */
    final float[] moonSide;

    /** Whether the pod comes through an asteroid belt, and past a gas giant. */
    final boolean belt, giant;
    /** The giant: its bands' hue, whether it has rings, which side the pod passes it on (1 left, -1 right), how its pole's tipped. */
    final float giantHue;
    final boolean giantRings;
    final float giantSide, giantTilt;
    /** The asteroids that strike the door's glass in the belt: when (share of the way through), where (u, v) and whether each cracks it. */
    final float[][] strikes;

    private StarSystem(long worldSeed) {
        this.worldSeed = worldSeed;
        // (streams of their own: nothing else the seed makes moves)
        Random rng = WorldPalette.rng(worldSeed, 0x57A2_5157L);
        sunScale = SMALLEST_SUN + (LARGEST_SUN - SMALLEST_SUN) * rng.nextFloat();
        starTemperatureK = new WorldPalette(worldSeed).starTemperatureK;
        starClass = starTemperatureK < 3700 ? 'M' : starTemperatureK < 5200 ? 'K' : starTemperatureK < 6000 ? 'G'
                : starTemperatureK < 7500 ? 'F' : starTemperatureK < 10000 ? 'A' : 'B';

        Random moon = WorldPalette.rng(worldSeed, 0x300_4E5L);
        hasMoon = moon.nextFloat() < MOON_CHANCE;
        moonDistance = MOON_NEAREST + (MOON_FURTHEST - MOON_NEAREST) * moon.nextFloat();
        float radius = MOON_SMALLEST + (MOON_LARGEST - MOON_SMALLEST) * moon.nextFloat();
        moonAngle = radius / moonDistance;
        moonRadius = radius;
        // Grey rock, or tinged ochre, rust or ice
        float kind = moon.nextFloat(), grey = 0.55f + 0.2f * moon.nextFloat();
        float[] tinge = kind < 0.4f ? new float[] { 1f, 1f, 1f } : kind < 0.6f ? new float[] { 1.12f, 1f, 0.78f }
                : kind < 0.8f ? new float[] { 1.18f, 0.92f, 0.8f } : new float[] { 0.92f, 1f, 1.12f };
        moonColour = new float[] { Math.min(1f, grey * tinge[0]), Math.min(1f, grey * tinge[1]), Math.min(1f, grey * tinge[2]) };
        moonTilt = (moon.nextFloat() * 2f - 1f) * MOON_TILT;
        moonNode = moon.nextFloat() * (float) (Math.PI * 2);
        // (off to the left of the planet and a little nearer the pod, where it's seen on the way in)
        moonSide = Affine.normalise(new float[] { -0.75f + 0.3f * (moon.nextFloat() - 0.5f), 0.1f + 0.25f * moon.nextFloat(),
                -0.4f + 0.5f * moon.nextFloat() });

        Random way = WorldPalette.rng(worldSeed, 0xBE17_6A27L);
        boolean b = way.nextFloat() < BELT_CHANCE, g = way.nextFloat() < GIANT_CHANCE;
        // (always something on the way in: one or the other if neither came up)
        if (!b && !g) {
            if (way.nextBoolean()) b = true;
            else g = true;
        }
        belt = b;
        giant = g;
        giantHue = way.nextFloat();
        giantRings = way.nextFloat() < RINGS_CHANCE;
        giantSide = way.nextBoolean() ? 1f : -1f;
        giantTilt = 0.15f + 0.45f * way.nextFloat();
        // None to three strike the glass; each has an even chance of cracking it
        int n = way.nextFloat() < 0.25f ? 0 : 1 + way.nextInt(3);
        strikes = new float[n][];
        for (int i = 0; i < n; i++) {
            double phi = way.nextDouble() * Math.PI * 2, out = 0.15 + 0.5 * Math.sqrt(way.nextDouble());
            strikes[i] = new float[] { 0.2f + 0.65f * (i + way.nextFloat()) / n, 0.5f + (float) (0.5 * out * Math.cos(phi)),
                    0.5f + (float) (0.5 * out * Math.sin(phi)), way.nextFloat() < 0.5f ? 1f : 0f, way.nextFloat() };
        }
    }

    /** The world's star system (made once per seed). */
    static StarSystem of(long worldSeed) {
        return MADE.computeIfAbsent(worldSeed, StarSystem::new);
    }

    /** How wide the sun looks (its disc's half angle, radians). */
    float sunAngle() {
        return SUN_ANGLE * sunScale;
    }

    // ------------------------------------------------------------------ the moon in the sky

    /**
     * How far round from the sun the moon is this round (radians east of it along the sky, so 0 is
     * new, pi full): from the round's sun (its declination and the longitude where it's noon, the
     * same on both PCs) and the seed, never from a stream anything else draws on, tried a few times
     * for one that's up over where the pod came down.
     */
    double elongation(double declination, double noonLongitude, SpherePosition landedAt) {
        Random rng = WorldPalette.rng(worldSeed ^ Double.doubleToLongBits(declination) * 31 ^ Double.doubleToLongBits(noonLongitude), 0x300_7124L);
        double e = 0.0;
        for (int i = 0; i < MOON_TRIES; i++) {
            e = rng.nextDouble() * Math.PI * 2;
            if (landedAt == null || moonDirection(landedAt, declination, noonLongitude, e)[1] > 0.1f) break;
        }
        return e;
    }

    /**
     * Where the moon is in the sky at a place, as a unit direction in the local east/up/south
     * frame (as Planet.sunDirection): elongation radians round from the sun along its path, tipped
     * a little off it.
     */
    float[] moonDirection(SpherePosition at, double declination, double noonLongitude, double elongation) {
        // (it trails the sun across the sky: overhead where it was noon that far round before)
        double tipped = declination * Math.cos(elongation) + moonTilt * Math.sin(elongation + moonNode);
        return Planet.skyDirection(at, Math.max(-1.5, Math.min(1.5, tipped)), noonLongitude - elongation);
    }

    // ------------------------------------------------------------------ pictures

    /** The moon's surface, all round (equirectangular, longitude 0 facing the planet): grey plains, darker seas and craters. */
    BufferedImage moonSurface() {
        int w = 512, h = 256;
        Random rng = WorldPalette.rng(worldSeed, 0xC7A7_E25L);
        float[] shade = new float[w * h];
        java.util.Arrays.fill(shade, 1f);
        // Broad dark seas of old lava, and craters of every size, more small than large
        double[][] spots = new double[160][];
        for (int i = 0; i < spots.length; i++) {
            double[] d = randomDirection(rng);
            boolean sea = i < 7;
            double size = sea ? 0.25 + 0.35 * rng.nextDouble() : 0.015 + 0.12 * Math.pow(rng.nextDouble(), 3.0);
            spots[i] = new double[] { d[0], d[1], d[2], size, sea ? 1 : 0, Math.cos(Math.min(Math.PI, size * 1.4)) };
        }
        for (int y = 0; y < h; y++) {
            double lat = Math.PI * 0.5 - (y + 0.5) / h * Math.PI;
            for (int x = 0; x < w; x++) {
                double lon = (x + 0.5) / w * Math.PI * 2 - Math.PI;
                double px = Math.cos(lat) * Math.cos(lon), py = Math.sin(lat), pz = -Math.cos(lat) * Math.sin(lon);
                float s = 0.92f + 0.08f * (float) Math.sin(px * 9 + py * 13 + Math.sin(pz * 7) * 2);
                for (double[] spot : spots) {
                    double dot = px * spot[0] + py * spot[1] + pz * spot[2];
                    if (dot < spot[5]) continue;
                    double r = Math.acos(Math.min(1, dot)) / spot[3];
                    if (spot[4] > 0) {
                        s *= (float) (1.0 - 0.32 * smooth(1.0 - r));
                    } else {
                        // A dark floor, a bright rim
                        s *= (float) (r < 1 ? 0.82 + 0.1 * r : 1.0 + 0.25 * Math.max(0, 1.0 - (r - 1.0) / 0.4));
                    }
                }
                shade[y * w + x] = s;
            }
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < shade.length; i++) {
            float s = Math.min(1.25f, shade[i]);
            image.setRGB(i % w, i / w, argb(1f, moonColour[0] * s, moonColour[1] * s, moonColour[2] * s));
        }
        return image;
    }

    /** The gas giant's face, all round: bands of its hue and their neighbours', wavering, and a storm. */
    BufferedImage giantSurface() {
        int w = 512, h = 256;
        Random rng = WorldPalette.rng(worldSeed, 0x6A57_61A7L);
        int bands = 9 + rng.nextInt(8);
        float[][] colours = new float[bands][];
        float[] edges = new float[bands];
        for (int i = 0; i < bands; i++) {
            float hue = giantHue + (rng.nextFloat() - 0.5f) * 0.12f;
            colours[i] = WorldPalette.hsv(hue, 0.18f + 0.45f * rng.nextFloat(), 0.55f + 0.4f * rng.nextFloat());
            edges[i] = rng.nextFloat();
        }
        java.util.Arrays.sort(edges);
        double stormLat = (rng.nextDouble() - 0.5) * 1.2, stormLon = rng.nextDouble() * Math.PI * 2 - Math.PI;
        float[] stormColour = WorldPalette.hsv(giantHue + 0.5f * (rng.nextFloat() - 0.5f), 0.55f, 0.85f);
        double wave = 2 + rng.nextInt(4), wobble = 0.02 + 0.03 * rng.nextDouble();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            double lat = Math.PI * 0.5 - (y + 0.5) / h * Math.PI;
            for (int x = 0; x < w; x++) {
                double lon = (x + 0.5) / w * Math.PI * 2 - Math.PI;
                // Where across the bands, wavering with longitude and finer swirls
                double v = (Math.sin(lat) + 1.0) * 0.5 + wobble * Math.sin(lon * wave + lat * 9.0)
                        + 0.008 * Math.sin(lon * 23.0 + Math.sin(lat * 40.0) * 3.0);
                v = ((v % 1.0) + 1.0) % 1.0;
                int band = 0;
                while (band < bands - 1 && edges[band + 1] < v) band++;
                float[] a = colours[band], c = colours[(band + 1) % bands];
                float next = band + 1 < bands ? edges[band + 1] : 1f;
                float blend = (float) smooth(1.0 - Math.min(1.0, (next - v) / 0.015));
                float r = a[0] + (c[0] - a[0]) * blend, g = a[1] + (c[1] - a[1]) * blend, b = a[2] + (c[2] - a[2]) * blend;
                // The storm, an oval eye
                double dl = Math.atan2(Math.sin(lon - stormLon), Math.cos(lon - stormLon)) / 0.18, dt = (lat - stormLat) / 0.08;
                double storm = Math.max(0.0, 1.0 - Math.sqrt(dl * dl + dt * dt));
                float k = (float) smooth(storm * 2.0);
                r += (stormColour[0] - r) * k;
                g += (stormColour[1] - g) * k;
                b += (stormColour[2] - b) * k;
                image.setRGB(x, y, argb(1f, r, g, b));
            }
        }
        return image;
    }

    /** The giant's rings, across (inner edge to outer, left to right): their colour, and how dense (alpha), with gaps. */
    BufferedImage ringProfile() {
        int w = 256;
        Random rng = WorldPalette.rng(worldSeed, 0x2146_5000L);
        float[] base = WorldPalette.hsv(giantHue + 0.05f, 0.15f + 0.2f * rng.nextFloat(), 0.8f);
        float[] density = new float[w];
        for (int i = 0; i < w; i++) density[i] = 0.35f + 0.3f * rng.nextFloat();
        // (smoothed, with a few gaps cut through)
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 1; i < w - 1; i++) density[i] = (density[i - 1] + density[i] * 2f + density[i + 1]) / 4f;
        }
        int gaps = 1 + rng.nextInt(3);
        for (int k = 0; k < gaps; k++) {
            int at = 30 + rng.nextInt(w - 60), wide = 2 + rng.nextInt(7);
            for (int i = Math.max(0, at - wide); i < Math.min(w, at + wide); i++) density[i] *= 0.08f;
        }
        BufferedImage image = new BufferedImage(w, 1, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < w; i++) {
            float edge = (float) smooth(Math.min(i, w - 1 - i) / 10.0);
            float tone = 0.8f + 0.4f * density[i];
            image.setRGB(i, 0, argb(density[i] * edge, base[0] * tone, base[1] * tone, base[2] * tone));
        }
        return image;
    }

    private static double[] randomDirection(Random rng) {
        double x, y, z, l;
        do {
            x = rng.nextGaussian();
            y = rng.nextGaussian();
            z = rng.nextGaussian();
            l = Math.sqrt(x * x + y * y + z * z);
        } while (l < 1e-6);
        return new double[] { x / l, y / l, z / l };
    }

    private static double smooth(double x) {
        x = Math.max(0.0, Math.min(1.0, x));
        return x * x * (3.0 - 2.0 * x);
    }

    private static int argb(float a, float r, float g, float b) {
        return (Math.round(WorldPalette.clamp01(a) * 255) << 24) | (Math.round(WorldPalette.clamp01(r) * 255) << 16)
                | (Math.round(WorldPalette.clamp01(g) * 255) << 8) | Math.round(WorldPalette.clamp01(b) * 255);
    }
}
