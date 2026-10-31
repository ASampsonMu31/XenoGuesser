import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import com.xenoguesser.math.Vector3;

/**
 * The landmarks' photographs for their map (see MapOverlays.showLandmarks), and a typical
 * house's for each of the architecture map's pictures: each one taken in the world itself, with
 * its own renderer, as a visitor would see it. While the world loads
 * (the maps being charted on their own thread meanwhile), the camera's sent to a spot chosen for
 * each kind (before a built one's front and off to one side; across a bay from its shore; along
 * a spit from its root; over a field of pinnacles from the land...), waits there until what's
 * round it is built, as a teleport does, and the world's drawn into a picture of its own on a
 * clear mid-morning. A house is drawn on its own against the sky instead, from before its
 * door, with nothing round it (no ground, plants, people, vehicles or signs) to hide it or to
 * tell where it stands: the architecture map shows how a region builds, not where. Then it goes back to the landing site (before anything's built round it), the
 * round's own day put back.
 */
final class LandmarkPhotos extends GamePart {

    private static final float M = PlayerBody.METRE;
    // Frames drawn at each spot before the one kept (the grass and shadows settle in over the first)
    private static final int WARM_UP = 4;
    // How long to wait for a spot's surroundings before photographing them as they are
    private static final long PATIENCE = 20_000;
    // Drawn at twice the picture's size and shrunk, for smooth edges
    private static final int SUPERSAMPLE = 2;

    LandmarkPhotos(XenoGuesser_GLEventListener game) {
        super(game);
    }

    /** Where a photograph's taken from, what it looks at, and how tall a view it takes (radians). */
    private record Shot(SpherePosition eye, SpherePosition target, double fieldOfView) {
    }

    /**
     * Something to photograph: its name (for the log and a saved photograph's file), the landmark
     * or the house it is (the other null), its picture's size, where it's taken from and what's
     * done with the picture once taken.
     */
    private record Subject(String name, String file, Landmark landmark, House house, int width, int height,
                           java.util.function.Supplier<Shot> shot, java.util.function.Consumer<BufferedImage> keep) {
    }

    private enum Stage { WAITING_TO_START, GOING, WAITING, SHOOTING, DONE }

    // A house's picture is as wide as a landmark's; how long to wait for the architecture map's
    // pictures to be placed (on the maps' own thread) before its houses go unphotographed
    private static final float HOUSE_PICTURE_ASPECT = 1.45f;
    private static final long MAP_PATIENCE = 60_000;

    private Stage stage = Stage.WAITING_TO_START;
    private final List<Subject> subjects = new java.util.ArrayList<>();
    // Whether the houses have been added after the landmarks, and since when they've been waited for
    private boolean housesAdded;
    private long housesWanted;
    private int next, frame;
    private Shot shot;
    private long since;
    // Whether a grove's shot has been aimed at its plants yet
    private boolean refined;
    // Where the camera was and the round's day, to put back
    private SpherePosition homeEye;
    private float homeYaw, homePitch, homeWetness;
    private double homeNoon, homeDeclination;
    private float homeTimeOfDay;
    private boolean homeHidden;

    boolean finished() {
        return stage == Stage.DONE;
    }

    /** Holds the camera on the shot (each frame drawn, after the player's moved), while there is one. */
    void placeCamera() {
        if (shot == null) return;
        game.camera.setSphereView(shot.eye(), 0f, 0f);
        game.camera.lookAt(shot.target());
    }

    /** One step a frame of the loading: on to the next landmark, waiting for what's round it, or photographing it. How far through they are, 0 to 1. */
    float step() {
        switch (stage) {
            case WAITING_TO_START -> {
                for (Landmark lm : game.infraManager.landmarks().all()) {
                    int height = MapOverlays.LANDMARK_PICTURE_HEIGHT;
                    subjects.add(new Subject(lm.name, lm.kind.name().toLowerCase(), lm, null, lm.pictureWidth(height), height,
                            () -> shotOf(lm), picture -> lm.picture = Landmark.framed(picture)));
                }
                homeEye = game.camera.getSpherePosition();
                homeYaw = game.camera.getYaw();
                homePitch = game.camera.getPitch();
                homeHidden = game.hideFirstPerson;
                homeNoon = game.noonLongitude;
                homeDeclination = game.sunDeclination;
                homeTimeOfDay = game.timeOfDay;
                if (game.sphereWeather != null) homeWetness = game.sphereWeather.dayWetness();
                stage = Stage.GOING;
            }
            case GOING -> {
                if (next >= subjects.size() && !housesAdded && !addHouses()) break;
                if (next >= subjects.size()) {
                    goHome();
                    break;
                }
                Subject subject = subjects.get(next);
                aim(subject.shot().get(), subject);
                refined = false;
                since = System.currentTimeMillis();
                stage = Stage.WAITING;
                // (a house, drawn on its own, has nothing round it to wait for)
                if (subject.house() != null) {
                    game.photoSubject = game.infraManager.houseBuilder.alone(subject.house());
                    frame = 0;
                    stage = Stage.SHOOTING;
                }
            }
            case WAITING -> {
                placeCamera();
                boolean built = game.buildRoundCamera();
                // (a grove's looked at again once its plants are there: at the nearest of them to its middle)
                Subject subject = subjects.get(next);
                Landmark lm = subject.landmark();
                if (built && lm != null && lm.kind == Landmark.Kind.GROVE && !refined) {
                    refined = true;
                    Shot closer = amongPlants(lm);
                    if (closer != null) {
                        aim(closer, subject);
                        break;
                    }
                }
                if (built || System.currentTimeMillis() - since > PATIENCE) {
                    if (!built) System.out.printf("[LANDMARKS] %s's surroundings still unbuilt after %d s: photographed as they are%n", subject.name(), PATIENCE / 1000);
                    clearDay(shot.target());
                    frame = 0;
                    stage = Stage.SHOOTING;
                }
            }
            case SHOOTING -> shoot(subjects.get(next));
            default -> { }
        }
        return subjects.isEmpty() ? 1f : Math.min(1f, (next + (stage == Stage.SHOOTING ? 0.6f : 0f)) / (subjects.size() + 0.5f));
    }

    /**
     * After the landmarks, a typical house (see InfrastructureManager.typicalHouses) for each of
     * the architecture map's designs as built in each nation, once its pictures are placed.
     * False while they're still being waited for.
     */
    private boolean addHouses() {
        List<Integer> keys = game.mapOverlays.architectureKeys;
        if (keys == null) {
            if (housesWanted == 0L) housesWanted = System.currentTimeMillis();
            if (System.currentTimeMillis() - housesWanted < MAP_PATIENCE) return false;
            System.out.printf("[LANDMARKS] The architecture map's pictures still unplaced after %d s: its houses left drawn%n", MAP_PATIENCE / 1000);
            keys = List.of();
        }
        housesAdded = true;
        int height = MapOverlays.DETAIL_PICTURE_HEIGHT, width = Math.round(height * HOUSE_PICTURE_ASPECT);
        for (int key : new java.util.TreeSet<>(keys)) {
            int[] of = game.mapOverlays.architectureOf(key);
            List<House> houses = game.infraManager.typicalHouses(of[0], of[1]);
            if (houses.isEmpty()) continue;
            House house = houses.get(0);
            subjects.add(new Subject("nation " + of[0] + "'s house in architecture " + of[1], "house_" + of[0] + "_" + of[1], null, house, width, height,
                    () -> houseShot(house), picture -> game.mapOverlays.architecturePhotos.put(key, picture)));
        }
        return true;
    }

    /** Draws a frame at the spot; the last, once the frames before have settled the grass and shadows, is kept as the subject's picture. */
    private void shoot(Subject subject) {
        int height = subject.height(), width = subject.width();
        try {
            game.hideFirstPerson = true;
            BufferedImage drawn = game.photograph(width * SUPERSAMPLE, height * SUPERSAMPLE, shot.fieldOfView());
            if (++frame < WARM_UP) return;
            BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = picture.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(drawn, 0, 0, width, height, null);
            g.dispose();
            subject.keep().accept(picture);
            System.out.printf("[LANDMARKS] Photographed %s in %d ms%n", subject.name(), System.currentTimeMillis() - since);
            if (SAVE_TO != null) save(drawn, subject);
        } catch (RuntimeException e) {
            // (its picture's drawn without the world instead: see MapOverlays.showLandmarks)
            System.err.println("[LANDMARKS] Couldn't photograph " + subject.name() + ": " + e);
        } finally {
            game.hideFirstPerson = homeHidden;
        }
        game.endPhotoSubject();
        putDayBack();
        next++;
        stage = Stage.GOING;
    }

    // Developer aid: -Dxenoguesser.landmarkphotos=folder saves each photograph there, at the size it was drawn
    private static final String SAVE_TO = Dev.property("xenoguesser.landmarkphotos");

    private void save(BufferedImage drawn, Subject subject) {
        java.io.File folder = new java.io.File(SAVE_TO);
        folder.mkdirs();
        try {
            javax.imageio.ImageIO.write(drawn, "png", new java.io.File(folder, String.format("%02d_%s.png", next, subject.file())));
        } catch (java.io.IOException e) {
            System.err.println("[LANDMARKS] Couldn't save " + subject.name() + "'s photograph: " + e);
        }
    }

    /** Back to the landing site as it was (what's round it built next: see advanceLoading). */
    private void goHome() {
        shot = null;
        game.buildOnlyInView(null);
        game.camera.setSphereView(homeEye, homeYaw, homePitch);
        stage = Stage.DONE;
    }

    /** A clear day at the place photographed, the sun up in the mid-morning on its side of the equator. */
    private void clearDay(SpherePosition place) {
        double hourAngle = (10.5 - 12.0) / 24.0 * Math.PI * 2.0;
        game.noonLongitude = Planet.longitude(place) - hourAngle;
        game.timeOfDay = (float) (0.5 + hourAngle / (2 * Math.PI));
        game.sunDeclination = Math.copySign(Math.toRadians(12.0), Planet.latitude(place));
        if (game.sphereWeather != null) game.sphereWeather.devWetness(-2.5f);
    }

    private void putDayBack() {
        game.noonLongitude = homeNoon;
        game.sunDeclination = homeDeclination;
        game.timeOfDay = homeTimeOfDay;
        if (game.sphereWeather != null) game.sphereWeather.setDayWetness(homeWetness);
    }

    // ------------------------------------------------------------------ where each is taken from

    /** Takes a shot from now on: the camera there, and only what's in its view built round it. */
    private void aim(Shot s, Subject subject) {
        shot = s;
        placeCamera();
        double across = Math.atan(subject.width() / (double) subject.height() * Math.tan(s.fieldOfView() * 0.5));
        game.buildOnlyInView(subject.house() != null ? cell -> false : inView(s, across));
    }

    /**
     * The cells in a shot's view (to across radians either side of the way it looks, along the
     * ground), and those right round the eye: all a photograph needs built, a fraction of the
     * whole round it.
     */
    private static java.util.function.Predicate<GeodesicChunkGrid.Cell> inView(Shot s, double across) {
        SpherePosition eye = SphereMath.atAltitude(s.eye(), 0.0);
        double toward = SphereMath.heading(eye, SphereMath.atAltitude(s.target(), 0.0));
        return cell -> {
            SpherePosition middle = SphereMath.atAltitude(cell.centre(), 0.0);
            double d = SphereMath.distance(eye, middle), r = cell.angularRadius() * Planet.radius();
            if (d <= r + 25f * M) return true;
            double off = Math.abs(Math.IEEEremainder(SphereMath.heading(eye, middle) - toward, Math.PI * 2));
            return off <= across + Math.asin(Math.min(1.0, r / d)) + 0.12;
        };
    }

    /**
     * A grove's shot among its own plants (once they're built): one of those nearest its middle,
     * from far enough off to see it whole with others behind it, from about its own height,
     * from out in the open (not in a house, nor with the land in the way).
     */
    private Shot amongPlants(Landmark lm) {
        List<SphereFlora> plants = new java.util.ArrayList<>();
        for (SphereFlora plant : game.trees.near(lm.place(), lm.reach)) if (plant.speciesIndex() == lm.species) plants.add(plant);
        plants.sort(java.util.Comparator.comparingDouble(p -> SphereMath.distance(lm.place(), SphereMath.atAltitude(p.position(), 0.0))));
        System.out.printf("[LANDMARKS] %s: %d of its plants found%n", lm.name, plants.size());
        for (SphereFlora plant : plants.subList(0, Math.min(12, plants.size()))) {
            SpherePosition at = SphereMath.atAltitude(plant.position(), 0.0);
            // (about its height: the first few species are trees, the rest shrubs and smaller, as SpeciesPictures frames them)
            float size = Math.max(0.5f * M, Math.min(25f * M, XenoGuesser_GLEventListener.FLORA_CULL_RADIUS * (lm.species < 4 ? 0.7f : 0.14f) * plant.scale()));
            double ground = ground(at), toMiddle = SphereMath.distance(at, lm.place()) > 1f * M ? SphereMath.heading(lm.place(), at) : 0.0;
            SpherePosition target = SphereMath.atAltitude(at, ground + size * 0.45);
            // (from out beyond it looking back in towards the grove's middle, or round from there)
            for (int k = 0; k < 8; k++) {
                SpherePosition eye = SphereMath.move(at, toMiddle + (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2) * Math.PI / 4, size * 3.5);
                eye = SphereMath.atAltitude(eye, Math.max(ground(eye) + 1.6f * M, ground + size * 0.75));
                if (game.infraManager.isBuildingLocation(eye, 2f * M) || hidden(eye, target, -0.3f * M) > 1) continue;
                return new Shot(eye, target, Math.toRadians(55));
            }
        }
        return null;
    }

    /** The ground's height (or the sea's, over it) at a place. */
    private double ground(SpherePosition p) {
        return Math.max(game.seaLevelHeight, game.groundHeightAt(SphereMath.atAltitude(p, 0.0)));
    }

    private Shot shotOf(Landmark lm) {
        SpherePosition middle = lm.place();
        double sea = game.seaLevelHeight;
        return switch (lm.kind) {
            // Built ones: before the front and off to one side, a little above (a spire from
            // partway up its height, in a tall wide view, so all of it is in the picture)
            case SPIRE -> built(lm, 100f * M, Math.toRadians(28), lm.base + lm.height * 0.38, lm.base + lm.height * 0.5, 80);
            case STONES -> built(lm, lm.reach * 2.3f, Math.toRadians(34), lm.base + lm.reach * 2.3f * 0.36, lm.base + 2f * M, 42);
            case RUIN -> built(lm, lm.reach * 2.1f, Math.toRadians(34), lm.base + lm.reach * 2.1f * 0.3, lm.base + lm.tallest() * 0.3, 44);
            // The peak: from where the land falls furthest, a little below its top, looking up at it
            case SUMMIT -> {
                double top = ground(middle);
                yield clearest(middle, 0.0, 8, 80f * M, eye -> Math.max(ground(eye) + 4f * M, top - 14f * M), top - 5f * M, 50);
            }
            // The bay: from its nearest shore, a little inland and up, across its stacks
            case BAY -> {
                SpherePosition target = SphereMath.atAltitude(middle, sea + 4f * M);
                double shore = -1.0, toShore = 0.0;
                for (int k = 0; k < 24; k++) {
                    double a = Math.PI * 2 * k / 24;
                    for (int j = 0; j < 24; j++) {
                        double d = 25f * M + j * 8f * M;
                        if (shore >= 0.0 && d >= shore) break;
                        if (game.groundHeightAt(SphereMath.move(middle, a, d)) > sea) {
                            shore = d;
                            toShore = a;
                            break;
                        }
                    }
                }
                if (shore < 0.0 || shore > 150f * M) yield clearest(middle, 0.0, 8, 90f * M, eye -> sea + 14f * M, sea + 4f * M, 60);
                SpherePosition eye = SphereMath.move(middle, toShore, shore + 8f * M);
                yield new Shot(SphereMath.atAltitude(eye, ground(eye) + 12f * M), target, Math.toRadians(60));
            }
            // The spit: from behind its root and off to one side, up high, along it to its hooked tip
            case SPIT -> {
                float length = lm.span / 2.4f;
                SpherePosition root = SphereMath.move(middle, lm.way + Math.PI, length * 0.5);
                SpherePosition eye = SphereMath.move(root, lm.way + Math.PI - 0.45, 30f * M);
                yield new Shot(SphereMath.atAltitude(eye, Math.max(ground(eye) + 6f * M, sea + 28f * M)), SphereMath.atAltitude(middle, sea + 2f * M), Math.toRadians(55));
            }
            // A field of pinnacles: by the sea, from the land, out over them to the sea; inland,
            // from wherever they're seen best
            case PINNACLES -> {
                float radius = lm.span / 2.4f;
                double over = ground(middle) + 14f * M;
                boolean bySea = !Double.isNaN(lm.way);
                yield clearest(middle, bySea ? lm.way + Math.PI : 0.0, bySea ? 3 : 8, radius + 36f * M,
                        eye -> Math.max(ground(eye) + 9f * M, sea + 12f * M), over, 58);
            }
            case GROVE -> clearest(middle, 0.0, 8, lm.reach + 15f * M, eye -> ground(eye) + 5f * M, ground(middle) + 4f * M, 55);
        };
    }

    /**
     * A house's shot: from before its door and off to one side (the side the sun lights), a
     * little above a visitor's eyes, far enough off to have all of it in the picture. It's drawn
     * on its own (see XenoGuesser_GLEventListener.photoSubject), so nothing can be in the way.
     */
    private Shot houseShot(House house) {
        BuildingStyle style = game.infraManager.styleOf(house);
        float wide = house.houseMaxX() - house.houseMinX(), middleX = (house.houseMinX() + house.houseMaxX()) * 0.5f;
        float tall = house.mainWallHeight + style.roofHeight * house.sizeScale;
        SpherePosition foot = house.frame.toSphere(middleX, 0.0, 0.0);
        double ground = ground(foot);
        float distance = house.depth * 0.5f + Math.max(Math.max(wide * 1.0f, tall * 1.4f), 5f * M);
        SpherePosition target = SphereMath.atAltitude(foot, ground + tall * 0.42);
        // (on the clear day it's photographed on: see clearDay)
        clearDay(foot);
        Vector3 sun = SphereRender.sunDirection(foot, Planet.sunDirection(foot, game.sunDeclination, game.noonLongitude));
        double[] right = house.frame.worldDirection(1.0, 0.0, 0.0);
        double side = right[0] * sun.x + right[1] * sun.y + right[2] * sun.z >= 0.0 ? 0.45 : -0.45;
        SpherePosition at = house.frame.toSphere(middleX + Math.sin(side) * distance, 0.0, Math.cos(side) * distance);
        return new Shot(SphereMath.atAltitude(at, ground + Math.max(2.2f * M, tall * 0.35)), target, Math.toRadians(50));
    }

    /**
     * A built one's shot: distance out from its middle, angle round from its front (or the other
     * side, or further round, should a house stand there or the land be in the way), the eye's
     * and the target's heights above the datum.
     */
    private Shot built(Landmark lm, float distance, double angle, double eyeHeight, double targetHeight, double degrees) {
        SpherePosition target = SphereMath.atAltitude(lm.place(), targetHeight);
        Shot first = null;
        for (double a : new double[] { angle, -angle, angle * 2, -angle * 2, angle * 3, -angle * 3 }) {
            SpherePosition at = lm.frame.toSphere(Math.sin(a) * distance, 0.0, Math.cos(a) * distance);
            Shot shot = new Shot(SphereMath.atAltitude(at, Math.max(eyeHeight, ground(at) + 3f * M)), target, Math.toRadians(degrees));
            if (first == null) first = shot;
            if (!game.infraManager.isBuildingLocation(at, 3f * M) && hidden(shot.eye(), target, 1.5f * M) == 0) return shot;
        }
        return first;
    }

    /**
     * The shot of a natural one seen best: from ways round its middle (starting from one way,
     * spread over a half turn for a few, a whole turn for eight), distance out, the eye at
     * the height given for the place, looking at its middle at a height; the way with the least
     * land in the way between (and not from inside a house).
     */
    private Shot clearest(SpherePosition middle, double from, int ways, double distance,
                          java.util.function.ToDoubleFunction<SpherePosition> eyeHeight, double targetHeight, double degrees) {
        SpherePosition target = SphereMath.atAltitude(middle, targetHeight);
        Shot best = null;
        int fewest = Integer.MAX_VALUE;
        for (int k = 0; k < ways; k++) {
            double a = ways < 8 ? from + (k - (ways - 1) * 0.5) * 0.5 : from + Math.PI * 2 * k / ways;
            SpherePosition at = SphereMath.move(middle, a, distance);
            SpherePosition eye = SphereMath.atAltitude(at, eyeHeight.applyAsDouble(at));
            int hidden = hidden(eye, target, 1.5f * M) + (game.infraManager.isBuildingLocation(at, 3f * M) ? 16 : 0);
            if (hidden < fewest) {
                fewest = hidden;
                best = new Shot(eye, target, Math.toRadians(degrees));
            }
        }
        return best;
    }

    /** How many of the points along the line from the eye to the target have the land within clearance of it (or over it by more than -clearance). */
    private int hidden(SpherePosition eye, SpherePosition target, float clearance) {
        double from = SphereMath.altitude(eye), to = SphereMath.altitude(target);
        SpherePosition a = SphereMath.atAltitude(eye, 0.0), b = SphereMath.atAltitude(target, 0.0);
        int hidden = 0;
        for (int k = 1; k < 16; k++) {
            double t = k / 16.0;
            if (ground(SphereMath.slerp(a, b, t)) > from + (to - from) * t - clearance) hidden++;
        }
        return hidden;
    }
}
