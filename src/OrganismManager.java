import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;

/**
 * The world's creatures: generates species, populates the land around the viewer, simulates
 * their motion, and draws them, all on the sphere. Individuals belong to geodesic cells and are
 * recreated identically (within a round) whenever their cell comes back into range, so the same
 * place has the same creatures; each cell has as many as its own area and the species' ranges
 * there call for. A creature's heading is D10's: radians from east towards south in the local
 * frame where it stands, carried along with it as it walks.
 */
public class OrganismManager {

    private static final int SPECIES_COUNT = 8;
    // Creatures are kept within KEEP_RADIUS of the viewer, made out to SPAWN_RADIUS and drawn
    // out to DRAW_DISTANCE
    private static final double SPAWN_RADIUS = 700.0, KEEP_RADIUS = 900.0;
    private static final float DRAW_DISTANCE = 900f;
    private static final int POPULATION_LEVEL = 7;
    // Expected creatures per chunk's area (100 by 100) in total, however many species share
    // the ground; each species takes its share by how well suited the place is to it and how
    // common it is
    private static final float PEAK_PER_CHUNK = 0.3f;
    // Those on the ground (walkers, slitherers, floaters) this many times as common again as
    // flyers: they're what's seen about
    private static final float LAND_BOOST = 2.5f;
    private static final int MOST_FLYERS = 3;
    private static final float ROAM_RADIUS = 90f;

    /** One living creature. */
    private static final class Creature {
        OrganismSpecies species;
        int morph;
        float sizeScale, legScale;
        float[] baseColour, accentColour, bellyColour, limbColour;
        SpherePosition home, position, target;
        double heading;
        float pause;
        // Off somewhere further, steadily and a little quicker, rather than grazing about
        boolean travelling;
        float walking;
        float gaitPhase;
        Random rand;
        // Finding a way round things: the heading chosen, when to look again, and progress
        // over the last few seconds to tell when it is stuck
        double steerHeading = Double.NaN;
        float steerTimer;
        // A hop over a rock in the way: how far through it (seconds, 0 when not hopping) and how high
        float hopTime, hopHeight;
        // Drawn standing level (for a picture), not leaning with the slope it's on
        boolean level;
        // Points round its body as last drawn, in the world (see forEachOutline), and which drawing that was
        double[] outline;
        int outlineDrawing;
        float progressTimer, progressExpected;
        SpherePosition progressFrom;
        // The path it walks alone (null in a herd, or for a picture's, which goes about on its own)
        Path path;
        // In a herd (null for one living alone), its place in it {along, across the herd's
        // heading}, its timetable for shuffling round that and how far, and its slant to the
        // herd's way as it stands
        Herd herd;
        float[] slot;
        long shuffleSeed;
        float shuffleReach;
        double slant;
        // A flyer's place round its circle at the round's start, and how wide its circle is (a
        // flock's members share a home, a little apart round it); NaN for one wheeling on its own clock
        double flightPhase = Double.NaN;
        float flightRadius;
    }

    private GeodesicCollision collision;
    private static final float STUCK_SECONDS = 2.0f;

    /** Shares the world's solid things, so creatures walk round them and not through. */
    public void setCollision(GeodesicCollision collision) {
        this.collision = collision;
    }

    /** How much room a creature takes up on the ground. */
    private static float bodyRadius(Creature c) {
        return Math.max(1.0f, Math.min(9f, 0.35f * c.species.reachRadius() * c.sizeScale * c.legScale));
    }

    private final List<OrganismSpecies> species = new ArrayList<>();
    private final Map<Long, List<Creature>> creaturesByCell = new HashMap<>();
    private final GeodesicChunkGrid populationGrid = new GeodesicChunkGrid(POPULATION_LEVEL);
    private long lastCentreCell = Long.MIN_VALUE;
    private final List<Creature> showcase = new ArrayList<>();
    private final long seed;
    // The world's climate, for drawing a species' range again (see measureRanges)
    private final RegionalGenerationManager regions;
    private final float chunkSize;
    private final float seaLevel;
    private final PerlinNoise terrainNoise;
    private final float[] bones = new float[OrganismSpecies.MAX_BONES * 16];
    private Shader shader;
    // Creatures keep to the countryside (built-up places would have them walking through walls),
    // and neither start nor stop on a road, where the crash barriers along highways would hem
    // them in (they may cross)
    private Predicate<SpherePosition> wild = p -> true;
    private RoadLookup roads = (p, clearance) -> false;
    // House plots, which creatures' ways keep out of (the houses and their fences stand in them)
    private Predicate<SpherePosition> plots = p -> false;

    @FunctionalInterface
    public interface RoadLookup {
        boolean near(SpherePosition position, float clearance);
    }

    /** Where creatures may live: anywhere not built up. */
    public void setWild(Predicate<SpherePosition> wild) {
        this.wild = wild;
    }

    public void setRoads(RoadLookup lookup) {
        this.roads = lookup;
    }

    /** Where the house plots are (whether a place is in one). */
    public void setPlots(Predicate<SpherePosition> plots) {
        this.plots = plots;
    }

    /** Somewhere a walking creature can be: dry land in the wild, off the roads. */
    private boolean walkable(SpherePosition p) {
        return TerrainMesh.getLayeredHeight(p, terrainNoise) > seaLevel + 1f && wild.test(p) && !roads.near(p, 10f);
    }

    public OrganismManager(long seed, float chunkSize, float seaLevel, PerlinNoise terrainNoise,
                           RegionalGenerationManager regions) {
        this.seed = seed;
        this.chunkSize = chunkSize;
        this.seaLevel = seaLevel;
        this.terrainNoise = terrainNoise;
        this.regions = regions;
        // Most of a world's creatures live on the ground, where they're seen about: no more than
        // MOST_FLYERS of them fly (one that would be another flyer is made afresh, as another creature)
        int flyers = 0;
        for (int i = 0; i < SPECIES_COUNT; i++) {
            OrganismSpecies made = new OrganismSpecies(seed, i, regions);
            for (int attempt = 1; made.locomotion == OrganismSpecies.Locomotion.FLYER && flyers >= MOST_FLYERS && attempt < 40; attempt++) {
                made = new OrganismSpecies(seed, i + attempt * 1000, regions);
            }
            if (made.locomotion == OrganismSpecies.Locomotion.FLYER) flyers++;
            species.add(made);
            System.out.printf("[ORGANISMS] Species %d: %s%n", i + 1, species.get(i).describe());
        }
        // Each species' name, made as the planet's is, none the same as the planet's or another's
        java.util.Set<String> taken = new java.util.HashSet<>();
        taken.add(PlanetName.forSeed(seed).spelling.toLowerCase(java.util.Locale.ROOT));
        for (int i = 0; i < species.size(); i++) {
            String name = null;
            for (int attempt = 0; name == null || !taken.add(name.toLowerCase(java.util.Locale.ROOT)); attempt++) {
                name = PlanetName.forSeed(seed * 0x2545F4914F6CDD1DL + (i + 1) * 1000003L + attempt * 7919L).spelling;
            }
            speciesNames.add(name);
        }
        // The biggest keep in small herds (a crowd of giants jostling looks wrong)
        for (OrganismSpecies s : species) {
            if (s.herdSize > OrganismSpecies.BIG_HERD && s.halfLength() + s.tailLength() > OrganismSpecies.BIG_HERD_LENGTH) s.herdSize = OrganismSpecies.BIG_HERD;
        }
        // Every world has some that keep together: should none have come out so, the first
        // walker (or failing that, flyer) lives in herds (flocks) of six
        if (species.stream().noneMatch(s -> s.herdSize > 0)) {
            species.stream().filter(s -> s.locomotion == OrganismSpecies.Locomotion.WALKER).findFirst()
                    .or(() -> species.stream().filter(s -> s.locomotion == OrganismSpecies.Locomotion.FLYER).findFirst())
                    .ifPresent(s -> s.herdSize = 6);
        }
        measureRanges();
    }

    // ------------------------------------------------------------------ ranges (see SpeciesRange)

    // Each species' range: spawning and its heatmap both follow it
    private SpeciesRange[] ranges;

    /** Each species' abundance before its range is applied, per chunk's area, at a place (sharing the ground with the others there). */
    private float[] raw(SpherePosition place) {
        float[] presences = new float[species.size()];
        float total = 0f;
        for (int k = 0; k < species.size(); k++) {
            presences[k] = species.get(k).presenceAt(place);
            total += presences[k];
        }
        // (the biggest far fewer: see OrganismSpecies.sizeShare)
        for (int k = 0; k < species.size(); k++) {
            OrganismSpecies s = species.get(k);
            presences[k] = presences[k] / Math.max(1f, total) * s.rarity * s.sizeShare() * PEAK_PER_CHUNK
                    * (s.locomotion == OrganismSpecies.Locomotion.FLYER ? 1f : LAND_BOOST);
        }
        return presences;
    }

    private void measureRanges() {
        List<GeodesicChunkGrid.Direction> land = SpeciesRange.land(terrainNoise, seaLevel);
        float[][] raw = new float[species.size()][land.size()];
        ranges = new SpeciesRange[species.size()];
        // Any species with almost nowhere to live has its range drawn again until it has
        // somewhere (see SpeciesRange.findable); the others' share the ground with it, so all
        // are measured again each time
        int[] attempts = new int[species.size()];
        for (int round = 0; ; round++) {
            java.util.stream.IntStream.range(0, land.size()).parallel().forEach(i -> {
                float[] here = raw(SphereMath.atAltitude(land.get(i), 0.0));
                for (int k = 0; k < here.length; k++) raw[k][i] = here[k];
            });
            boolean redrawn = false;
            for (int k = 0; k < species.size(); k++) {
                ranges[k] = SpeciesRange.of(raw[k], Float.MAX_VALUE);
                if (round < SpeciesRange.MOST_REDRAWS && !ranges[k].findable(raw[k])) {
                    species.get(k).redrawHabitat(seed, k, ++attempts[k], regions);
                    redrawn = true;
                }
            }
            if (!redrawn) break;
        }
        for (int k = 0; k < species.size(); k++) {
            if (attempts[k] > 0) System.out.printf("[ORGANISMS] %s had almost nowhere to live: its range drawn again %d time(s)%n",
                    speciesNames.isEmpty() ? "Species " + (k + 1) : speciesNames.get(k), attempts[k]);
            System.out.printf(java.util.Locale.ROOT, "[ORGANISMS] %s: heartland from %.5f, peak %.4f a chunk%s%n", speciesNames.isEmpty() ? "Species " + (k + 1) : speciesNames.get(k),
                    ranges[k].high, ranges[k].peak, species.get(k).herdSize <= 0 ? ""
                    : (species.get(k).locomotion == OrganismSpecies.Locomotion.FLYER ? ", in flocks of about " : ", in herds of about ") + species.get(k).herdSize);
        }
    }

    /** Species i's range (for its heatmap's key). */
    public SpeciesRange range(int i) {
        return ranges[i];
    }

    /** Where a place comes on species i's range, 0 to 1 (what its heatmap colours by). */
    public float rangeShare(int i, SpherePosition place) {
        return ranges[i].share(raw(place)[i]);
    }

    // ------------------------------------------------------------------ paths

    // The time the paths are measured from: seconds since the round began (the same on both PCs
    // in multiplayer, where rounds begin together)
    private double roundTime;
    // Counts the updates, so each herd's place is worked out once an update
    private long updates;
    // How far a herd wanders from where it was found, and how fast it goes (a share of its
    // members' own pace, so they keep up)
    private static final float HERD_ROAM = 140f, HERD_PACE = 0.7f;
    // How fast (radians a second) one alone turns to face its next stop, and a herd as a whole
    private static final double TURN_RATE = 1.2, HERD_TURN_RATE = 0.5;
    // A way is checked clear of the sea and of house plots every this far along it
    private static final double WAY_CHECK = 20.0;
    // The most legs a path is worked out to (far more than a round needs)
    private static final int MOST_LEGS = 4000;
    // A herd member shuffles to a new spot round its slot about this often, taking this long,
    // within this share of the herd's spacing
    private static final double SHUFFLE_EVERY = 9.0, SHUFFLE_TIME = 2.5;
    private static final float SHUFFLE_REACH = 0.3f;
    // How long before now a herd member's place is looked at again, to tell how it's moving
    private static final double LOOK_BACK = 0.5;

    /** Where a path has its walker at a moment: the place, the way it faces (D10, there), and how fast it's going (0 standing). */
    private record PathState(SpherePosition at, double heading, float pace) {
    }

    /**
     * Where a creature on the ground (or a herd's centre) goes: a path fixed by its seed and the
     * round's clock (not by when or where anyone saw it, nor by anything it met), so it's the
     * same on both PCs at any moment and the same when a viewer comes back to it (M19). It's a
     * string of legs: turn to face the next stop, walk there, stand a while, on and on, never
     * straying far from home. One alone mostly makes purposeful trips, roughly on the way it was
     * already going, now and then grazing about nearby; a herd wanders from stop to stop and grazes.
     */
    private final class Path {
        final long seed;
        final SpherePosition home;
        final float speed;
        final boolean herd;
        // {start, set off, arrive, leave, pace} per leg: round seconds, and how fast it's walked
        final List<double[]> legs = new ArrayList<>();
        // {from, to} per leg
        final List<SpherePosition[]> stops = new ArrayList<>();
        // {before turning, on setting off, on arriving} per leg (the first two at from, the last at to)
        final List<double[]> headings = new ArrayList<>();

        Path(long seed, SpherePosition home, float speed, boolean herd) {
            this.seed = seed;
            this.home = home;
            this.speed = speed;
            this.herd = herd;
            Random r = new Random(seed);
            double facing = r.nextDouble() * Math.PI * 2.0;
            double stand = herd ? 4.0 + r.nextDouble() * 12.0 : r.nextDouble() * 3.0;
            legs.add(new double[] { 0.0, 0.0, 0.0, stand, 0.0 });
            stops.add(new SpherePosition[] { home, home });
            headings.add(new double[] { facing, facing, facing });
        }

        /** Where it is at time t (round seconds), filling in the path as far as that. */
        PathState at(double t) {
            t = Math.max(0.0, t);
            while (legs.get(legs.size() - 1)[3] < t && legs.size() < MOST_LEGS) addLeg();
            int k = legs.size() - 1;
            while (k > 0 && legs.get(k)[0] > t) k--;
            double[] leg = legs.get(k), h = headings.get(k);
            SpherePosition from = stops.get(k)[0], to = stops.get(k)[1];
            if (t < leg[1]) {
                // Turning on the spot to face the way to the next stop
                double f = (t - leg[0]) / (leg[1] - leg[0]);
                return new PathState(from, h[0] + wrap(h[1] - h[0]) * f, 0f);
            }
            if (t < leg[2]) {
                SpherePosition at = SphereMath.slerp(from, to, (t - leg[1]) / (leg[2] - leg[1]));
                return new PathState(at, SphereMath.carryHeading(from, at, h[1]), (float) leg[4]);
            }
            return new PathState(to, h[2], 0f);
        }

        private void addLeg() {
            int k = legs.size();
            double[] last = legs.get(k - 1);
            SpherePosition from = stops.get(k - 1)[1];
            double facing = headings.get(k - 1)[2];
            Random r = new Random(seed * 0x9E3779B97F4A7C15L + k * 0x632BE59BD9B4E019L);
            boolean travelling = herd || r.nextFloat() < 0.65f;
            double fromHome = SphereMath.distance(from, home);
            SpherePosition to = from;
            for (int attempt = 0; attempt < 10; attempt++) {
                double angle, length;
                if (herd) {
                    // Off somewhere, turning back towards home once it has strayed
                    angle = fromHome > HERD_ROAM * 1.5 ? SphereMath.heading(from, home) + (r.nextDouble() - 0.5) * 1.2
                            : r.nextDouble() * Math.PI * 2.0;
                    length = HERD_ROAM * (0.4 + 0.6 * r.nextDouble());
                } else if (travelling) {
                    // A good way off, roughly on the way it was going (back towards home if it
                    // has wandered far), walked steadily
                    double base = fromHome > ROAM_RADIUS * 3f ? SphereMath.heading(from, home) : facing;
                    angle = base + (r.nextDouble() - 0.5) * 1.6;
                    length = ROAM_RADIUS * (1.2 + 1.6 * r.nextDouble());
                } else {
                    // A little grazing about nearby
                    angle = r.nextDouble() * Math.PI * 2.0;
                    length = 10.0 + 25.0 * r.nextDouble();
                }
                SpherePosition candidate = SphereMath.move(from, angle, length);
                if (walkable(candidate) && wayClear(from, candidate)) {
                    to = candidate;
                    break;
                }
            }
            double length = SphereMath.distance(from, to);
            if (length < 1.0) travelling = false;
            double setOffHeading = length < 1.0 ? facing : SphereMath.heading(from, to);
            double start = last[3], setOff = start + Math.abs(wrap(setOffHeading - facing)) / (herd ? HERD_TURN_RATE : TURN_RATE);
            float pace = speed * (travelling && !herd ? 1.35f : 1f);
            double arrive = setOff + length / Math.max(0.1f, pace);
            // A short stop after a trip, a longer one grazing; a herd grazes at each stop
            double rest = herd ? 6.0 + r.nextDouble() * 16.0 : travelling ? 0.3 + r.nextDouble() * 1.7 : 2.0 + r.nextDouble() * 6.0;
            legs.add(new double[] { start, setOff, arrive, arrive + rest, pace });
            stops.add(new SpherePosition[] { from, to });
            headings.add(new double[] { facing, setOffHeading, length < 1.0 ? facing : SphereMath.carryHeading(from, to, setOffHeading) });
        }
    }

    /** An angle brought into -pi to pi. */
    private static double wrap(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    /** Whether the straight way between two places stays on dry land and out of house plots (roads it may cross). */
    private boolean wayClear(SpherePosition a, SpherePosition b) {
        int steps = (int) Math.ceil(SphereMath.distance(a, b) / WAY_CHECK);
        for (int i = 1; i < steps; i++) {
            SpherePosition p = SphereMath.slerp(a, b, (double) i / steps);
            if (TerrainMesh.getLayeredHeight(p, terrainNoise) <= seaLevel + 0.5f || plots.test(p)) return false;
        }
        return true;
    }

    /** A herd: its members keep their places round a centre that walks a path of its own. */
    private static final class Herd {
        final Path path;
        final List<Creature> members = new ArrayList<>();
        // Where its centre is this update, and was a moment before (for which way its members are going)
        PathState now, before;
        long updated = -1;

        Herd(Path path) {
            this.path = path;
        }
    }

    /** A herd member's slot round its centre, in the herd's own frame (along, across), kept apart from those already given out. */
    private static float[] slot(Random rand, int members, float spacing, List<float[]> taken) {
        float[] best = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            double r = spacing * Math.sqrt(members) * Math.sqrt(rand.nextDouble()), a = rand.nextDouble() * Math.PI * 2.0;
            best = new float[] { (float) (r * Math.cos(a)), (float) (r * Math.sin(a)) };
            boolean apart = true;
            for (float[] other : taken) {
                if (Math.hypot(other[0] - best[0], other[1] - best[1]) < spacing * 0.8f) apart = false;
            }
            if (apart) break;
        }
        taken.add(best);
        return best;
    }

    /** Developer aid: a spot deep in the heartland of a species living in herds (null if none does). */
    public Viewpoint herdViewpoint(Random rand) {
        for (int attempt = 0; attempt < 60000; attempt++) {
            int k = attempt % species.size();
            OrganismSpecies s = species.get(k);
            if (s.herdSize <= 0 || s.locomotion != OrganismSpecies.Locomotion.WALKER) continue;
            SpherePosition p = SphereMath.atAltitude(SphereMath.randomPlace(rand, MapProjection.CLIP_LATITUDE), 0.0);
            if (walkable(p) && rangeShare(k, p) > 0.9f) return new Viewpoint(p, rand.nextDouble() * Math.PI * 2.0);
        }
        return null;
    }

    /** Developer aid: the centre of the nearest herd to a place just now, or null. */
    public SpherePosition nearestHerd(SpherePosition place) {
        SpherePosition best = null;
        double bestDistance = Double.MAX_VALUE;
        for (List<Creature> list : creaturesByCell.values()) {
            for (Creature c : list) {
                if (c.herd == null || c.herd.now == null) continue;
                double d = SphereMath.distance(place, c.herd.now.at());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = c.herd.now.at();
                }
            }
        }
        return best;
    }

    /** A number from 0 to 1 drawn from a seed and a count (the same for the same pair). */
    private static double hashed(long seed, long n) {
        long z = seed + n * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /**
     * A herd member's place at time t, as its herd's centre stands then: its slot, turned with
     * the herd, and a little to one side of it, shuffling to a new spot every few seconds (each
     * on a timetable of its own).
     */
    private SpherePosition memberPlace(Creature c, PathState centre, double t) {
        double every = SHUFFLE_EVERY * (0.7 + 0.6 * hashed(c.shuffleSeed, -1));
        double phase = t / every + hashed(c.shuffleSeed, -2);
        long n = (long) Math.floor(phase);
        double f = Math.min(1.0, (phase - n) * every / SHUFFLE_TIME);
        f = f * f * (3.0 - 2.0 * f);
        double reach = c.shuffleReach;
        double a0 = hashed(c.shuffleSeed, 2 * n - 2) * Math.PI * 2.0, r0 = reach * Math.sqrt(hashed(c.shuffleSeed, 2 * n - 1));
        double a1 = hashed(c.shuffleSeed, 2 * n) * Math.PI * 2.0, r1 = reach * Math.sqrt(hashed(c.shuffleSeed, 2 * n + 1));
        double along = c.slot[0] + r0 * Math.cos(a0) + (r1 * Math.cos(a1) - r0 * Math.cos(a0)) * f;
        double across = c.slot[1] + r0 * Math.sin(a0) + (r1 * Math.sin(a1) - r0 * Math.sin(a0)) * f;
        SpherePosition p = SphereFrame.at(centre.at(), centre.heading()).toSphere(along, 0.0, across);
        // (never out over the water: drawn in towards the herd's centre till it's on land)
        for (int i = 3; i >= 0 && TerrainMesh.getLayeredHeight(p, terrainNoise) <= seaLevel + 0.5f; i--) {
            p = SphereMath.slerp(centre.at(), p, i / 4.0);
        }
        return p;
    }

    /**
     * Puts a creature on the ground where its path (or its herd's) has it just now, facing the
     * way it's going, or for a herd member standing, the herd's way with its own slant. Only
     * walls, rocks and the like push it about as it's drawn (never moving things, nor anything
     * carried over from one update to the next), so where it is depends on the time alone.
     */
    private void place(Creature c, float dt) {
        SpherePosition ideal;
        float walking;
        if (c.herd != null) {
            Herd h = c.herd;
            if (h.updated != updates) {
                h.now = h.path.at(roundTime);
                h.before = h.path.at(roundTime - LOOK_BACK);
                h.updated = updates;
            }
            ideal = memberPlace(c, h.now, roundTime);
            SpherePosition before = memberPlace(c, h.before, roundTime - LOOK_BACK);
            double moved = SphereMath.distance(before, ideal), speed = moved / LOOK_BACK;
            // Facing the way it's going as it moves, the herd's way (with its own slant) as it stands
            double rest = SphereMath.carryHeading(h.now.at(), ideal, h.now.heading()) + c.slant;
            double going = moved > 1e-3 ? SphereMath.carryHeading(before, ideal, SphereMath.heading(before, ideal)) : rest;
            double w = Math.min(1.0, speed / Math.max(0.1, 0.4 * c.species.walkSpeed));
            double x = w * Math.cos(going) + (1.0 - w) * Math.cos(rest), z = w * Math.sin(going) + (1.0 - w) * Math.sin(rest);
            c.heading = x * x + z * z > 1e-6 ? Math.atan2(z, x) : rest;
            walking = (float) Math.min(1.0, speed / Math.max(0.1, c.species.walkSpeed * 0.6));
        } else {
            PathState now = c.path.at(roundTime);
            ideal = now.at();
            c.heading = now.heading();
            walking = now.pace() > 0f ? 1f : 0f;
        }
        c.position = collision != null ? resolveBody(c, ideal, legHeight(c) * STEP_OVER) : ideal;
        c.travelling = walking > 0.5f;
        c.walking += (walking - c.walking) * Math.min(1f, dt * 3f);
        c.gaitPhase = (c.gaitPhase + dt * c.species.stepsPerSecond * c.walking) % 1f;
        if (collision != null) {
            // The whole length is solid to others: middle, nose and tail
            collision.addBody(c, c.position, bodyRadius(c));
            collision.addBody(c, SphereMath.move(c.position, c.heading, noseReach(c)), endRadius(c));
            collision.addBody(c, SphereMath.move(c.position, c.heading + Math.PI, tailReach(c)), endRadius(c));
        }
    }

    // Each species' name (see the constructor)
    private final List<String> speciesNames = new ArrayList<>();

    /** Species i's name. */
    public String speciesName(int i) {
        return i >= 0 && i < speciesNames.size() ? speciesNames.get(i) : "";
    }

    /**
     * A picture of an individual of species i, size pixels square on a clear background:
     * drawn by the GPU from three-quarters on, lit from the side (for the map's choices).
     */
    public java.awt.image.BufferedImage portrait(GL3 gl, int i, int size) {
        // (at full detail, framed to the creature itself: M54)
        java.awt.image.BufferedImage view = view(gl, i, 512, (float) Math.toRadians(-40), Planet.position(0.0, 0.0, 0.0), true);
        return view == null ? null : Offscreen.cropTogether(new java.awt.image.BufferedImage[] { view }, size)[0];
    }

    /**
     * An individual of species i as it would be at a place (its size and colouring vary across
     * the range), turned to heading (radians, its nose from local +z towards +x), seen from
     * three-quarters on in a wide view (render pixels square, clear round it): to be cut down
     * to the creature with Offscreen.cropTogether. It is drawn in its own space, standing
     * level on flat ground at the origin.
     */
    public java.awt.image.BufferedImage view(GL3 gl, int i, int render, float heading, SpherePosition at) {
        return view(gl, i, render, heading, at, false);
    }

    /** As view; fitted, framed to the creature itself (see Offscreen.captureFitted). */
    public java.awt.image.BufferedImage view(GL3 gl, int i, int render, float heading, SpherePosition at, boolean fitted) {
        if (shader == null || i < 0 || i >= species.size()) return null;
        OrganismSpecies s = species.get(i);
        Creature c = create(s, at, new Random(seed * 131L + i));
        c.heading = Math.PI * 0.5 - heading;
        c.walking = 0f;
        c.gaitPhase = 0.25f;
        c.hopTime = 0f;
        c.level = true;
        float reach = s.reachRadius() * c.sizeScale * c.legScale;
        float centreY = bodyHeight(c, 0f, 0f, 0f, 0f);
        Vector3 centre = new Vector3(0f, centreY, 0f);
        float distance = reach * 5f + 25f;
        Vector3 eye = new Vector3(distance * 0.62f, centreY + distance * 0.42f, distance * 0.66f);
        Matrix4 view = com.xenoguesser.math.Matrix4Transform.lookAt(eye, centre, new Vector3(0f, 1f, 0f));
        Matrix4 projection = com.xenoguesser.math.Matrix4Transform.perspective(45f, 1f, distance * 0.02f, distance * 4f);
        Matrix4 viewProjection = Matrix4.multiply(projection, view);
        Vector3 sun = new Vector3(eye.x + distance * 3f, eye.y + distance * 6f, eye.z - distance * 1f);
        java.util.function.Consumer<Matrix4> draw = zoom -> {
            shader.use(gl);
            shader.setFloatArray(gl, "viewProjection", Matrix4.multiply(zoom, viewProjection).toFloatArrayForGLSL());
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1, false, new Matrix4(1).toFloatArrayForGLSL(), 0);
            shader.setVec3(gl, "viewPos", eye);
            shader.setVec3(gl, "sunPos", sun);
            shader.setVec3(gl, "sunColour", new Vector3(1f, 0.97f, 0.92f));
            shader.setVec3(gl, "ambientLight", new Vector3(0.55f, 0.55f, 0.6f));
            SphereRender.setAnchor(gl, shader, null);
            beginCreatures(gl, null);
            drawPosed(gl, c, centreY, 0f, 0f);
            gl.glEnable(GL.GL_CULL_FACE);
        };
        return fitted ? Offscreen.captureFitted(gl, render, draw) : Offscreen.capture(gl, render, () -> draw.accept(new Matrix4(1)));
    }

    public int speciesCount() {
        return species.size();
    }

    /** Where species i lives, as a factor the debug minimap can show. */
    public RegionalFactor habitat(int i) {
        return i < species.size() ? species.get(i).habitat : null;
    }

    public void initialise(GL3 gl) {
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_organism.txt", GamePaths.HOME + "assets/shaders/fs_organism.txt");
        for (OrganismSpecies s : species) {
            s.buildMeshes(gl);
        }
    }

    public void dispose(GL3 gl) {
        for (OrganismSpecies s : species) {
            s.dispose(gl);
        }
    }

    // Changed every round, so each round's creatures are drawn afresh (from the same ranges)
    private long roundSalt;

    /** Forgets every creature, e.g. when the viewer jumps to a new round; the next round's are a fresh draw. */
    public void clear() {
        clear(new Random().nextLong());
    }

    /** As clear(), with the next round's creatures drawn from this salt (multiplayer: the same on both PCs, M19). */
    public void clear(long salt) {
        roundSalt = salt;
        creaturesByCell.clear();
        showcase.clear();
        lastCentreCell = Long.MIN_VALUE;
    }

    /**
     * Places the round's creatures afresh from the same draw (M54: as the round begins, so none
     * has wandered from where its range puts it while the round was waiting to start).
     */
    public void respawn() {
        // (done by the next update, on the thread that moves them)
        respawnWanted = true;
    }

    private volatile boolean respawnWanted;

    /** Developer aid: where the creature the last showcase put down is just now, or null. */
    public SpherePosition devShowcasePlace() {
        return showcase.isEmpty() ? null : showcase.get(showcase.size() - 1).position;
    }

    /**
     * Developer aid: a spot in the heart of some species' range, and a creature of that species
     * placed just in front of it.
     */
    public Viewpoint showcaseViewpoint(Random rand) {
        for (int attempt = 0; attempt < 40000; attempt++) {
            OrganismSpecies s = species.get(attempt % species.size());
            SpherePosition p = SphereMath.randomPlace(rand, MapProjection.CLIP_LATITUDE);
            if (TerrainMesh.getLayeredHeight(p, terrainNoise) <= seaLevel + 2f || s.presenceAt(p) < 0.5f || !wild.test(p)) continue;
            double heading = rand.nextDouble() * Math.PI * 2.0;
            SpherePosition at = SphereMath.move(p, heading, 30.0 + s.halfLength() * 3.0);
            if (TerrainMesh.getLayeredHeight(at, terrainNoise) <= seaLevel + 2f) continue;
            Creature creature = create(s, at, new Random(rand.nextLong()));
            creature.heading = SphereMath.carryHeading(p, at, heading) - Math.PI * 0.6;
            showcase.add(creature);
            System.out.println("[DEV] Showcase creature: " + speciesName(attempt % species.size()));
            return new Viewpoint(p, heading);
        }
        return null;
    }

    /**
     * Developer aid: one of every species in an arc in front of a flat spot on dry land, facing
     * south, to compare them side by side.
     */
    public Viewpoint zooViewpoint(Random rand) {
        for (int attempt = 0; attempt < 20000; attempt++) {
            SpherePosition p = SphereMath.randomPlace(rand, MapProjection.CLIP_LATITUDE);
            if (!wild.test(p)) continue;
            SphereFrame frame = SphereFrame.at(p);
            float lowest = Float.MAX_VALUE, highest = -Float.MAX_VALUE;
            for (int i = 0; i < 25; i++) {
                float h = TerrainMesh.getLayeredHeight(frame.toSphere((i % 5 - 2) * 60.0, 0.0, (i / 5) * 60.0), terrainNoise);
                lowest = Math.min(lowest, h);
                highest = Math.max(highest, h);
            }
            if (lowest <= seaLevel + 2f || highest - lowest > 25f) continue;
            double facing = Math.PI * 0.5;
            for (int i = 0; i < species.size(); i++) {
                double angle = (i - (species.size() - 1) * 0.5) * 0.2;
                double distance = 110.0 + (i % 2) * 60.0;
                SpherePosition at = SphereMath.move(p, facing - angle, distance);
                Creature c = create(species.get(i), at, new Random(rand.nextLong()));
                // Side on to the viewer, facing east
                c.heading = 0.0;
                c.pause = 1000f;
                if (c.species.locomotion == OrganismSpecies.Locomotion.FLYER) {
                    // Flyers wheel overhead, starting ahead of the viewer
                    c.home = frame.toSphere(c.species.flightRadius, 0.0, c.species.flightRadius * 0.5);
                    c.pause = (float) Math.PI + 0.15f * i;
                }
                showcase.add(c);
            }
            return new Viewpoint(p, facing);
        }
        return null;
    }

    /**
     * Brings creatures into being near the viewer, retires distant ones, and moves them all on;
     * roundTime is seconds since the round began, which the herds' paths follow.
     */
    public void update(float dt, SpherePosition viewer, double roundTime) {
        this.roundTime = roundTime;
        if (respawnWanted) {
            respawnWanted = false;
            clear(roundSalt);
        }
        updates++;
        updatePopulation(viewer);
        float step = Math.min(dt, 0.1f);
        for (List<Creature> list : creaturesByCell.values()) {
            for (Creature c : list) move(c, step);
        }
        for (Creature c : showcase) move(c, step);
    }

    private void updatePopulation(SpherePosition viewer) {
        GeodesicChunkGrid.Cell centre = populationGrid.cellAt(viewer);
        if (centre.id() == lastCentreCell) return;
        lastCentreCell = centre.id();
        for (GeodesicChunkGrid.Cell cell : populationGrid.cellsWithin(SphereMath.direction(viewer), SPAWN_RADIUS / Planet.radius())) {
            if (!creaturesByCell.containsKey(cell.id())) creaturesByCell.put(cell.id(), populate(cell));
        }
        double keep = KEEP_RADIUS / Planet.radius();
        creaturesByCell.keySet().removeIf(id -> {
            GeodesicChunkGrid.Cell cell = populationGrid.cell(id);
            return SphereMath.angle(SphereMath.direction(viewer), cell.centre()) > keep + cell.angularRadius();
        });
    }

    /**
     * How many of species i would be expected in a chunk's area (100 by 100) round a place were
     * it wild (as populate spawns them: its range's density there, see SpeciesRange); towns,
     * which none of them live in, are left out (populate keeps them out of towns itself).
     */
    public float expectedPerChunk(int i, SpherePosition place) {
        return ranges[i].density(raw(place)[i]);
    }

    /**
     * A cell's creatures: for each species, candidates at its peak density for the cell's area
     * (a herd's worth at a time for those in herds), each kept where it falls by how common the
     * species is just there (SpeciesRange.keep) and only in the wild, so they follow its heatmap
     * closely. A herd's members gather round where its centre is now on its path.
     */
    private List<Creature> populate(GeodesicChunkGrid.Cell cell) {
        List<Creature> list = new ArrayList<>();
        Random rand = new Random(seed ^ roundSalt ^ (cell.id() * 0x9E3779B97F4A7C15L) ^ 0x0B6L);
        float areaInChunks = (float) (cell.area() * Planet.radius() * Planet.radius() / (chunkSize * chunkSize));
        for (int k = 0; k < species.size(); k++) {
            OrganismSpecies s = species.get(k);
            int group = Math.max(1, s.herdSize);
            float expected = ranges[k].peak * areaInChunks / group;
            int count = 0;
            float roll = rand.nextFloat();
            while (expected > 0f && roll < expected) {
                count++;
                expected -= 1f;
            }
            boolean flies = s.locomotion == OrganismSpecies.Locomotion.FLYER;
            for (int i = 0; i < count; i++) {
                SpherePosition p = SphereMath.atAltitude(cell.point(rand.nextDouble(), rand.nextDouble()), 0.0);
                float kept = rand.nextFloat();
                long creatureSeed = rand.nextLong();
                if (kept >= ranges[k].keep(raw(p)[k]) || !wild.test(p) || (!flies && !walkable(p))) continue;
                if (flies) {
                    // A flyer, or a flock: round one home, at one phase (each a little behind the
                    // last, on a slightly wider or narrower circle)
                    Random flockRand = new Random(creatureSeed);
                    double phase = flockRand.nextDouble() * Math.PI * 2.0;
                    int members = s.herdSize <= 0 ? 1 : Math.max(2, Math.round(s.herdSize * (0.6f + 0.8f * flockRand.nextFloat())));
                    for (int m = 0; m < members; m++) {
                        Creature c = create(s, p, new Random(flockRand.nextLong()));
                        c.flightPhase = phase - m * 0.08 + (flockRand.nextDouble() - 0.5) * 0.05;
                        c.flightRadius = s.flightRadius * (0.9f + 0.2f * flockRand.nextFloat());
                        list.add(c);
                    }
                    continue;
                }
                if (s.herdSize <= 0) {
                    // One alone, on a path of its own
                    Creature c = create(s, p, new Random(creatureSeed));
                    c.path = new Path(creatureSeed ^ 0x5A17L, c.home, s.walkSpeed * c.sizeScale * c.legScale, false);
                    list.add(c);
                    continue;
                }
                Random herdRand = new Random(creatureSeed);
                Herd herd = new Herd(new Path(herdRand.nextLong(), p, s.walkSpeed * HERD_PACE, true));
                PathState start = herd.path.at(0.0);
                int members = Math.max(2, Math.round(s.herdSize * (0.6f + 0.8f * herdRand.nextFloat())));
                float spacing = Math.max(4f, s.halfLength() * 2.2f);
                List<float[]> taken = new ArrayList<>();
                for (int m = 0; m < members; m++) {
                    float[] slot = slot(herdRand, members, spacing, taken);
                    // (each member's looks from where it stands as the herd sets out)
                    SpherePosition at = SphereFrame.at(start.at(), start.heading()).toSphere(slot[0], 0.0, slot[1]);
                    Creature c = create(s, at, new Random(herdRand.nextLong()));
                    c.herd = herd;
                    c.slot = slot;
                    c.shuffleSeed = herdRand.nextLong();
                    c.shuffleReach = spacing * SHUFFLE_REACH;
                    c.slant = (herdRand.nextDouble() - 0.5) * 1.2;
                    herd.members.add(c);
                }
                list.addAll(herd.members);
            }
        }
        // (each put where it is just now, so none is seen in the wrong place for a moment)
        for (Creature c : list) if (c.path != null || c.herd != null) place(c, 0f);
        return list;
    }

    private Creature create(OrganismSpecies s, SpherePosition at, Random rand) {
        float[] traits = s.traitsAt(at, rand);
        Creature c = new Creature();
        c.species = s;
        c.rand = rand;
        c.sizeScale = traits[0];
        c.legScale = traits[1];
        c.morph = (int) traits[3];
        c.baseColour = shiftHue(s.baseColour, traits[2]);
        c.accentColour = shiftHue(s.accentColour, traits[2]);
        c.bellyColour = shiftHue(s.bellyColour, traits[2] * 0.5f);
        c.limbColour = shiftHue(s.limbColour, traits[2]);
        c.home = c.position = c.progressFrom = SphereMath.atAltitude(at, 0.0);
        c.heading = rand.nextFloat() * Math.PI * 2.0;
        c.gaitPhase = rand.nextFloat();
        c.pause = rand.nextFloat() * 3f;
        pickTarget(c);
        return c;
    }

    private static float[] shiftHue(float[] rgb, float shift) {
        float[] hsv = WorldPalette.toHsv(rgb);
        return WorldPalette.hsv(hsv[0] + shift, hsv[1], hsv[2]);
    }

    /**
     * Where to go next. Mostly a purposeful trip: a good way off, roughly on the way it was
     * already going (turning back towards home if it has wandered far), walked steadily. Now and
     * then a little grazing about nearby instead.
     */
    private void pickTarget(Creature c) {
        c.travelling = c.rand.nextFloat() < 0.65f;
        double fromHome = SphereMath.distance(c.position, c.home);
        double towardsHome = fromHome < 1e-6 ? c.heading : SphereMath.heading(c.position, c.home);
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle, r;
            if (c.travelling) {
                double base = fromHome > ROAM_RADIUS * 3f ? towardsHome : c.heading;
                angle = base + (c.rand.nextFloat() - 0.5f) * 1.6f;
                r = ROAM_RADIUS * (1.2f + 1.6f * c.rand.nextFloat());
            } else {
                angle = c.rand.nextFloat() * Math.PI * 2.0;
                r = 10f + 25f * c.rand.nextFloat();
            }
            SpherePosition t = SphereMath.move(c.position, angle, r);
            if (walkable(t)) {
                c.target = t;
                return;
            }
        }
        c.travelling = false;
        c.target = c.home;
    }

    /** A new goal roughly behind it, for when the way ahead is blocked. */
    private void pickTargetAway(Creature c) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = c.heading + Math.PI + (c.rand.nextDouble() - 0.5) * 2.2;
            float r = ROAM_RADIUS * (0.3f + 0.4f * c.rand.nextFloat());
            SpherePosition t = SphereMath.move(c.position, angle, r);
            if (TerrainMesh.getLayeredHeight(t, terrainNoise) > seaLevel + 1f && !roads.near(t, 10f)) {
                c.target = t;
                c.travelling = true;
                return;
            }
        }
        pickTarget(c);
    }

    /** Goes a distance along its heading, carrying the heading along the step. */
    private static void step(Creature c, SpherePosition to) {
        c.heading = SphereMath.carryHeading(c.position, to, c.heading);
        c.position = to;
    }

    /** Walks towards its current goal, turning as it goes, and stops for a while on arrival. */
    private void move(Creature c, float dt) {
        if (c.species.locomotion == OrganismSpecies.Locomotion.FLYER) {
            // Flyers wheel round their home in wide circles, over land or water: by the round's
            // clock (so the same on both PCs, and a flock together), or for a picture's on its own
            float radius = Double.isNaN(c.flightPhase) ? c.species.flightRadius : c.flightRadius;
            float angular = c.species.walkSpeed * (Double.isNaN(c.flightPhase) ? c.sizeScale : 1f) / radius;
            double angle;
            if (Double.isNaN(c.flightPhase)) {
                c.pause += dt * angular;                  // the angle round the circle
                angle = c.pause;
            } else {
                angle = c.flightPhase + angular * roundTime;
            }
            c.position = SphereMath.move(c.home, angle, radius);
            c.heading = SphereMath.carryHeading(c.home, c.position, angle + Math.PI * 0.5);
            c.walking = 1f;
            c.gaitPhase = (c.gaitPhase + dt * c.species.stepsPerSecond) % 1f;
            return;
        }
        if (c.path != null || c.herd != null) {
            place(c, dt);
            return;
        }
        float speed = c.species.walkSpeed * c.sizeScale * c.legScale * (c.travelling ? 1.35f : 1f);
        boolean resting = c.pause > 0f;
        if (resting) {
            c.pause -= dt;
        } else {
            double distance = SphereMath.distance(c.position, c.target);
            if (distance < 4.0) {
                // A short stop after a trip; a longer one grazing
                c.pause = c.travelling ? 0.3f + c.rand.nextFloat() * 1.7f : 2f + c.rand.nextFloat() * 6f;
                pickTarget(c);
            } else {
                double desired = SphereMath.heading(c.position, c.target);
                if (collision != null) {
                    // Look a little way ahead every so often and bear round anything in the way
                    float r = bodyRadius(c);
                    c.steerTimer -= dt;
                    if (c.steerTimer <= 0f) {
                        c.steerTimer = 0.25f + c.rand.nextFloat() * 0.1f;
                        // Looking ahead from its nose, so a long body doesn't lead its head into
                        // things (the headings carried to the nose and back)
                        SpherePosition nose = SphereMath.move(c.position, c.heading, noseReach(c));
                        double steered = collision.steer(nose, endRadius(c), SphereMath.carryHeading(c.position, nose, desired),
                                Math.max(6f, r * 2.5f), c, 0.0);
                        c.steerHeading = Double.isNaN(steered) ? Double.NaN : SphereMath.carryHeading(nose, c.position, steered);
                        if (Double.isNaN(c.steerHeading)) {
                            // Hemmed in on every side: stop, then try somewhere else
                            c.pause = 0.5f + c.rand.nextFloat();
                            pickTargetAway(c);
                        }
                    }
                    if (!Double.isNaN(c.steerHeading)) desired = c.steerHeading;
                }
                double turn = Math.atan2(Math.sin(desired - c.heading), Math.cos(desired - c.heading));
                double maxTurn = 1.2 * dt;
                c.heading += Math.max(-maxTurn, Math.min(maxTurn, turn));
                // Slow down for sharp turns so it doesn't skid round them
                float pace = c.walking * (1f - 0.6f * (float) Math.min(1.0, Math.abs(turn)));
                SpherePosition next = SphereMath.move(c.position, c.heading, speed * pace * dt);
                if (TerrainMesh.getLayeredHeight(next, terrainNoise) > seaLevel + 0.5f) {
                    if (collision != null) {
                        // Small rocks it simply steps over; walking into a bigger one, it hops
                        // over it, carrying on forwards (a wall or anything too tall still stops it)
                        float legs = legHeight(c);
                        if (c.hopTime <= 0f && collision.blocked(next, bodyRadius(c), c, legs * STEP_OVER)
                                && !collision.blocked(next, bodyRadius(c), c, legs * HOP_OVER)) {
                            c.hopTime = HOP_SECONDS;
                            c.hopHeight = legs * HOP_OVER;
                        }
                        next = resolveBody(c, next, c.hopTime > 0f ? c.hopHeight : legs * STEP_OVER);
                    }
                    c.progressExpected += speed * pace * dt;
                    step(c, next);
                } else {
                    pickTarget(c);
                }
                // Stuck: it has been trying to walk but got almost nowhere, so it gives up on
                // this way and heads off somewhere else
                c.progressTimer += dt;
                if (c.progressTimer > STUCK_SECONDS) {
                    double moved = SphereMath.distance(c.position, c.progressFrom);
                    if (c.progressExpected > 4f && moved < c.progressExpected * 0.3f) {
                        pickTargetAway(c);
                        c.steerTimer = 0f;
                    }
                    c.progressTimer = 0f;
                    c.progressExpected = 0f;
                    c.progressFrom = c.position;
                }
            }
        }
        if (c.hopTime > 0f) c.hopTime = Math.max(0f, c.hopTime - dt);
        float targetWalking = resting ? 0f : 1f;
        c.walking += (targetWalking - c.walking) * Math.min(1f, dt * 3f);
        c.gaitPhase = (c.gaitPhase + dt * c.species.stepsPerSecond * c.walking) % 1f;
        if (collision != null) {
            // The whole length is solid to others: middle, nose and tail
            collision.addBody(c, c.position, bodyRadius(c));
            collision.addBody(c, SphereMath.move(c.position, c.heading, noseReach(c)), endRadius(c));
            collision.addBody(c, SphereMath.move(c.position, c.heading + Math.PI, tailReach(c)), endRadius(c));
        }
    }

    // A creature on the ground is a chain of three circles: its middle, its nose and its tail,
    // so a long one (a snake, say) can't push its head through a fence its middle stops at
    private static float noseReach(Creature c) {
        return c.species.halfLength() * c.sizeScale * 0.9f;
    }

    private static float tailReach(Creature c) {
        return c.species.tailLength() * c.sizeScale * 0.8f;
    }

    private static float endRadius(Creature c) {
        return Math.max(0.6f, Math.min(bodyRadius(c), c.species.halfLength() * c.sizeScale * 0.3f));
    }

    // Rocks lower than this (times its legs' height) it steps over; those up to this it hops
    // over, taking this long in the air
    private static final float STEP_OVER = 0.6f, HOP_OVER = 2.4f, HOP_SECONDS = 0.7f;

    /** How high its body stands above the ground on its legs. */
    private static float legHeight(Creature c) {
        return Math.max(1f, c.species.bodyHeight * c.legScale * c.sizeScale);
    }

    /** How high it is off the ground in a hop just now (an arc up and down). */
    private static float hopLift(Creature c) {
        if (c.hopTime <= 0f) return 0f;
        float t = 1f - c.hopTime / HOP_SECONDS;
        return c.hopHeight * 4f * t * (1f - t);
    }

    /**
     * Moves the whole body, middle, nose and tail, out of anything solid it stands in (walls,
     * rocks and the like: not moving things, so where it ends up is the same on both PCs;
     * anything lower than clearance it passes over), in the tangent frame where it now is.
     */
    private SpherePosition resolveBody(Creature c, SpherePosition p, float clearance) {
        double nose = noseReach(c), tail = tailReach(c), end = endRadius(c);
        for (int pass = 0; pass < 2; pass++) {
            p = collision.resolveStatic(p, bodyRadius(c), clearance);
            SphereFrame frame = SphereFrame.at(p);
            double hx = Math.cos(c.heading), hz = Math.sin(c.heading);
            double[] head = frame.fromSphere(collision.resolveStatic(frame.toSphere(hx * nose, 0.0, hz * nose), end, clearance));
            double x = head[0] - hx * nose, z = head[2] - hz * nose;
            double[] back = frame.fromSphere(collision.resolveStatic(frame.toSphere(x - hx * tail, 0.0, z - hz * tail), end, clearance));
            p = frame.toSphere(back[0] + hx * tail, SphereMath.altitude(p), back[2] + hz * tail);
        }
        return p;
    }

    /**
     * Draws the creatures near enough to see, relative to the camera, lit by the sun in
     * sunDirection (a world direction).
     */
    public void render(GL3 gl, Camera camera, Vector3 sunDirection, float[] sunColour,
            Vector3 ambient, Matrix4 skyRotation, Texture sky, float time) {
        if (shader == null) return;
        SphereRender.configure(gl, shader, camera.getSphereViewProjection(), sunDirection, sunColour, ambient, skyRotation);
        beginCreatures(gl, sky);
        SpherePosition eye = camera.getSpherePosition();
        Vector3 forward = camera.getSphereForwardDirection();
        for (Creature c : showcase) draw(gl, c, eye, forward, time);
        for (List<Creature> list : creaturesByCell.values()) {
            for (Creature c : list) draw(gl, c, eye, forward, time);
        }
        gl.glEnable(GL.GL_CULL_FACE);
    }

    private void beginCreatures(GL3 gl, Texture sky) {
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            shader.setInt(gl, "skyTexture", 2);
        }
        // Creatures keep to dry land, so nothing of theirs is under water
        shader.setFloat(gl, "waterLevel", -1e9f);
        shader.setVec3(gl, "waterTint", new Vector3(0f, 0f, 0f));
        // Thin parts are open lathes seen from all sides
        gl.glDisable(GL.GL_CULL_FACE);
        drawing++;
    }

    /** A creature's outline as drawn: its species, and points round its body in the world {x, y, z, ...}. */
    @FunctionalInterface
    public interface CreatureOutline {
        void at(int species, double[] points);
    }

    // Whether each creature's outline is worked out as it's drawn (while something wants them)
    private boolean keepOutlines;
    // Counts each drawing of the creatures, so an outline left from an earlier one is told apart
    private int drawing;

    public void setKeepOutlines(boolean keep) {
        keepOutlines = keep;
    }

    /** Every creature drawn last time (while outlines are kept), with its outline as drawn. */
    public void forEachOutline(CreatureOutline visit) {
        List<List<Creature>> lists = new ArrayList<>(creaturesByCell.values());
        lists.add(showcase);
        for (List<Creature> list : lists) {
            for (Creature c : list) {
                if (c.outline != null && c.outlineDrawing == drawing) visit.at(species.indexOf(c.species), c.outline);
            }
        }
    }

    /**
     * How high a creature's body stands above the ground under its middle, given the ground's
     * height there, ahead of it and behind it, and the sea's (relative to the same datum).
     */
    private float bodyHeight(Creature c, float ground, float frontGround, float backGround, float time) {
        OrganismSpecies s = c.species;
        if (s.locomotion == OrganismSpecies.Locomotion.FLYER) {
            // Holding its height above the land or sea below, rising and falling by the round's
            // clock (so as high on both PCs)
            return Math.max(ground, seaLevel) + s.bodyHeight + (c.level ? 0f : (float) Math.sin(roundTime * 0.4 + phase(c.home)) * 8f) - ground;
        }
        if (s.locomotion == OrganismSpecies.Locomotion.FLOATER) {
            // Floaters drift level, bobbing gently
            return Math.max(ground, seaLevel) + (s.bodyHeight + (float) Math.sin(roundTime * 0.7 + phase(c.home)) * 2.5f) * c.sizeScale - ground;
        }
        float bob = s.locomotion == OrganismSpecies.Locomotion.WALKER
                ? (float) Math.abs(Math.sin(c.gaitPhase * Math.PI * 4)) * 0.03f * s.bodyHeight * c.walking : 0f;
        return Math.max(ground, (frontGround + backGround) * 0.5f) + (s.bodyHeight * c.legScale + bob) * c.sizeScale + hopLift(c) - ground;
    }

    private void draw(GL3 gl, Creature c, SpherePosition eye, Vector3 forward, float time) {
        OrganismSpecies s = c.species;
        double dx = c.position.x() - eye.x(), dy = c.position.y() - eye.y(), dz = c.position.z() - eye.z();
        if (dx * dx + dy * dy + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE) return;
        float radius = s.reachRadius() * c.sizeScale * c.legScale + (s.locomotion == OrganismSpecies.Locomotion.FLYER ? 60f : 0f);
        if (dx * forward.x + dy * forward.y + dz * forward.z < -radius) return;

        // Level the body along the slope beneath it
        float ground = TerrainMesh.getLayeredHeight(c.position, terrainNoise);
        float front = s.halfLength() * c.sizeScale, back = s.tailLength() * c.sizeScale;
        float frontGround = ground, backGround = ground;
        if (!c.level && s.locomotion != OrganismSpecies.Locomotion.FLYER && s.locomotion != OrganismSpecies.Locomotion.FLOATER) {
            frontGround = TerrainMesh.getLayeredHeight(SphereMath.move(c.position, c.heading, front), terrainNoise);
            backGround = TerrainMesh.getLayeredHeight(SphereMath.move(c.position, c.heading + Math.PI, back), terrainNoise);
        }
        float pitch;
        if (s.locomotion == OrganismSpecies.Locomotion.FLYER) {
            // Banked into the turn
            pitch = c.level ? 0f : 0.05f * (float) Math.sin(c.gaitPhase * Math.PI * 2);
        } else if (s.locomotion == OrganismSpecies.Locomotion.FLOATER) {
            pitch = 0f;
        } else {
            pitch = (float) Math.atan2(frontGround - backGround, front + back);
        }
        float height = bodyHeight(c, ground, frontGround, backGround, time);
        // Posed in the frame where it stands on the ground
        SphereFrame frame = SphereFrame.at(SphereMath.atAltitude(c.position, ground));
        SphereRender.setAnchor(gl, shader, frame.modelMatrix(1f, eye));
        drawPosed(gl, c, height, pitch, time);
        if (keepOutlines) {
            float[] local = s.outline(c.morph);
            if (c.outline == null || c.outline.length != local.length / 4 * 3) c.outline = new double[local.length / 4 * 3];
            for (int k = 0, o = 0; k < local.length; k += 4, o += 3) {
                int m = (int) local[k + 3] * 16;
                float x = local[k], y = local[k + 1], z = local[k + 2];
                SpherePosition p = frame.toWorld(bones[m] * x + bones[m + 4] * y + bones[m + 8] * z + bones[m + 12],
                        bones[m + 1] * x + bones[m + 5] * y + bones[m + 9] * z + bones[m + 13],
                        bones[m + 2] * x + bones[m + 6] * y + bones[m + 10] * z + bones[m + 14]);
                c.outline[o] = p.x();
                c.outline[o + 1] = p.y();
                c.outline[o + 2] = p.z();
            }
            c.outlineDrawing = drawing;
        }
    }

    /**
     * Poses a creature in its own frame (its body height up, turned to its heading and leaning
     * by pitch) and draws it there.
     */
    private void drawPosed(GL3 gl, Creature c, float height, float pitch, float time) {
        OrganismSpecies s = c.species;
        // (the body's nose is along its local +z, turned from +z towards +x by the angle,
        // which runs the other way to a heading)
        float[] body = Affine.multiply(Affine.translation(0f, height, 0f),
                Affine.multiply(Affine.rotationY((float) (Math.PI * 0.5 - c.heading)),
                        Affine.multiply(Affine.rotationX(-pitch), Affine.scale(c.sizeScale, c.sizeScale, c.sizeScale))));
        s.pose(body, c.legScale, c.gaitPhase, c.walking, time + phase(c.home) * 0.01f, bones);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "bones"), s.boneCount, false, bones, 0);
        shader.setVec3(gl, "baseColour", vec(c.baseColour));
        shader.setVec3(gl, "accentColour", vec(c.accentColour));
        shader.setVec3(gl, "bellyColour", vec(c.bellyColour));
        shader.setVec3(gl, "limbColour", vec(c.limbColour));
        shader.setVec3(gl, "eyeColour", vec(s.eyeColour));
        shader.setInt(gl, "patternType", s.patternType);
        shader.setFloat(gl, "patternScale", s.patternScale);
        shader.setFloat(gl, "gloss", s.gloss);
        shader.setVec3(gl, "trimColour", vec(c.limbColour));
        s.mesh(c.morph).render(gl);
    }

    /** A creature's own phase for its bobbing and idling, from where it lives. */
    private static float phase(SpherePosition home) {
        return (float) (Planet.longitude(home) * 1000.0 + Planet.latitude(home) * 370.0);
    }

    private static Vector3 vec(float[] c) {
        return new Vector3(c[0], c[1], c[2]);
    }
}
