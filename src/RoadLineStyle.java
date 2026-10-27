import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import com.xenoguesser.math.Vector3;

/**
 * Per-nation road marking style: an inner line down the middle of the road and outer lines
 * along its edges, either, both or neither, each single or double, dashed or solid and painted
 * its own colour. The map's road lines pictures are drawn from the same stripes as the roads.
 */
public class RoadLineStyle {

    private static final float[][] LINE_COLOURS = {
        {0.95f, 0.95f, 0.92f}, // White
        {0.95f, 0.80f, 0.15f}, // Yellow
        {0.95f, 0.50f, 0.10f}, // Orange
        {0.20f, 0.85f, 0.90f}, // Cyan
        {0.85f, 0.15f, 0.15f}, // Red
        {0.55f, 0.90f, 0.20f}, // Lime
        {0.85f, 0.30f, 0.80f}, // Magenta
        {0.45f, 0.55f, 0.95f}  // Periwinkle
    };

    /** The paint an unmarked style still uses for its give-way lines and crossovers. */
    private static final Vector3 PLAIN_PAINT = new Vector3(0.95f, 0.95f, 0.92f);

    /** One set of lines (the inner or the outer): its paint and how it is laid. */
    public record Line(int colourIndex, boolean doubled, boolean dashed, float dashLength, float gapLength) {
        public Vector3 colour() {
            float[] rgb = LINE_COLOURS[colourIndex];
            return new Vector3(rgb[0], rgb[1], rgb[2]);
        }

        public float dashPeriod() {
            return dashLength + gapLength;
        }

        /** Whether paint is present at the given distance along the road. */
        public boolean isPaintedAt(float distanceAlongRoad) {
            if (!dashed) {
                return true;
            }
            float period = dashPeriod();
            float phase = distanceAlongRoad % period;
            if (phase < 0.0f) {
                phase += period;
            }
            return phase < dashLength;
        }

        /** What a player sees of it: its paint, doubling and dashing. */
        String look() {
            return colourIndex + (doubled ? "d" : "s") + (dashed ? "-" : "|");
        }
    }

    /** The line down the middle, or null for none. */
    public final Line inner;
    /** The lines along both edges, or null for none. */
    public final Line outer;
    public final float lineWidth;
    public final float doubleSpacing;
    public final float edgeInset;

    private RoadLineStyle(Line inner, Line outer, float lineWidth, float doubleSpacing, float edgeInset) {
        this.inner = inner;
        this.outer = outer;
        this.lineWidth = lineWidth;
        this.doubleSpacing = doubleSpacing;
        this.edgeInset = edgeInset;
    }

    /**
     * The whole world marks its roads in only about four ways (three to five), each a complete
     * look, and the looks spread in blocs of kindred nations, so neighbours usually share their
     * markings while distant nations may not; a few nations go their own way. The paint comes
     * from the two or three colours the world favours.
     */
    public static Map<Integer, RoadLineStyle> generateForNations(long seed, int numNations, NationKinship kinship) {
        Random rand = new Random(seed + 3131L);

        List<Integer> colourOrder = new ArrayList<>();
        for (int i = 0; i < LINE_COLOURS.length; i++) {
            colourOrder.add(i);
        }
        Collections.shuffle(colourOrder, rand);
        int worldColourCount = rand.nextFloat() < 0.4f ? 3 : 2;

        int variantCount = Math.max(1, Math.min(numNations, 3 + rand.nextInt(3)));
        List<RoadLineStyle> variants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean unmarkedTaken = false;
        for (int attempt = 0; variants.size() < variantCount && attempt < 200; attempt++) {
            RoadLineStyle style = randomStyle(rand, colourOrder, worldColourCount);
            // (no two looks alike, and at most one bloc leaves its roads bare)
            boolean unmarked = style.inner == null && style.outer == null;
            if ((unmarked && unmarkedTaken) || !seen.add(style.look())) continue;
            unmarkedTaken |= unmarked;
            variants.add(style);
        }

        int[] bloc = kinship.clusters(rand, variants.size(), 0.05f);
        Map<Integer, RoadLineStyle> styles = new HashMap<>();
        for (int n = 1; n <= numNations; n++) {
            styles.put(n, variants.get(bloc[n]));
        }
        return styles;
    }

    /** One look: which lines it has, and each one's paint, doubling and dashes. */
    private static RoadLineStyle randomStyle(Random rand, List<Integer> colourOrder, int worldColourCount) {
        float lineWidth = 0.9f + (rand.nextBoolean() ? 0.25f : 0.75f) * 0.7f;
        float doubleSpacing = 0.8f + rand.nextFloat() * 0.7f;
        float edgeInset = 1.5f + rand.nextFloat() * 2.0f;
        float presence = rand.nextFloat();
        boolean hasInner = presence < 0.75f, hasOuter = presence >= 0.4f && presence < 0.95f;
        Line inner = hasInner ? randomLine(rand, colourOrder.get(rand.nextInt(worldColourCount)), 0.4f, 0.6f) : null;
        // (the edges often, but not always, in the middle's paint)
        int outerColour = inner != null && rand.nextFloat() < 0.5f
                ? inner.colourIndex() : colourOrder.get(rand.nextInt(worldColourCount));
        Line outer = hasOuter ? randomLine(rand, outerColour, 0.15f, 0.3f) : null;
        return new RoadLineStyle(inner, outer, lineWidth, doubleSpacing, edgeInset);
    }

    private static Line randomLine(Random rand, int colour, float doubledChance, float dashedChance) {
        boolean doubled = rand.nextFloat() < doubledChance, dashed = rand.nextFloat() < dashedChance;
        float dash = rand.nextBoolean() ? 0.25f : 0.75f;
        return new Line(colour, doubled, dashed, 8.0f + dash * 18.0f, 6.0f + (1.0f - dash) * 18.0f);
    }

    /** What a player would see of this style: which lines, their paint, doubling and dashing. */
    private String look() {
        return (inner == null ? "-" : inner.look()) + "/" + (outer == null ? "-" : outer.look());
    }

    /** The inner line's paint, else the outer's: what give-way lines and crossovers are painted in. */
    public Vector3 mainColour() {
        return inner != null ? inner.colour() : outer != null ? outer.colour() : PLAIN_PAINT;
    }

    /** The outer lines' paint (the main paint when there are none). */
    public Vector3 outerColour() {
        return outer != null ? outer.colour() : mainColour();
    }

    /** Whether give-way lines are dashed: as the inner line is, else the outer. */
    public boolean dashedGiveWay() {
        Line line = inner != null ? inner : outer;
        return line != null && line.dashed();
    }

    /** Lateral offsets of the inner line's stripes from the road centreline (none without one). */
    public float[] innerOffsets() {
        if (inner == null) return new float[0];
        if (inner.doubled()) {
            float half = (lineWidth + doubleSpacing) * 0.5f;
            return new float[] { -half, half };
        }
        return new float[] { 0.0f };
    }

    /** Lateral offsets of the outer lines' stripes from the road centreline (none without them). */
    public float[] outerOffsets(float roadWidth) {
        if (outer == null) return new float[0];
        float edge = roadWidth * 0.5f - edgeInset - lineWidth * 0.5f;
        if (outer.doubled()) {
            float pairOffset = lineWidth + doubleSpacing;
            return new float[] { -edge, -(edge - pairOffset), edge - pairOffset, edge };
        }
        return new float[] { -edge, edge };
    }

    /**
     * A picture of a stretch of road marked in this style, seen from above (the road running up
     * the picture), height pixels tall, a road roadWidth wide (world units) shown about three
     * times its width long; on a clear background.
     */
    public java.awt.image.BufferedImage picture(int heightPx, float roadWidth) {
        float shown = roadWidth * 3f, scale = heightPx / shown;
        int width = Math.round(roadWidth * scale + 6);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, heightPx, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        float cx = width * 0.5f, half = roadWidth * 0.5f * scale;
        g.setColor(new java.awt.Color(48, 48, 52));
        g.fill(new java.awt.geom.RoundRectangle2D.Float(cx - half, 0, half * 2f, heightPx, 6, 6));
        float lw = Math.max(1.2f, lineWidth * scale);
        drawStripes(g, inner, innerOffsets(), cx, scale, lw, shown, heightPx);
        drawStripes(g, outer, outerOffsets(roadWidth), cx, scale, lw, shown, heightPx);
        g.setColor(new java.awt.Color(20, 20, 22, 200));
        g.draw(new java.awt.geom.RoundRectangle2D.Float(cx - half, 0, half * 2f, heightPx - 1, 6, 6));
        g.dispose();
        return image;
    }

    /** One line's stripes in the picture, each at its offset from the middle. */
    private static void drawStripes(java.awt.Graphics2D g, Line line, float[] offsets, float cx, float scale, float lw,
                                    float shown, int heightPx) {
        if (line == null) return;
        Vector3 c = line.colour();
        g.setColor(new java.awt.Color(Math.min(1f, c.x), Math.min(1f, c.y), Math.min(1f, c.z)));
        for (float offset : offsets) {
            float x = cx + offset * scale - lw * 0.5f;
            if (!line.dashed()) {
                g.fill(new java.awt.geom.Rectangle2D.Float(x, 0, lw, heightPx));
                continue;
            }
            for (float along = 0f; along < shown; along += line.dashPeriod()) {
                float y0 = heightPx - along * scale, y1 = heightPx - Math.min(shown, along + line.dashLength()) * scale;
                g.fill(new java.awt.geom.Rectangle2D.Float(x, y1, lw, y0 - y1));
            }
        }
    }
}
