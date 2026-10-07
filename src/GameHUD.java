import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JPanel;

/**
 * The round, the money (score) and the quarter's productivity target. The game runs over a
 * year in four quarters of three rounds each, a month a round; at the end of each quarter
 * the money must have reached its target. During play the panel is drawn over the 3D view by
 * the GL HUD, which can be see-through; this Swing copy is shown only over the results
 * screen, which covers the 3D view, so the round's points have somewhere to land.
 */
public class GameHUD extends JPanel {
    public static final int QUARTERS = 4, ROUNDS_PER_QUARTER = 3, ROUNDS = QUARTERS * ROUNDS_PER_QUARTER;
    // The money needed at the end of each quarter
    public static final int[] TARGETS = { 800, 2000, 4000, 10000 };

    private volatile int currentRound = 1;
    private volatile int totalAccumulatedScore = 0;
    private volatile int currentFps = 0;
    // Bumped whenever the round or score changes, so the GL copy knows to repaint
    private volatile int version;

    public GameHUD() {
        setOpaque(false);
        setSize(HudStyle.SCORE_W, HudStyle.SCORE_H);
        setVisible(false);
    }

    /** Appends round points into the continuous score tracker. */
    public void addScore(int roundScore) {
        totalAccumulatedScore += roundScore;
        version++;
        repaint();
    }

    /** Takes money (score) spent in the shop. */
    public void spend(int dollars) {
        totalAccumulatedScore -= dollars;
        version++;
        repaint();
    }

    /** The player's score: the dollars they have to spend. */
    public int getScore() {
        return totalAccumulatedScore;
    }

    /** The round being played (or whose results are showing), from 1. */
    public int getRound() {
        return currentRound;
    }

    /** The quarter the round is in, from 0. */
    public int getQuarter() {
        return (currentRound - 1) / ROUNDS_PER_QUARTER;
    }

    /** The money needed by the end of this quarter. */
    public int getTarget() {
        return TARGETS[getQuarter()];
    }

    /** Whether this round is the last of its quarter (so the target is checked after it). */
    public boolean isQuarterEnd() {
        return currentRound % ROUNDS_PER_QUARTER == 0;
    }

    /** Whether this is the last round of the year. */
    public boolean isLastRound() {
        return currentRound >= ROUNDS;
    }

    /** Advances the global game state round loop index. */
    public void advanceRound() {
        if (currentRound < ROUNDS) {
            currentRound++;
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
        HudStyle.paintScorePanel(g, getQuarter(), (currentRound - 1) % ROUNDS_PER_QUARTER, totalAccumulatedScore, getTarget(),
                overScene ? HudStyle.GLASS : HudStyle.GLASS_SOLID);
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
