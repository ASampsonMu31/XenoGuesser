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
            case RAINFALL -> rainfall(rand);
            case GROUND -> ground(rand);
            case GRASS -> mapSite(stage, rand, MapKind.RURAL, d -> share(MapOverlay.of(FactorName.GRASS_HEIGHT), d) > 0.6f
                    && g.grassAbundanceFactor.evaluate(d) > 0.6f, -1.5f);
            // (a poor town, its houses damaged and dirty, well away from any landmark)
            case WEALTH -> mapSite(stage, rand, MapKind.URBAN, d -> share(MapOverlay.of(FactorName.WEALTH), d) < 0.2f
                    && farFromLandmarks(SphereMath.atAltitude(d, 0.0), 500f * PlayerBody.METRE), -2f);
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
        // (and nothing in the way off the stairs that the player can't get past)
        if (!g.infraManager.stairsWayClear(new Viewpoint(stairs, heading), LandingPod.SPAWN_DISTANCE, LandingPod.REACH)) return false;
        return walkable(stairs);
    }

    /** Whether the way from a place along a heading can be walked all the way (dry, never too steep to climb, nothing built), every 3 m. */
    private boolean walkableWay(SpherePosition from, double heading, double distance) {
        double step = 3 * PlayerBody.METRE;
        SpherePosition last = SphereMath.atAltitude(from, height(from));
        for (double out = step; out <= distance; out += step) {
            SpherePosition p = SphereMath.move(from, heading, out);
            if (!dry(p) || g.infraManager.isRoadLocation(p, 2 * PlayerBody.METRE) || g.infraManager.isBuildingLocation(p, 2 * PlayerBody.METRE)) return false;
            SpherePosition here = SphereMath.atAltitude(p, height(p));
            if (g.playerMotion.sphereTooSteep(last, here)) return false;
            last = here;
        }
        return true;
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
        int[] why = new int[3];
        for (int attempt = 0; attempt < 200_000; attempt++) {
            SpherePosition p = randomLand(rand, Math.toRadians(62));
            if (p == null) return null;
            double lat = Math.abs(Math.toDegrees(Planet.latitude(p)));
            if (lat < 22 || !onMap(p) || checks.heatmapTemperature(SphereMath.direction(p)) > cold) continue;
            if (!remote(p, 200f * PlayerBody.METRE) || !level(p, 15 * PlayerBody.METRE, 1.5f * PlayerBody.METRE)) continue;
            why[0]++;
            // (the marker far ahead, the way there dry and walkable all along, and level where
            // it's reached for turning on: each of eight ways tried, from a random one)
            double first = rand.nextDouble() * Math.PI * 2;
            for (int k = 0; k < 8; k++) {
                double heading = first + k * Math.PI / 4;
                if (!walkableWay(p, heading, Tutorial.MARKER_AHEAD)) continue;
                why[1]++;
                if (!level(SphereMath.move(p, heading, Tutorial.MARKER_AHEAD), 4 * PlayerBody.METRE, 1.2f * PlayerBody.METRE)) continue;
                why[2]++;
                if (!podFits(p, heading)) continue;
                return site(TutorialPlan.Stage.SUN, p, heading, -0.55, 0.0, -3f, new LinkedHashMap<>());
            }
        }
        notes.add(String.format(Locale.ROOT, "sun: cold and remote %d, a walkable way %d, level at the marker %d (then the pod didn't fit)", why[0], why[1], why[2]));
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

    // How far from any road or house the plants and animals are looked at (metres), the furthest first
    private static final float[] WILD_CLEARANCES = { 150f, 100f, 60f };

    // The grid the plants' and animals' regions are found on, and the least a region must be to count
    private static final int REGION_GRID = 300, LEAST_REGION = 4;

    /**
     * A plant and an animal both plentiful (on their maps) at a place out in the wild, with no
     * other species common there, and their overlap in as few regions of the planet as can be
     * found (one, ideally). In order: as far from roads and houses as finds anything (see
     * WILD_CLEARANCES), then no other species common (OTHERS_SCARCE), then the fewest regions,
     * then the other species scarcest.
     */
    private TutorialPlan.Site wildlife(Random rand) {
        // (for the notes: how much of the land each species is plentiful on)
        StringBuilder spread = new StringBuilder("wildlife: plentiful over");
        for (int kind = 0; kind < 2; kind++) {
            int count = kind == 0 ? g.NUM_SPECIES : g.organismManager.speciesCount();
            for (int t = 0; t < count; t++) {
                MapOverlay o = new MapOverlay(kind == 0 ? FactorName.FLORA : FactorName.ANIMAL_POPULATION, t);
                int plentiful = 0;
                for (GeodesicChunkGrid.Direction d : land) if (share(o, d) >= TutorialChecks.PLENTIFUL) plentiful++;
                spread.append(String.format(Locale.ROOT, " %s%d %s %.0f%%,", kind == 0 ? "plant" : "animal", t,
                        kind == 0 ? g.speciesConfigs[t].design.kind() : "", 100f * plentiful / Math.max(1, land.size())));
            }
        }
        notes.add(spread.toString());
        for (float clearance : WILD_CLEARANCES) {
            Wild best = wildlife(rand, clearance * PlayerBody.METRE);
            if (best == null) continue;
            notes.add(String.format(Locale.ROOT, "wildlife: %.0f m or more from any road or house; both plentiful in %d region%s; "
                    + "the commonest other species there at %.0f%% of its range%s", clearance, best.regions, best.regions == 1 ? "" : "s",
                    best.others * 100f, best.others > OTHERS_SCARCE ? " (MORE THAN WANTED: nowhere better)" : ""));
            return best.site;
        }
        return null;
    }

    // How common (a share of its range) any other species may be at the wildlife exercise's place: not plentiful
    private static final float OTHERS_SCARCE = TutorialChecks.PLENTIFUL - 0.01f;

    /** A wildlife place found: how many regions its two species overlap in, how common the commonest other is there, and the site. */
    private record Wild(int regions, float others, float both, TutorialPlan.Site site) {

        /** Whether this is a better place than that (see wildlife). */
        boolean beats(Wild that) {
            if (that == null) return true;
            boolean scarce = others <= OTHERS_SCARCE, thatScarce = that.others <= OTHERS_SCARCE;
            if (scarce != thatScarce) return scarce;
            // (neither clear of other species: the clearer first, then the fewer regions)
            if (!scarce && Math.abs(others - that.others) > 0.05f) return others < that.others;
            if (regions != that.regions) return regions < that.regions;
            if (Math.abs(others - that.others) > 0.02f) return others < that.others;
            return both > that.both;
        }
    }

    // Each cell's two commonest plants and animals (their shares and species), so the commonest
    // species other than a pair's comes at once (worked out once, the first time it's wanted)
    private float[][] topShares;
    private int[][] topSpecies;

    private void findTopSpecies(int plants, int animals) {
        int n = REGION_GRID * REGION_GRID;
        topShares = new float[4][n];
        topSpecies = new int[2][n];
        java.util.stream.IntStream.range(0, n).parallel().forEach(k -> {
            GeodesicChunkGrid.Direction d = gridDirection(k % REGION_GRID, k / REGION_GRID);
            if (d == null) return;
            for (int kind = 0; kind < 2; kind++) {
                int count = kind == 0 ? plants : animals;
                float first = 0f, second = 0f;
                int firstOf = -1;
                for (int t = 0; t < count; t++) {
                    float v = share(new MapOverlay(kind == 0 ? FactorName.FLORA : FactorName.ANIMAL_POPULATION, t), d);
                    if (v > first) {
                        second = first;
                        first = v;
                        firstOf = t;
                    } else if (v > second) second = v;
                }
                topShares[kind * 2][k] = first;
                topShares[kind * 2 + 1][k] = second;
                topSpecies[kind][k] = firstOf;
            }
        });
    }

    /** How common the commonest plant other than s, and animal other than i, is in cell k. */
    private float othersAt(int k, int s, int i) {
        float plant = topSpecies[0][k] == s ? topShares[1][k] : topShares[0][k];
        float animal = topSpecies[1][k] == i ? topShares[3][k] : topShares[2][k];
        return Math.max(plant, animal);
    }

    private Wild wildlife(Random rand, float wildClearance) {
        int plants = g.NUM_SPECIES, animals = g.organismManager.speciesCount();
        if (topShares == null) findTopSpecies(plants, animals);
        Wild best = null;
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
                for (float plentiful = TutorialChecks.PLENTIFUL; plentiful <= 0.975f; plentiful += 0.025f) {
                    boolean[] in = new boolean[both.length];
                    int count = 0;
                    for (int k = 0; k < both.length; k++) if (both[k] >= plentiful) {
                        in[k] = true;
                        count++;
                    }
                    if (count < LEAST_REGION) break;
                    // Its regions (eight-way joined; specks too small to count fold into the rest)
                    int[] region = new int[both.length];
                    int regions = 0, counted = 0;
                    java.util.Map<Integer, Integer> sizes = new java.util.HashMap<>();
                    for (int k = 0; k < both.length; k++) {
                        if (!in[k] || region[k] != 0) continue;
                        int size = flood(in, region, k, ++regions);
                        sizes.put(regions, size);
                        if (size >= LEAST_REGION) counted++;
                    }
                    if (counted == 0) continue;
                    // The best place in any region big enough
                    for (int k = 0; k < both.length; k++) {
                        if (region[k] == 0 || sizes.get(region[k]) < LEAST_REGION) continue;
                        if (best != null && best.regions < counted && best.others <= OTHERS_SCARCE) continue;
                        if (best != null && best.others > OTHERS_SCARCE && othersAt(k, s, i) > best.others + 0.05f) continue;
                        GeodesicChunkGrid.Direction d = gridDirection(k % REGION_GRID, k / REGION_GRID);
                        float others = othersAt(k, s, i);
                        Wild candidate = new Wild(counted, others, both[k], null);
                        if (!candidate.beats(best)) continue;
                        SpherePosition p = standNear(SphereMath.atAltitude(d, 0.0), rand, 40 * PlayerBody.METRE);
                        double heading = rand.nextDouble() * Math.PI * 2;
                        // (out in the wild: no townsfolk to take for animals, nothing built in the way)
                        if (p == null || !onMap(p) || !remote(p, wildClearance) || !podFits(p, heading)) continue;
                        Map<String, String> extra = new LinkedHashMap<>();
                        extra.put("plant", Integer.toString(s));
                        extra.put("animal", Integer.toString(i));
                        extra.put("plentiful", String.format(Locale.ROOT, "%.3f", plentiful));
                        best = new Wild(counted, others, both[k], site(TutorialPlan.Stage.WILDLIFE, p, heading, -0.6, 0.0, -2f, extra));
                    }
                }
            }
        }
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

    /** Whether a place is at least this far (world units) from every landmark. */
    private boolean farFromLandmarks(SpherePosition p, float distance) {
        for (Landmark lm : g.infraManager.landmarks().all()) if (SphereMath.distance(p, SphereMath.atAltitude(lm.place(), 0.0)) < distance) return false;
        return true;
    }

    /** The share of the land (spread evenly) a measure is below: its value at that quantile. */
    private float quantile(java.util.function.ToDoubleFunction<GeodesicChunkGrid.Direction> measure, float q) {
        List<Double> values = new ArrayList<>();
        for (GeodesicChunkGrid.Direction d : land) values.add(measure.applyAsDouble(d));
        java.util.Collections.sort(values);
        return values.isEmpty() ? 0f : values.get(Math.min(values.size() - 1, Math.round(q * (values.size() - 1)))).floatValue();
    }

    /**
     * Somewhere very wet (among the wettest twentieth of the land), in the country, on a rainy
     * day; passed anywhere in the wetter half of the land (how wet can't be measured by eye),
     * the rainfall map's middle value over the land kept with it.
     */
    private TutorialPlan.Site rainfall(Random rand) {
        MapOverlay rain = MapOverlay.of(FactorName.RAINFALL);
        float wettest = quantile(d -> share(rain, d), 0.95f), middle = quantile(d -> share(rain, d), 0.5f);
        Viewpoint v = g.infraManager.landingSite(rand, LandingPod.SPAWN_DISTANCE, LandingPod.REACH,
                q -> dry(q) && onMap(q) && share(rain, q) >= wettest, MapKind.RURAL);
        if (v == null || share(rain, v.place()) < wettest) return null;
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("wetterHalf", String.format(Locale.ROOT, "%.4f", middle));
        return site(TutorialPlan.Stage.RAINFALL, v.place(), v.heading(), -0.6, 0.0, 2.6f, extra);
    }

    /**
     * Somewhere the ground stands out on every one of its maps: soil of a colour far from the
     * land's usual (bright red, say), and a great many small rocks. In the country, the pod
     * where it can stand.
     */
    private TutorialPlan.Site ground(Random rand) {
        WorldPalette palette = g.worldArt != null ? g.worldArt.palette() : new WorldPalette(g.worldSeed);
        MapOverlay rocks = MapOverlay.of(FactorName.ROCKS), size = MapOverlay.of(FactorName.ROCK_SIZE);
        // The land's usual soil colour (its average)
        double r = 0, gr = 0, b = 0;
        for (GeodesicChunkGrid.Direction d : land) {
            int c = g.mapOverlays.soilColourAt(palette, d);
            r += c >> 16 & 255;
            gr += c >> 8 & 255;
            b += c & 255;
        }
        int n = Math.max(1, land.size());
        float[] usual = { (float) (r / n / 255), (float) (gr / n / 255), (float) (b / n / 255) };
        float manyRocks = quantile(d -> share(rocks, d), 0.75f), smallRocks = quantile(d -> share(size, d), 0.35f);
        TutorialPlan.Site best = null;
        float bestApart = -1f;
        for (int attempt = 0; attempt < 30_000; attempt++) {
            SpherePosition p = randomLand(rand, Math.toRadians(70));
            if (p == null) break;
            GeodesicChunkGrid.Direction d = SphereMath.direction(p);
            if (!onMap(p) || share(rocks, d) < manyRocks || share(size, d) > smallRocks) continue;
            // (no snow lying over the soil to hide its colour: well above freezing there)
            if (averageCelsius(p) < g.freezingPoint + SNOWLESS_MARGIN) continue;
            int c = g.mapOverlays.soilColourAt(palette, d);
            float dr = (c >> 16 & 255) / 255f - usual[0], dg = (c >> 8 & 255) / 255f - usual[1], db = (c & 255) / 255f - usual[2];
            float apart = (float) Math.sqrt(dr * dr + dg * dg + db * db);
            if (apart <= bestApart || !remote(p, 40f * PlayerBody.METRE)) continue;
            double heading = rand.nextDouble() * Math.PI * 2;
            if (!podFits(p, heading)) continue;
            TutorialPlan.Site site = site(TutorialPlan.Stage.GROUND, p, heading, -0.6, 0.0, -2f, new LinkedHashMap<>());
            float share = passingShare(site);
            if (share < 0.01f || share > 0.25f) continue;
            bestApart = apart;
            best = site;
        }
        if (best != null) notes.add(String.format(Locale.ROOT, "ground: its soil %.2f from the land's usual colour (0 the same, 1.7 black from white)", bestApart));
        return best;
    }

    // How far above the planet's freezing point a place must be on average to have no snow lying
    // (snow thins out over the 5 degrees above it, see SnowCover, and its map is smoothed)
    private static final float SNOWLESS_MARGIN = 6f;

    /** A place's average temperature at its own height (as the snow's lying is worked out, see SnowCover). */
    private float averageCelsius(SpherePosition p) {
        GeodesicChunkGrid.Direction d = SphereMath.direction(p);
        float up = Math.max(0f, height(p) - g.seaLevelHeight);
        return -30f + 70f * (g.regionalManager.temperatureMap.evaluate(d) + g.regionalManager.altitudeChill(d)
                - up * RegionalGenerationManager.ALTITUDE_CHILL);
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
