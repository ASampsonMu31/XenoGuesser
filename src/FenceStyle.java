import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Per-nation garden boundary: how often plots are fenced, what the fence is built like,
 * and the surface its procedural texture shows. Neighbouring nations tend to fence alike.
 */
public class FenceStyle {
    public enum Type { PICKET, RAIL, PANEL, WALL, HEDGE }
    public enum Surface { WOOD, PAINTED, STONE, LEAVES }

    public final Type type;
    /** Share of houses whose plot is fenced; zero for nations that never fence. */
    public final float fenceChance;
    public final float height;
    public final float postSpacing;
    public final float thickness;
    public final Vector3 colour;
    public final Surface surface;
    /** World units covered by one repeat of the fence texture. */
    public final float tileSize;

    private FenceStyle(Type type, float fenceChance, float height, float postSpacing, float thickness,
                       Vector3 colour, Surface surface, float tileSize) {
        this.type = type;
        this.fenceChance = fenceChance;
        this.height = height;
        this.postSpacing = postSpacing;
        this.thickness = thickness;
        this.colour = colour;
        this.surface = surface;
        this.tileSize = tileSize;
    }

    public static Map<Integer, FenceStyle> generateForNations(long seed, int numNations, NationKinship kinship) {
        Random rand = new Random(seed + 5151L);
        int[] typeBloc = kinship.clusters(rand, 3, 0.2f);
        Type[] blocTypes = new Type[3];
        for (int b = 0; b < 3; b++) {
            blocTypes[b] = Type.values()[rand.nextInt(Type.values().length)];
        }

        Map<Integer, FenceStyle> styles = new HashMap<>();
        for (int n = 1; n <= numNations; n++) {
            Type type = blocTypes[typeBloc[n]];
            float roll = rand.nextFloat();
            float fenceChance = roll < 0.2f ? 0.0f : 0.35f + rand.nextFloat() * 0.65f;

            float height;
            float thickness;
            Vector3 colour;
            Surface surface;
            float tileSize;
            switch (type) {
                case WALL:
                    height = 4.0f + rand.nextFloat() * 4.0f;
                    thickness = 2.0f + rand.nextFloat() * 1.5f;
                    float stone = 0.45f + rand.nextFloat() * 0.35f;
                    colour = new Vector3(stone, stone * 0.95f, stone * 0.88f);
                    surface = Surface.STONE;
                    tileSize = 8.0f + rand.nextFloat() * 6.0f;
                    break;
                case HEDGE:
                    height = 7.0f + rand.nextFloat() * 5.0f;
                    thickness = 3.5f + rand.nextFloat() * 2.5f;
                    colour = new Vector3(0.12f + rand.nextFloat() * 0.1f, 0.3f + rand.nextFloat() * 0.2f, 0.1f + rand.nextFloat() * 0.08f);
                    surface = Surface.LEAVES;
                    tileSize = 6.0f + rand.nextFloat() * 4.0f;
                    break;
                case PANEL:
                    height = 8.0f + rand.nextFloat() * 5.0f;
                    thickness = 0.6f;
                    colour = woodOrPaint(rand);
                    surface = isWood(colour) ? Surface.WOOD : Surface.PAINTED;
                    tileSize = 10.0f + rand.nextFloat() * 6.0f;
                    break;
                case PICKET:
                case RAIL:
                default:
                    height = 5.0f + rand.nextFloat() * 5.0f;
                    thickness = 0.6f;
                    colour = woodOrPaint(rand);
                    surface = isWood(colour) ? Surface.WOOD : Surface.PAINTED;
                    tileSize = 8.0f + rand.nextFloat() * 6.0f;
                    break;
            }
            styles.put(n, new FenceStyle(type, fenceChance, height, 7.0f + rand.nextFloat() * 6.0f, thickness,
                    colour, surface, tileSize));
        }
        return styles;
    }

    private static boolean isWood(Vector3 colour) {
        // Stained wood keeps the fixed 1 : 0.72 : 0.48 channel ratio woodOrPaint gives it
        return Math.abs(colour.y - colour.x * 0.72f) < 1e-4f && Math.abs(colour.z - colour.x * 0.48f) < 1e-4f;
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
