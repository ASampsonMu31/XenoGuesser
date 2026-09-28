import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Polygon;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.Dimension;
import com.xenoguesser.math.Vector3;

public class CompassHUD extends JPanel {
    
    private float currentYawRadians = 0.0f;
    
    private final int size = 120; // Internal size of the compass face area
    private static final int BORDER_SIZE = 10; // Frame thickness matching map panel
    
    // --- MATCHING THEMATIC THEME COLOUR TRIPLETS ---
    private final Color baseDarkGrey = new Color(45, 45, 45);
    private final Color highlightLightGrey = new Color(90, 90, 90);
    private final Color trayGrey = new Color(60, 60, 60); 

    public CompassHUD() {
        this.setOpaque(false); // Let custom paint control background geometry boundary blocks
        this.setVisible(false); // CRITICAL: Start completely hidden until round data loads in
        
        // Compute full component space constraints including surrounding border offsets
        int panelWidth = size + (BORDER_SIZE * 2);
        int panelHeight = size + (BORDER_SIZE * 2);
        this.setPreferredSize(new Dimension(panelWidth, panelHeight));
        this.setSize(panelWidth, panelHeight);
    }
    
    public void updateHeading(Vector3 cameraForwardDirection) {
        this.currentYawRadians = (float) Math.atan2(cameraForwardDirection.x, cameraForwardDirection.z);
        
        if (!this.isVisible()) {
            this.setVisible(true);
        }
        
        repaint();
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            int frameW = getWidth();
            int frameH = getHeight();
            
            // 1. Paint Solid Inner Tray Base Background Panel
            g2d.setColor(trayGrey);
            g2d.fillRect(0, 0, frameW, frameH);
            
            // 2. Render Outer Bevel Border Pieces using Linear Gradient Shading Frames
            float[] fractions = {0.0f, 0.5f, 1.0f};
            Color[] colours = {baseDarkGrey, highlightLightGrey, baseDarkGrey};
            
            // Top rim segment frame
            Polygon topFrame = new Polygon();
            topFrame.addPoint(0, 0); 
            topFrame.addPoint(frameW, 0); 
            topFrame.addPoint(frameW - BORDER_SIZE, BORDER_SIZE); 
            topFrame.addPoint(BORDER_SIZE, BORDER_SIZE);
            g2d.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(0, BORDER_SIZE), fractions, colours));
            g2d.fill(topFrame);

            // Bottom rim segment frame
            Polygon bottomFrame = new Polygon();
            bottomFrame.addPoint(BORDER_SIZE, frameH - BORDER_SIZE); 
            bottomFrame.addPoint(frameW - BORDER_SIZE, frameH - BORDER_SIZE); 
            bottomFrame.addPoint(frameW, frameH); 
            bottomFrame.addPoint(0, frameH);
            g2d.setPaint(new LinearGradientPaint(new Point2D.Float(0, frameH - BORDER_SIZE), new Point2D.Float(0, frameH), fractions, colours));
            g2d.fill(bottomFrame);

            // Left rim segment frame
            Polygon leftFrame = new Polygon();
            leftFrame.addPoint(0, 0); 
            leftFrame.addPoint(BORDER_SIZE, BORDER_SIZE); 
            leftFrame.addPoint(BORDER_SIZE, frameH - BORDER_SIZE); 
            leftFrame.addPoint(0, frameH);
            g2d.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(BORDER_SIZE, 0), fractions, colours));
            g2d.fill(leftFrame);

            // Right rim segment frame
            Polygon rightFrame = new Polygon();
            rightFrame.addPoint(frameW - BORDER_SIZE, BORDER_SIZE); 
            rightFrame.addPoint(frameW, 0); 
            rightFrame.addPoint(frameW, frameH); 
            rightFrame.addPoint(frameW - BORDER_SIZE, frameH - BORDER_SIZE);
            g2d.setPaint(new LinearGradientPaint(new Point2D.Float(frameW - BORDER_SIZE, 0), new Point2D.Float(frameW, 0), fractions, colours));
            g2d.fill(rightFrame);
            
            // 3. Render Circular Compass Graphics Centered inside Frame boundaries
            int centerX = frameW / 2;
            int centerY = frameH / 2;
            int radius = Math.min(size, size) / 2 - 5;
            
            // Inner instrument dial face background backing circle
            g2d.setColor(new Color(30, 30, 30)); 
            g2d.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
            
            g2d.setColor(new Color(110, 110, 110));
            g2d.setStroke(new BasicStroke(2.0f));
            g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
            
            // Prepare rotation translation transformation matrices context 
            g2d.translate(centerX, centerY);
            g2d.rotate(currentYawRadians);
            
            int pointerLength = (int)(radius * 0.80);
            int halfPointerBase = (int)(radius * 0.25);
            
            // Draw North Triangle pointer indicator (Red)
            int[] xPointsNorth = {0, -halfPointerBase, halfPointerBase};
            int[] yPointsNorth = {pointerLength, 0, 0}; 
            g2d.setColor(new Color(220, 50, 50)); 
            g2d.fillPolygon(xPointsNorth, yPointsNorth, 3);
            
            // Draw South Triangle pointer indicator (White)
            int[] xPointsSouth = {0, -halfPointerBase, halfPointerBase};
            int[] yPointsSouth = {-pointerLength, 0, 0}; 
            g2d.setColor(new Color(240, 240, 240)); 
            g2d.fillPolygon(xPointsSouth, yPointsSouth, 3);
            
            // Center core pivot axis pin detail
            g2d.setColor(new Color(150, 150, 150));
            g2d.fillOval(-4, -4, 8, 8);
            
        } finally {
            g2d.dispose();
        }
    }
}