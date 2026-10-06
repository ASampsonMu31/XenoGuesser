import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Matrix4Transform;
import com.xenoguesser.math.Vector3;

/**
 * The player: a human in a spacesuit, seen in first person. The body hangs below and a
 * little behind the eyes and turns with the view, so looking down shows the chest, arms,
 * legs and boots. It walks when the player moves, and when the water is too deep to stand
 * in it lies forward in the water and swims a front crawl.
 *
 * Built from the organism parts library and drawn with the organism shader.
 * World units: the eyes are 20 above the ground.
 */
public class PlayerBody {

    // Bones
    private static final int TORSO = 0;
    private static final int UPPER_ARM = 1, FOREARM = 3, GLOVE = 5;      // left then right
    private static final int THIGH = 7, SHIN = 9, BOOT = 11;            // left then right
    private static final int HEAD = 13;                                 // helmet, outside first person
    private static final int COMPASS = 14, NEEDLE = 15;                 // carried in the left hand
    private static final int THERMOMETER = 16, MERCURY = 17;            // carried in the right hand
    // The right hand's fingers (index to little: their first then second joints) and thumb,
    // which curl round the thermometer to hold it
    private static final int FINGER_BASE = 18, FINGER_TIP = 22, THUMB_BASE = 26, THUMB_TIP = 27;
    private static final int BONES = 28;
    private static final float[] FINGER_LENGTHS = { 0.72f, 0.8f, 0.76f, 0.6f };   // index to little finger
    // How tightly each digit of the right hand curls round the thermometer when it's held up:
    // 0 left in its resting curve, 1 curled right in (both joints), in between partly.
    // First finger, middle finger, ring finger, little finger, thumb.
    private static final float[] THERMOMETER_GRIP = { 0.85f, 0.83f, 0.7f, 0.35f, 2.2f};
    // +X is the body's left (the view's right is -X when facing +Z)
    private static final int LEFT = 1;

    // Proportions
    private static final float TORSO_LENGTH = 6.8f, TORSO_WIDTH = 4.2f, TORSO_DEPTH = 2.4f;
    private static final float UPPER_ARM_LENGTH = 3.6f, FOREARM_LENGTH = 3.3f;
    private static final float THIGH_LENGTH = 5.0f, SHIN_LENGTH = 4.8f;
    private static final float NECK_DROP = 3.3f, HIP_DROP = 10.0f, SHOULDER_DROP = 3.9f;
    private static final float SHOULDER_SPAN = 2.5f, HIP_SPAN = 1.1f;
    private static final float EYE_HEIGHT = 20f;
    private static final float STRIDE = 14f;
    private static final float SWIM_TILT = (float) Math.toRadians(80);
    private static final float SWIM_SHOULDER_LIFT = 2.4f;

    // The compass: a brass case with a cream card and a red and white needle
    private static final float COMPASS_RADIUS = 0.42f, COMPASS_DEPTH = 0.15f, CARD_THICKNESS = 0.07f;
    private static final float CARD_GAP = 0.02f, BEZEL_TOP = 0.28f, NEEDLE_HEIGHT = 0.265f;
    // The glove's palm, along the hand from the wrist
    private static final float PALM_START = 0.5f, PALM_LENGTH = 1.1f;
    // Where the compass rests on the palm: along the hand, and the palm's half-thickness
    private static final float COMPASS_ON_PALM = 1.05f, PALM_TOP = 0.17f;
    private static final float COMPASS_RAISE = 0.45f, COMPASS_HOLD = 2.4f;
    // Held a little left of and below the middle of the view, at this distance from the eyes
    private static final float COMPASS_REACH = 5.0f;
    private static final float COMPASS_BELOW = (float) Math.toRadians(14), COMPASS_LEFT = (float) Math.toRadians(13);

    // The thermometer: a glass rod (up its own +Z, its front to +Y) with its scale printed on
    // it, a red bulb at the bottom and the red column running up the front of the rod
    private static final float THERMOMETER_LENGTH = 2.3f;
    private static final float ROD_RADIUS = 0.075f, COLUMN_RADIUS = 0.026f, TUBE_OUT = 0f;
    private static final float BULB_Z = 0.2f, BULB_RADIUS = 0.1f, TUBE_START = 0.26f, TUBE_END = 2.15f;
    // The scale, coldest to hottest, and where along the board those are
    public static final float SCALE_LOW = -30f, SCALE_HIGH = 50f;
    private static final float SCALE_LOW_Z = 0.45f, SCALE_HIGH_Z = 2.0f;
    // Held to the right of the middle of the view, a little low, at this distance
    private static final float THERMOMETER_REACH = 4.4f;
    // How far the compass hand is tipped towards the eyes (radians): always, and extra when held up high
    private static final float COMPASS_TIP = 0.5f, COMPASS_TIP_HIGH = 1.5f;
    private static final float THERMOMETER_BELOW = (float) Math.toRadians(4), THERMOMETER_RIGHT = (float) Math.toRadians(13);
    // Where in the right hand it's held: how far up from its bottom, how far along the hand from
    // the wrist (just past the knuckles), and how far in front of the palm
    private static final float GRIP_UP = 0.36f, GRIP_ALONG = 1.05f, GRIP_OUT = 0.46f;
    // The thermometer in the glove's frame: held across the hand (the glove's -x is up it), facing as the palm does
    private static final float[] THERMOMETER_IN_HAND = Affine.frame(new float[] { GRIP_UP, GRIP_OUT, GRIP_ALONG },
            new float[] { -1f, 0f, 0f }, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f);

    /**
     * The suit's loose fabric: soft rings of folds bunching along each section, twisting a
     * little round it, with finer creases between. Only cloth parts take them.
     */
    private static final OrganismMesh.Folds SUIT_FOLDS = (along, angle) -> 1f
            + 0.07f * (float) Math.sin(along * 2.6f + 1.4f * Math.sin(angle * 2f + along * 0.7f))
            + 0.035f * (float) Math.sin(angle * 3f + along * 1.9f + 1.3f)
            + 0.02f * (float) Math.sin(angle * 7f - along * 4.1f);

    private Shader shader;
    private OrganismMesh mesh;
    private Texture fabric;
    private final float[] bones = new float[BONES * 16];

    private float walkPhase;
    private float walking;
    private float strokePhase;
    private float swimBlend;
    private float pitch;
    private float lastX = Float.NaN, lastZ = Float.NaN;
    // Time since the compass was taken out (negative when put away), and its needle's
    // heading in the world (0 is north) and how fast that is turning
    private float compassTime = -1f;
    // The same for the thermometer, and what it reads (degrees Celsius), the column easing to it
    private float thermometerTime = -1f;
    private float reading = 15f, shown = 15f;
    private Texture scale;
    private float needleAngle, needleSpin;
    private final java.util.Random needleKick = new java.util.Random();

    public void initialise(GL3 gl) {
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_organism.txt", GamePaths.HOME + "assets/shaders/fs_organism.txt");
        // In first person the eyes are inside the helmet, so it is left off
        mesh = buildMesh(false).build(gl);
        glass = buildGlass().build(gl);
        // The suit's crumpled fabric, shared with the loading screen's spaceman
        if (new java.io.File(LoadingArt.FABRIC).exists()) {
            fabric = TextureLibrary.loadTextureWrap(gl, LoadingArt.FABRIC);
        }
        try {
            java.io.File file = java.io.File.createTempFile("thermometer_scale", ".png");
            file.deleteOnExit();
            javax.imageio.ImageIO.write(scaleImage(), "png", file);
            scale = TextureLibrary.loadTextureWrap(gl, file.getPath());
        } catch (java.io.IOException e) {
            System.err.println("Thermometer scale unavailable: " + e.getMessage());
        }
    }

    /** The thermometer's glass tube, its scale printed on it: drawn see-through after everything else. */
    private static OrganismMesh.Builder buildGlass() {
        OrganismMesh.Builder b = new OrganismMesh.Builder();
        b.bone(THERMOMETER).transform(Affine.translation(0f, 0f, TUBE_START)).part(OrganismMesh.PART_PRODUCT);
        b.lathe(32, 8, puck(ROD_RADIUS, TUBE_END - TUBE_START));
        b.resetTransform();
        return b;
    }

    private OrganismMesh glass;

    /** The suit, optionally with its helmet; shared with the loading screen's spaceman. */
    static OrganismMesh.Builder buildMesh(boolean helmet) {
        OrganismMesh.Builder b = new OrganismMesh.Builder();
        b.folds(SUIT_FOLDS);

        // Torso: modelled up its own Z, with +Y to the front
        b.bone(TORSO).resetTransform();
        OrganismParts.bodySegment(b, TORSO_LENGTH, TORSO_WIDTH, TORSO_DEPTH, 0.45f, 0, 0f, 1f);
        b.transform(Affine.translation(0f, 0f, -TORSO_LENGTH * 0.5f));
        OrganismParts.bodySegment(b, 2.6f, 4.2f, 2.9f, 0.5f, 0, 0f, 1f);
        b.transform(Affine.translation(0f, -(TORSO_DEPTH * 0.5f + 0.9f), 0.4f));
        trimSegment(b, 5.2f, 3.6f, 1.8f);
        b.transform(Affine.translation(0f, TORSO_DEPTH * 0.5f + 0.15f, 1.0f));
        trimSegment(b, 1.5f, 1.9f, 0.7f);

        for (int side = 0; side < 2; side++) {
            // Rounded joints fill the gaps where the limb sections meet
            b.bone(UPPER_ARM + side).resetTransform();
            OrganismParts.legSegment(b, UPPER_ARM_LENGTH, 0.85f, 0.72f, 0);
            joint(b, 1.05f);
            b.bone(FOREARM + side).resetTransform();
            OrganismParts.legSegment(b, FOREARM_LENGTH, 0.72f, 0.62f, 0);
            joint(b, 0.8f);
            glove(b, GLOVE + side, side == LEFT ? 1f : -1f, side != LEFT);
            b.bone(THIGH + side).resetTransform();
            OrganismParts.legSegment(b, THIGH_LENGTH, 1.15f, 0.95f, 0);
            joint(b, 1.3f);
            b.bone(SHIN + side).resetTransform();
            OrganismParts.legSegment(b, SHIN_LENGTH, 0.95f, 0.8f, 0);
            joint(b, 1.05f);
            b.transform(Affine.translation(0f, 0f, SHIN_LENGTH));
            joint(b, 0.85f);
            b.resetTransform();
            // Boot: ankle at the origin, sole on the ground below it, toes forward
            b.bone(BOOT + side).transform(Affine.translation(0f, -0.15f, 0.55f));
            trimSegment(b, 2.8f, 1.5f, 1.3f);
        }
        if (!helmet) {
            // In first person: the compass, out of sight until it is used
            b.folds(null);
            b.bone(COMPASS).resetTransform().part(OrganismMesh.PART_BRASS);
            b.lathe(24, 12, puck(COMPASS_RADIUS, COMPASS_DEPTH));
            // The bezel: an open brass band standing up round the card
            b.lathe(32, 2, (t, out) -> {
                out[0] = 0f;
                out[1] = 0f;
                out[2] = COMPASS_DEPTH * 0.7f + t * (BEZEL_TOP - COMPASS_DEPTH * 0.7f);
                out[3] = COMPASS_RADIUS * 0.97f;
                out[4] = COMPASS_RADIUS * 0.97f;
            });
            // The card sits clear of the case's top, inside the bezel
            b.transform(Affine.translation(0f, 0f, COMPASS_DEPTH + CARD_GAP)).part(OrganismMesh.PART_DIAL);
            b.lathe(32, 12, puck(COMPASS_RADIUS * 0.88f, CARD_THICKNESS));
            b.resetTransform();
            float needle = COMPASS_RADIUS * 0.72f;
            b.bone(NEEDLE).part(OrganismMesh.PART_NEEDLE_NORTH);
            b.lathe(8, 6, (t, out) -> needleHalf(t, needle, out));
            b.part(OrganismMesh.PART_NEEDLE_SOUTH);
            b.lathe(8, 6, (t, out) -> needleHalf(t, -needle, out));
            // The pivot cap
            b.transform(Affine.rotationX((float) -Math.PI / 2)).part(OrganismMesh.PART_BRASS);
            b.lathe(10, 8, puck(0.05f, 0.04f));
            b.resetTransform();
            // The thermometer's red bulb (its glass tube is drawn after, see buildGlass; the column is its own bone)
            b.bone(THERMOMETER).transform(Affine.translation(0f, 0f, BULB_Z));
            OrganismParts.shell(b, OrganismMesh.PART_NEEDLE_NORTH, BULB_RADIUS * 2f, BULB_RADIUS * 2f, BULB_RADIUS * 2f, 1f, 0, 0f, 1f);
            b.resetTransform();
            b.bone(MERCURY).part(OrganismMesh.PART_NEEDLE_NORTH);
            b.lathe(12, 4, puck(COLUMN_RADIUS, 1f));
            b.resetTransform();
        }
        if (helmet) {
            // A round helmet with a dark visor, and a collar ring where it seals to the suit
            b.bone(HEAD).resetTransform();
            OrganismParts.shell(b, OrganismMesh.PART_SKIN, 4.6f, 4.4f, 4.6f, 0.5f, 0, 0f, 1f);
            b.transform(Affine.translation(0f, 0.15f, 0.75f));
            OrganismParts.shell(b, OrganismMesh.PART_EYE, 3.4f, 3.7f, 3.3f, 0.5f, 0, 0f, 1f);
            b.transform(Affine.multiply(Affine.translation(0f, -2.0f, -0.1f), Affine.rotationX((float) -Math.PI / 2)));
            OrganismParts.shell(b, OrganismMesh.PART_TRIM, 1.4f, 4.0f, 3.6f, 0.2f, 0, 0f, 1f);
        }
        return b;
    }

    /** Poses the body standing still with its eyes at eye, facing heading (radians), into the returned bone matrices. */
    float[] standingPose(Vector3 eye, float heading) {
        walking = 0f;
        swimBlend = 0f;
        pitch = 0f;
        pose(eye, heading);
        return bones.clone();
    }

    /**
     * A short round box along Z from 0 to depth: flat faces (very slightly domed, so the
     * lathe can tell which way they face) and a straight side.
     */
    private static OrganismMesh.Profile puck(float radius, float depth) {
        float dome = Math.min(depth * 0.08f, 0.004f);
        return (t, out) -> {
            float z, r;
            if (t < 0.25f) {
                r = radius * t / 0.25f;
                z = -dome * (1f - t / 0.25f);
            } else if (t < 0.75f) {
                r = radius;
                z = depth * (t - 0.25f) / 0.5f;
            } else {
                r = radius * (1f - t) / 0.25f;
                z = depth + dome * (t - 0.75f) / 0.25f;
            }
            out[0] = 0f;
            out[1] = 0f;
            out[2] = z;
            out[3] = r;
            out[4] = r;
        };
    }

    /** Half the compass needle, from the pivot out to a point at z = length (negative for the south half). */
    private static void needleHalf(float t, float length, float[] out) {
        out[0] = 0f;
        out[1] = 0f;
        out[2] = length * t;
        out[3] = 0.08f * (1f - t) + 0.003f;
        out[4] = 0.022f * (1f - 0.5f * t);
    }

    /** C: take the compass out, or keep it out a little longer if it already is. */
    public void useCompass() {
        if (compassTime < 0f || compassTime > COMPASS_RAISE + COMPASS_HOLD) {
            if (compassTime < 0f) {
                // Freshly out of the pocket the needle swings before it settles
                needleAngle = (needleKick.nextBoolean() ? 1f : -1f) * (0.7f + 0.6f * needleKick.nextFloat());
                needleSpin = 0f;
            }
            compassTime = compassTime < 0f ? 0f : COMPASS_RAISE;
        } else if (compassTime > COMPASS_RAISE) {
            compassTime = COMPASS_RAISE;
        }
    }

    /** 2: take the thermometer out, or keep it out a little longer if it already is. */
    public void useThermometer() {
        if (thermometerTime < 0f) {
            thermometerTime = 0f;
            // Fresh out of the pocket it starts from 0 and creeps up or down to the reading
            shown = 0f;
        } else if (thermometerTime > COMPASS_RAISE) thermometerTime = COMPASS_RAISE;
    }

    /** What the thermometer reads where the player stands, in degrees Celsius. */
    public void setReading(float celsius) {
        reading = celsius;
    }

    /** How far the thermometer is raised, 0 put away to 1 held up to look at. */
    private float thermometerBlend() {
        if (thermometerTime < 0f) return 0f;
        float t;
        if (thermometerTime < COMPASS_RAISE) t = thermometerTime / COMPASS_RAISE;
        else if (thermometerTime < COMPASS_RAISE + COMPASS_HOLD * 1.5f) t = 1f;
        else t = 1f - (thermometerTime - COMPASS_RAISE - COMPASS_HOLD * 1.5f) / COMPASS_RAISE;
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /**
     * The glass rod's surface, as wrapped round it: across the image is round the rod (a
     * quarter of the way across is its front, where the red column runs) and up the image is
     * along it (its side from a quarter to three quarters of the way, the ends beyond). Printed
     * on the front: a tick every two degrees beside the column, longer every ten with its
     * number, from SCALE_LOW to SCALE_HIGH, and "°C" at the top.
     */
    private static java.awt.image.BufferedImage scaleImage() {
        int w = 512, h = 2048;
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Clear glass (drawn see-through: only the print shows solid)
        g.setColor(new java.awt.Color(235, 242, 246));
        g.fillRect(0, 0, w, h);
        float rodLength = TUBE_END - TUBE_START;
        // Pixels to a unit round the rod and along it; text is stretched across so it reads in proportion
        float acrossPerUnit = w / (float) (2 * Math.PI * ROD_RADIUS), alongPerUnit = h * 0.5f / rodLength;
        float stretch = acrossPerUnit / alongPerUnit;
        float front = w * 0.25f, column = COLUMN_RADIUS * acrossPerUnit * 1.05f;
        g.setColor(new java.awt.Color(20, 22, 28));
        java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 30);
        for (int t = (int) SCALE_LOW; t <= (int) SCALE_HIGH; t += 2) {
            float z = SCALE_LOW_Z + (t - SCALE_LOW) / (SCALE_HIGH - SCALE_LOW) * (SCALE_HIGH_Z - SCALE_LOW_Z);
            float y = h * (1f - (0.25f + 0.5f * (z - TUBE_START) / rodLength));
            boolean major = t % 10 == 0, mid = t % 10 == 5;
            // Ticks on the column's one side and the numbers on its other, both on the front of the rod
            float length = (major ? 0.06f : mid ? 0.045f : 0.025f) * acrossPerUnit;
            g.setStroke(new java.awt.BasicStroke(major ? 9f : 6f));
            g.draw(new java.awt.geom.Line2D.Float(front - column - length, y, front - column, y));
            if (major) {
                g.draw(new java.awt.geom.Line2D.Float(front + column, y, front + column + 0.015f * acrossPerUnit, y));
                // Printed along the tube, reading upwards, over the middle of its front (the red shows round them)
                java.awt.geom.AffineTransform before = g.getTransform();
                g.setFont(font);
                java.awt.FontMetrics metrics = g.getFontMetrics();
                g.translate(front + metrics.getAscent() * stretch * 0.5f, y);
                g.rotate(-Math.PI / 2);
                g.scale(1f, stretch);
                String label = Integer.toString(t);
                g.drawString(label, -metrics.stringWidth(label) * 0.5f, 0f);
                g.setTransform(before);
            }
        }
        java.awt.geom.AffineTransform before = g.getTransform();
        float topY = h * (1f - (0.25f + 0.5f * (SCALE_HIGH_Z + 0.09f - TUBE_START) / rodLength));
        g.translate(front - 0.03f * acrossPerUnit, topY);
        g.scale(stretch, 1f);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 26));
        g.drawString("\u00B0C", 0f, 12f);
        g.setTransform(before);
        g.dispose();
        return image;
    }

    /** How far the compass is raised, 0 put away to 1 held up to look at. */
    private float compassBlend() {
        if (compassTime < 0f) return 0f;
        float t;
        if (compassTime < COMPASS_RAISE) t = compassTime / COMPASS_RAISE;
        else if (compassTime < COMPASS_RAISE + COMPASS_HOLD) t = 1f;
        else t = 1f - (compassTime - COMPASS_RAISE - COMPASS_HOLD) / COMPASS_RAISE;
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /** A ball of suit fabric at a joint, centred on the current origin. */
    private static void joint(OrganismMesh.Builder b, float radius) {
        OrganismParts.shell(b, OrganismMesh.PART_LEG, radius * 2f, radius * 2f, radius * 2f, 0.5f, 0, 0f, 1f);
    }

    /**
     * A gloved hand along its bone's +Z from the wrist, palm facing +Y, the thumb towards
     * +X times thumbSide: a cuff, a flat palm, four fingers curling gently up and a thumb.
     */
    private static void glove(OrganismMesh.Builder b, int bone, float thumbSide, boolean jointed) {
        b.bone(bone);
        b.transform(Affine.translation(0f, 0f, 0.3f));
        OrganismParts.shell(b, OrganismMesh.PART_TRIM, 0.75f, 0.92f, 0.8f, 0.3f, 0, 0f, 1f);
        b.transform(Affine.translation(0f, 0f, PALM_START + PALM_LENGTH * 0.5f));
        OrganismParts.shell(b, OrganismMesh.PART_TRIM, PALM_LENGTH, 1.0f, 0.38f, 0.25f, 0, 0f, 0.95f);
        if (jointed) {
            // Each finger and the thumb in two parts, each on its own bone from its joint, so they can curl
            for (int f = 0; f < 4; f++) {
                float first = FINGER_LENGTHS[f] * 0.58f, second = FINGER_LENGTHS[f] * 0.5f;
                b.bone(FINGER_BASE + f).transform(Affine.translation(0f, 0f, first * 0.5f));
                OrganismParts.shell(b, OrganismMesh.PART_TRIM, first, 0.21f, 0.2f, 0.35f, 0, 0f, 0.95f);
                b.bone(FINGER_TIP + f).transform(Affine.translation(0f, 0f, second * 0.5f));
                OrganismParts.shell(b, OrganismMesh.PART_TRIM, second, 0.2f, 0.19f, 0.35f, 0, 0f, 0.85f);
            }
            b.bone(THUMB_BASE).transform(Affine.translation(0f, 0f, 0.2f));
            OrganismParts.shell(b, OrganismMesh.PART_TRIM, 0.4f, 0.25f, 0.23f, 0.35f, 0, 0f, 0.95f);
            b.bone(THUMB_TIP).transform(Affine.translation(0f, 0f, 0.17f));
            OrganismParts.shell(b, OrganismMesh.PART_TRIM, 0.34f, 0.23f, 0.21f, 0.35f, 0, 0f, 0.85f);
            b.resetTransform();
            return;
        }
        float[] lengths = FINGER_LENGTHS;
        for (int f = 0; f < 4; f++) {
            float x = thumbSide * (0.34f - f * 0.225f);
            float[] place = Affine.multiply(Affine.translation(x, 0.02f, PALM_START + PALM_LENGTH - 0.12f),
                    Affine.multiply(Affine.rotationX(-0.32f), Affine.translation(0f, 0f, lengths[f] * 0.5f)));
            b.transform(place);
            OrganismParts.shell(b, OrganismMesh.PART_TRIM, lengths[f], 0.21f, 0.2f, 0.35f, 0, 0f, 0.85f);
        }
        float[] thumb = Affine.multiply(Affine.translation(thumbSide * 0.42f, 0.04f, PALM_START + 0.3f),
                Affine.multiply(Affine.rotationY(thumbSide * 0.75f),
                        Affine.multiply(Affine.rotationX(-0.25f), Affine.translation(0f, 0f, 0.32f))));
        b.transform(thumb);
        OrganismParts.shell(b, OrganismMesh.PART_TRIM, 0.64f, 0.25f, 0.23f, 0.35f, 0, 0f, 0.85f);
        b.resetTransform();
    }

    private static void trimSegment(OrganismMesh.Builder b, float length, float width, float height) {
        OrganismParts.shell(b, OrganismMesh.PART_TRIM, length, width, height, 0.35f, 0, 0f, 1f);
    }

    public void dispose(GL3 gl) {
        if (mesh != null) mesh.dispose(gl);
        if (glass != null) glass.dispose(gl);
    }

    /**
     * Moves the body on by one frame.
     *
     * @param eye      where the eyes are
     * @param swimming whether the player is swimming rather than standing
     */
    public void update(float dt, Vector3 eye, boolean swimming) {
        float moved = Float.isNaN(lastX) ? 0f : (float) Math.hypot(eye.x - lastX, eye.z - lastZ);
        lastX = eye.x;
        lastZ = eye.z;
        if (moved > 50f) moved = 0f;   // a teleport, not a step
        float speed = dt > 0 ? moved / dt : 0f;
        float targetWalking = Math.min(1f, speed / 40f);
        walking += (targetWalking - walking) * Math.min(1f, dt * 6f);
        walkPhase = (walkPhase + moved * (float) (2 * Math.PI) / STRIDE) % (float) (2 * Math.PI);
        swimBlend += ((swimming ? 1f : 0f) - swimBlend) * Math.min(1f, dt * 3f);
        strokePhase = (strokePhase + dt * (1.4f + 1.8f * walking)) % (float) (2 * Math.PI);
        if (thermometerTime >= 0f) {
            thermometerTime += dt;
            if (thermometerTime > 2f * COMPASS_RAISE + COMPASS_HOLD * 1.5f) thermometerTime = -1f;
        }
        // The column creeps to the reading rather than jumping
        shown += (reading - shown) * Math.min(1f, dt * 1.5f);
        if (compassTime >= 0f) {
            compassTime += dt;
            if (compassTime > 2f * COMPASS_RAISE + COMPASS_HOLD) compassTime = -1f;
            // The needle is a damped pendulum pulled towards north; its heading is kept in
            // the world, so it holds steady as the body turns under it. North on the map is
            // north on the planet (it's a Mercator chart), but the pull weakens towards the
            // poles, where the planet's field points into the ground: there the needle is
            // sluggish, any jolt sends it wandering, and at the pole itself it points anywhere
            float pull = Planet.compassPull(eye.z);
            float step = Math.min(dt, 0.05f);
            float wobble = (1f - pull) * (1f - pull) * 6f;
            needleSpin += (-38f * pull * pull * (float) Math.sin(needleAngle) - 4.5f * needleSpin
                    + (needleKick.nextFloat() - 0.5f) * wobble * 20f) * step;
            needleAngle += needleSpin * step;
            needleAngle = (float) Math.atan2(Math.sin(needleAngle), Math.cos(needleAngle));
        }
    }

    /**
     * Draws the body over the finished scene. It gets its own close-range projection, since
     * the scene's near plane would cut away anything within arm's reach, and it is always the
     * nearest thing to the eyes.
     */
    public void render(GL3 gl, Camera camera, float aspect, Vector3 sunPos, float[] sunColour, Vector3 ambient,
                       Matrix4 skyRotation, Texture sky, float waterLevel, float[] waterTint) {
        if (mesh == null) return;
        Vector3 eye = camera.getPosition();
        Vector3 forward = camera.getForwardDirection();
        float heading = (float) Math.atan2(forward.x, forward.z);
        pitch = (float) Math.asin(Math.max(-1f, Math.min(1f, forward.y)));
        pose(eye, heading);

        Matrix4 projection = Matrix4Transform.perspective(45, aspect, 0.3f, 60f);
        Matrix4 viewProjection = Matrix4.multiply(projection, camera.getViewMatrix());

        gl.glClear(GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_CULL_FACE);
        shader.use(gl);
        shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "bones"), BONES, false, bones, 0);
        shader.setVec3(gl, "viewPos", eye);
        shader.setVec3(gl, "sunPos", sunPos);
        shader.setVec3(gl, "sunColour", new Vector3(sunColour[0], sunColour[1], sunColour[2]));
        shader.setVec3(gl, "ambientLight", ambient);
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            shader.setInt(gl, "skyTexture", 2);
        }
        // A white suit with orange bands and dark grey boots, gloves and pack
        shader.setVec3(gl, "baseColour", new Vector3(0.92f, 0.92f, 0.9f));
        shader.setVec3(gl, "bellyColour", new Vector3(0.88f, 0.88f, 0.86f));
        shader.setVec3(gl, "accentColour", new Vector3(0.95f, 0.45f, 0.1f));
        shader.setVec3(gl, "limbColour", new Vector3(0.9f, 0.9f, 0.88f));
        // (the thermometer's glass tube: pale and glossy)
        shader.setVec3(gl, "eyeColour", new Vector3(0.78f, 0.86f, 0.9f));
        shader.setVec3(gl, "trimColour", new Vector3(0.5f, 0.51f, 0.54f));
        shader.setInt(gl, "patternType", 0);
        shader.setFloat(gl, "patternScale", 2.0f);
        shader.setFloat(gl, "gloss", 0.05f);
        shader.setFloat(gl, "waterLevel", waterLevel);
        shader.setVec3(gl, "waterTint", new Vector3(waterTint[0], waterTint[1], waterTint[2]));
        if (fabric != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE4);
            fabric.bind(gl);
            shader.setInt(gl, "detailTexture", 4);
            shader.setFloat(gl, "detailAmount", 0.7f);
        }
        if (scale != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE5);
            scale.bind(gl);
            shader.setInt(gl, "productTexture", 5);
        }
        mesh.render(gl);
        if (glass != null && thermometerBlend() > 0.01f) {
            // The tube: part see-through, part reflecting the sky, like the windows
            gl.glEnable(GL.GL_BLEND);
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            gl.glDepthMask(false);
            shader.setFloat(gl, "glassAlpha", 0.12f);
            shader.setFloat(gl, "gloss", 0.9f);
            glass.render(gl);
            shader.setFloat(gl, "glassAlpha", 0f);
            gl.glDepthMask(true);
            gl.glDisable(GL.GL_BLEND);
        }
        gl.glEnable(GL.GL_CULL_FACE);
    }

    /** Works out every bone's world matrix for the current walk or swim. */
    private void pose(Vector3 eye, float heading) {
        float s = swimBlend;
        // Standing, the body hangs a little behind the eyes; swimming, it lies just below them
        float back = -1.2f + 2.4f * s;
        float[] body = Affine.multiply(Affine.translation(eye.x, eye.y, eye.z),
                Affine.multiply(Affine.rotationY(heading), Affine.multiply(Affine.rotationX(SWIM_TILT * s),
                        // Lying in the water the head tips back, bringing the shoulders up under the eyes
                        Affine.translation(0f, SWIM_SHOULDER_LIFT * s, 0f))));

        float breathe = (float) Math.sin(strokePhase * 0.7f) * 0.08f;
        float[] neck = { 0f, -NECK_DROP + breathe, back };
        float[] pelvis = { 0f, -HIP_DROP + 0.2f * walking * (float) Math.abs(Math.cos(walkPhase)), back };
        float[] torsoCentre = { 0f, (neck[1] + pelvis[1]) * 0.5f, back };
        float twist = 0.08f * walking * (float) Math.sin(walkPhase) * (1f - s);
        float[] torsoFrame = Affine.frame(torsoCentre, Affine.subtract(neck, pelvis),
                new float[] { (float) Math.sin(twist), 0f, (float) Math.cos(twist) }, 1f, 1f,
                Affine.length(Affine.subtract(neck, pelvis)) / TORSO_LENGTH);
        Affine.multiply(body, torsoFrame, bones, TORSO * 16);
        Affine.multiply(body, Affine.translation(0f, -0.6f, -1.0f), bones, HEAD * 16);

        // Where the compass is held: in front of the eyes, a little below and left of where
        // they look, following the view up and down within reach
        // (even looking up at the sky, when it's raised high and drawn in closer so the arm
        // reaches, the shoulder lifting a little)
        float held = compassBlend();
        float lookDown = Math.max((float) Math.toRadians(-69), Math.min((float) Math.toRadians(72), pitch - COMPASS_BELOW));
        float high = Math.max(0f, (float) Math.sin(lookDown));
        float shoulderLift = 1.6f * high * held;
        float[] compassAt = compassPoint(COMPASS_REACH, lookDown);
        float[] leftShoulder = { SHOULDER_SPAN, -SHOULDER_DROP + shoulderLift, back };
        for (float reach = COMPASS_REACH; reach > 2.4f; reach -= 0.1f) {
            compassAt = compassPoint(reach, lookDown);
            if (Affine.length(Affine.subtract(compassAt, leftShoulder)) < (UPPER_ARM_LENGTH + FOREARM_LENGTH) * 0.95f) break;
        }

        // Where the thermometer is held: upright, to the right of the view and facing the eyes
        float thermo = thermometerBlend();
        float elevation = Math.max((float) Math.toRadians(-60), Math.min((float) Math.toRadians(60), pitch - THERMOMETER_BELOW));
        float[] rightShoulder = { -SHOULDER_SPAN, -SHOULDER_DROP, back };
        float[] thermoCentre = null;
        for (float reach = THERMOMETER_REACH; reach > 2.4f; reach -= 0.1f) {
            thermoCentre = new float[] {
                -reach * (float) Math.sin(THERMOMETER_RIGHT),
                reach * (float) Math.sin(elevation),
                reach * (float) Math.cos(elevation) * (float) Math.cos(THERMOMETER_RIGHT)
            };
            if (Affine.length(Affine.subtract(thermoCentre, rightShoulder)) < (UPPER_ARM_LENGTH + FOREARM_LENGTH) * 0.9f) break;
        }
        float[] face = Affine.normalise(new float[] { -thermoCentre[0], -thermoCentre[1], -thermoCentre[2] });
        float[] upright = Affine.normalise(Affine.subtract(new float[] { 0f, 1f, 0f }, scaled(face, face[1])));
        float[] thermoBottom = Affine.subtract(thermoCentre, scaled(upright, THERMOMETER_LENGTH * 0.5f));
        // Gripped low down in the fist: palm to the eyes, the hand across the tube with the
        // fingers pointing left and curled round its front, the thumb on the right
        float[] across = Affine.normalise(Affine.cross(face, upright));
        // (where it's gripped, in the glove's own frame: just past the knuckles, in front of the palm)
        float[] grip = Affine.subtract(thermoBottom, scaled(upright, -GRIP_UP));
        float[] thermoWrist = {
            grip[0] - across[0] * GRIP_ALONG - face[0] * GRIP_OUT,
            grip[1] - across[1] * GRIP_ALONG - face[1] * GRIP_OUT,
            grip[2] - across[2] * GRIP_ALONG - face[2] * GRIP_OUT
        };

        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? -1f : 1f;
            float phase = walkPhase + side * (float) Math.PI;
            float stroke = strokePhase + side * (float) Math.PI;

            // Arms: swinging opposite the legs when walking, a front crawl when swimming
            float[] shoulder = { sign * SHOULDER_SPAN, -SHOULDER_DROP + (side == LEFT ? shoulderLift : 0f), back };
            float swing = -(float) Math.sin(phase) * walking;
            float[] walkHand = { shoulder[0] + sign * 0.5f, shoulder[1] - 6.1f + 0.4f * Math.abs(swing), shoulder[2] + swing * 2.0f + 0.3f };
            // Front crawl, in the tilted body's space (+Y ahead, +Z down into the water): the
            // hand pulls deep under the body, then comes forward low and wide beside the head,
            // never up across the eyes
            float reachCos = (float) Math.cos(stroke), reachSin = (float) Math.sin(stroke);
            // (smooth throughout: no term changes abruptly between pull and recovery)
            float recovery = Math.max(0f, -reachSin);
            float[] swimHand = {
                shoulder[0] * (0.85f - 0.15f * reachSin + 0.2f * recovery * recovery),
                shoulder[1] + 6.0f * reachCos,
                shoulder[2] + 1.8f * reachSin + 1.0f * reachSin * Math.abs(reachSin)
            };
            float[] hand = lerp(walkHand, swimHand, s);
            // Elbows bend back when walking, and out to the sides when swimming
            float[] bend = lerp(new float[] { sign * 0.4f, 0f, -1f }, new float[] { sign, 0f, 0.35f }, s);
            // The palm faces the glove frame's +Y: standing and walking it faces in towards the
            // thigh with the thumb forward; swimming it faces down into the water, thumb inwards
            float[] palm = lerp(new float[] { -sign, 0f, 0f }, new float[] { 0f, 0f, 1f }, s);
            boolean holding = side == LEFT && held > 0f;
            // Holding the compass the wrist bends so the hand points level, ahead and in
            float[] handAlong = null;
            if (holding) {
                // Held out palm up and level, the compass resting on the glove (it's tipped
                // towards the eyes afterwards, see COMPASS_TIP)
                float[] toShoulder = Affine.subtract(compassAt, shoulder);
                float[] reach = Affine.normalise(new float[] { toShoulder[0], 0f, toShoulder[2] });
                handAlong = reach;
                float[] palmUp = { 0f, 1f, 0f };
                float[] wrist = {
                    compassAt[0] - reach[0] * COMPASS_ON_PALM - palmUp[0] * PALM_TOP,
                    compassAt[1] - reach[1] * COMPASS_ON_PALM - palmUp[1] * PALM_TOP,
                    compassAt[2] - reach[2] * COMPASS_ON_PALM - palmUp[2] * PALM_TOP
                };
                hand = lerp(hand, wrist, held);
                bend = lerp(bend, new float[] { 0.6f, -1f, 0f }, held);
                palm = lerp(palm, palmUp, held);
            }
            if (side == 0 && thermo > 0f) {
                // Holding the thermometer up: palm to the eyes, the hand across it
                hand = lerp(hand, thermoWrist, thermo);
                bend = lerp(bend, new float[] { -1f, -1f, 0f }, thermo);
                palm = lerp(palm, face, thermo);
                handAlong = across;
            }
            float[] elbow = Affine.middleJoint(shoulder, hand, UPPER_ARM_LENGTH, FOREARM_LENGTH, bend);
            limb(body, shoulder, elbow, UPPER_ARM_LENGTH, UPPER_ARM + side);
            limb(body, elbow, hand, FOREARM_LENGTH, FOREARM + side);
            float[] along = Affine.normalise(Affine.subtract(hand, elbow));
            if (handAlong != null) along = Affine.normalise(lerp(along, handAlong, side == 0 ? thermo : held));
            float[] gloveFrame = Affine.frame(hand, along, palm, 1f, 1f, 1f);
            if (holding) {
                // Tip the level hand towards the eyes about the body's left-to-right axis (its
                // far side rising), pivoting at the wrist, a little more the higher it's held
                float tipAngle = (COMPASS_TIP + COMPASS_TIP_HIGH * high) * held;
                gloveFrame = Affine.multiply(Affine.translation(hand[0], hand[1], hand[2]), Affine.multiply(Affine.rotationX(-tipAngle),
                        Affine.multiply(Affine.translation(-hand[0], -hand[1], -hand[2]), gloveFrame)));
            }
            Affine.multiply(body, gloveFrame, bones, (GLOVE + side) * 16);
            if (side == 0) {
                poseFingers(body, gloveFrame, thermo);
                if (thermo > 0.01f) {
                    // In the hand, coming up with it
                    float[] frame = Affine.multiply(gloveFrame, THERMOMETER_IN_HAND);
                    Affine.multiply(body, frame, bones, THERMOMETER * 16);
                    // The column: from the bulb up to the reading
                    float t = Math.max(SCALE_LOW - 2f, Math.min(SCALE_HIGH + 1f, shown));
                    float top = SCALE_LOW_Z + (t - SCALE_LOW) / (SCALE_HIGH - SCALE_LOW) * (SCALE_HIGH_Z - SCALE_LOW_Z);
                    float[] column = Affine.multiply(frame, Affine.multiply(Affine.translation(0f, TUBE_OUT, BULB_Z), Affine.scale(1f, 1f, Math.max(0.05f, top - BULB_Z))));
                    Affine.multiply(body, column, bones, MERCURY * 16);
                } else {
                    java.util.Arrays.fill(bones, THERMOMETER * 16, (MERCURY + 1) * 16, 0f);
                }
            }
            if (side == LEFT) poseCompass(body, gloveFrame, held);

            // Legs: stepping when walking, a flutter kick when swimming
            float[] hip = { sign * HIP_SPAN, pelvis[1], back };
            float lift = Math.max(0f, (float) Math.cos(phase)) * 1.6f * walking;
            float[] walkFoot = { hip[0] + sign * 0.2f, -EYE_HEIGHT + 0.8f + lift, back + 0.7f + (float) Math.sin(phase) * 2.4f * walking };
            float kick = (float) Math.sin(strokePhase * 2.3f + side * Math.PI);
            float[] swimFoot = { hip[0] + sign * 0.3f, hip[1] - 9.3f, hip[2] + 1.3f * kick };
            float[] foot = lerp(walkFoot, swimFoot, s);
            float[] knee = Affine.middleJoint(hip, foot, THIGH_LENGTH, SHIN_LENGTH, new float[] { 0f, 0f, 1f });
            limb(body, hip, knee, THIGH_LENGTH, THIGH + side);
            limb(body, knee, foot, SHIN_LENGTH, SHIN + side);
            // Boots stay level with the ground when walking and point back when swimming
            float[] toes = lerp(new float[] { 0f, 0f, 1f }, new float[] { 0f, -1f, 0.25f }, s);
            float[] bootFrame = Affine.frame(foot, toes, lerp(new float[] { 0f, 1f, 0f }, new float[] { 0f, 0f, 1f }, s), 1f, 1f, 1f);
            Affine.multiply(body, bootFrame, bones, (BOOT + side) * 16);
        }
    }

    /** A point at reach from the eyes, at elevation (radians) and a little to the left of the view. */
    private static float[] compassPoint(float reach, float elevation) {
        return new float[] {
            reach * (float) Math.sin(COMPASS_LEFT),
            reach * (float) Math.sin(elevation),
            reach * (float) Math.cos(elevation) * (float) Math.cos(COMPASS_LEFT)
        };
    }

    /**
     * Sets the compass flat on the glove's palm (the palm faces the glove frame's +Y), and
     * points the needle north (world -Z), less its swing. Put away, it shrinks to nothing.
     */
    private void poseCompass(float[] body, float[] glove, float held) {
        if (held <= 0.01f) {
            java.util.Arrays.fill(bones, COMPASS * 16, (NEEDLE + 1) * 16, 0f);
            return;
        }
        float[] forearm = { glove[8], glove[9], glove[10] };
        float[] face = { glove[4], glove[5], glove[6] };
        float[] centre = {
            glove[12] + forearm[0] * COMPASS_ON_PALM + face[0] * PALM_TOP,
            glove[13] + forearm[1] * COMPASS_ON_PALM + face[1] * PALM_TOP,
            glove[14] + forearm[2] * COMPASS_ON_PALM + face[2] * PALM_TOP
        };
        Affine.multiply(body, Affine.frame(centre, face, forearm, 1f, 1f, 1f), bones, COMPASS * 16);

        float[] pivot = Affine.transformPoint(body, centre[0] + face[0] * NEEDLE_HEIGHT,
                centre[1] + face[1] * NEEDLE_HEIGHT, centre[2] + face[2] * NEEDLE_HEIGHT);
        float[] up = Affine.normalise(Affine.transformDirection(body, face[0], face[1], face[2]));
        float[] pointing = { (float) Math.sin(needleAngle), 0f, -(float) Math.cos(needleAngle) };
        // Lay the needle flat on the card
        float along = Affine.dot(pointing, up);
        float[] flat = { pointing[0] - up[0] * along, pointing[1] - up[1] * along, pointing[2] - up[2] * along };
        if (Affine.length(flat) < 1e-4f) flat = Affine.transformDirection(body, forearm[0], forearm[1], forearm[2]);
        float[] needle = Affine.frame(pivot, flat, up, 1f, 1f, 1f);
        System.arraycopy(needle, 0, bones, NEEDLE * 16, 16);
    }

    private void limb(float[] body, float[] from, float[] to, float designLength, int bone) {
        float[] span = Affine.subtract(to, from);
        float[] frame = Affine.frame(from, span, new float[] { 0f, 1f, 0f }, 1f, 1f, Affine.length(span) / designLength);
        Affine.multiply(body, frame, bones, bone * 16);
    }

    /**
     * The right hand's fingers and thumb, from the glove's frame: their gentle resting curve,
     * or (as grip goes from 0 to 1) curled round to hold the thermometer, the fingers in towards
     * the palm and the thumb across to meet them.
     */
    private void poseFingers(float[] body, float[] glove, float grip) {
        float thumbSide = -1f;
        for (int f = 0; f < 4; f++) {
            float x = thumbSide * (0.34f - f * 0.225f);
            float first = FINGER_LENGTHS[f] * 0.58f;
            // Curled as tightly as THERMOMETER_GRIP says (as the hand comes up, grip goes 0 to 1),
            // all slanting down a little as they wrap round (the glove's +x is down the thermometer)
            float curl = THERMOMETER_GRIP[f] * grip;
            float bendFirst = 0.32f + 1.5f * curl, bendSecond = 1.65f * curl;
            float[] knuckle = Affine.multiply(glove, Affine.multiply(Affine.translation(x, 0.02f, PALM_START + PALM_LENGTH - 0.12f),
                    Affine.multiply(Affine.rotationY((0.22f + 0.06f * f) * grip), Affine.rotationX(-bendFirst))));
            Affine.multiply(body, knuckle, bones, (FINGER_BASE + f) * 16);
            float[] middle = Affine.multiply(knuckle, Affine.multiply(Affine.translation(0f, 0f, first * 0.92f), Affine.rotationX(-bendSecond)));
            Affine.multiply(body, middle, bones, (FINGER_TIP + f) * 16);
        }
        // The thumb, curled across to meet the fingers as tightly as THERMOMETER_GRIP says
        float thumbCurl = THERMOMETER_GRIP[4] * grip;
        float swing = thumbSide * (0.75f - 0.45f * thumbCurl);
        float[] thumb = Affine.multiply(glove, Affine.multiply(Affine.translation(thumbSide * 0.42f, 0.04f, PALM_START + 0.3f),
                Affine.multiply(Affine.rotationY(swing), Affine.rotationX(-0.25f - 0.75f * thumbCurl))));
        Affine.multiply(body, thumb, bones, THUMB_BASE * 16);
        // (its joint folds the opposite way to the fingers', bending the tip in onto the glass)
        float[] thumbTip = Affine.multiply(thumb, Affine.multiply(Affine.translation(0f, 0f, 0.37f), Affine.rotationX(0.7f * thumbCurl)));
        Affine.multiply(body, thumbTip, bones, THUMB_TIP * 16);
    }

    private static float[] scaled(float[] v, float k) {
        return new float[] { v[0] * k, v[1] * k, v[2] * k };
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }
}
