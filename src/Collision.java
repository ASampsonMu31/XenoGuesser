import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps everything that walks out of everything solid, on the ground plane: building walls,
 * fences, guard rails, sign posts and tree trunks (static), and the player, creatures and
 * inhabitants (moving, round). Movers are circles; walls, fences and rails are thick line
 * segments; posts and trunks are circles.
 *
 * Static shapes come from a source asked for everything near a point; moving bodies are
 * listed afresh each frame.
 */
public class Collision {

    /** Supplies static shapes near a point. */
    public interface StaticSource {
        void obstaclesNear(float x, float z, float reach, Sink sink);
    }

    /** Receives shapes, each with how high it stands above the ground (infinite if not given). */
    public interface Sink {
        void segment(float ax, float az, float bx, float bz, float halfWidth, float top);

        void circle(float x, float z, float radius, float top);

        default void segment(float ax, float az, float bx, float bz, float halfWidth) {
            segment(ax, az, bx, bz, halfWidth, Float.POSITIVE_INFINITY);
        }

        default void circle(float x, float z, float radius) {
            circle(x, z, radius, Float.POSITIVE_INFINITY);
        }
    }

    private static final int FIELDS = 7;

    private final List<StaticSource> sources = new ArrayList<>();

    // Shapes gathered for the current query: {kind, ax, az, bx, bz, size, top}, kind 0 segment, 1 circle
    private float[] shapes = new float[FIELDS * 256];
    private int shapeCount;
    private float queryX, queryZ, queryReach;

    // Moving bodies this frame, bucketed by cell
    private static final float CELL = 40f;
    private static final class Body {
        float x, z, r;
        Object owner;
        boolean immovable;   // others give way to it entirely (the player)
    }
    // Queries see last frame's bodies while this frame's are listed
    private Map<Long, List<Body>> bodies = new HashMap<>();
    private Map<Long, List<Body>> listing = new HashMap<>();

    private final Sink gather = new Sink() {
        @Override
        public void segment(float ax, float az, float bx, float bz, float halfWidth, float top) {
            // Only what could touch the query circle
            float minX = Math.min(ax, bx) - halfWidth, maxX = Math.max(ax, bx) + halfWidth;
            float minZ = Math.min(az, bz) - halfWidth, maxZ = Math.max(az, bz) + halfWidth;
            if (maxX < queryX - queryReach || minX > queryX + queryReach || maxZ < queryZ - queryReach || minZ > queryZ + queryReach) return;
            add(0, ax, az, bx, bz, halfWidth, top);
        }

        @Override
        public void circle(float x, float z, float radius, float top) {
            float reach = queryReach + radius;
            if (Math.abs(x - queryX) > reach || Math.abs(z - queryZ) > reach) return;
            add(1, x, z, 0f, 0f, radius, top);
        }
    };

    public void addSource(StaticSource source) {
        sources.add(source);
    }

    // ------------------------------------------------------------------ moving bodies

    /** Ends a frame: the bodies listed during it become the ones queries see next frame. */
    public void endFrame() {
        Map<Long, List<Body>> seen = bodies;
        bodies = listing;
        listing = seen;
        listing.clear();
    }

    /** Lists a moving body for this frame. */
    public void addBody(Object owner, float x, float z, float radius) {
        addBody(owner, x, z, radius, false);
    }

    /** Lists a moving body for this frame; others always step fully clear of an immovable one. */
    public void addBody(Object owner, float x, float z, float radius, boolean immovable) {
        Body b = new Body();
        b.immovable = immovable;
        b.x = x;
        b.z = z;
        b.r = radius;
        b.owner = owner;
        listing.computeIfAbsent(cellKey(x, z), k -> new ArrayList<>(4)).add(b);
    }

    private static long cellKey(float x, float z) {
        long cx = (long) Math.floor(x / CELL), cz = (long) Math.floor(z / CELL);
        return (cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ queries

    private void gatherNear(float x, float z, float reach) {
        shapeCount = 0;
        queryX = x;
        queryZ = z;
        queryReach = reach;
        for (StaticSource source : sources) source.obstaclesNear(x, z, reach, gather);
    }

    private void add(float kind, float a, float b, float c, float d, float size, float top) {
        if ((shapeCount + 1) * FIELDS > shapes.length) shapes = java.util.Arrays.copyOf(shapes, shapes.length * 2);
        int i = shapeCount * FIELDS;
        shapes[i + 6] = top;
        shapes[i] = kind;
        shapes[i + 1] = a;
        shapes[i + 2] = b;
        shapes[i + 3] = c;
        shapes[i + 4] = d;
        shapes[i + 5] = size;
        shapeCount++;
    }

    /**
     * Where a circle of radius r at (x, z) ends up once pushed out of every solid thing it
     * overlaps (moving bodies other than self included), as {x, z}.
     */
    public float[] resolve(float x, float z, float r, Object self) {
        return resolve(x, z, r, self, 0f);
    }

    /** As resolve, for someone clearance above the ground: anything lower passes beneath them. */
    public float[] resolve(float x, float z, float r, Object self, float clearance) {
        gatherNear(x, z, r + 4f);
        float px = x, pz = z;
        for (int pass = 0; pass < 3; pass++) {
            boolean moved = false;
            for (int s = 0; s < shapeCount; s++) {
                if (shapes[s * FIELDS + 6] < clearance) continue;
                float[] push = penetration(s, px, pz, r);
                if (push != null) {
                    px += push[0];
                    pz += push[1];
                    moved = true;
                }
            }
            for (Body b : nearbyBodies(px, pz, r)) {
                if (b.owner == self) continue;
                float dx = px - b.x, dz = pz - b.z;
                float d = (float) Math.sqrt(dx * dx + dz * dz), min = r + b.r;
                if (d >= min) continue;
                if (d < 1e-4f) { dx = 1f; dz = 0f; d = 1f; }
                // Moving bodies share the shove: this one moves half the overlap, or all of it
                // when the other won't budge
                float push = (min - d) * (b.immovable ? 1f : 0.5f);
                px += dx / d * push;
                pz += dz / d * push;
                moved = true;
            }
            if (!moved) break;
        }
        return new float[] { px, pz };
    }

    /**
     * Static-only push-out for someone who is never shoved by others (the player): from
     * (fromX, fromZ) to (x, z) they stop against walls and the like, and against moving
     * bodies they may not come any closer than they already were, so they can't walk
     * through a creature but a creature brushing past doesn't drag them along.
     */
    public float[] resolveFirm(float fromX, float fromZ, float x, float z, float r, Object self) {
        return resolveFirm(fromX, fromZ, x, z, r, self, 0f);
    }

    /**
     * As resolveFirm, for someone whose feet are clearance above the ground (in the air):
     * anything whose top is lower than that passes beneath them.
     */
    public float[] resolveFirm(float fromX, float fromZ, float x, float z, float r, Object self, float clearance) {
        gatherNear(x, z, r + 4f);
        float px = x, pz = z;
        for (int pass = 0; pass < 3; pass++) {
            boolean moved = false;
            for (int s = 0; s < shapeCount; s++) {
                if (shapes[s * FIELDS + 6] < clearance) continue;
                float[] push = penetration(s, px, pz, r);
                if (push != null) {
                    px += push[0];
                    pz += push[1];
                    moved = true;
                }
            }
            if (!moved) break;
        }
        for (Body b : nearbyBodies(px, pz, r)) {
            if (b.owner == self) continue;
            float dx = px - b.x, dz = pz - b.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            float before = (float) Math.hypot(fromX - b.x, fromZ - b.z);
            float allowed = Math.min(r + b.r, before);
            if (d >= allowed || d < 1e-4f) continue;
            px = b.x + dx / d * allowed;
            pz = b.z + dz / d * allowed;
        }
        return new float[] { px, pz };
    }

    /** Whether a circle at (x, z) would overlap anything solid (moving bodies other than self included). */
    public boolean blocked(float x, float z, float r, Object self) {
        return blocked(x, z, r, self, 0f);
    }

    /** As blocked, ignoring anything lower than clearance (something that could be jumped). */
    public boolean blocked(float x, float z, float r, Object self, float clearance) {
        gatherNear(x, z, r + 4f);
        return overlapsGathered(x, z, r, self, clearance);
    }

    /** As blocked, not counting any moving body whose owner ignore accepts. */
    public boolean blocked(float x, float z, float r, Object self, java.util.function.Predicate<Object> ignore) {
        gatherNear(x, z, r + 4f);
        for (int s = 0; s < shapeCount; s++) {
            if (penetration(s, x, z, r) != null) return true;
        }
        for (Body b : nearbyBodies(x, z, r)) {
            if (b.owner == self || ignore.test(b.owner)) continue;
            float dx = x - b.x, dz = z - b.z, min = r + b.r;
            if (dx * dx + dz * dz < min * min) return true;
        }
        return false;
    }

    private boolean overlapsGathered(float x, float z, float r, Object self) {
        return overlapsGathered(x, z, r, self, 0f);
    }

    private boolean overlapsGathered(float x, float z, float r, Object self, float clearance) {
        for (int s = 0; s < shapeCount; s++) {
            if (shapes[s * FIELDS + 6] < clearance) continue;
            if (penetration(s, x, z, r) != null) return true;
        }
        for (Body b : nearbyBodies(x, z, r)) {
            if (b.owner == self) continue;
            float dx = x - b.x, dz = z - b.z, min = r + b.r;
            if (dx * dx + dz * dz < min * min) return true;
        }
        return false;
    }

    /**
     * Chooses a heading (radians, 0 towards +Z) close to the one wanted that leads somewhere
     * free over the next probe distance, trying ever wider turns either side. Returns NaN if
     * every way is blocked.
     */
    public float steer(float x, float z, float r, float wanted, float probe, Object self) {
        float[] tries = { 0f, 0.35f, -0.35f, 0.75f, -0.75f, 1.2f, -1.2f, 1.7f, -1.7f, 2.3f, -2.3f, (float) Math.PI };
        // One gathering covers every way it might go
        gatherNear(x, z, probe + r + 4f);
        for (float offset : tries) {
            float h = wanted + offset;
            float sx = (float) Math.sin(h), sz = (float) Math.cos(h);
            // Both halfway and at the end, so a thin fence isn't stepped over
            if (!overlapsGathered(x + sx * probe * 0.5f, z + sz * probe * 0.5f, r, self)
                    && !overlapsGathered(x + sx * probe, z + sz * probe, r, self)) {
                return h;
            }
        }
        return Float.NaN;
    }

    // ------------------------------------------------------------------ finding a way round

    /** Whether a circle of radius r could go straight from a to b without touching a wall, fence or post. */
    public boolean clearLine(float ax, float az, float bx, float bz, float r) {
        float length = (float) Math.hypot(bx - ax, bz - az);
        gatherNear((ax + bx) * 0.5f, (az + bz) * 0.5f, length * 0.5f + r + 4f);
        return lineFree(ax, az, bx, bz, r);
    }

    private boolean lineFree(float ax, float az, float bx, float bz, float r) {
        float length = (float) Math.hypot(bx - ax, bz - az);
        int steps = Math.max(1, (int) Math.ceil(length / Math.max(0.5f, r * 0.6f)));
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            if (hitsStatic(ax + (bx - ax) * t, az + (bz - az) * t, r)) return false;
        }
        return true;
    }

    /** Whether a circle touches any gathered wall, fence or post (moving bodies don't count). */
    private boolean hitsStatic(float x, float z, float r) {
        for (int s = 0; s < shapeCount; s++) {
            int i = s * FIELDS;
            float qx, qz;
            if (shapes[i] == 1f) {
                qx = shapes[i + 1];
                qz = shapes[i + 2];
            } else {
                float ax = shapes[i + 1], az = shapes[i + 2], ex = shapes[i + 3] - ax, ez = shapes[i + 4] - az;
                float len2 = ex * ex + ez * ez;
                float t = len2 < 1e-6f ? 0f : Math.max(0f, Math.min(1f, ((x - ax) * ex + (z - az) * ez) / len2));
                qx = ax + ex * t;
                qz = az + ez * t;
            }
            float dx = x - qx, dz = z - qz, min = r + shapes[i + 5];
            if (dx * dx + dz * dz < min * min) return true;
        }
        return false;
    }

    /**
     * A way on foot from a to b round the walls, fences and posts between (people and
     * vehicles move, so they're left to be stepped round as met): a grid search over the
     * ground between them, the corners then cut wherever the way is clear. Returns the points
     * to walk through after a, ending at b; null if there's no way within reach (or it's too far
     * to look). Someone starting pressed against a fence starts from the nearest clear spot.
     */
    public List<float[]> findPath(float ax, float az, float bx, float bz, float r, float maxSpan) {
        float margin = 30f;
        float minX = Math.min(ax, bx) - margin, maxX = Math.max(ax, bx) + margin;
        float minZ = Math.min(az, bz) - margin, maxZ = Math.max(az, bz) + margin;
        if (maxX - minX > maxSpan || maxZ - minZ > maxSpan) return null;
        float cell = Math.max(1.5f, r * 0.8f);
        int w = (int) Math.ceil((maxX - minX) / cell) + 1, h = (int) Math.ceil((maxZ - minZ) / cell) + 1;
        if ((long) w * h > 40000) {
            cell = (float) Math.sqrt((maxX - minX) * (maxZ - minZ) / 40000.0) + 0.01f;
            w = (int) Math.ceil((maxX - minX) / cell) + 1;
            h = (int) Math.ceil((maxZ - minZ) / cell) + 1;
        }
        gatherNear((minX + maxX) * 0.5f, (minZ + maxZ) * 0.5f, (float) Math.hypot(maxX - minX, maxZ - minZ) * 0.5f + r + 4f);
        // 0 not yet looked at, 1 clear, 2 blocked
        byte[] state = new byte[w * h];
        int start = nearestFree(state, w, h, cell, minX, minZ, r, Math.round((ax - minX) / cell), Math.round((az - minZ) / cell));
        int goal = nearestFree(state, w, h, cell, minX, minZ, r, Math.round((bx - minX) / cell), Math.round((bz - minZ) / cell));
        if (start < 0 || goal < 0) return null;
        float[] cost = new float[w * h];
        java.util.Arrays.fill(cost, Float.MAX_VALUE);
        int[] from = new int[w * h];
        java.util.PriorityQueue<float[]> open = new java.util.PriorityQueue<>((p, q) -> Float.compare(p[0], q[0]));
        cost[start] = 0f;
        int gx = goal % w, gz = goal / w;
        open.add(new float[] { 0f, start });
        int expanded = 0;
        boolean found = false;
        while (!open.isEmpty() && expanded++ < 30000) {
            float[] top = open.poll();
            int c = (int) top[1];
            if (c == goal) { found = true; break; }
            int cx = c % w, cz = c / w;
            float base = cost[c];
            if (top[0] > base + octile(cx, cz, gx, gz) + 1e-3f) continue;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                    int n = nz * w + nx;
                    if (!clear(state, n, w, cell, minX, minZ, r)) continue;
                    // No cutting a corner between two blocked squares
                    if (dx != 0 && dz != 0 && (!clear(state, cz * w + nx, w, cell, minX, minZ, r) || !clear(state, nz * w + cx, w, cell, minX, minZ, r))) continue;
                    float step = dx != 0 && dz != 0 ? 1.4142f : 1f;
                    if (base + step < cost[n]) {
                        cost[n] = base + step;
                        from[n] = c;
                        open.add(new float[] { cost[n] + octile(nx, nz, gx, gz), n });
                    }
                }
            }
        }
        if (!found) return null;
        List<float[]> cells = new ArrayList<>();
        for (int c = goal; c != start; c = from[c]) cells.add(0, new float[] { minX + (c % w) * cell, minZ + (c / w) * cell });
        cells.add(0, new float[] { minX + (start % w) * cell, minZ + (start / w) * cell });
        cells.set(cells.size() - 1, new float[] { bx, bz });
        // Corners cut wherever the way between is clear
        List<float[]> way = new ArrayList<>();
        float[] anchor = { ax, az };
        int i = 0;
        while (i < cells.size() - 1) {
            int far = i + 1;
            for (int j = cells.size() - 1; j > i + 1; j--) {
                if (lineFree(anchor[0], anchor[1], cells.get(j)[0], cells.get(j)[1], r * 0.9f)) { far = j; break; }
            }
            anchor = cells.get(far);
            way.add(anchor);
            i = far;
        }
        return way;
    }

    private static float octile(int ax, int az, int bx, int bz) {
        int dx = Math.abs(ax - bx), dz = Math.abs(az - bz);
        return Math.max(dx, dz) + 0.4142f * Math.min(dx, dz);
    }

    private boolean clear(byte[] state, int c, int w, float cell, float minX, float minZ, float r) {
        if (state[c] == 0) state[c] = hitsStatic(minX + (c % w) * cell, minZ + (c / w) * cell, r) ? (byte) 2 : (byte) 1;
        return state[c] == 1;
    }

    /** The clear square nearest (cx, cz), within a few squares; -1 if none. */
    private int nearestFree(byte[] state, int w, int h, float cell, float minX, float minZ, float r, int cx, int cz) {
        for (int ring = 0; ring <= 6; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = cx + dx, z = cz + dz;
                    if (x < 0 || z < 0 || x >= w || z >= h) continue;
                    if (clear(state, z * w + x, w, cell, minX, minZ, r)) return z * w + x;
                }
            }
        }
        return -1;
    }


    private final List<Body> nearby = new ArrayList<>();

    private List<Body> nearbyBodies(float x, float z, float r) {
        nearby.clear();
        int cx0 = (int) Math.floor((x - r - 12f) / CELL), cx1 = (int) Math.floor((x + r + 12f) / CELL);
        int cz0 = (int) Math.floor((z - r - 12f) / CELL), cz1 = (int) Math.floor((z + r + 12f) / CELL);
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                List<Body> list = bodies.get(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                if (list != null) nearby.addAll(list);
            }
        }
        return nearby;
    }

    /** How far to move a circle at (x, z) to clear gathered shape s, or null if clear. */
    private float[] penetration(int s, float x, float z, float r) {
        int i = s * FIELDS;
        float qx, qz, size = shapes[i + 5];
        if (shapes[i] == 1f) {
            qx = shapes[i + 1];
            qz = shapes[i + 2];
        } else {
            float ax = shapes[i + 1], az = shapes[i + 2], bx = shapes[i + 3], bz = shapes[i + 4];
            float ex = bx - ax, ez = bz - az;
            float len2 = ex * ex + ez * ez;
            float t = len2 < 1e-6f ? 0f : Math.max(0f, Math.min(1f, ((x - ax) * ex + (z - az) * ez) / len2));
            qx = ax + ex * t;
            qz = az + ez * t;
        }
        float dx = x - qx, dz = z - qz;
        float d2 = dx * dx + dz * dz, min = r + size;
        if (d2 >= min * min) return null;
        float d = (float) Math.sqrt(d2);
        if (d < 1e-4f) {
            // Exactly on the line: out along its normal
            if (shapes[i] == 0f) {
                float ex = shapes[i + 3] - shapes[i + 1], ez = shapes[i + 4] - shapes[i + 2];
                float len = (float) Math.sqrt(ex * ex + ez * ez);
                dx = len < 1e-6f ? 1f : -ez / len;
                dz = len < 1e-6f ? 0f : ex / len;
            } else {
                dx = 1f;
                dz = 0f;
            }
            d = 1f;
            return new float[] { dx * min, dz * min };
        }
        return new float[] { dx / d * (min - d), dz / d * (min - d) };
    }
}
