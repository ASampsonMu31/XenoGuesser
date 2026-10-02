import java.awt.*;
import java.awt.event.*;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
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

  private CompassHUD compassHUD;

  private final long worldSeed;
  private final LoadingProgress loadingProgress = new LoadingProgress();
  private LoadingScreen loadingScreen;
  private JLayeredPane layeredPane;
  private MapPanel minimap;
  private GameHUD gameHUD;

  public static void main(String[] args) {
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

    // 3. The loading screen covers everything from launch until the round's first frame
    loadingScreen = new LoadingScreen(loadingProgress, worldSeed);
    layeredPane.add(loadingScreen, JLayeredPane.DRAG_LAYER);

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
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                shutdownGame();
            }
        }
    };
    this.addKeyListener(escapeQuitListener);

    // 4. Everything that doesn't need the GL context is generated off the UI thread
    Thread worldGeneration = new Thread(() -> generateWorld(escapeQuitListener), "world-generation");
    worldGeneration.setDaemon(true);
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
          generator.generateAllSystems(totalDatasetAlphabets, "assets/textures/generated_alphabets", worldSeed);
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
    canvas = new GLCanvas(glcapabilities); 
    canvas.setBackground(Color.BLACK); 

    gameHUD = new GameHUD();
    glEventListener.setGameHUD(gameHUD);
    
    compassHUD = new CompassHUD();

    minimap = new MapPanel(
      750,
      750,
      totalRegionWidth,
      seaLevelHeight,
      worldNoise,
      physicalChunkSize,
      glEventListener,
      compassHUD
    );

    minimap.setMainApp(this);
    minimap.setGameHUD(gameHUD);
    glEventListener.setMinimap(minimap);
    glEventListener.setCompassHUD(compassHUD);
    glEventListener.setLoading(loadingProgress, this::onWorldReady);

    canvas.addGLEventListener(glEventListener);
    canvas.addMouseMotionListener(new MyMouseInput(camera, minimap)); 
    canvas.addKeyListener(keyboardInput);
    canvas.addKeyListener(escapeQuitListener);

    layeredPane.add(canvas, JLayeredPane.DEFAULT_LAYER);   
    layeredPane.add(minimap, JLayeredPane.PALETTE_LAYER);  
    layeredPane.add(gameHUD, JLayeredPane.MODAL_LAYER);       
    layeredPane.add(compassHUD, JLayeredPane.MODAL_LAYER);
    layoutComponents();

    animator = new FPSAnimator(canvas, 60);
    animator.start();
  }

  /** Called on the UI thread once the first frame of the round has been drawn. */
  private void onWorldReady() {
    loadingScreen.stop();
    layeredPane.remove(loadingScreen);
    layeredPane.repaint();
    canvas.requestFocus();
  }

  private void layoutComponents() {
    int paneWidth = layeredPane.getWidth();
    int paneHeight = layeredPane.getHeight();
    loadingScreen.setBounds(0, 0, paneWidth, paneHeight);
    if (canvas == null) return;

    canvas.setBounds(0, 0, paneWidth - 2, paneHeight - 2);
    updateMinimapBounds(layeredPane, minimap);
    gameHUD.setBounds(0, 0, gameHUD.getWidth(), gameHUD.getHeight());
    
    int compassW = compassHUD.getPreferredSize().width;
    int compassH = compassHUD.getPreferredSize().height;
    compassHUD.setBounds(0, paneHeight - compassH - 2, compassW, compassH);
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

  class MyMouseInput extends MouseMotionAdapter {
    private Point lastpoint;
    private Camera camera;
    private MapPanel minimap; 
    
    public MyMouseInput(Camera camera, MapPanel minimap) {
        this.camera = camera;
        this.minimap = minimap;
    }
        
    @Override
    public void mouseDragged(MouseEvent e) {
        Point ms = e.getPoint();
        if (minimap != null && minimap.isFullScreenRevealMode()) {
            lastpoint = ms; 
            return; 
        }
        
        float sensitivity = 0.001f;
        float dx = (float) (ms.x - lastpoint.x) * sensitivity;
        float dy = (float) (ms.y - lastpoint.y) * sensitivity;
        
        if (e.getModifiersEx() == MouseEvent.BUTTON1_DOWN_MASK) {
            camera.updateYawPitch(-dx, dy);
        }
        lastpoint = ms;
    }

    @Override
    public void mouseMoved(MouseEvent e) {   
        lastpoint = e.getPoint(); 
    }
  }
}
