import com.jogamp.opengl.*;
import gmaths.*;
import java.util.*;

/**
 * TerrainMesh: Handles deterministic procedural generation of local planetary terrain chunks.
 * Uses a Cube-to-Sphere (Quad-Sphere) projection with a 5-layer 3D Perlin Noise displacement.
 * Winding order is natively validated as Counter-Clockwise (CCW).
 */
public class TerrainMesh {

    /**
     * Sourced 5-Layer 3D Perlin Noise Height Generator.
     * Evaluates a unit radial direction vector to return a relative displacement height.
     */
    public static float getLayeredHeight3D(Vec3 unitDir, float sX, float sY, float sZ, PerlinNoise noise) {
        float sampleX = (unitDir.x * 2400.0f) + sX;
        float sampleY = (unitDir.y * 2400.0f) + sY;
        float sampleZ = (unitDir.z * 2400.0f) + sZ;

        // LAYER 1: continentLayer1
        float f1 = 0.00003f;
        float macroMacroVariationHeight = 800.0f;
        float rawNoise1 = noise.noise3D(sampleX * f1, sampleY * f1, sampleZ * f1);
        float continentLayer1 = (rawNoise1 * Math.abs(rawNoise1)) * macroMacroVariationHeight;

        // LAYER 2: continentLayer2
        float f2 = 0.00008f;
        float macroVariationHeight = 400.0f;
        float rawNoise2 = noise.noise3D(sampleX * f2, sampleY * f2, sampleZ * f2);
        float continentLayer2 = (rawNoise2 * Math.abs(rawNoise2)) * macroVariationHeight;

        // LAYER 3: mountainLayer
        float f3 = 0.0002f;
        float maxMountainHeight = 1200.0f;
        float maxCanyonDepth = -800.0f;
        float rawNoise3 = noise.noise3D(sampleX * f3, sampleY * f3, sampleZ * f3);
        float mountainLayer = 0.0f;

        if (rawNoise3 > 0.0f) {
            float mountainShape = rawNoise3 * rawNoise3 * rawNoise3 * rawNoise3;
            mountainLayer = mountainShape * maxMountainHeight;
        } else {
            float positiveValleySignal = Math.abs(rawNoise3);
            float valleyShape = positiveValleySignal * positiveValleySignal * positiveValleySignal * positiveValleySignal;
            mountainLayer = valleyShape * maxCanyonDepth;
        }

        float baseHeight = mountainLayer + continentLayer2 + continentLayer1;

        // LAYER 4: hillLayer
        float f4 = 0.001f;
        float maxHillHeight = 120.0f;
        float rawNoise4 = noise.noise3D(sampleX * f4, sampleY * f4, sampleZ * f4);
        float hillLayer = (rawNoise4 * Math.abs(rawNoise4)) * maxHillHeight;

        float hillAltitudeMask = 1.0f;
        if (baseHeight < 100.0f) {
            hillAltitudeMask = (baseHeight + 200.0f) / 300.0f;
            if (hillAltitudeMask < 0.0f) hillAltitudeMask = 0.0f;
            if (hillAltitudeMask > 1.0f) hillAltitudeMask = 1.0f;
        }
        hillLayer *= hillAltitudeMask;

        // LAYER 5: roughnessLayer
        float f5 = 0.12f;
        float a5 = 2.2f;
        float rawNoise5 = noise.noise3D(sampleX * f5, sampleY * f5, sampleZ * f5);
        float roughnessLayer = (1.0f - Math.abs(rawNoise5)) * a5;

        return baseHeight + hillLayer + roughnessLayer;
    }

    /**
     * Generates a single, self-stitching spherical terrain chunk patch.
     * @param gl           The current GL3 graphics context profile
     * @param face         The active cube-face context identifier (0 to 5)
     * @param segments     Resolution configuration per side (number of square quads)
     * @param scale        Physical scale distance separating adjacent vertices
     * @param chunkX       Horizontal relative grid position reference index
     * @param chunkZ       Vertical relative grid position reference index
     * @param seedOffsetX  Unified frame global noise positional shift variable (X)
     * @param seedOffsetY  Unified frame global noise positional shift variable (Y)
     * @param seedOffsetZ  Unified frame global noise positional shift variable (Z)
     * @param noise        The engine reference 3D Perlin Noise algorithm wrapper instance
     * @return             A populated, structured Mesh object buffer containing local GPU assets
     */
    public static Mesh generateSphericalChunk(GL3 gl, int face, int segments, float scale, int chunkX, int chunkZ, 
                                              float seedOffsetX, float seedOffsetY, float seedOffsetZ, PerlinNoise noise) {
        
        int coreVertices = (segments + 1) * (segments + 1);
        int skirtVerticesCount = 4 * (segments + 1);

        // Standard 8 floats per vertex layout array: (X, Y, Z, NX, NY, NZ, U, V)
        float[] vertices = new float[(coreVertices + skirtVerticesCount) * 8];
        float chunkSize = segments * scale;
        float localStartX = chunkX * chunkSize;
        float localStartZ = chunkZ * chunkSize;
        
        // Match base cube dimensions to the actual base planet radius to prevent edge distortion gaps
        float halfCubeSide = PlanetConfig.planetRadius;

        int vertexIndex = 0;
        Vec3[][] corePositions = new Vec3[segments + 1][segments + 1];

        // --- STEP 1: Generate and Morph Flat Cube Face Grid to Sphere ---
        for (int z = 0; z <= segments; z++) {
            for (int x = 0; x <= segments; x++) {
                // Calculate position on the flat face of the cube bounding volume
                float lx = localStartX + (x * scale) - (chunkSize / 2.0f);
                float lz = localStartZ + (z * scale) - (chunkSize / 2.0f);

                float xc = 0, yc = 0, zc = 0;

                // Project 2D coordinates outward into a 3D Cube-Face framework layout
                switch (face) {
                    case 0: xc = lx;              yc = halfCubeSide;   zc = lz;           break; // +Y
                    case 1: xc = lx;              yc = -halfCubeSide;  zc = -lz;          break; // -Y
                    case 2: xc = -halfCubeSide;   yc = lx;             zc = lz;           break; // -X
                    case 3: xc = halfCubeSide;    yc = lx;             zc = -lz;          break; // +X
                    case 4: xc = lx;              yc = lz;             zc = halfCubeSide; break; // +Z
                    case 5: xc = -lx;             yc = lz;             zc = -halfCubeSide;break; // -Z
                }

                // Mathematical Cube-to-Sphere inflation step
                float len = (float) Math.sqrt(xc * xc + yc * yc + zc * zc);
                Vec3 radialDir = new Vec3(xc / len, yc / len, zc / len);

                // Query the exact procedural physical elevation offset
                float heightOffset = getLayeredHeight3D(radialDir, seedOffsetX, seedOffsetY, seedOffsetZ, noise);
                float finalRadius = PlanetConfig.planetRadius + heightOffset;

                // Push position out radially matching final height assignment allocation
                corePositions[z][x] = Vec3.multiply(radialDir, finalRadius);

                // Array assignment positions (X, Y, Z)
                vertices[vertexIndex++] = corePositions[z][x].x;
                vertices[vertexIndex++] = corePositions[z][x].y;
                vertices[vertexIndex++] = corePositions[z][x].z;

                // Analytical Surface Normals (point directly out away from center origin)
                vertices[vertexIndex++] = radialDir.x;
                vertices[vertexIndex++] = radialDir.y;
                vertices[vertexIndex++] = radialDir.z;

                // Standard global spherical mapping projection UV coordinate system
                vertices[vertexIndex++] = (float) (Math.atan2(radialDir.z, radialDir.x) / (2 * Math.PI) + 0.5);
                vertices[vertexIndex++] = (float) (Math.asin(radialDir.y) / Math.PI + 0.5);
            }
        }

        // --- STEP 2: Evaluate Face Vector Orientation and Self-Correct Winding Layers ---
        int mid = segments / 2;
        Vec3 p0 = corePositions[mid][mid];
        Vec3 p1 = corePositions[mid + 1][mid];
        Vec3 p2 = corePositions[mid][mid + 1];
        
        // Explicit layout sanity check to prevent runtime crashes if geometry density scales down too low
        if (p1 == null) p1 = corePositions[Math.min(mid + 1, segments)][mid];
        if (p2 == null) p2 = corePositions[mid][Math.min(mid + 1, segments)];

        Vec3 vA = Vec3.subtract(p1, p0);
        Vec3 vB = Vec3.subtract(p2, p0);
        Vec3 triNormal = Vec3.crossProduct(vA, vB);
        boolean needsWindingFlip = Vec3.dotProduct(triNormal, p0) < 0.0f;

        int numCoreIndices = segments * segments * 6;
        int numSkirtIndices = segments * 4 * 6;
        int[] indices = new int[numCoreIndices + numSkirtIndices];
        int indexPointer = 0;

        // Build continuous core grid quad index elements
        for (int z = 0; z < segments; z++) {
            for (int x = 0; x < segments; x++) {
                int topLeft = (z * (segments + 1)) + x;
                int topRight = topLeft + 1;
                int bottomLeft = ((z + 1) * (segments + 1)) + x;
                int bottomRight = bottomLeft + 1;

                if (needsWindingFlip) {
                    indices[indexPointer++] = topLeft;
                    indices[indexPointer++] = topRight;
                    indices[indexPointer++] = bottomLeft;

                    indices[indexPointer++] = topRight;
                    indices[indexPointer++] = bottomRight;
                    indices[indexPointer++] = bottomLeft;
                } else {
                    indices[indexPointer++] = topLeft;
                    indices[indexPointer++] = bottomLeft;
                    indices[indexPointer++] = topRight;

                    indices[indexPointer++] = topRight;
                    indices[indexPointer++] = bottomLeft;
                    indices[indexPointer++] = bottomRight;
                }
            }
        }

        // --- STEP 3: Generate Under-Grid Skirt Vertices to Mask LOD Gaps ---
        float skirtDepth = 60.0f; // Pull skirt down below sea level or base elevations
        int skirtVertexCounter = 0;
        int skirtStartVertexIdx = coreVertices;

        int[] northSkirtIndices = new int[segments + 1];
        int[] southSkirtIndices = new int[segments + 1];
        int[] westSkirtIndices  = new int[segments + 1];
        int[] eastSkirtIndices  = new int[segments + 1];

        for (int z = 0; z <= segments; z++) {
            for (int x = 0; x <= segments; x++) {
                // Isolate outer border boundary ring vertices
                if (x == 0 || x == segments || z == 0 || z == segments) {
                    int coreVertexID = (z * (segments + 1)) + x;
                    int coreStride = coreVertexID * 8;
                    int currentSkirtVertexID = skirtStartVertexIdx + skirtVertexCounter;

                    // FIX: Populate side maps correctly for overlapping corner points
                    if (z == 0) northSkirtIndices[x] = currentSkirtVertexID;
                    if (z == segments) southSkirtIndices[x] = currentSkirtVertexID;
                    if (x == 0) westSkirtIndices[z] = currentSkirtVertexID;
                    if (x == segments) eastSkirtIndices[z] = currentSkirtVertexID;

                    // FIX: Increment loop step exactly once per border position to guarantee vertex index alignment
                    skirtVertexCounter++;

                    float cx = vertices[coreStride + 0];
                    float cy = vertices[coreStride + 1];
                    float cz = vertices[coreStride + 2];

                    float clen = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                    float nx = cx / clen; float ny = cy / clen; float nz = cz / clen;

                    // Push the skirt points down directly along the radial unit normal vector axis
                    vertices[vertexIndex++] = cx - (nx * skirtDepth);
                    vertices[vertexIndex++] = cy - (ny * skirtDepth);
                    vertices[vertexIndex++] = cz - (nz * skirtDepth);

                    // Duplicate Normals and texture UV assignments from corresponding border positions
                    for (int k = 3; k < 8; k++) {
                        vertices[vertexIndex++] = vertices[coreStride + k];
                    }
                }
            }
        }

        // --- STEP 4: Stitch Skirt Border Faces into Place ---
        for (int i = 0; i < segments; i++) {
            // North Edge Wall Panel
            stitchSkirtWall(indices, indexPointer, i, i + 1, northSkirtIndices[i], northSkirtIndices[i + 1], needsWindingFlip);
            indexPointer += 6;

            // South Edge Wall Panel
            int sCurr = (segments * (segments + 1)) + i;
            stitchSkirtWall(indices, indexPointer, sCurr, sCurr + 1, southSkirtIndices[i], southSkirtIndices[i + 1], !needsWindingFlip);
            indexPointer += 6;

            // West Edge Wall Panel
            int wCurr = i * (segments + 1); int wNext = (i + 1) * (segments + 1);
            stitchSkirtWall(indices, indexPointer, wCurr, wNext, westSkirtIndices[i], westSkirtIndices[i + 1], !needsWindingFlip);
            indexPointer += 6;

            // East Edge Wall Panel
            int eCurr = (i * (segments + 1)) + segments; int eNext = ((i + 1) * (segments + 1)) + segments;
            stitchSkirtWall(indices, indexPointer, eCurr, eNext, eastSkirtIndices[i], eastSkirtIndices[i + 1], needsWindingFlip);
            indexPointer += 6;
        }

        return new Mesh(gl, vertices, indices);
    }

    /**
     * Stitches two adjacent border vertices to their dropped skirt counter-elements.
     */
    private static void stitchSkirtWall(int[] indices, int ptr, int cCurr, int cNext, int sCurr, int sNext, boolean flip) {
        if (flip) {
            indices[ptr + 0] = cCurr;  indices[ptr + 1] = cNext;  indices[ptr + 2] = sCurr;
            indices[ptr + 3] = cNext;  indices[ptr + 4] = sNext;  indices[ptr + 5] = sCurr;
        } else {
            indices[ptr + 0] = cCurr;  indices[ptr + 1] = sCurr;  indices[ptr + 2] = cNext;
            indices[ptr + 3] = cNext;  indices[ptr + 4] = sCurr;  indices[ptr + 5] = sNext;
        }
    }
}