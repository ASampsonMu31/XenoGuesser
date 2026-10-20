import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * The results' globe: the plain map wrapped round a ball, seen straight on (orthographic) with
 * north always up, skewered on a rod through the poles. It is looked at from over one place
 * (the centre's longitude and latitude), turned by dragging and zoomed with the wheel; the
 * reveal swings it from the guess to the answer along the great circle between them.
 */
public final class RevealGlobe {

    // The globe's radius as a share of the view's smaller side (the rod reaching out past the
    // poles still fits), the most it can be zoomed, and how far the rod sticks out (in radii)
    private static final double RADIUS_SHARE = 0.36, MAX_ZOOM = 12.0, ROD_REACH = 1.3;
    // This globe's radius as a share of the view's smaller side (the results' RADIUS_SHARE; the map's larger)
    private final double radiusShare;
    // Where the light comes from, in the view (x right, y up, z out of the screen)
    private static final double[] LIGHT = normalise(-0.45, 0.55, 0.7);
    private static final Color SPACE_TOP = new Color(18, 22, 38), SPACE_BOTTOM = new Color(34, 40, 62);
    private static final Color ATMOSPHERE = new Color(150, 195, 255);

    private double centreLon, centreLat;
    private double zoom = 1.0;

    // The map the globe is wrapped in, as packed RGB, and what the chart spans (see MapProjection)
    private BufferedImage textureSource;
    private int[] texture;
    private int textureW, textureH;
    private final float halfRegion, totalRegionWidth;

    // The ball as last drawn, and what it was drawn for, so it's only drawn again when that changes
    private BufferedImage picture;
    private double pictureLon = Double.NaN, pictureLat, pictureZoom;
    private int pictureW, pictureH;
    private Object pictureTexture;

    // Turning by dragging: the centre when the drag began
    private double dragLon, dragLat;

    public RevealGlobe(float totalRegionWidth) {
        this(totalRegionWidth, RADIUS_SHARE);
    }

    /** A globe filling radiusShare of its view's smaller side (the rod still reaching out past it as far as fits). */
    public RevealGlobe(float totalRegionWidth, double radiusShare) {
        this.radiusShare = radiusShare;
        this.totalRegionWidth = totalRegionWidth;
        this.halfRegion = totalRegionWidth / 2f;
    }

    /** The map wrapped round the globe (the chart's square image, see MapPanel's mapImage). */
    public void setTexture(BufferedImage image) {
        if (image == null || image == textureSource) return;
        textureSource = image;
        textureW = image.getWidth();
        textureH = image.getHeight();
        if (image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_INT_ARGB) {
            texture = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        } else {
            texture = image.getRGB(0, 0, textureW, textureH, null, 0, textureW);
        }
    }

    public void resetZoom() {
        zoom = 1.0;
    }

    /** Looks straight down on a place. */
    public void centreOn(GeodesicChunkGrid.Direction d) {
        centreLat = Math.asin(Math.max(-1.0, Math.min(1.0, d.y())));
        centreLon = Math.atan2(-d.z(), d.x());
    }

    /** The place t of the way from a to b along the great circle between them (0 at a, 1 at b). */
    public static GeodesicChunkGrid.Direction along(GeodesicChunkGrid.Direction a, GeodesicChunkGrid.Direction b, double t) {
        double cos = Math.max(-1.0, Math.min(1.0, a.dot(b)));
        double angle = Math.acos(cos);
        if (angle < 1e-9) return a;
        double sin = Math.sin(angle);
        double wa = Math.sin((1 - t) * angle) / sin, wb = Math.sin(t * angle) / sin;
        return new GeodesicChunkGrid.Direction(a.x() * wa + b.x() * wb, a.y() * wa + b.y() * wb, a.z() * wa + b.z() * wb).normalized();
    }

    // ------------------------------------------------------------------ the user's turning and zooming

    public void beginDrag() {
        dragLon = centreLon;
        dragLat = centreLat;
    }

    /** Turns the globe as the pointer moves (dx, dy) pixels from where the drag began: the surface follows it. */
    public void dragTo(int dx, int dy, int viewW, int viewH) {
        double r = radius(viewW, viewH);
        centreLon = dragLon - dx / r;
        centreLat = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, dragLat + dy / r));
    }

    /** Zooms in (steps above 0) or out by a factor per step. */
    // ------------------------------------------------------------------ detail when zoomed (M47)

    // The map's detail tiles over the part of the ball in view, at a level (level L has 2^L tiles
    // across the chart, each MapTiles.SIZE square) by tileKey, each its pixels; where one isn't
    // ready the whole texture's used. sourceKey names what they show. (detailLevel is the level
    // at the equator: away from it the chart's stretched, by 1 / cos(latitude), so a coarser
    // level shows as much there, and far fewer tiles are wanted near the poles: see levelAt)
    private int detailLevel;
    private java.util.Map<Long, int[]> detail = java.util.Map.of();
    private String detailSource = "";

    /**
     * The tiles' level the ball wants to stay sharp in a view of w by h, its texture being
     * textureWidth across: 0 while the whole texture's sharp enough.
     */
    public int detailLevel(int w, int h, int textureWidth) {
        double across = 2.0 * Math.PI * radius(w, h);
        if (across <= textureWidth * 1.1) return 0;
        return Math.max(1, Math.min(8, (int) Math.ceil(Math.log(across / MapTiles.SIZE) / Math.log(2))));
    }

    /** A tile's key: its level, and where it is at that level. */
    public static long tileKey(int level, int tx, int ty) {
        return ((long) level << 56) | ((long) tx << 28) | ty;
    }

    public static int levelOf(long key) {
        return (int) (key >>> 56);
    }

    public static int txOf(long key) {
        return (int) ((key >>> 28) & 0xFFFFFFF);
    }

    public static int tyOf(long key) {
        return (int) (key & 0xFFFFFFF);
    }

    /**
     * The level wanted at a latitude for the equator's level: as many levels coarser as the
     * chart's stretched there doubles (it's stretched by 1 / cos(latitude)).
     */
    private static int levelAt(double latitude, int equatorLevel) {
        double stretch = 1.0 / Math.max(1e-3, Math.cos(latitude));
        return Math.max(1, equatorLevel - (int) Math.floor(Math.log(stretch) / Math.log(2)));
    }

    /** The tiles (by tileKey) the ball shows in a view of w by h, for the equator's level (found from every few pixels). */
    public java.util.Set<Long> visibleTiles(int w, int h, int level) {
        java.util.Set<Long> found = new java.util.LinkedHashSet<>();
        double r = radius(w, h), cx = w / 2.0, cy = h / 2.0;
        double[][] a = axes();
        // (out from the middle, so the tiles in the middle are drawn first)
        int step = 6;
        for (int py = 0; py < h; py += step) {
            for (int px = 0; px < w; px += step) {
                double vx = (px - cx) / r, vy = -(py - cy) / r, rr = vx * vx + vy * vy;
                if (rr > 1.0) continue;
                double[] uv = chartOf(vx, vy, Math.sqrt(1.0 - rr), a);
                int at = levelAt(uv[2], level), n = 1 << at;
                int tx = (int) (uv[0] * n), ty = (int) (uv[1] * n);
                if (tx >= 0 && tx < n && ty >= 0 && ty < n) found.add(tileKey(at, tx, ty));
            }
        }
        return found;
    }

    /** Gives the ball the tiles ready for it (drawn afresh only when they're different). */
    public void setDetail(int level, java.util.Map<Long, int[]> tiles, String sourceKey) {
        if (level == detailLevel && sourceKey.equals(detailSource) && tiles.keySet().equals(detail.keySet())) return;
        detailLevel = level;
        detail = tiles;
        detailSource = sourceKey;
        picture = null;
    }

    /** Where a point on the ball (vx, vy, vz in the view's axes) is on the chart, {u, v} (0 to 1), and its latitude. */
    private double[] chartOf(double vx, double vy, double vz, double[][] a) {
        double dx = vx * a[0][0] + vy * a[1][0] + vz * a[2][0];
        double dy = vx * a[0][1] + vy * a[1][1] + vz * a[2][1];
        double dz = vx * a[0][2] + vy * a[1][2] + vz * a[2][2];
        double lat = Math.asin(Math.max(-1.0, Math.min(1.0, dy)));
        lat = Math.max(-1.5, Math.min(1.5, lat));
        double lon = Math.atan2(-dz, dx);
        return new double[] { (MapProjection.x(lon) + halfRegion) / totalRegionWidth, (MapProjection.z(lat) + halfRegion) / totalRegionWidth, lat };
    }

    public void zoomBy(double steps, double factor) {
        zoom = Math.max(1.0, Math.min(MAX_ZOOM, zoom * Math.pow(factor, steps)));
    }

    public double zoom() {
        return zoom;
    }

    // ------------------------------------------------------------------ seeing it

    private double radius(int w, int h) {
        return Math.min(w, h) * radiusShare * zoom;
    }

    /** The view's axes in the world: {east (right), north (up), towards the viewer}. */
    private double[][] axes() {
        double sLon = Math.sin(centreLon), cLon = Math.cos(centreLon), sLat = Math.sin(centreLat), cLat = Math.cos(centreLat);
        return new double[][] {
            { -sLon, 0.0, -cLon },
            { -sLat * cLon, cLat, sLat * sLon },
            { cLat * cLon, sLat, -cLat * sLon } };
    }

    /**
     * Where a direction from the planet's centre is on screen, {x, y, depth} (depth above 0 on
     * the side facing the viewer), for a view of w by h at (x, y).
     */
    public double[] project(GeodesicChunkGrid.Direction d, int x, int y, int w, int h) {
        double[][] a = axes();
        double r = radius(w, h);
        double ex = d.x() * a[0][0] + d.y() * a[0][1] + d.z() * a[0][2];
        double ny = d.x() * a[1][0] + d.y() * a[1][1] + d.z() * a[1][2];
        double cz = d.x() * a[2][0] + d.y() * a[2][1] + d.z() * a[2][2];
        return new double[] { x + w / 2.0 + ex * r, y + h / 2.0 - ny * r, cz };
    }

    /**
     * The direction from the planet's centre under a point on screen (sx, sy) in a view of w by h
     * at (x, y): the inverse of project; null off the ball.
     */
    public GeodesicChunkGrid.Direction unproject(double sx, double sy, int x, int y, int w, int h) {
        double r = radius(w, h);
        double vx = (sx - x - w / 2.0) / r, vy = -(sy - y - h / 2.0) / r;
        double rr = vx * vx + vy * vy;
        if (rr > 1.0) return null;
        double vz = Math.sqrt(1.0 - rr);
        double[][] a = axes();
        return new GeodesicChunkGrid.Direction(vx * a[0][0] + vy * a[1][0] + vz * a[2][0], vx * a[0][1] + vy * a[1][1] + vz * a[2][1],
                vx * a[0][2] + vy * a[1][2] + vz * a[2][2]).normalized();
    }

    /** Draws the space behind, the rod through the poles and the globe, in a view of w by h at (x, y). */
    public void paint(Graphics2D g, int x, int y, int w, int h) {
        g.setPaint(new GradientPaint(x, y, SPACE_TOP, x, y + h, SPACE_BOTTOM));
        g.fillRect(x, y, w, h);
        double r = radius(w, h);
        double cx = x + w / 2.0, cy = y + h / 2.0;
        // (the rod: the half beyond the far pole behind the ball, the near half in front of it)
        double northTowards = Math.sin(centreLat), northUp = Math.cos(centreLat);
        drawRod(g, cx, cy, r, northUp, northTowards, false);
        g.drawImage(ball(w, h), x, y, null);
        drawGraticule(g, x, y, w, h);
        drawRod(g, cx, cy, r, northUp, northTowards, true);
    }

    /** The ends of the rod beyond the poles that are behind the ball (front false) or in front of it. */
    private void drawRod(Graphics2D g, double cx, double cy, double r, double up, double towards, boolean front) {
        Stroke old = g.getStroke();
        float thick = (float) Math.max(3.0, r * 0.035);
        for (int end = -1; end <= 1; end += 2) {
            // (each end runs from just inside the pole, hidden by the ball, out to the knob)
            if ((end * towards >= 0) != front) continue;
            double x0 = cx, y0 = cy - end * up * r * 0.98, x1 = cx, y1 = cy - end * up * r * ROD_REACH;
            g.setStroke(new BasicStroke(thick + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(70, 52, 30));
            g.draw(new java.awt.geom.Line2D.Double(x0, y0, x1, y1));
            g.setStroke(new BasicStroke(thick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(196, 160, 92));
            g.draw(new java.awt.geom.Line2D.Double(x0, y0, x1, y1));
            g.setStroke(new BasicStroke(Math.max(1f, thick * 0.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(240, 214, 150));
            g.draw(new java.awt.geom.Line2D.Double(x0 - thick * 0.2, y0, x1 - thick * 0.2, y1));
            double knob = thick * 1.6;
            g.setColor(new Color(70, 52, 30));
            g.fill(new java.awt.geom.Ellipse2D.Double(x1 - knob / 2 - 1, y1 - knob / 2 - 1, knob + 2, knob + 2));
            g.setColor(new Color(214, 178, 104));
            g.fill(new java.awt.geom.Ellipse2D.Double(x1 - knob / 2, y1 - knob / 2, knob, knob));
        }
        g.setStroke(old);
    }

    /** The ball itself, lit and with a thin haze of air round its edge (drawn again only when the view changes). */
    private BufferedImage ball(int w, int h) {
        if (picture != null && pictureLon == centreLon && pictureLat == centreLat && pictureZoom == zoom
                && pictureW == w && pictureH == h && pictureTexture == textureSource) return picture;
        BufferedImage image = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        double r = radius(w, h), cx = w / 2.0, cy = h / 2.0;
        double[][] a = axes();
        int[] tex = texture;
        int tw = textureW, th = textureH;
        java.util.Map<Long, int[]> tiles = detail;
        int equatorLevel = detailLevel, size = MapTiles.SIZE;
        int top = (int) Math.max(0, Math.floor(cy - r - 1)), bottom = (int) Math.min(h - 1, Math.ceil(cy + r + 1));
        java.util.stream.IntStream.rangeClosed(top, bottom).parallel().forEach(py -> {
            double vy = -(py + 0.5 - cy) / r;
            for (int px = 0; px < w; px++) {
                double vx = (px + 0.5 - cx) / r;
                double rr = vx * vx + vy * vy;
                double edge = (1.0 - Math.sqrt(rr)) * r + 0.5;   // (pixels inside the rim, for a smooth edge)
                if (edge <= 0.0) continue;
                double vz = Math.sqrt(Math.max(0.0, 1.0 - rr));
                int rgb = 0x3060a0;
                if (tex != null) {
                    double[] uv = chartOf(vx, vy, vz, a);
                    double u = uv[0], v = uv[1];
                    // From the detail tile there, if it's ready; else the whole texture
                    int[] tile = null;
                    int sx = 0, sy = 0;
                    if (equatorLevel > 0) {
                        int n = 1 << levelAt(uv[2], equatorLevel);
                        int tx = (int) (u * n), ty = (int) (v * n);
                        if (tx >= 0 && tx < n && ty >= 0 && ty < n) {
                            tile = tiles.get(tileKey(levelAt(uv[2], equatorLevel), tx, ty));
                            sx = Math.max(0, Math.min(size - 1, (int) ((u * n - tx) * size)));
                            sy = Math.max(0, Math.min(size - 1, (int) ((v * n - ty) * size)));
                        }
                    }
                    if (tile != null) {
                        rgb = tile[sy * size + sx];
                    } else {
                        int tx = Math.max(0, Math.min(tw - 1, (int) (u * tw)));
                        int ty = Math.max(0, Math.min(th - 1, (int) (v * th)));
                        rgb = tex[ty * tw + tx];
                    }
                }
                // Lit from the upper left, never quite dark; the air's haze thickening to the rim
                double light = 0.5 + 0.55 * Math.max(0.0, vx * LIGHT[0] + vy * LIGHT[1] + vz * LIGHT[2]);
                double haze = Math.pow(1.0 - vz, 3.0) * 0.55;
                int red = shade((rgb >> 16) & 255, light, haze, ATMOSPHERE.getRed());
                int green = shade((rgb >> 8) & 255, light, haze, ATMOSPHERE.getGreen());
                int blue = shade(rgb & 255, light, haze, ATMOSPHERE.getBlue());
                int alpha = (int) Math.round(Math.min(1.0, edge) * 255);
                out[py * w + px] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        });
        picture = image;
        pictureLon = centreLon;
        pictureLat = centreLat;
        pictureZoom = zoom;
        pictureW = w;
        pictureH = h;
        pictureTexture = textureSource;
        return image;
    }

    private static int shade(int channel, double light, double haze, int air) {
        double c = channel * light;
        c = c + (air - c) * haze;
        return (int) Math.max(0, Math.min(255, Math.round(c)));
    }

    /** Faint lines of latitude and longitude every 30 degrees, on the side facing the viewer. */
    private void drawGraticule(Graphics2D g, int x, int y, int w, int h) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1f));
        for (int lon = -180; lon < 180; lon += 30) {
            Path2D path = new Path2D.Double();
            boolean drawing = false;
            for (int lat = -90; lat <= 90; lat += 3) {
                drawing = addPoint(path, project(direction(lat, lon), x, y, w, h), drawing);
            }
            g.setColor(new Color(255, 255, 255, lon == 0 ? 70 : 40));
            g.draw(path);
        }
        for (int lat = -60; lat <= 60; lat += 30) {
            Path2D path = new Path2D.Double();
            boolean drawing = false;
            for (int lon = -180; lon <= 180; lon += 3) {
                drawing = addPoint(path, project(direction(lat, lon), x, y, w, h), drawing);
            }
            g.setColor(new Color(255, 255, 255, lat == 0 ? 70 : 40));
            g.draw(path);
        }
        g.setStroke(old);
    }

    /** Adds a projected point to a path if it's on the near side; returns whether the path is now drawing. */
    private static boolean addPoint(Path2D path, double[] p, boolean drawing) {
        if (p[2] < 0.0) return false;
        if (drawing) path.lineTo(p[0], p[1]);
        else path.moveTo(p[0], p[1]);
        return true;
    }

    private static GeodesicChunkGrid.Direction direction(double latDegrees, double lonDegrees) {
        double lat = Math.toRadians(latDegrees), lon = Math.toRadians(lonDegrees), c = Math.cos(lat);
        return new GeodesicChunkGrid.Direction(c * Math.cos(lon), Math.sin(lat), -c * Math.sin(lon));
    }

    /**
     * The great circle's arc from a to b (the way the distance is measured), dotted, drawn as far
     * as progress (0 to 1) of the way along; only where it's on the side facing the viewer.
     */
    public void drawArc(Graphics2D g, GeodesicChunkGrid.Direction a, GeodesicChunkGrid.Direction b, double progress,
                        int x, int y, int w, int h) {
        drawArc(g, a, b, progress, x, y, w, h, Color.WHITE);
    }

    /** As drawArc, the dashes in colour (multiplayer: each player's line in their own, M14). */
    public void drawArc(Graphics2D g, GeodesicChunkGrid.Direction a, GeodesicChunkGrid.Direction b, double progress,
                        int x, int y, int w, int h, Color colour) {
        if (progress <= 0.0) return;
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, a.dot(b))));
        int steps = Math.max(2, (int) Math.ceil(Math.toDegrees(angle) * progress));
        Path2D path = new Path2D.Double();
        boolean drawing = false;
        for (int i = 0; i <= steps; i++) {
            drawing = addPoint(path, project(along(a, b, progress * i / steps), x, y, w, h), drawing);
        }
        Stroke old = g.getStroke();
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(20, 20, 30, 150));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 6f, 6f }, 0f));
        g.draw(path);
        g.setColor(colour);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 6f, 6f }, 0f));
        g.draw(path);
        g.setStroke(old);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    private static double[] normalise(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[] { x / l, y / l, z / l };
    }
}
