import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.jogamp.opengl.GL3;
import gmaths.*;

public class Flora {

    /**
     * Generates a fully baked, fractal-like procedural plant mesh with dynamic detail settings.
     */
    public static Mesh generateFloraMesh(GL3 gl, long seed, float branchingRate, float startWidth, float widthDecline, float stoppingDistance, float meanBranchAngle, int slices) {
        List<Float> verts = new ArrayList<>();
        List<Integer> inds = new ArrayList<>();
        Random rand = new Random(seed);
        
        // Start recursion at the origin, pointing straight up
        buildBranch(rand, new Vec3(0, 0, 0), new Mat4(1), startWidth, 0f, 0, 
                    branchingRate, widthDecline, stoppingDistance, meanBranchAngle, slices, verts, inds);
        
        float[] vArr = new float[verts.size()];
        for(int i = 0; i < verts.size(); i++) vArr[i] = verts.get(i);
        
        int[] iArr = new int[inds.size()];
        for(int i = 0; i < inds.size(); i++) iArr[i] = inds.get(i);
        
        return new Mesh(gl, vArr, iArr);
    }

    private static void buildBranch(
            Random rand, 
            Vec3 pos, 
            Mat4 rot, 
            float width, 
            float dist, 
            int depth,
            float branchingRate, 
            float widthDecline, 
            float stoppingDistance, 
            float meanBranchAngle, 
            int slices,
            List<Float> verts, 
            List<Integer> inds) {
        
        // Safety base cases to prevent heap overflow or infinite recursion
        if (dist >= stoppingDistance || width < 0.05f || depth > 20 || verts.size() > 300000) {
            return;
        }
        
        // Variable segment length for natural jitter
        float length = 1.0f + (rand.nextFloat() * 0.8f); 
        
        // Build the transform for this specific branch segment cylinder
        Mat4 localTransform = Mat4.multiply(Mat4Transform.scale(width, length, width), Mat4Transform.translate(0f, 0.5f, 0f));
        Mat4 modelMat = Mat4Transform.translate(pos.x, pos.y, pos.z);
        modelMat = Mat4.multiply(modelMat, rot);
        modelMat = Mat4.multiply(modelMat, localTransform);
        
        int vertexOffset = verts.size() / 8; // 8 floats per vertex
        
        // Fetch custom template details for requested LOD slice tier
        float[] cylVerts = Cylinder.createVertices(slices);
        int[] cylInds = Cylinder.createIndices(slices);
        
        // Bake transformed vertices
        for (int i = 0; i < cylVerts.length; i += 8) {
            float[] posVec = multiply(modelMat, cylVerts[i], cylVerts[i+1], cylVerts[i+2]);
            float[] normVec = multiplyNormal(rot, cylVerts[i+3], cylVerts[i+4], cylVerts[i+5]);
            
            verts.add(posVec[0]); verts.add(posVec[1]); verts.add(posVec[2]); // Position
            verts.add(normVec[0]); verts.add(normVec[1]); verts.add(normVec[2]); // Normal
            verts.add(cylVerts[i+6]); verts.add(cylVerts[i+7]);                 // UVs
        }

        // Bake indices
        for (int i = 0; i < cylInds.length; i++) {
            inds.add(vertexOffset + cylInds[i]);
        }
        
        // Calculate the starting position of the next segment at the end of this one
        float[] endOffset = multiplyNormal(rot, 0, length, 0);
        Vec3 nextPos = new Vec3(pos.x + endOffset[0], pos.y + endOffset[1], pos.z + endOffset[2]);
        
        // Slight organic curve variation for main stem tracking
        float mainTwist = (rand.nextFloat() * 10f) - 5f;
        float mainBend = (rand.nextFloat() * 6f) - 3f;
        Mat4 mainRot = Mat4.multiply(rot, Mat4Transform.rotateAroundY(mainTwist));
        mainRot = Mat4.multiply(mainRot, Mat4Transform.rotateAroundZ(mainBend));
        
        // Determine branching splitting
        if (rand.nextFloat() < branchingRate) {
            float twistAngle = (rand.nextFloat() * 360f);
            float bendAngle = meanBranchAngle + (rand.nextFloat() * 15f - 7.5f);
            float branchWidth = width * widthDecline;
            
            Mat4 branchRot = Mat4.multiply(rot, Mat4Transform.rotateAroundY(twistAngle));
            branchRot = Mat4.multiply(branchRot, Mat4Transform.rotateAroundZ(bendAngle));
            
            buildBranch(rand, nextPos, branchRot, branchWidth, dist + length, depth + 1, 
                        branchingRate, widthDecline, stoppingDistance, meanBranchAngle, slices, verts, inds);
        }
        
        // Continue the main stem upward
        buildBranch(rand, nextPos, mainRot, width * 0.96f, dist + length, depth, 
                    branchingRate, widthDecline, stoppingDistance, meanBranchAngle, slices, verts, inds);
    }

    private static float[] multiply(Mat4 mat, float x, float y, float z) {
        float[] m = mat.toFloatArrayForGLSL();
        float nx = m[0]*x + m[4]*y + m[8]*z + m[12];
        float ny = m[1]*x + m[5]*y + m[9]*z + m[13];
        float nz = m[2]*x + m[6]*y + m[10]*z + m[14];
        return new float[]{nx, ny, nz};
    }

    private static float[] multiplyNormal(Mat4 rot, float x, float y, float z) {
        float[] m = rot.toFloatArrayForGLSL();
        float nx = m[0]*x + m[4]*y + m[8]*z;
        float ny = m[1]*x + m[5]*y + m[9]*z;
        float nz = m[2]*x + m[6]*y + m[10]*z;
        return new float[]{nx, ny, nz};
    }
}