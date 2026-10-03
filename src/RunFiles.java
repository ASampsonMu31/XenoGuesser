import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Where a run's generated files (textures, writing systems, debug captures) live: a folder
 * in the system's temporary directory, never in the project's assets. It is deleted when the
 * game exits, and any folder left behind by a run that crashed or was killed is removed the
 * next time the game starts.
 */
public final class RunFiles {

    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "xenoguesser");
    private static final String PREFIX = "run-";
    private static final Path RUN = ROOT.resolve(PREFIX + ProcessHandle.current().pid());

    /** This run's world textures. */
    public static final String WORLD_DIR = RUN.resolve("generated_world").toString();
    /** This run's writing systems. */
    public static final String ALPHABETS_DIR = RUN.resolve("generated_alphabets").toString();

    private static boolean prepared;

    private RunFiles() {}

    /** Clears leftovers from earlier runs and arranges for this run's files to go on exit. */
    public static synchronized void prepare() {
        if (prepared) return;
        prepared = true;
        removeStaleRuns();
        // Earlier versions wrote generated textures into the project's assets
        deleteQuietly(Path.of("assets", "textures", "generated_world"));
        deleteQuietly(Path.of("assets", "textures", "generated_alphabets"));
        File textures = new File("assets/textures");
        String[] left = textures.list();
        if (left != null && left.length == 0) textures.delete();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(RUN), "run-files-cleanup"));
        new File(WORLD_DIR).mkdirs();
        new File(ALPHABETS_DIR).mkdirs();
    }

    /** Deletes the folders of runs whose process is no longer alive. */
    private static void removeStaleRuns() {
        File[] runs = ROOT.toFile().listFiles();
        if (runs == null) return;
        for (File run : runs) {
            String name = run.getName();
            if (!name.startsWith(PREFIX)) continue;
            try {
                long pid = Long.parseLong(name.substring(PREFIX.length()));
                if (pid != ProcessHandle.current().pid() && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    continue;
                }
            } catch (NumberFormatException ignored) {
                // Not one of ours to judge; leave it
                continue;
            }
            if (!run.toPath().equals(RUN)) deleteQuietly(run.toPath());
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A file still open elsewhere; the next start will try again
                }
            });
        } catch (IOException ignored) {
            // As above
        }
    }
}
