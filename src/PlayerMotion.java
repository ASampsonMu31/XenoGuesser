/**
 * The player's body in the world: walking, sprinting, jumping,
 * swimming, kept clear of what's solid and off slopes too steep to climb, standing on the
 * ground, a road's deck or the pod's stairs; the head's bob as they step; and the oxygen's
 * light-headedness once it's been downgraded.
 */
final class PlayerMotion extends GamePart {

    PlayerMotion(XenoGuesser_GLEventListener game) {
        super(game);
    }

    /**
     * Carries the player from where they were to where the keys took them, in short steps,
     * stopping against anything solid and sliding along it rather than passing through, and
     * not up anything too steep to climb.
     */
    private void moveSolidly(SpherePosition from) {
        SpherePosition to = game.camera.getSpherePosition();
        double distance = SphereMath.distance(from, to);
        if (distance < 1e-6) return;
        int steps = Math.max(1, (int) Math.ceil(distance / 1.5));
        SpherePosition lastFree = from;
        // (where the feet and head are: raised roads and bridges stop the body beside them)
        float feet = airborne ? airborneEyeY - game.playerEyeHeight
                : Float.isNaN(lastStood) ? (float) SphereMath.altitude(to) - game.playerEyeHeight : lastStood;
        float head = feet + game.playerEyeHeight + 2f;
        boolean startsInRoad = game.infraManager != null && game.infraManager.blocksWalker(from, feet, head);
        for (int i = 1; i <= steps; i++) {
            SpherePosition candidate = clearOfPartner(lastFree, SphereMath.slerp(from, to, i / (double) steps), feet);
            // (while swimming, the slope of the bed beneath doesn't matter: only ground that
            // comes up out of the water, where the swimmer would stand, can be too steep)
            boolean floating = game.swimming && TerrainMesh.getLayeredHeight(candidate, game.worldNoise) + game.playerEyeHeight
                    < game.waterSurfaceHere + XenoGuesser_GLEventListener.SWIM_EYE_HEIGHT;
            // On a bridge the ground's slope beneath (a river's bank) doesn't matter, and what's
            // under it doesn't stand in the way: only what's as high as the feet up on it (its
            // railings)
            float deck = game.infraManager == null ? Float.NaN : game.infraManager.bridgeDeckY(candidate);
            float under = TerrainMesh.getLayeredHeight(candidate, game.worldNoise);
            boolean onBridge = !Float.isNaN(deck) && feet >= deck - 3f && deck > under + 1f;
            double clearance = onBridge ? Math.max(jumpHeight, feet - under) : jumpHeight;
            if (!floating && !onBridge && jumpHeight < 1f && sphereTooSteep(lastFree, candidate, feet)) break;
            SpherePosition resolved = game.geodesicCollision.resolveFirm(lastFree, candidate, XenoGuesser_GLEventListener.PLAYER_RADIUS, XenoGuesser_GLEventListener.PLAYER, clearance);
            if (game.geodesicCollision.blockedStatic(resolved, XenoGuesser_GLEventListener.PLAYER_RADIUS * 0.98, clearance)) break;
            if (!startsInRoad && game.infraManager != null && game.infraManager.blocksWalker(resolved, feet, head)) break;
            lastFree = resolved;
        }
        // (the eyes keep the height they had)
        lastFree = SphereMath.atAltitude(lastFree, SphereMath.altitude(to));
        if (SphereMath.distance(lastFree, to) > 1e-4) game.camera.setSpherePosition(lastFree);
    }

    /**
     * Multiplayer (M15): the partner is a solid upright cylinder, PLAYER_RADIUS round, from
     * their feet to 2 above their eyes, where they're shown. A step from from to to that would
     * go into it slides round it instead (to its edge, on the side the step was heading for).
     * Only this player is stopped, never pushed, and nobody is trapped: if they already overlap
     * (after lag, or at a shared spawn) any step that doesn't bring them closer is allowed, and
     * one that does is held back. Passing over their head (a jump that high) is allowed too.
     */
    private SpherePosition clearOfPartner(SpherePosition from, SpherePosition to, float feet) {
        if (game.partnerBody == null || !game.partner.hasPose()) return to;
        SpherePosition them = game.partner.eye();
        double theirFeet = SphereMath.altitude(them) - game.playerEyeHeight;
        if (feet > theirFeet + game.playerEyeHeight + 2.0) return to;
        double apart = XenoGuesser_GLEventListener.PLAYER_RADIUS * 2.0;
        double now = SphereMath.distance(from, them), next = SphereMath.distance(to, them);
        if (next >= apart || next >= now) return to;
        if (now < apart) return from;
        // Round the edge of their cylinder rather than into it
        SpherePosition edge = SphereMath.move(them, SphereMath.heading(them, to), apart);
        return SphereMath.atAltitude(edge, SphereMath.altitude(to));
    }

    boolean sphereTooSteep(SpherePosition at, SpherePosition toward) {
        if (SphereMath.angle(at, toward) < 1e-12) return false;
        double heading = SphereMath.heading(at, toward);
        SpherePosition ahead = SphereMath.move(at, heading, 3.0);
        SpherePosition behind = SphereMath.move(at, heading + Math.PI, 3.0);
        float rise = TerrainMesh.getLayeredHeight(ahead, game.worldNoise)
                - TerrainMesh.getLayeredHeight(behind, game.worldNoise);
        return rise / 6f > MAX_CLIMB;
    }

    /**
     * Whether a step is too steep for someone with their feet at feet: only if the ground and
     * what they'd walk on (a road's deck, levelled over the dips and ruts beneath it) are both
     * too steep. A road can run smoothly over ground that's steep under it, and stepping onto a
     * low road is a short rise of its own over ground that's gentle. Along a road itself
     * (ahead and behind both on it) nothing is too steep: what's built to be walked is.
     */
    boolean sphereTooSteep(SpherePosition at, SpherePosition toward, float feet) {
        if (!sphereTooSteep(at, toward)) return false;
        if (game.infraManager == null) return true;
        double heading = SphereMath.heading(at, toward);
        SpherePosition ahead = SphereMath.move(at, heading, 3.0);
        SpherePosition behind = SphereMath.move(at, heading + Math.PI, 3.0);
        if (game.infraManager.isRoadLocation(ahead, 0f) && game.infraManager.isRoadLocation(behind, 0f)) return false;
        float rise = game.infraManager.walkingSurfaceY(ahead, feet) - game.infraManager.walkingSurfaceY(behind, feet);
        return rise / 6f > MAX_CLIMB;
    }

    // Steeper than this (rise over run) can't be walked up
    private static final float MAX_CLIMB = 0.7f;   // about 35 degrees

    // Jumping: Space throws the player up, and gravity brings them down again. While in the
    // air, anything lower than their feet passes beneath them (rocks, low fences, rails).
    private static final float JUMP_SPEED = 42f, GRAVITY = 90f;

    private volatile boolean jumpWanted;

    // How long returning to the pod (R) takes, and when it's due (NaN while not returning)
    private static final double RETURN_SECONDS = 3.0;
    private volatile double returnAt = Double.NaN;

    boolean airborne;

    float jumpHeight, jumpVelocity, airborneEyeY;

    // What the player stood on last frame (NaN before any, and after a spawn or teleport)
    float lastStood = Float.NaN;

    /** Space: jump, if standing on something (not mid-air or swimming). */
    public void jump() {
        // (not where the game mode keeps the player where they landed: it'd serve no purpose)
        if (!GameMode.current().moving) return;
        jumpWanted = true;
    }

    /** R: back to the pod's stairs where the round started, after RETURN_SECONDS (where the player can move at all). */
    public void returnToPod() {
        if (!GameMode.current().moving || returningToPod()) return;
        returnAt = System.nanoTime() * 1e-9 + RETURN_SECONDS;
    }

    /** Whether the player's on their way back to the pod (R), for the HUD to say so. */
    boolean returningToPod() {
        return !Double.isNaN(returnAt);
    }

    /** Forgets any return to the pod under way (a spawn or teleport has moved the player anyway). */
    void cancelReturn() {
        returnAt = Double.NaN;
    }

    /** How steep the ground is at a place: rise over run. */
    float slopeAt(SpherePosition p) {
        SphereFrame frame = SphereFrame.at(p);
        double d = 3.0;
        float gx = TerrainMesh.getLayeredHeight(frame.toSphere(d, 0.0, 0.0), game.worldNoise) - TerrainMesh.getLayeredHeight(frame.toSphere(-d, 0.0, 0.0), game.worldNoise);
        float gz = TerrainMesh.getLayeredHeight(frame.toSphere(0.0, 0.0, d), game.worldNoise) - TerrainMesh.getLayeredHeight(frame.toSphere(0.0, 0.0, -d), game.worldNoise);
        return (float) Math.hypot(gx, gz) / (float) (2.0 * d);
    }

    /**
     * Moves the player for a frame: walking or sprinting by the keys (where the game mode lets
     * them), kept clear of what's solid, onto the ground, a road's deck or the pod's stairs
     * underfoot, swimming where it's too deep, jumping, and the head's bob as they step.
     */
    void movePlayer(double deltaTime, double elapsedTime) {
        if (returningToPod() && System.nanoTime() * 1e-9 >= returnAt) {
            returnAt = Double.NaN;
            game.roundSpawner.returnToPod();
        }
        boolean moveW = game.keyboard.w;
        boolean moveS = game.keyboard.s;
        boolean moveA = game.keyboard.a;
        boolean moveD = game.keyboard.d;

        if (moveW && moveS) { moveW = false; moveS = false; }
        // (in the game modes where the player stays where they landed, they can't walk)
        if (!GameMode.current().moving) { moveW = false; moveA = false; moveS = false; moveD = false; }
        if (moveA && moveD) { moveA = false; moveD = false; }
        boolean walking = moveW || moveA || moveS || moveD;
        boolean revealing = game.minimap != null && game.minimap.isFullScreenRevealMode();
        boolean sprinting = walking && !revealing && game.keyboard.shift;
        updateOxygen(deltaTime, sprinting, elapsedTime);

        SpherePosition sphereBeforeMove = game.camera.getSpherePosition();
        if (revealing) {
            game.camera.updatePosition(false, false, false, false, (float)deltaTime);
        } else {
            game.camera.updatePosition(moveW, moveA, moveS, moveD, (float)deltaTime, sprinting ? SPRINT_SPEED : 1f);
    }

    if (!game.devShots.menuShotHold) {
        moveSolidly(sphereBeforeMove);
    }
    game.roundSpawner.settleClear();
    game.easeZoom(deltaTime);
    // (the eyes' height less last frame's step bob, so the easing after the ground never sees it)
    float currentAltitude = game.camera.getAltitude() - headBob;
    // What's underfoot: the ground, a road's deck (if they're up on it) or the pod's stairs
    // Where the feet are: in the air, under the jump; on foot, on what they stood on last frame
    // (not under the eyes' eased height, which lags on a steep bridge or ramp far enough for
    // the deck to seem out of reach, dropping the player through it to the ground beneath)
    float feet = airborne ? airborneEyeY - game.playerEyeHeight : (Float.isNaN(lastStood) ? currentAltitude - game.playerEyeHeight : lastStood);
    float rawGroundHeight = (float) game.groundHeightAt(game.camera.getSpherePosition(), feet);
    lastStood = rawGroundHeight;
    float targetCameraHeight = rawGroundHeight + game.playerEyeHeight;

    // Too deep to stand: swim, eyes just above the water
    game.waterSurfaceHere = game.regionalManager.waveSurfaceAt(game.camera.getSpherePosition(), (float) elapsedTime, game.seaLevelHeight);
    // (or a river's or lake's, where that's higher)
    float freshWater = game.freshWaterAt(game.camera.getSpherePosition());
    if (!Float.isNaN(freshWater)) game.waterSurfaceHere = Math.max(game.waterSurfaceHere, freshWater);
    game.swimming = targetCameraHeight < game.waterSurfaceHere + XenoGuesser_GLEventListener.SWIM_EYE_HEIGHT;
    if (game.swimming) {
        targetCameraHeight = game.waterSurfaceHere + XenoGuesser_GLEventListener.SWIM_EYE_HEIGHT;
    }
    float wantedNear = game.swimming ? XenoGuesser_GLEventListener.SWIMMING_NEAR_PLANE : XenoGuesser_GLEventListener.NEAR_PLANE;
    if (wantedNear != game.currentNearPlane) {
        game.currentNearPlane = wantedNear;
        game.applyProjection();
    }

    float dynamicSmoothingFactor = (game.swimming ? 10.0f : 6.0f) * (float)deltaTime;
    if (dynamicSmoothingFactor > 1.0f) dynamicSmoothingFactor = 1.0f;

    if (jumpWanted) {
        jumpWanted = false;
        if (!airborne && !game.swimming) {
            airborne = true;
            jumpVelocity = JUMP_SPEED;
            // From where the eyes are now (the walking height eases after the ground, so
            // starting from the ground itself would jolt)
            airborneEyeY = currentAltitude;
        }
    }
    updateHeadBob(deltaTime, walking && !revealing && !game.devShots.menuShotHold && !airborne && !game.swimming, sprinting);
    if (airborne) {
        // A ballistic arc through the air, whatever the ground does beneath: it lands when
        // it comes down to the ground (going up a slope, sooner; off a drop, later)
        float dt = (float) deltaTime;
        // (no stepping in the air: the bob starts again from nothing on landing)
        headBob = 0f;
        bobWeight = 0f;
        airborneEyeY += (jumpVelocity - GRAVITY * dt * 0.5f) * dt;
        jumpVelocity -= GRAVITY * dt;
        jumpHeight = Math.max(0f, airborneEyeY - targetCameraHeight);
        if ((jumpVelocity <= 0f && airborneEyeY <= targetCameraHeight) || game.swimming) {
            jumpHeight = 0f;
            jumpVelocity = 0f;
            airborne = false;
            game.camera.setAltitude(Math.max(airborneEyeY, targetCameraHeight - 0.5f));
        } else {
            game.camera.setAltitude(Math.max(airborneEyeY, targetCameraHeight));
        }
    } else {
        float smoothedHeight = currentAltitude + (targetCameraHeight - currentAltitude) * dynamicSmoothingFactor;
        game.camera.setAltitude(smoothedHeight + headBob);
    }
    }

    // Holding Shift while walking sprints at SPRINT_SPEED times the walk, for as long as it's held
    private static final float SPRINT_SPEED = 1.5f;

    // With the oxygen downgraded, sprinting this long takes exertion from nothing to the most
    private static final float SPRINT_EXERTION_SECONDS = 3f;

    // The eyes dip at each footfall and rise between: a step every STEP_LENGTH world units walked
    // (a 0.8 m pace walking, 1 m running: some 2.5 steps a second walking, 3 sprinting),
    // BOB_WALK high walking and BOB_RUN sprinting. It eases in over a step or so when the player
    // sets off and out when they stop, so neither jolts
    private static final float STEP_LENGTH_WALK = 16f, STEP_LENGTH_RUN = 20f;

    private static final float BOB_WALK = 0.35f, BOB_RUN = 1.1f, BOB_EASE = 6f;

    // How far the eyes are off their height this frame for the step; how far through the steps
    // (radians, a step each pi); and how much of the bob shows, 0 standing to 1 walking, eased
    private float headBob, stepPhase, bobWeight, bobHeight = BOB_WALK;

    /** Moves the step bob on this frame: walking (on foot, on the ground), and whether at a sprint. */
    private void updateHeadBob(double deltaTime, boolean walking, boolean sprinting) {
        float dt = (float) deltaTime;
        float ease = Math.min(1f, BOB_EASE * dt);
        bobWeight += ((walking ? 1f : 0f) - bobWeight) * ease;
        bobHeight += ((sprinting ? BOB_RUN : BOB_WALK) - bobHeight) * ease;
        if (walking) {
            float speed = game.camera.KEYBOARD_SPEED * (sprinting ? SPRINT_SPEED : 1f);
            stepPhase = (stepPhase + (float) Math.PI * speed * dt / (sprinting ? STEP_LENGTH_RUN : STEP_LENGTH_WALK)) % (float) (2.0 * Math.PI);
        }
        // |sin| peaks between footfalls and dips sharply on each; less its average (2/pi), so the
        // eyes ride as high as ever on the whole
        float step = (float) Math.abs(Math.sin(stepPhase)) - (float) (2.0 / Math.PI);
        headBob = bobWeight < 0.001f ? 0f : bobHeight * bobWeight * step;
    }

    final OxygenBlur oxygenBlur = new OxygenBlur();

    // Whether the oxygen's been downgraded (it stays so for the rest of the game); how out of
    // breath the player is, 0 to 1 (up while sprinting, down while walking or standing); and how
    // light-headed that makes them, 0 to 1, and the time it sways by
    private volatile boolean oxygenDowngraded;

    float exertion, oxygenStrength, oxygenTime;

    // Not sprinting, this many seconds brings exertion back from the most to nothing. It shows
    // from EXERTION_SHOWS on, at its worst at the most
    private static final float EXERTION_CLEAR_SECONDS = 30f, EXERTION_SHOWS = 0.3f;

    // Where the player can't walk (GameMode.NO_MOVE...), it's always this bad
    private static final float STILL_STRENGTH = 0.45f;

    /** The oxygen's downgraded, for the rest of the game. */
    public void downgradeOxygen() {
        oxygenDowngraded = true;
        System.out.println("[OXYGEN] Downgraded");
    }

    /**
     * How light-headed the player is this frame (or always, where they can't walk). Walking
     * leaves them be; sprinting with the oxygen downgraded makes them dizzy within moments.
     */
    private void updateOxygen(double deltaTime, boolean sprinting, double time) {
        if (!oxygenDowngraded) {
            oxygenStrength = 0f;
            return;
        }
        oxygenTime = (float) time;
        if (!GameMode.current().moving) {
            oxygenStrength = STILL_STRENGTH;
            return;
        }
        float dt = (float) deltaTime;
        float change = sprinting ? dt / SPRINT_EXERTION_SECONDS : -dt / EXERTION_CLEAR_SECONDS;
        exertion = Math.max(0f, Math.min(1f, exertion + change));
        float t = Math.max(0f, Math.min(1f, (exertion - EXERTION_SHOWS) / (1f - EXERTION_SHOWS)));
        oxygenStrength = 0.9f * t * t * (3f - 2f * t);
    }
}
