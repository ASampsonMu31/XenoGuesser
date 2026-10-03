import javax.swing.JPanel;
import javax.swing.JLayeredPane;
import javax.swing.border.EmptyBorder;
import javax.swing.Timer;
import javax.swing.JTextArea;
import javax.swing.JButton; 
import javax.swing.BorderFactory;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DocumentFilter;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.SwingUtilities;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.AlphaComposite;
import java.awt.geom.Point2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.Stroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.List;
import java.util.ArrayList;

public class MapPanel extends JPanel {
    
    private class MapNote {
        int coreX;
        int coreY;
        String text;
        boolean isExpanded;

        public MapNote(int coreX, int coreY, String text) {
            this.coreX = coreX;
            this.coreY = coreY;
            this.text = text;
            this.isExpanded = false;
        }
    }

    private BufferedImage mapImage;
    // Fully composed map shown in place of the plain map while an overlay is active
    private volatile BufferedImage overlayImage;
    // Optional higher-resolution versions used once the map is zoomed in
    private volatile BufferedImage overlayDetail;
    private volatile BufferedImage baseMapDetail;

    // Scroll-wheel zoom of the enlarged map: magnification and the visible centre in 0..1 map space
    private static final float MAX_ZOOM = 8.0f;
    private static final float ZOOM_STEP = 1.25f;
    private static final float DETAIL_SWITCH_ZOOM = 1.3f;
    private float zoom = 1.0f;
    private float viewCentreU = 0.5f;
    private float viewCentreV = 0.5f;
private volatile boolean showHeatmap = false;   
    
    private static final int BORDER_SIZE = 10;       
    private static final int EXTRA_BOTTOM_SPACE = 55; 
    private static final int HORIZONTAL_SHUFFLE_OFFSET = 2; 

    private final Color baseDarkGrey = new Color(45, 45, 45);
    private final Color highlightLightGrey = new Color(90, 90, 90);
    private final Color trayGrey = new Color(60, 60, 60); 

    private final Color btnDisabledGrey = new Color(90, 92, 95);
    private final Color btnEnabledGreen = new Color(50, 165, 50);
    private final Color btnNextRoundBlue = new Color(40, 120, 210); 
    private final Color btnTextWhite = new Color(255, 255, 255);

    private float totalRegionWidth;
    private float halfRegion;

    private volatile boolean isGuessed = false;
    // The next round's answer, waiting for the results to close
    private int[] pendingGoal;
    private int goalX; 
    private int goalY; 

    private boolean hasPin = false;
    private int pinX; 
    private int pinY; 

    private int btnX;
    private int btnY;
    private int btnWidth;
    private int btnHeight;

    private int noteBtnX;
    private int noteBtnY;
    private int noteBtnWidth;
    private int noteBtnHeight;
    private boolean isTypingNote = false;
    private JTextArea noteArea;
    private JButton noteEnterBtn; 
    
    private static final int NOTE_CHARACTER_LIMIT = 112; 

    private List<MapNote> savedNotes = new ArrayList<>();

    // The map is small or large, switched with M; only the large map takes the mouse
    public enum MapSize { SMALL, LARGE }
    private MapSize mapSize = MapSize.SMALL;
    private boolean isLarge = false;
    private Runnable onSizeChanged;
    private boolean isFullScreenReveal = false; 
    private int currentMapSize = 150; 

    private int targetRoundScore = 0;
    private int currentDisplayScore = 0;
    private boolean shouldDrawScoreText = false;
    
    private long phaseStartTime = 0;
    private float slamProgress = 0.0f;
    private float currentScoreScale = 1.0f;
    
    private static final long LINGER_DURATION_MS = 500; 
    private static final long SLAM_DURATION_MS = 250;   

    private int visualMapX = 0;
    private int visualMapY = 0;

    private enum RevealPhase {
        SHOW_PLAYER_PIN,
        SHOW_ALL_RESULTS,
        LINGER,
        SLAM_TO_HUD
    }
    private RevealPhase currentPhase = RevealPhase.SHOW_PLAYER_PIN;
    private Timer revealTimer;
    
    private float lineProgress = 0.0f; 

        private boolean nextRoundRequested = false;
    // While the next round is being built the button turns pale, says Loading and shows a spinner
    private boolean roundLoading = false;
    private long roundLoadingStart;
    private javax.swing.Timer spinnerTimer;

    private GameHUD gameHUD;
    private XenoGuesser mainApp;

    private float physicalChunkSize;

    private volatile String heatmapName;

    private XenoGuesser_GLEventListener listener;


    public MapPanel(
            int maxMapWidth,
            int maxMapHeight,
            float totalRegionWidth,
            float seaLevelHeight,
            PerlinNoise noise,
            float physicalChunkSize,
            XenoGuesser_GLEventListener listener
        ) {
        this.totalRegionWidth = totalRegionWidth;
        this.halfRegion = totalRegionWidth / 2.0f;
        this.mapImage = new BufferedImage(maxMapWidth, maxMapHeight, BufferedImage.TYPE_INT_RGB);
        this.physicalChunkSize = physicalChunkSize;
        this.listener = listener;

        this.setOpaque(false);
        this.setLayout(null); 

        noteArea = new JTextArea();
        noteArea.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        noteArea.setBackground(Color.WHITE);          
        noteArea.setForeground(Color.BLACK);          
        noteArea.setCaretColor(Color.BLACK);          
        noteArea.setLineWrap(true);                   
        noteArea.setWrapStyleWord(false);              
        noteArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 65), 1),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        noteArea.setVisible(false);

        noteEnterBtn = new JButton("→");
        noteEnterBtn.setFont(new Font("Arial", Font.BOLD, 32));
        noteEnterBtn.setBackground(btnEnabledGreen);
        noteEnterBtn.setForeground(btnTextWhite);
        noteEnterBtn.setFocusPainted(false);
        noteEnterBtn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(40, 130, 40), 1),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
        noteEnterBtn.setVisible(false);
        noteEnterBtn.addActionListener(e -> commitNote());

        ((AbstractDocument) noteArea.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                if ((fb.getDocument().getLength() + string.length()) <= NOTE_CHARACTER_LIMIT) {
                    super.insertString(fb, offset, string, attr);
                }
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if ((fb.getDocument().getLength() - length + text.length()) <= NOTE_CHARACTER_LIMIT) {
                    super.replace(fb, offset, length, text, attrs);
                }
            }
        });
        
        noteArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume(); 
                    commitNote();
                }
            }
        });

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
                // The small and medium maps are only looked at; the mouse steers the view then
                if (!isLarge && !isFullScreenReveal) return;
                int clickX = e.getX();
                int clickY = e.getY();
                
                if (isLarge || isFullScreenReveal) {
                    int mapLeft = (visualMapX != 0) ? visualMapX : (BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET);
                    int mapTop = (visualMapY != 0) ? visualMapY : BORDER_SIZE;
                    for (int i = savedNotes.size() - 1; i >= 0; i--) {
                        MapNote note = savedNotes.get(i);
                        int nx = coreToScreenX(note.coreX, mapLeft);
                        int ny = coreToScreenY(note.coreY, mapTop);

                        boolean hitTriangle = (clickX >= nx - 14 && clickX <= nx + 14 && clickY >= ny - 22 && clickY <= ny);
                        boolean hitTextbox = false;

                        if (note.isExpanded) {
                            Font fontText = new Font("Arial", Font.PLAIN, 13);
                            FontMetrics fmText = getFontMetrics(fontText);
                            int maxBoxWidth = 180;
                            int padding = 8;
                            
                            List<String> displayLines = new ArrayList<>();
                            String[] actualLines = note.text.split("\n");
                            for (String pLine : actualLines) {
                                String[] words = pLine.split(" ");
                                String currLine = "";
                                for (String word : words) {
                                    if (fmText.stringWidth(currLine + word) < (maxBoxWidth - padding * 2)) {
                                        currLine += word + " ";
                                    } else {
                                        displayLines.add(currLine.trim());
                                        currLine = word + " ";
                                    }
                                }
                                displayLines.add(currLine.trim());
                            }

                            int boxHeight = (displayLines.size() * fmText.getHeight()) + (padding * 2);
                            int boxY = ny - 25 - boxHeight - 5; 
                            int boxX = nx - maxBoxWidth / 2; 

                            int limitLeft = mapLeft + 5;
                            int limitRight = mapLeft + currentMapSize - 5;
                            int limitTop = mapTop + 5;
                            int limitBottom = mapTop + currentMapSize - 5;

                            if (boxX < limitLeft) boxX = limitLeft;
                            else if (boxX + maxBoxWidth > limitRight) boxX = limitRight - maxBoxWidth;

                            if (boxY < limitTop) {
                                boxY = ny + 15;
                                if (boxY + boxHeight > limitBottom) boxY = limitBottom - boxHeight;
                            }

                            hitTextbox = (clickX >= boxX && clickX <= boxX + maxBoxWidth && clickY >= boxY && clickY <= boxY + boxHeight);
                        }

                        if (note.isExpanded) {
                            if (hitTriangle || hitTextbox) {
                                note.isExpanded = false;
                                repaint();
                                return; 
                            }
                        } else {
                            boolean isOpeningClick = (e.getButton() == MouseEvent.BUTTON3) || (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() >= 2);
                            if (isOpeningClick && hitTriangle) {
                                note.isExpanded = true;
                                repaint();
                                return; 
                            }
                        }
                    }
                }

                if (e.getButton() == MouseEvent.BUTTON1) {
                    if (isFullScreenReveal && (currentPhase == RevealPhase.SHOW_ALL_RESULTS || currentPhase == RevealPhase.LINGER || currentPhase == RevealPhase.SLAM_TO_HUD)) {
                        
                        if (clickX >= noteBtnX && clickX <= (noteBtnX + noteBtnWidth) && clickY >= noteBtnY && clickY <= (noteBtnY + noteBtnHeight)) {
                            if (!isTypingNote) {
                                isTypingNote = true;
                                MapPanel.this.add(noteArea);
                                MapPanel.this.add(noteEnterBtn);
                                
                                int enterBtnWidth = 75;
                                int gap = 8;
                                
                                noteArea.setBounds(noteBtnX, noteBtnY, noteBtnWidth - enterBtnWidth - gap, noteBtnHeight);
                                noteEnterBtn.setBounds(noteBtnX + noteBtnWidth - enterBtnWidth, noteBtnY, enterBtnWidth, noteBtnHeight);
                                
                                noteArea.setVisible(true);
                                noteEnterBtn.setVisible(true);
                                noteArea.requestFocusInWindow();
                                MapPanel.this.repaint();
                            }
                            return; 
                        }

                        if (clickX >= btnX && clickX <= (btnX + btnWidth) && clickY >= btnY && clickY <= (btnY + btnHeight)) {
                                                        boolean resultsReady = currentPhase != RevealPhase.SHOW_PLAYER_PIN;
                            if (resultsReady && !roundLoading) {
                                startRoundLoading();
                            }
                            return;  
                        }
                    }

                    if (isGuessed) return;

                    int mapLeft = visualMapX;
                    int mapTop = visualMapY;
                    int mapRight = mapLeft + currentMapSize;
                    int mapBottom = mapTop + currentMapSize;

                    if (clickX >= mapLeft && clickX < mapRight && clickY >= mapTop && clickY < mapBottom) {
                        int localizedX = clickX - visualMapX;
                        int localizedY = clickY - visualMapY;
                        float scaleToCore = (float) mapImage.getWidth() / currentMapSize;
                        
                        pinX = (int) screenToCoreX(clickX, visualMapX);
                        pinY = (int) screenToCoreY(clickY, visualMapY);
                        hasPin = true;
                        repaint(); 
                    }
                    else if (isLarge && clickX >= btnX && clickX <= (btnX + btnWidth) && clickY >= btnY && clickY <= (btnY + btnHeight)) {
                        if (hasPin) {
                            startRevealSequence();
                        }
                    }
                }
                
                if (e.getButton() == MouseEvent.BUTTON3 && mainApp.getIsDebugModeActive()) {
                    int localizedX = clickX - visualMapX;
                    int localizedY = clickY - visualMapY;

                    if (localizedX >= 0 && localizedX < currentMapSize && localizedY >= 0 && localizedY < currentMapSize) {
                        float corePixelX = screenToCoreX(clickX, visualMapX);
                        float corePixelY = screenToCoreY(clickY, visualMapY);

                        float worldX = (corePixelX / mapImage.getWidth()) * totalRegionWidth - halfRegion;
                        float worldZ = (corePixelY / mapImage.getHeight()) * totalRegionWidth - halfRegion;

                        if (MapPanel.this.listener != null) {
                            MapPanel.this.listener.setTelepot(worldX, worldZ);
                        }
                    }
                }
            }
        });

        this.addMouseWheelListener(this::handleMouseWheel);
    }

    /** Zooms the enlarged map about the cursor, keeping the point under it fixed. */
    private void handleMouseWheel(MouseWheelEvent e) {
        if (!(isLarge || isFullScreenReveal)) {
            return;
        }
        int mapLeft = visualMapX;
        int mapTop = visualMapY;
        float fractionX = (float) (e.getX() - mapLeft) / currentMapSize;
        float fractionY = (float) (e.getY() - mapTop) / currentMapSize;
        if (fractionX < 0.0f || fractionX > 1.0f || fractionY < 0.0f || fractionY > 1.0f) {
            return;
        }

        float cursorU = viewLeft() + fractionX / zoom;
        float cursorV = viewTop() + fractionY / zoom;
        float newZoom = zoom * (float) Math.pow(ZOOM_STEP, -e.getPreciseWheelRotation());
        zoom = Math.max(1.0f, Math.min(MAX_ZOOM, newZoom));

        float halfView = 0.5f / zoom;
        viewCentreU = clampCentre(cursorU - fractionX / zoom + halfView);
        viewCentreV = clampCentre(cursorV - fractionY / zoom + halfView);
        repaint();
    }

    private void resetZoom() {
        zoom = 1.0f;
        viewCentreU = 0.5f;
        viewCentreV = 0.5f;
    }

    private float clampCentre(float centre) {
        float halfView = 0.5f / zoom;
        return Math.max(halfView, Math.min(1.0f - halfView, centre));
    }

    private float viewLeft() {
        return viewCentreU - 0.5f / zoom;
    }

    private float viewTop() {
        return viewCentreV - 0.5f / zoom;
    }

    // Conversions between core map pixels (the mapImage grid) and screen pixels, respecting the zoom
    private int coreToScreenX(float coreX, int mapLeft) {
        return mapLeft + Math.round((coreX / mapImage.getWidth() - viewLeft()) * zoom * currentMapSize);
    }

    private int coreToScreenY(float coreY, int mapTop) {
        return mapTop + Math.round((coreY / mapImage.getHeight() - viewTop()) * zoom * currentMapSize);
    }

    private float screenToCoreX(int screenX, int mapLeft) {
        return (viewLeft() + (float) (screenX - mapLeft) / (currentMapSize * zoom)) * mapImage.getWidth();
    }

    private float screenToCoreY(int screenY, int mapTop) {
        return (viewTop() + (float) (screenY - mapTop) / (currentMapSize * zoom)) * mapImage.getHeight();
    }

    private void commitNote() {
        String text = noteArea.getText().trim();
        if (!text.isEmpty()) {
            boolean existingFound = false;
            for (MapNote note : savedNotes) {
                if (note.coreX == goalX && note.coreY == goalY) {
                    note.text = text;
                    note.isExpanded = false;
                    existingFound = true;
                    System.out.println("Note Overwritten at destination: " + text);
                    break;
                }
            }
            
            if (!existingFound) {
                savedNotes.add(new MapNote(goalX, goalY, text));
                System.out.println("Note Stored: " + text);
            }
        }
        noteArea.setText("");
        noteArea.setVisible(false);
        isTypingNote = false;
        MapPanel.this.remove(noteArea);
        if (noteEnterBtn != null) {
            noteEnterBtn.setVisible(false);
            MapPanel.this.remove(noteEnterBtn);
        }
        MapPanel.this.requestFocusInWindow(); 
        MapPanel.this.repaint();
    }

    /** Shows a chunk-resolution heatmap over the land, leaving the sea in its usual colour. */
    public void setHeatmapOverlay(BufferedImage heatmap) {
        this.overlayDetail = null;
        this.overlayImage = composeChunkHeatmap(heatmap);
        repaint();
    }

    /**
     * Shows an image covering the whole map region as-is, e.g. a bird's-eye road
     * map, with an optional sharper version for when the map is zoomed in.
     */
    public void setFullMapOverlay(BufferedImage image, BufferedImage detail) {
        this.overlayDetail = detail;
        this.overlayImage = image;
        repaint();
    }

    /** A sharper version of the plain map, used once the map is zoomed in. */
    public void setBaseMapDetail(BufferedImage detail) {
        this.baseMapDetail = detail;
        repaint();
    }

    private BufferedImage composeChunkHeatmap(BufferedImage heatmap) {
        int baseW = mapImage.getWidth();
        int baseH = mapImage.getHeight();

        BufferedImage combinedImage = new BufferedImage(baseW, baseH, BufferedImage.TYPE_INT_RGB);
        int oceanRGB = new Color(25, 80, 160).getRGB();

        int minChunkX = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);
        int minChunkZ = (int) Math.floor((-totalRegionWidth / 2.0f) / physicalChunkSize);

        int overlayW = heatmap.getWidth();
        int overlayH = heatmap.getHeight();

        for (int y = 0; y < baseH; y++) {
            float worldZ = ((float) y / baseH) * totalRegionWidth - halfRegion;
            int cz = (int) Math.floor(worldZ / physicalChunkSize);
            int hy = cz - minChunkZ;
            hy = Math.max(0, Math.min(overlayH - 1, hy));

            for (int x = 0; x < baseW; x++) {
                int baseColour = mapImage.getRGB(x, y);

                if (baseColour == oceanRGB) {
                    combinedImage.setRGB(x, y, oceanRGB); 
                } else {
                    float worldX = ((float) x / baseW) * totalRegionWidth - halfRegion;
                    int cx = (int) Math.floor(worldX / physicalChunkSize);
                    int hx = cx - minChunkX;
                    hx = Math.max(0, Math.min(overlayW - 1, hx));
                    
                    combinedImage.setRGB(x, y, heatmap.getRGB(hx, hy));
                }
            }
        }
        return combinedImage;
    }

    public void setHeatmapVisible(boolean visible) {
        this.showHeatmap = visible;
        repaint();
    }

    private void startRevealSequence() {
        this.isGuessed = true;
        this.isFullScreenReveal = true;
        this.isLarge = false;
        this.mapSize = MapSize.SMALL;
        // The reveal starts from the whole map so both the guess and the answer are visible
        resetZoom();
        this.currentPhase = RevealPhase.SHOW_PLAYER_PIN; 
        this.lineProgress = 0.0f;
        
        this.currentDisplayScore = 0;
        this.shouldDrawScoreText = false;
        this.currentScoreScale = 1.0f;
        this.slamProgress = 0.0f;


        if (getParent() != null) {
            int parentHeight = getParent().getHeight();
            this.currentMapSize = Math.min(750, Math.max(300, parentHeight - 240)); 
        } else {
            this.currentMapSize = 650;
        }

        if (this.mainApp != null) {
            this.mainApp.lockWindowDragging();
        }

        updateGeometryLayouts();
        triggerParentLayoutUpdate();
        if (onSizeChanged != null) onSizeChanged.run();

        final float lineRevealSpeed = 0.05f; 
        final long COUNT_UP_DURATION_MS = 500; 
        final long[] countUpStartTime = {-1};  

        revealTimer = new Timer(16, e -> {
            if (currentPhase == RevealPhase.SHOW_PLAYER_PIN) {
                lineProgress += lineRevealSpeed;
                if (lineProgress >= 1.0f) {
                    lineProgress = 1.0f;
                    currentPhase = RevealPhase.SHOW_ALL_RESULTS;
                    calculateAndApplyScore();
                    shouldDrawScoreText = true;
                    updateGeometryLayouts();
                    triggerParentLayoutUpdate();
                }
            } 
            else if (currentPhase == RevealPhase.SHOW_ALL_RESULTS) {
                if (countUpStartTime[0] == -1) {
                    countUpStartTime[0] = System.currentTimeMillis();
                }

                long elapsed = System.currentTimeMillis() - countUpStartTime[0];
                float t = (float) elapsed / COUNT_UP_DURATION_MS;

                if (t >= 1.0f) {
                    currentDisplayScore = targetRoundScore;
                    currentPhase = RevealPhase.LINGER;
                    phaseStartTime = System.currentTimeMillis();
                } else {
                    float easeOutRatio = 1.0f - (1.0f - t) * (1.0f - t); 
                    currentDisplayScore = (int) (targetRoundScore * easeOutRatio);
                }
            } 
            else if (currentPhase == RevealPhase.LINGER) {
                long elapsed = System.currentTimeMillis() - phaseStartTime;
                if (elapsed >= LINGER_DURATION_MS) {
                    currentPhase = RevealPhase.SLAM_TO_HUD;
                    phaseStartTime = System.currentTimeMillis();
                }
            } 
            else if (currentPhase == RevealPhase.SLAM_TO_HUD) {
                long elapsed = System.currentTimeMillis() - phaseStartTime;
                float t = (float) elapsed / SLAM_DURATION_MS;
                
                if (t >= 1.0f) {
                    t = 1.0f;
                    slamProgress = 1.0f;
                    currentScoreScale = 0.0f;
                    shouldDrawScoreText = false; 
                    
                    if (this.gameHUD != null) {
                        this.gameHUD.addScore(targetRoundScore);
                    }
                    revealTimer.stop(); 
                } else {
                    slamProgress = t;
                    currentScoreScale = 1.0f - (t * 0.9f); 
                }
            }
            repaint();
        });
        revealTimer.start();
    }

    public boolean isFullScreenRevealMode() {
        return this.isFullScreenReveal; 
    }

    /**
     * Where the player really is this round. The next round's spot arrives while the results
     * are still showing (it is built behind them), so it is held back until they close
     * rather than moving the answer flag on screen and giving the next round away.
     */
    public synchronized void setPlayerSpawnLocation(float spawnX, float spawnZ) {
        int x = (int) (((spawnX + halfRegion) / totalRegionWidth) * mapImage.getWidth());
        int y = (int) (((spawnZ + halfRegion) / totalRegionWidth) * mapImage.getHeight());
        if (isGuessed) {
            pendingGoal = new int[] { x, y };
        } else {
            goalX = x;
            goalY = y;
        }
    }

    private synchronized void applyPendingGoal() {
        if (pendingGoal != null) {
            goalX = pendingGoal[0];
            goalY = pendingGoal[1];
            pendingGoal = null;
        }
    }

    public void updateGeometryLayouts() {
        if (isFullScreenReveal && getParent() != null) {
            this.setPreferredSize(getParent().getSize());
            this.setSize(getParent().getSize());
            
            this.visualMapX = (getWidth() - currentMapSize) / 2;
            this.visualMapY = Math.max(20, (getHeight() - currentMapSize) / 2 - 45);
            
            this.btnWidth = currentMapSize;
            this.btnHeight = 40;
            
            this.noteBtnWidth = btnWidth;
            this.noteBtnHeight = 55; 
            this.noteBtnX = visualMapX;
            this.noteBtnY = visualMapY + currentMapSize + BORDER_SIZE + 15; 
            
            this.btnX = visualMapX;
            this.btnY = noteBtnY + noteBtnHeight + 15; 

            if (noteArea != null && noteArea.isVisible()) {
                int enterBtnWidth = 75;
                int gap = 8;
                noteArea.setBounds(noteBtnX, noteBtnY, noteBtnWidth - enterBtnWidth - gap, noteBtnHeight);
                if (noteEnterBtn != null) {
                    noteEnterBtn.setBounds(noteBtnX + noteBtnWidth - enterBtnWidth, noteBtnY, enterBtnWidth, noteBtnHeight);
                }
            }
        } else {
            boolean needsExtraSpace = isLarge;
            int bottomSpace = needsExtraSpace ? EXTRA_BOTTOM_SPACE : 0;
            
            int panelWidth = currentMapSize + (BORDER_SIZE * 2) + HORIZONTAL_SHUFFLE_OFFSET + 2;
            int panelHeight = currentMapSize + (BORDER_SIZE * 2) + bottomSpace;
            
            this.setPreferredSize(new Dimension(panelWidth, panelHeight));
            
            this.visualMapX = BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET;
            this.visualMapY = BORDER_SIZE;
            
            this.btnWidth = panelWidth - 24;
            this.btnHeight = 40;
            this.btnX = (panelWidth - btnWidth) / 2;
            this.btnY = currentMapSize + (BORDER_SIZE * 2) + 7;

            if (noteArea != null && noteArea.isVisible()) {
                noteArea.setVisible(false);
                isTypingNote = false;
                this.remove(noteArea);
            }
            if (noteEnterBtn != null && noteEnterBtn.isVisible()) {
                noteEnterBtn.setVisible(false);
                this.remove(noteEnterBtn);
            }
        }
    }

    private void triggerParentLayoutUpdate() {
        if (getParent() instanceof JLayeredPane) {
            JLayeredPane layeredPane = (JLayeredPane) getParent();
            Rectangle oldBounds = this.getBounds();
            XenoGuesser.updateMinimapBounds(layeredPane, this);
            layeredPane.repaint(oldBounds);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create(); 
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int frameW = getWidth();
            int frameH = getHeight();

            if (isFullScreenReveal) {
                g2d.setColor(new Color(245, 235, 195)); 
                g2d.fillRect(0, 0, frameW, frameH);
            } else {
                g2d.setColor(trayGrey);
                g2d.fillRect(0, 0, frameW, frameH);
            }

            int mapX = (this.visualMapX != 0) ? this.visualMapX : (BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET);
            int mapY = (this.visualMapY != 0) ? this.visualMapY : BORDER_SIZE;

            float[] fractions = {0.0f, 0.5f, 1.0f};
            Color[] colours = {baseDarkGrey, highlightLightGrey, baseDarkGrey};

            int mapFrameH = currentMapSize + (BORDER_SIZE * 2);
            int mapFrameW = currentMapSize + (BORDER_SIZE * 2);
            int startX = mapX - BORDER_SIZE;
            int startY = mapY - BORDER_SIZE;
            
            Polygon topFrame = new Polygon();
            topFrame.addPoint(startX, startY); 
            topFrame.addPoint(startX + mapFrameW + 2, startY); 
            topFrame.addPoint(startX + mapFrameW - BORDER_SIZE + 2, startY + BORDER_SIZE); 
            topFrame.addPoint(startX + BORDER_SIZE, startY + BORDER_SIZE);
            g2d.setPaint(new LinearGradientPaint(new Point(startX, startY), new Point(startX, startY + BORDER_SIZE), fractions, colours));
            g2d.fill(topFrame);

            Polygon bottomFrame = new Polygon();
            bottomFrame.addPoint(startX + BORDER_SIZE, startY + mapFrameH - BORDER_SIZE); 
            bottomFrame.addPoint(startX + mapFrameW - BORDER_SIZE + 2, startY + mapFrameH - BORDER_SIZE); 
            bottomFrame.addPoint(startX + mapFrameW + 2, startY + mapFrameH); 
            bottomFrame.addPoint(startX, startY + mapFrameH);
            g2d.setPaint(new LinearGradientPaint(new Point(startX, startY + mapFrameH - BORDER_SIZE), new Point(startX, startY + mapFrameH), fractions, colours));
            g2d.fill(bottomFrame);

            Polygon leftFrame = new Polygon();
            leftFrame.addPoint(startX, startY); 
            leftFrame.addPoint(startX + BORDER_SIZE, startY + BORDER_SIZE); 
            leftFrame.addPoint(startX + BORDER_SIZE, startY + mapFrameH - BORDER_SIZE); 
            leftFrame.addPoint(startX, startY + mapFrameH);
            g2d.setPaint(new LinearGradientPaint(new Point(startX, startY), new Point(startX + BORDER_SIZE, startY), fractions, colours));
            g2d.fill(leftFrame);

            Polygon rightFrame = new Polygon();
            rightFrame.addPoint(startX + mapFrameW - BORDER_SIZE, startY + BORDER_SIZE); 
            rightFrame.addPoint(startX + mapFrameW + 2, startY); 
            rightFrame.addPoint(startX + mapFrameW + 2, startY + mapFrameH); 
            rightFrame.addPoint(startX + mapFrameW - BORDER_SIZE, startY + mapFrameH - BORDER_SIZE);
            g2d.setPaint(new LinearGradientPaint(new Point(startX + mapFrameW - BORDER_SIZE, startY), new Point(startX + mapFrameW + 2, startY), fractions, colours));
            g2d.fill(rightFrame);

            BufferedImage overlay = this.overlayImage;
            boolean overlayShown = showHeatmap && overlay != null;
            BufferedImage mapSource = overlayShown ? overlay : mapImage;
            BufferedImage detailSource = overlayShown ? overlayDetail : baseMapDetail;
            if (zoom >= DETAIL_SWITCH_ZOOM && detailSource != null) {
                mapSource = detailSource;
            }
            Shape frameClip = g2d.getClip();
            g2d.clipRect(mapX, mapY, currentMapSize, currentMapSize);
            AffineTransform mapTransform = new AffineTransform();
            mapTransform.translate(mapX - viewLeft() * zoom * currentMapSize, mapY - viewTop() * zoom * currentMapSize);
            mapTransform.scale(zoom * currentMapSize / mapSource.getWidth(), zoom * currentMapSize / mapSource.getHeight());
            g2d.drawImage(mapSource, mapTransform, null);
            g2d.setClip(frameClip);

            if (zoom > 1.01f) {
                String zoomLabel = String.format("%.1fx", zoom);
                g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 12f));
                FontMetrics zoomFm = g2d.getFontMetrics();
                int zoomW = zoomFm.stringWidth(zoomLabel) + 12;
                int zoomH = zoomFm.getAscent() + 8;
                int zoomX = mapX + currentMapSize - zoomW - 8;
                int zoomY = mapY + currentMapSize - zoomH - 8;
                g2d.setColor(new Color(25, 25, 27, 195));
                g2d.fillRoundRect(zoomX, zoomY, zoomW, zoomH, 8, 8);
                g2d.setColor(Color.WHITE);
                g2d.drawString(zoomLabel, zoomX + 6, zoomY + zoomFm.getAscent() + 3);
            }

            if (showHeatmap) {
                if (heatmapName != null && !heatmapName.isEmpty()) {
                    g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 13f));
                    FontMetrics labelFm = g2d.getFontMetrics();
                    int textW = labelFm.stringWidth(heatmapName);
                    int textH = labelFm.getAscent();

                    int padX = 12;
                    int padY = 6;
                    int boxW = textW + (padX * 2);
                    int boxH = textH + (padY * 2);
                    
                    int boxX = mapX + (currentMapSize - boxW) / 2;
                    int boxY = mapY + 12;

                    g2d.setColor(new Color(25, 25, 27, 195));
                    g2d.fillRoundRect(boxX, boxY, boxW, boxH, 10, 10);

                    g2d.setColor(new Color(255, 255, 255, 45));
                    g2d.drawRoundRect(boxX, boxY, boxW, boxH, 10, 10);

                    g2d.setColor(Color.WHITE);
                    g2d.drawString(heatmapName, boxX + padX, boxY + padY + textH - 1);
                }
            }

            if (isLarge && !isFullScreenReveal) {
                g2d.setColor(hasPin ? btnEnabledGreen : btnDisabledGrey);
                g2d.fillRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight); 

                g2d.setColor(new Color(255, 255, 255, 50));
                g2d.setStroke(new BasicStroke(1.2f));
                g2d.drawRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight);

                g2d.setColor(btnTextWhite);
                g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 22f));
                FontMetrics fm = g2d.getFontMetrics();
                String btnText = "Guess";
                int stringWidth = fm.stringWidth(btnText);
                int stringHeight = fm.getAscent();
                int textX = btnX + (btnWidth - stringWidth) / 2;
                int textY = btnY + (btnHeight + stringHeight) / 2 - 2; 
                g2d.drawString(btnText, textX, textY);
            }

            if (isLarge || isFullScreenReveal) {
                for (MapNote note : savedNotes) {
                    int drawX = coreToScreenX(note.coreX, mapX);
                    int drawY = coreToScreenY(note.coreY, mapY);
                    if (drawX < mapX || drawX > mapX + currentMapSize || drawY < mapY || drawY > mapY + currentMapSize) {
                        continue;
                    }

                    Polygon tri = new Polygon();
                    tri.addPoint(drawX, drawY);             
                    tri.addPoint(drawX - 14, drawY - 22);   
                    tri.addPoint(drawX + 14, drawY - 22);   

                    g2d.setColor(Color.BLACK);
                    g2d.fill(tri);

                    g2d.setColor(Color.WHITE);
                    g2d.setFont(new Font("Arial", Font.BOLD, 10));
                    FontMetrics labelFm = g2d.getFontMetrics();
                    int strW = labelFm.stringWidth("abc");
                    g2d.drawString("abc", drawX - strW / 2, drawY - 13);

                    if (note.isExpanded) {
                        g2d.setFont(new Font("Arial", Font.PLAIN, 13));
                        FontMetrics fmText = g2d.getFontMetrics();
                        int maxBoxWidth = 180;
                        int padding = 8;
                        
                        List<String> displayLines = new ArrayList<>();
                        String[] actualLines = note.text.split("\n");
                        
                        for (String pLine : actualLines) {
                            String[] words = pLine.split(" ");
                            String currLine = "";
                            for (String word : words) {
                                if (fmText.stringWidth(currLine + word) < (maxBoxWidth - padding * 2)) {
                                    currLine += word + " ";
                                } else {
                                    displayLines.add(currLine.trim());
                                    currLine = word + " ";
                                }
                            }
                            displayLines.add(currLine.trim());
                        }

                        int boxHeight = (displayLines.size() * fmText.getHeight()) + (padding * 2);
                        
                        int boxY = drawY - 25 - boxHeight - 5; 
                        int boxX = drawX - maxBoxWidth / 2; 

                        int limitLeft = mapX + 5;
                        int limitRight = mapX + currentMapSize - 5;
                        int limitTop = mapY + 5;
                        int limitBottom = mapY + currentMapSize - 5;

                        if (boxX < limitLeft) {
                            boxX = limitLeft;
                        } else if (boxX + maxBoxWidth > limitRight) {
                            boxX = limitRight - maxBoxWidth;
                        }

                        if (boxY < limitTop) {
                            boxY = drawY + 15;
                            if (boxY + boxHeight > limitBottom) {
                                boxY = limitBottom - boxHeight;
                            }
                        }

                        g2d.setColor(new Color(25, 25, 27, 240));
                        g2d.fillRoundRect(boxX, boxY, maxBoxWidth, boxHeight, 8, 8);
                        
                        g2d.setColor(Color.WHITE);
                        g2d.setStroke(new BasicStroke(1.2f));
                        g2d.drawRoundRect(boxX, boxY, maxBoxWidth, boxHeight, 8, 8);

                        int textYPos = boxY + padding + fmText.getAscent();
                        for (String line : displayLines) {
                            g2d.drawString(line, boxX + padding, textYPos);
                            textYPos += fmText.getHeight();
                        }
                    }
                }
            }

            if (hasPin) {
                Stroke originalStroke = g2d.getStroke();

                int displayPinX = coreToScreenX(pinX, mapX);
                int displayPinY = coreToScreenY(pinY, mapY);

                int displayGoalX = coreToScreenX(goalX, mapX);
                int displayGoalY = coreToScreenY(goalY, mapY);

                Shape markerClip = g2d.getClip();
                g2d.clipRect(mapX, mapY, currentMapSize, currentMapSize);

                if (isFullScreenReveal) {
                    int targetLineX = displayPinX + (int) ((displayGoalX - displayPinX) * lineProgress);
                    int targetLineY = displayPinY + (int) ((displayGoalY - displayPinY) * lineProgress);

                    g2d.setColor(Color.WHITE);
                    Stroke dottedStroke = new BasicStroke(3.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{6.0f, 6.0f}, 0.0f);
                    g2d.setStroke(dottedStroke);
                    g2d.drawLine(displayPinX, displayPinY, targetLineX, targetLineY);
                    g2d.setStroke(originalStroke);
                }

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

                if (isFullScreenReveal && (currentPhase != RevealPhase.SHOW_PLAYER_PIN)) {
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

                g2d.setClip(markerClip);

                if (isFullScreenReveal) {
                    boolean resultsReady = (currentPhase != RevealPhase.SHOW_PLAYER_PIN);
                    
                    if (!isTypingNote) {
                        g2d.setColor(resultsReady ? btnEnabledGreen : btnDisabledGrey);
                        g2d.fillRoundRect(noteBtnX, noteBtnY, noteBtnWidth, noteBtnHeight, 12, 12);

                        g2d.setColor(new Color(255, 255, 255, resultsReady ? 60 : 30));
                        g2d.setStroke(new BasicStroke(1.5f));
                        g2d.drawRoundRect(noteBtnX, noteBtnY, noteBtnWidth, noteBtnHeight, 12, 12);
                        g2d.setStroke(originalStroke);

                        g2d.setColor(btnTextWhite);
                        g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 18f));
                        FontMetrics fmNote = g2d.getFontMetrics();
                        String noteBtnText = "Add Note";
                        int noteStrW = fmNote.stringWidth(noteBtnText);
                        int noteStrH = fmNote.getAscent();
                        g2d.drawString(noteBtnText, noteBtnX + (noteBtnWidth - noteStrW) / 2, noteBtnY + (noteBtnHeight + noteStrH) / 2 - 2);
                    }

                                        // Paler while the next round loads
                    Color nextColour = !resultsReady ? btnDisabledGrey
                            : roundLoading ? blend(btnNextRoundBlue, Color.WHITE, 0.45f) : btnNextRoundBlue;
                    g2d.setColor(nextColour);
                    g2d.fillRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight);

                    g2d.setColor(new Color(255, 255, 255, resultsReady ? 60 : 30));
                    g2d.setStroke(new BasicStroke(1.5f));
                    g2d.drawRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight);
                    g2d.setStroke(originalStroke);

                    g2d.setColor(btnTextWhite);
                    g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 20f));
                    FontMetrics fm = g2d.getFontMetrics();
                                        String endText = roundLoading ? "Loading" : "Next Round";
                    int stringWidth = fm.stringWidth(endText);
                    int stringHeight = fm.getAscent();
                    int spinnerSize = roundLoading ? Math.round(btnHeight * 0.45f) : 0;
                    int spinnerGap = roundLoading ? 12 : 0;
                    int textX = btnX + (btnWidth - stringWidth + spinnerSize + spinnerGap) / 2;
                    int textY = btnY + (btnHeight + stringHeight) / 2 - 2;
                    g2d.drawString(endText, textX, textY);
                    if (roundLoading) {
                        // A half-circle outline turning round, to show work is going on
                        int spinnerX = textX - spinnerGap - spinnerSize;
                        int spinnerY = btnY + (btnHeight - spinnerSize) / 2;
                        int angle = (int) (((System.currentTimeMillis() - roundLoadingStart) * 0.4) % 360);
                        g2d.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2d.drawArc(spinnerX, spinnerY, spinnerSize, spinnerSize, -angle, 180);
                        g2d.setStroke(originalStroke);
                    }
                }

                if (shouldDrawScoreText) {
                    String pointsStr = String.format("%,d", currentDisplayScore);
                    float baseBubbleFontSize = currentMapSize * 0.15f; 
                    float dynamicFontSize = baseBubbleFontSize * currentScoreScale;
                    
                    if (dynamicFontSize > 2f) {
                        g2d.setFont(new Font("Arial Black", Font.BOLD, (int) dynamicFontSize));
                        FontMetrics scoreFm = g2d.getFontMetrics();
                        
                        int textW = scoreFm.stringWidth(pointsStr);
                        int textH = scoreFm.getAscent();
                        
                        int centerMapX = mapX + (currentMapSize / 2);
                        int centerMapY = mapY + (currentMapSize / 2);
                        
                        int targetHUDX = -getX() + HudStyle.HUD_MARGIN + HudStyle.SCORE_TARGET_X;
                        int targetHUDY = -getY() + HudStyle.HUD_MARGIN + HudStyle.SCORE_TARGET_Y;
                        
                        int drawX = (int) (centerMapX + (targetHUDX - centerMapX) * slamProgress) - (textW / 2);
                        int drawY = (int) (centerMapY + (targetHUDY - centerMapY) * slamProgress) + (textH / 2);
                        
                        int baseOutline = Math.max(3, (int)(baseBubbleFontSize * 0.08f));
                        int outlineThickness = Math.max(1, (int)(baseOutline * currentScoreScale));
                        
                        g2d.setColor(new Color(50, 50, 52, 230)); 
                        
                        for (int xOffset = -outlineThickness; xOffset <= outlineThickness; xOffset++) {
                            for (int yOffset = -outlineThickness; yOffset <= outlineThickness; yOffset++) {
                                if (xOffset * xOffset + yOffset * yOffset <= outlineThickness * outlineThickness) {
                                    g2d.drawString(pointsStr, drawX + xOffset, drawY + yOffset);
                                }
                            }
                        }
                        
                        g2d.setColor(Color.WHITE);
                        g2d.drawString(pointsStr, drawX, drawY);
                    }
                }
            }
        } finally {
            g2d.dispose(); 
        }
    }

    public void resetMapState() {
        applyPendingGoal();
        this.isGuessed = false;
        this.isFullScreenReveal = false;
        this.isLarge = false;
        this.mapSize = MapSize.SMALL;
        this.hasPin = false;
        this.currentMapSize = 150;
        resetZoom();
        this.lineProgress = 0.0f;
        this.currentPhase = RevealPhase.SHOW_PLAYER_PIN;
        
        this.targetRoundScore = 0;
        this.currentDisplayScore = 0;
        this.shouldDrawScoreText = false;
        this.phaseStartTime = 0;
        this.slamProgress = 0.0f;
        this.currentScoreScale = 1.0f;

        this.isTypingNote = false;
        if (this.noteArea != null) {
            this.noteArea.setVisible(false);
            this.noteArea.setText("");
            this.remove(this.noteArea);
        }
        if (this.noteEnterBtn != null) {
            this.noteEnterBtn.setVisible(false);
            this.remove(this.noteEnterBtn);
        }
        
        if (this.mainApp != null) {
            this.mainApp.unlockWindowDragging();
        }

        updateGeometryLayouts();
        triggerParentLayoutUpdate();
        repaint();
        if (onSizeChanged != null) onSizeChanged.run();
    }

    /** M: enlarges the small map, or shrinks the large one. */
    public void toggleSize() {
        setMapSize(mapSize == MapSize.SMALL ? MapSize.LARGE : MapSize.SMALL);
    }

    public MapSize getMapSize() {
        return mapSize;
    }

    public boolean isLargeMap() {
        return isLarge;
    }

    /** Called on the UI thread whenever the map changes size or the results open or close. */
    public void setOnSizeChanged(Runnable onSizeChanged) {
        this.onSizeChanged = onSizeChanged;
    }

    private void setMapSize(MapSize size) {
        if (isFullScreenReveal || isGuessed) return;
        mapSize = size;
        isLarge = size == MapSize.LARGE;
        int parentHeight = getParent() != null ? getParent().getHeight() : 900;
        currentMapSize = switch (size) {
            case SMALL -> 150;
            case LARGE -> (int) (parentHeight * 0.60f);
        };
        if (!isLarge) resetZoom();
        updateGeometryLayouts();
        triggerParentLayoutUpdate();
        repaint();
        if (onSizeChanged != null) onSizeChanged.run();
    }

        /** Shows the button's loading state, then asks for the next round once that has been painted. */
    private void startRoundLoading() {
        roundLoading = true;
        roundLoadingStart = System.currentTimeMillis();
        if (spinnerTimer == null) {
            spinnerTimer = new javax.swing.Timer(30, e -> repaint());
        }
        spinnerTimer.start();
        repaint();
        javax.swing.Timer delay = new javax.swing.Timer(80, e -> nextRoundRequested = true);
        delay.setRepeats(false);
        delay.start();
    }

    /** Called once the next round's surroundings are built: closes the results and starts the round. */
    public void finishRoundLoading() {
        roundLoading = false;
        if (spinnerTimer != null) spinnerTimer.stop();
        if (gameHUD != null) {
            gameHUD.advanceRound();
        }
        resetMapState();
    }

    public boolean isNextRoundRequested() {
        return nextRoundRequested;
    }

        private static Color blend(Color from, Color to, float t) {
        return new Color(Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
    }

    public void clearNextRoundRequest() {
        this.nextRoundRequested = false;
    }

    private void calculateAndApplyScore() {
        double deltaX = pinX - goalX;
        double deltaY = pinY - goalY;
        double pixelDistance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        float mapWidthPixels = mapImage.getWidth();
        double realWorldDistance = (pixelDistance / mapWidthPixels) * totalRegionWidth;

        double maxDiagonalDistance = Math.sqrt(2.0) * totalRegionWidth;
        double kConstant = maxDiagonalDistance / 14.0f; 
        int score = (int) Math.round(5000.0 * Math.exp(-realWorldDistance / kConstant));
        
        if (score < 0) score = 0;
        if (score > 5000) score = 5000;

        this.targetRoundScore = score;
    }

    public void setGameHUD(GameHUD hud) {
        this.gameHUD = hud;
    }

    public void setMainApp(XenoGuesser mainApp) {
        this.mainApp = mainApp;
    }

    public void setHeatmapName(String heatmapName) {
        this.heatmapName = heatmapName;
    }
}