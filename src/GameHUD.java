import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;

public class GameHUD extends JPanel {
    private int currentRound = 1;
    private final int MAX_ROUNDS = 20;
    private int totalAccumulatedScore = 0;

    // UI Colors matching your premium dark aesthetic
    private final Color cardBackground = new Color(0, 0, 0); // Solid black panel
    private final Color textGold = new Color(240, 190, 60);
    private final Color textWhite = new Color(245, 245, 245);
    
    // Completely uniform bold font across all text items
    private final Font uniformFont = new Font("SansSerif", Font.BOLD, 15);

    public GameHUD() {
        this.setOpaque(true); // Sharp square corners allow safe optimization back to true opaque layout
        this.setSize(220, 95);  // Strict bounds for its static footprint
    }

    /**
     * Appends round points into the continuous score tracker.
     */
    public void addScore(int roundScore) {
        this.totalAccumulatedScore += roundScore;
        repaint();
    }

    /**
     * Advances the global game state round loop index.
     */
    public void advanceRound() {
        if (currentRound < MAX_ROUNDS) {
            currentRound++;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();

        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // 1. Draw Background Card Panel (Fills entire component area with sharp square edges)
            g2d.setColor(cardBackground);
            g2d.fillRect(0, 0, w, h);

            // 2. Draw Soft Outer Border Glow
            g2d.setColor(new Color(255, 255, 255, 30));
            g2d.drawRect(0, 0, w - 1, h - 1);

            // Set uniform font globally for text operations
            g2d.setFont(uniformFont);

            // 3. Render Round Counter Text (Uppercase casing)
            g2d.setColor(new Color(180, 180, 180));
            g2d.drawString("ROUND:", 20, 38);
            
            g2d.setColor(textGold);
            g2d.drawString(currentRound + " / " + MAX_ROUNDS, 150, 38);

            // 4. Render Cumulative Point Strings (Consistent font sizes, styling, and uppercase casing)
            g2d.setColor(new Color(180, 180, 180));
            g2d.drawString("TOTAL SCORE:", 20, 68);

            g2d.setColor(textWhite);
            String scoreString = String.format("%,d", totalAccumulatedScore);
            g2d.drawString(scoreString, 150, 68);

        } finally {
            g2d.dispose();
        }
    }
}