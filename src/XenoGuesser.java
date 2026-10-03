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
  private final Set<Integer> keysDown = new HashSet<>();

  private final long worldSeed;
  private final LoadingProgress loadingProgress = new LoadingProgress();
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

    // 2. Setup Window Configurations
    this.setUndecorated(false); 
    this.setResizable(false);
    
    Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    this.setSize(screenSize.width, screenSize.height);
    this.setMinimumSize(screenSize);
    this.setMaximumSize(screenSize);
    this.setLocationRelativeTo(null); 
    this.setExtendedState(JFrame.MAXIMIZED_BOTH);
    
    this.addWindowStateListener(new WindowStateListener() {
        @Override
        public void windowStateChanged(WindowEvent e) {
            if (e.getNewState() != JFrame.MAXIMIZED_BOTH) {
                setExtendedState(JFrame.MAXIMIZED_BOTH);
            }
        }
    });
    
    this.getContentPane().setBackground(Color.BLACK);
    this.setBackground(Color.BLACK); 
    
    layeredPane = new JLayeredPane();
    layeredPane.setOpaque(false); 
    this.setContentPane(layeredPane);

    System.setProperty("sun.awt.noerasebackground", "true"); 
    System.setProperty("sun.java2d.noddraw", "true");

    // 3. The main menu first; the loading screen then covers everything until the round's first frame
    mainMenu = new MainMenu(this::startSingleplayer, this::startMultiplayer, this::shutdownGame);
    layeredPane.add(mainMenu, JLayeredPane.DRAG_LAYER);

    this.addComponentListener(new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) {
            if (getExtendedState() != JFrame.MAXIMIZED_BOTH) {
                setExtendedState(JFrame.MAXIMIZED_BOTH);
            }
            if (isWindowLocked && lockedWindowPosition != null) {
                setLocation(lockedWindowPosition);
            } else if (permanentWindowPosition != null && getExtendedState() == JFrame.NORMAL) {
                setLocation(permanentWindowPosition);
            }
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
            // Until the game starts Esc quits; after that it opens the settings
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE && !inGame) {
                shutdownGame();
            }
        }
    };
    this.addKeyListener(escapeQuitListener);
    this.escapeQuitListener = escapeQuitListener;

    // Developer aid: -Dxenoguesser.skipmenu (or taking the menu's picture) goes straight to loading
    if (System.getProperty("xenoguesser.skipmenu") != null || System.getProperty("xenoguesser.menushot") != null) {
      startSingleplayer();
    }
  }

  /**
   * Play Multiplayer: not available yet. The cockpit plays the multiplayer message while
   * trying to reach the partner pod, then says it's coming soon and returns to the menu.
   */
  private void startMultiplayer() {
    if (loadingScreen != null) return;
    LoadingScreen[] holder = new LoadingScreen[1];
    LoadingScreen preview = new LoadingScreen(new LoadingProgress(), worldSeed, true, () -> backToMenu(holder[0]));
    holder[0] = preview;
    loadingScreen = preview;
    preview.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
    layeredPane.add(preview, JLayeredPane.DRAG_LAYER);
    layeredPane.remove(mainMenu);
    layeredPane.repaint();
  }

  private void backToMenu(LoadingScreen preview) {
    layeredPane.remove(preview);
    loadingScreen = null;
    mainMenu = new MainMenu(this::startSingleplayer, this::startMultiplayer, this::shutdownGame);
    mainMenu.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
    layeredPane.add(mainMenu, JLayeredPane.DRAG_LAYER);
    layeredPane.repaint();
  }

  /** Play Singleplayer: the loading screen replaces the menu while a world is generated. */
  private void startSingleplayer() {
    if (loadingScreen != null) return;
    loadingScreen = new LoadingScreen(loadingProgress, worldSeed);
    loadingScreen.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
    layeredPane.add(loadingScreen, JLayeredPane.DRAG_LAYER);
    layeredPane.remove(mainMenu);
    layeredPane.repaint();
    requestFocus();

    // Everything that doesn't need the GL context is generated off the UI thread
    Thread worldGeneration = new Thread(() -> generateWorld(escapeQuitListener), "world-generation");
    worldGeneration.setDaemon(true);
    worldGeneration.setPriority(Thread.NORM_PRIORITY - 2);
    worldGeneration.start();
  }

  private void generateWorld(KeyAdapter escapeQuitListener) {
    // JOGL's first profile probe takes seconds; doing it here, alongside the writing systems,
    // keeps it off the UI thread where it would freeze the loading screen
    Thread glWarmUp = new Thread(() -> GLProfile.get(GLProfile.GL3), "gl-profile-warm-up");
    glWarmUp.setDaemon(true);
    glWarmUp.start();
    try {
      loadingProgress.begin(LoadingProgress.Stage.WRITING_SYSTEMS);
      try (GlyphGenerator generator = new GlyphGenerator("models/cvae_generator.pt")) {
          int totalDatasetAlphabets = 30; // Matches Omniglot training bounds
          generator.generateAllSystems(totalDatasetAlphabets, RunFiles.ALPHABETS_DIR, worldSeed);
      } catch (Exception e) {
          System.err.println("CRITICAL ERROR: Failed to generate writing systems.");
          e.printStackTrace();
      }

      loadingProgress.begin(LoadingProgress.Stage.SEA_LEVEL);
      PerlinNoise worldNoise = new PerlinNoise(worldSeed);
      float physicalChunkSize = 100.0f;
      float totalRegionWidth = 150_000f;
      float seaLevelHeight = XenoGuesser_GLEventListener.precalculateSeaLevel(worldSeed, totalRegionWidth, worldNoise);
      
      Camera camera = new Camera(Camera.DEFAULT_POSITION, Camera.DEFAULT_TARGET, Camera.DEFAULT_UP, seaLevelHeight);
      MyKeyboardInput keyboardInput = new MyKeyboardInput(); 

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
      javax.imageio.ImageIO.write(listener.buildSoilRegionMap(art.palette()), "png",
                    new java.io.File(WorldArtGenerator.pathFor(WorldArtGenerator.SOIL_REGIONS)));
            listener.buildWaveMap();

      loadingProgress.begin(LoadingProgress.Stage.WINDOW);
      glWarmUp.join();
      SwingUtilities.invokeLater(() -> buildGameWindow(listener, camera, keyboardInput, worldNoise,
          seaLevelHeight, totalRegionWidth, physicalChunkSize, escapeQuitListener));
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

    settingsMenu = new SettingsMenu(() -> setSettingsOpen(false), this::shutdownGame);

    canvas.addGLEventListener(glEventListener);
    installControls();

    layeredPane.add(canvas, JLayeredPane.DEFAULT_LAYER);
    layeredPane.add(minimap, JLayeredPane.PALETTE_LAYER);
    layeredPane.add(gameHUD, JLayeredPane.MODAL_LAYER);
    layeredPane.add(settingsMenu, JLayeredPane.POPUP_LAYER);
    layoutComponents();

    animator = new FPSAnimator(canvas, 60);
    animator.start();
  }

  /** Called on the UI thread once the first frame of the round has been drawn; the player then chooses when to start. */
  private void onWorldReady() {
    // A HUD test run goes straight in
    if (System.getProperty("xenoguesser.hudtest") != null) enterGame();
    else loadingScreen.setWorldReady(this::enterGame);
  }

  private void enterGame() {
    loadingScreen.stop();
    layeredPane.remove(loadingScreen);
    layeredPane.repaint();
    canvas.requestFocus();
    inGame = true;
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
        if (firstPress) {
          switch (code) {
            case KeyEvent.VK_M: minimap.toggleSize(); break;
            case KeyEvent.VK_SPACE: glEventListener.jump(); break;
            case KeyEvent.VK_C:
              if (!minimap.isFullScreenRevealMode()) glEventListener.useCompass();
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

  private Point viewCentreOnScreen() {
    Point origin = canvas.getLocationOnScreen();
    return new Point(origin.x + canvas.getWidth() / 2, origin.y + canvas.getHeight() / 2);
  }

  /** Takes or gives back the mouse to suit what is on screen. */
  private void updateMouseMode() {
    if (canvas == null || !canvas.isShowing()) return;
    boolean pointerNeeded = settingsOpen || minimap.isLargeMap() || minimap.isFullScreenRevealMode();
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

  private void onMapChanged() {
    gameHUD.setVisible(minimap.isFullScreenRevealMode());
    updateMouseMode();
  }

  private void setSettingsOpen(boolean open) {
    settingsOpen = open;
    settingsMenu.setVisible(open);
    if (open) {
      keysDown.removeIf(k -> k != KeyEvent.VK_ESCAPE);
      keyboardInput.releaseAll();
    }
    glEventListener.setMenuOpen(open);
    updateMouseMode();
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
        default: break;
      }
    }
    if (test.contains("shot")) {
      javax.swing.Timer shot = new javax.swing.Timer(2600, e -> {
        try {
          Rectangle area = new Rectangle(getLocationOnScreen(), getSize());
          javax.imageio.ImageIO.write(new Robot().createScreenCapture(area), "png",
              new java.io.File(RunFiles.WORLD_DIR, "window_shot.png"));
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      });
      shot.setRepeats(false);
      shot.start();
    }
  }

  private void layoutComponents() {
    int paneWidth = layeredPane.getWidth();
    int paneHeight = layeredPane.getHeight();
    if (mainMenu != null) mainMenu.setBounds(0, 0, paneWidth, paneHeight);
    if (loadingScreen != null) loadingScreen.setBounds(0, 0, paneWidth, paneHeight);
    if (canvas == null) return;

    canvas.setBounds(0, 0, paneWidth - 2, paneHeight - 2);
    updateMinimapBounds(layeredPane, minimap);
    gameHUD.setBounds(HudStyle.HUD_MARGIN, HudStyle.HUD_MARGIN, gameHUD.getWidth(), gameHUD.getHeight());
    settingsMenu.setBounds((paneWidth - SettingsMenu.W) / 2, (paneHeight - SettingsMenu.H) / 2, SettingsMenu.W, SettingsMenu.H);
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
    layeredPane.revalidate();
    layeredPane.repaint();
  }

  public boolean getIsDebugModeActive() {
    return IS_DEBUG_MODE_ACTIVE;
  }
}
