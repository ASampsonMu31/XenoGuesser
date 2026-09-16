import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.jogamp.opengl.GL3;

import java.util.List;
import java.util.ArrayList;

import gmaths.Mat4;
import gmaths.Vec3;

public class InfrastructureManager {
    
    private final Map<Integer, Double> nationSignProbabilities;
    private final NationGenerationManager nationManager;
    private final XenoGuesser_GLEventListener mainListener;
    
    public InfrastructureManager(long seed, int numNations, NationGenerationManager nationManager, XenoGuesser_GLEventListener mainListener) {
        this.nationManager = nationManager;
        this.mainListener = mainListener;
        this.nationSignProbabilities = new HashMap<>();
        
        Random rand = new Random(seed + 5555L); 
        
        for (int i = 1; i <= numNations; i++) {
            double prob = 0.005 + (rand.nextDouble() * 0.025);
            nationSignProbabilities.put(i, prob);
        }
    }

    // NOTE: Added GL3 gl to the parameter list to allow mesh generation!
    public List<InfrastructureObject> generateForChunk(int cx, int cz, float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise, GL3 gl) {
        List<InfrastructureObject> objects = new ArrayList<>();
        
        float centerX = cx * chunkSize;
        float centerZ = cz * chunkSize;
        float startX = centerX - (chunkSize * 0.5f);
        float startZ = centerZ - (chunkSize * 0.5f);
        
        // --- NEW: ROAD GENERATION ---
        int roadCountInChunk = 0;
        boolean verticalRoad = Math.floorMod(cx, 5) == 0;
        boolean horizontalRoad = Math.floorMod(cz, 5) == 0;
        if (verticalRoad || horizontalRoad) {
            float sampleY = TerrainMesh.getLayeredHeight(centerX, centerZ, noise);
            if (sampleY > seaLevelHeight + 0.5f) {
                if (verticalRoad) {
                    roadCountInChunk += addRoadObject(
                        objects, gl, centerX, centerZ, startZ, chunkSize, noise, true, cx, cz, roadCountInChunk
                    );
                }
                if (horizontalRoad) {
                    roadCountInChunk += addRoadObject(
                        objects, gl, centerX, centerZ, startX, chunkSize, noise, false, cx, cz, roadCountInChunk
                    );
                }
            }
        }

        if (roadCountInChunk > 0) {
            System.out.println(String.format("--> Total Road Objects Generated in Chunk (%d, %d): %d", cx, cz, roadCountInChunk));
        }

        // --- EXISTING: SIGN GENERATION ---
        Random chunkRand = new Random((long)cx * 8912L + (long)cz * 4123L);
        int attemptsPerChunk = 12;
        
        for (int i = 0; i < attemptsPerChunk; i++) {
            float worldX = startX + (chunkRand.nextFloat() * chunkSize);
            float worldZ = startZ + (chunkRand.nextFloat() * chunkSize);
            
            float worldY = TerrainMesh.getLayeredHeight(worldX, worldZ, noise);
            
            if (worldY > seaLevelHeight + 0.5f) {

                int nationId = nationManager.getNationAtWorld(worldX, worldZ, totalRegionWidth);

                if (nationId != 0 && nationSignProbabilities.containsKey(nationId)) {
                    if (chunkRand.nextDouble() < nationSignProbabilities.get(nationId)) {
                        float randomRotY = chunkRand.nextFloat() * 360.0f;
                        
                        // --- UPDATED STRING LENGTH GENERATION ---
                        int minLen = 80;
                        int maxLen = 200;
                        int stringLength = minLen + chunkRand.nextInt(maxLen - minLen + 1);
                        
                        int[] textString = new int[stringLength];
                        
                        int maxGlyphs = mainListener.getNationAtlasSize(nationId); 
                        if (maxGlyphs <= 0) maxGlyphs = 1; 
                        
                        // --- NATURAL WORD SPACING LOGIC ---
                        int wordLengthCounter = 0;
                        int targetWordLength = 3 + chunkRand.nextInt(6); // Words of length 3-8
                        
                        for (int c = 0; c < stringLength; c++) {
                            if (wordLengthCounter >= targetWordLength) {
                                // Insert a space (index 0) to separate words
                                textString[c] = 0; 
                                wordLengthCounter = 0;
                                targetWordLength = 3 + chunkRand.nextInt(6);
                            } else {
                                // Pick a random glyph (1 to maxGlyphs - 1)
                                textString[c] = 1 + chunkRand.nextInt(maxGlyphs);
                                wordLengthCounter++;
                            }
                        }
                        
                        objects.add(new InfrastructureObject(InfrastructureObject.Type.SIGN, new Vec3(worldX, worldY, worldZ), nationId, randomRotY, textString));
                    }
                }
            }
        }
        return objects;
    }

    private int addRoadObject(List<InfrastructureObject> objects, GL3 gl, float centerX, float centerZ,
                              float chunkStart, float chunkSize, PerlinNoise noise, boolean vertical,
                              int cx, int cz, int roadNumber) {
        float roadCenter = vertical ? centerX : centerZ;
        Mesh roadMesh = generateRoadMeshForChunk(gl, roadCenter, chunkStart, chunkSize, noise, vertical);
        InfrastructureObject roadObj = new InfrastructureObject(
            InfrastructureObject.Type.ROAD,
            new Vec3(centerX, TerrainMesh.getLayeredHeight(centerX, centerZ, noise) + 4.0f, centerZ),
            0, 0f, null
        );
        roadObj.modelMatrix = new Mat4(1);
        roadObj.customMesh = roadMesh;
        objects.add(roadObj);
        System.out.println(String.format(
            "[Road Generator] Chunk (%d, %d) | Road #%d | Base Y=%.1f | Vertices: %d | Indices/Tris: %d (%d tris)",
            cx, cz, roadNumber + 1, roadObj.position.y,
            roadMesh.getVertexCount(), roadMesh.getIndexCount(), roadMesh.getIndexCount() / 3
        ));
        return 1;
    }

    private Mesh generateRoadMeshForChunk(GL3 gl, float roadCenter, float chunkStart, float chunkSize,
                                          PerlinNoise noise, boolean vertical) {
        float halfWidth = 9.0f;
        int segments = 50;
        
        // 8 floats per vertex: 3 position, 3 normal, 2 UV
        float[] vertices = new float[(segments + 1) * 2 * 8];
        int[] indices = new int[segments * 6];
        
        int vIdx = 0;
        for (int i = 0; i <= segments; i++) {
            float t = (float)i / segments;
            float x = vertical ? roadCenter : chunkStart + t * chunkSize;
            float z = vertical ? chunkStart + t * chunkSize : roadCenter;
            float sideX = vertical ? halfWidth : 0.0f;
            float sideZ = vertical ? 0.0f : halfWidth;
            float leftX = x - sideX;
            float rightX = x + sideX;
            float leftZ = z - sideZ;
            float rightZ = z + sideZ;
            float leftY = TerrainMesh.getLayeredHeight(leftX, leftZ, noise) + 4.0f;
            float rightY = TerrainMesh.getLayeredHeight(rightX, rightZ, noise) + 4.0f;
            
            // Left Vertex
            vertices[vIdx++] = leftX; vertices[vIdx++] = leftY; vertices[vIdx++] = leftZ;
            vertices[vIdx++] = 0; vertices[vIdx++] = 1; vertices[vIdx++] = 0; // Up Normal
            vertices[vIdx++] = 0; vertices[vIdx++] = t;
            
            // Right Vertex
            vertices[vIdx++] = rightX; vertices[vIdx++] = rightY; vertices[vIdx++] = rightZ;
            vertices[vIdx++] = 0; vertices[vIdx++] = 1; vertices[vIdx++] = 0; // Up Normal
            vertices[vIdx++] = 1; vertices[vIdx++] = t;
        }
        
        int iIdx = 0;
        for (int i = 0; i < segments; i++) {
            int left0 = i * 2;
            int right0 = left0 + 1;
            int left1 = left0 + 2;
            int right1 = left0 + 3;
            
            // Triangle 1: 1 corner on left, 2 on right
            // Triangle 1: Counter-Clockwise
            indices[iIdx++] = left0;
            indices[iIdx++] = right1;
            indices[iIdx++] = right0;

            // Triangle 2: Counter-Clockwise
            indices[iIdx++] = left0;
            indices[iIdx++] = left1;
            indices[iIdx++] = right1;
        }
        
        return new Mesh(gl, vertices, indices);
    }
}