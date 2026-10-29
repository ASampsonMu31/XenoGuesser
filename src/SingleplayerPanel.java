import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import javax.swing.JComponent;

/**
 * Play Singleplayer's panel, over the main menu (dimmed behind it), as the multiplayer panel is:
 * the game mode and map to play, chosen together from a grid of every pairing (a row for each
 * game mode, a column for each map), each square saying whether that game has been won yet (its
 * achievement, see Achievements.winFor). Start begins loading the world; Back (or Esc) goes back
 * to the menu, Enter starts.
 */
public class SingleplayerPanel extends JComponent {

    private static final float PANEL_W = 860f;
    // What each game mode is, under its name at the start of its row
    private static final String[] MODE_NOTES = { "Walk and jump about", "Stand still: lower targets" };

    private final Runnable onStart, onBack;
    // The grid's squares (by mode, then map), Start and Back, as last laid out; which the pointer's over
    private final Rectangle[][] squares = new Rectangle[GameMode.values().length][MapKind.values().length];
    private final Rectangle startButton = new Rectangle(), backButton = new Rectangle();
    private Rectangle hovered;
    private boolean finished;

    public SingleplayerPanel(Runnable onStart, Runnable onBack) {
        this.onStart = onStart;
        this.onBack = onBack;
        for (Rectangle[] row : squares) for (int m = 0; m < row.length; m++) row[m] = new Rectangle();
        setOpaque(false);
        setFocusable(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (e.getButton() != MouseEvent.BUTTON1 || finished) return;
                for (int mode = 0; mode < squares.length; mode++) {
                    for (int map = 0; map < squares[mode].length; map++) {
                        if (squares[mode][map].contains(e.getPoint())) {
                            GameMode.choose(GameMode.values()[mode]);
                            MapKind.choose(MapKind.values()[map]);
                            repaint();
                            return;
                        }
                    }
                }
                if (startButton.contains(e.getPoint())) start();
                else if (backButton.contains(e.getPoint())) back();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                Rectangle over = startButton.contains(e.getPoint()) ? startButton : backButton.contains(e.getPoint()) ? backButton : null;
                for (Rectangle[] row : squares) for (Rectangle r : row) if (r.contains(e.getPoint())) over = r;
                if (over != hovered) {
                    hovered = over;
                    repaint();
                }
                setCursor(over != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) back();
                else if (e.getKeyCode() == KeyEvent.VK_ENTER) start();
                // (nothing typed here reaches the window's own keys, such as Esc for the settings)
                e.consume();
            }
        });
    }

    /** Takes the keyboard (once the panel is on screen). */
    public void takeFocus() {
        requestFocusInWindow();
    }

    private void start() {
        if (finished) return;
        finished = true;
        onStart.run();
    }

    private void back() {
        if (finished) return;
        finished = true;
        onBack.run();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth(), h = getHeight();
        Graphics2D g = (Graphics2D) graphics.create();
        HudStyle.smooth(g);
        float unit = h / 1080f;
        float pw = Math.min(PANEL_W * unit, w - 32f), pad = 40 * unit, inner = pw - pad * 2;
        // The grid: the modes' names in a column at the left, then a square for each map
        float labelColumn = 220 * unit, gap = 12 * unit, squareH = 96 * unit, headRow = 30 * unit;
        int maps = MapKind.values().length, modes = GameMode.values().length;
        float squareW = (inner - labelColumn - gap * maps) / maps;
        float ph = pad + 14 * unit + 52 * unit + 50 * unit + 28 * unit + 30 * unit + headRow - 18 * unit + modes * (squareH + gap)
                + 26 * unit + 64 * unit + 18 * unit + 52 * unit + pad;
        float px = (w - pw) / 2f, py = Math.max(16f, (h - ph) / 2f);
        HudStyle.panel(g, px, py, pw, ph, HudStyle.GLASS_SOLID);

        float x = px + pad, y = py + pad + 14 * unit;
        HudStyle.label(g, "Singleplayer", x, y, 15 * unit, HudStyle.ACCENT);
        y += 52 * unit;
        g.setFont(HudStyle.font(Font.BOLD, 34 * unit));
        g.setColor(Color.WHITE);
        g.drawString("Choose your expedition", x, y);
        y += 50 * unit;
        g.setFont(HudStyle.font(Font.PLAIN, 19 * unit));
        g.setColor(new Color(205, 214, 226));
        g.drawString("The game mode and the map to play. A tick marks each you've won.", x, y);
        y += 28 * unit + 30 * unit;

        // The maps' names over their columns
        float gridX = x + labelColumn + gap;
        for (int map = 0; map < maps; map++) {
            String name = MapKind.values()[map].label + " map";
            HudStyle.label(g, name, gridX + map * (squareW + gap) + 2 * unit, y, 12 * unit, HudStyle.ACCENT);
        }
        y += headRow - 18 * unit;
        for (int mode = 0; mode < modes; mode++) {
            GameMode gameMode = GameMode.values()[mode];
            float rowY = y + mode * (squareH + gap);
            // The mode's name and what it is
            g.setFont(HudStyle.font(Font.BOLD, 22 * unit));
            g.setColor(Color.WHITE);
            g.drawString(gameMode.label, x, rowY + squareH * 0.45f);
            g.setFont(HudStyle.font(Font.PLAIN, 14 * unit));
            g.setColor(HudStyle.LABEL);
            g.drawString(mode < MODE_NOTES.length ? MODE_NOTES[mode] : "", x, rowY + squareH * 0.45f + 24 * unit);
            for (int map = 0; map < maps; map++) {
                Rectangle r = squares[mode][map];
                r.setBounds(Math.round(gridX + map * (squareW + gap)), Math.round(rowY), Math.round(squareW), Math.round(squareH));
                paintSquare(g, r, gameMode, MapKind.values()[map], unit);
            }
        }
        y += modes * (squareH + gap) + 26 * unit;

        startButton.setBounds(Math.round(x), Math.round(y), Math.round(inner), Math.round(64 * unit));
        HudStyle.button(g, startButton, "Start: " + Expedition.choice(), null, true, true, hovered == startButton, unit);
        y += (64 + 18) * unit;
        backButton.setBounds(Math.round(x), Math.round(y), Math.round(230 * unit), Math.round(52 * unit));
        HudStyle.button(g, backButton, "Back", null, true, false, hovered == backButton, unit);
        g.dispose();
    }

    /**
     * A pairing's square: lit when it's the one chosen, lighter under the pointer; the map's
     * name, and whether its game's been won (a gold tick and "Won") or not yet.
     */
    private void paintSquare(Graphics2D g, Rectangle r, GameMode mode, MapKind map, float unit) {
        boolean chosen = GameMode.current() == mode && MapKind.current() == map, over = hovered == r;
        boolean won = Achievements.isUnlocked(Achievements.winFor(mode, map));
        Path2D shape = HudStyle.chamfered(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 12 * unit);
        g.setColor(chosen ? new Color(24, 110, 145, 235) : over ? new Color(70, 80, 96, 235) : new Color(24, 30, 40, 220));
        g.fill(shape);
        g.setColor(chosen || over ? Color.WHITE : won ? new Color(245, 196, 80, 170) : HudStyle.EDGE);
        g.setStroke(new BasicStroke((chosen ? 2.2f : 1.4f) * Math.max(1f, unit)));
        g.draw(shape);
        float left = r.x + 18 * unit;
        g.setFont(HudStyle.font(Font.BOLD, 21 * unit));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Color.WHITE);
        g.drawString(map.label, left, r.y + 20 * unit + fm.getAscent());
        float statusY = r.y + r.height - 20 * unit;
        if (won) {
            // A gold tick before "Won"
            float box = 16 * unit, by = statusY - box + 2 * unit;
            g.setColor(HudStyle.GOLD);
            g.fill(new java.awt.geom.RoundRectangle2D.Float(left, by, box, box, 4 * unit, 4 * unit));
            g.setColor(new Color(30, 24, 10));
            g.setStroke(new BasicStroke(2f * Math.max(1f, unit), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D tick = new Path2D.Float();
            tick.moveTo(left + box * 0.22f, by + box * 0.52f);
            tick.lineTo(left + box * 0.43f, by + box * 0.74f);
            tick.lineTo(left + box * 0.8f, by + box * 0.28f);
            g.draw(tick);
            HudStyle.label(g, "Won", left + box + 9 * unit, statusY, 12 * unit, HudStyle.GOLD);
        } else {
            HudStyle.label(g, "Not won yet", left, statusY, 12 * unit, chosen ? new Color(200, 225, 240) : HudStyle.DIM);
        }
    }
}
