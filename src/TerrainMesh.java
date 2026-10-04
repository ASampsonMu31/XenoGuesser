import com.jogamp.opengl.*;

public class TerrainMesh {

    /**
     * The ground's height at chart point (worldX, worldZ). Every layer is noise sampled at
     * that place on the planet's surface (see Planet), so the land runs on round the world
     * with no seam where the chart's east and west edges meet.
     */
    public static float getLayeredHeight(float worldX, float worldZ, PerlinNoise noise) {
        float[] surface = Planet.surface(worldX, worldZ);
        // LAYER 1: the continents. A handful of separate landmasses spread round the planet
        // (see continentField), their coasts broken up by broad noise so they're irregular,
        // now and then split into two or joined by an isthmus
        float f1 = 0.00003f;
        float rawNoise1 = noise.onSphere(surface, f1, 0f, 0f);
        float f2 = 0.00008f;
        float rawNoise2 = noise.onSphere(surface, f2, 0f, 0f);
        // (out at sea the continents' pull fades, so islands can still rise from the broad noise)
        float continent = Math.max(-0.3f, continentField(surface, noise));
        float continentLayer1 = (continent + 0.4f * rawNoise1 + 0.55f * rawNoise2) * CONTINENT_HEIGHT;

        // LAYER 2: continentLayer2 (Master macro continental signals)
        float macroVariationHeight = 400.0f;
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
        float polar = Math.max(0f, Math.min(1f, (sinLatitude - 0.62f) / 0.38f));
        baseHeight -= 120f * polar * polar * (3f - 2f * polar);

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

    private static final float CONTINENT_HEIGHT = 480.0f;

    /**
     * How deep inside a continent a point on the sphere is: 1 at a continent's heart, 0 at
     * its rough edge, below 0 out to sea (the most of any continent). Each continent is an
     * oval patch of the sphere, some long and thin, some broad, at its own angle.
     */
    private static float continentField(float[] surface, PerlinNoise noise) {
        float[][] cores = noise.continentCores;
        if (cores == null) cores = continentCores(noise);
        float inv = 1f / (float) Math.sqrt(surface[0] * surface[0] + surface[1] * surface[1] + surface[2] * surface[2]);
        float px = surface[0] * inv, py = surface[1] * inv, pz = surface[2] * inv;
        float best = -1f;
        for (float[] c : cores) {
            float facing = px * c[0] + py * c[1] + pz * c[2];
            if (facing <= 0f) continue;
            // Across the sphere from the continent's middle, along its long axis and across it
            float along = px * c[3] + py * c[4] + pz * c[5];
            float across = px * c[6] + py * c[7] + pz * c[8];
            float q = (along * along) / (c[9] * c[9]) + (across * across) / (c[10] * c[10]);
            best = Math.max(best, 1f - (float) Math.sqrt(q));
        }
        return best;
    }

    /**
     * The world's continents, made once from its seed: five to eight, spread apart, kept
     * away from the poles, each {middle (unit vector), long axis, short axis, long and short
     * half-sizes (as sines of the angle)}.
     */
    private static synchronized float[][] continentCores(PerlinNoise noise) {
        if (noise.continentCores != null) return noise.continentCores;
        java.util.Random rand = new java.util.Random(noise.seed * 0x5DEECE66DL + 0xC0417L);
        int count = 5 + rand.nextInt(4);
        java.util.List<float[]> cores = new java.util.ArrayList<>();
        for (int k = 0; k < count; k++) {
            float[] centre = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                double lat = Math.asin((rand.nextDouble() * 2 - 1) * Math.sin(Math.toRadians(55)));
                double lon = rand.nextDouble() * Math.PI * 2;
                float[] d = { (float) (Math.cos(lat) * Math.cos(lon)), (float) Math.sin(lat), (float) (Math.cos(lat) * Math.sin(lon)) };
                boolean apart = true;
                for (float[] other : cores) {
                    if (d[0] * other[0] + d[1] * other[1] + d[2] * other[2] > Math.cos(0.85 - attempt * 0.002)) { apart = false; break; }
                }
                if (apart) { centre = d; break; }
            }
            if (centre == null) continue;
            // Two directions along the sphere at the middle, turned to a random angle
            float[] up = Math.abs(centre[1]) < 0.95f ? new float[] { 0f, 1f, 0f } : new float[] { 1f, 0f, 0f };
            float[] east = normalise(cross(up, centre));
            float[] north = cross(centre, east);
            double turn = rand.nextDouble() * Math.PI;
            float[] axis = new float[3], side = new float[3];
            for (int i = 0; i < 3; i++) {
                axis[i] = (float) (east[i] * Math.cos(turn) + north[i] * Math.sin(turn));
                side[i] = (float) (-east[i] * Math.sin(turn) + north[i] * Math.cos(turn));
            }
            // Big and small continents alike; some long and thin
            float size = 0.22f + rand.nextFloat() * 0.38f;
            float aspect = 1f + rand.nextFloat() * 1.4f;
            float major = (float) Math.sin(size * Math.sqrt(aspect)), minor = (float) Math.sin(size / Math.sqrt(aspect));
            cores.add(new float[] { centre[0], centre[1], centre[2], axis[0], axis[1], axis[2], side[0], side[1], side[2], major, minor });
        }
        noise.continentCores = cores.toArray(new float[0][]);
        return noise.continentCores;
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    private static float[] normalise(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[] { v[0] / l, v[1] / l, v[2] / l };
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
        Object[] data = buildChunkData(segments, scale, chunkX, chunkZ, noise);
        return new Mesh(gl, (float[]) data[0], (int[]) data[1]);
    }

    /**
     * A terrain chunk's vertices and indices, {float[], int[]}, without touching the GPU, so
     * it can be worked out on another thread and only uploaded on the GL thread. Heights are
     * sampled once per grid point (and a border round it), normals taken from neighbours.
     */
    public static Object[] buildChunkData(int segments, float scale, int chunkX, int chunkZ, PerlinNoise noise) {
        int coreVertices = (segments + 1) * (segments + 1);
        int skirtVerticesCount = (segments + 1) * 4 - 4; // Safely drops corners
        
        float[] vertices = new float[(coreVertices + skirtVerticesCount) * 8]; 
        float chunkSize = segments * scale;
        float globalStartX = chunkX * chunkSize;
        float globalStartZ = chunkZ * chunkSize;

        int vertexIndex = 0;

        // Heights on the grid and a one-step border round it
        int side = segments + 3;
        float[] grid = new float[side * side];
        for (int z = -1; z <= segments + 1; z++) {
            for (int x = -1; x <= segments + 1; x++) {
                grid[(z + 1) * side + (x + 1)] = getLayeredHeight(globalStartX + (x * scale) - (chunkSize / 2.0f),
                        globalStartZ + (z * scale) - (chunkSize / 2.0f), noise);
            }
        }
        // Normals from the neighbours' heights, flattened as before (the slope across 0.2 units against 1 up)
        float flatten = 0.1f / scale;

        // --- Step 1: Generate Standard Core Grid ---
        for (int z = 0; z <= segments; z++) {
            for (int x = 0; x <= segments; x++) {
                float worldX = globalStartX + (x * scale) - (chunkSize / 2.0f);
                float worldZ = globalStartZ + (z * scale) - (chunkSize / 2.0f);
                int g = (z + 1) * side + (x + 1);
                float worldY = grid[g];
                float gx = (grid[g - 1] - grid[g + 1]) * flatten, gz = (grid[g - side] - grid[g + side]) * flatten;
                float glen = (float) Math.sqrt(gx * gx + 1f + gz * gz);
                float[] normal = { gx / glen, 1f / glen, gz / glen };

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

        return new Object[] { vertices, indices };
    }
}