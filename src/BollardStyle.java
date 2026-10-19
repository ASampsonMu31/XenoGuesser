import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Per-nation roadside bollard: the short posts along a road's edge (delineators). Each nation
 * builds its own design, the same everywhere in it: the body's shape (round, square, three-,
 * six- or eight-sided, or a flat plank facing the traffic), how tall and wide it is and how it
 * narrows, its top (flat, pointed or rounded), its colour (black, white or a grey) and maybe a
 * band of the opposite shade; and its reflectors, one to three of them in any bright colour,
 * as a band round it or as patches facing each way along the road (rectangles, discs,
 * diamonds, triangles or tall strips), the two ways sometimes different colours. Only a few
 * designs go round the world (see World and RegionalDesigns): neighbours, kindred ones above all,
 * tend to share them, and a nation may build two or three, each in its own part of the land.
 *
 * Also how often they stand (some nations far more keen on them than others), how far off the
 * road's edge, whether the two sides' are staggered, and on which roads: always the open roads
 * (highways and lanes, where they have no guard rail), town streets in some nations, dirt
 * tracks in a few.
 */
public final class BollardStyle {
    public enum Body { ROUND, SQUARE, TRIANGLE, HEXAGON, OCTAGON, PLANK }
    public enum Cap { FLAT, POINTED, ROUNDED }
    public enum Mark { BAND, RECTANGLE, DISC, DIAMOND, TRIANGLE, STRIP }

    public Body body;
    public Cap cap;
    public Mark mark;
    /** Height above the ground, the body's half width at its foot, its top's width as a share of that, and the cap's height. */
    public float height, radius, taper, capHeight;
    /** The body's shade, and its band's (NaN for none) and where the band runs (shares of the height). */
    public float bodyShade, bandShade, bandFrom, bandTo;
    /** How many reflectors, how big (world units), how far apart, and how far down from the top the first is. */
    public int markCount;
    public float markSize, markGap, markTop;
    /** The reflectors' colours: those facing the way the road runs, and those facing back. */
    public Vector3 colourA, colourB;
    /** One every spacing (world units) along each side, out from the road's edge; the far side's half a spacing on if staggered. */
    public float spacing, out;
    public boolean staggered, onStreets, onTracks;

    private BollardStyle() {
    }

    private BollardStyle copy() {
        BollardStyle c = new BollardStyle();
        c.body = body; c.cap = cap; c.mark = mark;
        c.height = height; c.radius = radius; c.taper = taper; c.capHeight = capHeight;
        c.bodyShade = bodyShade; c.bandShade = bandShade; c.bandFrom = bandFrom; c.bandTo = bandTo;
        c.markCount = markCount; c.markSize = markSize; c.markGap = markGap; c.markTop = markTop;
        c.colourA = colourA; c.colourB = colourB;
        c.spacing = spacing; c.out = out; c.staggered = staggered; c.onStreets = onStreets; c.onTracks = onTracks;
        c.design = design;
        return c;
    }

    /** Which of the world's few designs this is (see World), for its materials. */
    public int design;

    /**
     * Every nation's bollards: the world's pool of designs, as each nation places them
     * ([nation][design]: its own spacing, distance out and roads, whichever design), and which
     * design each nation builds where (see RegionalDesigns: few designs, neighbours sharing them,
     * some nations building two or three, each in its own part of the land).
     */
    public record World(BollardStyle[][] styles, RegionalDesigns designs) {
        /** The bollard a nation builds at a place; null for none. */
        public BollardStyle at(int nation, GeodesicChunkGrid.Direction place) {
            int d = designs.designAt(nation, place);
            return d < 0 ? null : styles[nation][d];
        }

        /** A nation's main bollard (whose spacing and roads all its bollards keep); null for none. */
        public BollardStyle main(int nation) {
            int[] mine = designs.designsOf(nation);
            return mine.length == 0 ? null : styles[nation][mine[0]];
        }

        /** Any design's bollard, as a nation places it (for its materials). */
        public BollardStyle design(int nation, int design) {
            return styles[nation][design];
        }
    }

    /** Deals the world's bollards: about one design for every POOL_PER_NATIONS nations. */
    public static World generateForNations(long seed, int numNations, NationKinship kinship) {
        RegionalDesigns designs = new RegionalDesigns(seed + 9494L, numNations, kinship, POOL_PER_NATIONS);
        Random rand = new Random(seed + 9393L);
        BollardStyle[] pool = new BollardStyle[designs.poolSize];
        for (int d = 0; d < pool.length; d++) {
            pool[d] = fresh(rand);
            pool[d].design = d;
        }
        // (how keen on them, and where, is each nation's own, whatever the design)
        Random placing = new Random(seed + 9595L);
        BollardStyle[][] styles = new BollardStyle[numNations + 1][];
        for (int n = 1; n <= numNations; n++) {
            BollardStyle where = new BollardStyle();
            where.placing(placing);
            styles[n] = new BollardStyle[pool.length];
            for (int d = 0; d < pool.length; d++) {
                BollardStyle s = pool[d].copy();
                s.spacing = where.spacing;
                s.out = where.out;
                s.staggered = where.staggered;
                s.onStreets = where.onStreets;
                s.onTracks = where.onTracks;
                styles[n][d] = s;
            }
        }
        return new World(styles, designs);
    }

    private static final float POOL_PER_NATIONS = 3.5f;

    /** A design of its own. */
    private static BollardStyle fresh(Random rand) {
        BollardStyle s = new BollardStyle();
        s.body = Body.values()[rand.nextInt(Body.values().length)];
        s.cap = Cap.values()[rand.nextInt(Cap.values().length)];
        s.mark = Mark.values()[rand.nextInt(Mark.values().length)];
        // (ten units to the metre: 0.6 to 1.25 m tall, 10 to 34 cm across)
        s.height = 6f + rand.nextFloat() * 6.5f;
        s.radius = 0.5f + rand.nextFloat() * 1.2f;
        s.taper = rand.nextFloat() < 0.4f ? 1f : 0.6f + rand.nextFloat() * 0.35f;
        s.capHeight = s.radius * (0.4f + rand.nextFloat() * 0.9f);
        float shade = rand.nextFloat();
        s.bodyShade = shade < 0.35f ? 0.06f : shade < 0.7f ? 0.93f : 0.35f + rand.nextFloat() * 0.35f;
        if (rand.nextFloat() < 0.45f) {
            s.bandShade = s.bodyShade > 0.5f ? 0.07f : 0.92f;
            s.bandFrom = 0.55f + rand.nextFloat() * 0.2f;
            s.bandTo = Math.min(0.97f, s.bandFrom + 0.12f + rand.nextFloat() * 0.18f);
        } else {
            s.bandShade = Float.NaN;
        }
        s.markCount = 1 + rand.nextInt(3);
        s.markSize = 0.7f + rand.nextFloat() * 0.9f;
        s.markGap = s.markSize * (1.3f + rand.nextFloat() * 0.8f);
        s.markTop = 0.6f + rand.nextFloat() * 1.2f;
        s.colourA = brightColour(rand);
        s.colourB = rand.nextFloat() < 0.5f ? s.colourA : brightColour(rand);
        return s;
    }

    /** Where and how often: every nation its own. */
    private void placing(Random rand) {
        // From every 4 m to every 40 m or so, most nations towards the sparse end
        spacing = 40f + 360f * (float) Math.pow(rand.nextFloat(), 1.6);
        out = 1.5f + rand.nextFloat() * 2.5f;
        staggered = rand.nextBoolean();
        onStreets = rand.nextFloat() < 0.45f;
        onTracks = rand.nextFloat() < 0.3f;
    }

    /** Whether this nation puts them along a road of a class. */
    public boolean along(RoadPath.RoadClass roadClass) {
        return switch (roadClass) {
            case DIRT -> onTracks;
            case STREET -> onStreets;
            default -> true;
        };
    }

    /** A bright colour for a reflector: any hue, strong; now and then white. */
    private static Vector3 brightColour(Random rand) {
        if (rand.nextFloat() < 0.15f) return new Vector3(0.97f, 0.97f, 0.95f);
        java.awt.Color c = java.awt.Color.getHSBColor(rand.nextFloat(), 0.75f + rand.nextFloat() * 0.25f, 1f);
        return new Vector3(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
    }

    public Vector3 bodyColour() {
        return new Vector3(bodyShade, bodyShade, bodyShade);
    }

    public Vector3 bandColour() {
        return new Vector3(bandShade, bandShade, bandShade);
    }

    /** How far the bollard reaches from its middle at its widest (for things walking into it). */
    public float reach() {
        return radius;
    }

    private int sides() {
        return switch (body) {
            case ROUND -> 12;
            case SQUARE, PLANK -> 4;
            case TRIANGLE -> 3;
            case HEXAGON -> 6;
            case OCTAGON -> 8;
        };
    }

    // ------------------------------------------------------------------ its picture (for the map)

    /**
     * A picture of this bollard standing, drawn flat (for the bollard map): seen from the front,
     * its reflectors facing the viewer; and should those facing back be another colour, seen
     * from behind too, beside it. On a clear background, height pixels tall.
     */
    public java.awt.image.BufferedImage picture(int height) {
        // (its front only: its reflectors facing the viewer)
        boolean twoWays = false;
        float tallest = this.height + (cap == Cap.FLAT ? 0f : capHeight);
        float scale = height * 0.86f / tallest;
        int one = Math.max(6, Math.round(Math.max(radius * 2f * scale * 1.15f, height * 0.22f)));
        int width = twoWays ? one * 2 + Math.round(height * 0.06f) : one;
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        drawStanding(g, one * 0.5f, height * 0.95f, scale, colourA);
        if (twoWays) drawStanding(g, width - one * 0.5f, height * 0.95f, scale, colourB);
        g.dispose();
        return image;
    }

    /** One bollard face on, its foot's middle at (x, ground), scale pixels a unit, its reflectors in this colour. */
    private void drawStanding(java.awt.Graphics2D g, float x, float ground, float scale, Vector3 reflector) {
        int n = sides();
        // How far it reaches either side seen face on (a plank's broad face to the viewer)
        float reach = 0f;
        for (int k = 0; k < n; k++) reach = Math.max(reach, (float) Math.abs(Math.sin(Math.PI / n + 2 * Math.PI * k / n)));
        float foot = radius * reach * scale, top = radiusAt(height) * reach * scale, h = height * scale;
        java.awt.geom.Path2D.Float outline = new java.awt.geom.Path2D.Float();
        outline.moveTo(x - foot, ground);
        outline.lineTo(x - top, ground - h);
        float c = capHeight * scale;
        switch (cap) {
            case POINTED -> outline.lineTo(x, ground - h - c);
            case ROUNDED -> {
                outline.lineTo(x - top * 0.72f, ground - h - c * 0.6f);
                outline.lineTo(x, ground - h - c);
                outline.lineTo(x + top * 0.72f, ground - h - c * 0.6f);
            }
            default -> { }
        }
        outline.lineTo(x + top, ground - h);
        outline.lineTo(x + foot, ground);
        outline.closePath();
        // A shadow at its foot, then the body shaded as if lit from the left
        g.setColor(new java.awt.Color(0, 0, 0, 70));
        g.fill(new java.awt.geom.Ellipse2D.Float(x - foot * 1.6f, ground - foot * 0.35f, foot * 3.2f, foot * 0.7f));
        java.awt.Color body = colour(bodyColour());
        g.setPaint(new java.awt.LinearGradientPaint(x - foot, 0f, x + foot, 0f, new float[] { 0f, 0.35f, 1f },
                new java.awt.Color[] { shade(body, 0.7f), shade(body, 1.12f), shade(body, 0.55f) }));
        g.fill(outline);
        java.awt.Shape clip = g.getClip();
        g.clip(outline);
        if (!Float.isNaN(bandShade)) {
            java.awt.Color band = colour(bandColour());
            g.setPaint(new java.awt.LinearGradientPaint(x - foot, 0f, x + foot, 0f, new float[] { 0f, 0.35f, 1f },
                    new java.awt.Color[] { shade(band, 0.7f), shade(band, 1.12f), shade(band, 0.55f) }));
            g.fill(new java.awt.geom.Rectangle2D.Float(x - foot - 1, ground - h * bandTo, foot * 2 + 2, h * (bandTo - bandFrom)));
        }
        // The reflectors, as the game places them (see build)
        java.awt.Color shine = colour(reflector);
        g.setColor(shine);
        for (int k = 0; k < markCount; k++) {
            float centre = height - markTop - markSize * 0.5f - k * markGap;
            if (centre - markSize * 0.5f < 1f) break;
            float cy = ground - centre * scale, half = markSize * 0.5f * scale;
            if (mark == Mark.BAND) {
                g.fill(new java.awt.geom.Rectangle2D.Float(x - foot - 1, cy - half * 0.7f, foot * 2 + 2, half * 1.4f));
                continue;
            }
            float faceWidth = 2f * radiusAt(centre) * (float) Math.sin(Math.PI / n) * (n >= 8 ? 1.6f : 1f);
            float w = Math.min(markSize, faceWidth * 0.85f) * 0.5f * scale;
            java.awt.Shape shape = switch (mark) {
                case DISC -> new java.awt.geom.Ellipse2D.Float(x - w, cy - Math.min(w, half), w * 2, Math.min(w, half) * 2);
                case DIAMOND -> polygon(new float[] { x, x + w, x, x - w }, new float[] { cy - half, cy, cy + half, cy });
                case TRIANGLE -> polygon(new float[] { x - w, x + w, x }, new float[] { cy + half, cy + half, cy - half });
                case STRIP -> new java.awt.geom.Rectangle2D.Float(x - w * 0.45f, cy - half * 1.5f, w * 0.9f, half * 3f);
                default -> new java.awt.geom.Rectangle2D.Float(x - w, cy - half * 0.7f, w * 2, half * 1.4f);
            };
            g.fill(shape);
        }
        g.setClip(clip);
        // (outlined, so a white one shows on pale land and a black one on dark)
        g.setColor(new java.awt.Color(20, 20, 22, 220));
        g.setStroke(new java.awt.BasicStroke(Math.max(1f, scale * 0.12f)));
        g.draw(outline);
    }

    private static java.awt.geom.Path2D.Float polygon(float[] xs, float[] ys) {
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < xs.length; i++) p.lineTo(xs[i], ys[i]);
        p.closePath();
        return p;
    }

    private static java.awt.Color colour(Vector3 c) {
        return new java.awt.Color(Math.min(1f, c.x), Math.min(1f, c.y), Math.min(1f, c.z));
    }

    private static java.awt.Color shade(java.awt.Color c, float by) {
        return new java.awt.Color(Math.min(255, Math.round(c.getRed() * by + (by > 1f ? 12 : 0))),
                Math.min(255, Math.round(c.getGreen() * by + (by > 1f ? 12 : 0))), Math.min(255, Math.round(c.getBlue() * by + (by > 1f ? 12 : 0))));
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Builds one bollard standing on the ground at (x, groundY, z) in a road segment's frame
     * (y up), its front facing (fx, fz) along the road: the body into bodyBuilder, its band into
     * bandBuilder, its reflectors facing (fx, fz) (and any band of them) into markA and those
     * facing back into markB.
     */
    public void build(MeshBuilder bodyBuilder, MeshBuilder bandBuilder, MeshBuilder markA, MeshBuilder markB,
                      float x, float groundY, float z, float fx, float fz) {
        int n = sides();
        float[] f = { fx, 0f, fz }, s = { -fz, 0f, fx };
        // (a plank's thin across its face; foot sunk a little into the ground)
        float depth = body == Body.PLANK ? 0.35f : 1f;
        float foot = groundY - 1f, top = groundY + height;
        prism(bodyBuilder, x, z, f, s, n, depth, foot, top, radius, radiusAt(top - groundY), 1f);
        if (!Float.isNaN(bandShade)) {
            float y0 = groundY + height * bandFrom, y1 = groundY + height * bandTo;
            prism(bandBuilder, x, z, f, s, n, depth, y0, y1, radiusAt(y0 - groundY), radiusAt(y1 - groundY), 1.02f);
        }
        cap(bodyBuilder, x, z, f, s, n, depth, top, radiusAt(height));
        for (int k = 0; k < markCount; k++) {
            float centre = top - markTop - markSize * 0.5f - k * markGap;
            if (centre - markSize * 0.5f < groundY + 1f) break;
            if (mark == Mark.BAND) {
                float y0 = centre - markSize * 0.35f, y1 = centre + markSize * 0.35f;
                prism(markA, x, z, f, s, n, depth, y0, y1, radiusAt(y0 - groundY), radiusAt(y1 - groundY), 1.04f);
                continue;
            }
            patch(markA, x, z, f, s, n, depth, centre, radiusAt(centre - groundY), 1f);
            // (back the other way where there's a face to carry it: not on a three-sided one)
            if (n % 2 == 0) patch(markB, x, z, f, s, n, depth, centre, radiusAt(centre - groundY), -1f);
        }
    }

    private float radiusAt(float up) {
        return radius * (1f + (taper - 1f) * Math.max(0f, Math.min(1f, up / height)));
    }

    /** A point round the body's axis at an angle (0 straight ahead), a radius out, at a height. */
    private static float[] round(float x, float z, float[] f, float[] s, float depth, double angle, float r, float y) {
        float a = (float) Math.cos(angle) * r * depth, b = (float) Math.sin(angle) * r;
        return new float[] { x + f[0] * a + s[0] * b, y, z + f[2] * a + s[2] * b };
    }

    /** The sides of an n-sided prism between two heights (a face straight ahead), scaled out by grow. */
    private static void prism(MeshBuilder m, float x, float z, float[] f, float[] s, int n, float depth, float y0, float y1, float r0, float r1, float grow) {
        for (int k = 0; k < n; k++) {
            double a = Math.PI / n + 2 * Math.PI * k / n, b = a + 2 * Math.PI / n;
            float[] p0 = round(x, z, f, s, depth, a, r0 * grow, y0), p1 = round(x, z, f, s, depth, b, r0 * grow, y0);
            float[] p2 = round(x, z, f, s, depth, b, r1 * grow, y1), p3 = round(x, z, f, s, depth, a, r1 * grow, y1);
            float[] outward = round(0f, 0f, f, s, depth, (a + b) * 0.5, 1f, 0f);
            quad(m, p0, p1, p2, p3, outward);
        }
    }

    /** The bollard's top: flat, a point, or rounded over in two steps. */
    private void cap(MeshBuilder m, float x, float z, float[] f, float[] s, int n, float depth, float top, float r) {
        float[] up = { 0f, 1f, 0f };
        float[] centre = { x, top + (cap == Cap.FLAT ? 0f : capHeight), z };
        float ringY = top, ringR = r;
        if (cap == Cap.ROUNDED) {
            // (in to most of its width a little way up, then over to the top)
            float midY = top + capHeight * 0.6f, midR = r * 0.72f;
            prism(m, x, z, f, s, n, depth, top, midY, r, midR, 1f);
            ringY = midY;
            ringR = midR;
        }
        for (int k = 0; k < n; k++) {
            double a = Math.PI / n + 2 * Math.PI * k / n, b = a + 2 * Math.PI / n;
            float[] p0 = round(x, z, f, s, depth, a, ringR, ringY), p1 = round(x, z, f, s, depth, b, ringR, ringY);
            float[] outward = round(0f, 0f, f, s, depth, (a + b) * 0.5, 1f, 0f);
            float[] hint = cap == Cap.FLAT ? up : new float[] { outward[0], 1f, outward[2] };
            triangle(m, p0, p1, centre, hint);
        }
    }

    /** One reflector patch on the face straight ahead (way 1) or behind (way -1), its middle at a height. */
    private void patch(MeshBuilder m, float x, float z, float[] f, float[] s, int n, float depth, float y, float r, float way) {
        // On the face: as far out as the face is (its apothem), a hair proud of it, and no wider than it
        float apothem = r * depth * (float) Math.cos(Math.PI / n) + 0.04f;
        float faceWidth = 2f * r * (float) Math.sin(Math.PI / n) * (n >= 8 ? 1.6f : 1f);
        float w = Math.min(markSize, faceWidth * 0.85f) * 0.5f, h = markSize * 0.5f;
        float[][] outline = switch (mark) {
            case DISC -> {
                float[][] o = new float[10][];
                for (int k = 0; k < 10; k++) o[k] = new float[] { (float) Math.cos(2 * Math.PI * k / 10) * w, (float) Math.sin(2 * Math.PI * k / 10) * Math.min(w, h) };
                yield o;
            }
            case DIAMOND -> new float[][] { { 0f, -h }, { w, 0f }, { 0f, h }, { -w, 0f } };
            case TRIANGLE -> new float[][] { { -w, -h }, { w, -h }, { 0f, h } };
            case STRIP -> new float[][] { { -w * 0.45f, -h * 1.5f }, { w * 0.45f, -h * 1.5f }, { w * 0.45f, h * 1.5f }, { -w * 0.45f, h * 1.5f } };
            default -> new float[][] { { -w, -h * 0.7f }, { w, -h * 0.7f }, { w, h * 0.7f }, { -w, h * 0.7f } };
        };
        float cx = x + f[0] * apothem * way, cz = z + f[2] * apothem * way;
        float[] facing = { f[0] * way, 0f, f[2] * way };
        float[] middle = { cx, y, cz };
        for (int k = 0; k < outline.length; k++) {
            float[] a = outline[k], b = outline[(k + 1) % outline.length];
            float[] pa = { cx + s[0] * a[0], y + a[1], cz + s[2] * a[0] };
            float[] pb = { cx + s[0] * b[0], y + b[1], cz + s[2] * b[0] };
            triangle(m, middle, pa, pb, facing);
        }
    }

    /** A quad, wound to face the way hint points (its normal worked out from its corners). */
    private static void quad(MeshBuilder m, float[] p0, float[] p1, float[] p2, float[] p3, float[] hint) {
        triangle(m, p0, p1, p2, hint);
        triangle(m, p0, p2, p3, hint);
    }

    /** A triangle, wound to face the way hint points, lit flat. */
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
}
