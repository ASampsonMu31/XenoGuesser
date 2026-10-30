import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import com.xenoguesser.math.Vector3;

/**
 * One of the world's famous landmarks (see Landmarks): built ones that stand out from
 * everything round them (a great spire many times the height of the tallest towers, a circle
 * of standing stones, a grand pillared ruin dressed in its region's ornaments) and natural ones
 * (the planet's highest peak, its most sheltered bay with stacks of rock standing in it, a
 * ribbon of sand with the sea either side, fields of steep pointed hills, the grove where a
 * plant grows thicker than anywhere else: the land shaped for all but the peak and the grove,
 * see Landforms).
 * <p>
 * A built one is laid out in a frame of its own (local +z its front, y its height above its
 * base) from its seed, as faces of a few colours (see SLOTS), drawn both in the world (see
 * InfrastructureManager); with its floors (steps that can be walked up) and what's solid. A
 * natural one is only somewhere. Its picture for the map is a photograph of it taken in the
 * world itself (see LandmarkPhotos); render draws one without the world, should that fail.
 */
public final class Landmark {

    public enum Kind {
        SPIRE("Great spire", true), STONES("Ancient monoliths", true), RUIN("Ancient ruin", true),
        SUMMIT("Highest peak", false), BAY("Sheltered bay", false), SPIT("Sand spit", false), GROVE("Famous grove", false),
        PINNACLES("Stone pinnacles", false);

        public final String label;
        public final boolean built;

        Kind(String label, boolean built) {
            this.label = label;
            this.built = built;
        }
    }

    private static final float M = PlayerBody.METRE;
    // Each step of a platform: low enough to walk up (InfrastructureManager's STEP_UP is 4)
    static final float STEP = 0.35f * M;

    // The colours a built one is made in: its stone, a darker stone, its ornaments (metal or
    // paint), glass, deep shadow (doorways), and its foundations
    static final int STONE = 0, STONE2 = 1, ORN = 2, GLASS = 3, DARK = 4, FOUND = 5, SLOTS = 6;

    public final Kind kind;
    // Its place on the datum, turned so local +z is its front (towards the road it was built by)
    public final SphereFrame frame;
    public final int nation;
    public final long seed;
    public String name = "", caption = "";
    // How far round it its ground reaches (kept clear of houses, trees and rocks)
    public final float reach;
    // Its base's height above the datum, and how far its foundations go down below it
    public float base;
    float drop;
    // Its colours by slot, and how shiny each is
    final Vector3[] colours = new Vector3[SLOTS];
    final float[] shine = new float[SLOTS];
    // The style it's built in
    final int sides;
    // Which of its kind's forms it takes (see buildSpire, buildStones, buildRuin)
    final int variant;
    final ArchitectureStyle.Crest crest;
    final float ornateness;
    // What it looks like, in words (for its description)
    String looks = "";
    // Its size: a spire's height, a stone circle's radius, a ruin's half width and half depth
    final float height, radius, halfWidth, halfDepth;
    // A grove's species and its leaves' and trunk's colours; how far round a natural one's picture shows
    public int species = -1;
    Vector3 leaves, trunk;
    float span;
    // How many hills or stacks a natural one has, and the way it runs or is best seen (a spit's
    // out from its root, a field's by the sea from the land towards it), as a heading (NaN for none)
    int count;
    double way = Double.NaN;

    // What it's made of (built ones, once settled)
    Geometry geometry;
    // Floors: {x, z, half width (or radius), half depth (or -1 for a disc), top above base}
    final List<float[]> floors = new ArrayList<>();
    // What's solid: circles {x, z, radius} and walls {x0, z0, x1, z1, half thickness, top above base}
    final List<float[]> circles = new ArrayList<>();
    final List<float[]> walls = new ArrayList<>();

    // The land, for its picture: heights above the datum, and the sea's
    ToDoubleFunction<SpherePosition> ground;
    float seaLevel;
    // Its picture, once drawn, and the world's colours it's drawn in
    public volatile BufferedImage picture;
    Look look = Look.EARTHLY;

    /** The world's colours, for the pictures: its sky (overhead and at the horizon), grass (lush and dry), bare soil and sea. */
    public record Look(Vector3 zenith, Vector3 horizon, Vector3 grass, Vector3 dry, Vector3 soil, Vector3 sea) {
        static final Look EARTHLY = new Look(new Vector3(0.33f, 0.52f, 0.77f), new Vector3(0.8f, 0.87f, 0.92f), new Vector3(0.3f, 0.47f, 0.21f),
                new Vector3(0.5f, 0.52f, 0.3f), new Vector3(0.47f, 0.44f, 0.4f), new Vector3(0.12f, 0.34f, 0.5f));

        /** Its far-off land, in the haze. */
        Vector3 distance() {
            return mix(mix(grass, dry, 0.4f), horizon, 0.45f);
        }
    }

    /** A built one: its size and style from its seed (its base and geometry come with settle). */
    Landmark(Kind kind, SphereFrame frame, int nation, long seed, ArchitectureStyle style, Vector3 stone, Vector3 wall) {
        this.kind = kind;
        this.frame = frame;
        this.nation = nation;
        this.seed = seed;
        Random rand = new Random(seed);
        switch (kind) {
            case SPIRE -> {
                variant = rand.nextInt(5);
                sides = 3 + rand.nextInt(6);
                height = (130f + rand.nextFloat() * 80f) * M;
                radius = (6.5f + rand.nextFloat() * 3.5f) * M;
                // (on legs, it stands over a wider plinth)
                halfWidth = halfDepth = (variant == LEGS ? radius * (2.4f + rand.nextFloat() * 0.6f) : radius) + 6.5f * M;
                reach = halfWidth + 2f * M;
            }
            case STONES -> {
                variant = rand.nextInt(4);
                sides = 3 + rand.nextInt(3);
                radius = (12f + rand.nextFloat() * 7f) * M;
                height = 0f;
                halfWidth = halfDepth = radius;
                reach = radius + 12f * M;
            }
            case RUIN -> {
                variant = rand.nextInt(3);
                sides = 3 + rand.nextInt(6);
                halfWidth = (12f + rand.nextFloat() * 5f) * M;
                halfDepth = variant == RECT ? halfWidth * (1.3f + rand.nextFloat() * 0.6f) : variant == ROUND ? halfWidth : halfWidth * (1f + rand.nextFloat() * 0.35f);
                height = (10f + rand.nextFloat() * 6f) * M;
                radius = 0f;
                reach = (float) Math.hypot(halfWidth, halfDepth + 12f * M) + 6f * M;
            }
            default -> throw new IllegalArgumentException(kind + " is not built");
        }
        crest = style != null ? style.crest : ArchitectureStyle.Crest.ORBS;
        ornateness = style != null ? style.ornateness : 0.5f;
        Vector3 ornament = style != null ? style.ornamentColour : new Vector3(0.85f, 0.66f, 0.25f);
        float ornamentShine = style != null ? style.ornamentShine : 40f;
        // Stone: the nation's dressing stone, a little of its walls in it, and a tint of some
        // colour of its own (no two alike); the monoliths of a strange rock of their own
        float[] tint = WorldPalette.hsv(rand.nextFloat(), 0.35f + rand.nextFloat() * 0.4f, 0.45f + rand.nextFloat() * 0.4f);
        Vector3 own = new Vector3(tint[0], tint[1], tint[2]);
        Vector3 main = mix(mix(stone, wall, 0.3f), own, kind == Kind.STONES ? 0.55f : 0.25f + rand.nextFloat() * 0.2f);
        colours[STONE] = main;
        colours[STONE2] = mix(Vector3.multiply(main, 0.7f), own, 0.2f);
        float[] glow = WorldPalette.hsv(rand.nextFloat(), 0.6f, 0.85f);
        colours[ORN] = kind == Kind.STONES ? new Vector3(glow[0], glow[1], glow[2]) : ornament;
        colours[GLASS] = mix(new Vector3(0.18f, 0.27f, 0.36f), ornament, 0.25f);
        colours[DARK] = new Vector3(0.06f, 0.055f, 0.05f);
        colours[FOUND] = Vector3.multiply(main, 0.7f);
        shine[STONE] = shine[STONE2] = shine[FOUND] = 4f;
        shine[ORN] = kind == Kind.STONES ? 24f : ornamentShine;
        shine[GLASS] = 64f;
        shine[DARK] = 2f;
    }

    /** A natural one, at a place on the datum. */
    Landmark(Kind kind, SpherePosition place, int nation, long seed, float span) {
        this.kind = kind;
        this.frame = SphereFrame.at(SphereMath.atAltitude(place, 0.0));
        this.nation = nation;
        this.seed = seed;
        this.span = span;
        this.sides = 0;
        this.variant = 0;
        this.crest = ArchitectureStyle.Crest.NONE;
        this.ornateness = 0f;
        this.height = this.radius = this.halfWidth = this.halfDepth = 0f;
        this.reach = kind == Kind.GROVE ? span * 0.5f : 0f;
    }

    private static Vector3 mix(Vector3 a, Vector3 b, float t) {
        return new Vector3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    /** Where it is: the place on the datum at its middle. */
    public SpherePosition place() {
        return frame.origin();
    }

    // ------------------------------------------------------------------ settling it on the land

    /** Points over its ground, local {x, z}: its middle, and rings out to its reach. */
    List<float[]> footprint() {
        List<float[]> points = new ArrayList<>();
        points.add(new float[] { 0f, 0f });
        for (float share : new float[] { 0.35f, 0.7f, 1f }) {
            int count = share < 0.5f ? 8 : 16;
            for (int i = 0; i < count; i++) {
                double a = Math.PI * 2 * i / count;
                if (kind == Kind.RUIN) {
                    // (the platform's rectangle, with its obelisks in front)
                    points.add(new float[] { (float) Math.sin(a) * (halfWidth + 2f * M) * share, (float) Math.cos(a) * (halfDepth + 10f * M) * share });
                } else {
                    float r = kind == Kind.SPIRE ? halfWidth : reach;
                    points.add(new float[] { (float) Math.sin(a) * r * share, (float) Math.cos(a) * r * share });
                }
            }
        }
        return points;
    }

    /** The ground's height above the datum at a local point. */
    float groundAt(float x, float z) {
        return (float) ground.applyAsDouble(frame.toSphere(x, 0.0, z));
    }

    /**
     * Sets it on the land: its base level with the highest of its ground (a stone circle's with
     * its middle, each stone standing on the ground where it is), its foundations down to the
     * lowest, and lays it out.
     */
    void settle(ToDoubleFunction<SpherePosition> ground, float seaLevel) {
        this.ground = ground;
        this.seaLevel = seaLevel;
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (float[] p : footprint()) {
            float h = groundAt(p[0], p[1]);
            low = Math.min(low, h);
            high = Math.max(high, h);
        }
        base = kind == Kind.STONES ? groundAt(0f, 0f) : high + 0.1f * M;
        drop = base - low + 1.5f * M;
        Geometry g = new Geometry();
        Random rand = new Random(seed * 31L + 5L);
        switch (kind) {
            case SPIRE -> buildSpire(g, rand);
            case STONES -> buildStones(g, rand);
            case RUIN -> buildRuin(g, rand);
            default -> {
            }
        }
        geometry = g;
    }

    /** The height of its floor above the datum at a local point, or NaN off its floors. */
    float floorAt(float x, float z) {
        float best = Float.NaN;
        for (float[] f : floors) {
            boolean on = f[3] < 0f ? Math.hypot(x - f[0], z - f[1]) <= f[2] : Math.abs(x - f[0]) <= f[2] && Math.abs(z - f[1]) <= f[3];
            if (on && (Float.isNaN(best) || base + f[4] > best)) best = base + f[4];
        }
        return best;
    }

    /** How tall it stands above its base. */
    float tallest() {
        return switch (kind) {
            case SPIRE -> height;
            case STONES -> 8f * M;
            case RUIN -> height + 6f * M;
            default -> 0f;
        };
    }

    // ------------------------------------------------------------------ the great spire

    // How a spire is built (chosen with its size): twisting tiers, a chain of bulbs, a stack of
    // discs, a tower held up on curving legs, or one branching as it rises
    static final int TIERS = 0, BULBS = 1, DISCS = 2, LEGS = 3, BRANCHES = 4;

    private void buildSpire(Geometry g, Random rand) {
        int n = sides;
        float turn = rand.nextFloat() * (float) Math.PI;
        // Its plinth: three broad steps on foundations, a flight of steps down to the ground in front
        float plinth = halfWidth - 1f * M;
        int round = Math.max(n, 3) * 2;
        g.tube(FOUND, 0f, -drop, 0f, 0f, plinth + 1.2f * M, plinth + 1.2f * M, round, turn, false, true);
        for (int k = 0; k < 3; k++) {
            float r = plinth - k * 1.6f * M;
            g.tube(k == 2 ? STONE : STONE2, 0f, k * STEP, 0f, (k + 1) * STEP, r, r, round, turn, false, true);
            floors.add(new float[] { 0f, 0f, r * (float) Math.cos(Math.PI / round), -1f, (k + 1) * STEP });
        }
        frontSteps(g, plinth * (float) Math.cos(Math.PI / round), Math.min(radius * 0.6f, plinth * 0.5f));
        float y0 = 3 * STEP;

        // The body's outline: narrowing up it (straight, or curving in or out), swelling in
        // bulges, twisting round as it goes
        float bodyTop = height * (0.55f + rand.nextFloat() * 0.25f);
        float bottom = variant == LEGS ? height * (0.14f + rand.nextFloat() * 0.1f) : y0;
        int segments = 24;
        float tip = 0.1f + rand.nextFloat() * 0.35f, power = 0.5f + rand.nextFloat() * 1.8f;
        int bulges = variant == BULBS ? 3 + rand.nextInt(4) : rand.nextInt(4);
        float swell = variant == BULBS ? 0.7f + rand.nextFloat() * 0.6f : rand.nextFloat() * 0.35f;
        float twist = rand.nextFloat() < 0.65f ? (rand.nextFloat() - 0.5f) * 1.6f * (float) Math.PI / n : 0f;
        int bodySides = variant == BULBS ? Math.max(9, n * 2) : n;
        boolean windows = variant != BULBS && rand.nextFloat() < 0.7f;
        int tierEvery = 3 + rand.nextInt(4);
        float[] r = new float[segments + 1];
        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments;
            float taper = 1f + (tip - 1f) * (float) Math.pow(t, power);
            float bulge = (float) Math.pow(Math.sin(Math.PI * bulges * t), 2);
            r[i] = variant == BULBS ? radius * taper * (0.3f + swell * bulge) : radius * taper * (1f + swell * bulge);
        }
        for (int i = 0; i < segments; i++) {
            float ya = bottom + (bodyTop - bottom) * i / segments, yb = bottom + (bodyTop - bottom) * (i + 1) / segments;
            float a = turn + twist * i;
            g.tube(STONE, 0f, ya, 0f, yb, r[i], r[i + 1], bodySides, a, i == 0 && variant == LEGS, i == segments - 1);
            if (windows && i % 2 == 1) {
                float ym = (ya + yb) * 0.5f, rm = (r[i] + r[i + 1]) * 0.5f + 0.12f * M;
                g.tube(GLASS, 0f, ym - 0.9f * M, 0f, ym + 0.9f * M, rm, rm, bodySides, a, false, false);
            }
            // (ledges round it every few segments, bristling with shards on the stepped ones)
            if (variant == TIERS && i % tierEvery == tierEvery - 1 && i < segments - 1) {
                float ledge = r[i + 1] * 1.25f + 0.5f * M;
                g.tube(ORN, 0f, yb - 0.4f * M, 0f, yb + 0.6f * M, ledge, ledge, n, a, true, true);
                for (int c = 0; c < n; c++) {
                    float ca = a + (float) (Math.PI * 2 * c / n);
                    float px = (float) Math.sin(ca) * ledge * 0.9f, pz = (float) Math.cos(ca) * ledge * 0.9f;
                    float out = 0.25f + rand.nextFloat() * 0.25f;
                    g.tube(ORN, px, yb + 0.6f * M, pz, px * (1f + out), yb + 0.6f * M + 6f * M, pz * (1f + out), 0.7f * M, 0f, 3, 0f, true, false);
                }
            }
        }
        float rTop = r[segments];
        if (variant != LEGS) {
            circles.add(new float[] { 0f, 0f, r[0] });
            // A doorway into its foot, framed in its ornament
            float doorW = Math.min(r[0] * 0.5f, 4.5f * M), doorH = Math.min(bodyTop * 0.08f, 9f * M);
            g.box(DARK, 0f, y0 + doorH * 0.5f, r[0] * 0.75f, doorW * 0.5f, doorH * 0.5f, r[0] * 0.3f + 0.4f * M, 0f, 0f, 0f);
            g.box(ORN, 0f, y0 + doorH + 0.5f * M, r[0] * 0.8f, doorW * 0.5f + 0.8f * M, 0.5f * M, r[0] * 0.3f + 0.6f * M, 0f, 0f, 0f);
        }

        // Legs: curving down and out from under it to feet on the plinth
        if (variant == LEGS) {
            int legs = 3 + rand.nextInt(3);
            float spread = halfWidth - 7.5f * M, bow = 0.9f + rand.nextFloat() * 0.5f;
            for (int k = 0; k < legs; k++) {
                float a = turn + (float) (Math.PI * 2 * k / legs);
                float sx = (float) Math.sin(a), sz = (float) Math.cos(a);
                float[] foot = { sx * spread, y0, sz * spread }, top = { sx * r[0] * 0.7f, bottom + 2f * M, sz * r[0] * 0.7f };
                float[] bend = { sx * spread * bow, bottom * 0.75f, sz * spread * bow };
                g.curve(STONE2, bezier(foot, bend, top, 10), 1.8f * M, 1f * M, 6);
                circles.add(new float[] { foot[0], foot[2], 2.4f * M });
            }
        }
        // Discs round it, like plates on a stem
        int discs = variant == DISCS ? 3 + rand.nextInt(5) : rand.nextFloat() < 0.3f ? 1 + rand.nextInt(2) : 0;
        for (int k = 0; k < discs; k++) {
            float t = 0.15f + 0.8f * (k + rand.nextFloat() * 0.6f) / Math.max(1, discs);
            int i = Math.min(segments, Math.round(t * segments));
            float y = bottom + (bodyTop - bottom) * i / segments, wide = r[i] * (1.8f + rand.nextFloat() * 1.6f) + 2f * M;
            int slot = k % 2 == 0 ? ORN : STONE2;
            g.tube(slot, 0f, y - 1.6f * M, 0f, y, r[i], wide, Math.max(bodySides, 8), turn, false, false);
            g.tube(slot, 0f, y, 0f, y + 0.6f * M, wide, wide * 0.9f, Math.max(bodySides, 8), turn, false, true);
        }
        // Branches: arms curving up and out, tipped with orbs or shards
        if (variant == BRANCHES) {
            int levels = 2 + rand.nextInt(3), arms = 2 + rand.nextInt(4);
            boolean orbs = rand.nextBoolean();
            for (int l = 0; l < levels; l++) {
                float t = 0.3f + 0.55f * l / Math.max(1, levels - 1);
                int i = Math.round(t * segments);
                float y = bottom + (bodyTop - bottom) * i / segments, length = radius * (1.6f + rand.nextFloat() * 1.6f) * (1f - t * 0.4f);
                for (int k = 0; k < arms; k++) {
                    float a = turn + (float) (Math.PI * 2 * (k + l * 0.5f) / arms);
                    float sx = (float) Math.sin(a), sz = (float) Math.cos(a);
                    float[] from = { sx * r[i] * 0.8f, y, sz * r[i] * 0.8f };
                    float[] bend = { sx * (r[i] + length * 0.7f), y + length * 0.1f, sz * (r[i] + length * 0.7f) };
                    float[] end = { sx * (r[i] + length), y + length * 0.8f, sz * (r[i] + length) };
                    g.curve(STONE2, bezier(from, bend, end, 7), 1.1f * M, 0.5f * M, 5);
                    if (orbs) g.sphere(ORN, end[0], end[1] + 1.2f * M, end[2], 1.6f * M, 4);
                    else g.tube(ORN, end[0], end[1], end[2], end[0] * 1.05f, end[1] + 5f * M, end[2] * 1.05f, 0.8f * M, 0f, 3, 0f, true, false);
                }
            }
        }
        // A rib winding up round it
        if (rand.nextFloat() < 0.35f) {
            float turns = 2f + rand.nextFloat() * 4f;
            for (int k = 0; k < 140; k++) {
                float t = k / 140f;
                int i = Math.min(segments, Math.round(t * segments));
                float a = (float) (Math.PI * 2 * turns * t), rr = r[i] + 0.4f * M;
                g.box(ORN, (float) Math.sin(a) * rr, bottom + (bodyTop - bottom) * t, (float) Math.cos(a) * rr, 0.45f * M, 0.45f * M, 1.1f * M, a + (float) Math.PI / 2, 0.3f, 0f);
            }
        }
        // Rings hanging round it on spokes
        int halos = rand.nextFloat() < 0.4f ? 1 + rand.nextInt(3) : 0;
        for (int k = 0; k < halos; k++) {
            int i = Math.round((0.3f + rand.nextFloat() * 0.6f) * segments);
            float y = bottom + (bodyTop - bottom) * i / segments, ring = r[i] * 2f + 3f * M;
            g.ring(ORN, 0f, y, 0f, 0f, 1f, 0f, ring, 0.5f * M, 20);
            for (int s = 0; s < 3; s++) {
                float a = (float) (Math.PI * 2 * s / 3);
                g.tube(STONE2, (float) Math.sin(a) * r[i], y, (float) Math.cos(a) * r[i], (float) Math.sin(a) * ring, y, (float) Math.cos(a) * ring,
                        0.3f * M, 0.3f * M, 4, 0f, false, false);
            }
        }
        // Its crown, up to its full height
        float rise = height - bodyTop;
        switch (rand.nextInt(5)) {
            case 0 -> g.tube(ORN, 0f, bodyTop, 0f, height, rTop * 0.8f, 0f, n, turn, true, false);
            case 1 -> {
                g.tube(STONE2, 0f, bodyTop, 0f, height - rise * 0.35f, rTop * 0.4f, rTop * 0.3f, n, turn, false, false);
                g.sphere(ORN, 0f, height - rise * 0.25f, 0f, Math.max(rTop, rise * 0.12f), 6);
                g.tube(ORN, 0f, height - rise * 0.15f, 0f, height, 0.5f * M, 0f, 4, 0f, false, false);
            }
            case 2 -> {
                int prongs = 3 + rand.nextInt(3);
                for (int k = 0; k < prongs; k++) {
                    float a = turn + (float) (Math.PI * 2 * k / prongs), out = rise * (0.2f + rand.nextFloat() * 0.25f);
                    g.tube(ORN, (float) Math.sin(a) * rTop * 0.5f, bodyTop, (float) Math.cos(a) * rTop * 0.5f,
                            (float) Math.sin(a) * out, height - rand.nextFloat() * rise * 0.3f, (float) Math.cos(a) * out, rTop * 0.35f + 0.3f * M, 0f, 3, a, true, false);
                }
            }
            case 3 -> {
                g.tube(STONE2, 0f, bodyTop, 0f, bodyTop + rise * 0.3f, rTop * 0.5f, rTop * 0.35f, n, turn, false, false);
                float ring = rise * 0.32f;
                g.ring(ORN, 0f, bodyTop + rise * 0.3f + ring, 0f, (float) Math.cos(turn), 0f, (float) Math.sin(turn), ring, 0.8f * M, 22);
                g.sphere(ORN, 0f, bodyTop + rise * 0.3f + ring, 0f, ring * 0.3f, 5);
            }
            default -> {
                for (int k = 0; k < 7; k++) {
                    float a = rand.nextFloat() * (float) Math.PI * 2, lean = rand.nextFloat() * rise * 0.18f;
                    float tall = rise * (0.4f + rand.nextFloat() * 0.6f);
                    g.tube(k % 2 == 0 ? ORN : GLASS, (float) Math.sin(a) * rTop * 0.3f, bodyTop, (float) Math.cos(a) * rTop * 0.3f,
                            (float) Math.sin(a) * lean, bodyTop + tall, (float) Math.cos(a) * lean, rTop * (0.25f + rand.nextFloat() * 0.25f) + 0.3f * M, 0f, 4 + rand.nextInt(3), a, true, false);
                }
            }
        }
        looks = switch (variant) {
            case TIERS -> twist != 0f ? "A twisting, many-ledged spire" : "A spire of bristling ledges";
            case BULBS -> "A spire of swelling bulbs";
            case DISCS -> "A spire ringed with great discs";
            case LEGS -> "A spire held aloft on " + "curving legs";
            default -> "A spire branching as it climbs";
        };
    }

    /** Points along a quadratic curve from a, drawn towards bend, to b. */
    private static float[][] bezier(float[] a, float[] bend, float[] b, int steps) {
        float[][] points = new float[steps + 1][];
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps, u = 1f - t;
            points[i] = new float[] { u * u * a[0] + 2 * u * t * bend[0] + t * t * b[0], u * u * a[1] + 2 * u * t * bend[1] + t * t * b[1],
                    u * u * a[2] + 2 * u * t * bend[2] + t * t * b[2] };
        }
        return points;
    }

    /**
     * A flight of steps out from the front of a platform (its lowest step's front at local z
     * from), half wide across, each a step lower than the last, down to the ground in front.
     */
    private void frontSteps(Geometry g, float from, float half) {
        stairs(g, from, half, 0f, Float.NaN);
    }

    /**
     * Steps out from local z from, half wide, the first's top at top, each a step lower, down to
     * stop (or to the ground, if NaN).
     */
    private void stairs(Geometry g, float from, float half, float top0, float stop) {
        float tread = 0.55f * M;
        for (int k = 1; k < 60; k++) {
            float top = top0 - (k - 1) * STEP, z = from + (k - 0.5f) * tread;
            float ground = Math.min(groundAt(-half, z), Math.min(groundAt(0f, z), groundAt(half, z))) - base;
            float floor = Float.isNaN(stop) ? ground : Math.max(stop, ground);
            if (top <= floor + 0.05f * M) break;
            float bottom = Float.isNaN(stop) ? ground - 1f * M : Math.max(stop, ground) - 1f * M;
            g.box(STONE2, 0f, (top + bottom) * 0.5f, z, half, (top - bottom) * 0.5f, tread * 0.5f, 0f, 0f, 0f);
            floors.add(new float[] { 0f, z, half, tread * 0.5f, top });
        }
    }

    // ------------------------------------------------------------------ the monoliths

    // How the monoliths are laid out: in a spiral, in rings, along spokes, or scattered about
    static final int SPIRAL = 0, RINGS = 1, SPOKES = 2, SCATTER = 3;
    // What each one is: a leaning shard, a rough slab, a balanced stack, a cap on a stem, a ring on its edge
    private static final int SHARD = 0, SLAB = 1, STACK = 2, CAP = 3, HOOP = 4;

    private void buildStones(Geometry g, Random rand) {
        int kindA = rand.nextInt(5), kindB = rand.nextFloat() < 0.5f ? kindA : rand.nextInt(5);
        // (leaning in, out, or every which way)
        float lean = (rand.nextFloat() - 0.5f) * 0.7f, wild = rand.nextFloat() * 0.25f;
        float tall = (3.2f + rand.nextFloat() * 4f) * M;
        boolean tallerIn = rand.nextBoolean();
        List<float[]> spots = new ArrayList<>();
        switch (variant) {
            case SPIRAL -> {
                int count = 16 + rand.nextInt(16);
                float turns = 1.2f + rand.nextFloat() * 1.6f;
                for (int i = 0; i < count; i++) {
                    float t = (i + 1f) / count, a = (float) (Math.PI * 2 * turns * t), d = radius * (0.15f + 0.85f * t);
                    spots.add(new float[] { (float) Math.sin(a) * d, (float) Math.cos(a) * d, tallerIn ? 1.5f - t : 0.5f + t });
                }
            }
            case RINGS -> {
                int rings = 1 + rand.nextInt(3);
                for (int k = 0; k < rings; k++) {
                    float d = radius * (1f - k * (0.7f / rings)), gap = (3.5f + rand.nextFloat() * 3f) * M;
                    int count = Math.max(5, (int) (Math.PI * 2 * d / gap));
                    float offset = rand.nextFloat() * 6.3f, size = 0.6f + rand.nextFloat() * 0.8f;
                    for (int i = 0; i < count; i++) {
                        float a = offset + (float) (Math.PI * 2 * i / count);
                        spots.add(new float[] { (float) Math.sin(a) * d, (float) Math.cos(a) * d, size });
                    }
                }
            }
            case SPOKES -> {
                int spokes = 3 + rand.nextInt(5), along = 3 + rand.nextInt(4);
                float offset = rand.nextFloat() * 6.3f, curl = (rand.nextFloat() - 0.5f) * 1.2f;
                for (int s = 0; s < spokes; s++) {
                    for (int i = 0; i < along; i++) {
                        float t = (i + 1f) / along, a = offset + (float) (Math.PI * 2 * s / spokes) + curl * t, d = radius * (0.25f + 0.75f * t);
                        spots.add(new float[] { (float) Math.sin(a) * d, (float) Math.cos(a) * d, tallerIn ? 1.4f - t * 0.8f : 0.6f + t * 0.8f });
                    }
                }
            }
            default -> {
                for (int tries = 0; tries < 400 && spots.size() < 26; tries++) {
                    float a = rand.nextFloat() * 6.3f, d = radius * (float) Math.sqrt(rand.nextFloat());
                    float x = (float) Math.sin(a) * d, z = (float) Math.cos(a) * d;
                    boolean clear = d > 4f * M;
                    for (float[] s : spots) clear &= Math.hypot(s[0] - x, s[1] - z) > 4.5f * M;
                    if (clear) spots.add(new float[] { x, z, 0.5f + rand.nextFloat() * 1f });
                }
            }
        }
        List<float[]> tops = new ArrayList<>();
        for (int i = 0; i < spots.size(); i++) {
            float[] s = spots.get(i);
            if (rand.nextFloat() < 0.08f) {
                tops.add(null);
                continue;
            }
            tops.add(monolith(g, rand, i % 3 == 2 ? kindB : kindA, s[0], s[1], tall * s[2], lean, wild));
        }
        // Some neighbours bridged by arches of the glowing stone
        if (rand.nextFloat() < 0.5f) {
            for (int i = 0; i + 1 < tops.size(); i++) {
                float[] a = tops.get(i), b = tops.get(i + 1);
                if (a == null || b == null || rand.nextFloat() < 0.5f) continue;
                float span = (float) Math.hypot(b[0] - a[0], b[2] - a[2]);
                if (span > 9f * M) continue;
                float[] mid = { (a[0] + b[0]) * 0.5f, Math.max(a[1], b[1]) + span * 0.5f, (a[2] + b[2]) * 0.5f };
                g.curve(ORN, bezier(a, mid, b, 8), 0.45f * M, 0.45f * M, 5);
            }
        }
        // And in the middle, something greater
        float midGround = groundAt(0f, 0f) - base;
        switch (rand.nextInt(5)) {
            case 0 -> {
                float a = rand.nextFloat() * 6.3f;
                g.tube(STONE, 0f, midGround - 1f * M, 0f, (float) Math.sin(a) * tall * 0.4f, midGround + tall * 2.4f, (float) Math.cos(a) * tall * 0.4f,
                        2.2f * M, 0f, sides, a, false, false);
                circles.add(new float[] { 0f, 0f, 2.6f * M });
                looks2 = "a great shard leaning at its heart";
            }
            case 1 -> {
                g.blob(STONE2, rand, 0f, midGround + 0.6f * M, 0f, 1.2f * M, 1f * M, 1.2f * M, 4, 0.3f);
                g.blob(STONE, rand, 0.3f * M, midGround + 1.4f * M + tall * 0.45f, 0f, tall * 0.55f, tall * 0.45f, tall * 0.5f, 5, 0.25f);
                circles.add(new float[] { 0f, 0f, tall * 0.5f });
                looks2 = "a vast boulder balanced on a pebble at its heart";
            }
            case 2 -> {
                float a = rand.nextFloat() * 6.3f, ring = tall * 1.1f;
                g.ring(ORN, 0f, midGround + ring - 0.6f * M, 0f, (float) Math.sin(a), 0f, (float) Math.cos(a), ring, 0.9f * M, 24);
                looks2 = "a ring of glowing stone standing on its edge at its heart";
            }
            case 3 -> {
                g.tube(ORN, 0f, midGround - 1f * M, 0f, midGround + 0.5f * M, 4f * M, 3.6f * M, 12, 0f, false, true);
                g.tube(DARK, 0f, midGround + 0.5f * M, 0f, midGround + 0.55f * M, 2.8f * M, 2.8f * M, 12, 0f, false, true);
                looks2 = "a dark round pool at its heart";
            }
            default -> looks2 = "";
        }
        looks = switch (variant) {
            case SPIRAL -> "A spiral of ";
            case RINGS -> "Rings of ";
            case SPOKES -> "Rows radiating out of ";
            default -> "A scattered field of ";
        } + switch (kindA) {
            case SHARD -> "leaning shards";
            case SLAB -> "rough monoliths";
            case STACK -> "balanced stones";
            case CAP -> "capped stone stems";
            default -> "stone rings";
        };
    }

    // (what's at the heart of the monoliths, for the description)
    String looks2 = "";

    /** One monolith of a kind, its foot at (x, z), so tall, leaning out from the middle by lean (and wildly); its top {x, y, z}. */
    private float[] monolith(Geometry g, Random rand, int kind, float x, float z, float tall, float lean, float wild) {
        float d = (float) Math.hypot(x, z);
        float ox = d > 0.1f ? x / d : 0f, oz = d > 0.1f ? z / d : 1f;
        float lx = ox * lean + (rand.nextFloat() - 0.5f) * wild, lz = oz * lean + (rand.nextFloat() - 0.5f) * wild;
        float foot = groundAt(x, z) - base;
        float yaw = (float) Math.atan2(ox, oz);
        switch (kind) {
            case SHARD -> {
                float r = (0.8f + rand.nextFloat() * 0.6f) * M;
                float[] top = { x + lx * tall, foot + tall, z + lz * tall };
                float[] mid = { x + lx * tall * 0.55f + (rand.nextFloat() - 0.5f) * M, foot + tall * 0.55f, z + lz * tall * 0.55f };
                g.tube(STONE, x, foot - 1f * M, z, mid[0], mid[1], mid[2], r, r * 0.75f, sides, yaw, false, false);
                g.tube(STONE, mid[0], mid[1], mid[2], top[0], top[1], top[2], r * 0.75f, 0f, sides, yaw, false, false);
                circles.add(new float[] { x, z, r + 0.3f * M });
                return top;
            }
            case SLAB -> {
                g.block(STONE, rand, x, foot - 1.2f * M, z, (0.9f + rand.nextFloat() * 0.6f) * M, (0.4f + rand.nextFloat() * 0.3f) * M, tall + 1.2f * M, yaw + (float) Math.PI / 2, lx, lz);
                circles.add(new float[] { x, z, 1.3f * M });
                return new float[] { x + lx * tall, foot + tall, z + lz * tall };
            }
            case STACK -> {
                float y = foot - 0.3f * M, r = (1.1f + rand.nextFloat() * 0.5f) * M, sx = x, sz = z;
                int stones = 2 + rand.nextInt(3);
                for (int k = 0; k < stones; k++) {
                    float ry = r * (0.55f + rand.nextFloat() * 0.3f);
                    g.blob(k % 2 == 0 ? STONE : STONE2, rand, sx, y + ry, sz, r, ry, r * (0.8f + rand.nextFloat() * 0.3f), 4, 0.25f);
                    y += ry * 1.9f;
                    sx += lx * ry * 2f;
                    sz += lz * ry * 2f;
                    r *= 0.7f + rand.nextFloat() * 0.2f;
                }
                circles.add(new float[] { x, z, 1.4f * M });
                return new float[] { sx, y, sz };
            }
            case CAP -> {
                float stem = (0.4f + rand.nextFloat() * 0.3f) * M, cap = (1.8f + rand.nextFloat() * 1.4f) * M;
                float[] top = { x + lx * tall, foot + tall, z + lz * tall };
                g.tube(STONE2, x, foot - 0.8f * M, z, top[0], top[1], top[2], stem * 1.4f, stem, sides + 2, 0f, false, false);
                g.tube(STONE, top[0], top[1] - cap * 0.25f, top[2], top[0], top[1] + cap * 0.35f, top[2], cap, cap * 0.2f, 9, 0f, true, true);
                circles.add(new float[] { x, z, stem * 1.6f + 0.3f * M });
                return new float[] { top[0], top[1] + cap * 0.35f, top[2] };
            }
            default -> {
                float ring = tall * 0.45f;
                g.ring(STONE, x, foot + ring - 0.5f * M, z, ox, 0f, oz, ring, 0.55f * M, 16);
                circles.add(new float[] { x - oz * ring * 0.8f, z + ox * ring * 0.8f, 0.8f * M });
                circles.add(new float[] { x + oz * ring * 0.8f, z - ox * ring * 0.8f, 0.8f * M });
                return new float[] { x, foot + ring * 2f, z };
            }
        }
    }

    // ------------------------------------------------------------------ the ancient temple

    // Its platform: a long rectangle, a round many-sided one, or stepped up in terraces
    static final int RECT = 0, ROUND = 1, TERRACES = 2;

    private void buildRuin(Geometry g, Random rand) {
        float w = halfWidth, d = halfDepth;
        int n = Math.max(5, sides + 2);
        boolean polygon = variant == ROUND || (variant == TERRACES && rand.nextBoolean());
        float polyTurn = (float) Math.PI / n;
        // The platform: three steps, or terraces each high enough to climb by its own stairs
        int terraces = variant == TERRACES ? 2 + rand.nextInt(2) : 1;
        float terraceHigh = variant == TERRACES ? (1.8f + rand.nextFloat() * 1f) * M : 3 * STEP;
        float inset = (4.5f + rand.nextFloat() * 2f) * M;
        float top = 0f;
        float tw = w, td = d;
        for (int k = 0; k < terraces; k++) {
            tw = w - k * inset;
            td = d - k * inset;
            float bottom = k == 0 ? -drop : top;
            float y1 = top + terraceHigh;
            if (k == 0 && variant != TERRACES) {
                // (three walkable steps)
                platform(g, polygon, FOUND, tw + 0.4f * M, td + 0.4f * M, -drop, 0f, polyTurn, n);
                for (int s = 0; s < 3; s++) {
                    float in = s * 0.9f * M;
                    platform(g, polygon, s == 2 ? STONE : STONE2, tw - in, td - in, s * STEP, (s + 1) * STEP, polyTurn, n);
                }
            } else {
                platform(g, polygon, k % 2 == 0 ? STONE : STONE2, tw, td, bottom, y1, polyTurn, n);
                // (a trim of the ornament round its top edge)
                platform(g, polygon, ORN, tw + 0.25f * M, td + 0.25f * M, y1 - 0.5f * M, y1 - 0.15f * M, polyTurn, n);
            }
            float front = polygon ? tw * (float) Math.cos(Math.PI / n) : td;
            if (variant != TERRACES) frontSteps(g, front, Math.min(tw, td) * 0.45f);
            else stairs(g, front, Math.min(tw, td) * 0.35f, y1 - STEP, k == 0 ? Float.NaN : top);
            top = y1;
        }
        float floor = top;
        float fw = tw, fd = td;

        // The pillars: their shape and crowns all of a kind, in this temple's own style
        int colSides = 3 + rand.nextInt(6);
        float colR = (0.7f + rand.nextFloat() * 0.5f) * M, shaft = height;
        float twist = rand.nextFloat() < 0.5f ? (rand.nextFloat() - 0.5f) * 1.4f : 0f;
        int profile = rand.nextInt(4), capital = rand.nextInt(4);
        boolean ringed = rand.nextFloat() < 0.35f;
        // Laid out round the edge, in rings, down an avenue, or in a spiral in towards the middle
        int layout = polygon ? (rand.nextBoolean() ? 1 : 3) : rand.nextInt(4);
        List<float[]> columns = new ArrayList<>();
        float gap = (3.8f + rand.nextFloat() * 2f) * M;
        switch (layout) {
            case 0 -> {
                float xs = fw - 2f * M - colR, zs = fd - 2f * M - colR;
                int across = Math.max(3, Math.round(xs * 2f / gap)) + 1, along = Math.max(4, Math.round(zs * 2f / gap)) + 1;
                for (int i = 0; i < across; i++) {
                    float x = -xs + 2f * xs * i / (across - 1);
                    columns.add(new float[] { x, zs, 0 });
                    columns.add(new float[] { x, -zs, 2 });
                }
                for (int j = 1; j < along - 1; j++) {
                    float z = -zs + 2f * zs * j / (along - 1);
                    columns.add(new float[] { -xs, z, 1 });
                    columns.add(new float[] { xs, z, 1 });
                }
            }
            case 1 -> {
                int rings = 1 + rand.nextInt(2);
                for (int k = 0; k < rings; k++) {
                    float r = (Math.min(fw, fd) - 2f * M - colR) * (1f - k * 0.45f);
                    int count = Math.max(5, (int) (Math.PI * 2 * r / gap));
                    for (int i = 0; i < count; i++) {
                        float a = (float) (Math.PI * 2 * i / count) + k * 0.3f;
                        columns.add(new float[] { (float) Math.sin(a) * r, (float) Math.cos(a) * r, (float) Math.cos(a) > 0.5f ? 0 : 1 });
                    }
                }
            }
            case 2 -> {
                float xs = Math.min(fw * 0.45f, 6f * M);
                int along = Math.max(4, Math.round(fd * 1.6f / gap));
                for (int j = 0; j < along; j++) {
                    float z = fd - 2f * M - j * gap;
                    columns.add(new float[] { -xs, z, j < 2 ? 0 : 1 });
                    columns.add(new float[] { xs, z, j < 2 ? 0 : 1 });
                }
            }
            default -> {
                int count = 14 + rand.nextInt(10);
                float outer = Math.min(fw, fd) - 2f * M - colR;
                for (int i = 0; i < count; i++) {
                    float t = i / (float) count, a = (float) (Math.PI * 2 * 1.4f * t) + (float) Math.PI * 0.5f, r = outer * (1f - 0.7f * t);
                    columns.add(new float[] { (float) Math.sin(a) * r, (float) Math.cos(a) * r, t < 0.15f ? 0 : 1 });
                }
            }
        }
        List<float[]> whole = new ArrayList<>();
        for (float[] c : columns) {
            float roll = rand.nextFloat();
            float keep = c[2] == 0 ? 0.9f : c[2] == 1 ? 0.5f : 0.25f, broken = c[2] == 0 ? 1f : 0.85f;
            float x = c[0], z = c[1];
            if (roll < keep) {
                pillar(g, rand, x, z, floor, shaft, colR, colSides, twist, profile, capital, ringed, true);
                whole.add(new float[] { x, floor + shaft, z });
            } else if (roll < broken) {
                pillar(g, rand, x, z, floor, shaft * (0.2f + rand.nextFloat() * 0.6f), colR, colSides, twist, profile, capital, ringed, false);
            } else {
                // (fallen: its pieces lie where they rolled)
                g.tube(STONE, x, floor, z, x, floor + 0.5f * M, z, colR * 1.4f, colR * 1.3f, colSides, 0f, false, true);
                float yaw = rand.nextFloat() * (float) Math.PI * 2;
                for (int k = 0; k < 2 + rand.nextInt(3); k++) {
                    float dist = (2.5f + k * 2.3f + rand.nextFloat()) * M;
                    float px = x + (float) Math.sin(yaw) * dist, pz = z + (float) Math.cos(yaw) * dist;
                    float y = Float.isNaN(floorAt(px, pz)) ? groundAt(px, pz) - base : floorAt(px, pz) - base;
                    float tx = (float) Math.sin(yaw + 1.6f) * M, tz = (float) Math.cos(yaw + 1.6f) * M;
                    g.tube(STONE, px - tx, y + colR * 0.85f, pz - tz, px + tx, y + colR * 0.85f, pz + tz, colR * 0.9f, colR * 0.9f, colSides, 0f, true, true);
                    yaw += (rand.nextFloat() - 0.5f) * 0.4f;
                }
            }
            circles.add(new float[] { x, z, colR * 1.35f });
        }
        // What the pillars held up: ribs arching in to a point over the middle, beams from one to
        // the next, or a ring floating over them; mostly fallen
        int roof = rand.nextInt(3);
        float apex = floor + shaft * (1.5f + rand.nextFloat() * 0.6f);
        for (int i = 0; i < whole.size(); i++) {
            float[] c = whole.get(i);
            if (roof == 0) {
                if (rand.nextFloat() < 0.45f) continue;
                float[] from = { c[0], c[1] + 1f * M, c[2] }, peak = { 0f, apex, 0f };
                float[] bend = { c[0] * 0.55f, apex * 1.02f, c[2] * 0.55f };
                float[][] arc = bezier(from, bend, peak, 10);
                // (broken off part way, most of them)
                int keep = rand.nextFloat() < 0.3f ? arc.length : 3 + rand.nextInt(arc.length - 4);
                g.curve(STONE2, java.util.Arrays.copyOf(arc, keep), colR * 0.8f, colR * 0.55f, Math.max(3, colSides));
                if (keep < arc.length) ornament(g, arc[keep - 1][0], arc[keep - 1][1], arc[keep - 1][2], 0.5f * M);
            } else if (roof == 1 && i + 1 < whole.size()) {
                float[] e = whole.get(i + 1);
                float span = (float) Math.hypot(e[0] - c[0], e[2] - c[2]);
                if (span > gap * 1.6f || rand.nextFloat() < 0.25f) continue;
                float yaw = (float) Math.atan2(e[0] - c[0], e[2] - c[2]);
                float mx = (c[0] + e[0]) * 0.5f, mz = (c[2] + e[2]) * 0.5f, y = c[1] + 1.4f * M;
                g.box(STONE, mx, y, mz, colR * 1.1f, 0.7f * M, span * 0.5f + colR, yaw, 0f, 0f);
                ornament(g, mx, y + 1.2f * M, mz, 0.55f * M);
            }
        }
        if (roof == 2 && !whole.isEmpty()) {
            // (a ring over the pillars, some of its pieces still up there)
            float ring = 0f;
            for (float[] c : whole) ring = Math.max(ring, (float) Math.hypot(c[0], c[2]));
            float y = floor + shaft + 3f * M;
            int pieces = 28;
            for (int k = 0; k < pieces; k++) {
                if (rand.nextFloat() < 0.4f) continue;
                double a0 = Math.PI * 2 * k / pieces, a1 = Math.PI * 2 * (k + 1) / pieces;
                g.tube(ORN, (float) Math.sin(a0) * ring, y, (float) Math.cos(a0) * ring, (float) Math.sin(a1) * ring, y, (float) Math.cos(a1) * ring,
                        0.9f * M, 0.9f * M, 5, 0f, true, true);
            }
        }
        // At its heart
        String heart;
        switch (rand.nextInt(4)) {
            case 0 -> {
                g.tube(STONE2, 0f, floor, 0f, floor + 2.5f * M, 2.5f * M, 1.8f * M, colSides, 0f, false, true);
                g.sphere(ORN, 0f, floor + 2.5f * M + shaft * 0.22f, 0f, shaft * 0.22f, 7);
                circles.add(new float[] { 0f, 0f, 2.8f * M });
                heart = "a great orb";
            }
            case 1 -> {
                g.tube(ORN, 0f, floor, 0f, 0.8f * M, floor + shaft * 1.6f, 0.4f * M, 2f * M, 0f, colSides, 0.3f, false, false);
                circles.add(new float[] { 0f, 0f, 2.3f * M });
                heart = "a soaring shard";
            }
            case 2 -> {
                float ring = shaft * 0.45f;
                g.ring(ORN, 0f, floor + ring, 0f, 0f, 0f, 1f, ring, 0.8f * M, 24);
                heart = "a ring-shaped gate";
            }
            default -> {
                // (a sanctum's walls, broken off at all heights)
                float sw = Math.min(fw, fd) * 0.4f;
                int m = polygon ? n : 4;
                for (int k = 0; k < m; k++) {
                    double a0 = Math.PI * 2 * k / m + Math.PI / m, a1 = Math.PI * 2 * (k + 1) / m + Math.PI / m;
                    if (k == 0) continue;
                    cellaWall(g, rand, (float) Math.sin(a0) * sw, (float) Math.cos(a0) * sw, (float) Math.sin(a1) * sw, (float) Math.cos(a1) * sw, floor);
                }
                heart = "a broken sanctum";
            }
        }
        // Rubble about the platform and the ground round it
        for (int k = 0; k < 14 + rand.nextInt(10); k++) {
            float x = (rand.nextFloat() * 2f - 1f) * (w + 6f * M), z = (rand.nextFloat() * 2f - 1f) * (d + 6f * M);
            float s = (0.35f + rand.nextFloat() * 0.6f) * M;
            float y = Float.isNaN(floorAt(x, z)) ? groundAt(x, z) - base : floorAt(x, z) - base;
            g.blob(rand.nextBoolean() ? STONE : STONE2, rand, x, y + s * 0.5f, z, s * (1f + rand.nextFloat()), s, s * (1f + rand.nextFloat()), 3, 0.35f);
        }
        // Guardians either side of the way up
        int guardian = rand.nextInt(3);
        float front = polygon ? w * (float) Math.cos(Math.PI / n) : d;
        for (float side : new float[] { -1f, 1f }) {
            float x = side * Math.min(w, d) * 0.62f, z = front + 5f * M;
            float foot = groundAt(x, z) - base, tall = shaft * 0.8f;
            g.tube(STONE2, x, foot - 1.5f * M, z, x, foot + 1.2f * M, z, 1.9f * M, 1.7f * M, colSides, 0f, false, true);
            switch (guardian) {
                case 0 -> g.tube(STONE, x, foot + 1.2f * M, z, x + side * tall * 0.15f, foot + 1.2f * M + tall, z, 1.3f * M, 0f, colSides, 0f, false, false);
                case 1 -> {
                    g.tube(STONE, x, foot + 1.2f * M, z, x, foot + tall * 0.6f, z, 0.6f * M, 0.9f * M, colSides, 0f, false, true);
                    g.sphere(ORN, x, foot + tall * 0.6f + 1.6f * M, z, 1.8f * M, 5);
                }
                default -> g.ring(ORN, x, foot + 1.2f * M + tall * 0.3f, z, 0f, 0f, 1f, tall * 0.3f, 0.5f * M, 16);
            }
            circles.add(new float[] { x, z, 2.2f * M });
        }
        looks = (variant == TERRACES ? "A terraced temple" : variant == ROUND ? "A round temple" : "A long temple")
                + " of " + switch (profile) {
                    case 0 -> "tapering";
                    case 1 -> "swollen";
                    case 2 -> "top-heavy";
                    default -> "slender";
                } + (twist != 0f ? ", twisted" : "") + " pillars about " + heart;
    }

    /** A temple's platform (or a terrace of it): a rectangle, or many-sided, from bottom to top. */
    private void platform(Geometry g, boolean polygon, int slot, float hw, float hd, float bottom, float top, float turn, int n) {
        if (polygon) {
            g.tube(slot, 0f, bottom, 0f, top, hw, hw, n, turn, false, true);
            floors.add(new float[] { 0f, 0f, hw * (float) Math.cos(Math.PI / n), -1f, top });
        } else {
            g.box(slot, 0f, (bottom + top) * 0.5f, 0f, hw, (top - bottom) * 0.5f, hd, 0f, 0f, 0f);
            floors.add(new float[] { 0f, 0f, hw, hd, top });
        }
    }

    /**
     * A pillar standing to height (a broken one ends ragged, a whole one crowned): its profile
     * (tapering, swollen, wider at the top, or slender), twisting as it rises, perhaps ringed.
     */
    private void pillar(Geometry g, Random rand, float x, float z, float floor, float shaft, float r, int sides, float twist,
                        int profile, int capital, boolean ringed, boolean whole) {
        g.tube(STONE2, x, floor, z, x, floor + 0.6f * M, z, r * 1.5f, r * 1.3f, sides, 0f, false, true);
        int pieces = 6;
        float full = height;
        float y = floor + 0.6f * M;
        for (int k = 0; k < pieces; k++) {
            float t0 = k / (float) pieces * shaft / full, t1 = (k + 1) / (float) pieces * shaft / full;
            float r0 = r * profileAt(profile, t0), r1 = r * profileAt(profile, t1);
            float y1 = floor + 0.6f * M + shaft * (k + 1) / pieces;
            g.tube(STONE, x, y, z, x, y1, z, r0, r1, sides, twist * k, false, k == pieces - 1);
            if (ringed && k % 2 == 1) g.ring(ORN, x, y1, z, 0f, 1f, 0f, r1 * 1.1f, 0.18f * M, Math.max(6, sides * 2));
            y = y1;
        }
        if (!whole) return;
        float rt = r * profileAt(profile, 1f);
        switch (capital) {
            case 0 -> g.sphere(ORN, x, y + rt * 1.2f, z, rt * 1.3f, 4);
            case 1 -> {
                for (int k = 0; k < 4; k++) {
                    float a = (float) (Math.PI * k / 2) + 0.4f;
                    g.tube(ORN, x, y, z, x + (float) Math.sin(a) * rt * 2.2f, y + rt * 2.4f, z + (float) Math.cos(a) * rt * 2.2f, rt * 0.35f, 0f, 3, 0f, true, false);
                }
            }
            case 2 -> g.ring(ORN, x, y + rt * 0.6f, z, 0f, 1f, 0f, rt * 1.6f, rt * 0.25f, 10);
            default -> g.tube(ORN, x, y, z, x, y + rt * 1.2f, z, rt * 0.9f, rt * 1.9f, sides, 0f, false, true);
        }
    }

    /** A pillar's radius up it, as a share of its foot's, for a profile (t from 0 at its foot to 1 at its top). */
    private static float profileAt(int profile, float t) {
        return switch (profile) {
            case 0 -> 1f - 0.3f * t;
            case 1 -> 0.75f + 0.55f * (float) Math.sin(Math.PI * t);
            case 2 -> 0.65f + 0.6f * t * t;
            default -> 0.7f;
        };
    }

    /** A carved ornament, in the region's manner. */
    private void ornament(Geometry g, float x, float y, float z, float s) {
        switch (crest) {
            case ORBS, RINGS -> g.sphere(ORN, x, y, z, s, 3);
            case TEETH, SPIKES -> g.tube(ORN, x, y - s, z, x, y + s * 1.5f, z, s, 0f, 4, 0f, true, false);
            default -> g.box(ORN, x, y, z, s * 0.7f, s * 0.7f, s * 0.7f, (float) Math.PI / 4, 0f, 0f);
        }
    }

    /** A stretch of wall, its top broken off at varying heights. */
    private void cellaWall(Geometry g, Random rand, float x0, float z0, float x1, float z1, float floor) {
        float length = (float) Math.hypot(x1 - x0, z1 - z0);
        if (length < 0.5f * M) return;
        int pieces = Math.max(1, Math.round(length / (2.6f * M)));
        float yaw = (float) Math.atan2(x1 - x0, z1 - z0);
        float tallest = 0f;
        for (int k = 0; k < pieces; k++) {
            float t = (k + 0.5f) / pieces, hgt = (1.2f + rand.nextFloat() * 6.5f) * M;
            if (rand.nextFloat() < 0.15f) continue;
            tallest = Math.max(tallest, hgt);
            g.box(STONE2, x0 + (x1 - x0) * t, floor + hgt * 0.5f, z0 + (z1 - z0) * t, 0.65f * M, hgt * 0.5f, length * 0.5f / pieces + 0.02f * M, yaw, 0f, 0f);
        }
        if (tallest > 0f) walls.add(new float[] { x0, z0, x1, z1, 0.8f * M, floor + tallest });
    }

    // ------------------------------------------------------------------ its picture

    // The light in the pictures: from the front right, a little above
    private static final float[] SUN = normalise(new float[] { 0.55f, 0.72f, 0.42f });

    /**
     * A picture of it as a visitor would see it on a fine day (for the map's popup): a built one
     * stood on the land round it, a natural one the land itself; height pixels tall, on a sky.
     */
    public BufferedImage render(int heightPx) {
        int widthPx = pictureWidth(heightPx);
        SoftwareRenderer r = new SoftwareRenderer(widthPx, heightPx, 3);
        float aspect = widthPx / (float) heightPx;
        if (kind.built) {
            // Seen from the front, off to one side, from a little way up
            float extent = kind == Kind.SPIRE ? height * 0.56f : reach * (kind == Kind.STONES ? 0.62f : 0.7f);
            float elevation = (float) Math.toRadians(kind == Kind.SPIRE ? 6 : kind == Kind.STONES ? 24 : 20);
            float fov = 32f;
            float fit = kind == Kind.SPIRE ? extent : extent / Math.min(1f, aspect * 0.8f);
            float distance = fit / (float) Math.tan(Math.toRadians(fov * 0.5f)) * 1.05f;
            float azimuth = (float) Math.toRadians(kind == Kind.SPIRE ? 28 : 34);
            float[] target = { 0f, kind == Kind.SPIRE ? height * 0.48f : tallest() * 0.25f, 0f };
            float[] eye = { target[0] + (float) (Math.sin(azimuth) * Math.cos(elevation)) * distance,
                    target[1] + (float) Math.sin(elevation) * distance, target[2] + (float) (Math.cos(azimuth) * Math.cos(elevation)) * distance };
            r.lookAt(eye, target, fov);
            land(r, kind == Kind.SPIRE ? height * 1.6f : reach * 3f, 40, 0f, 1f, null);
            for (int i = 0; i < geometry.faces.size(); i++) {
                float[][] corners = geometry.faces.get(i);
                float[] n = geometry.normals.get(i);
                Vector3 c = colours[geometry.slots.get(i)];
                float s = shine[geometry.slots.get(i)];
                float[] lit = shade(c, n, s);
                SoftwareRenderer.SurfaceShader shader = (p, normal, u, v) -> lit;
                float[] a = SoftwareRenderer.vertex(corners[0], n, 0, 0);
                for (int k = 1; k < corners.length - 1; k++) {
                    r.triangle(a, SoftwareRenderer.vertex(corners[k], n, 0, 0), SoftwareRenderer.vertex(corners[k + 1], n, 0, 0), shader);
                }
            }
        } else {
            float elevation = (float) Math.toRadians(switch (kind) {
                case SUMMIT -> 24;
                case BAY -> 48;
                case SPIT -> 40;
                default -> 22;
            });
            float rise = kind == Kind.SUMMIT ? 1.35f : kind == Kind.GROVE ? 1f : 2.2f;
            float peak = kind == Kind.SUMMIT ? (groundAt(0f, 0f) - seaLevel) * rise : 0f;
            float[] target = { 0f, kind == Kind.SUMMIT ? peak * 0.45f : 0f, kind == Kind.SUMMIT ? 0f : -span * 0.05f };
            float distance = span * (kind == Kind.GROVE ? 0.75f : 0.95f);
            float azimuth = (float) Math.toRadians(20);
            float[] eye = { (float) (Math.sin(azimuth) * Math.cos(elevation)) * distance, target[1] + (float) Math.sin(elevation) * distance,
                    (float) (Math.cos(azimuth) * Math.cos(elevation)) * distance };
            r.lookAt(eye, target, 40f);
            base = seaLevel;
            land(r, span * 1.6f, 72, seaLevel, rise, kind == Kind.GROVE ? new Random(seed) : null);
        }
        BufferedImage drawn = r.image();
        // On a sky, in a frame
        BufferedImage out = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, colour(look.zenith()), 0, heightPx * 0.75f, colour(look.horizon())));
        g.fillRect(0, 0, widthPx, heightPx);
        g.drawImage(drawn, 0, 0, null);
        g.dispose();
        return framed(out);
    }

    /** Whether its picture is tall (a spire's) rather than wide. */
    boolean tallPicture() {
        return kind == Kind.SPIRE;
    }

    /** A picture's width for its height, by its shape. */
    int pictureWidth(int heightPx) {
        return Math.round(heightPx * (tallPicture() ? 0.78f : 1.45f));
    }

    /** A picture in the pale frame every landmark's has on the map. */
    static BufferedImage framed(BufferedImage picture) {
        BufferedImage out = new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(picture, 0, 0, null);
        g.setColor(new Color(255, 255, 255, 170));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(1, 1, out.getWidth() - 3, out.getHeight() - 3);
        g.dispose();
        return out;
    }

    /**
     * The land round it into a picture: a grid of the ground's heights (above base, raised by
     * rise) span across, coloured as it is (sand by the sea, grass, rock on the steeps, snow up
     * high), the sea level across it; and for a grove, its trees.
     */
    private void land(SoftwareRenderer r, float across, int cells, float sea, float rise, Random trees) {
        float[][] h = new float[cells + 1][cells + 1];
        for (int i = 0; i <= cells; i++) {
            for (int j = 0; j <= cells; j++) {
                float x = (i / (float) cells - 0.5f) * across, z = (j / (float) cells - 0.5f) * across;
                h[i][j] = (groundAt(x, z) - base) * rise;
            }
        }
        float seaY = (seaLevel - base) * rise;
        float cell = across / cells;
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < cells; j++) {
                float x0 = (i / (float) cells - 0.5f) * across, z0 = (j / (float) cells - 0.5f) * across;
                float[] a = { x0, h[i][j], z0 }, b = { x0 + cell, h[i + 1][j], z0 }, c = { x0 + cell, h[i + 1][j + 1], z0 + cell }, d = { x0, h[i][j + 1], z0 + cell };
                // (fading off at the picture's edges, so the land has no hard rim)
                float edge = Math.max(Math.abs(i + 0.5f - cells * 0.5f), Math.abs(j + 0.5f - cells * 0.5f)) / (cells * 0.5f);
                for (float[][] tri : new float[][][] { { a, c, b }, { a, d, c } }) {
                    float[] n = normalise(cross(sub(tri[1], tri[0]), sub(tri[2], tri[0])));
                    if (n[1] < 0) n = new float[] { -n[0], -n[1], -n[2] };
                    float y = (tri[0][1] + tri[1][1] + tri[2][1]) / 3f;
                    float[] lit = shade(landColour((y - seaY) / rise, n[1], edge), n, 2f);
                    SoftwareRenderer.SurfaceShader shader = (p, normal, u, v) -> lit;
                    r.triangle(SoftwareRenderer.vertex(tri[0], n, 0, 0), SoftwareRenderer.vertex(tri[1], n, 0, 0), SoftwareRenderer.vertex(tri[2], n, 0, 0), shader);
                }
            }
        }
        // Out to the horizon: the land beyond, lost in the haze, level with the lowest of the
        // picture's edge, and the sea wherever it's higher than that
        float edgeLow = Float.MAX_VALUE;
        for (int k = 0; k <= cells; k++) {
            edgeLow = Math.min(edgeLow, Math.min(Math.min(h[k][0], h[k][cells]), Math.min(h[0][k], h[cells][k])));
        }
        float far = across * 60f;
        flat(r, far, edgeLow - 0.5f, look.distance(), 2f);
        if (seaY > minOf(h) - 1f || seaY > edgeLow) flat(r, far, seaY, look.sea(), 32f);
        if (trees == null) return;
        // The grove: its trees standing thick all about
        Geometry g = new Geometry();
        for (int k = 0; k < 140; k++) {
            float x = (trees.nextFloat() - 0.5f) * across * 0.8f, z = (trees.nextFloat() - 0.5f) * across * 0.8f;
            float y = (groundAt(x, z) - base) * rise;
            if (y <= seaY + 0.5f) continue;
            float tall = across * (0.035f + trees.nextFloat() * 0.025f);
            g.tube(STONE2, x, y, z, x, y + tall * 0.45f, z, tall * 0.06f, tall * 0.045f, 5, 0f, false, false);
            if (trees.nextBoolean()) g.sphere(STONE, x, y + tall * 0.68f, z, tall * 0.3f, 4);
            else g.tube(STONE, x, y + tall * 0.25f, z, x, y + tall * 1.1f, z, tall * 0.3f, 0f, 7, 0f, true, false);
        }
        Vector3[] grove = { leaves != null ? leaves : new Vector3(0.25f, 0.45f, 0.2f), trunk != null ? trunk : new Vector3(0.35f, 0.25f, 0.15f) };
        for (int i = 0; i < g.faces.size(); i++) {
            float[][] corners = g.faces.get(i);
            float[] n = g.normals.get(i);
            float[] lit = shade(grove[g.slots.get(i)], n, 4f);
            SoftwareRenderer.SurfaceShader shader = (p, normal, u, v) -> lit;
            float[] a = SoftwareRenderer.vertex(corners[0], n, 0, 0);
            for (int k = 1; k < corners.length - 1; k++) {
                r.triangle(a, SoftwareRenderer.vertex(corners[k], n, 0, 0), SoftwareRenderer.vertex(corners[k + 1], n, 0, 0), shader);
            }
        }
    }

    /** A level square across the picture, its middle under the landmark, at height y. */
    private static void flat(SoftwareRenderer r, float across, float y, Vector3 colour, float shininess) {
        float half = across * 0.5f;
        float[] up = { 0f, 1f, 0f };
        float[] lit = shade(colour, up, shininess);
        SoftwareRenderer.SurfaceShader shader = (p, normal, u, v) -> lit;
        float[] a = SoftwareRenderer.vertex(new float[] { -half, y, -half }, up, 0, 0), b = SoftwareRenderer.vertex(new float[] { half, y, -half }, up, 0, 0);
        float[] c = SoftwareRenderer.vertex(new float[] { half, y, half }, up, 0, 0), d = SoftwareRenderer.vertex(new float[] { -half, y, half }, up, 0, 0);
        r.triangle(a, c, b, shader);
        r.triangle(a, d, c, shader);
    }

    private static Color colour(Vector3 c) {
        return new Color(Math.max(0f, Math.min(1f, c.x)), Math.max(0f, Math.min(1f, c.y)), Math.max(0f, Math.min(1f, c.z)));
    }

    private static float minOf(float[][] h) {
        float m = Float.MAX_VALUE;
        for (float[] row : h) for (float v : row) m = Math.min(m, v);
        return m;
    }

    /** The land's colour at a height above the sea (world units), on a slope (its normal's up part), and how near the picture's edge. */
    private Vector3 landColour(float aboveSea, float upness, float edge) {
        float metres = aboveSea / M;
        Vector3 c;
        if (metres < 2.5f && upness > 0.85f) c = mix(new Vector3(0.86f, 0.79f, 0.58f), look.soil(), 0.3f);
        else if (metres > 140f) c = new Vector3(0.93f, 0.95f, 0.98f);
        else if (upness < 0.72f || metres > 105f) c = mix(look.soil(), new Vector3(0.45f, 0.44f, 0.43f), 0.5f);
        else c = mix(look.grass(), look.dry(), Math.min(1f, metres / 90f));
        // (lost in the haze towards the edges, into the land beyond)
        float haze = Math.max(0f, Math.min(1f, (edge - 0.5f) / 0.5f));
        return mix(c, look.distance(), haze * haze);
    }

    /** A surface's colour as lit: sun and sky on its colour (taken from its sRGB look to linear), with a glint if shiny. */
    private static float[] shade(Vector3 c, float[] n, float shininess) {
        float diffuse = Math.max(0f, n[0] * SUN[0] + n[1] * SUN[1] + n[2] * SUN[2]);
        float sky = 0.3f + 0.12f * n[1];
        float glint = shininess > 30f ? (float) Math.pow(diffuse, 12) * 0.5f : 0f;
        float k = sky + 0.95f * diffuse;
        return new float[] { linear(c.x) * k + glint, linear(c.y) * k + glint, linear(c.z) * k + glint };
    }

    private static float linear(float srgb) {
        return (float) Math.pow(Math.max(0f, srgb), 2.2);
    }

    // ------------------------------------------------------------------ geometry

    private static float[] sub(float[] a, float[] b) {
        return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    private static float[] normalise(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return l < 1e-9f ? new float[] { 0f, 1f, 0f } : new float[] { v[0] / l, v[1] / l, v[2] / l };
    }

    /**
     * Flat faces in a landmark's own frame, each of one colour (its slot), wound to face out
     * from the inside point it was given (so they're lit and culled the right way round).
     */
    static final class Geometry {
        final List<float[][]> faces = new ArrayList<>();
        final List<float[]> normals = new ArrayList<>();
        final List<Integer> slots = new ArrayList<>();

        /** A flat face; corners in either winding, turned to face away from inside. */
        void face(int slot, float[] inside, float[]... corners) {
            float nx = 0f, ny = 0f, nz = 0f, cx = 0f, cy = 0f, cz = 0f;
            for (int i = 0; i < corners.length; i++) {
                float[] a = corners[i], b = corners[(i + 1) % corners.length];
                nx += (a[1] - b[1]) * (a[2] + b[2]);
                ny += (a[2] - b[2]) * (a[0] + b[0]);
                nz += (a[0] - b[0]) * (a[1] + b[1]);
                cx += a[0];
                cy += a[1];
                cz += a[2];
            }
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-9f) return;
            nx /= length;
            ny /= length;
            nz /= length;
            cx /= corners.length;
            cy /= corners.length;
            cz /= corners.length;
            float[][] wound = corners;
            if (nx * (cx - inside[0]) + ny * (cy - inside[1]) + nz * (cz - inside[2]) < 0f) {
                wound = new float[corners.length][];
                for (int i = 0; i < corners.length; i++) wound[i] = corners[corners.length - 1 - i];
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            faces.add(wound);
            normals.add(new float[] { nx, ny, nz });
            slots.add(slot);
        }

        /** An upright tube standing at (x, z), from y0 up to y1. */
        void tube(int slot, float x, float y0, float z, float y1, float ra, float rb, int sides, float turn, boolean capA, boolean capB) {
            tube(slot, x, y0, z, x, y1, z, ra, rb, sides, turn, 0f, capA, capB);
        }

        /** A tube from one point to another, unfluted. */
        void tube(int slot, float ax, float ay, float az, float bx, float by, float bz, float ra, float rb, int sides, float turn, boolean capA, boolean capB) {
            tube(slot, ax, ay, az, bx, by, bz, ra, rb, sides, turn, 0f, capA, capB);
        }

        /**
         * A frustum from a to b, radius ra to rb, of so many sides turned by turn (a vertical
         * one's first corner at turn from +z towards +x); every other corner pulled in by flute
         * (for fluting); its ends capped as asked.
         */
        void tube(int slot, float ax, float ay, float az, float bx, float by, float bz, float ra, float rb, int sides, float turn, float flute,
                  boolean capA, boolean capB) {
            float[] a = { ax, ay, az }, b = { bx, by, bz };
            float[] axis = sub(b, a);
            float length = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
            if (length < 1e-6f) return;
            axis = new float[] { axis[0] / length, axis[1] / length, axis[2] / length };
            float[] ref = Math.abs(axis[1]) > 0.9f ? new float[] { 1f, 0f, 0f } : new float[] { 0f, 1f, 0f };
            float[] side = normalise(cross(ref, axis)), other = cross(axis, side);
            float[][] ringA = new float[sides][], ringB = new float[sides][];
            for (int i = 0; i < sides; i++) {
                double t = turn + Math.PI * 2 * i / sides;
                float k = flute > 0f && i % 2 == 1 ? 1f - flute : 1f;
                float c = (float) Math.cos(t), s = (float) Math.sin(t);
                float dx = side[0] * c + other[0] * s, dy = side[1] * c + other[1] * s, dz = side[2] * c + other[2] * s;
                ringA[i] = new float[] { ax + dx * ra * k, ay + dy * ra * k, az + dz * ra * k };
                ringB[i] = new float[] { bx + dx * rb * k, by + dy * rb * k, bz + dz * rb * k };
            }
            float[] mid = { (ax + bx) * 0.5f, (ay + by) * 0.5f, (az + bz) * 0.5f };
            for (int i = 0; i < sides; i++) {
                int j = (i + 1) % sides;
                if (rb <= 1e-5f) face(slot, mid, ringA[i], ringA[j], b);
                else if (ra <= 1e-5f) face(slot, mid, a, ringB[j], ringB[i]);
                else face(slot, mid, ringA[i], ringA[j], ringB[j], ringB[i]);
                if (capA && ra > 1e-5f) face(slot, mid, a, ringA[i], ringA[j]);
                if (capB && rb > 1e-5f) face(slot, mid, b, ringB[j], ringB[i]);
            }
        }

        /** A box about a centre, half sizes along its own axes, turned by yaw (its +z towards yaw from +z towards +x), then pitched and rolled. */
        void box(int slot, float cx, float cy, float cz, float hx, float hy, float hz, float yaw, float pitch, float roll) {
            float cyw = (float) Math.cos(yaw), syw = (float) Math.sin(yaw);
            float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
            float cr = (float) Math.cos(roll), sr = (float) Math.sin(roll);
            float[][] c = new float[8][];
            for (int i = 0; i < 8; i++) {
                float x = (i & 1) == 0 ? -hx : hx, y = (i & 2) == 0 ? -hy : hy, z = (i & 4) == 0 ? -hz : hz;
                // roll about z, pitch about x, then yaw about y
                float x1 = x * cr - y * sr, y1 = x * sr + y * cr;
                float y2 = y1 * cp - z * sp, z2 = y1 * sp + z * cp;
                c[i] = new float[] { cx + x1 * cyw + z2 * syw, cy + y2, cz - x1 * syw + z2 * cyw };
            }
            float[] in = { cx, cy, cz };
            int[][] quads = { { 0, 1, 3, 2 }, { 4, 6, 7, 5 }, { 0, 4, 5, 1 }, { 2, 3, 7, 6 }, { 0, 2, 6, 4 }, { 1, 5, 7, 3 } };
            for (int[] q : quads) face(slot, in, c[q[0]], c[q[1]], c[q[2]], c[q[3]]);
        }

        /**
         * A rough standing stone: its foot at (x, foot, z), half width and half thickness there,
         * so tall, swelling a little and narrowing to a rounded top, leaning, every corner a
         * little out of true.
         */
        void block(int slot, Random rand, float x, float foot, float z, float hx, float hz, float tall, float yaw, float leanX, float leanZ) {
            float[] shares = { 0f, 0.5f, 0.92f };
            float[] widths = { 1f, 1.05f, 0.82f + rand.nextFloat() * 0.1f };
            float[][][] rings = new float[3][4][];
            float cyw = (float) Math.cos(yaw), syw = (float) Math.sin(yaw);
            for (int k = 0; k < 3; k++) {
                for (int c = 0; c < 4; c++) {
                    float sx = c == 0 || c == 3 ? -1f : 1f, sz = c < 2 ? -1f : 1f;
                    float lx = sx * hx * widths[k] * (0.9f + rand.nextFloat() * 0.2f), lz = sz * hz * widths[k] * (0.88f + rand.nextFloat() * 0.24f);
                    float ly = shares[k] * tall + (k == 2 ? (rand.nextFloat() - 0.5f) * tall * 0.08f : 0f);
                    lx += leanX * ly;
                    lz += leanZ * ly;
                    rings[k][c] = new float[] { x + lx * cyw + lz * syw, foot + ly, z - lx * syw + lz * cyw };
                }
            }
            float[] apex = { x + leanX * tall * cyw + leanZ * tall * syw, foot + tall, z - leanX * tall * syw + leanZ * tall * cyw };
            float[] in = { x + (apex[0] - x) * 0.5f, foot + tall * 0.5f, z + (apex[2] - z) * 0.5f };
            for (int k = 0; k < 2; k++) {
                for (int c = 0; c < 4; c++) {
                    int d = (c + 1) % 4;
                    face(slot, in, rings[k][c], rings[k][d], rings[k + 1][d]);
                    face(slot, in, rings[k][c], rings[k + 1][d], rings[k + 1][c]);
                }
            }
            for (int c = 0; c < 4; c++) face(slot, in, rings[2][c], rings[2][(c + 1) % 4], apex);
        }

        /** A ball, in bands of facets. */
        void sphere(int slot, float cx, float cy, float cz, float radius, int bands) {
            int around = bands * 2;
            float[] in = { cx, cy, cz };
            for (int b = 0; b < bands; b++) {
                double p0 = Math.PI * b / bands - Math.PI / 2, p1 = Math.PI * (b + 1) / bands - Math.PI / 2;
                for (int i = 0; i < around; i++) {
                    double t0 = Math.PI * 2 * i / around, t1 = Math.PI * 2 * (i + 1) / around;
                    float[] a = point(cx, cy, cz, radius, p0, t0), bb = point(cx, cy, cz, radius, p0, t1);
                    float[] c = point(cx, cy, cz, radius, p1, t1), d = point(cx, cy, cz, radius, p1, t0);
                    if (b == 0) face(slot, in, a, c, d);
                    else if (b == bands - 1) face(slot, in, a, bb, c);
                    else face(slot, in, a, bb, c, d);
                }
            }
        }

        private static float[] point(float cx, float cy, float cz, float r, double lat, double lon) {
            return new float[] { cx + (float) (Math.cos(lat) * Math.sin(lon)) * r, cy + (float) Math.sin(lat) * r, cz + (float) (Math.cos(lat) * Math.cos(lon)) * r };
        }

        /** A tube along a line of points, its radius going from r0 to r1, its joints rounded. */
        void curve(int slot, float[][] points, float r0, float r1, int sides) {
            for (int i = 0; i + 1 < points.length; i++) {
                float ra = r0 + (r1 - r0) * i / (points.length - 1), rb = r0 + (r1 - r0) * (i + 1) / (points.length - 1);
                float[] a = points[i], b = points[i + 1];
                tube(slot, a[0], a[1], a[2], b[0], b[1], b[2], ra, rb, sides, 0f, i == 0, i + 2 == points.length);
                if (i > 0) sphere(slot, a[0], a[1], a[2], ra, 2);
            }
        }

        /** A ring round a centre, square to the direction (nx, ny, nz): so many short bars, each thick. */
        void ring(int slot, float cx, float cy, float cz, float nx, float ny, float nz, float radius, float thick, int pieces) {
            float[] axis = normalise(new float[] { nx, ny, nz });
            float[] ref = Math.abs(axis[1]) > 0.9f ? new float[] { 1f, 0f, 0f } : new float[] { 0f, 1f, 0f };
            float[] u = normalise(cross(ref, axis)), v = cross(axis, u);
            for (int i = 0; i < pieces; i++) {
                double a0 = Math.PI * 2 * i / pieces, a1 = Math.PI * 2 * (i + 1) / pieces;
                float c0 = (float) Math.cos(a0) * radius, s0 = (float) Math.sin(a0) * radius, c1 = (float) Math.cos(a1) * radius, s1 = (float) Math.sin(a1) * radius;
                tube(slot, cx + u[0] * c0 + v[0] * s0, cy + u[1] * c0 + v[1] * s0, cz + u[2] * c0 + v[2] * s0,
                        cx + u[0] * c1 + v[0] * s1, cy + u[1] * c1 + v[1] * s1, cz + u[2] * c1 + v[2] * s1, thick, thick, 4, 0f, true, true);
            }
        }

        /** A lumpy boulder: a ball stretched to these radii, every point of it pushed in or out by up to rough. */
        void blob(int slot, Random rand, float cx, float cy, float cz, float rx, float ry, float rz, int bands, float rough) {
            int around = bands * 2;
            float[][][] p = new float[bands + 1][around][];
            for (int b = 0; b <= bands; b++) {
                double lat = Math.PI * b / bands - Math.PI / 2;
                float pole = 1f + (rand.nextFloat() - 0.5f) * rough;
                for (int i = 0; i < around; i++) {
                    double lon = Math.PI * 2 * i / around;
                    float k = b == 0 || b == bands ? pole : 1f + (rand.nextFloat() - 0.5f) * rough;
                    p[b][i] = new float[] { cx + (float) (Math.cos(lat) * Math.sin(lon)) * rx * k, cy + (float) Math.sin(lat) * ry * k, cz + (float) (Math.cos(lat) * Math.cos(lon)) * rz * k };
                }
                if (b == 0 || b == bands) for (int i = 1; i < around; i++) p[b][i] = p[b][0];
            }
            float[] in = { cx, cy, cz };
            for (int b = 0; b < bands; b++) {
                for (int i = 0; i < around; i++) {
                    int j = (i + 1) % around;
                    if (b > 0) face(slot, in, p[b][i], p[b][j], p[b + 1][j]);
                    if (b + 1 < bands) face(slot, in, p[b][i], p[b + 1][j], p[b + 1][i]);
                }
            }
        }

        /** A level ring round a centre: so many short bars, each thick. */
        void hoop(int slot, float cx, float cy, float cz, float radius, float thick, int pieces) {
            for (int i = 0; i < pieces; i++) {
                double a0 = Math.PI * 2 * i / pieces, a1 = Math.PI * 2 * (i + 1) / pieces;
                tube(slot, cx + (float) Math.sin(a0) * radius, cy, cz + (float) Math.cos(a0) * radius,
                        cx + (float) Math.sin(a1) * radius, cy, cz + (float) Math.cos(a1) * radius, thick, thick, 4, 0f, true, true);
            }
        }

        /**
         * A buttress fin standing out from a tower, along the direction angle (from +z towards
         * +x): from inner to outer out, its foot at y0, rising to top at the inner edge; thick across.
         */
        void fin(int slot, float angle, float inner, float outer, float y0, float top, float thick) {
            float ux = (float) Math.sin(angle), uz = (float) Math.cos(angle), px = uz * thick * 0.5f, pz = -ux * thick * 0.5f;
            float[] in = { ux * (inner * 0.66f + outer * 0.34f), y0 + (top - y0) * 0.3f, uz * (inner * 0.66f + outer * 0.34f) };
            float[][][] tris = new float[2][3][];
            for (int s = 0; s < 2; s++) {
                float sx = s == 0 ? px : -px, sz = s == 0 ? pz : -pz;
                tris[s][0] = new float[] { ux * inner + sx, y0, uz * inner + sz };
                tris[s][1] = new float[] { ux * outer + sx, y0, uz * outer + sz };
                tris[s][2] = new float[] { ux * inner + sx, top, uz * inner + sz };
                face(slot, in, tris[s][0], tris[s][1], tris[s][2]);
            }
            face(slot, in, tris[0][1], tris[1][1], tris[1][2], tris[0][2]);
            face(slot, in, tris[0][0], tris[0][1], tris[1][1], tris[1][0]);
        }

        /** A triangular gable from x0 to x1 across, z0 to z1 deep, its foot at y0, rising h to its ridge. */
        void gable(int slot, float x0, float x1, float z0, float z1, float y0, float h) {
            float xm = (x0 + x1) * 0.5f;
            float[] in = { xm, y0 + h * 0.3f, (z0 + z1) * 0.5f };
            float[] a0 = { x0, y0, z0 }, b0 = { x1, y0, z0 }, c0 = { xm, y0 + h, z0 };
            float[] a1 = { x0, y0, z1 }, b1 = { x1, y0, z1 }, c1 = { xm, y0 + h, z1 };
            face(slot, in, a0, b0, c0);
            face(slot, in, a1, b1, c1);
            face(slot, in, a0, c0, c1, a1);
            face(slot, in, b0, c0, c1, b1);
            face(slot, in, a0, b0, b1, a1);
        }
    }
}
