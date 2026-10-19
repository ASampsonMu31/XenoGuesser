import java.util.ArrayList;
import java.util.List;

/**
 * Keeps everything that walks on the planet out of everything solid: walls, fences, rocks,
 * tree trunks and the landing pod (static, from sources asked for what is near a place), and
 * the player and creatures (moving, round, listed afresh each frame in a CellIndex).
 * <p>
 * The tests are Collision's flat ones, run in the tangent frame of the place asked about (D3):
 * the shapes near it are gathered into that frame's local (x, z), as distances along the
 * surface (SphereFrame.fromSphere), and the answer is lifted back onto the sphere. Shapes are
 * small next to the planet, so this is exact to well under a millimetre, and the same at the
 * equator, at 70 degrees and at a pole. Headings are D10's: radians from local x (east)
 * towards local z (south) in the frame at the place asked about.
 */
public final class GeodesicCollision {

    /** Supplies the static shapes near a place. */
    public interface Source {
        void obstaclesNear(SpherePosition centre, double reach, Sink sink);
    }

    /** Receives shapes, each with how high it stands above the ground (infinite if not given). */
    public interface Sink {
        void segment(SpherePosition a, SpherePosition b, double halfWidth, double top);

        void circle(SpherePosition centre, double radius, double top);

        /**
         * A solid line standing from bottom up to top (heights over the ground): one walking
         * under it (their head below its bottom), as under a bridge's railing, passes beneath.
         */
        default void segment(SpherePosition a, SpherePosition b, double halfWidth, double top, double bottom) {
            segment(a, b, halfWidth, top);
        }

        default void segment(SpherePosition a, SpherePosition b, double halfWidth) {
            segment(a, b, halfWidth, Double.POSITIVE_INFINITY);
        }

        default void circle(SpherePosition centre, double radius) {
            circle(centre, radius, Double.POSITIVE_INFINITY);
        }
    }

    private static final int FIELDS = 8;
    // How tall a body is over its feet, for passing beneath something (see Sink.segment's bottom)
    private static final double BODY_TALL = 20.0;
    // Moving bodies are never bigger than this round (the largest creature)
    private static final double LARGEST_BODY = 12.0;

    private final List<Source> sources = new ArrayList<>();

    private static final class Body {
        SpherePosition position;
        double radius;
        Object owner;
        boolean immovable;   // others give way to it entirely (the player)
    }
    // Queries see last frame's bodies while this frame's are listed
    private CellIndex<Body> bodies = new CellIndex<>();
    private CellIndex<Body> listing = new CellIndex<>();

    // Shapes gathered for the current query, in the frame at its place:
    // {kind, ax, az, bx, bz, size, top, bottom}, kind 0 segment, 1 circle
    private double[] shapes = new double[FIELDS * 256];
    private int shapeCount;
    private SphereFrame frame;
    private double queryReach;

    private final Sink gather = new Sink() {
        @Override
        public void segment(SpherePosition a, SpherePosition b, double halfWidth, double top) {
            segment(a, b, halfWidth, top, Double.NEGATIVE_INFINITY);
        }

        @Override
        public void segment(SpherePosition a, SpherePosition b, double halfWidth, double top, double bottom) {
            double[] la = frame.fromSphere(a), lb = frame.fromSphere(b);
            // Only what could touch the query circle
            double minX = Math.min(la[0], lb[0]) - halfWidth, maxX = Math.max(la[0], lb[0]) + halfWidth;
            double minZ = Math.min(la[2], lb[2]) - halfWidth, maxZ = Math.max(la[2], lb[2]) + halfWidth;
            if (maxX < -queryReach || minX > queryReach || maxZ < -queryReach || minZ > queryReach) return;
            add(0, la[0], la[2], lb[0], lb[2], halfWidth, top, bottom);
        }

        @Override
        public void circle(SpherePosition centre, double radius, double top) {
            double[] l = frame.fromSphere(centre);
            double reach = queryReach + radius;
            if (Math.abs(l[0]) > reach || Math.abs(l[2]) > reach) return;
            add(1, l[0], l[2], 0, 0, radius, top, Double.NEGATIVE_INFINITY);
        }
    };

    public void addSource(Source source) {
        sources.add(source);
    }

    // ------------------------------------------------------------------ moving bodies

    /** Ends a frame: the bodies listed during it become the ones queries see next frame. */
    public void endFrame() {
        CellIndex<Body> seen = bodies;
        bodies = listing;
        listing = seen;
        listing.clear();
    }

    /** Forgets every moving body (a new round). */
    public void clear() {
        bodies.clear();
        listing.clear();
    }

    /** Lists a moving body for this frame. */
    public void addBody(Object owner, SpherePosition position, double radius) {
        addBody(owner, position, radius, false);
    }

    /** Lists a moving body for this frame; others always step fully clear of an immovable one. */
    public void addBody(Object owner, SpherePosition position, double radius, boolean immovable) {
        if (!Double.isFinite(radius) || radius < 0.0 || radius > LARGEST_BODY) {
            throw new IllegalArgumentException("A moving body's radius must be between 0 and " + LARGEST_BODY);
        }
        Body body = new Body();
        body.position = position;
        body.radius = radius;
        body.owner = owner;
        body.immovable = immovable;
        listing.add(position, radius, body);
    }

    // ------------------------------------------------------------------ queries

    private void gatherNear(SpherePosition centre, double reach) {
        shapeCount = 0;
        frame = SphereFrame.at(centre);
        queryReach = reach;
        for (Source source : sources) source.obstaclesNear(centre, reach, gather);
    }

    /** Whether one whose feet are clearance over the ground passes shape s: over its top, or under its bottom. */
    private boolean passes(int s, double clearance) {
        return shapes[s * FIELDS + 6] < clearance || shapes[s * FIELDS + 7] > clearance + BODY_TALL;
    }

    private void add(double kind, double a, double b, double c, double d, double size, double top, double bottom) {
        if ((shapeCount + 1) * FIELDS > shapes.length) shapes = java.util.Arrays.copyOf(shapes, shapes.length * 2);
        int i = shapeCount * FIELDS;
        shapes[i] = kind;
        shapes[i + 1] = a;
        shapes[i + 2] = b;
        shapes[i + 3] = c;
        shapes[i + 4] = d;
        shapes[i + 5] = size;
        shapes[i + 6] = top;
        shapes[i + 7] = bottom;
        shapeCount++;
    }

    /** The moving bodies within reach of the gathered frame's origin, as {x, z, radius} there, with their bodies. */
    private List<Body> nearbyBodies(double reach, List<double[]> places) {
        List<Body> found = bodies.near(frame.origin(), reach);
        for (Body body : found) {
            double[] l = frame.fromSphere(body.position);
            places.add(new double[] { l[0], l[2], body.radius });
        }
        return found;
    }

    private SpherePosition lift(double x, double z, SpherePosition original) {
        if (x == 0.0 && z == 0.0) return original;
        return frame.toSphere(x, SphereMath.altitude(original), z);
    }

    /**
     * Where a circle of radius r at a place ends up once pushed out of every solid thing it
     * overlaps (moving bodies other than self included), at the place's own altitude; anything
     * lower than clearance passes beneath it.
     */
    public SpherePosition resolve(SpherePosition position, double r, Object self, double clearance) {
        requireRadius(r);
        gatherNear(position, r + 4.0);
        List<double[]> places = new ArrayList<>();
        List<Body> near = nearbyBodies(r + 4.0, places);
        double px = 0.0, pz = 0.0;
        for (int pass = 0; pass < 3; pass++) {
            boolean moved = false;
            for (int s = 0; s < shapeCount; s++) {
                if (passes(s, clearance)) continue;
                double[] push = penetration(s, px, pz, r);
                if (push != null) {
                    px += push[0];
                    pz += push[1];
                    moved = true;
                }
            }
            for (int b = 0; b < near.size(); b++) {
                Body body = near.get(b);
                if (body.owner == self) continue;
                double[] at = places.get(b);
                double dx = px - at[0], dz = pz - at[1];
                double d = Math.sqrt(dx * dx + dz * dz), min = r + at[2];
                if (d >= min) continue;
                if (d < 1e-4) { dx = 1.0; dz = 0.0; d = 1.0; }
                // Moving bodies share the shove: this one moves half the overlap, or all of it
                // when the other won't budge
                double push = (min - d) * (body.immovable ? 1.0 : 0.5);
                px += dx / d * push;
                pz += dz / d * push;
                moved = true;
            }
            if (!moved) break;
        }
        return lift(px, pz, position);
    }

    /**
     * Static push-out for someone who is never shoved by others (the player), whose feet are
     * clearance above the ground: going from one place to another they stop against walls
     * and the like, and against moving bodies they may not come any closer than they already
     * were, so they can't walk through a creature but one brushing past doesn't drag them along.
     */
    public SpherePosition resolveFirm(SpherePosition from, SpherePosition to, double r, Object self, double clearance) {
        requireRadius(r);
        gatherNear(to, r + 4.0);
        double px = 0.0, pz = 0.0;
        for (int pass = 0; pass < 3; pass++) {
            boolean moved = false;
            for (int s = 0; s < shapeCount; s++) {
                if (passes(s, clearance)) continue;
                double[] push = penetration(s, px, pz, r);
                if (push != null) {
                    px += push[0];
                    pz += push[1];
                    moved = true;
                }
            }
            if (!moved) break;
        }
        double[] start = frame.fromSphere(from);
        List<double[]> places = new ArrayList<>();
        List<Body> near = nearbyBodies(r + 4.0, places);
        for (int b = 0; b < near.size(); b++) {
            if (near.get(b).owner == self) continue;
            double[] at = places.get(b);
            double dx = px - at[0], dz = pz - at[1];
            double d = Math.sqrt(dx * dx + dz * dz);
            double before = Math.hypot(start[0] - at[0], start[2] - at[1]);
            double allowed = Math.min(r + at[2], before);
            if (d >= allowed || d < 1e-4) continue;
            px = at[0] + dx / d * allowed;
            pz = at[1] + dz / d * allowed;
        }
        return lift(px, pz, to);
    }

    /** Whether a circle at a place would overlap anything solid as tall as clearance (moving bodies other than self included). */
    public boolean blocked(SpherePosition position, double r, Object self, double clearance) {
        requireRadius(r);
        gatherNear(position, r + 4.0);
        List<double[]> places = new ArrayList<>();
        List<Body> near = nearbyBodies(r + 4.0, places);
        return overlapsGathered(0.0, 0.0, r, self, clearance, near, places);
    }

    /** As blocked, not counting any moving body whose owner ignore accepts. */
    public boolean blocked(SpherePosition position, double r, Object self, double clearance,
            java.util.function.Predicate<Object> ignore) {
        requireRadius(r);
        gatherNear(position, r + 4.0);
        List<double[]> places = new ArrayList<>();
        List<Body> near = nearbyBodies(r + 4.0, places);
        for (int b = near.size() - 1; b >= 0; b--) {
            if (ignore.test(near.get(b).owner)) {
                near.remove(b);
                places.remove(b);
            }
        }
        return overlapsGathered(0.0, 0.0, r, self, clearance, near, places);
    }

    /** As blocked, counting only the static shapes (walls, rocks and the like), not moving bodies. */
    public boolean blockedStatic(SpherePosition position, double r, double clearance) {
        requireRadius(r);
        gatherNear(position, r + 4.0);
        return overlapsGathered(0.0, 0.0, r, null, clearance, List.of(), List.of());
    }

    private boolean overlapsGathered(double x, double z, double r, Object self, double clearance,
            List<Body> near, List<double[]> places) {
        for (int s = 0; s < shapeCount; s++) {
            if (passes(s, clearance)) continue;
            if (penetration(s, x, z, r) != null) return true;
        }
        for (int b = 0; b < near.size(); b++) {
            if (near.get(b).owner == self) continue;
            double[] at = places.get(b);
            double dx = x - at[0], dz = z - at[1], min = r + at[2];
            if (dx * dx + dz * dz < min * min) return true;
        }
        return false;
    }

    /**
     * Chooses a heading close to the one wanted that leads somewhere free over the next probe
     * distance from a place, trying ever wider turns either side, ignoring anything lower than
     * clearance. Returns NaN if every way is blocked.
     */
    public double steer(SpherePosition position, double r, double wanted, double probe, Object self, double clearance) {
        requireRadius(r);
        double[] tries = { 0.0, 0.35, -0.35, 0.75, -0.75, 1.2, -1.2, 1.7, -1.7, 2.3, -2.3, Math.PI };
        // One gathering covers every way it might go
        gatherNear(position, probe + r + 4.0);
        List<double[]> places = new ArrayList<>();
        List<Body> near = nearbyBodies(probe + r + 4.0, places);
        for (double offset : tries) {
            double h = wanted + offset;
            double sx = Math.cos(h), sz = Math.sin(h);
            // Both halfway and at the end, so a thin fence isn't stepped over
            if (!overlapsGathered(sx * probe * 0.5, sz * probe * 0.5, r, self, clearance, near, places)
                    && !overlapsGathered(sx * probe, sz * probe, r, self, clearance, near, places)) {
                return h;
            }
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------ finding a way round

    /** Whether a circle of radius r could go straight from one place to another without touching anything static. */
    public boolean clearLine(SpherePosition a, SpherePosition b, double r) {
        requireRadius(r);
        double length = SphereMath.distance(a, b);
        gatherNear(SphereMath.slerp(SphereMath.atAltitude(a, 0.0), SphereMath.atAltitude(b, 0.0), 0.5), length * 0.5 + r + 4.0);
        double[] la = frame.fromSphere(a), lb = frame.fromSphere(b);
        return lineFree(la[0], la[2], lb[0], lb[2], r);
    }

    private boolean lineFree(double ax, double az, double bx, double bz, double r) {
        double length = Math.hypot(bx - ax, bz - az);
        int steps = Math.max(1, (int) Math.ceil(length / Math.max(0.5, r * 0.6)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            if (hitsStatic(ax + (bx - ax) * t, az + (bz - az) * t, r)) return false;
        }
        return true;
    }

    /** Whether a circle at local (x, z) touches any gathered static shape, however low. */
    private boolean hitsStatic(double x, double z, double r) {
        for (int s = 0; s < shapeCount; s++) {
            if (penetration(s, x, z, r) != null) return true;
        }
        return false;
    }

    /**
     * A way on foot from one place to another round the walls, fences, posts, trees and rocks
     * between (moving bodies are left to be stepped round as met): a grid search over the ground
     * between them in the frame half way, the corners then cut wherever the way is clear.
     * Returns the places to walk through after the first, ending at the second, on the datum;
     * null if there's no way within reach (or it's too far to look). Someone starting pressed
     * against a fence starts from the nearest clear spot.
     */
    public List<SpherePosition> findPath(SpherePosition from, SpherePosition to, double r, double maxSpan) {
        requireRadius(r);
        SpherePosition a0 = SphereMath.atAltitude(from, 0.0), b0 = SphereMath.atAltitude(to, 0.0);
        SphereFrame here = SphereFrame.at(SphereMath.slerp(a0, b0, 0.5));
        double[] la = here.fromSphere(a0), lb = here.fromSphere(b0);
        double ax = la[0], az = la[2], bx = lb[0], bz = lb[2];
        double margin = 30.0;
        double minX = Math.min(ax, bx) - margin, maxX = Math.max(ax, bx) + margin;
        double minZ = Math.min(az, bz) - margin, maxZ = Math.max(az, bz) + margin;
        if (maxX - minX > maxSpan || maxZ - minZ > maxSpan) return null;
        double cell = Math.max(1.5, r * 0.8);
        int w = (int) Math.ceil((maxX - minX) / cell) + 1, h = (int) Math.ceil((maxZ - minZ) / cell) + 1;
        if ((long) w * h > 40000) {
            cell = Math.sqrt((maxX - minX) * (maxZ - minZ) / 40000.0) + 0.01;
            w = (int) Math.ceil((maxX - minX) / cell) + 1;
            h = (int) Math.ceil((maxZ - minZ) / cell) + 1;
        }
        gatherNear(here.origin(), Math.hypot(maxX - minX, maxZ - minZ) * 0.5 + r + 4.0);
        // 0 not yet looked at, 1 clear, 2 blocked
        byte[] state = new byte[w * h];
        int start = nearestFree(state, w, h, cell, minX, minZ, r, (int) Math.round((ax - minX) / cell), (int) Math.round((az - minZ) / cell));
        int goal = nearestFree(state, w, h, cell, minX, minZ, r, (int) Math.round((bx - minX) / cell), (int) Math.round((bz - minZ) / cell));
        if (start < 0 || goal < 0) return null;
        double[] cost = new double[w * h];
        java.util.Arrays.fill(cost, Double.MAX_VALUE);
        int[] cameFrom = new int[w * h];
        java.util.PriorityQueue<double[]> open = new java.util.PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        cost[start] = 0.0;
        int gx = goal % w, gz = goal / w;
        open.add(new double[] { 0.0, start });
        int expanded = 0;
        boolean found = false;
        while (!open.isEmpty() && expanded++ < 30000) {
            double[] top = open.poll();
            int c = (int) top[1];
            if (c == goal) { found = true; break; }
            int cx = c % w, cz = c / w;
            double base = cost[c];
            if (top[0] > base + octile(cx, cz, gx, gz) + 1e-3) continue;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                    int n = nz * w + nx;
                    if (!clear(state, n, w, cell, minX, minZ, r)) continue;
                    // No cutting a corner between two blocked squares
                    if (dx != 0 && dz != 0 && (!clear(state, cz * w + nx, w, cell, minX, minZ, r) || !clear(state, nz * w + cx, w, cell, minX, minZ, r))) continue;
                    double step = dx != 0 && dz != 0 ? 1.4142 : 1.0;
                    if (base + step < cost[n]) {
                        cost[n] = base + step;
                        cameFrom[n] = c;
                        open.add(new double[] { cost[n] + octile(nx, nz, gx, gz), n });
                    }
                }
            }
        }
        if (!found) return null;
        List<double[]> cells = new ArrayList<>();
        for (int c = goal; c != start; c = cameFrom[c]) cells.add(0, new double[] { minX + (c % w) * cell, minZ + (c / w) * cell });
        cells.add(0, new double[] { minX + (start % w) * cell, minZ + (start / w) * cell });
        cells.set(cells.size() - 1, new double[] { bx, bz });
        // Corners cut wherever the way between is clear
        List<SpherePosition> way = new ArrayList<>();
        double[] anchor = { ax, az };
        int i = 0;
        while (i < cells.size() - 1) {
            int far = i + 1;
            for (int j = cells.size() - 1; j > i + 1; j--) {
                if (lineFree(anchor[0], anchor[1], cells.get(j)[0], cells.get(j)[1], r * 0.9)) { far = j; break; }
            }
            anchor = cells.get(far);
            way.add(far == cells.size() - 1 ? b0 : here.toSphere(anchor[0], 0.0, anchor[1]));
            i = far;
        }
        return way;
    }

    private static double octile(int ax, int az, int bx, int bz) {
        int dx = Math.abs(ax - bx), dz = Math.abs(az - bz);
        return Math.max(dx, dz) + 0.4142 * Math.min(dx, dz);
    }

    private boolean clear(byte[] state, int c, int w, double cell, double minX, double minZ, double r) {
        if (state[c] == 0) state[c] = hitsStatic(minX + (c % w) * cell, minZ + (c / w) * cell, r) ? (byte) 2 : (byte) 1;
        return state[c] == 1;
    }

    /** The clear square nearest (cx, cz), within a few squares; -1 if none. */
    private int nearestFree(byte[] state, int w, int h, double cell, double minX, double minZ, double r, int cx, int cz) {
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

    /** How far to move a circle at local (x, z) to clear gathered shape s, or null if clear. */
    private double[] penetration(int s, double x, double z, double r) {
        int i = s * FIELDS;
        double qx, qz, size = shapes[i + 5];
        if (shapes[i] == 1.0) {
            qx = shapes[i + 1];
            qz = shapes[i + 2];
        } else {
            double ax = shapes[i + 1], az = shapes[i + 2], bx = shapes[i + 3], bz = shapes[i + 4];
            double ex = bx - ax, ez = bz - az;
            double len2 = ex * ex + ez * ez;
            double t = len2 < 1e-12 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x - ax) * ex + (z - az) * ez) / len2));
            qx = ax + ex * t;
            qz = az + ez * t;
        }
        double dx = x - qx, dz = z - qz;
        double d2 = dx * dx + dz * dz, min = r + size;
        if (d2 >= min * min) return null;
        double d = Math.sqrt(d2);
        if (d < 1e-4) {
            // Exactly on the line: out along its normal
            if (shapes[i] == 0.0) {
                double ex = shapes[i + 3] - shapes[i + 1], ez = shapes[i + 4] - shapes[i + 2];
                double len = Math.sqrt(ex * ex + ez * ez);
                dx = len < 1e-9 ? 1.0 : -ez / len;
                dz = len < 1e-9 ? 0.0 : ex / len;
            } else {
                dx = 1.0;
                dz = 0.0;
            }
            return new double[] { dx * min, dz * min };
        }
        return new double[] { dx / d * (min - d), dz / d * (min - d) };
    }

    private static void requireRadius(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Distance must be finite and non-negative");
        }
    }
}
