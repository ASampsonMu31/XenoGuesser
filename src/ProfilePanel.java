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
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * The player's profile (M58), over the main menu (dimmed behind it) as Play Multiplayer's panel
 * is: their username, typed into a box (checked as it's typed, see NameCheck, with why it won't
 * do shown under it), and their suit's colour, one of a row of swatches. Asked for as the game
 * starts when no name has been chosen yet (and then it can't be put off), and opened from the
 * menu's profile button at any time after. Save keeps both (see Profile, which also has the
 * name said for Xenocorp's messages); Cancel or Esc leaves them as they were.
 */
public class ProfilePanel extends JComponent {

    private static final float PANEL_W = 640f;

    private final boolean first;
    private final Runnable onClose;
    private String typed;
    private SuitColour suit;
    private final long openedAt = System.currentTimeMillis();
    private boolean closed;

    // The buttons and swatches as last painted, each with what it does
    private final List<Rectangle> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private final List<Boolean> enabled = new ArrayList<>();
    private int hovered = -1;
    // (repaints for the caret's blink)
    private final Timer blink = new Timer(400, e -> repaint());

    /** first: the game's just started with no name chosen, so there's no Cancel; onClose runs once it's saved or cancelled. */
    public ProfilePanel(boolean first, Runnable onClose) {
        this.first = first;
        this.onClose = onClose;
        typed = Profile.hasName() ? Profile.name() : "";
        suit = Profile.suit();
        setOpaque(false);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (e.getButton() != MouseEvent.BUTTON1) return;
                int b = buttonAt(e.getX(), e.getY());
                if (b >= 0) actions.get(b).run();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int over = buttonAt(e.getX(), e.getY());
                if (over != hovered) {
                    hovered = over;
                    repaint();
                }
                setCursor(over >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_BACK_SPACE && !typed.isEmpty()) typed = typed.substring(0, typed.length() - 1);
                else if (code == KeyEvent.VK_ENTER) save();
                else if (code == KeyEvent.VK_ESCAPE && !first) close();
                repaint();
                // (nothing typed here reaches the window's own keys, such as Esc for the settings)
                e.consume();
            }

            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                boolean allowed = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-';
                if (allowed && !e.isControlDown() && typed.length() < NameCheck.LONGEST) {
                    typed += c;
                    repaint();
                }
                e.consume();
            }
        });
        blink.start();
    }

    /** Takes the keyboard (once the panel is on screen). */
    public void takeFocus() {
        requestFocusInWindow();
    }

    private void save() {
        if (NameCheck.problem(typed) != null) return;
        Profile.save(typed.trim(), suit);
        close();
    }

    private void close() {
        if (closed) return;
        closed = true;
        blink.stop();
        onClose.run();
    }

    private int buttonAt(int x, int y) {
        for (int b = 0; b < buttons.size(); b++) {
            if (enabled.get(b) && buttons.get(b).contains(x, y)) return b;
        }
        return -1;
    }

    /** Lays out a button or swatch (in the panel's coordinates; moved onto the screen after painting); whether the pointer is over it. */
    private boolean addButton(Rectangle r, boolean on, Runnable action) {
        buttons.add(r);
        enabled.add(on);
        actions.add(action);
        return buttons.size() - 1 == hovered;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth(), h = getHeight();
        Graphics2D g = (Graphics2D) graphics.create();
        HudStyle.smooth(g);
        float unit = h / 1080f;
        float pw = Math.min(PANEL_W * unit, w - 32f), pad = 40 * unit;
        // Laid out once on a scrap of image to measure it, then drawn centred on its panel
        java.awt.image.BufferedImage scrap = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D measure = scrap.createGraphics();
        HudStyle.smooth(measure);
        float ph = paintContents(measure, pw, pad, unit) + pad;
        measure.dispose();
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        HudStyle.panel(g, px, py, pw, ph, HudStyle.GLASS_SOLID);
        g.translate(px, py);
        paintContents(g, pw, pad, unit);
        for (Rectangle r : buttons) r.translate(Math.round(px), Math.round(py));
        g.dispose();
    }

    /** The panel's contents in its own coordinates (its top left at 0, 0, pw wide), laying out the buttons; returns the bottom. */
    private float paintContents(Graphics2D g, float pw, float pad, float unit) {
        buttons.clear();
        actions.clear();
        enabled.clear();
        float x = pad, y = pad + 14 * unit, inner = pw - pad * 2;
        HudStyle.label(g, "Xenocorp personnel record", x, y, 15 * unit, HudStyle.ACCENT);
        y += 52 * unit;
        g.setFont(HudStyle.font(Font.BOLD, 34 * unit));
        g.setColor(Color.WHITE);
        g.drawString(first ? "Choose your username" : "Your profile", x, y);
        y += 46 * unit;
        y = paragraph(g, first ? "Xenocorp no longer issues employee numbers. How should we address you? Your partner sees this name in multiplayer."
                : "Xenocorp will address you by this name, and your partner sees it and your suit in multiplayer.", x, y, inner, unit,
                new Color(205, 214, 226));

        // The name's box, with a blinking caret after what's typed
        y += 14 * unit;
        HudStyle.label(g, "Username", x, y, 12 * unit, HudStyle.LABEL);
        y += 12 * unit;
        float boxH = 64 * unit;
        String problem = typed.isEmpty() ? null : NameCheck.problem(typed);
        Path2D box = HudStyle.chamfered(x + 0.5f, y + 0.5f, inner - 1, boxH - 1, 10 * unit);
        g.setColor(new Color(10, 14, 22, 230));
        g.fill(box);
        g.setColor(problem != null ? new Color(245, 105, 90) : HudStyle.ACCENT);
        g.setStroke(new BasicStroke(2f * Math.max(1f, unit)));
        g.draw(box);
        g.setFont(HudStyle.font(Font.BOLD, 30 * unit));
        FontMetrics fm = g.getFontMetrics();
        float textY = y + (boxH + fm.getAscent() - fm.getDescent()) / 2f;
        g.setColor(Color.WHITE);
        g.drawString(typed, x + 18 * unit, textY);
        if ((System.currentTimeMillis() - openedAt) / 500 % 2 == 0) {
            float cx = x + 18 * unit + fm.stringWidth(typed) + 3 * unit;
            g.setColor(HudStyle.ACCENT);
            g.fill(new java.awt.geom.Rectangle2D.Float(cx, textY - fm.getAscent() * 0.8f, Math.max(2f, 3 * unit), fm.getAscent() * 0.9f));
        }
        String count = typed.length() + " / " + NameCheck.LONGEST;
        HudStyle.label(g, count, x + inner - HudStyle.labelWidth(g, count, 11 * unit) - 14 * unit, y + boxH - 10 * unit, 11 * unit, HudStyle.LABEL);
        y += boxH + 30 * unit;
        // Why it won't do, or what's allowed
        String note = problem != null ? problem : typed.isEmpty() ? "3 to 16 letters, numbers, _ or -." : "Approved by Human Resources.";
        y = paragraph(g, note, x, y, inner, unit, problem != null ? new Color(245, 105, 90)
                : typed.isEmpty() ? HudStyle.LABEL : new Color(110, 225, 130));

        // The suit's colour: a row of swatches, the chosen one ringed
        y += 12 * unit;
        HudStyle.label(g, "Suit colour: " + suit.label, x, y, 12 * unit, HudStyle.LABEL);
        y += 14 * unit;
        SuitColour[] all = SuitColour.values();
        float gap = 12 * unit, size = Math.min(54 * unit, (inner - gap * (all.length - 1)) / all.length);
        for (int i = 0; i < all.length; i++) {
            SuitColour c = all[i];
            float sx = x + i * (size + gap);
            Rectangle r = new Rectangle(Math.round(sx), Math.round(y), Math.round(size), Math.round(size));
            boolean over = addButton(r, true, () -> {
                suit = c;
                repaint();
            });
            float inset = 6 * unit;
            g.setColor(c.awt());
            g.fill(new Ellipse2D.Float(sx + inset, y + inset, size - inset * 2, size - inset * 2));
            if (c == suit || over) {
                g.setColor(c == suit ? Color.WHITE : new Color(255, 255, 255, 120));
                g.setStroke(new BasicStroke((c == suit ? 3f : 2f) * Math.max(1f, unit)));
                g.draw(new Ellipse2D.Float(sx + 1.5f * unit, y + 1.5f * unit, size - 3 * unit, size - 3 * unit));
            }
        }
        y += size + 34 * unit;

        // Save (once the name will do), and Cancel unless it's the first time
        Rectangle saveButton = new Rectangle(Math.round(x), Math.round(y), Math.round(inner), Math.round(64 * unit));
        boolean ok = !typed.isEmpty() && problem == null;
        HudStyle.button(g, saveButton, "Save", null, ok, true, addButton(saveButton, ok, this::save), unit);
        y += (64 + 18) * unit;
        if (!first) {
            Rectangle cancel = new Rectangle(Math.round(x), Math.round(y), Math.round(230 * unit), Math.round(52 * unit));
            HudStyle.button(g, cancel, "Cancel", null, true, false, addButton(cancel, true, this::close), unit);
            y += 52 * unit;
        }
        return y;
    }

    /** Text wrapped to width, from its first baseline at y; returns the baseline after it. */
    private float paragraph(Graphics2D g, String text, float x, float y, float width, float unit, Color colour) {
        g.setFont(HudStyle.font(Font.PLAIN, 19 * unit));
        g.setColor(colour);
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tried = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(tried) > width && line.length() > 0) {
                g.drawString(line.toString(), x, y);
                y += 28 * unit;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (line.length() > 0) {
            g.drawString(line.toString(), x, y);
            y += 28 * unit;
        }
        return y;
    }
}
