import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * A way of dressing up buildings, on top of their form (footprint, roof, walls: see
 * BuildingStyle): the shape of the walls' surface (bulging, pinched, creased, fluted or studded),
 * roofs whose corners sweep up, droop or curl, cresting along the eaves and ridge (orbs, teeth,
 * blocks, spikes or rings), something standing on the top (an aerial, an orb, a trident, a vane,
 * a hoop, a dish, a spiral, a crescent), spikes from the walls, the walls' favourite masonry
 * (coursed bricks, triangles, honeycomb or a plain grid), and the colours of the ornaments.
 * Towers in the big cities have their own high-tech dress: neon up the corners and round the
 * floors, blades down the sides, rings round them and a crown on top.
 *
 * Few designs go round the world: neighbours share them, kindred ones above all (see
 * RegionalDesigns), and a nation may build a neighbour's near that neighbour. Within a nation one
 * province often has its own take on the nation's design (a few of its traits different). How much
 * of its finery a building wears depends on how well off the household is: the poor build plain,
 * the rich ornately; and only the richest towers glow.
 */
public final class ArchitectureStyle {
    public enum Relief { NONE, PILLOW, BARREL, PINCH, CHEVRON, FLUTED, STUDDED }
    public enum Horns { NONE, UPTURNED, DROOPING, CURLED }
    public enum Crest { NONE, ORBS, TEETH, BLOCKS, SPIKES, RINGS }
    public enum Topper { NONE, MAST, ORB, TRIDENT, VANE, HOOP, DISH, SPIRAL, CRESCENT }
    public enum WallSpikes { NONE, ROW, CORNERS, THORNS }
    public enum Crown { NONE, SPIRE, HALO, FINS, DISHES, ORB }

    // The walls' surface: its shape, how far it stands out (world units), whether the shape
    // repeats on every floor, and for flutes and studs how far apart they are
    public Relief relief;
    public float reliefDepth;
    public boolean reliefPerFloor;
    public float reliefPitch;
    // The roof's corners: how they sweep, how far out (a share of the roof's span) and how thick
    public Horns horns;
    public float hornLength, hornThickness;
    // Cresting: what, how big, how far apart (in its own sizes), and whether along the ridge too
    public Crest crest;
    public float crestSize, crestSpacing;
    public boolean ridgeCrest;
    // On the top
    public Topper topper;
    public float topperHeight;
    // Spikes from the walls, and how long
    public WallSpikes wallSpikes;
    public float spikeLength;
    // The masonry this design likes its walls built in
    public BuildingStyle.Pattern pattern;
    // How keen on finery its builders are (0 to 1): how much of it the well off put on
    public float ornateness;
    // The ornaments' colour and shine, and the spikes'
    public Vector3 ornamentColour, spikeColour;
    public float ornamentShine;
    // The neon's two colours, and whether rich houses (not only towers) have it under their eaves
    public Vector3 neonA, neonB;
    public boolean neonEaves;
    // Towers: neon up their corners and in bands every so many floors, their crown, blades down
    // their sides, and rings round their middles
    public boolean neonCorners, neonBands;
    public int bandEvery;
    public Crown crown;
    public boolean blades, rings;

    /** Which of the world's designs this is (see World), for its materials. */
    public int design;

    private ArchitectureStyle() {
    }

    private ArchitectureStyle copy() {
        ArchitectureStyle c = new ArchitectureStyle();
        c.relief = relief; c.reliefDepth = reliefDepth; c.reliefPerFloor = reliefPerFloor; c.reliefPitch = reliefPitch;
        c.horns = horns; c.hornLength = hornLength; c.hornThickness = hornThickness;
        c.crest = crest; c.crestSize = crestSize; c.crestSpacing = crestSpacing; c.ridgeCrest = ridgeCrest;
        c.topper = topper; c.topperHeight = topperHeight;
        c.wallSpikes = wallSpikes; c.spikeLength = spikeLength;
        c.pattern = pattern; c.ornateness = ornateness;
        c.ornamentColour = ornamentColour; c.spikeColour = spikeColour; c.ornamentShine = ornamentShine;
        c.neonA = neonA; c.neonB = neonB; c.neonEaves = neonEaves;
        c.neonCorners = neonCorners; c.neonBands = neonBands; c.bandEvery = bandEvery;
        c.crown = crown; c.blades = blades; c.rings = rings;
        c.design = design;
        return c;
    }

    // ------------------------------------------------------------------ the world's designs

    /**
     * The world's designs and where each is built: the pool (see RegionalDesigns), then each
     * nation's provincial take on its main design (null where it has none), at pool size + nation - 1.
     */
    public static final class World {
        private final ArchitectureStyle[] styles;
        private final RegionalDesigns designs;
        private final NationKinship kinship;
        private final int poolSize;
        // Each nation's province: the side of it towards axis, beyond reach (a sine of the angle) from its middle
        private final double[][] axis;
        private final double[] reach;

        private World(ArchitectureStyle[] styles, RegionalDesigns designs, NationKinship kinship, int poolSize,
                      double[][] axis, double[] reach) {
            this.styles = styles;
            this.designs = designs;
            this.kinship = kinship;
            this.poolSize = poolSize;
            this.axis = axis;
            this.reach = reach;
        }

        /** Which design (an index into style) a nation builds at a place; -1 for no nation. */
        public int at(int nation, GeodesicChunkGrid.Direction place) {
            int d = designs.designAt(nation, place);
            if (d < 0) return -1;
            int[] mine = designs.designsOf(nation);
            // (a neighbour's design near that neighbour; else the province's own take, if this is in it)
            if (d == mine[0] && inProvince(nation, place)) return poolSize + nation - 1;
            return d;
        }

        private boolean inProvince(int nation, GeodesicChunkGrid.Direction place) {
            if (nation < 1 || nation >= axis.length || axis[nation] == null) return false;
            double x = place.x(), y = place.y(), z = place.z(), l = Math.sqrt(x * x + y * y + z * z);
            double along = (x * axis[nation][0] + y * axis[nation][1] + z * axis[nation][2]) / l;
            return along > reach[nation];
        }

        public ArchitectureStyle style(int index) {
            return index >= 0 && index < styles.length && styles[index] != null ? styles[index] : styles[0];
        }

        /** How many designs there are in all (the pool, then the provinces'). */
        public int count() {
            return styles.length;
        }

        /**
         * A nation's designs, each {index (see style), the neighbour it's from (0 for its main
         * one, -1 for its province's)}; for its province's, a third: a direction {x, y, z} in the
         * middle of the province.
         */
        public List<Object[]> designsOf(int nation) {
            List<Object[]> list = new ArrayList<>();
            int[] mine = designs.designsOf(nation), from = designs.fromNationsOf(nation);
            for (int k = 0; k < mine.length; k++) {
                list.add(new Object[] { mine[k], from[k] });
                if (k == 0 && nation < axis.length && axis[nation] != null) {
                    double[] c = kinship.centre(nation);
                    double t = reach[nation] * 2.2;
                    double[] spot = { c[0] + axis[nation][0] * t, c[1] + axis[nation][1] * t, c[2] + axis[nation][2] * t };
                    list.add(new Object[] { poolSize + nation - 1, -1, spot });
                }
            }
            return list;
        }

        public String summary() {
            int provinces = 0;
            for (int i = poolSize; i < styles.length; i++) if (styles[i] != null) provinces++;
            return designs.summary() + ", " + provinces + " with a province of its own";
        }
    }

    /** Deals the world's designs: about one for every POOL_PER_NATIONS nations, and the nations' provinces. */
    public static World generateForNations(long seed, int numNations, NationKinship kinship) {
        RegionalDesigns designs = new RegionalDesigns(seed + 8484L, numNations, kinship, POOL_PER_NATIONS);
        Random rand = new Random(seed + 8383L);
        ArchitectureStyle[] styles = new ArchitectureStyle[designs.poolSize + numNations];
        for (int d = 0; d < designs.poolSize; d++) {
            styles[d] = fresh(rand);
            styles[d].design = d;
        }
        // Provinces: most nations have one, on one side of their land, with its own take on the main design
        Random provinceRand = new Random(seed + 8585L);
        double[][] axis = new double[numNations + 1][];
        double[] reach = new double[numNations + 1];
        for (int n = 1; n <= numNations; n++) {
            boolean has = provinceRand.nextFloat() < 0.75f;
            double[] c = kinship.centre(n);
            // How wide the nation is: half the way to its nearest bordering neighbour's middle
            double radius = 0.12;
            for (int m = 1; m <= numNations; m++) {
                if (!kinship.bordering(n, m)) continue;
                double[] o = kinship.centre(m);
                radius = Math.min(radius, 0.5 * Math.acos(Math.max(-1.0, Math.min(1.0, c[0] * o[0] + c[1] * o[1] + c[2] * o[2]))));
            }
            double[] direction = { provinceRand.nextGaussian(), provinceRand.nextGaussian(), provinceRand.nextGaussian() };
            if (!has) continue;
            // (along the land: the part of a random direction across the nation's middle)
            double along = direction[0] * c[0] + direction[1] * c[1] + direction[2] * c[2];
            double[] tangent = { direction[0] - c[0] * along, direction[1] - c[1] * along, direction[2] - c[2] * along };
            double l = Math.sqrt(tangent[0] * tangent[0] + tangent[1] * tangent[1] + tangent[2] * tangent[2]);
            if (l < 1e-9) continue;
            axis[n] = new double[] { tangent[0] / l, tangent[1] / l, tangent[2] / l };
            reach[n] = Math.sin(radius * (0.15 + 0.3 * provinceRand.nextDouble()));
            int main = designs.designsOf(n)[0];
            ArchitectureStyle province = styles[main].copy();
            province.reroll(provinceRand, 2 + provinceRand.nextInt(2));
            province.design = designs.poolSize + n - 1;
            styles[province.design] = province;
        }
        return new World(styles, designs, kinship, designs.poolSize, axis, reach);
    }

    private static final float POOL_PER_NATIONS = 2.2f;

    /** A design of its own. */
    private static ArchitectureStyle fresh(Random rand) {
        ArchitectureStyle s = new ArchitectureStyle();
        s.relief = pick(rand, Relief.values(), 0.22f, 0.13f, 0.12f, 0.09f, 0.14f, 0.17f, 0.13f);
        s.reliefDepth = 1.6f + rand.nextFloat() * 3.2f;
        s.reliefPerFloor = rand.nextFloat() < 0.4f;
        s.reliefPitch = 7f + rand.nextFloat() * 9f;
        s.horns = pick(rand, Horns.values(), 0.3f, 0.26f, 0.2f, 0.24f);
        s.hornLength = 0.1f + rand.nextFloat() * 0.14f;
        s.hornThickness = 1.2f + rand.nextFloat() * 1.6f;
        s.crest = pick(rand, Crest.values(), 0.2f, 0.18f, 0.18f, 0.14f, 0.16f, 0.14f);
        s.crestSize = 1.4f + rand.nextFloat() * 1.8f;
        s.crestSpacing = 2.2f + rand.nextFloat() * 2.6f;
        s.ridgeCrest = rand.nextFloat() < 0.55f;
        s.topper = pick(rand, Topper.values(), 0.12f, 0.13f, 0.11f, 0.1f, 0.11f, 0.1f, 0.1f, 0.12f, 0.11f);
        s.topperHeight = 10f + rand.nextFloat() * 14f;
        s.wallSpikes = pick(rand, WallSpikes.values(), 0.45f, 0.2f, 0.18f, 0.17f);
        s.spikeLength = 2.5f + rand.nextFloat() * 4f;
        s.pattern = pick(rand, BuildingStyle.Pattern.values(), 0.4f, 0.2f, 0.22f, 0.18f);
        s.ornateness = 0.25f + rand.nextFloat() * 0.75f;
        s.ornament(rand);
        s.neonA = neonColour(rand);
        s.neonB = rand.nextFloat() < 0.5f ? s.neonA : neonColour(rand);
        s.neonEaves = rand.nextFloat() < 0.35f;
        s.tower(rand);
        return s;
    }

    /** The ornaments' colour: gold, bronze, chrome or black metal, or a strong alien glaze. */
    private void ornament(Random rand) {
        float kind = rand.nextFloat();
        if (kind < 0.55f) {
            Vector3[] metals = { new Vector3(0.86f, 0.66f, 0.22f), new Vector3(0.6f, 0.38f, 0.2f), new Vector3(0.78f, 0.8f, 0.84f),
                    new Vector3(0.1f, 0.1f, 0.12f), new Vector3(0.3f, 0.5f, 0.45f) };
            ornamentColour = metals[rand.nextInt(metals.length)];
            ornamentShine = 48f;
        } else {
            Color c = Color.getHSBColor(rand.nextFloat(), 0.5f + rand.nextFloat() * 0.4f, 0.45f + rand.nextFloat() * 0.45f);
            ornamentColour = new Vector3(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
            ornamentShine = 16f;
        }
        spikeColour = rand.nextFloat() < 0.5f ? ornamentColour : rand.nextBoolean() ? new Vector3(0.08f, 0.08f, 0.09f) : new Vector3(0.82f, 0.82f, 0.8f);
    }

    /** The towers' high-tech dress. */
    private void tower(Random rand) {
        neonCorners = rand.nextFloat() < 0.6f;
        neonBands = rand.nextFloat() < 0.5f;
        bandEvery = 2 + rand.nextInt(4);
        crown = pick(rand, Crown.values(), 0.12f, 0.22f, 0.2f, 0.18f, 0.14f, 0.14f);
        blades = rand.nextFloat() < 0.45f;
        rings = rand.nextFloat() < 0.35f;
    }

    /** A province's own take: a few of the design's traits drawn afresh. */
    private void reroll(Random rand, int traits) {
        for (int t = 0; t < traits; t++) {
            switch (rand.nextInt(7)) {
                case 0 -> relief = Relief.values()[rand.nextInt(Relief.values().length)];
                case 1 -> horns = Horns.values()[rand.nextInt(Horns.values().length)];
                case 2 -> crest = Crest.values()[rand.nextInt(Crest.values().length)];
                case 3 -> topper = Topper.values()[rand.nextInt(Topper.values().length)];
                case 4 -> wallSpikes = WallSpikes.values()[rand.nextInt(WallSpikes.values().length)];
                case 5 -> ornament(rand);
                default -> crown = Crown.values()[rand.nextInt(Crown.values().length)];
            }
        }
    }

    private static <T> T pick(Random rand, T[] values, float... weights) {
        float total = 0f;
        for (float w : weights) total += w;
        float roll = rand.nextFloat() * total;
        for (int i = 0; i < values.length; i++) {
            roll -= weights[i];
            if (roll <= 0f) return values[i];
        }
        return values[values.length - 1];
    }

    private static Vector3 neonColour(Random rand) {
        Color c = Color.getHSBColor(rand.nextFloat(), 0.7f + rand.nextFloat() * 0.3f, 1f);
        return new Vector3(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
    }

    // ------------------------------------------------------------------ one building's dress

    /** What one building of this design wears (see dress). */
    public static final class Dress {
        public boolean relief, horns, crest, ridgeCrest, topper, spikes, neonEaves;
        // (towers)
        public boolean neonCorners, neonBands, crown, blades, rings;
    }

    /**
     * What a building of this design wears, rolled from its own seed: the walls' shape is the
     * tradition, on most of them whoever lives there; the finery comes with money, the ornate
     * design's richest houses wearing all of it and the poor little or none; a grand house all it
     * has. A tower's high tech likewise, its neon only where people are well off.
     */
    public Dress dress(long seed, float richness, boolean grand, boolean tower) {
        Random rand = new Random(seed ^ 0xA4C8E7L);
        Dress d = new Dress();
        float rich = Math.max(0f, Math.min(1f, richness));
        float finery = ornateness * (0.1f + 0.9f * (float) Math.pow(rich, 1.3)) + (grand ? 0.45f : 0f);
        if (tower) {
            float tech = 0.15f + 0.85f * rich;
            d.neonCorners = neonCorners && rich > 0.3f && rand.nextFloat() < tech;
            d.neonBands = neonBands && rich > 0.4f && rand.nextFloat() < tech;
            d.crown = crown != Crown.NONE && rand.nextFloat() < 0.35f + 0.65f * rich;
            d.blades = blades && rand.nextFloat() < 0.25f + 0.6f * rich;
            d.rings = rings && rand.nextFloat() < rich * rich * 1.3f;
            return d;
        }
        d.relief = relief != Relief.NONE && rand.nextFloat() < 0.6f + 0.3f * ornateness;
        d.horns = horns != Horns.NONE && rand.nextFloat() < 0.3f + 0.35f * ornateness + 0.3f * rich;
        d.crest = crest != Crest.NONE && rand.nextFloat() < finery;
        d.ridgeCrest = d.crest && ridgeCrest && rand.nextFloat() < 0.4f + 0.6f * rich;
        d.topper = topper != Topper.NONE && rand.nextFloat() < 0.2f + 0.45f * ornateness * (0.4f + 0.6f * rich) + (grand ? 0.3f : 0f);
        d.spikes = wallSpikes != WallSpikes.NONE && rand.nextFloat() < finery * 0.85f;
        d.neonEaves = neonEaves && rich > 0.55f && rand.nextFloat() < (rich - 0.55f) * 2.2f;
        return d;
    }

    // ------------------------------------------------------------------ its picture (for the map)

    /**
     * A picture of this design (for the architecture map): a house of the given form dressed in
     * all of it, face on, its walls built of the given variety; and beside it, should
     * the design dress its towers up, one of its towers. On a clear background, height pixels tall.
     */
    public BufferedImage picture(int height, BuildingStyle house, BuildingStyle.WallVariant walls) {
        boolean withTower = neonCorners || neonBands || crown != Crown.NONE || blades || rings;
        int width = Math.round(height * (withTower ? 1.45f : 1.05f));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float ground = height * 0.95f;
        float houseWidth = height * 1.0f;
        drawHouse(g, houseWidth * 0.5f + height * 0.02f, ground, houseWidth * 0.9f, height * 0.86f, house, walls);
        if (withTower) drawTower(g, width - height * 0.21f, ground, height * 0.26f, height * 0.9f);
        g.dispose();
        return image;
    }

    /** The house face on, its foot's middle at (x, ground), fitted into roomW by roomH pixels. */
    private void drawHouse(Graphics2D g, float x, float ground, float roomW, float roomH, BuildingStyle house,
                           BuildingStyle.WallVariant masonry) {
        float roofSpan = house.width * house.taper + 2f * house.roofOverhang;
        float horn = horns == Horns.NONE ? 0f : roofSpan * hornLength;
        float wide = Math.max(house.width, roofSpan + 2f * horn);
        float tall = house.wallHeight + house.roofHeight + (topper == Topper.NONE ? 0f : topperHeight) + crestSize * 2f;
        float s = Math.min(roomW / wide, roomH / tall);
        float w = house.width * s, h = house.wallHeight * s, top = w * house.taper;
        float eave = ground - h;
        Color wall = colour(masonry.colour), roof = colour(house.roofColour), orn = colour(ornamentColour), spike = colour(spikeColour);
        boolean round = house.footprint != BuildingStyle.Footprint.BOX;

        // A shadow at its foot
        g.setColor(new Color(0, 0, 0, 60));
        g.fill(new Ellipse2D.Float(x - w * 0.62f, ground - h * 0.05f, w * 1.24f, h * 0.1f));

        // The walls: their outline shaped as the surface is
        float bulge = Math.min(w * 0.12f, reliefDepth * s * 2.2f);
        Path2D.Float walls = new Path2D.Float();
        walls.moveTo(x - w * 0.5f, ground);
        walls.lineTo(x + w * 0.5f, ground);
        side(walls, x + w * 0.5f, ground, x + top * 0.5f, eave, bulge);
        walls.lineTo(x - top * 0.5f, eave);
        side(walls, x - top * 0.5f, eave, x - w * 0.5f, ground, -bulge);
        walls.closePath();
        g.setPaint(new LinearGradientPaint(x - w * 0.5f, 0f, x + w * 0.5f, 0f, new float[] { 0f, 0.35f, 1f },
                new Color[] { shade(wall, round ? 0.72f : 0.95f), shade(wall, 1.1f), shade(wall, round ? 0.55f : 0.85f) }));
        g.fill(walls);
        Shape clip = g.getClip();
        g.clip(walls);
        masonry(g, x - w * 0.6f, eave, w * 1.2f, h, masonry, s, shade(wall, 0.6f));
        reliefMarks(g, x, ground, w, h, s, wall);
        // The door and the windows, a floor at a time
        g.setColor(colour(house.doorColour));
        float doorW = Math.min(w * 0.22f, house.doorWidth * s), doorH = Math.min(h * 0.8f, house.doorHeight * s);
        g.fill(new Rectangle2D.Float(x - doorW * 0.5f, ground - doorH, doorW, doorH));
        Color glass = new Color(40, 46, 70);
        int floors = Math.max(1, house.floors);
        float floorH = h / floors, winW = Math.max(3f, house.windowWidth * s), winH = Math.max(3f, house.windowHeight * s);
        for (int f = 0; f < floors; f++) {
            float cy = ground - floorH * (f + 0.55f);
            for (float side : new float[] { -1f, 1f }) {
                float cx = x + side * w * 0.3f;
                g.setColor(colour(house.frameColour));
                g.fill(new Rectangle2D.Float(cx - winW * 0.5f - 1f, cy - winH * 0.5f - 1f, winW + 2f, winH + 2f));
                g.setColor(glass);
                g.fill(new Rectangle2D.Float(cx - winW * 0.5f, cy - winH * 0.5f, winW, winH));
            }
        }
        g.setClip(clip);
        g.setColor(shade(wall, 0.45f));
        g.setStroke(new BasicStroke(1f));
        g.draw(walls);

        // Spikes from the walls
        if (wallSpikes != WallSpikes.NONE) {
            float len = Math.max(3f, spikeLength * s * 1.6f), half = Math.max(1.2f, len * 0.22f);
            float[] heights = switch (wallSpikes) {
                case ROW -> new float[] { 0.78f };
                case CORNERS -> new float[] { 0.96f };
                default -> new float[] { 0.3f, 0.55f, 0.8f };
            };
            g.setColor(spike);
            for (float t : heights) {
                float y = ground - h * t, reachX = (w + (top - w) * t) * 0.5f;
                float lift = wallSpikes == WallSpikes.CORNERS ? -len * 0.6f : 0f;
                for (float side : new float[] { -1f, 1f }) {
                    Path2D.Float p = new Path2D.Float();
                    p.moveTo(x + side * reachX, y - half);
                    p.lineTo(x + side * (reachX + len), y + lift);
                    p.lineTo(x + side * reachX, y + half);
                    p.closePath();
                    g.fill(p);
                }
            }
        }

        // The roof
        float ov = house.roofOverhang * s, span = top + 2f * ov, rh = house.roofHeight * s;
        float left = x - span * 0.5f, right = x + span * 0.5f;
        Path2D.Float roofShape = new Path2D.Float();
        List<float[]> crestLine = new ArrayList<>();   // edges cresting runs along, {x0, y0, x1, y1}
        float peakX = x, peakY;
        switch (house.roofType) {
            case FLAT -> {
                float slab = Math.max(3f, rh);
                roofShape.append(new Rectangle2D.Float(left, eave - slab, span, slab), false);
                peakY = eave - slab;
                crestLine.add(new float[] { left, peakY, right, peakY });
            }
            case DOME -> {
                roofShape.append(new java.awt.geom.Arc2D.Float(left, eave - rh, span, rh * 2f, 0f, 180f, java.awt.geom.Arc2D.CHORD), false);
                peakY = eave - rh;
                crestLine.add(new float[] { left, eave, right, eave });
            }
            case HIP -> {
                float ridge = span * Math.min(0.4f, house.hipRidgeHalfLength());
                roofShape.moveTo(left, eave);
                roofShape.lineTo(x - ridge, eave - rh);
                roofShape.lineTo(x + ridge, eave - rh);
                roofShape.lineTo(right, eave);
                roofShape.closePath();
                peakY = eave - rh;
                crestLine.add(new float[] { left, eave, right, eave });
                if (ridgeCrest) crestLine.add(new float[] { x - ridge, peakY, x + ridge, peakY });
            }
            case SHED -> {
                roofShape.moveTo(left, eave);
                roofShape.lineTo(left, eave - rh);
                roofShape.lineTo(right, eave - rh * 0.25f);
                roofShape.lineTo(right, eave);
                roofShape.closePath();
                peakX = left + span * 0.15f;
                peakY = eave - rh * 0.95f;
                crestLine.add(new float[] { left, eave, right, eave });
            }
            default -> {
                // Gable, pyramid and spire, seen end on: up to a point
                roofShape.moveTo(left, eave);
                roofShape.lineTo(x, eave - rh);
                roofShape.lineTo(right, eave);
                roofShape.closePath();
                peakY = eave - rh;
                crestLine.add(new float[] { left, eave, right, eave });
                if (ridgeCrest) {
                    crestLine.add(new float[] { left + span * 0.12f, eave - rh * 0.12f, x - span * 0.04f, eave - rh * 0.92f });
                    crestLine.add(new float[] { right - span * 0.12f, eave - rh * 0.12f, x + span * 0.04f, eave - rh * 0.92f });
                }
            }
        }
        g.setPaint(new LinearGradientPaint(left, 0f, right, 0f, new float[] { 0f, 0.4f, 1f },
                new Color[] { shade(roof, 0.85f), shade(roof, 1.15f), shade(roof, 0.7f) }));
        g.fill(roofShape);
        g.setColor(shade(roof, 0.5f));
        g.draw(roofShape);

        // Neon under the eaves
        if (neonEaves) {
            g.setStroke(new BasicStroke(Math.max(2f, s * 1.4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            glowLine(g, left + ov * 0.5f, eave + 1.5f, right - ov * 0.5f, eave + 1.5f, colour(neonA));
        }

        // The corners of the roof
        if (horns != Horns.NONE) {
            float len = Math.max(4f, horn * s), thick = Math.max(2f, hornThickness * s * 1.4f);
            g.setColor(orn);
            for (float side : new float[] { -1f, 1f }) {
                float bx = x + side * span * 0.5f, by = eave;
                Path2D.Float p = new Path2D.Float();
                p.moveTo(bx - side * thick, by - thick * 0.5f);
                switch (horns) {
                    case UPTURNED -> {
                        p.quadTo(bx + side * len * 0.6f, by - thick * 0.2f, bx + side * len, by - len * 0.75f);
                        p.quadTo(bx + side * len * 0.45f, by + thick * 0.4f, bx - side * thick, by + thick * 0.5f);
                    }
                    case DROOPING -> {
                        p.quadTo(bx + side * len * 0.6f, by - thick * 0.3f, bx + side * len, by + len * 0.6f);
                        p.quadTo(bx + side * len * 0.4f, by + thick * 0.6f, bx - side * thick, by + thick * 0.5f);
                    }
                    default -> {
                        p.curveTo(bx + side * len * 0.9f, by, bx + side * len * 1.05f, by - len * 0.6f, bx + side * len * 0.6f, by - len * 0.7f);
                        p.curveTo(bx + side * len * 0.75f, by - len * 0.4f, bx + side * len * 0.5f, by + thick * 0.4f, bx - side * thick, by + thick * 0.5f);
                    }
                }
                p.closePath();
                g.fill(p);
            }
        }

        // Cresting along the eaves (and the ridge)
        if (crest != Crest.NONE) {
            float size = Math.max(2.4f, crestSize * s * 1.5f), gap = size * crestSpacing * 0.8f;
            for (float[] line : crestLine) {
                float lx = line[2] - line[0], ly = line[3] - line[1], length = (float) Math.hypot(lx, ly);
                int count = Math.max(2, Math.round(length / gap));
                for (int k = 0; k <= count; k++) {
                    float px = line[0] + lx * k / count, py = line[1] + ly * k / count;
                    crestItem(g, px, py, size, orn, spike);
                }
            }
        }

        // The topper
        if (topper != Topper.NONE) drawTopper(g, peakX, peakY, Math.max(10f, topperHeight * s * 1.3f), orn, spike);
    }

    /** One side of the walls from (x0, y0) to (x1, y1), shaped as the relief is, bulging out by bulge (pixels, signed outward). */
    private void side(Path2D.Float p, float x0, float y0, float x1, float y1, float bulge) {
        float mx = (x0 + x1) * 0.5f, my = (y0 + y1) * 0.5f;
        switch (relief) {
            case PILLOW, BARREL -> p.quadTo(mx + bulge * 2f, my, x1, y1);
            case PINCH -> p.quadTo(mx - bulge * 2f, my, x1, y1);
            case CHEVRON -> {
                p.lineTo(mx + bulge, my);
                p.lineTo(x1, y1);
            }
            case STUDDED -> {
                for (int k = 1; k <= 6; k++) {
                    float t = k / 6f;
                    float out = (k % 2 == 1) ? bulge * 0.6f : 0f;
                    p.lineTo(x0 + (x1 - x0) * t + out, y0 + (y1 - y0) * t);
                }
            }
            default -> p.lineTo(x1, y1);
        }
    }

    /** The relief's light and shade across the face: flutes, studs, a crease, or a soft bulge. */
    private void reliefMarks(Graphics2D g, float x, float ground, float w, float h, float s, Color wall) {
        switch (relief) {
            case FLUTED -> {
                float pitch = Math.max(3f, reliefPitch * s);
                for (float fx = x - w * 0.5f; fx < x + w * 0.5f; fx += pitch) {
                    g.setColor(shade(wall, 1.2f));
                    g.fill(new Rectangle2D.Float(fx, ground - h, pitch * 0.3f, h));
                    g.setColor(shade(wall, 0.72f));
                    g.fill(new Rectangle2D.Float(fx + pitch * 0.55f, ground - h, pitch * 0.3f, h));
                }
            }
            case STUDDED -> {
                float pitch = Math.max(4f, reliefPitch * s);
                for (float fy = ground - h + pitch * 0.5f; fy < ground; fy += pitch) {
                    for (float fx = x - w * 0.5f + pitch * 0.5f; fx < x + w * 0.5f; fx += pitch) {
                        Path2D.Float d = new Path2D.Float();
                        d.moveTo(fx, fy - pitch * 0.45f);
                        d.lineTo(fx + pitch * 0.45f, fy);
                        d.lineTo(fx, fy + pitch * 0.45f);
                        d.lineTo(fx - pitch * 0.45f, fy);
                        d.closePath();
                        g.setColor(shade(wall, 1.15f));
                        g.fill(d);
                        g.setColor(shade(wall, 0.7f));
                        g.draw(d);
                    }
                }
            }
            case CHEVRON -> {
                g.setColor(shade(wall, 0.7f));
                g.setStroke(new BasicStroke(Math.max(1f, s * 0.8f)));
                int bands = reliefPerFloor ? 2 : 1;
                for (int b = 0; b < bands; b++) {
                    float y = ground - h * (b + 0.5f) / bands;
                    g.draw(new Line2D.Float(x - w * 0.6f, y, x + w * 0.6f, y));
                }
            }
            case PILLOW, BARREL, PINCH -> {
                // (a soft highlight where it swells, a shadow where it pinches)
                boolean in = relief == Relief.PINCH;
                g.setPaint(new java.awt.RadialGradientPaint(x - w * 0.12f, ground - h * 0.5f, Math.max(w, h) * 0.55f,
                        new float[] { 0f, 1f }, new Color[] { in ? new Color(0, 0, 0, 60) : new Color(255, 255, 255, 60), new Color(0, 0, 0, 0) }));
                g.fill(new Rectangle2D.Float(x - w, ground - h, w * 2f, h));
            }
            default -> { }
        }
    }

    /** The walls' masonry, as lines over the face: courses of bricks, triangles, honeycomb or a grid. */
    private static void masonry(Graphics2D g, float x0, float y0, float w, float h, BuildingStyle.WallVariant walls,
                                float s, Color line) {
        BuildingStyle.Pattern pattern = walls.pattern;
        if (walls.finish == BuildingStyle.WallFinish.PLAIN && pattern == BuildingStyle.Pattern.COURSED) return;
        float unit = Math.max(3.5f, walls.courseHeight * s * 1.6f);
        g.setColor(new Color(line.getRed(), line.getGreen(), line.getBlue(), 150));
        g.setStroke(new BasicStroke(0.8f));
        switch (pattern) {
            case TRIANGLE -> {
                for (float y = y0; y < y0 + h; y += unit) {
                    g.draw(new Line2D.Float(x0, y, x0 + w, y));
                    for (float x = x0; x < x0 + w; x += unit) {
                        g.draw(new Line2D.Float(x, y + unit, x + unit * 0.5f, y));
                        g.draw(new Line2D.Float(x + unit * 0.5f, y, x + unit, y + unit));
                    }
                }
            }
            case HEX -> {
                float r = unit * 0.6f, dx = r * 1.732f, dy = r * 1.5f;
                int row = 0;
                for (float y = y0; y < y0 + h + r; y += dy, row++) {
                    for (float x = x0 + (row % 2) * dx * 0.5f; x < x0 + w + r; x += dx) {
                        Path2D.Float hex = new Path2D.Float();
                        for (int k = 0; k < 6; k++) {
                            double a = Math.PI / 6 + k * Math.PI / 3;
                            float px = x + r * (float) Math.cos(a), py = y + r * (float) Math.sin(a);
                            if (k == 0) hex.moveTo(px, py); else hex.lineTo(px, py);
                        }
                        hex.closePath();
                        g.draw(hex);
                    }
                }
            }
            case GRID -> {
                float cell = unit * 1.4f;
                for (float y = y0; y < y0 + h; y += cell) g.draw(new Line2D.Float(x0, y, x0 + w, y));
                for (float x = x0; x < x0 + w; x += cell) g.draw(new Line2D.Float(x, y0, x, y0 + h));
            }
            default -> {
                float length = unit * Math.max(1f, walls.brickAspect);
                int row = 0;
                for (float y = y0; y < y0 + h; y += unit, row++) {
                    g.draw(new Line2D.Float(x0, y, x0 + w, y));
                    for (float x = x0 + (row % 2) * length * 0.5f; x < x0 + w; x += length) g.draw(new Line2D.Float(x, y, x, y + unit));
                }
            }
        }
    }

    /** One piece of cresting standing on (x, y). */
    private void crestItem(Graphics2D g, float x, float y, float size, Color orn, Color spike) {
        switch (crest) {
            case ORBS -> {
                g.setColor(orn);
                g.fill(new Ellipse2D.Float(x - size * 0.5f, y - size * 1.1f, size, size));
                g.setColor(shade(orn, 1.4f));
                g.fill(new Ellipse2D.Float(x - size * 0.25f, y - size * 1.0f, size * 0.3f, size * 0.3f));
            }
            case TEETH -> {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x - size * 0.45f, y);
                p.lineTo(x, y - size * 1.3f);
                p.lineTo(x + size * 0.45f, y);
                p.closePath();
                g.setColor(orn);
                g.fill(p);
            }
            case BLOCKS -> {
                g.setColor(orn);
                g.fill(new Rectangle2D.Float(x - size * 0.4f, y - size, size * 0.8f, size));
            }
            case SPIKES -> {
                g.setColor(spike);
                g.setStroke(new BasicStroke(Math.max(1f, size * 0.25f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Float(x, y, x, y - size * 1.9f));
            }
            case RINGS -> {
                g.setColor(orn);
                g.setStroke(new BasicStroke(Math.max(1f, size * 0.22f)));
                g.draw(new Ellipse2D.Float(x - size * 0.45f, y - size * 1.1f, size * 0.9f, size * 0.9f));
            }
            default -> { }
        }
    }

    /** What stands on the top, its foot at (x, y), height pixels tall. */
    private void drawTopper(Graphics2D g, float x, float y, float height, Color orn, Color spike) {
        float rod = Math.max(1.2f, height * 0.06f), topY = y - height;
        g.setStroke(new BasicStroke(rod, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(spike);
        g.draw(new Line2D.Float(x, y, x, topY + (topper == Topper.ORB ? height * 0.2f : 0f)));
        g.setColor(orn);
        float r = height * 0.2f;
        switch (topper) {
            case MAST -> {
                for (int k = 0; k < 3; k++) {
                    float by = topY + height * (0.15f + 0.22f * k), half = height * (0.12f + 0.08f * k);
                    g.draw(new Line2D.Float(x - half, by, x + half, by));
                }
            }
            case ORB -> {
                g.fill(new Ellipse2D.Float(x - r, topY, r * 2f, r * 2f));
                g.setColor(shade(orn, 1.4f));
                g.fill(new Ellipse2D.Float(x - r * 0.5f, topY + r * 0.3f, r * 0.6f, r * 0.6f));
            }
            case TRIDENT -> {
                float by = topY + height * 0.3f;
                g.draw(new Line2D.Float(x - r, by, x + r, by));
                g.draw(new Line2D.Float(x - r, by, x - r, topY));
                g.draw(new Line2D.Float(x + r, by, x + r, topY));
                g.draw(new Line2D.Float(x, by, x, topY - height * 0.1f));
            }
            case VANE -> {
                Path2D.Float p = new Path2D.Float();
                float vy = topY + height * 0.2f;
                p.moveTo(x - r * 1.6f, vy);
                p.lineTo(x + r * 1.6f, vy - r * 0.2f);
                p.lineTo(x + r * 0.6f, vy - r * 0.9f);
                p.lineTo(x + r * 0.3f, vy - r * 0.15f);
                p.lineTo(x - r * 1.2f, vy - r * 0.8f);
                p.closePath();
                g.fill(p);
            }
            case HOOP -> {
                g.setStroke(new BasicStroke(rod * 1.2f));
                g.draw(new Ellipse2D.Float(x - r * 1.2f, topY, r * 2.4f, r * 2.4f));
            }
            case DISH -> {
                g.fill(new java.awt.geom.Arc2D.Float(x - r * 1.5f, topY - r * 0.6f, r * 3f, r * 2f, 200f, 140f, java.awt.geom.Arc2D.CHORD));
            }
            case SPIRAL -> {
                Path2D.Float p = new Path2D.Float();
                for (int k = 0; k <= 40; k++) {
                    float t = k / 40f;
                    float px = x + (float) Math.sin(t * Math.PI * 6) * r * (1f - t * 0.6f), py = y - height * t;
                    if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
                }
                g.draw(p);
            }
            case CRESCENT -> {
                java.awt.geom.Area moon = new java.awt.geom.Area(new Ellipse2D.Float(x - r * 1.2f, topY - r * 0.4f, r * 2.4f, r * 2.4f));
                moon.subtract(new java.awt.geom.Area(new Ellipse2D.Float(x - r * 0.9f, topY - r * 1.0f, r * 2.2f, r * 2.2f)));
                g.fill(moon);
            }
            default -> { }
        }
    }

    /** One of the design's towers, its foot's middle at (x, ground), w by h pixels. */
    private void drawTower(Graphics2D g, float x, float ground, float w, float h) {
        float crownH = crown == Crown.NONE ? 0f : h * 0.16f;
        float body = h - crownH, topY = ground - body;
        Color glassy = new Color(70, 84, 104);
        g.setColor(new Color(0, 0, 0, 60));
        g.fill(new Ellipse2D.Float(x - w * 0.7f, ground - h * 0.02f, w * 1.4f, h * 0.04f));
        g.setPaint(new LinearGradientPaint(x - w * 0.5f, 0f, x + w * 0.5f, 0f, new float[] { 0f, 0.4f, 1f },
                new Color[] { shade(glassy, 0.85f), shade(glassy, 1.3f), shade(glassy, 0.6f) }));
        g.fill(new Rectangle2D.Float(x - w * 0.5f, topY, w, body));
        // Its windows
        g.setColor(new Color(150, 170, 190, 110));
        float floor = Math.max(3f, body / 22f);
        for (float y = topY + floor * 0.5f; y < ground - floor * 0.3f; y += floor) {
            g.fill(new Rectangle2D.Float(x - w * 0.42f, y, w * 0.84f, floor * 0.4f));
        }
        if (blades) {
            g.setColor(new Color(200, 205, 215));
            for (float side : new float[] { -1f, 1f }) {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x + side * w * 0.5f, topY + body * 0.05f);
                p.lineTo(x + side * w * 0.78f, topY + body * 0.2f);
                p.lineTo(x + side * w * 0.78f, ground - body * 0.15f);
                p.lineTo(x + side * w * 0.5f, ground - body * 0.05f);
                p.closePath();
                g.fill(p);
            }
        }
        g.setStroke(new BasicStroke(Math.max(1.6f, w * 0.06f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (neonBands) {
            int bands = Math.max(3, 14 / Math.max(1, bandEvery));
            for (int b = 1; b <= bands; b++) {
                float y = topY + body * b / (bands + 1f);
                glowLine(g, x - w * 0.5f, y, x + w * 0.5f, y, colour(neonB));
            }
        }
        if (neonCorners) {
            glowLine(g, x - w * 0.5f, topY, x - w * 0.5f, ground - body * 0.04f, colour(neonA));
            glowLine(g, x + w * 0.5f, topY, x + w * 0.5f, ground - body * 0.04f, colour(neonA));
        }
        if (rings) {
            g.setColor(new Color(215, 220, 230));
            g.setStroke(new BasicStroke(Math.max(1.5f, w * 0.07f)));
            g.draw(new Ellipse2D.Float(x - w * 0.85f, topY + body * 0.45f, w * 1.7f, w * 0.35f));
        }
        Color metal = new Color(205, 210, 220);
        g.setColor(metal);
        g.setStroke(new BasicStroke(Math.max(1.2f, w * 0.05f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (crown) {
            case SPIRE -> {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x - w * 0.12f, topY);
                p.lineTo(x, topY - crownH);
                p.lineTo(x + w * 0.12f, topY);
                p.closePath();
                g.fill(p);
            }
            case HALO -> {
                g.draw(new Line2D.Float(x, topY, x, topY - crownH * 0.6f));
                glowEllipse(g, x - w * 0.6f, topY - crownH * 0.75f, w * 1.2f, crownH * 0.4f, colour(neonA));
            }
            case FINS -> {
                for (float side : new float[] { -1f, 0f, 1f }) {
                    Path2D.Float p = new Path2D.Float();
                    float fx = x + side * w * 0.4f;
                    p.moveTo(fx - w * 0.08f, topY);
                    p.lineTo(fx + side * w * 0.12f, topY - crownH * (side == 0f ? 1f : 0.75f));
                    p.lineTo(fx + w * 0.08f, topY);
                    p.closePath();
                    g.fill(p);
                }
            }
            case DISHES -> {
                g.draw(new Line2D.Float(x, topY, x, topY - crownH));
                for (float side : new float[] { -1f, 1f }) {
                    g.fill(new java.awt.geom.Arc2D.Float(x + side * w * 0.25f - w * 0.18f, topY - crownH * 0.55f, w * 0.36f, crownH * 0.5f,
                            side > 0 ? 200f : -20f, 140f, java.awt.geom.Arc2D.CHORD));
                }
            }
            case ORB -> {
                g.draw(new Line2D.Float(x, topY, x, topY - crownH * 0.4f));
                g.setColor(colour(neonB));
                float r = Math.min(w * 0.3f, crownH * 0.35f);
                g.fill(new Ellipse2D.Float(x - r, topY - crownH * 0.4f - r * 2f, r * 2f, r * 2f));
            }
            default -> { }
        }
    }

    /** A neon line: a soft glow under a bright core. */
    private static void glowLine(Graphics2D g, float x0, float y0, float x1, float y1, Color neon) {
        java.awt.Stroke stroke = g.getStroke();
        float width = stroke instanceof BasicStroke b ? b.getLineWidth() : 2f;
        g.setStroke(new BasicStroke(width * 2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(neon.getRed(), neon.getGreen(), neon.getBlue(), 80));
        g.draw(new Line2D.Float(x0, y0, x1, y1));
        g.setStroke(stroke);
        g.setColor(shade(neon, 1.25f));
        g.draw(new Line2D.Float(x0, y0, x1, y1));
    }

    private static void glowEllipse(Graphics2D g, float x, float y, float w, float h, Color neon) {
        java.awt.Stroke stroke = g.getStroke();
        float width = stroke instanceof BasicStroke b ? b.getLineWidth() : 2f;
        g.setStroke(new BasicStroke(width * 2.6f));
        g.setColor(new Color(neon.getRed(), neon.getGreen(), neon.getBlue(), 80));
        g.draw(new Ellipse2D.Float(x, y, w, h));
        g.setStroke(stroke);
        g.setColor(shade(neon, 1.25f));
        g.draw(new Ellipse2D.Float(x, y, w, h));
    }

    private static Color colour(Vector3 c) {
        return new Color(clamp(c.x), clamp(c.y), clamp(c.z));
    }

    private static Color shade(Color c, float f) {
        return new Color(Math.min(255, Math.round(c.getRed() * f)), Math.min(255, Math.round(c.getGreen() * f)),
                Math.min(255, Math.round(c.getBlue() * f)), c.getAlpha());
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
