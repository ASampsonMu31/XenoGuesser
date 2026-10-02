import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * Tracks startup from launch to the first rendered frame as a sequence of weighted stages.
 *
 * Each stage is weighted by how long it is expected to take. Within a stage, progress comes
 * from explicit reports where the work can count itself (textures, flora species) and
 * otherwise from elapsed time against the expected duration, eased so it slows down rather
 * than overshooting. Measured durations are saved after each launch and blended into the
 * next launch's expectations, so the bar's estimate gets more accurate on each machine.
 *
 * Written by the loading threads, read by the loading screen's repaint timer.
 */
public final class LoadingProgress {

    public enum Stage {
        WRITING_SYSTEMS("Inventing writing systems", 2500),
        SEA_LEVEL("Measuring the oceans", 300),
        WORLD_LAYOUT("Shaping climate, nations and roads", 6000),
        WORLD_ART("Painting the world's textures", 2500),
        WINDOW("Opening the window", 800),
        GPU_UPLOAD("Uploading textures and shaders", 800),
        FLORA("Growing alien flora", 2000),
        TERRAIN("Raising the terrain", 5000),
        FIRST_FRAME("Letting the light in", 500);

        final String label;
        final long defaultMillis;

        Stage(String label, long defaultMillis) {
            this.label = label;
            this.defaultMillis = defaultMillis;
        }
    }

    private static final File TIMINGS_FILE = new File(".loading_times.properties");

    private final Map<Stage, Long> expectedMillis = new EnumMap<>(Stage.class);
    private final Map<Stage, Long> measuredMillis = new EnumMap<>(Stage.class);
    private final long totalExpectedMillis;
    private final long launchNanos = System.nanoTime();

    private Stage current;
    private long stageStartNanos;
    private float reportedFraction;
    private long completedExpectedMillis;
    private float displayedFraction;
    private boolean finished;

    public LoadingProgress() {
        Properties saved = new Properties();
        if (TIMINGS_FILE.isFile()) {
            try (FileReader reader = new FileReader(TIMINGS_FILE)) {
                saved.load(reader);
            } catch (Exception ignored) {
                // A missing or corrupt timings file only means less accurate estimates
            }
        }
        long total = 0;
        for (Stage s : Stage.values()) {
            long ms = s.defaultMillis;
            try {
                ms = Long.parseLong(saved.getProperty(s.name(), Long.toString(ms)));
            } catch (NumberFormatException ignored) {}
            ms = Math.max(50, ms);
            expectedMillis.put(s, ms);
            total += ms;
        }
        totalExpectedMillis = total;
    }

    public synchronized void begin(Stage stage) {
        closeCurrentStage();
        current = stage;
        stageStartNanos = System.nanoTime();
        reportedFraction = 0f;
        System.out.printf("[LOADING] %s...%n", stage.label);
    }

    /** Fraction of the current stage done, for stages that can count their own work. */
    public synchronized void report(float fractionOfStage) {
        reportedFraction = Math.max(reportedFraction, Math.min(1f, fractionOfStage));
    }

    public synchronized void finish() {
        if (finished) return;
        closeCurrentStage();
        finished = true;
        displayedFraction = 1f;
        System.out.printf("[LOADING] World ready in %d ms%n", (System.nanoTime() - launchNanos) / 1_000_000);
        saveTimings();
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    private String failure;

    /** Shown in place of the stage name if loading can't continue. */
    public synchronized void fail(String message) {
        failure = message;
    }

    public synchronized String currentLabel() {
        if (failure != null) return "Loading failed: " + failure;
        return finished ? "Ready" : (current == null ? "Starting up" : current.label);
    }

    /** Overall estimated progress in [0, 1]; never moves backwards. */
    public synchronized float overallFraction() {
        if (finished) return 1f;
        float within = 0f;
        long weight = 0;
        if (current != null) {
            weight = expectedMillis.get(current);
            double elapsed = (System.nanoTime() - stageStartNanos) / 1e6;
            // Time-based estimate eases toward (but never reaches) the end of the stage
            float byTime = (float) (1.0 - Math.exp(-1.6 * elapsed / weight));
            within = Math.min(0.98f, Math.max(reportedFraction, byTime));
        }
        float overall = (completedExpectedMillis + within * weight) / (float) totalExpectedMillis;
        displayedFraction = Math.max(displayedFraction, Math.min(0.995f, overall));
        return displayedFraction;
    }

    /** Rough seconds remaining, from the remaining share of the expected total. */
    public synchronized float estimatedSecondsLeft() {
        return finished ? 0f : (1f - overallFraction()) * totalExpectedMillis / 1000f;
    }

    private void closeCurrentStage() {
        if (current == null) return;
        long ms = (System.nanoTime() - stageStartNanos) / 1_000_000;
        measuredMillis.put(current, ms);
        completedExpectedMillis += expectedMillis.get(current);
        System.out.printf("[LOADING] %s took %d ms%n", current.label, ms);
        current = null;
    }

    private void saveTimings() {
        Properties out = new Properties();
        for (Stage s : Stage.values()) {
            long expected = expectedMillis.get(s);
            Long measured = measuredMillis.get(s);
            long blended = measured == null ? expected : (expected + measured) / 2;
            out.setProperty(s.name(), Long.toString(blended));
        }
        try (FileWriter writer = new FileWriter(TIMINGS_FILE)) {
            out.store(writer, "XenoGuesser loading-stage durations (ms), learned to improve the loading bar estimate");
        } catch (Exception e) {
            System.err.println("Could not save loading timings: " + e.getMessage());
        }
    }
}
