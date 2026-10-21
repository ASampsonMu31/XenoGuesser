import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Matrix4Transform;
import com.xenoguesser.math.Vector3;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The opening of a game, from Play to the round: the player stands inside the landing pod as
 * it comes down to the planet, free to walk, jump and look round in it, while the world is
 * made. It owns the 3D view from the start: the game's own drawing (see
 * XenoGuesser_GLEventListener) is handed to it once the world is far enough along to have one,
 * its loading steps run each frame under the pod's, and once the player opens the hatch the
 * view is the game's alone.
 */
public class PodSequence implements GLEventListener {

    /** What the pod asks of the game round it. */
    interface Host {
        /** Whether the world's ready for the door to be opened. */
        boolean worldReady();

        /** E at the hatch. */
        void openHatch();

        /** The pod's first frame has been drawn (on the UI thread). */
        void showing();

        /** How opaque the quarter's targets are to be shown just now (singleplayer's message speaks of them), 0 for not. */
        float targetsAlpha();
    }

    private static final float FIELD_OF_VIEW = 55f, NEAR = 0.5f, FAR = 600f;
    // How near the player must be to something, and how squarely facing it, to use it
    // (reach is measured along the floor, from where the player stands)
    private static final float FACING = 0.8f;
    private static final String OPEN = "Open the door";

    private final Host host;
    private final boolean multiplayer;
    private final PodInterior interior;
    private final PodPhysics physics = new PodPhysics();
    private final PlayerBody body = new PlayerBody();
    private final HudOverlay hud = new HudOverlay();
    private final PodInterior.Lighting lighting = new PodInterior.Lighting();
    private final PodTerminal terminal;
    private final PodTimeline timeline = new PodTimeline();
    private final PodSpaceView space;
    private final PodSounds sounds = new PodSounds();
    // Multiplayer: the partner in the pod with this player, as last heard {x, y, z, yaw} and eased towards it
    private final PlayerBody partnerBody;
    private final float[] partner = new float[4];
    private boolean partnerSeen;
    private final Multiplayer.Role role;
    // The landing's jolt, felt as a dip of the view that springs back (units, and their speed)
    private float bump, bumpSpeed;

    // The game's drawing, once there is one, whether it's been started on this view, and
    // whether the pod has handed the view over to it
    private volatile XenoGuesser_GLEventListener game;
    private boolean gameStarted;
    private volatile boolean handedOver;
    private LoadingProgress progress;

    private int width = 1, height = 1;
    private float pixelScale = 1f;
    private boolean ready, shown;
    private final long startNanos = System.nanoTime();
    private long lastNanos = startNanos;

    // Input, gathered on the UI thread and taken at the start of each frame
    private volatile boolean forward, back, left, right;
    private volatile boolean jumpWanted, useWanted;
    private float lookYaw, lookPitch;
    private String promptShown;
    // The wheeled chair, and the furniture it and the player bump into
    private final PodChair chair = new PodChair();
    private final java.util.List<float[]> furniture = PodFurniture.obstacles();
    // The lights: off for LIGHTS_OFF seconds from the switch's being pressed (time, or -1 when
    // on), the lamp's brightness and the lever's swing easing after
    static final float LIGHTS_OFF = 5f;
    private float lightsOffAt = -1f, cabinLight = 1f, lever;
    private float now;

    public PodSequence(Host host, boolean multiplayer, LoadingProgress progress, long worldSeed) {
        this.host = host;
        this.multiplayer = multiplayer;
        this.progress = progress;
        interior = new PodInterior(multiplayer);
        space = new PodSpaceView(worldSeed);
        // (the condensation on the door the faint colour of the planet's seas)
        interior.setWaterTint(MapColours.of(worldSeed)[0]);
        Multiplayer session = Multiplayer.active();
        role = multiplayer ? (session != null ? session.role : Multiplayer.Role.HOST) : null;
        terminal = new PodTerminal(worldSeed, role);
        // In multiplayer this player's suit in their colour, and the partner's in theirs (as in the game)
        partnerBody = role == null ? null : PlayerBody.seenFromOutside();
        if (role != null) {
            body.setColours(new float[] { 0.5f, 0.51f, 0.54f }, role.trimColour());
            partnerBody.setColours(new float[] { 0.2f, 0.21f, 0.23f }, role.other().litTrimColour());
        }
        for (float[] box : furniture) physics.addBox(box);
        // In from the computer at the back, facing the door (in multiplayer the host on the left, the joiner on the right)
        physics.place(role == null ? 0f : role == Multiplayer.Role.HOST ? 5f : -5f, -4f, (float) (Math.PI * 0.5));
        // Developer aid: -Dxenoguesser.podview=x,z,yaw,pitch (degrees) stands the player there
        String view = Dev.property("xenoguesser.podview");
        if (view != null) {
            String[] v = view.split(",");
            physics.place(Float.parseFloat(v[0]), Float.parseFloat(v[1]), (float) Math.toRadians(Float.parseFloat(v[2])));
            if (v.length > 3) physics.pitch = (float) Math.toRadians(Float.parseFloat(v[3]));
        }
    }

    // Developer aid: -Dxenoguesser.podstalls logs every frame of the pod's that takes over 60 ms, and where the time went
    private static final boolean STALLS = Dev.property("xenoguesser.podstalls") != null;
    static {
        if (STALLS) {
            watchUiThread();
            watchFrames();
        }
    }
    // When the pod's last frame was drawn, and the thread drawing it (for watchFrames)
    private static volatile long lastFrameAt;
    private static volatile Thread drawing;

    /**
     * Developer aid (with podstalls): whenever the whole program stops (this watcher's own short
     * sleep overruns), or the pod's gone 400 ms without a frame, says so, and what the drawing
     * and UI threads are doing.
     */
    private static void watchFrames() {
        Thread watch = new Thread(() -> {
            boolean told = false;
            while (true) {
                long before = System.nanoTime();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.nanoTime(), overslept = (now - before) / 1_000_000L - 20;
                if (overslept > 150) System.out.printf("[STALL] Whole program held up about %d ms%n", overslept);
                long since = lastFrameAt == 0L ? 0L : (now - lastFrameAt) / 1_000_000L;
                if (since > 400 && !told && drawing != null) {
                    told = true;
                    StringBuilder trace = new StringBuilder();
                    for (StackTraceElement e : drawing.getStackTrace()) {
                        if (trace.length() > 1600) break;
                        trace.append("\n    at ").append(e);
                    }
                    System.out.printf("[STALL] No pod frame for %d ms; drawing thread %s:%s%n", since, drawing.getState(), trace);
                } else if (since < 100) {
                    told = false;
                }
            }
        }, "frame-watch");
        watch.setDaemon(true);
        watch.setPriority(Thread.MAX_PRIORITY);
        watch.start();
    }

    /** Developer aid (with podstalls): says what the UI thread was doing whenever it's held up for over 200 ms. */
    private static void watchUiThread() {
        Thread watch = new Thread(() -> {
            Thread[] ui = new Thread[1];
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> ui[0] = Thread.currentThread());
            } catch (Exception e) {
                return;
            }
            while (true) {
                long posted = System.nanoTime();
                java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
                javax.swing.SwingUtilities.invokeLater(() -> ran.set(true));
                boolean told = false;
                while (!ran.get()) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!told && System.nanoTime() - posted > 200_000_000L) {
                        told = true;
                        StringBuilder trace = new StringBuilder();
                        StackTraceElement[] stack = ui[0].getStackTrace();
                        for (int i = 0; i < Math.min(14, stack.length); i++) trace.append("\n    at ").append(stack[i]);
                        System.out.println("[STALL] UI thread held up: " + trace);
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "ui-watch");
        watch.setDaemon(true);
        watch.start();
    }
    private long lastFrameEnd;

    // Developer aid: -Dxenoguesser.podshot=3,8,20 saves a picture of the pod that many seconds
    // in (pod_shot_1.png and on, beside the world's files), and how many have been saved
    private static final String SHOTS = Dev.property("xenoguesser.podshot");
    private int shotsTaken;

    private void takeShots(GL3 gl, float time) {
        if (SHOTS == null) return;
        String[] at = SHOTS.split(",");
        if (shotsTaken >= at.length || time < Float.parseFloat(at[shotsTaken])) return;
        shotsTaken++;
        int w = width, h = height;
        java.nio.ByteBuffer pixels = com.jogamp.common.nio.Buffers.newDirectByteBuffer(w * h * 4);
        gl.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels);
        java.io.File file = new java.io.File(new java.io.File(RunFiles.WORLD_DIR).getParentFile(), "pod_shot_" + shotsTaken + ".png");
        Thread writer = new Thread(() -> {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    image.setRGB(x, h - 1 - y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
                }
            }
            try {
                javax.imageio.ImageIO.write(image, "png", file);
                System.out.println("[POD] Saved " + file);
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
        }, "pod-shot");
        writer.setDaemon(true);
        writer.start();
    }

    /** The planet as seen coming in (see PlanetGlobeTexture), from any thread once it's made. */
    public void setGlobe(BufferedImage globe) {
        space.setGlobe(globe);
    }

    /** The share of the world loaded on both PCs (the less of the two). */
    private float loadedEverywhere() {
        float here = progress.overallFraction();
        Multiplayer session = multiplayer ? Multiplayer.active() : null;
        return session == null ? here : Math.min(here, session.remoteProgress());
    }

    /** The partner's share loaded (multiplayer). */
    private float partnerLoaded() {
        Multiplayer session = multiplayer ? Multiplayer.active() : null;
        return session == null ? 0f : session.remoteProgress();
    }

    /** Whether the world's ready on both PCs. */
    private boolean readyEverywhere() {
        if (!progress.isFinished()) return false;
        Multiplayer session = multiplayer ? Multiplayer.active() : null;
        return session == null || session.remoteWorldReady();
    }

    /** The game's own drawing, ready to be started on this view and to load under the pod. */
    public void attachGame(XenoGuesser_GLEventListener listener) {
        listener.deferFirstFrames();
        game = listener;
    }

    // The hatch swinging open, from when (-1 shut), and what's done once it's open
    private static final float HATCH_OPENING = 1.8f;
    private volatile float hatchOpenedAt = -1f;
    // Whether the door's been asked to open (here, or by the partner)
    private boolean doorOpening;
    // Multiplayer: whether this player and the partner have said they're ready (R), and R pressed
    private boolean saidReady, partnerReady;
    private volatile boolean readyWanted;
    private volatile Runnable afterHatch;

    /** Swings the hatch open, the daylight flooding in, then runs then (on the UI thread). */
    public void openHatchThen(Runnable then) {
        afterHatch = then;
        hatchOpenedAt = now;
    }

    /** From now on the view is the game's (the pod's sounds dying away a moment after). */
    public void handOver() {
        handedOver = true;
        System.out.println("[POD] Out of the hatch: the game's view from now on");
        javax.swing.Timer quiet = new javax.swing.Timer(1500, e -> sounds.stop());
        quiet.setRepeats(false);
        quiet.start();
    }

    public boolean handedOver() {
        return handedOver;
    }

    // ==========================================
    //          INPUT
    // ==========================================

    /** W, A, S, D held or let go. */
    public void setKeys(boolean w, boolean a, boolean s, boolean d) {
        forward = w;
        left = a;
        back = s;
        right = d;
    }

    public void jump() {
        jumpWanted = true;
    }

    /** R (multiplayer): this player's ready to go. */
    public void ready() {
        readyWanted = true;
    }

    /** E: uses whatever the player is facing. */
    public void use() {
        useWanted = true;
    }

    /** Mouse look, as the game's look() takes it. */
    public synchronized void look(float yaw, float pitch) {
        lookYaw += yaw;
        lookPitch += pitch;
    }

    private synchronized float[] takeLook() {
        float[] look = { lookYaw, lookPitch };
        lookYaw = 0f;
        lookPitch = 0f;
        return look;
    }

    // ==========================================
    //          GL
    // ==========================================

    @Override
    public void init(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        // (which graphics card and driver, should anything go wrong: see CrashLog)
        CrashLog.setRenderer(gl.glGetString(GL.GL_RENDERER) + ", OpenGL " + gl.glGetString(GL.GL_VERSION));
        // (the frames come first: the world's made on lesser threads)
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 1);
        interior.initialise(gl);
        space.initialise(gl);
        sounds.start();
        body.initialise(gl);
        if (partnerBody != null) partnerBody.initialise(gl);
        hud.initialise(gl);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(GL.GL_LESS);
        ready = true;
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        pixelScale = drawable instanceof java.awt.Component c && c.getWidth() > 0 ? width / (float) c.getWidth() : 1f;
        XenoGuesser_GLEventListener g = game;
        if (g != null && gameStarted) g.reshape(drawable, x, y, w, h);
    }

    /**
     * A frame, the pod's or (once handed over) the game's: anything thrown in one is written to the
     * crash log and the next frame comes regardless, where otherwise the animator would stop and the
     * view freeze for good.
     */
    @Override
    public void display(GLAutoDrawable drawable) {
        try {
            frame(drawable);
        } catch (RuntimeException | OutOfMemoryError e) {
            CrashLog.write("A frame failed" + (handedOver ? "" : " (in the pod)"), e);
            e.printStackTrace();
        }
    }

    private void frame(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        XenoGuesser_GLEventListener g = game;
        long frameStart = System.nanoTime();
        if (STALLS) {
            drawing = Thread.currentThread();
            lastFrameAt = frameStart;
        }
        if (g != null && !gameStarted) {
            g.init(drawable);
            g.reshape(drawable, 0, 0, width, height);
            gameStarted = true;
        }
        if (handedOver) {
            if (g != null) g.display(drawable);
            return;
        }
        // The world's loading steps, under the pod's frame (the game draws nothing to be seen
        // until it's entered; once loaded, it's left alone until then)
        if (g != null && !progress.isFinished()) g.display(drawable);
        long loaded = System.nanoTime();

        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float time = (now - startNanos) / 1e9f;

        update(dt);
        draw(gl, time);
        takeShots(gl, time);
        if (STALLS) {
            long end = System.nanoTime();
            // (a long frame: the time between frames, and in this one the game's loading step and the pod's own)
            if (lastFrameEnd != 0L && end - lastFrameEnd > 60_000_000L) {
                System.out.printf("[STALL] %d ms frame at %.1f s (%s): %d ms before it began, %d ms loading, %d ms the pod%n",
                        (end - lastFrameEnd) / 1_000_000, time, progress.currentLabel(), (frameStart - lastFrameEnd) / 1_000_000,
                        (loaded - frameStart) / 1_000_000, (end - loaded) / 1_000_000);
            }
            lastFrameEnd = end;
        } else {
            lastFrameEnd = System.nanoTime();
        }
        if (!shown) {
            shown = true;
            javax.swing.SwingUtilities.invokeLater(host::showing);
        }
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        sounds.stop();
        interior.dispose(gl);
        space.dispose(gl);
        body.dispose(gl);
        if (partnerBody != null) partnerBody.dispose(gl);
        hud.dispose(gl);
        XenoGuesser_GLEventListener g = game;
        if (g != null && gameStarted) g.dispose(drawable);
    }

    // ==========================================
    //          A FRAME
    // ==========================================

    // The things that can be used (E), each the point the player must face and how near they must be
    private enum Target { SWITCH, CHAIR, HATCH }

    private void update(float dt) {
        now += dt;
        float[] look = takeLook();
        // (as the planet's camera turns: degrees a unit of look)
        physics.look(look[0] * 100f, look[1] * 100f);
        if (jumpWanted) {
            jumpWanted = false;
            physics.jump();
        }
        // The way down: what's out of the window, how gravity's felt, and the jolts on the way
        Multiplayer session = multiplayer ? Multiplayer.active() : null;
        if (session != null) session.publishProgress(progress.overallFraction());
        timeline.step(dt, loadedEverywhere(), readyEverywhere(), space);
        space.update(dt);
        System.arraycopy(timeline.gravity, 0, physics.gravity, 0, 3);
        for (PodTimeline.Event event = timeline.nextEvent(); event != null; event = timeline.nextEvent()) happened(event);
        bumpSpeed += (-bump * 160f - bumpSpeed * 9f) * dt;
        bump += bumpSpeed * dt;
        sharePod(dt);
        java.util.List<float[]> others = new java.util.ArrayList<>();
        others.add(new float[] { chair.x, chair.z, PodFurniture.CHAIR_RADIUS });
        if (partnerSeen) others.add(new float[] { partner[0], partner[2], PodPhysics.RADIUS });
        physics.setCircles(others);
        physics.step(dt, (forward ? 1f : 0f) - (back ? 1f : 0f), (right ? 1f : 0f) - (left ? 1f : 0f));
        body.update(dt, physics.moved, false);
        if (physics.hitLamp) Achievements.unlock(Achievements.Achievement.LIGHTS_OUT);
        java.util.List<float[]> players = new java.util.ArrayList<>();
        players.add(new float[] { physics.x, physics.z, PodPhysics.RADIUS });
        if (partnerSeen) players.add(new float[] { partner[0], partner[2], PodPhysics.RADIUS });
        chair.step(dt, physics.gravity, furniture, players);
        updateLights(dt);
        updateReady();
        updateHatch();
        updateTargets();
        sounds.set(timeline.engine, timeline.roar, timeline.rumble, chair.y == 0f ? (float) Math.hypot(chair.vx, chair.vz) / PodChair.PUSH_SPEED : 0f);
        devTests();

        Target target = facing();
        String prompt = target == null ? null : switch (target) {
            case SWITCH -> "Turn off the lights";
            case CHAIR -> "Push the chair";
            case HATCH -> doorPrompt();
        };
        boolean onlySaid = target == Target.HATCH && prompt != null && !OPEN.equals(prompt);
        if (useWanted) {
            useWanted = false;
            // (the partner's not being ready is only said: there's nothing to do)
            if (prompt != null && !onlySaid) use(target);
        }
        showPrompt(prompt, onlySaid ? "" : "E");
    }

    /** Whether the door can be opened yet: the pod's landed and the world's ready (in multiplayer, once this player's said they're ready). */
    private boolean doorAvailable() {
        return timeline.landed() && host.worldReady() && !doorOpening && (role == null || saidReady);
    }

    /** What E at the door would do: open it; in multiplayer, if the partner's not ready, only say so; null if nothing yet. */
    private String doorPrompt() {
        if (!doorAvailable()) return null;
        if (role != null && !partnerReady) return "Employee " + role.other().label + " not ready";
        return OPEN;
    }

    /** The door's opened, here or by the partner: it swings open for both, and the game starts. */
    private void openDoor(boolean tellPartner) {
        if (doorOpening) return;
        doorOpening = true;
        sounds.play(PodSounds.Shot.HATCH);
        Multiplayer session = role == null ? null : Multiplayer.active();
        if (tellPartner && session != null) session.sendBegin();
        javax.swing.SwingUtilities.invokeLater(host::openHatch);
    }

    /** Multiplayer: R pressed once the door's there to open, and the partner opening it. */
    private void updateReady() {
        Multiplayer session = role == null ? null : Multiplayer.active();
        boolean wanted = readyWanted;
        readyWanted = false;
        if (session == null) return;
        if (wanted && !saidReady && timeline.landed() && host.worldReady()) {
            saidReady = true;
            session.sendPodEvent(Multiplayer.POD_READY, 0f, 0f);
            System.out.println("[POD] Ready");
        }
        // The partner opened the door: it opens here too
        if (session.remoteBegun() && saidReady && partnerReady) openDoor(false);
    }

    /** The hatch swinging out on its hinge (on the right as one faces it), and the light flooding in. */
    private void updateHatch() {
        float opened = hatchOpenedAt < 0f ? 0f : (now - hatchOpenedAt) / HATCH_OPENING;
        float swing = (float) (Math.toRadians(105.0) * smoothStep(opened * 1.6f));
        float[] hinge = { -interior.doorHalfWidth(), interior.doorMiddle(), PodInterior.radiusAt(interior.doorMiddle()) + PodInterior.WALL * 0.45f };
        interior.setBone(PodInterior.HATCH, Affine.multiply(Affine.translation(hinge[0], hinge[1], hinge[2]),
                Affine.multiply(Affine.rotationY(swing), Affine.translation(-hinge[0], -hinge[1], -hinge[2]))));
        float white = smoothStep((opened - 0.35f) / 0.65f);
        if (white > 0f) {
            if (!hud.has("daylight") || hud.width("daylight") != width) {
                BufferedImage light = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = light.createGraphics();
                g.setColor(new java.awt.Color(255, 252, 245));
                g.fillRect(0, 0, width, height);
                g.dispose();
                hud.put("daylight", light, 0, 0);
            }
            hud.setVisible("daylight", true);
            hud.setAlpha("daylight", white);
        }
        if (opened >= 1f && afterHatch != null) {
            Runnable then = afterHatch;
            afterHatch = null;
            javax.swing.SwingUtilities.invokeLater(then);
        }
    }

    private static float smoothStep(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    // The targets' picture, and when it was last drawn (it bobs, so it's drawn again a few times a second)
    private long targetsDrawn;

    /** Singleplayer: the quarter's targets at the top left while the message speaks of them. */
    private void updateTargets() {
        float alpha = host.targetsAlpha();
        if (alpha <= 0f) {
            if (hud.has("targets")) hud.setVisible("targets", false);
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (!hud.has("targets") || nowMs - targetsDrawn > 50) {
            targetsDrawn = nowMs;
            java.awt.Dimension size = LoadingScreen.targetsSize();
            Graphics2D[] g = new Graphics2D[1];
            BufferedImage image = HudStyle.canvas(size.width, size.height, pixelScale, g);
            LoadingScreen.paintTargets(g[0]);
            g[0].dispose();
            int margin = Math.round(HudStyle.HUD_MARGIN * pixelScale);
            hud.put("targets", image, margin, margin);
        }
        hud.setVisible("targets", true);
        hud.setAlpha("targets", alpha);
    }

    /** Something that happens once on the way down: the pod's jolted, and so's everything loose in it. */
    private void happened(PodTimeline.Event event) {
        switch (event) {
            case RELEASED -> {
                sounds.play(PodSounds.Shot.CLUNK);
                // Let go with a clunk: a kick up and back
                physics.shove(0f, 14f, -10f);
                chair.vy += 10f;
                chair.vx += 4f;
                bumpSpeed += 25f;
            }
            case THRUSTERS -> {
                sounds.play(PodSounds.Shot.THRUSTER);
                physics.shove(0f, 0f, 6f);
            }
            case ENTRY -> bumpSpeed -= 12f;
            case UPRIGHTING -> {
                sounds.play(PodSounds.Shot.THRUSTER);
                physics.shove(0f, 8f, 0f);
            }
            case LANDED -> {
                System.out.println("[POD] Landed");
                // Down with a thump: everything jumps
                sounds.play(PodSounds.Shot.LANDING);
                physics.shove(0f, 22f, 0f);
                chair.vy += 22f;
                chair.spin += 1.5f;
                bumpSpeed -= 70f;
            }
        }
    }

    /**
     * Multiplayer: this player's place in the pod sent, the partner's taken; the lights and the
     * chair kept the same on both PCs (the chair as the host's PC has it).
     */
    private void sharePod(float dt) {
        Multiplayer session = role == null ? null : Multiplayer.active();
        if (session == null) return;
        session.publishPodPose(new float[] { physics.x, physics.y, physics.z, physics.yaw, physics.pitch });
        float[] heard = session.remotePodPose();
        float partnerMoved = 0f;
        if (heard != null) {
            if (!partnerSeen) {
                System.arraycopy(heard, 0, partner, 0, 4);
                partnerSeen = true;
            } else {
                // Eased towards where they were last heard of (twenty times a second), so they glide
                float ease = Math.min(1f, dt * 14f);
                float ox = partner[0], oz = partner[2];
                for (int i = 0; i < 3; i++) partner[i] += (heard[i] - partner[i]) * ease;
                partner[3] += (float) Math.atan2(Math.sin(heard[3] - partner[3]), Math.cos(heard[3] - partner[3])) * ease;
                partnerMoved = (float) Math.hypot(partner[0] - ox, partner[2] - oz);
            }
        }
        if (partnerBody != null) partnerBody.update(dt, partnerMoved, false);
        for (float[] event = session.nextPodEvent(); event != null; event = session.nextPodEvent()) {
            if ((byte) event[0] == Multiplayer.POD_LIGHTS_OFF) lightsOff();
            else if ((byte) event[0] == Multiplayer.POD_CHAIR_PUSH && role == Multiplayer.Role.HOST) chair.push(event[1], event[2], 1f);
            else if ((byte) event[0] == Multiplayer.POD_READY) partnerReady = true;
        }
        if (role == Multiplayer.Role.HOST) {
            session.publishChair(new float[] { chair.x, chair.z, chair.vx, chair.vz, chair.angle });
        } else {
            float[] host = session.remoteChair();
            if (host != null) {
                // (drawn towards the host's, which is the one that counts)
                float pull = Math.min(1f, dt * 10f);
                chair.set(chair.x + (host[0] - chair.x) * pull, chair.z + (host[1] - chair.z) * pull, host[2], host[3],
                        chair.angle + (float) Math.atan2(Math.sin(host[4] - chair.angle), Math.cos(host[4] - chair.angle)) * pull);
            }
        }
    }

    private void use(Target target) {
        Multiplayer session = role == null ? null : Multiplayer.active();
        switch (target) {
            case SWITCH -> {
                lightsOff();
                if (session != null) session.sendPodEvent(Multiplayer.POD_LIGHTS_OFF, 0f, 0f);
            }
            case CHAIR -> {
                chair.push(physics.x, physics.z, 1f);
                if (lightsOffAt >= 0f && now - lightsOffAt < LIGHTS_OFF) Achievements.unlock(Achievements.Achievement.MOVES_IN_THE_DARK);
                // (the host's PC moves the chair for both; the joiner's pushes it too, for now, and follows)
                if (session != null && role == Multiplayer.Role.JOIN) session.sendPodEvent(Multiplayer.POD_CHAIR_PUSH, physics.x, physics.z);
            }
            case HATCH -> openDoor(true);
        }
    }

    // Developer aid: -Dxenoguesser.podtest=lights,chair turns the lights off and pushes the chair 5 s in
    private static final String TESTS = Dev.property("xenoguesser.podtest", "");
    private boolean tested;

    private void devTests() {
        // (=hatch says it's ready and opens the door as soon as it can; in multiplayer
        // -Dxenoguesser.mpautobegin says it's ready, and the host opens the door)
        boolean auto = TESTS.contains("hatch") || (role != null && Dev.property("xenoguesser.mpautobegin") != null);
        if (auto && role != null && !saidReady) readyWanted = true;
        if (auto && OPEN.equals(doorPrompt()) && (role != Multiplayer.Role.JOIN || TESTS.contains("hatch"))) openDoor(true);
        if (tested || now < 5f) return;
        tested = true;
        if (TESTS.contains("lights")) lightsOff();
        if (TESTS.contains("chair")) chair.push(chair.x + 5f, chair.z, 1f);
    }

    /** The lights go off, for LIGHTS_OFF seconds. */
    void lightsOff() {
        if (lightsOffAt >= 0f) return;
        lightsOffAt = now;
        sounds.play(PodSounds.Shot.SWITCH);
    }

    /** The lamp fades out at once, comes back with a flicker after LIGHTS_OFF seconds, the lever swinging down and back. */
    private void updateLights(float dt) {
        float off = lightsOffAt < 0f ? -1f : now - lightsOffAt;
        float wanted;
        if (off < 0f) {
            wanted = 1f;
        } else if (off < LIGHTS_OFF) {
            wanted = 0f;
        } else if (off < LIGHTS_OFF + 0.6f) {
            // A fluorescent's stutter as it comes on
            float t = off - LIGHTS_OFF;
            wanted = t < 0.08f || (t > 0.18f && t < 0.24f) || t > 0.4f ? 1f : 0.15f;
        } else {
            wanted = 1f;
            lightsOffAt = -1f;
        }
        // (the switch springs back with a click)
        if (off >= LIGHTS_OFF && off - dt < LIGHTS_OFF) sounds.play(PodSounds.Shot.SWITCH);
        cabinLight = wanted < cabinLight ? Math.max(wanted, cabinLight - dt / 0.15f) : wanted;
        float leverWanted = off >= 0f && off < LIGHTS_OFF ? 1f : 0f;
        lever += (leverWanted - lever) * Math.min(1f, dt * (leverWanted > lever ? 30f : 14f));
    }

    /** What the player is near enough to use and facing most squarely, if anything. */
    private Target facing() {
        float[] eye = physics.eye(), f = physics.forward();
        Target best = null;
        float bestFacing = FACING;
        for (Target t : Target.values()) {
            if (t == Target.SWITCH && lightsOffAt >= 0f) continue;
            // (once down, only the door's to be used)
            if (t != Target.HATCH && timeline.landed()) continue;
            float[] at = switch (t) {
                case SWITCH -> PodFurniture.switchPosition();
                case CHAIR -> new float[] { chair.x, PodInterior.FLOOR + chair.y + 7f, chair.z };
                case HATCH -> new float[] { 0f, interior.doorMiddle(), PodInterior.radiusAt(interior.doorMiddle()) };
            };
            float reach = switch (t) {
                case SWITCH -> 9f;
                case CHAIR -> 8.5f + PodFurniture.CHAIR_RADIUS;
                case HATCH -> 16f;
            };
            float[] to = Affine.subtract(at, eye);
            float distance = (float) Math.hypot(at[0] - physics.x, at[2] - physics.z);
            float squarely = Affine.dot(Affine.normalise(to), f);
            // (the nearer it is, the less squarely it must be faced)
            float needed = bestFacing - (t == Target.CHAIR ? 0.1f : 0f);
            if (distance < reach && squarely > needed) {
                best = t;
                bestFacing = squarely;
            }
        }
        return best;
    }

    /** The key hint for what E would do, low in the middle of the screen; null for none. */
    private void showPrompt(String prompt, String key) {
        if (prompt == null) {
            if (hud.has("prompt")) hud.setVisible("prompt", false);
            promptShown = null;
            return;
        }
        if (!prompt.equals(promptShown)) {
            promptShown = prompt;
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = probe.createGraphics();
            HudStyle.smooth(pg);
            int w = HudStyle.hintWidth(pg, key, prompt);
            pg.dispose();
            Graphics2D[] g = new Graphics2D[1];
            BufferedImage image = HudStyle.canvas(w, HudStyle.HINT_H, pixelScale, g);
            HudStyle.paintHint(g[0], w, key, prompt);
            g[0].dispose();
            hud.put("prompt", image, 0, 0);
        }
        hud.setVisible("prompt", true);
        hud.move("prompt", (width - hud.width("prompt")) / 2, Math.round(height * 0.62f));
    }

    private void draw(GL3 gl, float time) {
        gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
        gl.glViewport(0, 0, width, height);
        gl.glDisable(GL.GL_BLEND);
        gl.glDepthMask(true);
        gl.glClearColor(0f, 0f, 0f, 1f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        if (!ready) return;
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(GL.GL_LESS);

        float[] eye = physics.eye(), f = physics.forward();
        // The cabin shakes: the eyes knocked about a little, and the view with them
        float shake = timeline.shake;
        eye[0] += shake * 0.35f * wobble(time * 23f, 1f);
        eye[1] += shake * 0.35f * wobble(time * 27f, 2f) + bump;
        eye[2] += shake * 0.35f * wobble(time * 19f, 3f);
        f = Affine.normalise(new float[] { f[0] + shake * 0.012f * wobble(time * 17f, 4f), f[1] + shake * 0.012f * wobble(time * 21f, 5f), f[2] });
        float aspect = width / (float) height;
        Matrix4 projection = Matrix4Transform.perspective(FIELD_OF_VIEW, aspect, NEAR, FAR);
        Matrix4 rotation = viewRotation(f);
        Matrix4 view = Matrix4.multiply(rotation, Matrix4Transform.translate(-eye[0], -eye[1], -eye[2]));

        // The ceiling lamp, warm white; a little light from the screens either side and behind
        // (flickering as the air roars past)
        float cabin = cabinLight * (1f - 0.35f * timeline.roar * (wobble(time * 31f, 6f) > 0.6f ? 1f : 0f));
        lighting.lamp = 0.05f + 0.95f * cabin;
        float lampY = PodInterior.CEILING - 4f;
        lighting.set(0, 0f, lampY, 0f, 1.05f * cabin, 1.0f * cabin, 0.92f * cabin, 30f);
        lighting.set(1, 13f, 31f, 0f, 0.04f, 0.1f, 0.06f, 10f);
        lighting.set(2, -13f, 31f, 0f, 0.04f, 0.1f, 0.06f, 10f);
        lighting.set(3, 0f, PodFurniture.SCREEN_MIDDLE[1], -8f, 0.05f, 0.16f, 0.07f, 9f);
        lighting.ambient[0] = 0.012f + 0.18f * cabin;
        lighting.ambient[1] = 0.016f + 0.18f * cabin;
        lighting.ambient[2] = 0.022f + 0.19f * cabin;
        lighting.interference = timeline.roar;
        java.awt.image.BufferedImage screen = terminal.picture(progress, partnerLoaded(), timeline.status());
        if (screen != null) interior.showOnTerminal(gl, screen);
        interior.setBone(PodInterior.CHAIR, chair.pose());
        interior.setBone(PodInterior.SWITCH, PodFurniture.switchPose(lever));
        // Space (or the fogged sky) first, behind the cabin, seen only through the door's glass
        space.render(gl, Matrix4Transform.perspective(FIELD_OF_VIEW, aspect, 0.5f, 200000f), rotation);
        interior.render(gl, Matrix4.multiply(projection, view), eye, lighting, time);
        // The partner, in their suit
        if (partnerBody != null && partnerSeen) {
            float heading = (float) Math.atan2(Math.cos(partner[3]), Math.sin(partner[3]));
            partnerBody.renderPlaced(gl, Matrix4.multiply(projection, view),
                    Matrix4Transform.translate(partner[0], partner[1] + PodPhysics.EYE_HEIGHT, partner[2]), heading,
                    new Vector3(0f, 1f, 0f), new float[] { 0.95f * cabin, 0.92f * cabin, 0.85f * cabin },
                    new Vector3(lighting.ambient[0] * 1.6f, lighting.ambient[1] * 1.6f, lighting.ambient[2] * 1.6f), new Matrix4(1f), null);
        }
        float[] fogLight = { 0.62f + 0.3f * (1f - space.space), 0.66f + 0.3f * (1f - space.space), 0.7f + 0.3f * (1f - space.space) };
        interior.renderGlass(gl, Matrix4.multiply(projection, view), eye, timeline.fog, timeline.plasma, cabin, fogLight, physics.gravity, time);

        // The player's own body, over the cabin, lit by the lamp
        Matrix4 bodyProjection = Matrix4Transform.perspective(FIELD_OF_VIEW, aspect, 0.3f, 60f);
        body.render(gl, f, Matrix4.multiply(bodyProjection, rotation), new Vector3(-eye[0], lampY - eye[1], -eye[2]),
                new float[] { 0.95f * cabin, 0.92f * cabin, 0.85f * cabin },
                new Vector3(lighting.ambient[0] * 1.6f, lighting.ambient[1] * 1.6f, lighting.ambient[2] * 1.6f),
                new Matrix4(1f), null, -1e9f, new float[] { 0f, 0f, 0f });
        showReadyButton();
        showDoorHint(Matrix4.multiply(projection, view));
        hud.draw(gl, width, height);
    }

    // The ready button's state as last drawn (0 none, 1 press R, 2 waiting for the partner)
    private int readyShown;

    /** Multiplayer, once landed: a button to say this player's ready (R), then that they're waiting for the partner. */
    private void showReadyButton() {
        int state = role == null || doorOpening || !timeline.landed() || !host.worldReady() ? 0 : !saidReady ? 1 : !partnerReady ? 2 : 0;
        if (state == 0) {
            if (hud.has("ready")) hud.setVisible("ready", false);
            readyShown = 0;
            return;
        }
        if (state != readyShown) {
            readyShown = state;
            String[] keys = state == 1 ? new String[] { "R", "Ready" } : new String[] { "", "Ready: waiting for Employee " + role.other().label };
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = probe.createGraphics();
            HudStyle.smooth(pg);
            int w = HudStyle.hintWidth(pg, keys);
            pg.dispose();
            // A button: the hint's pill, drawn larger
            Graphics2D[] g = new Graphics2D[1];
            BufferedImage image = HudStyle.canvas(w, HudStyle.HINT_H, pixelScale * 1.8f, g);
            HudStyle.paintHint(g[0], w, keys);
            g[0].dispose();
            hud.put("ready", image, 0, 0);
        }
        hud.setVisible("ready", true);
        hud.move("ready", (width - hud.width("ready")) / 2, Math.round(height * 0.74f));
    }

    // Which way the door hint's arrow last pointed (degrees), for drawing it again only when that changes
    private int hintAngle = Integer.MIN_VALUE;

    /**
     * Once the door can be opened: "Open the door to begin" with an arrow pointing at the door,
     * over it if it's in view, or at the edge of the screen pointing round towards it if not.
     */
    private void showDoorHint(Matrix4 viewProjection) {
        if (!OPEN.equals(doorPrompt())) {
            if (hud.has("doorHint")) hud.setVisible("doorHint", false);
            return;
        }
        float[] m = viewProjection.toFloatArrayForGLSL();
        float dx = 0f, dy = interior.doorMiddle(), dz = PodInterior.radiusAt(interior.doorMiddle());
        float cx = m[0] * dx + m[4] * dy + m[8] * dz + m[12], cy = m[1] * dx + m[5] * dy + m[9] * dz + m[13];
        float cw = m[3] * dx + m[7] * dy + m[11] * dz + m[15];
        float sx, sy;
        boolean inView;
        if (cw > 0.01f) {
            sx = cx / cw;
            sy = cy / cw;
            inView = Math.abs(sx) < 0.8f && Math.abs(sy) < 0.7f;
        } else {
            // (behind: the way round to it)
            sx = -cx;
            sy = -cy;
            if (Math.abs(sx) + Math.abs(sy) < 1e-4f) sx = 1f;
            inView = false;
        }
        // Where the arrow's tip goes, in pixels, and which way it points (0 straight down)
        float px, py;
        int angle;
        if (inView) {
            px = (sx * 0.5f + 0.5f) * width;
            py = (0.5f - sy * 0.5f) * height;
            angle = 0;
        } else {
            double a = Math.atan2(-sy * height, sx * width);
            px = width * 0.5f + (float) Math.cos(a) * width * 0.36f;
            py = height * 0.5f + (float) Math.sin(a) * height * 0.34f;
            angle = Math.round((float) (Math.toDegrees(a) - 90.0) / 5f) * 5;
        }
        if (angle != hintAngle) {
            hintAngle = angle;
            hud.put("doorHint", doorHintImage(angle), 0, 0);
        }
        int size = hud.width("doorHint");
        // (the picture's middle is its arrow's root: in view the tip's at the door, otherwise the picture's centred there)
        float reachOut = (DOOR_HINT_SIZE / 2f - 12f) * pixelScale;
        hud.setVisible("doorHint", true);
        hud.move("doorHint", Math.round(px) - size / 2, Math.round(py - (inView ? reachOut : 0f)) - size / 2);
    }

    private static final int DOOR_HINT_SIZE = 300;

    /** The door's hint: its words in a pill in the middle, and a gold arrow from there pointing angle degrees round from straight down. */
    private BufferedImage doorHintImage(int angle) {
        String words = "Open the door to begin";
        int size = DOOR_HINT_SIZE;
        Graphics2D[] g = new Graphics2D[1];
        BufferedImage image = HudStyle.canvas(size, size, pixelScale, g);
        Graphics2D d = g[0];
        HudStyle.smooth(d);
        int w = HudStyle.hintWidth(d, "", words);
        java.awt.geom.AffineTransform before = d.getTransform();
        d.translate(size / 2.0, size / 2.0);
        d.rotate(Math.toRadians(angle));
        d.setColor(HudStyle.GOLD);
        d.setStroke(new java.awt.BasicStroke(7f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        float tip = size / 2f - 12f;
        d.draw(new java.awt.geom.Line2D.Float(0f, 26f, 0f, tip - 18f));
        java.awt.geom.Path2D head = new java.awt.geom.Path2D.Float();
        head.moveTo(0f, tip);
        head.lineTo(-15f, tip - 24f);
        head.lineTo(15f, tip - 24f);
        head.closePath();
        d.fill(head);
        d.setTransform(before);
        d.translate((size - w) / 2.0, size / 2.0 - HudStyle.HINT_H / 2.0);
        HudStyle.paintHint(d, w, "", words);
        d.dispose();
        return image;
    }

    /** A smooth wobble between -1 and 1 at time t, its own for each seed. */
    private static float wobble(float t, float seed) {
        return (float) (Math.sin(t + seed * 1.7) * 0.6 + Math.sin(t * 2.31 + seed * 5.3) * 0.4);
    }

    /** The view's turn for a facing (y up), as the planet's camera builds its own. */
    private static Matrix4 viewRotation(float[] f) {
        float[] right = Affine.normalise(Affine.cross(f, new float[] { 0f, 1f, 0f }));
        float[] up = Affine.cross(right, f);
        float[] v = new float[16];
        v[0] = right[0];
        v[1] = up[0];
        v[2] = -f[0];
        v[4] = right[1];
        v[5] = up[1];
        v[6] = -f[1];
        v[8] = right[2];
        v[9] = up[2];
        v[10] = -f[2];
        v[15] = 1f;
        return Matrix4.fromColumns(v);
    }
}
