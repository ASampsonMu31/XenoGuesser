import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The heights written on the contour lines: each number on a little rounded badge in its
 * line's own colour, laid over the line itself along its straighter stretches (so lines close
 * together, on steep or high ground, still get theirs: a neighbour passing under the badge is
 * covered, not in the way), a good way apart along each line and a few to a line, never over
 * one another; smaller where a full-sized one won't fit.
 */
final class ContourLabels {

    /** A traced contour line to be labelled: its path (in the canvas's pixels), its words and colour, and whether it's a heavier one. */
    record Line(Shape path, String text, Color colour, float width, boolean index) {
    }

    // The most numbers along any one line, and how much straighter than not the line must run
    // under one (the gap between its ends against the text's length)
    private static final int MOST_PER_LINE = 4;
    private static final float STRAIGHT_ENOUGH = 0.85f;
    // The smaller size tried where a full-sized number won't fit (a share of it), and how much
    // curve it may lie on (it bends round a tight ring less noticeably)
    private static final float SMALLER = 0.72f, CURVY_ENOUGH = 0.7f;

    private ContourLabels() {
    }

    /** A line flattened to its points, and how far along it each lies. */
    private record Run(Line line, float[] xs, float[] ys, float[] along) {
        float length() {
            return along[along.length - 1];
        }

        /** The point this far along the run. */
        float[] at(float d) {
            int i = 1;
            while (i < along.length - 1 && along[i] < d) i++;
            float span = along[i] - along[i - 1];
            float t = span <= 0f ? 0f : Math.max(0f, Math.min(1f, (d - along[i - 1]) / span));
            return new float[] { xs[i - 1] + (xs[i] - xs[i - 1]) * t, ys[i - 1] + (ys[i] - ys[i - 1]) * t };
        }
    }

    /** A number placed: its middle, its slant, and the box round it (for keeping others off it). */
    private record Placed(float x, float y, float angle, Rectangle2D bounds) {
    }

    /**
     * Writes the lines' numbers onto g, a font of fontPx pixels, one every `spacing` pixels
     * or so along each line, all wholly inside `within` (so none is cut at a tile's edge).
     */
    static void draw(Graphics2D g, List<Line> lines, float fontPx, float spacing, Rectangle2D within) {
        List<Run> runs = new ArrayList<>();
        for (Line line : lines) {
            Run run = flatten(line);
            if (run != null) runs.add(run);
        }
        // The heavier lines first, then the longer: they're the ones most worth a number
        List<Integer> order = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) order.add(r);
        order.sort(Comparator.<Integer, Boolean>comparing(r -> !runs.get(r).line.index()).thenComparing(r -> -runs.get(r).length()));

        Font full = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(fontPx));
        Font small = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, Math.round(fontPx * SMALLER)));
        FontMetrics fullFm = g.getFontMetrics(full), smallFm = g.getFontMetrics(small);
        List<Placed> placed = new ArrayList<>();
        for (int r : order) {
            Run run = runs.get(r);
            String text = run.line.text();
            float length = run.length();
            // Spots a spacing apart, the first half a spacing in (or the middle of a short line),
            // each tried a little either side if it won't do where it is, full-sized and then smaller
            List<Float> spots = new ArrayList<>();
            if (length < spacing) spots.add(length * 0.5f);
            else for (float d = spacing * 0.5f; d < length; d += spacing) spots.add(d);
            int count = 0;
            for (float spot : spots) {
                if (count >= MOST_PER_LINE) break;
                boolean done = false;
                for (FontMetrics fm : new FontMetrics[] { fullFm, smallFm }) {
                    boolean smaller = fm == smallFm;
                    float w = fm.stringWidth(text) + fm.getAscent() * 0.5f, h = fm.getAscent() * 0.95f;
                    // (the smaller badge goes on shorter lines too: a small hill's tight rings)
                    if (length < w * (smaller ? 1.2f : 1.6f)) continue;
                    for (float shift : new float[] { 0f, 0.2f, -0.2f, 0.4f, -0.4f }) {
                        float d = spot + shift * Math.min(spacing, length) * 0.5f;
                        if (d - w * 0.6f < 0f || d + w * 0.6f > length) continue;
                        Placed p = fit(run, d, w, h, smaller ? CURVY_ENOUGH : STRAIGHT_ENOUGH, within, placed);
                        if (p != null) {
                            g.setFont(fm.getFont());
                            write(g, text, run.line.colour(), p, w, h, fm);
                            placed.add(p);
                            count++;
                            done = true;
                            break;
                        }
                    }
                    if (done) break;
                }
            }
        }
    }

    /** Where a number's badge would go this far along the run, over the line; or null if it won't fit there. */
    private static Placed fit(Run run, float d, float w, float h, float straight, Rectangle2D within, List<Placed> placed) {
        float[] a = run.at(d - w * 0.55f), b = run.at(d + w * 0.55f);
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float chord = (float) Math.hypot(dx, dy);
        if (chord < w * 1.1f * straight) return null;
        float angle = (float) Math.atan2(dy, dx);
        // Upright: never upside down
        if (Math.cos(angle) < 0) angle += (float) Math.PI;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float[] middle = run.at(d);
        float cx = middle[0], cy = middle[1];
        float hw = w * 0.5f + 1f, hh = h * 0.5f + 1f;
        float ex = Math.abs(cos) * hw + Math.abs(sin) * hh, ey = Math.abs(sin) * hw + Math.abs(cos) * hh;
        Rectangle2D box = new Rectangle2D.Float(cx - ex, cy - ey, ex * 2, ey * 2);
        if (!within.contains(box)) return null;
        for (Placed p : placed) {
            // (with a little room between)
            if (p.bounds().intersects(box.getX() - 3, box.getY() - 3, box.getWidth() + 6, box.getHeight() + 6)) return null;
        }
        return new Placed(cx, cy, angle, box);
    }

    /**
     * Writes a number on its badge: a rounded box in its line's colour, edged faintly in a
     * contrasting shade, and the number on it in black or white, whichever reads better.
     */
    private static void write(Graphics2D g, String text, Color colour, Placed p, float w, float h, FontMetrics fm) {
        AffineTransform saved = g.getTransform();
        g.translate(p.x(), p.y());
        g.rotate(p.angle());
        float light = (0.299f * colour.getRed() + 0.587f * colour.getGreen() + 0.114f * colour.getBlue()) / 255f;
        java.awt.geom.RoundRectangle2D badge = new java.awt.geom.RoundRectangle2D.Float(-w * 0.5f, -h * 0.5f, w, h, h * 0.8f, h * 0.8f);
        g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 240));
        g.fill(badge);
        g.setColor(light > 0.5f ? new Color(0, 0, 0, 120) : new Color(255, 255, 255, 150));
        g.setStroke(new BasicStroke(Math.max(1f, h * 0.07f)));
        g.draw(badge);
        TextLayout layout = new TextLayout(text, g.getFont(), g.getFontRenderContext());
        float tw = (float) layout.getAdvance();
        Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(-tw * 0.5f, fm.getAscent() * 0.36f));
        g.setColor(light > 0.5f ? new Color(14, 22, 14) : Color.WHITE);
        g.fill(outline);
        g.setTransform(saved);
    }

    /** A line as its points (flattened), or null if it's too short to be worth anything. */
    private static Run flatten(Line line) {
        List<float[]> points = new ArrayList<>();
        float[] start = null;
        float[] c = new float[6];
        for (PathIterator it = line.path().getPathIterator(null, 0.5); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                start = new float[] { c[0], c[1] };
                points.add(start);
            } else if (type == PathIterator.SEG_LINETO) {
                points.add(new float[] { c[0], c[1] });
            } else if (type == PathIterator.SEG_CLOSE && start != null) {
                points.add(start);
            }
        }
        if (points.size() < 2) return null;
        float[] xs = new float[points.size()], ys = new float[points.size()], along = new float[points.size()];
        for (int i = 0; i < points.size(); i++) {
            xs[i] = points.get(i)[0];
            ys[i] = points.get(i)[1];
            if (i > 0) along[i] = along[i - 1] + (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
        return new Run(line, xs, ys, along);
    }
}
