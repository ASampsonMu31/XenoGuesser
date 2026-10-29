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
 * Play Multiplayer's panel, over the main menu (which stays behind it, dimmed): play with a
 * friend (host a game and share its 5-letter room code, or join theirs by typing it), or find
 * a match against whoever else is looking (M59), casual or ranked. It's the game's standard
 * panel (HudStyle.panel) with the main menu's buttons (HudStyle.button), and goes through
 * these states:
 *   CHOOSE    - Play with a friend, Find a match, Back.
 *   FRIEND    - Host game, Join game, Back.
 *   HOST_SETUP - the host's game mode and map, one of each (as radio buttons); Host game, Back.
 *   MATCH     - the Casual / Ranked switch (remembered), this player's rating, the game modes
 *               and maps they'll play (tick boxes, any number of each, remembered: see
 *               Expedition), Find match, Back.
 *   SEARCHING - "Finding an opponent..." and how long it's been; Cancel.
 *   HOSTING   - "Starting room...", then the code in large letters, waiting for the partner; Cancel.
 *   JOIN      - five boxes for the code (typed: letters, Backspace, Enter joins, Esc goes back,
 *               Ctrl+V pastes); Join (once all five are in) and Back.
 *   JOINING   - "Looking for room KXQPB...", then "Joining room..." once in it.
 *   CONNECTED - "Sam connected" / "Connected to Alex" (the partner's name) and the game mode
 *               and map to be played, for a moment, then onReady runs (the game goes on to the
 *               loading screen).
 *   ERROR     - why it didn't work (no room, Steam not running, partner left, different
 *               versions); Back.
 * The panel takes the keyboard while it's up, so typing a code never reaches the game's keys.
 */
public class MultiplayerPanel extends JComponent {

    private enum State { CHOOSE, FRIEND, HOST_SETUP, MATCH, SEARCHING, HOSTING, JOIN, JOINING, CONNECTED, ERROR }

    // How long "connected" shows before the loading screen (longer with a warning to read)
    private static final int CONNECTED_MS = 1500, CONNECTED_WARNING_MS = 4000;
    private static final float PANEL_W = 640f;

    private final LongSupplier hostSeed;
    private final Runnable onBack;
    private final Consumer<Multiplayer> onReady;
    private final Multiplayer.Listener sessionListener = new SessionEvents();
    private Multiplayer session;
    private State state = State.CHOOSE;
    // Where Cancel and an error's Back go: the friend's choices or matchmaking's
    private State home = State.CHOOSE;
    // Matchmaking's queue (remembered between games), and when the search began
    private boolean ranked = GameSettings.STORE.getBoolean("rankedQueue", false);
    private long searchStartedAt;
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

    /**
     * Developer aid (-Dxenoguesser.mpauto): =host presses Host game, =join Join game with any
     * code (LocalTransport doesn't check it), =match and =ranked Find match in that queue.
     */
    public void autoStart(String how) {
        if (how.equalsIgnoreCase("join")) {
            home = State.FRIEND;
            typed = "AAAAA";
            joinGame();
        } else if (how.equalsIgnoreCase("match") || how.equalsIgnoreCase("ranked")) {
            home = State.MATCH;
            ranked = how.equalsIgnoreCase("ranked");
            findMatch();
        } else {
            home = State.FRIEND;
            hostGame();
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

    private void show(State shown) {
        state = shown;
        if (shown == State.FRIEND || shown == State.MATCH) home = shown;
        repaint();
    }

    /** Matchmaking: looks for an opponent in the chosen queue; should this player host, it's their world and settings. */
    private void findMatch() {
        state = State.SEARCHING;
        searchStartedAt = System.currentTimeMillis();
        session = Multiplayer.matchmake(ranked, hostSeed.getAsLong(), Expedition.matchModes(), Expedition.matchMaps(), sessionListener);
        repaint();
    }

    private void toggleRanked() {
        ranked = !ranked;
        try {
            GameSettings.STORE.putBoolean("rankedQueue", ranked);
        } catch (Exception e) {
            // Remembered for this run only
        }
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

    /** Leaves any room being made, joined or searched for, and goes back to the choices it came from. */
    private void cancel() {
        leaveSession();
        state = home;
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
            case FRIEND, MATCH -> {
                if (code == KeyEvent.VK_ESCAPE) show(State.CHOOSE);
                else if (state == State.MATCH && code == KeyEvent.VK_ENTER) findMatch();
            }
            case HOST_SETUP -> {
                if (code == KeyEvent.VK_ESCAPE) show(State.FRIEND);
                else if (code == KeyEvent.VK_ENTER) hostGame();
            }
            case JOIN -> {
                if (code == KeyEvent.VK_ESCAPE) {
                    state = State.FRIEND;
                } else if (code == KeyEvent.VK_BACK_SPACE && !typed.isEmpty()) {
                    typed = typed.substring(0, typed.length() - 1);
                } else if (code == KeyEvent.VK_ENTER) {
                    joinGame();
                } else if (code == KeyEvent.VK_V && e.isControlDown()) {
                    paste();
                }
                repaint();
            }
            case HOSTING, JOINING, SEARCHING -> {
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
        public void matched(Multiplayer.Role role) {
            // The host's writing systems are made now, while the opponent links up, and sent once they have (M18)
            if (session != null && role == Multiplayer.Role.HOST) WritingSystems.shareWhenReady(session, session.worldSeed());
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
            showError(state == State.SEARCHING ? "Couldn't find a match"
                    : session != null && session.role == Multiplayer.Role.JOIN ? "Couldn't join" : "Couldn't connect", reason);
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
                y = heading(g, "Play multiplayer", x, y, unit);
                y = paragraph(g, "Play with a friend by sharing a room code, or find a match against whoever else is looking.",
                        x, y, inner, unit);
                y += 26 * unit;
                y = button(g, "Play with a friend", true, true, () -> show(State.FRIEND), x, y, inner, unit);
                y = button(g, "Find a match", true, false, () -> show(State.MATCH), x, y, inner, unit);
                y = backButton(g, "Back", this::back, x, y + 10 * unit, unit);
            }
            case FRIEND -> {
                y = heading(g, "Play with a friend", x, y, unit);
                y = paragraph(g, "Host a game and share its room code with your partner, or join theirs with the code they give you.",
                        x, y, inner, unit);
                y = paragraph(g, "The host chooses the game mode and map. Games with a friend are never ranked.", x, y + 4 * unit, inner, unit, HudStyle.LABEL);
                y += 26 * unit;
                y = button(g, "Host game", true, true, () -> show(State.HOST_SETUP), x, y, inner, unit);
                y = button(g, "Join game", true, false, this::showJoin, x, y, inner, unit);
                y = backButton(g, "Back", () -> show(State.CHOOSE), x, y + 10 * unit, unit);
            }
            case HOST_SETUP -> {
                y = heading(g, "Host a game", x, y, unit);
                y = paragraph(g, "Choose the game mode and map. Your partner plays the same; you'll get the room code to give them next.",
                        x, y, inner, unit);
                y = choiceRow(g, "Game mode", modeLabels(), m -> GameMode.current().ordinal() == m, false,
                        m -> GameMode.choose(GameMode.values()[m]), x, y + 34 * unit, inner, unit);
                y = choiceRow(g, "Map", mapLabels(), m -> MapKind.current().ordinal() == m, false,
                        m -> MapKind.choose(MapKind.values()[m]), x, y + 30 * unit, inner, unit);
                y += 30 * unit;
                y = button(g, "Host game", true, true, this::hostGame, x, y, inner, unit);
                y = backButton(g, "Back", () -> show(State.FRIEND), x, y + 10 * unit, unit);
            }
            case MATCH -> {
                y = heading(g, "Find a match", x, y, unit);
                y = queueSwitch(g, x, y - 20 * unit, inner, unit);
                y += 40 * unit;
                if (ranked) {
                    y = paragraph(g, "Ranked games change your rating: up when you win and down when you lose, by more the less "
                            + "expected the result. You're only matched with players also looking for a ranked game.", x, y, inner, unit);
                    y = paragraph(g, "Your rating: " + Rating.rating() + "   (" + Rating.wins() + " won, " + Rating.losses() + " lost)",
                            x, y + 4 * unit, inner, unit, HudStyle.GOLD);
                } else {
                    y = paragraph(g, "A casual game against whoever else is looking: your rating isn't changed. You're only "
                            + "matched with players also looking for a casual game.", x, y, inner, unit);
                }
                int modes = Expedition.matchModes(), maps = Expedition.matchMaps();
                y = choiceRow(g, "Game modes", modeLabels(), m -> (modes & (1 << m)) != 0, true,
                        m -> Expedition.toggleMatchMode(GameMode.values()[m]), x, y + 34 * unit, inner, unit);
                y = choiceRow(g, "Maps", mapLabels(), m -> (maps & (1 << m)) != 0, true,
                        m -> Expedition.toggleMatchMap(MapKind.values()[m]), x, y + 30 * unit, inner, unit);
                y = paragraph(g, "Tick every game mode and map you'll play. You're only matched with players who ticked one of "
                        + "each too, and play one of those you share, at random.", x, y + 40 * unit, inner, unit, HudStyle.LABEL);
                y += 26 * unit;
                y = button(g, ranked ? "Find ranked match" : "Find casual match", true, true, this::findMatch, x, y, inner, unit);
                y = backButton(g, "Back", () -> show(State.CHOOSE), x, y + 10 * unit, unit);
            }
            case SEARCHING -> {
                boolean found = session != null && (session.role != null || session.isInRoom());
                y = heading(g, (found ? "Opponent found" : "Finding an opponent") + dots(), x, y, unit);
                long seconds = (System.currentTimeMillis() - searchStartedAt) / 1000;
                y = paragraph(g, found ? "Linking up with your opponent."
                        : "Looking for another player in the " + (ranked ? "ranked" : "casual") + " queue.", x, y, inner, unit);
                y = paragraph(g, String.format(java.util.Locale.ROOT, "Searching for %d:%02d", seconds / 60, seconds % 60),
                        x, y + 4 * unit, inner, unit, HudStyle.ACCENT);
                if (ranked) y = paragraph(g, "Your rating: " + Rating.rating(), x, y, inner, unit, HudStyle.GOLD);
                y = backButton(g, "Cancel", this::cancel, x, y + 26 * unit, unit);
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
                y = backButton(g, "Back", () -> show(State.FRIEND), x, y + 10 * unit, unit);
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
                if (session != null && session.isMatchmade()) {
                    y = heading(g, "Matched with " + partner, x, y, unit);
                    if (session.isRanked()) {
                        int mine = session.localRating(), theirs = session.remoteRating();
                        y = paragraph(g, "Ranked: you " + mine + ", " + partner + " " + theirs + ". A win takes you to "
                                + Rating.after(mine, theirs, true) + ", a loss to " + Rating.after(mine, theirs, false) + ".",
                                x, y, inner, unit, HudStyle.GOLD);
                    }
                } else {
                    y = heading(g, host ? partner + " connected" : "Connected to " + partner, x, y, unit);
                }
                if (session != null && session.gameMode() != null && session.mapKind() != null) {
                    y = paragraph(g, session.gameMode().label + "  \u00b7  " + session.mapKind().label + " map", x, y + 4 * unit, inner, unit, Color.WHITE);
                }
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

    /**
     * A row of choices of game mode or map under its title (from x, the title's baseline at y),
     * each one's button (radio buttons, or tick boxes where ticks) chosen where chosen holds for
     * its index, choosing it (or ticking it) with choose; returns the bottom of the row.
     */
    private float choiceRow(Graphics2D g, String title, String[] labels, java.util.function.IntPredicate chosen, boolean ticks,
                            java.util.function.IntConsumer choose, float x, float y, float width, float unit) {
        HudStyle.label(g, title, x + 2 * unit, y, 12 * unit, HudStyle.ACCENT);
        float h = 46 * unit, top = y + 12 * unit, gap = 10 * unit, at = x;
        for (int m = 0; m < labels.length; m++) {
            float w = Expedition.choiceWidth(g, labels[m], h, unit);
            Rectangle r = new Rectangle(Math.round(at), Math.round(top), Math.round(w), Math.round(h));
            int index = m;
            boolean over = addButton(r, true, () -> {
                choose.accept(index);
                repaint();
            });
            Expedition.paintChoice(g, r, labels[m], chosen.test(m), ticks, over, unit);
            at += w + gap;
        }
        return top + h;
    }

    private static String[] modeLabels() {
        return java.util.Arrays.stream(GameMode.values()).map(m -> m.label).toArray(String[]::new);
    }

    private static String[] mapLabels() {
        return java.util.Arrays.stream(MapKind.values()).map(m -> m.label).toArray(String[]::new);
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

    /**
     * Matchmaking's queue: Casual and Ranked either side of a switch, the chosen one lit; a
     * click anywhere on it changes queue. Its top at y; returns its bottom.
     */
    private float queueSwitch(Graphics2D g, float x, float y, float width, float unit) {
        float h = 40 * unit, trackW = 84 * unit, gap = 18 * unit;
        g.setFont(HudStyle.font(Font.BOLD, 22 * unit));
        FontMetrics fm = g.getFontMetrics();
        float trackX = x + fm.stringWidth("Casual") + gap, labelY = y + (h + fm.getAscent() - fm.getDescent()) / 2f;
        float total = trackX - x + trackW + gap + fm.stringWidth("Ranked");
        Rectangle r = new Rectangle(Math.round(x), Math.round(y), Math.round(Math.min(width, total)), Math.round(h));
        boolean over = addButton(r, true, this::toggleRanked);

        g.setColor(ranked ? HudStyle.DIM : Color.WHITE);
        g.drawString("Casual", x, labelY);
        g.setColor(ranked ? HudStyle.GOLD : HudStyle.DIM);
        g.drawString("Ranked", trackX + trackW + gap, labelY);
        java.awt.geom.RoundRectangle2D track = new java.awt.geom.RoundRectangle2D.Float(trackX, y, trackW, h, h, h);
        g.setColor(ranked ? new Color(84, 62, 18, 235) : new Color(10, 14, 22, 230));
        g.fill(track);
        g.setColor(over ? HudStyle.ACCENT : HudStyle.EDGE);
        g.setStroke(new java.awt.BasicStroke(1.6f * Math.max(1f, unit)));
        g.draw(track);
        float knob = h - 8 * unit, knobX = ranked ? trackX + trackW - 4 * unit - knob : trackX + 4 * unit;
        g.setColor(ranked ? HudStyle.GOLD : HudStyle.ACCENT);
        g.fill(new java.awt.geom.Ellipse2D.Float(knobX, y + 4 * unit, knob, knob));
        return y + h;
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
