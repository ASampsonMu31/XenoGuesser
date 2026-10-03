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
    private static final int BONES = 16;
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
    private float needleAngle, needleSpin;
    private final java.util.Random needleKick = new java.util.Random();

    public void initialise(GL3 gl) {
        shader = new Shader(gl, "assets/shaders/vs_organism.txt", "assets/shaders/fs_organism.txt");
        // In first person the eyes are inside the helmet, so it is left off
        mesh = buildMesh(false).build(gl);
        // The suit's crumpled fabric, shared with the loading screen's spaceman
        if (new java.io.File(LoadingArt.FABRIC).exists()) {
            fabric = TextureLibrary.loadTextureWrap(gl, LoadingArt.FABRIC);
        }
    }

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
            glove(b, GLOVE + side, side == LEFT ? 1f : -1f);
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
    private static void glove(OrganismMesh.Builder b, int bone, float thumbSide) {
        b.bone(bone);
        b.transform(Affine.translation(0f, 0f, 0.3f));
        OrganismParts.shell(b, OrganismMesh.PART_TRIM, 0.75f, 0.92f, 0.8f, 0.3f, 0, 0f, 1f);
        b.transform(Affine.translation(0f, 0f, PALM_START + PALM_LENGTH * 0.5f));
        OrganismParts.shell(b, OrganismMesh.PART_TRIM, PALM_LENGTH, 1.0f, 0.38f, 0.25f, 0, 0f, 0.95f);
        float[] lengths = { 0.72f, 0.8f, 0.76f, 0.6f };   // index to little finger
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
        if (compassTime >= 0f) {
            compassTime += dt;
            if (compassTime > 2f * COMPASS_RAISE + COMPASS_HOLD) compassTime = -1f;
            // The needle is a damped pendulum pulled towards north; its heading is kept in
            // the world, so it holds steady as the body turns under it
            float step = Math.min(dt, 0.05f);
            needleSpin += (-38f * needleAngle - 4.5f * needleSpin) * step;
            needleAngle += needleSpin * step;
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
        shader.setVec3(gl, "eyeColour", new Vector3(0.1f, 0.1f, 0.1f));
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
        mesh.render(gl);
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
        float held = compassBlend();
        float lookDown = Math.max((float) Math.toRadians(-55), Math.min((float) Math.toRadians(15), pitch)) - COMPASS_BELOW;
        float[] compassAt = {
            COMPASS_REACH * (float) Math.sin(COMPASS_LEFT),
            COMPASS_REACH * (float) Math.sin(lookDown),
            COMPASS_REACH * (float) Math.cos(lookDown) * (float) Math.cos(COMPASS_LEFT)
        };

        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? -1f : 1f;
            float phase = walkPhase + side * (float) Math.PI;
            float stroke = strokePhase + side * (float) Math.PI;

            // Arms: swinging opposite the legs when walking, a front crawl when swimming
            float[] shoulder = { sign * SHOULDER_SPAN, -SHOULDER_DROP, back };
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
            if (holding) {
                // Held out palm up, the compass resting on the glove
                float[] reach = Affine.normalise(Affine.subtract(compassAt, shoulder));
                float[] toEyes = Affine.normalise(new float[] { -compassAt[0], -compassAt[1], -compassAt[2] });
                float[] palmUp = Affine.normalise(new float[] { toEyes[0] * 0.5f, 1f + toEyes[1] * 0.5f, toEyes[2] * 0.5f });
                float[] wrist = {
                    compassAt[0] - reach[0] * COMPASS_ON_PALM - palmUp[0] * PALM_TOP,
                    compassAt[1] - reach[1] * COMPASS_ON_PALM - palmUp[1] * PALM_TOP,
                    compassAt[2] - reach[2] * COMPASS_ON_PALM - palmUp[2] * PALM_TOP
                };
                hand = lerp(hand, wrist, held);
                bend = lerp(bend, new float[] { 0.6f, -1f, 0f }, held);
                palm = lerp(palm, palmUp, held);
            }
            float[] elbow = Affine.middleJoint(shoulder, hand, UPPER_ARM_LENGTH, FOREARM_LENGTH, bend);
            limb(body, shoulder, elbow, UPPER_ARM_LENGTH, UPPER_ARM + side);
            limb(body, elbow, hand, FOREARM_LENGTH, FOREARM + side);
            float[] gloveFrame = Affine.frame(hand, Affine.subtract(hand, elbow), palm, 1f, 1f, 1f);
            Affine.multiply(body, gloveFrame, bones, (GLOVE + side) * 16);
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

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }
}
