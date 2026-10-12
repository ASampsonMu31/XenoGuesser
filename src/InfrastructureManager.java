import java.util.ArrayList;
import java.util.Collections;
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
 * roads; everything is then baked per chunk into a handful of merged meshes.
 */
public class InfrastructureManager {
    private static final float ROAD_SURFACE_OFFSET = 0.6f;
    private static final int ROAD_SEGMENT_SUBDIVISIONS = 8;
    private static final int LINE_SAMPLES_PER_SEGMENT = 24;
    private static final float ROAD_LINE_SURFACE_OFFSET = 0.35f;
    private static final float MAX_ROAD_HALF_WIDTH = RoadPath.RoadClass.HIGHWAY.width * 0.5f;
    // Resampled paths space their points up to about 1.5 road steps apart
    private static final float MAX_ROAD_SEGMENT_HALF_LENGTH = 60.0f;

    // Wide roads get dashed lane dividers between the nation's own markings
    private static final float LANE_DIVIDER_MIN_WIDTH = 40.0f;
    private static final float LANE_DIVIDER_DASH = 12.0f;
    private static final float LANE_DIVIDER_GAP = 18.0f;
    private static final float GIVE_WAY_LINE_LENGTH = 2.4f;

    // Guard rails line rural highways in nations that build them
    private static final float RAIL_MAX_URBANNESS = 0.2f;
    private static final float RAIL_OFFSET = 3.0f;
    // How far past the edge of a road joining a guard-railed one its rail stays open, so
    // vehicles turning in or out have room to swing round
    private static final float RAIL_JUNCTION_CLEARANCE = 30.0f;

    // Roadside signs roll once per road segment rather than per attempt, so scale the nation's sign chance up
    private static final double ROADSIDE_SIGN_CHANCE_SCALE = 40.0;
    // Busy streets carry far more signage than country roads
    private static final double URBAN_SIGN_BOOST = 3.0;
    private static final float ROADSIDE_SIGN_MARGIN = 8.0f;
    private static final float SIGN_SPACING = 36.0f;

    // Houses per 100 units of road per side: a rural trickle per nation, rising steeply with urbanness
    private static final double RURAL_FRONTAGE_SCALE = 200.0;
    private static final float URBAN_FRONTAGE_MIN = 1.0f;
    private static final float URBAN_FRONTAGE_RANGE = 0.6f;
    private static final float URBAN_DENSITY_EXPONENT = 1.3f;
    // More placement attempts than wanted houses, since some land on slopes, roads or neighbours
    private static final float PLACEMENT_OVERSAMPLE = 2.5f;

    // Town houses sit right at the kerb; out in the country they stand back behind a front garden
    private static final float KERB_SETBACK_MIN = 10.0f;
    private static final float KERB_SETBACK_RANGE = 6.0f;
    private static final float MAX_FRONT_GARDEN = 70.0f;
    private static final float FRONT_FENCE_GAP = 3.0f;
    private static final float PLOT_GAP = 2.0f;
    private static final float PLOT_ROAD_MARGIN = 2.0f;
    private static final float PLOT_HIGHWAY_MARGIN = 7.0f;
    private static final float GATE_HALF_WIDTH = 7.0f;
    // A driveway beside the house: how wide, and how far it keeps from the walls
    private static final float DRIVEWAY_WIDTH = 22.0f;
    private static final float DRIVEWAY_LIFT = 0.5f;
    // Neighbouring fenced plots closer than this join up, sharing one fence between them
    private static final float FENCE_JOIN_GAP = 26.0f;
    // Fence outlines: the plain plot, an irregular outline, the front boundary only
    private static final int FENCE_PLOT = 0, FENCE_IRREGULAR = 1, FENCE_FRONT_ONLY = 2;
    private static final float BUILDING_MAX_GROUND_DROP = 25.0f;
    private static final float BUILDING_FOUNDATION_DEPTH = 3.0f;
        private static final float DOOR_OFFSET = 0.5f;
    private static final float FRAME_OFFSET = 0.2f;

    private static final Material ASPHALT = new Material(
        new Vector3(0.18f, 0.18f, 0.18f),
        new Vector3(0.28f, 0.28f, 0.28f),
        new Vector3(0.01f, 0.01f, 0.01f),
        1.0f
    );

    private final Map<Integer, Double> nationSignProbabilities;
    private final Map<Integer, Double> nationBuildingProbabilities;
    private final Map<Integer, Float> nationUrbanDensities;
    private final Map<Integer, RoadLineStyle> roadLineStyles;
        private final Map<Integer, BuildingStyle> buildingStyles;
    // Each nation's house in every form: index 0 is its own tradition, then the world's forms
    private final Map<Integer, BuildingStyle[]> formStyles = new HashMap<>();
    private List<BuildingStyle.FormVariant> formVariants;
    private final Map<Integer, GuardRailStyle> guardRailStyles;
    private final Map<Integer, FenceStyle> fenceStyles;
    private final Map<String, Material> materials = new HashMap<>();
    private final NationGenerationManager nationManager;
    // Which nations are at odds (see NationKinship.hostile), and how far each cell of the
    // nation map is from a border between two that are, in cells (see hostileBorderDistance)
    private final NationKinship kinship;
    private final float[] borderGap;
    // Islands the main roads never reached have a wealth of their own (see RoadNetworkBuilder.buildIslandTracks)
    private volatile RoadNetworkBuilder islandWealth;
    private final SettlementManager settlementManager;
    private final XenoGuesser_GLEventListener mainListener;
    private final long worldSeed;
    private final List<RoadPath> roadNetwork = new ArrayList<>();
    private final List<RoadPath.DeadEnd> deadEnds = new ArrayList<>();
    private final Map<Integer, List<RoadSegment>> segmentsByPath = new HashMap<>();
    private final List<RoadSegment> landSegments = new ArrayList<>();
    // Chart bridge (D13): which chart chunk builds each segment and house, until the batches are
    // built per geodesic cell; every lookup by place goes through the CellIndexes below
    private final Map<Long, List<RoadSegment>> roadSegmentsByChunk = new HashMap<>();
    private final List<House> houses = new ArrayList<>();
    // Lookups by place on the sphere (D5): segments filed at their middle reaching half their
    // length; houses at their middle reaching the far corner of their plot (once the plots are
    // final); driveways and shops at their house's middle
    private static final int LOOKUP_LEVEL = 7;
    private final CellIndex<RoadSegment> segmentIndex = new CellIndex<>(LOOKUP_LEVEL);
    private final CellIndex<House> houseIndex = new CellIndex<>(LOOKUP_LEVEL);
    private final CellIndex<House> drivewayIndex = new CellIndex<>(LOOKUP_LEVEL);
    private final CellIndex<Shop> shopIndex = new CellIndex<>(LOOKUP_LEVEL);
    private final Map<Integer, FlagDesigner.Spec> flags;

    /** A nation's flag as a picture (FlagDesigner.WIDTH by HEIGHT), or null if it has none. */
    public java.awt.image.BufferedImage flagImage(int nation) {
        FlagDesigner.Spec spec = flags.get(nation);
        return spec == null ? null : FlagDesigner.render(spec);
    }
    // Each nation's writing, and the goods in its shops
    private NationScripts scripts;
    private Products products;
    // Shops: some of the buildings in the middle of towns
    private final List<Shop> shops = new ArrayList<>();
    private final Map<Integer, Vector3[]> shopColours = new HashMap<>();
    private final Map<Long, Object[]> goodsGeometry = new java.util.concurrent.ConcurrentHashMap<>();
    private RoadRoutes routes;
    // How proudly each nation flies its flag, 0 to 1: how often flags appear on houses,
    // flagpoles and signs
    private final Map<Integer, Float> patriotism = new HashMap<>();
    // How keen each nation is on pasting posters on its walls, 0.3 to 1
    private final Map<Integer, Float> posterKeenness = new HashMap<>();
    // Maps for signs are drawn here, off the GL thread
    private static final java.util.concurrent.ExecutorService SIGN_MAP_ARTIST = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sign-map-artist");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final float windAngle;
    /**
     * A solid shape, for collision: a wall, fence or rail along the ground from a to b, size
     * its half-width, or (b null) a post of radius size at a; top how high it stands.
     */
    private record Obstacle(SpherePosition a, SpherePosition b, float size, float top) {
    }

    // Solid shapes recorded as each chunk is built, filed on the sphere. The world never
    // changes, so a chunk's shapes stay valid after its meshes are dropped (and are only filed
    // the first time it is built).
    private final CellIndex<Obstacle> obstacleIndex = new CellIndex<>(CellIndex.DEFAULT_LEVEL);
    private final java.util.Set<Long> chunksRecorded = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private List<Obstacle> recording;
    private final Map<Long, List<House>> housesByChunk = new HashMap<>();
    private volatile boolean roadNetworkInitialized;
    private float roadChunkSize;
    private float regionWidth;
    private float seaLevel;
    private PerlinNoise terrainNoise;

    // Unit building meshes shared by every house, plus each nation's roof
        private final Map<Integer, float[]> roofVertices = new HashMap<>();
        private java.util.function.Function<String, Texture> nationTextures = name -> null;
    private final List<BuildingStyle.WallVariant> wallVariants;
    private final Map<Integer, int[]> roofIndices = new HashMap<>();

    /**
     * A straight piece of road between two of its path's points, built in a frame of its own
     * (D3): east/up/south at its middle, on the datum. start, end and the tangents are in that
     * frame as {x, height above the datum, z}, x and z measured along the ground (see
     * SphereFrame.fromSphere), so the flat road-building code runs on them as it stands.
     */
    private static final class RoadSegment {
        private final SphereFrame frame;
        // Its ends on the planet: the path's own points
        private final SpherePosition from, to;
        private final Vector3 start;
        private final Vector3 end;
        private final Vector3 startTangent;
        private final Vector3 endTangent;
        // Chart bridge (D13): its ends on the Mercator chart {x, height, z}, for what still works
        // there (which chunk builds it, spawn points, people and vehicles)
        private final Vector3 chartStart, chartEnd;
        private final int nationId;
        private final int pathIndex;
        private final RoadPath.RoadClass roadClass;
        private final float startDistance;
        private final float length;
        private final float width;
        private final boolean culDeSac;
        // The deck's height at each subdivision, once worked out
        volatile float[] deck;
        private boolean railed;

        private RoadSegment(SphereFrame frame, SpherePosition from, SpherePosition to, Vector3 start, Vector3 end,
                            Vector3 startTangent, Vector3 endTangent, Vector3 chartStart, Vector3 chartEnd, int nationId,
                            int pathIndex, RoadPath.RoadClass roadClass, float startDistance, float length,
                            float width, boolean culDeSac) {
            this.frame = frame;
            this.from = from;
            this.to = to;
            this.chartStart = chartStart;
            this.chartEnd = chartEnd;
            this.start = start;
            this.end = end;
            this.startTangent = startTangent;
            this.endTangent = endTangent;
            this.nationId = nationId;
            this.pathIndex = pathIndex;
            this.roadClass = roadClass;
            this.startDistance = startDistance;
            this.length = length;
            this.width = width;
            this.culDeSac = culDeSac;
        }

        /** Junction priority; turning circles always swallow any paint that reaches them. */
        private int rank() {
            return culDeSac ? 3 : roadClass.rank;
        }

        /** Chart bridge (D13): the middle on the chart. */
        private float chartMidX() {
            return (chartStart.x + chartEnd.x) * 0.5f;
        }

        private float chartMidZ() {
            return (chartStart.z + chartEnd.z) * 0.5f;
        }

        /** Chart bridge (D13): the length on the chart (longer than the true length away from the equator). */
        private float chartLength() {
            return (float) Math.max(1e-4, Math.hypot(chartEnd.x - chartStart.x, chartEnd.z - chartStart.z));
        }
    }

    /**
     * A house on its plot, built in its own frame (D3): frame's origin is on the datum under
     * the house's middle, local +Z (the door side) faces the road it fronts, and every house
     * measurement (plot, walls, fence) is in that frame, y being the height above the datum.
     */
    private static final class House {
        private SphereFrame frame;
        // Chart bridge (D13): where the house is on the Mercator chart and how it's turned there
        // (degrees, as Matrix4Transform.rotateAroundY), for what is still laid out on the chart
        private float x;
        private float z;
        private float rotationY;
        private int nationId;
        private float sizeScale;
        private float width;
        private float depth;
        private float setback;
        private float baseY;
        private float mainWallHeight;
        private float doorBase;
        private int extensionSide;
        private float extensionWidth;
        private float extensionDepth;
        private float extensionWallHeight;
        private boolean fenced;
        private float plotMinX;
        private float plotMaxX;
        private float plotMinZ;
        private float plotMaxZ;
        private float priority;
                private long seed;
        private int frontagePath;
                private int wallVariant;
        private int form;
        // Fence: a tall security boundary, the outline's shape, whether a neighbour's fence
        // already runs along a side, and any extra fenced paddock (local minX, maxX, minZ, maxZ)
        private boolean secure;
        private int fenceShape;
        private boolean sharedLeft;
        private boolean sharedRight;
        private float[] paddock;
        private Doorway doorway;
        private Shop shop;
        // How well off the household is (0 to 1, see areaRichness); how weathered its walls and
        // roof are (0 none to 3 badly); whether it's dressed up with pillars and a cornice
        private float richness;
        private int weathering;
        private boolean grand;
        // A driveway down one side (-1 left, 1 right, 0 none), between these local x
        private int drivewaySide;
        private float driveMinX, driveMaxX;
        // How far towards the road the driveway runs (local z), once known
        private float driveFrontZ;
        // The front door's corners in world space {x, y, z}: bottom left, bottom right, top right, top left
        private float[][] doorCorners;


        private float plotCentreLocalX() {
            return (plotMinX + plotMaxX) * 0.5f;
        }

        private float plotCentreLocalZ() {
            return (plotMinZ + plotMaxZ) * 0.5f;
        }

        private float plotRadius() {
            float hx = (plotMaxX - plotMinX) * 0.5f;
            float hz = (plotMaxZ - plotMinZ) * 0.5f;
            return (float) Math.sqrt(hx * hx + hz * hz);
        }

        /** Bounds of the building itself (house plus any extension) in local space. */
        private float houseMinX() {
            return -width * 0.5f - (extensionSide < 0 ? extensionWidth : 0.0f);
        }

        private float houseMaxX() {
            return width * 0.5f + (extensionSide > 0 ? extensionWidth : 0.0f);
        }
    }

    /** A shop: a building in a town centre with a shop front, its name over it and goods in its windows. */
    public static final class Shop {
        private final House house;
        public final int nationId;
        public final int colour;
        public final boolean grocer;
        private int[] name;

        private Shop(House house, Random rand) {
            this.house = house;
            this.nationId = house.nationId;
            this.colour = rand.nextInt(4);
            // Fresh produce is likelier where people are poorer; the well off buy packets and boxes
            this.grocer = rand.nextFloat() < 0.8f - 0.65f * house.richness;
        }
    }

    @FunctionalInterface
    public interface RoadPathVisitor {
        void visit(RoadPath path);
    }

    @FunctionalInterface
    public interface BuildingVisitor {
        /** A footprint: where its middle is, how it's turned, its size, and its outline round its middle in its own x and z. */
        void visit(float x, float z, float rotationY, float width, float depth, int nationId, float[][] outline);
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
            // Most nations are fairly reserved; a few fly flags everywhere
            float roll = new Random(seed * 53L + n * 977L).nextFloat();
            patriotism.put(n, 0.05f + 0.95f * (float) Math.pow(roll, 1.4));
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

        /** Texture name for a nation's walls, roofs or fences ("wall", "roof" or "fence"). */
    /** This world's curtain fabrics: soft, fairly muted colours, a different set each world. */
    private Vector3 curtainColour(int k) {
        Random rand = new Random(worldSeed * 43L + k * 7919L);
        float[] c = WorldPalette.hsv(rand.nextFloat(), 0.3f + 0.4f * rand.nextFloat(), 0.4f + 0.45f * rand.nextFloat());
        return new Vector3(c[0], c[1], c[2]);
    }

    /** How wide a nation's dirt tracks are: from footpaths to broad cart tracks. */
    private float trackWidth(int nationId) {
        return 14f + 6f * nationRandom(67L, nationId).nextFloat();
    }

    /** How gritty a nation's tracks are, from packed smooth earth (0) to stony (1). */
    private float trackGrit(int nationId) {
        return nationRandom(61L, nationId).nextFloat();
    }

    public static String nationTextureName(String part, int nationId) {
        return part + "_nation" + nationId;
    }

    /**
     * Generators for every nation's wall, roof and fence textures, keyed by texture name,
     * for WorldArtGenerator to run alongside the world's other textures.
     */
    // The curtains' pictures, one for each of this world's curtain colours (then 0 to 3)
    private static final String CURTAIN_TEXTURE = "curtains_";

    public Map<String, java.util.function.Supplier<java.awt.image.BufferedImage>> textureJobs() {
        Map<String, java.util.function.Supplier<java.awt.image.BufferedImage>> jobs = new LinkedHashMap<>();
        for (int k = 0; k < CURTAIN_COLOURS; k++) {
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
                if (System.getProperty("xenoguesser.dumpart") != null) {
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

            private static int roofKey(int nationId, int form) {
        return nationId * 64 + form;
    }

    private BuildingStyle styleOf(House house) {
        return formStyles.get(house.nationId)[house.form];
    }

    /**
     * Picks a house's form from its nation's mix, tilted by its town's favourites; taller
     * forms are likelier towards the middle of a town, so centres rise above the suburbs, and
     * where people are richer, whose houses are taller and less like everyone else's (the
     * nation's rarer forms come up more often).
     */
    private int chooseForm(SpherePosition place, int nationId, long houseSeed, float richness) {
        BuildingStyle[] forms = formStyles.get(nationId);
        float[] weights = forms[0].formWeights;
        float urbanness = settlementManager.getUrbanness(place);
        // In the heart of a big city, most buildings are towers, the more so the richer it is
        float big = smoothstep(BIG_CITY_FROM, BIG_CITY_TO, settlementManager.getCitySize(SphereMath.direction(place)));
        float towerChance = Math.min(0.92f, big * smoothstep(0.3f, 0.75f, urbanness) * (0.55f + 0.6f * richness));
        Random towerRoll = new Random(houseSeed ^ 0x5BD1E995L);
        if (towerRoll.nextFloat() < towerChance) {
            List<Integer> towers = new ArrayList<>();
            for (int f = 0; f < forms.length; f++) if (forms[f].tower) towers.add(f);
            if (!towers.isEmpty()) return towers.get(towerRoll.nextInt(towers.size()));
        }
        SettlementManager.Settlement town = urbanness > 0.05f ? settlementManager.dominantSettlementAt(place) : null;
        Random townRand = town == null ? null : new Random(worldSeed ^ (town.rank * 0xC2B2AE3D27D4EB4FL));
        float[] tilted = new float[weights.length];
        float total = 0;
        for (int f = 0; f < weights.length; f++) {
            float townBias = townRand == null ? 1.0f : (float) Math.exp(townRand.nextGaussian() * 0.7);
            float lift = Math.max(0.0f, forms[f].wallHeight / forms[0].wallHeight - 1.0f);
            float usual = (float) Math.pow(Math.max(1e-4f, weights[f]), 1.0f - 0.75f * richness);
            tilted[f] = forms[f].tower ? 0f : usual * townBias * (1.0f + 3.0f * urbanness * lift + 4.0f * richness * richness * lift);
            total += tilted[f];
        }
        float pick = new Random(houseSeed ^ 0x3C6EF372FE94F82AL).nextFloat() * total;
        for (int f = 0; f < tilted.length; f++) {
            pick -= tilted[f];
            if (pick <= 0) return f;
        }
        return tilted.length - 1;
    }

        public static String wallVariantTextureName(int variant) {
        return "wall_variant" + variant;
    }

    /**
     * Picks a house's wall variety from its nation's mix, tilted by its town's own favourites,
     * so a town has a recognisable look without every house matching.
     */
    private int chooseWallVariant(SpherePosition place, int nationId, long houseSeed) {
        float[] weights = buildingStyles.get(nationId).wallVariantWeights;
        SettlementManager.Settlement town = settlementManager.getUrbanness(place) > 0.05f
                ? settlementManager.dominantSettlementAt(place) : null;
        float[] tilted = new float[weights.length];
        float total = 0;
        Random townRand = town == null ? null : new Random(worldSeed ^ (town.rank * 0x9E3779B97F4A7C15L));
        for (int v = 0; v < weights.length; v++) {
            float townBias = townRand == null ? 1.0f : (float) Math.exp(townRand.nextGaussian() * 0.6);
            tilted[v] = weights[v] * townBias;
            total += tilted[v];
        }
        float pick = new Random(houseSeed).nextFloat() * total;
        for (int v = 0; v < tilted.length; v++) {
            pick -= tilted[v];
            if (pick <= 0) return v;
        }
        return tilted.length - 1;
    }

    /**
     * A house's way in and out, for the people who live there: a point just inside the front
     * door, the doorstep, and a point on the pavement beside the road it faces, with that
     * road's direction there. World coordinates.
     */
    public static final class Doorway {
        public final float insideX, insideZ, doorX, doorZ, kerbX, kerbZ, roadDirX, roadDirZ;
        public final int nationId;
        public final int path;
        public final float urbanness;
        public final long id;
        // A shop's door: people go in to shop, but nobody lives there
        public final boolean shop;
        // The way through the front fence (or where it would be), straight out from the door
        public final float gateX, gateZ;
        // How far the opening in the front runs each way along the road from the kerb point
        // (gate, and driveway if there is one), so nobody parks across it
        public final float gapBefore, gapAfter;
        // The driveway, or null: {mouth x, z (where it meets the front), inward x, z (unit, towards
        // the back), its length, its width, x, z (unit, across it towards the garden path)}
        public final float[] driveway;
        // The front door's corners {x, y, z} (bottom left, bottom right, top right, top left), once built
        public volatile float[][] doorCorners;

        private Doorway(float[] inside, float[] door, float[] kerb, float[] roadDir, int nationId, int path, float urbanness, long id, boolean shop,
                        float[] gate, float[] gap, float[] driveway) {
            this.gateX = gate[0];
            this.gateZ = gate[1];
            this.gapBefore = gap[0];
            this.gapAfter = gap[1];
            this.driveway = driveway;
            this.insideX = inside[0];
            this.insideZ = inside[1];
            this.doorX = door[0];
            this.doorZ = door[1];
            this.kerbX = kerb[0];
            this.kerbZ = kerb[1];
            this.roadDirX = roadDir[0];
            this.roadDirZ = roadDir[1];
            this.nationId = nationId;
            this.path = path;
            this.urbanness = urbanness;
            this.id = id;
            this.shop = shop;
        }
    }

    /**
     * A way on foot along a road from near (ax, az) to near (bx, bz): points just inside the
     * road's edge on A's side, following the road's bends, so walkers keep to the street
     * rather than cutting across gardens. Empty if the road isn't known.
     */
    public List<float[]> streetWalk(int path, float ax, float az, float bx, float bz) {
        List<float[]> points = new ArrayList<>();
        List<RoadSegment> all = segmentsByPath.get(path);
        if (all == null) return points;
        List<RoadSegment> segs = new ArrayList<>();
        for (RoadSegment segment : all) if (!segment.culDeSac) segs.add(segment);
        if (segs.isEmpty()) return points;
        int ia = nearestSegment(segs, ax, az), ib = nearestSegment(segs, bx, bz);
        RoadSegment first = segs.get(ia);
        float[] onA = nearestOnSegment(ax, az, first);
        float dirX = first.chartEnd.x - first.chartStart.x, dirZ = first.chartEnd.z - first.chartStart.z;
        float side = dirX * (az - onA[1]) - dirZ * (ax - onA[0]) >= 0f ? 1f : -1f;
        points.add(walkPoint(first, ax, az, side));
        if (ia < ib) {
            for (int k = ia; k < ib; k++) points.add(walkPoint(segs.get(k), segs.get(k).chartEnd.x, segs.get(k).chartEnd.z, side));
        } else {
            for (int k = ia; k > ib; k--) points.add(walkPoint(segs.get(k), segs.get(k).chartStart.x, segs.get(k).chartStart.z, side));
        }
        points.add(walkPoint(segs.get(ib), bx, bz, side));
        return points;
    }

    private static int nearestSegment(List<RoadSegment> segs, float x, float z) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < segs.size(); i++) {
            float[] p = nearestOnSegment(x, z, segs.get(i));
            float d = (p[0] - x) * (p[0] - x) + (p[1] - z) * (p[1] - z);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** The point on a segment nearest (x, z), moved out to just inside the road edge on one side. */
    private static float[] walkPoint(RoadSegment segment, float x, float z, float side) {
        float[] on = nearestOnSegment(x, z, segment);
        float dx = segment.chartEnd.x - segment.chartStart.x, dz = segment.chartEnd.z - segment.chartStart.z;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-4f) return on;
        float offset = Math.max(1.5f, segment.width * 0.5f - 2.5f) * side;
        return new float[] { on[0] - dz / length * offset, on[1] + dx / length * offset };
    }

    /**
     * Chart bridge (D13) for the people, who live on the chart until Phase 4: every house's
     * doorway with its centre inside a chart rectangle (found on the sphere round its middle).
     */
    public List<Doorway> doorwaysIn(float minX, float minZ, float maxX, float maxZ) {
        List<Doorway> result = new ArrayList<>();
        float midX = (minX + maxX) * 0.5f, midZ = (minZ + maxZ) * 0.5f;
        // (the true reach of the rectangle's corners: the chart is stretched least nearest the equator)
        float nearestEquator = minZ <= 0f && maxZ >= 0f ? 0f : Math.min(Math.abs(minZ), Math.abs(maxZ));
        float reach = (float) Math.hypot(maxX - minX, maxZ - minZ) * 0.5f / chartStretch(nearestEquator);
        houseIndex.forEachNear(sphere(midX, midZ), reach, house -> {
            if (house.x >= minX && house.x < maxX && house.z >= minZ && house.z < maxZ) result.add(doorwayOf(house));
        });
        return result;
    }

    /** Doorways of houses whose middle is within reach (true distance) of a chart point. */
    public List<Doorway> doorwaysNear(float x, float z, float reach) {
        List<Doorway> result = new ArrayList<>();
        SpherePosition place = sphere(x, z);
        houseIndex.forEachNear(place, reach, house -> {
            if (SphereMath.distance(house.frame.origin(), place) <= reach) result.add(doorwayOf(house));
        });
        return result;
    }

    private Doorway doorwayOf(House house) {
        if (house.doorway != null) return house.doorway;
        // Worked out in the house's frame, then put on the chart for the people, who live there until Phase 4
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
        float[] gateLocal = followKerb(house, 0f);
        float gapMin = -GATE_HALF_WIDTH, gapMax = GATE_HALF_WIDTH;
        float[] driveway = null;
        if (house.drivewaySide != 0 && house.shop == null) {
            gapMin = Math.min(gapMin, house.driveMinX - 1f);
            gapMax = Math.max(gapMax, house.driveMaxX + 1f);
            float cx = (house.driveMinX + house.driveMaxX) * 0.5f;
            float frontZ = followKerb(house, cx)[1];
            float[] mouth = chartAt(house, cx, frontZ);
            float[] in = chartDirection(house, 0f, -1f);
            float[] across = chartDirection(house, -house.drivewaySide, 0f);
            driveway = new float[] { mouth[0], mouth[1], in[0], in[1], frontZ + house.depth * 0.5f,
                    house.driveMaxX - house.driveMinX, across[0], across[1] };
        }
        // The opening measured along the road from the kerb point (the house's x runs along the road one way or the other)
        float sense = roadDir[0] >= 0f ? 1f : -1f;
        float a = (gapMin - kerb[0]) * sense, b = (gapMax - kerb[0]) * sense;
        float[] gap = { Math.min(a, b), Math.max(a, b) };
        house.doorway = new Doorway(chartAt(house, 0f, house.depth * 0.5f - 5f), chartAt(house, door[0], door[1]),
                chartAt(house, kerb[0], kerb[1]), chartDirection(house, roadDir[0], roadDir[1]), house.nationId, house.frontagePath,
                settlementManager.getUrbanness(house.frame.origin()), house.seed, house.shop != null,
                chartAt(house, 0f, gateLocal[1]), gap, driveway);
        house.doorway.doorCorners = house.doorCorners;
        return house.doorway;
    }

    /** Supplies the loaded nation textures; must be set before any chunk is generated. */
    public void setNationTextures(java.util.function.Function<String, Texture> lookup) {
        this.nationTextures = lookup;
    }

    public RoadLineStyle getRoadLineStyle(int nationId) {
        return roadLineStyles.get(nationId);
    }

    public BuildingStyle getBuildingStyle(int nationId) {
        return buildingStyles.get(nationId);
    }

    // ==========================================
    //          NETWORK PREPARATION
    // ==========================================

    public void prepareRoadNetwork(float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        if (roadNetworkInitialized) {
            return;
        }
        roadChunkSize = chunkSize;
        regionWidth = totalRegionWidth;
        seaLevel = seaLevelHeight;
        terrainNoise = noise;

        RoadNetworkBuilder builder = new RoadNetworkBuilder(worldSeed, seaLevelHeight, noise,
                settlementManager, nationManager);
        builder.setNationRichness(this::nationRichness);
        // (dirt tracks don't cross fenced borders)
        builder.setHostile(kinship::hostile);
        islandWealth = builder;
        roadNetwork.addAll(builder.build());
        straightenCrossings();
        // (the maps, sign maps, route graph and border checks still read the roads on the chart)
        for (RoadPath path : roadNetwork) path.toChart();
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
                float chartMidX = (float) Planet.mercatorPosition(middle)[0];
                RoadSegment segment = new RoadSegment(frame, from, to, inFrame(frame, from), inFrame(frame, to),
                        startTangent, endTangent, onChart(from, chartMidX), onChart(to, chartMidX), nationId, pathIndex,
                        path.roadClass, distanceAlongPath, length, width, false);

                GuardRailStyle railStyle = guardRailStyles.get(nationId);
                segment.railed = path.roadClass == RoadPath.RoadClass.HIGHWAY && railStyle != null && railStyle.enabled
                        && settlementManager.getUrbanness(middle) < RAIL_MAX_URBANNESS;

                roadSegmentsByChunk.computeIfAbsent(chunkKeyAt(segment.chartMidX(), segment.chartMidZ()), ignored -> new ArrayList<>()).add(segment);
                segmentIndex.add(middle, length * 0.5, segment);
                segmentsByPath.computeIfAbsent(pathIndex, ignored -> new ArrayList<>()).add(segment);
                distanceAlongPath += length;
            }
        }

        findBorderChecks();

        long startTime = System.currentTimeMillis();
        placeHouses();
        System.out.printf("[HOUSES] %d houses placed along the roads in %d ms%n", houses.size(), System.currentTimeMillis() - startTime);
        chooseShops();
        // Fences go up once it's known which buildings are shops (they have none)
        connectNeighbourFences();
        // (the plots are final now, so each house is filed reaching its plot's far corner)
        for (House house : houses) {
            houseIndex.add(house.frame.origin(), plotReach(house), house);
            if (house.drivewaySide != 0 && house.shop == null) noteDriveway(house);
            if (house.shop != null) shopIndex.add(house.frame.origin(), house.shop);
        }
        // The road graph for walking and for maps, got ready in the background
        SIGN_MAP_ARTIST.submit(this::routes);
        for (List<RoadSegment> bucket : roadSegmentsByChunk.values()) {
            for (RoadSegment segment : bucket) {
                boolean playable = Math.abs(segment.chartStart.x) < regionWidth * 0.5f && Math.abs(segment.chartStart.z) < regionWidth * 0.5f;
                if (playable && segment.start.y > seaLevel + 0.5f && segment.end.y > seaLevel + 0.5f) {
                    landSegments.add(segment);
                }
            }
        }
        // Bucket order is not stable; sort so a seeded spawn pick is reproducible
        landSegments.sort((a, b) -> a.pathIndex != b.pathIndex ? Integer.compare(a.pathIndex, b.pathIndex)
                : Float.compare(a.startDistance, b.startDistance));
        roadNetworkInitialized = true;
    }

    /** Visits every road polyline in the region, e.g. for drawing a road map. */
    public void forEachRoadPath(RoadPathVisitor visitor) {
        for (RoadPath path : roadNetwork) {
            visitor.visit(path);
        }
    }

        /**
     * A spot in the road in front of a random house, looking at it: {x, z, dirX, dirZ}.
     * A developer aid for inspecting buildings; null if the world has no houses.
     */
    public float[] randomHouseViewpoint(Random rand) {
        if (houses.isEmpty()) {
            return null;
        }
                House house = houses.get(rand.nextInt(houses.size()));
        for (int attempt = 0; attempt < 50 && (Math.abs(house.x) > regionWidth * 0.5f || Math.abs(house.z) > regionWidth * 0.5f); attempt++) {
            house = houses.get(rand.nextInt(houses.size()));
        }
        float distance = house.depth * 0.5f + house.setback + Math.max(house.width, house.depth) * 1.6f;
        float[] eye = chartAt(house, house.width * 0.35f, distance);
        float dx = house.x - eye[0], dz = house.z - eye[1];
        float len = (float) Math.hypot(dx, dz);
        return new float[] { eye[0], eye[1], dx / len, dz / len };
    }

    /**
     * A random point on a road over dry land, for spawning the player.
     * Returns {x, z, directionX, directionZ}, or null if the world has no roads.
     */
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
     * land. Returns {spawnX, spawnZ, dirX, dirZ}: where the player stands on its stairs and
     * the way the stairs face, towards the road. Null if nowhere suitable turns up.
     *
     * @param standsAt   distance from the pod's middle to where the player stands
     * @param reach      how far the pod and its stairs reach from its middle
     * @param dryLand    whether a point is above the sea
     */
    public float[] landingSite(Random rand, float standsAt, float reach, java.util.function.BiPredicate<Float, Float> dryLand) {
        if (landSegments.isEmpty()) return null;
        for (int attempt = 0; attempt < 400; attempt++) {
            RoadSegment segment = landSegments.get(rand.nextInt(landSegments.size()));
            if (segment.railed) continue;   // not over a guard rail
            float t = rand.nextFloat();
            // (picked on the chart, where the spawn still lands: its true distances stretched to the chart's)
            float rx = segment.chartStart.x + (segment.chartEnd.x - segment.chartStart.x) * t;
            float rz = segment.chartStart.z + (segment.chartEnd.z - segment.chartStart.z) * t;
            float stretch = chartStretch(rz);
            float alongX = (segment.chartEnd.x - segment.chartStart.x) / segment.chartLength();
            float alongZ = (segment.chartEnd.z - segment.chartStart.z) / segment.chartLength();
            float side = rand.nextBoolean() ? 1f : -1f;
            float acrossX = -alongZ * side, acrossZ = alongX * side;
            // Off to one side (never on the road, where it would be in the traffic's way), stairs towards it
            float off = (segment.width * 0.5f + reach + 4f + rand.nextFloat() * 45f) * stretch;
            float cx = rx + acrossX * off, cz = rz + acrossZ * off;
            float dirX = -acrossX, dirZ = -acrossZ;
            float sx = cx + dirX * standsAt * stretch, sz = cz + dirZ * standsAt * stretch;
            float fx = cx + dirX * reach * stretch, fz = cz + dirZ * reach * stretch;
            if (isPlotLocation(cx, cz, reach) || isPlotLocation(sx, sz, 8f) || isPlotLocation(fx, fz, 8f)) continue;
            if (!dryLand.test(cx, cz) || !dryLand.test(sx, sz) || !dryLand.test(fx, fz)) continue;
            boolean feetDry = true;
            for (int leg = 0; leg < 4 && feetDry; leg++) {
                double a = Math.PI * 0.25 + leg * Math.PI * 0.5;
                feetDry = dryLand.test(cx + (float) Math.cos(a) * 36f * stretch, cz + (float) Math.sin(a) * 36f * stretch);
            }
            if (!feetDry) continue;
            // Clear of every road, legs and all
            boolean offRoad = !isRoadLocation(cx, cz, 30f);
            for (int leg = 0; leg < 4 && offRoad; leg++) {
                double a = Math.PI * 0.25 + leg * Math.PI * 0.5;
                offRoad = !isRoadLocation(cx + (float) Math.cos(a) * 36f * stretch, cz + (float) Math.sin(a) * 36f * stretch, 6f);
            }
            if (!offRoad) continue;
            return new float[] { sx, sz, dirX, dirZ };
        }
        return null;
    }

    public float[] randomRoadPoint(Random rand) {
        if (landSegments.isEmpty()) {
            return null;
        }
        RoadSegment segment = landSegments.get(rand.nextInt(landSegments.size()));
        float t = rand.nextFloat();
        float dirX = (segment.chartEnd.x - segment.chartStart.x) / segment.chartLength();
        float dirZ = (segment.chartEnd.z - segment.chartStart.z) / segment.chartLength();
        if (rand.nextBoolean()) {
            dirX = -dirX;
            dirZ = -dirZ;
        }
        return new float[] {
            segment.chartStart.x + (segment.chartEnd.x - segment.chartStart.x) * t,
            segment.chartStart.z + (segment.chartEnd.z - segment.chartStart.z) * t,
            dirX, dirZ
        };
    }

    /** Visits every shop's building (without its extension). */
    public void forEachShop(BuildingVisitor visitor) {
        for (Shop shop : shops) {
            House house = shop.house;
            visitor.visit(house.x, house.z, house.rotationY, house.width, house.depth, house.nationId,
                    footprint(styleOf(house), house.width, house.depth, 0f, 0f));
        }
    }

    /** Visits every building footprint in the region, in its own shape (box, round or many-sided); extensions are visited as their own rectangle. */
    public void forEachBuilding(BuildingVisitor visitor) {
        for (House house : houses) {
            visitor.visit(house.x, house.z, house.rotationY, house.width, house.depth, house.nationId,
                    footprint(styleOf(house), house.width, house.depth, 0f, 0f));
            if (house.extensionSide != 0) {
                float[] centre = chartAt(house, house.extensionSide * (house.width + house.extensionWidth) * 0.5f,
                        (house.extensionDepth - house.depth) * 0.5f);
                float hw = house.extensionWidth * 0.5f, hd = house.extensionDepth * 0.5f;
                visitor.visit(centre[0], centre[1], house.rotationY, house.extensionWidth, house.extensionDepth, house.nationId,
                        new float[][] { { -hw, hd }, { hw, hd }, { hw, -hd }, { -hw, -hd } });
            }
        }
    }

    // ==========================================
    //          SPATIAL QUERIES
    // ==========================================
    // On the sphere (D5): each searches the CellIndex round the place, and every distance test
    // is done in the segment's or house's own frame, so it matches what is drawn. The (x, z)
    // overloads are chart bridges (D13) for the people and vehicles, on the chart until Phase 4.

    /**
     * Whether a place is on ground cleared for a road: within clearance of a made road's
     * edge, or on a dirt track itself (within trackMargin of its edge), so a track's bare
     * earth spans just the ground cleared for it; or on a driveway.
     */
    public boolean isClearedForRoad(SpherePosition place, float clearance, float trackMargin) {
        place = SphereMath.atAltitude(place, 0.0);
        if (onDriveway(place)) return true;
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

    /** Chart bridge: isRoadLocation at a chart point. */
    public boolean isRoadLocation(float worldX, float worldZ, float clearance) {
        return isRoadLocation(sphere(worldX, worldZ), clearance);
    }

    /**
     * The altitude of what someone walking at a place stands on: the road's raised surface on
     * a road (eased down to the ground over a pace or so beyond its edge), otherwise the ground.
     */
    public float walkingSurfaceY(SpherePosition place) {
        place = SphereMath.atAltitude(place, 0.0);
        float ground = TerrainMesh.getLayeredHeight(place, terrainNoise);
        if (!roadNetworkInitialized) return ground;
        float inside = roadDepth(place);
        final float easing = 1.5f;
        if (inside <= -easing) return ground;
        float road = roadSurfaceY(place);
        float t = Math.min(1f, (inside + easing) / easing);
        return ground + (road - ground) * t;
    }

    /** Chart bridge: walkingSurfaceY at a chart point. */
    public float walkingSurfaceY(float worldX, float worldZ) {
        return walkingSurfaceY(sphere(worldX, worldZ));
    }

    /** How far inside the nearest made road's edge a place is (negative outside it). */
    private float roadDepth(SpherePosition place) {
        float best = -Float.MAX_VALUE;
        for (RoadSegment segment : segmentsNear(place, 2f + MAX_ROAD_HALF_WIDTH)) {
            if (segment.roadClass == RoadPath.RoadClass.DIRT) continue;
            float distance = (float) Math.sqrt(distanceSquaredToSegment(place, segment));
            best = Math.max(best, segment.width * 0.5f - distance);
        }
        return best;
    }

    /** Whether a place lies within clearance of any house (including its extension). */
    public boolean isBuildingLocation(SpherePosition place, float clearance) {
        if (!roadNetworkInitialized) {
            return false;
        }
        place = SphereMath.atAltitude(place, 0.0);
        for (House house : houseIndex.near(place, clearance)) {
            float[] local = local(house, place);
            if (local[0] >= house.houseMinX() - clearance && local[0] <= house.houseMaxX() + clearance
                    && local[1] >= -house.depth * 0.5f - clearance && local[1] <= house.depth * 0.5f + clearance) {
                return true;
            }
        }
        return false;
    }

    /** Chart bridge: isBuildingLocation at a chart point. */
    public boolean isBuildingLocation(float worldX, float worldZ, float clearance) {
        return isBuildingLocation(sphere(worldX, worldZ), clearance);
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
    private boolean isPlotLocation(SpherePosition place, float clearance) {
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

    /** Chart bridge: isPlotLocation at a chart point. */
    private boolean isPlotLocation(float worldX, float worldZ, float clearance) {
        return isPlotLocation(sphere(worldX, worldZ), clearance);
    }

    /** How far a house's land reaches from its middle: the far corner of its plot (or paddock). */
    private static float plotReach(House house) {
        float x = Math.max(-house.plotMinX, house.plotMaxX), z = Math.max(-house.plotMinZ, house.plotMaxZ);
        if (house.paddock != null) z = Math.max(z, -house.paddock[2]);
        // (a hair over, as the frame's flat plane and the ground part a little)
        return (float) Math.hypot(x, z) + 1f;
    }

    /** Road segments that may come within a true distance of a place (on the datum). */
    private List<RoadSegment> segmentsNear(SpherePosition place, float distance) {
        return segmentIndex.near(place, distance);
    }

    // ==========================================
    //          HOUSE PLACEMENT
    // ==========================================

    /**
     * Rolls houses along every road segment, then keeps them in priority order
     * so no two plots overlap. Deterministic for a given seed.
     */
    private void placeHouses() {
        List<House> candidates = new ArrayList<>();
        // Dead ends first: their houses outrank every roadside house, and trimming a road
        // back must happen before houses are rolled along it
        List<House> endHouses = new ArrayList<>();
        for (RoadPath.DeadEnd deadEnd : deadEnds) {
            House endHouse = finishDeadEnd(deadEnd, endHouses);
            if (endHouse != null) {
                endHouses.add(endHouse);
            }
        }
        candidates.addAll(endHouses);
        System.out.printf("[ROADS] %d dead ends: %d finished with a house, %d cut back to a junction%n",
                deadEnds.size(), endHouses.size(), deadEnds.size() - endHouses.size());
        for (List<RoadSegment> bucket : roadSegmentsByChunk.values()) {
            for (RoadSegment segment : bucket) {
                // (dirt tracks have no houses, except on islands the main roads never reached)
                if (!segment.culDeSac && !segment.railed && (segment.roadClass != RoadPath.RoadClass.DIRT || roadNetwork.get(segment.pathIndex).island)) {
                    rollHousesAlong(segment, candidates);
                }
            }
        }
        // Bucket iteration order is not stable, so sort fully by priority then seed for a deterministic result
        candidates.sort((a, b) -> a.priority != b.priority ? Float.compare(b.priority, a.priority) : Long.compare(a.seed, b.seed));

        // The plots kept so far, filed at the middle of each plot reaching its corners
        for (House candidate : candidates) {
            SpherePosition centre = world(candidate, candidate.plotCentreLocalX(), 0f, candidate.plotCentreLocalZ());
            boolean blocked = straddlesFencedBorder(candidate);
            if (!blocked) {
                for (House other : plotIndex.near(centre, candidate.plotRadius() + PLOT_GAP)) {
                    if (plotsOverlap(candidate, other)) {
                        blocked = true;
                        break;
                    }
                }
            }
            if (blocked) {
                continue;
            }
            plotIndex.add(centre, candidate.plotRadius(), candidate);
            houses.add(candidate);
            housesByChunk.computeIfAbsent(chunkKeyAt(candidate.x, candidate.z), ignored -> new ArrayList<>()).add(candidate);
        }
    }

    // The plots by their middles while they are laid out and fenced (they may still grow a
    // little: see connectNeighbourFences), before houseIndex takes over
    private final CellIndex<House> plotIndex = new CellIndex<>(LOOKUP_LEVEL);

    // How much more closely houses line the roads of islands the main roads never reached
    private static final float ISLAND_HOUSING = 3f;

    private void rollHousesAlong(RoadSegment segment, List<House> candidates) {
        long segmentSeed = worldSeed ^ ((long) segment.pathIndex * 0x9E3779B97F4A7C15L)
                ^ (Float.floatToIntBits(segment.startDistance) * 0xC2B2AE3D27D4EB4FL);
        Random rand = new Random(segmentSeed);
        // Laid out in the segment's frame (D4), each house then given a frame of its own
        float urbanness = settlementManager.getUrbanness(segment.frame.origin());
        float habitability = settlementManager.getHabitability(segment.frame.origin());

        Double ruralProbability = nationBuildingProbabilities.get(segment.nationId);
        Float urbanDensity = nationUrbanDensities.get(segment.nationId);
        if (ruralProbability == null || urbanDensity == null) {
            return;
        }
        float perHundred = (float) (ruralProbability * RURAL_FRONTAGE_SCALE) * (0.3f + 0.7f * habitability)
                + urbanDensity * (float) Math.pow(urbanness, URBAN_DENSITY_EXPONENT);
        // An island's few roads are where all its people live, so they're built up more closely
        if (roadNetwork.get(segment.pathIndex).island) perHundred *= ISLAND_HOUSING;
        float attemptsExact = perHundred * segment.length / 100.0f * 2.0f * PLACEMENT_OVERSAMPLE;
        int attempts = (int) attemptsExact;
        if (rand.nextFloat() < attemptsExact - attempts) {
            attempts++;
        }

        // Gardens shrink to nothing as the countryside gives way to town
        float gardenScale = 1.0f - smoothstep(0.1f, 0.45f, urbanness);
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;

        for (int attempt = 0; attempt < attempts; attempt++) {
            House house = new House();
            house.seed = segmentSeed * 31L + attempt;
            house.frontagePath = segment.pathIndex;
            float t = rand.nextFloat();
            float side = rand.nextBoolean() ? 1.0f : -1.0f;
            house.sizeScale = 0.9f + rand.nextFloat() * 0.2f;
            house.setback = KERB_SETBACK_MIN + rand.nextFloat() * KERB_SETBACK_RANGE + rand.nextFloat() * MAX_FRONT_GARDEN * gardenScale;
            float extensionRoll = rand.nextFloat();
            boolean extensionLeft = rand.nextBoolean();
            float fenceRoll = rand.nextFloat();
            float sideYard = 6.0f + rand.nextFloat() * 8.0f;
            float backYard = 8.0f + rand.nextFloat() * (6.0f + 30.0f * gardenScale);
            house.priority = rand.nextFloat();

            float outwardX = -dirZ * side;
            float outwardZ = dirX * side;
            float roadX = segment.start.x + (segment.end.x - segment.start.x) * t;
            float roadZ = segment.start.z + (segment.end.z - segment.start.z) * t;

            // The house takes the nation of the ground it stands on, a little back from the road
            float probe = segment.width + house.setback + 40.0f;
            int nationId = nationManager.getNationAt(world(segment, roadX + outwardX * probe, roadZ + outwardZ * probe));
            FenceStyle fenceStyle = fenceStyles.get(nationId);
            if (!formStyles.containsKey(nationId)) {
                continue;
            }
            house.nationId = nationId;
            SpherePosition roadPlace = world(segment, roadX, roadZ);
            house.richness = areaRichness(nationId, settlementManager.getUrbanness(roadPlace), roadPlace);
            house.sizeScale *= 1f + 0.35f * house.richness * house.richness;
            house.form = chooseForm(roadPlace, nationId, house.seed, house.richness);
            BuildingStyle style = styleOf(house);
            house.width = style.width * house.sizeScale;
            house.depth = style.depth * house.sizeScale;
            if (extensionRoll < style.extensionChance * (1f + house.richness)) {
                house.extensionSide = extensionLeft ? -1 : 1;
                house.extensionWidth = house.width * style.extensionWidthRatio;
                house.extensionDepth = house.depth * style.extensionDepthRatio;
            }
            house.fenced = fenceStyle != null && fenceRoll < fenceStyle.fenceChance;

            float offset = segment.width * 0.5f + house.setback + house.depth * 0.5f;
            placeHouse(house, segment, roadX + outwardX * offset, roadZ + outwardZ * offset, -outwardX, -outwardZ);

            float overhang = style.roofOverhang * house.sizeScale;
            house.plotMinX = house.houseMinX() - overhang - sideYard;
            house.plotMaxX = house.houseMaxX() + overhang + sideYard;
            decideDriveway(house, overhang);
            house.plotMinZ = -house.depth * 0.5f - overhang - backYard;
            house.plotMaxZ = house.depth * 0.5f + house.setback - FRONT_FENCE_GAP;

            if (!settleOnGround(house, style) || !isPlotClearOfRoads(house)) {
                continue;
            }
            house.wallVariant = chooseWallVariant(house.frame.origin(), house.nationId, house.seed);
        decideFence(house);
        decideLook(house);
            candidates.add(house);
        }
    }

    /**
     * Gives a dead-end road a house at its end, facing back down the road. Where the
     * ground there can't take a house, the road is cut back a segment at a time until one
     * fits or the road's end reaches a junction, so no road simply stops in the open.
     */
    private House finishDeadEnd(RoadPath.DeadEnd deadEnd, List<House> otherEndHouses) {
        List<RoadSegment> pathSegments = segmentsByPath.get(deadEnd.pathIndex);
        RoadPath path = roadNetwork.get(deadEnd.pathIndex);
        Random rand = new Random(worldSeed ^ (deadEnd.pathIndex * 0x9E3779B97F4A7C15L) ^ (deadEnd.atStart ? 0x5A5AL : 0xA5A5L));
        for (int trims = 0; pathSegments != null && !pathSegments.isEmpty(); trims++) {
            RoadSegment terminal = deadEnd.atStart ? pathSegments.get(0) : pathSegments.get(pathSegments.size() - 1);
            Vector3 end = deadEnd.atStart ? terminal.start : terminal.end;
            Vector3 inner = deadEnd.atStart ? terminal.end : terminal.start;
            SpherePosition endPoint = deadEnd.atStart ? terminal.from : terminal.to;
            if (trims > 0 && meetsAnotherRoad(endPoint, deadEnd.pathIndex, terminal.width)) {
                return null;
            }
            float dirX = (end.x - inner.x) / terminal.length;
            float dirZ = (end.z - inner.z) / terminal.length;
            for (int attempt = 0; attempt < 3; attempt++) {
                House house = endHouse(terminal, end, dirX, dirZ, rand, attempt);
                if (house == null) {
                    continue;
                }
                boolean overlaps = false;
                for (House other : otherEndHouses) {
                    overlaps |= plotsOverlap(house, other);
                }
                if (!overlaps) {
                    return house;
                }
            }
            // Nothing fits here: cut the last stretch of road off and try further back
            pathSegments.remove(terminal);
            List<RoadSegment> bucket = roadSegmentsByChunk.get(chunkKeyAt(terminal.chartMidX(), terminal.chartMidZ()));
            if (bucket != null) {
                bucket.remove(terminal);
            }
            segmentIndex.remove(terminal.frame.origin(), terminal);
            for (int i = 0; i < path.points.size(); i++) {
                if (path.points.get(i) == endPoint) {
                    path.points.remove(i);
                    // (the chart copy, point for point, for the maps)
                    if (i < path.chart.size()) path.chart.remove(i);
                    break;
                }
            }
        }
        return null;
    }

    private boolean meetsAnotherRoad(SpherePosition point, int pathIndex, float width) {
        for (RoadSegment segment : segmentsNear(point, width + MAX_ROAD_HALF_WIDTH)) {
            if (segment.pathIndex == pathIndex) {
                continue;
            }
            float limit = (segment.width + width) * 0.5f + 2.0f;
            if (distanceSquaredToSegment(point, segment) <= limit * limit) {
                return true;
            }
        }
        return false;
    }

    /** A house on the line of the road just past its end (in the terminal segment's frame); later attempts are smaller and closer. */
    private House endHouse(RoadSegment terminal, Vector3 end, float dirX, float dirZ, Random rand, int attempt) {
        House house = new House();
        house.seed = rand.nextLong();
        house.frontagePath = terminal.pathIndex;
        house.priority = 2.0f + rand.nextFloat();
        house.sizeScale = (0.9f + rand.nextFloat() * 0.2f) * (1.0f - 0.12f * attempt);
        house.setback = KERB_SETBACK_MIN + rand.nextFloat() * KERB_SETBACK_RANGE * (1.0f - 0.4f * attempt);
        float extensionRoll = rand.nextFloat();
        boolean extensionLeft = rand.nextBoolean();
        float fenceRoll = rand.nextFloat();
        float sideYard = 6.0f + rand.nextFloat() * 8.0f;
        float backYard = 8.0f + rand.nextFloat() * 14.0f;

        float probe = terminal.width + house.setback + 40.0f;
        int nationId = nationManager.getNationAt(world(terminal, end.x + dirX * probe, end.z + dirZ * probe));
        FenceStyle fenceStyle = fenceStyles.get(nationId);
        if (!formStyles.containsKey(nationId)) {
            return null;
        }
        house.nationId = nationId;
        SpherePosition endPlace = world(terminal, end.x, end.z);
        house.richness = areaRichness(nationId, settlementManager.getUrbanness(endPlace), endPlace);
        house.sizeScale *= 1f + 0.35f * house.richness * house.richness;
        house.form = chooseForm(endPlace, nationId, house.seed, house.richness);
        BuildingStyle style = styleOf(house);
        house.width = style.width * house.sizeScale;
        house.depth = style.depth * house.sizeScale;
        if (attempt == 0 && extensionRoll < style.extensionChance * (1f + house.richness)) {
            house.extensionSide = extensionLeft ? -1 : 1;
            house.extensionWidth = house.width * style.extensionWidthRatio;
            house.extensionDepth = house.depth * style.extensionDepthRatio;
        }
        house.fenced = fenceStyle != null && fenceRoll < fenceStyle.fenceChance;

        float offset = terminal.width * 0.5f + house.setback + house.depth * 0.5f;
        placeHouse(house, terminal, end.x + dirX * offset, end.z + dirZ * offset, -dirX, -dirZ);

        float overhang = style.roofOverhang * house.sizeScale;
        house.plotMinX = house.houseMinX() - overhang - sideYard;
        house.plotMaxX = house.houseMaxX() + overhang + sideYard;
        decideDriveway(house, overhang);
        house.plotMinZ = -house.depth * 0.5f - overhang - backYard;
        house.plotMaxZ = house.depth * 0.5f + house.setback - FRONT_FENCE_GAP;

        if (!settleOnGround(house, style) || !isPlotClearOfRoads(house)) {
            return null;
        }
        house.wallVariant = chooseWallVariant(house.frame.origin(), house.nationId, house.seed);
        decideFence(house);
        decideLook(house);
        return house;
    }

    /** Sinks the house into the slope and sizes its walls; false if the ground is too steep or wet. */
    private boolean settleOnGround(House house, BuildingStyle style) {
        float halfWidth = house.width * 0.5f;
        float halfDepth = house.depth * 0.5f;
        float[] mainRange = groundRange(house, -halfWidth, halfWidth, -halfDepth, halfDepth);
        float minGround = mainRange[0];
        float maxGround = mainRange[1];
        float[] extensionRange = null;
        if (house.extensionSide != 0) {
            float innerX = house.extensionSide * halfWidth;
            float outerX = house.extensionSide * (halfWidth + house.extensionWidth);
            extensionRange = groundRange(house, Math.min(innerX, outerX), Math.max(innerX, outerX),
                    -halfDepth, -halfDepth + house.extensionDepth);
            minGround = Math.min(minGround, extensionRange[0]);
            maxGround = Math.max(maxGround, extensionRange[1]);
        }
        if (minGround <= seaLevel + 0.5f || maxGround - minGround > BUILDING_MAX_GROUND_DROP) {
            return false;
        }
        if (groundAt(house, house.plotMinX, house.plotMinZ) <= seaLevel || groundAt(house, house.plotMaxX, house.plotMinZ) <= seaLevel) {
            return false;
        }

        // Sink the base below the lowest corner and grow the walls so slopes never expose a gap
        house.baseY = minGround - BUILDING_FOUNDATION_DEPTH;
        house.mainWallHeight = (mainRange[1] - house.baseY) + style.wallHeight * house.sizeScale;
        if (extensionRange != null) {
            house.extensionWallHeight = (extensionRange[1] - house.baseY)
                    + style.wallHeight * house.sizeScale * style.extensionHeightRatio;
        }
        house.doorBase = groundAt(house, 0.0f, halfDepth) - house.baseY;
        return true;
    }

    private float[] groundRange(House house, float minX, float maxX, float minZ, float maxZ) {
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            float ground = groundAt(house, (corner & 1) == 0 ? minX : maxX, (corner & 2) == 0 ? minZ : maxZ);
            low = Math.min(low, ground);
            high = Math.max(high, ground);
        }
        return new float[] { low, high };
    }

    /**
     * Exact test of the rotated plot against nearby carriageways and turning
     * circles. The road the house fronts only has to clear the house itself,
     * since it may bend into the corners of the front garden; the fence simply
     * stops where it does.
     */
        private boolean isPlotClearOfRoads(House house) {
        BuildingStyle style = styleOf(house);
        float overhang = style.roofOverhang * house.sizeScale;
        float plotHalfX = (house.plotMaxX - house.plotMinX) * 0.5f;
        float plotHalfZ = (house.plotMaxZ - house.plotMinZ) * 0.5f;
        float houseHalfX = (house.houseMaxX() - house.houseMinX()) * 0.5f + overhang;
        float houseHalfZ = house.depth * 0.5f + overhang;
        float[] plotCentre = { house.plotCentreLocalX(), house.plotCentreLocalZ() };
        float[] houseCentre = { (house.houseMinX() + house.houseMaxX()) * 0.5f, 0.0f };
        float reach = (float) Math.sqrt(plotHalfX * plotHalfX + plotHalfZ * plotHalfZ) + PLOT_HIGHWAY_MARGIN + MAX_ROAD_HALF_WIDTH;

        for (RoadSegment segment : segmentsNear(world(house, plotCentre[0], 0f, plotCentre[1]), reach)) {
            boolean ownRoad = segment.pathIndex == house.frontagePath;
            float[] boxCentre = ownRoad ? houseCentre : plotCentre;
            float halfX = ownRoad ? houseHalfX : plotHalfX;
            float halfZ = ownRoad ? houseHalfZ : plotHalfZ;

            // Segment end points in the house's frame
            float[] a = local(house, segment.from);
            float[] b = local(house, segment.to);
            float margin = segment.roadClass == RoadPath.RoadClass.HIGHWAY ? PLOT_HIGHWAY_MARGIN : PLOT_ROAD_MARGIN;
            float limit = segment.width * 0.5f + margin;
            if (segmentToBoxDistanceSquared(a[0] - boxCentre[0], a[1] - boxCentre[1], b[0] - boxCentre[0], b[1] - boxCentre[1],
                    halfX, halfZ) < limit * limit) {
                return false;
            }
        }
        return true;
    }

    /** Separating-axis test between two rotated plot rectangles, worked in a's frame. */
    private static boolean plotsOverlap(House a, House b) {
        float reach = a.plotRadius() + b.plotRadius() + PLOT_GAP;
        SpherePosition centreA = world(a, a.plotCentreLocalX(), 0f, a.plotCentreLocalZ());
        SpherePosition centreB = world(b, b.plotCentreLocalX(), 0f, b.plotCentreLocalZ());
        // (a quick look first: straight through the ground is never further than along it)
        double cx = centreB.x() - centreA.x(), cy = centreB.y() - centreA.y(), cz = centreB.z() - centreA.z();
        if (cx * cx + cy * cy + cz * cz > (double) reach * reach) {
            return false;
        }
        float[] centre = local(a, centreB);
        float dx = centre[0] - a.plotCentreLocalX();
        float dz = centre[1] - a.plotCentreLocalZ();
        if (dx * dx + dz * dz > reach * reach) {
            return false;
        }

        float[][] axesA = { { 1f, 0f }, { 0f, 1f } };
        float[][] axesB = plotAxes(a, b);
        float[] halfA = { (a.plotMaxX - a.plotMinX) * 0.5f, (a.plotMaxZ - a.plotMinZ) * 0.5f };
        float[] halfB = { (b.plotMaxX - b.plotMinX) * 0.5f, (b.plotMaxZ - b.plotMinZ) * 0.5f };
        float[][] testAxes = { axesA[0], axesA[1], axesB[0], axesB[1] };
        for (float[] axis : testAxes) {
            float radiusA = halfA[0] * Math.abs(dot(axesA[0], axis)) + halfA[1] * Math.abs(dot(axesA[1], axis));
            float radiusB = halfB[0] * Math.abs(dot(axesB[0], axis)) + halfB[1] * Math.abs(dot(axesB[1], axis));
            if (Math.abs(dx * axis[0] + dz * axis[1]) > radiusA + radiusB + PLOT_GAP) {
                return false;
            }
        }
        return true;
    }

    /** House b's local X and Z axes as level directions in a's frame. */
    private static float[][] plotAxes(House a, House b) {
        double[] x = b.frame.xAxis(), z = b.frame.zAxis();
        double[] bx = a.frame.localDirection(x[0], x[1], x[2]), bz = a.frame.localDirection(z[0], z[1], z[2]);
        double lx = Math.hypot(bx[0], bx[2]), lz = Math.hypot(bz[0], bz[2]);
        return new float[][] { { (float) (bx[0] / lx), (float) (bx[2] / lx) }, { (float) (bz[0] / lz), (float) (bz[2] / lz) } };
    }

    // ==========================================
    //          CHUNK GENERATION
    // ==========================================

    /** Geometry accumulated for one nation within one chunk, one builder per material. */
    private final class NationBatch {
        private final int nationId;
        private final Map<String, MeshBuilder> builders = new LinkedHashMap<>();
        private float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        // What is being built now is in a frame of its own, placed by this (null: on the chart)
        private MeshBuilder.Placement placement;

        private NationBatch(int nationId) {
            this.nationId = nationId;
        }

        /** Builds from now on in a frame of its own, placed by placement (null: chart coordinates as given). */
        private void place(MeshBuilder.Placement placement) {
            this.placement = placement;
            for (MeshBuilder builder : builders.values()) builder.setPlacement(placement);
        }

        private MeshBuilder builder(String part) {
            MeshBuilder builder = builders.computeIfAbsent(part, ignored -> new MeshBuilder());
            builder.setPlacement(placement);
            return builder;
        }

        private void include(float x, float y, float z, float margin) {
            if (placement != null) {
                float[] p = { x, y, z };
                placement.place(p, new float[] { 0f, 1f, 0f });
                x = p[0];
                y = p[1];
                z = p[2];
            }
            minX = Math.min(minX, x - margin);
            maxX = Math.max(maxX, x + margin);
            minZ = Math.min(minZ, z - margin);
            maxZ = Math.max(maxZ, z + margin);
            maxY = Math.max(maxY, y);
        }

        private InfrastructureObject build() {
            List<InfrastructureObject.BatchPart> parts = new ArrayList<>();
            for (Map.Entry<String, MeshBuilder> entry : builders.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                String part = entry.getKey();
                boolean doubleSided = part.equals("rail") || part.equals("band") || part.equals("fence") || part.equals("security") || part.equals("roadwall")
                        || part.equals("borderfence") || part.equals("barbed") || part.startsWith(BOOTH_PART)
                        || part.startsWith("curtain") || part.equals("flag") || part.equals("goods");
                                boolean paint = part.equals("line") || part.equals("driveway");
                int wornAt = part.indexOf(WORN);
                Material material = material(wornAt < 0 ? part : part.substring(0, wornAt), nationId);
                InfrastructureObject.BatchPart batchPart = new InfrastructureObject.BatchPart(entry.getValue().vertexArray(), entry.getValue().indexArray(),
                        material, doubleSided, paint, material.diffuseMapExists(), part.equals("glass"), part);
                if (wornAt >= 0) batchPart.weathering = Integer.parseInt(part.substring(wornAt + WORN.length())) / 3f;
                if (part.equals("dirt")) batchPart.trackGrit = 0.05f + 0.95f * trackGrit(nationId);
                parts.add(batchPart);
            }
            if (parts.isEmpty() || minX > maxX) {
                return null;
            }
            float centreX = (minX + maxX) * 0.5f;
            float centreZ = (minZ + maxZ) * 0.5f;
            float centreY = TerrainMesh.getLayeredHeight(centreX, centreZ, terrainNoise);
            float halfDiagonal = 0.5f * (float) Math.sqrt((maxX - minX) * (maxX - minX) + (maxZ - minZ) * (maxZ - minZ));
            float radius = Math.max(halfDiagonal, maxY - centreY);
            InfrastructureObject batch = InfrastructureObject.createBatch(new Vector3(centreX, centreY, centreZ), nationId, radius, parts);
            // The goods on shop shelves and small flags vanish into the distance before the buildings
            // do; what's seen through windows (curtains, rooms, shop interiors) lasts nearly as long
            batch.setDetailDistance(p -> p.name.equals("goods") || p.name.equals("shelf") || p.name.equals("flag"), 700f);
            batch.setDetailDistance(p -> p.name.startsWith("curtain") || p.name.equals("interior") || p.name.equals("shopinterior"), 1700f);
            return batch;
        }
    }

    public List<InfrastructureObject> generateForChunk(int cx, int cz, float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        List<InfrastructureObject> objects = new ArrayList<>();
        prepareRoadNetwork(chunkSize, totalRegionWidth, seaLevelHeight, noise);

        Random signRand = new Random((long)cx * 8912L + (long)cz * 4123L);
        List<SpherePosition> signPositions = new ArrayList<>();
        Map<Integer, NationBatch> batches = new HashMap<>();
        long key = chunkKey(cx, cz);
        recording = new ArrayList<>();

        List<House> chunkHouses = across(cx, cz, housesByChunk, this::shifted);
        if (!chunkHouses.isEmpty()) {
            for (House house : chunkHouses) {
                NationBatch batch = batches.computeIfAbsent(house.nationId, NationBatch::new);
                // Built in the house's own frame (D3)
                batch.place(onChart(house));
                bakeHouse(batch, house, objects);
                if (house.fenced) {
                    bakeFence(batch, house);
                }
                // In town, a tall flagpole at the front corner of some plots
                float urban = settlementManager.getUrbanness(house.frame.origin());
                Random poleRand = new Random(house.seed ^ 0x9013L);
                if (urban > 0.25f && poleRand.nextFloat() < patriotismOf(house.nationId) * 0.45f * urban) {
                    float side = house.drivewaySide != 0 ? -house.drivewaySide : poleRand.nextBoolean() ? 1f : -1f;
                    float px = side > 0 ? house.plotMaxX - 4f : house.plotMinX + 4f, pz = house.plotMaxZ - 4f;
                    SpherePosition at = world(house, px, 0f, pz);
                    if (!inKeepClear(at) && !isRoadLocation(at, 2f)) {
                        flagpole(batch, house, px, pz, 40f + poleRand.nextFloat() * 14f);
                    }
                }
                batch.place(null);
                if (signRand.nextDouble() < buildingStyles.get(house.nationId).signChance) {
                    InfrastructureObject sign = createHouseSign(house, signRand);
                    if (sign != null && (inKeepClear(sign.frame.origin()) || nearBorderCheck(sign))) sign = null;
                    if (sign != null) {
                        objects.add(sign);
                        recordSignPosts(sign);
                        signPositions.add(sign.frame.origin());
                    }
                }
            }
        }

        borderFences(batches, cx, cz);
        borderBooths(batches, cx, cz);
        crossoverLines(batches, cx, cz);

        List<RoadSegment> segments = across(cx, cz, roadSegmentsByChunk, this::shifted);
        if (!segments.isEmpty()) {
            for (RoadSegment segment : segments) {
                NationBatch batch = batches.computeIfAbsent(segment.nationId, NationBatch::new);
                // Built in the segment's own frame (D3)
                batch.place(onChart(segment));
                if (segment.culDeSac) {
                    appendCulDeSac(batch, segment);
                    batch.place(null);
                    continue;
                }
                appendRoadSegment(batch, segment);
                batch.place(null);
                InfrastructureObject sign = tryCreateRoadsideSign(segment, signRand, signPositions);
                if (sign != null && (inKeepClear(sign.frame.origin()) || nearBorderCheck(sign))) sign = null;
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
        // (a chunk built again, after its meshes were dropped, has its shapes filed already)
        if (chunksRecorded.add(key)) {
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

    private void recordSegment(SpherePosition a, SpherePosition b, float halfWidth) {
        recordSegment(a, b, halfWidth, Float.POSITIVE_INFINITY);
    }

    /** A solid line along the ground between two places, standing top high (for jumping over). */
    private void recordSegment(SpherePosition a, SpherePosition b, float halfWidth, float top) {
        if (recording != null) recording.add(new Obstacle(SphereMath.atAltitude(a, 0.0), SphereMath.atAltitude(b, 0.0), halfWidth, top));
    }

    /** A sign stands on two posts either side of its board, which is up out of the way. */
    private void recordSignPosts(InfrastructureObject sign) {
        if (sign.wallMounted) return;
        for (float side : new float[] { -InfrastructureObject.SIGN_POST_SPACING, InfrastructureObject.SIGN_POST_SPACING }) {
            recordCircle(sign.frame.toWorld(side, 0.0, 0.0), 0.9f);
        }
    }

    private void recordCircle(SpherePosition centre, float radius) {
        if (recording != null) recording.add(new Obstacle(SphereMath.atAltitude(centre, 0.0), null, radius, Float.POSITIVE_INFINITY));
    }

    /** Walls, fences, guard rails and sign posts reaching within reach of a place, from the chunks built so far. */
    public void obstaclesNear(SpherePosition centre, double reach, GeodesicCollision.Sink sink) {
        obstacleIndex.forEachNear(SphereMath.atAltitude(centre, 0.0), reach, obstacle -> {
            if (obstacle.b() == null) sink.circle(obstacle.a(), obstacle.size(), obstacle.top());
            else sink.segment(obstacle.a(), obstacle.b(), obstacle.size(), obstacle.top());
        });
    }

    /**
     * Chart bridge (D13) for the people and vehicles, on the chart until Phase 4: the shapes
     * near a chart point, put on the chart (reach, widths and radii in chart units there).
     */
    public void obstaclesNear(float x, float z, float reach, Collision.Sink sink) {
        float stretch = chartStretch(z);
        obstacleIndex.forEachNear(sphere(x, z), reach / stretch, obstacle -> {
            float[] a = chartPoint(obstacle.a(), 0f, x);
            if (obstacle.b() == null) {
                sink.circle(a[0], a[2], obstacle.size() * stretch, obstacle.top());
            } else {
                float[] b = chartPoint(obstacle.b(), 0f, x);
                sink.segment(a[0], a[2], b[0], b[2], obstacle.size() * stretch, obstacle.top());
            }
        });
    }

        /**
     * A white material carrying the named texture (which holds the colour itself), or a
     * plain coloured one if the texture isn't available.
     */
    private Material texturedOr(String textureName, Vector3 colour, Vector3 specular, float shininess) {
        Texture texture = nationTextures.apply(textureName);
        if (texture == null) {
            return new Material(colour, colour, specular, shininess);
        }
        Vector3 white = new Vector3(1.0f, 1.0f, 1.0f);
        Material material = new Material(white, white, specular, shininess);
        material.setDiffuseMap(texture);
        return material;
    }

    /** Materials are created once per nation and part, and shared by every batch. */
    private Material material(String part, int nationId) {
        if (part.equals("asphalt")) {
            return ASPHALT;
        }
        return materials.computeIfAbsent(part + "_" + nationId, ignored -> {
                        Vector3 specular = new Vector3(0.03f, 0.03f, 0.03f);
            BuildingStyle building = buildingStyles.get(nationId);
            if (part.startsWith(BOOTH_PART)) {
                int v = Integer.parseInt(part.substring(BOOTH_PART.length()));
                return texturedOr(wallVariantTextureName(v), wallVariants.get(v).colour, specular, 2.0f);
            }
            if (part.startsWith("wall_variant")) {
                int v = Integer.parseInt(part.substring("wall_variant".length()));
                return texturedOr(wallVariantTextureName(v), wallVariants.get(v).colour, specular, 2.0f);
            }
            GuardRailStyle rail = guardRailStyles.get(nationId);
            switch (part) {
                case "line": {
                    Vector3 colour = roadLineStyles.get(nationId).colour;
                    return new Material(Vector3.multiply(colour, 0.9f), colour, new Vector3(0.05f, 0.05f, 0.05f), 8.0f);
                }
                case "rail":
                    return new Material(rail.railColour, rail.railColour, new Vector3(0.35f, 0.35f, 0.35f), 24.0f);
                case "band":
                    return new Material(rail.bandColour, rail.bandColour, new Vector3(0.35f, 0.35f, 0.35f), 24.0f);
                case "post":
                    return new Material(rail.postColour, rail.postColour, specular, 4.0f);
                                                case "wall":
                    return new Material(building.wallColour, building.wallColour, specular, 2.0f);
                case "roof":
                    return texturedOr(nationTextureName("roof", nationId), building.roofColour, specular, 4.0f);
                case "door":
                    return new Material(building.doorColour, building.doorColour, specular, 2.0f);
                case "glass":
                    return new Material(building.glassColour, building.glassColour, new Vector3(0.6f, 0.6f, 0.6f), 64.0f);
                case "flag":
                    return texturedOr(nationTextureName("flag", nationId), new Vector3(0.8f, 0.8f, 0.8f), new Vector3(0.05f, 0.05f, 0.05f), 4.0f);
                case "goods":
                    return texturedOr(nationTextureName("packaging", nationId), new Vector3(0.8f, 0.6f, 0.4f), new Vector3(0.15f, 0.15f, 0.15f), 12.0f);
                case "shopinterior":
                    // A brightly lit shop seen through the glass
                    return new Material(new Vector3(1.1f, 1.05f, 0.92f), new Vector3(0.95f, 0.92f, 0.82f), new Vector3(0f, 0f, 0f), 2.0f);
                case "shelf":
                    return new Material(new Vector3(0.75f, 0.72f, 0.66f), new Vector3(0.7f, 0.67f, 0.6f), new Vector3(0.1f, 0.1f, 0.1f), 8.0f);
                case "shopwall0": case "shopwall1": case "shopwall2": case "shopwall3": {
                    Vector3 paint = shopColours.get(nationId)[part.charAt(8) - '0'];
                    return new Material(paint, paint, new Vector3(0.08f, 0.08f, 0.08f), 6.0f);
                }
                case "flagpole":
                    return new Material(new Vector3(0.72f, 0.73f, 0.75f), new Vector3(0.72f, 0.73f, 0.75f), new Vector3(0.5f, 0.5f, 0.5f), 32.0f);
                case "interior":
                    return new Material(new Vector3(0.025f, 0.025f, 0.03f), new Vector3(0.03f, 0.03f, 0.035f), new Vector3(0f, 0f, 0f), 2.0f);
                case "curtain0": case "curtain1": case "curtain2": case "curtain3": {
                    int k = part.charAt(7) - '0';
                    return texturedOr(CURTAIN_TEXTURE + k, curtainColour(k), new Vector3(0.02f, 0.02f, 0.02f), 2.0f);
                }
                case "blind0": case "blind1": case "blind2": case "blind3": {
                    // (a tower's upper windows: the same curtains, seen from far below)
                    int k = part.charAt(5) - '0';
                    return texturedOr(CURTAIN_TEXTURE + k, curtainColour(k), new Vector3(0.02f, 0.02f, 0.02f), 2.0f);
                }
                case "frame":
                    return new Material(building.frameColour, building.frameColour, specular, 2.0f);
                case "security": {
                    Vector3 metal = fenceStyles.get(nationId).securityColour;
                    return new Material(metal, metal, new Vector3(0.3f, 0.3f, 0.3f), 24.0f);
                }
                case "dirt":
                    // The ground's own soil texture: the track is the ground, worn bare
                    return texturedOr("dirt_diffuse", new Vector3(0.5f, 0.4f, 0.3f), new Vector3(0.1f, 0.1f, 0.1f), 4.0f);
                case "finery": {
                    // The nation's dressing stone: marble, cream stone, gilt or dark granite
                    Random pick = nationRandom(83L, nationId);
                    Vector3[] stones = { new Vector3(0.9f, 0.89f, 0.86f), new Vector3(0.9f, 0.82f, 0.64f),
                            new Vector3(0.86f, 0.66f, 0.22f), new Vector3(0.22f, 0.22f, 0.25f) };
                    Vector3 stone = stones[pick.nextInt(stones.length)];
                    return new Material(stone, stone, new Vector3(0.35f, 0.33f, 0.3f), 32.0f);
                }
                case "driveway": {
                    // Paving: a little of the nation's stone in it
                    Random tint = nationRandom(59L, nationId);
                    float grey = 0.36f + tint.nextFloat() * 0.2f;
                    Vector3 paving = new Vector3(grey + tint.nextFloat() * 0.08f, grey + tint.nextFloat() * 0.05f, grey);
                    return new Material(paving, paving, new Vector3(0.05f, 0.05f, 0.05f), 2.0f);
                }
                case "roadwall": {
                    // Weathered concrete
                    Vector3 concrete = new Vector3(0.55f, 0.54f, 0.52f);
                    return new Material(concrete, concrete, specular, 2.0f);
                }
                case "secwall": {
                    // Plain rendered concrete, a little tinted by the nation's metalwork
                    Vector3 metal = fenceStyles.get(nationId).securityColour;
                    Vector3 concrete = new Vector3(0.62f + metal.x * 0.15f, 0.6f + metal.y * 0.15f, 0.57f + metal.z * 0.15f);
                    return new Material(concrete, concrete, specular, 2.0f);
                }
                case "borderfence": {
                    // Galvanised steel
                    Vector3 steel = new Vector3(0.56f, 0.58f, 0.6f);
                    return new Material(steel, steel, new Vector3(0.45f, 0.45f, 0.45f), 30.0f);
                }
                case "barbed": {
                    Vector3 wire = new Vector3(0.32f, 0.31f, 0.3f);
                    return new Material(wire, wire, new Vector3(0.5f, 0.5f, 0.5f), 40.0f);
                }
                case "borderpost": {
                    // Heavy concrete gateposts, with a band of warning paint on them
                    Vector3 concrete = new Vector3(0.6f, 0.59f, 0.56f);
                    return new Material(concrete, concrete, specular, 2.0f);
                }
                case "fence":
                default:
                    return texturedOr(nationTextureName("fence", nationId), fenceStyles.get(nationId).colour, specular, 2.0f);
            }
        });
    }

    // ==========================================
    //          ROAD GEOMETRY
    // ==========================================

    /**
     * The height of the road deck at a point on a road's centreline: level with the highest
     * ground across the road's width (the half-width of the widest road there), so the road
     * is flat across, with no sideways camber: its higher side meets the ground and its lower
     * side is built up on a wall. Small dips are bridged. It depends only on where the point
     * is, so roads meeting at a junction meet at the same height. Roads ride a low causeway
     * wherever they dip below the sea.
     */
    private float roadSurfaceY(SpherePosition place) {
        float reach = RoadPath.RoadClass.LANE.width * 0.5f;
        if (roadNetworkInitialized) {
            for (RoadSegment segment : segmentsNear(place, MAX_ROAD_HALF_WIDTH + 1f)) {
                float half = segment.width * 0.5f;
                if (half > reach && distanceSquaredToSegment(place, segment) <= (half + 1f) * (half + 1f)) reach = half;
            }
        }
        // (round the place in its own east/south frame, so it depends on nothing but where it is)
        SphereFrame here = SphereFrame.at(SphereMath.atAltitude(place, 0.0));
        float top = TerrainMesh.getLayeredHeight(place, terrainNoise);
        int around = reach > 16f ? 12 : 8;
        for (int k = 0; k < around; k++) {
            double angle = k * 2 * Math.PI / around;
            for (float r : new float[] { reach * 0.5f, reach }) {
                top = Math.max(top, TerrainMesh.getLayeredHeight(here.toSphere(Math.cos(angle) * r, 0.0, Math.sin(angle) * r), terrainNoise));
            }
        }
        return Math.max(top, seaLevel + 1.0f) + ROAD_SURFACE_OFFSET;
    }

    /** Chart bridge (D13): roadSurfaceY at a chart point. */
    private float roadSurfaceY(float x, float z) {
        return roadSurfaceY(sphere(x, z));
    }

    /** Positions of a line offset sideways from the centreline, one per subdivision, mitred at the ends. */
    private float[][] offsetEdge(RoadSegment segment, float offset) {
        return offsetEdge(segment, offset, deckHeights(segment));
    }

    /** The deck's height at each subdivision along a segment's centreline. */
    private float[] deckHeights(RoadSegment segment) {
        // Worked out once per segment: the surface, its edges, rails and supports all ask
        float[] known = segment.deck;
        if (known != null) return known;
        float[] deck = new float[ROAD_SEGMENT_SUBDIVISIONS + 1];
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            float t = (float) i / ROAD_SEGMENT_SUBDIVISIONS;
            deck[i] = roadSurfaceY(world(segment, segment.start.x + (segment.end.x - segment.start.x) * t,
                    segment.start.z + (segment.end.z - segment.start.z) * t));
        }
        segment.deck = deck;
        return deck;
    }

    private float[][] offsetEdge(RoadSegment segment, float offset, float[] deck) {
        float[][] edge = new float[ROAD_SEGMENT_SUBDIVISIONS + 1][];
        float halfOffset = Math.abs(offset);
        float sign = Math.signum(offset);
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            float t = (float) i / ROAD_SEGMENT_SUBDIVISIONS;
            float x = segment.start.x + (segment.end.x - segment.start.x) * t;
            float z = segment.start.z + (segment.end.z - segment.start.z) * t;
            Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, t);
            float sideX = -tangent.z * halfOffset;
            float sideZ = tangent.x * halfOffset;
            if (i == 0) {
                float[] miter = miterOffset(segment.startTangent, tangent, halfOffset);
                sideX = miter[0];
                sideZ = miter[1];
            } else if (i == ROAD_SEGMENT_SUBDIVISIONS) {
                float[] miter = miterOffset(tangent, segment.endTangent, halfOffset);
                sideX = miter[0];
                sideZ = miter[1];
            }
            float edgeX = x + sideX * sign;
            float edgeZ = z + sideZ * sign;
            // Level across the road: the edge is as high as the deck at the centreline
            edge[i] = new float[] { edgeX, deck[i], edgeZ };
        }
        return edge;
    }

    private void appendRoadSegment(NationBatch batch, RoadSegment segment) {
        float halfWidth = segment.width * 0.5f;
        float[] deck = deckHeights(segment);
        float[][] leftEdge = offsetEdge(segment, -halfWidth, deck);
        float[][] rightEdge = offsetEdge(segment, halfWidth, deck);
        if (segment.roadClass == RoadPath.RoadClass.DIRT) {
            appendTrack(batch, segment, leftEdge, rightEdge);
            return;
        }
        // Where the deck stands above the ground: a wall down to it, or pillars where it's high
        appendRoadSupports(batch, leftEdge, -1f, segment);
        appendRoadSupports(batch, rightEdge, 1f, segment);
        appendDeckUnderside(batch, segment, leftEdge, rightEdge);

        MeshBuilder asphalt = batch.builder("asphalt");
        int firstVertex = -1;
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            float t = (float) i / ROAD_SEGMENT_SUBDIVISIONS;
            int left = asphalt.addVertex(leftEdge[i][0], leftEdge[i][1], leftEdge[i][2], 0.0f, 1.0f, 0.0f, t, 0.0f);
            asphalt.addVertex(rightEdge[i][0], rightEdge[i][1], rightEdge[i][2], 0.0f, 1.0f, 0.0f, t, 0.0f);
            if (firstVertex < 0) {
                firstVertex = left;
            }
            batch.include(leftEdge[i][0], leftEdge[i][1], leftEdge[i][2], 0.0f);
            batch.include(rightEdge[i][0], rightEdge[i][1], rightEdge[i][2], 0.0f);
        }
        for (int i = 0; i < ROAD_SEGMENT_SUBDIVISIONS; i++) {
            // Wound counter-clockwise seen from above so the surface survives back-face culling
            int left = firstVertex + i * 2;
            asphalt.addTriangle(left, left + 1, left + 2);
            asphalt.addTriangle(left + 1, left + 3, left + 2);
        }

        // Other roads close enough to meet this segment, for junction handling, in this segment's frame
        List<Crossing> crossing = new ArrayList<>();
        for (RoadSegment other : segmentsNear(segment.frame.origin(), segment.length * 0.5f + segment.width + MAX_ROAD_HALF_WIDTH + RAIL_JUNCTION_CLEARANCE)) {
            if (other.pathIndex != segment.pathIndex || other.culDeSac) {
                crossing.add(new Crossing(other, segment));
            }
        }

        appendRoadLines(batch.builder("line"), segment, leftEdge, rightEdge, crossing);
        if (segment.railed) {
            GuardRailStyle style = guardRailStyles.get(segment.nationId);
            appendGuardRail(batch, segment, style, -1.0f, crossing);
            appendGuardRail(batch, segment, style, 1.0f, crossing);
        }
    }

    // Below this the drop is left to the asphalt's own edge; above, a retaining wall; higher
    // still, pillars under a deck slab
    private static final float SUPPORT_MIN_DROP = 1.8f, PILLAR_DROP = 8f;

    /**
     * Holds up one side of a raised stretch of road. Where the ground falls away below the
     * deck a vertical retaining wall runs from the road's edge down into the ground; where
     * it falls a long way, the deck is a slab on square pillars instead.
     */
    private void appendRoadSupports(NationBatch batch, float[][] edge, float side, RoadSegment segment) {
        int n = edge.length;
        float[] ground = new float[n];
        float deepest = 0f;
        for (int i = 0; i < n; i++) {
            ground[i] = groundAt(segment, edge[i][0], edge[i][2]);
            deepest = Math.max(deepest, edge[i][1] - ground[i]);
        }
        if (deepest < SUPPORT_MIN_DROP) return;
        MeshBuilder wall = batch.builder("roadwall");
        for (int i = 0; i + 1 < n; i++) {
            float dropA = edge[i][1] - ground[i], dropB = edge[i + 1][1] - ground[i + 1];
            if (Math.max(dropA, dropB) < SUPPORT_MIN_DROP) continue;
            boolean pillars = Math.min(dropA, dropB) > PILLAR_DROP;
            // Outwards from the road, for which way the face points
            float ax = edge[i + 1][0] - edge[i][0], az = edge[i + 1][2] - edge[i][2];
            float length = (float) Math.hypot(ax, az);
            if (length < 1e-3f) continue;
            float outX = -az / length * side, outZ = ax / length * side;
            float bottomA = pillars ? edge[i][1] - 1.6f : ground[i] - 1.5f;
            float bottomB = pillars ? edge[i + 1][1] - 1.6f : ground[i + 1] - 1.5f;
            float[][] quad = { { edge[i][0], edge[i][1] - 0.05f, edge[i][2] }, { edge[i + 1][0], edge[i + 1][1] - 0.05f, edge[i + 1][2] },
                    { edge[i + 1][0], bottomB, edge[i + 1][2] }, { edge[i][0], bottomA, edge[i][2] } };
            int first = -1;
            for (int k = 0; k < 4; k++) {
                float v = k < 2 ? 0f : (quad[k][1] - quad[0][1]) * 0.1f;
                int index = wall.addVertex(quad[k][0], quad[k][1], quad[k][2], outX, 0f, outZ, (k == 1 || k == 2 ? length : 0f) * 0.1f, v);
                if (first < 0) first = index;
            }
            // Counter-clockwise seen from outside
            float[] e1 = { quad[1][0] - quad[0][0], quad[1][1] - quad[0][1], quad[1][2] - quad[0][2] };
            float[] e2 = { quad[3][0] - quad[0][0], quad[3][1] - quad[0][1], quad[3][2] - quad[0][2] };
            float facing = (e1[1] * e2[2] - e1[2] * e2[1]) * outX + (e1[0] * e2[1] - e1[1] * e2[0]) * outZ;
            if (facing > 0f) {
                wall.addTriangle(first, first + 1, first + 3);
                wall.addTriangle(first + 1, first + 2, first + 3);
            } else {
                wall.addTriangle(first, first + 3, first + 1);
                wall.addTriangle(first + 1, first + 3, first + 2);
            }
            if (pillars && i % 2 == 0) {
                // A pillar under the deck's edge, set in a little
                float px = edge[i][0] - outX * 1.5f, pz = edge[i][2] - outZ * 1.5f;
                float base = groundAt(segment, px, pz) - 1f;
                addBox(wall, px, base, pz, ax / length, az / length, 1.1f, 1.1f, edge[i][1] - 1.6f - base);
            }
        }
    }

    /**
     * A dirt track: the ground itself, worn bare and gritty, laid over the ground point by
     * point (following its slope across as well as along) just above it. A fine mesh, so the
     * ground never shows through between its points; textured just as the ground is, so its
     * soil matches what's round it.
     */
    private void appendTrack(NationBatch batch, RoadSegment segment, float[][] leftEdge, float[][] rightEdge) {
        MeshBuilder dirt = batch.builder("dirt");
        int along = Math.max(2, Math.round(segment.length / 3f)), across = Math.max(2, Math.round(segment.width / 3f));
        int first = -1;
        for (int i = 0; i <= along; i++) {
            float t = (float) i / along;
            float[] l = edgePoint(leftEdge, t), r = edgePoint(rightEdge, t);
            // (textured by distance along the path and across it, so one segment runs on into the next)
            float pathDistance = segment.startDistance + t * segment.length;
            for (int j = 0; j <= across; j++) {
                float u = (float) j / across;
                float x = l[0] + (r[0] - l[0]) * u, z = l[2] + (r[2] - l[2]) * u;
                float y = groundAt(segment, x, z);
                // Lit as the ground is: its normal flattened the same way, so the two match
                float nx = (groundAt(segment, x - 1f, z) - groundAt(segment, x + 1f, z)) * 0.1f;
                float nz = (groundAt(segment, x, z - 1f) - groundAt(segment, x, z + 1f)) * 0.1f;
                float length = (float) Math.sqrt(nx * nx + 1f + nz * nz);
                int index = dirt.addVertex(x, y + TRACK_LIFT, z, nx / length, 1f / length, nz / length,
                        ((u - 0.5f) * segment.width + 50f) / 100f, pathDistance / 100f);
                if (first < 0) first = index;
                batch.include(x, y, z, 0f);
            }
        }
        int row = across + 1;
        for (int i = 0; i < along; i++) {
            for (int j = 0; j < across; j++) {
                int a = first + i * row + j, b = a + 1, c = a + row, d = c + 1;
                dirt.addTriangle(a, b, c);
                dirt.addTriangle(b, d, c);
            }
        }
    }

    /** A point t of the way along an edge's subdivisions. */
    private static float[] edgePoint(float[][] edge, float t) {
        float scaled = Math.max(0f, Math.min(1f, t)) * (edge.length - 1);
        int i = Math.min((int) Math.floor(scaled), edge.length - 2);
        float f = scaled - i;
        return new float[] { edge[i][0] + (edge[i + 1][0] - edge[i][0]) * f, 0f, edge[i][2] + (edge[i + 1][2] - edge[i][2]) * f };
    }

    // How far a track lies above the ground (with a depth offset as well, so it never flickers into it)
    private static final float TRACK_LIFT = 0.45f;

    // How long a stretch of track one repeat of its texture covers
    private static final float TRACK_TEXTURE_LENGTH = 15f;

    /** Where the deck stands on pillars, the slab's underside, so it can be seen from beneath. */
    private void appendDeckUnderside(NationBatch batch, RoadSegment segment, float[][] left, float[][] right) {
        MeshBuilder slab = null;
        for (int i = 0; i + 1 < left.length; i++) {
            float drop = Math.max(left[i][1] - groundAt(segment, left[i][0], left[i][2]),
                    right[i][1] - groundAt(segment, right[i][0], right[i][2]));
            float dropNext = Math.max(left[i + 1][1] - groundAt(segment, left[i + 1][0], left[i + 1][2]),
                    right[i + 1][1] - groundAt(segment, right[i + 1][0], right[i + 1][2]));
            // (wherever either edge stands on pillars: on a hillside the downhill edge does, and
            // the road's underside shows between them; where the uphill edge is in the hill, the
            // slab there is hidden in it)
            if (Math.max(drop, dropNext) < PILLAR_DROP * 0.5f) continue;
            if (slab == null) slab = batch.builder("roadwall");
            int a = slab.addVertex(left[i][0], left[i][1] - 1.6f, left[i][2], 0f, -1f, 0f, 0f, 0f);
            slab.addVertex(right[i][0], right[i][1] - 1.6f, right[i][2], 0f, -1f, 0f, 1f, 0f);
            slab.addVertex(left[i + 1][0], left[i + 1][1] - 1.6f, left[i + 1][2], 0f, -1f, 0f, 0f, 1f);
            slab.addVertex(right[i + 1][0], right[i + 1][1] - 1.6f, right[i + 1][2], 0f, -1f, 0f, 1f, 1f);
            slab.addTriangle(a, a + 2, a + 1);
            slab.addTriangle(a + 1, a + 2, a + 3);
        }
    }

    /** Another road near a segment, its centreline put in that segment's frame (junction tests run there). */
    private static final class Crossing {
        private final RoadSegment road;
        private final Vector3 start, end;

        private Crossing(RoadSegment road, RoadSegment frameOf) {
            this.road = road;
            this.start = inFrame(frameOf.frame, road.from);
            this.end = inFrame(frameOf.frame, road.to);
        }
    }

    /** Highest junction rank of any other road covering the point (in the frame the crossings are in), or -1. */
    private int coveringRank(float x, float z, List<Crossing> crossing, float margin) {
        int rank = -1;
        for (Crossing other : crossing) {
            float limit = other.road.width * 0.5f + margin;
            if (other.road.rank() > rank && distanceSquaredToSegment(x, z, other.start, other.end) <= limit * limit) {
                rank = other.road.rank();
            }
        }
        return rank;
    }

    /**
     * Paints the nation's line style and any lane dividers, leaving junctions
     * clear the way real roads do: minor roads stop their paint at the edge of
     * a bigger road and get a give-way line, while the bigger road only breaks
     * its edge lines across the mouth of the junction.
     */
    private void appendRoadLines(MeshBuilder builder, RoadSegment segment, float[][] leftEdge, float[][] rightEdge,
                                 List<Crossing> crossing) {
        RoadLineStyle style = roadLineStyles.get(segment.nationId);
        if (style == null || segment.length <= 0.0f) {
            return;
        }

        List<Float> breakpoints = new ArrayList<>();
        for (int i = 0; i <= LINE_SAMPLES_PER_SEGMENT; i++) {
            breakpoints.add((float) i / LINE_SAMPLES_PER_SEGMENT);
        }
        float segmentEnd = segment.startDistance + segment.length;
        if (style.dashed) {
            addDashBreakpoints(breakpoints, segment, style.dashLength, style.dashPeriod(), segmentEnd);
        }
        boolean laneDividers = segment.width >= LANE_DIVIDER_MIN_WIDTH;
        if (laneDividers) {
            addDashBreakpoints(breakpoints, segment, LANE_DIVIDER_DASH, LANE_DIVIDER_DASH + LANE_DIVIDER_GAP, segmentEnd);
        }
        Collections.sort(breakpoints);

        int ownRank = segment.rank();
        float halfLine = style.lineWidth * 0.5f;
        List<float[]> stripes = new ArrayList<>();
        for (float offset : style.stripeOffsets(segment.width)) {
            stripes.add(new float[] { offset, 0.0f });
        }
        if (laneDividers) {
            stripes.add(new float[] { -segment.width * 0.25f, 1.0f });
            stripes.add(new float[] { segment.width * 0.25f, 1.0f });
        }

        for (float[] stripe : stripes) {
            float offset = stripe[0];
            boolean divider = stripe[1] > 0.0f;
            boolean edgeStripe = Math.abs(offset) > segment.width * 0.3f;
            float innerAcross = 0.5f + (offset - halfLine) / segment.width;
            float outerAcross = 0.5f + (offset + halfLine) / segment.width;

            for (int k = 0; k < breakpoints.size() - 1; k++) {
                float t0 = breakpoints.get(k);
                float t1 = breakpoints.get(k + 1);
                if (t1 - t0 < 1e-5f) {
                    continue;
                }
                float tMid = (t0 + t1) * 0.5f;
                float midDistance = segment.startDistance + tMid * segment.length;
                boolean painted = divider ? isDividerPainted(midDistance) : style.isPaintedAt(midDistance);
                if (!painted) {
                    continue;
                }
                float[] point = surfacePoint(leftEdge, rightEdge, tMid, 0.5f + offset / segment.width);
                int cover = coveringRank(point[0], point[2], crossing, 1.0f);
                if (cover >= 0 && (cover >= ownRank || edgeStripe)) {
                    continue;
                }
                addPaintQuad(builder, leftEdge, rightEdge, t0, t1, innerAcross, outerAcross);
            }
        }

        appendGiveWayLines(builder, segment, style, leftEdge, rightEdge, crossing);
    }

    private void addDashBreakpoints(List<Float> breakpoints, RoadSegment segment, float dashLength, float period, float segmentEnd) {
        for (float dashStart = (float) Math.floor(segment.startDistance / period) * period;
             dashStart <= segmentEnd; dashStart += period) {
            addBreakpoint(breakpoints, (dashStart - segment.startDistance) / segment.length);
            addBreakpoint(breakpoints, (dashStart + dashLength - segment.startDistance) / segment.length);
        }
    }

    private static boolean isDividerPainted(float distance) {
        float period = LANE_DIVIDER_DASH + LANE_DIVIDER_GAP;
        float phase = distance % period;
        if (phase < 0.0f) {
            phase += period;
        }
        return phase < LANE_DIVIDER_DASH;
    }

    /** A line across the carriageway wherever this road meets a road it must give way to. */
    private void appendGiveWayLines(MeshBuilder builder, RoadSegment segment, RoadLineStyle style,
                                    float[][] leftEdge, float[][] rightEdge, List<Crossing> crossing) {
        int ownRank = segment.rank();
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        float halfSpan = GIVE_WAY_LINE_LENGTH * 0.5f / segment.length;

        // Samples run one step beyond each end so transitions at segment joins are caught exactly once
        boolean previousInside = false;
        for (int k = -1; k <= LINE_SAMPLES_PER_SEGMENT + 1; k++) {
            float t = (float) k / LINE_SAMPLES_PER_SEGMENT;
            float x = segment.start.x + dirX * segment.length * t;
            float z = segment.start.z + dirZ * segment.length * t;
            boolean inside = false;
            for (Crossing other : crossing) {
                if (other.road.culDeSac || other.road.rank() <= ownRank) {
                    continue;
                }
                float limit = other.road.width * 0.5f + 1.0f;
                if (distanceSquaredToSegment(x, z, other.start, other.end) <= limit * limit) {
                    inside = true;
                    break;
                }
            }
            if (k > -1 && inside != previousInside) {
                float outsideT = inside ? (float) (k - 1) / LINE_SAMPLES_PER_SEGMENT : t;
                if (outsideT >= 0.0f && outsideT <= 1.0f) {
                    float t0 = Math.max(0.0f, outsideT - halfSpan);
                    float t1 = Math.min(1.0f, outsideT + halfSpan);
                    if (style.dashed) {
                        for (int dash = 0; dash < 5; dash++) {
                            float a = 0.08f + dash * 0.18f;
                            addPaintQuad(builder, leftEdge, rightEdge, t0, t1, a, a + 0.1f);
                        }
                    } else {
                        addPaintQuad(builder, leftEdge, rightEdge, t0, t1, 0.06f, 0.94f);
                    }
                }
            }
            previousInside = inside;
        }
    }

    private void addPaintQuad(MeshBuilder builder, float[][] leftEdge, float[][] rightEdge,
                              float t0, float t1, float innerAcross, float outerAcross) {
        int a = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t0, innerAcross);
        int b = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t0, outerAcross);
        int c = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t1, outerAcross);
        int d = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t1, innerAcross);
        builder.addTriangle(a, b, c);
        builder.addTriangle(a, c, d);
    }

    private void addBreakpoint(List<Float> breakpoints, float t) {
        if (t > 0.0f && t < 1.0f) {
            breakpoints.add(t);
        }
    }

    /** t runs along the segment, across runs from the left edge (0) to the right edge (1). */
    private float[] surfacePoint(float[][] leftEdge, float[][] rightEdge, float t, float across) {
        float scaled = Math.max(0.0f, Math.min(1.0f, t)) * ROAD_SEGMENT_SUBDIVISIONS;
        int i = Math.min((int) Math.floor(scaled), ROAD_SEGMENT_SUBDIVISIONS - 1);
        float along = scaled - i;
        float[] point = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            float left = leftEdge[i][axis] + (leftEdge[i + 1][axis] - leftEdge[i][axis]) * along;
            float right = rightEdge[i][axis] + (rightEdge[i + 1][axis] - rightEdge[i][axis]) * along;
            point[axis] = left + (right - left) * across;
        }
        return point;
    }

    private int addRoadSurfaceVertex(MeshBuilder builder, float[][] leftEdge, float[][] rightEdge, float t, float across) {
        float[] point = surfacePoint(leftEdge, rightEdge, t, across);
        return builder.addVertex(point[0], point[1] + ROAD_LINE_SURFACE_OFFSET, point[2], 0.0f, 1.0f, 0.0f, across, t);
    }

    /** A flat disc of asphalt at the closed end of a street. */
    private void appendCulDeSac(NationBatch batch, RoadSegment segment) {
        MeshBuilder asphalt = batch.builder("asphalt");
        float radius = segment.width * 0.5f;
        int sides = 20;
        float centreX = segment.start.x;
        float centreZ = segment.start.z;
        float level = roadSurfaceY(world(segment, centreX, centreZ));
        int centre = asphalt.addVertex(centreX, level, centreZ, 0.0f, 1.0f, 0.0f, 0.5f, 0.5f);
        for (int i = 0; i <= sides; i++) {
            double angle = 2.0 * Math.PI * i / sides;
            float x = centreX + (float) Math.cos(angle) * radius;
            float z = centreZ + (float) Math.sin(angle) * radius;
            asphalt.addVertex(x, level, z, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f);
            batch.include(x, 0.0f, z, 0.0f);
        }
        for (int i = 0; i < sides; i++) {
            // Counter-clockwise seen from above
            asphalt.addTriangle(centre, centre + i + 2, centre + i + 1);
        }
    }

    // ==========================================
    //          GUARD RAILS
    // ==========================================

    /**
     * Extrudes the nation's rail profile along one side of the highway on
     * regularly spaced posts, leaving gaps where other roads join.
     */
    private void appendGuardRail(NationBatch batch, RoadSegment segment, GuardRailStyle style, float side,
                                 List<Crossing> crossing) {
        float[][] base = offsetEdge(segment, side * (segment.width * 0.5f + RAIL_OFFSET));
        MeshBuilder railBuilder = batch.builder("rail");
        MeshBuilder bandBuilder = batch.builder("band");
        MeshBuilder postBuilder = batch.builder("post");

        List<Float> breakpoints = new ArrayList<>();
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            breakpoints.add((float) i / ROAD_SEGMENT_SUBDIVISIONS);
        }
        if (style.banding == GuardRailStyle.Banding.ALTERNATING) {
            addDashBreakpoints(breakpoints, segment, style.bandLength, style.bandPeriod, segment.startDistance + segment.length);
        }
        Collections.sort(breakpoints);

        List<float[][]> profile = style.crossSection();
        for (int k = 0; k < breakpoints.size() - 1; k++) {
            float t0 = breakpoints.get(k);
            float t1 = breakpoints.get(k + 1);
            if (t1 - t0 < 1e-5f) {
                continue;
            }
            float[] mid = railBase(base, (t0 + t1) * 0.5f);
            if (coveringRank(mid[0], mid[2], crossing, RAIL_JUNCTION_CLEARANCE) >= 0) {
                continue;
            }
            // (nor by a border crossing, where the booth, lights and gates stand)
            if (borderCheckAt(world(segment, mid[0], mid[2]), RAIL_CLEAR_OF_BORDER) != null) continue;
            float[] start = railBase(base, t0);
            float[] end = railBase(base, t1);
            recordSegment(world(segment, start[0], start[2]), world(segment, end[0], end[2]), 0.5f, style.railHeight + style.railSize * 0.5f);
            // Profile "out" points back towards the traffic
            float[] towardRoad0 = towardRoad(segment, t0, side);
            float[] towardRoad1 = towardRoad(segment, t1, side);
            boolean band = style.isBandAt(segment.startDistance + (t0 + t1) * 0.5f * segment.length);

            for (float[][] strip : profile) {
                for (int p = 0; p < strip.length - 1; p++) {
                    boolean topEdge = p == strip.length - 2;
                    MeshBuilder target = band || (style.banding == GuardRailStyle.Banding.TOP_STRIPE && topEdge)
                            ? bandBuilder : railBuilder;
                    float[] a = strip[p];
                    float[] b = strip[p + 1];
                    float edgeOut = b[0] - a[0];
                    float edgeUp = b[1] - a[1];
                    float edgeLength = (float) Math.sqrt(edgeOut * edgeOut + edgeUp * edgeUp);
                    float normalOut = edgeUp / edgeLength;
                    float normalUp = -edgeOut / edgeLength;

                    float[] v0 = profilePoint(start, towardRoad0, style.railHeight, a);
                    float[] v1 = profilePoint(start, towardRoad0, style.railHeight, b);
                    float[] v2 = profilePoint(end, towardRoad1, style.railHeight, b);
                    float[] v3 = profilePoint(end, towardRoad1, style.railHeight, a);
                    float nx = towardRoad0[0] * normalOut;
                    float nz = towardRoad0[1] * normalOut;
                    int i0 = target.addVertex(v0[0], v0[1], v0[2], nx, normalUp, nz, 0, 0);
                    int i1 = target.addVertex(v1[0], v1[1], v1[2], nx, normalUp, nz, 0, 1);
                    int i2 = target.addVertex(v2[0], v2[1], v2[2], nx, normalUp, nz, 1, 1);
                    int i3 = target.addVertex(v3[0], v3[1], v3[2], nx, normalUp, nz, 1, 0);
                    target.addTriangle(i0, i1, i2);
                    target.addTriangle(i0, i2, i3);
                }
            }
            batch.include(start[0], start[1] + style.railHeight, start[2], 2.0f);
        }

        // Posts at a fixed spacing along the whole path so they line up across segments
        float firstPost = (float) Math.ceil(segment.startDistance / style.postSpacing) * style.postSpacing;
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        for (float distance = firstPost; distance < segment.startDistance + segment.length; distance += style.postSpacing) {
            float t = (distance - segment.startDistance) / segment.length;
            float[] position = railBase(base, t);
            if (coveringRank(position[0], position[2], crossing, RAIL_JUNCTION_CLEARANCE) >= 0) {
                continue;
            }
            float[] toward = towardRoad(segment, t, side);
            float postX = position[0] - toward[0] * style.postWidth;
            float postZ = position[2] - toward[1] * style.postWidth;
            float top = style.railHeight + style.railSize * 0.5f;
            addBox(postBuilder, postX, position[1] - 2.0f, postZ, dirX, dirZ,
                    style.postWidth * 0.5f, style.postWidth * 0.5f, top + 2.0f);
        }
    }

    /** Ground point under the rail line (road surface height without the asphalt lift). */
    private float[] railBase(float[][] base, float t) {
        float scaled = Math.max(0.0f, Math.min(1.0f, t)) * ROAD_SEGMENT_SUBDIVISIONS;
        int i = Math.min((int) Math.floor(scaled), ROAD_SEGMENT_SUBDIVISIONS - 1);
        float along = scaled - i;
        return new float[] {
            base[i][0] + (base[i + 1][0] - base[i][0]) * along,
            base[i][1] + (base[i + 1][1] - base[i][1]) * along - ROAD_SURFACE_OFFSET,
            base[i][2] + (base[i + 1][2] - base[i][2]) * along
        };
    }

    /** Horizontal unit vector from the rail back across to the carriageway. */
    private float[] towardRoad(RoadSegment segment, float t, float side) {
        Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, Math.max(0.0f, Math.min(1.0f, t)));
        return new float[] { tangent.z * side, -tangent.x * side };
    }

    private static float[] profilePoint(float[] base, float[] towardRoad, float railHeight, float[] profile) {
        return new float[] {
            base[0] + towardRoad[0] * profile[0],
            base[1] + railHeight + profile[1],
            base[2] + towardRoad[1] * profile[0]
        };
    }

    // ==========================================
    //          HOUSE GEOMETRY
    // ==========================================

        private void bakeHouse(NationBatch batch, House house, List<InfrastructureObject> objects) {
        BuildingStyle style = styleOf(house);
        float scale = house.sizeScale;
        float overhang = style.roofOverhang * scale;
        float[][] footprint = footprint(style, house.width, house.depth, 0.0f, 0.0f);
        boolean smooth = style.footprint == BuildingStyle.Footprint.ROUND;

                BuildingStyle.WallVariant variant = wallVariants.get(house.wallVariant);
        // Shops are painted, standing out from the houses round them
        String wallPart = house.shop != null ? "shopwall" + house.shop.colour : wallVariantTextureName(house.wallVariant);
        // Weathered houses' walls and roofs are drawn stained (see BatchPart.weathering)
        String worn = house.shop == null && house.weathering > 0 ? WORN + house.weathering : "";
        wallPart += worn;
        String roofPart = "roof" + worn;
        HouseWall[] walls = extrudeWalls(batch.builder(wallPart), house, footprint, house.mainWallHeight, style.taper, smooth,
                variant.tileWidth, variant.tileHeight);

        if (style.footprint == BuildingStyle.Footprint.BOX) {
                        float[] roofV = roofVertices.get(roofKey(house.nationId, house.form));
            int[] roofIdx = roofIndices.get(roofKey(house.nationId, house.form));
            bake(batch.builder(roofPart), roofV, roofIdx, house, house.width * style.taper + 2.0f * overhang, style.roofHeight * scale,
                    house.depth * style.taper + 2.0f * overhang, 0, house.mainWallHeight, 0, style.roofTileSize);
        } else {
            centredRoof(batch.builder(roofPart), house, footprint, style, house.mainWallHeight, overhang, smooth);
        }

        // The door sits in the middle of the wall that faces the road
        HouseWall front = frontWall(walls);
        float doorHalfW = Math.min(style.doorWidth * scale, front.length * 0.7f) * 0.5f;
        float doorH = style.doorHeight * scale;
        float[][] door = wallPanel(batch.builder("door"), house, front, 0.5f, house.doorBase + doorH * 0.5f, doorHalfW, doorH * 0.5f, DOOR_OFFSET);
        // (on the chart for the people going in and out, who are still there until Phase 4)
        house.doorCorners = new float[4][];
        for (int i = 0; i < 4; i++) house.doorCorners[i] = chartPoint(house, door[i][0], door[i][1], door[i][2]);
        if (house.doorway != null) house.doorway.doorCorners = house.doorCorners;
        if (house.drivewaySide != 0 && house.shop == null) bakeDriveway(batch, house);
        if (house.grand || (house.shop != null && house.richness > 0.6f)) dressUp(batch, house, walls, door, scale);

        if (house.extensionSide != 0) {
            float centreX = house.extensionSide * (house.width + house.extensionWidth) * 0.5f;
            float centreZ = (house.extensionDepth - house.depth) * 0.5f;
            float extensionOverhang = overhang * 0.6f;
            // Nudged outward a hair so the shared wall never z-fights the house
            float[][] wing = footprint(style, house.extensionWidth + 0.2f, house.extensionDepth,
                    centreX + house.extensionSide * 0.1f, centreZ);
                        HouseWall[] wingWalls = extrudeWalls(batch.builder(wallPart), house, wing, house.extensionWallHeight, 1.0f, false,
                    variant.tileWidth, variant.tileHeight);
            bake(batch.builder(roofPart), roofVertices.get(roofKey(house.nationId, house.form)), roofIndices.get(roofKey(house.nationId, house.form)), house,
                    house.extensionWidth + 2.0f * extensionOverhang, style.roofHeight * scale * style.extensionHeightRatio,
                    house.extensionDepth + 2.0f * extensionOverhang, centreX, house.extensionWallHeight, centreZ, style.roofTileSize);
            HouseWall wingFront = frontWall(wingWalls);
            if (wingFront.length > style.windowWidth * scale * 1.8f) {
                addWindow(batch, house, style, wingFront, 0.5f,
                        house.doorBase + style.wallHeight * scale * style.extensionHeightRatio * 0.45f);
            }
            emitWalls(batch.builder(wallPart), house, wingWalls, variant.tileWidth, variant.tileHeight);
        }
        appendWindows(batch, house, style, walls, front);
        Random signs = new Random(house.seed ^ 0x51C4L);
        if (house.shop != null) shopFront(batch, house, style, front, objects);
        float urbanHere = settlementManager.getUrbanness(house.frame.origin());
        // Posters: on most shops, and on plenty of houses, more in town and in nations keen on them
        float keen = posterKeenness.getOrDefault(house.nationId, 0.6f);
        if (house.shop != null ? signs.nextFloat() < 0.6f + 0.3f * keen : urbanHere > 0.02f && signs.nextFloat() < keen * (0.6f + 0.4f * urbanHere)) {
            wallPoster(house, walls, front, objects, signs);
        }
        // Now and then a flag flies from a short pole above the door, more often in town
        float urbanness = settlementManager.getUrbanness(house.frame.origin());
        if (new Random(house.seed ^ 0xF1A6L).nextFloat() < patriotismOf(house.nationId) * (0.1f + 0.3f * urbanness)) {
            wallFlag(batch, house, front, house.doorBase + style.doorHeight * scale + 3.5f);
        }
        emitWalls(batch.builder(wallPart), house, walls, variant.tileWidth, variant.tileHeight);

        float roofTop = house.baseY + house.mainWallHeight + style.roofHeight * scale;
        batch.include(0f, roofTop, 0f, Math.max(house.plotMaxX - house.plotMinX, house.plotMaxZ - house.plotMinZ));
    }

    // Marks a part drawn weathered: the part's name, this, then how badly (1 to 3)
    private static final String WORN = "~worn";

    /**
     * A well-off house's finery, in the nation's dressing stone: a cornice running round
     * under the roof, and a pillar either side of the door holding up a beam over it.
     */
    private void dressUp(NationBatch batch, House house, HouseWall[] walls, float[][] door, float scale) {
        MeshBuilder stone = batch.builder("finery");
        float out = 1.8f * scale, tall = 2.6f * scale;
        for (HouseWall wall : walls) {
            float[] n = wall.normal;
            float y = wall.topA[1], yB = wall.topB[1];
            float[][] local = {
                    { wall.topA[0], y - tall, wall.topA[2] }, { wall.topB[0], yB - tall, wall.topB[2] },
                    { wall.topB[0] + n[0] * out, yB - tall, wall.topB[2] + n[2] * out }, { wall.topA[0] + n[0] * out, y - tall, wall.topA[2] + n[2] * out },
                    { wall.topA[0], y, wall.topA[2] }, { wall.topB[0], yB, wall.topB[2] },
                    { wall.topB[0] + n[0] * out, yB, wall.topB[2] + n[2] * out }, { wall.topA[0] + n[0] * out, y, wall.topA[2] + n[2] * out } };
            float[][] w = new float[8][];
            for (int i = 0; i < 8; i++) {
                w[i] = new float[] { local[i][0], house.baseY + local[i][1], local[i][2] };
            }
            solidBox(stone, w);
        }
        float[][] c = door;
        if (c == null) return;
        float ax = c[1][0] - c[0][0], az = c[1][2] - c[0][2], width = (float) Math.hypot(ax, az);
        if (width < 1e-3f) return;
        ax /= width;
        az /= width;
        float[] ahead = { 0f, house.depth * 0.5f + 10f };
        float nx = -az, nz = ax;
        float midX = (c[0][0] + c[1][0]) * 0.5f, midZ = (c[0][2] + c[1][2]) * 0.5f;
        if (nx * (ahead[0] - midX) + nz * (ahead[1] - midZ) < 0f) { nx = -nx; nz = -nz; }
        float r = 1.4f * scale, top = c[3][1] + 3.5f * scale, bottom = house.baseY;
        float[][] posts = { { c[0][0] - ax * (r + 1f), c[0][2] - az * (r + 1f) }, { c[1][0] + ax * (r + 1f), c[1][2] + az * (r + 1f) } };
        for (float[] at : posts) {
            float px = at[0] + nx * (r + 0.6f), pz = at[1] + nz * (r + 0.6f);
            solidBox(stone, boxCorners(px, pz, ax, az, nx, nz, r, r, bottom, top));
        }
        // The beam across the tops
        float bx = midX + nx * (r + 0.6f), bz = midZ + nz * (r + 0.6f);
        solidBox(stone, boxCorners(bx, bz, ax, az, nx, nz, width * 0.5f + 2f * r + 1.5f, r * 1.1f, top, top + 2.2f * scale));
    }

    /** The eight corners of an upright box: bottom four then top four, in order round. */
    private static float[][] boxCorners(float cx, float cz, float ax, float az, float nx, float nz, float halfAlong, float halfOut, float y0, float y1) {
        float[][] corners = new float[8][];
        float[][] round = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (int i = 0; i < 4; i++) {
            float x = cx + ax * halfAlong * round[i][0] + nx * halfOut * round[i][1];
            float z = cz + az * halfAlong * round[i][0] + nz * halfOut * round[i][1];
            corners[i] = new float[] { x, y0, z };
            corners[i + 4] = new float[] { x, y1, z };
        }
        return corners;
    }

    /** A closed box from eight corners (bottom four then top four, in order round), faces lit outward. */
    private static void solidBox(MeshBuilder builder, float[][] w) {
        float[] centre = new float[3];
        for (float[] p : w) for (int k = 0; k < 3; k++) centre[k] += p[k] / 8f;
        builder.addConvexFace(centre, w[0], w[1], w[2], w[3]);
        builder.addConvexFace(centre, w[4], w[5], w[6], w[7]);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            builder.addConvexFace(centre, w[i], w[j], w[j + 4], w[i + 4]);
        }
    }

    /** One flat face of a house's walls, from its bottom edge to its (possibly slanted) top edge, in house-local space. */
    private static final class HouseWall {
        float[] bottomA, bottomB, topA, topB;   // {x, y, z}
        float[] normal;                          // outward, house-local
        float length;
        int index;
        // For building the face once its windows are known: normals at each end (smoothed
        // on round buildings), where its texture starts, its slanted height, and the window
        // openings cut through it as {s0, s1, y0, y1} (s along the wall from A to B, 0..1)
        float[] normalA, normalB;
        float u;
        float slant;
        final List<float[]> openings = new ArrayList<>();
    }

    /**
     * The footprint outline in house-local XZ, counter-clockwise seen from above, fitted
     * to a width by depth rectangle centred on (offsetX, offsetZ). Non-box footprints are
     * turned so one edge faces straight down local +Z, where the door goes.
     */
    private static float[][] footprint(BuildingStyle style, float width, float depth, float offsetX, float offsetZ) {
        float hw = width * 0.5f, hd = depth * 0.5f;
        if (style.footprint == BuildingStyle.Footprint.BOX) {
            return new float[][] { { offsetX - hw, offsetZ + hd }, { offsetX + hw, offsetZ + hd },
                                   { offsetX + hw, offsetZ - hd }, { offsetX - hw, offsetZ - hd } };
        }
        int n = style.sides;
        float[][] unit = new float[n][];
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            // Clockwise in (x, z) here equals counter-clockwise seen from above (+Y), as the box is listed
            double angle = Math.PI / 2 + Math.PI / n - 2 * Math.PI * i / n;
            unit[i] = new float[] { (float) Math.cos(angle), (float) Math.sin(angle) };
            minX = Math.min(minX, unit[i][0]); maxX = Math.max(maxX, unit[i][0]);
            minZ = Math.min(minZ, unit[i][1]); maxZ = Math.max(maxZ, unit[i][1]);
        }
        float[][] points = new float[n][];
        for (int i = 0; i < n; i++) {
            float x = (unit[i][0] - (minX + maxX) * 0.5f) / ((maxX - minX) * 0.5f) * hw;
            float z = (unit[i][1] - (minZ + maxZ) * 0.5f) / ((maxZ - minZ) * 0.5f) * hd;
            points[i] = new float[] { offsetX + x, offsetZ + z };
        }
        return points;
    }

    /**
     * Raises walls around a footprint. With taper below 1 the walls lean inward and above 1
     * they flare out; round footprints get smooth normals so they shade as one curved wall.
     * Texture coordinates are in world units divided by the texture's repeat size, so bricks
     * stay the same size on every house however wide or tall it is.
     */
    private HouseWall[] extrudeWalls(MeshBuilder builder, House house, float[][] footprint, float height, float taper,
                                     boolean smooth, float tileWidth, float tileHeight) {
        int n = footprint.length;
        float cx = 0, cz = 0;
        for (float[] p : footprint) { cx += p[0]; cz += p[1]; }
        cx /= n; cz /= n;

        HouseWall[] walls = new HouseWall[n];
        for (int i = 0; i < n; i++) {
            recordSegment(world(house, footprint[i][0], 0f, footprint[i][1]),
                    world(house, footprint[(i + 1) % n][0], 0f, footprint[(i + 1) % n][1]), 0.6f);
        }
        for (int i = 0; i < n; i++) {
            float[] a = footprint[i], b = footprint[(i + 1) % n];
            HouseWall wall = new HouseWall();
            wall.index = i;
            wall.bottomA = new float[] { a[0], 0, a[1] };
            wall.bottomB = new float[] { b[0], 0, b[1] };
            wall.topA = new float[] { cx + (a[0] - cx) * taper, height, cz + (a[1] - cz) * taper };
            wall.topB = new float[] { cx + (b[0] - cx) * taper, height, cz + (b[1] - cz) * taper };
            wall.length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            float[] along = sub(wall.bottomB, wall.bottomA);
            float[] up = sub(mid(wall.topA, wall.topB), mid(wall.bottomA, wall.bottomB));
            float[] normal = normalise(cross(along, up));
            float[] outward = { (a[0] + b[0]) * 0.5f - cx, 0, (a[1] + b[1]) * 0.5f - cz };
            if (dot3(normal, outward) < 0) normal = scale3(normal, -1);
            wall.normal = normal;
            walls[i] = wall;
        }

        float u = 0;
        for (int i = 0; i < n; i++) {
            HouseWall wall = walls[i];
            wall.normalA = wall.normal;
            wall.normalB = wall.normal;
            if (smooth) {
                wall.normalA = normalise(add(wall.normal, walls[(i + n - 1) % n].normal));
                wall.normalB = normalise(add(wall.normal, walls[(i + 1) % n].normal));
            }
            wall.slant = dist(mid(wall.topA, wall.topB), mid(wall.bottomA, wall.bottomB));
            wall.u = u;
            u += wall.length;
        }
        return walls;
    }

    /** A point on a wall's face: s along it from A to B (0..1) at height y, house-local. */
    private static float[] wallPoint(HouseWall wall, float s, float y) {
        float[] bottom = lerp(wall.bottomA, wall.bottomB, s);
        float[] top = lerp(wall.topA, wall.topB, s);
        float[] p = lerp(bottom, top, Math.max(0f, Math.min(1f, y / wall.topA[1])));
        p[1] = y;
        return p;
    }

    /**
     * Builds the walls' faces, leaving a hole wherever a window was cut: the wall is split
     * into horizontal bands at the windows' tops and bottoms, and every band is cut at the
     * sides of every window (on any floor), the pieces inside a window left out. Cutting each
     * band at the same places means neighbouring bands share their corners exactly: a long
     * piece meeting several short ones along a floor line would leave a hairline crack there.
     */
    private void emitWalls(MeshBuilder builder, House house, HouseWall[] walls, float tileWidth, float tileHeight) {
        for (HouseWall wall : walls) {
            float height = wall.topA[1];
            java.util.TreeSet<Float> levels = new java.util.TreeSet<>();
            java.util.TreeSet<Float> cuts = new java.util.TreeSet<>();
            levels.add(0f);
            levels.add(height);
            cuts.add(0f);
            cuts.add(1f);
            for (float[] o : wall.openings) {
                levels.add(Math.max(0f, Math.min(height, o[2])));
                levels.add(Math.max(0f, Math.min(height, o[3])));
                cuts.add(Math.max(0f, Math.min(1f, o[0])));
                cuts.add(Math.max(0f, Math.min(1f, o[1])));
            }
            Float[] ys = levels.toArray(new Float[0]);
            Float[] ss = cuts.toArray(new Float[0]);
            for (int b = 0; b + 1 < ys.length; b++) {
                float ya = ys[b], yb = ys[b + 1];
                if (yb - ya < 1e-4f) continue;
                for (int c = 0; c + 1 < ss.length; c++) {
                    float sa = ss[c], sb = ss[c + 1];
                    if (sb - sa < 1e-4f) continue;
                    boolean hole = false;
                    for (float[] o : wall.openings) {
                        if (o[2] <= ya + 1e-4f && o[3] >= yb - 1e-4f && o[0] <= sa + 1e-4f && o[1] >= sb - 1e-4f) {
                            hole = true;
                            break;
                        }
                    }
                    if (!hole) wallPiece(builder, house, wall, sa, sb, ya, yb, tileWidth, tileHeight);
                }
            }
        }
    }

    private void wallPiece(MeshBuilder builder, House house, HouseWall wall, float s0, float s1, float y0, float y1,
                           float tileWidth, float tileHeight) {
        if (s1 - s0 < 1e-4f) return;
        float height = wall.topA[1];
        float[][] corners = { wallPoint(wall, s0, y0), wallPoint(wall, s1, y0), wallPoint(wall, s1, y1), wallPoint(wall, s0, y1) };
        float[] ss = { s0, s1, s1, s0 }, yy = { y0, y0, y1, y1 };
        int[] index = new int[4];
        for (int i = 0; i < 4; i++) {
            float[] n = normalise(lerp(wall.normalA, wall.normalB, ss[i]));
            index[i] = houseVertex(builder, house, corners[i], n, (wall.u + ss[i] * wall.length) / tileWidth,
                    wall.slant * (yy[i] / height) / tileHeight);
        }
        addOutwardQuad(builder, house, index[0], index[1], index[2], index[3], corners[0], corners[1], corners[2], wall.normal);
    }

    /**
     * Roofs for round and polygonal houses, centred over the footprint: a flat slab, a
     * pyramid or cone, a tall spire, or a dome. Texture coordinates run around the eaves and
     * up the slope in world units.
     */
    private void centredRoof(MeshBuilder builder, House house, float[][] footprint, BuildingStyle style, float wallTop,
                             float overhang, boolean smooth) {
        int n = footprint.length;
        float cx = 0, cz = 0, radius = 0;
        for (float[] p : footprint) { cx += p[0]; cz += p[1]; }
        cx /= n; cz /= n;
        for (float[] p : footprint) radius += Math.hypot(p[0] - cx, p[1] - cz);
        radius /= n;
        float grow = style.taper * (1.0f + overhang / Math.max(1.0f, radius * style.taper));
        float roofHeight = style.roofHeight * house.sizeScale;
        float tile = style.roofTileSize;

        float[][] eave = new float[n][];
        for (int i = 0; i < n; i++) {
            eave[i] = new float[] { cx + (footprint[i][0] - cx) * grow, wallTop, cz + (footprint[i][1] - cz) * grow };
        }
        float[] centreBelow = { cx, wallTop, cz };

        if (style.roofType == BuildingStyle.RoofType.DOME) {
            int rings = 6;
            float[][] previous = eave;
            float uRingLength = 0;
            for (int i = 0; i < n; i++) uRingLength += dist(eave[i], eave[(i + 1) % n]);
            for (int r = 1; r <= rings; r++) {
                double phi = Math.PI / 2 * r / rings;
                float ringScale = (float) Math.cos(phi);
                float y = wallTop + roofHeight * (float) Math.sin(phi);
                float[][] ring = new float[n][];
                for (int i = 0; i < n; i++) {
                    ring[i] = new float[] { cx + (eave[i][0] - cx) * ringScale, y, cz + (eave[i][2] - cz) * ringScale };
                }
                float vLow = (r - 1) * (float) (Math.PI / 2 / rings) * radius / tile;
                float vHigh = r * (float) (Math.PI / 2 / rings) * radius / tile;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    float u0 = uRingLength * i / n / tile, u1 = uRingLength * (i + 1) / n / tile;
                    float[] lowA = previous[i], lowB = previous[j], highA = ring[i], highB = ring[j];
                    float[] nLowA = domeNormal(lowA, cx, cz, wallTop, radius * grow, roofHeight);
                    float[] nLowB = domeNormal(lowB, cx, cz, wallTop, radius * grow, roofHeight);
                    float[] nHighA = domeNormal(highA, cx, cz, wallTop, radius * grow, roofHeight);
                    float[] nHighB = domeNormal(highB, cx, cz, wallTop, radius * grow, roofHeight);
                    int a = houseVertex(builder, house, lowA, nLowA, u0, vLow);
                    int b = houseVertex(builder, house, lowB, nLowB, u1, vLow);
                    int c = houseVertex(builder, house, highB, nHighB, u1, vHigh);
                    int d = houseVertex(builder, house, highA, nHighA, u0, vHigh);
                    float[] faceNormal = add(add(nLowA, nLowB), add(nHighA, nHighB));
                    addOutwardQuad(builder, house, a, b, c, d, lowA, lowB, highB, faceNormal);
                }
                previous = ring;
            }
        } else if (style.roofType == BuildingStyle.RoofType.FLAT) {
            float[][] top = new float[n][];
            for (int i = 0; i < n; i++) top[i] = new float[] { eave[i][0], wallTop + roofHeight, eave[i][2] };
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float[] normal = normalise(new float[] { (eave[i][0] + eave[j][0]) * 0.5f - cx, 0, (eave[i][2] + eave[j][2]) * 0.5f - cz });
                float len = dist(eave[i], eave[j]) / tile;
                int a = houseVertex(builder, house, eave[i], normal, 0, 0);
                int b = houseVertex(builder, house, eave[j], normal, len, 0);
                int c = houseVertex(builder, house, top[j], normal, len, roofHeight / tile);
                int d = houseVertex(builder, house, top[i], normal, 0, roofHeight / tile);
                addOutwardQuad(builder, house, a, b, c, d, eave[i], eave[j], top[j], normal);
            }
            flatCap(builder, house, top, new float[] { 0, 1, 0 }, tile);
        } else {
            // Pyramid or spire: every eave edge slopes up to a single apex
            float[] apex = { cx, wallTop + roofHeight, cz };
            float slant = (float) Math.hypot(radius * grow, roofHeight) / tile;
            float u = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float edge = dist(eave[i], eave[j]);
                float[] faceNormal = normalise(cross(sub(eave[j], eave[i]), sub(apex, eave[i])));
                float[] outward = { (eave[i][0] + eave[j][0]) * 0.5f - cx, 0.5f, (eave[i][2] + eave[j][2]) * 0.5f - cz };
                if (dot3(faceNormal, outward) < 0) faceNormal = scale3(faceNormal, -1);
                float[] nA = faceNormal, nB = faceNormal, nApex = faceNormal;
                if (smooth) {
                    nA = normalise(new float[] { eave[i][0] - cx, radius * grow / Math.max(1f, roofHeight) * 0.6f, eave[i][2] - cz });
                    nB = normalise(new float[] { eave[j][0] - cx, radius * grow / Math.max(1f, roofHeight) * 0.6f, eave[j][2] - cz });
                }
                int a = houseVertex(builder, house, eave[i], nA, u / tile, 0);
                int b = houseVertex(builder, house, eave[j], nB, (u + edge) / tile, 0);
                int c = houseVertex(builder, house, apex, nApex, (u + edge * 0.5f) / tile, slant);
                addOutwardTriangle(builder, house, a, b, c, eave[i], eave[j], apex, faceNormal);
                u += edge;
            }
        }
        // Underside of the overhang
        flatCap(builder, house, eave, new float[] { 0, -1, 0 }, tile);
    }

    private static float[] domeNormal(float[] p, float cx, float cz, float baseY, float radius, float height) {
        // Gradient of the ellipsoid ((x - cx)^2 + (z - cz)^2) / r^2 + (y - baseY)^2 / h^2 = 1
        return normalise(new float[] { (p[0] - cx) / (radius * radius), (p[1] - baseY) / Math.max(1f, height * height),
                                       (p[2] - cz) / (radius * radius) });
    }

    /** A convex polygon facing straight up or down, as a triangle fan with planar texture coordinates. */
    private void flatCap(MeshBuilder builder, House house, float[][] ring, float[] normal, float tile) {
        int first = -1;
        for (float[] p : ring) {
            int index = houseVertex(builder, house, p, normal, p[0] / tile, p[2] / tile);
            if (first < 0) first = index;
        }
        for (int i = 1; i < ring.length - 1; i++) {
            addOutwardTriangle(builder, house, first, first + i, first + i + 1, ring[0], ring[i], ring[i + 1], normal);
        }
    }

    /** The wall whose outward normal points most nearly down local +Z, toward the road. */
    private static HouseWall frontWall(HouseWall[] walls) {
        HouseWall best = walls[0];
        for (HouseWall wall : walls) {
            if (wall.normal[2] > best.normal[2]) best = wall;
        }
        return best;
    }

    /** Rows of windows on every wall, one row per floor, leaving room for the front door. */
    private void appendWindows(NationBatch batch, House house, BuildingStyle style, HouseWall[] walls, HouseWall front) {
        float scale = house.sizeScale;
        float windowWidth = style.windowWidth * scale;
        float floorHeight = style.wallHeight * scale / style.floors;
        float doorClearance = style.doorWidth * scale * 0.5f + windowWidth * 0.5f + 3.0f;
        boolean box = style.footprint == BuildingStyle.Footprint.BOX;
        boolean round = style.footprint == BuildingStyle.Footprint.ROUND;

        for (int floor = 0; floor < style.floors; floor++) {
            float centreY = house.doorBase + floorHeight * (floor + 0.55f);
            for (HouseWall wall : walls) {
                if (box && wall.normal[0] * house.extensionSide > 0.5f && floor == 0) {
                    continue;   // the extension covers this wall's ground floor
                }
                int count;
                if (round) {
                    // Curved walls: one window on every other facet, clear of the door
                    int offset = Math.abs(wall.index - front.index);
                    count = (wall.index % 2 == 0 && wall.length > windowWidth * 0.6f && (floor > 0 || offset > 1)) ? 1 : 0;
                } else if (box) {
                    boolean sideWall = Math.abs(wall.normal[0]) > 0.5f;
                    count = sideWall ? style.windowsSide : (wall == front && floor > 0 ? style.windowsFront + 1 : style.windowsFront);
                } else {
                    count = Math.min(3, (int) (wall.length / (windowWidth * 2.4f)));
                }
                if (count <= 0) continue;
                // A shop's ground floor front is its shop window
                if (house.shop != null && wall == front && floor == 0) continue;
                float margin = Math.min(0.45f, (windowWidth * 0.5f + 4.0f) / wall.length);
                if (wall == front && floor == 0) {
                    // Ground-floor front windows sit in two groups either side of the door
                    float gap = doorClearance / wall.length;
                    placeBetween(batch, house, style, wall, margin, 0.5f - gap, (count + 1) / 2, centreY);
                    placeBetween(batch, house, style, wall, 0.5f + gap, 1 - margin, count / 2, centreY);
                } else {
                    placeBetween(batch, house, style, wall, margin, 1 - margin, count, centreY);
                }
            }
        }
    }

    /** Evenly spaces windows between two fractions along a wall, if they fit. */
    private void placeBetween(NationBatch batch, House house, BuildingStyle style, HouseWall wall,
                              float from, float to, int count, float centreY) {
        // A single window just needs its own width (it is clamped to the wall); rows need the span
        if (count <= 0 || (count > 1 && (to - from) * wall.length < style.windowWidth * house.sizeScale * 0.8f)
                || to <= from) {
            return;
        }
        for (int i = 0; i < count; i++) {
            float s = count == 1 ? (from + to) * 0.5f : from + (to - from) * i / (count - 1);
            addWindow(batch, house, style, wall, s, centreY);
        }
    }

    /** A glass pane, with an optional frame behind it, set into a wall at fraction s along it. */
    /**
     * A window: a hole through the wall, lined (sides, sill and head) back to a dark panel,
     * with drawn curtains hanging in folds just in front of that, a pane of glass set a
     * little back from the wall's face, and a frame round the outside.
     */
    private void addWindow(NationBatch batch, House house, BuildingStyle style, HouseWall wall, float s, float centreY) {
        float halfW = Math.min(style.windowWidth * house.sizeScale, wall.length * 0.8f) * 0.5f;
        float halfH = style.windowHeight * house.sizeScale * 0.5f;
        float s0 = Math.max(0.02f, s - halfW / wall.length), s1 = Math.min(0.98f, s + halfW / wall.length);
        float y0 = centreY - halfH, y1 = Math.min(centreY + halfH, wall.topA[1] - 0.5f);
        if (s1 - s0 < 0.02f || y1 - y0 < 1f) return;
        wall.openings.add(new float[] { s0, s1, y0, y1 });

        float[] n = wall.normal;
        // Behind the opening, the household's drawn curtains, painted on a panel (see
        // ArchitectureTextures.curtains), with the opening lined from the wall's face back to it
        // all round, so nothing shows through round the curtains
        int colour = (int) Math.floorMod(house.seed, (long) CURTAIN_COLOURS);
        float[] along = normalise(sub(wallPoint(wall, s1, y0), wallPoint(wall, s0, y0)));
        float[] up = { 0f, 1f, 0f };
        MeshBuilder reveal = batch.builder("frame");
        localQuad(reveal, house, inset(wall, s0, y0, 0), inset(wall, s0, y1, 0), inset(wall, s0, y1, CURTAIN_DEPTH), inset(wall, s0, y0, CURTAIN_DEPTH), along);
        localQuad(reveal, house, inset(wall, s1, y0, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, CURTAIN_DEPTH), inset(wall, s1, y0, CURTAIN_DEPTH), scale3(along, -1));
        localQuad(reveal, house, inset(wall, s0, y0, 0), inset(wall, s1, y0, 0), inset(wall, s1, y0, CURTAIN_DEPTH), inset(wall, s0, y0, CURTAIN_DEPTH), up);
        localQuad(reveal, house, inset(wall, s0, y1, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, CURTAIN_DEPTH), inset(wall, s0, y1, CURTAIN_DEPTH), scale3(up, -1));
        localQuad(batch.builder("blind" + colour), house, inset(wall, s0, y0, CURTAIN_DEPTH), inset(wall, s1, y0, CURTAIN_DEPTH),
                inset(wall, s1, y1, CURTAIN_DEPTH), inset(wall, s0, y1, CURTAIN_DEPTH), n);
        // (a tower's hundreds of upper windows have no frames: far too much to build)
        float floorHeight = style.wallHeight * house.sizeScale / style.floors;
        if (style.tower && centreY - house.doorBase > floorHeight * TOWER_DETAILED_FLOORS) return;

        // The frame: a flat surround just proud of the wall
        float f = style.frameSize;
        if (f > 0f) {
            float fs = f / wall.length;
            MeshBuilder frame = batch.builder("frame");
            float[][][] strips = {
                { { s0 - fs, y1 }, { s1 + fs, y1 }, { s1 + fs, y1 + f }, { s0 - fs, y1 + f } },
                { { s0 - fs, y0 - f }, { s1 + fs, y0 - f }, { s1 + fs, y0 }, { s0 - fs, y0 } },
                { { s0 - fs, y0 }, { s0, y0 }, { s0, y1 }, { s0 - fs, y1 } },
                { { s1, y0 }, { s1 + fs, y0 }, { s1 + fs, y1 }, { s1, y1 } } };
            for (float[][] strip : strips) {
                float[][] c = new float[4][];
                for (int i = 0; i < 4; i++) c[i] = inset(wall, Math.max(0f, Math.min(1f, strip[i][0])), strip[i][1], -FRAME_OFFSET);
                localQuad(frame, house, c[0], c[1], c[2], c[3], n);
            }
        }
    }

    // How many of a tower's lowest floors have framed windows
    private static final int TOWER_DETAILED_FLOORS = 2;
    // How deep a window's curtains are set back into the wall
    private static final float CURTAIN_DEPTH = 1.0f;
    // How far back a shop window's glass sits
    private static final float GLASS_SETBACK = 0.4f;
    private static final int CURTAIN_COLOURS = 4;

    /** A point on the wall's face pushed straight in (or out, if depth is negative) from it. */
    private static float[] inset(HouseWall wall, float s, float y, float depth) {
        return sub(wallPoint(wall, s, y), scale3(wall.normal, depth));
    }

    // ------------------------------------------------------------------ SHOPS

    /**
     * Shops gather into commercial districts: each town has a shopping centre round its
     * middle, bigger and busier the bigger the town, where most buildings are shops; from
     * there shopping streets run on along the roads, a shop's neighbours on the same road
     * likely shops too; and here and there, a lone corner shop in the suburbs.
     */
    private void chooseShops() {
        Map<House, Random> rolls = new HashMap<>();
        for (House house : houses) rolls.put(house, new Random(house.seed ^ 0x5409L));
        // The town centre
        for (House house : houses) {
            if (!canBeShop(house)) continue;
            SettlementManager.Settlement town = settlementManager.dominantSettlementAt(house.frame.origin());
            if (town == null) continue;
            // (along the surface, as the town's radius is)
            float distance = (float) SphereMath.distance(house.frame.origin(), town.centre);
            // Villages have a shop or two at their heart; cities a district a few streets across
            float size = Math.min(1f, town.radius / 1600f);
            float core = town.radius * (0.55f + 0.45f * size);
            float chance = (0.45f + 0.5f * size) * (float) Math.exp(-(distance / core) * (distance / core));
            if (rolls.get(house).nextFloat() < chance) makeShop(house, rolls.get(house));
        }
        // Shopping streets: next door to a shop, on the same road, is likely another
        Map<Integer, List<House>> byRoad = new HashMap<>();
        for (House house : houses) byRoad.computeIfAbsent(house.frontagePath, k -> new ArrayList<>()).add(house);
        for (int pass = 0; pass < 4; pass++) {
            List<House> joining = new ArrayList<>();
            for (List<House> road : byRoad.values()) {
                for (House house : road) {
                    if (house.shop != null || !canBeShop(house)) continue;
                    for (House other : road) {
                        if (other.shop == null) continue;
                        if (nextDoor(house, other)) {
                            if (rolls.get(house).nextFloat() < 0.6f * settlementManager.getUrbanness(house.frame.origin()) + 0.25f) joining.add(house);
                            break;
                        }
                    }
                }
            }
            for (House house : joining) makeShop(house, rolls.get(house));
        }
        // The odd corner shop out in the suburbs
        for (House house : houses) {
            if (house.shop == null && canBeShop(house) && settlementManager.getUrbanness(house.frame.origin()) > 0.25f
                    && rolls.get(house).nextFloat() < 0.012f) {
                makeShop(house, rolls.get(house));
            }
        }
        int lone = 0;
        for (Shop shop : shops) {
            boolean neighbour = false;
            for (House other : byRoad.get(shop.house.frontagePath)) {
                if (other == shop.house || other.shop == null) continue;
                if (nextDoor(shop.house, other)) { neighbour = true; break; }
            }
            if (!neighbour) lone++;
        }
        System.out.printf("[SHOPS] %d shops of %d buildings (%.1f%%), %d on their own%n", shops.size(), houses.size(),
                100f * shops.size() / Math.max(1, houses.size()), lone);
    }

    /** Whether two buildings on the same road are next door to (or straight across from) each other. */
    private static boolean nextDoor(House a, House b) {
        SpherePosition pa = a.frame.origin(), pb = b.frame.origin();
        double dx = pa.x() - pb.x(), dy = pa.y() - pb.y(), dz = pa.z() - pb.z();
        float reach = ((a.plotMaxX - a.plotMinX) + (b.plotMaxX - b.plotMinX)) * 0.5f + 60f;
        return dx * dx + dy * dy + dz * dz < (double) reach * reach;
    }

    /** A building with a front wide enough for a shop window either side of the door. */
    private boolean canBeShop(House house) {
        BuildingStyle style = styleOf(house);
        // (towers are offices and flats, not shops)
        if (style.tower) return false;
        return frontLength(style, house) >= style.doorWidth * house.sizeScale * 2.2f + 10f;
    }

    private void makeShop(House house, Random rand) {
        if (house.shop != null) return;
        growShop(house);
        house.shop = new Shop(house, rand);
        shops.add(house.shop);
        // Open to the street: no fence round a shop, and customers park in the road
        house.fenced = false;
        house.secure = false;
        house.drivewaySide = 0;
    }

    /**
     * A shop where people are well off is bigger: grown (taller too) as far as its plot allows,
     * keeping some front garden and side and back yards.
     */
    private void growShop(House house) {
        if (house.richness < 0.3f) return;
        BuildingStyle style = styleOf(house);
        float overhang = style.roofOverhang * house.sizeScale;
        float span = house.houseMaxX() - house.houseMinX();
        float side = Math.min(house.houseMinX() - house.plotMinX, house.plotMaxX - house.houseMaxX()) - overhang - 3f;
        float front = house.setback - 8f;
        float back = (-house.depth * 0.5f - overhang) - house.plotMinZ - 3f;
        float grow = 1f + 0.6f * (house.richness - 0.3f);
        grow = Math.min(grow, 1f + 2f * Math.max(0f, side) / span);
        grow = Math.min(grow, 1f + 2f * Math.max(0f, Math.min(front, back)) / house.depth);
        if (grow < 1.05f) return;
        float[] before = { house.sizeScale, house.width, house.depth, house.extensionWidth, house.extensionDepth, house.setback };
        house.sizeScale *= grow;
        house.width *= grow;
        house.depth *= grow;
        house.extensionWidth *= grow;
        house.extensionDepth *= grow;
        house.setback -= (grow - 1f) * before[2] * 0.5f;
        if (!settleOnGround(house, style)) {
            house.sizeScale = before[0];
            house.width = before[1];
            house.depth = before[2];
            house.extensionWidth = before[3];
            house.extensionDepth = before[4];
            house.setback = before[5];
            settleOnGround(house, style);
        }
    }

    /** How long the wall facing the road is. */
    private static float frontLength(BuildingStyle style, House house) {
        float[][] outline = footprint(style, house.width, house.depth, 0f, 0f);
        float best = -Float.MAX_VALUE, length = 0f;
        for (int i = 0; i < outline.length; i++) {
            float[] a = outline[i], b = outline[(i + 1) % outline.length];
            float midZ = (a[1] + b[1]) * 0.5f;
            if (midZ > best) {
                best = midZ;
                length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
        }
        return length;
    }

    /** A developer aid: {x, z, lookX, lookZ} out in the road in front of a random shop, facing it; null if none. */
    public float[] shopViewpoint(Random rand) {
        if (shops.isEmpty()) return null;
        Shop shop = shops.get(rand.nextInt(shops.size()));
        Doorway door = doorwayOf(shop.house);
        float dx = door.kerbX - door.doorX, dz = door.kerbZ - door.doorZ;
        float length = Math.max(1e-3f, (float) Math.hypot(dx, dz));
        dx /= length;
        dz /= length;
        float back = Float.parseFloat(System.getProperty("xenoguesser.shopdistance", "50"));
        float side = Float.parseFloat(System.getProperty("xenoguesser.shopside", "0"));
        return new float[] { door.kerbX + dx * back + door.roadDirX * side, door.kerbZ + dz * back + door.roadDirZ * side, -dx, -dz };
    }

    /**
     * Developer aid: beside a raised stretch of road, on its lower side some way off,
     * looking at its retaining wall, as {x, z, lookX, lookZ}; null if there's none.
     */
    public float[] raisedRoadViewpoint(Random rand) {
        float[] best = null;
        float bestDrop = 3f;
        for (int attempt = 0; attempt < 4000 && !landSegments.isEmpty(); attempt++) {
            RoadSegment segment = landSegments.get(rand.nextInt(landSegments.size()));
            // (in the segment's frame, whose x and z run east and south as the chart's do)
            float mx = (segment.start.x + segment.end.x) * 0.5f, mz = (segment.start.z + segment.end.z) * 0.5f;
            float dx = segment.end.x - segment.start.x, dz = segment.end.z - segment.start.z;
            float length = (float) Math.hypot(dx, dz);
            if (length < 1f) continue;
            float sideX = -dz / length, sideZ = dx / length, half = segment.width * 0.5f;
            float deck = roadSurfaceY(world(segment, mx, mz));
            for (float sign = -1f; sign <= 1f; sign += 2f) {
                float ex = mx + sideX * half * sign, ez = mz + sideZ * half * sign;
                float drop = deck - groundAt(segment, ex, ez);
                if (drop > bestDrop && drop < 12f) {
                    bestDrop = drop;
                    float out = 70f;
                    float[] eye = chartAt(segment, ex + sideX * sign * out, ez + sideZ * sign * out);
                    best = new float[] { eye[0], eye[1], -sideX * sign, -sideZ * sign };
                }
            }
        }
        return best;
    }

    /** Developer aid: standing on a dirt track looking along it, as {x, z, lookX, lookZ}; null if there's none. */
    public float[] trackViewpoint(Random rand) {
        for (int attempt = 0; attempt < 20000 && !landSegments.isEmpty(); attempt++) {
            RoadSegment segment = landSegments.get(rand.nextInt(landSegments.size()));
            if (segment.roadClass != RoadPath.RoadClass.DIRT || segment.length < 1f) continue;
            float dx = (segment.chartEnd.x - segment.chartStart.x) / segment.chartLength(), dz = (segment.chartEnd.z - segment.chartStart.z) / segment.chartLength();
            return new float[] { segment.chartStart.x, segment.chartStart.z, dx, dz };
        }
        return null;
    }

    /** The shop nearest a place within reach, preferring one in the same nation; null if none. */
    public Shop nearestShop(SpherePosition place, float reach, int nationId) {
        Shop best = null;
        double bestScore = (double) reach * reach;
        SpherePosition at = SphereMath.atAltitude(place, 0.0);
        for (Shop shop : shopIndex.near(at, reach)) {
            double d = SphereMath.distance(shop.house.frame.origin(), at);
            double score = d * d * (shop.nationId == nationId ? 1.0 : 4.0);
            if (score < bestScore) { bestScore = score; best = shop; }
        }
        return best;
    }

    /** A shop's name, in its nation's letters: a word or two, short enough for any shop front. */
    public int[] shopName(Shop shop) {
        if (shop.name != null) return shop.name;
        int maxGlyphs = Math.max(2, mainListener != null ? mainListener.getNationAtlasSize(shop.nationId) : 10);
        Random rand = new Random(shop.house.seed ^ 0x4A3EL);
        boolean vertical = scripts.direction(shop.nationId) >= 2;
        List<Integer> letters = new ArrayList<>();
        int words = vertical ? 1 : (rand.nextFloat() < 0.4f ? 2 : 1);
        for (int w = 0; w < words; w++) {
            if (w > 0) letters.add(0);
            int count = vertical ? 2 + rand.nextInt(4) : 3 + rand.nextInt(4);
            for (int i = 0; i < count; i++) letters.add(1 + rand.nextInt(maxGlyphs - 1));
        }
        int[] name = new int[Math.min(InfrastructureObject.TITLE_CAPACITY, letters.size())];
        for (int i = 0; i < name.length; i++) name[i] = letters.get(i);
        shop.name = name;
        return name;
    }

    /**
     * A way on foot from one place to another by the shortest way along the roads, keeping
     * to the side of the road as on a pavement: {x, z} points. Empty if there's none.
     */
    public List<float[]> walkingRoute(float ax, float az, float bx, float bz) {
        List<float[]> way = routes().route(ax, az, bx, bz);
        List<float[]> kept = new ArrayList<>(way.size());
        for (int i = 0; i < way.size(); i++) {
            float[] p = way.get(i);
            if (i == 0 || i == way.size() - 1) {
                kept.add(p);
                continue;
            }
            float[] before = way.get(i - 1), after = way.get(i + 1);
            float dx = after[0] - before[0], dz = after[1] - before[1];
            float length = (float) Math.hypot(dx, dz);
            if (length < 1e-3f) continue;
            // On the pavement, just off the edge of the carriageway where the traffic runs
            float aside = roadAtPoint(p[0], p[1])[0] + 1.3f;
            kept.add(new float[] { p[0] - dz / length * aside, p[1] + dx / length * aside });
        }
        return kept;
    }

    /**
     * The road at a point: {half its width, its rank (2 highway, 1 street, 0 lane or track),
     * the nation it runs through, 1 if a dirt track}, from the nearest road; a lane's if none is near.
     */
    public float[] roadAtPoint(float x, float z) {
        RoadSegment best = null;
        float nearest = Float.MAX_VALUE;
        SpherePosition place = sphere(x, z);
        for (RoadSegment segment : segmentsNear(place, MAX_ROAD_HALF_WIDTH + 20f)) {
            float d = distanceSquaredToSegment(place, segment);
            if (d < nearest) { nearest = d; best = segment; }
        }
        if (best == null) return new float[] { RoadPath.RoadClass.LANE.width * 0.5f, 0f, 1f, 0f };
        return new float[] { best.width * 0.5f, best.rank(), best.nationId, best.roadClass == RoadPath.RoadClass.DIRT ? 1f : 0f };
    }

    /** The shortest way along the roads between two points, along the roads' middles: {x, z} points. */
    public List<float[]> drivingRoute(float ax, float az, float bx, float bz) {
        return routes().route(ax, az, bx, bz);
    }

    /**
     * Somewhere for a drive out: the middle of another settlement, between minDistance and
     * maxDistance away, as {x, z}; null if there's none.
     */
    public float[] settlementBetween(float x, float z, float minDistance, float maxDistance, Random rand) {
        List<SettlementManager.Settlement> all = settlementManager.getSettlements();
        List<SettlementManager.Settlement> fit = new ArrayList<>();
        for (SettlementManager.Settlement s : all) {
            float d = (float) Planet.surfaceDistance(x, z, s.chartX, s.chartZ);
            if (d >= minDistance && d <= maxDistance) fit.add(s);
        }
        if (fit.isEmpty()) return null;
        SettlementManager.Settlement pick = fit.get(rand.nextInt(fit.size()));
        return new float[] { pick.chartX, pick.chartZ };
    }

    /**
     * A place to leave a vehicle width wide at the edge of the road nearest (x, z), on the side
     * towards (towardsX, towardsZ): pulled right over, partly up on the verge on a narrow road,
     * so it never stands in the way. Returns {x, z, along x, along z (the road's direction
     * there), out x, out z (unit, towards the edge it's at), the road's half width, its
     * nation}; null if that's at a junction (or no road is near).
     */
    public float[] kerbSpot(float x, float z, float towardsX, float towardsZ, float width) {
        RoadSegment best = null;
        float nearest = Float.MAX_VALUE;
        SpherePosition place = sphere(x, z);
        for (RoadSegment segment : segmentsNear(place, MAX_ROAD_HALF_WIDTH + 30f)) {
            if (segment.culDeSac) continue;
            float d = distanceSquaredToSegment(place, segment);
            if (d < nearest) { nearest = d; best = segment; }
        }
        if (best == null) return null;
        float[] centre = nearestOnSegment(x, z, best);
        float ax = best.chartEnd.x - best.chartStart.x, az = best.chartEnd.z - best.chartStart.z, length = (float) Math.hypot(ax, az);
        if (length < 1e-3f) return null;
        ax /= length;
        az /= length;
        float nx = -az, nz = ax;
        if (nx * (towardsX - centre[0]) + nz * (towardsZ - centre[1]) < 0f) { nx = -nx; nz = -nz; }
        float half = best.width * 0.5f;
        // Up on the verge a little where there's one, but inside the crash barrier where there's that
        float out = best.railed || best.roadClass == RoadPath.RoadClass.HIGHWAY
                ? Math.max(half * 0.32f, half - width * 0.5f - 2f)
                : Math.max(half * 0.32f, half + 2.5f - width * 0.5f);
        // (out from the middle by a true distance, stretched to the chart's)
        float stretch = chartStretch(centre[1]);
        float px = centre[0] + nx * out * stretch, pz = centre[1] + nz * out * stretch;
        // Not across the mouth of another road
        SpherePosition spot = sphere(px, pz);
        for (RoadSegment other : segmentsNear(spot, MAX_ROAD_HALF_WIDTH + width + 10f)) {
            if (other.pathIndex == best.pathIndex || other.culDeSac) continue;
            float reach = other.width * 0.5f + width * 0.5f + 6f;
            if (distanceSquaredToSegment(spot, other) < reach * reach) return null;
        }
        return new float[] { px, pz, ax, az, nx, nz, half, best.nationId };
    }

    /**
     * A point on a road's middle between minDistance and maxDistance from (x, z), likelier on
     * bigger roads (highways most of all, dirt tracks hardly ever), as {x, z}; null if none.
     */
    public float[] roadPointBetween(float x, float z, float minDistance, float maxDistance, Random rand) {
        List<RoadSegment> fit = new ArrayList<>();
        List<Float> weights = new ArrayList<>();
        float total = 0f;
        SpherePosition place = sphere(x, z);
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
                float t = rand.nextFloat();
                return new float[] { s.chartStart.x + (s.chartEnd.x - s.chartStart.x) * t, s.chartStart.z + (s.chartEnd.z - s.chartStart.z) * t };
            }
        }
        return null;
    }

    /** Which side of the road a nation drives on: 1 the right, -1 the left. */
    public float drivingSide(int nationId) {
        return nationRandom(71L, nationId).nextFloat() < 0.6f ? 1f : -1f;
    }

    /** The door of the shop nearest a point, within reach; null if there's none. */
    public Doorway shopDoorNear(float x, float z, float reach, int nationId) {
        Shop shop = nearestShop(sphere(x, z), reach, nationId);
        return shop == null ? null : doorwayOf(shop.house);
    }

    private synchronized RoadRoutes routes() {
        if (routes == null) {
            long start = System.currentTimeMillis();
            routes = new RoadRoutes(this);
            System.out.printf("[ROADS] Route graph ready in %d ms%n", System.currentTimeMillis() - start);
        }
        return routes;
    }

    private static final float DISPLAY_DEPTH = 7f;

    /** The ground floor of a shop's front: two big display windows either side of the door, and its name above. */
    private void shopFront(NationBatch batch, House house, BuildingStyle style, HouseWall front, List<InfrastructureObject> objects) {
        float scale = house.sizeScale;
        float floorHeight = style.wallHeight * scale / style.floors;
        float wallTop = front.topA[1];
        float doorHalfW = Math.min(style.doorWidth * scale, front.length * 0.7f) * 0.5f;
        float doorH = style.doorHeight * scale;
        float fasciaH = 4.2f;
        float upperBottom = style.floors > 1 ? house.doorBase + floorHeight * 1.55f - style.windowHeight * scale * 0.5f : wallTop;
        float y0 = house.doorBase + 1.3f;
        float y1 = house.doorBase + Math.max(doorH + 1f, floorHeight * 0.8f);
        y1 = Math.min(y1, Math.min(upperBottom, wallTop) - fasciaH - 1.0f);
        if (y1 - y0 < 4f) y1 = y0 + 4f;
        float gap = (doorHalfW + 1.4f) / front.length, margin = 1.6f / front.length;
        Random rand = new Random(house.seed ^ 0x5D0FL);
        displayWindow(batch, house, style, front, margin, 0.5f - gap, y0, y1, rand);
        displayWindow(batch, house, style, front, 0.5f + gap, 1f - margin, y0, y1, rand);

        // The name: across a board over the windows, or for a script that runs down the page,
        // on a tall board sticking out from the corner into the street
        int[] name = shopName(house.shop);
        boolean vertical = scripts.direction(house.nationId) >= 2;
        float[] n = horizontal(front.normal);
        InfrastructureObject sign;
        if (!vertical) {
            float yc = y1 + 0.5f + fasciaH * 0.5f;
            float[] at = add(wallPoint(front, 0.5f, yc), scale3(n, 0.45f + standOff(front, 0.5f, yc, fasciaH)));
            float width = Math.min(70f, front.length * (1f - 2f * margin) * 0.95f);
            sign = InfrastructureObject.createWallSign(fixedTo(house, at, n), chartVector(house, at), house.nationId,
                    width, fasciaH, true, false, null, 0f, 0f);
        } else {
            float side = rand.nextBoolean() ? 0.06f : 0.94f;
            float bladeW = 5f;
            float bottom = y1 + 0.5f;
            float height = Math.max(8f, Math.min(18f, wallTop - bottom - 1f));
            float[] at = add(wallPoint(front, side, bottom + height * 0.5f), scale3(n, bladeW * 0.5f + 0.6f));
            float[] along = normalise(sub(front.bottomB, front.bottomA));
            sign = InfrastructureObject.createWallSign(fixedTo(house, at, along), chartVector(house, at), house.nationId,
                    bladeW, height, true, true, null, 0f, 0f);
        }
        System.arraycopy(name, 0, sign.titleString, 0, name.length);
        sign.titleLength = name.length;
        letter(sign, rand);
        float[] paint = new float[] { shopColours.get(house.nationId)[house.shop.colour].x, shopColours.get(house.nationId)[house.shop.colour].y,
                shopColours.get(house.nationId)[house.shop.colour].z };
        // The board contrasts with the paintwork, the letters with the board
        boolean darkBoard = rand.nextFloat() < 0.45f;
        float[] hsv = WorldPalette.toHsv(paint);
        sign.boardColour = darkBoard ? WorldPalette.hsv(hsv[0] + 0.5f, 0.5f, 0.18f + rand.nextFloat() * 0.12f)
                : WorldPalette.hsv(hsv[0] + 0.1f, 0.08f + rand.nextFloat() * 0.15f, 0.9f + rand.nextFloat() * 0.08f);
        sign.titleColour = darkBoard ? WorldPalette.hsv(rand.nextFloat(), 0.3f + rand.nextFloat() * 0.5f, 0.92f)
                : WorldPalette.hsv(hsv[0] + rand.nextFloat() * 0.3f, 0.7f + rand.nextFloat() * 0.3f, 0.25f + rand.nextFloat() * 0.3f);
        objects.add(sign);
    }

    /** A wide, deep window full of goods on shelves, brightly lit inside, with no curtains. */
    private void displayWindow(NationBatch batch, House house, BuildingStyle style, HouseWall wall, float s0, float s1,
                               float y0, float y1, Random rand) {
        if (s1 - s0 < 0.04f || (s1 - s0) * wall.length < 4f) return;
        wall.openings.add(new float[] { s0, s1, y0, y1 });
        float[] n = wall.normal;
        float[] along = normalise(sub(wallPoint(wall, s1, y0), wallPoint(wall, s0, y0)));
        float[] up = { 0f, 1f, 0f };
        float d = DISPLAY_DEPTH;

        MeshBuilder reveal = batch.builder("frame");
        localQuad(reveal, house, inset(wall, s0, y0, 0), inset(wall, s0, y1, 0), inset(wall, s0, y1, d), inset(wall, s0, y0, d), along);
        localQuad(reveal, house, inset(wall, s1, y0, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, d), inset(wall, s1, y0, d), scale3(along, -1));
        localQuad(reveal, house, inset(wall, s0, y1, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, d), inset(wall, s0, y1, d), scale3(up, -1));
        localQuad(batch.builder("shelf"), house, inset(wall, s0, y0, 0), inset(wall, s1, y0, 0), inset(wall, s1, y0, d), inset(wall, s0, y0, d), up);
        localQuad(batch.builder("shopinterior"), house, inset(wall, s0, y0, d), inset(wall, s1, y0, d),
                inset(wall, s1, y1, d), inset(wall, s0, y1, d), n);

        // Shelves: one or two, stepping up towards the back
        float height = y1 - y0;
        int shelves = height > 7.5f ? 2 : 1;
        float[] levels = new float[shelves + 1];
        float[] tops = new float[shelves + 1];
        levels[0] = y0;
        for (int i = 1; i <= shelves; i++) levels[i] = y0 + height * i / (shelves + 1f);
        for (int i = 0; i <= shelves; i++) tops[i] = i < shelves ? levels[i + 1] : y1;
        MeshBuilder shelf = batch.builder("shelf");
        for (int i = 1; i <= shelves; i++) {
            float y = levels[i], front = 2.2f;
            localQuad(shelf, house, inset(wall, s0, y, front), inset(wall, s1, y, front), inset(wall, s1, y, d), inset(wall, s0, y, d), up);
            localQuad(shelf, house, inset(wall, s0, y - 0.3f, front), inset(wall, s1, y - 0.3f, front), inset(wall, s1, y, front),
                    inset(wall, s0, y, front), n);
        }
        stockWindow(batch.builder("goods"), house, wall, s0, s1, levels, tops, rand);

        // The glass, and a frame like the house's other windows
        localQuad(batch.builder("glass"), house, inset(wall, s0, y0, GLASS_SETBACK), inset(wall, s1, y0, GLASS_SETBACK),
                inset(wall, s1, y1, GLASS_SETBACK), inset(wall, s0, y1, GLASS_SETBACK), n);
        float f = Math.max(0.5f, style.frameSize);
        float fs = f / wall.length;
        MeshBuilder frame = batch.builder("frame");
        float[][][] strips = {
            { { s0 - fs, y1 }, { s1 + fs, y1 }, { s1 + fs, y1 + f }, { s0 - fs, y1 + f } },
            { { s0 - fs, y0 - f }, { s1 + fs, y0 - f }, { s1 + fs, y0 }, { s0 - fs, y0 } },
            { { s0 - fs, y0 }, { s0, y0 }, { s0, y1 }, { s0 - fs, y1 } },
            { { s1, y0 }, { s1 + fs, y0 }, { s1 + fs, y1 }, { s1, y1 } } };
        for (float[][] strip : strips) {
            float[][] c = new float[4][];
            for (int i = 0; i < 4; i++) c[i] = inset(wall, Math.max(0f, Math.min(1f, strip[i][0])), strip[i][1], -FRAME_OFFSET);
            localQuad(frame, house, c[0], c[1], c[2], c[3], n);
        }
    }

    /**
     * Fills each level of a display window with goods: heaps of produce and rows of
     * packets, a few of each kind together, sized to the shelf they stand on.
     */
    // The most goods set out along one shelf of a shop window
    private static final int MOST_GOODS_PER_SHELF = 18;

    private void stockWindow(MeshBuilder goods, House house, HouseWall wall, float s0, float s1, float[] levels, float[] tops, Random rand) {
        int nation = house.nationId;
        boolean grocer = house.shop.grocer;
        List<Products.Produce> crops = products.produce(nation);
        List<Products.Packet> packets = products.packets(nation);
        float width = (s1 - s0) * wall.length;
        float[] outward = horizontal(wall.normal);
        float[] up = { 0f, 1f, 0f };
        float[] across = cross(up, outward);
        for (int level = 0; level < levels.length; level++) {
            float room = tops[level] - levels[level] - 0.4f;
            if (room < 1f) continue;
            float frontDepth = level == 0 ? 1.4f : 2.6f, backDepth = DISPLAY_DEPTH - 0.9f;
            float t = 0.5f;
            int remaining = 0;
            boolean packet = false;
            int index = 0;
            // (a very wide window, as in a big shop, has its goods only so far along each shelf)
            int placed = 0;
            while (t < width - 0.5f && placed++ < MOST_GOODS_PER_SHELF) {
                if (remaining <= 0) {
                    packet = grocer ? rand.nextFloat() < 0.2f : rand.nextFloat() < 0.75f;
                    index = rand.nextInt(packet ? packets.size() : crops.size());
                    remaining = 2 + rand.nextInt(3);
                }
                remaining--;
                float footprint;
                if (packet) {
                    Products.Packet k = packets.get(index);
                    float scale = Math.min(1f, room * 0.92f / (k.height + 0.2f));
                    footprint = k.width * scale + 0.25f;
                    if (t + footprint > width - 0.3f) break;
                    float s = s0 + (t + footprint * 0.5f) / wall.length;
                    for (float depth : new float[] { frontDepth + k.depth * scale * 0.5f, backDepth - k.depth * scale * 0.5f }) {
                        float[] origin = inset(wall, s, levels[level], depth);
                        appendGoods(goods, house, origin, across, up, outward, scale, (rand.nextFloat() - 0.5f) * 0.25f,
                                goodsGeometry(nation, true, index));
                    }
                } else {
                    Products.Produce p = crops.get(index);
                    float tall = p.shape == Products.Shape.DISC ? p.size * 0.45f : p.size;
                    float scale = Math.min(1f, room * 0.85f / tall);
                    float wide = Math.max(p.size * p.width, p.shape == Products.Shape.POD ? p.size * 0.6f : 0f) * scale;
                    footprint = wide + 0.2f;
                    if (t + footprint > width - 0.3f) break;
                    float s = s0 + (t + footprint * 0.5f) / wall.length;
                    // A little heap: a few pieces one behind the other, turned this way and that
                    int pieces = 2 + rand.nextInt(3);
                    for (int i = 0; i < pieces; i++) {
                        float depth = frontDepth + wide * 0.5f + (backDepth - frontDepth - wide) * i / Math.max(1f, pieces - 1f);
                        float[] origin = inset(wall, s + (rand.nextFloat() - 0.5f) * 0.3f / wall.length, levels[level], depth);
                        appendGoods(goods, house, origin, across, up, outward, scale * (0.85f + rand.nextFloat() * 0.25f),
                                rand.nextFloat() * 6.28f, goodsGeometry(nation, false, index));
                    }
                }
                t += footprint;
            }
        }
    }

    /** A product's geometry at unit scale, built once: {vertices (OrganismMesh layout), indices}. */
    private Object[] goodsGeometry(int nationId, boolean packet, int index) {
        long key = nationId * 64L + (packet ? 32 : 0) + index;
        return goodsGeometry.computeIfAbsent(key, k -> {
            OrganismMesh.Builder b = new OrganismMesh.Builder();
            if (packet) Products.buildPacket(b, products.packets(nationId).get(index), 1f, Affine.identity());
            else Products.buildProduce(b, products.produce(nationId).get(index), 1f, Affine.identity());
            return new Object[] { b.vertices(), b.indices() };
        });
    }

    /** Copies a product into a builder, standing at a house-local point, its front facing front, turned by yaw. */
    private void appendGoods(MeshBuilder builder, House house, float[] origin, float[] across, float[] up, float[] front,
                             float scale, float yaw, Object[] geometry) {
        float[] v = (float[]) geometry[0];
        int[] idx = (int[]) geometry[1];
        float cos = (float) Math.cos(yaw), sin = (float) Math.sin(yaw);
        int stride = OrganismMesh.STRIDE;
        int first = -1;
        for (int i = 0; i + stride <= v.length; i += stride) {
            float x = v[i] * cos + v[i + 2] * sin, y = v[i + 1], z = -v[i] * sin + v[i + 2] * cos;
            float nx = v[i + 3] * cos + v[i + 5] * sin, ny = v[i + 4], nz = -v[i + 3] * sin + v[i + 5] * cos;
            float[] p = add(origin, add(add(scale3(across, x * scale), scale3(up, y * scale)), scale3(front, z * scale)));
            float[] normal = add(add(scale3(across, nx), scale3(up, ny)), scale3(front, nz));
            int index = houseVertex(builder, house, p, normal, v[i + 6], v[i + 7]);
            if (first < 0) first = index;
        }
        if (first < 0) return;
        for (int i = 0; i + 2 < idx.length; i += 3) builder.addTriangle(first + idx[i], first + idx[i + 1], first + idx[i + 2]);
    }

    /** How far a leaning wall's face comes out past where it is at height y, over a board height tall centred there. */
    private static float standOff(HouseWall wall, float s, float y, float height) {
        float[] n = horizontal(wall.normal);
        float[] centre = wallPoint(wall, s, y);
        float below = dot3(sub(wallPoint(wall, s, Math.max(0f, y - height * 0.5f)), centre), n);
        float above = dot3(sub(wallPoint(wall, s, y + height * 0.5f), centre), n);
        return Math.max(0f, Math.max(below, above));
    }

    private static float[] horizontal(float[] v) {
        return normalise(new float[] { v[0], 0f, v[2] });
    }

    /**
     * Now and then a poster pasted on a wall, often an advert for a shop nearby: on a
     * stretch of wall clear of windows and doors, leaning with the wall if it leans and
     * curving round it if it's round, and above the fence if the house has one, so the
     * fence doesn't hide it.
     */
    private void wallPoster(House house, HouseWall[] walls, HouseWall front, List<InfrastructureObject> objects, Random rand) {
        float scale = 0.5f + rand.nextFloat() * 0.2f;
        float w = 30f * scale, h = 22f * scale;
        // The middle of the footprint, for how round a curved wall is
        float cx = 0f, cz = 0f;
        for (HouseWall wall : walls) { cx += wall.bottomA[0]; cz += wall.bottomA[2]; }
        cx /= walls.length;
        cz /= walls.length;
        FenceStyle fence = house.fenced ? fenceStyles.get(house.nationId) : null;
        float fenceHeight = fence == null ? 0f : house.secure ? fence.securityHeight : fence.height;
        int start = rand.nextInt(walls.length);
        for (int k = 0; k < walls.length; k++) {
            HouseWall wall = walls[(start + k) % walls.length];
            boolean curved = wall.normalA != wall.normal;
            float[] mid = mid(wall.bottomA, wall.bottomB);
            float radius = curved ? (float) Math.hypot(mid[0] - cx, mid[2] - cz) : 0f;
            // A round wall is one curve, made of short straight pieces: the poster may span several
            if (curved ? radius * 2f * (float) Math.PI < w * 3f : wall.length < w + 3f) continue;
            if (wall.topA[1] < house.doorBase + h + 4f) continue;
            float yc = house.doorBase + Math.min(wall.topA[1] - house.doorBase - h * 0.5f - 1.5f, h * 0.5f + 4f);
            // Clear of the ground, wherever it rises against this wall, and of any fence
            float halfAlong = curved ? 0f : (w * 0.5f) / wall.length;
            float ground = -Float.MAX_VALUE;
            for (float s = 0.5f - halfAlong; s <= 0.5f + halfAlong + 1e-4f; s += Math.max(1e-3f, halfAlong)) {
                float[] foot = wallPoint(wall, s, 0f);
                ground = Math.max(ground, groundAt(house, foot[0], foot[2]));
            }
            yc = Math.max(yc, ground + 3f - house.baseY + h * 0.5f);
            if (fenceHeight > 0f) yc = Math.max(yc, ground - house.baseY + fenceHeight + 1.5f + h * 0.5f);
            if (yc + h * 0.5f > wall.topA[1] - 1f) continue;
            boolean clear = true;
            if (curved) {
                // Every piece of the wall it covers, by angle round the middle
                float facing = (float) Math.atan2(mid[2] - cz, mid[0] - cx);
                float halfAngle = (w * 0.5f + 1f) / radius;
                for (HouseWall other : walls) {
                    float[] om = mid(other.bottomA, other.bottomB);
                    float angle = (float) Math.atan2(om[2] - cz, om[0] - cx);
                    float apart = Math.abs((float) Math.atan2(Math.sin(angle - facing), Math.cos(angle - facing)));
                    if (apart > halfAngle + other.length * 0.5f / radius) continue;
                    for (float[] o : other.openings) {
                        if (o[3] > yc - h * 0.5f - 0.8f && o[2] < yc + h * 0.5f + 0.8f) clear = false;
                    }
                    if (other == front && yc - h * 0.5f < house.doorBase + styleOf(house).doorHeight * house.sizeScale + 1f) clear = false;
                }
            } else {
                float halfS = (w * 0.5f + 0.8f) / wall.length;
                for (float[] o : wall.openings) {
                    if (o[1] > 0.5f - halfS && o[0] < 0.5f + halfS && o[3] > yc - h * 0.5f - 0.8f && o[2] < yc + h * 0.5f + 0.8f) {
                        clear = false;
                        break;
                    }
                }
                if (wall == front) {
                    // Beside the door, not over it
                    float doorHalf = styleOf(house).doorWidth * house.sizeScale * 0.5f / wall.length;
                    if (0.5f - halfS < 0.5f + doorHalf + 0.02f && 0.5f + halfS > 0.5f - doorHalf - 0.02f) clear = false;
                }
            }
            if (!clear) continue;
            // Pasted flat against the face, which may lean: the poster leans the same way
            float[] n = horizontal(wall.normal);
            float tilt = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, wall.normal[1]))));
            float[] at = add(wallPoint(wall, 0.5f, yc), scale3(wall.normal, curved ? 0.45f : 0.3f));
            int maxGlyphs = Math.max(1, mainListener != null ? mainListener.getNationAtlasSize(house.nationId) : 10);
            InfrastructureObject poster = InfrastructureObject.createWallSign(fixedTo(house, at, n), chartVector(house, at), house.nationId,
                    w, h, false, false, randomText(rand, maxGlyphs, 40, 120), tilt, radius);
            decorateSign(poster, rand, maxGlyphs, true);
            objects.add(poster);
            return;
        }
    }

    // ------------------------------------------------------------------ FLAGS

    /**
     * A tall pole standing in the ground at a point in a house's frame (the batch building in
     * it), with the nation's flag at the top, flying in the world's wind.
     */
    private void flagpole(NationBatch batch, House house, float x, float z, float height) {
        float ground = groundAt(house, x, z);
        MeshBuilder pole = batch.builder("flagpole");
        addBox(pole, x, ground - 1f, z, 1f, 0f, 0.45f, 0.45f, height + 1.5f);
        addBox(pole, x, ground + height + 0.4f, z, 1f, 0f, 0.9f, 0.9f, 0.8f);   // the finial
        float flagH = 9f + height * 0.05f, flagW = flagH * FlagDesigner.WIDTH / FlagDesigner.HEIGHT;
        // The wind blows from the same heading (from east towards south) everywhere, put in the house's frame
        SpherePosition foot = world(house, x, 0f, z);
        double[] wind = SphereFrame.at(foot).headingDirection(windAngle);
        double[] local = house.frame.localDirection(wind[0], wind[1], wind[2]);
        float length = (float) Math.max(1e-6, Math.hypot(local[0], local[2]));
        float dirX = (float) local[0] / length, dirZ = (float) local[2] / length;
        flagCloth(batch.builder("flag"), new float[] { x, ground + height - 0.5f, z }, new float[] { dirX, 0f, dirZ },
                new float[] { 0f, -1f, 0f }, flagW, flagH, Math.floorMod(house.seed >> 3, 1000L) * 0.13f);
        batch.include(x, ground + height + 2f, z, flagW + 2f);
        recordCircle(foot, 0.8f);
    }

    /** A short pole angled up and out from a wall, a flag hanging along it. */
    private void wallFlag(NationBatch batch, House house, HouseWall wall, float y) {
        if (y > wall.topA[1] - 2f) return;
        float s = (house.seed & 1) == 0 ? 0.22f : 0.78f;
        float[] base = wallPoint(wall, s, y);
        float[] out = wall.normal;
        float lift = 0.75f;
        float[] dir = normalise(new float[] { out[0], lift, out[2] });
        float length = 11f;
        float[] tip = add(base, scale3(dir, length));
        float[] baseWorld = { base[0], house.baseY + base[1], base[2] }, tipWorld = { tip[0], house.baseY + tip[1], tip[2] };
        MeshBuilder pole = batch.builder("flagpole");
        addRod(pole, baseWorld, tipWorld, 0.3f);
        // The flag hangs from the pole, its top edge along it and its face across the wall
        float[] along = normalise(sub(tipWorld, baseWorld));
        float flagW = length * 0.8f, flagH = flagW * FlagDesigner.HEIGHT / FlagDesigner.WIDTH;
        float[] start = add(baseWorld, scale3(along, length * 0.18f));
        float[] down = { 0f, -1f, 0f };
        flagCloth(batch.builder("flag"), start, along, down, flagW, flagH, Math.floorMod(house.seed, 1000L) * 0.11f);
    }

    /**
     * A flag's cloth: hoist edge at start, flying along fly for width, hanging along down for
     * height, rippling across itself more towards its free end. u runs hoist to fly, v top to bottom.
     */
    private void flagCloth(MeshBuilder builder, float[] start, float[] fly, float[] down, float width, float height, float phase) {
        float[] across = normalise(cross(fly, down));
        int columns = 12, rows = 3;
        int[][] index = new int[columns + 1][rows + 1];
        for (int i = 0; i <= columns; i++) {
            float u = i / (float) columns;
            float wave = (float) Math.sin(u * Math.PI * 3.0 + phase) * 0.9f * u;
            float slope = (float) Math.cos(u * Math.PI * 3.0 + phase) * 0.9f * u * (float) Math.PI * 3.0f / width;
            float[] normal = normalise(sub(across, scale3(fly, slope)));
            for (int j = 0; j <= rows; j++) {
                float v = j / (float) rows;
                // The free end droops a little
                float droop = u * u * height * 0.12f;
                float[] p = add(add(add(start, scale3(fly, u * width)), scale3(down, v * height + droop)), scale3(across, wave));
                // Textures made from images are stored bottom row first, so v counts up from the flag's foot
                index[i][j] = builder.addVertex(p[0], p[1], p[2], normal[0], normal[1], normal[2], u, 1f - v);
            }
        }
        for (int i = 0; i < columns; i++) {
            for (int j = 0; j < rows; j++) {
                builder.addTriangle(index[i][j], index[i + 1][j], index[i + 1][j + 1]);
                builder.addTriangle(index[i][j], index[i + 1][j + 1], index[i][j + 1]);
            }
        }
    }

    /** A square rod between two world points. */
    private void addRod(MeshBuilder builder, float[] a, float[] b, float half) {
        float[] axis = normalise(sub(b, a));
        float[] side = normalise(cross(axis, Math.abs(axis[1]) > 0.9f ? new float[] { 1f, 0f, 0f } : new float[] { 0f, 1f, 0f }));
        float[] up = normalise(cross(side, axis));
        float[][] corners = { add(scale3(side, half), scale3(up, half)), add(scale3(side, -half), scale3(up, half)),
                add(scale3(side, -half), scale3(up, -half)), add(scale3(side, half), scale3(up, -half)) };
        for (int k = 0; k < 4; k++) {
            float[] c0 = corners[k], c1 = corners[(k + 1) % 4];
            float[] n = normalise(add(c0, c1));
            float[][] q = { add(a, c0), add(a, c1), add(b, c1), add(b, c0) };
            int i0 = builder.addVertex(q[0][0], q[0][1], q[0][2], n[0], n[1], n[2], 0, 0);
            int i1 = builder.addVertex(q[1][0], q[1][1], q[1][2], n[0], n[1], n[2], 1, 0);
            int i2 = builder.addVertex(q[2][0], q[2][1], q[2][2], n[0], n[1], n[2], 1, 1);
            int i3 = builder.addVertex(q[3][0], q[3][1], q[3][2], n[0], n[1], n[2], 0, 1);
            builder.addTriangle(i0, i1, i2);
            builder.addTriangle(i0, i2, i3);
            builder.addTriangle(i0, i2, i1);
            builder.addTriangle(i0, i3, i2);
        }
    }

    /**
     * A frame for something fixed to a house (a sign on its wall): at a house-local point (y
     * above the house's base), with the house's axes turned so local +z faces (face x, z) in the
     * house's frame. Rigid, as the house is, so it lies flush with the wall.
     */
    private static SphereFrame fixedTo(House house, float[] at, float[] face) {
        double turn = Math.atan2(face[2], face[0]) - Math.PI * 0.5;
        return house.frame.rotated(turn).movedTo(house.frame.toWorld(at[0], house.baseY + at[1], at[2]));
    }

    /** Chart bridge (D13): a house-local point (y above the house's base) on the chart, for the signs. */
    private static Vector3 chartVector(House house, float[] p) {
        float[] chart = chartPoint(house, p[0], house.baseY + p[1], p[2]);
        return new Vector3(chart[0], chart[1], chart[2]);
    }

    /** A flat four-cornered face in house-local space, wound to face along outward. */
    private void localQuad(MeshBuilder builder, House house, float[] a, float[] b, float[] c, float[] d, float[] outward) {
        float[] normal = normalise(outward);
        int ia = houseVertex(builder, house, a, normal, 0f, 0f);
        int ib = houseVertex(builder, house, b, normal, 1f, 0f);
        int ic = houseVertex(builder, house, c, normal, 1f, 1f);
        int id = houseVertex(builder, house, d, normal, 0f, 1f);
        addOutwardQuad(builder, house, ia, ib, ic, id, a, b, c, normal);
    }

    /**
     * A flat rectangle lying on a (possibly slanted) wall: centred at fraction s along the
     * wall and at height centreY, following the wall's lean, and lifted off it by offset.
     */
    private float[][] wallPanel(MeshBuilder builder, House house, HouseWall wall, float s, float centreY,
                           float halfW, float halfH, float offset) {
        float wallHeight = wall.topA[1];
        float[] along = normalise(sub(wall.bottomB, wall.bottomA));
        float[][] corners = new float[4][];
        float[][] local = { { -halfW, -halfH }, { halfW, -halfH }, { halfW, halfH }, { -halfW, halfH } };
        for (int i = 0; i < 4; i++) {
            float y = centreY + local[i][1];
            float t = Math.max(0f, Math.min(1f, y / wallHeight));
            float[] bottom = lerp(wall.bottomA, wall.bottomB, s);
            float[] top = lerp(wall.topA, wall.topB, s);
            float[] onWall = lerp(bottom, top, t);
            onWall[1] = y;
            float[] p = add(add(onWall, scale3(along, local[i][0])), scale3(wall.normal, offset));
            corners[i] = new float[] { p[0], house.baseY + p[1], p[2] };
        }
        float[] inside = { (wall.bottomA[0] + wall.bottomB[0]) * 0.5f - wall.normal[0] * 5.0f, house.baseY + centreY,
                (wall.bottomA[2] + wall.bottomB[2]) * 0.5f - wall.normal[2] * 5.0f };
        builder.addConvexFace(inside, corners);
        return corners;
    }

    /** Adds a point given above the house's base to a builder working in the house's frame (y above the datum). */
    private int houseVertex(MeshBuilder builder, House house, float[] p, float[] n, float u, float v) {
        return builder.addVertex(p[0], house.baseY + p[1], p[2], n[0], n[1], n[2], u, v);
    }

    /** Two triangles for a quad, wound counter-clockwise as seen from the side its normal faces. */
    private void addOutwardQuad(MeshBuilder builder, House house, int a, int b, int c, int d,
                                float[] pa, float[] pb, float[] pc, float[] outward) {
        if (dot3(cross(sub(pb, pa), sub(pc, pa)), outward) >= 0) {
            builder.addTriangle(a, b, c);
            builder.addTriangle(a, c, d);
        } else {
            builder.addTriangle(a, c, b);
            builder.addTriangle(a, d, c);
        }
    }

    private void addOutwardTriangle(MeshBuilder builder, House house, int a, int b, int c,
                                    float[] pa, float[] pb, float[] pc, float[] outward) {
        if (dot3(cross(sub(pb, pa), sub(pc, pa)), outward) >= 0) {
            builder.addTriangle(a, b, c);
        } else {
            builder.addTriangle(a, c, b);
        }
    }

    private static float[] sub(float[] a, float[] b) { return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] }; }
    private static float[] add(float[] a, float[] b) { return new float[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] }; }
    private static float[] scale3(float[] a, float s) { return new float[] { a[0] * s, a[1] * s, a[2] * s }; }
    private static float[] mid(float[] a, float[] b) { return lerp(a, b, 0.5f); }
    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }
    private static float dot3(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static float dist(float[] a, float[] b) { float[] d = sub(a, b); return (float) Math.sqrt(dot3(d, d)); }
    private static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }
    private static float[] normalise(float[] a) {
        float len = (float) Math.sqrt(dot3(a, a));
        return len < 1e-6f ? new float[] { 0, 1, 0 } : scale3(a, 1f / len);
    }

    /**
     * Copies a unit mesh into a builder working in the house's frame: scaled by (sx, sy, sz),
     * shifted by the local offset, and stood on the house's base. With a texture repeat size,
     * texture coordinates are remapped to world units: along the eaves and up the slope
     * on sloping faces, and straight down onto flat ones.
     */
    private void bake(MeshBuilder builder, float[] vertices, int[] indices, House house,
                      float sx, float sy, float sz, float ox, float oy, float oz, float tileSize) {
        int first = -1;
        for (int v = 0; v < vertices.length; v += 8) {
            float lx = vertices[v] * sx + ox;
            float ly = vertices[v + 1] * sy + oy;
            float lz = vertices[v + 2] * sz + oz;
            // Normals take the inverse scale so they stay perpendicular to stretched faces
            float nx = vertices[v + 3] / sx;
            float ny = vertices[v + 4] / sy;
            float nz = vertices[v + 5] / sz;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= length;
            ny /= length;
            nz /= length;
            float u = vertices[v + 6], tv = vertices[v + 7];
            if (tileSize > 0) {
                float rx = vertices[v] * sx, rz = vertices[v + 2] * sz, ry = vertices[v + 1] * sy;
                if (Math.abs(ny) > 0.95f) {
                    u = rx / tileSize;
                    tv = rz / tileSize;
                } else if (Math.abs(nx) > Math.abs(nz)) {
                    u = rz / tileSize;
                    tv = (ry + (sx * 0.5f - Math.abs(rx))) / tileSize;
                } else {
                    u = rx / tileSize;
                    tv = (ry + (sz * 0.5f - Math.abs(rz))) / tileSize;
                }
            }
            int index = builder.addVertex(lx, house.baseY + ly, lz, nx, ny, nz, u, tv);
            if (first < 0) {
                first = index;
            }
        }
        for (int i = 0; i + 2 < indices.length; i += 3) {
            builder.addTriangle(first + indices[i], first + indices[i + 1], first + indices[i + 2]);
        }
    }

    /**
     * Poorer households' houses are weathered, streaked and stained, more so the poorer; the
     * richest are dressed up with pillars round the door and a cornice under the roof.
     */
    private void decideLook(House house) {
        Random rand = new Random(house.seed ^ 0x3EA7E2L);
        float poor = Math.max(0f, Math.min(1f, (0.5f - house.richness) / 0.4f));
        house.weathering = rand.nextFloat() < 0.2f + 0.8f * poor ? Math.min(3, Math.round(poor * (0.6f + 0.6f * rand.nextFloat()) * 3f)) : 0;
        house.grand = house.richness > 0.5f && rand.nextFloat() < (house.richness - 0.5f) * 2.2f;
    }

    /**
     * A nation's own random numbers for one purpose (salt). The seed is scrambled first: Java's
     * Random gives almost the same first number for seeds that differ by a little, which would
     * make every nation alike.
     */
    private Random nationRandom(long salt, int nationId) {
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

    /**
     * How well off the people at a point are: the nation's richness, more so in town, and far
     * less near a fenced border (see borderBlight), on both sides of it.
     */
    // A city counts as big from this size to fully big at this (1 the planet's largest), and the
    // biggest are this much richer than the nation's usual towns
    private static final float BIG_CITY_FROM = 0.3f, BIG_CITY_TO = 0.75f, BIG_CITY_WEALTH = 0.8f;

    public float areaRichness(int nationId, float urbanness, float x, float z) {
        return areaRichness(nationId, urbanness, sphere(x, z));
    }

    private float areaRichness(int nationId, float urbanness, SpherePosition place) {
        RoadNetworkBuilder islands = islandWealth;
        float island = islands == null ? Float.NaN : islands.islandRichnessAt(place);
        float base = Float.isNaN(island) ? nationRichness(nationId) : island;
        // (big cities draw the money: the bigger the city, the richer its people)
        float big = smoothstep(BIG_CITY_FROM, BIG_CITY_TO, settlementManager.getCitySize(SphereMath.direction(place)));
        // (the fenced borders are measured on the nation map, D12)
        double[] onMap = Planet.mercatorPosition(place);
        return Math.min(1f, base * (0.55f + 0.9f * urbanness) * (1f + BIG_CITY_WEALTH * big))
                * (1f - BORDER_POVERTY * borderBlight((float) onMap[0], (float) onMap[2]));
    }

    // How much of their wealth people right on a fenced border have lost, and how far (in
    // nation-map cells, each a few hundred metres) the loss reaches either side
    private static final float BORDER_POVERTY = 0.75f, BORDER_POVERTY_REACH = 5f;

    /** 1 right on a fenced border between nations at odds, fading to 0 BORDER_POVERTY_REACH cells away. */
    public float borderBlight(float x, float z) {
        float gap = borderGapAt(x, z);
        if (gap >= BORDER_POVERTY_REACH) return 0f;
        float t = 1f - gap / BORDER_POVERTY_REACH;
        return t * t * (3f - 2f * t);
    }

    /** The distance to the nearest fenced border, in nation-map cells, blended between cells. */
    private float borderGapAt(float x, float z) {
        int res = nationManager.nationMap.length;
        float width = Planet.width(), cell = width / res;
        float gx = (x + width * 0.5f) / cell - 0.5f, gz = (z + width * 0.5f) / cell - 0.5f;
        int x0 = (int) Math.floor(gx), z0 = (int) Math.floor(gz);
        float fx = gx - x0, fz = gz - z0;
        float sum = 0f;
        for (int k = 0; k < 4; k++) {
            int dx = k & 1, dz = k >> 1;
            int i = Math.floorMod(x0 + dx, res), j = Math.max(0, Math.min(res - 1, z0 + dz));
            sum += borderGap[j * res + i] * (dx == 0 ? 1f - fx : fx) * (dz == 0 ? 1f - fz : fz);
        }
        return sum;
    }

    /**
     * How far each cell of the nation map is from a border between nations at odds, in cells
     * (0 on such a border, counted out to a little past where it matters).
     */
    private float[] hostileBorderDistance() {
        int[][] map = nationManager.nationMap;
        int res = map.length;
        float[] gap = new float[res * res];
        java.util.Arrays.fill(gap, Float.MAX_VALUE);
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        for (int x = 0; x < res; x++) {
            for (int z = 0; z < res; z++) {
                int id = map[x][z];
                boolean edge = kinship.hostile(id, map[(x + 1) % res][z]) || kinship.hostile(id, map[Math.floorMod(x - 1, res)][z])
                        || (z + 1 < res && kinship.hostile(id, map[x][z + 1])) || (z > 0 && kinship.hostile(id, map[x][z - 1]));
                if (edge) {
                    gap[z * res + x] = 0f;
                    queue.add(new int[] { x, z });
                }
            }
        }
        // Out from the borders, a step at a time (diagonals the longer way)
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            float here = gap[c[1] * res + c[0]];
            if (here > BORDER_POVERTY_REACH + 2f) continue;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nz = c[1] + dz;
                    if ((dx == 0 && dz == 0) || nz < 0 || nz >= res) continue;
                    int nx = Math.floorMod(c[0] + dx, res);
                    float step = dx != 0 && dz != 0 ? 1.41421f : 1f;
                    if (here + step < gap[nz * res + nx]) {
                        gap[nz * res + nx] = here + step;
                        queue.add(new int[] { nx, nz });
                    }
                }
            }
        }
        for (int i = 0; i < gap.length; i++) gap[i] = Math.min(gap[i], BORDER_POVERTY_REACH + 3f);
        return gap;
    }

    /**
     * Whether the house has a driveway down one side for a car, the likelier the richer the
     * area; the plot is widened to take it. It goes on the side away from any extension.
     */
    private void decideDriveway(House house, float overhang) {
        house.drivewaySide = 0;
        Random rand = new Random(house.seed ^ 0xD71E3AL);
        float urbanness = settlementManager.getUrbanness(house.frame.origin());
        if (rand.nextFloat() >= 0.85f * areaRichness(house.nationId, urbanness, house.frame.origin())) return;
        int side = house.extensionSide != 0 ? -house.extensionSide : (rand.nextBoolean() ? 1 : -1);
        house.drivewaySide = side;
        if (side > 0) {
            house.driveMinX = house.houseMaxX() + overhang + 1f;
            house.driveMaxX = house.driveMinX + DRIVEWAY_WIDTH;
            house.plotMaxX = Math.max(house.plotMaxX, house.driveMaxX + 3f);
        } else {
            house.driveMaxX = house.houseMinX() - overhang - 1f;
            house.driveMinX = house.driveMaxX - DRIVEWAY_WIDTH;
            house.plotMinX = Math.min(house.plotMinX, house.driveMinX - 3f);
        }
    }

    /**
     * The driveway's surface: paving laid over the ground from the road's edge back to the
     * back of the house, and noted so grass doesn't grow through it.
     */
    private void bakeDriveway(NationBatch batch, House house) {
        MeshBuilder paving = batch.builder("driveway");
        float cx = (house.driveMinX + house.driveMaxX) * 0.5f;
        float frontZ = followKerb(house, cx)[1] + FRONT_FENCE_GAP;
        float backZ = -house.depth * 0.5f;
        int along = Math.max(2, Math.round((frontZ - backZ) / 4f)), across = Math.max(2, Math.round(DRIVEWAY_WIDTH / 4f));
        int first = -1;
        for (int i = 0; i <= along; i++) {
            float lz = backZ + (frontZ - backZ) * i / along;
            for (int j = 0; j <= across; j++) {
                float lx = house.driveMinX + (house.driveMaxX - house.driveMinX) * j / across;
                float y = groundAt(house, lx, lz);
                float nx = (groundAt(house, lx - 1f, lz) - groundAt(house, lx + 1f, lz)) * 0.5f;
                float nz = (groundAt(house, lx, lz - 1f) - groundAt(house, lx, lz + 1f)) * 0.5f;
                float length = (float) Math.sqrt(nx * nx + 1f + nz * nz);
                int index = paving.addVertex(lx, y + DRIVEWAY_LIFT, lz, nx / length, 1f / length, nz / length, lx / 12f, lz / 12f);
                if (first < 0) first = index;
                batch.include(lx, y, lz, 0f);
            }
        }
        int row = across + 1;
        for (int i = 0; i < along; i++) {
            for (int j = 0; j < across; j++) {
                int a = first + i * row + j, b = a + 1, c = a + row, d = c + 1;
                // Wound to face up (turning the house keeps it so)
                paving.addTriangle(a, c, b);
                paving.addTriangle(b, c, d);
            }
        }
    }

    /** Notes where a driveway is (filed at its house, reaching its far corner), so grass keeps off it. */
    private void noteDriveway(House house) {
        float cx = (house.driveMinX + house.driveMaxX) * 0.5f;
        house.driveFrontZ = followKerb(house, cx)[1] + FRONT_FENCE_GAP;
        float x = Math.max(Math.abs(house.driveMinX), Math.abs(house.driveMaxX));
        float z = Math.max(house.depth * 0.5f, house.driveFrontZ);
        drivewayIndex.add(house.frame.origin(), Math.hypot(x, z) + 1.0, house);
    }

    /** Whether a place (on the datum) is on a driveway, worked in its house's frame. */
    private boolean onDriveway(SpherePosition place) {
        for (House house : drivewayIndex.near(place, 0.0)) {
            float[] local = local(house, place);
            if (local[0] >= house.driveMinX && local[0] <= house.driveMaxX
                    && local[1] >= -house.depth * 0.5f && local[1] <= house.driveFrontZ) return true;
        }
        return false;
    }

    /**
     * Whether and how a house is fenced. Security-minded nations put tall fences round many
     * of their houses, most of all in built-up areas; otherwise the nation's usual share of
     * plots are fenced, with the outline varying from house to house.
     */
    private void decideFence(House house) {
        FenceStyle style = fenceStyles.get(house.nationId);
        if (style == null) {
            house.fenced = false;
            return;
        }
        Random rand = new Random(house.seed ^ 0x5EC0F3A5L);
        float urbanness = settlementManager.getUrbanness(house.frame.origin());
        float secureChance = style.securityLevel * (0.06f + 0.9f * smoothstep(0.05f, 0.45f, urbanness));
        house.secure = rand.nextFloat() < secureChance;
        house.fenced = house.fenced || house.secure;
        float shape = rand.nextFloat();
        house.fenceShape = house.secure || shape < 0.55f ? FENCE_PLOT : shape < 0.8f ? FENCE_IRREGULAR : FENCE_FRONT_ONLY;
    }

    /**
     * Fenced neighbours along the same road close the gap between them so one fence runs
     * between their gardens, and some country houses fence off an extra paddock behind.
     */
    private void connectNeighbourFences() {
        for (House house : houses) {
            if (!house.fenced) continue;
            // (other plots may have grown since they were filed, by up to half the gap a side)
            SpherePosition centre = world(house, house.plotCentreLocalX(), 0f, house.plotCentreLocalZ());
            for (House other : plotIndex.near(centre, house.plotRadius() + FENCE_JOIN_GAP * 2f)) {
                if (other == house || !other.fenced || other.frontagePath != house.frontagePath) continue;
                float[] otherX = plotAxes(house, other)[0];
                float turn = (float) Math.abs(Math.toDegrees(Math.atan2(otherX[1], otherX[0])));
                if (turn > 25f) continue;
                float[] local = local(house, other.frame.origin());
                if (Math.abs(local[1]) > house.depth) continue;
                if (local[0] > 0) {
                    float gap = (local[0] + other.plotMinX) - house.plotMaxX;
                    if (gap > -1f && gap < FENCE_JOIN_GAP) {
                        house.plotMaxX += Math.max(0f, gap * 0.5f);
                        house.sharedRight = true;
                    }
                } else {
                    float gap = house.plotMinX - (local[0] + other.plotMaxX);
                    if (gap > -1f && gap < FENCE_JOIN_GAP) {
                        house.plotMinX -= Math.max(0f, gap * 0.5f);
                    }
                }
            }
        }
        for (House house : houses) {
            if (!house.fenced || house.secure || house.fenceShape != FENCE_PLOT) continue;
            Random rand = new Random(house.seed ^ 0x9ADD0CL);
            if (rand.nextFloat() > 0.3f || settlementManager.getUrbanness(house.frame.origin()) > 0.2f) continue;
            // A paddock behind the garden, as wide as the plot or narrower
            float length = 15f + rand.nextFloat() * 35f;
            float inset = rand.nextFloat() * (house.plotMaxX - house.plotMinX) * 0.3f;
            float[] paddock = { house.plotMinX + (rand.nextBoolean() ? inset : 0f), house.plotMaxX - (rand.nextBoolean() ? inset : 0f),
                    house.plotMinZ - length, house.plotMinZ };
            if (isAreaFree(house, paddock)) house.paddock = paddock;
        }
    }

    /** True if a local rectangle of a house's land touches no road and no other plot. */
    private boolean isAreaFree(House house, float[] area) {
        for (int i = 0; i <= 4; i++) {
            for (int j = 0; j <= 4; j++) {
                float lx = area[0] + (area[1] - area[0]) * i / 4f;
                float lz = area[2] + (area[3] - area[2]) * j / 4f;
                SpherePosition place = world(house, lx, 0f, lz);
                if (isRoadLocation(place, 4f)) return false;
                if (TerrainMesh.getLayeredHeight(place, terrainNoise) <= seaLevel) return false;
                // (plots may have grown since they were filed, by up to half the join gap a side)
                for (House other : plotIndex.near(place, 2f + FENCE_JOIN_GAP)) {
                    if (other == house) continue;
                    float[] local = local(other, place);
                    if (local[0] >= other.plotMinX - 2f && local[0] <= other.plotMaxX + 2f
                            && local[1] >= other.plotMinZ - 2f && local[1] <= other.plotMaxZ + 2f) return false;
                }
            }
        }
        return true;
    }

    /**
     * The house's fence: its front follows the edge of the road it faces, with a gate in
     * front of the door; the rest outlines the plot, an irregular piece of it, or nothing
     * (front boundary only). A side a neighbour already fences is left to them.
     */
    private void bakeFence(NationBatch batch, House house) {
        FenceStyle style = fenceStyles.get(house.nationId);
        String part = !house.secure ? "fence" : style.securityType == FenceStyle.SecurityType.HIGH_WALL ? "secwall" : "security";
        MeshBuilder builder = batch.builder(part);
        builder.setPlanarTextureScale(1.0f / style.tileSize);

        // Front, right to left, pulled onto the line of the kerb
        List<float[]> front = new ArrayList<>();
        float span = house.plotMaxX - house.plotMinX;
        int samples = Math.max(2, (int) Math.ceil(span / 5f));
        for (int i = 0; i <= samples; i++) {
            float lx = house.plotMaxX - span * i / samples;
            front.add(followKerb(house, lx));
        }
        float[] frontRight = front.get(0), frontLeft = front.get(front.size() - 1);
        List<float[]> run = new ArrayList<>();
        // The gate in front of the door, widened to take in the driveway if there is one
        float gapMin = -GATE_HALF_WIDTH, gapMax = GATE_HALF_WIDTH;
        if (house.drivewaySide != 0) {
            gapMin = Math.min(gapMin, house.driveMinX - 1f);
            gapMax = Math.max(gapMax, house.driveMaxX + 1f);
        }
        for (float[] point : front) {
            if (point[0] > gapMin && point[0] < gapMax) {
                fenceLine(batch, builder, house, style, run);
                run = new ArrayList<>();
                continue;
            }
            run.add(point);
        }
        fenceLine(batch, builder, house, style, run);

        float minX = house.plotMinX, maxX = house.plotMaxX, minZ = house.plotMinZ;
        if (house.fenceShape == FENCE_FRONT_ONLY) {
            // Just short returns down each side
            float returnZ = Math.max(minZ, frontRight[1] - 8f);
            if (!house.sharedRight) fenceLine(batch, builder, house, style, List.of(frontRight, new float[] { maxX, returnZ }));
            fenceLine(batch, builder, house, style, List.of(new float[] { minX, Math.max(minZ, frontLeft[1] - 8f) }, frontLeft));
            return;
        }
        List<float[]> back = new ArrayList<>();
        if (house.fenceShape == FENCE_IRREGULAR) {
            // An irregular outline: back corners cut in by different amounts, the back edge kinked
            Random rand = new Random(house.seed ^ 0x1F3A5L);
            float depthSpan = frontRight[1] - minZ;
            back.add(new float[] { maxX, minZ + depthSpan * (0.15f + 0.35f * rand.nextFloat()) });
            back.add(new float[] { maxX - span * (0.1f + 0.25f * rand.nextFloat()), minZ });
            back.add(new float[] { minX + span * (0.3f + 0.4f * rand.nextFloat()), minZ + depthSpan * 0.15f * rand.nextFloat() });
            back.add(new float[] { minX, minZ + depthSpan * (0.1f + 0.3f * rand.nextFloat()) });
        } else {
            back.add(new float[] { maxX, minZ });
            back.add(new float[] { minX, minZ });
        }
        List<float[]> outline = new ArrayList<>();
        outline.add(frontRight);
        outline.addAll(back);
        outline.add(frontLeft);
        // The right side is the first stretch; a neighbour may already have fenced it
        if (house.sharedRight) {
            fenceLine(batch, builder, house, style, outline.subList(1, outline.size()));
        } else {
            fenceLine(batch, builder, house, style, outline);
        }
        if (house.paddock != null) {
            float[] p = house.paddock;
            fenceLine(batch, builder, house, style, List.of(new float[] { p[0], p[3] }, new float[] { p[0], p[2] },
                    new float[] { p[1], p[2] }, new float[] { p[1], p[3] }));
        }
    }

    /** A point on the front fence at local x: on the kerb line of the road the house faces, if it is near. */
    private float[] followKerb(House house, float localX) {
        float bestDistance = Float.MAX_VALUE;
        float[] best = null;
        // (the road put in the house's frame)
        for (RoadSegment segment : segmentsNear(world(house, localX, 0f, house.plotMaxZ), 60f)) {
            if (segment.pathIndex != house.frontagePath || segment.culDeSac) continue;
            float[] nearest = nearestOnLine(localX, house.plotMaxZ, local(house, segment.from), local(house, segment.to));
            float dx = localX - nearest[0], dz = house.plotMaxZ - nearest[1];
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance < bestDistance && distance > 1e-3f) {
                bestDistance = distance;
                float reach = segment.width * 0.5f + FRONT_FENCE_GAP;
                best = new float[] { nearest[0] + dx / distance * reach, nearest[1] + dz / distance * reach };
            }
        }
        if (best == null || bestDistance > 60f) return new float[] { localX, house.plotMaxZ };
        // Stay in front of the house and not too far from the plot's own line
        float z = Math.max(house.depth * 0.5f + 4f, Math.min(house.plotMaxZ + 20f, best[1]));
        return new float[] { localX, z };
    }

    /** Chart bridge (D13): the point on a segment nearest a chart point, on the chart (for people and vehicles). */
    private static float[] nearestOnSegment(float x, float z, RoadSegment segment) {
        return nearestOnLine(x, z, new float[] { segment.chartStart.x, segment.chartStart.z },
                new float[] { segment.chartEnd.x, segment.chartEnd.z });
    }

    /** The point on the line from a to b (each {x, z}) nearest (x, z). */
    private static float[] nearestOnLine(float x, float z, float[] a, float[] b) {
        float ax = a[0], az = a[1];
        float bx = b[0] - ax, bz = b[1] - az;
        float lengthSquared = bx * bx + bz * bz;
        float t = lengthSquared < 1e-6f ? 0f : Math.max(0f, Math.min(1f, ((x - ax) * bx + (z - az) * bz) / lengthSquared));
        return new float[] { ax + bx * t, az + bz * t };
    }

    /** Builds fence along a polyline of points in the house's frame. */
    private void fenceLine(NationBatch batch, MeshBuilder builder, House house, FenceStyle style, List<float[]> localPoints) {
        for (int i = 0; i + 1 < localPoints.size(); i++) {
            float[] a = localPoints.get(i), b = localPoints.get(i + 1);
            // (solid only where it's actually built: see fenceRun)
            fenceRun(batch, builder, style, house, a[0], a[1], b[0], b[1]);
        }
    }

    /** One straight run of a house's fence, between two points in its frame. */
    private void fenceRun(NationBatch batch, MeshBuilder builder, FenceStyle style, House house,
                          float startX, float startZ, float endX, float endZ) {
        boolean secure = house.secure;
        float length = (float) Math.hypot(endX - startX, endZ - startZ);
        if (length < 1.0f) {
            return;
        }
        int pieces = Math.max(1, Math.round(length / style.postSpacing));
        float dirX = (endX - startX) / length;
        float dirZ = (endZ - startZ) / length;
        float h = secure ? style.securityHeight : style.height;
        boolean posts = secure ? style.securityType != FenceStyle.SecurityType.HIGH_WALL
                : style.type != FenceStyle.Type.WALL && style.type != FenceStyle.Type.HEDGE;

        float[][] points = new float[pieces + 1][];
        for (int i = 0; i <= pieces; i++) {
            float t = (float) i / pieces;
            float x = startX + (endX - startX) * t;
            float z = startZ + (endZ - startZ) * t;
            points[i] = new float[] { x, groundAt(house, x, z), z };
            batch.include(x, points[i][1] + h, z, 2.0f);
        }

        for (int i = 0; i < pieces; i++) {
            float[] a = points[i];
            float[] b = points[i + 1];
            // A bending road may clip the corner of a garden; the fence stops short of it
            if (isRoadLocation(world(house, (a[0] + b[0]) * 0.5f, 0f, (a[2] + b[2]) * 0.5f), 1.0f)) {
                continue;
            }
            recordSegment(world(house, a[0], 0f, a[2]), world(house, b[0], 0f, b[2]), secure ? 0.8f : 0.4f, h);
            if (secure) {
                switch (style.securityType) {
                    case HIGH_WALL -> {
                        addPrism(builder, a, b, 2.2f, -1.0f, h);
                        addPrism(builder, a, b, 2.8f, h, h + 0.7f);
                        // A row of spikes along the coping
                        uprights(builder, a, b, dirX, dirZ, 3.0f, 0.15f, h + 0.7f, 1.6f);
                    }
                    case MESH -> {
                        addPrism(builder, a, b, 0.25f, 0.6f, 1.0f);
                        addPrism(builder, a, b, 0.25f, h * 0.5f - 0.2f, h * 0.5f + 0.2f);
                        addPrism(builder, a, b, 0.25f, h - 0.4f, h);
                        uprights(builder, a, b, dirX, dirZ, 2.4f, 0.1f, 0.2f, h - 0.2f);
                        // Strands of wire strung above
                        for (int wire = 1; wire <= 2; wire++) {
                            addPrism(builder, a, b, 0.12f, h + wire * 0.7f - 0.06f, h + wire * 0.7f + 0.06f);
                        }
                    }
                    default -> {
                        addPrism(builder, a, b, 0.35f, 1.0f, 1.5f);
                        addPrism(builder, a, b, 0.35f, h - 1.6f, h - 1.1f);
                        // Bars rising past the top rail into points
                        uprights(builder, a, b, dirX, dirZ, 2.3f, 0.2f, -0.3f, h + 0.9f);
                    }
                }
            } else {
                switch (style.type) {
                    case WALL:
                    case HEDGE:
                        addPrism(builder, a, b, style.thickness, -1.0f, h);
                        break;
                    case PANEL:
                        addPrism(builder, a, b, style.thickness, -0.5f, h);
                        break;
                    case RAIL:
                        addPrism(builder, a, b, 0.5f, h * 0.3f - 0.3f, h * 0.3f + 0.3f);
                        addPrism(builder, a, b, 0.5f, h * 0.6f - 0.3f, h * 0.6f + 0.3f);
                        addPrism(builder, a, b, 0.5f, h * 0.9f - 0.3f, h * 0.9f + 0.3f);
                        break;
                    case PICKET:
                    default:
                        addPrism(builder, a, b, 0.5f, h * 0.25f - 0.3f, h * 0.25f + 0.3f);
                        addPrism(builder, a, b, 0.5f, h * 0.7f - 0.3f, h * 0.7f + 0.3f);
                        uprights(builder, a, b, dirX, dirZ, 3.5f, 0.3f, -0.5f, h + 0.5f);
                        break;
                }
            }
            if (posts) {
                addBox(builder, a[0], a[1] - 1.0f, a[2], dirX, dirZ, 0.6f, 0.6f, h + 1.5f);
            }
        }
        float[] last = points[pieces];
        if (posts && !isRoadLocation(world(house, last[0], 0f, last[2]), 1.0f)) {
            addBox(builder, last[0], last[1] - 1.0f, last[2], dirX, dirZ, 0.6f, 0.6f, h + 1.5f);
        }
    }

    // ==========================================
    //          FENCED BORDERS
    // ==========================================

    /**
     * Where a tarmac road crosses a border that gets a check or traffic lights, in a frame of
     * its own (D3): on the datum at the crossing, local x along the road the way its path runs,
     * local z across it towards the side the near booth stands (the near side's nation's).
     */
    private static final class BorderCheck {
        private final int index;
        private final SphereFrame frame;
        // The frame's turn from east, towards south (its local x's heading there)
        private final double heading;
        // The road's half width
        private final float half;
        private final int nearNation, farNation;
        // Traffic lights (the two sides drive on different sides of the road), and a fenced
        // border's checks and booths
        private final boolean lights, fenced;
        // Chart bridge (D13): the crossing on the chart, for which chunk builds its markings
        private final float chartX, chartZ;

        private BorderCheck(int index, SphereFrame frame, double heading, float half, int nearNation, int farNation,
                            boolean lights, boolean fenced) {
            this.index = index;
            this.frame = frame;
            this.heading = heading;
            this.half = half;
            this.nearNation = nearNation;
            this.farNation = farNation;
            this.lights = lights;
            this.fenced = fenced;
            double[] chart = Planet.mercatorPosition(frame.origin());
            this.chartX = (float) chart[0];
            this.chartZ = (float) chart[2];
        }

        /**
         * Chart bridge (D13) for the vehicles: a place and a chart heading direction there
         * (the chart is conformal, so it is the local east/south one) in this check's frame, as
         * {x, z, the way's x, z}.
         */
        private float[] localWay(SpherePosition place, float dirX, float dirZ) {
            double[] local = frame.fromSphere(place);
            double way = SphereMath.carryHeading(place, frame.origin(), Math.atan2(dirZ, dirX)) - heading;
            return new float[] { (float) local[0], (float) local[2], (float) Math.cos(way), (float) Math.sin(way) };
        }
    }

    /** A border check's booth, in a frame of its own: local x along the road, its guard looking along local z by guard (1 or -1). */
    private static final class Booth {
        private final SphereFrame frame;
        private final double heading;
        private final int nation;
        private final float guard;
        // Chart bridge (D13): where it is on the chart, for which chunk builds it
        private final float chartX, chartZ;

        private Booth(SphereFrame frame, double heading, int nation, float guard) {
            this.frame = frame;
            this.heading = heading;
            this.nation = nation;
            this.guard = guard;
            double[] chart = Planet.mercatorPosition(frame.origin());
            this.chartX = (float) chart[0];
            this.chartZ = (float) chart[2];
        }
    }

    private final List<BorderCheck> borderChecks = new ArrayList<>();
    private final CellIndex<BorderCheck> checkIndex = new CellIndex<>(LOOKUP_LEVEL);
    // The booths, filed reaching as far as the room the border fence leaves them
    private final List<Booth> booths = new ArrayList<>();
    private final CellIndex<Booth> boothIndex = new CellIndex<>(LOOKUP_LEVEL);
    // How far back from the border the booth stands, how far out from the road's edge, and
    // how close a vehicle comes before it stops to be checked
    private static final float BOOTH_BACK = 14f, BOOTH_OUT = 14f;
    // A booth's part: its wall variant's material, drawn from both sides
    private static final String BOOTH_PART = "booth_variant";
    // The booth's half width, wall and roof heights; and how far round its middle the border
    // fence is pushed back to leave it room
    private static final float BOOTH_HALF = 10f, BOOTH_WALL = 9f, BOOTH_ROOF = 34f, BOOTH_ROOM = 19f;

    // How far either side of a border crossing the road is made straight
    private static final float STRAIGHT_REACH = 75f;

    /**
     * Where a tarmac road crosses a border that gets a check or traffic lights, the road runs
     * dead straight for a way either side (along the way it was heading there), so the booths,
     * lights and markings all line up with it and nothing stands in the road.
     */
    private void straightenCrossings() {
        for (RoadPath path : roadNetwork) {
            if (path.roadClass == RoadPath.RoadClass.DIRT) continue;
            List<SpherePosition> pts = path.points;
            for (int i = 0; i + 1 < pts.size(); i++) {
                SpherePosition a = pts.get(i), b = pts.get(i + 1);
                int na = nationAt(a), nb = nationAt(b);
                if (na == nb || na < 1 || nb < 1) continue;
                if (!kinship.hostile(na, nb) && drivingSide(na) == drivingSide(nb)) continue;
                SpherePosition p = borderCrossing(a, b, na);
                // Its heading there, from a little way either side
                SpherePosition before = pts.get(Math.max(0, i - 1)), after = pts.get(Math.min(pts.size() - 1, i + 2));
                if (SphereMath.distance(before, after) < 1e-3) continue;
                SphereFrame frame = SphereFrame.at(p);
                double heading = frame.headingOf(after.x() - before.x(), after.y() - before.y(), after.z() - before.z());
                // The points within reach either side go; a straight run (along the great
                // circle through the crossing) takes their place
                int first = i, last = i + 1;
                while (first > 0 && SphereMath.distance(pts.get(first), p) < STRAIGHT_REACH) first--;
                while (last < pts.size() - 1 && SphereMath.distance(pts.get(last), p) < STRAIGHT_REACH) last++;
                List<SpherePosition> run = new ArrayList<>();
                for (float t = -STRAIGHT_REACH; t <= STRAIGHT_REACH + 0.01f; t += 15f) {
                    run.add(TerrainMesh.groundAt(SphereMath.move(p, heading, t), terrainNoise));
                }
                // (the run goes the path's own way)
                List<SpherePosition> keepHead = new ArrayList<>(pts.subList(0, first + 1));
                List<SpherePosition> keepTail = new ArrayList<>(pts.subList(last, pts.size()));
                double[] way = frame.headingDirection(heading);
                boolean forward = (b.x() - a.x()) * way[0] + (b.y() - a.y()) * way[1] + (b.z() - a.z()) * way[2] >= 0.0;
                if (!forward) java.util.Collections.reverse(run);
                pts.clear();
                pts.addAll(keepHead);
                pts.addAll(run);
                pts.addAll(keepTail);
                i = keepHead.size() + run.size() - 1;
            }
        }
    }

    /** Finds every place a tarmac road crosses a fenced border, and where its booths stand (D4: planned on the sphere). */
    private void findBorderChecks() {
        for (RoadPath path : roadNetwork) {
            if (path.roadClass == RoadPath.RoadClass.DIRT) continue;
            List<SpherePosition> pts = path.points;
            for (int i = 0; i + 1 < pts.size(); i++) {
                SpherePosition a = pts.get(i), b = pts.get(i + 1);
                int na = nationAt(a), nb = nationAt(b);
                // Fenced borders get a check; open ones (alike cultures) only if the two drive on
                // different sides of the road, for the traffic lights
                if (na == nb || na < 1 || nb < 1) continue;
                boolean fenced = kinship.hostile(na, nb);
                if (!fenced && drivingSide(na) == drivingSide(nb)) continue;
                if (SphereMath.distance(a, b) < 1e-3) continue;
                SpherePosition p = SphereMath.atAltitude(borderCrossing(a, b, na), 0.0);
                if (checkIndex.nearest(p, 40.0) != null) continue;
                double heading = SphereFrame.at(p).headingOf(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
                float half = path.roadClass.width * 0.5f;
                // Where the two sides drive on different sides of the road, a crossover: traffic
                // lights at each end, taking turns, and (at a fenced border) a booth at each end
                boolean lights = drivingSide(na) != drivingSide(nb);
                BorderCheck check = new BorderCheck(borderChecks.size(), SphereFrame.at(p, heading), heading, half, na, nb, lights, fenced);
                if (fenced) {
                    // On the near side of the border (at a crossover, its near end), beside the
                    // road, its guard looking out over it; and the far end's, across the road
                    float back = lights ? CROSS_HALF : BOOTH_BACK;
                    addBooth(check, -back, half + BOOTH_OUT, na, -1f);
                    if (lights) addBooth(check, back, -(half + BOOTH_OUT), nb, 1f);
                }
                borderChecks.add(check);
                checkIndex.add(p, check);
            }
        }
        int lit = 0;
        for (BorderCheck c : borderChecks) if (c.lights) lit++;
        System.out.printf("[BORDERS] %d border checks on the roads, %d with traffic lights%n", borderChecks.size(), lit);
    }

    /** A booth at a point in a check's frame, its axes carried over from the check's. */
    private void addBooth(BorderCheck check, float along, float across, int nation, float guard) {
        SpherePosition at = check.frame.toSphere(along, 0.0, across);
        double heading = SphereMath.carryHeading(check.frame.origin(), at, check.heading);
        Booth booth = new Booth(SphereFrame.at(at, heading), heading, nation, guard);
        booths.add(booth);
        boothIndex.add(at, BOOTH_ROOM, booth);
    }

    // How far either side of the border a crossover's ends (its lights and booths) are
    private static final float CROSS_HALF = 45f;

    // Traffic lights at a crossing: how long each side's traffic has to cross, and the pause
    // between (both lights off) while the last across clear the crossing; how far back from the
    // border each side's cars wait at a red light; how far from a crossing the guard rails stop
    private static final float LIGHT_GO = 14f, LIGHT_CLEAR = 7f, LIGHT_STOP_BACK = CROSS_HALF, RAIL_CLEAR_OF_BORDER = CROSS_HALF + 30f;

    /**
     * Whose turn it is to cross at border check i: 1 the traffic coming from the booth's side,
     * 2 that from the other side, 0 neither (the pause between); always 3 (anyone) where there
     * are no lights.
     */
    public int borderTurn(int i) {
        if (!borderChecks.get(i).lights) return 3;
        float cycle = 2f * (LIGHT_GO + LIGHT_CLEAR);
        // (each crossing a little out of step with the others)
        double t = (System.currentTimeMillis() / 1000.0 + i * 7.3) % cycle;
        if (t < LIGHT_GO) return 1;
        if (t < LIGHT_GO + LIGHT_CLEAR) return 0;
        if (t < 2f * LIGHT_GO + LIGHT_CLEAR) return 2;
        return 0;
    }

    /**
     * Chart bridge (D13) for the vehicles, on the chart until Phase 4: how far ahead (in chart
     * units) the line is where a vehicle at (x, z) heading (dirX, dirZ) must wait at a border's
     * traffic light that isn't its turn; NaN if it may go on (or there's none within reach).
     * Worked in each check's frame, in true units.
     */
    public float borderHoldAhead(float x, float z, float dirX, float dirZ, float reach) {
        SpherePosition place = sphere(x, z);
        float stretch = chartStretch(z), trueReach = reach / stretch;
        float nearest = Float.NaN;
        for (BorderCheck c : checkIndex.near(place, trueReach + LIGHT_STOP_BACK)) {
            if (!c.lights) continue;
            float[] v = c.localWay(place, dirX, dirZ);
            // (the way along the road: with it, from the booth's side, or against it)
            float with = v[2];
            if (Math.abs(with) < 0.3f) continue;
            int side = with > 0f ? 1 : 2;
            if (borderTurn(c.index) == side) continue;
            float lx = (with > 0f ? -LIGHT_STOP_BACK : LIGHT_STOP_BACK) - v[0], lz = -v[1];
            float ahead = lx * v[2] + lz * v[3];
            if (ahead < -1.5f || ahead > trueReach || Math.abs(lz) > c.half + 6f) continue;
            if (Float.isNaN(nearest) || ahead < nearest) nearest = ahead;
        }
        return Float.isNaN(nearest) ? nearest : nearest * stretch;
    }

    // Each world's two traffic light colours (one for each side's turn), and how they're arranged
    private float[][] lightColours;

    /**
     * Chart bridge (D13) for the people, who draw the lights until Phase 4: the traffic lights
     * at the crossings, placed in each check's frame, as {x, z (the pole's foot on the chart),
     * the way the road runs there x, z, whether the lamps are one above the other (1) or side
     * by side (0), the crossing's number, colour A rgb (the booth's side's turn), colour B rgb}.
     */
    public List<float[]> borderLights() {
        if (lightColours == null) {
            Random rand = new Random(worldSeed * 211L + 5L);
            float hue = rand.nextFloat();
            lightColours = new float[][] { WorldPalette.hsv(hue, 0.85f, 1f), WorldPalette.hsv(hue + 0.3f + rand.nextFloat() * 0.4f, 0.85f, 1f) };
        }
        List<float[]> lights = new ArrayList<>();
        for (BorderCheck c : borderChecks) {
            if (!c.lights) continue;
            float vertical = new Random(worldSeed * 7L + c.index).nextBoolean() ? 1f : 0f;
            float[] a = lightColours[0], b = lightColours[1];
            // One at each end of the crossover, across the road from that end's booth
            for (float end = -1f; end <= 1f; end += 2f) {
                SpherePosition foot = c.frame.toSphere(CROSS_HALF * end, 0.0, (c.half + 5f) * end);
                float[] at = chartPoint(foot, 0f, c.chartX);
                double way = SphereMath.carryHeading(c.frame.origin(), foot, c.heading);
                lights.add(new float[] { at[0], at[2], (float) Math.cos(way), (float) Math.sin(way), vertical, c.index,
                        a[0], a[1], a[2], b[0], b[1], b[2] });
            }
        }
        return lights;
    }

    /** Chart bridge (D13) for the people: the border checks' booths, {x, z, the way their guard faces x, z, nation}. */
    public List<float[]> borderBooths() {
        List<float[]> list = new ArrayList<>();
        for (Booth b : booths) {
            double facing = b.heading + b.guard * Math.PI * 0.5;
            list.add(new float[] { b.chartX, b.chartZ, (float) Math.cos(facing), (float) Math.sin(facing), b.nation });
        }
        return list;
    }

    /**
     * Chart bridge (D13) for the vehicles: the next border check on the road ahead of a vehicle
     * at (x, z) heading (dirX, dirZ), as {its number, how far ahead (in chart units) the place to
     * stop beside its booth is}; null if none within reach. Vehicles from either way stop level
     * with the booth. Worked in each check's frame, in true units.
     */
    public float[] borderStopAhead(float x, float z, float dirX, float dirZ, float reach) {
        SpherePosition place = sphere(x, z);
        float stretch = chartStretch(z), trueReach = reach / stretch;
        float[] best = null;
        for (BorderCheck c : checkIndex.near(place, trueReach + CROSS_HALF)) {
            if (!c.fenced) continue;   // (an open border: no check)
            float[] v = c.localWay(place, dirX, dirZ);
            // Level with the booth, on the road's middle line: at a crossover, the booth at the
            // end the vehicle comes in by
            float back = c.lights ? (v[2] >= 0f ? -CROSS_HALF : CROSS_HALF) : -BOOTH_BACK;
            float ox = back - v[0], oz = -v[1];
            float ahead = ox * v[2] + oz * v[3];
            // (how far off the road's line it is: within the road)
            if (ahead < -2f || ahead > trueReach || Math.abs(oz) > c.half + 6f || Math.abs(ox) > trueReach) continue;
            if (best == null || ahead * stretch < best[1]) best = new float[] { c.index, ahead * stretch };
        }
        return best;
    }

    /** Where the border fence must keep clear of a booth: pushed out round its middle. */
    private SpherePosition clearOfBooths(SpherePosition p) {
        for (Booth booth : boothIndex.near(p, 0.0)) {
            double[] local = booth.frame.fromSphere(p);
            double d = Math.hypot(local[0], local[2]);
            if (d >= BOOTH_ROOM) continue;
            if (d < 1e-3) return booth.frame.toSphere(BOOTH_ROOM, 0.0, 0.0);
            return booth.frame.toSphere(local[0] / d * BOOTH_ROOM, 0.0, local[2] / d * BOOTH_ROOM);
        }
        return p;
    }

    /** Whether a sign would stand at a border crossing (its booths, lights and gates). */
    private boolean nearBorderCheck(InfrastructureObject sign) {
        return borderCheckAt(sign.frame.origin(), CROSS_HALF + 35f) != null;
    }

    /** The border check within reach of a place (the nearest), or null. */
    private BorderCheck borderCheckAt(SpherePosition place, float reach) {
        return checkIndex.nearest(SphereMath.atAltitude(place, 0.0), reach);
    }

    /**
     * The booths of any border checks in this chunk, each built in its own frame: a small
     * concrete box beside the road, waist high, with a pillar at each corner holding up a flat
     * roof (its guard stands in it: see Inhabitants).
     */
    private void borderBooths(Map<Integer, NationBatch> batches, int cx, int cz) {
        for (Booth booth : booths) {
            if (chunkIndex(booth.chartX) != cx || chunkIndex(booth.chartZ) != cz) continue;
            NationBatch batch = batches.computeIfAbsent(booth.nation, NationBatch::new);
            SpherePosition origin = booth.frame.origin();
            // Built of the plain local material (the nation's walls there), seen from inside and out
            int variant = chooseWallVariant(origin, booth.nation, (long) (origin.x() * 31.0) ^ (long) (origin.z() * 17.0));
            MeshBuilder concrete = batch.builder(BOOTH_PART + variant);
            concrete.setPlanarTextureScale(1.0f / wallVariants.get(variant).tileWidth);
            batch.place(onChart(booth.frame, false, booth.chartX));
            float ground = TerrainMesh.getLayeredHeight(origin, terrainNoise);
            float half = BOOTH_HALF, wall = BOOTH_WALL, roof = BOOTH_ROOF;
            // Four thin walls round its floor, chest high to the guard standing inside (local x
            // along the road, z across it)
            float thick = 0.8f;
            for (float sign = -1f; sign <= 1f; sign += 2f) {
                float o = sign * (half - thick);
                // (front and back, running across; then the sides, running along)
                addBox(concrete, o, ground - 2f, 0f, 0f, 1f, half, thick, wall + 2f);
                addBox(concrete, 0f, ground - 2f, o, 1f, 0f, half, thick, wall + 2f);
            }
            for (int k = 0; k < 4; k++) {
                float sa = (k & 1) == 0 ? -1f : 1f, sb = (k & 2) == 0 ? -1f : 1f;
                addBox(concrete, sa * (half - 1.2f), ground + wall, sb * (half - 1.2f), 1f, 0f, 1.1f, 1.1f, roof - wall);
            }
            float r = half + 3.2f;
            addBox(concrete, 0f, ground + roof, 0f, 1f, 0f, r, r, 2.6f);
            // (the roof's underside, which a box leaves open)
            float[][] under = new float[4][];
            float[][] signs = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
            for (int k = 0; k < 4; k++) {
                under[k] = new float[] { r * signs[k][0], ground + roof, r * signs[k][1] };
            }
            concrete.addConvexFace(new float[] { 0f, ground + roof + 2f, 0f }, under);
            batch.include(0f, ground + roof + 2f, 0f, half + 3f);
            batch.place(null);
            recordCircle(origin, half + 0.5f);
        }
    }

    /**
     * The crossovers' road markings, in the check's frame: two lines crossing in an X over the
     * middle, from each side's lane at one end to the other side of the road at the other.
     */
    private void crossoverLines(Map<Integer, NationBatch> batches, int cx, int cz) {
        for (BorderCheck c : borderChecks) {
            if (!c.lights || chunkIndex(c.chartX) != cx || chunkIndex(c.chartZ) != cz) continue;
            NationBatch batch = batches.computeIfAbsent(c.nearNation, NationBatch::new);
            MeshBuilder paint = batch.builder("line");
            // (laid along the ground: local x along the road, z across it)
            batch.place(onChart(c.frame, true, c.chartX));
            float lane = c.half * 0.5f, reach = CROSS_HALF - 8f, w = 0.9f;
            int steps = 16;
            for (float flip = -1f; flip <= 1f; flip += 2f) {
                for (int k = 0; k < steps; k++) {
                    float t0 = k / (float) steps, t1 = (k + 1) / (float) steps;
                    float[][] q = new float[4][];
                    for (int e = 0; e < 2; e++) {
                        float t = e == 0 ? t0 : t1;
                        float along = -reach + 2f * reach * t, lat = flip * lane * (1f - 2f * t);
                        // (across the line, square to the road)
                        float y = roadSurfaceY(c.frame.toSphere(along, 0.0, lat)) + 0.15f;
                        q[e * 2] = new float[] { along, y, lat - w };
                        q[e * 2 + 1] = new float[] { along, y, lat + w };
                    }
                    int a = paint.addVertex(q[0][0], q[0][1], q[0][2], 0f, 1f, 0f, 0f, 0f);
                    int b = paint.addVertex(q[1][0], q[1][1], q[1][2], 0f, 1f, 0f, 1f, 0f);
                    int d = paint.addVertex(q[2][0], q[2][1], q[2][2], 0f, 1f, 0f, 0f, 1f);
                    int e = paint.addVertex(q[3][0], q[3][1], q[3][2], 0f, 1f, 0f, 1f, 1f);
                    // (both windings: whichever faces up is drawn)
                    paint.addTriangle(a, b, e);
                    paint.addTriangle(a, e, d);
                    paint.addTriangle(a, e, b);
                    paint.addTriangle(a, d, e);
                }
            }
            batch.include(0f, roadSurfaceY(c.frame.origin()) + 1f, 0f, CROSS_HALF + 4f);
            batch.place(null);
        }
    }

    // Border fences: how often the border is sampled (and so the length of each stretch of
    // fence), how tall the fence is, the gap for a road through it, and how far the
    // gateposts stand from the road's middle
    private static final float BORDER_STEP = 6f, BORDER_FENCE_HEIGHT = 26f, BORDER_GATE_CLEARANCE = 4f;

    /**
     * The fence along any border in this chunk between nations at odds (see
     * NationKinship.hostile): tall chain-link on steel posts, barbed wire strung along arms
     * leaning out at the top, and where a road crosses, a gap between heavy gateposts with
     * the gates swung open back along the fence. The border is traced on a grid fixed to the
     * world (marching squares, each crossing found closely by halving), so neighbouring chunks
     * meet; under water there is none.
     */
    private void borderFences(Map<Integer, NationBatch> batches, int cx, int cz) {
        float size = roadChunkSize;
        float x0 = (cx - 0.5f) * size, z0 = (cz - 0.5f) * size;
        // Far from any such border (the borders wander up to a couple of cells from the map's)
        if (borderGapAt(x0 + size * 0.5f, z0 + size * 0.5f) > 3.5f) return;
        int i0 = (int) Math.ceil(x0 / BORDER_STEP), i1 = (int) Math.ceil((x0 + size) / BORDER_STEP);
        int j0 = (int) Math.ceil(z0 / BORDER_STEP), j1 = (int) Math.ceil((z0 + size) / BORDER_STEP);
        int w = i1 - i0 + 1, h = j1 - j0 + 1;
        int[] owner = new int[w * h];
        boolean any = false;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                owner[j * w + i] = nationAt((i0 + i) * BORDER_STEP, (j0 + j) * BORDER_STEP);
                if (owner[j * w + i] != owner[0]) any = true;
            }
        }
        if (!any) return;
        for (int j = 0; j + 1 < h; j++) {
            for (int i = 0; i + 1 < w; i++) {
                // Corners round the square, and the crossings on its sides between nations at odds
                float ax = (i0 + i) * BORDER_STEP, az = (j0 + j) * BORDER_STEP;
                float[][] corner = { { ax, az }, { ax + BORDER_STEP, az }, { ax + BORDER_STEP, az + BORDER_STEP }, { ax, az + BORDER_STEP } };
                int[] id = { owner[j * w + i], owner[j * w + i + 1], owner[(j + 1) * w + i + 1], owner[(j + 1) * w + i] };
                List<float[]> crossings = new ArrayList<>();
                int a = 0, b = 0;
                for (int k = 0; k < 4; k++) {
                    int n = (k + 1) % 4;
                    if (!kinship.hostile(id[k], id[n])) continue;
                    crossings.add(borderCrossing(corner[k], corner[n], id[k]));
                    a = id[k];
                    b = id[n];
                }
                if (crossings.isEmpty()) continue;
                NationBatch batch = batches.computeIfAbsent(Math.min(a, b), NationBatch::new);
                if (crossings.size() == 2) {
                    borderFencePiece(batch, crossings.get(0), crossings.get(1));
                } else if (crossings.size() == 4) {
                    borderFencePiece(batch, crossings.get(0), crossings.get(1));
                    borderFencePiece(batch, crossings.get(2), crossings.get(3));
                } else {
                    // Three nations meet: each crossing runs in to the middle
                    float[] middle = { ax + BORDER_STEP * 0.5f, az + BORDER_STEP * 0.5f };
                    for (float[] crossing : crossings) borderFencePiece(batch, crossing, middle);
                }
            }
        }
    }

    /** Whether a house's plot (with a margin) reaches across a fenced border, where the fence would run through it. */
    private boolean straddlesFencedBorder(House house) {
        // (nor on a border check's booth)
        if (!boothIndex.near(house.frame.origin(), house.plotRadius() + 6f).isEmpty()) return true;
        if (borderGapAt(house.x, house.z) > 3.5f) return false;
        float margin = 4f;
        float[][] corners = { { house.plotMinX - margin, house.plotMinZ - margin }, { house.plotMaxX + margin, house.plotMinZ - margin },
                { house.plotMaxX + margin, house.plotMaxZ + margin }, { house.plotMinX - margin, house.plotMaxZ + margin } };
        int own = house.nationId;
        for (float[] c : corners) {
            if (kinship.hostile(own, nationAt(world(house, c[0], 0f, c[1])))) return true;
        }
        return false;
    }

    private int nationAt(float x, float z) {
        return nationManager.getNationAtWorld(x, z, Planet.width());
    }

    private int nationAt(SpherePosition place) {
        return nationManager.getNationAt(place);
    }

    /** Where along the great circle between two places the border lies, found by halving (a belongs to nation first). */
    private SpherePosition borderCrossing(SpherePosition a, SpherePosition b, int first) {
        double lo = 0.0, hi = 1.0;
        for (int k = 0; k < 4; k++) {
            double mid = (lo + hi) * 0.5;
            if (nationAt(SphereMath.slerp(a, b, mid)) == first) lo = mid; else hi = mid;
        }
        return SphereMath.slerp(a, b, (lo + hi) * 0.5);
    }

    /** Where between two points the border lies, found by halving (a belongs to nation first). */
    private float[] borderCrossing(float[] a, float[] b, int first) {
        float lo = 0f, hi = 1f;
        for (int k = 0; k < 4; k++) {
            float mid = (lo + hi) * 0.5f;
            if (nationAt(a[0] + (b[0] - a[0]) * mid, a[1] + (b[1] - a[1]) * mid) == first) lo = mid; else hi = mid;
        }
        float t = (lo + hi) * 0.5f;
        return new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t };
    }

    /**
     * One stretch of border fence between two points on the border (with a gatepost where a
     * road begins), built in a frame of its own at its middle (D4). The border is traced on the
     * chart, as the nation map it follows is (D12): from and to are its ends there.
     */
    private void borderFencePiece(NationBatch batch, float[] fromChart, float[] toChart) {
        // (bending back round any booth beside the gateway)
        SpherePosition from = clearOfBooths(sphere(fromChart[0], fromChart[1]));
        SpherePosition to = clearOfBooths(sphere(toChart[0], toChart[1]));
        if (SphereMath.distance(from, to) < 0.05) return;
        SphereFrame frame = SphereFrame.at(SphereMath.slerp(from, to, 0.5));
        double[] fa = frame.fromSphere(from), fb = frame.fromSphere(to);
        float length = (float) Math.hypot(fb[0] - fa[0], fb[2] - fa[2]);
        if (length < 0.05f) return;
        float dirX = (float) (fb[0] - fa[0]) / length, dirZ = (float) (fb[2] - fa[2]) / length;
        float[] a = { (float) fa[0], TerrainMesh.getLayeredHeight(from, terrainNoise), (float) fa[2] };
        float[] b = { (float) fb[0], TerrainMesh.getLayeredHeight(to, terrainNoise), (float) fb[2] };
        if (Math.min(a[1], b[1]) < seaLevel + 0.5f) return;
        boolean roadA = isRoadLocation(from, BORDER_GATE_CLEARANCE), roadB = isRoadLocation(to, BORDER_GATE_CLEARANCE);
        float hgt = BORDER_FENCE_HEIGHT;
        if (roadA && roadB) return;
        // (laid along the ground: y is the height above the datum)
        batch.place(onChart(frame, true, fromChart[0]));
        if (roadA || roadB) {
            // The gateway: a heavy post at the road's side, and the gate swung back along the fence
            float[] post = roadA ? b : a;
            float awayX = roadA ? dirX : -dirX, awayZ = roadA ? dirZ : -dirZ;
            MeshBuilder posts = batch.builder("borderpost");
            posts.setPlanarTextureScale(0.1f);
            addBox(posts, post[0], post[1] - 2f, post[2], dirX, dirZ, 1.6f, 1.6f, hgt + 8f);
            batch.include(post[0], post[1] + hgt + 8f, post[2], 4f);
            recordCircle(roadA ? to : from, 2.2f);
            MeshBuilder gate = batch.builder("borderfence");
            float side = 2.4f;
            float[] hinge = { post[0] - awayZ * side, post[1], post[2] + awayX * side };
            float freeX = hinge[0] + awayX * 12f, freeZ = hinge[2] + awayZ * 12f;
            float[] free = { freeX, TerrainMesh.getLayeredHeight(frame.toSphere(freeX, 0.0, freeZ), terrainNoise), freeZ };
            addPrism(gate, hinge, free, 0.5f, 0.8f, 1.6f);
            addPrism(gate, hinge, free, 0.5f, hgt * 0.5f - 0.4f, hgt * 0.5f + 0.4f);
            addPrism(gate, hinge, free, 0.5f, hgt - 1.6f, hgt - 0.8f);
            uprights(gate, hinge, free, awayX, awayZ, 1.5f, 0.12f, 0.8f, hgt - 0.8f);
            addBox(gate, free[0], free[1] + 0.2f, free[2], awayX, awayZ, 0.4f, 0.4f, hgt - 0.6f);
            batch.include(free[0], free[1] + hgt, free[2], 2f);
            batch.place(null);
            return;
        }
        batch.include(a[0], a[1] + hgt + 4f, a[2], 4f);
        batch.include(b[0], b[1] + hgt + 4f, b[2], 4f);
        recordSegment(from, to, 0.5f, hgt + 4f);
        MeshBuilder mesh = batch.builder("borderfence");
        mesh.setPlanarTextureScale(0.1f);
        // Chain-link: rails top, middle and bottom, and close-set wires between
        addPrism(mesh, a, b, 0.3f, 0.2f, 0.8f);
        addPrism(mesh, a, b, 0.3f, hgt * 0.5f - 0.3f, hgt * 0.5f + 0.3f);
        addPrism(mesh, a, b, 0.3f, hgt - 0.6f, hgt);
        uprights(mesh, a, b, dirX, dirZ, 0.9f, 0.06f, 0.2f, hgt);
        // A post at some of the grid points (fixed to the world, so it's the same from both
        // chunks), fewer where the chart's grid is drawn tighter, so they stand as far apart in
        // true distance everywhere
        int every = 2 * Math.max(1, Math.round(chartStretch(fromChart[1])));
        if (Math.floorMod(Math.round(fromChart[0] * 0.37f) + Math.round(fromChart[1] * 0.61f), every) == 0) {
            addBox(mesh, a[0], a[1] - 1.5f, a[2], dirX, dirZ, 0.45f, 0.45f, hgt + 1.5f);
            // Its arm leaning out at the top, carrying the wire
            float[] foot = { a[0], a[1] + hgt, a[2] };
            float[] tip = { a[0] - dirZ * 2.4f, a[1] + hgt + 3.6f, a[2] + dirX * 2.4f };
            addRod(batch.builder("barbed"), foot, tip, 0.22f);
            float[] other = { a[0] + dirZ * 2.4f, a[1] + hgt + 3.6f, a[2] - dirX * 2.4f };
            addRod(batch.builder("barbed"), foot, other, 0.22f);
        }
        // Barbed wire: strands along the top of the arms on both sides, and a coil between them
        MeshBuilder wire = batch.builder("barbed");
        for (int strand = 0; strand < 3; strand++) {
            float out = 0.8f + strand * 0.8f, up = hgt + 1.2f + strand * 1.2f;
            for (float sideSign = -1f; sideSign <= 1f; sideSign += 2f) {
                float sx = -dirZ * out * sideSign, sz = dirX * out * sideSign;
                addRod(wire, new float[] { a[0] + sx, a[1] + up, a[2] + sz }, new float[] { b[0] + sx, b[1] + up, b[2] + sz }, 0.07f);
            }
        }
        // The coil: loops round the top of the fence, a few to each stretch
        int loops = Math.max(1, Math.round(length / 1.6f));
        float radius = 1.3f;
        float[] previous = null;
        int steps = loops * 8;
        for (int k = 0; k <= steps; k++) {
            float t = (float) k / steps;
            float angle = t * loops * (float) Math.PI * 2f;
            float cx = a[0] + (b[0] - a[0]) * t, cy = a[1] + (b[1] - a[1]) * t + hgt + 1.4f, cz = a[2] + (b[2] - a[2]) * t;
            float across = (float) Math.cos(angle) * radius, up = (float) Math.sin(angle) * radius;
            float[] p = { cx - dirZ * across, cy + up, cz + dirX * across };
            if (previous != null) addRod(wire, previous, p, 0.06f);
            previous = p;
        }
        batch.place(null);
    }

    /** Evenly spaced thin uprights (pickets, bars, spikes) between two ground points. */
    private void uprights(MeshBuilder builder, float[] a, float[] b, float dirX, float dirZ,
                          float spacing, float halfWidth, float bottom, float height) {
        float pieceLength = (float) Math.hypot(b[0] - a[0], b[2] - a[2]);
        int count = Math.max(1, Math.round(pieceLength / spacing));
        for (int p = 0; p < count; p++) {
            float t = (p + 0.5f) / count;
            float x = a[0] + (b[0] - a[0]) * t;
            float z = a[2] + (b[2] - a[2]) * t;
            float ground = a[1] + (b[1] - a[1]) * t;
            addBox(builder, x, ground + bottom, z, dirX, dirZ, halfWidth * 1.4f, halfWidth, height - bottom);
        }
    }

    /** A sloped slab between two ground points, from bottom to top above the ground at each end. */
    private void addPrism(MeshBuilder builder, float[] a, float[] b, float thickness, float bottom, float top) {
        float dx = b[0] - a[0];
        float dz = b[2] - a[2];
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-3f) {
            return;
        }
        float sideX = -dz / length * thickness * 0.5f;
        float sideZ = dx / length * thickness * 0.5f;
        float[] a0 = { a[0] - sideX, a[1] + bottom, a[2] - sideZ };
        float[] a1 = { a[0] + sideX, a[1] + bottom, a[2] + sideZ };
        float[] a2 = { a[0] + sideX, a[1] + top, a[2] + sideZ };
        float[] a3 = { a[0] - sideX, a[1] + top, a[2] - sideZ };
        float[] b0 = { b[0] - sideX, b[1] + bottom, b[2] - sideZ };
        float[] b1 = { b[0] + sideX, b[1] + bottom, b[2] + sideZ };
        float[] b2 = { b[0] + sideX, b[1] + top, b[2] + sideZ };
        float[] b3 = { b[0] - sideX, b[1] + top, b[2] - sideZ };
        float[] centre = { (a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f + (bottom + top) * 0.5f, (a[2] + b[2]) * 0.5f };
        builder.addConvexFace(centre, a0, b0, b3, a3);
        builder.addConvexFace(centre, a1, b1, b2, a2);
        builder.addConvexFace(centre, a3, b3, b2, a2);
        builder.addConvexFace(centre, a0, a1, a2, a3);
        builder.addConvexFace(centre, b0, b1, b2, b3);
    }

    /** An upright box standing at a point, aligned with the given direction. */
    private void addBox(MeshBuilder builder, float x, float y, float z, float dirX, float dirZ,
                        float halfAlong, float halfAcross, float height) {
        float sideX = -dirZ;
        float sideZ = dirX;
        float[][] base = new float[4][];
        float[][] top = new float[4][];
        float[][] signs = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (int i = 0; i < 4; i++) {
            float px = x + dirX * halfAlong * signs[i][0] + sideX * halfAcross * signs[i][1];
            float pz = z + dirZ * halfAlong * signs[i][0] + sideZ * halfAcross * signs[i][1];
            base[i] = new float[] { px, y, pz };
            top[i] = new float[] { px, y + height, pz };
        }
        float[] centre = { x, y + height * 0.5f, z };
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            builder.addConvexFace(centre, base[i], base[next], top[next], top[i]);
        }
        builder.addConvexFace(centre, top[0], top[1], top[2], top[3]);
    }

    // ==========================================
    //          SIGNS
    // ==========================================

    /** A sign in the front garden beside the path, text facing the road (as the house does), in a frame of its own. */
    private InfrastructureObject createHouseSign(House house, Random rand) {
        float lateral = (rand.nextBoolean() ? 1.0f : -1.0f) * (house.width * 0.3f + 8.0f);
        float forward = house.depth * 0.5f + Math.max(3.0f, (house.setback - FRONT_FENCE_GAP) * 0.5f);
        SpherePosition foot = world(house, lateral, 0f, forward);
        if (isRoadLocation(foot, 2.0f)) {
            return null;
        }
        float signY = groundAt(house, lateral, forward);
        if (signY <= seaLevel + 0.5f) {
            return null;
        }
        SpherePosition at = SphereMath.atAltitude(foot, signY);
        return createSign(house.frame.movedTo(at), onChart(at, house.x), house.nationId, rand);
    }

    /** A sign beside the road, text facing across the carriageway, laid out in the segment's frame. More likely the more urban the street. */
    private InfrastructureObject tryCreateRoadsideSign(RoadSegment segment, Random rand, List<SpherePosition> existingSigns) {
        Double probability = nationSignProbabilities.get(segment.nationId);
        if (probability == null) {
            return null;
        }
        double urbanBoost = 1.0 + URBAN_SIGN_BOOST * settlementManager.getUrbanness(segment.frame.origin());
        if (rand.nextDouble() >= probability * ROADSIDE_SIGN_CHANCE_SCALE * urbanBoost) {
            return null;
        }

        float t = 0.2f + rand.nextFloat() * 0.6f;
        float side = rand.nextBoolean() ? 1.0f : -1.0f;
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        float outwardX = -dirZ * side;
        float outwardZ = dirX * side;
        float offset = segment.width * 0.5f + ROADSIDE_SIGN_MARGIN;

        float signX = segment.start.x + (segment.end.x - segment.start.x) * t + outwardX * offset;
        float signZ = segment.start.z + (segment.end.z - segment.start.z) * t + outwardZ * offset;
        SpherePosition foot = world(segment, signX, signZ);
        if (isRoadLocation(foot, 2.0f) || isPlotLocation(foot, 2.0f)) {
            return null;
        }
        for (SpherePosition existing : existingSigns) {
            if (SphereMath.distance(existing, foot) < SIGN_SPACING) {
                return null;
            }
        }
        float signY = TerrainMesh.getLayeredHeight(foot, terrainNoise);
        if (signY <= seaLevel + 0.5f) {
            return null;
        }
        SpherePosition at = SphereMath.atAltitude(foot, signY);
        // Its face (local +z) back towards the road, that heading carried from the segment's middle
        double facing = SphereMath.carryHeading(segment.frame.origin(), foot, Math.atan2(-outwardZ, -outwardX));
        return createSign(SphereFrame.at(at, facing - Math.PI * 0.5), onChart(at, segment.chartMidX()), segment.nationId, rand);
    }
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

    /** A standing sign in its own frame (its foot at the origin, text facing local +z); chart its place on the chart (D13). */
    private InfrastructureObject createSign(SphereFrame frame, Vector3 chart, int nationId, Random rand) {
        int maxGlyphs = mainListener != null ? mainListener.getNationAtlasSize(nationId) : 10;
        if (maxGlyphs <= 0) maxGlyphs = 1;
        int[] textString = randomText(rand, maxGlyphs, 80, 200);
        InfrastructureObject sign = InfrastructureObject.createSign(frame, chart, nationId, textString);
        // Separate streams, so the pictures and title don't change what else the chunk holds
        Random design = new Random(rand.nextLong());
        decorateSign(sign, design, maxGlyphs, false);
        return sign;
    }

    /** Words of random letters, separated by spaces. */
    private static int[] randomText(Random rand, int maxGlyphs, int minLen, int maxLen) {
        int stringLength = minLen + rand.nextInt(maxLen - minLen + 1);
        int[] textString = new int[stringLength];
        int wordLengthCounter = 0;
        int targetWordLength = 3 + rand.nextInt(6);
        for (int c = 0; c < stringLength; c++) {
            if (wordLengthCounter >= targetWordLength) {
                textString[c] = 0;
                wordLengthCounter = 0;
                targetWordLength = 3 + rand.nextInt(6);
            } else {
                textString[c] = 1 + rand.nextInt(maxGlyphs);
                wordLengthCounter++;
            }
        }
        return textString;
    }

    /**
     * Gives a sign its pictures (none to three: the flag, a map, the locals, their goods)
     * and perhaps a title. Some signs are adverts for a shop nearby: titled with the shop's
     * name, showing its goods, people holding them, or a map of the way there.
     */
    private void decorateSign(InfrastructureObject sign, Random rand, int maxGlyphs, boolean poster) {
        int nation = sign.nationId;
        boolean vertical = scripts.direction(nation) >= 2;
        Shop shop = nearestShop(sign.frame.origin(), 2600f, nation);
        boolean advert = shop != null && rand.nextFloat() < (poster ? 0.8f : 0.4f);
        float patriotism = patriotismOf(nation);

        if (advert) {
            int[] name = shopName(shop);
            System.arraycopy(name, 0, sign.titleString, 0, name.length);
            sign.titleLength = name.length;
        } else if (rand.nextFloat() < 0.55f) {
            // A title of a word or two, short enough to fit its line whole: a line of a
            // vertical script holds six of its big letters, a horizontal one fourteen
            int most = vertical ? 6 : 12;
            int length = 0;
            int words = rand.nextFloat() < (vertical ? 0.2f : 0.6f) ? 2 : 1;
            for (int word = 0; word < words; word++) {
                int letters = 2 + rand.nextInt(vertical ? 3 : 5);
                if (length + (word > 0 ? 1 : 0) + letters > most) break;
                if (word > 0) sign.titleString[length++] = 0;
                for (int i = 0; i < letters; i++) sign.titleString[length++] = 1 + rand.nextInt(maxGlyphs);
            }
            sign.titleLength = length;
        }
        if (sign.titleLength > 0) letter(sign, rand);
        if (advert && rand.nextFloat() < 0.5f) {
            // Adverts are often printed on a coloured ground
            sign.boardColour = WorldPalette.hsv(rand.nextFloat(), 0.12f + rand.nextFloat() * 0.18f, 0.9f + rand.nextFloat() * 0.08f);
        }

        List<Integer> kinds = new ArrayList<>();
        if (advert) {
            if (rand.nextFloat() < 0.55f) kinds.add(InfrastructureObject.PICTURE_MAP);
            if (rand.nextFloat() < 0.65f) kinds.add(InfrastructureObject.PICTURE_ADVERT);
            if (rand.nextFloat() < 0.45f) kinds.add(InfrastructureObject.PICTURE_PRODUCT);
            if (rand.nextFloat() < 0.1f * patriotism) kinds.add(InfrastructureObject.PICTURE_FLAG);
            if (kinds.isEmpty()) kinds.add(rand.nextBoolean() ? InfrastructureObject.PICTURE_ADVERT : InfrastructureObject.PICTURE_PRODUCT);
        } else {
            float roll = rand.nextFloat();
            int count = roll < 0.45f ? 0 : roll < 0.8f ? 1 : roll < 0.95f ? 2 : 3;
            for (int i = 0; i < count; i++) {
                float flagWeight = 0.3f * patriotism;
                boolean hasMap = kinds.contains(InfrastructureObject.PICTURE_MAP);
                float mapWeight = hasMap ? 0f : 0.3f;
                float total = flagWeight + mapWeight + 0.25f + 0.18f + 0.07f;
                float pick = rand.nextFloat() * total;
                int kind;
                if ((pick -= flagWeight) < 0f) kind = InfrastructureObject.PICTURE_FLAG;
                else if ((pick -= mapWeight) < 0f) kind = InfrastructureObject.PICTURE_MAP;
                else if ((pick -= 0.25f) < 0f) kind = InfrastructureObject.PICTURE_PORTRAIT;
                else if ((pick -= 0.18f) < 0f) kind = InfrastructureObject.PICTURE_FIGURE;
                else kind = InfrastructureObject.PICTURE_PRODUCT;
                kinds.add(kind);
            }
        }
        while (kinds.size() > InfrastructureObject.MAX_PICTURES) kinds.remove(kinds.size() - 1);
        java.util.Collections.shuffle(kinds, rand);
        float[][] rects = layoutPictures(kinds, vertical, sign.titleLength > 0, rand);
        for (int i = 0; i < kinds.size(); i++) {
            sign.addPicture(kinds.get(i), rects[i], rand.nextInt(1 << 16));
            if (kinds.get(i) == InfrastructureObject.PICTURE_MAP) scheduleMap(sign, rects[i], advert ? shop : null, rand);
        }
    }

    /** A title's lettering: weight, slant, width, perhaps outlined or shadowed, and an ink of its own. */
    private void letter(InfrastructureObject sign, Random rand) {
        float weight = new float[] { 0.25f, 0.35f, 0.5f, 0.62f }[rand.nextInt(4)];
        float slant = rand.nextFloat() < 0.35f ? (rand.nextBoolean() ? 0.22f : -0.18f) : 0f;
        float width = 0.75f + rand.nextFloat() * 0.4f;
        boolean outline = rand.nextFloat() < 0.15f;
        sign.titleFont = new float[] { weight, slant, width, outline ? 1f : 0f };
        sign.titleShadow = !outline && rand.nextFloat() < 0.2f;
        float inkRoll = rand.nextFloat();
        if (inkRoll < 0.4f) {
            sign.titleColour = new float[] { 0.05f, 0.05f, 0.06f };
        } else if (inkRoll < 0.65f && flags.containsKey(sign.nationId)) {
            // One of the flag's colours, if it shows on white
            List<float[]> colours = flags.get(sign.nationId).colours;
            float[] c = colours.get(rand.nextInt(colours.size()));
            boolean pale = c[0] + c[1] + c[2] > 2.2f;
            sign.titleColour = pale ? new float[] { 0.05f, 0.05f, 0.06f } : c;
        } else {
            sign.titleColour = WorldPalette.hsv(rand.nextFloat(), 0.6f + rand.nextFloat() * 0.3f, 0.3f + rand.nextFloat() * 0.3f);
        }
    }

    /**
     * Where each picture goes, as fractions of the text area: in different corners (or, a
     * lone picture, now and then taking up most of the board), clear of the title's lines.
     */
    private static float[][] layoutPictures(List<Integer> kinds, boolean vertical, boolean title, Random rand) {
        int count = kinds.size();
        float[][] rects = new float[count][];
        // The title takes the first two of twelve lines: rows for a horizontal script, columns for a vertical one
        float ax = title && vertical ? 2f / 12f + 0.01f : 0f;
        float ay = title && !vertical ? 2f / 12f + 0.02f : 0f;
        float availW = 1f - ax, availH = 1f - ay;
        boolean large = count == 1 && rand.nextFloat() < 0.3f;
        float maxW = count == 1 ? (large ? Math.min(0.72f, availW) : 0.45f) : count == 2 ? 0.44f : 0.38f;
        float maxH = count == 1 ? (large ? availH : Math.min(0.62f, availH)) : (availH - 0.1f) / 2f;
        List<Integer> corners = new ArrayList<>(List.of(0, 1, 2, 3));
        java.util.Collections.shuffle(corners, rand);
        for (int i = 0; i < count; i++) {
            int kind = kinds.get(i);
            // Width over height in world units
            float aspect = switch (kind) {
                case InfrastructureObject.PICTURE_FLAG -> 1.5f;
                case InfrastructureObject.PICTURE_FIGURE -> 0.6f;
                case InfrastructureObject.PICTURE_MAP -> 0.8f + rand.nextFloat() * 1.0f;
                default -> 0.75f;
            };
            float f = large ? 1f : 0.75f + rand.nextFloat() * 0.25f;
            float w = maxW * f;
            float h = w * 27.6f / 19.36f / aspect;
            if (h > maxH * f) {
                h = maxH * f;
                w = h * aspect * 19.36f / 27.6f;
            }
            int corner = corners.get(i);
            float x0 = corner % 2 == 0 ? ax : 1f - w;
            float y0 = corner < 2 ? ay : 1f - h;
            rects[i] = new float[] { x0, y0, x0 + w, y0 + h };
        }
        return rects;
    }

    /**
     * Draws a sign's map off the GL thread: of the area round the sign at any scale, or, on
     * an advert, of the way from the sign to the shop.
     */
    private void scheduleMap(InfrastructureObject sign, float[] rect, Shop shop, Random rand) {
        float worldW = (rect[2] - rect[0]) * sign.boardWidth * 0.92f, worldH = (rect[3] - rect[1]) * sign.boardHeight * 0.88f;
        int mapW = worldW >= worldH ? 256 : Math.max(64, Math.round(256 * worldW / worldH));
        int mapH = worldW >= worldH ? Math.max(64, Math.round(256 * worldH / worldW)) : 256;
        sign.mapWidth = mapW;
        sign.mapHeight = mapH;
        float px = sign.position.x, pz = sign.position.z;
        float aspect = mapW / (float) mapH;
        float cx, cz, halfWidth;
        float[] here, destination = null;
        boolean route = false;
        java.awt.image.BufferedImage badge = null;
        if (shop != null) {
            Doorway door = doorwayOf(shop.house);
            destination = new float[] { door.doorX, door.doorZ };
            cx = (px + door.doorX) * 0.5f;
            cz = (pz + door.doorZ) * 0.5f;
            halfWidth = Math.max(Math.abs(door.doorX - px) * 0.5f, Math.abs(door.doorZ - pz) * 0.5f * aspect) * 1.45f + 90f;
            here = rand.nextFloat() < 0.75f ? new float[] { px, pz } : null;
            route = here != null && rand.nextFloat() < 0.85f;
            int[] name = shopName(shop);
            List<java.awt.image.BufferedImage> glyphs = scripts.glyphs(shop.nationId);
            for (int letter : name) {
                if (letter > 0 && letter < glyphs.size()) { badge = glyphs.get(letter); break; }
            }
        } else {
            halfWidth = (float) (150.0 * Math.pow(25.0, rand.nextFloat())) * (mapW / (float) Math.max(mapW, mapH));
            float halfHeight = halfWidth / aspect;
            cx = px + (rand.nextFloat() - 0.5f) * halfWidth * 0.8f;
            cz = pz + (rand.nextFloat() - 0.5f) * halfHeight * 0.8f;
            here = rand.nextFloat() < 0.6f ? new float[] { px, pz } : null;
        }
        long style = worldSeed * 61L + sign.nationId * 17L;
        PerlinNoise noise = terrainNoise;
        float sea = seaLevel;
        float[] hereMark = here, shopMark = destination;
        boolean withRoute = route;
        java.awt.image.BufferedImage letter = badge;
        float centreX = cx, centreZ = cz, half = halfWidth;
        SIGN_MAP_ARTIST.submit(() -> {
            try {
                List<float[]> way = withRoute ? routes().route(hereMark[0], hereMark[1], shopMark[0], shopMark[1]) : null;
                sign.mapPixels = SignMap.render(this, noise, sea, style, centreX, centreZ, half, mapW, mapH, hereMark, shopMark, way, letter);
            } catch (RuntimeException e) {
                System.err.println("[SIGNS] Map failed: " + e);
            }
        });
    }

    // ==========================================
    //          GEOMETRY HELPERS
    // ==========================================

    private float distanceSquaredToSegment(float x, float z, Vector3 start, Vector3 end) {
        float dx = end.x - start.x;
        float dz = end.z - start.z;
        float lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0.0f) {
            float pointDx = x - start.x;
            float pointDz = z - start.z;
            return pointDx * pointDx + pointDz * pointDz;
        }

        float projection = ((x - start.x) * dx + (z - start.z) * dz) / lengthSquared;
        projection = Math.max(0.0f, Math.min(1.0f, projection));
        float closestX = start.x + projection * dx;
        float closestZ = start.z + projection * dz;
        float distanceX = x - closestX;
        float distanceZ = z - closestZ;
        return distanceX * distanceX + distanceZ * distanceZ;
    }

    /** The level direction from one place to another in a frame, as a unit {x, 0, z}. */
    private static Vector3 roadTangent(SphereFrame frame, SpherePosition fromPlace, SpherePosition toPlace) {
        Vector3 from = inFrame(frame, fromPlace);
        Vector3 to = inFrame(frame, toPlace);
        float dx = to.x - from.x;
        float dz = to.z - from.z;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-4f) {
            return new Vector3(1.0f, 0.0f, 0.0f);
        }
        return new Vector3(dx / length, 0.0f, dz / length);
    }

    /** Squared distance between segment AB and the axis-aligned box [-hx, hx] x [-hz, hz]. */
    private static float segmentToBoxDistanceSquared(float ax, float az, float bx, float bz, float hx, float hz) {
        if (segmentIntersectsBox(ax, az, bx, bz, hx, hz)) {
            return 0.0f;
        }
        float best = Math.min(pointToBoxDistanceSquared(ax, az, hx, hz), pointToBoxDistanceSquared(bx, bz, hx, hz));
        best = Math.min(best, pointToSegmentDistanceSquared(-hx, -hz, ax, az, bx, bz));
        best = Math.min(best, pointToSegmentDistanceSquared(hx, -hz, ax, az, bx, bz));
        best = Math.min(best, pointToSegmentDistanceSquared(hx, hz, ax, az, bx, bz));
        best = Math.min(best, pointToSegmentDistanceSquared(-hx, hz, ax, az, bx, bz));
        return best;
    }

    /** Liang-Barsky clip of the segment against the box. */
    private static boolean segmentIntersectsBox(float ax, float az, float bx, float bz, float hx, float hz) {
        float dx = bx - ax;
        float dz = bz - az;
        float[] p = { -dx, dx, -dz, dz };
        float[] q = { ax + hx, hx - ax, az + hz, hz - az };
        float enter = 0.0f;
        float exit = 1.0f;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0f) {
                if (q[i] < 0.0f) {
                    return false;
                }
            } else {
                float r = q[i] / p[i];
                if (p[i] < 0.0f) {
                    enter = Math.max(enter, r);
                } else {
                    exit = Math.min(exit, r);
                }
                if (enter > exit) {
                    return false;
                }
            }
        }
        return true;
    }

    private static float pointToBoxDistanceSquared(float x, float z, float hx, float hz) {
        float dx = Math.max(0.0f, Math.abs(x) - hx);
        float dz = Math.max(0.0f, Math.abs(z) - hz);
        return dx * dx + dz * dz;
    }

    private static float pointToSegmentDistanceSquared(float x, float z, float ax, float az, float bx, float bz) {
        float dx = bx - ax;
        float dz = bz - az;
        float lengthSquared = dx * dx + dz * dz;
        float t = lengthSquared > 0.0f ? ((x - ax) * dx + (z - az) * dz) / lengthSquared : 0.0f;
        t = Math.max(0.0f, Math.min(1.0f, t));
        float offsetX = x - (ax + dx * t);
        float offsetZ = z - (az + dz * t);
        return offsetX * offsetX + offsetZ * offsetZ;
    }

    // ------------------------------------------------------------------ frames (D3)

    /** A place in a frame as {x, height above the datum, z}, x and z along the ground (see SphereFrame.fromSphere). */
    private static Vector3 inFrame(SphereFrame frame, SpherePosition place) {
        double[] local = frame.fromSphere(place);
        return new Vector3((float) local[0], (float) local[1], (float) local[2]);
    }

    /** A point in a house's frame (y its height above the datum) on the planet. */
    private static SpherePosition world(House house, float x, float y, float z) {
        return house.frame.toWorld(x, y, z);
    }

    /** Where a place is in a house's frame, {x, z} (its height left out). */
    private static float[] local(House house, SpherePosition place) {
        double[] local = house.frame.fromSphere(place);
        return new float[] { (float) local[0], (float) local[2] };
    }

    /** A point in a road segment's frame, on the datum. */
    private static SpherePosition world(RoadSegment segment, float x, float z) {
        return segment.frame.toSphere(x, 0.0, z);
    }

    /** The ground's height at a point in a house's frame. */
    private float groundAt(House house, float x, float z) {
        return TerrainMesh.getLayeredHeight(world(house, x, 0f, z), terrainNoise);
    }

    /** The ground's height at a point in a road segment's frame. */
    private float groundAt(RoadSegment segment, float x, float z) {
        return TerrainMesh.getLayeredHeight(world(segment, x, z), terrainNoise);
    }

    /** Squared distance along the ground from a place to a segment's centreline, worked in the segment's frame. */
    private static float distanceSquaredToSegment(SpherePosition place, RoadSegment segment) {
        double[] p = segment.frame.fromSphere(place);
        return pointToSegmentDistanceSquared((float) p[0], (float) p[2], segment.start.x, segment.start.z, segment.end.x, segment.end.z);
    }

    /**
     * Gives a house its frame: at a point in a road segment's frame, its door side facing
     * (faceX, faceZ) there, that heading carried along the ground to the house.
     */
    private static void placeHouse(House house, RoadSegment segment, float x, float z, float faceX, float faceZ) {
        SpherePosition at = world(segment, x, z);
        double facing = SphereMath.carryHeading(segment.frame.origin(), at, Math.atan2(faceZ, faceX));
        // (turned so local +z, the door side, points along facing)
        house.frame = SphereFrame.at(at, facing - Math.PI * 0.5);
        double[] chart = Planet.mercatorPosition(at);
        house.x = (float) chart[0];
        house.z = (float) chart[2];
        house.rotationY = (float) Math.toDegrees(Math.atan2(Math.cos(facing), Math.sin(facing)));
    }

    // ------------------------------------------------------------------ chart bridge (D13)
    // The batches, collision shapes, signs, doorways, maps and the grass's keep-off checks are
    // still on the Mercator chart until the rest of Phase 3b (and Phase 4 for people and
    // vehicles, Phase 5 for the maps). Everything they get from the frames goes through here.

    /** Chart bridge: the place under a chart point, on the datum. */
    private static SpherePosition sphere(float chartX, float chartZ) {
        return Planet.spherePosition(chartX, chartZ, 0.0);
    }

    /** Chart bridge: how many chart units a true unit spans at chart z (1 / cos latitude). */
    private static float chartStretch(float chartZ) {
        return (float) (1.0 / Math.max(0.05, Math.cos(Planet.latitude(chartZ))));
    }

    /** Chart bridge: a place on the chart as {x, height, z}, x kept beside nearX so it never jumps across the map's join. */
    private static float[] chartPoint(SpherePosition place, float height, float nearX) {
        double[] chart = Planet.mercatorPosition(place);
        return new float[] { nearX + (float) Planet.wrapX(chart[0] - nearX), height, (float) chart[2] };
    }

    /** Chart bridge: a place on the chart as {x, height, z}, its x beside nearX. */
    private static Vector3 onChart(SpherePosition place, float nearX) {
        float[] p = chartPoint(place, (float) SphereMath.altitude(place), nearX);
        return new Vector3(p[0], p[1], p[2]);
    }

    /** Chart bridge: a point in a house's frame (y its height above the datum) on the chart, {x, y, z}. */
    private static float[] chartPoint(House house, float x, float y, float z) {
        return chartPoint(world(house, x, 0f, z), y, house.x);
    }

    /** Chart bridge: a point in a house's frame on the chart, {x, z}. */
    private static float[] chartAt(House house, float x, float z) {
        float[] p = chartPoint(house, x, 0f, z);
        return new float[] { p[0], p[2] };
    }

    /** Chart bridge: a point in a road segment's frame on the chart, {x, z}. */
    private static float[] chartAt(RoadSegment segment, float x, float z) {
        float[] p = chartPoint(world(segment, x, z), 0f, segment.chartMidX());
        return new float[] { p[0], p[2] };
    }

    /** Chart bridge: a level direction in a house's frame as a unit chart direction {x, z} (the chart keeps angles). */
    private static float[] chartDirection(House house, float x, float z) {
        double[] world = house.frame.worldDirection(x, 0.0, z);
        double[] local = SphereFrame.at(house.frame.origin()).localDirection(world[0], world[1], world[2]);
        double length = Math.max(1e-9, Math.hypot(local[0], local[2]));
        return new float[] { (float) (local[0] / length), (float) (local[2] / length) };
    }

    private static MeshBuilder.Placement onChart(House house) {
        return onChart(house.frame, false, house.x);
    }

    private static MeshBuilder.Placement onChart(RoadSegment segment) {
        return onChart(segment.frame, true, segment.chartMidX());
    }

    /**
     * Chart bridge: places what is built in a frame (rigidly for a house, along the ground for a
     * road) on the chart, where the batches stay until they are built per geodesic cell: each
     * vertex goes onto the planet and then to its chart point, which the batch lifts back exactly
     * (see InfrastructureObject.sphereVertices). A normal's level part is shrunk by the chart's
     * stretch there, which that lifting undoes.
     */
    private static MeshBuilder.Placement onChart(SphereFrame frame, boolean alongGround, float nearX) {
        return (position, normal) -> {
            SpherePosition at = alongGround ? frame.toSphere(position[0], position[1], position[2])
                    : frame.toWorld(position[0], position[1], position[2]);
            double[] chart = Planet.mercatorPosition(at);
            position[0] = nearX + (float) Planet.wrapX(chart[0] - nearX);
            position[1] = (float) chart[1];
            position[2] = (float) chart[2];
            double[] world = frame.worldDirection(normal[0], normal[1], normal[2]);
            double[] local = SphereFrame.at(at).localDirection(world[0], world[1], world[2]);
            double cosine = Math.hypot(at.x(), at.z()) / at.length();
            normal[0] = (float) (local[0] * cosine);
            normal[1] = (float) local[1];
            normal[2] = (float) (local[2] * cosine);
        };
    }

    // ------------------------------------------------------------------ the map's join

    /**
     * What stands in a chunk: its own, plus whatever was built a whole way round the planet
     * east or west of it, in the margin past the other edge of the map (a town near the
     * join spreads over it), moved round to here. So both sides of the join show the same.
     */
    private <T> List<T> across(int cx, int cz, Map<Long, List<T>> byChunk, java.util.function.BiFunction<T, Float, T> move) {
        List<T> found = new ArrayList<>();
        List<T> own = byChunk.get(chunkKey(cx, cz));
        if (own != null) found.addAll(own);
        int round = Math.round(Planet.width() / roadChunkSize);
        for (int k : new int[] { round, -round }) {
            List<T> other = byChunk.get(chunkKey(cx + k, cz));
            if (other == null) continue;
            for (T thing : other) found.add(move.apply(thing, -k * roadChunkSize));
        }
        return found;
    }

    private final Map<House, House> shiftedHouses = new java.util.concurrent.ConcurrentHashMap<>();

    /** A house moved a whole way round the planet (dx is plus or minus the map's width). */
    private House shifted(House house, float dx) {
        return shiftedHouses.computeIfAbsent(house, original -> {
            try {
                House copy = new House();
                for (java.lang.reflect.Field field : House.class.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    field.set(copy, field.get(original));
                }
                copy.x += dx;
                copy.doorway = null;
                return copy;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private RoadSegment shifted(RoadSegment segment, float dx) {
        // (the same place: only its chart copy moves)
        RoadSegment copy = new RoadSegment(segment.frame, segment.from, segment.to, segment.start, segment.end,
                segment.startTangent, segment.endTangent,
                new Vector3(segment.chartStart.x + dx, segment.chartStart.y, segment.chartStart.z),
                new Vector3(segment.chartEnd.x + dx, segment.chartEnd.y, segment.chartEnd.z),
                segment.nationId, segment.pathIndex, segment.roadClass, segment.startDistance, segment.length, segment.width, segment.culDeSac);
        copy.deck = segment.deck;
        copy.railed = segment.railed;
        return copy;
    }

    private int chunkIndex(float coordinate) {
        return (int) Math.floor((coordinate + roadChunkSize * 0.5f) / roadChunkSize);
    }

    private long chunkKeyAt(float x, float z) {
        return chunkKey(chunkIndex(x), chunkIndex(z));
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1];
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private Vector3 lerpTangent(Vector3 first, Vector3 second, float amount) {
        float x = first.x + (second.x - first.x) * amount;
        float z = first.z + (second.z - first.z) * amount;
        float length = (float) Math.sqrt(x * x + z * z);
        if (length < 1e-4f) {
            return second;
        }
        return new Vector3(x / length, 0.0f, z / length);
    }

    private float[] miterOffset(Vector3 incoming, Vector3 outgoing, float halfWidth) {
        float incomingSideX = -incoming.z;
        float incomingSideZ = incoming.x;
        float outgoingSideX = -outgoing.z;
        float outgoingSideZ = outgoing.x;
        float miterX = incomingSideX + outgoingSideX;
        float miterZ = incomingSideZ + outgoingSideZ;
        float miterLength = (float) Math.sqrt(miterX * miterX + miterZ * miterZ);

        if (miterLength < 0.001f) {
            return new float[] { outgoingSideX * halfWidth, outgoingSideZ * halfWidth };
        }

        miterX /= miterLength;
        miterZ /= miterLength;
        float scale = halfWidth / Math.max(0.35f, miterX * outgoingSideX + miterZ * outgoingSideZ);
        return new float[] { miterX * scale, miterZ * scale };
    }
}
