import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JFrame;

/**
 * The game window's own title bar in windowed mode (the window has no system frame, so it can
 * switch to fullscreen and back without being rebuilt): its name, drag it to move the window,
 * and buttons to minimise it and to close the game.
 */
public class WindowTitleBar extends JComponent {
    public static final int HEIGHT = 30;
    private static final int BUTTON = 46;

    private final JFrame frame;
    private final Runnable onClose;
    private Point grabbedAt;
    private int hovered = -1;   // 0 minimise, 1 close

    public WindowTitleBar(JFrame frame, Runnable onClose) {
        this.frame = frame;
        this.onClose = onClose;
        setPreferredSize(new Dimension(100, HEIGHT));
        setFocusable(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int button = buttonAt(e.getX());
                if (button == 0) {
                    frame.setState(JFrame.ICONIFIED);
                } else if (button == 1) {
                    onClose.run();
                } else {
                    grabbedAt = e.getPoint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                grabbedAt = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (grabbedAt == null) return;
                Point on = e.getLocationOnScreen();
                frame.setLocation(on.x - grabbedAt.x, on.y - grabbedAt.y);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int over = buttonAt(e.getX());
                if (over != hovered) {
                    hovered = over;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = -1;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Which button is under x: 0 minimise, 1 close, -1 none. */
    private int buttonAt(int x) {
        int w = getWidth();
        if (x >= w - BUTTON) return 1;
        if (x >= w - 2 * BUTTON) return 0;
        return -1;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        HudStyle.smooth(g);
        int w = getWidth(), h = getHeight();
        g.setColor(new Color(14, 16, 22));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(120, 205, 235, 70));
        g.fillRect(0, h - 1, w, 1);
        g.setFont(HudStyle.font(Font.BOLD, 13f));
        g.setColor(HudStyle.VALUE);
        g.drawString(frame.getTitle(), 12, (h + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2);
        // Minimise and close
        for (int b = 0; b < 2; b++) {
            Rectangle r = new Rectangle(w - (2 - b) * BUTTON, 0, BUTTON, h - 1);
            if (hovered == b) {
                g.setColor(b == 1 ? new Color(196, 43, 28) : new Color(255, 255, 255, 30));
                g.fill(r);
            }
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.2f));
            int cx = r.x + r.width / 2, cy = h / 2;
            if (b == 0) {
                g.drawLine(cx - 5, cy, cx + 5, cy);
            } else {
                g.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
                g.drawLine(cx - 5, cy + 5, cx + 5, cy - 5);
            }
        }
        g.dispose();
    }
}
