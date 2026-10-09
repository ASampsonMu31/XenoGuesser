import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Builds deterministic, latitude-independent streets around geodesic settlement hubs. */
public final class GeodesicRoadMesh {
    private static final double MIN_LAND_HEIGHT = 2.0;
    private static final double MAX_SECTION_LENGTH = 28.0;
    private static final int SETTLEMENT_LEVEL = 4;
    private static final long SETTLEMENT_SEED = 0x534554544c454d45L;

    private GeodesicRoadMesh() {
    }

    public record Patch(float[] vertices, int[] indices, List<RouteSegment> routes) {
    }

    /** One native route edge following the generated road centreline. */
    public record RouteSegment(SpherePosition start, SpherePosition end, double halfWidth) {
    }

    public static Patch buildPatchData(SpherePosition centre, double angularRadius,
            float seaLevel, long worldSeed, PerlinNoise noise) {
        GeodesicChunkGrid.Direction origin =
                new GeodesicChunkGrid.Direction(centre.x(), centre.y(), centre.z()).normalized();
        List<GeodesicChunkGrid.Cell> hubs = new GeodesicChunkGrid(SETTLEMENT_LEVEL)
                .cellsWithin(origin, angularRadius + 0.06);
        List<Float> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        List<RouteSegment> routes = new ArrayList<>();

        for (GeodesicChunkGrid.Cell hub : hubs) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Geodesic road build cancelled");
            }
            if (!isLand(hub.centre(), seaLevel, noise)) continue;
            Random random = new Random(mix(worldSeed ^ hub.id() ^ SETTLEMENT_SEED));
            if (random.nextFloat() > 0.42f) continue;
            appendSettlementStreets(hub.centre(), random, seaLevel, noise, vertices, indices, routes);
        }

        float[] vertexData = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) vertexData[i] = vertices.get(i);
        int[] indexData = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) indexData[i] = indices.get(i);
        return new Patch(vertexData, indexData, List.copyOf(routes));
    }

    private static void appendSettlementStreets(GeodesicChunkGrid.Direction hub, Random random,
            float seaLevel, PerlinNoise noise, List<Float> vertices, List<Integer> indices,
            List<RouteSegment> routes) {
        double streetHalfWidth = 4.0 + random.nextDouble() * 2.0;
        double firstBearing = random.nextDouble() * Math.PI * 2.0;
        double spokeLength = 650.0 + random.nextDouble() * 450.0;
        GeodesicChunkGrid.Direction[] ring = new GeodesicChunkGrid.Direction[8];
        for (int i = 0; i < ring.length; i++) {
            double bearing = firstBearing + i * Math.PI * 2.0 / ring.length;
            ring[i] = offset(hub, bearing, 260.0);
            appendRoad(hub, ring[i], streetHalfWidth, seaLevel, noise, vertices, indices, routes);
            appendRoad(ring[i], offset(hub, bearing, spokeLength), streetHalfWidth,
                    seaLevel, noise, vertices, indices, routes);
        }
        for (int i = 0; i < ring.length; i++) {
            appendRoad(ring[i], ring[(i + 1) % ring.length], streetHalfWidth,
                    seaLevel, noise, vertices, indices, routes);
        }
    }

    private static GeodesicChunkGrid.Direction offset(GeodesicChunkGrid.Direction origin,
            double bearing, double distance) {
        double length = distance / Planet.radius();
        double lat = Math.asin(origin.y());
        double lon = Math.atan2(origin.z(), origin.x());
        double eastX = -Math.sin(lon), eastZ = Math.cos(lon);
        double northX = -Math.sin(lat) * Math.cos(lon);
        double northY = Math.cos(lat);
        double northZ = -Math.sin(lat) * Math.sin(lon);
        double tangentX = eastX * Math.sin(bearing) + northX * Math.cos(bearing);
        double tangentY = northY * Math.cos(bearing);
        double tangentZ = eastZ * Math.sin(bearing) + northZ * Math.cos(bearing);
        double cosine = Math.cos(length), sine = Math.sin(length);
        return new GeodesicChunkGrid.Direction(
                origin.x() * cosine + tangentX * sine,
                origin.y() * cosine + tangentY * sine,
                origin.z() * cosine + tangentZ * sine).normalized();
    }

    private static boolean isLand(GeodesicChunkGrid.Direction direction, float seaLevel, PerlinNoise noise) {
        SpherePosition point = new SpherePosition(direction.x() * Planet.radius(),
                direction.y() * Planet.radius(), direction.z() * Planet.radius());
        return TerrainMesh.getLayeredHeight(point, noise) > seaLevel + MIN_LAND_HEIGHT;
    }

    private static void appendRoad(GeodesicChunkGrid.Direction start, GeodesicChunkGrid.Direction end,
            double halfWidth, float seaLevel, PerlinNoise noise, List<Float> vertices, List<Integer> indices,
            List<RouteSegment> routes) {
        double angle = start.angleTo(end);
        if (!(angle > 1e-10)) return;
        double length = angle * Planet.radius();
        int sections = Math.max(1, Math.min(256, (int) Math.ceil(length / MAX_SECTION_LENGTH)));
        GeodesicChunkGrid.Direction previous = null;
        double previousHeight = 0.0;
        double previousSideX = 0.0, previousSideY = 0.0, previousSideZ = 0.0;

        for (int section = 0; section <= sections; section++) {
            double t = section / (double) sections;
            GeodesicChunkGrid.Direction direction = slerp(start, end, t, angle);
            double nearbyT = t < 1.0 ? Math.min(1.0, t + 1e-5) : Math.max(0.0, t - 1e-5);
            GeodesicChunkGrid.Direction nearby = slerp(start, end, nearbyT, angle);
            double sign = nearbyT > t ? 1.0 : -1.0;
            double tx = sign * (nearby.x() - direction.x() * direction.dot(nearby));
            double ty = sign * (nearby.y() - direction.y() * direction.dot(nearby));
            double tz = sign * (nearby.z() - direction.z() * direction.dot(nearby));
            double tangentLength = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (!(tangentLength > 1e-12)) continue;
            tx /= tangentLength;
            ty /= tangentLength;
            tz /= tangentLength;
            double sideX = direction.y() * tz - direction.z() * ty;
            double sideY = direction.z() * tx - direction.x() * tz;
            double sideZ = direction.x() * ty - direction.y() * tx;
            double sideLength = Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
            sideX /= sideLength;
            sideY /= sideLength;
            sideZ /= sideLength;
            double ground = height(direction, noise);

            if (previous != null && ground > seaLevel && previousHeight > seaLevel) {
                int first = vertices.size() / 8;
                appendVertex(vertices, previous, previousSideX, previousSideY, previousSideZ, -halfWidth, previousHeight);
                appendVertex(vertices, previous, previousSideX, previousSideY, previousSideZ, halfWidth, previousHeight);
                appendVertex(vertices, direction, sideX, sideY, sideZ, -halfWidth, ground);
                appendVertex(vertices, direction, sideX, sideY, sideZ, halfWidth, ground);
                indices.add(first);
                indices.add(first + 2);
                indices.add(first + 1);
                indices.add(first + 2);
                indices.add(first + 3);
                indices.add(first + 1);
                routes.add(new RouteSegment(onGround(previous, previousHeight),
                        onGround(direction, ground), halfWidth));
            }
            previous = direction;
            previousHeight = ground;
            previousSideX = sideX;
            previousSideY = sideY;
            previousSideZ = sideZ;
        }
    }

    private static void appendVertex(List<Float> vertices, GeodesicChunkGrid.Direction direction,
            double sideX, double sideY, double sideZ, double offset, double height) {
        double lateralAngle = offset / Planet.radius();
        double cosine = Math.cos(lateralAngle), sine = Math.sin(lateralAngle);
        double x = direction.x() * cosine + sideX * sine;
        double y = direction.y() * cosine + sideY * sine;
        double z = direction.z() * cosine + sideZ * sine;
        double radius = Planet.radius() + height + 0.35;
        vertices.add((float) (x * radius));
        vertices.add((float) (y * radius));
        vertices.add((float) (z * radius));
        vertices.add((float) x);
        vertices.add((float) y);
        vertices.add((float) z);
        vertices.add((float) (offset / 4.4));
        vertices.add(0f);
    }

    private static double height(GeodesicChunkGrid.Direction direction, PerlinNoise noise) {
        return TerrainMesh.getLayeredHeight(new SpherePosition(direction.x() * Planet.radius(),
                direction.y() * Planet.radius(), direction.z() * Planet.radius()), noise);
    }

    private static SpherePosition onGround(GeodesicChunkGrid.Direction direction, double height) {
        double radius = Planet.radius() + height;
        return new SpherePosition(direction.x() * radius, direction.y() * radius, direction.z() * radius);
    }

    private static GeodesicChunkGrid.Direction slerp(GeodesicChunkGrid.Direction a,
            GeodesicChunkGrid.Direction b, double t, double angle) {
        double denominator = Math.sin(angle);
        double wa = Math.sin((1.0 - t) * angle) / denominator;
        double wb = Math.sin(t * angle) / denominator;
        return new GeodesicChunkGrid.Direction(wa * a.x() + wb * b.x(),
                wa * a.y() + wb * b.y(), wa * a.z() + wb * b.z()).normalized();
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
