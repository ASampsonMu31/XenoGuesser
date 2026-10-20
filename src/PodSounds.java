import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * The landing pod's sounds, made as they play (as RainSound's are): the cabin's hum and the
 * hiss of its air always; the engines' throb as the pod's turned; the roar and crackle of the
 * air tearing past as it comes in; the low rumble of coming down; the chair's casters rolling.
 * And sounds that happen once: the clunk as the pod's let go, the thrusters' puffs, the lights
 * switch's click, the thump of landing, the hiss of the hatch.
 *
 * Told how loud each continuing sound should be every frame (it eases to that); one-off sounds
 * are queued from any thread.
 */
public final class PodSounds {

    enum Shot { CLUNK, THRUSTER, SWITCH, LANDING, HATCH }

    private static final int RATE = 22050, BLOCK = 256;

    private volatile float engine, roar, rumble, rolling;
    private volatile boolean running;
    private final ConcurrentLinkedQueue<Shot> queued = new ConcurrentLinkedQueue<>();
    private final float[][] shots = new float[Shot.values().length][];

    /** How loud the engines, the air's roar, the rumble of coming down and the chair's rolling are, 0..1. */
    void set(float engine, float roar, float rumble, float rolling) {
        this.engine = engine;
        this.roar = roar;
        this.rumble = rumble;
        this.rolling = Math.max(0f, Math.min(1f, rolling));
    }

    void play(Shot shot) {
        queued.add(shot);
    }

    void start() {
        if (running) return;
        running = true;
        Thread thread = new Thread(this::run, "pod-sound");
        thread.setDaemon(true);
        // (ahead of the world being made, so the sounds never stutter while it is)
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void stop() {
        running = false;
    }

    private void run() {
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BLOCK * 2 * 8);
            line.start();
        } catch (Exception e) {
            System.err.println("Pod sound unavailable: " + e.getMessage());
            return;
        }
        Random rand = new Random(5);
        makeShots(rand);
        byte[] bytes = new byte[BLOCK * 2];
        // What's playing of the one-off sounds: {which, where it's got to}
        List<int[]> playing = new ArrayList<>();
        float e = 0f, r = 0f, ru = 0f, ro = 0f;
        float phase = 0f, enginePhase = 0f, engineLfo = 0f;
        // Filter memories: the air's hiss, the engine's noise, the roar (two poles), the rumble, the rolling
        float air = 0f, engineNoise = 0f, roarLow = 0f, roarLower = 0f, rumbleLow = 0f, rollLow = 0f, rollBand = 0f;
        float crackle = 0f;
        try {
            while (running) {
                for (Shot shot = queued.poll(); shot != null; shot = queued.poll()) playing.add(new int[] { shot.ordinal(), 0 });
                for (int i = 0; i < BLOCK; i++) {
                    // Each continuing sound eased to how loud it's wanted
                    e += (engine - e) * 0.0004f;
                    r += (roar - r) * 0.0003f;
                    ru += (rumble - ru) * 0.0003f;
                    ro += (rolling - ro) * 0.002f;
                    float white = rand.nextFloat() * 2f - 1f;
                    // The cabin: mains hum and the air through its vents
                    phase += (float) (2 * Math.PI * 55.0 / RATE);
                    if (phase > 2 * Math.PI) phase -= (float) (2 * Math.PI);
                    air += (white - air) * 0.08f;
                    float sample = 0.016f * (float) Math.sin(phase) + 0.008f * (float) Math.sin(phase * 2f) + 0.02f * air;
                    // The engines: two low saws beating, through a throb, with their noise
                    if (e > 0.001f) {
                        enginePhase += 42f / RATE;
                        engineLfo += 6.5f / RATE;
                        float saw = (enginePhase % 1f) * 2f - 1f + ((enginePhase * 1.03f) % 1f) * 2f - 1f;
                        engineNoise += (white - engineNoise) * 0.05f;
                        float throb = 0.75f + 0.25f * (float) Math.sin(engineLfo * 2 * Math.PI);
                        sample += e * throb * (0.1f * saw + 0.35f * engineNoise);
                    }
                    // The air tearing past: deep noise, its brightness rising with it, and crackles
                    if (r > 0.001f) {
                        float cutoff = 0.02f + 0.1f * r;
                        roarLow += (white - roarLow) * cutoff;
                        roarLower += (roarLow - roarLower) * cutoff;
                        if (rand.nextFloat() < r * 0.0015f) crackle = 0.6f + 0.4f * rand.nextFloat();
                        crackle *= 0.985f;
                        sample += r * (0.9f * roarLower + 0.25f * roarLow) + crackle * white * 0.5f * r;
                    }
                    // The rumble of coming down, felt more than heard
                    if (ru > 0.001f) {
                        rumbleLow += (white - rumbleLow) * 0.012f;
                        sample += ru * 1.6f * rumbleLow;
                    }
                    // The chair's casters on the grating
                    if (ro > 0.001f) {
                        rollLow += (white - rollLow) * 0.25f;
                        rollBand += (rollLow - rollBand) * 0.1f;
                        sample += ro * 0.25f * (rollLow - rollBand) * (0.7f + 0.3f * (float) Math.sin(phase * 0.13f));
                    }
                    for (int k = playing.size() - 1; k >= 0; k--) {
                        int[] p = playing.get(k);
                        float[] wave = shots[p[0]];
                        sample += wave[p[1]++];
                        if (p[1] >= wave.length) playing.remove(k);
                    }
                    // Softly limited, never clipping
                    float out = (float) Math.tanh(sample * 1.3f) * 0.85f;
                    int v = Math.round(out * 32767f);
                    bytes[i * 2] = (byte) v;
                    bytes[i * 2 + 1] = (byte) (v >> 8);
                }
                line.write(bytes, 0, bytes.length);
            }
        } finally {
            line.drain();
            line.close();
        }
    }

    /** The one-off sounds, made once. */
    private void makeShots(Random rand) {
        shots[Shot.CLUNK.ordinal()] = clunk(rand);
        shots[Shot.THRUSTER.ordinal()] = puff(rand, 0.6f, 0.5f);
        shots[Shot.SWITCH.ordinal()] = click(rand);
        shots[Shot.LANDING.ordinal()] = thump(rand);
        shots[Shot.HATCH.ordinal()] = puff(rand, 1.4f, 0.35f);
    }

    /** The pod let go: a heavy latch's clunk, a ringing of the hull and a click before it. */
    private static float[] clunk(Random rand) {
        int n = (int) (RATE * 1.4f);
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float click = t < 0.004f ? (rand.nextFloat() * 2f - 1f) * 0.8f : 0f;
            float body = (float) Math.sin(2 * Math.PI * 85.0 * t) * (float) Math.exp(-t * 9.0) * 0.7f;
            float ring = ((float) Math.sin(2 * Math.PI * 620.0 * t) + 0.6f * (float) Math.sin(2 * Math.PI * 931.0 * t)) * (float) Math.exp(-t * 4.0) * 0.12f;
            float knock = t > 0.09f && t < 0.096f ? (rand.nextFloat() * 2f - 1f) * 0.5f : 0f;
            w[i] = click + body + ring + knock;
        }
        return w;
    }

    /** A rush of gas: noise swelling and dying away over length seconds. */
    private static float[] puff(Random rand, float length, float loudness) {
        int n = (int) (RATE * length);
        float[] w = new float[n];
        float low = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float white = rand.nextFloat() * 2f - 1f;
            low += (white - low) * 0.3f;
            float envelope = (float) (Math.min(1.0, t * 12.0) * Math.pow(1.0 - t, 2.0));
            w[i] = (white - low) * envelope * loudness;
        }
        return w;
    }

    /** A chunky switch: two sharp ticks a moment apart. */
    private static float[] click(Random rand) {
        int n = (int) (RATE * 0.12f);
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float first = (float) Math.exp(-t * 900.0) * (rand.nextFloat() * 2f - 1f);
            float t2 = t - 0.035f;
            float second = t2 > 0f ? (float) Math.exp(-t2 * 1200.0) * (rand.nextFloat() * 2f - 1f) * 0.6f : 0f;
            float ring = (float) Math.sin(2 * Math.PI * 2400.0 * t) * (float) Math.exp(-t * 120.0) * 0.15f;
            w[i] = (first + second) * 0.7f + ring;
        }
        return w;
    }

    /** Landing: a deep thump falling in pitch, a crunch, and everything loose rattling as it settles. */
    private static float[] thump(Random rand) {
        int n = (int) (RATE * 1.8f);
        float[] w = new float[n];
        float phase = 0f, low = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            phase += (float) (2 * Math.PI * (70.0 * Math.exp(-t * 3.0) + 28.0) / RATE);
            float boom = (float) Math.sin(phase) * (float) Math.exp(-t * 3.5);
            float white = rand.nextFloat() * 2f - 1f;
            low += (white - low) * 0.15f;
            float crunch = low * (float) Math.exp(-t * 14.0) * 1.2f;
            float rattle = t > 0.15f ? white * (float) Math.exp(-(t - 0.15) * 5.0) * 0.12f * (rand.nextFloat() < 0.05f ? 3f : 1f) : 0f;
            w[i] = boom * 0.95f + crunch + rattle;
        }
        return w;
    }
}
