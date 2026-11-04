import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Looks over the tutorial's planet (built, without GL, by TutorialFinder) for each exercise's
 * kind of place, as the update-tutorial skill sets them out: somewhere cold, remote and under a
 * clear sky for the sun; a straight stretch of coast in the country for the coast; low ground on
 * the equator for the thermometer; a mountainside exactly on a contour for the altimeter; one
 * plant's and one animal's heartland, with nothing else about, where they overlap in only one
 * region; and for each of the other maps, somewhere it tells apart from much of the planet.
 * Every search draws from its own seeded Random, so the same world always gives the same places.
 */
final class TutorialSites {

    private final XenoGuesser_GLEventListener g;
    private final TutorialChecks checks;
    // What was found and how well it suits, for the rounds file's notes
    final List<String> notes = new ArrayList<>();
    // Land spread evenly over the map (for how much of it passes each exercise)
    private final List<GeodesicChunkGrid.Direction> land = new ArrayList<>();

    TutorialSites(XenoGuesser_GLEventListener g) {
        this.g = g;
        this.checks = new TutorialChecks(g);
        double golden = Math.PI * (3 - Math.sqrt(5)), top = Math.sin(MapProjection.CLIP_LATITUDE);
        int count = 6000;
        for (int i = 0; i < count; i++) {
            double y = top * (1 - 2 * (i + 0.5) / count), across = Math.sqrt(1 - y * y), longitude = golden * i;
            GeodesicChunkGrid.Direction d = new GeodesicChunkGrid.Direction(across * Math.cos(longitude), y, -across * Math.sin(longitude));
            if (TerrainMesh.getLayeredHeight(d, g.worldNoise) > g.seaLevelHeight) land.add(d);
        }
    }

    /** Every exercise's site, in order (one left out if nowhere suits it: its round is then a normal one). */
    List<TutorialPlan.Site> findAll() {
        List<TutorialPlan.Site> sites = new ArrayList<>();
        for (TutorialPlan.Stage stage : TutorialPlan.Stage.values()) {
            long started = System.currentTimeMillis();
            TutorialPlan.Site site;
            try {
                site = find(stage, new Random(TutorialPlan.SEED * 7_919L + stage.ordinal() * 104_729L));
            } catch (RuntimeException e) {
                e.printStackTrace();
                site = null;
            }
            if (site == null) {
                notes.add(stage.key + ": NOTHING SUITABLE FOUND");
            } else {
                sites.add(site);
                float share = passingShare(site);
                notes.add(String.format(Locale.ROOT, "%s: %.2f, %.2f; %.1f%% of the land passes%s", stage.key, site.latitude(), site.longitude(),
                        share * 100f, stage == TutorialPlan.Stage.COAST ? " (before the coast's own check)" : ""));
            }
            System.out.printf(Locale.ROOT, "[TUTORIAL] %s found in %d ms: %s%n", stage.key, System.currentTimeMillis() - started, notes.get(notes.size() - 1));
        }
        return sites;
    }

    private TutorialPlan.Site find(TutorialPlan.Stage stage, Random rand) {
        return switch (stage) {
            case SUN -> sun(rand);
            case COAST -> coast(rand);
            case TEMPERATURE -> temperature(rand);
            case ALTITUDE -> altitude(rand);
            case WILDLIFE -> wildlife(rand);
            case RAINFALL -> mapSite(stage, rand, MapKind.RURAL, d -> share(MapOverlay.of(FactorName.RAINFALL), d) > 0.72f, 2.4f);
            case GROUND -> mapSite(stage, rand, MapKind.RURAL, d -> share(MapOverlay.of(FactorName.ROCKS), d) > 0.55f, -2f);
            case GRASS -> mapSite(stage, rand, MapKind.RURAL, d -> share(MapOverlay.of(FactorName.GRASS_HEIGHT), d) > 0.6f
                    && g.grassAbundanceFactor.evaluate(d) > 0.6f, -1.5f);
            case WEALTH -> mapSite(stage, rand, MapKind.URBAN, d -> share(MapOverlay.of(FactorName.WEALTH), d) > 0.7f, -2f);
            case FLAGS -> mapSite(stage, rand, MapKind.URBAN, d -> true, -2f);
            case ROADSIDE -> mapSite(stage, rand, MapKind.BALANCED, d -> {
                int n = g.nationManager.getNationAt(d);
                return n > 0 && g.mapOverlays.designKey(FactorName.BOLLARDS).at(n, d) >= 0 && g.mapOverlays.designKey(FactorName.ROAD_LINES).at(n, d) >= 0;
            }, -2f);
            case ARCHITECTURE -> mapSite(stage, rand, MapKind.URBAN, d -> {
                int n = g.nationManager.getNationAt(d);
                return n > 0 && g.mapOverlays.designKey(FactorName.ARCHITECTURE).at(n, d) >= 0;
            }, -2f);
            case LANDMARKS -> landmark(rand);
        };
    }

    // ------------------------------------------------------------------ what places are like

    private float height(SpherePosition p) {
        return TerrainMesh.getLayeredHeight(p, g.worldNoise);
    }

    /** Dry land a little above the sea, out of any lake or river. */
    private boolean dry(SpherePosition p) {
        float h = height(p);
        return h > g.seaLevelHeight + PlayerBody.METRE && !g.underFreshWater(p, h);
    }

    /** Level enough to stand and walk about on: the ground within reach no more than rise above or below. */
    private boolean level(SpherePosition p, double reach, float rise) {
        float h = height(p);
        for (int a = 0; a < 8; a++) if (Math.abs(height(SphereMath.move(p, a * Math.PI / 4, reach)) - h) > rise) return false;
        return true;
    }

    /** Out in the country, clear of roads and houses by this much (world units). */
    private boolean remote(SpherePosition p, float clearance) {
        return g.settlementManager.getUrbanness(p) < 0.02f && !g.infraManager.isRoadLocation(p, clearance)
                && !g.infraManager.isBuildingLocation(p, clearance);
    }

    /**
     * Where the pod can come down and its stairs be stood on: dry under it and its legs (as a
     * normal round's landing site is: its legs take up any slope), nothing built there, and the
     * ground round the stairs walkable most ways.
     */
    private boolean podFits(SpherePosition stairs, double heading) {
        SpherePosition pod = SphereMath.move(stairs, heading + Math.PI, LandingPod.SPAWN_DISTANCE);
        if (!dry(stairs) || !dry(pod)) return false;
        for (int leg = 0; leg < 4; leg++) if (!dry(SphereMath.move(pod, Math.PI * 0.25 + leg * Math.PI * 0.5, 36.0))) return false;
        if (g.infraManager.isRoadLocation(pod, LandingPod.REACH) || g.infraManager.isPrivateGround(pod, LandingPod.REACH)) return false;
        return walkable(stairs);
    }

    /** Whether at least six ways of eight from a place can be walked (none too steep for the player, over 3 m). */
    private boolean walkable(SpherePosition p) {
        int ways = 0;
        SpherePosition eye = SphereMath.atAltitude(p, height(p));
        for (int a = 0; a < 8; a++) if (!g.playerMotion.sphereTooSteep(eye, SphereMath.move(eye, a * Math.PI / 4, 3 * PlayerBody.METRE))) ways++;
        return ways >= 6;
    }

    /** Well inside the map: clear of its join (its left and right edges) and its top and bottom, so a guess there needn't be at an edge. */
    private static boolean onMap(SpherePosition p) {
        double[] m = MapProjection.toMap(p);
        double u = m[0] / Planet.width() + 0.5;
        return u > 0.04 && u < 0.96 && Math.abs(Planet.latitude(p)) < MapProjection.CLIP_LATITUDE - 0.12;
    }

    private float share(MapOverlay overlay, GeodesicChunkGrid.Direction d) {
        RegionalFactor f = g.mapOverlays.factorFor(overlay);
        return f == null ? 0f : f.evaluate(d);
    }

    private float share(MapOverlay overlay, SpherePosition p) {
        return share(overlay, SphereMath.direction(p));
    }

    private SpherePosition randomLand(Random rand, double maxLatitude) {
        for (int k = 0; k < 10_000; k++) {
            SpherePosition p = SphereMath.atAltitude(SphereMath.randomPlace(rand, Math.min(maxLatitude, MapProjection.CLIP_LATITUDE - 0.05)), 0.0);
            if (dry(p)) return p;
        }
        return null;
    }

    /** The share of the land a site's exercise passes on (from land spread evenly over the map). */
    float passingShare(TutorialPlan.Site site) {
        if (site.stage() == TutorialPlan.Stage.COAST) return Float.NaN;
        TutorialChecks.Test test = checks.rule(site).test();
        int passing = 0;
        for (GeodesicChunkGrid.Direction d : land) if (test.at(d)) passing++;
        return land.isEmpty() ? 0f : passing / (float) land.size();
    }

    private static TutorialPlan.Site site(TutorialPlan.Stage stage, SpherePosition p, double heading, double hour, double declination,
                                          float wetness, Map<String, String> extra) {
        return new TutorialPlan.Site(stage, Math.toDegrees(Planet.latitude(p)), Math.toDegrees(Planet.longitude(p)), heading, hour,
                declination, wetness, extra);
    }

    // ------------------------------------------------------------------ the sun

    /**
     * Somewhere cold (among the coldest fifth of the land on the temperature map), far from roads
     * and houses, well away from the equator so the sun stands clearly to one side, on level
     * ground with a clear walk to the marker; at the equinox, mid morning, under a clear sky.
     */
    private TutorialPlan.Site sun(Random rand) {
        List<Float> temps = new ArrayList<>();
        for (GeodesicChunkGrid.Direction d : land) temps.add(checks.heatmapTemperature(d));
        java.util.Collections.sort(temps);
        float cold = temps.isEmpty() ? 0f : temps.get(temps.size() / 5);
        for (int attempt = 0; attempt < 200_000; attempt++) {
            SpherePosition p = randomLand(rand, Math.toRadians(62));
            if (p == null) return null;
            double lat = Math.abs(Math.toDegrees(Planet.latitude(p)));
            if (lat < 22 || !onMap(p) || checks.heatmapTemperature(SphereMath.direction(p)) > cold) continue;
            if (!remote(p, 200f * PlayerBody.METRE) || !level(p, 15 * PlayerBody.METRE, 1.5f * PlayerBody.METRE)) continue;
            double heading = rand.nextDouble() * Math.PI * 2;
            SpherePosition marker = SphereMath.move(p, heading, 10 * PlayerBody.METRE);
            if (!dry(marker) || !podFits(p, heading)) continue;
            return site(TutorialPlan.Stage.SUN, p, heading, -0.55, 0.0, -3f, new LinkedHashMap<>());
        }
        return null;
    }

    // ------------------------------------------------------------------ the coast

    // How far from the sea the pod comes down (world units), and how straight the coast must be
    // (the coast's spread across its line as a share of its spread along it, squared)
    private static final float COAST_NEAREST = 30 * PlayerBody.METRE, COAST_FURTHEST = 70 * PlayerBody.METRE;
    private static final double STRAIGHT = 0.012;

    /**
     * In the country near a straight stretch of coast: the coast within COAST_RUN either way
     * lying close along one line, the pod 30 to 70 m from the sea facing it, and a marker on the
     * shore straight ahead.
     */
    private TutorialPlan.Site coast(Random rand) {
        TutorialPlan.Site best = null;
        double bestStraightness = STRAIGHT;
        int tried = 0;
        for (int attempt = 0; attempt < 400_000 && tried < 900; attempt++) {
            SpherePosition p = randomLand(rand, Math.toRadians(60));
            if (p == null) return null;
            float sea = g.regionalManager.distanceToWater(p);
            if (sea < COAST_NEAREST || sea > COAST_FURTHEST || !onMap(p)) continue;
            if (!remote(p, 60f * PlayerBody.METRE)) continue;
            tried++;
            double[] line = coastLine(p);
            if (line == null || line[1] >= bestStraightness) continue;
            // The shore straight ahead, square to the coast's line, on the sea's side
            double across = line[0] + Math.PI / 2;
            if (height(SphereMath.move(p, across, sea * 1.6)) > g.seaLevelHeight) across += Math.PI;
            SpherePosition shore = null;
            for (double out = PlayerBody.METRE; out < sea * 2.5; out += PlayerBody.METRE) {
                SpherePosition q = SphereMath.move(p, across, out);
                if (height(q) < g.seaLevelHeight + 0.6f * PlayerBody.METRE) break;
                if (!g.underFreshWater(q, height(q))) shore = q;
            }
            if (shore == null || SphereMath.distance(p, shore) < 15 * PlayerBody.METRE) continue;
            double heading = SphereMath.heading(p, shore);
            if (!podFits(p, heading)) continue;
            Map<String, String> extra = new LinkedHashMap<>();
            TutorialPlan.putPlace(extra, "marker", shore);
            extra.put("coastLine", String.format(Locale.ROOT, "%.5f", TutorialChecks.fold(line[0])));
            bestStraightness = line[1];
            best = site(TutorialPlan.Stage.COAST, p, heading, -0.7, 0.0, -2.5f, extra);
        }
        return best;
    }

    /**
     * The coast round a place: the line it runs along (radians from east towards south) and how
     * far from straight it is (see STRAIGHT), from the coast points on a grid laid round it; null
     * if there's too little coast, or the sea's on both sides.
     */
    private double[] coastLine(SpherePosition p) {
        double run = TutorialChecks.COAST_RUN, step = 60.0;
        int n = (int) (run / step);
        SphereFrame frame = SphereFrame.at(p);
        boolean[] wet = new boolean[(2 * n + 1) * (2 * n + 1)];
        for (int j = -n; j <= n; j++) for (int i = -n; i <= n; i++) {
            wet[(j + n) * (2 * n + 1) + i + n] = height(frame.toWorld(i * step, 0.0, j * step)) <= g.seaLevelHeight;
        }
        List<double[]> points = new ArrayList<>();
        double sx = 0, sz = 0;
        for (int j = -n + 1; j < n; j++) for (int i = -n + 1; i < n; i++) {
            int k = (j + n) * (2 * n + 1) + i + n;
            if (wet[k] || (!wet[k - 1] && !wet[k + 1] && !wet[k - 2 * n - 1] && !wet[k + 2 * n + 1])) continue;
            if (i * i + j * j > n * n) continue;
            points.add(new double[] { i * step, j * step });
            sx += i * step;
            sz += j * step;
        }
        if (points.size() < n) return null;
        double mx = sx / points.size(), mz = sz / points.size(), xx = 0, xz = 0, zz = 0;
        for (double[] q : points) {
            double dx = q[0] - mx, dz = q[1] - mz;
            xx += dx * dx;
            xz += dx * dz;
            zz += dz * dz;
        }
        double trace = xx + zz, det = xx * zz - xz * xz;
        double big = trace / 2 + Math.sqrt(Math.max(0, trace * trace / 4 - det)), small = trace - big;
        double angle = 0.5 * Math.atan2(2 * xz, xx - zz);
        return new double[] { angle, small / Math.max(1e-9, big) };
    }

    // ------------------------------------------------------------------ temperature

    // The hour (radians from noon) when the day's warming adds nothing to the average: the
    // thermometer then reads as the heatmap does (see XenoGuesser_GLEventListener.temperatureAt)
    private static final double AVERAGE_HOUR = 0.6 - Math.PI / 2;

    /**
     * Low, level ground near the equator, at the equinox and the hour when the day's at its
     * average (so the thermometer reads just what the heatmap shows), where the reading's
     * temperature is found over between 4% and 25% of the land.
     */
    private TutorialPlan.Site temperature(Random rand) {
        for (int attempt = 0; attempt < 100_000; attempt++) {
            SpherePosition p = randomLand(rand, Math.toRadians(5));
            if (p == null) return null;
            if (!onMap(p) || height(p) - g.seaLevelHeight > 60 * PlayerBody.METRE || !level(p, 15 * PlayerBody.METRE, 1.5f * PlayerBody.METRE)) continue;
            double heading = rand.nextDouble() * Math.PI * 2;
            if (!podFits(p, heading)) continue;
            g.sunDeclination = 0.0;
            g.noonLongitude = Planet.longitude(p) - AVERAGE_HOUR;
            float reading = g.temperatureAt(SphereMath.atAltitude(p, height(p)));
            if (Math.abs(reading - checks.heatmapTemperature(SphereMath.direction(p))) > 0.5f) continue;
            TutorialPlan.Site site = site(TutorialPlan.Stage.TEMPERATURE, p, heading, AVERAGE_HOUR, 0.0, -1.5f, new LinkedHashMap<>());
            float share = passingShare(site);
            if (share < 0.04f || share > 0.25f) continue;
            return site;
        }
        return null;
    }

    // ------------------------------------------------------------------ altitude

    /**
     * On a mountainside, at a height that's a whole ten metres (a contour) about three quarters
     * of the way up to the planet's highest ground, with the land falling away below for a view
     * (the pod faces downhill), and level enough to stand.
     */
    private TutorialPlan.Site altitude(Random rand) {
        float highest = 0f;
        for (GeodesicChunkGrid.Direction d : land) highest = Math.max(highest, checks.metresUp(d));
        for (int k = 0; k < 40_000; k++) {
            SpherePosition p = randomLand(rand, MapProjection.CLIP_LATITUDE);
            if (p != null) highest = Math.max(highest, (height(p) - g.seaLevelHeight) / PlayerBody.METRE);
        }
        float target = Math.round(highest * 0.75f / 10f) * 10f;
        int[] why = new int[6];
        for (int attempt = 0; attempt < 400_000; attempt++) {
            SpherePosition p = randomLand(rand, Math.toRadians(70));
            if (p == null) return null;
            float metres = (height(p) - g.seaLevelHeight) / PlayerBody.METRE;
            if (Math.abs(metres - target) > 20f) continue;
            why[0]++;
            // Downhill, and onto the contour along it
            double down = downhill(p);
            SpherePosition onContour = toHeight(p, down, target);
            if (onContour == null || !onMap(onContour)) continue;
            why[1]++;
            if (!walkable(onContour)) continue;
            why[2]++;
            // (a view: the ground well below further down the slope)
            if ((height(SphereMath.move(onContour, down, 300 * PlayerBody.METRE)) - g.seaLevelHeight) / PlayerBody.METRE > target - 40f) continue;
            why[3]++;
            if (!podFits(onContour, down)) continue;
            why[4]++;
            if (!remote(onContour, 30f * PlayerBody.METRE)) continue;
            return site(TutorialPlan.Stage.ALTITUDE, onContour, down, -0.9, 0.0, -2.5f, new LinkedHashMap<>());
        }
        notes.add(String.format(Locale.ROOT, "altitude: highest %.0f m, target %.0f m; near it %d, on the contour %d, level %d, a view %d, the pod fits %d",
                highest, target, why[0], why[1], why[2], why[3], why[4]));
        return null;
    }

    /** The heading downhill from a place (the steepest way down, over 20 m). */
    private double downhill(SpherePosition p) {
        double best = 0;
        float lowest = Float.MAX_VALUE;
        for (int a = 0; a < 16; a++) {
            float h = height(SphereMath.move(p, a * Math.PI / 8, 20 * PlayerBody.METRE));
            if (h < lowest) {
                lowest = h;
                best = a * Math.PI / 8;
            }
        }
        return best;
    }

    /** Along a heading from a place (either way, up to 150 m), the point at exactly so many metres above the sea; null if it's not crossed. */
    private SpherePosition toHeight(SpherePosition p, double heading, float metres) {
        double step = 2 * PlayerBody.METRE;
        float want = g.seaLevelHeight + metres * PlayerBody.METRE;
        for (double sign : new double[] { 1, -1 }) {
            SpherePosition a = p;
            for (double out = step; out < 150 * PlayerBody.METRE; out += step) {
                SpherePosition b = SphereMath.move(p, heading, sign * out);
                if ((height(a) - want) * (height(b) - want) <= 0) {
                    // (halved down to well under a centimetre of height)
                    for (int k = 0; k < 30; k++) {
                        SpherePosition mid = SphereMath.slerp(a, b, 0.5);
                        if ((height(a) - want) * (height(mid) - want) <= 0) b = mid;
                        else a = mid;
                    }
                    return SphereMath.atAltitude(a, 0.0);
                }
                a = b;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ plants and animals

    // The grid the plants' and animals' regions are found on, and the least a region must be to count
    private static final int REGION_GRID = 300, LEAST_REGION = 4;

    /**
     * A plant and an animal plentiful together in only one region of the planet, and a place in
     * that region where both are at their thickest and no other plant or animal is about.
     */
    private TutorialPlan.Site wildlife(Random rand) {
        int plants = g.NUM_SPECIES, animals = g.organismManager.speciesCount();
        TutorialPlan.Site best = null;
        // (the best so far: the other species there scarcest, then the two thickest)
        float bestScore = -Float.MAX_VALUE, bestOthers = 1f;
        int[] why = new int[4];
        for (int s = 0; s < plants; s++) {
            String kind = g.speciesConfigs[s].design.kind();
            if (!kind.equals("Tree") && !kind.equals("Shrub")) continue;
            MapOverlay plant = new MapOverlay(FactorName.FLORA, s);
            for (int i = 0; i < animals; i++) {
                MapOverlay animal = new MapOverlay(FactorName.ANIMAL_POPULATION, i);
                // How plentiful the scarcer of the two is in each cell
                float[] both = new float[REGION_GRID * REGION_GRID];
                for (int y = 0; y < REGION_GRID; y++) for (int x = 0; x < REGION_GRID; x++) {
                    GeodesicChunkGrid.Direction d = gridDirection(x, y);
                    if (d == null || TerrainMesh.getLayeredHeight(d, g.worldNoise) <= g.seaLevelHeight) continue;
                    both[y * REGION_GRID + x] = Math.min(share(plant, d), share(animal, d));
                }
                // The least "plentiful" (from PLENTIFUL up) at which the two overlap in only one region
                for (float plentiful = TutorialChecks.PLENTIFUL; plentiful <= 0.95f; plentiful += 0.05f) {
                    boolean[] in = new boolean[both.length];
                    int count = 0;
                    for (int k = 0; k < both.length; k++) if (both[k] >= plentiful) {
                        in[k] = true;
                        count++;
                    }
                    if (count < LEAST_REGION) break;
                    why[0]++;
                    int[] region = new int[both.length];
                    int regions = 0, biggest = 0, biggestId = 0;
                    for (int k = 0; k < both.length; k++) {
                        if (!in[k] || region[k] != 0) continue;
                        int size = flood(in, region, k, ++regions);
                        if (size > biggest) {
                            biggest = size;
                            biggestId = regions;
                        }
                    }
                    if (biggest < LEAST_REGION || count - biggest > Math.max(1, biggest / 12)) continue;
                    why[1]++;
                    // The best place in it: both thickest, nothing else about
                    for (int k = 0; k < both.length; k++) {
                        if (region[k] != biggestId) continue;
                        GeodesicChunkGrid.Direction d = gridDirection(k % REGION_GRID, k / REGION_GRID);
                        float others = 0f;
                        for (int t = 0; t < plants; t++) if (t != s) others = Math.max(others, share(new MapOverlay(FactorName.FLORA, t), d));
                        for (int a = 0; a < animals; a++) if (a != i) others = Math.max(others, share(new MapOverlay(FactorName.ANIMAL_POPULATION, a), d));
                        // (other species scarcer here by a clear margin wins; much the same, the two thicker)
                        float score = both[k] - 2f * others;
                        if (score <= bestScore) continue;
                        why[2]++;
                        SpherePosition p = standNear(SphereMath.atAltitude(d, 0.0), rand, 40 * PlayerBody.METRE);
                        double heading = rand.nextDouble() * Math.PI * 2;
                        if (p == null || !onMap(p) || !podFits(p, heading)) continue;
                        Map<String, String> extra = new LinkedHashMap<>();
                        extra.put("plant", Integer.toString(s));
                        extra.put("animal", Integer.toString(i));
                        extra.put("plentiful", String.format(Locale.ROOT, "%.2f", plentiful));
                        bestScore = score;
                        bestOthers = others;
                        best = site(TutorialPlan.Stage.WILDLIFE, p, heading, -0.6, 0.0, -2f, extra);
                    }
                    break;
                }
            }
        }
        if (best == null) notes.add(String.format(Locale.ROOT, "wildlife: %d pairs and thresholds overlap, %d in one region, %d places considered",
                why[0], why[1], why[2]));
        else notes.add(String.format(Locale.ROOT, "wildlife: the commonest other species there is at %.0f%% of its range (0 is none about)", bestOthers * 100f));
        return best;
    }

    /** The middle of a cell of the regions' grid (laid on the chart, as TutorialChecks' is), or null off the map's top or bottom. */
    private static GeodesicChunkGrid.Direction gridDirection(int x, int y) {
        double width = Planet.width();
        GeodesicChunkGrid.Direction d = MapProjection.direction(((x + 0.5) / REGION_GRID - 0.5) * width, ((y + 0.5) / REGION_GRID - 0.5) * width);
        return Math.abs(Math.asin(Math.max(-1.0, Math.min(1.0, d.y())))) > MapProjection.CLIP_LATITUDE ? null : d;
    }

    /** Marks a region of the grid (from cell k, eight-way joined) with id; returns its size. */
    private static int flood(boolean[] in, int[] region, int k, int id) {
        java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>();
        open.add(k);
        region[k] = id;
        int size = 0;
        while (!open.isEmpty()) {
            int c = open.poll();
            size++;
            int cx = c % REGION_GRID, cy = c / REGION_GRID;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int nx = Math.floorMod(cx + dx, REGION_GRID), ny = cy + dy;
                if (ny < 0 || ny >= REGION_GRID) continue;
                int n = ny * REGION_GRID + nx;
                if (in[n] && region[n] == 0) {
                    region[n] = id;
                    open.add(n);
                }
            }
        }
        return size;
    }

    /** A place within reach of p that the pod can come down at, level and dry (nearest first, roughly); null if none. */
    private SpherePosition standNear(SpherePosition p, Random rand, double reach) {
        for (int k = 0; k < 60; k++) {
            SpherePosition q = k == 0 ? p : SphereMath.move(p, rand.nextDouble() * Math.PI * 2, reach * k / 60.0);
            if (dry(q) && walkable(q)) return q;
        }
        return null;
    }

    // ------------------------------------------------------------------ the other maps

    /**
     * A landing site (as a normal round's, of the kind of map given) where the exercise's own
     * condition holds, whose rule passes on between 2% and 25% of the land: the map tells it
     * apart, but not too narrowly to find. The likeliest to teach (nearest 10%) is chosen of a
     * few found.
     */
    private TutorialPlan.Site mapSite(TutorialPlan.Stage stage, Random rand, MapKind kind, TutorialChecks.Test wanted, float wetness) {
        TutorialPlan.Site best = null;
        float bestApart = Float.MAX_VALUE;
        int found = 0;
        for (int attempt = 0; attempt < 60 && found < 8; attempt++) {
            Viewpoint v = g.infraManager.landingSite(rand, LandingPod.SPAWN_DISTANCE, LandingPod.REACH,
                    q -> dry(q) && onMap(q) && wanted.at(SphereMath.direction(q)), kind);
            if (v == null || !wanted.at(SphereMath.direction(v.place()))) continue;
            TutorialPlan.Site site = site(stage, v.place(), v.heading(), -0.6, 0.0, wetness, new LinkedHashMap<>());
            float share = passingShare(site);
            if (share < 0.02f || share > 0.25f) continue;
            found++;
            float apart = Math.abs(share - 0.1f);
            if (apart < bestApart) {
                bestApart = apart;
                best = site;
            }
        }
        return best;
    }

    /** In view of one of the landmarks (the pod a little way off, facing it), the natural ones' and the built alike. */
    private TutorialPlan.Site landmark(Random rand) {
        List<Landmark> all = new ArrayList<>(g.infraManager.landmarks().all());
        for (int attempt = 0; attempt < all.size() * 40; attempt++) {
            Landmark lm = all.get(rand.nextInt(all.size()));
            double away = Math.max(lm.reach * 1.6, 60 * PlayerBody.METRE) + rand.nextDouble() * 60 * PlayerBody.METRE;
            SpherePosition at = SphereMath.atAltitude(lm.place(), 0.0);
            SpherePosition p = SphereMath.move(at, rand.nextDouble() * Math.PI * 2, away);
            if (!dry(p) || !onMap(p) || !level(p, 10 * PlayerBody.METRE, 2f * PlayerBody.METRE)) continue;
            if (g.infraManager.isBuildingLocation(p, 20 * PlayerBody.METRE) || g.infraManager.isRoadLocation(p, 8 * PlayerBody.METRE)) continue;
            double heading = SphereMath.heading(p, at);
            if (!podFits(p, heading)) continue;
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("landmark", Integer.toString(g.infraManager.landmarks().indexOf(lm)));
            return site(TutorialPlan.Stage.LANDMARKS, p, heading, -0.5, 0.0, -3f, extra);
        }
        return null;
    }
}
