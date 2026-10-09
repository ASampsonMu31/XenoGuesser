import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Chart-free collision and local route search on the planet surface.
 * Obstacle centres and route points are planet-centred positions; radii and path costs are
 * true surface distances.
 */
public final class GeodesicCollision {
    private static final int SEARCH_LIMIT = 20000;
    private static final int ROUTE_LEVEL = 11;

    private sealed interface Obstacle permits Circle, Segment {
        double clearance(SpherePosition point);
    }

    private record Circle(SpherePosition centre, double radius, double top) implements Obstacle {
        @Override
        public double clearance(SpherePosition point) {
            return Planet.surfaceDistance(centre, point) - radius;
        }
    }

    private record Segment(SpherePosition start, SpherePosition end, double halfWidth, double top)
            implements Obstacle {
        @Override
        public double clearance(SpherePosition point) {
            double length = Planet.surfaceDistance(start, end);
            if (length < 1e-8) return Planet.surfaceDistance(start, point) - halfWidth;
            int samples = Math.max(2, Math.min(64, (int) Math.ceil(length / Math.max(1.0, halfWidth))));
            double closest = Double.POSITIVE_INFINITY;
            for (int i = 0; i <= samples; i++) {
                SpherePosition sample = Planet.interpolateSurface(start, end, i / (double) samples);
                closest = Math.min(closest, Planet.surfaceDistance(sample, point));
            }
            return closest - halfWidth;
        }
    }

    private final List<Obstacle> obstacles = new ArrayList<>();
    private final GeodesicChunkGrid grid = new GeodesicChunkGrid(ROUTE_LEVEL);
    private final Map<Long, List<Obstacle>> obstaclesByCell = new HashMap<>();
    private final Map<Long, List<Segment>> routesByCell = new HashMap<>();
    private final Map<NodeKey, SpherePosition> routeNodes = new HashMap<>();
    private final Map<NodeKey, List<RouteEdge>> routes = new HashMap<>();

    private record NodeKey(long x, long y, long z) {
    }

    private record RouteEdge(NodeKey destination, double width) {
    }

    public void clear() {
        obstacles.clear();
        obstaclesByCell.clear();
        routesByCell.clear();
        routeNodes.clear();
        routes.clear();
    }

    public void addCircle(SpherePosition centre, double radius, double top) {
        requireRadius(radius);
        requireTop(top);
        addObstacle(new Circle(groundPosition(centre), radius, top), radius);
    }

    public void addSegment(SpherePosition start, SpherePosition end, double halfWidth, double top) {
        requireRadius(halfWidth);
        requireTop(top);
        SpherePosition surfaceStart = groundPosition(start), surfaceEnd = groundPosition(end);
        addObstacle(new Segment(surfaceStart, surfaceEnd, halfWidth, top),
                Planet.surfaceDistance(surfaceStart, surfaceEnd) * 0.5 + halfWidth);
    }

    /** Adds a native road-centreline edge to the routable graph. */
    public void addRouteSegment(SpherePosition start, SpherePosition end, double halfWidth) {
        requireRadius(halfWidth);
        SpherePosition surfaceStart = groundPosition(start), surfaceEnd = groundPosition(end);
        if (Planet.surfaceDistance(surfaceStart, surfaceEnd) < 1e-6) return;
        NodeKey from = nodeKey(surfaceStart), to = nodeKey(surfaceEnd);
        routeNodes.putIfAbsent(from, surfaceStart);
        routeNodes.putIfAbsent(to, surfaceEnd);
        routes.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new RouteEdge(to, halfWidth));
        routes.computeIfAbsent(to, ignored -> new ArrayList<>()).add(new RouteEdge(from, halfWidth));
        Segment road = new Segment(surfaceStart, surfaceEnd, halfWidth, Double.POSITIVE_INFINITY);
        indexSegment(road, Planet.surfaceDistance(surfaceStart, surfaceEnd) * 0.5 + halfWidth,
                routesByCell);
    }

    /** True when a native spherical location lies within the requested distance of a generated road. */
    public boolean nearRoute(SpherePosition position, double distance) {
        requireRadius(distance);
        SpherePosition ground = groundPosition(position);
        GeodesicChunkGrid.Cell cell = cellFor(ground);
        double reach = distance / Planet.radius() + 2.0 * cell.angularRadius();
        GeodesicChunkGrid.Direction direction =
                new GeodesicChunkGrid.Direction(ground.x(), ground.y(), ground.z());
        Map<Segment, Boolean> unique = new HashMap<>();
        for (GeodesicChunkGrid.Cell nearby : grid.cellsWithin(direction, reach)) {
            for (Segment segment : routesByCell.getOrDefault(nearby.id(), List.of())) {
                unique.put(segment, Boolean.TRUE);
            }
        }
        for (Segment segment : unique.keySet()) {
            if (segment.clearance(ground) < distance) return true;
        }
        return false;
    }

    /** True when the moving body's surface clearance intersects an obstacle below its height. */
    public boolean blocked(SpherePosition position, double radius, double clearance) {
        requireRadius(radius);
        requireTop(clearance);
        SpherePosition ground = groundPosition(position);
        for (Obstacle obstacle : nearbyObstacles(ground, radius)) {
            double top = obstacle instanceof Circle circle ? circle.top()
                    : ((Segment) obstacle).top();
            if (top >= clearance && obstacle.clearance(ground) < radius) return true;
        }
        return false;
    }

    /**
     * Pushes a proposed position out of obstacles in the local tangent plane. Returns the
     * original point if it is clear; the caller can stop movement when no free point is found.
     */
    public SpherePosition resolve(SpherePosition proposed, double radius, double clearance) {
        requireRadius(radius);
        requireTop(clearance);
        SpherePosition result = groundPosition(proposed);
        List<Obstacle> nearby = nearbyObstacles(result, radius);
        for (int pass = 0; pass < 5; pass++) {
            boolean moved = false;
            for (Obstacle obstacle : nearby) {
                double top = obstacle instanceof Circle circle ? circle.top()
                        : ((Segment) obstacle).top();
                if (top < clearance) continue;
                double distance = obstacle.clearance(result);
                if (distance >= radius) continue;
                SpherePosition nearest = nearestPoint(obstacle, result);
                double push = radius - distance + 0.02;
                double[] bearing = bearing(nearest, result);
                if (Math.hypot(bearing[0], bearing[1]) < 1e-9) {
                    bearing = new double[] { 1.0, 0.0 };
                }
                result = Planet.moveOnSurface(nearest, bearing[0], bearing[1], push);
                moved = true;
            }
            if (!moved) return result;
        }
        return blocked(result, radius, clearance) ? groundPosition(proposed) : result;
    }

    /**
     * A* route over connected geodesic cells. Route nodes are cell-centre sphere positions,
     * never chart coordinates. Returns null when no route is found within maxDistance.
     */
    public List<SpherePosition> findPath(SpherePosition start, SpherePosition goal, double radius,
            double maxDistance, double clearance) {
        requireRadius(radius);
        requireRadius(maxDistance);
        requireTop(clearance);
        SpherePosition source = groundPosition(start), destination = groundPosition(goal);
        GeodesicChunkGrid.Cell first = cellFor(source), last = cellFor(destination);
        if (first.id() == last.id()) return List.of(destination);
        PriorityQueue<QueueEntry> open = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::score));
        Map<Long, Double> costs = new HashMap<>();
        Map<Long, Long> previous = new HashMap<>();
        Map<Long, GeodesicChunkGrid.Cell> visitedCells = new HashMap<>();
        costs.put(first.id(), 0.0);
        visitedCells.put(first.id(), first);
        open.add(new QueueEntry(first.id(), Planet.surfaceDistance(cellPosition(first), destination)));
        int expanded = 0;
        boolean found = false;
        while (!open.isEmpty() && expanded++ < SEARCH_LIMIT) {
            QueueEntry entry = open.remove();
            GeodesicChunkGrid.Cell current = visitedCells.get(entry.cellId());
            double cost = costs.getOrDefault(entry.cellId(), Double.POSITIVE_INFINITY);
            if (current == null || entry.score() > cost + Planet.surfaceDistance(cellPosition(current), destination) + 1e-6) continue;
            if (current.id() == last.id()) {
                found = true;
                break;
            }
            for (GeodesicChunkGrid.Cell neighbour : grid.neighbours(current)) {
                SpherePosition point = cellPosition(neighbour);
                SpherePosition currentPoint = cellPosition(current);
                double edgeLength = Planet.surfaceDistance(currentPoint, point);
                double nextCost = cost + edgeLength;
                if (nextCost > maxDistance || !clearSegment(currentPoint, point, edgeLength,
                        radius, clearance)) continue;
                if (nextCost >= costs.getOrDefault(neighbour.id(), Double.POSITIVE_INFINITY)) continue;
                costs.put(neighbour.id(), nextCost);
                previous.put(neighbour.id(), current.id());
                visitedCells.put(neighbour.id(), neighbour);
                open.add(new QueueEntry(neighbour.id(),
                        nextCost + Planet.surfaceDistance(point, destination)));
            }
        }
        if (!found) return null;

        List<SpherePosition> path = new ArrayList<>();
        long cursor = last.id();
        while (cursor != first.id()) {
            GeodesicChunkGrid.Cell cell = visitedCells.get(cursor);
            if (cell == null) return null;
            path.add(0, cellPosition(cell));
            Long parent = previous.get(cursor);
            if (parent == null) return null;
            cursor = parent;
        }
        if (!blocked(destination, radius, clearance)) path.add(destination);
        return path;
    }

    /** Finds a shortest path along native generated roads, snapped to the network within maxSnap. */
    public List<SpherePosition> findRoadPath(SpherePosition start, SpherePosition goal, double maxSnap) {
        requireRadius(maxSnap);
        if (routes.isEmpty()) return null;
        SpherePosition source = groundPosition(start), destination = groundPosition(goal);
        NodeKey sourceNode = nearestRouteNode(source, maxSnap);
        NodeKey destinationNode = nearestRouteNode(destination, maxSnap);
        if (sourceNode == null || destinationNode == null) return null;

        PriorityQueue<RouteQueueEntry> open = new PriorityQueue<>(Comparator.comparingDouble(RouteQueueEntry::score));
        Map<NodeKey, Double> cost = new HashMap<>();
        Map<NodeKey, NodeKey> previous = new HashMap<>();
        cost.put(sourceNode, 0.0);
        open.add(new RouteQueueEntry(sourceNode, Planet.surfaceDistance(routeNodes.get(sourceNode),
                routeNodes.get(destinationNode))));
        while (!open.isEmpty()) {
            RouteQueueEntry entry = open.remove();
            double currentCost = cost.getOrDefault(entry.node(), Double.POSITIVE_INFINITY);
            if (entry.score() > currentCost + Planet.surfaceDistance(routeNodes.get(entry.node()),
                    routeNodes.get(destinationNode)) + 1e-6) continue;
            if (entry.node().equals(destinationNode)) break;
            for (RouteEdge edge : routes.getOrDefault(entry.node(), List.of())) {
                SpherePosition from = routeNodes.get(entry.node()), to = routeNodes.get(edge.destination());
                double nextCost = currentCost + Planet.surfaceDistance(from, to);
                if (nextCost >= cost.getOrDefault(edge.destination(), Double.POSITIVE_INFINITY)) continue;
                cost.put(edge.destination(), nextCost);
                previous.put(edge.destination(), entry.node());
                open.add(new RouteQueueEntry(edge.destination(),
                        nextCost + Planet.surfaceDistance(to, routeNodes.get(destinationNode))));
            }
        }
        if (!cost.containsKey(destinationNode)) return null;
        List<SpherePosition> path = new ArrayList<>();
        for (NodeKey node = destinationNode; node != null; node = previous.get(node)) {
            path.add(0, routeNodes.get(node));
            if (node.equals(sourceNode)) break;
        }
        if (!path.get(0).equals(routeNodes.get(sourceNode))) return null;
        path.add(0, source);
        path.add(destination);
        return path;
    }

    private record RouteQueueEntry(NodeKey node, double score) {
    }

    private NodeKey nearestRouteNode(SpherePosition point, double maxSnap) {
        NodeKey nearest = null;
        double best = maxSnap;
        for (Map.Entry<NodeKey, SpherePosition> entry : routeNodes.entrySet()) {
            double distance = Planet.surfaceDistance(point, entry.getValue());
            if (distance <= best) {
                best = distance;
                nearest = entry.getKey();
            }
        }
        return nearest;
    }

    private void addObstacle(Obstacle obstacle, double extent) {
        obstacles.add(obstacle);
        if (obstacle instanceof Segment segment) {
            SpherePosition centre = Planet.interpolateSurface(segment.start(), segment.end(), 0.5);
            indexFeature(segment, centre, extent, obstaclesByCell);
            return;
        }
        SpherePosition centre = ((Circle) obstacle).centre();
        indexFeature(obstacle, centre, extent, obstaclesByCell);
    }

    private void indexSegment(Segment segment, double extent, Map<Long, List<Segment>> index) {
        SpherePosition centre = Planet.interpolateSurface(segment.start(), segment.end(), 0.5);
        indexFeature(segment, centre, extent, index);
    }

    private <T> void indexFeature(T feature, SpherePosition centre, double extent,
            Map<Long, List<T>> index) {
        double angularRadius = Math.min(Math.PI, extent / Planet.radius());
        GeodesicChunkGrid.Direction direction =
                new GeodesicChunkGrid.Direction(centre.x(), centre.y(), centre.z());
        for (GeodesicChunkGrid.Cell cell : grid.cellsWithin(direction, angularRadius)) {
            index.computeIfAbsent(cell.id(), ignored -> new ArrayList<>()).add(feature);
        }
    }

    private List<Obstacle> nearbyObstacles(SpherePosition point, double radius) {
        GeodesicChunkGrid.Cell cell = cellFor(point);
        double reach = radius / Planet.radius() + 2.0 * cell.angularRadius();
        GeodesicChunkGrid.Direction direction =
                new GeodesicChunkGrid.Direction(point.x(), point.y(), point.z());
        Map<Obstacle, Boolean> unique = new HashMap<>();
        for (GeodesicChunkGrid.Cell nearby : grid.cellsWithin(direction, reach)) {
            for (Obstacle obstacle : obstaclesByCell.getOrDefault(nearby.id(), List.of())) {
                unique.put(obstacle, Boolean.TRUE);
            }
        }
        return new ArrayList<>(unique.keySet());
    }

    private static NodeKey nodeKey(SpherePosition point) {
        double precision = 0.1;
        return new NodeKey(Math.round(point.x() / precision), Math.round(point.y() / precision),
                Math.round(point.z() / precision));
    }

    private boolean clearSegment(SpherePosition start, SpherePosition end, double length,
            double radius, double clearance) {
        int samples = Math.max(1, (int) Math.ceil(length / Math.max(1.0, radius * 0.5)));
        for (int i = 1; i <= samples; i++) {
            SpherePosition point = Planet.interpolateSurface(start, end, i / (double) samples);
            if (blocked(point, radius, clearance)) return false;
        }
        return true;
    }

    private record QueueEntry(long cellId, double score) {
    }

    private GeodesicChunkGrid.Cell cellFor(SpherePosition position) {
        return grid.cellAt(new GeodesicChunkGrid.Direction(position.x(), position.y(), position.z()));
    }

    private static SpherePosition cellPosition(GeodesicChunkGrid.Cell cell) {
        double height = Planet.radius();
        return new SpherePosition(cell.centre().x() * height, cell.centre().y() * height,
                cell.centre().z() * height);
    }

    private static SpherePosition groundPosition(SpherePosition position) {
        double length = position.length();
        if (!(length > 0.0)) throw new IllegalArgumentException("Position cannot be at the planet centre");
        double radius = Planet.radius();
        return new SpherePosition(position.x() * radius / length, position.y() * radius / length,
                position.z() * radius / length);
    }

    private static SpherePosition nearestPoint(Obstacle obstacle, SpherePosition point) {
        if (obstacle instanceof Circle circle) return circle.centre();
        Segment segment = (Segment) obstacle;
        double length = Planet.surfaceDistance(segment.start(), segment.end());
        int samples = Math.max(2, Math.min(64, (int) Math.ceil(length / Math.max(1.0, segment.halfWidth()))));
        SpherePosition nearest = segment.start();
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= samples; i++) {
            SpherePosition candidate = Planet.interpolateSurface(segment.start(), segment.end(),
                    i / (double) samples);
            double distance = Planet.surfaceDistance(candidate, point);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /** Local east/south components pointing from one surface position to another. */
    private static double[] bearing(SpherePosition from, SpherePosition to) {
        double length = from.length();
        double latitude = Math.asin(from.y() / length);
        double longitude = Math.atan2(from.z(), from.x());
        double upX = Math.cos(latitude) * Math.cos(longitude);
        double upY = Math.sin(latitude);
        double upZ = Math.cos(latitude) * Math.sin(longitude);
        double eastX = -Math.sin(longitude), eastZ = Math.cos(longitude);
        double southX = Math.sin(latitude) * Math.cos(longitude);
        double southY = -Math.cos(latitude);
        double southZ = Math.sin(latitude) * Math.sin(longitude);
        double targetLength = to.length();
        double dot = (from.x() * to.x() + from.y() * to.y() + from.z() * to.z())
                / (length * targetLength);
        double tx = to.x() / targetLength - dot * upX;
        double ty = to.y() / targetLength - dot * upY;
        double tz = to.z() / targetLength - dot * upZ;
        return new double[] { tx * eastX + tz * eastZ,
                tx * southX + ty * southY + tz * southZ };
    }

    private static void requireRadius(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Distance must be finite and non-negative");
        }
    }

    private static void requireTop(double value) {
        if (Double.isNaN(value)) throw new IllegalArgumentException("Obstacle top cannot be NaN");
    }
}
