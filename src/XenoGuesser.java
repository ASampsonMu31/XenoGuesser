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
  
  private static final boolean IS_DEVELOPMENT_MODE = true; 

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
    
    Camera camera = new Camera(Camera.DEFAULT_POSITION, Camera.DEFAULT_TARGET, Camera.DEFAULT_UP, IS_DEVELOPMENT_MODE);
    MyKeyboardInput keyboardInput = new MyKeyboardInput(); 
    
    long worldSeed = IS_DEVELOPMENT_MODE ? 123L : System.currentTimeMillis();
    PerlinNoise worldNoise = new PerlinNoise(worldSeed);
    System.out.println("World Seed Context: " + worldSeed);
    
    int viewDistance = 24;
    float physicalChunkSize = 100.0f;
    float totalRegionWidth = (viewDistance * physicalChunkSize) * 50.0f;
    
    float seaLevelHeight = XenoGuesser_GLEventListener.precalculateSeaLevel(worldSeed, totalRegionWidth, worldNoise);
    
    glEventListener = new XenoGuesser_GLEventListener(camera, keyboardInput, worldNoise, seaLevelHeight, worldSeed);

    GameHUD gameHUD = new GameHUD();
    glEventListener.setGameHUD(gameHUD); 

    MapPanel minimap = new MapPanel(750, 750, totalRegionWidth, seaLevelHeight, worldNoise);
    minimap.setMainApp(this);
    minimap.setGameHUD(gameHUD);
    glEventListener.setMinimap(minimap);

    compassHUD = new CompassHUD();
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
          
          // FIXED: Squeezes the framed compass absolutely into the bottom left corner (x=0)
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