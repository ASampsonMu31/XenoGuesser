import com.xenoguesser.math.*;

/**
 * Multiplayer (M62): the Spectator Cam. A player who's guessed first watches the rest of the
 * round over the partner's shoulder until their guess comes (MapPanel.isSpectating): the camera
 * follows a few metres behind and above where the partner's shown (RemotePlayer, from their
 * poses, sent 20 times a second while they're watched), swinging round after them to look the
 * way they look, with their body in view; this player's own walking and body are left out
 * meanwhile. Where this player stood and looked is kept and put
 * back as the watching ends, with the reveal (before the next round's built behind it).
 *
 * The pose sent meanwhile is the camera's, marked POSE_SPECTATING: the partner's PC neither
 * draws this player nor bumps into them, and the host's townsfolk gather round where they're
 * watched from as they would round the joiner.
 *
 * GL thread only.
 */
final class SpectatorCam extends GamePart {

    // Where this player stood and how they looked (yaw, pitch in degrees) as the watching began;
    // null while not watching
    private SpherePosition ownEye;
    private float ownYaw, ownPitch;
    // Which way the camera faces round the partner (as RemotePlayer.heading), easing after theirs
    private double swungHeading;
    private boolean swung;

    // How far behind the partner's eye and above it the camera follows, and how close it may
    // come to the ground or the sea beneath it
    private static final double BEHIND = 4.5 * PlayerBody.METRE, ABOVE = 1.0 * PlayerBody.METRE,
            CLEARANCE = 1.0 * PlayerBody.METRE;
    // How long (seconds) the camera takes to swing most of the way round after the partner turns
    private static final double SWING = 0.35;

    SpectatorCam(XenoGuesser_GLEventListener game) {
        super(game);
    }

    /** Once a frame: begins watching once the map says this player's spectating (and the partner's been seen), ends it once it no longer does. */
    void frame() {
        boolean wanted = game.minimap != null && game.minimap.isSpectating() && game.partner.hasPose();
        if (wanted && ownEye == null) {
            ownEye = game.camera.getSpherePosition();
            float[] f = game.camera.getLocalForward();
            ownYaw = (float) Math.toDegrees(Math.atan2(f[2], f[0]));
            ownPitch = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, f[1]))));
            swung = false;
            System.out.println("[MP] Spectator Cam on");
        } else if (!wanted && ownEye != null) {
            game.camera.setSphereView(ownEye, ownYaw, ownPitch);
            ownEye = null;
            System.out.println("[MP] Spectator Cam off: back where this player stood");
        }
    }

    /** Whether the view is the partner's just now. */
    boolean watching() {
        return ownEye != null;
    }

    /**
     * In place of this player's movement while watching: the camera behind the partner and a
     * little above, on the side away from where it faces, kept clear of the ground and the sea.
     * It faces the way the camera has swung (their heading is atan2 of the forward's east and
     * south parts, the camera's yaw the other way round), tilted down to keep them in view and
     * half as far up or down as they look.
     */
    void placeCamera(double deltaTime) {
        RemotePlayer partner = game.partner;
        double heading = partner.heading();
        if (!swung) {
            swungHeading = heading;
            swung = true;
        } else {
            double turn = Math.atan2(Math.sin(heading - swungHeading), Math.cos(heading - swungHeading));
            swungHeading += turn * (1.0 - Math.exp(-Math.max(0.0, deltaTime) / SWING));
        }
        SpherePosition theirEye = partner.eye();
        SphereFrame there = SphereFrame.at(theirEye);
        double eyeAltitude = theirEye.length() - Planet.radius();
        double x = -Math.sin(swungHeading) * BEHIND, z = -Math.cos(swungHeading) * BEHIND;
        double altitude = eyeAltitude + ABOVE;
        double floor = Math.max(game.groundHeightAt(there.toSphere(x, altitude, z)), game.seaLevelHeight) + CLEARANCE;
        altitude = Math.max(altitude, floor);
        SpherePosition behind = there.toSphere(x, altitude, z);

        float yaw = (float) Math.toDegrees(Math.atan2(Math.cos(swungHeading), Math.sin(swungHeading)));
        double down = Math.atan2(altitude - eyeAltitude, BEHIND);
        game.camera.setSphereView(behind, yaw, (float) Math.toDegrees(0.5 * partner.pitch() - down));
    }
}
