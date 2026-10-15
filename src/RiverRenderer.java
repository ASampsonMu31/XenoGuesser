import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the rivers and lakes (see Rivers): each river a ribbon of water down its channel, its
 * surface at the water's level, laid over the ground where the ground as drawn rises above it
 * (a narrow stream the ground's mesh is too coarse to cut runs over it), fading at its edges,
 * from a lake's shore or its source to a lake's shore or just under the sea; each lake flat at
 * its level over its hollow, its edge following the shore smoothly and cut off square where a
 * river leaves or joins it, so lake and river meet edge to edge. The meshes are made once in
 * the background and drawn in pieces, those out of sight left out.
 */
public final class RiverRenderer {

    // Vertex: position (3), the way downstream (3), {how far its ripples are carried, across,
    // speed, edge}, {x, z on a lake's frame, how still it is (1 a lake, 0 a running river)}
    private static final int FLOATS = 13;
    // How fast the ripples' pattern moves at the speed it's carried at (world units a second; see travel)
    private static final float FLOW_DRIFT = 4f;
    // Over how far a river stills as it comes to a lake (or starts from one)
    private static final float CALM_EASE = 40f;
    // How far a river's water dips under the sea's wherever the ground as drawn is below the sea
    // (no further than just over the ground there, faded out, so no strip's left bare between them)
    private static final float SEA_DIP = 1.5f;
    // Across a river: these shares of its half width (the outermost run up its banks, faded out)
    private static final float[] ACROSS = { -1.25f, -0.6f, 0f, 0.6f, 1.25f };
    // Where it falls, rows between its points this much lower than each other at most (and no
    // more than so many between two points), so it follows the rock it falls over
    private static final float FALL_STEP = 2f;
    private static final int MOST_FALL_STEPS = 8;
    // Over these slopes of the ground under it (or as it speeds up over these speeds) it thins to a
    // sheet this deep running down the rock
    private static final float STEEP_FROM = 0.12f, STEEP_TO = 0.4f, RUSH_FROM = 6f, RUSH_TO = 13f, FALL_DEPTH = 0.2f;
    // How far above the ground as drawn the water's surface is at least (so it's never buried in it)
    private static final float ABOVE_GROUND = 0.08f;
    // Points of a river drawn together as one piece (for leaving out those out of sight)
    private static final int PIECE_POINTS = 48;
    // How many sides each of a lake's texels' patches of water has
    private static final int LAKE_SIDES = 8;

    /** A piece drawn on its own: its indices, and the ball holding it. */
    private record Piece(int firstIndex, int indexCount, double cx, double cy, double cz, double radius) {
    }

    /** The meshes as made (off the GL thread). */
    public static final class Built {
        final float[] vertices;
        final int[] indices;
        final List<Piece> pieces;

        Built(float[] vertices, int[] indices, List<Piece> pieces) {
            this.vertices = vertices;
            this.indices = indices;
            this.pieces = pieces;
        }
    }

    private int vao, vbo, ebo;
    private List<Piece> pieces = List.of();

    // ------------------------------------------------------------------ making the meshes

    /** Makes the rivers' and lakes' meshes. Safe off the GL thread (slow: ground heights all along them). */
    public static Built build(Rivers rivers, PerlinNoise noise) {
        double radius = Planet.radius();
        List<float[]> pieceVertices = java.util.Collections.synchronizedList(new ArrayList<>());
        List<int[]> pieceIndices = java.util.Collections.synchronizedList(new ArrayList<>());
        List<LakeFrame> frames = new ArrayList<>();
        for (Rivers.Lake lake : rivers.lakes()) frames.add(LakeFrame.of(lake, radius));
        // (each lake's level as drawn, which the rivers meet it at: see buildLake)
        float[] drawnLevels = new float[rivers.lakes().size()];
        List<Object[]> lakes = java.util.stream.IntStream.range(0, rivers.lakes().size()).parallel()
                .mapToObj(l -> buildLake(rivers, l, frames.get(l), noise, radius, drawnLevels)).toList();
        for (Object[] m : lakes) {
            if (((int[]) m[1]).length == 0) continue;
            pieceVertices.add((float[]) m[0]);
            pieceIndices.add((int[]) m[1]);
        }
        List<Rivers.Reach> reaches = rivers.reaches();
        // (each reach on its own thread, each with its own store of the ground's vertex heights)
        List<Shape> shapes = shapes(reaches, radius);
        for (Shape shape : shapes) shape.lakeLevels = drawnLevels;
        List<Object[]> made = shapes.parallelStream().map(shape -> buildReach(shape, frames, rivers.seaLevel(), noise, radius)).toList();
        for (Object[] m : made) {
            @SuppressWarnings("unchecked")
            List<float[]> v = (List<float[]>) m[0];
            @SuppressWarnings("unchecked")
            List<int[]> i = (List<int[]>) m[1];
            pieceVertices.addAll(v);
            pieceIndices.addAll(i);
        }
        // All in one buffer, each piece's indices offset to its vertices
        int vertexFloats = 0, indexCount = 0;
        for (int k = 0; k < pieceVertices.size(); k++) {
            vertexFloats += pieceVertices.get(k).length;
            indexCount += pieceIndices.get(k).length;
        }
        float[] vertices = new float[vertexFloats];
        int[] indices = new int[indexCount];
        List<Piece> pieces = new ArrayList<>();
        int vOffset = 0, iOffset = 0;
        for (int k = 0; k < pieceVertices.size(); k++) {
            float[] v = pieceVertices.get(k);
            int[] idx = pieceIndices.get(k);
            System.arraycopy(v, 0, vertices, vOffset, v.length);
            int base = vOffset / FLOATS;
            for (int i = 0; i < idx.length; i++) indices[iOffset + i] = idx[i] + base;
            double cx = 0, cy = 0, cz = 0;
            int n = v.length / FLOATS;
            for (int j = 0; j < n; j++) {
                cx += v[j * FLOATS];
                cy += v[j * FLOATS + 1];
                cz += v[j * FLOATS + 2];
            }
            cx /= n;
            cy /= n;
            cz /= n;
            double reach = 0;
            for (int j = 0; j < n; j++) {
                double dx = v[j * FLOATS] - cx, dy = v[j * FLOATS + 1] - cy, dz = v[j * FLOATS + 2] - cz;
                reach = Math.max(reach, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            pieces.add(new Piece(iOffset, idx.length, cx, cy, cz, reach));
            vOffset += v.length;
            iOffset += idx.length;
        }
        System.out.printf("[RIVERS] Water meshes: %d pieces, %d triangles%n", pieces.size(), indexCount / 3);
        return new Built(vertices, indices, pieces);
    }

    /**
     * How far along a reach its ripples have been carried at each point (world units at
     * FLOW_DRIFT a second): the time the water takes to get there at its own speed, so the
     * ripples are carried down it everywhere at that speed, stretched out where it runs fast and
     * bunched where it's slow, never torn where the speed changes. (Its speed as shown is eased,
     * so the stretching stays within bounds.)
     */
    private static float[] travel(Rivers.Reach reach) {
        float[] t = new float[reach.size()];
        for (int k = 1; k < reach.size(); k++) {
            float speed = Math.max(1.2f, 0.5f * (reach.speed[k - 1] + reach.speed[k]));
            float shown = (float) Math.sqrt(speed * FLOW_DRIFT);
            t[k] = t[k - 1] + (reach.along[k] - reach.along[k - 1]) * FLOW_DRIFT / shown;
        }
        return t;
    }

    /** A reach's water: its stretches out of lakes, in pieces of PIECE_POINTS points ({vertex lists, index lists}). */
    private static Object[] buildReach(Shape shape, List<LakeFrame> frames, float seaLevel, PerlinNoise noise, double radius) {
        Rivers.Reach reach = shape.reach;
        java.util.Map<Long, Float> ground = new java.util.HashMap<>();
        float[] under = new float[reach.size()];
        java.util.Arrays.fill(under, Float.NaN);
        List<float[]> vertexPieces = new ArrayList<>();
        List<int[]> indexPieces = new ArrayList<>();
        int n = shape.last + 1;
        int k = 0;
        while (k < n) {
            // A stretch out of the lakes (from and to the points on their shores, where it meets them)
            while (k < n && reach.inLake[k] && (k + 1 >= n || reach.inLake[k + 1])) k++;
            if (k >= n) break;
            int start = k;
            int end = start + 1;
            while (end < n && !reach.inLake[end]) end++;
            end = Math.min(n - 1, end);
            // In pieces sharing their end points
            for (int from = start; from < end; from += PIECE_POINTS) {
                int to = Math.min(end, from + PIECE_POINTS);
                Object[] piece = ribbon(shape, start, end, from, to, frames, seaLevel, noise, radius, ground, under);
                vertexPieces.add((float[]) piece[0]);
                indexPieces.add((int[]) piece[1]);
            }
            k = end + 1;
        }
        return new Object[] { vertexPieces, indexPieces };
    }

    /**
     * The ribbon of water over points from to to (inclusive) of a reach's stretch start to end.
     * Where the stretch meets a lake, its end is on the lake's shore at the lake's level (the
     * lake cut off along the same line), and over the last CALM_EASE it stills to look as the
     * lake does, in the lake's own frame, so the one runs into the other without a seam. Wherever
     * the sea is over the ground it dips under the sea's water, which hides it from the coast on.
     * Where it falls steeply it's laid in close rows and thins to a sheet down the rock, never
     * standing off it; from a source it fades in as it rises.
     */
    private static Object[] ribbon(Shape shape, int start, int end, int from, int to, List<LakeFrame> frames,
            float seaLevel, PerlinNoise noise, double radius, java.util.Map<Long, Float> ground, float[] under) {
        Rivers.Reach reach = shape.reach;
        // The rows across it: at its points, and between them wherever it falls steeply, closely
        // enough to follow the rock it falls over ({point k, share t of the way on to k + 1})
        List<double[]> stations = new ArrayList<>();
        for (int k = from; k < to; k++) {
            float drop = Math.abs(centreHeight(reach, k, noise, ground, under) - centreHeight(reach, k + 1, noise, ground, under))
                    + Math.abs(shape.surface[k] - shape.surface[k + 1]);
            int steps = Math.max(1, Math.min(MOST_FALL_STEPS, (int) Math.ceil(drop / FALL_STEP)));
            for (int s = 0; s < steps; s++) stations.add(new double[] { k, s / (double) steps });
        }
        stations.add(new double[] { to, 0.0 });
        int rows = stations.size(), cols = ACROSS.length;
        // Each row's middle, its way down and across, and the ground as drawn under its middle
        double[][] middle = new double[rows][], down = new double[rows][], side = new double[rows][];
        float[] bed = new float[rows], along = new float[rows];
        for (int i = 0; i < rows; i++) {
            int k = (int) stations.get(i)[0];
            double t = stations.get(i)[1];
            double[] p = reach.points[k], q = reach.points[Math.min(reach.size() - 1, k + 1)];
            middle[i] = unit(p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, p[2] + (q[2] - p[2]) * t);
            double[] ta = shape.tangent(k), tb = shape.tangent(Math.min(reach.size() - 1, k + 1));
            double[] m = middle[i];
            double tx = ta[0] + (tb[0] - ta[0]) * t, ty = ta[1] + (tb[1] - ta[1]) * t, tz = ta[2] + (tb[2] - ta[2]) * t;
            double up = tx * m[0] + ty * m[1] + tz * m[2];
            double[] d = unit(tx - up * m[0], ty - up * m[1], tz - up * m[2]);
            down[i] = d == null ? new double[] { 1, 0, 0 } : d;
            // (across: square to up and to the way down)
            side[i] = new double[] { m[1] * down[i][2] - m[2] * down[i][1], m[2] * down[i][0] - m[0] * down[i][2], m[0] * down[i][1] - m[1] * down[i][0] };
            bed[i] = TerrainMesh.drawnHeight(new GeodesicChunkGrid.Direction(m[0], m[1], m[2]), noise, ground);
            along[i] = lerp(reach.along, k, t);
        }
        float[] v = new float[rows * cols * FLOATS];
        int o = 0;
        for (int i = 0; i < rows; i++) {
            int k = (int) stations.get(i)[0];
            double t = stations.get(i)[1];
            boolean shore = t == 0.0 && reach.inLake[k];
            float half = lerp(shape.half, k, t), surface = lerp(shape.surface, k, t);
            // Never deeper over the ground as drawn than its own water is over its bed (though the
            // ground may be lower: another river's channel, a fall from a lake's lip), and where
            // it falls steeply, a thin sheet running down the rock, never standing off it
            boolean joint = t == 0.0 && ((k == 0 && shape.jointStart) || (k == reach.size() - 1 && shape.jointEnd));
            float slope = joint ? 0f : t == 0.0 ? Math.max(slope(reach, k - 1, noise, ground, under), slope(reach, k, noise, ground, under))
                    : slope(reach, k, noise, ground, under);
            float sheet = (float) Math.max(smooth((slope - STEEP_FROM) / (STEEP_TO - STEEP_FROM)),
                    smooth((lerp(shape.speed, k, t) - RUSH_FROM) / (RUSH_TO - RUSH_FROM)));
            float own = Math.max(FALL_DEPTH, surface - (lerp(shape.rim, k, t) - lerp(shape.depth, k, t)));
            float deepest = FALL_DEPTH + (1f - sheet) * (own - FALL_DEPTH);
            // Stilling towards a lake at either end (the nearer), rising out of the ground at a source
            LakeFrame calmIn = null;
            float still = 0f;
            for (int e : new int[] { start, end }) {
                if (!reach.inLake[e]) continue;
                float s = 1f - (float) smooth(Math.abs(along[i] - reach.along[e]) / CALM_EASE);
                if (s > still) {
                    still = s;
                    calmIn = frames.get(reach.lake[e]);
                }
            }
            float rises = lerp(reach.rises, k, t);
            double[] m = middle[i], sd = side[i], dn = down[i];
            for (float f : ACROSS) {
                double off = f * half / radius;
                double[] d = unit(m[0] + sd[0] * off, m[1] + sd[1] * off, m[2] + sd[2] * off);
                float level;
                boolean underSea = false;
                if (shore) {
                    // (on a lake's shore: at its level, the ground hiding what's past the water's edge)
                    level = shape.lakeLevels[reach.lake[k]];
                } else {
                    float drawn = f == 0f ? bed[i] : TerrainMesh.drawnHeight(new GeodesicChunkGrid.Direction(d[0], d[1], d[2]), noise, ground);
                    // (where the sea is over the ground, under it: the river ends where the sea's water begins;
                    // elsewhere at its surface, but over each place across it no deeper than deepest)
                    underSea = drawn < seaLevel;
                    level = underSea ? Math.max(drawn + ABOVE_GROUND, seaLevel - SEA_DIP)
                            : drawn + ABOVE_GROUND + Math.max(0f, Math.min(surface - drawn - ABOVE_GROUND, deepest));
                }
                double r = radius + level;
                v[o++] = (float) (d[0] * r);
                v[o++] = (float) (d[1] * r);
                v[o++] = (float) (d[2] * r);
                v[o++] = (float) dn[0];
                v[o++] = (float) dn[1];
                v[o++] = (float) dn[2];
                v[o++] = lerp(shape.travel, k, t);
                v[o++] = f * half;
                v[o++] = lerp(shape.speed, k, t);
                // (its edge, nearer the middle the less it has risen, so a source's water fades in)
                float edge = Math.abs(f) / ACROSS[ACROSS.length - 1];
                v[o++] = underSea ? 1f : 1f - (1f - edge) * rises;
                double[] calm = calmIn == null ? new double[2] : calmIn.toPlane(d[0], d[1], d[2]);
                v[o++] = (float) calm[0];
                v[o++] = (float) calm[1];
                v[o++] = still;
            }
        }
        int[] idx = new int[(rows - 1) * (cols - 1) * 6];
        int i = 0;
        for (int r = 0; r + 1 < rows; r++) {
            for (int c = 0; c + 1 < cols; c++) {
                int p00 = r * cols + c, p01 = p00 + 1, p10 = p00 + cols, p11 = p10 + 1;
                idx[i++] = p00; idx[i++] = p10; idx[i++] = p11;
                idx[i++] = p00; idx[i++] = p11; idx[i++] = p01;
            }
        }
        return new Object[] { v, idx };
    }

    /** The ground as drawn under a reach's point k (worked out once, kept in under). */
    private static float centreHeight(Rivers.Reach reach, int k, PerlinNoise noise, java.util.Map<Long, Float> ground, float[] under) {
        if (Float.isNaN(under[k])) {
            double[] p = reach.points[k];
            under[k] = TerrainMesh.drawnHeight(new GeodesicChunkGrid.Direction(p[0], p[1], p[2]), noise, ground);
        }
        return under[k];
    }

    /** How steeply the ground as drawn falls (or rises) between a reach's points k and k + 1 (0 past its ends). */
    private static float slope(Rivers.Reach reach, int k, PerlinNoise noise, java.util.Map<Long, Float> ground, float[] under) {
        if (k < 0 || k + 1 >= reach.size()) return 0f;
        float run = Math.max(0.5f, reach.along[k + 1] - reach.along[k]);
        return Math.abs(centreHeight(reach, k, noise, ground, under) - centreHeight(reach, k + 1, noise, ground, under)) / run;
    }

    /** The way down a reach at point k (from its neighbours either side). */
    private static double[] tangent(Rivers.Reach reach, int k) {
        double[] a = reach.points[Math.max(0, k - 1)], b = reach.points[Math.min(reach.size() - 1, k + 1)];
        return new double[] { b[0] - a[0], b[1] - a[1], b[2] - a[2] };
    }

    /**
     * A reach's water as it's drawn: its own widths, levels, speeds and how far its ripples have
     * come (carried on from the river it continues), but where it runs on into the reach below a
     * meeting as the main river, its last row the same as that reach's first (so the two join
     * without a gap or a seam), and where it's a stream joining a river there, ending at last,
     * at the river's edge, not out over the river's own water.
     */
    private static final class Shape {
        final Rivers.Reach reach;
        final float[] half, surface, rim, depth, speed;
        float[] travel, lakeLevels;
        double[] endTangent;
        boolean jointStart, jointEnd;
        int last;

        Shape(Rivers.Reach reach) {
            this.reach = reach;
            half = reach.halfWidth.clone();
            surface = reach.surface.clone();
            rim = reach.rim.clone();
            depth = reach.depth.clone();
            speed = reach.speed.clone();
            last = reach.size() - 1;
        }

        double[] tangent(int k) {
            return k == reach.size() - 1 && endTangent != null ? endTangent : RiverRenderer.tangent(reach, k);
        }
    }

    // A stream joining a river ends this share of the river's half width out from its middle
    private static final float JOIN_REACH = 0.9f;

    /** Each reach's shape as drawn (see Shape), in the order the reaches are (each after those running into it). */
    private static List<Shape> shapes(List<Rivers.Reach> reaches, double radius) {
        java.util.Map<Integer, Rivers.Reach> below = new java.util.HashMap<>(), mainInto = new java.util.HashMap<>();
        for (Rivers.Reach r : reaches) {
            if (r.startsAt >= 0) below.put(r.startsAt, r);
            if (r.mainInflow) mainInto.put(r.endsAt, r);
        }
        java.util.Map<Integer, Float> travelled = new java.util.HashMap<>();
        List<Shape> shapes = new ArrayList<>();
        for (Rivers.Reach r : reaches) {
            Shape shape = new Shape(r);
            int n = r.size(), last = n - 1;
            shape.travel = travel(r);
            float offset = r.startsAt >= 0 ? travelled.getOrDefault(r.startsAt, 0f) : 0f;
            for (int k = 0; k < n; k++) shape.travel[k] += offset;
            shape.jointStart = r.startsAt >= 0 && mainInto.containsKey(r.startsAt) && !r.inLake[0];
            Rivers.Reach next = r.endsAt >= 0 ? below.get(r.endsAt) : null;
            if (next != null && !r.inLake[last] && !next.inLake[0]) {
                if (r.mainInflow) {
                    shape.half[last] = next.halfWidth[0];
                    shape.surface[last] = next.surface[0];
                    shape.rim[last] = next.rim[0];
                    shape.depth[last] = next.depth[0];
                    shape.speed[last] = next.speed[0];
                    shape.endTangent = tangent(next, 0);
                    shape.jointEnd = true;
                } else {
                    // (back to the river's edge: off the river below and the main one running in)
                    Rivers.Reach main = mainInto.get(r.endsAt);
                    double edge = next.halfWidth[0] * JOIN_REACH;
                    while (shape.last > 1 && Math.min(distanceTo(r.points[shape.last], next, 0, 4, radius),
                            main == null ? Double.MAX_VALUE : distanceTo(r.points[shape.last], main, main.size() - 5, main.size() - 1, radius)) < edge) {
                        shape.last--;
                    }
                }
            }
            if (r.mainInflow && r.endsAt >= 0) travelled.put(r.endsAt, shape.travel[last]);
            shapes.add(shape);
        }
        return shapes;
    }

    /** How far a point is from a reach's line between its points from and to (world units). */
    private static double distanceTo(double[] p, Rivers.Reach r, int from, int to, double radius) {
        from = Math.max(0, from);
        to = Math.min(r.size() - 1, to);
        double best = Double.MAX_VALUE;
        for (int k = from; k < to; k++) {
            double[] a = r.points[k], b = r.points[k + 1];
            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], l2 = ux * ux + uy * uy + uz * uz;
            double f = l2 > 0 ? Math.max(0, Math.min(1, ((p[0] - a[0]) * ux + (p[1] - a[1]) * uy + (p[2] - a[2]) * uz) / l2)) : 0;
            double dx = p[0] - a[0] - ux * f, dy = p[1] - a[1] - uy * f, dz = p[2] - a[2] - uz * f;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz) * radius);
        }
        return best;
    }

    /** A value a share t of the way from values[k] to values[k + 1]. */
    private static float lerp(float[] values, int k, double t) {
        if (t == 0.0 || k + 1 >= values.length) return values[k];
        return (float) (values[k] + (values[k + 1] - values[k]) * t);
    }

    private static double smooth(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    // Lakes' water is laid on a grid this many world units apart (the grid Rivers checks their levels on)
    private static final float LAKE_STEP = Rivers.LAKE_GRID;
    // How far past the shore (in height: where the ground is up to this much above the water) the
    // water's sheet reaches, under the ground, so its edge is hidden
    private static final float SHORE_TUCK = 1.0f;
    // How many times the way between two of the grid's points is halved to find the shore between them
    private static final int SHORE_HALVINGS = 6;
    // How many grid squares past its own water (as Rivers found it) a lake's water may reach, through
    // the rivers' channels cut into its shore
    private static final int BEYOND_OWN = 3;
    // A lake's sheet is drawn at least this much below where it would spill over its rim as drawn
    private static final float POOL_MARGIN = 0.1f;
    // How much a world unit of distance counts for against height, where the water's edge is set by
    // how far it is from something (its texels, a river's channel) rather than by the ground
    private static final float FIELD_PER_UNIT = 0.05f;

    /** A lake's flat frame (the one its water grid is laid on): its middle, the ways east and north there, and points on it as {x, z} in world units. */
    private record LakeFrame(double[] c, double[] east, double[] north, double radius) {
        static LakeFrame of(Rivers.Lake lake, double radius) {
            return new LakeFrame(lake.middle, lake.east, lake.north, radius);
        }

        double[] toPlane(double x, double y, double z) {
            double d = x * c[0] + y * c[1] + z * c[2];
            return new double[] { radius * (x * east[0] + y * east[1] + z * east[2]) / d,
                radius * (x * north[0] + y * north[1] + z * north[2]) / d };
        }

        double[] toPlane(double[] p) {
            return toPlane(p[0], p[1], p[2]);
        }

        double[] direction(double x, double z) {
            return unit(c[0] + (east[0] * x + north[0] * z) / radius, c[1] + (east[1] * x + north[1] * z) / radius,
                    c[2] + (east[2] * x + north[2] * z) / radius);
        }
    }

    /**
     * A lake's water: one flat sheet at its level over the ground below that level joined to its
     * own texels, its edge followed smoothly (the grid's squares cut along where the water meets
     * the ground, found between the grid's points, and reaching a little under the ground so the
     * ground hides it). It never reaches further from its texels than Rivers lets its level spill,
     * and where a river leaves it or comes into it, it stops at the line across the river where
     * the river's own water starts (keeping out of the river's channel past it), so the two meet
     * edge to edge and never overlap.
     */
    private static Object[] buildLake(Rivers rivers, int lakeIndex, LakeFrame frame, PerlinNoise noise, double radius, float[] drawnLevels) {
        Rivers.Lake lake = rivers.lakes().get(lakeIndex);
        // Where rivers cross its shore, on its frame (its water grid's)
        double extent = lake.extent;
        List<double[][]> cuts = new ArrayList<>();
        for (Rivers.Cut cut : lake.cuts) {
            double[] e = frame.toPlane(cut.point);
            double step = 1.0 / radius;
            double[] ahead = frame.toPlane(cut.point[0] + cut.tangent[0] * step, cut.point[1] + cut.tangent[1] * step,
                    cut.point[2] + cut.tangent[2] * step);
            double ax = ahead[0] - e[0], az = ahead[1] - e[1], al = Math.hypot(ax, az);
            if (al < 1e-9) continue;
            double[][] c = new double[1 + cut.corridor.length][];
            // (its point, the way downstream and whether it's an outlet; then the river's way, {x, z, width})
            c[0] = new double[] { e[0], e[1], ax / al, az / al, cut.outlet ? 1 : 0 };
            for (int k = 0; k < cut.corridor.length; k++) {
                double[] q = frame.toPlane(cut.corridor[k]);
                c[1 + k] = new double[] { q[0], q[1], cut.widths[k] };
            }
            cuts.add(c);
        }
        // How far into the water's sheet a place is (in height; below zero, dry): how deep the
        // water is there, but reaching a little under the shore, and never into a river's channel
        // past where it leaves or joins it
        int n = lake.size;
        java.util.Map<Long, Float> heights = new java.util.HashMap<>();
        float[] ground = new float[1];
        // (its level as drawn: see below)
        float[] level = { lake.level };
        Sheet sheet = (x, z, tuck) -> {
            ground[0] = Float.NaN;
            float w = Float.MAX_VALUE;
            for (double[][] c : cuts) w = Math.min(w, (float) (cutSide(c, x, z) * FIELD_PER_UNIT));
            if (w <= 0f) return w;
            double[] d = frame.direction(x, z);
            ground[0] = TerrainMesh.drawnHeight(new GeodesicChunkGrid.Direction(d[0], d[1], d[2]), noise, heights);
            return Math.min(w, level[0] + tuck - ground[0]);
        };
        // How many grid squares each point is from its own water (as Rivers found it, on the
        // ground before the rivers' channels were cut), out to a square past BEYOND_OWN
        int[] queue = new int[n * n];
        int head = 0, tail = 0;
        int[] away = new int[n * n];
        java.util.Arrays.fill(away, Integer.MAX_VALUE);
        for (int g = 0; g < n * n; g++) {
            if (lake.water[g] > 0f) {
                away[g] = 0;
                queue[tail++] = g;
            }
        }
        while (head < tail) {
            int g = queue[head++];
            if (away[g] > BEYOND_OWN) continue;
            int i = g % n, j = g / n;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    int a = i + di, b = j + dj;
                    if (a < 0 || a >= n || b < 0 || b >= n || away[b * n + a] <= away[g] + 1) continue;
                    away[b * n + a] = away[g] + 1;
                    queue[tail++] = b * n + a;
                }
            }
        }
        // At each of the grid's points near enough its own water: the sheet, and how deep the water is
        float[] depth = new float[n * n], wetness = new float[n * n], under = new float[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int g = j * n + i;
                under[g] = Float.NaN;
                if (away[g] > BEYOND_OWN + 1) {
                    depth[g] = wetness[g] = -1f;
                    continue;
                }
                double x = -extent + i * LAKE_STEP, z = -extent + j * LAKE_STEP;
                wetness[g] = sheet.at(x, z, SHORE_TUCK);
                under[g] = ground[0];
                depth[g] = Float.isNaN(ground[0]) ? wetness[g] : Math.min(wetness[g], level[0] - ground[0]);
            }
        }
        // Its level as drawn: no higher than the water would pool on this very ground (finer than
        // the ground Rivers set its level by, and cut by the rivers' channels), so it never reaches
        // out past its rim to be cut off in the air. (A priority flood, inwards from beyond its
        // reach, the rivers' ways out of it walls.)
        float pooled = poolingLevel(lake, n, away, under);
        if (pooled < level[0]) {
            level[0] = pooled;
            for (int g = 0; g < n * n; g++) {
                if (Float.isNaN(under[g])) continue;
                // (the level only comes down, so the sheet's limited by the lower one as well as by the rivers' ways out)
                wetness[g] = Math.min(wetness[g], level[0] + SHORE_TUCK - under[g]);
                depth[g] = Math.min(wetness[g], level[0] - under[g]);
            }
        }
        float[] own = wetness.clone();
        // Only the water joined to its own, and never more than BEYOND_OWN grid squares from that
        // (so wherever a channel would open the way to lower ground, it can't run far)...
        boolean[] joined = new boolean[n * n];
        head = tail = 0;
        for (int g = 0; g < n * n; g++) {
            if (lake.water[g] > 0f && depth[g] > 0f) {
                joined[g] = true;
                queue[tail++] = g;
            }
        }
        while (head < tail) {
            int g = queue[head++];
            int i = g % n, j = g / n;
            int[] next = { i > 0 ? g - 1 : -1, i + 1 < n ? g + 1 : -1, j > 0 ? g - n : -1, j + 1 < n ? g + n : -1 };
            for (int q : next) {
                if (q < 0 || joined[q] || depth[q] <= 0f || away[q] > BEYOND_OWN) continue;
                joined[q] = true;
                queue[tail++] = q;
            }
        }
        // ...its sheet reaching a grid square under the shore round it, no further (and never out
        // over a hollow below its level that it isn't joined to: its edge put between the two)
        for (int g = 0; g < n * n; g++) {
            if (joined[g] || wetness[g] <= 0f) continue;
            if (depth[g] > 0f) {
                wetness[g] = -Math.max(0.5f, depth[g]);
                continue;
            }
            int i = g % n, j = g / n;
            boolean beside = false;
            for (int dj = -1; dj <= 1 && !beside; dj++) {
                for (int di = -1; di <= 1 && !beside; di++) {
                    int a = i + di, b = j + dj;
                    beside = a >= 0 && a < n && b >= 0 && b < n && joined[b * n + a];
                }
            }
            if (!beside) wetness[g] = -0.01f;
        }
        // Each square as two triangles, each cut along where the wetness crosses zero
        drawnLevels[lakeIndex] = level[0];
        LakeMesh mesh = new LakeMesh(frame, level[0], n, extent, wetness, own, sheet);
        for (int j = 0; j + 1 < n; j++) {
            for (int i = 0; i + 1 < n; i++) {
                int g = j * n + i;
                mesh.triangle(g, g + 1, g + n + 1);
                mesh.triangle(g, g + n + 1, g + n);
            }
        }
        return mesh.finish();
    }

    /**
     * The highest a lake's water can stand on the ground as drawn (under, on its grid; NaN where
     * it's not looked at or a river's way out of it) without running away: a priority flood from
     * the points more than BEYOND_OWN from its own water (away), read at its lowest own point.
     */
    private static float poolingLevel(Rivers.Lake lake, int n, int[] away, float[] under) {
        float[] filled = new float[n * n];
        boolean[] done = new boolean[n * n];
        java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>((a, b) -> Float.compare(Float.intBitsToFloat((int) a[0]), Float.intBitsToFloat((int) b[0])));
        int lowest = -1;
        for (int g = 0; g < n * n; g++) {
            if (Float.isNaN(under[g]) || away[g] > BEYOND_OWN) continue;
            if (lake.water[g] > 0f && (lowest < 0 || under[g] < under[lowest])) lowest = g;
            int i = g % n, j = g / n;
            boolean edge = i == 0 || j == 0 || i == n - 1 || j == n - 1;
            for (int q : new int[] { g - 1, g + 1, g - n, g + n }) {
                if (edge || (away[q] > BEYOND_OWN && !Float.isNaN(under[q]))) edge = true;
            }
            if (edge) {
                done[g] = true;
                filled[g] = under[g];
                open.add(new long[] { Float.floatToIntBits(under[g]), g });
            }
        }
        if (lowest < 0) return Float.MAX_VALUE;
        while (!open.isEmpty()) {
            int g = (int) open.poll()[1];
            int i = g % n, j = g / n;
            int[] next = { i > 0 ? g - 1 : -1, i + 1 < n ? g + 1 : -1, j > 0 ? g - n : -1, j + 1 < n ? g + n : -1 };
            for (int q : next) {
                if (q < 0 || done[q] || Float.isNaN(under[q]) || away[q] > BEYOND_OWN) continue;
                done[q] = true;
                filled[q] = Math.max(under[q], filled[g]);
                open.add(new long[] { Float.floatToIntBits(filled[q]), q });
            }
        }
        return done[lowest] ? filled[lowest] - POOL_MARGIN : Float.MAX_VALUE;
    }

    /**
     * How far a point (x, z on a lake's frame) is on the lake's side of where a river crosses its
     * shore: the line across the river there, and only within the river's way (its corridor of
     * points, out to their widths either side) past it; negative on the river's side.
     */
    private static double cutSide(double[][] cut, double x, double z) {
        double[] c = cut[0];
        double ahead = (x - c[0]) * c[2] + (z - c[1]) * c[3];
        double lakeward = c[4] > 0.5 ? -ahead : ahead;
        double nearest = Double.MAX_VALUE;
        for (int k = 1; k < cut.length; k++) {
            double[] p = cut[k], q = cut[Math.min(cut.length - 1, k + 1)];
            double ux = q[0] - p[0], uz = q[1] - p[1], l2 = ux * ux + uz * uz;
            double f = l2 > 0 ? Math.max(0, Math.min(1, ((x - p[0]) * ux + (z - p[1]) * uz) / l2)) : 0;
            double ex = x - p[0] - ux * f, ez = z - p[1] - uz * f;
            nearest = Math.min(nearest, Math.sqrt(ex * ex + ez * ez) - (p[2] + (q[2] - p[2]) * f));
        }
        return Math.max(lakeward, nearest);
    }

    /** How far into a lake's sheet of water a place (x, z on its frame) is, reaching tuck under its shore. */
    @FunctionalInterface
    private interface Sheet {
        float at(double x, double z, float tuck);
    }

    /**
     * A lake's sheet of water as it's made: its vertices (shared, by grid point or grid edge) and
     * triangles. Where its edge crosses a square's side between a point in the water and one
     * truly dry (own: the sheet there, not what was made of it), it's found on the ground itself
     * (halving the way between them a few times), so it follows a steep shore closely rather
     * than cutting the corner the grid's points would.
     */
    private static final class LakeMesh {
        private final LakeFrame frame;
        private final double r;
        private final int n;
        private final double extent;
        private final float[] wetness, own;
        private final Sheet sheet;
        private final List<Float> verts = new ArrayList<>();
        private final List<Integer> idx = new ArrayList<>();
        private final java.util.Map<Long, Integer> vertexOf = new java.util.HashMap<>();

        LakeMesh(LakeFrame frame, float level, int n, double extent, float[] wetness, float[] own, Sheet sheet) {
            this.frame = frame;
            this.r = frame.radius() + level;
            this.n = n;
            this.extent = extent;
            this.wetness = wetness;
            this.own = own;
            this.sheet = sheet;
        }

        /** The wet part of a triangle of grid points (0, 3 or 4 corners), as triangles. */
        void triangle(int a, int b, int c) {
            if (wetness[a] <= 0f && wetness[b] <= 0f && wetness[c] <= 0f) return;
            int[] corner = { a, b, c };
            int[] poly = new int[4];
            int count = 0;
            for (int e = 0; e < 3; e++) {
                int p = corner[e], q = corner[(e + 1) % 3];
                boolean pWet = wetness[p] > 0f, qWet = wetness[q] > 0f;
                if (pWet) poly[count++] = vertex(p, p);
                if (pWet != qWet) poly[count++] = pWet ? vertex(p, q) : vertex(q, p);
            }
            for (int k = 1; k + 1 < count; k++) {
                idx.add(poly[0]);
                idx.add(poly[k]);
                idx.add(poly[k + 1]);
            }
        }

        /** The vertex at grid point p (q = p), or where the edge is between wet grid point p and dry q (made once, shared). */
        private int vertex(int p, int q) {
            long key = p == q ? p : (long) Math.min(p, q) * n * n + Math.max(p, q) + (long) n * n;
            Integer v = vertexOf.get(key);
            if (v != null) return v;
            v = verts.size() / FLOATS;
            vertexOf.put(key, v);
            double px = -extent + (p % n) * LAKE_STEP, pz = -extent + (p / n) * LAKE_STEP;
            double qx = -extent + (q % n) * LAKE_STEP, qz = -extent + (q / n) * LAKE_STEP;
            double t = 0.0;
            if (p != q) {
                t = wetness[p] / (double) (wetness[p] - wetness[q]);
                if (own[p] > 0f && own[q] <= 0f) {
                    double wet = 0.0, dry = 1.0;
                    for (int k = 0; k < SHORE_HALVINGS; k++) {
                        double mid = k == 0 ? t : (wet + dry) * 0.5;
                        if (sheet.at(px + (qx - px) * mid, pz + (qz - pz) * mid, SHORE_TUCK) > 0f) wet = mid;
                        else dry = mid;
                    }
                    t = (wet + dry) * 0.5;
                }
            }
            double x = px + (qx - px) * t, z = pz + (qz - pz) * t;
            double[] d = frame.direction(x, z);
            verts.add((float) (d[0] * r)); verts.add((float) (d[1] * r)); verts.add((float) (d[2] * r));
            double[] east = frame.east();
            verts.add((float) east[0]); verts.add((float) east[1]); verts.add((float) east[2]);
            verts.add(0f); verts.add(0f); verts.add(0f); verts.add(0f);
            verts.add((float) x); verts.add((float) z); verts.add(1f);
            return v;
        }

        Object[] finish() {
            float[] v = new float[verts.size()];
            for (int k = 0; k < v.length; k++) v[k] = verts.get(k);
            int[] ix = new int[idx.size()];
            for (int k = 0; k < ix.length; k++) ix[k] = idx.get(k);
            return new Object[] { v, ix };
        }
    }

    private static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return l < 1e-12 ? null : new double[] { x / l, y / l, z / l };
    }

    // ------------------------------------------------------------------ drawing

    /** Puts the meshes up on the GPU. */
    public void upload(GL3 gl, Built built) {
        int[] ids = new int[1];
        gl.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        gl.glBindVertexArray(vao);
        gl.glGenBuffers(1, ids, 0);
        vbo = ids[0];
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, built.vertices.length * 4L, FloatBuffer.wrap(built.vertices), GL.GL_STATIC_DRAW);
        int stride = FLOATS * 4;
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, stride, 0);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, stride, 3 * 4);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(2, 4, GL.GL_FLOAT, false, stride, 6 * 4);
        gl.glEnableVertexAttribArray(2);
        gl.glVertexAttribPointer(3, 3, GL.GL_FLOAT, false, stride, 10 * 4);
        gl.glEnableVertexAttribArray(3);
        gl.glGenBuffers(1, ids, 0);
        ebo = ids[0];
        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, ebo);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, built.indices.length * 4L, IntBuffer.wrap(built.indices), GL.GL_STATIC_DRAW);
        gl.glBindVertexArray(0);
        pieces = built.pieces;
    }

    public boolean ready() {
        return vao != 0;
    }

    /** Which pieces to draw: whether a ball (its centre dx, dy, dz from the eyes, its distance squared and radius) may be seen. */
    @FunctionalInterface
    public interface Visible {
        boolean test(double dx, double dy, double dz, double distanceSquared, double radius);
    }

    /** Draws the pieces that may be seen, with the river shader already set up and in use. */
    public void render(GL3 gl, SpherePosition eye, double drawDistance, Visible visible) {
        if (vao == 0) return;
        gl.glBindVertexArray(vao);
        for (Piece p : pieces) {
            double dx = p.cx - eye.x(), dy = p.cy - eye.y(), dz = p.cz - eye.z();
            double d2 = dx * dx + dy * dy + dz * dz, reach = drawDistance + p.radius;
            if (d2 > reach * reach || !visible.test(dx, dy, dz, d2, p.radius)) continue;
            gl.glDrawElements(GL.GL_TRIANGLES, p.indexCount, GL.GL_UNSIGNED_INT, p.firstIndex * 4L);
        }
        gl.glBindVertexArray(0);
    }

    public void dispose(GL3 gl) {
        if (vao == 0) return;
        gl.glDeleteVertexArrays(1, new int[] { vao }, 0);
        gl.glDeleteBuffers(2, new int[] { vbo, ebo }, 0);
        vao = 0;
    }
}
