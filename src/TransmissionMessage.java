import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

/**
 * The recorded message from Xenocorp played in the cockpit while the world loads, with its
 * transcript and when each word is spoken, so the scrolling text can keep pace with it.
 */
public class TransmissionMessage {

    public static final String AUDIO = "assets/audio/xenocorp_message.wav";
    public static final String TIMINGS = "assets/audio/xenocorp_message.txt";

    private final String transcript;
    private final long[] wordMillis;
    private final int[] wordChars;
    private final long endMillis;
    private final Clip clip;
    private volatile boolean stopped;
    private volatile boolean ended;
    // Asking the clip where it is can block while the sound system is busy, so a background
    // thread asks and the screen reads the last answer, moved on by the time since
    private volatile long sampledMillis, sampledAt;
    private volatile boolean running;

    private TransmissionMessage(String transcript, long[] wordMillis, int[] wordChars, long endMillis, Clip clip) {
        this.transcript = transcript;
        this.wordMillis = wordMillis;
        this.wordChars = wordChars;
        this.endMillis = endMillis;
        this.clip = clip;
        if (clip != null) {
            clip.addLineListener(e -> {
                if (e.getType() == javax.sound.sampled.LineEvent.Type.STOP) ended = true;
            });
        }
    }

    /** Loads the message, or returns null if it or the sound device is unavailable. */
    public static TransmissionMessage load() {
        try {
            List<String> lines = Files.readAllLines(new File(TIMINGS).toPath(), StandardCharsets.UTF_8);
            String transcript = lines.get(0);
            long end = Long.parseLong(lines.get(1).split("\t")[1]);
            List<String> words = lines.subList(2, lines.size()).stream().filter(l -> l.contains("\t")).toList();
            long[] ms = new long[words.size()];
            int[] chars = new int[words.size()];
            for (int i = 0; i < words.size(); i++) {
                String[] parts = words.get(i).split("\t");
                ms[i] = Long.parseLong(parts[0].trim());
                chars[i] = Integer.parseInt(parts[1].trim());
            }
            Clip clip = null;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(AUDIO))) {
                clip = AudioSystem.getClip();
                clip.open(in);
            } catch (Exception e) {
                System.err.println("Message audio unavailable: " + e.getMessage());
            }
            return new TransmissionMessage(transcript, ms, chars, end, clip);
        } catch (Exception e) {
            System.err.println("Message unavailable: " + e.getMessage());
            return null;
        }
    }

    public void play() {
        if (clip == null || stopped) return;
        clip.start();
        sampledAt = System.nanoTime();
        running = true;
        Thread poll = new Thread(() -> {
            while (!stopped && !ended) {
                long ms = clip.getMicrosecondPosition() / 1000;
                sampledMillis = ms;
                sampledAt = System.nanoTime();
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
            running = false;
        }, "message-position");
        poll.setDaemon(true);
        poll.start();
    }

    public void stop() {
        stopped = true;
        if (clip != null) {
            // Closing can wait on the sound system, so it is done in the background
            Thread close = new Thread(() -> {
                clip.stop();
                clip.close();
            }, "message-stop");
            close.setDaemon(true);
            close.start();
        }
    }

    public String transcript() {
        return transcript;
    }

    /** Milliseconds into the message. */
    public long position() {
        if (!running) return sampledMillis;
        return sampledMillis + (System.nanoTime() - sampledAt) / 1_000_000;
    }

    public boolean isFinished() {
        return stopped || ended || clip == null;
    }

    /**
     * How far through the transcript the voice has got, in characters (fractional), moving
     * smoothly from each word to the next and to the end once the last has been said.
     */
    public float spokenCharacters() {
        // A finished clip may report its position as back at the start
        if (isFinished()) return transcript.length();
        long now = position();
        if (wordMillis.length == 0) return 0f;
        if (now <= wordMillis[0]) return wordChars[0] * Math.max(0f, now / (float) Math.max(1, wordMillis[0]));
        for (int i = 0; i < wordMillis.length; i++) {
            long nextMs = i + 1 < wordMillis.length ? wordMillis[i + 1] : endMillis;
            int nextChar = i + 1 < wordMillis.length ? wordChars[i + 1] : transcript.length();
            if (now < nextMs) {
                float f = (now - wordMillis[i]) / (float) Math.max(1, nextMs - wordMillis[i]);
                return wordChars[i] + f * (nextChar - wordChars[i]);
            }
        }
        return transcript.length();
    }
}
