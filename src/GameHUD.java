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
 * screen, which covers the 3D view, so the round's points have somewhere to land.
 */
public class GameHUD extends JPanel {
    public static final int QUARTERS = 4, ROUNDS_PER_QUARTER = 3, ROUNDS = QUARTERS * ROUNDS_PER_QUARTER;
    // The productivity needed by the end of each quarter
    public static final int[] TARGETS = { 800, 2000, 4000, 10000 };
    // The cash paid each month, before the share of the guess's points
    public static final int SALARY = 300;

    private volatile int currentRound = 1;
    private volatile int productivity = 0, cash = 0;
    private volatile int currentFps = 0;
    // Bumped whenever the round or score changes, so the GL copy knows to repaint
    private volatile int version;
    // Multiplayer (M13): the game's scoring (both players' productivity and quarter points, shown
    // instead of the target), and whether the year has gone on to final deciders (M17)
    private volatile MultiplayerScore multiplayer;
    private volatile Multiplayer session;
    private volatile boolean deciders;

    public GameHUD() {
        setOpaque(false);
        setSize(HudStyle.SCORE_W, HudStyle.SCORE_H);
        setVisible(false);
    }

    /** The cash a guess worth these points earns: the salary plus half the points. */
    public static int cashFor(int points) {
        return SALARY + points / 2;
    }

    /** A guess's points: all of them added to productivity, and cashFor them to the cash. */
    public void addRound(int points) {
        productivity += points;
        cash += cashFor(points);
        version++;
        repaint();
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

    /** Multiplayer: shows both players' productivity and quarter points (from score) instead of the target. */
    public void setMultiplayer(Multiplayer session, MultiplayerScore score) {
        this.session = session;
        this.multiplayer = score;
        version++;
        repaint();
    }

    /** Multiplayer (M17): the points were level after Q4, so rounds go on past the year, one decider at a time. */
    public void startDeciders() {
        deciders = true;
        version++;
        repaint();
    }

    /** Whether this round is a final decider (past the year's last round), and which (from 1); 0 if not. */
    public int deciderNumber() {
        return currentRound > ROUNDS ? currentRound - ROUNDS : 0;
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

    /** Advances the global game state round loop index. */
    public void advanceRound() {
        if (currentRound < ROUNDS || deciders) {
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
        // (and whenever a guess comes in from the partner, for their productivity and the points)
        Multiplayer s = session;
        return version + (s != null ? s.guessCount() * 7919 : 0);
    }

    /** Paints the panel; shared by this component and the GL HUD. */
    public void paintPanel(Graphics2D g, boolean overScene) {
        java.awt.Color body = overScene ? HudStyle.GLASS : HudStyle.GLASS_SOLID;
        MultiplayerScore score = multiplayer;
        Multiplayer s = session;
        if (score != null && s != null) {
            // Both players (119-A first on both PCs): this quarter's productivity (this player's
            // live, the partner's as of their last guess) and their quarter points
            Multiplayer.Role a = Multiplayer.Role.HOST, b = Multiplayer.Role.JOIN;
            int productivityA = a == s.role ? productivity : score.productivitySoFar(a, currentRound);
            int productivityB = b == s.role ? productivity : score.productivitySoFar(b, currentRound);
            HudStyle.paintMultiplayerScorePanel(g, getQuarter(), (currentRound - 1) % ROUNDS_PER_QUARTER, deciderNumber(),
                    new String[] { a.label, b.label }, new java.awt.Color[] { textColour(a), textColour(b) },
                    new int[] { productivityA, productivityB }, new int[] { score.points(a), score.points(b) }, cash, body);
            return;
        }
        HudStyle.paintScorePanel(g, getQuarter(), (currentRound - 1) % ROUNDS_PER_QUARTER, productivity, getTarget(), cash, body);
    }

    /** A player's colour (M6) lifted towards white, to read as text on the dark panel. */
    static java.awt.Color textColour(Multiplayer.Role player) {
        float[] c = player.trimColour();
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
