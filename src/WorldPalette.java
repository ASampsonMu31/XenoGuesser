import java.awt.Color;
import java.util.Random;

/**
 * The colour scheme of one world, derived entirely from its seed.
 *
 * Colours are chosen together rather than independently so that they stay believable
 * side by side: the sun is a black-body colour for a random star temperature, the
 * horizon is the sky washed toward that sunlight, and the soil is re-rolled until it
 * reads clearly against the sky (the fog fades distant ground into the horizon colour,
 * so a soil close in hue to the sky makes the whole landscape look flat and muddy).
 */
public final class WorldPalette {

    public final double starTemperatureK;
    public final float[] sunTint;
    public final float[] skyZenith;
    public final float[] skyHorizon;
    public final float[] soilBase;
    public final float[] seaDeep;
    public final float[] seaLight;

        private final float seaHue;

    /** Water shader tints: colour seen through shallow water, and of open deep ocean. */
    public float[] seaShallowTint() {
        return hsv(seaHue - 0.03f, 1.0f, 0.6f);
    }

    public float[] seaDeepOcean() {
        return hsv(seaHue + 0.02f, 0.97f, 0.3f);
    }

        /**
     * Two further soil colours that the ground drifts toward region by region (as earth
     * soils shift between brown, red, dark grey and pale), each a plausible relative of the
     * base soil and each kept clear of the sky colour like the base.
     */
    public final float[] soilRegionalA;
    public final float[] soilRegionalB;

    /** Grass colour where conditions are poor (dry) and where they are best (lush). */
    public final float[] grassDry;
    public final float[] grassLush;

    private static final float MIN_SOIL_SKY_HUE_DISTANCE = 40f;
    private static final float MIN_GRASS_SEA_HUE_DISTANCE = 60f;
    private static final float MIN_SUN_SKY_HUE_DISTANCE = 60f;
    private static final float GREY_SOIL_SATURATION = 0.14f;

    public WorldPalette(long worldSeed) {
        Random rng = rng(worldSeed, 0x5EED_C010_0A11L);

        // Cool red dwarfs are far more common than hot blue stars, so bias toward the low end
        double t = rng.nextDouble();
        starTemperatureK = 2700 + 8800 * t * t;
        sunTint = blackBody(starTemperatureK);

                // The sun disc must stand out from the sky around it: a coloured star needs a sky
        // well away from its hue, and a near-white star needs a properly saturated sky
        float[] sunHsv = toHsv(sunTint);
        float skyHue = 0f, skySat = 0f, skyVal = 0f;
        for (int attempt = 0; attempt < 64; attempt++) {
            skyHue = rng.nextFloat();
            skySat = 0.55f + rng.nextFloat() * 0.40f;
            skyVal = 0.82f + rng.nextFloat() * 0.16f;
            boolean colouredSun = sunHsv[1] > 0.15f;
            boolean contrasts = colouredSun
                    ? hueDistance(skyHue * 360f, sunHsv[0] * 360f) >= MIN_SUN_SKY_HUE_DISTANCE
                    : skySat >= 0.65f;
            if (contrasts) break;
        }
        skyZenith = hsv(skyHue, skySat, skyVal);

        // Near the horizon light travels through more atmosphere: paler, and tinted by the sun
        float wash = 0.45f + rng.nextFloat() * 0.30f;
        float[] washed = new float[3];
        for (int i = 0; i < 3; i++) {
            float sunLit = 0.55f + 0.45f * sunTint[i];
            washed[i] = clamp01(skyZenith[i] + (sunLit - skyZenith[i]) * wash);
        }
        skyHorizon = washed;

        soilBase = pickSoil(rng);
        // Two clearly different regional soils, so the ground itself hints at where you are
        int kindA = rng.nextInt(SOIL_VARIANT_KINDS);
        int kindB = (kindA + 1 + rng.nextInt(SOIL_VARIANT_KINDS - 1)) % SOIL_VARIANT_KINDS;
        soilRegionalA = pickSoilVariant(rng, soilBase, kindA);
        soilRegionalB = pickSoilVariant(rng, soilBase, kindB);

        // The sea needn't be water: any hue, as long as it reads apart from the sky
        float[] skyHsv = toHsv(skyZenith);
        float seaHue = 0f;
        for (int attempt = 0; attempt < 32; attempt++) {
            seaHue = rng.nextFloat() < 0.35f ? (190f + rng.nextFloat() * 40f) / 360f : rng.nextFloat();
            if (hueDistance(seaHue * 360f, skyHsv[0] * 360f) >= 35f) break;
        }
        // Texel colours for the sea texture's troughs and crests, modelled on the original
        // hand-made water (about (0.07, 0.29, 0.62) to (0.28, 0.66, 0.95) for a blue sea)
        // and rotated to this world's hue
        this.seaHue = seaHue;
        seaDeep = hsv(seaHue, 0.85f + rng.nextFloat() * 0.07f, 0.55f + rng.nextFloat() * 0.13f);
        seaLight = hsv(seaHue - 0.03f, 0.62f + rng.nextFloat() * 0.13f, 0.92f + rng.nextFloat() * 0.08f);

        // Grass runs from a dry brown, related to the soil, to a lush colour that is green on
        // some worlds and pink, yellow, teal, violet or anything else on others
        float[] soilHsv = toHsv(soilBase);
        grassDry = hsv(0.07f + (soilHsv[0] - 0.07f) * 0.3f + (rng.nextFloat() - 0.5f) * 0.04f,
                0.35f + rng.nextFloat() * 0.2f, 0.45f + rng.nextFloat() * 0.15f);
        // ...and never too like the sea, so land and water can't be mistaken for each other
        float lushHue = 0f;
        boolean found = false;
        for (int attempt = 0; attempt < 64 && !found; attempt++) {
            lushHue = rng.nextFloat() < 0.35f ? (90f + rng.nextFloat() * 50f) / 360f : rng.nextFloat();
            boolean clearOfSky = hueDistance(lushHue * 360f, skyHsv[0] * 360f) >= 40f;
            boolean clearOfSoil = soilHsv[1] < GREY_SOIL_SATURATION || hueDistance(lushHue * 360f, soilHsv[0] * 360f) >= 40f;
            boolean clearOfSea = hueDistance(lushHue * 360f, seaHue * 360f) >= MIN_GRASS_SEA_HUE_DISTANCE;
            found = clearOfSky && clearOfSoil && clearOfSea;
        }
        // Nothing fitting every rule: at least as far from the sea as can be
        if (!found) lushHue = (seaHue + 0.5f) % 1f;
        grassLush = hsv(lushHue, 0.6f + rng.nextFloat() * 0.3f, 0.3f + rng.nextFloat() * 0.25f);
    }

    private static final int SOIL_VARIANT_KINDS = 4;

    private float[] pickSoil(Random rng) {
        float[] skyHsv = toHsv(skyHorizon);
        float[] zenithHsv = toHsv(skyZenith);
        float[] candidate = null;
        for (int attempt = 0; attempt < 64; attempt++) {
            float hue, sat, val;
            float family = rng.nextFloat();
            if (family < 0.50f) {          // iron-rich browns and reds
                hue = 6f + rng.nextFloat() * 32f;
                sat = 0.38f + rng.nextFloat() * 0.30f;
                val = 0.32f + rng.nextFloat() * 0.25f;
            } else if (family < 0.70f) {   // ochre and sandy loams
                hue = 32f + rng.nextFloat() * 16f;
                sat = 0.32f + rng.nextFloat() * 0.25f;
                val = 0.42f + rng.nextFloat() * 0.18f;
            } else if (family < 0.78f) {   // grey ash and basalt
                hue = rng.nextFloat() * 360f;
                sat = 0.05f + rng.nextFloat() * 0.09f;
                val = 0.28f + rng.nextFloat() * 0.27f;
            } else {                       // alien minerals: unearthly but still earthy-toned
                // Greens are skipped: green ground reads as moss or grass rather than soil
                hue = 170f + rng.nextFloat() * 200f;
                sat = 0.22f + rng.nextFloat() * 0.22f;
                val = 0.30f + rng.nextFloat() * 0.25f;
            }
            candidate = hsv(hue / 360f, sat, val);
            boolean grey = sat < GREY_SOIL_SATURATION;
            boolean clearOfHorizon = grey || hueDistance(hue, skyHsv[0] * 360f) >= MIN_SOIL_SKY_HUE_DISTANCE;
            boolean clearOfZenith = grey || hueDistance(hue, zenithHsv[0] * 360f) >= MIN_SOIL_SKY_HUE_DISTANCE * 0.75f;
            boolean darkerThanSky = skyHsv[2] - val >= 0.25f;
            if (clearOfHorizon && clearOfZenith && darkerThanSky) return candidate;
        }
        return candidate;
    }

    /** A regional relative of the base soil: redder, dark and ashy, pale and sandy, or browner and richer. */
    private float[] pickSoilVariant(Random rng, float[] base, int kind) {
        float[] hsv = toHsv(base);
        float[] skyHsv = toHsv(skyHorizon);
        float[] candidate = base;
        for (int attempt = 0; attempt < 32; attempt++) {
            float hue = hsv[0], sat = Math.max(0.15f, hsv[1]), val = hsv[2];
            switch (kind) {
                case 0 -> {               // redder, iron-rich
                    hue -= 0.035f + rng.nextFloat() * 0.04f;
                    sat = Math.min(0.75f, sat * 1.5f + 0.1f);
                    val *= 0.92f;
                }
                case 1 -> {               // dark grey, ashy or peaty
                    sat *= 0.25f;
                    val *= 0.5f + rng.nextFloat() * 0.12f;
                }
                case 2 -> {               // pale, sandy or chalky
                    hue += 0.02f;
                    sat *= 0.5f;
                    val = Math.min(0.72f, val * (1.45f + rng.nextFloat() * 0.2f));
                }
                default -> {              // ochre-brown, humus-rich
                    hue += 0.04f + rng.nextFloat() * 0.03f;
                    sat = Math.min(0.7f, sat * 1.25f + 0.05f);
                    val *= 0.72f;
                }
            }
            candidate = hsv(hue, sat, val);
            boolean grey = sat < GREY_SOIL_SATURATION;
            boolean clear = grey || hueDistance(((hue % 1f) + 1f) % 1f * 360f, skyHsv[0] * 360f) >= MIN_SOIL_SKY_HUE_DISTANCE;
            if (clear && skyHsv[2] - val >= 0.2f) return candidate;
        }
        return candidate;
    }

    /**
     * A Random seeded through SplitMix64. java.util.Random's first outputs are nearly
     * identical for neighbouring seeds (1, 2, 3...), which would make consecutive worlds
     * look alike; scrambling the seed first decorrelates them.
     */
    static Random rng(long seed, long salt) {
        return new Random(mix(mix(seed * 0x9E3779B97F4A7C15L) ^ salt));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Approximate sRGB colour of a black body (Tanner Helland's fit), normalised to max channel 1. */
    static float[] blackBody(double kelvin) {
        double t = kelvin / 100.0;
        double r, g, b;
        if (t <= 66) {
            r = 255;
            g = 99.4708025861 * Math.log(t) - 161.1195681661;
            b = t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        } else {
            r = 329.698727446 * Math.pow(t - 60, -0.1332047592);
            g = 288.1221695283 * Math.pow(t - 60, -0.0755148492);
            b = 255;
        }
        float[] c = {clamp01((float) r / 255f), clamp01((float) g / 255f), clamp01((float) b / 255f)};
        float max = Math.max(c[0], Math.max(c[1], c[2]));
        return new float[] {c[0] / max, c[1] / max, c[2] / max};
    }

    static float[] hsv(float h, float s, float v) {
        int rgb = Color.HSBtoRGB(((h % 1f) + 1f) % 1f, clamp01(s), clamp01(v));
        return new float[] {((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f};
    }

    static float[] toHsv(float[] rgb) {
        return Color.RGBtoHSB(Math.round(rgb[0] * 255), Math.round(rgb[1] * 255), Math.round(rgb[2] * 255), null);
    }

    private static float hueDistance(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
