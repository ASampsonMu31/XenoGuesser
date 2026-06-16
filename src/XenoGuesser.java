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
   
  private static final boolean IS_DETERMINISTIC_MODE = false; // Set to true to test seed consistency
  private static final boolean IS_DEBUG_MODE_ACTIVE = false;

  private GLCanvas canvas; 
  private XenoGuesser_GLEventListener glEventListener;
  private final FPSAnimator animator;

  private boolean isWindowLocked = false;
  private Point lockedWindowPosition = null;
  private Point permanentWindowPosition = null;

  private CompassHUD compassHUD;

  public static void main(String[] args) {
    SwingUtilities.invokeLater(new Runnable() {
        @Override
        public void run() {
            XenoGuesser b1 = new XenoGuesser("XenoGuesser");
            b1.setVisible(true);
            b1.canvas.requestFocus(); 
        }
    });
  }

  public XenoGuesser(String textForTitleBar) {
    super(textForTitleBar);

    // 1. Establish the World Seed Early for Deterministic Generation
    long worldSeed = IS_DETERMINISTIC_MODE ? 123L : System.currentTimeMillis();

    String modelPath = "models/cvae_generator.pt";
    String baseOutputDir = "assets/textures/generated_alphabets";

    // 2. Procedural Systemic Multi-Glyph Generation
    try (GlyphGenerator generator = new GlyphGenerator(modelPath)) {
        
        int totalDatasetAlphabets = 30; // Matches Omniglot training bounds
        
        generator.generateAllSystems(totalDatasetAlphabets, baseOutputDir, worldSeed);

    } catch (Exception e) {
        System.err.println("CRITICAL ERROR: Failed to generate writing systems.");
        e.printStackTrace();
    }
    
    // 3. Setup Window Configurations
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
    
    JLayeredPane layeredPane = new JLayeredPane();
    layeredPane.setOpaque(false); 
    this.setContentPane(layeredPane);
    
    GLCapabilities glcapabilities = new GLCapabilities(GLProfile.get(GLProfile.GL3));
    canvas = new GLCanvas(glcapabilities); 
    canvas.setBackground(Color.BLACK); 
    
    System.setProperty("sun.awt.noerasebackground", "true"); 
    System.setProperty("sun.java2d.noddraw", "true");
    
    // 4. Use the established seed for the World Engine
    PerlinNoise worldNoise = new PerlinNoise(worldSeed);
    
    float physicalChunkSize = 100.0f;
    float totalRegionWidth = 150_000f;
    
    float seaLevelHeight = XenoGuesser_GLEventListener.precalculateSeaLevel(worldSeed, totalRegionWidth, worldNoise);
    
    Camera camera = new Camera(Camera.DEFAULT_POSITION, Camera.DEFAULT_TARGET, Camera.DEFAULT_UP, seaLevelHeight);
    MyKeyboardInput keyboardInput = new MyKeyboardInput(); 
    
    physicalChunkSize = 100f;

    glEventListener = new XenoGuesser_GLEventListener(
      camera,
      keyboardInput,
      worldNoise,
      seaLevelHeight,
      worldSeed,
      totalRegionWidth,
      physicalChunkSize,
      IS_DEBUG_MODE_ACTIVE
    );

    GameHUD gameHUD = new GameHUD();
    glEventListener.setGameHUD(gameHUD);
    
    compassHUD = new CompassHUD();

    MapPanel minimap = new MapPanel(
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
    
    int initialCompassW = compassHUD.getPreferredSize().width;
    int initialCompassH = compassHUD.getPreferredSize().height;
    compassHUD.setBounds(0, screenSize.height - initialCompassH - 2, initialCompassW, initialCompassH);
    
    layeredPane.add(compassHUD, JLayeredPane.MODAL_LAYER);
    glEventListener.setCompassHUD(compassHUD);

    canvas.addGLEventListener(glEventListener);
    canvas.addMouseMotionListener(new MyMouseInput(camera, minimap)); 
    canvas.addKeyListener(keyboardInput);
    
    KeyAdapter escapeQuitListener = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                shutdownGame();
            }
        }
    };
    canvas.addKeyListener(escapeQuitListener);
    this.addKeyListener(escapeQuitListener);
    
    gameHUD.setBounds(0, 0, gameHUD.getWidth(), gameHUD.getHeight());

    layeredPane.add(canvas, JLayeredPane.DEFAULT_LAYER);   
    layeredPane.add(minimap, JLayeredPane.PALETTE_LAYER);  
    layeredPane.add(gameHUD, JLayeredPane.MODAL_LAYER);       

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
          int paneWidth = layeredPane.getWidth();
          int paneHeight = layeredPane.getHeight();
          
          canvas.setBounds(0, 0, paneWidth - 2, paneHeight - 2);
          updateMinimapBounds(layeredPane, minimap);
          gameHUD.setBounds(0, 0, gameHUD.getWidth(), gameHUD.getHeight());
          
          int compassW = compassHUD.getPreferredSize().width;
          int compassH = compassHUD.getPreferredSize().height;
          compassHUD.setBounds(0, paneHeight - compassH - 2, compassW, compassH);
          
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

    animator = new FPSAnimator(canvas, 60);
    animator.start();
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
                    remove(canvas);
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