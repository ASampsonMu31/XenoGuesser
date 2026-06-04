import com.jogamp.opengl.*;

public class TerrainMesh {

    public static float getLayeredHeight(float worldX, float worldZ, PerlinNoise noise) {

        // -----------------------------------------------------------------------
        // LAYER 1: continentLayer1 (Ultra-low macro continental signals)
        // -----------------------------------------------------------------------
        float f1 = 0.00003f;
        float macroMacroVariationHeight = 800.0f;
        float rawNoise1 = noise.eval(worldX * f1, worldZ * f1);
        float continentLayer1 = (rawNoise1 * Math.abs(rawNoise1)) * macroMacroVariationHeight;

        // -----------------------------------------------------------------------
        // LAYER 2: continentLayer2 (Master macro continental signals)
        // -----------------------------------------------------------------------
        float f2 = 0.00008f;
        float macroVariationHeight = 400.0f;
        float rawNoise2 = noise.eval(worldX * f2, worldZ * f2);
        float continentLayer2 = (rawNoise2 * Math.abs(rawNoise2)) * macroVariationHeight;

        // -----------------------------------------------------------------------
        // LAYER 3: mountainLayer (Base mountains & deep canyons)
        // -----------------------------------------------------------------------
        float f3 = 0.0002f;
        float maxMountainHeight = 1200.0f;
        float maxCanyonDepth = -800.0f;
        float rawNoise3 = noise.eval(worldX * f3, worldZ * f3);
        float mountainLayer = 0.0f;

        if (rawNoise3 > 0.0f) {
            float mountainShape = rawNoise3 * rawNoise3 * rawNoise3 * rawNoise3;
            mountainLayer = mountainShape * maxMountainHeight;
        } else {
            float positiveValleySignal = Math.abs(rawNoise3);
            float valleyShape = positiveValleySignal * positiveValleySignal * positiveValleySignal * positiveValleySignal;
            mountainLayer = valleyShape * maxCanyonDepth;
        }

        // -----------------------------------------------------------------------
        // ALTITUDE STRUCTURAL BASELINE
        // -----------------------------------------------------------------------
        // Combines macro continents and primary geographic features to form the 
        // true underlying layout of oceans, shores, and land masses.
        float baseHeight = mountainLayer + continentLayer2 + continentLayer1;

        // -----------------------------------------------------------------------
        // LAYER 4: hillLayer (Mid-scale hills with low altitude suppression)
        // -----------------------------------------------------------------------
        float f4 = 0.001f;
        float maxHillHeight = 120.0f;
        float rawNoise4 = noise.eval(worldX * f4, worldZ * f4);
        float hillLayer = (rawNoise4 * Math.abs(rawNoise4)) * maxHillHeight;

        // DYNAMIC HILL MASK: Smoothly suppresses hills as base elevation approaches 
        // low-lying or sub-aquatic basins, preventing clusters of small speckle islands.
        float hillAltitudeMask = 1.0f;
        if (baseHeight < 100.0f) {
            hillAltitudeMask = (baseHeight + 200.0f) / 300.0f;
            if (hillAltitudeMask < 0.0f) hillAltitudeMask = 0.0f;
            if (hillAltitudeMask > 1.0f) hillAltitudeMask = 1.0f;
        }
        
        hillLayer *= hillAltitudeMask;

        // -----------------------------------------------------------------------
        // LAYER 5: roughnessLayer (Micro surface roughness ground detail / texture)
        // -----------------------------------------------------------------------
        float f5 = 0.12f;
        float a5 = 2.2f;
        float rawNoise5 = noise.eval(worldX * f5, worldZ * f5);
        float roughnessLayer = (1.0f - Math.abs(rawNoise5)) * a5;

        // Combine structural base with properly restricted hills and micro detail
        return baseHeight + hillLayer + roughnessLayer;
    }

    /**
     * Generates a unique terrain slice positioned at specific global chunk coordinates.
     */
    public static Mesh generateTerrainChunk(GL3 gl, int segments, float scale, int chunkX, int chunkZ, PerlinNoise noise) {
        int coreVertices = (segments + 1) * (segments + 1);
        
        // We only need skirt vertices for the outer edges: 4 edges * (segments + 1)
        int skirtVerticesCount = 4 * (segments + 1);
        
        float[] vertices = new float[(coreVertices + skirtVerticesCount) * 8]; 
        float chunkSize = segments * scale;
        float globalStartX = chunkX * chunkSize;
        float globalStartZ = chunkZ * chunkSize;

        int vertexIndex = 0;
        
        // --- Step 1: Generate Standard Core Grid ---
        for (int z = 0; z <= segments; z++) {
            for (int x = 0; x <= segments; x++) {
                float worldX = globalStartX + (x * scale) - (chunkSize / 2.0f);
                float worldZ = globalStartZ + (z * scale) - (chunkSize / 2.0f);
                float worldY = getLayeredHeight(worldX, worldZ, noise);

                vertices[vertexIndex++] = worldX;
                vertices[vertexIndex++] = worldY; 
                vertices[vertexIndex++] = worldZ;
                
                vertices[vertexIndex++] = 0.0f; vertices[vertexIndex++] = 1.0f; vertices[vertexIndex++] = 0.0f; // Normal
                vertices[vertexIndex++] = (float) x / segments; vertices[vertexIndex++] = (float) z / segments; // UV
            }
        }

        // Set up Index Allocations
        int numCoreIndices = segments * segments * 6;
        int numSkirtIndices = segments * 4 * 6; // 4 edges, each has 'segments' quads, 6 indices per quad
        int[] indices = new int[numCoreIndices + numSkirtIndices];
        
        // Fill core terrain indices
        int indexPointer = 0;
        for (int z = 0; z < segments; z++) {
            for (int x = 0; x < segments; x++) {
                int topLeft = (z * (segments + 1)) + x;
                int topRight = topLeft + 1;
                int bottomLeft = ((z + 1) * (segments + 1)) + x;
                int bottomRight = bottomLeft + 1;
                indices[indexPointer++] = topLeft; indices[indexPointer++] = bottomLeft; indices[indexPointer++] = topRight;
                indices[indexPointer++] = topRight; indices[indexPointer++] = bottomLeft; indices[indexPointer++] = bottomRight;
            }
        }

        // --- Step 2: Generate Skirt Vertices & Stitch Walls ---
        float skirtDepth = 60.0f; 
        int skirtVertexCounter = 0;
        
        // We keep track of where the skirt block starts in our vertex array
        int skirtStartVertexIdx = coreVertices; 

        // Create a mapping layout so we can easily connect core perimeter vertices to their skirt equivalents
        int[] northSkirtIndices = new int[segments + 1];
        int[] southSkirtIndices = new int[segments + 1];
        int[] westSkirtIndices  = new int[segments + 1];
        int[] eastSkirtIndices  = new int[segments + 1];

        for (int z = 0; z <= segments; z++) {
            for (int x = 0; x <= segments; x++) {
                if (x == 0 || x == segments || z == 0 || z == segments) {
                    int coreVertexID = (z * (segments + 1)) + x;
                    int coreStride = coreVertexID * 8;
                    
                    // This index is the absolute vertex ID of our new dropped skirt vertex
                    int currentSkirtVertexID = skirtStartVertexIdx + skirtVertexCounter;
                    
                    // Map it to its respective wall list for clean index stitching below
                    if (z == 0) northSkirtIndices[x] = currentSkirtVertexID;
                    if (z == segments) southSkirtIndices[x] = currentSkirtVertexID;
                    if (x == 0) westSkirtIndices[z] = currentSkirtVertexID;
                    if (x == segments) eastSkirtIndices[z] = currentSkirtVertexID;
                    
                    skirtVertexCounter++;

                    // Write the dropped vertex coordinates down into our float array
                    vertices[vertexIndex++] = vertices[coreStride + 0]; // X
                    vertices[vertexIndex++] = vertices[coreStride + 1] - skirtDepth; // Y (Dropped!)
                    vertices[vertexIndex++] = vertices[coreStride + 2]; // Z
                    
                    // Match the core lighting/UV attributes exactly
                    for(int k = 3; k < 8; k++) { 
                        vertices[vertexIndex++] = vertices[coreStride + k]; 
                    }
                }
            }
        }

        // --- Step 3: Stitching the 4 Skirt Walls into the Index Buffer ---
        // North Wall (z = 0)
        for (int x = 0; x < segments; x++) {
            int cCurr = x; 
            int cNext = x + 1;
            int sCurr = northSkirtIndices[x];
            int sNext = northSkirtIndices[x + 1];
            
            indices[indexPointer++] = cCurr; indices[indexPointer++] = sCurr; indices[indexPointer++] = cNext;
            indices[indexPointer++] = cNext; indices[indexPointer++] = sCurr; indices[indexPointer++] = sNext;
        }

        // South Wall (z = segments)
        for (int x = 0; x < segments; x++) {
            int cCurr = (segments * (segments + 1)) + x;
            int cNext = cCurr + 1;
            int sCurr = southSkirtIndices[x];
            int sNext = southSkirtIndices[x + 1];
            
            indices[indexPointer++] = cCurr; indices[indexPointer++] = cNext; indices[indexPointer++] = sCurr;
            indices[indexPointer++] = cNext; indices[indexPointer++] = sNext; indices[indexPointer++] = sCurr;
        }

        // West Wall (x = 0)
        for (int z = 0; z < segments; z++) {
            int cCurr = z * (segments + 1);
            int cNext = (z + 1) * (segments + 1);
            int sCurr = westSkirtIndices[z];
            int sNext = westSkirtIndices[z + 1];
            
            indices[indexPointer++] = cCurr; indices[indexPointer++] = cNext; indices[indexPointer++] = sCurr;
            indices[indexPointer++] = cNext; indices[indexPointer++] = sNext; indices[indexPointer++] = sCurr;
        }

        // East Wall (x = segments)
        for (int z = 0; z < segments; z++) {
            int cCurr = (z * (segments + 1)) + segments;
            int cNext = ((z + 1) * (segments + 1)) + segments;
            int sCurr = eastSkirtIndices[z];
            int sNext = eastSkirtIndices[z + 1];
            
            indices[indexPointer++] = cCurr; indices[indexPointer++] = sCurr; indices[indexPointer++] = cNext;
            indices[indexPointer++] = cNext; indices[indexPointer++] = sCurr; indices[indexPointer++] = sNext;
        }

        return new Mesh(gl, vertices, indices);
    }
}