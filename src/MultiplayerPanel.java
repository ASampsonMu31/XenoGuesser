import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * Play Multiplayer's panel, over the main menu (which stays behind it, dimmed): host a game
 * and share its 5-letter room code, or join a partner's game by typing theirs. It's the
 * game's standard panel (HudStyle.panel) with the main menu's buttons (HudStyle.button), and
 * goes through these states:
 *   CHOOSE    - Host game, Join game, Back.
 *   HOSTING   - "Starting room...", then the code in large letters, waiting for the partner; Cancel.
 *   JOIN      - five boxes for the code (typed: letters, Backspace, Enter joins, Esc goes back,
 *               Ctrl+V pastes); Join (once all five are in) and Back.
 *   JOINING   - "Looking for room KXQPB...", then "Joining room..." once in it.
 *   CONNECTED - "Sam connected" / "Connected to Alex" (the partner's name) for a moment, then
 *               onReady runs (the game goes on to the loading screen).
 *   ERROR     - why it didn't work (no room, Steam not running, partner left, different
 *               versions); Back.
 * The panel takes the keyboard while it's up, so typing a code never reaches the game's keys.
 */
public class MultiplayerPanel extends JComponent {

    private enum State { CHOOSE, HOSTING, JOIN, JOINING, CONNECTED, ERROR }

    // How long "connected" shows before the loading screen (longer with a warning to read)
    private static final int CONNECTED_MS = 1500, CONNECTED_WARNING_MS = 4000;
    private static final float PANEL_W = 640f;

    private final LongSupplier hostSeed;
    private final Runnable onBack;
    private final Consumer<Multiplayer> onReady;
    private final Multiplayer.Listener sessionListener = new SessionEvents();
    private Multiplayer session;
    private State state = State.CHOOSE;
    private String typed = "";
    private String errorTitle = "", errorText = "";
    private final long openedAt = System.currentTimeMillis();
    private boolean finished;

    // The buttons of the state shown, each with what it does, laid out as it's painted
    private final List<Rectangle> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private final List<Boolean> enabled = new ArrayList<>();
    // Which of them the pointer is over (they're laid out afresh, in the same order, each paint), -1 none
    private int hovered = -1;
    private State paintedState;
    // (repaints for the caret's blink and the waiting dots)
    private final Timer blink = new Timer(400, e -> repaint());

    /**
     * hostSeed gives the world's seed when Host game is pressed; onBack closes the panel;
     * onReady runs (once) with the session when both players are in and the handshake is done.
     */
    public MultiplayerPanel(LongSupplier hostSeed, Runnable onBack, Consumer<Multiplayer> onReady) {
        this.hostSeed = hostSeed;
        this.onBack = onBack;
        this.onReady = onReady;
        setOpaque(false);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (e.getButton() != MouseEvent.BUTTON1) return;
                int b = buttonAt(e.getX(), e.getY());
                if (b >= 0) actions.get(b).run();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int over = buttonAt(e.getX(), e.getY());
                if (over != hovered) {
                    hovered = over;
                    repaint();
                }
                setCursor(over >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKey(e);
                // (nothing typed here reaches the window's own keys, such as Esc for the settings)
                e.consume();
            }

            @Override
            public void keyTyped(KeyEvent e) {
                if (state == State.JOIN && !e.isControlDown()) {
                    String letter = Multiplayer.cleanRoomCode(String.valueOf(e.getKeyChar()));
                    if (!letter.isEmpty() && typed.length() < Multiplayer.ROOM_CODE_LENGTH) {
                        typed += letter;
                        repaint();
                    }
                }
                e.consume();
            }
        });
        blink.start();
    }

    /** Takes the keyboard (once the panel is on screen). */
    public void takeFocus() {
        requestFocusInWindow();
    }

    /** Developer aid (-Dxenoguesser.mpauto): presses Host game, or Join game with any code (LocalTransport doesn't check it). */
    public void autoStart(boolean host) {
        if (host) {
            hostGame();
        } else {
            typed = "AAAAA";
            joinGame();
        }
    }

    // ---- What the buttons and keys do ----

    private void hostGame() {
        state = State.HOSTING;
        long seed = hostSeed.getAsLong();
        session = Multiplayer.host(seed, GameMode.current(), MapKind.current(), sessionListener);
        // The writing systems are made now, while the partner joins, and sent once they have (M18)
        WritingSystems.shareWhenReady(session, seed);
        repaint();
    }

    private void showJoin() {
        state = State.JOIN;
        typed = "";
        repaint();
    }

    private void joinGame() {
        if (typed.length() != Multiplayer.ROOM_CODE_LENGTH) return;
        state = State.JOINING;
        session = Multiplayer.join(typed, sessionListener);
        repaint();
    }

    /** Leaves any room being made or joined and goes back to the first choice. */
    private void cancel() {
        leaveSession();
        state = State.CHOOSE;
        repaint();
    }

    private void back() {
        leaveSession();
        close();
        onBack.run();
    }

    private void leaveSession() {
        if (session == null) return;
        session.removeListener(sessionListener);
        session.leave();
        session = null;
    }

    private void close() {
        finished = true;
        blink.stop();
    }

    private void handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        switch (state) {
            case CHOOSE -> {
                if (code == KeyEvent.VK_ESCAPE) back();
            }
            case JOIN -> {
                if (code == KeyEvent.VK_ESCAPE) {
                    state = State.CHOOSE;
                } else if (code == KeyEvent.VK_BACK_SPACE && !typed.isEmpty()) {
                    typed = typed.substring(0, typed.length() - 1);
                } else if (code == KeyEvent.VK_ENTER) {
                    joinGame();
                } else if (code == KeyEvent.VK_V && e.isControlDown()) {
                    paste();
                }
                repaint();
            }
            case HOSTING, JOINING -> {
                if (code == KeyEvent.VK_ESCAPE) cancel();
            }
            case ERROR -> {
                if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_ENTER) cancel();
            }
            default -> {
            }
        }
    }

    /** Ctrl+V: the clipboard's text, cleaned to the code's letters, after what's typed. */
    private void paste() {
        try {
            Object text = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            typed = Multiplayer.cleanRoomCode(typed + text);
        } catch (Exception e) {
            // Nothing usable on the clipboard
        }
    }

    /** What the session says, on the Swing thread. */
    private final class SessionEvents implements Multiplayer.Listener {
        @Override
        public void roomCode(String code) {
            repaint();
        }

        @Override
        public void changed() {
            repaint();
        }

        @Override
        public void ready() {
            if (finished || session == null) return;
            state = State.CONNECTED;
            repaint();
            System.out.println("[MP] Connected: " + session.localName() + " and " + session.remoteName()
                    + ", world seed " + session.worldSeed());
            Multiplayer connected = session;
            Timer next = new Timer(javaWarning() != null ? CONNECTED_WARNING_MS : CONNECTED_MS, e -> {
                if (finished || session != connected || state != State.CONNECTED) return;
                connected.removeListener(sessionListener);
                close();
                onReady.accept(connected);
            });
            next.setRepeats(false);
            next.start();
        }

        @Override
        public void failed(String reason) {
            showError(session != null && session.role == Multiplayer.Role.JOIN ? "Couldn't join" : "Couldn't connect", reason);
        }

        @Override
        public void disconnected(String reason) {
            showError("Disconnected", reason);
        }
    }

    private void showError(String title, String reason) {
        if (finished) return;
        if (session != null) session.removeListener(sessionListener);
        session = null;
        state = State.ERROR;
        errorTitle = title;
        errorText = reason;
        repaint();
    }

    /** A warning when the partner's Java differs from this PC's (M20), or null. */
    private String javaWarning() {
        String theirs = session != null ? session.remoteJavaVersion() : null;
        String ours = System.getProperty("java.version");
        if (theirs == null || theirs.equals(ours)) return null;
        return "Your partner runs Java " + theirs + " and you run " + ours + ": your worlds may differ slightly.";
    }

    // ---- Painting ----

    private int buttonAt(int x, int y) {
        for (int b = 0; b < buttons.size(); b++) {
            if (enabled.get(b) && buttons.get(b).contains(x, y)) return b;
        }
        return -1;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth(), h = getHeight();
        Graphics2D g = (Graphics2D) graphics.create();
        HudStyle.smooth(g);
        float unit = h / 1080f;
        // (another state's buttons: the pointer is over none of them until it next moves)
        if (state != paintedState) hovered = -1;
        paintedState = state;
        float pw = Math.min(PANEL_W * unit, w - 32f), pad = 40 * unit;
        // Laid out once on a scrap of image to measure it, then drawn centred on its panel
        java.awt.image.BufferedImage scrap = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D measure = scrap.createGraphics();
        HudStyle.smooth(measure);
        float ph = paintContents(measure, pw, pad, unit) + pad;
        measure.dispose();
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        HudStyle.panel(g, px, py, pw, ph, HudStyle.GLASS_SOLID);
        g.translate(px, py);
        paintContents(g, pw, pad, unit);
        for (Rectangle r : buttons) r.translate(Math.round(px), Math.round(py));
        g.dispose();
    }

    /**
     * The panel's contents for the state shown, in the panel's own coordinates (its top left at
     * 0, 0, pw wide), laying out the buttons; returns the bottom of the last thing in it.
     */
    private float paintContents(Graphics2D g, float pw, float pad, float unit) {
        buttons.clear();
        actions.clear();
        enabled.clear();
        float x = pad, y = pad + 14 * unit, inner = pw - pad * 2;
        HudStyle.label(g, "Multiplayer", x, y, 15 * unit, HudStyle.ACCENT);
        y += 52 * unit;

        switch (state) {
            case CHOOSE -> {
                y = heading(g, "Play with a partner", x, y, unit);
                y = paragraph(g, "Host a game and share its room code with your partner, or join theirs with the code they give you.",
                        x, y, inner, unit);
                y = paragraph(g, "The host's expedition settings are used: " + GameMode.current().label + ", "
                        + MapKind.current().label + " map.", x, y + 4 * unit, inner, unit, HudStyle.LABEL);
                y += 26 * unit;
                y = button(g, "Host game", true, true, this::hostGame, x, y, inner, unit);
                y = button(g, "Join game", true, false, this::showJoin, x, y, inner, unit);
                y = backButton(g, "Back", this::back, x, y + 10 * unit, unit);
            }
            case HOSTING -> {
                String code = session != null ? session.roomCode() : null;
                if (code == null) {
                    y = heading(g, "Starting room" + dots(), x, y, unit);
                    y = paragraph(g, "Opening a room for your partner to join.", x, y, inner, unit);
                } else {
                    HudStyle.label(g, "Room code", x, y, 13 * unit, HudStyle.LABEL);
                    y = bigCode(g, code, x, y + 16 * unit, unit);
                    y = paragraph(g, "Share this code with your partner.", x, y + 8 * unit, inner, unit);
                    boolean joining = session.isInRoom();
                    y = paragraph(g, (joining ? "Your partner is joining" : "Waiting for your partner") + dots(),
                            x, y + 6 * unit, inner, unit, HudStyle.ACCENT);
                }
                y = backButton(g, "Cancel", this::cancel, x, y + 26 * unit, unit);
            }
            case JOIN -> {
                y = heading(g, "Join a game", x, y, unit);
                y = paragraph(g, "Type the room code your partner sees on their screen.", x, y, inner, unit);
                y = codeBoxes(g, x, y + 20 * unit, inner, unit);
                y += 30 * unit;
                y = button(g, "Join", typed.length() == Multiplayer.ROOM_CODE_LENGTH, true, this::joinGame, x, y, inner, unit);
                y = backButton(g, "Back", () -> {
                    state = State.CHOOSE;
                    repaint();
                }, x, y + 10 * unit, unit);
            }
            case JOINING -> {
                String code = session != null ? session.roomCode() : typed;
                boolean inRoom = session != null && session.isInRoom();
                y = heading(g, inRoom ? "Joining room" + dots() : "Looking for room " + code + dots(), x, y, unit);
                y = paragraph(g, inRoom ? "Found it. Linking up with your partner." : "Searching for your partner's room.",
                        x, y, inner, unit);
                y = backButton(g, "Cancel", this::cancel, x, y + 26 * unit, unit);
            }
            case CONNECTED -> {
                boolean host = session == null || session.role == Multiplayer.Role.HOST;
                String partner = session != null ? session.remoteName() : "your partner";
                y = heading(g, host ? partner + " connected" : "Connected to " + partner, x, y, unit);
                y = paragraph(g, "Preparing for planetfall" + dots(), x, y, inner, unit, HudStyle.ACCENT);
                String warning = javaWarning();
                if (warning != null) y = paragraph(g, warning, x, y + 8 * unit, inner, unit, HudStyle.GOLD);
            }
            case ERROR -> {
                y = heading(g, errorTitle, x, y, unit);
                y = paragraph(g, errorText, x, y, inner, unit);
                y = backButton(g, "Back", this::cancel, x, y + 26 * unit, unit);
            }
        }
        return y;
    }

    private float heading(Graphics2D g, String text, float x, float y, float unit) {
        g.setFont(HudStyle.font(Font.BOLD, 34 * unit));
        g.setColor(Color.WHITE);
        g.drawString(text, x, y);
        return y + 50 * unit;
    }

    private float paragraph(Graphics2D g, String text, float x, float y, float width, float unit) {
        return paragraph(g, text, x, y, width, unit, new Color(205, 214, 226));
    }

    /** Text wrapped to width, from its first baseline at y; returns the baseline after it. */
    private float paragraph(Graphics2D g, String text, float x, float y, float width, float unit, Color colour) {
        g.setFont(HudStyle.font(Font.PLAIN, 19 * unit));
        g.setColor(colour);
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tried = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(tried) > width && line.length() > 0) {
                g.drawString(line.toString(), x, y);
                y += 28 * unit;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (line.length() > 0) {
            g.drawString(line.toString(), x, y);
            y += 28 * unit;
        }
        return y;
    }

    /** A full-width button with its top at y; returns where the next goes. */
    private float button(Graphics2D g, String text, boolean on, boolean primary, Runnable action,
                         float x, float y, float width, float unit) {
        Rectangle r = new Rectangle(Math.round(x), Math.round(y), Math.round(width), Math.round(64 * unit));
        boolean over = addButton(r, on, action);
        HudStyle.button(g, r, text, null, on, primary, over, unit);
        return y + (64 + 18) * unit;
    }

    /** The smaller Back / Cancel button, as the main menu's Quit, its top at y; returns its bottom. */
    private float backButton(Graphics2D g, String text, Runnable action, float x, float y, float unit) {
        Rectangle r = new Rectangle(Math.round(x), Math.round(y), Math.round(230 * unit), Math.round(52 * unit));
        boolean over = addButton(r, true, action);
        HudStyle.button(g, r, text, null, true, false, over, unit);
        return y + 52 * unit;
    }

    /** Lays out a button (in the panel's coordinates; moved onto the screen after painting); whether the pointer is over it. */
    private boolean addButton(Rectangle r, boolean on, Runnable action) {
        buttons.add(r);
        enabled.add(on);
        actions.add(action);
        return buttons.size() - 1 == hovered;
    }

    /** The host's room code in large spaced letters, its top at y; returns the baseline below it. */
    private float bigCode(Graphics2D g, String code, float x, float y, float unit) {
        Font f = HudStyle.font(Font.BOLD, 84 * unit).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.18f));
        g.setFont(f);
        float baseline = y + g.getFontMetrics().getAscent() * 0.82f;
        g.setColor(new Color(0, 0, 0, 140));
        g.drawString(code, x + 3 * unit, baseline + 4 * unit);
        g.setColor(HudStyle.GOLD);
        g.drawString(code, x, baseline);
        return y + 96 * unit;
    }

    /** The five boxes the code is typed into, the next one to fill lit with a blinking caret; returns their bottom. */
    private float codeBoxes(Graphics2D g, float x, float y, float width, float unit) {
        int n = Multiplayer.ROOM_CODE_LENGTH;
        float gap = 14 * unit, size = Math.min(92 * unit, (width - gap * (n - 1)) / n);
        g.setFont(HudStyle.font(Font.BOLD, size * 0.56f));
        FontMetrics fm = g.getFontMetrics();
        boolean caretOn = (System.currentTimeMillis() - openedAt) / 500 % 2 == 0;
        for (int i = 0; i < n; i++) {
            float bx = x + i * (size + gap);
            boolean next = i == typed.length();
            Path2D box = HudStyle.chamfered(bx + 0.5f, y + 0.5f, size - 1, size - 1, 10 * unit);
            g.setColor(next ? new Color(24, 60, 78, 235) : new Color(10, 14, 22, 230));
            g.fill(box);
            g.setColor(next ? HudStyle.ACCENT : HudStyle.EDGE);
            g.setStroke(new java.awt.BasicStroke((next ? 2.2f : 1.4f) * Math.max(1f, unit)));
            g.draw(box);
            if (i < typed.length()) {
                String letter = typed.substring(i, i + 1);
                g.setColor(Color.WHITE);
                g.drawString(letter, bx + (size - fm.stringWidth(letter)) / 2f, y + (size + fm.getAscent() - fm.getDescent()) / 2f);
            } else if (next && caretOn) {
                g.setColor(HudStyle.ACCENT);
                g.fill(new java.awt.geom.Rectangle2D.Float(bx + size * 0.3f, y + size * 0.74f, size * 0.4f, Math.max(2f, 3 * unit)));
            }
        }
        return y + size;
    }

    /** "", ".", "..", "..." in turn, for waiting. */
    private String dots() {
        return ".".repeat((int) ((System.currentTimeMillis() / 400) % 4));
    }
}
