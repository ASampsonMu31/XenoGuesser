import java.util.Arrays;
import com.jogamp.opengl.GL3;

/**
 * Growable vertex/index buffer in the 8-float layout Mesh expects
 * (position, normal, texture coords).
 */
public class MeshBuilder {
    private static final int STRIDE = 8;

    private float[] vertices = new float[64 * STRIDE];
    private int vertexFloatCount;
    private int[] indices = new int[96];
        private int indexCount;
    private float planarTextureScale = 0;

    /**
     * Gives faces added with addConvexFace texture coordinates in world units times this
     * scale: along the face horizontally and up it vertically, or straight down onto flat
     * faces. Zero keeps the old corner-based coordinates.
     */
    public void setPlanarTextureScale(float scale) {
        this.planarTextureScale = scale;
    }

    public int addVertex(float x, float y, float z, float nx, float ny, float nz, float u, float v) {
        if (vertexFloatCount + STRIDE > vertices.length) {
            vertices = Arrays.copyOf(vertices, vertices.length * 2);
        }
        int index = vertexFloatCount / STRIDE;
        vertices[vertexFloatCount++] = x;
        vertices[vertexFloatCount++] = y;
        vertices[vertexFloatCount++] = z;
        vertices[vertexFloatCount++] = nx;
        vertices[vertexFloatCount++] = ny;
        vertices[vertexFloatCount++] = nz;
        vertices[vertexFloatCount++] = u;
        vertices[vertexFloatCount++] = v;
        return index;
    }

    public void addTriangle(int a, int b, int c) {
        if (indexCount + 3 > indices.length) {
            indices = Arrays.copyOf(indices, indices.length * 2);
        }
        indices[indexCount++] = a;
        indices[indexCount++] = b;
        indices[indexCount++] = c;
    }

    /**
     * Adds a flat-shaded planar convex polygon. The corners may be listed in either
     * winding order: the face is flipped so it winds counter-clockwise when viewed
     * from outside, i.e. from the side facing away from interiorPoint.
     */
    public void addConvexFace(float[] interiorPoint, float[]... corners) {
        float[] c0 = corners[0];
        float[] c1 = corners[1];
        float[] c2 = corners[2];
        float e1x = c1[0] - c0[0], e1y = c1[1] - c0[1], e1z = c1[2] - c0[2];
        float e2x = c2[0] - c0[0], e2y = c2[1] - c0[1], e2z = c2[2] - c0[2];
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= length;
        ny /= length;
        nz /= length;

        float centreX = 0.0f, centreY = 0.0f, centreZ = 0.0f;
        for (float[] corner : corners) {
            centreX += corner[0];
            centreY += corner[1];
            centreZ += corner[2];
        }
        centreX /= corners.length;
        centreY /= corners.length;
        centreZ /= corners.length;

        boolean flip = nx * (centreX - interiorPoint[0])
                + ny * (centreY - interiorPoint[1])
                + nz * (centreZ - interiorPoint[2]) < 0.0f;
        if (flip) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }

        int first = -1;
        for (int i = 0; i < corners.length; i++) {
            float[] corner = corners[flip ? corners.length - 1 - i : i];
                        float u = corner[0] + 0.5f, v = corner[1] + corner[2] + 0.5f;
            if (planarTextureScale > 0) {
                if (Math.abs(ny) > 0.7f) {
                    u = corner[0] * planarTextureScale;
                    v = corner[2] * planarTextureScale;
                } else {
                    // Horizontal direction within the face
                    float tx = -nz, tz = nx;
                    float tl = (float) Math.sqrt(tx * tx + tz * tz);
                    u = (corner[0] * tx + corner[2] * tz) / tl * planarTextureScale;
                    v = corner[1] * planarTextureScale;
                }
            }
            int index = addVertex(corner[0], corner[1], corner[2], nx, ny, nz, u, v);
            if (first < 0) {
                first = index;
            }
        }
        for (int i = 1; i < corners.length - 1; i++) {
            addTriangle(first, first + i, first + i + 1);
        }
    }

    public boolean isEmpty() {
        return indexCount == 0;
    }

    public float[] vertexArray() {
        return Arrays.copyOf(vertices, vertexFloatCount);
    }

    public int[] indexArray() {
        return Arrays.copyOf(indices, indexCount);
    }

    public Mesh build(GL3 gl) {
        return new Mesh(gl, vertexArray(), indexArray());
    }
}
