import com.jogamp.opengl.GL3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The inhabitants' vehicles: a handful of standard kinds, each strange in its own way and
 * built in a few variations, parked at the roadside outside houses and driven along the
 * roads to the shops and out to other settlements.
 * <ul>
 *   <li>a little egg-shaped pod on three ball wheels with a bubble canopy;</li>
 *   <li>a long, low, ridged crawler on six wheels, feelers at the front;</li>
 *   <li>a cabin perched on four stilts, each on its own great disc wheel;</li>
 *   <li>a hauler: a cab towing one or two ribbed cargo segments on eight wheels.</li>
 * </ul>
 * Each nation favours some kinds over others, and may have none of one or two. Each nation
 * drives on its own side of the road; vehicles keep to it, switching at a border, and park
 * on it, pulled right to the edge. They go faster on big roads, the small ones fastest;
 * slow behind and steer round each other; stop for anything solid in their way (their
 * whole length and width counts); and are themselves solid to everyone else.
 */
public final class Vehicles {

    public static final int KINDS = 4, VARIANTS = 3;
    private static final int POD = 0, CRAWLER = 1, STILT = 2, HAULER = 3;
    private static final String[] KIND_NAMES = { "pod", "crawler", "stilt", "hauler" };
    // Each kind's size (length, width, height) relative to the people who drive them, and
    // speed on ordinary roads and on highways (world units a second)
    private static final float[][] KIND_SIZE = { { 0.85f, 0.5f, 0.55f }, { 1.3f, 0.58f, 0.5f }, { 1.0f, 0.55f, 0.95f }, { 2.0f, 0.56f, 0.7f } };
    private static final float[][] KIND_SPEED = { { 52f, 100f }, { 46f, 88f }, { 40f, 72f }, { 34f, 60f } };
    // How much taller than the kind's usual height each variation is. The first of each low kind
    // is open-topped instead: its driver sits in a hole in the top, head and shoulders out
    private static final float[][] VARIANT_HEIGHT = { { 1f, 1.55f, 1.45f }, { 1f, 1.6f, 1.5f }, { 1f, 1f, 1.1f }, { 1f, 1.3f, 1.35f } };

    private static boolean openTop(int kind, int variant) {
        return variant == 0 && kind != STILT;
    }

    // Further than this from the viewer, a vehicle can't be seen (people and vehicles are drawn out to 850)
    public static final float OUT_OF_SIGHT = 950f;

    // Lanes run this far out from the road's middle (as a share of its half width)
    private static final float LANE_SHARE = 0.32f;

    /** One vehicle, parked or on the move. */
    public static final class Vehicle {
        final int kind, variant;
        final float length, width, height;
        final float[] body, accent, trim, glass;
        final int pattern;
        final float patternScale;
        final InfrastructureManager.Doorway home;
        float x, z, heading, speed, spin, pitch;
        boolean parked = true;
        // Where it's parked (or will park): which door's kerb, and which place along it
        long parkedAt;
        int slot;
        // The way it's driving: points {x, z, side, half width, rank, dirt}; then its parking place {x, z, heading}
        final List<float[]> route = new ArrayList<>();
        int next;
        float[] park;
        Runnable onArrive;
        // Reversing out of a driveway to here before setting off; turning in at a driveway from here
        float[] backOut, approach;
        // Whoever has it for a trip (its owner or someone borrowing it), or null if it's free
        Object claimedBy;
        // Easing out round parked vehicles; how long it has been held up; when it may push through
        float shift, stuck, ghost;
        // Turning one way without letting up, chasing the point it's heading for
        float circling;
        // Overtaking: how long it keeps out on the other side, and how far over that is
        float overtaking, overtakeShift;
        // Stuck on something: reversing for this long, then going round it to one side (-1 left,
        // 1 right) for a while; how many tries it has had
        float reversing, detour;
        int detourSide, tries;
        // The road deck's height above the ground where it stands (slow to find), and where that was found
        float lift, liftX = Float.NaN, liftZ;
        boolean settling;

        private Vehicle(int kind, int variant, float scale, InfrastructureManager.Doorway home, Random rand) {
            this.kind = kind;
            this.variant = variant;
            this.home = home;
            length = KIND_SIZE[kind][0] * scale * (0.92f + rand.nextFloat() * 0.16f);
            width = KIND_SIZE[kind][1] * scale;
            height = KIND_SIZE[kind][2] * scale * VARIANT_HEIGHT[kind][variant];
            float hue = rand.nextFloat();
            body = WorldPalette.hsv(hue, 0.35f + rand.nextFloat() * 0.6f, 0.35f + rand.nextFloat() * 0.6f);
            // All one colour (bar the windows and tyres): markings would make it look like an animal
            accent = body;
            trim = body;
            glass = WorldPalette.hsv(rand.nextFloat(), 0.4f + rand.nextFloat() * 0.4f, 0.25f + rand.nextFloat() * 0.3f);
            pattern = 4;
            patternScale = 1f + rand.nextFloat() * 3f;
        }

        public boolean isParked() {
            return parked;
        }

        // Passing through, belonging to no household nearby
        boolean traffic;

        public Object claimedBy() {
            return claimedBy;
        }

        public boolean isTraffic() {
            return traffic;
        }

        public float x() {
            return x;
        }

        public float z() {
            return z;
        }
    }

    private final InfrastructureManager infrastructure;
    private final PerlinNoise terrainNoise;
    private final float scale;
    private final long seed;
    // The mesh of each kind's variations, and where each wheel's hub is and how big it is
    private final OrganismMesh.Builder[][] builders = new OrganismMesh.Builder[KINDS][VARIANTS];
    private final OrganismMesh[][] meshes = new OrganismMesh[KINDS][VARIANTS];
    private final float[][][][] wheels = new float[KINDS][VARIANTS][][];
    // Each nation's taste in vehicles, how many of its households have one, and its side of the road
    private final Map<Integer, float[]> kindWeights = new HashMap<>();
    private final Map<Integer, Float> ownership = new HashMap<>();
    // Places taken along each door's kerb (by door id)
    private final Map<Long, boolean[]> slotsTaken = new HashMap<>();
    private Collision collision;
    private float viewerX, viewerZ;
    private final float[] bones = new float[OrganismSpecies.MAX_BONES * 16];

    public Vehicles(long seed, int nationCount, float personHeight, InfrastructureManager infrastructure, PerlinNoise terrainNoise) {
        this.seed = seed;
        this.infrastructure = infrastructure;
        this.terrainNoise = terrainNoise;
        this.scale = personHeight;
        Random rand = new Random(seed * 409L + 77L);
        for (int k = 0; k < KINDS; k++) {
            for (int v = 0; v < VARIANTS; v++) builders[k][v] = buildKind(k, v, new Random(rand.nextLong()));
        }
        for (int n = 1; n <= nationCount; n++) {
            // (scrambled: Random's first numbers are nearly alike for nearby seeds)
            long h = (seed * 0x9E3779B97F4A7C15L + n * 0xC2B2AE3D27D4EB4FL) ^ 0x211L;
            h ^= h >>> 31;
            h *= 0xBF58476D1CE4E5B9L;
            Random taste = new Random(h ^ (h >>> 29));
            float[] weights = new float[KINDS];
            for (int k = 0; k < KINDS; k++) weights[k] = 0.15f + taste.nextFloat();
            // None at all of one or two kinds, now and then
            int missing = taste.nextFloat() < 0.35f ? 0 : taste.nextFloat() < 0.6f ? 1 : 2;
            for (int m = 0; m < missing; m++) weights[taste.nextInt(KINDS)] = 0f;
            float total = 0f;
            for (float w : weights) total += w;
            if (total <= 0f) weights[POD] = total = 1f;
            for (int k = 0; k < KINDS; k++) weights[k] /= total;
            kindWeights.put(n, weights);
            ownership.put(n, 0.45f + taste.nextFloat() * 0.5f);
        }
    }

    public void initialise(GL3 gl) {
        for (int k = 0; k < KINDS; k++) {
            for (int v = 0; v < VARIANTS; v++) meshes[k][v] = builders[k][v].build(gl);
        }
    }

    public void dispose(GL3 gl) {
        for (OrganismMesh[] set : meshes) {
            for (OrganismMesh mesh : set) if (mesh != null) mesh.dispose(gl);
        }
    }

    public void setCollision(Collision collision) {
        this.collision = collision;
    }

    public void clear() {
        slotsTaken.clear();
        reservedAt.clear();
    }

    // ------------------------------------------------------------------ the kinds

    private static final float[] IDENTITY = Affine.identity();

    /** One variation of a kind, its body on bone 0 and each wheel on its own bone after (so the wheels can turn). */
    // Where an open-topped variation's driver sits: {how far forward, how high its rim is}; null if closed
    private final float[][][] seats = new float[KINDS][VARIANTS][];

    private OrganismMesh.Builder buildKind(int kind, int variant, Random rand) {
        OrganismMesh.Builder b = new OrganismMesh.Builder();
        float L = KIND_SIZE[kind][0] * scale, W = KIND_SIZE[kind][1] * scale, H0 = KIND_SIZE[kind][2] * scale;
        // Taller variations are taller in the body; the wheels stay the kind's size
        float H = H0 * VARIANT_HEIGHT[kind][variant];
        boolean open = openTop(kind, variant);
        // One variation of each kind is boxy, more like the vehicles of home, if still odd
        boolean boxy = kind == STILT ? variant == 1 : variant == 2;
        List<float[]> hubs = new ArrayList<>();
        b.bone(0).resetTransform().part(OrganismMesh.PART_BODY);
        switch (kind) {
            case POD -> {
                // An egg on three ball wheels, a bubble canopy at the front, perhaps a tail fin
                float r = H0 * 0.2f;
                float seatZ = L * (0.08f + rand.nextFloat() * 0.1f);
                if (boxy) {
                    // A squat box with a glassy cabin set back on top, and something odd fixed to it
                    float floor = r * 1.5f;
                    b.transform(IDENTITY);
                    b.box(0f, floor + H * 0.24f, 0f, L, H * 0.48f, W);
                    b.box(0f, floor + H * 0.66f, -L * 0.08f, L * 0.55f, H * 0.36f, W * 0.86f);
                    b.part(OrganismMesh.PART_EYE);
                    b.box(0f, floor + H * 0.68f, -L * 0.08f, L * 0.5f, H * 0.24f, W * 0.88f);
                    b.box(0f, floor + H * 0.68f, -L * 0.08f, L * 0.57f, H * 0.24f, W * 0.8f);
                    b.part(OrganismMesh.PART_BODY);
                    boxyOddity(b, rand, L, W, H, floor + H * 0.84f, -L * 0.08f, L * 0.55f);
                } else {
                    b.transform(Affine.translation(0f, r * 1.5f + H * 0.38f, 0f));
                    OrganismParts.shell(b, OrganismMesh.PART_BODY, L, W, H * 0.78f, 0.8f + rand.nextFloat() * 0.8f, 0, 0f, 0.75f + rand.nextFloat() * 0.4f);
                }
                if (boxy) {
                    // (its cabin is its windows)
                } else if (open) {
                    cockpit(b, kind, variant, seatZ, r * 1.5f + H * 0.77f, L * 0.42f, W * 0.62f);
                } else {
                    b.transform(Affine.translation(0f, r * 1.5f + H * 0.62f, seatZ));
                    OrganismParts.shell(b, OrganismMesh.PART_EYE, L * 0.48f, W * 0.72f, H * 0.5f, 1.2f, 0, 0f, 1f);
                }
                if (rand.nextBoolean()) {
                    b.transform(Affine.multiply(Affine.translation(0f, r * 1.5f + H * 0.78f, -L * 0.38f), Affine.rotationX(-0.6f)));
                    OrganismParts.shell(b, OrganismMesh.PART_TRIM, L * 0.3f, W * 0.08f, H * 0.4f, 1.5f, 0, 0f, 0.6f);
                }
                hubs.add(new float[] { 0f, r, L * 0.33f, r, 1f });
                hubs.add(new float[] { -W * 0.42f, r, -L * 0.28f, r, 1f });
                hubs.add(new float[] { W * 0.42f, r, -L * 0.28f, r, 1f });
            }
            case CRAWLER -> {
                // A long ridged shell close to the ground, slit windows down its sides
                float r = H0 * 0.17f;
                int ridges = 3 + rand.nextInt(3);
                if (boxy) {
                    // A long low box in stepped sections, tallest at the front, a strip of window down each side
                    float floor = r * 1.4f;
                    b.transform(IDENTITY);
                    int sections = 2 + rand.nextInt(3);
                    float sectionLength = L / sections;
                    for (int i = 0; i < sections; i++) {
                        float z = L * 0.5f - sectionLength * (i + 0.5f);
                        float tall = H * (0.62f - 0.12f * i / Math.max(1, sections - 1));
                        b.box(0f, floor + tall * 0.5f, z, sectionLength * 0.97f, tall, W * (1f - 0.04f * i));
                    }
                    b.part(OrganismMesh.PART_EYE);
                    b.box(0f, floor + H * 0.45f, L * 0.1f, L * 0.55f, H * 0.1f, W * 1.02f);
                    b.part(OrganismMesh.PART_BODY);
                    boxyOddity(b, rand, L, W, H, floor + H * 0.62f, L * 0.2f, sectionLength);
                } else {
                    b.transform(Affine.translation(0f, r * 1.4f + H * 0.32f, 0f));
                    OrganismParts.shell(b, OrganismMesh.PART_BODY, L, W, H * 0.62f, 0.5f + rand.nextFloat() * 0.3f, ridges, 0.12f + rand.nextFloat() * 0.1f, 0.85f);
                }
                if (boxy) {
                    // (its windows are part of it)
                } else if (open) {
                    cockpit(b, kind, variant, L * 0.14f, r * 1.4f + H * 0.63f, L * 0.3f, W * 0.62f);
                } else {
                    for (int s = -1; s <= 1; s += 2) {
                        b.transform(Affine.translation(s * W * 0.42f, r * 1.4f + H * 0.42f, L * 0.08f));
                        OrganismParts.shell(b, OrganismMesh.PART_EYE, L * 0.5f, W * 0.18f, H * 0.14f, 1f, 0, 0f, 1f);
                    }
                }
                for (int i = 0; i < 3; i++) {
                    float hz = L * (0.3f - 0.3f * i);
                    hubs.add(new float[] { -W * 0.5f, r, hz, r, 0f });
                    hubs.add(new float[] { W * 0.5f, r, hz, r, 0f });
                }
            }
            case STILT -> {
                // A rounded cabin up on four stilts, a great disc wheel under each
                float r = H0 * (0.2f + rand.nextFloat() * 0.06f);
                float cabinY = H * 0.68f;
                if (boxy) {
                    // A boxy cabin up on the stilts, its front all window, and something odd on its roof
                    b.transform(IDENTITY);
                    b.box(0f, cabinY, 0f, L * 0.78f, H * 0.4f, W * 0.82f);
                    b.part(OrganismMesh.PART_EYE);
                    b.box(0f, cabinY + H * 0.04f, L * 0.2f, L * 0.4f, H * 0.24f, W * 0.84f);
                    b.part(OrganismMesh.PART_BODY);
                    boxyOddity(b, rand, L, W, H, cabinY + H * 0.2f, 0f, L * 0.7f);
                } else {
                    b.transform(Affine.translation(0f, cabinY, 0f));
                    OrganismParts.shell(b, OrganismMesh.PART_BODY, L * 0.78f, W * 0.82f, H * 0.42f, 1f + rand.nextFloat() * 0.6f, rand.nextInt(3), 0.1f, 1f);
                    b.transform(Affine.translation(0f, cabinY + H * 0.06f, L * 0.2f));
                    OrganismParts.shell(b, OrganismMesh.PART_EYE, L * 0.36f, W * 0.66f, H * 0.3f, 1.2f, 0, 0f, 1f);
                }
                float splay = 0.55f + rand.nextFloat() * 0.15f;
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        float[] hub = { sx * W * splay, r, sz * L * 0.34f };
                        float[] top = { sx * W * 0.28f, cabinY - H * 0.08f, sz * L * 0.22f };
                        b.transform(Affine.frame(hub, Affine.subtract(top, hub), new float[] { 0f, 0f, 1f }, 1f, 1f, 1f));
                        OrganismParts.limb(b, OrganismMesh.PART_TRIM, Affine.length(Affine.subtract(top, hub)), H * 0.035f, H * 0.025f);
                        hubs.add(new float[] { hub[0], r, hub[2], r, 0f });
                    }
                }
            }
            default -> {
                // A cab towing ribbed cargo segments, eight wheels beneath
                float r = H0 * 0.16f;
                int segments = 1 + rand.nextInt(2);
                float cabLength = L * 0.26f;
                float cargoLength = (L - cabLength) / segments * 0.92f;
                float cabZ = L * 0.5f - cabLength * 0.5f;
                if (boxy) {
                    // A square cab, its windscreen a band round the top
                    b.transform(IDENTITY);
                    b.box(0f, r * 1.3f + H * 0.42f, cabZ, cabLength, H * 0.82f, W * 0.95f);
                    b.part(OrganismMesh.PART_EYE);
                    b.box(0f, r * 1.3f + H * 0.62f, cabZ + cabLength * 0.04f, cabLength * 1.02f, H * 0.22f, W * 0.97f);
                    b.part(OrganismMesh.PART_BODY);
                } else {
                    b.transform(Affine.translation(0f, r * 1.3f + H * 0.42f, cabZ));
                    OrganismParts.shell(b, OrganismMesh.PART_BODY, cabLength, W * 0.95f, H * 0.82f, 0.7f, 0, 0f, 0.8f);
                }
                if (boxy) {
                    // (windows above)
                } else if (open) {
                    cockpit(b, kind, variant, cabZ, r * 1.3f + H * 0.83f, cabLength * 0.7f, W * 0.66f);
                } else {
                    b.transform(Affine.translation(0f, r * 1.3f + H * 0.6f, cabZ + cabLength * 0.12f));
                    OrganismParts.shell(b, OrganismMesh.PART_EYE, cabLength * 0.8f, W * 0.8f, H * 0.36f, 1f, 0, 0f, 1f);
                }
                for (int s = 0; s < segments; s++) {
                    float z = cabZ - cabLength * 0.5f - cargoLength * (s + 0.55f) - s * L * 0.02f;
                    if (boxy) {
                        // Square cargo boxes ribbed round, and an odd thing or two on top
                        b.transform(IDENTITY);
                        b.box(0f, r * 1.3f + H * 0.46f, z, cargoLength, H * 0.86f, W * 0.94f);
                        int ribs = 2 + rand.nextInt(3);
                        for (int k = 1; k <= ribs; k++) {
                            b.box(0f, r * 1.3f + H * 0.46f, z - cargoLength * 0.5f + cargoLength * k / (ribs + 1f), cargoLength * 0.04f, H * 0.9f, W);
                        }
                        if (s == 0) boxyOddity(b, rand, L, W, H, r * 1.3f + H * 0.89f, z, cargoLength);
                    } else {
                        b.transform(Affine.translation(0f, r * 1.3f + H * 0.46f, z));
                        OrganismParts.shell(b, OrganismMesh.PART_TOP, cargoLength, W, H * 0.88f, 0.35f, 2 + rand.nextInt(4), 0.08f, 1f);
                    }
                    hubs.add(new float[] { -W * 0.48f, r, z + cargoLength * 0.3f, r, 0f });
                    hubs.add(new float[] { W * 0.48f, r, z + cargoLength * 0.3f, r, 0f });
                    hubs.add(new float[] { -W * 0.48f, r, z - cargoLength * 0.3f, r, 0f });
                    hubs.add(new float[] { W * 0.48f, r, z - cargoLength * 0.3f, r, 0f });
                }
                hubs.add(new float[] { -W * 0.45f, r, cabZ, r, 0f });
                hubs.add(new float[] { W * 0.45f, r, cabZ, r, 0f });
                // A coupling between cab and cargo
                b.transform(Affine.multiply(Affine.translation(0f, r * 1.4f, cabZ - cabLength * 0.5f), Affine.rotationY((float) Math.PI)));
                OrganismParts.limb(b, OrganismMesh.PART_TRIM, L * 0.06f, H * 0.06f, H * 0.06f);
            }
        }
        // The wheels, each on its own bone with its hub at the bone's origin
        int bone = 1;
        for (float[] hub : hubs) {
            if (bone >= OrganismSpecies.MAX_BONES - 1) break;
            b.bone(bone++).resetTransform();
            float radius = hub[3];
            if (hub[4] > 0f) {
                // A ball
                b.transform(IDENTITY);
                OrganismParts.shell(b, OrganismMesh.PART_DARK, radius * 2f, radius * 2f, radius * 2f, 1f, 0, 0f, 1f);
            } else {
                // A disc: a tyre with a metal hub
                float thick = radius * 0.55f;
                b.transform(Affine.multiply(Affine.rotationY((float) Math.PI / 2f), Affine.translation(0f, 0f, -thick * 0.5f)));
                OrganismParts.limb(b, OrganismMesh.PART_DARK, thick, radius, radius);
                b.transform(Affine.multiply(Affine.rotationY((float) Math.PI / 2f), Affine.translation(0f, 0f, -thick * 0.56f)));
                OrganismParts.limb(b, OrganismMesh.PART_METAL, thick * 1.12f, radius * 0.4f, radius * 0.4f);
            }
        }
        b.transform(IDENTITY);
        wheelsFor(kind).add(hubs);
        return b;
    }

    /**
     * Something odd fixed to a boxy vehicle's roof (at height top, centred at z, over a roof
     * this long): a tall fin, a row of stubby periscopes, a great round tank, a pair of side
     * blisters, or a slanting vane. Leaves the builder on the body part, untransformed.
     */
    private void boxyOddity(OrganismMesh.Builder b, Random rand, float L, float W, float H, float top, float z, float roof) {
        b.part(OrganismMesh.PART_BODY);
        switch (rand.nextInt(5)) {
            case 0 -> {
                b.transform(IDENTITY);
                b.box(0f, top + H * 0.22f, z - roof * 0.25f, roof * 0.45f, H * 0.44f, W * 0.06f);
            }
            case 1 -> {
                int count = 2 + rand.nextInt(3);
                for (int i = 0; i < count; i++) {
                    float px = (i - (count - 1) * 0.5f) * W * 0.22f;
                    b.transform(Affine.frame(new float[] { px, top, z }, new float[] { 0f, 1f, 0.15f }, new float[] { 0f, 0f, 1f }, 1f, 1f, 1f));
                    OrganismParts.limb(b, OrganismMesh.PART_BODY, H * 0.25f, W * 0.05f, W * 0.07f);
                }
            }
            case 2 -> {
                b.transform(Affine.translation(0f, top + H * 0.12f, z));
                OrganismParts.shell(b, OrganismMesh.PART_BODY, roof * 0.6f, W * 0.7f, H * 0.3f, 1f, 0, 0f, 1f);
            }
            case 3 -> {
                for (int side = -1; side <= 1; side += 2) {
                    // (kept inside the body's width, so it looks no wider than it is)
                    b.transform(Affine.translation(side * W * 0.4f, top - H * 0.3f, z));
                    OrganismParts.shell(b, OrganismMesh.PART_BODY, roof * 0.5f, W * 0.18f, H * 0.2f, 1f, 0, 0f, 1f);
                }
            }
            default -> {
                b.transform(Affine.multiply(Affine.translation(0f, top + H * 0.1f, z), Affine.rotationX(0.5f)));
                b.box(0f, 0f, 0f, roof * 0.5f, H * 0.04f, W * 0.9f);
            }
        }
        b.transform(IDENTITY);
    }

    /**
     * An open top's hole for the driver: dark inside, with a rim round it, sunk into the top of
     * the body at (0, top, z) local; noted as where its driver sits.
     */
    private void cockpit(OrganismMesh.Builder b, int kind, int variant, float z, float top, float length, float width) {
        b.transform(Affine.translation(0f, top - length * 0.05f, z));
        OrganismParts.shell(b, OrganismMesh.PART_TRIM, length * 1.12f, width * 1.12f, length * 0.16f, 1f, 0, 0f, 1f);
        b.transform(Affine.translation(0f, top - length * 0.02f, z));
        OrganismParts.shell(b, OrganismMesh.PART_DARK, length, width, length * 0.14f, 1f, 0, 0f, 1f);
        seats[kind][variant] = new float[] { z, top };
    }

    /**
     * Where the driver of an open-topped vehicle sits, as {x, y of the rim, z, heading}; null if
     * it's closed (and its driver can't be seen).
     */
    public float[] seat(Vehicle v) {
        float[] seat = seats[v.kind][v.variant];
        if (seat == null) return null;
        float s = v.length / (KIND_SIZE[v.kind][0] * scale);
        float fx = (float) Math.sin(v.heading), fz = (float) Math.cos(v.heading);
        return new float[] { v.x + fx * seat[0] * s, standingHeight(v) + seat[1] * s, v.z + fz * seat[0] * s, v.heading };
    }

    private final List<List<float[]>> wheelLists = new ArrayList<>();

    private List<List<float[]>> wheelsFor(int kind) {
        // (filled in kind order, VARIANTS of each)
        return wheelLists;
    }

    private List<float[]> hubsOf(Vehicle v) {
        return wheelLists.get(v.kind * VARIANTS + v.variant);
    }

    // ------------------------------------------------------------------ owning and parking

    /** The household's vehicle (parked outside), or null if it hasn't one. */
    public Vehicle forHousehold(InfrastructureManager.Doorway home, Random rand) {
        float share = ownership.getOrDefault(home.nationId, 0.5f) * (1f - 0.35f * home.urbanness);
        if (rand.nextFloat() >= share) return null;
        float[] weights = kindWeights.getOrDefault(home.nationId, new float[] { 1f, 0f, 0f, 0f });
        float roll = rand.nextFloat();
        int kind = 0;
        for (int k = 0; k < KINDS; k++) {
            roll -= weights[k];
            if (roll <= 0f && weights[k] > 0f) { kind = k; break; }
            if (weights[k] > 0f) kind = k;
        }
        Vehicle v = new Vehicle(kind, rand.nextInt(VARIANTS), scale, home, rand);
        float[] spot = parkingSpot(home, v);
        v.parkedAt = home.id;
        v.slot = (int) spot[3];
        place(v, spot);
        return v;
    }

    private int takeSlot(InfrastructureManager.Doorway door) {
        boolean[] taken = slotsTaken.computeIfAbsent(door.id, k -> new boolean[8]);
        for (int i = 0; i < taken.length; i++) {
            if (!taken[i]) { taken[i] = true; return i; }
        }
        return taken.length - 1;
    }

    private void freeSlot(long doorId, int slot) {
        boolean[] taken = slotsTaken.get(doorId);
        if (taken != null && slot >= 0 && slot < taken.length) taken[slot] = false;
    }

    /**
     * Where a vehicle parks at a door. At home, up its own driveway if it has one the vehicle
     * fits, nose in. Otherwise at the road's edge on the door's side, pulled right over and
     * facing the way traffic on that side goes, just clear of the gate (and any driveway) so it
     * never blocks the way in; further places run along the road from there, either way.
     * Returns {x, z, heading, slot (-1 on a driveway), 1 if a driveway, where someone getting
     * in or out stands: x, z}.
     */
    private float[] parkingSpot(InfrastructureManager.Doorway door, Vehicle v) {
        float[] drive = door.driveway;
        if (drive != null && v.home == door && v.length <= drive[4] - 6f && v.width <= drive[5] - 3f) {
            float in = 3f + v.length * 0.5f, aside = v.width * 0.5f + 2.5f;
            float x = drive[0] + drive[2] * in, z = drive[1] + drive[3] * in;
            return new float[] { x, z, (float) Math.atan2(drive[2], drive[3]), -1f, 1f, x + drive[6] * aside, z + drive[7] * aside };
        }
        // Along the road from the door, either way, beyond the opening in the front, each place
        // fitted to the road's edge where it is (round bends too), and none across a junction
        float nx = -door.roadDirZ, nz = door.roadDirX;
        if (nx * (door.doorX - door.kerbX) + nz * (door.doorZ - door.kerbZ) < 0f) { nx = -nx; nz = -nz; }
        float pitch = KIND_SIZE[HAULER][0] * scale * 1.1f + 4f;
        float[] fallback = null;
        for (int tries = 0; tries < 8; tries++) {
            int slot = takeSlot(door);
            int n = slot / 2;
            float along = slot % 2 == 0 ? door.gapAfter + 3f + v.length * 0.5f + n * pitch
                    : door.gapBefore - 3f - v.length * 0.5f - n * pitch;
            float ax = door.kerbX + door.roadDirX * along, az = door.kerbZ + door.roadDirZ * along;
            float[] edge = infrastructure.kerbSpot(ax, az, ax + nx * 40f, az + nz * 40f, v.width);
            if (edge == null) {
                // At a junction: this place is no good (it stays taken), on to the next
                if (fallback == null) fallback = new float[] { ax, az, slot };
                continue;
            }
            return kerbParking(v, edge, slot);
        }
        // Nowhere fit nearby: as near the kerb as can be where the first place was
        float[] edge = infrastructure.kerbSpot(fallback[0], fallback[1], fallback[0] + nx * 40f, fallback[1] + nz * 40f, 0f);
        if (edge == null) edge = new float[] { fallback[0], fallback[1], door.roadDirX, door.roadDirZ, nx, nz, 0f, door.nationId };
        return kerbParking(v, edge, (int) fallback[2]);
    }

    /** A parking place at the road's edge (see InfrastructureManager.kerbSpot), facing with the traffic on that side. */
    private float[] kerbParking(Vehicle v, float[] edge, int slot) {
        float side = infrastructure.drivingSide((int) edge[7]);
        float ex = edge[4], ez = edge[5];
        // Facing so that the kerb is on the side it drives on: right of heading f is (-fz, fx)
        float fx = side * ez, fz = -side * ex;
        float aside = v.width * 0.5f + 2.5f;
        return new float[] { edge[0], edge[1], (float) Math.atan2(fx, fz), slot, 0f, edge[0] + ex * aside, edge[1] + ez * aside };
    }

    private static void place(Vehicle v, float[] spot) {
        v.park = spot;
        v.x = spot[0];
        v.z = spot[1];
        v.heading = spot[2];
        v.parked = true;
        v.speed = 0f;
    }

    /** Whether someone has it for a trip already. */
    public boolean claimed(Vehicle v) {
        return v.claimedBy != null;
    }

    /** Takes it for a trip (or lets it go, given null). */
    public void claim(Vehicle v, Object who) {
        v.claimedBy = who;
    }

    /**
     * A vehicle someone could borrow: parked outside its own home near (x, z), nobody using
     * it, one of the nearest few; null if there's none.
     */
    public Vehicle borrowable(List<Vehicle> all, float x, float z, float reach, Random rand) {
        List<Vehicle> near = new ArrayList<>();
        for (Vehicle v : all) {
            if (!v.parked || v.claimedBy != null || v.home == null || v.parkedAt != v.home.id || v.traffic) continue;
            if (Math.hypot(v.x - x, v.z - z) < reach) near.add(v);
        }
        return near.isEmpty() ? null : near.get(rand.nextInt(near.size()));
    }

    /** Where its owner's household lives (and so where it belongs). */
    public InfrastructureManager.Doorway homeOf(Vehicle v) {
        return v.home;
    }

    /**
     * A vehicle passing through, of the sort a nation's people drive, on its way between
     * two far-off places: driving from the start of a way along the roads to its end, and
     * then onArrive. Null if there's no way by road.
     */
    public Vehicle traffic(int nationId, InfrastructureManager.Doorway near, float[] from, float[] to, Random rand, Runnable onArrive) {
        float[] weights = kindWeights.getOrDefault(nationId, new float[] { 1f, 0f, 0f, 0f });
        float roll = rand.nextFloat();
        int kind = 0;
        for (int k = 0; k < KINDS; k++) {
            roll -= weights[k];
            if (roll <= 0f && weights[k] > 0f) { kind = k; break; }
            if (weights[k] > 0f) kind = k;
        }
        Vehicle v = new Vehicle(kind, rand.nextInt(VARIANTS), scale, near, rand);
        v.traffic = true;
        v.slot = -1;
        return travel(v, from, to, onArrive) ? v : null;
    }

    /** Sends a vehicle passing through on to somewhere else along the roads; false if there's no way. */
    public boolean travel(Vehicle v, float[] from, float[] to, Runnable onArrive) {
        List<float[]> centre = infrastructure.drivingRoute(from[0], from[1], to[0], to[1]);
        if (centre.size() < 2) return false;
        v.route.clear();
        for (float[] p : centre) {
            float[] road = infrastructure.roadAtPoint(p[0], p[1]);
            v.route.add(new float[] { p[0], p[1], infrastructure.drivingSide((int) road[2]), road[0], road[1], road[3] });
        }
        // At the end it pulls in at the side of the road (the side it drives on), out of the way
        float[] end = centre.get(centre.size() - 1), before = centre.get(centre.size() - 2);
        float dx = end[0] - before[0], dz = end[1] - before[1], dl = (float) Math.max(1e-3, Math.hypot(dx, dz));
        float side = infrastructure.drivingSide((int) infrastructure.roadAtPoint(end[0], end[1])[2]);
        float rx = -dz / dl * side, rz = dx / dl * side;
        float[] edge = infrastructure.kerbSpot(end[0], end[1], end[0] + rx * 40f, end[1] + rz * 40f, v.width);
        v.park = edge != null ? kerbParking(v, edge, -1) : new float[] { end[0] + rx * 8f, end[1] + rz * 8f, (float) Math.atan2(dx, dz), -1f, 0f, end[0], end[1] };
        v.onArrive = onArrive;
        v.parked = false;
        v.settling = false;
        v.backOut = null;
        v.approach = null;
        v.stuck = 0f;
        v.shift = 0f;
        // Starting out in its lane at the start, facing along the road
        v.next = 1;
        float[] at = target(v);
        float[] ahead = v.route.get(1);
        v.x = at[0];
        v.z = at[1];
        v.heading = (float) Math.atan2(ahead[0] - v.route.get(0)[0], ahead[1] - v.route.get(0)[1]);
        v.speed = KIND_SPEED[v.kind][0] * 0.5f;
        return true;
    }

    /**
     * A vehicle that has just set off (see drive) as though it had already gone part of the way:
     * on along its route by the given share, in its lane, moving.
     */
    public void skipAlong(Vehicle v, float share) {
        if (v.route.size() < 3) return;
        v.backOut = null;
        int k = 1 + Math.min(v.route.size() - 2, (int) (share * (v.route.size() - 2)));
        v.next = k;
        float[] at = target(v);
        float[] before = v.route.get(k - 1), after = v.route.get(k);
        v.x = at[0];
        v.z = at[1];
        v.heading = (float) Math.atan2(after[0] - before[0], after[1] - before[1]);
        v.speed = KIND_SPEED[v.kind][0] * 0.6f;
        v.liftX = Float.NaN;
    }

    /** Gives up a parking place kept but not used. */
    public void cancel(Vehicle v, float[] spot) {
        // (the place is kept under the door it's at; find it by the spot's slot at any door: the
        // caller's door id isn't kept, so free the slot wherever it's marked for this vehicle's trip)
        pendingRelease(v, spot);
    }

    private final Map<float[], Long> reservedAt = new java.util.IdentityHashMap<>();

    private void pendingRelease(Vehicle v, float[] spot) {
        Long door = reservedAt.remove(spot);
        if (door != null) freeSlot(door, (int) spot[3]);
    }

    /** The household has gone (its neighbourhood out of range): its car's place is free again. */
    public void release(Vehicle v) {
        freeSlot(v.parkedAt, v.slot);
    }

    /** Where someone getting in stands: beside the vehicle where it's parked, on the kerb (or garden path) side. */
    public float[] doorSide(Vehicle v, InfrastructureManager.Doorway door) {
        if (v.park != null && v.park.length > 6) return new float[] { v.park[5], v.park[6] };
        float nx = -door.roadDirZ, nz = door.roadDirX;
        if (nx * (door.doorX - door.kerbX) + nz * (door.doorZ - door.kerbZ) < 0f) { nx = -nx; nz = -nz; }
        float out = v.width * 0.5f + 2.5f;
        return new float[] { v.x + nx * out, v.z + nz * out };
    }

    /** Where a vehicle will park at a door (keeping the place for it): see parkingSpot. */
    public float[] reserve(Vehicle v, InfrastructureManager.Doorway door) {
        float[] spot = parkingSpot(door, v);
        if (spot[3] >= 0f) reservedAt.put(spot, door.id);
        return spot;
    }

    /** Where someone stands to get out at a reserved place by a door. */
    public float[] doorSideAt(Vehicle v, float[] spot, InfrastructureManager.Doorway door) {
        return new float[] { spot[5], spot[6] };
    }

    // ------------------------------------------------------------------ driving

    /**
     * Sets off from where it's parked: along the roads (by way of somewhere, for a drive out
     * and back, if via isn't null) to its reserved place at a door, then onArrive.
     */
    public boolean drive(Vehicle v, float[] via, InfrastructureManager.Doorway to, float[] spot, Runnable onArrive) {
        // Up a driveway: first back out into the road
        float startX = v.x, startZ = v.z;
        float[] backOut = null;
        float[] home = v.home.driveway;
        if (v.park != null && v.park.length > 4 && v.park[4] > 0f && home != null) {
            float back = 5f + v.length * 0.5f;
            backOut = new float[] { home[0] - home[2] * back, home[1] - home[3] * back };
            startX = backOut[0];
            startZ = backOut[1];
        }
        List<float[]> centre = new ArrayList<>();
        if (via != null) {
            List<float[]> out = infrastructure.drivingRoute(startX, startZ, via[0], via[1]);
            List<float[]> back = infrastructure.drivingRoute(via[0], via[1], spot[0], spot[1]);
            if (out.size() < 2 || back.size() < 2) return false;
            centre.addAll(out);
            centre.addAll(back.subList(1, back.size()));
        } else {
            centre.addAll(infrastructure.drivingRoute(startX, startZ, spot[0], spot[1]));
        }
        if (centre.size() < 2) return false;
        v.route.clear();
        for (float[] p : centre) {
            float[] road = infrastructure.roadAtPoint(p[0], p[1]);
            v.route.add(new float[] { p[0], p[1], infrastructure.drivingSide((int) road[2]), road[0], road[1], road[3] });
        }
        freeSlot(v.parkedAt, v.slot);
        reservedAt.remove(spot);
        v.parkedAt = to.id;
        v.slot = (int) spot[3];
        v.park = spot;
        v.onArrive = onArrive;
        v.next = 1;
        v.parked = false;
        v.settling = false;
        v.stuck = 0f;
        v.shift = 0f;
        v.backOut = backOut;
        v.approach = null;
        if (spot[4] > 0f && to.driveway != null) {
            // Turning in: from out in the road across from the driveway
            float out = 3f + infrastructure.roadAtPoint(to.kerbX, to.kerbZ)[0] * 0.45f;
            v.approach = new float[] { to.driveway[0] - to.driveway[2] * out, to.driveway[1] - to.driveway[3] * out };
        }
        return true;
    }

    /** Moves every vehicle on, and lists them all as solid for this frame. */
    public void update(List<Vehicle> all, float dt, float viewerX, float viewerZ) {
        this.viewerX = viewerX;
        this.viewerZ = viewerZ;
        for (Vehicle v : all) {
            if (!v.parked) step(v, all, dt);
            else if (v.settling) settle(v, dt);
        }
        if (collision == null) return;
        for (Vehicle v : all) {
            // Solid along its whole length: overlapping circles from nose to tail
            int circles = Math.max(2, Math.round(v.length / v.width) + 1);
            float fx = (float) Math.sin(v.heading), fz = (float) Math.cos(v.heading);
            for (int i = 0; i < circles; i++) {
                float t = (i / (float) (circles - 1) - 0.5f) * (v.length - v.width);
                collision.addBody(v, v.x + fx * t, v.z + fz * t, v.width * HITBOX, true);
            }
        }
    }

    /** The point it's steering for: the next route point out in its lane, or at the end its parking place. */
    private float[] target(Vehicle v) {
        if (v.next >= v.route.size()) return v.approach != null ? v.approach : v.park;
        float[] p = v.route.get(v.next);
        float[] before = v.route.get(Math.max(0, v.next - 1)), after = v.route.get(Math.min(v.route.size() - 1, v.next + 1));
        float dx = after[0] - before[0], dz = after[1] - before[1];
        float length = (float) Math.hypot(dx, dz);
        if (length < 1e-3f) return p;
        float rx = -dz / length, rz = dx / length;
        float lane = laneOffset(v, p[3]) - v.shift;
        return new float[] { p[0] + rx * p[2] * lane, p[1] + rz * p[2] * lane };
    }

    /**
     * How far out from the road's middle a vehicle keeps: in its own half, but never so far
     * that its outer side is off the road (a wide one on a narrow road keeps nearer the middle).
     */
    // How much of a vehicle's width is solid to everything else (a little less than all of it, so
    // passing traffic squeezes by rather than locking together)
    private static final float HITBOX = 0.4f;

    private static final java.util.function.Predicate<Object> IS_VEHICLE = o -> o instanceof Vehicle;

    private static float laneOffset(Vehicle v, float half) {
        float wanted = Math.max(v.width * 0.6f, half * LANE_SHARE);
        return Math.max(half * 0.2f, Math.min(wanted, half - v.width * 0.5f - 0.5f));
    }

    private void step(Vehicle v, List<Vehicle> all, float dt) {
        if (v.backOut != null) {
            backOut(v, dt);
            return;
        }
        if (v.reversing > 0f) {
            reverse(v, dt);
            return;
        }
        float[] t = target(v);
        if (v.detour > 0f) {
            // Going round what it was stuck on: aiming off to one side of its way for a while
            // (to the side of the way to the point, not of where it faces, which would turn with it)
            v.detour -= dt;
            float wx = t[0] - v.x, wz = t[1] - v.z, wl = (float) Math.max(1e-3, Math.hypot(wx, wz));
            float off = v.detourSide * (v.width * 0.9f + 3f);
            t = new float[] { t[0] - wz / wl * off, t[1] + wx / wl * off };
        }
        boolean approaching = v.next >= v.route.size() && v.approach != null;
        boolean final_ = v.next >= v.route.size() && !approaching;
        float dx = t[0] - v.x, dz = t[1] - v.z;
        float distance = (float) Math.hypot(dx, dz);
        if (approaching && distance < 3f) {
            v.approach = null;
            return;
        }
        if (!final_ && !approaching && distance < Math.max(8f, v.speed * 0.25f)) {
            v.next++;
            v.circling = 0f;
            return;
        }
        if (v.circling > (float) Math.PI * 2f) {
            // Gone right round without getting to the point: on to the next (or, at the end, parked there)
            v.circling = 0f;
            if (!final_ && !approaching) {
                v.next++;
                return;
            }
            if (approaching) {
                v.approach = null;
                return;
            }
            if (distance < 20f) {
                v.x = t[0];
                v.z = t[1];
                distance = 0f;
            }
        }
        if (final_ && distance < 1.5f) {
            // Arrived: pulled in, it straightens up and stops
            v.x = t[0];
            v.z = t[1];
            v.speed = 0f;
            v.parked = true;
            v.settling = true;
            v.route.clear();
            if (v.onArrive != null) {
                Runnable arrive = v.onArrive;
                v.onArrive = null;
                arrive.run();
            }
            return;
        }
        float[] here = v.route.isEmpty() ? null : v.route.get(Math.min(v.next, v.route.size() - 1));
        float limit = KIND_SPEED[v.kind][here != null && here[4] >= 2f ? 1 : 0];
        if (here != null && here[5] > 0f) limit *= 0.55f;
        float desired = (float) Math.atan2(dx, dz);
        float turn = (float) Math.atan2(Math.sin(desired - v.heading), Math.cos(desired - v.heading));
        // Slow for bends, and to pull in at the end
        limit *= Math.max(0.25f, (float) Math.cos(Math.min(Math.abs(turn), 1.4f)));
        // A sharp turn to make: slow enough to turn inside the distance to the point, or it would
        // circle round it for ever (at highway speed it can't turn tightly)
        if (Math.abs(turn) > 0.5f) limit = Math.min(limit, distance * 0.6f + 3f);
        if (final_ || approaching) limit = Math.min(limit, distance * 1.2f + 2f);

        float fx = (float) Math.sin(v.heading), fz = (float) Math.cos(v.heading);
        float rx = -fz, rz = fx;
        float wantShift = 0f;
        float look = v.length * 0.5f + 10f + v.speed * 1.2f;
        // Out to the other side of the road to pass: from its lane to the far lane, keeping on the road
        float lane = here != null ? laneOffset(v, here[3]) : v.width;
        float passShift = here != null ? Math.min(lane * 2f, lane + here[3] - v.width * 0.5f) : lane * 2f;
        Vehicle stopped = null;
        boolean oncoming = false;
        for (Vehicle o : all) {
            if (o == v) continue;
            float ox = o.x - v.x, oz = o.z - v.z;
            float reach = Math.max(look, 90f) + o.length;
            if (Math.abs(ox) > reach || Math.abs(oz) > reach) continue;
            float ahead = ox * fx + oz * fz, across = ox * rx + oz * rz;
            if (ahead <= 0f) continue;
            // Anything coming the other way on the stretch it would pass along
            if (!o.parked && o.speed > 2f && ahead < 90f + o.length
                    && (fx * (float) Math.sin(o.heading) + fz * (float) Math.cos(o.heading)) < -0.3f
                    && Math.abs(across) < passShift + (v.width + o.width) * 0.5f + 2f) {
                oncoming = true;
            }
            if (ahead > look + o.length * 0.5f) continue;
            float clear = (v.width + o.width) * 0.5f + 1f;
            if (Math.abs(across) >= clear) continue;
            boolean headOn = !o.parked && o.speed > 1f && (fx * (float) Math.sin(o.heading) + fz * (float) Math.cos(o.heading)) < -0.3f;
            if (headOn) {
                // Meeting something coming the other way: both keep over to their own edge (up
                // onto the verge a little if they must) and pass, slowing as they do; one out
                // overtaking gives that up and gets back in
                v.overtaking = 0f;
                wantShift = Math.min(wantShift, -Math.min(4f, clear - Math.abs(across) + 0.5f));
                limit = Math.min(limit, Math.max(6f, ahead * 0.6f));
                continue;
            }
            float alongDot = fx * (float) Math.sin(o.heading) + fz * (float) Math.cos(o.heading);
            if (!o.parked && o.speed > 1f && alongDot < 0.5f && System.identityHashCode(v) < System.identityHashCode(o)) {
                // Crossing its way (at a junction): one of the two gives way, the other goes on
                continue;
            }
            if (o.parked && !final_) {
                // A parked vehicle in the lane: ease out round it
                wantShift = Math.max(wantShift, clear - Math.abs(across) + 0.5f);
            } else {
                // Traffic ahead: keep a gap behind it
                float gap = ahead - (v.length + o.length) * 0.5f - 4f;
                limit = Math.min(limit, Math.max(0f, Math.min(o.speed + gap * 0.8f, gap * 1.5f)));
                if (o.speed < 2f && !final_ && !approaching) stopped = o;
            }
        }
        if (stopped != null && !oncoming && v.overtaking <= 0f) {
            // Something stopped in front of it: out to the other side of the road to get by
            v.overtaking = (stopped.length + v.length) / Math.max(8f, KIND_SPEED[v.kind][0] * 0.5f) + 2.5f;
            v.overtakeShift = passShift;
        }
        if (v.overtaking > 0f) {
            v.overtaking -= dt;
            // Back in if something comes the other way before it's out
            if (oncoming && v.shift < v.overtakeShift * 0.5f) v.overtaking = 0f;
            if (v.overtaking > 0f) wantShift = Math.max(wantShift, v.overtakeShift);
            if (Math.abs(v.shift - v.overtakeShift) > 2f) limit = Math.min(limit, 18f);
        }
        v.shift += (Math.max(-4f, Math.min(wantShift, Math.max(12f, passShift))) - v.shift) * Math.min(1f, dt * 2f);
        // Anything solid just ahead (people, walls, posts, the player): stop for it
        boolean obstacle = false;
        if (collision != null && v.ghost <= 0f) {
            // Looking the way it's steering (round a bend, not straight on into the gardens)
            float reach = v.length * 0.5f + 2f + v.speed * 0.25f;
            float lx = distance > 1e-3f ? dx / distance : fx, lz = distance > 1e-3f ? dz / distance : fz;
            // (other vehicles are kept clear of above, by following, overtaking and passing)
            if (collision.blocked(v.x + lx * reach, v.z + lz * reach, v.width * 0.42f, v, IS_VEHICLE)) {
                limit = 0f;
                obstacle = true;
            }
        }
        if (limit < 1f && v.speed < 1f) {
            v.stuck += dt;
            if (obstacle && final_ && v.stuck > 1.5f) {
                // Pulling in at the end and something's in the way: it stops where it is
                v.park = new float[] { v.x, v.z, v.heading, v.park != null ? v.park[3] : -1f, 0f, v.x, v.z };
                if (v.park.length > 5) {
                    float out = v.width * 0.5f + 2.5f;
                    v.park[5] = v.x - fz * out;
                    v.park[6] = v.z + fx * out;
                }
                v.stuck = 0f;
            } else if (obstacle && v.stuck > 2.5f && v.tries < 3) {
                // Stuck on something: back off a little, then go round it on its clearer side
                v.tries++;
                v.stuck = 0f;
                v.reversing = 1.4f;
                v.detour = 3.5f;
                float reach = v.length * 0.5f + 4f, aside = v.width * 0.8f;
                boolean leftBlocked = collision.blocked(v.x + fx * reach - rx * aside, v.z + fz * reach - rz * aside, v.width * 0.4f, v);
                boolean rightBlocked = collision.blocked(v.x + fx * reach + rx * aside, v.z + fz * reach + rz * aside, v.width * 0.4f, v);
                v.detourSide = leftBlocked == rightBlocked ? (v.tries % 2 == 0 ? 1 : -1) : leftBlocked ? 1 : -1;
                // Up against the road's edge (a crash barrier, say): always back in towards the middle
                float lateral = lateralOffset(v);
                float[] at = v.route.get(Math.min(v.next, v.route.size() - 1));
                if (!Float.isNaN(lateral) && Math.abs(lateral) > at[3] - v.width - 2f) v.detourSide = lateral > 0f ? -1 : 1;
            } else if (v.stuck > (obstacle ? 4f : 10f)) {
                // Still held up after that: out of sight it moves on; in sight it edges through
                if (Math.hypot(v.x - viewerX, v.z - viewerZ) > OUT_OF_SIGHT && !final_) {
                    float[] p = target(v);
                    v.x = p[0];
                    v.z = p[1];
                    v.next = Math.min(v.next + 1, v.route.size());
                } else {
                    v.ghost = 2f;
                }
                v.stuck = 0f;
                v.tries = 0;
            }
        } else {
            v.stuck = 0f;
            if (v.speed > 10f && v.detour <= 0f) v.tries = 0;
        }
        v.ghost -= dt;
        if (v.ghost > 0f) limit = Math.max(limit, 6f);
        float accel = limit > v.speed ? 22f : 55f;
        v.speed += Math.max(-accel * dt, Math.min(accel * dt, limit - v.speed));
        float rate = 1.8f * Math.min(1f, 12f / Math.max(4f, v.speed)) + 0.4f;
        float turned = Math.max(-rate * dt, Math.min(rate * dt, turn));
        v.heading += turned;
        // How far round it has turned chasing this point (a whole turn means it's going in circles)
        v.circling = Math.abs(v.circling + turned) > Math.abs(v.circling) ? v.circling + turned : turned;
        float move = v.speed * dt;
        v.x += (float) Math.sin(v.heading) * move;
        v.z += (float) Math.cos(v.heading) * move;
        v.spin += move;
        // Solid: if it has run into anything (another vehicle, a wall, a post), it's pushed back
        // out, nose and tail alike (but not turning into a driveway, close by the fence)
        boolean intoDriveway = final_ && v.park != null && v.park.length > 4 && v.park[4] > 0f;
        if (collision != null && v.ghost <= 0f && !intoDriveway) {
            float half = (v.length - v.width) * 0.5f, pushX = 0f, pushZ = 0f;
            float hx = (float) Math.sin(v.heading), hz = (float) Math.cos(v.heading);
            for (float along : new float[] { -half, half }) {
                float cx = v.x + hx * along, cz = v.z + hz * along;
                float[] free = collision.resolve(cx, cz, v.width * HITBOX, v);
                pushX += (free[0] - cx) * 0.5f;
                pushZ += (free[1] - cz) * 0.5f;
            }
            // Shoved back from the front (meeting something head on): pushed out sideways towards
            // its own edge instead, off the road if need be, so the two slide past; it steers back
            // into its lane further on
            float back = -(pushX * hx + pushZ * hz);
            if (back > 0f) {
                float side = here != null ? here[2] : 1f;
                // ... unless that's into a crash barrier or a wall: then towards the middle
                float ox = v.x + pushX - hz * side * back * 3f, oz = v.z + pushZ + hx * side * back * 3f;
                if (collision.blocked(ox, oz, v.width * HITBOX, v, IS_VEHICLE)) side = -side;
                pushX += hx * back - hz * side * back;
                pushZ += hz * back + hx * side * back;
            }
            v.x += pushX;
            v.z += pushZ;
        }
    }

    /**
     * How far it is to the right (positive) or left of the road's middle along the stretch of
     * route it's on; NaN if it's not on one.
     */
    private static float lateralOffset(Vehicle v) {
        if (v.route.size() < 2) return Float.NaN;
        int k = Math.max(1, Math.min(v.next, v.route.size() - 1));
        float[] a = v.route.get(k - 1), b = v.route.get(k);
        float dx = b[0] - a[0], dz = b[1] - a[1], length = (float) Math.hypot(dx, dz);
        if (length < 1e-3f) return Float.NaN;
        // Right of the way along is (-dz, dx)
        return ((v.x - a[0]) * -dz + (v.z - a[1]) * dx) / length;
    }

    /** Backing off from what it's stuck on, the tail swinging away from the side it will go round by. */
    private void reverse(Vehicle v, float dt) {
        v.reversing -= dt;
        float fx = (float) Math.sin(v.heading), fz = (float) Math.cos(v.heading);
        // Unless something is behind it too
        if (collision != null && collision.blocked(v.x - fx * (v.length * 0.5f + 2f), v.z - fz * (v.length * 0.5f + 2f), v.width * 0.42f, v)) {
            v.reversing = 0f;
            return;
        }
        float move = 7f * dt;
        v.x -= fx * move;
        v.z -= fz * move;
        v.heading -= v.detourSide * 0.35f * dt;
        v.spin -= move;
        v.speed = 0f;
    }

    /** Reversing slowly out of a driveway into the road, straightening up as it goes. */
    private void backOut(Vehicle v, float dt) {
        float bx = v.backOut[0] - v.x, bz = v.backOut[1] - v.z;
        float d = (float) Math.hypot(bx, bz);
        if (d < 1.5f) {
            v.backOut = null;
            v.speed = 0f;
            return;
        }
        // Facing away from where it's going
        float desired = (float) Math.atan2(-bx, -bz);
        float turn = (float) Math.atan2(Math.sin(desired - v.heading), Math.cos(desired - v.heading));
        v.heading += Math.max(-0.8f * dt, Math.min(0.8f * dt, turn));
        float move = Math.min(10f, d * 1.2f + 1.5f) * dt;
        v.x += bx / d * move;
        v.z += bz / d * move;
        v.spin -= move;
        v.speed = 0f;
    }

    /** Just parked: turns square to the kerb. */
    private void settle(Vehicle v, float dt) {
        if (v.park == null) { v.settling = false; return; }
        float turn = (float) Math.atan2(Math.sin(v.park[2] - v.heading), Math.cos(v.park[2] - v.heading));
        v.heading += Math.max(-1.5f * dt, Math.min(1.5f * dt, turn));
        if (Math.abs(turn) < 0.01f) v.settling = false;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the vehicles near enough to see, with the organism shader already set up: body
     * in its colour and pattern, glass, trim and tyres, the wheels turned as far as it has gone.
     */
    public void render(GL3 gl, Shader shader, List<Vehicle> all, Frustum frustum, float viewX, float viewZ, float drawDistance) {
        int bonesLocation = gl.glGetUniformLocation(shader.getID(), "bones");
        for (Vehicle v : all) {
            float dx = v.x - viewX, dz = v.z - viewZ;
            if (dx * dx + dz * dz > drawDistance * drawDistance) continue;
            float ground = standingHeight(v);
            if (!frustum.intersectsSphere(v.x, ground + v.height * 0.5f, v.z, v.length * 0.6f)) continue;
            List<float[]> hubs = hubsOf(v);
            float s = v.length / (KIND_SIZE[v.kind][0] * scale);
            float[] body = Affine.multiply(Affine.translation(v.x, ground, v.z),
                    Affine.multiply(Affine.rotationY(v.heading), Affine.multiply(Affine.rotationX(-v.pitch), Affine.scale(s, s, s))));
            System.arraycopy(body, 0, bones, 0, 16);
            int count = 1;
            for (float[] hub : hubs) {
                if (count >= OrganismSpecies.MAX_BONES - 1) break;
                float[] m = Affine.multiply(body, Affine.multiply(Affine.translation(hub[0], hub[1], hub[2]), Affine.rotationX(v.spin / Math.max(0.1f, hub[3] * s))));
                System.arraycopy(m, 0, bones, count * 16, 16);
                count++;
            }
            gl.glUniformMatrix4fv(bonesLocation, count, false, bones, 0);
            shader.setVec3(gl, "baseColour", vec(v.body));
            shader.setVec3(gl, "bellyColour", vec(v.body));
            shader.setVec3(gl, "accentColour", vec(v.accent));
            shader.setVec3(gl, "limbColour", vec(v.trim));
            shader.setVec3(gl, "trimColour", vec(v.trim));
            shader.setVec3(gl, "eyeColour", vec(v.glass));
            shader.setVec3(gl, "hairColour", vec(v.trim));
            shader.setInt(gl, "patternType", v.pattern);
            shader.setFloat(gl, "patternScale", v.patternScale);
            shader.setInt(gl, "flagOnTop", 0);
            meshes[v.kind][v.variant].render(gl);
        }
    }

    /** The height of the road (or ground) under it, and its tilt along its length; the road's lift found again every few units. */
    private float standingHeight(Vehicle v) {
        float ground = TerrainMesh.getLayeredHeight(v.x, v.z, terrainNoise);
        if (Float.isNaN(v.liftX) || Math.abs(v.x - v.liftX) + Math.abs(v.z - v.liftZ) > 5f) {
            v.liftX = v.x;
            v.liftZ = v.z;
            v.lift = infrastructure.walkingSurfaceY(v.x, v.z) - ground;
            float fx = (float) Math.sin(v.heading), fz = (float) Math.cos(v.heading), half = v.length * 0.4f;
            float front = TerrainMesh.getLayeredHeight(v.x + fx * half, v.z + fz * half, terrainNoise);
            float back = TerrainMesh.getLayeredHeight(v.x - fx * half, v.z - fz * half, terrainNoise);
            v.pitch = (float) Math.atan2(front - back, half * 2f) * (v.lift > 0.5f ? 0.3f : 1f);
        }
        return ground + v.lift;
    }

    private static com.xenoguesser.math.Vector3 vec(float[] c) {
        return new com.xenoguesser.math.Vector3(c[0], c[1], c[2]);
    }

    /** The name of a kind, for the log. */
    public static String kindName(int kind) {
        return KIND_NAMES[kind];
    }
}
