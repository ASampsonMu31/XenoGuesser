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
import com.xenoguesser.math.Vector3;

/**
 * Top-down minimap layers of the generated world: the plain land and sea, the
 * road network and where every building stands.
 * Each is drawn once at a detail resolution (used when the minimap is zoomed
 * in) and box-filtered down to the overview resolution.
 */
public class BirdsEyeMaps {
    private static final int DETAIL_FACTOR = 3;

    // Matches the plain minimap in MapPanel
    private static final Color BASE_LAND = new Color(92, 64, 45);
    private static final Color WATER = new Color(25, 80, 160);
    private static final Color LOWLAND = new Color(214, 206, 176);
    private static final Color HIGHLAND = new Color(150, 132, 105);
    private static final float HIGHLAND_HEIGHT = 900.0f;

    private static final Color HIGHWAY_COLOUR = new Color(35, 35, 38);
    private static final Color STREET_COLOUR = new Color(78, 78, 82);
    private static final Color LANE_COLOUR = new Color(128, 106, 80);
    // Stroke widths in detail pixels (about 67 world units each), wider than true scale so roads read clearly
    private static final float HIGHWAY_STROKE = 4.2f;
    private static final float STREET_STROKE = 2.2f;
    private static final float LANE_STROKE = 2.0f;

    private static final Color BUILDING_LAND = new Color(44, 48, 52);
    private static final float MIN_BUILDING_PIXELS = 2.5f;

    private final int outputResolution;
    private final int detailResolution;
    private final float totalRegionWidth;
    private final float halfRegion;
    private final float seaLevelHeight;
    private final PerlinNoise terrainNoise;
    private float[] detailHeights;

    public BirdsEyeMaps(int outputResolution, float totalRegionWidth, float seaLevelHeight, PerlinNoise terrainNoise) {
        this.outputResolution = outputResolution;
        this.detailResolution = outputResolution * DETAIL_FACTOR;
        this.totalRegionWidth = totalRegionWidth;
        this.halfRegion = totalRegionWidth * 0.5f;
        this.seaLevelHeight = seaLevelHeight;
        this.terrainNoise = terrainNoise;
    }

    /** The plain brown and blue map, for zooming into the default minimap. Returns {overview, detail}. */
    public BufferedImage[] renderBaseMap() {
        return finish(createLandCanvas(false, BASE_LAND));
    }

    public BufferedImage[] renderRoadMap(InfrastructureManager infrastructure) {
        BufferedImage canvas = createLandCanvas(true, LOWLAND);
        Graphics2D g = createGraphics(canvas);

        drawRoadClass(g, infrastructure, RoadPath.RoadClass.LANE, LANE_COLOUR, LANE_STROKE);
        drawRoadClass(g, infrastructure, RoadPath.RoadClass.STREET, STREET_COLOUR, STREET_STROKE);
        drawRoadClass(g, infrastructure, RoadPath.RoadClass.HIGHWAY, HIGHWAY_COLOUR, HIGHWAY_STROKE);

        g.dispose();
        return finish(canvas);
    }

    private static final Color BUILDING_COLOUR = new Color(240, 228, 196);

    public BufferedImage[] renderBuildingMap(InfrastructureManager infrastructure) {
        BufferedImage canvas = createLandCanvas(false, BUILDING_LAND);
        Graphics2D g = createGraphics(canvas);
        float pixelsPerUnit = detailResolution / totalRegionWidth;
        AffineTransform identity = g.getTransform();

        // One colour for every building, so the map shows where people live rather than hinting at nations
        g.setColor(BUILDING_COLOUR);
        infrastructure.forEachBuilding((x, z, rotationY, width, depth, nationId) -> {

            float pixelWidth = Math.max(MIN_BUILDING_PIXELS, width * pixelsPerUnit);
            float pixelDepth = Math.max(MIN_BUILDING_PIXELS, depth * pixelsPerUnit);
            g.setTransform(identity);
            g.translate(worldToPixel(x), worldToPixel(z));
            // Local +X maps to world (cos, -sin), i.e. a clockwise turn on the map
            g.rotate(-Math.toRadians(rotationY));
            g.fill(new Rectangle2D.Float(-pixelWidth * 0.5f, -pixelDepth * 0.5f, pixelWidth, pixelDepth));
        });

        g.dispose();
        return finish(canvas);
    }

    private void drawRoadClass(Graphics2D g, InfrastructureManager infrastructure, RoadPath.RoadClass roadClass,
                               Color colour, float strokeWidth) {
        g.setColor(colour);
        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        infrastructure.forEachRoadPath(path -> {
            if (path.roadClass != roadClass || path.points.size() < 2) {
                return;
            }
            List<Vector3> points = path.points;
            Path2D.Float line = new Path2D.Float();
            line.moveTo(worldToPixel(points.get(0).x), worldToPixel(points.get(0).z));
            for (int i = 1; i < points.size(); i++) {
                line.lineTo(worldToPixel(points.get(i).x), worldToPixel(points.get(i).z));
            }
            g.draw(line);
        });
    }

    /** Land shaded by elevation (or flat), with the sea on top. */
    private BufferedImage createLandCanvas(boolean shadeElevation, Color flatLand) {
        float[] heights = getDetailHeights();
        BufferedImage canvas = new BufferedImage(detailResolution, detailResolution, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < detailResolution; y++) {
            for (int x = 0; x < detailResolution; x++) {
                float height = heights[y * detailResolution + x];
                int rgb;
                if (height <= seaLevelHeight) {
                    rgb = WATER.getRGB();
                } else if (shadeElevation) {
                    float t = Math.min(1.0f, (height - seaLevelHeight) / HIGHLAND_HEIGHT);
                    rgb = lerpColour(LOWLAND, HIGHLAND, (float) Math.sqrt(t));
                } else {
                    rgb = flatLand.getRGB();
                }
                canvas.setRGB(x, y, rgb);
            }
        }
        return canvas;
    }

    private synchronized float[] getDetailHeights() {
        if (detailHeights == null) {
            float[] heights = new float[detailResolution * detailResolution];
            for (int y = 0; y < detailResolution; y++) {
                float worldZ = pixelToWorld(y + 0.5f);
                for (int x = 0; x < detailResolution; x++) {
                    heights[y * detailResolution + x] = TerrainMesh.getLayeredHeight(pixelToWorld(x + 0.5f), worldZ, terrainNoise);
                }
            }
            detailHeights = heights;
        }
        return detailHeights;
    }

    /** Pairs the detail canvas with a box-filtered overview. */
    private BufferedImage[] finish(BufferedImage detail) {
        BufferedImage output = new BufferedImage(outputResolution, outputResolution, BufferedImage.TYPE_INT_RGB);
        int samples = DETAIL_FACTOR * DETAIL_FACTOR;
        for (int y = 0; y < outputResolution; y++) {
            for (int x = 0; x < outputResolution; x++) {
                int r = 0, g = 0, b = 0;
                for (int sy = 0; sy < DETAIL_FACTOR; sy++) {
                    for (int sx = 0; sx < DETAIL_FACTOR; sx++) {
                        int rgb = detail.getRGB(x * DETAIL_FACTOR + sx, y * DETAIL_FACTOR + sy);
                        r += (rgb >> 16) & 0xFF;
                        g += (rgb >> 8) & 0xFF;
                        b += rgb & 0xFF;
                    }
                }
                output.setRGB(x, y, ((r / samples) << 16) | ((g / samples) << 8) | (b / samples));
            }
        }
        return new BufferedImage[] { output, detail };
    }

    private Graphics2D createGraphics(BufferedImage canvas) {
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private float pixelToWorld(float pixel) {
        return (pixel / detailResolution) * totalRegionWidth - halfRegion;
    }

    private float worldToPixel(float world) {
        return ((world + halfRegion) / totalRegionWidth) * detailResolution;
    }

    private static int lerpColour(Color from, Color to, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        int r = Math.round(from.getRed() + (to.getRed() - from.getRed()) * t);
        int g = Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t);
        int b = Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int clampChannel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }
}
