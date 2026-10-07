import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * The recorded message from Xenocorp played in the cockpit while the world loads, with its
 * transcript and when each word is spoken, so the scrolling text can keep pace with it. The
 * sentence naming the planet isn't in the recording: it's said afresh in the same voice with
 * the world's own planet name (see PlanetName, PlanetVoice), started while the main menu is up
 * (see prepare), and put in where it belongs as the message plays. If it isn't ready by the time
 * the name would come into view on the scrolling text, a stock recording naming a made-up
 * planet of no one's (FALLBACK_NAME) goes in instead.
 */
public class TransmissionMessage {

    // Each message is a recording (.wav) and its transcript with word timings (.txt)
    public static final String SINGLEPLAYER = GamePaths.HOME + "assets/audio/xenocorp_singleplayer";
    public static final String MULTIPLAYER = GamePaths.HOME + "assets/audio/xenocorp_multiplayer";
    // Played in the shop before the first round: the free gift, and that everything after is to be paid for
    public static final String GIFT = GamePaths.HOME + "assets/audio/xenocorp_gift";
    // Played as April begins: surprise that the employee's alive, given how much of the planet is ocean
    public static final String APRIL = GamePaths.HOME + "assets/audio/xenocorp_april";
    // The planet sentence said with a stock name, unprocessed, for when the world's own isn't ready
    public static final String FALLBACK_AUDIO = GamePaths.HOME + "assets/audio/planet_fallback.wav";
    public static final String FALLBACK_NAME = "Groonia";

    // Where the planet's name goes in the transcript
    private static final String PLANET = "{planet}";

    // The planet sentences being said, by world seed (both messages say it the same way)
    private static final Map<Long, CompletableFuture<PlanetVoice.Speech>> preparing = new ConcurrentHashMap<>();

    /**
     * Starts saying the planet sentence for a world in the background (from the main menu), so
     * it's ready when the message plays.
     */
    public static void prepare(long seed) {
        preparing.computeIfAbsent(seed, s -> {
            CompletableFuture<PlanetVoice.Speech> speech = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                try {
                    Map<String, String[]> extra = readExtra(SINGLEPLAYER + ".txt");
                    speech.complete(PlanetVoice.say(extra.get("say")[1] + " " + PlanetName.forSeed(s).phonemes + "."));
                } catch (Exception e) {
                    speech.complete(null);
                }
            }, "planet-voice");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
            return speech;
        });
    }

    // What's said and shown: the transcript and each word's time and place in it (both settled
    // once the planet's name is), how long the voice goes on for
    private volatile String transcript;
    private volatile long[] wordMillis;
    private volatile int[] wordChars;
    private volatile long endMillis;
    // The recording, where the planet sentence goes into it, and how
    private final AudioFormat format;
    private final byte[] pcm;
    private final Map<String, String[]> extra;
    private final String baseTranscript;
    private final List<long[]> baseWords;
    private final long baseEnd;
    private final int slotFrame, slotChar;
    // What goes in the slot (the planet's name, or whatever else the message says there), and
    // whether a stock recording stands in for it should it not be said in time
    private final String slotName;
    private final boolean stockFallback;
    private final CompletableFuture<PlanetVoice.Speech> speech;
    private volatile byte[] sentence;
    private volatile boolean settled;
    private SourceDataLine line;
    private volatile boolean stopped;
    private volatile boolean ended;
    // Asking the line where it is can block while the sound system is busy, so a background
    // thread asks and the screen reads the last answer, moved on by the time since
    private volatile long sampledMillis, sampledAt;
    private volatile boolean running;

    private TransmissionMessage(String transcript, List<long[]> words, long end, AudioFormat format, byte[] pcm,
                                Map<String, String[]> extra, String slotName, boolean stockFallback,
                                CompletableFuture<PlanetVoice.Speech> speech) {
        this.baseTranscript = transcript;
        this.baseWords = words;
        this.baseEnd = end;
        this.format = format;
        this.pcm = pcm;
        this.extra = extra;
        this.slotName = slotName;
        this.stockFallback = stockFallback;
        this.speech = speech;
        String[] slot = extra.get("slot");
        slotFrame = slot != null ? Integer.parseInt(slot[1]) : -1;
        slotChar = slot != null ? Integer.parseInt(slot[2]) : -1;
        if (slot == null) {
            settled = true;
            sentence = new byte[0];
            set(transcript, words, end);
        } else {
            // Until it's settled, laid out as if the stock name: the name itself is off to the right, unseen
            if (stockFallback) arrange(FALLBACK_NAME, fallbackSpeech(), false);
            else arrange(slotName, null, false);
        }
    }

    /** Loads the message for a world's planet, or returns null if it's unavailable. */
    public static TransmissionMessage load(String name, long seed) {
        try {
            List<String> lines = Files.readAllLines(new File(name + ".txt").toPath(), StandardCharsets.UTF_8);
            String transcript = lines.get(0);
            long end = Long.parseLong(lines.get(1).split("\t")[1]);
            List<long[]> words = new ArrayList<>();
            for (String line : lines.subList(2, lines.size())) {
                String[] parts = line.split("\t");
                if (parts.length >= 2 && Character.isDigit(parts[0].charAt(0))) {
                    words.add(new long[] { Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()) });
                }
            }
            AudioFormat format;
            byte[] pcm;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(name + ".wav"))) {
                format = in.getFormat();
                pcm = in.readAllBytes();
            }
            prepare(seed);
            return new TransmissionMessage(transcript, words, end, format, pcm, readExtra(name + ".txt"),
                    PlanetName.forSeed(seed).spelling, true, preparing.get(seed));
        } catch (Exception e) {
            System.err.println("Message unavailable: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads a message whose slot says something other than the planet's name: slotName shown in
     * the transcript, speech (being said by the time it's needed, see sentenceWith) heard there;
     * should it not be ready, that stretch is left silent. Null if the message is unavailable.
     */
    public static TransmissionMessage load(String name, String slotName, CompletableFuture<PlanetVoice.Speech> speech) {
        try {
            List<String> lines = Files.readAllLines(new File(name + ".txt").toPath(), StandardCharsets.UTF_8);
            String transcript = lines.get(0);
            long end = Long.parseLong(lines.get(1).split("\t")[1]);
            List<long[]> words = new ArrayList<>();
            for (String line : lines.subList(2, lines.size())) {
                String[] parts = line.split("\t");
                if (parts.length >= 2 && Character.isDigit(parts[0].charAt(0))) {
                    words.add(new long[] { Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()) });
                }
            }
            AudioFormat format;
            byte[] pcm;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(name + ".wav"))) {
                format = in.getFormat();
                pcm = in.readAllBytes();
            }
            return new TransmissionMessage(transcript, words, end, format, pcm, readExtra(name + ".txt"), slotName, false, speech);
        } catch (Exception e) {
            System.err.println("Message unavailable: " + e.getMessage());
            return null;
        }
    }

    /**
     * The phonemes for a message's slot sentence with a percentage in it (to three decimal
     * places, e.g. "seventy one point two eight four percent"), from the words' sounds stored with
     * the message; null if they're unavailable.
     */
    public static String sentenceWithPercent(String name, double percent) {
        try {
            Map<String, String[]> extra = readExtra(name + ".txt");
            Map<String, String> sounds = new HashMap<>();
            for (String line : Files.readAllLines(new File(name + ".txt").toPath(), StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length == 3 && parts[0].equals("word")) sounds.put(parts[1], parts[2]);
            }
            String[] ones = { "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
                    "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen" };
            String[] tens = { "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };
            String digits = String.format(java.util.Locale.ROOT, "%.3f", percent);
            int whole = Integer.parseInt(digits.substring(0, digits.indexOf('.')));
            List<String> said = new ArrayList<>();
            if (whole >= 100) said.addAll(List.of("one", "hundred"));
            else if (whole < 20) said.add(ones[whole]);
            else {
                said.add(tens[whole / 10]);
                if (whole % 10 != 0) said.add(ones[whole % 10]);
            }
            said.add("point");
            for (char c : digits.substring(digits.indexOf('.') + 1).toCharArray()) said.add(ones[c - '0']);
            said.add("percent");
            StringBuilder phonemes = new StringBuilder(extra.get("say")[1]);
            for (String word : said) phonemes.append(' ').append(sounds.get(word));
            return phonemes.append('.').toString();
        } catch (Exception e) {
            System.err.println("Message unavailable: " + e.getMessage());
            return null;
        }
    }

    /** The lines after the word timings: where the planet sentence goes, and how it should sound. */
    private static Map<String, String[]> readExtra(String timings) throws java.io.IOException {
        Map<String, String[]> extra = new HashMap<>();
        List<String> lines = Files.readAllLines(new File(timings).toPath(), StandardCharsets.UTF_8);
        for (String line : lines.subList(2, lines.size())) {
            String[] parts = line.split("\t");
            if (parts.length >= 2 && !Character.isDigit(parts[0].charAt(0))) extra.put(parts[0], parts);
        }
        return extra;
    }

    private static PlanetVoice.Speech fallbackSpeech() {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(FALLBACK_AUDIO))) {
            byte[] bytes = in.readAllBytes();
            boolean big = in.getFormat().isBigEndian();
            float[] audio = new float[bytes.length / 2];
            for (int i = 0; i < audio.length; i++) {
                int lo = bytes[i * 2 + (big ? 1 : 0)] & 0xFF, hi = bytes[i * 2 + (big ? 0 : 1)];
                audio[i] = (short) ((hi << 8) | lo) / 32768f;
            }
            return new PlanetVoice.Speech(audio, (int) in.getFormat().getSampleRate());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Settles the planet's name: the world's own if its sentence has been said by now, or else
     * the stock one. Called as the name is about to come into view, or when the voice reaches it.
     */
    public synchronized void settleName() {
        if (settled) return;
        PlanetVoice.Speech said = speech != null ? speech.getNow(null) : null;
        if (said != null) arrange(slotName, said, true);
        else if (stockFallback) arrange(FALLBACK_NAME, fallbackSpeech(), true);
        else arrange(slotName, null, true);
    }

    public boolean nameSettled() {
        return settled;
    }

    /** Where in the transcript the planet's name starts. */
    public int nameChar() {
        return slotChar < 0 ? -1 : baseTranscript.indexOf(PLANET, slotChar);
    }

    /**
     * Lays the message out with the planet sentence (saying name) put in: the sentence shaped to
     * sound like the rest, the words after it moved on by however long it takes, and along the
     * transcript by however much longer the name is than its placeholder.
     */
    private void arrange(String name, PlanetVoice.Speech said, boolean settle) {
        int rate = Integer.parseInt(extra.get("rate")[1]);
        float[] shaped = said != null && said.sampleRate() == rate ? shape(said.audio(), rate, extra) : new float[0];
        String text = baseTranscript.substring(slotChar, baseTranscript.indexOf('.', slotChar) + 1).replace(PLANET, name);
        String[] sentenceWords = text.split(" ");
        String[] weightText = extra.get("weights")[1].split(" ");
        float[] weights = new float[sentenceWords.length];
        float total = 0f;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = i < weightText.length ? Float.parseFloat(weightText[i]) : Math.max(1, name.length());
            total += weights[i];
        }
        long slotMs = slotFrame * 1000L / rate, lasting = shaped.length * 1000L / rate;
        int grow = name.length() - PLANET.length();
        List<long[]> words = new ArrayList<>();
        for (long[] word : baseWords) {
            words.add(word[1] >= slotChar ? new long[] { word[0] + lasting, word[1] + grow } : word.clone());
        }
        List<long[]> added = new ArrayList<>();
        float before = 0f;
        int c = slotChar;
        for (int i = 0; i < sentenceWords.length; i++) {
            added.add(new long[] { slotMs + (long) (lasting * before / total), c });
            before += weights[i];
            c += sentenceWords[i].length() + 1;
        }
        int at = 0;
        while (at < words.size() && words.get(at)[1] < slotChar) at++;
        words.addAll(at, added);
        set(baseTranscript.replace(PLANET, name), words, baseEnd + lasting);
        if (!settle) return;
        // As 16-bit samples, with the static the rest lies on
        int bytes = format.getSampleSizeInBits() / 8 * format.getChannels();
        float[] hiss = hiss(shaped.length, extra);
        byte[] out = new byte[shaped.length * bytes];
        boolean big = format.isBigEndian();
        for (int i = 0; i < shaped.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, shaped[i] + hiss[i])) * 32767f);
            for (int ch = 0; ch < format.getChannels(); ch++) {
                int o = i * bytes + ch * 2;
                out[o + (big ? 1 : 0)] = (byte) v;
                out[o + (big ? 0 : 1)] = (byte) (v >> 8);
            }
        }
        sentence = out;
        settled = true;
    }

    private void set(String text, List<long[]> words, long end) {
        long[] ms = new long[words.size()];
        int[] chars = new int[words.size()];
        for (int i = 0; i < words.size(); i++) {
            ms[i] = words.get(i)[0];
            chars[i] = (int) words.get(i)[1];
        }
        wordMillis = ms;
        wordChars = chars;
        endMillis = end;
        transcript = text;
    }

    /** Trimmed of silence at either end, then filtered and levelled as the recording's voice was. */
    private static float[] shape(float[] audio, int rate, Map<String, String[]> extra) {
        int first = 0, last = audio.length - 1;
        while (first < audio.length && Math.abs(audio[first]) <= 0.015f) first++;
        while (last > first && Math.abs(audio[last]) <= 0.015f) last--;
        if (first >= last) return new float[0];
        int from = Math.max(0, first - rate / 50), to = Math.min(audio.length, last + rate / 20);
        double[] x = new double[to - from];
        for (int i = 0; i < x.length; i++) x[i] = audio[from + i];
        // Rumble taken out, a touch of presence added
        String[] hp = extra.get("highpass"), pr = extra.get("presence");
        x = filter(numbers(hp[1]), numbers(hp[2]), x);
        double[] peak = filter(numbers(pr[1]), numbers(pr[2]), x);
        double amount = Double.parseDouble(pr[3]);
        String[] level = extra.get("level");
        double in = Double.parseDouble(level[1]), outGain = Double.parseDouble(level[2]);
        float[] shaped = new float[x.length];
        for (int i = 0; i < x.length; i++) shaped[i] = (float) (Math.tanh((x[i] + amount * peak[i]) * in) * outGain);
        return shaped;
    }

    /** The faint static the rest of the message lies on, for a stretch this long. */
    private static float[] hiss(int length, Map<String, String[]> extra) {
        float[] out = new float[length];
        String[] h = extra.get("hiss");
        if (h == null || length == 0) return out;
        Random rand = new Random(119);
        double[] noise = new double[length];
        for (int i = 0; i < length; i++) noise[i] = rand.nextGaussian();
        double[] band = filter(numbers(h[2]), numbers(h[3]), noise);
        double max = 1e-9;
        for (double v : band) max = Math.max(max, Math.abs(v));
        double level = Double.parseDouble(h[1]);
        for (int i = 0; i < length; i++) out[i] = (float) (band[i] / max * level);
        return out;
    }

    private static double[] numbers(String list) {
        String[] parts = list.trim().split(" ");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) values[i] = Double.parseDouble(parts[i]);
        return values;
    }

    /** A recursive filter, as scipy's lfilter (a[0] is 1). */
    private static double[] filter(double[] b, double[] a, double[] x) {
        double[] y = new double[x.length];
        for (int n = 0; n < x.length; n++) {
            double v = 0;
            for (int k = 0; k < b.length && k <= n; k++) v += b[k] * x[n - k];
            for (int k = 1; k < a.length && k <= n; k++) v -= a[k] * y[n - k];
            y[n] = v / a[0];
        }
        return y;
    }

    // ------------------------------------------------------------------ playing

    /**
     * Plays the message, streamed out a little at a time: the recording up to the planet
     * sentence, then the sentence (settling the name then if it hasn't been yet), then the rest.
     */
    public void play() {
        if (stopped) return;
        try {
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
            line.open(format);
        } catch (Exception e) {
            System.err.println("Message audio unavailable: " + e.getMessage());
            line = null;
            return;
        }
        line.start();
        sampledAt = System.nanoTime();
        running = true;
        int frameBytes = format.getFrameSize();
        Thread writer = new Thread(() -> {
            int split = slotFrame < 0 ? pcm.length : Math.min(pcm.length, slotFrame * frameBytes);
            write(pcm, 0, split);
            settleName();
            byte[] said = sentence;
            write(said, 0, said.length);
            write(pcm, split, pcm.length - split);
            if (!stopped) line.drain();
            ended = true;
        }, "message-audio");
        writer.setDaemon(true);
        writer.start();
        Thread poll = new Thread(() -> {
            while (!stopped && !ended) {
                sampledMillis = (long) (line.getLongFramePosition() * 1000L / format.getFrameRate());
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

    private void write(byte[] bytes, int from, int length) {
        int chunk = 4096;
        for (int i = from; i < from + length && !stopped; i += chunk) line.write(bytes, i, Math.min(chunk, from + length - i));
    }

    public void stop() {
        stopped = true;
        SourceDataLine playing = line;
        if (playing != null) {
            // Closing can wait on the sound system, so it is done in the background
            Thread close = new Thread(() -> {
                playing.stop();
                playing.flush();
                playing.close();
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

    /** How long the voice speaks for, in milliseconds. */
    public long durationMillis() {
        long[] ms = wordMillis;
        return endMillis - (ms.length > 0 ? ms[0] : 0L);
    }

    public boolean isFinished() {
        return stopped || ended || line == null;
    }

    /**
     * How far through the transcript the voice has got, in characters (fractional), moving
     * smoothly from each word to the next and to the end once the last has been said.
     */
    public float spokenCharacters() {
        String text = transcript;
        // A finished line may report its position as back at the start
        if (isFinished()) return text.length();
        long now = position();
        long[] ms = wordMillis;
        int[] chars = wordChars;
        long end = endMillis;
        if (ms.length == 0) return 0f;
        if (now <= ms[0]) return chars[0] * Math.max(0f, now / (float) Math.max(1, ms[0]));
        for (int i = 0; i < ms.length; i++) {
            long nextMs = i + 1 < ms.length ? ms[i + 1] : end;
            int nextChar = i + 1 < ms.length ? chars[i + 1] : text.length();
            if (now < nextMs) {
                float f = (now - ms[i]) / (float) Math.max(1, nextMs - ms[i]);
                return chars[i] + f * (nextChar - chars[i]);
            }
        }
        return text.length();
    }

    /**
     * Developer aid: writes the message for a seed, planet name spoken, to a file and prints its
     * transcript. Arguments: seed, "single" or "multi", output .wav.
     */
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        TransmissionMessage message = load("multi".equals(args[1]) ? MULTIPLAYER : SINGLEPLAYER, seed);
        preparing.get(seed).get();
        message.settleName();
        int split = message.slotFrame * message.format.getFrameSize();
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        all.write(message.pcm, 0, split);
        all.write(message.sentence);
        all.write(message.pcm, split, message.pcm.length - split);
        byte[] bytes = all.toByteArray();
        AudioSystem.write(new AudioInputStream(new java.io.ByteArrayInputStream(bytes), message.format, bytes.length / message.format.getFrameSize()),
                javax.sound.sampled.AudioFileFormat.Type.WAVE, new File(args[2]));
        System.out.println(message.slotName + "\n" + message.transcript);
    }
}
