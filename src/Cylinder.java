public final class Cylinder {

    private static final int SLICES = 12; // Dodecagon prism to minimize vertex overhead
    
    public static final float[] vertices = createVertices();
    public static final int[] indices = createIndices();

    private static float[] createVertices() {
        // (SLICES + 1) * 2 for side walls
        // (SLICES + 2) * 2 for both caps (1 center vertex + perimeter ring)
        int numVertices = (SLICES + 1) * 2 + (SLICES + 2) * 2;
        float[] v = new float[numVertices * 8];
        int idx = 0;
        float r = 0.5f;
        float h = 0.5f; 

        // 1. Side wall vertices
        for (int i = 0; i <= SLICES; i++) {
            float theta = (float) (2.0 * Math.PI * i / SLICES);
            float cosT = (float) Math.cos(theta);
            float sinT = (float) Math.sin(theta);
            float u = (float) i / SLICES;

            // Top rim vertex
            v[idx++] = r * cosT; v[idx++] = h; v[idx++] = r * sinT; // Position
            v[idx++] = cosT; v[idx++] = 0; v[idx++] = sinT;        // Normal
            v[idx++] = u; v[idx++] = 1.0f;                         // Texture Coords

            // Bottom rim vertex
            v[idx++] = r * cosT; v[idx++] = -h; v[idx++] = r * sinT; // Position
            v[idx++] = cosT; v[idx++] = 0; v[idx++] = sinT;         // Normal
            v[idx++] = u; v[idx++] = 0.0f;                          // Texture Coords
        }

        // 2. Top cap vertices
        // Center vertex
        v[idx++] = 0; v[idx++] = h; v[idx++] = 0;
        v[idx++] = 0; v[idx++] = 1; v[idx++] = 0; 
        v[idx++] = 0.5f; v[idx++] = 0.5f;
        // Outer perimeter ring
        for (int i = 0; i <= SLICES; i++) {
            float theta = (float) (2.0 * Math.PI * i / SLICES);
            float cosT = (float) Math.cos(theta);
            float sinT = (float) Math.sin(theta);
            v[idx++] = r * cosT; v[idx++] = h; v[idx++] = r * sinT;
            v[idx++] = 0; v[idx++] = 1; v[idx++] = 0;
            v[idx++] = 0.5f + 0.5f * cosT; v[idx++] = 0.5f + 0.5f * sinT;
        }

        // 3. Bottom cap vertices
        // Center vertex
        v[idx++] = 0; v[idx++] = -h; v[idx++] = 0;
        v[idx++] = 0; v[idx++] = -1; v[idx++] = 0; 
        v[idx++] = 0.5f; v[idx++] = 0.5f;
        // Outer perimeter ring
        for (int i = 0; i <= SLICES; i++) {
            float theta = (float) (2.0 * Math.PI * i / SLICES);
            float cosT = (float) Math.cos(theta);
            float sinT = (float) Math.sin(theta);
            v[idx++] = r * cosT; v[idx++] = -h; v[idx++] = r * sinT;
            v[idx++] = 0; v[idx++] = -1; v[idx++] = 0;
            v[idx++] = 0.5f + 0.5f * cosT; v[idx++] = 0.5f + 0.5f * sinT;
        }
        return v;
    }

    private static int[] createIndices() {
        int numIndices = SLICES * 6 + SLICES * 3 + SLICES * 3;
        int[] ind = new int[numIndices];
        int idx = 0;

        // 1. Side walls (Flipped to be Counter-Clockwise from outside)
        for (int i = 0; i < SLICES; i++) {
            int top1 = i * 2;
            int bot1 = i * 2 + 1;
            int top2 = (i + 1) * 2;
            int bot2 = (i + 1) * 2 + 1;

            ind[idx++] = bot1; ind[idx++] = top2; ind[idx++] = bot2;
            ind[idx++] = bot1; ind[idx++] = top1; ind[idx++] = top2;
        }

        // 2. Top cap (Counter-Clockwise looking down from above)
        int topOffset = (SLICES + 1) * 2;
        for (int i = 0; i < SLICES; i++) {
            ind[idx++] = topOffset; // Center
            ind[idx++] = topOffset + 1 + i + 1;
            ind[idx++] = topOffset + 1 + i;
        }

        // 3. Bottom cap (Counter-Clockwise looking up from below)
        int botOffset = topOffset + SLICES + 2;
        for (int i = 0; i < SLICES; i++) {
            ind[idx++] = botOffset; // Center
            ind[idx++] = botOffset + 1 + i;
            ind[idx++] = botOffset + 1 + i + 1;
        }

        return ind;
    }
}