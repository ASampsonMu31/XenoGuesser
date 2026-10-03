import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What each nation's shops sell: alien fruit and vegetables, and goods in packets, boxes
 * and tins. Every product's look lives in its nation's packaging texture (see PackagingArt),
 * a 4 by 4 grid of cells: the first eight are packet designs, the last eight the skins of
 * the produce, with a strip of leaf colour along each skin cell's foot. Product geometry
 * is built here once and shared by shop windows and the pictures on signs.
 */
public final class Products {

    public static final int GRID = 4;
    public static final int PACKETS = 8, PRODUCE = 8;

    public enum Shape { POD, LOBED, SPIKY, CLUSTER, TUBER, RIBBED, BULB, DISC }
    public enum Box { BOX, TALL_BOX, FLAT_BOX, TIN, POUCH }

    /** One kind of fruit or vegetable. */
    public static final class Produce {
        public Shape shape;
        public float size;          // roughly its longest dimension, in world units
        public float width;         // width over length
        public int lobes;
        public float lobeDepth;
        public float bend;
        public boolean leaves;
        public float[] colour, colour2, leafColour;
        public int pattern;         // 0 plain, 1 stripes, 2 spots, 3 speckles, 4 blush
        public int cell;            // its skin's cell in the packaging texture
    }

    /** One packaged product. */
    public static final class Packet {
        public Box box;
        public float width, height, depth;
        public int cell;            // its design's cell in the packaging texture
        public int produce = -1;    // the produce pictured on it, if any
    }

    private final List<List<Produce>> produce = new ArrayList<>();
    private final List<List<Packet>> packets = new ArrayList<>();

    public Products(long seed, int nations, NationKinship kinship) {
        produce.add(List.of());
        packets.add(List.of());
        for (int n = 1; n <= nations; n++) {
            Random rand = new Random(seed * 211L + n * 6151L + 7L);
            List<Produce> crops = new ArrayList<>();
            for (int i = 0; i < PRODUCE; i++) {
                Produce p = new Produce();
                p.shape = Shape.values()[rand.nextInt(Shape.values().length)];
                p.size = 1.2f + rand.nextFloat() * 2.2f;
                p.width = switch (p.shape) {
                    case POD, TUBER -> 0.3f + rand.nextFloat() * 0.25f;
                    case DISC -> 1.4f + rand.nextFloat() * 0.5f;
                    default -> 0.7f + rand.nextFloat() * 0.4f;
                };
                p.lobes = 3 + rand.nextInt(6);
                p.lobeDepth = 0.06f + rand.nextFloat() * 0.18f;
                p.bend = p.shape == Shape.POD ? 0.3f + rand.nextFloat() * 0.8f : rand.nextFloat() * 0.3f;
                p.leaves = rand.nextFloat() < 0.5f;
                // Strange, bright colours: no world's produce looks like ours
                float hue = rand.nextFloat();
                p.colour = WorldPalette.hsv(hue, 0.45f + rand.nextFloat() * 0.5f, 0.45f + rand.nextFloat() * 0.5f);
                p.colour2 = WorldPalette.hsv(hue + 0.15f + rand.nextFloat() * 0.6f, 0.4f + rand.nextFloat() * 0.5f, 0.3f + rand.nextFloat() * 0.65f);
                p.leafColour = WorldPalette.hsv(rand.nextFloat(), 0.4f + rand.nextFloat() * 0.4f, 0.25f + rand.nextFloat() * 0.45f);
                p.pattern = rand.nextInt(5);
                p.cell = PACKETS + i;
                crops.add(p);
            }
            produce.add(crops);
            List<Packet> goods = new ArrayList<>();
            for (int i = 0; i < PACKETS; i++) {
                Packet k = new Packet();
                k.box = Box.values()[rand.nextInt(Box.values().length)];
                float s = 1.6f + rand.nextFloat() * 1.6f;
                switch (k.box) {
                    case TALL_BOX -> { k.width = s * 0.6f; k.height = s * 1.3f; k.depth = s * 0.35f; }
                    case FLAT_BOX -> { k.width = s * 1.1f; k.height = s * 0.7f; k.depth = s * 0.3f; }
                    case TIN -> { k.width = s * 0.6f; k.height = s * 0.8f; k.depth = s * 0.6f; }
                    case POUCH -> { k.width = s * 0.75f; k.height = s * 1.0f; k.depth = s * 0.25f; }
                    default -> { k.width = s * 0.8f; k.height = s; k.depth = s * 0.45f; }
                }
                k.cell = i;
                k.produce = rand.nextFloat() < 0.55f ? rand.nextInt(PRODUCE) : -1;
                goods.add(k);
            }
            packets.add(goods);
        }
    }

    public List<Produce> produce(int nationId) {
        return nationId > 0 && nationId < produce.size() ? produce.get(nationId) : produce.get(1);
    }

    public List<Packet> packets(int nationId) {
        return nationId > 0 && nationId < packets.size() ? packets.get(nationId) : packets.get(1);
    }

    /** Where a cell's corner sits in the texture: {u, v} of its top-left. */
    public static float[] cellOrigin(int cell) {
        return new float[] { (cell % GRID) / (float) GRID, (cell / GRID) / (float) GRID };
    }

    // ==========================================
    //          GEOMETRY
    // ==========================================

    /**
     * Adds a piece of produce standing on the origin (its base at y = 0, growing up +Y),
     * scaled by scale, placed by the given transform, as PART_PRODUCT.
     */
    public static void buildProduce(OrganismMesh.Builder b, Produce p, float scale, float[] place) {
        float length = p.size * scale;
        float radius = length * 0.5f * p.width;
        float[] upright = Affine.multiply(place, Affine.rotationX((float) -Math.PI / 2));
        b.part(OrganismMesh.PART_PRODUCT);
        b.folds(null);
        int first = b.vertexCount();
        switch (p.shape) {
            case CLUSTER -> {
                // A bunch of small berries round a stem
                int berries = 5 + p.lobes;
                float r = length * 0.17f;
                Random rand = new Random(p.cell * 31L + p.lobes);
                for (int i = 0; i < berries; i++) {
                    float a = i * 2.4f;
                    float h = r + (1f - i / (float) berries) * (length - 2 * r) * 0.85f;
                    float spread = radius * 0.8f * (0.4f + 0.6f * (h / length));
                    b.transform(Affine.multiply(place, Affine.translation((float) Math.cos(a) * spread, h, (float) Math.sin(a) * spread)));
                    sphere(b, r * (0.85f + 0.3f * rand.nextFloat()));
                }
            }
            case SPIKY -> {
                b.transform(Affine.multiply(upright, Affine.translation(0f, 0f, 0f)));
                b.lathe(14, 10, (t, out) -> profileRound(t, out, length, radius));
                skin(b, first, p.cell);
                int spikes = 6 + p.lobes * 2;
                for (int i = 0; i < spikes; i++) {
                    double a = i * 2.39996;
                    float h = 0.2f + 0.6f * ((i * 0.618f) % 1f);
                    float ring = (float) Math.sin(Math.PI * h) * radius;
                    float[] at = { (float) Math.cos(a) * ring, h * length, (float) Math.sin(a) * ring };
                    float[] out = Affine.normalise(new float[] { at[0], (h - 0.5f) * radius * 1.5f, at[2] });
                    b.transform(Affine.multiply(place, Affine.frame(at, out, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f)));
                    int spikeFirst = b.vertexCount();
                    b.lathe(5, 3, (t, o) -> {
                        o[0] = 0f;
                        o[1] = 0f;
                        o[2] = t * radius * 0.6f;
                        o[3] = Math.max(0.001f, radius * 0.12f * (1f - t));
                        o[4] = o[3];
                    });
                    leafStrip(b, spikeFirst, p.cell);
                }
                b.transform(Affine.identity());
                return;
            }
            default -> {
                float lobeDepth = p.shape == Shape.LOBED || p.shape == Shape.RIBBED ? p.lobeDepth : p.shape == Shape.BULB ? 0.03f : 0f;
                int lobes = p.lobes;
                boolean sharp = p.shape == Shape.RIBBED;
                b.folds(lobeDepth > 0f ? (along, angle) -> {
                    float c = (float) Math.cos(angle * lobes);
                    return 1f + lobeDepth * (sharp ? Math.abs(c) * 2f - 1f : c);
                } : null);
                b.transform(upright);
                b.lathe(14, 10, (t, out) -> {
                    switch (p.shape) {
                        case POD -> {
                            float envelope = (float) Math.pow(Math.sin(Math.PI * t), 0.7);
                            out[0] = 0f;
                            out[1] = -p.bend * length * 0.35f * (float) Math.sin(Math.PI * t);
                            out[2] = t * length;
                            out[3] = Math.max(0.01f, radius * envelope);
                            out[4] = out[3];
                        }
                        case TUBER -> {
                            float envelope = (float) Math.pow(Math.sin(Math.PI * Math.min(1f, t * 1.15f)), 0.6) * (1.2f - 0.9f * t);
                            out[0] = 0f;
                            out[1] = p.bend * length * 0.3f * t * t;
                            out[2] = t * length;
                            out[3] = Math.max(0.01f, radius * 1.4f * envelope);
                            out[4] = out[3];
                        }
                        case DISC -> {
                            float envelope = (float) Math.sqrt(Math.max(0.0, Math.sin(Math.PI * t)));
                            out[0] = 0f;
                            out[1] = 0f;
                            out[2] = t * length * 0.45f;
                            out[3] = Math.max(0.01f, radius * envelope);
                            out[4] = out[3];
                        }
                        case BULB -> {
                            // Round below, drawn up into a point
                            float envelope = t < 0.6f ? (float) Math.sin(Math.PI * t / 1.2f) : (1f - t) / 0.4f;
                            out[0] = 0f;
                            out[1] = 0f;
                            out[2] = t * length;
                            out[3] = Math.max(0.01f, radius * envelope);
                            out[4] = out[3];
                        }
                        default -> profileRound(t, out, length, radius);
                    }
                });
                b.folds(null);
            }
        }
        skin(b, first, p.cell);
        if (p.leaves) {
            // A tuft of leaves (or fronds, or feelers) on top
            float top = p.shape == Shape.DISC ? length * 0.45f : length;
            for (int i = 0; i < 3; i++) {
                double a = i * Math.PI * 2 / 3 + p.lobes;
                float[] dir = Affine.normalise(new float[] { (float) Math.cos(a), 1.2f, (float) Math.sin(a) });
                b.transform(Affine.multiply(place, Affine.frame(new float[] { 0f, top * 0.97f, 0f }, dir, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f)));
                int leafFirst = b.vertexCount();
                b.lathe(6, 5, (t, out) -> {
                    out[0] = 0f;
                    out[1] = 0f;
                    out[2] = t * length * 0.45f;
                    out[3] = Math.max(0.005f, length * 0.12f * (float) Math.sin(Math.PI * t));
                    out[4] = Math.max(0.005f, length * 0.02f);
                });
                leafStrip(b, leafFirst, p.cell);
            }
        }
        b.transform(Affine.identity());
    }

    private static void profileRound(float t, float[] out, float length, float radius) {
        float envelope = (float) Math.sin(Math.PI * t);
        out[0] = 0f;
        out[1] = 0f;
        out[2] = t * length;
        out[3] = Math.max(0.01f, radius * envelope);
        out[4] = out[3];
    }

    private static void sphere(OrganismMesh.Builder b, float r) {
        b.lathe(8, 6, (t, out) -> {
            float envelope = (float) Math.sin(Math.PI * t);
            out[0] = 0f;
            out[1] = 0f;
            out[2] = (t - 0.5f) * 2f * r;
            out[3] = Math.max(0.001f, r * envelope);
            out[4] = out[3];
        });
    }

    /** Points the texture coordinates of the vertices since first into the skin part of a produce cell. */
    private static void skin(OrganismMesh.Builder b, int first, int cell) {
        float[] o = cellOrigin(cell);
        float c = 1f / GRID;
        for (int i = first; i < b.vertexCount(); i++) {
            float[] uv = b.uv(i);
            put(b, i, o[0] + c * (0.04f + 0.92f * uv[0]), o[1] + c * (0.04f + 0.76f * uv[1]));
        }
    }

    /** ... or into its strip of leaf colour. */
    private static void leafStrip(OrganismMesh.Builder b, int first, int cell) {
        float[] o = cellOrigin(cell);
        float c = 1f / GRID;
        for (int i = first; i < b.vertexCount(); i++) {
            float[] uv = b.uv(i);
            put(b, i, o[0] + c * (0.1f + 0.8f * uv[0]), o[1] + c * (0.88f + 0.08f * uv[1]));
        }
    }

    /**
     * Adds a packet standing on the origin, its printed front facing +Z, as PART_PRODUCT.
     * The front and back carry the design; the sides and ends take its border colour.
     */
    public static void buildPacket(OrganismMesh.Builder b, Packet k, float scale, float[] place) {
        b.part(OrganismMesh.PART_PRODUCT);
        b.folds(null);
        float w = k.width * scale, h = k.height * scale, d = k.depth * scale;
        float[] o = cellOrigin(k.cell);
        float c = 1f / GRID;
        if (k.box == Box.TIN) {
            // A tin: the label wrapped round, plain lids
            b.transform(Affine.multiply(place, Affine.rotationX((float) -Math.PI / 2)));
            int first = b.vertexCount();
            float r = w * 0.5f;
            b.lathe(16, 4, (t, out) -> {
                out[0] = 0f;
                out[1] = 0f;
                out[2] = t * h;
                float rim = t < 0.04f || t > 0.96f ? 0.92f : 1f;
                out[3] = r * rim;
                out[4] = r * rim;
            });
            for (int i = first; i < b.vertexCount(); i++) {
                float[] uv = b.uv(i);
                // Half the design shows each way round, the front at the middle of the label
                float u = (uv[0] + 0.25f) % 1f;
                put(b, i, o[0] + c * (0.02f + 0.96f * u), o[1] + c * (0.98f - 0.96f * uv[1]));
            }
            b.transform(place);
            for (int end = 0; end < 2; end++) {
                int capFirst = b.vertexCount();
                b.box(0f, end == 0 ? 0.05f : h - 0.05f, 0f, w * 0.92f, 0.1f, w * 0.92f);
                edge(b, capFirst, o, c);
            }
            b.transform(Affine.identity());
            return;
        }
        b.transform(place);
        int first = b.vertexCount();
        b.box(0f, h * 0.5f, 0f, w, h, d);
        // box() adds faces in the order +X, -X, +Y, -Y, +Z, -Z, four corners each
        for (int face = 0; face < 6; face++) {
            for (int corner = 0; corner < 4; corner++) {
                int i = first + face * 4 + corner;
                float[] uv = b.uv(i);
                if (face == 4 || face == 5) {
                    // On the front u runs along +x and v up +y; on the back u along +x and v down.
                    // Either way, read from outside, the design is the right way round
                    float across = face == 4 ? uv[0] : 1f - uv[0];
                    float down = face == 4 ? 1f - uv[1] : uv[1];
                    put(b, i, o[0] + c * across, o[1] + c * down);
                } else {
                    put(b, i, o[0] + c * 0.01f, o[1] + c * 0.01f);
                }
            }
        }
        if (k.box == Box.POUCH) {
            // A pouch is sealed with a crimped strip across its top
            int seal = b.vertexCount();
            b.box(0f, h + 0.08f * scale, 0f, w * 1.02f, 0.16f * scale, d * 0.4f);
            edge(b, seal, o, c);
        }
        b.transform(Affine.identity());
    }

    private static void edge(OrganismMesh.Builder b, int first, float[] o, float c) {
        for (int i = first; i < b.vertexCount(); i++) put(b, i, o[0] + c * 0.01f, o[1] + c * 0.01f);
    }

    /**
     * Points a vertex at a place in the packaging image, given across and down it from the
     * top-left; textures made from images are stored bottom row first, so v counts up.
     */
    private static void put(OrganismMesh.Builder b, int vertex, float u, float imageV) {
        b.setUV(vertex, u, 1f - imageV);
    }
}
