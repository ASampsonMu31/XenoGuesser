import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Lays out the planet's roads on the sphere in three layers:
 * highways routed across the terrain between neighbouring settlements,
 * street grids filling each settlement's urban area, and winding country
 * lanes that always run from one road to another. A final pass joins any
 * loose road end to a nearby road or runs it down to the shore; any end left
 * over becomes a dead end, which InfrastructureManager finishes with a house.
 * <p>
 * Everything is planned with great-circle geometry on SpherePositions (D4 in
 * GEODESIC_MIGRATION_PLAN.txt): highways are routed over the geodesic cells of
 * ROUTING_LEVEL, lanes and tracks walk along great circles carrying their heading
 * with them, a town's streets are laid out flat in its own tangent frame and lifted
 * along the curve, and roads are found again through a CellIndex. A heading is in
 * radians from east towards south in the local frame where it applies (D10).
 */
public class RoadNetworkBuilder {
    private static final float ROAD_STEP = 70.0f;

    // Highway routing: over the geodesic cells of this level, each joined to the (up to) nine
    // cells sharing a corner with it; their centres are about 260 world units apart
    private static final int ROUTING_LEVEL = 6;
    private static final float ROUTING_LAND_MARGIN = 3.0f;
    private static final float SLOPE_COST = 120.0f;
    // Grades past this (rise over run) are dearer still, so roads wind round hills instead
    private static final float GENTLE_GRADE = 0.07f, STEEP_GRADE_COST = 3000.0f;
    // Rugged cells (steep ground within them, not just between centres) cost extra to cross
    private static final float RUGGED_COST = 40.0f;
    // Finished roads are nudged sideways, up to this far a pass, to ease their gradients
    private static final float EASE_OFFSET = 40.0f;
    private static final int EASE_PASSES = 12;
    // Following an existing highway is cheaper, so routes merge into trunk roads
    private static final float EXISTING_ROAD_COST = 0.35f;
    // Crossing a river costs as much as this much more road (and this much more for each unit of
    // its width), many times more the more it runs along the river than across it, so roads keep
    // to one bank and cross squarely where they must; running over a lake costs this much more
    // for each unit of it, as well as the crossing
    private static final float RIVER_CROSSING_COST = 1200f, RIVER_WIDTH_COST = 15f, ALONG_RIVER_COST = 10f, LAKE_COST = 40f;
    // A lane, track or street runs across a river no more askew than this (|cos| of the angle
    // between it and the river: 0 straight across); where it'd have to, it stops short instead
    private static final float MOST_ASKEW_CROSSING = 0.6f;
    // Running along in a river's water (further than crossing it takes, and this much slack)
    // costs this much more for each unit of it; a lane, track or street never does
    private static final float IN_RIVER_COST = 60f, IN_RIVER_SLACK = 12f;
    private static final float HEURISTIC_WEIGHT = 0.6f;
    private static final int MAX_ROUTE_EXPANSIONS = 400_000;
    private static final int NEIGHBOURS_PER_CITY = 3;
    private static final int NEIGHBOURS_PER_TOWN = 2;
    private static final int CITY_RANK_LIMIT = 25;
    private static final float MAX_LINK_DISTANCE = 32_000.0f;
    private static final float JOIN_SNAP_DISTANCE = 600.0f;
    private static final int SMOOTHING_PASSES = 3;

    // City streets
    private static final float STREET_URBANNESS = 0.28f;
    private static final int MIN_STREET_POINTS = 3;
    // A street doesn't climb more steeply than this (rise over run) between its points
    private static final float MAX_STREET_GRADE = 0.14f;

    // Country lanes
    private static final int LANES_FROM_HIGHWAYS = 700;
    private static final float LANE_START_MAX_URBANNESS = 0.3f;
    private static final float LANE_TARGET_MIN_DISTANCE = 1200.0f;
    private static final float LANE_TARGET_MAX_DISTANCE = 7000.0f;
    private static final float LANE_STEERING = 0.16f;
    private static final float LANE_WANDER = 0.6f;
    private static final float LANE_JOIN_DISTANCE = ROAD_STEP * 0.9f;

    // Loose ends: join to a road ahead if one is close, run on to the shore if the sea is
    // close, otherwise leave a dead end for a house
    private static final float STREET_JOIN_DISTANCE = 240.0f;
    private static final float HIGHWAY_JOIN_DISTANCE = 600.0f;
    private static final float JOIN_CONE_COS = 0.34f;
    private static final float SHORE_SEARCH_DISTANCE = ROAD_STEP * 3.0f;
    private static final float SHORE_SEARCH_STEP = 10.0f;
    private static final float SHORE_STOP_BACK = 4.0f;
    // Highways may ride a short causeway across a dip below sea level rather than stopping
    private static final int MAX_CAUSEWAY_POINTS = 3;

    // Roads are indexed by the geodesic cells of this level (about 200 world units across)
    private static final int INDEX_LEVEL = 7;

    private final long seed;
    private final float seaLevelHeight;
    private final PerlinNoise terrainNoise;
    private final SettlementManager settlementManager;
    private final NationGenerationManager nationManager;

    // The routing cells, by id: their centres, the cells sharing a corner with each, and what
    // the ground is like there
    private final GeodesicChunkGrid routingGrid = new GeodesicChunkGrid(ROUTING_LEVEL);
    private final int cellCount;
    // About how far apart neighbouring cells' centres are
    private final float cellSpacing;
    private final GeodesicChunkGrid.Direction[] cellCentre;
    private final int[][] cellNeighbours;
    private final float[] cellHeight;
    // How well off the people of each island without the main roads are (see buildIslandTracks),
    // per routing cell; NaN elsewhere. And each nation's own wealth, which theirs is drawn from
    private final float[] islandRichness;
    private java.util.function.IntToDoubleFunction nationRichness = n -> 0.5;
    private final float[] cellRugged;
    private final boolean[] cellPassable;
    private final boolean[] cellHasHighway;
    private final CellIndex<SpherePosition> highwayPoints = new CellIndex<>(INDEX_LEVEL);
    private final List<RoadPath> roads = new ArrayList<>();
    private final List<RoadPath.DeadEnd> deadEnds = new ArrayList<>();

    // Index of finished roads: each segment filed at its middle, reaching half its length
    private record SegmentRef(int path, int index) {
    }

    private record Filed(SpherePosition position, SegmentRef segment) {
    }

    private final CellIndex<SegmentRef> segmentIndex = new CellIndex<>(INDEX_LEVEL);
    private final List<int[]> indexedPoints = new ArrayList<>();
    private final Map<Integer, List<Filed>> filedByPath = new HashMap<>();

    // Scratch state reused across route searches
    private final float[] routeCost;
    private final int[] routeParent;
    private final int[] routeStamp;
    private final int[] routeClosedStamp;
    private int currentStamp;

    /** Per-nation town planning: block size, block aspect and how much the streets wander. */
    private final Map<Integer, float[]> streetStyles = new HashMap<>();

    public RoadNetworkBuilder(long seed, float seaLevelHeight, PerlinNoise terrainNoise,
                              SettlementManager settlementManager, NationGenerationManager nationManager) {
        this.seed = seed;
        this.seaLevelHeight = seaLevelHeight;
        this.terrainNoise = terrainNoise;
        this.settlementManager = settlementManager;
        this.nationManager = nationManager;

        this.cellCount = (int) routingGrid.cellCount();
        this.cellSpacing = (float) (Math.sqrt(4.0 * Math.PI / cellCount) * Planet.radius());
        this.cellCentre = new GeodesicChunkGrid.Direction[cellCount];
        this.cellNeighbours = new int[cellCount][];
        this.cellHeight = new float[cellCount];
        this.islandRichness = new float[cellCount];
        Arrays.fill(islandRichness, Float.NaN);
        this.cellRugged = new float[cellCount];
        this.cellPassable = new boolean[cellCount];
        this.cellHasHighway = new boolean[cellCount];
        this.routeCost = new float[cellCount];
        this.routeParent = new int[cellCount];
        this.routeStamp = new int[cellCount];
        this.routeClosedStamp = new int[cellCount];

        int[][] edges = new int[cellCount][];
        IntStream.range(0, cellCount).parallel().forEach(id -> {
            GeodesicChunkGrid.Cell cell = routingGrid.cell(id);
            cellCentre[id] = cell.centre();
            edges[id] = routingGrid.neighbours(cell).stream().mapToInt(n -> (int) n.id()).toArray();
            SpherePosition centre = SphereMath.atAltitude(cell.centre(), 0.0);
            float height = height(centre);
            cellHeight[id] = height;
            cellPassable[id] = height > seaLevelHeight + ROUTING_LAND_MARGIN;
            if (cellPassable[id]) {
                SphereFrame frame = SphereFrame.at(centre);
                float d = cellSpacing * 0.35f;
                float gx = height(frame.toSphere(d, 0.0, 0.0)) - height(frame.toSphere(-d, 0.0, 0.0));
                float gz = height(frame.toSphere(0.0, 0.0, d)) - height(frame.toSphere(0.0, 0.0, -d));
                cellRugged[id] = (float) Math.hypot(gx, gz) / (2.0f * d);
            }
        });
        // A cell's edge neighbours and theirs: the cells round it sharing a corner with it
        IntStream.range(0, cellCount).parallel().forEach(id -> {
            Set<Integer> around = new LinkedHashSet<>();
            for (int e : edges[id]) around.add(e);
            for (int e : edges[id]) for (int f : edges[e]) if (f != id) around.add(f);
            cellNeighbours[id] = around.stream().mapToInt(Integer::intValue).toArray();
        });

        Random styleRand = new Random(seed + 2468L);
        for (int n = 1; n <= nationManager.numNations; n++) {
            float blockSize = 360.0f + styleRand.nextFloat() * 200.0f;
            float blockAspect = 1.0f + styleRand.nextFloat() * 0.7f;
            // A third of nations lay out rigid grids, the rest let their streets meander
            float wander = styleRand.nextFloat() < 0.33f ? 0.0f : 20.0f + styleRand.nextFloat() * 45.0f;
            streetStyles.put(n, new float[] { blockSize, blockAspect, wander });
        }
    }

    public List<RoadPath> build() {
        long startTime = System.currentTimeMillis();
        buildHighways();
        int highwayCount = roads.size();
        buildStreets();
        int streetCount = roads.size() - highwayCount;
        for (int i = 0; i < roads.size(); i++) {
            indexPath(i);
        }
        buildLanes();
        int laneCount = roads.size() - highwayCount - streetCount;
        closeLooseEnds();
        int beforeTracks = roads.size();
        buildTracks();
        buildIslandTracks();
        System.out.printf("[ROADS] %d highway, %d street, %d lane and %d track paths, %d dead ends generated in %d ms%n",
                highwayCount, streetCount, laneCount, roads.size() - beforeTracks, deadEnds.size(), System.currentTimeMillis() - startTime);
        return roads;
    }

    public List<RoadPath.DeadEnd> getDeadEnds() {
        return deadEnds;
    }

    // ==========================================
    //              HIGHWAYS
    // ==========================================

    private void buildHighways() {
        List<SettlementManager.Settlement> settlements = settlementManager.getSettlements();
        int[] landmass = labelLandmasses();
        int[] settlementLandmass = new int[settlements.size()];
        for (int a = 0; a < settlements.size(); a++) {
            int cell = nearestPassableCell(settlements.get(a).centre);
            settlementLandmass[a] = cell < 0 ? -1 : landmass[cell];
        }

        List<int[]> links = new ArrayList<>();
        Set<Long> linked = new HashSet<>();

        // A spanning tree per landmass guarantees every settlement is reachable by road...
        addSpanningTreeLinks(settlements, settlementLandmass, links, linked);

        // ...and links to a few nearest neighbours add the loops and shortcuts of a real network
        for (int a = 0; a < settlements.size(); a++) {
            if (settlementLandmass[a] < 0) {
                continue;
            }
            SettlementManager.Settlement from = settlements.get(a);
            int wanted = from.rank < CITY_RANK_LIMIT ? NEIGHBOURS_PER_CITY : NEIGHBOURS_PER_TOWN;

            Integer[] order = new Integer[settlements.size()];
            for (int b = 0; b < order.length; b++) {
                order[b] = b;
            }
            final SettlementManager.Settlement origin = from;
            Arrays.sort(order, (p, q) -> Double.compare(
                    distance(origin, settlements.get(p)), distance(origin, settlements.get(q))));

            int added = 0;
            for (int k = 0; k < order.length && added < wanted; k++) {
                int b = order[k];
                if (b == a || settlementLandmass[b] != settlementLandmass[a]) {
                    continue;
                }
                if (distance(from, settlements.get(b)) > MAX_LINK_DISTANCE) {
                    break;
                }
                addLink(a, b, links, linked);
                added++;
            }
        }

        // Short links first so longer routes can reuse the roads they built
        links.sort((p, q) -> Double.compare(
                distance(settlements.get(p[0]), settlements.get(p[1])),
                distance(settlements.get(q[0]), settlements.get(q[1]))));

        Random jitterRand = new Random(seed ^ 0x6A09E667F3BCC909L);
        for (int[] link : links) {
            routeHighway(settlements.get(link[0]), settlements.get(link[1]), jitterRand);
        }
    }

    /** Prim's minimum spanning tree over the settlements of each landmass. */
    private void addSpanningTreeLinks(List<SettlementManager.Settlement> settlements, int[] settlementLandmass,
                                      List<int[]> links, Set<Long> linked) {
        int count = settlements.size();
        boolean[] inTree = new boolean[count];
        double[] bestDistance = new double[count];
        int[] bestParent = new int[count];

        for (int root = 0; root < count; root++) {
            if (inTree[root] || settlementLandmass[root] < 0) {
                continue;
            }
            int landmassId = settlementLandmass[root];
            Arrays.fill(bestDistance, Double.MAX_VALUE);
            Arrays.fill(bestParent, -1);
            int current = root;
            while (current >= 0) {
                inTree[current] = true;
                if (bestParent[current] >= 0) {
                    addLink(bestParent[current], current, links, linked);
                }
                int next = -1;
                for (int other = 0; other < count; other++) {
                    if (inTree[other] || settlementLandmass[other] != landmassId) {
                        continue;
                    }
                    double distance = distance(settlements.get(current), settlements.get(other));
                    if (distance < bestDistance[other]) {
                        bestDistance[other] = distance;
                        bestParent[other] = current;
                    }
                    if (next < 0 || bestDistance[other] < bestDistance[next]) {
                        next = other;
                    }
                }
                current = next;
            }
        }
    }

    private void addLink(int a, int b, List<int[]> links, Set<Long> linked) {
        long pairKey = ((long) Math.min(a, b) << 32) | Math.max(a, b);
        if (linked.add(pairKey)) {
            links.add(new int[] { a, b });
        }
    }

    /** Flood fills the passable routing cells so routes are only attempted within one landmass. */
    private int[] labelLandmasses() {
        int[] labels = new int[cellCount];
        Arrays.fill(labels, -1);
        int[] queue = new int[cellCount];
        int nextLabel = 0;
        for (int seedCell = 0; seedCell < cellCount; seedCell++) {
            if (!cellPassable[seedCell] || labels[seedCell] >= 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = seedCell;
            labels[seedCell] = nextLabel;
            while (head < tail) {
                int cell = queue[head++];
                for (int next : cellNeighbours[cell]) {
                    if (cellPassable[next] && labels[next] < 0) {
                        labels[next] = nextLabel;
                        queue[tail++] = next;
                    }
                }
            }
            nextLabel++;
        }
        return labels;
    }

    private void routeHighway(SettlementManager.Settlement from, SettlementManager.Settlement to, Random jitterRand) {
        int start = nearestPassableCell(from.centre);
        int goal = nearestPassableCell(to.centre);
        if (start < 0 || goal < 0 || start == goal) {
            return;
        }
        int[] route = findRoute(start, goal);
        if (route == null) {
            return;
        }

        // Only lay road over the stretches the network doesn't already cover
        int runStart = -1;
        for (int k = 0; k <= route.length; k++) {
            boolean isNew = k < route.length && !cellHasHighway[route[k]];
            if (isNew && runStart < 0) {
                runStart = k;
            } else if (!isNew && runStart >= 0) {
                int first = Math.max(0, runStart - 1);
                int last = Math.min(route.length - 1, k);
                emitHighwayRun(route, first, last, from, to, jitterRand);
                runStart = -1;
            }
        }

        for (int cell : route) {
            cellHasHighway[cell] = true;
        }
    }

    private void emitHighwayRun(int[] route, int first, int last, SettlementManager.Settlement from,
                                SettlementManager.Settlement to, Random jitterRand) {
        List<SpherePosition> controlPoints = new ArrayList<>();
        for (int k = first; k <= last; k++) {
            int cell = route[k];
            SpherePosition point = SphereMath.atAltitude(cellCentre[cell], 0.0);

            if (k == 0) {
                point = from.centre;
            } else if (k == route.length - 1) {
                point = to.centre;
            } else if ((k == first || k == last) && cellHasHighway[cell]) {
                SpherePosition join = highwayPoints.nearest(point, JOIN_SNAP_DISTANCE);
                if (join != null) {
                    point = join;
                }
            } else {
                // Break up the routing grid so highways don't follow its cells' zigzags
                float jx = (jitterRand.nextFloat() - 0.5f) * cellSpacing * 0.6f;
                float jz = (jitterRand.nextFloat() - 0.5f) * cellSpacing * 0.6f;
                point = SphereFrame.at(point).toSphere(jx, 0.0, jz);
            }
            controlPoints.add(point);
        }
        if (controlPoints.size() < 2) {
            return;
        }

        List<SpherePosition> smoothed = controlPoints;
        for (int pass = 0; pass < SMOOTHING_PASSES; pass++) {
            smoothed = chaikin(smoothed);
        }
        List<SpherePosition> resampled = easeGradients(resample(smoothed, ROAD_STEP), 3);
        for (RoadPath path : splitOverWater(resampled, RoadPath.RoadClass.HIGHWAY)) {
            roads.add(path);
            for (SpherePosition point : path.points) {
                highwayPoints.add(point, point);
            }
        }
    }

    /** A* over the routing cells, cost weighted by slope and discounted along existing highways. */
    private int[] findRoute(int start, int goal) {
        currentStamp++;

        MinHeap open = new MinHeap();
        routeStamp[start] = currentStamp;
        routeCost[start] = 0.0f;
        routeParent[start] = -1;
        open.push(start, heuristic(start, goal));

        int expansions = 0;
        while (!open.isEmpty()) {
            int cell = open.pop();
            if (cell == goal) {
                return traceRoute(goal);
            }
            // Skip stale heap entries for cells already expanded
            if (routeClosedStamp[cell] == currentStamp) {
                continue;
            }
            routeClosedStamp[cell] = currentStamp;
            if (++expansions > MAX_ROUTE_EXPANSIONS) {
                return null;
            }

            GeodesicChunkGrid.Direction here = cellCentre[cell];
            for (int next : cellNeighbours[cell]) {
                if (!cellPassable[next] || routeClosedStamp[next] == currentStamp) {
                    continue;
                }

                GeodesicChunkGrid.Direction there = cellCentre[next];
                float stepLength = (float) (SphereMath.angle(here, there) * Planet.radius());
                // (halfway between the two centres; the height lookup puts it on the sphere)
                GeodesicChunkGrid.Direction middle = new GeodesicChunkGrid.Direction(
                        here.x() + there.x(), here.y() + there.y(), here.z() + there.z());
                if (TerrainMesh.getLayeredHeight(middle, terrainNoise) <= seaLevelHeight + 1.0f) {
                    continue;
                }

                float slope = Math.abs(cellHeight[next] - cellHeight[cell]) / stepLength;
                float rugged = cellRugged[next];
                float steep = Math.max(0f, slope - GENTLE_GRADE);
                float stepCost = stepLength * (1.0f + SLOPE_COST * slope * slope + STEEP_GRADE_COST * steep * steep + RUGGED_COST * rugged * rugged);
                if (cellHasHighway[next]) {
                    stepCost *= EXISTING_ROAD_COST;
                }
                stepCost += waterCost(cell, next);

                float cost = routeCost[cell] + stepCost;
                if (routeStamp[next] != currentStamp || cost < routeCost[next]) {
                    routeStamp[next] = currentStamp;
                    routeCost[next] = cost;
                    routeParent[next] = cell;
                    open.push(next, cost + heuristic(next, goal));
                }
            }
        }
        return null;
    }

    // Each step's cost of crossing water, once worked out (by the two cells, the lower first)
    private final Map<Long, Float> waterCosts = new HashMap<>();

    /** What crossing water costs on the step between two neighbouring routing cells (see RIVER_CROSSING_COST). */
    private float waterCost(int cell, int next) {
        Rivers rivers = terrainNoise.rivers;
        if (rivers == null) return 0f;
        long key = Math.min(cell, next) * (long) cellCount + Math.max(cell, next);
        Float known = waterCosts.get(key);
        if (known != null) return known;
        float[] crossing = rivers.crossing(cellCentre[cell], cellCentre[next]);
        float cost = 0f;
        if (crossing != null) {
            if (crossing[0] > 0f) {
                cost += (RIVER_CROSSING_COST + RIVER_WIDTH_COST * crossing[0]) * (1f + ALONG_RIVER_COST * crossing[1] * crossing[1]);
            }
            cost += LAKE_COST * crossing[2];
            // (and running along in a river's water, more than crossing it takes)
            cost += IN_RIVER_COST * Math.max(0f, crossing[3] - 1.5f * crossing[0]);
        }
        waterCosts.put(key, cost);
        return cost;
    }

    /** Whether a lane, track or street can go straight from a to b: not over a lake, nor across a river too askew (MOST_ASKEW_CROSSING). */
    private boolean fairCrossing(SpherePosition a, SpherePosition b) {
        float[] crossing = crossingBetween(a, b);
        return crossing == null || (crossing[2] <= 0f && crossing[1] <= MOST_ASKEW_CROSSING && crossing[3] <= 1.5f * crossing[0] + IN_RIVER_SLACK);
    }

    /** The water the way straight from a to b crosses (see Rivers.crossing), null if none (or no rivers yet). */
    private float[] crossingBetween(SpherePosition a, SpherePosition b) {
        Rivers rivers = terrainNoise.rivers;
        return rivers == null ? null : rivers.crossing(SphereMath.direction(a), SphereMath.direction(b));
    }

    private float heuristic(int cell, int goal) {
        return (float) (SphereMath.angle(cellCentre[cell], cellCentre[goal]) * Planet.radius()) * HEURISTIC_WEIGHT;
    }

    private int[] traceRoute(int goal) {
        List<Integer> reversed = new ArrayList<>();
        for (int cell = goal; cell >= 0; cell = routeParent[cell]) {
            reversed.add(cell);
        }
        int[] route = new int[reversed.size()];
        for (int k = 0; k < route.length; k++) {
            route[k] = reversed.get(route.length - 1 - k);
        }
        return route;
    }

    /** The passable routing cell holding a place, or the nearest within three rings of it; -1 if none. */
    private int nearestPassableCell(SpherePosition place) {
        int start = (int) routingGrid.cellAt(place).id();
        if (cellPassable[start]) return start;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(new int[] { start, 0 });
        seen.add(start);
        while (!queue.isEmpty()) {
            int[] entry = queue.removeFirst();
            if (cellPassable[entry[0]]) return entry[0];
            if (entry[1] >= 3) continue;
            for (int next : cellNeighbours[entry[0]]) {
                if (seen.add(next)) queue.addLast(new int[] { next, entry[1] + 1 });
            }
        }
        return -1;
    }

    // ==========================================
    //              CITY STREETS
    // ==========================================

    private void buildStreets() {
        Random streetRand = new Random(seed + 13579L);
        PerlinNoise wanderNoise = new PerlinNoise(seed + 97531L);

        for (SettlementManager.Settlement settlement : settlementManager.getSettlements()) {
            // Laid out flat in the town's own frame (x east, z south) and lifted along the curve
            SphereFrame frame = SphereFrame.at(settlement.centre);
            float extent = settlement.radiusAtUrbanness(STREET_URBANNESS);
            float heading = streetRand.nextFloat() * (float) Math.PI;
            // On a hillside the streets run along the slope, the cross streets up it
            float span = Math.max(ROAD_STEP * 2f, extent * 0.5f);
            float gx = height(frame.toSphere(span, 0.0, 0.0)) - height(frame.toSphere(-span, 0.0, 0.0));
            float gz = height(frame.toSphere(0.0, 0.0, span)) - height(frame.toSphere(0.0, 0.0, -span));
            if (Math.hypot(gx, gz) / (2f * span) > 0.035f) heading = (float) Math.atan2(gz, gx) + (float) Math.PI * 0.5f;
            float phaseA = streetRand.nextFloat();
            float phaseB = streetRand.nextFloat();
            if (extent < ROAD_STEP * 2.0f) {
                continue;
            }

                        int nationId = nationManager.getNationAt(settlement.direction);
            float[] style = streetStyles.getOrDefault(nationId, new float[] { 450.0f, 1.2f, 30.0f });
            // Each settlement has its own character within its nation's tendency: some are
            // strict grids, some wind like old villages, with their own block size and how
            // tightly their streets curve
            float character = streetRand.nextFloat();
            float wander;
            if (character < 0.2f) {
                wander = 0.0f;
            } else if (character < 0.4f) {
                wander = 60.0f + streetRand.nextFloat() * 60.0f;
            } else {
                wander = style[2] * (0.3f + streetRand.nextFloat() * 1.4f);
            }
            float wanderFrequency = 0.0006f + streetRand.nextFloat() * 0.0022f;
            float blockSize = style[0] * (0.75f + streetRand.nextFloat() * 0.5f);
            float blockAspect = style[1] * (0.85f + streetRand.nextFloat() * 0.3f);

            layStreetFamily(settlement, frame, extent, heading, blockSize, phaseA, wander, wanderFrequency, wanderNoise);
            layStreetFamily(settlement, frame, extent, heading + (float) Math.PI * 0.5f, blockSize * blockAspect,
                    phaseB, wander, wanderFrequency, wanderNoise);
        }
    }

    /** One set of parallel streets across the settlement, cut wherever it leaves the urban area or meets water. */
    private void layStreetFamily(SettlementManager.Settlement settlement, SphereFrame frame, float extent, float heading,
                                                                  float spacing, float phase, float wander, float wanderFrequency, PerlinNoise wanderNoise) {
        float dirX = (float) Math.cos(heading);
        float dirZ = (float) Math.sin(heading);
        float sideX = -dirZ;
        float sideZ = dirX;
        int lineCount = (int) Math.ceil(extent / spacing);

        for (int line = -lineCount; line <= lineCount; line++) {
            float offset = (line + phase - 0.5f) * spacing;
            if (Math.abs(offset) > extent) {
                continue;
            }
            float noiseRow = line * 7.31f + heading * 3.17f;

            List<SpherePosition> run = new ArrayList<>();
            float lastHeight = 0f;
            for (float t = -extent; t <= extent + 0.001f; t += ROAD_STEP) {
                                float drift = wander * wanderNoise.eval(t * wanderFrequency, noiseRow);
                float x = dirX * t + sideX * (offset + drift);
                float z = dirZ * t + sideZ * (offset + drift);
                SpherePosition point = frame.toSphere(x, 0.0, z);
                GeodesicChunkGrid.Direction direction = SphereMath.direction(point);

                float height = height(point);
                boolean inTown = settlementManager.getUrbanness(direction) >= STREET_URBANNESS
                        && settlementManager.dominantSettlementAt(direction) == settlement
                        && height > seaLevelHeight + 0.5f;
                // Too steep from the last point, or over water it can't cross squarely: the street
                // stops short and starts again beyond
                if (inTown && !run.isEmpty()) {
                    SpherePosition last = run.get(run.size() - 1);
                    float climb = Math.abs(height - lastHeight);
                    if (climb > MAX_STREET_GRADE * (float) SphereMath.distance(point, last) || !fairCrossing(last, point)) {
                        emitStreet(run);
                        run = new ArrayList<>();
                    }
                }
                if (inTown) {
                    run.add(point);
                    lastHeight = height;
                } else {
                    emitStreet(run);
                    run = new ArrayList<>();
                }
            }
            emitStreet(run);
        }
    }

    private void emitStreet(List<SpherePosition> run) {
        if (run.size() < MIN_STREET_POINTS) {
            return;
        }
        RoadPath path = new RoadPath(RoadPath.RoadClass.STREET);
        for (SpherePosition point : run) {
            path.points.add(ground(point));
        }
        roads.add(path);
    }

    // ==========================================
    //              COUNTRY LANES
    // ==========================================

    private void buildLanes() {
        Random laneRand = new Random(seed + 86420L);

        List<Integer> highways = new ArrayList<>();
        int highwayPointCount = 0;
        for (int i = 0; i < roads.size(); i++) {
            if (roads.get(i).roadClass == RoadPath.RoadClass.HIGHWAY) {
                highways.add(i);
                highwayPointCount += roads.get(i).points.size();
            }
        }

        // Lanes peeling off the highways and running across country to another road
        for (int lane = 0; lane < LANES_FROM_HIGHWAYS && highwayPointCount > 0; lane++) {
            int pick = laneRand.nextInt(highwayPointCount);
            int highwayIndex = highways.get(0);
            for (int candidate : highways) {
                int size = roads.get(candidate).points.size();
                if (pick < size) {
                    highwayIndex = candidate;
                    break;
                }
                pick -= size;
            }
            RoadPath highway = roads.get(highwayIndex);
            if (highway.points.size() < 2) {
                continue;
            }
            int index = Math.max(1, Math.min(highway.points.size() - 1, pick));
            SpherePosition origin = highway.points.get(index);
            if (settlementManager.getUrbanness(origin) > LANE_START_MAX_URBANNESS) {
                continue;
            }
            float along = (float) headingFrom(highway.points.get(index - 1), origin);
            float side = laneRand.nextBoolean() ? 1.0f : -1.0f;
            float heading = along + side * ((float) Math.PI * 0.5f + (laneRand.nextFloat() - 0.5f) * 0.9f);
            growLane(origin, heading, highwayIndex, laneRand, 0);
        }

        // Each settlement also sends lanes out from the streets at its edge towards other roads
        for (SettlementManager.Settlement settlement : settlementManager.getSettlements()) {
            int laneCount = settlement.rank < CITY_RANK_LIMIT ? 3 : 1 + laneRand.nextInt(2);
            float edge = Math.max(ROAD_STEP, settlement.radiusAtUrbanness(LANE_START_MAX_URBANNESS));
            for (int lane = 0; lane < laneCount; lane++) {
                float heading = laneRand.nextFloat() * (float) Math.PI * 2.0f;
                SpherePosition edgePoint = SphereMath.move(settlement.centre, heading, edge);
                RoadHit anchor = nearestRoad(edgePoint, edge, -1, Double.NaN, -1.0f);
                if (anchor != null) {
                    // (still heading out from the middle, as seen from the edge)
                    double outward = SphereMath.carryHeading(settlement.centre, edgePoint, heading);
                    growLane(anchor.point, (float) SphereMath.carryHeading(edgePoint, anchor.point, outward),
                            anchor.pathIndex, laneRand, 0);
                }
            }
        }
    }

    /**
     * Wanders from a point on one road towards a point on another and keeps
     * the lane only if it arrives, so lanes never end in open country.
     */
    private boolean growLane(SpherePosition start, float heading, int originPath, Random random, int depth) {
        SpherePosition target = pickLaneTarget(start, heading, originPath, random);
        if (target == null) {
            return false;
        }
        float targetDistance = (float) SphereMath.distance(start, target);
        int maxSteps = (int) (targetDistance / ROAD_STEP * 2.0f) + 6;

        RoadPath path = new RoadPath(RoadPath.RoadClass.LANE);
        path.points.add(ground(start));
        SpherePosition at = start;
        boolean joined = false;

        for (int step = 0; step < maxSteps && !joined; step++) {
            float desired = (float) SphereMath.heading(at, target);
            float turn = wrapAngle(desired - heading) * LANE_STEERING;
            turn = Math.max(-0.35f, Math.min(0.35f, turn));
            heading += turn + (random.nextFloat() - 0.5f) * LANE_WANDER;
            heading = flattestHeading(at, heading);
            SpherePosition next = SphereMath.move(at, heading, ROAD_STEP);
            heading = (float) SphereMath.carryHeading(at, next, heading);
            at = next;

            if (height(at) <= seaLevelHeight + 0.5f) {
                return false;
            }
            // (it crosses rivers squarely or not at all, and never runs over a lake)
            if (!fairCrossing(path.points.get(path.points.size() - 1), at)) {
                return false;
            }

            // Leave the road we set out from before we can join anything
            int excluded = step < 4 ? originPath : -1;
            RoadHit hit = nearestRoad(at, LANE_JOIN_DISTANCE, excluded, Double.NaN, -1.0f);
            if (hit != null) {
                path.points.add(ground(hit.point));
                joined = true;
            } else {
                path.points.add(ground(at));
            }
        }
        if (!joined || path.points.size() < 3) {
            return false;
        }

        roads.add(path);
        int laneIndex = roads.size() - 1;
        indexPath(laneIndex);

        if (depth < 1 && path.points.size() >= 12 && random.nextFloat() < 0.5f) {
            int branchIndex = 3 + random.nextInt(path.points.size() - 6);
            SpherePosition branchStart = path.points.get(branchIndex);
            float along = (float) headingFrom(path.points.get(branchIndex - 1), branchStart);
            float branchHeading = along + (random.nextBoolean() ? 1.0f : -1.0f) * (0.9f + random.nextFloat() * 0.6f);
            growLane(branchStart, branchHeading, laneIndex, random, depth + 1);
        }
        return true;
    }

    /** A point on some other road, a sensible distance away and roughly in the direction of travel. */
    private SpherePosition pickLaneTarget(SpherePosition from, float heading, int originPath, Random random) {
        if (indexedPoints.isEmpty()) {
            return null;
        }
        for (int attempt = 0; attempt < 60; attempt++) {
            int[] entry = indexedPoints.get(random.nextInt(indexedPoints.size()));
            if (entry[0] == originPath) {
                continue;
            }
            SpherePosition point = roads.get(entry[0]).points.get(entry[1]);
            double distance = SphereMath.distance(from, point);
            if (distance < LANE_TARGET_MIN_DISTANCE || distance > LANE_TARGET_MAX_DISTANCE) {
                continue;
            }
            if (Math.cos(SphereMath.heading(from, point) - heading) < 0.2) {
                continue;
            }
            return point;
        }
        return null;
    }

    // ==========================================
    //              DIRT TRACKS
    // ==========================================

    private static final int TRACKS = 1400;
    private static final float TRACK_MIN_LENGTH = 1500.0f, TRACK_MAX_LENGTH = 9000.0f;
    private static final float TRACK_MAX_URBANNESS = 0.22f;
    // How far ahead a track looks for open country to head into, and what counts as open
    private static final float TRACK_LOOKAHEAD = 1800.0f, TRACK_CLEARANCE = 700.0f;
    // Tracks peter out short of the far north and south (the map stops at 75 degrees)
    private static final double TRACK_MAX_LATITUDE = Math.toRadians(73.0);

    /**
     * Dirt tracks branching off the highways and lanes (and each other) and running a long
     * way out into the country, towards wherever there are fewest roads: wandering, keeping
     * to the gentlest ground, and ending wherever they peter out, at the shore, at a town, or
     * where they meet another road.
     */
    private void buildTracks() {
        Random rand = new Random(seed + 424242L);
        int made = 0;
        for (int attempt = 0; attempt < TRACKS * 4 && made < TRACKS && !indexedPoints.isEmpty(); attempt++) {
            int[] entry = indexedPoints.get(rand.nextInt(indexedPoints.size()));
            RoadPath source = roads.get(entry[0]);
            if (entry[1] < 1 || entry[1] >= source.points.size()) continue;
            if (source.roadClass == RoadPath.RoadClass.STREET && rand.nextFloat() < 0.7f) continue;
            SpherePosition origin = source.points.get(entry[1]);
            if (settlementManager.getUrbanness(origin) > TRACK_MAX_URBANNESS) continue;
            float along = (float) headingFrom(source.points.get(entry[1] - 1), origin);
            // Out to whichever side has open country ahead (if neither, usually not at all)
            float best = Float.NaN;
            for (float side : rand.nextBoolean() ? new float[] { 1f, -1f } : new float[] { -1f, 1f }) {
                float heading = along + side * ((float) Math.PI * 0.5f + (rand.nextFloat() - 0.5f) * 0.8f);
                SpherePosition ahead = SphereMath.move(origin, heading, TRACK_LOOKAHEAD);
                if (nearestRoad(ahead, TRACK_CLEARANCE, -1, Double.NaN, -1.0f) == null) {
                    best = heading;
                    break;
                }
            }
            if (Float.isNaN(best)) {
                if (rand.nextFloat() < 0.8f) continue;
                best = along + (rand.nextBoolean() ? 1f : -1f) * (float) Math.PI * 0.5f;
            }
            if (growTrack(origin, best, entry[0], rand, 8)) made++;
        }
    }

    // How likely an island with no roads is to have roads over it all the same
    private static final float ISLAND_TRACK_CHANCE = 0.8f;
    // An island's wealth is its nation's times a spread factor: e^(normal * SPREAD + SHIFT), so
    // usually a little poorer than at home, now and then far poorer or richer. Islands at least
    // ISLAND_TARMAC_RICHNESS well off have tarmac lanes rather than dirt tracks
    private static final float ISLAND_WEALTH_SPREAD = 0.5f, ISLAND_WEALTH_SHIFT = -0.2f, ISLAND_TARMAC_RICHNESS = 0.65f;

    // Whether two nations are at odds (their border fenced), which no dirt track crosses
    private java.util.function.BiPredicate<Integer, Integer> hostile = (a, b) -> false;

    public void setHostile(java.util.function.BiPredicate<Integer, Integer> hostile) {
        this.hostile = hostile;
    }

    /** Each nation's wealth (0 to 1), from which its islands' wealth is drawn. */
    public void setNationRichness(java.util.function.IntToDoubleFunction richness) {
        this.nationRichness = richness;
    }

    /** How well off the people are at a place on an island the main roads never reached; NaN anywhere else. */
    public float islandRichnessAt(SpherePosition place) {
        return islandRichness[(int) routingGrid.cellAt(place).id()];
    }

    /**
     * Islands that the roads never reached (no town on them, or none linked) mostly still have
     * a few roads: each starts somewhere inland and wanders out both ways to the shore, and
     * later ones join on to the earlier ones where they meet. Bigger islands get more. Each
     * island has its own wealth (see ISLAND_WEALTH_SPREAD): a poor one's roads are dirt
     * tracks, a well-off one's tarmac lanes; houses are built along them either way.
     */
    private void buildIslandTracks() {
        int[] landmass = labelLandmasses();
        int labels = 0;
        for (int label : landmass) labels = Math.max(labels, label + 1);
        boolean[] hasRoad = new boolean[labels];
        for (RoadPath road : roads) {
            for (SpherePosition point : road.points) {
                int cell = nearestPassableCell(point);
                if (cell >= 0 && landmass[cell] >= 0) hasRoad[landmass[cell]] = true;
            }
        }
        List<List<Integer>> cells = new ArrayList<>();
        for (int k = 0; k < labels; k++) cells.add(new ArrayList<>());
        for (int cell = 0; cell < landmass.length; cell++) {
            if (landmass[cell] >= 0 && !hasRoad[landmass[cell]]) cells.get(landmass[cell]).add(cell);
        }
        Random rand = new Random(seed + 7171717L);
        int made = 0;
        for (int k = 0; k < labels; k++) {
            List<Integer> island = cells.get(k);
            if (island.isEmpty() || rand.nextFloat() >= ISLAND_TRACK_CHANCE) continue;
            int middle = island.get(island.size() / 2);
            int nation = nationManager.getNationAt(cellCentre[middle]);
            float wealth = (float) (nationRichness.applyAsDouble(nation) * Math.exp(rand.nextGaussian() * ISLAND_WEALTH_SPREAD + ISLAND_WEALTH_SHIFT));
            wealth = Math.max(0.03f, Math.min(1f, wealth));
            // (and the shore cells round it, where its roads and houses can reach)
            for (int c : island) {
                islandRichness[c] = wealth;
                for (int n : cellNeighbours[c]) {
                    if (!cellPassable[n] && Float.isNaN(islandRichness[n])) islandRichness[n] = wealth;
                }
            }
            RoadPath.RoadClass roadClass = wealth >= ISLAND_TARMAC_RICHNESS ? RoadPath.RoadClass.LANE : RoadPath.RoadClass.DIRT;
            int wanted = 1 + Math.min(4, island.size() / 8);
            for (int t = 0, attempts = 0; t < wanted && attempts < wanted * 6; attempts++) {
                int cell = island.get(rand.nextInt(island.size()));
                float jx = (rand.nextFloat() - 0.5f) * cellSpacing * 0.6f;
                float jz = (rand.nextFloat() - 0.5f) * cellSpacing * 0.6f;
                SpherePosition start = SphereFrame.at(SphereMath.atAltitude(cellCentre[cell], 0.0)).toSphere(jx, 0.0, jz);
                if (Math.abs(Planet.latitude(start)) > TRACK_MAX_LATITUDE) continue;
                if (height(start) <= seaLevelHeight + 2f) continue;
                if (settlementManager.getUrbanness(start) > TRACK_MAX_URBANNESS) continue;
                // Out one way, then the other from the same spot, so it crosses the island
                float heading = rand.nextFloat() * (float) Math.PI * 2f;
                boolean one = growIslandRoad(start, heading, -1, rand, roadClass);
                boolean other = growIslandRoad(start, heading + (float) Math.PI, one ? roads.size() - 1 : -1, rand, roadClass);
                if (one || other) {
                    t++;
                    made++;
                }
            }
        }
        System.out.printf("[ROADS] %d roads on islands the main network missed%n", made);
    }

    private boolean growIslandRoad(SpherePosition start, float heading, int originPath, Random random, RoadPath.RoadClass roadClass) {
        if (!growTrack(start, heading, originPath, random, 3, roadClass)) return false;
        roads.get(roads.size() - 1).island = true;
        return true;
    }

    private boolean growTrack(SpherePosition start, float heading, int originPath, Random random, int minPoints) {
        return growTrack(start, heading, originPath, random, minPoints, RoadPath.RoadClass.DIRT);
    }

    private boolean growTrack(SpherePosition start, float heading, int originPath, Random random, int minPoints, RoadPath.RoadClass roadClass) {
        float length = TRACK_MIN_LENGTH + random.nextFloat() * (TRACK_MAX_LENGTH - TRACK_MIN_LENGTH);
        int steps = (int) (length / ROAD_STEP);
        RoadPath path = new RoadPath(roadClass);
        path.points.add(ground(start));
        SpherePosition at = start;
        float atHeight = height(at);
        float drift = (random.nextFloat() - 0.5f) * 0.04f;
        int startNation = nationManager.getNationAt(start);
        for (int step = 0; step < steps; step++) {
            heading += drift + (random.nextFloat() - 0.5f) * 0.45f;
            heading = flattestHeading(at, heading);
            SpherePosition next = SphereMath.move(at, heading, ROAD_STEP);
            float height = height(next);
            // Peters out at the shore, far north or south, on steep ground or at the edge of a town
            if (Math.abs(Planet.latitude(next)) > TRACK_MAX_LATITUDE || height <= seaLevelHeight + 0.5f) break;
            if (Math.abs(height - atHeight) > ROAD_STEP * 0.22f) break;
            if (settlementManager.getUrbanness(next) > TRACK_MAX_URBANNESS + 0.08f) break;
            // and at a fenced border, or at water it can't cross squarely
            if (hostile.test(startNation, nationManager.getNationAt(next))) break;
            if (!fairCrossing(at, next)) break;
            heading = (float) SphereMath.carryHeading(at, next, heading);
            at = next;
            atHeight = height;
            // Meeting another road: on to it, and done
            RoadHit hit = nearestRoad(at, LANE_JOIN_DISTANCE, step < 5 ? originPath : -1, Double.NaN, -1.0f);
            if (hit != null && step >= 3) {
                path.points.add(ground(hit.point));
                break;
            }
            path.points.add(ground(at));
        }
        if (path.points.size() < minPoints) return false;
        roads.add(path);
        indexPath(roads.size() - 1);
        return true;
    }

    // ==========================================
    //              LOOSE ENDS
    // ==========================================

    /**
     * Every highway or street end that doesn't already meet another road is
     * extended to a road just ahead of it or to the shore, or left as a dead end.
     */
    private void closeLooseEnds() {
        int pathCount = roads.size();
        for (int pathIndex = 0; pathIndex < pathCount; pathIndex++) {
            RoadPath path = roads.get(pathIndex);
            if (path.roadClass == RoadPath.RoadClass.LANE || path.roadClass == RoadPath.RoadClass.DIRT || path.points.size() < 2) {
                continue;
            }
            closeEnd(pathIndex, true);
            closeEnd(pathIndex, false);
        }
    }

    private void closeEnd(int pathIndex, boolean atStart) {
        RoadPath path = roads.get(pathIndex);
        List<SpherePosition> points = path.points;
        SpherePosition end = atStart ? points.get(0) : points.get(points.size() - 1);
        SpherePosition inner = atStart ? points.get(1) : points.get(points.size() - 2);
        if (SphereMath.distance(end, inner) < 1e-3) {
            return;
        }
        // The way the road is going as it ends
        double heading = headingFrom(inner, end);

        // Already running into another road?
        RoadHit touching = nearestRoad(end, RoadPath.RoadClass.HIGHWAY.width * 0.5f + 2.0f, pathIndex, Double.NaN, -1.0f);
        if (touching != null && touching.distance <= roads.get(touching.pathIndex).roadClass.width * 0.5f + 2.0f) {
            return;
        }

        float reach = path.roadClass == RoadPath.RoadClass.HIGHWAY ? HIGHWAY_JOIN_DISTANCE : STREET_JOIN_DISTANCE;
        RoadHit ahead = nearestRoad(end, reach, pathIndex, heading, JOIN_CONE_COS);
        if (ahead != null) {
            List<SpherePosition> connector = new ArrayList<>();
            int steps = Math.max(1, (int) Math.ceil(ahead.distance / ROAD_STEP));
            for (int step = 1; step <= steps; step++) {
                connector.add(ground(SphereMath.slerp(end, ahead.point, (double) step / steps)));
            }
            if (atStart) {
                for (SpherePosition point : connector) {
                    points.add(0, point);
                }
            } else {
                points.addAll(connector);
            }
            indexPath(pathIndex);
            return;
        }

        // Heading for the sea? Run the road on to the water's edge
        for (float d = SHORE_SEARCH_STEP; d <= SHORE_SEARCH_DISTANCE; d += SHORE_SEARCH_STEP) {
            if (height(SphereMath.move(end, heading, d)) > seaLevelHeight) {
                continue;
            }
            float reachShore = d - SHORE_STOP_BACK;
            List<SpherePosition> toShore = new ArrayList<>();
            int steps = Math.max(1, (int) Math.ceil(reachShore / ROAD_STEP));
            for (int step = 1; step <= steps && reachShore > 0; step++) {
                toShore.add(ground(SphereMath.move(end, heading, reachShore * step / steps)));
            }
            if (atStart) {
                for (SpherePosition point : toShore) {
                    points.add(0, point);
                }
            } else {
                points.addAll(toShore);
            }
            indexPath(pathIndex);
            return;
        }

        deadEnds.add(new RoadPath.DeadEnd(pathIndex, atStart));
    }

    // ==========================================
    //              ROAD INDEX
    // ==========================================

    private static final class RoadHit {
        private SpherePosition point;
        private double distance;
        private int pathIndex;
    }

    /** (Re)indexes every segment of a path, replacing any entries it had before. */
    private void indexPath(int pathIndex) {
        List<SpherePosition> points = roads.get(pathIndex).points;
        List<Filed> previous = filedByPath.remove(pathIndex);
        if (previous != null) {
            for (Filed filed : previous) {
                segmentIndex.remove(filed.position(), filed.segment());
            }
            indexedPoints.removeIf(entry -> entry[0] == pathIndex);
        }

        List<Filed> filed = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            indexedPoints.add(new int[] { pathIndex, i });
            if (i == points.size() - 1) {
                break;
            }
            SpherePosition a = points.get(i);
            SpherePosition b = points.get(i + 1);
            SpherePosition middle = SphereMath.slerp(a, b, 0.5);
            SegmentRef segment = new SegmentRef(pathIndex, i);
            segmentIndex.add(middle, SphereMath.distance(a, b) * 0.5, segment);
            filed.add(new Filed(middle, segment));
        }
        filedByPath.put(pathIndex, filed);
    }

    /**
     * Closest point on any indexed road within maxDistance (along the surface), skipping one
     * path. With a heading (not NaN), only points ahead within the cone (cosine limit) count.
     */
    private RoadHit nearestRoad(SpherePosition place, float maxDistance, int excludedPath, double heading, float coneCos) {
        RoadHit[] best = { null };
        segmentIndex.forEachNear(place, maxDistance, segment -> {
            if (segment.path() == excludedPath) {
                return;
            }
            List<SpherePosition> points = roads.get(segment.path()).points;
            SpherePosition onRoad = SphereMath.nearestOnArc(place, points.get(segment.index()), points.get(segment.index() + 1));
            double distance = SphereMath.distance(place, onRoad);
            if (distance > maxDistance || (best[0] != null && distance >= best[0].distance)) {
                return;
            }
            if (!Double.isNaN(heading) && (distance < 1e-3
                    || Math.cos(SphereMath.heading(place, onRoad) - heading) < coneCos)) {
                return;
            }
            if (best[0] == null) {
                best[0] = new RoadHit();
            }
            best[0].point = onRoad;
            best[0].distance = distance;
            best[0].pathIndex = segment.path();
        });
        return best[0];
    }

    private static float wrapAngle(float angle) {
        while (angle > Math.PI) {
            angle -= (float) (Math.PI * 2.0);
        }
        while (angle < -Math.PI) {
            angle += (float) (Math.PI * 2.0);
        }
        return angle;
    }

    // ==========================================
    //              GEOMETRY HELPERS
    // ==========================================

    /** Corner cutting along great circles that keeps both end points fixed. */
    private static List<SpherePosition> chaikin(List<SpherePosition> points) {
        if (points.size() < 3) {
            return points;
        }
        List<SpherePosition> result = new ArrayList<>();
        result.add(points.get(0));
        for (int i = 0; i < points.size() - 1; i++) {
            SpherePosition a = points.get(i);
            SpherePosition b = points.get(i + 1);
            if (i > 0) {
                result.add(SphereMath.slerp(a, b, 0.25));
            }
            if (i < points.size() - 2) {
                result.add(SphereMath.slerp(a, b, 0.75));
            }
        }
        result.add(points.get(points.size() - 1));
        return result;
    }

    /** Re-spaces a polyline so consecutive points sit about stepLength apart along the surface. */
    private static List<SpherePosition> resample(List<SpherePosition> points, float stepLength) {
        double totalLength = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            totalLength += SphereMath.distance(points.get(i), points.get(i + 1));
        }
        int stepCount = Math.max(1, (int) Math.round(totalLength / stepLength));
        double spacing = totalLength / stepCount;

        List<SpherePosition> result = new ArrayList<>();
        result.add(points.get(0));
        int segment = 0;
        double segmentStart = 0.0;
        for (int step = 1; step < stepCount; step++) {
            double target = step * spacing;
            while (segment < points.size() - 2
                    && segmentStart + SphereMath.distance(points.get(segment), points.get(segment + 1)) < target) {
                segmentStart += SphereMath.distance(points.get(segment), points.get(segment + 1));
                segment++;
            }
            SpherePosition a = points.get(segment);
            SpherePosition b = points.get(segment + 1);
            double length = SphereMath.distance(a, b);
            double t = length > 0.0 ? Math.max(0.0, Math.min(1.0, (target - segmentStart) / length)) : 0.0;
            result.add(SphereMath.slerp(a, b, t));
        }
        result.add(points.get(points.size() - 1));
        return result;
    }

    /** Splits a path where it crosses real water; short dips are kept and become causeways. */
    private List<RoadPath> splitOverWater(List<SpherePosition> points, RoadPath.RoadClass roadClass) {
        List<RoadPath> pieces = new ArrayList<>();
        RoadPath current = new RoadPath(roadClass);
        List<SpherePosition> pendingWater = new ArrayList<>();
        for (SpherePosition point : points) {
            float height = height(point);
            SpherePosition onGround = SphereMath.atAltitude(point, height);
            if (height <= seaLevelHeight + 0.5f) {
                pendingWater.add(onGround);
                continue;
            }
            if (pendingWater.size() > MAX_CAUSEWAY_POINTS) {
                if (current.points.size() >= 2) {
                    pieces.add(current);
                }
                current = new RoadPath(roadClass);
            } else if (!current.points.isEmpty()) {
                current.points.addAll(pendingWater);
            }
            pendingWater.clear();
            current.points.add(onGround);
        }
        if (current.points.size() >= 2) {
            pieces.add(current);
        }
        return pieces;
    }

    /**
     * Of a few headings either side of the one wanted, the one that climbs or falls least
     * over the next step, with a little preference for turning less, and keeping out of rivers
     * and lakes where it can (crossing a river squarely if at all).
     */
    private float flattestHeading(SpherePosition at, float heading) {
        float here = height(at);
        float best = heading, bestScore = Float.MAX_VALUE;
        for (float offset : new float[] { 0f, 0.3f, -0.3f, 0.6f, -0.6f, 0.95f, -0.95f }) {
            float h = heading + offset;
            SpherePosition ahead = SphereMath.move(at, h, ROAD_STEP);
            float grade = Math.abs(height(ahead) - here) / ROAD_STEP;
            float steep = Math.max(0f, grade - GENTLE_GRADE);
            float score = grade * grade * 8.0f + steep * steep * 300.0f + Math.abs(offset) * 0.15f;
            // (and not into water if it can help it: least of all running along a river)
            float[] crossing = crossingBetween(at, ahead);
            if (crossing != null) score += (crossing[0] > 0f ? 1.5f + 12f * crossing[1] * crossing[1] : 0f) + (crossing[2] > 0f ? 20f : 0f)
                    + Math.max(0f, crossing[3] - 1.5f * crossing[0]) * 0.3f;
            if (score < bestScore) {
                bestScore = score;
                best = h;
            }
        }
        return best;
    }

    /**
     * Nudges a road's points sideways (keeping `fixedEnds` points at each end where they are)
     * wherever that eases the climb to and from its neighbours, so the road bends round
     * steep ground; a small bending penalty keeps it from zigzagging.
     */
    private List<SpherePosition> easeGradients(List<SpherePosition> points, int fixedEnds) {
        int n = points.size();
        if (n < fixedEnds * 2 + 1) return points;
        SpherePosition[] p = points.toArray(new SpherePosition[0]);
        float[] h = new float[n];
        for (int i = 0; i < n; i++) h[i] = height(p[i]);
        for (int pass = 0; pass < EASE_PASSES; pass++) {
            for (int i = fixedEnds; i < n - fixedEnds; i++) {
                SpherePosition a = p[i - 1], c = p[i + 1];
                if (SphereMath.distance(a, c) < 1e-3) continue;
                // Sideways: square to the way from a to c, here
                double across = headingFrom(a, c, p[i]) + Math.PI * 0.5;
                SpherePosition middle = SphereMath.slerp(a, c, 0.5);
                SpherePosition bestPoint = p[i];
                float bestH = h[i];
                float bestScore = Float.MAX_VALUE;
                for (float offset : new float[] { 0f, -EASE_OFFSET * 0.5f, EASE_OFFSET * 0.5f, -EASE_OFFSET, EASE_OFFSET }) {
                    SpherePosition candidate = offset == 0f ? p[i] : SphereMath.move(p[i], across, offset);
                    float height = offset == 0f ? h[i] : height(candidate);
                    if (height <= seaLevelHeight + 1.0f) continue;
                    float la = (float) SphereMath.distance(candidate, a), lc = (float) SphereMath.distance(candidate, c);
                    float ga = Math.abs(height - h[i - 1]) / Math.max(1f, la), gc = Math.abs(h[i + 1] - height) / Math.max(1f, lc);
                    float bend = (float) SphereMath.distance(candidate, middle) / ROAD_STEP;
                    float steepA = Math.max(0f, ga - GENTLE_GRADE), steepC = Math.max(0f, gc - GENTLE_GRADE);
                    float score = (ga * ga * la + gc * gc * lc) * 10.0f + (steepA * steepA * la + steepC * steepC * lc) * 200.0f + bend * bend * 6.0f;
                    if (score < bestScore) {
                        bestScore = score;
                        bestPoint = candidate;
                        bestH = height;
                    }
                }
                p[i] = bestPoint;
                h[i] = bestH;
            }
        }
        return new ArrayList<>(Arrays.asList(p));
    }

    /** The heading at `to` of the way from `from` to `to` (for points close together). */
    private static double headingFrom(SpherePosition from, SpherePosition to) {
        return headingFrom(from, to, to);
    }

    /** The heading, in the frame at `at`, of the way from `from` to `to` (all close together). */
    private static double headingFrom(SpherePosition from, SpherePosition to, SpherePosition at) {
        return SphereFrame.at(at).headingOf(to.x() - from.x(), to.y() - from.y(), to.z() - from.z());
    }

    private float height(SpherePosition place) {
        return TerrainMesh.getLayeredHeight(place, terrainNoise);
    }

    private SpherePosition ground(SpherePosition place) {
        return TerrainMesh.groundAt(place, terrainNoise);
    }

    private static double distance(SettlementManager.Settlement a, SettlementManager.Settlement b) {
        return SphereMath.distance(a.centre, b.centre);
    }

    /** Binary min-heap of int payloads keyed by float priority; duplicates allowed. */
    private static final class MinHeap {
        private int[] items = new int[1024];
        private float[] keys = new float[1024];
        private int size;

        void push(int item, float key) {
            if (size == items.length) {
                items = Arrays.copyOf(items, size * 2);
                keys = Arrays.copyOf(keys, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >> 1;
                if (keys[parent] <= key) {
                    break;
                }
                items[i] = items[parent];
                keys[i] = keys[parent];
                i = parent;
            }
            items[i] = item;
            keys[i] = key;
        }

        int pop() {
            int top = items[0];
            size--;
            int lastItem = items[size];
            float lastKey = keys[size];
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && keys[child + 1] < keys[child]) {
                    child++;
                }
                if (keys[child] >= lastKey) {
                    break;
                }
                items[i] = items[child];
                keys[i] = keys[child];
                i = child;
            }
            items[i] = lastItem;
            keys[i] = lastKey;
            return top;
        }

        boolean isEmpty() {
            return size == 0;
        }
    }
}
