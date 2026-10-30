import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/**
 * Something worked out per geodesic cell (trees, grass) and kept for the cells within reach of
 * the viewer: cells coming into reach are worked out on builder threads, nearest first (those
 * right round the viewer at once, on the calling thread), and cells left well behind are
 * dropped. The cells are near enough equal in size everywhere that this costs the same at the
 * equator, at 70 degrees and at a pole.
 * <p>
 * The next round's surroundings can be worked out ahead, in spare time on a thread of their
 * own (prefetchAround and pump); a cell found there when it is wanted is taken as it is.
 * update, clear and the prefetch calls are for one thread (the GL thread).
 */
public final class CellStream<T> {

    private final GeodesicChunkGrid grid;
    private volatile double reach;
    private final double immediateReach;
    private final LongFunction<T> builder;
    private final Map<Long, T> ready = new HashMap<>();
    private final Map<Long, Future<T>> building = new HashMap<>();
    private final Map<Long, T> prefetched = new ConcurrentHashMap<>();
    private final List<Long> prefetchQueue = new ArrayList<>();
    private Future<?> prefetchJob;
    private Consumer<T> onAdded = t -> { }, onRemoved = t -> { };
    private int wantedCount, missing = -1;
    // The cells wanted round the viewer's cell, worked out again only when the viewer changes cell
    private long wantedFor = Long.MIN_VALUE;
    private List<GeodesicChunkGrid.Cell> wantedCells = List.of();
    private Set<Long> wantedIds = Set.of();

    /**
     * Cells of the grid at level, within reach (world units along the surface) of the viewer,
     * those within immediateReach built at once; builder works out a cell from its id (on any
     * thread).
     */
    public CellStream(int level, double reach, double immediateReach, LongFunction<T> builder) {
        this.grid = new GeodesicChunkGrid(level);
        this.reach = reach;
        this.immediateReach = immediateReach;
        this.builder = builder;
    }

    /** Called with each cell's contents as they come into use and as they are dropped. */
    public CellStream<T> onChange(Consumer<T> added, Consumer<T> removed) {
        onAdded = added;
        onRemoved = removed;
        return this;
    }

    /** How far (world units along the surface) cells are kept round the viewer from now on: changed between rounds, after clear. */
    public void setReach(double reach) {
        this.reach = reach;
        wantedFor = Long.MIN_VALUE;
    }

    public GeodesicChunkGrid grid() {
        return grid;
    }

    // Only the cells within reach that this lets through are wanted (null for all of them)
    private java.util.function.Predicate<GeodesicChunkGrid.Cell> only;

    /** From now on wants only the cells within reach that only lets through (those in a photograph's view: see LandmarkPhotos), or all again with null. */
    public void setOnly(java.util.function.Predicate<GeodesicChunkGrid.Cell> only) {
        this.only = only;
        wantedFor = Long.MIN_VALUE;
    }

    /** The cells wanted round a place, nearest first. */
    private List<GeodesicChunkGrid.Cell> wantedAround(SpherePosition place) {
        GeodesicChunkGrid.Direction here = SphereMath.direction(place);
        List<GeodesicChunkGrid.Cell> cells = grid.cellsWithin(here, reach / Planet.radius());
        if (only != null) cells.removeIf(only.negate());
        cells.sort(Comparator.comparingDouble(cell -> SphereMath.angle(here, cell.centre())));
        return cells;
    }

    /**
     * Brings the cells round the viewer up to date: takes in those worked out since last time,
     * sets the rest in range being worked out on builders (or, with all, works them out now),
     * and drops those well out of range. Returns whether anything came or went.
     */
    // Whether the cells right round the viewer are made at once (on the caller's thread), not on the builders
    private boolean nearAtOnce = true;

    /** Whether the cells right round the viewer are made at once, on the caller's thread (as in play), or on the builders like the rest. */
    public void setNearAtOnce(boolean atOnce) {
        nearAtOnce = atOnce;
    }

    public boolean update(SpherePosition viewer, ExecutorService builders, boolean all) {
        boolean changed = false;
        GeodesicChunkGrid.Cell viewerCell = grid.cellAt(viewer);
        GeodesicChunkGrid.Direction here = SphereMath.direction(viewer);
        if (viewerCell.id() != wantedFor) {
            wantedFor = viewerCell.id();
            wantedCells = wantedAround(viewer);
            Set<Long> ids = new HashSet<>();
            for (GeodesicChunkGrid.Cell cell : wantedCells) ids.add(cell.id());
            wantedIds = ids;
            // Dropped once well out of range, so a cell at the edge isn't made again and again
            double keep = reach / Planet.radius() + 2.0 * viewerCell.angularRadius();
            Iterator<Map.Entry<Long, T>> it = ready.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, T> e = it.next();
                if (wantedIds.contains(e.getKey()) || SphereMath.angle(here, grid.cell(e.getKey()).centre()) <= keep) continue;
                onRemoved.accept(e.getValue());
                it.remove();
                changed = true;
            }
            Iterator<Map.Entry<Long, Future<T>>> pending = building.entrySet().iterator();
            while (pending.hasNext()) {
                Map.Entry<Long, Future<T>> e = pending.next();
                if (wantedIds.contains(e.getKey())) continue;
                e.getValue().cancel(false);
                pending.remove();
            }
        }
        List<GeodesicChunkGrid.Cell> cells = wantedCells;
        wantedCount = cells.size();
        double immediate = immediateReach / Planet.radius();
        for (GeodesicChunkGrid.Cell cell : cells) {
            long id = cell.id();
            if (ready.containsKey(id)) continue;
            T made = prefetched.remove(id);
            Future<T> future = building.get(id);
            if (made == null && future == null) {
                boolean near = SphereMath.angle(here, cell.centre()) <= immediate + cell.angularRadius();
                if (all || (near && nearAtOnce) || builders == null) {
                    made = builder.apply(id);
                } else {
                    building.put(id, builders.submit(() -> builder.apply(id)));
                    continue;
                }
            } else if (made == null) {
                if (!future.isDone() && !all) continue;
                building.remove(id);
                try {
                    made = future.get();
                } catch (Exception e) {
                    // (cancelled or failed: tried again next time)
                    continue;
                }
            }
            if (future != null && made != null) future.cancel(false);
            building.remove(id);
            ready.put(id, made);
            onAdded.accept(made);
            changed = true;
        }
        missing = 0;
        for (GeodesicChunkGrid.Cell cell : cells) {
            if (!ready.containsKey(cell.id())) missing++;
        }
        return changed;
    }

    /** Whether every cell in reach of where the viewer was last is ready. */
    public boolean complete() {
        return missing == 0;
    }

    /** How much of what's in reach is ready, 0 to 1. */
    public float progress() {
        return wantedCount == 0 || missing < 0 ? 0f : (wantedCount - missing) / (float) wantedCount;
    }

    public Map<Long, T> ready() {
        return ready;
    }

    /** Drops everything (a new round), keeping what has been worked out ahead for it. */
    public void clear() {
        for (T t : ready.values()) onRemoved.accept(t);
        ready.clear();
        for (Future<T> future : building.values()) future.cancel(false);
        building.clear();
        wantedCount = 0;
        missing = -1;
        wantedFor = Long.MIN_VALUE;
    }

    /** Starts working out what's round a place ahead of time (the next round's landing site). */
    public void prefetchAround(SpherePosition place) {
        clearPrefetch();
        for (GeodesicChunkGrid.Cell cell : wantedAround(place)) prefetchQueue.add(cell.id());
    }

    public void clearPrefetch() {
        prefetchQueue.clear();
        prefetched.clear();
    }

    /** Hands the prefetch thread its next few cells once it has finished the last. */
    public void pump(ExecutorService prefetcher) {
        if (prefetchQueue.isEmpty() || (prefetchJob != null && !prefetchJob.isDone())) return;
        List<Long> batch = new ArrayList<>(prefetchQueue.subList(0, Math.min(4, prefetchQueue.size())));
        prefetchQueue.subList(0, batch.size()).clear();
        prefetchJob = prefetcher.submit(() -> {
            for (long id : batch) prefetched.put(id, builder.apply(id));
        });
    }
}
