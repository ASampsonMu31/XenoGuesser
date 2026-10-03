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
    private static final int BONES = 13;

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

    private Shader shader;
    private OrganismMesh mesh;
    private final float[] bones = new float[BONES * 16];

    private float walkPhase;
    private float walking;
    private float strokePhase;
    private float swimBlend;
    private float lastX = Float.NaN, lastZ = Float.NaN;

    public void initialise(GL3 gl) {
        shader = new Shader(gl, "assets/shaders/vs_organism.txt", "assets/shaders/fs_organism.txt");
        OrganismMesh.Builder b = new OrganismMesh.Builder();

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
            b.bone(UPPER_ARM + side).resetTransform();
            OrganismParts.legSegment(b, UPPER_ARM_LENGTH, 0.85f, 0.72f, 0);
            b.bone(FOREARM + side).resetTransform();
            OrganismParts.legSegment(b, FOREARM_LENGTH, 0.72f, 0.62f, 0);
            b.bone(GLOVE + side).transform(Affine.translation(0f, 0f, 0.6f));
            trimSegment(b, 1.5f, 1.0f, 0.6f);
            b.bone(THIGH + side).resetTransform();
            OrganismParts.legSegment(b, THIGH_LENGTH, 1.15f, 0.95f, 0);
            b.bone(SHIN + side).resetTransform();
            OrganismParts.legSegment(b, SHIN_LENGTH, 0.95f, 0.8f, 0);
            // Boot: ankle at the origin, sole on the ground below it, toes forward
            b.bone(BOOT + side).transform(Affine.translation(0f, -0.15f, 0.55f));
            trimSegment(b, 2.8f, 1.5f, 1.3f);
        }
        mesh = b.build(gl);
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
        pose(eye, heading);

        Matrix4 projection = Matrix4Transform.perspective(45, aspect, 0.3f, 400f);
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
        shader.setVec3(gl, "baseColour", new Vector3(0.86f, 0.86f, 0.83f));
        shader.setVec3(gl, "bellyColour", new Vector3(0.78f, 0.78f, 0.76f));
        shader.setVec3(gl, "accentColour", new Vector3(0.95f, 0.45f, 0.1f));
        shader.setVec3(gl, "limbColour", new Vector3(0.84f, 0.84f, 0.81f));
        shader.setVec3(gl, "eyeColour", new Vector3(0.1f, 0.1f, 0.1f));
        shader.setVec3(gl, "trimColour", new Vector3(0.22f, 0.23f, 0.25f));
        shader.setInt(gl, "patternType", 0);
        shader.setFloat(gl, "patternScale", 2.0f);
        shader.setFloat(gl, "gloss", 0.35f);
        shader.setFloat(gl, "waterLevel", waterLevel);
        shader.setVec3(gl, "waterTint", new Vector3(waterTint[0], waterTint[1], waterTint[2]));
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

        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? -1f : 1f;
            float phase = walkPhase + side * (float) Math.PI;
            float stroke = strokePhase + side * (float) Math.PI;

            // Arms: swinging opposite the legs when walking, a front crawl when swimming
            float[] shoulder = { sign * SHOULDER_SPAN, -SHOULDER_DROP, back };
            float swing = -(float) Math.sin(phase) * walking;
            float[] walkHand = { shoulder[0] + sign * 0.5f, shoulder[1] - 6.1f + 0.4f * Math.abs(swing), shoulder[2] + swing * 2.0f + 0.3f };
            float[] swimHand = { shoulder[0] * 0.45f, shoulder[1] + 6.3f * (float) Math.cos(stroke), shoulder[2] + 2.6f * (float) Math.sin(stroke) };
            float[] hand = lerp(walkHand, swimHand, s);
            float[] elbow = Affine.middleJoint(shoulder, hand, UPPER_ARM_LENGTH, FOREARM_LENGTH,
                    new float[] { sign * 0.4f, 0f, -1f });
            limb(body, shoulder, elbow, UPPER_ARM_LENGTH, UPPER_ARM + side);
            limb(body, elbow, hand, FOREARM_LENGTH, FOREARM + side);
            float[] gloveFrame = Affine.frame(hand, Affine.subtract(hand, elbow), new float[] { 0f, 0f, 1f }, 1f, 1f, 1f);
            Affine.multiply(body, gloveFrame, bones, (GLOVE + side) * 16);

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

    private void limb(float[] body, float[] from, float[] to, float designLength, int bone) {
        float[] span = Affine.subtract(to, from);
        float[] frame = Affine.frame(from, span, new float[] { 0f, 1f, 0f }, 1f, 1f, Affine.length(span) / designLength);
        Affine.multiply(body, frame, bones, bone * 16);
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }
}
