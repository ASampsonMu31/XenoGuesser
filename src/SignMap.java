import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

/**
 * A small printed map of the area round a sign, for drawing on the sign itself: land shaded
 * by its hills, the sea, roads and buildings, in a cartographic style that differs from nation
 * to nation, and now and then a pin where the sign stands.
 */
public final class SignMap {

    private SignMap() {
    }

    /**
     * Draws the map round centre's origin, halfWidth world units from middle to left and right
     * edges, as ARGB pixels in rows from the top. It is laid out in centre's frame (an
     * east/up/south one: north up) in true distances along the ground, so it looks the same at
     * any latitude. Optional: a mark where the sign stands (here), a business it advertises
     * (destination, its badge showing a letter of its name) and the way along the roads between them.
     */
    public static int[] render(InfrastructureManager infrastructure, PerlinNoise terrain, float seaLevel, long styleSeed,
                               SphereFrame centre, float halfWidth, int width, int height,
                               SpherePosition here, SpherePosition destination, List<SpherePosition> route, BufferedImage badgeGlyph) {
        Random style = new Random(styleSeed);
        float hue = style.nextFloat();
        float[] land = WorldPalette.hsv(hue, 0.08f + style.nextFloat() * 0.22f, 0.86f + style.nextFloat() * 0.1f);
        float[] high = WorldPalette.hsv(hue + 0.05f, 0.15f + style.nextFloat() * 0.2f, 0.62f + style.nextFloat() * 0.15f);
        float[] water = WorldPalette.hsv(0.53f + style.nextFloat() * 0.1f, 0.3f + style.nextFloat() * 0.3f, 0.72f + style.nextFloat() * 0.18f);
        boolean darkRoads = style.nextBoolean();
        Color roadFill = darkRoads ? new Color(70, 66, 64) : Color.WHITE;
        Color roadCasing = darkRoads ? null : new Color(120, 110, 100);
        Color highwayFill = colour(WorldPalette.hsv(style.nextFloat(), 0.55f, 0.85f));
        Color building = colour(WorldPalette.hsv(hue + 0.5f * style.nextFloat(), 0.15f + style.nextFloat() * 0.25f, 0.45f + style.nextFloat() * 0.25f));

        float unitsPerPixel = halfWidth * 2f / width;
        float halfHeight = halfWidth * height / width;
        float left = -halfWidth, top = -halfHeight;

        float reliefScale = Math.min(1f, unitsPerPixel / 12f);
        // The land, from a coarse grid of heights filled in between
        int step = 2;
        int gw = width / step + 2, gh = height / step + 2;
        float[] heights = new float[gw * gh];
        for (int j = 0; j < gh; j++) {
            for (int i = 0; i < gw; i++) {
                heights[j * gw + i] = TerrainMesh.getLayeredHeight(
                        centre.toSphere(left + i * step * unitsPerPixel, 0.0, top + j * step * unitsPerPixel), terrain);
            }
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float gx = x / (float) step, gy = y / (float) step;
                int i = Math.min(gw - 2, (int) gx), j = Math.min(gh - 2, (int) gy);
                float fx = gx - i, fy = gy - j;
                float h00 = heights[j * gw + i], h10 = heights[j * gw + i + 1];
                float h01 = heights[(j + 1) * gw + i], h11 = heights[(j + 1) * gw + i + 1];
                float h = (h00 * (1 - fx) + h10 * fx) * (1 - fy) + (h01 * (1 - fx) + h11 * fx) * fy;
                float[] c;
                if (h <= seaLevel) {
                    c = water;
                } else {
                    float t = Math.min(1f, (h - seaLevel) / 900f);
                    c = mix(land, high, (float) Math.sqrt(t));
                    // Hill shading, lit from the north-west
                    float slopeX = ((h10 - h00) + (h11 - h01)) * 0.5f / (step * unitsPerPixel);
                    float slopeZ = ((h01 - h00) + (h11 - h10)) * 0.5f / (step * unitsPerPixel);
                    // Close in, the ground's fine bumps would show; only the larger hills are shaded
                    float shade = Math.max(0.7f, Math.min(1.15f, 1f - (slopeX + slopeZ) * 0.35f * reliefScale));
                    c = new float[] { c[0] * shade, c[1] * shade, c[2] * shade };
                }
                image.setRGB(x, y, colour(c).getRGB());
            }
        }

        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float scale = 1f / unitsPerPixel;
        float margin = 60f;
        float reach = (float) Math.hypot(halfWidth, halfHeight) + margin;

        // Buildings, in their own shapes, each side at least a couple of pixels
        g.setColor(building);
        float least = 2.2f * unitsPerPixel;
        infrastructure.forEachBuildingNear(centre.origin(), reach, (frame, outline, nationId) -> {
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (float[] p : outline) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]);
            }
            float sx = Math.max(1f, least / Math.max(1e-3f, maxX - minX)), sz = Math.max(1f, least / Math.max(1e-3f, maxZ - minZ));
            Path2D.Float shape = new Path2D.Float();
            for (int i = 0; i < outline.length; i++) {
                double[] p = centre.fromSphere(frame.toWorld(outline[i][0] * sx, 0.0, outline[i][1] * sz));
                float px = (float) (p[0] - left) * scale, pz = (float) (p[2] - top) * scale;
                if (i == 0) shape.moveTo(px, pz); else shape.lineTo(px, pz);
            }
            shape.closePath();
            g.fill(shape);
        });

        // Roads: lanes, then streets, then highways on top
        for (RoadPath.RoadClass roadClass : new RoadPath.RoadClass[] { RoadPath.RoadClass.LANE, RoadPath.RoadClass.STREET, RoadPath.RoadClass.HIGHWAY }) {
            float minPixels = roadClass == RoadPath.RoadClass.HIGHWAY ? 2.4f : roadClass == RoadPath.RoadClass.STREET ? 1.5f : 1.0f;
            float stroke = Math.max(minPixels, roadClass.width * scale);
            Color fill = roadClass == RoadPath.RoadClass.HIGHWAY ? highwayFill : roadFill;
            infrastructure.forEachRoadPathNear(centre.origin(), reach, path -> {
                if (path.roadClass != roadClass || path.points.size() < 2) return;
                Path2D.Float line = onMap(centre, path.points, left, top, scale);
                if (roadCasing != null && stroke > 1.4f) {
                    g.setColor(roadCasing);
                    g.setStroke(new BasicStroke(stroke + 1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(line);
                }
                g.setColor(fill);
                g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(line);
            });
        }

        // This nation's way of marking things on a map: nothing like a pin
        Random marks = new Random(styleSeed * 31L + 7L);
        int hereShape = marks.nextInt(6);
        Color hereColour = colour(WorldPalette.hsv(marks.nextFloat(), 0.75f, 0.85f));
        Color badgeColour = colour(WorldPalette.hsv(marks.nextFloat(), 0.7f, 0.55f + marks.nextFloat() * 0.3f));
        Color routeColour = colour(WorldPalette.hsv(marks.nextFloat(), 0.8f, 0.8f));
        int routeStyle = marks.nextInt(3);
        int badgeShape = marks.nextInt(3);
        float markSize = Math.max(5f, width * 0.05f);

        if (route != null && route.size() > 1) {
            Path2D.Float way = onMap(centre, route, left, top, scale);
            float w = Math.max(2f, width * 0.018f);
            g.setColor(new Color(0, 0, 0, 90));
            g.setStroke(new BasicStroke(w + 1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (routeStyle != 2) g.draw(way);
            g.setColor(routeColour);
            g.setStroke(switch (routeStyle) {
                case 0 -> new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
                case 1 -> new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 4f, new float[] { w * 3f, w * 1.6f }, 0f);
                default -> new BasicStroke(w * 1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 4f, new float[] { 0.1f, w * 2.2f }, 0f);
            });
            g.draw(way);
        }
        if (destination != null) {
            double[] d = centre.fromSphere(destination);
            badge(g, (float) (d[0] - left) * scale, (float) (d[2] - top) * scale, markSize * 1.7f, badgeColour, badgeGlyph, badgeShape);
        }
        if (here != null) {
            double[] h = centre.fromSphere(here);
            mark(g, (float) (h[0] - left) * scale, (float) (h[2] - top) * scale, markSize, hereShape, hereColour);
        }
        g.dispose();
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    /** Where the sign stands, in one of six unearthly marks. */
    private static void mark(Graphics2D g, float x, float y, float size, int shape, Color colour) {
        Color dark = colour.darker().darker();
        g.setStroke(new BasicStroke(Math.max(1f, size * 0.18f)));
        switch (shape) {
            case 0 -> {
                // An eye: a ring round a dot, with three short rays beneath
                g.setColor(colour);
                g.draw(new Ellipse2D.Float(x - size * 0.6f, y - size * 0.6f, size * 1.2f, size * 1.2f));
                g.fill(new Ellipse2D.Float(x - size * 0.25f, y - size * 0.25f, size * 0.5f, size * 0.5f));
                for (int i = -1; i <= 1; i++) {
                    g.draw(new java.awt.geom.Line2D.Float(x + i * size * 0.35f, y + size * 0.75f, x + i * size * 0.55f, y + size * 1.15f));
                }
            }
            case 1 -> {
                // Three lobes round the spot
                g.setColor(colour);
                for (int i = 0; i < 3; i++) {
                    double a = i * Math.PI * 2 / 3 - Math.PI / 2;
                    float cx = x + (float) Math.cos(a) * size * 0.5f, cy = y + (float) Math.sin(a) * size * 0.5f;
                    g.fill(new Ellipse2D.Float(cx - size * 0.35f, cy - size * 0.35f, size * 0.7f, size * 0.7f));
                }
                g.setColor(dark);
                g.fill(new Ellipse2D.Float(x - size * 0.18f, y - size * 0.18f, size * 0.36f, size * 0.36f));
            }
            case 2 -> {
                // A hexagon with a dot
                Path2D.Float hex = new Path2D.Float();
                for (int i = 0; i < 6; i++) {
                    double a = i * Math.PI / 3;
                    float px = x + (float) Math.cos(a) * size * 0.7f, py = y + (float) Math.sin(a) * size * 0.7f;
                    if (i == 0) hex.moveTo(px, py); else hex.lineTo(px, py);
                }
                hex.closePath();
                g.setColor(colour);
                g.draw(hex);
                g.setColor(dark);
                g.fill(new Ellipse2D.Float(x - size * 0.2f, y - size * 0.2f, size * 0.4f, size * 0.4f));
            }
            case 3 -> {
                // Chevrons closing in on the spot from above
                g.setColor(colour);
                for (int i = 0; i < 3; i++) {
                    float off = i * size * 0.45f;
                    Path2D.Float v = new Path2D.Float();
                    v.moveTo(x - size * 0.6f, y - size * 0.6f - off);
                    v.lineTo(x, y - off);
                    v.lineTo(x + size * 0.6f, y - size * 0.6f - off);
                    g.draw(v);
                }
            }
            case 4 -> {
                // A spiked burst
                Path2D.Float star = new Path2D.Float();
                for (int i = 0; i < 16; i++) {
                    double a = i * Math.PI / 8;
                    float r = (i % 2 == 0 ? 0.85f : 0.3f) * size;
                    float px = x + (float) Math.cos(a) * r, py = y + (float) Math.sin(a) * r;
                    if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
                }
                star.closePath();
                g.setColor(colour);
                g.fill(star);
                g.setColor(dark);
                g.setStroke(new BasicStroke(1f));
                g.draw(star);
            }
            default -> {
                // Two arcs facing each other round a dot
                g.setColor(colour);
                g.draw(new java.awt.geom.Arc2D.Float(x - size * 0.7f, y - size * 0.7f, size * 1.4f, size * 1.4f, 30, 120, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Arc2D.Float(x - size * 0.7f, y - size * 0.7f, size * 1.4f, size * 1.4f, 210, 120, java.awt.geom.Arc2D.OPEN));
                g.fill(new Ellipse2D.Float(x - size * 0.22f, y - size * 0.22f, size * 0.44f, size * 0.44f));
            }
        }
    }

    /** The advertised business: a badge in some shape holding a letter of its name. */
    private static void badge(Graphics2D g, float x, float y, float size, Color colour, BufferedImage glyph, int shape) {
        java.awt.Shape outline;
        if (shape == 0) {
            outline = new java.awt.geom.RoundRectangle2D.Float(x - size * 0.5f, y - size * 0.5f, size, size, size * 0.35f, size * 0.35f);
        } else if (shape == 1) {
            Path2D.Float diamond = new Path2D.Float();
            diamond.moveTo(x, y - size * 0.65f);
            diamond.lineTo(x + size * 0.65f, y);
            diamond.lineTo(x, y + size * 0.65f);
            diamond.lineTo(x - size * 0.65f, y);
            diamond.closePath();
            outline = diamond;
        } else {
            outline = new Ellipse2D.Float(x - size * 0.55f, y - size * 0.55f, size * 1.1f, size * 1.1f);
        }
        g.setColor(new Color(0, 0, 0, 80));
        g.translate(1.5, 1.5);
        g.fill(outline);
        g.translate(-1.5, -1.5);
        g.setColor(colour);
        g.fill(outline);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(1f, size * 0.07f)));
        g.draw(outline);
        if (glyph != null) {
            // The glyph's ink in white
            int gs = Math.max(4, Math.round(size * 0.62f));
            BufferedImage ink = new BufferedImage(glyph.getWidth(), glyph.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int j = 0; j < glyph.getHeight(); j++) {
                for (int i = 0; i < glyph.getWidth(); i++) {
                    int argb = glyph.getRGB(i, j);
                    float bright = (((argb >> 16) & 255) * 0.299f + ((argb >> 8) & 255) * 0.587f + (argb & 255) * 0.114f) / 255f;
                    float darkness = (argb >>> 24) < 128 ? 0f : 1f - bright;
                    int a = Math.max(0, Math.min(255, Math.round((darkness - 0.35f) / 0.3f * 255f)));
                    ink.setRGB(i, j, (a << 24) | 0xFFFFFF);
                }
            }
            g.drawImage(ink, Math.round(x - gs * 0.5f), Math.round(y - gs * 0.5f), gs, gs, null);
        }
    }

    /** A line through places, in the map's pixels. */
    private static Path2D.Float onMap(SphereFrame centre, List<SpherePosition> places, float left, float top, float scale) {
        Path2D.Float line = new Path2D.Float();
        for (int i = 0; i < places.size(); i++) {
            double[] p = centre.fromSphere(places.get(i));
            float px = (float) (p[0] - left) * scale, pz = (float) (p[2] - top) * scale;
            if (i == 0) line.moveTo(px, pz); else line.lineTo(px, pz);
        }
        return line;
    }

    private static float[] mix(float[] a, float[] b, float t) {
        return new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }

    private static Color colour(float[] c) {
        return new Color(clamp(c[0]), clamp(c[1]), clamp(c[2]));
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }
}
