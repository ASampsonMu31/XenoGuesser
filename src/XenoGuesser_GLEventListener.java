import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import com.jogamp.opengl.*;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.*;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;

public class XenoGuesser_GLEventListener implements GLEventListener {
    
    private Camera camera;
    private MyKeyboardInput keyboard;
    private double startTime;
    private double lastElapsedTime = 0;

    private TextureLibrary textures;
    private Map<String, Model> chunkCache;
    private Map<String, float[]> grassCache; 
    
private static class SpeciesConfig {
        String name;
        int leafTexNum;
        
        // Base procedural growth boundaries
        float baseBRate, varBRate;
        float baseSWidth, varSWidth;
        float baseWDecl, varWDecl;
        float baseSDist, varSDist;
        float baseBAngle, varBAngle;
        float leafScale; 
        
        // Dynamic phenotype profiles
        Vector3 healthyColor;
        Vector3 dyingColor;
        Vector3 trunkColor; // NEW: Controls the bark/wood color
        
        // Climate niche constraints
        float tempMean, tempStdDev;
        float patchNoiseScale;
        
        RegionalFactor tempFactor;
        RegionalFactor patchNoiseFactor;
        RegionalFactor abundanceFactor;

        float baseAbundance;

        public SpeciesConfig(String name, int leafTexNum, float leafScale,
                             float baseBRate, float varBRate, float baseSWidth, float varSWidth,
                             float baseWDecl, float varWDecl, float baseSDist, float varSDist,
                             float baseBAngle, float varBAngle, Vector3 healthyColor, Vector3 dyingColor, Vector3 trunkColor,
                             float tempMean, float tempStdDev, float patchNoiseScale, float baseAbundance) {
            this.name = name;
            this.leafTexNum = leafTexNum;
            this.leafScale = leafScale; 
            this.baseBRate = baseBRate; this.varBRate = varBRate;
            this.baseSWidth = baseSWidth; this.varSWidth = varSWidth;
            this.baseWDecl = baseWDecl; this.varWDecl = varWDecl;
            this.baseSDist = baseSDist; this.varSDist = varSDist;
            this.baseBAngle = baseBAngle; this.varBAngle = varBAngle;
            this.healthyColor = healthyColor;
            this.dyingColor = dyingColor;
            this.trunkColor = trunkColor; // NEW
            this.tempMean = tempMean;
            this.tempStdDev = tempStdDev;
            this.patchNoiseScale = patchNoiseScale;
            this.baseAbundance = baseAbundance;
        }
    }

    private SpeciesConfig[] speciesConfigs;
    private final int NUM_SPECIES = 8; // 4 Trees + 4 Shrubs
    private final int FLORA_VARIATIONS = 10;

        private Map<String, List<FloraInstance>> floraCache;
    // Bounding sphere {x, y, z, radius} per terrain chunk, for frustum culling
    private final Map<String, float[]> chunkBounds = new HashMap<>();
    private final Frustum frustum = new Frustum();
    private final List<Model> visibleChunks = new ArrayList<>();
    private final List<FloraInstance> visibleFlora = new ArrayList<>();
    // Tallest trees reach about this far from their base, before instance scaling
    private static final float FLORA_CULL_RADIUS = 140.0f;
    private Model[][][] floraBranchModelsLOD; // Matrix bounds: [species][lodIndex][variationIndex]
    private Model[][][] floraLeafModelsLOD;   
    
    // Shader fields
    private Shader leafShader;
    
    private static class FloraInstance {
                Vector3 pos;
        int speciesIndex;
        int modelIndex;
        float scale;
        float rotationY;
        // Cached on first draw: these never change for a placed tree
        Matrix4 modelMatrix;
        Vector3 leafDark;
        Vector3 leafLight;
        int lodIndex;

        
        public FloraInstance(Vector3 pos, int speciesIndex, int modelIndex, float scale, float rotationY) {
            this.pos = pos;
            this.speciesIndex = speciesIndex;
            this.modelIndex = modelIndex;
            this.scale = scale;
            this.rotationY = rotationY;
        }
    }
    
    private Light[] lights;
    private Vector3 ambientLight;
    private float nightProportion;
    private float timeOfDay;

    private float seaLevelHeight;
    private Model waterPlaneModel;
    private Shader waterShader;
    private Material waterMaterial;
        // Bugs and, in time, other creatures; their ranges follow the land, not nation borders
        private OrganismManager organismManager;
    // The people who built the towns: generated anew each game, dressed by nation
    private Inhabitants inhabitants;
    // The player's own spacesuited body, and whether they are swimming
    private final PlayerBody playerBody = new PlayerBody();
    private boolean swimming;
    private float waterSurfaceHere;
    // Eyes this far above the water when swimming; deeper than this and the player swims
    private static final float SWIM_EYE_HEIGHT = 2.4f;
    // The scene's near plane: far enough out for depth precision on land, close in when the
    // eyes are just above the water so the surface around the swimmer isn't cut away
    private static final float NEAR_PLANE = 10.0f, SWIMMING_NEAR_PLANE = 1.5f, FAR_PLANE = 3000.0f;
    private float currentNearPlane = NEAR_PLANE;
    private int waveMapTexture;

    private Model skyModel;

    private Shader terrainShader;
    private Renderer terrainRenderer;
    private Material terrainMaterial;
    private Matrix4 globalModelMatrix;

    private Shader solidShader;
    private Shader signboardShader;

    private Shader depthPrePassShader;

    private final float PHYSICAL_CHUNK_SIZE; 
    private final int VIEW_DISTANCE = 22; 

    private final float TOTAL_REGION_WIDTH;

    private PerlinNoise worldNoise;
    private long worldSeed; 

    private float playerEyeHeight = 20.0f;
    
    private int lastChunkX = Integer.MAX_VALUE;
    private int lastChunkZ = Integer.MAX_VALUE;

    private MapPanel minimap;
    private GameHUD gameHUD; 

    // --- Seasonal Simulation Fields ---
    private float planetAxialTiltDegrees;
    private float currentSeasonalTiltDegrees;

    private int[] depthFBO = new int[1];
    private int[] depthTexture = new int[1];
    private int currentWidth = 1024;  
    private int currentHeight = 768;
    
    private static final double FPS_WINDOW_SECONDS = 0.5;
    private int fpsFrameCount = 0;
    private double fpsWindowStart = 0;

    // --- Fully GPU-Driven Instanced Grass Rendering Fields ---
    private Shader grassShader;
    private int grassVAO = 0;
    private int grassVBO = 0;
    private int grassChunkCoordVBO = 0; 
    private int totalGrassInstances = 0;
    
    private final int GRASS_VIEW_DISTANCE = 21;
        private final int MAX_GRASS_LIMIT = 1200;
    private final float GRASS_BASE_ABUNDANCE;

    // --- Optimized Zero-Allocation VRAM Streaming Fields ---
    private java.nio.FloatBuffer persistentGrassBuffer;
    private int currentGrassGPUCapacityFloats = 0;

    private CompassHUD compassHUD;

    // --- ECOSYSTEM SPAWNING MANAGER DATA ---
    private RegionalGenerationManager regionalManager;

    private RegionalFactor grassTemperateFactor;
    private RegionalFactor leafTemperateFactor;
    private RegionalFactor grassMoistureFactor;
    private RegionalFactor grassPatchNoiseFactor;
    private RegionalFactor grassHeightNoiseFactor;
    private RegionalFactor grassColourNoiseFactor;
    private RegionalFactor leafColourNoiseFactor;

        // Regional soil colour: where the ground drifts toward each of the palette's soil variants
    private RegionalFactor soilVariantAFactor;
    private RegionalFactor soilVariantBFactor;
    private static final int SOIL_REGION_MAP_SIZE = 256;

    private RegionalFactor grassAbundanceFactor;
    private RegionalFactor grassHeightFactor;
    private RegionalFactor grassColourFactor;
    private RegionalFactor leafColourFactor;

    private final boolean IS_DEBUG_MODE_ACTIVE;
    private FactorName currentDebugFactor;
    private boolean lastKeyboardG = false;
    private boolean lastKeyboardH = false;


    private boolean isToTeleport = false;
    private float teleportX = 0f;
    private float teleportZ = 0f;

    private GL3 gl;

    private Vector3 healthyColour;
    private Vector3 dyingColour;

    private NationGenerationManager nationManager;
    private int totalNationsCount;

    private InfrastructureManager infraManager;
    private SettlementManager settlementManager;
    private Map<String, List<InfrastructureObject>> infraCache;

    // Bird's-eye minimap layers, rendered on a background thread at startup
    private static final int BIRDS_EYE_MAP_RESOLUTION = 750;
    private volatile BufferedImage roadNetworkMap;
    

    private volatile BufferedImage buildingMap;
    // Three times the resolution, swapped in when the player zooms the minimap
    private volatile BufferedImage roadNetworkMapDetail;
    

    private volatile BufferedImage buildingMapDetail;

    // Cities thin out trees and draw fewer distant details to pay for their extra buildings
    private static final float URBAN_FLORA_REDUCTION = 0.75f;
    private static final float SIGN_DRAW_DISTANCE = 1600.0f;
    private Map<Integer, Model> signModelsByNation;
    private Model postModel;
    private Map<Integer, Model> postModelsByNation;

    // Procedurally generated textures and staged GL loading
    private WorldArtGenerator worldArt;
    private LoadingProgress loading;
    private Runnable onWorldReady;
    private int loadingStep = 0;
    private boolean worldReady = false;
    private int framesRenderedSinceReady = 0;
    // While loading, grass seeding may keep going for this long per frame; in play it seeds one far chunk per frame
    private static final long LOADING_GRASS_BUDGET_NANOS = 120_000_000L;
    private long grassBudgetNanos = 0;
    private static final float LEAF_DARK_SCALE = 0.65f;
    private static final float LEAF_LIGHT_SCALE = 1.3f;

    private Map<Integer, Texture> nationAtlases;
    private Map<Integer, Integer> nationAtlasSizes;
    private Map<Integer, Integer> nationDirections;

    public enum FactorName {
      GRASS_ABUNDANCE,
      GRASS_HEIGHT,
      GRASS_COLOUR,
      LEAF_COLOUR,
      GRASS_TEMPERATURE_PREFERENCE,
      LEAF_TEMPERATURE_PREFERENCE,
      MOISTURE,
      GRASS_PATCH_NOISE,
      GRASS_HEIGHT_NOISE,
      GRASS_COLOUR_NOISE,
            LEAF_COLOUR_NOISE,
      SOIL_COLOUR_VARIANT_A,
      SOIL_COLOUR_VARIANT_B,
      TREE_1_ABUNDANCE,
      TREE_2_ABUNDANCE,
      TREE_3_ABUNDANCE,
      TREE_4_ABUNDANCE,
      SHRUB_1_ABUNDANCE,
      SHRUB_2_ABUNDANCE,
      SHRUB_3_ABUNDANCE,
      SHRUB_4_ABUNDANCE,
            ORGANISM_1_HABITAT,
      ORGANISM_2_HABITAT,
      ORGANISM_3_HABITAT,
      ORGANISM_4_HABITAT,
      ORGANISM_5_HABITAT,
      ORGANISM_6_HABITAT,
      ORGANISM_7_HABITAT,
      ORGANISM_8_HABITAT,
      NATION_TERRITORIES,
      ROAD_NETWORK,
      
      BUILDINGS
    }

    public XenoGuesser_GLEventListener(
            Camera camera,
            MyKeyboardInput keyboard,
            PerlinNoise sharedNoise,
            float sharedSeaLevel,
            long sharedSeed,
            float totalRegionWidth,
            float physicalChunkSize,
            boolean isDebugModeActive) {
                
        this.camera = camera;
        this.keyboard = keyboard;
        this.worldNoise = sharedNoise;
        this.seaLevelHeight = sharedSeaLevel;
        this.worldSeed = sharedSeed; 
        this.PHYSICAL_CHUNK_SIZE = physicalChunkSize;
        this.TOTAL_REGION_WIDTH = totalRegionWidth;
        this.IS_DEBUG_MODE_ACTIVE = isDebugModeActive;
        this.currentDebugFactor = FactorName.GRASS_ABUNDANCE;
        
        this.camera.setPosition(new Vector3(0f, 5f, 15f));
        this.camera.setTarget(new Vector3(0f, 0f, 0f));

        java.util.Random seedRand = new java.util.Random(worldSeed);
        this.planetAxialTiltDegrees = 20.0f + seedRand.nextFloat() * 6.0f;
        
        this.regionalManager = new RegionalGenerationManager(worldSeed, TOTAL_REGION_WIDTH, seaLevelHeight);
        this.regionalManager.precalculateWaterDistanceField(TOTAL_REGION_WIDTH, this.worldNoise, PHYSICAL_CHUNK_SIZE);

        float GRASS_TEMP_MEAN = 0.4f;
        float GRASS_TEMP_STD_DEV = 0.5f;
        int GRASS_WATER_MEAN = 0;
        float GRASS_WATER_STD_DEV = 80.0f;
        float GRASS_DENSITY_SCALE = 2e-5f;
        float GRASS_VARIATION_SCALE = 1e-5f;
                this.GRASS_BASE_ABUNDANCE = 1050f;

        this.grassTemperateFactor = this.regionalManager.createTemperaturePreference(GRASS_TEMP_MEAN, GRASS_TEMP_STD_DEV);
        this.grassMoistureFactor = this.regionalManager.createWaterPreference(GRASS_WATER_MEAN, GRASS_WATER_STD_DEV);
        this.grassPatchNoiseFactor = this.regionalManager.createNoiseMap(GRASS_DENSITY_SCALE);
        this.grassHeightNoiseFactor = this.regionalManager.createNoiseMap(GRASS_VARIATION_SCALE);
        this.grassColourNoiseFactor = this.regionalManager.createNoiseMap(GRASS_VARIATION_SCALE);

        float LEAF_VARIATION_SCALE = 1e-5f;
        float LEAF_TEMP_MEAN = 0.2f;
        float LEAF_TEMP_STD_DEV = 0.5f;

        this.leafColourNoiseFactor = this.regionalManager.createNoiseMap(LEAF_VARIATION_SCALE);
        this.leafTemperateFactor = this.regionalManager.createTemperaturePreference(LEAF_TEMP_MEAN, LEAF_TEMP_STD_DEV);

        this.grassAbundanceFactor = new RegionalFactor.Builder()
            .setWeight(3f)
            .setPowerCurve(5.0f)
            .addFactor(this.grassTemperateFactor, 0.4f)
            .addFactor(this.grassMoistureFactor, 0.4f)
            .addFactor(this.grassPatchNoiseFactor, 0.2f)
            .build();

        this.grassHeightFactor = new RegionalFactor.Builder()
            .setWeight(1.5f)
            .setPowerCurve(3.0f)
            .addFactor(this.grassTemperateFactor, 0.2f)
            .addFactor(this.grassMoistureFactor, 0.2f)
            .addFactor(this.grassHeightNoiseFactor, 0.6f)
            .build();

        this.grassColourFactor = new RegionalFactor.Builder()
            .setWeight(1.5f)
            .setPowerCurve(3.0f)
            .addFactor(this.grassTemperateFactor, 0.35f)
            .addFactor(this.grassMoistureFactor, 0.35f)
            .addFactor(this.grassColourNoiseFactor, 0.3f)
            .build();

        this.leafColourFactor = new RegionalFactor.Builder()
            .setWeight(1.5f)
            .setPowerCurve(3.0f)
            .addFactor(this.leafTemperateFactor, 0.35f)
            .addFactor(this.grassMoistureFactor, 0.35f)
            .addFactor(this.leafColourNoiseFactor, 0.3f)
            .build();

        // --- SEEDED DYNAMIC SPECIES GENERATION PIPELINE ---
        speciesConfigs = new SpeciesConfig[NUM_SPECIES];
        
        // Create a Random instance tied strictly to this world's seed
        // This guarantees variety per game, but perfect uniformity on the same seed
        Random speciesSeeder = new Random(worldSeed);

        for (int s = 0; s < NUM_SPECIES; s++) {
            boolean isTree = (s < 4); // First 4 are Trees, last 4 are Shrubs
            String name = isTree ? ("Procedural Tree Type " + (s + 1)) : ("Procedural Shrub Type " + (s - 3));

            // Every species gets its own generated leaf texture
            int leafTexNum = s;
            
            // Randomize organic profiles depending on structural class (Tree vs Shrub)
            float leafScale, baseBRate, varBRate, baseSWidth, varSWidth, baseWDecl, varWDecl, baseSDist, varSDist, baseBAngle, varBAngle;
            float tempMean, tempStdDev, patchNoiseScale, baseAbundance;
            Vector3 healthyColor, dyingColor;

            if (isTree) {
                // Trees: Massive leaves, low branching density, thick trunks, tall structural heights
                leafScale   = 2.2f + speciesSeeder.nextFloat() * 1.5f; // Scale ranges from 2.2 to 3.7
                baseBRate   = 0.03f + speciesSeeder.nextFloat() * 0.06f;
                varBRate    = 0.01f + speciesSeeder.nextFloat() * 0.02f;
                baseSWidth  = 2.0f + speciesSeeder.nextFloat() * 2.5f;
                varSWidth   = 0.3f + speciesSeeder.nextFloat() * 0.4f;
                baseWDecl   = 0.01f + speciesSeeder.nextFloat() * 0.04f;
                varWDecl    = 0.005f + speciesSeeder.nextFloat() * 0.01f;
                baseSDist   = 80.0f + speciesSeeder.nextFloat() * 90.0f; // Tall
                varSDist    = 10.0f + speciesSeeder.nextFloat() * 15.0f;
                baseBAngle  = 15.0f + speciesSeeder.nextFloat() * 25.0f; // Narrow upward growth
                varBAngle   = 5.0f + speciesSeeder.nextFloat() * 10.0f;
                
                // Ecological layout: Random temperature preferences across the map
                tempMean    = 0.1f + speciesSeeder.nextFloat() * 0.6f; 
                tempStdDev  = 0.2f + speciesSeeder.nextFloat() * 0.2f;
                baseAbundance = 0.02f + speciesSeeder.nextFloat() * 0.08f;


            } else {
                // Shrubs: Short, thin start, dense brush branching rates, ultra wide extension angles
                leafScale   = 0.7f + speciesSeeder.nextFloat() * 0.4f; // Shrub leaf sizes (0.7 to 1.1)
                baseBRate   = 0.2f + speciesSeeder.nextFloat() * 0.15f;
                varBRate    = 0.05f + speciesSeeder.nextFloat() * 0.08f;
                baseSWidth  = 0.3f + speciesSeeder.nextFloat() * 0.4f;
                varSWidth   = 0.1f + speciesSeeder.nextFloat() * 0.2f;
                baseWDecl   = 0.15f + speciesSeeder.nextFloat() * 0.2f;
                varWDecl    = 0.05f + speciesSeeder.nextFloat() * 0.1f;
                baseSDist   = 5.0f + speciesSeeder.nextFloat() * 12.0f; // Short
                varSDist    = 2.0f + speciesSeeder.nextFloat() * 6.0f;
                baseBAngle  = 25.0f + speciesSeeder.nextFloat() * 25.0f; // Wide bushy dispersion
                varBAngle   = 10.0f + speciesSeeder.nextFloat() * 20.0f;
                
                // Ecological layout: Prefer warmer/different niches than trees
                tempMean    = 0.4f + speciesSeeder.nextFloat() * 0.5f;
                tempStdDev  = 0.15f + speciesSeeder.nextFloat() * 0.2f;
                baseAbundance = 0.04f + speciesSeeder.nextFloat() * 0.16f;
            }

            // --- GENERATE PURE GREYSCALE WOOD LUMINANCE (0.15 = Charcoal, 0.95 = Ghost White) ---
            float trunkLuminance = 0.15f + speciesSeeder.nextFloat() * 0.80f;
            
            // Set all channels equal to create a clean greyscale intensity factor
            Vector3 trunkColor = new Vector3(trunkLuminance, trunkLuminance, trunkLuminance);

            patchNoiseScale = 1e-5f;

            // --- 1. GENERATE ANY BRIGHT HEALTHY COLOR (Per-Species) ---
            float rawR = speciesSeeder.nextFloat();
            float rawG = speciesSeeder.nextFloat();
            float rawB = speciesSeeder.nextFloat();

            // Find the strongest channel to scale the color up and guarantee brightness/vibrancy
            float maxChannel = Math.max(rawR, Math.max(rawG, rawB));
            if (maxChannel == 0.0f) { // Edge case fallback to prevent division by zero
                rawR = 1.0f; rawG = 1.0f; rawB = 1.0f; 
                maxChannel = 1.0f;
            }

            // Normalize so the brightest channel hits full capacity (1.0f), making it pop
            float brightR = rawR / maxChannel;
            float brightG = rawG / maxChannel;
            float brightB = rawB / maxChannel;

            // Add a slight minimum floor so it never goes pure black or pure neon white unexpectedly
            healthyColor = new Vector3(
                Math.max(0.15f, brightR),
                Math.max(0.15f, brightG),
                Math.max(0.15f, brightB)
            );

            // --- 2. GENERATE A BROWNER, DEGRADED VERSION OF THAT EXACT COLOR ---
            // To simulate organic decay/browning, we mix the base color with a dark brown/grey tint.
            // This preserves the unique base species hue while pulling it into a muddy, dead state.
            float brownFactor = 0.35f; // Controls how much of the original hue passes through
            float deadR = (healthyColor.x * brownFactor) + 0.15f; 
            float deadG = (healthyColor.y * brownFactor) + 0.10f; // Lower green reduces vitality
            float deadB = (healthyColor.z * brownFactor) + 0.04f; // Lower blue pushes it toward warm mud/earth tones

            dyingColor = new Vector3(
                Math.min(deadR, 1.0f),
                Math.min(deadG, 1.0f),
                Math.min(deadB, 1.0f)
            );

            // Construct the runtime configuration profile
            speciesConfigs[s] = new SpeciesConfig(
                name, leafTexNum, leafScale,
                baseBRate, varBRate, baseSWidth, varSWidth,
                baseWDecl, varWDecl, baseSDist, varSDist,
                baseBAngle, varBAngle, healthyColor, dyingColor, trunkColor,
                tempMean, tempStdDev, patchNoiseScale, baseAbundance
            );

            // Compile Ecosystem Pipeline Rules for this dynamic configuration
            SpeciesConfig sc = speciesConfigs[s];
            sc.tempFactor = this.regionalManager.createTemperaturePreference(sc.tempMean, sc.tempStdDev);
            sc.patchNoiseFactor = this.regionalManager.createNoiseMap(sc.patchNoiseScale);
            
            // Blended factor with prominent 50% unique noise allocation for explicit segregation
            sc.abundanceFactor = new RegionalFactor.Builder()
                .setWeight(6.0f)        // Drop this to 1.0f so it NEVER artificially blows up low scores
                .setPowerCurve(6.0f)    // Crank the power curve up to 5.0 or 6.0 to make it brutal
                .addFactor(sc.tempFactor, 0.30f)
                .addFactor(this.grassMoistureFactor, 0.20f)
                .addFactor(sc.patchNoiseFactor, 0.50f)
                .build();
        }

        // Dynamically determine the total number of writing systems/nations between 6 and 14 configurations
        Random rand = new Random(this.worldSeed);
        this.totalNationsCount = 6 + rand.nextInt(9); 

        // Map resolution matched safely to 512x512 grids to match MapPanel specs
        // Map resolution matched safely to 384 grids for fast CA simulation
        this.nationManager = new NationGenerationManager(this.worldSeed, this.totalNationsCount, 384, seaLevelHeight, worldNoise);

        // Run the spreading calculation using your environment configuration data parameters
        // Assuming 'terrainAbundanceFactor' or standard height maps are accessible inside your initialization path
        this.nationManager.generateTerritories(this.worldSeed, TOTAL_REGION_WIDTH);

        // Settlements decide where cities grow; the road network and buildings follow from them
        this.settlementManager = new SettlementManager(worldSeed, TOTAL_REGION_WIDTH, PHYSICAL_CHUNK_SIZE,
                                seaLevelHeight, worldNoise, regionalManager, nationManager);
        this.infraManager = new InfrastructureManager(this.worldSeed, this.totalNationsCount, this.nationManager,
                this.settlementManager, this);
        this.inhabitants = new Inhabitants(worldSeed, totalNationsCount, infraManager, TOTAL_REGION_WIDTH, worldNoise);
        System.out.println("[INHABITANTS] " + inhabitants.describe());
                this.infraManager.prepareRoadNetwork(PHYSICAL_CHUNK_SIZE, TOTAL_REGION_WIDTH, seaLevelHeight, worldNoise);
        this.soilVariantAFactor = this.regionalManager.createNoiseMap(1.4e-5f);
                this.soilVariantBFactor = this.regionalManager.createNoiseMap(2.1e-5f);
        this.organismManager = new OrganismManager(worldSeed, PHYSICAL_CHUNK_SIZE, seaLevelHeight, worldNoise, regionalManager);
        this.organismManager.setUrbanness((x, z) -> infraManager.getUrbanness(x, z));
        startBirdsEyeMapRendering();
    }

    private void startBirdsEyeMapRendering() {
        Thread mapThread = new Thread(() -> {
            BirdsEyeMaps maps = new BirdsEyeMaps(BIRDS_EYE_MAP_RESOLUTION, TOTAL_REGION_WIDTH, seaLevelHeight, worldNoise);
            long startTime = System.currentTimeMillis();

            BufferedImage[] baseMap = maps.renderBaseMap();
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) {
                    minimap.setBaseMapDetail(baseMap[1]);
                }
            });

            

            BufferedImage[] roads = maps.renderRoadMap(infraManager);
            roadNetworkMapDetail = roads[1];
            roadNetworkMap = roads[0];
            onBirdsEyeMapReady(FactorName.ROAD_NETWORK);

            BufferedImage[] buildings = maps.renderBuildingMap(infraManager);
            buildingMapDetail = buildings[1];
            buildingMap = buildings[0];
            onBirdsEyeMapReady(FactorName.BUILDINGS);

            System.out.printf("[MINIMAP] Bird's-eye maps ready in %d ms%n", System.currentTimeMillis() - startTime);
        }, "birds-eye-map-renderer");
        mapThread.setDaemon(true);
        mapThread.setPriority(Thread.MIN_PRIORITY);
        mapThread.start();
    }

    private void onBirdsEyeMapReady(FactorName factor) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (minimap != null && currentDebugFactor == factor) {
                assignHeatmapToMinimap(factor);
            }
        });
    }

    public void setGameHUD(GameHUD gameHUD) {
        this.gameHUD = gameHUD;
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        this.gl = gl;
        
        float[] zenith = worldArt.palette().skyZenith;
        gl.glClearColor(zenith[0], zenith[1], zenith[2], 1.0f);
        gl.glClearDepth(1.0f);

        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(GL.GL_LESS);
        gl.glFrontFace(GL.GL_CCW);
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glCullFace(GL.GL_BACK);
        // The heavy set-up runs in steps from display(), so the loading screen keeps animating
    }

    /**
     * Advances GL-side loading by one step per display() call: uploads and shaders, one
     * flora species at a time, then the first terrain around the spawn point, then a first
     * real frame. Returns true once the game is ready to render normally.
     */
    private boolean advanceLoading() {
        if (loadingStep == 0) {
            loading.begin(LoadingProgress.Stage.GPU_UPLOAD);
            initialiseCore();
            loadingStep++;
            loading.begin(LoadingProgress.Stage.FLORA);
            return false;
        }
        int floraSteps = NUM_SPECIES * FLORA_VARIATIONS;
        int floraStep = loadingStep - 1;
        if (floraStep < floraSteps) {
            initialiseFloraVariation(floraStep / FLORA_VARIATIONS, floraStep % FLORA_VARIATIONS);
            loading.report((floraStep + 1) / (float) floraSteps);
            loadingStep++;
            if (floraStep + 1 == floraSteps) loading.begin(LoadingProgress.Stage.TERRAIN);
            return false;
        }
        if (loadingStep == floraSteps + 1) {
            finishInitialise();
            loadingStep++;
            return false;
        }
        // Distant grass is seeded within a time budget per frame so the loading screen keeps moving
        int grassChunksTotal = (GRASS_VIEW_DISTANCE * 2 + 1) * (GRASS_VIEW_DISTANCE * 2 + 1);
        if (grassCache.size() < grassChunksTotal) {
            grassBudgetNanos = LOADING_GRASS_BUDGET_NANOS;
            updateVisibleChunks(false);
            grassBudgetNanos = 0;
            loading.report(grassCache.size() / (float) grassChunksTotal);
            return false;
        }
        loading.begin(LoadingProgress.Stage.FIRST_FRAME);
        startTime = getSeconds();
        lastElapsedTime = 0;
        return true;
    }
    
    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        GL3 gl = drawable.getGL().getGL3();
        
        if (height <= 0) height = 1;
        if (width <= 0) width = 1;
        
        this.currentWidth = width;
        this.currentHeight = height;
        gl.glViewport(0, 0, width, height);
        
                applyProjection();

        createDepthFramebuffer(gl, width, height);
    }

        private void applyProjection() {
        float aspect = (float) currentWidth / (float) Math.max(1, currentHeight);
        camera.setPerspectiveMatrix(Matrix4Transform.perspective(45, aspect, currentNearPlane, FAR_PLANE));
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        
        if (!worldReady) {
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            if (!advanceLoading()) return;
            worldReady = true;
        }

        if (minimap != null && minimap.isFullScreenRevealMode()) {
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            return;
        }

        render();

        // Hand over from the loading screen only once a real frame of the round exists
        if (!loading.isFinished() && ++framesRenderedSinceReady >= 2) {
            if (IS_DEBUG_MODE_ACTIVE) saveFrame(gl, "first_frame.png");
            loading.finish();
            if (onWorldReady != null) javax.swing.SwingUtilities.invokeLater(onWorldReady);
        } else if (IS_DEBUG_MODE_ACTIVE && loading.isFinished() && ++framesRenderedSinceReady == 150) {
            // A second capture a moment later shows what has moved
            saveFrame(gl, "later_frame.png");
        }
    }

    /** Debug aid: writes the current frame next to this run's generated textures. */
    private void saveFrame(GL3 gl, String fileName) {
        int w = currentWidth, h = currentHeight;
        java.nio.ByteBuffer pixels = com.jogamp.common.nio.Buffers.newDirectByteBuffer(w * h * 4);
        gl.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels);
        Thread writer = new Thread(() -> {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    int rgb = ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255);
                    img.setRGB(x, h - 1 - y, rgb);
                }
            }
            try {
                ImageIO.write(img, "png", new File(WorldArtGenerator.OUTPUT_DIR, fileName));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "first-frame-writer");
        writer.setDaemon(true);
        writer.start();
    }

    public void setWorldArt(WorldArtGenerator worldArt) {
        this.worldArt = worldArt;
    }

    public void setLoading(LoadingProgress loading, Runnable onWorldReady) {
        this.loading = loading;
        this.onWorldReady = onWorldReady;
    }

        /**
     * A whole-region map of how far the soil's colour departs from the base soil texture,
     * stored as a per-channel ratio (halved to fit in [0, 1]). The terrain shader multiplies
     * the soil texture by it, so the ground shifts gradually between the palette's base soil
     * and its two regional variants over tens of kilometres.
     */
    public java.awt.image.BufferedImage buildSoilRegionMap(WorldPalette palette) {
        int n = SOIL_REGION_MAP_SIZE;
        java.awt.image.BufferedImage map = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_RGB);
                float[] base = palette.soilBase, a = palette.soilRegionalA, b = palette.soilRegionalB;
        float half = TOTAL_REGION_WIDTH / 2.0f;
        float[] fa = new float[n * n], fb = new float[n * n];
        for (int py = 0; py < n; py++) {
            for (int px = 0; px < n; px++) {
                float worldX = (px + 0.5f) / n * TOTAL_REGION_WIDTH - half;
                float worldZ = (py + 0.5f) / n * TOTAL_REGION_WIDTH - half;
                int cx = (int) Math.floor(worldX / PHYSICAL_CHUNK_SIZE), cz = (int) Math.floor(worldZ / PHYSICAL_CHUNK_SIZE);
                fa[py * n + px] = soilVariantAFactor.evaluate(cx, cz, worldX, worldZ);
                fb[py * n + px] = soilVariantBFactor.evaluate(cx, cz, worldX, worldZ);
            }
        }
        // Noise values bunch around the middle, so thresholds come from their actual spread:
        // each variant takes over roughly the top third of its factor, blending in gradually
        float[] rangeA = percentiles(fa, 0.55f, 0.72f), rangeB = percentiles(fb, 0.55f, 0.72f);
        for (int py = 0; py < n; py++) {
            for (int px = 0; px < n; px++) {
                float wa = ProceduralTextures.smoothstep(rangeA[0], rangeA[1], fa[py * n + px]);
                float wb = ProceduralTextures.smoothstep(rangeB[0], rangeB[1], fb[py * n + px]);
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    float colour = base[c] + (a[c] - base[c]) * wa;
                    colour += (b[c] - colour) * wb;
                    float ratio = Math.min(2.0f, colour / Math.max(0.02f, base[c]));
                    rgb = (rgb << 8) | Math.round(ratio * 0.5f * 255f);
                }
                map.setRGB(px, py, rgb);
            }
        }
        return map;
    }

        /** Wave size over every body of water; see RegionalGenerationManager.buildWaveMap. */
        public void buildWaveMap() {
        regionalManager.buildWaveMap(PHYSICAL_CHUNK_SIZE);
    }

    /** Uploads the wave map: wave size and distance to shore, as a two-channel float texture. */
    private int createWaveMapTexture() {
        int n = regionalManager.waveMapResolution();
        int[] id = new int[1];
        gl.glGenTextures(1, id, 0);
        gl.glBindTexture(GL3.GL_TEXTURE_2D, id[0]);
        gl.glTexImage2D(GL3.GL_TEXTURE_2D, 0, GL3.GL_RG32F, n, n, 0, GL3.GL_RG, GL3.GL_FLOAT,
                com.jogamp.common.nio.Buffers.newDirectFloatBuffer(regionalManager.waveMapTexels()));
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
        gl.glBindTexture(GL3.GL_TEXTURE_2D, 0);
        return id[0];
    }

    /** Every nation's architecture texture generators, for WorldArtGenerator. */
    public Map<String, java.util.function.Supplier<BufferedImage>> getNationTextureJobs() {
        return infraManager.textureJobs();
    }

        private static float[] percentiles(float[] values, float low, float high) {
        float[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int last = sorted.length - 1;
        return new float[] { sorted[(int) (low * last)], sorted[(int) (high * last)] };
    }

    public int getSpeciesCount() {
        return NUM_SPECIES;
    }

    // Leaf textures are greyscale; the leaf shader maps black to this dark colour and white
    // to this light colour, continuously, for each species' regional colour
    private static Vector3 leafDarkColour(Vector3 regional) {
        return new Vector3(regional.x * LEAF_DARK_SCALE, regional.y * LEAF_DARK_SCALE, regional.z * LEAF_DARK_SCALE);
    }

    private static Vector3 leafLightColour(Vector3 regional) {
        return new Vector3(Math.min(regional.x * LEAF_LIGHT_SCALE, 1.0f),
                Math.min(regional.y * LEAF_LIGHT_SCALE, 1.0f),
                Math.min(regional.z * LEAF_LIGHT_SCALE, 1.0f));
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
                if (organismManager != null) organismManager.dispose(gl);
        if (inhabitants != null) inhabitants.dispose(gl);
        playerBody.dispose(gl);
        for (Model model : chunkCache.values()) {
            if (model.mesh != null) model.mesh.dispose(gl);
        }
        
        if (floraBranchModelsLOD != null) {
            for (int s = 0; s < NUM_SPECIES; s++) {
                for (int lod = 0; lod < 3; lod++) {
                    for (int i = 0; i < FLORA_VARIATIONS; i++) {
                        if (floraBranchModelsLOD[s][lod][i] != null && floraBranchModelsLOD[s][lod][i].mesh != null) {
                            floraBranchModelsLOD[s][lod][i].mesh.dispose(gl);
                        }
                        if (floraLeafModelsLOD[s][lod][i] != null && floraLeafModelsLOD[s][lod][i].mesh != null) {
                            floraLeafModelsLOD[s][lod][i].mesh.dispose(gl);
                        }
                    }
                }
            }
        }

        if (grassVAO != 0) {
            gl.glDeleteVertexArrays(1, new int[]{grassVAO}, 0);
            gl.glDeleteBuffers(2, new int[]{grassVBO, grassChunkCoordVBO}, 0);
        }
        
        gl.glDeleteFramebuffers(1, depthFBO, 0);
        gl.glDeleteTextures(1, depthTexture, 0);
    }

    private void createDepthFramebuffer(GL3 gl, int width, int height) {
        if (depthFBO[0] != 0) {
            gl.glDeleteFramebuffers(1, depthFBO, 0);
            gl.glDeleteTextures(1, depthTexture, 0);
        }

        gl.glGenFramebuffers(1, depthFBO, 0);
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, depthFBO[0]);

        gl.glGenTextures(1, depthTexture, 0);
        gl.glBindTexture(GL3.GL_TEXTURE_2D, depthTexture[0]);
        gl.glTexImage2D(GL3.GL_TEXTURE_2D, 0, GL3.GL_DEPTH_COMPONENT32F, width, height, 0, GL3.GL_DEPTH_COMPONENT, GL3.GL_FLOAT, null);
        
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_NEAREST);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_NEAREST);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);

        gl.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, GL3.GL_TEXTURE_2D, depthTexture[0], 0);
        
        gl.glDrawBuffer(GL3.GL_NONE);
        gl.glReadBuffer(GL3.GL_NONE);

        if (gl.glCheckFramebufferStatus(GL3.GL_FRAMEBUFFER) != GL3.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("Critical Error: Depth Framebuffer Configuration Failed.");
        }
        
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
    }

    private Vector3 getSunPosition() {
        float sunDistance = 2350.0f; 
        
        float progress = timeOfDay - (float)Math.floor(timeOfDay);
        
        float minAngleRad = (float)Math.toRadians(10.0);
        float maxAngleRad = (float)Math.toRadians(170.0);
        float currentAngleRad = minAngleRad + progress * (maxAngleRad - minAngleRad);
        
        Vector3 cameraPosition = camera.getPosition();
        
        float localX = sunDistance * (float)Math.cos(currentAngleRad);
        float localY = sunDistance * (float)Math.sin(currentAngleRad); 
        float localZ = 0.0f;
        
        float maxMapEdgeZ = TOTAL_REGION_WIDTH / 2.0f;
        
        float latitudeFactor = cameraPosition.z / maxMapEdgeZ;
        if (latitudeFactor > 1.0f) latitudeFactor = 1.0f;
        if (latitudeFactor < -1.0f) latitudeFactor = -1.0f;
        
        float maxTiltRadians = (float)Math.toRadians(35.0);
        float latitudeAngle = -latitudeFactor * maxTiltRadians; 
        
        float seasonalTiltRadians = (float)Math.toRadians(currentSeasonalTiltDegrees);
        float tiltAngle = latitudeAngle + seasonalTiltRadians;
        
        float cosTilt = (float)Math.cos(tiltAngle);
        float sinTilt = (float)Math.sin(tiltAngle);
        
        float worldX = cameraPosition.x + localX;
        float worldY = cameraPosition.y + (localY * cosTilt - localZ * sinTilt);
        float worldZ = cameraPosition.z + (localY * sinTilt + localZ * cosTilt);
        
        return new Vector3(worldX, worldY, worldZ);
    }

    private void initialiseCore() {
        // All textures were generated for this world by WorldArtGenerator before the window opened
        textures = new TextureLibrary();
        textures.add(gl, "dirt_diffuse", WorldArtGenerator.pathFor(WorldArtGenerator.SOIL));
        textures.add(gl, "water_diffuse", WorldArtGenerator.pathFor(WorldArtGenerator.SEA));
        textures.add(gl, "sky", WorldArtGenerator.pathFor(WorldArtGenerator.SKY));
        textures.add(gl, "sun_glow", WorldArtGenerator.pathFor(WorldArtGenerator.SUN_GLOW));
        for (int s = 0; s < NUM_SPECIES; s++) {
            textures.add(gl, "leaf" + s, WorldArtGenerator.pathFor(WorldArtGenerator.leafName(s)));
        }

        Texture waterTexInstance = textures.get("water_diffuse");
        waterTexInstance.bind(gl);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT);

        ambientLight = new Vector3(0.4f, 0.38f, 0.35f); 
        nightProportion = 0.0f;
        
        timeOfDay = 0.5f;

        lights = new Light[1];
        float lightSize = 275.0f; 
        
        Light l = new Light(gl, camera, true, new Vector3(0,0,0), lightSize, textures.get("sun_glow"));
        Material m = new Material();

        // Sunlight takes on a little of the star's black-body colour
        float[] sunTint = worldArt.palette().sunTint;
        m.setFullDiffuse(0.75f + 0.25f * sunTint[0], 0.75f + 0.25f * sunTint[1], 0.75f + 0.25f * sunTint[2]);
        m.setFullSpecular(1.0f, 0.0f, 0.0f);   
        l.setMaterial(m);
        lights[0] = l;

        skyModel = makeSkybox(gl, "assets/shaders/fs_single_sky.txt", textures.get("sky"));
        
        chunkCache = new HashMap<>();
        grassCache = new HashMap<>(); 
        floraCache = new HashMap<>();

        terrainShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_standard_d.txt");
        depthPrePassShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_depth_only.txt");
        solidShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_solid.txt");

        terrainMaterial = new Material(
            new Vector3(1.0f, 1.0f, 1.0f), 
            new Vector3(1.0f, 1.0f, 1.0f), 
            new Vector3(0.1f, 0.1f, 0.1f), 
            4.0f                                                                                                                                                                                
        );
        terrainMaterial.setDiffuseMap(textures.get("dirt_diffuse"));
                enableAnisotropicFiltering(textures.get("dirt_diffuse"));
        textures.add(gl, "soil_regions", WorldArtGenerator.pathFor(WorldArtGenerator.SOIL_REGIONS));

        // Each nation's wall, roof and fence textures repeat across surfaces
        for (String name : infraManager.textureJobs().keySet()) {
            textures.addWrap(gl, name, WorldArtGenerator.pathFor(name));
            enableAnisotropicFiltering(textures.get(name));
        }
        infraManager.setNationTextures(textures::get);
        terrainRenderer = new Renderer();
        globalModelMatrix = new Matrix4(1);


        this.infraCache = new HashMap<>();
        this.signModelsByNation = new HashMap<>();
        this.postModelsByNation = new HashMap<>();
        
        // --- INFRASTRUCTURE COMPILATION ---
        
        // 1. Pre-compile the 4 geometry variations for posts once to preserve VRAM
        int[] sliceOptions = {3, 4, 8, 12}; // Triangle, Square, Octagon, 12-pointed Circle
        Map<Integer, Mesh> postMeshesBySlice = new HashMap<>();
        
        for (int slices : sliceOptions) {
            float[] vert = Cylinder.createVertices(slices);
            int[] ind = Cylinder.createIndices(slices);
            postMeshesBySlice.put(slices, new Mesh(gl, vert, ind));
        }
        
        Material postMat = new Material(
            new Vector3(0.35f, 0.25f, 0.15f), // Wood/brown ambient
            new Vector3(0.35f, 0.25f, 0.15f), // Wood/brown diffuse
            new Vector3(0.0f, 0.0f, 0.0f),    // Zero specular
            1.0f
        );
        
        // Assign a random post cross-section to each nation deterministically based on the world seed
        java.util.Random postRand = new java.util.Random(this.worldSeed + 7777L);
        
        for (int n = 1; n <= totalNationsCount; n++) {
            int chosenSlices = sliceOptions[postRand.nextInt(sliceOptions.length)];
            Mesh postMesh = postMeshesBySlice.get(chosenSlices);
            Model postModel = new Model("post_nation_" + n, postMesh, new Matrix4(1), solidShader, postMat, terrainRenderer, lights, camera);
            postModelsByNation.put(n, postModel);
        }

        // 3. Pre-compile the flat TwoTriangles billboard models for each nation with Text Atlas Mapping
        signboardShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_signboard.txt");
        Mesh signMeshBase = new Mesh(gl, TwoTriangles.vertices, TwoTriangles.indices);
        
        this.nationAtlases = new HashMap<>();
        this.nationAtlasSizes = new HashMap<>();
        this.nationDirections = new HashMap<>();
        
        java.util.Random signConfigRand = new java.util.Random(this.worldSeed + 999L);
        
        // Dynamically count subdirectories inside generated_alphabets
        java.io.File alphabetsDir = new java.io.File(RunFiles.ALPHABETS_DIR);
        java.io.File[] alphabetFolders = alphabetsDir.listFiles(java.io.File::isDirectory);
        
        int totalAvailableAlphabets = (alphabetFolders != null) ? alphabetFolders.length : 0;
        
        if (totalAvailableAlphabets == 0) {
            System.err.println("Warning: No alphabet folders found in generated_alphabets!");
        }
        
        for (int n = 1; n <= totalNationsCount; n++) {
            // Assign random writing direction (0=LR, 1=RL, 2=UD, 3=DU)
            int direction = signConfigRand.nextInt(4);
            nationDirections.put(n, direction);
            
            // Assign random alphabet safely
            int alphabetId = 1 + signConfigRand.nextInt(Math.max(1, totalAvailableAlphabets));
            // Load and cache the atlas if not already loaded
            if (!nationAtlases.containsKey(n)) {
                Texture atlas = createAlphabetAtlas(gl, alphabetId);
                if (atlas != null) {
                    nationAtlases.put(n, atlas);
                    
                    // Count files safely to determine the number of available characters
                    java.io.File alphabetDir = new java.io.File(RunFiles.ALPHABETS_DIR, "alphabet" + alphabetId);
                    java.io.File[] glyphFiles = alphabetDir.listFiles((d, name) -> name.startsWith("glyph_") && name.endsWith(".png"));
                    int glyphCount = (glyphFiles != null) ? glyphFiles.length : 0;
                    nationAtlasSizes.put(n, glyphCount);
                }
            }
            
            java.awt.Color awtColor = nationManager.getNationColor(n);
            Vector3 nationRGB = new Vector3(awtColor.getRed() / 255.0f, awtColor.getGreen() / 255.0f, awtColor.getBlue() / 255.0f);
            
            // Fix: Replaced nationRGB with raw white values so the shader gets a clean canvas 
            Material signMaterial = new Material(
                new Vector3(1.0f, 1.0f, 1.0f),                     
                new Vector3(1.0f, 1.0f, 1.0f),                     
                new Vector3(0.0f, 0.0f, 0.0f),    
                1.0f                           
            );
            
            // Note: We are using the new signboardShader here instead of solidShader
            Model signModel = new Model("sign_nation_" + n, signMeshBase, new Matrix4(1), signboardShader, signMaterial, terrainRenderer, lights, camera);
            signModelsByNation.put(n, signModel);
        }
        
        // --- MULTI-SPECIES 3D GEOMETRY COMPILATION PIPELINE ---
        floraBranchModelsLOD = new Model[NUM_SPECIES][3][FLORA_VARIATIONS];
        floraLeafModelsLOD = new Model[NUM_SPECIES][3][FLORA_VARIATIONS];
        
        leafShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_leaf.txt");
        initialiseWaterAndGrass();
    }

    private void initialiseFloraVariation(int s, int variation) {
        int[] lodSlices = {12, 8, 4};
        {
            SpeciesConfig sc = speciesConfigs[s];
            
            float brightnessBoost = 2.5f;
            Vector3 boostedTrunkColor = new Vector3(
                sc.trunkColor.x * brightnessBoost,
                sc.trunkColor.y * brightnessBoost,
                sc.trunkColor.z * brightnessBoost
            );

            Material floraMat = new Material(
                boostedTrunkColor,             
                boostedTrunkColor,             
                new Vector3(0.02f, 0.02f, 0.02f), 
                2.0f                           
            );
            
            floraMat.setDiffuseMap(textures.get("dirt_diffuse"));
            
            {
                int i = variation;
                java.util.Random fRand = new java.util.Random(worldSeed + s * 3721L + i * 8273L);
                
                float bRate = sc.baseBRate + fRand.nextFloat() * sc.varBRate;
                float sWidth = sc.baseSWidth + fRand.nextFloat() * sc.varSWidth;
                float wDecl = sc.baseWDecl + fRand.nextFloat() * sc.varWDecl;
                float sDist = sc.baseSDist + fRand.nextFloat() * sc.varSDist;
                float bAngle = sc.baseBAngle + fRand.nextFloat() * sc.varBAngle;
                
                int texNum = sc.leafTexNum;
                Material leafMat = new Material(new Vector3(0.9f, 0.9f, 0.9f), new Vector3(0.2f, 0.2f, 0.2f), new Vector3(0.0f, 0.0f, 0.0f), 1.0f);
                leafMat.setDiffuseMap(textures.get("leaf" + texNum));

                for (int lod = 0; lod < 3; lod++) {
                    
                    Flora.FloraBundle fBundle = Flora.generateFloraBundle(
                        gl, worldSeed + (i * 7382L) + s * 8831L, 
                        bRate, sWidth, wDecl, sDist, bAngle, lodSlices[lod], sc.leafScale
                    );
                    
                    floraBranchModelsLOD[s][lod][i] = new Model("flora_branch_s" + s + "_" + i + "_lod" + lod, fBundle.branchMesh, new Matrix4(1), terrainShader, floraMat, terrainRenderer, lights, camera);
                    floraLeafModelsLOD[s][lod][i] = new Model("flora_leaf_s" + s + "_" + i + "_lod" + lod, fBundle.leafMesh, new Matrix4(1), leafShader, leafMat, terrainRenderer, lights, camera);
                }
            }
        }
    }

        // Vertices along each side of the water grid; spacing grows from about 2 units under the
    // viewer to about 30 at the edge, fine enough for the waves where they can be seen
    private static final int WATER_GRID_SIZE = 256;
    private static final float WATER_GRID_CENTRE_DENSITY = 0.12f;

    /** A unit square of water, densest at its centre, for the wave shader to displace. */
    private static Mesh createWaterGrid(GL3 gl) {
        int n = WATER_GRID_SIZE;
        float[] coordinate = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n * 2.0f - 1.0f;
            coordinate[i] = 0.5f * t * (WATER_GRID_CENTRE_DENSITY + (1.0f - WATER_GRID_CENTRE_DENSITY) * Math.abs(t));
        }
        float[] vertices = new float[(n + 1) * (n + 1) * 8];
        int v = 0;
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                vertices[v++] = coordinate[i];
                vertices[v++] = 0.0f;
                vertices[v++] = coordinate[j];
                vertices[v++] = 0.0f;
                vertices[v++] = 1.0f;
                vertices[v++] = 0.0f;
                vertices[v++] = i / (float) n;
                vertices[v++] = j / (float) n;
            }
        }
        int[] indices = new int[n * n * 6];
        int k = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = j * (n + 1) + i, b = a + 1, c = a + n + 1, d = c + 1;
                indices[k++] = a; indices[k++] = c; indices[k++] = b;
                indices[k++] = b; indices[k++] = c; indices[k++] = d;
            }
        }
        return new Mesh(gl, vertices, indices);
    }

    private void initialiseWaterAndGrass() {
                waterShader = new Shader(gl, "assets/shaders/vs_water.txt", "assets/shaders/fs_water.txt");
                waveMapTexture = createWaveMapTexture();
        
        waterMaterial = new Material(
            new Vector3(0.01f, 0.31f, 0.55f),  
            new Vector3(0.01f, 0.31f, 0.55f),  
                        new Vector3(6.0f, 6.0f, 6.0f),
            2048f                                                                                                                                                                                
        );
        waterMaterial.setDiffuseMap(textures.get("water_diffuse"));

        Renderer waterRenderer = new Renderer(); 
        Matrix4 waterModelMatrix = new Matrix4(1);
                Mesh waterMesh = createWaterGrid(gl);  
        waterPlaneModel = new Model("ocean_surface", waterMesh, waterModelMatrix, waterShader, waterMaterial, waterRenderer, lights, camera);

        textures.add(gl, "grass_atlas", WorldArtGenerator.pathFor(WorldArtGenerator.GRASS_ATLAS));
        
        Texture grassTex = textures.get("grass_atlas");
        grassTex.bind(gl);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST_MIPMAP_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        gl.glGenerateMipmap(GL.GL_TEXTURE_2D);

        grassShader = new Shader(gl, "assets/shaders/vs_grass_instanced.txt", "assets/shaders/fs_grass_instanced.txt");

        float[] grassVertices = {
            -0.8f, 0.0f,  0.0f,  0.0f, 0.0f,
             0.8f, 0.0f,  0.0f,  1.0f, 0.0f,
             0.8f, 4.8f,  0.0f,  1.0f, 1.0f,
            -0.8f, 0.0f,  0.0f,  0.0f, 0.0f,
             0.8f, 4.8f,  0.0f,  1.0f, 1.0f,
            -0.8f, 4.8f,  0.0f,  0.0f, 1.0f
        };

        int[] tempBuffers = new int[2];
        gl.glGenVertexArrays(1, tempBuffers, 0);
        grassVAO = tempBuffers[0];
        gl.glBindVertexArray(grassVAO);

        gl.glGenBuffers(1, tempBuffers, 0);
        grassVBO = tempBuffers[0];
        gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassVBO);
        java.nio.FloatBuffer geoBuffer = com.jogamp.common.nio.Buffers.newDirectFloatBuffer(grassVertices);
        gl.glBufferData(GL3.GL_ARRAY_BUFFER, grassVertices.length * 4L, geoBuffer, GL3.GL_STATIC_DRAW);

        gl.glEnableVertexAttribArray(0); 
        gl.glVertexAttribPointer(0, 3, GL3.GL_FLOAT, false, 5 * 4, 0);
        gl.glEnableVertexAttribArray(1); 
        gl.glVertexAttribPointer(1, 2, GL3.GL_FLOAT, false, 5 * 4, 3 * 4);

        gl.glGenBuffers(1, tempBuffers, 0);
        grassChunkCoordVBO = tempBuffers[0];
        gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassChunkCoordVBO);
        
        int instanceStride = 5 * 4; 

        gl.glEnableVertexAttribArray(2);
        gl.glVertexAttribPointer(2, 3, GL3.GL_FLOAT, false, instanceStride, 0);
        gl.glVertexAttribDivisor(2, 1);  

        gl.glEnableVertexAttribArray(3);
        gl.glVertexAttribPointer(3, 1, GL3.GL_FLOAT, false, instanceStride, 3 * 4); 
        gl.glVertexAttribDivisor(3, 1);  

        gl.glEnableVertexAttribArray(4);
        gl.glVertexAttribPointer(4, 1, GL3.GL_FLOAT, false, instanceStride, 4 * 4); 
        gl.glVertexAttribDivisor(4, 1);  

        gl.glBindVertexArray(0);
    }

        private void finishInitialise() {
                organismManager.initialise(gl);
        inhabitants.initialise(gl);
        playerBody.initialise(gl);
        spawnPlayerAtRandomLocation();
        createDepthFramebuffer(gl, currentWidth, currentHeight);

        java.util.Random rand = new java.util.Random(worldSeed);
        float r = 0.35f + rand.nextFloat() * 0.5f;
        float g = 0.35f + rand.nextFloat() * 0.5f;
        float b = 0.35f + rand.nextFloat() * 0.5f;
        healthyColour = new Vector3(r, g, b);
        dyingColour = new Vector3(0.4f, 0.25f, 0.15f);
    }

    /**
     * The ground is mostly seen at a glancing angle, where plain mipmapping blurs the soil's
     * fine grain into smeared streaks; anisotropic filtering keeps it crisp when available.
     */
    private void enableAnisotropicFiltering(Texture texture) {
        if (!gl.isExtensionAvailable("GL_EXT_texture_filter_anisotropic")) return;
        float[] maxAniso = new float[1];
        gl.glGetFloatv(GL.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, maxAniso, 0);
        texture.bind(gl);
        gl.glTexParameterf(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAX_ANISOTROPY_EXT, Math.min(16f, maxAniso[0]));
    }

        // With a fixed test seed the spawn sequence repeats too, so test runs are comparable
    private final java.util.Random spawnRandom = Long.getLong("xenoguesser.seed") != null
            ? new java.util.Random(Long.getLong("xenoguesser.seed")) : new java.util.Random();

    private void spawnPlayerAtRandomLocation() {
        java.util.Random dynamicRand = spawnRandom;
        float halfRegion = TOTAL_REGION_WIDTH / 2.0f;
        
        float spawnX = 0.0f;
        float spawnZ = 0.0f;
        float terrainHeightAtSpawn = 0.0f;
        // Rounds always start on a road, looking along it
                // Developer aid: -Dxenoguesser.view=house starts each round looking at a house
        float[] roadSpawn = "house".equals(System.getProperty("xenoguesser.view"))
                ? infraManager.randomHouseViewpoint(dynamicRand) : infraManager.randomRoadPoint(dynamicRand);
        float lookX = 0.0f, lookZ = -1.0f;
        if (roadSpawn != null) {
            spawnX = roadSpawn[0];
            spawnZ = roadSpawn[1];
            lookX = roadSpawn[2];
            lookZ = roadSpawn[3];
        }
        boolean foundDryLand = roadSpawn != null;
        if ("edge".equals(System.getProperty("xenoguesser.view"))) {
            // Developer aid: stand just inside the eastern edge of the map, looking out past it
            float halfWidth = TOTAL_REGION_WIDTH / 2.0f;
            for (float z = 0; z < halfWidth; z += 500.0f) {
                if (TerrainMesh.getLayeredHeight(halfWidth - 200.0f, z, worldNoise) > seaLevelHeight) {
                    spawnX = halfWidth - 200.0f;
                    spawnZ = z;
                    break;
                }
            }
            lookX = 1.0f;
            lookZ = 0.0f;
            foundDryLand = true;
        }

                        if ("street".equals(System.getProperty("xenoguesser.view"))) {
            // Developer aid: on a town pavement among the inhabitants
            float[] view = inhabitants.streetViewpoint(dynamicRand);
            if (view != null) {
                spawnX = view[0];
                spawnZ = view[1];
                lookX = view[2];
                lookZ = view[3];
                foundDryLand = true;
            }
        }

        if ("zoo".equals(System.getProperty("xenoguesser.view"))) {
            float[] view = organismManager.zooViewpoint(dynamicRand, TOTAL_REGION_WIDTH);
            if (view != null) {
                spawnX = view[0];
                spawnZ = view[1];
                lookX = view[2];
                lookZ = view[3];
                foundDryLand = true;
            }
        }

        if ("bug".equals(System.getProperty("xenoguesser.view"))) {
            // Developer aid: stand in some species' range with one of them just ahead
            float[] view = organismManager.showcaseViewpoint(dynamicRand, TOTAL_REGION_WIDTH);
            if (view != null) {
                spawnX = view[0];
                spawnZ = view[1];
                lookX = view[2];
                lookZ = view[3];
                foundDryLand = true;
            }
        }

        String devView = System.getProperty("xenoguesser.view");
        if ("shore".equals(devView) || "swim".equals(devView)) {
            // Developer aid: stand on a coast facing the open sea, to watch the waves
            for (int attempt = 0; attempt < 200000; attempt++) {
                float x = (dynamicRand.nextFloat() * TOTAL_REGION_WIDTH) - halfRegion;
                float z = (dynamicRand.nextFloat() * TOTAL_REGION_WIDTH) - halfRegion;
                if (TerrainMesh.getLayeredHeight(x, z, worldNoise) <= seaLevelHeight + 2.0f) continue;
                int cx = (int) Math.floor(x / PHYSICAL_CHUNK_SIZE), cz = (int) Math.floor(z / PHYSICAL_CHUNK_SIZE);
                if (regionalManager.getChunkDistanceToWater(cx, cz) != 2) continue;
                float best = 0f;
                for (int a = 0; a < 16; a++) {
                    float ax = (float) Math.cos(a * Math.PI / 8), az = (float) Math.sin(a * Math.PI / 8);
                    float strength = regionalManager.waveStrengthAt(x + ax * 900f, z + az * 900f, PHYSICAL_CHUNK_SIZE);
                    if (strength > best) { best = strength; lookX = ax; lookZ = az; }
                }
                if (best > 0.75f) {
                    spawnX = x;
                    spawnZ = z;
                    if ("swim".equals(devView)) {
                        // Out in the water, looking back at the waves rolling in
                        spawnX += lookX * 600f;
                        spawnZ += lookZ * 600f;
                        lookX = -lookX;
                        lookZ = -lookZ;
                    }
                    foundDryLand = true;
                    break;
                }
            }
        }

        while (!foundDryLand) {
            spawnX = (dynamicRand.nextFloat() * TOTAL_REGION_WIDTH) - halfRegion;
            spawnZ = (dynamicRand.nextFloat() * TOTAL_REGION_WIDTH) - halfRegion;
            terrainHeightAtSpawn = TerrainMesh.getLayeredHeight(spawnX, spawnZ, worldNoise);
            if (terrainHeightAtSpawn > seaLevelHeight) {
                foundDryLand = true;
            }
        }

        this.timeOfDay = dynamicRand.nextFloat();

        float seasonalPhase = dynamicRand.nextFloat() * (float)(2.0 * Math.PI);
        this.currentSeasonalTiltDegrees = this.planetAxialTiltDegrees * (float)Math.sin(seasonalPhase);

        if (this.minimap != null) {
            this.minimap.setPlayerSpawnLocation(spawnX, spawnZ);
        }

        moveToLocation(spawnX, spawnZ, lookX, lookZ);
        // Developer aid: -Dxenoguesser.pitch=-60 starts the round looking down by that many degrees
        String pitch = System.getProperty("xenoguesser.pitch");
        if (pitch != null) camera.updateYawPitch(0f, Float.parseFloat(pitch) / camera.MOUSE_SPEED);
    }

    public void moveToLocation(float spawnX, float spawnZ) {
        moveToLocation(spawnX, spawnZ, 0.0f, -1.0f);
    }

    private void moveToLocation(float spawnX, float spawnZ, float lookX, float lookZ) {
        float terrainHeightAtSpawn = TerrainMesh.getLayeredHeight(spawnX, spawnZ, worldNoise);
        camera.setPosition(new Vector3(spawnX, terrainHeightAtSpawn + playerEyeHeight, spawnZ));
        camera.setTarget(new Vector3(spawnX + lookX * 10.0f, terrainHeightAtSpawn + playerEyeHeight, spawnZ + lookZ * 10.0f));

        lastChunkX = (int) Math.floor((spawnX + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        lastChunkZ = (int) Math.floor((spawnZ + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        
        // During the initial load, distant grass is seeded over later loading frames instead
        updateVisibleChunks(worldReady);
    }

    public void resetToNextRound(GL3 gl) {
        for (Model model : chunkCache.values()) {
            if (model.mesh != null) model.mesh.dispose(gl);
        }
        chunkCache.clear();
        grassCache.clear(); 
        floraCache.clear();

        lastChunkX = Integer.MAX_VALUE;
        lastChunkZ = Integer.MAX_VALUE;
                totalGrassInstances = 0;
        organismManager.clear();
        inhabitants.clear();

        if (minimap != null) {
            minimap.resetMapState();
        }

        spawnPlayerAtRandomLocation();
    }

    private void updateVisibleChunks(boolean forceImmediate) {
        infraManager.prepareRoadNetwork(
            PHYSICAL_CHUNK_SIZE, TOTAL_REGION_WIDTH, seaLevelHeight, worldNoise
        );
        Map<String, Integer> requiredChunksWithLod = new HashMap<>();

        for (int cz = lastChunkZ - VIEW_DISTANCE; cz <= lastChunkZ + VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - VIEW_DISTANCE; cx <= lastChunkX + VIEW_DISTANCE; cx++) {
                int deltaX = Math.abs(cx - lastChunkX);
                int deltaZ = Math.abs(cz - lastChunkZ);
                int chunkRingDistance = Math.max(deltaX, deltaZ);

                int currentSegments;
                if (chunkRingDistance > 14) currentSegments = 4;   
                else if (chunkRingDistance > 7) currentSegments = 10;  
                else if (chunkRingDistance > 3) currentSegments = 25;  
                else currentSegments = 50;  

                String key = cx + "_" + cz;
                requiredChunksWithLod.put(key, currentSegments);
            }
        }

        Iterator<Map.Entry<String, Model>> iterator = chunkCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Model> entry = iterator.next();
            String key = entry.getKey();
            if (!requiredChunksWithLod.containsKey(key)) {
                Model oldModel = entry.getValue();
                                if (oldModel.mesh != null) oldModel.mesh.dispose(gl);
                iterator.remove();
                chunkBounds.remove(key);
            }
        }

        for (Map.Entry<String, Integer> target : requiredChunksWithLod.entrySet()) {
            String key = target.getKey();
            int targetSegments = target.getValue();
            
            String[] coords = key.split("_");
            int cx = Integer.parseInt(coords[0]);
            int cz = Integer.parseInt(coords[1]);

            boolean mustBuild = false;

            if (chunkCache.containsKey(key)) {
                Model cachedModel = chunkCache.get(key);
                if (!cachedModel.name.endsWith("seg" + targetSegments)) {
                    if (cachedModel.mesh != null) cachedModel.mesh.dispose(gl);
                    mustBuild = true;
                }
            } else {
                mustBuild = true;
            }

            if (mustBuild) {
                float dynamicScale = PHYSICAL_CHUNK_SIZE / (float) targetSegments;
                Mesh chunkMesh = TerrainMesh.generateTerrainChunk(gl, targetSegments, dynamicScale, cx, cz, worldNoise);
                                Model chunkModel = new Model("chunk_" + cx + "_" + cz + "_seg" + targetSegments, chunkMesh, globalModelMatrix, terrainShader, terrainMaterial, terrainRenderer, lights, camera);
                chunkCache.put(key, chunkModel);
                chunkBounds.put(key, chunkBoundingSphere(cx, cz));
            }
        }
        
        // --- MULTI-SPECIES PROBABILISTIC SPATIAL ECOSYSTEM SEEDING ---
        Map<String, Boolean> activeFloraKeys = new HashMap<>();
        for (int cz = lastChunkZ - VIEW_DISTANCE; cz <= lastChunkZ + VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - VIEW_DISTANCE; cx <= lastChunkX + VIEW_DISTANCE; cx++) {
                activeFloraKeys.put(cx + "_" + cz, true);
            }
        }
        floraCache.keySet().retainAll(activeFloraKeys.keySet());

        for (int cz = lastChunkZ - VIEW_DISTANCE; cz <= lastChunkZ + VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - VIEW_DISTANCE; cx <= lastChunkX + VIEW_DISTANCE; cx++) {
                String key = cx + "_" + cz;
                if (!floraCache.containsKey(key)) {
                    List<FloraInstance> instances = new ArrayList<>();
                    float urbanness = infraManager.getUrbanness((cx + 0.5f) * PHYSICAL_CHUNK_SIZE, (cz + 0.5f) * PHYSICAL_CHUNK_SIZE);
                    
                    // Seed species independently based on their unique abundance calculations
                    for (int s = 0; s < NUM_SPECIES; s++) {
                        SpeciesConfig sc = speciesConfigs[s];
                        long fSeed = worldSeed ^ ((long) cx * 492876847L) ^ ((long) cz * 314159265L) ^ ((long) s * 9012431L);
                        java.util.Random cRand = new java.util.Random(fSeed);

                        // FIX: Give the black-box manager 100x multiplier headroom to prevent sub-0.5 integer zero-outs
                        float internalScale = 100f;
                        int maxCeiling = Math.max(1, (int)(5 * sc.baseAbundance));
                        int scaledCeiling = (int) (maxCeiling * internalScale);

                        int scaledAttempts = regionalManager.evaluateChunkAssetCount(
                            cx, cz, PHYSICAL_CHUNK_SIZE, 
                            sc.baseAbundance * internalScale, // Turn 0.49 into 49.0 smoothly
                            scaledCeiling,       
                            sc.abundanceFactor
                        );

                        // FIX: Scale back down using a random float roll for perfect linear fraction probability 
                        double actualAttemptsFloat = scaledAttempts / (double) internalScale;
                        actualAttemptsFloat *= 1.0 - URBAN_FLORA_REDUCTION * urbanness;
                        int speciesAttempts = (int) actualAttemptsFloat;
                        double fractionalPart = actualAttemptsFloat - speciesAttempts;
                        if (cRand.nextFloat() < fractionalPart) {
                            speciesAttempts++;
                        }

                        for (int i = 0; i < speciesAttempts; i++) {
                            float cxWorld = cx * PHYSICAL_CHUNK_SIZE + (cRand.nextFloat() * PHYSICAL_CHUNK_SIZE);
                            float czWorld = cz * PHYSICAL_CHUNK_SIZE + (cRand.nextFloat() * PHYSICAL_CHUNK_SIZE);
                            float cyWorld = TerrainMesh.getLayeredHeight(cxWorld, czWorld, worldNoise);

                            if (cyWorld > seaLevelHeight + 0.1f
                                    && !infraManager.isRoadLocation(cxWorld, czWorld, 11.0f)
                                    && !infraManager.isBuildingLocation(cxWorld, czWorld, 6.0f)) {
                                int randModelIndex = cRand.nextInt(FLORA_VARIATIONS);
                                float randomScale = 0.70f + cRand.nextFloat() * 0.60f;
                                float randomRotY = cRand.nextFloat() * 360.0f;
                                
                                instances.add(new FloraInstance(new Vector3(cxWorld, cyWorld, czWorld), s, randModelIndex, randomScale, randomRotY));
                            }
                        }
                    }
                    floraCache.put(key, instances);
                }
            }
        }

        Map<String, Boolean> activeGrassKeys = new HashMap<>();
        for (int cz = lastChunkZ - GRASS_VIEW_DISTANCE; cz <= lastChunkZ + GRASS_VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - GRASS_VIEW_DISTANCE; cx <= lastChunkX + GRASS_VIEW_DISTANCE; cx++) {
                activeGrassKeys.put(cx + "_" + cz, true);
            }
        }
        grassCache.keySet().retainAll(activeGrassKeys.keySet());

        totalGrassInstances = 0;
        boolean generatedThisFrame = false; 
        long grassDeadline = System.nanoTime() + grassBudgetNanos;

        for (int cz = lastChunkZ - GRASS_VIEW_DISTANCE; cz <= lastChunkZ + GRASS_VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - GRASS_VIEW_DISTANCE; cx <= lastChunkX + GRASS_VIEW_DISTANCE; cx++) {
                String key = cx + "_" + cz;
                float[] chunkGrassData = grassCache.get(key);
                
                if (chunkGrassData == null) {
                    int distanceFromPlayer = Math.max(Math.abs(cx - lastChunkX), Math.abs(cz - lastChunkZ));
                    boolean prioritizeNearbyChunk = distanceFromPlayer <= 3;
                    boolean overBudget = System.nanoTime() > grassDeadline;
                    if (generatedThisFrame && overBudget && !forceImmediate && !prioritizeNearbyChunk) {
                        continue; 
                    }

                    int dynamicGrassAttempts = regionalManager.evaluateChunkAssetCount(
                        cx, cz, PHYSICAL_CHUNK_SIZE, 
                        GRASS_BASE_ABUNDANCE,
                        MAX_GRASS_LIMIT,
                        this.grassAbundanceFactor
                    );

                    if (dynamicGrassAttempts > 0) {
                        float[] rawChunkBuffer = new float[dynamicGrassAttempts * 5];
                        int writeIdx = 0;

                        float chunkMinX = cx * PHYSICAL_CHUNK_SIZE;
                        float chunkMinZ = cz * PHYSICAL_CHUNK_SIZE;

                        for (int i = 0; i < dynamicGrassAttempts; i++) {
                            long bladeSeed = worldSeed 
                                    ^ ((long) cx * 73731703L) 
                                    ^ ((long) cz * 19349663L) 
                                    ^ ((long) i * 2147483647L);

                            double pVal1 = Math.sin(bladeSeed * 12.9898) * 43758.5453123;
                            double pVal2 = Math.cos(bladeSeed * 78.2330) * 43758.5453123;
                            double pVal3 = Math.sin(bladeSeed * 34.1245) * 54321.1243141;
                            double pVal4 = Math.cos(bladeSeed * 95.4321) * 67891.9876543;

                            float rand1 = (float)(Math.abs(pVal1) % 1.0);
                            float rand2 = (float)(Math.abs(pVal2) % 1.0);
                            float rand3 = (float)(Math.abs(pVal3) % 1.0);
                            float rand4 = (float)(Math.abs(pVal4) % 1.0);

                            float worldX = chunkMinX + (rand1 * PHYSICAL_CHUNK_SIZE);
                            float worldZ = chunkMinZ + (rand2 * PHYSICAL_CHUNK_SIZE);
                            float worldY = TerrainMesh.getLayeredHeight(worldX, worldZ, worldNoise);
                            
                            if (worldY > seaLevelHeight + 0.1f
                                    && !infraManager.isRoadLocation(worldX, worldZ, 11.0f)) {
                                float structuralHeightBase = this.grassHeightFactor.evaluate(cx, cz, worldX, worldZ);
                                float structuralColourBase = this.grassColourFactor.evaluate(cx, cz, worldX, worldZ);

                                float u1 = Math.max(0.0001f, rand3); 
                                float u2 = rand4;
                                
                                float logTerm = (float) Math.sqrt(-2.0 * Math.log(u1));
                                float standardNormalColour = (float) (logTerm * Math.cos(2.0 * Math.PI * u2));
                                float standardNormalHeight = (float) (logTerm * Math.sin(2.0 * Math.PI * u2));
                                
                                float colourStandardDeviation = 0.1f;
                                float colourJitter = standardNormalColour * colourStandardDeviation;
                                float finalColourPhenotype = Math.max(0.0f, Math.min(1.0f, structuralColourBase + colourJitter));

                                float climateHeightTarget = 0.4f + structuralHeightBase * (1.7f - 0.4f);
                                float heightStandardDeviation = 0.2f; 
                                float heightJitter = standardNormalHeight * heightStandardDeviation;
                                float finalBladeHeight = Math.max(0.25f, Math.min(2.4f, climateHeightTarget + heightJitter));

                                rawChunkBuffer[writeIdx++] = worldX;
                                rawChunkBuffer[writeIdx++] = worldY;
                                rawChunkBuffer[writeIdx++] = worldZ;
                                rawChunkBuffer[writeIdx++] = finalColourPhenotype;
                                rawChunkBuffer[writeIdx++] = finalBladeHeight;
                            }
                        }

                        if (writeIdx < rawChunkBuffer.length) {
                            chunkGrassData = java.util.Arrays.copyOf(rawChunkBuffer, writeIdx);
                        } else {
                            chunkGrassData = rawChunkBuffer;
                        }
                    } else {
                        chunkGrassData = new float[0];
                    }
                    
                    grassCache.put(key, chunkGrassData);
                    generatedThisFrame = true; 
                }
                totalGrassInstances += (chunkGrassData.length / 5);
            }
        }

        if (totalGrassInstances > 0) {
            int requiredFloats = totalGrassInstances * 5;

            if (persistentGrassBuffer == null || requiredFloats > currentGrassGPUCapacityFloats) {
                currentGrassGPUCapacityFloats = (int) (requiredFloats * 1.2f); 
                persistentGrassBuffer = com.jogamp.common.nio.Buffers.newDirectFloatBuffer(currentGrassGPUCapacityFloats);
                
                gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassChunkCoordVBO);
                gl.glBufferData(GL3.GL_ARRAY_BUFFER, currentGrassGPUCapacityFloats * 4L, null, GL3.GL_DYNAMIC_DRAW);
            }

                        persistentGrassBuffer.clear();
            int writtenGrassInstances = 0;


            for (int cz = lastChunkZ - GRASS_VIEW_DISTANCE; cz <= lastChunkZ + GRASS_VIEW_DISTANCE; cz++) {
                for (int cx = lastChunkX - GRASS_VIEW_DISTANCE; cx <= lastChunkX + GRASS_VIEW_DISTANCE; cx++) {
                                        String key = cx + "_" + cz;
                    float[] chunkGrassData = grassCache.get(key);
                    if (chunkGrassData != null) {
                        // Far grass is thinned: it shrinks below a pixel and fades into fog,
                        // but drawing every blade out to the horizon was the costliest pass
                        int ring = Math.max(Math.abs(cx - lastChunkX), Math.abs(cz - lastChunkZ));
                        int stride = ring <= 4 ? 1 : ring <= 8 ? 2 : ring <= 14 ? 4 : 8;
                        if (stride == 1) {
                            persistentGrassBuffer.put(chunkGrassData);
                            writtenGrassInstances += chunkGrassData.length / 5;
                        } else {
                            for (int blade = 0; blade * 5 < chunkGrassData.length; blade += stride) {
                                persistentGrassBuffer.put(chunkGrassData, blade * 5, 5);
                                writtenGrassInstances++;
                            }
                        }
                    }
                }
            }
                        persistentGrassBuffer.flip();
            totalGrassInstances = writtenGrassInstances;

            gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassChunkCoordVBO);
            gl.glBufferSubData(GL3.GL_ARRAY_BUFFER, 0, persistentGrassBuffer.limit() * 4L, persistentGrassBuffer);
            gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, 0);
        }

        // --- MULTI-SPECIES INFRASTRUCTURE SEEDING ---
        Map<String, Boolean> activeInfraKeys = new HashMap<>();
        for (int cz = lastChunkZ - VIEW_DISTANCE; cz <= lastChunkZ + VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - VIEW_DISTANCE; cx <= lastChunkX + VIEW_DISTANCE; cx++) {
                activeInfraKeys.put(cx + "_" + cz, true);
            }
        }
        Iterator<Map.Entry<String, List<InfrastructureObject>>> infraIterator = infraCache.entrySet().iterator();
        while (infraIterator.hasNext()) {
            Map.Entry<String, List<InfrastructureObject>> entry = infraIterator.next();
            if (!activeInfraKeys.containsKey(entry.getKey())) {
                for (InfrastructureObject obj : entry.getValue()) {
                    obj.dispose(gl);
                }
                infraIterator.remove();
            }
        }

        for (int cz = lastChunkZ - VIEW_DISTANCE; cz <= lastChunkZ + VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - VIEW_DISTANCE; cx <= lastChunkX + VIEW_DISTANCE; cx++) {
                String key = cx + "_" + cz;
                if (!infraCache.containsKey(key)) {
                    List<InfrastructureObject> spawnedObjects = infraManager.generateForChunk(
                        cx, cz, PHYSICAL_CHUNK_SIZE, TOTAL_REGION_WIDTH, seaLevelHeight, worldNoise
                    );
                    for (InfrastructureObject obj : spawnedObjects) {
                        if (obj.type == InfrastructureObject.Type.BATCH) {
                            obj.initializeBatch(gl, solidShader, terrainShader, terrainRenderer, lights, camera);
                        }
                    }
                    infraCache.put(key, spawnedObjects);
                }
            }
        }
    }


        /** Placement matrix and regional leaf colours never change for a tree, so they are computed once. */
    private void cacheFloraInstance(FloraInstance inst) {
        Matrix4 m = Matrix4Transform.translate(inst.pos);
        m = Matrix4.multiply(m, Matrix4Transform.rotateAroundY(inst.rotationY));
        inst.modelMatrix = Matrix4.multiply(m, Matrix4Transform.scale(inst.scale, inst.scale, inst.scale));

        SpeciesConfig sc = speciesConfigs[inst.speciesIndex];
        int cx = (int) Math.floor((inst.pos.x + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        int cz = (int) Math.floor((inst.pos.z + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        float climateVal = this.leafColourFactor.evaluate(cx, cz, inst.pos.x, inst.pos.z);
        Vector3 regional = new Vector3(
                sc.dyingColor.x + (sc.healthyColor.x - sc.dyingColor.x) * climateVal,
                sc.dyingColor.y + (sc.healthyColor.y - sc.dyingColor.y) * climateVal,
                sc.dyingColor.z + (sc.healthyColor.z - sc.dyingColor.z) * climateVal);
        inst.leafDark = leafDarkColour(regional);
        inst.leafLight = leafLightColour(regional);
    }

    /** Sphere around a terrain chunk, from its corner and centre heights plus slack for peaks between them. */
    private float[] chunkBoundingSphere(int cx, int cz) {
        float half = PHYSICAL_CHUNK_SIZE / 2.0f;
        float centreX = cx * PHYSICAL_CHUNK_SIZE, centreZ = cz * PHYSICAL_CHUNK_SIZE;
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        float[][] samples = {{0, 0}, {-half, -half}, {half, -half}, {-half, half}, {half, half}};
        for (float[] o : samples) {
            float h = TerrainMesh.getLayeredHeight(centreX + o[0], centreZ + o[1], worldNoise);
            low = Math.min(low, h);
            high = Math.max(high, h);
        }
        float halfHeight = (high - low) / 2.0f + 40.0f;
        return new float[] {centreX, (low + high) / 2.0f, centreZ, (float) Math.sqrt(2 * half * half + halfHeight * halfHeight)};
    }

    private void render() {

        double elapsedTime = getSeconds() - startTime;
        double deltaTime = elapsedTime - lastElapsedTime;
        lastElapsedTime = elapsedTime;

        if (minimap != null && minimap.isNextRoundRequested()) {
            minimap.clearNextRoundRequest();
            minimap.resetMapState(); 
            resetToNextRound(gl);
            return;
        }

        if (IS_DEBUG_MODE_ACTIVE && isToTeleport) {
            moveToLocation(teleportX, teleportZ);
            isToTeleport = false;
        }

        // Frames counted over half-second windows give the true rate; the old per-frame
        // estimate replaced any frame slower than 0.1 s with 1/60 s, so slow frames read as 60 fps
        fpsFrameCount++;
        if (elapsedTime - fpsWindowStart >= FPS_WINDOW_SECONDS) {
            double measuredFps = fpsFrameCount / (elapsedTime - fpsWindowStart);
            fpsFrameCount = 0;
            fpsWindowStart = elapsedTime;
            if (this.gameHUD != null) {
                this.gameHUD.setGameFps((int) Math.round(measuredFps));
            }
        }

        // Movement uses a capped step so a long hitch doesn't fling the camera
        if (deltaTime > 0.1) { deltaTime = 1.0 / 60.0; }

        boolean moveW = keyboard.w;
        boolean moveS = keyboard.s;
        boolean moveA = keyboard.a;
        boolean moveD = keyboard.d;

        if (moveW && moveS) { moveW = false; moveS = false; }
        if (moveA && moveD) { moveA = false; moveD = false; }

        if (minimap != null && minimap.isFullScreenRevealMode()) {
            camera.updatePosition(false, false, false, false, (float)deltaTime);
        } else {
            camera.updatePosition(moveW, moveA, moveS, moveD, (float)deltaTime);
        }

        Vector3 currentPos = camera.getPosition();
        float rawGroundHeight = TerrainMesh.getLayeredHeight(currentPos.x, currentPos.z, worldNoise);
                float targetCameraHeight = rawGroundHeight + playerEyeHeight;

        // Too deep to stand: swim, eyes just above the waves and riding them
        waterSurfaceHere = regionalManager.waveSurfaceAt(currentPos.x, currentPos.z, (float) elapsedTime,
                seaLevelHeight, PHYSICAL_CHUNK_SIZE);
        swimming = targetCameraHeight < waterSurfaceHere + SWIM_EYE_HEIGHT;
        if (swimming) {
            targetCameraHeight = waterSurfaceHere + SWIM_EYE_HEIGHT;
        }
        float wantedNear = swimming ? SWIMMING_NEAR_PLANE : NEAR_PLANE;
        if (wantedNear != currentNearPlane) {
            currentNearPlane = wantedNear;
            applyProjection();
        }

        float dynamicSmoothingFactor = (swimming ? 10.0f : 6.0f) * (float)deltaTime;
        if (dynamicSmoothingFactor > 1.0f) dynamicSmoothingFactor = 1.0f;

        float smoothedHeight = currentPos.y + (targetCameraHeight - currentPos.y) * dynamicSmoothingFactor;
        camera.setHeight(smoothedHeight);

        lights[0].setPosition(getSunPosition());
        Vector3 sunPos = lights[0].getPosition();

        int currentChunkX = (int) Math.floor((camera.getPosition().x + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        int currentChunkZ = (int) Math.floor((camera.getPosition().z + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);

        if (currentChunkX != lastChunkX || currentChunkZ != lastChunkZ || grassCache.size() < ((GRASS_VIEW_DISTANCE * 2 + 1) * (GRASS_VIEW_DISTANCE * 2 + 1))) {
            lastChunkX = currentChunkX;
            lastChunkZ = currentChunkZ;
            updateVisibleChunks(false);
        }

        if (IS_DEBUG_MODE_ACTIVE && keyboard.g && !lastKeyboardG) {
            FactorName[] values = FactorName.values();
            // Fix: Use Math.floorMod to handle negative numbers safely
            int prevIndex = Math.floorMod(currentDebugFactor.ordinal() - 1, values.length);
            currentDebugFactor = values[prevIndex];
            assignHeatmapToMinimap(currentDebugFactor);
        }
        lastKeyboardG = keyboard.g;

        if (IS_DEBUG_MODE_ACTIVE && keyboard.h && !lastKeyboardH) {
            FactorName[] values = FactorName.values();
            // While standard % works fine for addition, using floorMod here keeps your code uniform
            int nextIndex = Math.floorMod(currentDebugFactor.ordinal() + 1, values.length);
            currentDebugFactor = values[nextIndex];
            assignHeatmapToMinimap(currentDebugFactor);
        }
        lastKeyboardH = keyboard.h;

        float sunAngle = (float)Math.atan2(sunPos.y - currentPos.y, sunPos.x - currentPos.x);
        float degSunAngle = (float)Math.toDegrees(sunAngle);
        float twilightZoneSize = 30f;
        if (degSunAngle < 0f) { nightProportion = 1f; }
        else if (degSunAngle > 180f - twilightZoneSize) { nightProportion = (degSunAngle - (180f - twilightZoneSize)) / twilightZoneSize; }
        else if (degSunAngle < twilightZoneSize) { nightProportion = ((twilightZoneSize - degSunAngle) / twilightZoneSize); }
        else { nightProportion = 0f; }

        float dayR = 0.40f, dayG = 0.38f, dayB = 0.35f; 
        float nightR = 0.08f, nightG = 0.08f, nightB = 0.12f; 
        
        float currentR = dayR + nightProportion * (nightR - dayR);
        float currentG = dayG + nightProportion * (nightG - dayG);
        float currentB = dayB + nightProportion * (nightB - dayB);
        
        ambientLight = new Vector3(currentR, currentG, currentB);

        float[] zenith = worldArt.palette().skyZenith;
        float skyDayR = zenith[0], skyDayG = zenith[1], skyDayB = zenith[2];
        float skyNightR = 0.05f, skyNightG = 0.05f, skyNightB = 0.08f; 
        
        float curSkyR = skyDayR + nightProportion * (skyNightR - skyDayR);
        float curSkyG = skyDayG + nightProportion * (skyNightG - skyDayG);
        float curSkyB = skyDayB + nightProportion * (skyNightB - skyDayB);
        Vector3 skyColour = new Vector3(curSkyR, curSkyG, curSkyB);

        if (this.compassHUD != null) {
            Vector3 cameraLookDir = camera.getForwardDirection(); 
            this.compassHUD.updateHeading(cameraLookDir);
        }

        String skyTextureKey = "sky"; 

        Vector3 camForward = camera.getForwardDirection();
        float maxFloraRenderDistance = VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE;
        float maxFloraDistSq = maxFloraRenderDistance * maxFloraRenderDistance;

        // --- PASS 1: DEPTH PRE-PASS ---
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, depthFBO[0]);
        gl.glClear(GL3.GL_DEPTH_BUFFER_BIT); 
        gl.glEnable(GL3.GL_DEPTH_TEST);
        gl.glEnable(GL3.GL_CULL_FACE);

                depthPrePassShader.use(gl);

        Matrix4 view = camera.getViewMatrix();
        Matrix4 projection = camera.getPerspectiveMatrix();
        Matrix4 viewProjection = Matrix4.multiply(projection, view);
        frustum.update(viewProjection);

        // Only chunks inside the view volume are drawn, in both the depth and colour passes
        visibleChunks.clear();
        for (Map.Entry<String, Model> chunk : chunkCache.entrySet()) {
            float[] b = chunkBounds.get(chunk.getKey());
            if (b == null || frustum.intersectsSphere(b[0], b[1], b[2], b[3])) {
                visibleChunks.add(chunk.getValue());
            }
        }

        for (Model plane : visibleChunks) {
            plane.renderDepthPass(gl, depthPrePassShader, viewProjection);
        }
        
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);

        // --- PASS 2: MAIN FORWARD DRAW ---
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST); 
        gl.glDisable(GL.GL_CULL_FACE); 

        float sunProgress = timeOfDay - (float)Math.floor(timeOfDay);
        float sunAngleDeg = 10.0f + sunProgress * (170.0f - 10.0f);

        Vector3 camPosForSky = camera.getPosition();
        float totalPlayableRegion = (VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE) * 50.0f;
        float maxMapEdgeZ = totalPlayableRegion / 2.0f;
        
        float latitudeFactor = camPosForSky.z / maxMapEdgeZ;
        if (latitudeFactor > 1.0f) latitudeFactor = 1.0f;
        if (latitudeFactor < -1.0f) latitudeFactor = -1.0f;
        
        float latitudeAngleDeg = -latitudeFactor * 35.0f;
        float tiltAngleDeg = latitudeAngleDeg + currentSeasonalTiltDegrees;

        Matrix4 skyRotation = Matrix4Transform.rotateAroundX(tiltAngleDeg);
        skyRotation = Matrix4.multiply(skyRotation, Matrix4Transform.rotateAroundZ(sunAngleDeg + 90.0f));

        Matrix4 skyTransform = Matrix4Transform.translate(camera.getPosition());
        skyTransform = Matrix4.multiply(skyTransform, skyRotation);
        skyTransform = Matrix4.multiply(skyTransform, Matrix4Transform.scale(2400.0f, 2400.0f, 2400.0f));
        
        skyModel.setModelMatrix(skyTransform);
        skyModel.render(gl, ambientLight, nightProportion);

        gl.glEnable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_DEPTH_TEST);

        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        lights[0].render(gl); 
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);

        // --- TERRAIN PASS ---
        terrainShader.use(gl);
        terrainShader.setVec3(gl, "skyColour", skyColour);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(terrainShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);

        if (textures.get(skyTextureKey) != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            textures.get(skyTextureKey).bind(gl); 
            terrainShader.setInt(gl, "skyTexture", 2);
        }

                        // Only the ground takes the regional soil colour; trunks and walls share this shader
        gl.glActiveTexture(GL3.GL_TEXTURE6);
        textures.get("soil_regions").bind(gl);
        terrainShader.setInt(gl, "soilRegionMap", 6);
        terrainShader.setFloat(gl, "regionWidth", TOTAL_REGION_WIDTH);
        terrainShader.setFloat(gl, "useSoilRegions", 1.0f);
        for (Model plane : visibleChunks) {
            plane.render(gl, ambientLight, nightProportion);
        }
        terrainShader.setFloat(gl, "useSoilRegions", 0.0f);
        
        // ==========================================
        // --- MULTI-SPECIES FLORA RENDERING PASS ---
        // ==========================================
        // Visible trees are collected first, then drawn as all branches followed by all leaves,
        // so the GPU switches shader twice per frame rather than twice per tree
        visibleFlora.clear();
        for (List<FloraInstance> positions : floraCache.values()) {
            for (FloraInstance inst : positions) {
                float dx = inst.pos.x - currentPos.x;
                float dy = inst.pos.y - currentPos.y;
                float dz = inst.pos.z - currentPos.z;
                float distSq = dx*dx + dy*dy + dz*dz;

                if (distSq > maxFloraDistSq) continue;
                float radius = FLORA_CULL_RADIUS * inst.scale;
                if (!frustum.intersectsSphere(inst.pos.x, inst.pos.y + radius * 0.5f, inst.pos.z, radius)) continue;

                inst.lodIndex = distSq > 135f * 135f ? 2 : (distSq > 65f * 65f ? 1 : 0);
                if (inst.modelMatrix == null) {
                    cacheFloraInstance(inst);
                }
                visibleFlora.add(inst);
            }
        }

        for (FloraInstance inst : visibleFlora) {
            Model branch = floraBranchModelsLOD[inst.speciesIndex][inst.lodIndex][inst.modelIndex];
            branch.setModelMatrix(inst.modelMatrix);
            branch.render(gl, ambientLight, nightProportion);
        }

        leafShader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(leafShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        if (textures.get(skyTextureKey) != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            textures.get(skyTextureKey).bind(gl);
            leafShader.setInt(gl, "skyTexture", 2);
        }
        for (FloraInstance inst : visibleFlora) {
            leafShader.setVec3(gl, "u_LeafDarkColor", inst.leafDark);
            leafShader.setVec3(gl, "u_LeafLightColor", inst.leafLight);
            Model leaves = floraLeafModelsLOD[inst.speciesIndex][inst.lodIndex][inst.modelIndex];
            leaves.setModelMatrix(inst.modelMatrix);
            leaves.render(gl, ambientLight, nightProportion);
        }

        // ==========================================
        // --- INFRASTRUCTURE RENDERING PASS --------
        // ==========================================

        solidShader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(solidShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);

        if (textures.get(skyTextureKey) != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            textures.get(skyTextureKey).bind(gl); 
            solidShader.setInt(gl, "skyTexture", 2);
        }

        for (List<InfrastructureObject> objects : infraCache.values()) {
            for (InfrastructureObject obj : objects) {
                
                float dx = obj.position.x - currentPos.x;
                float dy = obj.position.y - currentPos.y;
                float dz = obj.position.z - currentPos.z;
                float distSq = dx*dx + dy*dy + dz*dz;
                
                float dotProduct = dx * camForward.x + dy * camForward.y + dz * camForward.z;
                if (dotProduct < -12.0f - obj.boundingRadius) continue;

                // Batches are culled by their bounds, since their anchor is only the middle of their contents
                                if (obj.type == InfrastructureObject.Type.BATCH) {
                    float reach = maxFloraRenderDistance + obj.boundingRadius;
                    if (distSq > reach * reach
                            || !frustum.intersectsSphere(obj.position.x, obj.position.y, obj.position.z, obj.boundingRadius)) {
                        continue;
                    }
                    obj.render(gl, ambientLight, nightProportion, signModelsByNation, postModelsByNation, null, 0, 0);
                    continue;
                }

                // Frustum / Distance Culling
                if (distSq > maxFloraDistSq) continue;

                                if (obj.type == InfrastructureObject.Type.SIGN) {
                    if (distSq > SIGN_DRAW_DISTANCE * SIGN_DRAW_DISTANCE
                            || !frustum.intersectsSphere(obj.position.x, obj.position.y + 30.0f, obj.position.z, 45.0f)) {
                        continue;
                    }
                    signboardShader.use(gl);
                    gl.glUniformMatrix4fv(
                        gl.glGetUniformLocation(signboardShader.getID(), "skyRotation"),
                        1,
                        false,
                        skyRotation.toFloatArrayForGLSL(),
                        0
                    );
                    if (textures.get(skyTextureKey) != null) {
                        gl.glActiveTexture(GL3.GL_TEXTURE2);
                        textures.get(skyTextureKey).bind(gl);
                        signboardShader.setInt(gl, "skyTexture", 2);
                    }
                }
                
                // --- NEW: Fetch Atlas Data for this specific sign's nation ---
                Texture atlas = nationAtlases.get(obj.nationId);
                int atlasSize = nationAtlasSizes.getOrDefault(obj.nationId, 1);
                int writingDir = nationDirections.getOrDefault(obj.nationId, 0);
                
                // Pass the new variables to the object
                obj.render(gl, ambientLight, nightProportion, signModelsByNation, postModelsByNation, atlas, atlasSize, writingDir);
            }
        }
        
                // --- CREATURES ---
        organismManager.update((float) deltaTime, currentPos.x, currentPos.z);
        float[] sunTintForCreatures = worldArt.palette().sunTint;
        float daylight = 1.0f - nightProportion;
        organismManager.render(gl, viewProjection, frustum, camera.getPosition(), sunPos,
                new float[] { sunTintForCreatures[0] * daylight, sunTintForCreatures[1] * daylight, sunTintForCreatures[2] * daylight },
                ambientLight, skyRotation, textures.get(skyTextureKey), (float) elapsedTime);

        inhabitants.update((float) deltaTime, currentPos.x, currentPos.z);
        inhabitants.render(gl, viewProjection, frustum, camera.getPosition(), sunPos,
                new float[] { sunTintForCreatures[0] * daylight, sunTintForCreatures[1] * daylight, sunTintForCreatures[2] * daylight },
                ambientLight, skyRotation, textures.get(skyTextureKey), (float) elapsedTime);

        // --- INSTANCED GRASS PASS ---
        if (totalGrassInstances > 0) {
            gl.glDisable(GL.GL_CULL_FACE); 

            grassShader.use(gl);
            
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(grassShader.getID(), "view"), 1, false, camera.getViewMatrix().toFloatArrayForGLSL(), 0);
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(grassShader.getID(), "projection"), 1, false, camera.getPerspectiveMatrix().toFloatArrayForGLSL(), 0);
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(grassShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
            
            Vector3 camPos1 = camera.getPosition();
            gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "cameraPos"), camPos1.x, camPos1.y, camPos1.z);
            gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "ambientLight"), ambientLight.x, ambientLight.y, ambientLight.z);
            gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "sunColour"), 1.0f, 0.95f, 0.95f); 
            gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "skyColour"), skyColour.x, skyColour.y, skyColour.z);

            gl.glUniform1i(gl.glGetUniformLocation(grassShader.getID(), "worldSeed"), (int)(worldSeed & 0xFFFF));

            Vector3 camPosForSun = camera.getPosition();
            Vector3 direction = new Vector3(sunPos.x - camPosForSun.x, sunPos.y - camPosForSun.y, sunPos.z - camPosForSun.z);
            float len = (float)Math.sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z);
            if (len > 0.0f) {
                direction = new Vector3(direction.x / len, direction.y / len, direction.z / len);
            }
            gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "sunDir"), direction.x, direction.y, direction.z);
            gl.glUniform1f(gl.glGetUniformLocation(grassShader.getID(), "time"), (float)elapsedTime);

            if (textures.get(skyTextureKey) != null) {
                gl.glActiveTexture(GL3.GL_TEXTURE2);
                textures.get(skyTextureKey).bind(gl);
                grassShader.setInt(gl, "skyTexture", 2);
            }

            gl.glActiveTexture(GL3.GL_TEXTURE0);
            textures.get("grass_atlas").bind(gl);
                        grassShader.setInt(gl, "grassTexture", 0);
            float[] dry = worldArt.palette().grassDry, lush = worldArt.palette().grassLush;
            grassShader.setVec3(gl, "u_GrassDry", new Vector3(dry[0], dry[1], dry[2]));
            grassShader.setVec3(gl, "u_GrassLush", new Vector3(lush[0], lush[1], lush[2]));

            gl.glBindVertexArray(grassVAO);
            gl.glDrawArraysInstanced(GL3.GL_TRIANGLES, 0, 6, totalGrassInstances);
            gl.glBindVertexArray(0);

            gl.glEnable(GL.GL_CULL_FACE); 
        }

        // --- OCEAN PASS ---
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        Vector3 camPos = camera.getPosition();
        float waterCoverageSize = PHYSICAL_CHUNK_SIZE * VIEW_DISTANCE * 2.0f;

        Matrix4 waterMatrix = Matrix4Transform.translate(camPos.x, seaLevelHeight - 0.05f, camPos.z);
        waterMatrix = Matrix4.multiply(waterMatrix, Matrix4Transform.scale(waterCoverageSize, 1.0f, waterCoverageSize));

        waterShader.use(gl);
                waterShader.setFloat(gl, "seaLevelHeight", seaLevelHeight);
        waterShader.setFloat(gl, "nearPlane", currentNearPlane);
        waterShader.setFloat(gl, "farPlane", FAR_PLANE);
        waterShader.setVec2(gl, "windowSize", new Vector2((float)currentWidth, (float)currentHeight));

        waterShader.setVec3(gl, "sunPos", sunPos);
                // The glint on the water is the sun's own colour
        float[] sunTint = worldArt.palette().sunTint;
        waterShader.setVec3(gl, "lightSpecular", new Vector3(sunTint[0], sunTint[1], sunTint[2]));
        
        waterShader.setVec3(gl, "matSpecular", waterMaterial.getSpecular());
        waterShader.setFloat(gl, "matShininess", waterMaterial.getShininess());

        waterShader.setVec3(gl, "ambientLight", ambientLight);
        waterShader.setVec3(gl, "skyColour", skyColour);
        float[] shallow = worldArt.palette().seaShallowTint();
        float[] deepOcean = worldArt.palette().seaDeepOcean();
        waterShader.setVec3(gl, "u_ShallowTint", new Vector3(shallow[0], shallow[1], shallow[2]));
        waterShader.setVec3(gl, "u_DeepOceanColour", new Vector3(deepOcean[0], deepOcean[1], deepOcean[2]));
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(waterShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);

        if (textures.get(skyTextureKey) != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            textures.get(skyTextureKey).bind(gl);
            waterShader.setInt(gl, "skyTexture", 2);
        }

        gl.glActiveTexture(GL3.GL_TEXTURE1);
        gl.glBindTexture(GL3.GL_TEXTURE_2D, depthTexture[0]);
        waterShader.setInt(gl, "terrainDepthTexture", 1);

                waterShader.setFloat(gl, "time", (float)elapsedTime);
        waterShader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        gl.glActiveTexture(GL3.GL_TEXTURE3);
                gl.glBindTexture(GL3.GL_TEXTURE_2D, waveMapTexture);
        waterShader.setInt(gl, "waveMap", 3);
        float chunk = PHYSICAL_CHUNK_SIZE;
        float origin = regionalManager.waveMapOrigin(chunk);
        waterShader.setVec2(gl, "waveMapOrigin", new Vector2(origin, origin));
                waterShader.setFloat(gl, "waveMapWidth", regionalManager.waveMapWidth(chunk));
        waterShader.setFloat(gl, "waveMapTexel", regionalManager.waveMapWidth(chunk) / regionalManager.waveMapResolution());
        // Wave crests can be seen from either side
        gl.glDisable(GL.GL_CULL_FACE);

        waterPlaneModel.setModelMatrix(waterMatrix);
                waterPlaneModel.render(gl, ambientLight, nightProportion);

                gl.glEnable(GL.GL_CULL_FACE);
        gl.glDisable(GL.GL_BLEND);

        // --- THE PLAYER'S OWN BODY, drawn last and nearest ---
        playerBody.update((float) deltaTime, camera.getPosition(), swimming);
        float[] deep = worldArt.palette().seaShallowTint();
        float daylightOnBody = 1.0f - nightProportion;
        playerBody.render(gl, camera, (float) currentWidth / Math.max(1, currentHeight), sunPos,
                new float[] { sunTint[0] * daylightOnBody, sunTint[1] * daylightOnBody, sunTint[2] * daylightOnBody },
                ambientLight, skyRotation, textures.get(skyTextureKey), waterSurfaceHere, deep);
    }

    private double getSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }

    public static float precalculateSeaLevel(long seed, float totalRegionWidth, PerlinNoise noise) {
        java.util.Random rand = new java.util.Random(seed);
        float waterProportion = 0.6f + rand.nextFloat() * 0.1f; 
        float halfRegion = totalRegionWidth / 2.0f;
        
        int totalSamples = 4000;
        java.util.ArrayList<Float> heightSamples = new java.util.ArrayList<>(totalSamples);

        for (int i = 0; i < totalSamples; i++) {
            float sampleX = (rand.nextFloat() * totalRegionWidth) - halfRegion;
            float sampleZ = (rand.nextFloat() * totalRegionWidth) - halfRegion;
            
            float h = TerrainMesh.getLayeredHeight(sampleX, sampleZ, noise);
            heightSamples.add(h);
        }

        java.util.Collections.sort(heightSamples);
        int targetIndex = (int)(heightSamples.size() * waterProportion);
        if (targetIndex >= heightSamples.size()) targetIndex = heightSamples.size() - 1;
        
        float calculatedSeaLevel = heightSamples.get(targetIndex);
        return calculatedSeaLevel;
    }

    public void setMinimap(MapPanel minimap) {
        this.minimap = minimap;
        if (!IS_DEBUG_MODE_ACTIVE) return;
        // The first heatmap takes seconds to evaluate; build it off the UI thread so the
        // loading screen keeps animating
        FactorName initialFactor = currentDebugFactor;
        RegionalFactor factor = factorFor(initialFactor);
        if (factor == null) {
            assignHeatmapToMinimap(initialFactor);
            return;
        }
        Thread heatmapThread = new Thread(() -> {
            BufferedImage snapshot = this.regionalManager.generateHeatmap(
                TOTAL_REGION_WIDTH, PHYSICAL_CHUNK_SIZE, factor, initialFactor.toString());
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (currentDebugFactor != initialFactor) return;
                minimap.setHeatmapOverlay(snapshot);
                minimap.setHeatmapVisible(true);
                minimap.setHeatmapName(initialFactor.toString());
            });
        }, "initial-heatmap");
        heatmapThread.setDaemon(true);
        heatmapThread.start();
    }

    public void assignHeatmapToMinimap(FactorName currentDebugFactor) {
        if (this.IS_DEBUG_MODE_ACTIVE) {
            
            // 1. Intercept the Nation Territory view to bypass continuous factor generation
            if (currentDebugFactor == FactorName.NATION_TERRITORIES) {
                if (this.nationManager != null) {
                    // Tell the Manager to upscale the render to match the chunk layout
                    int chunkRes = (int)(TOTAL_REGION_WIDTH / PHYSICAL_CHUNK_SIZE); 
                    BufferedImage nationSnapshot = this.nationManager.generateNationOverlay(chunkRes);
                    
                    minimap.setHeatmapOverlay(nationSnapshot);
                    minimap.setHeatmapVisible(true);
                    minimap.setHeatmapName("NATION_TERRITORIES");
                }
                return; // Exit method early
            }

            // Bird's-eye layers are pre-rendered in the background and cover the whole map
            if (currentDebugFactor == FactorName.ROAD_NETWORK
                    
                    || currentDebugFactor == FactorName.BUILDINGS) {
                BufferedImage layer = switch (currentDebugFactor) {
                    case ROAD_NETWORK -> this.roadNetworkMap;
                    
                    default -> this.buildingMap;
                };
                BufferedImage detail = switch (currentDebugFactor) {
                    case ROAD_NETWORK -> this.roadNetworkMapDetail;
                    
                    default -> this.buildingMapDetail;
                };
                if (layer != null) {
                    minimap.setHeatmapName(currentDebugFactor.toString());
                    minimap.setFullMapOverlay(layer, detail);
                } else {
                    // Shown until the background render finishes and swaps the real layer in
                    minimap.setHeatmapName(currentDebugFactor + " (generating...)");
                    minimap.setFullMapOverlay(null, null);
                }
                minimap.setHeatmapVisible(true);
                return;
            }

            // 2. Otherwise, look up and evaluate standard noise/growth parameters
            RegionalFactor targetFactor = factorFor(currentDebugFactor);
            
            if (targetFactor != null) {
                BufferedImage rawSnapshot = this.regionalManager.generateHeatmap(
                    TOTAL_REGION_WIDTH, PHYSICAL_CHUNK_SIZE, targetFactor, currentDebugFactor.toString()
                );
                minimap.setHeatmapOverlay(rawSnapshot);
                minimap.setHeatmapVisible(true);
                minimap.setHeatmapName(currentDebugFactor.toString());
            }
        }
    }

    private RegionalFactor factorFor(FactorName factorName) {
            return switch (factorName) {
                case GRASS_ABUNDANCE -> this.grassAbundanceFactor;
                case GRASS_HEIGHT -> this.grassHeightFactor;
                case GRASS_COLOUR -> this.grassColourFactor;
                case LEAF_COLOUR -> this.leafColourFactor;
                case GRASS_TEMPERATURE_PREFERENCE -> this.grassTemperateFactor;
                case LEAF_TEMPERATURE_PREFERENCE -> this.leafTemperateFactor;
                case MOISTURE -> this.grassMoistureFactor;
                case GRASS_PATCH_NOISE -> this.grassPatchNoiseFactor;
                case GRASS_HEIGHT_NOISE -> this.grassHeightNoiseFactor;
                case GRASS_COLOUR_NOISE -> this.grassColourNoiseFactor;
                                case LEAF_COLOUR_NOISE -> this.leafColourNoiseFactor;
                case SOIL_COLOUR_VARIANT_A -> this.soilVariantAFactor;
                case SOIL_COLOUR_VARIANT_B -> this.soilVariantBFactor;
                case TREE_1_ABUNDANCE -> this.speciesConfigs[0].abundanceFactor;
                case TREE_2_ABUNDANCE -> this.speciesConfigs[1].abundanceFactor;
                case TREE_3_ABUNDANCE -> this.speciesConfigs[2].abundanceFactor;
                case TREE_4_ABUNDANCE -> this.speciesConfigs[3].abundanceFactor;
                case SHRUB_1_ABUNDANCE -> this.speciesConfigs[4].abundanceFactor;
                case SHRUB_2_ABUNDANCE -> this.speciesConfigs[5].abundanceFactor;
                case SHRUB_3_ABUNDANCE -> this.speciesConfigs[6].abundanceFactor;
                                case SHRUB_4_ABUNDANCE -> this.speciesConfigs[7].abundanceFactor;
                case ORGANISM_1_HABITAT -> organismManager.habitat(0);
                case ORGANISM_2_HABITAT -> organismManager.habitat(1);
                case ORGANISM_3_HABITAT -> organismManager.habitat(2);
                case ORGANISM_4_HABITAT -> organismManager.habitat(3);
                case ORGANISM_5_HABITAT -> organismManager.habitat(4);
                case ORGANISM_6_HABITAT -> organismManager.habitat(5);
                case ORGANISM_7_HABITAT -> organismManager.habitat(6);
                case ORGANISM_8_HABITAT -> organismManager.habitat(7);
                default -> null; 
            };
    }

    public void setCompassHUD(CompassHUD compassHUD) {
        this.compassHUD = compassHUD;
    }

    private Model makeSkybox(GL3 gl, String fragmentPath, Texture skyTexture) {
        String name = "skybox";
        Mesh mesh = new Mesh(gl, InsideSphere.vertices.clone(), InsideSphere.indices.clone());
        Matrix4 modelMatrix = Matrix4Transform.scale(2400.0f, 2400.0f, 2400.0f);
        Shader shader = new Shader(gl, "assets/shaders/vs_standard.txt", fragmentPath);
        Material material = new Material(new Vector3(0f, 0f, 0f), new Vector3(0f, 0f, 0f));
        material.setDiffuseMap(skyTexture);
        
        Renderer renderer = new Renderer();
        return new Model(name, mesh, modelMatrix, shader, material, renderer, lights, camera);
    }

    public FactorName getCurrentDebugFactor() {
      return currentDebugFactor;
    }

    public int getTotalNationsCount() {
        return this.totalNationsCount;
    }

    public void setTelepot(float teleportX, float teleportZ) {
        this.isToTeleport = true;
        this.teleportX = teleportX;
        this.teleportZ = teleportZ;
    }

    private Texture createAlphabetAtlas(GL3 gl, int alphabetId) {
        try {
            File dir = new File(RunFiles.ALPHABETS_DIR, "alphabet" + alphabetId);
            File[] glyphFiles = dir.listFiles((d, name) -> name.startsWith("glyph_") && name.endsWith(".png"));
            
            if (glyphFiles == null || glyphFiles.length == 0) return null;
            
            // Assume all glyphs are same resolution (e.g., 64x64)
            BufferedImage firstGlyph = ImageIO.read(glyphFiles[0]);
            int gWidth = firstGlyph.getWidth();
            int gHeight = firstGlyph.getHeight();
            
            BufferedImage atlasImage = new BufferedImage(gWidth * glyphFiles.length, gHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = atlasImage.createGraphics();
            
            for (int i = 0; i < glyphFiles.length; i++) {
                File gFile = new File(dir, "glyph_" + i + ".png");
                if (gFile.exists()) {
                    BufferedImage glyph = ImageIO.read(gFile);
                    g2d.drawImage(glyph, i * gWidth, 0, null);
                }
            }
            g2d.dispose();

            try {
                // Kept with this run's other generated images, which are wiped on the next launch
                File outputDebugFile = new File(WorldArtGenerator.OUTPUT_DIR, "debug_atlas_nation_" + alphabetId + ".png");
                ImageIO.write(atlasImage, "png", outputDebugFile);
                System.out.println("Saved debug atlas to: " + outputDebugFile.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
}
            
            return TextureLibrary.createTextureFromBufferedImage(gl, atlasImage);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Returns the total number of glyphs available in a nation's character atlas.
     * * @param nationId The ID of the nation (1-indexed)
     * @return The number of glyphs in the nation's atlas, or a fallback default (e.g. 10) if not found.
     */
    public int getNationAtlasSize(int nationId) {
        if (nationAtlasSizes != null && nationAtlasSizes.containsKey(nationId)) {
            int count = nationAtlasSizes.get(nationId);
            return count > 0 ? count : 10; // Ensure we don't return 0 to prevent division by zero
        }
        return 10; // Fallback default if atlas size hasn't been mapped yet
    }
}