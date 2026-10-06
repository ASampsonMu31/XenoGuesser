import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Rocks lying about the land. How many there are, how big, what colour and what shape all
 * drift smoothly across the world on their own noise maps (independent of nations), and
 * high ground has more of them, so the rocks underfoot are a clue to where you are.
 *
 * Near the player each chunk's rocks are merged into one detailed mesh, coloured per vertex;
 * the bigger ones are solid. Out to the horizon the medium and large ones are drawn too, in
 * tiles of several chunks, each rock a coarser version of the same shape in the same place,
 * hidden wherever the detailed chunks have taken over.
 */
public class RockField {

    private static final int VARIANTS = 10;
    private static final int RADIUS_CHUNKS = 7;          // in full detail this many chunks out
    private static final int BUILDS_PER_FRAME = 4;       // beyond the nearest few, built a few at a time
    private static final float MAX_PER_CHUNK = 26f;
    // Beyond, out to the horizon: tiles of FAR_TILE by FAR_TILE chunks holding just the rocks
    // big enough to see that far, in coarse meshes
    private static final int FAR_TILE = 4;
    private static final float FAR_REACH = 2400f;
    private static final float FAR_MIN_RADIUS = 2.8f;
    private static final int FAR_BUILDS_PER_FRAME = 2;
    // Rocks whose tops stand lower than this are stepped over; taller ones are solid
    private static final float STEP_HEIGHT = 1.8f;

    private final long seed;
    private final float chunkSize;
    private final float seaLevel;
    private final PerlinNoise terrain;
    private final PerlinNoise abundance, size, hue, shade, shape;
    private final float[] baseColour;

    // A handful of lumpy rock shapes, unit size: {x, y, z, nx, ny, nz} per vertex, in detail
    // and coarse (the same lumps on a less divided ball)
    private final List<float[]> variantVertices = new ArrayList<>();
    private final List<float[]> coarseVertices = new ArrayList<>();

    private static final class Chunk {
        int vao, vbo, ebo, indexCount;
        float[] solids;   // {x, z, radius, top} per solid rock
        float centreX, centreY, centreZ, radius;
    }
    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final Map<Long, Chunk> farTiles = new HashMap<>();
    // How many rings of detailed chunks round the player are complete, and round which chunk;
    // the coarse rocks there are hidden
    private int completeRings = -1, ringCentreX, ringCentreZ;
    private Shader shader;

    /** Where rocks may not go: roads, buildings and gardens, the landing pod. */
    public interface Keepout {
        boolean blocks(float x, float z, float radius);
    }
    private Keepout keepout = (x, z, r) -> false;

    public RockField(long seed, float chunkSize, float seaLevel, PerlinNoise terrain, float[] bedrock) {
        this.seed = seed;
        this.chunkSize = chunkSize;
        this.seaLevel = seaLevel;
        this.terrain = terrain;
        this.abundance = new PerlinNoise(seed ^ 0x5A0C1L);
        this.size = new PerlinNoise(seed ^ 0x5A0C2L);
        this.hue = new PerlinNoise(seed ^ 0x5A0C3L);
        this.shade = new PerlinNoise(seed ^ 0x5A0C4L);
        this.shape = new PerlinNoise(seed ^ 0x5A0C5L);
        this.baseColour = bedrock;
        for (int v = 0; v < VARIANTS; v++) buildVariant(new Random(seed * 131L + v));
    }

    public void setKeepout(Keepout keepout) {
        this.keepout = keepout;
    }

    public void initialise(GL3 gl) {
        shader = new Shader(gl, "assets/shaders/vs_rock.txt", "assets/shaders/fs_rock.txt");
    }

    // ------------------------------------------------------------------ regional character

    /** 0..1: how rocky the land is here. */
    private float rockinessAt(float x, float z) {
        float n = abundance.onSphere(Planet.surface(x, z), 0.00011f, 0f, 0f);
        float base = smooth(-0.25f, 0.55f, n);
        // High ground is rockier
        float height = TerrainMesh.getLayeredHeight(x, z, terrain) - seaLevel;
        float mountain = smooth(120f, 520f, height);
        return Math.min(1f, base * 0.85f + mountain * 0.7f);
    }

    /** Typical rock radius here, from pebbles to boulders. */
    private float typicalSizeAt(float x, float z) {
        float n = 0.5f + 0.5f * size.onSphere(Planet.surface(x, z), 0.00008f, 3.1f, -1.7f);
        return 1.5f + 9.5f * n * n;
    }

    /** The colour of the stone here: this world's bedrock, shifted in hue and shade across regions. */
    private float[] colourAt(float x, float z) {
        float h = hue.onSphere(Planet.surface(x, z), 0.00006f, 0f, 0f);
        float v = shade.onSphere(Planet.surface(x, z), 0.00009f, 7.7f, 2.2f);
        float[] hsv = WorldPalette.toHsv(baseColour);
        return WorldPalette.hsv(hsv[0] + h * 0.18f, Math.max(0f, Math.min(1f, hsv[1] + h * 0.2f + 0.05f)),
                Math.max(0.12f, Math.min(0.85f, hsv[2] * (1f + v * 0.55f))));
    }

    /** 0 rounded and squat, 1 tall and angular. */
    private float angularityAt(float x, float z) {
        return 0.5f + 0.5f * shape.onSphere(Planet.surface(x, z), 0.0001f, -4.4f, 9.1f);
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    // ------------------------------------------------------------------ shapes

    /** A subdivided icosahedron pushed in and out by noise, its underside flattened, in detail and coarse. */
    private void buildVariant(Random rand) {
        // Lumps: a few random bulges and dents, then flattened facets for an angular look
        float[][] bumps = new float[6][];
        for (int i = 0; i < bumps.length; i++) {
            bumps[i] = new float[] { rand.nextFloat() * 2 - 1, rand.nextFloat() * 2 - 1, rand.nextFloat() * 2 - 1, (rand.nextFloat() - 0.4f) * 0.5f };
        }
        float[][] cuts = new float[5][];
        for (int i = 0; i < cuts.length; i++) {
            cuts[i] = normalise(new float[] { rand.nextFloat() * 2 - 1, rand.nextFloat() * 1.4f - 0.2f, rand.nextFloat() * 2 - 1 });
            cuts[i] = new float[] { cuts[i][0], cuts[i][1], cuts[i][2], 0.72f + rand.nextFloat() * 0.2f };
        }
        variantVertices.add(shapeMesh(1, bumps, cuts));
        coarseVertices.add(shapeMesh(0, bumps, cuts));
    }

    private float[] shapeMesh(int subdivisions, float[][] bumps, float[][] cuts) {
        List<float[]> points = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        float t = (1f + (float) Math.sqrt(5)) / 2f;
        float[][] ico = { { -1, t, 0 }, { 1, t, 0 }, { -1, -t, 0 }, { 1, -t, 0 }, { 0, -1, t }, { 0, 1, t }, { 0, -1, -t }, { 0, 1, -t },
                { t, 0, -1 }, { t, 0, 1 }, { -t, 0, -1 }, { -t, 0, 1 } };
        for (float[] p : ico) points.add(normalise(p));
        int[][] tris = { { 0, 11, 5 }, { 0, 5, 1 }, { 0, 1, 7 }, { 0, 7, 10 }, { 0, 10, 11 }, { 1, 5, 9 }, { 5, 11, 4 }, { 11, 10, 2 },
                { 10, 7, 6 }, { 7, 1, 8 }, { 3, 9, 4 }, { 3, 4, 2 }, { 3, 2, 6 }, { 3, 6, 8 }, { 3, 8, 9 }, { 4, 9, 5 }, { 2, 4, 11 },
                { 6, 2, 10 }, { 8, 6, 7 }, { 9, 8, 1 } };
        for (int[] tri : tris) faces.add(tri);
        for (int level = 0; level < subdivisions; level++) {
            Map<Long, Integer> midpoints = new HashMap<>();
            List<int[]> next = new ArrayList<>();
            for (int[] f : faces) {
                int a = midpoint(points, midpoints, f[0], f[1]), b = midpoint(points, midpoints, f[1], f[2]), c = midpoint(points, midpoints, f[2], f[0]);
                next.add(new int[] { f[0], a, c });
                next.add(new int[] { f[1], b, a });
                next.add(new int[] { f[2], c, b });
                next.add(new int[] { a, b, c });
            }
            faces = next;
        }
        float[][] moved = new float[points.size()][];
        for (int i = 0; i < points.size(); i++) {
            float[] p = points.get(i);
            float r = 1f;
            for (float[] b : bumps) {
                float d = p[0] * b[0] + p[1] * b[1] + p[2] * b[2];
                r += b[3] * Math.max(0f, d) * Math.max(0f, d);
            }
            float[] q = { p[0] * r, p[1] * r, p[2] * r };
            for (float[] c : cuts) {
                float d = q[0] * c[0] + q[1] * c[1] + q[2] * c[2];
                if (d > c[3]) {
                    float push = d - c[3];
                    q = new float[] { q[0] - c[0] * push, q[1] - c[1] * push, q[2] - c[2] * push };
                }
            }
            q[1] = Math.max(q[1], -0.35f);
            moved[i] = q;
        }
        // Flat-shaded: every triangle gets its own corners and face normal
        float[] vertices = new float[faces.size() * 3 * 6];
        int[] indices = new int[faces.size() * 3];
        int k = 0;
        for (int f = 0; f < faces.size(); f++) {
            float[] a = moved[faces.get(f)[0]], b = moved[faces.get(f)[1]], c = moved[faces.get(f)[2]];
            float[] n = normalise(cross(sub(b, a), sub(c, a)));
            if (n[0] * (a[0] + b[0] + c[0]) + n[1] * (a[1] + b[1] + c[1]) + n[2] * (a[2] + b[2] + c[2]) < 0) {
                n = new float[] { -n[0], -n[1], -n[2] };
                float[] swap = b; b = c; c = swap;
            }
            for (float[] p : new float[][] { a, b, c }) {
                System.arraycopy(new float[] { p[0], p[1], p[2], n[0], n[1], n[2] }, 0, vertices, k * 6, 6);
                indices[k] = k;
                k++;
            }
        }
        return vertices;
    }

    private static int midpoint(List<float[]> points, Map<Long, Integer> cache, int a, int b) {
        long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
        Integer known = cache.get(key);
        if (known != null) return known;
        float[] p = points.get(a), q = points.get(b);
        points.add(normalise(new float[] { p[0] + q[0], p[1] + q[1], p[2] + q[2] }));
        cache.put(key, points.size() - 1);
        return points.size() - 1;
    }

    // ------------------------------------------------------------------ chunks

    /** Builds the rocks of chunks coming into range and drops those left behind. */
    public void update(GL3 gl, float viewerX, float viewerZ, boolean all) {
        int cx0 = (int) Math.floor(viewerX / chunkSize), cz0 = (int) Math.floor(viewerZ / chunkSize);
        Iterator<Map.Entry<Long, Chunk>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Chunk> e = it.next();
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            if (Math.abs(cx - cx0) > RADIUS_CHUNKS + 1 || Math.abs(cz - cz0) > RADIUS_CHUNKS + 1) {
                dispose(gl, e.getValue());
                it.remove();
            }
        }
        int built = 0;
        ringCentreX = cx0;
        ringCentreZ = cz0;
        completeRings = -1;
        boolean stopped = false;
        for (int ring = 0; ring <= RADIUS_CHUNKS && !stopped; ring++) {
            boolean ringComplete = true;
            for (int dz = -ring; dz <= ring && !stopped; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    long key = key(cx0 + dx, cz0 + dz);
                    if (chunks.containsKey(key)) continue;
                    // The nearest chunks are built at once; further out a few a frame
                    if (!all && ring > 1 && built >= BUILDS_PER_FRAME) {
                        ringComplete = false;
                        stopped = true;
                        break;
                    }
                    chunks.put(key, build(gl, cx0 + dx, cz0 + dz, 1, false));
                    built++;
                }
            }
            if (ringComplete && !stopped) completeRings = ring;
        }
        updateFar(gl, viewerX, viewerZ, all);
    }

    /** Builds the coarse tiles out to the horizon, nearest first, and drops those left behind. */
    private void updateFar(GL3 gl, float viewerX, float viewerZ, boolean all) {
        float tileSize = chunkSize * FAR_TILE;
        int tx0 = (int) Math.floor(viewerX / tileSize), tz0 = (int) Math.floor(viewerZ / tileSize);
        int reach = (int) Math.ceil(FAR_REACH / tileSize);
        Iterator<Map.Entry<Long, Chunk>> it = farTiles.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Chunk> e = it.next();
            int tx = (int) (e.getKey() >> 32), tz = (int) (long) e.getKey();
            if (Math.abs(tx - tx0) > reach + 1 || Math.abs(tz - tz0) > reach + 1) {
                dispose(gl, e.getValue());
                it.remove();
            }
        }
        int built = 0;
        for (int ring = 0; ring <= reach; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    long key = key(tx0 + dx, tz0 + dz);
                    if (farTiles.containsKey(key)) continue;
                    float centreX = (tx0 + dx + 0.5f) * tileSize, centreZ = (tz0 + dz + 0.5f) * tileSize;
                    if (Math.hypot(centreX - viewerX, centreZ - viewerZ) > FAR_REACH + tileSize) continue;
                    if (!all && built >= FAR_BUILDS_PER_FRAME) return;
                    farTiles.put(key, build(gl, (tx0 + dx) * FAR_TILE, (tz0 + dz) * FAR_TILE, FAR_TILE, true));
                    built++;
                }
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * The rocks of span by span chunks from (firstX, firstZ) in one mesh: every rock in full
     * detail, or (coarse) just those big enough to see from afar, each vertex also carrying
     * its chunk so the detailed chunks can hide it.
     */
    private Chunk build(GL3 gl, int firstX, int firstZ, int span, boolean coarse) {
        Chunk chunk = new Chunk();
        List<float[]> verts = new ArrayList<>();
        List<Float> solids = new ArrayList<>();
        float[] bounds = { Float.MAX_VALUE, -Float.MAX_VALUE };
        for (int cz = firstZ; cz < firstZ + span; cz++) {
            for (int cx = firstX; cx < firstX + span; cx++) {
                addChunkRocks(cx, cz, coarse, verts, solids, bounds);
            }
        }
        int vertexCount = verts.size();
        chunk.solids = new float[solids.size()];
        for (int i = 0; i < solids.size(); i++) chunk.solids[i] = solids.get(i);
        chunk.centreX = (firstX + span * 0.5f) * chunkSize;
        chunk.centreZ = (firstZ + span * 0.5f) * chunkSize;
        chunk.centreY = vertexCount == 0 ? 0f : (bounds[0] + bounds[1]) * 0.5f;
        chunk.radius = chunkSize * span * 0.75f + (vertexCount == 0 ? 0f : (bounds[1] - bounds[0]) * 0.5f + 10f);
        chunk.indexCount = vertexCount;
        if (vertexCount == 0) return chunk;

        int floats = coarse ? 11 : 9;
        float[] data = new float[vertexCount * floats];
        for (int i = 0; i < vertexCount; i++) System.arraycopy(verts.get(i), 0, data, i * floats, floats);
        int[] ids = new int[3];
        gl.glGenVertexArrays(1, ids, 0);
        gl.glGenBuffers(2, ids, 1);
        chunk.vao = ids[0];
        chunk.vbo = ids[1];
        chunk.ebo = ids[2];
        gl.glBindVertexArray(chunk.vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, chunk.vbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) data.length * Float.BYTES, Buffers.newDirectFloatBuffer(data), GL.GL_STATIC_DRAW);
        int stride = floats * Float.BYTES;
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, stride, 0);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, stride, 3L * Float.BYTES);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(2, 3, GL.GL_FLOAT, false, stride, 6L * Float.BYTES);
        gl.glEnableVertexAttribArray(2);
        if (coarse) {
            gl.glVertexAttribPointer(3, 2, GL.GL_FLOAT, false, stride, 9L * Float.BYTES);
            gl.glEnableVertexAttribArray(3);
        }
        gl.glBindVertexArray(0);
        return chunk;
    }

    /** Places one chunk's rocks, the same each time, adding their vertices (and solid ones). */
    private void addChunkRocks(int cx, int cz, boolean coarse, List<float[]> verts, List<Float> solids, float[] bounds) {
        float x0 = cx * chunkSize, z0 = cz * chunkSize;
        Random rand = new Random(seed ^ (Planet.wrapChunk(cx, chunkSize) * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL) ^ 0x70C5L);
        float centreX = x0 + chunkSize * 0.5f, centreZ = z0 + chunkSize * 0.5f;
        float expected = rockinessAt(centreX, centreZ) * MAX_PER_CHUNK;
        int count = (int) expected + (rand.nextFloat() < expected - (int) expected ? 1 : 0);
        // Rocks gather in loose clusters rather than spreading evenly
        float clusterX = x0 + rand.nextFloat() * chunkSize, clusterZ = z0 + rand.nextFloat() * chunkSize;
        for (int i = 0; i < count; i++) {
            float x, z;
            if (rand.nextFloat() < 0.6f) {
                x = clusterX + (float) rand.nextGaussian() * chunkSize * 0.18f;
                z = clusterZ + (float) rand.nextGaussian() * chunkSize * 0.18f;
            } else {
                x = x0 + rand.nextFloat() * chunkSize;
                z = z0 + rand.nextFloat() * chunkSize;
            }
            float typical = typicalSizeAt(x, z);
            // Mostly small, now and then a big one
            float r = typical * (0.35f + 1.3f * (float) Math.pow(rand.nextFloat(), 2.2f));
            // Every rock draws the same numbers whether or not it's kept, so each is always the same
            float angularRoll = rand.nextFloat(), yaw = rand.nextFloat() * (float) Math.PI * 2;
            float tiltX = (rand.nextFloat() - 0.5f) * 0.4f, tiltZ = (rand.nextFloat() - 0.5f) * 0.4f;
            float tone = 0.85f + 0.3f * rand.nextFloat();
            int variant = rand.nextInt(VARIANTS);
            if (coarse && r < FAR_MIN_RADIUS) continue;
            float ground = TerrainMesh.getLayeredHeight(x, z, terrain);
            if (ground < seaLevel + 0.5f || keepout.blocks(x, z, r)) continue;
            float angular = angularityAt(x, z);
            float squash = 0.45f + 0.45f * angular * (0.6f + 0.4f * angularRoll);
            float[] colour = colourAt(x, z);
            float[] src = (coarse ? coarseVertices : variantVertices).get(variant);
            float cos = (float) Math.cos(yaw), sin = (float) Math.sin(yaw);
            float sink = r * squash * 0.25f;
            for (int v = 0; v < src.length; v += 6) {
                float lx = src[v] * r, ly = src[v + 1] * r * squash, lz = src[v + 2] * r;
                // A slight lean, then turned
                ly += lx * tiltX + lz * tiltZ;
                float wx = x + lx * cos + lz * sin, wz = z - lx * sin + lz * cos;
                float wy = ground - sink + ly + r * squash * 0.35f;
                float nx = src[v + 3], ny = src[v + 4] / Math.max(0.3f, squash), nz = src[v + 5];
                float rnx = nx * cos + nz * sin, rnz = -nx * sin + nz * cos;
                float len = (float) Math.sqrt(rnx * rnx + ny * ny + rnz * rnz);
                // Darker low down where soil and shadow gather
                float low = 0.65f + 0.35f * Math.max(0f, Math.min(1f, (src[v + 1] + 0.35f) / 1.0f));
                float[] vertex = coarse
                        ? new float[] { wx, wy, wz, rnx / len, ny / len, rnz / len, colour[0] * tone * low, colour[1] * tone * low, colour[2] * tone * low, cx, cz }
                        : new float[] { wx, wy, wz, rnx / len, ny / len, rnz / len, colour[0] * tone * low, colour[1] * tone * low, colour[2] * tone * low };
                verts.add(vertex);
                bounds[0] = Math.min(bounds[0], wy);
                bounds[1] = Math.max(bounds[1], wy);
            }
            float top = r * squash * 1.1f;
            if (!coarse && top > STEP_HEIGHT) {
                solids.add(x);
                solids.add(z);
                solids.add(r * 0.85f);
                solids.add(top);
            }
        }
    }

    private void dispose(GL3 gl, Chunk chunk) {
        if (chunk.vao == 0) return;
        gl.glDeleteVertexArrays(1, new int[] { chunk.vao }, 0);
        gl.glDeleteBuffers(2, new int[] { chunk.vbo, chunk.ebo }, 0);
    }

    /** Drops every chunk, for a new round (the pod may now be standing where rocks were). */
    public void clear(GL3 gl) {
        for (Chunk chunk : chunks.values()) dispose(gl, chunk);
        chunks.clear();
        for (Chunk chunk : farTiles.values()) dispose(gl, chunk);
        farTiles.clear();
        completeRings = -1;
    }

    public void render(GL3 gl, Matrix4 viewProjection, Frustum frustum, Vector3 viewPos, Vector3 sunPos, float[] sunColour,
                       Vector3 ambient, Matrix4 skyRotation, Texture sky) {
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
        shader.setInt(gl, "coarse", 0);
        for (Chunk chunk : chunks.values()) {
            if (chunk.indexCount == 0 || !frustum.intersectsSphere(chunk.centreX, chunk.centreY, chunk.centreZ, chunk.radius)) continue;
            gl.glBindVertexArray(chunk.vao);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, chunk.indexCount);
        }
        // The coarse rocks out to the horizon, except where the detailed ones are drawn
        shader.setInt(gl, "coarse", 1);
        shader.setFloat(gl, "detailedCentre", ringCentreX, ringCentreZ);
        shader.setFloat(gl, "detailedRings", completeRings);
        for (Chunk chunk : farTiles.values()) {
            if (chunk.indexCount == 0 || !frustum.intersectsSphere(chunk.centreX, chunk.centreY, chunk.centreZ, chunk.radius)) continue;
            gl.glBindVertexArray(chunk.vao);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, chunk.indexCount);
        }
        gl.glBindVertexArray(0);
    }

    /**
     * How high the top of a rock is over a point (a dome over its footprint), given the
     * ground there; NaN if no rock stands there.
     */
    public float topAt(float x, float z, float ground) {
        Chunk chunk = chunks.get(key((int) Math.floor(x / chunkSize), (int) Math.floor(z / chunkSize)));
        if (chunk == null || chunk.solids == null) return Float.NaN;
        float best = Float.NaN;
        for (int i = 0; i < chunk.solids.length; i += 4) {
            float dx = x - chunk.solids[i], dz = z - chunk.solids[i + 1], r = chunk.solids[i + 2];
            float d2 = (dx * dx + dz * dz) / (r * r);
            if (d2 >= 1f) continue;
            float top = ground + chunk.solids[i + 3] * (float) Math.sqrt(1f - d2);
            if (Float.isNaN(best) || top > best) best = top;
        }
        return best;
    }

    /** The bigger rocks near a point, as solid circles. */
    public void obstaclesNear(float x, float z, float reach, Collision.Sink sink) {
        int cx0 = (int) Math.floor((x - reach - 10f) / chunkSize), cx1 = (int) Math.floor((x + reach + 10f) / chunkSize);
        int cz0 = (int) Math.floor((z - reach - 10f) / chunkSize), cz1 = (int) Math.floor((z + reach + 10f) / chunkSize);
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                Chunk chunk = chunks.get(key(cx, cz));
                if (chunk == null) continue;
                for (int i = 0; i < chunk.solids.length; i += 4) {
                    sink.circle(chunk.solids[i], chunk.solids[i + 1], chunk.solids[i + 2], chunk.solids[i + 3]);
                }
            }
        }
    }

    private static float[] normalise(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return l < 1e-9f ? v : new float[] { v[0] / l, v[1] / l, v[2] / l };
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }
}
