import java.awt.*;
import java.awt.event.*;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.image.BufferedImage;
import java.util.Set;
import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLCanvas; 
import com.jogamp.opengl.util.FPSAnimator;

/**
 * The game's window and the flow round the 3D view: the main menu, starting a game (the landing
 * pod while the world is made on its own thread, see generateWorld), the controls, the map's
 * guess and results, the shop and the quarters, Xenocorp's messages, the settings, multiplayer's
 * waits for the partner, and the developer's test runs (runHudTest).
 */
public class XenoGuesser extends JFrame {

  // The developer's test runs (see DevTestRun)
  final DevTestRun devTestRun = new DevTestRun(this);

   
  // A fixed seed can be passed with -Dxenoguesser.seed=123 to test seed consistency; otherwise every game is a new world
  private static final Long FIXED_SEED = Dev.longValue("xenoguesser.seed");
  // Developer mode (teleporting by the map, and the like): only with the developer switches on (see Dev)
  private static final boolean IS_DEBUG_MODE_ACTIVE = Dev.ENABLED;

  private GLCanvas canvas; 
  XenoGuesser_GLEventListener glEventListener;
  private FPSAnimator animator;

  private boolean isWindowLocked = false;
  private Point lockedWindowPosition = null;
  private Point permanentWindowPosition = null;

  // Mouse look: while the game has the mouse its pointer is hidden and kept near the middle
  // of the view, and every movement turns the view. The large map, the results and the
  // settings give the pointer back.
  private static final float LOOK_SENSITIVITY = 0.0012f;
  private static final int RECENTRE_DISTANCE = 80;
  Robot robot;
  private Cursor hiddenCursor;
  private boolean mouseCaptured;
  private Point lastMouse;
  private Camera camera;
  MyKeyboardInput keyboardInput;
  private SettingsMenu settingsMenu;
  private boolean settingsOpen;
  volatile boolean inGame;
  private final Set<Integer> keysDown = java.util.concurrent.ConcurrentHashMap.newKeySet();

  // The world being played: a new one each game (the clock's time when it was chosen), unless -Dxenoguesser.seed fixes it
  private long worldSeed;
  private LoadingProgress loadingProgress = new LoadingProgress();
  private LoadingScreen loadingScreen;
  // The landing pod the player waits in as the world loads (see PodSequence): the 3D view's own
  // drawing from Play until the hatch is opened, and whether its first frame has been shown
  private PodSequence podSequence;
  private boolean podShown;
  // The planet's globe for the pod's window (see PlanetGlobeTexture), once it's made
  private BufferedImage planetGlobe;
  // JOGL's first profile probe takes seconds: made while the menu's up, so the pod can be shown at once
  private static final Thread GL_WARM_UP = new Thread(() -> GLProfile.get(GLProfile.GL3), "gl-profile-warm-up");
  private MainMenu mainMenu;
  JLayeredPane layeredPane;
  MapPanel minimap;
  GameHUD gameHUD;

  public static void main(String[] args) {
    // Nothing's written to the terminal but in development (M47): what the game logs as it
    // makes the world and plays (where the round is, what's near) would give the answer away
    if (!Dev.ENABLED) {
      java.io.PrintStream nowhere = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
      System.setOut(nowhere);
      System.setErr(nowhere);
    }
    // (what goes wrong is written down where it can be found and sent on: see CrashLog)
    CrashLog.install();
    RunFiles.prepare();
    // Keep the loading screen smooth: GL work runs on the animator's own thread rather than
    // the UI thread that draws the screen, and the background generators leave two cores free
    // (the parallel streams' shared pool among them, its threads below the usual priority:
    // set before it's first used), so on a PC with few cores the landing pod's frames still come
    com.jogamp.opengl.Threading.disableSingleThreading();
    int cores = Runtime.getRuntime().availableProcessors();
    System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", Integer.toString(Math.max(1, cores - 2)));
    System.setProperty("java.util.concurrent.ForkJoinPool.common.threadFactory", BackgroundWorkers.class.getName());
    // The heap held to what this PC can spare (see MemoryBudget)
    MemoryBudget.apply();
    // (and what a crash in native code left behind last time, which nothing here could write)
    Thread natives = new Thread(CrashLog::collectNativeCrashes, "native-crash-logs");
    natives.setDaemon(true);
    natives.start();
    int spare = Math.max(1, cores / 2);
    System.setProperty("ai.djl.pytorch.num_threads", Integer.toString(spare));
    System.setProperty("ai.djl.pytorch.num_interop_threads", "1");
    SwingUtilities.invokeLater(new Runnable() {
        @Override
        public void run() {
            XenoGuesser b1 = new XenoGuesser("XenoGuesser");
            b1.setVisible(true);
        }
    });
  }

  public XenoGuesser(String textForTitleBar) {
    super(textForTitleBar);

    // 1. Establish the World Seed Early for Deterministic Generation
    worldSeed = FIXED_SEED != null ? FIXED_SEED : System.currentTimeMillis();

    // 2. The window: no system frame, so it can go between fullscreen and windowed (with a title
    // bar of its own) without being rebuilt, which would lose the 3D view
    this.setUndecorated(true);
    this.setResizable(false);
    this.getContentPane().setBackground(Color.BLACK);
    this.setBackground(Color.BLACK);

    layeredPane = new JLayeredPane();
    layeredPane.setOpaque(false);
    // (the layers are the window's content itself: the 3D view, drawn by the system, only stays
    // under the loading screen and menus laid over it when they share this pane)
    this.setContentPane(layeredPane);
    // The title bar (windowed only) and the settings, over everything else, from the start
    titleBar = new WindowTitleBar(this, this::shutdownGame);
    layeredPane.add(titleBar, Integer.valueOf(TOP_LAYER));
    // Grab edges for resizing the window (windowed only), keeping the screen's shape
    Rectangle screenShape = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    screenAspect = screenShape.width / (float) screenShape.height;
    int[][] edges = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
    resizers = new WindowResizer[edges.length];
    for (int k = 0; k < edges.length; k++) {
      resizers[k] = new WindowResizer(this, edges[k][0], edges[k][1], screenAspect, WindowTitleBar.HEIGHT);
      layeredPane.add(resizers[k], Integer.valueOf(TOP_LAYER + 1));
    }
    settingsMenu = new SettingsMenu(() -> setSettingsOpen(false), this::shutdownGame, this::applyDisplayMode);
    // (the controls over the settings, which come back once they're closed)
    settingsMenu.setOnControls(() -> {
      settingsMenu.setVisible(false);
      showControls(() -> {
        if (settingsOpen) settingsMenu.setVisible(true);
      });
    });
    layeredPane.add(settingsMenu, Integer.valueOf(TOP_LAYER));
    // The pop-up as an achievement's unlocked (M53), at the bottom right over the menus and
    // panels; in the world the 3D view draws its own (see XenoGuesser_GLEventListener.drawHud)
    javax.swing.JComponent toast = new javax.swing.JComponent() {
      @Override
      protected void paintComponent(java.awt.Graphics g0) {
        Object[] now = Achievements.toast();
        if (now == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        HudStyle.smooth(g);
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, (Float) now[1]));
        Achievements.paintToast(g, (Achievements.Achievement) now[0]);
        g.dispose();
      }
    };
    layeredPane.add(toast, Integer.valueOf(TOP_LAYER + 1));
    new javax.swing.Timer(40, e -> {
      Object[] now = Achievements.toast();
      boolean worldShown = inGame && canvas != null && canvas.getX() < layeredPane.getWidth();
      boolean show = now != null && !worldShown;
      int m = HudStyle.HUD_MARGIN;
      toast.setBounds(layeredPane.getWidth() - Achievements.TOAST_W - m, layeredPane.getHeight() - Achievements.TOAST_H - m, Achievements.TOAST_W, Achievements.TOAST_H);
      if (show != toast.isVisible()) toast.setVisible(show);
      if (show) toast.repaint();
    }).start();
    applyDisplayMode();

    System.setProperty("sun.awt.noerasebackground", "true"); 
    System.setProperty("sun.java2d.noddraw", "true");

    // 3. The main menu first; the loading screen then covers everything until the round's first frame
    mainMenu = new MainMenu(this::startSingleplayer, this::startMultiplayer, this::shutdownGame);
    // The planet's name is said (for the message from Xenocorp) while the menu is up
    TransmissionMessage.prepare(worldSeed);
    layeredPane.add(mainMenu, JLayeredPane.DRAG_LAYER);
    GL_WARM_UP.setDaemon(true);
    GL_WARM_UP.start();

    this.addComponentListener(new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) {
            // (held where it is while the map's reveal plays; otherwise it stays wherever it's put)
            if (isWindowLocked && lockedWindowPosition != null && !getLocation().equals(lockedWindowPosition)) {
                setLocation(lockedWindowPosition);
            }
            keepFullscreen();
        }

        @Override
        public void componentResized(ComponentEvent e) {
            keepFullscreen();
        }

        @Override
        public void componentShown(ComponentEvent e) {
            keepFullscreen();
        }
    });
    // (once it's on screen, fullscreen again: Windows may have sized it otherwise as it opened)
    addWindowListener(new WindowAdapter() {
      @Override
      public void windowOpened(WindowEvent e) {
        applyDisplayMode();
      }
    });

    layeredPane.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
          layoutComponents();
          if (permanentWindowPosition == null) {
              permanentWindowPosition = getLocation();
          }
      }
    });
    
    addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        shutdownGame();
      }
    });

    // Esc opens and closes the settings wherever the focus is (see escapePressed)
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::escapePressed);

    // Developer aid: -Dxenoguesser.skipmenu (or taking the menu's picture) goes straight to loading
    if (Dev.property("xenoguesser.skipmenu") != null || Dev.property("xenoguesser.menushot") != null) {
      startSingleplayer();
    }
    // Developer aid: -Dxenoguesser.mpauto=host (or =join) opens the multiplayer panel and presses
    // Host game (or Join game with any code: for -Dxenoguesser.mpfake, which doesn't check it)
    String mpAuto = Dev.property("xenoguesser.mpauto");
    if (mpAuto != null) {
      startMultiplayer();
      multiplayerPanel.autoStart(!mpAuto.equalsIgnoreCase("join"));
    }
  }

  // Play Multiplayer's panel, over the main menu while it's up
  private MultiplayerPanel multiplayerPanel;

  /**
   * Play Multiplayer: the multiplayer panel over the (dimmed) menu, to host a game or join
   * one by its room code. Steam is started now, in the background (M2), never before. Once
   * both players are in, both load the host's world (multiplayerReady).
   */
  private void startMultiplayer() {
    if (loadingScreen != null || multiplayerPanel != null) return;
    Multiplayer.startTransport();
    multiplayerPanel = new MultiplayerPanel(() -> worldSeed, this::closeMultiplayerPanel, this::multiplayerReady);
    layeredPane.add(multiplayerPanel, Integer.valueOf(JLayeredPane.DRAG_LAYER + 1));
    layoutComponents();
    layeredPane.repaint();
    SwingUtilities.invokeLater(() -> {
      if (multiplayerPanel != null) multiplayerPanel.takeFocus();
    });
  }

  /** The panel's Back: the menu as it was. */
  private void closeMultiplayerPanel() {
    removeMultiplayerPanel();
    if (mainMenu != null) mainMenu.unchoose();
    requestFocus();
  }

  private void removeMultiplayerPanel() {
    if (multiplayerPanel == null) return;
    layeredPane.remove(multiplayerPanel);
    multiplayerPanel = null;
    layeredPane.repaint();
  }

  /**
   * Both players are in the room: the world is the host's (M5). The joiner takes the host's
   * seed, game mode and map kind over its own menu choices, and the planet's name is said
   * afresh for it; then both load the world as singleplayer does.
   */
  private void multiplayerReady(Multiplayer session) {
    removeMultiplayerPanel();
    worldSeed = session.worldSeed();
    // (this player's own menu choices, given back after the game: the joiner plays the host's)
    menuGameMode = GameMode.current();
    menuMapKind = MapKind.current();
    gameOver = false;
    session.addListener(new Multiplayer.Listener() {
      @Override
      public void disconnected(String reason) {
        partnerGone(session, reason);
      }

      @Override
      public void failed(String reason) {
        partnerGone(session, reason);
      }
    });
    if (session.role == Multiplayer.Role.JOIN) {
      GameMode.choose(session.gameMode());
      MapKind.choose(session.mapKind());
    }
    TransmissionMessage.prepare(worldSeed);
    System.out.println("[MP] Loading the world as Employee " + session.localLabel() + ": seed " + worldSeed
        + ", " + GameMode.current() + ", " + MapKind.current());
    startGame(true);
  }

  /** Play Singleplayer: the loading screen replaces the menu while a world is generated. */
  void startSingleplayer() {
    startGame(false);
  }

  /** The loading screen replaces the menu while this.worldSeed's world is generated (singleplayer or multiplayer). */
  private boolean loadingEnterInstalled;

  private void startGame(boolean multiplayer) {
    if (loadingScreen != null) return;
    planetGlobe = null;
    // (the memory free now, for the world about to be made: other programs may have closed since startup)
    MemoryBudget.apply();
    loadingScreen = new LoadingScreen(worldSeed, multiplayer);
    loadingScreen.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
    layeredPane.add(loadingScreen, JLayeredPane.DRAG_LAYER);
    layeredPane.remove(mainMenu);
    layeredPane.repaint();
    requestFocus();
    // The pod's shown as soon as the 3D view can be opened (the menu's had time to warm it up)
    int opening = generationRun + 1;
    Thread open = new Thread(() -> {
      try {
        GL_WARM_UP.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      SwingUtilities.invokeLater(() -> {
        if (opening == generationRun && canvas == null && loadingScreen != null) openPod(multiplayer);
      });
    }, "open-pod");
    open.setDaemon(true);
    open.start();
    // Enter skips the loading screen's message (from the start: the game's own keys come later)
    if (!loadingEnterInstalled) {
      loadingEnterInstalled = true;
      KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
        if (inGame || e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_ENTER) return false;
        return loadingScreen != null && loadingScreen.skipMessage();
      });
    }

    // Everything that doesn't need the GL context is generated off the UI thread (after any
    // world given up halfway has finished being made, as they share the run's files)
    int run = ++generationRun;
    Thread previous = worldGeneration;
    worldGeneration = new Thread(() -> {
      try {
        if (previous != null) previous.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      generateWorld(run);
    }, "world-generation");
    worldGeneration.setDaemon(true);
    worldGeneration.setPriority(Thread.NORM_PRIORITY - 2);
    worldGeneration.start();
  }

  // The world being made (its thread), and which run it is: a world given up on before it's
  // ready (multiplayer: the partner left) is still made to the end, but never shown
  private Thread worldGeneration;
  private volatile int generationRun;

  private void generateWorld(int run) {
    // JOGL's first profile probe takes seconds; doing it here, alongside the writing systems,
    // keeps it off the UI thread where it would freeze the loading screen
    // In multiplayer the joiner's writing systems come from the host (M18): it never makes its
    // own, and only waits for them (if it must) before the nations are laid out
    Multiplayer session = Multiplayer.active();
    boolean receiving = session != null && session.role == Multiplayer.Role.JOIN;
    try {
      if (!receiving) {
        // (the host's were begun when Host game was pressed: this waits for that same job)
        loadingProgress.begin(LoadingProgress.Stage.WRITING_SYSTEMS);
        WritingSystems.generate(worldSeed).join();
      }

      loadingProgress.begin(LoadingProgress.Stage.SEA_LEVEL);
      PerlinNoise worldNoise = new PerlinNoise(worldSeed);
      float physicalChunkSize = 100.0f;
      // Each planet a little bigger or smaller than the last (M47): up to 8% either way, from
      // its seed (so both players' PCs make it the same)
      float totalRegionWidth = 150_000f * (0.92f + 0.16f * new java.util.Random(worldSeed * 104_729L + 17L).nextFloat());
      Planet.setWidth(totalRegionWidth);
      System.out.printf("[PLANET] %.0f units round%n", totalRegionWidth);
      float seaLevelHeight = XenoGuesser_GLEventListener.precalculateSeaLevel(worldSeed, worldNoise);
      // Where the map's left and right edges join: the meridian crossing the least land
      float joinLand = MapProjection.chooseJoin(d -> TerrainMesh.getLayeredHeight(d, worldNoise), seaLevelHeight);
      System.out.printf("[PLANET] The map's join crosses %.1f%% land%n", joinLand * 100f);

      // The rivers, from the bare land: everything after sees the ground with their channels cut
      loadingProgress.begin(LoadingProgress.Stage.RIVERS);
      long riversStarted = System.currentTimeMillis();
      // (the climate first: streams rise more readily, and run fuller, where it's wet)
      RegionalGenerationManager climate = new RegionalGenerationManager(worldSeed, seaLevelHeight);
      climate.precalculateWaterDistanceField(worldNoise);
      climate.setMoistureMap(climate.createWaterPreference(0f, 8000f));
      worldNoise.rivers = Rivers.generate(worldNoise, seaLevelHeight, climate.rainfallMap::evaluate);
      // (and the moisture they bring to the land round them)
      climate.addRivers(worldNoise.rivers);
      worldNoise.climate = climate;
      // The planet as the pod sees it coming in, made alongside the rest from here (the rivers cut its coasts)
      long globeSeed = worldSeed;
      Thread globe = new Thread(() -> {
        BufferedImage image = PlanetGlobeTexture.make(worldNoise, seaLevelHeight, globeSeed);
        SwingUtilities.invokeLater(() -> {
          if (run != generationRun) return;
          planetGlobe = image;
          if (podSequence != null) podSequence.setGlobe(image);
        });
      }, "planet-globe");
      globe.setDaemon(true);
      globe.setPriority(Thread.MIN_PRIORITY);
      globe.start();
      System.out.printf("[RIVERS] Worked out in %d ms%n", System.currentTimeMillis() - riversStarted);
      
      // (put in place by the first spawn)
      Camera camera = new Camera(Planet.position(0.0, 0.0, seaLevelHeight + 20.0), seaLevelHeight);
      MyKeyboardInput keyboardInput = new MyKeyboardInput(); 

      if (receiving) {
        loadingProgress.begin(LoadingProgress.Stage.WRITING_SYSTEMS, "Receiving writing systems from " + session.remoteLabel());
        WritingSystems.receive(session);
      }

      loadingProgress.begin(LoadingProgress.Stage.WORLD_LAYOUT);
      XenoGuesser_GLEventListener listener = new XenoGuesser_GLEventListener(
        camera,
        keyboardInput,
        worldNoise,
        seaLevelHeight,
        worldSeed,
        totalRegionWidth,
        physicalChunkSize,
        IS_DEBUG_MODE_ACTIVE
      );

      loadingProgress.begin(LoadingProgress.Stage.WORLD_ART);
      WorldArtGenerator art = WorldArtGenerator.generate(worldSeed, listener.getSpeciesCount(), listener.getNationTextureJobs(), loadingProgress);
            listener.setWorldArt(art);
      listener.buildSoilRegions(art.palette());

      loadingProgress.begin(LoadingProgress.Stage.WINDOW);
      GL_WARM_UP.join();
      if (run != generationRun) return;
      // The map (its land and sea worked out point by point) made here, not on the UI thread,
      // which the landing pod's frames wait on
      MapPanel map = new MapPanel(750, 750, totalRegionWidth, seaLevelHeight, worldNoise, physicalChunkSize, listener);
      SwingUtilities.invokeLater(() -> {
        if (run == generationRun) buildGameWindow(listener, camera, keyboardInput, worldNoise,
          seaLevelHeight, totalRegionWidth, physicalChunkSize, map);
      });
    } catch (Throwable e) {
      // (running out of memory too: the loading screen says so, rather than waiting for ever)
      System.err.println("CRITICAL ERROR: World generation failed.");
      CrashLog.write("World generation failed", e);
      loadingProgress.fail(e instanceof OutOfMemoryError ? "Out of memory: close other programs and try again"
          : e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      e.printStackTrace();
    }
  }

  private void buildGameWindow(XenoGuesser_GLEventListener listener, Camera camera, MyKeyboardInput keyboardInput,
                               PerlinNoise worldNoise, float seaLevelHeight, float totalRegionWidth,
                               float physicalChunkSize, MapPanel map) {
    glEventListener = listener;
    // (the 3D view is the pod's already, unless it's not been opened yet)
    if (canvas == null) openPod(Multiplayer.isActive());

    gameHUD = new GameHUD();
    // (nothing of the game's shown over the pod)
    gameHUD.setVisible(false);
    // Multiplayer: quarter points instead of targets (M13), on the score panel too
    Multiplayer session = Multiplayer.active();
    multiplayerScore = session != null ? MultiplayerScore.of(session) : null;
    if (session != null) gameHUD.setMultiplayer(session, multiplayerScore);
glEventListener.setGameHUD(gameHUD);
    this.camera = camera;
    this.keyboardInput = keyboardInput;

    minimap = map;

    minimap.setMainApp(this);
    minimap.setGameHUD(gameHUD);
    glEventListener.setMinimap(minimap);
    glEventListener.setLoading(loadingProgress, this::onWorldReady);
    minimap.setOnSizeChanged(this::onMapChanged);
    shopPanel = new ShopPanel(gameHUD);
    quarterPanel = new QuarterPanel();
    minimap.setOnProceedToShop(this::afterResults);
    minimap.setProceedLabel(() -> gameHUD.isQuarterEnd() ? "End of Quarter" : "Proceed to Shop");

    // The mouse wheel (and a touchpad's pinch, which comes as one) zooms the view where the game mode allows it
    canvas.addMouseWheelListener(e -> {
      if (inGame && mouseCaptured) glEventListener.zoomBy(e.getPreciseWheelRotation());
    });
    installControls();

    layeredPane.add(minimap, JLayeredPane.PALETTE_LAYER);
    // (the shop over the results, under the score)
    layeredPane.add(shopPanel, Integer.valueOf(JLayeredPane.PALETTE_LAYER + 50));
    layeredPane.add(quarterPanel, Integer.valueOf(JLayeredPane.PALETTE_LAYER + 60));
    layeredPane.add(gameHUD, JLayeredPane.MODAL_LAYER);
    layoutComponents();
    // The world's loading goes on under the pod, its drawing taken over once the hatch is opened
    podSequence.attachGame(listener);
  }

  /**
   * Opens the 3D view with the landing pod in it (see PodSequence), for the player to wait in
   * while the world loads; the game's own drawing is handed to it later (buildGameWindow).
   */
  private void openPod(boolean multiplayer) {
    GLCapabilities glcapabilities = new GLCapabilities(GLProfile.get(GLProfile.GL3));
    // JOGL asks for a 16-bit depth buffer by default, too coarse for close, thin parts
    glcapabilities.setDepthBits(24);
    canvas = new GLCanvas(glcapabilities);
    canvas.setBackground(Color.BLACK);
    podShown = false;
    podSequence = new PodSequence(new PodSequence.Host() {
      @Override
      public boolean worldReady() {
        LoadingScreen screen = loadingScreen;
        return screen != null && screen.worldReady();
      }

      @Override
      public void openHatch() {
        if (loadingScreen != null) loadingScreen.openHatch();
      }

      @Override
      public float targetsAlpha() {
        LoadingScreen screen = loadingScreen;
        return screen == null ? 0f : screen.targetsAlpha();
      }

      @Override
      public void showing() {
        if (loadingScreen == null || canvas == null) return;
        podShown = true;
        loadingScreen.podShowing();
        layoutComponents();
        canvas.requestFocus();
        updateMouseMode();
      }
    }, multiplayer, loadingProgress, worldSeed);
    if (planetGlobe != null) podSequence.setGlobe(planetGlobe);
    canvas.addGLEventListener(podSequence);
    layeredPane.add(canvas, JLayeredPane.DEFAULT_LAYER);
    installMouseLook();
    installPodControls();
    layoutComponents();
    animator = new FPSAnimator(canvas, 60);
    // The GL context kept current on the animator's thread, rather than taken and given back each
    // frame. Discord's capture hook (sharing the game's window) seemed to take each as something
    // new to capture, building it a copy each time, until the PC ran out of memory
    animator.setExclusiveContext(true);
    animator.start();
    // (a freeze, which throws nothing, written down all the same)
    FreezeWatch.start(() -> {
      FPSAnimator running = animator;
      return running != null && running.isStarted() && !running.isPaused() && (getExtendedState() & ICONIFIED) == 0;
    });
  }

  // Whether the pod's keys (and the mouse look, for the pod and the game) are listened for yet
  private boolean podControlsInstalled, mouseLookInstalled;

  /** In the pod: WASD walk, Space jumps, E uses what's faced, R says ready (multiplayer); Esc opens the settings (see escapePressed). */
  private void installPodControls() {
    if (podControlsInstalled) return;
    podControlsInstalled = true;
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
      PodSequence pod = podSequence;
      if (inGame || pod == null || pod.handedOver() || !podShown) return false;
      if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent) return false;
      int code = e.getKeyCode();
      // (Enter skips the message: see startGame)
      if (code == KeyEvent.VK_ENTER || e.getID() == KeyEvent.KEY_TYPED) return false;
      if (e.getID() == KeyEvent.KEY_PRESSED) {
        boolean firstPress = keysDown.add(code);
        if (!settingsOpen && firstPress && code == KeyEvent.VK_SPACE) pod.jump();
        if (!settingsOpen && firstPress && code == KeyEvent.VK_E) pod.use();
        if (!settingsOpen && firstPress && code == KeyEvent.VK_R) pod.ready();
      } else if (e.getID() == KeyEvent.KEY_RELEASED) {
        keysDown.remove(code);
      }
      pod.setKeys(!settingsOpen && keysDown.contains(KeyEvent.VK_W), !settingsOpen && keysDown.contains(KeyEvent.VK_A),
          !settingsOpen && keysDown.contains(KeyEvent.VK_S), !settingsOpen && keysDown.contains(KeyEvent.VK_D));
      return true;
    });
  }

  /** Mouse look, while the game or the pod has the mouse: its pointer hidden and kept near the middle of the view. */
  private void installMouseLook() {
    if (mouseLookInstalled) return;
    mouseLookInstalled = true;
    try {
      robot = new Robot();
    } catch (AWTException e) {
      System.err.println("Mouse look unavailable: " + e.getMessage());
    }
    // (a run taking pictures of the pod leaves the mouse alone)
    if (Dev.property("xenoguesser.podshot") != null) robot = null;
    hiddenCursor = Toolkit.getDefaultToolkit().createCustomCursor(
        new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "hidden");
    Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
      if (!mouseCaptured || canvas == null) return;
      MouseEvent e = (MouseEvent) event;
      if (e.getID() != MouseEvent.MOUSE_MOVED && e.getID() != MouseEvent.MOUSE_DRAGGED) return;
      Point p = e.getLocationOnScreen();
      if (lastMouse != null) {
        int dx = p.x - lastMouse.x, dy = p.y - lastMouse.y;
        if (dx != 0 || dy != 0) {
          PodSequence pod = podSequence;
          if (!inGame && pod != null && !pod.handedOver()) pod.look(dx * LOOK_SENSITIVITY, -dy * LOOK_SENSITIVITY);
          else if (glEventListener != null) glEventListener.look(dx * LOOK_SENSITIVITY, -dy * LOOK_SENSITIVITY);
        }
      }
      lastMouse = p;
      Point centre = viewCentreOnScreen();
      if (robot != null && (Math.abs(p.x - centre.x) > RECENTRE_DISTANCE || Math.abs(p.y - centre.y) > RECENTRE_DISTANCE)) {
        // The jump back is not a movement: the next position read starts afresh
        robot.mouseMove(centre.x, centre.y);
        lastMouse = null;
      }
    }, AWTEvent.MOUSE_MOTION_EVENT_MASK);
    addWindowFocusListener(new WindowAdapter() {
      @Override
      public void windowGainedFocus(WindowEvent e) {
        updateMouseMode();
      }

      @Override
      public void windowLostFocus(WindowEvent e) {
        keysDown.clear();
        escapeDown = false;
        if (keyboardInput != null) keyboardInput.releaseAll();
        if (podSequence != null) podSequence.setKeys(false, false, false, false);
        updateMouseMode();
      }
    });
  }

  /** Called on the UI thread once the first frame of the round has been drawn; the player then chooses when to start. */
  private void onWorldReady() {
    // (the heap held from now to what the game holds, not all the PC could spare for making it)
    MemoryBudget.worldMade();
    // Multiplayer: the partner hears this PC is loaded (their Begin waits for it, M16)
    Multiplayer session = Multiplayer.active();
    if (session != null) session.sendWorldReady();
    // A HUD test run goes straight in (in multiplayer it waits at Begin like anyone, so both go in together)
    if (Dev.property("xenoguesser.hudtest") != null && session == null) enterGame();
    // (the pod's hatch swings open, and out into the light, before the game's entered)
    else loadingScreen.setWorldReady(() -> podSequence.openHatchThen(this::enterGame));
  }

  private void enterGame() {
    loadingScreen.stop();
    // (the 3D view's the game's from now on)
    podSequence.handOver();
    keysDown.clear();
    // (the first round begins now: in multiplayer, on both PCs together)
    glEventListener.startRoundClock();
    // A shop before January (a HUD test run skips it), opened under the cockpit before it goes,
    // so the round isn't glimpsed in between; otherwise the world's straight in view
    String test = Dev.property("xenoguesser.hudtest");
    if (test == null || test.contains("firstshop")) openFirstShop();
    else glEventListener.setWorldHidden(false);
    layeredPane.remove(loadingScreen);
    layoutComponents();
    layeredPane.repaint();
    canvas.requestFocus();
    inGame = true;
    // (multiplayer: from now on, silence from the partner counts as them gone)
    Multiplayer playing = Multiplayer.active();
    if (playing != null) playing.setPlaying(true);
    devTestRun.runHudTest();
    updateMouseMode();
  }

  // Whether Esc is held (so holding it, its repeats, open and close nothing more), and when it last said so
  private boolean escapeDown;
  private long escapeAt;
  // Esc's repeats come many times a second while it's held: a gap longer than this is a new press
  // (should its letting go have gone unheard), so the settings can never stop answering Esc
  private static final long ESCAPE_REPEAT_GAP_MS = 1100L;

  /**
   * Esc, wherever the focus is and at any point (the menu, the loading screen, the pod, the game):
   * closes the controls if they're up, otherwise opens or closes the settings. Not while a note's
   * being typed (Esc is the note's), nor over the multiplayer panel (Esc is its Back).
   */
  private boolean escapePressed(KeyEvent e) {
    if (e.getKeyCode() != KeyEvent.VK_ESCAPE) return false;
    if (e.getID() == KeyEvent.KEY_RELEASED) {
      escapeDown = false;
      keysDown.remove(KeyEvent.VK_ESCAPE);
      return false;
    }
    if (e.getID() != KeyEvent.KEY_PRESSED) return false;
    boolean firstPress = !escapeDown || e.getWhen() - escapeAt > ESCAPE_REPEAT_GAP_MS;
    escapeDown = true;
    escapeAt = e.getWhen();
    if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent) return false;
    if (multiplayerPanel != null && !settingsOpen) return false;
    if (firstPress) {
      if (controlsPanel != null && controlsPanel.isOpen()) controlsPanel.close();
      else setSettingsOpen(!settingsOpen);
    }
    return true;
  }

  /**
   * Keys work wherever the focus is (except while typing a note): WASD walk, Space jumps, M enlarges
   * or shrinks the map, C uses the compass and Esc opens the settings. Mouse
   * movement anywhere in the window turns the view while the game has the mouse.
   */
  private void installControls() {
    installMouseLook();
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
      if (!inGame) return false;
      if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent) return false;
      int code = e.getKeyCode();
      if (e.getID() == KeyEvent.KEY_PRESSED) {
        boolean firstPress = keysDown.add(code);   // ignore key repeat
        if (code == KeyEvent.VK_ENTER && settingsOpen && controlsPanel != null && controlsPanel.isOpen()) {
          if (firstPress) controlsPanel.close();
          return true;
        }
        if (settingsOpen) return true;
        // Enter: closes the controls, or skips the message playing (in the shop or over the game)
        if (code == KeyEvent.VK_ENTER) {
          if (firstPress) {
            pressEnter();
          }
          return true;
        }
        if (controlsPanel != null && controlsPanel.isOpen()) return true;
        // (the shop and the quarter's result take the mouse only)
        if ((shopPanel != null && shopPanel.isOpen()) || (quarterPanel != null && quarterPanel.isOpen())) return true;
        if (firstPress) {
          switch (code) {
            case KeyEvent.VK_M: minimap.toggleSize(); break;
            // X: the controls on offer skipped
            case KeyEvent.VK_X: if (controlsOffered) withdrawControls(); break;
            case KeyEvent.VK_SPACE: glEventListener.jump(); break;
            // The inventory: each item by its number
            case KeyEvent.VK_1: case KeyEvent.VK_NUMPAD1:
              if (!minimap.isFullScreenRevealMode()) glEventListener.useItem(0);
              break;
            case KeyEvent.VK_2: case KeyEvent.VK_NUMPAD2:
              if (!minimap.isFullScreenRevealMode()) glEventListener.useItem(1);
              break;
            case KeyEvent.VK_3: case KeyEvent.VK_NUMPAD3:
              if (!minimap.isFullScreenRevealMode()) glEventListener.useItem(2);
              break;
            case KeyEvent.VK_4: case KeyEvent.VK_NUMPAD4:
              if (!minimap.isFullScreenRevealMode()) glEventListener.useItem(3);
              break;
            default: break;
          }
        }
        keyboardInput.keyPressed(e);
      } else if (e.getID() == KeyEvent.KEY_RELEASED) {
        keysDown.remove(code);
        keyboardInput.keyReleased(e);
      }
      return false;
    });

  }

  /** Whether a key is held down just now (while playing). */
  /** Whether the player is out in a round, free to guess (no shop, verdict or loading screen in the way); the round's clock runs only then. */
  public boolean roundInPlay() {
    return inGame && glEventListener != null && glEventListener.roundInPlay() && !shopPanel.isOpen()
        && (quarterPanel == null || !quarterPanel.isOpen());
  }

  /** Whether the settings are open over the game (singleplayer's round clock stops while they are). */
  public boolean isSettingsOpen() {
    return settingsOpen;
  }

  public boolean isKeyHeld(int keyCode) {
    return keysDown.contains(keyCode);
  }

  private Point viewCentreOnScreen() {
    Point origin = canvas.getLocationOnScreen();
    return new Point(origin.x + canvas.getWidth() / 2, origin.y + canvas.getHeight() / 2);
  }

  /** Takes or gives back the mouse to suit what is on screen. */
  private void updateMouseMode() {
    if (canvas == null || !canvas.isShowing()) return;
    // (in the pod, only the settings need the pointer)
    boolean inPod = !inGame && podSequence != null && !podSequence.handedOver() && podShown;
    boolean pointerNeeded = settingsOpen || (!inPod && (minimap == null || minimap.isLargeMap() || minimap.isFullScreenRevealMode()
        || shopPanel.isOpen() || (quarterPanel != null && quarterPanel.isOpen()) || (controlsPanel != null && controlsPanel.isOpen())));
    boolean capture = (inGame || inPod) && !pointerNeeded && isFocused() && robot != null;
    if (capture == mouseCaptured) return;
    mouseCaptured = capture;
    lastMouse = null;
    Cursor cursor = capture ? hiddenCursor : Cursor.getDefaultCursor();
    setCursor(cursor);
    canvas.setCursor(cursor);
    if (robot == null || !isFocused()) return;
    if (capture) {
      Point centre = viewCentreOnScreen();
      robot.mouseMove(centre.x, centre.y);
    } else if (minimap != null && minimap.isLargeMap() && !settingsOpen) {
      // The pointer appears over the middle of the map, ready to place a marker
      Point origin = minimap.getLocationOnScreen();
      robot.mouseMove(origin.x + minimap.getWidth() / 2, origin.y + minimap.getHeight() / 2);
    }
  }

  // The shop, between a round's results and the next round
  ShopPanel shopPanel;

  // A message from Xenocorp played during a round, its words along the bottom over everything
  private MessageStrip messageStrip;

  /** Plays a message from Xenocorp over the game, its words scrolling along the bottom (above the map) until it's said. */
  void showMessage(TransmissionMessage message) {
    showMessage(message, null);
  }

  /** As showMessage; onEnd told when it ends (true if skipped). */
  void showMessage(TransmissionMessage message, java.util.function.Consumer<Boolean> onEnd) {
    SwingUtilities.invokeLater(() -> {
      if (!inGame || message == null) {
        if (onEnd != null) onEnd.accept(true);
        return;
      }
      if (messageStrip == null) {
        messageStrip = new MessageStrip();
        layeredPane.add(messageStrip, Integer.valueOf(JLayeredPane.MODAL_LAYER + 10));
      }
      messageStrip.play(message, onEnd);
      layoutComponents();
    });
  }

  // The controls, shown after January's message (singleplayer), and whether the round's clock is
  // held for the message and them (the first round's: the instructions aren't on the clock)
  private ControlsPanel controlsPanel;
  private volatile boolean clockHeldForInstructions;

  /** Enter (in the game): closes the controls, skips the message playing (in the shop or over the game), or opens the controls on offer. */
  void pressEnter() {
    if (controlsPanel != null && controlsPanel.isOpen()) controlsPanel.close();
    else if (shopPanel != null && shopPanel.isOpen() && shopPanel.messagePlaying()) shopPanel.skipMessage();
    else if (messageStrip != null && messageStrip.isPlaying()) messageStrip.skip();
    else if (controlsOffered) {
      withdrawControls();
      // (the round's clock waits while they cover the screen, as the first round's instructions aren't on it)
      clockHeldForInstructions = true;
      showControls(() -> clockHeldForInstructions = false);
    }
  }

  // After January's message the controls are offered (Enter opens them, X skips), not shown
  // straight over the game; the offer goes by itself after CONTROLS_OFFER_MS
  private static final int CONTROLS_OFFER_MS = 30_000;
  private boolean controlsOffered;
  private javax.swing.Timer controlsOfferTimer;

  /** Offers the controls: a hint over the game until Enter opens them, X skips them, or they're left. */
  private void offerControls() {
    if (!inGame || glEventListener == null) return;
    controlsOffered = true;
    glEventListener.setControlsOffered(true);
    controlsOfferTimer = new javax.swing.Timer(CONTROLS_OFFER_MS, e -> withdrawControls());
    controlsOfferTimer.setRepeats(false);
    controlsOfferTimer.start();
  }

  /** The controls are no longer on offer (opened, skipped or left too long). */
  private void withdrawControls() {
    controlsOffered = false;
    if (controlsOfferTimer != null) controlsOfferTimer.stop();
    controlsOfferTimer = null;
    if (glEventListener != null) glEventListener.setControlsOffered(false);
  }

  /** Whether the round's clock is held: the settings open, or January's message and the controls up. */
  public boolean clockHeld() {
    return settingsOpen || clockHeldForInstructions;
  }

  /**
   * January's message (singleplayer, as the first round begins): "detailed instructions" from
   * Xenocorp; said to its end (not skipped), the controls are offered after it (Enter to open
   * them, X to skip; gone by themselves after 30 s). The round's clock runs on while it's said
   * (as for every message), and waits only while the controls cover the screen.
   */
  private void startJanuaryMessage() {
    Thread load = new Thread(() -> {
      TransmissionMessage january = TransmissionMessage.load(TransmissionMessage.JANUARY, worldSeed);
      showMessage(january, skipped -> {
        if (skipped || !inGame) return;
        offerControls();
      });
    }, "january-message");
    load.setDaemon(true);
    load.start();
  }

  // The year's later messages (M52, singleplayer), by month (1 January, as the rounds are; 0 for
  // none): October's or December's, whichever's to be played this game; and the month the
  // oxygen's downgraded, if it is
  private int seasonalMonth, oxygenMonth;
  static final int AUGUST = 8, OCTOBER = 10, DECEMBER = 12, OXYGEN_FROM = 7;
  // The chance each month from July (with no other message) that it's the oxygen's
  private static final float OXYGEN_CHANCE = 0.27f;

  /**
   * Chooses this game's later messages: August's always; October's or December's, an even
   * chance of each; and in each month from July with no other message, until it's happened, a
   * OXYGEN_CHANCE of the oxygen's downgrade.
   */
  void planLaterMessages() {
    java.util.Random rand = new java.util.Random(worldSeed * 6151L + 29L);
    seasonalMonth = rand.nextBoolean() ? OCTOBER : DECEMBER;
    oxygenMonth = 0;
    for (int month = OXYGEN_FROM; month <= GameHUD.ROUNDS && oxygenMonth == 0; month++) {
      if (month == AUGUST || month == seasonalMonth) continue;
      if (rand.nextFloat() < OXYGEN_CHANCE) oxygenMonth = month;
    }
    // Developer aids: -Dxenoguesser.season=10 (or 12) and -Dxenoguesser.oxygenmonth=7 (0 for never)
    seasonalMonth = Dev.integer("xenoguesser.season", seasonalMonth);
    oxygenMonth = Dev.integer("xenoguesser.oxygenmonth", oxygenMonth);
    System.out.println("[MESSAGES] August's, " + (seasonalMonth == OCTOBER ? "October's" : "December's")
        + (oxygenMonth > 0 ? ", the oxygen's in month " + oxygenMonth : ", no oxygen's"));
  }

  /** The message for a month as it begins (singleplayer), if it has one. */
  void monthMessage(int month) {
    String name = month == AUGUST ? TransmissionMessage.AUGUST
        : month == seasonalMonth ? (month == OCTOBER ? TransmissionMessage.OCTOBER : TransmissionMessage.DECEMBER)
        : month == oxygenMonth ? TransmissionMessage.OXYGEN : null;
    if (name == null) return;
    boolean oxygen = month == oxygenMonth;
    Thread load = new Thread(() -> {
      TransmissionMessage message = TransmissionMessage.load(name, worldSeed);
      // The oxygen's downgraded once it's been said (or skipped), for the rest of the game
      showMessage(message, skipped -> {
        if (oxygen && inGame && glEventListener != null) glEventListener.downgradeOxygen();
      });
    }, "month-message");
    load.setDaemon(true);
    load.start();
  }

  /** The controls in the middle of the screen, until Continue or Enter. */
  private void showControls(Runnable then) {
    if (controlsPanel == null) {
      controlsPanel = new ControlsPanel();
      layeredPane.add(controlsPanel, Integer.valueOf(JLayeredPane.MODAL_LAYER + 20));
    }
    controlsPanel.open(() -> {
      then.run();
      layoutComponents();
      layeredPane.revalidate();
      layeredPane.repaint();
      updateMouseMode();
      canvas.requestFocus();
    });
    layoutComponents();
    layeredPane.revalidate();
    layeredPane.repaint();
    updateMouseMode();
  }

  // Between a quarter's last results and the shop: whether its target was met
  QuarterPanel quarterPanel;
  // Whether the shop open is one between rounds (closed as the next round starts), not the first
  private boolean shopBetweenRounds;

  /**
   * The results' button: at the end of a quarter, whether its productivity target was met
   * (failed, the game is lost; the last quarter passed, it's won), then on to the shop.
   */
  private void afterResults() {
    Multiplayer session = Multiplayer.active();
    if (session != null && multiplayerScore != null) {
      multiplayerAfterResults(session, multiplayerScore);
      return;
    }
    int money = gameHUD.getProductivity(), target = gameHUD.getTarget();
    if (!gameHUD.isQuarterEnd()) {
      // (M50) Out of reach: even full marks in every round left of the quarter wouldn't meet its
      // target, so it's lost now rather than played out
      int left = GameHUD.ROUNDS_PER_QUARTER - 1 - (gameHUD.getRound() - 1) % GameHUD.ROUNDS_PER_QUARTER;
      int best = money + left * MapPanel.FULL_MARKS;
      if (best < target) {
        quarterPanel.showOutOfReach(gameHUD.getQuarter(), money, target, left, best, () -> {
          quarterPanel.close();
          endGame(false);
        });
        layoutComponents();
        return;
      }
      openShop();
      return;
    }
    boolean passed = money >= target, won = passed && gameHUD.isLastRound();
    // (the year won: this mode and kind of map's achievement, popping up over the verdict)
    if (won) Achievements.unlock(Achievements.winFor(GameMode.current(), MapKind.current()));
    quarterPanel.show(gameHUD.getQuarter(), money, target, gameHUD.isLastRound(), () -> {
      quarterPanel.close();
      if (!passed) endGame(false);
      else if (won) endGame(true);
      else openShop();
    });
    layoutComponents();
  }

  // Multiplayer's scoring (M13, M17); null in singleplayer
  private MultiplayerScore multiplayerScore;

  /**
   * Multiplayer, after a round's results (M13, M17): on to the shop, or at a quarter's end its
   * verdict between the two players (both PCs work it out from the same guesses), then on to
   * the shop, to the game's end (an irrecoverable lead after a Q1 to Q3 sweep, or the year won
   * on points), or to final deciders if the points are level; after a decider, the game's end
   * if one player scored higher, another decider if not. Every verdict's button waits for both (M16).
   */
  private void multiplayerAfterResults(Multiplayer session, MultiplayerScore score) {
    // (this player first throughout, so it's plain which line is theirs)
    Multiplayer.Role me = session.role, them = me.other();
    int round = gameHUD.getRound(), decider = gameHUD.deciderNumber();
    if (decider > 0) {
      Multiplayer.Role winner = score.deciderWinner(round);
      String scores = String.format("%s: %,d   %s: %,d", name(me, me), score.roundScore(me, round), name(them, me), score.roundScore(them, round));
      if (winner != null) {
        showGameEnd(session, score, "Final decider " + decider + ": " + (winner == me ? "you scored higher" : name(winner, me) + " scored higher")
            + " (" + scores + ")", winner);
        return;
      }
      showVerdict(session, "Final decider " + decider, HudStyle.GOLD, "Level again: another final decider", null,
          java.util.List.of(scores), java.util.List.of(HudStyle.VALUE), "On to the next decider",
          Multiplayer.STAGE_FINAL_DECIDER + decider, this::openShop);
      devTestRun.verdictShot("decider" + decider);
      return;
    }
    if (!gameHUD.isQuarterEnd()) {
      openShop();
      return;
    }
    int quarter = gameHUD.getQuarter(), points = MultiplayerScore.pointsFor(quarter);
    Multiplayer.Role winner = score.quarterWinner(quarter);
    String gained = " (+" + points + (points == 1 ? " point)" : " points)");
    String headline = winner == null ? "Level: nobody gets the points"
        : winner == me ? "You won Quarter " + (quarter + 1) + gained
        : name(winner, me) + " won Quarter " + (quarter + 1) + gained;
    java.util.List<String> lines = new java.util.ArrayList<>(java.util.List.of(
        String.format("%s: %,d productivity", name(me, me), score.productivity(me, quarter)),
        String.format("%s: %,d productivity", name(them, me), score.productivity(them, quarter)),
        String.format("Quarter points: you %d, Employee %s %d", score.points(me), them.label, score.points(them))));
    java.util.List<Color> colours = new java.util.ArrayList<>(java.util.List.of(GameHUD.textColour(me), GameHUD.textColour(them), HudStyle.VALUE));
    String button = "Continue to the shop";
    Runnable next = this::openShop;
    Multiplayer.Role sweep = quarter == GameHUD.QUARTERS - 2 ? score.sweepWinner() : null;
    if (sweep != null) {
      lines.add((sweep == me ? "You have" : name(sweep, me) + " has") + " an irrecoverable lead");
      colours.add(HudStyle.GOLD);
      button = "See the result";
      next = () -> showGameEnd(session, score, (sweep == me ? "You" : name(sweep, me)) + " won Q1, Q2 and Q3: an irrecoverable lead", sweep);
    } else if (quarter == GameHUD.QUARTERS - 1) {
      Multiplayer.Role year = score.yearWinner();
      if (year != null) {
        button = "See the result";
        next = () -> showGameEnd(session, score, "The year is over: " + (year == me ? "you have" : name(year, me) + " has")
            + " more quarter points", year);
      } else {
        lines.add("Points are level: final decider");
        colours.add(HudStyle.GOLD);
        button = "On to the final decider";
        next = () -> {
          gameHUD.startDeciders();
          openShop();
        };
      }
    }
    // (the panel's edge green when this player won the quarter, red when the partner did)
    showVerdict(session, "Quarter " + (quarter + 1), HudStyle.QUARTER_COLOURS[quarter], headline, outcomeEdge(winner, me),
        lines, colours, button, Multiplayer.STAGE_QUARTER_END + quarter + 1, next);
    devTestRun.verdictShot("quarter" + (quarter + 1));
  }

  /** A player as named on the verdicts: "Employee 119-A", with " (You)" after this player. */
  private static String name(Multiplayer.Role player, Multiplayer.Role me) {
    return "Employee " + player.label + (player == me ? " (You)" : "");
  }

  /** The verdict panel's edge: green if winner is this player, red if the partner, none (the usual) for a tie. */
  private static Color outcomeEdge(Multiplayer.Role winner, Multiplayer.Role me) {
    return winner == null ? null : winner == me ? GOOD : BAD;
  }

  private static final Color GOOD = new Color(110, 225, 130), BAD = new Color(245, 105, 90);

  /** Multiplayer: a verdict on the quarter panel; its button goes on (to then) once both players have pressed theirs. */
  private void showVerdict(Multiplayer session, String kicker, Color kickerColour, String headline, Color edge,
                           java.util.List<String> lines, java.util.List<Color> colours, String button, int stage, Runnable then) {
    quarterPanel.showMultiplayer(kicker, kickerColour, headline, HudStyle.VALUE, edge, lines, colours, button,
        () -> session.remoteStageDone(stage) ? null : "Waiting for Player " + session.remoteLabel(),
        () -> {
          session.sendStageDone(stage);
          whenTrue(() -> session.remoteStageDone(stage), () -> {
            System.out.println("[MP] Both pressed on at stage " + stage + " at " + System.currentTimeMillis());
            quarterPanel.close();
            then.run();
            layoutComponents();
          });
        });
    layoutComponents();
  }

  /**
   * Multiplayer: the game's end (both PCs show it): who's retained and who's released, why,
   * each quarter's winner and the points; its edge green for the winner, red for the loser.
   * Its button leaves the game for the menu.
   */
  private void showGameEnd(Multiplayer session, MultiplayerScore score, String reason, Multiplayer.Role winner) {
    Multiplayer.Role me = session.role, them = me.other();
    boolean won = winner == me;
    if (won) {
      Achievements.unlock(Achievements.Achievement.WIN_MULTIPLAYER);
      if (score.points(them) == 0) Achievements.unlock(Achievements.Achievement.FLAWLESS_MULTIPLAYER);
    }
    java.util.List<String> lines = new java.util.ArrayList<>();
    java.util.List<Color> colours = new java.util.ArrayList<>();
    lines.add(reason);
    colours.add(HudStyle.GOLD);
    for (int q = 0; q < GameHUD.QUARTERS && score.decided(q); q++) {
      Multiplayer.Role w = score.quarterWinner(q);
      lines.add("Q" + (q + 1) + ": " + (w == null ? "level, no points" : (w == me ? "you" : name(w, me)) + " (+" + MultiplayerScore.pointsFor(q) + ")"));
      colours.add(w == null ? HudStyle.LABEL : GameHUD.textColour(w));
    }
    lines.add(String.format("Quarter points: you %d, Employee %s %d", score.points(me), them.label, score.points(them)));
    colours.add(HudStyle.VALUE);
    System.out.println("[MP] Game over: Employee " + winner.label + " wins (" + reason + ")");
    gameOver = true;
    quarterPanel.showMultiplayer(won ? "You win" : "You lose", won ? GOOD : BAD,
        won ? "You are retained. Employee " + them.label + " will be released via the airlock."
            : "Employee " + them.label + " is retained. You will be released via the airlock.",
        HudStyle.VALUE, won ? GOOD : BAD, lines, colours, "Main menu", null, () -> {
          quarterPanel.close();
          // (the game's over: the room is left, so the next game, singleplayer or not, starts afresh)
          session.leave();
          endGame(won);
        });
    layoutComponents();
    devTestRun.verdictShot("gameend");
  }

  // Multiplayer: this player's own game mode and map kind, given back after the game (the
  // joiner plays the host's); and whether the game's end is showing (a partner leaving then changes nothing)
  private GameMode menuGameMode;
  private MapKind menuMapKind;
  private boolean gameOver;

  /**
   * Multiplayer: the partner has gone (left on purpose, the connection lost, or silent for 10
   * s). Mid-game this player wins (M23): the end panel says so and why, then Main menu. Before
   * the game (still loading, or waiting at Begin) it's back to the menu, saying who left.
   */
  private void partnerGone(Multiplayer session, String reason) {
    if (gameOver) return;
    String who = "Employee " + session.remoteLabel();
    System.out.println("[MP] Partner gone (" + reason + ")" + (inGame ? ": this player wins" : ": back to the menu"));
    if (inGame && quarterPanel != null) {
      gameOver = true;
      if (shopPanel != null && shopPanel.isOpen()) shopPanel.close();
      quarterPanel.showMultiplayer("You win", GOOD, "You win: " + who + " disconnected",
          HudStyle.VALUE, GOOD, java.util.List.of(reason), java.util.List.of(HudStyle.LABEL), "Main menu", null, () -> {
            quarterPanel.close();
            endGame(true);
          });
      layoutComponents();
      updateMouseMode();
      devTestRun.verdictShot("partnergone");
      return;
    }
    leaveBeforeTheGame(who + " left. " + reason);
  }

  /** Multiplayer, before the game has begun: gives up the world being made (or made) and goes back to the menu with a notice. */
  private void leaveBeforeTheGame(String notice) {
    generationRun++;
    gameOver = true;
    FPSAnimator running = animator;
    animator = null;
    if (glEventListener != null) glEventListener.stopSounds();
    Thread stop = new Thread(() -> {
      if (running != null && running.isStarted()) running.stop();
      SwingUtilities.invokeLater(() -> {
        backToMenuFromGame();
        gameOver = false;
        mainMenu.setNotice(notice);
        // Developer aid: -Dxenoguesser.mpautoagain (with mpauto) hosts or joins again a moment
        // later, to test a second game in the same run
        String again = Dev.property("xenoguesser.mpauto");
        if (again != null && Dev.property("xenoguesser.mpautoagain") != null) {
          later(3000, () -> {
            startMultiplayer();
            if (multiplayerPanel != null) multiplayerPanel.autoStart(!again.equalsIgnoreCase("join"));
          });
        }
      });
    }, "leave-before-game");
    stop.setDaemon(true);
    stop.start();
  }

  /** Runs then (on the Swing thread) as soon as condition holds, checking every 100 ms. */
  private static void whenTrue(java.util.function.BooleanSupplier condition, Runnable then) {
    javax.swing.Timer wait = new javax.swing.Timer(100, null);
    wait.addActionListener(e -> {
      // (given up should multiplayer be over meanwhile)
      if (Multiplayer.active() == null) {
        wait.stop();
        return;
      }
      if (!condition.getAsBoolean()) return;
      wait.stop();
      then.run();
    });
    wait.setInitialDelay(0);
    wait.start();
  }

  /** Opens the shop over the results; once the player has bought something or nothing, the next round is asked for. */
  private void openShop() {
    shopBetweenRounds = true;
    int round = gameHUD.getRound();
    Multiplayer session = Multiplayer.active();
    if (session == null) {
      shopPanel.open(glEventListener.shopOffers(round), minimap::requestNextRound);
    } else {
      // Multiplayer (M16): the next round is asked for once both have left their shops, and it
      // starts once both PCs have built it (see the GL listener); until then the shop says so.
      // There's no cash: the round's loser takes one of three for free, the winner nothing (a
      // level round, both take one)
      Multiplayer.Role me = session.role, them = me.other();
      int mine = multiplayerScore != null ? multiplayerScore.roundScore(me, round) : -1;
      int theirs = multiplayerScore != null ? multiplayerScore.roundScore(them, round) : -1;
      boolean won = mine > theirs;
      String note = won ? "You won the round: no pick this time"
          : mine < theirs ? "You lost the round: take one, free"
          : "A level round: take one, free";
      shopPanel.open(won ? java.util.List.of() : glEventListener.shopOffers(round),
          () -> leaveShopTogether(session, round, minimap::requestNextRound),
          () -> session.remoteShopDone(round) && session.remoteRoundBuilt(round + 1) ? null
              : "Waiting for Player " + session.remoteLabel(), note);
    }
    layoutComponents();
  }

  /**
   * Multiplayer: this player has left the shop after round (0: the one before the first): the
   * partner hears so (SHOP_DONE), and then, once the partner has left theirs too, then runs.
   */
  private void leaveShopTogether(Multiplayer session, int round, Runnable then) {
    session.sendShopDone(round);
    javax.swing.Timer wait = new javax.swing.Timer(100, null);
    wait.addActionListener(e -> {
      if (session.isClosed()) {
        wait.stop();
        return;
      }
      if (!session.remoteShopDone(round)) return;
      wait.stop();
      System.out.println("[MP] Both have left the shop after round " + round + " at " + System.currentTimeMillis());
      then.run();
    });
    wait.setInitialDelay(0);
    wait.start();
  }

  /** The shop before the first round (January): leaving it, the round (already loaded) begins. */
  private void openFirstShop() {
    shopBetweenRounds = false;
    // (the money shown over it)
    gameHUD.setVisible(true);
    glEventListener.setWorldHidden(true);
    // (round 0: before the first round, where everything is free), with Xenocorp's word on it
    ShopPanel shop = shopPanel;
    // (nothing taken, nor the shop left, until the message has been said or skipped)
    Thread message = new Thread(() -> {
      TransmissionMessage gift = TransmissionMessage.load(TransmissionMessage.GIFT, worldSeed);
      SwingUtilities.invokeLater(() -> shop.playMessage(gift));
    }, "gift-message");
    message.setDaemon(true);
    Runnable intoTheRound = () -> {
      shopPanel.close();
      gameHUD.setVisible(false);
      glEventListener.setWorldHidden(false);
      layoutComponents();
      canvas.requestFocus();
      updateMouseMode();
      Achievements.unlock(Multiplayer.isActive() ? Achievements.Achievement.PLAY_MULTIPLAYER : Achievements.Achievement.PLAY_SINGLEPLAYER);
      // January's message, and then the controls (singleplayer); and the year's later messages chosen
      if (!Multiplayer.isActive()) {
        planLaterMessages();
        startJanuaryMessage();
      }
    };
    Multiplayer session = Multiplayer.active();
    if (session == null) {
      shopPanel.open(glEventListener.shopOffers(0), intoTheRound);
    } else {
      // Multiplayer: the first round (built on both already) begins once both have left the shop
      shopPanel.open(glEventListener.shopOffers(0), () -> leaveShopTogether(session, 0, () -> {
            intoTheRound.run();
            glEventListener.startRoundClock();
          }),
          () -> session.remoteShopDone(0) ? null : "Waiting for Player " + session.remoteLabel());
    }
    // (nothing taken, nor the shop left, until the message has been said or skipped; singleplayer
    // only, as its word that all else must be bought from earnings doesn't hold in multiplayer)
    if (session == null) {
      shopPanel.holdForMessage();
      message.start();
    }
    layoutComponents();
    updateMouseMode();
  }

  /** The game's over (the year won, or a quarter's target missed): says so, and goes back to the main menu. */
  void endGame(boolean won) {
    System.out.println(won ? "You win" : "You lose");
    if (messageStrip != null) messageStrip.stop();
    glEventListener.stopSounds();
    inGame = false;
    FPSAnimator running = animator;
    animator = null;
    // (the animator is stopped off the UI thread, which it needs to finish its frame)
    Thread stop = new Thread(() -> {
      if (running != null && running.isStarted()) running.stop();
      SwingUtilities.invokeLater(this::backToMenuFromGame);
    }, "end-game");
    stop.setDaemon(true);
    stop.start();
  }

  private void backToMenuFromGame() {
    withdrawControls();
    // Multiplayer: the room is left (the partner hears so) and this player's own menu choices come back
    Multiplayer session = Multiplayer.active();
    if (session != null) session.leave();
    if (menuGameMode != null) GameMode.choose(menuGameMode);
    if (menuMapKind != null) MapKind.choose(menuMapKind);
    if (session != null || menuGameMode != null) {
      System.out.println("[MP] Back at the menu: multiplayer over (" + (Multiplayer.active() == null ? "no session" : "session still open!")
          + "), " + GameMode.current() + ", " + MapKind.current());
    }
    menuGameMode = null;
    menuMapKind = null;
    inGame = false;
    if (loadingScreen != null) {
      loadingScreen.stop();
      layeredPane.remove(loadingScreen);
    }
    for (java.awt.Component c : new java.awt.Component[] { canvas, minimap, minimap != null ? minimap.getLayersPanel() : null,
        minimap != null ? minimap.getPreviewCard() : null, gameHUD, shopPanel, quarterPanel, messageStrip }) {
      if (c != null && c.getParent() == layeredPane) layeredPane.remove(c);
    }
    canvas = null;
    podSequence = null;
    podShown = false;
    glEventListener = null;
    minimap = null;
    gameHUD = null;
    shopPanel = null;
    quarterPanel = null;
    messageStrip = null;
    // (their GL context has gone with the 3D view)
    Shader.forgetAll();
    unlockWindowDragging();
    mouseCaptured = false;
    setCursor(Cursor.getDefaultCursor());
    keysDown.clear();
    loadingProgress = new LoadingProgress();
    loadingScreen = null;
    // (the next game is on a new world)
    if (FIXED_SEED == null) worldSeed = Math.max(System.currentTimeMillis(), worldSeed + 1);
    mainMenu = new MainMenu(this::startSingleplayer, this::startMultiplayer, this::shutdownGame);
    TransmissionMessage.prepare(worldSeed);
    layeredPane.add(mainMenu, JLayeredPane.DRAG_LAYER);
    layoutComponents();
    layeredPane.revalidate();
    layeredPane.repaint();
  }

  private void onMapChanged() {
    gameHUD.setVisible(minimap.isFullScreenRevealMode());
    // (the next round has begun)
    if (!minimap.isFullScreenRevealMode() && shopPanel != null && shopPanel.isOpen() && shopBetweenRounds) {
      shopPanel.close();
      // April's begun: a message from Xenocorp
      // (not in multiplayer: M24)
      if (!Multiplayer.isActive()) {
        if (gameHUD.getRound() == 1 + GameHUD.ROUNDS_PER_QUARTER) glEventListener.startAprilMessage(this::showMessage);
        // (and the later ones, in their months: a round to a month)
        else monthMessage(gameHUD.getRound());
      }
    }
    // (the 3D view goes aside behind the results, and comes back for the next round)
    SwingUtilities.invokeLater(this::layoutComponents);
    updateMouseMode();
  }

  void setSettingsOpen(boolean open) {
    settingsOpen = open;
    settingsMenu.setVisible(open);
    layoutComponents();
    if (open) {
      keysDown.removeIf(k -> k != KeyEvent.VK_ESCAPE);
      if (keyboardInput != null) keyboardInput.releaseAll();
    }
    // (before the game, there's no game to pause or mouse to free)
    if (glEventListener != null) glEventListener.setMenuOpen(open);
    if (canvas != null) updateMouseMode();
    layeredPane.repaint();
  }

  static void later(int millis, Runnable action) {
    javax.swing.Timer timer = new javax.swing.Timer(millis, e -> action.run());
    timer.setRepeats(false);
    timer.start();
  }

  // The layer the title bar and settings go in, over the menu and the loading screen
  private static final int TOP_LAYER = JLayeredPane.DRAG_LAYER + 50;

  void layoutComponents() {
    int paneWidth = layeredPane.getWidth();
    // Below the title bar when there is one
    int top = titleBar != null && titleBar.isVisible() ? WindowTitleBar.HEIGHT : 0;
    contentTop = top;
    int paneHeight = layeredPane.getHeight() - top;
    if (titleBar != null) titleBar.setBounds(0, 0, paneWidth, WindowTitleBar.HEIGHT);
    if (resizers != null) {
      boolean windowed = titleBar.isVisible();
      int t = WindowResizer.THICKNESS, fullH = layeredPane.getHeight();
      Rectangle[] at = { new Rectangle(0, 0, t, fullH), new Rectangle(paneWidth - t, 0, t, fullH),
          new Rectangle(0, 0, paneWidth, t), new Rectangle(0, fullH - t, paneWidth, t) };
      for (int k = 0; k < resizers.length; k++) {
        resizers[k].setVisible(windowed);
        resizers[k].setBounds(at[k]);
      }
    }
    if (settingsMenu != null) {
      settingsMenu.setBounds((paneWidth - SettingsMenu.W) / 2, top + (paneHeight - SettingsMenu.H) / 2, SettingsMenu.W, SettingsMenu.H);
    }
    if (mainMenu != null) mainMenu.setBounds(0, top, paneWidth, paneHeight);
    if (multiplayerPanel != null) multiplayerPanel.setBounds(0, top, paneWidth, paneHeight);
    if (loadingScreen != null) {
      // (once the pod's in view, only the message's strip along the bottom)
      if (podShown) {
        int stripH = LoadingScreen.stripHeight(paneHeight);
        loadingScreen.setBounds(0, top + paneHeight - stripH, paneWidth, stripH);
      } else {
        loadingScreen.setBounds(0, top, paneWidth, paneHeight);
      }
    }
    if (shopPanel != null) shopPanel.setBounds(0, top, paneWidth, paneHeight);
    if (quarterPanel != null) quarterPanel.setBounds(0, top, paneWidth, paneHeight);
    if (messageStrip != null) {
      int stripH = Math.max(44, paneHeight / 20);
      messageStrip.setBounds(0, top + paneHeight - stripH, paneWidth, stripH);
    }
    if (controlsPanel != null) controlsPanel.setBounds(0, top, paneWidth, paneHeight);
    if (canvas == null) return;

    // While the cockpit is up the 3D view (drawn by the system, over everything) waits just off
    // the window's edge at full size, still drawing as the world loads, so it can't cover it
    boolean inPod = podSequence != null && !podSequence.handedOver();
    // (until the pod's first frame, the loading screen's black covers it)
    boolean cockpit = (inPod && !podShown)
        // (and behind the results, while the next round is built behind them)
        || (minimap != null && minimap.isFullScreenRevealMode())
        // (and behind the shop before the first round)
        || (shopPanel != null && shopPanel.isOpen()) || (quarterPanel != null && quarterPanel.isOpen())
        // (and behind the controls: the system draws the 3D view over anything in its way)
        || (controlsPanel != null && controlsPanel.isOpen())
        // (and behind the settings, so whatever the graphics driver they're always seen and
        // clicked: only in a multiplayer game, which isn't paused, is the world kept in view behind them)
        || (settingsOpen && !(inGame && Multiplayer.isActive()));
    // (far off, so however quickly the window is pulled wider it's never seen)
    canvas.setBounds(cockpit ? paneWidth + 20000 : 0, top, paneWidth - 2, paneHeight - 2);
    // Nothing of the game's over the pod
    if (minimap == null) return;
    if (inPod) {
      minimap.setVisible(false);
      if (minimap.getLayersPanel() != null) minimap.getLayersPanel().setVisible(false);
      if (minimap.getPreviewCard() != null) minimap.getPreviewCard().setVisible(false);
      return;
    }
    // The map sized afresh for the space there is now
    minimap.fitToWindow(paneWidth, paneHeight);
    updateMinimapBounds(layeredPane, minimap);
    // (and while the game's paused in singleplayer, no map to study: M46)
    boolean paused = settingsOpen && !Multiplayer.isActive() && !minimap.isFullScreenRevealMode();
    minimap.setVisible(!paused);
    if (paused && minimap.getLayersPanel() != null) minimap.getLayersPanel().setVisible(false);
    gameHUD.setBounds(HudStyle.HUD_MARGIN, top + HudStyle.HUD_MARGIN, gameHUD.getWidth(), gameHUD.getHeight());
  }

  private WindowTitleBar titleBar;
  // Where the game's area starts below the title bar (0 in fullscreen)
  private static int contentTop;
  private WindowResizer[] resizers;
  // The screen's width over its height: the game area keeps this shape in a window
  private float screenAspect = 16f / 9f;
  // Where the window was last when windowed, to put it back there
  private Rectangle windowedBounds;

  /**
   * Fullscreen (filling the screen it's on, no title bar) or windowed (a window most of the
   * screen's size with the game's own title bar, where it was last, else in the middle), as
   * the settings say.
   */
  public void applyDisplayMode() {
    GraphicsConfiguration config = getGraphicsConfiguration() != null ? getGraphicsConfiguration()
        : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
    Rectangle screen = config.getBounds();
    boolean wasWindowed = titleBar.isVisible() && isShowing();
    if (wasWindowed) windowedBounds = getBounds();
    if (GameSettings.fullscreen()) {
      titleBar.setVisible(false);
      setBounds(screen);
    } else {
      titleBar.setVisible(true);
      if (windowedBounds == null) {
        int w = Math.round(screen.width * 0.8f), h = Math.round(w / screenAspect) + WindowTitleBar.HEIGHT;
        windowedBounds = new Rectangle(screen.x + (screen.width - w) / 2, screen.y + (screen.height - h) / 2, w, h);
      }
      setBounds(windowedBounds);
    }
    layoutComponents();
    layeredPane.revalidate();
    layeredPane.repaint();
  }

  /** In fullscreen, puts the window back over the whole screen should anything have moved or shrunk it. */
  private void keepFullscreen() {
    if (!GameSettings.fullscreen() || titleBar == null || !isShowing()) return;
    Rectangle screen = getGraphicsConfiguration().getBounds();
    if (!getBounds().equals(screen)) SwingUtilities.invokeLater(() -> {
      if (GameSettings.fullscreen() && !getBounds().equals(getGraphicsConfiguration().getBounds())) applyDisplayMode();
    });
  }

  public void lockWindowDragging() {
      this.lockedWindowPosition = this.getLocation();
      this.isWindowLocked = true;
  }

  public void unlockWindowDragging() {
      this.isWindowLocked = false;
      this.lockedWindowPosition = null;
  }

  void shutdownGame() {
    new Thread(new Runnable() {
        @Override
        public void run() {
            if (animator != null && animator.isStarted()) {
                animator.stop();
            }
            // Leaves any multiplayer room and shuts Steam down, if multiplayer was ever started
            Multiplayer.shutdown();
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (canvas != null) remove(canvas);
                    dispose();
                    System.exit(0);
                }
            });
        }
    }).start();
  }

  public static void updateMinimapBounds(JLayeredPane layeredPane, MapPanel minimap) {
    int w = layeredPane.getWidth();
    int h = layeredPane.getHeight();
    
    Dimension minimapSize = minimap.getPreferredSize();
    int panelWidth = minimapSize.width;
    int panelHeight = minimapSize.height;
    
    if (minimap.isFullScreenRevealMode()) {
        minimap.setBounds((w - panelWidth) / 2, (h - panelHeight) / 2, panelWidth, panelHeight);
    } else {
        minimap.setBounds(w - panelWidth, h - panelHeight - 2, panelWidth, panelHeight + 2);
    }
    // The map's tick boxes, to its left while it's enlarged
    MapLayersPanel layers = minimap.getLayersPanel();
    if (layers != null) {
        if (layers.getParent() != layeredPane) layeredPane.add(layers, JLayeredPane.PALETTE_LAYER);
        boolean shown = minimap.isLargeMap() && !minimap.isFullScreenRevealMode();
        layers.setVisible(shown);
        if (shown) {
            // (the map's full height, however few there are)
            int height = minimap.getHeight() - 12;
            layers.setBounds(minimap.getX() - MapLayersPanel.WIDTH - 8, minimap.getY() + 6, MapLayersPanel.WIDTH, height);
            layers.revalidate();
        }
    }
    // The turning animal or plant whose range is shown, in the space above the map
    PreviewCard card = minimap.getPreviewCard();
    if (card.getParent() != layeredPane) layeredPane.add(card, JLayeredPane.PALETTE_LAYER);
    // (lined up with the tick-box panel's left edge, down to a row above the map kept for the
    // map's key hint ("M Contract map"), which it would otherwise cover when its leaf is shown)
    // (below the title bar, when windowed)
    int hintRow = HudStyle.HINT_H + 16;
    int cardHeight = Math.min(PreviewCard.WIDTH + 70, minimap.getY() - contentTop - 20 - hintRow);
    boolean cardShown = minimap.isLargeMap() && !minimap.isFullScreenRevealMode() && card.hasSubject() && cardHeight > 120;
    card.setVisible(cardShown);
    if (cardShown) card.setBounds(minimap.getX() - MapLayersPanel.WIDTH - 8, minimap.getY() - hintRow - cardHeight, card.wantedWidth(), cardHeight);
    layeredPane.revalidate();
    layeredPane.repaint();
  }

  public boolean getIsDebugModeActive() {
    return IS_DEBUG_MODE_ACTIVE;
  }
}
