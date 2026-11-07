import java.io.File;
import java.util.List;
import java.util.Random;
import javax.swing.SwingUtilities;

/**
 * Finds the tutorial's rounds: makes its planet (TutorialPlan.SEED) exactly as the game does,
 * without GL, as RegressionCheck does, looks over it for each exercise's place (TutorialSites)
 * and writes them to assets/text/tutorial_rounds.txt (or the file given), each with how much of
 * the land its exercise passes on. Run again whenever the game's generation changes (see the
 * update-tutorial skill).
 *
 *   java -Xmx10g -cp "target/classes;<the game's classpath>" TutorialFinder [out file]
 */
public final class TutorialFinder {

    public static void main(String[] args) throws Exception {
        long seed = TutorialPlan.SEED;
        long started = System.currentTimeMillis();
        RunFiles.prepare();
        // As XenoGuesser.generateWorld makes the world: from the cache's writing systems if
        // there are any (so the places are found on just the world the tutorial loads)
        java.nio.file.Path cachedAlphabets = java.nio.file.Path.of(TutorialCache.DIR, "alphabets");
        if (java.nio.file.Files.isDirectory(cachedAlphabets)) TutorialCache.copyTree(cachedAlphabets, java.nio.file.Path.of(RunFiles.ALPHABETS_DIR), true);
        else WritingSystems.generate(seed).join();
        PerlinNoise worldNoise = new PerlinNoise(seed);
        float physicalChunkSize = 100.0f;
        float totalRegionWidth = 150_000f * (0.92f + 0.16f * new Random(seed * 104_729L + 17L).nextFloat());
        Planet.setWidth(totalRegionWidth);
        float sea = XenoGuesser_GLEventListener.precalculateSeaLevel(seed, worldNoise);
        worldNoise.landforms = Landforms.shape(worldNoise, seed, sea);
        MapProjection.chooseJoin(d -> TerrainMesh.getLayeredHeight(d, worldNoise), sea);
        RegionalGenerationManager climate = new RegionalGenerationManager(seed, sea);
        climate.precalculateWaterDistanceField(worldNoise);
        climate.setMoistureMap(climate.createMoistureMap());
        worldNoise.rivers = Rivers.generate(worldNoise, sea, climate.rainfallMap::evaluate);
        climate.addRivers(worldNoise.rivers);
        worldNoise.climate = climate;
        Camera camera = new Camera(Planet.position(0.0, 0.0, sea + 20.0), sea);
        XenoGuesser_GLEventListener listener = new XenoGuesser_GLEventListener(camera, new MyKeyboardInput(), worldNoise, sea,
                seed, totalRegionWidth, physicalChunkSize, false);
        WorldArtGenerator art = WorldArtGenerator.generate(seed, listener.getSpeciesCount(), listener.getNationTextureJobs(), new LoadingProgress());
        listener.setWorldArt(art);
        listener.buildSoilRegions(art.palette());
        MapPanel map = new MapPanel(750, 750, totalRegionWidth, sea, worldNoise, physicalChunkSize, listener);
        SwingUtilities.invokeAndWait(() -> listener.setMinimap(map));
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("birds-eye-map-renderer")) t.join();
        SwingUtilities.invokeAndWait(() -> { });
        System.out.printf("[TUTORIAL] World made in %d s%n", (System.currentTimeMillis() - started) / 1000);

        // (whether the cache is of this very world: if not, it's to be written again, see the skill)
        File keyFile = new File(TutorialCache.DIR, "key.txt");
        boolean cacheMatches = keyFile.exists() && java.nio.file.Files.readString(keyFile.toPath()).equals(TutorialCache.key(listener));
        System.out.println(cacheMatches ? "[TUTORIAL] The cache is of this world" : "[TUTORIAL] CACHE STALE OR MISSING: write it again (tools/write-tutorial-cache.sh)");
        TutorialSites finder = new TutorialSites(listener);
        List<TutorialPlan.Site> sites = finder.findAll();
        File out = new File(args.length > 0 ? args[0] : TutorialPlan.FILE);
        TutorialPlan.write(out, sea, totalRegionWidth, sites, finder.notes);
        System.out.println("[TUTORIAL] Wrote " + sites.size() + " of " + TutorialPlan.Stage.values().length + " rounds to " + out);
        for (String note : finder.notes) System.out.println("[TUTORIAL]   " + note);
        System.exit(sites.size() == TutorialPlan.Stage.values().length ? 0 : 1);
    }
}
