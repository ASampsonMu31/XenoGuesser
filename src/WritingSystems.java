import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The world's writing systems (RunFiles.ALPHABETS_DIR): made by GlyphGenerator, or in
 * multiplayer sent by the host to the joiner (M18), since the AI model behind them doesn't
 * give exactly the same letters on two PCs (see DETERMINISM AUDIT in the plan).
 *
 * Making them goes through one background thread, one world at a time, so the host can start
 * as soon as Host game is pressed (while the partner is still joining) and the loading screen
 * then just waits for the same job, and two jobs never write the folder at once.
 *
 * Sent as one ALPHABETS message: a zip of the folder (its files in name order, so the same
 * files always make the same zip). The joiner writes them into its own folder and never runs
 * the model. Both log a hash of the folder's files, which must match.
 */
public final class WritingSystems {

    private static final ExecutorService maker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "writing-systems");
        t.setDaemon(true);
        // (as the world's generation: the loading screen stays smooth)
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    });
    // The latest job and the world it's for
    private static long seed;
    private static CompletableFuture<Void> job;

    private WritingSystems() {
    }

    /**
     * Makes this world's writing systems in the background, unless they're already being (or
     * have been) made for it; finished when they're written. A failure is logged and the world
     * goes on with what there is, as before.
     */
    public static synchronized CompletableFuture<Void> generate(long worldSeed) {
        if (job != null && seed == worldSeed && !job.isCompletedExceptionally()) return job;
        seed = worldSeed;
        job = CompletableFuture.runAsync(() -> {
            try (GlyphGenerator generator = new GlyphGenerator()) {
                generator.generateAllSystems(RunFiles.ALPHABETS_DIR, worldSeed);
            } catch (Exception e) {
                System.err.println("CRITICAL ERROR: Failed to generate writing systems.");
                e.printStackTrace();
            }
        }, maker);
        return job;
    }

    /**
     * Host game: starts making the writing systems for this world now, and sends them to the
     * joiner as soon as both they exist and the joiner has connected (M18). Gives up quietly if
     * the room is left first.
     */
    public static void shareWhenReady(Multiplayer session, long worldSeed) {
        CompletableFuture<Void> made = generate(worldSeed);
        Thread sender = new Thread(() -> {
            made.join();
            try {
                while (!session.isReady()) {
                    if (session.isClosed()) return;
                    Thread.sleep(100);
                }
                byte[] zip = zip(RunFiles.ALPHABETS_DIR);
                System.out.println("[MP] Writing systems sent: " + zip.length + " bytes, files hash " + hash(RunFiles.ALPHABETS_DIR));
                session.sendAlphabets(zip);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                System.err.println("[MP] Couldn't send the writing systems: " + e);
            }
        }, "writing-systems-send");
        sender.setDaemon(true);
        sender.start();
    }

    /**
     * Joiner: waits for the host's writing systems and writes them into this run's folder in
     * place of its own. Throws if the partner goes before they arrive.
     */
    public static void receive(Multiplayer session) throws IOException, InterruptedException {
        byte[] zip;
        while ((zip = session.remoteAlphabets()) == null) {
            if (session.isClosed()) throw new IOException(session.remoteName() + " disconnected");
            Thread.sleep(50);
        }
        // (a job from an earlier, cancelled Host game may still be writing the folder; and once
        // the host's files are in it, it no longer holds that job's world)
        CompletableFuture<Void> earlier;
        synchronized (WritingSystems.class) {
            earlier = job;
            job = null;
        }
        if (earlier != null) earlier.join();
        unzip(zip, RunFiles.ALPHABETS_DIR);
        System.out.println("[MP] Writing systems received: " + zip.length + " bytes, files hash " + hash(RunFiles.ALPHABETS_DIR));
    }

    /** The folder's files (relative names with / between folders, in name order) as a zip. */
    static byte[] zip(String directory) throws IOException {
        Path root = Path.of(directory);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Path file : files(root)) {
                ZipEntry entry = new ZipEntry(name(root, file));
                // (no times, so the same files make the same zip)
                entry.setTime(0L);
                out.putNextEntry(entry);
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Empties the folder and fills it from a zip made by zip(); names that would leave the folder are refused. */
    static void unzip(byte[] zip, String directory) throws IOException {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        deleteContents(root);
        Files.createDirectories(root);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root) || entry.isDirectory()) continue;
                Files.createDirectories(target.getParent());
                Files.write(target, in.readAllBytes());
            }
        }
    }

    /** SHA-256 of every file's name and bytes, in name order: the same on two PCs only if the files are. */
    static String hash(String directory) throws IOException {
        Path root = Path.of(directory);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : files(root)) {
                digest.update(name(root, file).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> files(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(root)) return files;
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort((a, b) -> name(root, a).compareTo(name(root, b)));
        return files;
    }

    private static String name(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static void deleteContents(Path root) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!p.equals(root)) Files.delete(p);
            }
        }
    }
}
