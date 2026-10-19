import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Per-nation road marking style: where the lines sit, whether they are single or
 * double, dashed or solid, and what colour they are painted.
 */
public class RoadLineStyle {
    public enum Placement { CENTRE, EDGES }

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

    public final Placement placement;
    public final boolean doubleLine;
    public final boolean dashed;
    public final float dashLength;
    public final float gapLength;
    public final float lineWidth;
    public final float doubleSpacing;
    public final float edgeInset;
    public final Vector3 colour;

    private RoadLineStyle(Placement placement, boolean doubleLine, boolean dashed, float dashLength,
                          float gapLength, float lineWidth, float doubleSpacing, float edgeInset,
                          Vector3 colour) {
        this.placement = placement;
        this.doubleLine = doubleLine;
        this.dashed = dashed;
        this.dashLength = dashLength;
        this.gapLength = gapLength;
        this.lineWidth = lineWidth;
        this.doubleSpacing = doubleSpacing;
        this.edgeInset = edgeInset;
        this.colour = colour;
    }

    /**
     * The whole world paints its roads in just two or three colours, and both the colour
     * and the line layout spread in geographic blocs, so neighbouring nations usually
     * share conventions while distant ones may differ. Dash proportions drift gradually.
     */
    public static Map<Integer, RoadLineStyle> generateForNations(long seed, int numNations, NationKinship kinship) {
        Random rand = new Random(seed + 3131L);

        List<int[]> layouts = new ArrayList<>();
        for (Placement placement : Placement.values()) {
            for (int doubleLine = 0; doubleLine <= 1; doubleLine++) {
                for (int dashed = 0; dashed <= 1; dashed++) {
                    layouts.add(new int[] { placement.ordinal(), doubleLine, dashed });
                }
            }
        }
        Collections.shuffle(layouts, rand);

        List<Integer> colourOrder = new ArrayList<>();
        for (int i = 0; i < LINE_COLOURS.length; i++) {
            colourOrder.add(i);
        }
        Collections.shuffle(colourOrder, rand);
        int worldColourCount = rand.nextFloat() < 0.4f ? 3 : 2;

        // (M54: fewer designs about the map, so they're less of a give-away: usually two layouts
        // rather than three, fewer nations going their own way, and a layout's spacing and
        // inset shared by all who use it; dashes and widths in two steps, not a smooth drift)
        int layoutCount = rand.nextFloat() < 0.3f ? 3 : 2;
        int[] colourBloc = kinship.clusters(rand, worldColourCount, 0.05f);
        int[] layoutBloc = kinship.clusters(rand, layoutCount, 0.07f);
        float[] dashDrift = kinship.gradient(rand, 0.1f);
        float[] widthDrift = kinship.gradient(rand, 0.1f);
        float[] blocSpacing = new float[layoutCount], blocInset = new float[layoutCount];
        for (int b = 0; b < layoutCount; b++) {
            blocSpacing[b] = 0.8f + rand.nextFloat() * 0.7f;
            blocInset[b] = 1.5f + rand.nextFloat() * 2.0f;
        }

        Map<Integer, RoadLineStyle> styles = new HashMap<>();
        for (int n = 1; n <= numNations; n++) {
            int[] layout = layouts.get(layoutBloc[n]);
            float[] rgb = LINE_COLOURS[colourOrder.get(colourBloc[n])];

            float dash = dashDrift[n] < 0.5f ? 0.25f : 0.75f, width = widthDrift[n] < 0.5f ? 0.25f : 0.75f;
            float dashLength = 8.0f + dash * 18.0f;
            float gapLength = 6.0f + (1.0f - dash) * 18.0f;
            float lineWidth = 0.9f + width * 0.7f;
            float doubleSpacing = blocSpacing[layoutBloc[n]];
            float edgeInset = blocInset[layoutBloc[n]];

            styles.put(n, new RoadLineStyle(
                Placement.values()[layout[0]], layout[1] == 1, layout[2] == 1,
                dashLength, gapLength, lineWidth, doubleSpacing, edgeInset,
                new Vector3(rgb[0], rgb[1], rgb[2])
            ));
        }
        return styles;
    }

    /** Lateral offsets of each painted stripe's centre from the road centreline. */
    public float[] stripeOffsets(float roadWidth) {
        float pairOffset = lineWidth + doubleSpacing;
        if (placement == Placement.CENTRE) {
            if (doubleLine) {
                float half = pairOffset * 0.5f;
                return new float[] { -half, half };
            }
            return new float[] { 0.0f };
        }

        float edge = roadWidth * 0.5f - edgeInset - lineWidth * 0.5f;
        if (doubleLine) {
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
        g.setColor(new java.awt.Color(Math.min(1f, colour.x), Math.min(1f, colour.y), Math.min(1f, colour.z)));
        float lw = Math.max(1.2f, lineWidth * scale);
        for (float offset : stripeOffsets(roadWidth)) {
            float x = cx + offset * scale - lw * 0.5f;
            if (!dashed) {
                g.fill(new java.awt.geom.Rectangle2D.Float(x, 0, lw, heightPx));
                continue;
            }
            for (float along = 0f; along < shown; along += dashPeriod()) {
                float y0 = heightPx - along * scale, y1 = heightPx - Math.min(shown, along + dashLength) * scale;
                g.fill(new java.awt.geom.Rectangle2D.Float(x, y1, lw, y0 - y1));
            }
        }
        g.setColor(new java.awt.Color(20, 20, 22, 200));
        g.draw(new java.awt.geom.RoundRectangle2D.Float(cx - half, 0, half * 2f, heightPx - 1, 6, 6));
        g.dispose();
        return image;
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
}
