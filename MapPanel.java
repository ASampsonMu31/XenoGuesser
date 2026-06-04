import javax.swing.JPanel;
import javax.swing.JLayeredPane;
import javax.swing.border.EmptyBorder;
import javax.swing.Timer;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.Stroke;
import java.awt.FontMetrics;

public class MapPanel extends JPanel {
    private BufferedImage mapImage;
    private static final int BORDER_SIZE = 10;       // Frame thickness
    private static final int EXTRA_BOTTOM_SPACE = 55; // Space allocated for buttons
    
    // Pushes internal graphics assets to clear layout discrepancies
    private static final int HORIZONTAL_SHUFFLE_OFFSET = 2; 

    private final Color baseDarkGrey = new Color(45, 45, 45);
    private final Color highlightLightGrey = new Color(90, 90, 90);
    private final Color trayGrey = new Color(60, 60, 60); 

    // --- BUTTON COLORS ---
    private final Color btnDisabledGrey = new Color(90, 92, 95);
    private final Color btnEnabledGreen = new Color(50, 165, 50);
    private final Color btnNextRoundBlue = new Color(40, 120, 210); 
    private final Color btnTextWhite = new Color(255, 255, 255);

    // --- WORLD DATA TRACKING ---
    private float totalRegionWidth;
    private float halfRegion;

    // --- GAME END STATE VARIABLES ---
    private boolean isGuessed = false;
    private int goalX; 
    private int goalY; 

    // --- PIN STATE VARIABLES ---
    private boolean hasPin = false;
    private int pinX; 
    private int pinY; 

    // --- BUTTON BOUNDS TRACKING ---
    private int btnX;
    private int btnY;
    private int btnWidth;
    private int btnHeight;

    // --- HOVER & FULLSCREEN REVEAL TRACKING ---
    private boolean isHovered = false;
    private boolean isFullScreenReveal = false; 
    private int currentMapSize = 150; 

    // --- TIMED CINEMATIC SEQUENCE STATES ---
    private enum RevealPhase {
        SHOW_PLAYER_PIN,
        SHOW_ALL_RESULTS
    }
    private RevealPhase currentPhase = RevealPhase.SHOW_PLAYER_PIN;
    private Timer revealTimer;
    
    // --- GRADUAL LINE REVEAL TRACKING ---
    private float lineProgress = 0.0f; 


    private boolean nextRoundRequested = false;

    private GameHUD gameHUD;

    public MapPanel(int maxMapWidth, int maxMapHeight, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        this.totalRegionWidth = totalRegionWidth;
        this.halfRegion = totalRegionWidth / 2.0f;
        this.mapImage = new BufferedImage(maxMapWidth, maxMapHeight, BufferedImage.TYPE_INT_RGB);

        this.setOpaque(false);

        for (int z = 0; z < maxMapHeight; z++) {
            for (int x = 0; x < maxMapWidth; x++) {
                float worldX = ((float) x / maxMapWidth) * totalRegionWidth - halfRegion;
                float worldZ = ((float) z / maxMapHeight) * totalRegionWidth - halfRegion;

                float terrainHeight = TerrainMesh.getLayeredHeight(worldX, worldZ, noise);

                if (terrainHeight > seaLevelHeight) {
                    mapImage.setRGB(x, z, new Color(92, 64, 45).getRGB());  
                } else {
                    mapImage.setRGB(x, z, new Color(25, 80, 160).getRGB()); 
                }
            }
        }

        updateGeometryLayouts();

        this.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int clickX = e.getX();
                int clickY = e.getY();

                if (isFullScreenReveal && currentPhase == RevealPhase.SHOW_ALL_RESULTS) {
                    if (clickX >= btnX && clickX <= (btnX + btnWidth) && clickY >= btnY && clickY <= (btnY + btnHeight)) {
                        System.out.println("Next Round triggered!");
                        
                        // --- HOOK 2: ADVANCE CURRENT ROUND ---
                        if (MapPanel.this.gameHUD != null) {
                            MapPanel.this.gameHUD.advanceRound();
                        }
                        // -------------------------------------
                        
                        MapPanel.this.nextRoundRequested = true; 
                        return;
                    }
                }

                if (isGuessed) return;

                int mapLeft = BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET;
                int mapTop = BORDER_SIZE;
                int mapRight = mapLeft + currentMapSize;
                int mapBottom = mapTop + currentMapSize;

                if (clickX >= mapLeft && clickX < mapRight && clickY >= mapTop && clickY < mapBottom) {
                    int localizedX = clickX - (BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET);
                    int localizedY = clickY - BORDER_SIZE;
                    float scaleToCore = (float) mapImage.getWidth() / currentMapSize;
                    
                    pinX = (int) (localizedX * scaleToCore);
                    pinY = (int) (localizedY * scaleToCore);
                    hasPin = true;
                    repaint(); 
                }
                else if (isHovered && clickX >= btnX && clickX <= (btnX + btnWidth) && clickY >= btnY && clickY <= (btnY + btnHeight)) {
                    if (hasPin) {
                        startRevealSequence();
                    }
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                if (!isHovered && !isFullScreenReveal) {
                    isHovered = true;
                    currentMapSize = 300;
                    updateGeometryLayouts();
                    triggerParentLayoutUpdate();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (isGuessed || isFullScreenReveal) return;

                java.awt.Point mousePos = getMousePosition();
                if (mousePos != null && mousePos.x >= 0 && mousePos.x < getWidth() && mousePos.y >= 0 && mousePos.y < getHeight()) {
                    return; 
                }

                if (isHovered) {
                    isHovered = false;
                    currentMapSize = 150;
                    updateGeometryLayouts();
                    triggerParentLayoutUpdate();
                }
            }
        });
    }

    private void startRevealSequence() {
        this.isGuessed = true;
        this.isFullScreenReveal = true;
        this.isHovered = false;
        this.currentPhase = RevealPhase.SHOW_PLAYER_PIN; 
        this.lineProgress = 0.0f;

        if (getParent() != null) {
            int parentHeight = getParent().getHeight();
            this.currentMapSize = Math.max(400, parentHeight - 120); 
        } else {
            this.currentMapSize = 600; 
        }

        updateGeometryLayouts();
        triggerParentLayoutUpdate();

        final float lineRevealSpeed = 0.04f; 

        revealTimer = new Timer(30, e -> {
            if (currentPhase == RevealPhase.SHOW_PLAYER_PIN) {
                lineProgress += lineRevealSpeed;
                if (lineProgress >= 1.0f) {
                    lineProgress = 1.0f;
                    currentPhase = RevealPhase.SHOW_ALL_RESULTS;
                    revealTimer.stop(); 
                    
                    // --- HOOK 1: COMPUTE AND DISPATCH SCORE ---
                    calculateAndApplyScore();
                    // ------------------------------------------
                    
                    updateGeometryLayouts();
                    triggerParentLayoutUpdate();
                }
            }
            repaint();
        });
        revealTimer.start();
    }

    public boolean isFullScreenRevealMode() {
        return this.isFullScreenReveal; 
    }

    public void setPlayerSpawnLocation(float spawnX, float spawnZ) {
        this.goalX = (int) (((spawnX + halfRegion) / totalRegionWidth) * mapImage.getWidth());
        this.goalY = (int) (((spawnZ + halfRegion) / totalRegionWidth) * mapImage.getHeight());
    }

    public void updateGeometryLayouts() {
        boolean needsExtraSpace = (isHovered && !isFullScreenReveal) || isFullScreenReveal;
        int bottomSpace = needsExtraSpace ? EXTRA_BOTTOM_SPACE : 0;
        
        this.setPreferredSize(new Dimension(currentMapSize + (BORDER_SIZE * 2) + HORIZONTAL_SHUFFLE_OFFSET + 2, currentMapSize + (BORDER_SIZE * 2) + bottomSpace + 1));
        this.setBorder(new EmptyBorder(BORDER_SIZE, BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET, BORDER_SIZE + bottomSpace, BORDER_SIZE));

        int totalWidth = currentMapSize + (BORDER_SIZE * 2) + HORIZONTAL_SHUFFLE_OFFSET;
        this.btnWidth = totalWidth - 24;
        this.btnHeight = 40;
        this.btnX = (totalWidth - btnWidth) / 2;
        this.btnY = currentMapSize + (BORDER_SIZE * 2) + 7;
    }

    private void triggerParentLayoutUpdate() {
        if (getParent() instanceof JLayeredPane) {
            JLayeredPane layeredPane = (JLayeredPane) getParent();
            java.awt.Rectangle oldBounds = this.getBounds();
            XenoGuessr.updateMinimapBounds(layeredPane, this);
            layeredPane.repaint(oldBounds);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create(); 
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int frameW = getWidth();
            int frameH = getHeight();

            g2d.setBackground(new Color(0, 0, 0, 0));
            g2d.clearRect(0, 0, frameW, frameH);
            g2d.setClip(0, 0, frameW, frameH);

            g2d.setColor(trayGrey);
            g2d.fillRect(0, 0, frameW, frameH);

            float[] fractions = {0.0f, 0.5f, 1.0f};
            Color[] colors = {baseDarkGrey, highlightLightGrey, baseDarkGrey};

            // --- 1. FRAMES ---
            int mapFrameH = currentMapSize + (BORDER_SIZE * 2);
            int mapFrameW = currentMapSize + (BORDER_SIZE * 2) + HORIZONTAL_SHUFFLE_OFFSET;
            int startX = HORIZONTAL_SHUFFLE_OFFSET;
            
            Polygon topFrame = new Polygon();
            topFrame.addPoint(startX, 0); topFrame.addPoint(mapFrameW + 2, 0); topFrame.addPoint(mapFrameW - BORDER_SIZE + 2, BORDER_SIZE); topFrame.addPoint(BORDER_SIZE + startX, BORDER_SIZE);
            g2d.setPaint(new LinearGradientPaint(new Point2D.Float(startX, 0), new Point2D.Float(startX, BORDER_SIZE), fractions, colors));
            g2d.fill(topFrame);

            Polygon bottomFrame = new Polygon();
            bottomFrame.addPoint(BORDER_SIZE + startX, mapFrameH - BORDER_SIZE); bottomFrame.addPoint(mapFrameW - BORDER_SIZE + 2, mapFrameH - BORDER_SIZE); bottomFrame.addPoint(mapFrameW + 2, mapFrameH); bottomFrame.addPoint(startX, mapFrameH);
            g2d.setPaint(new LinearGradientPaint(new Point2D.Float(startX, mapFrameH - BORDER_SIZE), new Point2D.Float(startX, mapFrameH), fractions, colors));
            g2d.fill(bottomFrame);

            Polygon leftFrame = new Polygon();
            leftFrame.addPoint(startX, 0); leftFrame.addPoint(BORDER_SIZE + startX, BORDER_SIZE); leftFrame.addPoint(BORDER_SIZE + startX, mapFrameH - BORDER_SIZE); leftFrame.addPoint(startX, mapFrameH);
            g2d.setPaint(new LinearGradientPaint(new Point2D.Float(startX, 0), new Point2D.Float(BORDER_SIZE + startX, 0), fractions, colors));
            g2d.fill(leftFrame);

            Polygon rightFrame = new Polygon();
            rightFrame.addPoint(mapFrameW - BORDER_SIZE, BORDER_SIZE); rightFrame.addPoint(mapFrameW + 2, 0); rightFrame.addPoint(mapFrameW + 2, mapFrameH); rightFrame.addPoint(mapFrameW - BORDER_SIZE, mapFrameH - BORDER_SIZE);
            g2d.setPaint(new LinearGradientPaint(new Point2D.Float(mapFrameW - BORDER_SIZE, 0), new Point2D.Float(mapFrameW + 2, 0), fractions, colors));
            g2d.fill(rightFrame);

            // --- 2. DRAW MAP IMAGE ---
            Insets insets = getInsets();
            g2d.drawImage(mapImage, insets.left, insets.top, currentMapSize, currentMapSize, null);

            // --- 3. DRAW CUSTOM GUESS BUTTON ---
            if (isHovered && !isFullScreenReveal) {
                g2d.setColor(hasPin ? btnEnabledGreen : btnDisabledGrey);
                g2d.fillRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight); 

                g2d.setColor(new Color(255, 255, 255, 50));
                g2d.setStroke(new BasicStroke(1.2f));
                g2d.drawRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight);

                g2d.setColor(btnTextWhite);
                g2d.setFont(g2d.getFont().deriveFont(java.awt.Font.BOLD, 22f));
                FontMetrics fm = g2d.getFontMetrics();
                String btnText = "Guess";
                int stringWidth = fm.stringWidth(btnText);
                int stringHeight = fm.getAscent();
                int textX = btnX + (btnWidth - stringWidth) / 2;
                int textY = btnY + (btnHeight + stringHeight) / 2 - 2; 
                g2d.drawString(btnText, textX, textY);
            }

            // --- 4. RENDER TIMED MAP RESULTS ---
            if (hasPin) {
                Stroke originalStroke = g2d.getStroke();

                float scaleFromCore = (float) currentMapSize / mapImage.getWidth();
                int displayPinX = BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET + (int) (pinX * scaleFromCore);
                int displayPinY = BORDER_SIZE + (int) (pinY * scaleFromCore);

                int displayGoalX = BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET + (int) (goalX * scaleFromCore);
                int displayGoalY = BORDER_SIZE + (int) (goalY * scaleFromCore);

                // --- DOTTED LINE ---
                if (isFullScreenReveal) {
                    int targetLineX = displayPinX + (int) ((displayGoalX - displayPinX) * lineProgress);
                    int targetLineY = displayPinY + (int) ((displayGoalY - displayPinY) * lineProgress);

                    g2d.setColor(Color.WHITE);
                    Stroke dottedStroke = new BasicStroke(3.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{6.0f, 6.0f}, 0.0f);
                    g2d.setStroke(dottedStroke);
                    g2d.drawLine(displayPinX, displayPinY, targetLineX, targetLineY);
                    g2d.setStroke(originalStroke);
                }

                // --- RED PLAYER PIN ---
                int pinLength = 16;       
                int headDiameter = 12;    
                int headRadius = headDiameter / 2;

                g2d.setColor(new Color(180, 182, 185)); 
                g2d.setStroke(new BasicStroke(2.0f));
                g2d.drawLine(displayPinX, displayPinY, displayPinX, displayPinY - pinLength);

                g2d.setStroke(originalStroke);
                int circleX = displayPinX - headRadius;
                int circleY = (displayPinY - pinLength) - headRadius;

                g2d.setColor(new Color(200, 40, 40)); 
                g2d.fillOval(circleX, circleY, headDiameter, headDiameter);
                g2d.setColor(new Color(30, 30, 30));
                g2d.drawOval(circleX, circleY, headDiameter, headDiameter);

                // --- GOAL FLAG ---
                if (isFullScreenReveal && currentPhase == RevealPhase.SHOW_ALL_RESULTS) {
                    int flagPoleLength = 22;

                    g2d.setColor(Color.LIGHT_GRAY);
                    g2d.setStroke(new BasicStroke(2.5f));
                    g2d.drawLine(displayGoalX, displayGoalY, displayGoalX, displayGoalY - flagPoleLength);
                    g2d.setStroke(originalStroke);

                    Polygon flagPoly = new Polygon();
                    int flagTopY = displayGoalY - flagPoleLength;
                    flagPoly.addPoint(displayGoalX, flagTopY);
                    flagPoly.addPoint(displayGoalX + 16, flagTopY + 6); 
                    flagPoly.addPoint(displayGoalX, flagTopY + 12);

                    g2d.setColor(new Color(45, 180, 45)); 
                    g2d.fill(flagPoly);
                    g2d.setColor(Color.BLACK);
                    g2d.draw(flagPoly);
                }

                // --- NEXT ROUND BUTTON ---
                if (isFullScreenReveal) {
                    boolean resultsReady = (currentPhase == RevealPhase.SHOW_ALL_RESULTS);
                    
                    g2d.setColor(resultsReady ? btnNextRoundBlue : btnDisabledGrey);
                    g2d.fillRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight);

                    g2d.setColor(new Color(255, 255, 255, resultsReady ? 60 : 30));
                    g2d.setStroke(new BasicStroke(1.5f));
                    g2d.drawRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight);
                    g2d.setStroke(originalStroke);

                    g2d.setColor(btnTextWhite);
                    g2d.setFont(g2d.getFont().deriveFont(java.awt.Font.BOLD, 20f));
                    FontMetrics fm = g2d.getFontMetrics();
                    String endText = "Next Round";
                    int stringWidth = fm.stringWidth(endText);
                    int stringHeight = fm.getAscent();
                    int textX = btnX + (btnWidth - stringWidth) / 2;
                    int textY = btnY + (btnHeight + stringHeight) / 2 - 2;
                    g2d.drawString(endText, textX, textY);
                }
            }
        } finally {
            g2d.dispose(); 
        }
    }

    public void resetMapState() {
        this.isGuessed = false;
        this.isFullScreenReveal = false;
        this.isHovered = false;
        this.hasPin = false;
        this.currentMapSize = 150; 
        this.lineProgress = 0.0f;
        this.currentPhase = RevealPhase.SHOW_PLAYER_PIN;
        
        updateGeometryLayouts();
        triggerParentLayoutUpdate();
        repaint();
    }

    public boolean isNextRoundRequested() {
        return nextRoundRequested;
    }

    public void clearNextRoundRequest() {
        this.nextRoundRequested = false;
    }

    private void calculateAndApplyScore() {
        // Determine raw pixel error gap
        double deltaX = pinX - goalX;
        double deltaY = pinY - goalY;
        double pixelDistance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        // Map pixel gap to world coordinate metrics
        float mapWidthPixels = mapImage.getWidth();
        double realWorldDistance = (pixelDistance / mapWidthPixels) * totalRegionWidth;

        // Apply GeoGuessr Exponential Decay Formula with an optimized casual divisor (5.0f)
        double maxDiagonalDistance = Math.sqrt(2.0) * totalRegionWidth;
        double kConstant = maxDiagonalDistance / 14.0f; 
        int score = (int) Math.round(5000.0 * Math.exp(-realWorldDistance / kConstant));
        
        if (score < 0) score = 0;
        if (score > 5000) score = 5000;

        // Send the score straight to your persistent overlay element
        if (this.gameHUD != null) {
            this.gameHUD.addScore(score);
        }

        System.out.println("Round Completed! Distance: " + realWorldDistance + " | Score Awarded: " + score);
    }

    public void setGameHUD(GameHUD hud) {
        this.gameHUD = hud;
    }
}