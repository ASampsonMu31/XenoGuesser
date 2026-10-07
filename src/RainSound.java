import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.Random;

/**
 * The sound of the weather, made as it plays: a soft hiss of air always, rising to the rush of
 * rain as it falls harder, with drops tapping on top, more of them and louder the heavier the
 * rain. Each drop is a short burst of noise ringing briefly through a low, heavily damped
 * resonance (the tap), with a touch of softer splash (the wet). Told how hard it's raining
 * where the player is (0 to 1) every frame; it eases to that. Cut off at once while the world
 * isn't in view (the results, the shop), and taken up afresh when it is again.
 */
public final class RainSound {
    private static final int RATE = 22050, BLOCK = 256;
    // The hiss with no rain, and at the heaviest rain; and how many drops a second at the heaviest
    private static final float CALM_HISS = 0.012f, STORM_HISS = 0.1f, MOST_DROPS = 70f;
    // A drop's tap: how low and high it rings (Hz), and how long its burst and its ring last (seconds)
    private static final float TAP_LOW = 300f, TAP_HIGH = 500f, BURST = 0.003f, RING = 0.0001f;

    private volatile float rain;
    private volatile boolean audible = true, freshStart;
    private volatile boolean running;
    private SourceDataLine line;

    /** How hard it's raining where the player is, 0 (none, or snow) to 1. */
    public void setRain(float rain) {
        this.rain = Math.max(0f, Math.min(1f, rain));
    }

    /** Whether the world's in view: silent at once while it isn't, and picked up afresh (not eased from before) when it is again. */
    public void setAudible(boolean audible) {
        if (audible == this.audible) return;
        this.audible = audible;
        if (audible) freshStart = true;
        else {
            // (what's queued to play is dropped, so it stops now)
            SourceDataLine playing = line;
            if (playing != null) playing.flush();
        }
    }

    public void start() {
        if (running) return;
        running = true;
        Thread thread = new Thread(this::play, "rain-sound");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
    }

    private void play() {
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BLOCK * 2 * 6);
            line.start();
        } catch (Exception e) {
            System.err.println("Rain sound unavailable: " + e.getMessage());
            return;
        }
        Random rand = new Random();
        byte[] bytes = new byte[BLOCK * 2];
        float shownRain = 0f, gain = 0f;
        float low = 0f, lower = 0f;
        // The drops sounding now: each {samples left in its burst, burst length, resonator's two
        // coefficients, its last two outputs, loudness, splash's last output}
        float[][] drops = new float[64][];
        try {
            while (running) {
                boolean hear = audible;
                if (freshStart) {
                    freshStart = false;
                    shownRain = rain;
                    gain = 0f;
                    java.util.Arrays.fill(drops, null);
                }
                for (int i = 0; i < BLOCK; i++) {
                    shownRain += (rain - shownRain) * 0.00005f;
                    // Fading in gently; silenced at once
                    gain = hear ? gain + (1f - gain) * 0.0005f : 0f;
                    float r = shownRain;
                    // The hiss: white noise softened (and deepened as the rain grows) into a rushing sound
                    float white = rand.nextFloat() * 2f - 1f;
                    low += (white - low) * (0.25f + 0.3f * r);
                    lower += (low - lower) * 0.5f;
                    float hiss = lower * (CALM_HISS + (STORM_HISS - CALM_HISS) * r * r);
                    // Drops, started at random, more often the heavier the rain
                    if (r > 0.02f && rand.nextFloat() < MOST_DROPS * r * r * r / RATE * 3f) {
                        for (int d = 0; d < drops.length; d++) {
                            if (drops[d] != null) continue;
                            // A two-pole resonator, damped to die away over RING
                            double frequency = TAP_LOW + rand.nextFloat() * (TAP_HIGH - TAP_LOW);
                            double radius = Math.exp(-1.0 / (RING * RATE * (0.6 + 0.8 * rand.nextFloat())));
                            float a1 = (float) (2 * radius * Math.cos(2 * Math.PI * frequency / RATE)), a2 = (float) (-radius * radius);
                            float burst = RATE * BURST * (0.6f + 0.8f * rand.nextFloat());
                            float loud = (0.6f + 2.2f * r) * (0.25f + 0.75f * rand.nextFloat());
                            drops[d] = new float[] { burst, burst, a1, a2, 0f, 0f, loud, 0f, RATE * RING * 3f };
                            break;
                        }
                    }
                    float patter = 0f;
                    for (int d = 0; d < drops.length; d++) {
                        float[] drop = drops[d];
                        if (drop == null) continue;
                        // The burst: noise falling away quickly, exciting the ring
                        float excite = drop[0] > 0f ? (rand.nextFloat() * 2f - 1f) * (drop[0] / drop[1]) : 0f;
                        drop[0]--;
                        float y = excite * 0.08f + drop[2] * drop[4] + drop[3] * drop[5];
                        drop[5] = drop[4];
                        drop[4] = y;
                        // A little of the burst itself, softened: the splash
                        drop[7] += (excite - drop[7]) * 0.45f;
                        patter += (y + drop[7] * 0.12f) * drop[6];
                        if (--drop[8] <= 0f) drops[d] = null;
                    }
                    float out = (hiss + patter) * gain;
                    int v = Math.round(Math.max(-1f, Math.min(1f, out)) * 32767f);
                    bytes[i * 2] = (byte) v;
                    bytes[i * 2 + 1] = (byte) (v >> 8);
                }
                line.write(bytes, 0, bytes.length);
            }
        } finally {
            line.stop();
            line.flush();
            line.close();
        }
    }
}
