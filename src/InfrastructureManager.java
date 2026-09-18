import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import gmaths.Vec3;

public class InfrastructureManager {
    private static final int ROAD_SEED_COUNT = 1000;
    private static final float ROAD_SURFACE_OFFSET = 2.0f;
    
    private final Map<Integer, Double> nationSignProbabilities;
    private final NationGenerationManager nationManager;
    private final XenoGuesser_GLEventListener mainListener;
    private final long worldSeed;
    private final List<RoadPath> roadNetwork = new ArrayList<>();
    private final Map<String, List<RoadSegment>> roadSegmentsByChunk = new HashMap<>();
    private boolean roadNetworkInitialized;
    private float roadChunkSize;

    private static final class RoadPath {
        private final List<Vec3> points = new ArrayList<>();
    }

    private static final class RoadSegment {
        private final Vec3 start;
        private final Vec3 end;
        private final Vec3 startTangent;
        private final Vec3 endTangent;

        private RoadSegment(Vec3 start, Vec3 end, Vec3 startTangent, Vec3 endTangent) {
            this.start = start;
            this.end = end;
            this.startTangent = startTangent;
            this.endTangent = endTangent;
        }
    }
    
    public InfrastructureManager(long seed, int numNations, NationGenerationManager nationManager, XenoGuesser_GLEventListener mainListener) {
        this.worldSeed = seed;
        this.nationManager = nationManager;
        this.mainListener = mainListener;
        this.nationSignProbabilities = new HashMap<>();
        
        Random rand = new Random(seed + 5555L); 
        
        for (int i = 1; i <= numNations; i++) {
            double prob = 0.005 + (rand.nextDouble() * 0.025);
            nationSignProbabilities.put(i, prob);
        }
    }

    public List<InfrastructureObject> generateForChunk(int cx, int cz, float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        List<InfrastructureObject> objects = new ArrayList<>();
        prepareRoadNetwork(chunkSize, totalRegionWidth, seaLevelHeight, noise);
        
        float startX = cx * chunkSize;
        float startZ = cz * chunkSize;
        
        Random chunkRand = new Random((long)cx * 8912L + (long)cz * 4123L);
        int attemptsPerChunk = 12;
        
        for (int i = 0; i < attemptsPerChunk; i++) {
            float worldX = startX + (chunkRand.nextFloat() * chunkSize);
            float worldZ = startZ + (chunkRand.nextFloat() * chunkSize);
            
            float worldY = TerrainMesh.getLayeredHeight(worldX, worldZ, noise);
            
            if (worldY > seaLevelHeight + 0.5f && !isRoadLocation(worldX, worldZ, 28.0f)) {

                int nationId = nationManager.getNationAtWorld(worldX, worldZ, totalRegionWidth);

                if (nationId != 0 && nationSignProbabilities.containsKey(nationId)) {
                    if (chunkRand.nextDouble() < nationSignProbabilities.get(nationId)) {
                        float randomRotY = chunkRand.nextFloat() * 360.0f;
                        
                        int minLen = 80;
                        int maxLen = 200;
                        int stringLength = minLen + chunkRand.nextInt(maxLen - minLen + 1);
                        
                        int[] textString = new int[stringLength];
                        
                        int maxGlyphs = mainListener.getNationAtlasSize(nationId); 
                        if (maxGlyphs <= 0) maxGlyphs = 1; 
                        
                        int wordLengthCounter = 0;
                        int targetWordLength = 3 + chunkRand.nextInt(6);
                        
                        for (int c = 0; c < stringLength; c++) {
                            if (wordLengthCounter >= targetWordLength) {
                                textString[c] = 0; 
                                wordLengthCounter = 0;
                                targetWordLength = 3 + chunkRand.nextInt(6);
                            } else {
                                textString[c] = 1 + chunkRand.nextInt(maxGlyphs);
                                wordLengthCounter++;
                            }
                        }
                        
                        objects.add(new InfrastructureObject(InfrastructureObject.Type.SIGN, new Vec3(worldX, worldY, worldZ), nationId, randomRotY, textString));
                    }
                }
            }
        }

        List<RoadSegment> segments = roadSegmentsByChunk.get(cx + "_" + cz);
        if (segments != null) {
            for (RoadSegment segment : segments) {
                objects.add(createRoadSegment(segment, noise, seaLevelHeight));
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
            for (int i = 0; i < path.points.size() - 1; i++) {
                Vec3 start = path.points.get(i);
                Vec3 end = path.points.get(i + 1);
                Vec3 startTangent = roadTangent(path.points, i == 0 ? i : i - 1, i + 1);
                Vec3 endTangent = roadTangent(path.points, i, i + 1 == path.points.size() - 1 ? i + 1 : i + 2);
                float midpointX = (start.x + end.x) * 0.5f;
                float midpointZ = (start.z + end.z) * 0.5f;
                int segmentChunkX = (int) Math.floor((midpointX + chunkSize * 0.5f) / chunkSize);
                int segmentChunkZ = (int) Math.floor((midpointZ + chunkSize * 0.5f) / chunkSize);
                String key = segmentChunkX + "_" + segmentChunkZ;
                RoadSegment segment = new RoadSegment(start, end, startTangent, endTangent);
                roadSegmentsByChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(segment);
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

    private float distanceSquaredToSegment(float x, float z, Vec3 start, Vec3 end) {
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

            path.points.add(new Vec3(x, height, z));
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
                Vec3 branchStart = path.points.get(branchIndex);
                float branchDirection = heading + (branch == 0 ? 1.0f : -1.0f)
                        * (0.65f + random.nextFloat() * 0.8f);
                growRoadBranch(branchStart.x, branchStart.z, branchDirection,
                        steps - 4, stepLength * 0.9f, depth + 1, random, halfRegion,
                        seaLevelHeight, noise);
            }
        }
    }

    private Vec3 roadTangent(List<Vec3> points, int fromIndex, int toIndex) {
        Vec3 from = points.get(fromIndex);
        Vec3 to = points.get(toIndex);
        float dx = to.x - from.x;
        float dz = to.z - from.z;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        return new Vec3(dx / length, 0.0f, dz / length);
    }

    private InfrastructureObject createRoadSegment(RoadSegment segment, PerlinNoise noise, float seaLevelHeight) {
        final int segments = 8;
        final float roadWidth = 34.0f;
        float[] vertices = new float[(segments + 1) * 2 * 8];
        int[] indices = new int[segments * 6];

        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            float x = segment.start.x + (segment.end.x - segment.start.x) * t;
            float z = segment.start.z + (segment.end.z - segment.start.z) * t;
            Vec3 tangent = lerpTangent(segment.startTangent, segment.endTangent, t);
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

        float centerX = (segment.start.x + segment.end.x) * 0.5f;
        float centerZ = (segment.start.z + segment.end.z) * 0.5f;
        float centerY = TerrainMesh.getLayeredHeight(centerX, centerZ, noise) + ROAD_SURFACE_OFFSET;
        return new InfrastructureObject(
            InfrastructureObject.Type.ROAD,
            new Vec3(centerX, centerY, centerZ),
            0,
            0.0f,
            null,
            vertices,
            indices
        );
    }

    private Vec3 lerpTangent(Vec3 first, Vec3 second, float amount) {
        float x = first.x + (second.x - first.x) * amount;
        float z = first.z + (second.z - first.z) * amount;
        float length = (float) Math.sqrt(x * x + z * z);
        return new Vec3(x / length, 0.0f, z / length);
    }

    private float[] miterOffset(Vec3 incoming, Vec3 outgoing, float halfWidth) {
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