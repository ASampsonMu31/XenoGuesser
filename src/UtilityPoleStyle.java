import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Per-nation utility pole: the tall posts carrying wires along a nation's roads and down to its
 * houses. Each nation builds one design, the same all over it: its colour (weathered wood,
 * concrete, painted or galvanised metal), its section (round, square, eight-sided or a flat
 * slab), how tall and thick and how much it narrows, whether it has rings or grooves cut round
 * it, a ladder set into it (recessed faces with ribs across them, as Brazil's concrete poles
 * have), holes right through it (two slabs held apart by blocks), and
 * the crossarms at its top with their insulators, where the wires are strung.
 *
 * Also how often they stand (some nations far more given to them than others, and all more so
 * in town), how far off the road, and how many wires each drops to the houses round it (many
 * more in a built-up city), each drop one to three strands, sagging a little. Only a few designs
 * go round the world (see World and RegionalDesigns): neighbours, kindred ones above all, tend to
 * share them, and a nation may build two or three, each in its own part of the land. Their tops
 * differ: crossarms level or sloping, straight or curving up or down at their ends, as long both
 * sides or lopsided.
 */
public final class UtilityPoleStyle {
    public enum Section { ROUND, SQUARE, OCTAGON, SLAB }
    public enum Material { WOOD, CONCRETE, PAINTED, GALVANISED }

    public Section section;
    public Material material;
    public Vector3 colour, armColour, insulatorColour;
    /** Height, half width at the foot, and the top's width as a share of that. */
    public float height, radius, taper;
    /**
     * Rings cut round it (indents) every so often, or none; a ladder set into it (its faces along
     * the road recessed between solid edges, ribs across the recess every ribSpacing, not right
     * through); holes right through.
     */
    public boolean indents, ribbed, holes;
    public float indentSpacing, ribSpacing, holeSpacing;
    /** Crossarms: how many (0 to 3), how far each reaches either side, how far apart, and insulators on each side of each. */
    public int arms, insulators;
    public float armReach, armGap;
    /**
     * Its crossarms' shape: how far each side reaches (shares of armReach, so one side may be
     * shorter: lopsided), how much they slope (rise across them per unit, up to the right
     * positive), and how much their ends curve up (positive) or down from that, at full reach.
     */
    public float armLeft = 1f, armRight = 1f, armSlope, armCurve;
    /** One every spacing (world units) along a road, out from its edge; in the country only this share of them stand (in town all). */
    public float spacing, out, ruralShare;
    /** Wires dropped from each pole to the houses about it (before the town's own boost), strands in each, and how much they sag (a share of their length). */
    public float drops, sag;
    public int strands;

    private UtilityPoleStyle() {
    }

    private UtilityPoleStyle copy() {
        UtilityPoleStyle c = new UtilityPoleStyle();
        c.section = section; c.material = material; c.colour = colour; c.armColour = armColour; c.insulatorColour = insulatorColour;
        c.height = height; c.radius = radius; c.taper = taper;
        c.indents = indents; c.ribbed = ribbed; c.holes = holes;
        c.indentSpacing = indentSpacing; c.ribSpacing = ribSpacing; c.holeSpacing = holeSpacing;
        c.arms = arms; c.insulators = insulators; c.armReach = armReach; c.armGap = armGap;
        c.spacing = spacing; c.out = out; c.ruralShare = ruralShare; c.drops = drops; c.sag = sag; c.strands = strands;
        c.armLeft = armLeft; c.armRight = armRight; c.armSlope = armSlope; c.armCurve = armCurve;
        c.design = design;
        return c;
    }

    /** Which of the world's few designs this is (see World), for its materials. */
    public int design;

    /**
     * Every nation's poles: the world's pool of designs, as each nation places them
     * ([nation][design]: its own spacing, distance out, country share and wiring, whichever
     * design), and which design each nation builds where (see RegionalDesigns).
     */
    public record World(UtilityPoleStyle[][] styles, RegionalDesigns designs) {
        /** The pole a nation builds at a place; null for none. */
        public UtilityPoleStyle at(int nation, GeodesicChunkGrid.Direction place) {
            int d = designs.designAt(nation, place);
            return d < 0 ? null : styles[nation][d];
        }

        /** A nation's main pole (whose spacing all its poles keep); null for none. */
        public UtilityPoleStyle main(int nation) {
            int[] mine = designs.designsOf(nation);
            return mine.length == 0 ? null : styles[nation][mine[0]];
        }

        public UtilityPoleStyle design(int nation, int design) {
            return styles[nation][design];
        }
    }

    /** Deals the world's poles: about one design for every POOL_PER_NATIONS nations. */
    public static World generateForNations(long seed, int numNations, NationKinship kinship) {
        RegionalDesigns designs = new RegionalDesigns(seed + 7575L, numNations, kinship, POOL_PER_NATIONS);
        Random rand = new Random(seed + 7474L);
        UtilityPoleStyle[] pool = new UtilityPoleStyle[designs.poolSize];
        for (int d = 0; d < pool.length; d++) {
            pool[d] = fresh(rand);
            pool[d].design = d;
        }
        Random placing = new Random(seed + 7676L);
        UtilityPoleStyle[][] styles = new UtilityPoleStyle[numNations + 1][];
        for (int n = 1; n <= numNations; n++) {
            UtilityPoleStyle where = new UtilityPoleStyle();
            where.placing(placing);
            styles[n] = new UtilityPoleStyle[pool.length];
            for (int d = 0; d < pool.length; d++) {
                UtilityPoleStyle s = pool[d].copy();
                s.spacing = where.spacing;
                s.out = where.out;
                s.ruralShare = where.ruralShare;
                s.drops = where.drops;
                s.strands = where.strands;
                s.sag = where.sag;
                styles[n][d] = s;
            }
        }
        return new World(styles, designs);
    }

    private static final float POOL_PER_NATIONS = 3.5f;

    private static UtilityPoleStyle fresh(Random rand) {
        UtilityPoleStyle s = new UtilityPoleStyle();
        s.material = Material.values()[rand.nextInt(Material.values().length)];
        s.section = s.material == Material.WOOD ? (rand.nextFloat() < 0.8f ? Section.ROUND : Section.SQUARE)
                : Section.values()[rand.nextInt(Section.values().length)];
        s.colour = colourOf(s.material, rand);
        s.armColour = rand.nextFloat() < 0.5f ? s.colour : colourOf(rand.nextBoolean() ? Material.WOOD : Material.GALVANISED, rand);
        float glass = rand.nextFloat();
        s.insulatorColour = glass < 0.35f ? new Vector3(0.9f, 0.9f, 0.86f) : glass < 0.6f ? new Vector3(0.35f, 0.62f, 0.5f)
                : glass < 0.8f ? new Vector3(0.45f, 0.26f, 0.15f) : new Vector3(0.3f, 0.45f, 0.75f);
        // (ten units to the metre: 7 to 13 m tall, 20 to 44 cm across at the foot)
        s.height = 70f + rand.nextFloat() * 60f;
        s.radius = 1.0f + rand.nextFloat() * 1.2f;
        s.taper = 0.55f + rand.nextFloat() * 0.4f;
        s.indents = rand.nextFloat() < 0.35f;
        s.indentSpacing = 6f + rand.nextFloat() * 14f;
        // (a ladder set in, or holes through, only in a squared or flat one, not wood: one or the other)
        boolean squared = (s.section == Section.SQUARE || s.section == Section.SLAB) && s.material != Material.WOOD;
        float form = rand.nextFloat();
        s.ribbed = squared && form < 0.45f;
        s.ribSpacing = 3.5f + rand.nextFloat() * 3f;
        s.holes = squared && !s.ribbed && form < 0.8f;
        s.holeSpacing = 6f + rand.nextFloat() * 8f;
        s.arms = rand.nextFloat() < 0.15f ? 0 : 1 + rand.nextInt(3);
        s.insulators = 1 + rand.nextInt(3);
        s.armReach = 6f + rand.nextFloat() * 8f;
        s.armGap = 5f + rand.nextFloat() * 5f;
        // About half have plain level arms the same both sides; the rest are lopsided, sloping or
        // curved up or down, or more than one of these
        if (rand.nextFloat() < 0.5f) {
            if (rand.nextFloat() < 0.5f) {
                float shorter = 0.2f + rand.nextFloat() * 0.6f;
                if (rand.nextBoolean()) s.armLeft = shorter; else s.armRight = shorter;
            }
            if (rand.nextFloat() < 0.45f) s.armSlope = (rand.nextFloat() - 0.5f) * 0.5f;
            if (rand.nextFloat() < 0.5f) s.armCurve = (rand.nextBoolean() ? 1f : -1f) * (1f + rand.nextFloat() * 3f);
            if (s.armLeft == 1f && s.armRight == 1f && s.armSlope == 0f && s.armCurve == 0f) s.armCurve = 2.5f;
        }
        return s;
    }

    private void placing(Random rand) {
        // From every 25 m to every 60 m; in the country from almost none to nearly all
        spacing = 250f + rand.nextFloat() * 350f;
        // (on the verge, short of anyone's garden)
        out = 1.5f + rand.nextFloat() * 1.5f;
        ruralShare = (float) Math.pow(rand.nextFloat(), 1.5);
        drops = 0.4f + rand.nextFloat() * 2.2f;
        strands = 1 + rand.nextInt(3);
        sag = 0.015f + rand.nextFloat() * 0.035f;
    }

    private static Vector3 colourOf(Material m, Random rand) {
        return switch (m) {
            case WOOD -> {
                float v = 0.22f + rand.nextFloat() * 0.25f;
                yield new Vector3(v * 1.25f, v, v * 0.72f);
            }
            case CONCRETE -> {
                float v = 0.55f + rand.nextFloat() * 0.25f;
                yield new Vector3(v, v * 0.98f, v * 0.94f);
            }
            case GALVANISED -> {
                float v = 0.55f + rand.nextFloat() * 0.15f;
                yield new Vector3(v * 0.96f, v, v * 1.03f);
            }
            case PAINTED -> {
                java.awt.Color c = java.awt.Color.getHSBColor(rand.nextFloat(), 0.25f + rand.nextFloat() * 0.5f, 0.2f + rand.nextFloat() * 0.55f);
                yield new Vector3(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
            }
        };
    }

    private int sides() {
        return switch (section) {
            case ROUND -> 10;
            case OCTAGON -> 8;
            default -> 4;
        };
    }

    /** Half the pole's width a height up it. */
    public float radiusAt(float up) {
        return radius * (1f + (taper - 1f) * Math.max(0f, Math.min(1f, up / height)));
    }

    /** How far its crossarms reach on a side (-1 left, 1 right). */
    public float armSideReach(float side) {
        return armReach * (side < 0f ? armLeft : armRight);
    }

    /** How far a crossarm rises (or falls) from its middle at a distance across (left negative): its slope and the curve of its ends. */
    public float armRise(float across) {
        float t = across / armReach;
        return armSlope * across + armCurve * t * t;
    }

    /**
     * Where wires are strung on it, in its own terms{along the road, across it (out from the
     * road positive), up from its foot}: each insulator's top on each crossarm (both sides of
     * the pole), or with no crossarms, two on brackets just under its top.
     */
    public List<float[]> lineAttachments() {
        List<float[]> points = new ArrayList<>();
        float top = height - 2f;
        if (arms == 0) {
            points.add(new float[] { 0f, -radiusAt(top) - 1.2f, top - 1f });
            points.add(new float[] { 0f, radiusAt(top) + 1.2f, top - 4f });
            return points;
        }
        for (int a = 0; a < arms; a++) {
            float y = top - a * armGap + 1.6f;
            for (int k = 1; k <= insulators; k++) {
                for (float side = -1f; side <= 1f; side += 2f) {
                    float across = side * armSideReach(side) * k / insulators;
                    points.add(new float[] { 0f, across, y + armRise(across) });
                }
            }
        }
        return points;
    }

    /** Where wires down to the houses leave it, {along, across, up}: a bracket a little under its lowest crossarm. */
    public float[] dropAttachment() {
        float y = (arms == 0 ? height - 2f : height - 2f - (arms - 1) * armGap) - 5f;
        return new float[] { 0f, 0f, y };
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Builds one pole standing on the ground at (x, groundY, z) in a road segment's frame (y up),
     * f its way along the road and s straight out across it (unit vectors, {x, z}): its body
     * into body, its crossarms and steps into arm, its insulators into glass.
     */
    public void build(MeshBuilder body, MeshBuilder arm, MeshBuilder glass, float x, float groundY, float z, float[] f, float[] s) {
        int n = sides();
        float depth = section == Section.SLAB ? 0.45f : 1f;
        float foot = groundY - 3f;
        if (holes) {
            // Two slabs (along s, either side of the middle) held apart by blocks, the gaps between right through
            float y = foot;
            for (float side = -1f; side <= 1f; side += 2f) {
                for (float up = foot; up < groundY + height; up += 8f) {
                    float y1 = Math.min(groundY + height, up + 8f);
                    float r0 = radiusAt(up - groundY), r1 = radiusAt(y1 - groundY);
                    box(body, x + s[0] * side * r0 * 0.7f, z + s[1] * side * r0 * 0.7f, f, s, up, y1, r0 * depth, r0 * 0.3f, r1 * depth, r1 * 0.3f);
                }
            }
            for (y = groundY + holeSpacing * 0.5f; y < groundY + height; y += holeSpacing) {
                float r = radiusAt(y - groundY);
                box(body, x, z, f, s, y, y + holeSpacing * 0.35f, r * depth, r * 0.45f, r * depth, r * 0.45f);
            }
            box(body, x, z, f, s, foot, groundY + 2f, radius * depth, radius * 0.45f, radius * depth, radius * 0.45f);
        } else if (ribbed) {
            // A ladder set in: solid edges either side (across the road), a thin wall between them
            // well back from both faces, and ribs across the recesses so made, just inside the faces
            for (float up = foot; up < groundY + height; up += 8f) {
                float y1 = Math.min(groundY + height, up + 8f);
                float r0 = radiusAt(up - groundY), r1 = radiusAt(y1 - groundY);
                for (float side = -1f; side <= 1f; side += 2f) {
                    box(body, x + s[0] * side * r0 * 0.85f, z + s[1] * side * r0 * 0.85f, f, s, up, y1, r0 * depth, r0 * 0.15f, r1 * depth, r1 * 0.15f);
                }
                box(body, x, z, f, s, up, y1, r0 * depth * 0.3f, r0 * 0.72f, r1 * depth * 0.3f, r1 * 0.72f);
            }
            for (float y = groundY + 4f; y < groundY + height - 2f; y += ribSpacing) {
                float r = radiusAt(y - groundY);
                box(body, x, z, f, s, y, y + 0.8f, r * depth * 0.92f, r * 0.72f, r * depth * 0.92f, r * 0.72f);
            }
            // (solid at its foot and its top)
            box(body, x, z, f, s, foot, groundY + 3f, radius * depth, radius * 0.72f, radius * depth, radius * 0.72f);
            float topR = radiusAt(height - 2f);
            box(body, x, z, f, s, groundY + height - 2f, groundY + height, topR * depth, topR * 0.72f, topR * depth, topR * 0.72f);
        } else {
            prism(body, x, z, f, s, n, depth, foot, groundY + height, radius, radiusAt(height), 1f);
        }
        // The top closed
        float topR = radiusAt(height);
        for (int k = 0; k < n; k++) {
            double a = Math.PI / n + 2 * Math.PI * k / n, b = a + 2 * Math.PI / n;
            triangle(body, round(x, z, f, s, depth, a, topR, groundY + height), round(x, z, f, s, depth, b, topR, groundY + height),
                    new float[] { x, groundY + height, z }, new float[] { 0f, 1f, 0f });
        }
        if (indents) {
            // Rings cut round it: dark narrow bands a hair inside its surface, between bulging lips
            for (float y = groundY + indentSpacing; y < groundY + height - 3f; y += indentSpacing) {
                float r = radiusAt(y - groundY);
                prism(arm, x, z, f, s, n, depth, y - 0.5f, y + 0.5f, r * 1.07f, r * 1.07f, 1f);
            }
        }
float top = groundY + height - 2f;
        for (int a = 0; a < arms; a++) {
            float y = top - a * armGap;
            // The arm in short straight pieces along its slope and curve, end to end
            float from = -armSideReach(-1f) - 1f, to = armSideReach(1f) + 1f;
            int pieces = Math.max(2, (int) Math.ceil((to - from) / 2.5f));
            for (int p = 0; p < pieces; p++) {
                float a0 = from + (to - from) * p / pieces, a1 = from + (to - from) * (p + 1) / pieces;
                beam(arm, x, z, f, s, a0, y + armRise(a0), a1, y + armRise(a1), 0.6f);
            }
            for (int k = 1; k <= insulators; k++) {
                for (float side = -1f; side <= 1f; side += 2f) {
                    float across = side * armSideReach(side) * k / insulators, iy = y + armRise(across);
                    float ix = x + s[0] * across, iz = z + s[1] * across;
                    prism(glass, ix, iz, f, s, 6, 1f, iy + 0.6f, iy + 1.6f, 0.45f, 0.3f, 1f);
                }
            }
        }
        if (arms == 0) {
            for (float[] p : lineAttachments()) {
                float bx = x + s[0] * p[1], bz = z + s[1] * p[1];
                box(arm, (x + bx) * 0.5f, (z + bz) * 0.5f, f, s, groundY + p[2] - 0.8f, groundY + p[2] - 0.3f, 0.3f, Math.abs(p[1]) * 0.5f, 0.3f, Math.abs(p[1]) * 0.5f);
                prism(glass, bx, bz, f, s, 6, 1f, groundY + p[2] - 0.3f, groundY + p[2] + 0.4f, 0.4f, 0.28f, 1f);
            }
        }
    }

    /**
     * A wire from a to b (points {x, y, z} in the frame), sagging in the middle by sag of its
     * length, as a thin four-sided tube in pieces.
     */
    public static void wire(MeshBuilder m, float[] a, float[] b, float sag) {
        float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.5f) return;
        int pieces = Math.max(3, Math.min(12, (int) (length / 8f)));
        // (4 cm across: thin, but still to be seen a street away)
        float r = 0.2f;
        // (square to the wire, sideways and up-ish)
        float hx = -dz, hz = dx, hl = (float) Math.sqrt(hx * hx + hz * hz);
        if (hl < 1e-4f) { hx = 1f; hz = 0f; hl = 1f; }
        hx /= hl; hz /= hl;
        float[] prev = null;
        for (int i = 0; i <= pieces; i++) {
            float t = (float) i / pieces;
            float drop = 4f * sag * length * t * (1f - t);
            float[] p = { a[0] + dx * t, a[1] + dy * t - drop, a[2] + dz * t };
            if (prev != null) {
                float[][] ring0 = { { prev[0] + hx * r, prev[1], prev[2] + hz * r }, { prev[0], prev[1] + r, prev[2] }, { prev[0] - hx * r, prev[1], prev[2] - hz * r }, { prev[0], prev[1] - r, prev[2] } };
                float[][] ring1 = { { p[0] + hx * r, p[1], p[2] + hz * r }, { p[0], p[1] + r, p[2] }, { p[0] - hx * r, p[1], p[2] - hz * r }, { p[0], p[1] - r, p[2] } };
                for (int k = 0; k < 4; k++) {
                    int k1 = (k + 1) % 4;
                    float[] out = { (ring0[k][0] + ring0[k1][0]) * 0.5f - prev[0], (ring0[k][1] + ring0[k1][1]) * 0.5f - prev[1], (ring0[k][2] + ring0[k1][2]) * 0.5f - prev[2] };
                    triangle(m, ring0[k], ring0[k1], ring1[k1], out);
                    triangle(m, ring0[k], ring1[k1], ring1[k], out);
                }
            }
            prev = p;
        }
    }

    /** A square beam half thick across, from a0 across the pole at height y0 to a1 at y1 (across s, from the pole's middle at x, z). */
    private static void beam(MeshBuilder m, float x, float z, float[] f, float[] s, float a0, float y0, float a1, float y1, float half) {
        float[][] c0 = new float[4][], c1 = new float[4][];
        float[][] corner = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (int k = 0; k < 4; k++) {
            float along = corner[k][0] * half, up = corner[k][1] * half;
            c0[k] = new float[] { x + s[0] * a0 + f[0] * along, y0 + up, z + s[1] * a0 + f[1] * along };
            c1[k] = new float[] { x + s[0] * a1 + f[0] * along, y1 + up, z + s[1] * a1 + f[1] * along };
        }
        float mx = x + s[0] * (a0 + a1) * 0.5f, my = (y0 + y1) * 0.5f, mz = z + s[1] * (a0 + a1) * 0.5f;
        for (int k = 0; k < 4; k++) {
            int k1 = (k + 1) % 4;
            float[] out = { (c0[k][0] + c0[k1][0] + c1[k][0] + c1[k1][0]) * 0.25f - mx, (c0[k][1] + c0[k1][1] + c1[k][1] + c1[k1][1]) * 0.25f - my,
                    (c0[k][2] + c0[k1][2] + c1[k][2] + c1[k1][2]) * 0.25f - mz };
            triangle(m, c0[k], c0[k1], c1[k1], out);
            triangle(m, c0[k], c1[k1], c1[k], out);
        }
        // (its ends closed)
        float[] back = { -s[0], 0f, -s[1] }, ahead = { s[0], 0f, s[1] };
        triangle(m, c0[0], c0[1], c0[2], back);
        triangle(m, c0[0], c0[2], c0[3], back);
        triangle(m, c1[0], c1[1], c1[2], ahead);
        triangle(m, c1[0], c1[2], c1[3], ahead);
    }

    private static float[] round(float x, float z, float[] f, float[] s, float depth, double angle, float r, float y) {
        float a = (float) Math.cos(angle) * r * depth, b = (float) Math.sin(angle) * r;
        return new float[] { x + f[0] * a + s[0] * b, y, z + f[1] * a + s[1] * b };
    }

    private static void prism(MeshBuilder m, float x, float z, float[] f, float[] s, int n, float depth, float y0, float y1, float r0, float r1, float grow) {
        for (int k = 0; k < n; k++) {
            double a = Math.PI / n + 2 * Math.PI * k / n, b = a + 2 * Math.PI / n;
            float[] p0 = round(x, z, f, s, depth, a, r0 * grow, y0), p1 = round(x, z, f, s, depth, b, r0 * grow, y0);
            float[] p2 = round(x, z, f, s, depth, b, r1 * grow, y1), p3 = round(x, z, f, s, depth, a, r1 * grow, y1);
            float[] outward = round(0f, 0f, f, s, depth, (a + b) * 0.5, 1f, 0f);
            triangle(m, p0, p1, p2, outward);
            triangle(m, p0, p2, p3, outward);
        }
    }

    /** A box round (x, z) between two heights, half sizes along f and across s at its foot and top, closed top and bottom. */
    private static void box(MeshBuilder m, float x, float z, float[] f, float[] s, float y0, float y1, float alongA, float acrossA, float alongB, float acrossB) {
        float[][] lo = new float[4][], hi = new float[4][];
        float[][] signs = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (int k = 0; k < 4; k++) {
            lo[k] = new float[] { x + f[0] * signs[k][0] * alongA + s[0] * signs[k][1] * acrossA, y0, z + f[1] * signs[k][0] * alongA + s[1] * signs[k][1] * acrossA };
            hi[k] = new float[] { x + f[0] * signs[k][0] * alongB + s[0] * signs[k][1] * acrossB, y1, z + f[1] * signs[k][0] * alongB + s[1] * signs[k][1] * acrossB };
        }
        for (int k = 0; k < 4; k++) {
            int k1 = (k + 1) % 4;
            float[] out = { (lo[k][0] + lo[k1][0]) * 0.5f - x, 0f, (lo[k][2] + lo[k1][2]) * 0.5f - z };
            triangle(m, lo[k], lo[k1], hi[k1], out);
            triangle(m, lo[k], hi[k1], hi[k], out);
        }
        triangle(m, hi[0], hi[1], hi[2], new float[] { 0f, 1f, 0f });
        triangle(m, hi[0], hi[2], hi[3], new float[] { 0f, 1f, 0f });
        triangle(m, lo[0], lo[2], lo[1], new float[] { 0f, -1f, 0f });
        triangle(m, lo[0], lo[3], lo[2], new float[] { 0f, -1f, 0f });
    }

    private static void triangle(MeshBuilder m, float[] a, float[] b, float[] c, float[] hint) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-9f) return;
        boolean flip = nx * hint[0] + ny * hint[1] + nz * hint[2] < 0f;
        if (flip) { nx = -nx; ny = -ny; nz = -nz; }
        nx /= length; ny /= length; nz /= length;
        int i0 = m.addVertex(a[0], a[1], a[2], nx, ny, nz, 0f, 0f);
        int i1 = m.addVertex(b[0], b[1], b[2], nx, ny, nz, 1f, 0f);
        int i2 = m.addVertex(c[0], c[1], c[2], nx, ny, nz, 0f, 1f);
        if (flip) m.addTriangle(i0, i2, i1); else m.addTriangle(i0, i1, i2);
    }

    // ------------------------------------------------------------------ its picture (for the map)

    // How much wider than true a pole's picture is drawn (a pole is so slender its features
    // wouldn't show on the map otherwise)
    private static final float PICTURE_WIDEN = 2.4f;

    /** A picture of this pole standing, seen along the road (its crossarms across the picture), height pixels tall, drawn PICTURE_WIDEN times as wide as true, on a clear background. */
    public java.awt.image.BufferedImage picture(int heightPx) {
        float scale = heightPx * 0.9f / height;
        float reach = Math.max(arms > 0 ? armReach + 1.6f : radius + 2f, radius * 2f);
        int trueWidth = Math.max(heightPx / 8, Math.round(reach * 2f * scale + heightPx * 0.04f));
        int width = Math.round(trueWidth * PICTURE_WIDEN);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, heightPx, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        // (drawn at its true width, stretched across)
        g.scale(PICTURE_WIDEN, 1.0);
        float cx = trueWidth * 0.5f, ground = heightPx * 0.97f;
        java.awt.Color body = awt(colour), armC = awt(armColour), glassC = awt(insulatorColour);
        float stroke = Math.max(1f, scale * 0.25f);
        // The body (two slabs and blocks if it has holes right through)
        java.awt.geom.Path2D.Float outline = new java.awt.geom.Path2D.Float();
        float foot = radius * scale, topW = radiusAt(height) * scale, h = height * scale;
        outline.moveTo(cx - foot, ground);
        outline.lineTo(cx - topW, ground - h);
        outline.lineTo(cx + topW, ground - h);
        outline.lineTo(cx + foot, ground);
        outline.closePath();
        g.setPaint(new java.awt.LinearGradientPaint(cx - foot, 0f, cx + foot, 0f, new float[] { 0f, 0.35f, 1f },
                new java.awt.Color[] { shade(body, 0.7f), shade(body, 1.15f), shade(body, 0.55f) }));
        g.fill(outline);
        if (holes) {
            // (the gaps between the blocks, cut through so the background shows)
            java.awt.Composite old = g.getComposite();
            g.setComposite(java.awt.AlphaComposite.Clear);
            for (float y = holeSpacing * 0.5f + holeSpacing * 0.35f; y < height - 1f; y += holeSpacing) {
                float y1 = Math.min(height - 1f, y + holeSpacing * 0.65f);
                float w = radiusAt(y) * 0.4f * scale;
                g.fill(new java.awt.geom.Rectangle2D.Float(cx - w, ground - y1 * scale, w * 2f, (y1 - y) * scale));
            }
            g.setComposite(old);
        }
        g.setColor(new java.awt.Color(20, 20, 22, 220));
        g.setStroke(new java.awt.BasicStroke(stroke * 0.6f));
        g.draw(outline);
        if (indents) {
            g.setColor(shade(body, 0.45f));
            for (float y = indentSpacing; y < height - 3f; y += indentSpacing) {
                float w = radiusAt(y) * 1.07f * scale;
                g.fill(new java.awt.geom.Rectangle2D.Float(cx - w, ground - (y + 0.5f) * scale, w * 2f, Math.max(1f, scale)));
            }
        }
        if (ribbed) {
            // The recess between its edges, darker (the wall well back), with the ribs across it
            java.awt.geom.Path2D.Float recess = new java.awt.geom.Path2D.Float();
            recess.moveTo(cx - radiusAt(3f) * 0.7f * scale, ground - 3f * scale);
            recess.lineTo(cx - radiusAt(height - 2f) * 0.7f * scale, ground - (height - 2f) * scale);
            recess.lineTo(cx + radiusAt(height - 2f) * 0.7f * scale, ground - (height - 2f) * scale);
            recess.lineTo(cx + radiusAt(3f) * 0.7f * scale, ground - 3f * scale);
            recess.closePath();
            g.setColor(shade(body, 0.45f));
            g.fill(recess);
            g.setColor(shade(body, 1.05f));
            for (float y = 4f; y < height - 2f; y += ribSpacing) {
                float w = radiusAt(y) * 0.72f * scale;
                g.fill(new java.awt.geom.Rectangle2D.Float(cx - w, ground - (y + 0.8f) * scale, w * 2f, Math.max(1f, 0.8f * scale)));
            }
        }
        float top = height - 2f;
        for (int a = 0; a < arms; a++) {
            float y = top - a * armGap;
            float thick = Math.max(1.5f, 1.2f * scale);
            // (along its slope and curve)
            java.awt.geom.Path2D.Float bar = new java.awt.geom.Path2D.Float();
            float from = -armSideReach(-1f) - 1f, to = armSideReach(1f) + 1f;
            int pieces = 16;
            for (int p = 0; p <= pieces; p++) {
                float across = from + (to - from) * p / pieces;
                float px = cx + across * scale, py = ground - (y + armRise(across)) * scale - thick * 0.5f;
                if (p == 0) bar.moveTo(px, py); else bar.lineTo(px, py);
            }
            for (int p = pieces; p >= 0; p--) {
                float across = from + (to - from) * p / pieces;
                bar.lineTo(cx + across * scale, ground - (y + armRise(across)) * scale + thick * 0.5f);
            }
            bar.closePath();
            g.setColor(armC);
            g.fill(bar);
            g.setColor(new java.awt.Color(20, 20, 22, 200));
            g.draw(bar);
            g.setColor(glassC);
            for (int k = 1; k <= insulators; k++) {
                for (float side = -1f; side <= 1f; side += 2f) {
                    float across = side * armSideReach(side) * k / insulators;
                    float w = Math.max(2f, 0.9f * scale), hh = Math.max(2f, 1.1f * scale);
                    g.fill(new java.awt.geom.Rectangle2D.Float(cx + across * scale - w * 0.5f, ground - (y + armRise(across) + 0.6f) * scale - hh, w, hh));
                }
            }
        }
        if (arms == 0) {
            g.setColor(glassC);
            for (float[] p : lineAttachments()) {
                float w = Math.max(2f, 0.8f * scale);
                g.fill(new java.awt.geom.Rectangle2D.Float(cx + p[1] * scale - w * 0.5f, ground - p[2] * scale - w, w, w));
            }
        }
        g.dispose();
        return image;
    }

    private static java.awt.Color awt(Vector3 c) {
        return new java.awt.Color(Math.max(0f, Math.min(1f, c.x)), Math.max(0f, Math.min(1f, c.y)), Math.max(0f, Math.min(1f, c.z)));
    }

    private static java.awt.Color shade(java.awt.Color c, float by) {
        return new java.awt.Color(Math.min(255, Math.round(c.getRed() * by + (by > 1f ? 12 : 0))),
                Math.min(255, Math.round(c.getGreen() * by + (by > 1f ? 12 : 0))), Math.min(255, Math.round(c.getBlue() * by + (by > 1f ? 12 : 0))));
    }
}
