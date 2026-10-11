import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A spatial index on the sphere (D5 in GEODESIC_MIGRATION_PLAN.txt). Items are filed under the
 * geodesic cell (GeodesicChunkGrid at a fixed level) holding their position and found again by
 * distance along the surface, searching outward from the query's cell through its neighbours.
 * The cells are near enough the same size everywhere that a search costs the same at the
 * equator, at 70 degrees and at a pole.
 * <p>
 * An item may have an extent (how far it reaches from its position, as a road segment or a
 * house does); it is found when any of it is in reach. Searches may run alongside one writer:
 * a cell's list is replaced, never changed in place, when an item is added or removed.
 */
public final class CellIndex<T> {

    /** Cells about 100 world units (10 m) across. */
    public static final int DEFAULT_LEVEL = 8;
    private static final int SHAPE_CACHE_LIMIT = 200_000;

    private record Entry<T>(SpherePosition position, double extent, T item) {
    }

    /** A cell's centre, its angular radius and its edge neighbours, kept once worked out. */
    private record Shape(GeodesicChunkGrid.Direction centre, double radius, long[] neighbours) {
    }

    private final GeodesicChunkGrid grid;
    private final Map<Long, List<Entry<T>>> cells = new ConcurrentHashMap<>();
    private final Map<Long, Shape> shapes = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private volatile double largestExtent;

    public CellIndex() {
        this(DEFAULT_LEVEL);
    }

    public CellIndex(int level) {
        grid = new GeodesicChunkGrid(level);
    }

    public int level() {
        return grid.level();
    }

    public int size() {
        return size.get();
    }

    public boolean isEmpty() {
        return size.get() == 0;
    }

    /** The id of the cell a position is filed under. */
    public long cellId(SpherePosition position) {
        return grid.cellAt(position).id();
    }

    public void add(SpherePosition position, T item) {
        add(position, 0.0, item);
    }

    /** Files an item reaching extent world units (along the surface) from position. */
    public void add(SpherePosition position, double extent, T item) {
        if (!Double.isFinite(extent) || extent < 0.0) throw new IllegalArgumentException("Extent must be finite and non-negative");
        Entry<T> entry = new Entry<>(position, extent, item);
        cells.compute(cellId(position), (id, list) -> {
            List<Entry<T>> grown = list == null ? new ArrayList<>(1) : new ArrayList<>(list);
            grown.add(entry);
            return List.copyOf(grown);
        });
        size.incrementAndGet();
        if (extent > largestExtent) largestExtent = extent;
    }

    /** Takes out one filing of item at position (the position it was added with); false if there was none. */
    public boolean remove(SpherePosition position, T item) {
        boolean[] removed = new boolean[1];
        cells.computeIfPresent(cellId(position), (id, list) -> {
            List<Entry<T>> kept = new ArrayList<>(list);
            for (int i = 0; i < kept.size(); i++) {
                if (Objects.equals(kept.get(i).item(), item)) {
                    kept.remove(i);
                    removed[0] = true;
                    break;
                }
            }
            return kept.isEmpty() ? null : List.copyOf(kept);
        });
        if (removed[0]) size.decrementAndGet();
        return removed[0];
    }

    public void clear() {
        cells.clear();
        size.set(0);
        largestExtent = 0.0;
    }

    /** Every item with some part within radius world units (along the surface) of a place. */
    public List<T> near(SpherePosition position, double radius) {
        List<T> found = new ArrayList<>();
        forEachNear(position, radius, found::add);
        return found;
    }

    /** The item reaching nearest a place, if any reaches within radius; otherwise null. */
    public T nearest(SpherePosition position, double radius) {
        Object[] best = { null };
        double[] bestDistance = { Double.MAX_VALUE };
        visit(position, radius, (entry, distance) -> {
            if (distance < bestDistance[0]) {
                bestDistance[0] = distance;
                best[0] = entry.item();
            }
        });
        @SuppressWarnings("unchecked")
        T item = (T) best[0];
        return item;
    }

    /** Hands every item with some part within radius of a place to action. */
    public void forEachNear(SpherePosition position, double radius, Consumer<T> action) {
        visit(position, radius, (entry, distance) -> action.accept(entry.item()));
    }

    private interface Visitor<T> {
        void found(Entry<T> entry, double distance);
    }

    private void visit(SpherePosition position, double radius, Visitor<T> visitor) {
        if (!Double.isFinite(radius) || radius < 0.0) throw new IllegalArgumentException("Radius must be finite and non-negative");
        if (size.get() == 0) return;
        GeodesicChunkGrid.Direction origin = SphereMath.direction(position);
        double reach = (radius + largestExtent) / Planet.radius();
        long start = grid.cellAt(origin).id();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            long id = queue.removeFirst();
            Shape shape = shape(id);
            // (the cells meeting the circle of reach join up edge to edge, so the search stops at
            // the first ring of cells clear of it)
            if (id != start && SphereMath.angle(origin, shape.centre()) > reach + shape.radius()) continue;
            List<Entry<T>> entries = cells.get(id);
            if (entries != null) {
                for (Entry<T> entry : entries) {
                    double distance = SphereMath.distance(position, entry.position()) - entry.extent();
                    if (distance <= radius) visitor.found(entry, Math.max(0.0, distance));
                }
            }
            for (long neighbour : shape.neighbours()) {
                if (seen.add(neighbour)) queue.addLast(neighbour);
            }
        }
    }

    private Shape shape(long id) {
        Shape shape = shapes.get(id);
        if (shape != null) return shape;
        if (shapes.size() > SHAPE_CACHE_LIMIT) shapes.clear();
        GeodesicChunkGrid.Cell cell = grid.cell(id);
        long[] neighbours = grid.neighbours(cell).stream().mapToLong(GeodesicChunkGrid.Cell::id).toArray();
        shape = new Shape(cell.centre(), cell.angularRadius(), Arrays.copyOf(neighbours, neighbours.length));
        shapes.put(id, shape);
        return shape;
    }
}
