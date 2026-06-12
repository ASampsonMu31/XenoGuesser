import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.jogamp.opengl.GL3;
import gmaths.*;

public class Flora {

    /**
     * Generates a fully baked, fractal-like procedural plant mesh.
     */
    public static Mesh generateFloraMesh(GL3 gl, long seed, float branchingRate, float startWidth, float widthDecline, float stoppingDistance, float meanBranchAngle) {
        List<Float> verts = new ArrayList<>();
        List<Integer> inds = new ArrayList<>();
        Random rand = new Random(seed);
        
        // Start recursion at the origin, pointing straight up
        buildBranch(rand, new Vec3(0, 0, 0), new Mat4(1), startWidth, 0f, 0, 
                    branchingRate, widthDecline, stoppingDistance, meanBranchAngle, verts, inds);
        
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
            List<Float> verts, 
            List<Integer> inds) {
        
        // Safety base cases to prevent heap overflow or infinite recursion
        if (dist >= stoppingDistance || width < 0.05f || depth > 20 || verts.size() > 300000) {
            return;
        }
        
        // Variable segment length for natural jitter
        float length = 1.0f + (rand.nextFloat() * 0.8f); 
        
        // Build the transform for this specific branch segment cylinder
        // 1. Scale cylinder to correct width and length
        // 2. Translate up by 0.5 so its base sits exactly at 0.0 instead of -0.5
        Mat4 localTransform = Mat4.multiply(Mat4Transform.scale(width, length, width), Mat4Transform.translate(0f, 0.5f, 0f));
        // Apply branch rotation and world position
        Mat4 modelMat = Mat4Transform.translate(pos.x, pos.y, pos.z);
        modelMat = Mat4.multiply(modelMat, rot);
        modelMat = Mat4.multiply(modelMat, localTransform);
        
        int vertexOffset = verts.size() / 8; // 8 floats per vertex in the Cylinder class
        
        // Bake transformed vertices
        for (int i = 0; i < Cylinder.vertices.length; i += 8) {
            float vx = Cylinder.vertices[i];
            float vy = Cylinder.vertices[i+1];
            float vz = Cylinder.vertices[i+2];
            float nx = Cylinder.vertices[i+3];
            float ny = Cylinder.vertices[i+4];
            float nz = Cylinder.vertices[i+5];
            float u = Cylinder.vertices[i+6];
            float v = Cylinder.vertices[i+7];
            
            float[] wp = multiply(modelMat, vx, vy, vz);
            float[] wn = multiplyNormal(rot, nx, ny, nz);
            
            verts.add(wp[0]); verts.add(wp[1]); verts.add(wp[2]);
            verts.add(wn[0]); verts.add(wn[1]); verts.add(wn[2]);
            verts.add(u); verts.add(v);
        }
        
        // Bake shifted indices
        for (int i = 0; i < Cylinder.indices.length; i++) {
            inds.add(vertexOffset + Cylinder.indices[i]);
        }
        
        // Calculate the starting position of the *next* segment at the end of this one
        float[] endOffset = multiplyNormal(rot, 0, length, 0);
        Vec3 nextPos = new Vec3(pos.x + endOffset[0], pos.y + endOffset[1], pos.z + endOffset[2]);
        
        // Apply a slight organic continuous bend to the main trunk path
        float mainBend = (rand.nextFloat() - 0.5f) * 15.0f;
        float mainTwist = rand.nextFloat() * 360.0f;
        Mat4 mainRot = Mat4.multiply(rot, Mat4Transform.rotateAroundY(mainTwist));
        mainRot = Mat4.multiply(mainRot, Mat4Transform.rotateAroundZ(mainBend));
        
        // Check for branching event (Poisson-style)
        if (rand.nextFloat() < branchingRate) {
            float branchWidthDecline = widthDecline + (float)rand.nextGaussian() * 0.05f;
            float branchWidth = width * (1.0f - Math.max(0.05f, Math.min(0.9f, branchWidthDecline)));
            
            float bendAngle = meanBranchAngle + (float)rand.nextGaussian() * 10.0f;
            float twistAngle = rand.nextFloat() * 360.0f;
            
            Mat4 branchRot = Mat4.multiply(rot, Mat4Transform.rotateAroundY(twistAngle));
            branchRot = Mat4.multiply(branchRot, Mat4Transform.rotateAroundZ(bendAngle));
            
            buildBranch(rand, nextPos, branchRot, branchWidth, dist + length, depth + 1, 
                        branchingRate, widthDecline, stoppingDistance, meanBranchAngle, verts, inds);
        }
        
        // Continue the main stem upward
        buildBranch(rand, nextPos, mainRot, width * 0.96f, dist + length, depth, 
                    branchingRate, widthDecline, stoppingDistance, meanBranchAngle, verts, inds);
    }

    // Helper to safely apply Mat4 to local coordinates without relying on external Vector objects
    private static float[] multiply(Mat4 mat, float x, float y, float z) {
        float[] m = mat.toFloatArrayForGLSL(); // Column-major indexing
        float nx = m[0]*x + m[4]*y + m[8]*z + m[12];
        float ny = m[1]*x + m[5]*y + m[9]*z + m[13];
        float nz = m[2]*x + m[6]*y + m[10]*z + m[14];
        return new float[]{nx, ny, nz};
    }

    // Helper for Normals (ignores translation values)
    private static float[] multiplyNormal(Mat4 rot, float x, float y, float z) {
        float[] m = rot.toFloatArrayForGLSL(); 
        float nx = m[0]*x + m[4]*y + m[8]*z;
        float ny = m[1]*x + m[5]*y + m[9]*z;
        float nz = m[2]*x + m[6]*y + m[10]*z;
        return new float[]{nx, ny, nz};
    }
}