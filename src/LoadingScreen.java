import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * The loading screen, shown from launch until the round's first frame: the spaceship's
 * cockpit, stars streaming past the front window, the old computer in the middle of the
 * room reporting progress in green text, and the player standing on the left in their
 * spacesuit, who can be turned round by dragging across them. In multiplayer both players
 * stand there, this one on the left in their own colour and the partner to their right in
 * theirs (the host blue, the joiner red), each turned by dragging on its own. Until the art
 * has loaded it is plain grey.
 *
 * The cockpit and spaceman are pre-rendered art (see LoadingArt); the stars and the
 * computer's screen are drawn live. To stay smooth while the world loads, the cockpit is
 * flattened once into an opaque background, the stars are drawn into a small buffer the
 * size of the window with the cockpit's foreground laid back over them, the terminal is only
 * redrawn when its text changes, and each tick repaints only the window and the screen.
 */
public class LoadingScreen extends JComponent {

    private static final int REPAINT_MS = 16;
    private static final Color GREY = new Color(58, 60, 64);
    private static final Color PHOSPHOR = new Color(110, 255, 150);
    private static final Color PHOSPHOR_DIM = new Color(60, 170, 90);
    private static final Color PHOSPHOR_FAIL = new Color(255, 190, 80);
    // The spaceman turntable's camera: the frame spans this many world units top to bottom,
    // and his feet sit this far down it
    private static final float FRAME_SPAN_UNITS = 2f * 62f * (float) Math.tan(Math.toRadians(11));
    private static final float SPACEMAN_HEIGHT_UNITS = 22.5f;
    private static final float FEET_FRACTION = 0.5f + 11.4f / FRAME_SPAN_UNITS;
    private static final int STAR_COUNT = 360;
    // Stars are drawn with a handful of prepared shades and line widths
    private static final Color[] STAR_SHADES = new Color[16];
    private static final BasicStroke[] STAR_STROKES = new BasicStroke[8];
    static {
        for (int i = 0; i < STAR_SHADES.length; i++) {
            int v = Math.round(40 + 215 * i / (float) (STAR_SHADES.length - 1));
            STAR_SHADES[i] = new Color(v, v, Math.min(255, v + 25));
        }
        for (int i = 0; i < STAR_STROKES.length; i++) {
            STAR_STROKES[i] = new BasicStroke(0.7f + i * 0.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        }
    }

    private final LoadingProgress progress;
    private final long worldSeed;
    private final Timer repaintTimer;
    private float smoothedFraction;

    // Art, and the layers made from it for the current window size
    private BufferedImage cockpit;
    private Properties layout = new Properties();
    private int layersWidth = -1, layersHeight = -1;
    private Image background;            // the whole cockpit, opaque, window black
    private BufferedImage foreground;    // the cockpit over the window's area, window transparent
    private Image starBuffer;
    private Rectangle windowBox = new Rectangle(), screenBox = new Rectangle();
    private float scale, offsetX, offsetY;

    // Stars: x, y spread across the view and z counting down towards the window
    private final float[][] stars = new float[STAR_COUNT][3];
    private final Random starRand = new Random();
    private long lastFrameNanos = System.nanoTime();

    // The computer's log: finished steps, then the one in progress; redrawn only on change
    private final List<String> finishedSteps = new ArrayList<>();
    private String lastLabel;
    private BufferedImage terminal;
    private String terminalKey = "";
    // The screen's corners in the window (top left, top right, bottom right, bottom left), and the
    // terminal drawn in its perspective onto them (redone when its text or the layout changes)
    private float[][] screenCorners = new float[4][];
    private BufferedImage warpedTerminal;

    // The spacemen: this player's (0, on the left) and in multiplayer the partner's (1, to
    // their right): each one's turntable, which way he faces, where he is drawn, and which
    // is being dragged round (-1 none)
    private final BufferedImage[] spacemanSheets = new BufferedImage[2];
    private final float[] spacemanAngles = { 200f, 160f };
    private final Rectangle[] spacemanBounds = { new Rectangle(), new Rectangle() };
    private int dragStartX;
    private float dragStartAngle;
    private int dragging = -1;
    // How far right of this player's feet the partner stands, as a share of a turntable frame's drawn width
    // (the plan's 0.6 had their shoulders touching; this leaves a gap and stays clear of the computer)
    private static final float PARTNER_ACROSS = 0.85f;

    // The message from Xenocorp: played once the cockpit appears, its words scrolling along
    // the bottom. The game starts only when the player presses the button: Skip while it
    // plays, Begin once it has finished.
    private TransmissionMessage message;
    private float[] charX;
    private String charXText;               // where each character of the transcript starts
    private Font tickerFont;
    // When the message finished, for scrolling its text off afterwards
    private long tickerDoneAt;
    private Rectangle tickerBox = new Rectangle(), buttonBox = new Rectangle();
    private boolean buttonHovered;
    private boolean skipped;
    private Runnable enterGame;
    // Multiplayer (M16): whether this player has pressed Begin; the game starts only when both have
    private boolean begun;
    private final boolean multiplayer;
    // This PC's player in multiplayer (null in singleplayer)
    private final Multiplayer.Role role;

    public LoadingScreen(LoadingProgress progress, long worldSeed) {
        this(progress, worldSeed, false);
    }

    /**
     * The cockpit while a world loads. In multiplayer (the partner already linked) the
     * multiplayer message plays instead of the singleplayer one, addressed to this player.
     */
    public LoadingScreen(LoadingProgress progress, long worldSeed, boolean multiplayer) {
        this.progress = progress;
        this.worldSeed = worldSeed;
        this.multiplayer = multiplayer;
        Multiplayer session = Multiplayer.active();
        this.role = !multiplayer ? null : session != null ? session.role : Multiplayer.Role.HOST;
        // The link to the partner's pod is already made; the loading stages follow it on the terminal
        if (multiplayer) finishedSteps.add("Partner pod linked " + role.other().label);
        setOpaque(true);
        for (float[] star : stars) resetStar(star, true);
        loadArt();
        MouseAdapter turning = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (buttonBox.contains(e.getPoint())) {
                    pressButton();
                    return;
                }
                int man = spacemanAt(e.getX(), e.getY());
                if (man >= 0) {
                    dragging = man;
                    dragStartX = e.getX();
                    dragStartAngle = spacemanAngles[man];
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragging >= 0) {
                    // Dragging right turns him to his left, as if spinning him by the arm
                    spacemanAngles[dragging] = dragStartAngle - (e.getX() - dragStartX) * 0.6f;
                    Rectangle b = spacemanBounds[dragging];
                    repaint(b.x - 40, b.y - 20, b.width + 80, b.height + 60);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = -1;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                boolean overButton = buttonBox.contains(e.getPoint()) && buttonEnabled();
                if (overButton != buttonHovered) {
                    buttonHovered = overButton;
                    repaint(buttonBox);
                }
                setCursor(spacemanAt(e.getX(), e.getY()) >= 0 || overButton ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(turning);
        addMouseMotionListener(turning);
        repaintTimer = new Timer(REPAINT_MS, e -> {
            // Multiplayer: both players have pressed Begin, so in we go (each PC sees both BEGINs
            // within one message's trip of the other)
            if (begun && bothBegun()) start();
            // Only the stars and the computer's screen change from tick to tick
            if (background == null) {
                repaint();
            } else {
                repaint(windowBox);
                repaint(screenBox);
                repaint(tickerBox);
                repaint(buttonBox);
            }
        });
        repaintTimer.setCoalesce(true);
        repaintTimer.start();
        // Developer aid: -Dxenoguesser.loadshot=seconds saves a picture of this screen then
        String shot = Dev.property("xenoguesser.loadshot");
        if (shot != null) {
            Timer capture = new Timer((int) (Float.parseFloat(shot) * 1000), e -> {
                BufferedImage image = new BufferedImage(Math.max(1, getWidth()), Math.max(1, getHeight()), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                paint(g);
                g.dispose();
                try {
                    ImageIO.write(image, "png", new File(new File(RunFiles.WORLD_DIR).getParentFile(), "loading_screen.png"));
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });
            capture.setRepeats(false);
            capture.start();
        }
    }

    /** Loads the cockpit art, making it first (in the background) if it isn't there yet. */
    private void loadArt() {
        Thread loader = new Thread(() -> {
            LoadingArt.ensure();
            try {
                BufferedImage cockpitImage = ImageIO.read(new File(LoadingArt.COCKPIT));
                // (in multiplayer: this player's colour, and the partner's)
                BufferedImage sheet = ImageIO.read(new File(role == null ? LoadingArt.SPACEMAN : LoadingArt.spaceman(role)));
                BufferedImage partnerSheet = role == null ? null : ImageIO.read(new File(LoadingArt.spaceman(role.other())));
                Properties loaded = LoadingArt.readLayout();
                TransmissionMessage loadedMessage = TransmissionMessage.load(
                        role == null ? TransmissionMessage.SINGLEPLAYER
                                : role == Multiplayer.Role.HOST ? TransmissionMessage.MULTIPLAYER_A : TransmissionMessage.MULTIPLAYER_B, worldSeed);
                javax.swing.SwingUtilities.invokeLater(() -> {
                    message = loadedMessage;
                    if (message != null && !skipped) message.play();
                    layout = loaded;
                    spacemanSheets[0] = sheet;
                    spacemanSheets[1] = partnerSheet;
                    cockpit = cockpitImage;
                    layersWidth = -1;
                    repaint();
                });
            } catch (Exception e) {
                System.err.println("Loading screen art unavailable: " + e.getMessage());
            }
        }, "loading-art");
        loader.setDaemon(true);
        loader.start();
    }

    public void stop() {
        repaintTimer.stop();
        if (message != null) message.stop();
    }

    /** The world is built; enter is run when the player chooses to start. */
    public void setWorldReady(Runnable enter) {
        enterGame = enter;
        // The button now appears, offering to start
        repaint(buttonBox);
        // Developer aid: -Dxenoguesser.mpautobegin=ms presses Skip and Begin that long after the
        // world is ready (trying again until Begin can be pressed), for testing two windows
        Integer autoBegin = Dev.integer("xenoguesser.mpautobegin");
        if (role != null && autoBegin != null) {
            Timer press = new Timer(500, null);
            press.setInitialDelay(autoBegin);
            press.addActionListener(e -> {
                if (enterGame == null || begun) {
                    press.stop();
                    return;
                }
                if (messagePlaying()) pressButton();
                pressButton();
            });
            press.start();
        }
    }

    private boolean messagePlaying() {
        return message != null && !skipped && !message.isFinished();
    }

    /**
     * The button appears only once the world is ready. In singleplayer it always starts the
     * game. In multiplayer (M16) Skip only stops the message; Begin is greyed, waiting, until the
     * partner's world is ready too, and again after it's pressed until the partner presses theirs.
     */
    private boolean buttonEnabled() {
        if (enterGame == null) return false;
        if (role == null || messagePlaying()) return true;
        return !waitingForPartner();
    }

    private String buttonText() {
        if (messagePlaying()) return "Skip";
        if (role != null && waitingForPartner()) return "Waiting for Player " + role.other().label;
        return "Begin";
    }

    /** Multiplayer: the partner's world isn't ready yet, or this player has pressed Begin and the partner hasn't. */
    private boolean waitingForPartner() {
        Multiplayer session = Multiplayer.active();
        if (session == null) return false;
        return !session.remoteWorldReady() || (begun && !session.remoteBegun());
    }

    private boolean bothBegun() {
        Multiplayer session = Multiplayer.active();
        return session != null && session.remoteBegun();
    }

    private void pressButton() {
        if (enterGame == null || !buttonEnabled()) return;
        boolean skipping = messagePlaying();
        skipped = true;
        if (message != null) message.stop();
        if (role == null) {
            start();
        } else if (!skipping && !begun) {
            begun = true;
            Multiplayer session = Multiplayer.active();
            if (session != null) session.sendBegin();
            System.out.println("[MP] Begin pressed at " + System.currentTimeMillis());
            buttonHovered = false;
            repaint();
        }
    }

    private void start() {
        Runnable enter = enterGame;
        enterGame = null;
        if (role != null && enter != null) System.out.println("[MP] Both players pressed Begin: into the game at " + System.currentTimeMillis());
        if (enter != null) enter.run();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth(), h = getHeight();
        if (cockpit == null || w <= 0 || h <= 0) {
            graphics.setColor(GREY);
            graphics.fillRect(0, 0, w, h);
            return;
        }
        if (layersWidth != w || layersHeight != h) buildLayers(w, h);

        long paintStart = System.nanoTime();
        Graphics2D g = (Graphics2D) graphics.create();
        Rectangle clip = g.getClipBounds();
        g.drawImage(background, 0, 0, null);
        if (clip == null || clip.intersects(windowBox)) drawStars(g);
        if (clip == null || clip.intersects(screenBox)) drawTerminal(g);
        drawSpacemen(g);
        layoutStrip(w, h);
        if (message != null && (clip == null || clip.intersects(tickerBox))) drawTicker(g);
        if (enterGame != null && (clip == null || clip.intersects(buttonBox))) drawButton(g);
        g.dispose();
        paintNanos += System.nanoTime() - paintStart;
    }

    /**
     * The transcript scrolling along the bottom in step with the voice: the word being
     * spoken reaches the middle of the screen as it is said; what has been said is bright,
     * what is to come dim.
     */
    private void layoutStrip(int w, int h) {
        int stripH = Math.max(44, h / 20);
        tickerBox = new Rectangle(0, h - stripH, w, stripH);
        int bw = Math.max(190, w / 9), bh = Math.max(40, stripH - 4);
        // (wider for the multiplayer "Waiting for Player 119-B")
        if (enterGame != null) {
            java.awt.FontMetrics fm = getFontMetrics(HudStyle.font(Font.BOLD, bh * 0.42f));
            bw = Math.max(bw, fm.stringWidth(buttonText()) + bh);
        }
        Rectangle box = new Rectangle(w - bw - 24, h - stripH - bh - 18, bw, bh);
        // (a narrower button leaves the old one's edge behind unless that's drawn over too)
        if (!box.equals(buttonBox)) repaint(buttonBox.union(box));
        buttonBox = box;
    }

    private void drawTicker(Graphics2D target) {
        int w = getWidth();
        int stripH = tickerBox.height;
        Graphics2D g = (Graphics2D) target.create();
        HudStyle.smooth(g);
        g.clip(tickerBox);
        g.setColor(new Color(6, 9, 14, 215));
        g.fill(tickerBox);
        g.setColor(new Color(120, 205, 235, 120));
        g.fillRect(0, tickerBox.y, w, 1);

        String text = message.transcript();
        // (laid out again whenever the text changes: when the planet's name is settled)
        if (tickerFont == null || tickerFont.getSize2D() != stripH * 0.42f || text != charXText) {
            charXText = text;
            tickerFont = HudStyle.font(Font.PLAIN, stripH * 0.42f);
            charX = new float[text.length() + 1];
            java.awt.font.FontRenderContext frc = g.getFontRenderContext();
            for (int i = 1; i <= text.length(); i++) {
                charX[i] = (float) tickerFont.getStringBounds(text, 0, i, frc).getWidth();
            }
        }
        float spoken = skipped ? text.length() : message.spokenCharacters();
        int whole = Math.min(text.length(), (int) spoken);
        float at = whole >= text.length() ? charX[text.length()]
                : charX[whole] + (charX[Math.min(text.length(), whole + 1)] - charX[whole]) * (spoken - whole);
        // Once it's all been said the text carries on left at the pace it went, until it's gone
        if (message.isFinished() && !skipped) {
            if (tickerDoneAt == 0L) tickerDoneAt = System.nanoTime();
            float pace = charX[text.length()] / Math.max(1f, message.durationMillis());
            at += pace * (System.nanoTime() - tickerDoneAt) / 1_000_000f;
        }
        float anchor = w * 0.5f;
        float x = anchor - at;
        // The planet's name is about to scroll into view: settled now, whether the world's own is ready or not
        int nameAt = message.nameChar();
        if (!message.nameSettled() && nameAt >= 0 && x + charX[Math.min(nameAt, text.length())] < w + stripH * 4f) message.settleName();
        // Skipped, or scrolled right off: nothing left to show
        boolean gone = skipped || x + charX[text.length()] < 0f;
        float baseline = tickerBox.y + stripH * 0.64f;
        g.setFont(tickerFont);
        if (!gone) {
            g.setColor(new Color(150, 165, 180, 140));
            g.drawString(text, x, baseline);
            Graphics2D said = (Graphics2D) g.create();
            said.clipRect(0, tickerBox.y, Math.round(anchor), stripH);
            said.setColor(new Color(235, 245, 255));
            said.drawString(text, x, baseline);
            said.dispose();
        }
        // The source, over a fade at the left
        float labelX = stripH * 0.4f + stripH * 0.22f + 10;
        int labelW = Math.round(labelX + HudStyle.labelWidth(g, "Xenocorp transmission", stripH * 0.25f) + stripH * 1.6f);
        g.setPaint(new java.awt.GradientPaint(labelW - stripH * 1.4f, 0, new Color(6, 9, 14, 255), labelW, 0, new Color(6, 9, 14, 0)));
        g.fillRect(0, tickerBox.y + 1, labelW, stripH);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0 && messagePlaying();
        g.setColor(blink ? new Color(245, 90, 70) : new Color(110, 40, 35));
        int dot = Math.round(stripH * 0.22f);
        g.fillOval(Math.round(stripH * 0.4f), tickerBox.y + (stripH - dot) / 2, dot, dot);
        HudStyle.label(g, "Xenocorp transmission", stripH * 0.4f + dot + 10, tickerBox.y + stripH * 0.6f, stripH * 0.25f, HudStyle.LABEL);
        g.dispose();
    }

    private void drawButton(Graphics2D target) {
        int bw = buttonBox.width, bh = buttonBox.height;
        Graphics2D g = (Graphics2D) target.create();
        HudStyle.smooth(g);
        boolean enabled = buttonEnabled();
        java.awt.geom.Path2D shape = HudStyle.chamfered(buttonBox.x + 0.5f, buttonBox.y + 0.5f, bw - 1, bh - 1, 10);
        g.setColor(!enabled ? new Color(30, 36, 44, 220) : buttonHovered ? new Color(40, 150, 185, 235) : new Color(16, 70, 92, 230));
        g.fill(shape);
        g.setColor(enabled ? HudStyle.ACCENT : new Color(110, 125, 140));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(shape);
        String text = buttonText();
        g.setFont(HudStyle.font(Font.BOLD, bh * 0.42f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.setColor(enabled ? Color.WHITE : new Color(150, 160, 172));
        float arrow = enabled ? bh * 0.22f : 0f, gap = enabled ? bh * 0.25f : 0f;
        float tx = buttonBox.x + (bw - fm.stringWidth(text) - gap - arrow) / 2f;
        g.drawString(text, tx, buttonBox.y + (bh + fm.getAscent() - fm.getDescent()) / 2f);
        if (enabled) {
            // A small arrow after the word
            float ax = tx + fm.stringWidth(text) + gap, cy = buttonBox.y + bh / 2f;
            java.awt.geom.Path2D tri = new java.awt.geom.Path2D.Float();
            tri.moveTo(ax, cy - arrow * 0.8f);
            tri.lineTo(ax + arrow, cy);
            tri.lineTo(ax, cy + arrow * 0.8f);
            tri.closePath();
            g.fill(tri);
        }
        g.dispose();
    }

    /** Prepares the cockpit layers for this window size. */
    private void buildLayers(int w, int h) {
        scale = Math.max(w / (float) LoadingArt.WIDTH, h / (float) LoadingArt.HEIGHT);
        offsetX = (w - LoadingArt.WIDTH * scale) / 2f;
        offsetY = (h - LoadingArt.HEIGHT * scale) / 2f;
        GraphicsConfiguration gc = getGraphicsConfiguration();

        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = scaled.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.drawImage(cockpit, Math.round(offsetX), Math.round(offsetY), Math.round(LoadingArt.WIDTH * scale), Math.round(LoadingArt.HEIGHT * scale), null);
        sg.dispose();

        background = gc != null ? gc.createCompatibleImage(w, h, Transparency.OPAQUE) : new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D bg = (Graphics2D) background.getGraphics();
        bg.setColor(Color.BLACK);
        bg.fillRect(0, 0, w, h);
        bg.drawImage(scaled, 0, 0, null);
        bg.dispose();

        Shape windowShape = polygon("window.bottomLeft", "window.bottomRight", "window.topRight", "window.topLeft");
        windowBox = windowShape.getBounds();
        windowBox.grow(2, 2);
        windowBox = windowBox.intersection(new Rectangle(0, 0, w, h));
        foreground = new BufferedImage(Math.max(1, windowBox.width), Math.max(1, windowBox.height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D fg = foreground.createGraphics();
        fg.drawImage(scaled, -windowBox.x, -windowBox.y, null);
        fg.dispose();
        starBuffer = gc != null ? gc.createCompatibleImage(foreground.getWidth(), foreground.getHeight(), Transparency.OPAQUE)
                : new BufferedImage(foreground.getWidth(), foreground.getHeight(), BufferedImage.TYPE_INT_RGB);

        screenBox = polygon("screen.bottomLeft", "screen.bottomRight", "screen.topRight", "screen.topLeft").getBounds();
        screenCorners = new float[][] { point(layout.getProperty("screen.topLeft", "0,0")), point(layout.getProperty("screen.topRight", "0,0")),
                point(layout.getProperty("screen.bottomRight", "0,0")), point(layout.getProperty("screen.bottomLeft", "0,0")) };
        warpedTerminal = null;
        layersWidth = w;
        layersHeight = h;
    }

    // ==========================================
    //          STARS
    // ==========================================

    private void resetStar(float[] star, boolean anywhere) {
        star[0] = (starRand.nextFloat() - 0.5f) * 2f;
        star[1] = (starRand.nextFloat() - 0.5f) * 1.2f;
        star[2] = anywhere ? 0.05f + starRand.nextFloat() : 1f;
    }

    /** Stars rushing towards the ship, drawn as streaks behind the cockpit's window frame. */
    // Developer aid: -Dxenoguesser.loadstats logs this screen's frame rate and longest stall every two seconds
    private static final boolean LOAD_STATS = Dev.property("xenoguesser.loadstats") != null;
    private long statsStart = System.nanoTime(), worstGap, paintNanos;
    private int statsFrames;

    private void recordFrame(long gap) {
        statsFrames++;
        worstGap = Math.max(worstGap, gap);
        long now = System.nanoTime();
        if (now - statsStart >= 2_000_000_000L) {
            System.out.printf("[LOADSCREEN] %.1f fps, longest frame %d ms, painting %.1f ms a frame%n", statsFrames / ((now - statsStart) / 1e9),
                    worstGap / 1_000_000, paintNanos / 1e6 / Math.max(1, statsFrames));
            paintNanos = 0;
            statsStart = now;
            statsFrames = 0;
            worstGap = 0;
        }
    }

    private void drawStars(Graphics2D target) {
        long now = System.nanoTime();
        if (LOAD_STATS) recordFrame(now - lastFrameNanos);
        float dt = Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        int bw = foreground.getWidth(), bh = foreground.getHeight();
        Graphics2D g = (Graphics2D) starBuffer.getGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, bw, bh);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float cx = bw * 0.5f, cy = bh * 0.5f;
        float focal = bw * 0.35f;
        float speed = 0.45f;
        for (float[] star : stars) {
            float before = star[2];
            star[2] -= speed * dt;
            if (star[2] <= 0.02f) {
                resetStar(star, false);
                continue;
            }
            float trail = Math.min(1f, before + speed * 0.06f);
            float x1 = cx + star[0] / star[2] * focal, y1 = cy + star[1] / star[2] * focal;
            if (x1 < -bw || x1 > 2 * bw || y1 < -bh || y1 > 2 * bh) {
                resetStar(star, false);
                continue;
            }
            if (x1 < 0 || x1 > bw || y1 < 0 || y1 > bh) continue;
            float x0 = cx + star[0] / trail * focal, y0 = cy + star[1] / trail * focal;
            float nearness = 1f - star[2];
            g.setColor(STAR_SHADES[Math.min(STAR_SHADES.length - 1, (int) (nearness * 1.2f * STAR_SHADES.length))]);
            g.setStroke(STAR_STROKES[Math.min(STAR_STROKES.length - 1, (int) (nearness * STAR_STROKES.length))]);
            g.drawLine(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
        }
        // The window frame, struts and anything in front of the window go back over the stars
        g.drawImage(foreground, 0, 0, null);
        g.dispose();
        target.drawImage(starBuffer, windowBox.x, windowBox.y, null);
    }

    // ==========================================
    //          THE COMPUTER
    // ==========================================

    /** Green phosphor text: the steps done so far, the one under way, a progress bar and the time left. */
    private void drawTerminal(Graphics2D g) {
        String label = progress.currentLabel();
        float target = progress.overallFraction();
        float secondsLeft = progress.estimatedSecondsLeft();
        boolean failed = label.startsWith("Loading failed");
        if (lastLabel != null && !lastLabel.equals(label) && !failed) {
            finishedSteps.add(lastLabel);
        }
        lastLabel = label;

        smoothedFraction += (target - smoothedFraction) * 0.18f;
        int percent = Math.round(smoothedFraction * 100);
        boolean blink = (System.currentTimeMillis() / 450) % 2 == 0;
        String key = label + "|" + finishedSteps.size() + "|" + percent + "|" + Math.round(secondsLeft) + "|" + blink;
        if (!key.equals(terminalKey) || warpedTerminal == null) {
            terminal = renderTerminal(label, failed, percent, secondsLeft, blink);
            terminalKey = key;
            warpedTerminal = warp(terminal);
        }
        if (warpedTerminal != null) g.drawImage(warpedTerminal, screenBox.x, screenBox.y, null);
    }

    /**
     * The terminal's picture laid onto the screen as the monitor is seen, in perspective: each
     * pixel of the screen's box is taken back through the projective map from the picture's
     * rectangle to the screen's four corners and sampled there (bilinearly), with a thin dark
     * margin of glass left round it. Pixels outside the screen are left clear.
     */
    private BufferedImage warp(BufferedImage source) {
        int w = screenBox.width, h = screenBox.height;
        if (w <= 0 || h <= 0 || screenCorners[0] == null) return null;
        // The map from the unit square ((0,0) top left to (1,1) bottom right) to the corners
        float[] c0 = screenCorners[0], c1 = screenCorners[1], c2 = screenCorners[2], c3 = screenCorners[3];
        double sx = c0[0] - c1[0] + c2[0] - c3[0], sy = c0[1] - c1[1] + c2[1] - c3[1];
        double dx1 = c1[0] - c2[0], dx2 = c3[0] - c2[0], dy1 = c1[1] - c2[1], dy2 = c3[1] - c2[1];
        double det = dx1 * dy2 - dx2 * dy1;
        if (Math.abs(det) < 1e-9) return null;
        double g7 = (sx * dy2 - dx2 * sy) / det, h8 = (dx1 * sy - sx * dy1) / det;
        double a = c1[0] - c0[0] + g7 * c1[0], b = c3[0] - c0[0] + h8 * c3[0], cc = c0[0];
        double d = c1[1] - c0[1] + g7 * c1[1], e = c3[1] - c0[1] + h8 * c3[1], f = c0[1];
        // ...and its inverse, from the window back to the square
        double i00 = e - f * h8, i01 = cc * h8 - b, i02 = b * f - cc * e;
        double i10 = f * g7 - d, i11 = a - cc * g7, i12 = cc * d - a * f;
        double i20 = d * h8 - e * g7, i21 = b * g7 - a * h8, i22 = a * e - b * d;
        float margin = 1f / 30f;
        int sw = source.getWidth(), sh = source.getHeight();
        int[] src = source.getRGB(0, 0, sw, sh, null, 0, sw);
        int[] out = new int[w * h];
        int glass = 0xFF040E07;
        for (int y = 0; y < h; y++) {
            double py = screenBox.y + y + 0.5;
            for (int x = 0; x < w; x++) {
                double px = screenBox.x + x + 0.5;
                double q = i20 * px + i21 * py + i22;
                double u = (i00 * px + i01 * py + i02) / q, v = (i10 * px + i11 * py + i12) / q;
                if (u < 0 || u > 1 || v < 0 || v > 1) continue;
                double su = (u - margin) / (1 - 2 * margin), sv = (v - margin) / (1 - 2 * margin);
                if (su < 0 || su > 1 || sv < 0 || sv > 1) {
                    out[y * w + x] = glass;
                    continue;
                }
                out[y * w + x] = sample(src, sw, sh, (float) (su * sw - 0.5), (float) (sv * sh - 0.5));
            }
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, out, 0, w);
        return image;
    }

    /** The picture's colour at a point between its pixels, blended from the four round it. */
    private static int sample(int[] src, int w, int h, float x, float y) {
        int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(x))), y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(y)));
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float fx = Math.max(0f, Math.min(1f, x - x0)), fy = Math.max(0f, Math.min(1f, y - y0));
        int p00 = src[y0 * w + x0], p10 = src[y0 * w + x1], p01 = src[y1 * w + x0], p11 = src[y1 * w + x1];
        int result = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            float top = ((p00 >> shift) & 255) * (1 - fx) + ((p10 >> shift) & 255) * fx;
            float bottom = ((p01 >> shift) & 255) * (1 - fx) + ((p11 >> shift) & 255) * fx;
            result |= Math.round(top * (1 - fy) + bottom * fy) << shift;
        }
        return result;
    }

    private BufferedImage renderTerminal(String label, boolean failed, int percent, float secondsLeft, boolean blink) {
        // Rendered small and scaled up, so the text has the chunky look of an old screen
        int cols = 30, rows = 11;
        BufferedImage image = new BufferedImage(cols * 9, rows * 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D t = image.createGraphics();
        t.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        t.setColor(new Color(4, 20, 9));
        t.fillRect(0, 0, image.getWidth(), image.getHeight());
        t.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        Color text = failed ? PHOSPHOR_FAIL : PHOSPHOR;

        List<String> lines = new ArrayList<>();
        if (multiplayer) {
            lines.add("XENO-NAV 2.7   MULTIPLAYER");
            lines.add("LINK 119-A <> 119-B");
        } else {
            lines.add("XENO-NAV 2.7   PLANETFALL");
            lines.add("WORLD SEED " + worldSeed);
        }
        lines.add("");
        int shown = Math.max(0, finishedSteps.size() - 3);
        for (int i = shown; i < finishedSteps.size(); i++) {
            lines.add(fit("> " + finishedSteps.get(i).toUpperCase(), cols - 4) + " OK");
        }
        lines.add(fit("> " + label.toUpperCase(), cols - 2) + (blink && !failed ? "_" : ""));
        int barCells = cols - 8;
        int filled = Math.round(percent / 100f * barCells);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barCells; i++) bar.append(i < filled ? '#' : '.');
        bar.append("] ").append(String.format("%3d%%", percent));
        while (lines.size() < rows - 3) lines.add("");
        lines.add(bar.toString());
        lines.add(failed ? "SYSTEM HALTED" : secondsLeft >= 1f ? String.format("ETA %d S", Math.round(secondsLeft)) : "STAND BY");

        for (int i = 0; i < lines.size() && i < rows; i++) {
            // A soft glow behind each line, then the line itself
            t.setColor(new Color(text.getRed(), text.getGreen(), text.getBlue(), 50));
            t.drawString(lines.get(i), 5, 15 + i * 16 + 1);
            t.drawString(lines.get(i), 7, 15 + i * 16 - 1);
            t.setColor(i < 2 ? PHOSPHOR_DIM : text);
            t.drawString(lines.get(i), 6, 15 + i * 16);
        }
        // Scanlines, and a faint glare across the curved glass
        t.setColor(new Color(0, 0, 0, 70));
        for (int y = 0; y < image.getHeight(); y += 3) t.drawLine(0, y, image.getWidth(), y);
        t.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 34), image.getWidth() * 0.6f, image.getHeight() * 0.6f, new Color(255, 255, 255, 0)));
        t.fillRect(0, 0, image.getWidth(), image.getHeight());
        t.dispose();
        return image;
    }

    private static String fit(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "~";
    }

    // ==========================================
    //          THE SPACEMAN
    // ==========================================

    /**
     * The spacemen on the cockpit floor: this player at the layout's spaceman.feet, and in
     * multiplayer the partner to their right, the same size, each with their employee number
     * under their feet; one hint under them that they can be turned.
     */
    private void drawSpacemen(Graphics2D g) {
        if (spacemanSheets[0] == null) return;
        float[] feet = point(layout.getProperty("spaceman.feet", "420,930"));
        float heightPx = Float.parseFloat(layout.getProperty("spaceman.height", "557")) * scale;
        float drawHeight = heightPx * FRAME_SPAN_UNITS / SPACEMAN_HEIGHT_UNITS;
        float drawWidth = drawHeight * LoadingArt.FRAME_WIDTH / LoadingArt.FRAME_HEIGHT;
        float unit = getHeight() / 1080f;
        float labelY = feet[1] + drawWidth * 0.06f + 24 * unit;
        Graphics2D s = (Graphics2D) g.create();
        s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float hintX = feet[0];
        if (role != null && spacemanSheets[1] != null) {
            // The partner first, so this player is in front should they overlap
            float partnerX = feet[0] + PARTNER_ACROSS * drawWidth;
            drawSpaceman(s, 1, partnerX, feet[1], drawWidth, drawHeight);
            drawSpaceman(s, 0, feet[0], feet[1], drawWidth, drawHeight);
            drawName(s, role.label, feet[0], labelY, unit);
            drawName(s, role.other().label, partnerX, labelY, unit);
            hintX = (feet[0] + partnerX) / 2f;
            labelY += 26 * unit;
        } else {
            drawSpaceman(s, 0, feet[0], feet[1], drawWidth, drawHeight);
        }
        // As on the main menu: a hint under his feet (under both) that he can be turned
        HudStyle.label(s, "Drag to turn", hintX - HudStyle.labelWidth(s, "Drag to turn", 15 * unit) / 2f,
                labelY, 15 * unit, new Color(230, 236, 245, 220));
        s.dispose();
    }

    /** Spaceman man (0 this player, 1 the partner) from his turntable, his feet at (feetX, feetY). */
    private void drawSpaceman(Graphics2D s, int man, float feetX, float feetY, float drawWidth, float drawHeight) {
        int x = Math.round(feetX - drawWidth / 2f), y = Math.round(feetY - drawHeight * FEET_FRACTION);
        spacemanBounds[man] = new Rectangle(x + Math.round(drawWidth * 0.15f), y, Math.round(drawWidth * 0.7f), Math.round(drawHeight));
        Rectangle clip = s.getClipBounds();
        if (clip != null && !clip.intersects(new Rectangle(x, y, Math.round(drawWidth), Math.round(drawHeight * 1.05f)))) return;

        float step = 360f / LoadingArt.FRAMES;
        int frame = Math.floorMod(Math.round(spacemanAngles[man] / step), LoadingArt.FRAMES);
        int sx = (frame % LoadingArt.FRAME_COLUMNS) * LoadingArt.FRAME_WIDTH;
        int sy = (frame / LoadingArt.FRAME_COLUMNS) * LoadingArt.FRAME_HEIGHT;
        // A soft shadow on the floor beneath him
        s.setColor(new Color(0, 0, 0, 90));
        s.fillOval(Math.round(feetX - drawWidth * 0.32f), Math.round(feetY - drawWidth * 0.06f), Math.round(drawWidth * 0.64f), Math.round(drawWidth * 0.14f));
        s.drawImage(spacemanSheets[man], x, y, x + Math.round(drawWidth), y + Math.round(drawHeight),
                sx, sy, sx + LoadingArt.FRAME_WIDTH, sy + LoadingArt.FRAME_HEIGHT, null);
    }

    /** An employee number ("119-A") centred under a spaceman's feet. */
    private void drawName(Graphics2D s, String label, float centreX, float baseline, float unit) {
        HudStyle.label(s, label, centreX - HudStyle.labelWidth(s, label, 15 * unit) / 2f, baseline, 15 * unit, HudStyle.ACCENT);
    }

    /** Which spaceman is at a point: 0 this player, 1 the partner, -1 neither. */
    private int spacemanAt(int x, int y) {
        for (int man = 0; man < 2; man++) {
            if (spacemanSheets[man] != null && spacemanBounds[man].contains(x, y)) return man;
        }
        return -1;
    }

    // ==========================================
    //          HELPERS
    // ==========================================

    private Shape polygon(String... keys) {
        Path2D.Float path = new Path2D.Float();
        for (int i = 0; i < keys.length; i++) {
            float[] p = point(layout.getProperty(keys[i], "0,0"));
            if (i == 0) path.moveTo(p[0], p[1]); else path.lineTo(p[0], p[1]);
        }
        path.closePath();
        return path;
    }

    private float[] point(String value) {
        String[] parts = value.split(",");
        return new float[] { Float.parseFloat(parts[0]) * scale + offsetX, Float.parseFloat(parts[1]) * scale + offsetY };
    }
}
