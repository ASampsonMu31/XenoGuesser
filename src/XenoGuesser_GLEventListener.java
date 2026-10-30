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

/**
 * The running 3D game: the world laid out once (its constructor), the GL set-up a step a frame
 * while the loading screen shows, then every frame the ground, roads and buildings, plants and
 * grass streamed in round the player and everything drawn (render). It's the facade the window
 * (XenoGuesser) talks to; each side of the game is in a part (see GamePart): RoundSpawner,
 * PlayerMotion, MapOverlays, SpeciesPictures, IdentificationLens, HudRenderer, SnowCover,
 * FloraSpecies and DevShots.
 */
public class XenoGuesser_GLEventListener implements GLEventListener {

    // Its parts: the code for each side of the running game, working on the state below (see GamePart)
    final MapOverlays mapOverlays = new MapOverlays(this);
    final SpeciesPictures speciesPictures = new SpeciesPictures(this);
    final IdentificationLens identificationLens = new IdentificationLens(this);
    final DevShots devShots = new DevShots(this);
    // (only when the trailer's being filmed: see TrailerDirector)
    final TrailerDirector trailerDirector = TrailerDirector.LIST != null ? new TrailerDirector(this) : null;
    final HudRenderer hudRenderer = new HudRenderer(this);
    final PlayerMotion playerMotion = new PlayerMotion(this);
    final SnowCover snowCover = new SnowCover(this);
    final RoundSpawner roundSpawner = new RoundSpawner(this);
    final FloraSpecies floraSpecies = new FloraSpecies(this);

    /** Space: jump, if standing on something (not mid-air or swimming). */
    public void jump() {
        playerMotion.jump();
    }

    /** R: back to the pod's stairs where the round started. */
    public void returnToPod() {
        playerMotion.returnToPod();
    }

    /** The oxygen's downgraded, for the rest of the game. */
    public void downgradeOxygen() {
        playerMotion.downgradeOxygen();
    }

    /** Three maps for the shop after this round (counting from 1): see MapOverlays.shopOffers. */
    public List<ShopPanel.Card> shopOffers(int round) {
        return mapOverlays.shopOffers(round);
    }
    
    Camera camera;
    MyKeyboardInput keyboard;
    private double startTime;
    private double lastElapsedTime = 0;

    private TextureLibrary textures;
    private final GeodesicChunkGrid geodesicTerrainGrid = new GeodesicChunkGrid(TerrainMesh.DRAWN_LEVEL);
    private Model geodesicTerrainModel;
    private java.util.concurrent.Future<MeshData> geodesicTerrainBuild;
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
    SphereWeather sphereWeather;
    final GeodesicCollision geodesicCollision = new GeodesicCollision();
    // Trees and grass, per geodesic cell (a little over two chunks' area each) round the player,
    // and every tree in them, for collision and the identification kit
    private static final int VEGETATION_LEVEL = 7;
    CellStream<List<SphereFlora>> floraStream;
    CellStream<float[]> grassStream;
    final CellIndex<SphereFlora> trees = new CellIndex<>();
    private static final int GEODESIC_TERRAIN_SEGMENTS = TerrainMesh.DRAWN_SEGMENTS;

    SpeciesConfig[] speciesConfigs;
    final int NUM_SPECIES = 8; // 4 Trees + 4 Shrubs
    final int FLORA_VARIATIONS = 10;

    private final List<SphereFlora> visibleFlora = new ArrayList<>();
    // Tallest trees reach about this far from their base, before instance scaling
    static final float FLORA_CULL_RADIUS = 140.0f;
    Model[][][] floraBranchModelsLOD; // Matrix bounds: [species][lodIndex][variationIndex]
    Model[][][] floraLeafModelsLOD;   
    
    // Shader fields
    Shader leafShader;
    
    private Light[] lights;
    private Vector3 ambientLight;
    private float nightProportion;
    float timeOfDay;

    float seaLevelHeight;
    private Material waterMaterial;
        // Bugs and, in time, other creatures; their ranges follow the land, not nation borders
        OrganismManager organismManager;
    // The people who built the towns: generated anew each game, dressed by nation
    Inhabitants inhabitants;
    // The player's own spacesuited body, and whether they are swimming
    final PlayerBody playerBody = new PlayerBody();
    // The pod the player landed in, standing where each round starts
    // (in multiplayer with wider stairs, so both players start on them side by side: M11)
    final LandingPod landingPod = new LandingPod(Multiplayer.isActive());
    // (and the beam of light it shines up from its top, to find it by)
    private final PodBeacon podBeacon = new PodBeacon();
    private RockField rockField;
    static final Object PLAYER = "player", PARTNER = "partner";
    static final float PLAYER_RADIUS = 3.0f;   // must stay over 1.45 x NEAR_PLANE
    // Developer aid: -Dxenoguesser.menushot photographs the pod for the main menu's background
    static final boolean MENU_SHOT = Dev.property("xenoguesser.menushot") != null;
    boolean hideFirstPerson;
    boolean swimming;
    float waterSurfaceHere;
    // Eyes this far above the water when swimming; deeper than this and the player swims
    static final float SWIM_EYE_HEIGHT = 2.4f;
    // The scene's near plane: far enough out for depth precision on land, close in when the
    // eyes are just above the water so the surface around the swimmer isn't cut away
    // The near plane sits close enough that the player's solid circle always keeps it off
    // walls: its far corners are at most ~1.45x the near distance from the eye (45 degree
    // view, up to 21:9 screens), inside PLAYER_RADIUS, so nothing can be cut away in front
    static final float NEAR_PLANE = 2.0f, SWIMMING_NEAR_PLANE = 1.5f, FAR_PLANE = 3000.0f;
    float currentNearPlane = NEAR_PLANE;

    // The temperature (Celsius) the planet's water freezes at, so below which it snows
    float freezingPoint;

    private Shader terrainShader;
    // The plants' bodies: bark, skin, orbs, spikes (see fs_flora)
    Shader floraShader;
    private Renderer terrainRenderer;
    private Material terrainMaterial;
    private Matrix4 globalModelMatrix;

    private Shader solidShader;
    private Shader glassShader;
    private Shader signboardShader;

    private Shader depthPrePassShader;

    final float PHYSICAL_CHUNK_SIZE; 
    // (how far out the plants, roads and buildings are made: as the graphics chosen, taken up as
    // each round starts being built: see applyQuality)
    private int VIEW_DISTANCE = GameSettings.quality().viewDistance;

    final float TOTAL_REGION_WIDTH;

    PerlinNoise worldNoise;
    long worldSeed; 

    float playerEyeHeight = 20.0f;

    // (set from the window's thread once it opens, and waited for by the map's: see awaitMinimap)
    volatile MapPanel minimap;
    GameHUD gameHUD; 

    // --- Seasonal Simulation Fields ---
    float planetAxialTiltDegrees;
    // The sun: its declination (radians north of the equator it stands overhead) and the
    // longitude where it is noon
    double sunDeclination, noonLongitude;

    private int[] depthFBO = new int[1];
    private int[] depthTexture = new int[1];
    int currentWidth = 1024;  
    int currentHeight = 768;
    
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

    // While the game's paused (M46) the view is greyed out altogether: nothing of it to study
    static final float PAUSED_DIM = 1f;
    volatile boolean menuOpen;

    // --- ECOSYSTEM SPAWNING MANAGER DATA ---
    RegionalGenerationManager regionalManager;

    private RegionalFactor leafTemperateFactor;
    RegionalFactor grassMoistureFactor;
    private RegionalFactor leafColourNoiseFactor;

        // Regional soil colour: where the ground drifts toward each of the palette's soil variants
    RegionalFactor soilVariantAFactor;
    RegionalFactor soilVariantBFactor;

    RegionalFactor grassAbundanceFactor;
    RegionalFactor grassHeightFactor;
    RegionalFactor grassColourFactor;
    private RegionalFactor leafColourFactor;

    private final boolean IS_DEBUG_MODE_ACTIVE;

    private volatile boolean isToTeleport = false;
    // Developer teleport: loading everything round the new place before play goes on
    private boolean teleportLoading;
    private long teleportStarted;
    private GeodesicChunkGrid.Direction teleportTo;

    private GL3 gl;

    NationGenerationManager nationManager;
    private int totalNationsCount;

    InfrastructureManager infraManager;
    SettlementManager settlementManager;
    // The roads and buildings round the player, per geodesic cell at InfrastructureManager.BATCH_LEVEL
    Map<Long, List<InfrastructureObject>> infraCache;
    private final GeodesicChunkGrid infraGrid = new GeodesicChunkGrid(InfrastructureManager.BATCH_LEVEL);
    // The cells wanted round the player's cell (nearest first, those ahead before those behind),
    // worked out again only when the player changes cell, and where they were then
    private long infraWantedFor = Long.MIN_VALUE;
    private List<GeodesicChunkGrid.Cell> infraWanted = List.of();
    private SpherePosition infraWantedFrom;

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
    WorldArtGenerator worldArt;
    private LoadingProgress loading;
    private Runnable onWorldReady;
    private int loadingStep = 0;
    boolean worldReady = false;
    int framesRenderedSinceReady = 0;
    // While loading, grass seeding may keep going for this long per frame; in play it seeds one far chunk per frame
    // (small, so the landing pod's frames keep coming while it's done behind them: at least one step a frame)
    private static final long LOADING_GRASS_BUDGET_NANOS = 8_000_000L;
    // Between rounds the new surroundings are built in slices of this long per frame
    private static final long ROUND_LOADING_BUDGET_NANOS = 45_000_000L;
    // ...and the world's first load, under the landing pod (see deferFirstFrames)
    private static final long POD_LOADING_BUDGET_NANOS = 10_000_000L;
    boolean roundLoading = false;
    private long roundChunkDeadline;
    private long roundLoadingStarted;
    private int roundLoadingFrames;
    private static final float LEAF_DARK_SCALE = 0.65f;
    private static final float LEAF_LIGHT_SCALE = 1.3f;

    private Map<Integer, Texture> nationAtlases;
    private Map<Integer, Integer> nationAtlasSizes;
    Map<Integer, Integer> nationDirections;

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
        mapOverlays.currentDebugFactor = null;
        String startingMap = Dev.property("xenoguesser.minimap");
        if (startingMap != null) {
            for (String part : startingMap.split(",")) {
                try {
                    FactorName factor = FactorName.valueOf(part.trim());
                    // (the animals' population is shown a species at a time: the first, to start with)
                    mapOverlays.currentDebugFactor = new MapOverlay(factor, factor == FactorName.ANIMAL_POPULATION || factor == FactorName.FLORA ? 0 : -1);
                    mapOverlays.ownedOverlays.add(mapOverlays.currentDebugFactor);
                } catch (IllegalArgumentException notGradient) {
                    try {
                        mapOverlays.startingLayers.add(MapPanel.Layer.valueOf(part.trim()));
                        mapOverlays.ownedLayers.add(MapPanel.Layer.valueOf(part.trim()));
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

        // (world units from the sea)
        float GRASS_WATER_MEAN = 0f;
        float GRASS_WATER_STD_DEV = 8000.0f;
                this.GRASS_BASE_ABUNDANCE = 1050f;

        this.grassMoistureFactor = this.regionalManager.createWaterPreference(GRASS_WATER_MEAN, GRASS_WATER_STD_DEV);
        // The rainfall follows the moisture (with the temperature)
        this.regionalManager.setMoistureMap(this.grassMoistureFactor);
        // The grass's old noise tracks, too broad to vary across the planet: still drawn, so the
        // maps drawn after them (the leaves', the soil's, the species') keep theirs
        for (float scale : new float[] { 2e-5f, 1e-5f, 1e-5f }) this.regionalManager.createNoiseMap(scale);

        float LEAF_VARIATION_SCALE = 1e-5f;
        float LEAF_TEMP_MEAN = 0.2f;
        float LEAF_TEMP_STD_DEV = 0.5f;

        this.leafColourNoiseFactor = this.regionalManager.createNoiseMap(LEAF_VARIATION_SCALE);
        this.leafTemperateFactor = this.regionalManager.createTemperaturePreference(LEAF_TEMP_MEAN, LEAF_TEMP_STD_DEV);

        GrassClimate grass = GrassClimate.of(this.regionalManager, this.freezingPoint, this.grassMoistureFactor);
        this.grassAbundanceFactor = grass.abundance();
        this.grassHeightFactor = grass.height();
        this.grassColourFactor = grass.colour();

        this.leafColourFactor = new RegionalFactor.Builder()
            .setWeight(1.5f)
            .setPowerCurve(3.0f)
            .addFactor(this.leafTemperateFactor, 0.35f)
            .addFactor(this.grassMoistureFactor, 0.35f)
            .addFactor(this.leafColourNoiseFactor, 0.3f)
            .build();

        floraSpecies.createFloraSpecies();

        // Each plant's range (see SpeciesRange): spawning and its heatmap both follow it
        landPoints = SpeciesRange.land(worldNoise, seaLevelHeight);
        floraSpecies.floraRanges = floraSpecies.floraRanges(landPoints);

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
        this.townsfolk = new TownsfolkSync(inhabitants);
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
                        return floraSpecies.floraRaw(species, place);
                    }

                    public float share(int species, float raw) {
                        return floraSpecies.floraRanges[species].share(raw);
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
        organismManager.setPlots(p -> infraManager.isPlotLocation(p, 0f));
        infraManager.borders().setLightClock(() -> Multiplayer.isActive() ? getSeconds() - roundClockStart : getSeconds());
        double floraReach = (VIEW_DISTANCE + PREFETCH_RINGS) * PHYSICAL_CHUNK_SIZE;
        floraStream = new CellStream<List<SphereFlora>>(VEGETATION_LEVEL, floraReach, PHYSICAL_CHUNK_SIZE * 1.5, this::buildFloraCell)
                .onChange(added -> { for (SphereFlora tree : added) trees.add(tree.position(), tree.trunk(), tree); },
                        removed -> { for (SphereFlora tree : removed) trees.remove(tree.position(), tree); });
        grassStream = new CellStream<>(VEGETATION_LEVEL, GRASS_VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE, PHYSICAL_CHUNK_SIZE * 1.5, this::buildGrassCell);
        mapOverlays.startBirdsEyeMapRendering();
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
        if (!mapOverlays.mapsReady) {
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
    int screenWidth = 1024, screenHeight = 768;
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
            if (devShots.frameShot != null) {
                if (sceneFbo != 0) gl.glBindFramebuffer(GL3.GL_READ_FRAMEBUFFER, sceneFbo);
                else gl.glReadBuffer(GL.GL_BACK);
                devShots.writeFrameShot(gl, sceneFbo != 0 ? currentWidth : screenWidth, sceneFbo != 0 ? currentHeight : screenHeight);
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

        void applyProjection() {
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
    volatile float zoomWanted = 1f;
    float zoom = 1f;

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
    void easeZoom(double deltaTime) {
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
            roundSpawner.queueNextRound();
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
            System.out.printf("[ROUND] Next round (%d) started %d ms after it was asked for, at %d%n", roundSpawner.spawnedRound,
                    System.currentTimeMillis() - nextRoundWantedAt, System.currentTimeMillis());
            startRoundClock();
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (minimap != null) minimap.finishRoundLoading();
            });
        }
        // Developer aid: the trailer's shots filmed, one after another (see TrailerDirector)
        if (trailerDirector != null && trailerDirector.active() && loading.isFinished() && framesRenderedSinceReady > 30) trailerDirector.beforeFrame();
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
                if (session != null) session.sendRoundBuilt(roundSpawner.spawnedRound);
                setBuilderThreads(false);
                roundSpawner.queueNextRound();
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

        roundSpawner.pumpPrefetch();

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
            if (!hideFirstPerson) hudRenderer.drawHud(drawable);
            return;
        }

        if (MENU_SHOT && loading.isFinished()) devShots.menuShot(gl);
        if (DevShots.SIGN_SHOT && loading.isFinished()) devShots.signShot(gl);
        // Developer aid: -Dxenoguesser.vehicleshot (=moving for one on the move, =driveway for one up a
        // driveway, =door for someone coming out of their front door) stands by it two seconds in
        if (DevShots.VEHICLE_SHOT != null && loading.isFinished()) devShots.vehicleShotFrames++;
        if (DevShots.VEHICLE_SHOT != null && devShots.vehicleShotFrames == DevShots.VEHICLE_SHOT_FRAME + 20) devShots.saveFrame(gl, "vehicle_shot.png");
        if (DevShots.VEHICLE_SHOT != null && loading.isFinished() && devShots.vehicleShotFrames == DevShots.VEHICLE_SHOT_FRAME) {
            SpherePosition at = camera.getSpherePosition();
            double eyes = SphereMath.altitude(at);
            Viewpoint stand = "door".equals(DevShots.VEHICLE_SHOT) ? inhabitants.doorViewpoint(at)
                    : inhabitants.vehicleViewpoint(at, DevShots.VEHICLE_SHOT);
            if (stand != null) {
                camera.setSphereView(SphereMath.atAltitude(stand.place(), eyes), (float) Math.toDegrees(stand.heading()), 0f);
                camera.lookAt(SphereMath.atAltitude(SphereMath.move(stand.place(), stand.heading(), 50.0), eyes - 8.0));
            }
        }
        renderScene(gl);
        // (light-headed, once the oxygen's downgraded: M52; the HUD over it stays clear)
        if (!hideFirstPerson) playerMotion.oxygenBlur.apply(gl, screenWidth, screenHeight, playerMotion.oxygenStrength, playerMotion.oxygenTime);
        if (!hideFirstPerson) hudRenderer.drawHud(drawable);
        speciesPictures.advancePreviews(gl);
        if (trailerDirector != null) trailerDirector.afterFrame(gl, screenWidth, screenHeight);

        // Hand over from the loading screen only once a real frame of the round exists
        if (!loading.isFinished() && ++framesRenderedSinceReady >= 2) {
            if (IS_DEBUG_MODE_ACTIVE) devShots.saveFrame(gl, "first_frame.png");
            loading.finish();
            if (onWorldReady != null) javax.swing.SwingUtilities.invokeLater(onWorldReady);
        } else if (IS_DEBUG_MODE_ACTIVE && loading.isFinished() && ++framesRenderedSinceReady == 150) {
            // A second capture a moment later shows what has moved
            devShots.saveFrame(gl, "later_frame.png");
        } else if (DevShots.FRAME_SEQUENCE > 0 && loading.isFinished() && framesRenderedSinceReady > 150 && framesRenderedSinceReady <= 150 + DevShots.FRAME_SEQUENCE) {
            devShots.saveFrame(gl, "sequence_" + (framesRenderedSinceReady - 150) + ".png");
        }
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
    int uploadCubeMap(int side, int channels, byte[] texels) {
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
    int uploadCubeMap(int side, float[] texels) {
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
    static Vector3 leafDarkColour(Vector3 regional) {
        return new Vector3(regional.x * LEAF_DARK_SCALE, regional.y * LEAF_DARK_SCALE, regional.z * LEAF_DARK_SCALE);
    }

    static Vector3 leafLightColour(Vector3 regional) {
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
        podBeacon.dispose(gl);
        if (rockField != null) rockField.clear(gl);
        hudRenderer.hud.dispose(gl);
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

    /** A plant species' body colours and markings, for floraShader (accent: its orbs', the plant's foliage colour). */
    void floraBodyUniforms(GL3 gl, SpeciesConfig sc, Vector3 accent) {
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
        steps.add(() -> speciesPictures.portraitsMade = new java.awt.Image[organismManager.speciesCount()]);
        for (int i = 0; i < organismManager.speciesCount(); i++) {
            int species = i;
            steps.add(() -> speciesPictures.portraitsMade[species] = organismManager.portrait(gl, species, 96));
        }
        steps.add(() -> speciesPictures.showSpeciesPortraits(speciesPictures.portraitsMade));
        // (and each plant's, likewise: so the map's and the shop's choices have them from the start)
        steps.add(() -> speciesPictures.floraMade = new java.awt.Image[NUM_SPECIES]);
        for (int s = 0; s < NUM_SPECIES; s++) {
            int species = s;
            steps.add(() -> speciesPictures.floraMade[species] = speciesPictures.floraPortrait(gl, species));
        }
        steps.add(() -> speciesPictures.showFloraPortraits(speciesPictures.floraMade));
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
        steps.add(() -> podBeacon.initialise(gl));
        steps.add(() -> {
            rockField = rocks();
            rockField.setKeepout((p, r) -> {
                if (roundSpawner.podCovers(p, r)) return true;
                return infraManager.isRoadLocation(p, r + 2f) || infraManager.isBuildingLocation(p, r + 4f);
            });
            rockField.initialise(gl, terrainBuilders);
        });
        steps.add(() -> hudRenderer.hud.initialise(gl));
        steps.add(roundSpawner::spawnPlayerAtRandomLocation);
        steps.add(() -> createDepthFramebuffer(gl, currentWidth, currentHeight));
        return steps;
    }

    /** The player's own body, and in multiplayer the partner's. */
    private void initialiseBodies() {
        playerBody.initialise(gl);
        Multiplayer session = Multiplayer.active();
        if (session != null) {
            // Multiplayer (M6, M58): the packs on this player's own body in their suit colour, and the
            // partner, seen from outside, in theirs (darker, as lit in the world; grey trim as the pod's)
            playerBody.setColours(new float[] { 0.5f, 0.51f, 0.54f }, session.suit(session.role).trimColour());
            partnerBody = PlayerBody.seenFromOutside();
            partnerBody.setColours(new float[] { 0.2f, 0.21f, 0.23f }, session.suit(session.role.other()).litTrimColour());
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

    // ------------------------------------------------------------------ the next round, got ready ahead

    /**
     * Every spawn and teleport comes through here: the eyes go above what's underfoot there,
     * facing the viewpoint's way.
     */
    void moveToLocation(Viewpoint view) {
        SpherePosition place = SphereMath.atAltitude(view.place(), 0.0);
        double ground = Math.max(seaLevelHeight, groundHeightAt(place));
        camera.setSphereView(SphereMath.atAltitude(place, ground + playerEyeHeight), (float) Math.toDegrees(view.heading()), 0f);
        playerMotion.airborne = false;
        playerMotion.jumpHeight = 0f;
        playerMotion.lastStood = Float.NaN;
        playerMotion.cancelReturn();

        // (what's round the new place is worked out on the builder threads while the loading
        // screen, or the teleport's wait, keeps going)
        updateVisibleChunks(false);
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
    double groundHeightAt(SpherePosition p) {
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
    double groundHeightAt(SpherePosition p, float feet) {
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
                if (Multiplayer.isActive()) organismManager.clear(roundSpawner.roundRandom(roundSpawner.spawnedRound + 1, RoundSpawner.CREATURE_DRAWS).nextLong());
                else organismManager.clear();
        if (rockField != null) rockField.clear(gl);
        inhabitants.clear();
        townsfolk.clear();

        roundSpawner.spawnPlayerAtRandomLocation();
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

    // Points spread evenly over the land (see SpeciesRange.land), for the ranges and the landmarks
    List<GeodesicChunkGrid.Direction> landPoints = List.of();

    /**
     * One cell's trees and shrubs (safe to work out off the GL thread): candidates for each
     * species at its peak density for the cell's area, each kept where it falls by how common
     * the species is just there (SpeciesRange.keep), so they follow the heatmap closely.
     */
    private List<SphereFlora> buildFloraCell(long id) {
        GeodesicChunkGrid.Cell cell = floraStream.grid().cell(id);
        List<SphereFlora> plants = new ArrayList<>();
        SpherePosition middle = SphereMath.atAltitude(cell.centre(), 0.0);
        float area = floraSpecies.areaInChunks(cell);
        float urbanness = settlementManager.getUrbanness(middle);
        for (int s = 0; s < NUM_SPECIES; s++) {
            SpeciesConfig sc = speciesConfigs[s];
            java.util.Random cRand = new java.util.Random(worldSeed ^ (id * 492876847L) ^ ((long) s * 9012431L));
            SpeciesRange range = floraSpecies.floraRanges[s];
            double attempts = range.peak * area * (1.0 - URBAN_FLORA_REDUCTION * urbanness);
            int count = (int) attempts;
            if (cRand.nextFloat() < attempts - count) count++;
            // (in a famous grove, three times as many, kept thick all through it)
            int usual = count;
            boolean grove = infraManager.landmarks().groveNear(s, middle, cell.angularRadius() * Planet.radius());
            if (grove) count = (int) Math.ceil(range.peak * area * FloraSpecies.GROVE_THICKNESS);
            for (int i = 0; i < count; i++) {
                SpherePosition at = SphereMath.atAltitude(cell.point(cRand.nextDouble(), cRand.nextDouble()), 0.0);
                // (every plant draws the same numbers whether or not it's kept, so each is always the same)
                int model = cRand.nextInt(FLORA_VARIATIONS);
                float scale = 0.70f + cRand.nextFloat() * 0.60f;
                float rotation = cRand.nextFloat() * 360.0f;
                float kept = cRand.nextFloat();
                float keep = i < usual ? range.keep(floraSpecies.floraRaw(s, SphereMath.direction(at))) : 0f;
                if (grove) keep = Math.max(keep, 0.9f * infraManager.landmarks().groveBoost(s, at));
                if (kept >= keep) continue;
                float ground = TerrainMesh.getLayeredHeight(at, worldNoise);
                if (ground <= seaLevelHeight + 0.1f || playerMotion.slopeAt(at) >= 0.9f || roundSpawner.podCovers(at, 8f) || underFreshWater(at, ground)) continue;
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
        int attempts = Math.round(perChunk * GRASS_DENSITY * floraSpecies.areaInChunks(cell));
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
            if (roundSpawner.podCovers(at, -2f)) continue;
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

    private java.util.concurrent.Future<GrassBuffer> grassGather;

    /** The grass's instance data gathered for the GPU: the blades (five floats each) and how many. */
    private record GrassBuffer(float[] blades, int count) {
    }

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
                MeshData data = geodesicTerrainBuild.get();
                Mesh mesh = new Mesh(gl, data.vertices(), data.indices());
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
    float freshWaterAt(SpherePosition place) {
        Rivers rivers = worldNoise.rivers;
        if (rivers == null) return Float.NaN;
        return rivers.waterAt(SphereMath.direction(place), TerrainMesh.getLayeredHeight(place, worldNoise));
    }

    /** Whether a place's ground is under a river's or lake's water. */
    boolean underFreshWater(SpherePosition place, float ground) {
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
                GrassBuffer gathered = grassGather.get();
                uploadGrass(gathered.blades(), gathered.count());
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
        java.util.concurrent.Callable<GrassBuffer> gather = () -> {
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
            return new GrassBuffer(staging, written);
        };
        // (straight away when there's none yet; but under the landing pod, on a worker, so its frames keep coming)
        if (all || (drawnGrassInstances == 0 && !(firstFramesDeferred && !worldReady))) {
            try {
                GrassBuffer gathered = gather.call();
                uploadGrass(gathered.blades(), gathered.count());
            } catch (Exception ignored) {
            }
        } else {
            grassGather = terrainBuilders.submit(gather);
        }
    }

    // When this round began (getSeconds()): in multiplayer the clouds drift with the time since
    // then rather than since the window opened, so both PCs' skies match (M19)
    double roundClockStart;

    /** Multiplayer: whether the partner's PC has built the round this one has built (always true in singleplayer). */
    private boolean partnerHasBuilt() {
        Multiplayer session = Multiplayer.active();
        return session == null || session.remoteRoundBuilt(roundSpawner.spawnedRound);
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

        countFrameRate(elapsedTime);

        // Movement uses a capped step so a long hitch doesn't fling the camera
        if (deltaTime > 0.1) { deltaTime = 1.0 / 60.0; }

        playerMotion.movePlayer(deltaTime, elapsedTime);
        if (trailerDirector != null) trailerDirector.placeCamera();

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
        // (the creatures walk paths fixed by the round's clock: see OrganismManager.Path)
        organismManager.update((float) deltaTime, eye, getSeconds() - roundClockStart);
        // (in multiplayer the host's PC moves the townsfolk round both players, and the joiner's
        // draws the host's: see TownsfolkSync, M57)
        Multiplayer session = Multiplayer.active();
        inhabitants.setReplica(session != null && session.role == Multiplayer.Role.JOIN);
        inhabitants.update((float) deltaTime, eye, session != null && session.role == Multiplayer.Role.HOST && partner.hasPose() ? partner.eye() : null);

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
        snowCover.bindSnowCover(gl);

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
        // (and so is the partner, so the host's townsfolk step round them and stop for them)
        if (Multiplayer.isActive() && partner.hasPose()) geodesicCollision.addBody(PARTNER, partner.eye(), PLAYER_RADIUS, true);
        geodesicCollision.endFrame();
        organismManager.render(gl, camera, sunDirection, sunColour, ambientLight, skyRotation, textures.get(skyTextureKey), (float) elapsedTime);
        inhabitants.render(gl, camera, sunDirection, sunColour, ambientLight, skyRotation, textures.get(skyTextureKey), (float) elapsedTime);

        // --- INSTANCED GRASS PASS ---
        renderGrass(gl, sphereViewProjection, eye, skyRotation, skyColour, elapsedTime, skyTextureKey);

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
        // --- THE POD'S BEACON, up into the sky over everything but what stands in front of it ---
        podBeacon.render(gl, sphereViewProjection, eye, landingPod);

        // What's in front of what through the magnifying glass, before the body's drawn over it
        identificationLens.captureLensDepth(gl);
        // (no shadows on the player's own body, drawn in a frame of its own, nor on anything drawn
        // after this frame's world, such as the map's pictures)
        Shader.clearShadows(gl);

        if (hideFirstPerson) return;

        // --- THE PLAYER'S OWN BODY, drawn last and nearest, in the camera's local frame ---
        renderPlayerBody(eye, deltaTime, sunDir, sunColour, localSkyRotation, skyTextureKey);
    }

    /** The frame rate, counted over half-second windows, for the HUD (and the developer's log). */
    private void countFrameRate(double elapsedTime) {
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
    }

    /** The grass, drawn instanced: every blade gathered round the player (see updateVegetation). */
    private void renderGrass(GL3 gl, Matrix4 sphereViewProjection, SpherePosition eye, Matrix4 skyRotation, Vector3 skyColour,
                             double elapsedTime, String skyTextureKey) {
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
    }

    /** The player's own body and what they're holding, posed and drawn in the camera's local frame. */
    private void renderPlayerBody(SpherePosition eye, double deltaTime, float[] sunDir, float[] sunColour, Matrix4 localSkyRotation,
                                  String skyTextureKey) {
        if (gadgetsDropped) {
            gadgetsDropped = false;
            playerBody.putAwayAtOnce();
        }
        playerBody.hold(heldItem < 0 ? PlayerBody.NO_GADGET : gadgetOf(ownedItems.get(heldItem)));
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

    // ------------------------------------------------------------------ multiplayer: poses

    // Where the partner is shown, and their body, seen from outside (null in singleplayer)
    final RemotePlayer partner = new RemotePlayer();
    // The people, vehicles and open doors kept the same on both PCs (made with the inhabitants)
    TownsfolkSync townsfolk;
    PlayerBody partnerBody;
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
        if (session == null) {
            townsfolk.clear();
            return;
        }
        SpherePosition eye = camera.getSpherePosition();
        Vector3 forward = camera.getSphereForwardDirection();
        byte flags = (byte) ((swimming ? Multiplayer.POSE_SWIMMING : 0) | (playerMotion.airborne ? Multiplayer.POSE_AIRBORNE : 0));
        session.publishLocalPose(new Multiplayer.Pose(eye.x(), eye.y(), eye.z(), forward.x, forward.y, forward.z, flags));

        long now = System.currentTimeMillis();
        partner.update(session.remotePoses(), now, deltaTime, p -> groundHeightAt(p), playerEyeHeight);
        // The townsfolk near the joiner, sent from the host's PC and drawn on the joiner's (M57)
        if (session.role == Multiplayer.Role.HOST) townsfolk.hostFrame(session, partner.hasPose() ? partner.eye() : null, now);
        else townsfolk.joinerFrame(session, infraManager, now, (float) deltaTime);
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

    /** Developer aid: looks pitchDegrees up (negative down), keeping the heading. */
    public void lookPitch(float pitchDegrees) {
        float[] f = camera.getLocalForward();
        camera.setSphereView(camera.getSpherePosition(), (float) Math.toDegrees(Math.atan2(f[2], f[0])), pitchDegrees);
    }

    // (the world's time, which stands still while the game's paused: see GameClock)
    double getSeconds() {
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

    public void setMinimap(MapPanel minimap) {
        this.minimap = minimap;
        // Developer aid: -Dxenoguesser.unlockall starts with every map (the items are had from the start anyway)
        if (Dev.flag("xenoguesser.unlockall")) {
            mapOverlays.ownedLayers.addAll(List.of(MapPanel.Layer.values()));
            mapOverlays.ownedOverlays.addAll(mapOverlays.allOverlays());
        }
        for (MapPanel.Layer layer : mapOverlays.startingLayers) minimap.setLayerShown(layer, true);
        // Developer aid: -Dxenoguesser.picturemap=Landmarks (or another map of pictures) starts with it shown
        for (FactorName f : MapOverlays.PICTURE_MAPS) if (f.label().equalsIgnoreCase(Dev.property("xenoguesser.picturemap", ""))) mapOverlays.currentPictureMap = f;
        mapOverlays.refreshMapChoices();
        if (mapOverlays.currentPictureMap != null) mapOverlays.choosePictureMap(mapOverlays.currentPictureMap);
        if (mapOverlays.currentDebugFactor != null) mapOverlays.chooseGradient(mapOverlays.currentDebugFactor);
    }

    // ------------------------------------------------------------------ what the player has bought

    // The things the player can carry, every one had from the start (in this order, so the
    // compass comes out first and the kit is last); and the maps bought in the shop, nothing to begin with: the map's
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
    // Bumped when an item is bought, so the inventory is drawn again
    volatile int itemsVersion;

    /** Which of the body's gadgets an item is. */
    private static int gadgetOf(String item) {
        return switch (item) {
            case COMPASS -> PlayerBody.GADGET_COMPASS;
            case THERMOMETER -> PlayerBody.GADGET_THERMOMETER;
            case ALTIMETER -> PlayerBody.GADGET_ALTIMETER;
            default -> PlayerBody.GADGET_KIT;
        };
    }

    /** The items the player has, in the order bought (the inventory's order, scrolled through top to bottom). */
    List<HudStyle.Item> inventoryItems() {
        List<HudStyle.Item> items = new ArrayList<>();
        for (String item : ownedItems) items.add(new HudStyle.Item(item, itemIcon(item)));
        return items;
    }

    // The place in the inventory of the item in hand (kept out until put away or scrolled off), or -1 for none
    private volatile int heldItem = -1;
    // Set when everything's to be put away at once rather than lowered (a new round)
    private volatile boolean gadgetsDropped;

    /** The place in the inventory of the item in hand, or -1 for none. */
    int heldItem() {
        return heldItem;
    }

    /** Takes out the item in that place in the inventory (0 for the first) and keeps it out; -1, or a place with nothing, puts it away. */
    public void useItem(int index) {
        heldItem = index >= 0 && index < ownedItems.size() ? index : -1;
    }

    /**
     * The mouse wheel: the next place down the inventory (steps above 0) or up it, None (nothing
     * in hand) coming after the last item, and round from there to the first and back.
     */
    public void scrollItems(int steps) {
        int count = ownedItems.size();
        if (count == 0 || steps == 0) return;
        int held = heldItem, place = held < 0 ? count : held;
        int next = Math.floorMod(place + steps, count + 1);
        useItem(next == count ? -1 : next);
    }

    /** Nothing in hand, everything put away at once rather than lowered (each round starts so). */
    public void dropItems() {
        heldItem = -1;
        gadgetsDropped = true;
    }

    // Each plant species' name (see floraName)
    private List<String> floraNames;

    /**
     * Plant species s's name, made as the planet's and the animals' are, and the same as none of
     * them or another plant's.
     */
    synchronized String floraName(int s) {
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

    // The light in the shade by day (darkening towards night)
    static final float[] DAY_AMBIENT = { 0.40f, 0.38f, 0.35f };

    // Where each regional soil colour starts and finishes blending in (from its factor's spread of values)
    volatile float[][] soilBlendRanges;

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
    static final float GRASS_BLADE_HEIGHT = 4.8f;
    // How far a blade's root is sunk per unit of slope (rise over run): about a typical blade's half width
    private static final float GRASS_SLOPE_SINK = 0.8f;
    // The grass's height where it grows shortest and tallest, in metres (before each blade's own variation)
    private static final float GRASS_SHORTEST = 0.07f, GRASS_TALLEST = 1.0f;
    // The lowest and highest the grass's height and colour factors reach on the land, once worked out
    volatile float[] grassHeightSpan, grassColourSpan;

    /** A grass blade's height (as the blade's scale, see GRASS_BLADE_HEIGHT) where its height share is share (0 shortest, 1 tallest). */
    static float bladeHeight(float share) {
        return (GRASS_SHORTEST + (GRASS_TALLEST - GRASS_SHORTEST) * share) * PlayerBody.METRE / GRASS_BLADE_HEIGHT;
    }

    /** Where a value sits in a span, 0 to 1. */
    static float grassShare(float[] span, float value) {
        return Math.max(0f, Math.min(1f, (value - span[0]) / Math.max(1e-4f, span[1] - span[0])));
    }

    float[] grassHeightSpan() {
        float[] span = grassHeightSpan;
        if (span == null) grassHeightSpan = span = mapOverlays.landSpan(grassHeightFactor);
        return span;
    }

    float[] grassColourSpan() {
        float[] span = grassColourSpan;
        if (span == null) grassColourSpan = span = mapOverlays.landSpan(grassColourFactor);
        return span;
    }

    // Points round each plant model {x, y, z, ...} (species, then variation), as generated
    float[][][] floraOutlines;
    // The scene's last view-projection (column major), for finding what's seen through the magnifying glass
    float[] lastSceneViewProjection;

    /**
     * The highest ground on the map, in metres above the sea. Found at places spread evenly
     * over it, then each of the highest few dozen climbed to the top of its hill in finer and
     * finer steps.
     */
    /** A place and the ground's height there, while looking for the highest ground (see highestGround). */
    private record HillTop(SpherePosition place, float height) {
    }

    private float highestGround() {
        // Many places first, keeping the highest few dozen (each a likely different hill)...
        int keep = 40, count = 1_000_000;
        java.util.PriorityQueue<HillTop> tops = new java.util.PriorityQueue<>((p, q) -> Float.compare(p.height(), q.height()));
        double golden = Math.PI * (3 - Math.sqrt(5)), top = Math.sin(MapProjection.CLIP_LATITUDE);
        for (int i = 0; i < count; i++) {
            double y = top * (1 - 2 * (i + 0.5) / count);
            SpherePosition p = Planet.position(Math.asin(y), golden * i, 0.0);
            float h = TerrainMesh.getLayeredHeight(p, worldNoise);
            if (tops.size() < keep) tops.add(new HillTop(p, h));
            else if (h > tops.peek().height()) {
                tops.poll();
                tops.add(new HillTop(p, h));
            }
        }
        // ...then each climbed to the top of its hill, in finer and finer steps (from about the
        // spacing of the places looked at)
        double spacing = Math.sqrt(4 * Math.PI * top / count) * Planet.radius();
        float best = -Float.MAX_VALUE;
        for (HillTop start : tops) {
            SpherePosition at = start.place();
            float height = start.height();
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

    // ------------------------------------------------------------------ the oxygen's downgrade (M52)

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
    volatile boolean controlsOffered;

    /** Shows or hides the offer of the controls over the game. */
    public void setControlsOffered(boolean offered) {
        controlsOffered = offered;
    }

    /** Stops the weather's sound (the game's over). */
    public void stopSounds() {
        rainSound.stop();
    }

    /** The player takes out the compass and keeps it out. */
    public void useCompass() {
        useItem(ownedItems.indexOf(COMPASS));
    }

    /** The player takes out the thermometer and keeps it out. */
    public void useThermometer() {
        useItem(ownedItems.indexOf(THERMOMETER));
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

    /** The world's rocks (made when first needed: the map may want them before the round is set up). */
    synchronized RockField rocks() {
        if (rockField == null) {
            Vector3 bedrock = worldRockColour();
            rockField = new RockField(worldSeed, PHYSICAL_CHUNK_SIZE, seaLevelHeight, worldNoise, new float[] { bedrock.x, bedrock.y, bedrock.z });
        }
        return rockField;
    }

    /** How much rain falls at a place, 0 driest to 1 wettest (see RegionalGenerationManager.rainfallMap). */
    private float rainfallAt(SpherePosition place) {
        return regionalManager.rainfallMap.evaluateAt(place);
    }

    float temperatureAt(SpherePosition place) {
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
      return mapOverlays.currentDebugFactor == null ? null : mapOverlays.currentDebugFactor.factor();
    }

    public int getTotalNationsCount() {
        return this.totalNationsCount;
    }

    /** Developer aid (the trailer's shots): moves there now and waits, drawing nothing, until what's round it is built. */
    void devTeleport(Viewpoint view) {
        moveToLocation(view);
        teleportTo = SphereMath.direction(view.place());
        teleportLoading = true;
        teleportStarted = System.currentTimeMillis();
        setBuilderThreads(true);
    }

    /** Developer aid: whether a teleport's still waiting for its surroundings. */
    boolean devTeleporting() {
        return teleportLoading;
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