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
  
  private static final boolean IS_DEVELOPMENT_MODE = false; 

  private GLCanvas canvas; 
  private XenoGuesser_GLEventListener glEventListener;
  private final FPSAnimator animator;

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

  public XenoGuesser(String textForTitleBar) {
    super(textForTitleBar);
    
    // FIX 1: Retain normal window decorations so the OS compositor handles focus and alt-tabs safely.
    this.setResizable(true);
    
    // FIX 2: Maximize the frame using standard OS properties to scale seamlessly up to your screen size.
    this.setExtendedState(JFrame.MAXIMIZED_BOTH);
    
    this.getContentPane().setBackground(Color.BLACK);
    
    JLayeredPane layeredPane = new JLayeredPane();
    layeredPane.setBackground(Color.BLACK);
    this.setContentPane(layeredPane);
    
    GLCapabilities glcapabilities = new GLCapabilities(GLProfile.get(GLProfile.GL3));
    
    // Reverted completely to your original working native hardware canvas
    canvas = new GLCanvas(glcapabilities); 
    
    System.setProperty("sun.awt.noerasebackground", "true"); 
    
    Camera camera = new Camera(Camera.DEFAULT_POSITION, Camera.DEFAULT_TARGET, Camera.DEFAULT_UP);
    MyKeyboardInput keyboardInput = new MyKeyboardInput(); 
    
    long worldSeed = IS_DEVELOPMENT_MODE ? 123L : System.currentTimeMillis();
    PerlinNoise worldNoise = new PerlinNoise(worldSeed);
    
    int viewDistance = 24;
    float physicalChunkSize = 100.0f;
    float totalRegionWidth = (viewDistance * physicalChunkSize) * 50.0f;
    
    float seaLevelHeight = XenoGuesser_GLEventListener.precalculateSeaLevel(worldSeed, totalRegionWidth, worldNoise);
    
    glEventListener = new XenoGuesser_GLEventListener(camera, keyboardInput, worldNoise, seaLevelHeight, worldSeed);

    GameHUD gameHUD = new GameHUD();
    MapPanel minimap = new MapPanel(300, 300, totalRegionWidth, seaLevelHeight, worldNoise);
    
    minimap.setGameHUD(gameHUD);
    glEventListener.setMinimap(minimap);

    canvas.addGLEventListener(glEventListener);
    canvas.addMouseMotionListener(new MyMouseInput(camera, minimap)); 
    canvas.addKeyListener(keyboardInput);
    
    // FIX 3: Bind the exit listener to BOTH the canvas and frame to catch the Escape key flawlessly
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

    // Dynamically captures your screen size changes and updates the dimensions of everything
    layeredPane.addComponentListener(new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            canvas.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
            updateMinimapBounds(layeredPane, minimap);
            gameHUD.setBounds(0, 0, gameHUD.getWidth(), gameHUD.getHeight());
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