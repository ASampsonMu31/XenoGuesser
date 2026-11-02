import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The landing pod's way down, as the world loads: cruising at lightspeed with the stars
 * streaming past; dropping out of it, the stars slowing to points and the planet's star ahead;
 * on the way in, as the world's star system has it (StarSystem), through an asteroid belt (a few
 * small rocks striking the glass) or swung round a gas giant by its gravity, or both; closing on
 * the planet as it grows ahead, its moon beside it if it has one; swerving past it and swinging
 * round into orbit, the window facing the planet as it turns below, at least half way round; let
 * go from the ship with a clunk and turned, its top to the planet, on its thrusters, still going
 * round; the roar of the air as it comes in, the window fogging; turned upright again through the
 * fog; coming down; and landing with a jolt.
 *
 * Each stage lasts at least so long and some end only once the loading's near enough its end
 * that what's left of the way down takes as long as what's left of the loading (the waiting done
 * where it looks natural: cruising, in orbit, coming in through the air; never part way through
 * the belt, the giant or the approach), so the pod's down as the screen reads 100%: the time left
 * is reckoned from the less of this PC's share loaded and the partner's, so both players' pods
 * come down together. If the loading gets ahead of the pod anyway (a quick load, or a guess of the
 * time left that was too long) the rest of the way down is hurried, never skipped. It lands only
 * once both worlds are ready. Each frame it sets what's seen out of the window (PodSpaceView),
 * gravity as it's felt in the cabin, how hard the cabin shakes and how loud each sound is; things
 * that happen once (the clunk, a rock on the glass, the landing) are queued for PodSequence. It
 * keeps the ship's log the pod's computer shows: what the ship's doing now, and what it's done.
 */
public class PodTimeline {

    enum Phase { CRUISE, DROPOUT, BELT, FLYBY, APPROACH, ORBIT, RELEASE, REENTRY, UPRIGHT, DESCENT, LANDED }

    enum Event { DROPPED_OUT, STRIKE, RELEASED, THRUSTERS, ENTRY, UPRIGHTING, LANDED }

    // How long each stage lasts at least (the drop out of lightspeed, the belt, the giant and the
    // approach exactly; the orbit's at least ORBIT_LEAST radians round, going once round in ORBIT_TIME)
    private static final float CRUISE_TIME = 8f, DROPOUT_TIME = 6f, BELT_TIME = 16f, FLYBY_TIME = 18f, APPROACH_TIME = 12f,
            ORBIT_TIME = 20f, RELEASE_TIME = 5f, REENTRY_TIME = 10f, UPRIGHT_TIME = 5f, DESCENT_TIME = 3f;
    private static final float ORBIT_LEAST = (float) Math.PI;
    // The cruise ends with this many seconds of loading to spare beyond the least the rest takes,
    // waited out in orbit (so a guess of the time left that's a little short is made up there)
    private static final float ORBIT_HOLD = 10f;
    // Hurrying: the rest of the way down goes at most MOST_HURRY times as fast, aiming to be done
    // as the loading is, or HURRY_WITHIN seconds after it's done; the pace eased in, not jumped to
    private static final float MOST_HURRY = 3f, HURRY_WITHIN = 1f, HURRY_EASE = 2f;
    // ...but through the belt and past the giant at most this, so they're seen (what's lost made up after)
    private static final float MOST_HURRY_SIGHTS = 1.5f;
    // How fast the pod's going while it cruises (for the streaks); dropping out of that the
    // streaks slow to nothing and the stars, still now, fill in all round as a dome
    private static final float CRUISE_SPEED = 6000f;
    // The belt: how fast its rocks come past, the dust's streaks, and the dust's colour
    private static final float ROCK_SPEED = 330f, DUST_SPEED = 260f;
    private static final float[] DUST = { 0.78f, 0.66f, 0.55f }, STARLIGHT = { 0.9f, 0.92f, 1f };
    // The giant: the pod passes GIANT_NEAREST of its radii from its middle, coming from GIANT_FAR
    // ahead and going as far behind; turned (at most GIANT_LOOK radians) to keep it in the window;
    // the way to the planet GIANT_COURSE off to the other side at first, swung ahead by it
    private static final float GIANT_NEAREST = 2.4f, GIANT_FAR = 15f, GIANT_LOOK = 1.1f, GIANT_COURSE = 0.7f;
    // The approach as it really looks: the pod flies straight at the planet, its distance falling
    // from APPROACH_FAR to APPROACH_NEAR (by the same share each moment). The star is
    // STAR_FROM_PLANET beyond it, off to one side (STAR_SIDE): from far off the two are lined up,
    // the star in the middle of the view; closing in, the planet grows fast while the star, so
    // much further, hardly grows and slides away to the side, until it's out beside the pod,
    // lighting the planet's face towards it (a little behind the pod, so its day side's seen). Sizes are radii, in the same units
    // (the star looks from the planet as the game's sun does from the ground)
    private static final float APPROACH_FAR = 30f, APPROACH_NEAR = 0.012f;
    // How the approach is spread over its time: more of it far off, the star in the middle, before
    // the planet comes up fast (the share of the way, in log distance, is the share of time to this power)
    private static final float FAR_LINGER = 1.6f;
    // How far below level (radians) the way ahead is, seen from the cabin: the door's middle from standing
    private static final float AHEAD_TIP = 0.33f;
    // So where it'll land isn't given away: from this far through the approach the pod steers off
    // to pass the planet on its right, turned ORBIT_TURN aside by the end (the planet off to the
    // left of the door); then, over ORBIT_SETTLE seconds, it swings round to face it from its
    // orbit, ORBIT_DISTANCE from its middle, going round once in ORBIT_TIME and on round at that
    // rate (the stars sweeping past) until the window's fogged
    private static final float ORBIT_FROM = 0.7f, ORBIT_TURN = 0.36f, ORBIT_SETTLE = 8f, ORBIT_DISTANCE = 0.0065f;
    // How fast the swerve's turning as the approach ends (it turns ever faster: ORBIT_TURN by the square of the way through it)
    private static final float SWERVE_RATE = 2f * ORBIT_TURN / ((1f - ORBIT_FROM) * APPROACH_TIME);
    private static final float ORBIT_RATE = (float) (Math.PI * 2.0 / ORBIT_TIME);
    private static final float STAR_FROM_PLANET = 1f, PLANET_RADIUS = 0.002f;
    private static final float[] STAR_SIDE = Affine.normalise(new float[] { 0.9f, 0.15f, -0.45f });
    // While cruising, the computer notes something routine every so often (seconds)
    private static final float CHATTER_EVERY = 11f;
    private static final String[] CHATTER = { "Course check: on track", "Hull integrity 100%", "Drive core stable",
            "Life support nominal", "Long-range scan: clear", "Navigation lock held", "Star charts updated", "Radiation shield OK",
            "Cabin pressure nominal", "Fuel reserves 94%" };
    // How many lines of the log are kept
    private static final int LOG_KEPT = 8;

    // Developer aids: -Dxenoguesser.podfast makes every stage a quarter as long;
    // -Dxenoguesser.podphase=REENTRY starts at that stage, not waiting on the loading
    private static final float PACE = Dev.property("xenoguesser.podfast") != null ? 0.25f : 1f;
    private static final String START = Dev.property("xenoguesser.podphase");

    Phase phase = Phase.CRUISE;
    private float inPhase, approach, angle, spin, heading, orbitAngle;
    // How much faster than usual the pod's going down (1 for not hurried)
    private float hurry = 1f;
    private final boolean ignoreProgress;
    private final Deque<Event> events = new ArrayDeque<>();
    private final StarSystem system;
    // The rocks still to strike the glass, in turn, and the next one due
    private final Deque<float[]> strikesDue = new ArrayDeque<>();
    private int nextStrike;
    // The ship's log: what's been done, and what's being done now
    private final List<String> log = new ArrayList<>();
    private String doing;
    private final Random chatter;
    private float chatterAt = CHATTER_EVERY;
    private int saidInPhase;

    // ---- What it sets each frame
    /** The pod's orientation (its own frame into the one it flies in). */
    final float[] podToSpace = Affine.identity();
    /** Gravity as it's felt in the cabin. */
    final float[] gravity = { 0f, -PodPhysics.GRAVITY, 0f };
    /** How hard the cabin shakes (0..1), how fogged the window is (0..1), the glow of the air at its edge (0..1). */
    float shake, fog, plasma;
    /** How loud the engines, the air's roar and the low rumble of coming down are (0..1). */
    float engine, roar, rumble;

    public PodTimeline(StarSystem system) {
        this.system = system;
        chatter = WorldPalette.rng(system.worldSeed, 0xC4A7L);
        Phase start = Phase.CRUISE;
        if (START != null) {
            try {
                start = Phase.valueOf(START.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // (an unknown stage: from the start)
            }
        }
        ignoreProgress = START != null;
        phase = included(start) ? start : following(start);
        if (phase.ordinal() > Phase.APPROACH.ordinal()) approach = 1f;
        begin(phase);
    }

    /** The next thing that's happened once, or null. */
    Event nextEvent() {
        return events.poll();
    }

    /** The rock that's to strike the glass (with Event.STRIKE): {when, u, v, cracks (1 or 0), seed}. */
    float[] nextStrike() {
        return strikesDue.poll();
    }

    boolean landed() {
        return phase == Phase.LANDED;
    }

    /** What the ship's doing now, for the computer's screen. */
    String doing() {
        return doing;
    }

    /** What the ship's done, the latest last. */
    List<String> log() {
        return log;
    }

    /** Notes something done in the log (a rock on the glass, say). */
    void note(String line) {
        log.add(line);
        while (log.size() > LOG_KEPT) log.remove(0);
    }

    /** What the ship's doing now, what it was doing before going into the log. */
    private void now(String line) {
        if (doing != null && !doing.equals(line)) note(doing);
        doing = line;
    }

    /** Whether this world's way in has a stage (the belt and the giant only where the star system has them). */
    private boolean included(Phase p) {
        return switch (p) {
            case BELT -> system.belt;
            case FLYBY -> system.giant;
            default -> true;
        };
    }

    /** The stage after this one on this world's way in. */
    private Phase following(Phase p) {
        Phase[] all = Phase.values();
        for (int i = p.ordinal() + 1; i < all.length; i++) if (included(all[i])) return all[i];
        return Phase.LANDED;
    }

    /**
     * Moves on by dt: secondsLeft is roughly how long the loading has left on both PCs (the longer
     * of the two; 0 once both are ready), bothReady whether both worlds are ready.
     */
    void step(float dt, float secondsLeft, boolean bothReady, PodSpaceView view) {
        float wanted = 1f;
        if (ignoreProgress) {
            secondsLeft = 0f;
        } else if (phase != Phase.LANDED) {
            float most = phase == Phase.BELT || phase == Phase.FLYBY ? MOST_HURRY_SIGHTS : MOST_HURRY;
            wanted = Math.max(1f, Math.min(most, leastLeft(inPhase / PACE) / Math.max(secondsLeft, HURRY_WITHIN)));
        }
        hurry += (wanted - hurry) * Math.min(1f, dt * HURRY_EASE);
        dt *= hurry;
        inPhase += dt;
        spin += dt * 0.02f;
        float t = inPhase / PACE;
        switch (phase) {
            case CRUISE -> {
                if (t >= CRUISE_TIME && secondsLeft <= (after(Phase.CRUISE) + ORBIT_HOLD) * PACE) next();
            }
            case DROPOUT -> {
                if (t >= DROPOUT_TIME) next();
            }
            case BELT -> {
                // The rocks that strike the glass, each sent on its way to arrive when it's due
                while (nextStrike < system.strikes.length && t >= system.strikes[nextStrike][0] * BELT_TIME) {
                    strikesDue.add(system.strikes[nextStrike++]);
                    events.add(Event.STRIKE);
                }
                if (t >= BELT_TIME) next();
            }
            case FLYBY -> {
                if (t >= FLYBY_TIME) next();
            }
            case APPROACH -> {
                // Steadily, by the clock: never held part way in (the orbit waits on the loading)
                approach = Math.min(1f, t / APPROACH_TIME);
                if (approach >= 1f) next();
            }
            case ORBIT -> {
                // At least half way round, and on round until what's left of the loading is as long as the rest
                if (orbitAngle >= ORBIT_LEAST && secondsLeft <= after(Phase.ORBIT) * PACE) next();
            }
            case RELEASE -> {
                if (t >= RELEASE_TIME) next();
            }
            case REENTRY -> {
                if (t >= REENTRY_TIME && secondsLeft <= after(Phase.REENTRY) * PACE) next();
            }
            case UPRIGHT -> {
                if (t >= UPRIGHT_TIME) next();
            }
            case DESCENT -> {
                if (t >= DESCENT_TIME && bothReady) next();
            }
            case LANDED -> {
            }
        }
        t = inPhase / PACE;
        // Off the straight line at the planet; then round, facing it, in orbit
        if (phase == Phase.FLYBY) {
            heading = flybyHeading(t / FLYBY_TIME);
        } else if (phase == Phase.APPROACH) {
            float x = Math.max(0f, (approach - ORBIT_FROM) / (1f - ORBIT_FROM));
            heading = ORBIT_TURN * x * x;
        } else if (phase.ordinal() >= Phase.ORBIT.ordinal()) {
            // One smooth curve from the swerve into the orbit: carrying on turning as fast as the
            // swerve ended (curving out further), easing back round to face the planet, while the
            // orbit's own turning builds from nothing up to its rate
            float x = phase == Phase.ORBIT ? Math.min(1f, t / ORBIT_SETTLE) : 1f;
            float m = SWERVE_RATE * ORBIT_SETTLE;
            heading = ORBIT_TURN * (2f * x * x * x - 3f * x * x + 1f) + m * (x * x * x - 2f * x * x + x);
            orbitAngle += ORBIT_RATE * smooth(x) * dt / PACE;
        } else {
            heading = 0f;
        }
        narrate(t);
        pose(t, dt);
        show(t, view);
    }

    /** The least a stage takes, from its start (the orbit's from the start of its turning round); none for one this world's way in leaves out. */
    private float least(Phase phase) {
        if (!included(phase)) return 0f;
        return switch (phase) {
            case CRUISE -> CRUISE_TIME;
            case DROPOUT -> DROPOUT_TIME;
            case BELT -> BELT_TIME;
            case FLYBY -> FLYBY_TIME;
            case APPROACH -> APPROACH_TIME;
            // (its turning eases in over ORBIT_SETTLE, half as far as at its full rate)
            case ORBIT -> ORBIT_LEAST / ORBIT_RATE + ORBIT_SETTLE * 0.5f;
            case RELEASE -> RELEASE_TIME;
            case REENTRY -> REENTRY_TIME;
            case UPRIGHT -> UPRIGHT_TIME;
            case DESCENT -> DESCENT_TIME;
            case LANDED -> 0f;
        };
    }

    /** The least the stages after this one take, down to the landing (seconds, unpaced). */
    private float after(Phase phase) {
        float sum = 0f;
        for (Phase p : Phase.values()) if (p.ordinal() > phase.ordinal()) sum += least(p);
        return sum;
    }

    /** The least the rest of the way down takes from here, t seconds into this stage (seconds, as paced). */
    private float leastLeft(float t) {
        float here = phase == Phase.ORBIT
                ? Math.max(0f, ORBIT_LEAST - orbitAngle) / ORBIT_RATE + Math.max(0f, ORBIT_SETTLE - t) * 0.5f
                : Math.max(0f, least(phase) - t);
        return (here + after(phase)) * PACE;
    }

    private boolean thrustersFired;

    /** On to the next stage of this world's way in. */
    private void next() {
        phase = following(phase);
        inPhase = 0f;
        thrustersFired = false;
        switch (phase) {
            case DROPOUT -> events.add(Event.DROPPED_OUT);
            case RELEASE -> events.add(Event.RELEASED);
            case REENTRY -> events.add(Event.ENTRY);
            case UPRIGHT -> events.add(Event.UPRIGHTING);
            case LANDED -> events.add(Event.LANDED);
            default -> {
            }
        }
        begin(phase);
    }

    /** What the computer says as a stage begins. */
    private void begin(Phase phase) {
        saidInPhase = 0;
        switch (phase) {
            case CRUISE -> now("Cruising at lightspeed");
            case DROPOUT -> now("Dropping out of lightspeed");
            case BELT -> {
                note("Asteroid belt ahead");
                now("Evading asteroids");
            }
            case FLYBY -> {
                note("Gas giant ahead");
                now("Gravity assist burn");
            }
            case APPROACH -> {
                note("Planet acquired");
                now("Braking burn");
            }
            case ORBIT -> now("Entering orbit");
            case RELEASE -> now("Undocking from ship");
            case REENTRY -> now("Atmospheric entry");
            case UPRIGHT -> now("Reorienting for landing");
            case DESCENT -> now("Final descent");
            case LANDED -> now("Touchdown");
        }
    }

    /** What the computer says part way through a stage, t seconds in (each thing once). */
    private void narrate(float t) {
        switch (phase) {
            case CRUISE -> {
                if (t >= chatterAt) {
                    chatterAt += CHATTER_EVERY * (0.8f + 0.4f * chatter.nextFloat());
                    note(CHATTER[chatter.nextInt(CHATTER.length)]);
                }
            }
            case DROPOUT -> {
                if (once(0, t >= DROPOUT_TIME * 0.6f)) now("Star sighted: class " + system.starClass);
            }
            case BELT -> {
                if (once(0, t >= BELT_TIME - 2f)) now("Asteroid belt cleared");
            }
            case FLYBY -> {
                if (once(0, t >= FLYBY_TIME * 0.45f)) now("Slingshot round giant");
                else if (once(1, t >= FLYBY_TIME * 0.85f)) now("Course set for planet");
            }
            case APPROACH -> {
                if (once(0, t >= APPROACH_TIME * 0.35f)) {
                    if (system.hasMoon) note("Moon sighted");
                    now("Closing on planet");
                } else if (once(1, approach >= ORBIT_FROM)) now("Steering for orbit");
            }
            case ORBIT -> {
                if (once(0, t >= ORBIT_SETTLE)) now("Orbit stable");
                else if (once(1, t >= ORBIT_SETTLE + 4f)) now("Choosing landing site");
            }
            case RELEASE -> {
                if (once(0, t >= 0.8f)) now("Pod released: reorienting");
                else if (once(1, t >= RELEASE_TIME - 1f)) now("Heat shield to planet");
            }
            case REENTRY -> {
                if (once(0, t >= 3f)) now("Hull temperature high");
                else if (once(1, t >= REENTRY_TIME * 0.6f)) now("Plasma fading: braking");
            }
            case DESCENT -> {
                if (once(0, t >= DESCENT_TIME * 0.5f)) now("Landing legs down");
            }
            case LANDED -> {
                if (once(0, t >= 2f)) now("Landed safely");
            }
            default -> {
            }
        }
    }

    /** Whether the stage's next thing to say (the which'th, from 0) is to be said now: the first time when's true, once those before it are said. */
    private boolean once(int which, boolean when) {
        if (saidInPhase != which || !when) return false;
        saidInPhase++;
        return true;
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    /** Where the giant is (in its radii, in the frame the pod flies in), f of the way past it. */
    private float[] giantAt(float f) {
        // (a little quicker past it at its nearest, as its pull swings the pod round)
        float g = f + 0.06f * (float) Math.sin(f * Math.PI * 2);
        return new float[] { system.giantSide * GIANT_NEAREST, 0.35f, GIANT_FAR * (1f - 2f * g) };
    }

    /** How far the pod's turned to keep the giant in the window, f of the way past it, easing back at the end. */
    private float flybyHeading(float f) {
        float[] g = giantAt(f);
        float seen = (float) Math.atan2(g[0], g[2]);
        float look = Math.max(-GIANT_LOOK, Math.min(GIANT_LOOK, -0.6f * seen));
        return look * smooth(f / 0.1f) * (1f - smooth((f - 0.72f) / 0.28f));
    }

    /** The pod's turn, gravity, the shaking and the sounds, t seconds into the stage. */
    private void pose(float t, float dt) {
        float before = angle;
        float lean = 0f, aside = 0f;
        shake = 0.04f;
        engine = 0f;
        roar = 0f;
        rumble = 0f;
        plasma = 0f;
        float lighter = 1f;
        switch (phase) {
            case CRUISE -> angle = 0f;
            case DROPOUT -> {
                angle = 0f;
                // Out of lightspeed: pressed towards the door, the engines hard at it, shaking
                float x = t / DROPOUT_TIME;
                lean = 14f * (1f - x);
                engine = 0.4f * (1f - smooth(x));
                shake = 0.04f + 0.3f * (1f - smooth(x * 1.5f));
            }
            case BELT -> {
                angle = 0f;
                // Jinking between the rocks: thrusters, and gravity swung this way and that
                float thick = smooth(t / 3f) * (1f - smooth((t - BELT_TIME + 3f) / 3f));
                engine = 0.18f * thick;
                aside = 7f * thick * (float) Math.sin(t * 0.9f) * (float) Math.sin(t * 0.37f + 1f);
                shake = 0.05f + 0.05f * thick;
            }
            case FLYBY -> {
                angle = 0f;
                // Swung round the giant by its pull, felt as a lean towards it at the nearest
                float x = t / FLYBY_TIME;
                float pull = (float) Math.pow(Math.sin(Math.PI * Math.max(0f, Math.min(1f, x))), 3.0);
                aside = system.giantSide * 10f * pull;
                engine = 0.3f * pull;
                shake = 0.05f + 0.1f * pull;
            }
            case APPROACH -> {
                angle = 0f;
                // Braking: pressed gently towards the door
                lean = 5f * (1f - approach);
                engine = 0.15f * (1f - approach);
            }
            case ORBIT -> {
                angle = 0f;
                // The burn into orbit, then quiet
                engine = 0.45f * (1f - smooth(t / ORBIT_SETTLE));
                shake = 0.04f + 0.1f * engine;
            }
            case RELEASE -> {
                // Let go, then turned on its thrusters, its top to the planet
                float turn = smooth((t - 0.9f) / (RELEASE_TIME - 1.4f));
                angle = (float) (Math.PI * 0.5) * turn;
                if (t > 0.8f && !thrustersFired) {
                    thrustersFired = true;
                    events.add(Event.THRUSTERS);
                }
                engine = t > 0.8f ? Math.min(1f, (t - 0.8f) * 3f) * (1f - smooth((t - RELEASE_TIME + 0.6f) / 0.6f)) : 0f;
                shake = t < 0.4f ? 0.5f : 0.08f + 0.25f * engine;
            }
            case REENTRY -> {
                angle = (float) (Math.PI * 0.5);
                // The air's roar rising to its height and easing off; the glow at the window with it
                float rise = smooth(t / 3f), fall = 1f - smooth((t - REENTRY_TIME * 0.6f) / (REENTRY_TIME * 0.5f));
                roar = rise * (0.35f + 0.65f * fall);
                plasma = rise * fall;
                shake = 0.15f + 0.85f * roar;
                // Braking hard, top first: lighter on the floor
                lighter = 1f - 0.45f * roar;
                fog = Math.max(fog, smooth((t - 2f) / (REENTRY_TIME - 2f)) * 0.88f);
            }
            case UPRIGHT -> {
                // Over through half a turn, through the fog, so the floor's down again
                angle = (float) (Math.PI * 0.5 - Math.PI * smooth(t / UPRIGHT_TIME));
                engine = 0.8f * (1f - smooth((t - UPRIGHT_TIME + 1f) / 1f));
                roar = 0.25f;
                rumble = 0.6f;
                shake = 0.25f;
                fog = 0.88f;
            }
            case DESCENT -> {
                angle = (float) (-Math.PI * 0.5) + 0.03f * (float) Math.sin(t * 1.3f);
                rumble = 0.7f;
                roar = 0.12f;
                shake = 0.12f;
                fog = 0.88f;
            }
            case LANDED -> {
                angle = (float) (-Math.PI * 0.5);
                rumble = Math.max(0f, 0.7f - t * 1.5f);
                shake = t < 0.5f ? 1f - t * 2f : 0.02f;
                fog = 0.88f - 0.18f * smooth(t / 8f);
            }
        }
        // (out in space the way it flies is tipped down a little, so what's ahead is in the middle of
        // the door for someone standing in the cabin; once it's upright again, level)
        float tip = phase.ordinal() < Phase.UPRIGHT.ordinal() ? 1f : phase == Phase.UPRIGHT ? 1f - smooth(t / UPRIGHT_TIME) : 0f;
        float[] turn = Affine.rotationX(angle - AHEAD_TIP * tip);
        System.arraycopy(turn, 0, podToSpace, 0, 16);
        // Gravity: as the cabin's own (the pod spins it up, or the planet's once it's down),
        // swung by the turning (the faster it turns the further), and pressed on by braking
        float swing = Math.max(-30f, Math.min(30f, (angle - before) / Math.max(1e-3f, dt) * 30f));
        gravity[0] = aside;
        gravity[1] = -PodPhysics.GRAVITY * lighter;
        gravity[2] = lean + swing;
    }

    /** Only the still stars, no streaks. */
    private static void still(PodSpaceView view) {
        view.speed = 0f;
        view.streaming = 0f;
        view.dome = 1f;
    }

    /**
     * The planet, its star and its moon as seen from distance away from the planet (facing it,
     * along +z), orbit radians round it: where each is and how wide each looks, and the way the
     * star's light falls (the star, the moon and the light turned about the planet's middle with
     * the orbit).
     */
    private void place(PodSpaceView view, float distance, float orbit) {
        float[] round = Affine.rotationY(orbit);
        float[] side = Affine.transformDirection(round, STAR_SIDE[0], STAR_SIDE[1], STAR_SIDE[2]);
        float[] star = { side[0] * STAR_FROM_PLANET, side[1] * STAR_FROM_PLANET, distance + side[2] * STAR_FROM_PLANET };
        float starDistance = Affine.length(star);
        float[] d = Affine.normalise(star);
        System.arraycopy(d, 0, view.starDirection, 0, 3);
        // (as big as the world's sun looks from the ground, from the planet)
        view.starAngle = (float) Math.atan(Math.tan(system.sunAngle()) * STAR_FROM_PLANET / starDistance);
        view.planetDirection[0] = 0f;
        view.planetDirection[1] = 0f;
        view.planetDirection[2] = 1f;
        view.planetAngle = (float) Math.asin(Math.min(1.0, PLANET_RADIUS / distance));
        if (system.hasMoon) {
            float reach = system.moonDistance * PLANET_RADIUS;
            float[] m = Affine.transformDirection(round, system.moonSide[0], system.moonSide[1], system.moonSide[2]);
            float[] moon = { m[0] * reach, m[1] * reach, distance + m[2] * reach };
            float moonDistance = Affine.length(moon);
            System.arraycopy(Affine.normalise(moon), 0, view.moonDirection, 0, 3);
            view.moonAngle = (float) Math.asin(Math.min(1.0, system.moonRadius * PLANET_RADIUS / moonDistance));
            view.moonFarther = moonDistance / distance;
        } else {
            view.moonAngle = 0f;
        }
        // (from the star to the planet)
        view.light[0] = -side[0];
        view.light[1] = -side[1];
        view.light[2] = -side[2];
    }

    /** What's seen out of the window, t seconds into the stage. */
    private void show(float t, PodSpaceView view) {
        System.arraycopy(podToSpace, 0, view.podToSpace, 0, 16);
        view.heading = heading;
        view.orbitAngle = orbitAngle;
        view.planetSpin = spin;
        view.course = 0f;
        view.belt = 0f;
        view.giantShown = false;
        System.arraycopy(STARLIGHT, 0, view.streakTint, 0, 3);
        float a = approach;
        // (until the giant's swung it round, the way to the planet's off to one side)
        float off = system.giant && phase.ordinal() < Phase.FLYBY.ordinal() ? -system.giantSide * GIANT_COURSE : 0f;
        switch (phase) {
            case CRUISE -> {
                view.speed = CRUISE_SPEED;
                view.streaming = 1f;
                view.dome = 0f;
                view.starAngle = 0f;
                view.planetAngle = 0f;
                view.moonAngle = 0f;
                view.space = 1f;
            }
            case DROPOUT -> {
                // Out of the cruise: the streaks slowing to nothing, the still stars coming in
                float slowing = 1f - smooth(t / DROPOUT_TIME);
                view.speed = CRUISE_SPEED * slowing * slowing;
                view.streaming = slowing;
                view.dome = smooth((t / DROPOUT_TIME - 0.15f) / 0.6f);
                place(view, APPROACH_FAR, 0f);
                view.course = off;
                view.space = 1f;
            }
            case BELT -> {
                // The rocks thickening and thinning, the dust streaming past them
                still(view);
                float thick = smooth(t / 2f) * (1f - smooth((t - BELT_TIME + 4f) / 4f));
                view.belt = thick;
                view.rockSpeed = ROCK_SPEED;
                view.speed = DUST_SPEED;
                view.streaming = 0.45f * thick;
                System.arraycopy(DUST, 0, view.streakTint, 0, 3);
                place(view, APPROACH_FAR, 0f);
                view.course = off;
                view.space = 1f;
            }
            case FLYBY -> {
                still(view);
                float f = t / FLYBY_TIME;
                view.giantShown = true;
                System.arraycopy(giantAt(f), 0, view.giantCentre, 0, 3);
                view.giantSpin = spin * 4f;
                place(view, APPROACH_FAR, 0f);
                // (swung round to the planet as it passes)
                view.course = -system.giantSide * GIANT_COURSE * (1f - smooth((f - 0.2f) / 0.6f));
                view.space = 1f;
            }
            case APPROACH -> {
                still(view);
                place(view, APPROACH_FAR * (float) Math.pow(APPROACH_NEAR / APPROACH_FAR, Math.pow(a, FAR_LINGER)), 0f);
                view.space = 1f;
            }
            case ORBIT -> {
                still(view);
                // (closing in from the end of the approach to the orbit's height as it swings round)
                place(view, APPROACH_NEAR + (ORBIT_DISTANCE - APPROACH_NEAR) * smooth(t / ORBIT_SETTLE), orbitAngle);
                view.space = 1f;
            }
            case RELEASE -> {
                still(view);
                place(view, ORBIT_DISTANCE, orbitAngle);
                view.space = 1f;
            }
            case REENTRY -> {
                still(view);
                place(view, ORBIT_DISTANCE, orbitAngle);
                view.space = 1f - smooth((t - 2f) / (REENTRY_TIME * 0.6f));
            }
            default -> view.space = 0f;
        }
        // Up, through the fog: away from the planet (which is ahead, +z, in the frame it flies in)
        float[] up = Affine.transformDirection(PodFurniture.invertRigid(podToSpace), 0f, 0f, -1f);
        System.arraycopy(up, 0, view.skyUp, 0, 3);
    }
}
