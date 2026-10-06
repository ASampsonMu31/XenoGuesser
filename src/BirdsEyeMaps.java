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
    // The plain map's land and sea, in the world's own colours (see MapColours)
    private final Color BASE_LAND;
    private final Color WATER;
    private static final Color LOWLAND = new Color(214, 206, 176);
    private static final Color HIGHLAND = new Color(150, 132, 105);
    private static final float HIGHLAND_HEIGHT = 900.0f;

    private static final Color HIGHWAY_COLOUR = new Color(0, 0, 0);
    private static final Color STREET_COLOUR = new Color(0, 0, 0);
    private static final Color LANE_COLOUR = new Color(0, 0, 0);
    // Stroke widths in detail pixels (about 67 world units each), wider than true scale so roads read clearly
    private static final float HIGHWAY_STROKE = 4.2f;
    private static final float STREET_STROKE = 2.2f;
    private static final float LANE_STROKE = 1.1f;
    // Dirt tracks: thinner, and brown rather than black
    private static final Color TRACK_COLOUR = new Color(205, 178, 128);
    private static final float TRACK_STROKE = 0.8f;

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
        int[] colours = MapColours.of(terrainNoise.seed);
        this.WATER = new Color(colours[0]);
        this.BASE_LAND = new Color(colours[1]);
    }

    /** The plain brown and blue map, for zooming into the default minimap. Returns {overview, detail}. */
    public BufferedImage[] renderBaseMap() {
        return finish(createLandCanvas(false, BASE_LAND));
    }

    // ------------------------------------------------------------------ layers
    // Each layer is drawn on a transparent canvas, to lie over the plain map (or a gradient
    // map) along with any of the others

    private static final Color CONTOUR_LINE = new Color(255, 255, 255, 170);
    private static final Color INDEX_CONTOUR_LINE = new Color(255, 255, 255, 225);
    private static final int CONTOUR_LEVELS = 8;
    // Contours are traced on a grid this many detail pixels apart
    private static final int CONTOUR_STEP = 2;

    /**
     * Elevation as solid white contour lines, no colours: levels spaced on a square-root scale of
     * height above the sea, so the lowlands get lines close together in height and the
     * mountains don't turn to a tangle, every fourth line a little heavier. Traced over
     * heights smoothed a little so each line runs clean. Returns {overview, detail}.
     */
    public BufferedImage[] renderContourLayer() {
        int n = detailResolution;
        float[] heights = smoothed(getDetailHeights(), n, 2);
        float highest = seaLevelHeight + 1f;
        for (float h : heights) highest = Math.max(highest, h);
        float rise = highest - seaLevelHeight;
        contourRise = rise;
        // The grid traced: every CONTOUR_STEP pixels, heights as levels (level k at value k)
        int g = n / CONTOUR_STEP;
        float[] value = new float[g * g];
        for (int y = 0; y < g; y++) {
            for (int x = 0; x < g; x++) {
                float above = heights[(y * CONTOUR_STEP) * n + x * CONTOUR_STEP] - seaLevelHeight;
                value[y * g + x] = above <= 0f ? -1f : (float) Math.sqrt(above / rise) * CONTOUR_LEVELS;
            }
        }
        BufferedImage canvas = newLayer();
        Graphics2D g2 = createGraphics(canvas);
        float scale = CONTOUR_STEP;
        for (int level = 1; level < CONTOUR_LEVELS; level++) {
            boolean index = level % 4 == 0;
            g2.setColor(index ? INDEX_CONTOUR_LINE : CONTOUR_LINE);
            g2.setStroke(new BasicStroke(index ? 2.2f : 1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (Path2D.Float line : traceLevel(value, g, level)) {
                line.transform(AffineTransform.getScaleInstance(scale, scale));
                g2.draw(line);
            }
        }
        g2.dispose();
        return finishLayer(canvas);
    }

    /**
     * Marching squares: the lines where a grid of values crosses the level, joined up into
     * as long runs as they make (so they're drawn as smooth lines, not cell by cell).
     */
    private static List<Path2D.Float> traceLevel(float[] value, int g, float level) {
        // Each crossing sits on a cell edge: horizontal edge (x,y)-(x+1,y) is 2*(y*g+x), vertical (x,y)-(x,y+1) is 2*(y*g+x)+1
        java.util.Map<Integer, int[]> links = new java.util.HashMap<>();
        java.util.function.BiConsumer<Integer, Integer> link = (from, to) -> {
            int[] l = links.computeIfAbsent(from, k -> new int[] { -1, -1 });
            if (l[0] < 0) l[0] = to; else l[1] = to;
            int[] m = links.computeIfAbsent(to, k -> new int[] { -1, -1 });
            if (m[0] < 0) m[0] = from; else m[1] = from;
        };
        for (int y = 0; y + 1 < g; y++) {
            for (int x = 0; x + 1 < g; x++) {
                float a = value[y * g + x], b = value[y * g + x + 1], c = value[(y + 1) * g + x + 1], d = value[(y + 1) * g + x];
                int code = (a > level ? 1 : 0) | (b > level ? 2 : 0) | (c > level ? 4 : 0) | (d > level ? 8 : 0);
                if (code == 0 || code == 15) continue;
                int top = 2 * (y * g + x), bottom = 2 * ((y + 1) * g + x), left = 2 * (y * g + x) + 1, right = 2 * (y * g + x + 1) + 1;
                switch (code) {
                    case 1, 14 -> link.accept(left, top);
                    case 2, 13 -> link.accept(top, right);
                    case 3, 12 -> link.accept(left, right);
                    case 4, 11 -> link.accept(right, bottom);
                    case 6, 9 -> link.accept(top, bottom);
                    case 7, 8 -> link.accept(left, bottom);
                    case 5 -> { link.accept(left, top); link.accept(right, bottom); }
                    case 10 -> { link.accept(top, right); link.accept(left, bottom); }
                    default -> { }
                }
            }
        }
        List<Path2D.Float> lines = new java.util.ArrayList<>();
        java.util.Set<Integer> done = new java.util.HashSet<>();
        // Open runs first (from an end), then the closed loops left
        for (int pass = 0; pass < 2; pass++) {
            for (java.util.Map.Entry<Integer, int[]> e : links.entrySet()) {
                int start = e.getKey();
                if (done.contains(start)) continue;
                if (pass == 0 && e.getValue()[1] >= 0) continue;
                Path2D.Float line = new Path2D.Float();
                float[] p = crossing(value, g, start, level);
                line.moveTo(p[0], p[1]);
                done.add(start);
                int previous = -1, at = start;
                while (true) {
                    int[] l = links.get(at);
                    int next = l[0] != previous && !done.contains(l[0]) ? l[0] : l[1] >= 0 && l[1] != previous && !done.contains(l[1]) ? l[1] : -1;
                    if (next < 0) {
                        if (pass == 1) line.closePath();
                        break;
                    }
                    p = crossing(value, g, next, level);
                    line.lineTo(p[0], p[1]);
                    done.add(next);
                    previous = at;
                    at = next;
                }
                lines.add(line);
            }
        }
        return lines;
    }

    /** A box blur of the given radius (wrapping round east to west, as the map does). */
    private static float[] smoothed(float[] heights, int n, int radius) {
        float[] across = new float[heights.length], out = new float[heights.length];
        float count = 2 * radius + 1;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float sum = 0f;
                for (int d = -radius; d <= radius; d++) sum += heights[y * n + Math.floorMod(x + d, n)];
                across[y * n + x] = sum / count;
            }
        }
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float sum = 0f;
                for (int d = -radius; d <= radius; d++) sum += across[Math.max(0, Math.min(n - 1, y + d)) * n + x];
                out[y * n + x] = sum / count;
            }
        }
        return out;
    }

    /** Where on its edge the level is crossed, in grid units. */
    private static float[] crossing(float[] value, int g, int edge, float level) {
        int cell = edge >> 1;
        int x = cell % g, y = cell / g;
        boolean vertical = (edge & 1) == 1;
        float a = value[cell], b = vertical ? value[cell + g] : value[cell + 1];
        float t = Math.abs(b - a) < 1e-6f ? 0.5f : Math.max(0f, Math.min(1f, (level - a) / (b - a)));
        return vertical ? new float[] { x, y + t } : new float[] { x + t, y };
    }

    /**
     * Where each nation's names go on the map: the middle of each separate stretch of its
     * land (or the nearest point of it to the middle, for a ring-shaped one), the largest
     * marked as where its main name goes, the rest (its islands) getting small ones. Each is
     * {nationId, worldX, worldZ, area in world units squared, 1 for the main name or 0}.
     */
    // The smallest island (in territory cells, each a few hundred units across) given its own small name
    private static final int MIN_NAMED_ISLAND_CELLS = 12;
    private static final int MIN_NAMED_NATION_CELLS = 5;

    public List<float[]> nationLabelPlaces(NationGenerationManager nations) {
        int res = nations.nationMap.length;
        // Only dry land on the map counts: a nation's territory reaches out over its waters,
        // and the chart is cut off short of the poles
        float[] heights = getDetailHeights();
        int n = detailResolution;
        float cellSize = totalRegionWidth / res;
        boolean[] land = new boolean[res * res];
        for (int cy = 0; cy < res; cy++) {
            float worldZ = (cy + 0.5f) * cellSize - totalRegionWidth * 0.5f;
            if (Math.abs(worldZ) > Planet.clipHalfHeight() * 0.94f) continue;
            for (int cx = 0; cx < res; cx++) {
                int px = Math.min(n - 1, (int) ((cx + 0.5f) / res * n)), py = Math.min(n - 1, (int) ((cy + 0.5f) / res * n));
                land[cy * res + cx] = heights[py * n + px] > seaLevelHeight;
            }
        }
        int[] depth = new int[res * res];
        int[] queue = new int[res * res];
        int[] region = new int[res * res];
        List<float[]> places = new java.util.ArrayList<>();
        java.util.Map<Integer, float[]> biggest = new java.util.HashMap<>();
        List<float[]> pieces = new java.util.ArrayList<>();
        int regionCount = 0;
        int[] stack = new int[res * res];
        for (int start = 0; start < res * res; start++) {
            int sx = start % res, sy = start / res;
            int id = nations.nationMap[sx][sy];
            if (id == 0 || region[start] != 0 || !land[start]) continue;
            regionCount++;
            // Flood fill (wrapping east to west), summing the cells' positions as angles so a
            // region across the join averages properly
            int top = 0, count = 0;
            double sumY = 0, sumCos = 0, sumSin = 0;
            stack[top++] = start;
            region[start] = regionCount;
            List<Integer> cells = new java.util.ArrayList<>();
            while (top > 0) {
                int c = stack[--top];
                int cx = c % res, cy = c / res;
                cells.add(c);
                count++;
                sumY += cy;
                double angle = 2 * Math.PI * cx / res;
                sumCos += Math.cos(angle);
                sumSin += Math.sin(angle);
                int[][] next = { { Math.floorMod(cx + 1, res), cy }, { Math.floorMod(cx - 1, res), cy }, { cx, cy + 1 }, { cx, cy - 1 } };
                for (int[] q : next) {
                    if (q[1] < 0 || q[1] >= res) continue;
                    int k = q[1] * res + q[0];
                    if (region[k] == 0 && land[k] && nations.nationMap[q[0]][q[1]] == id) {
                        region[k] = regionCount;
                        stack[top++] = k;
                    }
                }
            }
            double meanX = Math.floorMod((long) Math.round(Math.atan2(sumSin, sumCos) / (2 * Math.PI) * res * 1000), (long) res * 1000) / 1000.0;
            double meanY = sumY / count;
            // How deep inside the region each cell is: steps from its edge (coast, border or
            // the chart's cut-off), worked outwards from the edge cells
            int head = 0, tail = 0;
            for (int c : cells) {
                int cx = c % res, cy = c / res;
                boolean edge = cy == 0 || cy == res - 1;
                int[] around = { cy * res + Math.floorMod(cx + 1, res), cy * res + Math.floorMod(cx - 1, res), c + res, c - res };
                for (int k : around) edge |= k < 0 || k >= res * res || region[k] != regionCount;
                depth[c] = edge ? 1 : 0;
                if (edge) queue[tail++] = c;
            }
            while (head < tail) {
                int c = queue[head++];
                int cx = c % res, cy = c / res;
                int[] around = { cy * res + Math.floorMod(cx + 1, res), cy * res + Math.floorMod(cx - 1, res), c + res, c - res };
                for (int k : around) {
                    if (k < 0 || k >= res * res || region[k] != regionCount || depth[k] != 0) continue;
                    depth[k] = depth[c] + 1;
                    queue[tail++] = k;
                }
            }
            // The name goes on the deepest cell (so it sits well inside the land, never on the
            // sea in a bay or a ring), the one nearest the middle among equals
            int pick = cells.get(0);
            double nearest = Double.MAX_VALUE;
            int deepest = 0;
            for (int c : cells) deepest = Math.max(deepest, depth[c]);
            for (int c : cells) {
                if (depth[c] < deepest) continue;
                double dx = Math.abs(c % res - meanX);
                dx = Math.min(dx, res - dx);
                double dy = c / res - meanY;
                double d = dx * dx + dy * dy;
                if (d < nearest) { nearest = d; pick = c; }
            }
            float[] piece = { id, pick % res + 0.5f, pick / res + 0.5f, count, 0f };
            // Islands too small to matter (specks off the coast) aren't named
            if (count >= MIN_NAMED_ISLAND_CELLS) pieces.add(piece);
            float[] best = biggest.get(id);
            if (best == null || best[3] < count) biggest.put(id, piece);
        }
        for (float[] b : biggest.values()) {
            // A nation with no more than a few specks of land isn't named: there'd be nothing to see under its name
            if (b[3] < MIN_NAMED_NATION_CELLS) {
                pieces.remove(b);
                continue;
            }
            b[4] = 1f;
            if (!pieces.contains(b)) pieces.add(b);
        }
        float cell = totalRegionWidth / res;
        for (float[] b : pieces) {
            places.add(new float[] { b[0], b[1] * cell - totalRegionWidth * 0.5f, b[2] * cell - totalRegionWidth * 0.5f, b[3] * cell * cell, b[4] });
        }
        return places;
    }

    /**
     * The nations overlay: each nation's land filled in its own colour, the sea as on the
     * plain map, at the detail resolution so the borders stay smooth however far the map is
     * zoomed. Rows are worked out side by side.
     */
    public BufferedImage renderNationOverlay(NationGenerationManager nations) {
        float[] heights = getDetailHeights();
        int n = detailResolution;
        int[] pixels = new int[n * n];
        java.util.stream.IntStream.range(0, n).parallel().forEach(y -> {
            float worldZ = pixelToWorld(y + 0.5f);
            for (int x = 0; x < n; x++) {
                if (heights[y * n + x] <= seaLevelHeight) {
                    pixels[y * n + x] = WATER.getRGB();
                } else {
                    int id = nations.getNationAtWorld(pixelToWorld(x + 0.5f), worldZ, totalRegionWidth);
                    pixels[y * n + x] = nations.getNationColor(id).getRGB() | 0xFF000000;
                }
            }
        });
        BufferedImage canvas = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        canvas.setRGB(0, 0, n, n, pixels, 0, n);
        return canvas;
    }

    public BufferedImage[] renderRoadLayer(InfrastructureManager infrastructure) {
        BufferedImage canvas = newLayer();
        Graphics2D g = createGraphics(canvas);
        g.setStroke(new BasicStroke(0.9f));
        drawRoadClass(g, infrastructure, RoadPath.RoadClass.DIRT, TRACK_COLOUR, TRACK_STROKE);
        drawRoadClass(g, infrastructure, RoadPath.RoadClass.LANE, LANE_COLOUR, LANE_STROKE);
        drawRoadClass(g, infrastructure, RoadPath.RoadClass.STREET, STREET_COLOUR, STREET_STROKE);
        drawRoadClass(g, infrastructure, RoadPath.RoadClass.HIGHWAY, HIGHWAY_COLOUR, HIGHWAY_STROKE);
        g.dispose();
        return finishLayer(canvas);
    }

    private static final Color BUILDING_COLOUR = new Color(140, 140, 144);

    public BufferedImage[] renderBuildingLayer(InfrastructureManager infrastructure) {
        BufferedImage canvas = newLayer();
        Graphics2D g = createGraphics(canvas);
        // One colour for every building, so the map shows where people live rather than hinting at nations
        infrastructure.forEachBuilding(buildingDrawer(g, BUILDING_COLOUR, MIN_BUILDING_PIXELS));
        g.dispose();
        return finishLayer(canvas);
    }

    private static final Color COMMERCIAL_ZONE = new Color(255, 170, 60, 80);
    private static final Color SHOP_COLOUR = new Color(255, 110, 20);

    /** Where the shopping is: every shop in bright orange, over a warm glow marking the commercial districts round them. */
    public BufferedImage[] renderShopLayer(InfrastructureManager infrastructure) {
        BufferedImage canvas = newLayer();
        Graphics2D g = createGraphics(canvas);
        float pixelsPerUnit = detailResolution / totalRegionWidth;
        // The districts: a soft disc round each shop, overlapping into zones (drawn opaque on
        // their own canvas first so overlaps don't build up)
        BufferedImage zones = newLayer();
        Graphics2D z = createGraphics(zones);
        z.setColor(Color.WHITE);
        float glow = Math.max(4f, 120f * pixelsPerUnit);
        infrastructure.forEachShop((x, zz, rotationY, width, depth, nationId) -> {
            float px = worldToPixel((float) Planet.wrapX(x)), pz = worldToPixel(zz);
            z.fill(new Ellipse2D.Float(px - glow, pz - glow, glow * 2, glow * 2));
        });
        z.dispose();
        int zone = COMMERCIAL_ZONE.getRGB();
        for (int y = 0; y < detailResolution; y++) {
            for (int x = 0; x < detailResolution; x++) {
                int a = zones.getRGB(x, y) >>> 24;
                if (a > 0) canvas.setRGB(x, y, (zone & 0xFFFFFF) | ((COMMERCIAL_ZONE.getAlpha() * a / 255) << 24));
            }
        }
        infrastructure.forEachShop(buildingDrawer(g, SHOP_COLOUR, MIN_BUILDING_PIXELS + 1f));
        g.dispose();
        return finishLayer(canvas);
    }

    /** Draws each building visited as its footprint, at least minimumPixels across. */
    private InfrastructureManager.BuildingVisitor buildingDrawer(Graphics2D g, Color colour, float minimumPixels) {
        float pixelsPerUnit = detailResolution / totalRegionWidth;
        AffineTransform identity = g.getTransform();
        return (x, z, rotationY, width, depth, nationId) -> {
            float pixelWidth = Math.max(minimumPixels, width * pixelsPerUnit);
            float pixelDepth = Math.max(minimumPixels, depth * pixelsPerUnit);
            g.setTransform(identity);
            // Anything built past the map's join is drawn where it is on the planet
            g.translate(worldToPixel((float) Planet.wrapX(x)), worldToPixel(z));
            // Local +X maps to world (cos, -sin), i.e. a clockwise turn on the map
            g.rotate(-Math.toRadians(rotationY));
            g.setColor(colour);
            g.fill(new Rectangle2D.Float(-pixelWidth * 0.5f, -pixelDepth * 0.5f, pixelWidth, pixelDepth));
        };
    }

    private BufferedImage newLayer() {
        return new BufferedImage(detailResolution, detailResolution, BufferedImage.TYPE_INT_ARGB);
    }

    /** Pairs a transparent detail canvas with a box-filtered overview (colours weighted by how solid they are). */
    private BufferedImage[] finishLayer(BufferedImage detail) {
        BufferedImage output = new BufferedImage(outputResolution, outputResolution, BufferedImage.TYPE_INT_ARGB);
        int samples = DETAIL_FACTOR * DETAIL_FACTOR;
        for (int y = 0; y < outputResolution; y++) {
            for (int x = 0; x < outputResolution; x++) {
                long r = 0, g = 0, b = 0, a = 0;
                for (int sy = 0; sy < DETAIL_FACTOR; sy++) {
                    for (int sx = 0; sx < DETAIL_FACTOR; sx++) {
                        int argb = detail.getRGB(x * DETAIL_FACTOR + sx, y * DETAIL_FACTOR + sy);
                        int alpha = argb >>> 24;
                        a += alpha;
                        r += ((argb >> 16) & 0xFF) * alpha;
                        g += ((argb >> 8) & 0xFF) * alpha;
                        b += (argb & 0xFF) * alpha;
                    }
                }
                if (a == 0) continue;
                // A little more solid than a plain average, so thin lines don't fade away
                int alpha = (int) Math.min(255, a * 3 / (samples * 2));
                output.setRGB(x, y, alpha << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a));
            }
        }
        return new BufferedImage[] { output, detail };
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
            // ...and a road running over the map's join is drawn again from the other side
            float pixelsRound = totalRegionWidth * detailResolution / totalRegionWidth;
            for (Vector3 p : points) {
                if (Math.abs(p.x) > totalRegionWidth * 0.5f) {
                    float shift = p.x > 0 ? -pixelsRound : pixelsRound;
                    g.translate(shift, 0);
                    g.draw(line);
                    g.translate(-shift, 0);
                    break;
                }
            }
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

    // ------------------------------------------------------------------ tiles (see MapTiles)
    // Once the map is zoomed in past what the whole images show sharply, it's drawn in tiles,
    // each made afresh at the detail it's seen at: the ground sampled for each of its pixels,
    // roads and buildings drawn to scale, overlays smoothly enlarged under a sharp coast

    private volatile float contourRise = Float.NaN;
    private final java.util.Map<String, float[]> tileHeights = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, float[]>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, float[]> eldest) {
                    return size() > 96;
                }
            });

    /** The world x (or z) at a tile pixel's middle (its pixel column or row, -1 to size). */
    private float tileWorld(int level, int t, float pixel, int size) {
        return ((t + (pixel + 0.5f) / size) / (1 << level)) * totalRegionWidth - halfRegion;
    }

    /** The ground's height at each of a tile's pixels, with a pixel's border all round ((size+2) squared). */
    private float[] tileHeights(int level, int tx, int ty, int size) {
        String key = level + "/" + tx + "/" + ty;
        float[] heights = tileHeights.get(key);
        if (heights != null) return heights;
        int w = size + 2;
        float[] h = new float[w * w];
        for (int y = 0; y < w; y++) {
            float worldZ = tileWorld(level, ty, y - 1, size);
            for (int x = 0; x < w; x++) h[y * w + x] = TerrainMesh.getLayeredHeight(tileWorld(level, tx, x - 1, size), worldZ, terrainNoise);
        }
        tileHeights.put(key, h);
        return h;
    }

    /** How many times more tile pixels there are to a world unit than detail-image pixels. */
    private float tileMagnification(int level, int size) {
        return (float) (1 << level) * size / detailResolution;
    }

    /** The plain land and sea. */
    public MapTiles.Renderer baseTiles() {
        return (level, tx, ty, size) -> {
            float[] h = tileHeights(level, tx, ty, size);
            int w = size + 2;
            BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    tile.setRGB(x, y, h[(y + 1) * w + x + 1] <= seaLevelHeight ? WATER.getRGB() : BASE_LAND.getRGB());
                }
            }
            return tile;
        };
    }

    /**
     * An overlay at chunk resolution (one pixel a chunk, from the far west and north chunk on),
     * enlarged smoothly over the land, the sea left plain, so its gradients don't show blocks.
     */
    public MapTiles.Renderer overlayTiles(BufferedImage chunkImage, float chunkSize) {
        int minChunk = (int) Math.floor(-halfRegion / chunkSize);
        int ow = chunkImage.getWidth(), oh = chunkImage.getHeight();
        int[] colours = chunkImage.getRGB(0, 0, ow, oh, null, 0, ow);
        return (level, tx, ty, size) -> {
            float[] h = tileHeights(level, tx, ty, size);
            int w = size + 2;
            BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < size; y++) {
                float fz = tileWorld(level, ty, y, size) / chunkSize - 0.5f - minChunk;
                int z0 = (int) Math.floor(fz);
                float tz = fz - z0;
                for (int x = 0; x < size; x++) {
                    if (h[(y + 1) * w + x + 1] <= seaLevelHeight) {
                        tile.setRGB(x, y, WATER.getRGB());
                        continue;
                    }
                    float fx = tileWorld(level, tx, x, size) / chunkSize - 0.5f - minChunk;
                    int x0 = (int) Math.floor(fx);
                    float txf = fx - x0;
                    float r = 0, g = 0, b = 0, weight = 0;
                    for (int k = 0; k < 4; k++) {
                        int cx = Math.max(0, Math.min(ow - 1, x0 + (k & 1))), cz = Math.max(0, Math.min(oh - 1, z0 + (k >> 1)));
                        int c = colours[cz * ow + cx];
                        if ((c >>> 24) == 0) continue;
                        float wgt = ((k & 1) == 1 ? txf : 1 - txf) * ((k >> 1) == 1 ? tz : 1 - tz);
                        r += ((c >> 16) & 0xFF) * wgt;
                        g += ((c >> 8) & 0xFF) * wgt;
                        b += (c & 0xFF) * wgt;
                        weight += wgt;
                    }
                    tile.setRGB(x, y, weight <= 1e-4f ? BASE_LAND.getRGB()
                            : ((int) (r / weight) << 16) | ((int) (g / weight) << 8) | (int) (b / weight));
                }
            }
            return tile;
        };
    }

    /** Each nation in its colour, borders as sharp as the tile. */
    public MapTiles.Renderer nationTiles(NationGenerationManager nations) {
        return (level, tx, ty, size) -> {
            float[] h = tileHeights(level, tx, ty, size);
            int w = size + 2;
            BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < size; y++) {
                float worldZ = tileWorld(level, ty, y, size);
                for (int x = 0; x < size; x++) {
                    if (h[(y + 1) * w + x + 1] <= seaLevelHeight) {
                        tile.setRGB(x, y, WATER.getRGB());
                    } else {
                        int id = nations.getNationAtWorld(tileWorld(level, tx, x, size), worldZ, totalRegionWidth);
                        tile.setRGB(x, y, nations.getNationColor(id).getRGB());
                    }
                }
            }
            return tile;
        };
    }

    /** The contour lines, traced from the tile's own heights (the same levels as the whole map's). */
    public MapTiles.Renderer contourTiles() {
        return (level, tx, ty, size) -> {
            int w = size + 2;
            float[] heights = smoothedSquare(tileHeights(level, tx, ty, size), w);
            float rise = contourRise;
            if (Float.isNaN(rise)) {
                float highest = seaLevelHeight + 1f;
                for (float h : getDetailHeights()) highest = Math.max(highest, h);
                rise = highest - seaLevelHeight;
                contourRise = rise;
            }
            float[] value = new float[w * w];
            for (int i = 0; i < value.length; i++) {
                float above = heights[i] - seaLevelHeight;
                value[i] = above <= 0f ? -1f : (float) Math.sqrt(above / rise) * CONTOUR_LEVELS;
            }
            BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = createGraphics(tile);
            g2.translate(-0.5f, -0.5f);
            float thicken = (float) Math.pow(tileMagnification(level, size), 0.35);
            for (int lv = 1; lv < CONTOUR_LEVELS; lv++) {
                boolean index = lv % 4 == 0;
                g2.setColor(index ? INDEX_CONTOUR_LINE : CONTOUR_LINE);
                g2.setStroke(new BasicStroke((index ? 2.2f : 1.5f) * thicken, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (Path2D.Float line : traceLevel(value, w, lv)) g2.draw(line);
            }
            g2.dispose();
            return tile;
        };
    }

    /** A light blur (radius one) of a square grid, edges held. */
    private static float[] smoothedSquare(float[] v, int w) {
        float[] out = new float[v.length];
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= w) continue;
                        sum += v[ny * w + nx];
                        count++;
                    }
                }
                out[y * w + x] = sum / count;
            }
        }
        return out;
    }

    /** A transparent tile with a world-to-tile-pixel transform set on its graphics. */
    private Graphics2D tileGraphics(BufferedImage tile, int level, int tx, int ty, int size) {
        Graphics2D g = createGraphics(tile);
        double perUnit = (double) (1 << level) * size / totalRegionWidth;
        g.scale(perUnit, perUnit);
        g.translate(halfRegion - (double) tx * totalRegionWidth / (1 << level), halfRegion - (double) ty * totalRegionWidth / (1 << level));
        return g;
    }

    /** The world rectangle a tile covers, with a margin: {minX, minZ, maxX, maxZ}. */
    private float[] tileBounds(int level, int tx, int ty, float margin) {
        float span = totalRegionWidth / (1 << level);
        float x0 = tx * span - halfRegion, z0 = ty * span - halfRegion;
        return new float[] { x0 - margin, z0 - margin, x0 + span + margin, z0 + span + margin };
    }

    /** The roads, drawn to the tile's scale (their lines thicken a little as it zooms, not fully). */
    public MapTiles.Renderer roadTiles(InfrastructureManager infrastructure) {
        return (level, tx, ty, size) -> {
            BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tileGraphics(tile, level, tx, ty, size);
            float detailToWorld = totalRegionWidth / detailResolution;
            float widen = (float) Math.pow(tileMagnification(level, size), 0.45) / tileMagnification(level, size);
            float[] bounds = tileBounds(level, tx, ty, 200f);
            Object[][] classes = { { RoadPath.RoadClass.DIRT, TRACK_COLOUR, TRACK_STROKE }, { RoadPath.RoadClass.LANE, LANE_COLOUR, LANE_STROKE },
                    { RoadPath.RoadClass.STREET, STREET_COLOUR, STREET_STROKE }, { RoadPath.RoadClass.HIGHWAY, HIGHWAY_COLOUR, HIGHWAY_STROKE } };
            for (Object[] rc : classes) {
                g.setColor((Color) rc[1]);
                g.setStroke(new BasicStroke((Float) rc[2] * detailToWorld * Math.max(widen, 1f / tileMagnification(level, size)),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                infrastructure.forEachRoadPath(path -> {
                    if (path.roadClass != rc[0] || path.points.size() < 2) return;
                    float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                    for (Vector3 p : path.points) {
                        minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                        minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
                    }
                    if (maxX < bounds[0] || minX > bounds[2] || maxZ < bounds[1] || minZ > bounds[3]) return;
                    Path2D.Float line = new Path2D.Float();
                    line.moveTo(path.points.get(0).x, path.points.get(0).z);
                    for (int i = 1; i < path.points.size(); i++) line.lineTo(path.points.get(i).x, path.points.get(i).z);
                    g.draw(line);
                });
            }
            g.dispose();
            return tile;
        };
    }

    /** Buildings (or shops) to scale, each at least a few pixels across. */
    private MapTiles.Renderer footprintTiles(java.util.function.BiConsumer<InfrastructureManager, InfrastructureManager.BuildingVisitor> each,
                                             InfrastructureManager infrastructure, Color colour, float minimumDetailPixels) {
        return (level, tx, ty, size) -> {
            BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tileGraphics(tile, level, tx, ty, size);
            AffineTransform base = g.getTransform();
            float[] bounds = tileBounds(level, tx, ty, 120f);
            float minimum = minimumDetailPixels * totalRegionWidth / detailResolution
                    * (float) Math.pow(tileMagnification(level, size), 0.4) / tileMagnification(level, size);
            g.setColor(colour);
            each.accept(infrastructure, (x, z, rotationY, width, depth, nationId) -> {
                float wx = (float) Planet.wrapX(x);
                if (wx < bounds[0] || wx > bounds[2] || z < bounds[1] || z > bounds[3]) return;
                g.setTransform(base);
                g.translate(wx, z);
                g.rotate(-Math.toRadians(rotationY));
                float pw = Math.max(minimum, width), pd = Math.max(minimum, depth);
                g.fill(new Rectangle2D.Float(-pw * 0.5f, -pd * 0.5f, pw, pd));
            });
            g.dispose();
            return tile;
        };
    }

    public MapTiles.Renderer buildingTiles(InfrastructureManager infrastructure) {
        return footprintTiles(InfrastructureManager::forEachBuilding, infrastructure, BUILDING_COLOUR, MIN_BUILDING_PIXELS);
    }

    public MapTiles.Renderer shopTiles(InfrastructureManager infrastructure) {
        MapTiles.Renderer shops = footprintTiles(InfrastructureManager::forEachShop, infrastructure, SHOP_COLOUR, MIN_BUILDING_PIXELS + 1f);
        return (level, tx, ty, size) -> {
            // The districts' glow under the shops
            BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tileGraphics(tile, level, tx, ty, size);
            float[] bounds = tileBounds(level, tx, ty, 200f);
            g.setColor(COMMERCIAL_ZONE);
            java.awt.geom.Area zones = new java.awt.geom.Area();
            infrastructure.forEachShop((x, z, rotationY, width, depth, nationId) -> {
                float wx = (float) Planet.wrapX(x);
                if (wx < bounds[0] || wx > bounds[2] || z < bounds[1] || z > bounds[3]) return;
                zones.add(new java.awt.geom.Area(new Ellipse2D.Float(wx - 120f, z - 120f, 240f, 240f)));
            });
            g.fill(zones);
            g.dispose();
            Graphics2D over = tile.createGraphics();
            over.drawImage(shops.render(level, tx, ty, size), 0, 0, null);
            over.dispose();
            return tile;
        };
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
