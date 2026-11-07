import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tutorial's words (assets/text/tutorial_messages.txt, kept apart from the code so they can
 * be written and rewritten freely): Xenocorp's messages, the objectives on the HUD, and what the
 * results say. Each message is said in the Xenocorp voice from its recording
 * (assets/audio/tutorial/<key>, made by tools/record_tutorial.py) while that recording's
 * transcript is still the file's words; once they've been changed, until it's recorded again,
 * the new words scroll along without it.
 */
final class TutorialText {

    static final String FILE = GamePaths.HOME + "assets/text/tutorial_messages.txt";
    static final String RECORDINGS = GamePaths.HOME + "assets/audio/tutorial/";

    private final Map<String, String> entries;

    private TutorialText(Map<String, String> entries) {
        this.entries = entries;
    }

    /** The words as the file has them now (none, should it be missing: every entry then its own key). */
    static TutorialText load() {
        Map<String, String> entries = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(new File(FILE).toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf(" = ");
                if (eq < 0) continue;
                entries.put(t.substring(0, eq).trim(), t.substring(eq + 3).trim());
            }
        } catch (Exception e) {
            System.err.println("Tutorial words unavailable: " + e.getMessage());
        }
        return new TutorialText(entries);
    }

    /** An entry's words, each {name} in it filled in from vars (in pairs: name, value); the key itself if there's no such entry. */
    String get(String key, String... vars) {
        String text = entries.getOrDefault(key, key);
        for (int i = 0; i + 1 < vars.length; i += 2) text = text.replace("{" + vars[i] + "}", vars[i + 1]);
        return text;
    }

    boolean has(String key) {
        return entries.containsKey(key);
    }

    /**
     * A message, said from its recording if that's of these very words, otherwise its words
     * shown at a reading pace without the voice.
     */
    TransmissionMessage message(String key) {
        String text = get(key);
        String recording = RECORDINGS + key;
        try {
            File transcript = new File(recording + ".txt");
            if (transcript.exists() && new File(recording + ".wav").exists()) {
                String said = Files.readAllLines(transcript.toPath(), StandardCharsets.UTF_8).get(0).trim();
                if (said.equals(text)) {
                    TransmissionMessage recorded = TransmissionMessage.plain(recording);
                    if (recorded != null) return recorded;
                }
            }
        } catch (Exception e) {
            System.err.println("Tutorial recording unavailable: " + e.getMessage());
        }
        return TransmissionMessage.silent(text);
    }
}
