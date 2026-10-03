import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JPanel;

/**
 * The round and total score. During play the panel is drawn over the 3D view by the GL
 * HUD, which can be see-through; this Swing copy is shown only over the results screen,
 * which covers the 3D view, so the round's points have somewhere to land.
 */
public class GameHUD extends JPanel {
    private static final int MAX_ROUNDS = 20;

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

    /** Advances the global game state round loop index. */
    public void advanceRound() {
        if (currentRound < MAX_ROUNDS) {
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
        HudStyle.paintScorePanel(g, currentRound, MAX_ROUNDS, totalAccumulatedScore,
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
