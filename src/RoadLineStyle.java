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
     * Deals out styles so that the first nations all get a different
     * placement/double/dashed layout and a different colour; once the layouts run
     * out they repeat, but paired with a different colour.
     */
    public static Map<Integer, RoadLineStyle> generateForNations(long seed, int numNations) {
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

        Map<Integer, RoadLineStyle> styles = new HashMap<>();
        for (int n = 1; n <= numNations; n++) {
            int slot = n - 1;
            int[] layout = layouts.get(slot % layouts.size());
            int colourSlot = (slot + slot / layouts.size()) % colourOrder.size();
            float[] rgb = LINE_COLOURS[colourOrder.get(colourSlot)];

            float dashLength = 8.0f + rand.nextFloat() * 18.0f;
            float gapLength = 6.0f + rand.nextFloat() * 18.0f;
            float lineWidth = 0.9f + rand.nextFloat() * 0.7f;
            float doubleSpacing = 0.8f + rand.nextFloat() * 0.7f;
            float edgeInset = 1.5f + rand.nextFloat() * 2.0f;

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
