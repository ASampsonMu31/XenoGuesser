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
    private int currentFps = 0;

    // --- COLOURS & FONTS ---
    private final Color cardBackground = new Color(0, 0, 0); 
    private final Color textGold = new Color(240, 190, 60);
    private final Color textWhite = new Color(245, 245, 245);
    private final Color textGreen = new Color(80, 220, 100); // Fixed missing variable
    
    private final Font uniformFont = new Font("SansSerif", Font.BOLD, 15);
    private final Font smallFont = new Font("SansSerif", Font.PLAIN, 11); // Fixed missing variable

    public GameHUD() {
        this.setOpaque(true); 
        // Increased height slightly to 115 so the new FPS line fits comfortably without clipping
        this.setSize(220, 115);  
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

    /**
     * Call this from the main game loop/panel to update the number.
     */
    public void setGameFps(int fps) {
        this.currentFps = fps;
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

            // Background
            g2d.setColor(cardBackground);
            g2d.fillRect(0, 0, w, h);

            // Border
            g2d.setColor(new Color(255, 255, 255, 30));
            g2d.drawRect(0, 0, w - 1, h - 1);

            // Row 1: Round Info
            g2d.setFont(uniformFont);
            g2d.setColor(new Color(180, 180, 180));
            g2d.drawString("ROUND:", 20, 38);
            
            g2d.setColor(textGold);
            g2d.drawString(currentRound + " / " + MAX_ROUNDS, 150, 38);

            // Row 2: Total Score Info
            g2d.setColor(new Color(180, 180, 180));
            g2d.drawString("TOTAL SCORE:", 20, 68);

            g2d.setColor(textWhite);
            String scoreString = String.format("%,d", totalAccumulatedScore);
            g2d.drawString(scoreString, 150, 68);

            // Row 3: Game FPS Performance Engine Info
            g2d.setFont(smallFont);
            g2d.setColor(new Color(130, 130, 130));
            g2d.drawString("GAME FPS:", 20, 98);

            g2d.setColor(textGreen);
            g2d.drawString(currentFps + " FPS", 150, 98);

        } finally {
            g2d.dispose();
        }
    }
}