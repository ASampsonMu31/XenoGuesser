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

    public static final int SCORE_W = 262, SCORE_H = 84;
    /** Where the round's points fly to at the end of the results, relative to the panel. */
    public static final int SCORE_TARGET_X = 160, SCORE_TARGET_Y = 50;
    /** Gap between the HUD panels and the edges of the screen. */
    public static final int HUD_MARGIN = 18;

    public static void paintScorePanel(Graphics2D g, int round, int maxRounds, int score, Color body) {
        panel(g, 0, 0, SCORE_W, SCORE_H, body);
        // Round
        label(g, "Round", 20, 28, 11f, LABEL);
        g.setFont(font(Font.BOLD, 30f));
        g.setColor(GOLD);
        String r = String.format("%02d", round);
        g.drawString(r, 20, 64);
        float rw = g.getFontMetrics().stringWidth(r);
        g.setFont(font(Font.PLAIN, 15f));
        g.setColor(DIM);
        g.drawString("/ " + maxRounds, 22 + rw, 64);
        // Divider
        g.setColor(new Color(120, 205, 235, 70));
        g.fill(new java.awt.geom.Rectangle2D.Float(100, 18, 1f, SCORE_H - 36));
        // Score
        label(g, "Total score", 118, 28, 11f, LABEL);
        g.setFont(font(Font.BOLD, 30f));
        g.setColor(VALUE);
        g.drawString(String.format("%,d", score), 118, 64);
    }

    // ------------------------------------------------------------------ FPS

    public static final int FPS_W = 92, FPS_H = 24;

    public static void paintFps(Graphics2D g, int fps) {
        String n = Integer.toString(fps);
        Font big = font(Font.BOLD, 16f);
        FontMetrics fm = g.getFontMetrics(big);
        float labelW = labelWidth(g, "fps", 10f);
        float x = FPS_W - 6 - labelW - 5 - fm.stringWidth(n);
        // A soft shadow keeps it legible over bright sky
        g.setFont(big);
        g.setColor(new Color(0, 0, 0, 150));
        g.drawString(n, x + 1, 18);
        Color c = fps >= 45 ? new Color(120, 235, 140) : fps >= 25 ? GOLD : new Color(245, 105, 90);
        g.setColor(c);
        g.drawString(n, x, 17);
        label(g, "fps", FPS_W - 6 - labelW + 1, 18, 10f, new Color(0, 0, 0, 150));
        label(g, "fps", FPS_W - 6 - labelW, 17, 10f, new Color(225, 232, 240));
    }

    // ------------------------------------------------------------------ Inventory

    /** Something the player carries: its name, the key that uses it and a way to draw its icon. */
    public record Item(String name, String key, IconPainter icon) {
    }

    @FunctionalInterface
    public interface IconPainter {
        void paint(Graphics2D g, float x, float y, float size);
    }

    public static final int INVENTORY_W = 236;

    public static int inventoryHeight(int items) {
        return 38 + items * 54 + 6;
    }

    public static void paintInventory(Graphics2D g, List<Item> items) {
        int h = inventoryHeight(items.size());
        panel(g, 0, 0, INVENTORY_W, h, GLASS);
        label(g, "Inventory", 18, 25, 11f, LABEL);
        g.setColor(new Color(120, 205, 235, 60));
        g.fill(new java.awt.geom.Rectangle2D.Float(18, 33, INVENTORY_W - 36, 1f));
        float y = 40;
        for (Item item : items) {
            // Icon in its own slot
            java.awt.geom.RoundRectangle2D slot = new java.awt.geom.RoundRectangle2D.Float(18, y + 3, 44, 44, 8, 8);
            g.setColor(new Color(255, 255, 255, 14));
            g.fill(slot);
            g.setColor(new Color(120, 205, 235, 90));
            g.setStroke(new BasicStroke(1f));
            g.draw(slot);
            item.icon().paint(g, 21, y + 6, 38);
            g.setFont(font(Font.BOLD, 16f));
            g.setColor(VALUE);
            g.drawString(item.name(), 74, y + 22);
            label(g, "Use", 74, y + 40, 9.5f, DIM);
            float useW = labelWidth(g, "Use", 9.5f);
            keyCap(g, item.key(), 74 + useW + 7, y + 28, 17);
            y += 54;
        }
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
