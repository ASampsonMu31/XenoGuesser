import java.nio.ByteBuffer;

/**
 * How the two players' games reach each other: a room with a code that one player hosts and
 * the other joins, and messages sent between them once both are in it. Two kinds: Steam's
 * lobbies and peer-to-peer messages (SteamTransport, the real thing), and a socket on this PC
 * (LocalTransport) so two game windows on one PC can play together while developing, since
 * Steam won't run two accounts on one PC.
 *
 * Each transport has a networking thread of its own. Calls may come from any thread; they are
 * handed to that thread. Events go to the Listener on the Swing thread, apart from message()
 * and tick(), which come on the networking thread so a pose can be stored or sent without
 * waiting for the Swing thread.
 */
public interface MultiplayerTransport {

    /** What happens to a room and its messages. */
    interface Listener {
        /** The room is open with this code (host only), waiting for the partner. Swing thread. */
        void roomCode(String code);

        /** The two players are in the same room and can send messages. Swing thread. */
        void connected();

        /** A message from the partner; read it before returning (the buffer is reused). Net thread. */
        void message(ByteBuffer message);

        /** Hosting or joining didn't work (or the transport couldn't start); the room is left. Swing thread. */
        void failed(String reason);

        /** The partner left or the connection was lost; the room is left. Swing thread. */
        void disconnected(String reason);

        /** Called every few milliseconds on the networking thread, for anything sent on a timer. */
        default void tick(long nowMs) {
        }
    }

    /**
     * Starts the networking thread (Steam: loads and starts Steam). Returns at once; if it
     * can't start, failed() says why and the transport can be thrown away.
     */
    void start(Listener listener);

    /** Whether start() succeeded and the transport is still usable. */
    boolean isRunning();

    /** Opens a room with a new code, reported by roomCode(), then connected() when the partner joins. */
    void host();

    /** Joins the room with this code (already upper-cased and checked); connected() once in. */
    void join(String code);

    /**
     * Sends a message to the partner (its bytes from position to limit, copied before this
     * returns). Reliable messages always arrive, in order; unreliable ones may be lost.
     * Ignored when not connected.
     */
    void send(ByteBuffer message, boolean reliable);

    /** Leaves the room (if in one), quietly: no events follow. The transport can host or join again. */
    void leave();

    /** Leaves any room and stops the networking thread for good (Steam: shuts Steam down). */
    void shutdown();
}
