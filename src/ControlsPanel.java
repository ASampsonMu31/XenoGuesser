import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The controls Xenocorp encloses with January's message (singleplayer), and the settings open: the
 * controls, each key beside what it does, in a panel in the middle of the screen, closed with
 * Continue or Enter.
 */
public class ControlsPanel extends JComponent {
    // Each row: its keys (shown as key caps; "" for none) and what they do
    private static final String[][] ROWS = {
        { "W A S D", "Move" },
        { "Shift", "Sprint (uses stamina)" },
        { "Mouse", "Look" },
        { "Space", "Jump" },
        { "M", "Expand / contract the map" },
        { "Scroll", "Zoom the map" },
        { "Click", "Place your marker on the enlarged map" },
        { "Middle mouse", "Take out / put away gadgets" },
        { "Scroll", "Switch gadget (while one's out)" },
        { "Esc", "Settings" },
        { "Enter", "Skip a message" },
    };
    public static final int W = 560, H = 542;

    private final Rectangle button = new Rectangle();
    private boolean hovered;
    private Runnable onClose;

    public ControlsPanel() {
        // (opaque, over the 3D view: the system draws that over any see-through component, so
        // a see-through one there is never seen)
        setOpaque(true);
        setVisible(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean over = button.contains(e.getPoint());
                if (over != hovered) {
                    hovered = over;
                    repaint();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && button.contains(e.getPoint())) close();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Shows the controls; onClose is told when they're closed. */
    public void open(Runnable onClose) {
        this.onClose = onClose;
        hovered = false;
        setVisible(true);
        repaint();
    }

    /** Closes the controls (Continue, or Enter). */
    public void close() {
        if (!isVisible()) return;
        setVisible(false);
        Runnable then = onClose;
        onClose = null;
        if (then != null) then.run();
    }

    public boolean isOpen() {
        return isVisible();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        HudStyle.smooth(g);
        // A dark backdrop over the whole window, a little lighter in the middle
        g.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(getWidth() * 0.5f, getHeight() * 0.45f),
                Math.max(getWidth(), getHeight()) * 0.75f, new float[] { 0f, 1f }, new Color[] { new Color(26, 34, 48), new Color(6, 9, 14) }));
        g.fillRect(0, 0, getWidth(), getHeight());
        // (scaled to the window: designed at W by H, as big as fits with room round it)
        float scale = Math.min(1.6f, Math.min(getWidth() / (W + 80f), getHeight() / (H + 80f)));
        g.translate((getWidth() - W * scale) / 2f, (getHeight() - H * scale) / 2f);
        g.scale(scale, scale);
        HudStyle.panel(g, 0, 0, W, H, HudStyle.GLASS_SOLID);
        HudStyle.label(g, "Enclosed: Controls", 28, 40, 13f, HudStyle.ACCENT);
        g.setColor(new Color(120, 205, 235, 70));
        g.fillRect(28, 52, W - 56, 1);
        float y = 72;
        for (String[] row : ROWS) {
            float x = 28;
            for (String key : row[0].split(" ")) x += HudStyle.keyCap(g, key, x, y, 28f) + 6f;
            g.setFont(HudStyle.font(Font.PLAIN, 16f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(HudStyle.VALUE);
            g.drawString(row[1], 210, y + (28 + fm.getAscent() - fm.getDescent()) / 2f);
            y += 36;
        }
        // Continue (Enter does as well)
        int bw = 220, bh = 44, bx = (W - bw) / 2, by = H - bh - 22;
        HudStyle.panel(g, bx, by, bw, bh, hovered ? new Color(40, 150, 190) : new Color(26, 110, 145));
        g.setFont(HudStyle.font(Font.BOLD, 17f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Color.WHITE);
        String text = "Continue";
        g.drawString(text, bx + (bw - fm.stringWidth(text)) / 2f, by + (bh + fm.getAscent() - fm.getDescent()) / 2f);
        button.setBounds(Math.round((getWidth() - W * scale) / 2f + bx * scale), Math.round((getHeight() - H * scale) / 2f + by * scale),
                Math.round(bw * scale), Math.round(bh * scale));
        g.dispose();
    }
}
