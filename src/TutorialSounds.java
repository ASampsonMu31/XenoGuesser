import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * The training programme's little sounds: a bright, rising ping as an objective's met, and a soft
 * boop for each plant or animal named through the identification kit's glass, a step up the
 * scale with each one, so the player can hear the count climbing towards the five asked for.
 * Each is made as it's asked for (they're a fraction of a second long) and played on a thread of
 * its own, as PartnerGuessSound's chime is, so it never holds up the game.
 */
final class TutorialSounds {
    private static final int RATE = 22050;
    // The ping's two notes (Hz: a C and the G above), the second's start and how long each rings (seconds)
    private static final float PING_FIRST = 1046.5f, PING_SECOND = 1568f, PING_GAP = 0.07f, PING_RING = 0.45f;
    // The boops' lowest note (Hz), how long each lasts (seconds), and the steps up for each one
    // after the first (semitones: a pentatonic run, so any two heard together still sound right)
    private static final float BOOP_BASE = 392f, BOOP_LENGTH = 0.17f;
    private static final int[] BOOP_STEPS = { 0, 2, 4, 7, 9, 12, 14, 16 };
    // How loud each is, and how long after the last boop an objective's ping follows it (seconds)
    private static final float PING_LOUDNESS = 0.26f, BOOP_LOUDNESS = 0.34f, PING_AFTER_BOOP = 0.16f;

    private TutorialSounds() {
    }

    /** The ping for an objective met. */
    static void ping() {
        float[] mix = new float[(int) ((PING_GAP + PING_RING) * RATE)];
        addPing(mix, 0);
        play(mix, "tutorial-ping");
    }

    /**
     * The boop for the {@code count}th plant or animal identified (1 the first, each higher than
     * the last), followed by the objective's ping if that one met it.
     */
    static void boop(int count, boolean thenPing) {
        int pingAt = (int) (PING_AFTER_BOOP * RATE);
        float[] mix = new float[thenPing ? pingAt + (int) ((PING_GAP + PING_RING) * RATE) : (int) (BOOP_LENGTH * RATE)];
        int step = BOOP_STEPS[Math.max(0, Math.min(BOOP_STEPS.length - 1, count - 1))];
        addBoop(mix, BOOP_BASE * (float) Math.pow(2.0, step / 12.0));
        if (thenPing) addPing(mix, pingAt);
        play(mix, "tutorial-boop");
    }

    /** The ping's two bell-like notes from {@code start}, the second a fifth above the first. */
    private static void addPing(float[] mix, int start) {
        addBell(mix, PING_FIRST, start, PING_LOUDNESS);
        addBell(mix, PING_SECOND, start + (int) (PING_GAP * RATE), PING_LOUDNESS);
    }

    /** One bell-like note: a quick rise, then dying away, with a quieter overtone for its shimmer. */
    private static void addBell(float[] mix, float hz, int start, float loudness) {
        int ring = (int) (PING_RING * RATE), rise = RATE / 300;
        for (int i = 0; i < ring && start + i < mix.length; i++) {
            float t = i / (float) RATE;
            float envelope = Math.min(1f, i / (float) rise) * (float) Math.exp(-t * 9.0);
            double phase = 2.0 * Math.PI * hz * t;
            mix[start + i] += loudness * envelope * (float) (0.75 * Math.sin(phase) + 0.25 * Math.sin(2.0 * phase) * Math.exp(-t * 8.0));
        }
    }

    /** A boop: a round, pure note that slides a little up into its pitch and is gone in a moment. */
    private static void addBoop(float[] mix, float hz) {
        int length = Math.min(mix.length, (int) (BOOP_LENGTH * RATE)), rise = RATE / 150;
        double phase = 0.0;
        for (int i = 0; i < length; i++) {
            float t = i / (float) RATE;
            // (starts a few per cent flat and settles, which is what makes it a boop rather than a beep)
            double pitch = hz * (1.0 - 0.06 * Math.exp(-t * 40.0));
            phase += 2.0 * Math.PI * pitch / RATE;
            float fall = 1f - t / BOOP_LENGTH;
            float envelope = Math.min(1f, i / (float) rise) * fall * fall;
            mix[i] += BOOP_LOUDNESS * envelope * (float) (Math.sin(phase) + 0.12 * Math.sin(3.0 * phase));
        }
    }

    /** Plays a mix once on a thread of its own (or nothing, without a sound device). */
    private static void play(float[] mix, String name) {
        Thread thread = new Thread(() -> {
            byte[] bytes = new byte[mix.length * 2];
            for (int i = 0; i < mix.length; i++) {
                short s = (short) (Math.max(-1f, Math.min(1f, mix[i])) * Short.MAX_VALUE);
                bytes[2 * i] = (byte) s;
                bytes[2 * i + 1] = (byte) (s >> 8);
            }
            AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();
                line.write(bytes, 0, bytes.length);
                line.drain();
            } catch (Exception e) {
                System.err.println("Tutorial sound unavailable: " + e.getMessage());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }
}
