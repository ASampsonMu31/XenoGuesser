import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The landing pod's way down, as the world loads: cruising with the stars streaming past; slowing
 * as the planet's star and then the planet itself grow ahead; swerving past it and swinging round
 * into orbit, the window facing the planet as it turns below, at least half way round; let go from the
 * ship with a clunk and turned, its top to the planet, on its thrusters, still going round; the
 * roar of the air as it comes in, the window fogging; turned upright again through the fog; coming
 * down; and landing with a jolt.
 *
 * Each stage lasts at least so long and some end only once the loading's near enough its end
 * that what's left of the way down takes as long as what's left of the loading (the waiting done
 * where it looks natural: cruising, in orbit, coming in through the air; never part way through
 * the approach), so the pod's down as the screen reads 100%: the time left is reckoned from the
 * less of this PC's share loaded and the partner's, so both players' pods come down together. If
 * the loading gets ahead of the pod anyway (a quick load, or a guess of the time left that was
 * too long) the rest of the way down is hurried, never skipped. It lands only once both worlds are
 * ready. Each frame it sets what's seen out of the window (PodSpaceView), gravity as it's felt
 * in the cabin, how hard the cabin shakes and how loud each sound is; things that happen once
 * (the clunk, the landing) are queued for PodSequence.
 */
public class PodTimeline {

    enum Phase { CRUISE, APPROACH, ORBIT, RELEASE, REENTRY, UPRIGHT, DESCENT, LANDED }

    enum Event { RELEASED, THRUSTERS, ENTRY, UPRIGHTING, LANDED }

    // How long each stage lasts at least (the approach exactly; the orbit's at least ORBIT_LEAST
    // radians round, going once round in ORBIT_TIME)
    private static final float CRUISE_TIME = 8f, APPROACH_TIME = 14f, ORBIT_TIME = 20f, RELEASE_TIME = 5f, REENTRY_TIME = 10f,
            UPRIGHT_TIME = 5f, DESCENT_TIME = 3f;
    private static final float ORBIT_LEAST = (float) Math.PI;
    // The cruise ends with this many seconds of loading to spare beyond the least the rest takes,
    // waited out in orbit (so a guess of the time left that's a little short is made up there)
    private static final float ORBIT_HOLD = 10f;
    // Hurrying: the rest of the way down goes at most MOST_HURRY times as fast, aiming to be done
    // as the loading is, or HURRY_WITHIN seconds after it's done; the pace eased in, not jumped to
    private static final float MOST_HURRY = 3f, HURRY_WITHIN = 1f, HURRY_EASE = 2f;
    // How fast the pod's going while it cruises (for the streaks); in the first part of the
    // approach it drops out of that, the streaks slowing to nothing and the stars, still now,
    // filling in all round as a dome
    private static final float CRUISE_SPEED = 6000f, DROP_OUT = 0.3f;
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
    private static final float STAR_FROM_PLANET = 1f, STAR_RADIUS = 0.0585f, PLANET_RADIUS = 0.002f;
    private static final float[] STAR_SIDE = Affine.normalise(new float[] { 0.9f, 0.15f, -0.45f });

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

    // ---- What it sets each frame
    /** The pod's orientation (its own frame into the one it flies in). */
    final float[] podToSpace = Affine.identity();
    /** Gravity as it's felt in the cabin. */
    final float[] gravity = { 0f, -PodPhysics.GRAVITY, 0f };
    /** How hard the cabin shakes (0..1), how fogged the window is (0..1), the glow of the air at its edge (0..1). */
    float shake, fog, plasma;
    /** How loud the engines, the air's roar and the low rumble of coming down are (0..1). */
    float engine, roar, rumble;

    public PodTimeline() {
        Phase start = Phase.CRUISE;
        if (START != null) {
            try {
                start = Phase.valueOf(START.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // (an unknown stage: from the start)
            }
        }
        ignoreProgress = START != null;
        phase = start;
        if (phase.ordinal() > Phase.APPROACH.ordinal()) approach = 1f;
    }

    /** The next thing that's happened once, or null. */
    Event nextEvent() {
        return events.poll();
    }

    boolean landed() {
        return phase == Phase.LANDED;
    }

    /** The stage's name, for the computer's screen, or null while cruising. */
    String status() {
        return switch (phase) {
            case CRUISE -> null;
            case APPROACH -> approach < DROP_OUT ? "Decelerating" : "Planet acquired";
            case ORBIT -> inPhase < ORBIT_SETTLE ? "Entering orbit" : "Orbiting";
            case RELEASE -> "Pod released";
            case REENTRY -> "Atmospheric entry";
            case UPRIGHT -> "Orienting for landing";
            case DESCENT -> "Final descent";
            case LANDED -> "Touchdown";
        };
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
            wanted = Math.max(1f, Math.min(MOST_HURRY, leastLeft(inPhase / PACE) / Math.max(secondsLeft, HURRY_WITHIN)));
        }
        hurry += (wanted - hurry) * Math.min(1f, dt * HURRY_EASE);
        dt *= hurry;
        inPhase += dt;
        spin += dt * 0.02f;
        float t = inPhase / PACE;
        switch (phase) {
            case CRUISE -> {
                if (t >= CRUISE_TIME && secondsLeft <= (after(Phase.CRUISE) + ORBIT_HOLD) * PACE) next(Phase.APPROACH);
            }
            case APPROACH -> {
                // Steadily, by the clock: never held part way in (the orbit waits on the loading)
                approach = Math.min(1f, t / APPROACH_TIME);
                if (approach >= 1f) next(Phase.ORBIT);
            }
            case ORBIT -> {
                // At least half way round, and on round until what's left of the loading is as long as the rest
                if (orbitAngle >= ORBIT_LEAST && secondsLeft <= after(Phase.ORBIT) * PACE) next(Phase.RELEASE);
            }
            case RELEASE -> {
                if (t >= RELEASE_TIME) next(Phase.REENTRY);
            }
            case REENTRY -> {
                if (t >= REENTRY_TIME && secondsLeft <= after(Phase.REENTRY) * PACE) next(Phase.UPRIGHT);
            }
            case UPRIGHT -> {
                if (t >= UPRIGHT_TIME) next(Phase.DESCENT);
            }
            case DESCENT -> {
                if (t >= DESCENT_TIME && bothReady) next(Phase.LANDED);
            }
            case LANDED -> {
            }
        }
        t = inPhase / PACE;
        // Off the straight line at the planet; then round, facing it, in orbit
        if (phase == Phase.APPROACH) {
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
        }
        pose(t, dt);
        show(t, view);
    }

    /** The least a stage takes, from its start (the orbit's from the start of its turning round). */
    private static float least(Phase phase) {
        return switch (phase) {
            case CRUISE -> CRUISE_TIME;
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
    private static float after(Phase phase) {
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

    private void next(Phase phase) {
        this.phase = phase;
        inPhase = 0f;
        thrustersFired = false;
        switch (phase) {
            case RELEASE -> events.add(Event.RELEASED);
            case REENTRY -> events.add(Event.ENTRY);
            case UPRIGHT -> events.add(Event.UPRIGHTING);
            case LANDED -> events.add(Event.LANDED);
            default -> {
            }
        }
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    /** The pod's turn, gravity, the shaking and the sounds, t seconds into the stage. */
    private void pose(float t, float dt) {
        float before = angle;
        float lean = 0f;
        shake = 0.04f;
        engine = 0f;
        roar = 0f;
        rumble = 0f;
        plasma = 0f;
        float lighter = 1f;
        switch (phase) {
            case CRUISE -> angle = 0f;
            case APPROACH -> {
                angle = 0f;
                // Slowing: pressed gently towards the door
                lean = 12f * (1f - approach);
                engine = 0.25f * (1f - approach);
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
        gravity[0] = 0f;
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
     * The planet and its star as seen from distance away from the planet (facing it, along +z),
     * orbit radians round it: where each is and how wide each looks, and the way the star's light
     * falls (the star and its light turned about the planet's middle with the orbit).
     */
    private static void place(PodSpaceView view, float distance, float orbit) {
        float[] side = Affine.transformDirection(Affine.rotationY(orbit), STAR_SIDE[0], STAR_SIDE[1], STAR_SIDE[2]);
        float[] star = { side[0] * STAR_FROM_PLANET, side[1] * STAR_FROM_PLANET, distance + side[2] * STAR_FROM_PLANET };
        float starDistance = Affine.length(star);
        float[] d = Affine.normalise(star);
        System.arraycopy(d, 0, view.starDirection, 0, 3);
        view.starAngle = (float) Math.atan(STAR_RADIUS / starDistance);
        view.planetDirection[0] = 0f;
        view.planetDirection[1] = 0f;
        view.planetDirection[2] = 1f;
        view.planetAngle = (float) Math.asin(Math.min(1.0, PLANET_RADIUS / distance));
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
        float a = approach;
        switch (phase) {
            case CRUISE -> {
                view.speed = CRUISE_SPEED;
                view.streaming = 1f;
                view.dome = 0f;
                view.starAngle = 0f;
                view.planetAngle = 0f;
                view.space = 1f;
            }
            case APPROACH -> {
                // Out of the cruise: the streaks slowing to nothing, the still stars coming in
                float slowing = 1f - smooth(a / DROP_OUT);
                view.speed = CRUISE_SPEED * slowing * slowing;
                view.streaming = slowing;
                view.dome = smooth((a - 0.05f) / DROP_OUT);
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
