import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A national flag for every nation, different each world. A flag is a field layout (stripes,
 * a cross, a canton, a triangle at the hoist...), two to four colours and perhaps an emblem.
 * Neighbouring nations tend to share their layout family, colour tradition and emblem, the
 * way flags run in families on Earth, but each is its own.
 *
 * Every design is checked against a catalogue of real Earth flags described in the same
 * terms; one that comes out too close to any of them is changed until it doesn't.
 */
public final class FlagDesigner {

    public static final int WIDTH = 240, HEIGHT = 160;

    enum Layout { PLAIN, HORIZONTAL, VERTICAL, NORDIC_CROSS, CROSS, SALTIRE, DIAGONAL, HOIST_TRIANGLE, CANTON, BORDER, QUARTERS, PALE }
    enum Emblem { NONE, DISC, STAR, RING, CRESCENT, DIAMOND, TRIANGLE, SUNBURST, BARS, CIRCLE_OF_STARS }

    /** One flag: its layout, how many stripes, its colours (field first) and emblem. */
    static final class Spec {
        Layout layout;
        int stripes;
        List<float[]> colours = new ArrayList<>();
        Emblem emblem = Emblem.NONE;
        float[] emblemColour;
        int emblemPoints = 5;
        boolean emblemAtHoist;

        Spec copy() {
            Spec s = new Spec();
            s.layout = layout;
            s.stripes = stripes;
            for (float[] c : colours) s.colours.add(c.clone());
            s.emblem = emblem;
            s.emblemColour = emblemColour == null ? null : emblemColour.clone();
            s.emblemPoints = emblemPoints;
            s.emblemAtHoist = emblemAtHoist;
            return s;
        }
    }

    // ------------------------------------------------------------------ Earth's flags
    // The common Earth colours, then a catalogue of well-known flags in the same terms

    private static final float[] RED = { 0.80f, 0.10f, 0.15f }, WHITE = { 1f, 1f, 1f }, BLUE = { 0.0f, 0.22f, 0.6f },
            LIGHT_BLUE = { 0.35f, 0.65f, 0.9f }, GREEN = { 0.0f, 0.5f, 0.25f }, YELLOW = { 1f, 0.82f, 0.0f },
            BLACK = { 0f, 0f, 0f }, ORANGE = { 1f, 0.5f, 0.0f }, MAROON = { 0.5f, 0.05f, 0.15f };

    private static final List<Spec> EARTH = new ArrayList<>();

    private static void earth(Layout layout, int stripes, Emblem emblem, float[]... colours) {
        Spec s = new Spec();
        s.layout = layout;
        s.stripes = stripes;
        for (float[] c : colours) s.colours.add(c);
        s.emblem = emblem;
        EARTH.add(s);
    }

    static {
        // Tricolours and bicolours
        earth(Layout.VERTICAL, 3, Emblem.NONE, BLUE, WHITE, RED);         // France
        earth(Layout.VERTICAL, 3, Emblem.NONE, GREEN, WHITE, RED);        // Italy
        earth(Layout.VERTICAL, 3, Emblem.NONE, GREEN, WHITE, ORANGE);     // Ireland
        earth(Layout.VERTICAL, 3, Emblem.NONE, BLACK, YELLOW, RED);       // Belgium
        earth(Layout.VERTICAL, 3, Emblem.NONE, BLUE, YELLOW, RED);        // Romania, Chad
        earth(Layout.VERTICAL, 3, Emblem.NONE, GREEN, YELLOW, RED);       // Guinea, Mali
        earth(Layout.VERTICAL, 3, Emblem.NONE, ORANGE, WHITE, GREEN);     // Côte d'Ivoire
        earth(Layout.VERTICAL, 3, Emblem.NONE, RED, WHITE, RED);          // Peru
        earth(Layout.VERTICAL, 3, Emblem.NONE, GREEN, WHITE, GREEN);      // Nigeria
        earth(Layout.VERTICAL, 3, Emblem.STAR, GREEN, RED, GREEN);        // Senegal-like
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, BLACK, RED, YELLOW);     // Germany
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, WHITE, BLUE, RED);       // Russia
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, RED, WHITE, BLUE);       // Netherlands
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, RED, WHITE, RED);        // Austria
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, WHITE, GREEN, RED);      // Bulgaria
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, RED, WHITE, GREEN);      // Hungary
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, YELLOW, GREEN, RED);     // Lithuania
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, BLUE, BLACK, WHITE);     // Estonia
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, RED, YELLOW, GREEN);     // Bolivia
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, GREEN, WHITE, GREEN);
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, RED, WHITE, BLACK);      // Yemen
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, ORANGE, WHITE, GREEN);   // India (wheel aside)
        earth(Layout.HORIZONTAL, 3, Emblem.DISC, ORANGE, WHITE, GREEN);   // India
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, BLUE, YELLOW, RED);
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, YELLOW, BLUE, RED);      // Colombia
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, LIGHT_BLUE, WHITE, LIGHT_BLUE); // Argentina
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, RED, YELLOW, RED);       // Spain
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, BLUE, WHITE, BLUE);
        earth(Layout.HORIZONTAL, 3, Emblem.NONE, GREEN, YELLOW, BLUE);    // Gabon
        earth(Layout.HORIZONTAL, 2, Emblem.NONE, WHITE, RED);             // Poland
        earth(Layout.HORIZONTAL, 2, Emblem.NONE, RED, WHITE);             // Indonesia, Monaco
        earth(Layout.HORIZONTAL, 2, Emblem.NONE, BLUE, YELLOW);           // Ukraine
        earth(Layout.HORIZONTAL, 2, Emblem.NONE, WHITE, BLUE);
        earth(Layout.HORIZONTAL, 2, Emblem.NONE, YELLOW, RED);
        earth(Layout.VERTICAL, 2, Emblem.NONE, GREEN, RED);               // Portugal
        earth(Layout.VERTICAL, 2, Emblem.NONE, WHITE, YELLOW);            // Vatican
        earth(Layout.HORIZONTAL, 5, Emblem.NONE, BLUE, WHITE, BLUE, WHITE, BLUE);
        earth(Layout.HORIZONTAL, 5, Emblem.NONE, GREEN, WHITE, GREEN, WHITE, GREEN);
        earth(Layout.HORIZONTAL, 5, Emblem.NONE, RED, WHITE, RED, WHITE, RED);
        // Crosses
        earth(Layout.NORDIC_CROSS, 0, Emblem.NONE, RED, WHITE);           // Denmark
        earth(Layout.NORDIC_CROSS, 0, Emblem.NONE, BLUE, YELLOW);         // Sweden
        earth(Layout.NORDIC_CROSS, 0, Emblem.NONE, WHITE, BLUE);          // Finland
        earth(Layout.NORDIC_CROSS, 0, Emblem.NONE, RED, BLUE);            // Norway
        earth(Layout.NORDIC_CROSS, 0, Emblem.NONE, BLUE, RED);            // Iceland
        earth(Layout.CROSS, 0, Emblem.NONE, RED, WHITE);                  // Switzerland, Georgia
        earth(Layout.CROSS, 0, Emblem.NONE, WHITE, RED);                  // England
        earth(Layout.CROSS, 0, Emblem.NONE, BLUE, WHITE);
        earth(Layout.SALTIRE, 0, Emblem.NONE, BLUE, WHITE);               // Scotland
        earth(Layout.SALTIRE, 0, Emblem.NONE, WHITE, RED);                // Northern Ireland, Alabama-like
        earth(Layout.SALTIRE, 0, Emblem.NONE, GREEN, YELLOW);             // Jamaica-like
        earth(Layout.SALTIRE, 0, Emblem.NONE, WHITE, BLUE);               // Russian naval ensign
        earth(Layout.SALTIRE, 0, Emblem.NONE, RED, WHITE);                // Burgundy-style
        earth(Layout.HORIZONTAL, 3, Emblem.STAR, WHITE, BLUE, WHITE);     // Israel-like
        earth(Layout.HORIZONTAL, 2, Emblem.NONE, WHITE, GREEN);
        earth(Layout.HORIZONTAL, 2, Emblem.NONE, RED, GREEN);
        earth(Layout.VERTICAL, 3, Emblem.NONE, RED, YELLOW, GREEN);
        earth(Layout.VERTICAL, 3, Emblem.NONE, BLACK, YELLOW, BLACK);
        earth(Layout.CROSS, 0, Emblem.NONE, GREEN, WHITE);
        earth(Layout.NORDIC_CROSS, 0, Emblem.NONE, GREEN, WHITE);
        earth(Layout.PLAIN, 0, Emblem.NONE, BLUE);
        earth(Layout.PLAIN, 0, Emblem.STAR, BLUE, WHITE);
        earth(Layout.PLAIN, 0, Emblem.DISC, BLUE, WHITE);
        // Plain fields with emblems
        earth(Layout.PLAIN, 0, Emblem.DISC, WHITE, RED);                  // Japan
        earth(Layout.PLAIN, 0, Emblem.DISC, GREEN, RED);                  // Bangladesh
        earth(Layout.PLAIN, 0, Emblem.DISC, LIGHT_BLUE, YELLOW);          // Palau
        earth(Layout.PLAIN, 0, Emblem.STAR, RED, YELLOW);                 // Vietnam
        earth(Layout.PLAIN, 0, Emblem.STAR, RED, GREEN);                  // Morocco
        earth(Layout.PLAIN, 0, Emblem.STAR, LIGHT_BLUE, WHITE);           // Somalia
        earth(Layout.PLAIN, 0, Emblem.CRESCENT, RED, WHITE);              // Turkey
        earth(Layout.PLAIN, 0, Emblem.CRESCENT, GREEN, WHITE);
        earth(Layout.PLAIN, 0, Emblem.CIRCLE_OF_STARS, BLUE, YELLOW);     // European Union
        earth(Layout.PLAIN, 0, Emblem.NONE, RED);
        earth(Layout.PLAIN, 0, Emblem.NONE, GREEN);
        earth(Layout.PLAIN, 0, Emblem.NONE, WHITE);
        // Triangles at the hoist, cantons, diagonals, borders
        earth(Layout.HOIST_TRIANGLE, 3, Emblem.NONE, BLUE, WHITE, RED, WHITE);      // Czechia, Philippines
        earth(Layout.HOIST_TRIANGLE, 3, Emblem.STAR, BLUE, WHITE, RED, WHITE);      // Cuba, Puerto Rico
        earth(Layout.HOIST_TRIANGLE, 3, Emblem.NONE, RED, BLACK, WHITE, GREEN);     // Palestine, Jordan, Sudan
        earth(Layout.HOIST_TRIANGLE, 2, Emblem.STAR, RED, BLUE, WHITE);
        earth(Layout.CANTON, 13, Emblem.CIRCLE_OF_STARS, RED, WHITE, BLUE, WHITE);  // United States
        earth(Layout.CANTON, 0, Emblem.STAR, RED, BLUE, WHITE);                     // Taiwan-like
        earth(Layout.CANTON, 0, Emblem.STAR, RED, YELLOW);                          // China
        earth(Layout.CANTON, 0, Emblem.CIRCLE_OF_STARS, BLUE, WHITE);               // Australia, NZ-like
        earth(Layout.DIAGONAL, 0, Emblem.NONE, GREEN, YELLOW, BLACK);               // Tanzania-like
        earth(Layout.DIAGONAL, 0, Emblem.NONE, YELLOW, GREEN, RED);
        earth(Layout.DIAGONAL, 0, Emblem.STAR, BLUE, RED, WHITE);
        earth(Layout.BORDER, 0, Emblem.NONE, RED, YELLOW);
        earth(Layout.PALE, 3, Emblem.NONE, RED, WHITE, RED);                        // Canada
        earth(Layout.PALE, 3, Emblem.NONE, MAROON, WHITE, MAROON);
        earth(Layout.QUARTERS, 0, Emblem.NONE, RED, WHITE, WHITE, RED);
    }

    /** The colours that actually show, in order, with the emblem's last (as the catalogue lists them). */
    private static List<float[]> visible(Spec spec, boolean catalogue) {
        if (catalogue) return spec.colours;
        List<float[]> out = new ArrayList<>();
        List<float[]> c = spec.colours;
        switch (spec.layout) {
            case PLAIN -> out.add(c.get(0));
            case HORIZONTAL, VERTICAL -> { for (int i = 0; i < spec.stripes; i++) out.add(c.get(i % c.size())); }
            case HOIST_TRIANGLE -> {
                out.add(c.get(0));
                for (int i = 0; i < Math.max(1, spec.stripes); i++) out.add(c.get((i + 1) % c.size()));
            }
            case PALE -> { out.add(c.get(0)); out.add(c.get(1 % c.size())); out.add(c.get(0)); }
            case DIAGONAL -> { for (int i = 0; i < Math.min(3, c.size()); i++) out.add(c.get(i)); }
            case QUARTERS -> { out.add(c.get(0)); out.add(c.get(1 % c.size())); out.add(c.get(1 % c.size())); out.add(c.get(0)); }
            default -> { out.add(c.get(0)); out.add(c.get(1 % c.size())); }
        }
        if (spec.emblem != Emblem.NONE && spec.emblemColour != null) out.add(spec.emblemColour);
        return out;
    }

    /**
     * Whether two flags would look like the same flag at a glance. On a plain field the emblem
     * is the flag, so it has to match too; on any other layout the field pattern is what the
     * eye takes in, so matching fields are too close even if one adds a small emblem.
     */
    private static boolean tooAlike(Spec a, boolean aCatalogue, Spec b, boolean bCatalogue) {
        if (a.layout != b.layout) return false;
        boolean plain = a.layout == Layout.PLAIN;
        if (plain) {
            if ((a.emblem == Emblem.NONE) != (b.emblem == Emblem.NONE)) return false;
            if (a.emblem != Emblem.NONE && a.emblem != b.emblem) return false;
        }
        List<float[]> ca = visible(a, aCatalogue), cb = visible(b, bCatalogue);
        if (!plain) {
            // Fields only: drop the emblem colour each may carry at the end
            if (a.emblem != Emblem.NONE && ca.size() > 1) ca = ca.subList(0, ca.size() - 1);
            if (b.emblem != Emblem.NONE && cb.size() > 1) cb = cb.subList(0, cb.size() - 1);
        }
        if (ca.size() != cb.size()) return false;
        for (int i = 0; i < ca.size(); i++) {
            if (!sameColourFamily(ca.get(i), cb.get(i))) return false;
        }
        return true;
    }

    /**
     * Whether two colours would be called the same colour on a flag: whites, greys and blacks
     * by how light they are, everything else by hue (a light and a dark blue are both blue).
     */
    private static boolean sameColourFamily(float[] a, float[] b) {
        float[] ha = WorldPalette.toHsv(a), hb = WorldPalette.toHsv(b);
        boolean greyA = ha[1] < 0.22f || ha[2] < 0.15f, greyB = hb[1] < 0.22f || hb[2] < 0.15f;
        if (greyA || greyB) return greyA && greyB && Math.abs(ha[2] - hb[2]) < 0.35f;
        float dh = Math.abs(ha[0] - hb[0]);
        dh = Math.min(dh, 1f - dh);
        return dh < 0.07f && Math.abs(ha[2] - hb[2]) < 0.45f;
    }

    private static float colourDistance(float[] a, float[] b) {
        float dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2];
        return (float) Math.sqrt(dr * dr * 0.9f + dg * dg * 1.2f + db * db * 0.9f);
    }

    /** Whether a design is too close to any flag of Earth, as drawn or flipped end to end. */
    static boolean resemblesEarth(Spec spec) {
        Spec flipped = spec.copy();
        if (spec.layout == Layout.HORIZONTAL || spec.layout == Layout.VERTICAL) {
            List<float[]> stripes = new ArrayList<>();
            for (int i = 0; i < spec.stripes; i++) stripes.add(spec.colours.get(i % spec.colours.size()));
            java.util.Collections.reverse(stripes);
            flipped.colours = stripes;
        }
        for (Spec earth : EARTH) {
            if (tooAlike(spec, false, earth, true) || tooAlike(flipped, false, earth, true)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ designing

    /** Designs every nation's flag. */
    public static Map<Integer, Spec> design(long seed, int nations, NationKinship kinship) {
        Random rng = new Random(seed * 97L + 13L);
        Layout[] families = Layout.values();
        // A culture's nations (see NationKinship.assignCultures) share a flag tradition: its
        // layout, colours and emblem, now and then set aside by a nation of its own
        int[] layoutGroup = new int[nations + 1], paletteGroup = new int[nations + 1], emblemGroup = new int[nations + 1];
        for (int n = 1; n <= nations; n++) layoutGroup[n] = paletteGroup[n] = emblemGroup[n] = kinship.cultureOf(n);
        // Each group's tradition
        Layout[] groupLayout = new Layout[nations + 1];
        List<List<float[]>> groupPalette = new ArrayList<>();
        Emblem[] groupEmblem = new Emblem[nations + 1];
        for (int g = 0; g <= nations; g++) {
            groupLayout[g] = families[rng.nextInt(families.length)];
            groupPalette.add(palette(rng));
            Emblem[] emblems = Emblem.values();
            groupEmblem[g] = rng.nextFloat() < 0.4f ? Emblem.NONE : emblems[1 + rng.nextInt(emblems.length - 1)];
        }
        Map<Integer, Spec> flags = new HashMap<>();
        List<Spec> made = new ArrayList<>();
        List<float[]> drawn = new ArrayList<>();
        for (int n = 1; n <= nations; n++) {
            Random own = new Random(seed * 131L + n * 7919L);
            Spec spec = new Spec();
            spec.layout = own.nextFloat() < 0.75f ? groupLayout[layoutGroup[n]] : families[own.nextInt(families.length)];
            spec.stripes = stripesFor(spec.layout, own);
            List<float[]> tradition = groupPalette.get(paletteGroup[n]);
            int colourCount = Math.max(2, Math.min(4, spec.stripes + 1));
            List<float[]> pool = new ArrayList<>(tradition);
            java.util.Collections.shuffle(pool, own);
            for (int i = 0; i < colourCount; i++) {
                float[] c = pool.get(i % pool.size()).clone();
                // A nation's own shade of its tradition's colours
                for (int k = 0; k < 3; k++) c[k] = Math.max(0f, Math.min(1f, c[k] + (own.nextFloat() - 0.5f) * 0.12f));
                spec.colours.add(c);
            }
            // Stripes that repeat colours alternate, as on many flags
            if (spec.layout == Layout.HORIZONTAL && spec.stripes > 3) {
                List<float[]> alternating = new ArrayList<>();
                for (int i = 0; i < spec.stripes; i++) alternating.add(spec.colours.get(i % 2));
                spec.colours = alternating;
            }
            spec.emblem = own.nextFloat() < 0.7f ? groupEmblem[emblemGroup[n]] : Emblem.values()[own.nextInt(Emblem.values().length)];
            spec.emblemColour = pool.get((colourCount) % pool.size()).clone();
            if (colourDistance(spec.emblemColour, spec.colours.get(0)) < 0.3f) spec.emblemColour = contrast(spec.colours.get(0));
            spec.emblemPoints = 4 + own.nextInt(5);
            spec.emblemAtHoist = own.nextFloat() < 0.4f;

            // Not a copy of Earth, nor of another nation's (by description or as drawn: a
            // culture's flags may be alike, but never the same); should small changes not do,
            // a layout of its own
            int guard = 0;
            while (resemblesEarth(spec) || copiesAny(spec, made) || looksLikeAny(spec, drawn)) {
                if (++guard > 400) break;
                if (guard % 25 == 0) {
                    spec.layout = families[own.nextInt(families.length)];
                    spec.stripes = stripesFor(spec.layout, own);
                    while (spec.colours.size() < Math.max(2, spec.stripes)) spec.colours.add(spec.colours.get(spec.colours.size() % 2).clone());
                }
                mutate(spec, own);
            }
            made.add(spec);
            drawn.add(thumbnail(spec));
            flags.put(n, spec);
        }
        return flags;
    }

    private static int stripesFor(Layout layout, Random rng) {
        return switch (layout) {
            case HORIZONTAL -> 2 + rng.nextInt(4);
            case VERTICAL -> 2 + rng.nextInt(2);
            case HOIST_TRIANGLE -> 1 + rng.nextInt(3);
            case PALE -> 3;
            default -> 0;
        };
    }

    // A flag drawn small, for telling at a glance whether two look the same: so many across and down
    private static final int THUMB_W = 24, THUMB_H = 16;
    // How different two flags must look, on average over their thumbnails (each colour 0 to 1)
    private static final float LOOKS_DIFFERENT = 0.1f;

    /** A flag as drawn, shrunk to THUMB_W by THUMB_H, as red, green, blue for each spot in turn. */
    private static float[] thumbnail(Spec spec) {
        BufferedImage image = render(spec);
        float[] out = new float[THUMB_W * THUMB_H * 3];
        for (int y = 0; y < THUMB_H; y++) {
            for (int x = 0; x < THUMB_W; x++) {
                int rgb = image.getRGB((x * 2 + 1) * image.getWidth() / (THUMB_W * 2), (y * 2 + 1) * image.getHeight() / (THUMB_H * 2));
                int i = (y * THUMB_W + x) * 3;
                out[i] = ((rgb >> 16) & 0xFF) / 255f;
                out[i + 1] = ((rgb >> 8) & 0xFF) / 255f;
                out[i + 2] = (rgb & 0xFF) / 255f;
            }
        }
        return out;
    }

    /** Whether a flag, as drawn, would look the same as any of these thumbnails. */
    private static boolean looksLikeAny(Spec spec, List<float[]> drawn) {
        if (drawn.isEmpty()) return false;
        float[] mine = thumbnail(spec);
        for (float[] other : drawn) {
            float difference = 0f;
            for (int i = 0; i < mine.length; i++) difference += Math.abs(mine[i] - other[i]);
            if (difference / mine.length < LOOKS_DIFFERENT) return true;
        }
        return false;
    }

    private static boolean copiesAny(Spec spec, List<Spec> others) {
        for (Spec o : others) {
            if (tooAlike(spec, false, o, false) && spec.emblemAtHoist == o.emblemAtHoist) return true;
        }
        return false;
    }

    /** One small change: an emblem added or swapped, a colour shifted, stripes reordered. */
    private static void mutate(Spec spec, Random rng) {
        switch (rng.nextInt(4)) {
            case 0 -> {
                Emblem[] emblems = Emblem.values();
                spec.emblem = emblems[1 + rng.nextInt(emblems.length - 1)];
                spec.emblemAtHoist = rng.nextBoolean();
            }
            case 1 -> {
                int i = rng.nextInt(spec.colours.size());
                float[] hsv = WorldPalette.toHsv(spec.colours.get(i));
                spec.colours.set(i, WorldPalette.hsv(hsv[0] + 0.25f + rng.nextFloat() * 0.5f, 0.35f + rng.nextFloat() * 0.6f, 0.3f + rng.nextFloat() * 0.65f));
            }
            case 2 -> java.util.Collections.rotate(spec.colours, 1);
            default -> spec.emblemPoints = 4 + rng.nextInt(6);
        }
        if (spec.emblemColour == null || colourDistance(spec.emblemColour, spec.colours.get(0)) < 0.3f) spec.emblemColour = contrast(spec.colours.get(0));
    }

    /** A colour tradition: a few hues that sit well together, some bold, some pale or dark. */
    private static List<float[]> palette(Random rng) {
        List<float[]> colours = new ArrayList<>();
        float base = rng.nextFloat();
        int n = 4 + rng.nextInt(2);
        for (int i = 0; i < n; i++) {
            float roll = rng.nextFloat();
            if (roll < 0.22f) {
                colours.add(new float[] { 0.96f, 0.96f, 0.93f });          // white
            } else if (roll < 0.3f) {
                colours.add(new float[] { 0.07f, 0.07f, 0.09f });          // black
            } else if (roll < 0.4f) {
                colours.add(WorldPalette.hsv(0.12f + rng.nextFloat() * 0.05f, 0.85f, 0.97f));   // gold
            } else {
                // Bold dyes: strong colour, clear of muddy browns
                float hue = base + (rng.nextFloat() < 0.5f ? 0f : 0.5f) + (rng.nextFloat() - 0.5f) * 0.3f;
                colours.add(WorldPalette.hsv(hue, 0.65f + rng.nextFloat() * 0.35f, 0.5f + rng.nextFloat() * 0.45f));
            }
        }
        return colours;
    }

    private static float[] contrast(float[] c) {
        float luma = c[0] * 0.3f + c[1] * 0.59f + c[2] * 0.11f;
        return luma > 0.5f ? new float[] { 0.1f, 0.1f, 0.12f } : new float[] { 0.97f, 0.95f, 0.85f };
    }

    // ------------------------------------------------------------------ drawing

    public static BufferedImage render(Spec spec) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = WIDTH, h = HEIGHT;
        List<float[]> c = spec.colours;
        Color field = colour(c.get(0));
        Color second = colour(c.get(Math.min(1, c.size() - 1)));
        g.setColor(field);
        g.fillRect(0, 0, w, h);
        float[] emblemCentre = { w * 0.5f, h * 0.5f };
        float emblemSize = h * 0.42f;
        switch (spec.layout) {
            case HORIZONTAL -> {
                for (int i = 0; i < spec.stripes; i++) {
                    g.setColor(colour(c.get(i % c.size())));
                    g.fillRect(0, i * h / spec.stripes, w, h / spec.stripes + 1);
                }
            }
            case VERTICAL -> {
                for (int i = 0; i < spec.stripes; i++) {
                    g.setColor(colour(c.get(i % c.size())));
                    g.fillRect(i * w / spec.stripes, 0, w / spec.stripes + 1, h);
                }
            }
            case PALE -> {
                g.setColor(field);
                g.fillRect(0, 0, w, h);
                g.setColor(second);
                g.fillRect(w / 4, 0, w / 2, h);
            }
            case NORDIC_CROSS -> {
                g.setColor(second);
                g.fillRect(w * 5 / 16, 0, w / 8, h);
                g.fillRect(0, h * 7 / 16, w, h / 8);
                emblemCentre = new float[] { w * 0.17f, h * 0.22f };
                emblemSize = h * 0.25f;
            }
            case CROSS -> {
                g.setColor(second);
                g.fillRect(w * 7 / 16, 0, w / 8, h);
                g.fillRect(0, h * 7 / 16, w, h / 8);
                emblemSize = h * 0.2f;
            }
            case SALTIRE -> {
                g.setColor(second);
                g.setStroke(new BasicStroke(h * 0.16f));
                g.drawLine(0, 0, w, h);
                g.drawLine(0, h, w, 0);
                emblemSize = h * 0.24f;
            }
            case DIAGONAL -> {
                g.setColor(second);
                g.fillPolygon(new Polygon(new int[] { w, w, 0 }, new int[] { 0, h, h }, 3));
                if (c.size() > 2) {
                    g.setColor(colour(c.get(2)));
                    g.setStroke(new BasicStroke(h * 0.12f));
                    g.drawLine(0, h, w, 0);
                }
                emblemCentre = new float[] { w * 0.22f, h * 0.28f };
                emblemSize = h * 0.3f;
            }
            case HOIST_TRIANGLE -> {
                int stripes = Math.max(1, spec.stripes);
                for (int i = 0; i < stripes; i++) {
                    g.setColor(colour(c.get((i + 1) % c.size())));
                    g.fillRect(0, i * h / stripes, w, h / stripes + 1);
                }
                g.setColor(field);
                g.fillPolygon(new Polygon(new int[] { 0, w * 2 / 5, 0 }, new int[] { 0, h / 2, h }, 3));
                emblemCentre = new float[] { w * 0.13f, h * 0.5f };
                emblemSize = h * 0.26f;
            }
            case CANTON -> {
                g.setColor(second);
                g.fillRect(0, 0, w * 9 / 20, h * 9 / 17);
                emblemCentre = new float[] { w * 0.225f, h * 0.265f };
                emblemSize = h * 0.3f;
            }
            case BORDER -> {
                g.setColor(second);
                int b = h / 9;
                g.fillRect(0, 0, w, b);
                g.fillRect(0, h - b, w, b);
                g.fillRect(0, 0, b, h);
                g.fillRect(w - b, 0, b, h);
            }
            case QUARTERS -> {
                g.setColor(second);
                g.fillRect(w / 2, 0, w - w / 2, h / 2);
                g.fillRect(0, h / 2, w / 2, h - h / 2);
                emblemSize = h * 0.3f;
            }
            default -> { }
        }
        if (spec.emblemAtHoist && (spec.layout == Layout.PLAIN || spec.layout == Layout.HORIZONTAL || spec.layout == Layout.BORDER)) {
            emblemCentre = new float[] { w * 0.25f, h * 0.5f };
        }
        drawEmblem(g, spec, emblemCentre[0], emblemCentre[1], emblemSize);
        g.dispose();
        return img;
    }

    private static void drawEmblem(Graphics2D g, Spec spec, float cx, float cy, float size) {
        if (spec.emblem == Emblem.NONE || spec.emblemColour == null) return;
        g.setColor(colour(spec.emblemColour));
        float r = size * 0.5f;
        switch (spec.emblem) {
            case DISC -> g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
            case RING -> {
                g.setStroke(new BasicStroke(r * 0.28f));
                g.draw(new Ellipse2D.Float(cx - r * 0.85f, cy - r * 0.85f, r * 1.7f, r * 1.7f));
            }
            case STAR -> g.fill(star(cx, cy, r, r * 0.42f, spec.emblemPoints));
            case CRESCENT -> {
                java.awt.geom.Area moon = new java.awt.geom.Area(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
                moon.subtract(new java.awt.geom.Area(new Ellipse2D.Float(cx - r * 0.55f, cy - r * 0.85f, r * 1.7f, r * 1.7f)));
                g.fill(moon);
            }
            case DIAMOND -> g.fillPolygon(new Polygon(new int[] { (int) cx, (int) (cx + r * 0.7f), (int) cx, (int) (cx - r * 0.7f) },
                    new int[] { (int) (cy - r), (int) cy, (int) (cy + r), (int) cy }, 4));
            case TRIANGLE -> g.fillPolygon(new Polygon(new int[] { (int) cx, (int) (cx + r * 0.9f), (int) (cx - r * 0.9f) },
                    new int[] { (int) (cy - r * 0.9f), (int) (cy + r * 0.7f), (int) (cy + r * 0.7f) }, 3));
            case SUNBURST -> {
                g.fill(new Ellipse2D.Float(cx - r * 0.45f, cy - r * 0.45f, r * 0.9f, r * 0.9f));
                g.setStroke(new BasicStroke(r * 0.1f));
                int rays = spec.emblemPoints * 2;
                for (int i = 0; i < rays; i++) {
                    double a = i * Math.PI * 2 / rays;
                    g.drawLine((int) (cx + Math.cos(a) * r * 0.6f), (int) (cy + Math.sin(a) * r * 0.6f),
                            (int) (cx + Math.cos(a) * r), (int) (cy + Math.sin(a) * r));
                }
            }
            case BARS -> {
                int bars = 3;
                for (int i = 0; i < bars; i++) {
                    g.fillRect((int) (cx - r * 0.8f + i * r * 0.6f), (int) (cy - r * 0.8f), (int) (r * 0.35f), (int) (r * 1.6f));
                }
            }
            case CIRCLE_OF_STARS -> {
                int stars = spec.emblemPoints + 3;
                for (int i = 0; i < stars; i++) {
                    double a = i * Math.PI * 2 / stars;
                    g.fill(star(cx + (float) Math.cos(a) * r * 0.75f, cy + (float) Math.sin(a) * r * 0.75f, r * 0.2f, r * 0.08f, 5));
                }
            }
            default -> { }
        }
    }

    private static Path2D star(float cx, float cy, float outer, float inner, int points) {
        Path2D path = new Path2D.Float();
        for (int i = 0; i < points * 2; i++) {
            double a = -Math.PI / 2 + i * Math.PI / points;
            float rr = i % 2 == 0 ? outer : inner;
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.closePath();
        return path;
    }

    private static Color colour(float[] c) {
        return new Color(Math.max(0f, Math.min(1f, c[0])), Math.max(0f, Math.min(1f, c[1])), Math.max(0f, Math.min(1f, c[2])));
    }
}
