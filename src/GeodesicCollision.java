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

        default void segment(SpherePosition a, SpherePosition b, double halfWidth) {
            segment(a, b, halfWidth, Double.POSITIVE_INFINITY);
        }

        default void circle(SpherePosition centre, double radius) {
            circle(centre, radius, Double.POSITIVE_INFINITY);
        }
    }

    private static final int FIELDS = 7;
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
    // {kind, ax, az, bx, bz, size, top}, kind 0 segment, 1 circle
    private double[] shapes = new double[FIELDS * 256];
    private int shapeCount;
    private SphereFrame frame;
    private double queryReach;

    private final Sink gather = new Sink() {
        @Override
        public void segment(SpherePosition a, SpherePosition b, double halfWidth, double top) {
            double[] la = frame.fromSphere(a), lb = frame.fromSphere(b);
            // Only what could touch the query circle
            double minX = Math.min(la[0], lb[0]) - halfWidth, maxX = Math.max(la[0], lb[0]) + halfWidth;
            double minZ = Math.min(la[2], lb[2]) - halfWidth, maxZ = Math.max(la[2], lb[2]) + halfWidth;
            if (maxX < -queryReach || minX > queryReach || maxZ < -queryReach || minZ > queryReach) return;
            add(0, la[0], la[2], lb[0], lb[2], halfWidth, top);
        }

        @Override
        public void circle(SpherePosition centre, double radius, double top) {
            double[] l = frame.fromSphere(centre);
            double reach = queryReach + radius;
            if (Math.abs(l[0]) > reach || Math.abs(l[2]) > reach) return;
            add(1, l[0], l[2], 0, 0, radius, top);
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

    private void add(double kind, double a, double b, double c, double d, double size, double top) {
        if ((shapeCount + 1) * FIELDS > shapes.length) shapes = java.util.Arrays.copyOf(shapes, shapes.length * 2);
        int i = shapeCount * FIELDS;
        shapes[i] = kind;
        shapes[i + 1] = a;
        shapes[i + 2] = b;
        shapes[i + 3] = c;
        shapes[i + 4] = d;
        shapes[i + 5] = size;
        shapes[i + 6] = top;
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
                if (shapes[s * FIELDS + 6] < clearance) continue;
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
                if (shapes[s * FIELDS + 6] < clearance) continue;
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

    /** As blocked, counting only the static shapes (walls, rocks and the like), not moving bodies. */
    public boolean blockedStatic(SpherePosition position, double r, double clearance) {
        requireRadius(r);
        gatherNear(position, r + 4.0);
        return overlapsGathered(0.0, 0.0, r, null, clearance, List.of(), List.of());
    }

    private boolean overlapsGathered(double x, double z, double r, Object self, double clearance,
            List<Body> near, List<double[]> places) {
        for (int s = 0; s < shapeCount; s++) {
            if (shapes[s * FIELDS + 6] < clearance) continue;
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
