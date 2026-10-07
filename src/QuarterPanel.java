import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;

/**
 * Between a quarter's last results and the shop: whether the quarter's productivity target
 * was met (the points from every guess so far had at least reached it), drawn as the HUD's panels are. One button
 * goes on: to the shop, or back to the menu should the quarter have been failed or the year won.
 */
public class QuarterPanel extends JPanel {
    private int quarter, money, target;
    private boolean passed, won;
    private Runnable onContinue;
    private final Rectangle button = new Rectangle();

    public QuarterPanel() {
        setOpaque(true);
        setLayout(null);
        setVisible(false);
        setFocusable(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && button.contains(e.getPoint()) && onContinue != null) {
                    Runnable next = onContinue;
                    onContinue = null;
                    next.run();
                }
            }
        });
    }

    /** Shows how quarter (from 0) went: the productivity against its target; won if it was the last quarter, passed. */
    public void show(int quarter, int money, int target, boolean won, Runnable onContinue) {
        this.quarter = quarter;
        this.money = money;
        this.target = target;
        this.passed = money >= target;
        this.won = won && passed;
        this.onContinue = onContinue;
        setVisible(true);
        repaint();
    }

    public void close() {
        setVisible(false);
        onContinue = null;
    }

    public boolean isOpen() {
        return isVisible();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            HudStyle.smooth(g);
            int w = getWidth(), h = getHeight();
            g.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.5f, h * 0.45f), Math.max(w, h) * 0.75f,
                    new float[] { 0f, 1f }, new Color[] { new Color(26, 34, 48), new Color(6, 9, 14) }));
            g.fillRect(0, 0, w, h);

            Color colour = HudStyle.QUARTER_COLOURS[quarter];
            int pw = Math.min(w - 80, 640), ph = 300;
            int px = (w - pw) / 2, py = (h - ph) / 2 - 30;
            HudStyle.panel(g, px, py, pw, ph, HudStyle.GLASS_SOLID);

            String heading = "Quarter " + (quarter + 1);
            float headingW = HudStyle.labelWidth(g, heading, 14f);
            HudStyle.label(g, heading, px + (pw - headingW) / 2f, py + 50, 14f, colour);

            String result = won ? "YOU WIN" : passed ? "QUARTER PASSED" : "QUARTER FAILED";
            g.setFont(HudStyle.font(Font.BOLD, 46f).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.08f)));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(passed ? new Color(110, 225, 130) : new Color(245, 105, 90));
            g.drawString(result, px + (pw - fm.stringWidth(result)) / 2f, py + 120);

            String detail = String.format("%,d of the %,d productivity target", money, target);
            g.setFont(HudStyle.font(Font.PLAIN, 20f));
            fm = g.getFontMetrics();
            g.setColor(HudStyle.VALUE);
            g.drawString(detail, px + (pw - fm.stringWidth(detail)) / 2f, py + 172);

            String text = passed && !won ? "Continue to the shop" : "Back to the menu";
            int bw = 300, bh = 48;
            button.setBounds(px + (pw - bw) / 2, py + ph - bh - 34, bw, bh);
            HudStyle.panel(g, button.x, button.y, bw, bh, passed ? new Color(40, 150, 90) : new Color(60, 66, 76));
            float size = 15f, textW = HudStyle.labelWidth(g, text, size);
            HudStyle.label(g, text, button.x + (bw - textW) / 2f, button.y + bh / 2f + size * 0.38f, size, HudStyle.VALUE);
        } finally {
            g.dispose();
        }
    }
}
