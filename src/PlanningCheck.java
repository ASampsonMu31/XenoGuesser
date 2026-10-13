import java.util.List;
import java.util.Random;

/**
 * Self-check for Phase 3a (planning on the sphere): builds seed 123's nations, settlements and
 * road network as the game does, then checks that nations by direction match the map's own
 * lookup, that urbanness by direction is the exact union of every town's influence (and the
 * chart bridge agrees), that towns keep their spacing in true distance, and that every road
 * point is on the ground, evenly spaced, and carried onto the chart without a jump.
 * Phase 3b (building in frames): builds the houses and roads as the game does and checks they
 * are true size at every latitude (houses fill their footprints and no more, roads are as wide
 * as their class), and that the geodesic cells round two towns (at the equator and near 70
 * degrees) build batches in world coordinates on the ground in their own cell, with every
 * vertex inside the batch's bounds and unit normals; that signs stand in right-handed frames of
 * their own, on the ground facing a road or flat on a wall; that walls are solid, driveways
 * cleared, border booths, lights and stops placed in their checks' frames; and that the
 * lookups on the sphere (D5) cost no more in the north.
 * Phase 4 (vehicles on the sphere): parked cars stand at the road clear of buildings, and a car
 * driven by road from the south to the north moves its true speed and gets up to its limits.
 * Run after compiling (takes several seconds):
 * java -cp "target/classes;lib/jogamp-fat.jar" PlanningCheck (exit code 1 on a failure).
 */
public final class PlanningCheck {

    private static int checks, failures;

    public static void main(String[] args) {
        long seed = 123L;
        float width = 150_000f;
        Planet.setWidth(width);
        PerlinNoise noise = new PerlinNoise(seed);
        float sea = XenoGuesser_GLEventListener.precalculateSeaLevel(seed, noise);
        MapProjection.chooseJoin(d -> TerrainMesh.getLayeredHeight(d, noise), sea);
        RegionalGenerationManager regional = new RegionalGenerationManager(seed, sea);
        long fieldStart = System.currentTimeMillis();
        regional.precalculateWaterDistanceField(noise);
        System.out.printf("Water distance field built in %d ms%n", System.currentTimeMillis() - fieldStart);
        checkWaves(regional, noise, sea);
        regional.setMoistureMap(regional.createWaterPreference(0f, 8000f));
        NationGenerationManager nations = new NationGenerationManager(seed, 6 + new Random(seed).nextInt(9), sea, noise);
        nations.generateTerritories(seed);
        SettlementManager settlements = new SettlementManager(seed, sea, noise, regional, nations);

        checkClimate(regional, noise, sea);
        checkNations(nations, width);
        checkSettlements(settlements);
        checkRoads(new RoadNetworkBuilder(seed, sea, noise, settlements, nations).build(), noise, width);
        checkInfrastructure(new InfrastructureManager(seed, nations.numNations, nations, settlements, null), width, sea, noise);

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

    /**
     * The regional factors are sampled by direction (Phase 5): the distance to the sea is the
     * true distance along the ground at every latitude (against a search round each place), and
     * the climate is defined everywhere, the poles and the map's join included.
     */
    private static void checkClimate(RegionalGenerationManager regional, PerlinNoise noise, float sea) {
        Random rand = new Random(17);
        int tried = 0, north = 0;
        double worst = 0;
        while (tried < 60) {
            // (half of them beyond 50 degrees, where the chart stretched distances two to four times)
            SpherePosition p = SphereMath.randomPlace(rand, Math.toRadians(73));
            if (tried % 2 == 1 && Math.abs(Planet.latitude(p)) < Math.toRadians(50)) continue;
            if (TerrainMesh.getLayeredHeight(p, noise) <= sea) continue;
            double nearest = Double.MAX_VALUE;
            for (int k = 0; k < 120; k++) {
                for (double out = 10.0; out < Math.min(nearest, 12000.0); out += 10.0) {
                    if (TerrainMesh.getLayeredHeight(SphereMath.move(p, k * Math.PI / 60, out), noise) <= sea) {
                        nearest = out;
                        break;
                    }
                }
            }
            if (nearest > 12000.0) continue;
            tried++;
            if (Math.abs(Planet.latitude(p)) >= Math.toRadians(50)) north++;
            double field = regional.distanceToWater(p), off = Math.abs(field - nearest);
            worst = Math.max(worst, off);
            // (the field's texels are up to about 90 across, and it's blended between them)
            check(off < 120.0 + 0.04 * nearest, "distance to the sea at " + p + ": " + field + ", searched " + nearest);
        }
        System.out.printf("Distance to the sea: %d places (%d beyond 50 degrees), worst %.0f off the search%n", tried, north, worst);
        for (double[] place : new double[][] { { 90, 0 }, { -90, 0 }, { 0, 180 }, { 0, -180 }, { 45, 179.9999 }, { 45, -179.9999 } }) {
            GeodesicChunkGrid.Direction d = SphereMath.direction(Planet.position(Math.toRadians(place[0]), Math.toRadians(place[1]), 0.0));
            float t = regional.temperatureMap.evaluate(d), r = regional.rainfallMap.evaluate(d);
            check(t >= 0f && t <= 1f && r >= 0f && r <= 1f, "climate at " + place[0] + ", " + place[1] + ": " + t + ", " + r);
        }
        // Either side of the map's join (one place, two map points) the climate is the same
        GeodesicChunkGrid.Direction east = MapProjection.direction(Planet.width() * 0.5 - 1e-3, 1234.0);
        GeodesicChunkGrid.Direction west = MapProjection.direction(-Planet.width() * 0.5 + 1e-3, 1234.0);
        check(Math.abs(regional.temperatureMap.evaluate(east) - regional.temperatureMap.evaluate(west)) < 1e-3f, "temperature jumps at the map's join");
    }

    /** The world's borders are the map's: nation by direction equals the map lookup at the place's map point. */
    private static void checkNations(NationGenerationManager nations, float width) {
        Random rand = new Random(7);
        for (int i = 0; i < 2000; i++) {
            SpherePosition p = SphereMath.randomPlace(rand, Math.toRadians(74));
            double[] m = MapProjection.toMap(p);
            int byDirection = nations.getNationAt(p), byMap = nations.getNationAtMap((float) m[0], (float) m[1]);
            check(byDirection == byMap, "nation at " + p + ": " + byDirection + " by direction, " + byMap + " on the map");
        }
        // The territories are on the sphere (no chart): every nation holds some of it, in true
        // area, and the nations near the poles are no smaller for being there
        SphereRaster raster = nations.raster();
        double[] area = new double[nations.numNations + 1];
        for (int t = 0; t < raster.size(); t++) area[nations.territory()[t]] += raster.solidAngle(t);
        for (int n = 1; n <= nations.numNations; n++) {
            check(area[n] > 0.0, "nation " + n + " holds none of the sphere");
            System.out.printf("Nation %d: %.1f%% of the sphere%n", n, area[n] / (4.0 * Math.PI) * 100.0);
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

    /** The sea's waves: within their greatest height of the sea everywhere, and rolling on open water. */
    private static void checkWaves(RegionalGenerationManager regional, PerlinNoise noise, float sea) {
        Random rand = new Random(7);
        int seaPlaces = 0, waving = 0;
        for (int i = 0; i < 4000; i++) {
            SpherePosition place = SphereMath.randomPlace(rand, Math.PI / 2);
            if (TerrainMesh.getLayeredHeight(place, noise) > sea) continue;
            seaPlaces++;
            float surface = regional.waveSurfaceAt(place, rand.nextFloat() * 100f, sea);
            // (swell 7 and chop 2.2 high, each set's shares summed)
            check(Math.abs(surface - sea) <= 7f * 1.6f + 2.2f * 3.45f, "the sea's surface at " + place + " is " + (surface - sea) + " off");
            if (Math.abs(surface - sea) > 1f) waving++;
        }
        System.out.printf("Waves: %d of %d places on the sea more than 1 off its level%n", waving, seaPlaces);
        check(waving > seaPlaces / 4, "the sea is mostly flat: " + waving + " of " + seaPlaces + " places with waves");
    }

    private static void checkRoads(List<RoadPath> roads, PerlinNoise noise, float width) {
        check(roads.size() > 1000, "only " + roads.size() + " roads planned");
        for (int r = 0; r < roads.size(); r++) {
            RoadPath path = roads.get(r);
            for (int i = 0; i < path.points.size(); i++) {
                SpherePosition p = path.points.get(i);
                check(Math.abs(SphereMath.altitude(p) - TerrainMesh.getLayeredHeight(p, noise)) < 0.01, "road " + r + " point " + i + " off the ground");
                if (i > 0) {
                    double step = SphereMath.distance(path.points.get(i - 1), p);
                    // (easing can stretch a highway step to a few road steps)
                    check(step < 600.0, "road " + r + " step " + i + " is " + step + " long");
                }
            }
        }
    }

    /** A point in a building's frame (as the maps are given it), on the datum. */
    private static SpherePosition inFrame(SphereFrame frame, double x, double z) {
        return SphereMath.atAltitude(frame.toWorld(x, 0.0, z), 0.0);
    }

    private static void checkInfrastructure(InfrastructureManager infra, float width, float sea, PerlinNoise noise) {
        long start = System.currentTimeMillis();
        infra.prepareRoadNetwork(sea, noise);
        System.out.printf("Infrastructure built in %d ms%n", System.currentTimeMillis() - start);
        // Every building (and extension) fills its footprint, true size, and stops at its back wall
        int[] byBand = new int[10];
        infra.forEachBuilding((frame, outline, nation) -> {
            SpherePosition middle = frame.origin();
            check(Math.abs(SphereMath.altitude(middle)) < 1e-6, "building at " + middle + " not given on the datum");
            byBand[Math.min(9, (int) (Math.abs(Math.toDegrees(Planet.latitude(middle))) / 10))]++;
            // (the middle, and most of the way out to each corner of its outline)
            check(infra.isBuildingLocation(inFrame(frame, 0.0, 0.0), 0f), "building at " + middle + " missing at its middle");
            float back = 0f;
            for (float[] corner : outline) {
                check(infra.isBuildingLocation(inFrame(frame, corner[0] * 0.8, corner[1] * 0.8), 0f),
                        "building at " + middle + " missing towards " + corner[0] + ", " + corner[1]);
                back = Math.min(back, corner[1]);
            }
            check(!infra.isBuildingLocation(inFrame(frame, 0.0, back - 1.5), 0f), "building at " + middle + " reaches past its back wall");
        });
        System.out.printf("Buildings by latitude band (0-10, 10-20, ...): %s%n", java.util.Arrays.toString(byBand));
        check(byBand[6] > 50, "few buildings between 60 and 70 degrees: " + byBand[6]);
        // Made roads are as wide as their class, on the ground at the middle of every step
        infra.forEachRoadPath(path -> {
            if (path.roadClass == RoadPath.RoadClass.DIRT) return;
            for (int i = 1; i < path.points.size(); i += 3) {
                SpherePosition a = path.points.get(i - 1), b = path.points.get(i);
                if (SphereMath.distance(a, b) < 1.0) continue;
                SpherePosition middle = SphereMath.slerp(a, b, 0.5);
                double heading = SphereFrame.at(middle).headingTo(b);
                for (double side : new double[] { -1, 1 }) {
                    SpherePosition edge = SphereMath.move(middle, heading + Math.PI * 0.5, side * path.roadClass.width * 0.45);
                    check(infra.isRoadLocation(edge, 0f), path.roadClass + " at " + middle + " narrower than its class");
                }
            }
        });
        // The geodesic cells round two towns (the same true ground round each) build batches in
        // world coordinates, and signs in frames of their own, standing where they should and
        // facing the right way
        double[] lookupMicros = new double[2];
        // (two towns of about the same size, radius 482 and 457, near the equator and at 67 N)
        double[][] towns = { { 4.841, 78.397 }, { 67.461, 139.716 } };
        GeodesicChunkGrid batchGrid = new GeodesicChunkGrid(InfrastructureManager.BATCH_LEVEL);
        for (int t = 0; t < towns.length; t++) {
            double[] town = towns[t];
            SpherePosition centre = Planet.position(Math.toRadians(town[0]), Math.toRadians(town[1]), 0.0);
            List<GeodesicChunkGrid.Cell> cells = batchGrid.cellsWithin(SphereMath.direction(centre), 450.0 / Planet.radius());
            int vertices = 0, wide = 0, offGround = 0, outside = 0, badNormals = 0;
            List<InfrastructureObject> signs = new java.util.ArrayList<>();
            long building = 0L, slowest = 0L;
            for (GeodesicChunkGrid.Cell cell : cells) {
                SpherePosition cellMiddle = SphereMath.atAltitude(cell.centre(), 0.0);
                double cellReach = cell.angularRadius() * Planet.radius();
                long before = System.nanoTime();
                List<InfrastructureObject> built = infra.generateForCell(cell.id(), sea, noise);
                building += System.nanoTime() - before;
                slowest = Math.max(slowest, System.nanoTime() - before);
                for (InfrastructureObject object : built) {
                    if (object.type == InfrastructureObject.Type.SIGN) signs.add(object);
                    for (InfrastructureObject.BatchPart part : object.batchParts()) {
                        for (int v = 0; v + 7 < part.vertices.length; v += 8) {
                            vertices++;
                            SpherePosition p = new SpherePosition(part.vertices[v], part.vertices[v + 1], part.vertices[v + 2]);
                            // (each piece is built by the cell its middle is in, and reaches a plot's size from there)
                            if (SphereMath.distance(p, cellMiddle) > cellReach + 200.0) wide++;
                            double dx = p.x() - object.centre.x(), dy = p.y() - object.centre.y(), dz = p.z() - object.centre.z();
                            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > object.boundingRadius + 0.01) outside++;
                            float nx = part.vertices[v + 3], ny = part.vertices[v + 4], nz = part.vertices[v + 5];
                            if (Math.abs(Math.sqrt(nx * nx + ny * ny + nz * nz) - 1.0) > 1e-3) badNormals++;
                            // (on the ground, give or take a tall building or a deck: not on the chart)
                            if (v % (8 * 37) == 0) {
                                double above = SphereMath.altitude(p) - TerrainMesh.getLayeredHeight(p, noise);
                                if (above < -60.0 || above > 600.0) offGround++;
                            }
                        }
                    }
                }
            }
            String where = String.format("round %.3f, %.3f", town[0], town[1]);
            System.out.printf("Town at %.3f, %.3f: %d vertices and %d signs in its %d cells, built in %.1f ms (%.2f ms a cell, at most %.2f)%n",
                    town[0], town[1], vertices, signs.size(), cells.size(), building / 1e6, building / 1e6 / cells.size(), slowest / 1e6);
            check(vertices > 10000, "town " + where + " built almost nothing: " + vertices + " vertices");
            check(wide == 0, wide + " vertices built far from their cell " + where);
            check(outside == 0, outside + " vertices outside their batch's bounds " + where);
            check(badNormals == 0, badNormals + " vertices with normals not of unit length " + where);
            check(offGround == 0, offGround + " sampled vertices far off the ground " + where);
            checkSigns(infra, signs, noise, where);
            checkSolids(infra, centre, where);
            checkDriveways(infra, centre, where);
            lookupMicros[t] = lookupCost(infra, centre);
        }
        // A lookup costs the same wherever it is (D5)
        System.out.printf("Lookups (road, building, walking surface, cleared ground): %.2f us at the equator, %.2f us at 67 N%n",
                lookupMicros[0], lookupMicros[1]);
        check(lookupMicros[1] < lookupMicros[0] * 2.0 + 2.0, "lookups cost more in the north: " + lookupMicros[1] + " us against " + lookupMicros[0]);
        checkBorders(infra);
        checkLandingSites(infra, noise, sea);
        checkBorderFences(infra, width, sea, noise);
        checkVehicles(infra, noise, towns);
        checkPeople(infra, width, sea, noise, towns);
    }

    /**
     * People (Phase 4): a minute of life round each test town, the town built out to where
     * they're watched. Those out on the street keep out of the buildings, walk at their own
     * true speed (as fast in the north as at the equator) without jumping about, and get where
     * they're going (some go in, some come out).
     */
    /**
     * Whether a place is inside a building's own outline, more than margin from its walls
     * (round and many-sided buildings too, not just within the rectangle round them).
     */
    private static boolean insideBuilding(InfrastructureManager infra, SpherePosition at, float margin) {
        boolean[] in = { false };
        infra.forEachBuildingNear(at, 1f, (frame, outline, nation) -> {
            double[] local = frame.fromSphere(at);
            float x = (float) local[0], z = (float) local[2];
            boolean odd = false;
            for (int i = 0, j = outline.length - 1; i < outline.length; j = i++) {
                float[] a = outline[i], b = outline[j];
                if ((a[1] > z) != (b[1] > z) && x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]) odd = !odd;
            }
            if (!odd) return;
            for (int i = 0, j = outline.length - 1; i < outline.length; j = i++) {
                float[] a = outline[i], b = outline[j];
                float ex = b[0] - a[0], ez = b[1] - a[1], length2 = ex * ex + ez * ez;
                float t = length2 > 0f ? Math.max(0f, Math.min(1f, ((x - a[0]) * ex + (z - a[1]) * ez) / length2)) : 0f;
                float dx = x - a[0] - ex * t, dz = z - a[1] - ez * t;
                if (dx * dx + dz * dz < margin * margin) return;
            }
            in[0] = true;
        });
        return in[0];
    }

    private static void checkPeople(InfrastructureManager infra, float width, float sea, PerlinNoise noise, double[][] towns) {
        GeodesicChunkGrid batchGrid = new GeodesicChunkGrid(InfrastructureManager.BATCH_LEVEL);
        double[] paces = new double[towns.length];
        for (int t = 0; t < towns.length; t++) {
            SpherePosition centre = Planet.position(Math.toRadians(towns[t][0]), Math.toRadians(towns[t][1]), 0.0);
            String where = "round " + towns[t][0] + ", " + towns[t][1];
            // (walls are solid once their cells are built)
            for (GeodesicChunkGrid.Cell cell : batchGrid.cellsWithin(SphereMath.direction(centre), 1100.0 / Planet.radius())) {
                infra.generateForCell(cell.id(), sea, noise);
            }
            Inhabitants people = new Inhabitants(123L, 20, infra, noise);
            GeodesicCollision collision = new GeodesicCollision();
            collision.addSource(infra::obstaclesNear);
            people.setCollision(collision);
            java.util.Map<Object, SpherePosition> before = new java.util.IdentityHashMap<>();
            float dt = 0.05f;
            int steps = 0, inBuilding = 0, jumps = 0;
            double paced = 0.0, expected = 0.0;
            int[] start = null;
            for (int frame = 0; frame < 1200; frame++) {
                before.clear();
                people.forEachWalker((p, at, street, speed) -> before.put(p, at));
                people.update(dt, centre);
                collision.endFrame();
                if (frame == 20) start = people.census();
                int[] moves = new int[3];
                double[] sums = new double[2];
                people.forEachWalker((p, at, street, speed) -> {
                    SpherePosition was = before.get(p);
                    if (!street || was == null || SphereMath.distance(at, centre) > 900.0) return;
                    moves[0]++;
                    if (insideBuilding(infra, at, 1f)) moves[1]++;
                    double moved = SphereMath.distance(was, at);
                    if (moved > speed * dt * 3.0 + 2.0) moves[2]++;
                    sums[0] += moved;
                    sums[1] += speed * dt;
                });
                steps += moves[0];
                inBuilding += moves[1];
                jumps += moves[2];
                paced += sums[0];
                expected += sums[1];
            }
            int[] end = people.census();
            paces[t] = paced / Math.max(1e-9, expected);
            System.out.printf("  people %s: in %d / walking %d / driving %d after 1 s, %d / %d / %d after 60 s; %d street steps, "
                            + "%d in a building, %d jumps, pace %.2f of their speed%n",
                    where, start[0], start[1], start[2], end[0], end[1], end[2], steps, inBuilding, jumps, paces[t]);
            check(steps > 2000, "few people out on the street " + where + ": " + steps + " steps");
            check(inBuilding <= steps / 50, inBuilding + " of " + steps + " street steps in a building " + where);
            check(jumps <= steps / 500, jumps + " of " + steps + " street steps jumped " + where);
            check(paces[t] > 0.5 && paces[t] <= 1.05, "people walk at " + paces[t] + " of their speed " + where);
            check(end[0] > 0 && end[1] > 0, "nobody in or nobody out " + where);
        }
        check(Math.abs(paces[1] - paces[0]) < 0.2, "people walk at " + paces[1] + " of their speed in the north against " + paces[0]);
    }

    /**
     * Vehicles (Phase 4): parked at the kerb outside houses round the two towns, clear of the
     * buildings; and one driving a highway from low latitude to high keeping to the road, moving
     * exactly its speed each step, as fast in the north as in the south, and getting there.
     */
    private static void checkVehicles(InfrastructureManager infra, PerlinNoise noise, double[][] towns) {
        Vehicles vehicles = new Vehicles(123L, 1, 20f, infra, noise);
        Random rand = new Random(5L);
        int parked = 0, inBuilding = 0, offRoad = 0, getInBlocked = 0;
        for (double[] town : towns) {
            SpherePosition centre = Planet.position(Math.toRadians(town[0]), Math.toRadians(town[1]), 0.0);
            for (InfrastructureManager.Doorway door : infra.doorwaysNear(centre, 1500f)) {
                if (door.shop) continue;
                Vehicles.Vehicle v = vehicles.forHousehold(door, rand);
                if (v == null) continue;
                parked++;
                if (infra.isBuildingLocation(v.position, 0f)) inBuilding++;
                if (infra.isBuildingLocation(v.park.getIn(), 0f)) getInBlocked++;
                // (at the road's edge, or up the driveway)
                if (!v.park.driveway() && !infra.isRoadLocation(v.position, v.width)) offRoad++;
            }
        }
        System.out.printf("Parked vehicles: %d round the two towns, %d in a building, %d getting in from a building, %d away from the road%n",
                parked, inBuilding, getInBlocked, offRoad);
        // (enough to go by: 18 round the towns used since Phase 5, 32 round the denser ones before)
        check(parked >= 15, "few vehicles parked round the towns: " + parked);
        check(inBuilding == 0, inBuilding + " vehicles parked in a building");
        check(getInBlocked <= parked / 50, getInBlocked + " vehicles got into from inside a building");
        check(offRoad <= parked / 50, offRoad + " vehicles parked away from the road");

        // From a road below 38 degrees to one above 56 in the same hemisphere, by road
        List<SpherePosition> south = new java.util.ArrayList<>(), north = new java.util.ArrayList<>();
        infra.forEachRoadPath(path -> {
            if (path.roadClass == RoadPath.RoadClass.DIRT) return;
            for (SpherePosition p : path.points) {
                double lat = Math.abs(Math.toDegrees(Planet.latitude(p)));
                if (lat > 28.0 && lat < 38.0) south.add(p);
                if (lat > 56.0 && lat < 70.0) north.add(p);
            }
        });
        boolean[] arrived = { false };
        Vehicles.Vehicle car = null;
        SpherePosition from = null, to = null;
        // (each of a few northern points with its nearest southern one)
        java.util.Collections.shuffle(north, new Random(9L));
        for (int attempt = 0; attempt < Math.min(80, north.size()) && car == null; attempt++) {
            SpherePosition b = north.get(attempt), a = null;
            for (SpherePosition s : south) {
                if (Planet.latitude(s) * Planet.latitude(b) > 0.0 && (a == null || SphereMath.distance(s, b) < SphereMath.distance(a, b))) a = s;
            }
            if (a == null || SphereMath.distance(a, b) > 9000.0) continue;
            car = vehicles.traffic(1, null, a, b, new Random(attempt), () -> arrived[0] = true);
            from = a;
            to = b;
        }
        check(car != null, "no way by road from below 38 degrees to above 56");
        if (car == null) return;
        List<Vehicles.Vehicle> all = List.of(car);
        // (the viewer far off, as nothing here depends on it)
        SpherePosition viewer = SphereMath.move(from, Math.PI, 9000.0);
        // Top speeds by {street or lane, highway} and {below 42 degrees, above 55}
        float[][] fastest = new float[2][2];
        float dt = 0.05f, seconds = 0f, worst = 0f;
        int steps = 0, onRoad = 0;
        while (!arrived[0] && seconds < 1500f) {
            SpherePosition before = car.position;
            float speedBefore = car.speed;
            int legBefore = car.next;
            vehicles.update(all, dt, viewer);
            seconds += dt;
            double moved = SphereMath.distance(before, car.position);
            // (a step that only moves on to the next point of its way doesn't move it)
            if (car.speed > 5f && moved > 1e-6) worst = Math.max(worst, (float) Math.abs(moved - car.speed * dt) / (car.speed * dt));
            double lat = Math.abs(Math.toDegrees(Planet.latitude(car.position)));
            // (by the road it's driving, as its speed limit is: a step on one leg of its way, not
            // one where it moves on to the next, nor while it brakes down to a slower road's limit)
            Vehicles.Stop road = car.route.isEmpty() ? null : car.route.get(Math.min(car.next, car.route.size() - 1));
            if (road != null && road.dirt() == 0f && (lat < 42.0 || lat > 55.0) && car.next == legBefore && car.speed >= speedBefore) {
                int rank = road.rank() >= 2f ? 1 : 0, band = lat < 42.0 ? 0 : 1;
                fastest[rank][band] = Math.max(fastest[rank][band], car.speed);
            }
            steps++;
            if (infra.isRoadLocation(car.position, 0f)) onRoad++;
        }
        System.out.printf("Driving %.1f to %.1f degrees: %s in %.0f s; top speed on streets %.1f below 42 degrees, %.1f above 55, "
                        + "on highways %.1f and %.1f; worst step off its speed by %.4f%%; on the road %d of %d steps%n",
                Math.toDegrees(Planet.latitude(from)), Math.toDegrees(Planet.latitude(to)), arrived[0] ? "there" : "not there",
                seconds, fastest[0][0], fastest[0][1], fastest[1][0], fastest[1][1], worst * 100f, onRoad, steps);
        check(arrived[0], "the vehicle never got from " + from + " to " + to);
        check(worst < 1e-3f, "a step moved the vehicle " + worst * 100f + "% more or less than its speed");
        // Wherever it drove a road, it got up to that road's speed limit and no more, south and north alike
        boolean[] reached = new boolean[2];
        for (int rank = 0; rank < 2; rank++) {
            float limit = Vehicles.speedLimit(car, rank == 1);
            for (int band = 0; band < 2; band++) {
                if (fastest[rank][band] < 5f) continue;
                check(Math.abs(fastest[rank][band] - limit) < limit * 0.02f, "top speed " + fastest[rank][band] + " on "
                        + (rank == 1 ? "a highway" : "a street") + (band == 1 ? " in the north" : " in the south") + ", limit " + limit);
                reached[band] = true;
            }
        }
        check(reached[0] && reached[1], "the vehicle didn't get up to speed both below 42 degrees and above 55");
        check(onRoad >= steps * 0.98, "the vehicle was off the road for " + (steps - onRoad) + " of " + steps + " steps");
    }

    /**
     * The pod's landing sites (chosen on the sphere): off the road and every plot, facing a road
     * a true distance ahead, as far off at 55 degrees as at the equator.
     */
    private static void checkLandingSites(InfrastructureManager infra, PerlinNoise noise, float sea) {
        Random rand = new Random(7L);
        int sites = 0, facing = 0, north = 0, inRoad = 0;
        double farthest = 0.0;
        float standsAt = LandingPod.SPAWN_DISTANCE, reach = LandingPod.REACH;
        for (int k = 0; k < 300; k++) {
            Viewpoint site = infra.landingSite(rand, standsAt, reach,
                    p -> TerrainMesh.getLayeredHeight(p, noise) > sea + 1.0f && Math.abs(Planet.latitude(p)) < Math.toRadians(60.0));
            if (site == null) continue;
            sites++;
            if (Math.abs(Math.toDegrees(Planet.latitude(site.place()))) > 40.0) north++;
            // (now and then another road passes where the player stands)
            if (infra.isRoadLocation(site.place(), 0f)) inRoad++;
            check(!infra.isBuildingLocation(site.place(), 0f), "landing site at " + site.place() + " in a building");
            // (a road straight ahead, within the pod's reach and the most it is set back)
            for (double ahead = 1.0; ahead <= reach + 60.0; ahead += 1.0) {
                if (infra.isRoadLocation(SphereMath.move(site.place(), site.heading(), ahead), 0f)) {
                    facing++;
                    farthest = Math.max(farthest, ahead);
                    break;
                }
            }
        }
        System.out.printf("Landing sites: %d of 300 found (%d above 40 degrees), %d facing a road, farthest %.1f ahead, %d standing in a road%n",
                sites, north, facing, farthest, inRoad);
        check(inRoad <= sites / 50, inRoad + " landing sites standing in a road");
        check(sites > 250, "few landing sites: " + sites);
        check(north > 10, "no landing sites in the north: " + north);
        check(facing == sites, (sites - facing) + " landing sites not facing a road a true distance ahead");
    }

    /**
     * Border fences at fenced crossings (built as their cells are): reaching the road where it
     * crosses the border, with a gatepost there, and true size (short pieces at 70 degrees too).
     */
    private static void checkBorderFences(InfrastructureManager infra, float width, float sea, PerlinNoise noise) {
        List<InfrastructureManager.Guard> booths = infra.borderGuards();
        List<InfrastructureManager.TrafficLight> lights = infra.borderLights();
        GeodesicChunkGrid batchGrid = new GeodesicChunkGrid(InfrastructureManager.BATCH_LEVEL);
        int crossings = 0, fenced = 0, gated = 0;
        double longest = 0.0, farthest = 0.0;
        // Every fence piece's ends, to see that the pieces join up (across cells too)
        java.util.Map<String, SpherePosition[]> pieces = new java.util.HashMap<>();
        for (int i = 0; i + 1 < lights.size() && crossings < 12; i += 2) {
            // (the crossing is half way between its two traffic lights)
            SpherePosition crossing = SphereMath.slerp(lights.get(i).foot(), lights.get(i + 1).foot(), 0.5);
            boolean hasBooth = false;
            for (InfrastructureManager.Guard booth : booths) hasBooth |= SphereMath.distance(booth.place(), crossing) < 90.0;
            if (!hasBooth) continue;
            crossings++;
            for (GeodesicChunkGrid.Cell cell : batchGrid.cellsWithin(SphereMath.direction(crossing), 150.0 / Planet.radius())) {
                infra.generateForCell(cell.id(), sea, noise);
            }
            double[] nearest = { Double.MAX_VALUE, Double.MAX_VALUE }, most = { 0.0 };
            infra.obstaclesNear(crossing, 120.0, new GeodesicCollision.Sink() {
                @Override
                public void segment(SpherePosition a, SpherePosition b, double halfWidth, double top) {
                    if (top < 29.0 || top > 31.0) return;   // (a border fence stands 30 high)
                    pieces.put(a + " " + b, new SpherePosition[] { a, b });
                    nearest[0] = Math.min(nearest[0], SphereMath.distanceToArc(crossing, a, b));
                    // (true size: as long as a side of the lattice it's traced on, but for those
                    // bent round a booth)
                    for (InfrastructureManager.Guard booth : booths) {
                        if (SphereMath.distance(booth.place(), a) < 25.0 || SphereMath.distance(booth.place(), b) < 25.0) return;
                    }
                    most[0] = Math.max(most[0], SphereMath.distance(a, b));
                }

                @Override
                public void circle(SpherePosition c, double radius, double top) {
                    if (Math.abs(radius - 2.2) < 1e-3) nearest[1] = Math.min(nearest[1], SphereMath.distance(c, crossing));
                }
            });
            if (nearest[0] < 60.0) fenced++;
            if (nearest[1] < 60.0) gated++;
            farthest = Math.max(farthest, Math.min(nearest[0], nearest[1]));
            longest = Math.max(longest, most[0]);
        }
        // A piece's end with no other piece's end at it: a gateway (the gate, not a fence piece,
        // carries on from there), a booth's bend, or a gap
        int ends = 0, loose = 0;
        List<SpherePosition> all = new java.util.ArrayList<>();
        for (SpherePosition[] piece : pieces.values()) { all.add(piece[0]); all.add(piece[1]); }
        for (SpherePosition end : all) {
            int at = 0;
            for (SpherePosition other : all) if (SphereMath.distance(end, other) < 0.05) at++;
            ends++;
            if (at < 2) loose++;
        }
        System.out.printf("Border fences: %d of %d fenced crossings have fence within 60 of the road's crossing, %d a gatepost; "
                + "longest piece %.1f; %d of %d piece ends meet no other%n", fenced, crossings, gated, longest, loose, ends);
        check(loose * 10 < ends, loose + " of " + ends + " border fence piece ends meet no other piece (gaps in the fence)");
        check(crossings > 0, "no fenced crossings");
        check(fenced == crossings, (crossings - fenced) + " of " + crossings + " fenced crossings with no fence near the road");
        check(gated == crossings, (crossings - gated) + " of " + crossings + " fenced crossings with no gatepost");
        check(longest < 12.0, "a border fence piece " + longest + " long (not true size)");
    }

    /**
     * Signs: every one in a right-handed frame (so its lettering reads the right way round),
     * standing ones on the ground and off the road with their faces towards a road; boards on
     * walls flat against the building behind them.
     */
    private static void checkSigns(InfrastructureManager infra, List<InfrastructureObject> signs, PerlinNoise noise, String where) {
        int standing = 0, facingRoad = 0, onWalls = 0, againstWalls = 0;
        for (InfrastructureObject sign : signs) {
            check(sign.frame != null, "sign without a frame " + where);
            if (sign.frame == null) continue;
            double[] x = sign.frame.xAxis(), up = sign.frame.up(), z = sign.frame.zAxis();
            double handed = (x[1] * up[2] - x[2] * up[1]) * z[0] + (x[2] * up[0] - x[0] * up[2]) * z[1] + (x[0] * up[1] - x[1] * up[0]) * z[2];
            check(handed > 0.999, "sign at " + sign.frame.origin() + " in a mirrored frame " + where);
            // (its up is the planet's up there)
            SpherePosition origin = sign.frame.origin();
            double level = (up[0] * origin.x() + up[1] * origin.y() + up[2] * origin.z()) / origin.length();
            check(level > 0.99999, "sign at " + origin + " leaning " + where);
            if (sign.wallMounted) {
                if (sign.bothSides) continue;
                onWalls++;
                if (infra.isBuildingLocation(sign.frame.toWorld(0.0, 0.0, -2.0), 1.5f)) againstWalls++;
                continue;
            }
            standing++;
            float ground = TerrainMesh.getLayeredHeight(origin, noise);
            check(Math.abs(SphereMath.altitude(origin) - ground) < 0.05, "sign at " + origin + " not on the ground " + where);
            check(!infra.isRoadLocation(origin, 1f), "sign at " + origin + " standing in the road " + where);
            // (straight ahead, or a little to one side: a dead end's house faces the road coming
            // straight at its door, which its sign, off to the side of the path, looks along)
            boolean faces = false;
            for (double ahead = 2.0; ahead <= 160.0 && !faces; ahead += 2.0) {
                for (double side : new double[] { 0.0, -ahead, ahead }) {
                    faces |= infra.isRoadLocation(sign.frame.toWorld(side, 0.0, ahead), 0.5f);
                }
            }
            if (faces) facingRoad++;
        }
        System.out.printf("  signs %s: %d standing (%d facing a road), %d boards on walls (%d against the building)%n", where, standing, facingRoad,
                onWalls, againstWalls);
        check(standing > 0 && onWalls > 0, "no signs " + where);
        check(facingRoad >= standing * 0.95, (standing - facingRoad) + " of " + standing + " standing signs not facing a road " + where);
        check(againstWalls >= onWalls * 0.95, (onWalls - againstWalls) + " of " + onWalls + " boards on walls not against a building " + where);
    }

    /** The walls of every building round a town are solid (filed on the sphere as their cells are built). */
    private static void checkSolids(InfrastructureManager infra, SpherePosition centre, String where) {
        int buildings = 0, solid = 0;
        for (InfrastructureManager.Doorway door : infra.doorwaysNear(centre, 250f)) {
            buildings++;
            SpherePosition at = door.door;
            int[] shapes = { 0 };
            infra.obstaclesNear(at, 6.0, new GeodesicCollision.Sink() {
                @Override
                public void segment(SpherePosition a, SpherePosition b, double halfWidth, double top) {
                    shapes[0]++;
                }

                @Override
                public void circle(SpherePosition c, double radius, double top) {
                    shapes[0]++;
                }
            });
            if (shapes[0] > 0) solid++;
        }
        System.out.printf("  solids %s: %d of %d front doors have a wall within 6 units%n", where, solid, buildings);
        check(buildings > 0 && solid == buildings, (buildings - solid) + " of " + buildings + " buildings with no solid walls " + where);
    }

    /** A driveway's paving is ground cleared for the road (so no grass or trees on it), all along it. */
    private static void checkDriveways(InfrastructureManager infra, SpherePosition centre, String where) {
        int driveways = 0, cleared = 0;
        for (InfrastructureManager.Doorway door : infra.doorwaysNear(centre, 2000f)) {
            float[] d = door.localDriveway;
            if (d == null) continue;
            driveways++;
            // (laid out in the house's frame)
            boolean all = true;
            for (float f : new float[] { 0.2f, 0.5f, 0.8f }) {
                all &= infra.isClearedForRoad(door.at(d[0] + d[2] * d[4] * f, d[1] + d[3] * d[4] * f), 0f, 0f);
            }
            if (all) cleared++;
        }
        System.out.printf("  driveways %s: %d of %d cleared all along%n", where, cleared, driveways);
        check(driveways == 0 || cleared >= driveways * 0.97, (driveways - cleared) + " of " + driveways + " driveways not cleared " + where);
    }

    /** How long a round of the per-frame lookups takes round a place, in microseconds. */
    private static double lookupCost(InfrastructureManager infra, SpherePosition centre) {
        Random rand = new Random(11);
        int count = 20000;
        SpherePosition[] places = new SpherePosition[count];
        for (int i = 0; i < count; i++) places[i] = SphereMath.move(centre, rand.nextDouble() * Math.PI * 2, rand.nextDouble() * 400.0);
        int hits = 0;
        // (once to warm up, then timed)
        for (int pass = 0; pass < 2; pass++) {
            long start = System.nanoTime();
            for (SpherePosition p : places) {
                if (infra.isRoadLocation(p, 2f)) hits++;
                if (infra.isBuildingLocation(p, 4f)) hits++;
                if (infra.isClearedForRoad(p, 11f, 3f)) hits++;
                hits += (int) infra.walkingSurfaceY(p) & 1;
            }
            if (pass == 1) return (System.nanoTime() - start) / 1000.0 / count + hits * 0.0;
        }
        return 0.0;
    }

    /**
     * Border checks: their booths beside the road (off it, guards facing it), their traffic
     * lights either side, and a vehicle coming up the road stopping level with the booth.
     */
    private static void checkBorders(InfrastructureManager infra) {
        List<InfrastructureManager.Guard> booths = infra.borderGuards();
        List<InfrastructureManager.TrafficLight> lights = infra.borderLights();
        int facing = 0, offRoad = 0, lightsOffRoad = 0;
        for (InfrastructureManager.Guard b : booths) {
            // (well clear of its own road's edge; now and then another road runs close by)
            if (!infra.isRoadLocation(b.place(), 2f)) offRoad++;
            if (infra.isRoadLocation(SphereMath.move(b.place(), b.facing(), 16.0), 1.5f)) facing++;
        }
        check(facing == booths.size(), (booths.size() - facing) + " of " + booths.size() + " booths' guards not facing the road");
        check(offRoad >= booths.size() * 0.9, (booths.size() - offRoad) + " of " + booths.size() + " booths in a road");
        check(lights.size() % 2 == 0, "traffic lights not in pairs");
        int stops = 0, fencedLit = 0;
        for (int i = 0; i + 1 < lights.size(); i += 2) {
            InfrastructureManager.TrafficLight a = lights.get(i), b = lights.get(i + 1);
            check(a.check() == b.check(), "traffic lights " + i + " not a pair");
            SpherePosition pa = a.foot(), pb = b.foot();
            if (!infra.isRoadLocation(pa, 1f)) lightsOffRoad++;
            if (!infra.isRoadLocation(pb, 1f)) lightsOffRoad++;
            // The crossing is half way between its lights; come up the road towards it from 100 away
            SpherePosition crossing = SphereMath.slerp(pa, pb, 0.5);
            double heading = SphereMath.carryHeading(pa, crossing, a.heading());
            boolean fenced = false;
            for (InfrastructureManager.Guard booth : booths) fenced |= SphereMath.distance(booth.place(), crossing) < 90.0;
            if (!fenced) continue;
            fencedLit++;
            SpherePosition from = SphereMath.move(crossing, heading + Math.PI, 100.0);
            double way = SphereMath.carryHeading(crossing, from, heading);
            float[] stop = infra.borderStopAhead(from, way, 200f);
            check(stop != null, "no stop ahead of a vehicle coming up to border check " + a.check());
            if (stop == null) continue;
            // (another check may come first: a road can cross the same border twice)
            if ((int) stop[0] != a.check()) {
                check(stop[1] < 55f, "border check " + (int) stop[0] + " stopped at beyond check " + a.check());
                continue;
            }
            // (level with the near end's booth, CROSS_HALF before the border: 55 true units on)
            check(Math.abs(stop[1] - 55f) < 3f, "border check " + a.check() + " stop " + stop[1] + " ahead, not 55");
            stops++;
        }
        System.out.printf("Borders: %d booths (%d off the road, %d guards facing it), %d traffic lights (%d off the road), %d of %d fenced crossings stop traffic level with the booth%n",
                booths.size(), offRoad, facing, lights.size(), lightsOffRoad, stops, fencedLit);
        check(lightsOffRoad >= lights.size() * 0.9, (lights.size() - lightsOffRoad) + " of " + lights.size() + " traffic lights in a road");
        check(stops >= fencedLit * 0.9, "only " + stops + " of " + fencedLit + " fenced crossings stop traffic level with the booth");
        check(!booths.isEmpty() && !lights.isEmpty(), "no border checks");
    }
}
