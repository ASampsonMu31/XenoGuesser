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
    // The sea's colour on the map
    private int oceanRGB;
    // The plain map's sharper version, used once the map is zoomed in
    private volatile BufferedImage baseMapDetail;
    // A gradient map colouring the land in place of the plain map (the sea as usual), if one is chosen
    private volatile BufferedImage gradientImage;
    private volatile String gradientName;

    /** What can be drawn over the land and sea, in the order they're drawn. */
    public enum Layer {
        CONTOURS("Contours"), ROADS("Roads"), BUILDINGS("Buildings"), SHOPS("Shops");

        public final String label;

        Layer(String label) {
            this.label = label;
        }
    }

    // Each layer's {overview, detail} images (transparent), once drawn, and which are ticked
    private final java.util.Map<Layer, BufferedImage[]> layerImages = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Layer> shownLayers = java.util.Collections.synchronizedSet(java.util.EnumSet.noneOf(Layer.class));
    // Where each nation's names go: {name written in its own script (image), u, v (0..1 across the map image), whether it's the main one}
    private volatile List<Object[]> nationLabels = List.of();
    // Whether the nations' names are written over the map (with the nations overlay)
    private volatile boolean showNationNames;
    private MapLayersPanel layersPanel;

    // Scroll-wheel zoom of the enlarged map: magnification and the visible centre in 0..1 map space
    private static final float MAX_ZOOM = 16.0f;
    private static final float ZOOM_STEP = 1.25f;
    private static final float DETAIL_SWITCH_ZOOM = 1.3f;
    private float zoom = 1.0f;
    // Zoomed in past the whole images' detail, the map is drawn in tiles made at the detail
    // it's seen at (see MapTiles): the plain map, the overlay chosen and each layer
    private final MapTiles tiles = new MapTiles(this::repaint);
    private volatile MapTiles.Renderer baseTiles, overlayTiles;
    private volatile String overlayTileName;
    private final java.util.Map<Layer, MapTiles.Renderer> layerTiles = new java.util.concurrent.ConcurrentHashMap<>();
    // How many pixels across the whole detailed images are (beyond that, tiles)
    private static final int WHOLE_IMAGE_DETAIL = 2250;
    private float viewCentreU = 0.5f;
    private float viewCentreV = 0.5f;
    
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
    // (in map pixels, fractional: exactly where, however far the map is zoomed)
    private float[] pendingGoal;
    private float goalX;
    private float goalY;

    private boolean hasPin = false;
    private float pinX;
    private float pinY;

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
    private int currentMapSize = 210; 

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
    // Once the player has left the shop, until the next round is ready
    private boolean roundLoading = false;
    // Whether the round's points have gone onto the score (as they fly there, or on going to the shop)
    private boolean scoreBanked;
    // Opens the shop (from the results' button)
    private Runnable onProceedToShop;

    private GameHUD gameHUD;
    private XenoGuesser mainApp;

    private float physicalChunkSize;


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

        // The world's own sea and land colours (land as grass over soil)
        int[] mapColours = MapColours.of(noise.seed);
        this.oceanRGB = mapColours[0];
        for (int z = 0; z < maxMapHeight; z++) {
            for (int x = 0; x < maxMapWidth; x++) {
                float worldX = ((float) x / maxMapWidth) * totalRegionWidth - halfRegion;
                float worldZ = ((float) z / maxMapHeight) * totalRegionWidth - halfRegion;

                float terrainHeight = TerrainMesh.getLayeredHeight(worldX, worldZ, noise);

                if (terrainHeight > seaLevelHeight) {
                    mapImage.setRGB(x, z, mapColours[1]);
                } else {
                    mapImage.setRGB(x, z, mapColours[0]);
                }
            }
        }

        updateGeometryLayouts();

        this.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // Developer mode: holding P, a click anywhere on the map goes there
                if (mainApp != null && mainApp.getIsDebugModeActive() && mainApp.isKeyHeld(java.awt.event.KeyEvent.VK_P)) {
                    teleportToClick(e.getX(), e.getY());
                    return;
                }
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
                            int limitBottom = mapTop + mapHeight() - 5;

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
                                proceedToShop();
                            }
                            return;  
                        }
                    }

                    int mapLeft = visualMapX;
                    int mapTop = visualMapY;
                    int mapRight = mapLeft + currentMapSize;
                    int mapBottom = mapTop + mapHeight();

                    if (clickX >= mapLeft && clickX < mapRight && clickY >= mapTop && clickY < mapBottom) {
                        // A press on the map: dragged, it moves the view about (zoomed in); let
                        // go without dragging, it places the marker (see mouseReleased)
                        dragFrom = new Point(clickX, clickY);
                        dragFromU = viewCentreU;
                        dragFromV = viewCentreV;
                        dragging = false;
                        return;
                    }
                    if (isGuessed) return;
                    else if (isLarge && clickX >= btnX && clickX <= (btnX + btnWidth) && clickY >= btnY && clickY <= (btnY + btnHeight)) {
                        if (hasPin) {
                            startRevealSequence();
                        }
                    }
                }
                
                if (e.getButton() == MouseEvent.BUTTON3 && mainApp.getIsDebugModeActive()) {
                    int localizedX = clickX - visualMapX;
                    int localizedY = clickY - visualMapY;

                    if (localizedX >= 0 && localizedX < currentMapSize && localizedY >= 0 && localizedY < mapHeight()) {
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
        this.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragFrom == null || e.getButton() != MouseEvent.BUTTON1) return;
                boolean click = !dragging;
                dragFrom = null;
                dragging = false;
                setCursor(java.awt.Cursor.getDefaultCursor());
                // A click without a drag places the marker (before the guess)
                if (click && !isGuessed && (isLarge || isFullScreenReveal)) {
                    pinX = screenToCoreX(e.getX(), visualMapX);
                    pinY = screenToCoreY(e.getY(), visualMapY);
                    hasPin = true;
                    repaint();
                }
            }
        });
        this.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragFrom == null || !(isLarge || isFullScreenReveal)) return;
                int dx = e.getX() - dragFrom.x, dy = e.getY() - dragFrom.y;
                if (!dragging && dx * dx + dy * dy < DRAG_THRESHOLD * DRAG_THRESHOLD) return;
                dragging = true;
                if (zoom <= 1.0f) return;
                // The point grabbed stays under the pointer, at the same zoom
                setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
                float across = currentMapSize * zoom;
                viewCentreU = clampCentre(dragFromU - dx / across);
                viewCentreV = clampCentreV(dragFromV - dy / across);
                repaint();
            }
        });
    }

    // Dragging the enlarged map: where the press was and the view's centre then, and whether
    // it has moved far enough (in pixels) to count as a drag rather than a click
    private Point dragFrom;
    private float dragFromU, dragFromV;
    private boolean dragging;
    private static final int DRAG_THRESHOLD = 5;

    /** Developer mode: sends the player to the place on the map under a click (see XenoGuesser_GLEventListener.setTelepot). */
    private void teleportToClick(int clickX, int clickY) {
        int localizedX = clickX - visualMapX, localizedY = clickY - visualMapY;
        if (localizedX < 0 || localizedX >= currentMapSize || localizedY < 0 || localizedY >= mapHeight() || listener == null) return;
        float worldX = (screenToCoreX(clickX, visualMapX) / mapImage.getWidth()) * totalRegionWidth - halfRegion;
        float worldZ = (screenToCoreY(clickY, visualMapY) / mapImage.getHeight()) * totalRegionWidth - halfRegion;
        listener.setTelepot(worldX, worldZ);
    }

    /** Zooms the enlarged map about the cursor, keeping the point under it fixed. */
    private void handleMouseWheel(MouseWheelEvent e) {
        if (!(isLarge || isFullScreenReveal)) {
            return;
        }
        int mapLeft = visualMapX;
        int mapTop = visualMapY;
        float fractionX = (float) (e.getX() - mapLeft) / currentMapSize;
        float fractionY = (float) (e.getY() - mapTop) / mapHeight();
        if (fractionX < 0.0f || fractionX > 1.0f || fractionY < 0.0f || fractionY > 1.0f) {
            return;
        }

        float cursorU = viewLeft() + fractionX / zoom;
        float cursorV = viewTop() + fractionY * Planet.aspect() / zoom;
        float newZoom = zoom * (float) Math.pow(ZOOM_STEP, -e.getPreciseWheelRotation());
        zoom = Math.max(1.0f, Math.min(MAX_ZOOM, newZoom));

        float halfView = 0.5f / zoom;
        viewCentreU = clampCentre(cursorU - fractionX / zoom + halfView);
        viewCentreV = clampCentreV(cursorV - fractionY * Planet.aspect() / zoom + halfView * Planet.aspect());
        repaint();
    }

    private void resetZoom() {
        zoom = 1.0f;
        viewCentreU = 0.5f;
        viewCentreV = 0.5f;
    }

    /** Developer aid: zoomed in this far, centred on (u, v) of the map (0 to 1 across, 0 to 1 down the square). */
    public void devZoom(float magnification, float u, float v) {
        zoom = Math.max(1.0f, Math.min(MAX_ZOOM, magnification));
        viewCentreU = clampCentre(u);
        viewCentreV = clampCentreV(v);
        repaint();
    }

    private float clampCentre(float centre) {
        float halfView = 0.5f / zoom;
        return Math.max(halfView, Math.min(1.0f - halfView, centre));
    }

    /**
     * The map is a Mercator chart of the planet, wider than it is tall (see Planet): the
     * square map images cover the chart's width both ways, and only the band between the
     * clipped latitudes, its height aspect times its width, is shown.
     */
    private int mapHeight() {
        return Math.round(currentMapSize * Planet.aspect());
    }

    private float clampCentreV(float centre) {
        float halfView = 0.5f * Planet.aspect() / zoom;
        float top = 0.5f - 0.5f * Planet.aspect(), bottom = 0.5f + 0.5f * Planet.aspect();
        return Math.max(top + halfView, Math.min(bottom - halfView, centre));
    }

    private float viewLeft() {
        return viewCentreU - 0.5f / zoom;
    }

    private float viewTop() {
        return viewCentreV - 0.5f * Planet.aspect() / zoom;
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
                if (note.coreX == Math.round(goalX) && note.coreY == Math.round(goalY)) {
                    note.text = text;
                    note.isExpanded = false;
                    existingFound = true;
                    System.out.println("Note Overwritten at destination: " + text);
                    break;
                }
            }
            
            if (!existingFound) {
                savedNotes.add(new MapNote(Math.round(goalX), Math.round(goalY), text));
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

    /**
     * Colours the land with an overlay already composed by composeOverlay (the sea staying as
     * it is), or with nothing but the plain map given null; with the nations' names written
     * over it if asked.
     */
    // The overlay's key: its colours from lowest to highest (four, evenly spaced) and the values
    // at its low end, middle and high end, with units; null for none
    private volatile float[][] legendColours;
    private volatile String[] legendLabels;

    /** The key shown under the overlay's name: its colours lowest to highest, and labels for its low end, middle and high end. */
    // A picture shown beside the overlay's name (an animal's, for its population), or null
    private volatile java.awt.Image overlayPicture;

    // The card above the map with the animal or plant whose range is shown, turning round
    private final PreviewCard previewCard = new PreviewCard();

    /** Shows an animal or plant turning round above the map (frames null: still being drawn; name null: none). */
    public void setPreview(String name, String kind, BufferedImage[] frames, BufferedImage leaf) {
        previewCard.show(name, kind, frames, leaf);
        if (getParent() instanceof JLayeredPane) XenoGuesser.updateMinimapBounds((JLayeredPane) getParent(), this);
    }

    /** The card above the map with the turning animal or plant. */
    public PreviewCard getPreviewCard() {
        return previewCard;
    }

    public void setOverlayPicture(java.awt.Image picture) {
        this.overlayPicture = picture;
        repaint();
    }

    public void setLegend(float[][] colours, String[] labels) {
        this.legendColours = colours;
        this.legendLabels = labels;
        repaint();
    }

    public void setOverlay(BufferedImage composed, String name, boolean nationNames) {
        this.gradientImage = composed;
        this.gradientName = composed != null ? name : null;
        this.showNationNames = composed != null && nationNames;
        repaint();
    }

    /**
     * A chunk-resolution overlay laid over the land of the map (the sea left as it is),
     * ready to show. Slow-ish, so done in the background: it only reads the plain map.
     */
    /** The overlay's key (see setLegend), centred on x below y: a bar of its colours, with its values and units beneath. */
    private void drawLegend(Graphics2D g2d, int centreX, int top) {
        float[][] colours = legendColours;
        String[] labels = legendLabels;
        if (colours == null || labels == null) return;
        g2d.setFont(g2d.getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g2d.getFontMetrics();
        int barW = Math.min(220, currentMapSize - 40), barH = 10, pad = 8;
        int boxW = barW + pad * 2, boxH = barH + fm.getAscent() + pad * 2 + 4;
        int boxX = centreX - boxW / 2;
        g2d.setColor(new Color(25, 25, 27, 195));
        g2d.fillRoundRect(boxX, top, boxW, boxH, 10, 10);
        int barX = boxX + pad, barY = top + pad;
        for (int x = 0; x < barW; x++) {
            float f = x / (float) (barW - 1);
            int seg = Math.min(colours.length - 2, (int) (f * (colours.length - 1)));
            float t = f * (colours.length - 1) - seg;
            float[] a = colours[seg], b = colours[seg + 1];
            g2d.setColor(new Color(Math.min(1f, a[0] + (b[0] - a[0]) * t), Math.min(1f, a[1] + (b[1] - a[1]) * t), Math.min(1f, a[2] + (b[2] - a[2]) * t)));
            g2d.drawLine(barX + x, barY, barX + x, barY + barH);
        }
        g2d.setColor(new Color(255, 255, 255, 60));
        g2d.drawRect(barX, barY, barW - 1, barH);
        g2d.setColor(Color.WHITE);
        int textY = barY + barH + 4 + fm.getAscent();
        g2d.drawString(labels[0], barX, textY);
        g2d.drawString(labels[1], barX + (barW - fm.stringWidth(labels[1])) / 2, textY);
        g2d.drawString(labels[2], barX + barW - fm.stringWidth(labels[2]), textY);
    }

    public BufferedImage composeOverlay(BufferedImage heatmap) {
        return composeChunkHeatmap(heatmap);
    }

    /** How to draw the plain map, and each layer, in tiles when zoomed in close. */
    public void setTileRenderers(MapTiles.Renderer base, java.util.Map<Layer, MapTiles.Renderer> layers) {
        this.baseTiles = base;
        layerTiles.putAll(layers);
        repaint();
    }

    /** How to draw the overlay now shown in tiles (null for none); a name tells overlays apart. */
    public void setOverlayTiles(MapTiles.Renderer renderer, String name) {
        this.overlayTiles = renderer;
        this.overlayTileName = name;
        repaint();
    }

    /** A sharper version of the plain map, used once the map is zoomed in. */
    public void setBaseMapDetail(BufferedImage detail) {
        this.baseMapDetail = detail;
        repaint();
    }

    /** A layer's images, {overview, detail}, once drawn. */
    public void setLayerImages(Layer layer, BufferedImage[] images) {
        layerImages.put(layer, images);
        repaint();
    }

    public void setLayerShown(Layer layer, boolean shown) {
        if (shown) shownLayers.add(layer);
        else shownLayers.remove(layer);
        repaint();
    }

    public boolean isLayerShown(Layer layer) {
        return shownLayers.contains(layer);
    }

    /** Where the nations' names are written: each {name as an image, worldX, worldZ, area, whether it's the main one, flag (or null)}. */
    public void setNationLabels(List<Object[]> labels) {
        List<Object[]> placed = new ArrayList<>();
        for (Object[] label : labels) {
            float u = ((Float) label[1] + halfRegion) / totalRegionWidth;
            float v = ((Float) label[2] + halfRegion) / totalRegionWidth;
            placed.add(new Object[] { label[0], u, v, label[4], label.length > 5 ? label[5] : null });
        }
        this.nationLabels = placed;
        repaint();
    }

    /**
     * Sets up (or sets up afresh) the tick boxes beside the enlarged map, listing these layers
     * and overlays; with none of either, there are none.
     */
    public void setLayerChoices(List<Layer> layers, java.util.LinkedHashMap<String, List<String>> gradients, String chosen,
                                java.util.function.Consumer<String> onGradient, java.util.function.Function<String, java.awt.Image> icons) {
        if (layersPanel != null && layersPanel.getParent() != null) {
            java.awt.Container parent = layersPanel.getParent();
            parent.remove(layersPanel);
            parent.repaint();
        }
        boolean any = !layers.isEmpty() || gradients.values().stream().anyMatch(g -> !g.isEmpty());
        layersPanel = any ? new MapLayersPanel(this::setLayerShown, layers, shownLayers, gradients, chosen, onGradient, icons) : null;
        if (getParent() instanceof JLayeredPane) XenoGuesser.updateMinimapBounds((JLayeredPane) getParent(), this);
    }

    /** The tick-box panel, shown beside the map while it's enlarged; null if there's nothing to list. */
    public MapLayersPanel getLayersPanel() {
        return layersPanel;
    }

    private BufferedImage composeChunkHeatmap(BufferedImage heatmap) {
        int baseW = mapImage.getWidth();
        int baseH = mapImage.getHeight();

        BufferedImage combinedImage = new BufferedImage(baseW, baseH, BufferedImage.TYPE_INT_RGB);

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
                    
                    int colour = heatmap.getRGB(hx, hy);
                    combinedImage.setRGB(x, y, (colour >>> 24) == 0 ? baseColour : colour);
                }
            }
        }
        return combinedImage;
    }


    private void startRevealSequence() {
        this.isGuessed = true;
        // The next round can be got ready behind the results from now on
        this.prepareRequested = true;
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
        this.scoreBanked = false;


        if (getParent() != null) {
            int parentHeight = getParent().getHeight();
            int parentWidth = getParent().getWidth();
            int tall = Math.min(750, Math.max(300, parentHeight - 240));
            this.currentMapSize = Math.min(Math.max(300, parentWidth - 80), Math.round(tall / Planet.aspect()));
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
                    
                    bankScore();
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
        float x = ((spawnX + halfRegion) / totalRegionWidth) * mapImage.getWidth();
        float y = ((spawnZ + halfRegion) / totalRegionWidth) * mapImage.getHeight();
        if (isGuessed) {
            pendingGoal = new float[] { x, y };
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
            this.visualMapY = Math.max(20, (getHeight() - mapHeight()) / 2 - 45);
            
            this.btnWidth = currentMapSize;
            this.btnHeight = 40;
            
            this.noteBtnWidth = btnWidth;
            this.noteBtnHeight = 55; 
            this.noteBtnX = visualMapX;
            this.noteBtnY = visualMapY + mapHeight() + BORDER_SIZE + 15; 
            
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
            int panelHeight = mapHeight() + (BORDER_SIZE * 2) + bottomSpace;
            
            this.setPreferredSize(new Dimension(panelWidth, panelHeight));
            
            this.visualMapX = BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET;
            this.visualMapY = BORDER_SIZE;
            
            this.btnWidth = panelWidth - 24;
            this.btnHeight = 40;
            this.btnX = (panelWidth - btnWidth) / 2;
            this.btnY = mapHeight() + (BORDER_SIZE * 2) + 7;

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

            int mapFrameH = mapHeight() + (BORDER_SIZE * 2);
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

            // The land and sea, plain or coloured by the gradient map chosen; then whatever's ticked
            // (the results show just the land and sea)
            boolean plain = isFullScreenReveal;
            BufferedImage gradient = plain ? null : this.gradientImage;
            BufferedImage mapSource = gradient != null ? gradient : mapImage;
            if (gradient == null && zoom >= DETAIL_SWITCH_ZOOM && baseMapDetail != null) {
                mapSource = baseMapDetail;
            }
            Shape frameClip = g2d.getClip();
            g2d.clipRect(mapX, mapY, currentMapSize, mapHeight());
            Object oldInterpolation = g2d.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            drawWholeMap(g2d, mapSource, mapX, mapY);
            tiles.beginFrame();
            if (gradient == null) drawTiles(g2d, "base", baseTiles, mapX, mapY, WHOLE_IMAGE_DETAIL, null);
            else if (overlayTiles != null) drawTiles(g2d, "overlay-" + overlayTileName, overlayTiles, mapX, mapY, gradient.getWidth(), null);
            for (Layer layer : Layer.values()) {
                BufferedImage[] images = layerImages.get(layer);
                if (images == null || !shownLayers.contains(layer) || plain) continue;
                BufferedImage whole = zoom >= DETAIL_SWITCH_ZOOM ? images[1] : images[0];
                // (see-through, so the whole image only where its tile isn't ready)
                if (!drawTiles(g2d, layer.name(), layerTiles.get(layer), mapX, mapY, images[1].getWidth(), whole)) {
                    drawWholeMap(g2d, whole, mapX, mapY);
                }
            }
            tiles.endFrame();
            if (oldInterpolation != null) g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
            drawGraticule(g2d, mapX, mapY);
            if (showNationNames && !plain) drawNationNames(g2d, mapX, mapY);
            g2d.setClip(frameClip);

            if (zoom > 1.01f) {
                String zoomLabel = String.format("%.1fx", zoom);
                g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 12f));
                FontMetrics zoomFm = g2d.getFontMetrics();
                int zoomW = zoomFm.stringWidth(zoomLabel) + 12;
                int zoomH = zoomFm.getAscent() + 8;
                int zoomX = mapX + currentMapSize - zoomW - 8;
                int zoomY = mapY + mapHeight() - zoomH - 8;
                g2d.setColor(new Color(25, 25, 27, 195));
                g2d.fillRoundRect(zoomX, zoomY, zoomW, zoomH, 8, 8);
                g2d.setColor(Color.WHITE);
                g2d.drawString(zoomLabel, zoomX + 6, zoomY + zoomFm.getAscent() + 3);
            }

            String heatmapName = plain ? null : this.gradientName;
            if (heatmapName != null) {
                {
                    g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 13f));
                    FontMetrics labelFm = g2d.getFontMetrics();
                    int textW = labelFm.stringWidth(heatmapName);
                    int textH = labelFm.getAscent();

                    int padX = 12;
                    int padY = 6;
                    java.awt.Image picture = overlayPicture;
                    int pictureSize = picture != null ? 34 : 0;
                    int boxW = textW + (padX * 2) + (picture != null ? pictureSize + 6 : 0);
                    int boxH = Math.max(textH + (padY * 2), pictureSize + 6);
                    
                    int boxX = mapX + (currentMapSize - boxW) / 2;
                    int boxY = mapY + 12;

                    g2d.setColor(new Color(25, 25, 27, 195));
                    g2d.fillRoundRect(boxX, boxY, boxW, boxH, 10, 10);

                    g2d.setColor(new Color(255, 255, 255, 45));
                    g2d.drawRoundRect(boxX, boxY, boxW, boxH, 10, 10);

                    g2d.setColor(Color.WHITE);
                    if (picture != null) {
                        g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        g2d.drawImage(picture, boxX + padX - 4, boxY + (boxH - pictureSize) / 2, pictureSize, pictureSize, null);
                    }
                    int textX = boxX + padX + (picture != null ? pictureSize + 6 - 4 : 0);
                    g2d.drawString(heatmapName, textX, boxY + (boxH + textH) / 2 - 2);
                    // (only on the enlarged map: the small one is too cramped)
                    if (isLarge || isFullScreenReveal) drawLegend(g2d, mapX + currentMapSize / 2, boxY + boxH + 6);
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
                    if (drawX < mapX || drawX > mapX + currentMapSize || drawY < mapY || drawY > mapY + mapHeight()) {
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
                        int limitBottom = mapY + mapHeight() - 5;

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
                g2d.clipRect(mapX, mapY, currentMapSize, mapHeight());

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

                    g2d.setColor(resultsReady ? btnNextRoundBlue : btnDisabledGrey);
                    g2d.fillRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight);

                    g2d.setColor(new Color(255, 255, 255, resultsReady ? 60 : 30));
                    g2d.setStroke(new BasicStroke(1.5f));
                    g2d.drawRoundRect(btnX, btnY, btnWidth, btnHeight, btnHeight, btnHeight);
                    g2d.setStroke(originalStroke);

                    g2d.setColor(btnTextWhite);
                    g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 20f));
                    FontMetrics fm = g2d.getFontMetrics();
                    String endText = proceedLabel != null ? proceedLabel.get() : "Proceed to Shop";
                    g2d.drawString(endText, btnX + (btnWidth - fm.stringWidth(endText)) / 2, btnY + (btnHeight + fm.getAscent()) / 2 - 2);
                }

                // How the guess was paid: its points as productivity, and the salary plus half of them as cash
                if (currentPhase != RevealPhase.SHOW_PLAYER_PIN) drawReckoning(g2d, mapX, mapY);

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
                        int centerMapY = mapY + (mapHeight() / 2);
                        
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
        this.currentMapSize = 210;
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
    /**
     * Resizes the map for the space the window now has (it's laid out again whenever the window
     * changes size, or goes between fullscreen and windowed): the enlarged map and the results'
     * map are sized from it; the small map stays as it is.
     */
    public void fitToWindow(int width, int height) {
        int size;
        if (isFullScreenReveal) {
            int tall = Math.min(750, Math.max(300, height - 240));
            size = Math.min(Math.max(300, width - 80), Math.round(tall / Planet.aspect()));
        } else if (isLarge) {
            size = Math.min((int) (height * 0.60f / Planet.aspect()), (int) (width * 0.55f));
        } else {
            return;
        }
        if (size == currentMapSize || size <= 0) return;
        currentMapSize = size;
        updateGeometryLayouts();
        repaint();
        if (onSizeChanged != null) onSizeChanged.run();
    }

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
            case SMALL -> 210;
            case LARGE -> Math.min((int) (parentHeight * 0.60f / Planet.aspect()),
                    getParent() != null ? (int) (getParent().getWidth() * 0.55f) : 900);
        };
        if (!isLarge) resetZoom();
        updateGeometryLayouts();
        triggerParentLayoutUpdate();
        repaint();
        if (onSizeChanged != null) onSizeChanged.run();
    }

    // What the results' button says (it can lead to the quarter's end rather than the shop)
    private java.util.function.Supplier<String> proceedLabel;

    public void setProceedLabel(java.util.function.Supplier<String> label) {
        this.proceedLabel = label;
    }

    /** What opens the shop when the results' button is pressed. */
    public void setOnProceedToShop(Runnable onProceedToShop) {
        this.onProceedToShop = onProceedToShop;
    }

    /** The results' button: the round's points go onto the score at once (should they still be flying there) and the shop opens. */
    private void proceedToShop() {
        if (!scoreBanked) {
            if (revealTimer != null) revealTimer.stop();
            currentDisplayScore = targetRoundScore;
            shouldDrawScoreText = false;
            bankScore();
            repaint();
        }
        if (onProceedToShop != null) onProceedToShop.run();
    }

    private void bankScore() {
        if (scoreBanked) return;
        scoreBanked = true;
        if (gameHUD != null) gameHUD.addRound(targetRoundScore);
    }

    /**
     * Asks for the next round (the shop has been left), once the shop has had a moment to show
     * it's waiting; the results close once it's ready.
     */
    public void requestNextRound() {
        if (roundLoading) return;
        roundLoading = true;
        javax.swing.Timer delay = new javax.swing.Timer(80, e -> nextRoundRequested = true);
        delay.setRepeats(false);
        delay.start();
    }

    /** Called once the next round's surroundings are built: closes the results and starts the round. */
    public void finishRoundLoading() {
        roundLoading = false;
        if (gameHUD != null) {
            gameHUD.advanceRound();
        }
        resetMapState();
    }

    /** Developer aid: a guess at the middle of the map, as if clicked and confirmed. */
    public void devGuess() {
        pinX = mapImage.getWidth() / 2;
        pinY = mapImage.getHeight() / 2;
        hasPin = true;
        startRevealSequence();
    }

    /** Developer aid: Proceed to Shop, as if pressed. */
    public void devProceedToShop() {
        if (isFullScreenReveal && currentPhase != RevealPhase.SHOW_PLAYER_PIN && !roundLoading) proceedToShop();
    }

    // Set when a guess is made, so the next round starts being built straight away
    private volatile boolean prepareRequested;

    /** Whether a guess has just been made (and the next round may be built); asking clears it. */
    public boolean takePrepareRequest() {
        boolean wanted = prepareRequested;
        prepareRequested = false;
        return wanted;
    }

    public boolean isNextRoundRequested() {
        return nextRoundRequested;
    }

    public void clearNextRoundRequest() {
        this.nextRoundRequested = false;
    }

    /**
     * Lines of latitude and longitude every 30 degrees (the equator a little stronger): the
     * map is a Mercator chart of the planet, so the parallels spread out towards the poles.
     */
    private void drawGraticule(Graphics2D g2d, int mapX, int mapY) {
        float span = zoom * currentMapSize;
        float originX = mapX - viewLeft() * span, originY = mapY - viewTop() * span;
        java.awt.Stroke old = g2d.getStroke();
        g2d.setStroke(new BasicStroke(1f));
        for (int lon = -150; lon <= 150; lon += 30) {
            float fx = (float) ((Planet.chartX(Math.toRadians(lon)) + halfRegion) / totalRegionWidth);
            int px = Math.round(originX + fx * span);
            g2d.setColor(new Color(255, 255, 255, lon == 0 ? 70 : 38));
            g2d.drawLine(px, mapY, px, mapY + mapHeight());
        }
        for (int lat = -80; lat <= 80; lat += lat == -80 || lat == 60 ? 20 : 30) {
            float fy = (float) ((Planet.chartZ(Math.toRadians(lat)) + halfRegion) / totalRegionWidth);
            int py = Math.round(originY + fy * span);
            g2d.setColor(new Color(255, 255, 255, lat == 0 ? 70 : 38));
            g2d.drawLine(mapX, py, mapX + currentMapSize, py);
        }
        g2d.setStroke(old);
    }

    private void calculateAndApplyScore() {
        // The map is a Mercator chart of the planet: where each point is on it, in world units
        float mapWidthPixels = mapImage.getWidth();
        float mapHeightPixels = mapImage.getHeight();
        double pinWorldX = (pinX / mapWidthPixels - 0.5) * totalRegionWidth, pinWorldZ = (pinY / mapHeightPixels - 0.5) * totalRegionWidth;
        double goalWorldX = (goalX / mapWidthPixels - 0.5) * totalRegionWidth, goalWorldZ = (goalY / mapHeightPixels - 0.5) * totalRegionWidth;
        // How far apart they really are: the shortest way round the sphere's surface
        double realWorldDistance = Planet.surfaceDistance(pinWorldX, pinWorldZ, goalWorldX, goalWorldZ);

        double kConstant = Planet.greatestDistance() / 7.0;
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

    /**
     * Once the map is shown bigger than a whole image's own detail, the tiles in view drawn over
     * it at the detail they're seen at. Where one isn't ready yet the whole image shows instead:
     * already drawn underneath, or given as under, drawn there just for its patch. False (and
     * nothing drawn) if the map isn't shown big enough for tiles.
     */
    private boolean drawTiles(Graphics2D g2d, String source, MapTiles.Renderer renderer, int mapX, int mapY, int wholeDetail,
                              BufferedImage under) {
        float across = zoom * currentMapSize;
        if (renderer == null || across <= wholeDetail * 1.1f) return false;
        int level = Math.max(1, Math.min(8, (int) Math.ceil(Math.log(across / MapTiles.SIZE) / Math.log(2))));
        int n = 1 << level;
        float u0 = viewLeft(), v0 = viewTop(), u1 = u0 + 1f / zoom, v1 = v0 + Planet.aspect() / zoom;
        int tx0 = Math.max(0, (int) Math.floor(u0 * n)), tx1 = Math.min(n - 1, (int) Math.floor(u1 * n));
        int ty0 = Math.max(0, (int) Math.floor(v0 * n)), ty1 = Math.min(n - 1, (int) Math.floor(v1 * n));
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                BufferedImage tile = tiles.get(source, renderer, level, tx, ty);
                int x0 = mapX + (int) Math.floor(((float) tx / n - u0) * across), x1 = mapX + (int) Math.floor(((float) (tx + 1) / n - u0) * across);
                int y0 = mapY + (int) Math.floor(((float) ty / n - v0) * across), y1 = mapY + (int) Math.floor(((float) (ty + 1) / n - v0) * across);
                if (tile != null) {
                    g2d.drawImage(tile, x0, y0, x1 - x0, y1 - y0, null);
                } else if (under != null) {
                    Shape clip = g2d.getClip();
                    g2d.clipRect(x0, y0, x1 - x0, y1 - y0);
                    drawWholeMap(g2d, under, mapX, mapY);
                    g2d.setClip(clip);
                }
            }
        }
        return true;
    }

    /** Draws an image covering the whole map region, scaled and placed as the map is viewed. */
    private void drawWholeMap(Graphics2D g2d, BufferedImage image, int mapX, int mapY) {
        AffineTransform transform = new AffineTransform();
        transform.translate(mapX - viewLeft() * zoom * currentMapSize, mapY - viewTop() * zoom * currentMapSize);
        transform.scale(zoom * currentMapSize / image.getWidth(), zoom * currentMapSize / image.getHeight());
        g2d.drawImage(image, transform, null);
    }

    /**
     * Each nation's name, in its own writing, over the middle of its largest land, all at one
     * size; and smaller on each of its islands once the map is zoomed in, at a size readable
     * for the zoom. Written again a map's width away near the join.
     */
    private void drawNationNames(Graphics2D g2d, int mapX, int mapY) {
        float span = zoom * currentMapSize;
        float originX = mapX - viewLeft() * span, originY = mapY - viewTop() * span;
        // Islands' names only once zoomed in, growing with the zoom to stay readable
        boolean islands = zoom >= 1.8f;
        Object oldInterpolation = g2d.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // Main names first; a small one is left out where it would overlap another name
        List<Rectangle> taken = new ArrayList<>();
        List<Object[]> ordered = new ArrayList<>(nationLabels);
        ordered.sort((a, b) -> Boolean.compare((Boolean) b[3], (Boolean) a[3]));
        for (Object[] label : ordered) {
            boolean main = (Boolean) label[3];
            if (!main && !islands) continue;
            BufferedImage name = (BufferedImage) label[0];
            // The glyphs' height on screen
            float glyph = main ? (currentMapSize < 300 ? 11f : 17f) : Math.min(14f, 6f + zoom * 1.5f);
            float scale = glyph / NATION_NAME_GLYPH;
            int w = Math.round(name.getWidth() * scale), h = Math.round(name.getHeight() * scale);
            for (int copy = -1; copy <= 1; copy++) {
                int x = Math.round(originX + ((Float) label[1] + copy) * span - w * 0.5f);
                int y = Math.round(originY + (Float) label[2] * span - h * 0.5f);
                // Only the copy whose middle is on the map; partly off the edge, it's slid back
                // on so it can be read whole
                int middleX = x + w / 2, middleY = y + h / 2;
                if (middleX < mapX || middleX >= mapX + currentMapSize || middleY < mapY || middleY >= mapY + mapHeight()) continue;
                int pad0 = 6;
                x = Math.max(mapX + pad0, Math.min(mapX + currentMapSize - w - pad0, x));
                y = Math.max(mapY + pad0, Math.min(mapY + mapHeight() - h - pad0, y));
                int pad = Math.max(3, Math.round(glyph * 0.3f));
                // The nation's flag over its main name (under it, should it not fit above); beside
                // it instead where the name's written downwards (taller than it's wide)
                BufferedImage flag = main ? (BufferedImage) label[4] : null;
                int flagH = Math.round(glyph * 1.7f), flagW = flag == null ? 0 : Math.round(flagH * flag.getWidth() / (float) flag.getHeight());
                int flagX, flagY;
                if (h > w) {
                    flagX = x - pad - 4 - flagW;
                    if (flagX < mapX + 2) flagX = x + w + pad + 4;
                    flagY = y + (h - flagH) / 2;
                } else {
                    flagX = x + (w - flagW) / 2;
                    flagY = y - pad - 4 - flagH;
                    if (flagY < mapY + 2) flagY = y + h + pad + 4;
                }
                Rectangle box = new Rectangle(x - 5, y - 5, w + 10, h + 10);
                if (flag != null) box.add(new Rectangle(flagX - 2, flagY - 2, flagW + 4, flagH + 4));
                if (!main && taken.stream().anyMatch(box::intersects)) continue;
                taken.add(box);
                if (flag != null) {
                    g2d.drawImage(flag, flagX, flagY, flagW, flagH, null);
                    g2d.setColor(new Color(30, 30, 34, 200));
                    g2d.drawRect(flagX, flagY, flagW - 1, flagH - 1);
                }
                Object oldAntialias = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(new Color(40, 40, 44, 150));
                g2d.fillRoundRect(x - pad, y - pad, w + pad * 2, h + pad * 2, pad * 2, pad * 2);
                if (oldAntialias != null) g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialias);
                g2d.drawImage(name, x, y, w, h, null);
            }
        }
        if (oldInterpolation != null) g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
    }

    /**
     * Under the top of the results' map: the guess's points going to productivity, and the cash
     * it earns worked out (the monthly salary plus half the points).
     */
    private void drawReckoning(Graphics2D g2d, int mapX, int mapY) {
        int points = targetRoundScore, cash = GameHUD.cashFor(points);
        String[] lines = {
            String.format("Productivity: +%,d", points),
            String.format("Cash: ($%,d salary + 1/2 x $%,d points) = +$%,d", GameHUD.SALARY, points, cash)
        };
        Font font = HudStyle.font(Font.BOLD, 15f);
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();
        int w = 0;
        for (String line : lines) w = Math.max(w, fm.stringWidth(line));
        int padX = 14, padY = 9, lineH = fm.getHeight();
        int boxW = w + padX * 2, boxH = lineH * lines.length + padY * 2;
        int x = mapX + (currentMapSize - boxW) / 2, y = mapY + 12;
        g2d.setColor(new Color(25, 25, 27, 215));
        g2d.fillRoundRect(x, y, boxW, boxH, 12, 12);
        g2d.setColor(new Color(255, 255, 255, 50));
        g2d.drawRoundRect(x, y, boxW, boxH, 12, 12);
        Color[] colours = { new Color(150, 220, 255), new Color(120, 230, 140) };
        for (int i = 0; i < lines.length; i++) {
            g2d.setColor(colours[i]);
            g2d.drawString(lines[i], x + padX, y + padY + fm.getAscent() + i * lineH);
        }
    }

    // The height of one glyph in a nation-name image, in its pixels
    public static final int NATION_NAME_GLYPH = 28;
}