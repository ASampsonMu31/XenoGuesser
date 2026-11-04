import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * What the tutorial's planet (always the same: TutorialPlan.SEED) takes longest to make, made
 * once and kept with the game (assets/tutorial_cache) so loading it is mostly a matter of
 * reading files: its writing systems (the glyph model, the slowest single step), its textures,
 * its landmarks' and typical houses' photographs (taken in the world itself, a few seconds
 * each) and every heatmap the map can show (each worked out over the whole planet). The
 * planet's layout itself (its land, rivers, nations, roads and houses) is still worked out as
 * it always is: everything else reads it.
 *
 * The cache is written by a tutorial run with -Dxenoguesser.tutorialcache=write (see the
 * update-tutorial skill), which makes everything afresh and saves it once the world's ready.
 * Kept with it is the world's key (its size, sea level, and what's built on it, see key): a
 * cache whose key isn't the world's now (the game's generation has changed since it was
 * written) is left alone and everything made as usual, so it can be slower but never wrong.
 * -Dxenoguesser.tutorialcache=off ignores it (to compare with what's made afresh).
 */
final class TutorialCache {

    static final String DIR = GamePaths.HOME + "assets/tutorial_cache";
    // (bumped whenever what's kept, or how, changes: an older cache is then left alone)
    private static final int FORMAT = 1;

    private static final String ALPHABETS = "alphabets", ART = "art", PHOTOS = "photos", OVERLAYS = "overlays";

    // Whether this tutorial's world matched the cache (worked out once it's laid out; null before)
    private static volatile Boolean matches;

    private TutorialCache() {
    }

    private static String mode() {
        return Dev.property("xenoguesser.tutorialcache");
    }

    /** Whether this run writes the cache (made afresh, saved once the world's ready). */
    static boolean writing() {
        return Tutorial.active && "write".equals(mode());
    }

    /** Whether the cache may be read at all this run (the tutorial, not writing it, not turned off). */
    private static boolean readable() {
        return Tutorial.active && mode() == null && new File(DIR, "key.txt").exists();
    }

    /** Whether the parts made from the world's layout may be read: the cache is of this very world. */
    static boolean usable() {
        Boolean m = matches;
        return readable() && m != null && m;
    }

    // ------------------------------------------------------------------ the world's key

    /**
     * What tells this world from any other the generation might make of the same seed: its
     * size and sea level, how much is built where, its landmarks, nations, species and
     * textures. (Not a proof, but any change to the generation that would change what's cached
     * changes one of these.)
     */
    static String key(XenoGuesser_GLEventListener g) {
        StringBuilder b = new StringBuilder();
        b.append("format ").append(FORMAT).append('\n');
        b.append("seed ").append(g.worldSeed).append('\n');
        b.append(String.format(Locale.ROOT, "sea %.4f width %.2f%n", g.seaLevelHeight, g.TOTAL_REGION_WIDTH));
        b.append("towns ").append(g.settlementManager.getSettlements().size()).append(" houses ").append(g.infraManager.houses.size())
                .append(" roads ").append(g.infraManager.landSegments.size()).append(" nations ").append(g.infraManager.roadLineStyles.size()).append('\n');
        long houseSum = 0;
        for (House h : g.infraManager.houses) {
            SpherePosition o = h.frame.origin();
            houseSum = houseSum * 31 + Math.round(o.x()) * 7 + Math.round(o.z()) * 3 + Math.round(h.width * 10) + h.nationId;
        }
        b.append("houses at ").append(Long.toHexString(houseSum)).append("\n");
        if (g.worldNoise.rivers != null) b.append("rivers ").append(g.worldNoise.rivers.reaches().size()).append(" lakes ").append(g.worldNoise.rivers.lakes().size()).append("\n");
        for (Landmark lm : g.infraManager.landmarks().all()) b.append("landmark ").append(lm.kind).append(' ').append(lm.name).append('\n');
        for (int s = 0; s < g.NUM_SPECIES; s++) b.append("plant ").append(g.floraName(s)).append('\n');
        for (int i = 0; i < g.organismManager.speciesCount(); i++) b.append("animal ").append(g.organismManager.speciesName(i)).append('\n');
        for (String job : new java.util.TreeSet<>(g.getNationTextureJobs().keySet())) b.append("texture ").append(job).append('\n');
        return b.toString();
    }

    /** Once the world's laid out: whether the cache is of it (logged, and why not when it isn't). */
    static void check(XenoGuesser_GLEventListener g) {
        matches = null;
        if (!readable()) return;
        try {
            String kept = Files.readString(new File(DIR, "key.txt").toPath(), StandardCharsets.UTF_8);
            boolean same = kept.equals(key(g));
            matches = same;
            System.out.println(same ? "[TUTORIAL] Loading from the cache" : "[TUTORIAL] The cache is of another world (the generation has changed): making everything afresh");
        } catch (IOException e) {
            matches = false;
        }
    }

    // ------------------------------------------------------------------ the writing systems

    /**
     * The tutorial's writing systems put in place from the cache instead of being made (they're
     * read before the world's laid out, so they're taken whenever there are any: the world is
     * made from them, and its key is checked after). False if there are none to take.
     */
    static boolean restoreAlphabets() {
        if (!readable()) return false;
        File from = new File(DIR, ALPHABETS);
        if (!from.isDirectory()) return false;
        try {
            copyTree(from.toPath(), Path.of(RunFiles.ALPHABETS_DIR), true);
            System.out.println("[TUTORIAL] Writing systems from the cache");
            return true;
        } catch (IOException e) {
            System.err.println("[TUTORIAL] Couldn't take the writing systems from the cache: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------ the textures

    /** The world's textures from the cache, every one the world wants there; null to make them as usual. */
    static WorldArtGenerator restoreArt(XenoGuesser_GLEventListener g) {
        if (!usable()) return null;
        File from = new File(DIR, ART);
        if (!from.isDirectory()) return null;
        try {
            WorldArtGenerator art = WorldArtGenerator.fromFiles(from, g.worldSeed, g.getSpeciesCount(), g.getNationTextureJobs().keySet());
            if (art != null) System.out.println("[TUTORIAL] Textures from the cache");
            return art;
        } catch (IOException e) {
            System.err.println("[TUTORIAL] Couldn't take the textures from the cache: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------ the photographs

    /**
     * Every landmark's photograph and every typical house's (by its architecture map key) from
     * the cache, given to them; false (nothing given) unless every landmark's is there.
     */
    static boolean restorePhotos(XenoGuesser_GLEventListener g) {
        if (!usable()) return false;
        File dir = new File(DIR, PHOTOS);
        List<Landmark> all = g.infraManager.landmarks().all();
        BufferedImage[] landmarks = new BufferedImage[all.size()];
        Map<Integer, BufferedImage> houses = new java.util.HashMap<>();
        try {
            for (int i = 0; i < all.size(); i++) {
                File f = new File(dir, "landmark_" + i + ".png");
                if (!f.exists()) return false;
                landmarks[i] = rgb(ImageIO.read(f));
            }
            File[] files = dir.listFiles((d, name) -> name.startsWith("house_") && name.endsWith(".png"));
            if (files != null) for (File f : files) {
                houses.put(Integer.parseInt(f.getName().substring(6, f.getName().length() - 4)), rgb(ImageIO.read(f)));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[TUTORIAL] Couldn't take the photographs from the cache: " + e);
            return false;
        }
        for (int i = 0; i < all.size(); i++) all.get(i).picture = landmarks[i];
        g.mapOverlays.architecturePhotos.putAll(houses);
        System.out.println("[TUTORIAL] " + all.size() + " landmarks' and " + houses.size() + " houses' photographs from the cache");
        return true;
    }

    // ------------------------------------------------------------------ the heatmaps

    /** An overlay's file name: its factor, and its species for the plants' and animals'. */
    private static String overlayName(MapOverlay o) {
        return o.factor().name() + (o.species() >= 0 ? "_" + o.species() : "");
    }

    /** An overlay as worked out (a pixel a chunk, see MapOverlays.overlayImage) from the cache, or null to work it out. */
    static BufferedImage rawOverlay(MapOverlay o) {
        if (!usable()) return null;
        File f = new File(new File(DIR, OVERLAYS), overlayName(o) + ".png");
        if (!f.exists()) return null;
        try {
            BufferedImage read = ImageIO.read(f);
            if (read == null) return null;
            BufferedImage argb = new BufferedImage(read.getWidth(), read.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(read, 0, 0, null);
            return argb;
        } catch (IOException e) {
            return null;
        }
    }

    /** Each heatmap's lowest and highest values on the land (see MapOverlays.heatmapRange) from the cache; empty to work them out. */
    static Map<String, float[]> ranges() {
        Map<String, float[]> ranges = new java.util.HashMap<>();
        if (!usable()) return ranges;
        try {
            for (String line : Files.readAllLines(new File(new File(DIR, OVERLAYS), "ranges.txt").toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                if (p.length == 3) ranges.put(p[0], new float[] { Float.parseFloat(p[1]), Float.parseFloat(p[2]) });
            }
        } catch (IOException | RuntimeException e) {
            ranges.clear();
        }
        return ranges;
    }

    static String rangeName(MapOverlay o) {
        return overlayName(o);
    }

    // ------------------------------------------------------------------ writing it

    /**
     * Saves everything the cache keeps, as this run made it (the tutorial with
     * -Dxenoguesser.tutorialcache=write, once its world's ready): written beside the old cache,
     * then put in its place, so a run that fails halfway leaves the old one whole.
     */
    static void write(XenoGuesser_GLEventListener g) throws IOException {
        Path target = Path.of(DIR), fresh = Path.of(DIR + ".new");
        deleteTree(fresh);
        Files.createDirectories(fresh);
        copyTree(Path.of(RunFiles.ALPHABETS_DIR), fresh.resolve(ALPHABETS), false);
        copyTree(Path.of(RunFiles.WORLD_DIR), fresh.resolve(ART), false);
        Path photos = Files.createDirectories(fresh.resolve(PHOTOS));
        List<Landmark> all = g.infraManager.landmarks().all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).picture != null) ImageIO.write(all.get(i).picture, "png", photos.resolve("landmark_" + i + ".png").toFile());
        }
        for (Map.Entry<Integer, BufferedImage> e : new java.util.TreeMap<>(g.mapOverlays.architecturePhotos).entrySet()) {
            ImageIO.write(e.getValue(), "png", photos.resolve("house_" + e.getKey() + ".png").toFile());
        }
        Path overlays = Files.createDirectories(fresh.resolve(OVERLAYS));
        List<String> ranges = new ArrayList<>();
        int written = 0;
        for (MapOverlay o : g.mapOverlays.allOverlays()) {
            BufferedImage raw = g.mapOverlays.rawOverlay(o);
            if (raw != null) {
                ImageIO.write(raw, "png", overlays.resolve(overlayName(o) + ".png").toFile());
                written++;
            }
            float[] range = g.mapOverlays.knownRange(o);
            if (range != null) ranges.add(String.format(Locale.ROOT, "%s %s %s", overlayName(o), Float.toString(range[0]), Float.toString(range[1])));
        }
        Files.write(overlays.resolve("ranges.txt"), ranges, StandardCharsets.UTF_8);
        Files.writeString(fresh.resolve("key.txt"), key(g), StandardCharsets.UTF_8);
        deleteTree(target);
        Files.move(fresh, target, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("[TUTORIAL] Cache written to " + target + ": " + all.size() + " landmarks, "
                + g.mapOverlays.architecturePhotos.size() + " houses, " + written + " heatmaps");
    }

    /** An image as plain RGB ints (as the game's own pictures are). */
    private static BufferedImage rgb(BufferedImage image) {
        if (image == null) throw new IllegalStateException("unreadable picture");
        if (image.getType() == BufferedImage.TYPE_INT_RGB) return image;
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(image, 0, 0, null);
        return out;
    }

    /** Copies a folder's files (and folders) into another, emptying it first if asked. */
    static void copyTree(Path from, Path to, boolean emptyFirst) throws IOException {
        if (emptyFirst && Files.isDirectory(to)) {
            File[] old = to.toFile().listFiles();
            if (old != null) for (File f : old) deleteTree(f.toPath());
        }
        Files.createDirectories(to);
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path out = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(out);
                else Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            java.util.Collections.reverse(all);
            for (Path q : all) Files.delete(q);
        }
    }
}
