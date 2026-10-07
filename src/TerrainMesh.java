import com.jogamp.opengl.*;

public class TerrainMesh {

    /**
     * The ground's height at chart point (worldX, worldZ). Every layer is noise sampled at
     * that place on the planet's surface (see Planet), so the land runs on round the world
     * with no seam where the chart's east and west edges meet.
     */
    public static float getLayeredHeight(float worldX, float worldZ, PerlinNoise noise) {
        float[] surface = Planet.surface(worldX, worldZ);
        // LAYER 1: the continents. A handful of separate landmasses spread round the planet
        // (see continentField), their coasts broken up by broad noise so they're irregular,
        // now and then split into two or joined by an isthmus
        float f1 = 0.00003f;
        float rawNoise1 = noise.onSphere(surface, f1, 0f, 0f);
        float f2 = 0.00008f;
        float rawNoise2 = noise.onSphere(surface, f2, 0f, 0f);
        // (out at sea the continents' pull fades, so islands can still rise from the broad noise)
        float continent = Math.max(-0.3f, continentField(surface, noise, rawNoise1, rawNoise2));
        // Near the coast, finer noise makes it ragged: headlands, inlets and islands off it, and
        // ever smaller octaves on top so no peninsula's outline stays a smooth blob (capes,
        // coves, rocky points and offshore stacks)
        float coast = 1f - Math.min(1f, Math.abs(continent) / 0.35f);
        float ragged = 0f;
        if (coast > 0f) {
            ragged = noise.onSphere(surface, 0.00026f, 31.7f, -12.3f) * 0.3f;
            float frequency = 0.00065f, amplitude = 0.15f;
            for (int octave = 0; octave < 4; octave++) {
                ragged += noise.onSphere(surface, frequency, 53.9f + 17.3f * octave, 8.1f - 29.5f * octave) * amplitude;
                frequency *= 2.2f;
                amplitude *= 0.55f;
            }
            ragged *= coast;
        }
        float continentLayer1 = (continent + 0.4f * rawNoise1 + 0.55f * rawNoise2 + ragged) * CONTINENT_HEIGHT;

        // LAYER 2: continentLayer2 (Master macro continental signals)
        float macroVariationHeight = 400.0f;
        float continentLayer2 = (rawNoise2 * Math.abs(rawNoise2)) * macroVariationHeight;

        // LAYER 3: mountainLayer (Base mountains & deep canyons)
        float f3 = 0.0002f;
        float maxMountainHeight = 1200.0f;
        float maxCanyonDepth = -800.0f;
        float rawNoise3 = noise.onSphere(surface, f3, 0f, 0f);
        float mountainLayer = 0.0f;

        if (rawNoise3 > 0.0f) {
            float mountainShape = rawNoise3 * rawNoise3 * rawNoise3 * rawNoise3;
            mountainLayer = mountainShape * maxMountainHeight;
        } else {
            float positiveValleySignal = Math.abs(rawNoise3);
            float valleyShape = positiveValleySignal * positiveValleySignal * positiveValleySignal * positiveValleySignal;
            mountainLayer = valleyShape * maxCanyonDepth;
        }

        // ALTITUDE STRUCTURAL BASELINE
        float baseHeight = mountainLayer + continentLayer2 + continentLayer1;
        // As on Earth, land thins out towards the poles: the crust sinks away at high latitudes
        float sinLatitude = Math.abs(surface[1]) / (float) Planet.radius();
        float polar = Math.max(0f, Math.min(1f, (sinLatitude - 0.62f) / 0.38f));
        baseHeight -= 120f * polar * polar * (3f - 2f * polar);

        // LAYER 4: hillLayer (Mid-scale hills with low altitude suppression)
        float f4 = 0.001f;
        float maxHillHeight = 120.0f;
        float rawNoise4 = noise.onSphere(surface, f4, 0f, 0f);
        float hillLayer = (rawNoise4 * Math.abs(rawNoise4)) * maxHillHeight;

        // DYNAMIC HILL MASK
        float hillAltitudeMask = 1.0f;
        if (baseHeight < 100.0f) {
            hillAltitudeMask = (baseHeight + 200.0f) / 300.0f;
            if (hillAltitudeMask < 0.0f) hillAltitudeMask = 0.0f;
            if (hillAltitudeMask > 1.0f) hillAltitudeMask = 1.0f;
        }
        hillLayer *= hillAltitudeMask;

        // LAYER 5: roughnessLayer (Micro surface roughness ground detail)
        float f5 = 0.12f;
        float a5 = 2.2f;
        float rawNoise5 = noise.onSphere(surface, f5, 0f, 0f);
        float roughnessLayer = (1.0f - Math.abs(rawNoise5)) * a5;

        float height = baseHeight + hillLayer + roughnessLayer + mountainRidges(surface, baseHeight + hillLayer, noise);
        return flattenLowlands(height, surface, continent, noise);
    }

    private static final float CONTINENT_HEIGHT = 480.0f;

    // Ground above this height is squashed down outside the mountain belts (well above any
    // sea level, so the coasts are untouched), and by how much at most (what's left of each unit)
    private static final float PLAINS_FROM = 130f, PLAINS_KEEP = 0.12f;

    /**
     * Not all high ground is mountains: broad regions (noise at the scale of a large country)
     * are mountain belts, as they are; elsewhere ground above PLAINS_FROM is pressed down into
     * plateaus and rolling plains, deserts and grasslands, keeping a little of its shape. Only
     * inland (out at sea, the islands and volcanic atolls keep their peaks), and only above
     * PLAINS_FROM, so the coastlines and sea level stay as they were.
     */
    private static float flattenLowlands(float height, float[] surface, float continent, PerlinNoise noise) {
        if (height <= PLAINS_FROM) return height;
        float inland = smooth((continent - 0.0f) / 0.3f);
        if (inland <= 0f) return height;
        float belt = smooth((noise.onSphere(surface, 1f / 18000f, 61.3f, -27.9f) - 0.05f) / 0.35f);
        float keep = 1f - inland * (1f - belt) * (1f - PLAINS_KEEP);
        return PLAINS_FROM + (height - PLAINS_FROM) * keep;
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    // A piece of land (or of sea cut into it): {middle (unit vector), long axis, short axis, long
    // and short half-sizes (as sines of the angle), taper (how much narrower its far end is than
    // its near one: peninsulas thin to a point), cut (1 for a bay or gulf cut out of the land),
    // the cosine of the angle beyond which it can't reach}
    private static final int MIDDLE = 0, AXIS = 3, SIDE = 6, LONG = 9, SHORT = 10, TAPER = 11, CUT = 12, REACH = 13, FIELDS = 14;

    /**
     * How deep inside a continent a point on the sphere is: 1 at a continent's heart, 0 at
     * its rough edge, below 0 out to sea (the most of any continent). Continents are made of
     * pieces: a broad body, peninsulas reaching out from it (narrowing as they go), and bays
     * and gulfs cut into it; there are island chains and lone islands too. The whole is warped
     * by the broad noise (n1, n2) so no coast runs smooth for long.
     */
    private static float continentField(float[] surface, PerlinNoise noise, float n1, float n2) {
        float[][] pieces = noise.continentCores;
        if (pieces == null) pieces = continentCores(noise);
        float inv = 1f / (float) Math.sqrt(surface[0] * surface[0] + surface[1] * surface[1] + surface[2] * surface[2]);
        float px = surface[0] * inv, py = surface[1] * inv, pz = surface[2] * inv;
        // Warped a little along the surface (east and north), by the broad noise already sampled here
        float ex = -pz, ez = px;
        float el = (float) Math.sqrt(ex * ex + ez * ez);
        if (el > 1e-4f) {
            ex /= el;
            ez /= el;
            // north = p x east
            float nx = -py * ez, ny = pz * ex - px * ez, nz = py * ex;
            float w1 = n2 * 0.11f, w2 = n1 * 0.11f;
            px += ex * w1 + nx * w2;
            py += ny * w2;
            pz += ez * w1 + nz * w2;
            float l = 1f / (float) Math.sqrt(px * px + py * py + pz * pz);
            px *= l;
            py *= l;
            pz *= l;
        }
        float best = -1f;
        for (float[] c : pieces) {
            if (c[CUT] > 0f) continue;
            float facing = px * c[MIDDLE] + py * c[MIDDLE + 1] + pz * c[MIDDLE + 2];
            if (facing < c[REACH]) continue;
            best = Math.max(best, pieceField(c, px, py, pz));
        }
        // Bays and gulfs bite into whatever land is there, sloping down into them
        for (float[] c : pieces) {
            if (c[CUT] <= 0f) continue;
            float facing = px * c[MIDDLE] + py * c[MIDDLE + 1] + pz * c[MIDDLE + 2];
            if (facing < c[REACH]) continue;
            best = Math.min(best, -1.6f * pieceField(c, px, py, pz));
        }
        return best;
    }

    /** 1 in the middle of a piece, 0 at its edge, below 0 outside it. */
    private static float pieceField(float[] c, float px, float py, float pz) {
        float along = px * c[AXIS] + py * c[AXIS + 1] + pz * c[AXIS + 2];
        float across = px * c[SIDE] + py * c[SIDE + 1] + pz * c[SIDE + 2];
        // Narrower towards its far end (along > 0)
        float t = Math.max(0f, Math.min(1f, along / c[LONG] * 0.5f + 0.5f));
        float width = c[SHORT] * (1f - c[TAPER] * t);
        float q = (along * along) / (c[LONG] * c[LONG]) + (across * across) / (width * width);
        return 1f - (float) Math.sqrt(q);
    }

    /**
     * The world's land, made once from its seed: five to eight continents, spread apart and
     * kept away from the poles, each a body with peninsulas and bays; a few chains of islands
     * along curving arcs; and some lone islands of their own shapes.
     */
    private static synchronized float[][] continentCores(PerlinNoise noise) {
        if (noise.continentCores != null) return noise.continentCores;
        java.util.Random rand = new java.util.Random(noise.seed * 0x5DEECE66DL + 0xC0417L);
        int count = 5 + rand.nextInt(4);
        java.util.List<float[]> pieces = new java.util.ArrayList<>();
        java.util.List<float[]> middles = new java.util.ArrayList<>();
        for (int k = 0; k < count; k++) {
            float[] centre = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                float[] d = randomDirection(rand, 55);
                boolean apart = true;
                for (float[] other : middles) {
                    if (dot(d, other) > Math.cos(0.85 - attempt * 0.002)) { apart = false; break; }
                }
                if (apart) { centre = d; break; }
            }
            if (centre == null) continue;
            middles.add(centre);
            // The body: big and small continents alike, some long and thin
            float size = 0.22f + rand.nextFloat() * 0.34f;
            float aspect = 1f + rand.nextFloat() * 1.4f;
            float major = (float) (size * Math.sqrt(aspect)), minor = (float) (size / Math.sqrt(aspect));
            float turn = rand.nextFloat() * (float) Math.PI;
            pieces.add(piece(centre, turn, major, minor, rand.nextFloat() * 0.3f, false));
            // Peninsulas reaching out from its edge, narrowing as they go
            int peninsulas = 2 + rand.nextInt(4);
            for (int i = 0; i < peninsulas; i++) {
                float out = rand.nextFloat() * (float) Math.PI * 2f;
                float[] edge = along(centre, turn + out, edgeDistance(major, minor, out) * (0.75f + rand.nextFloat() * 0.25f));
                float length = major * (0.2f + rand.nextFloat() * 0.3f), width = Math.max(length * 0.3f, minor * (0.22f + rand.nextFloat() * 0.25f));
                float[] middle = along(edge, bearing(centre, edge), length * 0.7f);
                pieces.add(piece(middle, bearing(centre, edge), length, width, 0.25f + rand.nextFloat() * 0.45f, false));
            }
            // Bays and gulfs cut into its coast, and now and then a sea inside it
            int bays = 1 + rand.nextInt(3);
            for (int i = 0; i < bays; i++) {
                float out = rand.nextFloat() * (float) Math.PI * 2f;
                boolean inland = rand.nextFloat() < 0.15f;
                float reach = edgeDistance(major, minor, out) * (inland ? 0.35f : 0.85f + rand.nextFloat() * 0.25f);
                float[] middle = along(centre, turn + out, reach);
                float length = minor * (0.25f + rand.nextFloat() * 0.35f), width = length * (0.4f + rand.nextFloat() * 0.5f);
                pieces.add(piece(middle, rand.nextFloat() * (float) Math.PI, length, width, rand.nextFloat() * 0.5f, true));
            }
        }
        // Island chains along curving arcs
        int chains = 2 + rand.nextInt(4);
        for (int c = 0; c < chains; c++) {
            float[] at = randomDirection(rand, 60);
            float heading = rand.nextFloat() * (float) Math.PI * 2f, bend = (rand.nextFloat() - 0.5f) * 0.5f;
            int islands = 4 + rand.nextInt(9);
            float scale = 0.012f + rand.nextFloat() * 0.025f;
            for (int i = 0; i < islands; i++) {
                float length = scale * (0.6f + rand.nextFloat() * 1.2f), width = length * (0.5f + rand.nextFloat() * 0.45f);
                pieces.add(piece(at, heading + (rand.nextFloat() - 0.5f) * 0.6f, length, width, rand.nextFloat() * 0.4f, false));
                at = along(at, heading, length * 2f + scale * (0.4f + rand.nextFloat() * 1.6f));
                heading += bend;
            }
        }
        // Lone islands, some long, some with a spit or a hook
        int lone = 8 + rand.nextInt(10);
        for (int i = 0; i < lone; i++) {
            float[] at = randomDirection(rand, 62);
            float length = 0.01f + rand.nextFloat() * 0.05f, width = length * (0.5f + rand.nextFloat() * 0.45f);
            float turn = rand.nextFloat() * (float) Math.PI;
            pieces.add(piece(at, turn, length, width, rand.nextFloat() * 0.5f, false));
            if (rand.nextBoolean()) {
                float side = turn + (rand.nextBoolean() ? 1f : -1f) * (0.6f + rand.nextFloat() * 0.8f);
                pieces.add(piece(along(at, side, length * 0.8f), side, length * 0.6f, width * 0.6f, 0.45f, false));
            }
        }
        noise.continentCores = pieces.toArray(new float[0][]);
        return noise.continentCores;
    }

    /** A random direction, no more than maxLatitude degrees from the equator. */
    private static float[] randomDirection(java.util.Random rand, double maxLatitude) {
        double lat = Math.asin((rand.nextDouble() * 2 - 1) * Math.sin(Math.toRadians(maxLatitude)));
        double lon = rand.nextDouble() * Math.PI * 2;
        return new float[] { (float) (Math.cos(lat) * Math.cos(lon)), (float) Math.sin(lat), (float) (Math.cos(lat) * Math.sin(lon)) };
    }

    /** East and north on the sphere at a point. */
    private static float[][] tangents(float[] at) {
        float[] up = Math.abs(at[1]) < 0.95f ? new float[] { 0f, 1f, 0f } : new float[] { 1f, 0f, 0f };
        float[] east = normalise(cross(up, at));
        return new float[][] { east, cross(at, east) };
    }

    /** The point reached going from a point on the sphere a given angle along a bearing (0 east, turning north). */
    private static float[] along(float[] from, float bearing, float angle) {
        float[][] t = tangents(from);
        float c = (float) Math.cos(bearing), sn = (float) Math.sin(bearing);
        float[] dir = { t[0][0] * c + t[1][0] * sn, t[0][1] * c + t[1][1] * sn, t[0][2] * c + t[1][2] * sn };
        float ca = (float) Math.cos(angle), sa = (float) Math.sin(angle);
        return normalise(new float[] { from[0] * ca + dir[0] * sa, from[1] * ca + dir[1] * sa, from[2] * ca + dir[2] * sa });
    }

    /** The bearing at one point (to) of the way on from another nearby (from), in to's east and north. */
    private static float bearing(float[] from, float[] to) {
        float[][] t = tangents(to);
        float[] d = { to[0] - from[0], to[1] - from[1], to[2] - from[2] };
        return (float) Math.atan2(dot(d, t[1]), dot(d, t[0]));
    }

    /** How far from an oval's middle its edge is in a direction (angle from its long axis). */
    private static float edgeDistance(float major, float minor, float angle) {
        float c = (float) Math.cos(angle), sn = (float) Math.sin(angle);
        return 1f / (float) Math.sqrt(c * c / (major * major) + sn * sn / (minor * minor));
    }

    /** A piece of land or sea at a point, its long axis on a bearing, with half-sizes as angles. */
    private static float[] piece(float[] middle, float bearing, float major, float minor, float taper, boolean cut) {
        float[][] t = tangents(middle);
        float c = (float) Math.cos(bearing), sn = (float) Math.sin(bearing);
        float[] axis = new float[3], side = new float[3];
        for (int i = 0; i < 3; i++) {
            axis[i] = t[0][i] * c + t[1][i] * sn;
            side[i] = -t[0][i] * sn + t[1][i] * c;
        }
        float[] p = new float[FIELDS];
        System.arraycopy(middle, 0, p, MIDDLE, 3);
        System.arraycopy(axis, 0, p, AXIS, 3);
        System.arraycopy(side, 0, p, SIDE, 3);
        p[LONG] = (float) Math.sin(Math.min(1.4f, major));
        p[SHORT] = (float) Math.sin(Math.min(1.4f, minor));
        p[TAPER] = taper;
        p[CUT] = cut ? 1f : 0f;
        // Beyond twice its size (and the warp), it has no say
        p[REACH] = (float) Math.cos(Math.min(Math.PI * 0.5, Math.max(major, minor) * 2.2 + 0.12));
        return p;
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    private static float[] normalise(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[] { v[0] / l, v[1] / l, v[2] / l };
    }

    /**
     * High ground turns mountainous: sharp ridged noise (crests where the noise crosses zero,
     * each octave weighted by the one above so ridges branch into ridges) rising with
     * altitude, plus fine crags near the tops. Low ground is left alone.
     */
    private static float mountainRidges(float[] surface, float height, PerlinNoise noise) {
        float t = (height - 120.0f) / 380.0f;
        if (t <= 0.0f) return 0.0f;
        float mask = t >= 1.0f ? 1.0f : t * t * (3.0f - 2.0f * t);
        float total = 0.0f, weight = 1.0f;
        float frequency = 0.0016f, amplitude = 260.0f;
        for (int octave = 0; octave < 4; octave++) {
            // Offsets keep each octave's pattern independent of the other layers
            float n = noise.onSphere(surface, frequency, 37.1f * (octave + 1), -19.7f * (octave + 1));
            float ridge = 1.0f - Math.abs(n);
            ridge *= ridge;
            ridge *= weight;
            weight = Math.min(1.0f, ridge * 1.6f);
            total += ridge * amplitude;
            frequency *= 2.3f;
            amplitude *= 0.5f;
        }
        float crags = (1.0f - Math.abs(noise.onSphere(surface, 0.035f, 11.3f, -5.9f))) * 10.0f;
        return mask * (total - 80.0f + crags * mask);
    }

    // Calculates real normal data based on terrain elevation changes
    private static float[] calculateNormal(float x, float z, PerlinNoise noise) {
        float h = 0.1f; 
        float heightL = getLayeredHeight(x - h, z, noise);
        float heightR = getLayeredHeight(x + h, z, noise);
        float heightD = getLayeredHeight(x, z - h, noise);
        float heightU = getLayeredHeight(x, z + h, noise);

        float nx = heightL - heightR;
        float ny = 1.0f; // Reset to 1.0f for true height scaling
        float nz = heightD - heightU;

        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) return new float[]{0, 1, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }

    public static Mesh generateTerrainChunk(GL3 gl, int segments, float scale, int chunkX, int chunkZ, PerlinNoise noise) {
        Object[] data = buildChunkData(segments, scale, chunkX, chunkZ, noise);
        return new Mesh(gl, (float[]) data[0], (int[]) data[1]);
    }

    /**
     * A terrain chunk's vertices and indices, {float[], int[]}, without touching the GPU, so
     * it can be worked out on another thread and only uploaded on the GL thread. Heights are
     * sampled once per grid point (and a border round it), normals taken from neighbours.
     */
    public static Object[] buildChunkData(int segments, float scale, int chunkX, int chunkZ, PerlinNoise noise) {
        int coreVertices = (segments + 1) * (segments + 1);
        int skirtVerticesCount = (segments + 1) * 4 - 4; // Safely drops corners
        
        float[] vertices = new float[(coreVertices + skirtVerticesCount) * 8]; 
        float chunkSize = segments * scale;
        float globalStartX = chunkX * chunkSize;
        float globalStartZ = chunkZ * chunkSize;

        int vertexIndex = 0;

        // Heights on the grid and a one-step border round it
        int side = segments + 3;
        float[] grid = new float[side * side];
        for (int z = -1; z <= segments + 1; z++) {
            for (int x = -1; x <= segments + 1; x++) {
                grid[(z + 1) * side + (x + 1)] = getLayeredHeight(globalStartX + (x * scale) - (chunkSize / 2.0f),
                        globalStartZ + (z * scale) - (chunkSize / 2.0f), noise);
            }
        }
        // Normals from the neighbours' heights, flattened as before (the slope across 0.2 units against 1 up)
        float flatten = 0.1f / scale;

        // --- Step 1: Generate Standard Core Grid ---
        for (int z = 0; z <= segments; z++) {
            for (int x = 0; x <= segments; x++) {
                float worldX = globalStartX + (x * scale) - (chunkSize / 2.0f);
                float worldZ = globalStartZ + (z * scale) - (chunkSize / 2.0f);
                int g = (z + 1) * side + (x + 1);
                float worldY = grid[g];
                float gx = (grid[g - 1] - grid[g + 1]) * flatten, gz = (grid[g - side] - grid[g + side]) * flatten;
                float glen = (float) Math.sqrt(gx * gx + 1f + gz * gz);
                float[] normal = { gx / glen, 1f / glen, gz / glen };

                vertices[vertexIndex++] = worldX;
                vertices[vertexIndex++] = worldY; 
                vertices[vertexIndex++] = worldZ;
                
                vertices[vertexIndex++] = normal[0]; 
                vertices[vertexIndex++] = normal[1]; 
                vertices[vertexIndex++] = normal[2];
                
                vertices[vertexIndex++] = (float) x / segments; 
                vertices[vertexIndex++] = (float) z / segments; 
            }
        }

        int numCoreIndices = segments * segments * 6;
        int numSkirtIndices = segments * 4 * 6; 
        int[] indices = new int[numCoreIndices + numSkirtIndices];
        
        int indexPointer = 0;
        for (int z = 0; z < segments; z++) {
            for (int x = 0; x < segments; x++) {
                int topLeft = (z * (segments + 1)) + x;
                int topRight = topLeft + 1;
                int bottomLeft = ((z + 1) * (segments + 1)) + x;
                int bottomRight = bottomLeft + 1;
                indices[indexPointer++] = topLeft; indices[indexPointer++] = bottomLeft; indices[indexPointer++] = topRight;
                indices[indexPointer++] = topRight; indices[indexPointer++] = bottomLeft; indices[indexPointer++] = bottomRight;
            }
        }

        // --- Step 2: Generate Skirt Vertices & Stitch Walls ---
        float skirtDepth = 60.0f; 
        int skirtVertexCounter = 0;
        int skirtStartVertexIdx = coreVertices; 

        int[] northSkirtIndices = new int[segments + 1];
        int[] southSkirtIndices = new int[segments + 1];
        int[] westSkirtIndices  = new int[segments + 1];
        int[] eastSkirtIndices  = new int[segments + 1];

        for (int z = 0; z <= segments; z++) {
            for (int x = 0; x <= segments; x++) {
                if (x == 0 || x == segments || z == 0 || z == segments) {
                    int coreVertexID = (z * (segments + 1)) + x;
                    int coreStride = coreVertexID * 8;
                    int currentSkirtVertexID = skirtStartVertexIdx + skirtVertexCounter;
                    
                    if (z == 0) northSkirtIndices[x] = currentSkirtVertexID;
                    if (z == segments) southSkirtIndices[x] = currentSkirtVertexID;
                    if (x == 0) westSkirtIndices[z] = currentSkirtVertexID;
                    if (x == segments) eastSkirtIndices[z] = currentSkirtVertexID;
                    
                    skirtVertexCounter++;

                    vertices[vertexIndex++] = vertices[coreStride + 0]; 
                    vertices[vertexIndex++] = vertices[coreStride + 1] - skirtDepth; 
                    vertices[vertexIndex++] = vertices[coreStride + 2]; 
                    
                    for(int k = 3; k < 8; k++) { 
                        vertices[vertexIndex++] = vertices[coreStride + k]; 
                    }
                }
            }
        }

        // --- Step 3: Stitching Skirt Walls ---
        // North Wall
        for (int x = 0; x < segments; x++) {
            indices[indexPointer++] = x; 
            indices[indexPointer++] = northSkirtIndices[x]; 
            indices[indexPointer++] = x + 1;
            
            indices[indexPointer++] = x + 1; 
            indices[indexPointer++] = northSkirtIndices[x]; 
            indices[indexPointer++] = northSkirtIndices[x + 1];
        }

        // South Wall
        for (int x = 0; x < segments; x++) {
            int cCurr = (segments * (segments + 1)) + x;
            indices[indexPointer++] = cCurr; 
            indices[indexPointer++] = cCurr + 1; 
            indices[indexPointer++] = southSkirtIndices[x];
            
            indices[indexPointer++] = cCurr + 1; 
            indices[indexPointer++] = southSkirtIndices[x + 1]; 
            indices[indexPointer++] = southSkirtIndices[x];
        }

        // West Wall
        for (int z = 0; z < segments; z++) {
            int cCurr = z * (segments + 1);
            int cNext = (z + 1) * (segments + 1);
            indices[indexPointer++] = cCurr; 
            indices[indexPointer++] = cNext; 
            indices[indexPointer++] = westSkirtIndices[z];
            
            indices[indexPointer++] = cNext; 
            indices[indexPointer++] = westSkirtIndices[z + 1]; 
            indices[indexPointer++] = westSkirtIndices[z];
        }

        // East Wall
        for (int z = 0; z < segments; z++) {
            int cCurr = (z * (segments + 1)) + segments;
            int cNext = ((z + 1) * (segments + 1)) + segments;
            indices[indexPointer++] = cCurr; 
            indices[indexPointer++] = eastSkirtIndices[z]; 
            indices[indexPointer++] = cNext;
            
            indices[indexPointer++] = cNext; 
            indices[indexPointer++] = eastSkirtIndices[z]; 
            indices[indexPointer++] = eastSkirtIndices[z + 1];
        }

        return new Object[] { vertices, indices };
    }
}