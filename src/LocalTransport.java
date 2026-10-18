import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import javax.swing.SwingUtilities;

/**
 * Multiplayer between two game windows on this PC, for development (chosen with
 * -Dxenoguesser.mpfake=host or =join): Steam won't run two accounts on one PC, so without this
 * every test would need two machines. The host listens on 127.0.0.1:27999 and the joiner
 * connects to it; the room code is made and shown like Steam's but not checked, since there
 * can only be one room on the PC.
 *
 * Both kinds of message go over the one TCP connection, each as its length then its bytes, so
 * "unreliable" ones always arrive here. -Dxenoguesser.mpdrop=0.3 throws away that share of the
 * unreliable ones before sending, to see how the game copes with lost poses.
 */
public final class LocalTransport implements MultiplayerTransport {

    private static final int PORT = 27999;
    private static final double DROP_SHARE = parseDropShare(System.getProperty("xenoguesser.mpdrop"));
    // Larger than any message the game sends (the writing systems, under 1 MB)
    private static final int MAX_MESSAGE = 4 * 1024 * 1024;

    private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private volatile Listener listener;
    private volatile boolean running;
    private Thread netThread;

    // The room: its server socket while hosting, then the connection to the partner. Each room
    // has a number, so a reader or accept thread left over from a room already left stays quiet.
    private final Object lock = new Object();
    private ServerSocket server;
    private Socket socket;
    private DataOutputStream out;
    private int room;

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        running = true;
        netThread = new Thread(this::netLoop, "local-net");
        netThread.setDaemon(true);
        netThread.start();
        System.out.println("[MP] Local transport (no Steam): 127.0.0.1:" + PORT
                + (DROP_SHARE > 0 ? ", dropping " + Math.round(DROP_SHARE * 100) + "% of poses" : ""));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void host() {
        tasks.offer(this::openRoom);
    }

    @Override
    public void join(String code) {
        tasks.offer(() -> joinRoom(code));
    }

    @Override
    public void send(ByteBuffer message, boolean reliable) {
        if (!reliable && DROP_SHARE > 0 && ThreadLocalRandom.current().nextDouble() < DROP_SHARE) return;
        byte[] bytes = new byte[message.remaining()];
        message.duplicate().get(bytes);
        synchronized (lock) {
            if (out == null) return;
            try {
                out.writeInt(bytes.length);
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                // The reader notices the broken connection and reports it
                System.out.println("[MP] Couldn't send: " + e.getMessage());
            }
        }
    }

    @Override
    public void leave() {
        synchronized (lock) {
            closeRoom();
        }
    }

    @Override
    public void shutdown() {
        leave();
        running = false;
        if (netThread != null) netThread.interrupt();
    }

    /** Runs what other threads ask for, and the listener's timer. */
    private void netLoop() {
        try {
            while (running) {
                Runnable task;
                while ((task = tasks.poll()) != null) task.run();
                Listener l = listener;
                if (l != null) l.tick(System.currentTimeMillis());
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void openRoom() {
        int myRoom;
        ServerSocket listening;
        synchronized (lock) {
            closeRoom();
            myRoom = room;
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
            } catch (IOException e) {
                closeRoom();
                fail(myRoom, "Couldn't open a local room on port " + PORT + " (is another window already hosting?)");
                return;
            }
            listening = server;
        }
        String code = Multiplayer.newRoomCode();
        System.out.println("[MP] Local room " + code + " open on port " + PORT);
        onSwing(() -> listener.roomCode(code));

        Thread accept = new Thread(() -> {
            try {
                Socket partner = listening.accept();
                if (!connect(myRoom, partner)) partner.close();
            } catch (IOException e) {
                // Closed by leave(), or the room was replaced
                if (isRoom(myRoom)) fail(myRoom, "The local room closed (" + e.getMessage() + ")");
            }
        }, "local-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private void joinRoom(String code) {
        int myRoom;
        synchronized (lock) {
            closeRoom();
            myRoom = room;
        }
        System.out.println("[MP] Joining local room " + code + " (the code isn't checked locally)");
        try {
            Socket partner = new Socket();
            partner.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 2000);
            if (!connect(myRoom, partner)) partner.close();
        } catch (IOException e) {
            fail(myRoom, "No open room found for code " + code
                    + " (no window is hosting with -Dxenoguesser.mpfake=host).");
        }
    }

    /** Starts using a connection to the partner, unless the room has been left meanwhile. */
    private boolean connect(int myRoom, Socket partner) throws IOException {
        DataInputStream in;
        synchronized (lock) {
            if (room != myRoom) return false;
            partner.setTcpNoDelay(true);
            socket = partner;
            out = new DataOutputStream(new BufferedOutputStream(partner.getOutputStream()));
            in = new DataInputStream(new BufferedInputStream(partner.getInputStream()));
            // Only one partner per room
            closeQuietly(server);
            server = null;
        }
        System.out.println("[MP] Local partner connected");
        onSwing(() -> {
            if (isRoom(myRoom)) listener.connected();
        });
        Thread reader = new Thread(() -> readLoop(myRoom, in), "local-net-read");
        reader.setDaemon(true);
        reader.start();
        return true;
    }

    /** Hands each message from the partner to the listener, until the connection ends. */
    private void readLoop(int myRoom, DataInputStream in) {
        try {
            while (true) {
                int length = in.readInt();
                if (length <= 0 || length > MAX_MESSAGE) {
                    throw new IOException("a message of " + length + " bytes");
                }
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                if (!isRoom(myRoom)) return;
                listener.message(ByteBuffer.wrap(bytes));
            }
        } catch (EOFException e) {
            disconnect(myRoom, "The other player disconnected.");
        } catch (IOException e) {
            disconnect(myRoom, "The connection to the other player was lost (" + e.getMessage() + ").");
        }
    }

    private boolean isRoom(int myRoom) {
        synchronized (lock) {
            return room == myRoom;
        }
    }

    private void fail(int myRoom, String reason) {
        if (!leaveIfRoom(myRoom)) return;
        System.out.println("[MP] " + reason);
        onSwing(() -> listener.failed(reason));
    }

    private void disconnect(int myRoom, String reason) {
        if (!leaveIfRoom(myRoom)) return;
        System.out.println("[MP] " + reason);
        onSwing(() -> listener.disconnected(reason));
    }

    /** Leaves the room if it's still this one; false if it was already left. */
    private boolean leaveIfRoom(int myRoom) {
        synchronized (lock) {
            if (room != myRoom) return false;
            closeRoom();
            return true;
        }
    }

    /** Closes the room's sockets and moves on to a new room number. Call holding the lock. */
    private void closeRoom() {
        room++;
        closeQuietly(server);
        closeQuietly(socket);
        server = null;
        socket = null;
        out = null;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing anyway
        }
    }

    private static void onSwing(Runnable event) {
        SwingUtilities.invokeLater(event);
    }

    private static double parseDropShare(String value) {
        if (value == null) return 0;
        try {
            return Math.max(0, Math.min(1, Double.parseDouble(value)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
