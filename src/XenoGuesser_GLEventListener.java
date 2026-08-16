import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import com.jogamp.opengl.*;
import com.jogamp.opengl.util.texture.Texture;
import gmaths.*;

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
        Vec3 healthyColor;
        Vec3 dyingColor;
        Vec3 trunkColor; // NEW: Controls the bark/wood color
        
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
                             float baseBAngle, float varBAngle, Vec3 healthyColor, Vec3 dyingColor, Vec3 trunkColor,
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
    private Model[][][] floraBranchModelsLOD; // Matrix bounds: [species][lodIndex][variationIndex]
    private Model[][][] floraLeafModelsLOD;   
    
    // Shader fields
    private Shader leafShader;
    
    private static class FloraInstance {
        Vec3 pos;
        int speciesIndex;
        int modelIndex;
        float scale;
        float rotationY;
        
        public FloraInstance(Vec3 pos, int speciesIndex, int modelIndex, float scale, float rotationY) {
            this.pos = pos;
            this.speciesIndex = speciesIndex;
            this.modelIndex = modelIndex;
            this.scale = scale;
            this.rotationY = rotationY;
        }
    }
    
    private Light[] lights;
    private Vec3 ambientLight;
    private float nightProportion;
    private float timeOfDay;

    private float seaLevelHeight;
    private Model waterPlaneModel;
    private Shader waterShader;
    private Material waterMaterial; 

    private Model skyModel;

    private Shader terrainShader;
    private Renderer terrainRenderer;
    private Material terrainMaterial;
    private Mat4 globalModelMatrix;

    private Shader solidShader;

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
    
    private float fpsSmoothing = 0.95f; 
    private double smoothedFps = 60.0;  

    // --- Fully GPU-Driven Instanced Grass Rendering Fields ---
    private Shader grassShader;
    private int grassVAO = 0;
    private int grassVBO = 0;
    private int grassChunkCoordVBO = 0; 
    private int totalGrassInstances = 0;
    
    private final int GRASS_VIEW_DISTANCE = 21;
    private final int MAX_GRASS_LIMIT = 800;
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

    private Vec3 healthyColour;
    private Vec3 dyingColour;

    private NationGenerationManager nationManager;
    private int totalNationsCount;

    private InfrastructureManager infraManager;
    private Map<String, List<InfrastructureObject>> infraCache;
    private Map<Integer, Model> signModelsByNation;
    private Model postModel;

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
      TREE_1_ABUNDANCE,
      TREE_2_ABUNDANCE,
      TREE_3_ABUNDANCE,
      TREE_4_ABUNDANCE,
      SHRUB_1_ABUNDANCE,
      SHRUB_2_ABUNDANCE,
      SHRUB_3_ABUNDANCE,
      SHRUB_4_ABUNDANCE,
      NATION_TERRITORIES
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
        
        this.camera.setPosition(new Vec3(0f, 5f, 15f));
        this.camera.setTarget(new Vec3(0f, 0f, 0f));

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
        this.GRASS_BASE_ABUNDANCE = 700f;

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
        
        // Define available leaf texture asset pool indices
        int[] availableLeafTextures = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

        for (int s = 0; s < NUM_SPECIES; s++) {
            boolean isTree = (s < 4); // First 4 are Trees, last 4 are Shrubs
            String name = isTree ? ("Procedural Tree Type " + (s + 1)) : ("Procedural Shrub Type " + (s - 3));
            
            // Choose a texture out of the pool randomly based on the seed
            int leafTexNum = availableLeafTextures[speciesSeeder.nextInt(availableLeafTextures.length)];
            
            // Randomize organic profiles depending on structural class (Tree vs Shrub)
            float leafScale, baseBRate, varBRate, baseSWidth, varSWidth, baseWDecl, varWDecl, baseSDist, varSDist, baseBAngle, varBAngle;
            float tempMean, tempStdDev, patchNoiseScale, baseAbundance;
            Vec3 healthyColor, dyingColor;

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
            Vec3 trunkColor = new Vec3(trunkLuminance, trunkLuminance, trunkLuminance);

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
            healthyColor = new Vec3(
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

            dyingColor = new Vec3(
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
    }

    public void setGameHUD(GameHUD gameHUD) {
        this.gameHUD = gameHUD;
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        this.gl = gl;
        
        gl.glClearColor(0.976f, 0.725f, 0.043f, 1.0f); // Sky Colour
        gl.glClearDepth(1.0f);
        
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(GL.GL_LESS);
        gl.glFrontFace(GL.GL_CCW);
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glCullFace(GL.GL_BACK);
        
        initialise();
        startTime = getSeconds();
    }
    
    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        GL3 gl = drawable.getGL().getGL3();
        
        if (height <= 0) height = 1;
        if (width <= 0) width = 1;
        
        this.currentWidth = width;
        this.currentHeight = height;
        gl.glViewport(0, 0, width, height);
        
        float aspect = (float) width / (float) height;
        float farClippingPlane = 3000.0f; 
        Mat4 perspectiveMatrix = Mat4Transform.perspective(45, aspect, 10.0f, farClippingPlane);
        camera.setPerspectiveMatrix(perspectiveMatrix);

        createDepthFramebuffer(gl, width, height);
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        
        if (minimap != null && minimap.isFullScreenRevealMode()) {
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
            return; 
        }
        
        render();
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
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

    private Vec3 getSunPosition() {
        float sunDistance = 2350.0f; 
        
        float progress = timeOfDay - (float)Math.floor(timeOfDay);
        
        float minAngleRad = (float)Math.toRadians(10.0);
        float maxAngleRad = (float)Math.toRadians(170.0);
        float currentAngleRad = minAngleRad + progress * (maxAngleRad - minAngleRad);
        
        Vec3 cameraPosition = camera.getPosition();
        
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
        
        return new Vec3(worldX, worldY, worldZ);
    }

private void initialise() {
        textures = new TextureLibrary();
        textures.add(gl, "dirt_diffuse", "assets/textures/dirt_diffuse.png");
        textures.add(gl, "water_diffuse", "assets/textures/water_diffuse.png");
        textures.add(gl, "sky", "assets/textures/sky.png");
        textures.add(gl, "sun_glow", "assets/textures/sun_glow.png");
        int N_LEAVES = 10;
        for (int i=0; i < N_LEAVES; i++) {
            textures.add(gl, "leaf" + String.valueOf(i), "assets/textures/leaves/leaf" + String.valueOf(i) + ".png");
        }
        
        Texture waterTexInstance = textures.get("water_diffuse");
        waterTexInstance.bind(gl);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT);

        ambientLight = new Vec3(0.4f, 0.38f, 0.35f); 
        nightProportion = 0.0f;
        
        timeOfDay = 0.5f;

        lights = new Light[1];
        float lightSize = 275.0f; 
        
        Light l = new Light(gl, camera, true, new Vec3(0,0,0), lightSize, textures.get("sun_glow"));
        Material m = new Material();
        
        m.setFullDiffuse(1.0f, 0.95f, 0.95f);  
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
            new Vec3(1.0f, 1.0f, 1.0f), 
            new Vec3(1.0f, 1.0f, 1.0f), 
            new Vec3(0.1f, 0.1f, 0.1f), 
            4.0f                                                                                                                                                                                
        );
        terrainMaterial.setDiffuseMap(textures.get("dirt_diffuse"));
        terrainRenderer = new Renderer();
        globalModelMatrix = new Mat4(1);


        // Initialize the manager
        this.infraManager = new InfrastructureManager(this.worldSeed, this.totalNationsCount, this.nationManager);
        this.infraCache = new HashMap<>();
        this.signModelsByNation = new HashMap<>();
        
        // --- INFRASTRUCTURE COMPILATION ---
        // 1. Compile the shared cylindrical post model
        float[] postVerts = Cylinder.createVertices(12);
        int[] postInds = Cylinder.createIndices(12);
        Mesh postMesh = new Mesh(gl, postVerts, postInds);
        
        Material postMat = new Material(
            new Vec3(0.35f, 0.25f, 0.15f), // Wood/brown ambient
            new Vec3(0.35f, 0.25f, 0.15f), // Wood/brown diffuse
            new Vec3(0.0f, 0.0f, 0.0f),    // Zero specular
            1.0f
        );
        this.postModel = new Model("shared_sign_post", postMesh, new Mat4(1), solidShader, postMat, terrainRenderer, lights, camera);

        // 2. Pre-compile the flat TwoTriangles billboard models for each nation
        Mesh signMeshBase = new Mesh(gl, TwoTriangles.vertices, TwoTriangles.indices);
        
        for (int n = 1; n <= totalNationsCount; n++) {
            java.awt.Color awtColor = nationManager.getNationColor(n);
            Vec3 nationRGB = new Vec3(awtColor.getRed() / 255.0f, awtColor.getGreen() / 255.0f, awtColor.getBlue() / 255.0f);
            
            Material signMaterial = new Material(
                nationRGB,                     
                nationRGB,                     
                new Vec3(0.0f, 0.0f, 0.0f),    
                1.0f                           
            );
            
            Model signModel = new Model("sign_nation_" + n, signMeshBase, new Mat4(1), solidShader, signMaterial, terrainRenderer, lights, camera);
            signModelsByNation.put(n, signModel);
        }
        
        // --- MULTI-SPECIES 3D GEOMETRY COMPILATION PIPELINE ---
        floraBranchModelsLOD = new Model[NUM_SPECIES][3][FLORA_VARIATIONS];
        floraLeafModelsLOD = new Model[NUM_SPECIES][3][FLORA_VARIATIONS];
        
        leafShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_leaf.txt");
        int[] lodSlices = {12, 8, 4}; 
        
        for (int s = 0; s < NUM_SPECIES; s++) {
            SpeciesConfig sc = speciesConfigs[s];
            
            float brightnessBoost = 2.5f;
            Vec3 boostedTrunkColor = new Vec3(
                sc.trunkColor.x * brightnessBoost,
                sc.trunkColor.y * brightnessBoost,
                sc.trunkColor.z * brightnessBoost
            );

            Material floraMat = new Material(
                boostedTrunkColor,             
                boostedTrunkColor,             
                new Vec3(0.02f, 0.02f, 0.02f), 
                2.0f                           
            );
            
            floraMat.setDiffuseMap(textures.get("dirt_diffuse"));
            
            for (int i = 0; i < FLORA_VARIATIONS; i++) {
                
                java.util.Random fRand = new java.util.Random(worldSeed + s * 3721L + i * 8273L);
                
                float bRate = sc.baseBRate + fRand.nextFloat() * sc.varBRate;
                float sWidth = sc.baseSWidth + fRand.nextFloat() * sc.varSWidth;
                float wDecl = sc.baseWDecl + fRand.nextFloat() * sc.varWDecl;
                float sDist = sc.baseSDist + fRand.nextFloat() * sc.varSDist;
                float bAngle = sc.baseBAngle + fRand.nextFloat() * sc.varBAngle;
                
                int texNum = sc.leafTexNum;
                Material leafMat = new Material(new Vec3(0.9f, 0.9f, 0.9f), new Vec3(0.2f, 0.2f, 0.2f), new Vec3(0.0f, 0.0f, 0.0f), 1.0f);
                leafMat.setDiffuseMap(textures.get("leaf" + texNum));

                for (int lod = 0; lod < 3; lod++) {
                    
                    Flora.FloraBundle fBundle = Flora.generateFloraBundle(
                        gl, worldSeed + (i * 7382L) + s * 8831L, 
                        bRate, sWidth, wDecl, sDist, bAngle, lodSlices[lod], sc.leafScale
                    );
                    
                    floraBranchModelsLOD[s][lod][i] = new Model("flora_branch_s" + s + "_" + i + "_lod" + lod, fBundle.branchMesh, new Mat4(1), terrainShader, floraMat, terrainRenderer, lights, camera);
                    floraLeafModelsLOD[s][lod][i] = new Model("flora_leaf_s" + s + "_" + i + "_lod" + lod, fBundle.leafMesh, new Mat4(1), leafShader, leafMat, terrainRenderer, lights, camera);
                }
            }
        }

        waterShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_water.txt");
        
        waterMaterial = new Material(
            new Vec3(0.01f, 0.31f, 0.55f),  
            new Vec3(0.01f, 0.31f, 0.55f),  
            new Vec3(10.5f, 0.4f, 0.4f),
            2048f                                                                                                                                                                                
        );
        waterMaterial.setDiffuseMap(textures.get("water_diffuse"));

        Renderer waterRenderer = new Renderer(); 
        Mat4 waterModelMatrix = new Mat4(1);
        Mesh waterMesh = new Mesh(gl, TwoTriangles.vertices, TwoTriangles.indices);  
        waterPlaneModel = new Model("ocean_surface", waterMesh, waterModelMatrix, waterShader, waterMaterial, waterRenderer, lights, camera);

        textures.add(gl, "grass_atlas", "assets/textures/grass2.png");
        
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

        spawnPlayerAtRandomLocation();
        createDepthFramebuffer(gl, currentWidth, currentHeight);

        java.util.Random rand = new java.util.Random(worldSeed);
        float r = 0.35f + rand.nextFloat() * 0.5f;
        float g = 0.35f + rand.nextFloat() * 0.5f;
        float b = 0.35f + rand.nextFloat() * 0.5f;
        healthyColour = new Vec3(r, g, b);
        dyingColour = new Vec3(0.4f, 0.25f, 0.15f);
    }

    private void spawnPlayerAtRandomLocation() {
        java.util.Random dynamicRand = new java.util.Random();
        float halfRegion = TOTAL_REGION_WIDTH / 2.0f;
        
        float spawnX = 0.0f;
        float spawnZ = 0.0f;
        float terrainHeightAtSpawn = 0.0f;
        boolean foundDryLand = false;

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

        moveToLocation(spawnX, spawnZ);
    }

    public void moveToLocation(float spawnX, float spawnZ) {
        float terrainHeightAtSpawn = TerrainMesh.getLayeredHeight(spawnX, spawnZ, worldNoise);
        camera.setPosition(new Vec3(spawnX, terrainHeightAtSpawn + playerEyeHeight, spawnZ));
        camera.setTarget(new Vec3(spawnX, terrainHeightAtSpawn + playerEyeHeight, spawnZ - 10.0f));

        lastChunkX = (int) Math.floor((spawnX + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        lastChunkZ = (int) Math.floor((spawnZ + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
        
        updateVisibleChunks(true);
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

        if (minimap != null) {
            minimap.resetMapState(); 
        }

        spawnPlayerAtRandomLocation();
    }

    private void updateVisibleChunks(boolean forceImmediate) {
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
                        int speciesAttempts = (int) actualAttemptsFloat;
                        double fractionalPart = actualAttemptsFloat - speciesAttempts;
                        if (cRand.nextFloat() < fractionalPart) {
                            speciesAttempts++;
                        }

                        for (int i = 0; i < speciesAttempts; i++) {
                            float cxWorld = cx * PHYSICAL_CHUNK_SIZE + (cRand.nextFloat() * PHYSICAL_CHUNK_SIZE);
                            float czWorld = cz * PHYSICAL_CHUNK_SIZE + (cRand.nextFloat() * PHYSICAL_CHUNK_SIZE);
                            float cyWorld = TerrainMesh.getLayeredHeight(cxWorld, czWorld, worldNoise);

                            if (cyWorld > seaLevelHeight + 0.1f) {
                                int randModelIndex = cRand.nextInt(FLORA_VARIATIONS);
                                float randomScale = 0.70f + cRand.nextFloat() * 0.60f;
                                float randomRotY = cRand.nextFloat() * 360.0f;
                                
                                instances.add(new FloraInstance(new Vec3(cxWorld, cyWorld, czWorld), s, randModelIndex, randomScale, randomRotY));
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

        for (int cz = lastChunkZ - GRASS_VIEW_DISTANCE; cz <= lastChunkZ + GRASS_VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - GRASS_VIEW_DISTANCE; cx <= lastChunkX + GRASS_VIEW_DISTANCE; cx++) {
                String key = cx + "_" + cz;
                float[] chunkGrassData = grassCache.get(key);
                
                if (chunkGrassData == null) {
                    if (generatedThisFrame && !forceImmediate) {
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
                            
                            if (worldY > seaLevelHeight + 0.1f) {
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

            for (int cz = lastChunkZ - GRASS_VIEW_DISTANCE; cz <= lastChunkZ + GRASS_VIEW_DISTANCE; cz++) {
                for (int cx = lastChunkX - GRASS_VIEW_DISTANCE; cx <= lastChunkX + GRASS_VIEW_DISTANCE; cx++) {
                    String key = cx + "_" + cz;
                    float[] chunkGrassData = grassCache.get(key);
                    if (chunkGrassData != null) {
                        persistentGrassBuffer.put(chunkGrassData);
                    }
                }
            }
            persistentGrassBuffer.flip();

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
        infraCache.keySet().retainAll(activeInfraKeys.keySet());

        for (int cz = lastChunkZ - VIEW_DISTANCE; cz <= lastChunkZ + VIEW_DISTANCE; cz++) {
            for (int cx = lastChunkX - VIEW_DISTANCE; cx <= lastChunkX + VIEW_DISTANCE; cx++) {
                String key = cx + "_" + cz;
                if (!infraCache.containsKey(key)) {
                    List<InfrastructureObject> spawnedObjects = infraManager.generateForChunk(
                        cx, cz, PHYSICAL_CHUNK_SIZE, TOTAL_REGION_WIDTH, seaLevelHeight, worldNoise
                    );
                    infraCache.put(key, spawnedObjects);
                }
            }
        }
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

        if (deltaTime > 0.1) { deltaTime = 1.0 / 60.0; }

        if (deltaTime > 0) {
            double instantFps = 1.0 / deltaTime;
            smoothedFps = (smoothedFps * fpsSmoothing) + (instantFps * (1.0f - fpsSmoothing));
            
            if (this.gameHUD != null) {
                this.gameHUD.setGameFps((int) Math.round(smoothedFps));
            }
        }

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

        Vec3 currentPos = camera.getPosition();
        float rawGroundHeight = TerrainMesh.getLayeredHeight(currentPos.x, currentPos.z, worldNoise);
        float targetCameraHeight = rawGroundHeight + playerEyeHeight;

        float dynamicSmoothingFactor = 6.0f * (float)deltaTime;
        if (dynamicSmoothingFactor > 1.0f) dynamicSmoothingFactor = 1.0f;

        float smoothedHeight = currentPos.y + (targetCameraHeight - currentPos.y) * dynamicSmoothingFactor;
        camera.setHeight(smoothedHeight);

        lights[0].setPosition(getSunPosition());
        Vec3 sunPos = lights[0].getPosition();

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
        
        ambientLight = new Vec3(currentR, currentG, currentB);

        float skyDayR = 0.976f, skyDayG = 0.725f, skyDayB = 0.043f;
        float skyNightR = 0.05f, skyNightG = 0.05f, skyNightB = 0.08f; 
        
        float curSkyR = skyDayR + nightProportion * (skyNightR - skyDayR);
        float curSkyG = skyDayG + nightProportion * (skyNightG - skyDayG);
        float curSkyB = skyDayB + nightProportion * (skyNightB - skyDayB);
        Vec3 skyColour = new Vec3(curSkyR, curSkyG, curSkyB);

        if (this.compassHUD != null) {
            Vec3 cameraLookDir = camera.getForwardDirection(); 
            this.compassHUD.updateHeading(cameraLookDir);
        }

        String skyTextureKey = "sky"; 

        Vec3 camForward = camera.getForwardDirection();
        float maxFloraRenderDistance = VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE;
        float maxFloraDistSq = maxFloraRenderDistance * maxFloraRenderDistance;

        // --- PASS 1: DEPTH PRE-PASS ---
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, depthFBO[0]);
        gl.glClear(GL3.GL_DEPTH_BUFFER_BIT); 
        gl.glEnable(GL3.GL_DEPTH_TEST);
        gl.glEnable(GL3.GL_CULL_FACE);

        depthPrePassShader.use(gl);
        
        Mat4 view = camera.getViewMatrix();
        Mat4 projection = camera.getPerspectiveMatrix(); 
        Mat4 viewProjection = Mat4.multiply(projection, view); 

        for (Model plane : chunkCache.values()) { 
            plane.renderDepthPass(gl, depthPrePassShader, viewProjection); 
        }
        
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);

        // --- PASS 2: MAIN FORWARD DRAW ---
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST); 
        gl.glDisable(GL.GL_CULL_FACE); 

        float sunProgress = timeOfDay - (float)Math.floor(timeOfDay);
        float sunAngleDeg = 10.0f + sunProgress * (170.0f - 10.0f);

        Vec3 camPosForSky = camera.getPosition();
        float totalPlayableRegion = (VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE) * 50.0f;
        float maxMapEdgeZ = totalPlayableRegion / 2.0f;
        
        float latitudeFactor = camPosForSky.z / maxMapEdgeZ;
        if (latitudeFactor > 1.0f) latitudeFactor = 1.0f;
        if (latitudeFactor < -1.0f) latitudeFactor = -1.0f;
        
        float latitudeAngleDeg = -latitudeFactor * 35.0f;
        float tiltAngleDeg = latitudeAngleDeg + currentSeasonalTiltDegrees;

        Mat4 skyRotation = Mat4Transform.rotateAroundX(tiltAngleDeg);
        skyRotation = Mat4.multiply(skyRotation, Mat4Transform.rotateAroundZ(sunAngleDeg + 90.0f));

        Mat4 skyTransform = Mat4Transform.translate(camera.getPosition());
        skyTransform = Mat4.multiply(skyTransform, skyRotation);
        skyTransform = Mat4.multiply(skyTransform, Mat4Transform.scale(2400.0f, 2400.0f, 2400.0f));
        
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

        for (Model plane : chunkCache.values()) { 
            plane.render(gl, ambientLight, nightProportion); 
        }
        
        // ==========================================
        // --- MULTI-SPECIES FLORA RENDERING PASS ---
        // ==========================================
        for (List<FloraInstance> positions : floraCache.values()) {
            for (FloraInstance inst : positions) {
                float dx = inst.pos.x - currentPos.x;
                float dy = inst.pos.y - currentPos.y;
                float dz = inst.pos.z - currentPos.z;
                float distSq = dx*dx + dy*dy + dz*dz;

                if (distSq > maxFloraDistSq) continue;

                float dotProduct = dx * camForward.x + dy * camForward.y + dz * camForward.z;
                if (dotProduct < -12.0f) continue;

                int lodIndex = 0;
                if (distSq > 135f * 135f) {
                    lodIndex = 2;
                } else if (distSq > 65f * 65f) {
                    lodIndex = 1;
                }

                Mat4 m = Mat4Transform.translate(inst.pos);
                m = Mat4.multiply(m, Mat4Transform.rotateAroundY(inst.rotationY));
                m = Mat4.multiply(m, Mat4Transform.scale(inst.scale, inst.scale, inst.scale));
                
                SpeciesConfig sc = speciesConfigs[inst.speciesIndex];

                // 1. Draw Branch (Uses species config parameters)
                floraBranchModelsLOD[inst.speciesIndex][lodIndex][inst.modelIndex].setModelMatrix(m);
                floraBranchModelsLOD[inst.speciesIndex][lodIndex][inst.modelIndex].render(gl, ambientLight, nightProportion);
                
                // 2. Prepare Regional Phenotype Color Blending for Leaves
                int cx = (int) Math.floor((inst.pos.x + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
                int cz = (int) Math.floor((inst.pos.z + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);

                float climateVal = this.leafColourFactor.evaluate(cx, cz, inst.pos.x, inst.pos.z);

                // Mix using species-specific color boundaries
                float rOut = sc.dyingColor.x + (sc.healthyColor.x - sc.dyingColor.x) * climateVal;
                float gOut = sc.dyingColor.y + (sc.healthyColor.y - sc.dyingColor.y) * climateVal;
                float bOut = sc.dyingColor.z + (sc.healthyColor.z - sc.dyingColor.z) * climateVal;

                Vec3 dynamicOuterColor = new Vec3(rOut, gOut, bOut);

                // Scale inner lightness structure
                float lightScale = 1.3f; 
                float rIn = Math.min(dynamicOuterColor.x * lightScale, 1.0f);
                float gIn = Math.min(dynamicOuterColor.y * lightScale, 1.0f);
                float bIn = Math.min(dynamicOuterColor.z * lightScale, 1.0f);

                Vec3 dynamicInnerColor = new Vec3(rIn, gIn, bIn);

                // 3. Update Material Uniform State
                leafShader.use(gl);
                leafShader.setVec3(gl, "u_OuterLeafColor", dynamicOuterColor);
                leafShader.setVec3(gl, "u_InnerLeafColor", dynamicInnerColor);

                // --- NEW: Pass Fog/Sky data to the leaf shader ---
                gl.glUniformMatrix4fv(gl.glGetUniformLocation(leafShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
                
                if (textures.get(skyTextureKey) != null) {
                    gl.glActiveTexture(GL3.GL_TEXTURE2);
                    textures.get(skyTextureKey).bind(gl); 
                    leafShader.setInt(gl, "skyTexture", 2);
                }
                // -------------------------------------------------

                // 4. Draw Leaves (Uses the species leaf asset variation maps)
                floraLeafModelsLOD[inst.speciesIndex][lodIndex][inst.modelIndex].setModelMatrix(m);
                floraLeafModelsLOD[inst.speciesIndex][lodIndex][inst.modelIndex].render(gl, ambientLight, nightProportion);
            }
        }

        // ==========================================
        // --- INFRASTRUCTURE RENDERING PASS --------
        // ==========================================

        for (List<InfrastructureObject> objects : infraCache.values()) {
            for (InfrastructureObject obj : objects) {
                
                float dx = obj.position.x - currentPos.x;
                float dy = obj.position.y - currentPos.y;
                float dz = obj.position.z - currentPos.z;
                float distSq = dx*dx + dy*dy + dz*dz;
                
                // Frustum / Distance Culling
                if (distSq > maxFloraDistSq) continue;
                
                float dotProduct = dx * camForward.x + dy * camForward.y + dz * camForward.z;
                if (dotProduct < -12.0f) continue;
                
                // The main loop no longer cares what type of object this is. 
                // It just passes the necessary resources and says "Draw yourself."
                obj.render(gl, ambientLight, nightProportion, signModelsByNation, postModel);
            }
        }
        
        // --- INSTANCED GRASS PASS ---
        if (totalGrassInstances > 0) {
            gl.glDisable(GL.GL_CULL_FACE); 

            grassShader.use(gl);
            
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(grassShader.getID(), "view"), 1, false, camera.getViewMatrix().toFloatArrayForGLSL(), 0);
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(grassShader.getID(), "projection"), 1, false, camera.getPerspectiveMatrix().toFloatArrayForGLSL(), 0);
            gl.glUniformMatrix4fv(gl.glGetUniformLocation(grassShader.getID(), "skyRotation"), 1, false, skyRotation.toFloatArrayForGLSL(), 0);
            
            Vec3 camPos1 = camera.getPosition();
            gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "cameraPos"), camPos1.x, camPos1.y, camPos1.z);
            gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "ambientLight"), ambientLight.x, ambientLight.y, ambientLight.z);
            gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "sunColour"), 1.0f, 0.95f, 0.95f); 
            gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "skyColour"), skyColour.x, skyColour.y, skyColour.z);

            gl.glUniform1i(gl.glGetUniformLocation(grassShader.getID(), "worldSeed"), (int)(worldSeed & 0xFFFF));

            Vec3 camPosForSun = camera.getPosition();
            Vec3 direction = new Vec3(sunPos.x - camPosForSun.x, sunPos.y - camPosForSun.y, sunPos.z - camPosForSun.z);
            float len = (float)Math.sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z);
            if (len > 0.0f) {
                direction = new Vec3(direction.x / len, direction.y / len, direction.z / len);
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

            gl.glBindVertexArray(grassVAO);
            gl.glDrawArraysInstanced(GL3.GL_TRIANGLES, 0, 6, totalGrassInstances);
            gl.glBindVertexArray(0);

            gl.glEnable(GL.GL_CULL_FACE); 
        }

        // --- OCEAN PASS ---
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        Vec3 camPos = camera.getPosition();
        float waterCoverageSize = PHYSICAL_CHUNK_SIZE * VIEW_DISTANCE * 2.0f;

        Mat4 waterMatrix = Mat4Transform.translate(camPos.x, seaLevelHeight - 0.05f, camPos.z);
        waterMatrix = Mat4.multiply(waterMatrix, Mat4Transform.scale(waterCoverageSize, 1.0f, waterCoverageSize));

        waterShader.use(gl);
        waterShader.setFloat(gl, "seaLevelHeight", seaLevelHeight);
        waterShader.setVec2(gl, "windowSize", new Vec2((float)currentWidth, (float)currentHeight));

        waterShader.setVec3(gl, "sunPos", sunPos);
        waterShader.setVec3(gl, "lightSpecular", new Vec3(1.0f, 1.0f, 1.0f));
        
        waterShader.setVec3(gl, "matSpecular", waterMaterial.getSpecular());
        waterShader.setFloat(gl, "matShininess", waterMaterial.getShininess());

        waterShader.setVec3(gl, "ambientLight", ambientLight);
        waterShader.setVec3(gl, "skyColour", skyColour);
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

        waterPlaneModel.setModelMatrix(waterMatrix);
        waterPlaneModel.render(gl, ambientLight, nightProportion);

        gl.glDisable(GL.GL_BLEND);
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
        assignHeatmapToMinimap(currentDebugFactor);
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
            // ... (rest of the method stays the same)

            // 2. Otherwise, look up and evaluate standard noise/growth parameters
            RegionalFactor targetFactor = switch (currentDebugFactor) {
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
                case TREE_1_ABUNDANCE -> this.speciesConfigs[0].abundanceFactor;
                case TREE_2_ABUNDANCE -> this.speciesConfigs[1].abundanceFactor;
                case TREE_3_ABUNDANCE -> this.speciesConfigs[2].abundanceFactor;
                case TREE_4_ABUNDANCE -> this.speciesConfigs[3].abundanceFactor;
                case SHRUB_1_ABUNDANCE -> this.speciesConfigs[4].abundanceFactor;
                case SHRUB_2_ABUNDANCE -> this.speciesConfigs[5].abundanceFactor;
                case SHRUB_3_ABUNDANCE -> this.speciesConfigs[6].abundanceFactor;
                case SHRUB_4_ABUNDANCE -> this.speciesConfigs[7].abundanceFactor;
                default -> null; 
            };
            
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

    public void setCompassHUD(CompassHUD compassHUD) {
        this.compassHUD = compassHUD;
    }

    private Model makeSkybox(GL3 gl, String fragmentPath, Texture skyTexture) {
        String name = "skybox";
        Mesh mesh = new Mesh(gl, InsideSphere.vertices.clone(), InsideSphere.indices.clone());
        Mat4 modelMatrix = Mat4Transform.scale(2400.0f, 2400.0f, 2400.0f);
        Shader shader = new Shader(gl, "assets/shaders/vs_standard.txt", fragmentPath);
        Material material = new Material(new Vec3(0f, 0f, 0f), new Vec3(0f, 0f, 0f));
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
}