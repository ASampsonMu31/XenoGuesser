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
    // The volume, as the settings' slider stands (0 to 1): half unless chosen otherwise
    private static volatile float volume = clamp01(STORE.getFloat("volume", 0.5f));
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

    // The slider's place at which the sounds play at the level they were made at (they were
    // tuned with no volume control, at what's now 70%)
    private static final float VOLUME_UNITY = 0.7f;

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0.5f : Math.max(0f, Math.min(1f, v));
    }

    /** The volume slider's place, 0 to 1. */
    public static float volume() {
        return volume;
    }

    public static void setVolume(float v) {
        volume = clamp01(v);
        try {
            STORE.putFloat("volume", volume);
        } catch (Exception e) {
            // Settings that can't be saved still apply for this game
        }
    }

    /**
     * What the samples are multiplied by at the slider's place. Loudness is heard roughly as the
     * logarithm of the sound's power, so a slider straight on the amplitude would do nearly all its
     * work in its bottom tenth; instead the amplitude goes as the slider's place squared (40 dB per
     * tenfold, as games commonly do), which feels even along it: unity at 70%, about -6 dB at 50%,
     * about +6 dB at the top, and silent at nought.
     */
    public static float volumeGain() {
        float r = volume / VOLUME_UNITY;
        return r * r;
    }

    /**
     * Writes 16-bit signed little-endian PCM to a line at the volume chosen (a copy scaled, the
     * original left as it was), peaks above full scale rounded off rather than clipped harshly.
     */
    public static void writeAtVolume(javax.sound.sampled.SourceDataLine line, byte[] bytes, int from, int length) {
        float gain = volumeGain();
        if (Math.abs(gain - 1f) < 1e-3f) {
            line.write(bytes, from, length);
            return;
        }
        byte[] scaled = new byte[length];
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((bytes[from + i] & 0xFF) | (bytes[from + i + 1] << 8));
            float v = sample * gain / 32768f;
            // (past 0.8 of full scale, eased into the top rather than cut off at it)
            if (v > 0.8f) v = 0.8f + 0.2f * (float) Math.tanh((v - 0.8f) / 0.2f);
            else if (v < -0.8f) v = -0.8f - 0.2f * (float) Math.tanh((-v - 0.8f) / 0.2f);
            int out = Math.round(v * 32767f);
            scaled[i] = (byte) out;
            scaled[i + 1] = (byte) (out >> 8);
        }
        line.write(scaled, 0, length);
    }
}
