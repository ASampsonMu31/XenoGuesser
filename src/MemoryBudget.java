import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

/**
 * How much memory the game lets its heap grow to, worked out from the PC's own: its RAM, less a
 * share for Windows, the graphics driver and whatever else is open, less what the game's native
 * parts (the voice and glyph models, the GL) take outside the heap; and no more than is free
 * when it's worked out, so the game doesn't push the PC into its page file (where every frame
 * waits on the disk, and on a PC with little RAM it can run out altogether and crash).
 *
 * The launcher's -XX:MaxRAMPercentage (of no more than -XX:MaxRAM: see pom.xml for why) is only
 * the ceiling the heap may never pass, and the only thing that stops the heap spreading out while
 * a world's made, however fast that churns through memory; this is the
 * size the collector keeps it under (ZGC's SoftMaxHeapSize, changeable while running), working
 * harder rather than taking more. Worked out at startup and again as each world's made, when
 * the player may have closed other things since.
 *
 * Once the world's made the budget follows the game rather than the PC: what's still in use
 * after collecting, with room to spare, worked out afresh every few seconds (see watch). The
 * collector would otherwise let garbage pile up to the whole budget, and the PC have nothing
 * left when something else wants memory mid-game (Discord starting a screenshare, say, whose
 * video encoder shares the RAM with a laptop's graphics). Then Windows pages the heap out, and
 * each collection reads it all back from the disk: frames come seconds apart, then not at all.
 * So when the PC runs short the heap's held to little more than it holds, and what it frees
 * goes back to Windows (after the launcher's -XX:ZUncommitDelay).
 *
 * While a world's made the budget's the PC's, as above, but it's watched all the same: a
 * screenshare begun in the landing pod takes its memory from a PC already giving the most it can
 * to the making (on a PC with 16 GB the making alone can leave under 1 GB free), so then the
 * heap's held to what's in use plus LOADING_ROOM, or half again, whichever's more (the making goes
 * slower, collecting more often, rather than the whole PC waiting on its page file).
 */
public final class MemoryBudget {

    private static final long MB = 1024L * 1024L, GB = 1024L * MB;
    // Taken outside the heap by the game's own native parts, whatever the PC
    private static final long NATIVE = 1200 * MB;
    // Left to Windows and the rest of the PC: a fifth of its RAM, and never under this
    private static final long LEAST_FOR_SYSTEM = 2 * GB;
    // Kept spare of what's free, so other programs aren't pushed out
    private static final long SPARE = 512 * MB;
    // The least the heap's held to: making a world keeps 1.5 to 3 GB in use (more for a moment),
    // and held under that it would spend longer collecting than making
    private static final long LEAST = 3 * GB;
    // While playing, room above what's in use: with less the collector would hardly stop
    private static final long PLAYING_ROOM = 1536 * MB, SHORT_ROOM = 512 * MB;
    // While a world's made and memory's short, room above what's in use: the making takes more for a moment
    private static final long LOADING_ROOM = GB;
    // How often the budget's worked out afresh while playing
    private static final long WATCH_MILLIS = 2000L;
    // The budget's only changed by more than this (each change costs the collector a little)
    private static final long STEP = 256 * MB;
    // A collection's asked for when memory first runs short, but not more often than this
    private static final long SQUEEZE_GAP_MILLIS = 30_000L;

    private static volatile long budget = -1L, total = -1L, free = -1L;
    private static volatile boolean applied;
    // A world's being played (the budget follows the game) rather than made (the budget's the PC's)
    private static volatile boolean playing;
    // Memory's short: the heap's held tight until there's twice the shortfall's mark free again
    private static volatile boolean squeezed;
    private static long lastSqueeze;
    private static Thread watcher;

    private MemoryBudget() {
    }

    /** Works the budget out from the memory there is now, and holds the heap to it. */
    public static synchronized void apply() {
        // (a world's about to be made: it has all the PC can spare until it's done)
        playing = false;
        squeezed = false;
        long max = Runtime.getRuntime().maxMemory();
        readMemory();
        if (total <= 0L) {
            budget = max;
            return;
        }
        long fromTotal = total - Math.max(LEAST_FOR_SYSTEM, total / 5) - NATIVE;
        // (what the heap has already taken is the game's own to keep)
        long fromFree = Math.max(0L, free) + Runtime.getRuntime().totalMemory() - SPARE;
        setBudget(Math.min(max, Math.max(Math.min(LEAST, max), Math.min(fromTotal, fromFree))));
        System.out.println("[MEMORY] " + summary());
        startWatching();
    }

    /**
     * The world's made and being played: from now until the next world's begun the budget's
     * what the game holds plus room, and less when the PC runs short (see adjust).
     */
    public static synchronized void worldMade() {
        playing = true;
        startWatching();
        adjust();
    }

    /** The budget worked out afresh every few seconds from now on, while a world's made and while it's played. */
    private static synchronized void startWatching() {
        if (watcher != null) return;
        watcher = new Thread(MemoryBudget::watch, "memory-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(WATCH_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                adjust();
            } catch (Throwable e) {
                // (a budget that can't be worked out leaves the last one standing)
                CrashLog.write("Memory watch failed", e);
            }
        }
    }

    /**
     * The budget worked out from what the game holds and what the PC has free now: while playing,
     * what's in use plus room (less when memory's short); while a world's made, all the PC can
     * spare, or what's in use plus LOADING_ROOM (or half again) when memory's short.
     */
    private static void adjust() {
        boolean squeezeNow;
        synchronized (MemoryBudget.class) {
            readMemory();
            if (total <= 0L || !applied) return;
            long max = Runtime.getRuntime().maxMemory();
            long live = live();
            // Short of memory: under a sixteenth of the RAM free (never under 1 GB)
            long shortMark = Math.max(GB, total / 16);
            boolean wasSqueezed = squeezed;
            squeezed = squeezed ? free < 2 * shortMark : free < shortMark;
            long fromTotal = total - Math.max(LEAST_FOR_SYSTEM, total / 5) - NATIVE;
            long fromFree = Math.max(0L, free) + Runtime.getRuntime().totalMemory() - SPARE;
            long target;
            if (playing) {
                long room = squeezed ? Math.max(SHORT_ROOM, live / 4) : Math.max(PLAYING_ROOM, live / 2);
                // (never held under what's in use, or the collector would run without end)
                target = Math.min(max, Math.max(live + SHORT_ROOM, Math.min(live + room, Math.min(fromTotal, fromFree))));
            } else if (squeezed) {
                // (not from what's free: that counts the heap's own, which is what's to be given back)
                target = Math.min(max, Math.min(fromTotal, live + Math.max(LOADING_ROOM, live / 2)));
            } else {
                target = Math.min(max, Math.max(Math.min(LEAST, max), Math.min(fromTotal, fromFree)));
            }
            if (squeezed == wasSqueezed && Math.abs(target - budget) < STEP) return;
            setBudget(target);
            long now = System.currentTimeMillis();
            squeezeNow = squeezed && !wasSqueezed && now - lastSqueeze > SQUEEZE_GAP_MILLIS;
            if (squeezeNow) lastSqueeze = now;
            System.out.println("[MEMORY] " + (squeezed ? "short: " : "") + "holding " + gb(live) + " in use, " + summary());
            if (squeezed && !wasSqueezed) CrashLog.note("Memory short", summary() + ", " + gb(live) + " in use after collecting");
        }
        // What the collector frees now goes back to Windows (outside the lock: it waits on a whole collection)
        if (squeezeNow) System.gc();
    }

    /**
     * What the heap held after its last collections: the young and old generations each as
     * their own collection left them (a little under, between the old's collections, which the
     * next collection puts right), and no more than what's in use now, which it is when the
     * collector won't say.
     */
    private static long live() {
        long sum = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            MemoryUsage after = pool.getCollectionUsage();
            if (after != null) sum += after.getUsed();
        }
        Runtime runtime = Runtime.getRuntime();
        long now = runtime.totalMemory() - runtime.freeMemory();
        // (never more than's in use now: a young collection that found little to free reports
        // its whole generation, and a later collection may since have freed it)
        return sum > 0L ? Math.min(sum, now) : now;
    }

    /** The PC's memory, all and free, as it is now (unknown, -1, when it can't be read). */
    private static void readMemory() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            total = os.getTotalMemorySize();
            free = os.getFreeMemorySize();
        } catch (Throwable e) {
            // (no way of reading the PC's memory: the ceiling stands alone)
            total = -1L;
            free = -1L;
        }
    }

    /** Holds the heap to this many bytes. */
    private static void setBudget(long bytes) {
        budget = bytes;
        try {
            com.sun.management.HotSpotDiagnosticMXBean vm =
                    ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            vm.setVMOption("SoftMaxHeapSize", Long.toString(bytes));
            applied = true;
        } catch (Throwable e) {
            // (a collector without a soft limit: the ceiling stands alone)
            applied = false;
        }
    }

    /**
     * The PC's memory as it's shared out now, for the diagnostics (bytes; -1 where unknown). The
     * game's own is what it's committed (its heap and its native parts alike), of which the heap's
     * share is heapCommitted and what the heap holds heapUsed. Windows reports the page file only
     * as part of the commit limit (RAM and page file together, what all programs may commit
     * between them): commit past the RAM's size can only be kept in the page file.
     */
    public record Reading(long total, long free, long game, long heapCommitted, long heapUsed,
                          long commitLimit, long committed, boolean squeezed) {
        /** The page file's size. */
        public long pageFile() {
            return commitLimit > total && total > 0L ? commitLimit - total : 0L;
        }

        /** What's committed past the RAM's size, which only the page file can hold. */
        public long pastRam() {
            return total > 0L ? Math.max(0L, committed - total) : 0L;
        }
    }

    /** The memory now (see Reading). */
    public static Reading reading() {
        Runtime runtime = Runtime.getRuntime();
        long heapCommitted = runtime.totalMemory(), heapUsed = heapCommitted - runtime.freeMemory();
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            // (on Windows the "swap" figures are the commit limit and what's left of it; and ZGC's
            // heap is a section of the page file mapped in, which the process's own commit leaves
            // out, so the game's is the two together)
            long limit = os.getTotalSwapSpaceSize();
            return new Reading(os.getTotalMemorySize(), os.getFreeMemorySize(),
                    heapCommitted + os.getCommittedVirtualMemorySize(), heapCommitted, heapUsed,
                    limit, limit - os.getFreeSwapSpaceSize(), squeezed);
        } catch (Throwable e) {
            return new Reading(-1L, -1L, heapCommitted, heapCommitted, heapUsed, -1L, -1L, squeezed);
        }
    }

    /** The budget (bytes): the heap's held under this. */
    public static long budget() {
        return budget > 0L ? budget : Runtime.getRuntime().maxMemory();
    }

    /** The PC's memory, the budget and the heap now, in a line (for the log and the crash log). */
    public static String summary() {
        Runtime runtime = Runtime.getRuntime();
        long freeNow = free;
        try {
            freeNow = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize();
        } catch (Throwable e) {
            // (the last reading will do)
        }
        return String.format(java.util.Locale.ROOT,
                "RAM %s (%s free), heap budget %s%s%s (ceiling %s), heap now %s used of %s, %d cores",
                gb(total), gb(freeNow), gb(budget()), applied ? "" : " (not enforced)", squeezed ? " (memory short)" : "",
                gb(runtime.maxMemory()), gb(runtime.totalMemory() - runtime.freeMemory()), gb(runtime.totalMemory()),
                runtime.availableProcessors());
    }

    private static String gb(long bytes) {
        return bytes < 0L ? "unknown" : String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) GB);
    }
}
