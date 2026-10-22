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
    private final GeodesicChunkGrid geodesicTerrainGrid = new GeodesicChunkGrid(TerrainMesh.DRAWN_LEVEL);
    private Model geodesicTerrainModel;
    private java.util.concurrent.Future<Object[]> geodesicTerrainBuild;
    private SpherePosition geodesicTerrainBuildCentre;
    private SpherePosition geodesicTerrainActiveCentre;
    private long geodesicTerrainRetryAfterNanos;
    // The sea: a grid round the viewer, wrapped onto the sea's sphere and given its waves in
    // vs_sphere_water; the land hides it where it is higher (see renderSea)
    private Mesh seaGrid;
    private Shader sphereWaterShader;
    // The rivers and lakes: their shader, and their meshes (made in the background, see RiverRenderer)
    private Shader riverShader;
    private final RiverRenderer riverRenderer = new RiverRenderer();
    private java.util.concurrent.Future<RiverRenderer.Built> riverBuild;
    private SphereSkyRenderer sphereSkyRenderer;
    private SphereWeather sphereWeather;
    private final GeodesicCollision geodesicCollision = new GeodesicCollision();
    /**
     * A tree or shrub on the sphere: where it stands, which model, and what's worked out once
     * for it (its model matrix, its leaves' colours there and its trunk's radius).
     */
    private record SphereFlora(SpherePosition position, int speciesIndex, int modelIndex, float scale,
                               float rotationY, Matrix4 model, Vector3 leafDark, Vector3 leafLight, float trunk) {}
    // Trees and grass, per geodesic cell (a little over two chunks' area each) round the player,
    // and every tree in them, for collision and the identification kit
    private static final int VEGETATION_LEVEL = 7;
    private CellStream<List<SphereFlora>> floraStream;
    private CellStream<float[]> grassStream;
    private final CellIndex<SphereFlora> trees = new CellIndex<>();
    private static final int GEODESIC_TERRAIN_SEGMENTS = TerrainMesh.DRAWN_SEGMENTS;
    
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
        // How much further its tolerance reaches into the heat than the cold (1 for trees; see OrganismSpecies.heatHardiness)
        float tempHotStretch = 1f;
        float patchNoiseScale;
        
        RegionalFactor tempFactor;
        RegionalFactor patchNoiseFactor;
        RegionalFactor abundanceFactor;

        float baseAbundance;

        // What it looks like (see Flora.Design), and its body's colours and markings (see fs_flora)
        Flora.Design design;
        Vector3 bodyColour, patternColour;
        float pattern, patternScale;

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

    private final List<SphereFlora> visibleFlora = new ArrayList<>();
    // Tallest trees reach about this far from their base, before instance scaling
    private static final float FLORA_CULL_RADIUS = 140.0f;
    private Model[][][] floraBranchModelsLOD; // Matrix bounds: [species][lodIndex][variationIndex]
    private Model[][][] floraLeafModelsLOD;   
    
    // Shader fields
    private Shader leafShader;
    
    private Light[] lights;
    private Vector3 ambientLight;
    private float nightProportion;
    private float timeOfDay;

    private float seaLevelHeight;
    private Material waterMaterial;
        // Bugs and, in time, other creatures; their ranges follow the land, not nation borders
        private OrganismManager organismManager;
    // The people who built the towns: generated anew each game, dressed by nation
    private Inhabitants inhabitants;
    // The player's own spacesuited body, and whether they are swimming
    private final PlayerBody playerBody = new PlayerBody();
    // The pod the player landed in, standing where each round starts
    // (in multiplayer with wider stairs, so both players start on them side by side: M11)
    private final LandingPod landingPod = new LandingPod(Multiplayer.isActive());
    private RockField rockField;
    private static final Object PLAYER = "player";
    private static final float PLAYER_RADIUS = 3.0f;   // must stay over 1.45 x NEAR_PLANE
    // Developer aid: -Dxenoguesser.menushot photographs the pod for the main menu's background
    private static final boolean MENU_SHOT = Dev.property("xenoguesser.menushot") != null;
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

    // Grass dies back from this many degrees above the water's freezing point, and is gone by
    // this many below it
    private static final float GRASS_FROST_START = 6f, GRASS_FROST_DEATH = 6f;
    // The temperature (Celsius) the planet's water freezes at, so below which it snows
    private float freezingPoint;

    private Shader terrainShader;
    // The plants' bodies: bark, skin, orbs, spikes (see fs_flora)
    private Shader floraShader;
    private Renderer terrainRenderer;
    private Material terrainMaterial;
    private Matrix4 globalModelMatrix;

    private Shader solidShader;
    private Shader glassShader;
    private Shader signboardShader;

    private Shader depthPrePassShader;

    private final float PHYSICAL_CHUNK_SIZE; 
    // (how far out the plants, roads and buildings are made: as the graphics chosen, taken up as
    // each round starts being built: see applyQuality)
    private int VIEW_DISTANCE = GameSettings.quality().viewDistance;

    private final float TOTAL_REGION_WIDTH;

    private PerlinNoise worldNoise;
    private long worldSeed; 

    private float playerEyeHeight = 20.0f;
    

    // (set from the window's thread once it opens, and waited for by the map's: see awaitMinimap)
    private volatile MapPanel minimap;
    private GameHUD gameHUD; 

    // --- Seasonal Simulation Fields ---
    private float planetAxialTiltDegrees;
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
    private int grassInstanceVBO = 0; 
    // How many grass blades are in the instance buffer (thinned with distance), as drawn
    private int drawnGrassInstances = 0;
    
    private int GRASS_VIEW_DISTANCE = GameSettings.quality().grassViewDistance;
    // (thinned for lesser graphics: the same everywhere, so where it's thicker still shows)
    private volatile float GRASS_DENSITY = GameSettings.quality().grassDensity;
    // The graphics the distances and grass above were last set from
    private GameSettings.Quality builtQuality = GameSettings.quality();
        private final int MAX_GRASS_LIMIT = 1200;
    private final float GRASS_BASE_ABUNDANCE;

    // --- Optimized Zero-Allocation VRAM Streaming Fields ---
    private java.nio.FloatBuffer persistentGrassBuffer;
    private int currentGrassGPUCapacityFloats = 0;

    // The HUD drawn over the 3D view, and what each picture last showed
    private final HudOverlay hud = new HudOverlay();
    private int hudScoreVersion = -1, hudFps = -1, hudItemsVersion = -1, hudStaminaStep = -1;
    private long hudClockTenth = -1L;
    private Achievements.Achievement hudToast;
    // Under this long left on the round's clock, the screen's edges pulse red (M45)
    private static final long LOW_CLOCK_MS = 5000L;
    // While the game's paused (M46) the view is greyed out altogether: nothing of it to study
    private static final float PAUSED_DIM = 1f;
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
    private GeodesicChunkGrid.Direction teleportTo;

    private GL3 gl;

    private NationGenerationManager nationManager;
    private int totalNationsCount;

    private InfrastructureManager infraManager;
    private SettlementManager settlementManager;
    // The roads and buildings round the player, per geodesic cell at InfrastructureManager.BATCH_LEVEL
    private Map<Long, List<InfrastructureObject>> infraCache;
    private final GeodesicChunkGrid infraGrid = new GeodesicChunkGrid(InfrastructureManager.BATCH_LEVEL);
    // The cells wanted round the player's cell (nearest first, those ahead before those behind),
    // worked out again only when the player changes cell, and where they were then
    private long infraWantedFor = Long.MIN_VALUE;
    private List<GeodesicChunkGrid.Cell> infraWanted = List.of();
    private SpherePosition infraWantedFrom;

    // Bird's-eye minimap layers, rendered on a background thread at startup
    private static final int BIRDS_EYE_MAP_RESOLUTION = 750;
    private volatile BirdsEyeMaps birdsEyeMaps;

    // Cities thin out trees and draw fewer distant details to pay for their extra buildings
    private static final float URBAN_FLORA_REDUCTION = 0.75f;
    private static final float SIGN_DRAW_DISTANCE = 1600.0f;
    // Window glass has faded out by here (see fs_glass)
    private static final float GLASS_DRAW_DISTANCE = 2200.0f;
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
    // (small, so the landing pod's frames keep coming while it's done behind them: at least one step a frame)
    private static final long LOADING_GRASS_BUDGET_NANOS = 8_000_000L;
    // Between rounds the new surroundings are built in slices of this long per frame
    private static final long ROUND_LOADING_BUDGET_NANOS = 45_000_000L;
    // ...and the world's first load, under the landing pod (see deferFirstFrames)
    private static final long POD_LOADING_BUDGET_NANOS = 10_000_000L;
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
      ROCKS("Rock frequency"),
      ROCK_SIZE("Rock size"),
      ANIMAL_POPULATION("Animal population"),
      FLORA("Flora"),
      GRASS_ABUNDANCE("Grass abundance"),
      GRASS_HEIGHT("Grass height"),
      GRASS_COLOUR("Grass colour"),
      WEALTH("Wealth"),
      NATIONS("Nations"),
      BOLLARDS("Bollards"),
      ROAD_LINES("Road lines"),
      UTILITY_POLES("Utility poles"),
      ARCHITECTURE("Architecture"),
      LANDMARKS("Landmarks");

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
        if (sharedNoise.rivers != null) {
            Rivers rivers = sharedNoise.rivers;
            riverBuild = java.util.concurrent.ForkJoinPool.commonPool().submit(() -> RiverRenderer.build(rivers, sharedNoise));
        }
        this.seaLevelHeight = sharedSeaLevel;
        this.worldSeed = sharedSeed; 
        this.PHYSICAL_CHUNK_SIZE = physicalChunkSize;
        this.TOTAL_REGION_WIDTH = totalRegionWidth;
        this.IS_DEBUG_MODE_ACTIVE = isDebugModeActive;
        // Developer aid: -Dxenoguesser.minimap=NATIONS,ROADS,CONTOURS starts with that gradient
        // map chosen and those layers ticked
        this.currentDebugFactor = null;
        String startingMap = Dev.property("xenoguesser.minimap");
        if (startingMap != null) {
            for (String part : startingMap.split(",")) {
                try {
                    FactorName factor = FactorName.valueOf(part.trim());
                    // (the animals' population is shown a species at a time: the first, to start with)
                    this.currentDebugFactor = new Overlay(factor, factor == FactorName.ANIMAL_POPULATION || factor == FactorName.FLORA ? 0 : -1);
                    ownedOverlays.add(currentDebugFactor);
                } catch (IllegalArgumentException notGradient) {
                    try {
                        startingLayers.add(MapPanel.Layer.valueOf(part.trim()));
                        ownedLayers.add(MapPanel.Layer.valueOf(part.trim()));
                    } catch (IllegalArgumentException notLayer) {
                        System.err.println("Unknown map: " + part);
                    }
                }
            }
        }
        
        java.util.Random seedRand = new java.util.Random(worldSeed);
        this.planetAxialTiltDegrees = 20.0f + seedRand.nextFloat() * 6.0f;
        
        // (worked out with the rivers, if it was: see XenoGuesser)
        if (this.worldNoise.climate != null) {
            this.regionalManager = this.worldNoise.climate;
        } else {
            this.regionalManager = new RegionalGenerationManager(worldSeed, seaLevelHeight);
            this.regionalManager.precalculateWaterDistanceField(this.worldNoise);
            if (this.worldNoise.rivers != null) this.regionalManager.addRivers(this.worldNoise.rivers);
        }
        this.freezingPoint = -5f + 10f * new java.util.Random(worldSeed * 41L + 19L).nextFloat();

        float GRASS_TEMP_MEAN = 0.4f;
        float GRASS_TEMP_STD_DEV = 0.5f;
        // (world units from the sea)
        float GRASS_WATER_MEAN = 0f;
        float GRASS_WATER_STD_DEV = 8000.0f;
        float GRASS_DENSITY_SCALE = 2e-5f;
        float GRASS_VARIATION_SCALE = 1e-5f;
                this.GRASS_BASE_ABUNDANCE = 1050f;

        // Grass likes it mild, and dies back where it's cold enough for snow: from a few degrees
        // above the water's freezing point down to several below, it thins out to almost nothing
        // (and what's left is dry and dead, see grassColourFactor)
        RegionalFactor mildness = this.regionalManager.createTemperaturePreference(GRASS_TEMP_MEAN, GRASS_TEMP_STD_DEV);
        this.grassTemperateFactor = new RegionalFactor(1.0f, d -> {
            float celsius = -30f + 70f * regionalManager.temperatureMap.evaluate(d);
            float t = Math.max(0f, Math.min(1f, (celsius - (freezingPoint - GRASS_FROST_DEATH)) / (GRASS_FROST_DEATH + GRASS_FROST_START)));
            return mildness.evaluate(d) * t * t * (3f - 2f * t);
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
        // Each species' form, from a stream of its own (so the rest of each species is as it was):
        // the trees all different forms, and the shrubs
        Flora.Design.Form[][] forms = plantForms(new Random(worldSeed * 0x9E3779B97F4A7C15L + 77L));

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
                baseBRate   = 0.05f + speciesSeeder.nextFloat() * 0.08f; // Plenty of middling branches
                varBRate    = 0.01f + speciesSeeder.nextFloat() * 0.02f;
                baseSWidth  = 2.0f + speciesSeeder.nextFloat() * 2.5f;
                varSWidth   = 0.3f + speciesSeeder.nextFloat() * 0.4f;
                baseWDecl   = 0.01f + speciesSeeder.nextFloat() * 0.04f;
                varWDecl    = 0.005f + speciesSeeder.nextFloat() * 0.01f;
                baseSDist   = 80.0f + speciesSeeder.nextFloat() * 90.0f; // Tall
                varSDist    = 10.0f + speciesSeeder.nextFloat() * 15.0f;
                baseBAngle  = 30.0f + speciesSeeder.nextFloat() * 40.0f; // Splay: from fairly upright (30) to nearly level (70), by species
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
                baseBAngle  = 30.0f + speciesSeeder.nextFloat() * 30.0f; // Wide bushy dispersion
                varBAngle   = 10.0f + speciesSeeder.nextFloat() * 20.0f;
                
                // Ecological layout: Prefer warmer/different niches than trees
                tempMean    = 0.4f + speciesSeeder.nextFloat() * 0.5f;
                tempStdDev  = 0.15f + speciesSeeder.nextFloat() * 0.2f;
                baseAbundance = (0.04f + speciesSeeder.nextFloat() * 0.16f) * 2.5f; // (common: 2.5 times what they were)
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

            // What it looks like: its form and what grows on it (the branching forms keep the
            // branching above; every other is as wide at its foot as its design's stem)
            Random looks = new Random(worldSeed * 7919L + s * 104729L);
            Flora.Design design = Flora.Design.of(forms[isTree ? 0 : 1][isTree ? s : s - 4], !isTree, looks);
            design.leafScale = leafScale;
            if (design.form != Flora.Design.Form.TREE && design.form != Flora.Design.Form.WEEPING) {
                baseSWidth = design.width;
                varSWidth = 0f;
            }

            // Construct the runtime configuration profile
            speciesConfigs[s] = new SpeciesConfig(
                name, leafTexNum, leafScale,
                baseBRate, varBRate, baseSWidth, varSWidth,
                baseWDecl, varWDecl, baseSDist, varSDist,
                baseBAngle, varBAngle, healthyColor, dyingColor, trunkColor,
                tempMean, tempStdDev, patchNoiseScale, baseAbundance
            );
            speciesConfigs[s].design = design;
            plantMarkings(speciesConfigs[s], looks);
            if (Dev.ENABLED) System.out.println("[FLORA] Plant " + s + ": " + design.kind() + (design.leaves ? ", leaves" : "")
                    + (design.spikes > 0f ? ", spikes" : "") + (design.orbs > 0f ? ", orbs" : "") + (design.strands > 0 ? ", strands" : "")
                    + (design.skin ? ", skin" : "") + ", pattern " + (int) speciesConfigs[s].pattern);

            // Compile Ecosystem Pipeline Rules for this dynamic configuration
            SpeciesConfig sc = speciesConfigs[s];
            // Some shrubs cope with the heat better than the cold (from a Random of their own, so the rest is as it was)
            if (!isTree) sc.tempHotStretch = OrganismSpecies.heatHardiness(new Random(worldSeed * 3301L + s));
            sc.tempFactor = this.regionalManager.createTemperaturePreference(sc.tempMean, sc.tempStdDev, sc.tempHotStretch);
            sc.patchNoiseFactor = this.regionalManager.createNoiseMap(sc.patchNoiseScale);
            // ...and only in its home region, as the animals (see OrganismSpecies): a plant suited
            // to the cold north needn't grow in the cold south too
            RegionalFactor home = this.regionalManager.createNoiseMap(OrganismSpecies.HOME_SCALE_LOW
                    + new Random(worldSeed * 613L + s).nextFloat() * OrganismSpecies.HOME_SCALE_RANGE);
            sc.abundanceFactor = plantAbundance(sc, home, OrganismSpecies.homeStrength(new Random(worldSeed * 1993L + s)));
        }

        // Each plant's range (see SpeciesRange): spawning and its heatmap both follow it
        landPoints = SpeciesRange.land(worldNoise, seaLevelHeight);
        floraRanges = floraRanges(landPoints);

        // Dynamically determine the total number of writing systems/nations between 6 and 14 configurations
        Random rand = new Random(this.worldSeed);
        this.totalNationsCount = 6 + rand.nextInt(9); 

        // Map resolution matched safely to 512x512 grids to match MapPanel specs
        // Map resolution matched safely to 384 grids for fast CA simulation
        this.nationManager = new NationGenerationManager(this.worldSeed, this.totalNationsCount, seaLevelHeight, worldNoise);

        // Run the spreading calculation using your environment configuration data parameters
        // Assuming 'terrainAbundanceFactor' or standard height maps are accessible inside your initialization path
        this.nationManager.generateTerritories(this.worldSeed);

        // Settlements decide where cities grow; the road network and buildings follow from them
        this.settlementManager = new SettlementManager(worldSeed,
                                seaLevelHeight, worldNoise, regionalManager, nationManager);
        this.infraManager = new InfrastructureManager(this.worldSeed, this.totalNationsCount, this.nationManager,
                this.settlementManager, this);
        this.inhabitants = new Inhabitants(worldSeed, totalNationsCount, infraManager, worldNoise);
        System.out.println("[INHABITANTS] " + inhabitants.describe());
                this.infraManager.prepareRoadNetwork(seaLevelHeight, worldNoise);
        // (low frequencies: the soil's colour drifts over many kilometres)
        this.soilVariantAFactor = this.regionalManager.createNoiseMap(0.7e-5f);
        this.soilVariantBFactor = this.regionalManager.createNoiseMap(1.05e-5f);
        this.organismManager = new OrganismManager(worldSeed, PHYSICAL_CHUNK_SIZE, seaLevelHeight, worldNoise, regionalManager);
        // The groves among the landmarks, found before any plant is placed (they thicken them)
        infraManager.landmarks().findGroves(worldSeed, landPoints, p -> TerrainMesh.getLayeredHeight(p, worldNoise), seaLevelHeight,
                nationManager::getNationAt, new Landmarks.Plants() {
                    public int count() {
                        return NUM_SPECIES;
                    }

                    public float raw(int species, GeodesicChunkGrid.Direction place) {
                        return floraRaw(species, place);
                    }

                    public float share(int species, float raw) {
                        return floraRanges[species].share(raw);
                    }

                    public String name(int species) {
                        return floraName(species);
                    }

                    public Vector3 leaves(int species) {
                        return speciesConfigs[species].healthyColor;
                    }

                    public Vector3 trunk(int species) {
                        return speciesConfigs[species].trunkColor;
                    }
                });
        inhabitants.setCollision(geodesicCollision);
        // What's solid on the sphere: the pod, trees, rocks, and the buildings, fences, rails and
        // sign posts
        geodesicCollision.addSource(landingPod::obstaclesNear);
        geodesicCollision.addSource((centre, reach, sink) ->
                trees.forEachNear(centre, reach, tree -> sink.circle(tree.position(), tree.trunk())));
        geodesicCollision.addSource((centre, reach, sink) -> { if (rockField != null) rockField.obstaclesNear(centre, reach, sink); });
        geodesicCollision.addSource((centre, reach, sink) -> { if (infraManager != null) infraManager.obstaclesNear(centre, reach, sink); });
        organismManager.setCollision(geodesicCollision);
        organismManager.setWild(p -> settlementManager.getUrbanness(p) < 0.12f);
        organismManager.setRoads((p, clearance) -> infraManager.isRoadLocation(p, clearance));
        double floraReach = (VIEW_DISTANCE + PREFETCH_RINGS) * PHYSICAL_CHUNK_SIZE;
        floraStream = new CellStream<List<SphereFlora>>(VEGETATION_LEVEL, floraReach, PHYSICAL_CHUNK_SIZE * 1.5, this::buildFloraCell)
                .onChange(added -> { for (SphereFlora tree : added) trees.add(tree.position(), tree.trunk(), tree); },
                        removed -> { for (SphereFlora tree : removed) trees.remove(tree.position(), tree); });
        grassStream = new CellStream<>(VEGETATION_LEVEL, GRASS_VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE, PHYSICAL_CHUNK_SIZE * 1.5, this::buildGrassCell);
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
            // (nothing's handed over, nor the overlays made, until there's a map to take them: it's
            // only made once the window opens, and anything handed over before then was lost)
            awaitMinimap();
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) {
                    minimap.setBaseMapDetail(baseMap[1]);
                }
            });

            

            // The nations' names, in their own writing, where their largest land is, with their flags
            List<Object[]> labels = new ArrayList<>();
            Map<Integer, BufferedImage> flags = new HashMap<>();
            for (float[] place : maps.nationLabelPlaces(nationManager)) {
                int nation = (int) place[0];
                BufferedImage name = nationNameImage(nation);
                BufferedImage flag = flags.computeIfAbsent(nation, infraManager::flagImage);
                if (name != null) labels.add(new Object[] { name, place[1], place[2], place[3], place[4] > 0.5f, flag });
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) minimap.setNationLabels(labels);
            });
            // The picture maps' pictures: each nation's bollard, road markings or utility pole where
            // its main land's name goes
            java.util.Map<FactorName, List<Object[]>> pictures = new java.util.EnumMap<>(FactorName.class);
            for (FactorName f : PICTURE_MAPS) pictures.put(f, new ArrayList<>());
            // (a nation building more than one bollard or pole: its main one where its name goes,
            // each other one part way towards the neighbour whose it is, on the side it's built)
            java.util.Map<Integer, float[]> middles = new HashMap<>();
            for (float[] place : maps.nationLabelPlaces(nationManager)) if (place[4] > 0.5f) middles.put((int) place[0], place);
            List<Object[]> architectureMain = new ArrayList<>(), architectureOthers = new ArrayList<>();
            for (float[] place : middles.values()) {
                int nation = (int) place[0];
                for (Object[] design : infraManager.bollardDesigns(nation)) {
                    float[] at = towardsNeighbour(place, middles.get((Integer) design[1]));
                    pictures.get(FactorName.BOLLARDS).add(new Object[] { ((BollardStyle) design[0]).picture(MAP_PICTURE_HEIGHT), at[0], at[1] });
                }
                RoadLineStyle lines = infraManager.roadLineStyle(nation);
                if (lines != null) pictures.get(FactorName.ROAD_LINES).add(new Object[] { lines.picture(MAP_PICTURE_HEIGHT, RoadPath.RoadClass.STREET.width), place[1], place[2] });
                for (Object[] design : infraManager.poleDesigns(nation)) {
                    float[] at = towardsNeighbour(place, middles.get((Integer) design[1]));
                    pictures.get(FactorName.UTILITY_POLES).add(new Object[] { ((UtilityPoleStyle) design[0]).picture(MAP_PICTURE_HEIGHT), at[0], at[1] });
                }
                // (and a nation's province, with its own take on the nation's architecture, in the
                // middle of the province: gathered first, then thinned out, see spaceOut)
                for (Object[] design : infraManager.architecturePictures(nation, MAP_PICTURE_HEIGHT)) {
                    float[] at;
                    if ((Integer) design[1] < 0) {
                        double[] spot = (double[]) design[2];
                        double[] map = MapProjection.toMap(new GeodesicChunkGrid.Direction(spot[0], spot[1], spot[2]));
                        at = new float[] { (float) map[0], (float) map[1] };
                    } else {
                        at = towardsNeighbour(place, middles.get((Integer) design[1]));
                    }
                    (((Integer) design[1]) == 0 ? architectureMain : architectureOthers).add(new Object[] { design[0], at[0], at[1] });
                }
            }
            pictures.get(FactorName.ARCHITECTURE).addAll(spaceOut(architectureMain, architectureOthers));
            mapPictures = pictures;
            // (a picture map already chosen gets its pictures now)
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null && currentPictureMap != null) choosePictureMap(currentPictureMap);
            });
            // Drawn in tiles when zoomed in close
            java.util.Map<MapPanel.Layer, MapTiles.Renderer> layerTiles = new java.util.EnumMap<>(java.util.Map.of(
                    MapPanel.Layer.CONTOURS, maps.contourTiles(), MapPanel.Layer.ROADS, maps.roadTiles(infraManager),
                    MapPanel.Layer.BUILDINGS, maps.buildingTiles(infraManager), MapPanel.Layer.SHOPS, maps.shopTiles(infraManager)));
            if (worldNoise.rivers != null) layerTiles.put(MapPanel.Layer.RIVERS, maps.riverTiles(worldNoise.rivers));
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) minimap.setTileRenderers(maps.baseTiles(), layerTiles);
            });
            showLayer(MapPanel.Layer.CONTOURS, maps.renderContourLayer());
            if (worldNoise.rivers != null) showLayer(MapPanel.Layer.RIVERS, maps.renderRiverLayer(worldNoise.rivers));
            showLayer(MapPanel.Layer.ROADS, maps.renderRoadLayer(infraManager));
            showLayer(MapPanel.Layer.BUILDINGS, maps.renderBuildingLayer(infraManager));
            showLayer(MapPanel.Layer.SHOPS, maps.renderShopLayer(infraManager));

            // Then every overlay, side by side, so choosing one later is instant (the round doesn't
            // start until they're done: see advanceLoading)
            allOverlays().parallelStream().forEach(this::overlayImage);
            mapsReady = true;
            System.out.printf("[MINIMAP] Bird's-eye maps ready in %d ms%n", System.currentTimeMillis() - startTime);

            // The landmarks: the natural landforms found now the land's known, then each one's
            // picture, stood where it is (after the rest, as the round needn't wait for them)
            Landmarks landmarks = infraManager.landmarks();
            landmarks.findLandforms(worldSeed, landPoints, p -> TerrainMesh.getLayeredHeight(p, worldNoise), seaLevelHeight, nationManager::getNationAt);
            WorldPalette palette = worldArt.palette();
            java.util.function.Function<float[], Vector3> v = c -> new Vector3(c[0], c[1], c[2]);
            landmarks.setLook(new Landmark.Look(v.apply(palette.skyZenith), v.apply(palette.skyHorizon), v.apply(palette.grassLush),
                    v.apply(palette.grassDry), v.apply(palette.soilBase), v.apply(palette.seaLight)));
            List<Object[]> landmarkPictures = new ArrayList<>();
            for (Landmark landmark : landmarks.all()) {
                BufferedImage picture = landmark.render(LANDMARK_PICTURE_HEIGHT);
                landmark.picture = picture;
                double[] at = MapProjection.toMap(landmark.place());
                landmarkPictures.add(new Object[] { picture, (float) at[0], (float) at[1], landmark.name, landmark.caption, landmark.kind.label });
            }
            java.util.Map<FactorName, List<Object[]>> withLandmarks = new java.util.EnumMap<>(mapPictures);
            withLandmarks.put(FactorName.LANDMARKS, landmarkPictures);
            mapPictures = withLandmarks;
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null && currentPictureMap == FactorName.LANDMARKS) choosePictureMap(currentPictureMap);
            });
        }, "birds-eye-map-renderer");
        mapThread.setDaemon(true);
        mapThread.start();
    }

    /** Waits (on the map's own thread) until the map panel's been given to the game (see setMinimap). */
    private void awaitMinimap() {
        while (minimap == null) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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

    // The set-up's steps (see coreSteps and finishSteps), once begun
    private List<Runnable> coreSteps, finishSteps;

    private boolean advanceLoading() {
        if (loadingStep == 0) {
            if (coreSteps == null) {
                loading.begin(LoadingProgress.Stage.GPU_UPLOAD);
                coreSteps = coreSteps();
            }
            if (!runSteps(coreSteps)) return false;
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
            if (finishSteps == null) finishSteps = finishSteps();
            if (!runSteps(finishSteps)) return false;
            loadingStep++;
            return false;
        }
        // The trees and grass round the landing site are worked out on the builder threads while
        // the loading screen keeps moving
        if (!surroundingsBuilt()) {
            updateVisibleChunks(false);
            loading.report(Math.min(grassStream.progress(), floraStream.progress()));
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
        // (made on the builder threads, put up as each is ready, so no one frame takes them all)
        if (rockField != null) {
            rockField.update(gl, camera.getSpherePosition(), false);
            if (!rockField.allBuilt()) return false;
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
        
        resize(gl, width, height);
    }

    /**
     * The world is drawn at most at as many pixels as the graphics chosen allow (full HD on
     * High), then scaled up to fill a larger screen; the HUD over it is drawn at the screen's own
     * size, so it stays sharp.
     */
    private void resize(GL3 gl, int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
        renderPixels = GameSettings.quality().renderPixels;
        float shrink = (float) Math.min(1.0, Math.sqrt(renderPixels / ((double) width * height)));
        this.currentWidth = Math.max(1, Math.round(width * shrink));
        this.currentHeight = Math.max(1, Math.round(height * shrink));
        gl.glViewport(0, 0, width, height);
        
                applyProjection();

        createDepthFramebuffer(gl, currentWidth, currentHeight);
        createSceneFramebuffer(gl);
    }

    // The most pixels the world is drawn at (changed in the settings while playing, it's drawn
    // at the new size from the next frame), and the size of the screen it's shown on
    private double renderPixels = GameSettings.quality().renderPixels;
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
            if (frameShot != null) {
                if (sceneFbo != 0) gl.glBindFramebuffer(GL3.GL_READ_FRAMEBUFFER, sceneFbo);
                else gl.glReadBuffer(GL.GL_BACK);
                writeFrameShot(gl, sceneFbo != 0 ? currentWidth : screenWidth, sceneFbo != 0 ? currentHeight : screenHeight);
            }
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
        camera.setPerspectiveMatrix(Matrix4Transform.perspective((float) Math.toDegrees(fieldOfView()), aspect, currentNearPlane, FAR_PLANE));
        // (for what's drawn by casting rays through the screen, the sky and clouds, so they zoom too)
        camera.setTanHalfFov((float) Math.tan(fieldOfView() / 2.0));
    }

    // Zooming in (GameMode.NO_MOVE only): how far in the view is (1 as usual, up to MOST_ZOOM), and
    // how far it's going, each notch of the mouse wheel (or a pinch) going ZOOM_PER_NOTCH times
    // further, eased there at ZOOM_EASE of the way a second
    private static final float MOST_ZOOM = 4f, ZOOM_PER_NOTCH = 1.15f, ZOOM_EASE = 12f;
    private static final double USUAL_FIELD_OF_VIEW = Math.toRadians(45.0);
    private volatile float zoomWanted = 1f;
    private float zoom = 1f;

    /** The view's height as an angle: the usual, narrowed by the zoom (so zooming in keeps every pixel the screen has). */
    private double fieldOfView() {
        return 2.0 * Math.atan(Math.tan(USUAL_FIELD_OF_VIEW / 2.0) / zoom);
    }

    /** Zooms in (notches of the wheel or pinch, negative) or out, towards the middle of the screen, where the game mode allows it; safe from any thread. */
    public void zoomBy(double notches) {
        if (GameMode.current().moving) return;
        zoomWanted = (float) Math.max(1.0, Math.min(MOST_ZOOM, zoomWanted * Math.pow(ZOOM_PER_NOTCH, -notches)));
    }

    /** Eases the zoom towards where it's wanted, the projection (and the grass's thinning) following. */
    private void easeZoom(double deltaTime) {
        if (GameMode.current().moving) zoomWanted = 1f;
        float wanted = zoomWanted;
        if (Math.abs(wanted - zoom) < 1e-4f) return;
        float step = (float) Math.min(1.0, ZOOM_EASE * deltaTime);
        zoom = Math.abs(wanted - zoom) < 0.002f ? wanted : zoom + (wanted - zoom) * step;
        applyProjection();
        grassDirty = true;
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
            rainSound.start();
            Thread scale = new Thread(() -> playerBody.setAltimeterTop(roundUp(highestGround())), "altimeter-scale");
            // April's message: the planet's ocean cover worked out and its sentence said, well before it's needed
            Thread ocean = new Thread(() -> {
                double percent = oceanPercent();
                aprilPercent = percent;
                String phonemes = TransmissionMessage.sentenceWithPercent(TransmissionMessage.APRIL, percent);
                aprilSpeech.complete(phonemes == null ? null : PlanetVoice.say(phonemes));
            }, "ocean-cover");
            ocean.setDaemon(true);
            ocean.setPriority(Thread.MIN_PRIORITY);
            ocean.start();
            scale.setDaemon(true);
            scale.start();
        }
        // (in the landing pod: ready now, the first frames drawn once the hatch is opened)
        if (firstFramesDeferred && !loading.isFinished()) {
            loading.finish();
            if (onWorldReady != null) javax.swing.SwingUtilities.invokeLater(onWorldReady);
            return;
        }

        // The weather's heard only while the world's in view: checked first, before a frame given
        // to building the next round (behind the results) returns early
        rainSound.setAudible(!worldHidden && !menuOpen && minimap != null && !minimap.isFullScreenRevealMode()
                && !roundLoading && !teleportLoading);

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
        // (in multiplayer only once the partner's PC has built it too, so both start together: M16)
        if (nextRoundBuilt && nextRoundWanted && partnerHasBuilt()) {
            nextRoundBuilt = false;
            nextRoundWanted = false;
            System.out.printf("[ROUND] Next round (%d) started %d ms after it was asked for, at %d%n", spawnedRound,
                    System.currentTimeMillis() - nextRoundWantedAt, System.currentTimeMillis());
            startRoundClock();
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) minimap.finishRoundLoading();
            });
        }
        if (IS_DEBUG_MODE_ACTIVE && isToTeleport && !roundLoading) {
            isToTeleport = false;
            moveToLocation(new Viewpoint(SphereMath.atAltitude(teleportTo, 0.0), -Math.PI * 0.5));
            teleportLoading = true;
            teleportStarted = System.currentTimeMillis();
            setBuilderThreads(true);
        }
        if (teleportLoading) {
            updateVisibleChunks(false);
            // (given up on after a while, should something never finish)
            if (surroundingsLoadedHere() || System.currentTimeMillis() - teleportStarted > 30_000) {
                teleportLoading = false;
                setBuilderThreads(false);
                System.out.printf("[TELEPORT] Loaded round %.3f, %.3f in %d ms%n", Math.toDegrees(Math.asin(teleportTo.y())),
                        Math.toDegrees(Math.atan2(-teleportTo.z(), teleportTo.x())), System.currentTimeMillis() - teleportStarted);
            }
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            return;
        }
        if (roundLoading) {
            updateVisibleChunks(false);
            roundLoadingFrames++;
            if (surroundingsBuilt()) {
                roundLoading = false;
                nextRoundBuilt = true;
                // (the partner's PC waits for this before starting the round)
                Multiplayer session = Multiplayer.active();
                if (session != null) session.sendRoundBuilt(spawnedRound);
                setBuilderThreads(false);
                queueNextRound();
                System.out.printf("[ROUND] Next round built in %d ms over %d frames%n",
                        System.currentTimeMillis() - roundLoadingStarted, roundLoadingFrames);
            }
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            return;
        }

        // The world is only ever drawn on the sphere: with no ground built round the player yet
        // there is nothing to show (normally the loading above has waited for it)
        if (geodesicTerrainModel == null) {
            updateGeodesicTerrain(gl);
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            return;
        }

        pumpPrefetch();

        if (minimap != null && minimap.isFullScreenRevealMode()) {
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            return;
        }

        if (renderPixels != GameSettings.quality().renderPixels) resize(gl, screenWidth, screenHeight);
        // (the sun's shadow maps made again at the size the graphics now chosen ask for)
        if (shadowMapsMade && shadowMaps.size() != GameSettings.quality().shadowMapSize) {
            shadowMaps.dispose(gl);
            shadowMaps = new ShadowMaps();
            shadowMaps.initialise(gl);
        }
        // While the game's paused under the settings the view's greyed out altogether (PAUSED_DIM):
        // there's nothing of the world to be seen, so none's drawn, keeping a slow PC's settings quick
        if (menuOpen && GameClock.isPaused() && PAUSED_DIM >= 1f && loading.isFinished()) {
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            if (!hideFirstPerson) drawHud(drawable);
            return;
        }

        if (MENU_SHOT && loading.isFinished()) menuShot(gl);
        if (SIGN_SHOT && loading.isFinished()) signShot(gl);
        // Developer aid: -Dxenoguesser.vehicleshot (=moving for one on the move, =driveway for one up a
        // driveway, =door for someone coming out of their front door) stands by it two seconds in
        if (VEHICLE_SHOT != null && loading.isFinished()) vehicleShotFrames++;
        if (VEHICLE_SHOT != null && vehicleShotFrames == VEHICLE_SHOT_FRAME + 20) saveFrame(gl, "vehicle_shot.png");
        if (VEHICLE_SHOT != null && loading.isFinished() && vehicleShotFrames == VEHICLE_SHOT_FRAME) {
            SpherePosition at = camera.getSpherePosition();
            double eyes = SphereMath.altitude(at);
            Viewpoint stand = "door".equals(VEHICLE_SHOT) ? inhabitants.doorViewpoint(at)
                    : inhabitants.vehicleViewpoint(at, VEHICLE_SHOT);
            if (stand != null) {
                camera.setSphereView(SphereMath.atAltitude(stand.place(), eyes), (float) Math.toDegrees(stand.heading()), 0f);
                camera.lookAt(SphereMath.atAltitude(SphereMath.move(stand.place(), stand.heading(), 50.0), eyes - 8.0));
            }
        }
        renderScene(gl);
        // (light-headed, once the oxygen's downgraded: M52; the HUD over it stays clear)
        if (!hideFirstPerson) oxygenBlur.apply(gl, screenWidth, screenHeight, oxygenStrength, oxygenTime);
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
        } else if (FRAME_SEQUENCE > 0 && loading.isFinished() && framesRenderedSinceReady > 150 && framesRenderedSinceReady <= 150 + FRAME_SEQUENCE) {
            saveFrame(gl, "sequence_" + (framesRenderedSinceReady - 150) + ".png");
        }
    }

    /**
     * Developer aid for the main menu's background: a minute after the round starts, the
     * view moves out to look back at the landing pod, and a frame without the HUD or the
     * player's body is saved as the menu art.
     */
    private static final boolean SIGN_SHOT = Dev.property("xenoguesser.signshot") != null;
    private static final String VEHICLE_SHOT = Dev.property("xenoguesser.vehicleshot");
    // (after this many frames: -Dxenoguesser.vehicleshotframe)
    private static final int VEHICLE_SHOT_FRAME = Dev.integer("xenoguesser.vehicleshotframe", 120);
    private int vehicleShotFrames;
    private final java.util.Set<InfrastructureObject> signsShot = new java.util.HashSet<>();
    private SpherePosition signShotEye, signShotTarget;

    /**
     * Developer aid: from a little after the round starts, stands in front of one nearby sign
     * after another (those with pictures or titles first) and saves a frame of each.
     */
    private void signShot(GL3 gl) {
        int frame = framesRenderedSinceReady - 40;
        if (frame < 0 || frame > 40 * 8) return;
        if (signShotEye != null) lookFrom(signShotEye, signShotTarget);
        if (frame % 40 == 0) {
            hideFirstPerson = true;
            InfrastructureObject best = null;
            double bestScore = Double.MAX_VALUE;
            SpherePosition at = camera.getSpherePosition();
            for (List<InfrastructureObject> objects : infraCache.values()) {
                for (InfrastructureObject obj : objects) {
                    if (obj.type != InfrastructureObject.Type.SIGN || signsShot.contains(obj)) continue;
                    // -Dxenoguesser.signshot=shaped: only posters on leaning or round walls
                    if ("shaped".equals(Dev.property("xenoguesser.signshot")) && !obj.shaped) continue;
                    double score = SphereMath.distance(obj.frame.origin(), at);
                    if (obj.pictureCount == 0 && !obj.wallMounted) score += 1e9;
                    if (score < bestScore) { bestScore = score; best = obj; }
                }
            }
            if (best == null) return;
            signsShot.add(best);
            // (in the sign's own frame, whose local +z its face looks along)
            float side = Float.parseFloat(Dev.property("xenoguesser.signside", "1"));
            float back = best.wallMounted ? Math.min(30f, Math.max(best.boardWidth * 0.75f, best.boardHeight * 1.6f) + 6f) : 40f;
            float lift = best.wallMounted ? 0f : 32f;
            // -Dxenoguesser.signslant=0.8 views it from off to one side, by that much of the distance
            float slant = Float.parseFloat(Dev.property("xenoguesser.signslant", "0"));
            signShotEye = best.frame.toWorld(-back * slant * side, lift, back * side);
            signShotTarget = best.frame.toWorld(0.0, lift, 0.0);
            lookFrom(signShotEye, signShotTarget);
            System.out.println("[SIGNSHOT] sign " + signsShot.size() + ": pictures " + best.pictureCount + (best.wallMounted ? (best.banner ? " banner" : " poster") : "") + ", title " + best.titleLength
                    + ", direction " + nationDirections.getOrDefault(best.nationId, 0) + ", nation " + best.nationId
                    + ", patriotism " + infraManager.patriotismOf(best.nationId));
        } else if (frame % 40 == 30) {
            saveFrame(gl, "sign_" + signsShot.size() + ".png");
        }
    }

    /** Developer aid: stands the camera at a place looking at another. */
    private void lookFrom(SpherePosition eye, SpherePosition target) {
        camera.setSphereView(eye, 0f, 0f);
        camera.lookAt(target);
    }

    // The main menu's picture: of this many landing sites, the one whose view (as menuShot frames
    // it) has the most of water, mountains behind and houses about, each counted up to its own
    // fill: water over this share of the ground seen, the hills this steep (degrees up), this many
    // houses near the pod
    private static final int PICTURESQUE_SITES = 1500;
    private static final double VIEW_WATER = 0.3, VIEW_MOUNTAINS = 5.0, VIEW_HOUSES = 12.0;

    /** Developer aid: the landing site with the most picturesque view for the main menu's picture (see menuShot). */
    private Viewpoint picturesqueSite(java.util.Random rand) {
        Viewpoint best = null;
        double bestScore = -1;
        for (int i = 0; i < PICTURESQUE_SITES; i++) {
            Viewpoint site = landingSite(rand);
            if (site == null) continue;
            LandingPod pod = new LandingPod();
            pod.place(site.place(), site.heading(), p -> TerrainMesh.getLayeredHeight(p, worldNoise));
            SpherePosition[] view = menuView(pod);
            // (on fairly level ground, so the view looks out over the land, not up a hillside)
            double flatness = 0;
            for (int k = 0; k < 8; k++) {
                SpherePosition p = SphereMath.move(site.place(), k * Math.PI / 4, 250);
                flatness = Math.max(flatness, Math.abs(TerrainMesh.getLayeredHeight(p, worldNoise) - pod.ground()));
            }
            if (flatness > 25) continue;
            double eyeAltitude = SphereMath.altitude(view[0]);
            double look = SphereMath.heading(view[0], view[1]);
            // Water, mountains and houses across the view, near to far, counting only what the
            // land nearer the camera doesn't hide
            int seen = 0, wet = 0, houses = 0;
            double steepest = -90;
            SphereFrame frame = pod.frame();
            for (double turn = -0.6; turn <= 0.601; turn += 0.15) {
                double horizon = -90, nearHorizon = -90;
                for (double out = 60; out <= 9000; out += out < 1500 ? 30 : out < 3000 ? 100 : 400) {
                    SpherePosition p = SphereMath.move(view[0], look + turn, out);
                    float ground = TerrainMesh.getLayeredHeight(p, worldNoise);
                    boolean water = ground <= seaLevelHeight || underFreshWater(p, ground);
                    double top = Math.max(ground, seaLevelHeight);
                    double angle = Math.toDegrees(Math.atan2(top - eyeAltitude, out));
                    boolean visible = angle >= horizon - 0.05;
                    if (out <= 3000) {
                        seen++;
                        if (water && visible) wet++;
                    }
                    if (out <= 1200 && infraManager.isBuildingLocation(p, 0f)
                            && Math.toDegrees(Math.atan2(ground + 60 - eyeAltitude, out)) > horizon) houses++;
                    if (out < 1500) nearHorizon = Math.max(nearHorizon, angle);
                    else if (angle > nearHorizon + 1.0) steepest = Math.max(steepest, angle);
                    horizon = Math.max(horizon, angle);
                }
            }
            double water = (double) wet / Math.max(1, seen);
            // (each there at all, and nothing built between the camera and the pod)
            boolean blocked = infraManager.isPrivateGround(view[0], 40f) || infraManager.isBuildingLocation(view[0], 40f)
                    || infraManager.isRoadLocation(view[0], 15f);
            for (double t = 0.15; t < 0.9; t += 0.15) {
                SpherePosition p = SphereMath.move(view[0], SphereMath.heading(view[0], frame.toSphere(0, 0, 0)), 165 * t);
                if (infraManager.isPrivateGround(p, 10f) || infraManager.isBuildingLocation(p, 12f) || infraManager.isRoadLocation(p, 6f)) blocked = true;
            }
            if (blocked) continue;
            double waterScore = Math.min(water, VIEW_WATER) / VIEW_WATER - Math.max(0, water - 0.6);
            double mountainScore = Math.max(0, Math.min(steepest, VIEW_MOUNTAINS)) / VIEW_MOUNTAINS;
            double houseScore = Math.min(houses, VIEW_HOUSES) / VIEW_HOUSES;
            // (some of each, not all of one)
            double score = waterScore + mountainScore + houseScore + 2 * Math.min(waterScore, Math.min(mountainScore, houseScore));
            if (score > bestScore) {
                bestScore = score;
                System.out.printf("[MENU] better site: water %.2f, hills %.1f degrees, houses %d%n", water, steepest, houses);
                best = site;
            }
        }
        if (best != null) System.out.printf("[MENU] Picturesque site chosen (score %.2f of 5)%n", bestScore);
        return best;
    }

    /** Where the main menu's picture is taken from and what it looks at, round a pod (see menuShot): {eye, target}. */
    private SpherePosition[] menuView(LandingPod landed) {
        SphereFrame pod = landed.frame();
        double side = Math.toRadians(Double.parseDouble(Dev.property("xenoguesser.menuangle", "38")));
        double distance = Double.parseDouble(Dev.property("xenoguesser.menudistance", "165"));
        double ex = Math.sin(side) * distance, ez = Math.cos(side) * distance;
        SpherePosition standing = pod.toSphere(ex, 0.0, ez);
        double eye = Math.max(TerrainMesh.getLayeredHeight(standing, worldNoise), seaLevelHeight) + 26.0;
        // The pod sits just right of the middle: the menu takes the left, the spaceman the far right
        double lookSide = side + Math.PI + Math.toRadians(Double.parseDouble(Dev.property("xenoguesser.menuoffset", "1.5")));
        return new SpherePosition[] { SphereMath.atAltitude(standing, eye),
            pod.toSphere(ex + Math.sin(lookSide) * 100.0, landed.ground() + 24.0, ez + Math.cos(lookSide) * 100.0) };
    }

    private void menuShot(GL3 gl) {
        if (framesRenderedSinceReady == 60) {
            hideFirstPerson = true;
            // In the pod's own space (x across, z the way its stairs run): off to one side of
            // the stairs, a little above head height, looking at the pod (see menuView)
            SpherePosition[] view = menuView(landingPod);
            camera.setSphereView(view[0], 0f, 0f);
            camera.lookAt(view[1]);
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

    // Whether the world's ready as soon as it's loaded, its first frames drawn only once it's
    // shown (while the landing pod's up, they'd only stall its frames, seen by no one)
    private volatile boolean firstFramesDeferred;

    public void deferFirstFrames() {
        firstFramesDeferred = true;
    }

    /**
     * How far the soil's colour departs from the base soil texture over the whole sphere, as a
     * cube map (see SphereRaster) of per-channel ratios, halved to fit in [0, 1], three bytes a
     * texel. The terrain shader multiplies the soil texture by it in each place's direction, so
     * the ground shifts gradually between the palette's base soil and its two regional
     * variants over tens of kilometres. Kept until the GL thread uploads it (see soilRegionTexture).
     */
    public void buildSoilRegions(WorldPalette palette) {
        SphereRaster raster = new SphereRaster(SOIL_REGION_SIDE);
        float[] base = palette.soilBase, a = palette.soilRegionalA, b = palette.soilRegionalB;
        int size = raster.size();
        float[] fa = new float[size], fb = new float[size];
        java.util.stream.IntStream.range(0, size).parallel().forEach(t -> {
            GeodesicChunkGrid.Direction d = raster.direction(t);
            fa[t] = soilVariantAFactor.evaluate(d);
            fb[t] = soilVariantBFactor.evaluate(d);
        });
        // Noise values bunch around the middle, so thresholds come from their actual spread:
        // each variant fades in over a wide band of its factor, so the change is gradual
        float[] rangeA = percentiles(fa, 0.3f, 0.85f), rangeB = percentiles(fb, 0.3f, 0.85f);
        soilBlendRanges = new float[][] { rangeA, rangeB };
        byte[] texels = new byte[size * 3];
        for (int t = 0; t < size; t++) {
            float wa = ProceduralTextures.smoothstep(rangeA[0], rangeA[1], fa[t]);
            float wb = ProceduralTextures.smoothstep(rangeB[0], rangeB[1], fb[t]);
            for (int c = 0; c < 3; c++) {
                float colour = base[c] + (a[c] - base[c]) * wa;
                colour += (b[c] - colour) * wb;
                float ratio = Math.min(2.0f, colour / Math.max(0.02f, base[c]));
                texels[t * 3 + c] = (byte) Math.round(ratio * 0.5f * 255f);
            }
        }
        soilRegionTexels = texels;
    }

    // The soil colour cube map: texels a face's side, its texels until uploaded, and the texture
    private static final int SOIL_REGION_SIDE = 64;
    private volatile byte[] soilRegionTexels;
    private int soilRegionTexture;
    // The sea's wave map (RegionalGenerationManager.waveFieldTexels) as a cube map
    private int waveFieldTexture;

    /**
     * Uploads a SphereRaster's texels (channels bytes each, in its order) as a cube map,
     * linearly filtered and seamless across the faces' edges; returns the texture.
     */
    private int uploadCubeMap(int side, int channels, byte[] texels) {
        int[] id = new int[1];
        gl.glGenTextures(1, id, 0);
        gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, id[0]);
        gl.glPixelStorei(GL3.GL_UNPACK_ALIGNMENT, 1);
        int internal = channels == 2 ? GL3.GL_RG8 : GL3.GL_RGB8, format = channels == 2 ? GL3.GL_RG : GL3.GL_RGB;
        int faceBytes = side * side * channels;
        for (int face = 0; face < 6; face++) {
            gl.glTexImage2D(GL3.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, internal, side, side, 0, format, GL3.GL_UNSIGNED_BYTE,
                    java.nio.ByteBuffer.wrap(texels, face * faceBytes, faceBytes));
        }
        gl.glPixelStorei(GL3.GL_UNPACK_ALIGNMENT, 4);
        gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
        for (int wrap : new int[] { GL3.GL_TEXTURE_WRAP_S, GL3.GL_TEXTURE_WRAP_T, GL3.GL_TEXTURE_WRAP_R }) {
            gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, wrap, GL3.GL_CLAMP_TO_EDGE);
        }
        gl.glEnable(GL3.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, 0);
        return id[0];
    }

    /** As uploadCubeMap, for a SphereRaster of pairs of floats per texel (kept as floats, RG). */
    private int uploadCubeMap(int side, float[] texels) {
        int[] id = new int[1];
        gl.glGenTextures(1, id, 0);
        gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, id[0]);
        int faceFloats = side * side * 2;
        for (int face = 0; face < 6; face++) {
            gl.glTexImage2D(GL3.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL3.GL_RG32F, side, side, 0, GL3.GL_RG, GL3.GL_FLOAT,
                    java.nio.FloatBuffer.wrap(texels, face * faceFloats, faceFloats));
        }
        gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
        for (int wrap : new int[] { GL3.GL_TEXTURE_WRAP_S, GL3.GL_TEXTURE_WRAP_T, GL3.GL_TEXTURE_WRAP_R }) {
            gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, wrap, GL3.GL_CLAMP_TO_EDGE);
        }
        gl.glEnable(GL3.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, 0);
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
        if (sphereSkyRenderer != null) sphereSkyRenderer.dispose(gl);
        if (sphereWeather != null) sphereWeather.dispose(gl);
        if (geodesicTerrainModel != null && geodesicTerrainModel.mesh != null) {
            geodesicTerrainModel.mesh.dispose(gl);
        }
        if (seaGrid != null) seaGrid.dispose(gl);
        if (geodesicTerrainBuild != null) geodesicTerrainBuild.cancel(true);
        floraStream.clear();
        grassStream.clear();
        
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
            gl.glDeleteBuffers(2, new int[]{grassVBO, grassInstanceVBO}, 0);
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
     * Turns the sky dome so the sun on its texture (at the dome's local -Y) lies towards the
     * sun, the rest of the sky wheeling about the celestial pole as on any spinning planet: in
     * the local east/up/south frame at a place.
     */
    private Matrix4 skyRotation(SpherePosition at) {
        return skyRotation(Planet.sunDirection(at, sunDeclination, noonLongitude),
                Planet.celestialPole(at));
    }

    private Matrix4 skyRotation(float[] sun, float[] pole) {
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

    /**
     * The GL side's set-up (textures, shaders, the posts, signs and sea), as many small steps,
     * run a few to a frame (see runSteps) so the landing pod's frames keep coming as it's done.
     */
    private List<Runnable> coreSteps() {
        // Every picture the steps put on the GPU, read and decoded ahead on a background thread
        List<String> pictures = new ArrayList<>(List.of(WorldArtGenerator.pathFor(WorldArtGenerator.SOIL),
                WorldArtGenerator.pathFor(WorldArtGenerator.SOIL_TILE), WorldArtGenerator.pathFor(WorldArtGenerator.SEA),
                WorldArtGenerator.pathFor(WorldArtGenerator.SKY), WorldArtGenerator.pathFor(WorldArtGenerator.SUN_GLOW)));
        for (int s = 0; s < NUM_SPECIES; s++) pictures.add(WorldArtGenerator.pathFor(WorldArtGenerator.leafName(s)));
        for (String name : infraManager.textureJobs().keySet()) pictures.add(WorldArtGenerator.pathFor(name));
        pictures.add(WorldArtGenerator.pathFor(WorldArtGenerator.GRASS_ATLAS));
        TextureLibrary.prefetch(gl.getGLProfile(), pictures);
        List<Runnable> steps = new ArrayList<>();
        // All textures were generated for this world by WorldArtGenerator before the window opened
        steps.add(() -> {
            textures = new TextureLibrary();
            // Repeating: dirt tracks lay it at world scale, well past one tile
            textures.addWrap(gl, "dirt_diffuse", WorldArtGenerator.pathFor(WorldArtGenerator.SOIL));
        });
        // (the ground's own, on its triangular cells: clamped, as it has its own margins round them)
        steps.add(() -> textures.add(gl, "soil_tile", WorldArtGenerator.pathFor(WorldArtGenerator.SOIL_TILE)));
        steps.add(() -> textures.add(gl, "water_diffuse", WorldArtGenerator.pathFor(WorldArtGenerator.SEA)));
        steps.add(() -> textures.add(gl, "sky", WorldArtGenerator.pathFor(WorldArtGenerator.SKY)));
        steps.add(() -> textures.add(gl, "sun_glow", WorldArtGenerator.pathFor(WorldArtGenerator.SUN_GLOW)));
        for (int s = 0; s < NUM_SPECIES; s++) {
            int species = s;
            steps.add(() -> textures.add(gl, "leaf" + species, WorldArtGenerator.pathFor(WorldArtGenerator.leafName(species))));
        }
        steps.add(this::initialiseLightAndSky);
        steps.add(() -> sphereWeather = new SphereWeather(gl, worldSeed));
        steps.add(this::initialiseCoreShaders);
        steps.add(this::initialiseGround);
        // Each nation's wall, roof and fence textures repeat across surfaces
        for (String name : infraManager.textureJobs().keySet()) {
            steps.add(() -> {
                textures.addWrap(gl, name, WorldArtGenerator.pathFor(name));
                enableAnisotropicFiltering(textures.get(name));
            });
        }
        steps.add(this::initialisePosts);
        steps.add(this::initialiseSigns);
        for (int n = 1; n <= totalNationsCount; n++) {
            int nation = n;
            steps.add(() -> initialiseSign(nation));
        }
        steps.add(() -> {
            // --- MULTI-SPECIES 3D GEOMETRY COMPILATION PIPELINE ---
            floraBranchModelsLOD = new Model[NUM_SPECIES][3][FLORA_VARIATIONS];
            floraOutlines = new float[NUM_SPECIES][FLORA_VARIATIONS][];
            floraLeafModelsLOD = new Model[NUM_SPECIES][3][FLORA_VARIATIONS];
            leafShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_leaf.txt");
        });
        steps.add(this::initialiseWater);
        steps.add(this::initialiseGrass);
        return steps;
    }

    /** The sea's texture repeating, the light and the sun, and the sky. */
    private void initialiseLightAndSky() {
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

        sphereSkyRenderer = new SphereSkyRenderer(gl);
    }

    private void initialiseCoreShaders() {
        shadowMaps.initialise(gl);
        shadowMapsMade = true;
        shadowCutoutShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_shadow_cutout.txt");
        terrainShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_standard_d.txt");
        floraShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_flora.txt");
        depthPrePassShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_depth_only.txt");
        solidShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_solid.txt");
        glassShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_glass.txt", GamePaths.HOME + "assets/shaders/fs_glass.txt");
    }

    /** The ground's material, and the cube maps of the soil's regions and the sea's waves. */
    private void initialiseGround() {
        terrainMaterial = new Material(
            new Vector3(1.0f, 1.0f, 1.0f), 
            new Vector3(1.0f, 1.0f, 1.0f), 
            new Vector3(0.1f, 0.1f, 0.1f), 
            4.0f                                                                                                                                                                                
        );
        terrainMaterial.setDiffuseMap(textures.get("soil_tile"));
                enableAnisotropicFiltering(textures.get("dirt_diffuse"));
        enableAnisotropicFiltering(textures.get("soil_tile"));
        soilRegionTexture = uploadCubeMap(SOIL_REGION_SIDE, 3, soilRegionTexels);
        soilRegionTexels = null;
        InfrastructureObject.soilRegions = soilRegionTexture;
        // The sea's waves: how big they are and the distance to the shore, by direction
        float[] waves = regionalManager.waveFieldTexels();
        if (waves != null) waveFieldTexture = uploadCubeMap(regionalManager.waveFieldSide(), waves);
    }

    /** The renderer for the world, and each nation's posts. */
    private void initialisePosts() {
        infraManager.setNationTextures(textures::get);
        terrainRenderer = new Renderer();
        // (the sun lights the world: its position is kept relative to the eyes, see render)
        terrainRenderer.setLights(lights);
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
            Model postModel = new Model("post_nation_" + n, postMesh, new Matrix4(1), solidShader, postMat, terrainRenderer, camera);
            postModelsByNation.put(n, postModel);
        }

    }

    // The signs' board, shared by every nation's (see initialiseSign)
    private Mesh signMeshBase;

    /** The signs' shader and board. */
    private void initialiseSigns() {
        // 3. Pre-compile the flat TwoTriangles billboard models for each nation with Text Atlas Mapping
        signboardShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_standard.txt", GamePaths.HOME + "assets/shaders/fs_signboard.txt");
        signMeshBase = signBoardMesh(gl, 16);

        this.nationAtlases = new HashMap<>();
        this.nationAtlasSizes = new HashMap<>();
        this.nationDirections = new HashMap<>();
        
        // Dynamically count subdirectories inside generated_alphabets
        java.io.File alphabetsDir = new java.io.File(RunFiles.ALPHABETS_DIR);
        java.io.File[] alphabetFolders = alphabetsDir.listFiles(java.io.File::isDirectory);
        
        int totalAvailableAlphabets = (alphabetFolders != null) ? alphabetFolders.length : 0;
        
        if (totalAvailableAlphabets == 0) {
            System.err.println("Warning: No alphabet folders found in generated_alphabets!");
        }
    }

    /** A nation's sign: its writing's atlas and its board's model. */
    private void initialiseSign(int n) {
        {
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
            
            // White, so the sign's own picture shows as it is
            Material signMaterial = new Material(
                new Vector3(1.0f, 1.0f, 1.0f),                     
                new Vector3(1.0f, 1.0f, 1.0f),                     
                new Vector3(0.0f, 0.0f, 0.0f),    
                1.0f                           
            );
            
            // Note: We are using the new signboardShader here instead of solidShader
            Model signModel = new Model("sign_nation_" + n, signMeshBase, new Matrix4(1), signboardShader, signMaterial, terrainRenderer, camera);
            signModelsByNation.put(n, signModel);
        }
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

                Flora.Design design = sc.design.copy();
                design.branchingRate = bRate;
                design.startWidth = sWidth;
                design.widthDecline = wDecl;
                design.stoppingDistance = sDist;
                design.meanBranchAngle = bAngle;

                for (int lod = onlyLod; lod <= onlyLod; lod++) {
                    
                    Flora.FloraBundle fBundle = Flora.generateFloraBundle(
                        gl, worldSeed + (i * 7382L) + s * 8831L, design, lodSlices[lod], lod
                    );
                    
                    floraOutlines[s][i] = fBundle.outline;
                    floraBranchModelsLOD[s][lod][i] = new Model("flora_branch_s" + s + "_" + i + "_lod" + lod, fBundle.branchMesh, new Matrix4(1), floraShader, floraMat, terrainRenderer, camera);
                    floraLeafModelsLOD[s][lod][i] = new Model("flora_leaf_s" + s + "_" + i + "_lod" + lod, fBundle.leafMesh, new Matrix4(1), leafShader, leafMat, terrainRenderer, camera);
                }
            }
        }
    }

    /**
     * The plants' forms: {the four trees', the four shrubs'}, each four different forms drawn
     * from r (so a world might have a giant mushroom, a palm, a cactus and an orb tree).
     */
    private static Flora.Design.Form[][] plantForms(Random r) {
        Flora.Design.Form[][] forms = new Flora.Design.Form[2][];
        for (int k = 0; k < 2; k++) {
            List<Flora.Design.Form> all = new ArrayList<>(List.of(Flora.Design.Form.values()));
            java.util.Collections.shuffle(all, r);
            forms[k] = all.subList(0, 4).toArray(new Flora.Design.Form[0]);
        }
        return forms;
    }

    /**
     * A species' body colour (anything from a cactus green to a fungal violet), and its markings:
     * spots, stripes, pits or rings (or none) in a second colour, drawn from r.
     */
    private static void plantMarkings(SpeciesConfig sc, Random r) {
        float hue = r.nextFloat(), saturation = 0.3f + r.nextFloat() * 0.5f, brightness = 0.45f + r.nextFloat() * 0.45f;
        sc.bodyColour = hsb(hue, saturation, brightness);
        // (mostly a contrasting hue; sometimes the body's own, much paler or darker)
        if (r.nextFloat() < 0.6f) {
            sc.patternColour = hsb(hue + 0.35f + r.nextFloat() * 0.3f, 0.5f + r.nextFloat() * 0.4f, 0.6f + r.nextFloat() * 0.4f);
        } else {
            sc.patternColour = hsb(hue, saturation * 0.5f, r.nextBoolean() ? Math.min(1f, brightness * 1.7f) : brightness * 0.4f);
        }
        // Which markings, the likelier ones by form (a mushroom's spots, a cactus' stripes down its ribs, a palm's rings)
        float[] odds = switch (sc.design.form) {
            case MUSHROOM -> new float[] { 0.15f, 0.55f, 0.1f, 0.15f, 0.05f };
            case CACTUS -> new float[] { 0.2f, 0.15f, 0.35f, 0.25f, 0.05f };
            case PALM -> new float[] { 0.2f, 0.1f, 0.1f, 0.1f, 0.5f };
            default -> new float[] { 0.25f, 0.25f, 0.2f, 0.2f, 0.1f };
        };
        float pick = r.nextFloat();
        int pattern = 0;
        while (pattern < odds.length - 1 && pick >= odds[pattern]) pick -= odds[pattern++];
        sc.pattern = pattern;
        sc.patternScale = 5f + r.nextInt(8);
    }

    private static Vector3 hsb(float hue, float saturation, float brightness) {
        int rgb = java.awt.Color.HSBtoRGB(hue, Math.min(1f, saturation), Math.min(1f, brightness));
        return new Vector3(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
    }

    /** A plant species' body colours and markings, for floraShader (accent: its orbs', the plant's foliage colour). */
    private void floraBodyUniforms(GL3 gl, SpeciesConfig sc, Vector3 accent) {
        floraShader.setVec3(gl, "u_BodyColour", sc.bodyColour);
        floraShader.setVec3(gl, "u_PatternColour", sc.patternColour);
        floraShader.setVec3(gl, "u_AccentColour", accent);
        floraShader.setFloat(gl, "u_Pattern", sc.pattern);
        floraShader.setFloat(gl, "u_PatternScale", sc.patternScale);
    }

    // Vertices along each side of the sea's grid; spacing grows from about 2 units under the
    // viewer to about 30 at the edge, fine enough for the waves where they can be seen
    private static final int WATER_GRID_SIZE = 256;
    // How far the grid reaches along the ground either side of the viewer (as the old water
    // plane did: past the sea's horizon from any usual height, and the fog is total by then)
    private static final float SEA_GRID_REACH = 2200f;
    private static final float WATER_GRID_CENTRE_DENSITY = 0.12f;

    /** A unit square of water (x, z from -1/2 to 1/2), densest at its centre, for vs_sphere_water to wrap onto the sea and displace. */
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

    private void initialiseWater() {
        sphereWaterShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_sphere_water.txt",
                GamePaths.HOME + "assets/shaders/fs_sphere_water.txt");
        riverShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_river.txt", GamePaths.HOME + "assets/shaders/fs_river.txt");
        
        waterMaterial = new Material(
            new Vector3(0.01f, 0.31f, 0.55f),  
            new Vector3(0.01f, 0.31f, 0.55f),  
                        new Vector3(6.0f, 6.0f, 6.0f),
            2048f                                                                                                                                                                                
        );
        waterMaterial.setDiffuseMap(textures.get("water_diffuse"));
        seaGrid = createWaterGrid(gl);
    }

    private void initialiseGrass() {
        textures.add(gl, "grass_atlas",  WorldArtGenerator.pathFor(WorldArtGenerator.GRASS_ATLAS));
        
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
             0.8f, GRASS_BLADE_HEIGHT,  0.0f,  1.0f, 1.0f,
            -0.8f, 0.0f,  0.0f,  0.0f, 0.0f,
             0.8f, GRASS_BLADE_HEIGHT,  0.0f,  1.0f, 1.0f,
            -0.8f, GRASS_BLADE_HEIGHT,  0.0f,  0.0f, 1.0f
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
        grassInstanceVBO = tempBuffers[0];
        gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassInstanceVBO);
        
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

    /** The rest of the set-up once the trees are made (creatures, people, the player, the rocks, the first spawn), a step at a time as coreSteps. */
    private List<Runnable> finishSteps() {
        List<Runnable> steps = new ArrayList<>();
        steps.add(() -> organismManager.initialise(gl));
        // (a picture of each species, one at a time)
        steps.add(() -> portraitsMade = new java.awt.Image[organismManager.speciesCount()]);
        for (int i = 0; i < organismManager.speciesCount(); i++) {
            int species = i;
            steps.add(() -> portraitsMade[species] = organismManager.portrait(gl, species, 96));
        }
        steps.add(() -> showSpeciesPortraits(portraitsMade));
        // (and each plant's, likewise: so the map's and the shop's choices have them from the start)
        steps.add(() -> floraMade = new java.awt.Image[NUM_SPECIES]);
        for (int s = 0; s < NUM_SPECIES; s++) {
            int species = s;
            steps.add(() -> floraMade[species] = floraPortrait(gl, species));
        }
        steps.add(() -> showFloraPortraits(floraMade));
        steps.add(() -> inhabitants.initialise(gl));
        steps.add(() -> {
            // Pictures of the locals, for signs
            deletePeoplePictures();
            inhabitants.setFlags(n -> textures.get(InfrastructureManager.nationTextureName("flag", n)));
            inhabitants.setPackaging(n -> textures.get(InfrastructureManager.nationTextureName("packaging", n)));
            peoplePictures = inhabitants.renderPictures(gl, totalNationsCount, infraManager.products(),
                    n -> textures.get(InfrastructureManager.nationTextureName("packaging", n)));
            if (Dev.property("xenoguesser.dumpart") != null) dumpPeoplePictures();
        });
        steps.add(this::initialiseBodies);
        steps.add(() -> landingPod.initialise(gl));
        steps.add(() -> {
            rockField = rocks();
            rockField.setKeepout((p, r) -> {
                if (podCovers(p, r)) return true;
                return infraManager.isRoadLocation(p, r + 2f) || infraManager.isBuildingLocation(p, r + 4f);
            });
            rockField.initialise(gl, terrainBuilders);
        });
        steps.add(() -> hud.initialise(gl));
        steps.add(this::spawnPlayerAtRandomLocation);
        steps.add(() -> createDepthFramebuffer(gl, currentWidth, currentHeight));
        return steps;
    }

    /** The player's own body, and in multiplayer the partner's. */
    private void initialiseBodies() {
        playerBody.initialise(gl);
        Multiplayer session = Multiplayer.active();
        if (session != null) {
            // Multiplayer (M6): the packs on this player's own body in their colour, and the
            // partner, seen from outside, in theirs (darker, as lit in the world; grey trim as the pod's)
            playerBody.setColours(new float[] { 0.5f, 0.51f, 0.54f }, session.role.trimColour());
            partnerBody = PlayerBody.seenFromOutside();
            partnerBody.setColours(new float[] { 0.2f, 0.21f, 0.23f }, session.role.other().litTrimColour());
            partnerBody.initialise(gl);
        }
    }

    // The set-up steps under way (coreSteps, then finishSteps), and how many of them are done
    private List<Runnable> setUpSteps;
    private int setUpDone;
    // How long a frame's set-up steps may take together (always at least one a frame)
    private static final long SET_UP_BUDGET_NANOS = 6_000_000L;
    // Developer aid: -Dxenoguesser.podstalls logs each set-up step that takes long
    private static final boolean LOG_SLOW_STEPS = Dev.property("xenoguesser.podstalls") != null;

    /**
     * Runs the next of the set-up steps, as many as fit in SET_UP_BUDGET_NANOS (at least one),
     * reporting how far through they are; true once they're all done.
     */
    private boolean runSteps(List<Runnable> steps) {
        if (setUpSteps != steps) {
            setUpSteps = steps;
            setUpDone = 0;
        }
        long end = System.nanoTime() + SET_UP_BUDGET_NANOS;
        do {
            long started = System.nanoTime();
            steps.get(setUpDone).run();
            long took = (System.nanoTime() - started) / 1_000_000L;
            if (LOG_SLOW_STEPS && took > 40) System.out.printf("[STALL] Set-up step %d of %d took %d ms%n", setUpDone + 1, steps.size(), took);
            setUpDone++;
        } while (setUpDone < steps.size() && System.nanoTime() < end);
        loading.report(setUpDone / (float) steps.size());
        return setUpDone >= steps.size();
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
    private final java.util.Random spawnRandom = Dev.longValue("xenoguesser.seed") != null
            ? new java.util.Random(Dev.longValue("xenoguesser.seed")) : new java.util.Random();

    // ------------------------------------------------------------------ multiplayer: the same rounds on both PCs

    // The round the player was last spawned for (from 1): rounds are counted here as they're
    // built, the same on both PCs, since each builds every round once
    private int spawnedRound;
    // What each round's Random is drawn for, kept apart so one PC drawing a number more for one
    // of them (say a queued site it didn't need) can't change the others
    private static final long SITE_DRAWS = 1, DAY_DRAWS = 2, CREATURE_DRAWS = 3;

    /**
     * What a round's random choices are drawn from. In multiplayer (M5, M19) a Random made
     * afresh from the world seed, the round and what it's for, so both PCs draw the same landing
     * site, time of day, season, weather and creatures, and can't drift apart; in singleplayer
     * the one long-lived spawnRandom, as always.
     */
    private java.util.Random roundRandom(int round, long draws) {
        if (!Multiplayer.isActive()) return spawnRandom;
        return new java.util.Random(worldSeed * 0x9E3779B97F4A7C15L + round * 0xBF58476D1CE4E5B9L + draws * 0x94D049BB133111EBL);
    }

    /** Where a normal round's pod comes down: near a road, clear of houses, somewhere on the map. */
    private Viewpoint landingSite(java.util.Random rand) {
        return infraManager.landingSite(rand, LandingPod.SPAWN_DISTANCE, LandingPod.REACH,
                p -> TerrainMesh.getLayeredHeight(p, worldNoise) > seaLevelHeight + 1.0f
                        && !underFreshWater(p, TerrainMesh.getLayeredHeight(p, worldNoise))
                        // (within the map's top and bottom edges, so the place can be guessed)
                        && Math.abs(Planet.latitude(p)) < MapProjection.CLIP_LATITUDE,
                // (in the country, along the roads, or in town, as chosen for the game)
                MapKind.current());
    }

    // ------------------------------------------------------------------ the next round, got ready ahead

    // The next round's landing site, chosen as soon as this round is under way, and where its
    // pod will stand; its surroundings (trees, grass) are worked out in spare time on a thread
    // of their own, nearest first, so that moving there is mostly a matter of uploading them
    private Viewpoint queuedSite;
    private SpherePosition queuedPod;
    private final java.util.concurrent.ExecutorService prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "next-round-prefetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Chooses where the next round will be and starts getting it ready (normal rounds only). */
    private void queueNextRound() {
        floraStream.clearPrefetch();
        grassStream.clearPrefetch();
        queuedPod = null;
        if (Dev.property("xenoguesser.view") != null || Dev.property("xenoguesser.spawnat") != null) return;
        queuedSite = landingSite(roundRandom(spawnedRound + 1, SITE_DRAWS));
        if (queuedSite == null) return;
        if (Multiplayer.isActive()) {
            System.out.printf(java.util.Locale.ROOT, "[ROUND] Round %d queued: site %.5f, %.5f heading %.4f%n", spawnedRound + 1,
                    Math.toDegrees(Planet.latitude(queuedSite.place())), Math.toDegrees(Planet.longitude(queuedSite.place())), queuedSite.heading());
        }
        queuedPod = SphereMath.move(queuedSite.place(), queuedSite.heading() + Math.PI, LandingPod.SPAWN_DISTANCE);
        floraStream.prefetchAround(queuedSite.place());
        grassStream.prefetchAround(queuedSite.place());
    }

    /** Hands the prefetch thread its next few cells once it has finished the last. */
    private void pumpPrefetch() {
        floraStream.pump(prefetcher);
        grassStream.pump(prefetcher);
    }

    /** Whether a place is within margin of the landing pod, or of where the next round's will stand. */
    private boolean podCovers(SpherePosition p, double margin) {
        return landingPod.covers(p, margin)
                || (queuedPod != null && SphereMath.distance(p, queuedPod) < Math.max(40f, LandingPod.REACH) + margin);
    }

    private void spawnPlayerAtRandomLocation() {
        int round = ++spawnedRound;
        java.util.Random dynamicRand = roundRandom(round, DAY_DRAWS);
        String devView = Dev.property("xenoguesser.view");
        // Rounds always start on a road, looking along it
        // Developer aid: -Dxenoguesser.view=house starts each round looking at a house
        Viewpoint spawn = "house".equals(devView)
                ? infraManager.randomHouseViewpoint(dynamicRand) : infraManager.randomRoadPoint(dynamicRand);
        if (devView == null) {
            // A normal round: the pod has come down near a road, clear of houses and fences (where
            // it was chosen during the last round, so its surroundings could be got ready then)
            Viewpoint site = queuedSite != null ? queuedSite : landingSite(roundRandom(round, SITE_DRAWS));
            queuedSite = null;
            queuedPod = null;
            if (site != null) spawn = site;
        }
        // Developer aid: the main menu's picture (-Dxenoguesser.menushot) is taken somewhere picturesque
        if (MENU_SHOT && devView == null) spawn = orElse(picturesqueSite(dynamicRand), spawn);
        // Developer aids: on a town pavement among the inhabitants; across the street from a
        // shop, looking at its front; on a dirt track, looking along it; looking at a raised
        // road's retaining wall from below
        if ("street".equals(devView)) spawn = orElse(inhabitants.streetViewpoint(dynamicRand), spawn);
        if ("shop".equals(devView)) spawn = orElse(infraManager.shopViewpoint(dynamicRand), spawn);
        if ("track".equals(devView)) spawn = orElse(infraManager.trackViewpoint(dynamicRand), spawn);
        if ("raisedroad".equals(devView)) spawn = orElse(infraManager.raisedRoadViewpoint(dynamicRand), spawn);
        // One of every animal side by side; some species' range with one of them just ahead
        if ("zoo".equals(devView)) spawn = orElse(organismManager.zooViewpoint(dynamicRand), spawn);
        if ("bug".equals(devView)) spawn = orElse(organismManager.showcaseViewpoint(dynamicRand), spawn);
        // Deep in the heartland of a species that lives in herds
        if ("herd".equals(devView)) spawn = orElse(organismManager.herdViewpoint(dynamicRand), spawn);
        // On a river's bank (-Dxenoguesser.riverwidth=20: one at least that wide), looking across and up it
        if ("river".equals(devView)) spawn = orElse(riverViewpoint(dynamicRand), spawn);
        // Where plant species -Dxenoguesser.plant (random by default) grows thickest
        if ("plant".equals(devView)) spawn = orElse(plantViewpoint(dynamicRand), spawn);
        // On a side road looking at where it meets (at a step it would have had) a greater one
        if ("junction".equals(devView)) spawn = orElse(infraManager.junctionViewpoint(dynamicRand), spawn);
        // Across from one of the richest houses, dressed in all its architecture; or back from a
        // big city's tower, looking up at it
        if ("ornate".equals(devView)) spawn = orElse(infraManager.ornateHouseViewpoint(dynamicRand), spawn);
        if ("tower".equals(devView)) spawn = orElse(infraManager.towerViewpoint(dynamicRand), spawn);
        // In front of one of the famous landmarks (-Dxenoguesser.landmark=spire, stones or ruin for one kind)
        if ("landmark".equals(devView)) spawn = orElse(infraManager.landmarkViewpoint(dynamicRand), spawn);
        // At a road's edge looking along its bollards
        if ("bollard".equals(devView)) spawn = orElse(infraManager.bollardViewpoint(dynamicRand), spawn);
        // In town between two utility poles, looking along the road
        if ("pole".equals(devView)) spawn = orElse(infraManager.poleViewpoint(dynamicRand), spawn);
        // Side on to a pole's wire down to a house
        if ("drop".equals(devView)) spawn = orElse(infraManager.poleDropViewpoint(dynamicRand), spawn);
        // A little before a bridge, looking over it (a plank one with plankbridge)
        if ("bridge".equals(devView) || "plankbridge".equals(devView)) spawn = orElse(infraManager.bridgeViewpoint(dynamicRand, "plankbridge".equals(devView)), spawn);
        // Beside one of the bigger lakes, looking out over it
        if ("lake".equals(devView)) spawn = orElse(lakeViewpoint(dynamicRand), spawn);

        // Developer aid: -Dxenoguesser.spawnat=latitude,longitude (degrees, north and east
        // positive) starts the round there
        String spawnAt = Dev.property("xenoguesser.spawnat");
        if (spawnAt != null) {
            String[] parts = spawnAt.split(",");
            double latitude = Double.parseDouble(parts[0].trim()), longitude = Double.parseDouble(parts[1].trim());
            if (parts.length != 2 || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw new IllegalArgumentException("xenoguesser.spawnat takes latitude,longitude in degrees, not " + spawnAt);
            }
            spawn = new Viewpoint(Planet.position(Math.toRadians(latitude), Math.toRadians(longitude), 0.0),
                    spawn != null ? spawn.heading() : -Math.PI * 0.5);
        }

        if ("mountain".equals(devView)) {
            // Developer aid: some way off from the highest ground found, looking at it
            SpherePosition best = null;
            float highest = -Float.MAX_VALUE;
            for (int attempt = 0; attempt < 6000; attempt++) {
                SpherePosition p = SphereMath.randomPlace(dynamicRand, MapProjection.CLIP_LATITUDE);
                float h = TerrainMesh.getLayeredHeight(p, worldNoise);
                if (h > highest) { highest = h; best = p; }
            }
            // From the lowest dry spot on a ring round it, for a clear view up
            float lowest = Float.MAX_VALUE;
            for (int attempt = 0; attempt < 96; attempt++) {
                SpherePosition p = SphereMath.move(best, attempt * Math.PI * 2 / 96, 1250.0);
                float h = TerrainMesh.getLayeredHeight(p, worldNoise);
                if (h > seaLevelHeight + 2f && h < lowest) {
                    lowest = h;
                    spawn = new Viewpoint(p, SphereMath.heading(p, best));
                }
            }
        }
        if ("shore".equals(devView) || "swim".equals(devView)) {
            // Developer aid: stand on a coast facing the open sea, to watch the waves: dry land a
            // couple of hundred units from the sea, looking out where it's sea all the way out
            for (int attempt = 0; attempt < 200000; attempt++) {
                SpherePosition p = SphereMath.randomPlace(dynamicRand, MapProjection.CLIP_LATITUDE);
                if (TerrainMesh.getLayeredHeight(p, worldNoise) <= seaLevelHeight + 2.0f) continue;
                float shore = regionalManager.distanceToWater(p);
                if (shore < 150f || shore > 250f) continue;
                double look = Double.NaN;
                for (int a = 0; a < 16 && Double.isNaN(look); a++) {
                    boolean open = true;
                    for (double out = 600.0; out <= 3000.0 && open; out += 600.0) {
                        open = TerrainMesh.getLayeredHeight(SphereMath.move(p, a * Math.PI / 8, out), worldNoise) <= seaLevelHeight;
                    }
                    if (open) look = a * Math.PI / 8;
                }
                if (!Double.isNaN(look)) {
                    spawn = new Viewpoint(p, look);
                    if ("swim".equals(devView)) {
                        // Out in the water, looking back at the waves rolling in
                        SpherePosition out = SphereMath.move(p, look, 600.0);
                        spawn = new Viewpoint(out, SphereMath.carryHeading(p, out, look) + Math.PI);
                    }
                    break;
                }
            }
        }

        while (spawn == null) {
            SpherePosition p = SphereMath.randomPlace(dynamicRand, MapProjection.CLIP_LATITUDE);
            float ground = TerrainMesh.getLayeredHeight(p, worldNoise);
            if (ground > seaLevelHeight && !underFreshWater(p, ground)) spawn = new Viewpoint(p, dynamicRand.nextDouble() * Math.PI * 2);
        }

        this.timeOfDay = dynamicRand.nextFloat();

        // The season: where the planet is in its orbit sets how far north or south the sun
        // stands overhead
        float seasonalPhase = dynamicRand.nextFloat() * (float)(2.0 * Math.PI);
        this.sunDeclination = Math.asin(Math.sin(Math.toRadians(planetAxialTiltDegrees)) * Math.sin(seasonalPhase));

        if (this.minimap != null) this.minimap.setPlayerSpawnLocation(SphereMath.direction(spawn.place()));

        chooseTimeOfDay(spawn.place(), dynamicRand);
        // A new day: new clouds and weather
        if (sphereWeather != null) sphereWeather.newDay(dynamicRand, spawn.place());
        if (Multiplayer.isActive()) {
            // For checking both PCs agree: the same lines on each, round by round
            System.out.printf(java.util.Locale.ROOT, "[ROUND] Round %d (seed %d): site %.5f, %.5f heading %.4f, time of day %.4f, sun declination %.4f, noon longitude %.4f%n",
                    round, worldSeed, Math.toDegrees(Planet.latitude(spawn.place())), Math.toDegrees(Planet.longitude(spawn.place())),
                    spawn.heading(), timeOfDay, sunDeclination, noonLongitude);
        }
        // (the round's clouds drift from now until it's begun, then from when it begins: see startRoundClock)
        roundClockStart = getSeconds();
        landingPod.place(spawn.place(), spawn.heading(), p -> TerrainMesh.getLayeredHeight(p, worldNoise));
        // Nothing else stands where it came down
        infraManager.setKeepClear(landingPod.frame().origin(), Math.max(40f, LandingPod.REACH) + 6f);
        // (where the player stays where they land, out past the foot of the pod's stairs, where
        // there's more to see than the pod: settled clear of anything solid once that's there)
        if (!GameMode.current().moving && devView == null) {
            spawn = standingClear(spawn);
            settleUntil = System.nanoTime() * 1e-9 + SETTLE_SECONDS;
        }
        // Multiplayer (M11): side by side, 119-A on the left and 119-B on the right as seen
        // facing down the stairs, both still facing down them (from the same shared spot)
        if (Multiplayer.isActive()) spawn = sideBySide(spawn, Multiplayer.active().role);
        moveToLocation(spawn);
        // (each round starts with the view as usual)
        zoomWanted = 1f;
        zoom = 1f;
        applyProjection();
        // Developer aid: -Dxenoguesser.lookatsun starts the round looking straight at the sun
        if (Dev.property("xenoguesser.lookatsun") != null) {
            SpherePosition eye = camera.getSpherePosition();
            float[] d = Planet.sunDirection(eye, sunDeclination, noonLongitude);
            camera.lookAt(SphereFrame.at(eye).toWorld(d[0] * 10.0, d[1] * 10.0, d[2] * 10.0));
            System.out.printf("[PLANET] Sun %.0f degrees up, latitude %.1f, declination %.1f%n",
                    Math.toDegrees(Math.asin(d[1])), Math.toDegrees(Planet.latitude(eye)), Math.toDegrees(sunDeclination));
        }
        // Developer aid: -Dxenoguesser.pitch=-60 starts the round looking down by that many degrees
        String pitch = Dev.property("xenoguesser.pitch");
        // and -Dxenoguesser.yaw=180 turned round by that many degrees
        String yaw = Dev.property("xenoguesser.yaw");
        if (pitch != null || yaw != null) {
            camera.updateYawPitch(yaw == null ? 0f : Float.parseFloat(yaw) / camera.MOUSE_SPEED,
                    pitch == null ? 0f : Float.parseFloat(pitch) / camera.MOUSE_SPEED);
        }
    }

    // Where the player stays where they land (GameMode.NO_MOVE): this far out past where they'd
    // stand on the pod's stairs at most, nearer if need be, on dry ground clear of houses and
    // roads; and for this long after, put back towards the pod if they turn out to be in
    // something solid (trees, rocks and the like are only there once their ground's been built)
    private static final float[] STAND_OUT = { 60f, 52f, 44f, 36f, 28f, 20f, 12f };
    private static final double SETTLE_SECONDS = 8.0;
    private static final float SETTLE_STEP = 3f;
    private double settleUntil = -1.0;

    /** Where a player who can't move stands, out along the pod's stairs' way from spawn (see STAND_OUT). */
    // How far each player stands from the shared spawn, sideways: with PLAYER_RADIUS 3 they're
    // 13 apart, a clear gap between them, and 9.5 from the middle to their outer edge is well
    // inside the multiplayer stairs' half width of 14
    private static final double SIDE_OFFSET = 6.5;

    /**
     * Multiplayer's spawn for this player from the shared one: SIDE_OFFSET to the left for
     * 119-A, to the right for 119-B. Headings turn from east towards south (clockwise seen from
     * above, D10), so the left of heading h is h - pi/2. The heading is carried along, so both
     * still face exactly the same way.
     */
    private static Viewpoint sideBySide(Viewpoint spawn, Multiplayer.Role role) {
        double side = role == Multiplayer.Role.HOST ? -Math.PI / 2 : Math.PI / 2;
        SpherePosition moved = SphereMath.move(spawn.place(), spawn.heading() + side, SIDE_OFFSET);
        return new Viewpoint(moved, SphereMath.carryHeading(spawn.place(), moved, spawn.heading()));
    }

    private Viewpoint standingClear(Viewpoint spawn) {
        for (float out : STAND_OUT) {
            SpherePosition p = SphereMath.move(spawn.place(), spawn.heading(), out);
            float ground = TerrainMesh.getLayeredHeight(p, worldNoise);
            if (ground <= seaLevelHeight + 1f || underFreshWater(p, ground)) continue;
            // (nor on anyone's land: their plot, garden or driveway)
            if (infraManager.isPrivateGround(p, PLAYER_RADIUS + 2f)) continue;
            // (never on a road or track, nor at its very edge)
            if (infraManager.isRoadLocation(p, PLAYER_RADIUS + 2f)) continue;
            if (infraManager.blocksWalker(p, ground, ground + playerEyeHeight + 2f)) continue;
            if (sphereTooSteep(spawn.place(), p)) continue;
            return new Viewpoint(p, SphereMath.carryHeading(spawn.place(), p, spawn.heading()));
        }
        return spawn;
    }

    /** For a while after landing, puts a player who can't move back towards the pod if they're in something solid. */
    private void settleClear() {
        if (System.nanoTime() * 1e-9 > settleUntil || landingPod.frame() == null) return;
        SpherePosition at = camera.getSpherePosition();
        if (!geodesicCollision.blockedStatic(at, PLAYER_RADIUS, 0.0)) return;
        SpherePosition pod = landingPod.frame().origin();
        if (SphereMath.distance(at, pod) <= LandingPod.SPAWN_DISTANCE) return;
        SpherePosition back = SphereMath.move(at, SphereMath.heading(at, pod), SETTLE_STEP);
        camera.setSpherePosition(SphereMath.atAltitude(back, SphereMath.altitude(at)));
    }

    /** Developer aid: on the bank of a river at least -Dxenoguesser.riverwidth wide (default 12), looking upstream across it. */
    private Viewpoint riverViewpoint(java.util.Random rand) {
        Rivers rivers = worldNoise.rivers;
        if (rivers == null || rivers.reaches().isEmpty()) return null;
        float wide = Float.parseFloat(Dev.property("xenoguesser.riverwidth", "12"));
        for (int attempt = 0; attempt < 2000; attempt++) {
            Rivers.Reach r = rivers.reaches().get(rand.nextInt(rivers.reaches().size()));
            int k = 2 + rand.nextInt(Math.max(1, r.size() - 4));
            if (k + 1 >= r.size() || r.inLake[k] || r.halfWidth[k] * 2f < wide) continue;
            double[] p = r.points[k], q = r.points[k + 1];
            SpherePosition here = SphereMath.atAltitude(new GeodesicChunkGrid.Direction(p[0], p[1], p[2]), 0.0);
            SpherePosition down = SphereMath.atAltitude(new GeodesicChunkGrid.Direction(q[0], q[1], q[2]), 0.0);
            double downstream = SphereMath.heading(here, down);
            SpherePosition bank = SphereMath.move(here, downstream + Math.PI / 2, r.halfWidth[k] + Double.parseDouble(Dev.property("xenoguesser.riverback", "14")));
            if (TerrainMesh.getLayeredHeight(bank, worldNoise) <= seaLevelHeight + 1f) continue;
            // (back down the bank a little, looking up the river and across it)
            return new Viewpoint(bank, downstream - Math.PI * 0.62);
        }
        return null;
    }

    /** Developer aid: where a plant species (-Dxenoguesser.plant, or any) grows thickest of a few thousand places tried. */
    private Viewpoint plantViewpoint(java.util.Random rand) {
        int s = Dev.integer("xenoguesser.plant", rand.nextInt(NUM_SPECIES));
        SpherePosition best = null;
        float most = 0f;
        for (int attempt = 0; attempt < 4000; attempt++) {
            double x = rand.nextGaussian(), y = rand.nextGaussian(), z = rand.nextGaussian(), l = Math.sqrt(x * x + y * y + z * z);
            GeodesicChunkGrid.Direction d = new GeodesicChunkGrid.Direction(x / l, y / l, z / l);
            SpherePosition at = SphereMath.atAltitude(d, 0.0);
            if (TerrainMesh.getLayeredHeight(at, worldNoise) <= seaLevelHeight + 2f) continue;
            float here = floraRanges[s].keep(floraRaw(s, d));
            if (here > most) {
                most = here;
                best = at;
            }
        }
        System.out.println("[DEV] Plant " + s + " (" + speciesConfigs[s].design.kind() + ") thickest found: " + most);
        return best == null ? null : new Viewpoint(best, rand.nextDouble() * Math.PI * 2);
    }

    /** Developer aid: on the shore of a lake of a dozen texels or more, looking across it. */
    private Viewpoint lakeViewpoint(java.util.Random rand) {
        Rivers rivers = worldNoise.rivers;
        if (rivers == null) return null;
        List<Rivers.Lake> big = new ArrayList<>();
        for (Rivers.Lake lake : rivers.lakes()) if (lake.texels.size() >= 12) big.add(lake);
        if (big.isEmpty()) return null;
        Rivers.Lake lake = big.get(rand.nextInt(big.size()));
        double[] c = lake.texels.get(0);
        SpherePosition middle = SphereMath.atAltitude(new GeodesicChunkGrid.Direction(c[0], c[1], c[2]), 0.0);
        // Out from the middle until the ground rises above the water, then a little further
        for (int attempt = 0; attempt < 32; attempt++) {
            double heading = rand.nextDouble() * Math.PI * 2;
            for (double out = 10; out < 2000; out += 10) {
                SpherePosition p = SphereMath.move(middle, heading, out);
                if (TerrainMesh.getLayeredHeight(p, worldNoise) > lake.level + 1f) {
                    return new Viewpoint(SphereMath.move(middle, heading, out + 25), heading + Math.PI);
                }
            }
        }
        return null;
    }

    private static Viewpoint orElse(Viewpoint view, Viewpoint otherwise) {
        return view != null ? view : otherwise;
    }

    /**
     * The time of day: the round starts in daylight where the pod lands, at a random local
     * hour with the sun at least a little way up, which sets where on the planet it's noon.
     * Walk far enough east or west and it's another time of day there.
     */
    private void chooseTimeOfDay(SpherePosition place, java.util.Random rand) {
        double lat = Planet.latitude(place), lon = Planet.longitude(place);
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

    /**
     * Every spawn and teleport comes through here: the eyes go above what's underfoot there,
     * facing the viewpoint's way.
     */
    private void moveToLocation(Viewpoint view) {
        SpherePosition place = SphereMath.atAltitude(view.place(), 0.0);
        double ground = Math.max(seaLevelHeight, groundHeightAt(place));
        camera.setSphereView(SphereMath.atAltitude(place, ground + playerEyeHeight), (float) Math.toDegrees(view.heading()), 0f);
        airborne = false;
        jumpHeight = 0f;
        lastStood = Float.NaN;

        // (what's round the new place is worked out on the builder threads while the loading
        // screen, or the teleport's wait, keeps going)
        updateVisibleChunks(false);
    }

    /**
     * Carries the player from where they were to where the keys took them, in short steps,
     * stopping against anything solid and sliding along it rather than passing through, and
     * not up anything too steep to climb.
     */
    private void moveSolidly(SpherePosition from) {
        SpherePosition to = camera.getSpherePosition();
        double distance = SphereMath.distance(from, to);
        if (distance < 1e-6) return;
        int steps = Math.max(1, (int) Math.ceil(distance / 1.5));
        SpherePosition lastFree = from;
        // (where the feet and head are: raised roads and bridges stop the body beside them)
        float feet = airborne ? airborneEyeY - playerEyeHeight
                : Float.isNaN(lastStood) ? (float) SphereMath.altitude(to) - playerEyeHeight : lastStood;
        float head = feet + playerEyeHeight + 2f;
        boolean startsInRoad = infraManager != null && infraManager.blocksWalker(from, feet, head);
        for (int i = 1; i <= steps; i++) {
            SpherePosition candidate = clearOfPartner(lastFree, SphereMath.slerp(from, to, i / (double) steps), feet);
            // (while swimming, the slope of the bed beneath doesn't matter: only ground that
            // comes up out of the water, where the swimmer would stand, can be too steep)
            boolean floating = swimming && TerrainMesh.getLayeredHeight(candidate, worldNoise) + playerEyeHeight
                    < waterSurfaceHere + SWIM_EYE_HEIGHT;
            // On a bridge the ground's slope beneath (a river's bank) doesn't matter, and what's
            // under it doesn't stand in the way: only what's as high as the feet up on it (its
            // railings)
            float deck = infraManager == null ? Float.NaN : infraManager.bridgeDeckY(candidate);
            float under = TerrainMesh.getLayeredHeight(candidate, worldNoise);
            boolean onBridge = !Float.isNaN(deck) && feet >= deck - 3f && deck > under + 1f;
            double clearance = onBridge ? Math.max(jumpHeight, feet - under) : jumpHeight;
            if (!floating && !onBridge && jumpHeight < 1f && sphereTooSteep(lastFree, candidate, feet)) break;
            SpherePosition resolved = geodesicCollision.resolveFirm(lastFree, candidate, PLAYER_RADIUS, PLAYER, clearance);
            if (geodesicCollision.blockedStatic(resolved, PLAYER_RADIUS * 0.98, clearance)) break;
            if (!startsInRoad && infraManager != null && infraManager.blocksWalker(resolved, feet, head)) break;
            lastFree = resolved;
        }
        // (the eyes keep the height they had)
        lastFree = SphereMath.atAltitude(lastFree, SphereMath.altitude(to));
        if (SphereMath.distance(lastFree, to) > 1e-4) camera.setSpherePosition(lastFree);
    }

    /**
     * Multiplayer (M15): the partner is a solid upright cylinder, PLAYER_RADIUS round, from
     * their feet to 2 above their eyes, where they're shown. A step from from to to that would
     * go into it slides round it instead (to its edge, on the side the step was heading for).
     * Only this player is stopped, never pushed, and nobody is trapped: if they already overlap
     * (after lag, or at a shared spawn) any step that doesn't bring them closer is allowed, and
     * one that does is held back. Passing over their head (a jump that high) is allowed too.
     */
    private SpherePosition clearOfPartner(SpherePosition from, SpherePosition to, float feet) {
        if (partnerBody == null || !partner.hasPose()) return to;
        SpherePosition them = partner.eye();
        double theirFeet = SphereMath.altitude(them) - playerEyeHeight;
        if (feet > theirFeet + playerEyeHeight + 2.0) return to;
        double apart = PLAYER_RADIUS * 2.0;
        double now = SphereMath.distance(from, them), next = SphereMath.distance(to, them);
        if (next >= apart || next >= now) return to;
        if (now < apart) return from;
        // Round the edge of their cylinder rather than into it
        SpherePosition edge = SphereMath.move(them, SphereMath.heading(them, to), apart);
        return SphereMath.atAltitude(edge, SphereMath.altitude(to));
    }

    private boolean sphereTooSteep(SpherePosition at, SpherePosition toward) {
        if (SphereMath.angle(at, toward) < 1e-12) return false;
        double heading = SphereMath.heading(at, toward);
        SpherePosition ahead = SphereMath.move(at, heading, 3.0);
        SpherePosition behind = SphereMath.move(at, heading + Math.PI, 3.0);
        float rise = TerrainMesh.getLayeredHeight(ahead, worldNoise)
                - TerrainMesh.getLayeredHeight(behind, worldNoise);
        return rise / 6f > MAX_CLIMB;
    }

    /**
     * Whether a step is too steep for someone with their feet at feet: only if the ground and
     * what they'd walk on (a road's deck, levelled over the dips and ruts beneath it) are both
     * too steep. A road can run smoothly over ground that's steep under it, and stepping onto a
     * low road is a short rise of its own over ground that's gentle. Along a road itself
     * (ahead and behind both on it) nothing is too steep: what's built to be walked is.
     */
    private boolean sphereTooSteep(SpherePosition at, SpherePosition toward, float feet) {
        if (!sphereTooSteep(at, toward)) return false;
        if (infraManager == null) return true;
        double heading = SphereMath.heading(at, toward);
        SpherePosition ahead = SphereMath.move(at, heading, 3.0);
        SpherePosition behind = SphereMath.move(at, heading + Math.PI, 3.0);
        if (infraManager.isRoadLocation(ahead, 0f) && infraManager.isRoadLocation(behind, 0f)) return false;
        float rise = infraManager.walkingSurfaceY(ahead, feet) - infraManager.walkingSurfaceY(behind, feet);
        return rise / 6f > MAX_CLIMB;
    }

    // Steeper than this (rise over run) can't be walked up
    private static final float MAX_CLIMB = 0.7f;   // about 35 degrees

    // Jumping: Space throws the player up, and gravity brings them down again. While in the
    // air, anything lower than their feet passes beneath them (rocks, low fences, rails).
    private static final float JUMP_SPEED = 42f, GRAVITY = 90f;
    private volatile boolean jumpWanted;
    private boolean airborne;
    private float jumpHeight, jumpVelocity, airborneEyeY;
    // What the player stood on last frame (NaN before any, and after a spawn or teleport)
    private float lastStood = Float.NaN;

    /** Space: jump, if standing on something (not mid-air or swimming). */
    public void jump() {
        // (not where the game mode keeps the player where they landed: it'd serve no purpose)
        if (!GameMode.current().moving) return;
        jumpWanted = true;
    }

    /**
     * The batches' window glass round the viewer, camera-relative: see-through, so drawn after
     * everything solid, without hiding what's behind.
     */
    private void drawWindowGlass(GL3 gl, Matrix4 viewProjection, SpherePosition eye, Vector3 forward, Vector3 sunPos, float[] sunColour,
                                 Matrix4 skyRotation, Texture sky, float reach) {
        glassShader.use(gl);
        glassShader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        glassShader.setVec3(gl, "sphereCamera", new Vector3((float) eye.x(), (float) eye.y(), (float) eye.z()));
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(glassShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        glassShader.setVec3(gl, "viewPos", new Vector3(0f, 0f, 0f));
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
                double dx = obj.centre.x() - eye.x(), dy = obj.centre.y() - eye.y(), dz = obj.centre.z() - eye.z();
                double far = reach + obj.boundingRadius;
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                if (distanceSquared > far * far || !mayBeInView(dx, dy, dz, distanceSquared, obj.boundingRadius, forward)) continue;
                obj.renderTransparent(gl);
            }
        }
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
    }

    /** How steep the ground is at a place: rise over run. */
    private float slopeAt(SpherePosition p) {
        SphereFrame frame = SphereFrame.at(p);
        double d = 3.0;
        float gx = TerrainMesh.getLayeredHeight(frame.toSphere(d, 0.0, 0.0), worldNoise) - TerrainMesh.getLayeredHeight(frame.toSphere(-d, 0.0, 0.0), worldNoise);
        float gz = TerrainMesh.getLayeredHeight(frame.toSphere(0.0, 0.0, d), worldNoise) - TerrainMesh.getLayeredHeight(frame.toSphere(0.0, 0.0, -d), worldNoise);
        return (float) Math.hypot(gx, gz) / (float) (2.0 * d);
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

    /**
     * The altitude of what the player stands on at a place: the land or a road (up on the
     * road's deck where it stands above the ground),
     * or the pod's stairs where they are higher.
     */
    private double groundHeightAt(SpherePosition p) {
        double ground;
        if (infraManager != null) {
            ground = infraManager.walkingSurfaceY(p);
        } else {
            ground = TerrainMesh.getLayeredHeight(p, worldNoise);
        }
        return Math.max(ground, landingPod.floorAt(p));
    }

    /**
     * What the player stands on at a place with their feet at feet: a raised road or bridge
     * only if they're up on it already (or come down onto it), or it's low enough to step onto
     * (see InfrastructureManager.walkingSurfaceY); the pod's stairs where they are higher.
     */
    private double groundHeightAt(SpherePosition p, float feet) {
        double ground = infraManager != null ? infraManager.walkingSurfaceY(p, feet) : TerrainMesh.getLayeredHeight(p, worldNoise);
        return Math.max(ground, landingPod.floorAt(p));
    }

    // The next round: built and waiting for the player, and whether they've asked for it
    private boolean nextRoundBuilt, nextRoundWanted;
    private long nextRoundWantedAt;

    /**
     * Takes up graphics changed in the settings since the last round (how far out the plants,
     * grass, roads and buildings are made, and how thick the grass is), as a round's surroundings
     * are about to be built afresh anyway. What was worked out ahead for the next round is dropped
     * then, as it was made for the old.
     */
    private void applyQuality() {
        GameSettings.Quality quality = GameSettings.quality();
        if (quality == builtQuality) return;
        builtQuality = quality;
        VIEW_DISTANCE = quality.viewDistance;
        GRASS_VIEW_DISTANCE = quality.grassViewDistance;
        GRASS_DENSITY = quality.grassDensity;
        floraStream.clearPrefetch();
        grassStream.clearPrefetch();
        floraStream.setReach((VIEW_DISTANCE + PREFETCH_RINGS) * PHYSICAL_CHUNK_SIZE);
        grassStream.setReach(GRASS_VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE);
        System.out.printf("[GRAPHICS] %s from this round: view %d chunks, grass %d chunks at %.0f%%%n",
                quality.label, VIEW_DISTANCE, GRASS_VIEW_DISTANCE, GRASS_DENSITY * 100f);
    }

    private void startBuildingNextRound(GL3 gl) {
        roundLoading = true;
        setBuilderThreads(true);
        nextRoundBuilt = false;
        roundLoadingStarted = System.currentTimeMillis();
        roundLoadingFrames = 0;
        resetToNextRound(gl);
    }

    public void resetToNextRound(GL3 gl) {
        if (geodesicTerrainModel != null && geodesicTerrainModel.mesh != null) {
            geodesicTerrainModel.mesh.dispose(gl);
        }
        geodesicCollision.clear();
        geodesicTerrainModel = null;
        geodesicTerrainActiveCentre = null;
        if (geodesicTerrainBuild != null) geodesicTerrainBuild.cancel(true);
        geodesicTerrainBuild = null;
        geodesicTerrainBuildCentre = null;
        grassGather = null;
        floraStream.clear();
        grassStream.clear();
        applyQuality();

        infraWantedFor = Long.MIN_VALUE;
        infraWantedFrom = null;
                drawnGrassInstances = 0;
                grassDirty = true;
                // (in multiplayer the round's creatures stand in the same places on both PCs, M19)
                if (Multiplayer.isActive()) organismManager.clear(roundRandom(spawnedRound + 1, CREATURE_DRAWS).nextLong());
                else organismManager.clear();
        if (rockField != null) rockField.clear(gl);
        inhabitants.clear();

        spawnPlayerAtRandomLocation();
        if (rockField != null) rockField.update(gl, camera.getSpherePosition(), true);
    }

    /**
     * While the world is loading (at launch or between rounds), whether this frame's slice of
     * time is spent; nearby chunks always build. Slicing keeps the loading screen moving.
     */
    private boolean overRoundBudget(int ring) {
        // Right round the player is built at once; everything else waits for spare time, a
        // slice of each frame (bigger while loading), so crossing into a new cell never
        // stalls a frame building the whole of the next row
        // (under the landing pod nothing's seen until it's all built, so even what's right round
        // the player waits its turn, and the pod's frames keep coming)
        if (firstFramesDeferred && !worldReady) return System.nanoTime() > roundChunkDeadline;
        if (ring <= 1) return false;
        // (while loading, the next ring too: but not under the landing pod, where it's not seen and the pod's frames matter more)
        if ((roundLoading || (!worldReady && !firstFramesDeferred)) && ring <= 2) return false;
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
    private boolean grassDirty = true;
    private int framesSinceGrassBuffer;
    private boolean grassMovedChunk;
    private int chunkScanCountdown;
    // Terrain chunks are worked out on these threads and only uploaded on the GL thread
    private final java.util.concurrent.ExecutorService terrainBuilders = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2 - 1)), r -> {
                Thread thread = new Thread(r, "terrain-builder");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                return thread;
            });
    // The world's first build gets every spare thread too (but one, so the frames keep coming)
    { setBuilderThreads(true); }
    private static final int PLAY_BUILDERS = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2 - 1));
    private static final int LOADING_BUILDERS = Math.max(PLAY_BUILDERS, Runtime.getRuntime().availableProcessors() - 2);

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


    /**
     * Whether everything in view of where the player now stands is built: the ground, the
     * plants and grass, and the buildings and roads.
     */
    private boolean surroundingsLoadedHere() {
        if (!geodesicTerrainReadyHere() || !floraStream.complete() || !grassStream.complete()) return false;
        if (infraWantedFor != infraGrid.cellAt(camera.getSpherePosition()).id()) return false;
        for (GeodesicChunkGrid.Cell cell : infraWanted) {
            if (!infraCache.containsKey(cell.id())) return false;
        }
        return true;
    }

    private boolean surroundingsBuilt() {
        return surroundingsLoadedHere();
    }

    // Each plant species' range: where it's common, and how many there are along it (SpeciesRange)
    private SpeciesRange[] floraRanges;
    // Points spread evenly over the land (see SpeciesRange.land), for the ranges and the landmarks
    private List<GeodesicChunkGrid.Direction> landPoints = List.of();
    // The most of one tree or shrub species in a chunk's area, even in its heartland
    private static final float FLORA_PEAK_CAP = 2.0f;
    // How many times its peak a plant stands in its famous grove (see Landmarks)
    private static final float GROVE_THICKNESS = 3.0f;

    /** A plant species' abundance before its range is applied, per chunk's area. */
    private float floraRaw(int species, GeodesicChunkGrid.Direction d) {
        SpeciesConfig sc = speciesConfigs[species];
        return sc.abundanceFactor.evaluate(d) * sc.baseAbundance;
    }

    /**
     * A plant species' abundance over the world: suited to its climate, the ground's moisture and
     * its own patches (a steep blend, so species keep apart), in its home region.
     */
    private RegionalFactor plantAbundance(SpeciesConfig sc, RegionalFactor home, float homeStrength) {
        // Blended factor with prominent 50% unique noise allocation for explicit segregation
        RegionalFactor suited = new RegionalFactor.Builder()
            .setWeight(6.0f)        // Drop this to 1.0f so it NEVER artificially blows up low scores
            .setPowerCurve(6.0f)    // Crank the power curve up to 5.0 or 6.0 to make it brutal
            .addFactor(sc.tempFactor, 0.30f)
            .addFactor(this.grassMoistureFactor, 0.20f)
            .addFactor(sc.patchNoiseFactor, 0.50f)
            .build();
        return new RegionalFactor(1.0f, d -> {
            float t = Math.max(0f, Math.min(1f, (home.evaluate(d) - OrganismSpecies.HOME_FROM)
                    / (OrganismSpecies.HOME_TO - OrganismSpecies.HOME_FROM)));
            return suited.evaluate(d) * (1f - homeStrength + homeStrength * t * t * (3f - 2f * t));
        });
    }

    /**
     * Draws a plant species' range again (the temperature it likes, its patches and its home
     * region), the attempt'th time, for one that came out with almost nowhere to grow (see
     * SpeciesRange.findable); its look stays. From the world's seed alone, the same on every PC,
     * and leaving every other map as it was.
     */
    private void redrawPlantRange(int s, int attempt) {
        SpeciesConfig sc = speciesConfigs[s];
        Random r = new Random(worldSeed * 4241L + s * 7717L + attempt * 1_000_003L);
        boolean tree = s < 4;
        sc.tempMean = tree ? 0.1f + r.nextFloat() * 0.6f : 0.4f + r.nextFloat() * 0.5f;
        // (a little more tolerant each time, so a fussy species finds somewhere)
        sc.tempStdDev = (tree ? 0.2f + r.nextFloat() * 0.2f : 0.15f + r.nextFloat() * 0.2f) + attempt * 0.01f;
        long salt = (worldSeed * 17L + s) * 64L + attempt;
        sc.tempFactor = regionalManager.createTemperaturePreference(sc.tempMean, sc.tempStdDev, sc.tempHotStretch);
        sc.patchNoiseFactor = regionalManager.createNoiseMap(sc.patchNoiseScale, salt * 2L);
        RegionalFactor home = regionalManager.createNoiseMap(OrganismSpecies.HOME_SCALE_LOW + r.nextFloat() * OrganismSpecies.HOME_SCALE_RANGE, salt * 2L + 1L);
        sc.abundanceFactor = plantAbundance(sc, home, OrganismSpecies.homeStrength(r));
    }

    private SpeciesRange[] floraRanges(List<GeodesicChunkGrid.Direction> land) {
        SpeciesRange[] ranges = new SpeciesRange[NUM_SPECIES];
        int[] attempts = new int[NUM_SPECIES];
        // (any with almost nowhere to grow has its range drawn again until it has somewhere)
        java.util.stream.IntStream.range(0, NUM_SPECIES).parallel().forEach(s -> {
            while (true) {
                float[] raw = new float[land.size()];
                for (int i = 0; i < raw.length; i++) raw[i] = floraRaw(s, land.get(i));
                ranges[s] = SpeciesRange.of(raw, FLORA_PEAK_CAP);
                if (attempts[s] >= SpeciesRange.MOST_REDRAWS || ranges[s].findable(raw)) break;
                redrawPlantRange(s, ++attempts[s]);
            }
        });
        for (int s = 0; s < NUM_SPECIES; s++) {
            if (attempts[s] > 0) System.out.printf("[FLORA] %s had almost nowhere to grow: its range drawn again %d time(s)%n", speciesConfigs[s].name, attempts[s]);
            System.out.printf(java.util.Locale.ROOT, "[FLORA] %s: heartland from %.4f, peak %.3f a chunk%n",
                    speciesConfigs[s].name, ranges[s].high, ranges[s].peak);
        }
        return ranges;
    }

    /** A geodesic cell's area in chunks' areas (a chunk being PHYSICAL_CHUNK_SIZE square). */
    private float areaInChunks(GeodesicChunkGrid.Cell cell) {
        return (float) (cell.area() * Planet.radius() * Planet.radius() / (PHYSICAL_CHUNK_SIZE * PHYSICAL_CHUNK_SIZE));
    }

    /**
     * One cell's trees and shrubs (safe to work out off the GL thread): candidates for each
     * species at its peak density for the cell's area, each kept where it falls by how common
     * the species is just there (SpeciesRange.keep), so they follow the heatmap closely.
     */
    private List<SphereFlora> buildFloraCell(long id) {
        GeodesicChunkGrid.Cell cell = floraStream.grid().cell(id);
        List<SphereFlora> plants = new ArrayList<>();
        SpherePosition middle = SphereMath.atAltitude(cell.centre(), 0.0);
        float area = areaInChunks(cell);
        float urbanness = settlementManager.getUrbanness(middle);
        for (int s = 0; s < NUM_SPECIES; s++) {
            SpeciesConfig sc = speciesConfigs[s];
            java.util.Random cRand = new java.util.Random(worldSeed ^ (id * 492876847L) ^ ((long) s * 9012431L));
            SpeciesRange range = floraRanges[s];
            double attempts = range.peak * area * (1.0 - URBAN_FLORA_REDUCTION * urbanness);
            int count = (int) attempts;
            if (cRand.nextFloat() < attempts - count) count++;
            // (in a famous grove, three times as many, kept thick all through it)
            int usual = count;
            boolean grove = infraManager.landmarks().groveNear(s, middle, cell.angularRadius() * Planet.radius());
            if (grove) count = (int) Math.ceil(range.peak * area * GROVE_THICKNESS);
            for (int i = 0; i < count; i++) {
                SpherePosition at = SphereMath.atAltitude(cell.point(cRand.nextDouble(), cRand.nextDouble()), 0.0);
                // (every plant draws the same numbers whether or not it's kept, so each is always the same)
                int model = cRand.nextInt(FLORA_VARIATIONS);
                float scale = 0.70f + cRand.nextFloat() * 0.60f;
                float rotation = cRand.nextFloat() * 360.0f;
                float kept = cRand.nextFloat();
                float keep = i < usual ? range.keep(floraRaw(s, SphereMath.direction(at))) : 0f;
                if (grove) keep = Math.max(keep, 0.9f * infraManager.landmarks().groveBoost(s, at));
                if (kept >= keep) continue;
                float ground = TerrainMesh.getLayeredHeight(at, worldNoise);
                if (ground <= seaLevelHeight + 0.1f || slopeAt(at) >= 0.9f || podCovers(at, 8f) || underFreshWater(at, ground)) continue;
                if (infraManager.isClearedForRoad(at, 11.0f, 3.0f) || infraManager.isBuildingLocation(at, 6.0f)) continue;
                plants.add(plant(SphereMath.atAltitude(at, ground), s, model, scale, rotation));
            }
        }
        return plants;
    }

    /** A plant stood at a place: its model matrix, its leaves' colour there and its trunk, worked out once. */
    private SphereFlora plant(SpherePosition position, int species, int model, float scale, float rotation) {
        SpeciesConfig sc = speciesConfigs[species];
        float climate = leafColourFactor.evaluateAt(position);
        Vector3 regional = new Vector3(
                sc.dyingColor.x + (sc.healthyColor.x - sc.dyingColor.x) * climate,
                sc.dyingColor.y + (sc.healthyColor.y - sc.dyingColor.y) * climate,
                sc.dyingColor.z + (sc.healthyColor.z - sc.dyingColor.z) * climate);
        Matrix4 matrix = SphereFrame.at(position, Math.toRadians(rotation)).modelMatrix(scale);
        float trunk = Math.max(0.5f, sc.baseSWidth * 0.5f * scale);
        return new SphereFlora(position, species, model, scale, rotation, matrix, leafDarkColour(regional), leafLightColour(regional), trunk);
    }

    // The grass's steepness is looked up on a lattice this many steps along each edge of its cell
    private static final int GRASS_SLOPE_STEPS = 8;

    /**
     * One cell's grass blades, five floats each {x, y, z (planet-centred), colour, height}
     * (safe to work out off the GL thread): as many as the grass's abundance there (as the map
     * shows it) and the cell's own area call for.
     */
    private float[] buildGrassCell(long id) {
        GeodesicChunkGrid.Cell cell = grassStream.grid().cell(id);
        SpherePosition middle = SphereMath.atAltitude(cell.centre(), 0.0);
        float perChunk = Math.min(MAX_GRASS_LIMIT, Math.round(grassAbundanceFactor.evaluateAt(middle) * GRASS_BASE_ABUNDANCE));
        int attempts = Math.round(perChunk * GRASS_DENSITY * areaInChunks(cell));
        if (attempts <= 0) return new float[0];

        // The ground's steepness over the cell, on a fine lattice of little triangles
        int n = GRASS_SLOPE_STEPS;
        SphereFrame frame = SphereFrame.at(middle);
        double[][] lattice = new double[(n + 1) * (n + 2) / 2][];
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i + j <= n; i++) {
                SpherePosition at = SphereMath.atAltitude(blend(cell, 1.0 - (i + j) / (double) n, i / (double) n, j / (double) n), 0.0);
                double[] local = frame.fromSphere(at);
                lattice[latticeIndex(n, i, j)] = new double[] { local[0], TerrainMesh.getLayeredHeight(at, worldNoise), local[2] };
            }
        }
        // The grass's colour and height at the cell's corners, so each blade's can be blended
        // from them (the corners are shared with the neighbouring cells: no edges between them)
        SpherePosition[] corners = { SphereMath.atAltitude(cell.a(), 0.0), SphereMath.atAltitude(cell.b(), 0.0), SphereMath.atAltitude(cell.c(), 0.0) };
        float[] colourAt = new float[3], heightAt = new float[3];
        for (int k = 0; k < 3; k++) {
            colourAt[k] = grassColourFactor.evaluateAt(corners[k]);
            heightAt[k] = grassHeightFactor.evaluateAt(corners[k]);
        }
        float[] heightSpan = grassHeightSpan(), colourSpan = grassColourSpan();

        float[] blades = new float[attempts * 5];
        int writeIdx = 0;
        // (none on a landmark's steps and platform: only looked for where one's near)
        boolean landmarkNear = infraManager.landmarks().covers(middle, (float) (cell.angularRadius() * Planet.radius()));
        // The terrain mesh's vertex heights, each worked out once for the cell (by terrain cell, row and column)
        java.util.Map<Long, Float> meshHeights = new java.util.HashMap<>();
        for (int i = 0; i < attempts; i++) {
            long bladeSeed = worldSeed ^ (id * 73731703L) ^ ((long) i * 2147483647L);

            double pVal1 = Math.sin(bladeSeed * 12.9898) * 43758.5453123;
            double pVal2 = Math.cos(bladeSeed * 78.2330) * 43758.5453123;
            double pVal3 = Math.sin(bladeSeed * 34.1245) * 54321.1243141;
            double pVal4 = Math.cos(bladeSeed * 95.4321) * 67891.9876543;

            float rand1 = (float)(Math.abs(pVal1) % 1.0);
            float rand2 = (float)(Math.abs(pVal2) % 1.0);
            float rand3 = (float)(Math.abs(pVal3) % 1.0);
            float rand4 = (float)(Math.abs(pVal4) % 1.0);

            // Where in the cell (spread evenly over it, as Cell.point does)
            double root = Math.sqrt(rand1);
            double wa = 1.0 - root, wb = root * (1.0 - rand2), wc = root * rand2;
            GeodesicChunkGrid.Direction direction = blend(cell, wa, wb, wc);
            // (on the ground as it's drawn, so no blade hovers where the mesh's flat triangles
            // cut beneath a hump, nor is buried where they bridge a hollow)
            float ground = TerrainMesh.drawnHeight(direction, worldNoise, meshHeights);

            // Grass thins out on steep ground and gives way to bare rock
            float slope = latticeSlope(lattice, n, wb, wc);
            float keep = 1f - Math.max(0f, Math.min(1f, (slope - 0.6f) / 0.6f));
            boolean onSlope = ((rand1 * 7.31f + rand2 * 3.17f) % 1f) >= keep;
            if (ground <= seaLevelHeight + 0.1f || onSlope || underFreshWater(SphereMath.atAltitude(direction, 0.0), ground)) continue;
            // (a blade is a flat card turned to face the eyes: seen across a slope, its downhill
            // corner would stand clear of the ground, so its root is sunk by about that much)
            SpherePosition at = SphereMath.atAltitude(direction, ground - GRASS_SLOPE_SINK * Math.min(slope, 1.2f));
            if (podCovers(at, -2f)) continue;
            if (infraManager.isClearedForRoad(at, 11.0f, 1.5f)) continue;
            if (landmarkNear && !Float.isNaN(infraManager.landmarks().floorAt(at))) continue;

            // (each stretched over the whole range there is on the planet, so the shortest and
            // tallest, the driest and lushest, are all seen)
            float structuralHeightBase = grassShare(heightSpan, (float) (wa * heightAt[0] + wb * heightAt[1] + wc * heightAt[2]));
            float structuralColourBase = grassShare(colourSpan, (float) (wa * colourAt[0] + wb * colourAt[1] + wc * colourAt[2]));

            float u1 = Math.max(0.0001f, rand3);
            float u2 = rand4;

            float logTerm = (float) Math.sqrt(-2.0 * Math.log(u1));
            float standardNormalColour = (float) (logTerm * Math.cos(2.0 * Math.PI * u2));
            float standardNormalHeight = (float) (logTerm * Math.sin(2.0 * Math.PI * u2));

            float colourStandardDeviation = 0.1f;
            float colourJitter = standardNormalColour * colourStandardDeviation;
            float finalColourPhenotype = Math.max(0.0f, Math.min(1.0f, structuralColourBase + colourJitter));

            float climateHeightTarget = bladeHeight(structuralHeightBase);
            float heightJitter = standardNormalHeight * 0.12f * climateHeightTarget;
            float finalBladeHeight = Math.max(bladeHeight(0f) * 0.6f, climateHeightTarget + heightJitter);

            blades[writeIdx++] = (float) at.x();
            blades[writeIdx++] = (float) at.y();
            blades[writeIdx++] = (float) at.z();
            blades[writeIdx++] = finalColourPhenotype;
            blades[writeIdx++] = finalBladeHeight;
        }
        return writeIdx < blades.length ? java.util.Arrays.copyOf(blades, writeIdx) : blades;
    }

    /** The unit direction at barycentric weights (on a, b and c) in a cell. */
    private static GeodesicChunkGrid.Direction blend(GeodesicChunkGrid.Cell cell, double wa, double wb, double wc) {
        return new GeodesicChunkGrid.Direction(wa * cell.a().x() + wb * cell.b().x() + wc * cell.c().x(),
                wa * cell.a().y() + wb * cell.b().y() + wc * cell.c().y(),
                wa * cell.a().z() + wb * cell.b().z() + wc * cell.c().z()).normalized();
    }

    /** Where lattice point (i steps towards b, j towards c) is kept, n steps along each edge. */
    private static int latticeIndex(int n, int i, int j) {
        return j * (n + 1) - j * (j - 1) / 2 + i;
    }

    /** The steepness (rise over run) of the lattice triangle holding barycentric weights wb, wc. */
    private static float latticeSlope(double[][] lattice, int n, double wb, double wc) {
        double fb = wb * n, fc = wc * n;
        int i = Math.min(n - 1, (int) fb), j = Math.min(n - 1 - i, (int) fc);
        boolean upper = (fb - i) + (fc - j) > 1.0 && i + j <= n - 2;
        double[] p0, p1, p2;
        if (upper) {
            p0 = lattice[latticeIndex(n, i + 1, j)];
            p1 = lattice[latticeIndex(n, i + 1, j + 1)];
            p2 = lattice[latticeIndex(n, i, j + 1)];
        } else {
            p0 = lattice[latticeIndex(n, i, j)];
            p1 = lattice[latticeIndex(n, i + 1, j)];
            p2 = lattice[latticeIndex(n, i, j + 1)];
        }
        double ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2];
        double vx = p2[0] - p0[0], vy = p2[1] - p0[1], vz = p2[2] - p0[2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        return Math.abs(ny) < 1e-9 ? 99f : (float) (Math.hypot(nx, nz) / Math.abs(ny));
    }

    private java.util.concurrent.Future<Object[]> grassGather;

    /** Puts gathered grass blades (count of them, five floats each) into the instance buffer. */
    private void uploadGrass(float[] data, int count) {
        int floats = count * 5;
        if (persistentGrassBuffer == null || floats > currentGrassGPUCapacityFloats) {
            currentGrassGPUCapacityFloats = (int) (Math.max(floats, 5) * 1.2f);
            persistentGrassBuffer = com.jogamp.common.nio.Buffers.newDirectFloatBuffer(currentGrassGPUCapacityFloats);
            gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassInstanceVBO);
            gl.glBufferData(GL3.GL_ARRAY_BUFFER, currentGrassGPUCapacityFloats * 4L, null, GL3.GL_DYNAMIC_DRAW);
        }
        persistentGrassBuffer.clear();
        persistentGrassBuffer.put(data, 0, floats);
        persistentGrassBuffer.flip();
        gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassInstanceVBO);
        gl.glBufferSubData(GL3.GL_ARRAY_BUFFER, 0, floats * 4L, persistentGrassBuffer);
        gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, 0);
        drawnGrassInstances = count;
    }

    /** The angle round its centre that a terrain or plant patch built at a place reaches. */
    private double geodesicPatchRadius(SpherePosition centre) {
        return Math.min(Math.PI, FAR_PLANE / Planet.radius() + 3.0 * geodesicTerrainGrid.cellAt(centre).angularRadius());
    }

    /** Whether a patch built round centre still reaches far enough round a place (it is rebuilt a third of the way out). */
    private boolean geodesicPatchCovers(SpherePosition centre, SpherePosition here) {
        return centre != null && SphereMath.angle(centre, here) <= geodesicPatchRadius(here) * 0.35;
    }

    /**
     * Whether the sphere's ground has been built round where the player now is. Nothing is
     * drawn until it has (the world is only ever drawn on the sphere), so the loading screen
     * stays up until then at launch, between rounds and after a teleport.
     */
    private boolean geodesicTerrainReadyHere() {
        return geodesicTerrainModel != null
                && geodesicPatchCovers(geodesicTerrainActiveCentre, camera.getSpherePosition());
    }

    private void updateGeodesicTerrain(GL3 gl) {
        if (geodesicTerrainBuild != null && geodesicTerrainBuild.isDone()) {
            try {
                Object[] data = geodesicTerrainBuild.get();
                Mesh mesh = new Mesh(gl, (float[]) data[0], (int[]) data[1]);
                Model replacement = new Model("geodesic_terrain", mesh, globalModelMatrix, terrainShader,
                        terrainMaterial, terrainRenderer, camera);
                if (geodesicTerrainModel != null && geodesicTerrainModel.mesh != null) {
                    geodesicTerrainModel.mesh.dispose(gl);
                }
                geodesicTerrainModel = replacement;
                geodesicTerrainActiveCentre = geodesicTerrainBuildCentre;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while building geodesic terrain: " + e.getMessage());
            } catch (java.util.concurrent.ExecutionException e) {
                System.err.println("Could not build geodesic terrain: " + e.getCause());
                geodesicTerrainRetryAfterNanos = System.nanoTime() + 1_000_000_000L;
            } finally {
                geodesicTerrainBuild = null;
                geodesicTerrainBuildCentre = null;
            }
        }

        if (geodesicTerrainBuild != null) return;
        if (System.nanoTime() < geodesicTerrainRetryAfterNanos) return;
        SpherePosition current = camera.getSpherePosition();
        if (geodesicTerrainModel != null && geodesicPatchCovers(geodesicTerrainActiveCentre, current)) return;

        geodesicTerrainBuildCentre = current;
        double angularRadius = geodesicPatchRadius(current);
        geodesicTerrainBuild = terrainBuilders.submit(() ->
                TerrainMesh.buildGeodesicPatchData(geodesicTerrainGrid, current, angularRadius,
                        GEODESIC_TERRAIN_SEGMENTS, worldNoise));
    }

    /**
     * Whether a ball (its centre dx, dy, dz from the eyes, of a radius) may be in view: within the
     * cone round the way the camera looks that holds the whole picture, corners and all.
     */
    private boolean mayBeInView(double dx, double dy, double dz, double distanceSquared, double radius, Vector3 forward) {
        if (distanceSquared <= radius * radius) return true;
        double distance = Math.sqrt(distanceSquared);
        double along = (dx * forward.x + dy * forward.y + dz * forward.z) / distance;
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, along)));
        return angle - Math.asin(radius / distance) <= viewConeHalfAngle();
    }

    /** Half the angle across the picture's diagonal (the perspective is 45 degrees up and down), a little over. */
    private double viewConeHalfAngle() {
        double up = Math.tan(fieldOfView() / 2.0), across = up * currentWidth / Math.max(1, currentHeight);
        return Math.atan(Math.hypot(up, across)) + Math.toRadians(3.0);
    }

    // The building batches in view this frame, nearest first (see renderGeodesicInfrastructure)
    private final List<InfrastructureObject> visibleBatches = new ArrayList<>();

    private void renderGeodesicInfrastructure(GL3 gl, Matrix4 skyRotation) {
        SpherePosition eye = camera.getSpherePosition();
        Vector3 forward = camera.getSphereForwardDirection();
        float drawDistance = Math.max(VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE, FAR_PLANE);
        List<InfrastructureObject> signs = new ArrayList<>();
        visibleBatches.clear();
        for (List<InfrastructureObject> objects : infraCache.values()) {
            for (InfrastructureObject object : objects) {
                if (object.type == InfrastructureObject.Type.SIGN) {
                    signs.add(object);
                    continue;
                }
                if (object.type != InfrastructureObject.Type.BATCH) continue;
                double dx = object.centre.x() - eye.x();
                double dy = object.centre.y() - eye.y();
                double dz = object.centre.z() - eye.z();
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                double radius = object.boundingRadius;
                if (distanceSquared > (drawDistance + radius) * (drawDistance + radius)) continue;
                if (!mayBeInView(dx, dy, dz, distanceSquared, radius, forward)) continue;
                object.setViewerDistanceSquared((float) distanceSquared);
                visibleBatches.add(object);
            }
        }
        // Nearest first, so what's hidden behind them fails the depth test before it's shaded
        visibleBatches.sort((a, b) -> Float.compare(a.viewerDistanceSquared(), b.viewerDistanceSquared()));
        for (InfrastructureObject object : visibleBatches) {
            object.renderBatch(gl, ambientLight, nightProportion,
                    solidShader, terrainShader, terrainRenderer, camera);
        }
        renderSigns(gl, signs, eye, forward, skyRotation);
    }

    /** The signs near the viewer, each drawn on the sphere through its own frame. */
    private void renderSigns(GL3 gl, List<InfrastructureObject> signs, SpherePosition eye, Vector3 forward, Matrix4 skyRotation) {
        if (signs.isEmpty()) return;
        signboardShader.use(gl);
        signboardShader.setFloat(gl, "bend", 0f);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(signboardShader.getID(), "skyRotation"), 1, false,
                skyRotation.toFloatArrayForGLSL(), 0);
        if (textures.get("sky") != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            textures.get("sky").bind(gl);
            signboardShader.setInt(gl, "skyTexture", 2);
        }
        for (InfrastructureObject sign : signs) {
            // (culled round the middle of what's drawn: up its frame's local y)
            SpherePosition middle = sign.frame.toWorld(0.0, sign.drawnCentreY(), 0.0);
            double dx = middle.x() - eye.x(), dy = middle.y() - eye.y(), dz = middle.z() - eye.z();
            double reach = SIGN_DRAW_DISTANCE + sign.drawnRadius();
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            if (dx * forward.x + dy * forward.y + dz * forward.z < -sign.drawnRadius()) continue;
            sign.render(gl, ambientLight, nightProportion, signModelsByNation, postModelsByNation,
                    nationAtlases.get(sign.nationId), nationAtlasSizes.getOrDefault(sign.nationId, 1),
                    nationDirections.getOrDefault(sign.nationId, 0),
                    textures.get(InfrastructureManager.nationTextureName("flag", sign.nationId)),
                    peoplePictures != null && sign.nationId > 0 && sign.nationId < peoplePictures.length ? peoplePictures[sign.nationId] : null);
        }
    }

    /**
     * The sea: the grid laid along the ground round the viewer (out to SEA_GRID_REACH), wrapped
     * onto the sea's sphere and waved by vs_sphere_water; wherever the land is higher it hides
     * the water, so the coast is where the sea's surface meets the ground. Shallows let the
     * ground show through, by how much water lies over it (the depth pre-pass's terrain).
     */
    /** The height of a river's or lake's water at a place, if it's in one (NaN if not). */
    private float freshWaterAt(SpherePosition place) {
        Rivers rivers = worldNoise.rivers;
        if (rivers == null) return Float.NaN;
        return rivers.waterAt(SphereMath.direction(place), TerrainMesh.getLayeredHeight(place, worldNoise));
    }

    /** Whether a place's ground is under a river's or lake's water. */
    private boolean underFreshWater(SpherePosition place, float ground) {
        Rivers rivers = worldNoise.rivers;
        return rivers != null && !Float.isNaN(rivers.waterAt(SphereMath.direction(place), ground));
    }

    /** The rivers and lakes, once their meshes are made: after the sea, see-through like it. */
    private void renderRivers(GL3 gl, Matrix4 skyRotation, Texture sky, float time) {
        if (!riverRenderer.ready()) {
            if (riverBuild == null || !riverBuild.isDone()) return;
            try {
                riverRenderer.upload(gl, riverBuild.get());
            } catch (Exception e) {
                System.err.println("Could not make the rivers' meshes: " + e);
                riverBuild = null;
                return;
            }
        }
        SpherePosition eye = camera.getSpherePosition();
        Vector3 forward = camera.getSphereForwardDirection();
        Vector3 sunDirection = SphereRender.sunDirection(eye, Planet.sunDirection(eye, sunDeclination, noonLongitude));
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        // (writing depth, so where pieces overlap - a lake's patches, a bend - the water isn't laid on twice;
        // drawn a touch nearer than the ground it lies on)
        gl.glDepthMask(true);
        gl.glEnable(GL.GL_POLYGON_OFFSET_FILL);
        gl.glPolygonOffset(-1f, -4f);
        Shader s = riverShader;
        s.use(gl);
        s.setFloatArray(gl, "viewProjection", camera.getSphereViewProjection().toFloatArrayForGLSL());
        s.setVec3(gl, "sphereCamera", new Vector3((float) eye.x(), (float) eye.y(), (float) eye.z()));
        s.setFloat(gl, "time", time);
        s.setVec2(gl, "windowSize", new Vector2((float) currentWidth, (float) currentHeight));
        s.setFloat(gl, "nearPlane", currentNearPlane);
        s.setFloat(gl, "farPlane", FAR_PLANE);
        s.setVec3(gl, "sunDirection", sunDirection);
        float[] sunTint = worldArt.palette().sunTint;
        s.setVec3(gl, "lightSpecular", new Vector3(sunTint[0], sunTint[1], sunTint[2]));
        float[] shallow = worldArt.palette().seaShallowTint();
        float[] deep = worldArt.palette().seaDeepOcean();
        s.setVec3(gl, "u_ShallowTint", new Vector3(shallow[0], shallow[1], shallow[2]));
        s.setVec3(gl, "u_DeepOceanColour", new Vector3(deep[0], deep[1], deep[2]));
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(s.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            s.setInt(gl, "skyTexture", 2);
        }
        gl.glActiveTexture(GL3.GL_TEXTURE1);
        gl.glBindTexture(GL3.GL_TEXTURE_2D, depthTexture[0]);
        s.setInt(gl, "terrainDepthTexture", 1);
        gl.glActiveTexture(GL3.GL_TEXTURE0);
        textures.get("water_diffuse").bind(gl);
        s.setInt(gl, "diffuse_texture", 0);
        riverRenderer.render(gl, eye, FAR_PLANE, (dx, dy, dz, d2, r) -> mayBeInView(dx, dy, dz, d2, r, forward));
        gl.glDisable(GL.GL_POLYGON_OFFSET_FILL);
        gl.glDisable(GL.GL_BLEND);
        gl.glEnable(GL.GL_CULL_FACE);
    }

    private void renderSea(GL3 gl, Matrix4 skyRotation, Texture sky, float time) {
        if (geodesicTerrainModel == null || seaGrid == null) return;
        SpherePosition eye = camera.getSpherePosition();
        SphereFrame here = SphereFrame.at(eye);
        Vector3 sunDirection = SphereRender.sunDirection(eye, Planet.sunDirection(eye, sunDeclination, noonLongitude));

        gl.glEnable(GL.GL_DEPTH_TEST);
        // Wave crests can be seen from either side
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(true);   // (writing its depth, as the old water plane did, so a far crest never draws over a nearer one)
        Shader s = sphereWaterShader;
        s.use(gl);
        s.setFloatArray(gl, "viewProjection", camera.getSphereViewProjection().toFloatArrayForGLSL());
        s.setVec3(gl, "sphereCamera", new Vector3((float) eye.x(), (float) eye.y(), (float) eye.z()));
        s.setVec3(gl, "centreUp", vector(here.up()));
        s.setVec3(gl, "centreEast", vector(here.xAxis()));
        s.setVec3(gl, "centreSouth", vector(here.zAxis()));
        s.setFloat(gl, "eyeAboveSea", (float) (SphereMath.altitude(eye) - seaLevelHeight));
        s.setFloat(gl, "gridSize", SEA_GRID_REACH * 2f);
        s.setFloat(gl, "planetRadius", (float) Planet.radius());
        s.setFloat(gl, "seaLevel", seaLevelHeight);
        s.setFloat(gl, "time", time);
        s.setFloat(gl, "waveTexel", regionalManager.waveFieldTexel());
        gl.glActiveTexture(GL3.GL_TEXTURE0 + Shader.WAVE_UNIT);
        gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, waveFieldTexture);

        s.setVec2(gl, "windowSize", new Vector2((float) currentWidth, (float) currentHeight));
        s.setFloat(gl, "nearPlane", currentNearPlane);
        s.setFloat(gl, "farPlane", FAR_PLANE);
        s.setVec3(gl, "sunDirection", sunDirection);
        // The glint on the water is the sun's own colour
        float[] sunTint = worldArt.palette().sunTint;
        s.setVec3(gl, "lightSpecular", new Vector3(sunTint[0], sunTint[1], sunTint[2]));
        s.setVec3(gl, "matSpecular", waterMaterial.getSpecular());
        s.setFloat(gl, "matShininess", waterMaterial.getShininess());
        float[] shallow = worldArt.palette().seaShallowTint();
        float[] deepOcean = worldArt.palette().seaDeepOcean();
        s.setVec3(gl, "u_ShallowTint", new Vector3(shallow[0], shallow[1], shallow[2]));
        s.setVec3(gl, "u_DeepOceanColour", new Vector3(deepOcean[0], deepOcean[1], deepOcean[2]));
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(s.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        if (sky != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            sky.bind(gl);
            s.setInt(gl, "skyTexture", 2);
        }
        gl.glActiveTexture(GL3.GL_TEXTURE1);
        gl.glBindTexture(GL3.GL_TEXTURE_2D, depthTexture[0]);
        s.setInt(gl, "terrainDepthTexture", 1);
        gl.glActiveTexture(GL3.GL_TEXTURE0);
        textures.get("water_diffuse").bind(gl);
        s.setInt(gl, "diffuse_texture", 0);

        seaGrid.render(gl);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
        gl.glEnable(GL.GL_CULL_FACE);
    }

    private static Vector3 vector(double[] v) {
        return new Vector3((float) v[0], (float) v[1], (float) v[2]);
    }

    /**
     * The trees and shrubs in view, drawn relative to the camera: all the branches, then all
     * the leaves, so the GPU switches shader twice per frame rather than twice per tree.
     */
    private void renderFlora(GL3 gl, Matrix4 skyRotation) {
        SpherePosition eye = camera.getSpherePosition();
        Vector3 forward = camera.getSphereForwardDirection();
        float reach = VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE;
        visibleFlora.clear();
        visibleLods.clear();
        for (List<SphereFlora> plants : floraStream.ready().values()) {
            for (SphereFlora plant : plants) {
                SpherePosition position = plant.position();
                double dx = position.x() - eye.x(), dy = position.y() - eye.y(), dz = position.z() - eye.z();
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                if (distanceSquared > reach * reach) continue;
                float radius = FLORA_CULL_RADIUS * plant.scale();
                if (!mayBeInView(dx, dy, dz, distanceSquared, radius, forward)) continue;
                visibleFlora.add(plant);
                // (as near as it looks: zoomed in, as detailed as if it were that much nearer)
                double seen = distanceSquared / (zoom * zoom);
                visibleLods.add(seen > 135.0 * 135.0 ? 2 : (seen > 65.0 * 65.0 ? 1 : 0));
            }
        }
        floraTriangles = 0;
        if (visibleFlora.isEmpty()) return;
        gl.glDisable(GL.GL_CULL_FACE);
        floraShader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(floraShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        for (int i = 0; i < visibleFlora.size(); i++) {
            SphereFlora plant = visibleFlora.get(i);
            floraBodyUniforms(gl, speciesConfigs[plant.speciesIndex()], plant.leafLight());
            Model branch = floraBranchModelsLOD[plant.speciesIndex()][visibleLods.get(i)][plant.modelIndex()];
            branch.setModelMatrix(plant.model());
            branch.render(gl, ambientLight, nightProportion);
            floraTriangles += branch.mesh.getIndexCount() / 3;
        }
        leafShader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(leafShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
        if (textures.get("sky") != null) {
            gl.glActiveTexture(GL3.GL_TEXTURE2);
            textures.get("sky").bind(gl);
            leafShader.setInt(gl, "skyTexture", 2);
        }
        for (int i = 0; i < visibleFlora.size(); i++) {
            SphereFlora plant = visibleFlora.get(i);
            leafShader.setVec3(gl, "u_LeafDarkColor", plant.leafDark());
            leafShader.setVec3(gl, "u_LeafLightColor", plant.leafLight());
            Model leaves = floraLeafModelsLOD[plant.speciesIndex()][visibleLods.get(i)][plant.modelIndex()];
            leaves.setModelMatrix(plant.model());
            leaves.render(gl, ambientLight, nightProportion);
            floraTriangles += leaves.mesh.getIndexCount() / 3;
        }
        gl.glEnable(GL.GL_CULL_FACE);
    }

    private final List<Integer> visibleLods = new ArrayList<>();
    // The plants' triangles drawn last frame (for -Dxenoguesser.fpslog)
    private long floraTriangles;

    private void updateVisibleChunks(boolean forceImmediate) {
        boolean loadingNow = roundLoading || teleportLoading || !worldReady;
        // (the world's first load under the landing pod takes a smaller slice, so the pod's frames keep coming)
        long budget = !loadingNow ? PLAY_CHUNK_BUDGET_NANOS : firstFramesDeferred && !worldReady ? POD_LOADING_BUDGET_NANOS : ROUND_LOADING_BUDGET_NANOS;
        roundChunkDeadline = System.nanoTime() + budget;
        infraManager.prepareRoadNetwork(seaLevelHeight, worldNoise);
        boolean pending = false;

        // --- TERRAIN: the sphere's patch round the player (built off the GL thread, put up here)
        updateGeodesicTerrain(gl);

        // --- TREES AND GRASS: per geodesic cell round the player
        updateVegetation(forceImmediate);

        // --- ROADS AND BUILDINGS: per geodesic cell round the player
        SpherePosition here = camera.getSpherePosition();
        GeodesicChunkGrid.Cell hereCell = infraGrid.cellAt(here);
        if (hereCell.id() != infraWantedFor) {
            wantInfrastructureAround(here, hereCell);
        }

        // Buildings and roads are quick to make (a few milliseconds a cell in a dense town), so
        // they get a slice of the frame of their own rather than whatever the terrain and plants leave over;
        // otherwise, walking on, towns ahead would only appear once already close
        long infraDeadline = System.nanoTime() + INFRA_CHUNK_BUDGET_NANOS;
        for (GeodesicChunkGrid.Cell cell : infraWanted) {
            if (infraCache.containsKey(cell.id())) continue;
            // (how many chunks' widths out from the player its nearest edge is)
            double edge = SphereMath.angle(SphereMath.direction(here), cell.centre()) - cell.angularRadius();
            int ring = (int) (Math.max(0.0, edge) * Planet.radius() / PHYSICAL_CHUNK_SIZE);
            if (overRoundBudget(ring) && System.nanoTime() > infraDeadline) {
                pending = true;
                continue;
            }
            infraCache.put(cell.id(), infraManager.generateForCell(
                    cell.id(), seaLevelHeight, worldNoise));
        }
        chunkWorkPending = pending;
    }

    /**
     * The cells whose roads and buildings are wanted round the player, in the cell given:
     * within the view (and a ring beyond, ready ahead of time), nearest first and those the way
     * the player has been going before those behind. Cells left well behind are dropped.
     */
    private void wantInfrastructureAround(SpherePosition here, GeodesicChunkGrid.Cell hereCell) {
        GeodesicChunkGrid.Direction at = SphereMath.direction(here);
        double reach = (VIEW_DISTANCE + PREFETCH_RINGS) * PHYSICAL_CHUNK_SIZE / Planet.radius();
        // Which way the player has gone since the cells were last worked out
        double wx = 0.0, wy = 0.0, wz = 0.0;
        if (infraWantedFrom != null) {
            wx = here.x() - infraWantedFrom.x();
            wy = here.y() - infraWantedFrom.y();
            wz = here.z() - infraWantedFrom.z();
            double length = Math.sqrt(wx * wx + wy * wy + wz * wz);
            // (a teleport or a new round is no direction)
            if (length > 0.0 && length < 50.0 * PHYSICAL_CHUNK_SIZE) {
                wx /= length;
                wy /= length;
                wz /= length;
            } else {
                wx = wy = wz = 0.0;
            }
        }
        List<GeodesicChunkGrid.Cell> cells = infraGrid.cellsWithin(at, reach);
        Map<Long, Double> priority = new HashMap<>();
        for (GeodesicChunkGrid.Cell cell : cells) {
            double angle = SphereMath.angle(at, cell.centre());
            double ring = angle * Planet.radius() / PHYSICAL_CHUNK_SIZE;
            GeodesicChunkGrid.Direction c = cell.centre();
            double ax = c.x() - at.x(), ay = c.y() - at.y(), az = c.z() - at.z();
            double length = Math.sqrt(ax * ax + ay * ay + az * az);
            double ahead = length > 1e-12 ? (ax * wx + ay * wy + az * wz) / length : 0.0;
            priority.put(cell.id(), ring - ahead * 2.5);
        }
        cells.sort(java.util.Comparator.comparingDouble(cell -> priority.get(cell.id())));
        infraWanted = cells;
        infraWantedFor = hereCell.id();
        infraWantedFrom = here;

        double keep = reach + 2.0 * hereCell.angularRadius();
        Iterator<Map.Entry<Long, List<InfrastructureObject>>> it = infraCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, List<InfrastructureObject>> entry = it.next();
            if (SphereMath.angle(at, infraGrid.cell(entry.getKey()).centre()) <= keep) continue;
            for (InfrastructureObject obj : entry.getValue()) obj.dispose(gl);
            it.remove();
        }
    }


    /**
     * Brings the trees and grass round the player up to date (worked out on the builder
     * threads), and gathers the grass into the instance buffer, thinned with distance, whenever
     * what's there has changed.
     */
    private void updateVegetation(boolean all) {
        SpherePosition here = camera.getSpherePosition();
        // (under the landing pod, even the plants right round the player are made on the builders)
        boolean underPod = firstFramesDeferred && !worldReady;
        floraStream.setNearAtOnce(!underPod);
        grassStream.setNearAtOnce(!underPod);
        floraStream.update(here, terrainBuilders, all);
        if (grassStream.update(here, terrainBuilders, all)) grassDirty = true;

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
        framesSinceGrassBuffer++;
        if (!grassDirty || grassGather != null) return;
        if (!(all || grassMovedChunk || drawnGrassInstances == 0 || grassStream.complete() || framesSinceGrassBuffer > 20)) return;
        grassDirty = false;
        grassMovedChunk = false;
        framesSinceGrassBuffer = 0;
        List<float[]> cells = new ArrayList<>();
        List<Integer> strides = new ArrayList<>();
        int required = 0;
        for (Map.Entry<Long, float[]> entry : grassStream.ready().entrySet()) {
            float[] data = entry.getValue();
            if (data.length == 0) continue;
            // Far grass is thinned: it shrinks below a pixel and fades into fog, but drawing
            // every blade out to the horizon was the costliest pass
            // (as near as it looks: zoomed in, thinned as if it were that much nearer)
            int ring = (int) (SphereMath.distance(here, SphereMath.atAltitude(grassStream.grid().cell(entry.getKey()).centre(), 0.0)) / PHYSICAL_CHUNK_SIZE / zoom);
            int stride = ring <= 4 ? 1 : ring <= 8 ? 2 : ring <= 14 ? 4 : 8;
            cells.add(data);
            strides.add(stride);
            required += (data.length / 5 + stride - 1) / stride * 5;
        }
        int size = required;
        java.util.concurrent.Callable<Object[]> gather = () -> {
            float[] staging = new float[size];
            int staged = 0, written = 0;
            for (int i = 0; i < cells.size(); i++) {
                float[] data = cells.get(i);
                int stride = strides.get(i);
                for (int blade = 0; blade * 5 < data.length; blade += stride) {
                    System.arraycopy(data, blade * 5, staging, staged, 5);
                    staged += 5;
                    written++;
                }
            }
            return new Object[] { staging, written };
        };
        // (straight away when there's none yet; but under the landing pod, on a worker, so its frames keep coming)
        if (all || (drawnGrassInstances == 0 && !(firstFramesDeferred && !worldReady))) {
            try {
                Object[] gathered = gather.call();
                uploadGrass((float[]) gathered[0], (Integer) gathered[1]);
            } catch (Exception ignored) {
            }
        } else {
            grassGather = terrainBuilders.submit(gather);
        }
    }

    // When this round began (getSeconds()): in multiplayer the clouds drift with the time since
    // then rather than since the window opened, so both PCs' skies match (M19)
    private double roundClockStart;

    /** Multiplayer: whether the partner's PC has built the round this one has built (always true in singleplayer). */
    private boolean partnerHasBuilt() {
        Multiplayer session = Multiplayer.active();
        return session == null || session.remoteRoundBuilt(spawnedRound);
    }

    /** The round has begun for the player (rounds begin together on both PCs, M16). */
    public void startRoundClock() {
        roundClockStart = getSeconds();
        // (and the round's creatures placed afresh, from where their ranges put them)
        if (organismManager != null) organismManager.respawn();
    }

    private void render() {

        double elapsedTime = getSeconds() - startTime;
        float cloudTime = (float) (Multiplayer.isActive() ? getSeconds() - roundClockStart : elapsedTime);
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
            // Developer aid: -Dxenoguesser.fpslog logs the rate, and the plants and their triangles drawn
            if (Dev.flag("xenoguesser.fpslog")) {
                System.out.printf(java.util.Locale.ROOT, "[FPS] %.0f, %d plants drawn, %d thousand triangles in them%n", measuredFps, visibleFlora.size(), floraTriangles / 1000);
            }
        }

        // Movement uses a capped step so a long hitch doesn't fling the camera
        if (deltaTime > 0.1) { deltaTime = 1.0 / 60.0; }

        boolean moveW = keyboard.w;
        boolean moveS = keyboard.s;
        boolean moveA = keyboard.a;
        boolean moveD = keyboard.d;

        if (moveW && moveS) { moveW = false; moveS = false; }
        // (in the game modes where the player stays where they landed, they can't walk)
        if (!GameMode.current().moving) { moveW = false; moveA = false; moveS = false; moveD = false; }
        if (moveA && moveD) { moveA = false; moveD = false; }
        boolean walking = moveW || moveA || moveS || moveD;
        boolean revealing = minimap != null && minimap.isFullScreenRevealMode();
        boolean sprinting = updateStamina(deltaTime, walking && !revealing && keyboard.shift);
        updateOxygen(deltaTime, sprinting, elapsedTime);

        SpherePosition sphereBeforeMove = camera.getSpherePosition();
        if (revealing) {
            camera.updatePosition(false, false, false, false, (float)deltaTime);
        } else {
            camera.updatePosition(moveW, moveA, moveS, moveD, (float)deltaTime, sprinting ? SPRINT_SPEED : 1f);
        }

        if (!menuShotHold) {
            moveSolidly(sphereBeforeMove);
        }
        settleClear();
        easeZoom(deltaTime);
        // (the eyes' height less last frame's step bob, so the easing after the ground never sees it)
        float currentAltitude = camera.getAltitude() - headBob;
        // What's underfoot: the ground, a road's deck (if they're up on it) or the pod's stairs
        // Where the feet are: in the air, under the jump; on foot, on what they stood on last frame
        // (not under the eyes' eased height, which lags on a steep bridge or ramp far enough for
        // the deck to seem out of reach, dropping the player through it to the ground beneath)
        float feet = airborne ? airborneEyeY - playerEyeHeight : (Float.isNaN(lastStood) ? currentAltitude - playerEyeHeight : lastStood);
        float rawGroundHeight = (float) groundHeightAt(camera.getSpherePosition(), feet);
        lastStood = rawGroundHeight;
        float targetCameraHeight = rawGroundHeight + playerEyeHeight;

        // Too deep to stand: swim, eyes just above the water
        waterSurfaceHere = regionalManager.waveSurfaceAt(camera.getSpherePosition(), (float) elapsedTime, seaLevelHeight);
        // (or a river's or lake's, where that's higher)
        float freshWater = freshWaterAt(camera.getSpherePosition());
        if (!Float.isNaN(freshWater)) waterSurfaceHere = Math.max(waterSurfaceHere, freshWater);
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
                airborneEyeY = currentAltitude;
            }
        }
        updateHeadBob(deltaTime, walking && !revealing && !menuShotHold && !airborne && !swimming, sprinting);
        if (airborne) {
            // A ballistic arc through the air, whatever the ground does beneath: it lands when
            // it comes down to the ground (going up a slope, sooner; off a drop, later)
            float dt = (float) deltaTime;
            // (no stepping in the air: the bob starts again from nothing on landing)
            headBob = 0f;
            bobWeight = 0f;
            airborneEyeY += (jumpVelocity - GRAVITY * dt * 0.5f) * dt;
            jumpVelocity -= GRAVITY * dt;
            jumpHeight = Math.max(0f, airborneEyeY - targetCameraHeight);
            if ((jumpVelocity <= 0f && airborneEyeY <= targetCameraHeight) || swimming) {
                jumpHeight = 0f;
                jumpVelocity = 0f;
                airborne = false;
                camera.setAltitude(Math.max(airborneEyeY, targetCameraHeight - 0.5f));
            } else {
                camera.setAltitude(Math.max(airborneEyeY, targetCameraHeight));
            }
        } else {
            float smoothedHeight = currentAltitude + (targetCameraHeight - currentAltitude) * dynamicSmoothingFactor;
            camera.setAltitude(smoothedHeight + headBob);
        }

        SpherePosition eye = camera.getSpherePosition();
        // The sun's direction in the local frame here, and in the world
        float[] sunDir = Planet.sunDirection(eye, sunDeclination, noonLongitude);
        Vector3 sunDirection = SphereRender.sunDirection(eye, sunDir);
        lights[0].setPosition(SphereRender.sunPoint(sunDirection));

        // The buildings, roads, trees and grass come and go with the geodesic cell the player is in
        boolean movedChunk = infraGrid.cellAt(eye).id() != infraWantedFor;
        long vegetationCell = grassStream.grid().cellAt(eye).id();
        if (vegetationCell != lastVegetationCell) {
            lastVegetationCell = vegetationCell;
            grassDirty = true;   // the grass thins by distance from the player
            grassMovedChunk = true;
            movedChunk = true;
        }
        chunkScanCountdown--;
        // Waiting on work in the background: look in on it every few frames rather than rescanning every frame
        boolean waiting = chunkWorkPending || !floraStream.complete() || !grassStream.complete() || grassDirty || grassGather != null;
        if (movedChunk || (chunkScanCountdown <= 0 && waiting)) {
            chunkScanCountdown = 3;
            updateVisibleChunks(false);
        }

        // Night falls as the sun sinks: full day above 18 degrees, full night once it's 6 below
        float sunElevation = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, sunDir[1]))));
        nightProportion = Math.max(0f, Math.min(1f, (18f - sunElevation) / 24f));

        float dayR = DAY_AMBIENT[0], dayG = DAY_AMBIENT[1], dayB = DAY_AMBIENT[2];
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

        // The creatures and people moved on first, so their shadows are cast from where they're
        // drawn this frame (a frame behind, a moving vehicle's shadow fell on its rider's back)
        // (the herds walk paths fixed by the round's clock: see OrganismManager.Herd)
        organismManager.update((float) deltaTime, eye, getSeconds() - roundClockStart);
        inhabitants.update((float) deltaTime, eye);

        // --- PASS 0: THE SUN'S SHADOWS (drawn from the sun, for everything lit by it to look up) ---
        renderShadows(gl, eye, sunDirection, sunElevation, (float) elapsedTime);

        // --- PASS 1: DEPTH PRE-PASS ---
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, depthFBO[0]);
        gl.glClear(GL3.GL_DEPTH_BUFFER_BIT);
        gl.glEnable(GL3.GL_DEPTH_TEST);
        gl.glEnable(GL3.GL_CULL_FACE);

        Matrix4 sphereViewProjection = camera.getSphereViewProjection();
        // (the identification kit projects what it finds through the camera's local frame)
        lastSceneViewProjection = camera.localViewProjection(camera.getPerspectiveMatrix()).toFloatArrayForGLSL();

        // The ground: the sphere's patch round the player, in both the depth and colour passes
        updateGeodesicTerrain(gl);

        depthPrePassShader.use(gl);
        geodesicTerrainModel.renderDepthPass(gl, depthPrePassShader);

        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, sceneFbo);   // (back to where the world is drawn)

        // --- PASS 2: MAIN FORWARD DRAW ---
        gl.glClearColor(skyColour.x, skyColour.y, skyColour.z, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_CULL_FACE);

        multiplayerFrame(deltaTime);
        sphereWeather.update((float) deltaTime, cloudTime, eye, () -> rainfallAt(eye),
                p -> Math.max(groundHeightAt(p), seaLevelHeight), freezingPoint - temperatureAt(eye));
        // (as loud as the share of what falls that's rain: none in snow, some in sleet)
        rainSound.setRain(sphereWeather.intensity() * (1f - sphereWeather.snowShare()));

        // The sky's turn: in the local frame here (for the player's body, posed in it), and
        // in the world (for everything drawn in planet-centred coordinates)
        Matrix4 localSkyRotation = skyRotation(eye);
        Matrix4 skyRotation = Matrix4.multiply(SphereFrame.at(eye).rotationMatrix(), localSkyRotation);

        sphereSkyRenderer.render(gl, camera, textures.get("sky"), skyColour,
                (float) currentWidth / Math.max(1, currentHeight), skyRotation);

        gl.glEnable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_DEPTH_TEST);

        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        lights[0].render(gl);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);

        float[] sunTintNow = worldArt.palette().sunTint;
        float daylight = 1.0f - nightProportion;
        float[] sunColour = { sunTintNow[0] * daylight, sunTintNow[1] * daylight, sunTintNow[2] * daylight };
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

        // Only the ground takes the regional soil colour (and bare rock on steep slopes); trunks
        // and walls share this shader
        gl.glActiveTexture(GL3.GL_TEXTURE6);
        gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, soilRegionTexture);
        gl.glActiveTexture(GL3.GL_TEXTURE0);
        terrainShader.setInt(gl, "soilRegionMap", 6);
        terrainShader.setFloat(gl, "useSoilRegions", 1.0f);
        terrainShader.setVec3(gl, "rockColour", worldRockColour());
        geodesicTerrainModel.render(gl, ambientLight, nightProportion);
        terrainShader.setFloat(gl, "useSoilRegions", 0.0f);

        renderGeodesicInfrastructure(gl, skyRotation);
        renderFlora(gl, skyRotation);

        // --- CREATURES AND PEOPLE (moved on before the shadows) ---
        // The player is solid to everything else too; then this frame's bodies take over
        geodesicCollision.addBody(PLAYER, eye, PLAYER_RADIUS, true);
        geodesicCollision.endFrame();
        organismManager.render(gl, camera, sunDirection, sunColour, ambientLight, skyRotation, textures.get(skyTextureKey), (float) elapsedTime);
        inhabitants.render(gl, camera, sunDirection, sunColour, ambientLight, skyRotation, textures.get(skyTextureKey), (float) elapsedTime);

        // --- INSTANCED GRASS PASS ---
        if (drawnGrassInstances > 0) {
            gl.glDisable(GL.GL_CULL_FACE);

            grassShader.use(gl);
            grassShader.setFloatArray(gl, "viewProjection", sphereViewProjection.toFloatArrayForGLSL());
            grassShader.setVec3(gl, "sphereCamera", new Vector3((float) eye.x(), (float) eye.y(), (float) eye.z()));
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(grassShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
            grassShader.setVec3(gl, "cameraPos", new Vector3(0f, 0f, 0f));
            grassShader.setVec3(gl, "ambientLight", ambientLight);
            grassShader.setVec3(gl, "skyColour", skyColour);
            grassShader.setInt(gl, "worldSeed", (int) (worldSeed & 0xFFFF));
            grassShader.setFloat(gl, "time", (float) elapsedTime);

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

        // --- ROCKS ---
        rockField.update(gl, eye, false);
        gl.glDisable(GL.GL_CULL_FACE);
        rockField.render(gl, sphereViewProjection, eye, camera.getSphereForwardDirection(), sunDirection,
                sunColour, ambientLight, skyRotation, textures.get(skyTextureKey));
        gl.glEnable(GL.GL_CULL_FACE);

        // --- THE LANDING POD ---
        landingPod.render(gl, sphereViewProjection, eye, sunDirection, sunColour,
                ambientLight, skyRotation, textures.get(skyTextureKey));

        // --- THE PARTNER (multiplayer): always drawn, however far, where they're predicted to be now ---
        if (partnerBody != null && partner.hasPose()) {
            partnerBody.update((float) deltaTime, partner.eye(), (partner.flags() & Multiplayer.POSE_SWIMMING) != 0);
            partnerBody.renderInWorld(gl, sphereViewProjection, eye, partner.eye(), (float) partner.heading(),
                    sunDirection, sunColour, ambientLight, skyRotation, textures.get(skyTextureKey));
        }

        // --- CLOUDS, over the sky and the sun: only where nothing solid stands in front ---
        sphereWeather.renderClouds(gl, camera, (float) currentWidth / Math.max(1, currentHeight), skyColour, daylight,
                cloudTime, SphereRender.sunDirection(eye, Planet.sunDirection(eye, sunDeclination, noonLongitude)),
                sunTintNow, fog, seaLevelHeight);

        // --- WINDOW GLASS ---
        drawWindowGlass(gl, sphereViewProjection, eye, camera.getSphereForwardDirection(), SphereRender.sunPoint(sunDirection),
                sunColour, skyRotation, textures.get(skyTextureKey), GLASS_DRAW_DISTANCE);

        renderSea(gl, skyRotation, textures.get(skyTextureKey), (float) elapsedTime);
        renderRivers(gl, skyRotation, textures.get(skyTextureKey), (float) elapsedTime);
        // --- RAIN, under dark clouds ---
        sphereWeather.renderRain(gl, sphereViewProjection, eye, worldArt.palette().seaShallowTint(), daylight,
                (float) (currentHeight * 0.5 / Math.tan(fieldOfView() / 2.0)));

        // What's in front of what through the magnifying glass, before the body's drawn over it
        captureLensDepth(gl);
        // (no shadows on the player's own body, drawn in a frame of its own, nor on anything drawn
        // after this frame's world, such as the map's pictures)
        Shader.clearShadows(gl);

        if (hideFirstPerson) return;

        // --- THE PLAYER'S OWN BODY, drawn last and nearest, in the camera's local frame ---
        if (compassWanted) {
            compassWanted = false;
            playerBody.useCompass();
        }
        if (thermometerWanted) {
            thermometerWanted = false;
            playerBody.useThermometer();
        }
        if (altimeterWanted) {
            altimeterWanted = false;
            playerBody.useAltimeter();
        }
        if (kitWanted) {
            kitWanted = false;
            playerBody.useKit();
        }
        playerBody.setReading(temperatureAt(eye));
        // The altimeter reads the height of the ground underfoot (or the road) above the sea, in
        // metres: not the rock or roof stood on, so it agrees with the map
        playerBody.setAltitude(((float) groundHeightAt(eye) - seaLevelHeight) / PlayerBody.METRE);
        playerBody.update((float) deltaTime, eye, swimming);
        float[] deep = worldArt.palette().seaShallowTint();
        playerBody.render(gl, camera, (float) currentWidth / Math.max(1, currentHeight),
                new Vector3(sunDir[0] * 2350f, sunDir[1] * 2350f, sunDir[2] * 2350f), sunColour,
                ambientLight, localSkyRotation, textures.get(skyTextureKey), waterSurfaceHere - camera.getAltitude(), deep);
    }

    // The geodesic cell (at the trees' and grass's level) the player was in when last looked at
    private long lastVegetationCell = Long.MIN_VALUE;

    // Developer aid: -Dxenoguesser.framesequence=6 saves that many frames in a row after later_frame.png
    // (sequence_1.png on), to see what changes from one frame to the next
    private static final int FRAME_SEQUENCE = Dev.integer("xenoguesser.framesequence", 0);

    // The sun's shadows (see ShadowMaps), and the sun's height (degrees) at which they're at full strength
    private ShadowMaps shadowMaps = new ShadowMaps();
    private boolean shadowMapsMade;
    // Leafy things drawn into the shadows (only where their picture's solid)
    private Shader shadowCutoutShader;
    private static final float SHADOWS_FULL_ABOVE = 8f;

    /**
     * Draws the sun's shadow maps round the eyes (the near one every frame, the far one every
     * other frame) and hands them to every shader that draws shadows; or, with shadows off in the
     * settings or the sun down, none. Everything's drawn by its own drawing, with the camera's view
     * swapped for the sun's and the colour turned off; only near the eyes for the near map are the
     * smaller things (creatures, people, rocks, the pod) drawn too.
     */
    private void renderShadows(GL3 gl, SpherePosition eye, Vector3 sunDirection, float sunElevation, float time) {
        float strength = GameSettings.shadows() ? smoothStep(0f, SHADOWS_FULL_ABOVE, sunElevation) : 0f;
        // (nothing samples the maps while they're drawn into)
        Shader.clearShadows(gl);
        if (strength <= 0f || geodesicTerrainModel == null) return;
        boolean[] due = shadowMaps.dueThisFrame();
        float[] sunColour = { 1f, 1f, 1f };
        Matrix4 skyRotation = new Matrix4(1f);
        for (int map = 0; map < 2; map++) {
            if (!due[map]) continue;
            float reach = map == 0 ? ShadowMaps.NEAR_REACH : ShadowMaps.FAR_REACH;
            Matrix4 sunView = shadowMaps.begin(gl, map, eye, sunDirection);
            camera.overrideViewProjection(sunView);
            // (the buildings and trees drawn with only their depth: leaves with their see-through parts left out)
            Renderer.beginCasting(depthPrePassShader, shadowCutoutShader, java.util.Set.of(leafShader, signboardShader));
            try {
                gl.glDisable(GL.GL_CULL_FACE);
                // (the ground, hills shading valleys, into the far map only: it's the whole patch to the
                // horizon, and its own small bumps don't need the near one)
                if (map == 1) {
                    depthPrePassShader.use(gl);
                    geodesicTerrainModel.renderDepthPass(gl, depthPrePassShader);
                }
                castInfrastructure(gl, eye, reach);
                castFlora(gl, eye, reach, map == 0 ? 0 : 2);
                if (map == 0) {
                    // (posed at the same moment as they're drawn, so their shadows move with them)
                    organismManager.render(gl, camera, sunDirection, sunColour, ambientLight, skyRotation, null, time);
                    inhabitants.render(gl, camera, sunDirection, sunColour, ambientLight, skyRotation, null, time);
                    gl.glDisable(GL.GL_CULL_FACE);
                    rockField.render(gl, sunView, eye, camera.getSphereForwardDirection(), sunDirection, sunColour, ambientLight, skyRotation, null);
                    landingPod.render(gl, sunView, eye, sunDirection, sunColour, ambientLight, skyRotation, null);
                    if (partnerBody != null && partner.hasPose()) {
                        partnerBody.renderInWorld(gl, sunView, eye, partner.eye(), (float) partner.heading(),
                                sunDirection, sunColour, ambientLight, skyRotation, null);
                    }
                }
            } finally {
                Renderer.endCasting();
                camera.overrideViewProjection(null);
                shadowMaps.end(gl, sceneFbo, currentWidth, currentHeight);
            }
        }
        gl.glEnable(GL.GL_CULL_FACE);
        shadowMaps.publish(gl, eye, sunDirection, strength);
    }

    /** The buildings, roads and walls within reach of the eyes into a shadow map, whether in view or not. */
    private void castInfrastructure(GL3 gl, SpherePosition eye, float reach) {
        for (List<InfrastructureObject> objects : infraCache.values()) {
            for (InfrastructureObject object : objects) {
                if (object.type != InfrastructureObject.Type.BATCH) continue;
                double dx = object.centre.x() - eye.x(), dy = object.centre.y() - eye.y(), dz = object.centre.z() - eye.z();
                double r = reach + object.boundingRadius;
                if (dx * dx + dy * dy + dz * dz > r * r) continue;
                object.renderBatch(gl, ambientLight, nightProportion, solidShader, terrainShader, terrainRenderer, camera);
            }
        }
    }

    /** The trees and shrubs within reach of the eyes into a shadow map (at least as coarse as lod), whether in view or not. */
    private void castFlora(GL3 gl, SpherePosition eye, float reach, int lod) {
        for (List<SphereFlora> plants : floraStream.ready().values()) {
            for (SphereFlora plant : plants) {
                SpherePosition position = plant.position();
                double dx = position.x() - eye.x(), dy = position.y() - eye.y(), dz = position.z() - eye.z();
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                double r = reach + FLORA_CULL_RADIUS * plant.scale();
                if (distanceSquared > r * r) continue;
                int detail = Math.max(lod, distanceSquared > 135.0 * 135.0 ? 2 : distanceSquared > 65.0 * 65.0 ? 1 : 0);
                Model branch = floraBranchModelsLOD[plant.speciesIndex()][detail][plant.modelIndex()];
                Model leaves = floraLeafModelsLOD[plant.speciesIndex()][detail][plant.modelIndex()];
                if (branch == null || leaves == null) continue;
                branch.setModelMatrix(plant.model());
                branch.render(gl, ambientLight, nightProportion);
                leaves.setModelMatrix(plant.model());
                leaves.render(gl, ambientLight, nightProportion);
            }
        }
    }

    private static float smoothStep(float from, float to, float x) {
        float t = Math.max(0f, Math.min(1f, (x - from) / (to - from)));
        return t * t * (3f - 2f * t);
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

        // The sprint's stamina, in the top right (under the FPS, when that's shown), wherever the player can walk
        boolean staminaShown = GameMode.current().moving;
        if (staminaShown) {
            int step = Math.round(stamina * 200f) * 2 + (winded ? 1 : 0);
            if (rescaled || step != hudStaminaStep || !hud.has("stamina")) {
                hudStaminaStep = step;
                BufferedImage image = HudStyle.canvas(HudStyle.STAMINA_W, HudStyle.STAMINA_H, scale, g);
                HudStyle.paintStamina(g[0], stamina, winded);
                g[0].dispose();
                hud.put("stamina", image, 0, 0);
            }
            int below = GameSettings.showFps() ? Math.round((6 + HudStyle.FPS_H) * scale) : margin;
            hud.move("stamina", screenWidth - hud.width("stamina") - margin, below);
        }
        hud.setVisible("stamina", staminaShown);

        // The round's clock (M41), at the top in the middle, while it's running
        long[] clock = minimap != null ? minimap.guessClock() : null;
        if (clock != null) {
            long tenth = clock[0] / 100L;
            String note = clock[2] == 1L && Multiplayer.active() != null
                    ? "Employee " + Multiplayer.active().remoteLabel() + " guessed!" : "Time to guess";
            if (rescaled || tenth != hudClockTenth || !hud.has("clock")) {
                hudClockTenth = tenth;
                BufferedImage image = HudStyle.canvas(HudStyle.TIMER_W, HudStyle.TIMER_H, scale, g);
                HudStyle.paintTimer(g[0], clock[0], clock[1], note);
                g[0].dispose();
                hud.put("clock", image, (screenWidth - Math.round(HudStyle.TIMER_W * scale)) / 2, margin);
            }
        }
        hud.setVisible("clock", clock != null);
        // Under 5 s left (M45), the screen's edges pulse red, about once a second and stronger
        // as it runs out
        if (clock != null && clock[0] > 0L && clock[0] < LOW_CLOCK_MS) {
            float urgency = 1f - clock[0] / (float) LOW_CLOCK_MS;
            float beat = 0.5f + 0.5f * (float) Math.cos(System.nanoTime() / 1e9 * Math.PI * 2.0 * 1.25);
            hud.setEdgeGlow(Math.min(0.9f, (0.6f + 0.3f * urgency) * (0.3f + 0.7f * beat)));
        } else {
            hud.setEdgeGlow(0f);
        }

        // The inventory: only what the player has bought, and nothing at all until they've bought something
        java.util.List<HudStyle.Item> items = inventoryItems();
        if (rescaled || itemsVersion != hudItemsVersion || !hud.has("inventory")) {
            hudItemsVersion = itemsVersion;
            int h = HudStyle.inventoryHeight(Math.max(1, items.size()));
            BufferedImage image = HudStyle.canvas(HudStyle.INVENTORY_W, h, scale, g);
            HudStyle.paintInventory(g[0], items);
            g[0].dispose();
            hud.put("inventory", image, margin, 0);
        }
        hud.setVisible("inventory", !items.isEmpty());
        // (kept at the bottom however the window is resized)
        if (hud.has("inventory")) {
            int h = Math.round(HudStyle.inventoryHeight(Math.max(1, items.size())) * scale);
            hud.move("inventory", margin, screenHeight - h - margin);
        }

        if (minimap != null) {
            MapPanel.MapSize size = minimap.getMapSize();
            if (rescaled || size != hudMapSize) {
                hudMapSize = size;
                String[] keys = switch (size) {
                    case SMALL -> new String[] { "M", "Expand map" };
                    case LARGE -> new String[] { "M", "Contract map", "", "Scroll to zoom", "", "Click the map to place your marker" };
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

        drawLensFinds(scale);

        // An achievement just unlocked (M53): at the bottom right, just above the map
        Object[] toast = Achievements.toast();
        if (toast != null && minimap != null) {
            Achievements.Achievement a = (Achievements.Achievement) toast[0];
            if (rescaled || a != hudToast || !hud.has("achievement")) {
                hudToast = a;
                BufferedImage image = HudStyle.canvas(Achievements.TOAST_W, Achievements.TOAST_H, scale, g);
                Achievements.paintToast(g[0], a);
                g[0].dispose();
                hud.put("achievement", image, 0, 0);
            }
            java.awt.Rectangle map = minimap.getBounds();
            java.awt.Component view = drawable instanceof java.awt.Component c ? c : null;
            if (view != null) map.translate(-view.getX(), -view.getY());
            hud.move("achievement", screenWidth - hud.width("achievement") - margin,
                    Math.round((map.y - 16) * scale) - Math.round((Achievements.TOAST_H + HudStyle.HINT_H + 8) * scale));
            hud.setAlpha("achievement", (Float) toast[1]);
        }
        if (hud.has("achievement")) hud.setVisible("achievement", toast != null);
        // (the map's hint goes with the map while the game's paused: M46)
        if (hud.has("mapHint")) hud.setVisible("mapHint", !GameClock.isPaused());

        // After January's message: the offer of the controls, low in the middle (Enter opens them, X skips)
        boolean offered = controlsOffered;
        if (offered && (rescaled || !hud.has("controlsOffer"))) {
            String[] keys = { "Enter", "Open the controls", "X", "Skip" };
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D pg = probe.createGraphics();
            HudStyle.smooth(pg);
            int w = HudStyle.hintWidth(pg, keys);
            pg.dispose();
            BufferedImage image = HudStyle.canvas(w, HudStyle.HINT_H, scale * 1.4f, g);
            HudStyle.paintHint(g[0], w, keys);
            g[0].dispose();
            hud.put("controlsOffer", image, 0, 0);
        }
        if (hud.has("controlsOffer")) {
            hud.setVisible("controlsOffer", offered && !GameClock.isPaused());
            hud.move("controlsOffer", (screenWidth - hud.width("controlsOffer")) / 2, Math.round(screenHeight * 0.7f));
        }

        hud.setDim(!menuOpen ? 0f : GameClock.isPaused() ? PAUSED_DIM : 0.45f);
        hud.draw(gl, screenWidth, screenHeight);
    }

    // ------------------------------------------------------------------ multiplayer: poses

    // Where the partner is shown, and their body, seen from outside (null in singleplayer)
    private final RemotePlayer partner = new RemotePlayer();
    private PlayerBody partnerBody;
    private long partnerLoggedAt;

    /** The partner, as predicted for this frame. */
    public RemotePlayer partner() {
        return partner;
    }

    /**
     * Once a frame in multiplayer: this player's pose for the networking thread to send (it
     * sends the latest 5 times a second), and the partner's predicted for this frame.
     */
    private void multiplayerFrame(double deltaTime) {
        Multiplayer session = Multiplayer.active();
        if (session == null) return;
        SpherePosition eye = camera.getSpherePosition();
        Vector3 forward = camera.getSphereForwardDirection();
        byte flags = (byte) ((swimming ? Multiplayer.POSE_SWIMMING : 0) | (airborne ? Multiplayer.POSE_AIRBORNE : 0));
        session.publishLocalPose(new Multiplayer.Pose(eye.x(), eye.y(), eye.z(), forward.x, forward.y, forward.z, flags));

        long now = System.currentTimeMillis();
        partner.update(session.remotePoses(), now, deltaTime, p -> groundHeightAt(p), playerEyeHeight);
        if (partner.hasPose() && now - partnerLoggedAt >= 2000) {
            partnerLoggedAt = now;
            SpherePosition at = partner.eye();
            float[] f = camera.getLocalForward();
            double facing = Math.atan2(f[2], f[0]);
            double bearing = Math.toDegrees(Math.atan2(Math.sin(SphereMath.heading(eye, at) - facing), Math.cos(SphereMath.heading(eye, at) - facing)));
            System.out.printf(java.util.Locale.ROOT, "[MP] Partner shown at %.5f, %.5f, %.1f up, heading %.3f, %.1f units away (%.0f degrees to the right of where this player faces), speed %.1f, latest pose %d ms old%n",
                    Math.toDegrees(Planet.latitude(at)), Math.toDegrees(Planet.longitude(at)), SphereMath.altitude(at),
                    partner.heading(), SphereMath.distance(at, eye), bearing, partner.speed(), partner.ageMs(now));
        }
    }

    /** Developer aid: turns the view to face the partner, level (or pitchDegrees up), for checking how they're drawn. */
    /** Developer aid: faces the nearest herd (stepping in to about 90 away if it's further), a little down, and logs how far it was. */
    public void lookAtHerd() {
        SpherePosition here = camera.getSpherePosition();
        SpherePosition herd = organismManager.nearestHerd(here);
        if (herd == null) {
            System.out.println("[HERD] No herd near");
            return;
        }
        double distance = SphereMath.distance(here, herd);
        System.out.printf(java.util.Locale.ROOT, "[HERD] Nearest herd %.0f units away%n", distance);
        if (distance > 120.0) {
            // (stepping in to about 90 away, on the ground there)
            SpherePosition near = SphereMath.move(herd, SphereMath.heading(herd, here), 90.0);
            here = SphereMath.atAltitude(near, Math.max(seaLevelHeight, groundHeightAt(near)) + playerEyeHeight);
            distance = 90.0;
        }
        camera.setSphereView(here, (float) Math.toDegrees(SphereMath.heading(here, herd)), (float) -Math.toDegrees(Math.atan2(15.0, Math.max(20.0, distance))));
    }

    /** Developer aid: where the player's eyes are. */
    public SpherePosition devPosition() {
        return camera.getSpherePosition();
    }

    public void lookAtPartner(float pitchDegrees) {
        if (!partner.hasPose()) return;
        SpherePosition here = camera.getSpherePosition();
        camera.setSphereView(here, (float) Math.toDegrees(SphereMath.heading(here, partner.eye())), pitchDegrees);
    }

    /** Developer aid: how far the partner is shown from this player along the ground, or -1 with no partner. */
    public double distanceToPartner() {
        return partner.hasPose() ? SphereMath.distance(camera.getSpherePosition(), partner.eye()) : -1.0;
    }

    /**
     * Developer aid: stands the view distance out from the pod's middle, round from straight
     * down its stairs by aroundDegrees, raised by up, looking back at it (for checking how it's built).
     */
    public void viewPod(double distance, double aroundDegrees, double up) {
        SphereFrame pod = landingPod.frame();
        if (pod == null) return;
        SpherePosition middle = pod.origin();
        double[] stairs = pod.worldDirection(0, 0, 1);
        SpherePosition ahead = SphereMath.atAltitude(new SpherePosition(middle.x() + stairs[0], middle.y() + stairs[1], middle.z() + stairs[2]), SphereMath.altitude(middle));
        double down = SphereMath.heading(middle, ahead) + Math.toRadians(aroundDegrees);
        SpherePosition at = SphereMath.move(middle, down, distance);
        // (from above whichever is higher, the ground here or the pod's, aimed at its hatch)
        double eyeAt = Math.max(groundHeightAt(at), landingPod.ground()) + playerEyeHeight + up;
        at = SphereMath.atAltitude(at, eyeAt);
        double back = SphereMath.heading(at, middle);
        camera.setSphereView(at, (float) Math.toDegrees(back), (float) Math.toDegrees(Math.atan2(landingPod.ground() + 25.0 - eyeAt, distance)));
    }

    /** Developer aid: looks pitchDegrees up (negative down), keeping the heading. */
    public void lookPitch(float pitchDegrees) {
        float[] f = camera.getLocalForward();
        camera.setSphereView(camera.getSpherePosition(), (float) Math.toDegrees(Math.atan2(f[2], f[0])), pitchDegrees);
    }

    // Developer aid: a picture of the next frame as drawn (read back from the GL, so another
    // window over this one doesn't spoil it) is saved here, then this is cleared
    private volatile java.io.File frameShot;

    public void saveNextFrame(java.io.File file) {
        frameShot = file;
    }

    private void writeFrameShot(GL3 gl, int width, int height) {
        java.io.File file = frameShot;
        if (file == null) return;
        frameShot = null;
        java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(width * height * 4);
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl.glReadPixels(0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = ((height - 1 - y) * width + x) * 4;
                image.setRGB(x, y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
            }
        }
        try {
            file.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(image, "png", file);
            System.out.println("[SHOT] Frame saved to " + file);
        } catch (java.io.IOException e) {
            System.err.println("Couldn't save the frame: " + e.getMessage());
        }
    }

    // (the world's time, which stands still while the game's paused: see GameClock)
    private double getSeconds() {
        return GameClock.millis() / 1000.0;
    }

    public static float precalculateSeaLevel(long seed, PerlinNoise noise) {
        java.util.Random rand = new java.util.Random(seed);
        float waterProportion = 0.6f + rand.nextFloat() * 0.1f; 
        // Evenly over the part of the planet the map shows
        int totalSamples = 4000;
        java.util.ArrayList<Float> heightSamples = new java.util.ArrayList<>(totalSamples);

        for (int i = 0; i < totalSamples; i++) {
            double longitude = rand.nextFloat() * 2 * Math.PI - Math.PI;
            double latitude = Math.asin((rand.nextFloat() * 2f - 1f) * Math.sin(MapProjection.CLIP_LATITUDE));
            heightSamples.add(TerrainMesh.getLayeredHeight(Planet.position(latitude, longitude, 0.0), noise));
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
        // Developer aid: -Dxenoguesser.unlockall starts with every map (the items are had from the start anyway)
        if (Dev.flag("xenoguesser.unlockall")) {
            ownedLayers.addAll(List.of(MapPanel.Layer.values()));
            ownedOverlays.addAll(allOverlays());
        }
        for (MapPanel.Layer layer : startingLayers) minimap.setLayerShown(layer, true);
        // Developer aid: -Dxenoguesser.picturemap=Landmarks (or another map of pictures) starts with it shown
        for (FactorName f : PICTURE_MAPS) if (f.label().equalsIgnoreCase(Dev.property("xenoguesser.picturemap", ""))) currentPictureMap = f;
        refreshMapChoices();
        if (currentPictureMap != null) choosePictureMap(currentPictureMap);
        if (currentDebugFactor != null) chooseGradient(currentDebugFactor);
    }

    // ------------------------------------------------------------------ what the player has bought

    // The things the player can carry, every one had from the start (in this order, so 1 is the
    // compass and 4 the kit); and the maps bought in the shop, nothing to begin with: the map's
    // layers and overlays
    private static final String COMPASS = "Compass", THERMOMETER = "Thermometer", ALTIMETER = "Altimeter", KIT = "Identification kit";
    private static final String[] ITEMS = { COMPASS, THERMOMETER, ALTIMETER, KIT };

    /** How an item's icon is drawn. */
    private static HudStyle.IconPainter itemIcon(String item) {
        return switch (item) {
            case COMPASS -> HudStyle::paintCompassIcon;
            case THERMOMETER -> HudStyle::paintThermometerIcon;
            case ALTIMETER -> HudStyle::paintAltimeterIcon;
            default -> HudStyle::paintKitIcon;
        };
    }
    private final List<String> ownedItems = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(ITEMS));
    private final java.util.Set<MapPanel.Layer> ownedLayers = java.util.Collections.synchronizedSet(java.util.EnumSet.noneOf(MapPanel.Layer.class));
    private final java.util.Set<Overlay> ownedOverlays = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    // Bumped when an item is bought, so the inventory is drawn again
    private volatile int itemsVersion;

    /**
     * The collections' achievements (M53), for what's been got this game: every infrastructure
     * map (the buildings, shops and the maps of pictures), every plant's and every animal's
     * heatmap. (Not with the developer's everything unlocked. Every gadget, Technician, is had
     * from the start now, so is never earned by collecting.)
     */
    private void checkCollections() {
        if (Dev.flag("xenoguesser.unlockall")) return;
        boolean infrastructure = ownedLayers.contains(MapPanel.Layer.BUILDINGS) && ownedLayers.contains(MapPanel.Layer.SHOPS);
        for (FactorName f : PICTURE_MAPS) infrastructure &= ownedOverlays.contains(Overlay.of(f));
        if (infrastructure) Achievements.unlock(Achievements.Achievement.INFRASTRUCTURE_EXPERT);
        boolean flora = true, fauna = organismManager.speciesCount() > 0;
        for (int s = 0; s < NUM_SPECIES; s++) flora &= ownedOverlays.contains(new Overlay(FactorName.FLORA, s));
        for (int i = 0; i < organismManager.speciesCount(); i++) fauna &= ownedOverlays.contains(new Overlay(FactorName.ANIMAL_POPULATION, i));
        if (flora) Achievements.unlock(Achievements.Achievement.BOTANIST);
        if (fauna) Achievements.unlock(Achievements.Achievement.ZOOLOGIST);
    }

    /** Lists, beside the enlarged map, the layers and overlays the player has. */
    private void refreshMapChoices() {
        if (minimap == null) return;
        checkCollections();
        List<MapPanel.Layer> layers = new ArrayList<>();
        for (MapPanel.Layer layer : MapPanel.Layer.values()) if (ownedLayers.contains(layer)) layers.add(layer);
        java.util.LinkedHashMap<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, List<Overlay>> group : overlayGroups().entrySet()) {
            List<String> labels = new ArrayList<>();
            for (Overlay o : group.getValue()) if (ownedOverlays.contains(o)) labels.add(overlayLabel(o));
            groups.put(group.getKey(), labels);
        }
        // (the maps of pictures go in the markings' Infrastructure folder: M47)
        List<String> pictureMaps = new ArrayList<>();
        for (FactorName f : PICTURE_MAPS) if (ownedOverlays.contains(Overlay.of(f))) pictureMaps.add(f.label());
        groups.get("").removeIf(label -> pictureMaps.contains(label));
        Overlay shown = currentDebugFactor;
        FactorName pictures = currentPictureMap;
        minimap.setLayerChoices(layers, groups, shown != null && ownedOverlays.contains(shown) ? overlayLabel(shown) : null, label -> {
            Overlay chosen = null;
            for (Overlay o : allOverlays()) if (overlayLabel(o).equals(label)) chosen = o;
            chooseGradient(chosen);
        }, pictureMaps, pictures != null ? pictures.label() : null, label -> {
            FactorName chosen = null;
            for (FactorName f : PICTURE_MAPS) if (f.label().equals(label)) chosen = f;
            choosePictureMap(chosen);
        }, this::choiceIcon);
    }

    /** A map's price factor: for one of what people have built (roads, buildings, shops, wealth, nations), as the kind of map makes it (see MapKind). */
    private static float builtPriceFactor(boolean built) {
        return built ? MapKind.current().builtPriceFactor : 1f;
    }

    /**
     * Three maps for the shop after this round (counting from 1), shuffled from everything not
     * yet bought (the gadgets are had from the start, so never offered), each with its price
     * (drawn at random round what the player earns a round, see ShopPanel.price; nothing in multiplayer) and what buying it does.
     */
    public List<ShopPanel.Card> shopOffers(int round) {
        java.util.Map<String, String> descriptions = ShopPanel.loadDescriptions();
        java.util.Random rand = new java.util.Random();
        // (prices keep pace with what the player's been paid lately)
        float pay = gameHUD != null ? gameHUD.recentPay() : GameHUD.SALARY;
        List<ShopPanel.Card> deck = new ArrayList<>();
        for (MapPanel.Layer layer : MapPanel.Layer.values()) {
            if (ownedLayers.contains(layer)) continue;
            deck.add(new ShopPanel.Card(layer.label, "Map marking", choiceIcon(layer.label), null, describe(descriptions, layer.label, layer.label),
                    ShopPanel.price(round, pay, builtPriceFactor(layer == MapPanel.Layer.ROADS || layer == MapPanel.Layer.BUILDINGS
                            || layer == MapPanel.Layer.SHOPS), rand), () -> {
                ownedLayers.add(layer);
                // (shown straight away)
                minimap.setLayerShown(layer, true);
                refreshMapChoices();
            }));
        }
        for (List<Overlay> group : overlayGroups().values()) {
            for (Overlay o : group) {
                if (ownedOverlays.contains(o)) continue;
                String name = overlayLabel(o);
                boolean plant = o.factor() == FactorName.FLORA, animal = o.factor() == FactorName.ANIMAL_POPULATION;
                String key = plant ? "Flora" : animal ? "Fauna" : name;
                String kind = plant ? "Plant heatmap" : animal ? "Animal heatmap" : PICTURE_MAPS.contains(o.factor()) ? "Map" : "Heatmap";
                deck.add(new ShopPanel.Card(name, kind, choiceIcon(name), null, describe(descriptions, key, name),
                        ShopPanel.price(round, pay, plant || animal ? ShopPanel.SPECIES_PRICE_FACTOR
                                : builtPriceFactor(o.factor() == FactorName.WEALTH || o.factor() == FactorName.NATIONS || PICTURE_MAPS.contains(o.factor())), rand), () -> {
                    ownedOverlays.add(o);
                    refreshMapChoices();
                }));
            }
        }
        java.util.Collections.shuffle(deck, rand);
        List<ShopPanel.Card> dealt = new ArrayList<>();
        for (ShopPanel.Card card : deck) {
            if (dealt.size() < 3 && !dealt.contains(card)) dealt.add(card);
        }
        return dealt;
    }

    /** A card's description from the file, by key, with {name} filled in; a plain one if the file has none. */
    private static String describe(java.util.Map<String, String> descriptions, String key, String name) {
        String text = descriptions.get(key);
        return text != null ? text.replace("{name}", name) : "A map of the planet: " + name + ".";
    }

    /** The items the player has, in the order bought (the first used with 1, the next with 2, and so on). */
    private List<HudStyle.Item> inventoryItems() {
        List<HudStyle.Item> items = new ArrayList<>();
        for (String item : ownedItems) {
            items.add(new HudStyle.Item(item, String.valueOf(items.size() + 1), itemIcon(item)));
        }
        return items;
    }

    /** A number key: takes out the item in that place in the inventory, if the player has one there (0 for the first). */
    public void useItem(int index) {
        if (index < 0 || index >= ownedItems.size()) return;
        switch (ownedItems.get(index)) {
            case COMPASS -> useCompass();
            case THERMOMETER -> useThermometer();
            case ALTIMETER -> altimeterWanted = true;
            default -> kitWanted = true;
        }
    }

    /**
     * Colours the map's land by an overlay, or by nothing given null. Every overlay is
     * worked out in the background soon after the world is made, so it's normally ready at
     * once; one asked for before then is finished off in the background and shown when ready,
     * if it's still the one wanted.
     */
    // The dropdowns at the foot of the map's overlay choices, in order
    private static final String ROCK_GROUP = "Rocks", GRASS_GROUP = "Grass", FLORA_GROUP = "Flora", FAUNA_GROUP = "Fauna";

    /**
     * The map's overlays by where they're listed: "" for those listed on their own, then the
     * grass's maps, each plant species' range (trees, then shrubs), and each animal species' range.
     */
    private java.util.LinkedHashMap<String, List<Overlay>> overlayGroups() {
        java.util.LinkedHashMap<String, List<Overlay>> groups = new java.util.LinkedHashMap<>();
        List<Overlay> top = new ArrayList<>();
        for (FactorName f : new FactorName[] { FactorName.TEMPERATURE, FactorName.RAINFALL, FactorName.SOIL_COLOUR, FactorName.WEALTH, FactorName.NATIONS,
                FactorName.BOLLARDS, FactorName.ROAD_LINES, FactorName.UTILITY_POLES, FactorName.ARCHITECTURE, FactorName.LANDMARKS }) {
            top.add(Overlay.of(f));
        }
        groups.put("", top);
        groups.put(ROCK_GROUP, List.of(Overlay.of(FactorName.ROCKS), Overlay.of(FactorName.ROCK_SIZE)));
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
            taken.add(PlanetName.forSeed(worldSeed).spelling.toLowerCase(java.util.Locale.ROOT));
            for (int i = 0; i < organismManager.speciesCount(); i++) taken.add(organismManager.speciesName(i).toLowerCase(java.util.Locale.ROOT));
            for (int k = 0; k < NUM_SPECIES; k++) {
                String name = null;
                for (int attempt = 0; name == null || !taken.add(name.toLowerCase(java.util.Locale.ROOT)); attempt++) {
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
    // The maps of pictures rather than colours: each nation's bollard, road markings or utility
    // pole stood on its main land, over the plain map, no borders (see chooseGradient); their
    // pictures ({picture, worldX, worldZ}), once drawn, and how tall
    private static final java.util.Set<FactorName> PICTURE_MAPS = java.util.EnumSet.of(FactorName.BOLLARDS, FactorName.ROAD_LINES, FactorName.UTILITY_POLES,
            FactorName.ARCHITECTURE, FactorName.LANDMARKS);
    private volatile java.util.Map<FactorName, List<Object[]>> mapPictures = java.util.Map.of();
    // The map of pictures shown (over any heatmap: M47), or null
    private volatile FactorName currentPictureMap;
    private static final int MAP_PICTURE_HEIGHT = 128;
    // How tall the landmarks' pictures are (shown in their popups on the map)
    private static final int LANDMARK_PICTURE_HEIGHT = 220;

    /**
     * Where on the map a nation's design is pictured, {worldX, worldZ}: its middle (where its
     * name goes) for its main one; for a neighbour's, 45% of the way towards that neighbour's
     * middle (not across the map's join, where the way round is the short one), on the side of the
     * nation it's built.
     */
    private float[] towardsNeighbour(float[] own, float[] neighbour) {
        if (neighbour == null) return new float[] { own[1], own[2] };
        float dx = neighbour[1] - own[1], dz = neighbour[2] - own[2];
        if (Math.abs(dx) > TOTAL_REGION_WIDTH * 0.5f) return new float[] { own[1] + Math.signum(-dx) * 1500f, own[2] + dz * 0.45f };
        return new float[] { own[1] + dx * 0.45f, own[2] + dz * 0.45f };
    }

    // How far apart (shares of the map's width, across and up) the architecture map's pictures
    // stand: they're wide, so further apart across than up
    private static final float PICTURE_GAP_ACROSS = 0.13f, PICTURE_GAP_UP = 0.08f;

    /**
     * Thins out pictures ({picture, worldX, worldZ}) so they don't crowd together on the map: the
     * main ones first, each kept unless it's on top of one already kept (closer than half the
     * gap), then the others (neighbours' designs, provinces') only where they're a whole gap clear
     * of every one kept. (Across the map's join, the short way round.)
     */
    private List<Object[]> spaceOut(List<Object[]> main, List<Object[]> others) {
        List<Object[]> kept = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            float gap = pass == 0 ? 0.5f : 1f;
            for (Object[] p : pass == 0 ? main : others) {
                boolean clear = true;
                for (Object[] k : kept) {
                    float dx = Math.abs((Float) p[1] - (Float) k[1]), dz = Math.abs((Float) p[2] - (Float) k[2]);
                    dx = Math.min(dx, TOTAL_REGION_WIDTH - dx);
                    float ex = dx / (PICTURE_GAP_ACROSS * gap * TOTAL_REGION_WIDTH), ez = dz / (PICTURE_GAP_UP * gap * TOTAL_REGION_WIDTH);
                    if (ex * ex + ez * ez < 1f) {
                        clear = false;
                        break;
                    }
                }
                if (clear) kept.add(p);
            }
        }
        return kept;
    }

    /** A picture map's pictures (none yet until they're drawn). */
    private List<Object[]> picturesOf(FactorName f) {
        return mapPictures.getOrDefault(f, List.of());
    }


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
            case "Rivers" -> "rivers";
            case "Roads" -> "roads";
            case "Buildings" -> "buildings";
            case "Shops" -> "shops";
            case "None" -> "none";
            case "Temperature" -> "temperature";
            case "Rainfall" -> "rainfall";
            case "Soil colour" -> "soil";
            case "Rocks", "Rock frequency", "Rock size" -> "rocks";
            case "Wealth" -> "wealth";
            case "Nations" -> "nations";
            // (the maps of pictures: the same icon every game, whatever this world's designs)
            case "Bollards" -> "bollards";
            case "Road lines" -> "road_lines";
            case "Utility poles" -> "utility_poles";
            case "Architecture" -> "architecture";
            case "Landmarks" -> "landmarks";
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

    // Each plant species' picture for the map, once drawn, and those being drawn (see finishSteps)
    private volatile java.awt.Image[] floraPortraits;
    private java.awt.Image[] floraMade;

    /** A plant species' picture, for the map's and the shop's choices. */
    private java.awt.Image floraPortrait(GL3 gl, int species) {
        BufferedImage view = floraView(gl, species, PREVIEW_RENDER, (float) Math.toRadians(30), true);
        return view == null ? null : Offscreen.cropTogether(new BufferedImage[] { view }, 96)[0];
    }

    /** The plants' pictures, drawn: for the book and the map. */
    private void showFloraPortraits(java.awt.Image[] portraits) {
        floraPortraits = portraits;
        giveBookPictures();
        if (minimap == null) return;
        // (a plant's range already chosen gets its picture now)
        Overlay shown = currentDebugFactor;
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (shown != null && shown.factor() == FactorName.FLORA && shown == currentDebugFactor) minimap.setOverlayPicture(portraits[shown.species()]);
            minimap.repaint();
        });
    }
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
        // (normally drawn while loading: see finishSteps)
        if (floraPortraits == null) {
            java.awt.Image[] portraits = new java.awt.Image[NUM_SPECIES];
            for (int s = 0; s < NUM_SPECIES; s++) portraits[s] = floraPortrait(gl, s);
            showFloraPortraits(portraits);
        }
        Overlay wanted = previewWanted;
        if (wanted == null) return;
        if (previewBuilding != wanted) {
            previewBuilding = wanted;
            previewRaw = new BufferedImage[PREVIEW_FRAMES];
            previewNext = 0;
        }
        SpherePosition here = camera.getSpherePosition();
        for (int k = 0; k < PREVIEWS_PER_FRAME && previewNext < PREVIEW_FRAMES; k++, previewNext++) {
            float angle = (float) (Math.PI * 2 * previewNext / PREVIEW_FRAMES);
            previewRaw[previewNext] = wanted.factor() == FactorName.FLORA ? floraView(gl, wanted.species(), PREVIEW_RENDER, angle)
                    : organismManager.view(gl, wanted.species(), PREVIEW_RENDER, angle, here);
            if (previewRaw[previewNext] == null) previewRaw[previewNext] = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
        if (previewNext < PREVIEW_FRAMES) return;
        BufferedImage[] cropped = Offscreen.cropTogether(previewRaw, PREVIEW_SIZE);
        // (shrubs shown half as tall as trees, standing on the same ground)
        BufferedImage[] frames = wanted.factor() == FactorName.FLORA && wanted.species() >= 4 ? shrunkToGround(cropped, 0.5f) : cropped;
        previewRaw = null;
        previewWanted = null;
        previewBuilding = null;
        String name = overlayLabel(wanted);
        String kind = wanted.factor() == FactorName.FLORA ? speciesConfigs[wanted.species()].design.kind() : "Animal";
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (currentDebugFactor != null && currentDebugFactor.equals(wanted)) {
                minimap.setPreview(name, kind, frames, wanted.factor() == FactorName.FLORA ? detailPicture(wanted.species()) : null);
            }
        });
    }

    /** Each picture drawn smaller by scale, in a picture of the same size, at the middle of its foot. */
    private static BufferedImage[] shrunkToGround(BufferedImage[] frames, float scale) {
        BufferedImage[] out = new BufferedImage[frames.length];
        for (int i = 0; i < frames.length; i++) {
            int w = frames[i].getWidth(), h = frames[i].getHeight();
            int sw = Math.round(w * scale), sh = Math.round(h * scale);
            out[i] = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out[i].createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(frames[i], (w - sw) / 2, h - sh, sw, sh, null);
            g.dispose();
        }
        return out;
    }

    /** Plant species s's leaf, in its own colours (its texture runs from the dark shade to the light). */
    /**
     * What's shown beside a plant on the map to compare: its leaf, or if it has none, one of its
     * orbs or its spikes; nothing if it bears none of them (a bare mushroom or cactus).
     */
    private BufferedImage detailPicture(int s) {
        Flora.Design d = speciesConfigs[s].design;
        if (d.leaves) return leafPicture(s);
        if (d.orbs > 0f) return orbPicture(leafLightColour(speciesConfigs[s].healthyColor));
        if (d.spikes > 0f) return spikePicture();
        return null;
    }

    // The detail pictures' size (pixels square), as the leaves'
    private static final int DETAIL_PICTURE = 128;

    /** One of a plant's orbs: a glossy ball of its colour, lit from the upper left. */
    private static BufferedImage orbPicture(Vector3 colour) {
        BufferedImage image = new BufferedImage(DETAIL_PICTURE, DETAIL_PICTURE, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        int size = DETAIL_PICTURE * 3 / 4, at = (DETAIL_PICTURE - size) / 2;
        java.awt.Color base = awtColour(colour, 1f), dark = awtColour(colour, 0.35f), light = awtColour(colour, 1.5f);
        g.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(at + size * 0.38f, at + size * 0.36f), size * 0.7f,
                new float[] { 0f, 0.45f, 1f }, new java.awt.Color[] { light, base, dark }));
        g.fillOval(at, at, size, size);
        // (its shine)
        g.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(at + size * 0.33f, at + size * 0.3f), size * 0.16f,
                new float[] { 0f, 1f }, new java.awt.Color[] { new java.awt.Color(255, 255, 255, 220), new java.awt.Color(255, 255, 255, 0) }));
        g.fillOval(at, at, size, size);
        g.dispose();
        return image;
    }

    /** A plant's spikes: a tuft of three, pale at the root and dark at the tip (as fs_flora draws them). */
    private static BufferedImage spikePicture() {
        BufferedImage image = new BufferedImage(DETAIL_PICTURE, DETAIL_PICTURE, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        float n = DETAIL_PICTURE;
        float rootX = n * 0.5f, rootY = n * 0.88f;
        java.awt.Color pale = new java.awt.Color(230, 219, 189), tip = new java.awt.Color(56, 46, 41);
        float[][] spikes = { { -0.42f, 0.62f, 0.07f }, { 0.42f, 0.62f, 0.07f }, { 0f, 0.8f, 0.09f } };
        for (float[] spike : spikes) {
            double angle = spike[0];
            float length = n * spike[1], width = n * spike[2];
            float tx = rootX + (float) Math.sin(angle) * length, ty = rootY - (float) Math.cos(angle) * length;
            float sx = (float) Math.cos(angle) * width, sy = (float) Math.sin(angle) * width;
            java.awt.geom.Path2D.Float shape = new java.awt.geom.Path2D.Float();
            shape.moveTo(rootX - sx, rootY - sy);
            shape.lineTo(tx, ty);
            shape.lineTo(rootX + sx, rootY + sy);
            shape.closePath();
            g.setPaint(new java.awt.GradientPaint(rootX + (tx - rootX) * 0.3f, rootY + (ty - rootY) * 0.3f, pale, rootX + (tx - rootX) * 0.85f, rootY + (ty - rootY) * 0.85f, tip));
            g.fill(shape);
        }
        g.dispose();
        return image;
    }

    private static java.awt.Color awtColour(Vector3 c, float brightness) {
        return new java.awt.Color(Math.min(1f, c.x * brightness), Math.min(1f, c.y * brightness), Math.min(1f, c.z * brightness));
    }

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
        return floraView(gl, s, render, angle, false);
    }

    /** As floraView; fitted, framed to the plant itself (its picture for the map: see Offscreen.captureFitted). */
    private BufferedImage floraView(GL3 gl, int s, int render, float angle, boolean fitted) {
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
        // Drawn on its own, at the origin of its own space, through a view of its own
        // (framed generously, the tallest trees whole: the picture is cut down to the plant afterwards)
        // (shrubs are much smaller than trees, so framed closer: otherwise they come out tiny and blurred)
        float reach = FLORA_CULL_RADIUS * (s < 4 ? 1.0f : 0.2f);
        Matrix4 model = Matrix4Transform.rotateAroundY((float) Math.toDegrees(angle));
        Vector3 centre = new Vector3(0f, reach * 0.7f, 0f);
        float distance = reach * 3.6f;
        Vector3 eye = new Vector3(centre.x + distance * 0.8f, centre.y + distance * 0.25f, centre.z + distance * 0.55f);
        Matrix4 viewProjection = Matrix4.multiply(Matrix4Transform.perspective(40f, 1f, 1f, distance * 4f),
                Matrix4Transform.lookAt(eye, centre, new Vector3(0f, 1f, 0f)));
        final Model trunk = branch, foliage = leaves;
        Vector3 light = new Vector3(0.62f, 0.62f, 0.66f);
        java.util.function.Consumer<Matrix4> draw = zoom -> {
            Matrix4 shown = Matrix4.multiply(zoom, viewProjection);
            gl.glDisable(GL3.GL_CULL_FACE);
            floraShader.use(gl);
            floraBodyUniforms(gl, sc, leafLightColour(sc.healthyColor));
            trunk.setModelMatrix(model);
            trunk.renderWith(gl, light, shown, eye);
            leafShader.use(gl);
            leafShader.setVec3(gl, "u_LeafDarkColor", leafDarkColour(sc.healthyColor));
            leafShader.setVec3(gl, "u_LeafLightColor", leafLightColour(sc.healthyColor));
            foliage.setModelMatrix(model);
            foliage.renderWith(gl, light, shown, eye);
            gl.glEnable(GL3.GL_CULL_FACE);
        };
        return fitted ? Offscreen.captureFitted(gl, render, draw) : Offscreen.capture(gl, render, () -> draw.accept(new Matrix4(1f)));
    }

    /** Developer aid: saves the map's pictures of the plants and animals, each as prefix_plant3.png and the like. */
    public void devSavePortraits(String prefix) {
        java.awt.Image[][] sets = { floraPortraits, speciesPortraits };
        String[] names = { "plant", "animal" };
        for (int k = 0; k < 2; k++) {
            if (sets[k] == null) continue;
            for (int i = 0; i < sets[k].length; i++) {
                if (!(sets[k][i] instanceof BufferedImage image)) continue;
                try {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(prefix + "_" + names[k] + i + ".png"));
                } catch (java.io.IOException e) {
                    System.err.println("Couldn't save a portrait: " + e.getMessage());
                }
            }
        }
        System.out.println("[SHOT] Portraits saved to " + prefix + "_*.png");
    }

    /** Draws each species' picture for the map (with the GL context, once its meshes are made). */
    // The species' pictures as they're drawn (see finishSteps)
    private java.awt.Image[] portraitsMade;

    /** The species' pictures, drawn: for the book and the map. */
    private void showSpeciesPortraits(java.awt.Image[] portraits) {
        speciesPortraits = portraits;
        giveBookPictures();
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

    /** Shows one of the maps of pictures over the map (and whatever heatmap's on it), or none. */
    public void choosePictureMap(FactorName factor) {
        currentPictureMap = factor;
        minimap.setPictureOverlay(factor == null ? null : factor.label(), factor == null ? null : picturesOf(factor));
    }

    public void chooseGradient(Overlay factorName) {
        // (a map of pictures isn't a heatmap: it's shown over one)
        if (factorName != null && PICTURE_MAPS.contains(factorName.factor())) {
            choosePictureMap(factorName.factor());
            return;
        }
        currentDebugFactor = factorName;
        // (the nations' names go in the middle of each country, as the maps of pictures do: so
        // choosing the nations puts any of those away)
        if (factorName != null && factorName.factor() == FactorName.NATIONS && currentPictureMap != null) {
            choosePictureMap(null);
            refreshMapChoices();
        }
        java.awt.Image[] portraits = factorName != null && factorName.factor() == FactorName.FLORA ? floraPortraits : speciesPortraits;
        minimap.setOverlayPicture(factorName != null && factorName.species() >= 0 && portraits != null ? portraits[factorName.species()] : null);
        FactorName factor = factorName == null ? null : factorName.factor();
        // An animal's or plant's range: it's shown turning round beside the map
        if (factorName != null && factorName.species() >= 0) {
            boolean plant = factor == FactorName.FLORA;
            minimap.setPreview(overlayLabel(factorName), plant ? speciesConfigs[factorName.species()].design.kind() : "Animal", null,
                    plant ? detailPicture(factorName.species()) : null);
            previewWanted = factorName;
        } else {
            minimap.setPreview(null, null, null, null);
            previewWanted = null;
        }
        // The key: the heatmap's colours from its lowest value anywhere on the land to its highest,
        // in real units where it has them
        if (factor == FactorName.GRASS_COLOUR) {
            minimap.setLegend(grassColourSpectrum(), new String[] { "Dry", "", "Lush" });
        } else if (factorName != null && factorName.species() >= 0) {
            // A plant's or animal's key: how many in a chunk's area at each end and the middle of its colours
            SpeciesRange range = factorName.factor() == FactorName.FLORA ? floraRanges[factorName.species()]
                    : organismManager.range(factorName.species());
            minimap.setLegend(spectrumFor(factorName), new String[] { heatmapLabel(factorName, range.densityAtShare(0f)),
                    heatmapLabel(factorName, range.densityAtShare(0.5f)), heatmapLabel(factorName, range.densityAtShare(1f)) });
        } else if (factorName != null && heatmapMeasure(factorName) != null) {
            float[] range = heatmapRange(factorName);
            String[] labels = factor == FactorName.GRASS_ABUNDANCE ? new String[] { "Sparsest", "", "Thickest" }
                    : factor == FactorName.WEALTH ? new String[] { "Poorest", "", "Richest" }
                    : new String[] { heatmapLabel(factorName, range[0]), heatmapLabel(factorName, (range[0] + range[1]) * 0.5f),
                            heatmapLabel(factorName, range[1]) };
            minimap.setLegend(spectrumFor(factorName), labels);
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
            float mapZ = (minChunkX + z + 0.5f) * PHYSICAL_CHUNK_SIZE;
            for (int x = 0; x < size; x++) {
                GeodesicChunkGrid.Direction d = MapProjection.direction((minChunkX + x + 0.5f) * PHYSICAL_CHUNK_SIZE, mapZ);
                float fa = soilVariantAFactor.evaluate(d), fb = soilVariantBFactor.evaluate(d);
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
        // (the bollard map has no colours of its own: see chooseGradient)
        if (minimap == null || PICTURE_MAPS.contains(overlay.factor())) return null;
        return gradientCache.computeIfAbsent(overlay, o -> {
            FactorName f = o.factor();
            if (f == FactorName.NATIONS) {
                // Each nation filled in its own colour, chosen to stand apart from all the others,
                // drawn sharp enough for its borders to stay smooth when zoomed in
                BirdsEyeMaps maps = birdsEyeMaps;
                return maps != null ? maps.renderNationOverlay(nationManager) : null;
            }
            BufferedImage heatmap = f == FactorName.SOIL_COLOUR ? soilColourMap()
                    : regionalManager.generateHeatmap(TOTAL_REGION_WIDTH, PHYSICAL_CHUNK_SIZE, factorFor(o), overlayLabel(o), spectrumFor(o));
            // (kept as it is too, a pixel a chunk, for drawing the overlay in tiles when zoomed in)
            rawOverlays.put(o, heatmap);
            return minimap.composeOverlay(heatmap);
        });
    }

    // The light in the shade by day (darkening towards night)
    private static final float[] DAY_AMBIENT = { 0.40f, 0.38f, 0.35f };

    /**
     * The grass colour map's colours: the grass's own as it looks by day, from its colour where
     * it grows worst to where it grows best (as the grass shader mixes and lights them, see
     * fs_grass_instanced).
     */
    private float[][] grassColourSpectrum() {
        WorldPalette palette = worldArt != null ? worldArt.palette() : new WorldPalette(worldSeed);
        float[][] spectrum = new float[4][3];
        for (int k = 0; k < 4; k++) {
            for (int c = 0; c < 3; c++) {
                float light = Math.min(1.5f, 3f * DAY_AMBIENT[c]);
                spectrum[k][c] = Math.min(1f, (palette.grassDry[c] + (palette.grassLush[c] - palette.grassDry[c]) * k / 3f) * light);
            }
        }
        return spectrum;
    }

    // The wealth overlay: how well off people are, from each nation's richness, more so in town
    private RegionalFactor wealthOverlay;
    // Its colours, poorest to richest: dull brown through to gold
    private static final float[][] WEALTH_SPECTRUM = {
        { 0.30f, 0.22f, 0.18f }, { 0.58f, 0.48f, 0.40f }, { 0.85f, 0.75f, 0.45f }, { 1.00f, 0.82f, 0.10f }
    };
    // Where each regional soil colour starts and finishes blending in (from its factor's spread of values)
    private volatile float[][] soilBlendRanges;

    /**
     * What a heatmap colours the land by, 0 to 1: its measure (see heatmapMeasure) from the
     * lowest it is anywhere on the land the map shows (0, the key's first colour) to the highest
     * (1, its last). The grass colour map is the grass's own colour instead.
     */
    private RegionalFactor factorFor(Overlay overlay) {
        if (overlay.factor() == FactorName.GRASS_COLOUR) {
            float[] span = grassColourSpan();
            return new RegionalFactor(1f, d -> grassShare(span, grassColourFactor.evaluate(d)));
        }
        if (overlay.species() >= 0) {
            // A plant or animal: coloured by where it comes on its range (red its outer third,
            // where there are barely any; green its heartland), as it's spawned
            int s = overlay.species();
            if (overlay.factor() == FactorName.FLORA) return new RegionalFactor(1f, d -> floraRanges[s].share(floraRaw(s, d)));
            return new RegionalFactor(1f, d -> organismManager.rangeShare(s, SphereMath.atAltitude(d, 0.0)));
        }
        RegionalFactor.Sampler measure = heatmapMeasure(overlay);
        if (measure == null) return null;
        float[] range = heatmapRange(overlay);
        float span = Math.max(1e-6f, range[1] - range[0]);
        return new RegionalFactor(1f, d -> Math.max(0f, Math.min(1f, (measure.sample(d) - range[0]) / span)));
    }

    /**
     * What a heatmap measures in a direction, in the units its key gives: a plant's or animal's
     * count in a chunk's area (100 by 100) there (as they're spawned: a plant's is its abundance
     * times its base; an animal's, see expectedPerChunk), the rocks' (count and typical width in
     * metres), the grass's height in centimetres, degrees Celsius, millimetres of rain a year; or
     * for the grass's abundance and wealth, their own 0 to 1. Null for the maps not on a scale.
     */
    private RegionalFactor.Sampler heatmapMeasure(Overlay overlay) {
        if (overlay.species() >= 0 && overlay.factor() == FactorName.FLORA) {
            int s = overlay.species();
            return d -> floraRanges[s].density(floraRaw(s, d));
        }
        if (overlay.species() >= 0) {
            int i = overlay.species();
            return d -> organismManager.expectedPerChunk(i, SphereMath.atAltitude(d, 0.0));
        }
        return switch (overlay.factor()) {
            case ROCKS -> d -> rocks().expectedPerChunk(SphereMath.atAltitude(d, 0.0));
            // (a unit being ten centimetres, and the size a radius)
            case ROCK_SIZE -> d -> rocks().typicalSizeAt(SphereMath.atAltitude(d, 0.0)) * 2f / PlayerBody.METRE;
            case GRASS_ABUNDANCE -> grassAbundanceFactor::evaluate;
            // (as the blades are given their heights, see the grass's gathering)
            case GRASS_HEIGHT -> d -> bladeHeight(grassShare(grassHeightSpan(), grassHeightFactor.evaluate(d))) * GRASS_BLADE_HEIGHT / PlayerBody.METRE * 100f;
            case TEMPERATURE -> d -> -30f + 70f * regionalManager.temperatureMap.evaluate(d);
            case RAINFALL -> d -> regionalManager.rainfallMap.evaluate(d) * RAINFALL_FULL_MM;
            case WEALTH -> {
                if (wealthOverlay == null) {
                    wealthOverlay = new RegionalFactor(1f, d -> {
                        int nation = nationManager.getNationAt(d);
                        return nation <= 0 ? 0f : infraManager.areaRichness(nation, settlementManager.getUrbanness(d), SphereMath.atAltitude(d, 0.0));
                    });
                }
                yield wealthOverlay::evaluate;
            }
            default -> null;
        };
    }

    /** A value of a heatmap's measure for its key, with its units. */
    private String heatmapLabel(Overlay overlay, float value) {
        if (overlay.species() >= 0) return countLabel(value * chunksPerKm2()) + " /km\u00B2";
        return switch (overlay.factor()) {
            case ROCKS -> countLabel(value * chunksPerKm2()) + " /km\u00B2";
            case ROCK_SIZE -> String.format("%.1f m", value);
            case GRASS_HEIGHT -> Math.round(value) + " cm";
            case TEMPERATURE -> Math.round(value) + " \u00B0C";
            case RAINFALL -> Math.round(value) + " mm/yr";
            default -> String.format("%.2f", value);
        };
    }

    /** A heatmap's colours, lowest to highest. */
    private float[][] spectrumFor(Overlay overlay) {
        return switch (overlay.factor()) {
            case TEMPERATURE -> RegionalGenerationManager.TEMPERATURE_SPECTRUM;
            case RAINFALL -> RegionalGenerationManager.RAINFALL_SPECTRUM;
            case WEALTH -> WEALTH_SPECTRUM;
            case ROCKS, ROCK_SIZE -> ROCK_SPECTRUM;
            case GRASS_COLOUR -> grassColourSpectrum();
            default -> RANGE_SPECTRUM;
        };
    }

    // Mouse look gathered on the UI thread, applied at the start of the next frame
    private float lookYaw, lookPitch;

    /** Turns the view (more finely the more it's zoomed in, so it holds as steady); safe to call from any thread. */
    public synchronized void look(float yaw, float pitch) {
        lookYaw += yaw / zoomWanted;
        lookPitch += pitch / zoomWanted;
    }

    private synchronized void applyLook() {
        if (lookYaw != 0f || lookPitch != 0f) camera.updateYawPitch(lookYaw, lookPitch);
        lookYaw = 0f;
        lookPitch = 0f;
    }

    // A grass blade's height in world units, before each blade's own height scales it
    private static final float GRASS_BLADE_HEIGHT = 4.8f;
    // How far a blade's root is sunk per unit of slope (rise over run): about a typical blade's half width
    private static final float GRASS_SLOPE_SINK = 0.8f;
    // The grass's height where it grows shortest and tallest, in metres (before each blade's own variation)
    private static final float GRASS_SHORTEST = 0.07f, GRASS_TALLEST = 1.0f;
    // The lowest and highest the grass's height and colour factors reach on the land, once worked out
    private volatile float[] grassHeightSpan, grassColourSpan;

    /** A grass blade's height (as the blade's scale, see GRASS_BLADE_HEIGHT) where its height share is share (0 shortest, 1 tallest). */
    private static float bladeHeight(float share) {
        return (GRASS_SHORTEST + (GRASS_TALLEST - GRASS_SHORTEST) * share) * PlayerBody.METRE / GRASS_BLADE_HEIGHT;
    }

    /** Where a value sits in a span, 0 to 1. */
    private static float grassShare(float[] span, float value) {
        return Math.max(0f, Math.min(1f, (value - span[0]) / Math.max(1e-4f, span[1] - span[0])));
    }

    private float[] grassHeightSpan() {
        float[] span = grassHeightSpan;
        if (span == null) grassHeightSpan = span = landSpan(grassHeightFactor);
        return span;
    }

    private float[] grassColourSpan() {
        float[] span = grassColourSpan;
        if (span == null) grassColourSpan = span = landSpan(grassColourFactor);
        return span;
    }

    /** The lowest and highest a factor reaches on the land the map shows, sampled across it. */
    private float[] landSpan(RegionalFactor factor) {
        float[] span = { Float.MAX_VALUE, -Float.MAX_VALUE };
        forEachMapLand(28_800, d -> {
            float v = factor.evaluate(d);
            span[0] = Math.min(span[0], v);
            span[1] = Math.max(span[1], v);
        });
        return span[0] > span[1] ? new float[] { 0f, 1f } : span;
    }

    /**
     * Each of about count places spread evenly over the planet between the map's northern and
     * southern edges (MapProjection.CLIP_LATITUDE) that is on land, in turn.
     */
    private void forEachMapLand(int count, java.util.function.Consumer<GeodesicChunkGrid.Direction> visit) {
        double golden = Math.PI * (3 - Math.sqrt(5)), top = Math.sin(MapProjection.CLIP_LATITUDE);
        for (int i = 0; i < count; i++) {
            double y = top * (1 - 2 * (i + 0.5) / count), across = Math.sqrt(1 - y * y), longitude = golden * i;
            GeodesicChunkGrid.Direction d = new GeodesicChunkGrid.Direction(across * Math.cos(longitude), y, -across * Math.sin(longitude));
            if (TerrainMesh.getLayeredHeight(d, worldNoise) > seaLevelHeight) visit.accept(d);
        }
    }

    // Points round each plant model {x, y, z, ...} (species, then variation), as generated
    private float[][][] floraOutlines;
    // The scene's last view-projection (column major), for finding what's seen through the magnifying glass
    private float[] lastSceneViewProjection;
    private boolean bookPicturesGiven;

    /**
     * The highest ground on the map, in metres above the sea. Found at places spread evenly
     * over it, then each of the highest few dozen climbed to the top of its hill in finer and
     * finer steps.
     */
    private float highestGround() {
        // Many places first, keeping the highest few dozen (each a likely different hill)...
        int keep = 40, count = 1_000_000;
        java.util.PriorityQueue<Object[]> tops = new java.util.PriorityQueue<>((p, q) -> Float.compare((Float) p[1], (Float) q[1]));
        double golden = Math.PI * (3 - Math.sqrt(5)), top = Math.sin(MapProjection.CLIP_LATITUDE);
        for (int i = 0; i < count; i++) {
            double y = top * (1 - 2 * (i + 0.5) / count);
            SpherePosition p = Planet.position(Math.asin(y), golden * i, 0.0);
            float h = TerrainMesh.getLayeredHeight(p, worldNoise);
            if (tops.size() < keep) tops.add(new Object[] { p, h });
            else if (h > (Float) tops.peek()[1]) {
                tops.poll();
                tops.add(new Object[] { p, h });
            }
        }
        // ...then each climbed to the top of its hill, in finer and finer steps (from about the
        // spacing of the places looked at)
        double spacing = Math.sqrt(4 * Math.PI * top / count) * Planet.radius();
        float best = -Float.MAX_VALUE;
        for (Object[] start : tops) {
            SpherePosition at = (SpherePosition) start[0];
            float height = (Float) start[1];
            for (double step = spacing; step > 0.5; step *= 0.5) {
                boolean moved = true;
                while (moved) {
                    moved = false;
                    for (int k = 0; k < 16; k++) {
                        SpherePosition p = SphereMath.move(at, k * Math.PI / 8, step);
                        if (Math.abs(Planet.latitude(p)) > MapProjection.CLIP_LATITUDE) continue;
                        float h = TerrainMesh.getLayeredHeight(p, worldNoise);
                        if (h > height) {
                            height = h;
                            at = p;
                            moved = true;
                        }
                    }
                }
            }
            best = Math.max(best, height);
        }
        return Math.max(0f, best - seaLevelHeight) / PlayerBody.METRE;
    }

    /** The altimeter's top: the next multiple of 10 metres at or above x (128 gives 130, 109 gives 110). */
    private static float roundUp(float x) {
        return Math.max(10f, (float) Math.ceil(x / 10f - 1e-6) * 10f);
    }

    /** Once both are drawn, two of the animals' and two of the plants' pictures for the identification kit's book. */
    private void giveBookPictures() {
        java.awt.Image[] animals = speciesPortraits, plants = floraPortraits;
        if (bookPicturesGiven || animals == null || plants == null) return;
        bookPicturesGiven = true;
        java.util.Random rand = new java.util.Random(worldSeed * 977L + 3L);
        List<java.awt.Image> pictures = new ArrayList<>();
        for (java.awt.Image[] kind : new java.awt.Image[][] { animals, plants }) {
            List<java.awt.Image> some = new ArrayList<>();
            for (java.awt.Image picture : kind) if (picture != null) some.add(picture);
            java.util.Collections.shuffle(some, rand);
            for (int i = 0; i < 2; i++) pictures.add(i < some.size() ? some.get(i) : null);
        }
        playerBody.setBookPictures(pictures, worldSeed);
    }

    // The least share of the magnifying glass's lens something must fill (as a circle round
    // what's seen of it, on screen) to be made out and named: far things, too small in it, aren't
    private static final float IDENTIFY_SHARE = 0.05f;
    // At most this many named at once, nearest first
    private static final int MOST_IDENTIFIED = 6;
    private String lensSignature = "";
    // Something seen through the lens stays named (where it is now) for this many frames after
    // last passing the test, so it doesn't flicker as the view moves; and when each last passed
    private static final int LENS_HOLD_FRAMES = 8;
    private final Map<Object, Integer> lensLastSeen = new java.util.IdentityHashMap<>();
    private int lensFrame;

    /** One plant or animal considered through the lens: its name, something that stays the same for it, and points round it. */
    @FunctionalInterface
    private interface LensCandidate {
        void consider(String name, Object key, float[] points);
    }

    /**
     * While the identification kit's magnifying glass is held up: each animal and (non grass)
     * plant seen through it, big enough in it to make out, boxed (round what's seen of it on
     * the screen) and named on the HUD.
     */
    private void drawLensFinds(float scale) {
        float[] lens = playerBody.lensView();
        float[] vp = lastSceneViewProjection;
        organismManager.setKeepOutlines(lens != null);
        if (lens == null || vp == null) {
            hud.setVisible("lens", false);
            lensSignature = "";
            return;
        }
        // Everything here is in the camera's local frame (the eyes at the origin, x east, y up,
        // z south), as the body holding the lens is posed and as vp projects
        SphereFrame here = camera.frame();
        float[] centre = project(vp, lens[0], lens[1], lens[2]);
        float[] forward = camera.getLocalForward();
        float[] right = Affine.normalise(Affine.cross(forward, new float[] { 0f, 1f, 0f }));
        float[] edge = project(vp, lens[0] + right[0] * lens[3], lens[1] + right[1] * lens[3], lens[2] + right[2] * lens[3]);
        if (centre == null || edge == null) return;
        float lensR = (float) Math.hypot(edge[0] - centre[0], edge[1] - centre[1]);

        // Every candidate: {distance, left, top, right, bottom} on screen, and its name
        List<float[]> boxes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        // (its name, something to know it by, and points round it in the local frame {x, y, z, ...})
        lensFrame++;
        LensCandidate consider = (name, key, points) -> {
            if (points[0] * points[0] + points[2] * points[2] > 2500f * 2500f) return;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float sumX = 0f, sumY = 0f, sumZ = 0f;
            int n = points.length / 3;
            for (int k = 0; k < points.length; k += 3) {
                float px = points[k], py = points[k + 1], pz = points[k + 2];
                float[] p = project(vp, px, py, pz);
                if (p == null) return;
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
                sumX += px;
                sumY += py;
                sumZ += pz;
            }
            // Only what's on the screen
            minX = Math.max(0f, minX);
            minY = Math.max(0f, minY);
            maxX = Math.min(screenWidth - 1f, maxX);
            maxY = Math.min(screenHeight - 1f, maxY);
            if (maxX - minX < 2f || maxY - minY < 2f) return;
            // Seen as a circle round it: how much of the lens it fills, counting only the share of it
            // in the lens that nothing nearer hides (anything at all: buildings, trees, the land)
            float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f;
            float r = (float) Math.hypot(maxX - minX, maxY - minY) * 0.5f;
            float needed = IDENTIFY_SHARE * (float) Math.PI * lensR * lensR;
            float overlap = circleOverlap(cx - centre[0], cy - centre[1], r, lensR);
            float mx = sumX / n, my = sumY / n, mz = sumZ / n;
            boolean passes = overlap >= needed && overlap * unhiddenShare(vp, points, centre, lensR) >= needed;
            // ...and not hidden behind the land
            for (int i = 1; i < 16 && passes; i++) {
                float t = i / 16f;
                SpherePosition along = here.toWorld(mx * t, my * t, mz * t);
                if (TerrainMesh.getLayeredHeight(along, worldNoise) > SphereMath.altitude(along) + 1f) passes = false;
            }
            if (passes) lensLastSeen.put(key, lensFrame);
            Integer last = lensLastSeen.get(key);
            if (last == null || lensFrame - last > LENS_HOLD_FRAMES) return;
            boxes.add(new float[] { (float) Math.hypot(mx, mz), minX, minY, maxX, maxY });
            names.add(name);
        };
        organismManager.forEachOutline((s, points) -> {
            float[] local = new float[points.length];
            for (int k = 0; k < points.length; k += 3) {
                double[] l = here.toLocal(new SpherePosition(points[k], points[k + 1], points[k + 2]));
                local[k] = (float) l[0];
                local[k + 1] = (float) l[1];
                local[k + 2] = (float) l[2];
            }
            consider.consider(organismManager.speciesName(s), points, local);
        });
        if (floraOutlines != null) {
            trees.forEachNear(here.origin(), 2500.0, tree -> {
                float[] outline = floraOutlines[tree.speciesIndex()][tree.modelIndex()];
                if (outline == null) return;
                // Placed as it's drawn: turned, scaled and stood where it grows (see plant)
                SphereFrame stands = SphereFrame.at(tree.position(), Math.toRadians(tree.rotationY()));
                float[] local = new float[outline.length];
                for (int k = 0; k < outline.length; k += 3) {
                    double[] l = here.toLocal(stands.toWorld(outline[k] * tree.scale(), outline[k + 1] * tree.scale(), outline[k + 2] * tree.scale()));
                    local[k] = (float) l[0];
                    local[k + 1] = (float) l[1];
                    local[k + 2] = (float) l[2];
                }
                consider.consider(floraName(tree.speciesIndex()), tree, local);
            });
        }
        // (forgetting what's not been seen for a while)
        lensLastSeen.values().removeIf(last -> lensFrame - last > LENS_HOLD_FRAMES);
        // The nearest few
        Integer[] order = new Integer[boxes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(boxes.get(a)[0], boxes.get(b)[0]));
        int count = Math.min(MOST_IDENTIFIED, order.length);

        StringBuilder signature = new StringBuilder();
        for (int k = 0; k < count; k++) {
            float[] b = boxes.get(order[k]);
            signature.append('|').append(names.get(order[k])).append(Math.round(b[1])).append(',').append(Math.round(b[2]))
                    .append(',').append(Math.round(b[3])).append(',').append(Math.round(b[4]));
        }
        if (signature.toString().equals(lensSignature)) {
            hud.setVisible("lens", count > 0);
            return;
        }
        lensSignature = signature.toString();
        if (count == 0) {
            hud.setVisible("lens", false);
            return;
        }
        // Each box with its name: on a tab just above the box, or inside its top should there be
        // no room above (so the whole of both is always on the screen)
        java.awt.Font font = HudStyle.font(java.awt.Font.BOLD, 14f * scale);
        java.awt.FontMetrics fm = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontMetrics(font);
        float th = fm.getAscent() + 6 * scale;
        float[][] tabs = new float[count][];
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, rightEdge = 0f, bottom = 0f;
        for (int k = 0; k < count; k++) {
            float[] b = boxes.get(order[k]);
            float tw = fm.stringWidth(names.get(order[k])) + 10 * scale;
            float tx = Math.min(b[1], screenWidth - tw), ty = b[2] - th - 2 * scale;
            if (ty < 0f) ty = b[2] + 2 * scale;
            tabs[k] = new float[] { Math.max(0f, tx), ty, tw };
            left = Math.min(left, Math.min(b[1], tabs[k][0]));
            top = Math.min(top, Math.min(b[2], ty));
            rightEdge = Math.max(rightEdge, Math.max(b[3], tabs[k][0] + tw));
            bottom = Math.max(bottom, b[4]);
        }
        int ox = (int) Math.floor(left) - 4, oy = (int) Math.floor(top) - 4;
        int w = (int) Math.ceil(rightEdge) - ox + 5, h = (int) Math.ceil(bottom) - oy + 5;
        BufferedImage image = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        HudStyle.smooth(g);
        g.translate(-ox, -oy);
        g.setFont(font);
        for (int k = 0; k < count; k++) {
            float[] b = boxes.get(order[k]);
            java.awt.geom.Rectangle2D.Float box = new java.awt.geom.Rectangle2D.Float(b[1], b[2], b[3] - b[1], b[4] - b[2]);
            g.setStroke(new java.awt.BasicStroke(4f * scale));
            g.setColor(new java.awt.Color(0, 0, 0, 150));
            g.draw(box);
            g.setStroke(new java.awt.BasicStroke(2f * scale));
            g.setColor(HudStyle.ACCENT);
            g.draw(box);
            float[] tab = tabs[k];
            g.setColor(new java.awt.Color(10, 14, 22, 205));
            g.fill(new java.awt.geom.RoundRectangle2D.Float(tab[0], tab[1], tab[2], th, 6 * scale, 6 * scale));
            g.setColor(HudStyle.VALUE);
            g.drawString(names.get(order[k]), tab[0] + 5 * scale, tab[1] + th - 4 * scale);
        }
        g.dispose();
        hud.put("lens", image, ox, oy);
    }

    // The scene's depth over the magnifying glass's lens, read each frame while it's held up (in
    // the scene's own pixels, rows from the bottom), where that patch is, and the planes then
    private float[] lensDepth;
    private int lensDepthX, lensDepthY, lensDepthW, lensDepthH;
    private float lensDepthNear;
    private java.nio.FloatBuffer lensDepthBuffer;

    /** Reads the scene's depth over the lens (the body isn't drawn yet), or forgets it if the glass isn't held up. */
    private void captureLensDepth(GL3 gl) {
        float[] lens = playerBody.lensView();
        float[] vp = lastSceneViewProjection;
        if (lens == null || vp == null || screenWidth <= 0) {
            lensDepth = null;
            return;
        }
        float[] centre = project(vp, lens[0], lens[1], lens[2]);
        if (centre == null) {
            lensDepth = null;
            return;
        }
        float toScene = currentWidth / (float) screenWidth;
        // (generously round the lens: it's taken from last frame's pose)
        int radius = Math.round(currentHeight * 0.4f);
        int cx = Math.round(centre[0] * toScene), cy = currentHeight - Math.round(centre[1] * currentHeight / (float) screenHeight);
        int x0 = Math.max(0, cx - radius), y0 = Math.max(0, cy - radius);
        int w = Math.min(currentWidth, cx + radius) - x0, h = Math.min(currentHeight, cy + radius) - y0;
        if (w <= 0 || h <= 0) {
            lensDepth = null;
            return;
        }
        if (lensDepthBuffer == null || lensDepthBuffer.capacity() < w * h) lensDepthBuffer = com.jogamp.common.nio.Buffers.newDirectFloatBuffer(w * h);
        lensDepthBuffer.clear();
        gl.glPixelStorei(GL3.GL_PACK_ALIGNMENT, 4);
        gl.glReadPixels(x0, y0, w, h, GL3.GL_DEPTH_COMPONENT, GL3.GL_FLOAT, lensDepthBuffer);
        float[] depth = lensDepth != null && lensDepth.length == w * h ? lensDepth : new float[w * h];
        lensDepthBuffer.get(depth, 0, w * h);
        lensDepth = depth;
        lensDepthX = x0;
        lensDepthY = y0;
        lensDepthW = w;
        lensDepthH = h;
        lensDepthNear = currentNearPlane;
    }

    /**
     * Of something's points that fall within the lens, the share nothing nearer hides: each
     * point's distance from the eyes against the scene's depth there, allowing for the thing's
     * own size (so its far side isn't counted as hidden by its near side). 1 if the depth isn't known.
     */
    private float unhiddenShare(float[] vp, float[] points, float[] lensCentre, float lensR) {
        float[] depth = lensDepth;
        if (depth == null) return 1f;
        // How big it is: its points' spread
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int k = 0; k < points.length; k += 3) {
            minX = Math.min(minX, points[k]);
            maxX = Math.max(maxX, points[k]);
            minY = Math.min(minY, points[k + 1]);
            maxY = Math.max(maxY, points[k + 1]);
            minZ = Math.min(minZ, points[k + 2]);
            maxZ = Math.max(maxZ, points[k + 2]);
        }
        float size = (float) Math.sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY) + (maxZ - minZ) * (maxZ - minZ));
        float near = lensDepthNear, far = FAR_PLANE;
        float toSceneX = currentWidth / (float) screenWidth, toSceneY = currentHeight / (float) screenHeight;
        int inLens = 0, unhidden = 0;
        for (int k = 0; k < points.length; k += 3) {
            float px = points[k], py = points[k + 1], pz = points[k + 2];
            float w = vp[3] * px + vp[7] * py + vp[11] * pz + vp[15];
            if (w <= 0.01f) continue;
            float sx = ((vp[0] * px + vp[4] * py + vp[8] * pz + vp[12]) / w + 1f) * 0.5f * screenWidth;
            float sy = (1f - (vp[1] * px + vp[5] * py + vp[9] * pz + vp[13]) / w) * 0.5f * screenHeight;
            if (Math.hypot(sx - lensCentre[0], sy - lensCentre[1]) > lensR) continue;
            int rx = Math.round(sx * toSceneX) - lensDepthX, ry = currentHeight - 1 - Math.round(sy * toSceneY) - lensDepthY;
            if (rx < 0 || ry < 0 || rx >= lensDepthW || ry >= lensDepthH) continue;
            inLens++;
            // The scene's depth there, as a distance ahead of the eyes (as w is)
            float ndc = depth[ry * lensDepthW + rx] * 2f - 1f;
            float sceneDistance = 2f * near * far / (far + near - ndc * (far - near));
            if (sceneDistance >= w - size * 0.5f - 2f) unhidden++;
        }
        return inLens == 0 ? 0f : unhidden / (float) inLens;
    }

    /** Where a point in the world lands on the screen, in its pixels from the top left; null if it's behind the eyes. */
    private float[] project(float[] vp, float x, float y, float z) {
        float cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12];
        float cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13];
        float w = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
        if (w <= 0.01f) return null;
        return new float[] { (cx / w + 1f) * 0.5f * screenWidth, (1f - cy / w) * 0.5f * screenHeight };
    }

    /** The area two circles share: one of radius r with its middle (dx, dy) from the other's, of radius big. */
    private static float circleOverlap(float dx, float dy, float r, float big) {
        double d = Math.hypot(dx, dy);
        if (d >= r + big) return 0f;
        if (d <= Math.abs(big - r)) return (float) (Math.PI * Math.min(r, big) * Math.min(r, big));
        double a = r * r * Math.acos((d * d + r * r - big * big) / (2 * d * r));
        double b = big * big * Math.acos((d * d + big * big - r * r) / (2 * d * big));
        double c = 0.5 * Math.sqrt((-d + r + big) * (d + r - big) * (d - r + big) * (d + r + big));
        return (float) (a + b - c);
    }

    // ------------------------------------------------------------------ messages from Xenocorp in play

    // The share of the planet's whole surface under the sea (percent), and April's sentence
    // saying it, once worked out
    private volatile double aprilPercent = Double.NaN;
    private final java.util.concurrent.CompletableFuture<PlanetVoice.Speech> aprilSpeech = new java.util.concurrent.CompletableFuture<>();

    /**
     * How much of the planet's whole surface (to the poles, beyond the map) is ocean, in
     * percent: two million points spread evenly over the sphere, each land or sea.
     */
    private double oceanPercent() {
        int n = 2_000_000;
        double golden = Math.PI * (3 - Math.sqrt(5));
        double limit = Math.toRadians(89.99);
        long sea = java.util.stream.IntStream.range(0, n).parallel().filter(i -> {
            double y = 1 - 2 * (i + 0.5) / n;
            double latitude = Math.max(-limit, Math.min(limit, Math.asin(y)));
            double longitude = (golden * i) % (2 * Math.PI) - Math.PI;
            return TerrainMesh.getLayeredHeight(Planet.position(latitude, longitude, 0.0), worldNoise) <= seaLevelHeight;
        }).count();
        return 100.0 * sea / n;
    }

    // ------------------------------------------------------------------ sprinting and stamina

    // Holding Shift while walking sprints at SPRINT_SPEED times the walk, while there's stamina (0
    // to 1): a full bar lasts STAMINA_DRAIN_SECONDS of sprinting and refills from nothing in
    // STAMINA_REFILL_SECONDS, starting STAMINA_REFILL_DELAY after the last sprint. Run it dry and
    // the player's winded: no sprinting until it's back up to STAMINA_WINDED_UNTIL
    private static final float SPRINT_SPEED = 1.5f;
    private static final float STAMINA_DRAIN_SECONDS = 6f, STAMINA_REFILL_SECONDS = 8f, STAMINA_REFILL_DELAY = 1f, STAMINA_WINDED_UNTIL = 0.3f;
    // With the oxygen downgraded, sprinting this long takes exertion from nothing to the most
    private static final float SPRINT_EXERTION_SECONDS = 3f;
    private volatile float stamina = 1f;
    private float sinceSprint;
    private boolean winded;

    /** Uses up or refills stamina this frame; whether the player's sprinting (wanting to, with stamina to do it). */
    private boolean updateStamina(double deltaTime, boolean wantsToSprint) {
        float dt = (float) deltaTime;
        boolean sprinting = wantsToSprint && !winded && stamina > 0f;
        if (sprinting) {
            stamina = Math.max(0f, stamina - dt / STAMINA_DRAIN_SECONDS);
            sinceSprint = 0f;
            if (stamina == 0f) winded = true;
        } else {
            sinceSprint += dt;
            if (sinceSprint >= STAMINA_REFILL_DELAY) stamina = Math.min(1f, stamina + dt / STAMINA_REFILL_SECONDS);
            if (winded && stamina >= STAMINA_WINDED_UNTIL) winded = false;
        }
        return sprinting;
    }

    // ------------------------------------------------------------------ the step bob

    // The eyes dip at each footfall and rise between: a step every STEP_LENGTH world units walked
    // (a 0.8 m pace walking, 1 m running: some 2.5 steps a second walking, 3 sprinting),
    // BOB_WALK high walking and BOB_RUN sprinting. It eases in over a step or so when the player
    // sets off and out when they stop, so neither jolts
    private static final float STEP_LENGTH_WALK = 16f, STEP_LENGTH_RUN = 20f;
    private static final float BOB_WALK = 0.35f, BOB_RUN = 1.1f, BOB_EASE = 6f;
    // How far the eyes are off their height this frame for the step; how far through the steps
    // (radians, a step each pi); and how much of the bob shows, 0 standing to 1 walking, eased
    private float headBob, stepPhase, bobWeight, bobHeight = BOB_WALK;

    /** Moves the step bob on this frame: walking (on foot, on the ground), and whether at a sprint. */
    private void updateHeadBob(double deltaTime, boolean walking, boolean sprinting) {
        float dt = (float) deltaTime;
        float ease = Math.min(1f, BOB_EASE * dt);
        bobWeight += ((walking ? 1f : 0f) - bobWeight) * ease;
        bobHeight += ((sprinting ? BOB_RUN : BOB_WALK) - bobHeight) * ease;
        if (walking) {
            float speed = camera.KEYBOARD_SPEED * (sprinting ? SPRINT_SPEED : 1f);
            stepPhase = (stepPhase + (float) Math.PI * speed * dt / (sprinting ? STEP_LENGTH_RUN : STEP_LENGTH_WALK)) % (float) (2.0 * Math.PI);
        }
        // |sin| peaks between footfalls and dips sharply on each; less its average (2/pi), so the
        // eyes ride as high as ever on the whole
        float step = (float) Math.abs(Math.sin(stepPhase)) - (float) (2.0 / Math.PI);
        headBob = bobWeight < 0.001f ? 0f : bobHeight * bobWeight * step;
    }

    // ------------------------------------------------------------------ the oxygen's downgrade (M52)

    private final OxygenBlur oxygenBlur = new OxygenBlur();
    // Whether the oxygen's been downgraded (it stays so for the rest of the game); how out of
    // breath the player is, 0 to 1 (up while sprinting, down while walking or standing); and how
    // light-headed that makes them, 0 to 1, and the time it sways by
    private volatile boolean oxygenDowngraded;
    private float exertion, oxygenStrength, oxygenTime;
    // Not sprinting, this many seconds brings exertion back from the most to nothing. It shows
    // from EXERTION_SHOWS on, at its worst at the most
    private static final float EXERTION_CLEAR_SECONDS = 30f, EXERTION_SHOWS = 0.3f;
    // Where the player can't walk (GameMode.NO_MOVE...), it's always this bad
    private static final float STILL_STRENGTH = 0.45f;

    /** The oxygen's downgraded, for the rest of the game. */
    public void downgradeOxygen() {
        oxygenDowngraded = true;
        System.out.println("[OXYGEN] Downgraded");
    }

    /**
     * How light-headed the player is this frame (or always, where they can't walk). Walking
     * leaves them be; sprinting with the oxygen downgraded makes them dizzy within moments, and
     * running out of stamina leaves them as dizzy as they get.
     */
    private void updateOxygen(double deltaTime, boolean sprinting, double time) {
        if (!oxygenDowngraded) {
            oxygenStrength = 0f;
            return;
        }
        oxygenTime = (float) time;
        if (!GameMode.current().moving) {
            oxygenStrength = STILL_STRENGTH;
            return;
        }
        float dt = (float) deltaTime;
        float change = sprinting ? dt / SPRINT_EXERTION_SECONDS : -dt / EXERTION_CLEAR_SECONDS;
        exertion = Math.max(0f, Math.min(1f, exertion + change));
        if (winded) exertion = 1f;
        float t = Math.max(0f, Math.min(1f, (exertion - EXERTION_SHOWS) / (1f - EXERTION_SHOWS)));
        oxygenStrength = 0.9f * t * t * (3f - 2f * t);
    }

    /** April's message from Xenocorp, saying how much of the planet is ocean: handed to show once it's ready to play. */
    public void startAprilMessage(java.util.function.Consumer<TransmissionMessage> show) {
        Thread start = new Thread(() -> {
            // (the ocean's worked out a few rounds before this; should it somehow not be yet, it's waited for)
            PlanetVoice.Speech said = aprilSpeech.join();
            TransmissionMessage message = TransmissionMessage.load(TransmissionMessage.APRIL,
                    String.format(java.util.Locale.ROOT, "%.3f%%", aprilPercent), java.util.concurrent.CompletableFuture.completedFuture(said));
            if (message != null) show.accept(message);
        }, "april-message");
        start.setDaemon(true);
        start.start();
    }

    // The sound of the weather where the player is; and whether something covers the world (the shop before the first round)
    private final RainSound rainSound = new RainSound();
    // (hidden to begin with: the world is made behind the cockpit, and isn't heard until it's entered)
    private volatile boolean worldHidden = true;

    /** Whether a round is being played in the world just now: it's shown, built and not loading the next. */
    public boolean roundInPlay() {
        return worldReady && !worldHidden && !roundLoading && !teleportLoading;
    }

    public void setWorldHidden(boolean hidden) {
        worldHidden = hidden;
    }

    // Whether the controls are on offer (after January's message: see XenoGuesser.offerControls)
    private volatile boolean controlsOffered;

    /** Shows or hides the offer of the controls over the game. */
    public void setControlsOffered(boolean offered) {
        controlsOffered = offered;
    }

    /** Stops the weather's sound (the game's over). */
    public void stopSounds() {
        rainSound.stop();
    }


    /** C: the player takes out the compass and looks at it for a moment. */
    public void useCompass() {
        compassWanted = true;
    }

    /** 2: the player takes out the thermometer and reads it for a moment. */
    public void useThermometer() {
        thermometerWanted = true;
    }

    private volatile boolean thermometerWanted, altimeterWanted, kitWanted;

    /**
     * What a thermometer reads at (x, z), in degrees Celsius: the place's warmth as the
     * temperature overlay shows it (warmer towards the equator and inland in the tropics, colder
     * up high, varying from region to region), warmer in that hemisphere's summer and colder in
     * its winter (the more so the further from the equator), and warmest in the mid afternoon,
     * coolest round dawn, by this round's sun.
     */
    // The climate for the snow over the whole world (see snowClimate): a cube map (SphereRaster)
    // this many texels a face's side, its texture unit, and how many degrees below freezing it
    // takes for snow to lie thickly
    private static final int SNOW_COVER_SIDE = 256, SNOW_UNIT = Shader.SNOW_UNIT;
    private static final float SNOW_LIES_THICK = 5f;
    private java.util.concurrent.Future<byte[]> snowCoverJob;
    private int snowCoverTexture;

    /**
     * The climate in a direction as the snow needs it, each 0 to 1 for the texture: the
     * temperature as it would be at sea level (from -60 to 40 Celsius; the shader takes off for
     * height), and how much rain or snow falls.
     */
    private float[] snowClimate(GeodesicChunkGrid.Direction d) {
        float warmth = regionalManager.temperatureMap.evaluate(d) + regionalManager.altitudeChill(d);
        float celsius = -30f + 70f * warmth;
        return new float[] { Math.max(0f, Math.min(1f, (celsius + 60f) / 100f)), regionalManager.rainfallMap.evaluate(d) };
    }

    // How many times the snow's climate is averaged with its neighbours' (each texel a couple of hundred units)
    private static final int SNOW_SMOOTHING = 9;

    /** Averages the snow's climate (both channels, two floats a texel) with each texel's four neighbours, SNOW_SMOOTHING times over. */
    private static float[] smoothSnowClimate(SphereRaster raster, float[] climate) {
        int size = raster.size();
        float[] a = climate, b = new float[climate.length];
        for (int pass = 0; pass < SNOW_SMOOTHING; pass++) {
            float[] from = a, to = b;
            java.util.stream.IntStream.range(0, size).parallel().forEach(t -> {
                for (int c = 0; c < 2; c++) {
                    float sum = from[t * 2 + c];
                    for (int k = 0; k < 4; k++) sum += from[raster.neighbour(t, k) * 2 + c];
                    to[t * 2 + c] = sum / 5f;
                }
            });
            a = to;
            b = from;
        }
        return a;
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
                SphereRaster raster = new SphereRaster(SNOW_COVER_SIDE);
                float[] climate = new float[raster.size() * 2];
                java.util.stream.IntStream.range(0, raster.size()).parallel().forEach(t -> {
                    float[] here = snowClimate(raster.direction(t));
                    climate[t * 2] = here[0];
                    climate[t * 2 + 1] = here[1];
                });
                float[] smoothed = smoothSnowClimate(raster, climate);
                byte[] cover = new byte[smoothed.length];
                for (int k = 0; k < cover.length; k++) cover[k] = (byte) Math.round(Math.max(0f, Math.min(1f, smoothed[k])) * 255f);
                return cover;
            });
        }
        if (snowCoverTexture == 0) {
            if (!snowCoverJob.isDone()) return;
            try {
                snowCoverTexture = uploadCubeMap(SNOW_COVER_SIDE, 2, snowCoverJob.get());
            } catch (Exception e) {
                return;
            }
        }
        gl.glActiveTexture(GL3.GL_TEXTURE0 + SNOW_UNIT);
        gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, snowCoverTexture);
        gl.glActiveTexture(GL3.GL_TEXTURE0);
        // (every frame, so shaders made since pick it up too)
        // (today's temperature here against the usual, so fresh snow lies all round wherever it's snowing)
        SpherePosition here = camera.getSpherePosition();
        float usual = snowClimate(SphereMath.direction(here))[0] * 100f - 60f
                - Math.max(0f, camera.getAltitude() - seaLevelHeight) * 70f * RegionalGenerationManager.ALTITUDE_CHILL;
        Shader.setSnowCover(gl, SNOW_UNIT, sphereWeather.freshSnow(), temperatureAt(here) - usual,
                new float[] { freezingPoint, SNOW_LIES_THICK, 70f * RegionalGenerationManager.ALTITUDE_CHILL, seaLevelHeight }, snowColour(),
                (float) Planet.radius());
    }

    /**
     * The haze the distance fades into: the sky's colour greyed (as the air's haze greys
     * everything far off), and greyer still towards the clouds' colour the cloudier it is.
     */
    private float[] fogColour(Vector3 sky) {
        float grey = 0.3f * sky.x + 0.59f * sky.y + 0.11f * sky.z;
        float[] fog = { sky.x + (grey - sky.x) * FOG_GREYING, sky.y + (grey - sky.y) * FOG_GREYING, sky.z + (grey - sky.z) * FOG_GREYING };
        if (sphereWeather == null) return fog;
        float[] cloud = sphereWeather.haze(sky, 1.0f - nightProportion);
        for (int k = 0; k < 3; k++) fog[k] += (cloud[k] - fog[k]) * cloud[3];
        return fog;
    }

    // How far the fog is greyed from the sky's own colour (0 not at all, 1 fully grey)
    private static final float FOG_GREYING = 0.45f;

    // The rainfall map's top (1) as rain over a year, in millimetres, for the map's key
    private static final float RAINFALL_FULL_MM = 3000f;

    // Each heatmap's lowest and highest values on the land (see heatmapRange)
    private final Map<Overlay, float[]> heatmapRanges = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A heatmap's lowest and highest values anywhere on the land the map shows, found by
     * looking all over it (kept, once worked out): its map runs from the one to the other, so
     * both ends of its key are somewhere on its map.
     */
    private float[] heatmapRange(Overlay overlay) {
        return heatmapRanges.computeIfAbsent(overlay, o -> {
            RegionalFactor.Sampler measure = heatmapMeasure(o);
            float[] span = { Float.MAX_VALUE, -Float.MAX_VALUE };
            forEachMapLand(90_000, d -> {
                float v = measure.sample(d);
                span[0] = Math.min(span[0], v);
                span[1] = Math.max(span[1], v);
            });
            return span[0] > span[1] ? new float[] { 0f, 1f } : span;
        });
    }

    // The rock map's colours: white where there are none, through grey to black where there are the most
    private static final float[][] ROCK_SPECTRUM = {
        { 1f, 1f, 1f }, { 0.67f, 0.67f, 0.67f }, { 0.33f, 0.33f, 0.33f }, { 0f, 0f, 0f }
    };

    /** The world's rocks (made when first needed: the map may want them before the round is set up). */
    private synchronized RockField rocks() {
        if (rockField == null) {
            Vector3 bedrock = worldRockColour();
            rockField = new RockField(worldSeed, PHYSICAL_CHUNK_SIZE, seaLevelHeight, worldNoise, new float[] { bedrock.x, bedrock.y, bedrock.z });
        }
        return rockField;
    }

    /** How many chunks make a square kilometre (a metre being PlayerBody.METRE world units). */
    private float chunksPerKm2() {
        float across = 1000f * PlayerBody.METRE / PHYSICAL_CHUNK_SIZE;
        return across * across;
    }

    // The plant and animal maps' colours, none to the most
    private static final float[][] RANGE_SPECTRUM = {
        { 0.10f, 0.20f, 0.85f }, { 0.40f, 0.80f, 0.95f }, { 1.00f, 0.75f, 0.20f }, { 0.85f, 0.00f, 0.00f }
    };

    /** A count for the map's key, rounded sensibly (thousands as k). */
    private static String countLabel(float n) {
        if (n >= 10000f) return Math.round(n / 1000f) + "k";
        if (n >= 1000f) return String.format("%.1fk", n / 1000f);
        if (n >= 10f) return String.valueOf(Math.round(n));
        return String.format("%.1f", n);
    }

    /** How much rain falls at a place, 0 driest to 1 wettest (see RegionalGenerationManager.rainfallMap). */
    private float rainfallAt(SpherePosition place) {
        return regionalManager.rainfallMap.evaluateAt(place);
    }

    private float temperatureAt(SpherePosition place) {
        // (the map's chill is for the height of the ground round about: swapped for the
        // ground's own height here, so a peak reads colder than the valley beside it)
        GeodesicChunkGrid.Direction d = SphereMath.direction(place);
        float ground = Math.max(0f, TerrainMesh.getLayeredHeight(place, worldNoise) - seaLevelHeight);
        float warmth = regionalManager.temperatureMap.evaluate(d) + regionalManager.altitudeChill(d)
                - ground * RegionalGenerationManager.ALTITUDE_CHILL;
        float celsius = -30f + 70f * warmth;
        double latitude = Planet.latitude(place);
        double tilt = Math.toRadians(Math.max(1.0, planetAxialTiltDegrees));
        celsius += 14f * (float) (Math.sin(sunDeclination) / Math.sin(tilt) * Math.sin(latitude));
        double hour = Planet.longitude(place) - noonLongitude;
        hour = Math.atan2(Math.sin(hour), Math.cos(hour));
        celsius += 7f * (float) Math.cos(hour - 0.6);
        return celsius;
    }

    /**
     * While the settings are open the view behind them is darkened; in singleplayer (M46) it's
     * greyed out altogether and the game's paused: the world's time stops (see GameClock), so
     * nothing moves, falls or grows, until they're closed. (Not in multiplayer, where the
     * partner's game goes on.)
     */
    public void setMenuOpen(boolean open) {
        menuOpen = open;
        GameClock.setPaused(open && !Multiplayer.isActive());
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

    /** Developer mode: sends the player to a place (picked on the map), once the frame comes round. */
    public void setTelepot(GeodesicChunkGrid.Direction place) {
        this.teleportTo = place;
        this.isToTeleport = true;
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