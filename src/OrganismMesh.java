import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;

/**
 * A whole organism as one mesh, every vertex tagged with the bone (body part) that moves it
 * and the kind of part it belongs to, so the organism shader can pose and colour it in a
 * single draw call.
 *
 * Vertex layout: position (3), normal (3), texture coordinates (2), bone index, part kind.
 * Texture u runs around a part (0.25 is its top) and v along it.
 */
public class OrganismMesh {

    public static final int STRIDE = 10;

    // Part kinds, read by the organism fragment shader to choose a colour scheme
    public static final int PART_BODY = 0;
    public static final int PART_LEG = 1;
    public static final int PART_FOOT = 2;
    public static final int PART_ANTENNA = 3;
    public static final int PART_TRUNK = 4;
    public static final int PART_EYE = 5;
    public static final int PART_HORN = 6;
    public static final int PART_TRIM = 7;
    public static final int PART_SKIN = 8;

    private final int[] vertexArray = new int[1];
    private final int[] vertexBuffer = new int[1];
    private final int[] elementBuffer = new int[1];
    private final int indexCount;

    private OrganismMesh(GL3 gl, float[] vertices, int[] indices) {
        indexCount = indices.length;
        gl.glGenVertexArrays(1, vertexArray, 0);
        gl.glBindVertexArray(vertexArray[0]);
        gl.glGenBuffers(1, vertexBuffer, 0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vertexBuffer[0]);
        FloatBuffer fb = Buffers.newDirectFloatBuffer(vertices);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) Float.BYTES * vertices.length, fb, GL.GL_STATIC_DRAW);
        int stride = STRIDE * Float.BYTES;
        int[] sizes = { 3, 3, 2, 1, 1 };
        int offset = 0;
        for (int attribute = 0; attribute < sizes.length; attribute++) {
            gl.glVertexAttribPointer(attribute, sizes[attribute], GL.GL_FLOAT, false, stride, (long) offset * Float.BYTES);
            gl.glEnableVertexAttribArray(attribute);
            offset += sizes[attribute];
        }
        gl.glGenBuffers(1, elementBuffer, 0);
        IntBuffer ib = Buffers.newDirectIntBuffer(indices);
        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, elementBuffer[0]);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) Integer.BYTES * indices.length, ib, GL.GL_STATIC_DRAW);
        gl.glBindVertexArray(0);
    }

    public void render(GL3 gl) {
        gl.glBindVertexArray(vertexArray[0]);
        gl.glDrawElements(GL.GL_TRIANGLES, indexCount, GL.GL_UNSIGNED_INT, 0);
        gl.glBindVertexArray(0);
    }

    public void dispose(GL3 gl) {
        gl.glDeleteBuffers(1, vertexBuffer, 0);
        gl.glDeleteBuffers(1, elementBuffer, 0);
        gl.glDeleteVertexArrays(1, vertexArray, 0);
    }

    /**
     * The centre line of a lathed part and its cross-section at t in [0, 1], written into
     * out as {centreX, centreY, centreZ, radiusX, radiusY}.
     */
    @FunctionalInterface
    public interface Profile {
        void at(float t, float[] out);
    }

    /** Collects parts on the CPU; each is placed by the current transform and tagged with the current bone and part kind. */
    public static final class Builder {
        private float[] vertices = new float[4096 * STRIDE];
        private int vertexFloats;
        private int[] indices = new int[8192];
        private int indexCount;
        private float[] transform = Affine.identity();
        private int bone;
        private int part;

        public Builder bone(int bone) {
            this.bone = bone;
            return this;
        }

        public Builder part(int part) {
            this.part = part;
            return this;
        }

        /** Places the next parts in the bone's space by this transform. */
        public Builder transform(float[] transform) {
            this.transform = transform;
            return this;
        }

        public Builder resetTransform() {
            this.transform = Affine.identity();
            return this;
        }

        public float[] currentTransform() {
            return transform;
        }

        /**
         * Sweeps an elliptical cross-section along a centre line. The centre line should lie
         * in the part's YZ plane (bending up or down), which keeps the cross-section's X axis
         * fixed. Normals are taken from the finished surface, so any profile shades smoothly.
         */
        public void lathe(int around, int along, Profile profile) {
            float[] sample = new float[5];
            float[][] centres = new float[along + 1][];
            float[][] radii = new float[along + 1][];
            for (int j = 0; j <= along; j++) {
                profile.at(j / (float) along, sample);
                centres[j] = new float[] { sample[0], sample[1], sample[2] };
                radii[j] = new float[] { sample[3], sample[4] };
            }
            float[][][] grid = new float[along + 1][around][];
            for (int j = 0; j <= along; j++) {
                float[] tangent = Affine.normalise(Affine.subtract(centres[Math.min(along, j + 1)], centres[Math.max(0, j - 1)]));
                float[] side = { 1f, 0f, 0f };
                float[] up = Affine.normalise(Affine.cross(tangent, side));
                for (int i = 0; i < around; i++) {
                    double angle = i / (double) around * Math.PI * 2.0;
                    float cx = (float) Math.cos(angle) * radii[j][0];
                    float cy = (float) Math.sin(angle) * radii[j][1];
                    grid[j][i] = new float[] {
                        centres[j][0] + side[0] * cx + up[0] * cy,
                        centres[j][1] + side[1] * cx + up[1] * cy,
                        centres[j][2] + side[2] * cx + up[2] * cy
                    };
                }
            }

            int first = vertexFloats / STRIDE;
            for (int j = 0; j <= along; j++) {
                for (int i = 0; i <= around; i++) {
                    int wrapped = i % around;
                    float[] p = grid[j][wrapped];
                    float[] du = Affine.subtract(grid[j][(wrapped + 1) % around], grid[j][(wrapped + around - 1) % around]);
                    float[] dv = Affine.subtract(grid[Math.min(along, j + 1)][wrapped], grid[Math.max(0, j - 1)][wrapped]);
                    float[] normal = Affine.cross(du, dv);
                    float[] outward = Affine.subtract(p, centres[j]);
                    if (Affine.length(normal) < 1e-6f) {
                        // A pole: point along the centre line, away from the part
                        float[] axis = Affine.subtract(centres[Math.min(along, j + 1)], centres[Math.max(0, j - 1)]);
                        normal = j == 0 ? new float[] { -axis[0], -axis[1], -axis[2] } : axis;
                    } else if (Affine.dot(normal, outward) < 0) {
                        normal = new float[] { -normal[0], -normal[1], -normal[2] };
                    }
                    normal = Affine.normalise(normal);
                    float[] wp = Affine.transformPoint(transform, p[0], p[1], p[2]);
                    float[] wn = Affine.normalise(Affine.transformDirection(transform, normal[0], normal[1], normal[2]));
                    addVertex(wp, wn, i / (float) around, j / (float) along);
                }
            }
            int row = around + 1;
            for (int j = 0; j < along; j++) {
                for (int i = 0; i < around; i++) {
                    int a = first + j * row + i, b = a + 1, c = a + row, d = c + 1;
                    addTriangle(a, b, c);
                    addTriangle(b, d, c);
                }
            }
        }

        private void addVertex(float[] p, float[] n, float u, float v) {
            if (vertexFloats + STRIDE > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
            }
            vertices[vertexFloats++] = p[0];
            vertices[vertexFloats++] = p[1];
            vertices[vertexFloats++] = p[2];
            vertices[vertexFloats++] = n[0];
            vertices[vertexFloats++] = n[1];
            vertices[vertexFloats++] = n[2];
            vertices[vertexFloats++] = u;
            vertices[vertexFloats++] = v;
            vertices[vertexFloats++] = bone;
            vertices[vertexFloats++] = part;
        }

        private void addTriangle(int a, int b, int c) {
            if (indexCount + 3 > indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            indices[indexCount++] = a;
            indices[indexCount++] = b;
            indices[indexCount++] = c;
        }

        public int triangleCount() {
            return indexCount / 3;
        }

        public OrganismMesh build(GL3 gl) {
            return new OrganismMesh(gl, Arrays.copyOf(vertices, vertexFloats), Arrays.copyOf(indices, indexCount));
        }
    }
}
