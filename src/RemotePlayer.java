import java.util.function.ToDoubleFunction;

/**
 * Where the partner is to be drawn now (multiplayer, M9): predicted from their last two poses
 * (Multiplayer.RemotePoses), never drawn late behind them. Their poses come 5 times a second
 * and some are lost, so between and after them the partner is carried on at the speed and
 * rate of turn the last two showed (dead reckoning), and a new pose that disagrees with the
 * prediction is eased into rather than jumped to.
 *
 * Rules (the plan's Phase 5):
 * - Speed from the last two poses' positions over the SENDER's times between them (one clock,
 *   so uneven delivery doesn't distort it); none if they're over TELEPORT units apart (a new
 *   round's spawn, not a walk), or if the times don't go forward.
 * - Predicted = the latest pose + speed x (time since it arrived here), looking at most
 *   MAX_LOOKAHEAD ahead: after that they're held still (several poses lost in a row means we
 *   don't know, and drifting off would be worse). The heading turns on the same way.
 * - When a new pose arrives the difference between where they were shown and where they're
 *   now predicted is kept as an offset and decayed away (offset *= exp(-dt / DECAY), gone in
 *   about 150 ms); over SNAP units apart it's dropped at once instead.
 * - Unless they're airborne (jumping) or swimming, the eye is put at standing height over
 *   what's underfoot where they're shown, so prediction over a hill neither sinks into it
 *   nor floats.
 *
 * Used on the GL thread: update() once a frame, then the getters.
 */
public final class RemotePlayer {

    static final double TELEPORT = 50.0;
    static final double SNAP = 50.0;
    static final double MAX_LOOKAHEAD = 1.0;
    static final double DECAY = 0.05;

    // The latest pose worked from, and what it showed: speed (world units a second, planet-centred)
    // and rate of turn (radians a second)
    private int seq = -1;
    private Multiplayer.PoseSample latest;
    private double vx, vy, vz, turnRate;
    // The difference eased away after a new pose: position, then heading
    private double ox, oy, oz, oHeading;
    // What's shown: the eye, the heading (in the east/up/south frame there, as the player's own
    // body: atan2 of the forward's local x and z), and how fast they're moving along the ground
    private SpherePosition eye;
    private double heading;
    private double speed;
    private boolean hasPose;

    /**
     * Works out where the partner is this frame: poses as Multiplayer has them, the time now
     * (this PC's clock, ms), the time since the last frame (s), what's underfoot at a place
     * (its altitude) and the eye's height above it.
     */
    public void update(Multiplayer.RemotePoses poses, long nowMs, double dt, ToDoubleFunction<SpherePosition> groundAt,
                       double eyeHeight) {
        Multiplayer.PoseSample p1 = poses.latest();
        if (p1 == null) return;
        if (p1.seq() != seq) takeNewPose(poses.previous(), p1, nowMs);

        // Ease the last correction away
        double decay = Math.exp(-Math.max(0.0, dt) / DECAY);
        ox *= decay;
        oy *= decay;
        oz *= decay;
        oHeading *= decay;

        double[] predicted = predicted(nowMs);
        SpherePosition shown = new SpherePosition(predicted[0] + ox, predicted[1] + oy, predicted[2] + oz);
        Multiplayer.Pose pose = p1.pose();
        boolean offGround = (pose.flags() & (Multiplayer.POSE_AIRBORNE | Multiplayer.POSE_SWIMMING)) != 0;
        if (!offGround) shown = SphereMath.atAltitude(shown, groundAt.applyAsDouble(shown) + eyeHeight);
        eye = shown;
        heading = wrap(predicted[3] + oHeading);
        hasPose = true;
    }

    /** A new pose: its speed and turn from the one before, and the jump from what was shown to the new prediction. */
    private void takeNewPose(Multiplayer.PoseSample p0, Multiplayer.PoseSample p1, long nowMs) {
        double[] before = hasPose ? new double[] { eye.x(), eye.y(), eye.z(), heading } : null;
        seq = p1.seq();
        latest = p1;
        vx = vy = vz = turnRate = 0.0;
        if (p0 != null) {
            double seconds = (p1.sentAtMs() - p0.sentAtMs()) / 1000.0;
            Multiplayer.Pose a = p0.pose(), b = p1.pose();
            double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
            boolean walked = Math.sqrt(dx * dx + dy * dy + dz * dz) <= TELEPORT;
            if (seconds > 0.0 && walked) {
                vx = dx / seconds;
                vy = dy / seconds;
                vz = dz / seconds;
                turnRate = wrap(headingOf(b) - headingOf(a)) / seconds;
            }
        }
        // (for the walking animation, Phase 5b)
        speed = Math.sqrt(vx * vx + vy * vy + vz * vz);

        double[] after = predicted(nowMs);
        if (before == null) {
            ox = oy = oz = oHeading = 0.0;
            return;
        }
        ox = before[0] - after[0];
        oy = before[1] - after[1];
        oz = before[2] - after[2];
        oHeading = wrap(before[3] - after[3]);
        if (Math.sqrt(ox * ox + oy * oy + oz * oz) > SNAP) ox = oy = oz = oHeading = 0.0;
    }

    /** The latest pose carried on by its speed and turn for the time since it arrived (at most MAX_LOOKAHEAD): x, y, z, heading. */
    private double[] predicted(long nowMs) {
        Multiplayer.Pose pose = latest.pose();
        double ahead = Math.max(0.0, Math.min(MAX_LOOKAHEAD, (nowMs - latest.arrivedAtMs()) / 1000.0));
        return new double[] { pose.x() + vx * ahead, pose.y() + vy * ahead, pose.z() + vz * ahead,
                headingOf(pose) + turnRate * ahead };
    }

    /** Which way a pose faces: its forward's heading in the east/up/south frame where it is. */
    private static double headingOf(Multiplayer.Pose pose) {
        double[] local = SphereFrame.at(new SpherePosition(pose.x(), pose.y(), pose.z()))
                .localDirection(pose.fx(), pose.fy(), pose.fz());
        return Math.atan2(local[0], local[2]);
    }

    private static double wrap(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    /** Whether any pose has arrived yet (until then there's no partner to show). */
    public boolean hasPose() {
        return hasPose;
    }

    /** The partner's eye where they're shown now. */
    public SpherePosition eye() {
        return eye;
    }

    /** Which way they face, in the east/up/south frame at eye() (as PlayerBody's heading). */
    public double heading() {
        return heading;
    }

    /** How fast they're going (world units a second), from their last two poses. */
    public double speed() {
        return speed;
    }

    /** Their flags in the latest pose (Multiplayer.POSE_SWIMMING, POSE_AIRBORNE). */
    public byte flags() {
        return latest != null ? latest.pose().flags() : 0;
    }

    /** How old the latest pose is (ms, by this PC's clock since it arrived). */
    public long ageMs(long nowMs) {
        return latest != null ? nowMs - latest.arrivedAtMs() : -1;
    }
}
