import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.jogamp.opengl.GL3;

/**
 * A procedurally generated species, assembled from the parts in OrganismParts. Every world
 * seed produces its own set; nothing about a species is fixed in advance.
 *
 * The generator chooses a body plan:
 * <ul>
 *   <li>how it gets about: walking on legs, slithering on its belly, or floating above
 *       the ground trailing tentacles;</li>
 *   <li>a spine of body segments with a size profile (even like a worm, swollen in the
 *       middle, heavy at the back or the front), optionally a raised neck and a tail;</li>
 *   <li>for walkers, one to six pairs of legs, sprawled out to the sides like an insect's or
 *       upright beneath the body like a mammal's, with knees bending forwards or back;</li>
 *   <li>head parts: eyes (perhaps on stalks), antennae, horns, pincers or a trunk; spines
 *       along the back, a club or spike on the tail;</li>
 *   <li>colouring, pattern, gloss and gait.</li>
 * </ul>
 *
 * Where it lives and how it looks there depend on the land, never on nation borders: a
 * habitat from preferred temperature and closeness to water broken into patches, clines in
 * size, leg length and colour across its range, and two morphs differing in a few parts
 * whose mix shifts from one end of the range to the other.
 *
 * Distances are in world units (the viewer's eyes are about 20 above the ground). Bodies
 * face +Z in their own space.
 */
public class OrganismSpecies {

    public static final int MAX_BONES = 48;

    public enum Locomotion { WALKER, SLITHERER, FLOATER, FLYER }

    /** Where one leg joins the body and how it stands. */
    private static final class Leg {
        int segment;
        int side;            // -1 left, +1 right
        float hipZ, hipY;    // in body space
        float splay;         // how far forward (+) or back (-) its foot rests, as a share of reach
        float phase;         // offset in the gait cycle
        int femurBone, tibiaBone, footBone;
    }

    /** One wing of a flyer, in two jointed parts. */
    private static final class Wing {
        int side;
        int pair;
        float rootZ;
        int innerBone, outerBone;
    }

    /** A tentacle hanging from a floater. */
    private static final class Tentacle {
        float x, z;          // where it hangs from, under the body
        float phase;
        int firstBone;
    }

    /** The parts a morph is built from; the two morphs of a species differ in a few of these. */
    private static final class Morph {
        OrganismParts.AntennaType antenna;
        OrganismParts.FootType foot;
        int horns;
        int spines;
        boolean crest;
        boolean tailClub;
        OrganismMesh mesh;
        // A few hundred of its mesh's points {x, y, z, bone} in turn, to find its outline as posed
        float[] outline;
    }

    public final Locomotion locomotion;
    final int segments;
    final float[] segmentLength, segmentWidth, segmentHeight;
    // Rest position of each segment's centre and its pitch (positive raises the front)
    final float[][] segmentCentre;
    final float[] segmentPitch;
    final int neckSegments, tailSegments;
    final float pointiness, ridgeDepth;
    final int ridges;
    private final List<Leg> legs = new ArrayList<>();
    private final List<Tentacle> tentacles = new ArrayList<>();
    private final List<Wing> wings = new ArrayList<>();
    final float wingSpan, wingChord;
    /** Flyers circle their home at this radius. */
    final float flightRadius;
    /** How common the species is where it lives, relative to the others. */
    final float rarity;
    final float femurLength, tibiaLength, legRadius;
    final boolean upright;
    final float kneeDirection;     // upright legs: +1 knees forward, -1 knees back
    final float tentacleLength, tentacleRadius;
    static final int TENTACLE_BONES = 3;
    final boolean hasAntennae, hasTrunk, hasMandibles, eyeStalks;
    final float antennaLength, trunkLength, trunkCurl;
    final int eyes;
    final float eyeRadius;
    private final Morph[] morphs = new Morph[2];
    final int antennaBones, trunkBone, boneCount;

    // Gait
    final float bodyHeight;        // body origin above the ground
    final float stride;
    final float stepsPerSecond;
    final float walkSpeed;
    final float slitherAmplitude, slitherWavelength;

    // Colouring: base, accent, belly, limbs and eyes, as RGB; pattern kind and scale
    final float[] baseColour, accentColour, bellyColour, limbColour, eyeColour;
    final int patternType;
    final float patternScale;
    final float gloss;

    // Where it lives and how it varies across its range
    final RegionalFactor habitat;
    final float habitatThreshold;
    private final PerlinNoise sizeCline, legCline, hueCline, morphCline;

    public OrganismSpecies(long seed, int index, RegionalGenerationManager regions) {
        Random rand = WorldPalette.rng(seed, 0xB065L + index * 7919L);

        float planRoll = rand.nextFloat();
        locomotion = planRoll < 0.52f ? Locomotion.WALKER : planRoll < 0.7f ? Locomotion.SLITHERER
                : planRoll < 0.82f ? Locomotion.FLOATER : Locomotion.FLYER;
        // Walkers either sprawl like bugs (small) or stand upright like mammals (large)
        upright = locomotion == Locomotion.WALKER && rand.nextFloat() < 0.45f;
        // Some worlds' creatures run larger than others'
        float worldSize = 0.75f + WorldPalette.rng(seed, 0x512EL).nextFloat() * 0.6f;

        // --- Spine ---
        int neck = 0, tail = 0, torso;
        switch (locomotion) {
            case SLITHERER -> { torso = 7 + rand.nextInt(8); }
            case FLOATER -> { torso = 1 + rand.nextInt(2); }
            case FLYER -> {
                torso = 1 + rand.nextInt(2);
                neck = rand.nextFloat() < 0.4f ? 1 : 0;
                tail = 1 + rand.nextInt(3);
            }
            default -> {
                torso = 1 + rand.nextInt(rand.nextFloat() < 0.2f ? 7 : 3);
                neck = rand.nextFloat() < 0.4f ? 1 + rand.nextInt(3) : 0;
                tail = rand.nextFloat() < 0.45f ? 1 + rand.nextInt(4) : 0;
            }
        }
        neckSegments = neck;
        tailSegments = tail;
        segments = 1 + neck + torso + tail;
        segmentLength = new float[segments];
        segmentWidth = new float[segments];
        segmentHeight = new float[segments];
        segmentCentre = new float[segments][];
        segmentPitch = new float[segments];

        float scale = worldSize * switch (locomotion) {
            case SLITHERER -> 2.2f + rand.nextFloat() * 2.3f;
            case FLOATER -> 4.0f + rand.nextFloat() * 5.0f;
            case FLYER -> 4.0f + rand.nextFloat() * 4.0f;
            default -> upright ? 8.0f + rand.nextFloat() * 7.0f : 2.5f + rand.nextFloat() * 3.0f;
        };
        float slenderness = 0.5f + rand.nextFloat() * 0.65f;
        float flatness = locomotion == Locomotion.FLOATER ? 0.8f + rand.nextFloat() * 0.4f : 0.45f + rand.nextFloat() * 0.55f;
        int profile = rand.nextInt(4);           // even, swollen middle, heavy rear, heavy front
        float headShare = 0.5f + rand.nextFloat() * 0.45f;
        for (int s = 0; s < segments; s++) {
            float share;
            if (s == 0) {
                share = headShare;
            } else if (s <= neck) {
                share = 0.35f + rand.nextFloat() * 0.2f;
            } else if (s > neck + torso) {
                float t = (s - neck - torso) / (float) tail;
                share = 0.7f * (1f - 0.6f * t);
            } else {
                float t = torso == 1 ? 0.5f : (s - 1 - neck) / (float) (torso - 1);
                share = switch (profile) {
                    case 1 -> 0.8f + 0.6f * (float) Math.sin(Math.PI * t);
                    case 2 -> 0.8f + 0.8f * t;
                    case 3 -> 1.5f - 0.6f * t;
                    default -> 1.0f;
                };
                share *= 0.9f + rand.nextFloat() * 0.2f;
            }
            segmentLength[s] = scale * share * (s <= neck && s > 0 ? 1.4f : 1f);
            segmentWidth[s] = scale * share * slenderness * (s <= neck && s > 0 ? 0.6f : 1f);
            segmentHeight[s] = segmentWidth[s] * flatness;
        }

        // Lay the torso along -Z, the neck rising from its front, the tail drooping behind
        float neckRise = 0.3f + rand.nextFloat() * 0.9f;
        float tailDroop = -0.1f - rand.nextFloat() * 0.25f;
        float overlap = 0.72f + rand.nextFloat() * 0.15f;
        int firstTorso = 1 + neck, lastTorso = neck + torso;
        float z = 0f;
        for (int s = firstTorso; s <= lastTorso; s++) {
            if (s > firstTorso) z -= (segmentLength[s - 1] + segmentLength[s]) * 0.5f * overlap;
            segmentCentre[s] = new float[] { 0f, 0f, z };
        }
        float[] front = { 0f, 0f, segmentCentre[firstTorso][2] };
        float frontHalf = segmentLength[firstTorso] * 0.5f;
        for (int s = firstTorso - 1; s >= 0; s--) {
            // Neck segments and the head chain forward and, for a neck, upward
            float pitch = s == 0 ? (neck > 0 ? neckRise * 0.3f : 0f) : neckRise;
            float step = (frontHalf + segmentLength[s] * 0.5f) * overlap;
            front = new float[] { 0f, front[1] + (float) Math.sin(pitch) * step, front[2] + (float) Math.cos(pitch) * step };
            segmentCentre[s] = front;
            segmentPitch[s] = s == 0 ? 0f : pitch;
            frontHalf = segmentLength[s] * 0.5f;
        }
        float[] back = { 0f, 0f, segmentCentre[lastTorso][2] };
        float backHalf = segmentLength[lastTorso] * 0.5f;
        for (int s = lastTorso + 1; s < segments; s++) {
            float step = (backHalf + segmentLength[s] * 0.5f) * overlap;
            back = new float[] { 0f, back[1] + (float) Math.sin(tailDroop) * step, back[2] - (float) Math.cos(tailDroop) * step };
            segmentCentre[s] = back;
            segmentPitch[s] = tailDroop;
            backHalf = segmentLength[s] * 0.5f;
        }
        // The body's origin is the middle of the torso
        float middle = (segmentCentre[firstTorso][2] + segmentCentre[lastTorso][2]) * 0.5f;
        for (float[] c : segmentCentre) c[2] -= middle;
        pointiness = 0.5f + rand.nextFloat() * 1.3f;
        ridgeDepth = 0.06f + rand.nextFloat() * 0.12f;
        ridges = rand.nextFloat() < 0.5f ? 2 + rand.nextInt(4) : 0;

        // --- Legs ---
        int bone = segments;
        float torsoWidth = 0f, torsoLength = 0f;
        for (int s = firstTorso; s <= lastTorso; s++) {
            torsoWidth = Math.max(torsoWidth, segmentWidth[s]);
            torsoLength += segmentLength[s] * overlap;
        }
        kneeDirection = rand.nextBoolean() ? 1f : -1f;
        float reach = upright ? torsoWidth * (1.2f + rand.nextFloat() * 1.6f) : torsoWidth * (1.1f + rand.nextFloat() * 1.9f);
        // Big, but never towering far over the viewer
        reach = Math.min(reach, upright ? 40f : 14f);
        femurLength = reach * (0.42f + rand.nextFloat() * 0.16f);
        tibiaLength = reach - femurLength;
        legRadius = Math.max(0.35f, torsoWidth * (0.05f + rand.nextFloat() * 0.06f) * (upright ? 1.4f : 1f));
        int pairs = 0;
        if (locomotion == Locomotion.WALKER) {
            pairs = upright ? (rand.nextFloat() < 0.3f ? 1 : 2) : new int[] { 2, 3, 3, 3, 4, 4, 5, 6 }[rand.nextInt(8)];
            // Many-segmented walkers get a pair per segment, up to the bone budget
            if (!upright && torso > pairs) pairs = Math.min(torso, 6);
            // Each pair takes six bones; keep room for the head's antennae and trunk
            pairs = Math.min(pairs, (MAX_BONES - segments - 3) / 6);
        }
        float firstHipZ = segmentCentre[firstTorso][2] + segmentLength[firstTorso] * 0.3f;
        float lastHipZ = segmentCentre[lastTorso][2] - segmentLength[lastTorso] * 0.3f;
        for (int p = 0; p < pairs; p++) {
            float t = pairs == 1 ? 0.5f : p / (float) (pairs - 1);
            float hipZ = firstHipZ + (lastHipZ - firstHipZ) * t;
            int segment = nearestSegment(hipZ, firstTorso, lastTorso);
            for (int side = -1; side <= 1; side += 2) {
                Leg leg = new Leg();
                leg.segment = segment;
                leg.side = side;
                leg.hipZ = hipZ;
                leg.hipY = -segmentHeight[segment] * (upright ? 0.3f : 0.15f);
                leg.splay = upright || pairs == 1 ? 0f : 0.55f - 1.1f * t;
                // Alternating tripods, with a ripple running back along the body
                leg.phase = (((p + (side > 0 ? 1 : 0)) & 1) * 0.5f + p * 0.07f) % 1f;
                leg.femurBone = bone++;
                leg.tibiaBone = bone++;
                leg.footBone = bone++;
                legs.add(leg);
            }
        }

        // --- Tentacles ---
        float hover = 14f + rand.nextFloat() * 30f;
        // Tentacles trail clear of the ground beneath a floater
        tentacleLength = Math.min(segmentLength[0] * (1.2f + rand.nextFloat() * 1.8f), hover * 0.85f);
        tentacleRadius = Math.max(0.3f, segmentWidth[0] * 0.06f);
        if (locomotion == Locomotion.FLOATER) {
            int count = 3 + rand.nextInt(6);
            float ring = segmentWidth[0] * 0.3f;
            for (int i = 0; i < count && bone + TENTACLE_BONES <= MAX_BONES - 3; i++) {
                Tentacle tentacle = new Tentacle();
                double angle = i * Math.PI * 2 / count;
                tentacle.x = ring * (float) Math.cos(angle);
                tentacle.z = ring * (float) Math.sin(angle);
                tentacle.phase = rand.nextFloat() * 6.28f;
                tentacle.firstBone = bone;
                bone += TENTACLE_BONES;
                tentacles.add(tentacle);
            }
        }

        // --- Wings ---
        int wingPairs = locomotion == Locomotion.FLYER ? (rand.nextFloat() < 0.75f ? 1 : 2) : 0;
        wingSpan = torsoWidth * (2.5f + rand.nextFloat() * 2.5f);
        wingChord = torsoLength * (0.5f + rand.nextFloat() * 0.4f) / Math.max(1, wingPairs);
        for (int w = 0; w < wingPairs; w++) {
            float rootZ = firstHipZ + (lastHipZ - firstHipZ) * (wingPairs == 1 ? 0.3f : w);
            for (int side = -1; side <= 1; side += 2) {
                Wing wing = new Wing();
                wing.side = side;
                wing.pair = w;
                wing.rootZ = rootZ;
                wing.innerBone = bone++;
                wing.outerBone = bone++;
                wings.add(wing);
            }
        }
        flightRadius = 150f + rand.nextFloat() * 220f;

        // --- Head parts ---
        hasAntennae = rand.nextFloat() < (locomotion == Locomotion.WALKER ? 0.7f : 0.35f);
        antennaLength = segmentLength[0] * (0.8f + rand.nextFloat() * 2.0f);
        hasTrunk = rand.nextFloat() < 0.25f;
        trunkLength = segmentLength[0] * (0.7f + rand.nextFloat() * 1.0f);
        trunkCurl = 0.3f + rand.nextFloat() * 0.9f;
        hasMandibles = !hasTrunk && rand.nextFloat() < 0.4f;
        eyes = new int[] { 0, 2, 2, 2, 4, 6, 8 }[rand.nextInt(7)];
        eyeStalks = eyes > 0 && rand.nextFloat() < 0.25f;
        eyeRadius = segmentWidth[0] * (0.1f + rand.nextFloat() * 0.1f) * (eyes > 2 ? 0.75f : 1f);
        antennaBones = bone;
        bone += hasAntennae ? 2 : 0;
        trunkBone = hasTrunk ? bone++ : -1;
        boneCount = bone;

        // --- Morphs: the same body plan with a few parts swapped ---
        OrganismParts.AntennaType[] antennae = OrganismParts.AntennaType.values();
        OrganismParts.FootType[] feet = OrganismParts.FootType.values();
        for (int m = 0; m < 2; m++) {
            Morph morph = new Morph();
            morphs[m] = morph;
            if (m == 0) {
                morph.antenna = antennae[rand.nextInt(antennae.length)];
                morph.foot = feet[rand.nextInt(feet.length)];
                morph.horns = rand.nextFloat() < 0.3f ? 1 + rand.nextInt(3) : 0;
                morph.spines = rand.nextFloat() < 0.4f ? 2 + rand.nextInt(3) : 0;
                morph.crest = rand.nextFloat() < 0.3f;
                morph.tailClub = tail > 0 && rand.nextFloat() < 0.4f;
            } else {
                Morph first = morphs[0];
                morph.antenna = rand.nextBoolean() ? antennae[(first.antenna.ordinal() + 1 + rand.nextInt(antennae.length - 1)) % antennae.length] : first.antenna;
                morph.foot = rand.nextBoolean() ? feet[(first.foot.ordinal() + 1 + rand.nextInt(feet.length - 1)) % feet.length] : first.foot;
                morph.horns = first.horns > 0 ? (rand.nextBoolean() ? 0 : first.horns) : (rand.nextBoolean() ? 1 + rand.nextInt(2) : 0);
                morph.spines = first.spines > 0 ? 0 : 2 + rand.nextInt(3);
                morph.crest = rand.nextBoolean() != first.crest;
                morph.tailClub = tail > 0 && !first.tailClub;
            }
        }

        // --- Gait ---
        switch (locomotion) {
            case WALKER -> bodyHeight = upright ? reach * (0.86f + rand.nextFloat() * 0.08f) : reach * (0.38f + rand.nextFloat() * 0.12f);
            case FLOATER -> bodyHeight = hover;
            case FLYER -> bodyHeight = 60f + rand.nextFloat() * 90f;
            default -> bodyHeight = segmentHeight[1] * 0.45f;
        }
        stride = reach * (upright ? 0.55f : 0.35f + rand.nextFloat() * 0.15f);
        stepsPerSecond = switch (locomotion) {
            case FLOATER -> 0.25f + rand.nextFloat() * 0.3f;
            case FLYER -> 0.8f + rand.nextFloat() * 1.2f;     // wingbeats
            default -> 0.9f + rand.nextFloat() * 1.4f;
        };
        slitherAmplitude = scale * (0.5f + rand.nextFloat() * 0.6f);
        slitherWavelength = torsoLength * (0.5f + rand.nextFloat() * 0.5f);
        walkSpeed = switch (locomotion) {
            // Feet stay planted: each foot moves back one stride during the 60% of the cycle it is down
            case WALKER -> stride * stepsPerSecond / 0.6f;
            case SLITHERER -> slitherWavelength * stepsPerSecond * 0.6f;
            case FLYER -> 25f + rand.nextFloat() * 25f;
            default -> 5f + rand.nextFloat() * 6f;
        };

        // --- Colours ---
        float hue = rand.nextFloat();
        float saturation = 0.3f + rand.nextFloat() * 0.55f;
        float value = 0.3f + rand.nextFloat() * 0.45f;
        baseColour = WorldPalette.hsv(hue, saturation, value);
        float accentRoll = rand.nextFloat();
        accentColour = accentRoll < 0.4f ? WorldPalette.hsv(hue + 0.5f, 0.6f + rand.nextFloat() * 0.3f, 0.6f + rand.nextFloat() * 0.35f)
                : accentRoll < 0.75f ? WorldPalette.hsv(hue + 0.08f, saturation * 0.5f, Math.min(1f, value + 0.4f))
                : WorldPalette.hsv(hue, saturation, value * 0.35f);
        bellyColour = WorldPalette.hsv(hue + (rand.nextFloat() - 0.5f) * 0.15f, saturation * 0.45f, Math.min(1f, value + 0.25f));
        limbColour = WorldPalette.hsv(hue + (rand.nextFloat() - 0.5f) * 0.1f, saturation * 0.8f, value * (0.55f + rand.nextFloat() * 0.3f));
        eyeColour = rand.nextFloat() < 0.6f ? new float[] { 0.04f, 0.04f, 0.05f }
                : WorldPalette.hsv(rand.nextFloat(), 0.8f, 0.85f);
        patternType = rand.nextInt(4);
        patternScale = 2.0f + rand.nextFloat() * 5.0f;
        gloss = 0.15f + rand.nextFloat() * 0.85f;

        // --- Range: climate and water preferences, broken into patches; ignores borders ---
        RegionalFactor climate = regions.createTemperaturePreference(rand.nextFloat(), 0.15f + rand.nextFloat() * 0.25f);
        RegionalFactor water = regions.createWaterPreference(rand.nextInt(40), 25.0f + rand.nextFloat() * 70.0f);
        RegionalFactor patches = regions.createNoiseMap(2.0e-5f + rand.nextFloat() * 3.0e-5f);
        // Its home region: broad noise (each feature a large part of a continent across), only its
        // highest third counting, so a species keeps to one part of the world (one that likes the
        // cold north needn't live in the cold south too)
        homeStrength = homeStrength(new Random(seed * 1949L + index));
        RegionalFactor home = regions.createNoiseMap(HOME_SCALE_LOW + new Random(seed * 977L + index).nextFloat() * HOME_SCALE_RANGE);
        habitat = new RegionalFactor(1.0f, (cx, cz, wx, wz) -> {
            float suitability = climate.evaluate(cx, cz, wx, wz) * (0.35f + 0.65f * water.evaluate(cx, cz, wx, wz));
            float h = home.evaluate(cx, cz, wx, wz);
            float t = Math.max(0f, Math.min(1f, (h - HOME_FROM) / (HOME_TO - HOME_FROM)));
            float local = 1f - homeStrength + homeStrength * t * t * (3f - 2f * t);
            return suitability * (0.25f + 1.5f * patches.evaluate(cx, cz, wx, wz)) * 0.8f * local;
        });
        habitatThreshold = 0.3f;
        rarity = (0.3f + rand.nextFloat() * 0.7f) * (locomotion == Locomotion.FLYER ? 0.5f : 1f);
        sizeCline = new PerlinNoise(seed + 9001L + index * 31L);
        legCline = new PerlinNoise(seed + 9101L + index * 31L);
        hueCline = new PerlinNoise(seed + 9201L + index * 31L);
        morphCline = new PerlinNoise(seed + 9301L + index * 31L);
    }

    // A species' home region (see the constructor): the size of the noise it's drawn from, and
    // where on that noise it starts and is fully at home
    // How much this species keeps to its home region: 1 only there, 0 anywhere it suits
    private float homeStrength;

    /**
     * How strongly a species keeps to its home region: some only there, most mainly there but
     * found further afield too, and some anywhere that suits them.
     */
    public static float homeStrength(Random rand) {
        float roll = rand.nextFloat();
        return roll < 0.35f ? 1f : roll < 0.75f ? 0.4f + 0.3f * rand.nextFloat() : 0.1f * rand.nextFloat();
    }

    public static final float HOME_SCALE_LOW = 6.0e-6f, HOME_SCALE_RANGE = 4.0e-6f, HOME_FROM = 0.5f, HOME_TO = 0.64f;

    private int nearestSegment(float z, int first, int last) {
        int best = first;
        for (int s = first; s <= last; s++) {
            if (Math.abs(segmentCentre[s][2] - z) < Math.abs(segmentCentre[best][2] - z)) best = s;
        }
        return best;
    }

    /** How strongly the species lives here: 0 outside its range, rising to 1 at its heart. */
    public float presenceAt(float worldX, float worldZ, float chunkSize) {
        int cx = (int) Math.floor(worldX / chunkSize), cz = (int) Math.floor(worldZ / chunkSize);
        float h = habitat.evaluate(cx, cz, worldX, worldZ);
        return Math.max(0f, Math.min(1f, (h - habitatThreshold) / (1f - habitatThreshold) * 2.0f));
    }

    /** Per-individual traits from where it lives: {size scale, leg scale, hue shift, morph (0 or 1)}. */
    public float[] traitsAt(float worldX, float worldZ, Random rand) {
        float clineScale = 1.0f / 18000f;
        float size = 1.0f + 0.35f * sizeCline.onSphere(Planet.surface(worldX, worldZ), clineScale, 0f, 0f) + (rand.nextFloat() - 0.5f) * 0.12f;
        float legs = 1.0f + 0.3f * legCline.onSphere(Planet.surface(worldX, worldZ), clineScale, 0f, 0f);
        float hueShift = 0.12f * hueCline.onSphere(Planet.surface(worldX, worldZ), clineScale * 0.8f, 0f, 0f);
        // The share of the second morph runs smoothly from none to all across the range
        float share = 0.5f + 0.9f * morphCline.onSphere(Planet.surface(worldX, worldZ), clineScale * 0.7f, 0f, 0f);
        float morph = rand.nextFloat() < share ? 1f : 0f;
        return new float[] { size, legs, hueShift, morph };
    }

    public void buildMeshes(GL3 gl) {
        for (Morph morph : morphs) {
            OrganismMesh.Builder builder = buildMesh(morph);
            morph.mesh = builder.build(gl);
            float[] vertices = builder.vertices();
            int count = vertices.length / OrganismMesh.STRIDE, every = Math.max(1, count / 400);
            float[] outline = new float[(count + every - 1) / every * 4];
            for (int v = 0, k = 0; v < count; v += every, k += 4) {
                System.arraycopy(vertices, v * OrganismMesh.STRIDE, outline, k, 3);
                outline[k + 3] = vertices[v * OrganismMesh.STRIDE + 8];
            }
            morph.outline = outline;
        }
    }

    /** Points of a morph's mesh {x, y, z, bone} in turn, in its bones' own spaces. */
    public float[] outline(int morph) {
        return morphs[morph].outline;
    }

    public OrganismMesh mesh(int morph) {
        return morphs[morph].mesh;
    }

    public void dispose(GL3 gl) {
        for (Morph morph : morphs) {
            if (morph.mesh != null) morph.mesh.dispose(gl);
        }
    }

    /** One line describing the body plan, for the log. */
    public String describe() {
        String body = switch (locomotion) {
            case WALKER -> (legs.size() / 2) + "-pair " + (upright ? "upright" : "sprawling") + " walker";
            case SLITHERER -> "slitherer";
            case FLYER -> (wings.size() / 2) + "-pair flyer";
            default -> "floater with " + tentacles.size() + " tentacles";
        };
        return body + String.format(", %.0f long", halfLength() + tailLength()) + ", " + segments + " segments" + (neckSegments > 0 ? ", neck" : "") + (tailSegments > 0 ? ", tail" : "")
                + ", " + eyes + " eyes" + (hasAntennae ? ", antennae" : "") + (hasTrunk ? ", trunk" : "") + (hasMandibles ? ", pincers" : "");
    }

    private OrganismMesh.Builder buildMesh(Morph morph) {
        OrganismMesh.Builder b = new OrganismMesh.Builder();
        int lastTorso = neckSegments + segments - 1 - neckSegments - tailSegments;
        for (int s = 0; s < segments; s++) {
            b.bone(s).resetTransform();
            boolean isTorso = s > neckSegments && s <= lastTorso;
            boolean isTail = s > lastTorso;
            float taper = s == 0 ? 0.85f : 1.0f;
            OrganismParts.bodySegment(b, segmentLength[s], segmentWidth[s], segmentHeight[s],
                    pointiness, isTorso ? ridges : 0, ridgeDepth, taper);
            // A crest of spines along the back
            if (morph.crest && (isTorso || isTail)) {
                for (int c = -1; c <= 1; c += 2) {
                    b.transform(Affine.multiply(Affine.translation(0f, segmentHeight[s] * 0.42f, c * segmentLength[s] * 0.2f),
                            Affine.rotationX(-1.2f)));
                    OrganismParts.horn(b, segmentHeight[s] * 0.7f, segmentWidth[s] * 0.07f);
                }
            }
            if (morph.tailClub && s == segments - 1) {
                b.transform(Affine.translation(0f, 0f, -segmentLength[s] * 0.5f));
                OrganismParts.shell(b, OrganismMesh.PART_HORN, segmentLength[s] * 0.8f, segmentWidth[s] * 1.6f,
                        segmentHeight[s] * 1.6f, 0.6f, 0, 0f, 1f);
            }
        }

        // Head features ride on the head's bone
        float headLength = segmentLength[0], headWidth = segmentWidth[0], headHeight = segmentHeight[0];
        b.bone(0);
        for (int e = 0; e < eyes; e++) {
            int pair = e / 2;
            int side = (e & 1) == 0 ? -1 : 1;
            float ex = side * headWidth * (0.3f - 0.04f * pair);
            float ey = headHeight * (0.12f + 0.12f * pair);
            float ez = headLength * (0.22f - 0.1f * pair);
            if (eyeStalks) {
                float stalk = headHeight * 0.9f;
                float[] base = Affine.multiply(Affine.translation(ex * 0.6f, headHeight * 0.3f, ez), Affine.rotationX(-1.1f + 0.15f * pair));
                b.transform(base);
                OrganismParts.legSegment(b, stalk, eyeRadius * 0.35f, eyeRadius * 0.3f, 0);
                b.transform(Affine.multiply(base, Affine.translation(0f, 0f, stalk)));
            } else {
                b.transform(Affine.translation(ex, ey, ez));
            }
            OrganismParts.eye(b, eyeRadius);
        }
        for (int h = 0; h < morph.horns; h++) {
            float hx = morph.horns == 1 ? 0f : (h - (morph.horns - 1) * 0.5f) * headWidth * 0.25f;
            b.transform(Affine.multiply(Affine.translation(hx, headHeight * 0.3f, headLength * 0.15f), Affine.rotationX(-0.9f)));
            OrganismParts.horn(b, headLength * 0.8f, headWidth * 0.1f);
        }
        if (hasMandibles) {
            for (int side = -1; side <= 1; side += 2) {
                b.transform(Affine.multiply(Affine.translation(side * headWidth * 0.2f, -headHeight * 0.15f, headLength * 0.42f),
                        Affine.rotationY(-side * 0.5f)));
                OrganismParts.mandible(b, headLength * 0.55f, headWidth * 0.08f);
            }
        }

        // Limbs are modelled at the species' design length; posing stretches them per individual
        for (Leg leg : legs) {
            b.bone(leg.femurBone).resetTransform();
            OrganismParts.legSegment(b, femurLength, legRadius, legRadius * 0.8f, 0);
            b.bone(leg.tibiaBone).resetTransform();
            OrganismParts.legSegment(b, tibiaLength, legRadius * 0.8f, legRadius * 0.55f, morph.spines);
            b.bone(leg.footBone).resetTransform();
            OrganismParts.foot(b, morph.foot, legRadius * 2.4f);
        }
        for (Wing wing : wings) {
            float half = wingSpan * 0.5f;
            b.bone(wing.innerBone).resetTransform();
            OrganismParts.wing(b, half, wingChord, wingChord * 0.8f, false);
            b.bone(wing.outerBone).resetTransform();
            OrganismParts.wing(b, half, wingChord * 0.8f, wingChord * 0.15f, true);
        }
        for (Tentacle tentacle : tentacles) {
            for (int i = 0; i < TENTACLE_BONES; i++) {
                b.bone(tentacle.firstBone + i).resetTransform();
                float r0 = tentacleRadius * (1f - i / (float) TENTACLE_BONES);
                float r1 = tentacleRadius * (1f - (i + 1) / (float) TENTACLE_BONES) + 0.05f;
                OrganismParts.legSegment(b, tentacleLength / TENTACLE_BONES, r0, r1, 0);
            }
        }
        if (hasAntennae) {
            for (int a = 0; a < 2; a++) {
                b.bone(antennaBones + a).resetTransform();
                OrganismParts.antenna(b, morph.antenna, antennaLength, Math.max(0.2f, legRadius * 0.45f));
            }
        }
        if (hasTrunk) {
            b.bone(trunkBone).resetTransform();
            OrganismParts.trunk(b, trunkLength, headWidth * 0.14f, trunkCurl);
        }
        return b;
    }

    /** Distance from the middle of the body to its front and back, for footing on slopes. */
    public float halfLength() {
        return segmentCentre[0][2] + segmentLength[0] * 0.5f;
    }

    public float tailLength() {
        return -(segmentCentre[segments - 1][2] - segmentLength[segments - 1] * 0.5f);
    }

    /** Rough radius enclosing the whole creature, for culling. */
    public float reachRadius() {
        float r = Math.max(halfLength(), tailLength()) + femurLength + tibiaLength + (wings.isEmpty() ? 0f : wingSpan);
        return locomotion == Locomotion.FLOATER ? r + tentacleLength + bodyHeight : r;
    }

    /**
     * Poses one individual, writing a world matrix per bone into bones (16 floats each).
     *
     * @param body       the body's world frame (position, heading, slope and size)
     * @param legScale   this individual's leg length relative to the species
     * @param gaitPhase  how far through its stepping (or slithering, or pulsing) cycle it is
     * @param walking    0 standing still to 1 moving at full pace
     * @param time       seconds, for idle movement of tails, antennae, trunks and tentacles
     */
    public void pose(float[] body, float legScale, float gaitPhase, float walking, float time, float[] bones) {
        float cycle = gaitPhase * (float) Math.PI * 2f;
        for (int s = 0; s < segments; s++) {
            float[] c = segmentCentre[s];
            float x = 0f, yaw = 0f;
            if (locomotion == Locomotion.SLITHERER) {
                // A travelling wave runs down the body; its slope turns each segment
                float k = (float) (2 * Math.PI / slitherWavelength);
                float amplitude = slitherAmplitude * (0.35f + 0.65f * walking) * Math.min(1f, s / 2f);
                x = amplitude * (float) Math.sin(k * c[2] + cycle);
                yaw = (float) Math.atan(amplitude * k * Math.cos(k * c[2] + cycle));
            } else if (s > segments - 1 - tailSegments) {
                // Tails sway, more towards the tip
                float t = (s - (segments - 1 - tailSegments)) / (float) tailSegments;
                x = (float) Math.sin(time * 1.6f - t * 1.5f) * segmentWidth[s] * 0.8f * t;
                yaw = (float) Math.cos(time * 1.6f - t * 1.5f) * 0.35f * t;
            } else if (locomotion == Locomotion.WALKER) {
                yaw = (float) Math.sin(cycle) * 0.05f * walking * (s - 1 - neckSegments);
            }
            float nod = s <= neckSegments && neckSegments > 0 ? (float) Math.sin(time * 0.9f + s) * 0.06f : 0f;
            float[] local = Affine.multiply(Affine.translation(c[0] + x, c[1], c[2]),
                    Affine.multiply(Affine.rotationY(yaw), Affine.rotationX(-(segmentPitch[s] + nod))));
            Affine.multiply(body, local, bones, s * 16);
        }

        float femur = femurLength * legScale, tibia = tibiaLength * legScale;
        float height = bodyHeight * legScale;
        float reach = femur + tibia;
        float[] up = { 0f, 1f, 0f };
        for (Leg leg : legs) {
            float width = segmentWidth[leg.segment] * 0.5f;
            float[] hip = { leg.side * width * (upright ? 0.6f : 0.8f), leg.hipY, leg.hipZ };
            float legCycle = (gaitPhase + leg.phase) % 1f;
            float swing, lift = 0f;
            if (legCycle < 0.6f) {
                swing = 0.5f - legCycle / 0.6f;                  // planted: slides back under the body
            } else {
                float t = (legCycle - 0.6f) / 0.4f;              // lifted: swings forward
                swing = -0.5f + t;
                lift = (float) Math.sin(Math.PI * t);
            }
            float outward = upright ? width * 0.15f : reach * 0.62f;
            float[] foot = {
                hip[0] + leg.side * outward,
                -height + lift * reach * 0.12f * walking,
                hip[2] + leg.splay * reach * 0.6f + swing * stride * legScale * walking
            };
            float[] bend = upright ? new float[] { 0f, 0f, kneeDirection } : new float[] { leg.side * 0.3f, 1f, 0f };
            float[] knee = Affine.middleJoint(hip, foot, femur, tibia, bend);
            float[] femurFrame = Affine.frame(hip, Affine.subtract(knee, hip), up, 1f, 1f, Affine.length(Affine.subtract(knee, hip)) / femurLength);
            float[] tibiaFrame = Affine.frame(knee, Affine.subtract(foot, knee), up, 1f, 1f, Affine.length(Affine.subtract(foot, knee)) / tibiaLength);
            float turn = upright ? 0f : leg.side * 0.6f + leg.splay * 0.8f;
            float[] footFrame = Affine.frame(foot, new float[] { (float) Math.sin(turn), 0f, (float) Math.cos(turn) }, up, 1f, 1f, 1f);
            Affine.multiply(body, femurFrame, bones, leg.femurBone * 16);
            Affine.multiply(body, tibiaFrame, bones, leg.tibiaBone * 16);
            Affine.multiply(body, footFrame, bones, leg.footBone * 16);
        }

        // Wings beat, with spells of gliding; the outer part follows through further
        float beat = 0.35f + 0.65f * Math.max(0f, (float) Math.sin(time * 0.23f + segmentLength[0]));
        for (Wing wing : wings) {
            int seg = nearestSegment(wing.rootZ, Math.min(segments - 1, neckSegments + 1), Math.max(neckSegments + 1, segments - 1 - tailSegments));
            float[] root = { wing.side * segmentWidth[seg] * 0.4f, segmentHeight[seg] * 0.25f, wing.rootZ };
            float flap = (float) Math.sin(cycle + wing.pair * 0.8f) * 0.75f * beat + 0.1f;
            float follow = (float) Math.sin(cycle + wing.pair * 0.8f - 0.6f) * 0.95f * beat + 0.05f;
            float[] innerDir = { wing.side * (float) Math.cos(flap), (float) Math.sin(flap), 0f };
            float[] outerDir = { wing.side * (float) Math.cos(follow), (float) Math.sin(follow), -0.08f };
            Affine.multiply(body, Affine.frame(root, innerDir, up, 1f, 1f, 1f), bones, wing.innerBone * 16);
            float[] elbow = Affine.add(root, innerDir, wingSpan * 0.5f);
            Affine.multiply(body, Affine.frame(elbow, outerDir, up, 1f, 1f, 1f), bones, wing.outerBone * 16);
        }

        // Tentacles hang and drift, each part swinging a little more than the one above
        float bodyBottom = -segmentHeight[0] * 0.35f;
        float piece = tentacleLength / TENTACLE_BONES;
        for (Tentacle tentacle : tentacles) {
            float[] joint = { tentacle.x, bodyBottom + segmentCentre[0][1], tentacle.z + segmentCentre[0][2] };
            for (int i = 0; i < TENTACLE_BONES; i++) {
                float swing = 0.25f * (i + 1);
                float[] dir = {
                    (float) Math.sin(time * 0.9f + tentacle.phase + i * 0.6f) * swing + tentacle.x * 0.04f,
                    -1f,
                    (float) Math.cos(time * 0.7f + tentacle.phase * 1.3f + i * 0.5f) * swing - walking * 0.4f * (i + 1) + tentacle.z * 0.04f
                };
                float[] frame = Affine.frame(joint, dir, new float[] { 0f, 0f, 1f }, 1f, 1f, 1f);
                Affine.multiply(body, frame, bones, (tentacle.firstBone + i) * 16);
                joint = Affine.add(joint, Affine.normalise(dir), piece);
            }
        }

        float headLength = segmentLength[0], headWidth = segmentWidth[0], headHeight = segmentHeight[0];
        float[] headFrame = new float[16];
        System.arraycopy(bones, 0, headFrame, 0, 16);
        if (hasAntennae) {
            for (int a = 0; a < 2; a++) {
                int side = a == 0 ? -1 : 1;
                float wave = (float) Math.sin(time * (1.3f + 0.4f * a) + a * 2.1f);
                float[] base = { side * headWidth * 0.18f, headHeight * 0.3f, headLength * 0.38f };
                float[] dir = { side * (0.45f + 0.15f * wave), 0.35f + 0.15f * (float) Math.cos(time * 0.9f + a), 1f };
                Affine.multiply(headFrame, Affine.frame(base, dir, up, 1f, 1f, 1f), bones, (antennaBones + a) * 16);
            }
        }
        if (hasTrunk) {
            float wave = (float) Math.sin(time * 0.8f);
            float[] base = { 0f, -headHeight * 0.2f, headLength * 0.42f };
            float[] dir = { 0.15f * wave, -0.25f + 0.1f * (float) Math.cos(time * 0.6f), 1f };
            Affine.multiply(headFrame, Affine.frame(base, dir, up, 1f, 1f, 1f), bones, trunkBone * 16);
        }
    }
}
