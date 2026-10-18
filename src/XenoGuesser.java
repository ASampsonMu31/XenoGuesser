import java.awt.*;
import java.awt.event.*;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLCanvas; 
import com.jogamp.opengl.util.FPSAnimator;

public class XenoGuesser extends JFrame {
   
  // A fixed seed can be passed with -Dxenoguesser.seed=123 to test seed consistency; otherwise every game is a new world
  private static final Long FIXED_SEED = Long.getLong("xenoguesser.seed");
  private static final boolean IS_DEBUG_MODE_ACTIVE = true;

  private GLCanvas canvas; 
  private XenoGuesser_GLEventListener glEventListener;
  private FPSAnimator animator;

  private boolean isWindowLocked = false;
  private Point lockedWindowPosition = null;
  private Point permanentWindowPosition = null;

  // Mouse look: while the game has the mouse its pointer is hidden and kept near the middle
  // of the view, and every movement turns the view. The large map, the results and the
  // settings give the pointer back.
  private static final float LOOK_SENSITIVITY = 0.0012f;
  private static final int RECENTRE_DISTANCE = 80;
  private Robot robot;
  private Cursor hiddenCursor;
  private boolean mouseCaptured;
  private Point lastMouse;
  private Camera camera;
  private MyKeyboardInput keyboardInput;
  private SettingsMenu settingsMenu;
  private boolean settingsOpen;
  private volatile boolean inGame;
  private final Set<Integer> keysDown = java.util.concurrent.ConcurrentHashMap.newKeySet();

  // The world being played: a new one each game (the clock's time when it was chosen), unless -Dxenoguesser.seed fixes it
  private long worldSeed;
  private LoadingProgress loadingProgress = new LoadingProgress();
  private LoadingScreen loadingScreen;
  private MainMenu mainMenu;
  private KeyAdapter escapeQuitListener;
  private JLayeredPane layeredPane;
  private MapPanel minimap;
  private GameHUD gameHUD;

  public static void main(String[] args) {
    RunFiles.prepare();
    // Keep the loading screen smooth: GL work runs on the animator's own thread rather than
    // the UI thread that draws the screen, and the background generators leave a core free
    com.jogamp.opengl.Threading.disableSingleThreading();
    int spare = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
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
    layeredPane.add(settingsMenu, Integer.valueOf(TOP_LAYER));
    applyDisplayMode();

    System.setProperty("sun.awt.noerasebackground", "true"); 
    System.setProperty("sun.java2d.noddraw", "true");

    // 3. The main menu first; the loading screen then covers everything until the round's first frame
    mainMenu = new MainMenu(this::startSingleplayer, this::startMultiplayer, this::shutdownGame);
    // The planet's name is said (for the message from Xenocorp) while the menu is up
    TransmissionMessage.prepare(worldSeed);
    layeredPane.add(mainMenu, JLayeredPane.DRAG_LAYER);

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

    KeyAdapter escapeQuitListener = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent e) {
            // Before the game starts (the menu, the cockpit) Esc opens or closes the settings too
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE && !inGame) {
                setSettingsOpen(!settingsOpen);
            }
        }
    };
    this.addKeyListener(escapeQuitListener);
    this.escapeQuitListener = escapeQuitListener;

    // Developer aid: -Dxenoguesser.skipmenu (or taking the menu's picture) goes straight to loading
    if (System.getProperty("xenoguesser.skipmenu") != null || System.getProperty("xenoguesser.menushot") != null) {
      startSingleplayer();
    }
    // Developer aid: -Dxenoguesser.mpauto=host (or =join) opens the multiplayer panel and presses
    // Host game (or Join game with any code: for -Dxenoguesser.mpfake, which doesn't check it)
    String mpAuto = System.getProperty("xenoguesser.mpauto");
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
  private void startSingleplayer() {
    startGame(false);
  }

  /** The loading screen replaces the menu while this.worldSeed's world is generated (singleplayer or multiplayer). */
  private void startGame(boolean multiplayer) {
    if (loadingScreen != null) return;
    loadingScreen = new LoadingScreen(loadingProgress, worldSeed, multiplayer);
    loadingScreen.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
    layeredPane.add(loadingScreen, JLayeredPane.DRAG_LAYER);
    layeredPane.remove(mainMenu);
    layeredPane.repaint();
    requestFocus();

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
      generateWorld(escapeQuitListener, run);
    }, "world-generation");
    worldGeneration.setDaemon(true);
    worldGeneration.setPriority(Thread.NORM_PRIORITY - 2);
    worldGeneration.start();
  }

  // The world being made (its thread), and which run it is: a world given up on before it's
  // ready (multiplayer: the partner left) is still made to the end, but never shown
  private Thread worldGeneration;
  private volatile int generationRun;

  private void generateWorld(KeyAdapter escapeQuitListener, int run) {
    // JOGL's first profile probe takes seconds; doing it here, alongside the writing systems,
    // keeps it off the UI thread where it would freeze the loading screen
    Thread glWarmUp = new Thread(() -> GLProfile.get(GLProfile.GL3), "gl-profile-warm-up");
    glWarmUp.setDaemon(true);
    glWarmUp.start();
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
      float totalRegionWidth = 150_000f;
      Planet.setWidth(totalRegionWidth);
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
      glWarmUp.join();
      if (run != generationRun) return;
      SwingUtilities.invokeLater(() -> {
        if (run == generationRun) buildGameWindow(listener, camera, keyboardInput, worldNoise,
          seaLevelHeight, totalRegionWidth, physicalChunkSize, escapeQuitListener);
      });
    } catch (Exception e) {
      System.err.println("CRITICAL ERROR: World generation failed.");
      loadingProgress.fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      e.printStackTrace();
    }
  }

  private void buildGameWindow(XenoGuesser_GLEventListener listener, Camera camera, MyKeyboardInput keyboardInput,
                               PerlinNoise worldNoise, float seaLevelHeight, float totalRegionWidth,
                               float physicalChunkSize, KeyAdapter escapeQuitListener) {
    glEventListener = listener;

    GLCapabilities glcapabilities = new GLCapabilities(GLProfile.get(GLProfile.GL3));
    // JOGL asks for a 16-bit depth buffer by default, too coarse for close, thin parts
    glcapabilities.setDepthBits(24);
    canvas = new GLCanvas(glcapabilities); 
    canvas.setBackground(Color.BLACK); 

    gameHUD = new GameHUD();
    // Multiplayer: quarter points instead of targets (M13), on the score panel too
    Multiplayer session = Multiplayer.active();
    multiplayerScore = session != null ? MultiplayerScore.of(session) : null;
    if (session != null) gameHUD.setMultiplayer(session, multiplayerScore);
glEventListener.setGameHUD(gameHUD);
    this.camera = camera;
    this.keyboardInput = keyboardInput;

    minimap = new MapPanel(
      750,
      750,
      totalRegionWidth,
      seaLevelHeight,
      worldNoise,
      physicalChunkSize,
      glEventListener
    );

    minimap.setMainApp(this);
    minimap.setGameHUD(gameHUD);
    glEventListener.setMinimap(minimap);
    glEventListener.setLoading(loadingProgress, this::onWorldReady);
    minimap.setOnSizeChanged(this::onMapChanged);
    shopPanel = new ShopPanel(gameHUD);
    quarterPanel = new QuarterPanel();
    minimap.setOnProceedToShop(this::afterResults);
    minimap.setProceedLabel(() -> gameHUD.isQuarterEnd() ? "End of Quarter" : "Proceed to Shop");


    canvas.addGLEventListener(glEventListener);
    // The mouse wheel (and a touchpad's pinch, which comes as one) zooms the view where the game mode allows it
    canvas.addMouseWheelListener(e -> {
      if (inGame && mouseCaptured) glEventListener.zoomBy(e.getPreciseWheelRotation());
    });
    installControls();

    layeredPane.add(canvas, JLayeredPane.DEFAULT_LAYER);
    layeredPane.add(minimap, JLayeredPane.PALETTE_LAYER);
    // (the shop over the results, under the score)
    layeredPane.add(shopPanel, Integer.valueOf(JLayeredPane.PALETTE_LAYER + 50));
    layeredPane.add(quarterPanel, Integer.valueOf(JLayeredPane.PALETTE_LAYER + 60));
    layeredPane.add(gameHUD, JLayeredPane.MODAL_LAYER);
    layoutComponents();

    animator = new FPSAnimator(canvas, 60);
    animator.start();
  }

  /** Called on the UI thread once the first frame of the round has been drawn; the player then chooses when to start. */
  private void onWorldReady() {
    // Multiplayer: the partner hears this PC is loaded (their Begin waits for it, M16)
    Multiplayer session = Multiplayer.active();
    if (session != null) session.sendWorldReady();
    // A HUD test run goes straight in (in multiplayer it waits at Begin like anyone, so both go in together)
    if (System.getProperty("xenoguesser.hudtest") != null && session == null) enterGame();
    else loadingScreen.setWorldReady(this::enterGame);
  }

  private void enterGame() {
    loadingScreen.stop();
    // (the first round begins now: in multiplayer, on both PCs together)
    glEventListener.startRoundClock();
    // A shop before January (a HUD test run skips it), opened under the cockpit before it goes,
    // so the round isn't glimpsed in between; otherwise the world's straight in view
    String test = System.getProperty("xenoguesser.hudtest");
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
    runHudTest();
    updateMouseMode();
  }

  /**
   * Keys work wherever the focus is (except while typing a note): WASD walk, Space jumps, M enlarges
   * or shrinks the map, C uses the compass and Esc opens the settings. Mouse
   * movement anywhere in the window turns the view while the game has the mouse.
   */
  private void installControls() {
    try {
      robot = new Robot();
    } catch (AWTException e) {
      System.err.println("Mouse look unavailable: " + e.getMessage());
    }
    hiddenCursor = Toolkit.getDefaultToolkit().createCustomCursor(
        new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "hidden");

    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
      if (!inGame) return false;
      if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent) return false;
      int code = e.getKeyCode();
      if (e.getID() == KeyEvent.KEY_PRESSED) {
        boolean firstPress = keysDown.add(code);   // ignore key repeat
        if (code == KeyEvent.VK_ESCAPE) {
          if (firstPress) setSettingsOpen(!settingsOpen);
          return true;
        }
        if (settingsOpen) return true;
        // (the shop and the quarter's result take the mouse only)
        if ((shopPanel != null && shopPanel.isOpen()) || (quarterPanel != null && quarterPanel.isOpen())) return true;
        if (firstPress) {
          switch (code) {
            case KeyEvent.VK_M: minimap.toggleSize(); break;
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

    Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
      if (!mouseCaptured) return;
      MouseEvent e = (MouseEvent) event;
      if (e.getID() != MouseEvent.MOUSE_MOVED && e.getID() != MouseEvent.MOUSE_DRAGGED) return;
      Point p = e.getLocationOnScreen();
      if (lastMouse != null) {
        int dx = p.x - lastMouse.x, dy = p.y - lastMouse.y;
        if (dx != 0 || dy != 0) glEventListener.look(dx * LOOK_SENSITIVITY, -dy * LOOK_SENSITIVITY);
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
        keyboardInput.releaseAll();
        updateMouseMode();
      }
    });
  }

  /** Whether a key is held down just now (while playing). */
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
    boolean pointerNeeded = settingsOpen || minimap.isLargeMap() || minimap.isFullScreenRevealMode() || shopPanel.isOpen()
        || (quarterPanel != null && quarterPanel.isOpen());
    boolean capture = inGame && !pointerNeeded && isFocused() && robot != null;
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
    } else if (minimap.isLargeMap() && !settingsOpen) {
      // The pointer appears over the middle of the map, ready to place a marker
      Point origin = minimap.getLocationOnScreen();
      robot.mouseMove(origin.x + minimap.getWidth() / 2, origin.y + minimap.getHeight() / 2);
    }
  }

  // The shop, between a round's results and the next round
  private ShopPanel shopPanel;

  // A message from Xenocorp played during a round, its words along the bottom over everything
  private MessageStrip messageStrip;

  /** Plays a message from Xenocorp over the game, its words scrolling along the bottom (above the map) until it's said. */
  private void showMessage(TransmissionMessage message) {
    SwingUtilities.invokeLater(() -> {
      if (!inGame) return;
      if (messageStrip == null) {
        messageStrip = new MessageStrip();
        layeredPane.add(messageStrip, Integer.valueOf(JLayeredPane.MODAL_LAYER + 10));
      }
      messageStrip.play(message);
      layoutComponents();
    });
  }

  // Between a quarter's last results and the shop: whether its target was met
  private QuarterPanel quarterPanel;
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
    if (!gameHUD.isQuarterEnd()) {
      openShop();
      return;
    }
    int money = gameHUD.getProductivity(), target = gameHUD.getTarget();
    boolean passed = money >= target, won = passed && gameHUD.isLastRound();
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
      verdictShot("decider" + decider);
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
    verdictShot("quarter" + (quarter + 1));
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
    verdictShot("gameend");
  }

  /** Developer aid: the window's Swing panels (not the 3D view) painted to a file. */
  private void saveUiShot(java.io.File file) {
    BufferedImage image = new BufferedImage(Math.max(1, layeredPane.getWidth()), Math.max(1, layeredPane.getHeight()), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    layeredPane.paint(g);
    g.dispose();
    try {
      javax.imageio.ImageIO.write(image, "png", file);
      System.out.println("[SHOT] Panels saved to " + file);
    } catch (java.io.IOException e) {
      System.err.println("Couldn't save the panels: " + e.getMessage());
    }
  }

  /** Developer aid: -Dxenoguesser.verdictshot=prefix saves each multiplayer verdict as it's shown, as prefix_name.png. */
  private void verdictShot(String name) {
    String prefix = System.getProperty("xenoguesser.verdictshot");
    if (prefix == null) return;
    later(1200, () -> saveUiShot(new java.io.File(prefix + "_" + name.replaceAll("[^A-Za-z0-9]+", "_") + ".png")));
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
      verdictShot("partnergone");
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
        String again = System.getProperty("xenoguesser.mpauto");
        if (again != null && System.getProperty("xenoguesser.mpautoagain") != null) {
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
      // starts once both PCs have built it (see the GL listener); until then the shop says so
      shopPanel.open(glEventListener.shopOffers(round), () -> leaveShopTogether(session, round, minimap::requestNextRound),
          () -> session.remoteShopDone(round) && session.remoteRoundBuilt(round + 1) ? null
              : "Waiting for Player " + session.remoteLabel());
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
    message.start();
    layoutComponents();
    updateMouseMode();
  }

  /** The game's over (the year won, or a quarter's target missed): says so, and goes back to the main menu. */
  private void endGame(boolean won) {
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
      if (gameHUD.getRound() == 1 + GameHUD.ROUNDS_PER_QUARTER && !Multiplayer.isActive()) glEventListener.startAprilMessage(this::showMessage);
    }
    // (the 3D view goes aside behind the results, and comes back for the next round)
    SwingUtilities.invokeLater(this::layoutComponents);
    updateMouseMode();
  }

  private void setSettingsOpen(boolean open) {
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

  /**
   * Developer aid: -Dxenoguesser.hudtest=large,compass,settings,shot sets the HUD up
   * once the game starts, and "shot" saves a picture of the whole window a moment later.
   */
  private void runHudTest() {
    String test = System.getProperty("xenoguesser.hudtest");
    if (test == null) return;
    // A test run leaves the mouse alone
    robot = null;
    for (String step : test.split(",")) {
      switch (step.trim()) {
        case "large": minimap.toggleSize(); break;
        case "compass": {
          javax.swing.Timer later = new javax.swing.Timer(1200, e -> glEventListener.useCompass());
          later.setRepeats(false);
          later.start();
          break;
        }
        case "settings": setSettingsOpen(true); break;
        case "thermometer": later(1200, () -> glEventListener.useThermometer()); break;
        // lose: the game lost a moment in, back to the menu; restart: a new game from the menu after
        case "lose": later(1500, () -> endGame(false)); break;
        // april: April's message, as if April had just begun
        case "april": later(1500, () -> glEventListener.startAprilMessage(this::showMessage)); break;
        case "restart": later(Integer.getInteger("xenoguesser.restartdelay", 5000), this::startSingleplayer); break;
        // quarter: the end of a quarter's screen, as if its target were missed
        case "quarter": later(1500, () -> {
          quarterPanel.show(gameHUD.getQuarter(), gameHUD.getProductivity(), gameHUD.getTarget(), false, () -> quarterPanel.close());
          layoutComponents();
        }); break;
        // (with -Dxenoguesser.unlockall, the third and fourth items: the altimeter and the identification kit)
        case "altimeter": later(1200, () -> glEventListener.useItem(2)); break;
        case "kit": later(1200, () -> glEventListener.useItem(3)); break;
        // Multiplayer: face the partner (level); look down at this player's own body; and
        // frameshot: the drawn frame saved (from the GL, so another window over this one doesn't
        // spoil it) to -Dxenoguesser.frameshot=file, -Dxenoguesser.frameshotdelay ms in (default 4000)
        case "partner": later(1500, () -> glEventListener.lookAtPartner(0f)); break;
        // quit: quits the game -Dxenoguesser.quitdelay ms in (default 5000), as the settings' Quit game does
        case "quit": later(Integer.getInteger("xenoguesser.quitdelay", 5000), this::shutdownGame); break;
        // autoplay: every -Dxenoguesser.autoplayms (default 4000) the next thing a player would do:
        // press on from the quarter's verdict, else leave the shop (buying nothing), else go on
        // from the results, else guess (see guessat)
        case "autoplay": {
          javax.swing.Timer play = new javax.swing.Timer(Integer.getInteger("xenoguesser.autoplayms", 4000), e -> {
            if (!inGame || minimap == null) return;
            if (quarterPanel != null && quarterPanel.isOpen()) {
              quarterPanel.devPress();
              return;
            }
            if (shopPanel.isOpen()) shopPanel.nextRound();
            else if (minimap.isFullScreenRevealMode()) minimap.devProceedToShop();
            else minimap.devGuess();
          });
          play.start();
          break;
        }
        // uishot: the window's panels (the map, results, shop: Swing, not the 3D view) painted to
        // <-Dxenoguesser.uishot>_<ms>.png at each of -Dxenoguesser.uishotat=ms,ms,... in
        case "uishot": {
          for (String at : System.getProperty("xenoguesser.uishotat", "4000").split(",")) {
            int ms = Integer.parseInt(at.trim());
            later(ms, () -> saveUiShot(new java.io.File(System.getProperty("xenoguesser.uishot", "ui") + "_" + ms + ".png")));
          }
          break;
        }
        // walkpartner: face the partner and walk at them for four seconds (W held), logging how far apart they end up
        case "walkpartner": {
          later(1500, () -> {
            glEventListener.lookAtPartner(0f);
            System.out.printf("[MP] Walking at the partner from %.2f units%n", glEventListener.distanceToPartner());
            keyboardInput.w = true;
          });
          later(5500, () -> {
            keyboardInput.w = false;
            System.out.printf("[MP] Stopped %.2f units from the partner (their cylinders touch at 6)%n", glEventListener.distanceToPartner());
          });
          break;
        }
        case "lookdown": later(1500, () -> glEventListener.lookPitch(-70f)); break;
        // podview: the landing pod from out in front and to one side (-Dxenoguesser.podview=distance,degrees round,height up)
        case "podview": {
          String[] v = System.getProperty("xenoguesser.podview", "75,30,10").split(",");
          later(1500, () -> glEventListener.viewPod(Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2])));
          break;
        }
        case "frameshot": later(Integer.getInteger("xenoguesser.frameshotdelay", 4000),
            () -> glEventListener.saveNextFrame(new java.io.File(System.getProperty("xenoguesser.frameshot", "frame.png")))); break;
        // herd: faces the nearest herd (and again each time a frame is saved by frameshots)
        case "herd": later(2000, () -> glEventListener.lookAtHerd()); break;
        // frameshots: frames saved at each of -Dxenoguesser.frameshotsat=ms,... as <frameshot>_<ms>.png, facing the herd first
        case "frameshots": {
          for (String at : System.getProperty("xenoguesser.frameshotsat", "4000").split(",")) {
            int ms = Integer.parseInt(at.trim());
            later(ms - 300, () -> glEventListener.lookAtHerd());
            later(ms, () -> glEventListener.saveNextFrame(new java.io.File(System.getProperty("xenoguesser.frameshot", "frame") + "_" + ms + ".png")));
          }
          break;
        }
        // zoom: the map zoomed right in (-Dxenoguesser.mapzoomat=u,v to say where; the middle otherwise)
        case "zoom": {
          String[] at = System.getProperty("xenoguesser.mapzoomat", "0.5,0.5").split(",");
          later(1500, () -> minimap.devZoom(16f, Float.parseFloat(at[0]), Float.parseFloat(at[1])));
          break;
        }
        // guess: a guess two seconds in (-Dxenoguesser.guessdelay); shop: Proceed to Shop pressed
        // six seconds in (-Dxenoguesser.shopdelay); next: on to the next round, buying nothing,
        // twelve seconds in (-Dxenoguesser.nextdelay)
        case "guess": later(Integer.getInteger("xenoguesser.guessdelay", 2000), () -> minimap.devGuess()); break;
        case "shop": later(Integer.getInteger("xenoguesser.shopdelay", 6000), () -> minimap.devProceedToShop()); break;
        case "next": later(Integer.getInteger("xenoguesser.nextdelay", 12000), () -> {
          if (quarterPanel.isOpen() || shopPanel.isOpen()) return;
          minimap.devProceedToShop();
          if (shopPanel.isOpen()) shopPanel.nextRound();
        }); break;
        default: break;
      }
    }
    if (test.contains("shot")) {
      javax.swing.Timer shot = new javax.swing.Timer(Integer.getInteger("xenoguesser.shotdelay", 2600), e -> {
        try {
          Rectangle area = new Rectangle(getLocationOnScreen(), getSize());
          javax.imageio.ImageIO.write(new Robot().createScreenCapture(area), "png",
              new java.io.File(RunFiles.WORLD_DIR, "window_shot.png"));
          // The map on its own, drawn straight from the panel (whatever is on screen)
          java.awt.image.BufferedImage map = new java.awt.image.BufferedImage(Math.max(1, minimap.getWidth()), Math.max(1, minimap.getHeight()),
              java.awt.image.BufferedImage.TYPE_INT_ARGB);
          java.awt.Graphics2D mg = map.createGraphics();
          minimap.paint(mg);
          mg.dispose();
          javax.imageio.ImageIO.write(map, "png", new java.io.File(RunFiles.WORLD_DIR, "map_shot.png"));
          // ...and the tick boxes beside it, if they're showing
          MapLayersPanel layers = minimap.getLayersPanel();
          if (layers != null && layers.isVisible() && layers.getWidth() > 0) {
            java.awt.image.BufferedImage panel = new java.awt.image.BufferedImage(layers.getWidth(), layers.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D pg = panel.createGraphics();
            layers.paint(pg);
            pg.dispose();
            javax.imageio.ImageIO.write(panel, "png", new java.io.File(RunFiles.WORLD_DIR, "map_layers_shot.png"));
          }
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      });
      shot.setRepeats(false);
      shot.start();
    }
  }

  private static void later(int millis, Runnable action) {
    javax.swing.Timer timer = new javax.swing.Timer(millis, e -> action.run());
    timer.setRepeats(false);
    timer.start();
  }

  // The layer the title bar and settings go in, over the menu and the loading screen
  private static final int TOP_LAYER = JLayeredPane.DRAG_LAYER + 50;

  private void layoutComponents() {
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
    if (loadingScreen != null) loadingScreen.setBounds(0, top, paneWidth, paneHeight);
    if (shopPanel != null) shopPanel.setBounds(0, top, paneWidth, paneHeight);
    if (quarterPanel != null) quarterPanel.setBounds(0, top, paneWidth, paneHeight);
    if (messageStrip != null) {
      int stripH = Math.max(44, paneHeight / 20);
      messageStrip.setBounds(0, top + paneHeight - stripH, paneWidth, stripH);
    }
    if (canvas == null) return;

    // While the cockpit is up the 3D view (drawn by the system, over everything) waits just off
    // the window's edge at full size, still drawing as the world loads, so it can't cover it
    boolean cockpit = (loadingScreen != null && loadingScreen.getParent() == layeredPane)
        // (and behind the results, while the next round is built behind them)
        || (minimap != null && minimap.isFullScreenRevealMode())
        // (and behind the shop before the first round)
        || (shopPanel != null && shopPanel.isOpen()) || (quarterPanel != null && quarterPanel.isOpen());
    // (far off, so however quickly the window is pulled wider it's never seen)
    canvas.setBounds(cockpit ? paneWidth + 20000 : 0, top, paneWidth - 2, paneHeight - 2);
    // The map sized afresh for the space there is now
    minimap.fitToWindow(paneWidth, paneHeight);
    updateMinimapBounds(layeredPane, minimap);
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

  private void shutdownGame() {
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
    // (down to just above the map, lined up with the tick-box panel's left edge, clear of the map's key hint at the right)
    // (below the title bar, when windowed)
    int cardHeight = Math.min(PreviewCard.WIDTH + 70, minimap.getY() - contentTop - 20);
    boolean cardShown = minimap.isLargeMap() && !minimap.isFullScreenRevealMode() && card.hasSubject() && cardHeight > 120;
    card.setVisible(cardShown);
    if (cardShown) card.setBounds(minimap.getX() - MapLayersPanel.WIDTH - 8, minimap.getY() - cardHeight - 6, card.wantedWidth(), cardHeight);
    layeredPane.revalidate();
    layeredPane.repaint();
  }

  public boolean getIsDebugModeActive() {
    return IS_DEBUG_MODE_ACTIVE;
  }
}
