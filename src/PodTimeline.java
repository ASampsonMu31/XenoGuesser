import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The landing pod's way down, as the world loads: cruising at lightspeed with the stars
 * streaming past; dropping out of it far out in the planet's star system, the stars slowing to
 * points and the star ahead, the planet beyond it; on the way in, as the world's star system has
 * it (StarSystem), through the asteroid belt that rings the star in the planet's plane (a few small
 * rocks striking the glass) or swung round a gas giant by its gravity, or both; close past the
 * star, a hundred times the planet's size, filling the window as it goes by; closing on
 * the planet along a line that passes to one side of it, its moon beside it if it has one, the
 * window kept on the planet as it grows and swings from ahead to beside, so the pod's top comes
 * round to the way it's going; curving into orbit round it, the window facing it as it turns
 * below, at least half way round; let go from the ship with a clunk and spiralling down, tighter
 * and lower, turned on its thrusters over the last of the spiral so its top's to the planet and
 * its window looks back the way it came; the roar of the air as it comes in, the window fogging;
 * turned upright again through the fog; coming down; and landing with a jolt.
 *
 * Each stage lasts at least so long and some end only once the loading's near enough its end
 * that what's left of the way down takes as long as what's left of the loading (the waiting done
 * where it looks natural: cruising, in orbit, coming in through the air; never part way through
 * the belt, the giant, the star or the approach), so the pod's down as the screen reads 100%, and
 * no later. The time left is reckoned by PodSequence (in multiplayer from the average of both
 * PCs' shares loaded, the same on both, so the two pods, and what's out of their windows, keep
 * together). If the loading gets ahead of the pod (a quick load, or a guess of the time left that
 * was too long) the rest of the way down is hurried to catch it up, never skipped. It lands only
 * once both worlds are ready. Each frame it sets what's seen out of the window (PodSpaceView),
 * gravity as it's felt in the cabin, how hard the cabin shakes and how loud each sound is; things
 * that happen once (the clunk, a rock on the glass, the landing) are queued for PodSequence. It
 * keeps the ship's log the pod's computer shows: what the ship's doing now, and what it's done.
 */
public class PodTimeline {

    enum Phase { CRUISE, DROPOUT, BELT, FLYBY, STAR, APPROACH, ORBIT, RELEASE, REENTRY, UPRIGHT, DESCENT, LANDED }

    enum Event { DROPPED_OUT, STRIKE, RELEASED, THRUSTERS, ENTRY, UPRIGHTING, LANDED }

    // How long each stage lasts at least (the drop out of lightspeed, the belt, the giant, the pass
    // by the star and the approach exactly, the approach's reckoned below; the orbit's at least
    // ORBIT_LEAST radians round, going once round in ORBIT_TIME)
    private static final float CRUISE_TIME = 3f, DROPOUT_TIME = 6f, BELT_TIME = 16f, FLYBY_TIME = 18f, STAR_TIME = 14f,
            ORBIT_TIME = 20f, RELEASE_TIME = 16f, REENTRY_TIME = 10f, UPRIGHT_TIME = 5f, DESCENT_TIME = 3f;
    private static final float ORBIT_LEAST = (float) Math.PI;
    // The cruise ends with this many seconds of loading to spare beyond the least the rest takes,
    // waited out in orbit (so a guess of the time left that's a little short is made up there)
    private static final float ORBIT_HOLD = 10f;
    // Hurrying: the rest of the way down goes at most MOST_HURRY times as fast, aiming to be done
    // as the loading is, or HURRY_WITHIN seconds after it's done; the pace eased in, not jumped to
    private static final float MOST_HURRY = 5f, HURRY_WITHIN = 1f, HURRY_EASE = 2f;
    // ...but through the belt, past the giant and by the star at most this, so they're seen (what's lost made up after)
    private static final float MOST_HURRY_SIGHTS = 3f;
    // How fast the pod's going while it cruises (for the streaks); dropping out of that the
    // streaks slow to nothing and the stars, still now, fill in all round as a dome
    private static final float CRUISE_SPEED = 6000f;
    // The belt: how fast its rocks come past (small and quick: a rock a few times the pod's size
    // is gone in a moment), the dust's streaks, and the dust's colour
    private static final float ROCK_SPEED = 1400f, DUST_SPEED = 1100f;
    private static final float[] DUST = { 0.78f, 0.66f, 0.55f }, STARLIGHT = { 0.9f, 0.92f, 1f };
    // The giant: the pod passes GIANT_NEAREST of its radii from its middle (further than it passes
    // the star in the star's, so the star looks the bigger), coming from GIANT_FAR ahead and going
    // as far behind; turned (at most GIANT_LOOK radians) to keep it in the window; the way to the
    // planet GIANT_COURSE off to the other side at first, swung ahead by it
    private static final float GIANT_NEAREST = 3.2f, GIANT_FAR = 15f, GIANT_LOOK = 1.1f, GIANT_COURSE = 0.7f;
    // The star system to scale, in one set of units (the planet's radius PLANET_RADIUS). The star
    // is STAR_FROM_PLANET from the planet and looks from it as the game's sun does from the ground,
    // so it's some hundred times the planet's size. The pod comes in along one straight line in the
    // plane the planet goes round the star in, aimed to pass STAR_PASS of the star's radii from its
    // middle (on the giant's side) and on to the planet beyond: dropping out of lightspeed
    // DROP_FROM star-to-planet distances from the star, through the belt that rings the star between
    // BELT_OUTER and BELT_INNER of them (where its rocks are), past the giant until STAR_FROM of
    // them off, then by the star, turned (at most STAR_LOOK radians) to keep it in the window as it
    // swells and slides past, and on (the star behind now, lighting the planet's face ahead) to
    // the approach. That plane's tipped STAR_TIP from level, seen from the cabin
    private static final float STAR_FROM_PLANET = 3.4f, PLANET_RADIUS = 0.002f;
    private static final float STAR_PASS = 2.2f, STAR_LOOK = 1.25f, STAR_TIP = 0.12f;
    private static final float DROP_FROM = 5f, BELT_OUTER = 3.2f, BELT_INNER = 2.2f, STAR_FROM = 1f;
    // The approach as it really looks: on along the line, passing SIDE of the planet's radii from
    // its middle, its distance falling from APPROACH_FAR (just past the star) to APPROACH_NEAR (by
    // the same share each moment), turned all the while to keep its window on the planet, which
    // grows from a point to fill it. Sizes are radii, in the same units
    private static final float APPROACH_FAR = 2.6f, APPROACH_NEAR = 0.016f;
    // How the approach is spread over its time: going on at first as fast as it came past the star
    // (LINE_START_SPEED units a second), then braking, more of it far off before the planet comes up
    // fast (the share of the way, in log distance, is partly the share of time and partly the share
    // of time to the power FAR_LINGER)
    private static final float LINE_START_SPEED = 0.3f, FAR_LINGER = 2.5f;
    // How far below level (radians) the way ahead is, seen from the cabin: the door's middle from standing
    private static final float AHEAD_TIP = 0.33f;
    // ...and once it's turned its top to the planet, this many times as far (the window looking
    // back the way it came and down a little, so the planet's edge is across the door)
    private static final float ENTRY_TIP = 2.2f;
    // The way down drawn as one curve, in the plane of the pod's top and window (so the pod only
    // ever pitches): the line in, until it's beside the planet; given over, from there, through
    // a further LINE_HANDOVER radians round, to the orbit, which it meets curving a little wide;
    // round at ORBIT_HIGH of the planet's radii from its middle until the pod's let go; then
    // spiralling in to ORBIT_LOW over SPIRAL_TIME seconds (through its release, as low as it goes
    // as the window starts to fog), quickening as it gets lower (by the height's share to the
    // power SPIRAL_QUICKEN, a gentle Kepler)
    private static final float SIDE = 2.4f, LINE_HANDOVER = (float) (Math.PI * 0.6), ORBIT_HIGH = 2.4f, ORBIT_LOW = 1.25f;
    private static final float SPIRAL_TIME = RELEASE_TIME + 2f, SPIRAL_QUICKEN = 0.75f;
    // Going round once in ORBIT_TIME (the stars sweeping past), the pace it reaches over ORBIT_SETTLE
    // seconds from the pace it came in at off the line (nearly the same)
    private static final float ORBIT_SETTLE = 6f;
    private static final float ORBIT_RATE = (float) (Math.PI * 2.0 / ORBIT_TIME);
    // How far round the planet (radians, from where it's straight ahead) the pod is as the line
    // in starts and as the approach ends
    private static final float ROUND_FAR = (float) Math.asin(SIDE * PLANET_RADIUS / APPROACH_FAR),
            ROUND_NEAR = (float) Math.asin(SIDE * PLANET_RADIUS / APPROACH_NEAR);
    // The approach takes as long as it must for the line's turning round the planet at its end (as
    // the distance falls by its share each moment) to be the orbit's own pace, given it starts at
    // LINE_START_SPEED; the share of its way that's even in time (the rest by FAR_LINGER); and how
    // fast it's going round the planet at its end
    private static final double LOG_WAY = Math.log(APPROACH_FAR / APPROACH_NEAR);
    private static final float APPROACH_TIME = (float) (Math.tan(ROUND_NEAR) * LOG_WAY * FAR_LINGER
            / (ORBIT_RATE + Math.tan(ROUND_NEAR) * (FAR_LINGER - 1f) * LINE_START_SPEED / APPROACH_FAR));
    private static final float LINE_EVEN = (float) (LINE_START_SPEED * APPROACH_TIME / (APPROACH_FAR * LOG_WAY));
    private static final float LINE_RATE = (float) (Math.tan(ROUND_NEAR) * LOG_WAY * (LINE_EVEN + (1f - LINE_EVEN) * FAR_LINGER) / APPROACH_TIME);
    // The pod's let go and the spiral begun; then, from TURN_FROM to TURN_TO seconds into it, it's
    // turned its top to the planet over the last of the spiral, done before the air fogs the
    // window (from 2 s into the entry)
    private static final float TURN_FROM = 10.5f, TURN_TO = RELEASE_TIME - 0.5f;
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
    // How far round the planet the pod has come (radians, from where it was straight ahead on the
    // line in), how far it is from the planet's middle (units), and how long it's been spiralling down
    private float round = ROUND_FAR, distance, spiralling;
    // How much faster than usual the pod's going down (1 for not hurried)
    private float hurry = 1f;
    private final boolean ignoreProgress;
    private final Deque<Event> events = new ArrayDeque<>();
    private final StarSystem system;
    // The star's radius, and where it is from the planet (the line in runs along the z axis to the
    // planet, the pod at 0, 0, -distance); the plane it, the planet, the line in and the belt are in,
    // across (level, more or less) and square to it
    private final float starRadius;
    private final float[] starAt, plane, planeNormal;
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
        starRadius = (float) Math.tan(system.sunAngle()) * STAR_FROM_PLANET;
        float side = system.giantSide, pass = STAR_PASS * starRadius;
        plane = new float[] { side * (float) Math.cos(STAR_TIP), (float) Math.sin(STAR_TIP), 0f };
        planeNormal = new float[] { -side * (float) Math.sin(STAR_TIP), (float) Math.cos(STAR_TIP), 0f };
        float behind = (float) Math.sqrt(STAR_FROM_PLANET * STAR_FROM_PLANET - pass * pass);
        starAt = new float[] { plane[0] * pass, plane[1] * pass, -behind };
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
        distance = lineFrom(Phase.DROPOUT);
        if (phase.ordinal() > Phase.APPROACH.ordinal()) {
            approach = 1f;
            round = ROUND_NEAR;
            distance = APPROACH_NEAR;
        }
        // (started part way: as far round as it'd have come, and as far down the spiral)
        if (phase.ordinal() > Phase.ORBIT.ordinal()) {
            orbitAngle = ORBIT_LEAST;
            round += ORBIT_LEAST;
        }
        if (phase.ordinal() > Phase.RELEASE.ordinal()) spiralling = RELEASE_TIME;
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

    /** The stage it's at and how far into it (seconds, unpaced), for the developers' pictures of the pod. */
    String where() {
        return String.format("%s %.1f s in", phase, inPhase / PACE);
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
            float most = phase == Phase.BELT || phase == Phase.FLYBY || phase == Phase.STAR ? MOST_HURRY_SIGHTS : MOST_HURRY;
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
            case STAR -> {
                if (t >= STAR_TIME) next();
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
        travel(t, dt / PACE);
        heading = phase == Phase.FLYBY ? flybyHeading(t / FLYBY_TIME) : phase == Phase.STAR ? starHeading(t / STAR_TIME) : 0f;
        narrate(t);
        pose(t, dt);
        show(t, view);
    }

    /**
     * Where the pod is on its way down, t seconds into the stage, having gone on by dt (unpaced):
     * how far round the planet and how far from it. Through the star system, steadily along the
     * line from where the stage begins to where the next does (dropping out of lightspeed, from
     * fast to the next stage's pace); along the line in to the planet, the distance by the clock
     * and how far round from that; from the orbit on, how far round by the clock (the pace kept
     * from the line's, easing to the orbit's and quickening down the spiral) and the distance from that.
     */
    private void travel(float t, float dt) {
        if (phase.ordinal() < Phase.APPROACH.ordinal()) {
            if (phase == Phase.CRUISE) {
                distance = lineFrom(Phase.DROPOUT);
                return;
            }
            Phase after = following(phase);
            float from = lineFrom(phase), to = lineFrom(after), x = Math.min(1f, t / least(phase));
            if (phase == Phase.DROPOUT) {
                // (slowing hard out of lightspeed, to come into the next stage at its pace)
                float onward = (lineFrom(after) - lineFrom(following(after))) / least(after) * DROPOUT_TIME;
                float left = 1f - x;
                distance = to + onward * left + (from - to - onward) * left * left * left;
            } else {
                distance = from + (to - from) * x;
            }
            return;
        }
        if (phase == Phase.APPROACH) {
            double share = LINE_EVEN * approach + (1f - LINE_EVEN) * Math.pow(approach, FAR_LINGER);
            distance = APPROACH_FAR * (float) Math.pow(APPROACH_NEAR / APPROACH_FAR, share);
            round = (float) Math.asin(Math.min(1.0, SIDE * PLANET_RADIUS / distance));
            return;
        }
        if (phase.ordinal() >= Phase.RELEASE.ordinal()) spiralling += dt;
        float high = height();
        float pace = phase == Phase.ORBIT ? LINE_RATE + (ORBIT_RATE - LINE_RATE) * smooth(t / ORBIT_SETTLE) : ORBIT_RATE;
        float turned = pace * (float) Math.pow(ORBIT_HIGH / high, SPIRAL_QUICKEN) * dt;
        round += turned;
        if (phase == Phase.ORBIT) orbitAngle += turned;
        // (the line, in the same terms, given over smoothly to the orbit once it's passed beside the planet)
        float line = 1f - smooth((round - (float) Math.PI * 0.5f) / LINE_HANDOVER);
        float nearness = line * (float) Math.sin(round) / SIDE + (1f - line) / high;
        distance = PLANET_RADIUS / nearness;
    }

    /**
     * How far from the planet the pod is (along the line in) as a stage through the star system
     * begins: where the belt's outer edge is, its inner, the star STAR_FROM off, just past the star.
     */
    private float lineFrom(Phase p) {
        return switch (p) {
            case CRUISE, DROPOUT -> nearStar(DROP_FROM);
            case BELT -> nearStar(BELT_OUTER);
            case FLYBY -> nearStar(BELT_INNER);
            case STAR -> nearStar(STAR_FROM);
            default -> APPROACH_FAR;
        };
    }

    /** How far from the planet the pod is (along the line in, before it reaches the star) once it's away star-to-planet distances from the star. */
    private float nearStar(float away) {
        float pass = Affine.length(new float[] { starAt[0], starAt[1], 0f }), r = away * STAR_FROM_PLANET;
        return -starAt[2] + (float) Math.sqrt(Math.max(0f, r * r - pass * pass));
    }

    /** How high the orbit is now (in the planet's radii, from its middle): its own height, then falling down the spiral. */
    private float height() {
        float down = smooth(spiralling / SPIRAL_TIME);
        return ORBIT_HIGH * (float) Math.pow(ORBIT_LOW / ORBIT_HIGH, down);
    }

    /** The least a stage takes, from its start (the orbit's from the start of its turning round); none for one this world's way in leaves out. */
    private float least(Phase phase) {
        if (!included(phase)) return 0f;
        return switch (phase) {
            case CRUISE -> CRUISE_TIME;
            case DROPOUT -> DROPOUT_TIME;
            case BELT -> BELT_TIME;
            case FLYBY -> FLYBY_TIME;
            case STAR -> STAR_TIME;
            case APPROACH -> APPROACH_TIME;
            case ORBIT -> ORBIT_LEAST / ORBIT_RATE;
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
                ? Math.max(0f, ORBIT_LEAST - orbitAngle) / ORBIT_RATE
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
            case STAR -> {
                note("Star close approach");
                now("Heat shield to star");
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
                else if (once(1, t >= FLYBY_TIME * 0.85f)) now("Course set for star");
            }
            case STAR -> {
                if (once(0, t >= STAR_TIME * 0.4f)) now("Passing star: hull hot");
                else if (once(1, t >= STAR_TIME * 0.8f)) now("Course set for planet");
            }
            case APPROACH -> {
                if (once(0, t >= APPROACH_TIME * 0.35f)) {
                    if (system.hasMoon) note("Moon sighted");
                    now("Closing on planet");
                } else if (once(1, approach >= 0.7f)) now("Steering for orbit");
            }
            case ORBIT -> {
                if (once(0, t >= ORBIT_SETTLE)) now("Orbit stable");
                else if (once(1, t >= ORBIT_SETTLE + 4f)) now("Choosing landing site");
            }
            case RELEASE -> {
                if (once(0, t >= 0.8f)) now("Pod released: descending");
                else if (once(1, t >= TURN_FROM)) now("Reorienting for entry");
                else if (once(2, t >= TURN_TO)) now("Heat shield to planet");
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

    /** Where the star is from the pod (in the frame it flies in, before the orbit turns it). */
    private float[] starFromPod() {
        return new float[] { starAt[0], starAt[1], starAt[2] + distance };
    }

    /** How far the pod's turned to keep the star in the window as it goes by, f of the way past it, easing back once it's behind. */
    private float starHeading(float f) {
        float[] s = starFromPod();
        float seen = (float) Math.atan2(Math.abs(s[0]), s[2]);
        float look = Math.signum(s[0]) * Math.min(STAR_LOOK, 0.85f * seen);
        return -look * smooth(f / 0.15f) * (1f - smooth((f - 0.7f) / 0.3f));
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
            case STAR -> {
                angle = 0f;
                // Close by the star: the hull ticking and buffeted in its wind, a little thrust to hold the line
                float near = Math.min(1f, 1.5f * starRadius / Affine.length(starFromPod()));
                engine = 0.12f;
                shake = 0.05f + 0.15f * near * near;
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
                // Let go, spiralling down a while, then turned slowly on its thrusters, its top to the planet
                float turn = smooth((t - TURN_FROM) / (TURN_TO - TURN_FROM));
                angle = (float) (Math.PI * 0.5) * turn;
                float firing = TURN_FROM - 0.1f;
                if (t > firing && !thrustersFired) {
                    thrustersFired = true;
                    events.add(Event.THRUSTERS);
                }
                engine = t > firing ? Math.min(1f, (t - firing) * 3f) * (1f - smooth((t - TURN_TO + 0.4f) / 0.6f)) : 0f;
                shake = t < 0.4f ? 0.5f : 0.06f + 0.25f * engine;
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
        // the door for someone standing in the cabin; turned top to the planet, tipped further as
        // it turns, ENTRY_TIP times as far, so the planet's edge stays across the door into the
        // fog; once it's upright again, level)
        float tip = switch (phase) {
            case RELEASE -> 1f + (ENTRY_TIP - 1f) * smooth((t - TURN_FROM) / (TURN_TO - TURN_FROM));
            case REENTRY -> ENTRY_TIP;
            case UPRIGHT -> ENTRY_TIP * (1f - smooth(t / UPRIGHT_TIME));
            case DESCENT, LANDED -> 0f;
            default -> 1f;
        };
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
     * The planet, its star, its moon and the belt round the star as seen from distance away from
     * the planet (facing it, along +z), orbit radians round it (from where the line in started, in
     * the plane of the pod's top and window): where each is and how wide each looks, and the way
     * the star's light falls on the planet and on what's by the pod (the star, the moon, the belt and
     * the light turned about the planet's middle with the orbit).
     */
    private void place(PodSpaceView view, float distance, float orbit) {
        float[] round = PodSpaceView.orbitTurn(orbit);
        float[] side = Affine.transformDirection(round, starAt[0], starAt[1], starAt[2]);
        float[] star = { side[0], side[1], distance + side[2] };
        float starDistance = Affine.length(star);
        float[] d = Affine.normalise(star);
        System.arraycopy(d, 0, view.starDirection, 0, 3);
        // (as big as the world's sun looks from the ground, from the planet; and filling much of the sky close by)
        view.starAngle = (float) Math.asin(Math.min(0.9, starRadius / starDistance));
        view.starLight[0] = -d[0];
        view.starLight[1] = -d[1];
        view.starLight[2] = -d[2];
        // The belt, round the star in the planet's plane
        view.beltDust = system.belt ? 1f : 0f;
        System.arraycopy(star, 0, view.beltCentre, 0, 3);
        System.arraycopy(Affine.transformDirection(round, plane[0], plane[1], plane[2]), 0, view.beltAcross, 0, 3);
        System.arraycopy(Affine.transformDirection(round, 0f, 0f, 1f), 0, view.beltAlong, 0, 3);
        System.arraycopy(Affine.transformDirection(round, planeNormal[0], planeNormal[1], planeNormal[2]), 0, view.beltNormal, 0, 3);
        view.beltInner = BELT_INNER * STAR_FROM_PLANET;
        view.beltOuter = BELT_OUTER * STAR_FROM_PLANET;
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
        float[] lit = Affine.normalise(side);
        view.light[0] = -lit[0];
        view.light[1] = -lit[1];
        view.light[2] = -lit[2];
    }

    /** What's seen out of the window, t seconds into the stage. */
    private void show(float t, PodSpaceView view) {
        System.arraycopy(podToSpace, 0, view.podToSpace, 0, 16);
        view.heading = heading;
        view.orbitAngle = round - ROUND_FAR;
        view.planetSpin = spin;
        view.course = 0f;
        view.belt = 0f;
        view.giantShown = false;
        System.arraycopy(STARLIGHT, 0, view.streakTint, 0, 3);
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
                view.beltDust = 0f;
                view.space = 1f;
            }
            case DROPOUT -> {
                // Out of the cruise: the streaks slowing to nothing, the still stars coming in
                float slowing = 1f - smooth(t / DROPOUT_TIME);
                view.speed = CRUISE_SPEED * slowing * slowing;
                view.streaming = slowing;
                view.dome = smooth((t / DROPOUT_TIME - 0.15f) / 0.6f);
                place(view, distance, 0f);
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
                place(view, distance, 0f);
                view.course = off;
                view.space = 1f;
            }
            case FLYBY -> {
                still(view);
                float f = t / FLYBY_TIME;
                view.giantShown = true;
                System.arraycopy(giantAt(f), 0, view.giantCentre, 0, 3);
                view.giantSpin = spin * 4f;
                place(view, distance, 0f);
                // (swung round to the planet as it passes)
                view.course = -system.giantSide * GIANT_COURSE * (1f - smooth((f - 0.2f) / 0.6f));
                view.space = 1f;
            }
            case STAR, APPROACH, ORBIT, RELEASE -> {
                still(view);
                place(view, distance, round - ROUND_FAR);
                view.space = 1f;
            }
            case REENTRY -> {
                still(view);
                place(view, distance, round - ROUND_FAR);
                view.space = 1f - smooth((t - 2f) / (REENTRY_TIME * 0.6f));
            }
            default -> {
                view.space = 0f;
                view.beltDust = 0f;
            }
        }
        // Up, through the fog: away from the planet (which is ahead, +z, in the frame it flies in)
        float[] up = Affine.transformDirection(PodFurniture.invertRigid(podToSpace), 0f, 0f, -1f);
        System.arraycopy(up, 0, view.skyUp, 0, 3);
    }
}
