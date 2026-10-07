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
 * The world's creatures: generates a handful of bug species, populates the land around the
 * viewer with individuals of whichever species live there, walks them about, and draws them.
 *
 * Individuals belong to chunks and are recreated identically whenever their chunk comes
 * back into range, so the same place always has the same creatures.
 */
public class OrganismManager {

    private static final int SPECIES_COUNT = 8;
    // Creatures are kept within this many chunks of the viewer and drawn out to DRAW_DISTANCE
    private static final int SPAWN_RADIUS_CHUNKS = 7;
    private static final int KEEP_RADIUS_CHUNKS = 9;
    private static final float DRAW_DISTANCE = 900f;
    // Expected individuals per chunk where a species is at its most abundant
    // Expected creatures per chunk in total, however many species share the ground; each
    // species takes its share by how well suited the place is to it and how common it is
    private static final float PEAK_PER_CHUNK = 0.05f;
    private static final float ROAM_RADIUS = 90f;

    /** One living creature. */
    private static final class Creature {
        OrganismSpecies species;
        int morph;
        float sizeScale, legScale;
        float[] baseColour, accentColour, bellyColour, limbColour;
        float homeX, homeZ;
        float x, z, heading;
        float targetX, targetZ;
        float pause;
        // Off somewhere further, steadily and a little quicker, rather than grazing about
        boolean travelling;
        float walking;
        float gaitPhase;
        Random rand;
        // Finding a way round things: the heading chosen, when to look again, and progress
        // over the last few seconds to tell when it is stuck
        float steerHeading = Float.NaN, steerTimer;
        // A hop over a rock in the way: how far through it (seconds, 0 when not hopping) and how high
        float hopTime, hopHeight;
        // Drawn standing level (for a picture), not leaning with the slope it's on
        boolean level;
        // Points round its body as last drawn (see forEachOutline), and which drawing that was
        float[] outline;
        int outlineDrawing;
        float progressTimer, progressX, progressZ, progressExpected;
    }

    private Collision collision;
    private static final float STUCK_SECONDS = 2.0f;

    /** Shares the world's solid things, so creatures walk round them and not through. */
    public void setCollision(Collision collision) {
        this.collision = collision;
    }

    /** How much room a creature takes up on the ground. */
    private static float bodyRadius(Creature c) {
        return Math.max(1.0f, Math.min(9f, 0.35f * c.species.reachRadius() * c.sizeScale * c.legScale));
    }

    private final List<OrganismSpecies> species = new ArrayList<>();
    private final Map<Long, List<Creature>> creaturesByChunk = new HashMap<>();
    private final List<Creature> showcase = new ArrayList<>();
    private final long seed;
    private final float chunkSize;
    private final float seaLevel;
    private final PerlinNoise terrainNoise;
    private final float[] bones = new float[OrganismSpecies.MAX_BONES * 16];
    private Shader shader;
    private int lastCentreX = Integer.MIN_VALUE, lastCentreZ = Integer.MIN_VALUE;
    // Creatures keep to the countryside: built-up places would have them walking through walls
    private UrbannessLookup urbanness = (x, z) -> 0f;

    @FunctionalInterface
    public interface UrbannessLookup {
        float at(float worldX, float worldZ);
    }

    public void setUrbanness(UrbannessLookup lookup) {
        this.urbanness = lookup;
    }

    private boolean isWild(float x, float z) {
        return urbanness.at(x, z) < 0.12f;
    }

    // Whether a point is on (or within so far of) a road: creatures neither start nor stop on
    // one, where the crash barriers along highways would hem them in (they may cross)
    @FunctionalInterface
    public interface RoadLookup {
        boolean near(float worldX, float worldZ, float clearance);
    }

    private RoadLookup roads = (x, z, clearance) -> false;

    public void setRoads(RoadLookup lookup) {
        this.roads = lookup;
    }

    /** Somewhere a walking creature can be: dry land in the wild, off the roads. */
    private boolean walkable(float x, float z) {
        return TerrainMesh.getLayeredHeight(x, z, terrainNoise) > seaLevel + 1f && isWild(x, z) && !roads.near(x, z, 10f);
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
        java.awt.image.BufferedImage view = view(gl, i, 384, (float) Math.toRadians(-40), 0f, 0f);
        return view == null ? null : Offscreen.cropTogether(new java.awt.image.BufferedImage[] { view }, size)[0];
    }

    /**
     * An individual of species i, standing at (x, z) and turned to heading, seen from
     * three-quarters on in a wide view (render pixels square, clear round it): to be cut down to
     * the creature with Offscreen.cropTogether. Drawn where the viewer is keeps the planet's
     * curve out of it.
     */
    public java.awt.image.BufferedImage view(GL3 gl, int i, int render, float heading, float x, float z) {
        if (shader == null || i < 0 || i >= species.size()) return null;
        OrganismSpecies s = species.get(i);
        Creature c = create(s, x, z, new Random(seed * 131L + i));
        c.heading = heading;
        c.walking = 0f;
        c.gaitPhase = 0.25f;
        c.hopTime = 0f;
        c.level = true;
        float ground = TerrainMesh.getLayeredHeight(x, z, terrainNoise);
        float reach = s.reachRadius() * c.sizeScale * c.legScale;
        // (where draw puts it, at time 0)
        float centreY = s.locomotion == OrganismSpecies.Locomotion.FLYER ? Math.max(ground, seaLevel) + s.bodyHeight
                : s.locomotion == OrganismSpecies.Locomotion.FLOATER ? Math.max(ground, seaLevel) + (s.bodyHeight + (float) Math.sin(c.homeX) * 2.5f) * c.sizeScale
                : ground + s.bodyHeight * c.legScale * c.sizeScale * 0.7f;
        Vector3 centre = new Vector3(x, centreY, z);
        float distance = reach * 5f + 25f;
        Vector3 eye = new Vector3(x + distance * 0.62f, centreY + distance * 0.42f, z + distance * 0.66f);
        Matrix4 view = com.xenoguesser.math.Matrix4Transform.lookAt(eye, centre, new Vector3(0f, 1f, 0f));
        Matrix4 projection = com.xenoguesser.math.Matrix4Transform.perspective(45f, 1f, distance * 0.02f, distance * 4f);
        Matrix4 viewProjection = Matrix4.multiply(projection, view);
        Frustum frustum = new Frustum();
        frustum.update(viewProjection);
        Vector3 sun = new Vector3(eye.x + distance * 3f, eye.y + distance * 6f, eye.z - distance * 1f);
        return Offscreen.capture(gl, render, () -> renderCreatures(gl, viewProjection, frustum, eye, sun, new float[] { 1f, 0.97f, 0.92f },
                new Vector3(0.55f, 0.55f, 0.6f), new Matrix4(1), null, 0f, List.of(c)));
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
        creaturesByChunk.clear();
        showcase.clear();
        lastCentreX = lastCentreZ = Integer.MIN_VALUE;
    }

    /**
     * Developer aid: a spot in the heart of some species' range, as {x, z}, and a creature of
     * that species placed just in front of it.
     */
    public float[] showcaseViewpoint(Random rand, float regionWidth) {
        for (int attempt = 0; attempt < 40000; attempt++) {
            OrganismSpecies s = species.get(attempt % species.size());
            float x = (rand.nextFloat() - 0.5f) * regionWidth;
            float z = (rand.nextFloat() - 0.5f) * regionWidth;
            if (TerrainMesh.getLayeredHeight(x, z, terrainNoise) <= seaLevel + 2f || s.presenceAt(x, z, chunkSize) < 0.5f || !isWild(x, z)) continue;
            float heading = rand.nextFloat() * (float) Math.PI * 2f;
            float lookX = (float) Math.sin(heading), lookZ = (float) Math.cos(heading);
            float distance = 30f + s.halfLength() * 3f;
            float cx = x + lookX * distance, cz = z + lookZ * distance;
            if (TerrainMesh.getLayeredHeight(cx, cz, terrainNoise) <= seaLevel + 2f) continue;
            Creature creature = create(s, cx, cz, new Random(rand.nextLong()));
            creature.heading = heading + (float) Math.PI * 0.6f;
            showcase.add(creature);
            return new float[] { x, z, lookX, lookZ };
        }
        return null;
    }

    /**
     * Developer aid: one of every species in an arc in front of a flat spot on dry land, as
     * {x, z, lookX, lookZ} for the viewer, to compare them side by side.
     */
    public float[] zooViewpoint(Random rand, float regionWidth) {
        for (int attempt = 0; attempt < 20000; attempt++) {
            float x = (rand.nextFloat() - 0.5f) * regionWidth * 0.8f;
            float z = (rand.nextFloat() - 0.5f) * regionWidth * 0.8f;
            if (!isWild(x, z)) continue;
            float lowest = Float.MAX_VALUE, highest = -Float.MAX_VALUE;
            for (int i = 0; i < 25; i++) {
                float h = TerrainMesh.getLayeredHeight(x + (i % 5 - 2) * 60f, z + (i / 5) * 60f, terrainNoise);
                lowest = Math.min(lowest, h);
                highest = Math.max(highest, h);
            }
            if (lowest <= seaLevel + 2f || highest - lowest > 25f) continue;
            for (int i = 0; i < species.size(); i++) {
                float angle = (i - (species.size() - 1) * 0.5f) * 0.2f;
                float distance = 110f + (i % 2) * 60f;
                Creature c = create(species.get(i), x + (float) Math.sin(angle) * distance, z + (float) Math.cos(angle) * distance,
                        new Random(rand.nextLong()));
                c.heading = (float) Math.PI * 0.5f;
                c.pause = 1000f;
                if (c.species.locomotion == OrganismSpecies.Locomotion.FLYER) {
                    // Flyers wheel overhead, starting ahead of the viewer
                    c.homeX = x + c.species.flightRadius;
                    c.homeZ = z + c.species.flightRadius * 0.5f;
                    c.pause = (float) Math.PI + 0.15f * i;
                }
                showcase.add(c);
            }
            return new float[] { x, z, 0f, 1f };
        }
        return null;
    }

    /** Brings creatures into being near the viewer, retires distant ones, and moves them all on. */
    public void update(float dt, float viewerX, float viewerZ) {
        int centreX = (int) Math.floor(viewerX / chunkSize), centreZ = (int) Math.floor(viewerZ / chunkSize);
        if (centreX != lastCentreX || centreZ != lastCentreZ) {
            lastCentreX = centreX;
            lastCentreZ = centreZ;
            for (int dz = -SPAWN_RADIUS_CHUNKS; dz <= SPAWN_RADIUS_CHUNKS; dz++) {
                for (int dx = -SPAWN_RADIUS_CHUNKS; dx <= SPAWN_RADIUS_CHUNKS; dx++) {
                    if (dx * dx + dz * dz > SPAWN_RADIUS_CHUNKS * SPAWN_RADIUS_CHUNKS) continue;
                    long key = chunkKey(centreX + dx, centreZ + dz);
                    if (!creaturesByChunk.containsKey(key)) {
                        creaturesByChunk.put(key, populate(centreX + dx, centreZ + dz));
                    }
                }
            }
            Iterator<Map.Entry<Long, List<Creature>>> it = creaturesByChunk.entrySet().iterator();
            while (it.hasNext()) {
                long key = it.next().getKey();
                int cx = (int) (key >> 32), cz = (int) key;
                int ddx = cx - centreX, ddz = cz - centreZ;
                if (ddx * ddx + ddz * ddz > KEEP_RADIUS_CHUNKS * KEEP_RADIUS_CHUNKS) it.remove();
            }
        }
        float step = Math.min(dt, 0.1f);
        for (List<Creature> list : creaturesByChunk.values()) {
            for (Creature c : list) move(c, step);
        }
        for (Creature c : showcase) move(c, step);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * How many of species i would be expected in the chunk round a point were it wild (as
     * populate works it out, sharing the ground with the other species there); towns, which
     * none of them live in, are left out (populate keeps them out of towns itself).
     */
    public float expectedPerChunk(int i, float x, float z) {
        float total = 0f, mine = 0f;
        for (int k = 0; k < species.size(); k++) {
            float p = species.get(k).presenceAt(x, z, chunkSize);
            total += p;
            if (k == i) mine = p;
        }
        return mine / Math.max(1f, total) * species.get(i).rarity * PEAK_PER_CHUNK;
    }

    /** The most of species i a chunk can expect (where it's at its best, alone). */
    public float peakPerChunk(int i) {
        return species.get(i).rarity * PEAK_PER_CHUNK;
    }

    private List<Creature> populate(int cx, int cz) {
        List<Creature> list = new ArrayList<>();
        Random rand = new Random(seed ^ roundSalt ^ (Planet.wrapChunk(cx, chunkSize) * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL) ^ 0x0B6L);
        float centreX = (cx + 0.5f) * chunkSize, centreZ = (cz + 0.5f) * chunkSize;
        float[] presences = new float[species.size()];
        float total = 0f;
        for (int i = 0; i < species.size(); i++) {
            presences[i] = species.get(i).presenceAt(centreX, centreZ, chunkSize) * (isWild(centreX, centreZ) ? 1f : 0f);
            total += presences[i];
        }
        for (int k = 0; k < species.size(); k++) {
            OrganismSpecies s = species.get(k);
            float presence = presences[k];
            float expected = presence / Math.max(1f, total) * s.rarity * PEAK_PER_CHUNK;
            int count = 0;
            float roll = rand.nextFloat();
            while (expected > 0f && roll < expected) {
                count++;
                expected -= 1f;
            }
            for (int i = 0; i < count; i++) {
                float x = cx * chunkSize + rand.nextFloat() * chunkSize;
                float z = cz * chunkSize + rand.nextFloat() * chunkSize;
                boolean flies = s.locomotion == OrganismSpecies.Locomotion.FLYER;
                if (!flies && !walkable(x, z)) continue;
                list.add(create(s, x, z, new Random(rand.nextLong())));
            }
        }
        return list;
    }

    private Creature create(OrganismSpecies s, float x, float z, Random rand) {
        Creature c = new Creature();
        c.species = s;
        c.rand = rand;
        float[] traits = s.traitsAt(x, z, rand);
        c.sizeScale = traits[0];
        c.legScale = traits[1];
        c.morph = (int) traits[3];
        c.baseColour = shiftHue(s.baseColour, traits[2]);
        c.accentColour = shiftHue(s.accentColour, traits[2]);
        c.bellyColour = shiftHue(s.bellyColour, traits[2] * 0.5f);
        c.limbColour = shiftHue(s.limbColour, traits[2]);
        c.homeX = c.x = x;
        c.homeZ = c.z = z;
        c.heading = rand.nextFloat() * (float) Math.PI * 2f;
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
        float fromHome = (float) Math.hypot(c.x - c.homeX, c.z - c.homeZ);
        float towardsHome = (float) Math.atan2(c.homeX - c.x, c.homeZ - c.z);
        for (int attempt = 0; attempt < 10; attempt++) {
            float angle, r;
            if (c.travelling) {
                float base = fromHome > ROAM_RADIUS * 3f ? towardsHome : c.heading;
                angle = base + (c.rand.nextFloat() - 0.5f) * 1.6f;
                r = ROAM_RADIUS * (1.2f + 1.6f * c.rand.nextFloat());
            } else {
                angle = c.rand.nextFloat() * (float) Math.PI * 2f;
                r = 10f + 25f * c.rand.nextFloat();
            }
            float tx = c.x + r * (float) Math.sin(angle), tz = c.z + r * (float) Math.cos(angle);
            if (walkable(tx, tz)) {
                c.targetX = tx;
                c.targetZ = tz;
                return;
            }
        }
        c.travelling = false;
        c.targetX = c.homeX;
        c.targetZ = c.homeZ;
    }

    /** A new goal roughly behind it, for when the way ahead is blocked. */
    private void pickTargetAway(Creature c) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = c.heading + Math.PI + (c.rand.nextDouble() - 0.5) * 2.2;
            float r = ROAM_RADIUS * (0.3f + 0.4f * c.rand.nextFloat());
            float tx = c.x + r * (float) Math.sin(angle), tz = c.z + r * (float) Math.cos(angle);
            if (TerrainMesh.getLayeredHeight(tx, tz, terrainNoise) > seaLevel + 1f && !roads.near(tx, tz, 10f)) {
                c.targetX = tx;
                c.targetZ = tz;
                c.travelling = true;
                return;
            }
        }
        pickTarget(c);
    }

    /** Walks towards its current goal, turning as it goes, and stops for a while on arrival. */
    private void move(Creature c, float dt) {
        if (c.species.locomotion == OrganismSpecies.Locomotion.FLYER) {
            // Flyers wheel round their home in wide circles, over land or water
            float radius = c.species.flightRadius;
            float angular = c.species.walkSpeed * c.sizeScale / radius;
            c.pause += dt * angular;                      // the angle round the circle
            c.x = c.homeX + radius * (float) Math.cos(c.pause);
            c.z = c.homeZ + radius * (float) Math.sin(c.pause);
            c.heading = (float) Math.atan2(-Math.sin(c.pause), Math.cos(c.pause));
            c.walking = 1f;
            c.gaitPhase = (c.gaitPhase + dt * c.species.stepsPerSecond) % 1f;
            return;
        }
        float speed = c.species.walkSpeed * c.sizeScale * c.legScale * (c.travelling ? 1.35f : 1f);
        boolean resting = c.pause > 0f;
        if (resting) {
            c.pause -= dt;
        } else {
            float dx = c.targetX - c.x, dz = c.targetZ - c.z;
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance < 4f) {
                // A short stop after a trip; a longer one grazing
                c.pause = c.travelling ? 0.3f + c.rand.nextFloat() * 1.7f : 2f + c.rand.nextFloat() * 6f;
                pickTarget(c);
            } else {
                float desired = (float) Math.atan2(dx, dz);
                if (collision != null) {
                    // Look a little way ahead every so often and bear round anything in the way
                    float r = bodyRadius(c);
                    c.steerTimer -= dt;
                    if (c.steerTimer <= 0f) {
                        c.steerTimer = 0.25f + c.rand.nextFloat() * 0.1f;
                        // Looking ahead from its nose, so a long body doesn't lead its head into things
                        float nose = noseReach(c);
                        c.steerHeading = collision.steer(c.x + (float) Math.sin(c.heading) * nose, c.z + (float) Math.cos(c.heading) * nose,
                                endRadius(c), desired, Math.max(6f, r * 2.5f), c);
                        if (Float.isNaN(c.steerHeading)) {
                            // Hemmed in on every side: stop, then try somewhere else
                            c.pause = 0.5f + c.rand.nextFloat();
                            pickTargetAway(c);
                        }
                    }
                    if (!Float.isNaN(c.steerHeading)) desired = c.steerHeading;
                }
                float turn = desired - c.heading;
                turn = (float) Math.atan2(Math.sin(turn), Math.cos(turn));
                float maxTurn = 1.2f * dt;
                c.heading += Math.max(-maxTurn, Math.min(maxTurn, turn));
                // Slow down for sharp turns so it doesn't skid round them
                float pace = c.walking * (1f - 0.6f * Math.min(1f, Math.abs(turn)));
                float nx = c.x + (float) Math.sin(c.heading) * speed * pace * dt;
                float nz = c.z + (float) Math.cos(c.heading) * speed * pace * dt;
                if (TerrainMesh.getLayeredHeight(nx, nz, terrainNoise) > seaLevel + 0.5f) {
                    if (collision != null) {
                        // Small rocks it simply steps over; walking into a bigger one, it hops
                        // over it, carrying on forwards (a wall or anything too tall still stops it)
                        float legs = legHeight(c);
                        if (c.hopTime <= 0f && collision.blocked(nx, nz, bodyRadius(c), c, legs * STEP_OVER)
                                && !collision.blocked(nx, nz, bodyRadius(c), c, legs * HOP_OVER)) {
                            c.hopTime = HOP_SECONDS;
                            c.hopHeight = legs * HOP_OVER;
                        }
                        float[] free = resolveBody(c, nx, nz, c.hopTime > 0f ? c.hopHeight : legs * STEP_OVER);
                        nx = free[0];
                        nz = free[1];
                    }
                    c.progressExpected += speed * pace * dt;
                    c.x = nx;
                    c.z = nz;
                } else {
                    pickTarget(c);
                }
                // Stuck: it has been trying to walk but got almost nowhere, so it gives up on
                // this way and heads off somewhere else
                c.progressTimer += dt;
                if (c.progressTimer > STUCK_SECONDS) {
                    float moved = (float) Math.hypot(c.x - c.progressX, c.z - c.progressZ);
                    if (c.progressExpected > 4f && moved < c.progressExpected * 0.3f) {
                        pickTargetAway(c);
                        c.steerTimer = 0f;
                    }
                    c.progressTimer = 0f;
                    c.progressExpected = 0f;
                    c.progressX = c.x;
                    c.progressZ = c.z;
                }
            }
        }
        if (c.hopTime > 0f) c.hopTime = Math.max(0f, c.hopTime - dt);
        float targetWalking = resting ? 0f : 1f;
        c.walking += (targetWalking - c.walking) * Math.min(1f, dt * 3f);
        c.gaitPhase = (c.gaitPhase + dt * c.species.stepsPerSecond * c.walking) % 1f;
        if (collision != null && c.species.locomotion != OrganismSpecies.Locomotion.FLYER) {
            // The whole length is solid to others: middle, nose and tail
            float sin = (float) Math.sin(c.heading), cos = (float) Math.cos(c.heading);
            collision.addBody(c, c.x, c.z, bodyRadius(c));
            collision.addBody(c, c.x + sin * noseReach(c), c.z + cos * noseReach(c), endRadius(c));
            collision.addBody(c, c.x - sin * tailReach(c), c.z - cos * tailReach(c), endRadius(c));
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

    /** Moves the whole body, middle, nose and tail, out of anything it has walked into (anything lower than clearance it passes over). */
    private float[] resolveBody(Creature c, float x, float z, float clearance) {
        float sin = (float) Math.sin(c.heading), cos = (float) Math.cos(c.heading);
        float nose = noseReach(c), tail = tailReach(c), end = endRadius(c);
        for (int pass = 0; pass < 2; pass++) {
            float[] middle = collision.resolve(x, z, bodyRadius(c), c, clearance);
            x = middle[0];
            z = middle[1];
            float[] head = collision.resolve(x + sin * nose, z + cos * nose, end, c, clearance);
            x = head[0] - sin * nose;
            z = head[1] - cos * nose;
            float[] back = collision.resolve(x - sin * tail, z - cos * tail, end, c, clearance);
            x = back[0] + sin * tail;
            z = back[1] + cos * tail;
        }
        return new float[] { x, z };
    }

    public void render(GL3 gl, Matrix4 viewProjection, Frustum frustum, Vector3 viewPos, Vector3 sunPos,
                       float[] sunColour, Vector3 ambient, Matrix4 skyRotation, Texture sky, float time) {
        List<Creature> all = new ArrayList<>(showcase);
        for (List<Creature> list : creaturesByChunk.values()) all.addAll(list);
        renderCreatures(gl, viewProjection, frustum, viewPos, sunPos, sunColour, ambient, skyRotation, sky, time, all);
    }

    private void renderCreatures(GL3 gl, Matrix4 viewProjection, Frustum frustum, Vector3 viewPos, Vector3 sunPos,
                                 float[] sunColour, Vector3 ambient, Matrix4 skyRotation, Texture sky, float time, List<Creature> creatures) {
        if (shader == null) return;
        shader.use(gl);
        shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        shader.setVec3(gl, "viewPos", viewPos);
        shader.setVec3(gl, "sunPos", sunPos);
        shader.setVec3(gl, "sunColour", new Vector3(sunColour[0], sunColour[1], sunColour[2]));
        shader.setVec3(gl, "ambientLight", ambient);
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            shader.setInt(gl, "skyTexture", 2);
        }
        int bonesLocation = gl.glGetUniformLocation(shader.getID(), "bones");
        // Creatures keep to dry land, so nothing of theirs is under water
        shader.setFloat(gl, "waterLevel", -1e9f);
        shader.setVec3(gl, "waterTint", new Vector3(0f, 0f, 0f));
        // Thin parts are open lathes seen from all sides
        gl.glDisable(GL.GL_CULL_FACE);
        drawing++;
        for (Creature c : creatures) draw(gl, c, frustum, viewPos, bonesLocation, time);
        gl.glEnable(GL.GL_CULL_FACE);
    }

    /** A creature's outline as drawn: its species, where it stands, and points round its body in the world {x, y, z, ...}. */
    @FunctionalInterface
    public interface CreatureOutline {
        void at(int species, float x, float z, float[] points);
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
        java.util.List<List<Creature>> lists = new ArrayList<>(creaturesByChunk.values());
        lists.add(showcase);
        for (List<Creature> list : lists) {
            for (Creature c : list) {
                if (c.outline != null && c.outlineDrawing == drawing) visit.at(species.indexOf(c.species), c.x, c.z, c.outline);
            }
        }
    }

    private void draw(GL3 gl, Creature c, Frustum frustum, Vector3 viewPos, int bonesLocation, float time) {
        OrganismSpecies s = c.species;
        float dx = c.x - viewPos.x, dz = c.z - viewPos.z;
        if (dx * dx + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE) return;
        float ground = TerrainMesh.getLayeredHeight(c.x, c.z, terrainNoise);
        float radius = s.reachRadius() * c.sizeScale * c.legScale;
        // (a flyer is tested where it flies, not on the ground below)
        float middle = s.locomotion == OrganismSpecies.Locomotion.FLYER ? Math.max(ground, seaLevel) + s.bodyHeight : ground + radius * 0.3f;
        if (!frustum.intersectsSphere(c.x, middle, c.z, radius + (s.locomotion == OrganismSpecies.Locomotion.FLYER ? 10f : 0f))) return;

        // Level the body along the slope beneath it
        float sin = (float) Math.sin(c.heading), cos = (float) Math.cos(c.heading);
        float front = s.halfLength() * c.sizeScale, back = s.tailLength() * c.sizeScale;
        float frontGround = TerrainMesh.getLayeredHeight(c.x + sin * front, c.z + cos * front, terrainNoise);
        float backGround = TerrainMesh.getLayeredHeight(c.x - sin * back, c.z - cos * back, terrainNoise);
        if (c.level) frontGround = backGround = ground;
        float pitch = (float) Math.atan2(frontGround - backGround, front + back);
        float height;
        if (s.locomotion == OrganismSpecies.Locomotion.FLYER) {
            // Banked into the turn, holding its height above the land or sea below
            pitch = c.level ? 0f : 0.05f * (float) Math.sin(c.gaitPhase * Math.PI * 2);
            height = Math.max(ground, seaLevel) + s.bodyHeight + (float) Math.sin(time * 0.4f + c.homeZ) * 8f;
        } else if (s.locomotion == OrganismSpecies.Locomotion.FLOATER) {
            // Floaters drift level, bobbing gently
            pitch = 0f;
            height = Math.max(ground, seaLevel) + (s.bodyHeight + (float) Math.sin(time * 0.7f + c.homeX) * 2.5f) * c.sizeScale;
        } else {
            float bob = s.locomotion == OrganismSpecies.Locomotion.WALKER
                    ? (float) Math.abs(Math.sin(c.gaitPhase * Math.PI * 4)) * 0.03f * s.bodyHeight * c.walking : 0f;
            height = Math.max(ground, (frontGround + backGround) * 0.5f) + (s.bodyHeight * c.legScale + bob) * c.sizeScale + hopLift(c);
        }
        float[] body = Affine.multiply(Affine.translation(c.x, height, c.z),
                Affine.multiply(Affine.rotationY(c.heading),
                        Affine.multiply(Affine.rotationX(-pitch), Affine.scale(c.sizeScale, c.sizeScale, c.sizeScale))));

        s.pose(body, c.legScale, c.gaitPhase, c.walking, time + c.homeX * 0.01f, bones);
        if (keepOutlines) {
            float[] local = s.outline(c.morph);
            if (c.outline == null || c.outline.length != local.length / 4 * 3) c.outline = new float[local.length / 4 * 3];
            for (int k = 0, o = 0; k < local.length; k += 4, o += 3) {
                int m = (int) local[k + 3] * 16;
                float x = local[k], y = local[k + 1], z = local[k + 2];
                c.outline[o] = bones[m] * x + bones[m + 4] * y + bones[m + 8] * z + bones[m + 12];
                c.outline[o + 1] = bones[m + 1] * x + bones[m + 5] * y + bones[m + 9] * z + bones[m + 13];
                c.outline[o + 2] = bones[m + 2] * x + bones[m + 6] * y + bones[m + 10] * z + bones[m + 14];
            }
            c.outlineDrawing = drawing;
        }
        gl.glUniformMatrix4fv(bonesLocation, s.boneCount, false, bones, 0);
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

    private static Vector3 vec(float[] c) {
        return new Vector3(c[0], c[1], c[2]);
    }
}
