import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    // (with its unit direction and its extent as an angle, for a quick test before the exact one)
    private record Entry<T>(SpherePosition position, double extent, T item, double ux, double uy, double uz, double angle) {
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
        double length = position.length();
        Entry<T> entry = new Entry<>(position, extent, item, position.x() / length, position.y() / length, position.z() / length,
                extent / Planet.radius());
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
        double reach = (radius + largestExtent) / Planet.radius(), radiusAngle = radius / Planet.radius();
        long start = grid.cellAt(origin).id();
        // (a breadth-first search: the queue is also the list of cells seen)
        long[] queue = new long[32];
        int head = 0, tail = 0;
        queue[tail++] = start;
        IdSet seen = new IdSet();
        seen.add(start);
        while (head < tail) {
            long id = queue[head++];
            Shape shape = shape(id);
            // (the cells meeting the circle of reach join up edge to edge, so the search stops at
            // the first ring of cells clear of it; cos x >= 1 - x^2 / 2, so a cell failing that is
            // certainly clear, and the exact angle is only worked out for the rest)
            if (id != start) {
                double limit = reach + shape.radius();
                if (limit < Math.PI && (origin.dot(shape.centre()) < 1.0 - limit * limit * 0.5
                        || SphereMath.angle(origin, shape.centre()) > limit)) continue;
            }
            List<Entry<T>> entries = cells.get(id);
            if (entries != null) {
                for (Entry<T> entry : entries) {
                    double limit = radiusAngle + entry.angle();
                    if (limit < Math.PI && origin.x() * entry.ux() + origin.y() * entry.uy() + origin.z() * entry.uz() < 1.0 - limit * limit * 0.5) continue;
                    double distance = SphereMath.distance(position, entry.position()) - entry.extent();
                    if (distance <= radius) visitor.found(entry, Math.max(0.0, distance));
                }
            }
            for (long neighbour : shape.neighbours()) {
                if (!seen.add(neighbour)) continue;
                if (tail == queue.length) queue = Arrays.copyOf(queue, tail * 2);
                queue[tail++] = neighbour;
            }
        }
    }

    /** A set of cell ids, open addressed (no boxing: a search adds a few tens of them). */
    private static final class IdSet {
        private long[] slots = new long[64];
        private boolean[] used = new boolean[64];
        private int count;

        /** Adds an id; false if it was there already. */
        boolean add(long id) {
            if (count * 2 >= slots.length) grow();
            int mask = slots.length - 1;
            int i = (int) (Long.hashCode(id * 0x9E3779B97F4A7C15L)) & mask;
            while (used[i]) {
                if (slots[i] == id) return false;
                i = (i + 1) & mask;
            }
            used[i] = true;
            slots[i] = id;
            count++;
            return true;
        }

        private void grow() {
            long[] oldSlots = slots;
            boolean[] oldUsed = used;
            slots = new long[oldSlots.length * 2];
            used = new boolean[oldSlots.length * 2];
            count = 0;
            for (int i = 0; i < oldSlots.length; i++) if (oldUsed[i]) add(oldSlots[i]);
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
