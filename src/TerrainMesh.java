import java.util.List;

public class TerrainMesh {

    /**
     * The ground's height at map point (mapX, mapZ), for the map (see MapProjection). Every
     * layer is noise sampled at that place on the planet's surface, so the land runs on round
     * the world with no seam where the map's east and west edges meet.
     */
    public static float getLayeredHeight(float mapX, float mapZ, PerlinNoise noise) {
        return getLayeredHeight(MapProjection.surface(mapX, mapZ), noise);
    }

    private static float getLayeredHeight(float[] surface, PerlinNoise noise) {
        return getLayeredHeight(surface, noise, true);
    }

    private static float getLayeredHeight(float[] surface, PerlinNoise noise, boolean withRivers) {
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
        float polar = Math.max(0f, Math.min(1f, (sinLatitude - 0.7f) / 0.3f));
        baseHeight -= 100f * polar * polar * (3f - 2f * polar);

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
        height = flattenLowlands(height, surface, continent, noise);
        // The natural landmarks' pinnacles, stacks and spit (once they've been worked out from the bare land)
        Landforms landforms = noise.landforms;
        if (landforms != null) height = landforms.apply(surface, height);
        // The rivers' channels cut into it (once they've been worked out from the bare land)
        Rivers rivers = noise.rivers;
        return rivers == null || !withRivers ? height : rivers.carve(surface[0], surface[1], surface[2], height);
    }

    /**
     * The ground's height above the datum under a place on the sphere: the one terrain height
     * everything in the world uses (the chart's getLayeredHeight(x, z) goes through the same
     * sampling, for the map). The position's own altitude makes no difference.
     */
    public static float getLayeredHeight(SpherePosition position, PerlinNoise noise) {
        double length = position.length();
        if (!(length > 0.0)) throw new IllegalArgumentException("Terrain cannot be sampled at the planet centre");
        float[] surface = {
                (float) (position.x() * Planet.radius() / length),
                (float) (position.y() * Planet.radius() / length),
                (float) (position.z() * Planet.radius() / length)
        };
        return getLayeredHeight(surface, noise);
    }

    /** The ground's height above the datum under a place as it was before the rivers' channels were cut into it (what a bridge spans). */
    public static float getBareHeight(SpherePosition position, PerlinNoise noise) {
        double length = position.length();
        if (!(length > 0.0)) throw new IllegalArgumentException("Terrain cannot be sampled at the planet centre");
        float[] surface = {
                (float) (position.x() * Planet.radius() / length),
                (float) (position.y() * Planet.radius() / length),
                (float) (position.z() * Planet.radius() / length)
        };
        return getLayeredHeight(surface, noise, false);
    }

    /** The ground's height above the datum in a direction from the planet's centre. */
    public static float getLayeredHeight(GeodesicChunkGrid.Direction direction, PerlinNoise noise) {
        return getLayeredHeight(new SpherePosition(direction.x(), direction.y(), direction.z()), noise);
    }

    /** The point on the ground straight below (or above) a place. */
    public static SpherePosition groundAt(SpherePosition position, PerlinNoise noise) {
        return SphereMath.atAltitude(position, getLayeredHeight(position, noise));
    }

    /**
     * Builds a triangular geodesic terrain cell. Vertices are stored
     * in planet-centred Cartesian coordinates and transformed by the sphere-aware vertex shader.
     */
    public static MeshData buildGeodesicCellData(GeodesicChunkGrid.Cell cell, int segments, PerlinNoise noise) {
        if (segments < 1) throw new IllegalArgumentException("A geodesic terrain cell needs at least one segment");
        // (finer where a river's channel is cut, its edges stitched to coarser neighbours)
        segments = segmentsFor(cell, noise, segments);
        boolean[] coarse = coarseEdges(cell, segments, noise);
        int vertexCount = (segments + 1) * (segments + 2) / 2;
        float[] vertices = new float[vertexCount * 8];
        float[][] heights = new float[segments + 1][];
        for (int row = 0; row <= segments; row++) {
            heights[row] = new float[row + 1];
            for (int column = 0; column <= row; column++) {
                heights[row][column] = getLayeredHeight(meshVertex(cell, segments, row, column), noise);
            }
        }
        final int s = segments;
        VertexHeight raw = (row, column) -> heights[row][column];
        int vertex = 0;
        for (int row = 0; row <= segments; row++) {
            for (int column = 0; column <= row; column++) {
                GeodesicChunkGrid.Direction direction = meshVertex(cell, segments, row, column);
                float height = stitchedHeight(s, row, column, coarse, raw);
                float radius = (float) (Planet.radius() + height);
                vertices[vertex++] = (float) direction.x() * radius;
                vertices[vertex++] = (float) direction.y() * radius;
                vertices[vertex++] = (float) direction.z() * radius;
                vertices[vertex++] = (float) direction.x();
                vertices[vertex++] = (float) direction.y();
                vertices[vertex++] = (float) direction.z();
                // (the corners' shares, inset in the soil's triangular tile: see ProceduralTextures.triangleTile)
                float margin = (float) ProceduralTextures.TILE_MARGIN, inner = 1f - 2f * margin;
                vertices[vertex++] = margin + inner * ((float) (row - column) / segments + (float) column / segments);
                vertices[vertex++] = margin + inner * (float) column / segments;
            }
        }

        int triangleCount = segments * segments;
        int[] indices = new int[triangleCount * 3];
        int index = 0;
        for (int row = 0; row < segments; row++) {
            int rowStart = row * (row + 1) / 2;
            int nextRowStart = (row + 1) * (row + 2) / 2;
            for (int column = 0; column <= row; column++) {
                int top = rowStart + column;
                int lowerLeft = nextRowStart + column;
                int lowerRight = lowerLeft + 1;
                indices[index++] = top;
                indices[index++] = lowerLeft;
                indices[index++] = lowerRight;
                if (column < row) {
                    indices[index++] = top;
                    indices[index++] = top + 1;
                    indices[index++] = lowerRight;
                }
            }
        }
        orientTrianglesOutward(vertices, indices);
        smoothGeodesicNormals(vertices, indices);
        return new MeshData(vertices, indices);
    }

    /** The ground as drawn: cells of this level of the geodesic grid, each cut into this many segments along an edge. */
    public static final int DRAWN_LEVEL = 6, DRAWN_SEGMENTS = 32;
    private static final GeodesicChunkGrid DRAWN_GRID = new GeodesicChunkGrid(DRAWN_LEVEL);

    /**
     * The height above the datum of the ground as it's drawn in a direction (see meshHeight),
     * for setting things on it so none hovers. Vertex heights are kept in cache (if given) by
     * cell, row and column, as neighbours share them.
     */
    public static float drawnHeight(GeodesicChunkGrid.Direction direction, PerlinNoise noise, java.util.Map<Long, Float> cache) {
        GeodesicChunkGrid.Cell cell = DRAWN_GRID.cellAt(direction);
        int segments = segmentsFor(cell, noise, DRAWN_SEGMENTS);
        boolean[] coarse = coarseEdges(cell, segments, noise);
        VertexHeight raw = (row, column) -> {
            if (cache == null) return getLayeredHeight(meshVertex(cell, segments, row, column), noise);
            return cache.computeIfAbsent((cell.id() << 20) | ((long) row << 10) | column,
                    key -> getLayeredHeight(meshVertex(cell, segments, row, column), noise));
        };
        return (float) meshHeight(cell, segments, direction, (row, column) -> stitchedHeight(segments, row, column, coarse, raw));
    }

    // Where a river's channel is cut, the ground is drawn this much finer (twice: every other
    // vertex along an edge is one of the coarse cell's), so the channel shows
    public static final int RIVER_SEGMENTS = DRAWN_SEGMENTS * 2;

    /** How many segments a cell of the drawn ground is cut into: finer if a river's channel is cut into it. */
    public static int segmentsFor(GeodesicChunkGrid.Cell cell, PerlinNoise noise, int usual) {
        Rivers rivers = noise.rivers;
        return rivers != null && cell.level() == DRAWN_LEVEL && rivers.cuts(cell.id()) ? usual * 2 : usual;
    }

    /**
     * For a cell drawn finer than usual, which of its edges (ab, bc, ca) border a cell drawn
     * coarser, so their in-between vertices are laid along the coarse cell's edge and no crack
     * opens between them; null if it isn't finer.
     */
    private static boolean[] coarseEdges(GeodesicChunkGrid.Cell cell, int segments, PerlinNoise noise) {
        Rivers rivers = noise.rivers;
        if (rivers == null || segments <= DRAWN_SEGMENTS || cell.level() != DRAWN_LEVEL) return null;
        return rivers.coarseEdges.computeIfAbsent(cell.id(), id -> {
            GeodesicChunkGrid.Direction[] corner = { cell.a(), cell.b(), cell.c() };
            GeodesicChunkGrid.Direction centre = cell.centre();
            boolean[] coarse = new boolean[3];
            for (int e = 0; e < 3; e++) {
                GeodesicChunkGrid.Direction p = corner[e], q = corner[(e + 1) % 3];
                double mx = (p.x() + q.x()) * 0.5, my = (p.y() + q.y()) * 0.5, mz = (p.z() + q.z()) * 0.5;
                // (just across the edge's middle, away from this cell's)
                GeodesicChunkGrid.Direction across = new GeodesicChunkGrid.Direction(mx + (mx - centre.x()) * 0.05,
                        my + (my - centre.y()) * 0.05, mz + (mz - centre.z()) * 0.05).normalized();
                coarse[e] = !rivers.cuts(DRAWN_GRID.cellAt(across).id());
            }
            return coarse;
        });
    }

    /**
     * A vertex's height as drawn: the ground's own, except that on an edge bordering a coarser
     * cell the in-between vertices lie halfway between their neighbours along it (ab is column
     * 0, bc row segments, ca the diagonal column = row).
     */
    private static float stitchedHeight(int segments, int row, int column, boolean[] coarse, VertexHeight raw) {
        if (coarse != null) {
            if (column == 0 && coarse[0] && row % 2 == 1) return (raw.at(row - 1, 0) + raw.at(row + 1, 0)) * 0.5f;
            if (row == segments && coarse[1] && column % 2 == 1) return (raw.at(row, column - 1) + raw.at(row, column + 1)) * 0.5f;
            if (column == row && coarse[2] && row % 2 == 1) return (raw.at(row - 1, row - 1) + raw.at(row + 1, row + 1)) * 0.5f;
        }
        return raw.at(row, column);
    }

    public static float drawnHeight(SpherePosition place, PerlinNoise noise, java.util.Map<Long, Float> cache) {
        return drawnHeight(SphereMath.direction(place), noise, cache);
    }

    /** A cell's mesh vertex (as buildGeodesicCellData lays them out), as a direction from the planet's centre. */
    public static GeodesicChunkGrid.Direction meshVertex(GeodesicChunkGrid.Cell cell, int segments, int row, int column) {
        double wa = (segments - row) / (double) segments;
        double wb = (row - column) / (double) segments;
        double wc = column / (double) segments;
        GeodesicChunkGrid.Direction a = cell.a(), b = cell.b(), c = cell.c();
        return new GeodesicChunkGrid.Direction(
                wa * a.x() + wb * b.x() + wc * c.x(),
                wa * a.y() + wb * b.y() + wc * c.y(),
                wa * a.z() + wb * b.z() + wc * c.z()).normalized();
    }

    /** The ground's height at a mesh vertex (row, column) of a cell. */
    @FunctionalInterface
    public interface VertexHeight {
        float at(int row, int column);
    }

    /**
     * The height above the datum of the ground as the mesh draws it (buildGeodesicCellData, with
     * the same segments) in a direction inside the cell: where the ray meets the flat triangle
     * there, not the true terrain, which the triangles cut below on humps and above in hollows.
     */
    public static double meshHeight(GeodesicChunkGrid.Cell cell, int segments, GeodesicChunkGrid.Direction direction, VertexHeight heights) {
        GeodesicChunkGrid.Address address = cell.address(direction);
        double r = Math.max(0.0, Math.min(segments, segments * (address.b() + address.c())));
        double c = Math.max(0.0, Math.min(r, segments * address.c()));
        int row = Math.min(segments - 1, (int) Math.floor(r));
        int column = Math.min(row, (int) Math.floor(c));
        double fr = r - row, fc = c - column;
        // (the square between rows is cut corner to corner: below the cut, top / lower left /
        // lower right; above it, top / top's right / lower right)
        int[][] corners = fc <= fr || column >= row
                ? new int[][] { { row, column }, { row + 1, column }, { row + 1, column + 1 } }
                : new int[][] { { row, column }, { row, column + 1 }, { row + 1, column + 1 } };
        double[][] p = new double[3][];
        for (int k = 0; k < 3; k++) {
            GeodesicChunkGrid.Direction v = meshVertex(cell, segments, corners[k][0], corners[k][1]);
            double radius = Planet.radius() + heights.at(corners[k][0], corners[k][1]);
            p[k] = new double[] { v.x() * radius, v.y() * radius, v.z() * radius };
        }
        double ux = p[1][0] - p[0][0], uy = p[1][1] - p[0][1], uz = p[1][2] - p[0][2];
        double vx = p[2][0] - p[0][0], vy = p[2][1] - p[0][1], vz = p[2][2] - p[0][2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        GeodesicChunkGrid.Direction d = direction.normalized();
        double along = nx * d.x() + ny * d.y() + nz * d.z();
        if (Math.abs(along) < 1e-12) return heights.at(row, column);
        return (nx * p[0][0] + ny * p[0][1] + nz * p[0][2]) / along - Planet.radius();
    }

    private static void smoothGeodesicNormals(float[] vertices, int[] indices) {
        for (int i = 0; i < vertices.length; i += 8) {
            vertices[i + 3] = 0f;
            vertices[i + 4] = 0f;
            vertices[i + 5] = 0f;
        }
        for (int i = 0; i < indices.length; i += 3) {
            int a = indices[i] * 8, b = indices[i + 1] * 8, c = indices[i + 2] * 8;
            float abx = vertices[b] - vertices[a], aby = vertices[b + 1] - vertices[a + 1],
                    abz = vertices[b + 2] - vertices[a + 2];
            float acx = vertices[c] - vertices[a], acy = vertices[c + 1] - vertices[a + 1],
                    acz = vertices[c + 2] - vertices[a + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            vertices[a + 3] += nx; vertices[a + 4] += ny; vertices[a + 5] += nz;
            vertices[b + 3] += nx; vertices[b + 4] += ny; vertices[b + 5] += nz;
            vertices[c + 3] += nx; vertices[c + 4] += ny; vertices[c + 5] += nz;
        }
        for (int i = 0; i < vertices.length; i += 8) {
            float nx = vertices[i + 3], ny = vertices[i + 4], nz = vertices[i + 5];
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-8f) {
                nx = vertices[i]; ny = vertices[i + 1]; nz = vertices[i + 2];
                length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            }
            vertices[i + 3] = nx / length;
            vertices[i + 4] = ny / length;
            vertices[i + 5] = nz / length;
        }
    }

    private static void orientTrianglesOutward(float[] vertices, int[] indices) {
        for (int i = 0; i < indices.length; i += 3) {
            int a = indices[i] * 8, b = indices[i + 1] * 8, c = indices[i + 2] * 8;
            double abx = vertices[b] - vertices[a];
            double aby = vertices[b + 1] - vertices[a + 1];
            double abz = vertices[b + 2] - vertices[a + 2];
            double acx = vertices[c] - vertices[a];
            double acy = vertices[c + 1] - vertices[a + 1];
            double acz = vertices[c + 2] - vertices[a + 2];
            double outward = (aby * acz - abz * acy) * vertices[a]
                    + (abz * acx - abx * acz) * vertices[a + 1]
                    + (abx * acy - aby * acx) * vertices[a + 2];
            if (outward < 0.0) {
                int swap = indices[i + 1];
                indices[i + 1] = indices[i + 2];
                indices[i + 2] = swap;
            }
        }
    }

    /** Builds one merged geodesic terrain patch. */
    public static MeshData buildGeodesicPatchData(GeodesicChunkGrid grid, SpherePosition centre,
            double angularRadius, int segments, PerlinNoise noise) {
        return buildGeodesicPatchData(grid, centre, angularRadius, segments, noise, null);
    }

    /** The same, of only the cells within reach that only lets through (those in a photograph's view: see LandmarkPhotos), or all with null. */
    public static MeshData buildGeodesicPatchData(GeodesicChunkGrid grid, SpherePosition centre,
            double angularRadius, int segments, PerlinNoise noise, java.util.function.Predicate<GeodesicChunkGrid.Cell> only) {
        if (segments < 1) throw new IllegalArgumentException("A geodesic terrain patch needs at least one segment");
        List<GeodesicChunkGrid.Cell> cells = grid.cellsWithin(
                new GeodesicChunkGrid.Direction(centre.x(), centre.y(), centre.z()), angularRadius);
        if (only != null) cells.removeIf(only.negate());
        if (cells.isEmpty()) throw new IllegalStateException("Geodesic terrain patch contains no cells");

        // (cells may be cut into different numbers of segments: see segmentsFor)
        List<MeshData> built = new java.util.ArrayList<>(cells.size());
        int vertexFloats = 0, indexCount = 0;
        for (GeodesicChunkGrid.Cell cell : cells) {
            MeshData cellData = buildGeodesicCellData(cell, segments, noise);
            built.add(cellData);
            vertexFloats += cellData.vertices().length;
            indexCount += cellData.indices().length;
        }
        float[] vertices = new float[vertexFloats];
        int[] indices = new int[indexCount];
        int vertexOffset = 0;
        int indexOffset = 0;
        for (MeshData cellData : built) {
            float[] cellVertices = cellData.vertices();
            int[] cellIndices = cellData.indices();
            System.arraycopy(cellVertices, 0, vertices, vertexOffset * 8, cellVertices.length);
            for (int i = 0; i < cellIndices.length; i++) {
                indices[indexOffset + i] = cellIndices[i] + vertexOffset;
            }
            vertexOffset += cellVertices.length / 8;
            indexOffset += cellIndices.length;
        }
        return new MeshData(vertices, indices);
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
                float[] d = randomDirection(rand, 65);
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
            float[] at = randomDirection(rand, 70);
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
            float[] at = randomDirection(rand, 72);
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
}