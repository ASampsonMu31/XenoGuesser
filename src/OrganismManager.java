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
    private static final float PEAK_PER_CHUNK = 0.05f;
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
        for (int i = 0; i < SPECIES_COUNT; i++) {
            species.add(new OrganismSpecies(seed, i, regions));
            System.out.printf("[ORGANISMS] Species %d: %s%n", i + 1, species.get(i).describe());
        }
        // Each species' name, made as the planet's is, none the same as the planet's or another's
        java.util.Set<String> taken = new java.util.HashSet<>();
        taken.add(PlanetName.forSeed(seed).spelling.toLowerCase());
        for (int i = 0; i < species.size(); i++) {
            String name = null;
            for (int attempt = 0; name == null || !taken.add(name.toLowerCase()); attempt++) {
                name = PlanetName.forSeed(seed * 0x2545F4914F6CDD1DL + (i + 1) * 1000003L + attempt * 7919L).spelling;
            }
            speciesNames.add(name);
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
        java.awt.image.BufferedImage view = view(gl, i, 384, (float) Math.toRadians(-40), Planet.position(0.0, 0.0, 0.0));
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
        return Offscreen.capture(gl, render, () -> {
            shader.use(gl);
            shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1, false, new Matrix4(1).toFloatArrayForGLSL(), 0);
            shader.setVec3(gl, "viewPos", eye);
            shader.setVec3(gl, "sunPos", sun);
            shader.setVec3(gl, "sunColour", new Vector3(1f, 0.97f, 0.92f));
            shader.setVec3(gl, "ambientLight", new Vector3(0.55f, 0.55f, 0.6f));
            SphereRender.setAnchor(gl, shader, null);
            beginCreatures(gl, null);
            drawPosed(gl, c, centreY, 0f, 0f);
            gl.glEnable(GL.GL_CULL_FACE);
        });
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
        roundSalt = new Random().nextLong();
        creaturesByCell.clear();
        showcase.clear();
        lastCentreCell = Long.MIN_VALUE;
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

    /** Brings creatures into being near the viewer, retires distant ones, and moves them all on. */
    public void update(float dt, SpherePosition viewer) {
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
     * it wild (as populate works it out, sharing the ground with the other
     * species there); towns, which none of them live in, are left out (populate keeps them out
     * of towns itself).
     */
    public float expectedPerChunk(int i, SpherePosition place) {
        float total = 0f, mine = 0f;
        for (int k = 0; k < species.size(); k++) {
            float p = species.get(k).presenceAt(place);
            total += p;
            if (k == i) mine = p;
        }
        return mine / Math.max(1f, total) * species.get(i).rarity * PEAK_PER_CHUNK;
    }

    /** The most of species i a chunk can expect (where it's at its best, alone). */
    public float peakPerChunk(int i) {
        return species.get(i).rarity * PEAK_PER_CHUNK;
    }

    private List<Creature> populate(GeodesicChunkGrid.Cell cell) {
        List<Creature> list = new ArrayList<>();
        Random rand = new Random(seed ^ roundSalt ^ (cell.id() * 0x9E3779B97F4A7C15L) ^ 0x0B6L);
        SpherePosition middle = SphereMath.atAltitude(cell.centre(), 0.0);
        float areaInChunks = (float) (cell.area() * Planet.radius() * Planet.radius() / (chunkSize * chunkSize));
        boolean wildHere = wild.test(middle);
        float[] presences = new float[species.size()];
        float total = 0f;
        for (int i = 0; i < species.size(); i++) {
            presences[i] = wildHere ? species.get(i).presenceAt(middle) : 0f;
            total += presences[i];
        }
        for (int k = 0; k < species.size(); k++) {
            OrganismSpecies s = species.get(k);
            float expected = presences[k] / Math.max(1f, total) * s.rarity * PEAK_PER_CHUNK * areaInChunks;
            int count = 0;
            float roll = rand.nextFloat();
            while (expected > 0f && roll < expected) {
                count++;
                expected -= 1f;
            }
            for (int i = 0; i < count; i++) {
                SpherePosition p = SphereMath.atAltitude(cell.point(rand.nextDouble(), rand.nextDouble()), 0.0);
                boolean flies = s.locomotion == OrganismSpecies.Locomotion.FLYER;
                if (!flies && !walkable(p)) continue;
                list.add(create(s, p, new Random(rand.nextLong())));
            }
        }
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
            // Flyers wheel round their home in wide circles, over land or water
            float radius = c.species.flightRadius;
            float angular = c.species.walkSpeed * c.sizeScale / radius;
            c.pause += dt * angular;                      // the angle round the circle
            c.position = SphereMath.move(c.home, c.pause, radius);
            c.heading = SphereMath.carryHeading(c.home, c.position, c.pause + Math.PI * 0.5);
            c.walking = 1f;
            c.gaitPhase = (c.gaitPhase + dt * c.species.stepsPerSecond) % 1f;
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
     * Moves the whole body, middle, nose and tail, out of anything it has walked into (anything
     * lower than clearance it passes over), in the tangent frame where it now is.
     */
    private SpherePosition resolveBody(Creature c, SpherePosition p, float clearance) {
        double nose = noseReach(c), tail = tailReach(c), end = endRadius(c);
        for (int pass = 0; pass < 2; pass++) {
            p = collision.resolve(p, bodyRadius(c), c, clearance);
            SphereFrame frame = SphereFrame.at(p);
            double hx = Math.cos(c.heading), hz = Math.sin(c.heading);
            double[] head = frame.fromSphere(collision.resolve(frame.toSphere(hx * nose, 0.0, hz * nose), end, c, clearance));
            double x = head[0] - hx * nose, z = head[2] - hz * nose;
            double[] back = frame.fromSphere(collision.resolve(frame.toSphere(x - hx * tail, 0.0, z - hz * tail), end, c, clearance));
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
            // Holding its height above the land or sea below
            return Math.max(ground, seaLevel) + s.bodyHeight + (c.level ? 0f : (float) Math.sin(time * 0.4f + phase(c.home)) * 8f) - ground;
        }
        if (s.locomotion == OrganismSpecies.Locomotion.FLOATER) {
            // Floaters drift level, bobbing gently
            return Math.max(ground, seaLevel) + (s.bodyHeight + (float) Math.sin(time * 0.7f + phase(c.home)) * 2.5f) * c.sizeScale - ground;
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
