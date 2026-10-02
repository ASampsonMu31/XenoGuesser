import java.awt.image.BufferedImage;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Seeded, seamlessly tiling textures for buildings and fences: brick and stone walls with
 * per-nation brick proportions, bonds and mortar, plain rendered walls, roof coverings, and
 * fence surfaces. Shading is kept symmetric (edges darken on every side) so the textures
 * read correctly whichever way up they are mapped onto a surface.
 */
public final class ArchitectureTextures {

    private ArchitectureTextures() {}

    public static final int WALL_SIZE = 512;
    public static final int ROOF_SIZE = 256;
    public static final int FENCE_SIZE = 256;
    private static final int COURSES_PER_TILE = 8;

    // ------------------------------------------------------------------------------------
    // Walls
    // ------------------------------------------------------------------------------------

        public static BufferedImage wall(long seed, BuildingStyle.WallVariant variant) {
        Random rng = WorldPalette.rng(seed, 0x3A11L);
        if (variant.finish == BuildingStyle.WallFinish.PLAIN) {
            return render(rng, WALL_SIZE, variant.colour, 0.07f, 0.05f, true);
        }
        boolean stone = variant.finish == BuildingStyle.WallFinish.STONE;
        return masonry(rng, WALL_SIZE, COURSES_PER_TILE, variant.brickAspect, variant.bond, stone,
                variant.mortarFraction, variant.colour, variant.mortarColour, variant.colourVariation);
    }

    /**
     * Courses of bricks or stones laid in a bond, separated by mortar. Every course holds a
     * whole number of units so the texture tiles; stone courses use irregular lengths.
     * Each unit gets its own shade, a speckled surface and darkened, slightly chipped edges.
     */
    static BufferedImage masonry(Random rng, int n, int courses, float aspect, BuildingStyle.Bond bond, boolean irregular,
                                 float mortarFraction, Vector3 unitColour, Vector3 mortarColour, float colourVariation) {
        int courseH = n / courses;
        int unitsPerCourse = Math.max(1, Math.round(n / (aspect * courseH)));
        float unitLength = (float) n / unitsPerCourse;

        // Unit boundaries along each course, in pixels, and that course's offset
        float[][] edges = new float[courses][];
        float[] offsets = new float[courses];
        for (int c = 0; c < courses; c++) {
            if (irregular) {
                float[] lengths = new float[unitsPerCourse];
                float total = 0;
                for (int i = 0; i < unitsPerCourse; i++) {
                    lengths[i] = 0.6f + rng.nextFloat() * 0.8f;
                    total += lengths[i];
                }
                edges[c] = new float[unitsPerCourse + 1];
                for (int i = 0; i < unitsPerCourse; i++) edges[c][i + 1] = edges[c][i] + lengths[i] / total * n;
                offsets[c] = rng.nextFloat() * n;
            } else {
                edges[c] = new float[unitsPerCourse + 1];
                for (int i = 0; i <= unitsPerCourse; i++) edges[c][i] = i * unitLength;
                offsets[c] = switch (bond) {
                    case RUNNING -> (c % 2) * unitLength * 0.5f;
                    case THIRD -> (c % 3) * unitLength / 3f;
                    case STACK -> 0f;
                };
            }
        }
        float[][] shade = new float[courses][unitsPerCourse];
        float[][] hueShift = new float[courses][unitsPerCourse];
        for (int c = 0; c < courses; c++) {
            for (int i = 0; i < unitsPerCourse; i++) {
                shade[c][i] = 1f + (float) rng.nextGaussian() * colourVariation * (irregular ? 1.6f : 1f);
                hueShift[c][i] = (float) rng.nextGaussian() * colourVariation * 0.4f;
            }
        }

        float[] speckle = TileableNoise.blurWrap(TileableNoise.white(rng, n), n, 1);
        float[] surface = TileableNoise.spectral(rng, n, 1.2, 1.0, 0.0, 8);
        float[] chip = TileableNoise.spectral(rng, n, 1.0, 1.0, 0.0, 24);
        float[] mortarGrain = TileableNoise.white(rng, n);
        float mortarHalf = mortarFraction * courseH * 0.5f;
        float[] base = {unitColour.x, unitColour.y, unitColour.z};
        float[] warm = {Math.min(1f, unitColour.x * 1.1f), unitColour.y, unitColour.z * 0.9f};

        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < n; y++) {
            int c = Math.min(courses - 1, y / courseH);
            float yIn = y - c * courseH;
            for (int x = 0; x < n; x++) {
                int i = y * n + x;
                float xs = ((x - offsets[c]) % n + n) % n;
                int unit = 0;
                while (unit < unitsPerCourse - 1 && xs >= edges[c][unit + 1]) unit++;
                float xIn = xs - edges[c][unit];
                float len = edges[c][unit + 1] - edges[c][unit];
                float edgeDist = Math.min(Math.min(xIn, len - xIn), Math.min(yIn, courseH - yIn)) + chip[i] * 1.2f;

                if (edgeDist < mortarHalf) {
                    float m = 1f + 0.12f * mortarGrain[i];
                    img.setRGB(x, y, rgb(mortarColour.x * m, mortarColour.y * m, mortarColour.z * m));
                    continue;
                }
                // Edges of each unit catch less light than its face
                float bevel = 0.78f + 0.22f * ProceduralTextures.smoothstep(mortarHalf, mortarHalf + 4f, edgeDist);
                float lum = shade[c][unit] * bevel * (1f + 0.10f * speckle[i] * 1.7f + 0.06f * surface[i]);
                float h = WorldPalette.clamp01(0.5f + hueShift[c][unit] * 4f);
                float r = (base[0] + (warm[0] - base[0]) * (h - 0.5f) * 2f) * lum;
                float g = (base[1] + (warm[1] - base[1]) * (h - 0.5f) * 2f) * lum;
                float b = (base[2] + (warm[2] - base[2]) * (h - 0.5f) * 2f) * lum;
                img.setRGB(x, y, rgb(r, g, b));
            }
        }
        return img;
    }

    /** A rendered or painted surface: broad mottling, fine grain and faint weathering streaks. */
    static BufferedImage render(Random rng, int n, Vector3 colour, float mottle, float grain, boolean streaks) {
        float[] broad = TileableNoise.spectral(rng, n, 1.6, 1.0, 0.0, 2);
        float[] fine = TileableNoise.blurWrap(TileableNoise.white(rng, n), n, 1);
        // Streaks run along one image axis; stretched noise reads as rain-weathering either way up
        float[] streak = streaks ? TileableNoise.spectral(rng, n, 1.2, 6.0, Math.PI / 2, 2) : null;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n * n; i++) {
            float lum = (float) Math.exp(mottle * broad[i] + grain * fine[i] * 1.7f + (streak != null ? 0.04f * streak[i] : 0f));
            img.setRGB(i % n, i / n, rgb(colour.x * lum, colour.y * lum, colour.z * lum));
        }
        return img;
    }

    // ------------------------------------------------------------------------------------
    // Roofs
    // ------------------------------------------------------------------------------------

    public static BufferedImage roof(long seed, BuildingStyle style) {
        Random rng = WorldPalette.rng(seed, 0x500FL);
        int n = ROOF_SIZE;
        Vector3 colour = style.roofColour;
        switch (style.roofFinish) {
            case CORRUGATED: {
                int ridges = 8 + rng.nextInt(10);
                float[] grime = TileableNoise.spectral(rng, n, 1.5, 1.0, 0.0, 2);
                BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        float wave = (float) Math.sin(2 * Math.PI * ridges * x / n);
                        float lum = 0.82f + 0.18f * wave + 0.06f * grime[y * n + x];
                        img.setRGB(x, y, rgb(colour.x * lum, colour.y * lum, colour.z * lum));
                    }
                }
                return img;
            }
            case TILES:
                return tiledRows(rng, n, 6 + rng.nextInt(4), 1.0f + rng.nextFloat() * 0.6f, colour, true);
            case SHINGLES:
                return tiledRows(rng, n, 8 + rng.nextInt(6), 0.6f + rng.nextFloat() * 0.5f, colour, false);
            case PLAIN:
            default:
                return render(rng, n, colour, 0.08f, 0.06f, false);
        }
    }

    /**
     * Overlapping rows of tiles or shingles: each row is offset by half a unit, each unit
     * darkens toward its row edges, and units vary in shade. Rounded tiles also darken at
     * their sides like curved clay.
     */
    private static BufferedImage tiledRows(Random rng, int n, int rows, float aspect, Vector3 colour, boolean rounded) {
        int rowH = n / rows;
        int perRow = Math.max(2, Math.round(n / (rowH * aspect)));
        float unitW = (float) n / perRow;
        float[][] shade = new float[rows][perRow];
        for (int r = 0; r < rows; r++) for (int i = 0; i < perRow; i++) shade[r][i] = 1f + (float) rng.nextGaussian() * 0.08f;
        float[] grain = TileableNoise.blurWrap(TileableNoise.white(rng, n), n, 1);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < n; y++) {
            int r = Math.min(rows - 1, y / rowH);
            float t = (y - r * rowH) / (float) rowH;
            for (int x = 0; x < n; x++) {
                float xs = ((x - (r % 2) * unitW * 0.5f) % n + n) % n;
                int i = Math.min(perRow - 1, (int) (xs / unitW));
                float u = (xs - i * unitW) / unitW;
                float rowShadow = 0.65f + 0.35f * (float) Math.sin(Math.PI * Math.min(1f, t * 1.15f));
                float side = rounded ? 0.75f + 0.25f * (float) Math.sin(Math.PI * u) : (u < 0.04f || u > 0.96f ? 0.55f : 1f);
                float lum = shade[r][i] * rowShadow * side * (1f + 0.06f * grain[y * n + x] * 1.7f);
                img.setRGB(x, y, rgb(colour.x * lum, colour.y * lum, colour.z * lum));
            }
        }
        return img;
    }

    // ------------------------------------------------------------------------------------
    // Fences
    // ------------------------------------------------------------------------------------

    public static BufferedImage fence(long seed, FenceStyle style) {
        Random rng = WorldPalette.rng(seed, 0xFE4CL);
        int n = FENCE_SIZE;
        Vector3 colour = style.colour;
        switch (style.surface) {
            case STONE: {
                Vector3 mortar = new Vector3(colour.x * 0.55f, colour.y * 0.55f, colour.z * 0.55f);
                return masonry(rng, n, 4, 1.4f, BuildingStyle.Bond.RUNNING, true, 0.12f, colour, mortar, 0.12f);
            }
            case LEAVES: {
                // Clumps of small leaves: thresholded fine noise over broad light and shade
                float[] clumps = TileableNoise.spectral(rng, n, 0.9, 1.0, 0.0, 12);
                float[] leaves = TileableNoise.blurWrap(TileableNoise.white(rng, n), n, 1);
                BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
                for (int i = 0; i < n * n; i++) {
                    float lum = 0.55f + 0.3f * ProceduralTextures.smoothstep(-0.8f, 1.2f, clumps[i]) + 0.35f * leaves[i];
                    img.setRGB(i % n, i / n, rgb(colour.x * lum, colour.y * lum * 1.05f, colour.z * lum));
                }
                return img;
            }
            case PAINTED:
                return render(rng, n, colour, 0.04f, 0.04f, true);
            case WOOD:
            default: {
                // Grain runs along the boards: strongly stretched noise, plus a few dark knots
                float[] grain = TileableNoise.spectral(rng, n, 1.1, 14.0, 0.0, 2);
                float[] fine = TileableNoise.spectral(rng, n, 0.6, 8.0, 0.0, 16);
                float[][] knots = TileableNoise.worley(rng, n, 3);
                BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
                for (int i = 0; i < n * n; i++) {
                    float knot = ProceduralTextures.smoothstep(10f, 3f, knots[0][i]) * 0.35f;
                    float lum = (1f + 0.12f * grain[i] + 0.08f * fine[i]) * (1f - knot);
                    img.setRGB(i % n, i / n, rgb(colour.x * lum, colour.y * lum, colour.z * lum));
                }
                return img;
            }
        }
    }

    private static int rgb(float r, float g, float b) {
        return (to8(r) << 16) | (to8(g) << 8) | to8(b);
    }

    private static int to8(float v) {
        return Math.round(WorldPalette.clamp01(v) * 255f);
    }
}
