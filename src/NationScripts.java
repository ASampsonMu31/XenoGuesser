import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Which writing system each nation uses and which way it runs, decided once per world so
 * that signs, shop fronts and the print on packets all agree.
 */
public final class NationScripts {

    private final int[] direction;   // 0 left to right, 1 right to left, 2 top to bottom, 3 bottom to top
    private final int[] alphabet;    // which generated alphabet, from 1
    private final List<List<BufferedImage>> glyphCache = new ArrayList<>();

    public NationScripts(long worldSeed, int nations) {
        direction = new int[nations + 1];
        alphabet = new int[nations + 1];
        File[] folders = new File(RunFiles.ALPHABETS_DIR).listFiles(File::isDirectory);
        int available = folders != null ? folders.length : 0;
        Random rand = new Random(worldSeed + 999L);
        for (int n = 1; n <= nations; n++) {
            int d = rand.nextInt(4);
            // Developer aid: -Dxenoguesser.signdirection=0..3 gives every nation one writing direction
            if (System.getProperty("xenoguesser.signdirection") != null) d = Integer.getInteger("xenoguesser.signdirection", d);
            direction[n] = d;
            alphabet[n] = 1 + rand.nextInt(Math.max(1, available));
        }
        for (int n = 0; n <= nations; n++) glyphCache.add(null);
    }

    public int direction(int nationId) {
        return nationId > 0 && nationId < direction.length ? direction[nationId] : 0;
    }

    public int alphabet(int nationId) {
        return nationId > 0 && nationId < alphabet.length ? alphabet[nationId] : 1;
    }

    /** The nation's glyph images (dark ink on a light ground), in atlas order from glyph 0; empty if none. */
    public synchronized List<BufferedImage> glyphs(int nationId) {
        if (nationId <= 0 || nationId >= glyphCache.size()) return List.of();
        List<BufferedImage> cached = glyphCache.get(nationId);
        if (cached != null) return cached;
        List<BufferedImage> images = new ArrayList<>();
        File dir = new File(RunFiles.ALPHABETS_DIR, "alphabet" + alphabet(nationId));
        for (int i = 0; ; i++) {
            File file = new File(dir, "glyph_" + i + ".png");
            if (!file.exists()) break;
            try {
                images.add(ImageIO.read(file));
            } catch (Exception e) {
                break;
            }
        }
        glyphCache.set(nationId, images);
        return images;
    }
}
