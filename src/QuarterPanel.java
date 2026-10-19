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
 *
 * In multiplayer (showMultiplayer) it shows the quarter's verdict between the two players
 * (M13), a final decider's (M17), or the game's end: a heading, a headline, lines of detail
 * each in its own colour, and the button, which once pressed greys and says who it's waiting
 * for until the partner has pressed theirs (M16).
 */
public class QuarterPanel extends JPanel {
    private int quarter, money, target;
    private boolean passed, won;
    private Runnable onContinue;
    private final Rectangle button = new Rectangle();
    // Multiplayer's verdicts: what's said, and once the button's pressed, what it's waiting for
    private boolean multiplayer, pressed;
    private String kicker, headline, buttonText;
    private Color kickerColour, headlineColour;
    // The panel's edge: green when the outcome is good for this player, red when not, null for the usual
    private Color edge;
    private java.util.List<String> lines = java.util.List.of();
    private java.util.List<Color> lineColours = java.util.List.of();
    private java.util.function.Supplier<String> waiting;
    private final javax.swing.Timer waitingRepaint = new javax.swing.Timer(250, e -> repaint());

    public QuarterPanel() {
        setOpaque(true);
        setLayout(null);
        setVisible(false);
        setFocusable(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && button.contains(e.getPoint())) press();
            }
        });
    }

    private void press() {
        if (onContinue == null) return;
        Runnable next = onContinue;
        onContinue = null;
        pressed = true;
        repaint();
        next.run();
    }

    /** Developer aid: presses the button, as if clicked. */
    public void devPress() {
        press();
    }

    /**
     * Multiplayer: shows a verdict (or the game's end): kicker over a headline, then lines (each
     * in lineColours), the panel edged in edge (green good, red bad; null the usual), and a
     * button saying buttonText that runs onContinue; once pressed, while
     * waiting gives a text (e.g. "Waiting for Player 119-B") the button shows that, greyed.
     */
    public void showMultiplayer(String kicker, Color kickerColour, String headline, Color headlineColour, Color edge,
                                java.util.List<String> lines, java.util.List<Color> lineColours, String buttonText,
                                java.util.function.Supplier<String> waiting, Runnable onContinue) {
        this.multiplayer = true;
        this.pressed = false;
        this.kicker = kicker;
        this.kickerColour = kickerColour;
        this.headline = headline;
        this.headlineColour = headlineColour;
        this.edge = edge;
        this.lines = java.util.List.copyOf(lines);
        this.lineColours = java.util.List.copyOf(lineColours);
        this.buttonText = buttonText;
        this.waiting = waiting;
        this.onContinue = onContinue;
        waitingRepaint.start();
        setVisible(true);
        repaint();
    }

    // Why the quarter's lost before its end (M50), or null
    private String outOfReach;

    /**
     * Shows a quarter (from 0) lost before its end: its target out of reach, even full marks in
     * the rounds left (left of them) reaching only best.
     */
    public void showOutOfReach(int quarter, int money, int target, int left, int best, Runnable onContinue) {
        show(quarter, money, target, false, onContinue);
        outOfReach = String.format("Even full marks in the %s left would reach only %,d.", left == 1 ? "round" : left + " rounds", best);
        repaint();
    }

    /** Shows how quarter (from 0) went: the productivity against its target; won if it was the last quarter, passed. */
    public void show(int quarter, int money, int target, boolean won, Runnable onContinue) {
        this.multiplayer = false;
        this.outOfReach = null;
        this.pressed = false;
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
        waitingRepaint.stop();
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
            if (multiplayer) {
                paintMultiplayer(g, w, h);
                return;
            }

            Color colour = HudStyle.QUARTER_COLOURS[quarter];
            int pw = Math.min(w - 80, 640), ph = outOfReach != null ? 340 : 300;
            int px = (w - pw) / 2, py = (h - ph) / 2 - 30;
            HudStyle.panel(g, px, py, pw, ph, HudStyle.GLASS_SOLID);

            String heading = "Quarter " + (quarter + 1);
            float headingW = HudStyle.labelWidth(g, heading, 14f);
            HudStyle.label(g, heading, px + (pw - headingW) / 2f, py + 50, 14f, colour);

            String result = won ? "YOU WIN" : passed ? "QUARTER PASSED" : outOfReach != null ? "TARGET OUT OF REACH" : "QUARTER FAILED";
            g.setFont(HudStyle.font(Font.BOLD, outOfReach != null ? 34f : 46f).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.08f)));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(passed ? new Color(110, 225, 130) : new Color(245, 105, 90));
            g.drawString(result, px + (pw - fm.stringWidth(result)) / 2f, py + 120);

            String detail = String.format("%,d of the %,d productivity target", money, target);
            g.setFont(HudStyle.font(Font.PLAIN, 20f));
            fm = g.getFontMetrics();
            g.setColor(HudStyle.VALUE);
            g.drawString(detail, px + (pw - fm.stringWidth(detail)) / 2f, py + 172);
            if (outOfReach != null) {
                g.setFont(HudStyle.font(Font.PLAIN, 17f));
                fm = g.getFontMetrics();
                g.setColor(HudStyle.LABEL);
                g.drawString(outOfReach, px + (pw - fm.stringWidth(outOfReach)) / 2f, py + 208);
            }

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

    /** Multiplayer's panel: the kicker, the headline (wrapped), the lines, and the button or what it waits for. */
    private void paintMultiplayer(Graphics2D g, int w, int h) {
        int pw = Math.min(w - 80, 760);
        Font headlineFont = HudStyle.font(Font.BOLD, 30f), lineFont = HudStyle.font(Font.PLAIN, 19f);
        java.util.List<String> headlineLines = wrap(g.getFontMetrics(headlineFont), headline, pw - 80);
        // (each detail line wrapped too, keeping its colour)
        java.util.List<String> detail = new java.util.ArrayList<>();
        java.util.List<Color> detailColours = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            for (String part : wrap(g.getFontMetrics(lineFont), lines.get(i), pw - 80)) {
                detail.add(part);
                detailColours.add(i < lineColours.size() ? lineColours.get(i) : HudStyle.VALUE);
            }
        }
        int ph = 70 + headlineLines.size() * 40 + 16 + detail.size() * 30 + 34 + 48 + 34;
        int px = (w - pw) / 2, py = Math.max(20, (h - ph) / 2 - 30);
        HudStyle.panel(g, px, py, pw, ph, HudStyle.GLASS_SOLID);
        if (edge != null) HudStyle.edge(g, px, py, pw, ph, edge);
        float kickerW = HudStyle.labelWidth(g, kicker, 14f);
        HudStyle.label(g, kicker, px + (pw - kickerW) / 2f, py + 50, 14f, kickerColour);
        g.setFont(headlineFont);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(headlineColour);
        int y = py + 100;
        for (String line : headlineLines) {
            g.drawString(line, px + (pw - fm.stringWidth(line)) / 2f, y);
            y += 40;
        }
        y += 12;
        g.setFont(lineFont);
        fm = g.getFontMetrics();
        for (int i = 0; i < detail.size(); i++) {
            g.setColor(detailColours.get(i));
            g.drawString(detail.get(i), px + (pw - fm.stringWidth(detail.get(i))) / 2f, y);
            y += 30;
        }
        String waitingFor = pressed && waiting != null ? waiting.get() : null;
        String text = waitingFor != null ? waitingFor + ".".repeat((int) (System.currentTimeMillis() / 400 % 4)) : buttonText;
        int bw = 360, bh = 48;
        button.setBounds(px + (pw - bw) / 2, py + ph - bh - 34, bw, bh);
        HudStyle.panel(g, button.x, button.y, bw, bh, waitingFor != null || pressed ? new Color(60, 66, 76) : new Color(40, 150, 90));
        float size = 15f, textW = HudStyle.labelWidth(g, text, size);
        HudStyle.label(g, text, button.x + (bw - textW) / 2f, button.y + bh / 2f + size * 0.38f, size, HudStyle.VALUE);
    }

    private static java.util.List<String> wrap(FontMetrics fm, String text, int width) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String line = "";
        for (String word : text.split(" ")) {
            String tried = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(tried) > width && !line.isEmpty()) {
                out.add(line);
                line = word;
            } else {
                line = tried;
            }
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }
}
