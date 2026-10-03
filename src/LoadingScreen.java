import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * Full-window loading screen shown from launch until the first frame of the round renders:
 * a plain grey background with the title, the current stage and an estimated progress bar.
 */
public class LoadingScreen extends JComponent {

    private static final int REPAINT_MS = 33;
    private static final Color BACKGROUND = new Color(58, 60, 64);
    private static final Color TRACK = new Color(255, 255, 255, 38);
    private static final Color FILL = new Color(214, 218, 226);
    private static final Color TEXT = new Color(232, 234, 240);
    private static final Color SUBTLE_TEXT = new Color(176, 180, 190);

    private final LoadingProgress progress;
    private final long worldSeed;
    private final Timer repaintTimer;
    private float smoothedFraction;

    public LoadingScreen(LoadingProgress progress, long worldSeed) {
        this.progress = progress;
        this.worldSeed = worldSeed;
        setOpaque(true);
        repaintTimer = new Timer(REPAINT_MS, e -> repaint());
        repaintTimer.start();
    }

    public void stop() {
        repaintTimer.stop();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);

        int contentW = Math.min(560, w - 80);
        int x = (w - contentW) / 2;
        int y = h / 2 - 60;

        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 38));
        drawSpacedCentred(g, "XENOGUESSER", w / 2, y, 6);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.setColor(SUBTLE_TEXT);
        String subtitle = "Generating world " + worldSeed;
        g.drawString(subtitle, (w - g.getFontMetrics().stringWidth(subtitle)) / 2, y + 30);

        // Ease the bar toward the estimate so it glides rather than jumps
        float target = progress.overallFraction();
        smoothedFraction += (target - smoothedFraction) * 0.18f;
        int barY = y + 58, barH = 12;
        g.setColor(TRACK);
        g.fill(new RoundRectangle2D.Float(x, barY, contentW, barH, barH, barH));
        g.setColor(FILL);
        g.fill(new RoundRectangle2D.Float(x, barY, Math.max(barH, contentW * smoothedFraction), barH, barH, barH));

        g.setColor(TEXT);
        g.drawString(progress.currentLabel() + "…", x, barY + 36);
        float secondsLeft = progress.estimatedSecondsLeft();
        String right = String.format("%d%%", Math.round(smoothedFraction * 100))
                + (secondsLeft >= 1f ? String.format("  ·  ~%d s left", Math.round(secondsLeft)) : "");
        g.drawString(right, x + contentW - g.getFontMetrics().stringWidth(right), barY + 36);
        g.dispose();
    }

    private static void drawSpacedCentred(Graphics2D g, String text, int centreX, int y, int spacing) {
        FontMetrics fm = g.getFontMetrics();
        int width = 0;
        for (char c : text.toCharArray()) width += fm.charWidth(c) + spacing;
        int x = centreX - (width - spacing) / 2;
        for (char c : text.toCharArray()) {
            g.drawString(String.valueOf(c), x, y);
            x += fm.charWidth(c) + spacing;
        }
    }
}
