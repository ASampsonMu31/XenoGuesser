import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import com.xenoguesser.math.Vector3;

/**
 * Lays out the region's roads in three layers:
 * highways routed across the terrain between neighbouring settlements,
 * street grids filling each settlement's urban area, and winding country
 * lanes that always run from one road to another. A final pass joins any
 * loose road end to a nearby road or runs it down to the shore; any end left
 * over becomes a dead end, which InfrastructureManager finishes with a house.
 */
public class RoadNetworkBuilder {
    private static final float ROAD_STEP = 70.0f;

    // Highway routing grid
    private static final float ROUTING_CELL_SIZE = 400.0f;
    private static final float ROUTING_LAND_MARGIN = 3.0f;
    private static final float SLOPE_COST = 25.0f;
    // Following an existing highway is cheaper, so routes merge into trunk roads
    private static final float EXISTING_ROAD_COST = 0.35f;
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

    private static final float INDEX_CELL_SIZE = 200.0f;

    private final long seed;
    private final float halfRegion;
    private final float seaLevelHeight;
    private final PerlinNoise terrainNoise;
    private final SettlementManager settlementManager;
    private final NationGenerationManager nationManager;
    private final float totalRegionWidth;

    private final int routingResolution;
    private final float[] cellHeight;
    private final boolean[] cellPassable;
    private final boolean[] cellHasHighway;
    private final Map<Long, List<float[]>> highwayPointsByCell = new HashMap<>();
    private final List<RoadPath> roads = new ArrayList<>();
    private final List<RoadPath.DeadEnd> deadEnds = new ArrayList<>();

    // Spatial index of finished roads: cell -> {path index, point index} of each segment start
    private final Map<Long, List<int[]>> segmentIndex = new HashMap<>();
    private final List<int[]> indexedPoints = new ArrayList<>();
    private final Map<Integer, List<Long>> indexedCellsByPath = new HashMap<>();

    // Scratch state reused across route searches
    private final float[] routeCost;
    private final int[] routeParent;
    private final int[] routeStamp;
    private final int[] routeClosedStamp;
    private int currentStamp;

    /** Per-nation town planning: block size, block aspect and how much the streets wander. */
    private final Map<Integer, float[]> streetStyles = new HashMap<>();

    public RoadNetworkBuilder(long seed, float totalRegionWidth, float seaLevelHeight, PerlinNoise terrainNoise,
                              SettlementManager settlementManager, NationGenerationManager nationManager) {
        this.seed = seed;
        this.totalRegionWidth = totalRegionWidth;
                this.halfRegion = totalRegionWidth * 0.5f + RegionalGenerationManager.GENERATION_MARGIN;
        this.seaLevelHeight = seaLevelHeight;
        this.terrainNoise = terrainNoise;
        this.settlementManager = settlementManager;
        this.nationManager = nationManager;

                this.routingResolution = (int) Math.ceil(halfRegion * 2.0f / ROUTING_CELL_SIZE);
        int cellCount = routingResolution * routingResolution;
        this.cellHeight = new float[cellCount];
        this.cellPassable = new boolean[cellCount];
        this.cellHasHighway = new boolean[cellCount];
        this.routeCost = new float[cellCount];
        this.routeParent = new int[cellCount];
        this.routeStamp = new int[cellCount];
        this.routeClosedStamp = new int[cellCount];

        for (int j = 0; j < routingResolution; j++) {
            for (int i = 0; i < routingResolution; i++) {
                int cell = j * routingResolution + i;
                float height = TerrainMesh.getLayeredHeight(cellCentreX(i), cellCentreZ(j), terrainNoise);
                cellHeight[cell] = height;
                cellPassable[cell] = height > seaLevelHeight + ROUTING_LAND_MARGIN;
            }
        }

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
        System.out.printf("[ROADS] %d highway, %d street and %d lane paths, %d dead ends generated in %d ms%n",
                highwayCount, streetCount, laneCount, deadEnds.size(), System.currentTimeMillis() - startTime);
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
            int cell = nearestPassableCell(settlements.get(a).x, settlements.get(a).z);
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
            Arrays.sort(order, (p, q) -> Float.compare(
                    distanceSquared(origin, settlements.get(p)), distanceSquared(origin, settlements.get(q))));

            int added = 0;
            for (int k = 0; k < order.length && added < wanted; k++) {
                int b = order[k];
                if (b == a || settlementLandmass[b] != settlementLandmass[a]) {
                    continue;
                }
                if (distanceSquared(from, settlements.get(b)) > MAX_LINK_DISTANCE * MAX_LINK_DISTANCE) {
                    break;
                }
                addLink(a, b, links, linked);
                added++;
            }
        }

        // Short links first so longer routes can reuse the roads they built
        links.sort((p, q) -> Float.compare(
                distanceSquared(settlements.get(p[0]), settlements.get(p[1])),
                distanceSquared(settlements.get(q[0]), settlements.get(q[1]))));

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
        float[] bestDistance = new float[count];
        int[] bestParent = new int[count];

        for (int root = 0; root < count; root++) {
            if (inTree[root] || settlementLandmass[root] < 0) {
                continue;
            }
            int landmassId = settlementLandmass[root];
            Arrays.fill(bestDistance, Float.MAX_VALUE);
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
                    float distance = distanceSquared(settlements.get(current), settlements.get(other));
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
        int[] labels = new int[cellPassable.length];
        Arrays.fill(labels, -1);
        int[] queue = new int[cellPassable.length];
        int nextLabel = 0;
        for (int seedCell = 0; seedCell < cellPassable.length; seedCell++) {
            if (!cellPassable[seedCell] || labels[seedCell] >= 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = seedCell;
            labels[seedCell] = nextLabel;
            while (head < tail) {
                int cell = queue[head++];
                int ci = cell % routingResolution;
                int cj = cell / routingResolution;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int ni = ci + di;
                        int nj = cj + dj;
                        if (ni < 0 || nj < 0 || ni >= routingResolution || nj >= routingResolution) {
                            continue;
                        }
                        int next = nj * routingResolution + ni;
                        if (cellPassable[next] && labels[next] < 0) {
                            labels[next] = nextLabel;
                            queue[tail++] = next;
                        }
                    }
                }
            }
            nextLabel++;
        }
        return labels;
    }

    private void routeHighway(SettlementManager.Settlement from, SettlementManager.Settlement to, Random jitterRand) {
        int start = nearestPassableCell(from.x, from.z);
        int goal = nearestPassableCell(to.x, to.z);
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
        List<float[]> controlPoints = new ArrayList<>();
        for (int k = first; k <= last; k++) {
            int cell = route[k];
            float x = cellCentreX(cell % routingResolution);
            float z = cellCentreZ(cell / routingResolution);

            if (k == 0) {
                x = from.x;
                z = from.z;
            } else if (k == route.length - 1) {
                x = to.x;
                z = to.z;
            } else if ((k == first || k == last) && cellHasHighway[cell]) {
                float[] join = nearestHighwayPoint(x, z);
                if (join != null) {
                    x = join[0];
                    z = join[1];
                }
            } else {
                // Break up the routing grid so highways don't run in perfect 45 degree lines
                x += (jitterRand.nextFloat() - 0.5f) * ROUTING_CELL_SIZE * 0.6f;
                z += (jitterRand.nextFloat() - 0.5f) * ROUTING_CELL_SIZE * 0.6f;
            }
            controlPoints.add(new float[] { x, z });
        }
        if (controlPoints.size() < 2) {
            return;
        }

        List<float[]> smoothed = controlPoints;
        for (int pass = 0; pass < SMOOTHING_PASSES; pass++) {
            smoothed = chaikin(smoothed);
        }
        List<float[]> resampled = resample(smoothed, ROAD_STEP);
        for (RoadPath path : splitOverWater(resampled, RoadPath.RoadClass.HIGHWAY)) {
            roads.add(path);
            for (Vector3 point : path.points) {
                long key = routingCellKey(point.x, point.z);
                highwayPointsByCell.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new float[] { point.x, point.z });
            }
        }
    }

    /** A* over the routing grid, cost weighted by slope and discounted along existing highways. */
    private int[] findRoute(int start, int goal) {
        currentStamp++;
        int goalI = goal % routingResolution;
        int goalJ = goal / routingResolution;

        MinHeap open = new MinHeap();
        routeStamp[start] = currentStamp;
        routeCost[start] = 0.0f;
        routeParent[start] = -1;
        open.push(start, heuristic(start, goalI, goalJ));

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

            int ci = cell % routingResolution;
            int cj = cell / routingResolution;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) {
                        continue;
                    }
                    int ni = ci + di;
                    int nj = cj + dj;
                    if (ni < 0 || nj < 0 || ni >= routingResolution || nj >= routingResolution) {
                        continue;
                    }
                    int next = nj * routingResolution + ni;
                    if (!cellPassable[next] || routeClosedStamp[next] == currentStamp) {
                        continue;
                    }

                    float stepLength = ROUTING_CELL_SIZE * ((di != 0 && dj != 0) ? 1.41421356f : 1.0f);
                    float midX = (cellCentreX(ci) + cellCentreX(ni)) * 0.5f;
                    float midZ = (cellCentreZ(cj) + cellCentreZ(nj)) * 0.5f;
                    if (TerrainMesh.getLayeredHeight(midX, midZ, terrainNoise) <= seaLevelHeight + 1.0f) {
                        continue;
                    }

                    float slope = Math.abs(cellHeight[next] - cellHeight[cell]) / stepLength;
                    float stepCost = stepLength * (1.0f + SLOPE_COST * slope * slope);
                    if (cellHasHighway[next]) {
                        stepCost *= EXISTING_ROAD_COST;
                    }

                    float cost = routeCost[cell] + stepCost;
                    if (routeStamp[next] != currentStamp || cost < routeCost[next]) {
                        routeStamp[next] = currentStamp;
                        routeCost[next] = cost;
                        routeParent[next] = cell;
                        open.push(next, cost + heuristic(next, goalI, goalJ));
                    }
                }
            }
        }
        return null;
    }

    private float heuristic(int cell, int goalI, int goalJ) {
        int dx = Math.abs(cell % routingResolution - goalI);
        int dz = Math.abs(cell / routingResolution - goalJ);
        float octile = Math.max(dx, dz) + 0.41421356f * Math.min(dx, dz);
        return octile * ROUTING_CELL_SIZE * HEURISTIC_WEIGHT;
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

    private int nearestPassableCell(float x, float z) {
        int ci = clampCell((int) Math.floor((x + halfRegion) / ROUTING_CELL_SIZE));
        int cj = clampCell((int) Math.floor((z + halfRegion) / ROUTING_CELL_SIZE));
        for (int radius = 0; radius <= 3; radius++) {
            for (int dj = -radius; dj <= radius; dj++) {
                for (int di = -radius; di <= radius; di++) {
                    int ni = ci + di;
                    int nj = cj + dj;
                    if (ni >= 0 && nj >= 0 && ni < routingResolution && nj < routingResolution
                            && cellPassable[nj * routingResolution + ni]) {
                        return nj * routingResolution + ni;
                    }
                }
            }
        }
        return -1;
    }

    private float[] nearestHighwayPoint(float x, float z) {
        float[] best = null;
        float bestDistance = JOIN_SNAP_DISTANCE * JOIN_SNAP_DISTANCE;
        int ci = (int) Math.floor((x + halfRegion) / ROUTING_CELL_SIZE);
        int cj = (int) Math.floor((z + halfRegion) / ROUTING_CELL_SIZE);
        for (int dj = -2; dj <= 2; dj++) {
            for (int di = -2; di <= 2; di++) {
                List<float[]> points = highwayPointsByCell.get(((long) (ci + di) << 32) ^ (cj + dj));
                if (points == null) {
                    continue;
                }
                for (float[] point : points) {
                    float dx = point[0] - x;
                    float dz = point[1] - z;
                    float distance = dx * dx + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = point;
                    }
                }
            }
        }
        return best;
    }

    // ==========================================
    //              CITY STREETS
    // ==========================================

    private void buildStreets() {
        Random streetRand = new Random(seed + 13579L);
        PerlinNoise wanderNoise = new PerlinNoise(seed + 97531L);

        for (SettlementManager.Settlement settlement : settlementManager.getSettlements()) {
            float extent = settlement.radiusAtUrbanness(STREET_URBANNESS);
            float heading = streetRand.nextFloat() * (float) Math.PI;
            float phaseA = streetRand.nextFloat();
            float phaseB = streetRand.nextFloat();
            if (extent < ROAD_STEP * 2.0f) {
                continue;
            }

                        int nationId = nationManager.getNationAtWorld(settlement.x, settlement.z, totalRegionWidth);
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

            layStreetFamily(settlement, extent, heading, blockSize, phaseA, wander, wanderFrequency, wanderNoise);
            layStreetFamily(settlement, extent, heading + (float) Math.PI * 0.5f, blockSize * blockAspect,
                    phaseB, wander, wanderFrequency, wanderNoise);
        }
    }

    /** One set of parallel streets across the settlement, cut wherever it leaves the urban area or meets water. */
    private void layStreetFamily(SettlementManager.Settlement settlement, float extent, float heading,
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

            List<float[]> run = new ArrayList<>();
            for (float t = -extent; t <= extent + 0.001f; t += ROAD_STEP) {
                                float drift = wander * wanderNoise.eval(t * wanderFrequency, noiseRow);
                float x = settlement.x + dirX * t + sideX * (offset + drift);
                float z = settlement.z + dirZ * t + sideZ * (offset + drift);

                boolean inTown = settlementManager.getUrbanness(x, z) >= STREET_URBANNESS
                        && settlementManager.dominantSettlementAt(x, z) == settlement
                        && TerrainMesh.getLayeredHeight(x, z, terrainNoise) > seaLevelHeight + 0.5f;
                if (inTown) {
                    run.add(new float[] { x, z });
                } else {
                    emitStreet(run);
                    run = new ArrayList<>();
                }
            }
            emitStreet(run);
        }
    }

    private void emitStreet(List<float[]> run) {
        if (run.size() < MIN_STREET_POINTS) {
            return;
        }
        RoadPath path = new RoadPath(RoadPath.RoadClass.STREET);
        for (float[] point : run) {
            path.points.add(groundPoint(point[0], point[1]));
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
            Vector3 origin = highway.points.get(index);
            if (settlementManager.getUrbanness(origin.x, origin.z) > LANE_START_MAX_URBANNESS) {
                continue;
            }
            Vector3 previous = highway.points.get(index - 1);
            float along = (float) Math.atan2(origin.z - previous.z, origin.x - previous.x);
            float side = laneRand.nextBoolean() ? 1.0f : -1.0f;
            float heading = along + side * ((float) Math.PI * 0.5f + (laneRand.nextFloat() - 0.5f) * 0.9f);
            growLane(origin.x, origin.z, heading, highwayIndex, laneRand, 0);
        }

        // Each settlement also sends lanes out from the streets at its edge towards other roads
        for (SettlementManager.Settlement settlement : settlementManager.getSettlements()) {
            int laneCount = settlement.rank < CITY_RANK_LIMIT ? 3 : 1 + laneRand.nextInt(2);
            float edge = Math.max(ROAD_STEP, settlement.radiusAtUrbanness(LANE_START_MAX_URBANNESS));
            for (int lane = 0; lane < laneCount; lane++) {
                float heading = laneRand.nextFloat() * (float) Math.PI * 2.0f;
                float edgeX = settlement.x + (float) Math.cos(heading) * edge;
                float edgeZ = settlement.z + (float) Math.sin(heading) * edge;
                RoadHit anchor = nearestRoad(edgeX, edgeZ, edge, -1, null, -1.0f);
                if (anchor != null) {
                    growLane(anchor.x, anchor.z, heading, anchor.pathIndex, laneRand, 0);
                }
            }
        }
    }

    /**
     * Wanders from a point on one road towards a point on another and keeps
     * the lane only if it arrives, so lanes never end in open country.
     */
    private boolean growLane(float startX, float startZ, float heading, int originPath, Random random, int depth) {
        float[] target = pickLaneTarget(startX, startZ, heading, originPath, random);
        if (target == null) {
            return false;
        }
        float targetDistance = (float) Math.hypot(target[0] - startX, target[1] - startZ);
        int maxSteps = (int) (targetDistance / ROAD_STEP * 2.0f) + 6;

        RoadPath path = new RoadPath(RoadPath.RoadClass.LANE);
        path.points.add(groundPoint(startX, startZ));
        float x = startX;
        float z = startZ;
        boolean joined = false;

        for (int step = 0; step < maxSteps && !joined; step++) {
            float desired = (float) Math.atan2(target[1] - z, target[0] - x);
            float turn = wrapAngle(desired - heading) * LANE_STEERING;
            turn = Math.max(-0.35f, Math.min(0.35f, turn));
            heading += turn + (random.nextFloat() - 0.5f) * LANE_WANDER;
            x += (float) Math.cos(heading) * ROAD_STEP;
            z += (float) Math.sin(heading) * ROAD_STEP;

            if (Math.abs(x) > halfRegion || Math.abs(z) > halfRegion
                    || TerrainMesh.getLayeredHeight(x, z, terrainNoise) <= seaLevelHeight + 0.5f) {
                return false;
            }

            // Leave the road we set out from before we can join anything
            int excluded = step < 4 ? originPath : -1;
            RoadHit hit = nearestRoad(x, z, LANE_JOIN_DISTANCE, excluded, null, -1.0f);
            if (hit != null) {
                path.points.add(groundPoint(hit.x, hit.z));
                joined = true;
            } else {
                path.points.add(groundPoint(x, z));
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
            Vector3 branchStart = path.points.get(branchIndex);
            Vector3 before = path.points.get(branchIndex - 1);
            float along = (float) Math.atan2(branchStart.z - before.z, branchStart.x - before.x);
            float branchHeading = along + (random.nextBoolean() ? 1.0f : -1.0f) * (0.9f + random.nextFloat() * 0.6f);
            growLane(branchStart.x, branchStart.z, branchHeading, laneIndex, random, depth + 1);
        }
        return true;
    }

    /** A point on some other road, a sensible distance away and roughly in the direction of travel. */
    private float[] pickLaneTarget(float x, float z, float heading, int originPath, Random random) {
        if (indexedPoints.isEmpty()) {
            return null;
        }
        float headingX = (float) Math.cos(heading);
        float headingZ = (float) Math.sin(heading);
        for (int attempt = 0; attempt < 60; attempt++) {
            int[] entry = indexedPoints.get(random.nextInt(indexedPoints.size()));
            if (entry[0] == originPath) {
                continue;
            }
            Vector3 point = roads.get(entry[0]).points.get(entry[1]);
            float dx = point.x - x;
            float dz = point.z - z;
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance < LANE_TARGET_MIN_DISTANCE || distance > LANE_TARGET_MAX_DISTANCE) {
                continue;
            }
            if ((dx * headingX + dz * headingZ) / distance < 0.2f) {
                continue;
            }
            return new float[] { point.x, point.z };
        }
        return null;
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
            if (path.roadClass == RoadPath.RoadClass.LANE || path.points.size() < 2) {
                continue;
            }
            closeEnd(pathIndex, true);
            closeEnd(pathIndex, false);
        }
    }

    private void closeEnd(int pathIndex, boolean atStart) {
        RoadPath path = roads.get(pathIndex);
        List<Vector3> points = path.points;
        Vector3 end = atStart ? points.get(0) : points.get(points.size() - 1);
        Vector3 inner = atStart ? points.get(1) : points.get(points.size() - 2);
        float dirX = end.x - inner.x;
        float dirZ = end.z - inner.z;
        float length = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1e-3f) {
            return;
        }
        dirX /= length;
        dirZ /= length;

        // Already running into another road?
        RoadHit touching = nearestRoad(end.x, end.z, RoadPath.RoadClass.HIGHWAY.width * 0.5f + 2.0f, pathIndex, null, -1.0f);
        if (touching != null && touching.distance <= roads.get(touching.pathIndex).roadClass.width * 0.5f + 2.0f) {
            return;
        }

        float reach = path.roadClass == RoadPath.RoadClass.HIGHWAY ? HIGHWAY_JOIN_DISTANCE : STREET_JOIN_DISTANCE;
        RoadHit ahead = nearestRoad(end.x, end.z, reach, pathIndex, new float[] { dirX, dirZ }, JOIN_CONE_COS);
        if (ahead != null) {
            List<Vector3> connector = new ArrayList<>();
            int steps = Math.max(1, (int) Math.ceil(ahead.distance / ROAD_STEP));
            for (int step = 1; step <= steps; step++) {
                float t = (float) step / steps;
                connector.add(groundPoint(end.x + (ahead.x - end.x) * t, end.z + (ahead.z - end.z) * t));
            }
            if (atStart) {
                for (Vector3 point : connector) {
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
            if (TerrainMesh.getLayeredHeight(end.x + dirX * d, end.z + dirZ * d, terrainNoise) > seaLevelHeight) {
                continue;
            }
            float reachShore = d - SHORE_STOP_BACK;
            List<Vector3> toShore = new ArrayList<>();
            int steps = Math.max(1, (int) Math.ceil(reachShore / ROAD_STEP));
            for (int step = 1; step <= steps && reachShore > 0; step++) {
                float t = reachShore * step / steps;
                toShore.add(groundPoint(end.x + dirX * t, end.z + dirZ * t));
            }
            if (atStart) {
                for (Vector3 point : toShore) {
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
        private float x;
        private float z;
        private float distance;
        private int pathIndex;
    }

    /** (Re)indexes every segment of a path, replacing any entries it had before. */
    private void indexPath(int pathIndex) {
        List<Vector3> points = roads.get(pathIndex).points;
        List<Long> previousCells = indexedCellsByPath.remove(pathIndex);
        if (previousCells != null) {
            for (long key : previousCells) {
                List<int[]> bucket = segmentIndex.get(key);
                if (bucket != null) {
                    bucket.removeIf(entry -> entry[0] == pathIndex);
                }
            }
            indexedPoints.removeIf(entry -> entry[0] == pathIndex);
        }

        List<Long> cells = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            indexedPoints.add(new int[] { pathIndex, i });
            if (i == points.size() - 1) {
                break;
            }
            Vector3 a = points.get(i);
            Vector3 b = points.get(i + 1);
            int minI = indexCell(Math.min(a.x, b.x));
            int maxI = indexCell(Math.max(a.x, b.x));
            int minJ = indexCell(Math.min(a.z, b.z));
            int maxJ = indexCell(Math.max(a.z, b.z));
            for (int j = minJ; j <= maxJ; j++) {
                for (int k = minI; k <= maxI; k++) {
                    long key = indexKey(k, j);
                    segmentIndex.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new int[] { pathIndex, i });
                    cells.add(key);
                }
            }
        }
        indexedCellsByPath.put(pathIndex, cells);
    }

    /**
     * Closest point on any indexed road within maxDistance, skipping one path.
     * With a direction, only points ahead within the cone (cosine limit) count.
     */
    private RoadHit nearestRoad(float x, float z, float maxDistance, int excludedPath, float[] direction, float coneCos) {
        RoadHit best = null;
        int minI = indexCell(x - maxDistance);
        int maxI = indexCell(x + maxDistance);
        int minJ = indexCell(z - maxDistance);
        int maxJ = indexCell(z + maxDistance);
        for (int j = minJ; j <= maxJ; j++) {
            for (int k = minI; k <= maxI; k++) {
                List<int[]> bucket = segmentIndex.get(indexKey(k, j));
                if (bucket == null) {
                    continue;
                }
                for (int[] entry : bucket) {
                    if (entry[0] == excludedPath) {
                        continue;
                    }
                    List<Vector3> points = roads.get(entry[0]).points;
                    Vector3 a = points.get(entry[1]);
                    Vector3 b = points.get(entry[1] + 1);
                    float dx = b.x - a.x;
                    float dz = b.z - a.z;
                    float lengthSquared = dx * dx + dz * dz;
                    float t = lengthSquared > 0.0f ? ((x - a.x) * dx + (z - a.z) * dz) / lengthSquared : 0.0f;
                    t = Math.max(0.0f, Math.min(1.0f, t));
                    float px = a.x + dx * t;
                    float pz = a.z + dz * t;
                    float offsetX = px - x;
                    float offsetZ = pz - z;
                    float distance = (float) Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
                    if (distance > maxDistance || (best != null && distance >= best.distance)) {
                        continue;
                    }
                    if (direction != null && (distance < 1e-3f
                            || (offsetX * direction[0] + offsetZ * direction[1]) / distance < coneCos)) {
                        continue;
                    }
                    if (best == null) {
                        best = new RoadHit();
                    }
                    best.x = px;
                    best.z = pz;
                    best.distance = distance;
                    best.pathIndex = entry[0];
                }
            }
        }
        return best;
    }

    private int indexCell(float coordinate) {
        return (int) Math.floor((coordinate + halfRegion) / INDEX_CELL_SIZE);
    }

    private static long indexKey(int i, int j) {
        return ((long) i << 32) | (j & 0xFFFFFFFFL);
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

    /** Corner cutting that keeps both end points fixed. */
    private static List<float[]> chaikin(List<float[]> points) {
        if (points.size() < 3) {
            return points;
        }
        List<float[]> result = new ArrayList<>();
        result.add(points.get(0));
        for (int i = 0; i < points.size() - 1; i++) {
            float[] a = points.get(i);
            float[] b = points.get(i + 1);
            if (i > 0) {
                result.add(new float[] { a[0] * 0.75f + b[0] * 0.25f, a[1] * 0.75f + b[1] * 0.25f });
            }
            if (i < points.size() - 2) {
                result.add(new float[] { a[0] * 0.25f + b[0] * 0.75f, a[1] * 0.25f + b[1] * 0.75f });
            }
        }
        result.add(points.get(points.size() - 1));
        return result;
    }

    /** Re-spaces a polyline so consecutive points sit about stepLength apart. */
    private static List<float[]> resample(List<float[]> points, float stepLength) {
        float totalLength = 0.0f;
        for (int i = 0; i < points.size() - 1; i++) {
            totalLength += distance(points.get(i), points.get(i + 1));
        }
        int stepCount = Math.max(1, Math.round(totalLength / stepLength));
        float spacing = totalLength / stepCount;

        List<float[]> result = new ArrayList<>();
        result.add(points.get(0));
        int segment = 0;
        float segmentStart = 0.0f;
        for (int step = 1; step < stepCount; step++) {
            float target = step * spacing;
            while (segment < points.size() - 2
                    && segmentStart + distance(points.get(segment), points.get(segment + 1)) < target) {
                segmentStart += distance(points.get(segment), points.get(segment + 1));
                segment++;
            }
            float[] a = points.get(segment);
            float[] b = points.get(segment + 1);
            float length = distance(a, b);
            float t = length > 0.0f ? Math.min(1.0f, (target - segmentStart) / length) : 0.0f;
            result.add(new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t });
        }
        result.add(points.get(points.size() - 1));
        return result;
    }

    /** Splits a path where it crosses real water; short dips are kept and become causeways. */
    private List<RoadPath> splitOverWater(List<float[]> points, RoadPath.RoadClass roadClass) {
        List<RoadPath> pieces = new ArrayList<>();
        RoadPath current = new RoadPath(roadClass);
        List<Vector3> pendingWater = new ArrayList<>();
        for (float[] point : points) {
            Vector3 ground = groundPoint(point[0], point[1]);
            if (ground.y <= seaLevelHeight + 0.5f) {
                pendingWater.add(ground);
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
            current.points.add(ground);
        }
        if (current.points.size() >= 2) {
            pieces.add(current);
        }
        return pieces;
    }

    private Vector3 groundPoint(float x, float z) {
        return new Vector3(x, TerrainMesh.getLayeredHeight(x, z, terrainNoise), z);
    }

    private float cellCentreX(int i) {
        return (i + 0.5f) * ROUTING_CELL_SIZE - halfRegion;
    }

    private float cellCentreZ(int j) {
        return (j + 0.5f) * ROUTING_CELL_SIZE - halfRegion;
    }

    private int clampCell(int index) {
        return Math.max(0, Math.min(routingResolution - 1, index));
    }

    private long routingCellKey(float x, float z) {
        int ci = (int) Math.floor((x + halfRegion) / ROUTING_CELL_SIZE);
        int cj = (int) Math.floor((z + halfRegion) / ROUTING_CELL_SIZE);
        return ((long) ci << 32) ^ cj;
    }

    private static float distance(float[] a, float[] b) {
        float dx = b[0] - a[0];
        float dz = b[1] - a[1];
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private static float distanceSquared(SettlementManager.Settlement a, SettlementManager.Settlement b) {
        float dx = a.x - b.x;
        float dz = a.z - b.z;
        return dx * dx + dz * dz;
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
