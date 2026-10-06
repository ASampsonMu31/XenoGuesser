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
    // The pod the player landed in, standing where each round starts
    private final LandingPod landingPod = new LandingPod();
    private RockField rockField;
    // Everything solid: buildings, fences, rails, signs, trees, the pod, creatures and people
    private final Collision collision = new Collision();
    private static final Object PLAYER = "player";
    private static final float PLAYER_RADIUS = 3.0f;   // must stay over 1.45 x NEAR_PLANE
    // Developer aid: -Dxenoguesser.menushot photographs the pod for the main menu's background
    private static final boolean MENU_SHOT = System.getProperty("xenoguesser.menushot") != null;
    private boolean hideFirstPerson;
    private boolean swimming;
    private float waterSurfaceHere;
    // Eyes this far above the water when swimming; deeper than this and the player swims
    private static final float SWIM_EYE_HEIGHT = 2.4f;
    // The scene's near plane: far enough out for depth precision on land, close in when the
    // eyes are just above the water so the surface around the swimmer isn't cut away
    // The near plane sits close enough that the player's solid circle always keeps it off
    // walls: its far corners are at most ~1.45x the near distance from the eye (45 degree
    // view, up to 21:9 screens), inside PLAYER_RADIUS, so nothing can be cut away in front
    private static final float NEAR_PLANE = 2.0f, SWIMMING_NEAR_PLANE = 1.5f, FAR_PLANE = 3000.0f;
    private float currentNearPlane = NEAR_PLANE;
    private int waveMapTexture;

    private Model skyModel;
    // The clouds and rain
    private Weather weather;
    // Grass dies back from this many degrees above the water's freezing point, and is gone by
    // this many below it
    private static final float GRASS_FROST_START = 6f, GRASS_FROST_DEATH = 6f;
    // The temperature (Celsius) the planet's water freezes at, so below which it snows
    private float freezingPoint;

    private Shader terrainShader;
    private Renderer terrainRenderer;
    private Material terrainMaterial;
    private Matrix4 globalModelMatrix;

    private Shader solidShader;
    private Shader glassShader;
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
    // The sun: its declination (radians north of the equator it stands overhead) and the
    // longitude where it is noon
    private double sunDeclination, noonLongitude;

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
    // How many grass blades are in the instance buffer (thinned with distance), as drawn
    private int drawnGrassInstances = 0;
    
    private final int GRASS_VIEW_DISTANCE = 21;
        private final int MAX_GRASS_LIMIT = 1200;
    private final float GRASS_BASE_ABUNDANCE;

    // --- Optimized Zero-Allocation VRAM Streaming Fields ---
    private java.nio.FloatBuffer persistentGrassBuffer;
    private int currentGrassGPUCapacityFloats = 0;

    // The HUD drawn over the 3D view, and what each picture last showed
    private final HudOverlay hud = new HudOverlay();
    private int hudScoreVersion = -1, hudFps = -1;
    private float hudScale = -1f;
    private MapPanel.MapSize hudMapSize;
    private volatile boolean menuOpen;
    private volatile boolean compassWanted;

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
    private Overlay currentDebugFactor;

    /** One of the map's overlays: a factor, or (for the animals) one species' population. */
    private record Overlay(FactorName factor, int species) {
        static Overlay of(FactorName factor) {
            return new Overlay(factor, -1);
        }
    }


    private volatile boolean isToTeleport = false;
    // Developer teleport: loading everything round the new place before play goes on
    private boolean teleportLoading;
    private long teleportStarted;
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
    private volatile BirdsEyeMaps birdsEyeMaps;

    // Cities thin out trees and draw fewer distant details to pay for their extra buildings
    private static final float URBAN_FLORA_REDUCTION = 0.75f;
    private static final float SIGN_DRAW_DISTANCE = 1600.0f;
    private Map<Integer, Model> signModelsByNation;
    // Per nation: GL texture ids of portraits then full-length pictures of its people
    private int[][] peoplePictures;
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
    private static final long LOADING_GRASS_BUDGET_NANOS = 45_000_000L;
        private long grassBudgetNanos = 0;
    // Between rounds the new surroundings are built in slices of this long per frame
    private static final long ROUND_LOADING_BUDGET_NANOS = 45_000_000L;
    private boolean roundLoading = false;
    private long roundChunkDeadline;
    private long roundLoadingStarted;
    private int roundLoadingFrames;
    private static final float LEAF_DARK_SCALE = 0.65f;
    private static final float LEAF_LIGHT_SCALE = 1.3f;

    private Map<Integer, Texture> nationAtlases;
    private Map<Integer, Integer> nationAtlasSizes;
    private Map<Integer, Integer> nationDirections;

    /** The overlays the map can colour the land by. */
    public enum FactorName {
      TEMPERATURE("Temperature"),
      RAINFALL("Rainfall"),
      SOIL_COLOUR("Soil colour"),
      ANIMAL_POPULATION("Animal population"),
      FLORA("Flora"),
      GRASS_ABUNDANCE("Grass abundance"),
      GRASS_HEIGHT("Grass height"),
      GRASS_COLOUR("Grass colour"),
      WEALTH("Wealth"),
      NATIONS("Nations");

      private final String label;

      FactorName(String label) {
        this.label = label;
      }

      /** As the map's choices list it. */
      public String label() {
        return label;
      }
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
        // Developer aid: -Dxenoguesser.minimap=NATIONS,ROADS,CONTOURS starts with that gradient
        // map chosen and those layers ticked
        this.currentDebugFactor = null;
        String startingMap = System.getProperty("xenoguesser.minimap");
        if (startingMap != null) {
            for (String part : startingMap.split(",")) {
                try {
                    FactorName factor = FactorName.valueOf(part.trim());
                    // (the animals' population is shown a species at a time: the first, to start with)
                    this.currentDebugFactor = new Overlay(factor, factor == FactorName.ANIMAL_POPULATION || factor == FactorName.FLORA ? 0 : -1);
                } catch (IllegalArgumentException notGradient) {
                    try {
                        startingLayers.add(MapPanel.Layer.valueOf(part.trim()));
                    } catch (IllegalArgumentException notLayer) {
                        System.err.println("Unknown map: " + part);
                    }
                }
            }
        }
        
        this.camera.setPosition(new Vector3(0f, 5f, 15f));
        this.camera.setTarget(new Vector3(0f, 0f, 0f));

        java.util.Random seedRand = new java.util.Random(worldSeed);
        this.planetAxialTiltDegrees = 20.0f + seedRand.nextFloat() * 6.0f;
        
        this.regionalManager = new RegionalGenerationManager(worldSeed, TOTAL_REGION_WIDTH, seaLevelHeight);
        this.weather = new Weather(worldSeed, seaLevelHeight);
        this.freezingPoint = -5f + 10f * new java.util.Random(worldSeed * 41L + 19L).nextFloat();
        this.regionalManager.precalculateWaterDistanceField(TOTAL_REGION_WIDTH, this.worldNoise, PHYSICAL_CHUNK_SIZE);

        float GRASS_TEMP_MEAN = 0.4f;
        float GRASS_TEMP_STD_DEV = 0.5f;
        int GRASS_WATER_MEAN = 0;
        float GRASS_WATER_STD_DEV = 80.0f;
        float GRASS_DENSITY_SCALE = 2e-5f;
        float GRASS_VARIATION_SCALE = 1e-5f;
                this.GRASS_BASE_ABUNDANCE = 1050f;

        // Grass likes it mild, and dies back where it's cold enough for snow: from a few degrees
        // above the water's freezing point down to several below, it thins out to almost nothing
        // (and what's left is dry and dead, see grassColourFactor)
        RegionalFactor mildness = this.regionalManager.createTemperaturePreference(GRASS_TEMP_MEAN, GRASS_TEMP_STD_DEV);
        this.grassTemperateFactor = new RegionalFactor(1.0f, (cx, cz, x, z) -> {
            float celsius = -30f + 70f * regionalManager.temperatureMap.evaluate(cx, cz, x, z);
            float t = Math.max(0f, Math.min(1f, (celsius - (freezingPoint - GRASS_FROST_DEATH)) / (GRASS_FROST_DEATH + GRASS_FROST_START)));
            return mildness.evaluate(cx, cz, x, z) * t * t * (3f - 2f * t);
        });
        this.grassMoistureFactor = this.regionalManager.createWaterPreference(GRASS_WATER_MEAN, GRASS_WATER_STD_DEV);
        this.grassPatchNoiseFactor = this.regionalManager.createNoiseMap(GRASS_DENSITY_SCALE);
        // The rainfall follows the moisture (with the temperature)
        this.regionalManager.setMoistureMap(this.grassMoistureFactor);
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
            RegionalFactor suited = new RegionalFactor.Builder()
                .setWeight(6.0f)        // Drop this to 1.0f so it NEVER artificially blows up low scores
                .setPowerCurve(6.0f)    // Crank the power curve up to 5.0 or 6.0 to make it brutal
                .addFactor(sc.tempFactor, 0.30f)
                .addFactor(this.grassMoistureFactor, 0.20f)
                .addFactor(sc.patchNoiseFactor, 0.50f)
                .build();
            // ...and only in its home region, as the animals (see OrganismSpecies): a plant suited
            // to the cold north needn't grow in the cold south too
            RegionalFactor home = this.regionalManager.createNoiseMap(OrganismSpecies.HOME_SCALE_LOW
                    + new Random(worldSeed * 613L + s).nextFloat() * OrganismSpecies.HOME_SCALE_RANGE);
            float homeStrength = OrganismSpecies.homeStrength(new Random(worldSeed * 1993L + s));
            sc.abundanceFactor = new RegionalFactor(1.0f, (cx, cz, x, z) -> {
                float t = Math.max(0f, Math.min(1f, (home.evaluate(cx, cz, x, z) - OrganismSpecies.HOME_FROM)
                        / (OrganismSpecies.HOME_TO - OrganismSpecies.HOME_FROM)));
                return suited.evaluate(cx, cz, x, z) * (1f - homeStrength + homeStrength * t * t * (3f - 2f * t));
            });
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
        collision.addSource(infraManager::obstaclesNear);
        collision.addSource(landingPod::obstaclesNear);
        collision.addSource(this::treesNear);
        collision.addSource((x, z, reach, sink) -> { if (rockField != null) rockField.obstaclesNear(x, z, reach, sink); });
        organismManager.setCollision(collision);
        inhabitants.setCollision(collision);
        this.organismManager.setUrbanness((x, z) -> infraManager.getUrbanness(x, z));
        this.organismManager.setRoads((x, z, clearance) -> infraManager.isRoadLocation(x, z, clearance));
        startBirdsEyeMapRendering();
    }

    /**
     * A nation's name in its own writing, as the map shows it: two to four of its letters
     * (the same each time for the same world) in white with a dark edge, laid out in its
     * direction of writing. Null if its letters can't be read.
     */
    private BufferedImage nationNameImage(int nationId) {
        int alphabetId = infraManager.scripts().alphabet(nationId);
        java.io.File dir = new java.io.File(RunFiles.ALPHABETS_DIR, "alphabet" + alphabetId);
        java.io.File[] files = dir.listFiles((d, name) -> name.startsWith("glyph_") && name.endsWith(".png"));
        if (files == null || files.length < 2) return null;
        java.util.Random rand = new java.util.Random(worldSeed * 131L + nationId * 7919L);
        int letters = 2 + rand.nextInt(3);
        int direction = infraManager.scripts().direction(nationId);
        boolean vertical = direction >= 2, backwards = direction == 1 || direction == 3;
        int cell = MapPanel.NATION_NAME_GLYPH, pad = 3, step = cell - 2;
        int width = (vertical ? cell : step * (letters - 1) + cell) + pad * 2;
        int height = (vertical ? step * (letters - 1) + cell : cell) + pad * 2;
        // The letters' ink, then a dark edge round it and the ink in white on top
        boolean[] ink = new boolean[width * height];
        try {
            for (int i = 0; i < letters; i++) {
                // Glyph 0 is a space
                int letter = 1 + rand.nextInt(files.length - 1);
                BufferedImage glyph = javax.imageio.ImageIO.read(new java.io.File(dir, "glyph_" + letter + ".png"));
                if (glyph == null) return null;
                int slot = backwards ? letters - 1 - i : i;
                int ox = pad + (vertical ? 0 : slot * step), oy = pad + (vertical ? slot * step : 0);
                for (int y = 0; y < cell; y++) {
                    for (int x = 0; x < cell; x++) {
                        int gx = x * glyph.getWidth() / cell, gy = y * glyph.getHeight() / cell;
                        if ((glyph.getRGB(gx, gy) & 0xFF) < 110) ink[(oy + y) * width + ox + x] = true;
                    }
                }
            }
        } catch (java.io.IOException e) {
            return null;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (ink[y * width + x]) {
                    image.setRGB(x, y, 0xFFFAF8F0);
                    continue;
                }
                boolean edge = false;
                for (int dy = -2; dy <= 2 && !edge; dy++) {
                    for (int dx = -2; dx <= 2 && !edge; dx++) {
                        int nx = x + dx, ny = y + dy;
                        edge = nx >= 0 && ny >= 0 && nx < width && ny < height && ink[ny * width + nx];
                    }
                }
                if (edge) image.setRGB(x, y, 0xD8141218);
            }
        }
        return image;
    }

    private void startBirdsEyeMapRendering() {
        Thread mapThread = new Thread(() -> {
            BirdsEyeMaps maps = new BirdsEyeMaps(BIRDS_EYE_MAP_RESOLUTION, TOTAL_REGION_WIDTH, seaLevelHeight, worldNoise);
            birdsEyeMaps = maps;
            long startTime = System.currentTimeMillis();

            BufferedImage[] baseMap = maps.renderBaseMap();
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) {
                    minimap.setBaseMapDetail(baseMap[1]);
                }
            });

            

            // The nations' names, in their own writing, where their largest land is
            List<Object[]> labels = new ArrayList<>();
            for (float[] place : maps.nationLabelPlaces(nationManager)) {
                BufferedImage name = nationNameImage((int) place[0]);
                if (name != null) labels.add(new Object[] { name, place[1], place[2], place[3], place[4] > 0.5f });
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) minimap.setNationLabels(labels);
            });
            // Drawn in tiles when zoomed in close
            java.util.Map<MapPanel.Layer, MapTiles.Renderer> layerTiles = java.util.Map.of(
                    MapPanel.Layer.CONTOURS, maps.contourTiles(), MapPanel.Layer.ROADS, maps.roadTiles(infraManager),
                    MapPanel.Layer.BUILDINGS, maps.buildingTiles(infraManager), MapPanel.Layer.SHOPS, maps.shopTiles(infraManager));
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) minimap.setTileRenderers(maps.baseTiles(), layerTiles);
            });
            showLayer(MapPanel.Layer.CONTOURS, maps.renderContourLayer());
            showLayer(MapPanel.Layer.ROADS, maps.renderRoadLayer(infraManager));
            showLayer(MapPanel.Layer.BUILDINGS, maps.renderBuildingLayer(infraManager));
            showLayer(MapPanel.Layer.SHOPS, maps.renderShopLayer(infraManager));

            // Then every overlay, side by side, so choosing one later is instant (the round doesn't
            // start until they're done: see advanceLoading)
            allOverlays().parallelStream().forEach(this::overlayImage);
            mapsReady = true;
            System.out.printf("[MINIMAP] Bird's-eye maps ready in %d ms%n", System.currentTimeMillis() - startTime);
        }, "birds-eye-map-renderer");
        mapThread.setDaemon(true);
        mapThread.start();
    }

    /** Hands a finished layer's images to the map. */
    private void showLayer(MapPanel.Layer layer, BufferedImage[] images) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (minimap != null) minimap.setLayerImages(layer, images);
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
    // The bird's-eye map and all its overlays are done; when the loading screen began waiting for them
    private volatile boolean mapsReady;
    private long loadingMapsFrom;

    private boolean advanceLoading() {
        if (loadingStep == 0) {
            loading.begin(LoadingProgress.Stage.GPU_UPLOAD);
            initialiseCore();
            loadingStep++;
            loading.begin(LoadingProgress.Stage.FLORA);
            return false;
        }
        // Trees are built a detail level at a time, as many as fit in a slice of each frame,
        // so the loading screen keeps moving
        int floraSteps = NUM_SPECIES * FLORA_VARIATIONS * 3;
        if (loadingStep - 1 < floraSteps) {
            long sliceEnd = System.nanoTime() + LOADING_GRASS_BUDGET_NANOS;
            do {
                int floraStep = loadingStep - 1;
                int tree = floraStep / 3;
                initialiseFloraVariation(tree / FLORA_VARIATIONS, tree % FLORA_VARIATIONS, floraStep % 3);
                loadingStep++;
            } while (loadingStep - 1 < floraSteps && System.nanoTime() < sliceEnd);
            loading.report((loadingStep - 1) / (float) floraSteps);
            if (loadingStep - 1 == floraSteps) loading.begin(LoadingProgress.Stage.TERRAIN);
            return false;
        }
        if (loadingStep == floraSteps + 1) {
            finishInitialise();
            loadingStep++;
            return false;
        }
        // Distant grass is seeded within a time budget per frame so the loading screen keeps moving
        int grassChunksTotal = (GRASS_VIEW_DISTANCE * 2 + 1) * (GRASS_VIEW_DISTANCE * 2 + 1);
        if (grassCache.size() < grassChunksTotal || !surroundingsBuilt()) {
            grassBudgetNanos = LOADING_GRASS_BUDGET_NANOS;
            updateVisibleChunks(false);
            grassBudgetNanos = 0;
            loading.report(grassCache.size() / (float) grassChunksTotal);
            return false;
        }
        // The map, its layers and every overlay are ready before the round starts
        if (!mapsReady) {
            if (loadingMapsFrom == 0L) {
                loadingMapsFrom = System.currentTimeMillis();
                loading.begin(LoadingProgress.Stage.MAPS);
            }
            return false;
        }
        // Every rock round the landing site, near and far, is in place before the round starts
        if (rockField != null) {
            Vector3 at = camera.getPosition();
            rockField.update(gl, at.x, at.z, true);
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
        
        // The world is drawn at most at full HD (as many pixels), then scaled up to fill a larger
        // screen; the HUD over it is drawn at the screen's own size, so it stays sharp
        this.screenWidth = width;
        this.screenHeight = height;
        float shrink = (float) Math.min(1.0, Math.sqrt(MAX_RENDER_PIXELS / ((double) width * height)));
        this.currentWidth = Math.max(1, Math.round(width * shrink));
        this.currentHeight = Math.max(1, Math.round(height * shrink));
        gl.glViewport(0, 0, width, height);
        
                applyProjection();

        createDepthFramebuffer(gl, currentWidth, currentHeight);
        createSceneFramebuffer(gl);
    }

    // The most pixels the world is drawn at (full HD), and the size of the screen it's shown on
    private static final double MAX_RENDER_PIXELS = 1920.0 * 1080.0;
    private int screenWidth = 1024, screenHeight = 768;
    // Where the world is drawn when it's smaller than the screen (0 when it's drawn straight there)
    private int sceneFbo, sceneColour, sceneDepth;

    /** The framebuffer the world is drawn into when it's drawn smaller than the screen. */
    private void createSceneFramebuffer(GL3 gl) {
        if (sceneFbo != 0) {
            gl.glDeleteFramebuffers(1, new int[] { sceneFbo }, 0);
            gl.glDeleteRenderbuffers(2, new int[] { sceneColour, sceneDepth }, 0);
            sceneFbo = 0;
        }
        if (currentWidth == screenWidth && currentHeight == screenHeight) return;
        int[] ids = new int[2];
        gl.glGenFramebuffers(1, ids, 0);
        sceneFbo = ids[0];
        gl.glGenRenderbuffers(2, ids, 0);
        sceneColour = ids[0];
        sceneDepth = ids[1];
        gl.glBindRenderbuffer(GL3.GL_RENDERBUFFER, sceneColour);
        gl.glRenderbufferStorage(GL3.GL_RENDERBUFFER, GL3.GL_RGBA8, currentWidth, currentHeight);
        gl.glBindRenderbuffer(GL3.GL_RENDERBUFFER, sceneDepth);
        gl.glRenderbufferStorage(GL3.GL_RENDERBUFFER, GL3.GL_DEPTH_COMPONENT24, currentWidth, currentHeight);
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, sceneFbo);
        gl.glFramebufferRenderbuffer(GL3.GL_FRAMEBUFFER, GL3.GL_COLOR_ATTACHMENT0, GL3.GL_RENDERBUFFER, sceneColour);
        gl.glFramebufferRenderbuffer(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, GL3.GL_RENDERBUFFER, sceneDepth);
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
    }

    /** Draws the world (into the smaller framebuffer if there is one, then scaled up onto the screen). */
    private void renderScene(GL3 gl) {
        if (sceneFbo != 0) {
            gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, sceneFbo);
            gl.glViewport(0, 0, currentWidth, currentHeight);
        }
        try {
            render();
        } finally {
            if (sceneFbo != 0) {
                gl.glBindFramebuffer(GL3.GL_READ_FRAMEBUFFER, sceneFbo);
                gl.glBindFramebuffer(GL3.GL_DRAW_FRAMEBUFFER, 0);
                gl.glBlitFramebuffer(0, 0, currentWidth, currentHeight, 0, 0, screenWidth, screenHeight, GL3.GL_COLOR_BUFFER_BIT, GL3.GL_LINEAR);
                gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
                gl.glViewport(0, 0, screenWidth, screenHeight);
            }
        }
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
            setBuilderThreads(false);
            queueNextRound();
        }

        // The next round is built behind the results screen as soon as the guess is made, a
        // slice per frame. Next Round then starts it at once if it's ready; if not, its button
        // shows Loading until it is
        if (minimap != null && minimap.takePrepareRequest() && !roundLoading) {
            startBuildingNextRound(gl);
        }
        if (minimap != null && minimap.isNextRoundRequested()) {
            minimap.clearNextRoundRequest();
            nextRoundWanted = true;
            nextRoundWantedAt = System.currentTimeMillis();
            if (!roundLoading && !nextRoundBuilt) startBuildingNextRound(gl);
        }
        if (nextRoundBuilt && nextRoundWanted) {
            nextRoundBuilt = false;
            nextRoundWanted = false;
            System.out.printf("[ROUND] Next round started %d ms after it was asked for%n", System.currentTimeMillis() - nextRoundWantedAt);
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) minimap.finishRoundLoading();
            });
        }
        if (IS_DEBUG_MODE_ACTIVE && isToTeleport && !roundLoading) {
            isToTeleport = false;
            moveToLocation(teleportX, teleportZ);
            teleportLoading = true;
            teleportStarted = System.currentTimeMillis();
            setBuilderThreads(true);
        }
        if (teleportLoading) {
            grassBudgetNanos = ROUND_LOADING_BUDGET_NANOS;
            updateVisibleChunks(false);
            grassBudgetNanos = 0;
            // (given up on after a while, should something never finish)
            if (surroundingsLoadedHere() || System.currentTimeMillis() - teleportStarted > 30_000) {
                teleportLoading = false;
                setBuilderThreads(false);
                System.out.printf("[TELEPORT] Loaded round %.0f, %.0f in %d ms%n", teleportX, teleportZ, System.currentTimeMillis() - teleportStarted);
            }
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            return;
        }
        if (roundLoading) {
            grassBudgetNanos = ROUND_LOADING_BUDGET_NANOS;
            updateVisibleChunks(false);
            grassBudgetNanos = 0;
            roundLoadingFrames++;
            if (grassCache.size() >= (GRASS_VIEW_DISTANCE * 2 + 1) * (GRASS_VIEW_DISTANCE * 2 + 1) && surroundingsBuilt()) {
                roundLoading = false;
                nextRoundBuilt = true;
                setBuilderThreads(false);
                queueNextRound();
                System.out.printf("[ROUND] Next round built in %d ms over %d frames%n",
                        System.currentTimeMillis() - roundLoadingStarted, roundLoadingFrames);
            }
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            return;
        }

        pumpPrefetch();

        if (minimap != null && minimap.isFullScreenRevealMode()) {
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            return;
        }

        if (MENU_SHOT && loading.isFinished()) menuShot(gl);
        if (SIGN_SHOT && loading.isFinished()) signShot(gl);
        // Developer aid: -Dxenoguesser.vehicleshot (=moving for one on the move, =driveway for one up a
        // driveway, =door for someone coming out of their front door) stands by it two seconds in
        if (VEHICLE_SHOT != null && loading.isFinished()) vehicleShotFrames++;
        if (VEHICLE_SHOT != null && vehicleShotFrames == VEHICLE_SHOT_FRAME + 20) saveFrame(gl, "vehicle_shot.png");
        if (VEHICLE_SHOT != null && loading.isFinished() && vehicleShotFrames == VEHICLE_SHOT_FRAME) {
            Vector3 at = camera.getPosition();
            float[] view = "door".equals(VEHICLE_SHOT) ? inhabitants.doorViewpoint(at.x, at.z)
                    : inhabitants.vehicleViewpoint(at.x, at.z, VEHICLE_SHOT);
            if (view != null) {
                camera.setGroundPosition(view[0], view[1]);
                camera.setTarget(new Vector3(view[0] + view[2] * 50f, at.y - 8f, view[1] + view[3] * 50f));
            }
        }
        renderScene(gl);
        if (!hideFirstPerson) drawHud(drawable);
        advancePreviews(gl);

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

    /**
     * Developer aid for the main menu's background: a minute after the round starts, the
     * view moves out to look back at the landing pod, and a frame without the HUD or the
     * player's body is saved as the menu art.
     */
    private static final boolean SIGN_SHOT = System.getProperty("xenoguesser.signshot") != null;
    private static final String VEHICLE_SHOT = System.getProperty("xenoguesser.vehicleshot");
    // (after this many frames: -Dxenoguesser.vehicleshotframe)
    private static final int VEHICLE_SHOT_FRAME = Integer.getInteger("xenoguesser.vehicleshotframe", 120);
    private int vehicleShotFrames;
    private final java.util.Set<InfrastructureObject> signsShot = new java.util.HashSet<>();
    private Vector3 signShotEye, signShotTarget;

    /**
     * Developer aid: from a little after the round starts, stands in front of one nearby sign
     * after another (those with pictures or titles first) and saves a frame of each.
     */
    private void signShot(GL3 gl) {
        int frame = framesRenderedSinceReady - 40;
        if (frame < 0 || frame > 40 * 8) return;
        if (signShotEye != null) {
            camera.setPosition(signShotEye);
            camera.setTarget(signShotTarget);
        }
        if (frame % 40 == 0) {
            hideFirstPerson = true;
            InfrastructureObject best = null;
            float bestScore = Float.MAX_VALUE;
            Vector3 at = camera.getPosition();
            for (List<InfrastructureObject> objects : infraCache.values()) {
                for (InfrastructureObject obj : objects) {
                    if (obj.type != InfrastructureObject.Type.SIGN || signsShot.contains(obj)) continue;
                    // -Dxenoguesser.signshot=shaped: only posters on leaning or round walls
                    if ("shaped".equals(System.getProperty("xenoguesser.signshot")) && !obj.shaped) continue;
                    float dx = obj.position.x - at.x, dz = obj.position.z - at.z;
                    float score = dx * dx + dz * dz;
                    if (obj.pictureCount == 0 && !obj.wallMounted) score += 1e9f;
                    if (score < bestScore) { bestScore = score; best = obj; }
                }
            }
            if (best == null) return;
            signsShot.add(best);
            float[] m = best.frontBoardMatrix.toFloatArrayForGLSL();
            float nx = m[4], nz = m[6];
            float len = (float) Math.sqrt(nx * nx + nz * nz);
            float side = Float.parseFloat(System.getProperty("xenoguesser.signside", "1"));
            nx = nx / len * side;
            nz = nz / len * side;
            float back = best.wallMounted ? Math.min(30f, Math.max(best.boardWidth * 0.75f, best.boardHeight * 1.6f) + 6f) : 40f;
            float lift = best.wallMounted ? 0f : 32f;
            // -Dxenoguesser.signslant=0.8 views it from off to one side, by that much of the distance
            float slant = Float.parseFloat(System.getProperty("xenoguesser.signslant", "0"));
            float ex = best.position.x + nx * back - nz * back * slant, ez = best.position.z + nz * back + nx * back * slant;
            signShotEye = new Vector3(ex, best.position.y + lift, ez);
            signShotTarget = new Vector3(best.position.x, best.position.y + lift, best.position.z);
            camera.setPosition(signShotEye);
            camera.setTarget(signShotTarget);
            System.out.println("[SIGNSHOT] sign " + signsShot.size() + ": pictures " + best.pictureCount + (best.wallMounted ? (best.banner ? " banner" : " poster") : "") + ", title " + best.titleLength
                    + ", direction " + nationDirections.getOrDefault(best.nationId, 0) + ", nation " + best.nationId
                    + ", patriotism " + infraManager.patriotismOf(best.nationId));
        } else if (frame % 40 == 30) {
            saveFrame(gl, "sign_" + signsShot.size() + ".png");
        }
    }

    private void menuShot(GL3 gl) {
        if (framesRenderedSinceReady == 60) {
            hideFirstPerson = true;
            float h = landingPod.heading();
            // Off to one side of the stairs, a little above head height, looking at the pod
            float side = h + (float) Math.toRadians(Float.parseFloat(System.getProperty("xenoguesser.menuangle", "38")));
            float distance = Float.parseFloat(System.getProperty("xenoguesser.menudistance", "165"));
            float ex = landingPod.x() + (float) Math.sin(side) * distance, ez = landingPod.z() + (float) Math.cos(side) * distance;
            float eye = Math.max(TerrainMesh.getLayeredHeight(ex, ez, worldNoise), seaLevelHeight) + 26f;
            camera.setPosition(new Vector3(ex, eye, ez));
            // The pod sits just right of the middle: the menu takes the left, the spaceman the far right
            float lookSide = side + (float) Math.PI + (float) Math.toRadians(Float.parseFloat(System.getProperty("xenoguesser.menuoffset", "1.5")));
            camera.setTarget(new Vector3(ex + (float) Math.sin(lookSide) * 100f, landingPod.ground() + 24f, ez + (float) Math.cos(lookSide) * 100f));
            menuShotHold = true;
        } else if (framesRenderedSinceReady == 140) {
            saveFrameTo(gl, new File(LoadingArt.MENU));
            System.out.println("[MENU] Background saved to " + LoadingArt.MENU);
        }
    }

    private boolean menuShotHold;

    /** Debug aid: writes the current frame next to this run's generated textures. */
    private void saveFrame(GL3 gl, String fileName) {
        saveFrameTo(gl, new File(WorldArtGenerator.OUTPUT_DIR, fileName));
    }

    private void saveFrameTo(GL3 gl, File file) {
        int w = screenWidth, h = screenHeight;
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
                ImageIO.write(img, "png", file);
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
        soilBlendRanges = new float[][] { rangeA, rangeB };
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
        deletePeoplePictures();
        playerBody.dispose(gl);
        landingPod.dispose(gl);
        if (rockField != null) rockField.clear(gl);
        hud.dispose(gl);
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

    /**
     * The sun, as the planet's physics puts it: its declination (how far north or south of
     * the equator it is overhead, from the axial tilt and the season) and the longitude where
     * it's noon (the time of day) give its direction from wherever the player is on the
     * sphere. It sits a fixed distance away in that direction.
     */
    private Vector3 getSunPosition() {
        float sunDistance = 2350.0f;
        Vector3 at = camera.getPosition();
        float[] d = Planet.sunDirection(at.x, at.z, sunDeclination, noonLongitude);
        return new Vector3(at.x + d[0] * sunDistance, at.y + d[1] * sunDistance, at.z + d[2] * sunDistance);
    }

    /**
     * Turns the sky dome so the sun on its texture (at the dome's local -Y) lies towards the
     * sun, the rest of the sky wheeling about the celestial pole as on any spinning planet.
     */
    private Matrix4 skyRotation(Vector3 at) {
        float[] sun = Planet.sunDirection(at.x, at.z, sunDeclination, noonLongitude);
        float[] pole = Planet.celestialPole(at.z);
        float[] across = Affine.cross(pole, sun);
        if (Affine.length(across) < 1e-4f) across = Affine.cross(new float[] { 1f, 0f, 0f }, sun);
        across = Affine.normalise(across);
        float[] down = { -sun[0], -sun[1], -sun[2] };
        float[] third = Affine.cross(across, down);
        float[] m = new float[16];
        m[0] = across[0]; m[1] = across[1]; m[2] = across[2];
        m[4] = down[0]; m[5] = down[1]; m[6] = down[2];
        m[8] = third[0]; m[9] = third[1]; m[10] = third[2];
        m[15] = 1f;
        return Matrix4.fromColumns(m);
    }

    private void initialiseCore() {
        // All textures were generated for this world by WorldArtGenerator before the window opened
        textures = new TextureLibrary();
        // Repeating: dirt tracks lay it at world scale, well past one tile
        textures.addWrap(gl, "dirt_diffuse", WorldArtGenerator.pathFor(WorldArtGenerator.SOIL));
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
        // (the sun's picture is its disc inside its glow: sized so the disc stays as big as ever)
        float lightSize = 275.0f / ProceduralTextures.SUN_DISC; 
        
        Light l = new Light(gl, camera, true, new Vector3(0,0,0), lightSize, textures.get("sun_glow"));
        Material m = new Material();

        // Sunlight takes on a little of the star's black-body colour
        float[] sunTint = worldArt.palette().sunTint;
        m.setFullDiffuse(0.75f + 0.25f * sunTint[0], 0.75f + 0.25f * sunTint[1], 0.75f + 0.25f * sunTint[2]);
        m.setFullSpecular(1.0f, 0.0f, 0.0f);   
        l.setMaterial(m);
        lights[0] = l;

        skyModel = makeSkybox(gl, GamePaths.HOME + "assets/shaders/fs_single_sky.txt", textures.get("sky"));
        
        chunkCache = new HashMap<>();
        grassCache = new HashMap<>(); 
        floraCache = new HashMap<>();

        terrainShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_standard_d.txt");
        depthPrePassShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_depth_only.txt");
        solidShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_solid.txt");
        glassShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_glass.txt", GamePaths.HOME + "assets/shaders/fs_glass.txt");

        terrainMaterial = new Material(
            new Vector3(1.0f, 1.0f, 1.0f), 
            new Vector3(1.0f, 1.0f, 1.0f), 
            new Vector3(0.1f, 0.1f, 0.1f), 
            4.0f                                                                                                                                                                                
        );
        terrainMaterial.setDiffuseMap(textures.get("dirt_diffuse"));
                enableAnisotropicFiltering(textures.get("dirt_diffuse"));
        textures.add(gl, "soil_regions", WorldArtGenerator.pathFor(WorldArtGenerator.SOIL_REGIONS));
        InfrastructureObject.soilRegions = textures.get("soil_regions");
        // East and west edges of the map are the same place on the planet
        if (textures.get("soil_regions") != null) {
            textures.get("soil_regions").setTexParameteri(gl, GL3.GL_TEXTURE_WRAP_S, GL3.GL_REPEAT);
        }

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
        signboardShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_signboard.txt");
        Mesh signMeshBase = signBoardMesh(gl, 16);
        
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
            // Writing direction (0=LR, 1=RL, 2=UD, 3=DU) and alphabet, shared with the shops and packets
            int direction = infraManager.scripts().direction(n);
            nationDirections.put(n, direction);
            int alphabetId = infraManager.scripts().alphabet(n);
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
        
        leafShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_leaf.txt");
        initialiseWaterAndGrass();
    }

    private void initialiseFloraVariation(int s, int variation, int onlyLod) {
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

                for (int lod = onlyLod; lod <= onlyLod; lod++) {
                    
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
                waterShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_water.txt", GamePaths.HOME + "assets/shaders/fs_water.txt");
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

        grassShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_grass_instanced.txt", GamePaths.HOME + "assets/shaders/fs_grass_instanced.txt");

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

        /** Developer aid: each nation's sign pictures side by side, saved as people_N.png. */
    private void dumpPeoplePictures() {
        int w = Inhabitants.PICTURE_WIDTH, h = Inhabitants.PICTURE_HEIGHT;
        for (int n = 1; n < peoplePictures.length && n <= 4; n++) {
            int[] ids = peoplePictures[n];
            BufferedImage sheet = new BufferedImage(w * ids.length, h, BufferedImage.TYPE_INT_RGB);
            java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(w * h * 4);
            for (int k = 0; k < ids.length; k++) {
                gl.glActiveTexture(GL3.GL_TEXTURE0);
                gl.glBindTexture(GL3.GL_TEXTURE_2D, ids[k]);
                pixels.clear();
                gl.glGetTexImage(GL3.GL_TEXTURE_2D, 0, GL3.GL_RGBA, GL3.GL_UNSIGNED_BYTE, pixels);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = ((h - 1 - y) * w + x) * 4;
                        sheet.setRGB(k * w + x, y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
                    }
                }
            }
            try {
                ImageIO.write(sheet, "png", new File(WorldArtGenerator.OUTPUT_DIR, "people_" + n + ".png"));
            } catch (IOException ignored) {
            }
        }
    }

    private void deletePeoplePictures() {
        if (peoplePictures == null) return;
        for (int[] nation : peoplePictures) {
            if (nation != null) gl.glDeleteTextures(nation.length, nation, 0);
        }
        peoplePictures = null;
    }

    private void finishInitialise() {
                organismManager.initialise(gl);
                drawSpeciesPortraits(gl);
        inhabitants.initialise(gl);
        // Pictures of the locals, for signs
        deletePeoplePictures();
        inhabitants.setFlags(n -> textures.get(InfrastructureManager.nationTextureName("flag", n)));
        inhabitants.setPackaging(n -> textures.get(InfrastructureManager.nationTextureName("packaging", n)));
        peoplePictures = inhabitants.renderPictures(gl, totalNationsCount, infraManager.products(),
                n -> textures.get(InfrastructureManager.nationTextureName("packaging", n)));
        if (System.getProperty("xenoguesser.dumpart") != null) dumpPeoplePictures();
        playerBody.initialise(gl);
        landingPod.initialise(gl);
        Vector3 bedrock = worldRockColour();
        rockField = new RockField(worldSeed, PHYSICAL_CHUNK_SIZE, seaLevelHeight, worldNoise, new float[] { bedrock.x, bedrock.y, bedrock.z });
        rockField.setKeepout((x, z, r) -> landingPod.covers(x, z, r)
                || infraManager.isRoadLocation(x, z, r + 2f) || infraManager.isBuildingLocation(x, z, r + 4f));
        rockField.initialise(gl);
        hud.initialise(gl);
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

    /** Where a normal round's pod comes down: near a road, clear of houses, away from the poles. */
    private float[] landingSite(java.util.Random rand) {
        return infraManager.landingSite(rand, LandingPod.SPAWN_DISTANCE, LandingPod.REACH,
                (px, pz) -> TerrainMesh.getLayeredHeight(px, pz, worldNoise) > seaLevelHeight + 1.0f
                        // Rounds start away from the far north and south, where the map stretches most
                        && Math.abs(Planet.latitude(pz)) < Math.toRadians(60.0));
    }

    // ------------------------------------------------------------------ the next round, got ready ahead

    // The next round's landing site, chosen as soon as this round is under way, and its
    // surroundings (terrain shapes, trees, grass) worked out in spare time on a thread of its
    // own, nearest first, so that moving there is mostly a matter of uploading them
    private float[] queuedSite;
    private final List<int[]> prefetchQueue = new ArrayList<>();
    private final Map<String, Object[]> prefetchTerrain = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<FloraInstance>> prefetchFlora = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, float[]> prefetchGrass = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "next-round-prefetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private java.util.concurrent.Future<?> prefetchJob;

    /** Chooses where the next round will be and starts getting it ready (normal rounds only). */
    private void queueNextRound() {
        prefetchQueue.clear();
        prefetchTerrain.clear();
        prefetchFlora.clear();
        prefetchGrass.clear();
        if (System.getProperty("xenoguesser.view") != null || System.getProperty("xenoguesser.spawnat") != null) return;
        queuedSite = landingSite(spawnRandom);
        if (queuedSite == null) return;
        int centreX = (int) Math.floor((queuedSite[0] + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        int centreZ = (int) Math.floor((queuedSite[1] + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        int reach = VIEW_DISTANCE + PREFETCH_RINGS;
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                prefetchQueue.add(new int[] { centreX + dx, centreZ + dz, Math.max(Math.abs(dx), Math.abs(dz)) });
            }
        }
        prefetchQueue.sort((a, b) -> Integer.compare(a[2], b[2]));
    }

    /** Hands the prefetch thread its next few chunks once it has finished the last. */
    private void pumpPrefetch() {
        if (prefetchQueue.isEmpty() || (prefetchJob != null && !prefetchJob.isDone())) return;
        List<int[]> batch = new ArrayList<>(prefetchQueue.subList(0, Math.min(4, prefetchQueue.size())));
        prefetchQueue.subList(0, batch.size()).clear();
        prefetchJob = prefetcher.submit(() -> {
            for (int[] c : batch) {
                int cx = c[0], cz = c[1], ring = c[2];
                String key = cx + "_" + cz;
                int segments = terrainSegments(ring);
                prefetchTerrain.put(key + "_seg" + segments,
                        TerrainMesh.buildChunkData(segments, PHYSICAL_CHUNK_SIZE / (float) segments, cx, cz, worldNoise));
                prefetchFlora.put(key, buildFloraChunk(cx, cz));
                if (ring <= GRASS_VIEW_DISTANCE) prefetchGrass.put(key, buildGrassChunk(cx, cz));
            }
        });
    }

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
        if (System.getProperty("xenoguesser.view") == null) {
            // A normal round: the pod has come down near a road, clear of houses and fences (where
            // it was chosen during the last round, so its surroundings could be got ready then)
            float[] site = queuedSite != null ? queuedSite : landingSite(dynamicRand);
            queuedSite = null;
            if (site != null) roadSpawn = site;
        }
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

        if ("shop".equals(System.getProperty("xenoguesser.view"))) {
            // Developer aid: across the street from a shop, looking at its front
            float[] view = infraManager.shopViewpoint(dynamicRand);
            if (view != null) {
                spawnX = view[0];
                spawnZ = view[1];
                lookX = view[2];
                lookZ = view[3];
                foundDryLand = true;
            }
        }

        // Developer aid: -Dxenoguesser.spawnat=x,z starts the round there
        String spawnAt = System.getProperty("xenoguesser.spawnat");
        if (spawnAt != null) {
            String[] parts = spawnAt.split(",");
            spawnX = Float.parseFloat(parts[0].trim());
            spawnZ = Float.parseFloat(parts[1].trim());
            foundDryLand = true;
        }

        if ("track".equals(System.getProperty("xenoguesser.view"))) {
            // Developer aid: on a dirt track, looking along it
            float[] view = infraManager.trackViewpoint(dynamicRand);
            if (view != null) {
                spawnX = view[0];
                spawnZ = view[1];
                lookX = view[2];
                lookZ = view[3];
                foundDryLand = true;
            }
        }

        if ("raisedroad".equals(System.getProperty("xenoguesser.view"))) {
            // Developer aid: looking at a raised road's retaining wall from below
            float[] view = infraManager.raisedRoadViewpoint(dynamicRand);
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
        if ("mountain".equals(devView)) {
            // Developer aid: some way off from the highest ground found, looking at it
            float bestX = 0f, bestZ = 0f, best = -Float.MAX_VALUE;
            for (int attempt = 0; attempt < 6000; attempt++) {
                float x = (dynamicRand.nextFloat() - 0.5f) * TOTAL_REGION_WIDTH * 0.9f;
                float z = (dynamicRand.nextFloat() - 0.5f) * TOTAL_REGION_WIDTH * 0.9f;
                float h = TerrainMesh.getLayeredHeight(x, z, worldNoise);
                if (h > best) { best = h; bestX = x; bestZ = z; }
            }
            // From the lowest dry spot on a ring round it, for a clear view up
            float lowest = Float.MAX_VALUE;
            for (int attempt = 0; attempt < 96; attempt++) {
                double a = attempt * Math.PI * 2 / 96;
                float x = bestX + (float) Math.cos(a) * 1250f, z = bestZ + (float) Math.sin(a) * 1250f;
                float h = TerrainMesh.getLayeredHeight(x, z, worldNoise);
                if (h > seaLevelHeight + 2f && h < lowest) {
                    lowest = h;
                    spawnX = x;
                    spawnZ = z;
                    lookX = -(float) Math.cos(a);
                    lookZ = -(float) Math.sin(a);
                    foundDryLand = true;
                }
            }
        }
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

        // The season: where the planet is in its orbit sets how far north or south the sun
        // stands overhead
        float seasonalPhase = dynamicRand.nextFloat() * (float)(2.0 * Math.PI);
        this.currentSeasonalTiltDegrees = this.planetAxialTiltDegrees * (float)Math.sin(seasonalPhase);
        this.sunDeclination = Math.asin(Math.sin(Math.toRadians(planetAxialTiltDegrees)) * Math.sin(seasonalPhase));

        if (this.minimap != null) {
            this.minimap.setPlayerSpawnLocation(spawnX, spawnZ);
        }

        chooseTimeOfDay(spawnX, spawnZ, dynamicRand);
        // A new day: new clouds and weather
        weather.newDay(dynamicRand);
        landingPod.place(spawnX, spawnZ, lookX, lookZ, (px, pz) -> TerrainMesh.getLayeredHeight(px, pz, worldNoise));
        // Nothing else stands where it came down
        infraManager.setKeepClear(landingPod.x(), landingPod.z(), Math.max(40f, LandingPod.REACH) + 6f);
        moveToLocation(spawnX, spawnZ, lookX, lookZ);
        // Developer aid: -Dxenoguesser.lookatsun starts the round looking straight at the sun
        if (System.getProperty("xenoguesser.lookatsun") != null) {
            Vector3 eye = camera.getPosition();
            float[] d = Planet.sunDirection(eye.x, eye.z, sunDeclination, noonLongitude);
            camera.setTarget(new Vector3(eye.x + d[0] * 10f, eye.y + d[1] * 10f, eye.z + d[2] * 10f));
            System.out.printf("[PLANET] Sun %.0f degrees up, latitude %.1f, declination %.1f%n",
                    Math.toDegrees(Math.asin(d[1])), Math.toDegrees(Planet.latitude(eye.z)), Math.toDegrees(sunDeclination));
        }
        // Developer aid: -Dxenoguesser.pitch=-60 starts the round looking down by that many degrees
        String pitch = System.getProperty("xenoguesser.pitch");
        // and -Dxenoguesser.yaw=180 turned round by that many degrees
        String yaw = System.getProperty("xenoguesser.yaw");
        if (pitch != null || yaw != null) {
            camera.updateYawPitch(yaw == null ? 0f : Float.parseFloat(yaw) / camera.MOUSE_SPEED,
                    pitch == null ? 0f : Float.parseFloat(pitch) / camera.MOUSE_SPEED);
        }
    }

    /**
     * The time of day: the round starts in daylight where the pod lands, at a random local
     * hour with the sun at least a little way up, which sets where on the planet it's noon.
     * Walk far enough east or west and it's another time of day there.
     */
    private void chooseTimeOfDay(float x, float z, java.util.Random rand) {
        double lat = Planet.latitude(z), lon = Planet.longitude(x);
        double hour = 0.0;
        for (int attempt = 0; attempt < 40; attempt++) {
            double candidate = (rand.nextDouble() * 2 - 1) * Math.PI * 0.42;   // up to about five hours from noon
            double elevation = Math.asin(Math.sin(lat) * Math.sin(sunDeclination)
                    + Math.cos(lat) * Math.cos(sunDeclination) * Math.cos(candidate));
            if (elevation > Math.toRadians(12.0)) {
                hour = candidate;
                break;
            }
        }
        noonLongitude = lon - hour;
        timeOfDay = (float) (0.5 + hour / (2 * Math.PI));
    }

    public void moveToLocation(float spawnX, float spawnZ) {
        moveToLocation(spawnX, spawnZ, 0.0f, -1.0f);
    }

    private void moveToLocation(float spawnX, float spawnZ, float lookX, float lookZ) {
        float terrainHeightAtSpawn = groundHeightAt(spawnX, spawnZ);
        camera.setPosition(new Vector3(spawnX, terrainHeightAtSpawn + playerEyeHeight, spawnZ));
        camera.setTarget(new Vector3(spawnX + lookX * 10.0f, terrainHeightAtSpawn + playerEyeHeight, spawnZ + lookZ * 10.0f));

        lastChunkX = (int) Math.floor((spawnX + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        lastChunkZ = (int) Math.floor((spawnZ + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        
        // During the initial load, distant grass is seeded over later loading frames instead
                updateVisibleChunks(worldReady && !roundLoading);
    }

    /**
     * Carries the player from where they were to where the keys took them, in short steps,
     * stopping against anything solid and sliding along it rather than passing through.
     */
    private void moveSolidly(Vector3 from) {
        Vector3 to = camera.getPosition();
        float mx = to.x - from.x, mz = to.z - from.z;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(mx, mz) / 1.5f));
        float px = from.x, pz = from.z;
        for (int i = 0; i < steps; i++) {
            float sx = mx / steps, sz = mz / steps;
            // Too steep to climb straight up: try sliding along the slope instead (in the air,
            // high enough above it, the slope doesn't matter)
            if (jumpHeight < 1f && tooSteep(px, pz, sx, sz)) {
                if (!tooSteep(px, pz, sx, 0f)) sz = 0f;
                else if (!tooSteep(px, pz, 0f, sz)) sx = 0f;
                else break;
            }
            float[] next = collision.resolveFirm(px, pz, px + sx, pz + sz, PLAYER_RADIUS, PLAYER, jumpHeight);
            px = next[0];
            pz = next[1];
        }
        if (px != to.x || pz != to.z) camera.setGroundPosition(px, pz);
    }

    /**
     * The world is a sphere (see Planet): after a step, the view turns very slightly so that
     * walking straight follows a great circle; off the chart's east or west edge you come in
     * from the other; over a pole, out on the far side heading the other way.
     */
    private void followThePlanet(Vector3 before) {
        Vector3 now = camera.getPosition();
        if (now.x == before.x && now.z == before.z) return;
        double angle = Math.toRadians(camera.getYaw());
        double turn = Planet.turnAlong(before.x, before.z, now.x, now.z, angle);
        camera.turn((float) Math.toDegrees(turn));
        angle += turn;
        float x = now.x, z = now.z;
        double[] across = Planet.acrossPole(x, z, angle);
        if (across != null) {
            x = (float) across[0];
            z = (float) across[1];
            camera.turn((float) Math.toDegrees(across[2] - angle));
            System.out.printf("[PLANET] Over the pole to %.0f, %.0f%n", x, z);
        }
        float wrapped = (float) Planet.wrapX(x);
        if (wrapped != x || across != null) camera.setGroundPosition(wrapped, z);
    }

    // Steeper than this (rise over run) can't be walked up
    private static final float MAX_CLIMB = 0.7f;   // about 35 degrees

    // Jumping: Space throws the player up, and gravity brings them down again. While in the
    // air, anything lower than their feet passes beneath them (rocks, low fences, rails).
    private static final float JUMP_SPEED = 42f, GRAVITY = 90f;
    private volatile boolean jumpWanted;
    private boolean airborne;
    private float jumpHeight, jumpVelocity, airborneEyeY;

    /** Space: jump, if standing on something (not mid-air or swimming). */
    public void jump() {
        jumpWanted = true;
    }

    /**
     * Whether stepping by (sx, sz) from (x, z) would climb ground steeper than MAX_CLIMB.
     * The slope is taken over a few units ahead and behind, so the ground's fine roughness
     * doesn't trip the player up; going downhill or across is always allowed.
     */
    private boolean tooSteep(float x, float z, float sx, float sz) {
        float len = (float) Math.hypot(sx, sz);
        if (len < 1e-4f || landingPod.floorAt(x, z) > Float.NEGATIVE_INFINITY) return false;
        float dx = sx / len, dz = sz / len, span = 3f;
        // (roads' decks included, so their retaining walls can't be walked up)
        float ahead = groundHeightAt(x + dx * span, z + dz * span);
        float behind = groundHeightAt(x - dx * span, z - dz * span);
        return (ahead - behind) / (2f * span) > MAX_CLIMB;
    }

    /** Tree trunks near a point, from the trees placed so far. */
    private void treesNear(float x, float z, float reach, Collision.Sink sink) {
        int cx0 = (int) Math.floor((x - reach) / PHYSICAL_CHUNK_SIZE), cx1 = (int) Math.floor((x + reach) / PHYSICAL_CHUNK_SIZE);
        int cz0 = (int) Math.floor((z - reach) / PHYSICAL_CHUNK_SIZE), cz1 = (int) Math.floor((z + reach) / PHYSICAL_CHUNK_SIZE);
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                List<FloraInstance> trees = floraCache == null ? null : floraCache.get(cx + "_" + cz);
                if (trees == null) continue;
                for (FloraInstance tree : trees) {
                    float trunk = Math.max(0.5f, speciesConfigs[tree.speciesIndex].baseSWidth * 0.5f * tree.scale);
                    sink.circle(tree.pos.x, tree.pos.z, trunk);
                }
            }
        }
    }

    private void drawWindowGlass(GL3 gl, Matrix4 viewProjection, Frustum frustum, Vector3 eye, Vector3 sunPos, float[] sunColour,
                                 Matrix4 skyRotation, Texture sky, float reach) {
        glassShader.use(gl);
        glassShader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(glassShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        glassShader.setVec3(gl, "viewPos", eye);
        glassShader.setVec3(gl, "sunPos", sunPos);
        glassShader.setVec3(gl, "sunColour", new Vector3(sunColour[0], sunColour[1], sunColour[2]));
        glassShader.setVec3(gl, "ambientLight", ambientLight);
        glassShader.setVec3(gl, "glassTint", new Vector3(0.55f, 0.65f, 0.7f));
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            glassShader.setInt(gl, "skyTexture", 2);
        }
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        gl.glDisable(GL.GL_CULL_FACE);
        for (List<InfrastructureObject> objects : infraCache.values()) {
            for (InfrastructureObject obj : objects) {
                if (obj.type != InfrastructureObject.Type.BATCH || !obj.hasTransparentParts()) continue;
                float dx = obj.position.x - eye.x, dz = obj.position.z - eye.z;
                float far = reach + obj.boundingRadius;
                if (dx * dx + dz * dz > far * far
                        || !frustum.intersectsSphere(obj.position.x, obj.position.y, obj.position.z, obj.boundingRadius)) continue;
                obj.renderTransparent(gl);
            }
        }
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
    }

    private static final int SLOPE_GRID = 10;

    /** How steep the ground is at a point: rise over run. */
    private float slopeAt(float x, float z) {
        float d = 3f;
        float gx = TerrainMesh.getLayeredHeight(x + d, z, worldNoise) - TerrainMesh.getLayeredHeight(x - d, z, worldNoise);
        float gz = TerrainMesh.getLayeredHeight(x, z + d, worldNoise) - TerrainMesh.getLayeredHeight(x, z - d, worldNoise);
        return (float) Math.hypot(gx, gz) / (2f * d);
    }

    /** The steepness across a chunk on a coarse grid, cheap enough to consult for every blade of grass. */
    private float[] slopeGrid(int cx, int cz) {
        float[] heights = new float[(SLOPE_GRID + 1) * (SLOPE_GRID + 1)];
        float step = PHYSICAL_CHUNK_SIZE / SLOPE_GRID;
        for (int j = 0; j <= SLOPE_GRID; j++) {
            for (int i = 0; i <= SLOPE_GRID; i++) {
                heights[j * (SLOPE_GRID + 1) + i] = TerrainMesh.getLayeredHeight(cx * PHYSICAL_CHUNK_SIZE + i * step, cz * PHYSICAL_CHUNK_SIZE + j * step, worldNoise);
            }
        }
        float[] slopes = new float[SLOPE_GRID * SLOPE_GRID];
        for (int j = 0; j < SLOPE_GRID; j++) {
            for (int i = 0; i < SLOPE_GRID; i++) {
                int k = j * (SLOPE_GRID + 1) + i;
                float gx = (heights[k + 1] + heights[k + SLOPE_GRID + 2] - heights[k] - heights[k + SLOPE_GRID + 1]) * 0.5f / step;
                float gz = (heights[k + SLOPE_GRID + 1] + heights[k + SLOPE_GRID + 2] - heights[k] - heights[k + 1]) * 0.5f / step;
                slopes[j * SLOPE_GRID + i] = (float) Math.hypot(gx, gz);
            }
        }
        return slopes;
    }

    /** The steepness from a slope grid at a position given as fractions across the chunk. */
    private static float slopeIn(float[] slopes, float fx, float fz) {
        int i = Math.min(SLOPE_GRID - 1, (int) (fx * SLOPE_GRID)), j = Math.min(SLOPE_GRID - 1, (int) (fz * SLOPE_GRID));
        return slopes[j * SLOPE_GRID + i];
    }

    private Vector3 rockColour;

    /** This world's bedrock: a muted stone colour, different each world. */
    private Vector3 worldRockColour() {
        if (rockColour == null) {
            java.util.Random rand = new java.util.Random(worldSeed * 61L + 7L);
            float[] c = WorldPalette.hsv(rand.nextFloat(), 0.08f + 0.2f * rand.nextFloat(), 0.42f + 0.2f * rand.nextFloat());
            rockColour = new Vector3(c[0], c[1], c[2]);
        }
        return rockColour;
    }

    /** What the player stands on: the land or a road, or the pod's stairs where they are higher. */
    private float groundHeightAt(float x, float z) {
        // Up on the road's deck where it stands above the ground
        float ground = infraManager != null ? infraManager.walkingSurfaceY(x, z) : TerrainMesh.getLayeredHeight(x, z, worldNoise);
        return Math.max(ground, landingPod.floorAt(x, z));
    }

    // The next round: built and waiting for the player, and whether they've asked for it
    private boolean nextRoundBuilt, nextRoundWanted;
    private long nextRoundWantedAt;

    private void startBuildingNextRound(GL3 gl) {
        roundLoading = true;
        setBuilderThreads(true);
        nextRoundBuilt = false;
        roundLoadingStarted = System.currentTimeMillis();
        roundLoadingFrames = 0;
        resetToNextRound(gl);
    }

    public void resetToNextRound(GL3 gl) {
        for (Model model : chunkCache.values()) {
            if (model.mesh != null) model.mesh.dispose(gl);
        }
        chunkCache.clear();
        terrainInFlight.clear();
        grassGather = null;
        floraInFlight.clear();
        grassInFlight.clear();
        grassCache.clear(); 
        floraCache.clear();

        lastChunkX = Integer.MAX_VALUE;
        lastChunkZ = Integer.MAX_VALUE;
                totalGrassInstances = 0;
                drawnGrassInstances = 0;
                grassDirty = true;
                organismManager.clear();
        if (rockField != null) rockField.clear(gl);
        inhabitants.clear();

        spawnPlayerAtRandomLocation();
        if (rockField != null) {
            Vector3 at = camera.getPosition();
            rockField.update(gl, at.x, at.z, true);
        }
    }

    /**
     * While the world is loading (at launch or between rounds), whether this frame's slice of
     * time is spent; nearby chunks always build. Slicing keeps the loading screen moving.
     */
    private boolean overRoundBudget(int cx, int cz) {
        int ring = Math.max(Math.abs(cx - lastChunkX), Math.abs(cz - lastChunkZ));
        // Right round the player is built at once; everything else waits for spare time, a
        // slice of each frame (bigger while loading), so crossing into a new chunk never
        // stalls a frame building the whole of the next row
        if (ring <= 1) return false;
        if ((roundLoading || !worldReady) && ring <= 2) return false;
        return System.nanoTime() > roundChunkDeadline;
    }

    // In play, building roads and buildings gets this much of each frame besides
    private static final long INFRA_CHUNK_BUDGET_NANOS = 2_000_000L;
    // In play, chunk building gets this much of each frame
    private static final long PLAY_CHUNK_BUDGET_NANOS = 3_000_000L;
    // Chunks are built this many rings beyond the view ahead of time, so they're ready when needed
    private static final int PREFETCH_RINGS = 1;
    // Some chunk work was left for later frames
    private boolean chunkWorkPending;
    // Which way the player has lately been walking, for building ahead of them first
    private float walkDirX, walkDirZ, lastWalkX = Float.NaN, lastWalkZ;
    private boolean grassDirty = true;
    private int framesSinceGrassBuffer;
    private boolean grassMovedChunk;
    private int chunkScanCountdown;
    private float[] grassStaging;
    private boolean nearGrassArrived;
    // Terrain chunks are worked out on these threads and only uploaded on the GL thread
    private final java.util.concurrent.ExecutorService terrainBuilders = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2 - 1)), r -> {
                Thread thread = new Thread(r, "terrain-builder");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    private final Map<String, java.util.concurrent.Future<Object[]>> terrainInFlight = new HashMap<>();
    // The world's first build gets every spare thread too
    { setBuilderThreads(true); }
    private final Map<String, Integer> terrainInFlightSegments = new HashMap<>();
    private static final int MAX_TERRAIN_IN_FLIGHT = 48;
    // While a round is being built behind the results (nothing else to draw), far more at once
    private static final int LOADING_TERRAIN_IN_FLIGHT = 320, LOADING_UPLOADS_PER_FRAME = 80;
    private static final int PLAY_BUILDERS = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2 - 1));
    private static final int LOADING_BUILDERS = Math.max(PLAY_BUILDERS, Runtime.getRuntime().availableProcessors() - 1);

    /** More builder threads while a round loads behind the results, fewer while it's being played. */
    private void setBuilderThreads(boolean loading) {
        java.util.concurrent.ThreadPoolExecutor pool = (java.util.concurrent.ThreadPoolExecutor) terrainBuilders;
        int wanted = loading ? LOADING_BUILDERS : PLAY_BUILDERS;
        if (wanted > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(wanted);
            pool.setCorePoolSize(wanted);
        } else if (wanted < pool.getMaximumPoolSize()) {
            pool.setCorePoolSize(wanted);
            pool.setMaximumPoolSize(wanted);
        }
    }


    /** Notes which way the player is heading. */
    private void trackWalking(Vector3 at) {
        if (!Float.isNaN(lastWalkX)) {
            float dx = at.x - lastWalkX, dz = at.z - lastWalkZ;
            float length = (float) Math.hypot(dx, dz);
            if (length > 0.01f && length < 50f) {
                walkDirX += (dx / length - walkDirX) * 0.1f;
                walkDirZ += (dz / length - walkDirZ) * 0.1f;
            }
        }
        lastWalkX = at.x;
        lastWalkZ = at.z;
    }

    /** Chunk coordinates within radius rings of the player, nearest first and those ahead of them before those behind. */
    private List<int[]> chunksByPriority(int radius) {
        List<int[]> order = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        float heading = (float) Math.hypot(walkDirX, walkDirZ);
        for (int cz = lastChunkZ - radius; cz <= lastChunkZ + radius; cz++) {
            for (int cx = lastChunkX - radius; cx <= lastChunkX + radius; cx++) {
                int dx = cx - lastChunkX, dz = cz - lastChunkZ;
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                float ahead = 0f;
                if (heading > 0.2f && ring > 0) ahead = (dx * walkDirX + dz * walkDirZ) / ((float) Math.hypot(dx, dz) * heading);
                order.add(new int[] { cx, cz, ring, Math.round((ring - ahead * 2.5f) * 100f) });
            }
        }
        order.sort((a, b) -> Integer.compare(a[3], b[3]));
        return order;
    }

    private static int terrainSegments(int ring) {
        if (ring > 14) return 4;
        if (ring > 7) return 10;
        if (ring > 3) return 25;
        return 50;
    }

    /** Whether every terrain, flora and building chunk in view has been built. */
    /** Whether every chunk in view of where the player now stands has its ground, plants, buildings and grass. */
    private boolean surroundingsLoadedHere() {
        for (int dz = -VIEW_DISTANCE; dz <= VIEW_DISTANCE; dz++) {
            for (int dx = -VIEW_DISTANCE; dx <= VIEW_DISTANCE; dx++) {
                String key = (lastChunkX + dx) + "_" + (lastChunkZ + dz);
                if (!chunkCache.containsKey(key) || !floraCache.containsKey(key) || !infraCache.containsKey(key)) return false;
                if (Math.abs(dx) <= GRASS_VIEW_DISTANCE && Math.abs(dz) <= GRASS_VIEW_DISTANCE && !grassCache.containsKey(key)) return false;
            }
        }
        return true;
    }

    private boolean surroundingsBuilt() {
        int viewChunks = (VIEW_DISTANCE * 2 + 1) * (VIEW_DISTANCE * 2 + 1);
        return chunkCache.size() >= viewChunks && floraCache.size() >= viewChunks && infraCache.size() >= viewChunks;
    }

    /** One chunk's trees and shrubs (safe to work out off the GL thread). */
    private List<FloraInstance> buildFloraChunk(int cx, int cz) {
                    List<FloraInstance> instances = new ArrayList<>();
                    float urbanness = infraManager.getUrbanness((cx + 0.5f) * PHYSICAL_CHUNK_SIZE, (cz + 0.5f) * PHYSICAL_CHUNK_SIZE);
                    
                    // Seed species independently based on their unique abundance calculations
                    for (int s = 0; s < NUM_SPECIES; s++) {
                        SpeciesConfig sc = speciesConfigs[s];
                        // Seeded by the chunk's place on the planet, so it's the same on either side of the chart's join
                        long fSeed = worldSeed ^ ((long) Planet.wrapChunk(cx, PHYSICAL_CHUNK_SIZE) * 492876847L) ^ ((long) cz * 314159265L) ^ ((long) s * 9012431L);
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
                                    && slopeAt(cxWorld, czWorld) < 0.9f
                                    && !landingPod.covers(cxWorld, czWorld, 8f)
                                    && !infraManager.isClearedForRoad(cxWorld, czWorld, 11.0f, 3.0f)
                                    && !infraManager.isBuildingLocation(cxWorld, czWorld, 6.0f)) {
                                int randModelIndex = cRand.nextInt(FLORA_VARIATIONS);
                                float randomScale = 0.70f + cRand.nextFloat() * 0.60f;
                                float randomRotY = cRand.nextFloat() * 360.0f;
                                
                                instances.add(new FloraInstance(new Vector3(cxWorld, cyWorld, czWorld), s, randModelIndex, randomScale, randomRotY));
                            }
                        }
                    }
        return instances;
    }

    /** A regional factor at the middles of a chunk and the eight round it (row by row, west to east, north to south). */
    private float[] aroundChunk(RegionalFactor factor, int cx, int cz) {
        float[] values = new float[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx, z = cz + dz;
                values[(dz + 1) * 3 + dx + 1] = factor.evaluate(x, z, (x + 0.5f) * PHYSICAL_CHUNK_SIZE, (z + 0.5f) * PHYSICAL_CHUNK_SIZE);
            }
        }
        return values;
    }

    /**
     * A value at a point in a chunk (fx, fz from 0 to 1 across it), blended from the values at
     * the middles of the chunks nearest it (see aroundChunk) by how near it is to each.
     */
    private static float blendAround(float[] around, float fx, float fz) {
        float gx = fx + 0.5f, gz = fz + 0.5f;   // 0 to 2 across the middles
        int ix = Math.min(1, (int) gx), iz = Math.min(1, (int) gz);
        float tx = gx - ix, tz = gz - iz;
        float a = around[iz * 3 + ix], b = around[iz * 3 + ix + 1], c = around[(iz + 1) * 3 + ix], d = around[(iz + 1) * 3 + ix + 1];
        return (a + (b - a) * tx) * (1f - tz) + (c + (d - c) * tx) * tz;
    }

    /** One chunk's grass blades, five floats each (safe to work out off the GL thread). */
    private float[] buildGrassChunk(int cx, int cz) {
        float[] chunkGrassData;
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
                        float[] slopes = slopeGrid(cx, cz);
                        // The grass's colour and height at the middles of this chunk and those round
                        // it, so each blade's can be blended from the nearest (no edges between chunks)
                        float[] colourAround = aroundChunk(this.grassColourFactor, cx, cz);
                        float[] heightAround = aroundChunk(this.grassHeightFactor, cx, cz);

                        for (int i = 0; i < dynamicGrassAttempts; i++) {
                            long bladeSeed = worldSeed 
                                    ^ ((long) Planet.wrapChunk(cx, PHYSICAL_CHUNK_SIZE) * 73731703L) 
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
                            
                            // Grass thins out on steep ground and gives way to bare rock
                            float slope = slopeIn(slopes, rand1, rand2);
                            float keep = 1f - Math.max(0f, Math.min(1f, (slope - 0.6f) / 0.6f));
                            boolean onSlope = ((rand1 * 7.31f + rand2 * 3.17f) % 1f) >= keep;
                            if (worldY > seaLevelHeight + 0.1f && !onSlope
                                    && !landingPod.covers(worldX, worldZ, -2f)
                                    && !infraManager.isClearedForRoad(worldX, worldZ, 11.0f, 1.5f)) {
                                float structuralHeightBase = blendAround(heightAround, rand1, rand2);
                                float structuralColourBase = blendAround(colourAround, rand1, rand2);

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
                    
        return chunkGrassData;
    }

    private final Map<String, java.util.concurrent.Future<List<FloraInstance>>> floraInFlight = new HashMap<>();
    private final Map<String, java.util.concurrent.Future<float[]>> grassInFlight = new HashMap<>();

    private java.util.concurrent.Future<Object[]> grassGather;

    /** Puts gathered grass blades (count of them, five floats each) into the instance buffer. */
    private void uploadGrass(float[] data, int count) {
        int floats = count * 5;
        if (persistentGrassBuffer == null || floats > currentGrassGPUCapacityFloats) {
            currentGrassGPUCapacityFloats = (int) (Math.max(floats, 5) * 1.2f);
            persistentGrassBuffer = com.jogamp.common.nio.Buffers.newDirectFloatBuffer(currentGrassGPUCapacityFloats);
            gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassChunkCoordVBO);
            gl.glBufferData(GL3.GL_ARRAY_BUFFER, currentGrassGPUCapacityFloats * 4L, null, GL3.GL_DYNAMIC_DRAW);
        }
        persistentGrassBuffer.clear();
        persistentGrassBuffer.put(data, 0, floats);
        persistentGrassBuffer.flip();
        gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassChunkCoordVBO);
        gl.glBufferSubData(GL3.GL_ARRAY_BUFFER, 0, floats * 4L, persistentGrassBuffer);
        gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, 0);
        drawnGrassInstances = count;
    }

    private void updateVisibleChunks(boolean forceImmediate) {
        boolean loadingNow = roundLoading || teleportLoading || !worldReady;
        roundChunkDeadline = System.nanoTime() + (loadingNow ? ROUND_LOADING_BUDGET_NANOS : PLAY_CHUNK_BUDGET_NANOS);
        infraManager.prepareRoadNetwork(
            PHYSICAL_CHUNK_SIZE, TOTAL_REGION_WIDTH, seaLevelHeight, worldNoise
        );
        int reach = VIEW_DISTANCE + PREFETCH_RINGS;
        List<int[]> order = chunksByPriority(reach);
        int uploadsThisFrame = 0;
        boolean pending = false;

        // --- TERRAIN: dropped well behind, built (or re-detailed) nearest and ahead first
        Iterator<Map.Entry<String, Model>> iterator = chunkCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Model> entry = iterator.next();
            String[] coords = entry.getKey().split("_");
            int ring = Math.max(Math.abs(Integer.parseInt(coords[0]) - lastChunkX), Math.abs(Integer.parseInt(coords[1]) - lastChunkZ));
            if (ring > reach + 1) {
                if (entry.getValue().mesh != null) entry.getValue().mesh.dispose(gl);
                iterator.remove();
                chunkBounds.remove(entry.getKey());
            }
        }
        for (int[] c : order) {
            int cx = c[0], cz = c[1], ring = c[2];
            String key = cx + "_" + cz;
            int targetSegments = terrainSegments(ring);
            Model cachedModel = chunkCache.get(key);
            if (cachedModel != null && cachedModel.name.endsWith("_seg" + targetSegments)) continue;
            java.util.concurrent.Future<Object[]> building = terrainInFlight.get(key);
            if (building != null && terrainInFlightSegments.get(key) != targetSegments) {
                // Started at another level of detail before the player moved: start again
                terrainInFlight.remove(key);
                building = null;
            }
            Object[] data = null;
            Object[] ready = building == null ? prefetchTerrain.get(key + "_seg" + targetSegments) : null;
            if ((ready != null || (building != null && building.isDone())) && uploadsThisFrame >= (loadingNow ? LOADING_UPLOADS_PER_FRAME : 6)) {
                pending = true;
                continue;
            }
            if (ready != null) {
                // Worked out ahead, during the last round
                prefetchTerrain.remove(key + "_seg" + targetSegments);
                uploadsThisFrame++;
                data = ready;
            } else if (building != null && building.isDone()) {
                uploadsThisFrame++;
                try {
                    data = building.get();
                } catch (Exception e) {
                    data = null;
                }
                terrainInFlight.remove(key);
            } else if (building == null && ring <= 1 && cachedModel == null) {
                // Right under the player and missing: now
                data = TerrainMesh.buildChunkData(targetSegments, PHYSICAL_CHUNK_SIZE / (float) targetSegments, cx, cz, worldNoise);
            } else if (building == null) {
                if (terrainInFlight.size() < (loadingNow ? LOADING_TERRAIN_IN_FLIGHT : MAX_TERRAIN_IN_FLIGHT)) {
                    final int segs = targetSegments, bx = cx, bz = cz;
                    terrainInFlight.put(key, terrainBuilders.submit(() ->
                            TerrainMesh.buildChunkData(segs, PHYSICAL_CHUNK_SIZE / (float) segs, bx, bz, worldNoise)));
                    terrainInFlightSegments.put(key, targetSegments);
                }
                pending = true;
                continue;
            } else {
                pending = true;
                continue;
            }
            if (data == null) {
                pending = true;
                continue;
            }
            // Until the new mesh is up, a chunk keeps the detail it had
            Mesh chunkMesh = new Mesh(gl, (float[]) data[0], (int[]) data[1]);
            Model chunkModel = new Model("chunk_" + cx + "_" + cz + "_seg" + targetSegments, chunkMesh, globalModelMatrix, terrainShader, terrainMaterial, terrainRenderer, lights, camera);
            if (cachedModel != null && cachedModel.mesh != null) cachedModel.mesh.dispose(gl);
            chunkCache.put(key, chunkModel);
            chunkBounds.put(key, chunkBoundingSphere(cx, cz));
        }
        terrainInFlight.keySet().removeIf(key -> {
            String[] coords = key.split("_");
            return Math.max(Math.abs(Integer.parseInt(coords[0]) - lastChunkX), Math.abs(Integer.parseInt(coords[1]) - lastChunkZ)) > reach + 1;
        });

        // --- MULTI-SPECIES PROBABILISTIC SPATIAL ECOSYSTEM SEEDING ---
        floraInFlight.keySet().removeIf(key -> {
            String[] coords = key.split("_");
            return Math.max(Math.abs(Integer.parseInt(coords[0]) - lastChunkX), Math.abs(Integer.parseInt(coords[1]) - lastChunkZ)) > reach + 1;
        });
        floraCache.keySet().removeIf(key -> {
            String[] coords = key.split("_");
            return Math.max(Math.abs(Integer.parseInt(coords[0]) - lastChunkX), Math.abs(Integer.parseInt(coords[1]) - lastChunkZ)) > reach + 1;
        });

        for (int[] c : order) {
            int cx = c[0], cz = c[1];
            {
                String key = cx + "_" + cz;
                if (floraCache.containsKey(key)) continue;
                List<FloraInstance> grown = prefetchFlora.remove(key);
                if (grown != null) {
                    floraCache.put(key, grown);
                    continue;
                }
                if (overRoundBudget(cx, cz)) {
                    pending = true;
                    continue;
                }
                {
                    java.util.concurrent.Future<List<FloraInstance>> growing = floraInFlight.get(key);
                    if (growing == null && (c[2] <= 1 || forceImmediate)) {
                        floraCache.put(key, buildFloraChunk(cx, cz));
                        continue;
                    }
                    if (growing == null) {
                        final int fx = cx, fz = cz;
                        floraInFlight.put(key, terrainBuilders.submit(() -> buildFloraChunk(fx, fz)));
                        pending = true;
                        continue;
                    }
                    if (!growing.isDone()) {
                        pending = true;
                        continue;
                    }
                    floraInFlight.remove(key);
                    List<FloraInstance> instances;
                    try {
                        instances = growing.get();
                    } catch (Exception e) {
                        continue;
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
        if (grassCache.keySet().retainAll(activeGrassKeys.keySet())) grassDirty = true;
        grassInFlight.keySet().retainAll(activeGrassKeys.keySet());

        totalGrassInstances = 0;
        boolean generatedThisFrame = false; 
        long grassDeadline = System.nanoTime() + grassBudgetNanos;

        for (int cz = lastChunkZ - GRASS_VIEW_DISTANCE; cz <= lastChunkZ + GRASS_VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - GRASS_VIEW_DISTANCE; cx <= lastChunkX + GRASS_VIEW_DISTANCE; cx++) {
                String key = cx + "_" + cz;
                float[] chunkGrassData = grassCache.get(key);
                
                if (chunkGrassData == null) {
                    int distanceFromPlayer = Math.max(Math.abs(cx - lastChunkX), Math.abs(cz - lastChunkZ));

                    java.util.concurrent.Future<float[]> seeding = grassInFlight.get(key);
                    float[] seeded = seeding == null ? prefetchGrass.remove(key) : null;
                    if (seeded != null) {
                        chunkGrassData = seeded;
                    } else if (seeding == null && (distanceFromPlayer <= 1 || forceImmediate)) {
                        chunkGrassData = buildGrassChunk(cx, cz);
                    } else if (seeding == null) {
                        final int gx = cx, gz = cz;
                        grassInFlight.put(key, terrainBuilders.submit(() -> buildGrassChunk(gx, gz)));
                        pending = true;
                        continue;
                    } else if (!seeding.isDone()) {
                        pending = true;
                        continue;
                    } else {
                        grassInFlight.remove(key);
                        try {
                            chunkGrassData = seeding.get();
                        } catch (Exception e) {
                            continue;
                        }
                    }
                    grassCache.put(key, chunkGrassData);
                    generatedThisFrame = true; 
                    grassDirty = true;
                    if (distanceFromPlayer <= 8) nearGrassArrived = true;
                }
                totalGrassInstances += (chunkGrassData.length / 5);
            }
        }

        // The grass buffer: gathered (thinned by distance from the player) on a worker thread
        // whenever its contents change, and uploaded here once ready
        if (grassGather != null && grassGather.isDone()) {
            try {
                Object[] gathered = grassGather.get();
                uploadGrass((float[]) gathered[0], (Integer) gathered[1]);
            } catch (Exception ignored) {
            }
            grassGather = null;
        }
        if (totalGrassInstances > 0 && grassDirty && grassGather == null
                && (forceImmediate || grassMovedChunk || nearGrassArrived || grassInFlight.isEmpty() || drawnGrassInstances == 0)) {
            nearGrassArrived = false;
            grassDirty = false;
            grassMovedChunk = false;
            List<float[]> chunks = new ArrayList<>();
            List<Integer> strides = new ArrayList<>();
            for (int cz = lastChunkZ - GRASS_VIEW_DISTANCE; cz <= lastChunkZ + GRASS_VIEW_DISTANCE; cz++) {
                for (int cx = lastChunkX - GRASS_VIEW_DISTANCE; cx <= lastChunkX + GRASS_VIEW_DISTANCE; cx++) {
                    float[] chunkGrassData = grassCache.get(cx + "_" + cz);
                    if (chunkGrassData == null) continue;
                    // Far grass is thinned: it shrinks below a pixel and fades into fog,
                    // but drawing every blade out to the horizon was the costliest pass
                    int ring = Math.max(Math.abs(cx - lastChunkX), Math.abs(cz - lastChunkZ));
                    chunks.add(chunkGrassData);
                    strides.add(ring <= 4 ? 1 : ring <= 8 ? 2 : ring <= 14 ? 4 : 8);
                }
            }
            int required = totalGrassInstances * 5;
            java.util.concurrent.Callable<Object[]> gather = () -> {
                float[] staging = new float[required];
                int staged = 0, written = 0;
                for (int i = 0; i < chunks.size(); i++) {
                    float[] data = chunks.get(i);
                    int stride = strides.get(i);
                    if (stride == 1) {
                        System.arraycopy(data, 0, staging, staged, data.length);
                        staged += data.length;
                        written += data.length / 5;
                    } else {
                        for (int blade = 0; blade * 5 < data.length; blade += stride) {
                            System.arraycopy(data, blade * 5, staging, staged, 5);
                            staged += 5;
                            written++;
                        }
                    }
                }
                return new Object[] { staging, written };
            };
            if (forceImmediate || drawnGrassInstances == 0) {
                try {
                    Object[] gathered = gather.call();
                    uploadGrass((float[]) gathered[0], (Integer) gathered[1]);
                } catch (Exception ignored) {
                }
            } else {
                grassGather = terrainBuilders.submit(gather);
            }
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
            String[] infraCoords = entry.getKey().split("_");
            if (Math.max(Math.abs(Integer.parseInt(infraCoords[0]) - lastChunkX), Math.abs(Integer.parseInt(infraCoords[1]) - lastChunkZ)) > reach + 1) {
                for (InfrastructureObject obj : entry.getValue()) {
                    obj.dispose(gl);
                }
                infraIterator.remove();
            }
        }

        // Buildings and roads are quick to make (well under a millisecond a chunk), so they get a
        // slice of the frame of their own rather than whatever the terrain and plants leave over;
        // otherwise, walking on, towns ahead would only appear once already close
        long infraDeadline = System.nanoTime() + INFRA_CHUNK_BUDGET_NANOS;
        for (int[] c : order) {
            int cx = c[0], cz = c[1];
            {
                String key = cx + "_" + cz;
                if (infraCache.containsKey(key)) continue;
                if (overRoundBudget(cx, cz) && System.nanoTime() > infraDeadline) {
                    pending = true;
                    continue;
                }
                {
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
        chunkWorkPending = pending;
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
        applyLook();

        

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

        Vector3 beforeMove = camera.getPosition();
        if (minimap != null && minimap.isFullScreenRevealMode()) {
            camera.updatePosition(false, false, false, false, (float)deltaTime);
        } else {
            camera.updatePosition(moveW, moveA, moveS, moveD, (float)deltaTime);
        }

        if (!menuShotHold) {
            moveSolidly(beforeMove);
            followThePlanet(beforeMove);
        }
        Vector3 currentPos = camera.getPosition();
        float rawGroundHeight = groundHeightAt(currentPos.x, currentPos.z);
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

        if (jumpWanted) {
            jumpWanted = false;
            if (!airborne && !swimming) {
                airborne = true;
                jumpVelocity = JUMP_SPEED;
                // From where the eyes are now (the walking height eases after the ground, so
                // starting from the ground itself would jolt)
                airborneEyeY = currentPos.y;
            }
        }
        if (airborne) {
            // A ballistic arc through the air, whatever the ground does beneath: it lands when
            // it comes down to the ground (going up a slope, sooner; off a drop, later)
            float dt = (float) deltaTime;
            airborneEyeY += (jumpVelocity - GRAVITY * dt * 0.5f) * dt;
            jumpVelocity -= GRAVITY * dt;
            jumpHeight = Math.max(0f, airborneEyeY - targetCameraHeight);
            if ((jumpVelocity <= 0f && airborneEyeY <= targetCameraHeight) || swimming) {
                jumpHeight = 0f;
                jumpVelocity = 0f;
                airborne = false;
                camera.setHeight(Math.max(airborneEyeY, targetCameraHeight - 0.5f));
            } else {
                camera.setHeight(Math.max(airborneEyeY, targetCameraHeight));
            }
        } else {
            float smoothedHeight = currentPos.y + (targetCameraHeight - currentPos.y) * dynamicSmoothingFactor;
            camera.setHeight(smoothedHeight);
        }

        lights[0].setPosition(getSunPosition());
        Vector3 sunPos = lights[0].getPosition();

        int currentChunkX = (int) Math.floor((camera.getPosition().x + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        int currentChunkZ = (int) Math.floor((camera.getPosition().z + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);

        trackWalking(camera.getPosition());
        boolean movedChunk = currentChunkX != lastChunkX || currentChunkZ != lastChunkZ;
        if (movedChunk) {
            grassDirty = true;   // the grass thins by distance from the player's chunk
            grassMovedChunk = true;
        }
        chunkScanCountdown--;
        // Waiting on work in the background: look in on it every few frames rather than rescanning every chunk each frame
        if (movedChunk || (chunkScanCountdown <= 0 && (chunkWorkPending || grassCache.size() < ((GRASS_VIEW_DISTANCE * 2 + 1) * (GRASS_VIEW_DISTANCE * 2 + 1))))) {
            chunkScanCountdown = 3;
            lastChunkX = currentChunkX;
            lastChunkZ = currentChunkZ;
            updateVisibleChunks(false);
        }

        // Night falls as the sun sinks: full day above 18 degrees, full night once it's 6 below
        float[] sunDir = Planet.sunDirection(currentPos.x, currentPos.z, sunDeclination, noonLongitude);
        float sunElevation = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, sunDir[1]))));
        nightProportion = Math.max(0f, Math.min(1f, (18f - sunElevation) / 24f));

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
        // The ground curves away with the planet
        float[] vp = viewProjection.toFloatArrayForGLSL();
        Vector3 eye = camera.getPosition();
        Shader.setPlanetCurve(gl, eye.x, eye.z, Planet.curvature(eye.z), new float[] { vp[4], vp[5], vp[6], vp[7] });
        depthPrePassShader.use(gl);

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
        
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, sceneFbo);   // (back to where the world is drawn)

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

        Matrix4 skyRotation = skyRotation(camPosForSky);

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

        // --- CLOUDS, over the sky and behind everything else ---
        weather.update((float) deltaTime, (float) elapsedTime, camera.getPosition(), () -> rainfallAt(camera.getPosition().x, camera.getPosition().z),
                this::surfaceHeightAt, freezingPoint - temperatureAt(camera.getPosition().x, camera.getPosition().z));
        float[] sunTintNow = worldArt.palette().sunTint;
        weather.renderClouds(gl, camera, (float) currentWidth / Math.max(1, currentHeight), skyColour, 1.0f - nightProportion, (float) elapsedTime,
                Planet.sunDirection(camera.getPosition().x, camera.getPosition().z, sunDeclination, noonLongitude), sunTintNow, fogColour(skyColour));
        // The fog everything fades into with distance
        float[] fog = fogColour(skyColour);
        Shader.setFog(gl, fog[0], fog[1], fog[2]);
        // The snow lying on the ground, once it's been worked out
        bindSnowCover(gl);

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
        terrainShader.setVec3(gl, "rockColour", worldRockColour());
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
                    obj.setViewerDistanceSquared(distSq);
                    obj.render(gl, ambientLight, nightProportion, signModelsByNation, postModelsByNation, null, 0, 0);
                    continue;
                }

                // Frustum / Distance Culling
                if (distSq > maxFloraDistSq) continue;

                                if (obj.type == InfrastructureObject.Type.SIGN) {
                    if (distSq > SIGN_DRAW_DISTANCE * SIGN_DRAW_DISTANCE
                            || !frustum.intersectsSphere(obj.position.x, obj.position.y + obj.drawnCentreY(), obj.position.z, obj.drawnRadius())) {
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
                obj.render(gl, ambientLight, nightProportion, signModelsByNation, postModelsByNation, atlas, atlasSize, writingDir,
                        textures.get(InfrastructureManager.nationTextureName("flag", obj.nationId)),
                        peoplePictures != null && obj.nationId > 0 && obj.nationId < peoplePictures.length ? peoplePictures[obj.nationId] : null);
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
        // The player is solid to everything else too; then this frame's bodies take over
        collision.addBody(PLAYER, camera.getPosition().x, camera.getPosition().z, PLAYER_RADIUS, true);
        collision.endFrame();
        inhabitants.render(gl, viewProjection, frustum, camera.getPosition(), sunPos,
                new float[] { sunTintForCreatures[0] * daylight, sunTintForCreatures[1] * daylight, sunTintForCreatures[2] * daylight },
                ambientLight, skyRotation, textures.get(skyTextureKey), (float) elapsedTime);

        // --- INSTANCED GRASS PASS ---
        if (drawnGrassInstances > 0) {
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
            gl.glDrawArraysInstanced(GL3.GL_TRIANGLES, 0, 6, drawnGrassInstances);
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

        // --- ROCKS ---
        rockField.update(gl, currentPos.x, currentPos.z, false);
        gl.glDisable(GL.GL_CULL_FACE);
        rockField.render(gl, viewProjection, frustum, camera.getPosition(), sunPos,
                new float[] { sunTintForCreatures[0] * daylight, sunTintForCreatures[1] * daylight, sunTintForCreatures[2] * daylight },
                ambientLight, skyRotation, textures.get(skyTextureKey));
        gl.glEnable(GL.GL_CULL_FACE);

        // --- THE LANDING POD ---
        landingPod.render(gl, viewProjection, camera.getPosition(), sunPos,
                new float[] { sunTintForCreatures[0] * daylight, sunTintForCreatures[1] * daylight, sunTintForCreatures[2] * daylight },
                ambientLight, skyRotation, textures.get(skyTextureKey));

        // --- WINDOW GLASS: see-through, so drawn after everything solid, without hiding what's behind ---
        drawWindowGlass(gl, viewProjection, frustum, currentPos, sunPos,
                new float[] { sunTintForCreatures[0] * daylight, sunTintForCreatures[1] * daylight, sunTintForCreatures[2] * daylight },
                skyRotation, textures.get(skyTextureKey), maxFloraRenderDistance);

        // --- RAIN, under dark clouds ---
        weather.renderRain(gl, viewProjection, worldArt.palette().seaShallowTint(), 1.0f - nightProportion,
                (float) (currentHeight * 0.5 / Math.tan(Math.toRadians(45.0 / 2.0))));

        if (hideFirstPerson) return;

        // --- THE PLAYER'S OWN BODY, drawn last and nearest ---
        if (compassWanted) {
            compassWanted = false;
            playerBody.useCompass();
        }
        if (thermometerWanted) {
            thermometerWanted = false;
            playerBody.useThermometer();
        }
        Vector3 standing = camera.getPosition();
        playerBody.setReading(temperatureAt(standing.x, standing.z));
        playerBody.update((float) deltaTime, camera.getPosition(), swimming);
        float[] deep = worldArt.palette().seaShallowTint();
        float daylightOnBody = 1.0f - nightProportion;
        playerBody.render(gl, camera, (float) currentWidth / Math.max(1, currentHeight), sunPos,
                new float[] { sunTint[0] * daylightOnBody, sunTint[1] * daylightOnBody, sunTint[2] * daylightOnBody },
                ambientLight, skyRotation, textures.get(skyTextureKey), waterSurfaceHere, deep);
    }

    /**
     * The HUD over the finished frame: the round and score at the top left, the FPS in the
     * top right corner, the inventory at the bottom left and the map's keys above the map.
     * Each picture is repainted only when what it shows changes.
     */
    private void drawHud(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        // Pictures are painted at the screen's pixel density so they stay sharp when Windows scales the display
        float scale = drawable instanceof java.awt.Component c && c.getWidth() > 0 ? screenWidth / (float) c.getWidth() : 1f;
        boolean rescaled = scale != hudScale;
        hudScale = scale;
        int margin = Math.round(HudStyle.HUD_MARGIN * scale);
        java.awt.Graphics2D[] g = new java.awt.Graphics2D[1];

        if (gameHUD != null && (rescaled || gameHUD.getVersion() != hudScoreVersion)) {
            hudScoreVersion = gameHUD.getVersion();
            BufferedImage image = HudStyle.canvas(HudStyle.SCORE_W, HudStyle.SCORE_H, scale, g);
            gameHUD.paintPanel(g[0], true);
            g[0].dispose();
            hud.put("score", image, margin, margin);
        }

        if (GameSettings.showFps() && gameHUD != null) {
            int fps = gameHUD.getGameFps();
            if (rescaled || fps != hudFps || !hud.has("fps")) {
                hudFps = fps;
                BufferedImage image = HudStyle.canvas(HudStyle.FPS_W, HudStyle.FPS_H, scale, g);
                HudStyle.paintFps(g[0], fps);
                g[0].dispose();
                hud.put("fps", image, screenWidth - image.getWidth() - Math.round(8 * scale), Math.round(6 * scale));
            }
        } else {
            hud.setVisible("fps", false);
            hudFps = -1;
        }

        if (rescaled || !hud.has("inventory")) {
            java.util.List<HudStyle.Item> items = java.util.List.of(new HudStyle.Item("Compass", "1", HudStyle::paintCompassIcon),
                    new HudStyle.Item("Thermometer", "2", HudStyle::paintThermometerIcon));
            int h = HudStyle.inventoryHeight(items.size());
            BufferedImage image = HudStyle.canvas(HudStyle.INVENTORY_W, h, scale, g);
            HudStyle.paintInventory(g[0], items);
            g[0].dispose();
            hud.put("inventory", image, margin, 0);
        }
        // (kept at the bottom however the window is resized)
        if (hud.has("inventory")) {
            int h = Math.round(HudStyle.inventoryHeight(2) * scale);
            hud.move("inventory", margin, screenHeight - h - margin);
        }

        if (minimap != null) {
            MapPanel.MapSize size = minimap.getMapSize();
            if (rescaled || size != hudMapSize) {
                hudMapSize = size;
                String[] keys = switch (size) {
                    case SMALL -> new String[] { "M", "Expand map" };
                    case LARGE -> new String[] { "M", "Contract map", "", "Click the map to place your marker" };
                };
                BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D pg = probe.createGraphics();
                HudStyle.smooth(pg);
                int w = HudStyle.hintWidth(pg, keys);
                pg.dispose();
                BufferedImage image = HudStyle.canvas(w, HudStyle.HINT_H, scale, g);
                HudStyle.paintHint(g[0], w, keys);
                g[0].dispose();
                hud.put("mapHint", image, 0, 0);
            }
            // Just above the map, lined up with its right-hand edge
            java.awt.Rectangle map = minimap.getBounds();
            java.awt.Component view = drawable instanceof java.awt.Component c ? c : null;
            if (view != null) map.translate(-view.getX(), -view.getY());
            hud.move("mapHint", Math.round((map.x + map.width - 12) * scale) - hud.width("mapHint"),
                    Math.round((map.y - 8) * scale) - Math.round(HudStyle.HINT_H * scale));
        }

        hud.setDim(menuOpen ? 0.45f : 0f);
        hud.draw(gl, screenWidth, screenHeight);
    }

    private double getSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }

    public static float precalculateSeaLevel(long seed, float totalRegionWidth, PerlinNoise noise) {
        java.util.Random rand = new java.util.Random(seed);
        float waterProportion = 0.6f + rand.nextFloat() * 0.1f; 
        // Over the part of the planet the chart shows
        float halfRegion = totalRegionWidth / 2.0f;
        float halfHeight = Planet.clipHalfHeight();
        
        int totalSamples = 4000;
        java.util.ArrayList<Float> heightSamples = new java.util.ArrayList<>(totalSamples);

        for (int i = 0; i < totalSamples; i++) {
            float sampleX = (rand.nextFloat() * totalRegionWidth) - halfRegion;
            // Evenly over the sphere's surface, not the map, which stretches the far north and south
            float sampleZ = (float) Planet.chartZ(Math.asin((rand.nextFloat() * 2f - 1f) * Math.sin(Planet.CLIP_LATITUDE)));
            
            float h = TerrainMesh.getLayeredHeight(sampleX, sampleZ, noise);
            heightSamples.add(h);
        }

        java.util.Collections.sort(heightSamples);
        int targetIndex = (int)(heightSamples.size() * waterProportion);
        if (targetIndex >= heightSamples.size()) targetIndex = heightSamples.size() - 1;
        
        float calculatedSeaLevel = heightSamples.get(targetIndex);
        return calculatedSeaLevel;
    }

    // Layers the developer aid asks to start ticked
    private final java.util.Set<MapPanel.Layer> startingLayers = java.util.EnumSet.noneOf(MapPanel.Layer.class);
    // Gradient maps already worked out (they take a few seconds each)
    private final Map<Overlay, BufferedImage> gradientCache = new java.util.concurrent.ConcurrentHashMap<>();

    public void setMinimap(MapPanel minimap) {
        this.minimap = minimap;
        if (!IS_DEBUG_MODE_ACTIVE) return;
        for (MapPanel.Layer layer : startingLayers) minimap.setLayerShown(layer, true);
        java.util.LinkedHashMap<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, List<Overlay>> group : overlayGroups().entrySet()) {
            List<String> labels = new ArrayList<>();
            for (Overlay o : group.getValue()) labels.add(overlayLabel(o));
            groups.put(group.getKey(), labels);
        }
        minimap.setLayerChoices(groups, currentDebugFactor != null ? overlayLabel(currentDebugFactor) : null, label -> {
            Overlay chosen = null;
            for (Overlay o : allOverlays()) if (overlayLabel(o).equals(label)) chosen = o;
            chooseGradient(chosen);
        }, this::choiceIcon);
        if (currentDebugFactor != null) chooseGradient(currentDebugFactor);
    }

    /**
     * Colours the map's land by an overlay, or by nothing given null. Every overlay is
     * worked out in the background soon after the world is made, so it's normally ready at
     * once; one asked for before then is finished off in the background and shown when ready,
     * if it's still the one wanted.
     */
    // The dropdowns at the foot of the map's overlay choices, in order
    private static final String GRASS_GROUP = "Grass", FLORA_GROUP = "Flora", FAUNA_GROUP = "Fauna";

    /**
     * The map's overlays by where they're listed: "" for those listed on their own, then the
     * grass's maps, each plant species' range (trees, then shrubs), and each animal species' range.
     */
    private java.util.LinkedHashMap<String, List<Overlay>> overlayGroups() {
        java.util.LinkedHashMap<String, List<Overlay>> groups = new java.util.LinkedHashMap<>();
        List<Overlay> top = new ArrayList<>();
        for (FactorName f : new FactorName[] { FactorName.TEMPERATURE, FactorName.RAINFALL, FactorName.SOIL_COLOUR, FactorName.WEALTH, FactorName.NATIONS }) {
            top.add(Overlay.of(f));
        }
        groups.put("", top);
        groups.put(GRASS_GROUP, List.of(Overlay.of(FactorName.GRASS_ABUNDANCE), Overlay.of(FactorName.GRASS_HEIGHT), Overlay.of(FactorName.GRASS_COLOUR)));
        List<Overlay> flora = new ArrayList<>();
        for (int s = 0; s < NUM_SPECIES; s++) flora.add(new Overlay(FactorName.FLORA, s));
        groups.put(FLORA_GROUP, flora);
        List<Overlay> fauna = new ArrayList<>();
        for (int i = 0; i < organismManager.speciesCount(); i++) fauna.add(new Overlay(FactorName.ANIMAL_POPULATION, i));
        groups.put(FAUNA_GROUP, fauna);
        return groups;
    }

    /** Every overlay the map offers. */
    private List<Overlay> allOverlays() {
        List<Overlay> overlays = new ArrayList<>();
        for (List<Overlay> group : overlayGroups().values()) overlays.addAll(group);
        return overlays;
    }

    /** An overlay's name on the map: its factor's, or for a species, the species' own name. */
    private String overlayLabel(Overlay o) {
        if (o.species() >= 0 && o.factor() == FactorName.FLORA) return floraName(o.species());
        return o.species() >= 0 ? organismManager.speciesName(o.species()) : o.factor().label();
    }

    // Each plant species' name (see floraName)
    private List<String> floraNames;

    /**
     * Plant species s's name, made as the planet's and the animals' are, and the same as none of
     * them or another plant's.
     */
    private synchronized String floraName(int s) {
        if (floraNames == null) {
            List<String> names = new ArrayList<>();
            java.util.Set<String> taken = new java.util.HashSet<>();
            taken.add(PlanetName.forSeed(worldSeed).spelling.toLowerCase());
            for (int i = 0; i < organismManager.speciesCount(); i++) taken.add(organismManager.speciesName(i).toLowerCase());
            for (int k = 0; k < NUM_SPECIES; k++) {
                String name = null;
                for (int attempt = 0; name == null || !taken.add(name.toLowerCase()); attempt++) {
                    name = PlanetName.forSeed(worldSeed * 0x5851F42D4C957F2DL + (k + 1) * 2000003L + attempt * 6007L).spelling;
                }
                names.add(name);
            }
            floraNames = names;
        }
        return floraNames.get(s);
    }

    // The icons for the map's choices (assets/icons), by file name, and each species' picture
    // once drawn (see drawSpeciesPortraits)
    private final Map<String, java.awt.Image> choiceIcons = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.awt.Image[] speciesPortraits;

    /** The icon for one of the map's choices, by its name (a layer's or an overlay's); null if none. */
    private java.awt.Image choiceIcon(String label) {
        for (Overlay o : allOverlays()) {
            if (o.species() >= 0 && overlayLabel(o).equals(label)) {
                java.awt.Image[] portraits = o.factor() == FactorName.FLORA ? floraPortraits : speciesPortraits;
                if (portraits != null && portraits[o.species()] != null) return portraits[o.species()];
                return loadIcon(o.factor() == FactorName.FLORA ? "flora" : "animal");
            }
        }
        String file = switch (label) {
            case "Contours" -> "contours";
            case "Roads" -> "roads";
            case "Buildings" -> "buildings";
            case "Shops" -> "shops";
            case "None" -> "none";
            case "Temperature" -> "temperature";
            case "Rainfall" -> "rainfall";
            case "Soil colour" -> "soil";
            case "Wealth" -> "wealth";
            case "Nations" -> "nations";
            case "Grass abundance", GRASS_GROUP -> "grass";
            case "Grass height" -> "grass_height";
            case "Grass colour" -> "grass_colour";
            case FLORA_GROUP -> "flora";
            case FAUNA_GROUP -> "animal";
            default -> null;
        };
        return file == null ? null : loadIcon(file);
    }

    private java.awt.Image loadIcon(String file) {
        return choiceIcons.computeIfAbsent(file, f -> {
            try {
                return ImageIO.read(new File(GamePaths.HOME + "assets/icons/" + f + ".png"));
            } catch (Exception e) {
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            }
        });
    }

    // Each plant species' picture for the map, once drawn
    private volatile java.awt.Image[] floraPortraits;
    // The animal or plant to show turning beside the map: wanted, being drawn (its frames so far)
    private volatile Overlay previewWanted;
    private Overlay previewBuilding;
    private BufferedImage[] previewRaw;
    private int previewNext;
    // The turning picture's frames (all the way round), their size, and how many drawn a frame
    private static final int PREVIEW_FRAMES = 36, PREVIEW_SIZE = 256, PREVIEW_RENDER = 512, PREVIEWS_PER_FRAME = 4;

    /**
     * Draws, a few at a time between frames, the plants' pictures for the map's choices (once),
     * and the frames of the chosen animal or plant turning round, handing them to the map when done.
     */
    private void advancePreviews(GL3 gl) {
        if (!worldReady || roundLoading || minimap == null) return;
        if (floraPortraits == null) {
            java.awt.Image[] portraits = new java.awt.Image[NUM_SPECIES];
            for (int s = 0; s < NUM_SPECIES; s++) {
                BufferedImage view = floraView(gl, s, PREVIEW_RENDER, (float) Math.toRadians(30));
                portraits[s] = view == null ? null : Offscreen.cropTogether(new BufferedImage[] { view }, 96)[0];
            }
            floraPortraits = portraits;
            // (a plant's range already chosen gets its picture now)
            Overlay shown = currentDebugFactor;
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (shown != null && shown.factor() == FactorName.FLORA && shown == currentDebugFactor) minimap.setOverlayPicture(portraits[shown.species()]);
                minimap.repaint();
            });
        }
        Overlay wanted = previewWanted;
        if (wanted == null) return;
        if (previewBuilding != wanted) {
            previewBuilding = wanted;
            previewRaw = new BufferedImage[PREVIEW_FRAMES];
            previewNext = 0;
        }
        Vector3 here = camera.getPosition();
        for (int k = 0; k < PREVIEWS_PER_FRAME && previewNext < PREVIEW_FRAMES; k++, previewNext++) {
            float angle = (float) (Math.PI * 2 * previewNext / PREVIEW_FRAMES);
            previewRaw[previewNext] = wanted.factor() == FactorName.FLORA ? floraView(gl, wanted.species(), PREVIEW_RENDER, angle)
                    : organismManager.view(gl, wanted.species(), PREVIEW_RENDER, angle, here.x, here.z);
            if (previewRaw[previewNext] == null) previewRaw[previewNext] = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
        if (previewNext < PREVIEW_FRAMES) return;
        BufferedImage[] frames = Offscreen.cropTogether(previewRaw, PREVIEW_SIZE);
        previewRaw = null;
        previewWanted = null;
        previewBuilding = null;
        String name = overlayLabel(wanted);
        String kind = wanted.factor() == FactorName.FLORA ? (wanted.species() < 4 ? "Tree" : "Shrub") : "Animal";
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (currentDebugFactor != null && currentDebugFactor.equals(wanted)) {
                minimap.setPreview(name, kind, frames, wanted.factor() == FactorName.FLORA ? leafPicture(wanted.species()) : null);
            }
        });
    }

    /** Plant species s's leaf, in its own colours (its texture runs from the dark shade to the light). */
    private BufferedImage leafPicture(int s) {
        BufferedImage leaf = worldArt.leaf(speciesConfigs[s].leafTexNum);
        if (leaf == null) return null;
        Vector3 dark = leafDarkColour(speciesConfigs[s].healthyColor), light = leafLightColour(speciesConfigs[s].healthyColor);
        BufferedImage coloured = new BufferedImage(leaf.getWidth(), leaf.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < leaf.getHeight(); y++) {
            for (int x = 0; x < leaf.getWidth(); x++) {
                int argb = leaf.getRGB(x, y);
                float t = ((argb >> 16) & 0xFF) / 255f;
                int r = Math.round((dark.x + (light.x - dark.x) * t) * 255f), g = Math.round((dark.y + (light.y - dark.y) * t) * 255f),
                        b = Math.round((dark.z + (light.z - dark.z) * t) * 255f);
                coloured.setRGB(x, y, (argb & 0xFF000000) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b));
            }
        }
        return coloured;
    }

    /**
     * A plant of species s turned to angle, seen from three-quarters on in a wide view (render
     * pixels square, clear round it); null if its model isn't made yet. Drawn just under where
     * the viewer is (so the planet's curve leaves it be) by pointing the camera at it a moment.
     */
    private BufferedImage floraView(GL3 gl, int s, int render, float angle) {
        Model branch = null, leaves = null;
        for (int lod = 0; lod < floraBranchModelsLOD[s].length && branch == null; lod++) {
            for (int v = 0; v < FLORA_VARIATIONS; v++) {
                if (floraBranchModelsLOD[s][lod][v] != null && floraLeafModelsLOD[s][lod][v] != null) {
                    branch = floraBranchModelsLOD[s][lod][v];
                    leaves = floraLeafModelsLOD[s][lod][v];
                    break;
                }
            }
        }
        if (branch == null) return null;
        SpeciesConfig sc = speciesConfigs[s];
        Vector3 eyeBefore = camera.getPosition(), frontBefore = camera.getForwardDirection();
        Matrix4 lensBefore = camera.getPerspectiveMatrix();
        // (framed generously, the tallest trees whole: the picture is cut down to the plant afterwards)
        // (shrubs are much smaller than trees, so framed closer: otherwise they come out tiny and blurred)
        float reach = FLORA_CULL_RADIUS * (s < 4 ? 1.0f : 0.2f);
        Vector3 base = new Vector3(eyeBefore.x, eyeBefore.y - 3000f, eyeBefore.z);
        Matrix4 model = Matrix4.multiply(Matrix4Transform.translate(base), Matrix4Transform.rotateAroundY((float) Math.toDegrees(angle)));
        Vector3 centre = new Vector3(base.x, base.y + reach * 0.7f, base.z);
        float distance = reach * 3.6f;
        Vector3 eye = new Vector3(centre.x + distance * 0.8f, centre.y + distance * 0.25f, centre.z + distance * 0.55f);
        final Model trunk = branch, foliage = leaves;
        BufferedImage image;
        try {
            camera.setPosition(eye);
            camera.setTarget(centre);
            camera.setPerspectiveMatrix(Matrix4Transform.perspective(40f, 1f, 1f, distance * 4f));
            Vector3 light = new Vector3(0.62f, 0.62f, 0.66f);
            image = Offscreen.capture(gl, render, () -> {
                gl.glDisable(GL3.GL_CULL_FACE);
                terrainShader.use(gl);
                terrainShader.setFloat(gl, "useSoilRegions", 0.0f);
                trunk.setModelMatrix(model);
                trunk.render(gl, light, 0f);
                leafShader.use(gl);
                leafShader.setVec3(gl, "u_LeafDarkColor", leafDarkColour(sc.healthyColor));
                leafShader.setVec3(gl, "u_LeafLightColor", leafLightColour(sc.healthyColor));
                foliage.setModelMatrix(model);
                foliage.render(gl, light, 0f);
                gl.glEnable(GL3.GL_CULL_FACE);
            });
        } finally {
            camera.setPosition(eyeBefore);
            camera.setTarget(Vector3.add(eyeBefore, frontBefore));
            camera.setPerspectiveMatrix(lensBefore);
        }
        return image;
    }

    /** Draws each species' picture for the map (with the GL context, once its meshes are made). */
    private void drawSpeciesPortraits(GL3 gl) {
        java.awt.Image[] portraits = new java.awt.Image[organismManager.speciesCount()];
        for (int i = 0; i < portraits.length; i++) portraits[i] = organismManager.portrait(gl, i, 96);
        speciesPortraits = portraits;
        if (minimap == null) return;
        // (a species' overlay already chosen gets its picture now)
        Overlay shown = currentDebugFactor;
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (shown != null && shown.species() >= 0 && shown.factor() == FactorName.ANIMAL_POPULATION && shown == currentDebugFactor) {
                minimap.setOverlayPicture(portraits[shown.species()]);
            }
            minimap.repaint();
        });
    }

    public void chooseGradient(Overlay factorName) {
        currentDebugFactor = factorName;
        java.awt.Image[] portraits = factorName != null && factorName.factor() == FactorName.FLORA ? floraPortraits : speciesPortraits;
        minimap.setOverlayPicture(factorName != null && factorName.species() >= 0 && portraits != null ? portraits[factorName.species()] : null);
        FactorName factor = factorName == null ? null : factorName.factor();
        // An animal's or plant's range: it's shown turning round beside the map
        if (factorName != null && factorName.species() >= 0) {
            boolean plant = factor == FactorName.FLORA;
            minimap.setPreview(overlayLabel(factorName), plant ? (factorName.species() < 4 ? "Tree" : "Shrub") : "Animal", null,
                    plant ? leafPicture(factorName.species()) : null);
            previewWanted = factorName;
        } else {
            minimap.setPreview(null, null, null, null);
            previewWanted = null;
        }
        // The key for the overlays measured in real units
        if (factor == FactorName.TEMPERATURE) {
            minimap.setLegend(RegionalGenerationManager.TEMPERATURE_SPECTRUM, new String[] {
                    celsiusLabel(0f), celsiusLabel(0.5f), celsiusLabel(1f) });
        } else if (factor == FactorName.RAINFALL) {
            minimap.setLegend(RegionalGenerationManager.RAINFALL_SPECTRUM, new String[] {
                    "0 mm/yr", Math.round(0.5f * RAINFALL_FULL_MM) + " mm/yr", Math.round(RAINFALL_FULL_MM) + " mm/yr" });
        } else if (factorName != null && factorName.species() >= 0) {
            // How many per square kilometre (a hundred chunks by a hundred), none to the most
            int sp = factorName.species();
            float peak = factor == FactorName.FLORA ? speciesConfigs[sp].baseAbundance * mapPeak(-1 - sp, (x, z) -> speciesConfigs[sp].abundanceFactor
                    .evaluate((int) Math.floor(x / PHYSICAL_CHUNK_SIZE), (int) Math.floor(z / PHYSICAL_CHUNK_SIZE), x, z))
                    : animalMapPeak(sp);
            float perKm = peak * CHUNKS_PER_KM2;
            minimap.setLegend(RANGE_SPECTRUM, new String[] { "0 /km²", countLabel(perKm * 0.5f) + " /km²", countLabel(perKm) + " /km²" });
        } else {
            minimap.setLegend(null, null);
        }
        minimap.setOverlayTiles(overlayTiles(factorName), factorName == null ? null : overlayLabel(factorName));
        if (factorName == null) {
            minimap.setOverlay(null, null, false);
            return;
        }
        BufferedImage ready = gradientCache.get(factorName);
        if (ready != null) {
            minimap.setOverlay(ready, overlayLabel(factorName), factor == FactorName.NATIONS);
            return;
        }
        // (its tiles once the overlay itself is ready)
        Thread heatmapThread = new Thread(() -> {
            BufferedImage composed = overlayImage(factorName);
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (currentDebugFactor == factorName && composed != null) {
                    minimap.setOverlayTiles(overlayTiles(factorName), overlayLabel(factorName));
                    minimap.setOverlay(composed, overlayLabel(factorName), factor == FactorName.NATIONS);
                }
            });
        }, "gradient-map");
        heatmapThread.setDaemon(true);
        heatmapThread.start();
    }

    /**
     * The soil overlay: the soil's actual colour in every chunk, blended just as the ground's
     * is (see buildSoilRegionMap): the base soil, the first regional soil blended in as far as
     * its factor says, then the second over that as far as its own says.
     */
    private BufferedImage soilColourMap() {
        WorldPalette palette = new WorldPalette(worldSeed);
        float[] base = palette.soilBase, a = palette.soilRegionalA, b = palette.soilRegionalB;
        float[][] ranges = soilBlendRanges;
        int minChunkX = (int) Math.floor((-TOTAL_REGION_WIDTH / 2.0f) / PHYSICAL_CHUNK_SIZE);
        int maxChunkX = (int) Math.ceil((TOTAL_REGION_WIDTH / 2.0f) / PHYSICAL_CHUNK_SIZE);
        int size = maxChunkX - minChunkX + 1;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.util.stream.IntStream.range(0, size).parallel().forEach(z -> {
            int cz = minChunkX + z;
            for (int x = 0; x < size; x++) {
                int cx = minChunkX + x;
                float worldX = (cx + 0.5f) * PHYSICAL_CHUNK_SIZE, worldZ = (cz + 0.5f) * PHYSICAL_CHUNK_SIZE;
                float fa = soilVariantAFactor.evaluate(cx, cz, worldX, worldZ), fb = soilVariantBFactor.evaluate(cx, cz, worldX, worldZ);
                float wa = ranges == null ? 0f : ProceduralTextures.smoothstep(ranges[0][0], ranges[0][1], fa);
                float wb = ranges == null ? 0f : ProceduralTextures.smoothstep(ranges[1][0], ranges[1][1], fb);
                int rgb = 0xFF;
                for (int c = 0; c < 3; c++) {
                    float colour = base[c] + (a[c] - base[c]) * wa;
                    colour += (b[c] - colour) * wb;
                    rgb = (rgb << 8) | Math.max(0, Math.min(255, Math.round(colour * 255f)));
                }
                image.setRGB(x, z, rgb);
            }
        });
        return image;
    }

    // Each overlay as worked out, a pixel a chunk (before being laid over the land for the map)
    private final Map<Overlay, BufferedImage> rawOverlays = new java.util.concurrent.ConcurrentHashMap<>();

    /** How to draw an overlay in tiles when the map is zoomed in close; null if it can't be yet. */
    private MapTiles.Renderer overlayTiles(Overlay factorName) {
        BirdsEyeMaps maps = birdsEyeMaps;
        if (factorName == null || maps == null) return null;
        if (factorName.factor() == FactorName.NATIONS) return maps.nationTiles(nationManager);
        BufferedImage raw = rawOverlays.get(factorName);
        return raw == null ? null : maps.overlayTiles(raw, PHYSICAL_CHUNK_SIZE);
    }

    /**
     * An overlay ready to show on the map, worked out the first time it's asked for and kept
     * (a second asker waits for the first to finish rather than doing it again).
     */
    private BufferedImage overlayImage(Overlay overlay) {
        if (minimap == null) return null;
        return gradientCache.computeIfAbsent(overlay, o -> {
            FactorName f = o.factor();
            if (f == FactorName.NATIONS) {
                // Each nation filled in its own colour, chosen to stand apart from all the others,
                // drawn sharp enough for its borders to stay smooth when zoomed in
                BirdsEyeMaps maps = birdsEyeMaps;
                return maps != null ? maps.renderNationOverlay(nationManager) : null;
            }
            BufferedImage heatmap = f == FactorName.SOIL_COLOUR ? soilColourMap()
                    : regionalManager.generateHeatmap(TOTAL_REGION_WIDTH, PHYSICAL_CHUNK_SIZE, factorFor(o), overlayLabel(o),
                    f == FactorName.TEMPERATURE ? RegionalGenerationManager.TEMPERATURE_SPECTRUM : f == FactorName.WEALTH ? WEALTH_SPECTRUM
                    : f == FactorName.RAINFALL ? RegionalGenerationManager.RAINFALL_SPECTRUM : null);
            // (kept as it is too, a pixel a chunk, for drawing the overlay in tiles when zoomed in)
            rawOverlays.put(o, heatmap);
            return minimap.composeOverlay(heatmap);
        });
    }

    // The wealth overlay: how well off people are, from each nation's richness, more so in town
    private RegionalFactor wealthOverlay;
    // Its colours, poorest to richest: dull brown through to gold
    private static final float[][] WEALTH_SPECTRUM = {
        { 0.30f, 0.22f, 0.18f }, { 0.58f, 0.48f, 0.40f }, { 0.85f, 0.75f, 0.45f }, { 1.00f, 0.82f, 0.10f }
    };
    // Where each regional soil colour starts and finishes blending in (from its factor's spread of values)
    private volatile float[][] soilBlendRanges;

    private RegionalFactor factorFor(Overlay overlay) {
        // One species' range: how many there are, from none to the most anywhere (as they're
        // spawned: a plant's count is its abundance times its base; an animal's, see expectedPerChunk)
        if (overlay.species() >= 0 && overlay.factor() == FactorName.FLORA) {
            RegionalFactor abundance = speciesConfigs[overlay.species()].abundanceFactor;
            float peak = mapPeak(-1 - overlay.species(), (x, z) -> abundance.evaluate((int) Math.floor(x / PHYSICAL_CHUNK_SIZE),
                    (int) Math.floor(z / PHYSICAL_CHUNK_SIZE), x, z));
            return new RegionalFactor(1f, (cx, cz, x, z) -> abundance.evaluate(cx, cz, x, z) / peak);
        }
        if (overlay.species() >= 0) {
            int i = overlay.species();
            float peak = animalMapPeak(i);
            return new RegionalFactor(1f, (cx, cz, x, z) -> organismManager.expectedPerChunk(i, x, z) / peak);
        }
        return switch (overlay.factor()) {
            case GRASS_ABUNDANCE -> grassAbundanceFactor;
            case GRASS_HEIGHT -> grassHeightFactor;
            case GRASS_COLOUR -> grassColourFactor;
            case FLORA -> null;
            // Wettest at the water's edge, drying out inland
            case TEMPERATURE -> regionalManager.temperatureMap;
            case RAINFALL -> regionalManager.rainfallMap;
            case SOIL_COLOUR -> null;
            case NATIONS -> null;
            case WEALTH -> {
                if (wealthOverlay == null) {
                    wealthOverlay = new RegionalFactor(1f, (cx, cz, x, z) -> {
                        int nation = nationManager.getNationAtWorld(x, z, TOTAL_REGION_WIDTH);
                        return nation <= 0 ? 0f : infraManager.areaRichness(nation, settlementManager.getUrbanness(x, z), x, z);
                    });
                }
                yield wealthOverlay;
            }
            case ANIMAL_POPULATION -> null;
        };
    }

    // Mouse look gathered on the UI thread, applied at the start of the next frame
    private float lookYaw, lookPitch;

    /** Turns the view; safe to call from any thread. */
    public synchronized void look(float yaw, float pitch) {
        lookYaw += yaw;
        lookPitch += pitch;
    }

    private synchronized void applyLook() {
        if (lookYaw != 0f || lookPitch != 0f) camera.updateYawPitch(lookYaw, lookPitch);
        lookYaw = 0f;
        lookPitch = 0f;
    }

    /** C: the player takes out the compass and looks at it for a moment. */
    public void useCompass() {
        compassWanted = true;
    }

    /** 2: the player takes out the thermometer and reads it for a moment. */
    public void useThermometer() {
        thermometerWanted = true;
    }

    private volatile boolean thermometerWanted;

    /**
     * What a thermometer reads at (x, z), in degrees Celsius: the place's warmth as the
     * temperature overlay shows it (warmer towards the equator and inland in the tropics, colder
     * up high, varying from region to region), warmer in that hemisphere's summer and colder in
     * its winter (the more so the further from the equator), and warmest in the mid afternoon,
     * coolest round dawn, by this round's sun.
     */
    /** What rain or snow lands on at a point: the ground (or a road), a roof, or a rock, whichever is highest. */
    private float surfaceHeightAt(float x, float z) {
        float top = groundHeightAt(x, z);
        float roof = infraManager != null ? infraManager.roofAt(x, z) : Float.NaN;
        if (!Float.isNaN(roof)) top = Math.max(top, roof);
        float rock = rockField != null ? rockField.topAt(x, z, top) : Float.NaN;
        if (!Float.isNaN(rock)) top = Math.max(top, rock);
        return top;
    }

    // The climate for the snow over the whole world (see snowClimate): a pixel per this many to
    // a side, its texture unit, and how many degrees below freezing it takes for snow to lie thickly
    private static final int SNOW_COVER_SIZE = 512, SNOW_UNIT = 11;
    private static final float SNOW_LIES_THICK = 5f;
    private java.util.concurrent.Future<byte[]> snowCoverJob;
    private int snowCoverTexture;

    /**
     * The climate at a place as the snow needs it, each 0 to 1 for the texture: the temperature
     * as it would be at sea level (from -60 to 40 Celsius; the shader takes off for height), and
     * how much rain or snow falls.
     */
    private float[] snowClimate(float x, float z) {
        int cx = (int) Math.floor(x / PHYSICAL_CHUNK_SIZE), cz = (int) Math.floor(z / PHYSICAL_CHUNK_SIZE);
        float warmth = regionalManager.temperatureMap.evaluate(cx, cz, x, z) + regionalManager.altitudeChill(cx, cz);
        float celsius = -30f + 70f * warmth;
        return new float[] { Math.max(0f, Math.min(1f, (celsius + 60f) / 100f)), regionalManager.rainfallMap.evaluate(cx, cz, x, z) };
    }

    // How far either way the snow's climate is averaged over, in texture pixels (each a few chunks)
    private static final int SNOW_SMOOTHING = 3;

    /** Averages the snow's climate (both channels) over its neighbours, east-west then north-south, twice over. */
    private static void smoothSnowClimate(byte[] cover) {
        int n = SNOW_COVER_SIZE;
        float[] a = new float[n * n * 2], b = new float[n * n * 2];
        for (int k = 0; k < a.length; k++) a[k] = cover[k] & 0xFF;
        for (int pass = 0; pass < 4; pass++) {
            boolean across = pass % 2 == 0;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    for (int c = 0; c < 2; c++) {
                        float sum = 0f;
                        for (int d = -SNOW_SMOOTHING; d <= SNOW_SMOOTHING; d++) {
                            // (round the world east and west; held at the edge north and south)
                            int x = across ? Math.floorMod(i + d, n) : i, y = across ? j : Math.max(0, Math.min(n - 1, j + d));
                            sum += a[(y * n + x) * 2 + c];
                        }
                        b[(j * n + i) * 2 + c] = sum / (2 * SNOW_SMOOTHING + 1);
                    }
                }
            }
            float[] t = a; a = b; b = t;
        }
        for (int k = 0; k < a.length; k++) cover[k] = (byte) Math.round(Math.max(0f, Math.min(255f, a[k])));
    }

    /** The colour of snow: mostly white, with something of the planet's oceans in it. */
    private float[] snowColour() {
        float[] ocean = worldArt.palette().seaShallowTint();
        return new float[] { 0.45f * ocean[0] + 0.55f, 0.45f * ocean[1] + 0.55f, 0.45f * ocean[2] + 0.55f };
    }

    /** Works out the lying snow over the world in the background, then hands it to every shader. */
    private void bindSnowCover(GL3 gl) {
        if (snowCoverJob == null) {
            snowCoverJob = java.util.concurrent.ForkJoinPool.commonPool().submit(() -> {
                byte[] cover = new byte[SNOW_COVER_SIZE * SNOW_COVER_SIZE * 2];
                java.util.stream.IntStream.range(0, SNOW_COVER_SIZE).parallel().forEach(j -> {
                    for (int i = 0; i < SNOW_COVER_SIZE; i++) {
                        float x = (i + 0.5f) / SNOW_COVER_SIZE * TOTAL_REGION_WIDTH - TOTAL_REGION_WIDTH * 0.5f;
                        float z = (j + 0.5f) / SNOW_COVER_SIZE * TOTAL_REGION_WIDTH - TOTAL_REGION_WIDTH * 0.5f;
                        float[] climate = snowClimate(x, z);
                        cover[(j * SNOW_COVER_SIZE + i) * 2] = (byte) Math.round(climate[0] * 255f);
                        cover[(j * SNOW_COVER_SIZE + i) * 2 + 1] = (byte) Math.round(climate[1] * 255f);
                    }
                });
                smoothSnowClimate(cover);
                return cover;
            });
        }
        if (snowCoverTexture == 0) {
            if (!snowCoverJob.isDone()) return;
            byte[] cover;
            try {
                cover = snowCoverJob.get();
            } catch (Exception e) {
                return;
            }
            int[] id = new int[1];
            gl.glGenTextures(1, id, 0);
            snowCoverTexture = id[0];
            gl.glActiveTexture(GL3.GL_TEXTURE0 + SNOW_UNIT);
            gl.glBindTexture(GL3.GL_TEXTURE_2D, snowCoverTexture);
            gl.glPixelStorei(GL3.GL_UNPACK_ALIGNMENT, 1);
            gl.glTexImage2D(GL3.GL_TEXTURE_2D, 0, GL3.GL_RG8, SNOW_COVER_SIZE, SNOW_COVER_SIZE, 0, GL3.GL_RG, GL3.GL_UNSIGNED_BYTE,
                    java.nio.ByteBuffer.wrap(cover));
            gl.glPixelStorei(GL3.GL_UNPACK_ALIGNMENT, 4);
            gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR);
            gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
            // (round the world east and west; at the poles, the edge carries on)
            gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_REPEAT);
            gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
            gl.glActiveTexture(GL3.GL_TEXTURE0);
        }
        gl.glActiveTexture(GL3.GL_TEXTURE0 + SNOW_UNIT);
        gl.glBindTexture(GL3.GL_TEXTURE_2D, snowCoverTexture);
        gl.glActiveTexture(GL3.GL_TEXTURE0);
        // (every frame, so shaders made since pick it up too)
        // (today's temperature here against the usual, so fresh snow lies all round wherever it's snowing)
        Vector3 here = camera.getPosition();
        float usual = snowClimate(here.x, here.z)[0] * 100f - 60f - Math.max(0f, here.y - seaLevelHeight) * 70f * RegionalGenerationManager.ALTITUDE_CHILL;
        Shader.setSnowCover(gl, SNOW_UNIT, 1f / TOTAL_REGION_WIDTH, TOTAL_REGION_WIDTH * 0.5f, weather.freshSnow(), temperatureAt(here.x, here.z) - usual,
                new float[] { freezingPoint, SNOW_LIES_THICK, 70f * RegionalGenerationManager.ALTITUDE_CHILL, seaLevelHeight }, snowColour());
    }

    /**
     * The haze the distance fades into: the sky's colour greyed (as the air's haze greys
     * everything far off), and greyer still towards the clouds' colour the cloudier it is.
     */
    private float[] fogColour(Vector3 sky) {
        float grey = 0.3f * sky.x + 0.59f * sky.y + 0.11f * sky.z;
        float[] fog = { sky.x + (grey - sky.x) * FOG_GREYING, sky.y + (grey - sky.y) * FOG_GREYING, sky.z + (grey - sky.z) * FOG_GREYING };
        float[] cloud = weather.haze(sky, 1.0f - nightProportion);
        for (int k = 0; k < 3; k++) fog[k] += (cloud[k] - fog[k]) * cloud[3];
        return fog;
    }

    // How far the fog is greyed from the sky's own colour (0 not at all, 1 fully grey)
    private static final float FOG_GREYING = 0.45f;

    // The rainfall map's top (1) as rain over a year, in millimetres, for the map's key
    private static final float RAINFALL_FULL_MM = 3000f;

    // The most of each animal species expected in a chunk anywhere on the planet (see animalMapPeak)
    private final Map<Integer, Float> animalPeaks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The most of animal species i expected in any chunk, found by looking all over the land:
     * its map runs from none to this, so green on its key is somewhere on its map.
     */
    private float animalMapPeak(int i) {
        return mapPeak(i, (x, z) -> organismManager.expectedPerChunk(i, x, z));
    }

    /** The highest a value reaches anywhere on the land the map shows (kept under key, once worked out). */
    private float mapPeak(int key, java.util.function.BiFunction<Float, Float, Float> value) {
        return animalPeaks.computeIfAbsent(key, k -> {
            float best = 0f;
            int n = 300;
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    float x = (a + 0.5f) / n * TOTAL_REGION_WIDTH - TOTAL_REGION_WIDTH * 0.5f;
                    // (over the part of the planet the map shows, not the polar caps beyond it)
                    float z = ((b + 0.5f) / n * 2f - 1f) * Planet.clipHalfHeight();
                    if (TerrainMesh.getLayeredHeight(x, z, worldNoise) <= seaLevelHeight) continue;
                    best = Math.max(best, value.apply(x, z));
                }
            }
            return Math.max(1e-6f, best);
        });
    }

    // A square kilometre, in chunks; and the plant and animal maps' colours, none to the most
    private static final float CHUNKS_PER_KM2 = 100f * 100f;
    private static final float[][] RANGE_SPECTRUM = {
        { 0.85f, 0.00f, 0.00f }, { 1.00f, 0.50f, 0.00f }, { 1.00f, 0.90f, 0.00f }, { 0.00f, 0.70f, 0.10f }
    };

    /** A count for the map's key, rounded sensibly (thousands as k). */
    private static String countLabel(float n) {
        if (n >= 10000f) return Math.round(n / 1000f) + "k";
        if (n >= 1000f) return String.format("%.1fk", n / 1000f);
        if (n >= 10f) return String.valueOf(Math.round(n));
        return String.format("%.1f", n);
    }

    /** A point on the temperature map's scale (0 to 1) in Celsius, for the map's key (see temperatureAt). */
    private static String celsiusLabel(float warmth) {
        return Math.round(-30f + 70f * warmth) + " \u00B0C";
    }

    /** How much rain falls at a place, 0 driest to 1 wettest (see RegionalGenerationManager.rainfallMap). */
    private float rainfallAt(float x, float z) {
        int cx = (int) Math.floor(x / PHYSICAL_CHUNK_SIZE), cz = (int) Math.floor(z / PHYSICAL_CHUNK_SIZE);
        return regionalManager.rainfallMap.evaluate(cx, cz, x, z);
    }

    private float temperatureAt(float x, float z) {
        int cx = (int) Math.floor(x / PHYSICAL_CHUNK_SIZE), cz = (int) Math.floor(z / PHYSICAL_CHUNK_SIZE);
        float warmth = regionalManager.temperatureMap.evaluate(cx, cz, x, z);
        float celsius = -30f + 70f * warmth;
        double latitude = Planet.latitude(z);
        double tilt = Math.toRadians(Math.max(1.0, planetAxialTiltDegrees));
        celsius += 14f * (float) (Math.sin(sunDeclination) / Math.sin(tilt) * Math.sin(latitude));
        double hour = Planet.longitude(x) - noonLongitude;
        hour = Math.atan2(Math.sin(hour), Math.cos(hour));
        celsius += 7f * (float) Math.cos(hour - 0.6);
        return celsius;
    }

    /** While the settings are open the view behind them is darkened. */
    public void setMenuOpen(boolean open) {
        menuOpen = open;
    }

    private Model makeSkybox(GL3 gl, String fragmentPath, Texture skyTexture) {
        String name = "skybox";
        Mesh mesh = new Mesh(gl, InsideSphere.vertices.clone(), InsideSphere.indices.clone());
        Matrix4 modelMatrix = Matrix4Transform.scale(2400.0f, 2400.0f, 2400.0f);
        Shader shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", fragmentPath).flat();
        Material material = new Material(new Vector3(0f, 0f, 0f), new Vector3(0f, 0f, 0f));
        material.setDiffuseMap(skyTexture);
        
        Renderer renderer = new Renderer();
        return new Model(name, mesh, modelMatrix, shader, material, renderer, lights, camera);
    }

    /**
     * The board every sign is drawn on: as TwoTriangles (a unit square facing +Y, texture
     * coordinates the same), but cut into strips across so a poster can bend round a curved wall.
     */
    private static Mesh signBoardMesh(GL3 gl, int strips) {
        float[] vertices = new float[(strips + 1) * 2 * 8];
        int[] indices = new int[strips * 6];
        for (int i = 0; i <= strips; i++) {
            float u = i / (float) strips, x = u - 0.5f;
            float[][] pair = { { x, 0f, -0.5f, 0f, 1f, 0f, u, 1f }, { x, 0f, 0.5f, 0f, 1f, 0f, u, 0f } };
            for (int k = 0; k < 2; k++) System.arraycopy(pair[k], 0, vertices, (i * 2 + k) * 8, 8);
            if (i < strips) {
                int a = i * 2;
                int[] quad = { a, a + 1, a + 3, a, a + 3, a + 2 };
                System.arraycopy(quad, 0, indices, i * 6, 6);
            }
        }
        return new Mesh(gl, vertices, indices);
    }

    public FactorName getCurrentDebugFactor() {
      return currentDebugFactor == null ? null : currentDebugFactor.factor();
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
    /** A nation's writing direction: 0 left to right, 1 right to left, 2 top to bottom, 3 bottom to top. */
    public int getNationDirection(int nationId) {
        return nationDirections != null ? nationDirections.getOrDefault(nationId, 0) : 0;
    }

    public int getNationAtlasSize(int nationId) {
        if (nationAtlasSizes != null && nationAtlasSizes.containsKey(nationId)) {
            int count = nationAtlasSizes.get(nationId);
            return count > 0 ? count : 10; // Ensure we don't return 0 to prevent division by zero
        }
        return 10; // Fallback default if atlas size hasn't been mapped yet
    }
}