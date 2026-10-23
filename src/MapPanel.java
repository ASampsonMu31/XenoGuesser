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
        CONTOURS("Contours", false), RIVERS("Rivers", false), ROADS("Roads", true), BUILDINGS("Buildings", true), SHOPS("Shops", true);

        public final String label;
        // Listed in the markings' Infrastructure folder (M47), with the maps of pictures
        public final boolean infrastructure;

        Layer(String label, boolean infrastructure) {
            this.label = label;
            this.infrastructure = infrastructure;
        }
    }

    // Each layer's {overview, detail} images (transparent), once drawn, and which are ticked
    private final java.util.Map<Layer, BufferedImage[]> layerImages = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Layer> shownLayers = java.util.Collections.synchronizedSet(java.util.EnumSet.noneOf(Layer.class));
    // Where each nation's names go (u, v: 0..1 across the map image)
    private volatile List<NationLabel> nationLabels = List.of();
    // Whether the nations' names are written over the map (with the nations overlay)
    private volatile boolean showNationNames;
    // Pictures stood on the plain map instead of an overlay's colours (the bollard map: each
    // nation's bollard on its main land, no borders), each at its u, v; null when none
    private volatile List<MapPicture> mapPictures;
    // (and that map's name, shown with the heatmap's)
    private volatile String picturesName;
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
    // Where the player really is (the goal) and where they guess (the pin), as directions from
    // the planet's centre; and the same in map pixels, fractional (exactly where, however far
    // the map is zoomed), for drawing them. The next round's goal waits for the results to close.
    private GeodesicChunkGrid.Direction pendingGoal;
    private GeodesicChunkGrid.Direction goal;
    private float goalX;
    private float goalY;

    // Where the last round was (its answer, in the map image's pixels), once a round has been
    // played: marked on the map through the next round
    private GeodesicChunkGrid.Direction lastRound;
    private float lastRoundX, lastRoundY;
    private static final Color LAST_ROUND_COLOUR = new Color(255, 170, 40);

    private boolean hasPin = false;
    private GeodesicChunkGrid.Direction pin;
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
        // Multiplayer: guessed, waiting for the partner's guess before the reveal starts (M16)
        WAITING_FOR_PARTNER,
        SHOW_PLAYER_PIN,
        // Multiplayer (M14): resting on the partner's pin, then turning to the answer, and then
        // both lines drawn, each guess's to the answer
        SHOW_PARTNER_PIN,
        DRAW_LINES,
        SHOW_ALL_RESULTS,
        LINGER,
        SLAM_TO_HUD
    }
    private RevealPhase currentPhase = RevealPhase.SHOW_PLAYER_PIN;
    private Timer revealTimer;
    // Multiplayer: the partner's guess this round, once it's in (null in singleplayer), and the
    // timer that waits for it
    private Multiplayer.Guess partnerGuess;
    private GeodesicChunkGrid.Direction partnerPin;
    private Timer partnerWait;

    // The round's clock (M41): in singleplayer a minute to guess in from the round's start; in
    // multiplayer none until the partner guesses, then AFTER_PARTNER_MS more. Run out, the round
    // goes unguessed: no points, the salary alone, and only the answer (and any partner's guess)
    // shown. Counts only while the round's in play (and, in singleplayer, the settings shut).
    public static final long SINGLE_GUESS_MS = 120_000L, AFTER_PARTNER_MS = 20_000L;
    private long guessMsLeft = -1L, guessMsTotal;
    private long clockTicked;
    private boolean partnerGuessedFirst;
    // This round went unguessed (the clock ran out): no pin
    private boolean missedGuess;

    /** The round's clock as shown: {ms left, ms it started with, 1 if the partner's guessed already}; null when none is running. */
    // Developer aid (filming the trailer): the round's clock held at this many ms, not counting down
    static volatile boolean devClockHeld;
    static volatile long devClockFrom = -1L;

    public long[] guessClock() {
        if (guessMsLeft < 0L || isGuessed) return null;
        return new long[] { Math.max(0L, guessMsLeft), guessMsTotal, partnerGuessedFirst ? 1L : 0L };
    }

    /** A tenth of a second of the round's clock: started, run down, and run out into an unguessed round. */
    private void tickClock() {
        // (by the world's time, so a trailer filmed a frame at a time counts the clock down as it's seen)
        long now = GameClock.millis();
        long dt = clockTicked == 0L ? 0L : now - clockTicked;
        clockTicked = now;
        if (devClockHeld) {
            guessMsLeft = devClockFrom;
            guessMsTotal = SINGLE_GUESS_MS;
            return;
        }
        if (isGuessed || mainApp == null || gameHUD == null || !mainApp.roundInPlay()) return;
        Multiplayer session = Multiplayer.active();
        if (guessMsLeft < 0L) {
            if (session == null) {
                guessMsLeft = guessMsTotal = SINGLE_GUESS_MS;
            } else if (session.remoteGuess(gameHUD.getRound()) != null) {
                // (the partner's guessed: this player has a little longer)
                guessMsLeft = guessMsTotal = AFTER_PARTNER_MS;
                partnerGuessedFirst = true;
            }
            return;
        }
        // (singleplayer's held with the settings open, and for January's instructions)
        if (session == null && mainApp.clockHeld()) return;
        guessMsLeft -= dt;
        if (guessMsLeft <= 0L) {
            guessMsLeft = 0L;
            runOutOfTime();
        }
    }

    /**
     * The clock's run out: a marker put down (but not sent) is guessed where it was last put, so
     * it may still score; with none, the round's unguessed.
     */
    private void runOutOfTime() {
        if (isGuessed) return;
        if (isTypingNote) isTypingNote = false;
        if (!(hasPin && pin != null)) {
            missedGuess = true;
            pin = null;
            hasPin = true;
        }
        startRevealSequence();
    }
    // How long the lines are drawn for once the globe has reached the answer (multiplayer)
    private static final long LINES_MS = 900;

    /** Whether the results are up (the score counted, the buttons usable): after the reveal's turns. */
    private boolean resultsShown() {
        return currentPhase == RevealPhase.SHOW_ALL_RESULTS || currentPhase == RevealPhase.LINGER || currentPhase == RevealPhase.SLAM_TO_HUD;
    }
    
    private float lineProgress = 0.0f;

    // The results are shown on a globe (see RevealGlobe): first centred on the guess for a
    // moment, then turning to the answer as the line between them is drawn along the great
    // circle. How long it rests on the guess, and how long the turn takes (more the further)
    private RevealGlobe globe;
    // The map (small and enlarged; starts flat) and the results (start on a globe) each shown on a
    // globe or flat, toggled by a button in the map's corner (M41); the map's own globe, wrapped in
    // what the flat map shows (the overlay and the layers ticked: see globeTexture)
    private boolean mapGlobe = false, revealGlobe = true;
    private RevealGlobe mapGlobeView;
    private BufferedImage globeTexture;
    private List<Object> globeTextureKey = List.of();
    private static final int TOGGLE_W = 92, TOGGLE_H = 26;
    private long revealStartTime;
    private static final long GLOBE_HOLD_MS = 700, GLOBE_TURN_MIN_MS = 900, GLOBE_TURN_MAX_MS = 2200;
    // The results' view's height over its width (the globe and the rod through it want something squarer than the chart)
    private static final float REVEAL_ASPECT = 0.72f;

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
        this.globe = new RevealGlobe(totalRegionWidth);
        this.mapGlobeView = new RevealGlobe(totalRegionWidth, 0.44);
        // (looking at the middle of the chart to begin with)
        this.mapGlobeView.centreOn(new GeodesicChunkGrid.Direction(1, 0, 0));
        // (developer aid: -Dxenoguesser.flatmaps starts both flat)
        if (Dev.flag("xenoguesser.flatmaps")) mapGlobe = revealGlobe = false;
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
                // The globe/flat toggle
                if (e.getButton() == MouseEvent.BUTTON1 && toggleBounds().contains(clickX, clickY)) {
                    toggleGlobe();
                    return;
                }
                
                if (isLarge || isFullScreenReveal) {
                    int mapLeft = (visualMapX != 0) ? visualMapX : (BORDER_SIZE + HORIZONTAL_SHUFFLE_OFFSET);
                    int mapTop = (visualMapY != 0) ? visualMapY : BORDER_SIZE;
                    for (int i = savedNotes.size() - 1; i >= 0; i--) {
                        MapNote note = savedNotes.get(i);
                        int[] at = noteScreen(note, mapLeft, mapTop);
                        if (at == null) continue;
                        int nx = at[0];
                        int ny = at[1];

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
                                // (the whole width: it's kept when the shop's opened, or with Enter)
                                noteArea.setBounds(noteBtnX, noteBtnY, noteBtnWidth, noteBtnHeight);
                                noteArea.setVisible(true);
                                noteArea.requestFocusInWindow();
                                MapPanel.this.repaint();
                            }
                            return; 
                        }

                        if (clickX >= btnX && clickX <= (btnX + btnWidth) && clickY >= btnY && clickY <= (btnY + btnHeight)) {
                                                        boolean resultsReady = resultsShown();
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
                        if (globeShown()) activeGlobe().beginDrag();
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
                        if (MapPanel.this.listener != null) {
                            GeodesicChunkGrid.Direction under = directionUnder(clickX, clickY);
                            if (under != null) MapPanel.this.listener.setTelepot(under);
                        }
                    }
                }
            }
        });

        this.addMouseWheelListener(this::handleMouseWheel);
        Timer clock = new Timer(100, e -> tickClock());
        clock.start();
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
                    // (on the globe, where the click is on the ball; off it, nothing)
                    GeodesicChunkGrid.Direction under = directionUnder(e.getX(), e.getY());
                    if (under != null) {
                        float[] p = directionPixel(under);
                        placePin(p[0], p[1]);
                    }
                    repaint();
                }
            }
        });
        this.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                hoverLandmark(Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2);
            }
        });
        this.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hoverLandmark(e.getX(), e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragFrom == null || !(isLarge || isFullScreenReveal)) return;
                int dx = e.getX() - dragFrom.x, dy = e.getY() - dragFrom.y;
                if (!dragging && dx * dx + dy * dy < DRAG_THRESHOLD * DRAG_THRESHOLD) return;
                dragging = true;
                if (globeShown()) {
                    // The globe turns under the pointer (the results' once it has finished turning to the answer)
                    if (isFullScreenReveal && !resultsShown()) return;
                    setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
                    activeGlobe().dragTo(dx, dy, currentMapSize, mapHeight());
                    repaint();
                    return;
                }
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
        GeodesicChunkGrid.Direction under = directionUnder(clickX, clickY);
        if (under != null) listener.setTelepot(under);
    }

    /** The direction from the planet's centre under a point of the map image, in its pixels. */
    private GeodesicChunkGrid.Direction pixelDirection(float x, float y) {
        return MapProjection.direction(x / mapImage.getWidth() * totalRegionWidth - halfRegion,
                y / mapImage.getHeight() * totalRegionWidth - halfRegion);
    }

    /** Where a direction from the planet's centre is on the map image, {x, y} in its pixels. */
    private float[] directionPixel(GeodesicChunkGrid.Direction d) {
        double[] map = MapProjection.toMap(d);
        return new float[] { (float) ((map[0] + halfRegion) / totalRegionWidth * mapImage.getWidth()),
                (float) ((map[1] + halfRegion) / totalRegionWidth * mapImage.getHeight()) };
    }

    /** Where a note's marker is on screen, {x, y}; null when it's round the back of the results' globe. */
    private int[] noteScreen(MapNote note, int mapLeft, int mapTop) {
        double[] p = screenOf(pixelDirection(note.coreX, note.coreY), mapLeft, mapTop);
        return p[2] < 0.0 ? null : new int[] { (int) Math.round(p[0]), (int) Math.round(p[1]) };
    }

    /** Puts the guess's pin at a point of the map image, in its pixels. */
    private void placePin(float x, float y) {
        pinX = x;
        pinY = y;
        pin = pixelDirection(x, y);
        hasPin = true;
    }

    private void setGoal(GeodesicChunkGrid.Direction d) {
        goal = d;
        float[] p = directionPixel(d);
        goalX = p[0];
        goalY = p[1];
        // (developer aid: where the answer is, as shares of the chart's width and height, for scripted play)
        if (Dev.ENABLED) System.out.printf(java.util.Locale.ROOT, "[MAPGOAL] %.4f %.4f%n", goalX / mapImage.getWidth(), goalY / mapImage.getHeight());
    }

    /** Whether what's shown now is on a globe (the results' or the map's own), rather than the flat chart. */
    private boolean globeShown() {
        return isFullScreenReveal ? revealGlobe : mapGlobe;
    }

    /** The globe shown now (see globeShown). */
    private RevealGlobe activeGlobe() {
        return isFullScreenReveal ? globe : mapGlobeView;
    }

    /** Where a direction from the planet's centre is on screen, {x, y, depth}: depth below 0 when hidden round the back of the globe. */
    private double[] screenOf(GeodesicChunkGrid.Direction d, int mapX, int mapY) {
        if (globeShown()) return activeGlobe().project(d, mapX, mapY, currentMapSize, mapHeight());
        float[] p = directionPixel(d);
        return new double[] { coreToScreenX(p[0], mapX), coreToScreenY(p[1], mapY), 1.0 };
    }

    /**
     * Where a point of the map (u, v: 0 to 1 across and down the square image) is on screen; null
     * where it's hidden (round the back of the globe); copy is the flat chart's repeat east or west
     * (-1, 0, 1: only 0 on the globe).
     */
    private float[] uvScreen(float u, float v, int copy, int mapX, int mapY) {
        if (globeShown()) {
            if (copy != 0) return null;
            double[] p = activeGlobe().project(pixelDirection(u * mapImage.getWidth(), v * mapImage.getHeight()), mapX, mapY, currentMapSize, mapHeight());
            return p[2] < 0.0 ? null : new float[] { (float) p[0], (float) p[1] };
        }
        float span = zoom * currentMapSize;
        return new float[] { mapX - viewLeft() * span + (u + copy) * span, mapY - viewTop() * span + v * span };
    }

    /** The direction under a point on screen: on the globe, null off it. */
    private GeodesicChunkGrid.Direction directionUnder(int sx, int sy) {
        if (globeShown()) return activeGlobe().unproject(sx, sy, visualMapX, visualMapY, currentMapSize, mapHeight());
        return pixelDirection(screenToCoreX(sx, visualMapX), screenToCoreY(sy, visualMapY));
    }

    /** A great circle's arc, as far as progress of the way, on the globe or the flat chart (broken where it crosses the chart's edge). */
    private void drawArcAnyway(Graphics2D g2d, GeodesicChunkGrid.Direction a, GeodesicChunkGrid.Direction b, double progress, int mapX, int mapY, Color colour) {
        if (globeShown()) {
            activeGlobe().drawArc(g2d, a, b, progress, mapX, mapY, currentMapSize, mapHeight(), colour == null ? Color.WHITE : colour);
            return;
        }
        if (progress <= 0.0) return;
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, a.dot(b))));
        int steps = Math.max(2, (int) Math.ceil(Math.toDegrees(angle) * progress * 2));
        java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
        double[] last = null;
        for (int i = 0; i <= steps; i++) {
            double[] p = screenOf(RevealGlobe.along(a, b, progress * i / steps), mapX, mapY);
            if (last == null || Math.abs(p[0] - last[0]) > currentMapSize * zoom * 0.5) path.moveTo(p[0], p[1]);
            else path.lineTo(p[0], p[1]);
            last = p;
        }
        Stroke old = g2d.getStroke();
        g2d.setColor(new Color(20, 20, 30, 150));
        g2d.setStroke(new BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 6f, 6f }, 0f));
        g2d.draw(path);
        g2d.setColor(colour == null ? Color.WHITE : colour);
        g2d.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 6f, 6f }, 0f));
        g2d.draw(path);
        g2d.setStroke(old);
    }

    /**
     * What the map's globe is wrapped in: what the flat map shows (the overlay chosen, or the
     * plain map at its finer detail, with the layers ticked over it), made afresh only when that
     * changes.
     */
    private BufferedImage globeTexture() {
        BufferedImage base = gradientImage != null ? gradientImage : baseMapDetail != null ? baseMapDetail : mapImage;
        List<Object> key = new ArrayList<>();
        key.add(base);
        for (Layer layer : Layer.values()) {
            BufferedImage[] images = layerImages.get(layer);
            if (images != null && shownLayers.contains(layer)) key.add(images[1]);
        }
        if (globeTexture != null && key.equals(globeTextureKey)) return globeTexture;
        int w = base.getWidth(), h = base.getHeight();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(base, 0, 0, null);
        for (int k = 1; k < key.size(); k++) g.drawImage((BufferedImage) key.get(k), 0, 0, w, h, null);
        g.dispose();
        globeTexture = image;
        globeTextureKey = key;
        return image;
    }

    // The globe's detail tiles (see prepareGlobeDetail), each what the flat map shows there
    // composed into one picture's pixels, by "level/tx/ty/what it shows"
    private final java.util.Map<String, int[]> globeTiles = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, int[]> eldest) {
            return size() > 160;
        }
    };

    /**
     * Zoomed in on the globe (M47): the flat map's detail tiles over the part of the ball in view,
     * asked for as the flat map asks for its own, so the globe stays as sharp as the flat map
     * does. What they show: the plain map (the results) or the overlay or plain map with the
     * markings ticked over it, as the flat map draws them.
     */
    private int globeTilesLogged = -1;

    /** Where some of the globe's detail tiles come from: the name they're kept under, and what draws them. */
    private record TileSource(String name, MapTiles.Renderer renderer) {
    }

    private void prepareGlobeDetail(RevealGlobe shown, boolean plain, int textureWidth) {
        int w = currentMapSize, h = mapHeight();
        int level = shown.detailLevel(w, h, textureWidth);
        // What the tiles show: the base's (or overlay's) tiles, then each marking's
        List<TileSource> sources = new ArrayList<>();
        BufferedImage gradient = plain ? null : gradientImage;
        if (gradient == null) sources.add(new TileSource("base", baseTiles));
        else sources.add(new TileSource("overlay-" + overlayTileName, overlayTiles));
        if (!plain) {
            for (Layer layer : Layer.values()) {
                if (layerImages.get(layer) != null && shownLayers.contains(layer)) sources.add(new TileSource(layer.name(), layerTiles.get(layer)));
            }
        }
        StringBuilder sourceKey = new StringBuilder();
        for (TileSource s : sources) sourceKey.append(s.name()).append('+');
        if (level == 0 || sources.get(0).renderer() == null) {
            shown.setDetail(0, java.util.Map.of(), sourceKey.toString());
            return;
        }
        java.util.Map<Long, int[]> ready = new java.util.HashMap<>();
        tiles.beginFrame();
        for (long t : shown.visibleTiles(w, h, level)) {
            // (each at its own level: coarser towards the poles)
            int at = RevealGlobe.levelOf(t), tx = RevealGlobe.txOf(t), ty = RevealGlobe.tyOf(t);
            String key = at + "/" + tx + "/" + ty + "/" + sourceKey;
            int[] pixels;
            synchronized (globeTiles) {
                pixels = globeTiles.get(key);
            }
            if (pixels == null) {
                // (every piece asked for; composed once all are ready)
                BufferedImage[] parts = new BufferedImage[sources.size()];
                boolean all = true;
                for (int k = 0; k < parts.length; k++) {
                    MapTiles.Renderer renderer = sources.get(k).renderer();
                    parts[k] = renderer == null ? null : tiles.get(sources.get(k).name(), renderer, at, tx, ty);
                    if (parts[k] == null && renderer != null) all = false;
                }
                if (!all) continue;
                BufferedImage composed = new BufferedImage(MapTiles.SIZE, MapTiles.SIZE, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = composed.createGraphics();
                for (BufferedImage part : parts) if (part != null) g.drawImage(part, 0, 0, MapTiles.SIZE, MapTiles.SIZE, null);
                g.dispose();
                pixels = ((java.awt.image.DataBufferInt) composed.getRaster().getDataBuffer()).getData();
                synchronized (globeTiles) {
                    globeTiles.put(key, pixels);
                }
            }
            ready.put(t, pixels);
        }
        tiles.endFrame();
        // Developer aid: -Dxenoguesser.globetiles=true logs how many tiles the globe wants, and how many are ready
        if (Dev.flag("xenoguesser.globetiles") && ready.size() != globeTilesLogged) {
            globeTilesLogged = ready.size();
            System.out.printf("[GLOBE] level %d at the equator: %d tiles in view, %d ready%n", level, shown.visibleTiles(w, h, level).size(), ready.size());
        }
        shown.setDetail(level, ready, sourceKey.toString());
    }

    /** The globe/flat toggle's place: in the map's top left corner. */
    private Rectangle toggleBounds() {
        return new Rectangle(visualMapX + 8, visualMapY + 8, TOGGLE_W, TOGGLE_H);
    }

    /** The globe/flat toggle (on the enlarged map and the results): naming what it turns the map to. */
    private void drawToggle(Graphics2D g2d) {
        Rectangle r = toggleBounds();
        g2d.setColor(new Color(25, 25, 27, 210));
        g2d.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g2d.setColor(new Color(120, 205, 235, 170));
        g2d.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g2d.setFont(HudStyle.font(Font.BOLD, 12f));
        FontMetrics fm = g2d.getFontMetrics();
        String text = globeShown() ? "Flat map" : "Globe";
        // (a little picture of what it turns to: a flat chart or a ball)
        int ix = r.x + 8, iy = r.y + (r.height - 12) / 2;
        g2d.setColor(new Color(150, 220, 255));
        if (globeShown()) g2d.drawRect(ix, iy + 1, 14, 10);
        else g2d.drawOval(ix + 1, iy, 12, 12);
        g2d.setColor(Color.WHITE);
        g2d.drawString(text, ix + 22, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
    }

    /** Turns the map shown now between the globe and the flat chart. */
    private void toggleGlobe() {
        if (isFullScreenReveal) revealGlobe = !revealGlobe;
        else mapGlobe = !mapGlobe;
        resetZoom();
        repaint();
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
        if (globeShown()) {
            activeGlobe().zoomBy(-e.getPreciseWheelRotation(), ZOOM_STEP);
            repaint();
            return;
        }

        float cursorU= viewLeft() + fractionX / zoom;
        float cursorV = viewTop() + fractionY * MapProjection.aspect() / zoom;
        float newZoom = zoom * (float) Math.pow(ZOOM_STEP, -e.getPreciseWheelRotation());
        zoom = Math.max(1.0f, Math.min(MAX_ZOOM, newZoom));

        float halfView = 0.5f / zoom;
        viewCentreU = clampCentre(cursorU - fractionX / zoom + halfView);
        viewCentreV = clampCentreV(cursorV - fractionY * MapProjection.aspect() / zoom + halfView * MapProjection.aspect());
        repaint();
    }

    private void resetZoom() {
        zoom = 1.0f;
        viewCentreU = 0.5f;
        viewCentreV = 0.5f;
    }

    /** Developer aid: zoomed in this far, centred on (u, v) of the map (0 to 1 across, 0 to 1 down the square). */
    public void devZoom(float magnification, float u, float v) {
        // (on the globe: turned to face there and zoomed as far)
        if (globeShown()) {
            RevealGlobe shown = activeGlobe();
            double lat = MapProjection.latitude(v * totalRegionWidth - halfRegion), lon = MapProjection.longitude(u * totalRegionWidth - halfRegion);
            shown.resetZoom();
            shown.centreOn(new GeodesicChunkGrid.Direction(Math.cos(lat) * Math.cos(lon), Math.sin(lat), -Math.cos(lat) * Math.sin(lon)));
            shown.zoomBy(1.0, magnification);
            repaint();
            return;
        }
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
        if (isFullScreenReveal) return Math.round(currentMapSize * REVEAL_ASPECT);
        return Math.round(currentMapSize * MapProjection.aspect());
    }

    private float clampCentreV(float centre) {
        float halfView = 0.5f * MapProjection.aspect() / zoom;
        float top = 0.5f - 0.5f * MapProjection.aspect(), bottom = 0.5f + 0.5f * MapProjection.aspect();
        return Math.max(top + halfView, Math.min(bottom - halfView, centre));
    }

    private float viewLeft() {
        return viewCentreU - 0.5f / zoom;
    }

    private float viewTop() {
        return viewCentreV - 0.5f * MapProjection.aspect() / zoom;
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
     * Pictures stood on the map, named (as an overlay is), over whatever heatmap there is (M47):
     * each at its world x, z where its foot stands; null for none.
     */
    public void setPictureOverlay(String name, List<MapPicture> pictures) {
        List<MapPicture> placed = null;
        if (pictures != null) {
            placed = new ArrayList<>();
            for (MapPicture p : pictures) {
                placed.add(p.at((p.x() + halfRegion) / totalRegionWidth, (p.z() + halfRegion) / totalRegionWidth));
            }
        }
        this.picturesName = placed != null ? name : null;
        this.mapPictures = placed;
        hoveredLandmark = null;
        landmarkMarks = List.of();
        repaint();
    }

    // The landmarks' markers where they were last drawn, and the one the mouse is over (its popup
    // shown), if any
    private volatile List<LandmarkMark> landmarkMarks = List.of();
    private volatile MapPicture hoveredLandmark;

    /** A landmark's marker as drawn: its thumbnail's centre and radius on screen, and its picture. */
    private record LandmarkMark(float x, float y, float r, MapPicture picture) {
    }
    // Each landmark picture's round thumbnail, for its marker
    private final java.util.Map<BufferedImage, BufferedImage> landmarkThumbs = new java.util.IdentityHashMap<>();

    /** Which landmark's marker (if any) is under the mouse: its popup is shown while it is. */
    private void hoverLandmark(int x, int y) {
        MapPicture over = null;
        if (isLarge && !isFullScreenReveal) {
            for (LandmarkMark mark : landmarkMarks) {
                float dx = x - mark.x(), dy = y - mark.y(), r = mark.r() + 3f;
                if (dx * dx + dy * dy <= r * r) over = mark.picture();
            }
        }
        if (over != hoveredLandmark) {
            hoveredLandmark = over;
            repaint();
        }
    }

    /** A landmark's picture cut to a disc, size pixels across, with a rim. */
    private BufferedImage landmarkThumb(BufferedImage picture) {
        synchronized (landmarkThumbs) {
            return landmarkThumbs.computeIfAbsent(picture, ignored -> {
                int size = 72;
                BufferedImage thumb = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = thumb.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.fill(new java.awt.geom.Ellipse2D.Float(0, 0, size, size));
                g.setComposite(AlphaComposite.SrcIn);
                // (its middle, a square of the picture's shorter side, a little below centre where the landmark stands)
                int side = Math.min(picture.getWidth(), picture.getHeight());
                int sx = (picture.getWidth() - side) / 2, sy = Math.min(picture.getHeight() - side, (picture.getHeight() - side) / 2 + side / 10);
                g.drawImage(picture, 0, 0, size, size, sx, sy, sx + side, sy + side, null);
                g.dispose();
                return thumb;
            });
        }
    }

    /**
     * The landmarks' markers (a picture with a name: see setPictureOverlay): each a round
     * thumbnail of it on a pin over its place; and over the one the mouse is on, its popup.
     */
    private void drawLandmarks(Graphics2D g2d, int mapX, int mapY, List<MapPicture> pictures) {
        boolean small = currentMapSize < 300;
        float radius = small ? 9f : Math.min(22f, 15f + zoom * 1.2f);
        List<LandmarkMark> marks = new ArrayList<>();
        Object oldInterpolation = g2d.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        MapPicture hovered = hoveredLandmark;
        float[] hoveredAt = null;
        for (MapPicture p : pictures) {
            for (int copy = -1; copy <= 1; copy++) {
                float[] at = uvScreen(p.x(), p.z(), copy, mapX, mapY);
                if (at == null) continue;
                if (at[0] < mapX || at[0] >= mapX + currentMapSize || at[1] < mapY || at[1] >= mapY + mapHeight()) continue;
                // (the pin's foot on its place, the thumbnail above it)
                float cx = at[0], cy = at[1] - radius - 7f;
                boolean over = p == hovered;
                float r = over ? radius + 3f : radius;
                Polygon pin = new Polygon();
                pin.addPoint(Math.round(at[0]), Math.round(at[1]));
                pin.addPoint(Math.round(cx - r * 0.45f), Math.round(cy + r * 0.8f));
                pin.addPoint(Math.round(cx + r * 0.45f), Math.round(cy + r * 0.8f));
                g2d.setColor(new Color(0, 0, 0, 70));
                g2d.fill(new java.awt.geom.Ellipse2D.Float(cx - r - 1f, cy - r + 2f, r * 2f + 2f, r * 2f + 2f));
                g2d.setColor(over ? LANDMARK_GOLD : new Color(250, 248, 240));
                g2d.fill(pin);
                g2d.fill(new java.awt.geom.Ellipse2D.Float(cx - r - 2.5f, cy - r - 2.5f, r * 2f + 5f, r * 2f + 5f));
                g2d.drawImage(landmarkThumb(p.picture()), Math.round(cx - r), Math.round(cy - r), Math.round(r * 2f), Math.round(r * 2f), null);
                marks.add(new LandmarkMark(cx, cy, r, p));
                if (over) hoveredAt = new float[] { cx, cy, r };
            }
        }
        landmarkMarks = marks;
        // Developer aid: -Dxenoguesser.landmarkpopup=n shows the nth landmark's popup, as if hovered over
        if (hovered == null && !marks.isEmpty() && Dev.property("xenoguesser.landmarkpopup") != null) {
            LandmarkMark first = marks.get(Math.min(marks.size() - 1, Dev.integer("xenoguesser.landmarkpopup", 0)));
            hovered = first.picture();
            hoveredAt = new float[] { first.x(), first.y(), first.r() };
        }
        if (hovered != null && hoveredAt != null && !small) drawLandmarkPopup(g2d, mapX, mapY, hovered, hoveredAt);
        if (oldInterpolation != null) g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
    }

    private static final Color LANDMARK_GOLD = new Color(240, 196, 92);

    /** A landmark's popup beside its marker: its picture, its kind, its name and a line about it. */
    private void drawLandmarkPopup(Graphics2D g2d, int mapX, int mapY, MapPicture p, float[] mark) {
        BufferedImage picture = p.picture();
        String name = p.name(), caption = p.described() ? p.caption() : "", kind = p.described() ? p.kind().toUpperCase(java.util.Locale.ROOT) : "";
        int pad = 10;
        int pictureH = Math.min(picture.getHeight(), Math.max(120, mapHeight() / 3));
        int pictureW = Math.round(picture.getWidth() * pictureH / (float) picture.getHeight());
        int cardW = Math.max(pictureW, 230) + pad * 2;
        Font kindFont = new Font("Arial", Font.BOLD, 10), nameFont = new Font("Arial", Font.BOLD, 15), textFont = new Font("Arial", Font.PLAIN, 12);
        FontMetrics textFm = g2d.getFontMetrics(textFont);
        List<String> lines = wrap(caption, textFm, cardW - pad * 2);
        int cardH = pad + pictureH + 8 + g2d.getFontMetrics(kindFont).getHeight() + g2d.getFontMetrics(nameFont).getHeight()
                + lines.size() * textFm.getHeight() + pad;
        // (above its marker if there's room, else below; kept on the map)
        int x = Math.round(mark[0] - cardW / 2f);
        int y = Math.round(mark[1] - mark[2] - 10 - cardH);
        if (y < mapY + 6) y = Math.round(mark[1] + mark[2] + 16);
        x = Math.max(mapX + 6, Math.min(mapX + currentMapSize - cardW - 6, x));
        y = Math.max(mapY + 6, Math.min(mapY + mapHeight() - cardH - 6, y));
        g2d.setColor(new Color(0, 0, 0, 90));
        g2d.fillRoundRect(x + 3, y + 4, cardW, cardH, 14, 14);
        g2d.setColor(new Color(24, 25, 30, 240));
        g2d.fillRoundRect(x, y, cardW, cardH, 14, 14);
        g2d.setColor(LANDMARK_GOLD);
        g2d.setStroke(new BasicStroke(1.5f));
        g2d.drawRoundRect(x, y, cardW, cardH, 14, 14);
        g2d.drawImage(picture, x + (cardW - pictureW) / 2, y + pad, pictureW, pictureH, null);
        int textY = y + pad + pictureH + 8;
        g2d.setFont(kindFont);
        g2d.setColor(LANDMARK_GOLD);
        textY += g2d.getFontMetrics().getAscent();
        g2d.drawString(kind, x + pad, textY);
        g2d.setFont(nameFont);
        g2d.setColor(Color.WHITE);
        textY += g2d.getFontMetrics().getHeight();
        g2d.drawString(name, x + pad, textY);
        g2d.setFont(textFont);
        g2d.setColor(new Color(205, 210, 220));
        for (String line : lines) {
            textY += textFm.getHeight();
            g2d.drawString(line, x + pad, textY);
        }
    }

    /** Text broken into lines no wider than width. */
    private static List<String> wrap(String text, FontMetrics fm, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && fm.stringWidth(line + " " + word) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /** The map's pictures (see setPictureOverlay), each standing on its place, at a size readable on the map (they don't grow with zooming). */
    private void drawMapPictures(Graphics2D g2d, int mapX, int mapY, List<MapPicture> pictures) {
        if (!pictures.isEmpty() && pictures.get(0).described()) {
            drawLandmarks(g2d, mapX, mapY, pictures);
            return;
        }
        float span = zoom * currentMapSize;
        float originX = mapX - viewLeft() * span, originY = mapY - viewTop() * span;
        int tall = currentMapSize < 300 ? 30 : Math.round(Math.min(80f, 50f + zoom * 4f));
        Object oldInterpolation = g2d.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        for (MapPicture p : pictures) {
            BufferedImage picture = p.picture();
            int w = Math.round(picture.getWidth() * tall / (float) picture.getHeight());
            for (int copy = -1; copy <= 1; copy++) {
                float[] at = uvScreen(p.x(), p.z(), copy, mapX, mapY);
                if (at == null) continue;
                int footX = Math.round(at[0]), footY = Math.round(at[1]);
                if (footX < mapX || footX >= mapX + currentMapSize || footY < mapY || footY >= mapY + mapHeight()) continue;
                int x = footX - w / 2, y = footY - tall;
                // (on a soft backing, so it reads on any land)
                g2d.setColor(new Color(245, 245, 240, 120));
                g2d.fillRoundRect(x - 4, y - 3, w + 8, tall + 6, 10, 10);
                g2d.drawImage(picture, x, y, w, tall, null);
            }
        }
        if (oldInterpolation != null) g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
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

    /** Where the nations' names are written, each at its world x, z. */
    public void setNationLabels(List<NationLabel> labels) {
        List<NationLabel> placed = new ArrayList<>();
        for (NationLabel label : labels) {
            float u = (label.x() + halfRegion) / totalRegionWidth;
            float v = (label.z() + halfRegion) / totalRegionWidth;
            placed.add(label.at(u, v));
        }
        this.nationLabels = placed;
        repaint();
    }

    /**
     * Sets up (or sets up afresh) the tick boxes beside the enlarged map, listing these layers
     * and overlays; with none of either, there are none.
     */
    public void setLayerChoices(List<Layer> layers, java.util.LinkedHashMap<String, List<String>> gradients, String chosen,
                                java.util.function.Consumer<String> onGradient, List<String> pictureMaps, String chosenPictures,
                                java.util.function.Consumer<String> onPictures, java.util.function.Function<String, java.awt.Image> icons) {
        if (layersPanel != null && layersPanel.getParent() != null) {
            java.awt.Container parent = layersPanel.getParent();
            parent.remove(layersPanel);
            parent.repaint();
        }
        boolean any = !layers.isEmpty() || !pictureMaps.isEmpty() || gradients.values().stream().anyMatch(g -> !g.isEmpty());
        layersPanel = any ? new MapLayersPanel(this::setLayerShown, layers, shownLayers, gradients, chosen, onGradient,
                pictureMaps, chosenPictures, onPictures, icons) : null;
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
        partnerGuess = null;
        partnerPin = null;
        Multiplayer session = Multiplayer.active();
        if (session != null && gameHUD != null) {
            // Multiplayer: the guess goes to the partner (with this quarter's productivity, this
            // guess included: what the quarter's verdict compares), and the reveal waits for theirs
            calculateAndApplyScore();
            int round = gameHUD.getRound();
            // (an unguessed round goes as a guess with no place: NaN)
            session.sendGuess(new Multiplayer.Guess(round, pin == null ? Double.NaN : pin.x(), pin == null ? Double.NaN : pin.y(),
                    pin == null ? Double.NaN : pin.z(), targetRoundScore, gameHUD.getProductivity() + targetRoundScore));
            setUpReveal();
            currentPhase = RevealPhase.WAITING_FOR_PARTNER;
            if (partnerWait != null) partnerWait.stop();
            partnerWait = new Timer(100, e -> {
                // (given up should the partner have gone meanwhile: the game's ended for this player)
                if (session.isClosed()) {
                    ((Timer) e.getSource()).stop();
                    return;
                }
                Multiplayer.Guess theirs = session.remoteGuess(round);
                if (theirs == null) {
                    repaint();
                    return;
                }
                ((Timer) e.getSource()).stop();
                partnerGuess = theirs;
                // (none where they didn't guess in time)
                partnerPin = Double.isNaN(theirs.px()) ? null : new GeodesicChunkGrid.Direction(theirs.px(), theirs.py(), theirs.pz());
                runReveal();
            });
            partnerWait.setInitialDelay(0);
            partnerWait.start();
            return;
        }
        setUpReveal();
        runReveal();
    }

    /** The guess is made: the pin locked and the map shown at the reveal's size on the globe, centred on the pin. */
    private void setUpReveal() {
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
        globe.setTexture(mapImage);
        globe.resetZoom();
        globe.centreOn(pin != null ? pin : goal);
        this.revealStartTime = System.currentTimeMillis();

        this.currentDisplayScore = 0;
        this.shouldDrawScoreText = false;
        this.currentScoreScale = 1.0f;
        this.slamProgress = 0.0f;
        this.scoreBanked = false;


        if (getParent() != null) {
            int parentHeight = getParent().getHeight();
            int parentWidth = getParent().getWidth();
            this.currentMapSize = revealSize(parentWidth, parentHeight);
        } else {
            this.currentMapSize = 650;
        }

        if (this.mainApp != null) {
            this.mainApp.lockWindowDragging();
        }

        updateGeometryLayouts();
        triggerParentLayoutUpdate();
        if (onSizeChanged != null) onSizeChanged.run();
    }

    /** How long the globe takes to turn from a to b (the further apart, the longer). */
    private static long turnMs(GeodesicChunkGrid.Direction a, GeodesicChunkGrid.Direction b) {
        return GLOBE_TURN_MIN_MS + Math.round((GLOBE_TURN_MAX_MS - GLOBE_TURN_MIN_MS) * SphereMath.angle(a, b) / Math.PI);
    }

    /**
     * The reveal: resting on the guess, turning to the answer as the line is drawn, then the
     * score. In multiplayer (M14): resting on this player's pin, turning to the partner's,
     * resting there, turning to the answer, then both lines drawn, then the score.
     */
    private void runReveal() {
        currentPhase = RevealPhase.SHOW_PLAYER_PIN;
        this.revealStartTime = System.currentTimeMillis();
        // (with only one guess, the partner's where this player didn't guess in time, the globe
        // turns from it to the answer; with none, it rests on the answer)
        final GeodesicChunkGrid.Direction theirs = pin != null ? partnerPin : null;
        final GeodesicChunkGrid.Direction first = pin != null ? pin : partnerPin;
        final long turnMs = first == null ? 0L : turnMs(first, theirs != null ? theirs : goal);
        final long partnerTurnMs = theirs != null ? turnMs(theirs, goal) : 0;
        final long COUNT_UP_DURATION_MS = 500; 
        final long[] countUpStartTime = {-1};  

        revealTimer = new Timer(16, e -> {
            if (theirs != null && currentPhase == RevealPhase.SHOW_PLAYER_PIN) {
                // Multiplayer: from this player's pin to the partner's
                float t = Math.max(0f, Math.min(1f, (System.currentTimeMillis() - revealStartTime - GLOBE_HOLD_MS) / (float) turnMs));
                globe.centreOn(RevealGlobe.along(pin, theirs, t * t * (3f - 2f * t)));
                if (t >= 1.0f) {
                    currentPhase = RevealPhase.SHOW_PARTNER_PIN;
                    phaseStartTime = System.currentTimeMillis();
                }
            }
            else if (currentPhase == RevealPhase.SHOW_PARTNER_PIN) {
                // ...resting there, then on to the answer
                float t = Math.max(0f, Math.min(1f, (System.currentTimeMillis() - phaseStartTime - GLOBE_HOLD_MS) / (float) partnerTurnMs));
                globe.centreOn(RevealGlobe.along(theirs, goal, t * t * (3f - 2f * t)));
                if (t >= 1.0f) {
                    currentPhase = RevealPhase.DRAW_LINES;
                    phaseStartTime = System.currentTimeMillis();
                }
            }
            else if (currentPhase == RevealPhase.DRAW_LINES) {
                // ...and both lines drawn out from the guesses to it
                float t = Math.min(1f, (System.currentTimeMillis() - phaseStartTime) / (float) LINES_MS);
                lineProgress = t * t * (3f - 2f * t);
                if (t >= 1.0f) {
                    lineProgress = 1.0f;
                    currentPhase = RevealPhase.SHOW_ALL_RESULTS;
                    calculateAndApplyScore();
                    shouldDrawScoreText = true;
                    updateGeometryLayouts();
                    triggerParentLayoutUpdate();
                }
            }
            else if (currentPhase == RevealPhase.SHOW_PLAYER_PIN) {
                // Resting on the guess, then turning to the answer, easing in and out, the line
                // drawn as far as the globe has turned (so its end stays in the middle)
                float t = first == null ? (System.currentTimeMillis() - revealStartTime >= GLOBE_HOLD_MS ? 1f : 0f)
                        : Math.max(0f, Math.min(1f, (System.currentTimeMillis() - revealStartTime - GLOBE_HOLD_MS) / (float) turnMs));
                lineProgress = t * t * (3f - 2f * t);
                globe.centreOn(first == null ? goal : RevealGlobe.along(first, goal, lineProgress));
                if (t >= 1.0f) {
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
    public synchronized void setPlayerSpawnLocation(GeodesicChunkGrid.Direction place) {
        if (isGuessed) pendingGoal = place;
        else setGoal(place);
    }

    private synchronized void applyPendingGoal() {
        if (pendingGoal != null) {
            setGoal(pendingGoal);
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
                noteArea.setBounds(noteBtnX, noteBtnY, noteBtnWidth, noteBtnHeight);
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
            if (globeShown()) {
                // On the globe: the results' the plain map, the map's what the flat one shows
                RevealGlobe shown = activeGlobe();
                BufferedImage wrap = plain ? mapImage : globeTexture();
                shown.setTexture(wrap);
                prepareGlobeDetail(shown, plain, wrap.getWidth());
                shown.paint(g2d, mapX, mapY, currentMapSize, mapHeight());
                if (showNationNames && !plain) drawNationNames(g2d, mapX, mapY);
                List<MapPicture> pictures = mapPictures;
                if (pictures != null && !plain) drawMapPictures(g2d, mapX, mapY, pictures);
                g2d.setClip(frameClip);
            } else {
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
            List<MapPicture> pictures = mapPictures;
            if (pictures != null && !plain) drawMapPictures(g2d, mapX, mapY, pictures);
            g2d.setClip(frameClip);
            }

            float shownZoom = globeShown() ? (float) activeGlobe().zoom() : zoom;
            if (isLarge || isFullScreenReveal) drawToggle(g2d);
            if (shownZoom > 1.01f) {
                String zoomLabel = String.format("%.1fx", shownZoom);
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

            String heatmapName = plain ? null : this.gradientName, picturesShown = plain ? null : this.picturesName;
            if (picturesShown != null) heatmapName = heatmapName == null ? picturesShown : heatmapName + "  ·  " + picturesShown;
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
                    int[] at = noteScreen(note, mapX, mapY);
                    if (at == null) continue;
                    int drawX = at[0];
                    int drawY = at[1];
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

            // (over any note left there, which hangs above the spot)
            if (lastRound != null && !isFullScreenReveal) drawLastRound(g2d, mapX, mapY);

            if (hasPin) {
                Stroke originalStroke = g2d.getStroke();

                int displayPinX = coreToScreenX(pinX, mapX);
                int displayPinY = coreToScreenY(pinY, mapY);

                int displayGoalX = coreToScreenX(goalX, mapX);
                int displayGoalY = coreToScreenY(goalY, mapY);

                // (on the globe, each only while it's on the side facing the viewer)
                boolean pinShown = true, goalShown = true;

                Shape markerClip = g2d.getClip();
                g2d.clipRect(mapX, mapY, currentMapSize, mapHeight());

                if (isFullScreenReveal) {
                    // The great circle's arc the distance is measured along (multiplayer: both
                    // guesses' arcs, each in its player's colour)
                    // (each guess made; none where it went unguessed)
                    if (partnerPin != null) drawArcAnyway(g2d, partnerPin, goal, lineProgress, mapX, mapY, partnerColour());
                    if (pin != null && partnerPin != null) drawArcAnyway(g2d, pin, goal, lineProgress, mapX, mapY, ownColour());
                    else if (pin != null) drawArcAnyway(g2d, pin, goal, lineProgress, mapX, mapY, null);
                }
                if (globeShown() || isFullScreenReveal) {
                    double[] p = pin != null ? screenOf(pin, mapX, mapY) : new double[] { 0, 0, -1 };
                    double[] q = screenOf(goal, mapX, mapY);
                    displayPinX = (int) Math.round(p[0]);
                    displayPinY = (int) Math.round(p[1]);
                    displayGoalX = (int) Math.round(q[0]);
                    displayGoalY = (int) Math.round(q[1]);
                    pinShown = p[2] >= 0.0;
                    goalShown = q[2] >= 0.0;
                }

                int pinLength = MapMarkers.PIN_LENGTH;
                int headDiameter = MapMarkers.PIN_HEAD;
                // (in multiplayer, in this player's colour)
                if (pinShown) MapMarkers.pin(g2d, displayPinX, displayPinY, ownColour() != null ? ownColour() : new Color(220, 40, 40));
                if (isFullScreenReveal && partnerPin != null) drawPartnerPin(g2d, mapX, mapY, pinLength, headDiameter);
                // (multiplayer: this player's own pin named too, with "(You)")
                Multiplayer pinSession = Multiplayer.active();
                if (isFullScreenReveal && pinShown && pinSession != null && currentPhase != RevealPhase.WAITING_FOR_PARTNER) {
                    drawPinLabel(g2d, "Employee " + pinSession.localLabel() + " (You)", displayPinX, displayPinY, pinLength, headDiameter,
                            brighter(ownColour()));
                }

                if (isFullScreenReveal && goalShown && (resultsShown() || currentPhase == RevealPhase.DRAW_LINES)) {
                    MapMarkers.flag(g2d, displayGoalX, displayGoalY);
                }

                g2d.setClip(markerClip);

                if (isFullScreenReveal) {
                    boolean resultsReady = resultsShown();
                    
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
                if (resultsShown()) drawReckoning(g2d, mapX, mapY);
                if (currentPhase == RevealPhase.WAITING_FOR_PARTNER) drawWaitingForPartner(g2d, mapX, mapY);

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
        // (a fresh clock for the new round)
        guessMsLeft = -1L;
        partnerGuessedFirst = false;
        missedGuess = false;
        // (the round just played is now the last round)
        if (isGuessed && goal != null) {
            lastRound = goal;
            lastRoundX = goalX;
            lastRoundY = goalY;
        }
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
        if (partnerWait != null) partnerWait.stop();
        partnerGuess = null;
        partnerPin = null;

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
        
        if (this.mainApp != null) {
            this.mainApp.unlockWindowDragging();
        }

        updateGeometryLayouts();
        triggerParentLayoutUpdate();
        repaint();
        if (onSizeChanged != null) onSizeChanged.run();
    }

    /**
     * The results' view's width for a window of width by height: its height leaves room below
     * for the note and proceed buttons and above for a margin.
     */
    private static int revealSize(int width, int height) {
        int tall = Math.min(620, Math.max(260, height - 330));
        return Math.min(Math.max(300, width - 80), Math.round(tall / REVEAL_ASPECT));
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
            size = revealSize(width, height);
        } else if (isLarge) {
            size = Math.min((int) (height * 0.60f / MapProjection.aspect()), (int) (width * 0.55f));
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
            case LARGE -> Math.min((int) (parentHeight * 0.60f / MapProjection.aspect()),
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

    /**
     * The results' button: the note being written (if any) is kept, the round's points go onto
     * the score at once (should they still be flying there) and the shop opens.
     */
    private void proceedToShop() {
        if (isTypingNote) commitNote();
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

    /**
     * Developer aid: a guess at the middle of the map (or at -Dxenoguesser.guessat=u,v, shares of
     * its width and height, or =answer for the true location), as if clicked and confirmed.
     */
    public void devGuess() {
        String spot = Dev.property("xenoguesser.guessat", "0.5,0.5");
        // (-Dxenoguesser.guessatlater=round:spot guesses at spot instead from that round on)
        String later = Dev.property("xenoguesser.guessatlater");
        if (later != null && gameHUD != null && gameHUD.getRound() >= Integer.parseInt(later.split(":")[0])) spot = later.split(":")[1];
        if (spot.equals("answer") && goal != null) {
            // (right on the true location: full marks)
            placePin(goalX, goalY);
            pin = goal;
        } else {
            String[] at = spot.split(",");
            placePin((int) (mapImage.getWidth() * Double.parseDouble(at[0])), (int) (mapImage.getHeight() * Double.parseDouble(at[1])));
        }
        startRevealSequence();
    }

    /** Developer aid: Proceed to Shop, as if pressed. */
    public void devProceedToShop() {
        if (isFullScreenReveal && resultsShown() && !roundLoading) proceedToShop();
    }

    /** Developer aid: a marker put down (not guessed) at the middle of the map, or at -Dxenoguesser.pinat=u,v. */
    public void devPlacePin() {
        String[] at = Dev.property("xenoguesser.pinat", "0.5,0.5").split(",");
        placePin((int) (mapImage.getWidth() * Double.parseDouble(at[0])), (int) (mapImage.getHeight() * Double.parseDouble(at[1])));
        repaint();
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

    /** The equator's line on the maps: pale gold, half opaque, and twice the other lines' width (the results' globe draws it alike). */
    static final Color EQUATOR_COLOUR = new Color(255, 236, 170, 150);
    static final float EQUATOR_WIDTH = 2f;

    /**
     * Lines of latitude and longitude every 30 degrees (the equator much stronger): the
     * map is a Mercator chart of the planet, so the parallels spread out towards the poles.
     */
    private void drawGraticule(Graphics2D g2d, int mapX, int mapY) {
        float span = zoom * currentMapSize;
        float originX = mapX - viewLeft() * span, originY = mapY - viewTop() * span;
        java.awt.Stroke old = g2d.getStroke();
        g2d.setStroke(new BasicStroke(1f));
        for (int lon = -150; lon <= 150; lon += 30) {
            float fx = (float) ((MapProjection.x(Math.toRadians(lon)) + halfRegion) / totalRegionWidth);
            int px = Math.round(originX + fx * span);
            g2d.setColor(new Color(255, 255, 255, lon == 0 ? 70 : 38));
            g2d.drawLine(px, mapY, px, mapY + mapHeight());
        }
        // (every 30 degrees, as on the results' globe, so the two can be read alike)
        for (int lat = -60; lat <= 60; lat += 30) {
            float fy = (float) ((MapProjection.z(Math.toRadians(lat)) + halfRegion) / totalRegionWidth);
            int py = Math.round(originY + fy * span);
            // (the equator thicker, brighter and a little warm, so it reads at a glance)
            g2d.setStroke(new BasicStroke(lat == 0 ? EQUATOR_WIDTH : 1f));
            g2d.setColor(lat == 0 ? EQUATOR_COLOUR : new Color(255, 255, 255, 38));
            g2d.drawLine(mapX, py, mapX + currentMapSize, py);
        }
        g2d.setStroke(old);
    }

    private void calculateAndApplyScore() {
        if (pin == null) {
            // Unguessed: nothing for it
            this.roundDistance = Double.NaN;
            this.targetRoundScore = 0;
            return;
        }
        // How far apart the guess and the answer really are: the shortest way round the sphere's surface
        double realWorldDistance = SphereMath.angle(pin, goal) * Planet.radius();
        this.roundDistance = realWorldDistance;

        // Full marks within PERFECT_DISTANCE; beyond it the score falls away smoothly from just
        // under full (4,999 at the edge, never jumping) by the share of the way from there to the
        // far side of the planet (M48), so a guess as far off scores the same whatever its size
        int score;
        if (realWorldDistance <= PERFECT_DISTANCE) {
            score = FULL_MARKS;
        } else {
            double share = (realWorldDistance - PERFECT_DISTANCE) / (Planet.greatestDistance() - PERFECT_DISTANCE);
            score = (int) Math.min(4999.0, Math.floor(5000.0 * Math.exp(-7.0 * share)));
        }
        if (score < 0) score = 0;

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
        float u0 = viewLeft(), v0 = viewTop(), u1 = u0 + 1f / zoom, v1 = v0 + MapProjection.aspect() / zoom;
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
        List<NationLabel> ordered = new ArrayList<>(nationLabels);
        ordered.sort((a, b) -> Boolean.compare(b.main(), a.main()));
        for (NationLabel label : ordered) {
            boolean main = label.main();
            if (!main && !islands) continue;
            BufferedImage name = label.name();
            // The glyphs' height on screen
            float glyph = main ? (currentMapSize < 300 ? 11f : 17f) : Math.min(14f, 6f + zoom * 1.5f);
            float scale = glyph / NATION_NAME_GLYPH;
            int w = Math.round(name.getWidth() * scale), h = Math.round(name.getHeight() * scale);
            for (int copy = -1; copy <= 1; copy++) {
                float[] at = uvScreen(label.x(), label.z(), copy, mapX, mapY);
                if (at == null) continue;
                int x = Math.round(at[0] - w * 0.5f);
                int y = Math.round(at[1] - h * 0.5f);
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
                BufferedImage flag = main ? label.flag() : null;
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
    // A guess this close (world units: 10 m) scores full marks
    private static final double PERFECT_DISTANCE = 20.0 * PlayerBody.METRE;
    // The most a round can score
    public static final int FULL_MARKS = 5000;
    // How far the guess was from the answer this round (world units)
    private double roundDistance;

    /** A distance in world units as the player reads it: metres up to a kilometre, then kilometres. */
    private static String distanceText(double units) {
        double metres = units / PlayerBody.METRE;
        if (metres < 1000.0) return String.format("%,d m", Math.round(metres));
        return String.format(metres < 100_000.0 ? "%,.1f km" : "%,.0f km", metres / 1000.0);
    }

    /**
     * The reckoning's last line: singleplayer, the cash the guess earned; multiplayer (no cash),
     * who takes from the shop (the round's loser, both if it's level, the winner nothing).
     */
    private String paidLine(Multiplayer session, int points, int cash) {
        if (session == null) return String.format("Cash: ($%,d salary + 1/2 x $%,d points) = +$%,d", GameHUD.SALARY, points, cash);
        if (partnerGuess == null) return "Shop: the round's loser takes one";
        int theirs = partnerGuess.score();
        return points > theirs ? "You won the round: no shop pick"
                : points < theirs ? "You lost the round: take one from the shop"
                : "A level round: both take one from the shop";
    }

    private void drawReckoning(Graphics2D g2d, int mapX, int mapY) {
        int points = targetRoundScore, cash = GameHUD.cashFor(points);
        Multiplayer session = Multiplayer.active();
        String[] lines = {
            "Distance: " + distanceText(roundDistance),
            String.format("Productivity: +%,d", points),
            paidLine(session, points, cash)
        };
        Color[] colours = { Color.WHITE, new Color(150, 220, 255), new Color(120, 230, 140) };
        if (pin == null || (session != null && partnerGuess != null && partnerPin == null)) {
            // A round gone unguessed (this player's, the partner's or both): what was guessed, if anything
            List<String> said = new ArrayList<>();
            List<Color> shades = new ArrayList<>();
            if (pin == null) {
                said.add("You didn't guess in time");
                shades.add(new Color(245, 105, 90));
            } else {
                said.add(String.format("You: %s, +%,d productivity", distanceText(roundDistance), points));
                shades.add(session != null ? brighter(ownColour()) : Color.WHITE);
            }
            if (session != null && partnerGuess != null) {
                if (partnerPin == null) {
                    said.add("Employee " + session.remoteLabel() + " didn't guess in time");
                    shades.add(brighter(partnerColour()));
                } else {
                    double theirDistance = SphereMath.angle(partnerPin, goal) * Planet.radius();
                    said.add(String.format("Employee %s: %s, +%,d productivity", session.remoteLabel(), distanceText(theirDistance), partnerGuess.score()));
                    shades.add(brighter(partnerColour()));
                }
            }
            said.add(paidLine(session, points, cash));
            shades.add(new Color(120, 230, 140));
            lines = said.toArray(new String[0]);
            colours = shades.toArray(new Color[0]);
        }
        else if (partnerGuess != null && partnerPin != null && session != null) {
            // Multiplayer: both players' guesses, each named in their colour
            double theirDistance = SphereMath.angle(partnerPin, goal) * Planet.radius();
            // ...and who was closer, plainly: green if it was this player, red if the partner
            double apart = Math.abs(roundDistance - theirDistance);
            String closer = roundDistance < theirDistance ? "You were closer, by " + distanceText(apart)
                    : theirDistance < roundDistance ? "Employee " + session.remoteLabel() + " was closer, by " + distanceText(apart)
                    : "You were both just as close";
            Color closerColour = roundDistance < theirDistance ? new Color(110, 225, 130)
                    : theirDistance < roundDistance ? new Color(245, 105, 90) : HudStyle.VALUE;
            lines = new String[] {
                closer,
                String.format("Employee %s (You): %s, +%,d productivity", session.localLabel(), distanceText(roundDistance), points),
                String.format("Employee %s: %s, +%,d productivity", session.remoteLabel(), distanceText(theirDistance), partnerGuess.score()),
                paidLine(session, points, cash)
            };
            colours = new Color[] { closerColour, brighter(ownColour()), brighter(partnerColour()), new Color(120, 230, 140) };
        }
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
        for (int i = 0; i < lines.length; i++) {
            g2d.setColor(colours[i]);
            g2d.drawString(lines[i], x + padX, y + padY + fm.getAscent() + i * lineH);
        }
    }

    /** Multiplayer: this player's colour (M6), for their pin and line; null in singleplayer. */
    private static Color ownColour() {
        Multiplayer session = Multiplayer.active();
        return session == null ? null : colour(session.role.trimColour());
    }

    /** Multiplayer: the partner's colour, for their pin and line. */
    private static Color partnerColour() {
        Multiplayer session = Multiplayer.active();
        return session == null ? Color.WHITE : colour(session.role.other().trimColour());
    }

    private static Color colour(float[] rgb) {
        return new Color(rgb[0], rgb[1], rgb[2]);
    }

    /** A player's colour lifted towards white, to read as text on the dark panel. */
    private static Color brighter(Color c) {
        return new Color((c.getRed() + 255 * 2) / 3, (c.getGreen() + 255 * 2) / 3, (c.getBlue() + 255 * 2) / 3);
    }

    /** The partner's pin (multiplayer), as this player's but in their colour, labelled with their employee number. */
    /**
     * The last round's marker: a ring where its answer was, tagged "Last round" below it. A
     * note left there in that round (notes are pinned at the answer) hangs above the same
     * spot, so the two don't meet; when its text is open below the spot (near the map's top),
     * the tag goes beside it instead.
     */
    private void drawLastRound(Graphics2D g2d, int mapX, int mapY) {
        double[] at = screenOf(lastRound, mapX, mapY);
        if (at[2] < 0.0) return;
        int x = (int) Math.round(at[0]), y = (int) Math.round(at[1]);
        if (x < mapX || x > mapX + currentMapSize || y < mapY || y > mapY + mapHeight()) return;
        Shape clip = g2d.getClip();
        g2d.clipRect(mapX, mapY, currentMapSize, mapHeight());
        Stroke stroke = g2d.getStroke();
        g2d.setColor(new Color(20, 20, 22, 200));
        g2d.setStroke(new BasicStroke(4f));
        g2d.drawOval(x - 8, y - 8, 16, 16);
        g2d.setColor(LAST_ROUND_COLOUR);
        g2d.setStroke(new BasicStroke(2.2f));
        g2d.drawOval(x - 8, y - 8, 16, 16);
        g2d.fillOval(x - 3, y - 3, 6, 6);
        g2d.setStroke(stroke);

        // Its tag: below, or beside when a note's open text hangs below the spot
        boolean noteOpenBelow = false;
        if (isLarge) {
            for (MapNote note : savedNotes) {
                if (note.isExpanded && note.coreX == Math.round(lastRoundX) && note.coreY == Math.round(lastRoundY) && y - 30 - 60 < mapY + 5) {
                    noteOpenBelow = true;
                }
            }
        }
        g2d.setFont(new Font("Arial", Font.BOLD, isLarge ? 12 : 10));
        FontMetrics fm = g2d.getFontMetrics();
        String label = "Last round";
        int w = fm.stringWidth(label) + 10, h = fm.getAscent() + 6;
        int boxX = noteOpenBelow ? x + 12 : x - w / 2, boxY = noteOpenBelow ? y - h / 2 : y + 12;
        boxX = Math.max(mapX + 3, Math.min(mapX + currentMapSize - w - 3, boxX));
        if (boxY + h > mapY + mapHeight() - 3) boxY = y - 12 - h - (noteOpenBelow ? 0 : 26);
        g2d.setColor(new Color(25, 25, 27, 215));
        g2d.fillRoundRect(boxX, boxY, w, h, 8, 8);
        g2d.setColor(LAST_ROUND_COLOUR);
        g2d.drawRoundRect(boxX, boxY, w, h, 8, 8);
        g2d.drawString(label, boxX + 5, boxY + fm.getAscent() + 2);
        g2d.setClip(clip);
    }

    private void drawPartnerPin(Graphics2D g2d, int mapX, int mapY, int pinLength, int headDiameter) {
        double[] p = screenOf(partnerPin, mapX, mapY);
        if (p[2] < 0.0) return;
        int x = (int) Math.round(p[0]), y = (int) Math.round(p[1]);
        MapMarkers.pin(g2d, x, y, partnerColour());
        Multiplayer session = Multiplayer.active();
        if (session == null) return;
        drawPinLabel(g2d, "Employee " + session.remoteLabel(), x, y, pinLength, headDiameter, brighter(partnerColour()));
    }

    /** A pin's name ("Employee 119-A (You)") in a dark tag beside its head, in the player's colour. */
    private static void drawPinLabel(Graphics2D g2d, String label, int x, int y, int pinLength, int headDiameter, Color colour) {
        float size = 12f;
        float w = HudStyle.labelWidth(g2d, label, size);
        int lx = x + headDiameter / 2 + 6, ly = y - pinLength + 4;
        g2d.setColor(new Color(10, 14, 22, 210));
        g2d.fillRoundRect(lx - 5, ly - 13, Math.round(w) + 10, 18, 6, 6);
        HudStyle.label(g2d, label, lx, ly, size, colour);
    }

    /** Multiplayer: the guess is in and the partner's isn't yet: says so on a panel over the map. */
    private void drawWaitingForPartner(Graphics2D g2d, int mapX, int mapY) {
        Multiplayer session = Multiplayer.active();
        if (session == null) return;
        String text = "Waiting for Player " + session.remoteLabel() + " to make their guess" + ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
        Font font = HudStyle.font(Font.BOLD, 19f);
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();
        int boxW = fm.stringWidth("Waiting for Player 119-A to make their guess...") + 60, boxH = 74;
        int x = mapX + (currentMapSize - boxW) / 2, y = mapY + mapHeight() - boxH - 24;
        HudStyle.smooth(g2d);
        HudStyle.panel(g2d, x, y, boxW, boxH, HudStyle.GLASS);
        HudStyle.label(g2d, "Your guess is in", x + 28, y + 26, 12f, HudStyle.ACCENT);
        g2d.setFont(font);
        g2d.setColor(Color.WHITE);
        g2d.drawString(text, x + 28, y + 54);
    }

    // The height of one glyph in a nation-name image, in its pixels
    public static final int NATION_NAME_GLYPH = 28;
}