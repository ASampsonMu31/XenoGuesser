import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Developer tool (M12): makes the joiner's multiplayer message, xenocorp_multiplayer_b, from the
 * host's, xenocorp_multiplayer_a (the original recording, which already says "Good morning,
 * Employee 119-A ... you have been paired with Employee 119-B"). The two spoken letters are
 * swapped, so the joiner hears "Employee 119-B ... paired with Employee 119-A" in the same
 * recording, voice and processing. Rerun it if the A message changes:
 *   java -cp target/classes MessageSplicer
 *
 * Why a splice rather than saying the whole message again with PlanetVoice: the voice takes
 * eSpeak phonemes, and eSpeak isn't part of the project, so the transcript can't be turned into
 * them here; and the model gives no word timings. Swapping the letters keeps everything else
 * exactly as recorded, and the word timings follow exactly from the cuts.
 *
 * The letters' places were measured from the A recording's loudness, 5 ms at a time (see the
 * plan's LOG): each one starts at the quiet dip after "nineteen" and ends where the sound falls
 * to the background hiss. The "B" includes its closure (the lips shut before the burst).
 * Each piece is faded in and out over a few milliseconds where it meets its new neighbours, so
 * the joins don't click. The transcript keeps its length (one letter for one), so its character
 * places stay; times after the first swap move by the difference in the letters' lengths, back
 * again after the second, and the planet sentence's slot (between the two) moves with them.
 */
public final class MessageSplicer {

    private static final String A = GamePaths.HOME + "assets/audio/xenocorp_multiplayer_a";
    private static final String B = GamePaths.HOME + "assets/audio/xenocorp_multiplayer_b";

    // Where each letter is said in the A recording (milliseconds): "Employee 119-A," and "Employee 119-B."
    private static final double LETTER_A_START = 8625, LETTER_A_END = 8940;
    private static final double LETTER_B_START = 28268, LETTER_B_END = 28530;
    private static final double FADE_MS = 4;

    private MessageSplicer() {
    }

    public static void main(String[] args) throws Exception {
        AudioFormat format;
        byte[] pcm;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(A + ".wav"))) {
            format = in.getFormat();
            pcm = in.readAllBytes();
        }
        if (format.getChannels() != 1 || format.getSampleSizeInBits() != 16 || format.isBigEndian()) {
            throw new IllegalStateException("Expected 16-bit little-endian mono, found " + format);
        }
        float rate = format.getSampleRate();
        short[] samples = new short[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));

        int a0 = frame(LETTER_A_START, rate), a1 = frame(LETTER_A_END, rate);
        int b0 = frame(LETTER_B_START, rate), b1 = frame(LETTER_B_END, rate);
        int fade = frame(FADE_MS, rate);
        // Before the A, the B, between, the A, after: each piece faded at the joins
        List<short[]> pieces = List.of(
                piece(samples, 0, a0, 0, fade),
                piece(samples, b0, b1, fade, fade),
                piece(samples, a1, b0, fade, fade),
                piece(samples, a0, a1, fade, fade),
                piece(samples, b1, samples.length, fade, 0));
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length);
        for (short[] p : pieces) {
            for (short v : p) {
                out.write(v & 0xFF);
                out.write((v >> 8) & 0xFF);
            }
        }
        byte[] spliced = out.toByteArray();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(spliced), format, spliced.length / 2),
                AudioFileFormat.Type.WAVE, new File(B + ".wav"));

        // The timings: words between the two letters (and the slot) move by the length difference
        int shiftFrames = (b1 - b0) - (a1 - a0);
        long shiftMs = Math.round(shiftFrames * 1000.0 / rate);
        List<String> lines = Files.readAllLines(new File(A + ".txt").toPath(), StandardCharsets.UTF_8);
        List<String> result = new ArrayList<>();
        String transcript = lines.get(0);
        int first = transcript.indexOf("119-A"), second = transcript.indexOf("119-B");
        if (first < 0 || second < first) throw new IllegalStateException("The A transcript doesn't say 119-A then 119-B");
        StringBuilder swapped = new StringBuilder(transcript);
        swapped.setCharAt(first + 4, 'B');
        swapped.setCharAt(second + 4, 'A');
        result.add(swapped.toString());
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split("\t");
            if (parts.length >= 2 && !parts[0].isEmpty() && Character.isDigit(parts[0].charAt(0))) {
                long ms = Long.parseLong(parts[0].trim());
                int ch = Integer.parseInt(parts[1].trim());
                // (the words after "119-A," up to and including "119-B.")
                if (ch > first && ch <= second) ms += shiftMs;
                result.add(ms + "\t" + ch);
            } else if (parts[0].equals("slot")) {
                int slotFrame = Integer.parseInt(parts[1]);
                if (slotFrame > a1 && slotFrame < b0) slotFrame += shiftFrames;
                parts[1] = Integer.toString(slotFrame);
                result.add(String.join("\t", parts));
            } else {
                result.add(line);
            }
        }
        Files.write(new File(B + ".txt").toPath(), result, StandardCharsets.UTF_8);
        System.out.printf("Wrote %s.wav and .txt: letters swapped, everything between them moved by %d ms%n", B, shiftMs);
    }

    private static int frame(double ms, float rate) {
        return (int) Math.round(ms * rate / 1000.0);
    }

    /** samples[from, to), faded in over its first fadeIn samples and out over its last fadeOut. */
    private static short[] piece(short[] samples, int from, int to, int fadeIn, int fadeOut) {
        short[] p = new short[to - from];
        for (int i = 0; i < p.length; i++) {
            float gain = 1f;
            if (i < fadeIn) gain = Math.min(gain, i / (float) fadeIn);
            if (p.length - 1 - i < fadeOut) gain = Math.min(gain, (p.length - 1 - i) / (float) fadeOut);
            p[i] = (short) Math.round(samples[from + i] * gain);
        }
        return p;
    }
}
