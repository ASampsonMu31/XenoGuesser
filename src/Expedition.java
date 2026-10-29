import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Path2D;

/**
 * The game being played, said the same way wherever it's shown (the loading screen's banner, the
 * settings): what kind of game it is (singleplayer, with a friend, or matchmade, casual or
 * ranked), and its game mode and map. Also matchmaking's choices, which are ticked rather than
 * chosen one only (any of those ticked will do), kept between games; and the button each choice
 * of mode or map is drawn on, as a radio button or a tick box, for the screens they're chosen on.
 */
public final class Expedition {

    private Expedition() {
    }

    /** "Singleplayer", "Friends multiplayer" or "Matchmaking multiplayer: Casual" (or Ranked), for the game being played. */
    public static String kind() {
        Multiplayer session = Multiplayer.active();
        if (session == null) return "Singleplayer";
        if (!session.isMatchmade()) return "Friends multiplayer";
        return "Matchmaking multiplayer: " + (session.isRanked() ? "Ranked" : "Casual");
    }

    /** The game mode and map being played, e.g. "Move  ·  Balanced map". */
    public static String choice() {
        return GameMode.current().label + "  ·  " + MapKind.current().label + " map";
    }

    // ------------------------------------------------------------------ matchmaking's choices

    // The bit for each game mode and map in a set of them (their ordinal's), and all of them
    public static final int ALL_MODES = (1 << GameMode.values().length) - 1, ALL_MAPS = (1 << MapKind.values().length) - 1;

    /** The game modes ticked for matchmaking (bit n: GameMode.values()[n]), all of them at first. */
    public static int matchModes() {
        int modes = GameSettings.STORE.getInt("matchModes", ALL_MODES) & ALL_MODES;
        return modes != 0 ? modes : ALL_MODES;
    }

    /** The maps ticked for matchmaking (bit n: MapKind.values()[n]), all of them at first. */
    public static int matchMaps() {
        int maps = GameSettings.STORE.getInt("matchMaps", ALL_MAPS) & ALL_MAPS;
        return maps != 0 ? maps : ALL_MAPS;
    }

    /** Ticks or unticks a game mode for matchmaking (the last one ticked can't be unticked). */
    public static void toggleMatchMode(GameMode mode) {
        store("matchModes", toggled(matchModes(), mode.ordinal()));
    }

    /** Ticks or unticks a map for matchmaking (the last one ticked can't be unticked). */
    public static void toggleMatchMap(MapKind map) {
        store("matchMaps", toggled(matchMaps(), map.ordinal()));
    }

    private static int toggled(int set, int bit) {
        int after = set ^ (1 << bit);
        return after != 0 ? after : set;
    }

    private static void store(String key, int value) {
        try {
            GameSettings.STORE.putInt(key, value);
        } catch (Exception e) {
            // Not kept (it's read back from the store), so the tick doesn't change
        }
    }

    /**
     * Matchmaking (M59): one of the game modes and one of the maps both players ticked, each at
     * random from those they share (the host's choice, sent to the joiner). Should they share
     * none (only a local test's queue matches without asking), the host's own first ones.
     */
    public static GameMode pickMode(int mine, int theirs, java.util.Random random) {
        return GameMode.values()[pickBit(mine, theirs, random)];
    }

    /** As pickMode, for the map. */
    public static MapKind pickMap(int mine, int theirs, java.util.Random random) {
        return MapKind.values()[pickBit(mine, theirs, random)];
    }

    private static int pickBit(int mine, int theirs, java.util.Random random) {
        int shared = mine & theirs;
        if (shared == 0) {
            System.out.println("[MP] No game mode or map in common with the opponent: the host's own is played");
            shared = Integer.lowestOneBit(mine);
        }
        int pick = random.nextInt(Integer.bitCount(shared));
        for (int bit = 0; ; bit++) {
            if ((shared & (1 << bit)) != 0 && pick-- == 0) return bit;
        }
    }

    // ------------------------------------------------------------------ drawing

    /**
     * A choice of mode or map on a button, as the main menu's: cut corners, lit when chosen,
     * lighter while the pointer's over it; a round radio button when only one may be chosen
     * (ticks false), a tick box when any number may (true); then its label.
     */
    public static void paintChoice(Graphics2D g, Rectangle r, String label, boolean chosen, boolean ticks, boolean over, float unit) {
        Path2D shape = HudStyle.chamfered(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 9 * unit);
        g.setColor(chosen ? new Color(24, 110, 145, 225) : over ? new Color(70, 80, 96, 235) : new Color(24, 30, 40, 210));
        g.fill(shape);
        g.setColor(over || chosen ? Color.WHITE : HudStyle.EDGE);
        g.setStroke(new BasicStroke(1.4f * Math.max(1f, unit)));
        g.draw(shape);
        g.setFont(HudStyle.font(Font.BOLD, r.height * 0.36f));
        FontMetrics fm = g.getFontMetrics();
        float mark = r.height * 0.34f, mx = r.x + 16 * unit, my = r.y + (r.height - mark) / 2f;
        g.setColor(Color.WHITE);
        if (ticks) {
            java.awt.geom.RoundRectangle2D box = new java.awt.geom.RoundRectangle2D.Float(mx, my, mark, mark, 4 * unit, 4 * unit);
            if (chosen) {
                g.fill(box);
                g.setColor(new Color(24, 110, 145));
                g.setStroke(new BasicStroke(2f * Math.max(1f, unit), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D tick = new Path2D.Float();
                tick.moveTo(mx + mark * 0.22f, my + mark * 0.52f);
                tick.lineTo(mx + mark * 0.43f, my + mark * 0.74f);
                tick.lineTo(mx + mark * 0.8f, my + mark * 0.28f);
                g.draw(tick);
            } else {
                g.draw(box);
            }
        } else {
            g.draw(new java.awt.geom.Ellipse2D.Float(mx, my, mark, mark));
            if (chosen) {
                float inset = mark * 0.26f;
                g.fill(new java.awt.geom.Ellipse2D.Float(mx + inset, my + inset, mark - 2 * inset, mark - 2 * inset));
            }
        }
        g.setColor(chosen || over ? Color.WHITE : new Color(215, 222, 232));
        g.drawString(label, mx + mark + 10 * unit, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2f);
    }

    /** How wide paintChoice's button is for a label, as tall as height. */
    public static float choiceWidth(Graphics2D g, String label, float height, float unit) {
        g.setFont(HudStyle.font(Font.BOLD, height * 0.36f));
        return 16 * unit + height * 0.34f + 10 * unit + g.getFontMetrics().stringWidth(label) + 22 * unit;
    }

    // The banner's padding and its two lines' sizes
    private static final float BANNER_PAD_X = 28f, BANNER_KICKER = 12f, BANNER_TEXT = 22f;

    /** The loading screen's banner's size (see paintBanner), measured with g. */
    public static Dimension bannerSize(Graphics2D g) {
        g.setFont(HudStyle.font(Font.BOLD, BANNER_TEXT));
        float text = g.getFontMetrics().stringWidth(choice());
        float kicker = HudStyle.labelWidth(g, kind(), BANNER_KICKER);
        return new Dimension(Math.round(Math.max(text, kicker) + BANNER_PAD_X * 2), 74);
    }

    /**
     * The game loading, on one of the HUD's panels at (0, 0) as big as bannerSize: what kind of
     * game it is in small capitals over its game mode and map.
     */
    public static void paintBanner(Graphics2D g) {
        Dimension size = bannerSize(g);
        HudStyle.panel(g, 0, 0, size.width, size.height, HudStyle.GLASS);
        String kind = kind();
        HudStyle.label(g, kind, (size.width - HudStyle.labelWidth(g, kind, BANNER_KICKER)) / 2f, 27, BANNER_KICKER, HudStyle.ACCENT);
        g.setFont(HudStyle.font(Font.BOLD, BANNER_TEXT));
        FontMetrics fm = g.getFontMetrics();
        String choice = choice();
        g.setColor(HudStyle.VALUE);
        g.drawString(choice, (size.width - fm.stringWidth(choice)) / 2f, 57);
    }
}
