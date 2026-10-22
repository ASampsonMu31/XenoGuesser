import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;

/**
 * What went wrong, written where the player can find it to send on: crash-log.txt in
 * %LOCALAPPDATA%\XenoGuesser (or the home folder's XenoGuesser elsewhere). The packaged game
 * writes nothing to a terminal (see XenoGuesser.main), so without this a failure on someone
 * else's PC leaves no trace. Each run's first entry starts with the PC's memory, cores and
 * graphics settings; the file's started afresh once it's grown past MAX_BYTES.
 */
public final class CrashLog {

    private static final long MAX_BYTES = 512 * 1024;
    private static final File FILE = new File(folder(), "crash-log.txt");
    // The same failure over and over (each frame, say) is written only this many times
    private static final int MOST_REPEATS = 3;
    private static final java.util.Map<String, Integer> seen = new java.util.HashMap<>();
    private static boolean headerWritten;

    private CrashLog() {
    }

    private static File folder() {
        String local = System.getenv("LOCALAPPDATA");
        File base = local != null ? new File(local) : new File(System.getProperty("user.home"));
        return new File(base, "XenoGuesser");
    }

    /** Anything thrown and not caught, on any thread, is written down. */
    public static void install() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            write("Uncaught on thread " + thread.getName(), e);
            if (previous != null) previous.uncaughtException(thread, e);
            else e.printStackTrace();
        });
    }

    /** Writes what happened, and its stack, to the log (the game going on regardless). */
    public static synchronized void write(String what, Throwable e) {
        String key = what + ":" + e;
        int times = seen.merge(key, 1, Integer::sum);
        if (times > MOST_REPEATS) return;
        try {
            File dir = FILE.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            boolean append = FILE.length() < MAX_BYTES;
            try (PrintWriter out = new PrintWriter(new FileWriter(FILE, append))) {
                if (!headerWritten) {
                    headerWritten = true;
                    out.println();
                    out.println("=== XenoGuesser run at " + LocalDateTime.now() + " ===");
                    out.println("Java " + System.getProperty("java.version") + ", " + System.getProperty("os.name") + " "
                            + System.getProperty("os.version") + ", " + MemoryBudget.summary());
                    out.println("Graphics " + GameSettings.quality().label + ", shadows " + (GameSettings.shadows() ? "on" : "off")
                            + (GL_RENDERER != null ? ", " + GL_RENDERER : ""));
                }
                out.println("--- " + LocalDateTime.now() + ": " + what + (times == MOST_REPEATS ? " (again: no more of these written)" : ""));
                out.println(MemoryBudget.summary());
                e.printStackTrace(out);
            }
        } catch (Throwable ignored) {
            // (a log that can't be written mustn't make matters worse)
        }
    }

    // The graphics card and driver, once the GL's started (for the log's header)
    private static volatile String GL_RENDERER;

    /** Notes which graphics card and driver the game's drawing with. */
    public static void setRenderer(String renderer) {
        GL_RENDERER = renderer;
    }
}
