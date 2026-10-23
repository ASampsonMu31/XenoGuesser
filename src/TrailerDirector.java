import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2GL3;
import com.jogamp.opengl.GL3;

/**
 * Developer aid: films the trailer's shots (-Dxenoguesser.trailer=shot list file,
 * -Dxenoguesser.trailerout=folder, -Dxenoguesser.trailershots=name,name to film only some).
 * Each shot is a line of the list: a place found as the developer views find theirs (a landmark,
 * a herd, a street, a landing pod...), the time of day, and either a camera move round it
 * (move=cine) or the player's own controls played as a person would (move=play: keys held, items
 * taken out, the view turned smoothly). Every frame is drawn on a fixed step of the world's time
 * (GameClock.filmStep), however slowly this PC draws it, and saved as a JPEG in a folder of its
 * own; tools/trailer/encode.py makes the clips from them.
 */
final class TrailerDirector extends GamePart {

    static final String LIST = Dev.property("xenoguesser.trailer");

    TrailerDirector(XenoGuesser_GLEventListener game) {
        super(game);
    }

    /** One line of the shot list: its name and its settings (key=value). */
    private record Shot(String name, Map<String, String> settings) {
        String get(String key, String otherwise) {
            return settings.getOrDefault(key, otherwise);
        }

        double number(String key, double otherwise) {
            return Double.parseDouble(get(key, Double.toString(otherwise)));
        }

        double[] numbers(String key, String otherwise) {
            String[] parts = get(key, otherwise).split(",");
            double[] values = new double[parts.length];
            for (int i = 0; i < parts.length; i++) values[i] = Double.parseDouble(parts[i].trim());
            return values;
        }
    }

    /** Something to do partway through a play shot: at seconds in, the action and its arguments. */
    private record Cue(double at, String[] action) {
    }

    private enum Stage { WAITING, LOADING, SETTLING, FILMING, DONE }

    private List<Shot> shots;
    private int shotIndex = -1;
    private Stage stage = Stage.WAITING;
    private int frame, frames, settleFrames;
    // Where the shot is set (the place found and the way it faces), and what it looks at
    private SpherePosition anchor;
    private double anchorHeading, anchorGround;
    // The places along a cine shot's path still to be loaded before filming, nearest first
    private final List<SpherePosition> toLoad = new ArrayList<>();
    private File folder;
    // A play shot: its cues still to come, and the view's turn (current, wanted and how fast it's turning)
    private final List<Cue> cues = new ArrayList<>();
    private double yaw, pitch, yawWanted, pitchWanted, yawSpeed, pitchSpeed;
    private final java.util.concurrent.ExecutorService writers = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "trailer-frame-writer");
        t.setDaemon(true);
        return t;
    });
    // (no more than this many frames waiting to be written, so they can't fill the memory)
    private final java.util.concurrent.Semaphore queued = new java.util.concurrent.Semaphore(12);

    private static final int FPS = 30;

    boolean active() {
        return LIST != null && stage != Stage.DONE;
    }

    /** Before each frame: moves the world's time on a frame, and the shots on from one to the next. */
    void beforeFrame() {
        if (shots == null) {
            shots = readShots();
            GameSettings.devHideFps();
            MapPanel.devClockHeld = true;
            GameClock.filmStep(0);
        }
        GameClock.filmStep(1000 / FPS);
        switch (stage) {
            case WAITING -> nextShot();
            case LOADING -> {
                if (game.devTeleporting()) return;
                if (!toLoad.isEmpty()) {
                    game.devTeleport(new Viewpoint(toLoad.remove(0), anchorHeading));
                    return;
                }
                stage = Stage.SETTLING;
                settleFrames = (int) (shot().number("settle", 3.0) * FPS);
                frame = 0;
                startPlay();
            }
            case SETTLING -> {
                if (--settleFrames <= 0) {
                    stage = Stage.FILMING;
                    frame = 0;
                    folder.mkdirs();
                    // (a play shot's round clock runs from clock= seconds left while it's filmed)
                    MapPanel.devClockFrom = (long) (shot().number("clock", 84) * 1000);
                    MapPanel.devClockHeld = !"play".equals(shot().get("move", "cine"));
                    System.out.println("[TRAILER] Filming " + shot().name() + ", " + frames + " frames");
                }
            }
            default -> { }
        }
    }

    private Shot shot() {
        return shots.get(shotIndex);
    }

    private List<Shot> readShots() {
        List<Shot> list = new ArrayList<>();
        String only = Dev.property("xenoguesser.trailershots");
        java.util.Set<String> wanted = only == null ? null : new java.util.HashSet<>(List.of(only.split(",")));
        try {
            for (String line : java.nio.file.Files.readAllLines(new File(LIST).toPath())) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] words = line.split("\\s+");
                Map<String, String> settings = new HashMap<>();
                for (int i = 1; i < words.length; i++) {
                    int eq = words[i].indexOf('=');
                    if (eq > 0) settings.put(words[i].substring(0, eq), words[i].substring(eq + 1));
                }
                if (wanted == null || wanted.contains(words[0])) list.add(new Shot(words[0], settings));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Couldn't read the trailer's shot list " + LIST, e);
        }
        return list;
    }

    /** On to the next shot: its place found, its day set, and its path loaded. */
    private void nextShot() {
        if (++shotIndex >= shots.size()) {
            finish();
            return;
        }
        Shot s = shot();
        folder = new File(Dev.property("xenoguesser.trailerout", "trailer_frames"), s.name());
        Random rand = new Random(game.worldSeed * 31 + (long) s.number("pick", 0) * 7919 + s.name().hashCode());
        Viewpoint place = find(s, rand);
        if (place == null) {
            System.out.println("[TRAILER] Nothing found for " + s.name() + ", skipped");
            return;
        }
        anchor = SphereMath.atAltitude(place.place(), 0.0);
        anchorHeading = place.heading();
        anchorGround = Math.max(game.seaLevelHeight, game.groundHeightAt(anchor));
        frames = (int) Math.round(s.number("seconds", 5) * FPS);
        game.hideFirstPerson = !"play".equals(s.get("move", "cine"));
        setDay(s, rand);
        toLoad.clear();
        if ("play".equals(s.get("move", "cine"))) {
            toLoad.add(anchor);
        } else {
            for (double t : new double[] { 1.0, 0.5, 0.0 }) toLoad.add(eyeAt(t));
        }
        game.devTeleport(new Viewpoint(toLoad.remove(0), anchorHeading));
        stage = Stage.LOADING;
        System.out.printf(java.util.Locale.ROOT, "[TRAILER] Shot %s at %.3f, %.3f%n", s.name(),
                Math.toDegrees(Planet.latitude(anchor)), Math.toDegrees(Planet.longitude(anchor)));
    }

    /**
     * The shot's place: subject=landmark:SUMMIT (or SPIRE, STONES, RUIN, BAY, SPIT, GROVE; pick=n
     * for another of them), subject=pod (a picturesque landing site, the pod put down there),
     * subject=site (an ordinary round's landing site, pod and all) or subject=view:herd (any of
     * the developer views, see RoundSpawner.devViewpoint).
     */
    private Viewpoint find(Shot s, Random rand) {
        String subject = s.get("subject", "site");
        if (subject.startsWith("landmark:")) {
            List<Landmark> found = new ArrayList<>();
            for (Landmark lm : game.infraManager.landmarks().all()) {
                if (lm.kind.name().equalsIgnoreCase(subject.substring(9))) found.add(lm);
            }
            if (found.isEmpty()) return null;
            Landmark lm = found.get((int) s.number("pick", 0) % found.size());
            SpherePosition at = lm.frame.origin();
            // (facing the way its front does)
            double[] front = lm.frame.worldDirection(0, 0, 1);
            SpherePosition ahead = SphereMath.atAltitude(new SpherePosition(at.x() + front[0], at.y() + front[1], at.z() + front[2]), SphereMath.altitude(at));
            return new Viewpoint(at, SphereMath.heading(at, ahead));
        }
        if (subject.startsWith("view:")) return game.roundSpawner.devViewpoint(subject.substring(5), rand);
        Viewpoint site = "pod".equals(subject) ? game.devShots.picturesqueSite(rand) : game.roundSpawner.landingSite(rand);
        if (site == null) return null;
        game.landingPod.place(site.place(), site.heading(), p -> TerrainMesh.getLayeredHeight(p, game.worldNoise));
        game.infraManager.setKeepClear(game.landingPod.frame().origin(), Math.max(40f, LandingPod.REACH) + 6f);
        // (set at the pod, facing the way its stairs run: subject=site's player stands at the stairs' foot)
        if ("site".equals(subject)) return site;
        SpherePosition pod = game.landingPod.frame().origin();
        return new Viewpoint(pod, SphereMath.heading(pod, site.place()));
    }

    /** The shot's day: hour=17.5 (the local hour by the sun), wet=-3 (a clear day) to 3 (a wet one). */
    private void setDay(Shot s, Random rand) {
        if (game.sphereWeather != null) {
            game.sphereWeather.newDay(rand, anchor);
            if (s.settings().containsKey("wet")) game.sphereWeather.devWetness((float) s.number("wet", 0));
        }
        double hourAngle = (s.number("hour", 15.0) - 12.0) / 24.0 * Math.PI * 2.0;
        game.noonLongitude = Planet.longitude(anchor) - hourAngle;
        game.timeOfDay = (float) (0.5 + hourAngle / (2 * Math.PI));
        game.roundClockStart = game.getSeconds();
        if (game.organismManager != null) game.organismManager.respawn();
    }

    // ------------------------------------------------------------------ cine shots

    /** Smoothstep easing (or none with ease=linear). */
    private double eased(double t) {
        return "linear".equals(shot().get("ease", "smooth")) ? t : t * t * (3 - 2 * t);
    }

    /**
     * A cine shot's eye at t (0 to 1): between from= and to=, each angle (degrees round from the
     * way the place faces), distance and height (above the place's ground, and never under 1.2 m
     * above the ground under the eye).
     */
    private SpherePosition eyeAt(double t) {
        double[] a = shot().numbers("from", "180,300,60"), b = shot().numbers("to", shot().get("from", "180,300,60"));
        double angle = Math.toRadians(a[0] + (b[0] - a[0]) * t);
        double distance = a[1] + (b[1] - a[1]) * t, height = a[2] + (b[2] - a[2]) * t;
        SpherePosition at = SphereMath.move(anchor, anchorHeading + angle, distance);
        double ground = Math.max(game.seaLevelHeight, game.groundHeightAt(at));
        return SphereMath.atAltitude(at, Math.max(anchorGround + height, ground + 12.0));
    }

    /** Where a cine shot looks at t: the place, aim= above its ground (from,to); or with look=ahead, yaw= and pitch= degrees. */
    private void aim(double t, SpherePosition eye) {
        if ("ahead".equals(shot().get("look", "subject"))) {
            double[] y = shot().numbers("yaw", "0,0"), p = shot().numbers("pitch", "0,0");
            double heading = SphereMath.carryHeading(anchor, eye, anchorHeading) + Math.toRadians(y[0] + (y[y.length - 1] - y[0]) * t);
            game.camera.setSphereView(eye, (float) Math.toDegrees(heading), (float) (p[0] + (p[p.length - 1] - p[0]) * t));
            return;
        }
        double[] h = shot().numbers("aim", "30");
        double lift = h[0] + (h[h.length - 1] - h[0]) * t;
        SpherePosition target = anchor;
        if ("herd".equals(shot().get("look", "subject")) && game.organismManager != null) {
            SpherePosition herd = game.organismManager.nearestHerd(eye);
            if (herd != null) target = herd;
        }
        target = SphereMath.atAltitude(target, Math.max(game.seaLevelHeight, game.groundHeightAt(target)) + lift);
        game.camera.setSphereView(eye, 0f, 0f);
        game.camera.lookAt(target);
    }

    // ------------------------------------------------------------------ play shots

    /**
     * A play shot's cues (do=seconds:action:args;...): w+ / w- (walk), shift+ / shift-, jump,
     * item:n (take out the inventory's nth item), look:yaw:pitch (turn to that many degrees from
     * the way the shot faces, as a hand on a mouse would). The player starts looking along
     * yaw0=/pitch0=.
     */
    private void startPlay() {
        cues.clear();
        if (!"play".equals(shot().get("move", "cine"))) return;
        for (String cue : shot().get("do", "").split(";")) {
            if (cue.isBlank()) continue;
            String[] parts = cue.split(":");
            cues.add(new Cue(Double.parseDouble(parts[0]), java.util.Arrays.copyOfRange(parts, 1, parts.length)));
        }
        cues.sort((x, y) -> Double.compare(x.at(), y.at()));
        yaw = yawWanted = Math.toDegrees(anchorHeading) + shot().number("yaw0", 0);
        pitch = pitchWanted = shot().number("pitch0", -4);
        yawSpeed = pitchSpeed = 0;
        game.keyboard.releaseAll();
    }

    private void cue(String[] action) {
        switch (action[0]) {
            case "w+" -> game.keyboard.w = true;
            case "w-" -> game.keyboard.w = false;
            case "a+" -> game.keyboard.a = true;
            case "a-" -> game.keyboard.a = false;
            case "d+" -> game.keyboard.d = true;
            case "d-" -> game.keyboard.d = false;
            case "shift+" -> game.keyboard.shift = true;
            case "shift-" -> game.keyboard.shift = false;
            case "jump" -> game.jump();
            case "item" -> game.useItem(Integer.parseInt(action[1]));
            case "look" -> {
                yawWanted = Math.toDegrees(anchorHeading) + Double.parseDouble(action[1]);
                pitchWanted = Double.parseDouble(action[2]);
            }
            default -> System.out.println("[TRAILER] Unknown cue " + action[0]);
        }
    }

    /**
     * After the player's moved each frame: a cine shot's camera put where its path has it; a
     * play shot's cues acted on and its view turned, eased in and out by a critically damped
     * spring with a little unsteadiness, as a hand on a mouse turns it.
     */
    void placeCamera() {
        if (stage != Stage.SETTLING && stage != Stage.FILMING) return;
        double seconds = (stage == Stage.FILMING ? frame : 0) / (double) FPS;
        if ("play".equals(shot().get("move", "cine"))) {
            if (stage == Stage.FILMING) {
                while (!cues.isEmpty() && cues.get(0).at() <= seconds) cue(cues.remove(0).action());
            }
            double dt = 1.0 / FPS, stiffness = shot().number("turn", 5.0);
            yawSpeed += (stiffness * stiffness * (yawWanted - yaw) - 2 * stiffness * yawSpeed) * dt;
            pitchSpeed += (stiffness * stiffness * (pitchWanted - pitch) - 2 * stiffness * pitchSpeed) * dt;
            yaw += yawSpeed * dt;
            pitch += pitchSpeed * dt;
            double time = game.getSeconds();
            double wobbleYaw = 0.35 * Math.sin(time * 0.71) + 0.2 * Math.sin(time * 1.93 + 1.0);
            double wobblePitch = 0.25 * Math.sin(time * 0.53 + 2.0) + 0.12 * Math.sin(time * 2.31);
            game.camera.setSphereView(game.camera.getSpherePosition(), (float) (yaw + wobbleYaw), (float) (pitch + wobblePitch));
            return;
        }
        double t = eased(Math.min(1.0, seconds / Math.max(1e-6, frames / (double) FPS)));
        SpherePosition eye = eyeAt(t);
        aim(t, eye);
    }

    /** At the end of each frame drawn (HUD and all): saved while filming. */
    void afterFrame(GL3 gl, int width, int height) {
        if (stage != Stage.FILMING) return;
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 3);
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl.glReadBuffer(GL.GL_BACK);
        gl.glReadPixels(0, 0, width, height, GL2GL3.GL_BGR, GL.GL_UNSIGNED_BYTE, pixels);
        File file = new File(folder, String.format("f%05d.jpg", frame + 1));
        queued.acquireUninterruptibly();
        writers.submit(() -> {
            try {
                write(pixels, width, height, file);
            } finally {
                queued.release();
            }
        });
        if (++frame >= frames) {
            game.keyboard.releaseAll();
            MapPanel.devClockHeld = true;
            stage = Stage.WAITING;
        }
    }

    private static void write(ByteBuffer pixels, int width, int height, File file) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        int row = width * 3;
        // (the GL's rows run bottom up)
        for (int y = 0; y < height; y++) pixels.get((height - 1 - y) * row, data, y * row, row);
        try {
            javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next();
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.93f);
            try (javax.imageio.stream.ImageOutputStream out = javax.imageio.ImageIO.createImageOutputStream(file)) {
                writer.setOutput(out);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            }
            writer.dispose();
        } catch (java.io.IOException e) {
            System.err.println("Couldn't save a trailer frame: " + e.getMessage());
        }
    }

    /** Every shot filmed: once the frames are written, the game's closed. */
    private void finish() {
        stage = Stage.DONE;
        System.out.println("[TRAILER] All shots filmed");
        writers.shutdown();
        Thread closer = new Thread(() -> {
            try {
                writers.awaitTermination(5, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }, "trailer-closer");
        closer.start();
    }
}
