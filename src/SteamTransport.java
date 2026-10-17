import com.codedisaster.steamworks.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.swing.SwingUtilities;

/**
 * Multiplayer over Steam (steamworks4j 1.10.0): a Steam lobby is the room, found by its
 * 5-letter code, and the two players then send messages straight to each other with Steam's
 * peer-to-peer networking. This is the working Steam test app's logic (SteamP2PApp) without
 * its window. App ID 480 (Valve's shared test app, "Spacewar") from steam_appid.txt until
 * XenoGuesser has its own.
 *
 * Steam is only loaded and started by start(), which the game calls the first time Play
 * Multiplayer is pressed, so singleplayer never needs Steam. Every Steam call happens on the
 * one "steam-net" thread: other threads post tasks to it, and it runs Steam's callbacks and
 * reads incoming messages every 10 ms.
 *
 * Hosting: make a code, check no open room has it, create the lobby and publish the code,
 * then check again that only one room has it (two hosts could pick the same code at once); a
 * clash means a new code, up to 20 times. Joining: look the code up, join the one lobby found,
 * and its owner is the partner. Reliable messages go on channel 0, unreliable on channel 1.
 */
public final class SteamTransport implements MultiplayerTransport {

    private static final String ROOM_CODE_KEY = "room_code";
    // Only rooms of this game and protocol version are found, not other Spacewar (480) users'
    private static final String GAME_KEY = "game";
    private static final int MAX_CODE_COLLISION_RETRIES = 20;
    private static final int LOBBY_MEMBER_LIMIT = 2;
    private static final int RELIABLE_CHANNEL = 0;
    private static final int UNRELIABLE_CHANNEL = 1;
    // Steam's reliable peer-to-peer messages can be up to 1 MB
    private static final int MAX_MESSAGE = 1024 * 1024;
    private static final String NOT_RUNNING = "Steam isn't running. Start Steam and try again.";

    // The native libraries can only be loaded once per run
    private static boolean librariesLoaded;

    private enum Phase {
        IDLE,
        CHECKING_HOST_CODE,
        CREATING_LOBBY,
        VERIFYING_HOST_CODE,
        HOSTING,
        LOOKING_UP_ROOM,
        JOINING_ROOM,
        IN_ROOM
    }

    // Why the last lobby list was asked for, since its answer doesn't say
    private enum MatchAction {
        NONE,
        CHECK_HOST_CODE,
        VERIFY_HOST_CODE,
        LOOK_UP_ROOM
    }

    private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private volatile Listener listener;
    private volatile boolean running;
    private Thread netThread;

    // Only touched on the steam-net thread
    private SteamUser steamUser;
    private SteamNetworking networking;
    private SteamMatchmaking matchmaking;
    private SteamID mySteamID;
    private SteamID lobbyID;
    private SteamID remoteSteamID;
    private String roomCode;
    private Phase phase = Phase.IDLE;
    private MatchAction matchAction = MatchAction.NONE;
    private int collisionRetries;
    private ByteBuffer sendBuffer = ByteBuffer.allocateDirect(64 * 1024);
    private ByteBuffer readBuffer = ByteBuffer.allocateDirect(64 * 1024);

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        running = true;
        netThread = new Thread(this::netLoop, "steam-net");
        netThread.setDaemon(true);
        netThread.start();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void host() {
        tasks.offer(this::beginHosting);
    }

    @Override
    public void join(String code) {
        tasks.offer(() -> beginRoomLookup(code));
    }

    @Override
    public void send(ByteBuffer message, boolean reliable) {
        if (message.remaining() > MAX_MESSAGE) {
            System.out.println("[MP] Not sent: a message of " + message.remaining() + " bytes is over Steam's 1 MB");
            return;
        }
        if (Thread.currentThread() == netThread) {
            sendNow(message, reliable);
        } else {
            byte[] bytes = new byte[message.remaining()];
            message.duplicate().get(bytes);
            tasks.offer(() -> sendNow(ByteBuffer.wrap(bytes), reliable));
        }
    }

    @Override
    public void leave() {
        tasks.offer(this::resetRoom);
    }

    @Override
    public void shutdown() {
        if (!running) return;
        running = false;
        // Let the thread leave the lobby and shut Steam down itself, briefly, before the game exits
        try {
            if (netThread != null && Thread.currentThread() != netThread) netThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- The steam-net thread ----

    private void netLoop() {
        try {
            initSteam();
        } catch (IOException | SteamException | RuntimeException | UnsatisfiedLinkError e) {
            System.out.println("[MP] Steam couldn't start: " + e);
            running = false;
            cleanupSteam();
            onSwing(() -> listener.failed(NOT_RUNNING));
            return;
        }

        int[] packetSize = new int[1];
        try {
            while (running) {
                Runnable task;
                while ((task = tasks.poll()) != null) {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        System.out.println("[MP] Room operation failed: " + e);
                        e.printStackTrace();
                        endRoom("The room operation failed.");
                    }
                }

                SteamAPI.runCallbacks();
                receive(RELIABLE_CHANNEL, packetSize);
                receive(UNRELIABLE_CHANNEL, packetSize);
                listener.tick(System.currentTimeMillis());
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SteamException | RuntimeException e) {
            System.out.println("[MP] Steam networking failed: " + e);
            e.printStackTrace();
            endRoom("Steam networking failed.");
        } finally {
            running = false;
            cleanupSteam();
        }
    }

    private void initSteam() throws IOException, SteamException {
        ensureAppIdFile();
        loadSteamLibraries();
        if (!SteamAPI.init()) {
            throw new IOException("SteamAPI.init() failed (is the Steam client running?)");
        }

        steamUser = new SteamUser(new SteamUserCallback() {});
        mySteamID = steamUser.getSteamID();
        networking = new SteamNetworking(new SteamNetworkingCallback() {
            @Override
            public void onP2PSessionRequest(SteamID remoteID) {
                // Only the partner in this room may open a connection
                if (lobbyID == null || !isLobbyMember(remoteID)) return;
                if (remoteSteamID == null) remoteSteamID = new SteamID(remoteID);
                networking.acceptP2PSessionWithUser(remoteID);
            }

            @Override
            public void onP2PSessionConnectFail(SteamID remoteID, SteamNetworking.P2PSessionError error) {
                if (remoteSteamID != null && remoteSteamID.equals(remoteID)) {
                    endRoom("The connection to the other player failed (" + error + ").");
                }
            }
        });
        matchmaking = new SteamMatchmaking(new SteamMatchmakingCallback() {
            @Override
            public void onLobbyMatchList(int lobbyCount) {
                handleLobbyMatchList(lobbyCount);
            }

            @Override
            public void onLobbyCreated(SteamResult result, SteamID createdLobbyID) {
                handleLobbyCreated(result, createdLobbyID);
            }

            @Override
            public void onLobbyEnter(SteamID enteredLobbyID, int chatPermissions, boolean locked,
                                     SteamMatchmaking.ChatRoomEnterResponse response) {
                handleLobbyEnter(enteredLobbyID, response);
            }

            @Override
            public void onLobbyChatUpdate(SteamID changedLobbyID, SteamID changedUserID,
                                          SteamID makingChangeID,
                                          SteamMatchmaking.ChatMemberStateChange stateChange) {
                handleLobbyMemberChange(changedLobbyID, changedUserID, stateChange);
            }
        });
        System.out.println("[MP] Steam started (app " + readAppId() + ", me " + mySteamID + ")");
    }

    private void beginHosting() {
        if (phase != Phase.IDLE) resetRoom();
        collisionRetries = 0;
        roomCode = Multiplayer.newRoomCode();
        phase = Phase.CHECKING_HOST_CODE;
        requestRoomSearch(roomCode, MatchAction.CHECK_HOST_CODE);
    }

    private void beginRoomLookup(String code) {
        if (phase != Phase.IDLE) resetRoom();
        roomCode = code;
        phase = Phase.LOOKING_UP_ROOM;
        requestRoomSearch(roomCode, MatchAction.LOOK_UP_ROOM);
    }

    private void requestRoomSearch(String code, MatchAction action) {
        matchAction = action;
        matchmaking.addRequestLobbyListStringFilter(ROOM_CODE_KEY, code, SteamMatchmaking.LobbyComparison.Equal);
        matchmaking.addRequestLobbyListStringFilter(GAME_KEY, Multiplayer.BUILD_ID, SteamMatchmaking.LobbyComparison.Equal);
        // Steam's default only finds lobbies in nearby regions; friends may be far apart
        matchmaking.addRequestLobbyListDistanceFilter(SteamMatchmaking.LobbyDistanceFilter.Worldwide);
        matchmaking.addRequestLobbyListFilterSlotsAvailable(1);
        matchmaking.addRequestLobbyListResultCountFilter(10);
        matchmaking.requestLobbyList();
    }

    private void handleLobbyMatchList(int lobbyCount) {
        MatchAction action = matchAction;
        matchAction = MatchAction.NONE;

        if (action == MatchAction.CHECK_HOST_CODE) {
            if (phase != Phase.CHECKING_HOST_CODE) return;
            if (lobbyCount > 0) {
                retryHostCode();
                return;
            }
            phase = Phase.CREATING_LOBBY;
            matchmaking.createLobby(SteamMatchmaking.LobbyType.Public, LOBBY_MEMBER_LIMIT);
        } else if (action == MatchAction.VERIFY_HOST_CODE) {
            if (phase != Phase.VERIFYING_HOST_CODE) return;
            if (lobbyCount > 1) {
                retryHostCode();
                return;
            }
            phase = Phase.HOSTING;
            String code = roomCode;
            System.out.println("[MP] Hosting room " + code + " (lobby " + lobbyID + ")");
            onSwing(() -> listener.roomCode(code));
        } else if (action == MatchAction.LOOK_UP_ROOM) {
            if (phase != Phase.LOOKING_UP_ROOM) return;
            if (lobbyCount == 0) {
                endRoom("No open room found for code " + roomCode + ".");
            } else if (lobbyCount > 1) {
                endRoom("That code matches more than one open room. Ask the host for a new code.");
            } else {
                lobbyID = matchmaking.getLobbyByIndex(0);
                phase = Phase.JOINING_ROOM;
                matchmaking.joinLobby(lobbyID);
            }
        }
    }

    private void retryHostCode() {
        if (++collisionRetries > MAX_CODE_COLLISION_RETRIES) {
            endRoom("Couldn't find a unique room code. Please try hosting again.");
            return;
        }
        roomCode = Multiplayer.newRoomCode();
        if (lobbyID == null) {
            phase = Phase.CHECKING_HOST_CODE;
            requestRoomSearch(roomCode, MatchAction.CHECK_HOST_CODE);
            return;
        }
        if (!matchmaking.setLobbyData(lobbyID, ROOM_CODE_KEY, roomCode)) {
            endRoom("Steam couldn't update the room code. Please try hosting again.");
            return;
        }
        phase = Phase.VERIFYING_HOST_CODE;
        requestRoomSearch(roomCode, MatchAction.VERIFY_HOST_CODE);
    }

    private void handleLobbyCreated(SteamResult result, SteamID createdLobbyID) {
        if (phase != Phase.CREATING_LOBBY) return;
        if (result != SteamResult.OK) {
            endRoom("Steam couldn't create a room (" + result + ").");
            return;
        }

        lobbyID = new SteamID(createdLobbyID);
        if (!matchmaking.setLobbyData(lobbyID, ROOM_CODE_KEY, roomCode)
                || !matchmaking.setLobbyData(lobbyID, GAME_KEY, Multiplayer.BUILD_ID)
                || !matchmaking.setLobbyMemberLimit(lobbyID, LOBBY_MEMBER_LIMIT)
                || !matchmaking.setLobbyJoinable(lobbyID, true)) {
            endRoom("Steam couldn't publish the room code. Please try hosting again.");
            return;
        }
        phase = Phase.VERIFYING_HOST_CODE;
        requestRoomSearch(roomCode, MatchAction.VERIFY_HOST_CODE);
    }

    private void handleLobbyEnter(SteamID enteredLobbyID, SteamMatchmaking.ChatRoomEnterResponse response) {
        if (phase != Phase.JOINING_ROOM) return;
        if (response != SteamMatchmaking.ChatRoomEnterResponse.Success) {
            endRoom("Couldn't join the room (" + response + ").");
            return;
        }

        lobbyID = new SteamID(enteredLobbyID);
        SteamID ownerID = matchmaking.getLobbyOwner(lobbyID);
        if (ownerID == null || ownerID.equals(mySteamID)) {
            endRoom("The room's host is no longer there.");
            return;
        }
        partnerArrived(ownerID);
    }

    private void handleLobbyMemberChange(SteamID changedLobbyID, SteamID changedUserID,
                                         SteamMatchmaking.ChatMemberStateChange stateChange) {
        if (lobbyID == null || !lobbyID.equals(changedLobbyID)) return;

        if (stateChange == SteamMatchmaking.ChatMemberStateChange.Entered) {
            if (changedUserID.equals(mySteamID)) return;
            if (remoteSteamID != null && !remoteSteamID.equals(changedUserID)) return;
            partnerArrived(changedUserID);
            return;
        }

        if (remoteSteamID != null && remoteSteamID.equals(changedUserID)
                && (stateChange == SteamMatchmaking.ChatMemberStateChange.Left
                || stateChange == SteamMatchmaking.ChatMemberStateChange.Disconnected
                || stateChange == SteamMatchmaking.ChatMemberStateChange.Kicked
                || stateChange == SteamMatchmaking.ChatMemberStateChange.Banned)) {
            endRoom("The other player disconnected.");
        }
    }

    /**
     * The partner is in the room: from the lobby entering (joiner), the partner entering it
     * (host), or the partner's first message arriving before Steam has said they entered.
     */
    private void partnerArrived(SteamID partner) {
        if (phase == Phase.IN_ROOM) return;
        remoteSteamID = new SteamID(partner);
        if (phase == Phase.HOSTING) matchmaking.setLobbyJoinable(lobbyID, false);
        phase = Phase.IN_ROOM;
        System.out.println("[MP] Partner in room " + roomCode + ": " + remoteSteamID);
        onSwing(() -> listener.connected());
    }

    private boolean isLobbyMember(SteamID userID) {
        if (lobbyID == null) return false;
        int memberCount = matchmaking.getNumLobbyMembers(lobbyID);
        for (int i = 0; i < memberCount; i++) {
            if (userID.equals(matchmaking.getLobbyMemberByIndex(lobbyID, i))) return true;
        }
        return false;
    }

    private void sendNow(ByteBuffer message, boolean reliable) {
        if (phase != Phase.IN_ROOM || remoteSteamID == null) return;
        int length = message.remaining();
        if (sendBuffer.capacity() < length) sendBuffer = ByteBuffer.allocateDirect(length);
        sendBuffer.clear();
        sendBuffer.put(message.duplicate()).flip();
        try {
            networking.sendP2PPacket(remoteSteamID, sendBuffer,
                    reliable ? SteamNetworking.P2PSend.Reliable : SteamNetworking.P2PSend.Unreliable,
                    reliable ? RELIABLE_CHANNEL : UNRELIABLE_CHANNEL);
        } catch (SteamException e) {
            System.out.println("[MP] Couldn't send: " + e.getMessage());
        }
    }

    /** Hands every waiting message on this channel from the partner to the listener. */
    private void receive(int channel, int[] packetSize) throws SteamException {
        while (networking.isP2PPacketAvailable(channel, packetSize)) {
            if (readBuffer.capacity() < packetSize[0]) readBuffer = ByteBuffer.allocateDirect(packetSize[0]);
            readBuffer.clear();
            SteamID senderID = new SteamID();
            int bytesRead = networking.readP2PPacket(senderID, readBuffer, channel);
            if (bytesRead <= 0) continue;
            if (remoteSteamID == null && phase == Phase.HOSTING && isLobbyMember(senderID)
                    && !senderID.equals(mySteamID)) {
                partnerArrived(senderID);
            }
            if (phase != Phase.IN_ROOM || !senderID.equals(remoteSteamID)) {
                System.out.println("[MP] Ignored a message from " + senderID + " (not the partner)");
                continue;
            }
            readBuffer.position(0);
            readBuffer.limit(bytesRead);
            listener.message(readBuffer);
        }
    }

    /** Leaves the room and tells the listener why: failed() before the partner came, disconnected() after. */
    private void endRoom(String reason) {
        boolean wasInRoom = phase == Phase.IN_ROOM;
        resetRoom();
        System.out.println("[MP] " + reason);
        onSwing(() -> {
            if (wasInRoom) listener.disconnected(reason);
            else listener.failed(reason);
        });
    }

    /** Leaves the lobby and closes the connection to the partner, quietly. */
    private void resetRoom() {
        if (matchmaking != null && lobbyID != null) {
            matchmaking.setLobbyJoinable(lobbyID, false);
            matchmaking.leaveLobby(lobbyID);
        }
        if (networking != null && remoteSteamID != null) {
            networking.closeP2PSessionWithUser(remoteSteamID);
        }
        lobbyID = null;
        remoteSteamID = null;
        roomCode = null;
        phase = Phase.IDLE;
        matchAction = MatchAction.NONE;
    }

    private void cleanupSteam() {
        resetRoom();
        if (matchmaking != null) {
            matchmaking.dispose();
            matchmaking = null;
        }
        if (networking != null) {
            networking.dispose();
            networking = null;
        }
        if (steamUser != null) {
            steamUser.dispose();
            steamUser = null;
            SteamAPI.shutdown();
            System.out.println("[MP] Steam shut down");
        }
    }

    // ---- Steam's files ----

    /**
     * Steam reads the app ID from steam_appid.txt in the working directory. The packaged game
     * has one beside its exe, but if the game was started from somewhere else, copy the one
     * from the game's folder there (best effort: Steam then says whether it worked).
     */
    private static void ensureAppIdFile() {
        Path here = Path.of("steam_appid.txt").toAbsolutePath();
        if (Files.isRegularFile(here)) return;
        Path ours = Path.of(GamePaths.HOME, "steam_appid.txt").toAbsolutePath();
        if (!Files.isRegularFile(ours)) return;
        try {
            Files.copy(ours, here);
        } catch (IOException e) {
            System.out.println("[MP] steam_appid.txt isn't in the working directory and couldn't be copied there: " + e);
        }
    }

    private static int readAppId() {
        for (String dir : new String[] {"", GamePaths.HOME}) {
            try {
                return Integer.parseInt(Files.readString(Path.of(dir, "steam_appid.txt")).trim());
            } catch (IOException | NumberFormatException e) {
                // Try the next place
            }
        }
        return -1;
    }

    /**
     * Loads Valve's steam_api64.dll (from the game's folder, else the working directory) and
     * steamworks4j's own library, which is inside its jar and copied to a temporary file first.
     */
    private static synchronized void loadSteamLibraries() throws IOException {
        if (librariesLoaded) return;
        Path steamApiLibrary = Path.of(GamePaths.HOME, "steam_api64.dll").toAbsolutePath();
        if (!Files.isRegularFile(steamApiLibrary)) steamApiLibrary = Path.of("steam_api64.dll").toAbsolutePath();
        if (!Files.isRegularFile(steamApiLibrary)) {
            throw new IOException("steam_api64.dll was not found in " + GamePaths.HOME + " or the working directory");
        }

        Path steamworksLibrary;
        try (InputStream bundledLibrary = SteamAPI.class.getResourceAsStream("/steamworks4j64.dll")) {
            if (bundledLibrary == null) throw new IOException("steamworks4j64.dll is missing from steamworks4j's jar");
            steamworksLibrary = Files.createTempFile("steamworks4j64-", ".dll");
            Files.copy(bundledLibrary, steamworksLibrary, StandardCopyOption.REPLACE_EXISTING);
            steamworksLibrary.toFile().deleteOnExit();
        }

        Path steamApi = steamApiLibrary;
        Path steamworks = steamworksLibrary;
        // (an anonymous class: SteamLibraryLoader's method has a default, so it isn't a lambda type)
        boolean loaded = SteamAPI.loadLibraries(new SteamLibraryLoader() {
            @Override
            public boolean loadLibrary(String libraryName) {
                if ("steam_api".equals(libraryName)) {
                    System.load(steamApi.toString());
                } else if ("steamworks4j".equals(libraryName)) {
                    System.load(steamworks.toString());
                } else {
                    throw new IllegalArgumentException("Unexpected Steamworks library: " + libraryName);
                }
                return true;
            }
        });
        if (!loaded) throw new IOException("Steamworks' native libraries could not be loaded");
        librariesLoaded = true;
    }

    private static void onSwing(Runnable event) {
        SwingUtilities.invokeLater(event);
    }
}
