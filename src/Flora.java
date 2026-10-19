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

    // How much of the leaves each level of detail keeps (the rest dropped, those kept made a
    // little bigger to fill in): near, middling, far
    private static final float[] LEAVES_KEPT = { 1.0f, 0.75f, 0.5f };
    // Twigs on a branch's segment, and on the trunk's upper part: how likely
    private static final float TWIG_ON_BRANCH = 0.2f, TWIG_ON_TRUNK = 0.12f;

    /** A plant's geometry before it's put on the GPU: branch and leaf vertices (8 floats each) and triangles. */
    static final class Geometry {
        final List<Float> branchVerts = new ArrayList<>(), leafVerts = new ArrayList<>();
        final List<Integer> branchInds = new ArrayList<>(), leafInds = new ArrayList<>();
        // Which level of detail, and the separate stream deciding which leaves it drops (so every
        // level has the same tree: the main stream is drawn from identically at each)
        int lod;
        Random thinning;

        int triangles() {
            return (branchInds.size() + leafInds.size()) / 3;
        }
    }

    /**
     * Generates a fully baked, procedural plant with separated branch and leaf meshes, at a
     * level of detail (0 nearest; it sets the most slices round its thickest branches, and how
     * many of its leaves and twigs it keeps, see LEAVES_KEPT).
     */
    public static FloraBundle generateFloraBundle(GL3 gl, long seed, float branchingRate, float startWidth, float widthDecline, float stoppingDistance, float meanBranchAngle, int slices, float leafScaleMultiplier, int lod) {
        Geometry g = build(seed, branchingRate, startWidth, widthDecline, stoppingDistance, meanBranchAngle, slices, leafScaleMultiplier, lod);
        List<Float> branchVerts = g.branchVerts, leafVerts = g.leafVerts;
        Mesh bMesh = new Mesh(gl, toFloatArray(branchVerts), toIntArray(g.branchInds));
        Mesh lMesh = new Mesh(gl, toFloatArray(leafVerts), toIntArray(g.leafInds));

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

    /** A plant's geometry (see generateFloraBundle), without the GPU. */
    static Geometry build(long seed, float branchingRate, float startWidth, float widthDecline, float stoppingDistance, float meanBranchAngle, int slices, float leafScaleMultiplier, int lod) {
        Geometry g = new Geometry();
        g.lod = lod;
        g.thinning = new Random(seed ^ 0x5DEECE66DL);
        Random rand = new Random(seed);

        // Shared structural counter to prevent exponential explosion across all recursive paths
        int[] segmentCount = {0};

        // Start recursion at the origin, pointing straight up
        buildBranch(rand, new Vector3(0, 0, 0), new Matrix4(1), startWidth, 0f, 0,
                    branchingRate, widthDecline, stoppingDistance, meanBranchAngle, slices,
                    g, segmentCount, leafScaleMultiplier);
        return g;
    }

    /** Slices round a branch this wide: a thin one needs few to look round (at most the level's). */
    private static int slicesFor(float width, int most) {
        return Math.max(3, Math.min(most, Math.round(width * 8f)));
    }

    /**
     * A tube's sides (no ends: segments join end to end, so their ends are never seen) of a
     * cylinder of so many slices, baked through a model matrix into the branch geometry.
     */
    private static void tube(Geometry g, Matrix4 model, Matrix4 rot, int slices) {
        int offset = g.branchVerts.size() / 8;
        float[] cylVerts = Cylinder.createVertices(slices);
        int[] cylInds = Cylinder.createIndices(slices);
        // (the side walls come first in both: (slices + 1) * 2 vertices, slices * 6 indices)
        float[] m = model.toFloatArrayForGLSL(), r = rot.toFloatArrayForGLSL();
        for (int i = 0; i < (slices + 1) * 2 * 8; i += 8) {
            float x = cylVerts[i], y = cylVerts[i + 1], z = cylVerts[i + 2];
            float nx = cylVerts[i + 3], ny = cylVerts[i + 4], nz = cylVerts[i + 5];
            addVertex(g.branchVerts, m[0]*x + m[4]*y + m[8]*z + m[12], m[1]*x + m[5]*y + m[9]*z + m[13], m[2]*x + m[6]*y + m[10]*z + m[14],
                    r[0]*nx + r[4]*ny + r[8]*nz, r[1]*nx + r[5]*ny + r[9]*nz, r[2]*nx + r[6]*ny + r[10]*nz, cylVerts[i + 6], cylVerts[i + 7]);
        }
        for (int i = 0; i < slices * 6; i++) g.branchInds.add(offset + cylInds[i]);
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
            Geometry g,
            int[] segmentCount,
            float leafScaleMultiplier) {

        // SAFE & LOD-UNIFORM BOUNDARY CHECK:
        // Capping at 4000 total segments prevents memory crashes, while using structural counts
        // instead of vertex array sizes ensures the tree layout evaluates identically across all LOD tiers.
        if (dist >= stoppingDistance || width < 0.05f || depth > 20 || segmentCount[0] >= 4000) {
            // We've reached a branch tip. Spawn leaves using our scale multiplier!
            spawnLeaves(rand, pos, rot, g, leafScaleMultiplier);
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

        // Baked as a tube, with as many sides as its thickness needs
        tube(g, modelMat, rot, slicesFor(width, slices));

        // Calculate the starting position of the next segment at the end of this one
        float[] endOffset = multiplyNormal(rot, 0, length, 0);
        Vector3 nextPos = new Vector3(pos.x + endOffset[0], pos.y + endOffset[1], pos.z + endOffset[2]);

        // Twigs: short thin shoots off the branches (and the trunk's upper part), each ending in a
        // cluster of leaves, so the leaves fill the crown rather than only the branches' tips
        boolean leafy = depth >= 1 || dist > stoppingDistance * 0.45f;
        if (leafy && rand.nextFloat() < (depth >= 1 ? TWIG_ON_BRANCH : TWIG_ON_TRUNK)) {
            sprout(rand, nextPos, rot, Math.max(0.04f, width * 0.3f), g, segmentCount, leafScaleMultiplier);
        }

        // Slight organic curve variation for main stem tracking
        float mainTwist = (rand.nextFloat() * 10f) - 5f;
        float mainBend = (rand.nextFloat() * 6f) - 3f;
        Matrix4 mainRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(mainTwist));
        mainRot = Matrix4.multiply(mainRot, Matrix4Transform.rotateAroundZ(mainBend));

        // Determine branching splitting
        if (rand.nextFloat() < branchingRate) {
            float twistAngle = (rand.nextFloat() * 360f);
            // (about the species' own splay, some a good deal wider or narrower)
            float bendAngle = meanBranchAngle + (rand.nextFloat() * 30f - 15f);

            // Treat widthDecline as the amount to subtract (e.g., 0.1 decline = 90% thickness retained).
            float branchWidth = width * (1.0f - widthDecline);

            Matrix4 branchRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(twistAngle));
            branchRot = Matrix4.multiply(branchRot, Matrix4Transform.rotateAroundZ(bendAngle));

            // Scale the stopping distance down based on the parent's remaining length: from about
            // half to three quarters of it, so plenty of middling branches rather than all long limbs
            float remainingDistance = stoppingDistance - dist;
            float branchStoppingDist = Math.max(remainingDistance * (0.45f + rand.nextFloat() * 0.3f), 2.0f);

            // Branch off (note we pass 0f for the branch's starting dist)
            buildBranch(rand, nextPos, branchRot, branchWidth, 0f, depth + 1,
                        branchingRate, widthDecline, branchStoppingDist, meanBranchAngle, slices,
                        g, segmentCount, leafScaleMultiplier);
        }

        // Continue the main stem upward (using the existing distance counter)
        buildBranch(rand, nextPos, mainRot, width * 0.96f, dist + length, depth,
                    branchingRate, widthDecline, stoppingDistance, meanBranchAngle, slices,
                    g, segmentCount, leafScaleMultiplier);
    }

    /**
     * A twig from a point on a branch: one short thin segment angled out to the side (a few
     * sides only, as it's small; left out far off, its leaves still there), ending in a cluster
     * of leaves.
     */
    private static void sprout(Random rand, Vector3 pos, Matrix4 rot, float width, Geometry g, int[] segmentCount, float leafScaleMultiplier) {
        if (segmentCount[0] >= 4000) return;
        segmentCount[0]++;
        Matrix4 twigRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(rand.nextFloat() * 360f));
        twigRot = Matrix4.multiply(twigRot, Matrix4Transform.rotateAroundZ(45f + rand.nextFloat() * 35f));
        float length = (0.5f + rand.nextFloat() * 0.7f) * Math.max(1f, leafScaleMultiplier * 0.6f);
        Matrix4 model = Matrix4.multiply(Matrix4.multiply(Matrix4Transform.translate(pos.x, pos.y, pos.z), twigRot),
                Matrix4.multiply(Matrix4Transform.scale(width, length, width), Matrix4Transform.translate(0f, 0.5f, 0f)));
        if (g.lod < 2) tube(g, model, twigRot, 3);
        float[] end = multiplyNormal(twigRot, 0, length, 0);
        spawnLeaves(rand, new Vector3(pos.x + end[0], pos.y + end[1], pos.z + end[2]), twigRot, g, leafScaleMultiplier);
    }

    private static void spawnLeaves(Random rand, Vector3 pos, Matrix4 rot, Geometry g, float leafScaleMultiplier) {
        int numLeavesInCluster = 2 + rand.nextInt(3); // 2 to 4 leaves a cluster
        float kept = LEAVES_KEPT[g.lod];
        // (further off, those kept a little bigger, standing in for the ones dropped)
        float grow = (float) Math.pow(1.0 / kept, 0.35);

        for (int i = 0; i < numLeavesInCluster; i++) {
            // Randomize forward angle. Local Y is the forward direction.
            float twist = (rand.nextFloat() * 360f);
            float bend = 15f + rand.nextFloat() * 60f; // Bend outward from the stem
            float scale = (0.8f + rand.nextFloat() * 0.7f) * leafScaleMultiplier; // Jitter size * Multiplier
            if (g.thinning.nextFloat() >= kept) continue;
            scale *= grow;

            Matrix4 leafRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(twist));
            leafRot = Matrix4.multiply(leafRot, Matrix4Transform.rotateAroundZ(bend));
            float w = 0.8f * scale;
            float h = 2.0f * scale;

            // Standard local quad positions (pivot at bottom center), one face only: plants are
            // drawn without culling, and the leaf shader turns the normal to whichever side is seen
            float[][] positions = {
                {-w, 0, 0}, {w, 0, 0}, {-w, h, 0}, {w, h, 0}
            };
            float[][] uvs = {
                {0, 0}, {1, 0}, {0, 1}, {1, 1}
            };

            int offset = g.leafVerts.size() / 8;
            Matrix4 place = Matrix4.multiply(Matrix4Transform.translate(pos), leafRot);
            float[] normal = multiplyNormal(leafRot, 0, 0, 1);
            for (int v = 0; v < 4; v++) {
                float[] p = multiply(place, positions[v][0], positions[v][1], positions[v][2]);
                addVertex(g.leafVerts, p[0], p[1], p[2], normal[0], normal[1], normal[2], uvs[v][0], uvs[v][1]);
            }
            g.leafInds.add(offset + 0); g.leafInds.add(offset + 1); g.leafInds.add(offset + 2);
            g.leafInds.add(offset + 2); g.leafInds.add(offset + 1); g.leafInds.add(offset + 3);
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
