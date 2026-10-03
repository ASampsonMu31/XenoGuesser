import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Per-nation building appearance: footprint shape, proportions, roof design, colours, and
 * the wall and roof finishes their procedural textures are generated from.
 *
 * For box houses width runs along local X (the ridge direction) and depth along local Z;
 * other footprints are fitted inside the same width by depth rectangle. The front door
 * always faces local +Z.
 *
 * Neighbouring nations tend to share a building tradition: footprint, wall finish and roof
 * form are chosen in geographic blocs, and height and brick proportions drift gradually
 * across the map (see NationKinship).
 */
public class BuildingStyle {
    public enum RoofType { FLAT, GABLE, HIP, PYRAMID, SHED, DOME, SPIRE }
    public enum Footprint { BOX, ROUND, POLYGON }
    public enum WallFinish { BRICK, STONE, PLAIN }
    public enum Bond { RUNNING, THIRD, STACK }
    public enum RoofFinish { TILES, SHINGLES, CORRUGATED, PLAIN }

    public final RoofType roofType;
    public final float width;
    public final float depth;
    public final float wallHeight;
    public final float roofHeight;
    public final float roofOverhang;
    public final float doorWidth;
    public final float doorHeight;
    public final double signChance;
    public final Vector3 wallColour;
    public final Vector3 roofColour;
    public final Vector3 doorColour;

    // Footprint and wall slant
    public Footprint footprint = Footprint.BOX;
    public int sides = 4;
    /** Top of the walls relative to the base: below 1 leans inward, above 1 flares out. */
    public float taper = 1.0f;

    // Wall finish and its texture parameters
    public WallFinish wallFinish = WallFinish.PLAIN;
    public Bond bond = Bond.RUNNING;
    public float courseHeight;        // world units per brick course
    public float brickAspect;         // brick length / course height
    public float mortarFraction;      // share of each course taken by mortar
    public float brickColourVariation;
    public Vector3 mortarColour;
    /** World units covered by one repeat of the wall texture, horizontally and vertically. */
    public float wallTileWidth;
    public float wallTileHeight;

        /** How common each of the world's wall varieties is in this nation (sums to 1). */
    public float[] wallVariantWeights;

    // Roof finish
    public RoofFinish roofFinish = RoofFinish.PLAIN;
    public float roofTileSize;

    // Windows: how many per floor on the front/back and side walls, their size and glazing
    public int windowsFront;
    public int windowsSide;
    public int floors;
    public float windowWidth;
    public float windowHeight;
    public float frameSize;
    public Vector3 glassColour;
    public Vector3 frameColour;

    // A smaller wing attached to one side of the house (box houses only)
    public float extensionChance;
    public float extensionWidthRatio;
    public float extensionDepthRatio;
    public float extensionHeightRatio;

    private BuildingStyle(RoofType roofType, float width, float depth, float wallHeight,
                          float roofHeight, float roofOverhang, float doorWidth, float doorHeight,
                          double signChance, Vector3 wallColour, Vector3 roofColour, Vector3 doorColour) {
        this.roofType = roofType;
        this.width = width;
        this.depth = depth;
        this.wallHeight = wallHeight;
        this.roofHeight = roofHeight;
        this.roofOverhang = roofOverhang;
        this.doorWidth = doorWidth;
        this.doorHeight = doorHeight;
        this.signChance = signChance;
        this.wallColour = wallColour;
        this.roofColour = roofColour;
        this.doorColour = doorColour;
    }

    private static final RoofType[] BOX_ROOFS = { RoofType.FLAT, RoofType.GABLE, RoofType.HIP, RoofType.PYRAMID, RoofType.SHED };
    private static final RoofType[] CENTRED_ROOFS = { RoofType.FLAT, RoofType.PYRAMID, RoofType.DOME, RoofType.SPIRE };
    private static final int[] POLYGON_SIDES = { 3, 5, 6, 8 };

    public static Map<Integer, BuildingStyle> generateForNations(long seed, int numNations,
                                                                 NationKinship kinship, WorldPalette palette) {
        Random rand = new Random(seed + 8888L);

        // Building traditions spread between neighbours: a handful of blocs share a footprint,
        // a wall finish, a roof form and a colour family
        int traditionCount = Math.min(numNations, 3 + rand.nextInt(2));
        int[] tradition = kinship.clusters(rand, traditionCount, 0.15f);
        Footprint[] traditionFootprint = new Footprint[traditionCount];
        int[] traditionSides = new int[traditionCount];
        float[] traditionTaper = new float[traditionCount];
        WallFinish[] traditionFinish = new WallFinish[traditionCount];
        RoofType[] traditionRoof = new RoofType[traditionCount];
        RoofFinish[] traditionRoofFinish = new RoofFinish[traditionCount];
        float[] traditionWallHue = new float[traditionCount];
        float[] traditionRoofHue = new float[traditionCount];
        Bond[] traditionBond = new Bond[traditionCount];
        for (int t = 0; t < traditionCount; t++) {
            // The first tradition keeps ordinary boxes so every world has some familiar houses
            float shapeRoll = t == 0 ? 0f : rand.nextFloat();
            if (shapeRoll < 0.35f) {
                traditionFootprint[t] = Footprint.BOX;
                traditionSides[t] = 4;
            } else if (shapeRoll < 0.6f) {
                traditionFootprint[t] = Footprint.ROUND;
                traditionSides[t] = 20;
            } else {
                traditionFootprint[t] = Footprint.POLYGON;
                traditionSides[t] = POLYGON_SIDES[rand.nextInt(POLYGON_SIDES.length)];
            }
            float slantRoll = rand.nextFloat();
            traditionTaper[t] = slantRoll < 0.5f ? 1.0f : (slantRoll < 0.85f ? 0.72f + rand.nextFloat() * 0.18f : 1.04f + rand.nextFloat() * 0.06f);
            float finishRoll = rand.nextFloat();
            traditionFinish[t] = finishRoll < 0.45f ? WallFinish.BRICK : (finishRoll < 0.65f ? WallFinish.STONE : WallFinish.PLAIN);
            traditionBond[t] = Bond.values()[rand.nextInt(Bond.values().length)];
            traditionRoof[t] = traditionFootprint[t] == Footprint.BOX
                    ? BOX_ROOFS[rand.nextInt(BOX_ROOFS.length)]
                    : CENTRED_ROOFS[rand.nextInt(CENTRED_ROOFS.length)];
            traditionRoofFinish[t] = RoofFinish.values()[rand.nextInt(RoofFinish.values().length)];
            traditionWallHue[t] = rand.nextFloat();
            traditionRoofHue[t] = rand.nextFloat();
        }

        float[] heightDrift = kinship.gradient(rand, 0.12f);
        float[] sizeDrift = kinship.gradient(rand, 0.15f);
        float[] brickAspectDrift = kinship.gradient(rand, 0.1f);
        float[] courseDrift = kinship.gradient(rand, 0.1f);

        // Bricks are fired from the local earth, so their colour starts from the world's soil
        float[] clay = WorldPalette.toHsv(palette.soilBase);

        Map<Integer, BuildingStyle> styles = new HashMap<>();
        for (int n = 1; n <= numNations; n++) {
            int t = tradition[n];
            RoofType roofType = traditionRoof[t];
            Footprint footprint = traditionFootprint[t];

            float width = 55.0f + sizeDrift[n] * 55.0f + rand.nextFloat() * 10.0f;
            float depth = footprint == Footprint.BOX ? width * (0.55f + rand.nextFloat() * 0.35f)
                                                     : width * (0.85f + rand.nextFloat() * 0.15f);
            // Some nations build low bungalows, others tall towers
            float wallHeight = 26.0f + heightDrift[n] * heightDrift[n] * 80.0f + rand.nextFloat() * 8.0f;
            float roofHeight;
            float roofOverhang;
            if (roofType == RoofType.FLAT) {
                roofHeight = 3.0f + rand.nextFloat() * 3.0f;
                roofOverhang = 1.0f + rand.nextFloat() * 2.0f;
            } else if (roofType == RoofType.SPIRE) {
                roofHeight = depth * (1.0f + rand.nextFloat() * 0.9f);
                roofOverhang = 2.0f + rand.nextFloat() * 3.0f;
            } else if (roofType == RoofType.DOME) {
                roofHeight = depth * (0.35f + rand.nextFloat() * 0.25f);
                roofOverhang = 1.0f + rand.nextFloat() * 2.0f;
            } else {
                roofHeight = depth * (0.3f + rand.nextFloat() * 0.5f);
                roofOverhang = 3.0f + rand.nextFloat() * 6.0f;
            }
            float doorWidth = Math.min(width * 0.2f, 12.0f + rand.nextFloat() * 5.0f);
            float doorHeight = Math.min(26.0f, 22.0f + rand.nextFloat() * 6.0f);
            double signChance = 0.35 + rand.nextDouble() * 0.45;

            float wallHue = fraction(traditionWallHue[t] + (rand.nextFloat() - 0.5f) * 0.08f);
            float roofHue = fraction(traditionRoofHue[t] + (rand.nextFloat() - 0.5f) * 0.08f);
            Vector3 wallColour;
            WallFinish finish = traditionFinish[t];
            if (finish == WallFinish.BRICK) {
                // Fired clay: the soil's hue, warmed, darkened and saturated by the kiln,
                // or occasionally a glazed alien colour
                wallColour = rand.nextFloat() < 0.75f
                        ? hsb(fraction(clay[0] - 0.02f + (rand.nextFloat() - 0.5f) * 0.05f),
                              Math.min(0.8f, 0.35f + clay[1] * 0.8f + rand.nextFloat() * 0.15f), 0.42f + rand.nextFloat() * 0.25f)
                        : hsb(wallHue, 0.35f + rand.nextFloat() * 0.3f, 0.45f + rand.nextFloat() * 0.3f);
            } else if (finish == WallFinish.STONE) {
                wallColour = hsb(wallHue, 0.05f + rand.nextFloat() * 0.15f, 0.5f + rand.nextFloat() * 0.3f);
            } else {
                wallColour = hsb(wallHue, 0.12f + rand.nextFloat() * 0.33f, 0.62f + rand.nextFloat() * 0.30f);
            }
            Vector3 roofColour = hsb(roofHue, 0.40f + rand.nextFloat() * 0.40f, 0.30f + rand.nextFloat() * 0.30f);
            Vector3 doorColour = hsb(wallHue, 0.30f + rand.nextFloat() * 0.30f, 0.15f + rand.nextFloat() * 0.15f);

            BuildingStyle style = new BuildingStyle(roofType, width, depth, wallHeight, roofHeight, roofOverhang,
                    doorWidth, doorHeight, signChance, wallColour, roofColour, doorColour);
            style.footprint = footprint;
            style.sides = traditionSides[t];
            style.taper = traditionTaper[t];
            style.wallFinish = finish;
            style.bond = traditionBond[t];
            style.roofFinish = traditionRoofFinish[t];

            // Alien bricks: anything from cubes to long thin slabs, sized by nation
            style.courseHeight = 2.2f + courseDrift[n] * 3.5f;
            style.brickAspect = finish == WallFinish.STONE ? 1.2f + brickAspectDrift[n] * 1.6f : 1.0f + brickAspectDrift[n] * 4.0f;
            style.mortarFraction = 0.06f + rand.nextFloat() * 0.1f;
            style.brickColourVariation = 0.04f + rand.nextFloat() * 0.12f;
            float mortarShade = rand.nextFloat() < 0.6f ? 0.75f + rand.nextFloat() * 0.2f : 0.18f + rand.nextFloat() * 0.2f;
            style.mortarColour = new Vector3(mortarShade, mortarShade * 0.97f, mortarShade * 0.92f);
            if (finish == WallFinish.PLAIN) {
                style.wallTileWidth = style.wallTileHeight = 40.0f + rand.nextFloat() * 40.0f;
            } else {
                style.wallTileHeight = style.courseHeight * 8;   // the texture holds eight courses
                style.wallTileWidth = style.wallTileHeight;
            }
            style.roofTileSize = 12.0f + rand.nextFloat() * 18.0f;
            styles.put(n, style);
        }

        // Separate stream so windows and extensions leave the established nation looks unchanged
        Random detailRand = new Random(seed + 9191L);
        for (int n = 1; n <= numNations; n++) {
            BuildingStyle style = styles.get(n);
            style.floors = Math.max(1, Math.round(style.wallHeight / 26.0f));
            style.windowWidth = 7.0f + detailRand.nextFloat() * 6.0f;
            style.windowHeight = Math.min(style.wallHeight / style.floors * 0.55f, 8.0f + detailRand.nextFloat() * 6.0f);
            int maxFront = Math.max(1, (int) ((style.width - style.doorWidth) / (style.windowWidth * 2.2f)));
            style.windowsFront = 1 + detailRand.nextInt(Math.min(4, maxFront));
            int maxSide = Math.max(0, (int) (style.depth / (style.windowWidth * 2.2f)));
            style.windowsSide = maxSide == 0 ? 0 : detailRand.nextInt(Math.min(3, maxSide) + 1);
            style.frameSize = detailRand.nextFloat() < 0.3f ? 0.0f : 0.8f + detailRand.nextFloat() * 1.4f;

            float glass = 0.05f + detailRand.nextFloat() * 0.15f;
            float tint = detailRand.nextFloat();
            style.glassColour = tint < 0.4f ? new Vector3(glass * 0.8f, glass, glass * 1.8f)
                              : tint < 0.7f ? new Vector3(glass * 0.8f, glass * 1.4f, glass * 1.2f)
                              : new Vector3(glass, glass, glass);
            float frame = detailRand.nextFloat();
            style.frameColour = frame < 0.4f ? new Vector3(0.93f, 0.93f, 0.9f)
                              : frame < 0.7f ? style.doorColour
                              : new Vector3(0.12f, 0.12f, 0.12f);

            boolean canExtend = style.footprint == Footprint.BOX && style.taper == 1.0f;
            style.extensionChance = !canExtend || detailRand.nextFloat() < 0.3f ? 0.0f : 0.3f + detailRand.nextFloat() * 0.6f;
            style.extensionWidthRatio = 0.35f + detailRand.nextFloat() * 0.25f;
            style.extensionDepthRatio = 0.45f + detailRand.nextFloat() * 0.35f;
            style.extensionHeightRatio = 0.5f + detailRand.nextFloat() * 0.3f;
        }
        return styles;
    }

        /**
     * One kind of wall that houses can be built with: a finish, a colour and, for masonry,
     * the size, shape and bond of its bricks or stones. A world has a handful of these, and
     * every nation builds with its own mix of them.
     */
    public static final class WallVariant {
        public WallFinish finish;
        public Bond bond;
        public Vector3 colour;
        public Vector3 mortarColour;
        public float courseHeight;
        public float brickAspect;
        public float mortarFraction;
        public float colourVariation;
        public float tileWidth;
        public float tileHeight;
    }

    /**
     * The world's wall varieties. There is always a white render and a brick fired from the
     * local soil, so every world has the familiar pair, plus a few others: coloured renders,
     * darker or glazed bricks, and stone.
     */
    public static java.util.List<WallVariant> generateWallVariants(long seed, WorldPalette palette) {
        Random rand = new Random(seed + 7373L);
        float[] clay = WorldPalette.toHsv(palette.soilBase);
        int count = 7 + rand.nextInt(4);
        java.util.List<WallVariant> variants = new java.util.ArrayList<>();
        for (int v = 0; v < count; v++) {
            WallVariant variant = new WallVariant();
            float kind = v == 0 ? 0f : v == 1 ? 0.3f : rand.nextFloat();
            if (kind < 0.12f) {            // white or cream render
                variant.finish = WallFinish.PLAIN;
                variant.colour = hsb(0.08f + rand.nextFloat() * 0.08f, 0.03f + rand.nextFloat() * 0.08f, 0.88f + rand.nextFloat() * 0.08f);
            } else if (kind < 0.45f) {     // brick from the local clay
                variant.finish = WallFinish.BRICK;
                variant.colour = hsb(fraction(clay[0] - 0.02f + (rand.nextFloat() - 0.5f) * 0.05f),
                        Math.min(0.8f, 0.35f + clay[1] * 0.8f + rand.nextFloat() * 0.15f), 0.38f + rand.nextFloat() * 0.25f);
            } else if (kind < 0.6f) {      // glazed alien brick
                variant.finish = WallFinish.BRICK;
                variant.colour = hsb(rand.nextFloat(), 0.35f + rand.nextFloat() * 0.3f, 0.45f + rand.nextFloat() * 0.3f);
            } else if (kind < 0.78f) {     // stone
                variant.finish = WallFinish.STONE;
                variant.colour = hsb(rand.nextFloat(), 0.05f + rand.nextFloat() * 0.18f, 0.5f + rand.nextFloat() * 0.3f);
            } else {                       // coloured render
                variant.finish = WallFinish.PLAIN;
                variant.colour = hsb(rand.nextFloat(), 0.15f + rand.nextFloat() * 0.3f, 0.62f + rand.nextFloat() * 0.3f);
            }
            variant.bond = Bond.values()[rand.nextInt(Bond.values().length)];
            // Alien bricks: anything from cubes to long thin slabs
            variant.courseHeight = 2.2f + rand.nextFloat() * 3.5f;
            variant.brickAspect = variant.finish == WallFinish.STONE ? 1.2f + rand.nextFloat() * 1.6f : 1.0f + rand.nextFloat() * 4.0f;
            variant.mortarFraction = 0.06f + rand.nextFloat() * 0.1f;
            variant.colourVariation = 0.04f + rand.nextFloat() * 0.12f;
            float mortarShade = rand.nextFloat() < 0.6f ? 0.75f + rand.nextFloat() * 0.2f : 0.18f + rand.nextFloat() * 0.2f;
            variant.mortarColour = new Vector3(mortarShade, mortarShade * 0.97f, mortarShade * 0.92f);
            if (variant.finish == WallFinish.PLAIN) {
                variant.tileWidth = variant.tileHeight = 40.0f + rand.nextFloat() * 40.0f;
            } else {
                variant.tileHeight = variant.courseHeight * 8;   // the texture holds eight courses
                variant.tileWidth = variant.tileHeight;
            }
            variants.add(variant);
        }
        return variants;
    }

    /**
     * Gives every nation its mix of the wall varieties. Each variety's popularity follows its
     * own smooth spread across the map, so a variety common in one country is likely to be
     * common next door too, and varieties matching a nation's building tradition get a boost.
     */
    public static void assignWallVariants(long seed, Map<Integer, BuildingStyle> styles,
                                          java.util.List<WallVariant> variants, NationKinship kinship) {
        Random rand = new Random(seed + 7474L);
        float[][] popularity = new float[variants.size()][];
        for (int v = 0; v < variants.size(); v++) {
            popularity[v] = kinship.gradient(rand, 0.15f);
        }
        for (Map.Entry<Integer, BuildingStyle> entry : styles.entrySet()) {
            int n = entry.getKey();
            BuildingStyle style = entry.getValue();
            float[] weights = new float[variants.size()];
            float total = 0;
            for (int v = 0; v < variants.size(); v++) {
                float p = popularity[v][n];
                // A popular variety is common but rarely universal: there's always a mix
                weights[v] = 0.25f + 1.6f * p * p;
                if (variants.get(v).finish == style.wallFinish) weights[v] *= 1.6f;
                total += weights[v];
            }
            for (int v = 0; v < weights.length; v++) weights[v] /= total;
            style.wallVariantWeights = weights;
        }
    }

        /**
     * One house form a world's builders use: footprint, proportions, height and roof.
     * Sizes are relative to the nation's usual house, so a nation's bungalows and towers
     * keep its own scale. A world has a handful; every nation builds mostly its own
     * traditional form plus its own mix of these.
     */
    public static final class FormVariant {
        public Footprint footprint;
        public int sides;
        public float taper;
        public RoofType roofType;
        public float sizeScale;
        public float depthRatio;
        public float heightScale;
        public float roofPitch;
        public float overhangScale;
    }

    /** How common each house form is in this nation: index 0 is its own tradition, then the world's forms. */
    public float[] formWeights;

    public static java.util.List<FormVariant> generateFormVariants(long seed) {
        Random rand = new Random(seed + 6161L);
        int count = 6 + rand.nextInt(4);
        java.util.List<FormVariant> forms = new java.util.ArrayList<>();
        for (int v = 0; v < count; v++) {
            FormVariant form = new FormVariant();
            // The first is always a plain pitched box, so every world has some familiar houses
            float shapeRoll = v == 0 ? 0f : rand.nextFloat();
            if (shapeRoll < 0.5f) {
                form.footprint = Footprint.BOX;
                form.sides = 4;
            } else if (shapeRoll < 0.72f) {
                form.footprint = Footprint.ROUND;
                form.sides = 20;
            } else {
                form.footprint = Footprint.POLYGON;
                form.sides = POLYGON_SIDES[rand.nextInt(POLYGON_SIDES.length)];
            }
            float slantRoll = rand.nextFloat();
            form.taper = v == 0 || slantRoll < 0.55f ? 1.0f
                    : (slantRoll < 0.88f ? 0.7f + rand.nextFloat() * 0.2f : 1.04f + rand.nextFloat() * 0.06f);
            form.roofType = v == 0 ? RoofType.GABLE
                    : form.footprint == Footprint.BOX ? BOX_ROOFS[rand.nextInt(BOX_ROOFS.length)]
                    : CENTRED_ROOFS[rand.nextInt(CENTRED_ROOFS.length)];
            form.sizeScale = 0.65f + rand.nextFloat() * 0.75f;
            form.depthRatio = form.footprint == Footprint.BOX ? 0.5f + rand.nextFloat() * 0.45f : 0.85f + rand.nextFloat() * 0.15f;
            // Bungalows, ordinary houses and the occasional tower
            float heightRoll = rand.nextFloat();
            form.heightScale = heightRoll < 0.3f ? 0.6f + rand.nextFloat() * 0.25f
                    : heightRoll < 0.78f ? 0.9f + rand.nextFloat() * 0.4f
                    : 1.7f + rand.nextFloat() * 1.0f;
            form.roofPitch = rand.nextFloat();
            form.overhangScale = 0.5f + rand.nextFloat();
            forms.add(form);
        }
        return forms;
    }

    /**
     * This nation's house in another form: the nation's colours, doors, windows and
     * finishes on the form's footprint, proportions, height and roof.
     */
    public BuildingStyle withForm(FormVariant form, Random rand) {
        float newWidth = Math.max(35.0f, Math.min(150.0f, width * form.sizeScale));
        float newDepth = newWidth * form.depthRatio;
        float newWallHeight = Math.max(18.0f, Math.min(220.0f, wallHeight * form.heightScale));
        float pitch = form.roofPitch;
        float newRoofHeight;
        float newOverhang;
        switch (form.roofType) {
            case FLAT -> { newRoofHeight = 3.0f + pitch * 3.0f; newOverhang = 1.0f + form.overhangScale * 1.5f; }
            case SPIRE -> { newRoofHeight = newDepth * (1.0f + pitch * 0.9f); newOverhang = 2.0f + form.overhangScale * 2.0f; }
            case DOME -> { newRoofHeight = newDepth * (0.35f + pitch * 0.25f); newOverhang = 1.0f + form.overhangScale * 1.5f; }
            default -> { newRoofHeight = newDepth * (0.3f + pitch * 0.5f); newOverhang = 3.0f + form.overhangScale * 4.0f; }
        }
        BuildingStyle style = new BuildingStyle(form.roofType, newWidth, newDepth, newWallHeight, newRoofHeight, newOverhang,
                Math.min(newWidth * 0.2f, doorWidth), doorHeight, signChance, wallColour, roofColour, doorColour);
        style.footprint = form.footprint;
        style.sides = form.sides;
        style.taper = form.taper;
        style.wallFinish = wallFinish;
        style.bond = bond;
        style.courseHeight = courseHeight;
        style.brickAspect = brickAspect;
        style.mortarFraction = mortarFraction;
        style.brickColourVariation = brickColourVariation;
        style.mortarColour = mortarColour;
        style.wallTileWidth = wallTileWidth;
        style.wallTileHeight = wallTileHeight;
        style.wallVariantWeights = wallVariantWeights;
        style.formWeights = formWeights;
        style.roofFinish = roofFinish;
        style.roofTileSize = roofTileSize;

        style.floors = Math.max(1, Math.round(newWallHeight / 26.0f));
        style.windowWidth = windowWidth;
        style.windowHeight = Math.min(newWallHeight / style.floors * 0.55f, windowHeight);
        int maxFront = Math.max(1, (int) ((newWidth - style.doorWidth) / (windowWidth * 2.2f)));
        style.windowsFront = Math.max(1, Math.min(windowsFront + rand.nextInt(2), maxFront));
        int maxSide = Math.max(0, (int) (newDepth / (windowWidth * 2.2f)));
        style.windowsSide = Math.min(Math.max(windowsSide, rand.nextInt(2)), maxSide);
        style.frameSize = frameSize;
        style.glassColour = glassColour;
        style.frameColour = frameColour;

        boolean canExtend = form.footprint == Footprint.BOX && form.taper == 1.0f;
        style.extensionChance = canExtend ? Math.max(extensionChance, rand.nextFloat() < 0.5f ? 0.0f : 0.4f) : 0.0f;
        style.extensionWidthRatio = extensionWidthRatio;
        style.extensionDepthRatio = extensionDepthRatio;
        style.extensionHeightRatio = extensionHeightRatio;
        return style;
    }

    /**
     * Gives every nation its mix of house forms. A nation mostly builds its own traditional
     * house, but each world form's popularity spreads smoothly across the map, so a form
     * common in one country tends to turn up next door as well.
     */
    public static void assignFormWeights(long seed, Map<Integer, BuildingStyle> styles,
                                         java.util.List<FormVariant> forms, NationKinship kinship) {
        Random rand = new Random(seed + 6262L);
        float[][] popularity = new float[forms.size()][];
        for (int f = 0; f < forms.size(); f++) {
            popularity[f] = kinship.gradient(rand, 0.15f);
        }
        for (Map.Entry<Integer, BuildingStyle> entry : styles.entrySet()) {
            int n = entry.getKey();
            float[] weights = new float[forms.size() + 1];
            weights[0] = 1.6f;
            float total = weights[0];
            for (int f = 0; f < forms.size(); f++) {
                float p = popularity[f][n];
                weights[f + 1] = 0.06f + 0.8f * p * p;
                total += weights[f + 1];
            }
            for (int f = 0; f < weights.length; f++) weights[f] /= total;
            entry.getValue().formWeights = weights;
        }
    }

    /**
     * Half-length of the hip roof ridge in unit roof space, chosen so the end
     * slopes run in as far as the side slopes.
     */
    public float hipRidgeHalfLength() {
        float roofWidth = width + 2.0f * roofOverhang;
        float roofDepth = depth + 2.0f * roofOverhang;
        return Math.max(0.05f, 0.5f * (1.0f - roofDepth / roofWidth));
    }

    private static float fraction(float value) {
        return value - (float) Math.floor(value);
    }

    private static Vector3 hsb(float hue, float saturation, float brightness) {
        Color colour = new Color(Color.HSBtoRGB(hue, Math.max(0f, Math.min(1f, saturation)), Math.max(0f, Math.min(1f, brightness))));
        return new Vector3(colour.getRed() / 255.0f, colour.getGreen() / 255.0f, colour.getBlue() / 255.0f);
    }
}
