import com.xenoguesser.math.Vector3;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
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
     * Draws the map, centred on (centreX, centreZ) and halfWidth world units from middle to
     * left and right edges, as ARGB pixels in rows from the top. North (-z) is up.
     */
    public static int[] render(InfrastructureManager infrastructure, PerlinNoise terrain, float seaLevel, long styleSeed,
                               float centreX, float centreZ, float halfWidth, int width, int height,
                               boolean pin, float pinX, float pinZ) {
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
        float left = centreX - halfWidth, top = centreZ - halfHeight;

        float reliefScale = Math.min(1f, unitsPerPixel / 12f);
        // The land, from a coarse grid of heights filled in between
        int step = 2;
        int gw = width / step + 2, gh = height / step + 2;
        float[] heights = new float[gw * gh];
        for (int j = 0; j < gh; j++) {
            for (int i = 0; i < gw; i++) {
                heights[j * gw + i] = TerrainMesh.getLayeredHeight(left + i * step * unitsPerPixel, top + j * step * unitsPerPixel, terrain);
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
        AffineTransform toMap = new AffineTransform(scale, 0, 0, scale, -left * scale, -top * scale);
        float right = left + halfWidth * 2f, bottom = top + halfHeight * 2f;
        float margin = 60f;

        // Buildings
        g.setColor(building);
        AffineTransform base = g.getTransform();
        infrastructure.forEachBuilding((x, z, rotationY, w, d, nationId) -> {
            if (x < left - margin || x > right + margin || z < top - margin || z > bottom + margin) return;
            g.setTransform(base);
            g.transform(toMap);
            g.translate(x, z);
            g.rotate(-Math.toRadians(rotationY));
            float pw = Math.max(w, 2.2f * unitsPerPixel), pd = Math.max(d, 2.2f * unitsPerPixel);
            g.fill(new Rectangle2D.Float(-pw * 0.5f, -pd * 0.5f, pw, pd));
        });
        g.setTransform(base);

        // Roads: lanes, then streets, then highways on top
        for (RoadPath.RoadClass roadClass : new RoadPath.RoadClass[] { RoadPath.RoadClass.LANE, RoadPath.RoadClass.STREET, RoadPath.RoadClass.HIGHWAY }) {
            float minPixels = roadClass == RoadPath.RoadClass.HIGHWAY ? 2.4f : roadClass == RoadPath.RoadClass.STREET ? 1.5f : 1.0f;
            float stroke = Math.max(minPixels, roadClass.width * scale);
            Color fill = roadClass == RoadPath.RoadClass.HIGHWAY ? highwayFill : roadFill;
            infrastructure.forEachRoadPath(path -> {
                if (path.roadClass != roadClass || path.points.size() < 2 || !near(path.points, left, top, right, bottom, margin)) return;
                Path2D.Float line = new Path2D.Float();
                Vector3 first = path.points.get(0);
                line.moveTo((first.x - left) * scale, (first.z - top) * scale);
                for (int i = 1; i < path.points.size(); i++) {
                    Vector3 p = path.points.get(i);
                    line.lineTo((p.x - left) * scale, (p.z - top) * scale);
                }
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

        // You are here
        if (pin) {
            float px = (pinX - left) * scale, py = (pinZ - top) * scale;
            float size = Math.max(5f, width * 0.07f);
            Path2D.Float drop = new Path2D.Float();
            drop.moveTo(px, py);
            drop.curveTo(px - size * 0.35f, py - size * 0.8f, px - size * 0.55f, py - size * 1.1f, px - size * 0.55f, py - size * 1.45f);
            drop.curveTo(px - size * 0.55f, py - size * 2.05f, px + size * 0.55f, py - size * 2.05f, px + size * 0.55f, py - size * 1.45f);
            drop.curveTo(px + size * 0.55f, py - size * 1.1f, px + size * 0.35f, py - size * 0.8f, px, py);
            g.setColor(new Color(0, 0, 0, 70));
            g.fill(new Ellipse2D.Float(px - size * 0.3f, py - size * 0.12f, size * 0.6f, size * 0.24f));
            g.setColor(new Color(214, 38, 32));
            g.fill(drop);
            g.setColor(new Color(110, 14, 10));
            g.setStroke(new BasicStroke(Math.max(0.8f, size * 0.06f)));
            g.draw(drop);
            g.setColor(Color.WHITE);
            float dot = size * 0.42f;
            g.fill(new Ellipse2D.Float(px - dot * 0.5f, py - size * 1.45f - dot * 0.5f, dot, dot));
        }
        g.dispose();
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    private static boolean near(List<Vector3> points, float left, float top, float right, float bottom, float margin) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Vector3 p : points) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }
        return maxX >= left - margin && minX <= right + margin && maxZ >= top - margin && minZ <= bottom + margin;
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
