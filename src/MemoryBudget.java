import java.lang.management.ManagementFactory;

/**
 * How much memory the game lets its heap grow to, worked out from the PC's own: its RAM, less a
 * share for Windows, the graphics driver and whatever else is open, less what the game's native
 * parts (the voice and glyph models, the GL) take outside the heap; and no more than is free
 * when it's worked out, so the game doesn't push the PC into its page file (where every frame
 * waits on the disk, and on a PC with little RAM it can run out altogether and crash).
 *
 * The launcher's -XX:MaxRAMPercentage is only the ceiling the heap may never pass; this is the
 * size the collector keeps it under (ZGC's SoftMaxHeapSize, changeable while running), working
 * harder rather than taking more. Worked out at startup and again as each world's made, when
 * the player may have closed other things since.
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

    private static volatile long budget = -1L, total = -1L, free = -1L;
    private static volatile boolean applied;

    private MemoryBudget() {
    }

    /** Works the budget out from the memory there is now, and holds the heap to it. */
    public static synchronized void apply() {
        long max = Runtime.getRuntime().maxMemory();
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
        if (total <= 0L) {
            budget = max;
            return;
        }
        long fromTotal = total - Math.max(LEAST_FOR_SYSTEM, total / 5) - NATIVE;
        // (what the heap has already taken is the game's own to keep)
        long fromFree = Math.max(0L, free) + Runtime.getRuntime().totalMemory() - SPARE;
        budget = Math.min(max, Math.max(Math.min(LEAST, max), Math.min(fromTotal, fromFree)));
        try {
            com.sun.management.HotSpotDiagnosticMXBean vm =
                    ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            vm.setVMOption("SoftMaxHeapSize", Long.toString(budget));
            applied = true;
        } catch (Throwable e) {
            // (a collector without a soft limit: the ceiling stands alone)
            applied = false;
        }
        System.out.println("[MEMORY] " + summary());
    }

    /** The budget (bytes): the heap's held under this. */
    public static long budget() {
        return budget > 0L ? budget : Runtime.getRuntime().maxMemory();
    }

    /** The PC's memory, the budget and the heap now, in a line (for the log and the crash log). */
    public static String summary() {
        Runtime runtime = Runtime.getRuntime();
        return String.format(java.util.Locale.ROOT,
                "RAM %s (%s free), heap budget %s%s (ceiling %s), heap now %s used of %s, %d cores",
                gb(total), gb(free), gb(budget()), applied ? "" : " (not enforced)", gb(runtime.maxMemory()),
                gb(runtime.totalMemory() - runtime.freeMemory()), gb(runtime.totalMemory()), runtime.availableProcessors());
    }

    private static String gb(long bytes) {
        return bytes < 0L ? "unknown" : String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) GB);
    }
}
