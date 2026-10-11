import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Makes small deterministic house clusters at native spherical locations. */
public final class GeodesicSettlementMesh {
    private static final int SETTLEMENT_LEVEL = 4;

    private GeodesicSettlementMesh() {
    }

    /** Native render meshes and their matching sphere-space solid boundaries. */
    public record Patch(float[] wallVertices, int[] wallIndices, float[] roofVertices, int[] roofIndices,
            List<WallSegment> walls, List<SettlementArea> areas) {
    }

    /** One ground-level wall segment with its collision height above the terrain. */
    public record WallSegment(SpherePosition start, SpherePosition end, double top) {
    }

    /** Native settlement footprint used to keep wildlife away from built-up land. */
    public record SettlementArea(SpherePosition centre, double radius) {
    }

    public static Patch buildPatchData(GeodesicChunkGrid grid, SpherePosition centre,
            double angularRadius, float seaLevel, long worldSeed, PerlinNoise noise) {
        GeodesicChunkGrid.Direction origin =
                new GeodesicChunkGrid.Direction(centre.x(), centre.y(), centre.z()).normalized();
        List<GeodesicChunkGrid.Cell> hubs = new GeodesicChunkGrid(SETTLEMENT_LEVEL)
                .cellsWithin(origin, angularRadius + 0.025);
        Geometry walls = new Geometry();
        Geometry roofs = new Geometry();
        List<WallSegment> collisionWalls = new ArrayList<>();
        List<SettlementArea> settlementAreas = new ArrayList<>();
        for (GeodesicChunkGrid.Cell hub : hubs) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Geodesic settlement build cancelled");
            }
            SpherePosition hubPoint = onGround(hub.centre(), noise);
            if (height(hub.centre(), noise) <= seaLevel + 6.0) continue;
            Random random = new Random(mix(worldSeed ^ hub.id() ^ 0x534554544c454d45L));
            if (random.nextFloat() > 0.42f) continue;
            settlementAreas.add(new SettlementArea(groundPoint(hubPoint), 500.0));

            int count = 4 + random.nextInt(6);
            for (int i = 0; i < count; i++) {
                double bearing = random.nextDouble() * Math.PI * 2.0;
                double distance = Math.sqrt(random.nextDouble()) * 420.0;
                SpherePosition ground = SphereMath.moveToward(hubPoint,
                        Math.cos(bearing), Math.sin(bearing), distance);
                GeodesicChunkGrid.Direction direction =
                        new GeodesicChunkGrid.Direction(ground.x(), ground.y(), ground.z()).normalized();
                double groundHeight = height(direction, noise);
                if (groundHeight <= seaLevel + 3.0) continue;
                double radius = Planet.radius() + groundHeight + 0.2;
                SpherePosition base = new SpherePosition(direction.x() * radius,
                        direction.y() * radius, direction.z() * radius);
                double yaw = random.nextDouble() * Math.PI * 2.0;
                float width = 48f + random.nextFloat() * 28f;
                float depth = 38f + random.nextFloat() * 24f;
                float wallHeight = 18f + random.nextFloat() * 11f;
                float roofHeight = 10f + random.nextFloat() * 10f;
                appendHouse(base, yaw, width, depth, wallHeight, roofHeight, walls, roofs,
                        collisionWalls);
                settlementAreas.add(new SettlementArea(groundPoint(base),
                        Math.hypot(width, depth) * 0.5 + 55.0));
            }
        }
        return new Patch(walls.vertices(), walls.indices(), roofs.vertices(), roofs.indices(),
                List.copyOf(collisionWalls), List.copyOf(settlementAreas));
    }

    private static void appendHouse(SpherePosition base, double yaw, float width, float depth,
            float wallHeight, float roofHeight, Geometry walls, Geometry roofs,
            List<WallSegment> collisionWalls) {
        SphereFrame turned = SphereFrame.at(base, -yaw);
        double[] x = turned.xAxis(), up = turned.up(), z = turned.zAxis();
        Frame frame = new Frame(base, x[0], x[1], x[2], up[0], up[1], up[2], z[0], z[1], z[2]);

        float x0 = -width * 0.5f, x1 = width * 0.5f;
        float z0 = -depth * 0.5f, z1 = depth * 0.5f;
        double collisionTop = wallHeight + roofHeight;
        SpherePosition[] footprint = {
                groundPoint(frame.position(x0, 0, z0)),
                groundPoint(frame.position(x1, 0, z0)),
                groundPoint(frame.position(x1, 0, z1)),
                groundPoint(frame.position(x0, 0, z1))
        };
        for (int i = 0; i < footprint.length; i++) {
            collisionWalls.add(new WallSegment(footprint[i], footprint[(i + 1) % footprint.length],
                    collisionTop));
        }
        float y0 = 0f, y1 = wallHeight;
        walls.quad(frame, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1);
        walls.quad(frame, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1);
        walls.quad(frame, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0);
        walls.quad(frame, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0);
        walls.quad(frame, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0);

        float ridgeY = wallHeight + roofHeight;
        roofs.triangle(frame, x0 - 1.5f, y1, z0 - 1f, 0f, ridgeY, z0 - 1f,
                0f, ridgeY, z1 + 1f, -roofHeight, 1f, 0f);
        roofs.triangle(frame, x1 + 1.5f, y1, z1 + 1f, 0f, ridgeY, z1 + 1f,
                0f, ridgeY, z0 - 1f, roofHeight, 1f, 0f);
        roofs.quad(frame, x0 - 1.5f, y1, z1 + 1f, x1 + 1.5f, y1, z1 + 1f,
                x1 + 1.5f, y1, z0 - 1f, x0 - 1.5f, y1, z0 - 1f, 0, 1, 0);
    }

    private static SpherePosition groundPoint(float[] position) {
        double length = Math.sqrt((double) position[0] * position[0]
                + (double) position[1] * position[1] + (double) position[2] * position[2]);
        double radius = Planet.radius();
        return new SpherePosition(position[0] * radius / length, position[1] * radius / length,
                position[2] * radius / length);
    }

    private static SpherePosition groundPoint(SpherePosition position) {
        double length = position.length();
        double scale = Planet.radius() / length;
        return new SpherePosition(position.x() * scale, position.y() * scale, position.z() * scale);
    }

    private record Frame(SpherePosition base, double xx, double xy, double xz,
                         double ux, double uy, double uz, double zx, double zy, double zz) {
        float[] position(double x, double y, double z) {
            return new float[] { (float) (base.x() + xx * x + ux * y + zx * z),
                    (float) (base.y() + xy * x + uy * y + zy * z),
                    (float) (base.z() + xz * x + uz * y + zz * z) };
        }

        float[] normal(double x, double y, double z) {
            double nx = xx * x + ux * y + zx * z;
            double ny = xy * x + uy * y + zy * z;
            double nz = xz * x + uz * y + zz * z;
            double magnitude = Math.sqrt(nx * nx + ny * ny + nz * nz);
            return new float[] { (float) (nx / magnitude), (float) (ny / magnitude), (float) (nz / magnitude) };
        }
    }

    private static final class Geometry {
        private final List<Float> vertices = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();

        void quad(Frame frame, double[] a, double[] b, double[] c, double[] d,
                double nx, double ny, double nz) {
            int first = vertices.size() / 8;
            float uLength = (float) Math.sqrt((b[0] - a[0]) * (b[0] - a[0])
                    + (b[2] - a[2]) * (b[2] - a[2])) / 8f;
            float vLength = (float) Math.sqrt((d[0] - a[0]) * (d[0] - a[0])
                    + (d[1] - a[1]) * (d[1] - a[1])
                    + (d[2] - a[2]) * (d[2] - a[2])) / 8f;
            vertex(frame.position(a[0], a[1], a[2]), frame.normal(nx, ny, nz), 0, 0);
            vertex(frame.position(b[0], b[1], b[2]), frame.normal(nx, ny, nz), uLength, 0);
            vertex(frame.position(c[0], c[1], c[2]), frame.normal(nx, ny, nz), uLength, vLength);
            vertex(frame.position(d[0], d[1], d[2]), frame.normal(nx, ny, nz), 0, vLength);
            indices.add(first); indices.add(first + 1); indices.add(first + 2);
            indices.add(first); indices.add(first + 2); indices.add(first + 3);
        }

        void quad(Frame frame, double ax, double ay, double az, double bx, double by, double bz,
                double cx, double cy, double cz, double dx, double dy, double dz, double nx, double ny, double nz) {
            quad(frame, new double[] { ax, ay, az }, new double[] { bx, by, bz },
                    new double[] { cx, cy, cz }, new double[] { dx, dy, dz }, nx, ny, nz);
        }

        void triangle(Frame frame, double ax, double ay, double az, double bx, double by, double bz,
                double cx, double cy, double cz, double nx, double ny, double nz) {
            int first = vertices.size() / 8;
            float[] normal = frame.normal(nx, ny, nz);
            float uLength = (float) Math.hypot(bx - ax, bz - az) / 8f;
            float vLength = (float) Math.sqrt((cx - ax) * (cx - ax) + (cy - ay) * (cy - ay)
                    + (cz - az) * (cz - az)) / 8f;
            vertex(frame.position(ax, ay, az), normal, 0, 0);
            vertex(frame.position(bx, by, bz), normal, uLength, 0);
            vertex(frame.position(cx, cy, cz), normal, uLength, vLength);
            indices.add(first); indices.add(first + 1); indices.add(first + 2);
        }

        private void vertex(float[] position, float[] normal, float u, float v) {
            for (float value : position) vertices.add(value);
            for (float value : normal) vertices.add(value);
            vertices.add(u); vertices.add(v);
        }

        float[] vertices() {
            float[] result = new float[vertices.size()];
            for (int i = 0; i < result.length; i++) result[i] = vertices.get(i);
            return result;
        }

        int[] indices() {
            int[] result = new int[indices.size()];
            for (int i = 0; i < result.length; i++) result[i] = indices.get(i);
            return result;
        }
    }

    private static SpherePosition onGround(GeodesicChunkGrid.Direction direction, PerlinNoise noise) {
        double height = height(direction, noise);
        double radius = Planet.radius() + height;
        return new SpherePosition(direction.x() * radius, direction.y() * radius, direction.z() * radius);
    }

    private static double height(GeodesicChunkGrid.Direction direction, PerlinNoise noise) {
        return TerrainMesh.getLayeredHeight(new SpherePosition(direction.x() * Planet.radius(),
                direction.y() * Planet.radius(), direction.z() * Planet.radius()), noise);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
