import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
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
  private Model waterSphereModel; 
  private Shader waterShader;
  private Renderer waterRenderer;

  private Model skyModel;

  private Shader terrainShader;
  private Renderer terrainRenderer; // This will handle our rendering work safely
  private Material terrainMaterial;
  private Mat4 globalModelMatrix;

  private Shader depthPrePassShader;

  private final float PHYSICAL_CHUNK_SIZE = 100.0f; 
  private final int VIEW_DISTANCE = 6; 
  private PerlinNoise worldNoise;
  private long worldSeed; 

  private float playerEyeHeight = 20.0f;
  
  private int lastFace = -1;
  private int lastChunkX = Integer.MAX_VALUE;
  private int lastChunkZ = Integer.MAX_VALUE;

  // Track neighboring face state to preserve chunks on transitions
  private int previousFace = -1;
  private int prevChunkX = Integer.MAX_VALUE;
  private int prevChunkZ = Integer.MAX_VALUE;

  private MapPanel minimap;
  private GameHUD gameHUD; 

  private int[] depthFBO = new int[1];
  private int[] depthTexture = new int[1];
  private int currentWidth = 1024;  
  private int currentHeight = 768;
  
  private float fpsSmoothing = 0.95f; 
  private double smoothedFps = 60.0;  

  private float seedOffsetX;
  private float seedOffsetY;
  private float seedOffsetZ;

  private int frameCount = 0;
  private float smoothedCameraRadius = -1.0f;

  public XenoGuesser_GLEventListener(Camera camera, MyKeyboardInput keyboard, PerlinNoise sharedNoise, float sharedSeaLevel, long sharedSeed) {
    this.camera = camera;
    this.keyboard = keyboard;
    this.worldNoise = sharedNoise;
    this.seaLevelHeight = sharedSeaLevel;
    this.worldSeed = sharedSeed; 
    
    java.util.Random seedGenerator = new java.util.Random(sharedNoise.hashCode());
    this.seedOffsetX = seedGenerator.nextFloat() * 50000.0f;
    this.seedOffsetY = seedGenerator.nextFloat() * 50000.0f;
    this.seedOffsetZ = seedGenerator.nextFloat() * 50000.0f;

    this.camera.setPosition(new Vec3(0f, PlanetConfig.planetRadius + 500f, 0f));
    this.camera.setTarget(new Vec3(0f, 0f, 0f));
  }

  public void setGameHUD(GameHUD gameHUD) {
    this.gameHUD = gameHUD;
  }

  @Override
  public void init(GLAutoDrawable drawable) {
    GL3 gl = drawable.getGL().getGL3();
    
    gl.glClearColor(0.1f, 0.11f, 0.16f, 1.0f); 
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
    float farClippingPlane = Math.max(6000.0f, PlanetConfig.planetRadius * 4.0f); 
    Mat4 perspectiveMatrix = Mat4Transform.perspective(45, aspect, 1.0f, farClippingPlane);
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
    float sunDistance = Math.max(4500.0f, PlanetConfig.planetRadius * 3.0f); 
    if (timeOfDay > 1) { timeOfDay -= 1; }
    float x = sunDistance * (float)(Math.sin(2 * Math.PI * timeOfDay + Math.PI));
    float y = sunDistance * (float)(Math.cos(2 * Math.PI * timeOfDay + Math.PI));
    return new Vec3(x, y, 0.0f);
  }

  private void initialise(GL3 gl) {
    textures = new TextureLibrary();
    textures.add(gl, "dirt_diffuse", "assets/textures/dirt_diffuse.png");
    textures.add(gl, "water_diffuse", "assets/textures/water_diffuse.png");
    textures.add(gl, "sky", "assets/textures/sky.png");
    textures.add(gl, "sun_glow", "assets/textures/sun_glow.png");

    Texture dirtTexInstance = textures.get("dirt_diffuse");
    dirtTexInstance.bind(gl);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
    gl.glGenerateMipmap(GL.GL_TEXTURE_2D);

    Texture waterTexInstance = textures.get("water_diffuse");
    waterTexInstance.bind(gl);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT);

    ambientLight = new Vec3(0.4f, 0.38f, 0.35f); 
    nightProportion = 0.0f;
    timeOfDay = 0.35f;

    lights = new Light[1];
    float lightSize = 400.0f; 
    
    Light l = new Light(gl, camera, true, new Vec3(0,0,0), lightSize, textures.get("sun_glow"));
    Material m = new Material();
    m.setFullDiffuse(1.0f, 0.95f, 0.95f);  
    m.setFullSpecular(1.0f, 1.0f, 1.0f);   
    m.setDimmedDiffuseSpecular(1f);
    l.setMaterial(m);
    lights[0] = l;

    skyModel = makeSkybox(gl, "assets/shaders/fs_single_sky.txt", textures.get("sky"));
    chunkCache = new HashMap<>();

    terrainShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_standard_d.txt");
    depthPrePassShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_depth_only.txt");

    terrainMaterial = new Material(new Vec3(0.1f, 0.5f, 0.91f), new Vec3(0.1f, 0.5f, 0.91f), new Vec3(0.1f, 0.1f, 0.1f), 8.0f);
    terrainMaterial.setDiffuseMap(textures.get("dirt_diffuse"));
    
    // Set up our terrain renderer instance and scale mapping
    terrainRenderer = new Renderer();
    float textureTilingInterval = 4.0f;
    terrainRenderer.setScale(new Vec2(PHYSICAL_CHUNK_SIZE / textureTilingInterval, PHYSICAL_CHUNK_SIZE / textureTilingInterval));
    
    globalModelMatrix = new Mat4(1);

    waterShader = new Shader(gl, "assets/shaders/vs_standard.txt", "assets/shaders/fs_water.txt");
    Material waterMaterial = new Material(
        new Vec3(0.01f, 0.31f, 0.55f),  
        new Vec3(0.01f, 0.31f, 0.55f),  
        new Vec3(1.0f, 1.0f, 1.0f),  
        64.0f                                    
    );
    waterMaterial.setDiffuseMap(textures.get("water_diffuse"));

    waterRenderer = new Renderer(); 
    Mat4 waterModelMatrix = new Mat4(1);
    GeodesicSphereGenerator.MeshData geoWaterData = GeodesicSphereGenerator.generate(4);
    Mesh waterMesh = new Mesh(gl, geoWaterData.vertices, geoWaterData.indices);  

    waterSphereModel = new Model("ocean_surface", waterMesh, waterModelMatrix, waterShader, waterMaterial, waterRenderer, lights, camera);

    createDepthFramebuffer(gl, currentWidth, currentHeight);
    spawnPlayerOnSphere(gl);
  }

  private void spawnPlayerOnSphere(GL3 gl) {
    java.util.Random dynamicRand = new java.util.Random();
    boolean foundDryLand = false;
    Vec3 randomRadialDir = new Vec3(0, 1, 0);
    float terrainHeightAtSpawn = 0.0f;

    while (!foundDryLand) {
        float theta = dynamicRand.nextFloat() * (float)(2.0 * Math.PI);
        float phi = (float)Math.acos(2.0 * dynamicRand.nextFloat() - 1.0);
        
        float rx = (float)(Math.sin(phi) * Math.cos(theta));
        float ry = (float)(Math.sin(phi) * Math.sin(theta));
        float rz = (float)Math.cos(phi);
        
        randomRadialDir = new Vec3(rx, ry, rz);
        randomRadialDir.normalize();
        
        terrainHeightAtSpawn = TerrainMesh.getLayeredHeight3D(randomRadialDir, seedOffsetX, seedOffsetY, seedOffsetZ, worldNoise);
        if (terrainHeightAtSpawn > seaLevelHeight) {
            foundDryLand = true;
        }
    }

    float spawnRadius = PlanetConfig.planetRadius + terrainHeightAtSpawn + playerEyeHeight;
    Vec3 spawnPosition = Vec3.multiply(randomRadialDir, spawnRadius);

    Vec3 fallbackAxis = new Vec3(0, 0, 1);
    if (Math.abs(Vec3.dotProduct(randomRadialDir, fallbackAxis)) > 0.99f) {
        fallbackAxis = new Vec3(0, 1, 0);
    }
    
    Vec3 spawnFront = Vec3.crossProduct(randomRadialDir, fallbackAxis);
    spawnFront.normalize();
    Vec3 spawnTarget = Vec3.add(spawnPosition, spawnFront);

    this.camera.setWorldUp(randomRadialDir); 
    this.camera.setPosition(spawnPosition);
    this.camera.setTarget(spawnTarget);
  }

  public void resetToNextRound(GL3 gl) {
      for (Model model : chunkCache.values()) {
          if (model.mesh != null) model.mesh.dispose(gl);
      }
      chunkCache.clear();
      lastFace = -1;
      lastChunkX = Integer.MAX_VALUE;
      lastChunkZ = Integer.MAX_VALUE;
      previousFace = -1;
      prevChunkX = Integer.MAX_VALUE;
      prevChunkZ = Integer.MAX_VALUE;
      smoothedCameraRadius = -1.0f;

      if (minimap != null) {
          minimap.resetMapState(); 
      }
      spawnPlayerOnSphere(gl);
  }

  private void updateVisibleChunks(GL3 gl) {
    Map<String, Integer> requiredChunksWithLod = new HashMap<>();

    // 1. Gather required chunks for the current active grid face
    for (int cz = lastChunkZ - VIEW_DISTANCE; cz <= lastChunkZ + VIEW_DISTANCE; cz++) {
        for (int cx = lastChunkX - VIEW_DISTANCE; cx <= lastChunkX + VIEW_DISTANCE; cx++) {
            
            int deltaX = Math.abs(cx - lastChunkX);
            int deltaZ = Math.abs(cz - lastChunkZ);
            int chunkRingDistance = Math.max(deltaX, deltaZ);

            int currentSegments;
            if (chunkRingDistance > 4)       currentSegments = 6;   
            else if (chunkRingDistance > 2)  currentSegments = 16;  
            else                             currentSegments = 32;  

            String key = lastFace + "_" + cx + "_" + cz;
            requiredChunksWithLod.put(key, currentSegments);
        }
    }

    // 2. Retain chunks from the neighboring historical face if they exist in the cache
    if (previousFace != -1) {
        for (int cz = prevChunkZ - VIEW_DISTANCE; cz <= prevChunkZ + VIEW_DISTANCE; cz++) {
            for (int cx = prevChunkX - VIEW_DISTANCE; cx <= prevChunkX + VIEW_DISTANCE; cx++) {
                String key = previousFace + "_" + cx + "_" + cz;
                if (chunkCache.containsKey(key)) {
                    Model cachedModel = chunkCache.get(key);
                    int existingSegments = 6;
                    if (cachedModel.name.endsWith("seg32")) existingSegments = 32;
                    else if (cachedModel.name.endsWith("seg16")) existingSegments = 16;
                    
                    requiredChunksWithLod.put(key, existingSegments);
                }
            }
        }
    }

    // Clean up expired cache items cleanly
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

    List<String> staleLodKeys = new ArrayList<>();
    for (Map.Entry<String, Integer> target : requiredChunksWithLod.entrySet()) {
        String key = target.getKey();
        int targetSegments = target.getValue();
      
        String[] coords = key.split("_");
        int faceIdx = Integer.parseInt(coords[0]);
        int cx = Integer.parseInt(coords[1]);
        int cz = Integer.parseInt(coords[2]);

        boolean mustBuild = false;
        if (chunkCache.containsKey(key)) {
            Model cachedModel = chunkCache.get(key);
            if (!cachedModel.name.endsWith("seg" + targetSegments)) {
                if (cachedModel.mesh != null) cachedModel.mesh.dispose(gl);
                staleLodKeys.add(key); 
                mustBuild = true;
            }
        } else {
            mustBuild = true;
        }

        for (String staleKey : staleLodKeys) {
            chunkCache.remove(staleKey);
        }
        staleLodKeys.clear();

        if (mustBuild) {
            float dynamicScale = PHYSICAL_CHUNK_SIZE / (float) targetSegments;
            Mesh chunkMesh = TerrainMesh.generateSphericalChunk(gl, faceIdx, targetSegments, dynamicScale, cx, cz, seedOffsetX, seedOffsetY, seedOffsetZ, worldNoise);
            Model chunkModel = new Model("chunk_" + key + "_seg" + targetSegments, chunkMesh, globalModelMatrix, terrainShader, terrainMaterial, terrainRenderer, lights, camera);
            chunkCache.put(key, chunkModel);
        }
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

    Vec3 movedPos = camera.getPosition();
    Vec3 finalNorm = Vec3.normalize(movedPos);

    float rawHeight = TerrainMesh.getLayeredHeight3D(finalNorm, seedOffsetX, seedOffsetY, seedOffsetZ, worldNoise);
    float targetRadius = PlanetConfig.planetRadius + rawHeight + playerEyeHeight;

    if (this.smoothedCameraRadius < 0.0f) {
        this.smoothedCameraRadius = targetRadius;
    } else {
        float blendFactor = 6.0f * (float)deltaTime; 
        if (blendFactor > 1.0f) blendFactor = 1.0f;
        this.smoothedCameraRadius = (this.smoothedCameraRadius * (1.0f - blendFactor)) + (targetRadius * blendFactor);
    }

    Vec3 finalSnappedPos = Vec3.multiply(finalNorm, this.smoothedCameraRadius);
    camera.setRawPosition(finalSnappedPos);
    camera.setWorldUp(finalNorm);

    int currentFace = determineFaceFromVector(finalNorm);
    Vec3 activeFaceCoords = mapRadialToFacePlane(currentFace, finalNorm, PlanetConfig.planetRadius);

    int currentChunkX = (int) Math.floor((activeFaceCoords.x + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);
    int currentChunkZ = (int) Math.floor((activeFaceCoords.z + (PHYSICAL_CHUNK_SIZE / 2.0f)) / PHYSICAL_CHUNK_SIZE);

    if (currentChunkX != lastChunkX || currentChunkZ != lastChunkZ || currentFace != lastFace) {
        if (currentFace != lastFace) {
            previousFace = lastFace;
            prevChunkX = lastChunkX;
            prevChunkZ = lastChunkZ;
        }
        lastFace = currentFace;
        lastChunkX = currentChunkX;
        lastChunkZ = currentChunkZ;
        updateVisibleChunks(gl); 
    }

    lights[0].setPosition(getSunPosition());
    Vec3 sunPos = lights[0].getPosition();

    float sunAngle = (float)Math.atan2(sunPos.y, sunPos.x);
    float degSunAngle = (float)Math.toDegrees(sunAngle);
    float twighlightZoneSize = 30f;
    if (degSunAngle < 0f) { nightProportion = 1f; }
    else if (degSunAngle > 180f - twighlightZoneSize) { nightProportion = (degSunAngle - (180f - twighlightZoneSize)) / twighlightZoneSize; }
    else if (degSunAngle < twighlightZoneSize) { nightProportion = ((twighlightZoneSize - degSunAngle) / twighlightZoneSize); }
    else { nightProportion = 0f; }

    ambientLight = Vec3.multiply(new Vec3(0.4f, 0.38f, 0.35f), Math.max((float)Math.sin(sunAngle) * lights[0].getBrightnessProportion(), 0.05f));
    gl.glFrontFace(GL3.GL_CCW);
    Mat4 viewProjection = Mat4.multiply(camera.getPerspectiveMatrix(), camera.getViewMatrix());

    // --- PASS 1: FIXED DEPTH PRE-PASS ---
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, depthFBO[0]);
    gl.glClear(GL3.GL_DEPTH_BUFFER_BIT); 
    gl.glEnable(GL3.GL_DEPTH_TEST);
    gl.glEnable(GL3.GL_CULL_FACE);

    depthPrePassShader.use(gl);
    for (Map.Entry<String, Model> entry : chunkCache.entrySet()) { 
        Model plane = entry.getValue();
        Mat4 activeModelMatrix = plane.getModelMatrix(); 
        depthPrePassShader.setMat4(gl, "model", activeModelMatrix);
        Mat4 mvpMatrix = Mat4.multiply(viewProjection, activeModelMatrix);
        depthPrePassShader.setMat4(gl, "mvpMatrix", mvpMatrix);
        plane.mesh.render(gl); 
    }
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);

    // --- PASS 2: CANVAS SCENE ---
    gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

    // --- SKYBOX ---
    gl.glDisable(GL.GL_DEPTH_TEST); 
    gl.glDisable(GL.GL_CULL_FACE); 

    float skyScale = PlanetConfig.planetRadius * 3.5f;
    Mat4 skyTransform = Mat4.multiply(Mat4Transform.translate(camera.getPosition()), Mat4Transform.scale(skyScale, skyScale, skyScale));
    skyModel.setModelMatrix(skyTransform);
    skyModel.render(gl, ambientLight, nightProportion); 

    gl.glEnable(GL.GL_CULL_FACE);
    gl.glEnable(GL.GL_DEPTH_TEST);

    // --- SUN ---
    gl.glEnable(GL.GL_BLEND);
    gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
    gl.glDepthMask(false);
    lights[0].render(gl); 
    gl.glDepthMask(true);
    gl.glDisable(GL.GL_BLEND);

    // --- LANDSCAPE ---
    for (Map.Entry<String, Model> entry : chunkCache.entrySet()) { 
        Model plane = entry.getValue();
        terrainRenderer.render(gl, plane.mesh, plane.getModelMatrix(), terrainShader, terrainMaterial, lights, ambientLight, nightProportion, camera);
    }

    // --- OCEAN ---
    gl.glEnable(GL.GL_BLEND);
    gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

    float sphericalWaterRadius = PlanetConfig.planetRadius + seaLevelHeight;
    Mat4 waterMatrix = Mat4Transform.scale(sphericalWaterRadius, sphericalWaterRadius, sphericalWaterRadius);

    waterShader.use(gl);
    waterShader.setFloat(gl, "seaLevelHeight", seaLevelHeight);
    waterShader.setVec2(gl, "windowSize", new Vec2((float)currentWidth, (float)currentHeight));

    gl.glActiveTexture(GL3.GL_TEXTURE1);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, depthTexture[0]);
    waterShader.setInt(gl, "terrainDepthTexture", 1);

    waterSphereModel.setModelMatrix(waterMatrix);
    waterSphereModel.render(gl, ambientLight, nightProportion);
    gl.glDisable(GL.GL_BLEND);

    this.frameCount++;
  }

  private double getSeconds() { return System.currentTimeMillis() / 1000.0; }

  private int determineFaceFromVector(Vec3 v) {
    float absX = Math.abs(v.x); float absY = Math.abs(v.y); float absZ = Math.abs(v.z);
    if (absX >= absY && absX >= absZ) return (v.x > 0) ? 3 : 2; 
    else if (absY >= absX && absY >= absZ) return (v.y > 0) ? 0 : 1; 
    else return (v.z > 0) ? 4 : 5; 
  }

  private Vec3 mapRadialToFacePlane(int face, Vec3 v, float planetRadius) {
    Vec3 res = new Vec3(0,0,0);
    if (v.x == 0 && v.y == 0 && v.z == 0) return res;
    Vec3 n = Vec3.normalize(v);
    float eps = 0.00001f;

    switch (face) {
        case 0: res.x = (n.x / (Math.abs(n.y) < eps ? eps : n.y)) * planetRadius; res.z = (n.z / (Math.abs(n.y) < eps ? eps : n.y)) * planetRadius; break;
        case 1: res.x = (n.x / (Math.abs(-n.y) < eps ? -eps : -n.y)) * planetRadius; res.z = (-n.z / (Math.abs(-n.y) < eps ? -eps : -n.y)) * planetRadius; break;
        case 2: res.x = (n.z / (Math.abs(-n.x) < eps ? -eps : -n.x)) * planetRadius; res.z = (n.y / (Math.abs(-n.x) < eps ? -eps : -n.x)) * planetRadius; break;
        case 3: res.x = (-n.z / (Math.abs(n.x) < eps ? eps : n.x)) * planetRadius; res.z = (n.y / (Math.abs(n.x) < eps ? eps : n.x)) * planetRadius; break;
        case 4: res.x = (n.x / (Math.abs(n.z) < eps ? eps : n.z)) * planetRadius; res.z = (n.y / (Math.abs(n.z) < eps ? eps : n.z)) * planetRadius; break;
        case 5: res.x = (-n.x / (Math.abs(-n.z) < eps ? -eps : -n.z)) * planetRadius; res.z = (n.y / (Math.abs(-n.z) < eps ? -eps : -n.z)) * planetRadius; break;
    }
    return res;
  }

  public static float precalculateSeaLevel(long seed, float totalRegionWidth, PerlinNoise worldNoise) {
    java.util.Random rand = new java.util.Random(seed);
    float waterProportion = 0.55f; 
    int totalSamples = 1000;
    java.util.ArrayList<Float> heightSamples = new java.util.ArrayList<>(totalSamples);

    java.util.Random seedGenerator = new java.util.Random(worldNoise.hashCode());
    float sX = seedGenerator.nextFloat() * 50000.0f; float sY = seedGenerator.nextFloat() * 50000.0f; float sZ = seedGenerator.nextFloat() * 50000.0f;

    for (int i = 0; i < totalSamples; i++) {
        float theta = rand.nextFloat() * (float)(2.0 * Math.PI);
        float phi = (float)Math.acos(2.0 * rand.nextFloat() - 1.0);
        Vec3 dir = new Vec3((float)(Math.sin(phi)*Math.cos(theta)), (float)(Math.sin(phi)*Math.sin(theta)), (float)Math.cos(phi));
        float h = TerrainMesh.getLayeredHeight3D(dir, sX, sY, sZ, worldNoise);
        heightSamples.add(h);
    }
    java.util.Collections.sort(heightSamples);
    return heightSamples.get((int)(heightSamples.size() * waterProportion));
  }

  public void setMinimap(MapPanel minimap) { this.minimap = minimap; }

  private Model makeSkybox(GL3 gl, String fragmentPath, Texture skyTexture) {
    Mesh mesh = new Mesh(gl, InsideSphere.vertices.clone(), InsideSphere.indices.clone());
    float skyScale = PlanetConfig.planetRadius * 3.5f;
    Mat4 modelMatrix = Mat4Transform.scale(skyScale, skyScale, skyScale);
    Shader shader = new Shader(gl, "assets/shaders/vs_standard.txt", fragmentPath);
    Material material = new Material(new Vec3(0f, 0f, 0f), new Vec3(0f, 0f, 0f));
    material.setDiffuseMap(skyTexture);
    return new Model("skybox", mesh, modelMatrix, shader, material, new Renderer(), lights, camera);
  }

  public float getSeedOffsetX() { return this.seedOffsetX; }
  public float getSeedOffsetY() { return this.seedOffsetY; }
  public float getSeedOffsetZ() { return this.seedOffsetZ; }
}