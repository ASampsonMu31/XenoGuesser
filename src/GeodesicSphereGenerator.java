// GeodesicSphereGenerator.java
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class GeodesicSphereGenerator {

    private static class Vertex {
        float x, y, z;
        Vertex(float x, float y, float z) {
            float len = (float)Math.sqrt(x*x + y*y + z*z);
            this.x = x / len; // Automatically normalize onto a 1-unit sphere
            this.y = y / len;
            this.z = z / len;
        }
    }

    public static class MeshData {
        public float[] vertices;
        public int[] indices;
    }

    public static MeshData generate(int subdivisions) {
        ArrayList<Vertex> vertices = new ArrayList<>();
        ArrayList<Integer> indices = new ArrayList<>();

        // Step 1: Create the 12 base vertices of a regular icosahedron
        float t = (1.0f + (float)Math.sqrt(5.0)) / 2.0f;

        vertices.add(new Vertex(-1,  t,  0)); vertices.add(new Vertex( 1,  t,  0));
        vertices.add(new Vertex(-1, -t,  0)); vertices.add(new Vertex( 1, -t,  0));

        vertices.add(new Vertex( 0, -1,  t)); vertices.add(new Vertex( 0,  1,  t));
        vertices.add(new Vertex( 0, -1, -t)); vertices.add(new Vertex( 0,  1, -t));

        vertices.add(new Vertex( t,  0, -1)); vertices.add(new Vertex( t,  0,  1));
        vertices.add(new Vertex(-t,  0, -1)); vertices.add(new Vertex(-t,  0,  1));

        // Step 2: Define the 20 base triangles
        int[] baseIndices = {
            0, 11, 5,   0, 5, 1,    0, 1, 7,    0, 7, 10,   0, 10, 11,
            1, 5, 9,    5, 11, 4,   11, 10, 2,  10, 7, 6,   7, 1, 8,
            3, 9, 4,    3, 4, 2,    3, 2, 6,    3, 6, 8,    3, 8, 9,
            4, 9, 5,    2, 4, 11,   6, 2, 10,   8, 6, 7,    9, 8, 1
        };
        for (int idx : baseIndices) indices.add(idx);

        // Cache to avoid duplicate vertices when splitting shared edges
        Map<Long, Integer> midpointCache = new HashMap<>();

        // Step 3: Subdivide triangles iteratively
        for (int i = 0; i < subdivisions; i++) {
            ArrayList<Integer> newIndices = new ArrayList<>();
            for (int j = 0; j < indices.size(); j += 3) {
                int v1 = indices.get(j);
                int v2 = indices.get(j + 1);
                int v3 = indices.get(j + 2);

                int a = getMidpoint(v1, v2, vertices, midpointCache);
                int b = getMidpoint(v2, v3, vertices, midpointCache);
                int c = getMidpoint(v3, v1, vertices, midpointCache);

                // 1 triangle splits into 4 sub-triangles
                newIndices.add(v1); newIndices.add(a); newIndices.add(c);
                newIndices.add(v2); newIndices.add(b); newIndices.add(a);
                newIndices.add(v3); newIndices.add(c); newIndices.add(b);
                newIndices.add(a);  newIndices.add(b); newIndices.add(c);
            }
            indices = newIndices;
        }

        // Step 4: Flatten into standard OpenGL layouts (8 floats per vertex: X,Y,Z, NX,NY,NZ, U,V)
        float[] outVertices = new float[vertices.size() * 8];
        int vIdx = 0;
        for (Vertex v : vertices) {
            outVertices[vIdx++] = v.x; // Position
            outVertices[vIdx++] = v.y;
            outVertices[vIdx++] = v.z;
            
            outVertices[vIdx++] = v.x; // Normal (on a sphere from origin, normal equals normalized position!)
            outVertices[vIdx++] = v.y;
            outVertices[vIdx++] = v.z;

            // Simple spherical UV coordinates
            outVertices[vIdx++] = (float)(Math.atan2(v.z, v.x) / (2 * Math.PI) + 0.5);
            outVertices[vIdx++] = (float)(Math.asin(v.y) / Math.PI + 0.5);
        }

        int[] outIndices = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) outIndices[i] = indices.get(i);

        MeshData data = new MeshData();
        data.vertices = outVertices;
        data.indices = outIndices;
        return data;
    }

    private static int getMidpoint(int p1, int p2, ArrayList<Vertex> vertices, Map<Long, Integer> cache) {
        // Build a stable 64-bit key from two 32-bit indices.
        long a = Math.min(p1, p2);
        long b = Math.max(p1, p2);
        long key = (a << 32) | (b & 0xffffffffL);

        if (cache.containsKey(key)) return cache.get(key);

        Vertex v1 = vertices.get(p1);
        Vertex v2 = vertices.get(p2);
        Vertex middle = new Vertex((v1.x + v2.x) / 2.0f, (v1.y + v2.y) / 2.0f, (v1.z + v2.z) / 2.0f);

        vertices.add(middle);
        int index = vertices.size() - 1;
        cache.put(key, index);
        return index;
    }
}
