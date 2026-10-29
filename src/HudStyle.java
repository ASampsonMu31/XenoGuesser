import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

/**
 * The look of the heads-up display: a dark glass panel with cut corners and a thin cyan
 * edge, small spaced-out labels and bright figures. Every HUD element is painted here, so
 * the panels drawn over the 3D view and the Swing ones (the results screen, the settings)
 * match.
 */
public final class HudStyle {

    public static final Color GLASS = new Color(10, 14, 22, 196);
    public static final Color GLASS_SOLID = new Color(16, 21, 30);
    public static final Color EDGE = new Color(120, 205, 235, 150);
    public static final Color ACCENT = new Color(110, 215, 245);
    public static final Color LABEL = new Color(150, 168, 185);
    public static final Color VALUE = new Color(242, 246, 250);
    public static final Color GOLD = new Color(245, 196, 80);
    public static final Color DIM = new Color(105, 118, 132);

    private static final String FAMILY = pickFamily();

    private HudStyle() {
    }

    private static String pickFamily() {
        List<String> installed = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : new String[] { "Bahnschrift", "Segoe UI Semibold", "Segoe UI", "Helvetica Neue", "Arial" }) {
            if (installed.contains(family)) return family;
        }
        return Font.SANS_SERIF;
    }

    public static Font font(int style, float size) {
        return new Font(FAMILY, style, 1).deriveFont(style, size);
    }

    public static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** A transparent image of size w x h (in points) at the given pixel scale, ready to paint in points. */
    public static BufferedImage canvas(int w, int h, float scale, Graphics2D[] graphics) {
        BufferedImage image = new BufferedImage(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        smooth(g);
        g.scale(scale, scale);
        graphics[0] = g;
        return image;
    }

    /** A rectangle with its top-left and bottom-right corners cut off. */
    public static Path2D chamfered(float x, float y, float w, float h, float cut) {
        Path2D p = new Path2D.Float();
        p.moveTo(x + cut, y);
        p.lineTo(x + w, y);
        p.lineTo(x + w, y + h - cut);
        p.lineTo(x + w - cut, y + h);
        p.lineTo(x, y + h);
        p.lineTo(x, y + cut);
        p.closePath();
        return p;
    }

    /** The standard panel: glass body, a faint sheen at the top, a cyan edge and an accent bar down the left. */
    public static void panel(Graphics2D g, float x, float y, float w, float h, Color body) {
        float cut = Math.min(14f, h * 0.25f);
        Path2D shape = chamfered(x + 0.5f, y + 0.5f, w - 1f, h - 1f, cut);
        g.setColor(body);
        g.fill(shape);
        g.setPaint(new GradientPaint(0, y, new Color(255, 255, 255, 22), 0, y + h * 0.5f, new Color(255, 255, 255, 0)));
        g.fill(shape);
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(shape);
        g.setColor(ACCENT);
        g.fill(new java.awt.geom.Rectangle2D.Float(x + 3f, y + cut + 4f, 2.5f, Math.max(0f, h - cut - 12f)));
    }

    /** A panel's edge drawn over again, thick, in colour (e.g. green for a good outcome, red for a bad one). */
    public static void edge(Graphics2D g, float x, float y, float w, float h, Color colour) {
        float cut = Math.min(14f, h * 0.25f);
        Path2D shape = chamfered(x + 0.5f, y + 0.5f, w - 1f, h - 1f, cut);
        g.setColor(colour);
        g.setStroke(new BasicStroke(4f));
        g.draw(shape);
        g.fill(new java.awt.geom.Rectangle2D.Float(x + 3f, y + cut + 4f, 3.5f, Math.max(0f, h - cut - 12f)));
    }

    public static final int TIMER_W = 330, TIMER_H = 96;

    /**
     * The round's clock (M41, M44): a ring running out anticlockwise to nothing (cyan, gold under
     * a third, red under a sixth) with the time left inside it ("0:42"), and to its right what
     * it's for (note: "Time to guess", or that the partner has guessed).
     */
    public static void paintTimer(Graphics2D g, long msLeft, long msTotal, String note) {
        panel(g, 0, 0, TIMER_W, TIMER_H, GLASS);
        float share = msTotal <= 0L ? 0f : Math.max(0f, Math.min(1f, msLeft / (float) msTotal));
        Color run = share > 1f / 3f ? ACCENT : share > 1f / 6f ? GOLD : new Color(245, 95, 80);
        float cx = 54f, cy = TIMER_H * 0.5f, r = 36f;
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 34));
        g.draw(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));
        if (share > 0f) {
            g.setColor(run);
            g.draw(new java.awt.geom.Arc2D.Float(cx - r, cy - r, r * 2f, r * 2f, 90f, 360f * share, java.awt.geom.Arc2D.OPEN));
        }
        int seconds = (int) Math.ceil(msLeft / 1000.0);
        g.setFont(font(Font.BOLD, 24f));
        FontMetrics fm = g.getFontMetrics();
        String s = String.format("%d:%02d", seconds / 60, seconds % 60);
        g.setColor(share > 1f / 6f ? VALUE : run);
        g.drawString(s, cx - fm.stringWidth(s) / 2f, cy + (fm.getAscent() - fm.getDescent()) / 2f);
        // (as big as fits beside the ring)
        float tx = cx + r + 20f, room = TIMER_W - tx - 18f, size = 19f;
        while (size > 10f && labelWidth(g, note, size) > room) size -= 0.5f;
        label(g, note, tx, cy + size * 0.36f, size, note.equals("Time to guess") ? LABEL : run);
    }

    /** Small spaced-out capitals. */
    public static void label(Graphics2D g, String text, float x, float baseline, float size, Color colour) {
        Font f = font(Font.BOLD, size);
        g.setFont(f.deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.12f)));
        g.setColor(colour);
        g.drawString(text.toUpperCase(), x, baseline);
    }

    public static float labelWidth(Graphics2D g, String text, float size) {
        Font f = font(Font.BOLD, size).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.12f));
        return (float) f.getStringBounds(text.toUpperCase(), g.getFontRenderContext()).getWidth();
    }

    /**
     * The menus' button (the main menu's and the multiplayer panel's): cut corners, filled cyan
     * when it's the primary one (with an arrow at its right-hand end), dark otherwise, lighter
     * while the pointer is over it, greyed when not enabled; an optional small note at its right.
     * unit is the screen's scale (its height / 1080).
     */
    public static void button(Graphics2D g, java.awt.Rectangle r, String text, String note, boolean enabled, boolean primary,
                              boolean hovered, float unit) {
        boolean over = enabled && hovered;
        Path2D shape = chamfered(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 12 * unit);
        Color fill = !enabled ? new Color(40, 44, 52, 170)
                : primary ? (over ? new Color(45, 165, 205, 240) : new Color(24, 110, 145, 225))
                : (over ? new Color(70, 80, 96, 235) : new Color(24, 30, 40, 210));
        g.setColor(fill);
        g.fill(shape);
        g.setColor(!enabled ? new Color(120, 130, 140, 110) : over ? Color.WHITE : EDGE);
        g.setStroke(new BasicStroke(1.4f * Math.max(1f, unit)));
        g.draw(shape);
        g.setFont(font(Font.BOLD, r.height * 0.36f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(enabled ? Color.WHITE : new Color(150, 156, 166));
        float tx = r.x + 26 * unit;
        float ty = r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2f;
        g.drawString(text, tx, ty);
        if (note != null) {
            float noteSize = 11 * unit;
            label(g, note, r.x + r.width - 22 * unit - labelWidth(g, note, noteSize), ty - 2 * unit, noteSize,
                    new Color(150, 156, 166));
        }
        if (enabled && primary) {
            // An arrow at the right-hand end
            float ax = r.x + r.width - 34 * unit, cy = r.y + r.height / 2f, s = r.height * 0.14f;
            Path2D tri = new Path2D.Float();
            tri.moveTo(ax, cy - s);
            tri.lineTo(ax + s * 1.2f, cy);
            tri.lineTo(ax, cy + s);
            tri.closePath();
            g.setColor(Color.WHITE);
            g.fill(tri);
        }
    }

    /** A key cap with its letter, e.g. for "press M". Returns its width. */
    public static float keyCap(Graphics2D g, String key, float x, float y, float size) {
        Font f = font(Font.BOLD, size * 0.58f);
        FontMetrics fm = g.getFontMetrics(f);
        float w = Math.max(size, fm.stringWidth(key) + size * 0.55f);
        java.awt.geom.RoundRectangle2D cap = new java.awt.geom.RoundRectangle2D.Float(x, y, w, size, 5f, 5f);
        g.setPaint(new GradientPaint(0, y, new Color(62, 74, 90), 0, y + size, new Color(30, 37, 48)));
        g.fill(cap);
        g.setColor(new Color(160, 200, 225, 170));
        g.setStroke(new BasicStroke(1f));
        g.draw(cap);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new java.awt.geom.Rectangle2D.Float(x + 2f, y + size - 3f, w - 4f, 1.5f));
        g.setFont(f);
        g.setColor(VALUE);
        g.drawString(key, x + (w - fm.stringWidth(key)) / 2f, y + (size + fm.getAscent() - fm.getDescent()) / 2f - 0.5f);
        return w;
    }

    // ------------------------------------------------------------------ Score panel

    public static final int SCORE_W = 530, SCORE_H = 84;
    /** Where the round's points fly to at the end of the results (the productivity), relative to the panel. */
    public static final int SCORE_TARGET_X = 240, SCORE_TARGET_Y = 50;
    /** Gap between the HUD panels and the edges of the screen. */
    public static final int HUD_MARGIN = 18;
    // Each quarter's colour: light blue, green, yellow, pink
    public static final Color[] QUARTER_COLOURS = {
        new Color(120, 200, 255), new Color(110, 215, 125), new Color(250, 215, 70), new Color(250, 145, 200)
    };
    private static final String[] MONTHS = { "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC" };

    /**
     * The round counter (the quarter's three months as rounded tabs: this one in the quarter's
     * colour, those gone by in it greyed, those to come dim), the productivity against the
     * quarter's target (green once reached), and the cash.
     */
    public static void paintScorePanel(Graphics2D g, int quarter, int month, int productivity, int target, int cash, Color body) {
        panel(g, 0, 0, SCORE_W, SCORE_H, body);
        Color colour = QUARTER_COLOURS[quarter];
        label(g, "Quarter " + (quarter + 1), 20, 28, 11f, LABEL);
        float tabW = 40f, tabH = 26f, gap = 6f, tabY = 40f;
        Font monthFont = font(Font.BOLD, 12f);
        for (int m = 0; m < 3; m++) {
            float x = 20f + m * (tabW + gap);
            java.awt.geom.RoundRectangle2D tab = new java.awt.geom.RoundRectangle2D.Float(x, tabY, tabW, tabH, 12f, 12f);
            Color fill = m < month ? greyed(colour) : m == month ? colour : new Color(255, 255, 255, 18);
            g.setColor(fill);
            g.fill(tab);
            g.setStroke(new BasicStroke(1.2f));
            g.setColor(m > month ? new Color(120, 205, 235, 90) : new Color(255, 255, 255, 150));
            g.draw(tab);
            String name = MONTHS[quarter * 3 + m];
            g.setFont(monthFont);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(m > month ? DIM : new Color(20, 24, 32));
            g.drawString(name, x + (tabW - fm.stringWidth(name)) / 2f, tabY + (tabH + fm.getAscent() - fm.getDescent()) / 2f);
        }
        // Dividers
        g.setColor(new Color(120, 205, 235, 70));
        g.fill(new java.awt.geom.Rectangle2D.Float(168, 18, 1f, SCORE_H - 36));
        g.fill(new java.awt.geom.Rectangle2D.Float(392, 18, 1f, SCORE_H - 36));
        // Productivity over the quarter's target (the points large, green once the target's reached)
        label(g, "Productivity", 184, 28, 11f, LABEL);
        String points = String.format("%,d", productivity), of = String.format(" / %,d", target);
        float size = 30f;
        while (size > 14f && g.getFontMetrics(font(Font.BOLD, size)).stringWidth(points)
                + g.getFontMetrics(font(Font.PLAIN, size * 0.5f)).stringWidth(of) > 392 - 184 - 12) size -= 1f;
        g.setFont(font(Font.BOLD, size));
        g.setColor(productivity >= target ? new Color(110, 225, 130) : VALUE);
        g.drawString(points, 184, 64);
        float pw = g.getFontMetrics().stringWidth(points);
        g.setFont(font(Font.PLAIN, size * 0.5f));
        g.setColor(GOLD);
        g.drawString(of, 186 + pw, 64);
        // Cash, in dollars (spent in the shop), smaller should it grow too wide
        label(g, "Cash", 408, 28, 11f, LABEL);
        money(g, cash, 408, SCORE_W - 408 - 14, VALUE);
    }

    /** Multiplayer: where the rounds-won circles are on the score panel (each row's first circle's middle), and their size. */
    public static final int CIRCLES_X = 318, CIRCLES_Y = 38, CIRCLE_ROW = 26, CIRCLE_STEP = 20, CIRCLE_SIZE = 15;

    /**
     * The score panel in multiplayer (M58): the round being played, and each player (this one
     * first) with a row of ROUNDS_TO_WIN circles, filled in their suit's
     * colour for each round they've won. No cash: multiplayer's shop is free (the round's loser
     * takes one).
     */
    public static void paintMultiplayerScorePanel(Graphics2D g, int round, String[] names, Color[] suits, Color[] textColours,
                                                  int[] won, Color body) {
        panel(g, 0, 0, SCORE_W, SCORE_H, body);
        label(g, "Round", 20, 28, 11f, LABEL);
        g.setFont(font(Font.BOLD, 30f));
        g.setColor(VALUE);
        g.drawString(Integer.toString(round), 20, 64);
        label(g, "First to " + MultiplayerScore.ROUNDS_TO_WIN, 72, 64, 10f, GOLD);
        g.setColor(new Color(120, 205, 235, 70));
        g.fill(new java.awt.geom.Rectangle2D.Float(150, 18, 1f, SCORE_H - 36));
        float nameRight = CIRCLES_X - CIRCLE_SIZE / 2f - 12f;
        for (int p = 0; p < 2; p++) {
            float cy = CIRCLES_Y + p * CIRCLE_ROW;
            // The name as typed, cut short should it not fit before the circles
            g.setFont(font(Font.BOLD, 14f));
            FontMetrics fm = g.getFontMetrics();
            String name = names[p];
            boolean cut = false;
            while (name.length() > 1 && fm.stringWidth(name + (cut ? "..." : "")) > nameRight - 166f) {
                name = name.substring(0, name.length() - 1);
                cut = true;
            }
            g.setColor(textColours[p]);
            g.drawString(cut ? name + "..." : name, 166, cy + 5f);
            g.setStroke(new BasicStroke(1.6f));
            for (int c = 0; c < MultiplayerScore.ROUNDS_TO_WIN; c++) {
                float x = CIRCLES_X + c * CIRCLE_STEP - CIRCLE_SIZE / 2f, y = cy - CIRCLE_SIZE / 2f;
                java.awt.geom.Ellipse2D ring = new java.awt.geom.Ellipse2D.Float(x, y, CIRCLE_SIZE, CIRCLE_SIZE);
                if (c < won[p]) {
                    g.setColor(suits[p]);
                    g.fill(ring);
                    g.setColor(new Color(255, 255, 255, 190));
                } else {
                    g.setColor(new Color(255, 255, 255, 14));
                    g.fill(ring);
                    g.setColor(new Color(suits[p].getRed(), suits[p].getGreen(), suits[p].getBlue(), 170));
                }
                g.draw(ring);
            }
        }
    }

    /** A colour faded halfway towards grey and a little darker, for months gone by. */
    private static Color greyed(Color c) {
        float grey = (c.getRed() + c.getGreen() + c.getBlue()) / 3f;
        return new Color(Math.round((c.getRed() + grey) * 0.5f * 0.8f), Math.round((c.getGreen() + grey) * 0.5f * 0.8f),
                Math.round((c.getBlue() + grey) * 0.5f * 0.8f));
    }

    private static void money(Graphics2D g, int dollars, float x, float width, Color colour) {
        String text = String.format("$%,d", dollars);
        float size = 30f;
        while (size > 14f && g.getFontMetrics(font(Font.BOLD, size)).stringWidth(text) > width) size -= 1f;
        g.setFont(font(Font.BOLD, size));
        g.setColor(colour);
        g.drawString(text, x, 64);
    }

    // ------------------------------------------------------------------ Diagnostics

    public static final int DIAGNOSTICS_W = 300, DIAGNOSTICS_H = 86;
    private static final float GB = 1024f * 1024f * 1024f;
    // The memory bar's parts: the game's heap in use, the rest of what the game holds, everything
    // else's, and the page file holding what's committed past the RAM (red once it's half full)
    private static final Color HEAP_USED = new Color(110, 215, 245), GAME_REST = new Color(50, 125, 158),
            OTHERS = new Color(150, 168, 185, 190), PAGED = GOLD, PAGED_HIGH = new Color(245, 105, 90);

    /**
     * The frame rate, and a bar of the PC's memory across: its RAM (the game's, the heap's in use
     * at the start of it, then everything else's, then what's free) and, after a gap, its page
     * file (to scale), filling as more is committed than the RAM can hold. Under the bar what
     * each part comes to, and the heap in use of its budget (see MemoryBudget).
     */
    public static void paintDiagnostics(Graphics2D g, int fps, MemoryBudget.Reading memory) {
        int w = DIAGNOSTICS_W;
        panel(g, 0, 0, w, DIAGNOSTICS_H, GLASS);

        // The frame rate, at the right as it always was
        String n = Integer.toString(fps);
        Font big = font(Font.BOLD, 16f);
        FontMetrics fm = g.getFontMetrics(big);
        float labelW = labelWidth(g, "fps", 10f);
        float x = w - 14 - labelW - 5 - fm.stringWidth(n);
        g.setFont(big);
        g.setColor(fps >= 45 ? new Color(120, 235, 140) : fps >= 25 ? GOLD : new Color(245, 105, 90));
        g.drawString(n, x, 22);
        label(g, "fps", w - 14 - labelW, 22, 10f, VALUE);

        // (gold when the PC's short and the heap's held tight: see MemoryBudget)
        boolean squeezed = memory != null && memory.squeezed();
        label(g, squeezed ? "Memory short" : "Memory", 14, 22, 10f, squeezed ? GOLD : LABEL);
        boolean known = memory != null && memory.total() > 0L;

        float bx = 14f, by = 32f, bw = w - 28f, bh = 12f, gap = 3f;
        long pageFile = known ? memory.pageFile() : 0L;
        float ramW = known && pageFile > 0L ? (bw - gap) * memory.total() / (float) (memory.total() + pageFile) : bw;
        java.awt.Shape clip = g.getClip();
        java.awt.geom.RoundRectangle2D ram = new java.awt.geom.RoundRectangle2D.Float(bx, by, ramW, bh, 6f, 6f);
        g.setColor(new Color(255, 255, 255, 34));
        g.fill(ram);
        if (known) {
            g.clip(ram);
            float perByte = ramW / memory.total();
            float heapW = memory.heapUsed() * perByte, gameW = Math.min(ramW, memory.game() * perByte);
            float inUse = Math.min(ramW, Math.max(0L, memory.total() - memory.free()) * perByte);
            g.setColor(GAME_REST);
            g.fill(new java.awt.geom.Rectangle2D.Float(bx, by, gameW, bh));
            g.setColor(HEAP_USED);
            g.fill(new java.awt.geom.Rectangle2D.Float(bx, by, Math.min(heapW, gameW), bh));
            if (inUse > gameW) {
                g.setColor(OTHERS);
                g.fill(new java.awt.geom.Rectangle2D.Float(bx + gameW, by, inUse - gameW, bh));
            }
            g.setClip(clip);
        }
        float pageShare = 0f;
        if (pageFile > 0L) {
            java.awt.geom.RoundRectangle2D page = new java.awt.geom.RoundRectangle2D.Float(bx + ramW + gap, by, bw - ramW - gap, bh, 6f, 6f);
            g.setColor(new Color(255, 255, 255, 18));
            g.fill(page);
            pageShare = Math.min(1f, memory.pastRam() / (float) pageFile);
            if (pageShare > 0f) {
                g.clip(page);
                g.setColor(pageShare > 0.5f ? PAGED_HIGH : PAGED);
                g.fill(new java.awt.geom.Rectangle2D.Float(bx + ramW + gap, by, (bw - ramW - gap) * pageShare, bh));
                g.setClip(clip);
            }
        }

        if (!known) {
            label(g, "The PC's memory can't be read", 14, 62, 9.5f, DIM);
            return;
        }
        float lx = key(g, 14f, 62f, HEAP_USED, String.format(java.util.Locale.ROOT, "Game %.1f GB", memory.game() / GB));
        lx = key(g, lx + 10f, 62f, OTHERS, String.format(java.util.Locale.ROOT, "Other %.1f",
                Math.max(0L, memory.total() - memory.free() - memory.game()) / GB));
        key(g, lx + 10f, 62f, new Color(255, 255, 255, 60), String.format(java.util.Locale.ROOT, "Free %.1f", memory.free() / GB));
        key(g, 14f, 78f, pageShare > 0.5f ? PAGED_HIGH : PAGED, pageFile > 0L
                ? String.format(java.util.Locale.ROOT, "Page file %.1f of %.1f", memory.pastRam() / GB, pageFile / GB)
                : "No page file");
        String heap = String.format(java.util.Locale.ROOT, "Heap %.1f of %.1f", memory.heapUsed() / GB, MemoryBudget.budget() / GB);
        label(g, heap, w - 14 - labelWidth(g, heap, 9.5f), 78f, 9.5f, DIM);
    }

    /** A small square of the colour and what it stands for, from x; where the text ends. */
    private static float key(Graphics2D g, float x, float baseline, Color colour, String text) {
        g.setColor(colour);
        g.fill(new java.awt.geom.RoundRectangle2D.Float(x, baseline - 8f, 8f, 8f, 3f, 3f));
        label(g, text, x + 12f, baseline, 9.5f, VALUE);
        return x + 12f + labelWidth(g, text, 9.5f);
    }

    public static final int STAMINA_W = 220, STAMINA_H = 40;
    // The stamina bar's colour: pink when full, deepening to red as it runs out (and while winded)
    private static final Color STAMINA_FULL = new Color(250, 145, 200), STAMINA_LOW = new Color(245, 80, 95);

    /** The sprint's stamina (share, 0 to 1) as a bar; winded greys its label until it's usable again. */
    public static void paintStamina(Graphics2D g, float share, boolean winded) {
        share = Math.max(0f, Math.min(1f, share));
        panel(g, 0, 0, STAMINA_W, STAMINA_H, GLASS);
        label(g, "Stamina", 14, 25, 10f, winded ? DIM : LABEL);
        float bx = 84f, by = 15f, bw = STAMINA_W - bx - 14f, bh = 10f;
        g.setColor(new Color(255, 255, 255, 34));
        g.fill(new java.awt.geom.RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
        if (share > 0f) {
            // (pink down to half, then towards red)
            float t = winded ? 1f : Math.max(0f, Math.min(1f, (0.5f - share) / 0.5f));
            Color c = new Color(Math.round(STAMINA_FULL.getRed() + (STAMINA_LOW.getRed() - STAMINA_FULL.getRed()) * t),
                    Math.round(STAMINA_FULL.getGreen() + (STAMINA_LOW.getGreen() - STAMINA_FULL.getGreen()) * t),
                    Math.round(STAMINA_FULL.getBlue() + (STAMINA_LOW.getBlue() - STAMINA_FULL.getBlue()) * t));
            g.setColor(c);
            g.fill(new java.awt.geom.RoundRectangle2D.Float(bx, by, Math.max(bh, bw * share), bh, bh, bh));
        }
    }

    // ------------------------------------------------------------------ Inventory

    /** Something the player carries: its name and a way to draw its icon. */
    public record Item(String name, IconPainter icon) {
    }

    @FunctionalInterface
    public interface IconPainter {
        void paint(Graphics2D g, float x, float y, float size);
    }

    public static final int INVENTORY_W = 200;
    // The height of the heading, and of each item's row under it
    private static final int INVENTORY_HEAD = 28, INVENTORY_ROW = 40;

    public static int inventoryHeight(int items) {
        return INVENTORY_HEAD + items * INVENTORY_ROW + 6;
    }

    /** The inventory, the item in hand (its place in items, or -1 for none) lit up. */
    public static void paintInventory(Graphics2D g, List<Item> items, int held) {
        int h = inventoryHeight(items.size());
        panel(g, 0, 0, INVENTORY_W, h, GLASS);
        label(g, "Inventory", 16, 19, 10f, LABEL);
        g.setColor(new Color(120, 205, 235, 60));
        g.fill(new java.awt.geom.Rectangle2D.Float(16, 25, INVENTORY_W - 32, 1f));
        float y = INVENTORY_HEAD + 2;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            boolean inHand = i == held;
            // The one in hand on a lit band across the panel
            if (inHand) {
                java.awt.geom.RoundRectangle2D band = new java.awt.geom.RoundRectangle2D.Float(10, y, INVENTORY_W - 20, INVENTORY_ROW - 2, 9, 9);
                g.setColor(new Color(110, 215, 245, 40));
                g.fill(band);
                g.setColor(new Color(110, 215, 245, 150));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(band);
            }
            // Icon in its own slot
            java.awt.geom.RoundRectangle2D slot = new java.awt.geom.RoundRectangle2D.Float(16, y + 3, 32, 32, 7, 7);
            g.setColor(new Color(255, 255, 255, inHand ? 30 : 14));
            g.fill(slot);
            g.setColor(inHand ? ACCENT : new Color(120, 205, 235, 90));
            g.setStroke(new BasicStroke(inHand ? 1.6f : 1f));
            g.draw(slot);
            item.icon().paint(g, 18, y + 5, 28);
            g.setFont(font(Font.BOLD, 14f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(inHand ? VALUE : new Color(200, 210, 220));
            g.drawString(item.name(), 58, y + 19 + (fm.getAscent() - fm.getDescent()) / 2f);
            y += INVENTORY_ROW;
        }
    }

    /** A glass thermometer: a tube with a red bulb and its red column part way up, against a little scale. */
    public static void paintThermometerIcon(Graphics2D g, float x, float y, float size) {
        float cx = x + size / 2f;
        float tubeW = size * 0.16f, top = y + size * 0.06f, bulbR = size * 0.15f, bulbY = y + size - bulbR - size * 0.04f;
        // Ticks beside it
        g.setColor(new Color(210, 225, 235, 180));
        g.setStroke(new BasicStroke(size * 0.03f));
        for (int i = 0; i < 6; i++) {
            float ty = top + size * 0.08f + i * size * 0.11f;
            float len = i % 2 == 0 ? size * 0.16f : size * 0.1f;
            g.draw(new java.awt.geom.Line2D.Float(cx + tubeW * 0.9f, ty, cx + tubeW * 0.9f + len, ty));
        }
        // The glass
        java.awt.geom.RoundRectangle2D tube = new java.awt.geom.RoundRectangle2D.Float(cx - tubeW / 2f, top, tubeW, bulbY - top, tubeW, tubeW);
        g.setColor(new Color(225, 238, 245, 200));
        g.fill(tube);
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - bulbR, bulbY - bulbR, bulbR * 2, bulbR * 2));
        // The red column and bulb
        g.setColor(new Color(215, 35, 30));
        float inner = tubeW * 0.5f, level = top + size * 0.32f;
        g.fill(new java.awt.geom.Rectangle2D.Float(cx - inner / 2f, level, inner, bulbY - level));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - bulbR * 0.75f, bulbY - bulbR * 0.75f, bulbR * 1.5f, bulbR * 1.5f));
        g.setColor(new Color(255, 255, 255, 120));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - bulbR * 0.45f, bulbY - bulbR * 0.55f, bulbR * 0.4f, bulbR * 0.4f));
    }

    /** A brass pocket compass seen from above, needle pointing up. */
    public static void paintCompassIcon(Graphics2D g, float x, float y, float size) {
        float cx = x + size / 2f, cy = y + size / 2f, r = size * 0.46f;
        g.setPaint(new GradientPaint(x, y, new Color(240, 200, 110), x + size, y + size, new Color(150, 100, 35)));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        // The ring that hangs it from a chain
        g.setStroke(new BasicStroke(size * 0.06f));
        g.setColor(new Color(215, 170, 80));
        g.draw(new java.awt.geom.Ellipse2D.Float(cx - r * 0.18f, cy - r * 1.22f, r * 0.36f, r * 0.3f));
        float f = r * 0.78f;
        g.setColor(new Color(246, 240, 222));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - f, cy - f, f * 2, f * 2));
        g.setColor(new Color(60, 55, 50));
        g.setStroke(new BasicStroke(size * 0.03f));
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float inner = i % 2 == 0 ? f * 0.72f : f * 0.84f;
            g.draw(new java.awt.geom.Line2D.Float(cx + (float) Math.sin(a) * inner, cy - (float) Math.cos(a) * inner,
                    cx + (float) Math.sin(a) * f * 0.95f, cy - (float) Math.cos(a) * f * 0.95f));
        }
        float nw = f * 0.2f, nl = f * 0.72f;
        Path2D north = new Path2D.Float();
        north.moveTo(cx, cy - nl);
        north.lineTo(cx + nw, cy);
        north.lineTo(cx - nw, cy);
        north.closePath();
        g.setColor(new Color(210, 45, 40));
        g.fill(north);
        Path2D south = new Path2D.Float();
        south.moveTo(cx, cy + nl);
        south.lineTo(cx + nw, cy);
        south.lineTo(cx - nw, cy);
        south.closePath();
        g.setColor(new Color(90, 95, 105));
        g.fill(south);
        g.setColor(new Color(200, 160, 70));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - size * 0.05f, cy - size * 0.05f, size * 0.1f, size * 0.1f));
    }

    /** A blue altimeter seen from above: a cream dial with ticks round three quarters of it and a red pointer. */
    public static void paintAltimeterIcon(Graphics2D g, float x, float y, float size) {
        float cx = x + size / 2f, cy = y + size / 2f, r = size * 0.46f;
        g.setPaint(new GradientPaint(x, y, new Color(70, 120, 220), x + size, y + size, new Color(20, 50, 130)));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        float f = r * 0.76f;
        g.setColor(new Color(246, 240, 222));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - f, cy - f, f * 2, f * 2));
        g.setColor(new Color(40, 42, 50));
        g.setStroke(new BasicStroke(size * 0.028f));
        for (int i = 0; i <= 9; i++) {
            double a = Math.toRadians(-135 + 30 * i);
            float inner = i % 3 == 0 ? f * 0.66f : f * 0.8f;
            g.draw(new java.awt.geom.Line2D.Float(cx + (float) Math.sin(a) * inner, cy - (float) Math.cos(a) * inner,
                    cx + (float) Math.sin(a) * f * 0.94f, cy - (float) Math.cos(a) * f * 0.94f));
        }
        double a = Math.toRadians(40);
        g.setColor(new Color(210, 45, 40));
        g.setStroke(new BasicStroke(size * 0.05f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Float(cx, cy, cx + (float) Math.sin(a) * f * 0.75f, cy - (float) Math.cos(a) * f * 0.75f));
        g.setColor(new Color(200, 160, 70));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - size * 0.05f, cy - size * 0.05f, size * 0.1f, size * 0.1f));
    }

    /** An open brown book with a magnifying glass over it. */
    public static void paintKitIcon(Graphics2D g, float x, float y, float size) {
        // The book, open: a brown cover, two cream pages with lines of writing
        float bx = x + size * 0.04f, by = y + size * 0.42f, bw = size * 0.78f, bh = size * 0.5f;
        g.setColor(new Color(110, 62, 30));
        g.fill(new java.awt.geom.RoundRectangle2D.Float(bx, by, bw, bh, size * 0.06f, size * 0.06f));
        g.setColor(new Color(240, 228, 198));
        g.fill(new java.awt.geom.Rectangle2D.Float(bx + size * 0.04f, by + size * 0.04f, bw * 0.5f - size * 0.05f, bh - size * 0.08f));
        g.fill(new java.awt.geom.Rectangle2D.Float(bx + bw * 0.5f + size * 0.01f, by + size * 0.04f, bw * 0.5f - size * 0.05f, bh - size * 0.08f));
        g.setColor(new Color(120, 90, 60));
        g.setStroke(new BasicStroke(size * 0.022f));
        for (int i = 0; i < 4; i++) {
            float ly = by + size * 0.1f + i * size * 0.08f;
            g.draw(new java.awt.geom.Line2D.Float(bx + size * 0.08f, ly, bx + bw * 0.5f - size * 0.05f, ly));
            g.draw(new java.awt.geom.Line2D.Float(bx + bw * 0.5f + size * 0.05f, ly, bx + bw - size * 0.08f, ly));
        }
        // The magnifying glass: a brass ring with a pale lens and a wooden handle
        float lx = x + size * 0.6f, ly = y + size * 0.32f, lr = size * 0.24f;
        g.setColor(new Color(90, 52, 24));
        g.setStroke(new BasicStroke(size * 0.09f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Float(lx - lr * 0.75f, ly + lr * 0.75f, lx - lr * 1.75f, ly + lr * 1.75f));
        g.setColor(new Color(190, 225, 240, 170));
        g.fill(new java.awt.geom.Ellipse2D.Float(lx - lr, ly - lr, lr * 2, lr * 2));
        g.setColor(new Color(215, 170, 80));
        g.setStroke(new BasicStroke(size * 0.06f));
        g.draw(new java.awt.geom.Ellipse2D.Float(lx - lr, ly - lr, lr * 2, lr * 2));
    }

    // ------------------------------------------------------------------ Map hint

    public static final int HINT_H = 30;

    /** One line of key hints, e.g. {"M", "Expand map", "N", "Contract"}; returns the width it needs. An empty key leaves just the words. */
    public static int hintWidth(Graphics2D g, String... keysAndActions) {
        float w = 12;
        for (int i = 0; i < keysAndActions.length; i += 2) {
            if (!keysAndActions[i].isEmpty()) {
                w += Math.max(20, g.getFontMetrics(font(Font.BOLD, 20 * 0.58f)).stringWidth(keysAndActions[i]) + 11) + 7;
            }
            w += labelWidth(g, keysAndActions[i + 1], 10.5f) + (i + 2 < keysAndActions.length ? 16 : 12);
        }
        return (int) Math.ceil(w);
    }

    public static void paintHint(Graphics2D g, int width, String... keysAndActions) {
        java.awt.geom.RoundRectangle2D pill = new java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, width - 1, HINT_H - 1, 8, 8);
        g.setColor(GLASS);
        g.fill(pill);
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(pill);
        float x = 10;
        for (int i = 0; i < keysAndActions.length; i += 2) {
            // An empty key leaves just the words
            if (!keysAndActions[i].isEmpty()) x += keyCap(g, keysAndActions[i], x, 5, 20) + 7;
            label(g, keysAndActions[i + 1], x, 19.5f, 10.5f, new Color(215, 225, 235));
            x += labelWidth(g, keysAndActions[i + 1], 10.5f) + 16;
        }
    }
}
