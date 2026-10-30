import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.prefs.Preferences;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The player's achievements (M53), kept between games (and in the exe, which keeps its settings
 * in the same place: the user's registry, through Preferences).
 * <p>
 * What's kept is which are unlocked, and a signature over that (an HMAC-SHA256 with a key made
 * from one hidden in the game and this PC and its user), so editing the stored value, or copying
 * another PC's, gives a signature that doesn't match, and it's read as none unlocked.
 */
public final class Achievements {

    /** Each achievement: its name, and what earns it. */
    public enum Achievement {
        PLAY_SINGLEPLAYER("Play a Singleplayer game", null, 13),
        WIN_MOVE_BALANCED("Win a Moving Balanced game", null, 0),
        WIN_MOVE_URBAN("Win a Moving Urban game", null, 1),
        WIN_MOVE_RURAL("Win a Moving Rural game", null, 2),
        WIN_STILL_BALANCED("Win a No Move Balanced game", null, 3),
        WIN_STILL_URBAN("Win a No Move Urban game", null, 4),
        WIN_STILL_RURAL("Win a No Move Rural game", null, 5),
        MOVES_IN_THE_DARK("Moves in the dark", "Push the chair in the landing pod with the lights off", 14),
        LIGHTS_OUT("Lights out", "Headbutt the ceiling light in the landing pod", 15),
        TECHNICIAN("Technician", "Unlock all gadgets in one game", 6),
        INFRASTRUCTURE_EXPERT("Infrastructure Expert", "Unlock all infrastructure maps in one game", 7),
        BOTANIST("Botanist", "Unlock all flora heatmaps in one game", 8),
        ZOOLOGIST("Zoologist", "Unlock all fauna heatmaps in one game", 9),
        PLAY_MULTIPLAYER("Play a Multiplayer game", null, 10),
        WIN_MULTIPLAYER("Win a Multiplayer game", null, 11),
        FLAWLESS_MULTIPLAYER("Flawless", "Win a Multiplayer game without your opponent winning a round", 12),
        BULLSEYE("Bullseye", "Guess within 20 m of the right spot, in either mode", 16);

        public final String title, detail;
        // Its bit in what's kept (fixed, so adding one never moves another's)
        final int bit;

        Achievement(String title, String detail, int bit) {
            this.title = title;
            this.detail = detail;
            this.bit = bit;
        }

        /** What earns it, said in full. */
        public String description() {
            return detail != null ? detail : title;
        }
    }

    // (alongside the settings: see GameSettings.STORE)
    private static final Preferences STORE = GameSettings.STORE;
    private static final String KEY = "progress";
    private static final Set<Achievement> unlocked = EnumSet.noneOf(Achievement.class);
    private static boolean loaded;
    // Told whenever one's unlocked or they're reset (the menu, to show them afresh); may be null
    private static volatile Runnable onChange;

    private Achievements() {
    }

    public static void setOnChange(Runnable listener) {
        onChange = listener;
    }

    public static synchronized boolean isUnlocked(Achievement a) {
        load();
        return unlocked.contains(a);
    }

    public static synchronized int count() {
        load();
        return unlocked.size();
    }

    /** Unlocks one (if it isn't already) and keeps it. */
    public static void unlock(Achievement a) {
        synchronized (Achievements.class) {
            load();
            if (!unlocked.add(a)) return;
            save();
        }
        System.out.println("[ACHIEVEMENT] " + a.title);
        synchronized (toasts) {
            toasts.add(a);
        }
        Runnable listener = onChange;
        if (listener != null) javax.swing.SwingUtilities.invokeLater(listener);
    }

    // ------------------------------------------------------------------ the pop-up as each's unlocked

    // Those unlocked not yet shown, the first being shown since toastSince (0 not yet)
    private static final java.util.ArrayDeque<Achievement> toasts = new java.util.ArrayDeque<>();
    private static long toastSince;
    public static final long TOAST_MS = 4500L, TOAST_FADE_MS = 350L;

    /**
     * The pop-up to show now, if any: the achievement and how opaque (0 to 1), each in turn for
     * TOAST_MS from when it's first asked for.
     */
    public static Object[] toast() {
        synchronized (toasts) {
            long now = System.currentTimeMillis();
            while (!toasts.isEmpty()) {
                if (toastSince == 0L) toastSince = now;
                long shown = now - toastSince;
                if (shown < TOAST_MS) {
                    float alpha = Math.min(1f, Math.min(shown / (float) TOAST_FADE_MS, (TOAST_MS - shown) / (float) TOAST_FADE_MS));
                    return new Object[] { toasts.peek(), alpha };
                }
                toasts.poll();
                toastSince = 0L;
            }
            return null;
        }
    }

    public static final int TOAST_W = 380, TOAST_H = 78;

    /** The pop-up, TOAST_W by TOAST_H at (0, 0): "Achievement unlocked" and its name, in the HUD's style. */
    public static void paintToast(java.awt.Graphics2D g, Achievement a) {
        HudStyle.panel(g, 0, 0, TOAST_W, TOAST_H, HudStyle.GLASS_SOLID, HudStyle.Bar.RIGHT);
        HudStyle.edge(g, 0, 0, TOAST_W, TOAST_H, HudStyle.GOLD, HudStyle.Bar.RIGHT);
        // A gold star
        float cx = 40f, cy = TOAST_H / 2f;
        java.awt.geom.Path2D star = new java.awt.geom.Path2D.Float();
        for (int k = 0; k < 10; k++) {
            double r = k % 2 == 0 ? 20 : 8.5, t = -Math.PI / 2 + k * Math.PI / 5;
            if (k == 0) star.moveTo(cx + r * Math.cos(t), cy + r * Math.sin(t));
            else star.lineTo(cx + r * Math.cos(t), cy + r * Math.sin(t));
        }
        star.closePath();
        g.setColor(HudStyle.GOLD);
        g.fill(star);
        HudStyle.label(g, "Achievement unlocked", 76, 31, 11f, HudStyle.GOLD);
        g.setFont(HudStyle.font(java.awt.Font.BOLD, 19f));
        g.setColor(java.awt.Color.WHITE);
        g.drawString(a.title, 76, 57);
    }

    /** Locks them all again (the menu's Reset, once confirmed). */
    public static void reset() {
        synchronized (Achievements.class) {
            load();
            unlocked.clear();
            save();
        }
        Runnable listener = onChange;
        if (listener != null) javax.swing.SwingUtilities.invokeLater(listener);
    }

    /** The win achievement for a singleplayer game in a mode on a kind of map. */
    public static Achievement winFor(GameMode mode, MapKind map) {
        boolean moving = mode.moving;
        return switch (map) {
            case BALANCED -> moving ? Achievement.WIN_MOVE_BALANCED : Achievement.WIN_STILL_BALANCED;
            case URBAN -> moving ? Achievement.WIN_MOVE_URBAN : Achievement.WIN_STILL_URBAN;
            case RURAL -> moving ? Achievement.WIN_MOVE_RURAL : Achievement.WIN_STILL_RURAL;
        };
    }

    // ------------------------------------------------------------------ keeping them

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            String stored = STORE.get(KEY, null);
            if (stored == null) return;
            String[] parts = stored.split(":");
            if (parts.length != 2 || !parts[1].equals(sign(parts[0]))) return;
            long bits = Long.parseLong(parts[0], 16);
            for (Achievement a : Achievement.values()) if ((bits & (1L << a.bit)) != 0) unlocked.add(a);
        } catch (Exception unreadable) {
            unlocked.clear();
        }
    }

    private static void save() {
        long bits = 0;
        for (Achievement a : unlocked) bits |= 1L << a.bit;
        String value = Long.toHexString(bits);
        try {
            STORE.put(KEY, value + ":" + sign(value));
            STORE.flush();
        } catch (Exception e) {
            // (unlocked for this run at least)
        }
    }

    // (spread out, so it isn't one string to find in the game's files)
    private static final int[] SECRET = { 0x58a3, 0x1f7e, 0x9c42, 0x6d15, 0xe0b9, 0x37c8, 0xa46f, 0x2b91 };

    /** The signature for a stored value: tied to the game, this PC and its user. */
    private static String sign(String value) throws Exception {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < SECRET.length; i++) key.append(Integer.toHexString(SECRET[i] ^ (0x3141 * (i + 7))));
        key.append('|').append(System.getProperty("user.name", "")).append('|').append(System.getenv().getOrDefault("COMPUTERNAME", ""));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.toString().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(("xenoguesser-achievements:" + value).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
