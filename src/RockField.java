import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Rocks lying about the land. How many there are, how big, what colour and what shape all
 * drift smoothly across the world on their own noise maps (independent of nations), and
 * high ground has more of them, so the rocks underfoot are a clue to where you are.
 *
 * Rocks are placed per geodesic cell (ROCK_LEVEL, a little over half a chunk's area each),
 * as many as the rockiness there and the cell's own area call for, each in the tangent frame
 * where it lies, so they are the same size and as many to the hectare at the equator, at 70
 * degrees and at a pole. Near the player the rocks of each tile (a coarser cell, DETAIL_LEVEL)
 * are merged into one detailed mesh, coloured per vertex, and the bigger ones are solid. Out
 * to the horizon the medium and large ones are drawn too, in bigger tiles (FAR_LEVEL, the
 * parents of four detailed tiles), each rock a coarser version of the same shape in the same
 * place, hidden in whichever quarters of the tile are drawn in detail. Every tile is stored
 * relative to its centre and drawn relative to the camera.
 */
public class RockField {

    private static final int VARIANTS = 10;
    private static final int ROCK_LEVEL = 8, DETAIL_LEVEL = 6, FAR_LEVEL = 5;
    // Detailed tiles within this far, coarse ones out to FAR_REACH
    private static final double DETAIL_REACH = 700.0, FAR_REACH = 2400.0;
    // How many rocks a chunk's area (100 by 100) has where it's rockiest
    private static final float MAX_PER_CHUNK = 26f;
    private static final float FAR_MIN_RADIUS = 2.8f;
    // Rocks whose tops stand lower than this are stepped over; taller ones are solid
    private static final float STEP_HEIGHT = 1.8f;

    private final long seed;
    private final float chunkSize;
    private final float seaLevel;
    private final PerlinNoise terrain;
    private final PerlinNoise abundance, size, hue, shade, shape;
    private final float[] baseColour;
    private final GeodesicChunkGrid rockGrid = new GeodesicChunkGrid(ROCK_LEVEL);
    private final GeodesicChunkGrid detailGrid = new GeodesicChunkGrid(DETAIL_LEVEL);
    private final GeodesicChunkGrid farGrid = new GeodesicChunkGrid(FAR_LEVEL);

    // A handful of lumpy rock shapes, unit size: {x, y, z, nx, ny, nz} per vertex, in detail
    // and coarse (the same lumps on a less divided ball)
    private final List<float[]> variantVertices = new ArrayList<>();
    private final List<float[]> coarseVertices = new ArrayList<>();

    /** A solid rock: where it stands, how far round, and how high its top is above the ground there. */
    private record Solid(SpherePosition centre, double radius, double top) {
    }

    /** A tile's rocks, worked out off the GL thread: vertex data relative to its centre, and its solid rocks. */
    private record Built(SpherePosition centre, double radius, float[] data, List<Solid> solids) {
    }

    private static final class Tile {
        SpherePosition centre;
        double radius;
        int vao, vbo, vertexCount;
        List<Solid> solids = List.of();
    }
    private final Map<Long, Tile> detailTiles = new HashMap<>();
    private final Map<Long, Tile> farTiles = new HashMap<>();
    private final Map<Long, Future<Built>> detailBuilding = new HashMap<>();
    private final Map<Long, Future<Built>> farBuilding = new HashMap<>();
    // The solid rocks of every detailed tile, for collision and for what rain lands on
    private final CellIndex<Solid> solids = new CellIndex<>();
    private Shader shader;
    private ExecutorService builders;
    // The tiles in range of the viewer's tile, worked out again only when the viewer changes tile
    private final Map<GeodesicChunkGrid, Long> inRangeFor = new HashMap<>();
    private final Map<GeodesicChunkGrid, List<GeodesicChunkGrid.Cell>> inRange = new HashMap<>();

    /** Where rocks may not go: roads, buildings and gardens, the landing pod. */
    public interface Keepout {
        boolean blocks(SpherePosition position, float radius);
    }
    private Keepout keepout = (p, r) -> false;

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

    /** The shader, and the threads tiles are worked out on (their meshes are put up on the GL thread). */
    public void initialise(GL3 gl, ExecutorService builders) {
        this.builders = builders;
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_rock.txt", GamePaths.HOME + "assets/shaders/fs_rock.txt");
    }

    // ------------------------------------------------------------------ regional character

    /** The noise maps' sampling point for a place: on the datum sphere, as PerlinNoise.onSphere takes it. */
    private static float[] surface(SpherePosition p) {
        double scale = Planet.radius() / p.length();
        return new float[] { (float) (p.x() * scale), (float) (p.y() * scale), (float) (p.z() * scale) };
    }

    /**
     * How many rocks a chunk's area (100 by 100) round a place is expected to have (as they're
     * spawned, before any on water or roads are dropped), for the map's overlay.
     */
    public float expectedPerChunk(SpherePosition place) {
        return rockinessAt(place) * MAX_PER_CHUNK;
    }

    /** 0..1: how rocky the land is here. */
    private float rockinessAt(SpherePosition p) {
        float n = abundance.onSphere(surface(p), 0.00011f, 0f, 0f);
        float base = smooth(-0.25f, 0.55f, n);
        // High ground is rockier
        float height = TerrainMesh.getLayeredHeight(p, terrain) - seaLevel;
        float mountain = smooth(120f, 520f, height);
        return Math.min(1f, base * 0.85f + mountain * 0.7f);
    }

    // The smallest and largest typical rock (radius) a region can have
    public static final float SMALLEST_TYPICAL = 1.5f, LARGEST_TYPICAL = 11f;

    /** How big the rocks are round a point: their typical radius (most are smaller, a few bigger). */
    public float typicalSizeAt(SpherePosition p) {
        float n = 0.5f + 0.5f * size.onSphere(surface(p), 0.00008f, 3.1f, -1.7f);
        return SMALLEST_TYPICAL + (LARGEST_TYPICAL - SMALLEST_TYPICAL) * n * n;
    }

    /** The colour of the stone here: this world's bedrock, shifted in hue and shade across regions. */
    private float[] colourAt(SpherePosition p) {
        float[] s = surface(p);
        float h = hue.onSphere(s, 0.00006f, 0f, 0f);
        float v = shade.onSphere(s, 0.00009f, 7.7f, 2.2f);
        float[] hsv = WorldPalette.toHsv(baseColour);
        return WorldPalette.hsv(hsv[0] + h * 0.18f, Math.max(0f, Math.min(1f, hsv[1] + h * 0.2f + 0.05f)),
                Math.max(0.12f, Math.min(0.85f, hsv[2] * (1f + v * 0.55f))));
    }

    /** 0 rounded and squat, 1 tall and angular. */
    private float angularityAt(SpherePosition p) {
        return 0.5f + 0.5f * shape.onSphere(surface(p), 0.0001f, -4.4f, 9.1f);
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

    // ------------------------------------------------------------------ tiles

    /**
     * Puts up the tiles worked out since last time, sets the rest in range being worked out
     * (nearest first), and drops those left behind. With all, every tile in range is built
     * before returning (while loading, so the round starts with every rock in place).
     */
    public void update(GL3 gl, SpherePosition viewer, boolean all) {
        updateTiles(gl, viewer, all, detailGrid, DETAIL_REACH, detailTiles, detailBuilding, false);
        updateTiles(gl, viewer, all, farGrid, FAR_REACH, farTiles, farBuilding, true);
    }

    private void updateTiles(GL3 gl, SpherePosition viewer, boolean all, GeodesicChunkGrid grid, double reach,
            Map<Long, Tile> tiles, Map<Long, Future<Built>> building, boolean coarse) {
        GeodesicChunkGrid.Direction here = SphereMath.direction(viewer);
        long viewerTile = grid.cellAt(here).id();
        if (!Long.valueOf(viewerTile).equals(inRangeFor.get(grid))) {
            inRangeFor.put(grid, viewerTile);
            inRange.put(grid, grid.cellsWithin(here, reach / Planet.radius()));
        }
        List<GeodesicChunkGrid.Cell> inRange = this.inRange.get(grid);
        Set<Long> wanted = new HashSet<>();
        for (GeodesicChunkGrid.Cell cell : inRange) wanted.add(cell.id());
        // Dropped once well out of range, so a tile at the edge isn't made again and again
        double keep = reach / Planet.radius() + 2.0 * grid.cellAt(here).angularRadius();
        Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Tile> e = it.next();
            if (wanted.contains(e.getKey()) || SphereMath.angle(viewer, e.getValue().centre) <= keep) continue;
            dispose(gl, e.getValue());
            it.remove();
        }
        Iterator<Map.Entry<Long, Future<Built>>> pending = building.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<Long, Future<Built>> e = pending.next();
            if (wanted.contains(e.getKey())) continue;
            e.getValue().cancel(true);
            pending.remove();
        }
        for (GeodesicChunkGrid.Cell cell : inRange) {
            long id = cell.id();
            if (tiles.containsKey(id)) continue;
            Future<Built> future = building.get(id);
            Built built = null;
            if (future == null) {
                if (all || builders == null) {
                    built = build(grid, id, coarse);
                } else {
                    building.put(id, builders.submit(() -> build(grid, id, coarse)));
                    continue;
                }
            } else if (future.isDone() || all) {
                building.remove(id);
                try {
                    built = future.get();
                } catch (Exception e) {
                    // (cancelled, or failed: tried again next time)
                    continue;
                }
            }
            if (built != null) tiles.put(id, install(gl, built, coarse));
        }
    }

    /** Puts a worked-out tile's mesh up on the GPU, and its solid rocks in the index. */
    private Tile install(GL3 gl, Built built, boolean coarse) {
        Tile tile = new Tile();
        tile.centre = built.centre();
        tile.radius = built.radius();
        tile.solids = built.solids();
        for (Solid solid : tile.solids) solids.add(solid.centre(), solid.radius(), solid);
        int floats = coarse ? 10 : 9;
        tile.vertexCount = built.data().length / floats;
        if (tile.vertexCount == 0) return tile;
        int[] ids = new int[2];
        gl.glGenVertexArrays(1, ids, 0);
        gl.glGenBuffers(1, ids, 1);
        tile.vao = ids[0];
        tile.vbo = ids[1];
        gl.glBindVertexArray(tile.vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, tile.vbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) built.data().length * Float.BYTES, Buffers.newDirectFloatBuffer(built.data()), GL.GL_STATIC_DRAW);
        int stride = floats * Float.BYTES;
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, stride, 0);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, stride, 3L * Float.BYTES);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(2, 3, GL.GL_FLOAT, false, stride, 6L * Float.BYTES);
        gl.glEnableVertexAttribArray(2);
        if (coarse) {
            gl.glVertexAttribPointer(3, 1, GL.GL_FLOAT, false, stride, 9L * Float.BYTES);
            gl.glEnableVertexAttribArray(3);
        }
        gl.glBindVertexArray(0);
        return tile;
    }

    /**
     * The rocks of one tile (every rock in full detail, or, coarse, just those big enough to
     * see from afar, each vertex also carrying which quarter of the tile it lies in), as
     * vertices relative to the tile's centre. Safe off the GL thread.
     */
    private Built build(GeodesicChunkGrid grid, long id, boolean coarse) {
        SpherePosition centre = SphereMath.atAltitude(grid.cell(id).centre(), 0.0);
        int shift = 2 * (ROCK_LEVEL - grid.level());
        long first = id << shift, cells = 1L << shift;
        List<float[]> verts = new ArrayList<>();
        List<Solid> solidRocks = new ArrayList<>();
        // (the drawn ground's vertex heights, shared by neighbouring rocks)
        java.util.Map<Long, Float> groundHeights = new java.util.HashMap<>();
        for (long k = 0; k < cells; k++) {
            if (Thread.currentThread().isInterrupted()) break;
            addCellRocks(rockGrid.cell(first + k), centre, coarse, verts, solidRocks, groundHeights);
        }
        int floats = coarse ? 10 : 9;
        float[] data = new float[verts.size() * floats];
        double radius = 0.0;
        for (int i = 0; i < verts.size(); i++) {
            float[] v = verts.get(i);
            System.arraycopy(v, 0, data, i * floats, floats);
            radius = Math.max(radius, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]));
        }
        return new Built(centre, radius, data, solidRocks);
    }

    /** Places one cell's rocks, the same each time, adding their vertices (relative to origin) and solid ones. */
    private void addCellRocks(GeodesicChunkGrid.Cell cell, SpherePosition origin, boolean coarse, List<float[]> verts, List<Solid> solidRocks,
                              java.util.Map<Long, Float> groundHeights) {
        double area = cell.area() * Planet.radius() * Planet.radius();
        Random rand = new Random(seed ^ (cell.id() * 0x9E3779B97F4A7C15L) ^ 0x70C5L);
        float expected = rockinessAt(SphereMath.atAltitude(cell.centre(), 0.0)) * MAX_PER_CHUNK
                * (float) (area / (chunkSize * chunkSize));
        int count = (int) expected + (rand.nextFloat() < expected - (int) expected ? 1 : 0);
        // Rocks gather in loose clusters rather than spreading evenly
        double spread = Math.sqrt(area) * 0.18;
        SphereFrame cluster = SphereFrame.at(SphereMath.atAltitude(cell.point(rand.nextDouble(), rand.nextDouble()), 0.0));
        // (which quarter of its coarse tile, as the coarse tiles' hiding needs)
        float child = (float) ((cell.id() >>> (2 * (ROCK_LEVEL - DETAIL_LEVEL))) & 3L);
        for (int i = 0; i < count; i++) {
            SpherePosition at;
            if (rand.nextFloat() < 0.6f) {
                at = cluster.toSphere(rand.nextGaussian() * spread, 0.0, rand.nextGaussian() * spread);
            } else {
                at = SphereMath.atAltitude(cell.point(rand.nextDouble(), rand.nextDouble()), 0.0);
            }
            float typical = typicalSizeAt(at);
            // Mostly small, now and then a big one
            float r = typical * (0.35f + 1.3f * (float) Math.pow(rand.nextFloat(), 2.2f));
            // Every rock draws the same numbers whether or not it's kept, so each is always the same
            float angularRoll = rand.nextFloat(), yaw = rand.nextFloat() * (float) Math.PI * 2;
            float tiltX = (rand.nextFloat() - 0.5f) * 0.4f, tiltZ = (rand.nextFloat() - 0.5f) * 0.4f;
            float tone = 0.85f + 0.3f * rand.nextFloat();
            int variant = rand.nextInt(VARIANTS);
            if (coarse && r < FAR_MIN_RADIUS) continue;
            // (on the ground as it's drawn: its flat triangles cut beneath peaks and ridges)
            float ground = TerrainMesh.drawnHeight(at, terrain, groundHeights);
            if (ground < seaLevel + 0.5f || keepout.blocks(at, r)) continue;
            // (none in a river or lake)
            if (terrain.rivers != null && !Float.isNaN(terrain.rivers.waterAt(SphereMath.direction(at), ground + r * 0.3f))) continue;
            float angular = angularityAt(at);
            float squash = 0.45f + 0.45f * angular * (0.6f + 0.4f * angularRoll);
            float[] colour = colourAt(at);
            float[] src = (coarse ? coarseVertices : variantVertices).get(variant);
            float cos = (float) Math.cos(yaw), sin = (float) Math.sin(yaw);
            float sink = r * squash * 0.25f;
            // Built in the rock's own tangent frame, standing on the ground there, its up along
            // the ground's normal so on a slope it sits into the hillside rather than upright
            SpherePosition foot = SphereMath.atAltitude(at, ground);
            SphereFrame frame = SphereFrame.at(foot);
            double[][] lie = groundBasis(frame, Math.max(1.0, r), groundHeights);
            // Sunk further wherever the ground falls away under its edges (round a peak, over
            // a ridge) faster than its underside curves up, so no edge overhangs thin air
            float settle = settleDepth(frame, lie, ground, r, r * squash, groundHeights);
            for (int v = 0; v < src.length; v += 6) {
                float lx = src[v] * r, ly = src[v + 1] * r * squash, lz = src[v + 2] * r;
                // A slight lean, then turned
                ly += lx * tiltX + lz * tiltZ;
                double[] p = onGround(lie, lx * cos + lz * sin, -sink + ly + r * squash * 0.35f, -lx * sin + lz * cos);
                SpherePosition world = frame.toWorld(p[0], p[1] - settle, p[2]);
                float nx = src[v + 3], ny = src[v + 4] / Math.max(0.3f, squash), nz = src[v + 5];
                double[] ln = onGround(lie, nx * cos + nz * sin, ny, -nx * sin + nz * cos);
                double[] n = frame.worldDirection(ln[0], ln[1], ln[2]);
                float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                // Darker low down where soil and shadow gather
                float low = 0.65f + 0.35f * Math.max(0f, Math.min(1f, (src[v + 1] + 0.35f) / 1.0f));
                float px = (float) (world.x() - origin.x()), py = (float) (world.y() - origin.y()), pz = (float) (world.z() - origin.z());
                float r0 = colour[0] * tone * low, g0 = colour[1] * tone * low, b0 = colour[2] * tone * low;
                verts.add(coarse
                        ? new float[] { px, py, pz, (float) n[0] / len, (float) n[1] / len, (float) n[2] / len, r0, g0, b0, child }
                        : new float[] { px, py, pz, (float) n[0] / len, (float) n[1] / len, (float) n[2] / len, r0, g0, b0 });
            }
            float top = r * squash * 1.1f;
            if (!coarse && top > STEP_HEIGHT) solidRocks.add(new Solid(foot, r * 0.85f, top));
        }
    }

    /**
     * The ground's own axes at a frame's origin, in that frame: {across, the ground's normal,
     * along}, the frame's x and z laid onto the slope (from the heights step either way).
     */
    private double[][] groundBasis(SphereFrame frame, double step, java.util.Map<Long, Float> groundHeights) {
        double gx = (TerrainMesh.drawnHeight(frame.toSphere(step, 0.0, 0.0), terrain, groundHeights)
                - TerrainMesh.drawnHeight(frame.toSphere(-step, 0.0, 0.0), terrain, groundHeights)) / (2.0 * step);
        double gz = (TerrainMesh.drawnHeight(frame.toSphere(0.0, 0.0, step), terrain, groundHeights)
                - TerrainMesh.drawnHeight(frame.toSphere(0.0, 0.0, -step), terrain, groundHeights)) / (2.0 * step);
        double[] up = normalise(-gx, 1.0, -gz);
        // (x along the slope, then z square to both, by Gram-Schmidt, so the handedness stays the frame's)
        double[] x = normalise(1.0 - up[0] * up[0], -up[0] * up[1], -up[0] * up[2]);
        double xz = x[2], uz = up[2];
        double[] z = normalise(-uz * up[0] - xz * x[0], -uz * up[1] - xz * x[1], 1.0 - uz * up[2] - xz * x[2]);
        return new double[][] { x, up, z };
    }

    /**
     * How much further a rock of radius r and height h (its underside reaching about 0.9 h below
     * the slope it lies along, rounding up towards its edge) must sink so that, all round, the
     * drawn ground under it is no lower than its underside: from the ground's height in rings
     * part way and nearly all the way out. Never more than the rock's height.
     */
    private float settleDepth(SphereFrame frame, double[][] lie, float ground, float r, float h, java.util.Map<Long, Float> groundHeights) {
        float deepest = 0f;
        for (double ring : new double[] { 0.6, 0.9 }) {
            // (how far below the slope the underside is that far out, with something to spare
            // for the lumps in the rock's shape)
            double underside = 0.8 * h * (Math.sqrt(1.0 - ring * ring) - 0.1);
            for (int k = 0; k < 8; k++) {
                double angle = k * Math.PI / 4.0;
                double[] p = onGround(lie, Math.cos(angle) * r * ring, 0.0, Math.sin(angle) * r * ring);
                float below = TerrainMesh.drawnHeight(frame.toSphere(p[0], 0.0, p[2]), terrain, groundHeights) - ground;
                deepest = Math.max(deepest, (float) (p[1] - below - underside));
            }
        }
        return Math.min(deepest, h);
    }

    /** A point or direction in a ground basis's axes, in the frame's. */
    private static double[] onGround(double[][] basis, double x, double y, double z) {
        return new double[] {
                basis[0][0] * x + basis[1][0] * y + basis[2][0] * z,
                basis[0][1] * x + basis[1][1] * y + basis[2][1] * z,
                basis[0][2] * x + basis[1][2] * y + basis[2][2] * z };
    }

    private static double[] normalise(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[] { x / length, y / length, z / length };
    }

    private void dispose(GL3 gl, Tile tile) {
        for (Solid solid : tile.solids) solids.remove(solid.centre(), solid);
        if (tile.vao == 0) return;
        gl.glDeleteVertexArrays(1, new int[] { tile.vao }, 0);
        gl.glDeleteBuffers(1, new int[] { tile.vbo }, 0);
    }

    /** Drops every tile, for a new round (the pod may now be standing where rocks were). */
    public void clear(GL3 gl) {
        for (Tile tile : detailTiles.values()) dispose(gl, tile);
        detailTiles.clear();
        for (Tile tile : farTiles.values()) dispose(gl, tile);
        farTiles.clear();
        for (Future<Built> future : detailBuilding.values()) future.cancel(true);
        detailBuilding.clear();
        for (Future<Built> future : farBuilding.values()) future.cancel(true);
        farBuilding.clear();
        solids.clear();
    }

    /**
     * Draws the rocks relative to the camera at eye (looking along forward, a world
     * direction), lit by the sun in sunDirection (a world direction).
     */
    public void render(GL3 gl, Matrix4 viewProjection, SpherePosition eye, Vector3 forward, Vector3 sunDirection,
                       float[] sunColour, Vector3 ambient, Matrix4 skyRotation, Texture sky) {
        if (shader == null) return;
        shader.use(gl);
        shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        shader.setVec3(gl, "viewPos", new Vector3(0f, 0f, 0f));
        shader.setVec3(gl, "sunPos", SphereRender.sunPoint(sunDirection));
        shader.setVec3(gl, "sunColour", new Vector3(sunColour[0], sunColour[1], sunColour[2]));
        shader.setVec3(gl, "ambientLight", ambient);
        double[] up = SphereFrame.at(eye).up();
        shader.setVec3(gl, "up", new Vector3((float) up[0], (float) up[1], (float) up[2]));
        // (for the snow)
        shader.setVec3(gl, "sphereCamera", new Vector3((float) eye.x(), (float) eye.y(), (float) eye.z()));
        shader.setFloat(gl, "planetRadius", (float) Planet.radius());
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            shader.setInt(gl, "skyTexture", 2);
        }
        shader.setInt(gl, "coarse", 0);
        for (Tile tile : detailTiles.values()) draw(gl, tile, eye, forward);
        // The coarse rocks out to the horizon, except in the quarters drawn in detail
        shader.setInt(gl, "coarse", 1);
        for (Map.Entry<Long, Tile> e : farTiles.entrySet()) {
            int detailed = 0;
            for (int k = 0; k < 4; k++) {
                if (detailTiles.containsKey((e.getKey() << 2) | k)) detailed |= 1 << k;
            }
            if (detailed == 15) continue;
            shader.setInt(gl, "detailedChildren", detailed);
            draw(gl, e.getValue(), eye, forward);
        }
        gl.glBindVertexArray(0);
    }

    private void draw(GL3 gl, Tile tile, SpherePosition eye, Vector3 forward) {
        if (tile.vertexCount == 0) return;
        double dx = tile.centre.x() - eye.x(), dy = tile.centre.y() - eye.y(), dz = tile.centre.z() - eye.z();
        // (nothing of it in front of the eyes)
        if (dx * forward.x + dy * forward.y + dz * forward.z < -tile.radius) return;
        shader.setVec3(gl, "tileOffset", new Vector3((float) dx, (float) dy, (float) dz));
        gl.glBindVertexArray(tile.vao);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, tile.vertexCount);
    }

    /**
     * The altitude of the top of a rock over a place (a dome over its footprint), given the
     * ground's there; NaN if no rock stands there.
     */
    public double topAt(SpherePosition position, double ground) {
        double best = Double.NaN;
        for (Solid solid : solids.near(position, 0.0)) {
            double d = SphereMath.distance(position, solid.centre()) / solid.radius();
            if (d >= 1.0) continue;
            double top = ground + solid.top() * Math.sqrt(1.0 - d * d);
            if (Double.isNaN(best) || top > best) best = top;
        }
        return best;
    }

    /** The bigger rocks near a place, as solid circles. */
    public void obstaclesNear(SpherePosition centre, double reach, GeodesicCollision.Sink sink) {
        solids.forEachNear(centre, reach, solid -> sink.circle(solid.centre(), solid.radius(), solid.top()));
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
