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
 * own space the ground under its middle is the origin, +Y is up and the stairs run down
 * towards +Z.
 */
public class LandingPod {

    private static final int HULL = 0, STAIRS = 1, BONES = 2;

    // The hull: a cone from the heat shield up to a rounded top
    private static final float HULL_BASE = 14f, HULL_TOP = 58f, HULL_RADIUS = 26f, TOP_RADIUS = 14f;
    // The hatch platform's floor, and where the stairs leave it
    private static final float PLATFORM_HEIGHT = 17f, PLATFORM_FRONT = 27f;
    private static final float STAIR_WIDTH = 11f, STAIR_RUN = 5f, STAIR_LENGTH = 20f;
    private static final int STEPS = 4;
    // Where the player starts: on the third step down
    public static final float SPAWN_DISTANCE = PLATFORM_FRONT + STAIR_RUN * 2.5f;
    // How far the pod's solid outline reaches from its middle (its feet)
    private static final float KEEP_OUT = 40f;

    private Shader shader;
    private OrganismMesh mesh;
    private final float[] bones = new float[BONES * 16];
    private boolean placed;
    private float x, z, ground, footGround, stairScale, heading;

    public void initialise(GL3 gl) {
        shader = new Shader(gl, "assets/shaders/vs_organism.txt", "assets/shaders/fs_organism.txt");
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
        b.transform(Affine.frame(new float[] { doorAt[0], doorAt[1], doorAt[2] - 0.4f }, doorOut, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
        OrganismParts.shell(b, OrganismMesh.PART_METAL, 1.4f, 13.5f, 19.5f, 0.15f, 0, 0f, 1f);
        b.transform(Affine.frame(doorAt, doorOut, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
        OrganismParts.shell(b, OrganismMesh.PART_TRIM, 1.6f, 11f, 17f, 0.15f, 0, 0f, 1f);
        // The platform outside it
        b.resetTransform().part(OrganismMesh.PART_METAL);
        b.box(0f, PLATFORM_HEIGHT - 0.5f, (PLATFORM_FRONT + 20.5f) * 0.5f, STAIR_WIDTH + 1f, 1f, PLATFORM_FRONT - 20.5f);
        // Landing legs: a strut from the shoulder out to a foot pad, braced from the base
        for (int leg = 0; leg < 4; leg++) {
            float a = (float) (Math.PI * 0.25 + leg * Math.PI * 0.5);
            float sx = (float) Math.sin(a), sz = (float) Math.cos(a);
            float[] top = { sx * 20f, 22f, sz * 20f };
            float[] foot = { sx * 36f, 2f, sz * 36f };
            float[] brace = { sx * 24f, 11f, sz * 24f };
            strut(b, OrganismMesh.PART_BRASS, top, foot, 1.5f, 1.1f);
            strut(b, OrganismMesh.PART_BRASS, brace, midpoint(top, foot), 0.9f, 0.8f);
            b.transform(Affine.multiply(Affine.translation(foot[0], 0f, foot[2]), upright)).part(OrganismMesh.PART_BRASS);
            b.lathe(20, 8, puck(4f, 1.6f));
        }
        // Manoeuvring thrusters round the shoulder, and an aerial on top
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
            strut(b, OrganismMesh.PART_METAL, new float[] { sxp, PLATFORM_HEIGHT + 9f, -0.5f }, new float[] { sxp, 9f, STAIR_LENGTH }, 0.35f, 0.35f);
            for (float along : new float[] { 0f, STAIR_LENGTH * 0.5f, STAIR_LENGTH }) {
                float base = PLATFORM_HEIGHT * (1f - along / (STAIR_LENGTH + 1f));
                strut(b, OrganismMesh.PART_METAL, new float[] { sxp, base, along }, new float[] { sxp, base + 9f, along }, 0.3f, 0.3f);
            }
        }
        b.resetTransform();
        return b;
    }

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
     * Stands the pod so that a player at (spawnX, spawnZ) is on its stairs, facing along
     * (dirX, dirZ) away from it.
     */
    public void place(float spawnX, float spawnZ, float dirX, float dirZ, java.util.function.BiFunction<Float, Float, Float> terrain) {
        float len = (float) Math.hypot(dirX, dirZ);
        if (len < 1e-4f) { dirX = 0f; dirZ = 1f; len = 1f; }
        dirX /= len;
        dirZ /= len;
        heading = (float) Math.atan2(dirX, dirZ);
        x = spawnX - dirX * SPAWN_DISTANCE;
        z = spawnZ - dirZ * SPAWN_DISTANCE;
        // Settled on the lowest of its feet, so none of them hangs in the air
        ground = Float.MAX_VALUE;
        for (int leg = 0; leg < 4; leg++) {
            float a = (float) (Math.PI * 0.25 + leg * Math.PI * 0.5) + heading;
            ground = Math.min(ground, terrain.apply(x + (float) Math.sin(a) * 36f, z + (float) Math.cos(a) * 36f));
        }
        ground = Math.min(ground, terrain.apply(x, z));
        float reach = PLATFORM_FRONT + STAIR_LENGTH;
        footGround = terrain.apply(x + dirX * reach, z + dirZ * reach);
        // The stairs stretch or squash to meet the ground at their foot
        stairScale = Math.max(0.35f, Math.min(2.5f, (ground + PLATFORM_HEIGHT - footGround) / PLATFORM_HEIGHT));
        footGround = ground + PLATFORM_HEIGHT - stairScale * PLATFORM_HEIGHT;

        float[] world = Affine.multiply(Affine.translation(x, ground, z), Affine.rotationY(heading));
        System.arraycopy(world, 0, bones, HULL * 16, 16);
        float[] stairs = Affine.multiply(world, Affine.multiply(Affine.translation(0f, footGround - ground, PLATFORM_FRONT),
                Affine.scale(1f, stairScale, 1f)));
        System.arraycopy(stairs, 0, bones, STAIRS * 16, 16);
        placed = true;
        buildObstacles();
    }

    // The pod's solid outline in the world: {kind, ax, az, bx, bz, size} as Collision gathers them
    private final List<float[]> obstacles = new ArrayList<>();

    /**
     * A ring round the hull with a gap where the stairs meet it, rails down both sides of the
     * stairs, the hatch closed off at the top, and the four feet.
     */
    private void buildObstacles() {
        obstacles.clear();
        int sides = 24;
        float ring = HULL_RADIUS + 1.5f;
        float gap = (float) Math.asin((STAIR_WIDTH * 0.5f + 0.5f) / ring);
        for (int i = 0; i < sides; i++) {
            float a0 = (float) (i * Math.PI * 2 / sides), a1 = (float) ((i + 1) * Math.PI * 2 / sides);
            float mid = (float) Math.atan2(Math.sin((a0 + a1) * 0.5f), Math.cos((a0 + a1) * 0.5f));
            if (Math.abs(mid) < gap) continue;
            addSegment(new float[] { (float) Math.sin(a0) * ring, (float) Math.cos(a0) * ring },
                    new float[] { (float) Math.sin(a1) * ring, (float) Math.cos(a1) * ring }, 0.8f);
        }
        float side = STAIR_WIDTH * 0.5f + 0.6f;
        for (float sign : new float[] { -1f, 1f }) {
            addSegment(new float[] { sign * side, 18f }, new float[] { sign * side, PLATFORM_FRONT + STAIR_LENGTH }, 0.4f);
        }
        addSegment(new float[] { -side, 18f }, new float[] { side, 18f }, 0.6f);
        for (int leg = 0; leg < 4; leg++) {
            float a = (float) (Math.PI * 0.25 + leg * Math.PI * 0.5);
            float[] w = toWorld((float) Math.sin(a) * 36f, (float) Math.cos(a) * 36f);
            obstacles.add(new float[] { 1f, w[0], w[1], 0f, 0f, 4f });
        }
    }

    private void addSegment(float[] a, float[] b, float halfWidth) {
        float[] wa = toWorld(a[0], a[1]), wb = toWorld(b[0], b[1]);
        obstacles.add(new float[] { 0f, wa[0], wa[1], wb[0], wb[1], halfWidth });
    }

    private float[] toWorld(float across, float along) {
        float s = (float) Math.sin(heading), c = (float) Math.cos(heading);
        return new float[] { x + across * c + along * s, z - across * s + along * c };
    }

    /** The pod's solid outline near a point, for collision. */
    public void obstaclesNear(float px, float pz, float reach, Collision.Sink sink) {
        if (!placed) return;
        float r = reach + KEEP_OUT + 30f;
        if (Math.abs(px - x) > r || Math.abs(pz - z) > r) return;
        for (float[] o : obstacles) {
            if (o[0] == 0f) sink.segment(o[1], o[2], o[3], o[4], o[5]);
            else sink.circle(o[1], o[2], o[5]);
        }
    }

    /** Where (px, pz) lies in the pod's own space: {across, along}. */
    private float[] local(float px, float pz) {
        float dx = px - x, dz = pz - z;
        float s = (float) Math.sin(heading), c = (float) Math.cos(heading);
        return new float[] { dx * c - dz * s, dx * s + dz * c };
    }

    /** The height of the pod's stairs or platform underfoot at (px, pz), or -infinity off them. */
    public float floorAt(float px, float pz) {
        if (!placed) return Float.NEGATIVE_INFINITY;
        float[] l = local(px, pz);
        if (Math.abs(l[0]) > STAIR_WIDTH * 0.5f + 0.5f) return Float.NEGATIVE_INFINITY;
        if (l[1] >= 18f && l[1] < PLATFORM_FRONT) return ground + PLATFORM_HEIGHT;
        float along = l[1] - PLATFORM_FRONT;
        if (along < 0f || along >= STAIR_LENGTH) return Float.NEGATIVE_INFINITY;
        int step = (int) (along / STAIR_RUN);
        float top = PLATFORM_HEIGHT - PLATFORM_HEIGHT / (STEPS + 1) * (step + 1);
        return footGround + top * stairScale;
    }

    public float x() { return x; }
    public float z() { return z; }
    public float ground() { return ground; }
    public float heading() { return heading; }

    public void render(GL3 gl, Matrix4 viewProjection, Vector3 viewPos, Vector3 sunPos, float[] sunColour,
                       Vector3 ambient, Matrix4 skyRotation, Texture sky) {
        if (mesh == null || !placed) return;
        shader.use(gl);
        shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "bones"), BONES, false, bones, 0);
        shader.setVec3(gl, "viewPos", viewPos);
        shader.setVec3(gl, "sunPos", sunPos);
        shader.setVec3(gl, "sunColour", new Vector3(sunColour[0], sunColour[1], sunColour[2]));
        shader.setVec3(gl, "ambientLight", ambient);
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
        shader.setVec3(gl, "trimColour", new Vector3(0.11f, 0.115f, 0.125f));
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
