import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Turns the road network into renderable infrastructure: road surfaces and
 * markings, highway guard rails, cul-de-sacs, houses with their gardens and
 * fences, and signs. Houses are laid out once for the whole region along the
 * roads; everything is then baked per chunk into a handful of merged meshes.
 */
public class InfrastructureManager {
    private static final float ROAD_SURFACE_OFFSET = 2.0f;
    private static final int ROAD_SEGMENT_SUBDIVISIONS = 8;
    private static final int LINE_SAMPLES_PER_SEGMENT = 24;
    private static final float ROAD_LINE_SURFACE_OFFSET = 0.35f;
    private static final float MAX_ROAD_HALF_WIDTH = RoadPath.RoadClass.HIGHWAY.width * 0.5f;
    // Resampled paths space their points up to about 1.5 road steps apart
    private static final float MAX_ROAD_SEGMENT_HALF_LENGTH = 60.0f;

    // Wide roads get dashed lane dividers between the nation's own markings
    private static final float LANE_DIVIDER_MIN_WIDTH = 40.0f;
    private static final float LANE_DIVIDER_DASH = 12.0f;
    private static final float LANE_DIVIDER_GAP = 18.0f;
    private static final float GIVE_WAY_LINE_LENGTH = 2.4f;

    // Guard rails line rural highways in nations that build them
    private static final float RAIL_MAX_URBANNESS = 0.2f;
    private static final float RAIL_OFFSET = 3.0f;
    private static final float RAIL_JUNCTION_CLEARANCE = 8.0f;

    // Roadside signs roll once per road segment rather than per attempt, so scale the nation's sign chance up
    private static final double ROADSIDE_SIGN_CHANCE_SCALE = 40.0;
    // Busy streets carry far more signage than country roads
    private static final double URBAN_SIGN_BOOST = 3.0;
    private static final float ROADSIDE_SIGN_MARGIN = 8.0f;
    private static final float SIGN_SPACING = 36.0f;

    // Houses per 100 units of road per side: a rural trickle per nation, rising steeply with urbanness
    private static final double RURAL_FRONTAGE_SCALE = 200.0;
    private static final float URBAN_FRONTAGE_MIN = 1.0f;
    private static final float URBAN_FRONTAGE_RANGE = 0.6f;
    private static final float URBAN_DENSITY_EXPONENT = 1.3f;
    // More placement attempts than wanted houses, since some land on slopes, roads or neighbours
    private static final float PLACEMENT_OVERSAMPLE = 2.5f;

    // Town houses sit right at the kerb; out in the country they stand back behind a front garden
    private static final float KERB_SETBACK_MIN = 10.0f;
    private static final float KERB_SETBACK_RANGE = 6.0f;
    private static final float MAX_FRONT_GARDEN = 70.0f;
    private static final float FRONT_FENCE_GAP = 3.0f;
    private static final float PLOT_GAP = 2.0f;
    private static final float PLOT_ROAD_MARGIN = 2.0f;
    private static final float PLOT_HIGHWAY_MARGIN = 7.0f;
    private static final float GATE_HALF_WIDTH = 7.0f;
    private static final float BUILDING_MAX_GROUND_DROP = 25.0f;
    private static final float BUILDING_FOUNDATION_DEPTH = 3.0f;
    private static final float DOOR_THICKNESS = 1.5f;
    private static final float GLASS_OFFSET = 0.35f;
    private static final float FRAME_OFFSET = 0.2f;
    private static final float PLOT_INDEX_CELL = 250.0f;

    private static final Material ASPHALT = new Material(
        new Vector3(0.18f, 0.18f, 0.18f),
        new Vector3(0.28f, 0.28f, 0.28f),
        new Vector3(0.01f, 0.01f, 0.01f),
        1.0f
    );

    private final Map<Integer, Double> nationSignProbabilities;
    private final Map<Integer, Double> nationBuildingProbabilities;
    private final Map<Integer, Float> nationUrbanDensities;
    private final Map<Integer, RoadLineStyle> roadLineStyles;
    private final Map<Integer, BuildingStyle> buildingStyles;
    private final Map<Integer, GuardRailStyle> guardRailStyles;
    private final Map<Integer, FenceStyle> fenceStyles;
    private final Map<String, Material> materials = new HashMap<>();
    private final NationGenerationManager nationManager;
    private final SettlementManager settlementManager;
    private final XenoGuesser_GLEventListener mainListener;
    private final long worldSeed;
    private final List<RoadPath> roadNetwork = new ArrayList<>();
    private final List<RoadPath.CulDeSac> culDeSacs = new ArrayList<>();
    private final Map<Long, List<RoadSegment>> roadSegmentsByChunk = new HashMap<>();
    private final List<House> houses = new ArrayList<>();
    private final Map<Long, List<House>> housesByChunk = new HashMap<>();
    private float maxHouseReach;
    private float maxPlotReach;
    private volatile boolean roadNetworkInitialized;
    private float roadChunkSize;
    private float regionWidth;
    private float seaLevel;
    private PerlinNoise terrainNoise;

    // Unit building meshes shared by every house, plus each nation's roof
    private final float[] wallVertices;
    private final int[] wallIndices;
    private final float[] boxVertices;
    private final int[] boxIndices;
    private final Map<Integer, float[]> roofVertices = new HashMap<>();
    private final Map<Integer, int[]> roofIndices = new HashMap<>();

    private static final class RoadSegment {
        private final Vector3 start;
        private final Vector3 end;
        private final Vector3 startTangent;
        private final Vector3 endTangent;
        private final int nationId;
        private final int pathIndex;
        private final RoadPath.RoadClass roadClass;
        private final float startDistance;
        private final float length;
        private final float width;
        private final boolean culDeSac;
        private boolean railed;

        private RoadSegment(Vector3 start, Vector3 end, Vector3 startTangent, Vector3 endTangent, int nationId,
                            int pathIndex, RoadPath.RoadClass roadClass, float startDistance, float length,
                            float width, boolean culDeSac) {
            this.start = start;
            this.end = end;
            this.startTangent = startTangent;
            this.endTangent = endTangent;
            this.nationId = nationId;
            this.pathIndex = pathIndex;
            this.roadClass = roadClass;
            this.startDistance = startDistance;
            this.length = length;
            this.width = width;
            this.culDeSac = culDeSac;
        }

        /** Junction priority; turning circles always swallow any paint that reaches them. */
        private int rank() {
            return culDeSac ? 3 : roadClass.rank;
        }
    }

    /** A house on its plot. Local +Z (the door side) faces the road it fronts. */
    private static final class House {
        private float x;
        private float z;
        private float rotationY;
        private int nationId;
        private float sizeScale;
        private float width;
        private float depth;
        private float setback;
        private float baseY;
        private float mainWallHeight;
        private float doorBase;
        private int extensionSide;
        private float extensionWidth;
        private float extensionDepth;
        private float extensionWallHeight;
        private boolean fenced;
        private float plotMinX;
        private float plotMaxX;
        private float plotMinZ;
        private float plotMaxZ;
        private float priority;
        private long seed;
        private int frontagePath;

        private float plotCentreLocalX() {
            return (plotMinX + plotMaxX) * 0.5f;
        }

        private float plotCentreLocalZ() {
            return (plotMinZ + plotMaxZ) * 0.5f;
        }

        private float plotRadius() {
            float hx = (plotMaxX - plotMinX) * 0.5f;
            float hz = (plotMaxZ - plotMinZ) * 0.5f;
            return (float) Math.sqrt(hx * hx + hz * hz);
        }

        /** Bounds of the building itself (house plus any extension) in local space. */
        private float houseMinX() {
            return -width * 0.5f - (extensionSide < 0 ? extensionWidth : 0.0f);
        }

        private float houseMaxX() {
            return width * 0.5f + (extensionSide > 0 ? extensionWidth : 0.0f);
        }
    }

    @FunctionalInterface
    public interface RoadPathVisitor {
        void visit(RoadPath path);
    }

    @FunctionalInterface
    public interface BuildingVisitor {
        void visit(float x, float z, float rotationY, float width, float depth, int nationId);
    }

    public InfrastructureManager(long seed, int numNations, NationGenerationManager nationManager,
                                 SettlementManager settlementManager, XenoGuesser_GLEventListener mainListener) {
        this.worldSeed = seed;
        this.nationManager = nationManager;
        this.settlementManager = settlementManager;
        this.mainListener = mainListener;
        this.nationSignProbabilities = new HashMap<>();
        this.nationBuildingProbabilities = new HashMap<>();
        this.nationUrbanDensities = new HashMap<>();

        Random rand = new Random(seed + 5555L);
        Random buildingRand = new Random(seed + 6666L);
        Random urbanRand = new Random(seed + 4242L);

        for (int i = 1; i <= numNations; i++) {
            double prob = 0.00025 + (rand.nextDouble() * 0.00125);
            nationSignProbabilities.put(i, prob);
            nationBuildingProbabilities.put(i, 0.00025 + (buildingRand.nextDouble() * 0.00125));
            nationUrbanDensities.put(i, URBAN_FRONTAGE_MIN + urbanRand.nextFloat() * URBAN_FRONTAGE_RANGE);
        }

        this.roadLineStyles = RoadLineStyle.generateForNations(seed, numNations);
        this.buildingStyles = BuildingStyle.generateForNations(seed, numNations);
        this.guardRailStyles = GuardRailStyle.generateForNations(seed, numNations);
        this.fenceStyles = FenceStyle.generateForNations(seed, numNations);

        MeshBuilder walls = BuildingMeshes.createWalls();
        MeshBuilder box = BuildingMeshes.createBox();
        this.wallVertices = walls.vertexArray();
        this.wallIndices = walls.indexArray();
        this.boxVertices = box.vertexArray();
        this.boxIndices = box.indexArray();
        for (Map.Entry<Integer, BuildingStyle> entry : buildingStyles.entrySet()) {
            MeshBuilder roof = BuildingMeshes.createRoof(entry.getValue());
            roofVertices.put(entry.getKey(), roof.vertexArray());
            roofIndices.put(entry.getKey(), roof.indexArray());
        }
    }

    public RoadLineStyle getRoadLineStyle(int nationId) {
        return roadLineStyles.get(nationId);
    }

    public BuildingStyle getBuildingStyle(int nationId) {
        return buildingStyles.get(nationId);
    }

    public float getUrbanness(float worldX, float worldZ) {
        return settlementManager.getUrbanness(worldX, worldZ);
    }

    // ==========================================
    //          NETWORK PREPARATION
    // ==========================================

    public void prepareRoadNetwork(float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        if (roadNetworkInitialized) {
            return;
        }
        roadChunkSize = chunkSize;
        regionWidth = totalRegionWidth;
        seaLevel = seaLevelHeight;
        terrainNoise = noise;

        RoadNetworkBuilder builder = new RoadNetworkBuilder(worldSeed, totalRegionWidth, seaLevelHeight, noise,
                settlementManager, nationManager);
        roadNetwork.addAll(builder.build());
        culDeSacs.addAll(builder.getCulDeSacs());

        for (int pathIndex = 0; pathIndex < roadNetwork.size(); pathIndex++) {
            RoadPath path = roadNetwork.get(pathIndex);
            float width = path.roadClass.width;
            // Running distance along the path keeps dash patterns continuous across segments
            float distanceAlongPath = 0.0f;
            for (int i = 0; i < path.points.size() - 1; i++) {
                Vector3 start = path.points.get(i);
                Vector3 end = path.points.get(i + 1);
                float length = (float) Math.sqrt((end.x - start.x) * (end.x - start.x) + (end.z - start.z) * (end.z - start.z));
                if (length < 0.01f) {
                    continue;
                }
                Vector3 startTangent = roadTangent(path.points, i == 0 ? i : i - 1, i + 1);
                Vector3 endTangent = roadTangent(path.points, i, i + 1 == path.points.size() - 1 ? i + 1 : i + 2);
                float midpointX = (start.x + end.x) * 0.5f;
                float midpointZ = (start.z + end.z) * 0.5f;
                int nationId = nationManager.getNationAtWorld(midpointX, midpointZ, totalRegionWidth);
                RoadSegment segment = new RoadSegment(start, end, startTangent, endTangent, nationId, pathIndex,
                        path.roadClass, distanceAlongPath, length, width, false);

                GuardRailStyle railStyle = guardRailStyles.get(nationId);
                segment.railed = path.roadClass == RoadPath.RoadClass.HIGHWAY && railStyle != null && railStyle.enabled
                        && settlementManager.getUrbanness(midpointX, midpointZ) < RAIL_MAX_URBANNESS;

                roadSegmentsByChunk.computeIfAbsent(chunkKeyAt(midpointX, midpointZ), ignored -> new ArrayList<>()).add(segment);
                distanceAlongPath += length;
            }
        }

        for (RoadPath.CulDeSac culDeSac : culDeSacs) {
            Vector3 centre = new Vector3(culDeSac.x, roadSurfaceY(culDeSac.x, culDeSac.z), culDeSac.z);
            Vector3 tangent = new Vector3(1.0f, 0.0f, 0.0f);
            int nationId = nationManager.getNationAtWorld(culDeSac.x, culDeSac.z, totalRegionWidth);
            RoadSegment segment = new RoadSegment(centre, centre, tangent, tangent, nationId, -1,
                    RoadPath.RoadClass.STREET, 0.0f, 0.0f, culDeSac.radius * 2.0f, true);
            roadSegmentsByChunk.computeIfAbsent(chunkKeyAt(culDeSac.x, culDeSac.z), ignored -> new ArrayList<>()).add(segment);
        }

        long startTime = System.currentTimeMillis();
        placeHouses();
        System.out.printf("[HOUSES] %d houses placed along the roads in %d ms%n", houses.size(), System.currentTimeMillis() - startTime);
        roadNetworkInitialized = true;
    }

    /** Visits every road polyline in the region, e.g. for drawing a road map. */
    public void forEachRoadPath(RoadPathVisitor visitor) {
        for (RoadPath path : roadNetwork) {
            visitor.visit(path);
        }
    }

    public List<RoadPath.CulDeSac> getCulDeSacs() {
        return culDeSacs;
    }

    /** Visits every building footprint in the region; extensions are visited as their own rectangle. */
    public void forEachBuilding(BuildingVisitor visitor) {
        for (House house : houses) {
            visitor.visit(house.x, house.z, house.rotationY, house.width, house.depth, house.nationId);
            if (house.extensionSide != 0) {
                float[] centre = localToWorld(house.x, house.z, house.rotationY,
                        house.extensionSide * (house.width + house.extensionWidth) * 0.5f,
                        (house.extensionDepth - house.depth) * 0.5f);
                visitor.visit(centre[0], centre[1], house.rotationY, house.extensionWidth, house.extensionDepth, house.nationId);
            }
        }
    }

    // ==========================================
    //          SPATIAL QUERIES
    // ==========================================

    /** Whether a point lies within clearance of the edge of any road or turning circle. */
    public boolean isRoadLocation(float worldX, float worldZ, float clearance) {
        for (RoadSegment segment : segmentsNear(worldX, worldZ, clearance + MAX_ROAD_HALF_WIDTH)) {
            float limit = segment.width * 0.5f + clearance;
            if (distanceSquaredToSegment(worldX, worldZ, segment.start, segment.end) <= limit * limit) {
                return true;
            }
        }
        return false;
    }

    /** Whether a point lies within clearance of any house (including its extension). */
    public boolean isBuildingLocation(float worldX, float worldZ, float clearance) {
        if (!roadNetworkInitialized) {
            return false;
        }
        for (House house : housesNear(worldX, worldZ, maxHouseReach + clearance)) {
            float[] local = worldToLocal(house, worldX, worldZ);
            if (local[0] >= house.houseMinX() - clearance && local[0] <= house.houseMaxX() + clearance
                    && local[1] >= -house.depth * 0.5f - clearance && local[1] <= house.depth * 0.5f + clearance) {
                return true;
            }
        }
        return false;
    }

    /** Whether a point lies inside any house plot (garden and fence included). */
    private boolean isPlotLocation(float worldX, float worldZ, float clearance) {
        for (House house : housesNear(worldX, worldZ, maxPlotReach + clearance)) {
            float[] local = worldToLocal(house, worldX, worldZ);
            if (local[0] >= house.plotMinX - clearance && local[0] <= house.plotMaxX + clearance
                    && local[1] >= house.plotMinZ - clearance && local[1] <= house.plotMaxZ + clearance) {
                return true;
            }
        }
        return false;
    }

    private List<House> housesNear(float worldX, float worldZ, float reach) {
        List<House> nearby = new ArrayList<>();
        int centerChunkX = chunkIndex(worldX);
        int centerChunkZ = chunkIndex(worldZ);
        int radius = Math.max(1, (int) Math.ceil(reach / roadChunkSize));
        for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
            for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
                List<House> bucket = housesByChunk.get(chunkKey(chunkX, chunkZ));
                if (bucket != null) {
                    nearby.addAll(bucket);
                }
            }
        }
        return nearby;
    }

    private List<RoadSegment> segmentsNear(float worldX, float worldZ, float distance) {
        List<RoadSegment> nearby = new ArrayList<>();
        int centerChunkX = chunkIndex(worldX);
        int centerChunkZ = chunkIndex(worldZ);
        int chunkRadius = Math.max(1, (int) Math.ceil((distance + MAX_ROAD_SEGMENT_HALF_LENGTH) / roadChunkSize));
        for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
            for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
                List<RoadSegment> segments = roadSegmentsByChunk.get(chunkKey(chunkX, chunkZ));
                if (segments != null) {
                    nearby.addAll(segments);
                }
            }
        }
        return nearby;
    }

    // ==========================================
    //          HOUSE PLACEMENT
    // ==========================================

    /**
     * Rolls houses along every road segment, then keeps them in priority order
     * so no two plots overlap. Deterministic for a given seed.
     */
    private void placeHouses() {
        List<House> candidates = new ArrayList<>();
        for (List<RoadSegment> bucket : roadSegmentsByChunk.values()) {
            for (RoadSegment segment : bucket) {
                if (!segment.culDeSac && !segment.railed) {
                    rollHousesAlong(segment, candidates);
                }
            }
        }
        // Bucket iteration order is not stable, so sort fully by priority then seed for a deterministic result
        candidates.sort((a, b) -> a.priority != b.priority ? Float.compare(b.priority, a.priority) : Long.compare(a.seed, b.seed));

        float largestPlot = 0.0f;
        for (House candidate : candidates) {
            largestPlot = Math.max(largestPlot, candidate.plotRadius());
        }

        Map<Long, List<House>> plotIndex = new HashMap<>();
        for (House candidate : candidates) {
            float[] centre = localToWorld(candidate.x, candidate.z, candidate.rotationY,
                    candidate.plotCentreLocalX(), candidate.plotCentreLocalZ());
            int cellX = (int) Math.floor(centre[0] / PLOT_INDEX_CELL);
            int cellZ = (int) Math.floor(centre[1] / PLOT_INDEX_CELL);
            int reach = (int) Math.ceil((candidate.plotRadius() + largestPlot + PLOT_GAP) / PLOT_INDEX_CELL);

            boolean blocked = false;
            for (int dz = -reach; dz <= reach && !blocked; dz++) {
                for (int dx = -reach; dx <= reach && !blocked; dx++) {
                    List<House> cell = plotIndex.get(chunkKey(cellX + dx, cellZ + dz));
                    if (cell == null) {
                        continue;
                    }
                    for (House other : cell) {
                        if (plotsOverlap(candidate, other)) {
                            blocked = true;
                            break;
                        }
                    }
                }
            }
            if (blocked) {
                continue;
            }
            plotIndex.computeIfAbsent(chunkKey(cellX, cellZ), ignored -> new ArrayList<>()).add(candidate);
            houses.add(candidate);
            housesByChunk.computeIfAbsent(chunkKeyAt(candidate.x, candidate.z), ignored -> new ArrayList<>()).add(candidate);

            float houseReach = Math.max(-candidate.houseMinX(), candidate.houseMaxX()) + candidate.depth * 0.5f;
            maxHouseReach = Math.max(maxHouseReach, houseReach);
            float plotReach = Math.max(Math.max(-candidate.plotMinX, candidate.plotMaxX), Math.max(-candidate.plotMinZ, candidate.plotMaxZ));
            maxPlotReach = Math.max(maxPlotReach, plotReach * 1.42f);
        }
    }

    private void rollHousesAlong(RoadSegment segment, List<House> candidates) {
        long segmentSeed = worldSeed ^ ((long) segment.pathIndex * 0x9E3779B97F4A7C15L)
                ^ (Float.floatToIntBits(segment.startDistance) * 0xC2B2AE3D27D4EB4FL);
        Random rand = new Random(segmentSeed);
        float midX = (segment.start.x + segment.end.x) * 0.5f;
        float midZ = (segment.start.z + segment.end.z) * 0.5f;
        float urbanness = settlementManager.getUrbanness(midX, midZ);
        float habitability = settlementManager.getHabitability(midX, midZ);

        Double ruralProbability = nationBuildingProbabilities.get(segment.nationId);
        Float urbanDensity = nationUrbanDensities.get(segment.nationId);
        if (ruralProbability == null || urbanDensity == null) {
            return;
        }
        float perHundred = (float) (ruralProbability * RURAL_FRONTAGE_SCALE) * (0.3f + 0.7f * habitability)
                + urbanDensity * (float) Math.pow(urbanness, URBAN_DENSITY_EXPONENT);
        float attemptsExact = perHundred * segment.length / 100.0f * 2.0f * PLACEMENT_OVERSAMPLE;
        int attempts = (int) attemptsExact;
        if (rand.nextFloat() < attemptsExact - attempts) {
            attempts++;
        }

        // Gardens shrink to nothing as the countryside gives way to town
        float gardenScale = 1.0f - smoothstep(0.1f, 0.45f, urbanness);
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;

        for (int attempt = 0; attempt < attempts; attempt++) {
            House house = new House();
            house.seed = segmentSeed * 31L + attempt;
            house.frontagePath = segment.pathIndex;
            float t = rand.nextFloat();
            float side = rand.nextBoolean() ? 1.0f : -1.0f;
            house.sizeScale = 0.9f + rand.nextFloat() * 0.2f;
            house.setback = KERB_SETBACK_MIN + rand.nextFloat() * KERB_SETBACK_RANGE + rand.nextFloat() * MAX_FRONT_GARDEN * gardenScale;
            float extensionRoll = rand.nextFloat();
            boolean extensionLeft = rand.nextBoolean();
            float fenceRoll = rand.nextFloat();
            float sideYard = 6.0f + rand.nextFloat() * 8.0f;
            float backYard = 8.0f + rand.nextFloat() * (6.0f + 30.0f * gardenScale);
            house.priority = rand.nextFloat();

            float outwardX = -dirZ * side;
            float outwardZ = dirX * side;
            float roadX = segment.start.x + (segment.end.x - segment.start.x) * t;
            float roadZ = segment.start.z + (segment.end.z - segment.start.z) * t;

            // The house takes the nation of the ground it stands on, a little back from the road
            float probe = segment.width + house.setback + 40.0f;
            int nationId = nationManager.getNationAtWorld(roadX + outwardX * probe, roadZ + outwardZ * probe, regionWidth);
            BuildingStyle style = buildingStyles.get(nationId);
            FenceStyle fenceStyle = fenceStyles.get(nationId);
            if (style == null) {
                continue;
            }
            house.nationId = nationId;
            house.width = style.width * house.sizeScale;
            house.depth = style.depth * house.sizeScale;
            if (extensionRoll < style.extensionChance) {
                house.extensionSide = extensionLeft ? -1 : 1;
                house.extensionWidth = house.width * style.extensionWidthRatio;
                house.extensionDepth = house.depth * style.extensionDepthRatio;
            }
            house.fenced = fenceStyle != null && fenceRoll < fenceStyle.fenceChance;

            float offset = segment.width * 0.5f + house.setback + house.depth * 0.5f;
            house.x = roadX + outwardX * offset;
            house.z = roadZ + outwardZ * offset;
            house.rotationY = facingRotation(-outwardX, -outwardZ);

            float overhang = style.roofOverhang * house.sizeScale;
            house.plotMinX = house.houseMinX() - overhang - sideYard;
            house.plotMaxX = house.houseMaxX() + overhang + sideYard;
            house.plotMinZ = -house.depth * 0.5f - overhang - backYard;
            house.plotMaxZ = house.depth * 0.5f + house.setback - FRONT_FENCE_GAP;

            if (!settleOnGround(house, style) || !isPlotClearOfRoads(house)) {
                continue;
            }
            candidates.add(house);
        }
    }

    /** Sinks the house into the slope and sizes its walls; false if the ground is too steep or wet. */
    private boolean settleOnGround(House house, BuildingStyle style) {
        float halfWidth = house.width * 0.5f;
        float halfDepth = house.depth * 0.5f;
        float[] mainRange = groundRange(house, -halfWidth, halfWidth, -halfDepth, halfDepth);
        float minGround = mainRange[0];
        float maxGround = mainRange[1];
        float[] extensionRange = null;
        if (house.extensionSide != 0) {
            float innerX = house.extensionSide * halfWidth;
            float outerX = house.extensionSide * (halfWidth + house.extensionWidth);
            extensionRange = groundRange(house, Math.min(innerX, outerX), Math.max(innerX, outerX),
                    -halfDepth, -halfDepth + house.extensionDepth);
            minGround = Math.min(minGround, extensionRange[0]);
            maxGround = Math.max(maxGround, extensionRange[1]);
        }
        if (minGround <= seaLevel + 0.5f || maxGround - minGround > BUILDING_MAX_GROUND_DROP) {
            return false;
        }
        float[] backLeft = localToWorld(house.x, house.z, house.rotationY, house.plotMinX, house.plotMinZ);
        float[] backRight = localToWorld(house.x, house.z, house.rotationY, house.plotMaxX, house.plotMinZ);
        if (TerrainMesh.getLayeredHeight(backLeft[0], backLeft[1], terrainNoise) <= seaLevel
                || TerrainMesh.getLayeredHeight(backRight[0], backRight[1], terrainNoise) <= seaLevel) {
            return false;
        }

        // Sink the base below the lowest corner and grow the walls so slopes never expose a gap
        house.baseY = minGround - BUILDING_FOUNDATION_DEPTH;
        house.mainWallHeight = (mainRange[1] - house.baseY) + style.wallHeight * house.sizeScale;
        if (extensionRange != null) {
            house.extensionWallHeight = (extensionRange[1] - house.baseY)
                    + style.wallHeight * house.sizeScale * style.extensionHeightRatio;
        }
        float[] door = localToWorld(house.x, house.z, house.rotationY, 0.0f, halfDepth);
        house.doorBase = TerrainMesh.getLayeredHeight(door[0], door[1], terrainNoise) - house.baseY;
        return true;
    }

    private float[] groundRange(House house, float minX, float maxX, float minZ, float maxZ) {
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            float[] position = localToWorld(house.x, house.z, house.rotationY,
                    (corner & 1) == 0 ? minX : maxX, (corner & 2) == 0 ? minZ : maxZ);
            float ground = TerrainMesh.getLayeredHeight(position[0], position[1], terrainNoise);
            low = Math.min(low, ground);
            high = Math.max(high, ground);
        }
        return new float[] { low, high };
    }

    /**
     * Exact test of the rotated plot against nearby carriageways and turning
     * circles. The road the house fronts only has to clear the house itself,
     * since it may bend into the corners of the front garden; the fence simply
     * stops where it does.
     */
    private boolean isPlotClearOfRoads(House house) {
        BuildingStyle style = buildingStyles.get(house.nationId);
        float overhang = style.roofOverhang * house.sizeScale;
        float plotHalfX = (house.plotMaxX - house.plotMinX) * 0.5f;
        float plotHalfZ = (house.plotMaxZ - house.plotMinZ) * 0.5f;
        float houseHalfX = (house.houseMaxX() - house.houseMinX()) * 0.5f + overhang;
        float houseHalfZ = house.depth * 0.5f + overhang;
        float[] plotCentre = { house.plotCentreLocalX(), house.plotCentreLocalZ() };
        float[] houseCentre = { (house.houseMinX() + house.houseMaxX()) * 0.5f, 0.0f };
        float reach = (float) Math.sqrt(plotHalfX * plotHalfX + plotHalfZ * plotHalfZ) + PLOT_HIGHWAY_MARGIN + MAX_ROAD_HALF_WIDTH;
        float[] worldCentre = localToWorld(house.x, house.z, house.rotationY, plotCentre[0], plotCentre[1]);

        for (RoadSegment segment : segmentsNear(worldCentre[0], worldCentre[1], reach)) {
            boolean ownRoad = segment.pathIndex == house.frontagePath;
            float[] boxCentre = ownRoad ? houseCentre : plotCentre;
            float halfX = ownRoad ? houseHalfX : plotHalfX;
            float halfZ = ownRoad ? houseHalfZ : plotHalfZ;

            // Segment end points in the frame of the box being tested
            float[] a = worldToLocal(house, segment.start.x, segment.start.z);
            float[] b = worldToLocal(house, segment.end.x, segment.end.z);
            float margin = segment.roadClass == RoadPath.RoadClass.HIGHWAY ? PLOT_HIGHWAY_MARGIN : PLOT_ROAD_MARGIN;
            float limit = segment.width * 0.5f + margin;
            if (segmentToBoxDistanceSquared(a[0] - boxCentre[0], a[1] - boxCentre[1], b[0] - boxCentre[0], b[1] - boxCentre[1],
                    halfX, halfZ) < limit * limit) {
                return false;
            }
        }
        return true;
    }

    /** Separating-axis test between two rotated plot rectangles. */
    private static boolean plotsOverlap(House a, House b) {
        float[] centreA = localToWorld(a.x, a.z, a.rotationY, a.plotCentreLocalX(), a.plotCentreLocalZ());
        float[] centreB = localToWorld(b.x, b.z, b.rotationY, b.plotCentreLocalX(), b.plotCentreLocalZ());
        float dx = centreB[0] - centreA[0];
        float dz = centreB[1] - centreA[1];
        float reach = a.plotRadius() + b.plotRadius() + PLOT_GAP;
        if (dx * dx + dz * dz > reach * reach) {
            return false;
        }

        float[][] axesA = plotAxes(a);
        float[][] axesB = plotAxes(b);
        float[] halfA = { (a.plotMaxX - a.plotMinX) * 0.5f, (a.plotMaxZ - a.plotMinZ) * 0.5f };
        float[] halfB = { (b.plotMaxX - b.plotMinX) * 0.5f, (b.plotMaxZ - b.plotMinZ) * 0.5f };
        float[][] testAxes = { axesA[0], axesA[1], axesB[0], axesB[1] };
        for (float[] axis : testAxes) {
            float radiusA = halfA[0] * Math.abs(dot(axesA[0], axis)) + halfA[1] * Math.abs(dot(axesA[1], axis));
            float radiusB = halfB[0] * Math.abs(dot(axesB[0], axis)) + halfB[1] * Math.abs(dot(axesB[1], axis));
            if (Math.abs(dx * axis[0] + dz * axis[1]) > radiusA + radiusB + PLOT_GAP) {
                return false;
            }
        }
        return true;
    }

    /** World directions of a house's local X and Z axes. */
    private static float[][] plotAxes(House house) {
        float radians = (float) Math.toRadians(house.rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        return new float[][] { { cosine, -sine }, { sine, cosine } };
    }

    // ==========================================
    //          CHUNK GENERATION
    // ==========================================

    /** Geometry accumulated for one nation within one chunk, one builder per material. */
    private final class NationBatch {
        private final int nationId;
        private final Map<String, MeshBuilder> builders = new LinkedHashMap<>();
        private float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;

        private NationBatch(int nationId) {
            this.nationId = nationId;
        }

        private MeshBuilder builder(String part) {
            return builders.computeIfAbsent(part, ignored -> new MeshBuilder());
        }

        private void include(float x, float y, float z, float margin) {
            minX = Math.min(minX, x - margin);
            maxX = Math.max(maxX, x + margin);
            minZ = Math.min(minZ, z - margin);
            maxZ = Math.max(maxZ, z + margin);
            maxY = Math.max(maxY, y);
        }

        private InfrastructureObject build() {
            List<InfrastructureObject.BatchPart> parts = new ArrayList<>();
            for (Map.Entry<String, MeshBuilder> entry : builders.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                String part = entry.getKey();
                boolean doubleSided = part.equals("rail") || part.equals("band") || part.equals("fence");
                boolean paint = part.equals("line");
                parts.add(new InfrastructureObject.BatchPart(entry.getValue().vertexArray(), entry.getValue().indexArray(),
                        material(part, nationId), doubleSided, paint));
            }
            if (parts.isEmpty() || minX > maxX) {
                return null;
            }
            float centreX = (minX + maxX) * 0.5f;
            float centreZ = (minZ + maxZ) * 0.5f;
            float centreY = TerrainMesh.getLayeredHeight(centreX, centreZ, terrainNoise);
            float halfDiagonal = 0.5f * (float) Math.sqrt((maxX - minX) * (maxX - minX) + (maxZ - minZ) * (maxZ - minZ));
            float radius = Math.max(halfDiagonal, maxY - centreY);
            return InfrastructureObject.createBatch(new Vector3(centreX, centreY, centreZ), nationId, radius, parts);
        }
    }

    public List<InfrastructureObject> generateForChunk(int cx, int cz, float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        List<InfrastructureObject> objects = new ArrayList<>();
        prepareRoadNetwork(chunkSize, totalRegionWidth, seaLevelHeight, noise);

        Random signRand = new Random((long)cx * 8912L + (long)cz * 4123L);
        List<float[]> signPositions = new ArrayList<>();
        Map<Integer, NationBatch> batches = new HashMap<>();
        long key = chunkKey(cx, cz);

        List<House> chunkHouses = housesByChunk.get(key);
        if (chunkHouses != null) {
            for (House house : chunkHouses) {
                NationBatch batch = batches.computeIfAbsent(house.nationId, NationBatch::new);
                bakeHouse(batch, house);
                if (house.fenced) {
                    bakeFence(batch, house);
                }
                if (signRand.nextDouble() < buildingStyles.get(house.nationId).signChance) {
                    InfrastructureObject sign = createHouseSign(house, signRand);
                    if (sign != null) {
                        objects.add(sign);
                        signPositions.add(new float[] { sign.position.x, sign.position.z });
                    }
                }
            }
        }

        List<RoadSegment> segments = roadSegmentsByChunk.get(key);
        if (segments != null) {
            for (RoadSegment segment : segments) {
                NationBatch batch = batches.computeIfAbsent(segment.nationId, NationBatch::new);
                if (segment.culDeSac) {
                    appendCulDeSac(batch, segment);
                    continue;
                }
                appendRoadSegment(batch, segment);
                InfrastructureObject sign = tryCreateRoadsideSign(segment, signRand, signPositions);
                if (sign != null) {
                    objects.add(sign);
                    signPositions.add(new float[] { sign.position.x, sign.position.z });
                }
            }
        }

        for (NationBatch batch : batches.values()) {
            InfrastructureObject object = batch.build();
            if (object != null) {
                objects.add(object);
            }
        }
        return objects;
    }

    /** Materials are created once per nation and part, and shared by every batch. */
    private Material material(String part, int nationId) {
        if (part.equals("asphalt")) {
            return ASPHALT;
        }
        return materials.computeIfAbsent(part + "_" + nationId, ignored -> {
            Vector3 specular = new Vector3(0.03f, 0.03f, 0.03f);
            BuildingStyle building = buildingStyles.get(nationId);
            GuardRailStyle rail = guardRailStyles.get(nationId);
            switch (part) {
                case "line": {
                    Vector3 colour = roadLineStyles.get(nationId).colour;
                    return new Material(Vector3.multiply(colour, 0.9f), colour, new Vector3(0.05f, 0.05f, 0.05f), 8.0f);
                }
                case "rail":
                    return new Material(rail.railColour, rail.railColour, new Vector3(0.35f, 0.35f, 0.35f), 24.0f);
                case "band":
                    return new Material(rail.bandColour, rail.bandColour, new Vector3(0.35f, 0.35f, 0.35f), 24.0f);
                case "post":
                    return new Material(rail.postColour, rail.postColour, specular, 4.0f);
                case "wall":
                    return new Material(building.wallColour, building.wallColour, specular, 2.0f);
                case "roof":
                    return new Material(building.roofColour, building.roofColour, specular, 4.0f);
                case "door":
                    return new Material(building.doorColour, building.doorColour, specular, 2.0f);
                case "glass":
                    return new Material(building.glassColour, building.glassColour, new Vector3(0.6f, 0.6f, 0.6f), 64.0f);
                case "frame":
                    return new Material(building.frameColour, building.frameColour, specular, 2.0f);
                case "fence":
                default: {
                    Vector3 colour = fenceStyles.get(nationId).colour;
                    return new Material(colour, colour, specular, 2.0f);
                }
            }
        });
    }

    // ==========================================
    //          ROAD GEOMETRY
    // ==========================================

    private float roadSurfaceY(float x, float z) {
        // Roads ride a low causeway wherever they dip below the sea
        return Math.max(TerrainMesh.getLayeredHeight(x, z, terrainNoise), seaLevel + 1.0f) + ROAD_SURFACE_OFFSET;
    }

    /** Positions of a line offset sideways from the centreline, one per subdivision, mitred at the ends. */
    private float[][] offsetEdge(RoadSegment segment, float offset) {
        float[][] edge = new float[ROAD_SEGMENT_SUBDIVISIONS + 1][];
        float halfOffset = Math.abs(offset);
        float sign = Math.signum(offset);
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            float t = (float) i / ROAD_SEGMENT_SUBDIVISIONS;
            float x = segment.start.x + (segment.end.x - segment.start.x) * t;
            float z = segment.start.z + (segment.end.z - segment.start.z) * t;
            Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, t);
            float sideX = -tangent.z * halfOffset;
            float sideZ = tangent.x * halfOffset;
            if (i == 0) {
                float[] miter = miterOffset(segment.startTangent, tangent, halfOffset);
                sideX = miter[0];
                sideZ = miter[1];
            } else if (i == ROAD_SEGMENT_SUBDIVISIONS) {
                float[] miter = miterOffset(tangent, segment.endTangent, halfOffset);
                sideX = miter[0];
                sideZ = miter[1];
            }
            float edgeX = x + sideX * sign;
            float edgeZ = z + sideZ * sign;
            edge[i] = new float[] { edgeX, roadSurfaceY(edgeX, edgeZ), edgeZ };
        }
        return edge;
    }

    private void appendRoadSegment(NationBatch batch, RoadSegment segment) {
        float halfWidth = segment.width * 0.5f;
        float[][] leftEdge = offsetEdge(segment, -halfWidth);
        float[][] rightEdge = offsetEdge(segment, halfWidth);

        MeshBuilder asphalt = batch.builder("asphalt");
        int firstVertex = -1;
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            float t = (float) i / ROAD_SEGMENT_SUBDIVISIONS;
            int left = asphalt.addVertex(leftEdge[i][0], leftEdge[i][1], leftEdge[i][2], 0.0f, 1.0f, 0.0f, t, 0.0f);
            asphalt.addVertex(rightEdge[i][0], rightEdge[i][1], rightEdge[i][2], 0.0f, 1.0f, 0.0f, t, 0.0f);
            if (firstVertex < 0) {
                firstVertex = left;
            }
            batch.include(leftEdge[i][0], leftEdge[i][1], leftEdge[i][2], 0.0f);
            batch.include(rightEdge[i][0], rightEdge[i][1], rightEdge[i][2], 0.0f);
        }
        for (int i = 0; i < ROAD_SEGMENT_SUBDIVISIONS; i++) {
            // Wound counter-clockwise seen from above so the surface survives back-face culling
            int left = firstVertex + i * 2;
            asphalt.addTriangle(left, left + 1, left + 2);
            asphalt.addTriangle(left + 1, left + 3, left + 2);
        }

        // Other roads close enough to meet this segment, for junction handling
        List<RoadSegment> crossing = new ArrayList<>();
        float midX = (segment.start.x + segment.end.x) * 0.5f;
        float midZ = (segment.start.z + segment.end.z) * 0.5f;
        for (RoadSegment other : segmentsNear(midX, midZ, segment.length * 0.5f + segment.width + MAX_ROAD_HALF_WIDTH)) {
            if (other.pathIndex != segment.pathIndex || other.culDeSac) {
                crossing.add(other);
            }
        }

        appendRoadLines(batch.builder("line"), segment, leftEdge, rightEdge, crossing);
        if (segment.railed) {
            GuardRailStyle style = guardRailStyles.get(segment.nationId);
            appendGuardRail(batch, segment, style, -1.0f, crossing);
            appendGuardRail(batch, segment, style, 1.0f, crossing);
        }
    }

    /** Highest junction rank of any other road covering the point, or -1. */
    private int coveringRank(float x, float z, List<RoadSegment> crossing, float margin) {
        int rank = -1;
        for (RoadSegment other : crossing) {
            float limit = other.width * 0.5f + margin;
            if (other.rank() > rank && distanceSquaredToSegment(x, z, other.start, other.end) <= limit * limit) {
                rank = other.rank();
            }
        }
        return rank;
    }

    /**
     * Paints the nation's line style and any lane dividers, leaving junctions
     * clear the way real roads do: minor roads stop their paint at the edge of
     * a bigger road and get a give-way line, while the bigger road only breaks
     * its edge lines across the mouth of the junction.
     */
    private void appendRoadLines(MeshBuilder builder, RoadSegment segment, float[][] leftEdge, float[][] rightEdge,
                                 List<RoadSegment> crossing) {
        RoadLineStyle style = roadLineStyles.get(segment.nationId);
        if (style == null || segment.length <= 0.0f) {
            return;
        }

        List<Float> breakpoints = new ArrayList<>();
        for (int i = 0; i <= LINE_SAMPLES_PER_SEGMENT; i++) {
            breakpoints.add((float) i / LINE_SAMPLES_PER_SEGMENT);
        }
        float segmentEnd = segment.startDistance + segment.length;
        if (style.dashed) {
            addDashBreakpoints(breakpoints, segment, style.dashLength, style.dashPeriod(), segmentEnd);
        }
        boolean laneDividers = segment.width >= LANE_DIVIDER_MIN_WIDTH;
        if (laneDividers) {
            addDashBreakpoints(breakpoints, segment, LANE_DIVIDER_DASH, LANE_DIVIDER_DASH + LANE_DIVIDER_GAP, segmentEnd);
        }
        Collections.sort(breakpoints);

        int ownRank = segment.rank();
        float halfLine = style.lineWidth * 0.5f;
        List<float[]> stripes = new ArrayList<>();
        for (float offset : style.stripeOffsets(segment.width)) {
            stripes.add(new float[] { offset, 0.0f });
        }
        if (laneDividers) {
            stripes.add(new float[] { -segment.width * 0.25f, 1.0f });
            stripes.add(new float[] { segment.width * 0.25f, 1.0f });
        }

        for (float[] stripe : stripes) {
            float offset = stripe[0];
            boolean divider = stripe[1] > 0.0f;
            boolean edgeStripe = Math.abs(offset) > segment.width * 0.3f;
            float innerAcross = 0.5f + (offset - halfLine) / segment.width;
            float outerAcross = 0.5f + (offset + halfLine) / segment.width;

            for (int k = 0; k < breakpoints.size() - 1; k++) {
                float t0 = breakpoints.get(k);
                float t1 = breakpoints.get(k + 1);
                if (t1 - t0 < 1e-5f) {
                    continue;
                }
                float tMid = (t0 + t1) * 0.5f;
                float midDistance = segment.startDistance + tMid * segment.length;
                boolean painted = divider ? isDividerPainted(midDistance) : style.isPaintedAt(midDistance);
                if (!painted) {
                    continue;
                }
                float[] point = surfacePoint(leftEdge, rightEdge, tMid, 0.5f + offset / segment.width);
                int cover = coveringRank(point[0], point[2], crossing, 1.0f);
                if (cover >= 0 && (cover >= ownRank || edgeStripe)) {
                    continue;
                }
                addPaintQuad(builder, leftEdge, rightEdge, t0, t1, innerAcross, outerAcross);
            }
        }

        appendGiveWayLines(builder, segment, style, leftEdge, rightEdge, crossing);
    }

    private void addDashBreakpoints(List<Float> breakpoints, RoadSegment segment, float dashLength, float period, float segmentEnd) {
        for (float dashStart = (float) Math.floor(segment.startDistance / period) * period;
             dashStart <= segmentEnd; dashStart += period) {
            addBreakpoint(breakpoints, (dashStart - segment.startDistance) / segment.length);
            addBreakpoint(breakpoints, (dashStart + dashLength - segment.startDistance) / segment.length);
        }
    }

    private static boolean isDividerPainted(float distance) {
        float period = LANE_DIVIDER_DASH + LANE_DIVIDER_GAP;
        float phase = distance % period;
        if (phase < 0.0f) {
            phase += period;
        }
        return phase < LANE_DIVIDER_DASH;
    }

    /** A line across the carriageway wherever this road meets a road it must give way to. */
    private void appendGiveWayLines(MeshBuilder builder, RoadSegment segment, RoadLineStyle style,
                                    float[][] leftEdge, float[][] rightEdge, List<RoadSegment> crossing) {
        int ownRank = segment.rank();
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        float halfSpan = GIVE_WAY_LINE_LENGTH * 0.5f / segment.length;

        // Samples run one step beyond each end so transitions at segment joins are caught exactly once
        boolean previousInside = false;
        for (int k = -1; k <= LINE_SAMPLES_PER_SEGMENT + 1; k++) {
            float t = (float) k / LINE_SAMPLES_PER_SEGMENT;
            float x = segment.start.x + dirX * segment.length * t;
            float z = segment.start.z + dirZ * segment.length * t;
            boolean inside = false;
            for (RoadSegment other : crossing) {
                if (other.culDeSac || other.rank() <= ownRank) {
                    continue;
                }
                float limit = other.width * 0.5f + 1.0f;
                if (distanceSquaredToSegment(x, z, other.start, other.end) <= limit * limit) {
                    inside = true;
                    break;
                }
            }
            if (k > -1 && inside != previousInside) {
                float outsideT = inside ? (float) (k - 1) / LINE_SAMPLES_PER_SEGMENT : t;
                if (outsideT >= 0.0f && outsideT <= 1.0f) {
                    float t0 = Math.max(0.0f, outsideT - halfSpan);
                    float t1 = Math.min(1.0f, outsideT + halfSpan);
                    if (style.dashed) {
                        for (int dash = 0; dash < 5; dash++) {
                            float a = 0.08f + dash * 0.18f;
                            addPaintQuad(builder, leftEdge, rightEdge, t0, t1, a, a + 0.1f);
                        }
                    } else {
                        addPaintQuad(builder, leftEdge, rightEdge, t0, t1, 0.06f, 0.94f);
                    }
                }
            }
            previousInside = inside;
        }
    }

    private void addPaintQuad(MeshBuilder builder, float[][] leftEdge, float[][] rightEdge,
                              float t0, float t1, float innerAcross, float outerAcross) {
        int a = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t0, innerAcross);
        int b = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t0, outerAcross);
        int c = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t1, outerAcross);
        int d = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t1, innerAcross);
        builder.addTriangle(a, b, c);
        builder.addTriangle(a, c, d);
    }

    private void addBreakpoint(List<Float> breakpoints, float t) {
        if (t > 0.0f && t < 1.0f) {
            breakpoints.add(t);
        }
    }

    /** t runs along the segment, across runs from the left edge (0) to the right edge (1). */
    private float[] surfacePoint(float[][] leftEdge, float[][] rightEdge, float t, float across) {
        float scaled = Math.max(0.0f, Math.min(1.0f, t)) * ROAD_SEGMENT_SUBDIVISIONS;
        int i = Math.min((int) Math.floor(scaled), ROAD_SEGMENT_SUBDIVISIONS - 1);
        float along = scaled - i;
        float[] point = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            float left = leftEdge[i][axis] + (leftEdge[i + 1][axis] - leftEdge[i][axis]) * along;
            float right = rightEdge[i][axis] + (rightEdge[i + 1][axis] - rightEdge[i][axis]) * along;
            point[axis] = left + (right - left) * across;
        }
        return point;
    }

    private int addRoadSurfaceVertex(MeshBuilder builder, float[][] leftEdge, float[][] rightEdge, float t, float across) {
        float[] point = surfacePoint(leftEdge, rightEdge, t, across);
        return builder.addVertex(point[0], point[1] + ROAD_LINE_SURFACE_OFFSET, point[2], 0.0f, 1.0f, 0.0f, across, t);
    }

    /** A flat disc of asphalt at the closed end of a street. */
    private void appendCulDeSac(NationBatch batch, RoadSegment segment) {
        MeshBuilder asphalt = batch.builder("asphalt");
        float radius = segment.width * 0.5f;
        int sides = 20;
        float centreX = segment.start.x;
        float centreZ = segment.start.z;
        int centre = asphalt.addVertex(centreX, roadSurfaceY(centreX, centreZ), centreZ, 0.0f, 1.0f, 0.0f, 0.5f, 0.5f);
        for (int i = 0; i <= sides; i++) {
            double angle = 2.0 * Math.PI * i / sides;
            float x = centreX + (float) Math.cos(angle) * radius;
            float z = centreZ + (float) Math.sin(angle) * radius;
            asphalt.addVertex(x, roadSurfaceY(x, z), z, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f);
            batch.include(x, 0.0f, z, 0.0f);
        }
        for (int i = 0; i < sides; i++) {
            // Counter-clockwise seen from above
            asphalt.addTriangle(centre, centre + i + 2, centre + i + 1);
        }
    }

    // ==========================================
    //          GUARD RAILS
    // ==========================================

    /**
     * Extrudes the nation's rail profile along one side of the highway on
     * regularly spaced posts, leaving gaps where other roads join.
     */
    private void appendGuardRail(NationBatch batch, RoadSegment segment, GuardRailStyle style, float side,
                                 List<RoadSegment> crossing) {
        float[][] base = offsetEdge(segment, side * (segment.width * 0.5f + RAIL_OFFSET));
        MeshBuilder railBuilder = batch.builder("rail");
        MeshBuilder bandBuilder = batch.builder("band");
        MeshBuilder postBuilder = batch.builder("post");

        List<Float> breakpoints = new ArrayList<>();
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            breakpoints.add((float) i / ROAD_SEGMENT_SUBDIVISIONS);
        }
        if (style.banding == GuardRailStyle.Banding.ALTERNATING) {
            addDashBreakpoints(breakpoints, segment, style.bandLength, style.bandPeriod, segment.startDistance + segment.length);
        }
        Collections.sort(breakpoints);

        List<float[][]> profile = style.crossSection();
        for (int k = 0; k < breakpoints.size() - 1; k++) {
            float t0 = breakpoints.get(k);
            float t1 = breakpoints.get(k + 1);
            if (t1 - t0 < 1e-5f) {
                continue;
            }
            float[] mid = railBase(base, (t0 + t1) * 0.5f);
            if (coveringRank(mid[0], mid[2], crossing, RAIL_JUNCTION_CLEARANCE) >= 0) {
                continue;
            }
            float[] start = railBase(base, t0);
            float[] end = railBase(base, t1);
            // Profile "out" points back towards the traffic
            float[] towardRoad0 = towardRoad(segment, t0, side);
            float[] towardRoad1 = towardRoad(segment, t1, side);
            boolean band = style.isBandAt(segment.startDistance + (t0 + t1) * 0.5f * segment.length);

            for (float[][] strip : profile) {
                for (int p = 0; p < strip.length - 1; p++) {
                    boolean topEdge = p == strip.length - 2;
                    MeshBuilder target = band || (style.banding == GuardRailStyle.Banding.TOP_STRIPE && topEdge)
                            ? bandBuilder : railBuilder;
                    float[] a = strip[p];
                    float[] b = strip[p + 1];
                    float edgeOut = b[0] - a[0];
                    float edgeUp = b[1] - a[1];
                    float edgeLength = (float) Math.sqrt(edgeOut * edgeOut + edgeUp * edgeUp);
                    float normalOut = edgeUp / edgeLength;
                    float normalUp = -edgeOut / edgeLength;

                    float[] v0 = profilePoint(start, towardRoad0, style.railHeight, a);
                    float[] v1 = profilePoint(start, towardRoad0, style.railHeight, b);
                    float[] v2 = profilePoint(end, towardRoad1, style.railHeight, b);
                    float[] v3 = profilePoint(end, towardRoad1, style.railHeight, a);
                    float nx = towardRoad0[0] * normalOut;
                    float nz = towardRoad0[1] * normalOut;
                    int i0 = target.addVertex(v0[0], v0[1], v0[2], nx, normalUp, nz, 0, 0);
                    int i1 = target.addVertex(v1[0], v1[1], v1[2], nx, normalUp, nz, 0, 1);
                    int i2 = target.addVertex(v2[0], v2[1], v2[2], nx, normalUp, nz, 1, 1);
                    int i3 = target.addVertex(v3[0], v3[1], v3[2], nx, normalUp, nz, 1, 0);
                    target.addTriangle(i0, i1, i2);
                    target.addTriangle(i0, i2, i3);
                }
            }
            batch.include(start[0], start[1] + style.railHeight, start[2], 2.0f);
        }

        // Posts at a fixed spacing along the whole path so they line up across segments
        float firstPost = (float) Math.ceil(segment.startDistance / style.postSpacing) * style.postSpacing;
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        for (float distance = firstPost; distance < segment.startDistance + segment.length; distance += style.postSpacing) {
            float t = (distance - segment.startDistance) / segment.length;
            float[] position = railBase(base, t);
            if (coveringRank(position[0], position[2], crossing, RAIL_JUNCTION_CLEARANCE) >= 0) {
                continue;
            }
            float[] toward = towardRoad(segment, t, side);
            float postX = position[0] - toward[0] * style.postWidth;
            float postZ = position[2] - toward[1] * style.postWidth;
            float top = style.railHeight + style.railSize * 0.5f;
            addBox(postBuilder, postX, position[1] - 2.0f, postZ, dirX, dirZ,
                    style.postWidth * 0.5f, style.postWidth * 0.5f, top + 2.0f);
        }
    }

    /** Ground point under the rail line (road surface height without the asphalt lift). */
    private float[] railBase(float[][] base, float t) {
        float scaled = Math.max(0.0f, Math.min(1.0f, t)) * ROAD_SEGMENT_SUBDIVISIONS;
        int i = Math.min((int) Math.floor(scaled), ROAD_SEGMENT_SUBDIVISIONS - 1);
        float along = scaled - i;
        return new float[] {
            base[i][0] + (base[i + 1][0] - base[i][0]) * along,
            base[i][1] + (base[i + 1][1] - base[i][1]) * along - ROAD_SURFACE_OFFSET,
            base[i][2] + (base[i + 1][2] - base[i][2]) * along
        };
    }

    /** Horizontal unit vector from the rail back across to the carriageway. */
    private float[] towardRoad(RoadSegment segment, float t, float side) {
        Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, Math.max(0.0f, Math.min(1.0f, t)));
        return new float[] { tangent.z * side, -tangent.x * side };
    }

    private static float[] profilePoint(float[] base, float[] towardRoad, float railHeight, float[] profile) {
        return new float[] {
            base[0] + towardRoad[0] * profile[0],
            base[1] + railHeight + profile[1],
            base[2] + towardRoad[1] * profile[0]
        };
    }

    // ==========================================
    //          HOUSE GEOMETRY
    // ==========================================

    private void bakeHouse(NationBatch batch, House house) {
        BuildingStyle style = buildingStyles.get(house.nationId);
        float scale = house.sizeScale;
        float overhang = style.roofOverhang * scale;
        float halfDepth = house.depth * 0.5f;
        float[] roofV = roofVertices.get(house.nationId);
        int[] roofI = roofIndices.get(house.nationId);

        bake(batch.builder("wall"), wallVertices, wallIndices, house, house.width, house.mainWallHeight, house.depth, 0, 0, 0);
        bake(batch.builder("roof"), roofV, roofI, house, house.width + 2.0f * overhang, style.roofHeight * scale,
                house.depth + 2.0f * overhang, 0, house.mainWallHeight, 0);
        bake(batch.builder("door"), boxVertices, boxIndices, house, style.doorWidth * scale, style.doorHeight * scale,
                DOOR_THICKNESS, 0, house.doorBase, halfDepth);

        if (house.extensionSide != 0) {
            float centreX = house.extensionSide * (house.width + house.extensionWidth) * 0.5f;
            float centreZ = (house.extensionDepth - house.depth) * 0.5f;
            float extensionOverhang = overhang * 0.6f;
            // Nudged outward a hair so the shared wall never z-fights the house
            bake(batch.builder("wall"), wallVertices, wallIndices, house, house.extensionWidth + 0.2f,
                    house.extensionWallHeight, house.extensionDepth, centreX + house.extensionSide * 0.1f, 0, centreZ);
            bake(batch.builder("roof"), roofV, roofI, house, house.extensionWidth + 2.0f * extensionOverhang,
                    style.roofHeight * scale * style.extensionHeightRatio, house.extensionDepth + 2.0f * extensionOverhang,
                    centreX, house.extensionWallHeight, centreZ);
            if (house.extensionWidth > style.windowWidth * scale * 1.8f) {
                addWindow(batch, house, style, centreX, centreZ + house.extensionDepth * 0.5f, 0.0f, 1.0f,
                        house.doorBase + style.wallHeight * scale * style.extensionHeightRatio * 0.45f);
            }
        }
        appendWindows(batch, house, style);

        float roofTop = house.baseY + house.mainWallHeight + style.roofHeight * scale;
        batch.include(house.x, roofTop, house.z, Math.max(house.plotMaxX - house.plotMinX, house.plotMaxZ - house.plotMinZ));
    }

    /** Rows of windows on every wall, one row per floor, leaving room for the front door. */
    private void appendWindows(NationBatch batch, House house, BuildingStyle style) {
        float scale = house.sizeScale;
        float halfWidth = house.width * 0.5f;
        float halfDepth = house.depth * 0.5f;
        float windowWidth = style.windowWidth * scale;
        float floorHeight = style.wallHeight * scale / style.floors;
        float doorClearance = style.doorWidth * scale * 0.5f + windowWidth * 0.5f + 3.0f;
        float margin = windowWidth * 0.5f + 4.0f;

        for (int floor = 0; floor < style.floors; floor++) {
            float centreY = house.doorBase + floorHeight * (floor + 0.55f);
            if (floor == 0) {
                // Split the ground floor front windows either side of the door
                int left = (style.windowsFront + 1) / 2;
                int right = style.windowsFront / 2;
                placeAlong(batch, house, style, -halfWidth + margin, -doorClearance, left, halfDepth, 0, 1, centreY);
                placeAlong(batch, house, style, doorClearance, halfWidth - margin, right, halfDepth, 0, 1, centreY);
            } else {
                placeAlong(batch, house, style, -halfWidth + margin, halfWidth - margin, style.windowsFront + 1, halfDepth, 0, 1, centreY);
            }
            placeAlong(batch, house, style, -halfWidth + margin, halfWidth - margin, style.windowsFront, -halfDepth, 0, -1, centreY);
            for (int side = -1; side <= 1; side += 2) {
                if (side == house.extensionSide && floor == 0) {
                    continue;
                }
                float wallX = side * halfWidth;
                for (int i = 0; i < style.windowsSide; i++) {
                    float z = -halfDepth + margin + (house.depth - 2.0f * margin) * (i + 0.5f) / style.windowsSide;
                    addWindow(batch, house, style, wallX, z, side, 0, centreY);
                }
            }
        }
    }

    /** Evenly spaces windows between two points on a front or back wall. */
    private void placeAlong(NationBatch batch, House house, BuildingStyle style, float fromX, float toX, int count,
                            float wallZ, float normalX, float normalZ, float centreY) {
        if (count <= 0 || toX - fromX < style.windowWidth * house.sizeScale) {
            return;
        }
        for (int i = 0; i < count; i++) {
            float x = count == 1 ? (fromX + toX) * 0.5f : fromX + (toX - fromX) * i / (count - 1);
            addWindow(batch, house, style, x, wallZ, normalX, normalZ, centreY);
        }
    }

    /** A glass pane, with an optional frame behind it, on the wall plane facing (normalX, normalZ). */
    private void addWindow(NationBatch batch, House house, BuildingStyle style, float x, float z,
                           float normalX, float normalZ, float centreY) {
        float halfW = style.windowWidth * house.sizeScale * 0.5f;
        float halfH = style.windowHeight * house.sizeScale * 0.5f;
        if (style.frameSize > 0.0f) {
            addWallQuad(batch.builder("frame"), house, x, z, normalX, normalZ, centreY,
                    halfW + style.frameSize, halfH + style.frameSize, FRAME_OFFSET);
        }
        addWallQuad(batch.builder("glass"), house, x, z, normalX, normalZ, centreY, halfW, halfH, GLASS_OFFSET);
    }

    private void addWallQuad(MeshBuilder builder, House house, float x, float z, float normalX, float normalZ,
                             float centreY, float halfW, float halfH, float offset) {
        // Along-wall direction in local space
        float alongX = -normalZ;
        float alongZ = normalX;
        float px = x + normalX * offset;
        float pz = z + normalZ * offset;
        float[][] corners = new float[4][];
        float[][] local = { { -halfW, -halfH }, { halfW, -halfH }, { halfW, halfH }, { -halfW, halfH } };
        for (int i = 0; i < 4; i++) {
            float lx = px + alongX * local[i][0];
            float lz = pz + alongZ * local[i][0];
            float[] world = localToWorld(house.x, house.z, house.rotationY, lx, lz);
            corners[i] = new float[] { world[0], house.baseY + centreY + local[i][1], world[1] };
        }
        float[] inside = localToWorld(house.x, house.z, house.rotationY, x - normalX * 5.0f, z - normalZ * 5.0f);
        builder.addConvexFace(new float[] { inside[0], house.baseY + centreY, inside[1] }, corners);
    }

    /**
     * Copies a unit mesh into a builder: scaled by (sx, sy, sz), shifted by the
     * local offset, then turned and placed with the house.
     */
    private void bake(MeshBuilder builder, float[] vertices, int[] indices, House house,
                      float sx, float sy, float sz, float ox, float oy, float oz) {
        float radians = (float) Math.toRadians(house.rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        int first = -1;
        for (int v = 0; v < vertices.length; v += 8) {
            float lx = vertices[v] * sx + ox;
            float ly = vertices[v + 1] * sy + oy;
            float lz = vertices[v + 2] * sz + oz;
            // Normals take the inverse scale so they stay perpendicular to stretched faces
            float nx = vertices[v + 3] / sx;
            float ny = vertices[v + 4] / sy;
            float nz = vertices[v + 5] / sz;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= length;
            ny /= length;
            nz /= length;
            int index = builder.addVertex(
                    house.x + cosine * lx + sine * lz, house.baseY + ly, house.z - sine * lx + cosine * lz,
                    cosine * nx + sine * nz, ny, -sine * nx + cosine * nz,
                    vertices[v + 6], vertices[v + 7]);
            if (first < 0) {
                first = index;
            }
        }
        for (int i = 0; i + 2 < indices.length; i += 3) {
            builder.addTriangle(first + indices[i], first + indices[i + 1], first + indices[i + 2]);
        }
    }

    /** The nation's fence around the plot, with a gate gap in front of the door. */
    private void bakeFence(NationBatch batch, House house) {
        FenceStyle style = fenceStyles.get(house.nationId);
        MeshBuilder builder = batch.builder("fence");
        float[][] corners = {
            { house.plotMinX, house.plotMaxZ }, { house.plotMinX, house.plotMinZ },
            { house.plotMaxX, house.plotMinZ }, { house.plotMaxX, house.plotMaxZ }
        };
        for (int edge = 0; edge < 4; edge++) {
            float[] from = corners[edge];
            float[] to = corners[(edge + 1) % 4];
            if (edge == 3) {
                // Front edge runs from the right corner back to the left, split by the gate
                fenceRun(builder, house, style, from[0], from[1], GATE_HALF_WIDTH, from[1]);
                fenceRun(builder, house, style, -GATE_HALF_WIDTH, to[1], to[0], to[1]);
            } else {
                fenceRun(builder, house, style, from[0], from[1], to[0], to[1]);
            }
        }
    }

    private void fenceRun(MeshBuilder builder, House house, FenceStyle style, float fromX, float fromZ, float toX, float toZ) {
        float lengthLocal = (float) Math.hypot(toX - fromX, toZ - fromZ);
        if (lengthLocal < 1.0f) {
            return;
        }
        int pieces = Math.max(1, Math.round(lengthLocal / style.postSpacing));
        float[] start = localToWorld(house.x, house.z, house.rotationY, fromX, fromZ);
        float[] end = localToWorld(house.x, house.z, house.rotationY, toX, toZ);
        float dirX = (end[0] - start[0]) / lengthLocal;
        float dirZ = (end[1] - start[1]) / lengthLocal;
        float h = style.height;
        boolean posts = style.type != FenceStyle.Type.WALL && style.type != FenceStyle.Type.HEDGE;

        float[][] points = new float[pieces + 1][];
        for (int i = 0; i <= pieces; i++) {
            float t = (float) i / pieces;
            float x = start[0] + (end[0] - start[0]) * t;
            float z = start[1] + (end[1] - start[1]) * t;
            points[i] = new float[] { x, TerrainMesh.getLayeredHeight(x, z, terrainNoise), z };
        }

        for (int i = 0; i < pieces; i++) {
            float[] a = points[i];
            float[] b = points[i + 1];
            // A bending road may clip the corner of a front garden; the fence stops short of it
            if (isRoadLocation((a[0] + b[0]) * 0.5f, (a[2] + b[2]) * 0.5f, 1.0f)) {
                continue;
            }
            switch (style.type) {
                case WALL:
                case HEDGE:
                    addPrism(builder, a, b, style.thickness, -1.0f, h);
                    break;
                case PANEL:
                    addPrism(builder, a, b, style.thickness, -0.5f, h);
                    break;
                case RAIL:
                    addPrism(builder, a, b, 0.5f, h * 0.3f - 0.3f, h * 0.3f + 0.3f);
                    addPrism(builder, a, b, 0.5f, h * 0.6f - 0.3f, h * 0.6f + 0.3f);
                    addPrism(builder, a, b, 0.5f, h * 0.9f - 0.3f, h * 0.9f + 0.3f);
                    break;
                case PICKET:
                default:
                    addPrism(builder, a, b, 0.5f, h * 0.25f - 0.3f, h * 0.25f + 0.3f);
                    addPrism(builder, a, b, 0.5f, h * 0.7f - 0.3f, h * 0.7f + 0.3f);
                    float pieceLength = (float) Math.hypot(b[0] - a[0], b[2] - a[2]);
                    int pickets = Math.max(1, Math.round(pieceLength / 3.5f));
                    for (int p = 0; p < pickets; p++) {
                        float t = (p + 0.5f) / pickets;
                        float x = a[0] + (b[0] - a[0]) * t;
                        float z = a[2] + (b[2] - a[2]) * t;
                        float ground = a[1] + (b[1] - a[1]) * t;
                        addBox(builder, x, ground - 0.5f, z, dirX, dirZ, 0.7f, 0.3f, h + 0.5f);
                    }
                    break;
            }
            if (posts) {
                addBox(builder, a[0], a[1] - 1.0f, a[2], dirX, dirZ, 0.6f, 0.6f, h + 1.5f);
            }
        }
        float[] last = points[pieces];
        if (posts && !isRoadLocation(last[0], last[2], 1.0f)) {
            addBox(builder, last[0], last[1] - 1.0f, last[2], dirX, dirZ, 0.6f, 0.6f, h + 1.5f);
        }
    }

    /** A sloped slab between two ground points, from bottom to top above the ground at each end. */
    private void addPrism(MeshBuilder builder, float[] a, float[] b, float thickness, float bottom, float top) {
        float dx = b[0] - a[0];
        float dz = b[2] - a[2];
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-3f) {
            return;
        }
        float sideX = -dz / length * thickness * 0.5f;
        float sideZ = dx / length * thickness * 0.5f;
        float[] a0 = { a[0] - sideX, a[1] + bottom, a[2] - sideZ };
        float[] a1 = { a[0] + sideX, a[1] + bottom, a[2] + sideZ };
        float[] a2 = { a[0] + sideX, a[1] + top, a[2] + sideZ };
        float[] a3 = { a[0] - sideX, a[1] + top, a[2] - sideZ };
        float[] b0 = { b[0] - sideX, b[1] + bottom, b[2] - sideZ };
        float[] b1 = { b[0] + sideX, b[1] + bottom, b[2] + sideZ };
        float[] b2 = { b[0] + sideX, b[1] + top, b[2] + sideZ };
        float[] b3 = { b[0] - sideX, b[1] + top, b[2] - sideZ };
        float[] centre = { (a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f + (bottom + top) * 0.5f, (a[2] + b[2]) * 0.5f };
        builder.addConvexFace(centre, a0, b0, b3, a3);
        builder.addConvexFace(centre, a1, b1, b2, a2);
        builder.addConvexFace(centre, a3, b3, b2, a2);
        builder.addConvexFace(centre, a0, a1, a2, a3);
        builder.addConvexFace(centre, b0, b1, b2, b3);
    }

    /** An upright box standing at a point, aligned with the given direction. */
    private void addBox(MeshBuilder builder, float x, float y, float z, float dirX, float dirZ,
                        float halfAlong, float halfAcross, float height) {
        float sideX = -dirZ;
        float sideZ = dirX;
        float[][] base = new float[4][];
        float[][] top = new float[4][];
        float[][] signs = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (int i = 0; i < 4; i++) {
            float px = x + dirX * halfAlong * signs[i][0] + sideX * halfAcross * signs[i][1];
            float pz = z + dirZ * halfAlong * signs[i][0] + sideZ * halfAcross * signs[i][1];
            base[i] = new float[] { px, y, pz };
            top[i] = new float[] { px, y + height, pz };
        }
        float[] centre = { x, y + height * 0.5f, z };
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            builder.addConvexFace(centre, base[i], base[next], top[next], top[i]);
        }
        builder.addConvexFace(centre, top[0], top[1], top[2], top[3]);
    }

    // ==========================================
    //          SIGNS
    // ==========================================

    /** A sign in the front garden beside the path, text facing the road. */
    private InfrastructureObject createHouseSign(House house, Random rand) {
        float lateral = (rand.nextBoolean() ? 1.0f : -1.0f) * (house.width * 0.3f + 8.0f);
        float forward = house.depth * 0.5f + Math.max(3.0f, (house.setback - FRONT_FENCE_GAP) * 0.5f);
        float[] signPos = localToWorld(house.x, house.z, house.rotationY, lateral, forward);
        if (isRoadLocation(signPos[0], signPos[1], 2.0f)) {
            return null;
        }
        float signY = TerrainMesh.getLayeredHeight(signPos[0], signPos[1], terrainNoise);
        if (signY <= seaLevel + 0.5f) {
            return null;
        }
        return createSign(new Vector3(signPos[0], signY, signPos[1]), house.nationId, house.rotationY, rand);
    }

    /** A sign beside the road, text facing across the carriageway. More likely the more urban the street. */
    private InfrastructureObject tryCreateRoadsideSign(RoadSegment segment, Random rand, List<float[]> existingSigns) {
        Double probability = nationSignProbabilities.get(segment.nationId);
        if (probability == null) {
            return null;
        }
        float midX = (segment.start.x + segment.end.x) * 0.5f;
        float midZ = (segment.start.z + segment.end.z) * 0.5f;
        double urbanBoost = 1.0 + URBAN_SIGN_BOOST * settlementManager.getUrbanness(midX, midZ);
        if (rand.nextDouble() >= probability * ROADSIDE_SIGN_CHANCE_SCALE * urbanBoost) {
            return null;
        }

        float t = 0.2f + rand.nextFloat() * 0.6f;
        float side = rand.nextBoolean() ? 1.0f : -1.0f;
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        float outwardX = -dirZ * side;
        float outwardZ = dirX * side;
        float offset = segment.width * 0.5f + ROADSIDE_SIGN_MARGIN;

        float signX = segment.start.x + (segment.end.x - segment.start.x) * t + outwardX * offset;
        float signZ = segment.start.z + (segment.end.z - segment.start.z) * t + outwardZ * offset;
        if (isRoadLocation(signX, signZ, 2.0f) || isPlotLocation(signX, signZ, 2.0f)) {
            return null;
        }
        for (float[] existing : existingSigns) {
            float dx = existing[0] - signX;
            float dz = existing[1] - signZ;
            if (dx * dx + dz * dz < SIGN_SPACING * SIGN_SPACING) {
                return null;
            }
        }
        float signY = TerrainMesh.getLayeredHeight(signX, signZ, terrainNoise);
        if (signY <= seaLevel + 0.5f) {
            return null;
        }
        return createSign(new Vector3(signX, signY, signZ), segment.nationId, facingRotation(-outwardX, -outwardZ), rand);
    }

    private InfrastructureObject createSign(Vector3 position, int nationId, float rotationY, Random rand) {
        int minLen = 80;
        int maxLen = 200;
        int stringLength = minLen + rand.nextInt(maxLen - minLen + 1);

        int[] textString = new int[stringLength];

        int maxGlyphs = mainListener != null ? mainListener.getNationAtlasSize(nationId) : 10;
        if (maxGlyphs <= 0) maxGlyphs = 1;

        int wordLengthCounter = 0;
        int targetWordLength = 3 + rand.nextInt(6);

        for (int c = 0; c < stringLength; c++) {
            if (wordLengthCounter >= targetWordLength) {
                textString[c] = 0;
                wordLengthCounter = 0;
                targetWordLength = 3 + rand.nextInt(6);
            } else {
                textString[c] = 1 + rand.nextInt(maxGlyphs);
                wordLengthCounter++;
            }
        }

        return new InfrastructureObject(InfrastructureObject.Type.SIGN, position, nationId, rotationY, textString);
    }

    // ==========================================
    //          GEOMETRY HELPERS
    // ==========================================

    private float distanceSquaredToSegment(float x, float z, Vector3 start, Vector3 end) {
        float dx = end.x - start.x;
        float dz = end.z - start.z;
        float lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0.0f) {
            float pointDx = x - start.x;
            float pointDz = z - start.z;
            return pointDx * pointDx + pointDz * pointDz;
        }

        float projection = ((x - start.x) * dx + (z - start.z) * dz) / lengthSquared;
        projection = Math.max(0.0f, Math.min(1.0f, projection));
        float closestX = start.x + projection * dx;
        float closestZ = start.z + projection * dz;
        float distanceX = x - closestX;
        float distanceZ = z - closestZ;
        return distanceX * distanceX + distanceZ * distanceZ;
    }

    private Vector3 roadTangent(List<Vector3> points, int fromIndex, int toIndex) {
        Vector3 from = points.get(fromIndex);
        Vector3 to = points.get(toIndex);
        float dx = to.x - from.x;
        float dz = to.z - from.z;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-4f) {
            return new Vector3(1.0f, 0.0f, 0.0f);
        }
        return new Vector3(dx / length, 0.0f, dz / length);
    }

    /** Squared distance between segment AB and the axis-aligned box [-hx, hx] x [-hz, hz]. */
    private static float segmentToBoxDistanceSquared(float ax, float az, float bx, float bz, float hx, float hz) {
        if (segmentIntersectsBox(ax, az, bx, bz, hx, hz)) {
            return 0.0f;
        }
        float best = Math.min(pointToBoxDistanceSquared(ax, az, hx, hz), pointToBoxDistanceSquared(bx, bz, hx, hz));
        best = Math.min(best, pointToSegmentDistanceSquared(-hx, -hz, ax, az, bx, bz));
        best = Math.min(best, pointToSegmentDistanceSquared(hx, -hz, ax, az, bx, bz));
        best = Math.min(best, pointToSegmentDistanceSquared(hx, hz, ax, az, bx, bz));
        best = Math.min(best, pointToSegmentDistanceSquared(-hx, hz, ax, az, bx, bz));
        return best;
    }

    /** Liang-Barsky clip of the segment against the box. */
    private static boolean segmentIntersectsBox(float ax, float az, float bx, float bz, float hx, float hz) {
        float dx = bx - ax;
        float dz = bz - az;
        float[] p = { -dx, dx, -dz, dz };
        float[] q = { ax + hx, hx - ax, az + hz, hz - az };
        float enter = 0.0f;
        float exit = 1.0f;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0f) {
                if (q[i] < 0.0f) {
                    return false;
                }
            } else {
                float r = q[i] / p[i];
                if (p[i] < 0.0f) {
                    enter = Math.max(enter, r);
                } else {
                    exit = Math.min(exit, r);
                }
                if (enter > exit) {
                    return false;
                }
            }
        }
        return true;
    }

    private static float pointToBoxDistanceSquared(float x, float z, float hx, float hz) {
        float dx = Math.max(0.0f, Math.abs(x) - hx);
        float dz = Math.max(0.0f, Math.abs(z) - hz);
        return dx * dx + dz * dz;
    }

    private static float pointToSegmentDistanceSquared(float x, float z, float ax, float az, float bx, float bz) {
        float dx = bx - ax;
        float dz = bz - az;
        float lengthSquared = dx * dx + dz * dz;
        float t = lengthSquared > 0.0f ? ((x - ax) * dx + (z - az) * dz) / lengthSquared : 0.0f;
        t = Math.max(0.0f, Math.min(1.0f, t));
        float offsetX = x - (ax + dx * t);
        float offsetZ = z - (az + dz * t);
        return offsetX * offsetX + offsetZ * offsetZ;
    }

    /**
     * Y rotation (degrees) that turns an object's local +Z, the side a sign's
     * text is printed on, to face the given world direction.
     */
    private static float facingRotation(float directionX, float directionZ) {
        return (float) Math.toDegrees(Math.atan2(directionX, directionZ));
    }

    /** Matches Matrix4Transform.rotateAroundY: local +X maps to (cos, -sin) and local +Z to (sin, cos). */
    private static float[] localToWorld(float originX, float originZ, float rotationY, float localX, float localZ) {
        float radians = (float) Math.toRadians(rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        return new float[] {
            originX + cosine * localX + sine * localZ,
            originZ - sine * localX + cosine * localZ
        };
    }

    private static float[] worldToLocal(House house, float worldX, float worldZ) {
        float radians = (float) Math.toRadians(house.rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        float dx = worldX - house.x;
        float dz = worldZ - house.z;
        return new float[] { cosine * dx - sine * dz, sine * dx + cosine * dz };
    }

    private int chunkIndex(float coordinate) {
        return (int) Math.floor((coordinate + roadChunkSize * 0.5f) / roadChunkSize);
    }

    private long chunkKeyAt(float x, float z) {
        return chunkKey(chunkIndex(x), chunkIndex(z));
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1];
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private Vector3 lerpTangent(Vector3 first, Vector3 second, float amount) {
        float x = first.x + (second.x - first.x) * amount;
        float z = first.z + (second.z - first.z) * amount;
        float length = (float) Math.sqrt(x * x + z * z);
        if (length < 1e-4f) {
            return second;
        }
        return new Vector3(x / length, 0.0f, z / length);
    }

    private float[] miterOffset(Vector3 incoming, Vector3 outgoing, float halfWidth) {
        float incomingSideX = -incoming.z;
        float incomingSideZ = incoming.x;
        float outgoingSideX = -outgoing.z;
        float outgoingSideZ = outgoing.x;
        float miterX = incomingSideX + outgoingSideX;
        float miterZ = incomingSideZ + outgoingSideZ;
        float miterLength = (float) Math.sqrt(miterX * miterX + miterZ * miterZ);

        if (miterLength < 0.001f) {
            return new float[] { outgoingSideX * halfWidth, outgoingSideZ * halfWidth };
        }

        miterX /= miterLength;
        miterZ /= miterLength;
        float scale = halfWidth / Math.max(0.35f, miterX * outgoingSideX + miterZ * outgoingSideZ);
        return new float[] { miterX * scale, miterZ * scale };
    }
}
