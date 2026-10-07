import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.*;

public class Flora {

    // Helper class to return multiple distinct meshes from the generator
    public static class FloraBundle {
        public Mesh branchMesh;
        public Mesh leafMesh;
        // How tall the plant is and how far it spreads from its trunk (before it's scaled where it's
        // placed), and a few hundred of its points {x, y, z, ...} to find its outline
        public float height, spread;
        public float[] outline;
        
        public FloraBundle(Mesh branchMesh, Mesh leafMesh) {
            this.branchMesh = branchMesh;
            this.leafMesh = leafMesh;
        }
    }

    /**
     * Generates a fully baked, procedural plant with separated branch and leaf meshes.
     * UPDATED: Added leafScaleMultiplier to dynamically size leaves based on species config.
     */
    public static FloraBundle generateFloraBundle(GL3 gl, long seed, float branchingRate, float startWidth, float widthDecline, float stoppingDistance, float meanBranchAngle, int slices, float leafScaleMultiplier) {
        List<Float> branchVerts = new ArrayList<>();
        List<Integer> branchInds = new ArrayList<>();
        
        List<Float> leafVerts = new ArrayList<>();
        List<Integer> leafInds = new ArrayList<>();
        
        Random rand = new Random(seed);
        
        // Shared structural counter to prevent exponential explosion across all recursive paths
        int[] segmentCount = {0};
        
        // Start recursion at the origin, pointing straight up
        buildBranch(rand, new Vector3(0, 0, 0), new Matrix4(1), startWidth, 0f, 0, 
                    branchingRate, widthDecline, stoppingDistance, meanBranchAngle, slices, 
                    branchVerts, branchInds, leafVerts, leafInds, segmentCount, leafScaleMultiplier);
        
        Mesh bMesh = new Mesh(gl, toFloatArray(branchVerts), toIntArray(branchInds));
        Mesh lMesh = new Mesh(gl, toFloatArray(leafVerts), toIntArray(leafInds));
        
        FloraBundle bundle = new FloraBundle(bMesh, lMesh);
        List<Float> points = new ArrayList<>();
        int total = (branchVerts.size() + leafVerts.size()) / 8, every = Math.max(1, total / 500);
        int n = 0;
        for (List<Float> verts : List.of(branchVerts, leafVerts)) {
            for (int i = 0; i + 2 < verts.size(); i += 8, n++) {
                bundle.height = Math.max(bundle.height, verts.get(i + 1));
                bundle.spread = Math.max(bundle.spread, (float) Math.hypot(verts.get(i), verts.get(i + 2)));
                if (n % every == 0) {
                    points.add(verts.get(i));
                    points.add(verts.get(i + 1));
                    points.add(verts.get(i + 2));
                }
            }
        }
        bundle.outline = toFloatArray(points);
        return bundle;
    }

    private static void buildBranch(
            Random rand, 
            Vector3 pos, 
            Matrix4 rot, 
            float width, 
            float dist, 
            int depth,
            float branchingRate, 
            float widthDecline, 
            float stoppingDistance, 
            float meanBranchAngle, 
            int slices,
            List<Float> bVerts, 
            List<Integer> bInds,
            List<Float> lVerts,
            List<Integer> lInds,
            int[] segmentCount,
            float leafScaleMultiplier) {
        
        // SAFE & LOD-UNIFORM BOUNDARY CHECK:
        // Capping at 4000 total segments prevents memory crashes, while using structural counts 
        // instead of vertex array sizes ensures the tree layout evaluates identically across all LOD tiers.
        if (dist >= stoppingDistance || width < 0.05f || depth > 20 || segmentCount[0] >= 4000) {
            // We've reached a branch tip. Spawn leaves using our scale multiplier!
            spawnLeaves(rand, pos, rot, lVerts, lInds, leafScaleMultiplier);
            return;
        }
        
        // Increment structural segment counter
        segmentCount[0]++;
        
        // Variable segment length for natural jitter
        float length = 1.0f + (rand.nextFloat() * 0.8f); 
        
        // Build the transform for this specific branch segment cylinder
        Matrix4 localTransform = Matrix4.multiply(Matrix4Transform.scale(width, length, width), Matrix4Transform.translate(0f, 0.5f, 0f));
        Matrix4 modelMat = Matrix4Transform.translate(pos.x, pos.y, pos.z);
        modelMat = Matrix4.multiply(modelMat, rot);
        modelMat = Matrix4.multiply(modelMat, localTransform);
        
        int vertexOffset = bVerts.size() / 8; // 8 floats per vertex
        
        // Fetch custom template details for requested LOD slice tier
        float[] cylVerts = Cylinder.createVertices(slices);
        int[] cylInds = Cylinder.createIndices(slices);
        
        // Bake transformed vertices
        for (int i = 0; i < cylVerts.length; i += 8) {
            float[] posVec = multiply(modelMat, cylVerts[i], cylVerts[i+1], cylVerts[i+2]);
            float[] normVec = multiplyNormal(rot, cylVerts[i+3], cylVerts[i+4], cylVerts[i+5]);
            
            bVerts.add(posVec[0]); bVerts.add(posVec[1]); bVerts.add(posVec[2]); // Position
            bVerts.add(normVec[0]); bVerts.add(normVec[1]); bVerts.add(normVec[2]); // Normal
            bVerts.add(cylVerts[i+6]); bVerts.add(cylVerts[i+7]);                 // UVs
        }

        // Bake indices
        for (int i = 0; i < cylInds.length; i++) {
            bInds.add(vertexOffset + cylInds[i]);
        }
        
        // Calculate the starting position of the next segment at the end of this one
        float[] endOffset = multiplyNormal(rot, 0, length, 0);
        Vector3 nextPos = new Vector3(pos.x + endOffset[0], pos.y + endOffset[1], pos.z + endOffset[2]);
        
        // Slight organic curve variation for main stem tracking
        float mainTwist = (rand.nextFloat() * 10f) - 5f;
        float mainBend = (rand.nextFloat() * 6f) - 3f;
        Matrix4 mainRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(mainTwist));
        mainRot = Matrix4.multiply(mainRot, Matrix4Transform.rotateAroundZ(mainBend));
        
        // Determine branching splitting
        if (rand.nextFloat() < branchingRate) {
            float twistAngle = (rand.nextFloat() * 360f);
            float bendAngle = meanBranchAngle + (rand.nextFloat() * 15f - 7.5f);
            
            // Treat widthDecline as the amount to subtract (e.g., 0.1 decline = 90% thickness retained).
            float branchWidth = width * (1.0f - widthDecline);
            
            Matrix4 branchRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(twistAngle));
            branchRot = Matrix4.multiply(branchRot, Matrix4Transform.rotateAroundZ(bendAngle));
            
            // Scale the stopping distance down based on the parent's remaining length
            float remainingDistance = stoppingDistance - dist;
            float branchStoppingDist = Math.max(remainingDistance * 0.75f, 2.0f);
            
            // Branch off (note we pass 0f for the branch's starting dist)
            buildBranch(rand, nextPos, branchRot, branchWidth, 0f, depth + 1, 
                        branchingRate, widthDecline, branchStoppingDist, meanBranchAngle, slices, 
                        bVerts, bInds, lVerts, lInds, segmentCount, leafScaleMultiplier);
        }
        
        // Continue the main stem upward (using the existing distance counter)
        buildBranch(rand, nextPos, mainRot, width * 0.96f, dist + length, depth, 
                    branchingRate, widthDecline, stoppingDistance, meanBranchAngle, slices, 
                    bVerts, bInds, lVerts, lInds, segmentCount, leafScaleMultiplier);
    }

    private static void spawnLeaves(Random rand, Vector3 pos, Matrix4 rot, List<Float> verts, List<Integer> inds, float leafScaleMultiplier) {
        int numLeavesInCluster = 2 + rand.nextInt(3); // Spawn 2 to 4 leaves per tip
        
        for (int i = 0; i < numLeavesInCluster; i++) {
            // Randomize forward angle. Local Y is the forward direction.
            float twist = (rand.nextFloat() * 360f);
            float bend = 15f + rand.nextFloat() * 60f; // Bend outward from the stem
            
            Matrix4 leafRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(twist));
            leafRot = Matrix4.multiply(leafRot, Matrix4Transform.rotateAroundZ(bend));
            
            // UPDATED: Incorporate the leafScaleMultiplier to properly upscale trees relative to shrubs
            float scale = (0.8f + rand.nextFloat() * 0.7f) * leafScaleMultiplier; // Jitter size * Multiplier
            float w = 0.8f * scale; 
            float h = 2.0f * scale; 
            
            // Standard local quad positions (pivot at bottom center)
            float[][] positions = {
                {-w, 0, 0}, {w, 0, 0}, {-w, h, 0}, {w, h, 0}
            };
            
            // Front side UVs
            float[][] frontUVs = {
                {0, 0}, {1, 0}, {0, 1}, {1, 1}
            };
            
            // Back side UVs (Reflected horizontally to align seamlessly)
            float[][] backUVs = {
                {1, 0}, {0, 0}, {1, 1}, {0, 1}
            };
            
            int offset = verts.size() / 8;
            
            // --- FRONT PLANE ---
            float[] normFront = multiplyNormal(leafRot, 0, 0, 1);
            for (int v = 0; v < 4; v++) {
                float[] p = multiply(Matrix4.multiply(Matrix4Transform.translate(pos), leafRot), positions[v][0], positions[v][1], positions[v][2]);
                addVertex(verts, p[0], p[1], p[2], normFront[0], normFront[1], normFront[2], frontUVs[v][0], frontUVs[v][1]);
            }
            inds.add(offset + 0); inds.add(offset + 1); inds.add(offset + 2);
            inds.add(offset + 2); inds.add(offset + 1); inds.add(offset + 3);
            
            offset += 4;
            
            // --- BACK PLANE ---
            float[] normBack = multiplyNormal(leafRot, 0, 0, -1);
            for (int v = 0; v < 4; v++) {
                float[] p = multiply(Matrix4.multiply(Matrix4Transform.translate(pos), leafRot), positions[v][0], positions[v][1], positions[v][2]);
                addVertex(verts, p[0], p[1], p[2], normBack[0], normBack[1], normBack[2], backUVs[v][0], backUVs[v][1]);
            }
            // Reversed winding order so normals calculate correctly on the backface
            inds.add(offset + 0); inds.add(offset + 2); inds.add(offset + 1);
            inds.add(offset + 2); inds.add(offset + 3); inds.add(offset + 1);
        }
    }

    private static void addVertex(List<Float> verts, float px, float py, float pz, float nx, float ny, float nz, float u, float v) {
        verts.add(px); verts.add(py); verts.add(pz);
        verts.add(nx); verts.add(ny); verts.add(nz);
        verts.add(u); verts.add(v);
    }

    private static float[] multiply(Matrix4 mat, float x, float y, float z) {
        float[] m = mat.toFloatArrayForGLSL();
        float nx = m[0]*x + m[4]*y + m[8]*z + m[12];
        float ny = m[1]*x + m[5]*y + m[9]*z + m[13];
        float nz = m[2]*x + m[6]*y + m[10]*z + m[14];
        return new float[]{nx, ny, nz};
    }

    private static float[] multiplyNormal(Matrix4 rot, float x, float y, float z) {
        float[] m = rot.toFloatArrayForGLSL();
        float nx = m[0]*x + m[4]*y + m[8]*z;
        float ny = m[1]*x + m[5]*y + m[9]*z;
        float nz = m[2]*x + m[6]*y + m[10]*z;
        return new float[]{nx, ny, nz};
    }
    
    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
    
    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}