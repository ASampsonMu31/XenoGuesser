import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/** Per-nation garden boundary: how often plots are fenced and what the fence looks like. */
public class FenceStyle {
    public enum Type { PICKET, RAIL, PANEL, WALL, HEDGE }

    public final Type type;
    /** Share of houses whose plot is fenced; zero for nations that never fence. */
    public final float fenceChance;
    public final float height;
    public final float postSpacing;
    public final float thickness;
    public final Vector3 colour;

    private FenceStyle(Type type, float fenceChance, float height, float postSpacing, float thickness, Vector3 colour) {
        this.type = type;
        this.fenceChance = fenceChance;
        this.height = height;
        this.postSpacing = postSpacing;
        this.thickness = thickness;
        this.colour = colour;
    }

    public static Map<Integer, FenceStyle> generateForNations(long seed, int numNations) {
        Random rand = new Random(seed + 5151L);
        Map<Integer, FenceStyle> styles = new HashMap<>();
        for (int n = 1; n <= numNations; n++) {
            Type type = Type.values()[rand.nextInt(Type.values().length)];
            float roll = rand.nextFloat();
            float fenceChance = roll < 0.2f ? 0.0f : 0.35f + rand.nextFloat() * 0.65f;

            float height;
            float thickness;
            Vector3 colour;
            switch (type) {
                case WALL:
                    height = 4.0f + rand.nextFloat() * 4.0f;
                    thickness = 2.0f + rand.nextFloat() * 1.5f;
                    float stone = 0.45f + rand.nextFloat() * 0.35f;
                    colour = new Vector3(stone, stone * 0.95f, stone * 0.88f);
                    break;
                case HEDGE:
                    height = 7.0f + rand.nextFloat() * 5.0f;
                    thickness = 3.5f + rand.nextFloat() * 2.5f;
                    colour = new Vector3(0.12f + rand.nextFloat() * 0.1f, 0.3f + rand.nextFloat() * 0.2f, 0.1f + rand.nextFloat() * 0.08f);
                    break;
                case PANEL:
                    height = 8.0f + rand.nextFloat() * 5.0f;
                    thickness = 0.6f;
                    colour = woodOrPaint(rand);
                    break;
                case PICKET:
                case RAIL:
                default:
                    height = 5.0f + rand.nextFloat() * 5.0f;
                    thickness = 0.6f;
                    colour = woodOrPaint(rand);
                    break;
            }
            styles.put(n, new FenceStyle(type, fenceChance, height, 7.0f + rand.nextFloat() * 6.0f, thickness, colour));
        }
        return styles;
    }

    /** Stained wood half the time, otherwise white or a painted colour. */
    private static Vector3 woodOrPaint(Random rand) {
        if (rand.nextBoolean()) {
            float wood = 0.3f + rand.nextFloat() * 0.3f;
            return new Vector3(wood, wood * 0.72f, wood * 0.48f);
        }
        if (rand.nextBoolean()) {
            return new Vector3(0.92f, 0.92f, 0.9f);
        }
        java.awt.Color paint = java.awt.Color.getHSBColor(rand.nextFloat(), 0.35f + rand.nextFloat() * 0.3f, 0.45f + rand.nextFloat() * 0.35f);
        return new Vector3(paint.getRed() / 255.0f, paint.getGreen() / 255.0f, paint.getBlue() / 255.0f);
    }
}
