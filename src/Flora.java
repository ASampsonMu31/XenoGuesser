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
    static final float[] LEAVES_KEPT = { 1.0f, 0.75f, 0.5f };
    // ...and of the spikes and the orbs: all of them at every level (dropped, they'd be seen popping in as the player comes near)
    static final float[] SPIKES_KEPT = { 1.0f, 1.0f, 1.0f };
    static final float[] ORBS_KEPT = { 1.0f, 1.0f, 1.0f };
    // Twigs on a branch's segment, and on the trunk's upper part: how likely
    private static final float TWIG_ON_BRANCH = 0.2f, TWIG_ON_TRUNK = 0.12f;

    // The parts of a plant's body, each drawn its own way (see fs_flora): bark, the species' own
    // patterned skin, glossy orbs, spikes and pale flesh
    static final int BARK = 0, SKIN = 1, ORB = 2, SPIKE = 3, FLESH = 4;

    /**
     * What a species looks like: its form, how big it grows, and what grows on it. The same for
     * all of a species' variations, each of which is drawn from it with its own seed.
     */
    public static final class Design {
        public enum Form {
            // A branching tree (or bush), a mushroom (a cluster of them as a shrub), a cactus (a
            // barrel or a heap of pads as a shrub), a weeping tree hung with strands, an orb tree
            // (stalks ending in balls) and a palm (a fern as a shrub)
            TREE, MUSHROOM, CACTUS, WEEPING, ORBS, PALM
        }

        public Form form = Form.TREE;
        public boolean shrub;
        // The branching (TREE and WEEPING): as the plain trees' were
        public float branchingRate, startWidth, widthDecline, stoppingDistance, meanBranchAngle;
        public float leafScale = 1f;
        // How tall it grows and how wide its main stem is (units; every other form)
        public float height, width;
        // What grows on it: leaves; spikes (0 none, up to 1 thickly); orbs (0 none, up to 1 many)
        // and how big; hanging strands (vines, or a weeping tree's curtain: how many)
        public boolean leaves = true;
        public float spikes, orbs, orbSize = 1f;
        public int strands;
        // Its trunk and branches as patterned skin rather than bark
        public boolean skin;
        // Cacti: the ribs down them and how deep the grooves between; mushrooms: the cap's shape
        // (0 dome, 1 parasol, 2 cone, 3 funnel) and whether a frill hangs from the stalk
        public int ribs;
        public float ribDepth;
        public int capShape;
        public boolean frill;
        // How much its branches bend down as they grow (degrees a segment: weeping trees)
        public float droop;
        // Cactus shrubs: pads rather than barrels
        public boolean pads;

        public Design copy() {
            Design d = new Design();
            d.form = form; d.shrub = shrub;
            d.branchingRate = branchingRate; d.startWidth = startWidth; d.widthDecline = widthDecline;
            d.stoppingDistance = stoppingDistance; d.meanBranchAngle = meanBranchAngle; d.leafScale = leafScale;
            d.height = height; d.width = width; d.leaves = leaves; d.spikes = spikes; d.orbs = orbs;
            d.orbSize = orbSize; d.strands = strands; d.skin = skin; d.ribs = ribs; d.ribDepth = ribDepth;
            d.capShape = capShape; d.frill = frill; d.droop = droop; d.pads = pads;
            return d;
        }

        /** What it's called on the map: its kind of plant. */
        public String kind() {
            return switch (form) {
                case TREE -> shrub ? "Shrub" : "Tree";
                case MUSHROOM -> shrub ? "Mushrooms" : "Giant mushroom";
                case CACTUS -> shrub ? "Cactus" : "Giant cactus";
                case WEEPING -> shrub ? "Weeping shrub" : "Weeping tree";
                case ORBS -> shrub ? "Orb bush" : "Orb tree";
                case PALM -> shrub ? "Fern" : "Palm";
            };
        }

        /**
         * A species of this form: how big it grows and what grows on it, drawn from r. The
         * branching (for TREE and WEEPING) is set apart from this.
         */
        public static Design of(Form form, boolean shrub, Random r) {
            Design d = new Design();
            d.form = form;
            d.shrub = shrub;
            float big = shrub ? 1f : 0f;
            d.skin = r.nextFloat() < 0.35f;
            d.spikes = r.nextFloat() < 0.25f ? 0.3f + r.nextFloat() * 0.5f : 0f;
            d.orbs = r.nextFloat() < 0.3f ? 0.3f + r.nextFloat() * 0.6f : 0f;
            d.strands = !shrub && r.nextFloat() < 0.3f ? 6 + r.nextInt(12) : 0;
            d.ribs = 6 + r.nextInt(9);
            d.ribDepth = 0.06f + r.nextFloat() * 0.16f;
            d.capShape = r.nextInt(4);
            d.frill = r.nextFloat() < 0.4f;
            d.pads = r.nextFloat() < 0.5f;
            switch (form) {
                case MUSHROOM -> {
                    d.height = shrub ? 9f + r.nextFloat() * 9f : 60f + r.nextFloat() * 80f;
                    d.width = d.height * (0.06f + r.nextFloat() * 0.06f);
                    d.leaves = r.nextFloat() < 0.3f;
                    d.skin = true;
                    d.strands = 0;
                }
                case CACTUS -> {
                    d.height = shrub ? 6f + r.nextFloat() * 9f : 45f + r.nextFloat() * 65f;
                    d.width = d.height * (shrub ? 0.6f + r.nextFloat() * 0.3f : 0.12f + r.nextFloat() * 0.08f);
                    d.leaves = r.nextFloat() < 0.35f;
                    d.spikes = 0.5f + r.nextFloat() * 0.5f;
                    d.skin = true;
                    d.strands = 0;
                }
                case WEEPING -> {
                    d.droop = 4f + r.nextFloat() * 5f;
                    d.strands = shrub ? 0 : 30 + r.nextInt(30);
                }
                case ORBS -> {
                    d.height = shrub ? 8f + r.nextFloat() * 10f : 50f + r.nextFloat() * 70f;
                    d.width = d.height * (0.04f + r.nextFloat() * 0.04f) * (1f + big);
                    d.orbs = 1f;
                    d.leaves = r.nextFloat() < 0.5f;
                    d.strands = 0;
                }
                case PALM -> {
                    d.height = shrub ? 8f + r.nextFloat() * 8f : 70f + r.nextFloat() * 70f;
                    d.width = shrub ? 0.4f : d.height * (0.035f + r.nextFloat() * 0.03f);
                    d.leaves = true;
                    d.strands = 0;
                    d.skin = r.nextFloat() < 0.6f;
                }
                default -> { }
            }
            d.orbSize = 0.7f + r.nextFloat() * 0.8f;
            return d;
        }
    }

    /** A plant's geometry before it's put on the GPU: branch and leaf vertices (8 floats each) and triangles. */
    static final class Geometry {
        final List<Float> branchVerts = new ArrayList<>(), leafVerts = new ArrayList<>();
        final List<Integer> branchInds = new ArrayList<>(), leafInds = new ArrayList<>();
        // Which level of detail, and the separate stream deciding which leaves (and spikes and
        // orbs) it drops (so every level has the same plant: the main stream is drawn from
        // identically at each)
        int lod;
        Random thinning;
        // Whether it keeps all its leaves at every level of detail (the forms with only a few: the
        // branching trees, with thousands, drop some further off)
        boolean allLeaves;

        /** The share of its leaves it keeps at its level of detail. */
        float leavesKept() {
            return allLeaves ? 1f : LEAVES_KEPT[lod];
        }

        int triangles() {
            return (branchInds.size() + leafInds.size()) / 3;
        }
    }

    /**
     * Generates a fully baked, procedural plant with separated branch and leaf meshes, at a
     * level of detail (0 nearest; it sets the most slices round its thickest parts, and how many
     * of its leaves, spikes and twigs it keeps, see LEAVES_KEPT).
     */
    public static FloraBundle generateFloraBundle(GL3 gl, long seed, Design design, int slices, int lod) {
        Geometry g = build(seed, design, slices, lod);
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
    static Geometry build(long seed, Design design, int slices, int lod) {
        Geometry g = new Geometry();
        g.lod = lod;
        g.thinning = new Random(seed ^ 0x5DEECE66DL);
        g.allLeaves = design.form != Design.Form.TREE && design.form != Design.Form.WEEPING;
        Random rand = new Random(seed);
        switch (design.form) {
            case MUSHROOM -> FloraForms.mushrooms(g, rand, design, slices);
            case CACTUS -> FloraForms.cactus(g, rand, design, slices);
            case ORBS -> FloraForms.orbs(g, rand, design, slices);
            case PALM -> FloraForms.palm(g, rand, design, slices);
            default -> tree(g, rand, design, slices);
        }
        return g;
    }

    /** What a branching plant's growth needs all the way up: the plant, its design and what it's made. */
    private static final class Growth {
        final Random rand;
        final Geometry g;
        final Design d;
        final int slices;
        // Shared structural counter to prevent exponential explosion across all recursive paths
        int segments;
        // Points along the branches (not the trunk) strands can hang from, in the order grown
        final List<float[]> anchors = new ArrayList<>();
        // Below this nothing grows on (a weeping tree's branches end before they reach the ground)
        float floor;

        Growth(Random rand, Geometry g, Design d, int slices) {
            this.rand = rand;
            this.g = g;
            this.d = d;
            this.slices = slices;
        }
    }

    /** A branching tree or bush (and a weeping one, its branches bending down, hung with strands). */
    private static void tree(Geometry g, Random rand, Design d, int slices) {
        Growth growth = new Growth(rand, g, d, slices);
        growth.floor = d.droop > 0f ? d.stoppingDistance * 0.12f : -1e9f;
        // Start recursion at the origin, pointing straight up
        buildBranch(growth, new Vector3(0, 0, 0), new Matrix4(1), d.startWidth, 0f, 0, d.stoppingDistance);
        if (d.strands > 0 && !growth.anchors.isEmpty()) {
            FloraForms.strands(g, rand, d, growth.anchors, d.strands, Math.max(0.12f, d.startWidth * 0.08f), d.skin ? SKIN : BARK);
        }
    }

    /** Slices round a branch this wide: a thin one needs few to look round (at most the level's). */
    private static int slicesFor(float width, int most) {
        return Math.max(3, Math.min(most, Math.round(width * 8f)));
    }

    /**
     * A tube's sides (no ends: segments join end to end, so their ends are never seen) of a
     * cylinder of so many slices, baked through a model matrix into the branch geometry: of a
     * part of the body (see BARK), skin marked from v0 along, dv over its length.
     */
    private static void tube(Geometry g, Matrix4 model, Matrix4 rot, int slices, int part, float v0, float dv) {
        int offset = g.branchVerts.size() / 8;
        float[] cylVerts = Cylinder.createVertices(slices);
        int[] cylInds = Cylinder.createIndices(slices);
        // (the side walls come first in both: (slices + 1) * 2 vertices, slices * 6 indices)
        float[] m = model.toFloatArrayForGLSL(), r = rot.toFloatArrayForGLSL();
        for (int i = 0; i < (slices + 1) * 2 * 8; i += 8) {
            float x = cylVerts[i], y = cylVerts[i + 1], z = cylVerts[i + 2];
            float nx = cylVerts[i + 3], ny = cylVerts[i + 4], nz = cylVerts[i + 5];
            float v = part == BARK ? cylVerts[i + 7] : v0 + cylVerts[i + 7] * dv;
            addVertex(g.branchVerts, m[0]*x + m[4]*y + m[8]*z + m[12], m[1]*x + m[5]*y + m[9]*z + m[13], m[2]*x + m[6]*y + m[10]*z + m[14],
                    r[0]*nx + r[4]*ny + r[8]*nz, r[1]*nx + r[5]*ny + r[9]*nz, r[2]*nx + r[6]*ny + r[10]*nz, cylVerts[i + 6] + 2 * part, v);
        }
        for (int i = 0; i < slices * 6; i++) g.branchInds.add(offset + cylInds[i]);
    }

    private static void buildBranch(Growth growth, Vector3 pos, Matrix4 rot, float width, float dist, int depth, float stoppingDistance) {
        Random rand = growth.rand;
        Geometry g = growth.g;
        Design d = growth.d;

        // SAFE & LOD-UNIFORM BOUNDARY CHECK:
        // Capping at 4000 total segments prevents memory crashes, while using structural counts
        // instead of vertex array sizes ensures the tree layout evaluates identically across all LOD tiers.
        if (dist >= stoppingDistance || width < 0.05f || depth > 20 || growth.segments >= 4000 || (depth >= 1 && pos.y < growth.floor)) {
            tip(growth, pos, rot, width);
            return;
        }

        // Increment structural segment counter
        growth.segments++;

        // Variable segment length for natural jitter
        float length = 1.0f + (rand.nextFloat() * 0.8f);

        // Build the transform for this specific branch segment cylinder
        Matrix4 localTransform = Matrix4.multiply(Matrix4Transform.scale(width, length, width), Matrix4Transform.translate(0f, 0.5f, 0f));
        Matrix4 modelMat = Matrix4Transform.translate(pos.x, pos.y, pos.z);
        modelMat = Matrix4.multiply(modelMat, rot);
        modelMat = Matrix4.multiply(modelMat, localTransform);

        // Baked as a tube, with as many sides as its thickness needs
        float around = (float) Math.PI * Math.max(width, 0.05f);
        tube(g, modelMat, rot, slicesFor(width, growth.slices), d.skin ? SKIN : BARK, dist / around, length / around);

        // Calculate the starting position of the next segment at the end of this one
        float[] endOffset = multiplyNormal(rot, 0, length, 0);
        Vector3 nextPos = new Vector3(pos.x + endOffset[0], pos.y + endOffset[1], pos.z + endOffset[2]);
        if (depth >= 1) growth.anchors.add(new float[] { nextPos.x, nextPos.y, nextPos.z });

        // Thorns: a spike or two standing out from the segment
        if (d.spikes > 0f && rand.nextFloat() < d.spikes * 0.6f) {
            int thorns = 1 + rand.nextInt(2);
            for (int t = 0; t < thorns; t++) {
                float along = rand.nextFloat() * length, turn = rand.nextFloat() * 6.2832f;
                float[] side = multiplyNormal(rot, (float) Math.cos(turn), 0.25f, (float) Math.sin(turn));
                float[] at = multiplyNormal(rot, 0, along, 0);
                float[] out = normalise(side);
                float[] root = { pos.x + at[0] + out[0] * width * 0.45f, pos.y + at[1] + out[1] * width * 0.45f, pos.z + at[2] + out[2] * width * 0.45f };
                spike(g, root, out, Math.max(0.35f, width * 0.9f), Math.max(0.08f, width * 0.15f));
            }
        }

        // Twigs: short thin shoots off the branches (and the trunk's upper part), each ending in a
        // cluster of leaves, so the leaves fill the crown rather than only the branches' tips
        boolean leafy = depth >= 1 || dist > stoppingDistance * 0.45f;
        if (leafy && rand.nextFloat() < (depth >= 1 ? TWIG_ON_BRANCH : TWIG_ON_TRUNK)) {
            sprout(growth, nextPos, rot, Math.max(0.04f, width * 0.3f));
        }

        // Slight organic curve variation for main stem tracking
        float mainTwist = (rand.nextFloat() * 10f) - 5f;
        float mainBend = (rand.nextFloat() * 6f) - 3f;
        Matrix4 mainRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(mainTwist));
        mainRot = Matrix4.multiply(mainRot, Matrix4Transform.rotateAroundZ(mainBend));
        // (a weeping tree's branches bend down a little more each segment)
        if (d.droop > 0f && depth >= 1) mainRot = drooped(mainRot, d.droop);

        // Determine branching splitting
        if (rand.nextFloat() < d.branchingRate) {
            float twistAngle = (rand.nextFloat() * 360f);
            // (about the species' own splay, some a good deal wider or narrower)
            float bendAngle = d.meanBranchAngle + (rand.nextFloat() * 30f - 15f);

            // Treat widthDecline as the amount to subtract (e.g., 0.1 decline = 90% thickness retained).
            float branchWidth = width * (1.0f - d.widthDecline);

            Matrix4 branchRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(twistAngle));
            branchRot = Matrix4.multiply(branchRot, Matrix4Transform.rotateAroundZ(bendAngle));

            // Scale the stopping distance down based on the parent's remaining length: from about
            // half to three quarters of it, so plenty of middling branches rather than all long limbs
            float remainingDistance = stoppingDistance - dist;
            float branchStoppingDist = Math.max(remainingDistance * (0.45f + rand.nextFloat() * 0.3f), 2.0f);

            // Branch off (note we pass 0f for the branch's starting dist)
            buildBranch(growth, nextPos, branchRot, branchWidth, 0f, depth + 1, branchStoppingDist);
        }

        // Continue the main stem upward (using the existing distance counter)
        buildBranch(growth, nextPos, mainRot, width * 0.96f, dist + length, depth, stoppingDistance);
    }

    /** A branch's end: its leaves, and (if it bears them) now and then an orb. */
    private static void tip(Growth growth, Vector3 pos, Matrix4 rot, float width) {
        Design d = growth.d;
        boolean orb = d.orbs > 0f && growth.rand.nextFloat() < d.orbs * 0.5f;
        if (d.leaves || !orb) spawnLeaves(growth.rand, pos, rot, growth.g, d.leafScale);
        if (orb) {
            float r = d.orbSize * (d.shrub ? 0.35f : 1.1f) * (0.8f + growth.rand.nextFloat() * 0.4f);
            float[] up = multiplyNormal(rot, 0, 1, 0);
            orb(growth.g, new float[] { pos.x + up[0] * r * 0.7f, pos.y + up[1] * r * 0.7f, pos.z + up[2] * r * 0.7f }, r, orbSides(growth.g, growth.slices));
        }
    }

    /**
     * A twig from a point on a branch: one short thin segment angled out to the side (a few
     * sides only, as it's small; left out far off, its leaves still there), ending in a cluster
     * of leaves.
     */
    private static void sprout(Growth growth, Vector3 pos, Matrix4 rot, float width) {
        if (growth.segments >= 4000) return;
        growth.segments++;
        Random rand = growth.rand;
        Geometry g = growth.g;
        float leafScaleMultiplier = growth.d.leafScale;
        Matrix4 twigRot = Matrix4.multiply(rot, Matrix4Transform.rotateAroundY(rand.nextFloat() * 360f));
        twigRot = Matrix4.multiply(twigRot, Matrix4Transform.rotateAroundZ(45f + rand.nextFloat() * 35f));
        float length = (0.5f + rand.nextFloat() * 0.7f) * Math.max(1f, leafScaleMultiplier * 0.6f);
        Matrix4 model = Matrix4.multiply(Matrix4.multiply(Matrix4Transform.translate(pos.x, pos.y, pos.z), twigRot),
                Matrix4.multiply(Matrix4Transform.scale(width, length, width), Matrix4Transform.translate(0f, 0.5f, 0f)));
        if (g.lod < 2) tube(g, model, twigRot, 3, growth.d.skin ? SKIN : BARK, 0f, 1f);
        float[] end = multiplyNormal(twigRot, 0, length, 0);
        tip(growth, new Vector3(pos.x + end[0], pos.y + end[1], pos.z + end[2]), twigRot, width);
    }

    static void spawnLeaves(Random rand, Vector3 pos, Matrix4 rot, Geometry g, float leafScaleMultiplier) {
        int numLeavesInCluster = 2 + rand.nextInt(3); // 2 to 4 leaves a cluster
        float kept = g.leavesKept();
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
            float[] y = multiplyNormal(leafRot, 0, 1, 0), z = multiplyNormal(leafRot, 0, 0, 1);
            leafCard(g, new float[] { pos.x, pos.y, pos.z }, y, z, 0.8f * scale, 2.0f * scale);
        }
    }

    /**
     * One leaf: a card pivoted at the middle of its bottom edge at pos, running along y, facing
     * along face, w either side and h long. One face only: plants are drawn without culling, and
     * the leaf shader turns the normal to whichever side is seen.
     */
    static void leafCard(Geometry g, float[] pos, float[] y, float[] face, float w, float h) {
        float[] x = normalise(cross(y, face));
        float[] n = normalise(face);
        float[][] positions = {
            { pos[0] - x[0] * w, pos[1] - x[1] * w, pos[2] - x[2] * w },
            { pos[0] + x[0] * w, pos[1] + x[1] * w, pos[2] + x[2] * w },
            { pos[0] - x[0] * w + y[0] * h, pos[1] - x[1] * w + y[1] * h, pos[2] - x[2] * w + y[2] * h },
            { pos[0] + x[0] * w + y[0] * h, pos[1] + x[1] * w + y[1] * h, pos[2] + x[2] * w + y[2] * h },
        };
        float[][] uvs = { {0, 0}, {1, 0}, {0, 1}, {1, 1} };
        int offset = g.leafVerts.size() / 8;
        for (int v = 0; v < 4; v++) {
            addVertex(g.leafVerts, positions[v][0], positions[v][1], positions[v][2], n[0], n[1], n[2], uvs[v][0], uvs[v][1]);
        }
        g.leafInds.add(offset + 0); g.leafInds.add(offset + 1); g.leafInds.add(offset + 2);
        g.leafInds.add(offset + 2); g.leafInds.add(offset + 1); g.leafInds.add(offset + 3);
    }

    // ---- Building blocks of the other forms (see FloraForms) ----

    /** A part's surface, as built: its grid of points and their normals, rings by around + 1 (the last of each ring the first again). */
    static final class Surface {
        final float[][][] p, n;

        Surface(float[][][] p, float[][][] n) {
            this.p = p;
            this.n = n;
        }

        /** The point and normal at s along it (0 to 1, ring to ring) and t round it (0 to 1). */
        float[][] at(float s, float t) {
            int rings = p.length, around = p[0].length - 1;
            float fi = Math.max(0f, Math.min(1f, s)) * (rings - 1), fj = (((t % 1f) + 1f) % 1f) * around;
            int i = Math.min(rings - 2, (int) fi), j = Math.min(around - 1, (int) fj);
            float a = fi - i, b = fj - j;
            float[] pos = new float[3], nor = new float[3];
            for (int k = 0; k < 3; k++) {
                pos[k] = lerp(lerp(p[i][j][k], p[i][j + 1][k], b), lerp(p[i + 1][j][k], p[i + 1][j + 1][k], b), a);
                nor[k] = lerp(lerp(n[i][j][k], n[i][j + 1][k], b), lerp(n[i + 1][j][k], n[i + 1][j + 1][k], b), a);
            }
            return new float[][] { pos, normalise(nor) };
        }
    }

    /**
     * Lays a grid of points (rings, each round the part: see Surface) into the plant's body as
     * one part (see BARK): its normals worked out from the grid itself, u round it and v (one a
     * ring) along it.
     */
    static Surface grid(Geometry g, float[][][] p, int part, float[] v) {
        int rings = p.length, around = p[0].length - 1;
        float[][][] n = new float[rings][around + 1][];
        for (int i = 0; i < rings; i++) {
            for (int j = 0; j < around; j++) {
                float[] dRound = sub(p[i][(j + 1) % around], p[i][(j - 1 + around) % around]);
                float[] dAlong = sub(p[Math.min(i + 1, rings - 1)][j], p[Math.max(i - 1, 0)][j]);
                float[] c = cross(dAlong, dRound);
                n[i][j] = length(c) > 1e-9f ? normalise(c) : null;
            }
        }
        // (a ring shrunk to a point, the tip of a dome, takes its neighbour's)
        for (int i = 0; i < rings; i++) {
            for (int j = 0; j < around; j++) {
                for (int k = 1; n[i][j] == null && k < rings; k++) {
                    if (i - k >= 0 && n[i - k][j] != null && (i + k >= rings || n[i + k][j] == null)) n[i][j] = n[i - k][j];
                    else if (i + k < rings && n[i + k][j] != null) n[i][j] = n[i + k][j];
                }
                if (n[i][j] == null) n[i][j] = new float[] { 0f, 1f, 0f };
            }
            n[i][around] = n[i][0];
        }
        int offset = g.branchVerts.size() / 8;
        for (int i = 0; i < rings; i++) {
            for (int j = 0; j <= around; j++) {
                float[] q = p[i][j], m = n[i][j];
                addVertex(g.branchVerts, q[0], q[1], q[2], m[0], m[1], m[2], j / (float) around + 2 * part, v[i]);
            }
        }
        for (int i = 0; i < rings - 1; i++) {
            for (int j = 0; j < around; j++) {
                int a = offset + i * (around + 1) + j, b = a + around + 1, c = a + 1, d = b + 1;
                g.branchInds.add(a); g.branchInds.add(b); g.branchInds.add(c);
                g.branchInds.add(c); g.branchInds.add(b); g.branchInds.add(d);
            }
        }
        return new Surface(p, n);
    }

    /**
     * A part swept along a spine (points in order): round it, radius at each, so many points
     * round, grooved between so many ribs (none if 0) so deep (a share of the radius).
     */
    static Surface sweep(Geometry g, float[][] spine, float[] radius, int around, int ribs, float ribDepth, int part) {
        int rings = spine.length;
        float[][][] p = new float[rings][around + 1][];
        float[] v = new float[rings];
        float widest = 0f;
        for (float r : radius) widest = Math.max(widest, r);
        float[] normal = null;
        for (int i = 0; i < rings; i++) {
            float[] t = normalise(sub(spine[Math.min(i + 1, rings - 1)], spine[Math.max(i - 1, 0)]));
            // (each ring's sideways carried on from the last, so the part doesn't twist)
            if (normal == null) normal = perpendicular(t);
            else normal = normalise(sub(normal, scale(t, dot(normal, t))));
            float[] binormal = cross(normal, t);
            for (int j = 0; j <= around; j++) {
                p[i][j] = ringPoint(spine[i], normal, binormal, radius[i], j % around, around, ribs, ribDepth);
            }
            if (i > 0) v[i] = v[i - 1] + length(sub(spine[i], spine[i - 1])) / (6.2832f * Math.max(widest, 0.01f));
        }
        return grid(g, p, part, v);
    }

    /**
     * A part turned round an axis: from a frame (origin, then its x, y and z axes: see frame),
     * rings of radius r at height y along its y axis, so many points round.
     */
    static Surface lathe(Geometry g, float[][] frame, float[] r, float[] y, int around, int ribs, float ribDepth, int part) {
        int rings = r.length;
        float[][][] p = new float[rings][around + 1][];
        float[] v = new float[rings];
        float widest = 0f;
        for (float q : r) widest = Math.max(widest, q);
        for (int i = 0; i < rings; i++) {
            float[] centre = add(frame[0], scale(frame[2], y[i]));
            for (int j = 0; j <= around; j++) {
                p[i][j] = ringPoint(centre, frame[1], frame[3], r[i], j % around, around, ribs, ribDepth);
            }
            if (i > 0) v[i] = v[i - 1] + (float) Math.hypot(r[i] - r[i - 1], y[i] - y[i - 1]) / (6.2832f * Math.max(widest, 0.01f));
        }
        return grid(g, p, part, v);
    }

    private static float[] ringPoint(float[] centre, float[] x, float[] z, float r, int j, int around, int ribs, float ribDepth) {
        double angle = 2.0 * Math.PI * j / around;
        float rr = ribs > 0 ? r * (1f - ribDepth * (0.5f - 0.5f * (float) Math.cos(ribs * angle))) : r;
        float c = (float) Math.cos(angle) * rr, s = (float) Math.sin(angle) * rr;
        return new float[] { centre[0] + x[0] * c + z[0] * s, centre[1] + x[1] * c + z[1] * s, centre[2] + x[2] * c + z[2] * s };
    }

    /** A frame at origin with its y axis along up (any way round it): {origin, x, y, z}. */
    static float[][] frame(float[] origin, float[] up) {
        float[] y = normalise(up), x = perpendicular(y), z = cross(x, y);
        return new float[][] { origin, x, y, z };
    }

    /**
     * Points round a part of radius r: as many as its size and the level of detail (most) call
     * for. (A big part keeps a good many even far off: a giant mushroom's cap can be overhead
     * while its foot, which sets its level of detail, is some way off.)
     */
    static int sides(float r, int most) {
        return Math.max(4, Math.min(most * 2 + 8, Math.round(r * 3f) + 4));
    }

    /** Points round an orb, by the level of detail (round enough not to show its corners). */
    static int orbSides(Geometry g, int most) {
        return 12 - 2 * g.lod;
    }

    /** A glossy orb (see ORB): a ball of radius r centred at c, at most so many points round (fewer if it's small). */
    static void orb(Geometry g, float[] c, float r, int around) {
        if (g.thinning.nextFloat() >= ORBS_KEPT[g.lod]) return;
        around = Math.max(6, Math.min(around, Math.round(r * 3f) + 5));
        int rings = Math.max(4, around / 2 + 1);
        float[] rr = new float[rings], y = new float[rings];
        for (int i = 0; i < rings; i++) {
            double a = Math.PI * i / (rings - 1);
            rr[i] = r * (float) Math.sin(a);
            y[i] = -r * (float) Math.cos(a);
        }
        lathe(g, frame(c, new float[] { 0f, 1f, 0f }), rr, y, around, 0, 0f, ORB);
    }

    /** A spike from root along out, so long and wide at its root: three flat faces to a point. */
    static void spike(Geometry g, float[] root, float[] out, float length, float width) {
        if (g.thinning.nextFloat() >= SPIKES_KEPT[g.lod]) return;
        float[] o = normalise(out), x = perpendicular(o), z = cross(x, o);
        float[] tip = add(root, scale(o, length));
        float[][] base = new float[3][];
        for (int k = 0; k < 3; k++) {
            double a = 2.0 * Math.PI * k / 3.0;
            base[k] = add(root, add(scale(x, width * (float) Math.cos(a)), scale(z, width * (float) Math.sin(a))));
        }
        for (int k = 0; k < 3; k++) {
            float[] a = base[k], b = base[(k + 1) % 3];
            float[] n = normalise(cross(sub(b, a), sub(tip, a)));
            int offset = g.branchVerts.size() / 8;
            addVertex(g.branchVerts, a[0], a[1], a[2], n[0], n[1], n[2], 2 * SPIKE + 0.3f, 0f);
            addVertex(g.branchVerts, b[0], b[1], b[2], n[0], n[1], n[2], 2 * SPIKE + 0.7f, 0f);
            addVertex(g.branchVerts, tip[0], tip[1], tip[2], n[0], n[1], n[2], 2 * SPIKE + 0.5f, 1f);
            g.branchInds.add(offset); g.branchInds.add(offset + 1); g.branchInds.add(offset + 2);
        }
    }

    /** A rotation (as a model matrix's) whose y axis points along y. */
    static Matrix4 pointing(float[] y) {
        float[] yy = normalise(y), x = perpendicular(yy), z = cross(x, yy);
        return Matrix4.fromColumns(new float[] { x[0], x[1], x[2], 0, yy[0], yy[1], yy[2], 0, z[0], z[1], z[2], 0, 0, 0, 0, 1 });
    }

    /** rot turned so its y axis bends down by so many degrees (unless it already points straight down). */
    private static Matrix4 drooped(Matrix4 rot, float degrees) {
        float[] y = multiplyNormal(rot, 0, 1, 0);
        float[] axis = cross(y, new float[] { 0f, -1f, 0f });
        if (length(axis) < 1e-4f) return rot;
        axis = normalise(axis);
        float a = (float) Math.toRadians(degrees), c = (float) Math.cos(a), s = (float) Math.sin(a), k = 1f - c;
        float x = axis[0], yy = axis[1], z = axis[2];
        // (Rodrigues' rotation, column by column)
        Matrix4 turn = Matrix4.fromColumns(new float[] {
            c + x * x * k, yy * x * k + z * s, z * x * k - yy * s, 0,
            x * yy * k - z * s, c + yy * yy * k, z * yy * k + x * s, 0,
            x * z * k + yy * s, yy * z * k - x * s, c + z * z * k, 0,
            0, 0, 0, 1 });
        return Matrix4.multiply(turn, rot);
    }

    // ---- Small vector sums ----

    static float[] add(float[] a, float[] b) { return new float[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] }; }
    static float[] sub(float[] a, float[] b) { return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] }; }
    static float[] scale(float[] a, float s) { return new float[] { a[0] * s, a[1] * s, a[2] * s }; }
    static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static float length(float[] a) { return (float) Math.sqrt(dot(a, a)); }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    static float[] normalise(float[] a) {
        float l = length(a);
        return l < 1e-12f ? new float[] { 0f, 1f, 0f } : scale(a, 1f / l);
    }

    /** A unit vector at right angles to a (unit). */
    static float[] perpendicular(float[] a) {
        float[] other = Math.abs(a[1]) < 0.9f ? new float[] { 0f, 1f, 0f } : new float[] { 1f, 0f, 0f };
        return normalise(cross(other, a));
    }

    static void addVertex(List<Float> verts, float px, float py, float pz, float nx, float ny, float nz, float u, float v) {
        verts.add(px); verts.add(py); verts.add(pz);
        verts.add(nx); verts.add(ny); verts.add(nz);
        verts.add(u); verts.add(v);
    }

    static float[] multiplyNormal(Matrix4 rot, float x, float y, float z) {
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
