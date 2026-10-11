import java.util.List;
import java.util.Random;

/**
 * Self-check for Phase 3a (planning on the sphere): builds seed 123's nations, settlements and
 * road network as the game does, then checks that nations by direction match the map's own
 * lookup, that urbanness by direction is the exact union of every town's influence (and the
 * chart bridge agrees), that towns keep their spacing in true distance, and that every road
 * point is on the ground, evenly spaced, and carried onto the chart without a jump.
 * Run after compiling (takes several seconds):
 * java -cp "target/classes;lib/jogamp-fat.jar" PlanningCheck (exit code 1 on a failure).
 */
public final class PlanningCheck {

    private static int checks, failures;

    public static void main(String[] args) {
        long seed = 123L;
        float width = 150_000f, chunk = 100f;
        Planet.setWidth(width);
        PerlinNoise noise = new PerlinNoise(seed);
        float sea = XenoGuesser_GLEventListener.precalculateSeaLevel(seed, width, noise);
        Planet.chooseJoin((x, z) -> TerrainMesh.getLayeredHeight(x, z, noise), sea);
        RegionalGenerationManager regional = new RegionalGenerationManager(seed, width, sea);
        regional.precalculateWaterDistanceField(width, noise, chunk);
        regional.setMoistureMap(regional.createWaterPreference(0, 80f));
        NationGenerationManager nations = new NationGenerationManager(seed, 6 + new Random(seed).nextInt(9), 384, sea, noise);
        nations.generateTerritories(seed, width);
        SettlementManager settlements = new SettlementManager(seed, chunk, sea, noise, regional, nations);

        checkNations(nations, width);
        checkSettlements(settlements);
        checkRoads(new RoadNetworkBuilder(seed, sea, noise, settlements, nations).build(), noise, width);

        System.out.printf("%d checks, %d failed%n", checks, failures);
        if (failures > 0) System.exit(1);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            if (failures <= 20) System.out.println("FAILED: " + what);
        }
    }

    /** The world's borders are the map's: nation by direction equals the map lookup at the place's map point. */
    private static void checkNations(NationGenerationManager nations, float width) {
        Random rand = new Random(7);
        for (int i = 0; i < 2000; i++) {
            SpherePosition p = SphereMath.randomPlace(rand, Math.toRadians(74));
            double[] m = Planet.mercatorPosition(p);
            int byDirection = nations.getNationAt(p), byMap = nations.getNationAtWorld((float) m[0], (float) m[2], width);
            check(byDirection == byMap, "nation at " + p + ": " + byDirection + " by direction, " + byMap + " on the map");
        }
        // Past the map's top and bottom, and at the poles themselves, there is still a nation
        for (double lat : new double[] { 80, 89.9999, 90, -80, -90 }) {
            int id = nations.getNationAt(Planet.position(Math.toRadians(lat), 0.3, 0.0));
            check(id >= 1 && id <= nations.numNations, "nation at latitude " + lat + ": " + id);
        }
    }

    private static void checkSettlements(SettlementManager settlements) {
        List<SettlementManager.Settlement> all = settlements.getSettlements();
        check(all.size() > 200, "only " + all.size() + " settlements placed");
        Random rand = new Random(11);
        for (int i = 0; i < 3000; i++) {
            // Half anywhere, half within a few radii of a town, where the lookup's lists matter
            SpherePosition p;
            if (i % 2 == 0) {
                p = SphereMath.randomPlace(rand, Math.toRadians(80));
            } else {
                SettlementManager.Settlement town = all.get(rand.nextInt(all.size()));
                p = SphereMath.move(town.centre, rand.nextDouble() * Math.PI * 2, rand.nextDouble() * town.radius * 4);
            }
            GeodesicChunkGrid.Direction d = SphereMath.direction(p);
            float open = 1f, best = 0f;
            SettlementManager.Settlement dominant = null;
            for (SettlementManager.Settlement s : all) {
                float influence = s.influenceAt(d);
                open *= 1f - influence;
                if (influence > best) { best = influence; dominant = s; }
            }
            float urbanness = settlements.getUrbanness(d);
            // (the lookup leaves out a town's influence past 3.5 radii, under 0.3% each)
            check(Math.abs(urbanness - (1f - open)) < 0.005f, "urbanness at " + p + ": " + urbanness + ", brute force " + (1f - open));
            check(best < 0.01f || settlements.dominantSettlementAt(d) == dominant, "dominant town at " + p);
            double[] m = Planet.mercatorPosition(p);
            check(Math.abs(settlements.getUrbanness((float) m[0], (float) m[2]) - urbanness) < 1e-3f, "chart bridge urbanness at " + p);
        }
        for (int a = 0; a < all.size(); a++) {
            SettlementManager.Settlement s = all.get(a);
            check(Math.abs(Planet.latitude(s.centre)) <= Math.toRadians(73.0) + 1e-9, "town " + a + " founded beyond 73 degrees");
            check(settlements.getUrbanness(s.direction) >= s.intensity - 1e-5f, "town " + a + " less urban at its centre than its intensity");
            for (int b = 0; b < a; b++) {
                SettlementManager.Settlement t = all.get(b);
                check(SphereMath.distance(s.centre, t.centre) >= 2.1f * (s.radius + t.radius) - 1e-3, "towns " + b + " and " + a + " too close");
            }
        }
    }

    private static void checkRoads(List<RoadPath> roads, PerlinNoise noise, float width) {
        check(roads.size() > 1000, "only " + roads.size() + " roads planned");
        for (int r = 0; r < roads.size(); r++) {
            RoadPath path = roads.get(r);
            path.toChart();
            check(path.chart.size() == path.points.size(), "road " + r + "'s chart copy has a different length");
            for (int i = 0; i < path.points.size(); i++) {
                SpherePosition p = path.points.get(i);
                check(Math.abs(SphereMath.altitude(p) - TerrainMesh.getLayeredHeight(p, noise)) < 0.01, "road " + r + " point " + i + " off the ground");
                // The chart copy is the same place (its x maybe a whole way round), and runs on without a jump
                com.xenoguesser.math.Vector3 c = path.chart.get(i);
                check(SphereMath.distance(p, Planet.spherePosition(c.x, c.z, 0.0)) < 0.05, "road " + r + " point " + i + " moved on the chart");
                if (i > 0) {
                    double step = SphereMath.distance(path.points.get(i - 1), p);
                    // (easing can stretch a highway step to a few road steps)
                    check(step < 600.0, "road " + r + " step " + i + " is " + step + " long");
                    check(Math.abs(c.x - path.chart.get(i - 1).x) < width * 0.25f, "road " + r + " jumps across the chart at " + i);
                }
            }
        }
    }
}
