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
 * The heights written beside the contour lines, as a surveyor's map has them: small numbers
 * in each line's own colour, lying along it on its straighter stretches, a good way apart
 * along each line and only a few to a line, never over one another or across another line.
 */
final class ContourLabels {

    /** A traced contour line to be labelled: its path (in the canvas's pixels), its words and colour, and whether it's a heavier one. */
    record Line(Shape path, String text, Color colour, float width, boolean index) {
    }

    // The most numbers along any one line, and how much straighter than not the line must run
    // under one (the gap between its ends against the text's length)
    private static final int MOST_PER_LINE = 3;
    private static final float STRAIGHT_ENOUGH = 0.9f;
    // The lines' segments are kept in buckets this many pixels square, to find those near a number quickly
    private static final float BUCKET = 32f;

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
        // Every segment of every line, bucketed, for telling whether a number would sit across one
        int across = (int) Math.ceil(within.getMaxX() / BUCKET) + 2, down = (int) Math.ceil(within.getMaxY() / BUCKET) + 2;
        List<List<int[]>> buckets = new ArrayList<>(across * down);
        for (int i = 0; i < across * down; i++) buckets.add(null);
        for (int r = 0; r < runs.size(); r++) {
            Run run = runs.get(r);
            for (int i = 1; i < run.xs.length; i++) {
                int bx0 = bucket(Math.min(run.xs[i - 1], run.xs[i]), across), bx1 = bucket(Math.max(run.xs[i - 1], run.xs[i]), across);
                int by0 = bucket(Math.min(run.ys[i - 1], run.ys[i]), down), by1 = bucket(Math.max(run.ys[i - 1], run.ys[i]), down);
                for (int by = by0; by <= by1; by++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        int k = by * across + bx;
                        if (buckets.get(k) == null) buckets.set(k, new ArrayList<>());
                        buckets.get(k).add(new int[] { r, i });
                    }
                }
            }
        }
        // The heavier lines first, then the longer: they're the ones most worth a number
        List<Integer> order = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) order.add(r);
        order.sort(Comparator.<Integer, Boolean>comparing(r -> !runs.get(r).line.index()).thenComparing(r -> -runs.get(r).length()));

        Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(fontPx));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        List<Placed> placed = new ArrayList<>();
        for (int r : order) {
            Run run = runs.get(r);
            String text = run.line.text();
            float w = fm.stringWidth(text), h = fm.getAscent() * 0.8f;
            float length = run.length();
            if (length < w * 3f) continue;
            // Spots a spacing apart, the first half a spacing in (or the middle of a short line),
            // each tried a little either side if it won't do where it is
            List<Float> spots = new ArrayList<>();
            if (length < spacing) spots.add(length * 0.5f);
            else for (float d = spacing * 0.5f; d < length - w; d += spacing) spots.add(d);
            int count = 0;
            for (float spot : spots) {
                if (count >= MOST_PER_LINE) break;
                for (float shift : new float[] { 0f, 0.2f, -0.2f, 0.4f, -0.4f }) {
                    float d = spot + shift * Math.min(spacing, length) * 0.5f;
                    if (d - w * 0.6f < 0f || d + w * 0.6f > length) continue;
                    Placed p = fit(run, r, d, w, h, within, runs, buckets, across, placed);
                    if (p != null) {
                        write(g, text, run.line.colour(), p, w, fm);
                        placed.add(p);
                        count++;
                        break;
                    }
                }
            }
        }
    }

    /** Where a number would go this far along the run, beside it; or null if it won't fit there. */
    private static Placed fit(Run run, int self, float d, float w, float h, Rectangle2D within, List<Run> runs,
                              List<List<int[]>> buckets, int across, List<Placed> placed) {
        float[] a = run.at(d - w * 0.55f), b = run.at(d + w * 0.55f);
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float chord = (float) Math.hypot(dx, dy);
        if (chord < w * 1.1f * STRAIGHT_ENOUGH) return null;
        float angle = (float) Math.atan2(dy, dx);
        // Upright: never upside down
        if (Math.cos(angle) < 0) angle += (float) Math.PI;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        // Beside the line (on the side that's "above" the text), clear of its stroke
        float gap = run.line.width() * 0.5f + h * 0.5f + 2f;
        float cx = (a[0] + b[0]) * 0.5f + sin * gap, cy = (a[1] + b[1]) * 0.5f - cos * gap;
        float hw = w * 0.5f + 2f, hh = h * 0.5f + 2f;
        float ex = Math.abs(cos) * hw + Math.abs(sin) * hh, ey = Math.abs(sin) * hw + Math.abs(cos) * hh;
        Rectangle2D box = new Rectangle2D.Float(cx - ex, cy - ey, ex * 2, ey * 2);
        if (!within.contains(box)) return null;
        for (Placed p : placed) {
            // (with a little room between)
            if (p.bounds().intersects(box.getX() - 4, box.getY() - 4, box.getWidth() + 8, box.getHeight() + 8)) return null;
        }
        // No line but its own may pass under it (tested in the number's own frame)
        Rectangle2D local = new Rectangle2D.Float(-hw, -hh, hw * 2, hh * 2);
        int bx0 = bucket((float) box.getMinX(), across), bx1 = bucket((float) box.getMaxX(), across);
        int by0 = bucket((float) box.getMinY(), buckets.size() / across), by1 = bucket((float) box.getMaxY(), buckets.size() / across);
        for (int by = by0; by <= by1; by++) {
            for (int bx = bx0; bx <= bx1; bx++) {
                List<int[]> segments = buckets.get(by * across + bx);
                if (segments == null) continue;
                for (int[] s : segments) {
                    if (s[0] == self) continue;
                    Run other = runs.get(s[0]);
                    float x0 = other.xs[s[1] - 1] - cx, y0 = other.ys[s[1] - 1] - cy, x1 = other.xs[s[1]] - cx, y1 = other.ys[s[1]] - cy;
                    if (local.intersectsLine(x0 * cos + y0 * sin, -x0 * sin + y0 * cos, x1 * cos + y1 * sin, -x1 * sin + y1 * cos)) return null;
                }
            }
        }
        return new Placed(cx, cy, angle, box);
    }

    /** Writes a number in its line's colour, edged faintly in a contrasting shade so it reads over any ground. */
    private static void write(Graphics2D g, String text, Color colour, Placed p, float w, FontMetrics fm) {
        AffineTransform saved = g.getTransform();
        g.translate(p.x(), p.y());
        g.rotate(p.angle());
        TextLayout layout = new TextLayout(text, g.getFont(), g.getFontRenderContext());
        Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(-w * 0.5f, fm.getAscent() * 0.4f));
        float light = (0.299f * colour.getRed() + 0.587f * colour.getGreen() + 0.114f * colour.getBlue()) / 255f;
        g.setColor(light > 0.5f ? new Color(0, 0, 0, 110) : new Color(255, 255, 255, 140));
        g.setStroke(new BasicStroke(Math.max(2f, fm.getAscent() * 0.18f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
        g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue()));
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

    /** The bucket a coordinate falls in, held to the grid. */
    private static int bucket(float v, int count) {
        return Math.max(0, Math.min(count - 1, (int) Math.floor(v / BUCKET) + 1));
    }
}
