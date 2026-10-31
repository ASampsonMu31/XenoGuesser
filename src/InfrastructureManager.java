import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.Vector3;

/**
 * Turns the road network into renderable infrastructure: road surfaces and
 * markings, highway guard rails, cul-de-sacs, houses with their gardens and
 * fences, and signs. Houses are laid out once for the whole region along the
 * roads; everything is then baked per geodesic cell into a handful of merged meshes.
 *
 * This class holds the world's infrastructure state (segments, houses, shops, their
 * lookups by place) and is the facade the rest of the game uses. The work for each kind
 * of thing is in its parts (each an InfraPart, reaching this state through infra):
 * HousePlacement lays the houses out; HouseBuilder, HouseDressing, Gardens and ShopBuilder
 * build them; RoadGeometry, RoadFurniture and Bridges build the roads; BorderCrossings the
 * fenced borders; RoadsideSigns the signs; InfraMaterials their materials; InfraViewpoints
 * the developer views. InfraGeometry holds the frame and mesh helpers they all share.
 */
public class InfrastructureManager extends InfraGeometry {

    // Guard rails line rural highways in nations that build them
    private static final float RAIL_MAX_URBANNESS = 0.2f;

    private static final float URBAN_FRONTAGE_MIN = 1.0f;

    private static final float URBAN_FRONTAGE_RANGE = 0.6f;

    final Map<Integer, Double> nationSignProbabilities;

    final Map<Integer, Double> nationBuildingProbabilities;

    final Map<Integer, Float> nationUrbanDensities;

    final Map<Integer, RoadLineStyle> roadLineStyles;

        final Map<Integer, BuildingStyle> buildingStyles;

    // Each nation's house in every form: index 0 is its own tradition, then the world's forms
    final Map<Integer, BuildingStyle[]> formStyles = new HashMap<>();

    private List<BuildingStyle.FormVariant> formVariants;

    final Map<Integer, GuardRailStyle> guardRailStyles;

    // Each nation's roadside bollards (see BollardStyle) and utility poles (see UtilityPoleStyle)
    final BollardStyle.World bollardWorld;

    // The world's ways of dressing buildings up, and where each is built (see ArchitectureStyle)
    final ArchitectureStyle.World architectureWorld;

    final UtilityPoleStyle.World poleWorld;

    final Map<Integer, FenceStyle> fenceStyles;

    final NationGenerationManager nationManager;

    // Which nations are at odds (see NationKinship.hostile), and how far each texel of the
    // nations' territory is from a border between two that are, in cells (see hostileBorderDistance)
    final NationKinship kinship;

    private final float[] borderGap;

    // Islands the main roads never reached have a wealth of their own (see RoadNetworkBuilder.buildIslandTracks)
    private volatile RoadNetworkBuilder islandWealth;

    final SettlementManager settlementManager;

    final XenoGuesser_GLEventListener mainListener;

    final long worldSeed;

    final List<RoadPath> roadNetwork = new ArrayList<>();

    final List<RoadPath.DeadEnd> deadEnds = new ArrayList<>();

    final Map<Integer, List<RoadSegment>> segmentsByPath = new HashMap<>();

    final List<RoadSegment> landSegments = new ArrayList<>();

    // Which geodesic cell's batch builds each segment and house: the BATCH_LEVEL cell its middle
    // is in (D5). Every lookup by place goes through the CellIndexes below.
    public static final int BATCH_LEVEL = 7;

    final GeodesicChunkGrid batchGrid = new GeodesicChunkGrid(BATCH_LEVEL);

    final Map<Long, List<RoadSegment>> roadSegmentsByCell = new HashMap<>();

    final List<House> houses = new ArrayList<>();

    final CellIndex<RoadSegment> segmentIndex = new CellIndex<>(LOOKUP_LEVEL);

    final CellIndex<House> houseIndex = new CellIndex<>(LOOKUP_LEVEL);

    final CellIndex<House> drivewayIndex = new CellIndex<>(LOOKUP_LEVEL);

    final CellIndex<Shop> shopIndex = new CellIndex<>(LOOKUP_LEVEL);

    final Map<Integer, FlagDesigner.Spec> flags;

    // Each nation's writing, and the goods in its shops
    NationScripts scripts;

    Products products;

    // Shops: some of the buildings in the middle of towns
    final List<Shop> shops = new ArrayList<>();

    final Map<Integer, Vector3[]> shopColours = new HashMap<>();

    RoadRoutes routes;

    // How proudly each nation flies its flag, 0 to 1: how often flags appear on houses,
    // flagpoles and signs
    private final Map<Integer, Float> patriotism = new HashMap<>();

    // How keen each nation is on pasting posters on its walls, 0.3 to 1
    final Map<Integer, Float> posterKeenness = new HashMap<>();

    // Maps for signs are drawn here, off the GL thread
    static final java.util.concurrent.ExecutorService SIGN_MAP_ARTIST = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sign-map-artist");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    final float windAngle;

    /**
     * A solid shape, for collision: a wall, fence or rail along the ground from a to b, size
     * its half-width, or (b null) a post of radius size at a; top how high it stands.
     */
    private record Obstacle(SpherePosition a, SpherePosition b, float size, float top, float bottom) {
        Obstacle(SpherePosition a, SpherePosition b, float size, float top) {
            this(a, b, size, top, Float.NEGATIVE_INFINITY);
        }
    }

    // Solid shapes recorded as each cell is built, filed on the sphere. The world never
    // changes, so a cell's shapes stay valid after its meshes are dropped (and are only filed
    // the first time it is built).
    private final CellIndex<Obstacle> obstacleIndex = new CellIndex<>(CellIndex.DEFAULT_LEVEL);

    private final java.util.Set<Long> cellsRecorded = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private List<Obstacle> recording;

    final Map<Long, List<House>> housesByCell = new HashMap<>();

    // The world's famous landmarks (see Landmarks): the built ones planned with the roads, before the houses
    final Landmarks landmarks = new Landmarks();

    volatile boolean roadNetworkInitialized;

    float seaLevel;

    PerlinNoise terrainNoise;

    // Unit building meshes shared by every house, plus each nation's roof
        final Map<Integer, float[]> roofVertices = new HashMap<>();

        java.util.function.Function<String, Texture> nationTextures = name -> null;

    final List<BuildingStyle.WallVariant> wallVariants;

    final Map<Integer, int[]> roofIndices = new HashMap<>();

    // Its parts: the code for each kind of thing, working on the state above (see InfraPart)
    final HousePlacement housePlacement = new HousePlacement(this);

    final HouseBuilder houseBuilder = new HouseBuilder(this);

    final HouseDressing houseDressing = new HouseDressing(this);

    final Gardens gardens = new Gardens(this);

    final ShopBuilder shopBuilder = new ShopBuilder(this);

    final RoadGeometry roadGeometry = new RoadGeometry(this);

    final RoadFurniture roadFurniture = new RoadFurniture(this);

    final Bridges bridges = new Bridges(this);

    final BorderCrossings borderCrossings = new BorderCrossings(this);

    final RoadsideSigns roadsideSigns = new RoadsideSigns(this);

    final InfraMaterials infraMaterials = new InfraMaterials(this);

    final InfraViewpoints infraViewpoints = new InfraViewpoints(this);

    /** A shop: a building in a town centre with a shop front, its name over it and goods in its windows. */
    public static final class Shop {
        final House house;
        public final int nationId;
        public final int colour;
        public final boolean grocer;
        int[] name;

        Shop(House house, Random rand) {
            this.house = house;
            this.nationId = house.nationId;
            this.colour = rand.nextInt(4);
            // Fresh produce is likelier where people are poorer; the well off buy packets and boxes
            this.grocer = rand.nextFloat() < 0.8f - 0.65f * house.richness;
        }
    }

    /**
     * A house's way in and out, for the people who live there: a point just inside the front
     * door, the doorstep, the gate, and a point on the pavement beside the road it faces, with
     * that road's direction there. Worked out in the house's frame (local +z towards the road);
     * the places are given on the planet too, on the datum.
     */
    public static final class Doorway {
        // The house's frame, and in it {x, z}: the doorstep, the kerb point, the road's way
        // there (unit), the gate
        public final SphereFrame frame;
        public final float[] localDoor, localKerb, localRoadDir, localGate;
        public final SpherePosition inside, door, gate, kerb;
        // The driveway in the house's frame, or null: {mouth x, z (where it meets the front),
        // inward x, z (unit, towards the back), its length, its width, x, z (unit, across it
        // towards the garden path)}
        public final float[] localDriveway;
        public final int nationId;
        public final int path;
        public final float urbanness;
        public final long id;
        // A shop's door: people go in to shop, but nobody lives there
        public final boolean shop;
        // How far the opening in the front runs each way along the road from the kerb point
        // (gate, and driveway if there is one), so nobody parks across it
        public final float gapBefore, gapAfter;
        // The front door's corners in the house's frame {x, y above the datum, z} (bottom left,
        // bottom right, top right, top left), once built
        public volatile float[][] doorCorners;

        private Doorway(SphereFrame frame, float[] localInside, float[] localDoor, float[] localKerb, float[] localRoadDir,
                        float[] localGate, float[] localDriveway, int nationId, int path, float urbanness, long id,
                        boolean shop, float[] gap) {
            this.frame = frame;
            this.localDoor = localDoor;
            this.localKerb = localKerb;
            this.localRoadDir = localRoadDir;
            this.localGate = localGate;
            this.localDriveway = localDriveway;
            this.inside = at(localInside[0], localInside[1]);
            this.door = at(localDoor[0], localDoor[1]);
            this.gate = at(localGate[0], localGate[1]);
            this.kerb = at(localKerb[0], localKerb[1]);
            this.gapBefore = gap[0];
            this.gapAfter = gap[1];
            this.nationId = nationId;
            this.path = path;
            this.urbanness = urbanness;
            this.id = id;
            this.shop = shop;
        }

        /** A point in the house's frame, on the datum. */
        public SpherePosition at(double x, double z) {
            return SphereMath.atAltitude(frame.toWorld(x, 0.0, z), 0.0);
        }

        /** A level direction in the house's frame as a heading (D10) at a place near the house. */
        public double heading(SpherePosition place, double x, double z) {
            double[] world = frame.worldDirection(x, 0.0, z);
            return SphereFrame.at(place).headingOf(world[0], world[1], world[2]);
        }

        /** The place along the road from the kerb point by a distance (either way), on the datum. */
        public SpherePosition alongRoad(double along) {
            return at(localKerb[0] + localRoadDir[0] * along, localKerb[1] + localRoadDir[1] * along);
        }
    }

    /**
     * A place to leave a vehicle at the edge of a road: on the datum, with the road's way there
     * and the way out towards the edge it's at (headings, D10, at that place), the road's half
     * width and its nation.
     */
    public record Kerb(SpherePosition place, double along, double out, float half, int nationId) {
    }

    /**
     * A traffic light at a crossing: the pole's foot on the datum, the way the road runs there
     * (D10), whether the lamps are one above the other (or side by side), the crossing's number,
     * and the colours of the booth's side's turn and the other side's.
     */
    public record TrafficLight(SpherePosition foot, double heading, boolean vertical, int check, float[] colourA, float[] colourB) {
    }

    /** A border booth's guard: where they stand (the booth's middle, on the datum), the way they face (D10), their nation. */
    public record Guard(SpherePosition place, double facing, int nation) {
    }

    @FunctionalInterface
    public interface RoadPathVisitor {
        void visit(RoadPath path);
    }

    @FunctionalInterface
    public interface BuildingVisitor {
        /**
         * A footprint: its frame (origin on the datum at its middle, turned as the building is,
         * local +z the door side) and its outline round the middle in that frame's x and z.
         */
        void visit(SphereFrame frame, float[][] outline, int nationId);
    }

    public InfrastructureManager(long seed, int numNations, NationGenerationManager nationManager,
                                 SettlementManager settlementManager, XenoGuesser_GLEventListener mainListener) {
        this.worldSeed = seed;
        this.nationManager = nationManager;
        this.settlementManager = settlementManager;
        this.mainListener = mainListener;
        this.nationSignProbabilities = new HashMap<>();
        this.nationBuildingProbabilities = new HashMap<>();
        this.nationUrbanDensities = new HashMap<>();

        Random rand = new Random(seed + 5555L);
        Random buildingRand = new Random(seed + 6666L);
        Random urbanRand = new Random(seed + 4242L);

        for (int i = 1; i <= numNations; i++) {
            double prob = 0.00025 + (rand.nextDouble() * 0.00125);
            nationSignProbabilities.put(i, prob);
            nationBuildingProbabilities.put(i, 0.00025 + (buildingRand.nextDouble() * 0.00125));
            nationUrbanDensities.put(i, URBAN_FRONTAGE_MIN + urbanRand.nextFloat() * URBAN_FRONTAGE_RANGE);
        }

                // Neighbouring nations share conventions, so styles are dealt out geographically
        NationKinship kinship = new NationKinship(nationManager);
        // The nations' cultures: about one to every two or three nations, each with a writing
        // system of its own (so no more than there are writing systems)
        int cultureCount = Math.max(2, Math.round(numNations / 2.5f));
        if (NationScripts.available() > 0) cultureCount = Math.min(cultureCount, NationScripts.available());
        int[] cultures = kinship.assignCultures(new Random(seed * 31L + 7L), cultureCount);
        this.kinship = kinship;
        WorldPalette palette = new WorldPalette(seed);
        this.roadLineStyles = RoadLineStyle.generateForNations(seed, numNations, kinship);
                this.buildingStyles = BuildingStyle.generateForNations(seed, numNations, kinship, palette);
        this.wallVariants = BuildingStyle.generateWallVariants(seed, palette);
        BuildingStyle.assignWallVariants(seed, buildingStyles, wallVariants, kinship);
        this.guardRailStyles = GuardRailStyle.generateForNations(seed, numNations);
        this.fenceStyles = FenceStyle.generateForNations(seed, numNations, kinship);
        this.bollardWorld = BollardStyle.generateForNations(seed, numNations, kinship);
        this.poleWorld = UtilityPoleStyle.generateForNations(seed, numNations, kinship);
        System.out.println("[BOLLARDS] " + bollardWorld.designs().summary());
        System.out.println("[POLES] " + poleWorld.designs().summary());
        this.architectureWorld = ArchitectureStyle.generateForNations(seed, numNations, kinship);
        System.out.println("[ARCHITECTURE] " + architectureWorld.summary());
        this.flags = FlagDesigner.design(seed, numNations, kinship);
        this.scripts = new NationScripts(seed, numNations, cultures);
        this.borderGap = hostileBorderDistance();
        this.products = new Products(seed, numNations, kinship);
        for (int n = 1; n <= numNations; n++) {
            // Shop fronts are painted in strong colours, unlike the houses round them
            Random paint = new Random(seed * 29L + n * 101L);
            Vector3[] colours = new Vector3[4];
            float hue = paint.nextFloat();
            for (int k = 0; k < 4; k++) {
                float[] c = WorldPalette.hsv(hue + k * 0.25f + (paint.nextFloat() - 0.5f) * 0.1f, 0.45f + paint.nextFloat() * 0.4f,
                        0.45f + paint.nextFloat() * 0.45f);
                colours[k] = new Vector3(c[0], c[1], c[2]);
            }
            shopColours.put(n, colours);
        }
        for (int n = 1; n <= numNations; n++) {
            // Most nations are reserved about their flag, many hardly fly it at all, and the odd
            // one flies it everywhere: spread wide on a log scale round a low middle (about a
            // third under a twentieth, one in seven over a half, one in thirteen at the most)
            double roll = new Random(seed * 53L + n * 977L).nextGaussian();
            patriotism.put(n, (float) Math.max(0.005, Math.min(1.0, 0.1 * Math.exp(1.6 * roll))));
            posterKeenness.put(n, 0.3f + 0.7f * new Random(seed * 59L + n * 613L).nextFloat());
        }
        // The wind that every flag in this world flies in
        this.windAngle = new Random(seed * 71L + 5L).nextFloat() * (float) Math.PI * 2;
                this.formVariants = BuildingStyle.generateFormVariants(seed);
        BuildingStyle.assignFormWeights(seed, buildingStyles, formVariants, kinship);
        for (Map.Entry<Integer, BuildingStyle> entry : buildingStyles.entrySet()) {
            int nation = entry.getKey();
            BuildingStyle[] forms = new BuildingStyle[formVariants.size() + 1];
            forms[0] = entry.getValue();
            Random formRand = new Random(seed * 53L + nation);
            for (int f = 0; f < formVariants.size(); f++) {
                forms[f + 1] = entry.getValue().withForm(formVariants.get(f), formRand);
            }
            formStyles.put(nation, forms);
            for (int f = 0; f < forms.length; f++) {
                MeshBuilder roof = BuildingMeshes.createRoof(forms[f]);
                roofVertices.put(roofKey(nation, f), roof.vertexArray());
                roofIndices.put(roofKey(nation, f), roof.indexArray());
            }
        }
    }

    @Override
    PerlinNoise terrain() {
        return terrainNoise;
    }

    /** Developer viewpoints: places to stand to look at one kind of thing (see InfraViewpoints). */
    InfraViewpoints viewpoints() {
        return infraViewpoints;
    }

    /** The checks on fenced borders, for the guards and traffic that pass them (see BorderCrossings). */
    BorderCrossings borders() {
        return borderCrossings;
    }

    // ============================================================
    //  NATIONS: LOOKS, WEALTH AND TEXTURES
    // ============================================================

    public NationScripts scripts() {
        return scripts;
    }

    public Products products() {
        return products;
    }

    /** How proudly a nation flies its flag, 0 to 1. */
    public float patriotismOf(int nationId) {
        return patriotism.getOrDefault(nationId, 0.3f);
    }

    /** A nation's flag as a picture (FlagDesigner.WIDTH by HEIGHT), or null if it has none. */
    public java.awt.image.BufferedImage flagImage(int nation) {
        FlagDesigner.Spec spec = flags.get(nation);
        return spec == null ? null : FlagDesigner.render(spec);
    }

    public RoadLineStyle getRoadLineStyle(int nationId) {
        return roadLineStyles.get(nationId);
    }

    public BuildingStyle getBuildingStyle(int nationId) {
        return buildingStyles.get(nationId);
    }

    /** A nation's road markings (see RoadLineStyle). */
    public RoadLineStyle roadLineStyle(int nationId) {
        return roadLineStyles.get(nationId);
    }

    BuildingStyle styleOf(House house) {
        return formStyles.get(house.nationId)[house.form];
    }

    /**
     * A nation's own random numbers for one purpose (salt). The seed is scrambled first: Java's
     * Random gives almost the same first number for seeds that differ by a little, which would
     * make every nation alike.
     */
    Random nationRandom(long salt, int nationId) {
        long h = worldSeed * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL + nationId * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return new Random(h);
    }

    /**
     * How well off a nation is, 0.1 to 0.9: the same for the whole world's life. Its towns are
     * richer than its countryside (see areaRichness).
     */
    public float nationRichness(int nationId) {
        return 0.1f + 0.8f * nationRandom(131L, nationId).nextFloat();
    }

    // How much poorer people right on the equator are (see SettlementManager.tropicality)
    private static final float TROPICAL_POVERTY = 0.18f;

    public float areaRichness(int nationId, float urbanness, SpherePosition place) {
        RoadNetworkBuilder islands = islandWealth;
        float island = islands == null ? Float.NaN : islands.islandRichnessAt(place);
        float base = (Float.isNaN(island) ? nationRichness(nationId) : island)
                * (1f - TROPICAL_POVERTY * SettlementManager.tropicality(place));
        // (big cities draw the money: the bigger the city, the richer its people)
        float big = smoothstep(BIG_CITY_FROM, BIG_CITY_TO, settlementManager.getCitySize(SphereMath.direction(place)));
        return Math.min(1f, base * (0.55f + 0.9f * urbanness) * (1f + BIG_CITY_WEALTH * big))
                * (1f - BORDER_POVERTY * borderBlight(place));
    }

    // How much of their wealth people right on a fenced border have lost, and how far (in
    // territory cells, NationGenerationManager.cellSize(), each a few hundred metres) the loss
    // reaches either side
    private static final float BORDER_POVERTY = 0.75f, BORDER_POVERTY_REACH = 5f;

    /** 1 right on a fenced border between nations at odds, fading to 0 BORDER_POVERTY_REACH cells away. */
    public float borderBlight(SpherePosition place) {
        float gap = borderGapAt(place);
        if (gap >= BORDER_POVERTY_REACH) return 0f;
        float t = 1f - gap / BORDER_POVERTY_REACH;
        return t * t * (3f - 2f * t);
    }

    /** The distance from a place to the nearest fenced border, in territory cells, blended between texels. */
    float borderGapAt(SpherePosition place) {
        return nationManager.raster().sample(borderGap, place.x(), place.y(), place.z());
    }

    /**
     * How far each texel of the nations' territory raster is from a border between nations
     * at odds, in territory cells along the ground (0 on such a border; no more than a little
     * past where it matters).
     */
    private float[] hostileBorderDistance() {
        SphereRaster raster = nationManager.raster();
        int[] territory = nationManager.territory();
        boolean[] edge = new boolean[territory.length];
        for (int t = 0; t < territory.length; t++) {
            for (int k = 0; k < 4 && !edge[t]; k++) edge[t] = kinship.hostile(territory[t], territory[raster.neighbour(t, k)]);
        }
        float[] angle = raster.distanceTo(edge);
        float[] gap = new float[angle.length];
        float perRadian = (float) (Planet.radius() / NationGenerationManager.cellSize());
        for (int t = 0; t < gap.length; t++) gap[t] = Math.min(angle[t] * perRadian, BORDER_POVERTY_REACH + 3f);
        return gap;
    }

    /** Which side of the road a nation drives on: 1 the right, -1 the left. */
    public float drivingSide(int nationId) {
        return nationRandom(71L, nationId).nextFloat() < 0.6f ? 1f : -1f;
    }

    /** How wide a nation's dirt tracks are: from footpaths to broad cart tracks. */
    private float trackWidth(int nationId) {
        return 14f + 6f * nationRandom(67L, nationId).nextFloat();
    }

    /** How gritty a nation's tracks are, from packed smooth earth (0) to stony (1). */
    float trackGrit(int nationId) {
        return nationRandom(61L, nationId).nextFloat();
    }

        /** Texture name for a nation's walls, roofs or fences ("wall", "roof" or "fence"). */
    /** This world's curtain fabrics: soft, fairly muted colours, a different set each world. */
    Vector3 curtainColour(int k) {
        Random rand = new Random(worldSeed * 43L + k * 7919L);
        float[] c = WorldPalette.hsv(rand.nextFloat(), 0.3f + 0.4f * rand.nextFloat(), 0.4f + 0.45f * rand.nextFloat());
        return new Vector3(c[0], c[1], c[2]);
    }

    public static String nationTextureName(String part, int nationId) {
        return part + "_nation" + nationId;
    }

        public static String wallVariantTextureName(int variant) {
        return "wall_variant" + variant;
    }

            static int roofKey(int nationId, int form) {
        return nationId * 64 + form;
    }

    /** Supplies the loaded nation textures; must be set before any cell is generated. */
    public void setNationTextures(java.util.function.Function<String, Texture> lookup) {
        this.nationTextures = lookup;
    }

    public Map<String, java.util.function.Supplier<java.awt.image.BufferedImage>> textureJobs() {
        Map<String, java.util.function.Supplier<java.awt.image.BufferedImage>> jobs = new LinkedHashMap<>();
        for (int k = 0; k < HouseBuilder.CURTAIN_COLOURS; k++) {
            Vector3 c = curtainColour(k);
            long curtainSeed = worldSeed * 47L + k;
            jobs.put(CURTAIN_TEXTURE + k, () -> ArchitectureTextures.curtains(curtainSeed, new float[] { c.x, c.y, c.z }));
        }
        for (Map.Entry<Integer, BuildingStyle> entry : buildingStyles.entrySet()) {
            int nation = entry.getKey();
            BuildingStyle style = entry.getValue();
            long nationSeed = worldSeed * 31L + nation;
            
            jobs.put(nationTextureName("roof", nation), () -> ArchitectureTextures.roof(nationSeed, style));
        }
                for (int v = 0; v < wallVariants.size(); v++) {
            BuildingStyle.WallVariant variant = wallVariants.get(v);
            long variantSeed = worldSeed * 41L + v;
            jobs.put(wallVariantTextureName(v), () -> ArchitectureTextures.wall(variantSeed, variant));
        }
        for (Map.Entry<Integer, FenceStyle> entry : fenceStyles.entrySet()) {
            int nation = entry.getKey();
            FenceStyle style = entry.getValue();
            jobs.put(nationTextureName("fence", nation), () -> ArchitectureTextures.fence(worldSeed * 37L + nation, style));
        }
        for (Map.Entry<Integer, FlagDesigner.Spec> entry : flags.entrySet()) {
            FlagDesigner.Spec spec = entry.getValue();
            int nation = entry.getKey();
            jobs.put(nationTextureName("flag", nation), () -> FlagDesigner.render(spec));
            jobs.put(nationTextureName("packaging", nation), () -> {
                java.awt.image.BufferedImage art = PackagingArt.render(worldSeed, nation, products,
                        scripts.glyphs(nation), scripts.direction(nation), FlagDesigner.render(spec), patriotismOf(nation));
                if (Dev.property("xenoguesser.dumpart") != null) {
                    try {
                        javax.imageio.ImageIO.write(art, "png", new java.io.File(WorldArtGenerator.OUTPUT_DIR, "packaging_" + nation + ".png"));
                    } catch (java.io.IOException ignored) {
                    }
                }
                return art;
            });
        }
        return jobs;
    }

    // ============================================================
    //  NETWORK PREPARATION
    // ============================================================

    public void prepareRoadNetwork(float seaLevelHeight, PerlinNoise noise) {
        if (roadNetworkInitialized) {
            return;
        }
        seaLevel = seaLevelHeight;
        terrainNoise = noise;

        RoadNetworkBuilder builder = new RoadNetworkBuilder(worldSeed, seaLevelHeight, noise,
                settlementManager, nationManager);
        builder.setNationRichness(this::nationRichness);
        // (dirt tracks don't cross fenced borders)
        builder.setHostile(kinship::hostile);
        islandWealth = builder;
        roadNetwork.addAll(builder.build());
        borderCrossings.straightenCrossings();
        borderCrossings.stopTracksAtFences();
        deadEnds.addAll(builder.getDeadEnds());

        for (int pathIndex = 0; pathIndex < roadNetwork.size(); pathIndex++) {
            RoadPath path = roadNetwork.get(pathIndex);
            List<SpherePosition> points = path.points;
            float width = path.roadClass.width;
            if (path.roadClass == RoadPath.RoadClass.DIRT && !points.isEmpty()) {
                width = trackWidth(nationManager.getNationAt(points.get(0)));
            }
            // Running distance along the path keeps dash patterns continuous across segments
            float distanceAlongPath = 0.0f;
            for (int i = 0; i < points.size() - 1; i++) {
                SpherePosition from = points.get(i), to = points.get(i + 1);
                float length = (float) SphereMath.distance(from, to);
                if (length < 0.01f) {
                    continue;
                }
                // Each segment in its own frame, at its middle (D4)
                SpherePosition middle = SphereMath.atAltitude(SphereMath.slerp(from, to, 0.5), 0.0);
                SphereFrame frame = SphereFrame.at(middle);
                Vector3 startTangent = roadTangent(frame, points.get(i == 0 ? i : i - 1), points.get(i + 1));
                Vector3 endTangent = roadTangent(frame, points.get(i), points.get(i + 1 == points.size() - 1 ? i + 1 : i + 2));
                int nationId = nationManager.getNationAt(middle);
                RoadSegment segment = new RoadSegment(frame, from, to, inFrame(frame, from), inFrame(frame, to),
                        startTangent, endTangent, nationId, pathIndex,
                        path.roadClass, distanceAlongPath, length, width, false);

                GuardRailStyle railStyle = guardRailStyles.get(nationId);
                segment.railed = path.roadClass == RoadPath.RoadClass.HIGHWAY && railStyle != null && railStyle.enabled
                        && settlementManager.getUrbanness(middle) < RAIL_MAX_URBANNESS;

                roadSegmentsByCell.computeIfAbsent(batchCell(middle), ignored -> new ArrayList<>()).add(segment);
                segmentIndex.add(middle, length * 0.5, segment);
                segmentsByPath.computeIfAbsent(pathIndex, ignored -> new ArrayList<>()).add(segment);
                distanceAlongPath += length;
            }
        }

        borderCrossings.findBorderChecks();
        planLandmarks();

        long startTime = System.currentTimeMillis();
        housePlacement.placeHouses();
        System.out.printf("[HOUSES] %d houses placed along the roads in %d ms%n", houses.size(), System.currentTimeMillis() - startTime);
        shopBuilder.chooseShops();
        // Fences go up once it's known which buildings are shops (they have none)
        housePlacement.connectNeighbourFences();
        // (the plots are final now, so each house is filed reaching its plot's far corner)
        for (House house : houses) {
            houseIndex.add(house.frame.origin(), plotReach(house), house);
            if (house.drivewaySide != 0 && house.shop == null) gardens.noteDriveway(house);
            if (house.shop != null) shopIndex.add(house.frame.origin(), house.shop);
        }
        // The road graph for walking and for maps, got ready in the background
        SIGN_MAP_ARTIST.submit(this::routes);
        for (List<RoadSegment> bucket : roadSegmentsByCell.values()) {
            for (RoadSegment segment : bucket) {
                // (within the map's top and bottom edges)
                boolean playable = Math.abs(Planet.latitude(segment.from)) < MapProjection.CLIP_LATITUDE;
                if (playable && segment.start.y > seaLevel + 0.5f && segment.end.y > seaLevel + 0.5f) {
                    landSegments.add(segment);
                }
            }
        }
        // Bucket order is not stable; sort so a seeded spawn pick is reproducible
        landSegments.sort((a, b) -> a.pathIndex != b.pathIndex ? Integer.compare(a.pathIndex, b.pathIndex)
                : Float.compare(a.startDistance, b.startDistance));
        // Bridges wherever the roads cross rivers and lakes
        bridges.findBridges();
        roadNetworkInitialized = true;
        // The utility poles and their wiring, worked out in the background (the first road built
        // waits for them should they not be ready)
        Thread poleWork = new Thread(roadFurniture::preparePoles, "utility-poles");
        poleWork.setDaemon(true);
        poleWork.start();
    }

    /** Plans the built landmarks beside the roads (see Landmarks.planBuilt), before any house is placed. */
    private void planLandmarks() {
        List<Landmarks.RoadSpot> spots = new ArrayList<>();
        List<RoadSegment> all = new ArrayList<>();
        for (List<RoadSegment> bucket : roadSegmentsByCell.values()) all.addAll(bucket);
        all.sort((a, b) -> a.pathIndex != b.pathIndex ? Integer.compare(a.pathIndex, b.pathIndex) : Float.compare(a.startDistance, b.startDistance));
        for (RoadSegment segment : all) {
            if (segment.culDeSac || segment.length < 20f) continue;
            SpherePosition middle = segment.frame.origin();
            double heading = SphereFrame.at(middle).headingTo(segment.to);
            spots.add(new Landmarks.RoadSpot(middle, heading, segment.width * 0.5f, segment.nationId, segment.roadClass == RoadPath.RoadClass.DIRT));
        }
        Landmarks.World world = new Landmarks.World() {
            public float ground(SpherePosition place) {
                return TerrainMesh.getLayeredHeight(place, terrainNoise);
            }

            public int nationAt(SpherePosition place) {
                return nationManager.getNationAt(place);
            }

            public float urbanness(SpherePosition place) {
                return settlementManager.getUrbanness(place);
            }

            public float richness(int nation, SpherePosition place) {
                return areaRichness(nation, settlementManager.getUrbanness(place), place);
            }

            public float nationRichness(int nation) {
                return InfrastructureManager.this.nationRichness(nation);
            }

            public boolean nearRoad(SpherePosition place, float clearance) {
                return isRoadLocation(place, clearance);
            }

            public ArchitectureStyle architecture(int nation, SpherePosition place) {
                int design = architectureWorld.at(nation, SphereMath.direction(place));
                return architectureWorld.style(Math.max(0, design));
            }

            public boolean freshWater(SpherePosition place, float ground) {
                Rivers rivers = terrainNoise.rivers;
                return rivers != null && !Float.isNaN(rivers.waterAt(SphereMath.direction(place), ground));
            }

            public Vector3 stone(int nation) {
                return infraMaterials.dressingStone(nation);
            }

            public Vector3 wall(int nation) {
                BuildingStyle style = buildingStyles.get(nation);
                return style != null ? style.wallColour : new Vector3(0.7f, 0.68f, 0.64f);
            }
        };
        landmarks.planBuilt(worldSeed, buildingStyles.size(), spots, world, seaLevel,
                place -> TerrainMesh.getLayeredHeight(place, terrainNoise), this::batchCell);
    }

    synchronized RoadRoutes routes() {
        if (routes == null) {
            long start = System.currentTimeMillis();
            routes = new RoadRoutes(this);
            System.out.printf("[ROADS] Route graph ready in %d ms%n", System.currentTimeMillis() - start);
        }
        return routes;
    }

    // ============================================================
    //  SPATIAL QUERIES
    // ============================================================
    // On the sphere (D5): each searches the CellIndex round the place, and every distance test
    // is done in the segment's or house's own frame, so it matches what is drawn.

    /**
     * Whether a place is on ground cleared for a road: within clearance of a made road's
     * edge, or on a dirt track itself (within trackMargin of its edge), so a track's bare
     * earth spans just the ground cleared for it; or on a driveway.
     */
    public boolean isClearedForRoad(SpherePosition place, float clearance, float trackMargin) {
        place = SphereMath.atAltitude(place, 0.0);
        if (gardens.onDriveway(place)) return true;
        for (RoadSegment segment : segmentsNear(place, clearance + MAX_ROAD_HALF_WIDTH)) {
            float limit = segment.width * 0.5f + (segment.roadClass == RoadPath.RoadClass.DIRT ? trackMargin : clearance);
            if (distanceSquaredToSegment(place, segment) <= limit * limit) return true;
        }
        return false;
    }

    /** Whether a place lies within clearance of the edge of any road or turning circle. */
    public boolean isRoadLocation(SpherePosition place, float clearance) {
        place = SphereMath.atAltitude(place, 0.0);
        for (RoadSegment segment : segmentsNear(place, clearance + MAX_ROAD_HALF_WIDTH)) {
            float limit = segment.width * 0.5f + clearance;
            if (distanceSquaredToSegment(place, segment) <= limit * limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * The altitude of what someone walking at a place stands on, with their feet at feet: a
     * road's surface (or a bridge's deck) only if it's within STEP_UP of the ground there or of
     * their feet, so they step up onto it, or their feet are already up at it (on it, or come
     * down onto it from a jump); otherwise the ground, under a bridge as well as beside a raised
     * road.
     */
    public float walkingSurfaceY(SpherePosition place, float feet) {
        float surface = walkingSurfaceY(place);
        place = SphereMath.atAltitude(place, 0.0);
        float ground = TerrainMesh.getLayeredHeight(place, terrainNoise);
        // (stepped up onto from where the feet are, if they're up on something: from one road
        // onto another meeting it a little higher, not only from the ground)
        if (surface - Math.max(ground, feet) <= STEP_UP) return surface;
        // (where one road passes over another, as a hairpin's legs, the highest within reach:
        // not the ground beneath the lower)
        float reached = Float.NaN;
        if (roadNetworkInitialized) {
            for (float y : roadGeometry.drawnRoadsY(place, 1.5f)) {
                if (y >= ground && y - Math.max(ground, feet) <= STEP_UP && (Float.isNaN(reached) || y > reached)) reached = y;
            }
        }
        if (!Float.isNaN(reached)) return reached;
        // (a bridge is open beneath, but solid through its depth: feet already up within it, as
        // after a stride or a landing that sank into a steep deck, come up onto it rather than
        // down through it to the ground inside)
        Bridges.Deck bridge = roadNetworkInitialized ? bridges.bridgeDeckAt(place, 0f) : null;
        if (bridge != null && feet > Bridges.bridgeUnderside(bridge)) return surface;
        return ground;
    }

    /**
     * The altitude of what someone walking at a place stands on: the road's raised surface on
     * a road (eased down to the ground over a pace or so beyond its edge), otherwise the ground.
     */
    public float walkingSurfaceY(SpherePosition place) {
        float surface = roadOrGroundY(place);
        // (up on a landmark's steps and platform)
        float floor = landmarks.floorAt(place);
        return Float.isNaN(floor) ? surface : Math.max(surface, floor);
    }

    /**
     * Whether a road or bridge stands in the way of someone (their feet at feet, their head at
     * head) at a place: a made road raised more than STEP_UP over the ground there and their feet (solid down to
     * the ground, its wall or bank) or a bridge's deck (solid through its depth, open beneath),
     * where their body's beside it rather than up on it.
     */
    public boolean blocksWalker(SpherePosition place, float feet, float head) {
        if (!roadNetworkInitialized) return false;
        place = SphereMath.atAltitude(place, 0.0);
        float ground = TerrainMesh.getLayeredHeight(place, terrainNoise);
        // (a landmark's platform, where it stands higher than a step over the ground and the feet)
        float floor = landmarks.floorAt(place);
        if (!Float.isNaN(floor) && floor - Math.max(ground, feet) > STEP_UP) return head > ground && feet < floor;
        float top, bottom;
        Bridges.Deck bridge = bridges.bridgeDeckAt(place, BODY_MARGIN);
        if (bridge != null) {
            top = bridge.top();
            bottom = Bridges.bridgeUnderside(bridge);
        } else {
            // (no wall where there's a road to stand on within reach: where one passes over
            // another, as a hairpin's legs, the lower is walked along; nor where none is drawn)
            top = Float.NaN;
            for (float y : roadGeometry.drawnRoadsY(place, BODY_MARGIN)) {
                if (y - Math.max(ground, feet) <= STEP_UP) return false;
                if (Float.isNaN(top) || y > top) top = y;
            }
            if (Float.isNaN(top)) return false;
            bottom = ground;
        }
        // (as in walkingSurfaceY, stepped up onto from the ground or from what the feet are on)
        if (top - Math.max(ground, feet) <= STEP_UP) return false;
        return head > bottom && feet < top;
    }

    // (a body this far from a road's edge meets it)
    private static final float BODY_MARGIN = 3f;

    // A road this little above the ground, or above what the feet are on, is stepped up onto; one
    // higher must be climbed (jumped) onto, the feet coming down on it from no lower than this
    // below its surface
    private static final float STEP_UP = 4f;

    /** The road's raised surface at a place, or the ground (see walkingSurfaceY). */
    private float roadOrGroundY(SpherePosition place) {
        place = SphereMath.atAltitude(place, 0.0);
        float ground = TerrainMesh.getLayeredHeight(place, terrainNoise);
        if (!roadNetworkInitialized) return ground;
        // (on a bridge, its deck, a track's planks as well as a made road's)
        Bridges.Deck bridge = bridges.bridgeDeckAt(place, 0f);
        if (bridge != null) return Math.max(ground, bridge.top());
        // (the road as it's drawn: level across, meeting others at junctions)
        final float easing = 1.5f;
        float road = Float.NaN, outside = 0f;
        for (float[] hit : roadGeometry.drawnRoads(place, easing)) {
            if (Float.isNaN(road) || hit[0] > road) {
                road = hit[0];
                outside = hit[1];
            }
        }
        if (Float.isNaN(road)) return ground;
        float t = 1f - outside / easing;
        return ground + (road - ground) * t;
    }

    /** Whether a place lies within clearance of any house (including its extension). */
    public boolean isBuildingLocation(SpherePosition place, float clearance) {
        if (!roadNetworkInitialized) {
            return false;
        }
        place = SphereMath.atAltitude(place, 0.0);
        // (a landmark's ground counts as built on: trees, rocks and the landing pod keep off it)
        if (landmarks.covers(place, clearance)) return true;
        for (House house : houseIndex.near(place, clearance)) {
            float[] local = local(house, place);
            if (local[0] >= house.houseMinX() - clearance && local[0] <= house.houseMaxX() + clearance
                    && local[1] >= -house.depth * 0.5f - clearance && local[1] <= house.depth * 0.5f + clearance) {
                return true;
            }
        }
        return false;
    }

    /**
     * How high the top of any house over a place is (its walls' top, rising towards the
     * middle under its roof); NaN if no house stands there.
     */
    public float roofAt(SpherePosition place) {
        float best = Float.NaN;
        place = SphereMath.atAltitude(place, 0.0);
        for (House house : houseIndex.near(place, 0.0)) {
            float[] local = local(house, place);
            float minX = house.houseMinX(), maxX = house.houseMaxX(), half = house.depth * 0.5f;
            if (local[0] < minX || local[0] > maxX || local[1] < -half || local[1] > half) continue;
            float halfWidth = (maxX - minX) * 0.5f, middle = (maxX + minX) * 0.5f;
            float inwards = 1f - Math.max(Math.abs(local[0] - middle) / Math.max(1f, halfWidth), Math.abs(local[1]) / Math.max(1f, half));
            float top = house.baseY + house.mainWallHeight + styleOf(house).roofHeight * house.sizeScale * Math.max(0f, inwards);
            if (Float.isNaN(best) || top > best) best = top;
        }
        return best;
    }

    /** Whether a place lies inside any house plot (garden and fence included). */
    boolean isPlotLocation(SpherePosition place, float clearance) {
        place = SphereMath.atAltitude(place, 0.0);
        for (House house : houseIndex.near(place, clearance)) {
            float[] local = local(house, place);
            if (local[0] >= house.plotMinX - clearance && local[0] <= house.plotMaxX + clearance
                    && local[1] >= house.plotMinZ - clearance && local[1] <= house.plotMaxZ + clearance) {
                return true;
            }
        }
        return false;
    }

    /** Whether a place is on (or within clearance of) someone's land: a house's plot, its garden, or its driveway. */
    public boolean isPrivateGround(SpherePosition place, float clearance) {
        return gardens.isPrivateGround(place, clearance);
    }

    /** The height of the deck of a bridge at a place (anywhere on it, as walked on), NaN where there's none. */
    public float bridgeDeckY(SpherePosition place) {
        return bridges.bridgeDeckY(place);
    }

    /** How far a house's land reaches from its middle: the far corner of its plot (or paddock). */
    private static float plotReach(House house) {
        float x = Math.max(-house.plotMinX, house.plotMaxX), z = Math.max(-house.plotMinZ, house.plotMaxZ);
        if (house.paddock != null) z = Math.max(z, -house.paddock[2]);
        // (a hair over, as the frame's flat plane and the ground part a little)
        return (float) Math.hypot(x, z) + 1f;
    }

    /** Road segments that may come within a true distance of a place (on the datum). */
    List<RoadSegment> segmentsNear(SpherePosition place, float distance) {
        return segmentIndex.near(place, distance);
    }

    // ============================================================
    //  ROADS, HOUSES AND ROUTES FOR THE PEOPLE AND TRAFFIC
    // ============================================================

    /** Visits every road polyline in the region, e.g. for drawing a road map. */
    public void forEachRoadPath(RoadPathVisitor visitor) {
        for (RoadPath path : roadNetwork) {
            visitor.visit(path);
        }
    }

    /** Visits (once each) every road polyline with a segment within a true distance of a place. */
    public void forEachRoadPathNear(SpherePosition place, float distance, RoadPathVisitor visitor) {
        java.util.BitSet seen = new java.util.BitSet(roadNetwork.size());
        for (RoadSegment segment : segmentsNear(SphereMath.atAltitude(place, 0.0), distance)) {
            if (seen.get(segment.pathIndex)) continue;
            seen.set(segment.pathIndex);
            visitor.visit(roadNetwork.get(segment.pathIndex));
        }
    }

    /** Visits every shop's building (without its extension). */
    public void forEachShop(BuildingVisitor visitor) {
        for (Shop shop : shops) {
            House house = shop.house;
            visitor.visit(house.frame, footprint(styleOf(house), house.width, house.depth, 0f, 0f), house.nationId);
        }
    }

    /** Visits every building footprint in the region, in its own shape (box, round or many-sided); extensions are visited as their own rectangle. */
    public void forEachBuilding(BuildingVisitor visitor) {
        for (House house : houses) visitBuilding(house, visitor);
    }

    /** Visits every building footprint whose plot comes within a true distance of a place. */
    public void forEachBuildingNear(SpherePosition place, float distance, BuildingVisitor visitor) {
        houseIndex.forEachNear(SphereMath.atAltitude(place, 0.0), distance, house -> visitBuilding(house, visitor));
    }

    private void visitBuilding(House house, BuildingVisitor visitor) {
        visitor.visit(house.frame, footprint(styleOf(house), house.width, house.depth, 0f, 0f), house.nationId);
        if (house.extensionSide != 0) {
            // (its own frame, the house's axes at the extension's middle)
            SphereFrame frame = house.frame.movedTo(SphereMath.atAltitude(world(house,
                    house.extensionSide * (house.width + house.extensionWidth) * 0.5f, 0f, (house.extensionDepth - house.depth) * 0.5f), 0.0));
            float hw = house.extensionWidth * 0.5f, hd = house.extensionDepth * 0.5f;
            visitor.visit(frame, new float[][] { { -hw, hd }, { hw, hd }, { hw, -hd }, { -hw, -hd } }, house.nationId);
        }
    }

    /** Every house's doorway whose middle is in a BATCH_LEVEL geodesic cell. */
    public List<Doorway> doorwaysInCell(long cell) {
        List<Doorway> result = new ArrayList<>();
        for (House house : housesByCell.getOrDefault(cell, List.of())) result.add(doorwayOf(house));
        return result;
    }

    /** Doorways of houses whose middle is within reach of a place. */
    public List<Doorway> doorwaysNear(SpherePosition place, float reach) {
        List<Doorway> result = new ArrayList<>();
        houseIndex.forEachNear(place, reach, house -> {
            if (SphereMath.distance(house.frame.origin(), place) <= reach) result.add(doorwayOf(house));
        });
        return result;
    }

    Doorway doorwayOf(House house) {
        if (house.doorway != null) return house.doorway;
        // Worked out in the house's frame
        float[] door = { 0f, house.depth * 0.5f + 2.5f };
        // The pavement: just off the road edge, between the carriageway and any front fence
        float[] kerb = { 0f, house.plotMaxZ };
        float[] roadDir = { 1f, 0f };
        float best = Float.MAX_VALUE;
        for (RoadSegment segment : segmentsNear(world(house, door[0], 0f, door[1]), house.setback + 60f)) {
            if (segment.pathIndex != house.frontagePath || segment.culDeSac) continue;
            float[] a = local(house, segment.from), b = local(house, segment.to);
            float[] nearest = nearestOnLine(door[0], door[1], a, b);
            float dx = door[0] - nearest[0], dz = door[1] - nearest[1];
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance < best && distance > 1e-3f) {
                best = distance;
                float reach = segment.width * 0.5f + 1.3f;
                kerb = new float[] { nearest[0] + dx / distance * reach, nearest[1] + dz / distance * reach };
                float sx = b[0] - a[0], sz = b[1] - a[1];
                float sl = (float) Math.sqrt(sx * sx + sz * sz);
                if (sl > 1e-3f) roadDir = new float[] { sx / sl, sz / sl };
            }
        }
        // The gate: on the line of the front fence, straight out from the door
        float[] gateLocal = gardens.followKerb(house, 0f);
        float gapMin = -GATE_HALF_WIDTH, gapMax = GATE_HALF_WIDTH;
        float[] localDriveway = null;
        if (house.drivewaySide != 0 && house.shop == null) {
            gapMin = Math.min(gapMin, house.driveMinX - 1f);
            gapMax = Math.max(gapMax, house.driveMaxX + 1f);
            float cx = (house.driveMinX + house.driveMaxX) * 0.5f;
            float frontZ = gardens.followKerb(house, cx)[1];
            localDriveway = new float[] { cx, frontZ, 0f, -1f, frontZ + house.depth * 0.5f,
                    house.driveMaxX - house.driveMinX, -house.drivewaySide, 0f };
        }
        // The opening measured along the road from the kerb point (the house's x runs along the road one way or the other)
        float sense = roadDir[0] >= 0f ? 1f : -1f;
        float a = (gapMin - kerb[0]) * sense, b = (gapMax - kerb[0]) * sense;
        float[] gap = { Math.min(a, b), Math.max(a, b) };
        house.doorway = new Doorway(house.frame, new float[] { 0f, house.depth * 0.5f - 5f }, door, kerb, roadDir,
                new float[] { 0f, gateLocal[1] }, localDriveway, house.nationId, house.frontagePath,
                settlementManager.getUrbanness(house.frame.origin()), house.seed, house.shop != null, gap);
        house.doorway.doorCorners = house.doorCorners;
        return house.doorway;
    }

    /** The door of the shop nearest a place, within reach; null if there's none. */
    public Doorway shopDoorNear(SpherePosition place, float reach, int nationId) {
        Shop shop = shopBuilder.nearestShop(place, reach, nationId);
        return shop == null ? null : doorwayOf(shop.house);
    }

    /**
     * A way on foot along a road from near one place to near another: points just inside the
     * road's edge on the first place's side, following the road's bends, so walkers keep to
     * the street rather than cutting across gardens. Empty if the road isn't known. Worked in
     * each segment's frame (their x, z run the same way along the path).
     */
    public List<SpherePosition> streetWalk(int path, SpherePosition a, SpherePosition b) {
        List<SpherePosition> points = new ArrayList<>();
        List<RoadSegment> all = segmentsByPath.get(path);
        if (all == null) return points;
        List<RoadSegment> segs = new ArrayList<>();
        for (RoadSegment segment : all) if (!segment.culDeSac) segs.add(segment);
        if (segs.isEmpty()) return points;
        a = SphereMath.atAltitude(a, 0.0);
        b = SphereMath.atAltitude(b, 0.0);
        int ia = nearestSegment(segs, a), ib = nearestSegment(segs, b);
        RoadSegment first = segs.get(ia);
        // Which side of the road the first place is
        double[] p = first.frame.fromSphere(a);
        float[] on = nearestOnLine((float) p[0], (float) p[2], new float[] { first.start.x, first.start.z }, new float[] { first.end.x, first.end.z });
        float dirX = first.end.x - first.start.x, dirZ = first.end.z - first.start.z;
        float side = dirX * ((float) p[2] - on[1]) - dirZ * ((float) p[0] - on[0]) >= 0f ? 1f : -1f;
        points.add(walkPoint(first, a, side));
        if (ia < ib) {
            for (int k = ia; k < ib; k++) points.add(walkPoint(segs.get(k), segs.get(k).to, side));
        } else {
            for (int k = ia; k > ib; k--) points.add(walkPoint(segs.get(k), segs.get(k).from, side));
        }
        points.add(walkPoint(segs.get(ib), b, side));
        return points;
    }

    private static int nearestSegment(List<RoadSegment> segs, SpherePosition place) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < segs.size(); i++) {
            float d = distanceSquaredToSegment(place, segs.get(i));
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** The point on a segment nearest a place, moved out to just inside the road edge on one side, on the datum. */
    private static SpherePosition walkPoint(RoadSegment segment, SpherePosition place, float side) {
        double[] p = segment.frame.fromSphere(place);
        float[] on = nearestOnLine((float) p[0], (float) p[2], new float[] { segment.start.x, segment.start.z },
                new float[] { segment.end.x, segment.end.z });
        float dx = segment.end.x - segment.start.x, dz = segment.end.z - segment.start.z;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-4f) return world(segment, on[0], on[1]);
        float offset = Math.max(1.5f, segment.width * 0.5f - 2.5f) * side;
        return world(segment, on[0] - dz / length * offset, on[1] + dx / length * offset);
    }

    /**
     * A way on foot from one place to another by the shortest way along the roads, keeping
     * to the side of the road as on a pavement, as places on the datum. Empty if there's none.
     */
    public List<SpherePosition> walkingRoute(SpherePosition a, SpherePosition b) {
        List<SpherePosition> way = routes().route(a, b);
        List<SpherePosition> kept = new ArrayList<>(way.size());
        for (int i = 0; i < way.size(); i++) {
            SpherePosition p = way.get(i);
            if (i == 0 || i == way.size() - 1) {
                kept.add(p);
                continue;
            }
            // On the pavement, just off the edge of the carriageway where the traffic runs
            SphereFrame frame = SphereFrame.at(p);
            double[] before = frame.fromSphere(way.get(i - 1)), after = frame.fromSphere(way.get(i + 1));
            double dx = after[0] - before[0], dz = after[2] - before[2];
            double length = Math.hypot(dx, dz);
            if (length < 1e-3) continue;
            double aside = roadAt(p)[0] + 1.3;
            kept.add(frame.toSphere(-dz / length * aside, 0.0, dx / length * aside));
        }
        return kept;
    }

    /**
     * The road at a place: {half its width, its rank (2 highway, 1 street, 0 lane or track),
     * the nation it runs through, 1 if a dirt track}, from the nearest road; a lane's if none is near.
     */
    public float[] roadAt(SpherePosition place) {
        RoadSegment best = null;
        float nearest = Float.MAX_VALUE;
        place = SphereMath.atAltitude(place, 0.0);
        for (RoadSegment segment : segmentsNear(place, MAX_ROAD_HALF_WIDTH + 20f)) {
            float d = distanceSquaredToSegment(place, segment);
            if (d < nearest) { nearest = d; best = segment; }
        }
        if (best == null) return new float[] { RoadPath.RoadClass.LANE.width * 0.5f, 0f, 1f, 0f };
        return new float[] { best.width * 0.5f, best.rank(), best.nationId, best.roadClass == RoadPath.RoadClass.DIRT ? 1f : 0f };
    }

    /** The shortest way along the roads between two places, along the roads' middles, as places on the datum. */
    public List<SpherePosition> drivingRoute(SpherePosition a, SpherePosition b) {
        return routes().route(a, b);
    }

    /**
     * Somewhere for a drive out: the middle of another settlement, between minDistance and
     * maxDistance away; null if there's none.
     */
    public SpherePosition settlementBetween(SpherePosition place, float minDistance, float maxDistance, Random rand) {
        List<SettlementManager.Settlement> fit = new ArrayList<>();
        for (SettlementManager.Settlement s : settlementManager.getSettlements()) {
            double d = SphereMath.distance(place, s.centre);
            if (d >= minDistance && d <= maxDistance) fit.add(s);
        }
        if (fit.isEmpty()) return null;
        return SphereMath.atAltitude(fit.get(rand.nextInt(fit.size())).centre, 0.0);
    }

    /**
     * A place to leave a vehicle width wide at the edge of the road nearest a place, on the side
     * towards another: pulled right over, partly up on the verge on a narrow road, so it never
     * stands in the way. Null if that's at a junction (or no road is near). Worked in the road
     * segment's frame.
     */
    public Kerb kerbSpot(SpherePosition place, SpherePosition towards, float width) {
        RoadSegment best = null;
        float nearest = Float.MAX_VALUE;
        place = SphereMath.atAltitude(place, 0.0);
        for (RoadSegment segment : segmentsNear(place, MAX_ROAD_HALF_WIDTH + 30f)) {
            if (segment.culDeSac) continue;
            float d = distanceSquaredToSegment(place, segment);
            if (d < nearest) { nearest = d; best = segment; }
        }
        if (best == null) return null;
        double[] p = best.frame.fromSphere(place), t = best.frame.fromSphere(towards);
        float[] centre = nearestOnLine((float) p[0], (float) p[2], new float[] { best.start.x, best.start.z },
                new float[] { best.end.x, best.end.z });
        float ax = best.end.x - best.start.x, az = best.end.z - best.start.z, length = (float) Math.hypot(ax, az);
        if (length < 1e-3f) return null;
        ax /= length;
        az /= length;
        float nx = -az, nz = ax;
        if (nx * ((float) t[0] - centre[0]) + nz * ((float) t[2] - centre[1]) < 0f) { nx = -nx; nz = -nz; }
        float half = best.width * 0.5f;
        // Up on the verge a little where there's one, but inside the crash barrier where there's that
        float out = best.railed || best.roadClass == RoadPath.RoadClass.HIGHWAY
                ? Math.max(half * 0.32f, half - width * 0.5f - 2f)
                : Math.max(half * 0.32f, half + 2.5f - width * 0.5f);
        SpherePosition spot = world(best, centre[0] + nx * out, centre[1] + nz * out);
        // Not across the mouth of another road
        for (RoadSegment other : segmentsNear(spot, MAX_ROAD_HALF_WIDTH + width + 10f)) {
            if (other.pathIndex == best.pathIndex || other.culDeSac) continue;
            float reach = other.width * 0.5f + width * 0.5f + 6f;
            if (distanceSquaredToSegment(spot, other) < reach * reach) return null;
        }
        SpherePosition middle = best.frame.origin();
        return new Kerb(spot, SphereMath.carryHeading(middle, spot, Math.atan2(az, ax)),
                SphereMath.carryHeading(middle, spot, Math.atan2(nz, nx)), half, best.nationId);
    }

    /**
     * A place on a road's middle between minDistance and maxDistance from another, likelier on
     * bigger roads (highways most of all, dirt tracks hardly ever), on the datum; null if none.
     */
    public SpherePosition roadPointBetween(SpherePosition place, float minDistance, float maxDistance, Random rand) {
        List<RoadSegment> fit = new ArrayList<>();
        List<Float> weights = new ArrayList<>();
        float total = 0f;
        place = SphereMath.atAltitude(place, 0.0);
        for (RoadSegment segment : segmentsNear(place, maxDistance)) {
            if (segment.culDeSac) continue;
            float d = (float) SphereMath.distance(segment.frame.origin(), place);
            if (d < minDistance || d > maxDistance) continue;
            float w = segment.roadClass == RoadPath.RoadClass.HIGHWAY ? 8f : segment.roadClass == RoadPath.RoadClass.STREET ? 2f
                    : segment.roadClass == RoadPath.RoadClass.DIRT ? 0.2f : 1f;
            fit.add(segment);
            weights.add(w);
            total += w;
        }
        if (fit.isEmpty()) return null;
        float roll = rand.nextFloat() * total;
        for (int i = 0; i < fit.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0f || i == fit.size() - 1) {
                RoadSegment s = fit.get(i);
                return SphereMath.atAltitude(SphereMath.slerp(s.from, s.to, rand.nextFloat()), 0.0);
            }
        }
        return null;
    }

    // ============================================================
    //  LANDING SITES
    // ============================================================

    // Kept free of signs and flagpoles: where the landing pod stands this round, and how far round
    private record KeepClear(SpherePosition centre, double radius) {
    }

    private volatile KeepClear keepClear;

    /** Nothing new (signs, flagpoles) is put within radius of a place; for the landing pod. */
    public void setKeepClear(SpherePosition centre, double radius) {
        keepClear = new KeepClear(SphereMath.atAltitude(centre, 0.0), radius);
    }

    private boolean inKeepClear(SpherePosition place) {
        KeepClear k = keepClear;
        return k != null && SphereMath.distance(k.centre(), place) < k.radius();
    }

    /**
     * Somewhere for the landing pod to have come down: near a road (on it now and then, but
     * usually a little way off to one side), clear of every house, garden and fence, on dry
     * land. Returns where the player stands on its stairs and the way the stairs face,
     * towards the road; null if nowhere suitable turns up. Laid out in the segment's frame.
     *
     * @param standsAt   distance from the pod's middle to where the player stands
     * @param reach      how far the pod and its stairs reach from its middle
     * @param dryLand    whether a point is above the sea
     */
    public Viewpoint landingSite(Random rand, float standsAt, float reach, java.util.function.Predicate<SpherePosition> dryLand) {
        return landingSite(rand, standsAt, reach, dryLand, MapKind.BALANCED);
    }

    /**
     * Where the pod comes down for a kind of map (see MapKind): as landingSite, beside a road,
     * but in the country by a dirt track far from towns and houses (up in the hills where it can
     * be), or in town with houses about; anywhere along the roads if nowhere like that turns up.
     */
    public Viewpoint landingSite(Random rand, float standsAt, float reach, java.util.function.Predicate<SpherePosition> dryLand, MapKind kind) {
        if (landSegments.isEmpty()) return null;
        if (kind != MapKind.BALANCED) {
            Viewpoint site = landingSite(rand, standsAt, reach, dryLand, kind, PARTICULAR_SITE_ATTEMPTS);
            if (site != null) return site;
        }
        return landingSite(rand, standsAt, reach, dryLand, MapKind.BALANCED, 400);
    }

    private Viewpoint landingSite(Random rand, float standsAt, float reach, java.util.function.Predicate<SpherePosition> dryLand,
                                  MapKind kind, int attempts) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            RoadSegment segment = landSegments.get(rand.nextInt(landSegments.size()));
            if (segment.railed) continue;   // not over a guard rail
            if (!suits(segment, kind, rand)) continue;
            float t = rand.nextFloat();
            float dx = segment.end.x - segment.start.x, dz = segment.end.z - segment.start.z;
            float length = (float) Math.hypot(dx, dz);
            if (length < 1e-3f) continue;
            float rx = segment.start.x + dx * t, rz = segment.start.z + dz * t;
            float alongX = dx / length, alongZ = dz / length;
            float side = rand.nextBoolean() ? 1f : -1f;
            float acrossX = -alongZ * side, acrossZ = alongX * side;
            // Off to one side (never on the road, where it would be in the traffic's way), stairs towards it
            float off = segment.width * 0.5f + reach + 4f + rand.nextFloat() * 45f;
            float cx = rx + acrossX * off, cz = rz + acrossZ * off;
            float dirX = -acrossX, dirZ = -acrossZ;
            SpherePosition centre = world(segment, cx, cz);
            SpherePosition stands = world(segment, cx + dirX * standsAt, cz + dirZ * standsAt);
            SpherePosition foot = world(segment, cx + dirX * reach, cz + dirZ * reach);
            // (never on anyone's land: their plot, garden or driveway)
            if (isPrivateGround(centre, reach) || isPrivateGround(stands, 8f) || isPrivateGround(foot, 8f)) continue;
            if (!dryLand.test(centre) || !dryLand.test(stands) || !dryLand.test(foot)) continue;
            boolean feetDry = true;
            for (int leg = 0; leg < 4 && feetDry; leg++) {
                double a = Math.PI * 0.25 + leg * Math.PI * 0.5;
                feetDry = dryLand.test(world(segment, cx + (float) Math.cos(a) * 36f, cz + (float) Math.sin(a) * 36f));
            }
            if (!feetDry) continue;
            // Clear of every road, legs and all
            boolean offRoad = !isRoadLocation(centre, 30f);
            for (int leg = 0; leg < 4 && offRoad; leg++) {
                double a = Math.PI * 0.25 + leg * Math.PI * 0.5;
                offRoad = !isRoadLocation(world(segment, cx + (float) Math.cos(a) * 36f, cz + (float) Math.sin(a) * 36f), 6f);
            }
            if (!offRoad) continue;
            // (in the country far from any house; in town with one near)
            if (kind == MapKind.RURAL && isBuildingLocation(centre, RURAL_HOUSE_CLEARANCE)) continue;
            if (kind == MapKind.URBAN && !isBuildingLocation(centre, URBAN_HOUSE_NEAR)) continue;
            return viewpoint(segment.frame, cx + dirX * standsAt, cz + dirZ * standsAt, dirX, dirZ);
        }
        return null;
    }

    // Out in the country (MapKind.RURAL): no more urban than this, nor a house this near; a made
    // road taken only this often (dirt tracks always), and higher ground preferred, from this
    // often at the sea's level to always this high above it. In town (URBAN): at least this
    // urban, with a house this near. Either looked for this many times before settling for anywhere
    private static final float RURAL_MOST_URBANNESS = 0.05f, RURAL_HOUSE_CLEARANCE = 600f, RURAL_MADE_ROAD_CHANCE = 0.15f;

    private static final float RURAL_LOWLAND_CHANCE = 0.3f, RURAL_HIGHLAND = 400f;

    private static final float URBAN_LEAST_URBANNESS = 0.25f, URBAN_HOUSE_NEAR = 120f;

    private static final int PARTICULAR_SITE_ATTEMPTS = 4000;

    /** Whether a road segment suits a kind of map's landing sites (see MapKind), drawing on rand for those that only sometimes do. */
    private boolean suits(RoadSegment segment, MapKind kind, Random rand) {
        SpherePosition middle = segment.frame.origin();
        float urbanness = settlementManager.getUrbanness(middle);
        if (kind == MapKind.RURAL) {
            if (urbanness > RURAL_MOST_URBANNESS) return false;
            if (segment.roadClass != RoadPath.RoadClass.DIRT && rand.nextFloat() > RURAL_MADE_ROAD_CHANCE) return false;
            float high = Math.max(0f, Math.min(1f, (TerrainMesh.getLayeredHeight(middle, terrainNoise) - seaLevel) / RURAL_HIGHLAND));
            return rand.nextFloat() <= RURAL_LOWLAND_CHANCE + (1f - RURAL_LOWLAND_CHANCE) * high;
        }
        if (kind == MapKind.URBAN) return urbanness >= URBAN_LEAST_URBANNESS;
        return true;
    }

    /** A random point on a road over dry land, looking along it, for spawning the player; null if the world has no roads. */
    public Viewpoint randomRoadPoint(Random rand) {
        if (landSegments.isEmpty()) {
            return null;
        }
        RoadSegment segment = landSegments.get(rand.nextInt(landSegments.size()));
        float t = rand.nextFloat();
        float dx = segment.end.x - segment.start.x, dz = segment.end.z - segment.start.z;
        if (rand.nextBoolean()) {
            dx = -dx;
            dz = -dz;
        }
        return viewpoint(segment.frame, segment.start.x + (segment.end.x - segment.start.x) * t,
                segment.start.z + (segment.end.z - segment.start.z) * t, dx, dz);
    }

    // ============================================================
    //  CELL GENERATION
    // ============================================================

    /** The batch cell (a geodesic cell at BATCH_LEVEL) a place is in. */
    public long batchCell(SpherePosition place) {
        return batchGrid.cellAt(place).id();
    }

    /**
     * Builds what stands in one geodesic cell at BATCH_LEVEL (the houses, road segments, booths
     * and crossings whose middles are in it, and the border fence traced through it): a batch
     * per nation in world coordinates, and the signs.
     */
    public List<InfrastructureObject> generateForCell(long cell, float seaLevelHeight, PerlinNoise noise) {
        List<InfrastructureObject> objects = new ArrayList<>();
        prepareRoadNetwork(seaLevelHeight, noise);

        Random signRand = new Random(cell * 8912L + 4123L);
        List<SpherePosition> signPositions = new ArrayList<>();
        Map<Integer, NationBatch> batches = new HashMap<>();
        recording = new ArrayList<>();

        List<House> cellHouses = housesByCell.getOrDefault(cell, List.of());
        if (!cellHouses.isEmpty()) {
            for (House house : cellHouses) {
                NationBatch batch = batches.computeIfAbsent(house.nationId, nation -> new NationBatch(this, nation));
                // Built in the house's own frame (D3)
                batch.place(inWorld(house));
                houseBuilder.bakeHouse(batch, house, objects);
                if (house.fenced) {
                    gardens.bakeFence(batch, house);
                }
                // In town, a tall flagpole at the front corner of some plots
                float urban = settlementManager.getUrbanness(house.frame.origin());
                Random poleRand = new Random(house.seed ^ 0x9013L);
                if (urban > 0.25f && poleRand.nextFloat() < patriotismOf(house.nationId) * 0.3f * urban) {
                    float side = house.drivewaySide != 0 ? -house.drivewaySide : poleRand.nextBoolean() ? 1f : -1f;
                    float px = side > 0 ? house.plotMaxX - 4f : house.plotMinX + 4f, pz = house.plotMaxZ - 4f;
                    SpherePosition at = world(house, px, 0f, pz);
                    if (!inKeepClear(at) && !isRoadLocation(at, 2f)) {
                        houseBuilder.flagpole(batch, house, px, pz, 40f + poleRand.nextFloat() * 14f);
                    }
                }
                batch.place(null);
                if (signRand.nextDouble() < buildingStyles.get(house.nationId).signChance) {
                    InfrastructureObject sign = roadsideSigns.createHouseSign(house, signRand);
                    if (sign != null && (inKeepClear(sign.frame.origin()) || borderCrossings.nearBorderCheck(sign))) sign = null;
                    if (sign != null) {
                        objects.add(sign);
                        recordSignPosts(sign);
                        signPositions.add(sign.frame.origin());
                    }
                }
            }
        }

        for (Landmark landmark : landmarks.inCell(cell)) {
            bakeLandmark(batches.computeIfAbsent(landmark.nation, nation -> new NationBatch(this, nation)), landmark);
        }

        borderCrossings.borderFences(batches, cell);
        borderCrossings.borderBooths(batches, cell);
        borderCrossings.crossoverLines(batches, cell);

        List<RoadSegment> segments = roadSegmentsByCell.getOrDefault(cell, List.of());
        if (!segments.isEmpty()) {
            for (RoadSegment segment : segments) {
                NationBatch batch = batches.computeIfAbsent(segment.nationId, nation -> new NationBatch(this, nation));
                // Built in the segment's own frame (D3)
                batch.place(inWorld(segment));
                if (segment.culDeSac) {
                    roadGeometry.appendCulDeSac(batch, segment);
                    batch.place(null);
                    continue;
                }
                roadGeometry.appendRoadSegment(batch, segment);
                batch.place(null);
                InfrastructureObject sign = roadsideSigns.tryCreateRoadsideSign(segment, signRand, signPositions);
                if (sign != null && (inKeepClear(sign.frame.origin()) || borderCrossings.nearBorderCheck(sign))) sign = null;
                if (sign != null) {
                    objects.add(sign);
                    recordSignPosts(sign);
                    signPositions.add(sign.frame.origin());
                }
            }
        }

        for (NationBatch batch : batches.values()) {
            InfrastructureObject object = batch.build();
            if (object != null) {
                objects.add(object);
            }
        }
        // (a cell built again, after its meshes were dropped, has its shapes filed already)
        if (cellsRecorded.add(cell)) {
            for (Obstacle obstacle : recording) {
                if (obstacle.b() == null) {
                    obstacleIndex.add(obstacle.a(), obstacle.size(), obstacle);
                } else {
                    obstacleIndex.add(SphereMath.slerp(obstacle.a(), obstacle.b(), 0.5),
                            SphereMath.distance(obstacle.a(), obstacle.b()) * 0.5 + obstacle.size(), obstacle);
                }
            }
        }
        recording = null;
        return objects;
    }

    void recordSegment(SpherePosition a, SpherePosition b, float halfWidth) {
        recordSegment(a, b, halfWidth, Float.POSITIVE_INFINITY);
    }

    /** A solid line along the ground between two places, standing top high (for jumping over). */
    void recordSegment(SpherePosition a, SpherePosition b, float halfWidth, float top) {
        if (recording != null) recording.add(new Obstacle(SphereMath.atAltitude(a, 0.0), SphereMath.atAltitude(b, 0.0), halfWidth, top));
    }

    /**
     * A solid line held up off the ground between two places (a bridge's railing): from bottom up
     * to top, both over the ground there; passed under by one whose head is below its bottom.
     */
    void recordRaised(SpherePosition a, SpherePosition b, float halfWidth, float top, float bottom) {
        if (recording != null) recording.add(new Obstacle(SphereMath.atAltitude(a, 0.0), SphereMath.atAltitude(b, 0.0), halfWidth, top, bottom));
    }

    /** A sign stands on two posts either side of its board, which is up out of the way. */
    private void recordSignPosts(InfrastructureObject sign) {
        if (sign.wallMounted) return;
        for (float side : new float[] { -InfrastructureObject.SIGN_POST_SPACING, InfrastructureObject.SIGN_POST_SPACING }) {
            recordCircle(sign.frame.toWorld(side, 0.0, 0.0), 0.9f);
        }
    }

    void recordCircle(SpherePosition centre, float radius) {
        if (recording != null) recording.add(new Obstacle(SphereMath.atAltitude(centre, 0.0), null, radius, Float.POSITIVE_INFINITY));
    }

    /** Walls, fences, guard rails and sign posts reaching within reach of a place, from the cells built so far. */
    public void obstaclesNear(SpherePosition centre, double reach, GeodesicCollision.Sink sink) {
        obstacleIndex.forEachNear(SphereMath.atAltitude(centre, 0.0), reach, obstacle -> {
            if (obstacle.b() == null) sink.circle(obstacle.a(), obstacle.size(), obstacle.top());
            else sink.segment(obstacle.a(), obstacle.b(), obstacle.size(), obstacle.top(), obstacle.bottom());
        });
    }

    /** The world's landmarks: the built ones (planned), and the natural ones once found. */
    public Landmarks landmarks() {
        return landmarks;
    }

    /**
     * A landmark into its nation's batch in its cell: its faces in its own frame (raised to its
     * base), in its colours, and what's solid of it filed for walking into.
     */
    private void bakeLandmark(NationBatch batch, Landmark landmark) {
        int which = landmarks.indexOf(landmark);
        batch.place(inWorld(landmark.frame, false));
        Landmark.Geometry geometry = landmark.geometry;
        float base = landmark.base;
        for (int i = 0; i < geometry.faces.size(); i++) {
            float[][] corners = geometry.faces.get(i);
            float[] n = geometry.normals.get(i);
            MeshBuilder builder = batch.builder(LANDMARK_PART + which + ":" + geometry.slots.get(i));
            int first = -1;
            for (float[] c : corners) {
                int index = builder.addVertex(c[0], c[1] + base, c[2], n[0], n[1], n[2], 0f, 0f);
                if (first < 0) first = index;
            }
            for (int k = 1; k < corners.length - 1; k++) builder.addTriangle(first, first + k, first + k + 1);
        }
        float wide = Math.max(landmark.reach, landmark.halfDepth);
        batch.include(-wide, base - landmark.drop, -wide, 0f);
        batch.include(wide, base + landmark.tallest(), wide, 0f);
        batch.place(null);
        for (float[] c : landmark.circles) recordCircle(landmark.frame.toSphere(c[0], 0.0, c[1]), c[2]);
        for (float[] w : landmark.walls) {
            recordSegment(landmark.frame.toSphere(w[0], 0.0, w[1]), landmark.frame.toSphere(w[2], 0.0, w[3]), w[4], w[5]);
        }
    }

    // ============================================================
    //  MAP PICTURES
    // ============================================================

    /**
     * A nation's traditional house dressed in one of the world's architectures (an index into
     * ArchitectureStyle.World.style), height pixels tall, for the map; null if it has no houses.
     */
    public java.awt.image.BufferedImage architecturePicture(int nationId, int design, int height) {
        BuildingStyle[] forms = formStyles.get(nationId);
        if (forms == null) return null;
        ArchitectureStyle arch = architectureWorld.style(design);
        BuildingStyle.WallVariant walls = wallVariants.get(typicalWall(nationId, arch.pattern));
        return arch.picture(height, forms[0], walls);
    }

    /**
     * A nation's houses in one of the world's architectures, the most typical first (to be
     * photographed for the architecture map: see LandmarkPhotos): of its commonest form, walls
     * and weathering, neither grand nor a shop, then those nearest the middling size and wealth.
     * Empty should it build none in it.
     */
    List<House> typicalHouses(int nationId, int design) {
        List<House> built = new ArrayList<>();
        for (House house : houses) if (house.nationId == nationId && house.architecture == design && house.shop == null) built.add(house);
        if (built.isEmpty()) return built;
        int form = commonest(built, h -> h.form), walls = commonest(built, h -> h.wallVariant), weathering = commonest(built, h -> h.weathering);
        float size = middling(built, h -> h.sizeScale), wealth = middling(built, h -> h.richness);
        built.sort(java.util.Comparator.comparingDouble(h -> (h.form != form ? 8 : 0) + (h.wallVariant != walls ? 4 : 0) + (h.grand ? 2 : 0)
                + (h.weathering != weathering ? 1 : 0) + Math.abs(h.sizeScale - size) * 4 + Math.abs(h.richness - wealth)));
        return built;
    }

    /** The commonest of something about houses (the lowest, should two be as common). */
    private static int commonest(List<House> houses, java.util.function.ToIntFunction<House> of) {
        java.util.TreeMap<Integer, Integer> counts = new java.util.TreeMap<>();
        for (House house : houses) counts.merge(of.applyAsInt(house), 1, Integer::sum);
        int best = counts.firstKey();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) if (e.getValue() > counts.get(best)) best = e.getKey();
        return best;
    }

    /** The median of something about houses. */
    private static float middling(List<House> houses, java.util.function.ToDoubleFunction<House> of) {
        double[] values = houses.stream().mapToDouble(of).sorted().toArray();
        return (float) values[values.length / 2];
    }

    /** A nation's commonest wall variety in a masonry pattern (any, should it build none in it). */
    private int typicalWall(int nationId, BuildingStyle.Pattern pattern) {
        float[] weights = buildingStyles.get(nationId).wallVariantWeights;
        int best = 0, bestOfPattern = -1;
        for (int v = 0; v < weights.length; v++) {
            if (weights[v] > weights[best] && wallVariants.get(v).pattern == BuildingStyle.Pattern.COURSED) best = v;
            if (wallVariants.get(v).pattern == pattern && (bestOfPattern < 0 || weights[v] > weights[bestOfPattern])) bestOfPattern = v;
        }
        return bestOfPattern >= 0 ? bestOfPattern : best;
    }
// ==========================================
    //          GEOMETRY HELPERS
    // ==========================================

}
