import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * A two-player game: who this player is (the host is Employee 119-A, blue; the joiner is
 * 119-B, red), the world they share, and every message between the two PCs, written and read
 * here (see PROTOCOL in MULTIPLAYER_INTEGRATION_PLAN.txt). There is one session at a time,
 * active() while multiplayer is on; the transport under it (Steam, or a local socket with
 * -Dxenoguesser.mpfake) is made the first time it's needed and kept until the game exits, so
 * Steam is only started when Play Multiplayer is pressed.
 *
 * The handshake: the joiner enters the room and says HELLO; the host answers with START (its
 * world seed, game mode and map kind, which the joiner adopts); the joiner answers START_ACK.
 * Each side's Listener hears ready() when its half is done. After that the game's screens send
 * their messages through the send methods and read what has come from the partner here.
 *
 * Threads: the Listener is told everything on the Swing thread. Messages are read on the
 * transport's networking thread, so everything they change is safe to read from any thread
 * (volatile fields, concurrent collections, AtomicReference). The GL thread publishes this
 * player's pose with publishLocalPose(); the networking thread sends the latest one five
 * times a second.
 */
public final class Multiplayer {

    public static final int PROTOCOL_VERSION = 1;
    // Rooms are only found by games with the same id (SteamTransport's lobby filter)
    public static final String BUILD_ID = "xenoguesser-" + PROTOCOL_VERSION;

    // Room codes: 5 letters with I and O left out, so they can't be mistaken for 1 and 0
    public static final String ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    public static final int ROOM_CODE_LENGTH = 5;

    // Poses go 5 times a second; a lost one is simply predicted over by the receiver
    public static final long POSE_INTERVAL_MS = 200;

    // Message types (the first byte of each message)
    static final byte HELLO = 1;
    static final byte START = 2;
    static final byte START_ACK = 3;
    static final byte WORLD_READY = 4;
    static final byte BEGIN = 5;
    static final byte POSE = 6;
    static final byte GUESS = 7;
    static final byte SHOP_DONE = 8;
    static final byte ROUND_BUILT = 9;
    static final byte LEAVE = 10;
    static final byte STAGE_DONE = 11;
    static final byte ALPHABETS = 12;

    // Stage ids for STAGE_DONE, the lock-step points that have no message of their own: a
    // quarter's verdict panel (plus the quarter, 1 to 4), and a final decider's (plus its number)
    public static final int STAGE_QUARTER_END = 100;
    public static final int STAGE_FINAL_DECIDER = 200;

    // POSE flags
    public static final byte POSE_SWIMMING = 1;
    public static final byte POSE_AIRBORNE = 2;

    private static final int MAX_ALPHABETS = 1000 * 1000;
    private static final String VERSION_MISMATCH = "Your partner has a different version of XenoGuesser.";
    private static final SecureRandom codeRandom = new SecureRandom();

    /** Which of the two players this PC is. */
    public enum Role {
        // Trim colours (boots, gloves, back pack, helmet collar): first-person, and lit in the world
        HOST("119-A", "blue", new float[] {0.18f, 0.36f, 0.85f}, new float[] {0.08f, 0.18f, 0.55f}),
        JOIN("119-B", "red", new float[] {0.82f, 0.16f, 0.14f}, new float[] {0.55f, 0.07f, 0.06f});

        public final String label;
        public final String colourName;
        private final float[] trimColour;
        private final float[] litTrimColour;

        Role(String label, String colourName, float[] trimColour, float[] litTrimColour) {
            this.label = label;
            this.colourName = colourName;
            this.trimColour = trimColour;
            this.litTrimColour = litTrimColour;
        }

        public Role other() {
            return this == HOST ? JOIN : HOST;
        }

        public float[] trimColour() {
            return trimColour.clone();
        }

        public float[] litTrimColour() {
            return litTrimColour.clone();
        }
    }

    /** What a screen hears about the session, always on the Swing thread. */
    public interface Listener {
        /** Hosting: the room is open with this code. */
        default void roomCode(String code) {
        }

        /** Both players are in the room and the handshake is done: the world can be made. */
        default void ready() {
        }

        /** Hosting or joining didn't work (no room, Steam not running, different versions...). */
        default void failed(String reason) {
        }

        /** The partner left or was lost. */
        default void disconnected(String reason) {
        }

        /** Something from the partner arrived (WORLD_READY, BEGIN, GUESS...): worth a repaint. */
        default void changed() {
        }
    }

    /** Where a player is and looks: the eye (planet-centred) and the camera's forward direction. */
    public record Pose(double x, double y, double z, float fx, float fy, float fz, byte flags) {
    }

    /** A pose from the partner: its number, when they sent it (their clock) and when it arrived (ours). */
    public record PoseSample(int seq, long sentAtMs, long arrivedAtMs, Pose pose) {
    }

    /** The partner's last two poses (previous may be null), for predicting where they are now. */
    public record RemotePoses(PoseSample previous, PoseSample latest) {
    }

    /** A player's guess for a round: the pin's direction, the score, and the quarter's productivity so far. */
    public record Guess(int round, double px, double py, double pz, int score, int quarterProductivity) {
    }

    private static MultiplayerTransport transport;
    private static volatile Multiplayer active;

    // ---- The session ----

    public final Role role;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile String roomCode;
    private volatile long worldSeed;
    private volatile GameMode gameMode;
    private volatile MapKind mapKind;
    private volatile boolean inRoom;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile String remoteJavaVersion;
    private volatile long lastHeardMs;

    // What the partner has said
    private volatile boolean remoteWorldReady;
    private volatile boolean remoteBegun;
    private volatile byte[] remoteAlphabets;
    private final Map<Integer, Guess> remoteGuesses = new ConcurrentHashMap<>();
    // This player's own guesses as sent, so both PCs work out the score from the same numbers
    private final Map<Integer, Guess> localGuesses = new ConcurrentHashMap<>();
    private final Set<Integer> remoteShopDone = ConcurrentHashMap.newKeySet();
    private final Set<Integer> remoteRoundBuilt = ConcurrentHashMap.newKeySet();
    private final Set<Integer> remoteStagesDone = ConcurrentHashMap.newKeySet();
    private final AtomicReference<RemotePoses> remotePoses = new AtomicReference<>(new RemotePoses(null, null));

    // This player's pose, published by the GL thread and sent by the networking thread
    private final AtomicReference<Pose> localPose = new AtomicReference<>();
    private int poseSeq;
    private long lastPoseSentMs;
    private int posesReceived;

    private Multiplayer(Role role) {
        this.role = role;
    }

    /**
     * Hosts a new game of this world, mode and map: opens a room (the Listener hears its code),
     * then waits for the partner. Starts the transport (Steam) first if this is its first use.
     */
    public static Multiplayer host(long worldSeed, GameMode gameMode, MapKind mapKind, Listener listener) {
        Multiplayer session = open(Role.HOST, listener);
        session.worldSeed = worldSeed;
        session.gameMode = gameMode;
        session.mapKind = mapKind;
        transport.host();
        return session;
    }

    /** Joins the room with this code (cleaned with cleanRoomCode first); the world comes from the host. */
    public static Multiplayer join(String code, Listener listener) {
        Multiplayer session = open(Role.JOIN, listener);
        session.roomCode = cleanRoomCode(code);
        transport.join(session.roomCode);
        return session;
    }

    private static synchronized Multiplayer open(Role role, Listener listener) {
        if (active != null) active.leave();
        Multiplayer session = new Multiplayer(role);
        if (listener != null) session.listeners.add(listener);
        active = session;
        startTransport();
        System.out.println("[MP] " + (role == Role.HOST ? "Hosting" : "Joining") + " as Employee " + role.label);
        return session;
    }

    /**
     * Starts the transport (Steam) if it isn't running yet, in the background: called as the
     * multiplayer panel opens (M2), so Steam is usually up by the time Host or Join is pressed.
     * If it can't start nothing is said here (no session is listening); hosting or joining
     * then tries again and its Listener hears why it failed.
     */
    public static synchronized void startTransport() {
        if (transport != null && transport.isRunning()) return;
        transport = newTransport();
        transport.start(new TransportEvents());
    }

    /** -Dxenoguesser.mpfake (=host or =join) plays over a socket on this PC; otherwise Steam. */
    private static MultiplayerTransport newTransport() {
        return System.getProperty("xenoguesser.mpfake") != null ? new LocalTransport() : new SteamTransport();
    }

    /** The session being played, or null in singleplayer. */
    public static Multiplayer active() {
        return active;
    }

    public static boolean isActive() {
        return active != null;
    }

    /** Leaves any session and stops the transport (Steam), as the game exits. */
    public static synchronized void shutdown() {
        if (active != null) active.leave();
        if (transport != null) {
            transport.shutdown();
            transport = null;
        }
    }

    /** Leaves the game on purpose: tells the partner (LEAVE), then leaves the room. */
    public void leave() {
        if (closed) return;
        if (ready) send(message(LEAVE, 0), true);
        close();
    }

    private void close() {
        synchronized (Multiplayer.class) {
            closed = true;
            if (active == this) active = null;
            if (transport != null) transport.leave();
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ---- Room codes ----

    public static String newRoomCode() {
        StringBuilder code = new StringBuilder(ROOM_CODE_LENGTH);
        for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
            code.append(ROOM_CODE_ALPHABET.charAt(codeRandom.nextInt(ROOM_CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** A typed code upper-cased, with anything not in the code alphabet dropped, cut to 5 letters. */
    public static String cleanRoomCode(String typed) {
        StringBuilder code = new StringBuilder(ROOM_CODE_LENGTH);
        for (char c : typed.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (ROOM_CODE_ALPHABET.indexOf(c) >= 0 && code.length() < ROOM_CODE_LENGTH) code.append(c);
        }
        return code.toString();
    }

    // ---- What the game reads ----

    public String localLabel() {
        return role.label;
    }

    public String remoteLabel() {
        return role.other().label;
    }

    public String roomCode() {
        return roomCode;
    }

    /** The world's seed: the host's own, or the one in the host's START. */
    public long worldSeed() {
        return worldSeed;
    }

    public GameMode gameMode() {
        return gameMode;
    }

    public MapKind mapKind() {
        return mapKind;
    }

    /** Whether both players are in the room (the handshake may still be going on). */
    public boolean isInRoom() {
        return inRoom;
    }

    /** Whether the handshake is done (both in the room, the world settled). */
    public boolean isReady() {
        return ready;
    }

    /** Whether the session is over (left, failed or the partner gone): nothing more is sent or heard. */
    public boolean isClosed() {
        return closed;
    }

    /** The partner's Java version (from HELLO or START), or null before the handshake. */
    public String remoteJavaVersion() {
        return remoteJavaVersion;
    }

    /** When anything last arrived from the partner (this PC's clock), 0 before anything has. */
    public long lastHeardMs() {
        return lastHeardMs;
    }

    public boolean remoteWorldReady() {
        return remoteWorldReady;
    }

    public boolean remoteBegun() {
        return remoteBegun;
    }

    public Guess remoteGuess(int round) {
        return remoteGuesses.get(round);
    }

    /** This player's guess for a round as sent, or null. */
    public Guess localGuess(int round) {
        return localGuesses.get(round);
    }

    /** A player's guess for a round (either: by role), or null until it's made or has arrived. */
    public Guess guessBy(Role player, int round) {
        return player == role ? localGuesses.get(round) : remoteGuesses.get(round);
    }

    /** How many guesses there are, both players' (goes up as each comes in: for knowing when to repaint). */
    public int guessCount() {
        return localGuesses.size() + remoteGuesses.size();
    }

    public boolean remoteShopDone(int round) {
        return remoteShopDone.contains(round);
    }

    public boolean remoteRoundBuilt(int round) {
        return remoteRoundBuilt.contains(round);
    }

    public boolean remoteStageDone(int stageId) {
        return remoteStagesDone.contains(stageId);
    }

    /** The host's writing systems (a zip of its ALPHABETS_DIR), or null until they arrive. */
    public byte[] remoteAlphabets() {
        return remoteAlphabets;
    }

    public RemotePoses remotePoses() {
        return remotePoses.get();
    }

    /** This player's pose as of this frame (GL thread); the latest one is sent every 200 ms. */
    public void publishLocalPose(Pose pose) {
        localPose.set(pose);
    }

    // ---- Sending ----

    public void sendWorldReady() {
        send(message(WORLD_READY, 0), true);
    }

    public void sendBegin() {
        send(message(BEGIN, 0), true);
    }

    public void sendGuess(Guess guess) {
        localGuesses.put(guess.round(), guess);
        ByteBuffer m = message(GUESS, 4 + 3 * 8 + 4 + 4);
        m.putInt(guess.round()).putDouble(guess.px()).putDouble(guess.py()).putDouble(guess.pz())
                .putInt(guess.score()).putInt(guess.quarterProductivity());
        send(m, true);
    }

    public void sendShopDone(int round) {
        send(message(SHOP_DONE, 4).putInt(round), true);
    }

    public void sendRoundBuilt(int round) {
        send(message(ROUND_BUILT, 4).putInt(round), true);
    }

    public void sendStageDone(int stageId) {
        send(message(STAGE_DONE, 4).putInt(stageId), true);
    }

    /** Host only: the writing systems, a zip of ALPHABETS_DIR in one message (under 1 MB). */
    public void sendAlphabets(byte[] zip) {
        if (role != Role.HOST) throw new IllegalStateException("Only the host sends the writing systems");
        if (zip.length > MAX_ALPHABETS) throw new IllegalArgumentException("The writing systems are " + zip.length + " bytes, over 1 MB");
        send(message(ALPHABETS, 4 + zip.length).putInt(zip.length).put(zip), true);
    }

    /** A new message of this type with room for this many bytes after the type byte. */
    private static ByteBuffer message(byte type, int fieldBytes) {
        return ByteBuffer.allocate(1 + fieldBytes).put(type);
    }

    private void send(ByteBuffer m, boolean reliable) {
        MultiplayerTransport t = transport;
        if (closed || t == null) return;
        m.flip();
        if (m.get(0) != POSE) System.out.println("[MP] -> " + describe(m));
        t.send(m, reliable);
    }

    private static void putString(ByteBuffer m, byte[] utf8) {
        m.putShort((short) utf8.length).put(utf8);
    }

    private static String getString(ByteBuffer m) {
        int length = m.getShort() & 0xFFFF;
        byte[] bytes = new byte[length];
        m.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] javaVersion() {
        return System.getProperty("java.version", "?").getBytes(StandardCharsets.UTF_8);
    }

    private void sendHello() {
        byte[] java = javaVersion();
        ByteBuffer m = message(HELLO, 4 + 2 + java.length).putInt(PROTOCOL_VERSION);
        putString(m, java);
        send(m, true);
    }

    private void sendStart() {
        byte[] java = javaVersion();
        ByteBuffer m = message(START, 4 + 8 + 1 + 1 + 2 + java.length).putInt(PROTOCOL_VERSION)
                .putLong(worldSeed).put((byte) gameMode.ordinal()).put((byte) mapKind.ordinal());
        putString(m, java);
        send(m, true);
    }

    /** Networking thread, every few ms: sends this player's latest pose every 200 ms once the game is on. */
    private void tick(long now) {
        if (!ready || closed || now - lastPoseSentMs < POSE_INTERVAL_MS) return;
        Pose pose = localPose.get();
        if (pose == null) return;
        lastPoseSentMs = now;
        ByteBuffer m = message(POSE, 4 + 8 + 3 * 8 + 3 * 4 + 1);
        m.putInt(++poseSeq).putLong(now)
                .putDouble(pose.x()).putDouble(pose.y()).putDouble(pose.z())
                .putFloat(pose.fx()).putFloat(pose.fy()).putFloat(pose.fz()).put(pose.flags());
        send(m, false);
    }

    // ---- Receiving ----

    /** Reads one message from the partner (networking thread). Anything malformed is ignored with a log line. */
    private void receive(ByteBuffer m) {
        int length = m.remaining();
        if (length == 0) return;
        lastHeardMs = System.currentTimeMillis();
        byte type = m.get(m.position());
        if (type != POSE) System.out.println("[MP] <- " + describe(m.duplicate()));
        try {
            m.get();
            boolean changed = read(type, m);
            if (m.hasRemaining()) {
                System.out.println("[MP] Ignored a type " + type + " message of the wrong length (" + length + " bytes)");
                return;
            }
            if (changed) notifyListeners(Listener::changed);
        } catch (BufferUnderflowException e) {
            System.out.println("[MP] Ignored a type " + type + " message that was too short (" + length + " bytes)");
        }
    }

    /**
     * Reads a message's fields and acts on them; true if something the screens show changed.
     * The length is checked here as far as reading goes, and by the caller for anything left over;
     * a message that fails either check changes nothing, since every field is read before acting.
     */
    private boolean read(byte type, ByteBuffer m) {
        switch (type) {
            case HELLO -> {
                int version = m.getInt();
                String java = getString(m);
                if (m.hasRemaining() || !expect(Role.HOST, "HELLO")) return false;
                remoteJavaVersion = java;
                // The joiner checks the version too, so START goes even when it differs
                sendStart();
                if (!versionsMatch(version, java)) return false;
                ready = true;
                notifyListeners(Listener::ready);
                return false;
            }
            case START -> {
                int version = m.getInt();
                long seed = m.getLong();
                int mode = m.get();
                int map = m.get();
                String java = getString(m);
                if (m.hasRemaining() || !expect(Role.JOIN, "START")) return false;
                if (mode < 0 || mode >= GameMode.values().length || map < 0 || map >= MapKind.values().length) {
                    System.out.println("[MP] Ignored a START with an unknown game mode or map kind");
                    return false;
                }
                remoteJavaVersion = java;
                if (!versionsMatch(version, java)) return false;
                worldSeed = seed;
                gameMode = GameMode.values()[mode];
                mapKind = MapKind.values()[map];
                System.out.println("[MP] World from Employee " + remoteLabel() + ": seed " + seed
                        + ", " + gameMode + ", " + mapKind);
                send(message(START_ACK, 0), true);
                ready = true;
                notifyListeners(Listener::ready);
                return false;
            }
            case START_ACK -> {
                // The host is already ready (from HELLO); this only confirms the joiner is
                expect(Role.HOST, "START_ACK");
                return false;
            }
            case WORLD_READY -> {
                remoteWorldReady = true;
                return true;
            }
            case BEGIN -> {
                remoteBegun = true;
                return true;
            }
            case POSE -> {
                PoseSample sample = new PoseSample(m.getInt(), m.getLong(), System.currentTimeMillis(),
                        new Pose(m.getDouble(), m.getDouble(), m.getDouble(),
                                m.getFloat(), m.getFloat(), m.getFloat(), m.get()));
                if (m.hasRemaining()) return false;
                storePose(sample);
                return false;
            }
            case GUESS -> {
                Guess guess = new Guess(m.getInt(), m.getDouble(), m.getDouble(), m.getDouble(), m.getInt(), m.getInt());
                if (m.hasRemaining()) return false;
                remoteGuesses.put(guess.round(), guess);
                return true;
            }
            case SHOP_DONE -> {
                return addNumber(m, remoteShopDone);
            }
            case ROUND_BUILT -> {
                return addNumber(m, remoteRoundBuilt);
            }
            case STAGE_DONE -> {
                return addNumber(m, remoteStagesDone);
            }
            case LEAVE -> {
                String reason = "Employee " + remoteLabel() + " left.";
                close();
                notifyListeners(l -> l.disconnected(reason));
                return false;
            }
            case ALPHABETS -> {
                int size = m.getInt();
                if (size < 0 || size != m.remaining() || !expect(Role.JOIN, "ALPHABETS")) {
                    m.position(m.limit());
                    System.out.println("[MP] Ignored an ALPHABETS message whose size doesn't match");
                    return false;
                }
                byte[] zip = new byte[size];
                m.get(zip);
                remoteAlphabets = zip;
                return true;
            }
            default -> {
                m.position(m.limit());
                System.out.println("[MP] Ignored a message of unknown type " + type);
                return false;
            }
        }
    }

    private boolean addNumber(ByteBuffer m, Set<Integer> into) {
        int value = m.getInt();
        if (m.hasRemaining()) return false;
        return into.add(value);
    }

    /** Keeps the partner's last two poses, newest by number; older or repeated ones are dropped. */
    private void storePose(PoseSample sample) {
        RemotePoses now = remotePoses.get();
        if (now.latest() != null && sample.seq() <= now.latest().seq()) return;
        remotePoses.set(new RemotePoses(now.latest(), sample));
        if (++posesReceived % 25 == 1) {
            Pose p = sample.pose();
            System.out.printf(java.util.Locale.ROOT, "[MP] <- POSE #%d (%.1f, %.1f, %.1f) flags %d (%d poses so far)%n",
                    sample.seq(), p.x(), p.y(), p.z(), p.flags(), posesReceived);
        }
    }

    /** Whether this PC is the one the message is for; logs and ignores it if not. */
    private boolean expect(Role to, String what) {
        if (role == to) return true;
        System.out.println("[MP] Ignored a " + what + " sent to the wrong player");
        return false;
    }

    /** Fails the session (both PCs do) if the protocol differs; only warns if Java does. */
    private boolean versionsMatch(int version, String java) {
        if (version != PROTOCOL_VERSION) {
            System.out.println("[MP] Partner's protocol version " + version + ", ours " + PROTOCOL_VERSION);
            close();
            notifyListeners(l -> l.failed(VERSION_MISMATCH));
            return false;
        }
        if (!java.equals(System.getProperty("java.version"))) {
            // The world could come out slightly different (DETERMINISM AUDIT); Phase 2's panel shows this
            System.out.println("[MP] Warning: partner runs Java " + java + ", this PC " + System.getProperty("java.version"));
        }
        return true;
    }

    private void notifyListeners(java.util.function.Consumer<Listener> event) {
        SwingUtilities.invokeLater(() -> {
            for (Listener l : listeners) event.accept(l);
        });
    }

    /** A message for the log: its name and fields (it's read from a copy, so the original is untouched). */
    private static String describe(ByteBuffer original) {
        ByteBuffer m = original.duplicate();
        try {
            byte type = m.get();
            return switch (type) {
                case HELLO -> "HELLO version " + m.getInt() + ", Java " + getString(m);
                case START -> "START version " + m.getInt() + ", seed " + m.getLong() + ", mode " + m.get()
                        + ", map " + m.get() + ", Java " + getString(m);
                case START_ACK -> "START_ACK";
                case WORLD_READY -> "WORLD_READY";
                case BEGIN -> "BEGIN";
                case POSE -> "POSE #" + m.getInt();
                case GUESS -> String.format(java.util.Locale.ROOT, "GUESS round %d, pin (%.3f, %.3f, %.3f), score %d, quarter %d",
                        m.getInt(), m.getDouble(), m.getDouble(), m.getDouble(), m.getInt(), m.getInt());
                case SHOP_DONE -> "SHOP_DONE round " + m.getInt();
                case ROUND_BUILT -> "ROUND_BUILT round " + m.getInt();
                case LEAVE -> "LEAVE";
                case STAGE_DONE -> "STAGE_DONE " + m.getInt();
                case ALPHABETS -> "ALPHABETS " + m.getInt() + " bytes";
                default -> "unknown type " + type + " (" + original.remaining() + " bytes)";
            };
        } catch (BufferUnderflowException e) {
            return "type " + original.get(original.position()) + ", too short (" + original.remaining() + " bytes)";
        }
    }

    /** Routes the transport's events to the active session. */
    private static final class TransportEvents implements MultiplayerTransport.Listener {
        @Override
        public void roomCode(String code) {
            Multiplayer session = active;
            if (session == null) return;
            session.roomCode = code;
            session.notifyListeners(l -> l.roomCode(code));
        }

        @Override
        public void connected() {
            Multiplayer session = active;
            if (session == null) return;
            System.out.println("[MP] In a room with Employee " + session.remoteLabel());
            session.lastHeardMs = System.currentTimeMillis();
            session.inRoom = true;
            session.notifyListeners(Listener::changed);
            // The joiner speaks first; on Steam this also opens the peer-to-peer connection
            if (session.role == Role.JOIN) session.sendHello();
        }

        @Override
        public void message(java.nio.ByteBuffer message) {
            Multiplayer session = active;
            if (session != null && !session.closed) session.receive(message);
        }

        @Override
        public void failed(String reason) {
            Multiplayer session = active;
            if (session == null) return;
            session.close();
            session.notifyListeners(l -> l.failed(reason));
        }

        @Override
        public void disconnected(String reason) {
            Multiplayer session = active;
            if (session == null) return;
            session.close();
            if (session.ready) session.notifyListeners(l -> l.disconnected(reason));
            else session.notifyListeners(l -> l.failed(reason));
        }

        @Override
        public void tick(long nowMs) {
            Multiplayer session = active;
            if (session != null) session.tick(nowMs);
        }
    }
}
