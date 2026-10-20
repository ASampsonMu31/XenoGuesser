import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The markers on the map: a guess's pin (a steel needle with a glossy head in its player's colour,
 * a ring round the exact spot and a shadow on the map) and the flag where the player really was (a
 * tall pole, a chequered green flag waving from it, and rings round its foot). Drawn bold, with
 * light and dark edges, so they stand out over any land or sea.
 */
final class MapMarkers {

    /** How far a pin's head stands above its point, and the head's width. */
    static final int PIN_LENGTH = 24, PIN_HEAD = 18;
    // How tall the flag's pole is, and its flag
    private static final int POLE = 38, FLAG_W = 24, FLAG_H = 16;
    private static final Color FLAG_GREEN = new Color(40, 205, 90), FLAG_DARK = new Color(16, 120, 52);
    private static final Color GOLD = new Color(250, 205, 70);

    private MapMarkers() {
    }

    /** A pin stuck in the map at (x, y), its head in colour. */
    static void pin(Graphics2D target, int x, int y, Color colour) {
        Graphics2D g = (Graphics2D) target.create();
        smooth(g);
        float headY = y - PIN_LENGTH, r = PIN_HEAD / 2f;
        // Its shadow, cast down and to the right
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new Ellipse2D.Float(x - 2f, y - 2.5f, 12f, 5f));
        g.fill(new Ellipse2D.Float(x + 5f - r, y - 1f - r * 0.45f, PIN_HEAD, PIN_HEAD * 0.45f));
        // The spot itself: a ring round where the needle goes in
        g.setStroke(new BasicStroke(1.6f));
        g.setColor(new Color(255, 255, 255, 200));
        g.draw(new Ellipse2D.Float(x - 4.5f, y - 2.5f, 9f, 5f));
        g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 160));
        g.draw(new Ellipse2D.Float(x - 6.5f, y - 3.5f, 13f, 7f));
        // The needle: steel, lit down one side, tapering to its point
        GeneralPath needle = new GeneralPath();
        needle.moveTo(x - 1.6f, headY + r * 0.6f);
        needle.lineTo(x + 1.6f, headY + r * 0.6f);
        needle.lineTo(x + 0.25f, y);
        needle.lineTo(x - 0.25f, y);
        needle.closePath();
        g.setPaint(new GradientPaint(x - 1.6f, 0f, new Color(245, 247, 250), x + 1.6f, 0f, new Color(110, 115, 125)));
        g.fill(needle);
        g.setColor(new Color(40, 44, 50, 180));
        g.setStroke(new BasicStroke(0.6f));
        g.draw(needle);
        // The head: dark outline, a white rim, and the colour, glossy, lit from the top left
        Ellipse2D head = new Ellipse2D.Float(x - r, headY - r, PIN_HEAD, PIN_HEAD);
        g.setColor(new Color(15, 18, 24));
        g.fill(new Ellipse2D.Float(x - r - 2f, headY - r - 2f, PIN_HEAD + 4f, PIN_HEAD + 4f));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(x - r - 1f, headY - r - 1f, PIN_HEAD + 2f, PIN_HEAD + 2f));
        g.setPaint(new RadialGradientPaint(new Point2D.Float(x - r * 0.35f, headY - r * 0.4f), r * 1.5f,
                new float[] { 0f, 0.55f, 1f }, new Color[] { brighter(colour, 0.55f), colour, darker(colour, 0.55f) },
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(head);
        // ...and a highlight on it
        g.setColor(new Color(255, 255, 255, 190));
        g.fill(new Ellipse2D.Float(x - r * 0.62f, headY - r * 0.72f, r * 0.62f, r * 0.42f));
        g.dispose();
    }

    /** The flag where the player really was, its pole's foot at (x, y). */
    static void flag(Graphics2D target, int x, int y) {
        Graphics2D g = (Graphics2D) target.create();
        smooth(g);
        // Rings round its foot, spreading out and fading, and its shadow
        long now = System.currentTimeMillis();
        for (int i = 0; i < 2; i++) {
            float t = ((now / 1600f) + i * 0.5f) % 1f;
            float w = 10f + 26f * t;
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(FLAG_GREEN.getRed(), FLAG_GREEN.getGreen(), FLAG_GREEN.getBlue(), Math.round(200 * (1f - t))));
            g.draw(new Ellipse2D.Float(x - w / 2f, y - w * 0.22f, w, w * 0.44f));
        }
        g.setColor(new Color(0, 0, 0, 80));
        g.fill(new Ellipse2D.Float(x - 3f, y - 2.5f, 20f, 5f));
        g.setColor(new Color(15, 18, 24));
        g.fill(new Ellipse2D.Float(x - 4f, y - 2.5f, 8f, 5f));
        float top = y - POLE;
        // The pole: dark edged, lit down one side
        g.setColor(new Color(20, 22, 28));
        g.fill(new RoundRectangle2D.Float(x - 2.6f, top - 1f, 5.2f, POLE + 1f, 2f, 2f));
        g.setPaint(new GradientPaint(x - 1.6f, 0f, new Color(250, 250, 252), x + 1.6f, 0f, new Color(140, 145, 155)));
        g.fill(new RoundRectangle2D.Float(x - 1.6f, top, 3.2f, POLE, 2f, 2f));
        // The flag, waving: chequered green, edged white and dark
        GeneralPath cloth = new GeneralPath();
        float wave = (float) Math.sin(now / 260.0) * 2f;
        float fx = x + 1.6f, fy = top + 1f;
        cloth.moveTo(fx, fy);
        cloth.curveTo(fx + FLAG_W * 0.35f, fy - 3f + wave, fx + FLAG_W * 0.7f, fy + 3f - wave, fx + FLAG_W, fy + 1f + wave);
        cloth.lineTo(fx + FLAG_W, fy + FLAG_H + 1f + wave);
        cloth.curveTo(fx + FLAG_W * 0.7f, fy + FLAG_H + 3f - wave, fx + FLAG_W * 0.35f, fy + FLAG_H - 3f + wave, fx, fy + FLAG_H);
        cloth.closePath();
        g.setColor(new Color(15, 18, 24));
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(cloth);
        g.setPaint(new GradientPaint(fx, fy, FLAG_GREEN, fx + FLAG_W, fy + FLAG_H, FLAG_DARK));
        g.fill(cloth);
        Graphics2D checks = (Graphics2D) g.create();
        checks.clip(cloth);
        checks.setColor(new Color(255, 255, 255, 120));
        float cell = FLAG_H / 4f;
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 7; col++) {
                if ((row + col) % 2 == 0) checks.fill(new java.awt.geom.Rectangle2D.Float(fx + col * cell, fy - 3f + row * cell, cell, cell));
            }
        }
        checks.dispose();
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(cloth);
        // A gold ball on top
        g.setColor(new Color(15, 18, 24));
        g.fill(new Ellipse2D.Float(x - 4.5f, top - 7.5f, 9f, 9f));
        g.setPaint(new RadialGradientPaint(new Point2D.Float(x - 1.2f, top - 4.5f), 4.5f,
                new float[] { 0f, 1f }, new Color[] { new Color(255, 245, 200), GOLD }, MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(new Ellipse2D.Float(x - 3.5f, top - 6.5f, 7f, 7f));
        g.dispose();
    }

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static Color brighter(Color c, float share) {
        return new Color(Math.round(c.getRed() + (255 - c.getRed()) * share), Math.round(c.getGreen() + (255 - c.getGreen()) * share),
                Math.round(c.getBlue() + (255 - c.getBlue()) * share));
    }

    private static Color darker(Color c, float share) {
        return new Color(Math.round(c.getRed() * (1f - share)), Math.round(c.getGreen() * (1f - share)), Math.round(c.getBlue() * (1f - share)));
    }
}
