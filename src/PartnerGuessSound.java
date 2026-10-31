import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * The chime heard in multiplayer when the partner guesses first: two bright, bell-like notes, the
 * second a fifth above the first, so it's plain the clock has started even when the player's
 * looking away from the screen's border. Made once as it's first needed (as PodSounds' one-off
 * sounds are) and played on a thread of its own, so it never holds up whoever asked for it.
 */
final class PartnerGuessSound {
    private static final int RATE = 22050;
    // The two notes (Hz), when the second starts and how long each rings (seconds), and how loud
    private static final float FIRST = 880f, SECOND = 1318.5f, GAP = 0.13f, RING = 0.55f, LOUDNESS = 0.32f;

    private static short[] samples;

    private PartnerGuessSound() {
    }

    /** Plays the chime once (or nothing, without a sound device). */
    static void play() {
        Thread thread = new Thread(PartnerGuessSound::playNow, "partner-guess-sound");
        thread.setDaemon(true);
        thread.start();
    }

    private static void playNow() {
        short[] chime = chime();
        byte[] bytes = new byte[chime.length * 2];
        for (int i = 0; i < chime.length; i++) {
            bytes[2 * i] = (byte) chime[i];
            bytes[2 * i + 1] = (byte) (chime[i] >> 8);
        }
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            line.write(bytes, 0, bytes.length);
            line.drain();
        } catch (Exception e) {
            System.err.println("Partner's guess sound unavailable: " + e.getMessage());
        }
    }

    /** The chime's samples, made the first time they're asked for. */
    private static synchronized short[] chime() {
        if (samples != null) return samples;
        int length = (int) ((GAP + RING) * RATE);
        float[] mix = new float[length];
        addNote(mix, FIRST, 0);
        addNote(mix, SECOND, (int) (GAP * RATE));
        samples = new short[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (short) (Math.max(-1f, Math.min(1f, mix[i] * LOUDNESS)) * Short.MAX_VALUE);
        }
        return samples;
    }

    /** One bell-like note from {@code start}: a quick rise, then dying away, with a quieter overtone for its shimmer. */
    private static void addNote(float[] mix, float hz, int start) {
        int ring = (int) (RING * RATE), rise = RATE / 200;
        for (int i = 0; i < ring && start + i < mix.length; i++) {
            float t = i / (float) RATE;
            float envelope = Math.min(1f, i / (float) rise) * (float) Math.exp(-t * 7.0);
            double phase = 2.0 * Math.PI * hz * t;
            mix[start + i] += envelope * (float) (0.75 * Math.sin(phase) + 0.25 * Math.sin(2.0 * phase) * Math.exp(-t * 6.0));
        }
    }
}
