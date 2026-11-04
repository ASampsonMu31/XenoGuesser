import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * What passes each of the tutorial's exercises: for a site, every place on the map a guess there
 * would pass (worked out over a grid laid on the map's chart, in the background as the round
 * begins), so the results can say at once whether the guess passed and wash every such place in
 * green on the globe; and the words filled into the results' explanation.
 */
final class TutorialChecks extends GamePart {

    TutorialChecks(XenoGuesser_GLEventListener game) {
        super(game);
    }

    // The grid's cells across the chart's width and height (square, as the map image is)
    static final int SIZE = 900;

    // The green that passing places are washed in
    private static final int PASS_GREEN = new java.awt.Color(70, 230, 95, 150).getRGB();

    // How near the guess must come to the reading or the map's value at the site: degrees, metres,
    // and shares of a heatmap's colours
    static final float TEMPERATURE_TOLERANCE = 2.5f, ALTITUDE_TOLERANCE = 8f, SHARE_TOLERANCE = 0.08f;
    // A plant's or animal's share of its range from where it's plentiful (green on its map)
    static final float PLENTIFUL = 0.6f;
    // How far a coast may be turned from the site's (the way inland, so which side the sea is on
    // counts) and still match (degrees), and the
    // distance its run is measured over either way (world units, before the chart's stretching)
    static final double COAST_ANGLE_TOLERANCE = 20.0, COAST_RUN = 1500.0;
    // How near the landmark a guess must be (world units)
    static final double LANDMARK_REACH = 3000.0;

    /** What passes: a cell of the grid each place that does, the wash showing them, and the words for the explanation. */
    record Check(boolean[] pass, BufferedImage wash, String[] vars) {

        /** Whether a guess at a direction passes. */
        boolean passes(GeodesicChunkGrid.Direction d) {
            if (d == null) return false;
            int cell = cellOf(d);
            return cell >= 0 && pass[cell];
        }
    }

    /** The grid cell of a direction, or -1 off the chart. */
    static int cellOf(GeodesicChunkGrid.Direction d) {
        double[] m = MapProjection.toMap(d);
        double width = Planet.width();
        int x = (int) Math.floor((m[0] / width + 0.5) * SIZE), y = (int) Math.floor((m[1] / width + 0.5) * SIZE);
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return -1;
        return y * SIZE + x;
    }

    /** The middle of a grid cell, as a direction. */
    static GeodesicChunkGrid.Direction direction(int x, int y) {
        double width = Planet.width();
        return MapProjection.direction(((x + 0.5) / SIZE - 0.5) * width, ((y + 0.5) / SIZE - 0.5) * width);
    }

    // ------------------------------------------------------------------ what's measured

    private boolean land(GeodesicChunkGrid.Direction d) {
        return TerrainMesh.getLayeredHeight(d, game.worldNoise) > game.seaLevelHeight;
    }

    /** The temperature heatmap's reading in a direction (degrees). */
    float heatmapTemperature(GeodesicChunkGrid.Direction d) {
        return -30f + 70f * game.regionalManager.temperatureMap.evaluate(d);
    }

    /** The ground's height above the sea in metres, as the altimeter reads it. */
    float metresUp(GeodesicChunkGrid.Direction d) {
        return (TerrainMesh.getLayeredHeight(d, game.worldNoise) - game.seaLevelHeight) / PlayerBody.METRE;
    }

    private float share(MapOverlay overlay, GeodesicChunkGrid.Direction d) {
        RegionalFactor f = game.mapOverlays.factorFor(overlay);
        return f == null ? 0f : f.evaluate(d);
    }

    /** Whether a guess in a direction passes. */
    @FunctionalInterface
    interface Test {
        boolean at(GeodesicChunkGrid.Direction d);
    }

    /** An exercise's rule at a site: what passes, and the words for its explanation. */
    record Rule(Test test, String[] vars) {
    }

    // ------------------------------------------------------------------ the checks

    /** Everything that passes an exercise at a site (slow: called in the background). */
    Check prepare(TutorialPlan.Site site) {
        if (site.stage() == TutorialPlan.Stage.COAST) {
            Coast coast = coast(site);
            return new Check(coast.pass, wash(coast.pass),
                    new String[] { "coastline", coast.described, "coastdistance", Math.round(coast.distance / PlayerBody.METRE) + " m" });
        }
        Rule rule = rule(site);
        boolean[] pass = grid(rule.test());
        return new Check(pass, wash(pass), rule.vars());
    }

    /** The rule of every exercise but the coast's (whose is worked out on the grid as a whole: see coast). */
    Rule rule(TutorialPlan.Site site) {
        SpherePosition at = site.place();
        GeodesicChunkGrid.Direction here = SphereMath.direction(at);
        Test pass;
        String[] vars;
        switch (site.stage()) {
            case SUN -> {
                boolean north = Planet.latitude(at) > 0;
                pass = d -> (d.y() > 0) == north;
                vars = new String[] { "hemisphere", north ? "northern" : "southern", "sunside", north ? "south" : "north" };
            }
            case TEMPERATURE -> {
                float reading = game.temperatureAt(SphereMath.atAltitude(at, TerrainMesh.getLayeredHeight(at, game.worldNoise)));
                pass = d -> land(d) && Math.abs(heatmapTemperature(d) - reading) <= TEMPERATURE_TOLERANCE;
                vars = new String[] { "temperature", celsius(reading), "tolerance", celsius(TEMPERATURE_TOLERANCE) };
            }
            case ALTITUDE -> {
                float metres = metresUp(here);
                pass = d -> land(d) && Math.abs(metresUp(d) - metres) <= ALTITUDE_TOLERANCE;
                vars = new String[] { "altitude", Math.round(metres) + " m" };
            }
            case WILDLIFE -> {
                int plant = site.integer("plant", 0), animal = site.integer("animal", 0);
                MapOverlay p = new MapOverlay(FactorName.FLORA, plant), a = new MapOverlay(FactorName.ANIMAL_POPULATION, animal);
                float plentiful = (float) site.number("plentiful", PLENTIFUL);
                pass = d -> land(d) && share(p, d) >= plentiful && share(a, d) >= plentiful;
                vars = new String[] { "plant", game.floraName(plant), "animal", game.organismManager.speciesName(animal) };
            }
            case RAINFALL -> {
                MapOverlay rain = MapOverlay.of(FactorName.RAINFALL);
                float wet = share(rain, here);
                pass = d -> land(d) && Math.abs(share(rain, d) - wet) <= SHARE_TOLERANCE;
                float mm = game.mapOverlays.heatmapMeasure(rain).sample(here);
                vars = new String[] { "rainfall", Math.round(mm / 50f) * 50 + " mm" };
            }
            case GROUND -> {
                WorldPalette palette = game.worldArt != null ? game.worldArt.palette() : new WorldPalette(game.worldSeed);
                int soil = game.mapOverlays.soilColourAt(palette, here);
                MapOverlay rocks = MapOverlay.of(FactorName.ROCKS);
                float rocky = share(rocks, here);
                pass = d -> land(d) && colourDistance(game.mapOverlays.soilColourAt(palette, d), soil) <= 0.06f
                        && Math.abs(share(rocks, d) - rocky) <= 2f * SHARE_TOLERANCE;
                vars = new String[0];
            }
            case GRASS -> {
                MapOverlay colour = MapOverlay.of(FactorName.GRASS_COLOUR), height = MapOverlay.of(FactorName.GRASS_HEIGHT);
                float c0 = share(colour, here), h0 = share(height, here);
                pass = d -> land(d) && Math.abs(share(colour, d) - c0) <= SHARE_TOLERANCE * 1.25f
                        && Math.abs(share(height, d) - h0) <= SHARE_TOLERANCE * 1.5f;
                vars = new String[0];
            }
            case WEALTH -> {
                MapOverlay wealth = MapOverlay.of(FactorName.WEALTH);
                float w0 = share(wealth, here);
                pass = d -> land(d) && game.nationManager.getNationAt(d) > 0 && Math.abs(share(wealth, d) - w0) <= SHARE_TOLERANCE * 1.25f;
                vars = new String[0];
            }
            case FLAGS -> {
                int nation = game.nationManager.getNationAt(here);
                pass = d -> land(d) && game.nationManager.getNationAt(d) == nation;
                vars = new String[0];
            }
            case ROADSIDE -> {
                BirdsEyeMaps.DesignAt bollards = game.mapOverlays.designKey(FactorName.BOLLARDS), lines = game.mapOverlays.designKey(FactorName.ROAD_LINES);
                int nation = game.nationManager.getNationAt(here);
                int b0 = bollards.at(nation, here), l0 = lines.at(nation, here);
                pass = d -> {
                    int n = game.nationManager.getNationAt(d);
                    return n > 0 && land(d) && bollards.at(n, d) == b0 && lines.at(n, d) == l0;
                };
                vars = new String[0];
            }
            case ARCHITECTURE -> {
                BirdsEyeMaps.DesignAt style = game.mapOverlays.designKey(FactorName.ARCHITECTURE);
                int nation = game.nationManager.getNationAt(here);
                int s0 = style.at(nation, here);
                pass = d -> {
                    int n = game.nationManager.getNationAt(d);
                    return n > 0 && land(d) && style.at(n, d) == s0;
                };
                vars = new String[0];
            }
            default -> {
                Landmark landmark = game.infraManager.landmarks().get(site.integer("landmark", 0));
                SpherePosition lm = SphereMath.atAltitude(landmark.place(), 0.0);
                pass = d -> SphereMath.distance(SphereMath.atAltitude(d, 0.0), lm) <= LANDMARK_REACH;
                vars = new String[] { "landmark", landmark.name == null || landmark.name.isEmpty() ? "the landmark" : landmark.name };
            }
        }
        return new Rule(pass, vars);
    }

    /** Whether each cell of the grid passes a test (only those within the map's top and bottom edges can). */
    private boolean[] grid(Test test) {
        boolean[] pass = new boolean[SIZE * SIZE];
        java.util.stream.IntStream.range(0, SIZE).parallel().forEach(y -> {
            for (int x = 0; x < SIZE; x++) {
                GeodesicChunkGrid.Direction d = direction(x, y);
                if (Math.abs(Math.asin(Math.max(-1.0, Math.min(1.0, d.y())))) > MapProjection.CLIP_LATITUDE) continue;
                pass[y * SIZE + x] = test.at(d);
            }
        });
        return pass;
    }

    /** The passing cells washed in green, the rest clear: laid over the map's chart (the same square). */
    private static BufferedImage wash(boolean[] pass) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) row[x] = pass[y * SIZE + x] ? PASS_GREEN : 0;
            image.setRGB(0, y, SIZE, 1, row, 0, SIZE);
        }
        return image;
    }

    /** How far apart two opaque colours are, 0 the same to about 1.7 (black and white). */
    private static float colourDistance(int a, int b) {
        float dr = ((a >> 16 & 255) - (b >> 16 & 255)) / 255f, dg = ((a >> 8 & 255) - (b >> 8 & 255)) / 255f, db = ((a & 255) - (b & 255)) / 255f;
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    static String celsius(float degrees) {
        return String.format(Locale.ROOT, "%.1f°C", degrees);
    }

    // ------------------------------------------------------------------ the coast

    /** The coast's check: what passes, how the site's coast runs (in words), and how far the pod came down from it. */
    private record Coast(boolean[] pass, String described, double distance) {
    }

    /**
     * The coast's check, on the grid: each coast cell's line (worked out over COAST_RUN either
     * way, from which side of it the land lies); every cell's distance to its nearest coast
     * cell, along the ground; and passing, every cell no further from a coast running within
     * COAST_ANGLE_TOLERANCE of the site's than four times the pod's distance from the sea (or
     * out to sea, twice).
     */
    private Coast coast(TutorialPlan.Site site) {
        int n = SIZE;
        boolean[] land = grid(this::land);
        double cell = Planet.width() / n;
        // (each row's cells are this much smaller along the ground than on the chart: Mercator)
        double[] shrink = new double[n];
        for (int y = 0; y < n; y++) shrink[y] = Math.cos(Math.asin(Math.max(-1.0, Math.min(1.0, direction(0, y).y()))));
        // The sums of land, and of land's x and y, over every rectangle from the corner, so a
        // window's centre of land comes at once
        long[] sum = new long[(n + 1) * (n + 1)], sumX = new long[(n + 1) * (n + 1)], sumY = new long[(n + 1) * (n + 1)];
        for (int y = 0; y < n; y++) {
            long row = 0, rowX = 0, rowY = 0;
            for (int x = 0; x < n; x++) {
                if (land[y * n + x]) {
                    row++;
                    rowX += x;
                    rowY += y;
                }
                int i = (y + 1) * (n + 1) + x + 1, above = y * (n + 1) + x + 1;
                sum[i] = sum[above] + row;
                sumX[i] = sumX[above] + rowX;
                sumY[i] = sumY[above] + rowY;
            }
        }
        // Each coast cell (land beside the sea) and the line its coast runs along, radians from
        // the chart's east towards its south, folded into half a turn
        double[] line = new double[n * n];
        java.util.Arrays.fill(line, Double.NaN);
        boolean[] coastCell = new boolean[n * n];
        for (int y = 1; y < n - 1; y++) {
            int k = (int) Math.max(2, Math.min(60, Math.round(COAST_RUN / (cell * shrink[y]))));
            for (int x = 1; x < n - 1; x++) {
                int i = y * n + x;
                if (!land[i] || (land[i - 1] && land[i + 1] && land[i - n] && land[i + n])) continue;
                coastCell[i] = true;
                int x0 = Math.max(0, x - k), x1 = Math.min(n, x + k + 1), y0 = Math.max(0, y - k), y1 = Math.min(n, y + k + 1);
                long s = rect(sum, n, x0, y0, x1, y1);
                if (s == 0) continue;
                double cx = rect(sumX, n, x0, y0, x1, y1) / (double) s - x, cy = rect(sumY, n, x0, y0, x1, y1) / (double) s - y;
                // (the land lies towards its centre: the way inland, a whole turn's worth, so a
                // coast with the sea to the north is told from one with it to the south)
                line[i] = Math.atan2(cy, cx);
            }
        }
        // Every cell's distance along the ground to its nearest coast cell, and which that is
        // (two sweeps, each cell taking its neighbours' nearest where that's nearer)
        double[] dist = new double[n * n];
        int[] nearest = new int[n * n];
        java.util.Arrays.fill(dist, Double.MAX_VALUE);
        java.util.Arrays.fill(nearest, -1);
        for (int i = 0; i < n * n; i++) if (coastCell[i]) {
            dist[i] = 0;
            nearest[i] = i;
        }
        int[][] forward = { { -1, -1 }, { 0, -1 }, { 1, -1 }, { -1, 0 } }, backward = { { 1, 1 }, { 0, 1 }, { -1, 1 }, { 1, 0 } };
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) relax(dist, nearest, n, x, y, forward, cell, shrink);
            for (int y = n - 1; y >= 0; y--) for (int x = n - 1; x >= 0; x--) relax(dist, nearest, n, x, y, backward, cell, shrink);
        }
        // The site's own coast
        int siteCell = cellOf(SphereMath.direction(site.place()));
        int siteCoast = siteCell < 0 ? -1 : nearest[siteCell];
        double siteLine = siteCoast < 0 ? 0.0 : line[siteCoast];
        double distance = Math.max(siteCell < 0 ? 0.0 : dist[siteCell], game.regionalManager.distanceToWater(site.place()));
        distance = Math.max(distance, cell);
        boolean[] pass = new boolean[n * n];
        for (int i = 0; i < n * n; i++) {
            int c = nearest[i];
            if (c < 0 || Double.isNaN(line[c])) continue;
            double reach = land[i] ? 4.0 * distance : 2.0 * distance;
            pass[i] = dist[i] <= reach + cell * 0.5 && turnApart(line[c], siteLine) <= Math.toRadians(COAST_ANGLE_TOLERANCE);
        }
        return new Coast(pass, describeCoast(siteLine), distance);
    }

    private static long rect(long[] sums, int n, int x0, int y0, int x1, int y1) {
        int w = n + 1;
        return sums[y1 * w + x1] - sums[y0 * w + x1] - sums[y1 * w + x0] + sums[y0 * w + x0];
    }

    /** One cell of a sweep: nearer to a coast through any of its neighbours (offsets) than it was? */
    private static void relax(double[] dist, int[] nearest, int n, int x, int y, int[][] offsets, double cell, double[] shrink) {
        int i = y * n + x;
        for (int[] o : offsets) {
            int nx = x + o[0], ny = y + o[1];
            if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
            int j = ny * n + nx;
            if (nearest[j] < 0) continue;
            double step = cell * shrink[y] * (o[0] != 0 && o[1] != 0 ? Math.sqrt(2.0) : 1.0);
            if (dist[j] + step < dist[i]) {
                dist[i] = dist[j] + step;
                nearest[i] = nearest[j];
            }
        }
    }

    /** A line's angle folded into [0, pi): a line runs both ways. */
    static double fold(double angle) {
        double a = angle % Math.PI;
        return a < 0 ? a + Math.PI : a;
    }

    /** How far apart two lines are, 0 to pi/2. */
    static double angleApart(double a, double b) {
        double d = Math.abs(fold(a) - fold(b));
        return Math.min(d, Math.PI - d);
    }

    /** How far apart two ways are, 0 to pi (a whole turn: opposite ways are as far apart as can be). */
    static double turnApart(double a, double b) {
        return Math.abs(Math.IEEEremainder(a - b, 2 * Math.PI));
    }

    /** A coast in words, from the way inland (radians from east towards south): "north to south, the sea to the west". */
    static String describeCoast(double inland) {
        String[] sides = { "east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east" };
        // (the sea's the other way from inland)
        int sea = (int) Math.floorMod(Math.round(Math.toDegrees(inland + Math.PI) / 45.0), 8L);
        return describeLine(inland + Math.PI / 2) + ", the sea to the " + sides[sea];
    }

    /** A line (radians from east towards south) in words: "north to south", "north-east to south-west"... */
    static String describeLine(double angle) {
        // (as a compass bearing from north round through east, folded to the half from north to south-east)
        double bearing = Math.toDegrees(fold(angle + Math.PI / 2));
        String[] names = { "north to south", "north-north-east to south-south-west", "north-east to south-west",
                "east-north-east to west-south-west", "east to west", "west-north-west to east-south-east",
                "north-west to south-east", "north-north-west to south-south-east" };
        return names[(int) Math.round(bearing / 22.5) % 8];
    }
}
