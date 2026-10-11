import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Self-check for the sphere toolkit (Planet's axes, SphereFrame, SphereMath, CellIndex): frame
 * round trips at the equator, at 70 degrees and at the poles, the sphere's handedness against
 * the chart, the great-circle helpers, and the cell index against a brute-force search; and
 * Phase 2's pieces built on it: cell areas and points, collision in tangent frames, the
 * landing pod in its frame and the camera's local view.
 * Run after compiling: java -cp "target/classes;lib/jogamp-fat.jar" SphereToolkitCheck (exit
 * code 1 on a failure; the landing pod's class needs JOGL to load).
 */
public final class SphereToolkitCheck {

    private static int checks, failures;

    public static void main(String[] args) {
        Planet.setWidth(150_000f);
        double[][] places = {
            { 0, 0 }, { 0, 123 }, { 70, 37 }, { -70, -151 }, { 66, -3 }, { 89.9999, 10 },
            { 90, 0 }, { -90, 45 }, { 45, 180 }, { -12.5, -179.999 }
        };
        for (double[] place : places) checkFrame(place[0], place[1]);
        checkHandedness();
        checkMoving();
        checkArcs();
        checkCellIndex();
        checkCamera();
        checkCells();
        checkCollision();
        checkLandingPod();
        checkCameraFrames();
        System.out.printf("%d checks, %d failed%n", checks, failures);
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ frames

    private static void checkFrame(double latDeg, double lonDeg) {
        String where = String.format("(%.4f, %.3f)", latDeg, lonDeg);
        double lat = Math.toRadians(latDeg), lon = Math.toRadians(lonDeg);
        SpherePosition origin = Planet.position(lat, lon, 37.5);
        SphereFrame frame = SphereFrame.at(origin);
        double[] x = frame.xAxis(), up = frame.up(), z = frame.zAxis();

        near("unit east " + where, length(x), 1, 1e-12);
        near("unit up " + where, length(up), 1, 1e-12);
        near("unit south " + where, length(z), 1, 1e-12);
        near("east.up " + where, dot(x, up), 0, 1e-12);
        near("east.south " + where, dot(x, z), 0, 1e-12);
        near("up.south " + where, dot(up, z), 0, 1e-12);
        // Right-handed like the chart: east cross up is south
        near("east x up = south " + where, distance(cross(x, up), z), 0, 1e-12);

        // East and north agree with the way longitude and latitude grow (away from the poles)
        if (Math.abs(latDeg) < 89.9) {
            double h = 1e-6;
            double[] east = unit(sub(Planet.position(lat, lon + h, 37.5), Planet.position(lat, lon - h, 37.5)));
            double[] north = unit(sub(Planet.position(lat + h, lon, 37.5), Planet.position(lat - h, lon, 37.5)));
            near("east follows longitude " + where, distance(east, x), 0, 1e-7);
            near("north follows latitude " + where, distance(north, scale(z, -1)), 0, 1e-7);
            near("latitude back " + where, Planet.latitude(origin), lat, 1e-12);
            near("longitude back " + where, angleDifference(Planet.longitude(origin), lon), 0, 1e-12);
        }

        // Rigid round trip
        double[][] locals = { { 0, 0, 0 }, { 31.5, 2, -17 }, { -2500, 40, 1800 }, { 0.001, -0.002, 0.003 } };
        for (double[] v : locals) {
            double[] back = frame.toLocal(frame.toWorld(v[0], v[1], v[2]));
            near("toWorld/toLocal " + where, distance(back, v), 0, 1e-8);
            double[] dir = frame.localDirection(frame.worldDirection(v[0], v[1], v[2])[0],
                    frame.worldDirection(v[0], v[1], v[2])[1], frame.worldDirection(v[0], v[1], v[2])[2]);
            near("direction round trip " + where, distance(dir, v), 0, 1e-9);
            // Curved round trip (y is the altitude above the datum)
            double[] curved = frame.fromSphere(frame.toSphere(v[0], v[1], v[2]));
            near("toSphere/fromSphere " + where, distance(curved, v), 0, 1e-6);
            // Distances along the surface are kept exactly along the bearing
            double along = SphereMath.distance(frame.toSphere(0, 0, 0), frame.toSphere(v[0], 0, v[2]));
            near("toSphere distance " + where, along, Math.hypot(v[0], v[2]), 1e-6);
        }
        // A 300 m (3000 unit) house-sized patch: the flat frame and the sphere agree closely
        SpherePosition flat = frame.toWorld(150, 0, 0);
        double drop = SphereMath.altitude(flat) - SphereMath.altitude(origin);
        near("flat frame 15 m out " + where, drop, 150.0 * 150.0 / (2 * Planet.radius()), 1e-3);

        // Turned frames and headings
        for (double heading : new double[] { 0, 0.7, -2.4, Math.PI }) {
            SphereFrame turned = frame.rotated(heading);
            near("rotated x is heading " + where, distance(turned.xAxis(), frame.headingDirection(heading)), 0, 1e-12);
            near("rotated stays right-handed " + where, distance(cross(turned.xAxis(), turned.up()), turned.zAxis()), 0, 1e-12);
            double[] d = frame.headingDirection(heading);
            near("headingOf " + where, angleDifference(frame.headingOf(d[0], d[1], d[2]), heading), 0, 1e-12);
        }
        // Chart round trip, inside the chart
        if (Math.abs(latDeg) < 75) {
            double[] chart = Planet.mercatorPosition(origin);
            SpherePosition back = Planet.spherePosition(chart[0], chart[2], chart[1]);
            near("chart round trip " + where, SphereMath.distance(back, origin), 0, 1e-6);
        }
    }

    /** Anything built on the chart and lifted onto the sphere keeps its handedness (no mirror image). */
    private static void checkHandedness() {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            double x = (random.nextDouble() - 0.5) * Planet.width();
            double z = (random.nextDouble() - 0.5) * 2 * Planet.clipHalfHeight();
            SpherePosition p = Planet.spherePosition(x, z, 0);
            double[] dx = sub(Planet.spherePosition(x + 1, z, 0), p);
            double[] dz = sub(Planet.spherePosition(x, z + 1, 0), p);
            double[] up = unit(new double[] { p.x(), p.y(), p.z() });
            // On the chart x cross y is z: the lifted chart axes must keep that order
            check("chart lift keeps handedness at " + x + "," + z, dot(cross(dx, up), dz) > 0);
            // And the chart's east is the world's east
            double[] east = SphereFrame.at(p).xAxis();
            double[] across = sub(Planet.spherePosition(x + 1, z, 0), Planet.spherePosition(x - 1, z, 0));
            near("chart east is world east", distance(unit(across), east), 0, 1e-8);
        }
        // The distance on the chart's own formula matches the sphere's
        SpherePosition a = Planet.spherePosition(1200, -800, 0), b = Planet.spherePosition(-30000, 21000, 0);
        near("chart and sphere distances", SphereMath.distance(a, b), Planet.surfaceDistance(1200, -800, -30000, 21000), 1e-6);
    }

    // ------------------------------------------------------------------ moving

    private static void checkMoving() {
        Random random = new Random(11);
        for (int i = 0; i < 100; i++) {
            SpherePosition p = randomPosition(random, 25.0);
            double heading = random.nextDouble() * 2 * Math.PI, d = random.nextDouble() * 5000;
            SpherePosition q = SphereMath.move(p, heading, d);
            near("move distance", SphereMath.distance(p, q), d, 1e-6);
            near("move keeps altitude", SphereMath.altitude(q), SphereMath.altitude(p), 1e-6);
            if (d > 1) near("heading back", angleDifference(SphereMath.heading(p, q), heading), 0, 1e-6);
            SpherePosition half = SphereMath.slerp(p, q, 0.5);
            near("slerp halfway", SphereMath.distance(p, half), d / 2, 1e-6);
        }
        // Walking straight in small steps, carrying the heading, is one great circle: from 70 N
        // heading north it goes over the pole and comes round to where it began, facing the same way
        for (double startLat : new double[] { 70, 0, -66 }) {
            SpherePosition start = Planet.position(Math.toRadians(startLat), Math.toRadians(20), 0);
            double startHeading = -Math.PI / 2;   // north
            SpherePosition p = start;
            double heading = startHeading;
            double circumference = 2 * Math.PI * Planet.radius();
            int steps = 20000;
            for (int i = 0; i < steps; i++) {
                SpherePosition next = SphereMath.move(p, heading, circumference / steps);
                heading = SphereMath.carryHeading(p, next, heading);
                p = next;
            }
            near("great circle closes from " + startLat, SphereMath.distance(p, start), 0, 1e-3);
            near("heading carried round from " + startLat, angleDifference(heading, startHeading), 0, 1e-6);
            // One big step and many small ones end at the same place
            SpherePosition far = SphereMath.move(start, startHeading, circumference / 3);
            SpherePosition walked = start;
            double h = startHeading;
            for (int i = 0; i < 1000; i++) {
                SpherePosition next = SphereMath.move(walked, h, circumference / 3000);
                h = SphereMath.carryHeading(walked, next, h);
                walked = next;
            }
            near("steps match one move from " + startLat, SphereMath.distance(far, walked), 0, 1e-3);
        }
    }

    private static void checkArcs() {
        Random random = new Random(13);
        for (int i = 0; i < 100; i++) {
            SpherePosition a = randomPosition(random, 0);
            double heading = random.nextDouble() * 2 * Math.PI, length = 50 + random.nextDouble() * 3000;
            SpherePosition b = SphereMath.move(a, heading, length);
            SpherePosition middle = SphereMath.slerp(a, b, 0.5);
            double aside = 1 + random.nextDouble() * 40;
            double across = SphereMath.heading(middle, b) + Math.PI / 2;
            SpherePosition off = SphereMath.move(middle, across, aside);
            near("arc foot", SphereMath.arcFraction(off, a, b), 0.5, 1e-6);
            near("distance to arc", SphereMath.distanceToArc(off, a, b), aside, 1e-4);
            SpherePosition beyond = SphereMath.move(b, SphereMath.heading(b, a) + Math.PI, 20);
            near("past the end", SphereMath.arcFraction(beyond, a, b), 1, 0);
            near("past the end distance", SphereMath.distanceToArc(beyond, a, b), 20, 1e-4);
        }
    }

    // ------------------------------------------------------------------ the cell index

    private static void checkCellIndex() {
        Random random = new Random(17);
        // Round 70 degrees, round a pole, and round an icosahedron vertex (where five faces meet)
        double phi = (1 + Math.sqrt(5)) / 2;
        SpherePosition vertex = SphereMath.atAltitude(new GeodesicChunkGrid.Direction(1, phi, 0), 0);
        SpherePosition[] centres = {
            Planet.position(Math.toRadians(70), Math.toRadians(37), 0),
            Planet.position(Math.toRadians(90), 0, 0),
            vertex,
            Planet.position(0, 0, 0)
        };
        for (SpherePosition centre : centres) {
            CellIndex<Integer> index = new CellIndex<>();
            List<SpherePosition> positions = new ArrayList<>();
            List<Double> extents = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                SpherePosition p = SphereMath.move(centre, random.nextDouble() * 2 * Math.PI,
                        Math.sqrt(random.nextDouble()) * 1500);
                double extent = random.nextInt(4) == 0 ? random.nextDouble() * 120 : 0;
                positions.add(p);
                extents.add(extent);
                index.add(p, extent, i);
            }
            check("index size", index.size() == 3000);
            for (int q = 0; q < 200; q++) {
                SpherePosition at = SphereMath.move(centre, random.nextDouble() * 2 * Math.PI,
                        Math.sqrt(random.nextDouble()) * 1500);
                double radius = random.nextDouble() * 250;
                Set<Integer> expected = new HashSet<>();
                for (int i = 0; i < positions.size(); i++) {
                    if (SphereMath.distance(at, positions.get(i)) - extents.get(i) <= radius) expected.add(i);
                }
                Set<Integer> found = new HashSet<>(index.near(at, radius));
                check("index matches brute force (" + expected.size() + " expected, " + found.size() + " found)",
                        found.equals(expected));
            }
            // Removing
            check("remove", index.remove(positions.get(5), 5));
            check("removed is gone", !index.near(positions.get(5), 0.0).contains(5));
            check("remove twice", !index.remove(positions.get(5), 5));
            Integer nearest = index.nearest(positions.get(9), 1.0);
            check("nearest", nearest != null);
        }
    }

    // ------------------------------------------------------------------ the camera

    /**
     * The sphere camera is a plain (unmirrored) view: facing north, what lies east is on the
     * right of the screen and what lies above is at the top; facing east, south is on the right.
     */
    private static void checkCamera() {
        Camera camera = new Camera(Planet.position(0.0, 0.0, 10.0), 0f);
        camera.setPerspectiveMatrix(com.xenoguesser.math.Matrix4Transform.perspective(45, 16f / 9f, 0.1f, 5000f));
        double[][] places = { { 0, 11.5 }, { 70, 139 }, { -70, -40 }, { 89.99, 0 } };
        for (double[] place : places) {
            SpherePosition eye = Planet.position(Math.toRadians(place[0]), Math.toRadians(place[1]), 30);
            SphereFrame frame = SphereFrame.at(eye);
            String where = "(" + place[0] + ", " + place[1] + ")";
            // Facing north (yaw -90: from east towards south)
            camera.setSphereView(eye, -90f, 0f);
            double[] ahead = screen(camera, eye, frame.toWorld(0, 0, -1000));
            check("ahead is in front " + where, ahead[2] > 0 && Math.abs(ahead[0]) < 1e-3 && Math.abs(ahead[1]) < 1e-3);
            check("east is on the right facing north " + where, screen(camera, eye, frame.toWorld(200, 0, -1000))[0] > 0.05);
            check("west is on the left facing north " + where, screen(camera, eye, frame.toWorld(-200, 0, -1000))[0] < -0.05);
            check("up is at the top " + where, screen(camera, eye, frame.toWorld(0, 200, -1000))[1] > 0.05);
            // Facing east
            camera.setSphereView(eye, 0f, 0f);
            check("south is on the right facing east " + where, screen(camera, eye, frame.toWorld(1000, 0, 200))[0] > 0.05);
            // The camera's own forward agrees with the frame
            com.xenoguesser.math.Vector3 forward = camera.getSphereForwardDirection();
            double[] east = frame.xAxis();
            near("forward facing east " + where, forward.x * east[0] + forward.y * east[1] + forward.z * east[2], 1, 1e-5);
        }
    }

    /** A world position through the camera's sphere view-projection, as {ndc x, ndc y, w}. */
    private static double[] screen(Camera camera, SpherePosition eye, SpherePosition point) {
        float[] m = camera.getSphereViewProjection().toFloatArrayForGLSL();
        double x = point.x() - eye.x(), y = point.y() - eye.y(), z = point.z() - eye.z();
        double[] clip = new double[4];
        for (int row = 0; row < 4; row++) {
            clip[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row];
        }
        return new double[] { clip[0] / clip[3], clip[1] / clip[3], clip[3] };
    }

    // ------------------------------------------------------------------ Phase 2: cells, collision, the pod

    /** Cells' areas add up to the sphere's, and a cell's points lie in it. */
    private static void checkCells() {
        GeodesicChunkGrid coarse = new GeodesicChunkGrid(3);
        double total = 0.0;
        for (long id = 0; id < coarse.cellCount(); id++) total += coarse.cell(id).area();
        near("cell areas add up to the sphere's", total, 4 * Math.PI, 1e-9);
        GeodesicChunkGrid grid = new GeodesicChunkGrid(7);
        Random random = new Random(7);
        double smallest = Double.MAX_VALUE, largest = 0.0;
        for (int i = 0; i < 400; i++) {
            GeodesicChunkGrid.Cell cell = grid.cellAt(randomPosition(random, 0));
            smallest = Math.min(smallest, cell.area());
            largest = Math.max(largest, cell.area());
            GeodesicChunkGrid.Direction point = cell.point(random.nextDouble(), random.nextDouble());
            check("a cell's point lies in it", grid.cellAt(point).id() == cell.id());
        }
        // (per-cell counts go by each cell's own area, as these differ)
        check("level 7 cells are within a factor of 2 in area", largest / smallest < 2.0);
    }

    /** Collision in tangent frames: the same pushes at the equator, 70 degrees and a pole. */
    private static void checkCollision() {
        double[][] places = { { 0, 11.5 }, { 70, 27.5 }, { -70, 139 }, { 89.9999, 0 }, { 90, 0 } };
        for (double[] place : places) {
            String where = "(" + place[0] + ", " + place[1] + ")";
            SpherePosition centre = Planet.position(Math.toRadians(place[0]), Math.toRadians(place[1]), 0);
            SphereFrame frame = SphereFrame.at(centre);
            GeodesicCollision collision = new GeodesicCollision();
            // A post 5 round, and a wall 30 long running north to south 20 to the east, 2 high
            collision.addSource((at, reach, sink) -> {
                sink.circle(centre, 5.0);
                sink.segment(frame.toSphere(20, 0, -15), frame.toSphere(20, 0, 15), 1.0, 2.0);
            });
            SpherePosition inPost = frame.toSphere(-2, 7, 0);
            SpherePosition out = collision.resolve(inPost, 3.0, "me", 0.0);
            near("pushed clear of a post " + where, SphereMath.distance(out, centre), 8.0, 0.05);
            near("pushed straight out " + where, frame.fromSphere(out)[2], 0.0, 1e-6);
            near("keeps its altitude " + where, SphereMath.altitude(out), 7.0, 1e-6);
            check("blocked in a post " + where, collision.blocked(inPost, 3.0, "me", 0.0));
            check("clear beside it " + where, !collision.blocked(frame.toSphere(-9, 0, 0), 3.0, "me", 0.0));
            // Someone 2.5 up passes over the wall, someone on the ground doesn't
            SpherePosition atWall = frame.toSphere(19, 0, 0);
            check("a low wall blocks on the ground " + where, collision.blocked(atWall, 3.0, "me", 0.0));
            check("a low wall is jumped " + where, !collision.blocked(atWall, 3.0, "me", 2.5));
            // Heading straight at the wall (in the frame where it is asked: near a pole that is
            // not the centre's east), steering bears off it
            SpherePosition walker = frame.toSphere(12, 0, 0);
            double wanted = SphereMath.heading(walker, frame.toSphere(20, 0, 0));
            double steered = collision.steer(walker, 3.0, wanted, 8.0, "me", 0.0);
            check("steers round the wall " + where, !Double.isNaN(steered) && Math.abs(angleDifference(steered, wanted)) > 0.3);
            // A moving body: pushed half the overlap, and the player can't come any closer
            SpherePosition other = frame.toSphere(-30, 0, 0);
            collision.addBody("other", other, 2.0);
            collision.endFrame();
            SpherePosition touching = frame.toSphere(-27, 0, 0);
            // (half the overlap left each of three passes, as Collision does: 3 -> 3.5 -> 3.75 -> 3.875)
            near("shares a shove with a body " + where, SphereMath.distance(collision.resolve(touching, 2.0, "me", 0.0), other), 3.875, 0.02);
            SpherePosition firm = collision.resolveFirm(frame.toSphere(-26, 0, 0), touching, 2.0, "me", 0.0);
            near("comes no closer to a body " + where, SphereMath.distance(firm, other), 4.0, 0.02);
        }
    }

    /** The pod in its own frame: the player starts on its stairs, facing away, at any latitude. */
    private static void checkLandingPod() {
        double[][] places = { { 0, 11.5 }, { 70, 27.5 }, { 89.9999, 0 } };
        for (double[] place : places) {
            String where = "(" + place[0] + ", " + place[1] + ")";
            SpherePosition spawn = Planet.position(Math.toRadians(place[0]), Math.toRadians(place[1]), 0);
            double heading = 1.1;
            LandingPod pod = new LandingPod();
            pod.place(spawn, heading, p -> 12.0);
            SpherePosition middle = pod.frame().origin();
            near("pod stands back from the spawn " + where, SphereMath.distance(middle, spawn), LandingPod.SPAWN_DISTANCE, 1e-6);
            near("pod faces the way the player does " + where,
                    angleDifference(SphereMath.carryHeading(middle, spawn, SphereMath.heading(middle, spawn)), heading), 0, 1e-6);
            double floor = pod.floorAt(SphereMath.atAltitude(spawn, 12.0));
            check("the spawn is on the stairs " + where, floor > 12.0 && floor < 12.0 + 17.0);
            check("the pod covers the spawn " + where, pod.covers(spawn, 0));
            check("off the stairs is no floor " + where, pod.floorAt(SphereMath.move(spawn, heading + Math.PI * 0.5, 30)) == Double.NEGATIVE_INFINITY);
        }
    }

    /** Camera.lookAt and the local-frame view agree with the world view. */
    private static void checkCameraFrames() {
        Camera camera = new Camera(Planet.position(0.0, 0.0, 10.0), 0f);
        camera.setPerspectiveMatrix(com.xenoguesser.math.Matrix4Transform.perspective(45, 16f / 9f, 0.1f, 5000f));
        double[][] places = { { 0, 11.5 }, { 70, 139 }, { 89.99, 0 } };
        for (double[] place : places) {
            String where = "(" + place[0] + ", " + place[1] + ")";
            SpherePosition eye = Planet.position(Math.toRadians(place[0]), Math.toRadians(place[1]), 30);
            SphereFrame frame = SphereFrame.at(eye);
            camera.setSphereView(eye, 0f, 0f);
            camera.lookAt(frame.toWorld(0, 0, -100));
            near("looking north is yaw -90 " + where, camera.getYaw(), -90, 1e-3);
            camera.lookAt(frame.toWorld(50, -50, 0));
            near("looking down at 45 degrees " + where, camera.getPitch(), -45, 1e-3);
            float[] local = camera.localViewProjection(camera.getPerspectiveMatrix()).toFloatArrayForGLSL();
            double[] point = { 30, -20, 15 };
            double[] world = screen(camera, eye, frame.toWorld(point[0], point[1], point[2]));
            double[] clip = new double[4];
            for (int row = 0; row < 4; row++) {
                clip[row] = local[row] * point[0] + local[4 + row] * point[1] + local[8 + row] * point[2] + local[12 + row];
            }
            near("local view matches the world's (x) " + where, clip[0] / clip[3], world[0], 1e-4);
            near("local view matches the world's (y) " + where, clip[1] / clip[3], world[1], 1e-4);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static SpherePosition randomPosition(Random random, double altitude) {
        double lat = Math.asin(random.nextDouble() * 2 - 1), lon = (random.nextDouble() * 2 - 1) * Math.PI;
        return Planet.position(lat, lon, altitude);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAILED: " + name);
        }
    }

    private static void near(String name, double value, double expected, double tolerance) {
        checks++;
        if (!(Math.abs(value - expected) <= tolerance)) {
            failures++;
            System.out.printf("FAILED: %s: %.12g, expected %.12g (+/- %.1g)%n", name, value, expected, tolerance);
        }
    }

    private static double angleDifference(double a, double b) {
        return Math.atan2(Math.sin(a - b), Math.cos(a - b));
    }

    private static double[] sub(SpherePosition a, SpherePosition b) {
        return new double[] { a.x() - b.x(), a.y() - b.y(), a.z() - b.z() };
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double length(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double[] scale(double[] a, double s) {
        return new double[] { a[0] * s, a[1] * s, a[2] * s };
    }

    private static double[] unit(double[] a) {
        return scale(a, 1 / length(a));
    }

    private static double distance(double[] a, double[] b) {
        return length(new double[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] });
    }
}
