import java.util.prefs.Preferences;

/** The player's settings, remembered between games. */
public final class GameSettings {

    private static final Preferences STORE = Preferences.userRoot().node("xenoguesser");
    private static volatile boolean showFps = STORE.getBoolean("showFps", true);
    // Fullscreen, or in a window: always fullscreen at startup (a window is for this game only)
    private static volatile boolean fullscreen = true;

    private GameSettings() {
    }

    public static boolean fullscreen() {
        return fullscreen;
    }

    public static void setFullscreen(boolean on) {
        fullscreen = on;
    }

    public static boolean showFps() {
        return showFps;
    }

    public static void setShowFps(boolean show) {
        showFps = show;
        try {
            STORE.putBoolean("showFps", show);
        } catch (Exception e) {
            // Settings that can't be saved still apply for this game
        }
    }
}
