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
        float walking;
        float gaitPhase;
        Random rand;
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
    }

    public int speciesCount() {
        return species.size();
    }

    /** Where species i lives, as a factor the debug minimap can show. */
    public RegionalFactor habitat(int i) {
        return i < species.size() ? species.get(i).habitat : null;
    }

    public void initialise(GL3 gl) {
        shader = new Shader(gl, "assets/shaders/vs_organism.txt", "assets/shaders/fs_organism.txt");
        for (OrganismSpecies s : species) {
            s.buildMeshes(gl);
        }
    }

    public void dispose(GL3 gl) {
        for (OrganismSpecies s : species) {
            s.dispose(gl);
        }
    }

    /** Forgets every creature, e.g. when the viewer jumps to a new round. */
    public void clear() {
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

    private List<Creature> populate(int cx, int cz) {
        List<Creature> list = new ArrayList<>();
        Random rand = new Random(seed ^ (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL) ^ 0x0B6L);
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
                if (!flies && (TerrainMesh.getLayeredHeight(x, z, terrainNoise) <= seaLevel + 1f || !isWild(x, z))) continue;
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

    private void pickTarget(Creature c) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = c.rand.nextDouble() * Math.PI * 2;
            float r = ROAM_RADIUS * (float) Math.sqrt(c.rand.nextDouble());
            float tx = c.homeX + r * (float) Math.cos(angle), tz = c.homeZ + r * (float) Math.sin(angle);
            if (TerrainMesh.getLayeredHeight(tx, tz, terrainNoise) > seaLevel + 1f && isWild(tx, tz)) {
                c.targetX = tx;
                c.targetZ = tz;
                return;
            }
        }
        c.targetX = c.homeX;
        c.targetZ = c.homeZ;
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
        float speed = c.species.walkSpeed * c.sizeScale * c.legScale;
        boolean resting = c.pause > 0f;
        if (resting) {
            c.pause -= dt;
        } else {
            float dx = c.targetX - c.x, dz = c.targetZ - c.z;
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance < 4f) {
                c.pause = 1f + c.rand.nextFloat() * 6f;
                pickTarget(c);
            } else {
                float desired = (float) Math.atan2(dx, dz);
                float turn = desired - c.heading;
                turn = (float) Math.atan2(Math.sin(turn), Math.cos(turn));
                float maxTurn = 1.2f * dt;
                c.heading += Math.max(-maxTurn, Math.min(maxTurn, turn));
                // Slow down for sharp turns so it doesn't skid round them
                float pace = c.walking * (1f - 0.6f * Math.min(1f, Math.abs(turn)));
                float nx = c.x + (float) Math.sin(c.heading) * speed * pace * dt;
                float nz = c.z + (float) Math.cos(c.heading) * speed * pace * dt;
                if (TerrainMesh.getLayeredHeight(nx, nz, terrainNoise) > seaLevel + 0.5f) {
                    c.x = nx;
                    c.z = nz;
                } else {
                    pickTarget(c);
                }
            }
        }
        float targetWalking = resting ? 0f : 1f;
        c.walking += (targetWalking - c.walking) * Math.min(1f, dt * 3f);
        c.gaitPhase = (c.gaitPhase + dt * c.species.stepsPerSecond * c.walking) % 1f;
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
        for (List<Creature> list : creaturesByChunk.values()) {
            for (Creature c : list) draw(gl, c, frustum, viewPos, bonesLocation, time);
        }
        for (Creature c : showcase) draw(gl, c, frustum, viewPos, bonesLocation, time);
        gl.glEnable(GL.GL_CULL_FACE);
    }

    private void draw(GL3 gl, Creature c, Frustum frustum, Vector3 viewPos, int bonesLocation, float time) {
        OrganismSpecies s = c.species;
        float dx = c.x - viewPos.x, dz = c.z - viewPos.z;
        if (dx * dx + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE) return;
        float ground = TerrainMesh.getLayeredHeight(c.x, c.z, terrainNoise);
        float radius = s.reachRadius() * c.sizeScale * c.legScale;
        if (!frustum.intersectsSphere(c.x, ground + radius * 0.3f, c.z, radius)) return;

        // Level the body along the slope beneath it
        float sin = (float) Math.sin(c.heading), cos = (float) Math.cos(c.heading);
        float front = s.halfLength() * c.sizeScale, back = s.tailLength() * c.sizeScale;
        float frontGround = TerrainMesh.getLayeredHeight(c.x + sin * front, c.z + cos * front, terrainNoise);
        float backGround = TerrainMesh.getLayeredHeight(c.x - sin * back, c.z - cos * back, terrainNoise);
        float pitch = (float) Math.atan2(frontGround - backGround, front + back);
        float height;
        if (s.locomotion == OrganismSpecies.Locomotion.FLYER) {
            // Banked into the turn, holding its height above the land or sea below
            pitch = 0.05f * (float) Math.sin(c.gaitPhase * Math.PI * 2);
            height = Math.max(ground, seaLevel) + s.bodyHeight + (float) Math.sin(time * 0.4f + c.homeZ) * 8f;
        } else if (s.locomotion == OrganismSpecies.Locomotion.FLOATER) {
            // Floaters drift level, bobbing gently
            pitch = 0f;
            height = Math.max(ground, seaLevel) + (s.bodyHeight + (float) Math.sin(time * 0.7f + c.homeX) * 2.5f) * c.sizeScale;
        } else {
            float bob = s.locomotion == OrganismSpecies.Locomotion.WALKER
                    ? (float) Math.abs(Math.sin(c.gaitPhase * Math.PI * 4)) * 0.03f * s.bodyHeight * c.walking : 0f;
            height = Math.max(ground, (frontGround + backGround) * 0.5f) + (s.bodyHeight * c.legScale + bob) * c.sizeScale;
        }
        float[] body = Affine.multiply(Affine.translation(c.x, height, c.z),
                Affine.multiply(Affine.rotationY(c.heading),
                        Affine.multiply(Affine.rotationX(-pitch), Affine.scale(c.sizeScale, c.sizeScale, c.sizeScale))));

        s.pose(body, c.legScale, c.gaitPhase, c.walking, time + c.homeX * 0.01f, bones);
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
