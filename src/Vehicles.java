import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Vector3;
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
    // Every closed variation stands taller than the people in it (the tallest of them included),
    // so only the open-topped ones are low enough to see over
    private static final float[][] VARIANT_HEIGHT = { { 1f, 2.1f, 2.1f }, { 1f, 2.3f, 2.3f }, { 1.21f, 1.25f, 1.3f }, { 1f, 1.65f, 1.7f } };
    // How often each variation is made: the open-topped one least, the taller ones more
    private static final float[] VARIANT_SHARE = { 0.25f, 0.375f, 0.375f };

    private static int pickVariant(Random rand) {
        float roll = rand.nextFloat();
        for (int v = 0; v < VARIANTS; v++) {
            roll -= VARIANT_SHARE[v];
            if (roll <= 0f) return v;
        }
        return VARIANTS - 1;
    }

    private static boolean openTop(int kind, int variant) {
        return variant == 0 && kind != STILT;
    }

    // Vehicles are drawn out to here, well into the distance fog
    public static final float DRAW_DISTANCE = 1700f;
    // Further than this from the viewer, a vehicle can't be seen
    public static final float OUT_OF_SIGHT = DRAW_DISTANCE + 50f;

    // Lanes run this far out from the road's middle (as a share of its half width)
    private static final float LANE_SHARE = 0.32f;

    /**
     * One vehicle, parked or on the move. It is where it is on the planet (on the datum) and
     * faces a heading (D10) in the frame there; each step is worked out in that frame (D3) and
     * the heading carried along with it.
     */
    public static final class Vehicle {
        final int kind, variant;
        final float length, width, height;
        final float[] body, accent, trim, glass;
        final int pattern;
        final float patternScale;
        final InfrastructureManager.Doorway home;
        SpherePosition position;
        double heading;
        // Which way it faces as a world direction, worked out once a step for the others' tests
        double[] forward;
        float speed, spin, pitch;
        boolean parked = true;
        // Where it's parked (or will park): which door's kerb, and which place along it
        long parkedAt;
        int slot;
        // The way it's driving; then its parking place
        final List<Stop> route = new ArrayList<>();
        int next;
        Spot park;
        Runnable onArrive;
        // Reversing out of a driveway to here before setting off; turning in at a driveway from here
        SpherePosition backOut, approach;
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
        // The border check it last stopped at, and how much longer it waits there
        int checkedAt = -1;
        float checkWait;
        int detourSide, tries;
        // The road deck's height above the ground where it stands (slow to find), and where that was found
        float lift;
        SpherePosition liftAt;
        boolean settling;
        // The nation whose sort of vehicle it is, the seed its looks were drawn from, and whether
        // that was a household's (after the draw for whether it has one), so another PC can make
        // the same one (see replica)
        int looksNation;
        long looks;
        boolean householdLooks;

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

        /** Where it is, on the datum. */
        public SpherePosition position() {
            return position;
        }

        /** Which door's kerb it's parked at (or bound for). */
        public long parkedAt() {
            return parkedAt;
        }
    }

    /**
     * A point of a vehicle's way: on the road's middle, the side its traffic keeps to (1 right,
     * -1 left), the road's half width, its rank (2 highway) and 1 if it's a dirt track.
     */
    record Stop(SpherePosition place, float side, float half, float rank, float dirt) {
    }

    /**
     * Where a vehicle parks: the place, which way it faces there (D10), its place along the
     * door's kerb (-1 if none, or up a driveway), whether up a driveway, and where someone
     * getting in or out stands.
     */
    public record Spot(SpherePosition place, double heading, int slot, boolean driveway, SpherePosition getIn) {
    }

    /** Where an open-topped vehicle's driver sits: the place under the seat, the rim's height above the datum, and the way it faces (D10). */
    public record Seat(SpherePosition place, float rim, double heading) {
    }

    private final InfrastructureManager infrastructure;
    private final PerlinNoise terrainNoise;
    private final float scale;
    private final long seed;
    // The mesh of each kind's variations, and where each wheel's hub is and how big it is
    private final OrganismMesh.Builder[][] builders = new OrganismMesh.Builder[KINDS][VARIANTS];
    private final OrganismMesh[][] meshes = new OrganismMesh[KINDS][VARIANTS];
    // Each nation's taste in vehicles, how many of its households have one, and its side of the road
    private final Map<Integer, float[]> kindWeights = new HashMap<>();
    private final Map<Integer, Float> ownership = new HashMap<>();
    // Places taken along each door's kerb (by door id)
    private final Map<Long, boolean[]> slotsTaken = new HashMap<>();
    private GeodesicCollision collision;
    // Whether a place is out of everyone's sight (the viewer's, and in multiplayer the partner's)
    private java.util.function.Predicate<SpherePosition> unseen = p -> true;
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

    public void setCollision(GeodesicCollision collision) {
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

    /** Where the driver of an open-topped vehicle sits; null if it's closed (and its driver can't be seen). */
    public Seat seat(Vehicle v) {
        float[] seat = seats[v.kind][v.variant];
        if (seat == null) return null;
        float s = v.length / (KIND_SIZE[v.kind][0] * scale);
        double ahead = seat[0] * s;
        SpherePosition at = SphereFrame.at(v.position).toSphere(Math.cos(v.heading) * ahead, 0.0, Math.sin(v.heading) * ahead);
        return new Seat(at, standingHeight(v) + seat[1] * s, SphereMath.carryHeading(v.position, at, v.heading));
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
    public Vehicle forHousehold(InfrastructureManager.Doorway home, long looks) {
        Random rand = new Random(looks);
        float share = ownership.getOrDefault(home.nationId, 0.5f) * (1f - 0.35f * home.urbanness);
        if (rand.nextFloat() >= share) return null;
        int kind = kindFor(home.nationId, rand);
        Vehicle v = new Vehicle(kind, pickVariant(rand), scale, home, rand);
        v.looksNation = home.nationId;
        v.looks = looks;
        v.householdLooks = true;
        Spot spot = parkingSpot(home, v);
        v.parkedAt = home.id;
        v.slot = spot.slot();
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
     * Laid out in the house's frame.
     */
    private Spot parkingSpot(InfrastructureManager.Doorway door, Vehicle v) {
        float[] drive = door.localDriveway;
        if (drive != null && v.home == door && v.length <= drive[4] - 6f && v.width <= drive[5] - 3f) {
            float in = 3f + v.length * 0.5f, aside = v.width * 0.5f + 2.5f;
            float x = drive[0] + drive[2] * in, z = drive[1] + drive[3] * in;
            SpherePosition at = door.at(x, z);
            return new Spot(at, door.heading(at, drive[2], drive[3]), -1, true, door.at(x + drive[6] * aside, z + drive[7] * aside));
        }
        // Along the road from the door, either way, beyond the opening in the front, each place
        // fitted to the road's edge where it is (round bends too), and none across a junction
        float[] kerb = door.localKerb, road = door.localRoadDir, doorstep = door.localDoor;
        float nx = -road[1], nz = road[0];
        if (nx * (doorstep[0] - kerb[0]) + nz * (doorstep[1] - kerb[1]) < 0f) { nx = -nx; nz = -nz; }
        float pitch = KIND_SIZE[HAULER][0] * scale * 1.1f + 4f;
        float[] fallback = null;
        for (int tries = 0; tries < 8; tries++) {
            int slot = takeSlot(door);
            int n = slot / 2;
            float along = slot % 2 == 0 ? door.gapAfter + 3f + v.length * 0.5f + n * pitch
                    : door.gapBefore - 3f - v.length * 0.5f - n * pitch;
            float ax = kerb[0] + road[0] * along, az = kerb[1] + road[1] * along;
            InfrastructureManager.Kerb edge = infrastructure.kerbSpot(door.at(ax, az), door.at(ax + nx * 40f, az + nz * 40f), v.width);
            if (edge == null) {
                // At a junction: this place is no good (it stays taken), on to the next
                if (fallback == null) fallback = new float[] { ax, az, slot };
                continue;
            }
            return kerbParking(v, edge, slot);
        }
        // Nowhere fit nearby: as near the kerb as can be where the first place was
        InfrastructureManager.Kerb edge = infrastructure.kerbSpot(door.at(fallback[0], fallback[1]),
                door.at(fallback[0] + nx * 40f, fallback[1] + nz * 40f), 0f);
        if (edge == null) {
            SpherePosition at = door.at(fallback[0], fallback[1]);
            edge = new InfrastructureManager.Kerb(at, door.heading(at, road[0], road[1]), door.heading(at, nx, nz), 0f, door.nationId);
        }
        return kerbParking(v, edge, (int) fallback[2]);
    }

    /** A parking place at the road's edge (see InfrastructureManager.kerbSpot), facing with the traffic on that side. */
    private Spot kerbParking(Vehicle v, InfrastructureManager.Kerb edge, int slot) {
        float side = infrastructure.drivingSide(edge.nationId());
        double ex = Math.cos(edge.out()), ez = Math.sin(edge.out());
        // Facing so that the kerb is on the side it drives on: right of heading f is (-fz, fx)
        double fx = side * ez, fz = -side * ex;
        float aside = v.width * 0.5f + 2.5f;
        SphereFrame frame = SphereFrame.at(edge.place());
        return new Spot(edge.place(), Math.atan2(fz, fx), slot, false, frame.toSphere(ex * aside, 0.0, ez * aside));
    }

    private static void place(Vehicle v, Spot spot) {
        v.park = spot;
        v.position = spot.place();
        v.heading = spot.heading();
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
     * A vehicle someone could borrow: parked outside its own home within reach of a place,
     * nobody using it, one of the nearest few; null if there's none.
     */
    public Vehicle borrowable(List<Vehicle> all, SpherePosition near, float reach, Random rand) {
        List<Vehicle> found = new ArrayList<>();
        for (Vehicle v : all) {
            if (!v.parked || v.claimedBy != null || v.home == null || v.parkedAt != v.home.id || v.traffic) continue;
            if (SphereMath.distance(v.position, near) < reach) found.add(v);
        }
        return found.isEmpty() ? null : found.get(rand.nextInt(found.size()));
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
    public Vehicle traffic(int nationId, InfrastructureManager.Doorway near, SpherePosition from, SpherePosition to,
                           long looks, Runnable onArrive) {
        Random rand = new Random(looks);
        Vehicle v = new Vehicle(kindFor(nationId, rand), pickVariant(rand), scale, near, rand);
        v.looksNation = nationId;
        v.looks = looks;
        v.traffic = true;
        v.slot = -1;
        return travel(v, from, to, onArrive) ? v : null;
    }

    /** Which kind of vehicle one of a nation's people drives, by its taste in them. */
    private int kindFor(int nationId, Random rand) {
        float[] weights = kindWeights.getOrDefault(nationId, new float[] { 1f, 0f, 0f, 0f });
        float roll = rand.nextFloat();
        int kind = 0;
        for (int k = 0; k < KINDS; k++) {
            roll -= weights[k];
            if (roll <= 0f && weights[k] > 0f) { kind = k; break; }
            if (weights[k] > 0f) kind = k;
        }
        return kind;
    }

    /**
     * Multiplayer, on the joiner's PC: a vehicle looking just like one on the host's, from its
     * nation and the seed its looks were drawn from (see forHousehold and traffic), to be moved
     * where the host says (see TownsfolkSync). It belongs to nobody here.
     */
    public Vehicle replica(int nationId, long looks, boolean household) {
        Random rand = new Random(looks);
        // (a household's first draw is for whether it has one at all)
        if (household) rand.nextFloat();
        Vehicle v = new Vehicle(kindFor(nationId, rand), pickVariant(rand), scale, null, rand);
        v.looksNation = nationId;
        v.looks = looks;
        v.householdLooks = household;
        v.slot = -1;
        return v;
    }

    /** The way along the roads as a vehicle's stops, each with the road it's on there. */
    private void setRoute(Vehicle v, List<SpherePosition> centre) {
        v.route.clear();
        for (SpherePosition p : centre) {
            float[] road = infrastructure.roadAt(p);
            v.route.add(new Stop(p, infrastructure.drivingSide((int) road[2]), road[0], road[1], road[3]));
        }
    }

    /** Sends a vehicle passing through on to somewhere else along the roads; false if there's no way. */
    public boolean travel(Vehicle v, SpherePosition from, SpherePosition to, Runnable onArrive) {
        List<SpherePosition> centre = infrastructure.drivingRoute(from, to);
        if (centre.size() < 2) return false;
        setRoute(v, centre);
        // At the end it pulls in at the side of the road (the side it drives on), out of the way
        SpherePosition end = centre.get(centre.size() - 1);
        SphereFrame frame = SphereFrame.at(end);
        double[] before = frame.fromSphere(centre.get(centre.size() - 2));
        double dx = -before[0], dz = -before[2], dl = Math.max(1e-3, Math.hypot(dx, dz));
        float side = infrastructure.drivingSide((int) infrastructure.roadAt(end)[2]);
        double rx = -dz / dl * side, rz = dx / dl * side;
        InfrastructureManager.Kerb edge = infrastructure.kerbSpot(end, frame.toSphere(rx * 40.0, 0.0, rz * 40.0), v.width);
        v.park = edge != null ? kerbParking(v, edge, -1)
                : new Spot(frame.toSphere(rx * 8.0, 0.0, rz * 8.0), Math.atan2(dz, dx), -1, false, end);
        v.onArrive = onArrive;
        v.parked = false;
        v.settling = false;
        v.backOut = null;
        v.approach = null;
        v.stuck = 0f;
        v.shift = 0f;
        // Starting out in its lane at the start, facing along the road
        v.next = 1;
        SpherePosition first = v.route.get(0).place(), second = v.route.get(1).place();
        v.position = target(v);
        v.heading = SphereMath.carryHeading(first, v.position, SphereMath.heading(first, second));
        v.speed = KIND_SPEED[v.kind][0] * 0.5f;
        v.liftAt = null;
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
        SpherePosition before = v.route.get(k - 1).place(), after = v.route.get(k).place();
        v.position = target(v);
        v.heading = SphereMath.carryHeading(before, v.position, SphereMath.heading(before, after));
        v.speed = KIND_SPEED[v.kind][0] * 0.6f;
        v.liftAt = null;
    }

    /** Gives up a parking place kept but not used. */
    public void cancel(Vehicle v, Spot spot) {
        // (the place is kept under the door it's at, found by the spot itself)
        Long door = reservedAt.remove(spot);
        if (door != null) freeSlot(door, spot.slot());
    }

    private final Map<Spot, Long> reservedAt = new java.util.IdentityHashMap<>();

    /** The household has gone (its neighbourhood out of range): its car's place is free again. */
    public void release(Vehicle v) {
        freeSlot(v.parkedAt, v.slot);
    }

    /** Where someone getting in stands: beside the vehicle where it's parked, on the kerb (or garden path) side. */
    public SpherePosition doorSide(Vehicle v, InfrastructureManager.Doorway door) {
        if (v.park != null) return v.park.getIn();
        float[] road = door.localRoadDir;
        float nx = -road[1], nz = road[0];
        if (nx * (door.localDoor[0] - door.localKerb[0]) + nz * (door.localDoor[1] - door.localKerb[1]) < 0f) { nx = -nx; nz = -nz; }
        return SphereMath.move(v.position, door.heading(v.position, nx, nz), v.width * 0.5f + 2.5f);
    }

    /** Where a vehicle will park at a door (keeping the place for it): see parkingSpot. */
    public Spot reserve(Vehicle v, InfrastructureManager.Doorway door) {
        Spot spot = parkingSpot(door, v);
        if (spot.slot() >= 0) reservedAt.put(spot, door.id);
        return spot;
    }

    // ------------------------------------------------------------------ driving

    /**
     * Sets off from where it's parked: along the roads (by way of somewhere, for a drive out
     * and back, if via isn't null) to its reserved place at a door, then onArrive.
     */
    public boolean drive(Vehicle v, SpherePosition via, InfrastructureManager.Doorway to, Spot spot, Runnable onArrive) {
        // Up a driveway: first back out into the road
        SpherePosition start = v.position, backOut = null;
        float[] home = v.home.localDriveway;
        if (v.park != null && v.park.driveway() && home != null) {
            float back = 5f + v.length * 0.5f;
            backOut = v.home.at(home[0] - home[2] * back, home[1] - home[3] * back);
            start = backOut;
        }
        List<SpherePosition> centre = new ArrayList<>();
        if (via != null) {
            List<SpherePosition> out = infrastructure.drivingRoute(start, via);
            List<SpherePosition> back = infrastructure.drivingRoute(via, spot.place());
            if (out.size() < 2 || back.size() < 2) return false;
            centre.addAll(out);
            centre.addAll(back.subList(1, back.size()));
        } else {
            centre.addAll(infrastructure.drivingRoute(start, spot.place()));
        }
        if (centre.size() < 2) return false;
        setRoute(v, centre);
        freeSlot(v.parkedAt, v.slot);
        reservedAt.remove(spot);
        v.parkedAt = to.id;
        v.slot = spot.slot();
        v.park = spot;
        v.onArrive = onArrive;
        v.next = 1;
        v.parked = false;
        v.settling = false;
        v.stuck = 0f;
        v.shift = 0f;
        v.backOut = backOut;
        v.approach = null;
        if (spot.driveway() && to.localDriveway != null) {
            // Turning in: from out in the road across from the driveway
            float out = 3f + infrastructure.roadAt(to.kerb)[0] * 0.45f;
            float[] d = to.localDriveway;
            v.approach = to.at(d[0] - d[2] * out, d[1] - d[3] * out);
        }
        return true;
    }

    /**
     * Moves every vehicle on, and lists them all as solid for this frame. Whether one is seen
     * is asked of unseen (out of sight of the viewer and, on the host, the partner too).
     */
    public void update(List<Vehicle> all, float dt, java.util.function.Predicate<SpherePosition> unseen) {
        this.unseen = unseen;
        for (Vehicle v : all) v.forward = SphereFrame.at(v.position).headingDirection(v.heading);
        for (Vehicle v : all) {
            if (!v.parked) step(v, all, dt);
            else if (v.settling) settle(v, dt);
        }
        listSolid(all);
    }

    /** Lists every vehicle as solid for this frame. */
    public void listSolid(List<Vehicle> all) {
        if (collision == null) return;
        for (Vehicle v : all) {
            // Solid along its whole length: overlapping circles from nose to tail
            int circles = Math.max(2, Math.round(v.length / v.width) + 1);
            SphereFrame frame = SphereFrame.at(v.position);
            double fx = Math.cos(v.heading), fz = Math.sin(v.heading);
            for (int i = 0; i < circles; i++) {
                float t = (i / (float) (circles - 1) - 0.5f) * (v.length - v.width);
                SpherePosition c = frame.toSphere(fx * t, 0.0, fz * t);
                collision.addBody(v, c, v.width * HITBOX, true);
            }
        }
    }

    /** The point it's steering for: the next route point out in its lane, or at the end its parking place. */
    private SpherePosition target(Vehicle v) {
        if (v.next >= v.route.size()) return v.approach != null ? v.approach : v.park.place();
        Stop p = v.route.get(v.next);
        Stop before = v.route.get(Math.max(0, v.next - 1)), after = v.route.get(Math.min(v.route.size() - 1, v.next + 1));
        SphereFrame frame = SphereFrame.at(p.place());
        double[] b = frame.fromSphere(before.place()), a = frame.fromSphere(after.place());
        double dx = a[0] - b[0], dz = a[2] - b[2];
        double length = Math.hypot(dx, dz);
        if (length < 1e-3) return p.place();
        double rx = -dz / length, rz = dx / length;
        float lane = laneOffset(v, p.half()) - v.shift;
        return frame.toSphere(rx * p.side() * lane, 0.0, rz * p.side() * lane);
    }

    /**
     * How far out from the road's middle a vehicle keeps: in its own half, but never so far
     * that its outer side is off the road (a wide one on a narrow road keeps nearer the middle).
     */
    // How much of a vehicle's width is solid to everything else (a little less than all of it, so
    // passing traffic squeezes by rather than locking together)
    private static final float HITBOX = 0.4f;

    private static final java.util.function.Predicate<Object> IS_VEHICLE = o -> o instanceof Vehicle;

    /** A vehicle's speed limit on ordinary roads or on highways (world units a second). */
    static float speedLimit(Vehicle v, boolean highway) {
        return KIND_SPEED[v.kind][highway ? 1 : 0];
    }

    private static float laneOffset(Vehicle v, float half) {
        float wanted = Math.max(v.width * 0.6f, half * LANE_SHARE);
        return Math.max(half * 0.2f, Math.min(wanted, half - v.width * 0.5f - 0.5f));
    }

    // How near a border check a vehicle stops, and for how long (seconds)
    private static final float BORDER_STOP_REACH = 70f, BORDER_WAIT_LOW = 3f, BORDER_WAIT_RANGE = 3f, BORDER_BRAKING = 25f;

    /** Moves a vehicle to a place nearby, its heading carried along with it. */
    private static void moveTo(Vehicle v, SpherePosition to) {
        v.heading = SphereMath.carryHeading(v.position, to, v.heading);
        v.position = to;
    }

    private static float wrap(double angle) {
        return (float) Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    /**
     * One step of driving, worked out in the frame where the vehicle is (D3): it's at the
     * origin, and everything it steers by (its target, the others, what's in its way) is put
     * in that frame's (x, z), x east and z south, headings from x towards z.
     */
    private void step(Vehicle v, List<Vehicle> all, float dt) {
        if (v.backOut != null) {
            backOut(v, dt);
            return;
        }
        if (v.reversing > 0f) {
            reverse(v, dt);
            return;
        }
        SphereFrame frame = SphereFrame.at(v.position);
        double[] aim = frame.fromSphere(target(v));
        float dx = (float) aim[0], dz = (float) aim[2];
        if (v.detour > 0f) {
            // Going round what it was stuck on: aiming off to one side of its way for a while
            // (to the side of the way to the point, not of where it faces, which would turn with it)
            v.detour -= dt;
            float wl = (float) Math.max(1e-3, Math.hypot(dx, dz));
            float off = v.detourSide * (v.width * 0.9f + 3f);
            float ox = -dz / wl * off, oz = dx / wl * off;
            dx += ox;
            dz += oz;
        }
        boolean approaching = v.next >= v.route.size() && v.approach != null;
        boolean final_ = v.next >= v.route.size() && !approaching;
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
                moveTo(v, frame.toSphere(dx, 0.0, dz));
                return;
            }
        }
        if (final_ && distance < 1.5f) {
            // Arrived: pulled in, it straightens up and stops
            moveTo(v, frame.toSphere(dx, 0.0, dz));
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
        Stop here = v.route.isEmpty() ? null : v.route.get(Math.min(v.next, v.route.size() - 1));
        float limit = speedLimit(v, here != null && here.rank() >= 2f);
        if (here != null && here.dirt() > 0f) limit *= 0.55f;
        float desired = (float) Math.atan2(dz, dx);
        float turn = wrap(desired - v.heading);
        // Slow for bends, and to pull in at the end
        limit *= Math.max(0.25f, (float) Math.cos(Math.min(Math.abs(turn), 1.4f)));
        // A sharp turn to make: slow enough to turn inside the distance to the point, or it would
        // circle round it for ever (at highway speed it can't turn tightly)
        if (Math.abs(turn) > 0.5f) limit = Math.min(limit, distance * 0.6f + 3f);
        if (final_ || approaching) limit = Math.min(limit, distance * 1.2f + 2f);
        // At a border check it draws up level with the booth and stops a while to be let through
        float[] check = infrastructure.borders().borderStopAhead(v.position, v.heading, BORDER_STOP_REACH);
        if (check != null && (int) check[0] != v.checkedAt) {
            if (check[1] < 1.0f) {
                v.checkedAt = (int) check[0];
                v.checkWait = BORDER_WAIT_LOW + (float) Math.random() * BORDER_WAIT_RANGE;
                v.speed = 0f;
            } else {
                // Slowing to stop just there
                limit = Math.min(limit, (float) Math.sqrt(2f * BORDER_BRAKING * check[1]));
            }
        }
        if (v.checkWait > 0f) {
            v.checkWait -= dt;
            limit = 0f;
            v.speed = 0f;
        }
        // A border's traffic light that isn't its turn: it waits at the line until it is
        float hold = infrastructure.borders().borderHoldAhead(v.position, v.heading, BORDER_STOP_REACH);
        if (!Float.isNaN(hold)) {
            if (hold < 1.0f) {
                limit = 0f;
                v.speed = 0f;
            } else {
                limit = Math.min(limit, (float) Math.sqrt(2f * BORDER_BRAKING * hold));
            }
        }

        float fx = (float) Math.cos(v.heading), fz = (float) Math.sin(v.heading);
        float rx = -fz, rz = fx;
        float wantShift = 0f;
        float look = v.length * 0.5f + 10f + v.speed * 1.2f;
        // Out to the other side of the road to pass: from its lane to the far lane, keeping on the road
        float lane = here != null ? laneOffset(v, here.half()) : v.width;
        float passShift = here != null ? Math.min(lane * 2f, lane + here.half() - v.width * 0.5f) : lane * 2f;
        Vehicle stopped = null;
        boolean oncoming = false;
        for (Vehicle o : all) {
            if (o == v) continue;
            float reach = Math.max(look, 90f) + o.length;
            // (a quick test on the straight line between them before putting it in this frame)
            double wx = o.position.x() - v.position.x(), wy = o.position.y() - v.position.y(), wz = o.position.z() - v.position.z();
            if (wx * wx + wy * wy + wz * wz > 2.0 * reach * reach) continue;
            double[] local = frame.fromSphere(o.position);
            float ox = (float) local[0], oz = (float) local[2];
            if (Math.abs(ox) > reach || Math.abs(oz) > reach) continue;
            float ahead = ox * fx + oz * fz, across = ox * rx + oz * rz;
            if (ahead <= 0f) continue;
            // How far the two face the same way (the frames at the two are as good as level with each other)
            float alongDot = (float) (v.forward[0] * o.forward[0] + v.forward[1] * o.forward[1] + v.forward[2] * o.forward[2]);
            // Anything coming the other way on the stretch it would pass along
            if (!o.parked && o.speed > 2f && ahead < 90f + o.length && alongDot < -0.3f
                    && Math.abs(across) < passShift + (v.width + o.width) * 0.5f + 2f) {
                oncoming = true;
            }
            if (ahead > look + o.length * 0.5f) continue;
            float clear = (v.width + o.width) * 0.5f + 1f;
            if (Math.abs(across) >= clear) continue;
            boolean headOn = !o.parked && o.speed > 1f && alongDot < -0.3f;
            if (headOn) {
                // Meeting something coming the other way: both keep over to their own edge (up
                // onto the verge a little if they must) and pass, slowing as they do; one out
                // overtaking gives that up and gets back in
                v.overtaking = 0f;
                wantShift = Math.min(wantShift, -Math.min(4f, clear - Math.abs(across) + 0.5f));
                limit = Math.min(limit, Math.max(6f, ahead * 0.6f));
                continue;
            }
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
            if (collision.blocked(frame.toSphere(lx * reach, 0.0, lz * reach), v.width * 0.42f, v, 0.0, IS_VEHICLE)) {
                limit = 0f;
                obstacle = true;
            }
        }
        if (limit < 1f && v.speed < 1f) {
            v.stuck += dt;
            if (obstacle && final_ && v.stuck > 1.5f) {
                // Pulling in at the end and something's in the way: it stops where it is
                float out = v.width * 0.5f + 2.5f;
                v.park = new Spot(v.position, v.heading, v.park != null ? v.park.slot() : -1, false,
                        frame.toSphere(rx * out, 0.0, rz * out));
                v.stuck = 0f;
            } else if (obstacle && v.stuck > 2.5f && v.tries < 3) {
                // Stuck on something: back off a little, then go round it on its clearer side
                v.tries++;
                v.stuck = 0f;
                v.reversing = 1.4f;
                v.detour = 3.5f;
                float reach = v.length * 0.5f + 4f, aside = v.width * 0.8f;
                boolean leftBlocked = collision.blocked(frame.toSphere(fx * reach - rx * aside, 0.0, fz * reach - rz * aside), v.width * 0.4f, v, 0.0);
                boolean rightBlocked = collision.blocked(frame.toSphere(fx * reach + rx * aside, 0.0, fz * reach + rz * aside), v.width * 0.4f, v, 0.0);
                v.detourSide = leftBlocked == rightBlocked ? (v.tries % 2 == 0 ? 1 : -1) : leftBlocked ? 1 : -1;
                // Up against the road's edge (a crash barrier, say): always back in towards the middle
                float lateral = lateralOffset(v);
                Stop at = v.route.get(Math.min(v.next, v.route.size() - 1));
                if (!Float.isNaN(lateral) && Math.abs(lateral) > at.half() - v.width - 2f) v.detourSide = lateral > 0f ? -1 : 1;
            } else if (v.stuck > (obstacle ? 4f : 10f)) {
                // Still held up after that: out of sight it moves on; in sight it edges through
                if (unseen.test(v.position) && !final_) {
                    moveTo(v, target(v));
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
        moveTo(v, frame.toSphere(Math.cos(v.heading) * move, 0.0, Math.sin(v.heading) * move));
        v.spin += move;
        // Solid: if it has run into anything (another vehicle, a wall, a post), it's pushed back
        // out, nose and tail alike (but not turning into a driveway, close by the fence); worked
        // in the frame where it has got to
        boolean intoDriveway = final_ && v.park != null && v.park.driveway();
        if (collision != null && v.ghost <= 0f && !intoDriveway) {
            SphereFrame moved = SphereFrame.at(v.position);
            float half = (v.length - v.width) * 0.5f, pushX = 0f, pushZ = 0f;
            float hx = (float) Math.cos(v.heading), hz = (float) Math.sin(v.heading);
            for (float along : new float[] { -half, half }) {
                float cx = hx * along, cz = hz * along;
                double[] free = moved.fromSphere(collision.resolve(moved.toSphere(cx, 0.0, cz), v.width * HITBOX, v, 0.0));
                pushX += ((float) free[0] - cx) * 0.5f;
                pushZ += ((float) free[2] - cz) * 0.5f;
            }
            // Shoved back from the front (meeting something head on): pushed out sideways towards
            // its own edge instead, off the road if need be, so the two slide past; it steers back
            // into its lane further on
            float back = -(pushX * hx + pushZ * hz);
            if (back > 0f) {
                float side = here != null ? here.side() : 1f;
                // ... unless that's into a crash barrier or a wall: then towards the middle
                float ox = pushX - hz * side * back * 3f, oz = pushZ + hx * side * back * 3f;
                if (collision.blocked(moved.toSphere(ox, 0.0, oz), v.width * HITBOX, v, 0.0, IS_VEHICLE)) side = -side;
                pushX += hx * back - hz * side * back;
                pushZ += hz * back + hx * side * back;
            }
            if (pushX != 0f || pushZ != 0f) moveTo(v, moved.toSphere(pushX, 0.0, pushZ));
        }
        v.forward = SphereFrame.at(v.position).headingDirection(v.heading);
    }

    /**
     * How far it is to the right (positive) or left of the road's middle along the stretch of
     * route it's on (in the frame at the stretch's start); NaN if it's not on one.
     */
    private static float lateralOffset(Vehicle v) {
        if (v.route.size() < 2) return Float.NaN;
        int k = Math.max(1, Math.min(v.next, v.route.size() - 1));
        SphereFrame frame = SphereFrame.at(v.route.get(k - 1).place());
        double[] b = frame.fromSphere(v.route.get(k).place()), p = frame.fromSphere(v.position);
        double length = Math.hypot(b[0], b[2]);
        if (length < 1e-3) return Float.NaN;
        // Right of the way along is (-dz, dx)
        return (float) ((p[0] * -b[2] + p[2] * b[0]) / length);
    }

    /** Backing off from what it's stuck on, the tail swinging away from the side it will go round by. */
    private void reverse(Vehicle v, float dt) {
        v.reversing -= dt;
        SphereFrame frame = SphereFrame.at(v.position);
        float fx = (float) Math.cos(v.heading), fz = (float) Math.sin(v.heading);
        // Unless something is behind it too
        float behind = v.length * 0.5f + 2f;
        if (collision != null && collision.blocked(frame.toSphere(-fx * behind, 0.0, -fz * behind), v.width * 0.42f, v, 0.0)) {
            v.reversing = 0f;
            return;
        }
        float move = 7f * dt;
        moveTo(v, frame.toSphere(-fx * move, 0.0, -fz * move));
        // (the nose swings towards the side it will go round by)
        v.heading += v.detourSide * 0.35f * dt;
        v.spin -= move;
        v.speed = 0f;
    }

    /** Reversing slowly out of a driveway into the road, straightening up as it goes. */
    private void backOut(Vehicle v, float dt) {
        SphereFrame frame = SphereFrame.at(v.position);
        double[] b = frame.fromSphere(v.backOut);
        float bx = (float) b[0], bz = (float) b[2];
        float d = (float) Math.hypot(bx, bz);
        if (d < 1.5f) {
            v.backOut = null;
            v.speed = 0f;
            return;
        }
        // Facing away from where it's going
        float desired = (float) Math.atan2(-bz, -bx);
        float turn = wrap(desired - v.heading);
        v.heading += Math.max(-0.8f * dt, Math.min(0.8f * dt, turn));
        float move = Math.min(10f, d * 1.2f + 1.5f) * dt;
        moveTo(v, frame.toSphere(bx / d * move, 0.0, bz / d * move));
        v.spin -= move;
        v.speed = 0f;
    }

    /** Just parked: turns square to the kerb. */
    private void settle(Vehicle v, float dt) {
        if (v.park == null) { v.settling = false; return; }
        float turn = wrap(SphereMath.carryHeading(v.park.place(), v.position, v.park.heading()) - v.heading);
        v.heading += Math.max(-1.5f * dt, Math.min(1.5f * dt, turn));
        if (Math.abs(turn) < 0.01f) v.settling = false;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the vehicles near enough to see, with the organism shader already set up: body
     * in its colour and pattern, glass, trim and tyres, the wheels turned as far as it has gone.
     * Each is posed in the frame where it stands and drawn relative to the eye.
     */
    public void render(GL3 gl, Shader shader, List<Vehicle> all, float drawDistance, SpherePosition eye) {
        int bonesLocation = gl.glGetUniformLocation(shader.getID(), "bones");
        shader.setFloat(gl, "shadowExtraBias", SHADOW_EXTRA_BIAS);
        for (Vehicle v : all) {
            float ground = standingHeight(v);
            SpherePosition middle = SphereMath.atAltitude(v.position, ground + v.height * 0.5f);
            double ex = middle.x() - eye.x(), ey = middle.y() - eye.y(), ez = middle.z() - eye.z();
            double reach = drawDistance + v.length * 0.6f;
            if (ex * ex + ey * ey + ez * ez > reach * reach) continue;
            SphereRender.setAnchor(gl, shader, SphereFrame.at(v.position).modelMatrix(1f, eye));
            List<float[]> hubs = hubsOf(v);
            float s = v.length / (KIND_SIZE[v.kind][0] * scale);
            // (the body's nose is along its local +z, turned from +z towards +x by the angle,
            // which runs the other way to a heading)
            float[] body = Affine.multiply(Affine.translation(0f, ground, 0f),
                    Affine.multiply(Affine.rotationY((float) (Math.PI * 0.5 - v.heading)),
                            Affine.multiply(Affine.rotationX(-v.pitch), Affine.scale(s, s, s))));
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
        shader.setFloat(gl, "shadowExtraBias", 0f);
    }

    // How much further than other things a vehicle's surface is pushed out against its own shadow
    // (units, see shadows_include): one moving fast slides a texel or more across the shadow map
    // each frame, so the speckles where it shades itself would change every frame, its back
    // flashing light and dark
    private static final float SHADOW_EXTRA_BIAS = 1.6f;

    /**
     * The height above the datum of the road (or ground) under it, and its tilt along its
     * length; the road's lift found again every few units.
     */
    private float standingHeight(Vehicle v) {
        float ground = TerrainMesh.getLayeredHeight(v.position, terrainNoise);
        if (v.liftAt == null || SphereMath.distance(v.position, v.liftAt) > 5.0) {
            v.liftAt = v.position;
            v.lift = infrastructure.walkingSurfaceY(v.position) - ground;
            float half = v.length * 0.4f;
            float front = TerrainMesh.getLayeredHeight(SphereMath.move(v.position, v.heading, half), terrainNoise);
            float back = TerrainMesh.getLayeredHeight(SphereMath.move(v.position, v.heading + Math.PI, half), terrainNoise);
            v.pitch = (float) Math.atan2(front - back, half * 2f) * (v.lift > 0.5f ? 0.3f : 1f);
        }
        return ground + v.lift;
    }

    /**
     * Developer aid: out in the road ahead of the nearest vehicle to a place (mode "moving": one
     * on the move, or "driveway": one up a driveway, if there is one), a little to one side,
     * looking back at it; null if there's none.
     */
    public Viewpoint viewpoint(List<Vehicle> all, SpherePosition near, String mode) {
        boolean moving = "moving".equals(mode), driveway = "driveway".equals(mode);
        Vehicle best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Vehicle v : all) {
            if (moving && v.isParked()) continue;
            if (driveway && !(v.isParked() && v.park != null && v.park.driveway())) continue;
            double d = SphereMath.distance(v.position, near);
            if (d < bestDistance) { bestDistance = d; best = v; }
        }
        if (best == null) return mode != null && !mode.isEmpty() && !"true".equals(mode) ? viewpoint(all, near, "") : null;
        float fx = (float) Math.cos(best.heading), fz = (float) Math.sin(best.heading);
        float back = (best.length * 1.5f + 25f) * (driveway ? -1.4f : 1f);
        // (ahead, and a quarter as far to its right)
        SpherePosition at = SphereFrame.at(best.position).toSphere(fx * back - fz * back * 0.25f, 0.0, fz * back + fx * back * 0.25f);
        return new Viewpoint(at, SphereFrame.at(at).headingTo(best.position));
    }

    private static com.xenoguesser.math.Vector3 vec(float[] c) {
        return new com.xenoguesser.math.Vector3(c[0], c[1], c[2]);
    }

    /** The name of a kind, for the log. */
    public static String kindName(int kind) {
        return KIND_NAMES[kind];
    }
}
