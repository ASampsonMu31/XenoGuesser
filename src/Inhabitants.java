import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;

/**
 * The planet's dominant species: the people who built the houses and roads. Their body is
 * generated afresh for every world — an upright, roughly humanoid mammal whose proportions,
 * head, ears or horns, number of arms, legs and tail all vary — and they dress in the
 * clothes of their nation: each nation has its own colours, cut (robes or tunic and
 * trousers, long or short sleeves) and hats.
 *
 * They are the commonest creature by far, especially in towns. Each lives in a house: they
 * come out of the front door, walk down the path to the pavement, stroll along the road or
 * visit a neighbour, and go back indoors.
 *
 * World units: the viewer's eyes are 20 above the ground.
 */
public class Inhabitants {

    // Bones
    private static final int PELVIS = 0, TORSO = 1, HEAD = 2, FIRST_ARM = 3;

    // People are looked after in square cells of this size around the viewer
    private static final float CELL = 200f;
    private static final float ACTIVE_RADIUS = 750f;
    private static final float DRAW_DISTANCE = 850f;
    private static final int MAX_PEOPLE = 220;

    /** One nation's dress. */
    private static final class Culture {
        float[][] tops;
        float[][] bottoms;
        float robeChance, hatChance, longSleeveChance;
        int pattern;
        float patternScale;
    }

    private enum Phase { INSIDE, WALKING }

    /** One person. */
    private static final class Person {
        InfrastructureManager.Doorway home;
        Phase phase;
        float timer;
        List<float[]> route = new ArrayList<>();
        int next;
        float x, z, heading, lookYaw;
        float walkPhase, walking, speed;
        float scale, bulk;
        int mesh;
        float[] top, accent, bottom, skin;
        Random rand;
    }

    // --- The generated body plan ---
    private final float height, legLength, torsoLength, headSize, neckLength, shoulderWidth, armLength;
    private final int armPairs;
    private final boolean digitigrade, hasTail;
    private final float tailLength;
    private final float snoutLength;
    private final int ears;            // 0 none, 1 pointed, 2 long and drooping, 3 horns, 4 antennae
    private final int eyes;
    private final float eyeSize;
    private final float[] skinColour, eyeColour;
    private final boolean crest;
    private final int hatStyle;        // 0 brimmed, 1 tall cone, 2 cap
    private final int boneCount;
    private final int legBones, tailBones;

    private final long seed;
    private final InfrastructureManager infrastructure;
    private final float regionWidth;
    private final PerlinNoise terrainNoise;
    private final Map<Integer, Culture> cultures = new HashMap<>();
    private final Map<Long, List<Person>> peopleByCell = new HashMap<>();
    private final float[] bones = new float[OrganismSpecies.MAX_BONES * 16];
    private final OrganismMesh[] meshes = new OrganismMesh[8];
    private Shader shader;
    private int lastCellX = Integer.MIN_VALUE, lastCellZ = Integer.MIN_VALUE;

    public Inhabitants(long seed, int nationCount, InfrastructureManager infrastructure,
                       float regionWidth, PerlinNoise terrainNoise) {
        this.seed = seed;
        this.infrastructure = infrastructure;
        this.regionWidth = regionWidth;
        this.terrainNoise = terrainNoise;
        Random rand = WorldPalette.rng(seed, 0xD0517L);

        height = 15f + rand.nextFloat() * 11f;
        legLength = height * (0.4f + rand.nextFloat() * 0.12f);
        torsoLength = height * (0.27f + rand.nextFloat() * 0.07f);
        headSize = height * (0.11f + rand.nextFloat() * 0.06f);
        neckLength = height * (0.02f + rand.nextFloat() * 0.07f);
        shoulderWidth = height * (0.19f + rand.nextFloat() * 0.11f);
        armLength = height * (0.34f + rand.nextFloat() * 0.16f);
        armPairs = rand.nextFloat() < 0.2f ? 2 : 1;
        digitigrade = rand.nextFloat() < 0.3f;
        hasTail = rand.nextFloat() < 0.35f;
        tailLength = height * (0.3f + rand.nextFloat() * 0.35f);
        snoutLength = rand.nextFloat() < 0.5f ? headSize * (0.2f + rand.nextFloat() * 0.6f) : 0f;
        ears = rand.nextInt(5);
        eyes = new int[] { 2, 2, 2, 4, 1 }[rand.nextInt(5)];
        eyeSize = headSize * (0.1f + rand.nextFloat() * 0.08f) * (eyes == 1 ? 1.6f : 1f);
        skinColour = rand.nextFloat() < 0.4f
                ? WorldPalette.hsv(0.03f + rand.nextFloat() * 0.08f, 0.3f + rand.nextFloat() * 0.4f, 0.3f + rand.nextFloat() * 0.5f)
                : WorldPalette.hsv(rand.nextFloat(), 0.25f + rand.nextFloat() * 0.45f, 0.35f + rand.nextFloat() * 0.45f);
        eyeColour = rand.nextFloat() < 0.5f ? new float[] { 0.05f, 0.05f, 0.06f } : WorldPalette.hsv(rand.nextFloat(), 0.7f, 0.8f);
        crest = rand.nextFloat() < 0.25f;
        hatStyle = rand.nextInt(3);

        legBones = FIRST_ARM + armPairs * 6;
        tailBones = legBones + 6;
        boneCount = tailBones + (hasTail ? 2 : 0);

        for (int n = 1; n <= nationCount; n++) {
            Random culture = new Random(seed * 131L + n * 7L + 3L);
            Culture c = new Culture();
            float hue = culture.nextFloat();
            c.tops = new float[3][];
            for (int i = 0; i < 3; i++) {
                c.tops[i] = WorldPalette.hsv(hue + (culture.nextFloat() - 0.5f) * 0.25f,
                        0.3f + culture.nextFloat() * 0.6f, 0.35f + culture.nextFloat() * 0.6f);
            }
            c.bottoms = new float[2][];
            for (int i = 0; i < 2; i++) {
                c.bottoms[i] = culture.nextBoolean() ? WorldPalette.hsv(culture.nextFloat(), 0.2f + culture.nextFloat() * 0.3f, 0.15f + culture.nextFloat() * 0.3f)
                        : WorldPalette.hsv(hue + 0.5f, 0.3f + culture.nextFloat() * 0.4f, 0.3f + culture.nextFloat() * 0.4f);
            }
            c.robeChance = culture.nextFloat() < 0.35f ? 0.5f + culture.nextFloat() * 0.5f : culture.nextFloat() * 0.15f;
            c.hatChance = culture.nextFloat() < 0.4f ? 0.4f + culture.nextFloat() * 0.5f : culture.nextFloat() * 0.1f;
            c.longSleeveChance = culture.nextFloat();
            c.pattern = culture.nextInt(4);
            c.patternScale = 1.5f + culture.nextFloat() * 4f;
            cultures.put(n, c);
        }
    }

    public String describe() {
        return String.format("%.0f tall, %d arm%s, %s legs%s, %d eye%s%s", height, armPairs * 2, "s",
                digitigrade ? "backward-kneed" : "straight", hasTail ? ", tail" : "", eyes, eyes == 1 ? "" : "s",
                new String[] { "", ", pointed ears", ", long ears", ", horns", ", antennae" }[ears]);
    }

    public void initialise(GL3 gl) {
        shader = new Shader(gl, "assets/shaders/vs_organism.txt", "assets/shaders/fs_organism.txt");
        for (int m = 0; m < meshes.length; m++) {
            meshes[m] = buildMesh((m & 4) != 0, (m & 2) != 0, (m & 1) != 0).build(gl);
        }
    }

    public void dispose(GL3 gl) {
        for (OrganismMesh mesh : meshes) {
            if (mesh != null) mesh.dispose(gl);
        }
    }

    public void clear() {
        peopleByCell.clear();
        lastCellX = lastCellZ = Integer.MIN_VALUE;
    }

    // ==========================================
    //          BODY
    // ==========================================

    private float thighLength() { return legLength * 0.49f; }
    private float shinLength() { return legLength * 0.49f; }
    private float hipHeight() { return legLength * 0.95f; }

    private OrganismMesh.Builder buildMesh(boolean robe, boolean hat, boolean longSleeves) {
        OrganismMesh.Builder b = new OrganismMesh.Builder();
        float w = shoulderWidth;

        // Pelvis: hips in trousers, or a robe falling to the shins
        b.bone(PELVIS).resetTransform();
        OrganismParts.shell(b, OrganismMesh.PART_TRIM, height * 0.11f, w * 0.85f, w * 0.6f, 0.6f, 0, 0f, 1f);
        if (robe) {
            float drop = legLength * 0.8f;
            b.transform(Affine.multiply(Affine.translation(0f, 0f, height * 0.04f), Affine.rotationX((float) Math.PI)));
            b.part(OrganismMesh.PART_BODY);
            b.lathe(14, 6, (t, out) -> {
                out[0] = 0f;
                out[1] = 0f;
                out[2] = drop * t;
                out[3] = w * (0.45f + 0.35f * t);
                out[4] = w * (0.32f + 0.3f * t);
            });
        }

        // Torso in its top, neck and an optional crest of skin down the back
        b.bone(TORSO).resetTransform();
        OrganismParts.shell(b, OrganismMesh.PART_BODY, torsoLength, w, w * 0.62f, 0.55f, 0, 0f, 0.9f);
        b.transform(Affine.translation(0f, 0f, torsoLength * 0.4f));
        OrganismParts.limb(b, OrganismMesh.PART_SKIN, neckLength + headSize * 0.4f, w * 0.17f, w * 0.15f);

        // Head: faces +Z
        b.bone(HEAD).resetTransform();
        OrganismParts.shell(b, OrganismMesh.PART_SKIN, headSize * 1.05f, headSize * 0.85f, headSize, 0.6f, 0, 0f, 0.9f);
        if (snoutLength > 0f) {
            b.transform(Affine.translation(0f, -headSize * 0.15f, headSize * 0.35f));
            OrganismParts.shell(b, OrganismMesh.PART_SKIN, snoutLength * 2f, headSize * 0.45f, headSize * 0.4f, 0.8f, 0, 0f, 0.8f);
        }
        float front = headSize * 0.42f + (snoutLength > 0f ? snoutLength * 0.25f : 0f);
        for (int e = 0; e < eyes; e++) {
            float ex = eyes == 1 ? 0f : ((e & 1) == 0 ? -1 : 1) * headSize * 0.22f;
            float ey = headSize * (0.12f + 0.14f * (e / 2));
            b.transform(Affine.translation(ex, ey, front * 0.85f));
            OrganismParts.eye(b, eyeSize);
        }
        for (int side = -1; side <= 1; side += 2) {
            switch (ears) {
                case 1 -> {
                    b.transform(Affine.multiply(Affine.translation(side * headSize * 0.35f, headSize * 0.35f, -headSize * 0.05f),
                            Affine.multiply(Affine.rotationY(side * 0.5f), Affine.rotationX(-1.2f))));
                    OrganismParts.horn(b, headSize * 0.6f, headSize * 0.14f);
                }
                case 2 -> {
                    b.transform(Affine.multiply(Affine.translation(side * headSize * 0.4f, headSize * 0.2f, -headSize * 0.1f),
                            Affine.multiply(Affine.rotationY(side * 1.2f), Affine.rotationX(0.6f))));
                    OrganismParts.shell(b, OrganismMesh.PART_SKIN, headSize * 1.1f, headSize * 0.35f, headSize * 0.08f, 0.8f, 0, 0f, 1f);
                }
                case 3 -> {
                    b.transform(Affine.multiply(Affine.translation(side * headSize * 0.25f, headSize * 0.4f, headSize * 0.1f),
                            Affine.multiply(Affine.rotationY(side * 0.4f), Affine.rotationX(-0.6f))));
                    OrganismParts.horn(b, headSize * 0.9f, headSize * 0.1f);
                }
                case 4 -> {
                    b.transform(Affine.multiply(Affine.translation(side * headSize * 0.15f, headSize * 0.45f, headSize * 0.2f),
                            Affine.multiply(Affine.rotationY(side * 0.35f), Affine.rotationX(-1.0f))));
                    OrganismParts.antenna(b, OrganismParts.AntennaType.CLUBBED, headSize * 1.2f, headSize * 0.04f);
                }
                default -> { }
            }
        }
        if (crest) {
            for (int c = 0; c < 4; c++) {
                b.transform(Affine.multiply(Affine.translation(0f, headSize * 0.45f, headSize * (0.2f - 0.15f * c)), Affine.rotationX(-1.4f + c * 0.2f)));
                OrganismParts.horn(b, headSize * 0.35f, headSize * 0.06f);
            }
        }
        if (hat) {
            b.part(OrganismMesh.PART_TRIM);
            float top = headSize * 0.45f;
            switch (hatStyle) {
                case 0 -> {
                    b.transform(Affine.multiply(Affine.translation(0f, top, 0f), Affine.rotationX((float) -Math.PI / 2)));
                    OrganismParts.shell(b, OrganismMesh.PART_TRIM, headSize * 0.08f, headSize * 1.6f, headSize * 1.6f, 0.2f, 0, 0f, 1f);
                    b.transform(Affine.multiply(Affine.translation(0f, top + headSize * 0.2f, 0f), Affine.rotationX((float) -Math.PI / 2)));
                    OrganismParts.shell(b, OrganismMesh.PART_TRIM, headSize * 0.45f, headSize * 0.8f, headSize * 0.8f, 0.3f, 0, 0f, 1f);
                }
                case 1 -> {
                    b.transform(Affine.multiply(Affine.translation(0f, top - headSize * 0.1f, 0f), Affine.rotationX((float) -Math.PI / 2)));
                    b.lathe(12, 6, (t, out) -> {
                        out[0] = 0f;
                        out[1] = 0f;
                        out[2] = headSize * 1.3f * t;
                        out[3] = Math.max(0.01f, headSize * 0.55f * (1f - t));
                        out[4] = out[3];
                    });
                }
                default -> {
                    b.transform(Affine.multiply(Affine.translation(0f, top, -headSize * 0.05f), Affine.rotationX((float) -Math.PI / 2)));
                    OrganismParts.shell(b, OrganismMesh.PART_TRIM, headSize * 0.4f, headSize * 0.95f, headSize * 1.0f, 0.4f, 0, 0f, 1f);
                }
            }
        }

        // Arms: sleeve, forearm (sleeved or bare) and hand
        float upper = armLength * 0.48f, fore = armLength * 0.42f;
        for (int a = 0; a < armPairs * 2; a++) {
            int boneBase = FIRST_ARM + a * 3;
            b.bone(boneBase).resetTransform();
            OrganismParts.limb(b, OrganismMesh.PART_BODY, upper, w * 0.15f, w * 0.12f);
            b.bone(boneBase + 1).resetTransform();
            OrganismParts.limb(b, longSleeves ? OrganismMesh.PART_BODY : OrganismMesh.PART_SKIN, fore, w * 0.12f, w * 0.1f);
            b.bone(boneBase + 2).transform(Affine.translation(0f, 0f, armLength * 0.05f));
            OrganismParts.shell(b, OrganismMesh.PART_SKIN, armLength * 0.13f, w * 0.16f, w * 0.08f, 0.6f, 0, 0f, 1f);
        }

        // Legs: trousers and bare feet
        for (int side = 0; side < 2; side++) {
            int boneBase = legBones + side * 3;
            b.bone(boneBase).resetTransform();
            OrganismParts.limb(b, OrganismMesh.PART_TRIM, thighLength(), w * 0.2f, w * 0.15f);
            b.bone(boneBase + 1).resetTransform();
            OrganismParts.limb(b, OrganismMesh.PART_TRIM, shinLength(), w * 0.15f, w * 0.11f);
            b.bone(boneBase + 2).transform(Affine.translation(0f, legLength * 0.03f, legLength * 0.08f));
            OrganismParts.shell(b, OrganismMesh.PART_HORN, legLength * 0.22f, w * 0.17f, legLength * 0.06f, 0.5f, 0, 0f, 1f);
        }
        if (hasTail) {
            b.bone(tailBones).resetTransform();
            OrganismParts.limb(b, OrganismMesh.PART_SKIN, tailLength * 0.5f, w * 0.12f, w * 0.08f);
            b.bone(tailBones + 1).resetTransform();
            OrganismParts.limb(b, OrganismMesh.PART_SKIN, tailLength * 0.5f, w * 0.08f, w * 0.02f);
        }
        return b;
    }

    /** Bone matrices for one person, standing or walking, at their current spot. */
    private void pose(Person p, float time) {
        float ground = TerrainMesh.getLayeredHeight(p.x, p.z, terrainNoise);
        float[] body = Affine.multiply(Affine.translation(p.x, ground, p.z),
                Affine.multiply(Affine.rotationY(p.heading), Affine.scale(p.scale, p.scale, p.scale)));
        float walk = p.walking;
        float cycle = p.walkPhase;
        float bob = (float) Math.abs(Math.cos(cycle)) * legLength * 0.02f * walk;
        float hip = hipHeight() - legLength * 0.04f * walk + bob;
        float lean = 0.08f * walk;
        float[] forward = { 0f, 0f, 1f };

        float[] pelvis = { 0f, hip, 0f };
        place(body, PELVIS, Affine.frame(pelvis, new float[] { 0f, 1f, 0f }, forward, p.bulk, p.bulk, 1f));
        float[] torsoCentre = { 0f, hip + height * 0.04f + torsoLength * 0.5f, lean * torsoLength * 0.5f };
        place(body, TORSO, Affine.frame(torsoCentre, new float[] { 0f, 1f, lean }, forward, p.bulk, p.bulk, 1f));
        float shoulderY = torsoCentre[1] + torsoLength * 0.38f;
        float[] head = { 0f, torsoCentre[1] + torsoLength * 0.5f + neckLength + headSize * 0.5f, lean * torsoLength + headSize * 0.1f };
        place(body, HEAD, Affine.multiply(Affine.translation(head[0], head[1], head[2]), Affine.rotationY(p.lookYaw)));

        float upper = armLength * 0.48f, fore = armLength * 0.42f;
        for (int a = 0; a < armPairs * 2; a++) {
            int side = (a & 1) == 0 ? -1 : 1;
            int pair = a / 2;
            float swing = (float) Math.sin(cycle + (side > 0 ? 0 : Math.PI) + pair * 0.8f) * walk;
            float[] shoulder = { side * shoulderWidth * 0.5f * p.bulk, shoulderY - pair * torsoLength * 0.25f, torsoCentre[2] };
            float[] hand = { shoulder[0] + side * shoulderWidth * 0.08f, shoulder[1] - armLength * 0.86f + Math.abs(swing) * armLength * 0.08f,
                    shoulder[2] + swing * armLength * 0.35f + armLength * 0.05f };
            float[] elbow = Affine.middleJoint(shoulder, hand, upper, fore, new float[] { side * 0.3f, 0f, -1f });
            int boneBase = FIRST_ARM + a * 3;
            limb(body, shoulder, elbow, upper, boneBase);
            limb(body, elbow, hand, fore, boneBase + 1);
            place(body, boneBase + 2, Affine.frame(hand, Affine.subtract(hand, elbow), forward, 1f, 1f, 1f));
        }

        float stride = legLength * 0.55f;
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? -1f : 1f;
            float phase = cycle + side * (float) Math.PI;
            float[] hipJoint = { sign * shoulderWidth * 0.22f * p.bulk, hip, 0f };
            float lift = Math.max(0f, (float) Math.cos(phase)) * legLength * 0.12f * walk;
            float[] foot = { hipJoint[0], legLength * 0.05f + lift, (float) Math.sin(phase) * stride * 0.5f * walk };
            float[] knee = Affine.middleJoint(hipJoint, foot, thighLength(), shinLength(),
                    new float[] { 0f, 0f, digitigrade ? -1f : 1f });
            int boneBase = legBones + side * 3;
            limb(body, hipJoint, knee, thighLength(), boneBase);
            limb(body, knee, foot, shinLength(), boneBase + 1);
            place(body, boneBase + 2, Affine.frame(foot, forward, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
        }
        if (hasTail) {
            float sway = (float) Math.sin(time * 1.3f + p.x * 0.1f) * 0.4f;
            float[] base = { 0f, hip - height * 0.02f, -shoulderWidth * 0.3f };
            float[] dir = { sway, -0.45f, -1f };
            float[] middle = Affine.add(base, Affine.normalise(dir), tailLength * 0.5f);
            float[] dir2 = { sway * 1.8f, -0.1f, -1f };
            place(body, tailBones, Affine.frame(base, dir, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
            place(body, tailBones + 1, Affine.frame(middle, dir2, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f));
        }
    }

    private void limb(float[] body, float[] from, float[] to, float designLength, int bone) {
        float[] span = Affine.subtract(to, from);
        place(body, bone, Affine.frame(from, span, new float[] { 0f, 1f, 0f }, 1f, 1f, Affine.length(span) / designLength));
    }

    private void place(float[] body, int bone, float[] local) {
        Affine.multiply(body, local, bones, bone * 16);
    }

    // ==========================================
    //          LIFE
    // ==========================================

    public void update(float dt, float viewerX, float viewerZ) {
        int cellX = (int) Math.floor(viewerX / CELL), cellZ = (int) Math.floor(viewerZ / CELL);
        if (cellX != lastCellX || cellZ != lastCellZ) {
            lastCellX = cellX;
            lastCellZ = cellZ;
            int reach = (int) Math.ceil(ACTIVE_RADIUS / CELL);
            int count = 0;
            for (List<Person> list : peopleByCell.values()) count += list.size();
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    long key = cellKey(cellX + dx, cellZ + dz);
                    if (peopleByCell.containsKey(key) || dx * dx + dz * dz > reach * reach) continue;
                    List<Person> people = populate(cellX + dx, cellZ + dz, MAX_PEOPLE - count);
                    count += people.size();
                    peopleByCell.put(key, people);
                }
            }
            Iterator<Map.Entry<Long, List<Person>>> it = peopleByCell.entrySet().iterator();
            while (it.hasNext()) {
                long key = it.next().getKey();
                int cx = (int) (key >> 32), cz = (int) key;
                if ((cx - cellX) * (cx - cellX) + (cz - cellZ) * (cz - cellZ) > (reach + 1) * (reach + 1)) it.remove();
            }
        }
        float step = Math.min(dt, 0.1f);
        for (List<Person> list : peopleByCell.values()) {
            for (Person p : list) live(p, step);
        }
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** The people of one cell: some of each house's household, more in town than in the country. */
    private List<Person> populate(int cellX, int cellZ, int room) {
        List<Person> people = new ArrayList<>();
        if (room <= 0) return people;
        Random rand = new Random(seed ^ (cellX * 0x9E3779B97F4A7C15L) ^ (cellZ * 0xC2B2AE3D27D4EB4FL) ^ 0x1A7L);
        for (InfrastructureManager.Doorway door : infrastructure.doorwaysIn(cellX * CELL, cellZ * CELL, (cellX + 1) * CELL, (cellZ + 1) * CELL)) {
            // A household of up to three out and about, fuller in town than in the country
            float expected = 0.45f + 2.0f * door.urbanness;
            int household = (int) expected + (rand.nextFloat() < expected - (int) expected ? 1 : 0);
            for (int member = 0; member < household && people.size() < room; member++) {
                people.add(create(door, new Random(rand.nextLong())));
            }
            if (people.size() >= room) break;
        }
        return people;
    }

    private Person create(InfrastructureManager.Doorway home, Random rand) {
        Person p = new Person();
        p.home = home;
        p.rand = rand;
        p.scale = 0.9f + rand.nextFloat() * 0.2f;
        p.bulk = 0.85f + rand.nextFloat() * 0.35f;
        p.speed = 14f + rand.nextFloat() * 8f;
        Culture culture = cultures.getOrDefault(home.nationId, cultures.values().iterator().next());
        boolean robe = rand.nextFloat() < culture.robeChance;
        boolean hat = rand.nextFloat() < culture.hatChance;
        boolean sleeves = rand.nextFloat() < culture.longSleeveChance;
        p.mesh = (robe ? 4 : 0) | (hat ? 2 : 0) | (sleeves ? 1 : 0);
        p.top = culture.tops[rand.nextInt(culture.tops.length)];
        p.accent = culture.tops[rand.nextInt(culture.tops.length)];
        p.bottom = culture.bottoms[rand.nextInt(culture.bottoms.length)];
        float[] hsv = WorldPalette.toHsv(skinColour);
        p.skin = WorldPalette.hsv(hsv[0] + (rand.nextFloat() - 0.5f) * 0.04f, hsv[1], hsv[2] * (0.85f + rand.nextFloat() * 0.3f));
        p.x = home.insideX;
        p.z = home.insideZ;
        if (rand.nextFloat() < 0.35f) {
            p.phase = Phase.INSIDE;
            p.timer = rand.nextFloat() * 12f;
        } else {
            // Already out: somewhere along the way to wherever they're going
            setOff(p);
            int start = 1 + rand.nextInt(Math.max(1, p.route.size() - 2));
            float[] at = p.route.get(start - 1), to = p.route.get(start);
            float t = rand.nextFloat();
            p.x = at[0] + (to[0] - at[0]) * t;
            p.z = at[1] + (to[1] - at[1]) * t;
            p.next = start;
        }
        return p;
    }

    /**
     * Plans a trip out: through the door and down to the pavement, then either along the road
     * to a neighbour's house and in at their door, or a stroll along the road and back home.
     */
    private void setOff(Person p) {
        InfrastructureManager.Doorway home = p.home;
        p.route.clear();
        p.route.add(new float[] { home.insideX, home.insideZ });
        p.route.add(new float[] { home.doorX, home.doorZ });
        p.route.add(new float[] { home.kerbX, home.kerbZ });
        InfrastructureManager.Doorway visit = null;
        if (p.rand.nextFloat() < 0.55f) {
            List<InfrastructureManager.Doorway> near = infrastructure.doorwaysNear(home.kerbX, home.kerbZ, 160f);
            near.removeIf(d -> d.id == home.id || d.path != home.path);
            if (!near.isEmpty()) visit = near.get(p.rand.nextInt(near.size()));
        }
        if (visit != null) {
            p.route.add(new float[] { visit.kerbX, visit.kerbZ });
            p.route.add(new float[] { visit.doorX, visit.doorZ });
            p.route.add(new float[] { visit.insideX, visit.insideZ });
            p.home = visit;
        } else {
            float along = (p.rand.nextBoolean() ? 1f : -1f) * (30f + p.rand.nextFloat() * 90f);
            p.route.add(new float[] { home.kerbX + home.roadDirX * along, home.kerbZ + home.roadDirZ * along });
            p.route.add(new float[] { home.kerbX, home.kerbZ });
            p.route.add(new float[] { home.doorX, home.doorZ });
            p.route.add(new float[] { home.insideX, home.insideZ });
        }
        p.next = 1;
        p.phase = Phase.WALKING;
    }

    private void live(Person p, float dt) {
        if (p.phase == Phase.INSIDE) {
            p.timer -= dt;
            if (p.timer <= 0f) setOff(p);
            return;
        }
        float[] target = p.route.get(p.next);
        float dx = target[0] - p.x, dz = target[1] - p.z;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        float move = p.speed * p.scale * dt;
        if (distance <= move) {
            p.x = target[0];
            p.z = target[1];
            p.next++;
            if (p.next >= p.route.size()) {
                p.phase = Phase.INSIDE;
                p.timer = 2f + p.rand.nextFloat() * 12f;
                p.walking = 0f;
            }
        } else {
            p.x += dx / distance * move;
            p.z += dz / distance * move;
            float desired = (float) Math.atan2(dx, dz);
            float turn = (float) Math.atan2(Math.sin(desired - p.heading), Math.cos(desired - p.heading));
            p.heading += Math.max(-4f * dt, Math.min(4f * dt, turn));
        }
        p.walking += (1f - p.walking) * Math.min(1f, dt * 5f);
        float stride = legLength * 0.55f * p.scale;
        p.walkPhase = (p.walkPhase + move / stride * (float) Math.PI) % (float) (Math.PI * 2);
        p.lookYaw = 0.35f * (float) Math.sin(p.walkPhase * 0.13f + p.x * 0.01f);
    }

    /** Developer aid: a spot on a pavement in the busiest neighbourhood found, as {x, z, lookX, lookZ}. */
    public float[] streetViewpoint(Random rand) {
        InfrastructureManager.Doorway best = null;
        int bestCount = 0;
        for (int attempt = 0; attempt < 600; attempt++) {
            float x = (rand.nextFloat() - 0.5f) * regionWidth * 0.9f;
            float z = (rand.nextFloat() - 0.5f) * regionWidth * 0.9f;
            List<InfrastructureManager.Doorway> doors = infrastructure.doorwaysNear(x, z, 250f);
            if (doors.size() > bestCount) {
                bestCount = doors.size();
                best = doors.get(rand.nextInt(doors.size()));
            }
        }
        if (best == null) return null;
        // On the pavement a little way along from a front door, looking back towards it
        float back = 30f;
        float x = best.kerbX + best.roadDirX * back, z = best.kerbZ + best.roadDirZ * back;
        float lookX = best.doorX - x, lookZ = best.doorZ - z;
        float length = (float) Math.hypot(lookX, lookZ);
        return new float[] { x, z, lookX / length, lookZ / length };
    }

    public void render(GL3 gl, Matrix4 viewProjection, Frustum frustum, Vector3 viewPos, Vector3 sunPos,
                       float[] sunColour, Vector3 ambient, Matrix4 skyRotation, Texture sky, float time) {
        if (shader == null) return;
        shader.use(gl);
        shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        shader.setVec3(gl, "viewPos", viewPos);
        shader.setVec3(gl, "sunPos", sunPos);
        shader.setVec3(gl, "sunColour", new Vector3(sunColour[0], sunColour[1], sunColour[2]));
        shader.setVec3(gl, "ambientLight", ambient);
        shader.setFloat(gl, "waterLevel", -1e9f);
        shader.setVec3(gl, "waterTint", new Vector3(0f, 0f, 0f));
        shader.setVec3(gl, "eyeColour", vec(eyeColour));
        shader.setFloat(gl, "gloss", 0.2f);
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            shader.setInt(gl, "skyTexture", 2);
        }
        int bonesLocation = gl.glGetUniformLocation(shader.getID(), "bones");
        gl.glDisable(GL.GL_CULL_FACE);
        for (List<Person> list : peopleByCell.values()) {
            for (Person p : list) {
                if (p.phase == Phase.INSIDE) continue;
                float dx = p.x - viewPos.x, dz = p.z - viewPos.z;
                if (dx * dx + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE) continue;
                float ground = TerrainMesh.getLayeredHeight(p.x, p.z, terrainNoise);
                if (!frustum.intersectsSphere(p.x, ground + height * 0.5f, p.z, height)) continue;
                pose(p, time);
                gl.glUniformMatrix4fv(bonesLocation, boneCount, false, bones, 0);
                shader.setVec3(gl, "baseColour", vec(p.top));
                shader.setVec3(gl, "bellyColour", new Vector3(p.top[0] * 0.9f, p.top[1] * 0.9f, p.top[2] * 0.9f));
                shader.setVec3(gl, "accentColour", vec(p.accent));
                shader.setVec3(gl, "limbColour", vec(p.skin));
                shader.setVec3(gl, "trimColour", vec(p.bottom));
                Culture culture = cultures.getOrDefault(p.home.nationId, cultures.values().iterator().next());
                shader.setInt(gl, "patternType", culture.pattern);
                shader.setFloat(gl, "patternScale", culture.patternScale);
                meshes[p.mesh].render(gl);
            }
        }
        gl.glEnable(GL.GL_CULL_FACE);
    }

    private static Vector3 vec(float[] c) {
        return new Vector3(c[0], c[1], c[2]);
    }
}
