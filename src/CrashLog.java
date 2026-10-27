import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;

/**
 * What went wrong, written where the player can find it to send on: crash-log.txt in
 * %LOCALAPPDATA%\XenoGuesser (or the home folder's XenoGuesser elsewhere). The packaged game
 * writes nothing to a terminal (see XenoGuesser.main), so without this a failure on someone
 * else's PC leaves no trace. Each run's first entry starts with the PC's memory, cores and
 * graphics settings; the file's started afresh once it's grown past MAX_BYTES. Freezes (see
 * FreezeWatch) and crashes in native code (see collectNativeCrashes), which throw nothing, are
 * written here too.
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
    public static void write(String what, Throwable e) {
        entry(what + ":" + e, what, out -> e.printStackTrace(out));
    }

    /** Writes something that's no failure in itself but may explain one (a freeze, memory running short). */
    public static void note(String what, String detail) {
        entry(what, what, out -> out.println(detail));
    }

    private static synchronized void entry(String key, String what, java.util.function.Consumer<PrintWriter> body) {
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
                body.accept(out);
            }
        } catch (Throwable ignored) {
            // (a log that can't be written mustn't make matters worse)
        }
    }

    // The lines of a fatal error file copied into the log: what failed, in which library, and the
    // start of the failing thread's stack (the whole file is kept alongside)
    private static final int NATIVE_LINES = 60;

    /**
     * A crash in native code (the graphics driver, or something hooked into the game such as a
     * screen recorder) takes the whole program down before anything here can write, and the JVM
     * leaves its own record instead: hs_err_pid*.log, in the folder the game was started from
     * (or the temporary folder when that can't be written to). Any of the game's left from an
     * earlier run is moved beside the crash log and its opening lines copied into it.
     */
    public static void collectNativeCrashes() {
        java.util.Set<java.nio.file.Path> folders = new java.util.LinkedHashSet<>();
        folders.add(java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath());
        folders.add(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());
        for (java.nio.file.Path folder : folders) {
            try (java.nio.file.DirectoryStream<java.nio.file.Path> found =
                         java.nio.file.Files.newDirectoryStream(folder, "hs_err_pid*.log")) {
                for (java.nio.file.Path file : found) collectNativeCrash(file);
            } catch (Throwable ignored) {
                // (a folder that can't be read has nothing of ours to find)
            }
        }
    }

    private static void collectNativeCrash(java.nio.file.Path file) throws java.io.IOException {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.ISO_8859_1);
        // (the temporary folder's shared: only the game's own are taken)
        if (lines.stream().noneMatch(line -> line.contains("XenoGuesser"))) return;
        File dir = FILE.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        java.nio.file.Path kept = dir.toPath().resolve(file.getFileName());
        java.nio.file.Files.move(file, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String when = LocalDateTime.ofInstant(java.nio.file.Files.getLastModifiedTime(kept).toInstant(),
                java.time.ZoneId.systemDefault()).toString();
        String opening = String.join(System.lineSeparator(), lines.subList(0, Math.min(NATIVE_LINES, lines.size())));
        note("An earlier run crashed in native code at " + when + " (the whole report: " + kept + ")", opening);
    }

    // The graphics card and driver, once the GL's started (for the log's header)
    private static volatile String GL_RENDERER;

    /** Notes which graphics card and driver the game's drawing with. */
    public static void setRenderer(String renderer) {
        GL_RENDERER = renderer;
    }
}
