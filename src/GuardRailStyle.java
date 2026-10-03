import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Per-nation highway guard rail design: whether the nation builds them at all,
 * the cross-section of the rail, its grey-scale colours and banding, and the
 * posts holding it up.
 */
public class GuardRailStyle {
    public enum Profile { W_BEAM, THRIE_BEAM, BOX_BEAM, TUBE, DOUBLE_TUBE, C_CHANNEL }
    public enum Banding { NONE, ALTERNATING, TOP_STRIPE }

    public final boolean enabled;
    public final Profile profile;
    public final Banding banding;
    /** Height of the rail centre above the ground. */
    public final float railHeight;
    /** Overall height of the rail cross-section. */
    public final float railSize;
    public final float postSpacing;
    public final float postWidth;
    public final float bandLength;
    public final float bandPeriod;
    public final Vector3 railColour;
    public final Vector3 bandColour;
    public final Vector3 postColour;

    private GuardRailStyle(boolean enabled, Profile profile, Banding banding, float railHeight, float railSize,
                           float postSpacing, float postWidth, float bandLength, float bandPeriod,
                           Vector3 railColour, Vector3 bandColour, Vector3 postColour) {
        this.enabled = enabled;
        this.profile = profile;
        this.banding = banding;
        this.railHeight = railHeight;
        this.railSize = railSize;
        this.postSpacing = postSpacing;
        this.postWidth = postWidth;
        this.bandLength = bandLength;
        this.bandPeriod = bandPeriod;
        this.railColour = railColour;
        this.bandColour = bandColour;
        this.postColour = postColour;
    }

    /** Profiles are dealt from a shuffled deck so nations that build rails rarely share a design. */
    public static Map<Integer, GuardRailStyle> generateForNations(long seed, int numNations) {
        Random rand = new Random(seed + 7171L);
        List<Profile> profiles = new ArrayList<>(List.of(Profile.values()));
        Collections.shuffle(profiles, rand);

        Map<Integer, GuardRailStyle> styles = new HashMap<>();
        int railNations = 0;
        for (int n = 1; n <= numNations; n++) {
            boolean enabled = rand.nextFloat() < 0.65f;
            Profile profile = profiles.get(railNations % profiles.size());
            if (enabled) {
                railNations++;
            }
            Banding banding = Banding.values()[rand.nextInt(Banding.values().length)];

            // White through grey to black, with a band shade clearly lighter or darker than the rail
            float railShade = 0.08f + rand.nextFloat() * 0.87f;
            float bandShade = railShade > 0.5f ? railShade - 0.45f - rand.nextFloat() * 0.2f
                                               : railShade + 0.45f + rand.nextFloat() * 0.2f;
            bandShade = Math.max(0.05f, Math.min(0.97f, bandShade));
            float postShade = 0.15f + rand.nextFloat() * 0.6f;

            float railSize = profile == Profile.TUBE ? 2.2f + rand.nextFloat() * 1.2f
                           : profile == Profile.DOUBLE_TUBE ? 6.0f + rand.nextFloat() * 2.0f
                           : 4.0f + rand.nextFloat() * 3.0f;
            float bandLength = 3.0f + rand.nextFloat() * 6.0f;

            styles.put(n, new GuardRailStyle(
                enabled, profile, banding,
                6.5f + rand.nextFloat() * 3.0f,
                railSize,
                9.0f + rand.nextFloat() * 12.0f,
                0.9f + rand.nextFloat() * 1.1f,
                bandLength,
                bandLength * (2.0f + rand.nextFloat() * 2.0f),
                grey(railShade), grey(bandShade), grey(postShade)
            ));
        }
        return styles;
    }

    /**
     * Cross-section as (outward, up) pairs relative to the rail centre, split
     * into separate strips. Closed shapes repeat their first point.
     */
    public List<float[][]> crossSection() {
        float h = railSize * 0.5f;
        List<float[][]> strips = new ArrayList<>();
        switch (profile) {
            case W_BEAM:
                strips.add(new float[][] { {0, -h}, {0.9f, -h * 0.5f}, {0, 0}, {0.9f, h * 0.5f}, {0, h} });
                break;
            case THRIE_BEAM:
                strips.add(new float[][] { {0, -h}, {0.8f, -h * 0.66f}, {0, -h * 0.33f}, {0.8f, 0},
                                           {0, h * 0.33f}, {0.8f, h * 0.66f}, {0, h} });
                break;
            case BOX_BEAM:
                strips.add(new float[][] { {0, -h}, {h * 0.8f, -h}, {h * 0.8f, h}, {0, h}, {0, -h} });
                break;
            case TUBE:
                strips.add(circle(0.0f, 0.0f, h, 8));
                break;
            case DOUBLE_TUBE:
                float r = h * 0.3f;
                strips.add(circle(0.0f, -h + r, r, 8));
                strips.add(circle(0.0f, h - r, r, 8));
                break;
            case C_CHANNEL:
            default:
                float[][] arc = new float[7][];
                for (int i = 0; i < arc.length; i++) {
                    double angle = -Math.PI * 0.5 + Math.PI * i / (arc.length - 1);
                    arc[i] = new float[] { (float) Math.cos(angle) * h * 0.6f, (float) Math.sin(angle) * h };
                }
                strips.add(arc);
                break;
        }
        return strips;
    }

    /** Whether the stretch at this distance along the rail is painted in the band colour. */
    public boolean isBandAt(float distance) {
        if (banding != Banding.ALTERNATING) {
            return false;
        }
        float phase = distance % bandPeriod;
        if (phase < 0.0f) {
            phase += bandPeriod;
        }
        return phase < bandLength;
    }

    private static float[][] circle(float centreOut, float centreUp, float radius, int sides) {
        float[][] points = new float[sides + 1][];
        for (int i = 0; i <= sides; i++) {
            double angle = 2.0 * Math.PI * i / sides;
            points[i] = new float[] { centreOut + (float) Math.cos(angle) * radius, centreUp + (float) Math.sin(angle) * radius };
        }
        return points;
    }

    private static Vector3 grey(float shade) {
        return new Vector3(shade, shade, shade);
    }
}
