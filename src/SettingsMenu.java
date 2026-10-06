import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JPanel;

/** The settings, opened with Esc during the game: the FPS counter, and fullscreen or windowed. */
public class SettingsMenu extends JPanel {

    public static final int W = 440, H = 362;

    private final Runnable onResume;
    private final Runnable onQuit, onDisplayChanged;
    private final Rectangle fpsRow = new Rectangle(30, 96, W - 60, 52);
    private final Rectangle fullscreenRow = new Rectangle(30, 158, W - 60, 52);
    private final Rectangle resumeButton = new Rectangle(30, 258, (W - 72) / 2, 44);
    private final Rectangle quitButton = new Rectangle(42 + (W - 72) / 2, 258, (W - 72) / 2, 44);
    private Rectangle hovered;

    public SettingsMenu(Runnable onResume, Runnable onQuit, Runnable onDisplayChanged) {
        this.onResume = onResume;
        this.onQuit = onQuit;
        this.onDisplayChanged = onDisplayChanged;
        setOpaque(true);
        setPreferredSize(new Dimension(W, H));
        setSize(W, H);
        setVisible(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Rectangle over = fpsRow.contains(e.getPoint()) ? fpsRow
                        : fullscreenRow.contains(e.getPoint()) ? fullscreenRow
                        : resumeButton.contains(e.getPoint()) ? resumeButton
                        : quitButton.contains(e.getPoint()) ? quitButton : null;
                if (over != hovered) {
                    hovered = over;
                    setCursor(over != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = null;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) return;
                if (fpsRow.contains(e.getPoint())) {
                    GameSettings.setShowFps(!GameSettings.showFps());
                    repaint();
                } else if (fullscreenRow.contains(e.getPoint())) {
                    GameSettings.setFullscreen(!GameSettings.fullscreen());
                    onDisplayChanged.run();
                    repaint();
                } else if (resumeButton.contains(e.getPoint())) {
                    onResume.run();
                } else if (quitButton.contains(e.getPoint())) {
                    onQuit.run();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            HudStyle.smooth(g);
            g.setColor(new Color(6, 8, 12));
            g.fillRect(0, 0, W, H);
            HudStyle.panel(g, 0, 0, W, H, HudStyle.GLASS_SOLID);

            g.setFont(HudStyle.font(Font.BOLD, 24f));
            g.setColor(HudStyle.VALUE);
            g.drawString("Settings", 30, 52);
            HudStyle.label(g, "Esc to resume", W - 30 - HudStyle.labelWidth(g, "Esc to resume", 10f), 50, 10f, HudStyle.DIM);
            g.setColor(new Color(120, 205, 235, 70));
            g.fillRect(30, 70, W - 60, 1);

            paintToggle(g, fpsRow, "Show FPS counter", GameSettings.showFps(), "On", "Off");
            paintToggle(g, fullscreenRow, "Fullscreen", GameSettings.fullscreen(), "Fullscreen", "Windowed");

            paintButton(g, resumeButton, "Resume", true);
            paintButton(g, quitButton, "Quit game", false);
        } finally {
            g.dispose();
        }
    }

    /** A setting's row: its name, and a switch showing whether it's on (with a word for each). */
    private void paintToggle(Graphics2D g, Rectangle row, String name, boolean on, String onWord, String offWord) {
        {
            paintRowBackground(g, row);
            g.setFont(HudStyle.font(Font.PLAIN, 16f));
            g.setColor(HudStyle.VALUE);
            g.drawString(name, row.x + 16, row.y + 32);
            int sw = 52, sh = 26, sx = row.x + row.width - sw - 14, sy = row.y + (row.height - sh) / 2;
            g.setColor(on ? new Color(40, 150, 185) : new Color(55, 62, 72));
            g.fill(new RoundRectangle2D.Float(sx, sy, sw, sh, sh, sh));
            g.setColor(on ? HudStyle.ACCENT : new Color(110, 120, 132));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new RoundRectangle2D.Float(sx, sy, sw, sh, sh, sh));
            int knob = sh - 6;
            g.setColor(on ? Color.WHITE : new Color(185, 192, 200));
            g.fillOval(on ? sx + sw - knob - 3 : sx + 3, sy + 3, knob, knob);
            String word = on ? onWord : offWord;
            HudStyle.label(g, word, sx - 12 - HudStyle.labelWidth(g, word, 10f), sy + 17, 10f, on ? HudStyle.ACCENT : HudStyle.DIM);
        }
    }

    private void paintRowBackground(Graphics2D g, Rectangle r) {
        g.setColor(hovered == r ? new Color(255, 255, 255, 22) : new Color(255, 255, 255, 10));
        g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 10, 10));
    }

    private void paintButton(Graphics2D g, Rectangle r, String text, boolean primary) {
        boolean over = hovered == r;
        Color fill = primary ? (over ? new Color(55, 175, 210) : new Color(40, 145, 180))
                : (over ? new Color(150, 60, 55) : new Color(70, 76, 86));
        g.setColor(fill);
        g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 10, 10));
        g.setColor(new Color(255, 255, 255, 60));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 10, 10));
        g.setFont(HudStyle.font(Font.BOLD, 16f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Color.WHITE);
        g.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
    }
}
