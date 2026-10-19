import java.util.Locale;

/**
 * Self-check for multiplayer Phase 1 (the networking core, before any screen uses it): one
 * process hosts, another joins by the room code, and each sends the other every kind of
 * message and walks a pretend pose round a circle, logging what it sends and receives
 * ("[MP] ->" and "[MP] <-"), then leaves after a few seconds and prints what it heard.
 *
 * On one PC, without Steam (two terminals, host first; the local code isn't checked):
 *   java -Dxenoguesser.mpfake=host -cp "target/classes;lib/steamworks4j-1.10.0.jar" MultiplayerCheck host
 *   java -Dxenoguesser.mpfake=join -cp "target/classes;lib/steamworks4j-1.10.0.jar" MultiplayerCheck join ABCDE
 * Over Steam (two PCs with different accounts, Steam running, run from the project folder):
 *   java -cp "target/classes;lib/steamworks4j-1.10.0.jar" MultiplayerCheck host
 *   java -cp "target/classes;lib/steamworks4j-1.10.0.jar" MultiplayerCheck join KXQPB
 * "host" alone on one PC with Steam checks that Steam starts and a room opens with a code.
 * Exit code 1 if the room couldn't be opened or joined, or the partner's messages didn't all arrive.
 */
public final class MultiplayerCheck {

    private static final long RUN_MS = Dev.longValue("xenoguesser.mpcheck.ms", 8000);

    private static volatile Multiplayer session;
    private static volatile boolean failed;
    private static volatile boolean partnerLeft;

    public static void main(String[] args) throws Exception {
        boolean hosting = args.length > 0 && args[0].equalsIgnoreCase("host");
        if (!hosting && (args.length < 2 || !args[0].equalsIgnoreCase("join"))) {
            System.out.println("Usage: MultiplayerCheck host | join CODE");
            System.exit(2);
        }

        Multiplayer.Listener listener = new Multiplayer.Listener() {
            @Override
            public void roomCode(String code) {
                System.out.println("[CHECK] Room code: " + code + "  (" + code.length() + " letters)");
            }

            @Override
            public void ready() {
                System.out.println("[CHECK] Ready: Employee " + session.localLabel() + " with Employee "
                        + session.remoteLabel() + ", world seed " + session.worldSeed()
                        + ", " + session.gameMode() + ", " + session.mapKind());
                sendEverything(session);
            }

            @Override
            public void failed(String reason) {
                System.out.println("[CHECK] Failed: " + reason);
                failed = true;
            }

            @Override
            public void disconnected(String reason) {
                System.out.println("[CHECK] Disconnected: " + reason);
                partnerLeft = true;
            }
        };

        session = hosting
                ? Multiplayer.host(System.currentTimeMillis(), GameMode.current(), MapKind.current(), listener)
                : Multiplayer.join(args[1], listener);

        // Wait for the partner (hosting waits up to two minutes), then play for a few seconds
        long deadline = System.currentTimeMillis() + (hosting ? 120_000 : 30_000);
        while (!session.isReady() && !failed && System.currentTimeMillis() < deadline) Thread.sleep(50);
        if (!session.isReady()) {
            System.out.println("[CHECK] " + (failed ? "Gave up." : "Nobody joined in time."));
            Multiplayer.shutdown();
            System.exit(failed ? 1 : 0);
        }

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < RUN_MS && !partnerLeft) {
            // Walk round a circle of 20 units, 1 unit above a planet of radius 1000
            double t = (System.currentTimeMillis() - start) / 1000.0;
            session.publishLocalPose(new Multiplayer.Pose(20 * Math.cos(t), 1001, 20 * Math.sin(t),
                    (float) -Math.sin(t), 0f, (float) Math.cos(t), (byte) 0));
            Thread.sleep(16);
        }

        boolean allHeard = report(session);
        if (!partnerLeft) {
            // Give the partner's last messages time to arrive before saying goodbye
            Thread.sleep(1500);
            session.leave();
        }
        Thread.sleep(500);
        Multiplayer.shutdown();
        System.out.println("[CHECK] " + (allHeard ? "PASS" : "FAIL"));
        System.exit(allHeard ? 0 : 1);
    }

    private static void sendEverything(Multiplayer s) {
        s.sendWorldReady();
        s.sendBegin();
        boolean host = s.role == Multiplayer.Role.HOST;
        s.sendGuess(new Multiplayer.Guess(1, 0.6, host ? 0.8 : -0.8, 0, host ? 4321 : 1234, host ? 4321 : 1234));
        s.sendShopDone(1);
        s.sendRoundBuilt(2);
        s.sendStageDone(Multiplayer.STAGE_QUARTER_END + 1);
        if (host) s.sendAlphabets(new byte[200_000]);
    }

    /** Prints what came from the partner; true if every kind of message did. */
    private static boolean report(Multiplayer s) {
        Multiplayer.Guess guess = s.remoteGuess(1);
        Multiplayer.RemotePoses poses = s.remotePoses();
        byte[] alphabets = s.remoteAlphabets();
        boolean wantAlphabets = s.role == Multiplayer.Role.JOIN;
        System.out.println("[CHECK] From Employee " + s.remoteLabel() + ":");
        System.out.println("[CHECK]   WORLD_READY " + s.remoteWorldReady() + ", BEGIN " + s.remoteBegun());
        System.out.println("[CHECK]   GUESS round 1: " + guess);
        System.out.println("[CHECK]   SHOP_DONE 1 " + s.remoteShopDone(1) + ", ROUND_BUILT 2 " + s.remoteRoundBuilt(2)
                + ", STAGE_DONE " + (Multiplayer.STAGE_QUARTER_END + 1) + " " + s.remoteStageDone(Multiplayer.STAGE_QUARTER_END + 1));
        if (wantAlphabets) System.out.println("[CHECK]   ALPHABETS " + (alphabets == null ? "none" : alphabets.length + " bytes"));
        if (poses.latest() != null) {
            Multiplayer.PoseSample a = poses.previous(), b = poses.latest();
            System.out.println(String.format(Locale.ROOT, "[CHECK]   POSE #%d, %d ms after #%d (sender's clock)",
                    b.seq(), a == null ? 0 : b.sentAtMs() - a.sentAtMs(), a == null ? 0 : a.seq()));
        } else {
            System.out.println("[CHECK]   no POSE");
        }
        return s.remoteWorldReady() && s.remoteBegun() && guess != null && s.remoteShopDone(1)
                && s.remoteRoundBuilt(2) && s.remoteStageDone(Multiplayer.STAGE_QUARTER_END + 1)
                && poses.latest() != null && poses.latest().seq() >= 10
                && (!wantAlphabets || (alphabets != null && alphabets.length == 200_000));
    }
}
