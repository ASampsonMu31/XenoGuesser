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
 * The world's creatures: generates species, populates land around the viewer, simulates their
 * motion, and draws them. Native-world motion and rendering use sphere positions; the legacy
 * chart renderer and regional habitat sampling remain available while that migration proceeds.
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
        SpherePosition sphereHome, spherePosition, sphereTarget;
        SpherePosition sphereProgressPosition;
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
    private final Map<Long, List<Creature>> creaturesByGeodesicCell = new HashMap<>();
    private final Map<Long, Float> nativeWaterDistanceByCell = new HashMap<>();
    private final GeodesicChunkGrid populationGrid = new GeodesicChunkGrid(7);
    private long lastNativeCentreCell = Long.MIN_VALUE;
    private List<GeodesicSettlementMesh.SettlementArea> nativeSettlementAreas = List.of();
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

    public void setGeodesicSettlements(List<GeodesicSettlementMesh.SettlementArea> settlements) {
        nativeSettlementAreas = List.copyOf(settlements);
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
                new Vector3(0.55f, 0.55f, 0.6f), new Matrix4(1), null, 0f, List.of(c), false, null));
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
        creaturesByGeodesicCell.clear();
        nativeWaterDistanceByCell.clear();
        showcase.clear();
        lastCentreX = lastCentreZ = Integer.MIN_VALUE;
        lastNativeCentreCell = Long.MIN_VALUE;
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
        updatePopulation(viewerX, viewerZ);
        float step = Math.min(dt, 0.1f);
        for (List<Creature> list : creaturesByChunk.values()) {
            for (Creature c : list) move(c, step);
        }
        for (Creature c : showcase) move(c, step);
    }

    /** Updates motion in true surface distances; legacy regional habitat and spawn selection remain chart-based. */
    public void updateSphereNative(float dt, SpherePosition viewer, GeodesicCollision geodesicCollision) {
        updateGeodesicPopulation(viewer, geodesicCollision);
        float step = Math.min(dt, 0.1f);
        for (List<Creature> list : creaturesByGeodesicCell.values()) {
            for (Creature creature : list) {
                initializeSpherePosition(creature);
                moveSphereNative(creature, step, geodesicCollision);
            }
        }
        for (Creature creature : showcase) {
            initializeSpherePosition(creature);
            moveSphereNative(creature, step, geodesicCollision);
        }
    }

    private void updateGeodesicPopulation(SpherePosition viewer, GeodesicCollision geodesicCollision) {
        GeodesicChunkGrid.Cell centre = populationGrid.cellAt(viewer);
        if (centre.id() == lastNativeCentreCell) return;
        lastNativeCentreCell = centre.id();

        double spawnRadius = SPAWN_RADIUS_CHUNKS * (double) chunkSize / Planet.radius();
        double keepRadius = KEEP_RADIUS_CHUNKS * (double) chunkSize / Planet.radius();
        List<GeodesicChunkGrid.Cell> spawnCells = populationGrid.cellsWithin(centre.centre(), spawnRadius);
        for (GeodesicChunkGrid.Cell cell : spawnCells) {
            if (!creaturesByGeodesicCell.containsKey(cell.id())) {
                creaturesByGeodesicCell.put(cell.id(), populateGeodesicCell(cell, geodesicCollision));
            }
        }
        creaturesByGeodesicCell.entrySet().removeIf(entry -> {
            GeodesicChunkGrid.Cell cell = populationGrid.cell(entry.getKey());
            return centre.centre().angleTo(cell.centre()) > keepRadius + cell.angularRadius();
        });
    }

    private List<Creature> populateGeodesicCell(GeodesicChunkGrid.Cell cell,
            GeodesicCollision geodesicCollision) {
        List<Creature> residents = new ArrayList<>();
        SpherePosition cellCentre = spherePosition(cell.centre(), 0.0);
        float waterDistance = nativeWaterDistance(cell, cellCentre);
        float elevation = TerrainMesh.getLayeredHeight(cellCentre, terrainNoise);
        float[] presence = new float[species.size()];
        float totalPresence = 0f;
        for (int i = 0; i < species.size(); i++) {
            presence[i] = species.get(i).presenceAt(cellCentre, elevation, waterDistance);
            totalPresence += presence[i];
        }
        double cellArea = 4.0 * Math.PI * Planet.radius() * Planet.radius() / populationGrid.cellCount();
        double referenceArea = (double) chunkSize * chunkSize;
        Random random = new Random(seed ^ roundSalt ^ (cell.id() * 0x9E3779B97F4A7C15L) ^ 0x0B6L);
        for (int i = 0; i < species.size(); i++) {
            OrganismSpecies kind = species.get(i);
            if (presence[i] <= 0f || totalPresence <= 0f) continue;
            double expected = presence[i] / totalPresence * kind.rarity * PEAK_PER_CHUNK
                    * cellArea / referenceArea;
            int count = (int) expected;
            if (random.nextDouble() < expected - count) count++;
            for (int n = 0; n < count; n++) {
                SpherePosition point = randomPointInCell(cell, random);
                float height = TerrainMesh.getLayeredHeight(point, terrainNoise);
                if (height <= seaLevel + 1f) continue;
                if (nearNativeSettlement(point) || (geodesicCollision != null
                        && geodesicCollision.nearRoute(point, 10.0))) continue;
                Creature creature = createNative(kind, point, new Random(random.nextLong()));
                creature.spherePosition = terrainPosition(point);
                creature.sphereHome = creature.spherePosition;
                creature.sphereProgressPosition = creature.spherePosition;
                pickSphereTarget(creature);
                residents.add(creature);
            }
        }
        return residents;
    }

    private float nativeWaterDistance(GeodesicChunkGrid.Cell cell, SpherePosition centre) {
        Float cached = nativeWaterDistanceByCell.get(cell.id());
        if (cached != null) return cached;
        double nearest = 40000.0;
        for (int bearingIndex = 0; bearingIndex < 8; bearingIndex++) {
            double bearing = bearingIndex * Math.PI / 4.0;
            for (double distance = 3000.0; distance <= 40000.0; distance += 3000.0) {
                SpherePosition sample = Planet.moveOnSurface(centre, Math.sin(bearing),
                        Math.cos(bearing), distance);
                if (TerrainMesh.getLayeredHeight(sample, terrainNoise) <= seaLevel) {
                    nearest = Math.min(nearest, distance);
                    break;
                }
            }
        }
        float value = (float) nearest;
        nativeWaterDistanceByCell.put(cell.id(), value);
        return value;
    }

    private boolean nearNativeSettlement(SpherePosition point) {
        for (GeodesicSettlementMesh.SettlementArea settlement : nativeSettlementAreas) {
            if (Planet.surfaceDistance(point, settlement.centre()) < settlement.radius()) return true;
        }
        return false;
    }

    private static SpherePosition randomPointInCell(GeodesicChunkGrid.Cell cell, Random random) {
        double a = random.nextDouble(), b = random.nextDouble();
        if (a + b > 1.0) {
            a = 1.0 - a;
            b = 1.0 - b;
        }
        double c = 1.0 - a - b;
        GeodesicChunkGrid.Direction direction = new GeodesicChunkGrid.Direction(
                cell.a().x() * a + cell.b().x() * b + cell.c().x() * c,
                cell.a().y() * a + cell.b().y() * b + cell.c().y() * c,
                cell.a().z() * a + cell.b().z() * b + cell.c().z() * c).normalized();
        return spherePosition(direction, 0.0);
    }

    private static SpherePosition spherePosition(GeodesicChunkGrid.Direction direction, double altitude) {
        double radius = Planet.radius() + altitude;
        return new SpherePosition(direction.x() * radius, direction.y() * radius, direction.z() * radius);
    }

    private void updatePopulation(float viewerX, float viewerZ) {
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
    }

    private void initializeSpherePosition(Creature creature) {
        if (creature.spherePosition != null) return;
        creature.sphereHome = terrainPosition(Planet.spherePosition(creature.homeX, creature.homeZ, 0.0));
        creature.spherePosition = terrainPosition(Planet.spherePosition(creature.x, creature.z, 0.0));
        creature.sphereTarget = creature.spherePosition;
        creature.sphereProgressPosition = creature.spherePosition;
        pickSphereTarget(creature);
    }

    private SpherePosition terrainPosition(SpherePosition position) {
        double length = position.length();
        double height = TerrainMesh.getLayeredHeight(position, terrainNoise);
        double radius = Planet.radius() + height;
        return new SpherePosition(position.x() * radius / length, position.y() * radius / length,
                position.z() * radius / length);
    }

    private boolean sphereWalkable(SpherePosition position, Creature creature,
            GeodesicCollision geodesicCollision) {
        return sphereTerrainWalkable(position, creature)
                && (geodesicCollision == null
                        || !geodesicCollision.blocked(position, bodyRadius(creature),
                                legHeight(creature) * STEP_OVER));
    }

    private boolean sphereTerrainWalkable(SpherePosition position, Creature creature) {
        float height = TerrainMesh.getLayeredHeight(position, terrainNoise);
        if (creature.species.locomotion == OrganismSpecies.Locomotion.FLOATER) {
            if (height < seaLevel - 20f) return false;
        } else if (height <= seaLevel + 1f) {
            return false;
        }
        return true;
    }

    private void pickSphereTarget(Creature creature) {
        if (creature.species.locomotion == OrganismSpecies.Locomotion.FLYER) {
            creature.sphereTarget = creature.spherePosition;
            return;
        }
        double distanceFromHome = Planet.surfaceDistance(creature.spherePosition, creature.sphereHome);
        double homeBearing = sphereBearing(creature.spherePosition, creature.sphereHome);
        creature.travelling = creature.rand.nextFloat() < 0.65f;
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle, distance;
            if (creature.travelling) {
                double base = distanceFromHome > ROAM_RADIUS * 3f ? homeBearing : creature.heading;
                angle = base + (creature.rand.nextDouble() - 0.5) * 1.6;
                distance = ROAM_RADIUS * (1.2 + 1.6 * creature.rand.nextDouble());
            } else {
                angle = creature.rand.nextDouble() * Math.PI * 2.0;
                distance = 10.0 + 25.0 * creature.rand.nextDouble();
            }
            SpherePosition target = Planet.moveOnSurface(creature.spherePosition,
                    Math.sin(angle), Math.cos(angle), distance);
            target = terrainPosition(target);
            if (sphereWalkable(target, creature, null)) {
                creature.sphereTarget = target;
                return;
            }
        }
        creature.travelling = false;
        creature.sphereTarget = creature.sphereHome;
    }

    private void moveSphereNative(Creature creature, float dt, GeodesicCollision geodesicCollision) {
        if (creature.species.locomotion == OrganismSpecies.Locomotion.FLYER) {
            double angular = creature.species.walkSpeed * creature.sizeScale
                    / Math.max(1.0, creature.species.flightRadius);
            creature.pause += dt * angular;
            double radius = creature.species.flightRadius;
            SpherePosition orbit = Planet.moveOnSurface(creature.sphereHome,
                    Math.sin(creature.pause), Math.cos(creature.pause), radius);
            creature.spherePosition = terrainPosition(orbit);
            creature.heading = (float) (creature.pause + Math.PI * 0.5);
            creature.walking = 1f;
            creature.gaitPhase = (creature.gaitPhase + dt * creature.species.stepsPerSecond) % 1f;
            return;
        }

        boolean resting = creature.pause > 0f;
        if (resting) {
            creature.pause -= dt;
        } else {
            double distance = Planet.surfaceDistance(creature.spherePosition, creature.sphereTarget);
            if (distance < 4.0) {
                creature.pause = creature.travelling
                        ? 0.3f + creature.rand.nextFloat() * 1.7f
                        : 2f + creature.rand.nextFloat() * 6f;
                pickSphereTarget(creature);
            } else {
                double desired = sphereBearing(creature.spherePosition, creature.sphereTarget);
                double turn = Math.atan2(Math.sin(desired - creature.heading),
                        Math.cos(desired - creature.heading));
                double maxTurn = 1.2 * dt;
                creature.heading += Math.max(-maxTurn, Math.min(maxTurn, turn));
                double pace = creature.walking * (1.0 - 0.6 * Math.min(1.0, Math.abs(turn)));
                double speed = creature.species.walkSpeed * creature.sizeScale * creature.legScale
                        * (creature.travelling ? 1.35 : 1.0);
                SpherePosition candidate = Planet.moveOnSurface(creature.spherePosition,
                        Math.sin(creature.heading), Math.cos(creature.heading), speed * pace * dt);
                candidate = terrainPosition(candidate);
                if (!sphereTerrainWalkable(candidate, creature)) {
                    creature.pause = 0.5f + creature.rand.nextFloat();
                    pickSphereTarget(creature);
                } else {
                    if (geodesicCollision != null) {
                        candidate = geodesicCollision.resolve(candidate, bodyRadius(creature),
                                creature.hopTime > 0f ? creature.hopHeight : legHeight(creature) * STEP_OVER);
                        candidate = terrainPosition(candidate);
                    }
                    if (sphereWalkable(candidate, creature, geodesicCollision)) {
                        creature.spherePosition = candidate;
                        creature.progressExpected += (float) (speed * pace * dt);
                    } else {
                        creature.pause = 0.5f + creature.rand.nextFloat();
                        pickSphereTarget(creature);
                    }
                }
            }
            creature.progressTimer += dt;
            if (creature.progressTimer > STUCK_SECONDS) {
                double moved = Planet.surfaceDistance(creature.spherePosition, creature.sphereProgressPosition);
                if (creature.progressExpected > 4f && moved < creature.progressExpected * 0.3f) {
                    pickSphereTarget(creature);
                }
                creature.progressTimer = 0f;
                creature.progressExpected = 0f;
                creature.sphereProgressPosition = creature.spherePosition;
            }
        }

        if (creature.hopTime > 0f) creature.hopTime = Math.max(0f, creature.hopTime - dt);
        float targetWalking = resting ? 0f : 1f;
        creature.walking += (targetWalking - creature.walking) * Math.min(1f, dt * 3f);
        creature.gaitPhase = (creature.gaitPhase + dt * creature.species.stepsPerSecond
                * creature.walking) % 1f;
    }

    private static double sphereBearing(SpherePosition from, SpherePosition to) {
        double length = from.length();
        double latitude = Math.asin(from.y() / length);
        double longitude = Math.atan2(from.z(), from.x());
        double upX = Math.cos(latitude) * Math.cos(longitude);
        double upY = Math.sin(latitude);
        double upZ = Math.cos(latitude) * Math.sin(longitude);
        double eastX = -Math.sin(longitude), eastZ = Math.cos(longitude);
        double southX = Math.sin(latitude) * Math.cos(longitude);
        double southY = -Math.cos(latitude);
        double southZ = Math.sin(latitude) * Math.sin(longitude);
        double targetLength = to.length();
        double dot = (from.x() * to.x() + from.y() * to.y() + from.z() * to.z())
                / (length * targetLength);
        double tx = to.x() / targetLength - dot * upX;
        double ty = to.y() / targetLength - dot * upY;
        double tz = to.z() / targetLength - dot * upZ;
        double east = tx * eastX + tz * eastZ;
        double south = tx * southX + ty * southY + tz * southZ;
        return Math.atan2(east, south);
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
        return create(s, x, z, s.traitsAt(x, z, rand), rand);
    }

    private Creature createNative(OrganismSpecies s, SpherePosition position, Random rand) {
        return create(s, Float.NaN, Float.NaN, s.traitsAt(position, rand), rand);
    }

    private Creature create(OrganismSpecies s, float x, float z, float[] traits, Random rand) {
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
        c.homeX = c.x = x;
        c.homeZ = c.z = z;
        c.heading = rand.nextFloat() * (float) Math.PI * 2f;
        c.gaitPhase = rand.nextFloat();
        c.pause = rand.nextFloat() * 3f;
        if (Float.isFinite(x) && Float.isFinite(z)) {
            pickTarget(c);
        }
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
        renderCreatures(gl, viewProjection, frustum, viewPos, sunPos, sunColour, ambient,
                skyRotation, sky, time, all, false, null);
    }

    public void renderSphereNative(GL3 gl, Camera camera, Vector3 sunDirection, float[] sunColour,
            Vector3 ambient, Matrix4 skyRotation, Texture sky, float time) {
        if (shader == null) return;
        List<Creature> all = new ArrayList<>(showcase);
        for (List<Creature> list : creaturesByGeodesicCell.values()) all.addAll(list);
        SphereRender.configure(gl, shader, camera, camera.getSphereViewProjection(),
                sunDirection, sunColour, ambient, skyRotation);
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            shader.setInt(gl, "skyTexture", 2);
        }
        renderCreatures(gl, camera.getSphereViewProjection(), null, new Vector3(0f, 0f, 0f),
                new Vector3(0f, 0f, 0f), sunColour, ambient, skyRotation, sky, time,
                all, true, camera.getSpherePosition());
        SphereRender.reset(gl, shader);
    }

    private void renderCreatures(GL3 gl, Matrix4 viewProjection, Frustum frustum, Vector3 viewPos, Vector3 sunPos,
                                 float[] sunColour, Vector3 ambient, Matrix4 skyRotation, Texture sky, float time,
                                 List<Creature> creatures, boolean sphereNative, SpherePosition sphereEye) {
        if (shader == null) return;
        shader.use(gl);
        if (!sphereNative) {
            shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
            shader.setVec3(gl, "viewPos", viewPos);
            shader.setVec3(gl, "sunPos", sunPos);
            shader.setVec3(gl, "sunColour", new Vector3(sunColour[0], sunColour[1], sunColour[2]));
            shader.setVec3(gl, "ambientLight", ambient);
        }
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
        for (Creature c : creatures) draw(gl, c, frustum, viewPos, bonesLocation, time, sphereNative, sphereEye);
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
        lists.addAll(creaturesByGeodesicCell.values());
        lists.add(showcase);
        for (List<Creature> list : lists) {
            for (Creature c : list) {
                if (c.outline != null && c.outlineDrawing == drawing) {
                    double[] chart = c.spherePosition == null ? null : Planet.mercatorPosition(c.spherePosition);
                    visit.at(species.indexOf(c.species),
                            chart == null ? c.x : (float) chart[0],
                            chart == null ? c.z : (float) chart[2], c.outline);
                }
            }
        }
    }

    private void draw(GL3 gl, Creature c, Frustum frustum, Vector3 viewPos, int bonesLocation,
            float time, boolean sphereNative, SpherePosition sphereEye) {
        if (sphereNative) {
            drawSphereNative(gl, c, bonesLocation, time, sphereEye);
            return;
        }
        OrganismSpecies s = c.species;
        float dx = c.x - viewPos.x, dz = c.z - viewPos.z;
        float ground = TerrainMesh.getLayeredHeight(c.x, c.z, terrainNoise);
        float radius = s.reachRadius() * c.sizeScale * c.legScale;
        // (a flyer is tested where it flies, not on the ground below)
        float middle = s.locomotion == OrganismSpecies.Locomotion.FLYER ? Math.max(ground, seaLevel) + s.bodyHeight : ground + radius * 0.3f;
        if (dx * dx + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE) return;
        if (!frustum.intersectsSphere(c.x, middle, c.z,
                radius + (s.locomotion == OrganismSpecies.Locomotion.FLYER ? 10f : 0f))) return;

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

    private void drawSphereNative(GL3 gl, Creature creature, int bonesLocation, float time,
            SpherePosition eye) {
        if (creature.spherePosition == null) return;
        OrganismSpecies s = creature.species;
        SpherePosition groundPosition = terrainPosition(creature.spherePosition);
        double dx = groundPosition.x() - eye.x(), dy = groundPosition.y() - eye.y();
        double dz = groundPosition.z() - eye.z();
        if (dx * dx + dy * dy + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE) return;

        double frontDistance = s.halfLength() * creature.sizeScale;
        double backDistance = s.tailLength() * creature.sizeScale;
        SpherePosition front = Planet.moveOnSurface(groundPosition, Math.sin(creature.heading),
                Math.cos(creature.heading), frontDistance);
        SpherePosition back = Planet.moveOnSurface(groundPosition, -Math.sin(creature.heading),
                -Math.cos(creature.heading), backDistance);
        float ground = TerrainMesh.getLayeredHeight(groundPosition, terrainNoise);
        float frontGround = creature.level ? ground : TerrainMesh.getLayeredHeight(front, terrainNoise);
        float backGround = creature.level ? ground : TerrainMesh.getLayeredHeight(back, terrainNoise);
        float pitch = s.locomotion == OrganismSpecies.Locomotion.FLYER
                ? creature.level ? 0f : 0.05f * (float) Math.sin(creature.gaitPhase * Math.PI * 2)
                : s.locomotion == OrganismSpecies.Locomotion.FLOATER ? 0f
                : (float) Math.atan2(frontGround - backGround, frontDistance + backDistance);
        float bodyHeight;
        if (s.locomotion == OrganismSpecies.Locomotion.FLYER) {
            bodyHeight = Math.max(ground, seaLevel) + s.bodyHeight
                    + (float) Math.sin(time * 0.4f + spherePhase(creature.sphereHome)) * 8f - ground;
        } else if (s.locomotion == OrganismSpecies.Locomotion.FLOATER) {
            bodyHeight = Math.max(ground, seaLevel)
                    + (s.bodyHeight + (float) Math.sin(time * 0.7f + spherePhase(creature.sphereHome)) * 2.5f)
                    * creature.sizeScale - ground;
        } else {
            float bob = s.locomotion == OrganismSpecies.Locomotion.WALKER
                    ? (float) Math.abs(Math.sin(creature.gaitPhase * Math.PI * 4))
                            * 0.03f * s.bodyHeight * creature.walking : 0f;
            bodyHeight = Math.max(ground, (frontGround + backGround) * 0.5f)
                    + (s.bodyHeight * creature.legScale + bob) * creature.sizeScale
                    + hopLift(creature) - ground;
        }

        float[] body = Affine.multiply(Affine.translation(0f, bodyHeight, 0f),
                Affine.multiply(Affine.rotationY(creature.heading),
                        Affine.multiply(Affine.rotationX(-pitch),
                                Affine.scale(creature.sizeScale, creature.sizeScale, creature.sizeScale))));
        s.pose(body, creature.legScale, creature.gaitPhase, creature.walking,
                time + spherePhase(creature.sphereHome) * 0.01f, bones);
        if (keepOutlines) {
            float[] local = s.outline(creature.morph);
            if (creature.outline == null || creature.outline.length != local.length / 4 * 3) {
                creature.outline = new float[local.length / 4 * 3];
            }
            double length = groundPosition.length();
            double latitude = Math.asin(groundPosition.y() / length);
            double longitude = Math.atan2(groundPosition.z(), groundPosition.x());
            double eastX = -Math.sin(longitude), eastZ = Math.cos(longitude);
            double upX = groundPosition.x() / length, upY = groundPosition.y() / length,
                    upZ = groundPosition.z() / length;
            double southX = Math.sin(latitude) * Math.cos(longitude);
            double southY = -Math.cos(latitude);
            double southZ = Math.sin(latitude) * Math.sin(longitude);
            for (int k = 0, o = 0; k < local.length; k += 4, o += 3) {
                int matrix = (int) local[k + 3] * 16;
                double x = bones[matrix] * local[k] + bones[matrix + 4] * local[k + 1]
                        + bones[matrix + 8] * local[k + 2] + bones[matrix + 12];
                double y = bones[matrix + 1] * local[k] + bones[matrix + 5] * local[k + 1]
                        + bones[matrix + 9] * local[k + 2] + bones[matrix + 13];
                double z = bones[matrix + 2] * local[k] + bones[matrix + 6] * local[k + 1]
                        + bones[matrix + 10] * local[k + 2] + bones[matrix + 14];
                SpherePosition point = new SpherePosition(groundPosition.x() + eastX * x + upX * y + southX * z,
                        groundPosition.y() + upY * y + southY * z,
                        groundPosition.z() + eastZ * x + upZ * y + southZ * z);
                double[] chart = Planet.mercatorPosition(point);
                creature.outline[o] = (float) chart[0];
                creature.outline[o + 1] = (float) chart[1];
                creature.outline[o + 2] = (float) chart[2];
            }
            creature.outlineDrawing = drawing;
        }
        gl.glUniformMatrix4fv(bonesLocation, s.boneCount, false, bones, 0);
        shader.setInt(gl, "sphereNativeAnchor", 1);
        shader.setVec3(gl, "sphereWorldAnchor", new Vector3((float) groundPosition.x(),
                (float) groundPosition.y(), (float) groundPosition.z()));
        shader.setVec3(gl, "baseColour", vec(creature.baseColour));
        shader.setVec3(gl, "accentColour", vec(creature.accentColour));
        shader.setVec3(gl, "bellyColour", vec(creature.bellyColour));
        shader.setVec3(gl, "limbColour", vec(creature.limbColour));
        shader.setVec3(gl, "eyeColour", vec(s.eyeColour));
        shader.setInt(gl, "patternType", s.patternType);
        shader.setFloat(gl, "patternScale", s.patternScale);
        shader.setFloat(gl, "gloss", s.gloss);
        shader.setVec3(gl, "trimColour", vec(creature.limbColour));
        s.mesh(creature.morph).render(gl);
        shader.setInt(gl, "sphereNativeAnchor", 0);
    }

    private static float spherePhase(SpherePosition position) {
        double length = position.length();
        double latitude = Math.asin(position.y() / length);
        double longitude = Math.atan2(position.z(), position.x());
        return (float) (longitude + latitude * 0.37);
    }

    private static Vector3 vec(float[] c) {
        return new Vector3(c[0], c[1], c[2]);
    }
}
