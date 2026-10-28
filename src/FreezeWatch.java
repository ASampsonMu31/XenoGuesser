import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/**
 * Notices the game freezing and writes down why: a freeze throws nothing, so without this it
 * leaves no trace in the crash log. Each frame drawn is counted (see frame), and the UI thread
 * is sent a small job every check; when either has gone FREEZE_MILLIS without a sign of life,
 * every thread's stack and the memory's state go in the crash log, once for that freeze, and a
 * line when it comes back to life (a stall that passes rather than a hang).
 */
public final class FreezeWatch {

    // Long enough that loading a texture or a slow frame isn't taken for a freeze
    private static final long FREEZE_MILLIS = 15_000L;
    private static final long CHECK_MILLIS = 1000L;
    // The frames shown in each thread's stack (the deepest, where it's stuck, are what matter)
    private static final int STACK_DEPTH = 40;

    private static volatile long lastFrame, lastUi;
    private static volatile boolean uiPinged;
    private static Thread watcher;

    private FreezeWatch() {
    }

    /** A frame's been drawn (called from the GL thread, every frame). */
    public static void frame() {
        lastFrame = System.currentTimeMillis();
    }

    /** Begins watching, while drawing says frames are expected (not while minimised, say). */
    public static synchronized void start(BooleanSupplier drawing) {
        if (watcher != null) return;
        long now = System.currentTimeMillis();
        lastFrame = now;
        lastUi = now;
        watcher = new Thread(() -> watch(drawing), "freeze-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void watch(BooleanSupplier drawing) {
        boolean framesFrozen = false, uiFrozen = false;
        long framesFrozeAt = 0L, uiFrozeAt = 0L;
        while (true) {
            try {
                Thread.sleep(CHECK_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!uiPinged) {
                uiPinged = true;
                SwingUtilities.invokeLater(() -> {
                    lastUi = System.currentTimeMillis();
                    uiPinged = false;
                });
            }
            // (a minimised window draws nothing, rightly: the wait's counted from when it's back)
            if (!framesFrozen && !drawing.getAsBoolean()) lastFrame = now;
            long sinceFrame = now - lastFrame, sinceUi = now - lastUi;
            if (!framesFrozen && sinceFrame > FREEZE_MILLIS) {
                framesFrozen = true;
                framesFrozeAt = lastFrame;
                CrashLog.note("Frozen: no frame drawn for " + sinceFrame / 1000 + " s", threads());
            } else if (framesFrozen && sinceFrame < FREEZE_MILLIS) {
                framesFrozen = false;
                CrashLog.note("Frames drawn again after " + (lastFrame - framesFrozeAt) / 1000 + " s", "");
            }
            if (!uiFrozen && sinceUi > FREEZE_MILLIS) {
                uiFrozen = true;
                uiFrozeAt = lastUi;
                CrashLog.note("Frozen: the UI thread busy for " + sinceUi / 1000 + " s", threads());
            } else if (uiFrozen && sinceUi < FREEZE_MILLIS) {
                uiFrozen = false;
                CrashLog.note("The UI thread free again after " + (lastUi - uiFrozeAt) / 1000 + " s", "");
            }
        }
    }

    /** Every thread's state and stack, the GL and UI threads' first. */
    private static String threads() {
        StringBuilder text = new StringBuilder();
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        stacks.entrySet().stream()
                .sorted((a, b) -> Integer.compare(rank(a.getKey()), rank(b.getKey())))
                .forEach(entry -> {
                    Thread thread = entry.getKey();
                    text.append('"').append(thread.getName()).append("\" ").append(thread.getState()).append(System.lineSeparator());
                    StackTraceElement[] stack = entry.getValue();
                    for (int k = 0; k < Math.min(STACK_DEPTH, stack.length); k++) {
                        text.append("\tat ").append(stack[k]).append(System.lineSeparator());
                    }
                    if (stack.length > STACK_DEPTH) text.append("\t...").append(System.lineSeparator());
                });
        return text.toString();
    }

    private static int rank(Thread thread) {
        String name = thread.getName();
        if (name.contains("Animator")) return 0;
        if (name.startsWith("AWT-EventQueue")) return 1;
        return 2;
    }
}
