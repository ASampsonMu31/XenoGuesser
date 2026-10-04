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
    private static final float ROAD_SURFACE_OFFSET = 2.0f;
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
    private static final float RAIL_JUNCTION_CLEARANCE = 8.0f;

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
    // Neighbouring fenced plots closer than this join up, sharing one fence between them
    private static final float FENCE_JOIN_GAP = 26.0f;
    // Fence outlines: the plain plot, an irregular outline, the front boundary only
    private static final int FENCE_PLOT = 0, FENCE_IRREGULAR = 1, FENCE_FRONT_ONLY = 2;
    private static final float BUILDING_MAX_GROUND_DROP = 25.0f;
    private static final float BUILDING_FOUNDATION_DEPTH = 3.0f;
        private static final float DOOR_OFFSET = 0.5f;
    private static final float FRAME_OFFSET = 0.2f;
    private static final float PLOT_INDEX_CELL = 250.0f;

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
    private final SettlementManager settlementManager;
    private final XenoGuesser_GLEventListener mainListener;
    private final long worldSeed;
    private final List<RoadPath> roadNetwork = new ArrayList<>();
    private final List<RoadPath.DeadEnd> deadEnds = new ArrayList<>();
    private final Map<Integer, List<RoadSegment>> segmentsByPath = new HashMap<>();
    private final List<RoadSegment> landSegments = new ArrayList<>();
    private final Map<Long, List<RoadSegment>> roadSegmentsByChunk = new HashMap<>();
    private final List<House> houses = new ArrayList<>();
    private final Map<Integer, FlagDesigner.Spec> flags;
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
    // Maps for signs are drawn here, off the GL thread
    private static final java.util.concurrent.ExecutorService SIGN_MAP_ARTIST = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sign-map-artist");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final float windAngle;
    // Solid shapes recorded as each chunk is built, for collision: {kind, ax, az, bx, bz, size}
    // per shape, kind 0 a wall/fence/rail segment of half-width size, 1 a post of radius size.
    // The world never changes, so a chunk's shapes stay valid after its meshes are dropped.
    private final Map<Long, float[]> obstaclesByChunk = new java.util.concurrent.ConcurrentHashMap<>();
    private List<float[]> recording;
    private final Map<Long, List<House>> housesByChunk = new HashMap<>();
    private float maxHouseReach;
    private float maxPlotReach;
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

    private static final class RoadSegment {
        private final Vector3 start;
        private final Vector3 end;
        private final Vector3 startTangent;
        private final Vector3 endTangent;
        private final int nationId;
        private final int pathIndex;
        private final RoadPath.RoadClass roadClass;
        private final float startDistance;
        private final float length;
        private final float width;
        private final boolean culDeSac;
        private boolean railed;

        private RoadSegment(Vector3 start, Vector3 end, Vector3 startTangent, Vector3 endTangent, int nationId,
                            int pathIndex, RoadPath.RoadClass roadClass, float startDistance, float length,
                            float width, boolean culDeSac) {
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
    }

    /** A house on its plot. Local +Z (the door side) faces the road it fronts. */
    private static final class House {
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
            this.grocer = rand.nextFloat() < 0.5f;
        }
    }

    @FunctionalInterface
    public interface RoadPathVisitor {
        void visit(RoadPath path);
    }

    @FunctionalInterface
    public interface BuildingVisitor {
        void visit(float x, float z, float rotationY, float width, float depth, int nationId);
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
        WorldPalette palette = new WorldPalette(seed);
        this.roadLineStyles = RoadLineStyle.generateForNations(seed, numNations, kinship);
                this.buildingStyles = BuildingStyle.generateForNations(seed, numNations, kinship, palette);
        this.wallVariants = BuildingStyle.generateWallVariants(seed, palette);
        BuildingStyle.assignWallVariants(seed, buildingStyles, wallVariants, kinship);
        this.guardRailStyles = GuardRailStyle.generateForNations(seed, numNations);
        this.fenceStyles = FenceStyle.generateForNations(seed, numNations, kinship);
        this.flags = FlagDesigner.design(seed, numNations, kinship);
        this.scripts = new NationScripts(seed, numNations);
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

    public static String nationTextureName(String part, int nationId) {
        return part + "_nation" + nationId;
    }

    /**
     * Generators for every nation's wall, roof and fence textures, keyed by texture name,
     * for WorldArtGenerator to run alongside the world's other textures.
     */
    public Map<String, java.util.function.Supplier<java.awt.image.BufferedImage>> textureJobs() {
        Map<String, java.util.function.Supplier<java.awt.image.BufferedImage>> jobs = new LinkedHashMap<>();
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
     * forms are likelier towards the middle of a town, so centres rise above the suburbs.
     */
    private int chooseForm(float x, float z, int nationId, long houseSeed) {
        BuildingStyle[] forms = formStyles.get(nationId);
        float[] weights = forms[0].formWeights;
        float urbanness = settlementManager.getUrbanness(x, z);
        SettlementManager.Settlement town = urbanness > 0.05f ? settlementManager.dominantSettlementAt(x, z) : null;
        Random townRand = town == null ? null : new Random(worldSeed ^ (town.rank * 0xC2B2AE3D27D4EB4FL));
        float[] tilted = new float[weights.length];
        float total = 0;
        for (int f = 0; f < weights.length; f++) {
            float townBias = townRand == null ? 1.0f : (float) Math.exp(townRand.nextGaussian() * 0.7);
            float lift = Math.max(0.0f, forms[f].wallHeight / forms[0].wallHeight - 1.0f);
            tilted[f] = weights[f] * townBias * (1.0f + 3.0f * urbanness * lift);
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
    private int chooseWallVariant(float x, float z, int nationId, long houseSeed) {
        float[] weights = buildingStyles.get(nationId).wallVariantWeights;
        SettlementManager.Settlement town = settlementManager.getUrbanness(x, z) > 0.05f
                ? settlementManager.dominantSettlementAt(x, z) : null;
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

        private Doorway(float[] inside, float[] door, float[] kerb, float[] roadDir, int nationId, int path, float urbanness, long id) {
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
        float dirX = first.end.x - first.start.x, dirZ = first.end.z - first.start.z;
        float side = dirX * (az - onA[1]) - dirZ * (ax - onA[0]) >= 0f ? 1f : -1f;
        points.add(walkPoint(first, ax, az, side));
        if (ia < ib) {
            for (int k = ia; k < ib; k++) points.add(walkPoint(segs.get(k), segs.get(k).end.x, segs.get(k).end.z, side));
        } else {
            for (int k = ia; k > ib; k--) points.add(walkPoint(segs.get(k), segs.get(k).start.x, segs.get(k).start.z, side));
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
        float dx = segment.end.x - segment.start.x, dz = segment.end.z - segment.start.z;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-4f) return on;
        float offset = Math.max(1.5f, segment.width * 0.5f - 2.5f) * side;
        return new float[] { on[0] - dz / length * offset, on[1] + dx / length * offset };
    }

    /** Every house's doorway with its centre inside the rectangle. */
    public List<Doorway> doorwaysIn(float minX, float minZ, float maxX, float maxZ) {
        List<Doorway> result = new ArrayList<>();
        for (int cz = chunkIndex(minZ); cz <= chunkIndex(maxZ); cz++) {
            for (int cx = chunkIndex(minX); cx <= chunkIndex(maxX); cx++) {
                List<House> bucket = housesByChunk.get(chunkKey(cx, cz));
                if (bucket == null) continue;
                for (House house : bucket) {
                    if (house.x < minX || house.x >= maxX || house.z < minZ || house.z >= maxZ) continue;
                    result.add(doorwayOf(house));
                }
            }
        }
        return result;
    }

    /** Doorways of houses within reach of a point. */
    public List<Doorway> doorwaysNear(float x, float z, float reach) {
        List<Doorway> result = new ArrayList<>();
        for (House house : housesNear(x, z, reach)) {
            float dx = house.x - x, dz = house.z - z;
            if (dx * dx + dz * dz <= reach * reach) result.add(doorwayOf(house));
        }
        return result;
    }

    private Doorway doorwayOf(House house) {
        if (house.doorway != null) return house.doorway;
        float[] inside = localToWorld(house.x, house.z, house.rotationY, 0f, house.depth * 0.5f - 5f);
        float[] door = localToWorld(house.x, house.z, house.rotationY, 0f, house.depth * 0.5f + 2.5f);
        float[] outward = localToWorld(0f, 0f, house.rotationY, 0f, 1f);
        // The pavement: just off the road edge, between the carriageway and any front fence
        float[] kerb = localToWorld(house.x, house.z, house.rotationY, 0f, house.plotMaxZ);
        float[] roadDir = { outward[1], -outward[0] };
        float best = Float.MAX_VALUE;
        for (RoadSegment segment : segmentsNear(door[0], door[1], house.setback + 60f)) {
            if (segment.pathIndex != house.frontagePath || segment.culDeSac) continue;
            float[] nearest = nearestOnSegment(door[0], door[1], segment);
            float dx = door[0] - nearest[0], dz = door[1] - nearest[1];
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance < best && distance > 1e-3f) {
                best = distance;
                float reach = segment.width * 0.5f + 1.3f;
                kerb = new float[] { nearest[0] + dx / distance * reach, nearest[1] + dz / distance * reach };
                float sx = segment.end.x - segment.start.x, sz = segment.end.z - segment.start.z;
                float sl = (float) Math.sqrt(sx * sx + sz * sz);
                if (sl > 1e-3f) roadDir = new float[] { sx / sl, sz / sl };
            }
        }
        house.doorway = new Doorway(inside, door, kerb, roadDir, house.nationId, house.frontagePath,
                settlementManager.getUrbanness(house.x, house.z), house.seed);
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

    public float getUrbanness(float worldX, float worldZ) {
        return settlementManager.getUrbanness(worldX, worldZ);
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

        RoadNetworkBuilder builder = new RoadNetworkBuilder(worldSeed, totalRegionWidth, seaLevelHeight, noise,
                settlementManager, nationManager);
        roadNetwork.addAll(builder.build());
        deadEnds.addAll(builder.getDeadEnds());

        for (int pathIndex = 0; pathIndex < roadNetwork.size(); pathIndex++) {
            RoadPath path = roadNetwork.get(pathIndex);
            float width = path.roadClass.width;
            // Running distance along the path keeps dash patterns continuous across segments
            float distanceAlongPath = 0.0f;
            for (int i = 0; i < path.points.size() - 1; i++) {
                Vector3 start = path.points.get(i);
                Vector3 end = path.points.get(i + 1);
                float length = (float) Math.sqrt((end.x - start.x) * (end.x - start.x) + (end.z - start.z) * (end.z - start.z));
                if (length < 0.01f) {
                    continue;
                }
                Vector3 startTangent = roadTangent(path.points, i == 0 ? i : i - 1, i + 1);
                Vector3 endTangent = roadTangent(path.points, i, i + 1 == path.points.size() - 1 ? i + 1 : i + 2);
                float midpointX = (start.x + end.x) * 0.5f;
                float midpointZ = (start.z + end.z) * 0.5f;
                int nationId = nationManager.getNationAtWorld(midpointX, midpointZ, totalRegionWidth);
                RoadSegment segment = new RoadSegment(start, end, startTangent, endTangent, nationId, pathIndex,
                        path.roadClass, distanceAlongPath, length, width, false);

                GuardRailStyle railStyle = guardRailStyles.get(nationId);
                segment.railed = path.roadClass == RoadPath.RoadClass.HIGHWAY && railStyle != null && railStyle.enabled
                        && settlementManager.getUrbanness(midpointX, midpointZ) < RAIL_MAX_URBANNESS;

                roadSegmentsByChunk.computeIfAbsent(chunkKeyAt(midpointX, midpointZ), ignored -> new ArrayList<>()).add(segment);
                segmentsByPath.computeIfAbsent(pathIndex, ignored -> new ArrayList<>()).add(segment);
                distanceAlongPath += length;
            }
        }

        long startTime = System.currentTimeMillis();
        placeHouses();
        System.out.printf("[HOUSES] %d houses placed along the roads in %d ms%n", houses.size(), System.currentTimeMillis() - startTime);
        chooseShops();
        for (List<RoadSegment> bucket : roadSegmentsByChunk.values()) {
            for (RoadSegment segment : bucket) {
                                boolean playable = Math.abs(segment.start.x) < regionWidth * 0.5f && Math.abs(segment.start.z) < regionWidth * 0.5f;
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
        float[] eye = localToWorld(house.x, house.z, house.rotationY, house.width * 0.35f, distance);
        float dx = house.x - eye[0], dz = house.z - eye[1];
        float len = (float) Math.hypot(dx, dz);
        return new float[] { eye[0], eye[1], dx / len, dz / len };
    }

    /**
     * A random point on a road over dry land, for spawning the player.
     * Returns {x, z, directionX, directionZ}, or null if the world has no roads.
     */
    // Kept free of signs: where the landing pod stands this round {x, z, radius}
    private volatile float[] keepClear;

    /** Nothing new (signs) is put within radius of (x, z); for the landing pod. */
    public void setKeepClear(float x, float z, float radius) {
        keepClear = new float[] { x, z, radius };
    }

    private boolean inKeepClear(float x, float z) {
        float[] k = keepClear;
        return k != null && (x - k[0]) * (x - k[0]) + (z - k[1]) * (z - k[1]) < k[2] * k[2];
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
            float rx = segment.start.x + (segment.end.x - segment.start.x) * t;
            float rz = segment.start.z + (segment.end.z - segment.start.z) * t;
            float alongX = (segment.end.x - segment.start.x) / segment.length;
            float alongZ = (segment.end.z - segment.start.z) / segment.length;
            float side = rand.nextBoolean() ? 1f : -1f;
            float acrossX = -alongZ * side, acrossZ = alongX * side;
            float cx, cz, dirX, dirZ;
            if (rand.nextFloat() < 0.2f) {
                // Down on the road itself, stairs along it
                cx = rx;
                cz = rz;
                dirX = alongX * side;
                dirZ = alongZ * side;
            } else {
                // Off to one side, stairs towards the road
                float off = segment.width * 0.5f + reach * 0.75f + rand.nextFloat() * 45f;
                cx = rx + acrossX * off;
                cz = rz + acrossZ * off;
                dirX = -acrossX;
                dirZ = -acrossZ;
            }
            float sx = cx + dirX * standsAt, sz = cz + dirZ * standsAt;
            float fx = cx + dirX * reach, fz = cz + dirZ * reach;
            if (isPlotLocation(cx, cz, reach) || isPlotLocation(sx, sz, 8f) || isPlotLocation(fx, fz, 8f)) continue;
            if (!dryLand.test(cx, cz) || !dryLand.test(sx, sz) || !dryLand.test(fx, fz)) continue;
            boolean feetDry = true;
            for (int leg = 0; leg < 4 && feetDry; leg++) {
                double a = Math.PI * 0.25 + leg * Math.PI * 0.5;
                feetDry = dryLand.test(cx + (float) Math.cos(a) * 36f, cz + (float) Math.sin(a) * 36f);
            }
            if (!feetDry) continue;
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
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        if (rand.nextBoolean()) {
            dirX = -dirX;
            dirZ = -dirZ;
        }
        return new float[] {
            segment.start.x + (segment.end.x - segment.start.x) * t,
            segment.start.z + (segment.end.z - segment.start.z) * t,
            dirX, dirZ
        };
    }

    /** Visits every building footprint in the region; extensions are visited as their own rectangle. */
    public void forEachBuilding(BuildingVisitor visitor) {
        for (House house : houses) {
            visitor.visit(house.x, house.z, house.rotationY, house.width, house.depth, house.nationId);
            if (house.extensionSide != 0) {
                float[] centre = localToWorld(house.x, house.z, house.rotationY,
                        house.extensionSide * (house.width + house.extensionWidth) * 0.5f,
                        (house.extensionDepth - house.depth) * 0.5f);
                visitor.visit(centre[0], centre[1], house.rotationY, house.extensionWidth, house.extensionDepth, house.nationId);
            }
        }
    }

    // ==========================================
    //          SPATIAL QUERIES
    // ==========================================

    /** Whether a point lies within clearance of the edge of any road or turning circle. */
    public boolean isRoadLocation(float worldX, float worldZ, float clearance) {
        return roadAt(worldX, worldZ, clearance) || (nearJoin(worldX) && roadAt(otherSide(worldX), worldZ, clearance));
    }

    private boolean roadAt(float worldX, float worldZ, float clearance) {
        for (RoadSegment segment : segmentsNear(worldX, worldZ, clearance + MAX_ROAD_HALF_WIDTH)) {
            float limit = segment.width * 0.5f + clearance;
            if (distanceSquaredToSegment(worldX, worldZ, segment.start, segment.end) <= limit * limit) {
                return true;
            }
        }
        return false;
    }

    /** Whether a point lies within clearance of any house (including its extension). */
    public boolean isBuildingLocation(float worldX, float worldZ, float clearance) {
        if (!roadNetworkInitialized) {
            return false;
        }
        return buildingAt(worldX, worldZ, clearance) || (nearJoin(worldX) && buildingAt(otherSide(worldX), worldZ, clearance));
    }

    private boolean buildingAt(float worldX, float worldZ, float clearance) {
        for (House house : housesNear(worldX, worldZ, maxHouseReach + clearance)) {
            float[] local = worldToLocal(house, worldX, worldZ);
            if (local[0] >= house.houseMinX() - clearance && local[0] <= house.houseMaxX() + clearance
                    && local[1] >= -house.depth * 0.5f - clearance && local[1] <= house.depth * 0.5f + clearance) {
                return true;
            }
        }
        return false;
    }

    /** Whether a point lies inside any house plot (garden and fence included). */
    private boolean isPlotLocation(float worldX, float worldZ, float clearance) {
        return plotAt(worldX, worldZ, clearance) || (nearJoin(worldX) && plotAt(otherSide(worldX), worldZ, clearance));
    }

    private boolean plotAt(float worldX, float worldZ, float clearance) {
        for (House house : housesNear(worldX, worldZ, maxPlotReach + clearance)) {
            float[] local = worldToLocal(house, worldX, worldZ);
            if (local[0] >= house.plotMinX - clearance && local[0] <= house.plotMaxX + clearance
                    && local[1] >= house.plotMinZ - clearance && local[1] <= house.plotMaxZ + clearance) {
                return true;
            }
        }
        return false;
    }

    private List<House> housesNear(float worldX, float worldZ, float reach) {
        List<House> nearby = new ArrayList<>();
        int centerChunkX = chunkIndex(worldX);
        int centerChunkZ = chunkIndex(worldZ);
        int radius = Math.max(1, (int) Math.ceil(reach / roadChunkSize));
        for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
            for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
                List<House> bucket = housesByChunk.get(chunkKey(chunkX, chunkZ));
                if (bucket != null) {
                    nearby.addAll(bucket);
                }
            }
        }
        return nearby;
    }

    private List<RoadSegment> segmentsNear(float worldX, float worldZ, float distance) {
        List<RoadSegment> nearby = new ArrayList<>();
        int centerChunkX = chunkIndex(worldX);
        int centerChunkZ = chunkIndex(worldZ);
        int chunkRadius = Math.max(1, (int) Math.ceil((distance + MAX_ROAD_SEGMENT_HALF_LENGTH) / roadChunkSize));
        for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
            for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
                List<RoadSegment> segments = roadSegmentsByChunk.get(chunkKey(chunkX, chunkZ));
                if (segments != null) {
                    nearby.addAll(segments);
                }
            }
        }
        return nearby;
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
                if (!segment.culDeSac && !segment.railed) {
                    rollHousesAlong(segment, candidates);
                }
            }
        }
        // Bucket iteration order is not stable, so sort fully by priority then seed for a deterministic result
        candidates.sort((a, b) -> a.priority != b.priority ? Float.compare(b.priority, a.priority) : Long.compare(a.seed, b.seed));

        float largestPlot = 0.0f;
        for (House candidate : candidates) {
            largestPlot = Math.max(largestPlot, candidate.plotRadius());
        }

        Map<Long, List<House>> plotIndex = new HashMap<>();
        for (House candidate : candidates) {
            float[] centre = localToWorld(candidate.x, candidate.z, candidate.rotationY,
                    candidate.plotCentreLocalX(), candidate.plotCentreLocalZ());
            int cellX = (int) Math.floor(centre[0] / PLOT_INDEX_CELL);
            int cellZ = (int) Math.floor(centre[1] / PLOT_INDEX_CELL);
            int reach = (int) Math.ceil((candidate.plotRadius() + largestPlot + PLOT_GAP) / PLOT_INDEX_CELL);

            boolean blocked = false;
            for (int dz = -reach; dz <= reach && !blocked; dz++) {
                for (int dx = -reach; dx <= reach && !blocked; dx++) {
                    List<House> cell = plotIndex.get(chunkKey(cellX + dx, cellZ + dz));
                    if (cell == null) {
                        continue;
                    }
                    for (House other : cell) {
                        if (plotsOverlap(candidate, other)) {
                            blocked = true;
                            break;
                        }
                    }
                }
            }
            if (blocked) {
                continue;
            }
            plotIndex.computeIfAbsent(chunkKey(cellX, cellZ), ignored -> new ArrayList<>()).add(candidate);
            houses.add(candidate);
            housesByChunk.computeIfAbsent(chunkKeyAt(candidate.x, candidate.z), ignored -> new ArrayList<>()).add(candidate);

            float houseReach = Math.max(-candidate.houseMinX(), candidate.houseMaxX()) + candidate.depth * 0.5f;
            maxHouseReach = Math.max(maxHouseReach, houseReach);
            float plotReach = Math.max(Math.max(-candidate.plotMinX, candidate.plotMaxX), Math.max(-candidate.plotMinZ, candidate.plotMaxZ));
            maxPlotReach = Math.max(maxPlotReach, plotReach * 1.42f);
        }
        connectNeighbourFences();
    }

    private void rollHousesAlong(RoadSegment segment, List<House> candidates) {
        long segmentSeed = worldSeed ^ ((long) segment.pathIndex * 0x9E3779B97F4A7C15L)
                ^ (Float.floatToIntBits(segment.startDistance) * 0xC2B2AE3D27D4EB4FL);
        Random rand = new Random(segmentSeed);
        float midX = (segment.start.x + segment.end.x) * 0.5f;
        float midZ = (segment.start.z + segment.end.z) * 0.5f;
        float urbanness = settlementManager.getUrbanness(midX, midZ);
        float habitability = settlementManager.getHabitability(midX, midZ);

        Double ruralProbability = nationBuildingProbabilities.get(segment.nationId);
        Float urbanDensity = nationUrbanDensities.get(segment.nationId);
        if (ruralProbability == null || urbanDensity == null) {
            return;
        }
        float perHundred = (float) (ruralProbability * RURAL_FRONTAGE_SCALE) * (0.3f + 0.7f * habitability)
                + urbanDensity * (float) Math.pow(urbanness, URBAN_DENSITY_EXPONENT);
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
                        int nationId = nationManager.getNationAtWorld(roadX + outwardX * probe, roadZ + outwardZ * probe, regionWidth);
            FenceStyle fenceStyle = fenceStyles.get(nationId);
            if (!formStyles.containsKey(nationId)) {
                continue;
            }
            house.nationId = nationId;
            house.form = chooseForm(roadX, roadZ, nationId, house.seed);
            BuildingStyle style = styleOf(house);
            house.width = style.width * house.sizeScale;
            house.depth = style.depth * house.sizeScale;
            if (extensionRoll < style.extensionChance) {
                house.extensionSide = extensionLeft ? -1 : 1;
                house.extensionWidth = house.width * style.extensionWidthRatio;
                house.extensionDepth = house.depth * style.extensionDepthRatio;
            }
            house.fenced = fenceStyle != null && fenceRoll < fenceStyle.fenceChance;

            float offset = segment.width * 0.5f + house.setback + house.depth * 0.5f;
            house.x = roadX + outwardX * offset;
            house.z = roadZ + outwardZ * offset;
            house.rotationY = facingRotation(-outwardX, -outwardZ);

            float overhang = style.roofOverhang * house.sizeScale;
            house.plotMinX = house.houseMinX() - overhang - sideYard;
            house.plotMaxX = house.houseMaxX() + overhang + sideYard;
            house.plotMinZ = -house.depth * 0.5f - overhang - backYard;
            house.plotMaxZ = house.depth * 0.5f + house.setback - FRONT_FENCE_GAP;

                        if (!settleOnGround(house, style) || !isPlotClearOfRoads(house)) {
                continue;
            }
            house.wallVariant = chooseWallVariant(house.x, house.z, house.nationId, house.seed);
        decideFence(house);
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
            if (trims > 0 && meetsAnotherRoad(end, deadEnd.pathIndex, terminal.width)) {
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
            List<RoadSegment> bucket = roadSegmentsByChunk.get(chunkKeyAt((terminal.start.x + terminal.end.x) * 0.5f,
                    (terminal.start.z + terminal.end.z) * 0.5f));
            if (bucket != null) {
                bucket.remove(terminal);
            }
            for (int i = 0; i < path.points.size(); i++) {
                if (path.points.get(i) == end) {
                    path.points.remove(i);
                    break;
                }
            }
        }
        return null;
    }

    private boolean meetsAnotherRoad(Vector3 point, int pathIndex, float width) {
        for (RoadSegment segment : segmentsNear(point.x, point.z, width + MAX_ROAD_HALF_WIDTH)) {
            if (segment.pathIndex == pathIndex) {
                continue;
            }
            float limit = (segment.width + width) * 0.5f + 2.0f;
            if (distanceSquaredToSegment(point.x, point.z, segment.start, segment.end) <= limit * limit) {
                return true;
            }
        }
        return false;
    }

    /** A house on the line of the road just past its end; later attempts are smaller and closer. */
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
                int nationId = nationManager.getNationAtWorld(end.x + dirX * probe, end.z + dirZ * probe, regionWidth);
        FenceStyle fenceStyle = fenceStyles.get(nationId);
        if (!formStyles.containsKey(nationId)) {
            return null;
        }
        house.nationId = nationId;
        house.form = chooseForm(end.x, end.z, nationId, house.seed);
        BuildingStyle style = styleOf(house);
        house.width = style.width * house.sizeScale;
        house.depth = style.depth * house.sizeScale;
        if (attempt == 0 && extensionRoll < style.extensionChance) {
            house.extensionSide = extensionLeft ? -1 : 1;
            house.extensionWidth = house.width * style.extensionWidthRatio;
            house.extensionDepth = house.depth * style.extensionDepthRatio;
        }
        house.fenced = fenceStyle != null && fenceRoll < fenceStyle.fenceChance;

        float offset = terminal.width * 0.5f + house.setback + house.depth * 0.5f;
        house.x = end.x + dirX * offset;
        house.z = end.z + dirZ * offset;
        house.rotationY = facingRotation(-dirX, -dirZ);

        float overhang = style.roofOverhang * house.sizeScale;
        house.plotMinX = house.houseMinX() - overhang - sideYard;
        house.plotMaxX = house.houseMaxX() + overhang + sideYard;
        house.plotMinZ = -house.depth * 0.5f - overhang - backYard;
        house.plotMaxZ = house.depth * 0.5f + house.setback - FRONT_FENCE_GAP;

                if (!settleOnGround(house, style) || !isPlotClearOfRoads(house)) {
            return null;
        }
        house.wallVariant = chooseWallVariant(house.x, house.z, house.nationId, house.seed);
        decideFence(house);
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
        float[] backLeft = localToWorld(house.x, house.z, house.rotationY, house.plotMinX, house.plotMinZ);
        float[] backRight = localToWorld(house.x, house.z, house.rotationY, house.plotMaxX, house.plotMinZ);
        if (TerrainMesh.getLayeredHeight(backLeft[0], backLeft[1], terrainNoise) <= seaLevel
                || TerrainMesh.getLayeredHeight(backRight[0], backRight[1], terrainNoise) <= seaLevel) {
            return false;
        }

        // Sink the base below the lowest corner and grow the walls so slopes never expose a gap
        house.baseY = minGround - BUILDING_FOUNDATION_DEPTH;
        house.mainWallHeight = (mainRange[1] - house.baseY) + style.wallHeight * house.sizeScale;
        if (extensionRange != null) {
            house.extensionWallHeight = (extensionRange[1] - house.baseY)
                    + style.wallHeight * house.sizeScale * style.extensionHeightRatio;
        }
        float[] door = localToWorld(house.x, house.z, house.rotationY, 0.0f, halfDepth);
        house.doorBase = TerrainMesh.getLayeredHeight(door[0], door[1], terrainNoise) - house.baseY;
        return true;
    }

    private float[] groundRange(House house, float minX, float maxX, float minZ, float maxZ) {
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            float[] position = localToWorld(house.x, house.z, house.rotationY,
                    (corner & 1) == 0 ? minX : maxX, (corner & 2) == 0 ? minZ : maxZ);
            float ground = TerrainMesh.getLayeredHeight(position[0], position[1], terrainNoise);
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
        float[] worldCentre = localToWorld(house.x, house.z, house.rotationY, plotCentre[0], plotCentre[1]);

        for (RoadSegment segment : segmentsNear(worldCentre[0], worldCentre[1], reach)) {
            boolean ownRoad = segment.pathIndex == house.frontagePath;
            float[] boxCentre = ownRoad ? houseCentre : plotCentre;
            float halfX = ownRoad ? houseHalfX : plotHalfX;
            float halfZ = ownRoad ? houseHalfZ : plotHalfZ;

            // Segment end points in the frame of the box being tested
            float[] a = worldToLocal(house, segment.start.x, segment.start.z);
            float[] b = worldToLocal(house, segment.end.x, segment.end.z);
            float margin = segment.roadClass == RoadPath.RoadClass.HIGHWAY ? PLOT_HIGHWAY_MARGIN : PLOT_ROAD_MARGIN;
            float limit = segment.width * 0.5f + margin;
            if (segmentToBoxDistanceSquared(a[0] - boxCentre[0], a[1] - boxCentre[1], b[0] - boxCentre[0], b[1] - boxCentre[1],
                    halfX, halfZ) < limit * limit) {
                return false;
            }
        }
        return true;
    }

    /** Separating-axis test between two rotated plot rectangles. */
    private static boolean plotsOverlap(House a, House b) {
        float[] centreA = localToWorld(a.x, a.z, a.rotationY, a.plotCentreLocalX(), a.plotCentreLocalZ());
        float[] centreB = localToWorld(b.x, b.z, b.rotationY, b.plotCentreLocalX(), b.plotCentreLocalZ());
        float dx = centreB[0] - centreA[0];
        float dz = centreB[1] - centreA[1];
        float reach = a.plotRadius() + b.plotRadius() + PLOT_GAP;
        if (dx * dx + dz * dz > reach * reach) {
            return false;
        }

        float[][] axesA = plotAxes(a);
        float[][] axesB = plotAxes(b);
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

    /** World directions of a house's local X and Z axes. */
    private static float[][] plotAxes(House house) {
        float radians = (float) Math.toRadians(house.rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        return new float[][] { { cosine, -sine }, { sine, cosine } };
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

        private NationBatch(int nationId) {
            this.nationId = nationId;
        }

        private MeshBuilder builder(String part) {
            return builders.computeIfAbsent(part, ignored -> new MeshBuilder());
        }

        private void include(float x, float y, float z, float margin) {
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
                boolean doubleSided = part.equals("rail") || part.equals("band") || part.equals("fence") || part.equals("security")
                        || part.startsWith("curtain") || part.equals("flag") || part.equals("goods");
                                boolean paint = part.equals("line");
                Material material = material(part, nationId);
                parts.add(new InfrastructureObject.BatchPart(entry.getValue().vertexArray(), entry.getValue().indexArray(),
                        material, doubleSided, paint, material.diffuseMapExists(), part.equals("glass")));
            }
            if (parts.isEmpty() || minX > maxX) {
                return null;
            }
            float centreX = (minX + maxX) * 0.5f;
            float centreZ = (minZ + maxZ) * 0.5f;
            float centreY = TerrainMesh.getLayeredHeight(centreX, centreZ, terrainNoise);
            float halfDiagonal = 0.5f * (float) Math.sqrt((maxX - minX) * (maxX - minX) + (maxZ - minZ) * (maxZ - minZ));
            float radius = Math.max(halfDiagonal, maxY - centreY);
            return InfrastructureObject.createBatch(new Vector3(centreX, centreY, centreZ), nationId, radius, parts);
        }
    }

    public List<InfrastructureObject> generateForChunk(int cx, int cz, float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        List<InfrastructureObject> objects = new ArrayList<>();
        prepareRoadNetwork(chunkSize, totalRegionWidth, seaLevelHeight, noise);

        Random signRand = new Random((long)cx * 8912L + (long)cz * 4123L);
        List<float[]> signPositions = new ArrayList<>();
        Map<Integer, NationBatch> batches = new HashMap<>();
        long key = chunkKey(cx, cz);
        recording = new ArrayList<>();

        List<House> chunkHouses = across(cx, cz, housesByChunk, this::shifted);
        if (!chunkHouses.isEmpty()) {
            for (House house : chunkHouses) {
                NationBatch batch = batches.computeIfAbsent(house.nationId, NationBatch::new);
                bakeHouse(batch, house, objects);
                if (house.fenced) {
                    bakeFence(batch, house);
                }
                // In town, a tall flagpole at the front corner of some plots
                float urban = settlementManager.getUrbanness(house.x, house.z);
                Random poleRand = new Random(house.seed ^ 0x9013L);
                if (urban > 0.25f && poleRand.nextFloat() < patriotismOf(house.nationId) * 0.45f * urban) {
                    float side = poleRand.nextBoolean() ? 1f : -1f;
                    float[] at = localToWorld(house.x, house.z, house.rotationY,
                            side > 0 ? house.plotMaxX - 4f : house.plotMinX + 4f, house.plotMaxZ - 4f);
                    if (!inKeepClear(at[0], at[1]) && !isRoadLocation(at[0], at[1], 2f)) {
                        flagpole(batch, house.nationId, at[0], at[1], 40f + poleRand.nextFloat() * 14f);
                    }
                }
                if (signRand.nextDouble() < buildingStyles.get(house.nationId).signChance) {
                    InfrastructureObject sign = createHouseSign(house, signRand);
                    if (sign != null && inKeepClear(sign.position.x, sign.position.z)) sign = null;
                    if (sign != null) {
                        objects.add(sign);
                        recordSignPosts(sign);
                        signPositions.add(new float[] { sign.position.x, sign.position.z });
                    }
                }
            }
        }

        List<RoadSegment> segments = across(cx, cz, roadSegmentsByChunk, this::shifted);
        if (!segments.isEmpty()) {
            for (RoadSegment segment : segments) {
                NationBatch batch = batches.computeIfAbsent(segment.nationId, NationBatch::new);
                if (segment.culDeSac) {
                    appendCulDeSac(batch, segment);
                    continue;
                }
                appendRoadSegment(batch, segment);
                InfrastructureObject sign = tryCreateRoadsideSign(segment, signRand, signPositions);
                if (sign != null && inKeepClear(sign.position.x, sign.position.z)) sign = null;
                if (sign != null) {
                    objects.add(sign);
                    recordSignPosts(sign);
                    signPositions.add(new float[] { sign.position.x, sign.position.z });
                }
            }
        }

        for (NationBatch batch : batches.values()) {
            InfrastructureObject object = batch.build();
            if (object != null) {
                objects.add(object);
            }
        }
        float[] packed = new float[recording.size() * 7];
        for (int i = 0; i < recording.size(); i++) System.arraycopy(recording.get(i), 0, packed, i * 7, 7);
        obstaclesByChunk.put(key, packed);
        recording = null;
        return objects;
    }

    private void recordSegment(float ax, float az, float bx, float bz, float halfWidth) {
        recordSegment(ax, az, bx, bz, halfWidth, Float.POSITIVE_INFINITY);
    }

    /** A solid line standing top high above the ground (for jumping over). */
    private void recordSegment(float ax, float az, float bx, float bz, float halfWidth, float top) {
        if (recording != null) recording.add(new float[] { 0f, ax, az, bx, bz, halfWidth, top });
    }

    /** A sign stands on two posts either side of its board, which is up out of the way. */
    private void recordSignPosts(InfrastructureObject sign) {
        for (com.xenoguesser.math.Matrix4 post : new com.xenoguesser.math.Matrix4[] { sign.leftPostMatrix, sign.rightPostMatrix }) {
            if (post == null) continue;
            float[] m = post.toFloatArrayForGLSL();
            recordCircle(m[12], m[14], 0.9f);
        }
    }

    private void recordCircle(float x, float z, float radius) {
        if (recording != null) recording.add(new float[] { 1f, x, z, 0f, 0f, radius, Float.POSITIVE_INFINITY });
    }

    /** Walls, fences, guard rails and sign posts near a point, from the chunks built so far. */
    public void obstaclesNear(float x, float z, float reach, Collision.Sink sink) {
        // A chunk's shapes can reach past its edge (fences round a plot, a rail along a road)
        float margin = reach + 120f;
        for (int cz = chunkIndex(z - margin); cz <= chunkIndex(z + margin); cz++) {
            for (int cx = chunkIndex(x - margin); cx <= chunkIndex(x + margin); cx++) {
                float[] shapes = obstaclesByChunk.get(chunkKey(cx, cz));
                if (shapes == null) continue;
                for (int i = 0; i < shapes.length; i += 7) {
                    if (shapes[i] == 0f) sink.segment(shapes[i + 1], shapes[i + 2], shapes[i + 3], shapes[i + 4], shapes[i + 5], shapes[i + 6]);
                    else sink.circle(shapes[i + 1], shapes[i + 2], shapes[i + 5], shapes[i + 6]);
                }
            }
        }
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
                    Vector3 cloth = curtainColour(part.charAt(7) - '0');
                    return new Material(cloth, cloth, new Vector3(0.02f, 0.02f, 0.02f), 2.0f);
                }
                case "frame":
                    return new Material(building.frameColour, building.frameColour, specular, 2.0f);
                case "security": {
                    Vector3 metal = fenceStyles.get(nationId).securityColour;
                    return new Material(metal, metal, new Vector3(0.3f, 0.3f, 0.3f), 24.0f);
                }
                case "secwall": {
                    // Plain rendered concrete, a little tinted by the nation's metalwork
                    Vector3 metal = fenceStyles.get(nationId).securityColour;
                    Vector3 concrete = new Vector3(0.62f + metal.x * 0.15f, 0.6f + metal.y * 0.15f, 0.57f + metal.z * 0.15f);
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

    private float roadSurfaceY(float x, float z) {
        // Roads ride a low causeway wherever they dip below the sea
        return Math.max(TerrainMesh.getLayeredHeight(x, z, terrainNoise), seaLevel + 1.0f) + ROAD_SURFACE_OFFSET;
    }

    /** Positions of a line offset sideways from the centreline, one per subdivision, mitred at the ends. */
    private float[][] offsetEdge(RoadSegment segment, float offset) {
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
            edge[i] = new float[] { edgeX, roadSurfaceY(edgeX, edgeZ), edgeZ };
        }
        return edge;
    }

    private void appendRoadSegment(NationBatch batch, RoadSegment segment) {
        float halfWidth = segment.width * 0.5f;
        float[][] leftEdge = offsetEdge(segment, -halfWidth);
        float[][] rightEdge = offsetEdge(segment, halfWidth);

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

        // Other roads close enough to meet this segment, for junction handling
        List<RoadSegment> crossing = new ArrayList<>();
        float midX = (segment.start.x + segment.end.x) * 0.5f;
        float midZ = (segment.start.z + segment.end.z) * 0.5f;
        for (RoadSegment other : segmentsNear(midX, midZ, segment.length * 0.5f + segment.width + MAX_ROAD_HALF_WIDTH)) {
            if (other.pathIndex != segment.pathIndex || other.culDeSac) {
                crossing.add(other);
            }
        }

        appendRoadLines(batch.builder("line"), segment, leftEdge, rightEdge, crossing);
        if (segment.railed) {
            GuardRailStyle style = guardRailStyles.get(segment.nationId);
            appendGuardRail(batch, segment, style, -1.0f, crossing);
            appendGuardRail(batch, segment, style, 1.0f, crossing);
        }
    }

    /** Highest junction rank of any other road covering the point, or -1. */
    private int coveringRank(float x, float z, List<RoadSegment> crossing, float margin) {
        int rank = -1;
        for (RoadSegment other : crossing) {
            float limit = other.width * 0.5f + margin;
            if (other.rank() > rank && distanceSquaredToSegment(x, z, other.start, other.end) <= limit * limit) {
                rank = other.rank();
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
                                 List<RoadSegment> crossing) {
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
                                    float[][] leftEdge, float[][] rightEdge, List<RoadSegment> crossing) {
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
            for (RoadSegment other : crossing) {
                if (other.culDeSac || other.rank() <= ownRank) {
                    continue;
                }
                float limit = other.width * 0.5f + 1.0f;
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
        int centre = asphalt.addVertex(centreX, roadSurfaceY(centreX, centreZ), centreZ, 0.0f, 1.0f, 0.0f, 0.5f, 0.5f);
        for (int i = 0; i <= sides; i++) {
            double angle = 2.0 * Math.PI * i / sides;
            float x = centreX + (float) Math.cos(angle) * radius;
            float z = centreZ + (float) Math.sin(angle) * radius;
            asphalt.addVertex(x, roadSurfaceY(x, z), z, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f);
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
                                 List<RoadSegment> crossing) {
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
            float[] start = railBase(base, t0);
            float[] end = railBase(base, t1);
            recordSegment(start[0], start[2], end[0], end[2], 0.5f, style.railHeight + style.railSize * 0.5f);
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
        HouseWall[] walls = extrudeWalls(batch.builder(wallPart), house, footprint, house.mainWallHeight, style.taper, smooth,
                variant.tileWidth, variant.tileHeight);

        if (style.footprint == BuildingStyle.Footprint.BOX) {
                        float[] roofV = roofVertices.get(roofKey(house.nationId, house.form));
            int[] roofIdx = roofIndices.get(roofKey(house.nationId, house.form));
            bake(batch.builder("roof"), roofV, roofIdx, house, house.width * style.taper + 2.0f * overhang, style.roofHeight * scale,
                    house.depth * style.taper + 2.0f * overhang, 0, house.mainWallHeight, 0, style.roofTileSize);
        } else {
            centredRoof(batch.builder("roof"), house, footprint, style, house.mainWallHeight, overhang, smooth);
        }

        // The door sits in the middle of the wall that faces the road
        HouseWall front = frontWall(walls);
        float doorHalfW = Math.min(style.doorWidth * scale, front.length * 0.7f) * 0.5f;
        float doorH = style.doorHeight * scale;
        wallPanel(batch.builder("door"), house, front, 0.5f, house.doorBase + doorH * 0.5f, doorHalfW, doorH * 0.5f, DOOR_OFFSET);

        if (house.extensionSide != 0) {
            float centreX = house.extensionSide * (house.width + house.extensionWidth) * 0.5f;
            float centreZ = (house.extensionDepth - house.depth) * 0.5f;
            float extensionOverhang = overhang * 0.6f;
            // Nudged outward a hair so the shared wall never z-fights the house
            float[][] wing = footprint(style, house.extensionWidth + 0.2f, house.extensionDepth,
                    centreX + house.extensionSide * 0.1f, centreZ);
                        HouseWall[] wingWalls = extrudeWalls(batch.builder(wallPart), house, wing, house.extensionWallHeight, 1.0f, false,
                    variant.tileWidth, variant.tileHeight);
            bake(batch.builder("roof"), roofVertices.get(roofKey(house.nationId, house.form)), roofIndices.get(roofKey(house.nationId, house.form)), house,
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
        float urbanHere = settlementManager.getUrbanness(house.x, house.z);
        if (house.shop != null ? signs.nextFloat() < 0.6f : urbanHere > 0.45f && signs.nextFloat() < 0.2f * urbanHere) {
            wallPoster(house, walls, front, objects, signs);
        }
        // Now and then a flag flies from a short pole above the door, more often in town
        float urbanness = settlementManager.getUrbanness(house.x, house.z);
        if (new Random(house.seed ^ 0xF1A6L).nextFloat() < patriotismOf(house.nationId) * (0.1f + 0.3f * urbanness)) {
            wallFlag(batch, house, front, house.doorBase + style.doorHeight * scale + 3.5f);
        }
        emitWalls(batch.builder(wallPart), house, walls, variant.tileWidth, variant.tileHeight);

        float roofTop = house.baseY + house.mainWallHeight + style.roofHeight * scale;
        batch.include(house.x, roofTop, house.z, Math.max(house.plotMaxX - house.plotMinX, house.plotMaxZ - house.plotMinZ));
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
            float[] wa = localToWorld(house.x, house.z, house.rotationY, footprint[i][0], footprint[i][1]);
            float[] wb = localToWorld(house.x, house.z, house.rotationY, footprint[(i + 1) % n][0], footprint[(i + 1) % n][1]);
            recordSegment(wa[0], wa[1], wb[0], wb[1], 0.6f);
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
     * into horizontal bands at the windows' tops and bottoms, and each band into pieces
     * either side of the windows crossing it.
     */
    private void emitWalls(MeshBuilder builder, House house, HouseWall[] walls, float tileWidth, float tileHeight) {
        for (HouseWall wall : walls) {
            float height = wall.topA[1];
            java.util.TreeSet<Float> levels = new java.util.TreeSet<>();
            levels.add(0f);
            levels.add(height);
            for (float[] o : wall.openings) {
                levels.add(Math.max(0f, Math.min(height, o[2])));
                levels.add(Math.max(0f, Math.min(height, o[3])));
            }
            Float[] ys = levels.toArray(new Float[0]);
            for (int b = 0; b + 1 < ys.length; b++) {
                float ya = ys[b], yb = ys[b + 1];
                if (yb - ya < 1e-4f) continue;
                List<float[]> holes = new ArrayList<>();
                for (float[] o : wall.openings) {
                    if (o[2] <= ya + 1e-4f && o[3] >= yb - 1e-4f) holes.add(o);
                }
                holes.sort((p, q) -> Float.compare(p[0], q[0]));
                float s = 0f;
                for (float[] hole : holes) {
                    wallPiece(builder, house, wall, s, hole[0], ya, yb, tileWidth, tileHeight);
                    s = Math.max(s, hole[1]);
                }
                wallPiece(builder, house, wall, s, 1f, ya, yb, tileWidth, tileHeight);
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
        float[] along = normalise(sub(wallPoint(wall, s1, y0), wallPoint(wall, s0, y0)));
        float[] up = { 0f, 1f, 0f };

        // The lining of the opening, back to the panel behind it
        MeshBuilder reveal = batch.builder("frame");
        localQuad(reveal, house, inset(wall, s0, y0, 0), inset(wall, s0, y1, 0), inset(wall, s0, y1, WINDOW_DEPTH), inset(wall, s0, y0, WINDOW_DEPTH), along);
        localQuad(reveal, house, inset(wall, s1, y0, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, WINDOW_DEPTH), inset(wall, s1, y0, WINDOW_DEPTH), scale3(along, -1));
        localQuad(reveal, house, inset(wall, s0, y0, 0), inset(wall, s1, y0, 0), inset(wall, s1, y0, WINDOW_DEPTH), inset(wall, s0, y0, WINDOW_DEPTH), up);
        localQuad(reveal, house, inset(wall, s0, y1, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, WINDOW_DEPTH), inset(wall, s0, y1, WINDOW_DEPTH), scale3(up, -1));
        // The dark room behind, closing the hole
        localQuad(batch.builder("interior"), house, inset(wall, s0, y0, WINDOW_DEPTH), inset(wall, s1, y0, WINDOW_DEPTH),
                inset(wall, s1, y1, WINDOW_DEPTH), inset(wall, s0, y1, WINDOW_DEPTH), n);

        // Drawn curtains, two panels hanging in soft folds, meeting with a narrow gap
        Random rand = new Random(house.seed ^ ((long) (s * 1000) * 31L) ^ (long) (centreY * 17));
        int colour = (int) Math.floorMod(house.seed, (long) CURTAIN_COLOURS);
        MeshBuilder cloth = batch.builder("curtain" + colour);
        float gap = (0.01f + 0.03f * rand.nextFloat()) * (s1 - s0);
        float sm = (s0 + s1) * 0.5f;
        curtainPanel(cloth, house, wall, s0, sm - gap, y0 + 0.15f, y1 - 0.1f, along, rand);
        curtainPanel(cloth, house, wall, sm + gap, s1, y0 + 0.15f, y1 - 0.1f, along, rand);

        // The pane, set back a little from the wall
        localQuad(batch.builder("glass"), house, inset(wall, s0, y0, GLASS_SETBACK), inset(wall, s1, y0, GLASS_SETBACK),
                inset(wall, s1, y1, GLASS_SETBACK), inset(wall, s0, y1, GLASS_SETBACK), n);

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

    // How deep the window is set into the wall, and how far back its glass sits
    private static final float WINDOW_DEPTH = 1.8f, GLASS_SETBACK = 0.4f, CURTAIN_DEPTH = 1.35f;
    private static final int CURTAIN_COLOURS = 4;

    /** A point on the wall's face pushed straight in (or out, if depth is negative) from it. */
    private static float[] inset(HouseWall wall, float s, float y, float depth) {
        return sub(wallPoint(wall, s, y), scale3(wall.normal, depth));
    }

    /** One curtain: a sheet in vertical folds, a strip of quads whose depth ripples across it. */
    private void curtainPanel(MeshBuilder builder, House house, HouseWall wall, float s0, float s1, float y0, float y1,
                              float[] along, Random rand) {
        if (s1 - s0 < 1e-3f) return;
        float width = (s1 - s0) * wall.length;
        int folds = Math.max(2, Math.round(width / 1.6f));
        int steps = folds * 4;
        float amplitude = 0.22f;
        float phase = rand.nextFloat() * (float) Math.PI * 2;
        int[] bottom = new int[steps + 1], top = new int[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float angle = phase + t * folds * (float) Math.PI * 2;
            float depth = CURTAIN_DEPTH + amplitude * (float) Math.sin(angle);
            float slope = amplitude * (float) Math.cos(angle) * folds * (float) Math.PI * 2 / width;
            float[] normal = normalise(add(wall.normal, scale3(along, slope)));
            float s = s0 + (s1 - s0) * t;
            bottom[i] = houseVertex(builder, house, inset(wall, s, y0, depth), normal, t, 0f);
            top[i] = houseVertex(builder, house, inset(wall, s, y1, depth), normal, t, 1f);
        }
        for (int i = 0; i < steps; i++) {
            addOutwardQuad(builder, house, bottom[i], bottom[i + 1], top[i + 1], top[i],
                    inset(wall, s0, y0, 0f), inset(wall, s1, y0, 0f), inset(wall, s1, y1, 0f), wall.normal);
        }
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
            SettlementManager.Settlement town = settlementManager.dominantSettlementAt(house.x, house.z);
            if (town == null) continue;
            float dx = (float) Planet.wrapX(house.x - town.x), dz = house.z - town.z;
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            // Villages have a shop or two at their heart; cities a district a few streets across
            float size = Math.min(1f, town.radius / 1600f);
            float core = town.radius * (0.35f + 0.3f * size);
            float chance = (0.35f + 0.6f * size) * (float) Math.exp(-(distance / core) * (distance / core));
            if (rolls.get(house).nextFloat() < chance) makeShop(house, rolls.get(house));
        }
        // Shopping streets: next door to a shop, on the same road, is likely another
        Map<Integer, List<House>> byRoad = new HashMap<>();
        for (House house : houses) byRoad.computeIfAbsent(house.frontagePath, k -> new ArrayList<>()).add(house);
        for (int pass = 0; pass < 3; pass++) {
            List<House> joining = new ArrayList<>();
            for (List<House> road : byRoad.values()) {
                for (House house : road) {
                    if (house.shop != null || !canBeShop(house)) continue;
                    for (House other : road) {
                        if (other.shop == null) continue;
                        if (nextDoor(house, other)) {
                            if (rolls.get(house).nextFloat() < 0.5f * settlementManager.getUrbanness(house.x, house.z) + 0.15f) joining.add(house);
                            break;
                        }
                    }
                }
            }
            for (House house : joining) makeShop(house, rolls.get(house));
        }
        // The odd corner shop out in the suburbs
        for (House house : houses) {
            if (house.shop == null && canBeShop(house) && settlementManager.getUrbanness(house.x, house.z) > 0.25f
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
        float dx = (float) Planet.wrapX(a.x - b.x), dz = a.z - b.z;
        float reach = ((a.plotMaxX - a.plotMinX) + (b.plotMaxX - b.plotMinX)) * 0.5f + 60f;
        return dx * dx + dz * dz < reach * reach;
    }

    /** A building with a front wide enough for a shop window either side of the door. */
    private boolean canBeShop(House house) {
        BuildingStyle style = styleOf(house);
        return frontLength(style, house) >= style.doorWidth * house.sizeScale * 2.2f + 10f;
    }

    private void makeShop(House house, Random rand) {
        if (house.shop != null) return;
        house.shop = new Shop(house, rand);
        shops.add(house.shop);
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

    /** The shop nearest a point within reach, preferring one in the same nation; null if none. */
    public Shop nearestShop(float x, float z, float reach, int nationId) {
        Shop best = null;
        float bestScore = reach * reach;
        for (Shop shop : shops) {
            float dx = shop.house.x - x, dz = shop.house.z - z;
            float score = (dx * dx + dz * dz) * (shop.nationId == nationId ? 1f : 4f);
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

    private synchronized RoadRoutes routes() {
        if (routes == null) routes = new RoadRoutes(this);
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
        float[] worldNormal = localToWorld(0f, 0f, house.rotationY, n[0], n[2]);
        InfrastructureObject sign;
        if (!vertical) {
            float yc = y1 + 0.5f + fasciaH * 0.5f;
            float[] at = add(wallPoint(front, 0.5f, yc), scale3(n, 0.45f + standOff(front, 0.5f, yc, fasciaH)));
            float[] world = localToWorldPoint(house, at);
            float width = Math.min(70f, front.length * (1f - 2f * margin) * 0.95f);
            sign = InfrastructureObject.createWallSign(new Vector3(world[0], world[1], world[2]), house.nationId,
                    facingRotation(worldNormal[0], worldNormal[1]), width, fasciaH, true, false, null);
        } else {
            float side = rand.nextBoolean() ? 0.06f : 0.94f;
            float bladeW = 5f;
            float bottom = y1 + 0.5f;
            float height = Math.max(8f, Math.min(18f, wallTop - bottom - 1f));
            float[] at = add(wallPoint(front, side, bottom + height * 0.5f), scale3(n, bladeW * 0.5f + 0.6f));
            float[] world = localToWorldPoint(house, at);
            float[] along = normalise(sub(front.bottomB, front.bottomA));
            float[] worldAlong = localToWorld(0f, 0f, house.rotationY, along[0], along[2]);
            sign = InfrastructureObject.createWallSign(new Vector3(world[0], world[1], world[2]), house.nationId,
                    facingRotation(worldAlong[0], worldAlong[1]), bladeW, height, true, true, null);
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
            while (t < width - 0.5f) {
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
     * Now and then a poster pasted flat on a wall, often an advert for a shop nearby: on a
     * stretch of wall clear of windows and doors.
     */
    private void wallPoster(House house, HouseWall[] walls, HouseWall front, List<InfrastructureObject> objects, Random rand) {
        float scale = 0.5f + rand.nextFloat() * 0.2f;
        float w = 30f * scale, h = 22f * scale;
        int start = rand.nextInt(walls.length);
        for (int k = 0; k < walls.length; k++) {
            HouseWall wall = walls[(start + k) % walls.length];
            if (wall == front && house.shop == null) continue;
            if (wall.length < w + 3f || wall.topA[1] < house.doorBase + h + 4f) continue;
            float yc = house.doorBase + Math.min(wall.topA[1] - house.doorBase - h * 0.5f - 1.5f, h * 0.5f + 4f);
            // Clear of the ground, wherever it rises against this wall
            float[] foot = localToWorldPoint(house, wallPoint(wall, 0.5f, 0f));
            float ground = Math.max(TerrainMesh.getLayeredHeight(foot[0], foot[2], terrainNoise),
                    Math.max(TerrainMesh.getLayeredHeight(localToWorldPoint(house, wallPoint(wall, 0.5f - (w * 0.5f) / wall.length, 0f))[0],
                            localToWorldPoint(house, wallPoint(wall, 0.5f - (w * 0.5f) / wall.length, 0f))[2], terrainNoise),
                            TerrainMesh.getLayeredHeight(localToWorldPoint(house, wallPoint(wall, 0.5f + (w * 0.5f) / wall.length, 0f))[0],
                                    localToWorldPoint(house, wallPoint(wall, 0.5f + (w * 0.5f) / wall.length, 0f))[2], terrainNoise)));
            yc = Math.max(yc, ground + 3f - house.baseY + h * 0.5f);
            if (yc + h * 0.5f > wall.topA[1] - 1f) continue;
            float halfS = (w * 0.5f + 0.8f) / wall.length;
            boolean clear = true;
            for (float[] o : wall.openings) {
                if (o[1] > 0.5f - halfS && o[0] < 0.5f + halfS && o[3] > yc - h * 0.5f - 0.8f && o[2] < yc + h * 0.5f + 0.8f) {
                    clear = false;
                    break;
                }
            }
            if (wall == front) {
                // Beside a shop's door
                float doorHalf = styleOf(house).doorWidth * house.sizeScale * 0.5f / wall.length;
                if (0.5f - halfS < 0.5f + doorHalf + 0.02f && 0.5f + halfS > 0.5f - doorHalf - 0.02f) clear = false;
            }
            if (!clear) continue;
            float[] n = horizontal(wall.normal);
            float[] at = add(wallPoint(wall, 0.5f, yc), scale3(n, 0.3f + standOff(wall, 0.5f, yc, h)));
            float[] world = localToWorldPoint(house, at);
            float[] worldNormal = localToWorld(0f, 0f, house.rotationY, n[0], n[2]);
            int maxGlyphs = Math.max(1, mainListener != null ? mainListener.getNationAtlasSize(house.nationId) : 10);
            InfrastructureObject poster = InfrastructureObject.createWallSign(new Vector3(world[0], world[1], world[2]), house.nationId,
                    facingRotation(worldNormal[0], worldNormal[1]), w, h, false, false, randomText(rand, maxGlyphs, 40, 120));
            decorateSign(poster, rand, maxGlyphs, true);
            objects.add(poster);
            return;
        }
    }

    // ------------------------------------------------------------------ FLAGS

    /** A tall pole standing in the ground with the nation's flag at the top, flying in the wind. */
    private void flagpole(NationBatch batch, int nationId, float x, float z, float height) {
        float ground = TerrainMesh.getLayeredHeight(x, z, terrainNoise);
        MeshBuilder pole = batch.builder("flagpole");
        addBox(pole, x, ground - 1f, z, 1f, 0f, 0.45f, 0.45f, height + 1.5f);
        addBox(pole, x, ground + height + 0.4f, z, 1f, 0f, 0.9f, 0.9f, 0.8f);   // the finial
        float flagH = 9f + height * 0.05f, flagW = flagH * FlagDesigner.WIDTH / FlagDesigner.HEIGHT;
        float dirX = (float) Math.cos(windAngle), dirZ = (float) Math.sin(windAngle);
        flagCloth(batch.builder("flag"), new float[] { x, ground + height - 0.5f, z }, new float[] { dirX, 0f, dirZ },
                new float[] { 0f, -1f, 0f }, flagW, flagH, x * 0.13f + z * 0.07f);
        batch.include(x, ground + height + 2f, z, flagW + 2f);
        recordCircle(x, z, 0.8f);
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
        float[] baseWorld = localToWorldPoint(house, base), tipWorld = localToWorldPoint(house, tip);
        MeshBuilder pole = batch.builder("flagpole");
        addRod(pole, baseWorld, tipWorld, 0.3f);
        // The flag hangs from the pole, its top edge along it and its face across the wall
        float[] along = normalise(sub(tipWorld, baseWorld));
        float flagW = length * 0.8f, flagH = flagW * FlagDesigner.HEIGHT / FlagDesigner.WIDTH;
        float[] start = add(baseWorld, scale3(along, length * 0.18f));
        float[] down = { 0f, -1f, 0f };
        flagCloth(batch.builder("flag"), start, along, down, flagW, flagH, house.x * 0.11f);
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

    private float[] localToWorldPoint(House house, float[] p) {
        float[] w = localToWorld(house.x, house.z, house.rotationY, p[0], p[2]);
        return new float[] { w[0], house.baseY + p[1], w[1] };
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
    private void wallPanel(MeshBuilder builder, House house, HouseWall wall, float s, float centreY,
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
            float[] world = localToWorld(house.x, house.z, house.rotationY, p[0], p[2]);
            corners[i] = new float[] { world[0], house.baseY + p[1], world[1] };
        }
        float[] inside = localToWorld(house.x, house.z, house.rotationY,
                (wall.bottomA[0] + wall.bottomB[0]) * 0.5f - wall.normal[0] * 5.0f,
                (wall.bottomA[2] + wall.bottomB[2]) * 0.5f - wall.normal[2] * 5.0f);
        builder.addConvexFace(new float[] { inside[0], house.baseY + centreY, inside[1] }, corners);
    }

    /** Adds a house-local point to a builder in world space, turning its normal with the house. */
    private int houseVertex(MeshBuilder builder, House house, float[] p, float[] n, float u, float v) {
        float radians = (float) Math.toRadians(house.rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        return builder.addVertex(
                house.x + cosine * p[0] + sine * p[2], house.baseY + p[1], house.z - sine * p[0] + cosine * p[2],
                cosine * n[0] + sine * n[2], n[1], -sine * n[0] + cosine * n[2], u, v);
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
     * Copies a unit mesh into a builder: scaled by (sx, sy, sz), shifted by the
     * local offset, then turned and placed with the house. With a texture repeat size,
     * texture coordinates are remapped to world units: along the eaves and up the slope
     * on sloping faces, and straight down onto flat ones.
     */
    private void bake(MeshBuilder builder, float[] vertices, int[] indices, House house,
                      float sx, float sy, float sz, float ox, float oy, float oz, float tileSize) {
        float radians = (float) Math.toRadians(house.rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
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
            int index = builder.addVertex(
                    house.x + cosine * lx + sine * lz, house.baseY + ly, house.z - sine * lx + cosine * lz,
                    cosine * nx + sine * nz, ny, -sine * nx + cosine * nz,
                    u, tv);
            if (first < 0) {
                first = index;
            }
        }
        for (int i = 0; i + 2 < indices.length; i += 3) {
            builder.addTriangle(first + indices[i], first + indices[i + 1], first + indices[i + 2]);
        }
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
        float urbanness = settlementManager.getUrbanness(house.x, house.z);
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
            for (House other : housesNear(house.x, house.z, maxPlotReach + FENCE_JOIN_GAP)) {
                if (other == house || !other.fenced || other.frontagePath != house.frontagePath) continue;
                float turn = Math.abs(((other.rotationY - house.rotationY) % 360f + 540f) % 360f - 180f);
                if (turn > 25f) continue;
                float[] local = worldToLocal(house, other.x, other.z);
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
            if (rand.nextFloat() > 0.3f || settlementManager.getUrbanness(house.x, house.z) > 0.2f) continue;
            // A paddock behind the garden, as wide as the plot or narrower
            float length = 15f + rand.nextFloat() * 35f;
            float inset = rand.nextFloat() * (house.plotMaxX - house.plotMinX) * 0.3f;
            float[] paddock = { house.plotMinX + (rand.nextBoolean() ? inset : 0f), house.plotMaxX - (rand.nextBoolean() ? inset : 0f),
                    house.plotMinZ - length, house.plotMinZ };
            if (isAreaFree(house, paddock)) house.paddock = paddock;
        }
        for (House house : houses) {
            float plotReach = Math.max(Math.max(-house.plotMinX, house.plotMaxX), Math.max(-house.plotMinZ, house.plotMaxZ));
            if (house.paddock != null) plotReach = Math.max(plotReach, -house.paddock[2]);
            maxPlotReach = Math.max(maxPlotReach, plotReach * 1.42f);
        }
    }

    /** True if a local rectangle of a house's land touches no road and no other plot. */
    private boolean isAreaFree(House house, float[] area) {
        for (int i = 0; i <= 4; i++) {
            for (int j = 0; j <= 4; j++) {
                float lx = area[0] + (area[1] - area[0]) * i / 4f;
                float lz = area[2] + (area[3] - area[2]) * j / 4f;
                float[] world = localToWorld(house.x, house.z, house.rotationY, lx, lz);
                if (isRoadLocation(world[0], world[1], 4f)) return false;
                if (TerrainMesh.getLayeredHeight(world[0], world[1], terrainNoise) <= seaLevel) return false;
                for (House other : housesNear(world[0], world[1], maxPlotReach)) {
                    if (other == house) continue;
                    float[] local = worldToLocal(other, world[0], world[1]);
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
        for (float[] point : front) {
            if (Math.abs(point[0]) < GATE_HALF_WIDTH) {
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
        float[] world = localToWorld(house.x, house.z, house.rotationY, localX, house.plotMaxZ);
        float bestDistance = Float.MAX_VALUE;
        float[] best = null;
        for (RoadSegment segment : segmentsNear(world[0], world[1], 60f)) {
            if (segment.pathIndex != house.frontagePath || segment.culDeSac) continue;
            float[] nearest = nearestOnSegment(world[0], world[1], segment);
            float dx = world[0] - nearest[0], dz = world[1] - nearest[1];
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance < bestDistance && distance > 1e-3f) {
                bestDistance = distance;
                float reach = segment.width * 0.5f + FRONT_FENCE_GAP;
                best = new float[] { nearest[0] + dx / distance * reach, nearest[1] + dz / distance * reach };
            }
        }
        if (best == null || bestDistance > 60f) return new float[] { localX, house.plotMaxZ };
        float[] local = worldToLocal(house, best[0], best[1]);
        // Stay in front of the house and not too far from the plot's own line
        float z = Math.max(house.depth * 0.5f + 4f, Math.min(house.plotMaxZ + 20f, local[1]));
        return new float[] { localX, z };
    }

    private static float[] nearestOnSegment(float x, float z, RoadSegment segment) {
        float ax = segment.start.x, az = segment.start.z;
        float bx = segment.end.x - ax, bz = segment.end.z - az;
        float lengthSquared = bx * bx + bz * bz;
        float t = lengthSquared < 1e-6f ? 0f : Math.max(0f, Math.min(1f, ((x - ax) * bx + (z - az) * bz) / lengthSquared));
        return new float[] { ax + bx * t, az + bz * t };
    }

    /** Builds fence along a polyline of local points. */
    private void fenceLine(NationBatch batch, MeshBuilder builder, House house, FenceStyle style, List<float[]> localPoints) {
        for (int i = 0; i + 1 < localPoints.size(); i++) {
            float[] a = localToWorld(house.x, house.z, house.rotationY, localPoints.get(i)[0], localPoints.get(i)[1]);
            float[] b = localToWorld(house.x, house.z, house.rotationY, localPoints.get(i + 1)[0], localPoints.get(i + 1)[1]);
            fenceRun(batch, builder, style, house.secure, a[0], a[1], b[0], b[1]);
            recordSegment(a[0], a[1], b[0], b[1], house.secure ? 0.8f : 0.4f, house.secure ? style.securityHeight : style.height);
        }
    }

    private void fenceRun(NationBatch batch, MeshBuilder builder, FenceStyle style, boolean secure,
                          float startX, float startZ, float endX, float endZ) {
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
            points[i] = new float[] { x, TerrainMesh.getLayeredHeight(x, z, terrainNoise), z };
            batch.include(x, points[i][1] + h, z, 2.0f);
        }

        for (int i = 0; i < pieces; i++) {
            float[] a = points[i];
            float[] b = points[i + 1];
            // A bending road may clip the corner of a garden; the fence stops short of it
            if (isRoadLocation((a[0] + b[0]) * 0.5f, (a[2] + b[2]) * 0.5f, 1.0f)) {
                continue;
            }
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
        if (posts && !isRoadLocation(last[0], last[2], 1.0f)) {
            addBox(builder, last[0], last[1] - 1.0f, last[2], dirX, dirZ, 0.6f, 0.6f, h + 1.5f);
        }
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

    /** A sign in the front garden beside the path, text facing the road. */
    private InfrastructureObject createHouseSign(House house, Random rand) {
        float lateral = (rand.nextBoolean() ? 1.0f : -1.0f) * (house.width * 0.3f + 8.0f);
        float forward = house.depth * 0.5f + Math.max(3.0f, (house.setback - FRONT_FENCE_GAP) * 0.5f);
        float[] signPos = localToWorld(house.x, house.z, house.rotationY, lateral, forward);
        if (isRoadLocation(signPos[0], signPos[1], 2.0f)) {
            return null;
        }
        float signY = TerrainMesh.getLayeredHeight(signPos[0], signPos[1], terrainNoise);
        if (signY <= seaLevel + 0.5f) {
            return null;
        }
        return createSign(new Vector3(signPos[0], signY, signPos[1]), house.nationId, house.rotationY, rand);
    }

    /** A sign beside the road, text facing across the carriageway. More likely the more urban the street. */
    private InfrastructureObject tryCreateRoadsideSign(RoadSegment segment, Random rand, List<float[]> existingSigns) {
        Double probability = nationSignProbabilities.get(segment.nationId);
        if (probability == null) {
            return null;
        }
        float midX = (segment.start.x + segment.end.x) * 0.5f;
        float midZ = (segment.start.z + segment.end.z) * 0.5f;
        double urbanBoost = 1.0 + URBAN_SIGN_BOOST * settlementManager.getUrbanness(midX, midZ);
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
        if (isRoadLocation(signX, signZ, 2.0f) || isPlotLocation(signX, signZ, 2.0f)) {
            return null;
        }
        for (float[] existing : existingSigns) {
            float dx = existing[0] - signX;
            float dz = existing[1] - signZ;
            if (dx * dx + dz * dz < SIGN_SPACING * SIGN_SPACING) {
                return null;
            }
        }
        float signY = TerrainMesh.getLayeredHeight(signX, signZ, terrainNoise);
        if (signY <= seaLevel + 0.5f) {
            return null;
        }
        return createSign(new Vector3(signX, signY, signZ), segment.nationId, facingRotation(-outwardX, -outwardZ), rand);
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

    private InfrastructureObject createSign(Vector3 position, int nationId, float rotationY, Random rand) {
        int maxGlyphs = mainListener != null ? mainListener.getNationAtlasSize(nationId) : 10;
        if (maxGlyphs <= 0) maxGlyphs = 1;
        int[] textString = randomText(rand, maxGlyphs, 80, 200);
        InfrastructureObject sign = new InfrastructureObject(InfrastructureObject.Type.SIGN, position, nationId, rotationY, textString);
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
        Shop shop = nearestShop(sign.position.x, sign.position.z, 2600f, nation);
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

    private Vector3 roadTangent(List<Vector3> points, int fromIndex, int toIndex) {
        Vector3 from = points.get(fromIndex);
        Vector3 to = points.get(toIndex);
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

    /**
     * Y rotation (degrees) that turns an object's local +Z, the side a sign's
     * text is printed on, to face the given world direction.
     */
    private static float facingRotation(float directionX, float directionZ) {
        return (float) Math.toDegrees(Math.atan2(directionX, directionZ));
    }

    /** Matches Matrix4Transform.rotateAroundY: local +X maps to (cos, -sin) and local +Z to (sin, cos). */
    private static float[] localToWorld(float originX, float originZ, float rotationY, float localX, float localZ) {
        float radians = (float) Math.toRadians(rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        return new float[] {
            originX + cosine * localX + sine * localZ,
            originZ - sine * localX + cosine * localZ
        };
    }

    private static float[] worldToLocal(House house, float worldX, float worldZ) {
        float radians = (float) Math.toRadians(house.rotationY);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        float dx = worldX - house.x;
        float dz = worldZ - house.z;
        return new float[] { cosine * dx - sine * dz, sine * dx + cosine * dz };
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
        RoadSegment copy = new RoadSegment(new Vector3(segment.start.x + dx, segment.start.y, segment.start.z),
                new Vector3(segment.end.x + dx, segment.end.y, segment.end.z), segment.startTangent, segment.endTangent,
                segment.nationId, segment.pathIndex, segment.roadClass, segment.startDistance, segment.length, segment.width, segment.culDeSac);
        copy.railed = segment.railed;
        return copy;
    }

    /** The same place a whole way round the planet, on the other side of the map's join, if this is near it. */
    private static float otherSide(float x) {
        return x > 0 ? x - Planet.width() : x + Planet.width();
    }

    private static boolean nearJoin(float x) {
        return Math.abs(x) > Planet.width() * 0.5f - RegionalGenerationManager.GENERATION_MARGIN - 1000f;
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
