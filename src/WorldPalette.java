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
 * The sky, the sea and the lush grass are each any hue at all, as likely as any other as
 * it looks (OKLCH), and as vivid as the screen allows: the sea's far from both the others'
 * (the grass and sky may be alike), each as light as its hue is most vivid at within its
 * range: the sky's the lightest, the sea's the darkest.
 */
public final class WorldPalette {

    public final double starTemperatureK;
    public final float[] sunTint;
    public final float[] skyZenith;
    public final float[] skyHorizon;
    public final float[] soilBase;
    public final float[] seaDeep;
    public final float[] seaLight;

    // The sea's colour as seen through shallow water and over the open deep ocean (see the water shaders)
    private final float[] seaShallow, seaOcean;

    /** Water shader tints: colour seen through shallow water, and of open deep ocean. */
    public float[] seaShallowTint() {
        return seaShallow.clone();
    }

    public float[] seaDeepOcean() {
        return seaOcean.clone();
    }

    // The sky's, the sea's and the lush grass's hues, as they look (OKLCH degrees): the sea's at
    // least this far from both the others' (the grass and sky may be alike), every hue as likely
    // as any other, so worlds differ widely
    private static final float MIN_SEA_HUE_DISTANCE = 100f;
    // How light each may be (OKLab lightness, 0 black to 1 white), each made as light within its
    // range as its hue is most vivid at (yellows when light, blues when darker): the sky lightest,
    // the grass between, the sea darkest; the open ocean this much as light as the shallows
    private static final float SKY_LIGHTEST = 0.9f, SKY_DARKEST = 0.7f;
    private static final float GRASS_LIGHTEST = 0.82f, GRASS_DARKEST = 0.55f;
    private static final float SEA_LIGHTEST = 0.78f, SEA_DARKEST = 0.5f, SEA_DEEP_SHARE = 0.6f;
    // How strong a colour they're made (OKLCH chroma; less where the screen can't show it)
    private static final float VIVID = 0.3f;

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
        float sunHue = okHue(sunTint);
        float skyHue = 0f;
        for (int attempt = 0; attempt < 64; attempt++) {
            skyHue = rng.nextFloat() * 360f;
            if (sunHsv[1] <= 0.15f || hueDistance(skyHue, sunHue) >= MIN_SUN_SKY_HUE_DISTANCE) break;
        }
        skyZenith = vivid(mostVivid(skyHue, SKY_DARKEST, SKY_LIGHTEST), skyHue, VIVID * (0.75f + 0.25f * rng.nextFloat()));

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
        // (any hue at least MIN_SEA_HUE_DISTANCE from the sky's: evenly over what's left)
        float seaHue = (skyHue + MIN_SEA_HUE_DISTANCE + rng.nextFloat() * (360f - 2f * MIN_SEA_HUE_DISTANCE)) % 360f;
        float seaChroma = VIVID * (0.85f + 0.15f * rng.nextFloat());
        float seaLightness = mostVivid(seaHue, SEA_DARKEST, SEA_LIGHTEST);
        seaShallow = vivid(seaLightness, seaHue, seaChroma);
        seaOcean = vivid(seaLightness * SEA_DEEP_SHARE, seaHue, seaChroma);
        // Texel colours for the sea texture's troughs and crests, modelled on the original
        // hand-made water (about (0.07, 0.29, 0.62) to (0.28, 0.66, 0.95) for a blue sea)
        // and rotated to this world's hue
        seaDeep = vivid(seaLightness * (0.82f + rng.nextFloat() * 0.08f), seaHue, seaChroma);
        seaLight = vivid(Math.min(0.95f, seaLightness * 1.25f + rng.nextFloat() * 0.05f), seaHue - 6f, seaChroma * 0.8f);

        // Grass runs from a dry brown, related to the soil, to a lush colour that is green on
        // some worlds and pink, yellow, teal, violet or anything else on others
        float[] soilHsv = toHsv(soilBase);
        grassDry = hsv(0.07f + (soilHsv[0] - 0.07f) * 0.3f + (rng.nextFloat() - 0.5f) * 0.04f,
                0.35f + rng.nextFloat() * 0.2f, 0.45f + rng.nextFloat() * 0.15f);
        // ...leaning towards a hue of its own, any at all (not the lush grass's), so dead grass is
        // a reddish, purplish, yellowish or bluish brown from world to world rather than always
        // the same (from a stream of its own: every other colour stays as it was)
        Random dead = rng(worldSeed, 0xDEAD_6A55L);
        float[] lean = hsv(dead.nextFloat(), 0.55f + dead.nextFloat() * 0.35f, 0.5f + dead.nextFloat() * 0.25f);
        float share = 0.3f + dead.nextFloat() * 0.2f;
        for (int c = 0; c < 3; c++) grassDry[c] = grassDry[c] + (lean[c] - grassDry[c]) * share;
        // ...and never like the sea, so land and water can't be mistaken for each other (any hue at
        // least MIN_SEA_HUE_DISTANCE from the sea's, evenly; preferring one clear of the soil's)
        float soilHue = okHue(soilBase);
        float lushHue = 0f;
        for (int attempt = 0; attempt < 32; attempt++) {
            lushHue = (seaHue + MIN_SEA_HUE_DISTANCE + rng.nextFloat() * (360f - 2f * MIN_SEA_HUE_DISTANCE)) % 360f;
            if (soilHsv[1] < GREY_SOIL_SATURATION || hueDistance(lushHue, soilHue) >= 30f) break;
        }
        grassLush = vivid(mostVivid(lushHue, GRASS_DARKEST, GRASS_LIGHTEST), lushHue, VIVID * (0.8f + 0.2f * rng.nextFloat()));
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

    /**
     * The most vivid colour of a lightness and hue (OKLCH: lightness 0 to 1, hue in degrees) the
     * screen can show, no more vivid than chroma: as vivid as asked if it fits, else less.
     */
    static float[] vivid(float lightness, float hue, float chroma) {
        double h = Math.toRadians(((hue % 360f) + 360f) % 360f);
        double lo = 0, hi = chroma;
        float[] best = okLabToRgb(lightness, 0, 0);
        for (int i = 0; i < 24; i++) {
            double c = (lo + hi) * 0.5;
            float[] rgb = okLabToRgb(lightness, c * Math.cos(h), c * Math.sin(h));
            if (rgb != null) {
                best = rgb;
                lo = c;
            } else {
                hi = c;
            }
        }
        float[] at = okLabToRgb(lightness, hi * Math.cos(h), hi * Math.sin(h));
        return at != null ? at : best;
    }

    /** The lightness between darkest and lightest at which a hue can be shown most vivid. */
    static float mostVivid(float hue, float darkest, float lightest) {
        float best = darkest, bestChroma = -1f;
        for (int i = 0; i <= 16; i++) {
            float lightness = darkest + (lightest - darkest) * i / 16f;
            float chroma = okChroma(vivid(lightness, hue, 0.4f));
            if (chroma > bestChroma) {
                bestChroma = chroma;
                best = lightness;
            }
        }
        return best;
    }

    /** How strong a colour is (OKLCH chroma). */
    private static float okChroma(float[] rgb) {
        double[] lin = new double[3];
        for (int i = 0; i < 3; i++) lin[i] = rgb[i] <= 0.04045 ? rgb[i] / 12.92 : Math.pow((rgb[i] + 0.055) / 1.055, 2.4);
        double l = Math.cbrt(0.4122214708 * lin[0] + 0.5363325363 * lin[1] + 0.0514459929 * lin[2]);
        double m = Math.cbrt(0.2119034982 * lin[0] + 0.6806995451 * lin[1] + 0.1073969566 * lin[2]);
        double s = Math.cbrt(0.0883024619 * lin[0] + 0.2817188376 * lin[1] + 0.6299787005 * lin[2]);
        double a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        return (float) Math.hypot(a, b);
    }

    /** A colour's hue as it looks (OKLCH degrees, 0 to 360). */
    static float okHue(float[] rgb) {
        double[] lin = new double[3];
        for (int i = 0; i < 3; i++) lin[i] = rgb[i] <= 0.04045 ? rgb[i] / 12.92 : Math.pow((rgb[i] + 0.055) / 1.055, 2.4);
        double l = Math.cbrt(0.4122214708 * lin[0] + 0.5363325363 * lin[1] + 0.0514459929 * lin[2]);
        double m = Math.cbrt(0.2119034982 * lin[0] + 0.6806995451 * lin[1] + 0.1073969566 * lin[2]);
        double s = Math.cbrt(0.0883024619 * lin[0] + 0.2817188376 * lin[1] + 0.6299787005 * lin[2]);
        double a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        double hue = Math.toDegrees(Math.atan2(b, a));
        return (float) (hue < 0 ? hue + 360 : hue);
    }

    /** An OKLab colour as sRGB (0 to 1 each), or null if the screen can't show it. */
    private static float[] okLabToRgb(double lightness, double a, double b) {
        double l = lightness + 0.3963377774 * a + 0.2158037573 * b;
        double m = lightness - 0.1055613458 * a - 0.0638541728 * b;
        double s = lightness - 0.0894841775 * a - 1.2914855480 * b;
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        double[] lin = {
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s };
        float[] rgb = new float[3];
        for (int i = 0; i < 3; i++) {
            if (lin[i] < -1e-4 || lin[i] > 1 + 1e-4) return null;
            double c = Math.max(0, Math.min(1, lin[i]));
            rgb[i] = (float) (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);
        }
        return rgb;
    }

    private static float hueDistance(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
