import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Regression check for refactoring: makes seed 123's world exactly as the game does (no GL),
 * then fingerprints what came of it (see Fingerprint): the rivers, the textures, the maps,
 * every infrastructure cell round a few towns, lookups along grids, viewpoints, flora and
 * grass cells, people, vehicles and animals simulated for a while, and finally everything
 * reachable from the game's listener. A refactor that changes no behaviour changes none of them.
 *
 * Run after compiling (a couple of minutes, about 6 GB of memory):
 *   java -Xmx10g -cp "target/classes;<the game's classpath>" RegressionCheck write   (records the golden file)
 *   java -Xmx10g -cp ... RegressionCheck                                             (compares; exit code 1 on a difference)
 * The golden file is checks/regression-fingerprints.txt; -Dregression.out=other.txt writes elsewhere.
 * With "breakdown", the listener's per-field hashes go to checks/regression-breakdown.txt, to find what differs.
 */
public final class RegressionCheck {

    private static final long SEED = 123L;
    private static final Path GOLDEN = Path.of("checks", "regression-fingerprints.txt");

    // Each fingerprint by name, read (and so worked out) only once everything has run
    private final Map<String, Object> results = new LinkedHashMap<>();
    private boolean breakdown;

    public static void main(String[] args) throws Exception {
        boolean write = args.length > 0 && args[0].equals("write");
        boolean breakdown = Arrays.asList(args).contains("breakdown");
        RegressionCheck check = new RegressionCheck();
        check.breakdown = breakdown;
        check.run();
        List<String> lines = new ArrayList<>();
        check.results.forEach((k, v) -> {
            lines.add(k + " " + v);
            System.out.println("[REGRESSION] " + k + " " + v);
        });
        Path out = System.getProperty("regression.out") != null ? Path.of(System.getProperty("regression.out")) : GOLDEN;
        if (write) {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.write(out, lines, StandardCharsets.UTF_8);
            System.out.println("Wrote " + lines.size() + " fingerprints to " + out);
            System.exit(0);
        }
        List<String> golden = Files.readAllLines(GOLDEN, StandardCharsets.UTF_8);
        int differ = 0;
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : golden) expected.put(line.substring(0, line.indexOf(' ')), line.substring(line.indexOf(' ') + 1));
        for (Map.Entry<String, Object> e : check.results.entrySet()) {
            String want = expected.remove(e.getKey());
            if (!e.getValue().toString().equals(want)) {
                differ++;
                System.out.println("DIFFERS: " + e.getKey() + " was " + want + ", now " + e.getValue());
            }
        }
        for (String missing : expected.keySet()) {
            differ++;
            System.out.println("MISSING: " + missing);
        }
        System.out.printf("%d fingerprints, %d differ%n", check.results.size(), differ);
        System.exit(differ > 0 ? 1 : 0);
    }

    private void put(String name, Fingerprint fingerprint) {
        results.put(name, fingerprint);
    }

    private void putDeep(String name, Object value) {
        put(name, new Fingerprint().addDeep(value));
    }

    private XenoGuesser_GLEventListener run() throws Exception {
        RunFiles.prepare();
        // As XenoGuesser.generateWorld makes the world
        WritingSystems.generate(SEED).join();
        PerlinNoise worldNoise = new PerlinNoise(SEED);
        float physicalChunkSize = 100.0f;
        float totalRegionWidth = 150_000f * (0.92f + 0.16f * new Random(SEED * 104_729L + 17L).nextFloat());
        Planet.setWidth(totalRegionWidth);
        float sea = XenoGuesser_GLEventListener.precalculateSeaLevel(SEED, worldNoise);
        float joinLand = MapProjection.chooseJoin(d -> TerrainMesh.getLayeredHeight(d, worldNoise), sea);
        put("world.basics", new Fingerprint().add(totalRegionWidth).add(sea).add(joinLand));
        RegionalGenerationManager climate = new RegionalGenerationManager(SEED, sea);
        climate.precalculateWaterDistanceField(worldNoise);
        climate.setMoistureMap(climate.createWaterPreference(0f, 8000f));
        worldNoise.rivers = Rivers.generate(worldNoise, sea, climate.rainfallMap::evaluate);
        climate.addRivers(worldNoise.rivers);
        worldNoise.climate = climate;
        putDeep("rivers", worldNoise.rivers);
        put("globe", new Fingerprint().addDeep(PlanetGlobeTexture.make(worldNoise, sea, SEED)));
        put("alphabets", filesFingerprint(new File(RunFiles.ALPHABETS_DIR)));

        Camera camera = new Camera(Planet.position(0.0, 0.0, sea + 20.0), sea);
        XenoGuesser_GLEventListener listener = new XenoGuesser_GLEventListener(camera, new MyKeyboardInput(), worldNoise, sea,
                SEED, totalRegionWidth, physicalChunkSize, false);
        WorldArtGenerator art = WorldArtGenerator.generate(SEED, listener.getSpeciesCount(), listener.getNationTextureJobs(),
                new LoadingProgress());
        listener.setWorldArt(art);
        listener.buildSoilRegions(art.palette());
        put("art", filesFingerprint(new File(WorldArtGenerator.OUTPUT_DIR)));
        MapPanel map = new MapPanel(750, 750, totalRegionWidth, sea, worldNoise, physicalChunkSize, listener);
        SwingUtilities.invokeAndWait(() -> listener.setMinimap(map));
        // The bird's-eye maps, their overlays and the landmarks' pictures, made on their own thread
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("birds-eye-map-renderer")) t.join();
        SwingUtilities.invokeAndWait(() -> { });

        InfrastructureManager infra = (InfrastructureManager) field(listener, "infraManager");
        SettlementManager settlements = (SettlementManager) field(listener, "settlementManager");
        List<SettlementManager.Settlement> towns = settlements.getSettlements();
        // The largest town, a middling one and a small one, and somewhere in the country
        List<SpherePosition> places = new ArrayList<>();
        places.add(towns.get(0).centre);
        places.add(towns.get(towns.size() / 3).centre);
        places.add(towns.get(towns.size() - 1).centre);
        places.add(SphereMath.move(towns.get(1).centre, 1.0, 4000.0));
        GeodesicChunkGrid batchGrid = new GeodesicChunkGrid(InfrastructureManager.BATCH_LEVEL);
        for (int t = 0; t < places.size(); t++) {
            SpherePosition centre = places.get(t);
            Fingerprint cells = new Fingerprint();
            for (GeodesicChunkGrid.Cell cell : batchGrid.cellsWithin(SphereMath.direction(centre), 1100.0 / Planet.radius())) {
                List<InfrastructureObject> built = infra.generateForCell(cell.id(), sea, worldNoise);
                cells.add(cell.id()).add(built.size()).addDeep(built);
            }
            put("infra.cells." + t, cells);
            Fingerprint lookups = new Fingerprint();
            SphereFrame frame = SphereFrame.at(SphereMath.atAltitude(centre, 0.0));
            for (int i = -30; i <= 30; i++) {
                for (int j = -30; j <= 30; j++) {
                    SpherePosition p = SphereMath.atAltitude(frame.toSphere(i * 9.7, 0.0, j * 9.7), 0.0);
                    lookups.add(infra.isRoadLocation(p, 0f)).add(infra.isRoadLocation(p, 3f)).add(infra.isBuildingLocation(p, 0f))
                            .add(infra.isBuildingLocation(p, 2f)).add(infra.isClearedForRoad(p, 11f, 3f)).add(infra.walkingSurfaceY(p))
                            .add(infra.walkingSurfaceY(p, 5f)).add(infra.bridgeDeckY(p));
                }
            }
            put("infra.lookups." + t, lookups);
            Fingerprint obstacles = new Fingerprint();
            infra.obstaclesNear(centre, 400.0, new GeodesicCollision.Sink() {
                public void segment(SpherePosition a, SpherePosition b, double halfWidth, double top) {
                    obstacles.addDeep(a).addDeep(b).add(halfWidth).add(top);
                }

                public void circle(SpherePosition c, double radius, double top) {
                    obstacles.addDeep(c).add(radius).add(top);
                }

                public void segment(SpherePosition a, SpherePosition b, double halfWidth, double top, double bottom) {
                    obstacles.addDeep(a).addDeep(b).add(halfWidth).add(bottom).add(top);
                }
            });
            put("infra.obstacles." + t, obstacles);
            List<InfrastructureManager.Doorway> doors = infra.doorwaysNear(centre, 2000f);
            Fingerprint doorways = new Fingerprint().add(doors.size());
            for (InfrastructureManager.Doorway door : doors) doorways.addDeep(door);
            for (int k = 0; k + 1 < doors.size() && k < 40; k += 2) {
                doorways.addDeep(infra.streetWalk(doors.get(k).path, doors.get(k).kerb, doors.get(k + 1).kerb));
            }
            put("infra.doorways." + t, doorways);
            put("infra.stop." + t, new Fingerprint().addDeep(infra.borders().borderStopAhead(centre, 0.3, 200f)));
        }
        put("infra.borders", new Fingerprint().addDeep(infra.borders().borderGuards()).addDeep(infra.borders().borderLights()));
        Fingerprint views = new Fingerprint();
        views.addDeep(infra.viewpoints().randomHouseViewpoint(new Random(1))).addDeep(infra.viewpoints().ornateHouseViewpoint(new Random(1)))
                .addDeep(infra.viewpoints().towerViewpoint(new Random(1))).addDeep(infra.viewpoints().landmarkViewpoint(new Random(1)))
                .addDeep(infra.randomRoadPoint(new Random(1))).addDeep(infra.viewpoints().poleDropViewpoint(new Random(1)))
                .addDeep(infra.viewpoints().poleViewpoint(new Random(1))).addDeep(infra.viewpoints().shopViewpoint(new Random(1)))
                .addDeep(infra.viewpoints().raisedRoadViewpoint(new Random(1))).addDeep(infra.viewpoints().trackViewpoint(new Random(1)))
                .addDeep(infra.viewpoints().bridgeViewpoint(new Random(1), false)).addDeep(infra.viewpoints().bridgeViewpoint(new Random(1), true))
                .addDeep(infra.viewpoints().bollardViewpoint(new Random(1))).addDeep(infra.viewpoints().junctionViewpoint(new Random(1)));
        Random sites = new Random(5);
        for (int k = 0; k < 40; k++) views.addDeep(infra.landingSite(sites, 6f, 60f, p -> TerrainMesh.getLayeredHeight(p, worldNoise) > sea));
        put("infra.viewpoints", views);
        Fingerprint buildings = new Fingerprint();
        infra.forEachBuilding((frame, outline, nation) -> buildings.addDeep(frame).addDeep(outline).add(nation));
        infra.forEachRoadPath(path -> buildings.addDeep(path));
        put("infra.buildings", buildings);

        // Plants and grass, cell by cell round each place
        Method flora = method(listener, "buildFloraCell", long.class), grass = method(listener, "buildGrassCell", long.class);
        GeodesicChunkGrid vegetation = new GeodesicChunkGrid(7);
        for (int t = 0; t < places.size(); t++) {
            Fingerprint plants = new Fingerprint();
            for (GeodesicChunkGrid.Cell cell : vegetation.cellsWithin(SphereMath.direction(places.get(t)), 600.0 / Planet.radius())) {
                plants.add(cell.id()).addDeep(flora.invoke(listener, cell.id())).addDeep(grass.invoke(listener, cell.id()));
            }
            put("vegetation." + t, plants);
        }

        // Animals round the first herd
        SpherePosition town = places.get(0);
        OrganismManager organisms = (OrganismManager) field(listener, "organismManager");
        Fingerprint animals = new Fingerprint().addDeep(organisms.herdViewpoint(new Random(1)))
                .addDeep(organisms.showcaseViewpoint(new Random(1))).addDeep(organisms.zooViewpoint(new Random(1)));
        SpherePosition herd = organisms.nearestHerd(town);
        if (herd != null) {
            for (int frame = 0; frame < 300; frame++) organisms.update(0.05f, herd, frame * 0.05);
            animals.addDeep(organisms);
        }
        put("animals", animals);

        // Everything the listener holds (once the sign maps drawn in the background are done),
        // taken now: the people below move by identity hashes and threads, differently each run
        ExecutorService artist = (ExecutorService) staticField(InfrastructureManager.class, "SIGN_MAP_ARTIST");
        artist.submit(() -> { }).get();
        for (Map.Entry<String, Object> e : results.entrySet()) e.setValue(e.getValue().toString());
        results.put("map", new Fingerprint().addDeep(map).toString());
        results.put("listener", new Fingerprint().addDeep(listener).toString());
        if (breakdown) Files.write(Path.of("checks", "regression-breakdown.txt"), Fingerprint.breakdown(listener), StandardCharsets.UTF_8);

        // People and traffic round the largest town, for a minute: only that they run (see above)
        Inhabitants people = (Inhabitants) field(listener, "inhabitants");
        GeodesicCollision collision = (GeodesicCollision) field(listener, "geodesicCollision");
        int[] walkers = new int[1];
        for (int frame = 0; frame < 600; frame++) {
            people.update(0.05f, town);
            collision.endFrame();
            if (frame % 50 == 49) people.forEachWalker((p, at, street, speed) -> walkers[0]++);
        }
        put("people.ran", new Fingerprint().add(walkers[0] > 0).add(people.census().length));
        return listener;
    }

    /** The pictures (decoded) and other files in a folder and its subfolders, in name order. */
    private static Fingerprint filesFingerprint(File dir) throws Exception {
        Fingerprint f = new Fingerprint();
        List<File> files = new ArrayList<>();
        collect(dir, files);
        files.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        for (File file : files) {
            f.add(dir.toPath().relativize(file.toPath()).toString().replace('\\', '/'));
            // (hashed now, value by value: nothing's held until the end, and nothing's hashed by identity)
            if (file.getName().endsWith(".png")) {
                java.awt.image.BufferedImage image = ImageIO.read(file);
                f.add(image.getWidth()).add(image.getHeight());
                for (int y = 0; y < image.getHeight(); y++) {
                    long row = 0;
                    for (int x = 0; x < image.getWidth(); x++) row = row * 1_000_003L + image.getRGB(x, y);
                    f.add(row);
                }
            } else {
                for (byte b : Files.readAllBytes(file.toPath())) f.add(b);
            }
        }
        return f;
    }

    private static void collect(File dir, List<File> into) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collect(f, into);
            else into.add(f);
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException notHere) {
                // (its superclass, then)
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static Method method(Object owner, String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method m = owner.getClass().getDeclaredMethod(name, parameters);
        m.setAccessible(true);
        return m;
    }
}
