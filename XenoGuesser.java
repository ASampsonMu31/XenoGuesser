import java.awt.*;
import java.awt.event.*;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JLayeredPane;
import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLCanvas; 
import com.jogamp.opengl.util.FPSAnimator;

public class XenoGuesser extends JFrame {
  
  private static final boolean IS_DEVELOPMENT_MODE = false; 

  private static final int WIDTH = 1024;
  private static final int HEIGHT = 768;
  private static final Dimension dimension = new Dimension(WIDTH, HEIGHT);
  private GLCanvas canvas; 
  private XenoGuesser_GLEventListener glEventListener;
  private final FPSAnimator animator;

  public static void main(String[] args) {
    XenoGuesser b1 = new XenoGuesser("XenoGuesser");
    b1.getContentPane().setPreferredSize(dimension);
    b1.pack();
    b1.setVisible(true);
    b1.canvas.requestFocusInWindow();
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
    
    this.getContentPane().setBackground(Color.BLACK);
    
    JLayeredPane layeredPane = new JLayeredPane();
    layeredPane.setBackground(Color.BLACK);
    this.setContentPane(layeredPane);
    
    GLCapabilities glcapabilities = new GLCapabilities(GLProfile.get(GLProfile.GL3));
    canvas = new GLCanvas(glcapabilities); 
    
    canvas.setSize(WIDTH, HEIGHT); 
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

    // Create HUD first, then minimap so they can connect seamlessly
    GameHUD gameHUD = new GameHUD();
    MapPanel minimap = new MapPanel(300, 300, totalRegionWidth, seaLevelHeight, worldNoise);
    
    // Connect the components
    minimap.setGameHUD(gameHUD);
    glEventListener.setMinimap(minimap);

    canvas.addGLEventListener(glEventListener);
    canvas.addMouseMotionListener(new MyMouseInput(camera, minimap)); 
    canvas.addKeyListener(keyboardInput);
    
    // Static layout sizing bounds for your new custom transparent Swing HUD
    gameHUD.setBounds(0, 0, gameHUD.getWidth(), gameHUD.getHeight());

    // Layering hierarchy setup to avoid overlay flickering bugs over raw JOGL buffers
    layeredPane.add(canvas, JLayeredPane.DEFAULT_LAYER);   // Depth 0
    layeredPane.add(minimap, JLayeredPane.PALETTE_LAYER);  // Depth 100
    layeredPane.add(gameHUD, JLayeredPane.MODAL_LAYER);    // Depth 200 (Highest - absolute front)

    layeredPane.addComponentListener(new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            canvas.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
            updateMinimapBounds(layeredPane, minimap);
            
            // Keeps the scoreboard safely anchored to top-left corner on resizing
            gameHUD.setBounds(0, 0, gameHUD.getWidth(), gameHUD.getHeight());
        }
    });
    
    addWindowListener(new WindowAdapter() {
      public void windowClosing(WindowEvent e) {
        animator.stop();
        remove(canvas);
        dispose();
        System.exit(0);
      }
    });
    animator = new FPSAnimator(canvas, 60);
    animator.start();
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
      // Inverted signs to create a classic panoramic pan drag behavior
      camera.updateYawPitch(-dx, dy);
    }
    lastpoint = ms;
  }

  @Override
  public void mouseMoved(MouseEvent e) {   
    lastpoint = e.getPoint(); 
  }
}