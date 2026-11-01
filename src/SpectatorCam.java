import com.xenoguesser.math.*;

/**
 * Multiplayer (M62): the Spectator Cam. A player who's guessed first watches the rest of the
 * round through the partner's eyes until their guess comes (MapPanel.isSpectating): the camera
 * is put where the partner's shown (RemotePlayer, from their poses, sent 20 times a second while
 * they're watched), looking as they look, and this player's own walking and body, and the
 * partner's body, are left out meanwhile. Where this player stood and looked is kept and put
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
     * In place of this player's movement while watching: the camera at the partner's eye, facing
     * their way (their heading is atan2 of the forward's east and south parts, the camera's yaw
     * the other way round) and looking as far up as they do.
     */
    void placeCamera() {
        RemotePlayer partner = game.partner;
        double heading = partner.heading();
        float yaw = (float) Math.toDegrees(Math.atan2(Math.cos(heading), Math.sin(heading)));
        game.camera.setSphereView(partner.eye(), yaw, (float) Math.toDegrees(partner.pitch()));
    }
}
