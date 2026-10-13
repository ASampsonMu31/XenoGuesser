import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A hierarchical triangular grid over the whole sphere. Each subdivision normalizes the
 * midpoint of a triangle edge back to the sphere, so cell size is independent of latitude.
 */
public final class GeodesicChunkGrid {

    public static final int DEFAULT_LEVEL = 8;
    private static final int MAX_LEVEL = 26;
    private static final double EDGE_EPSILON = 1e-12;

    public record Direction(double x, double y, double z) {
        public Direction normalized() {
            double length = Math.sqrt(x * x + y * y + z * z);
            if (!(length > 0.0) || !Double.isFinite(length)) {
                throw new IllegalArgumentException("Cannot normalize a zero or invalid direction");
            }
            return new Direction(x / length, y / length, z / length);
        }

        public double dot(Direction other) {
            return x * other.x + y * other.y + z * other.z;
        }

        public double angleTo(Direction other) {
            return Math.acos(Math.max(-1.0, Math.min(1.0, dot(other))));
        }
    }

    /** Triangle address plus barycentric coordinates, suitable for stable cell-local storage. */
    public record Address(long cellId, int level, double a, double b, double c) {
    }

    /** One geodesic triangle, with unit-vector vertices and its normalized centre. */
    public record Cell(long id, int level, Direction a, Direction b, Direction c, Direction centre) {
        public double angularRadius() {
            return Math.max(centre.angleTo(a), Math.max(centre.angleTo(b), centre.angleTo(c)));
        }

        /**
         * The cell's area on the unit sphere (its spherical excess, in steradians); times the
         * radius squared, its area on a sphere. Cells of a level differ in area by a few tens
         * of percent, so counts per area are worked out from each cell's own.
         */
        public double area() {
            double denominator = 1.0 + a.dot(b) + b.dot(c) + c.dot(a);
            return 2.0 * Math.abs(Math.atan2(determinant(a, b, c), denominator));
        }

        /**
         * A place in the cell from two numbers 0 to 1, spread evenly over it when they are
         * (u picks how far from corner a, v where across).
         */
        public Direction point(double u, double v) {
            double root = Math.sqrt(u);
            double wa = 1.0 - root, wb = root * (1.0 - v), wc = root * v;
            return new Direction(wa * a.x() + wb * b.x() + wc * c.x(),
                    wa * a.y() + wb * b.y() + wc * c.y(),
                    wa * a.z() + wb * b.z() + wc * c.z()).normalized();
        }

        public Address address(Direction point) {
            Direction p = point.normalized();
            double det = determinant(a, b, c);
            double wa = determinant(p, b, c) / det;
            double wb = determinant(a, p, c) / det;
            double wc = determinant(a, b, p) / det;
            double total = wa + wb + wc;
            return new Address(id, level, wa / total, wb / total, wc / total);
        }
    }

    private record Triangle(Direction a, Direction b, Direction c) {
    }

    private static final double PHI = (1.0 + Math.sqrt(5.0)) / 2.0;
    private static final Direction[] VERTICES = {
        v(-1, PHI, 0), v(1, PHI, 0), v(-1, -PHI, 0), v(1, -PHI, 0),
        v(0, -1, PHI), v(0, 1, PHI), v(0, -1, -PHI), v(0, 1, -PHI),
        v(PHI, 0, -1), v(PHI, 0, 1), v(-PHI, 0, -1), v(-PHI, 0, 1)
    };
    private static final int[][] FACES = {
        {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
        {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
        {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
        {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
    };

    private final int level;

    public GeodesicChunkGrid() {
        this(DEFAULT_LEVEL);
    }

    public GeodesicChunkGrid(int level) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Geodesic subdivision level must be between 0 and " + MAX_LEVEL);
        }
        this.level = level;
    }

    public int level() {
        return level;
    }

    public long cellCount() {
        return 20L << (2 * level);
    }

    /** Finds the triangle containing a unit direction or a spherical world-space position. */
    public Cell cellAt(Direction direction) {
        Direction p = direction.normalized();
        for (int face = 0; face < FACES.length; face++) {
            Triangle root = face(face);
            if (contains(root, p)) return descend(face, root, p);
        }
        throw new IllegalArgumentException("Direction is outside the geodesic sphere");
    }

    public Cell cellAt(SpherePosition position) {
        return cellAt(new Direction(position.x(), position.y(), position.z()));
    }

    public Cell cell(long id) {
        long faceBits = id >>> (2 * level);
        if (faceBits >= FACES.length) throw new IllegalArgumentException("Invalid geodesic cell id");
        long pathMask = level == 0 ? 0L : (1L << (2 * level)) - 1L;
        long path = id & pathMask;
        Triangle triangle = face((int) faceBits);
        for (int depth = level - 1; depth >= 0; depth--) {
            int child = (int) ((path >>> (depth * 2)) & 3L);
            triangle = child(triangle, child);
        }
        return cell(id, level, triangle);
    }

    /** Finds a cell and expresses the location as barycentric coordinates inside that cell. */
    public Address address(Direction point) {
        return cellAt(point).address(point);
    }

    /**
     * Returns connected cells intersecting the requested angular neighbourhood. The boundary
     * is conservatively included by one cell circumradius to avoid holes in streamed coverage.
     */
    public List<Cell> cellsWithin(Direction centre, double angularRadius) {
        if (!Double.isFinite(angularRadius) || angularRadius < 0.0) {
            throw new IllegalArgumentException("Angular radius must be finite and non-negative");
        }
        Direction origin = centre.normalized();
        Cell start = cellAt(origin);
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<Cell> result = new ArrayList<>();
        queue.add(start);
        visited.add(start.id());
        while (!queue.isEmpty()) {
            Cell cell = queue.removeFirst();
            double distance = origin.angleTo(cell.centre());
            double cellRadius = cell.angularRadius();
            if (distance <= angularRadius + cellRadius) result.add(cell);
            if (distance > angularRadius + 3.0 * cellRadius) continue;
            for (Cell neighbour : neighbours(cell)) {
                if (visited.add(neighbour.id())) queue.addLast(neighbour);
            }
        }
        return result;
    }

    /** Three edge-adjacent cells at the same subdivision level. */
    public List<Cell> neighbours(Cell cell) {
        if (cell.level() != level) throw new IllegalArgumentException("Cell belongs to a different subdivision level");
        Direction[] vertices = { cell.a(), cell.b(), cell.c() };
        List<Cell> neighbours = new ArrayList<>(3);
        for (int edge = 0; edge < 3; edge++) {
            Direction a = vertices[edge], b = vertices[(edge + 1) % 3];
            Direction midpoint = add(a, b).normalized();
            Direction towardCentre = subtract(cell.centre(), scale(midpoint, cell.centre().dot(midpoint)));
            double edgeAngle = a.angleTo(b);
            Direction outside = subtract(midpoint, scale(towardCentre.normalized(), Math.max(EDGE_EPSILON, edgeAngle * 1e-7))).normalized();
            Cell neighbour = cellAt(outside);
            if (neighbour.id() != cell.id()) neighbours.add(neighbour);
        }
        return neighbours;
    }

    /** Reconstructs a point from a cell address, returning a point on the unit sphere. */
    public Direction resolve(Address address) {
        if (address.level() != level) throw new IllegalArgumentException("Address belongs to a different subdivision level");
        Cell cell = cell(address.cellId());
        return new Direction(cell.a().x() * address.a() + cell.b().x() * address.b() + cell.c().x() * address.c(),
                cell.a().y() * address.a() + cell.b().y() * address.b() + cell.c().y() * address.c(),
                cell.a().z() * address.a() + cell.b().z() * address.b() + cell.c().z() * address.c()).normalized();
    }

    private Cell descend(int face, Triangle root, Direction point) {
        Triangle triangle = root;
        long path = 0L;
        for (int depth = 0; depth < level; depth++) {
            // (the edges' midpoints once, shared by the four children: exactly as child() makes them)
            Direction ab = add(triangle.a(), triangle.b()).normalized();
            Direction bc = add(triangle.b(), triangle.c()).normalized();
            Direction ca = add(triangle.c(), triangle.a()).normalized();
            Triangle[] children = {
                new Triangle(triangle.a(), ab, ca), new Triangle(ab, triangle.b(), bc),
                new Triangle(ca, bc, triangle.c()), new Triangle(ab, bc, ca)
            };
            int selected = 0;
            for (; selected < 4; selected++) {
                if (contains(children[selected], point)) break;
            }
            if (selected == 4) selected = 3;
            path = (path << 2) | selected;
            triangle = children[selected];
        }
        long id = ((long) face << (2 * level)) | path;
        return cell(id, level, triangle);
    }

    private static Triangle face(int index) {
        int[] face = FACES[index];
        return new Triangle(VERTICES[face[0]], VERTICES[face[1]], VERTICES[face[2]]);
    }

    private static Cell cell(long id, int level, Triangle triangle) {
        return new Cell(id, level, triangle.a(), triangle.b(), triangle.c(),
                add(add(triangle.a(), triangle.b()), triangle.c()).normalized());
    }

    private static Triangle child(Triangle t, int child) {
        Direction ab = add(t.a(), t.b()).normalized();
        Direction bc = add(t.b(), t.c()).normalized();
        Direction ca = add(t.c(), t.a()).normalized();
        return switch (child) {
            case 0 -> new Triangle(t.a(), ab, ca);
            case 1 -> new Triangle(ab, t.b(), bc);
            case 2 -> new Triangle(ca, bc, t.c());
            case 3 -> new Triangle(ab, bc, ca);
            default -> throw new IllegalArgumentException("Triangle child must be between 0 and 3");
        };
    }

    private static boolean contains(Triangle triangle, Direction point) {
        double ab = determinant(triangle.a(), triangle.b(), point);
        double bc = determinant(triangle.b(), triangle.c(), point);
        double ca = determinant(triangle.c(), triangle.a(), point);
        double orientation = determinant(triangle.a(), triangle.b(), triangle.c());
        if (orientation < 0.0) {
            return ab <= EDGE_EPSILON && bc <= EDGE_EPSILON && ca <= EDGE_EPSILON;
        }
        return ab >= -EDGE_EPSILON && bc >= -EDGE_EPSILON && ca >= -EDGE_EPSILON;
    }

    private static double determinant(Direction a, Direction b, Direction c) {
        return a.x() * (b.y() * c.z() - b.z() * c.y())
                - a.y() * (b.x() * c.z() - b.z() * c.x())
                + a.z() * (b.x() * c.y() - b.y() * c.x());
    }

    private static Direction v(double x, double y, double z) {
        return new Direction(x, y, z).normalized();
    }

    private static Direction add(Direction a, Direction b) {
        return new Direction(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }

    private static Direction subtract(Direction a, Direction b) {
        return new Direction(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    private static Direction scale(Direction a, double factor) {
        return new Direction(a.x() * factor, a.y() * factor, a.z() * factor);
    }
}
