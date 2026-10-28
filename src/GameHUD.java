import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JPanel;

/**
 * The round, the productivity (the points from every guess so far), the cash, and the
 * quarter's productivity target. The game runs over a year in four quarters of three rounds
 * each, a month a round; at the end of each quarter productivity must have reached its target.
 * Each guess earns its points as productivity, and as cash the monthly salary plus half the
 * points; cash is what the shop takes. During play the panel is drawn over the 3D view by
 * the GL HUD, which can be see-through; this Swing copy is shown only over the results
 * screen, which covers the 3D view, so the round's points have somewhere to land. Multiplayer
 * (M58) has none of this: rounds go on until a player has won 10, and the panel shows each
 * player's rounds won as a row of circles in their suit's colour.
 */
public class GameHUD extends JPanel {
    public static final int QUARTERS = 4, ROUNDS_PER_QUARTER = 3, ROUNDS = QUARTERS * ROUNDS_PER_QUARTER;
    // The productivity needed by the end of each quarter
    public static final int[] TARGETS = { 800, 2000, 4000, 8000 };
    // The cash paid each month, before the share of the guess's points
    public static final int SALARY = 300;

    private volatile int currentRound = 1;
    private volatile int productivity = 0, cash = 0;
    private volatile int currentFps = 0;
    // Bumped whenever the round or score changes, so the GL copy knows to repaint
    private volatile int version;
    // Multiplayer (M58): the game's scoring (both players' rounds won, shown instead of
    // everything else), and the last round whose result has landed on the panel (the rounds won
    // are counted up to it, so a round's circle fills as its result flies up, not before)
    private volatile MultiplayerScore multiplayer;
    private volatile Multiplayer session;
    private volatile int bankedRound;

    public GameHUD() {
        setOpaque(false);
        setSize(HudStyle.SCORE_W, HudStyle.SCORE_H);
        setVisible(false);
    }

    /** The cash a guess worth these points earns: the salary plus half the points. */
    public static int cashFor(int points) {
        return SALARY + points / 2;
    }

    /** A guess's points: all of them added to productivity, and cashFor them to the cash (singleplayer only: multiplayer has no cash). */
    public void addRound(int points) {
        productivity += points;
        bankedRound = currentRound;
        if (!Multiplayer.isActive()) {
            cash += cashFor(points);
            recentPay[paidRounds++ % recentPay.length] = cashFor(points);
        }
        version++;
        repaint();
    }

    // The cash paid for the last few rounds (a quarter's worth), for the shop's prices, and how
    // many rounds have been paid
    private final int[] recentPay = new int[ROUNDS_PER_QUARTER];
    private int paidRounds;

    /** The average cash paid a round over the last few (the salary before any). */
    public float recentPay() {
        int n = Math.min(paidRounds, recentPay.length);
        if (n == 0) return SALARY;
        float sum = 0f;
        for (int i = 0; i < n; i++) sum += recentPay[i];
        return sum / n;
    }

    /** Takes cash spent in the shop. */
    public void spend(int dollars) {
        cash -= dollars;
        version++;
        repaint();
    }

    /** The cash there is to spend, in dollars. */
    public int getCash() {
        return cash;
    }

    /** The points from every guess so far this quarter. */
    public int getProductivity() {
        return productivity;
    }

    /** The round being played (or whose results are showing), from 1. */
    public int getRound() {
        return currentRound;
    }

    /** The quarter the round is in, from 0 (a final decider counts as the last quarter's). */
    public int getQuarter() {
        return Math.min(QUARTERS - 1, (currentRound - 1) / ROUNDS_PER_QUARTER);
    }

    /** Multiplayer: shows both players' rounds won (from score) instead of the year. */
    public void setMultiplayer(Multiplayer session, MultiplayerScore score) {
        this.session = session;
        this.multiplayer = score;
        version++;
        repaint();
    }

    /** Multiplayer: the rounds a player has won, as far as the panel shows (up to the last round whose result has landed). */
    public int roundsWon(Multiplayer.Role player) {
        MultiplayerScore score = multiplayer;
        return score == null ? 0 : score.wins(player, bankedRound);
    }

    /** The money needed by the end of this quarter (lower in the harder game modes: see GameMode), to the nearest 50. */
    public int getTarget() {
        return Math.round(TARGETS[getQuarter()] * GameMode.current().targetShare / 50f) * 50;
    }

    /** Whether this round is the last of its quarter (so the target is checked after it); never a final decider. */
    public boolean isQuarterEnd() {
        return currentRound <= ROUNDS && currentRound % ROUNDS_PER_QUARTER == 0;
    }

    /** Whether this is the last round of the year. */
    public boolean isLastRound() {
        return currentRound >= ROUNDS;
    }

    /** Advances the global game state round loop index (multiplayer's rounds go on until the game's won). */
    public void advanceRound() {
        if (currentRound < ROUNDS || multiplayer != null) {
            currentRound++;
            // A new quarter starts its productivity afresh
            if ((currentRound - 1) % ROUNDS_PER_QUARTER == 0) productivity = 0;
        }
        version++;
        repaint();
    }

    public void setGameFps(int fps) {
        currentFps = fps;
    }

    public int getGameFps() {
        return currentFps;
    }

    public int getVersion() {
        return version;
    }

    /** Paints the panel; shared by this component and the GL HUD. */
    public void paintPanel(Graphics2D g, boolean overScene) {
        java.awt.Color body = overScene ? HudStyle.GLASS : HudStyle.GLASS_SOLID;
        MultiplayerScore score = multiplayer;
        Multiplayer s = session;
        if (score != null && s != null) {
            // Both players, this one on top marked "(You)", each with their rounds won
            Multiplayer.Role a = s.role, b = s.role.other();
            HudStyle.paintMultiplayerScorePanel(g, currentRound, new String[] { s.name(a), s.name(b) },
                    new java.awt.Color[] { s.suit(a).awt(), s.suit(b).awt() }, new java.awt.Color[] { textColour(s.suit(a)), textColour(s.suit(b)) },
                    new int[] { roundsWon(a), roundsWon(b) }, body);
            return;
        }
        HudStyle.paintScorePanel(g, getQuarter(), (currentRound - 1) % ROUNDS_PER_QUARTER, productivity, getTarget(), cash, body);
    }

    /** A suit's colour (M6, M58) lifted towards white, to read as text on the dark panel. */
    static java.awt.Color textColour(SuitColour suit) {
        float[] c = suit.trimColour();
        return new java.awt.Color((c[0] + 2f) / 3f, (c[1] + 2f) / 3f, (c[2] + 2f) / 3f);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            HudStyle.smooth(g2d);
            paintPanel(g2d, false);
        } finally {
            g2d.dispose();
        }
    }
}
