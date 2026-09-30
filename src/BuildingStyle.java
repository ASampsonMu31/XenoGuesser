import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Per-nation building appearance: proportions, roof design and colours.
 * Width runs along the building's local X axis (the ridge direction) and depth
 * along local Z; the front door faces local +Z.
 */
public class BuildingStyle {
    public enum RoofType { FLAT, GABLE, HIP, PYRAMID, SHED }

    private static final float GOLDEN_RATIO_CONJUGATE = 0.618034f;

    public final RoofType roofType;
    public final float width;
    public final float depth;
    public final float wallHeight;
    public final float roofHeight;
    public final float roofOverhang;
    public final float doorWidth;
    public final float doorHeight;
    public final double signChance;
    public final Vector3 wallColour;
    public final Vector3 roofColour;
    public final Vector3 doorColour;

    private BuildingStyle(RoofType roofType, float width, float depth, float wallHeight,
                          float roofHeight, float roofOverhang, float doorWidth, float doorHeight,
                          double signChance, Vector3 wallColour, Vector3 roofColour, Vector3 doorColour) {
        this.roofType = roofType;
        this.width = width;
        this.depth = depth;
        this.wallHeight = wallHeight;
        this.roofHeight = roofHeight;
        this.roofOverhang = roofOverhang;
        this.doorWidth = doorWidth;
        this.doorHeight = doorHeight;
        this.signChance = signChance;
        this.wallColour = wallColour;
        this.roofColour = roofColour;
        this.doorColour = doorColour;
    }

    /**
     * Roof types are dealt from a shuffled deck so neighbouring nation IDs never
     * share a design, and hues are spread by the golden ratio so wall and roof
     * colours differ between nations.
     */
    public static Map<Integer, BuildingStyle> generateForNations(long seed, int numNations) {
        Random rand = new Random(seed + 8888L);

        List<RoofType> roofOrder = new ArrayList<>(Arrays.asList(RoofType.values()));
        Collections.shuffle(roofOrder, rand);

        float wallHueBase = rand.nextFloat();
        float roofHueBase = rand.nextFloat();

        Map<Integer, BuildingStyle> styles = new HashMap<>();
        for (int n = 1; n <= numNations; n++) {
            RoofType roofType = roofOrder.get((n - 1) % roofOrder.size());

            float width = 60.0f + rand.nextFloat() * 60.0f;
            float depth = width * (0.55f + rand.nextFloat() * 0.35f);
            float wallHeight = 30.0f + rand.nextFloat() * 35.0f;
            float roofHeight;
            float roofOverhang;
            if (roofType == RoofType.FLAT) {
                roofHeight = 3.0f + rand.nextFloat() * 3.0f;
                roofOverhang = 1.0f + rand.nextFloat() * 2.0f;
            } else {
                roofHeight = depth * (0.3f + rand.nextFloat() * 0.5f);
                roofOverhang = 3.0f + rand.nextFloat() * 6.0f;
            }
            float doorWidth = Math.min(width * 0.2f, 12.0f + rand.nextFloat() * 5.0f);
            float doorHeight = Math.min(wallHeight * 0.65f, 24.0f + rand.nextFloat() * 6.0f);
            double signChance = 0.35 + rand.nextDouble() * 0.45;

            float wallHue = fraction(wallHueBase + n * GOLDEN_RATIO_CONJUGATE);
            float roofHue = fraction(roofHueBase + n * GOLDEN_RATIO_CONJUGATE);
            Vector3 wallColour = hsb(wallHue, 0.12f + rand.nextFloat() * 0.33f, 0.60f + rand.nextFloat() * 0.30f);
            Vector3 roofColour = hsb(roofHue, 0.40f + rand.nextFloat() * 0.40f, 0.30f + rand.nextFloat() * 0.30f);
            Vector3 doorColour = hsb(wallHue, 0.30f + rand.nextFloat() * 0.30f, 0.15f + rand.nextFloat() * 0.15f);

            styles.put(n, new BuildingStyle(roofType, width, depth, wallHeight, roofHeight, roofOverhang,
                    doorWidth, doorHeight, signChance, wallColour, roofColour, doorColour));
        }
        return styles;
    }

    /**
     * Half-length of the hip roof ridge in unit roof space, chosen so the end
     * slopes run in as far as the side slopes.
     */
    public float hipRidgeHalfLength() {
        float roofWidth = width + 2.0f * roofOverhang;
        float roofDepth = depth + 2.0f * roofOverhang;
        return Math.max(0.05f, 0.5f * (1.0f - roofDepth / roofWidth));
    }

    private static float fraction(float value) {
        return value - (float) Math.floor(value);
    }

    private static Vector3 hsb(float hue, float saturation, float brightness) {
        Color colour = new Color(Color.HSBtoRGB(hue, saturation, brightness));
        return new Vector3(colour.getRed() / 255.0f, colour.getGreen() / 255.0f, colour.getBlue() / 255.0f);
    }
}
