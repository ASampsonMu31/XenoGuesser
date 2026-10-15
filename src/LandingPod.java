import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;
import java.util.ArrayList;
import java.util.List;

/**
 * The landing pod that brought the player down: a white capsule on gold landing legs, its
 * hatch open, with a short staircase down to the ground. Each round it stands where the
 * player landed, and the player starts on its stairs, looking out.
 *
 * Built from the organism parts library and drawn with the organism shader. In the pod's
 * own space (a SphereFrame where it stands, D3) the ground under its middle is the origin,
 * +Y is up and the stairs run down towards +Z.
 */
public class LandingPod {

    // Its bones: the hull (tilted to the ground), the stairs, and each landing leg (stretched or
    // squashed from its shoulder so its foot meets the ground)
    private static final int HULL = 0, STAIRS = 1, LEG = 2, BONES = LEG + 4;
    // On a slope it leans this share of the way to the ground's angle, no further than this
    private static final float TILT_SHARE = 0.75f, MOST_TILT = (float) Math.toRadians(15.0);
    // Each leg can lengthen or shorten this much (world units) to put its foot on the ground
    private static final float MOST_LEG_STRETCH = 8f;
    // Where a leg's shoulder is (its height), and its foot (out from the middle, along each axis)
    private static final float LEG_SHOULDER = 22f, FOOT_OUT = (float) (36.0 * Math.sin(Math.PI * 0.25));

    // The hull: a cone from the heat shield up to a rounded top
    private static final float HULL_BASE = 14f, HULL_TOP = 58f, HULL_RADIUS = 26f, TOP_RADIUS = 14f;
    // The hatch platform's floor, and where the stairs leave it
    private static final float PLATFORM_HEIGHT = 17f, PLATFORM_FRONT = 27f;
    private static final float STAIR_WIDTH = 17f, STAIR_RUN = 5f, STAIR_LENGTH = 20f;
    private static final int STEPS = 4;
    // Where the player starts: on the third step down
    public static final float SPAWN_DISTANCE = PLATFORM_FRONT + STAIR_RUN * 2.5f;
    // How far the pod's solid outline reaches from its middle (its feet)
    private static final float KEEP_OUT = 40f;

    private Shader shader;
    private OrganismMesh mesh;
    private final float[] bones = new float[BONES * 16];
    private boolean placed;
    private SphereFrame frame;
    // The altitude of the pod's origin (under its middle), and of the ground at the stairs' foot
    private double ground, footGround;
    private float stairScale;

    public void initialise(GL3 gl) {
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_organism.txt", GamePaths.HOME + "assets/shaders/fs_organism.txt");
        mesh = buildMesh().build(gl);
    }

    public void dispose(GL3 gl) {
        if (mesh != null) mesh.dispose(gl);
    }

    static OrganismMesh.Builder buildMesh() {
        OrganismMesh.Builder b = new OrganismMesh.Builder();
        float[] upright = Affine.rotationX((float) -Math.PI / 2);   // a lathe's +Z becomes +Y

        // Heat shield
        b.bone(HULL).transform(Affine.multiply(Affine.translation(0f, 10.5f, 0f), upright)).part(OrganismMesh.PART_TRIM);
        b.lathe(40, 12, puck(HULL_RADIUS + 1.2f, 3.6f));
        // Hull
        b.transform(upright).part(OrganismMesh.PART_BODY);
        b.lathe(48, 28, (t, out) -> {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = hullHeightAt(t);
            out[3] = out[4] = hullRadiusAt(t);
        });
        // Orange bands round the hull
        for (float[] band : new float[][] { { 29f, 32f }, { 47f, 48.5f } }) {
            b.transform(upright).part(OrganismMesh.PART_SKIN);
            b.lathe(48, 2, (t, out) -> {
                float y = band[0] + t * (band[1] - band[0]);
                out[0] = 0f;
                out[1] = 0f;
                out[2] = y;
                out[3] = out[4] = radiusAtHeight(y) + 0.3f;
            });
        }
        // Portholes on the sides and back, framed in metal
        for (float angle : new float[] { (float) Math.PI * 0.5f, (float) Math.PI, (float) Math.PI * 1.5f }) {
            porthole(b, angle, 40f);
        }
        // The open hatch: a dark, unlit doorway in a metal frame above the platform
        float doorY = PLATFORM_HEIGHT + 8.5f;
        float[] doorOut = slopeNormal(0f, doorY);
        float[] doorAt = { 0f, doorY, radiusAtHeight(doorY) - 0.4f };
        b.transform(Affine.frame(new float[] { doorAt[0], doorAt[1], doorAt[2] - 0.9f }, doorOut, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
        OrganismParts.shell(b, OrganismMesh.PART_METAL, 1.0f, 13.5f, 19.5f, 0.15f, 0, 0f, 1f);
        b.transform(Affine.frame(doorAt, doorOut, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
        OrganismParts.shell(b, OrganismMesh.PART_DARK, 2.6f, 11f, 17f, 0.15f, 0, 0f, 1f);
        // The platform outside it
        b.resetTransform().part(OrganismMesh.PART_METAL);
        b.box(0f, PLATFORM_HEIGHT - 0.5f, (PLATFORM_FRONT + 20.5f) * 0.5f, STAIR_WIDTH + 1f, 1f, PLATFORM_FRONT - 20.5f);
        // Landing legs: a strut from the shoulder out to a foot pad, braced from the base
        for (int leg = 0; leg < 4; leg++) {
            b.bone(LEG + leg);
            float a = (float) (Math.PI * 0.25 + leg * Math.PI * 0.5);
            float sx = (float) Math.sin(a), sz = (float) Math.cos(a);
            float[] top = { sx * 20f, LEG_SHOULDER, sz * 20f };
            float[] foot = { sx * 36f, 2f, sz * 36f };
            float[] brace = { sx * 24f, 11f, sz * 24f };
            strut(b, OrganismMesh.PART_BRASS, top, foot, 1.5f, 1.1f);
            strut(b, OrganismMesh.PART_BRASS, brace, midpoint(top, foot), 0.9f, 0.8f);
            b.transform(Affine.multiply(Affine.translation(foot[0], 0f, foot[2]), upright)).part(OrganismMesh.PART_BRASS);
            b.lathe(20, 8, puck(4f, 1.6f));
        }
        // Manoeuvring thrusters round the shoulder, and an aerial on top
        b.bone(HULL);
        for (int i = 0; i < 4; i++) {
            float a = (float) (i * Math.PI * 0.5);
            float y = 52f;
            float r = radiusAtHeight(y);
            float[] at = { (float) Math.sin(a) * r, y, (float) Math.cos(a) * r };
            b.transform(Affine.frame(at, new float[] { (float) Math.sin(a), -0.2f, (float) Math.cos(a) }, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
            OrganismParts.limb(b, OrganismMesh.PART_TRIM, 3.2f, 1.6f, 1.1f);
        }
        strut(b, OrganismMesh.PART_METAL, new float[] { 3f, HULL_TOP - 1f, -2f }, new float[] { 3f, HULL_TOP + 14f, -2f }, 0.35f, 0.2f);
        b.transform(Affine.translation(3f, HULL_TOP + 14f, -2f));
        OrganismParts.shell(b, OrganismMesh.PART_SKIN, 1.6f, 1.6f, 1.6f, 0.5f, 0, 0f, 1f);

        // The stairs, in their own space: from the platform edge (y = PLATFORM_HEIGHT) down to the ground (y = 0)
        b.bone(STAIRS).resetTransform().part(OrganismMesh.PART_METAL);
        float rise = PLATFORM_HEIGHT / (STEPS + 1);
        for (int i = 0; i < STEPS; i++) {
            float top = PLATFORM_HEIGHT - rise * (i + 1);
            b.box(0f, top - 0.4f, STAIR_RUN * (i + 0.5f), STAIR_WIDTH, 0.8f, STAIR_RUN);
        }
        for (float side : new float[] { -1f, 1f }) {
            float sxp = side * (STAIR_WIDTH * 0.5f + 0.4f);
            // Stringer under the treads, and a handrail on posts
            strut(b, OrganismMesh.PART_METAL, new float[] { sxp, PLATFORM_HEIGHT - 0.5f, 0f }, new float[] { sxp, 0f, STAIR_LENGTH + 1f }, 0.55f, 0.55f);
            // (tall enough to be seen from the stairs, so it's clear what's in the way)
            strut(b, OrganismMesh.PART_METAL, new float[] { sxp, PLATFORM_HEIGHT + RAIL_HEIGHT, -0.5f }, new float[] { sxp, RAIL_HEIGHT, STAIR_LENGTH }, 0.45f, 0.45f);
            strut(b, OrganismMesh.PART_METAL, new float[] { sxp, PLATFORM_HEIGHT + RAIL_HEIGHT * 0.5f, -0.5f }, new float[] { sxp, RAIL_HEIGHT * 0.5f, STAIR_LENGTH }, 0.3f, 0.3f);
            for (float along : new float[] { 0f, STAIR_LENGTH * 0.25f, STAIR_LENGTH * 0.5f, STAIR_LENGTH * 0.75f, STAIR_LENGTH }) {
                float base = PLATFORM_HEIGHT * (1f - along / (STAIR_LENGTH + 1f));
                strut(b, OrganismMesh.PART_METAL, new float[] { sxp, base, along }, new float[] { sxp, base + RAIL_HEIGHT, along }, 0.35f, 0.35f);
            }
        }
        b.resetTransform();
        return b;
    }

    // How high the stairs' handrails stand above the treads
    private static final float RAIL_HEIGHT = 17f;

    private static float hullHeightAt(float t) {
        if (t < 0.8f) return HULL_BASE + (HULL_TOP - 9f - HULL_BASE) * (t / 0.8f);
        float a = (t - 0.8f) / 0.2f * (float) Math.PI * 0.5f;
        return HULL_TOP - 9f + 9f * (float) Math.sin(a);
    }

    private static float hullRadiusAt(float t) {
        if (t < 0.8f) return HULL_RADIUS + (TOP_RADIUS - HULL_RADIUS) * (t / 0.8f);
        float a = (t - 0.8f) / 0.2f * (float) Math.PI * 0.5f;
        return TOP_RADIUS * (float) Math.cos(a);
    }

    /** The hull's radius at a height on its conical part. */
    private static float radiusAtHeight(float y) {
        float f = Math.max(0f, Math.min(1f, (y - HULL_BASE) / (HULL_TOP - 9f - HULL_BASE)));
        return HULL_RADIUS + (TOP_RADIUS - HULL_RADIUS) * f;
    }

    /** The outward normal of the conical hull at an angle round it (0 towards +Z). */
    private static float[] slopeNormal(float angle, float y) {
        float lean = (HULL_RADIUS - TOP_RADIUS) / (HULL_TOP - 9f - HULL_BASE);
        return Affine.normalise(new float[] { (float) Math.sin(angle), lean, (float) Math.cos(angle) });
    }

    private static void porthole(OrganismMesh.Builder b, float angle, float y) {
        float r = radiusAtHeight(y);
        float[] out = slopeNormal(angle, y);
        float[] at = { (float) Math.sin(angle) * r, y, (float) Math.cos(angle) * r };
        b.transform(Affine.frame(at, out, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
        OrganismParts.shell(b, OrganismMesh.PART_METAL, 1.4f, 7.2f, 7.2f, 0.3f, 0, 0f, 1f);
        float[] glass = { at[0] + out[0] * 0.25f, at[1] + out[1] * 0.25f, at[2] + out[2] * 0.25f };
        b.transform(Affine.frame(glass, out, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
        OrganismParts.shell(b, OrganismMesh.PART_EYE, 1.3f, 5.4f, 5.4f, 0.3f, 0, 0f, 1f);
    }

    private static void strut(OrganismMesh.Builder b, int part, float[] from, float[] to, float r0, float r1) {
        float[] span = Affine.subtract(to, from);
        float[] hint = Math.abs(span[1]) > 0.9f * Affine.length(span) ? new float[] { 0f, 0f, 1f } : new float[] { 0f, 1f, 0f };
        b.transform(Affine.frame(from, span, hint, 1f, 1f, 1f));
        OrganismParts.limb(b, part, Affine.length(span), r0, r1);
    }

    private static float[] midpoint(float[] a, float[] c) {
        return new float[] { (a[0] + c[0]) * 0.5f, (a[1] + c[1]) * 0.5f, (a[2] + c[2]) * 0.5f };
    }

    /** A short round box along Z from 0 to depth, flat-faced. */
    private static OrganismMesh.Profile puck(float radius, float depth) {
        float dome = Math.min(depth * 0.08f, 0.05f);
        return (t, out) -> {
            float y, r;
            if (t < 0.25f) {
                r = radius * t / 0.25f;
                y = -dome * (1f - t / 0.25f);
            } else if (t < 0.75f) {
                r = radius;
                y = depth * (t - 0.25f) / 0.5f;
            } else {
                r = radius * (1f - t) / 0.25f;
                y = depth + dome * (t - 0.75f) / 0.25f;
            }
            out[0] = 0f;
            out[1] = 0f;
            out[2] = y;
            out[3] = r;
            out[4] = r;
        };
    }

    /**
     * Stands the pod so that a player at spawn is on its stairs, facing along heading (D10:
     * radians from east towards south at spawn) away from it. terrain gives the ground's
     * height at a place. On a slope it leans most of the way to the ground (TILT_SHARE, at
     * most MOST_TILT), each leg lengthened or shortened a little (MOST_LEG_STRETCH) so its foot
     * is on the ground, and the stairs run from its platform down to the ground at their foot.
     */
    public void place(SpherePosition spawn, double heading, java.util.function.ToDoubleFunction<SpherePosition> terrain) {
        SpherePosition centre = SphereMath.atAltitude(SphereMath.move(spawn, heading + Math.PI, SPAWN_DISTANCE), 0.0);
        // The pod's own space: its local z (the way the stairs run) towards the spawn point
        SphereFrame level = SphereFrame.at(centre, SphereMath.heading(centre, spawn) - Math.PI * 0.5);
        // The ground under each foot, and its slope across the pod (rise along x and along z)
        double[] footX = new double[4], footZ = new double[4], footGround = new double[4];
        double gx = 0, gz = 0;
        for (int leg = 0; leg < 4; leg++) {
            double a = Math.PI * 0.25 + leg * Math.PI * 0.5;
            footX[leg] = Math.signum(Math.sin(a)) * FOOT_OUT;
            footZ[leg] = Math.signum(Math.cos(a)) * FOOT_OUT;
            footGround[leg] = terrain.applyAsDouble(level.toSphere(footX[leg], 0.0, footZ[leg]));
            gx += footGround[leg] * Math.signum(footX[leg]);
            gz += footGround[leg] * Math.signum(footZ[leg]);
        }
        gx /= 4.0 * FOOT_OUT;
        gz /= 4.0 * FOOT_OUT;
        // Leaning most of the way to it (each way no further than the most it leans overall)
        double slope = Math.atan(Math.hypot(gx, gz)), lean = Math.min(MOST_TILT, slope * TILT_SHARE);
        double share = slope > 1e-6 ? lean / slope : 0.0;
        float[] tilt = Affine.multiply(Affine.rotationZ((float) (Math.atan(gx) * share)), Affine.rotationX((float) (-Math.atan(gz) * share)));
        // Standing where its feet, as it leans, are nearest the ground all round; each leg
        // stretched or squashed (as far as it can be) to make up the rest
        double[] rest = new double[4];
        double highest = -Double.MAX_VALUE, lowest = Double.MAX_VALUE;
        for (int leg = 0; leg < 4; leg++) {
            float[] foot = Affine.transformPoint(tilt, (float) footX[leg], 0f, (float) footZ[leg]);
            rest[leg] = footGround[leg] - foot[1];
            highest = Math.max(highest, rest[leg]);
            lowest = Math.min(lowest, rest[leg]);
        }
        ground = (highest + lowest) * 0.5;
        for (int leg = 0; leg < 4; leg++) {
            // (lengthened as far down as its ground is below where it'd reach)
            double stretch = Math.max(-MOST_LEG_STRETCH, Math.min(MOST_LEG_STRETCH, ground - rest[leg]));
            float s = (float) ((LEG_SHOULDER + stretch) / LEG_SHOULDER);
            float[] bone = Affine.multiply(tilt, Affine.multiply(Affine.translation(0f, LEG_SHOULDER, 0f),
                    Affine.multiply(Affine.scale(1f, s, 1f), Affine.translation(0f, -LEG_SHOULDER, 0f))));
            System.arraycopy(bone, 0, bones, (LEG + leg) * 16, 16);
        }
        frame = level.movedTo(SphereMath.atAltitude(centre, ground));
        System.arraycopy(tilt, 0, bones, HULL * 16, 16);
        // The stairs: from the platform's edge, as it leans, down to the ground at their foot
        float[] edge = Affine.transformPoint(tilt, 0f, PLATFORM_HEIGHT, PLATFORM_FRONT);
        platformHeight = edge[1];
        double footAt = terrain.applyAsDouble(level.toSphere(edge[0], 0.0, edge[2] + STAIR_LENGTH));
        stairScale = (float) Math.max(0.35, Math.min(2.5, (ground + platformHeight - footAt) / PLATFORM_HEIGHT));
        this.footGround = ground + platformHeight - stairScale * PLATFORM_HEIGHT;
        float[] stairs = Affine.multiply(Affine.translation(edge[0], (float) (this.footGround - ground), edge[2]),
                Affine.scale(1f, stairScale, 1f));
        System.arraycopy(stairs, 0, bones, STAIRS * 16, 16);
        placed = true;
        buildObstacles();
    }

    // How high the hatch's platform stands above the pod's origin, as it leans
    private float platformHeight = PLATFORM_HEIGHT;

    // The pod's solid outline in the world: segments {a, b} with their half widths, and the four feet
    private final List<SpherePosition[]> segments = new ArrayList<>();
    private final List<Double> segmentWidths = new ArrayList<>();
    private final List<SpherePosition> feet = new ArrayList<>();

    /**
     * A ring round the hull with a gap where the stairs meet it, rails down both sides of the
     * stairs, the hatch closed off at the top, and the four feet.
     */
    private void buildObstacles() {
        segments.clear();
        segmentWidths.clear();
        feet.clear();
        int sides = 24;
        float ring = HULL_RADIUS + 1.5f;
        float gap = (float) Math.asin((STAIR_WIDTH * 0.5f + 0.5f) / ring);
        for (int i = 0; i < sides; i++) {
            float a0 = (float) (i * Math.PI * 2 / sides), a1 = (float) ((i + 1) * Math.PI * 2 / sides);
            float mid = (float) Math.atan2(Math.sin((a0 + a1) * 0.5f), Math.cos((a0 + a1) * 0.5f));
            if (Math.abs(mid) < gap) continue;
            addSegment(Math.sin(a0) * ring, Math.cos(a0) * ring, Math.sin(a1) * ring, Math.cos(a1) * ring, 0.8);
        }
        float side = STAIR_WIDTH * 0.5f + 0.6f;
        for (float sign : new float[] { -1f, 1f }) {
            addSegment(sign * side, 18.0, sign * side, PLATFORM_FRONT + STAIR_LENGTH, 0.4);
        }
        addSegment(-side, 18.0, side, 18.0, 0.6);
        for (int leg = 0; leg < 4; leg++) {
            double a = Math.PI * 0.25 + leg * Math.PI * 0.5;
            feet.add(frame.toWorld(Math.sin(a) * 36.0, 0.0, Math.cos(a) * 36.0));
        }
    }

    /** A segment from (across, along) a to b in the pod's own space. */
    private void addSegment(double ax, double az, double bx, double bz, double halfWidth) {
        segments.add(new SpherePosition[] { frame.toWorld(ax, 0.0, az), frame.toWorld(bx, 0.0, bz) });
        segmentWidths.add(halfWidth);
    }

    /** The pod's solid outline near a place, for collision. */
    public void obstaclesNear(SpherePosition centre, double reach, GeodesicCollision.Sink sink) {
        if (!placed || SphereMath.distance(centre, frame.origin()) > reach + KEEP_OUT + 30.0) return;
        for (int i = 0; i < segments.size(); i++) {
            sink.segment(segments.get(i)[0], segments.get(i)[1], segmentWidths.get(i));
        }
        for (SpherePosition foot : feet) sink.circle(foot, 4.0);
    }

    /** The altitude of the pod's stairs or platform underfoot at a place, or -infinity off them. */
    public double floorAt(SpherePosition position) {
        if (!placed) return Double.NEGATIVE_INFINITY;
        double[] l = frame.toLocal(position);
        if (Math.abs(l[0]) > STAIR_WIDTH * 0.5f + 0.5f) return Double.NEGATIVE_INFINITY;
        if (l[2] >= 18f && l[2] < PLATFORM_FRONT) return ground + platformHeight;
        double along = l[2] - PLATFORM_FRONT;
        if (along < 0.0 || along >= STAIR_LENGTH) return Double.NEGATIVE_INFINITY;
        int step = (int) (along / STAIR_RUN);
        float top = PLATFORM_HEIGHT - PLATFORM_HEIGHT / (STEPS + 1) * (step + 1);
        return footGround + top * stairScale;
    }

    /** How far the pod, its feet and its stairs reach from its middle. */
    public static final float REACH = PLATFORM_FRONT + STAIR_LENGTH + 2f;

    /** Whether a place is within margin of the pod's ground plan. */
    public boolean covers(SpherePosition position, double margin) {
        return placed && SphereMath.distance(position, frame.origin()) < Math.max(40f, REACH) + margin;
    }

    /** The pod's own frame: origin under its middle on the ground, local z the way its stairs run. */
    public SphereFrame frame() {
        return frame;
    }

    /** The altitude of the ground the pod stands on. */
    public double ground() {
        return ground;
    }

    public void render(GL3 gl, Matrix4 viewProjection, SpherePosition eye, Vector3 sunDirection, float[] sunColour,
                       Vector3 ambient, Matrix4 skyRotation, Texture sky) {
        if (mesh == null || !placed) return;
        SphereRender.configure(gl, shader, viewProjection, sunDirection, sunColour, ambient, skyRotation);
        SphereRender.setAnchor(gl, shader, frame.modelMatrix(1f, eye));
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "bones"), BONES, false, bones, 0);
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            shader.setInt(gl, "skyTexture", 2);
        }
        // A white hull, faintly scorched, with orange bands, gold legs and gunmetal fittings
        shader.setVec3(gl, "baseColour", new Vector3(0.9f, 0.9f, 0.88f));
        shader.setVec3(gl, "bellyColour", new Vector3(0.86f, 0.86f, 0.84f));
        shader.setVec3(gl, "accentColour", new Vector3(0.62f, 0.6f, 0.56f));
        shader.setVec3(gl, "limbColour", new Vector3(0.95f, 0.45f, 0.1f));
        shader.setVec3(gl, "eyeColour", new Vector3(0.05f, 0.06f, 0.08f));
        shader.setVec3(gl, "trimColour", new Vector3(0.2f, 0.21f, 0.23f));
        shader.setInt(gl, "patternType", 3);
        shader.setFloat(gl, "patternScale", 3.0f);
        shader.setFloat(gl, "gloss", 0.35f);
        shader.setFloat(gl, "waterLevel", -1e9f);
        shader.setVec3(gl, "waterTint", new Vector3(0f, 0f, 0f));
        shader.setFloat(gl, "detailAmount", 0f);
        gl.glDisable(GL.GL_CULL_FACE);
        mesh.render(gl);
        gl.glEnable(GL.GL_CULL_FACE);
    }
}
