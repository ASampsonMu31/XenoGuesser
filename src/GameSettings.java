import java.util.prefs.Preferences;

/** The player's settings, remembered between games. */
public final class GameSettings {

    /**
     * How much the PC's asked to draw: Low (the default, for any PC), Medium or High. Each is the
     * most pixels the world's drawn at (scaled up to fill the screen), how far out the plants,
     * roads and buildings are made (chunks), how far the grass is and how thick (of its full
     * abundance), the sun's shadow maps' size (texels), and whether shadows come on with it.
     */
    public enum Quality {
        LOW("Low", 1280.0 * 720.0, 14, 11, 0.6f, 1024, false),
        MEDIUM("Medium", 1600.0 * 900.0, 18, 16, 0.8f, 1536, false),
        HIGH("High", 1920.0 * 1080.0, 22, 21, 1f, 2048, true);

        public final String label;
        public final double renderPixels;
        public final int viewDistance, grassViewDistance, shadowMapSize;
        public final float grassDensity;
        public final boolean shadows;

        Quality(String label, double renderPixels, int viewDistance, int grassViewDistance, float grassDensity,
                int shadowMapSize, boolean shadows) {
            this.label = label;
            this.renderPixels = renderPixels;
            this.viewDistance = viewDistance;
            this.grassViewDistance = grassViewDistance;
            this.grassDensity = grassDensity;
            this.shadowMapSize = shadowMapSize;
            this.shadows = shadows;
        }
    }

    // Where the settings and achievements are saved (per Windows user): the packaged game keeps its
    // own (-Dxenoguesser.store=release, see build-exe.bat), so it starts at the defaults, with no
    // achievements, however the game's been played from the project
    static final Preferences STORE = Preferences.userRoot().node(System.getProperty("xenoguesser.store", "xenoguesser"));
    // The frame rate and the memory in the top right, in the landing pod and the game: on unless
    // turned off (under a key of its own, not the old frame-rate-only "showFps", so it's on for
    // everyone at first, whatever they'd chosen for that)
    private static volatile boolean showDiagnostics = STORE.getBoolean("showDiagnostics", true);
    // The graphics, Low unless chosen otherwise
    // (developer aid: -Dxenoguesser.quality=low, medium or high overrides it for this run)
    private static volatile Quality quality = parseQuality(Dev.property("xenoguesser.quality") != null
            ? Dev.property("xenoguesser.quality") : STORE.get("quality", Quality.LOW.name()));
    // The sun's shadows (see ShadowMaps): off unless turned on (or High chosen)
    // (developer aid: -Dxenoguesser.shadows=on or off overrides it for this run)
    private static volatile boolean shadows = Dev.property("xenoguesser.shadows") != null
            ? !"off".equals(Dev.property("xenoguesser.shadows")) : STORE.getBoolean("shadowsOn", false);
    // Fullscreen, or in a window: always fullscreen at startup (a window is for this game only)
    private static volatile boolean fullscreen = true;

    private GameSettings() {
    }

    private static Quality parseQuality(String name) {
        try {
            return Quality.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return Quality.LOW;
        }
    }

    public static Quality quality() {
        return quality;
    }

    /** Chooses the graphics; the shadows go on or off with them (they can be changed after). */
    public static void setQuality(Quality chosen) {
        quality = chosen;
        try {
            STORE.put("quality", chosen.name());
        } catch (Exception e) {
            // Settings that can't be saved still apply for this game
        }
        setShadows(chosen.shadows);
    }

    public static boolean fullscreen() {
        return fullscreen;
    }

    public static void setFullscreen(boolean on) {
        fullscreen = on;
    }

    public static boolean shadows() {
        return shadows;
    }

    public static void setShadows(boolean on) {
        shadows = on;
        try {
            STORE.putBoolean("shadowsOn", on);
        } catch (Exception e) {
            // Settings that can't be saved still apply for this game
        }
    }

    public static boolean showDiagnostics() {
        return showDiagnostics;
    }

    /** Developer aid (filming the trailer): the diagnostics hidden for this run only, the setting kept as it was. */
    static void devHideDiagnostics() {
        showDiagnostics = false;
    }

    public static void setShowDiagnostics(boolean show) {
        showDiagnostics = show;
        try {
            STORE.putBoolean("showDiagnostics", show);
        } catch (Exception e) {
            // Settings that can't be saved still apply for this game
        }
    }
}
