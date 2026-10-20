import java.awt.*;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * What's laid over the landing pod (see PodSequence) while a world loads: the message from
 * Xenocorp, played once the pod's in view, its words scrolling along a strip at the bottom;
 * and when the game may begin. Until the pod's first frame is drawn it covers the window,
 * plain black, saying it's starting up; then it shrinks to its strip.
 *
 * The game starts when the pod's door is opened (E at the door, once it's landed and the world
 * is ready; in multiplayer once both players have said they're ready, either opening it for
 * both: see PodSequence).
 */
public class LoadingScreen extends JComponent {

    private static final int REPAINT_MS = 16;

    private final long worldSeed;
    private final Timer repaintTimer;

    // The message from Xenocorp, and where each character of its transcript starts
    private TransmissionMessage message;
    private float[] charX;
    private String charXText;
    private Font tickerFont;
    // When the message finished, for scrolling its text off afterwards
    private long tickerDoneAt;
    private boolean skipped;
    private Runnable enterGame;
    // This PC's player in multiplayer (null in singleplayer)
    private final Multiplayer.Role role;
    // Whether the pod is in view (the strip, not the whole window, is shown then), and told when the strip's done with
    private boolean podShowing;
    private Consumer<Boolean> onStripShown;

    public LoadingScreen(long worldSeed, boolean multiplayer) {
        this.worldSeed = worldSeed;
        Multiplayer session = Multiplayer.active();
        this.role = !multiplayer ? null : session != null ? session.role : Multiplayer.Role.HOST;
        setOpaque(true);
        loadMessage();
        repaintTimer = new Timer(REPAINT_MS, e -> {
            if (podShowing && tickerGone() && isVisible()) {
                setVisible(false);
                if (onStripShown != null) onStripShown.accept(false);
            }
            repaint();
        });
        repaintTimer.setCoalesce(true);
        repaintTimer.start();
    }

    /** This PC's player in multiplayer, null in singleplayer. */
    public Multiplayer.Role role() {
        return role;
    }

    private void loadMessage() {
        Thread loader = new Thread(() -> {
            TransmissionMessage loaded = TransmissionMessage.load(
                    role == null ? TransmissionMessage.SINGLEPLAYER
                            : role == Multiplayer.Role.HOST ? TransmissionMessage.MULTIPLAYER_A : TransmissionMessage.MULTIPLAYER_B, worldSeed);
            javax.swing.SwingUtilities.invokeLater(() -> {
                message = loaded;
                if (podShowing && message != null && !skipped) playMessage();
            });
        }, "loading-message");
        loader.setDaemon(true);
        loader.start();
    }

    /** The pod's in view: the message begins, and from now on only the strip at the bottom is shown. */
    public void podShowing() {
        if (podShowing) return;
        podShowing = true;
        if (message != null && !skipped) playMessage();
        repaint();
    }

    public boolean isPodShowing() {
        return podShowing;
    }

    /** Starts the message, off the UI thread: opening the sound device can take a moment, which the pod's frames would wait on. */
    private void playMessage() {
        TransmissionMessage playing = message;
        Thread start = new Thread(playing::play, "message-start");
        start.setDaemon(true);
        start.start();
    }

    /** Told (false) when the strip has nothing left to show and has gone. */
    public void setOnStripGone(Consumer<Boolean> listener) {
        onStripShown = listener;
    }

    /** The strip's height for a window this tall. */
    public static int stripHeight(int paneHeight) {
        return Math.max(44, paneHeight / 20);
    }

    public void stop() {
        repaintTimer.stop();
        if (message != null) message.stop();
    }

    /** The world is built; enter is run when the pod's door is opened. */
    public void setWorldReady(Runnable enter) {
        enterGame = enter;
    }

    /** Whether the world's ready for the hatch to be opened. */
    public boolean worldReady() {
        return enterGame != null;
    }

    public boolean messagePlaying() {
        return message != null && !skipped && !message.isFinished();
    }

    /** The message itself (null until it's loaded), for the targets it speaks of (see PodSequence). */
    public TransmissionMessage message() {
        return skipped ? null : message;
    }

    /** Enter: the message stops; false if none was playing. */
    public boolean skipMessage() {
        if (!messagePlaying()) return false;
        skipped = true;
        if (message != null) message.stop();
        repaint();
        return true;
    }

    /** The pod's door opened (here, or by the partner): the message stops and the game starts. */
    public void openHatch() {
        if (enterGame == null) return;
        skipped = true;
        if (message != null) message.stop();
        start();
    }

    private void start() {
        Runnable enter = enterGame;
        enterGame = null;
        if (role != null && enter != null) System.out.println("[MP] The door's open: into the game at " + System.currentTimeMillis());
        if (enter != null) enter.run();
    }

    /** Whether the strip has nothing more to show: the message skipped, or said and scrolled off. */
    private boolean tickerGone() {
        if (message == null) return false;
        if (skipped) return true;
        if (!message.isFinished() || charX == null) return false;
        float pace = charX[charX.length - 1] / Math.max(1f, message.durationMillis());
        long since = tickerDoneAt == 0L ? 0L : System.nanoTime() - tickerDoneAt;
        return getWidth() * 0.5f - charX[charX.length - 1] - pace * since / 1_000_000f < 0f;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth(), h = getHeight();
        Graphics2D g = (Graphics2D) graphics.create();
        if (!podShowing) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            HudStyle.smooth(g);
            HudStyle.label(g, "Starting up", w / 2f - HudStyle.labelWidth(g, "Starting up", 14f) / 2f, h * 0.55f, 14f, new Color(110, 255, 150, 200));
        } else if (message != null) {
            drawTicker(g, w, h);
        } else {
            g.setColor(new Color(6, 9, 14));
            g.fillRect(0, 0, w, h);
        }
        g.dispose();
    }

    /**
     * The transcript scrolling along the strip in step with the voice: the word being spoken
     * reaches the middle of the screen as it is said; what has been said is bright, what is to
     * come dim.
     */
    private void drawTicker(Graphics2D g, int w, int stripH) {
        HudStyle.smooth(g);
        g.setColor(new Color(6, 9, 14));
        g.fillRect(0, 0, w, stripH);
        g.setColor(new Color(120, 205, 235, 120));
        g.fillRect(0, 0, w, 1);

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
        boolean gone = skipped || x + charX[text.length()] < 0f;
        float baseline = stripH * 0.64f;
        g.setFont(tickerFont);
        if (!gone) {
            g.setColor(new Color(150, 165, 180, 140));
            g.drawString(text, x, baseline);
            Graphics2D said = (Graphics2D) g.create();
            said.clipRect(0, 0, Math.round(anchor), stripH);
            said.setColor(new Color(235, 245, 255));
            said.drawString(text, x, baseline);
            said.dispose();
        }
        // The source, over a fade at the left
        float labelX = stripH * 0.4f + stripH * 0.22f + 10;
        int labelW = Math.round(labelX + HudStyle.labelWidth(g, "Xenocorp transmission", stripH * 0.25f) + stripH * 1.6f);
        g.setPaint(new java.awt.GradientPaint(labelW - stripH * 1.4f, 0, new Color(6, 9, 14, 255), labelW, 0, new Color(6, 9, 14, 0)));
        g.fillRect(0, 1, labelW, stripH);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0 && messagePlaying();
        g.setColor(blink ? new Color(245, 90, 70) : new Color(110, 40, 35));
        int dot = Math.round(stripH * 0.22f);
        g.fillOval(Math.round(stripH * 0.4f), (stripH - dot) / 2, dot, dot);
        HudStyle.label(g, "Xenocorp transmission", stripH * 0.4f + dot + 10, stripH * 0.6f, stripH * 0.25f, HudStyle.LABEL);
        // ("[Enter] Skip" at its right, while it's being said)
        if (messagePlaying()) MessageTicker.drawSkipHint(g, w, 0, stripH);
    }

    // How long the score panel and its arrow are shown from when "productivity" is said (M50:
    // three times the 5.7 s to "Departing employees" they had), fading in and out over the ends;
    // and when they were first shown (0 not yet)
    private static final long TARGETS_SHOWN_MS = 17_200L, TARGETS_FADE_MS = 500L;
    private long targetsShownAt;

    /**
     * Singleplayer: from when the message says "productivity" (of the targets that must be met),
     * for TARGETS_SHOWN_MS (on past the message's end, if need be, unless it's skipped), how
     * opaque the score panel (as it'll be in January) and its arrow are to be shown; 0 when not.
     */
    public float targetsAlpha() {
        if (role != null || message == null || skipped) return 0f;
        long now = System.currentTimeMillis();
        if (targetsShownAt == 0L) {
            int from = message.transcript().indexOf("productivity targets");
            if (from < 0 || !messagePlaying() || message.spokenCharacters() < from) return 0f;
            targetsShownAt = now;
        }
        long shown = now - targetsShownAt;
        if (shown >= TARGETS_SHOWN_MS) return 0f;
        return Math.max(0f, Math.min(1f, Math.min(shown / (float) TARGETS_FADE_MS, (TARGETS_SHOWN_MS - shown) / (float) TARGETS_FADE_MS)));
    }

    /** The score panel as it'll be in January at the top left, and an arrow bobbing under it pointing up at the quarter's target. */
    public static void paintTargets(Graphics2D g) {
        HudStyle.smooth(g);
        int quarterTarget = Math.round(GameHUD.TARGETS[0] * GameMode.current().targetShare / 50f) * 50;
        HudStyle.paintScorePanel(g, 0, 0, 0, quarterTarget, 0, HudStyle.GLASS);
        float bob = (float) Math.sin(System.nanoTime() / 1.6e8) * 6f;
        float tipX = 232f, tipY = HudStyle.SCORE_H + 6f + bob, tailY = tipY + 64f;
        g.setColor(HudStyle.GOLD);
        g.setStroke(new java.awt.BasicStroke(6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Float(tipX, tipY + 10f, tipX, tailY));
        java.awt.geom.Path2D head = new java.awt.geom.Path2D.Float();
        head.moveTo(tipX, tipY);
        head.lineTo(tipX - 13f, tipY + 18f);
        head.lineTo(tipX + 13f, tipY + 18f);
        head.closePath();
        g.fill(head);
        HudStyle.label(g, "Your target for January to March", tipX - 120f, tailY + 26f, 13f, HudStyle.GOLD);
    }

    /** The size the targets' picture needs (see paintTargets). */
    public static Dimension targetsSize() {
        return new Dimension(HudStyle.SCORE_W + 40, HudStyle.SCORE_H + 150);
    }
}
