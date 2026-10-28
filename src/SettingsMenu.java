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

/**
 * The settings, opened with Esc during the game: the graphics (Low, Medium or High), the FPS
 * counter, fullscreen or windowed, and the sun's shadows; Resume, Return to menu (while a game is
 * loading or being played) and Quit game. In multiplayer, leaving either way first asks whether
 * the player's sure, as it forfeits the game.
 */
public class SettingsMenu extends JPanel {

    public static final int W = 440, H = 566;

    private final Runnable onResume;
    private final Runnable onQuit, onDisplayChanged, onReturnToMenu;
    // Whether there's a game to return to the menu from, and whether leaving it forfeits it (multiplayer)
    private final java.util.function.BooleanSupplier inGame, forfeits;
    // What's being asked about (leaving to the menu, or quitting), until answered; null when nothing is
    private Runnable confirming;
    private String confirmingWord;
    // Opens the controls (M48), as enclosed with January's message
    private Runnable onControls;
    // The graphics: a button for each of Low, Medium and High, along the right of their row
    private final Rectangle qualityRow = new Rectangle(30, 96, W - 60, 72);
    private final Rectangle[] qualityButtons = new Rectangle[GameSettings.Quality.values().length];
    // (the shadows straight under the graphics, as they go together)
    private final Rectangle shadowsRow = new Rectangle(30, 178, W - 60, 52);
    private final Rectangle diagnosticsRow = new Rectangle(30, 240, W - 60, 52);
    private final Rectangle fullscreenRow = new Rectangle(30, 302, W - 60, 52);
    private final Rectangle controlsRow = new Rectangle(30, 364, W - 60, 52);
    // The buttons: Resume and Quit side by side, or with a game on, Resume and Return to menu, Quit under them
    private final Rectangle resumeButton = new Rectangle(), returnButton = new Rectangle(), quitButton = new Rectangle();
    // The question's buttons, while one's being asked
    private final Rectangle confirmButton = new Rectangle(30, 452, (W - 72) / 2, 44);
    private final Rectangle cancelButton = new Rectangle(42 + (W - 72) / 2, 452, (W - 72) / 2, 44);
    {
        int bw = 74, gap = 6, right = qualityRow.x + qualityRow.width - 14;
        for (int k = 0; k < qualityButtons.length; k++) {
            int x = right - (qualityButtons.length - k) * bw - (qualityButtons.length - 1 - k) * gap;
            qualityButtons[k] = new Rectangle(x, qualityRow.y + 12, bw, 30);
        }
    }

    /** The graphics' button at a point, or null. */
    private Rectangle qualityButtonAt(java.awt.Point p) {
        for (Rectangle button : qualityButtons) if (button.contains(p)) return button;
        return null;
    }
    private Rectangle hovered;

    /** What the Controls row opens. */
    public void setOnControls(Runnable onControls) {
        this.onControls = onControls;
    }

    /**
     * onReturnToMenu leaves the game for the main menu, offered while inGame holds; leaving
     * either way asks first while forfeits holds.
     */
    public SettingsMenu(Runnable onResume, Runnable onQuit, Runnable onDisplayChanged, Runnable onReturnToMenu,
                        java.util.function.BooleanSupplier inGame, java.util.function.BooleanSupplier forfeits) {
        this.onResume = onResume;
        this.onQuit = onQuit;
        this.onDisplayChanged = onDisplayChanged;
        this.onReturnToMenu = onReturnToMenu;
        this.inGame = inGame;
        this.forfeits = forfeits;
        setOpaque(true);
        setPreferredSize(new Dimension(W, H));
        setSize(W, H);
        setVisible(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                layOutButtons();
                Rectangle over = confirming != null ? (confirmButton.contains(e.getPoint()) ? confirmButton
                        : cancelButton.contains(e.getPoint()) ? cancelButton : null)
                        : qualityButtonAt(e.getPoint()) != null ? qualityButtonAt(e.getPoint())
                        : diagnosticsRow.contains(e.getPoint()) ? diagnosticsRow
                        : fullscreenRow.contains(e.getPoint()) ? fullscreenRow
                        : shadowsRow.contains(e.getPoint()) ? shadowsRow
                        : controlsRow.contains(e.getPoint()) ? controlsRow
                        : resumeButton.contains(e.getPoint()) ? resumeButton
                        : returnButton.contains(e.getPoint()) ? returnButton
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
                layOutButtons();
                // (while a question's asked, only its answers do anything)
                if (confirming != null) {
                    Runnable answer = confirming;
                    if (confirmButton.contains(e.getPoint()) || cancelButton.contains(e.getPoint())) {
                        confirming = null;
                        repaint();
                    }
                    if (confirmButton.contains(e.getPoint())) answer.run();
                    return;
                }
                Rectangle quality = qualityButtonAt(e.getPoint());
                if (quality != null) {
                    GameSettings.setQuality(GameSettings.Quality.values()[java.util.Arrays.asList(qualityButtons).indexOf(quality)]);
                    repaint();
                } else if (diagnosticsRow.contains(e.getPoint())) {
                    GameSettings.setShowDiagnostics(!GameSettings.showDiagnostics());
                    repaint();
                } else if (fullscreenRow.contains(e.getPoint())) {
                    GameSettings.setFullscreen(!GameSettings.fullscreen());
                    onDisplayChanged.run();
                    repaint();
                } else if (shadowsRow.contains(e.getPoint())) {
                    GameSettings.setShadows(!GameSettings.shadows());
                    repaint();
                } else if (controlsRow.contains(e.getPoint())) {
                    if (onControls != null) onControls.run();
                } else if (resumeButton.contains(e.getPoint())) {
                    onResume.run();
                } else if (returnButton.contains(e.getPoint())) {
                    leave(onReturnToMenu, "Forfeit and leave");
                } else if (quitButton.contains(e.getPoint())) {
                    leave(onQuit, "Forfeit and quit");
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Leaves (to the menu, or quitting): at once, or in multiplayer once the player's said they're sure (confirm on the button that does it). */
    private void leave(Runnable action, String confirm) {
        if (!forfeits.getAsBoolean()) {
            action.run();
            return;
        }
        confirming = action;
        confirmingWord = confirm;
        hovered = null;
        repaint();
    }

    /** A question unanswered as the settings close is forgotten. */
    @Override
    public void setVisible(boolean visible) {
        if (!visible) confirming = null;
        super.setVisible(visible);
    }

    /** Where the buttons go: Resume and Quit side by side, or with a game on, Return to menu beside Resume and Quit under them. */
    private void layOutButtons() {
        int half = (W - 72) / 2;
        if (inGame.getAsBoolean()) {
            resumeButton.setBounds(30, 446, half, 44);
            returnButton.setBounds(42 + half, 446, half, 44);
            quitButton.setBounds(30, 500, W - 60, 44);
        } else {
            resumeButton.setBounds(30, 462, half, 44);
            returnButton.setBounds(0, 0, 0, 0);
            quitButton.setBounds(42 + half, 462, half, 44);
        }
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

            paintQuality(g);
            paintToggle(g, shadowsRow, "Shadows", GameSettings.shadows(), "On", "Off");
            paintToggle(g, diagnosticsRow, "Show diagnostics", GameSettings.showDiagnostics(), "On", "Off");
            paintToggle(g, fullscreenRow, "Fullscreen", GameSettings.fullscreen(), "Fullscreen", "Windowed");
            // Controls: opens them, an arrow at its right
            paintRowBackground(g, controlsRow);
            g.setFont(HudStyle.font(Font.PLAIN, 16f));
            g.setColor(HudStyle.VALUE);
            g.drawString("Controls", controlsRow.x + 16, controlsRow.y + 32);
            int ax = controlsRow.x + controlsRow.width - 26, ay = controlsRow.y + controlsRow.height / 2;
            g.setColor(hovered == controlsRow ? HudStyle.ACCENT : new Color(150, 160, 172));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(ax - 4, ay - 7, ax + 3, ay);
            g.drawLine(ax + 3, ay, ax - 4, ay + 7);

            layOutButtons();
            paintButton(g, resumeButton, "Resume", true);
            if (returnButton.width > 0) paintButton(g, returnButton, "Return to menu", false);
            paintButton(g, quitButton, "Quit game", false);
            if (confirming != null) paintQuestion(g);
        } finally {
            g.dispose();
        }
    }

    /** Are they sure: the settings dimmed, and over their foot the question and its two answers. */
    private void paintQuestion(Graphics2D g) {
        g.setColor(new Color(6, 8, 12, 200));
        g.fillRect(0, 0, W, H);
        int top = 340;
        g.setColor(new Color(24, 28, 36));
        g.fill(new RoundRectangle2D.Float(18, top, W - 36, H - top - 18, 14, 14));
        g.setColor(new Color(235, 140, 130));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new RoundRectangle2D.Float(18.5f, top + 0.5f, W - 37, H - top - 19, 14, 14));
        g.setFont(HudStyle.font(Font.BOLD, 22f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(HudStyle.VALUE);
        String ask = "Are you sure?";
        g.drawString(ask, (W - fm.stringWidth(ask)) / 2, top + 46);
        g.setFont(HudStyle.font(Font.PLAIN, 16f));
        fm = g.getFontMetrics();
        g.setColor(new Color(235, 140, 130));
        String warn = "This forfeits the game.";
        g.drawString(warn, (W - fm.stringWidth(warn)) / 2, top + 78);
        paintButton(g, confirmButton, confirmingWord, false);
        paintButton(g, cancelButton, "Cancel", true);
    }

    /** The graphics' row: its name, a button for each choice (the one chosen lit), and what changes when. */
    private void paintQuality(Graphics2D g) {
        paintRowBackground(g, qualityRow);
        g.setFont(HudStyle.font(Font.PLAIN, 16f));
        g.setColor(HudStyle.VALUE);
        g.drawString("Graphics", qualityRow.x + 16, qualityRow.y + 32);
        GameSettings.Quality chosen = GameSettings.quality();
        for (int k = 0; k < qualityButtons.length; k++) {
            Rectangle b = qualityButtons[k];
            boolean on = GameSettings.Quality.values()[k] == chosen;
            g.setColor(on ? new Color(40, 150, 185) : hovered == b ? new Color(80, 88, 100) : new Color(55, 62, 72));
            g.fill(new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, 8, 8));
            g.setColor(on ? HudStyle.ACCENT : new Color(110, 120, 132));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, 8, 8));
            String word = GameSettings.Quality.values()[k].label;
            g.setFont(HudStyle.font(on ? Font.BOLD : Font.PLAIN, 13f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(on ? Color.WHITE : new Color(185, 192, 200));
            g.drawString(word, b.x + (b.width - fm.stringWidth(word)) / 2, b.y + (b.height + fm.getAscent() - fm.getDescent()) / 2);
        }
        HudStyle.label(g, "View distance changes next round", qualityRow.x + 16, qualityRow.y + 60, 10f, HudStyle.DIM);
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
