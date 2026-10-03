import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * Generates every texture for one world before the window's GL context needs them.
 *
 * Textures are written to a per-run folder that is wiped at the start of each launch, so
 * nothing carries over between worlds; the game then loads them through TextureLibrary
 * exactly as it used to load the hand-made assets. Generators run in parallel.
 */
public final class WorldArtGenerator {

    public static final String OUTPUT_DIR = RunFiles.WORLD_DIR;

    public static final String SOIL = "soil";
    public static final String SEA = "sea";
    public static final String SKY = "sky";
    public static final String SUN_GLOW = "sun_glow";
        public static final String GRASS_ATLAS = "grass_atlas";
        public static final String SOIL_REGIONS = "soil_regions";

    public static String leafName(int species) {
        return "leaf_species" + species;
    }

    public static String pathFor(String name) {
        return OUTPUT_DIR + "/" + name + ".png";
    }

    private final WorldPalette palette;
    private volatile BufferedImage[] leaves;
    private volatile BufferedImage sky, sunGlow, soil, sea;

    private WorldArtGenerator(WorldPalette palette) {
        this.palette = palette;
    }

    public static WorldArtGenerator generate(long worldSeed, int speciesCount,
                                             java.util.Map<String, Supplier<BufferedImage>> extraJobs,
                                             LoadingProgress progress) throws Exception {
        WorldArtGenerator art = new WorldArtGenerator(new WorldPalette(worldSeed));
        File dir = new File(OUTPUT_DIR);
        // Empty the folder rather than deleting it: on Windows a folder that another
        // program has open can't be removed, but its contents still can
        File[] previous = dir.listFiles();
        if (previous != null) for (File f : previous) deleteRecursively(f);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Could not create " + dir.getAbsolutePath());

        art.leaves = new BufferedImage[speciesCount];
        List<Supplier<BufferedImage>> jobs = new ArrayList<>();
        List<String> names = new ArrayList<>();

        names.add(SOIL);        jobs.add(() -> art.soil = ProceduralTextures.soil(worldSeed, art.palette));
        names.add(SEA);         jobs.add(() -> art.sea = ProceduralTextures.sea(worldSeed, art.palette));
        names.add(SKY);         jobs.add(() -> art.sky = ProceduralTextures.sky(worldSeed, art.palette));
        names.add(SUN_GLOW);    jobs.add(() -> art.sunGlow = ProceduralTextures.sunGlow(art.palette));
        names.add(GRASS_ATLAS); jobs.add(() -> ProceduralTextures.grassAtlas(worldSeed));
        for (int s = 0; s < speciesCount; s++) {
            final int species = s;
            names.add(leafName(s));
            jobs.add(() -> art.leaves[species] = ProceduralTextures.leaf(worldSeed * 1_000_003L + species));
        }
        // Per-nation architecture: walls, roofs and fences
        for (java.util.Map.Entry<String, Supplier<BufferedImage>> job : extraJobs.entrySet()) {
            names.add(job.getKey());
            jobs.add(job.getValue());
        }

        int threads = Math.max(1, Math.min(jobs.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "world-art");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger done = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                Supplier<BufferedImage> job = jobs.get(i);
                File out = new File(pathFor(names.get(i)));
                futures.add(pool.submit(() -> {
                    ImageIO.write(job.get(), "png", out);
                    progress.report(done.incrementAndGet() / (float) jobs.size());
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        return art;
    }

    public WorldPalette palette() { return palette; }
    public BufferedImage sky() { return sky; }
    public BufferedImage sunGlow() { return sunGlow; }
    public BufferedImage soil() { return soil; }
    public BufferedImage sea() { return sea; }
    public BufferedImage leaf(int species) { return leaves[species]; }
    public int speciesCount() { return leaves.length; }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }
}
