import com.jogamp.opengl.*;

public class TerrainMesh {

    /**
     * The ground's height at chart point (worldX, worldZ). Every layer is noise sampled at
     * that place on the planet's surface (see Planet), so the land runs on round the world
     * with no seam where the chart's east and west edges meet.
     */
    public static float getLayeredHeight(float worldX, float worldZ, PerlinNoise noise) {
        float[] surface = Planet.surface(worldX, worldZ);
        // LAYER 1: continentLayer1 (Ultra-low macro continental signals)
        float f1 = 0.00003f;
        float macroMacroVariationHeight = 800.0f;
        float rawNoise1 = noise.onSphere(surface, f1, 0f, 0f);
        float continentLayer1 = (rawNoise1 * Math.abs(rawNoise1)) * macroMacroVariationHeight;

        // LAYER 2: continentLayer2 (Master macro continental signals)
        float f2 = 0.00008f;
        float macroVariationHeight = 400.0f;
        float rawNoise2 = noise.onSphere(surface, f2, 0f, 0f);
        float continentLayer2 = (rawNoise2 * Math.abs(rawNoise2)) * macroVariationHeight;

        // LAYER 3: mountainLayer (Base mountains & deep canyons)
        float f3 = 0.0002f;
        float maxMountainHeight = 1200.0f;
        float maxCanyonDepth = -800.0f;
        float rawNoise3 = noise.onSphere(surface, f3, 0f, 0f);
        float mountainLayer = 0.0f;

        if (rawNoise3 > 0.0f) {
            float mountainShape = rawNoise3 * rawNoise3 * rawNoise3 * rawNoise3;
            mountainLayer = mountainShape * maxMountainHeight;
        } else {
            float positiveValleySignal = Math.abs(rawNoise3);
            float valleyShape = positiveValleySignal * positiveValleySignal * positiveValleySignal * positiveValleySignal;
            mountainLayer = valleyShape * maxCanyonDepth;
        }

        // ALTITUDE STRUCTURAL BASELINE
        float baseHeight = mountainLayer + continentLayer2 + continentLayer1;
        // As on Earth, land thins out towards the poles: the crust sinks away at high latitudes
        float sinLatitude = Math.abs(surface[1]) / (float) Planet.radius();
        float polar = Math.max(0f, Math.min(1f, (sinLatitude - 0.6f) / 0.4f));
        baseHeight -= 200f * polar * polar * (3f - 2f * polar);

        // LAYER 4: hillLayer (Mid-scale hills with low altitude suppression)
        float f4 = 0.001f;
        float maxHillHeight = 120.0f;
        float rawNoise4 = noise.onSphere(surface, f4, 0f, 0f);
        float hillLayer = (rawNoise4 * Math.abs(rawNoise4)) * maxHillHeight;

        // DYNAMIC HILL MASK
        float hillAltitudeMask = 1.0f;
        if (baseHeight < 100.0f) {
            hillAltitudeMask = (baseHeight + 200.0f) / 300.0f;
            if (hillAltitudeMask < 0.0f) hillAltitudeMask = 0.0f;
            if (hillAltitudeMask > 1.0f) hillAltitudeMask = 1.0f;
        }
        hillLayer *= hillAltitudeMask;

        // LAYER 5: roughnessLayer (Micro surface roughness ground detail)
        float f5 = 0.12f;
        float a5 = 2.2f;
        float rawNoise5 = noise.onSphere(surface, f5, 0f, 0f);
        float roughnessLayer = (1.0f - Math.abs(rawNoise5)) * a5;

        return baseHeight + hillLayer + roughnessLayer + mountainRidges(surface, baseHeight + hillLayer, noise);
    }

    /**
     * High ground turns mountainous: sharp ridged noise (crests where the noise crosses zero,
     * each octave weighted by the one above so ridges branch into ridges) rising with
     * altitude, plus fine crags near the tops. Low ground is left alone.
     */
    private static float mountainRidges(float[] surface, float height, PerlinNoise noise) {
        float t = (height - 120.0f) / 380.0f;
        if (t <= 0.0f) return 0.0f;
        float mask = t >= 1.0f ? 1.0f : t * t * (3.0f - 2.0f * t);
        float total = 0.0f, weight = 1.0f;
        float frequency = 0.0016f, amplitude = 260.0f;
        for (int octave = 0; octave < 4; octave++) {
            // Offsets keep each octave's pattern independent of the other layers
            float n = noise.onSphere(surface, frequency, 37.1f * (octave + 1), -19.7f * (octave + 1));
            float ridge = 1.0f - Math.abs(n);
            ridge *= ridge;
            ridge *= weight;
            weight = Math.min(1.0f, ridge * 1.6f);
            total += ridge * amplitude;
            frequency *= 2.3f;
            amplitude *= 0.5f;
        }
        float crags = (1.0f - Math.abs(noise.onSphere(surface, 0.035f, 11.3f, -5.9f))) * 10.0f;
        return mask * (total - 80.0f + crags * mask);
    }

    // Calculates real normal data based on terrain elevation changes
    private static float[] calculateNormal(float x, float z, PerlinNoise noise) {
        float h = 0.1f; 
        float heightL = getLayeredHeight(x - h, z, noise);
        float heightR = getLayeredHeight(x + h, z, noise);
        float heightD = getLayeredHeight(x, z - h, noise);
        float heightU = getLayeredHeight(x, z + h, noise);

        float nx = heightL - heightR;
        float ny = 1.0f; // Reset to 1.0f for true height scaling
        float nz = heightD - heightU;

        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) return new float[]{0, 1, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }

    public static Mesh generateTerrainChunk(GL3 gl, int segments, float scale, int chunkX, int chunkZ, PerlinNoise noise) {
        int coreVertices = (segments + 1) * (segments + 1);
        int skirtVerticesCount = (segments + 1) * 4 - 4; // Safely drops corners
        
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
                
                float[] normal = calculateNormal(worldX, worldZ, noise);

                vertices[vertexIndex++] = worldX;
                vertices[vertexIndex++] = worldY; 
                vertices[vertexIndex++] = worldZ;
                
                vertices[vertexIndex++] = normal[0]; 
                vertices[vertexIndex++] = normal[1]; 
                vertices[vertexIndex++] = normal[2];
                
                vertices[vertexIndex++] = (float) x / segments; 
                vertices[vertexIndex++] = (float) z / segments; 
            }
        }

        int numCoreIndices = segments * segments * 6;
        int numSkirtIndices = segments * 4 * 6; 
        int[] indices = new int[numCoreIndices + numSkirtIndices];
        
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
        int skirtStartVertexIdx = coreVertices; 

        int[] northSkirtIndices = new int[segments + 1];
        int[] southSkirtIndices = new int[segments + 1];
        int[] westSkirtIndices  = new int[segments + 1];
        int[] eastSkirtIndices  = new int[segments + 1];

        for (int z = 0; z <= segments; z++) {
            for (int x = 0; x <= segments; x++) {
                if (x == 0 || x == segments || z == 0 || z == segments) {
                    int coreVertexID = (z * (segments + 1)) + x;
                    int coreStride = coreVertexID * 8;
                    int currentSkirtVertexID = skirtStartVertexIdx + skirtVertexCounter;
                    
                    if (z == 0) northSkirtIndices[x] = currentSkirtVertexID;
                    if (z == segments) southSkirtIndices[x] = currentSkirtVertexID;
                    if (x == 0) westSkirtIndices[z] = currentSkirtVertexID;
                    if (x == segments) eastSkirtIndices[z] = currentSkirtVertexID;
                    
                    skirtVertexCounter++;

                    vertices[vertexIndex++] = vertices[coreStride + 0]; 
                    vertices[vertexIndex++] = vertices[coreStride + 1] - skirtDepth; 
                    vertices[vertexIndex++] = vertices[coreStride + 2]; 
                    
                    for(int k = 3; k < 8; k++) { 
                        vertices[vertexIndex++] = vertices[coreStride + k]; 
                    }
                }
            }
        }

        // --- Step 3: Stitching Skirt Walls ---
        // North Wall
        for (int x = 0; x < segments; x++) {
            indices[indexPointer++] = x; 
            indices[indexPointer++] = northSkirtIndices[x]; 
            indices[indexPointer++] = x + 1;
            
            indices[indexPointer++] = x + 1; 
            indices[indexPointer++] = northSkirtIndices[x]; 
            indices[indexPointer++] = northSkirtIndices[x + 1];
        }

        // South Wall
        for (int x = 0; x < segments; x++) {
            int cCurr = (segments * (segments + 1)) + x;
            indices[indexPointer++] = cCurr; 
            indices[indexPointer++] = cCurr + 1; 
            indices[indexPointer++] = southSkirtIndices[x];
            
            indices[indexPointer++] = cCurr + 1; 
            indices[indexPointer++] = southSkirtIndices[x + 1]; 
            indices[indexPointer++] = southSkirtIndices[x];
        }

        // West Wall
        for (int z = 0; z < segments; z++) {
            int cCurr = z * (segments + 1);
            int cNext = (z + 1) * (segments + 1);
            indices[indexPointer++] = cCurr; 
            indices[indexPointer++] = cNext; 
            indices[indexPointer++] = westSkirtIndices[z];
            
            indices[indexPointer++] = cNext; 
            indices[indexPointer++] = westSkirtIndices[z + 1]; 
            indices[indexPointer++] = westSkirtIndices[z];
        }

        // East Wall
        for (int z = 0; z < segments; z++) {
            int cCurr = (z * (segments + 1)) + segments;
            int cNext = ((z + 1) * (segments + 1)) + segments;
            indices[indexPointer++] = cCurr; 
            indices[indexPointer++] = eastSkirtIndices[z]; 
            indices[indexPointer++] = cNext;
            
            indices[indexPointer++] = cNext; 
            indices[indexPointer++] = eastSkirtIndices[z]; 
            indices[indexPointer++] = eastSkirtIndices[z + 1];
        }

        return new Mesh(gl, vertices, indices);
    }
}