import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Who the player is (M58): the username they chose (checked by NameCheck) and their suit's
 * colour, remembered between games with the settings, and the name said in Xenocorp's voice
 * (PlanetVoice, from Pronunciation's phonemes), kept as a recording of its own beside the crash
 * log so it's ready whenever a message says it. The name stands wherever "Employee 119" used to:
 * in the messages, spliced into the recordings (see TransmissionMessage), and on every screen.
 *
 * Developer aid: -Dxenoguesser.username=Name and -Dxenoguesser.suit=red set them for this run
 * only (two local windows share one store, so each can be someone).
 */
public final class Profile {

    // The name the developer runs that never ask use, should none have been chosen
    private static final String STAND_IN = "Guesser";

    private static volatile String name = Dev.property("xenoguesser.username") != null
            ? Dev.property("xenoguesser.username") : GameSettings.STORE.get("username", null);
    private static volatile SuitColour suit = parseSuit(Dev.property("xenoguesser.suit") != null
            ? Dev.property("xenoguesser.suit") : GameSettings.STORE.get("suitColour", SuitColour.BLUE.name()));

    // Names being said, or said, by name (this player's and partners')
    private static final Map<String, CompletableFuture<PlanetVoice.Speech>> spoken = new ConcurrentHashMap<>();

    private Profile() {
    }

    private static SuitColour parseSuit(String text) {
        try {
            return SuitColour.valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return SuitColour.BLUE;
        }
    }

    /** Whether a name has been chosen (if not, the game asks for one as it starts). */
    public static boolean hasName() {
        return name != null && !name.isBlank();
    }

    /** The player's name (a stand-in for developer runs that skip asking). */
    public static String name() {
        return hasName() ? name : STAND_IN;
    }

    public static SuitColour suit() {
        return suit;
    }

    /**
     * Keeps a checked name and a suit colour; a new name is said in the background and its
     * recording kept, so the messages have it.
     */
    public static void save(String newName, SuitColour newSuit) {
        name = newName;
        suit = newSuit;
        try {
            GameSettings.STORE.put("username", newName);
            GameSettings.STORE.put("suitColour", newSuit.name());
        } catch (Exception e) {
            // Settings that can't be saved still apply for this game
        }
        spoken(newName);
    }

    /** Starts saying this player's name if it isn't already, as the game starts. */
    public static void prepare() {
        if (hasName()) spoken(name);
    }

    /**
     * A name as said in Xenocorp's voice (this player's, or a partner's when they join): from
     * its recording if one was kept, or else said now and kept. Completes with null if the
     * voice can't be run.
     */
    public static CompletableFuture<PlanetVoice.Speech> spoken(String who) {
        return spoken.computeIfAbsent(who, n -> {
            CompletableFuture<PlanetVoice.Speech> speech = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                try {
                    PlanetVoice.Speech said = read(clip(n));
                    if (said == null) {
                        said = PlanetVoice.say(Pronunciation.phonemes(n) + ",");
                        if (said != null) write(said, clip(n));
                    }
                    speech.complete(said);
                } catch (Throwable e) {
                    System.err.println("Name unsaid: " + e);
                    speech.complete(null);
                }
            }, "name-voice");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
            return speech;
        });
    }

    /** Where a name's recording is kept: names are letters, digits, _ and - only, so safe as file names. */
    private static File clip(String who) {
        String local = System.getenv("LOCALAPPDATA");
        File base = local != null ? new File(local, "XenoGuesser") : new File(System.getProperty("user.home"), ".xenoguesser");
        return new File(new File(base, "names"), who.toLowerCase(java.util.Locale.ROOT) + ".wav");
    }

    private static PlanetVoice.Speech read(File file) {
        if (!file.isFile()) return null;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
            byte[] bytes = in.readAllBytes();
            float[] audio = new float[bytes.length / 2];
            for (int i = 0; i < audio.length; i++) audio[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8)) / 32768f;
            return new PlanetVoice.Speech(audio, (int) in.getFormat().getSampleRate());
        } catch (Exception e) {
            return null;
        }
    }

    /** As 16-bit little-endian mono, as the messages' recordings are. */
    private static void write(PlanetVoice.Speech said, File file) {
        try {
            file.getParentFile().mkdirs();
            byte[] bytes = new byte[said.audio().length * 2];
            for (int i = 0; i < said.audio().length; i++) {
                int v = Math.round(Math.max(-1f, Math.min(1f, said.audio()[i])) * 32767f);
                bytes[2 * i] = (byte) v;
                bytes[2 * i + 1] = (byte) (v >> 8);
            }
            AudioFormat format = new AudioFormat(said.sampleRate(), 16, 1, true, false);
            AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(bytes), format, said.audio().length),
                    AudioFileFormat.Type.WAVE, file);
        } catch (Exception e) {
            System.err.println("Name recording not kept: " + e.getMessage());
        }
    }
}
