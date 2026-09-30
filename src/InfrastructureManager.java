import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import com.xenoguesser.math.Vector3;

public class InfrastructureManager {
    private static final int ROAD_SEED_COUNT = 500;
    private static final float ROAD_SURFACE_OFFSET = 2.0f;
    private static final float ROAD_WIDTH = 34.0f;
    private static final int ROAD_SEGMENT_SUBDIVISIONS = 8;
    private static final float ROAD_LINE_SURFACE_OFFSET = 0.35f;

    private static final int PLACEMENT_ATTEMPTS_PER_CHUNK = 12;
    // Roadside signs roll once per road segment rather than per attempt, so scale the nation's sign chance up
    private static final double ROADSIDE_SIGN_CHANCE_SCALE = 40.0;
    private static final float ROADSIDE_SIGN_MARGIN = 8.0f;
    private static final float BUILDING_ROAD_MARGIN = 30.0f;
    private static final float BUILDING_MAX_GROUND_DROP = 25.0f;
    private static final float BUILDING_FOUNDATION_DEPTH = 3.0f;
    private static final float BUILDING_SIGN_GAP = 14.0f;

    private final Map<Integer, Double> nationSignProbabilities;
    private final Map<Integer, Double> nationBuildingProbabilities;
    private final Map<Integer, RoadLineStyle> roadLineStyles;
    private final Map<Integer, BuildingStyle> buildingStyles;
    private final NationGenerationManager nationManager;
    private final XenoGuesser_GLEventListener mainListener;
    private final long worldSeed;
    private final List<RoadPath> roadNetwork = new ArrayList<>();
    private final Map<String, List<RoadSegment>> roadSegmentsByChunk = new HashMap<>();
    private boolean roadNetworkInitialized;
    private float roadChunkSize;
    private float regionWidth;
    private float seaLevel;
    private PerlinNoise terrainNoise;

    private static final class RoadPath {
        private final List<Vector3> points = new ArrayList<>();
    }

    private static final class RoadSegment {
        private final Vector3 start;
        private final Vector3 end;
        private final Vector3 startTangent;
        private final Vector3 endTangent;
        private final int nationId;
        private final float startDistance;
        private final float length;

        private RoadSegment(Vector3 start, Vector3 end, Vector3 startTangent, Vector3 endTangent,
                            int nationId, float startDistance, float length) {
            this.start = start;
            this.end = end;
            this.startTangent = startTangent;
            this.endTangent = endTangent;
            this.nationId = nationId;
            this.startDistance = startDistance;
            this.length = length;
        }
    }

    private static final class BuildingPlacement {
        private final float x;
        private final float z;
        private final int nationId;
        private final float rotationY;
        private final float sizeScale;
        private final float minGroundY;
        private final float maxGroundY;
        private final float footprintRadius;

        private BuildingPlacement(float x, float z, int nationId, float rotationY, float sizeScale,
                                  float minGroundY, float maxGroundY, float footprintRadius) {
            this.x = x;
            this.z = z;
            this.nationId = nationId;
            this.rotationY = rotationY;
            this.sizeScale = sizeScale;
            this.minGroundY = minGroundY;
            this.maxGroundY = maxGroundY;
            this.footprintRadius = footprintRadius;
        }
    }

    public InfrastructureManager(long seed, int numNations, NationGenerationManager nationManager, XenoGuesser_GLEventListener mainListener) {
        this.worldSeed = seed;
        this.nationManager = nationManager;
        this.mainListener = mainListener;
        this.nationSignProbabilities = new HashMap<>();
        this.nationBuildingProbabilities = new HashMap<>();

        Random rand = new Random(seed + 5555L);
        Random buildingRand = new Random(seed + 6666L);

        for (int i = 1; i <= numNations; i++) {
            double prob = 0.00025 + (rand.nextDouble() * 0.00125);
            nationSignProbabilities.put(i, prob);
            nationBuildingProbabilities.put(i, 0.00025 + (buildingRand.nextDouble() * 0.00125));
        }

        this.roadLineStyles = RoadLineStyle.generateForNations(seed, numNations);
        this.buildingStyles = BuildingStyle.generateForNations(seed, numNations);
    }

    public RoadLineStyle getRoadLineStyle(int nationId) {
        return roadLineStyles.get(nationId);
    }

    public BuildingStyle getBuildingStyle(int nationId) {
        return buildingStyles.get(nationId);
    }

    public List<InfrastructureObject> generateForChunk(int cx, int cz, float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        List<InfrastructureObject> objects = new ArrayList<>();
        prepareRoadNetwork(chunkSize, totalRegionWidth, seaLevelHeight, noise);

        Random signRand = new Random((long)cx * 8912L + (long)cz * 4123L);

        for (BuildingPlacement placement : computeBuildingPlacements(cx, cz)) {
            objects.add(createBuilding(placement));
            if (signRand.nextDouble() < buildingStyles.get(placement.nationId).signChance) {
                InfrastructureObject sign = createBuildingSign(placement, signRand);
                if (sign != null) {
                    objects.add(sign);
                }
            }
        }

        List<RoadSegment> segments = roadSegmentsByChunk.get(cx + "_" + cz);
        if (segments != null) {
            for (RoadSegment segment : segments) {
                objects.add(createRoadSegment(segment, noise));
                InfrastructureObject sign = tryCreateRoadsideSign(segment, signRand);
                if (sign != null) {
                    objects.add(sign);
                }
            }
        }
        return objects;
    }

    public void prepareRoadNetwork(float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        if (roadNetworkInitialized) {
            return;
        }
        roadNetworkInitialized = true;
        roadChunkSize = chunkSize;
        regionWidth = totalRegionWidth;
        seaLevel = seaLevelHeight;
        terrainNoise = noise;

        Random networkRand = new Random(worldSeed ^ 0x6A09E667F3BCC909L);
        float halfRegion = totalRegionWidth * 0.5f;
        float stepLength = chunkSize * 0.7f;
        int seedCount = ROAD_SEED_COUNT;

        for (int seed = 0; seed < seedCount; seed++) {
            float startX = 0.0f;
            float startZ = 0.0f;
            boolean foundLand = false;
            for (int attempt = 0; attempt < 20 && !foundLand; attempt++) {
                startX = (networkRand.nextFloat() * 2.0f - 1.0f) * halfRegion;
                startZ = (networkRand.nextFloat() * 2.0f - 1.0f) * halfRegion;
                foundLand = TerrainMesh.getLayeredHeight(startX, startZ, noise) > seaLevelHeight + 0.5f;
            }

            if (foundLand) {
                float angle = networkRand.nextFloat() * (float) (Math.PI * 2.0);
                growRoadBranch(startX, startZ, angle, 100, stepLength,
                        0, networkRand, halfRegion, seaLevelHeight, noise);
            }
        }

        for (RoadPath path : roadNetwork) {
            // Running distance along the path keeps dash patterns continuous across segments
            float distanceAlongPath = 0.0f;
            for (int i = 0; i < path.points.size() - 1; i++) {
                Vector3 start = path.points.get(i);
                Vector3 end = path.points.get(i + 1);
                Vector3 startTangent = roadTangent(path.points, i == 0 ? i : i - 1, i + 1);
                Vector3 endTangent = roadTangent(path.points, i, i + 1 == path.points.size() - 1 ? i + 1 : i + 2);
                float midpointX = (start.x + end.x) * 0.5f;
                float midpointZ = (start.z + end.z) * 0.5f;
                float length = (float) Math.sqrt((end.x - start.x) * (end.x - start.x) + (end.z - start.z) * (end.z - start.z));
                int nationId = nationManager.getNationAtWorld(midpointX, midpointZ, totalRegionWidth);
                int segmentChunkX = (int) Math.floor((midpointX + chunkSize * 0.5f) / chunkSize);
                int segmentChunkZ = (int) Math.floor((midpointZ + chunkSize * 0.5f) / chunkSize);
                String key = segmentChunkX + "_" + segmentChunkZ;
                RoadSegment segment = new RoadSegment(start, end, startTangent, endTangent, nationId, distanceAlongPath, length);
                roadSegmentsByChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(segment);
                distanceAlongPath += length;
            }
        }
    }

    public boolean isRoadLocation(float worldX, float worldZ, float clearance) {
        float clearanceSquared = clearance * clearance;
        int centerChunkX = (int) Math.floor((worldX + roadChunkSize * 0.5f) / roadChunkSize);
        int centerChunkZ = (int) Math.floor((worldZ + roadChunkSize * 0.5f) / roadChunkSize);
        int chunkRadius = Math.max(1, (int) Math.ceil(clearance / roadChunkSize));

        for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
            for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
                List<RoadSegment> segments = roadSegmentsByChunk.get(chunkX + "_" + chunkZ);
                if (segments == null) {
                    continue;
                }
                for (RoadSegment segment : segments) {
                    if (distanceSquaredToSegment(worldX, worldZ, segment.start, segment.end) <= clearanceSquared) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether a point lies within clearance of any building footprint. Building
     * placement is a pure function of the chunk, so this can be asked before the
     * chunk's infrastructure has been generated.
     */
    public boolean isBuildingLocation(float worldX, float worldZ, float clearance) {
        if (!roadNetworkInitialized) {
            return false;
        }
        int centerChunkX = (int) Math.floor(worldX / roadChunkSize);
        int centerChunkZ = (int) Math.floor(worldZ / roadChunkSize);

        for (int chunkZ = centerChunkZ - 1; chunkZ <= centerChunkZ + 1; chunkZ++) {
            for (int chunkX = centerChunkX - 1; chunkX <= centerChunkX + 1; chunkX++) {
                for (BuildingPlacement placement : computeBuildingPlacements(chunkX, chunkZ)) {
                    float reach = placement.footprintRadius + clearance;
                    float dx = worldX - placement.x;
                    float dz = worldZ - placement.z;
                    if (dx * dx + dz * dz <= reach * reach) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

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

    private void growRoadBranch(float startX, float startZ, float direction, int steps,
                                float stepLength, int depth, Random random, float halfRegion,
                                float seaLevelHeight, PerlinNoise noise) {
        RoadPath path = new RoadPath();
        float x = startX;
        float z = startZ;
        float heading = direction;

        for (int step = 0; step < steps; step++) {
            if (Math.abs(x) > halfRegion || Math.abs(z) > halfRegion) {
                break;
            }

            float height = TerrainMesh.getLayeredHeight(x, z, noise);
            if (height <= seaLevelHeight + 0.5f) {
                break;
            }

            path.points.add(new Vector3(x, height, z));
            heading += (random.nextFloat() - 0.5f) * 0.55f;
            x += (float) Math.cos(heading) * stepLength;
            z += (float) Math.sin(heading) * stepLength;
        }

        if (path.points.size() >= 2) {
            roadNetwork.add(path);
        }

        if (depth < 3 && path.points.size() >= 7) {
            int branchCount = random.nextFloat() < 0.72f ? 2 : 1;
            for (int branch = 0; branch < branchCount; branch++) {
                if (random.nextFloat() > 0.82f) {
                    continue;
                }
                int branchIndex = 3 + random.nextInt(path.points.size() - 5);
                Vector3 branchStart = path.points.get(branchIndex);
                float branchDirection = heading + (branch == 0 ? 1.0f : -1.0f)
                        * (0.65f + random.nextFloat() * 0.8f);
                growRoadBranch(branchStart.x, branchStart.z, branchDirection,
                        steps - 4, stepLength * 0.9f, depth + 1, random, halfRegion,
                        seaLevelHeight, noise);
            }
        }
    }

    private Vector3 roadTangent(List<Vector3> points, int fromIndex, int toIndex) {
        Vector3 from = points.get(fromIndex);
        Vector3 to = points.get(toIndex);
        float dx = to.x - from.x;
        float dz = to.z - from.z;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        return new Vector3(dx / length, 0.0f, dz / length);
    }

    private InfrastructureObject createRoadSegment(RoadSegment segment, PerlinNoise noise) {
        final int segments = ROAD_SEGMENT_SUBDIVISIONS;
        final float roadWidth = ROAD_WIDTH;
        float[] vertices = new float[(segments + 1) * 2 * 8];
        int[] indices = new int[segments * 6];
        float[][] leftEdge = new float[segments + 1][];
        float[][] rightEdge = new float[segments + 1][];

        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            float x = segment.start.x + (segment.end.x - segment.start.x) * t;
            float z = segment.start.z + (segment.end.z - segment.start.z) * t;
            Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, t);
            float sideX = -tangent.z * roadWidth * 0.5f;
            float sideZ = tangent.x * roadWidth * 0.5f;

            if (i == 0) {
                float[] miter = miterOffset(segment.startTangent, tangent, roadWidth * 0.5f);
                sideX = miter[0];
                sideZ = miter[1];
            } else if (i == segments) {
                float[] miter = miterOffset(tangent, segment.endTangent, roadWidth * 0.5f);
                sideX = miter[0];
                sideZ = miter[1];
            }
            float leftX = x - sideX;
            float leftZ = z - sideZ;
            float rightX = x + sideX;
            float rightZ = z + sideZ;
            float leftY = TerrainMesh.getLayeredHeight(leftX, leftZ, noise) + ROAD_SURFACE_OFFSET;
            float rightY = TerrainMesh.getLayeredHeight(rightX, rightZ, noise) + ROAD_SURFACE_OFFSET;
            leftEdge[i] = new float[] { leftX, leftY, leftZ };
            rightEdge[i] = new float[] { rightX, rightY, rightZ };

            writeRoadVertex(vertices, (i * 2) * 8, leftX, leftY, leftZ, t);
            writeRoadVertex(vertices, (i * 2 + 1) * 8, rightX, rightY, rightZ, t);
        }

        int index = 0;
        for (int i = 0; i < segments; i++) {
            int left = i * 2;
            int right = left + 1;
            int nextLeft = left + 2;
            int nextRight = left + 3;
            indices[index++] = left;
            indices[index++] = nextLeft;
            indices[index++] = right;
            indices[index++] = right;
            indices[index++] = nextLeft;
            indices[index++] = nextRight;
        }

        MeshBuilder lines = new MeshBuilder();
        appendRoadLines(lines, segment, leftEdge, rightEdge);

        float centerX = (segment.start.x + segment.end.x) * 0.5f;
        float centerZ = (segment.start.z + segment.end.z) * 0.5f;
        float centerY = TerrainMesh.getLayeredHeight(centerX, centerZ, noise) + ROAD_SURFACE_OFFSET;
        return InfrastructureObject.createRoad(
            new Vector3(centerX, centerY, centerZ),
            segment.nationId,
            (segment.length + roadWidth) * 0.5f,
            vertices,
            indices,
            lines.vertexArray(),
            lines.indexArray()
        );
    }

    /**
     * Paints the segment's nation line style onto the road surface. Stripes are
     * split at every road subdivision and dash boundary so they follow the surface.
     */
    private void appendRoadLines(MeshBuilder builder, RoadSegment segment, float[][] leftEdge, float[][] rightEdge) {
        RoadLineStyle style = roadLineStyles.get(segment.nationId);
        if (style == null || segment.length <= 0.0f) {
            return;
        }

        List<Float> breakpoints = new ArrayList<>();
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            breakpoints.add((float) i / ROAD_SEGMENT_SUBDIVISIONS);
        }
        if (style.dashed) {
            float period = style.dashPeriod();
            float segmentEnd = segment.startDistance + segment.length;
            for (float dashStart = (float) Math.floor(segment.startDistance / period) * period;
                 dashStart <= segmentEnd; dashStart += period) {
                addBreakpoint(breakpoints, (dashStart - segment.startDistance) / segment.length);
                addBreakpoint(breakpoints, (dashStart + style.dashLength - segment.startDistance) / segment.length);
            }
        }
        Collections.sort(breakpoints);

        float halfLine = style.lineWidth * 0.5f;
        for (float offset : style.stripeOffsets(ROAD_WIDTH)) {
            float innerAcross = 0.5f + (offset - halfLine) / ROAD_WIDTH;
            float outerAcross = 0.5f + (offset + halfLine) / ROAD_WIDTH;

            for (int k = 0; k < breakpoints.size() - 1; k++) {
                float t0 = breakpoints.get(k);
                float t1 = breakpoints.get(k + 1);
                if (t1 - t0 < 1e-5f) {
                    continue;
                }
                float midDistance = segment.startDistance + (t0 + t1) * 0.5f * segment.length;
                if (!style.isPaintedAt(midDistance)) {
                    continue;
                }

                int a = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t0, innerAcross);
                int b = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t0, outerAcross);
                int c = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t1, outerAcross);
                int d = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t1, innerAcross);
                builder.addTriangle(a, b, c);
                builder.addTriangle(a, c, d);
            }
        }
    }

    private void addBreakpoint(List<Float> breakpoints, float t) {
        if (t > 0.0f && t < 1.0f) {
            breakpoints.add(t);
        }
    }

    /** t runs along the segment, across runs from the left edge (0) to the right edge (1). */
    private int addRoadSurfaceVertex(MeshBuilder builder, float[][] leftEdge, float[][] rightEdge, float t, float across) {
        float scaled = t * ROAD_SEGMENT_SUBDIVISIONS;
        int i = Math.min((int) Math.floor(scaled), ROAD_SEGMENT_SUBDIVISIONS - 1);
        float along = scaled - i;

        float[] point = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            float left = leftEdge[i][axis] + (leftEdge[i + 1][axis] - leftEdge[i][axis]) * along;
            float right = rightEdge[i][axis] + (rightEdge[i + 1][axis] - rightEdge[i][axis]) * along;
            point[axis] = left + (right - left) * across;
        }
        return builder.addVertex(point[0], point[1] + ROAD_LINE_SURFACE_OFFSET, point[2], 0.0f, 1.0f, 0.0f, across, t);
    }

    /**
     * Deterministic building layout for a chunk. Kept free of any other random
     * stream so isBuildingLocation can recompute it for flora exclusion.
     */
    private List<BuildingPlacement> computeBuildingPlacements(int cx, int cz) {
        List<BuildingPlacement> placements = new ArrayList<>();
        Random rand = new Random(worldSeed ^ ((long) cx * 73856093L) ^ ((long) cz * 19349663L) ^ 0x5BD1E995L);
        float startX = cx * roadChunkSize;
        float startZ = cz * roadChunkSize;

        for (int i = 0; i < PLACEMENT_ATTEMPTS_PER_CHUNK; i++) {
            float worldX = startX + (rand.nextFloat() * roadChunkSize);
            float worldZ = startZ + (rand.nextFloat() * roadChunkSize);

            int nationId = nationManager.getNationAtWorld(worldX, worldZ, regionWidth);
            Double probability = nationBuildingProbabilities.get(nationId);
            if (probability == null || rand.nextDouble() >= probability) {
                continue;
            }

            BuildingStyle style = buildingStyles.get(nationId);
            float rotationY = rand.nextFloat() * 360.0f;
            float sizeScale = 0.9f + rand.nextFloat() * 0.2f;
            float halfWidth = style.width * sizeScale * 0.5f;
            float halfDepth = style.depth * sizeScale * 0.5f;

            float minGroundY = Float.MAX_VALUE;
            float maxGroundY = -Float.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                float[] cornerPos = localToWorld(worldX, worldZ, rotationY,
                        (corner & 1) == 0 ? -halfWidth : halfWidth,
                        (corner & 2) == 0 ? -halfDepth : halfDepth);
                float groundY = TerrainMesh.getLayeredHeight(cornerPos[0], cornerPos[1], terrainNoise);
                minGroundY = Math.min(minGroundY, groundY);
                maxGroundY = Math.max(maxGroundY, groundY);
            }
            if (minGroundY <= seaLevel + 0.5f || maxGroundY - minGroundY > BUILDING_MAX_GROUND_DROP) {
                continue;
            }

            float footprintRadius = (float) Math.sqrt(halfWidth * halfWidth + halfDepth * halfDepth)
                    + style.roofOverhang * sizeScale;
            if (isRoadLocation(worldX, worldZ, ROAD_WIDTH * 0.5f + footprintRadius + BUILDING_ROAD_MARGIN)) {
                continue;
            }

            placements.add(new BuildingPlacement(worldX, worldZ, nationId, rotationY, sizeScale,
                    minGroundY, maxGroundY, footprintRadius));
        }
        return placements;
    }

    private InfrastructureObject createBuilding(BuildingPlacement placement) {
        BuildingStyle style = buildingStyles.get(placement.nationId);
        float scale = placement.sizeScale;
        float width = style.width * scale;
        float depth = style.depth * scale;

        // Sink the base below the lowest corner and grow the walls so slopes never expose a gap
        float baseY = placement.minGroundY - BUILDING_FOUNDATION_DEPTH;
        float wallHeight = (placement.maxGroundY - baseY) + style.wallHeight * scale;

        float[] doorPos = localToWorld(placement.x, placement.z, placement.rotationY, 0.0f, depth * 0.5f);
        float doorGroundY = TerrainMesh.getLayeredHeight(doorPos[0], doorPos[1], terrainNoise);

        return InfrastructureObject.createBuilding(
            new Vector3(placement.x, baseY, placement.z),
            placement.nationId,
            placement.rotationY,
            width,
            depth,
            wallHeight,
            style.roofHeight * scale,
            style.roofOverhang * scale,
            style.doorWidth * scale,
            style.doorHeight * scale,
            doorGroundY - baseY
        );
    }

    /** A sign standing in front of the building, off to one side of the door, text facing away from it. */
    private InfrastructureObject createBuildingSign(BuildingPlacement placement, Random rand) {
        BuildingStyle style = buildingStyles.get(placement.nationId);
        float width = style.width * placement.sizeScale;
        float depth = style.depth * placement.sizeScale;
        float lateral = (rand.nextBoolean() ? 1.0f : -1.0f) * width * 0.3f;

        float[] signPos = localToWorld(placement.x, placement.z, placement.rotationY,
                lateral, depth * 0.5f + BUILDING_SIGN_GAP);
        if (isRoadLocation(signPos[0], signPos[1], ROAD_WIDTH * 0.5f + 16.0f)) {
            return null;
        }
        float signY = TerrainMesh.getLayeredHeight(signPos[0], signPos[1], terrainNoise);
        if (signY <= seaLevel + 0.5f) {
            return null;
        }
        return createSign(new Vector3(signPos[0], signY, signPos[1]), placement.nationId, placement.rotationY, rand);
    }

    /** A sign beside the road, text facing across the carriageway. */
    private InfrastructureObject tryCreateRoadsideSign(RoadSegment segment, Random rand) {
        Double probability = nationSignProbabilities.get(segment.nationId);
        if (probability == null || rand.nextDouble() >= probability * ROADSIDE_SIGN_CHANCE_SCALE) {
            return null;
        }

        float t = 0.2f + rand.nextFloat() * 0.6f;
        float side = rand.nextBoolean() ? 1.0f : -1.0f;
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        float outwardX = -dirZ * side;
        float outwardZ = dirX * side;
        float offset = ROAD_WIDTH * 0.5f + ROADSIDE_SIGN_MARGIN;

        float signX = segment.start.x + (segment.end.x - segment.start.x) * t + outwardX * offset;
        float signZ = segment.start.z + (segment.end.z - segment.start.z) * t + outwardZ * offset;
        if (isRoadLocation(signX, signZ, ROAD_WIDTH * 0.5f + 2.0f)) {
            return null;
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

        int maxGlyphs = mainListener.getNationAtlasSize(nationId);
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

    /**
     * Y rotation (degrees) that turns an object's local +Z, the side a sign's
     * text is printed on, to face the given world direction.
     */
    private float facingRotation(float directionX, float directionZ) {
        return (float) Math.toDegrees(Math.atan2(directionX, directionZ));
    }

    /** Matches Matrix4Transform.rotateAroundY: local +X maps to (cos, -sin) and local +Z to (sin, cos). */
    private float[] localToWorld(float originX, float originZ, float rotationY, float localX, float localZ) {
        float radians = (float) Math.toRadians(rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        return new float[] {
            originX + cosine * localX + sine * localZ,
            originZ - sine * localX + cosine * localZ
        };
    }

    private Vector3 lerpTangent(Vector3 first, Vector3 second, float amount) {
        float x = first.x + (second.x - first.x) * amount;
        float z = first.z + (second.z - first.z) * amount;
        float length = (float) Math.sqrt(x * x + z * z);
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

    private void writeRoadVertex(float[] vertices, int offset, float x, float y, float z, float alongRoad) {
        vertices[offset] = x;
        vertices[offset + 1] = y;
        vertices[offset + 2] = z;
        vertices[offset + 3] = 0.0f;
        vertices[offset + 4] = 1.0f;
        vertices[offset + 5] = 0.0f;
        vertices[offset + 6] = alongRoad;
        vertices[offset + 7] = 0.0f;
    }
}
