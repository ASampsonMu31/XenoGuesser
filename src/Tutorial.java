import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The tutorial (Xenocorp's training programme): the same planet every time (TutorialPlan.SEED),
 * its exercises in a set order at set places, in set weather and at a set hour (see
 * TutorialPlan), each run as a little script: Xenocorp's messages (TutorialText), the gadgets and
 * maps it gives, and the objectives it lists down the HUD's right (white, gold once done) under a
 * bar for how far through the programme the player is. A guess is passed on what the exercise
 * teaches (TutorialChecks): passed, the places that would have passed are shown in green and the
 * results explain how it could have been worked out; failed, the exercise is tried again.
 */
final class Tutorial extends GamePart {

    Tutorial(XenoGuesser_GLEventListener game) {
        super(game);
    }

    // Whether the game being played is the tutorial (set before the world's made, so the loading
    // screen and the first round know)
    static volatile boolean active;

    private TutorialText text;
    private TutorialPlan.Rounds rounds;
    private final TutorialChecks checks = new TutorialChecks(game);
    // Told to play a message over the game (the window's, see XenoGuesser.showMessage)
    private volatile java.util.function.BiConsumer<TransmissionMessage, java.util.function.Consumer<Boolean>> messenger;

    // The exercise being played, and whether it's being tried again (the sun's then skips its
    // walking and turning)
    private volatile int stageIndex;
    private volatile boolean retry;
    // The exercise the next round is to be, decided as soon as the guess is judged (the next round
    // is built behind the results from then, before their button's pressed), and whether it's a retry
    // (developer aid: -Dxenoguesser.tutorialstage=4 starts at the fifth exercise)
    private volatile int upcomingIndex = Dev.integer("xenoguesser.tutorialstage", 0);
    private volatile boolean upcomingRetry;
    // Each exercise's check, once worked out (in the background as its round's built; the same every time)
    private final java.util.Map<TutorialPlan.Stage, TutorialChecks.Check> checksByStage = new java.util.concurrent.ConcurrentHashMap<>();
    // The last guess's verdict, for the results' button
    private volatile Boolean passed;

    /** One objective on the HUD's list: its words, and whether it's been done. */
    private static final class Objective {
        final String key;
        volatile String label;
        volatile boolean done;

        Objective(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private final List<Objective> objectives = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile int objectivesVersion;

    /** Reads the words and the rounds (as the world's made, before the first round is spawned). */
    void prepare() {
        text = TutorialText.load();
        rounds = TutorialPlan.read();
        if (rounds == null) {
            System.err.println("[TUTORIAL] No rounds file (" + TutorialPlan.FILE + "): run TutorialFinder");
        } else if (Math.abs(rounds.seaLevel() - game.seaLevelHeight) > 0.01f || Math.abs(rounds.width() - game.TOTAL_REGION_WIDTH) > 0.5f) {
            // (the world's generation has changed since the places were found: they may not suit it)
            System.err.println("[TUTORIAL] The rounds file was found on another world: run TutorialFinder again");
        }
    }

    void setMessenger(java.util.function.BiConsumer<TransmissionMessage, java.util.function.Consumer<Boolean>> messenger) {
        this.messenger = messenger;
    }

    TutorialText text() {
        if (text == null) text = TutorialText.load();
        return text;
    }

    TutorialPlan.Stage stage() {
        return TutorialPlan.Stage.values()[stageIndex];
    }

    private TutorialPlan.Site site() {
        return siteOf(stageIndex);
    }

    private TutorialPlan.Site siteOf(int index) {
        if (rounds == null || index < 0 || index >= TutorialPlan.Stage.values().length) return null;
        return rounds.sites().get(TutorialPlan.Stage.values()[index]);
    }

    // ------------------------------------------------------------------ the round's set-up

    /**
     * Where this exercise's round starts, its sky and its creatures set as they always are for
     * it (see RoundSpawner): the viewpoint, or null if there's no site for it (the round is then
     * a random one, as a normal game's).
     */
    Viewpoint spawn() {
        if (text == null) prepare();
        int index = upcomingIndex;
        TutorialPlan.Site site = siteOf(index);
        if (site == null) return null;
        Viewpoint view = site.viewpoint();
        double lon = Planet.longitude(view.place());
        game.sunDeclination = site.declination();
        game.noonLongitude = lon - site.hour();
        game.timeOfDay = (float) (0.5 + site.hour() / (2 * Math.PI));
        if (game.sphereWeather != null) {
            game.sphereWeather.newDay(new java.util.Random(TutorialPlan.SEED * 31L + index), view.place());
            game.sphereWeather.devWetness(site.wetness());
        }
        // (the same creatures in the same places every time)
        game.organismManager.clear(TutorialPlan.SEED * 131L + index);
        TutorialPlan.Site s = site;
        if (checksByStage.containsKey(s.stage())) return view;
        Thread prepare = new Thread(() -> {
            try {
                checksByStage.put(s.stage(), checks.prepare(s));
            } catch (Throwable e) {
                System.err.println("[TUTORIAL] Couldn't work out the " + s.stage().key + " exercise's check: " + e);
            }
        }, "tutorial-check");
        prepare.setDaemon(true);
        prepare.start();
        return view;
    }

    /** The round's begun (the player can move): the exercise's script starts. */
    void roundStarted() {
        stageIndex = Math.min(upcomingIndex, TutorialPlan.Stage.values().length - 1);
        retry = upcomingRetry;
        passed = null;
        objectives.clear();
        objectivesVersion++;
        resetProgress();
        TutorialPlan.Stage stage = stage();
        giveFor(stage, false);
        switch (stage) {
            case SUN -> {
                TutorialPlan.Site site = site();
                if (retry) startSunCompass();
                else {
                    // The marker first, ahead of the pod's stairs, then the welcome
                    Viewpoint stairs = site != null ? site.viewpoint() : new Viewpoint(game.camera.getSpherePosition(), Math.toRadians(game.camera.getYaw()));
                    placeMarker(SphereMath.move(stairs.place(), stairs.heading(), MARKER_AHEAD));
                    // (facing it from the start: no looking round needed until the circle)
                    faceWanted = marker;
                }
                if (!retry) say("msg.sun.welcome", () -> {
                    add("obj.sun.walk");
                    step = Step.WALK;
                    say("msg.sun.walk", null);
                });
            }
            case COAST -> {
                TutorialPlan.Site site = site();
                SpherePosition shore = site == null ? null : site.placeOf("marker");
                if (shore != null) {
                    placeMarker(shore);
                    faceWanted = marker;
                }
                add("obj.coast.walk");
                add("obj.coast.line");
                add("obj.coast.guess");
                step = Step.WALK;
                say("msg.coast.intro", null);
            }
            case TEMPERATURE -> {
                add("obj.temperature.read");
                add("obj.temperature.map");
                add("obj.temperature.guess");
                step = Step.READ;
                say("msg.temperature.intro", null);
            }
            case ALTITUDE -> {
                add("obj.altitude.read");
                add("obj.altitude.map");
                add("obj.altitude.guess");
                step = Step.READ;
                say("msg.altitude.intro", null);
            }
            case WILDLIFE -> {
                add("obj.wildlife.plants", "count", "0");
                step = Step.IDENTIFY;
                say("msg.wildlife.intro", null);
            }
            default -> {
                for (String key : mapObjectives(stage)) add(key);
                add("obj." + stage.key + ".guess");
                step = Step.MAPS;
                say("msg." + stage.key + ".intro", null);
            }
        }
    }

    /** The sun's exercise from the compass on (the walking and turning done, or skipped when it's tried again). */
    private void startSunCompass() {
        giveFor(TutorialPlan.Stage.SUN, true);
        add("obj.sun.compass");
        step = Step.FACE_SUN;
        say("msg.sun.compass", null);
    }

    // The gadgets, by their places in the inventory's full list (compass, thermometer, altimeter, kit)
    private static final boolean[] NONE = { false, false, false, false }, ALL = { true, true, true, true };

    /** Gives the gadgets and maps an exercise has (the sun's compass only once it's been earned: compassToo). */
    private void giveFor(TutorialPlan.Stage stage, boolean compassToo) {
        Set<MapPanel.Layer> layers = EnumSet.noneOf(MapPanel.Layer.class);
        List<MapOverlay> overlays = new ArrayList<>();
        boolean[] items = switch (stage) {
            case SUN -> compassToo ? new boolean[] { true, false, false, false } : NONE;
            case COAST -> new boolean[] { true, false, false, false };
            case TEMPERATURE -> new boolean[] { false, true, false, false };
            case ALTITUDE -> new boolean[] { false, false, true, false };
            case WILDLIFE -> new boolean[] { false, false, false, true };
            default -> ALL;
        };
        switch (stage) {
            case TEMPERATURE -> overlays.add(MapOverlay.of(FactorName.TEMPERATURE));
            case ALTITUDE -> layers.add(MapPanel.Layer.CONTOURS);
            case RAINFALL -> {
                overlays.add(MapOverlay.of(FactorName.RAINFALL));
                layers.add(MapPanel.Layer.RIVERS);
            }
            case GROUND -> {
                overlays.add(MapOverlay.of(FactorName.SOIL_COLOUR));
                overlays.add(MapOverlay.of(FactorName.ROCKS));
                overlays.add(MapOverlay.of(FactorName.ROCK_SIZE));
            }
            case GRASS -> {
                overlays.add(MapOverlay.of(FactorName.GRASS_ABUNDANCE));
                overlays.add(MapOverlay.of(FactorName.GRASS_HEIGHT));
                overlays.add(MapOverlay.of(FactorName.GRASS_COLOUR));
            }
            case WEALTH -> {
                overlays.add(MapOverlay.of(FactorName.WEALTH));
                layers.addAll(List.of(MapPanel.Layer.ROADS, MapPanel.Layer.BUILDINGS, MapPanel.Layer.SHOPS));
            }
            case FLAGS -> {
                overlays.add(MapOverlay.of(FactorName.FLAGS));
                overlays.add(MapOverlay.of(FactorName.LANGUAGES));
            }
            case ROADSIDE -> {
                overlays.add(MapOverlay.of(FactorName.BOLLARDS));
                overlays.add(MapOverlay.of(FactorName.ROAD_LINES));
                overlays.add(MapOverlay.of(FactorName.UTILITY_POLES));
                layers.add(MapPanel.Layer.ROADS);
            }
            case ARCHITECTURE -> overlays.add(MapOverlay.of(FactorName.ARCHITECTURE));
            case LANDMARKS -> overlays.add(MapOverlay.of(FactorName.LANDMARKS));
            default -> { }
        }
        game.ownItemsOnly(items);
        game.mapOverlays.ownMapsOnly(layers, overlays);
    }

    /** The plant's and animal's maps (the wildlife exercise's, once their identifying's done). */
    private void giveSpeciesMaps() {
        TutorialPlan.Site site = site();
        if (site == null) return;
        game.mapOverlays.ownMapsOnly(EnumSet.noneOf(MapPanel.Layer.class), List.of(
                new MapOverlay(FactorName.FLORA, site.integer("plant", 0)), new MapOverlay(FactorName.ANIMAL_POPULATION, site.integer("animal", 0))));
    }

    /** The map exercises' objectives of showing each of their maps (each done once it's been shown on the map). */
    private static List<String> mapObjectives(TutorialPlan.Stage stage) {
        return switch (stage) {
            case RAINFALL -> List.of("obj.rainfall.map", "obj.rainfall.rivers");
            case GROUND -> List.of("obj.ground.soil", "obj.ground.rocks", "obj.ground.size");
            case GRASS -> List.of("obj.grass.colour", "obj.grass.height");
            case WEALTH -> List.of("obj.wealth.map", "obj.wealth.buildings");
            case FLAGS -> List.of("obj.flags.map", "obj.flags.languages");
            case ROADSIDE -> List.of("obj.roadside.bollards", "obj.roadside.lines");
            case ARCHITECTURE -> List.of("obj.architecture.map");
            case LANDMARKS -> List.of("obj.landmarks.map");
            default -> List.of();
        };
    }

    /** Whether the map shows what a map objective asks for. */
    private boolean mapShown(String key) {
        MapPanel map = game.minimap;
        if (map == null) return false;
        MapOverlay heat = game.mapOverlays.currentDebugFactor;
        FactorName pictures = game.mapOverlays.currentPictureMap;
        FactorName f = heat == null ? null : heat.factor();
        return switch (key) {
            case "obj.temperature.map" -> f == FactorName.TEMPERATURE;
            case "obj.altitude.map" -> map.isLayerShown(MapPanel.Layer.CONTOURS);
            case "obj.wildlife.plantmap" -> f == FactorName.FLORA && site() != null && heat.species() == site().integer("plant", 0);
            case "obj.wildlife.animalmap" -> f == FactorName.ANIMAL_POPULATION && site() != null && heat.species() == site().integer("animal", 0);
            case "obj.rainfall.map" -> f == FactorName.RAINFALL;
            case "obj.rainfall.rivers" -> map.isLayerShown(MapPanel.Layer.RIVERS);
            case "obj.ground.soil" -> f == FactorName.SOIL_COLOUR;
            case "obj.ground.rocks" -> f == FactorName.ROCKS;
            case "obj.ground.size" -> f == FactorName.ROCK_SIZE;
            case "obj.grass.colour" -> f == FactorName.GRASS_COLOUR;
            case "obj.grass.height" -> f == FactorName.GRASS_HEIGHT;
            case "obj.wealth.map" -> f == FactorName.WEALTH;
            case "obj.wealth.buildings" -> map.isLayerShown(MapPanel.Layer.BUILDINGS);
            case "obj.flags.map" -> f == FactorName.FLAGS;
            case "obj.flags.languages" -> pictures == FactorName.LANGUAGES;
            case "obj.roadside.bollards" -> pictures == FactorName.BOLLARDS;
            case "obj.roadside.lines" -> pictures == FactorName.ROAD_LINES;
            case "obj.architecture.map" -> pictures == FactorName.ARCHITECTURE;
            case "obj.landmarks.map" -> pictures == FactorName.LANDMARKS;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ messages

    // The messages waiting to be said, each with what's done once it's ended
    private final java.util.ArrayDeque<Object[]> queued = new java.util.ArrayDeque<>();
    private boolean speaking;
    // Counts each hush, so a message from before one (still loading, or its ending) is let go
    private int spoken;
    // Stops whatever message is playing over the game (the window's: see XenoGuesser)
    private volatile Runnable silencer;

    void setSilencer(Runnable silencer) {
        this.silencer = silencer;
    }

    /** Says a message once those before it have been (or skipped), then does then (on the UI thread; null for nothing). */
    void say(String key, Runnable then) {
        synchronized (queued) {
            queued.add(new Object[] { key, then });
            if (speaking) return;
            speaking = true;
        }
        sayNext();
    }

    private void sayNext() {
        Object[] next;
        int heard;
        synchronized (queued) {
            heard = spoken;
            next = queued.poll();
            if (next == null) {
                speaking = false;
                return;
            }
        }
        String key = (String) next[0];
        Runnable then = (Runnable) next[1];
        Thread load = new Thread(() -> {
            TransmissionMessage message = text().message(key);
            java.util.function.BiConsumer<TransmissionMessage, java.util.function.Consumer<Boolean>> m = messenger;
            synchronized (queued) {
                // (hushed while it was loading: it's not said)
                if (heard != spoken) return;
            }
            java.util.function.Consumer<Boolean> ended = skipped -> {
                synchronized (queued) {
                    if (heard != spoken) return;
                }
                if (then != null) then.run();
                sayNext();
            };
            if (m == null) javax.swing.SwingUtilities.invokeLater(() -> ended.accept(true));
            else m.accept(message, ended);
        }, "tutorial-message");
        load.setDaemon(true);
        load.start();
    }

    /** Stops the message being said and forgets any waiting, and what they'd have done after (a new round, or the tutorial left). */
    void hush() {
        synchronized (queued) {
            queued.clear();
            spoken++;
            speaking = false;
        }
        Runnable stop = silencer;
        if (stop != null) javax.swing.SwingUtilities.invokeLater(stop);
    }

    // ------------------------------------------------------------------ objectives

    private void add(String key, String... vars) {
        objectives.add(new Objective(key, label(key, vars)));
        objectivesVersion++;
    }

    private String label(String key, String... vars) {
        TutorialPlan.Site site = site();
        List<String> all = new ArrayList<>(List.of(vars));
        if (site != null && stage() == TutorialPlan.Stage.WILDLIFE) {
            all.addAll(List.of("plant", game.floraName(site.integer("plant", 0)), "animal", game.organismManager.speciesName(site.integer("animal", 0))));
        }
        return text().get(key, all.toArray(new String[0]));
    }

    /**
     * Whether a guess may be made yet: every objective but the guess itself done (an exercise
     * whose guess isn't asked for yet, as the sun's until its compass is used, may not be guessed).
     */
    boolean guessAllowed() {
        if (!active) return true;
        boolean asked = false;
        for (Objective o : objectives) {
            if (o.key.endsWith(".guess")) asked = true;
            else if (!o.done) return false;
        }
        return asked;
    }

    private Objective objective(String key) {
        for (Objective o : objectives) if (o.key.equals(key)) return o;
        return null;
    }

    private boolean done(String key) {
        Objective o = objective(key);
        return o != null && o.done;
    }

    /** Marks an objective done (gold); returns whether it wasn't already. */
    private boolean complete(String key) {
        Objective o = objective(key);
        if (o == null || o.done) return false;
        o.done = true;
        objectivesVersion++;
        return true;
    }

    // ------------------------------------------------------------------ the marker

    // The first marker's distance ahead of the pod's stairs (world units: far off, a walk away;
    // TutorialSites checks the way there can be walked), and how near it counts as being at it;
    // and the circle on the ground it leaves for the turning, which must all be done inside it
    static final double MARKER_AHEAD = 70.0 * PlayerBody.METRE;
    private static final double AT_MARKER = 2.6 * PlayerBody.METRE, CIRCLE_RADIUS = 2.5 * PlayerBody.METRE;

    private volatile SpherePosition marker, circle;

    /** Lays the circle to turn in on the ground round a place (null takes it up). */
    private void placeCircle(SpherePosition at) {
        circle = at;
        game.measurementLog.setCircle(at, (float) CIRCLE_RADIUS);
    }

    private void placeMarker(SpherePosition at) {
        SpherePosition ground = SphereMath.atAltitude(at, game.groundHeightAt(at));
        marker = ground;
        game.measurementLog.setMarker(ground);
    }

    private void removeMarker() {
        marker = null;
        game.measurementLog.setMarker(null);
    }

    // ------------------------------------------------------------------ each frame

    private enum Step { NONE, WALK, SPIN, JUMP, FACE_SUN, LINE_COAST, READ, IDENTIFY, SPECIES_MAPS, MAPS, GUESS }

    private volatile Step step = Step.NONE;
    // Whether the player was off the ground last frame (a jump counts from its take-off)
    private volatile boolean leftGround;
    // Where the view's to be turned to face, next frame (the marker, as an exercise starts), or null
    private volatile SpherePosition faceWanted;
    private float lastYaw = Float.NaN, turned;
    // How long the compass has been held lined up (seconds)
    private double linedUp;
    private long lastFrame;
    // The plants and animals identified (where they were), each counted once
    private final List<SpherePosition> plantsFound = new ArrayList<>();

    private void resetProgress() {
        step = Step.NONE;
        lastYaw = Float.NaN;
        turned = 0f;
        linedUp = 0.0;
        removeMarker();
        placeCircle(null);
        synchronized (plantsFound) {
            plantsFound.clear();
            animalsSeen.clear();
        }
        hush();
    }

    // How nearly the view must face the sun or run along the coast (degrees), and for how long (seconds)
    private static final double LINE_UP_DEGREES = 12.0, LINE_UP_SECONDS = 0.8;

    /** Each frame (GL thread): what the player's done towards the objectives. */
    void update() {
        if (!active || game.minimap == null || game.minimap.isFullScreenRevealMode()) return;
        long now = System.nanoTime();
        double dt = lastFrame == 0L ? 0.0 : Math.min(0.1, (now - lastFrame) * 1e-9);
        lastFrame = now;
        SpherePosition eye = game.camera.getSpherePosition();
        SpherePosition face = faceWanted;
        if (face != null) {
            // (level with the eyes, straight at it)
            faceWanted = null;
            game.camera.lookAt(SphereMath.atAltitude(face, SphereMath.altitude(eye)));
            lastYaw = Float.NaN;
        }
        float yaw = game.camera.getYaw();
        float turnedNow = Float.isNaN(lastYaw) ? 0f : (float) Math.IEEEremainder(yaw - lastYaw, 360.0);
        lastYaw = yaw;
        SpherePosition m = marker;
        double toMarker = m == null ? Double.MAX_VALUE : SphereMath.distance(SphereMath.atAltitude(eye, 0.0), SphereMath.atAltitude(m, 0.0));
        boolean compass = game.heldGadget() == PlayerBody.GADGET_COMPASS;
        switch (step) {
            case WALK -> {
                if (toMarker < AT_MARKER) {
                    if (stage() == TutorialPlan.Stage.SUN) {
                        // The pin goes, and a circle's left on the ground where it stood, to turn in
                        complete("obj.sun.walk");
                        removeMarker();
                        placeCircle(m);
                        add("obj.sun.spin");
                        step = Step.SPIN;
                        turned = 0f;
                        say("msg.sun.spin", null);
                    } else {
                        complete("obj.coast.walk");
                        // (the shore's marker goes once it's reached)
                        removeMarker();
                        step = Step.LINE_COAST;
                        say("msg.coast.compass", null);
                    }
                }
            }
            case SPIN -> {
                // (the whole turn inside the circle, jumping or not: stepping out of it starts it again)
                SpherePosition c = circle;
                double fromCircle = c == null ? Double.MAX_VALUE : SphereMath.distance(SphereMath.atAltitude(eye, 0.0), SphereMath.atAltitude(c, 0.0));
                if (fromCircle > CIRCLE_RADIUS) {
                    turned = 0f;
                } else {
                    turned += turnedNow;
                    if (Math.abs(turned) >= 360f) {
                        complete("obj.sun.spin");
                        // (headings turn from east towards south: clockwise seen from above)
                        boolean clockwise = turned > 0f;
                        placeCircle(null);
                        step = Step.NONE;
                        // (the jumping's only asked for, and counted, once the turning's been remarked on)
                        say(clockwise ? "msg.sun.spun.clockwise" : "msg.sun.spun.anticlockwise", () -> {
                            add("obj.sun.jump");
                            leftGround = true;
                            step = Step.JUMP;
                            say("msg.sun.jump", null);
                        });
                    }
                }
            }
            case JUMP -> {
                // (a jump begun from now: one already in the air when it was asked for doesn't count)
                boolean up = game.playerMotion.airborne, takeOff = up && !leftGround;
                leftGround = up;
                if (takeOff) {
                    complete("obj.sun.jump");
                    step = Step.NONE;
                    javax.swing.SwingUtilities.invokeLater(this::startSunCompass);
                }
            }
            case FACE_SUN -> {
                float[] sun = Planet.sunDirection(eye, game.sunDeclination, game.noonLongitude);
                double sunYaw = Math.toDegrees(Math.atan2(sun[2], sun[0]));
                boolean facing = compass && Math.abs(Math.IEEEremainder(yaw - sunYaw, 360.0)) < LINE_UP_DEGREES;
                linedUp = facing ? linedUp + dt : 0.0;
                if (linedUp >= LINE_UP_SECONDS) {
                    complete("obj.sun.compass");
                    add("obj.sun.expand");
                    add("obj.sun.guess");
                    step = Step.GUESS;
                    say("msg.sun.map", null);
                }
            }
            case LINE_COAST -> {
                TutorialPlan.Site site = site();
                double line = site == null ? 0.0 : site.number("coastLine", 0.0);
                boolean along = compass && TutorialChecks.angleApart(Math.toRadians(yaw), line) < Math.toRadians(LINE_UP_DEGREES);
                linedUp = along ? linedUp + dt : 0.0;
                if (linedUp >= LINE_UP_SECONDS) {
                    complete("obj.coast.line");
                    step = Step.GUESS;
                }
            }
            case READ, MAPS, SPECIES_MAPS -> {
                for (Objective o : objectives) if (!o.done && mapShown(o.key)) complete(o.key);
            }
            default -> { }
        }
        if (step == Step.GUESS && game.minimap.getMapSize() == MapPanel.MapSize.LARGE) complete("obj.sun.expand");
    }

    /** A reading's just settled (see MeasurementLog.note). */
    void readingTaken(int gadget) {
        if (!active || step != Step.READ) return;
        if (gadget == PlayerBody.GADGET_THERMOMETER && complete("obj.temperature.read")) say("msg.temperature.read", null);
        if (gadget == PlayerBody.GADGET_ALTIMETER) complete("obj.altitude.read");
    }

    // How far apart two plants identified must stand to be two (a tree can be built afresh as the
    // ground round it streams in, so it's known by where it stands); and how many of each
    private static final double SAME_PLANT = 1.0 * PlayerBody.METRE;
    private static final int TO_IDENTIFY = 5;
    // The animals identified, each known by itself (its outline, which is its own for as long as it lives)
    private final java.util.Set<Object> animalsSeen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Something's been named through the identification kit's glass: an animal or a plant, its
     * species, something that's always the same for it, and where it is. Only the exercise's own
     * plant counts, five of them, and then only its own animal, five of those.
     */
    void identified(boolean animal, int species, Object key, SpherePosition at) {
        if (!active || step != Step.IDENTIFY) return;
        TutorialPlan.Site site = site();
        if (site == null) return;
        boolean plantsDone = done("obj.wildlife.plants");
        if (animal != plantsDone) return;
        if (species != site.integer(animal ? "animal" : "plant", -1)) return;
        int count;
        synchronized (plantsFound) {
            if (animal) {
                if (!animalsSeen.add(key)) return;
                count = animalsSeen.size();
            } else {
                for (SpherePosition p : plantsFound) if (SphereMath.distance(p, at) < SAME_PLANT) return;
                plantsFound.add(at);
                count = plantsFound.size();
            }
        }
        if (count > TO_IDENTIFY) return;
        String objKey = animal ? "obj.wildlife.animals" : "obj.wildlife.plants";
        Objective o = objective(objKey);
        if (o != null) {
            o.label = label(objKey, "count", Integer.toString(count));
            objectivesVersion++;
        }
        if (count < TO_IDENTIFY) return;
        complete(objKey);
        if (!animal) {
            // The plant's done: now its animal
            add("obj.wildlife.animals", "count", "0");
            say("msg.wildlife.animals", null);
            return;
        }
        step = Step.SPECIES_MAPS;
        giveSpeciesMaps();
        add("obj.wildlife.plantmap");
        add("obj.wildlife.animalmap");
        add("obj.wildlife.guess");
        say("msg.wildlife.maps", null);
    }

    // ------------------------------------------------------------------ the results

    /** What the results say of a guess: whether it passed, the green wash over the passing places, and the words. */
    record Verdict(boolean passed, BufferedImage wash, String title, String explanation) {
    }

    /** The verdict on a guess at pin (null: no guess), or null if the check isn't ready (the round's then judged as usual). */
    Verdict judge(GeodesicChunkGrid.Direction pin) {
        if (!active) return null;
        TutorialChecks.Check c = checksByStage.get(stage());
        // (the next round, built from now on behind the results, is the next exercise or this one again)
        boolean ok = c != null && c.passes(pin);
        passed = ok;
        upcomingIndex = ok ? stageIndex + 1 : stageIndex;
        upcomingRetry = !ok;
        if (c == null) return null;
        String key = stage().key;
        List<String> vars = new ArrayList<>(List.of(c.vars()));
        if (pin != null && stage() == TutorialPlan.Stage.TEMPERATURE) vars.addAll(List.of("guessed", TutorialChecks.celsius(checks.heatmapTemperature(pin))));
        if (pin != null && stage() == TutorialPlan.Stage.ALTITUDE) vars.addAll(List.of("guessed", Math.round(checks.metresUp(pin)) + " m"));
        String[] v = vars.toArray(new String[0]);
        if (ok) for (Objective o : objectives) if (o.key.endsWith(".guess")) complete(o.key);
        return new Verdict(ok, ok ? c.wash() : null, text().get(ok ? "verdict.pass" : "verdict.fail"),
                text().get((ok ? "explain." : "fail.") + key, v));
    }

    /** The results' button: on, tried again, or (the last passed) the training finished. */
    String buttonLabel() {
        Boolean p = passed;
        if (p == null || !p) return text().get("button.retry");
        return stageIndex == TutorialPlan.Stage.values().length - 1 ? text().get("button.finish") : text().get("button.next");
    }

    /** The results' button pressed: whether the training's over (true: the last exercise passed). */
    boolean afterResults() {
        Boolean p = passed;
        return p != null && p && stageIndex == TutorialPlan.Stage.values().length - 1;
    }

    /** The training's finished: the last message, then onEnd (the menu). */
    void finish(Runnable onEnd) {
        resetProgress();
        say("msg.finish", onEnd);
    }

    // ------------------------------------------------------------------ the HUD

    // The panel's width, and its rows' heights (before the screen's scaling)
    private static final int PANEL_W = 340, HEAD_H = 64, ROW_H = 30;
    private int drawnVersion = -1, drawnStage = -1;

    /** The objectives down the HUD's right, under the programme's progress (redrawn only when they change). */
    void drawPanel(HudOverlay hud, float scale, boolean rescaled, int screenWidth, int top) {
        if (!active) {
            if (hud.has("tutorial")) hud.setVisible("tutorial", false);
            return;
        }
        int version = objectivesVersion;
        if (rescaled || version != drawnVersion || stageIndex != drawnStage || !hud.has("tutorial")) {
            drawnVersion = version;
            drawnStage = stageIndex;
            List<Objective> list = new ArrayList<>(objectives);
            int h = HEAD_H + Math.max(1, list.size()) * ROW_H + 12;
            Graphics2D[] g = new Graphics2D[1];
            BufferedImage image = HudStyle.canvas(PANEL_W, h, scale, g);
            paint(g[0], h, list);
            g[0].dispose();
            hud.put("tutorial", image, 0, 0);
        }
        int margin = Math.round(HudStyle.HUD_MARGIN * scale);
        hud.move("tutorial", screenWidth - hud.width("tutorial") - margin, top);
        hud.setVisible("tutorial", !GameClock.isPaused());
    }

    private void paint(Graphics2D g, int h, List<Objective> list) {
        HudStyle.panel(g, 0, 0, PANEL_W, h, HudStyle.GLASS);
        int stages = TutorialPlan.Stage.values().length;
        HudStyle.label(g, "XENOCORP TRAINING", 16f, 22f, 11f, HudStyle.LABEL);
        String count = "Exercise " + (stageIndex + 1) + " of " + stages;
        g.setFont(HudStyle.font(Font.PLAIN, 12f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(HudStyle.DIM);
        g.drawString(count, PANEL_W - 16f - fm.stringWidth(count), 22f);
        // The bar: the exercises passed, and this one's share of its objectives done
        long doneHere = list.stream().filter(o -> o.done).count();
        float progress = (stageIndex + (list.isEmpty() ? 0f : doneHere / (float) list.size())) / stages;
        float bx = 16f, by = 34f, bw = PANEL_W - 32f, bh = 9f;
        g.setColor(new Color(255, 255, 255, 30));
        g.fill(new java.awt.geom.RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
        g.setColor(HudStyle.GOLD);
        g.fill(new java.awt.geom.RoundRectangle2D.Float(bx, by, Math.max(bh, bw * progress), bh, bh, bh));
        Font row = HudStyle.font(Font.BOLD, 15f);
        for (int i = 0; i < list.size(); i++) {
            Objective o = list.get(i);
            float y = HEAD_H + i * ROW_H;
            Color colour = o.done ? HudStyle.GOLD : Color.WHITE;
            // (a box, ticked once done)
            g.setColor(colour);
            g.setStroke(new java.awt.BasicStroke(1.6f));
            g.draw(new java.awt.geom.RoundRectangle2D.Float(16f, y + 4f, 15f, 15f, 4f, 4f));
            if (o.done) {
                java.awt.geom.Path2D tick = new java.awt.geom.Path2D.Float();
                tick.moveTo(19.5f, y + 11.5f);
                tick.lineTo(23f, y + 15.5f);
                tick.lineTo(28.5f, y + 7.5f);
                g.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                g.draw(tick);
            }
            g.setFont(row);
            g.drawString(fit(g, o.label, PANEL_W - 60f), 40f, y + 17f);
        }
    }

    /** Text cut short with an ellipsis to fit a width. */
    private static String fit(Graphics2D g, String text, float width) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= width) return text;
        String cut = text;
        while (cut.length() > 1 && fm.stringWidth(cut + "…") > width) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }
}
