import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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
  
  private Light[] lights;
  private Vec3 ambientLight;
  private float nightProportion;
  private float timeOfDay;

  private float seaLevelHeight;
  private Model waterPlaneModel;
  private Shader waterShader;

  private Model skyModel;

  private Shader terrainShader;
  private Renderer terrainRenderer;
  private Material terrainMaterial;
  private Mat4 globalModelMatrix;

  // --- Optimization: Dedicated Depth Pre-Pass Shader ---
  private Shader depthPrePassShader;

  private final float PHYSICAL_CHUNK_SIZE = 100.0f; 
  private final int VIEW_DISTANCE = 24; 
  private PerlinNoise worldNoise;
  private long worldSeed; 

  private float playerEyeHeight = 20.0f;
  
  private int lastChunkX = Integer.MAX_VALUE;
  private int lastChunkZ = Integer.MAX_VALUE;

  private MapPanel minimap;

  // --- Depth Pre-Pass FBO Fields ---
  private int[] depthFBO = new int[1];
  private int[] depthTexture = new int[1];
  private int currentWidth = 1024;  // Fallback initial window dimensions
  private int currentHeight = 768;
  
  public XenoGuesser_GLEventListener(Camera camera, MyKeyboardInput keyboard, PerlinNoise sharedNoise, float sharedSeaLevel, long sharedSeed) {
    this.camera = camera;
    this.keyboard = keyboard;
    this.worldNoise = sharedNoise;
    this.seaLevelHeight = sharedSeaLevel;
    this.worldSeed = sharedSeed; 
    
    this.camera.setPosition(new Vec3(0f, 5f, 15f));
    this.camera.setTarget(new Vec3(0f, 0f, 0f));
  }

  @Override
  public void init(GLAutoDrawable drawable) {
    GL3 gl = drawable.getGL().getGL3();
    
    gl.glClearColor(0.92f, 0.85f, 0.65f, 1.0f); 
    gl.glClearDepth(1.0f);
    
    gl.glEnable(GL.GL_DEPTH_TEST);
    gl.glDepthFunc(GL.GL_LESS);
    gl.glFrontFace(GL.GL_CCW);
    gl.glEnable(GL.GL_CULL_FACE);
    gl.glCullFace(GL.GL_BACK);
    
    initialise(gl);
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
    float farClippingPlane = 2500.0f; 
    Mat4 perspectiveMatrix = Mat4Transform.perspective(45, aspect, 10.0f, farClippingPlane);
    camera.setPerspectiveMatrix(perspectiveMatrix);

    // Recreate Depth Framebuffer to match the active fullscreen window canvas size
    createDepthFramebuffer(gl, width, height);
  }

  @Override
  public void display(GLAutoDrawable drawable) {
    GL3 gl = drawable.getGL().getGL3();
    render(gl);
  }

  @Override
  public void dispose(GLAutoDrawable drawable) {
    GL3 gl = drawable.getGL().getGL3();
    for (Model model : chunkCache.values()) {
        if (model.mesh != null) model.mesh.dispose(gl);
    }
    // Clean up FBO resources allocation safely
    gl.glDeleteFramebuffers(1, depthFBO, 0);
    gl.glDeleteTextures(1, depthTexture, 0);
  }

  private void createDepthFramebuffer(GL3 gl, int width, int height) {
    // Delete old configurations if reshaping
    if (depthFBO[0] != 0) {
        gl.glDeleteFramebuffers(1, depthFBO, 0);
        gl.glDeleteTextures(1, depthTexture, 0);
    }

    // 1. Generate Framebuffer
    gl.glGenFramebuffers(1, depthFBO, 0);
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, depthFBO[0]);

    // 2. Generate Texture Target to house the raw non-linear depth values
    gl.glGenTextures(1, depthTexture, 0);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, depthTexture[0]);
    gl.glTexImage2D(GL3.GL_TEXTURE_2D, 0, GL3.GL_DEPTH_COMPONENT32F, width, height, 0, GL3.GL_DEPTH_COMPONENT, GL3.GL_FLOAT, null);
    
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_NEAREST);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_NEAREST);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);

    // 3. Attach Depth Map object structurally to our custom Framebuffer target configuration
    gl.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, GL3.GL_TEXTURE_2D, depthTexture[0], 0);
    
    // Instruct OpenGL explicitly that we are not tracking color buffers during this pass
    gl.glDrawBuffer(GL3.GL_NONE);
    gl.glReadBuffer(GL3.GL_NONE);

    // Verify system stability configuration state context
    if (gl.glCheckFramebufferStatus(GL3.GL_FRAMEBUFFER) != GL3.GL_FRAMEBUFFER_COMPLETE) {
        System.err.println("Critical Error: Depth Framebuffer Configuration Failed.");
    }
    
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
  }

  private Vec3 getSunPosition() {
    float sunDistance = 2350.0f; 
    if (timeOfDay > 1) { timeOfDay -= 1; }
    Vec3 cameraPosition = camera.getPosition();
    float x = cameraPosition.x + sunDistance * (float)(Math.sin(2 * Math.PI * timeOfDay + Math.PI));
    float y = cameraPosition.y + sunDistance * (float)(Math.cos(2 * Math.PI * timeOfDay + Math.PI));
    float z = cameraPosition.z;
    return new Vec3(x, y, z);
  }

  private void initialise(GL3 gl) {
    textures = new TextureLibrary();
    textures.add(gl, "dirt_diffuse", "assets/textures/dirt_diffuse.png");
    textures.add(gl, "water_diffuse", "assets/textures/water_diffuse.png");
    textures.add(gl, "sky", "assets/textures/sky.png");
    textures.add(gl, "sun_glow", "assets/textures/sun_glow.png");

    Texture waterTexInstance = textures.get("water_diffuse");
    waterTexInstance.bind(gl);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT);

    ambientLight = new Vec3(0.4f, 0.38f, 0.35f); 
    nightProportion = 0.0f;
    timeOfDay = 0.35f;

    lights = new Light[1];
    float lightSize = 275.0f; 
    
    Light l = new Light(gl, camera, true, new Vec3(0,0,0), lightSize, textures.get("sun_glow"));
    Material m = new Material();
    
    m.setFullDiffuse(1.0f, 0.95f, 0.95f);  
    m.setFullSpecular(1.0f, 0.0f, 0.0f);   
    m.setDimmedDiffuseSpecular(1f);
    l.setMaterial(m);
    lights[0] = l;

    skyModel = makeSkybox(gl, "assets/shaders/fs_single_sky.txt", textures.get("sky"));
    
    chunkCache = new HashMap<>();

    terrainShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_standard_d.txt");
    
    // Initialize the high-performance Depth Pre-pass shader
    depthPrePassShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_depth_only.txt");

    terrainMaterial = new Material(new Vec3(0.1f, 0.5f, 0.91f), new Vec3(0.1f, 0.5f, 0.91f), new Vec3(0.4f, 0.2f, 0.2f), 4.0f);
    terrainMaterial.setDiffuseMap(textures.get("dirt_diffuse"));
    terrainRenderer = new Renderer();
    globalModelMatrix = new Mat4(1);

    waterShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_water.txt");
    Material waterMaterial = new Material(
        new Vec3(0.01f, 0.31f, 0.55f),  
        new Vec3(0.01f, 0.31f, 0.55f),  
        new Vec3(1.0f, 0.3f, 0.3f),  
        32.0f                        
    );
    waterMaterial.setDiffuseMap(textures.get("water_diffuse"));

    Renderer waterRenderer = new Renderer(); 
    Mat4 waterModelMatrix = new Mat4(1);
    Mesh waterMesh = new Mesh(gl, TwoTriangles.vertices, TwoTriangles.indices);  
    waterPlaneModel = new Model("ocean_surface", waterMesh, waterModelMatrix, waterShader, waterMaterial, waterRenderer, lights, camera);

    createDepthFramebuffer(gl, currentWidth, currentHeight);
    spawnPlayerAtRandomLocation(gl);
  }

  private void spawnPlayerAtRandomLocation(GL3 gl) {
    java.util.Random dynamicRand = new java.util.Random();
    float totalPlayableRegion = (VIEW_DISTANCE * PHYSICAL_CHUNK_SIZE) * 50.0f;
    float halfRegion = totalPlayableRegion / 2.0f;
    
    float spawnX = 0.0f;
    float spawnZ = 0.0f;
    float terrainHeightAtSpawn = 0.0f;
    boolean foundDryLand = false;

    while (!foundDryLand) {
        spawnX = (dynamicRand.nextFloat() * totalPlayableRegion) - halfRegion;
        spawnZ = (dynamicRand.nextFloat() * totalPlayableRegion) - halfRegion;
        terrainHeightAtSpawn = TerrainMesh.getLayeredHeight(spawnX, spawnZ, worldNoise);
        if (terrainHeightAtSpawn > seaLevelHeight) {
            foundDryLand = true;
        }
    }

    if (this.minimap != null) {
        this.minimap.setPlayerSpawnLocation(spawnX, spawnZ);
    }

    camera.setPosition(new Vec3(spawnX, terrainHeightAtSpawn + playerEyeHeight, spawnZ));
    camera.setTarget(new Vec3(spawnX, terrainHeightAtSpawn + playerEyeHeight, spawnZ - 10.0f));

    lastChunkX = (int) Math.floor((spawnX + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
    lastChunkZ = (int) Math.floor((spawnZ + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
    
    updateVisibleChunks(gl);
  }

  public void resetToNextRound(GL3 gl) {
      for (Model model : chunkCache.values()) {
          if (model.mesh != null) model.mesh.dispose(gl);
      }
      chunkCache.clear();

      lastChunkX = Integer.MAX_VALUE;
      lastChunkZ = Integer.MAX_VALUE;

      if (minimap != null) {
          minimap.resetMapState(); 
      }

      spawnPlayerAtRandomLocation(gl);
  }

  private void updateVisibleChunks(GL3 gl) {
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
  }

  private void render(GL3 gl) {
    if (minimap != null && minimap.isNextRoundRequested()) {
        minimap.clearNextRoundRequest();
        resetToNextRound(gl);
        return;
    }

    double elapsedTime = getSeconds() - startTime;
    double deltaTime = elapsedTime - lastElapsedTime;
    lastElapsedTime = elapsedTime;

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

    Vec3 currentPos = camera.getPosition();
    float rawGroundHeight = TerrainMesh.getLayeredHeight(currentPos.x, currentPos.z, worldNoise);
    float targetCameraHeight = rawGroundHeight + playerEyeHeight;

    float dynamicSmoothingFactor = 5.0f * (float)deltaTime;
    if (dynamicSmoothingFactor > 1.0f) dynamicSmoothingFactor = 1.0f;

    float smoothedHeight = currentPos.y + (targetCameraHeight - currentPos.y) * dynamicSmoothingFactor;
    camera.setHeight(smoothedHeight);

    lights[0].setPosition(getSunPosition());
    Vec3 sunPos = lights[0].getPosition();

    int currentChunkX = (int) Math.floor((camera.getPosition().x + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
    int currentChunkZ = (int) Math.floor((camera.getPosition().z + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);

    if (currentChunkX != lastChunkX || currentChunkZ != lastChunkZ) {
        lastChunkX = currentChunkX;
        lastChunkZ = currentChunkZ;
        updateVisibleChunks(gl);
    }

    float sunAngle = (float)Math.atan2(sunPos.y, sunPos.x);
    float degSunAngle = (float)Math.toDegrees(sunAngle);
    float twighlightZoneSize = 30f;
    if (degSunAngle < 0f) { nightProportion = 1f; }
    else if (degSunAngle > 180f - twighlightZoneSize) { nightProportion = (degSunAngle - (180f - twighlightZoneSize)) / twighlightZoneSize; }
    else if (degSunAngle < twighlightZoneSize) { nightProportion = ((twighlightZoneSize - degSunAngle) / twighlightZoneSize); }
    else { nightProportion = 0f; }

    ambientLight = Vec3.multiply(new Vec3(0.4f, 0.38f, 0.35f),
      Math.max((float)Math.sin(sunAngle) * lights[0].getBrightnessProportion(), 0.05f));

    // ========================================================
    // PASS 1: HIGH-PERFORMANCE DEPTH PRE-PASS TO FRAMEBUFFER
    // ========================================================
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, depthFBO[0]);
    gl.glClear(GL3.GL_DEPTH_BUFFER_BIT); 
    gl.glEnable(GL3.GL_DEPTH_TEST);
    gl.glEnable(GL3.GL_CULL_FACE);

    // Swap shaders temporarily to use the lightweight depth pipeline 
    for (Model plane : chunkCache.values()) { 
        plane.setShader(depthPrePassShader);
        plane.render(gl, ambientLight, nightProportion); 
        plane.setShader(terrainShader); // Immediately swap back for full color shading
    }
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);

    // ========================================================
    // PASS 2: RENDER COMPLETE LIGHT SCENE TO CANVAS SCREEN
    // ========================================================
    gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

    // --- SKYBOX PASS ---
    gl.glDisable(GL.GL_DEPTH_TEST); 
    gl.glDisable(GL.GL_CULL_FACE); 

    Mat4 skyTransform = Mat4.multiply(
        Mat4Transform.translate(camera.getPosition()),
        Mat4Transform.scale(2400.0f, 2400.0f, 2400.0f) 
    );
    skyModel.setModelMatrix(skyTransform);
    skyModel.render(gl, ambientLight, nightProportion); 

    gl.glEnable(GL.GL_CULL_FACE);
    gl.glEnable(GL.GL_DEPTH_TEST);

    // --- SUN PASS ---
    gl.glEnable(GL.GL_BLEND);
    gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
    gl.glDepthMask(false);
    
    lights[0].render(gl); 
    
    gl.glDepthMask(true);
    gl.glDisable(GL.GL_BLEND);

    // --- LANDSCAPE PASS ---
    for (Model plane : chunkCache.values()) { 
        plane.render(gl, ambientLight, nightProportion); 
    }

    // --- OCEAN BLENDING PASS ---
    gl.glEnable(GL.GL_BLEND);
    gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

    Vec3 camPos = camera.getPosition();
    float waterCoverageSize = PHYSICAL_CHUNK_SIZE * VIEW_DISTANCE * 2.0f;

    Mat4 waterMatrix = Mat4Transform.translate(camPos.x, seaLevelHeight - 0.05f, camPos.z);
    waterMatrix = Mat4.multiply(waterMatrix, Mat4Transform.scale(waterCoverageSize, 1.0f, waterCoverageSize));

    waterShader.use(gl);
    waterShader.setFloat(gl, "seaLevelHeight", seaLevelHeight);
    waterShader.setVec2(gl, "windowSize", new Vec2((float)currentWidth, (float)currentHeight));

    // Bind depth texture to Texture Unit 1
    gl.glActiveTexture(GL3.GL_TEXTURE1);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, depthTexture[0]);
    waterShader.setInt(gl, "terrainDepthTexture", 1);

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
    System.out.println("Global Target Ocean Coverage: " + (waterProportion * 100f) + "%");
    System.out.println("Locked Global Sea Level Height: " + calculatedSeaLevel);
    return calculatedSeaLevel;
  }

  public void setMinimap(MapPanel minimap) {
    this.minimap = minimap;
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
}