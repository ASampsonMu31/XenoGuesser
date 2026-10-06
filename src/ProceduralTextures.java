import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

/**
 * Seeded generators for every texture in the game. Nothing here reads an image file:
 * each texture is built from TileableNoise fields and the world's WorldPalette, so a
 * different seed gives genuinely different art rather than a different pick from a pool.
 */
public final class ProceduralTextures {

    private ProceduralTextures() {}

    public static final int SOIL_SIZE = 1024;
    public static final int SEA_SIZE = 1024;
    public static final int LEAF_SIZE = 256;

    /**
     * Candidate soil models, scored offline for realism (1-10) over seeds 1-6 as rendered in
     * game: GRAIN_ONLY 6.8 mean (every seed >= 6), CRACKED 6.3, PEBBLES 5.7 (pebbles read
     * as stamped discs). The losers are kept so the comparison can be re-run.
     */
    public enum SoilModel { GRAIN_ONLY, PEBBLES, CRACKED }

    public static final SoilModel SELECTED_SOIL_MODEL = SoilModel.GRAIN_ONLY;

    // ------------------------------------------------------------------------------------
    // Soil
    // ------------------------------------------------------------------------------------

    public static BufferedImage soil(long seed, WorldPalette palette) {
        return soil(seed, palette, SELECTED_SOIL_MODEL);
    }

    /**
     * Soil is the palette's base colour modulated per pixel. Brightness varies on three
     * scales (broad damp/dry patches, clumps, single-pixel grains) and each pixel's hue
     * drifts between a warmer and a greyer version of the base colour, so grains read as
     * variations of one soil rather than as stamped-on specks.
     */
    public static BufferedImage soil(long seed, WorldPalette palette, SoilModel model) {
        Random rng = WorldPalette.rng(seed, 0x501L);
        int n = SOIL_SIZE;

        // Each terrain chunk shows exactly one tile, so broad patches are kept to at least
        // three per tile and low contrast; otherwise they'd form a visible grid across chunks
        float patchContrast = 0.06f + rng.nextFloat() * 0.07f;
        float clumpContrast = 0.06f + rng.nextFloat() * 0.08f;
        float graininess = 0.08f + rng.nextFloat() * 0.16f;
        float hueDrift = 0.25f + rng.nextFloat() * 0.45f;

        float[] patches = TileableNoise.spectral(rng, n, 1.7, 1.0, 0.0, 3);
        float[] clumps = TileableNoise.spectral(rng, n, 1.1, 1.0, 0.0, 24);
        // Single-pixel grain plus small 2-4 pixel clods; the clods matter in game because
        // seen at a glancing angle the GPU averages single-pixel grain away
        float[] fineGrain = TileableNoise.white(rng, n);
        float[] softGrain = TileableNoise.blurWrap(TileableNoise.white(rng, n), n, 1);
        float[] clods = TileableNoise.blurWrap(TileableNoise.white(rng, n), n, 2);
        float clodContrast = 0.10f + rng.nextFloat() * 0.10f;
        float[] mineralField = TileableNoise.spectral(rng, n, 0.9, 1.0, 0.0, 16);
        float[] mineralGrain = TileableNoise.white(rng, n);

        float[] base = palette.soilBase;
        float[] hsv = WorldPalette.toHsv(base);
        float[] warm = WorldPalette.hsv(hsv[0] + 0.025f, hsv[1] * 1.25f, hsv[2]);
        float[] grey = WorldPalette.hsv(hsv[0] - 0.02f, hsv[1] * 0.45f, hsv[2]);

        float[] pebbleShade = null, pebbleMask = null, crackShade = null;
        if (model == SoilModel.PEBBLES) {
            int cells = 48 + rng.nextInt(48);
            float[][] w = TileableNoise.worley(rng, n, cells);
            float cell = (float) n / cells;
            pebbleMask = new float[n * n];
            pebbleShade = new float[n * n];
            float pebbleRadius = cell * (0.22f + rng.nextFloat() * 0.15f);
            // Pebbles gather where a smooth field is high, so whole stones appear or vanish together
            float[] stoniness = TileableNoise.spectral(rng, n, 2.0);
            for (int i = 0; i < n * n; i++) {
                float inside = smoothstep(pebbleRadius, pebbleRadius * 0.75f, w[0][i]);
                pebbleMask[i] = inside * smoothstep(0.0f, 0.6f, stoniness[i]);
                pebbleShade[i] = 1f - 0.35f * (w[0][i] / pebbleRadius);
            }
        } else if (model == SoilModel.CRACKED) {
            int cells = 10 + rng.nextInt(14);
            float[][] w = TileableNoise.worley(rng, n, cells);
            crackShade = new float[n * n];
            float crackWidth = 1.2f + rng.nextFloat() * 1.5f;
            for (int i = 0; i < n * n; i++) {
                float edge = w[1][i] - w[0][i];
                crackShade[i] = 1f - 0.45f * smoothstep(crackWidth, 0f, edge);
            }
        }

        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n * n; i++) {
            float grain = 0.7f * fineGrain[i] + 0.3f * softGrain[i] * 1.7f;
            float lum = (float) Math.exp(patchContrast * patches[i] + clumpContrast * clumps[i]
                    + graininess * grain + clodContrast * clods[i] * 2.2f);

            // Per-pixel mineral mix: mostly base colour, drifting warm or grey grain by grain
            float m = 0.55f * mineralField[i] + 0.45f * mineralGrain[i];
            float t = Math.max(-1f, Math.min(1f, m * hueDrift));
            float[] tint = t >= 0 ? warm : grey;
            float a = Math.abs(t);

            float r = (base[0] + (tint[0] - base[0]) * a) * lum;
            float g = (base[1] + (tint[1] - base[1]) * a) * lum;
            float b = (base[2] + (tint[2] - base[2]) * a) * lum;

            if (pebbleMask != null && pebbleMask[i] > 0f) {
                float p = pebbleMask[i];
                float shade = pebbleShade[i] * 1.35f * (1f + 0.15f * fineGrain[i]);
                r += (grey[0] * shade - r) * p;
                g += (grey[1] * shade - g) * p;
                b += (grey[2] * shade - b) * p;
            }
            if (crackShade != null) {
                r *= crackShade[i]; g *= crackShade[i]; b *= crackShade[i];
            }
            img.setRGB(i % n, i / n, rgb(r, g, b));
        }
        return img;
    }

    // ------------------------------------------------------------------------------------
    // Sea
    // ------------------------------------------------------------------------------------

        /**
     * Smooth, mid-sized blobs with no large swells and no fine grain: roughly 5 to 14 per
     * tile. The water shader multiplies and adds four copies of this scrolling past each
     * other, and it is that interference between band-limited blobs that produces the
     * dense, glittering ripple (large swells only blend into cloudy smears). Colours are
     * saturated so the brightest channel saturates in the blend, as in the original
     * hand-made water, while the other channels carry the ripple.
     */
    public static BufferedImage sea(long seed, WorldPalette palette) {
        Random rng = WorldPalette.rng(seed, 0x5EAL);
        int n = SEA_SIZE;
        double windAngle = rng.nextDouble() * Math.PI;
        double blobSize = 9 + rng.nextDouble() * 5;
        float[] h = TileableNoise.spectral(rng, n, 0.6, 1.0 + rng.nextDouble() * 0.4, windAngle, 4, blobSize);
        float gamma = 0.85f + rng.nextFloat() * 0.3f;
        float[] range = percentileRange(h, 0.01f, 0.99f);

        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        float[] deep = palette.seaDeep, light = palette.seaLight;
        for (int i = 0; i < h.length; i++) {
            float v = WorldPalette.clamp01((h[i] - range[0]) / (range[1] - range[0]));
            v = (float) Math.pow(v, gamma);
            img.setRGB(i % n, i / n, rgb(deep[0] + (light[0] - deep[0]) * v,
                    deep[1] + (light[1] - deep[1]) * v,
                    deep[2] + (light[2] - deep[2]) * v));
        }
        return img;
    }

    // ------------------------------------------------------------------------------------
    // Sky and sun
    // ------------------------------------------------------------------------------------

    /**
     * Vertical sky gradient laid out like the original sky texture: the top 55% is the
     * zenith colour, easing into the horizon colour at the bottom. Every lit shader also
     * samples this for distance fog, so terrain fades into exactly the visible sky.
     */
    public static BufferedImage sky(long seed, WorldPalette palette) {
        Random rng = WorldPalette.rng(seed, 0x5C1L);
        int w = 64, h = 1024;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        float[] top = palette.skyZenith, bottom = palette.skyHorizon;
        for (int y = 0; y < h; y++) {
            float t = smoothstep(0.55f, 1.0f, y / (float) (h - 1));
            for (int x = 0; x < w; x++) {
                // Half-step dither so the long gradient doesn't band
                float d = (rng.nextFloat() - 0.5f) / 255f;
                img.setRGB(x, y, rgb(top[0] + (bottom[0] - top[0]) * t + d,
                        top[1] + (bottom[1] - top[1]) * t + d,
                        top[2] + (bottom[2] - top[2]) * t + d));
            }
        }
        return img;
    }

    // How far out the sun's disc reaches in its picture (the rest is its glow), as a fraction of the half-width
    public static final float SUN_DISC = 0.42f;

    /**
     * The sun: a dazzling disc, nearly white with a touch of the star's black-body colour and
     * only slightly dimmer towards its edge (as real stars are), inside a soft glow of light
     * scattered round it that fades out to the picture's edge.
     */
    public static BufferedImage sunGlow(WorldPalette palette) {
        int n = 512;
        float[] tint = palette.sunTint;
        float[] rim = WorldPalette.blackBody(Math.max(1800, palette.starTemperatureK * 0.6));
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        float radius = n / 2f - 1f, discPixels = radius * SUN_DISC;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float dx = x + 0.5f - n / 2f, dy = y + 0.5f - n / 2f;
                float pixels = (float) Math.sqrt(dx * dx + dy * dy);
                float r = pixels / discPixels;
                // The glow: bright close to the disc, fading away to nothing at the picture's edge
                float out = Math.max(0f, (pixels - discPixels) / (radius - discPixels));
                float glow = out >= 1f ? 0f : 0.75f * (float) Math.pow(1f - out, 3.0);
                float gr = 0.8f + 0.2f * tint[0], gg = 0.8f + 0.2f * tint[1], gb = 0.8f + 0.2f * tint[2];
                float disc = WorldPalette.clamp01((1f - r) * discPixels + 0.5f);
                if (disc <= 0f) {
                    img.setRGB(x, y, glow <= 0f ? 0 : argb(glow, gr, gg, gb));
                    continue;
                }
                float mu = (float) Math.sqrt(Math.max(0f, 1f - r * r));
                float limb = 1f - 0.12f * (1f - mu);
                float edge = (float) Math.pow(r, 10) * 0.35f;
                float cr = (1f - edge) * (0.88f + 0.12f * tint[0]) + edge * rim[0];
                float cg = (1f - edge) * (0.88f + 0.12f * tint[1]) + edge * rim[1];
                float cb = (1f - edge) * (0.88f + 0.12f * tint[2]) + edge * rim[2];
                float alpha = disc + (1f - disc) * glow;
                img.setRGB(x, y, argb(alpha, Math.min(1f, cr * limb * 1.1f), Math.min(1f, cg * limb * 1.1f), Math.min(1f, cb * limb * 1.1f)));
            }
        }
        return img;
    }

    // ------------------------------------------------------------------------------------
    // Grass
    // ------------------------------------------------------------------------------------

    /**
     * Two-column blade atlas (the grass shader picks the left or right half per blade).
     * Only alpha matters to the shader, which colours blades itself; edges are soft so
     * the shader's fwidth() alpha sharpening can anti-alias them.
     */
        public static final int GRASS_ATLAS_COLUMNS = 4;

    public static BufferedImage grassAtlas(long seed) {
        Random rng = WorldPalette.rng(seed, 0x6A55L);
                // Four blade shapes side by side; each grass instance picks one
        int columns = GRASS_ATLAS_COLUMNS;
        int w = 1024, h = 512, colW = w / columns;
        float baseWidth = 0.10f + rng.nextFloat() * 0.12f;   // fraction of column width
        float taperPower = 1.0f + rng.nextFloat() * 1.2f;
        float bendAmount = 0.05f + rng.nextFloat() * 0.25f;
        float forkChance = rng.nextFloat() < 0.2f ? 0.5f : 0f;  // some worlds grow forked blades
        float[] alpha = new float[w * h];

                for (int col = 0; col < columns; col++) {
            int blades = 1 + rng.nextInt(3);
            for (int b = 0; b < blades; b++) {
                float rootX = 0.5f + (rng.nextFloat() - 0.5f) * 0.35f;
                float lean = (rng.nextFloat() - 0.5f) * 2f * bendAmount;
                float wave = (rng.nextFloat() - 0.5f) * 0.08f;
                float heightFrac = b == 0 ? 0.97f : 0.6f + rng.nextFloat() * 0.35f;
                float width = baseWidth * (b == 0 ? 1f : 0.7f + rng.nextFloat() * 0.3f);
                boolean forked = rng.nextFloat() < forkChance;
                for (int y = 0; y < h; y++) {
                    float t = (h - 1 - y) / (float) (h - 1) / heightFrac;  // 0 at the root, 1 at the tip
                    if (t > 1f) continue;
                    float centre = rootX + lean * t * t + wave * (float) Math.sin(t * Math.PI * 2);
                    float half = width * 0.5f * (float) Math.pow(1f - t, taperPower);
                    for (int x = 0; x < colW; x++) {
                        float u = (x + 0.5f) / colW;
                        float dist = Math.abs(u - centre);
                        if (forked && t > 0.6f) {
                            // Two tines diverging in a V, each tapering to its own point
                            float split = (t - 0.6f) * 0.45f;
                            dist = Math.min(Math.abs(u - centre - split), Math.abs(u - centre + split));
                            half = width * 0.35f * (1f - t) / 0.4f;
                        }
                        float a = WorldPalette.clamp01((half - dist) * colW + 0.5f);
                        int idx = y * w + col * colW + x;
                        alpha[idx] = Math.max(alpha[idx], a);
                    }
                }
            }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < alpha.length; i++) img.setRGB(i % w, i / w, argb(alpha[i], 1f, 1f, 1f));
        return img;
    }

    // ------------------------------------------------------------------------------------
    // Leaves
    // ------------------------------------------------------------------------------------

    /**
     * One leaf per species as a greyscale intensity map with a cut-out alpha. The leaf
     * shader maps intensity continuously from the species' dark to light colour.
     *
     * Leaves are built in a leaf-local frame: v runs up the midrib from the base (0) to
     * the tip (1), u across it. A seeded family decides the outline (simple blade, palmate
     * fan, or compound leaflets) and a seeded mix of veins, mottling, spots, a base-to-tip
     * gradient and a rim band fills the inside. The stem runs to the bottom centre of the
     * image, where Flora pivots the leaf card.
     */
    public static BufferedImage leaf(long seed) {
        Random rng = WorldPalette.rng(seed, 0x1EAFL);
        int n = LEAF_SIZE;
        LeafShape shape = LeafShape.random(rng);

        float[] mottle = TileableNoise.spectral(rng, n, 1.3 + rng.nextDouble() * 0.6);
        float[] warpX = TileableNoise.spectral(rng, n, 1.8);
        float[] warpY = TileableNoise.spectral(rng, n, 1.8);
        float warpStrength = 4f + rng.nextFloat() * 14f;
        float[][] spots = rng.nextFloat() < 0.45f ? TileableNoise.worley(rng, n, 6 + rng.nextInt(12)) : null;
        float spotRadius = (n / 12f) * (0.25f + rng.nextFloat() * 0.35f);

        float veinCount = 4 + rng.nextInt(10);
        float veinAngle = 0.35f + rng.nextFloat() * 0.8f;
        float veinWidth = 0.010f + rng.nextFloat() * 0.018f;
        float veinSign = rng.nextBoolean() ? 1f : -1f;          // light or dark veins
        float veinWeight = 0.20f + rng.nextFloat() * 0.35f;
        float mottleWeight = 0.15f + rng.nextFloat() * 0.45f;
        float gradientWeight = (rng.nextFloat() - 0.5f) * 0.8f; // lighter base or lighter tip
        float spotWeight = (rng.nextBoolean() ? 1f : -1f) * (0.3f + rng.nextFloat() * 0.4f);
        float rimWeight = (rng.nextFloat() - 0.5f) * 0.9f;

        boolean[] inside = new boolean[n * n];
        float[] raw = new float[n * n];
        float[] veinField = new float[n * n];
        float baseY = n * 0.86f;                 // leaf base, in image pixels from the top
        float scale = n * 0.80f;                 // pixels per leaf-length unit

        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float u = (x + 0.5f - n / 2f) / scale;
                float v = (baseY - (y + 0.5f)) / scale;
                int i = y * n + x;

                float vein = shape.veinDistance(u, v, veinCount, veinAngle);
                veinField[i] = smoothstep(veinWidth, veinWidth * 0.3f, vein);

                boolean stem = Math.abs(u) < shape.stemWidth && v < 0.02f && y < n - 1;
                inside[i] = shape.contains(u, v) || stem;
                if (!inside[i]) continue;

                float wx = x + warpStrength * warpX[i];
                float wy = y + warpStrength * warpY[i];
                float value = mottleWeight * TileableNoise.sampleWrap(mottle, n, wx, wy) * 0.5f;
                value += gradientWeight * (v - 0.5f);
                value += veinSign * veinWeight * veinField[i];
                if (spots != null) value += spotWeight * smoothstep(spotRadius, spotRadius * 0.6f, spots[0][i]);
                if (stem) value = veinSign * veinWeight;
                raw[i] = value;
            }
        }

        // Rim band: how close each pixel is to the outline, via a blurred copy of the mask
        float[] maskF = new float[n * n];
        for (int i = 0; i < maskF.length; i++) maskF[i] = inside[i] ? 1f : 0f;
        float[] blurred = TileableNoise.blurWrap(TileableNoise.blurWrap(maskF, n, 3), n, 3);
        for (int i = 0; i < raw.length; i++) {
            if (inside[i]) raw[i] += rimWeight * (1f - blurred[i]) * 2f;
        }

        // Stretch each leaf to use the full dark-to-light range
        float[] values = new float[raw.length];
        int count = 0;
        for (int i = 0; i < raw.length; i++) if (inside[i]) values[count++] = raw[i];
        float[] range = percentileRange(Arrays.copyOf(values, Math.max(1, count)), 0.02f, 0.98f);
        float span = Math.max(1e-4f, range[1] - range[0]);
        double mean = 0;
        for (int i = 0; i < count; i++) mean += WorldPalette.clamp01((values[i] - range[0]) / span);
        float fill = count > 0 ? (float) (mean / count) : 0.5f;

        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < raw.length; i++) {
            if (inside[i]) {
                float g = WorldPalette.clamp01((raw[i] - range[0]) / span);
                img.setRGB(i % n, i / n, argb(1f, g, g, g));
            } else {
                // Transparent pixels carry the average intensity so mipmaps don't darken the edges
                img.setRGB(i % n, i / n, argb(0f, fill, fill, fill));
            }
        }
        return img;
    }

    /** Leaf outline families with continuous seeded parameters. */
    private static final class LeafShape {
        enum Family { BLADE, PALMATE, COMPOUND }

        Family family;
        float stemWidth;
        // BLADE
        float width, widestAt, roundness, serrationDepth, serrationCount, lobeDepth, lobeCount, asymmetry;
        // PALMATE
        int lobes;
        float fanAngle, lobeSharpness, innerRadius;
        // COMPOUND
        int pairs;
        float leafletSize, leafletAngle, terminalSize;
        boolean bulbous;

        static LeafShape random(Random rng) {
            LeafShape s = new LeafShape();
            float roll = rng.nextFloat();
            s.family = roll < 0.5f ? Family.BLADE : (roll < 0.78f ? Family.PALMATE : Family.COMPOUND);
            s.stemWidth = 0.008f + rng.nextFloat() * 0.012f;

            s.width = 0.12f + rng.nextFloat() * 0.33f;
            s.widestAt = 0.25f + rng.nextFloat() * 0.45f;
            s.roundness = 0.4f + rng.nextFloat() * 1.4f;
            s.serrationDepth = rng.nextFloat() < 0.5f ? rng.nextFloat() * 0.18f : 0f;
            s.serrationCount = 6 + rng.nextInt(26);
            s.lobeDepth = rng.nextFloat() < 0.35f ? 0.15f + rng.nextFloat() * 0.45f : 0f;
            s.lobeCount = 2 + rng.nextInt(5);
            s.asymmetry = (rng.nextFloat() - 0.5f) * 0.3f;

            s.lobes = 3 + rng.nextInt(7);
            s.fanAngle = (float) Math.toRadians(70 + rng.nextFloat() * 110);
            s.lobeSharpness = 0.5f + rng.nextFloat() * 4f;
            s.innerRadius = 0.25f + rng.nextFloat() * 0.4f;

            s.pairs = 2 + rng.nextInt(4);
            s.leafletSize = 0.08f + rng.nextFloat() * 0.08f;
            s.leafletAngle = (float) Math.toRadians(25 + rng.nextFloat() * 50);
            s.terminalSize = 0.6f + rng.nextFloat() * 0.8f;
            s.bulbous = rng.nextFloat() < 0.4f;
            return s;
        }

        boolean contains(float u, float v) {
            switch (family) {
                case PALMATE: return palmate(u, v);
                case COMPOUND: return compound(u, v);
                default: return blade(u, v, 1f);
            }
        }

        private boolean blade(float u, float v, float sizeScale) {
            if (v <= 0f || v >= 1f) return false;
            float profileT = v < widestAt ? 0.5f * v / widestAt : 0.5f + 0.5f * (v - widestAt) / (1f - widestAt);
            float half = width * sizeScale * (float) Math.pow(Math.sin(Math.PI * profileT), roundness);
            half *= 1f + serrationDepth * (float) Math.abs(Math.sin(Math.PI * serrationCount * v)) - serrationDepth * 0.5f;
            half *= 1f - lobeDepth * (float) Math.pow(Math.abs(Math.sin(Math.PI * lobeCount * v)), 3);
            float side = u >= 0 ? 1f + asymmetry : 1f - asymmetry;
            return Math.abs(u) < half * side;
        }

        private boolean palmate(float u, float v) {
            float hubV = 0.05f;
            float dv = v - hubV;
            float r = (float) Math.sqrt(u * u + dv * dv);
            float theta = (float) Math.atan2(u, dv);          // 0 points straight up
            if (Math.abs(theta) > fanAngle / 2f + 0.2f) return r < 0.035f;
            float phase = (theta / fanAngle + 0.5f) * lobes;
            float lobe = (float) Math.pow(Math.abs(Math.cos(Math.PI * (phase - Math.floor(phase) - 0.5f))), lobeSharpness);
            float edgeFade = smoothstep(fanAngle / 2f + 0.2f, fanAngle / 2f - 0.1f, Math.abs(theta));
            float reach = 0.92f * (innerRadius + (1f - innerRadius) * lobe) * (0.6f + 0.4f * edgeFade);
            return r < reach && v > -0.01f;
        }

        private boolean compound(float u, float v) {
            if (v < 0f || v > 1.08f) return false;
            if (Math.abs(u) < stemWidth * 0.8f && v < 0.8f) return true;   // rachis
            for (int p = 0; p < pairs; p++) {
                float attachV = 0.18f + 0.62f * p / Math.max(1, pairs - 1);
                for (int sideSign = -1; sideSign <= 1; sideSign += 2) {
                    // Rotate into the leaflet's own frame, which branches off the rachis
                    float du = u * sideSign, dv = v - attachV;
                    float c = (float) Math.cos(leafletAngle), s = (float) Math.sin(leafletAngle);
                    float lu = du * c - dv * s;
                    float lv = du * s + dv * c;
                    if (inLeaflet(lv, lu, leafletSize * (1f - 0.3f * p / pairs))) return true;
                }
            }
            return inLeaflet(v - 0.78f, u, leafletSize * terminalSize);
        }

        private boolean inLeaflet(float along, float across, float size) {
            if (size <= 0f) return false;
            if (bulbous) {
                float stalk = size * 1.2f;
                if (along > 0 && along < stalk && Math.abs(across) < stemWidth * 0.6f) return true;
                float du = along - stalk - size * 0.6f;
                return du * du + across * across < size * size * 0.4f;
            }
            float t = along / (size * 2.5f);
            if (t <= 0f || t >= 1f) return false;
            return Math.abs(across) < size * 0.55f * (float) Math.sin(Math.PI * Math.pow(t, 0.8));
        }

        /** Distance (leaf units) to the nearest vein: the midrib or a secondary vein. */
        float veinDistance(float u, float v, float count, float angle) {
            float midrib = Math.abs(u);
            if (family == Family.PALMATE) {
                float dv = v - 0.05f;
                float theta = (float) Math.atan2(u, dv);
                float phase = (theta / fanAngle + 0.5f) * lobes;
                float r = (float) Math.sqrt(u * u + dv * dv);
                return Math.abs(phase - Math.round(phase)) / lobes * fanAngle * r;
            }
            float slanted = v - Math.abs(u) * (float) Math.tan(angle);
            float spacing = 1f / count;
            float k = slanted / spacing;
            float secondary = Math.abs(k - Math.round(k)) * spacing * (float) Math.cos(angle);
            return Math.min(midrib, secondary);
        }
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    static float smoothstep(float edge0, float edge1, float x) {
        float t = WorldPalette.clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float[] percentileRange(float[] values, float lo, float hi) {
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        int last = sorted.length - 1;
        return new float[] {sorted[(int) (lo * last)], sorted[(int) (hi * last)]};
    }

    private static int rgb(float r, float g, float b) {
        return (to8(r) << 16) | (to8(g) << 8) | to8(b);
    }

    private static int argb(float a, float r, float g, float b) {
        return (to8(a) << 24) | rgb(r, g, b);
    }

    private static int to8(float v) {
        return Math.round(WorldPalette.clamp01(v) * 255f);
    }
}
