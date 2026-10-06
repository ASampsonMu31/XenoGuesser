import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The voice the Xenocorp message is read in (a Piper neural voice, "bryce"), run here so it
 * can say things only known once the world is made, such as the planet's name. It reads
 * phonemes (eSpeak's IPA, as in its config), not text, so nothing needs working out how to say.
 * The model's weights are stored at half precision to keep it small; it runs at full.
 */
public final class PlanetVoice {

    public static final String MODEL = "assets/audio/voice/bryce.onnx";
    public static final String CONFIG = MODEL + ".json";

    // As the message's recording was made: quicker than the voice's usual pace, a little less varied
    private static final float NOISE = 0.6f, LENGTH = 0.6f, NOISE_W = 0.7f;

    private final Map<Integer, Integer> ids = new HashMap<>();
    public final int sampleRate;

    private PlanetVoice() throws Exception {
        try (FileReader in = new FileReader(CONFIG, StandardCharsets.UTF_8)) {
            JsonObject config = JsonParser.parseReader(in).getAsJsonObject();
            sampleRate = config.getAsJsonObject("audio").get("sample_rate").getAsInt();
            JsonObject map = config.getAsJsonObject("phoneme_id_map");
            for (String phoneme : map.keySet()) {
                if (phoneme.codePointCount(0, phoneme.length()) == 1) ids.put(phoneme.codePointAt(0), map.getAsJsonArray(phoneme).get(0).getAsInt());
            }
        }
    }

    /** Says the phonemes: the sound, at sampleRate, peaking at 1; null if the voice can't be run. */
    public static Speech say(String phonemes) {
        try {
            PlanetVoice voice = new PlanetVoice();
            return new Speech(voice.run(phonemes), voice.sampleRate);
        } catch (Throwable e) {
            System.err.println("Voice unavailable: " + e);
            return null;
        }
    }

    public record Speech(float[] audio, int sampleRate) {
    }

    private float[] run(String phonemes) throws Exception {
        // Start, then each phoneme followed by a pad, then end (as Piper does)
        List<Long> sequence = new ArrayList<>();
        sequence.add((long) ids.get((int) '^'));
        sequence.add((long) ids.get((int) '_'));
        phonemes.codePoints().forEach(c -> {
            Integer id = ids.get(c);
            if (id == null) return;
            sequence.add((long) id);
            sequence.add((long) ids.get((int) '_'));
        });
        sequence.add((long) ids.get((int) '$'));
        long[] input = sequence.stream().mapToLong(Long::longValue).toArray();
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession session = env.createSession(MODEL, new OrtSession.SessionOptions());
             OnnxTensor in = OnnxTensor.createTensor(env, new long[][] { input });
             OnnxTensor lengths = OnnxTensor.createTensor(env, new long[] { input.length });
             OnnxTensor scales = OnnxTensor.createTensor(env, new float[] { NOISE, LENGTH, NOISE_W });
             OrtSession.Result result = session.run(Map.of("input", in, "input_lengths", lengths, "scales", scales))) {
            FloatBuffer out = ((OnnxTensor) result.get(0)).getFloatBuffer();
            float[] audio = new float[out.remaining()];
            out.get(audio);
            // Brought up to full scale, as Piper does
            float peak = 1e-8f;
            for (float v : audio) peak = Math.max(peak, Math.abs(v));
            for (int i = 0; i < audio.length; i++) audio[i] /= peak;
            return audio;
        }
    }
}
