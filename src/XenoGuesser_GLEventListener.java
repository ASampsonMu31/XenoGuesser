import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;
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

  private Shader depthPrePassShader;

  private final float PHYSICAL_CHUNK_SIZE = 100.0f; 
  private final int VIEW_DISTANCE = 24; 
  private PerlinNoise worldNoise;
  private long worldSeed; 

  private float playerEyeHeight = 20.0f;
  
  private int lastChunkX = Integer.MAX_VALUE;
  private int lastChunkZ = Integer.MAX_VALUE;

  private MapPanel minimap;
  private GameHUD gameHUD; 

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
  
  private final int GRASS_VIEW_DISTANCE = 24; 
  private final int GRASS_PER_CHUNK = 300; 

  // --- Optimized Zero-Allocation VRAM Streaming Fields ---
  private java.nio.FloatBuffer persistentGrassBuffer;
  private int currentGrassGPUCapacityFloats = 0;

  private CompassHUD compassHUD;

  public XenoGuesser_GLEventListener(Camera camera, MyKeyboardInput keyboard, PerlinNoise sharedNoise, float sharedSeaLevel, long sharedSeed) {
    this.camera = camera;
    this.keyboard = keyboard;
    this.worldNoise = sharedNoise;
    this.seaLevelHeight = sharedSeaLevel;
    this.worldSeed = sharedSeed; 
    
    this.camera.setPosition(new Vec3(0f, 5f, 15f));
    this.camera.setTarget(new Vec3(0f, 0f, 0f));
  }

  public void setGameHUD(GameHUD gameHUD) {
    this.gameHUD = gameHUD;
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

    createDepthFramebuffer(gl, width, height);
  }

  @Override
  public void display(GLAutoDrawable drawable) {
      GL3 gl = drawable.getGL().getGL3();
      
      if (minimap != null && minimap.isFullScreenRevealMode()) {
          gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
          return; 
      }
      
      render(gl);
  }

  @Override
  public void dispose(GLAutoDrawable drawable) {
    GL3 gl = drawable.getGL().getGL3();
    for (Model model : chunkCache.values()) {
        if (model.mesh != null) model.mesh.dispose(gl);
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
    l.setMaterial(m);
    lights[0] = l;

    skyModel = makeSkybox(gl, "assets/shaders/fs_single_sky.txt", textures.get("sky"));
    
    chunkCache = new HashMap<>();
    grassCache = new HashMap<>(); 

    terrainShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_standard_d.txt");
    depthPrePassShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_depth_only.txt");

    terrainMaterial = new Material(
        new Vec3(1.0f, 1.0f, 1.0f), 
        new Vec3(1.0f, 1.0f, 1.0f), 
        new Vec3(0.1f, 0.1f, 0.1f), 
        4.0f                                                                                                                                                                                                                                                                                                                
    );
    terrainMaterial.setDiffuseMap(textures.get("dirt_diffuse"));
    terrainRenderer = new Renderer();
    globalModelMatrix = new Mat4(1);

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

    // --- Instanced Grass Geometry Setup with Mipmapping ---
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
        // Position (X,Y,Z), UV (U,V)
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
    
    gl.glEnableVertexAttribArray(2);
    gl.glVertexAttribPointer(2, 3, GL3.GL_FLOAT, false, 3 * 4, 0);
    gl.glVertexAttribDivisor(2, 1); 

    gl.glBindVertexArray(0);

    spawnPlayerAtRandomLocation(gl);
    createDepthFramebuffer(gl, currentWidth, currentHeight);
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
    
    updateVisibleChunks(gl, true);
  }

  public void resetToNextRound(GL3 gl) {
      for (Model model : chunkCache.values()) {
          if (model.mesh != null) model.mesh.dispose(gl);
      }
      chunkCache.clear();
      grassCache.clear(); 

      lastChunkX = Integer.MAX_VALUE;
      lastChunkZ = Integer.MAX_VALUE;
      totalGrassInstances = 0; 

      if (minimap != null) {
          minimap.resetMapState(); 
      }

      spawnPlayerAtRandomLocation(gl);
  }

  private void updateVisibleChunks(GL3 gl, boolean forceImmediate) {
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

    Map<String, Boolean> activeGrassKeys = new HashMap<>();
    for (int cz = lastChunkZ - GRASS_VIEW_DISTANCE; cz <= lastChunkZ + GRASS_VIEW_DISTANCE; cz++) {
        for (int cx = lastChunkX - GRASS_VIEW_DISTANCE; cx <= lastChunkX + GRASS_VIEW_DISTANCE; cx++) {
            activeGrassKeys.put(cx + "_" + cz, true);
        }
    }
    grassCache.keySet().retainAll(activeGrassKeys.keySet());

    totalGrassInstances = 0;
    boolean generatedThisFrame = false; 

    // Look ahead to calculate exactly how many instances we're building
    for (int cz = lastChunkZ - GRASS_VIEW_DISTANCE; cz <= lastChunkZ + GRASS_VIEW_DISTANCE; cz++) {
        for (int cx = lastChunkX - GRASS_VIEW_DISTANCE; cx <= lastChunkX + GRASS_VIEW_DISTANCE; cx++) {
            String key = cx + "_" + cz;
            float[] chunkGrassData = grassCache.get(key);

            if (chunkGrassData == null) {
                if (generatedThisFrame && !forceImmediate) {
                    continue; 
                }

                ArrayList<Float> tempChunkCoords = new ArrayList<>();
                float chunkMinX = (cx * PHYSICAL_CHUNK_SIZE) - (PHYSICAL_CHUNK_SIZE / 2.0f);
                float chunkMinZ = (cz * PHYSICAL_CHUNK_SIZE) - (PHYSICAL_CHUNK_SIZE / 2.0f);

                for (int i = 0; i < GRASS_PER_CHUNK; i++) {
                    long bladeSeed = worldSeed 
                                ^ ((long) cx * 73731703L) 
                                ^ ((long) cz * 19349663L) 
                                ^ ((long) i * 2147483647L);

                    double pseudoRandomValue1 = Math.sin(bladeSeed * 12.9898) * 43758.5453123;
                    double pseudoRandomValue2 = Math.cos(bladeSeed * 78.2330) * 43758.5453123;

                    double rand1 = Math.abs(pseudoRandomValue1) % 1.0;
                    double rand2 = Math.abs(pseudoRandomValue2) % 1.0;

                    float worldX = chunkMinX + (float)(rand1 * PHYSICAL_CHUNK_SIZE);
                    float worldZ = chunkMinZ + (float)(rand2 * PHYSICAL_CHUNK_SIZE);
                    float worldY = TerrainMesh.getLayeredHeight(worldX, worldZ, worldNoise);

                    if (worldY > seaLevelHeight + 0.1f) {
                        tempChunkCoords.add(worldX);
                        tempChunkCoords.add(worldY);
                        tempChunkCoords.add(worldZ);
                    }
                }

                chunkGrassData = new float[tempChunkCoords.size()];
                for (int idx = 0; idx < tempChunkCoords.size(); idx++) {
                    chunkGrassData[idx] = tempChunkCoords.get(idx);
                }
                grassCache.put(key, chunkGrassData);
                generatedThisFrame = true; 
            }
            totalGrassInstances += (chunkGrassData.length / 3);
        }
    }

    if (totalGrassInstances > 0) {
        int requiredFloats = totalGrassInstances * 3;

        // FIXED: Only resize/reallocate the buffer store if layout capacity expanded
        if (persistentGrassBuffer == null || requiredFloats > currentGrassGPUCapacityFloats) {
            currentGrassGPUCapacityFloats = (int) (requiredFloats * 1.2f); // 20% safety pad
            persistentGrassBuffer = com.jogamp.common.nio.Buffers.newDirectFloatBuffer(currentGrassGPUCapacityFloats);
            
            gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassChunkCoordVBO);
            gl.glBufferData(GL3.GL_ARRAY_BUFFER, currentGrassGPUCapacityFloats * 4L, null, GL3.GL_DYNAMIC_DRAW);
        }

        persistentGrassBuffer.clear();

        // Load coordinate points seamlessly straight into our persistent direct byte allocation
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

        // FIXED: Stream into existing allocated GPU Memory space cleanly without dropping VRAM blocks
        gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, grassChunkCoordVBO);
        gl.glBufferSubData(GL3.GL_ARRAY_BUFFER, 0, persistentGrassBuffer.limit() * 4L, persistentGrassBuffer);
        gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, 0);
    }
  }

  private void render(GL3 gl) {
    if (minimap != null && minimap.isNextRoundRequested()) {
        minimap.clearNextRoundRequest();
        minimap.resetMapState(); 
        resetToNextRound(gl);
        return;
    }

    double elapsedTime = getSeconds() - startTime;
    double deltaTime = elapsedTime - lastElapsedTime;
    lastElapsedTime = elapsedTime;

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

    float dynamicSmoothingFactor = 5.0f * (float)deltaTime;
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
        updateVisibleChunks(gl, false);
    }

    float sunAngle = (float)Math.atan2(sunPos.y, sunPos.x);
    float degSunAngle = (float)Math.toDegrees(sunAngle);
    float twighlightZoneSize = 30f;
    if (degSunAngle < 0f) { nightProportion = 1f; }
    else if (degSunAngle > 180f - twighlightZoneSize) { nightProportion = (degSunAngle - (180f - twighlightZoneSize)) / twighlightZoneSize; }
    else if (degSunAngle < twighlightZoneSize) { nightProportion = ((twighlightZoneSize - degSunAngle) / twighlightZoneSize); }
    else { nightProportion = 0f; }

    ambientLight = new Vec3(0.2f, 0.2f, 0.2f);


    if (this.compassHUD != null) {
        Vec3 cameraLookDir = camera.getForwardDirection(); 
        this.compassHUD.updateHeading(cameraLookDir);
    }

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

    Mat4 skyTransform = Mat4.multiply(
        Mat4Transform.translate(camera.getPosition()),
        Mat4Transform.scale(2400.0f, 2400.0f, 2400.0f) 
    );
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

    terrainShader.use(gl);
    for (Model plane : chunkCache.values()) { 
        plane.render(gl, ambientLight, nightProportion); 
    }
    
    // --- INSTANCED FOLIAGE RENDERING PASS ---
    if (totalGrassInstances > 0) {
      gl.glDisable(GL.GL_CULL_FACE); 

      grassShader.use(gl);
      
      gl.glUniformMatrix4fv(gl.glGetUniformLocation(grassShader.getID(), "view"), 1, false, camera.getViewMatrix().toFloatArrayForGLSL(), 0);
      gl.glUniformMatrix4fv(gl.glGetUniformLocation(grassShader.getID(), "projection"), 1, false, camera.getPerspectiveMatrix().toFloatArrayForGLSL(), 0);
      Vec3 camPos = camera.getPosition();
      gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "cameraPos"), camPos.x, camPos.y, camPos.z);
      
      gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "ambientLight"), ambientLight.x, ambientLight.y, ambientLight.z);
      gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "sunColor"), 1.0f, 0.95f, 0.95f); 

      gl.glUniform1i(gl.glGetUniformLocation(grassShader.getID(), "grassPerChunk"), GRASS_PER_CHUNK);
      gl.glUniform1i(gl.glGetUniformLocation(grassShader.getID(), "worldSeed"), (int)(worldSeed & 0xFFFF));

      Vec3 camPosForSun = camera.getPosition();
      Vec3 direction = new Vec3(sunPos.x - camPosForSun.x, sunPos.y - camPosForSun.y, sunPos.z - camPosForSun.z);
      float len = (float)Math.sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z);
      if (len > 0.0f) {
          direction = new Vec3(direction.x / len, direction.y / len, direction.z / len);
      }
      gl.glUniform3f(gl.glGetUniformLocation(grassShader.getID(), "sunDir"), direction.x, direction.y, direction.z);
      gl.glUniform1f(gl.glGetUniformLocation(grassShader.getID(), "time"), (float)elapsedTime);

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
    System.out.println("Global Target Ocean Coverage: " + (waterProportion * 100f) + "%");
    System.out.println("Locked Global Sea Level Height: " + calculatedSeaLevel);
    return calculatedSeaLevel;
  }

  public void setMinimap(MapPanel minimap) {
    this.minimap = minimap;
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
}