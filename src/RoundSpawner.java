import java.util.Random;


/**
 * Where each round starts: a landing site drawn from the round's own random numbers (the same
 * on both players' PCs), the next one's surroundings prefetched while this round is played,
 * the player stood on the pod's stairs (beside their partner in multiplayer) and the time of
 * day chosen; the developer views (-Dxenoguesser.view, spawnat) stand them elsewhere.
 */
final class RoundSpawner extends GamePart {

    RoundSpawner(XenoGuesser_GLEventListener game) {
        super(game);
    }

        // With a fixed test seed the spawn sequence repeats too, so test runs are comparable
    private final java.util.Random spawnRandom = Dev.longValue("xenoguesser.seed") != null
            ? new java.util.Random(Dev.longValue("xenoguesser.seed")) : new java.util.Random();

    // The round the player was last spawned for (from 1): rounds are counted here as they're
    // built, the same on both PCs, since each builds every round once
    int spawnedRound;

    // What each round's Random is drawn for, kept apart so one PC drawing a number more for one
    // of them (say a queued site it didn't need) can't change the others
    static final long SITE_DRAWS = 1, DAY_DRAWS = 2, CREATURE_DRAWS = 3;

    /**
     * What a round's random choices are drawn from. In multiplayer (M5, M19) a Random made
     * afresh from the world seed, the round and what it's for, so both PCs draw the same landing
     * site, time of day, season, weather and creatures, and can't drift apart; in singleplayer
     * the one long-lived spawnRandom, as always.
     */
    java.util.Random roundRandom(int round, long draws) {
        if (!Multiplayer.isActive()) return spawnRandom;
        return new java.util.Random(game.worldSeed * 0x9E3779B97F4A7C15L + round * 0xBF58476D1CE4E5B9L + draws * 0x94D049BB133111EBL);
    }

    /** Where a normal round's pod comes down: near a road, clear of houses, somewhere on the map. */
    Viewpoint landingSite(java.util.Random rand) {
        return game.infraManager.landingSite(rand, LandingPod.SPAWN_DISTANCE, LandingPod.REACH,
                p -> TerrainMesh.getLayeredHeight(p, game.worldNoise) > game.seaLevelHeight + 1.0f
                        && !game.underFreshWater(p, TerrainMesh.getLayeredHeight(p, game.worldNoise))
                        // (within the map's top and bottom edges, so the place can be guessed)
                        && Math.abs(Planet.latitude(p)) < MapProjection.CLIP_LATITUDE,
                // (in the country, along the roads, or in town, as chosen for the game)
                MapKind.current());
    }

    // The next round's landing site, chosen as soon as this round is under way, and where its
    // pod will stand; its surroundings (trees, grass) are worked out in spare time on a thread
    // of their own, nearest first, so that moving there is mostly a matter of uploading them
    private Viewpoint queuedSite;

    private SpherePosition queuedPod;

    private final java.util.concurrent.ExecutorService prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "next-round-prefetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Chooses where the next round will be and starts getting it ready (normal rounds only). */
    void queueNextRound() {
        game.floraStream.clearPrefetch();
        game.grassStream.clearPrefetch();
        queuedPod = null;
        if (Dev.property("xenoguesser.view") != null || Dev.property("xenoguesser.spawnat") != null) return;
        queuedSite = landingSite(roundRandom(spawnedRound + 1, SITE_DRAWS));
        if (queuedSite == null) return;
        if (Multiplayer.isActive()) {
            System.out.printf(java.util.Locale.ROOT, "[ROUND] Round %d queued: site %.5f, %.5f heading %.4f%n", spawnedRound + 1,
                    Math.toDegrees(Planet.latitude(queuedSite.place())), Math.toDegrees(Planet.longitude(queuedSite.place())), queuedSite.heading());
        }
        queuedPod = SphereMath.move(queuedSite.place(), queuedSite.heading() + Math.PI, LandingPod.SPAWN_DISTANCE);
        game.floraStream.prefetchAround(queuedSite.place());
        game.grassStream.prefetchAround(queuedSite.place());
    }

    /** Hands the prefetch thread its next few cells once it has finished the last. */
    void pumpPrefetch() {
        game.floraStream.pump(prefetcher);
        game.grassStream.pump(prefetcher);
    }

    /** Whether a place is within margin of the landing pod, or of where the next round's will stand. */
    boolean podCovers(SpherePosition p, double margin) {
        return game.landingPod.covers(p, margin)
                || (queuedPod != null && SphereMath.distance(p, queuedPod) < Math.max(40f, LandingPod.REACH) + margin);
    }

    void spawnPlayerAtRandomLocation() {
        int round = ++spawnedRound;
        java.util.Random dynamicRand = roundRandom(round, DAY_DRAWS);
        String devView = Dev.property("xenoguesser.view");
        // Rounds always start on a road, looking along it
        // Developer aid: -Dxenoguesser.view=house starts each round looking at a house
        Viewpoint spawn = "house".equals(devView)
                ? game.infraManager.viewpoints().randomHouseViewpoint(dynamicRand) : game.infraManager.randomRoadPoint(dynamicRand);
        if (devView == null) {
            // A normal round: the pod has come down near a road, clear of houses and fences (where
            // it was chosen during the last round, so its surroundings could be got ready then)
            Viewpoint site = queuedSite != null ? queuedSite : landingSite(roundRandom(round, SITE_DRAWS));
            queuedSite = null;
            queuedPod = null;
            if (site != null) spawn = site;
        }
        // Developer aid: the main menu's picture (-Dxenoguesser.menushot) is taken somewhere picturesque
        if (XenoGuesser_GLEventListener.MENU_SHOT && devView == null) spawn = orElse(game.devShots.picturesqueSite(dynamicRand), spawn);
        // Developer aids: the named views (see devViewpoint); the mountain and the shore are chosen
        // after spawnat, and so win over it
        boolean lateView = "mountain".equals(devView) || "shore".equals(devView) || "swim".equals(devView);
        if (devView != null && !lateView) spawn = orElse(devViewpoint(devView, dynamicRand), spawn);

        // Developer aid: -Dxenoguesser.spawnat=latitude,longitude (degrees, north and east
        // positive) starts the round there
        String spawnAt = Dev.property("xenoguesser.spawnat");
        if (spawnAt != null) {
            String[] parts = spawnAt.split(",");
            double latitude = Double.parseDouble(parts[0].trim()), longitude = Double.parseDouble(parts[1].trim());
            if (parts.length != 2 || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw new IllegalArgumentException("xenoguesser.spawnat takes latitude,longitude in degrees, not " + spawnAt);
            }
            spawn = new Viewpoint(Planet.position(Math.toRadians(latitude), Math.toRadians(longitude), 0.0),
                    spawn != null ? spawn.heading() : -Math.PI * 0.5);
        }

        if (lateView) spawn = orElse(devViewpoint(devView, dynamicRand), spawn);

        while (spawn == null) {
            SpherePosition p = SphereMath.randomPlace(dynamicRand, MapProjection.CLIP_LATITUDE);
            float ground = TerrainMesh.getLayeredHeight(p, game.worldNoise);
            if (ground > game.seaLevelHeight && !game.underFreshWater(p, ground)) spawn = new Viewpoint(p, dynamicRand.nextDouble() * Math.PI * 2);
        }

        game.timeOfDay = dynamicRand.nextFloat();

        // The season: where the planet is in its orbit sets how far north or south the sun
        // stands overhead
        float seasonalPhase = dynamicRand.nextFloat() * (float)(2.0 * Math.PI);
        game.sunDeclination = Math.asin(Math.sin(Math.toRadians(game.planetAxialTiltDegrees)) * Math.sin(seasonalPhase));

        if (game.minimap != null) game.minimap.setPlayerSpawnLocation(SphereMath.direction(spawn.place()));

        chooseTimeOfDay(spawn.place(), dynamicRand);
        // A new day: new clouds and weather
        if (game.sphereWeather != null) game.sphereWeather.newDay(dynamicRand, spawn.place());
        if (Multiplayer.isActive()) {
            // For checking both PCs agree: the same lines on each, round by round
            System.out.printf(java.util.Locale.ROOT, "[ROUND] Round %d (seed %d): site %.5f, %.5f heading %.4f, time of day %.4f, sun declination %.4f, noon longitude %.4f%n",
                    round, game.worldSeed, Math.toDegrees(Planet.latitude(spawn.place())), Math.toDegrees(Planet.longitude(spawn.place())),
                    spawn.heading(), game.timeOfDay, game.sunDeclination, game.noonLongitude);
        }
        // (the round's clouds drift from now until it's begun, then from when it begins: see startRoundClock)
        game.roundClockStart = game.getSeconds();
        game.landingPod.place(spawn.place(), spawn.heading(), p -> TerrainMesh.getLayeredHeight(p, game.worldNoise));
        // Nothing else stands where it came down
        game.infraManager.setKeepClear(game.landingPod.frame().origin(), Math.max(40f, LandingPod.REACH) + 6f);
        // (where the player stays where they land, out past the foot of the pod's stairs, where
        // there's more to see than the pod: settled clear of anything solid once that's there)
        if (!GameMode.current().moving && devView == null) {
            spawn = standingClear(spawn);
            settleUntil = System.nanoTime() * 1e-9 + SETTLE_SECONDS;
        }
        // Multiplayer (M11): side by side, 119-A on the left and 119-B on the right as seen
        // facing down the stairs, both still facing down them (from the same shared spot)
        if (Multiplayer.isActive()) spawn = sideBySide(spawn, Multiplayer.active().role);
        game.moveToLocation(spawn);
        // (each round starts with the view as usual)
        game.zoomWanted = 1f;
        game.zoom = 1f;
        game.applyProjection();
        // Developer aid: -Dxenoguesser.lookatsun starts the round looking straight at the sun
        if (Dev.property("xenoguesser.lookatsun") != null) {
            SpherePosition eye = game.camera.getSpherePosition();
            float[] d = Planet.sunDirection(eye, game.sunDeclination, game.noonLongitude);
            game.camera.lookAt(SphereFrame.at(eye).toWorld(d[0] * 10.0, d[1] * 10.0, d[2] * 10.0));
            System.out.printf("[PLANET] Sun %.0f degrees up, latitude %.1f, declination %.1f%n",
                    Math.toDegrees(Math.asin(d[1])), Math.toDegrees(Planet.latitude(eye)), Math.toDegrees(game.sunDeclination));
        }
        // Developer aid: -Dxenoguesser.pitch=-60 starts the round looking down by that many degrees
        String pitch = Dev.property("xenoguesser.pitch");
        // and -Dxenoguesser.yaw=180 turned round by that many degrees
        String yaw = Dev.property("xenoguesser.yaw");
        if (pitch != null || yaw != null) {
            game.camera.updateYawPitch(yaw == null ? 0f : Float.parseFloat(yaw) / game.camera.MOUSE_SPEED,
                    pitch == null ? 0f : Float.parseFloat(pitch) / game.camera.MOUSE_SPEED);
        }
    }

    // Where the player stays where they land (GameMode.NO_MOVE): this far out past where they'd
    // stand on the pod's stairs at most, nearer if need be, on dry ground clear of houses and
    // roads; and for this long after, put back towards the pod if they turn out to be in
    // something solid (trees, rocks and the like are only there once their ground's been built)
    private static final float[] STAND_OUT = { 60f, 52f, 44f, 36f, 28f, 20f, 12f };

    private static final double SETTLE_SECONDS = 8.0;

    private static final float SETTLE_STEP = 3f;

    private double settleUntil = -1.0;

    /** Where a player who can't move stands, out along the pod's stairs' way from spawn (see STAND_OUT). */
    // How far each player stands from the shared spawn, sideways: with PLAYER_RADIUS 3 they're
    // 13 apart, a clear gap between them, and 9.5 from the middle to their outer edge is well
    // inside the multiplayer stairs' half width of 14
    private static final double SIDE_OFFSET = 6.5;

    /**
     * Multiplayer's spawn for this player from the shared one: SIDE_OFFSET to the left for
     * 119-A, to the right for 119-B. Headings turn from east towards south (clockwise seen from
     * above, D10), so the left of heading h is h - pi/2. The heading is carried along, so both
     * still face exactly the same way.
     */
    private static Viewpoint sideBySide(Viewpoint spawn, Multiplayer.Role role) {
        double side = role == Multiplayer.Role.HOST ? -Math.PI / 2 : Math.PI / 2;
        SpherePosition moved = SphereMath.move(spawn.place(), spawn.heading() + side, SIDE_OFFSET);
        return new Viewpoint(moved, SphereMath.carryHeading(spawn.place(), moved, spawn.heading()));
    }

    private Viewpoint standingClear(Viewpoint spawn) {
        for (float out : STAND_OUT) {
            SpherePosition p = SphereMath.move(spawn.place(), spawn.heading(), out);
            float ground = TerrainMesh.getLayeredHeight(p, game.worldNoise);
            if (ground <= game.seaLevelHeight + 1f || game.underFreshWater(p, ground)) continue;
            // (nor on anyone's land: their plot, garden or driveway)
            if (game.infraManager.isPrivateGround(p, XenoGuesser_GLEventListener.PLAYER_RADIUS + 2f)) continue;
            // (never on a road or track, nor at its very edge)
            if (game.infraManager.isRoadLocation(p, XenoGuesser_GLEventListener.PLAYER_RADIUS + 2f)) continue;
            if (game.infraManager.blocksWalker(p, ground, ground + game.playerEyeHeight + 2f)) continue;
            if (game.playerMotion.sphereTooSteep(spawn.place(), p)) continue;
            return new Viewpoint(p, SphereMath.carryHeading(spawn.place(), p, spawn.heading()));
        }
        return spawn;
    }

    /** For a while after landing, puts a player who can't move back towards the pod if they're in something solid. */
    void settleClear() {
        if (System.nanoTime() * 1e-9 > settleUntil || game.landingPod.frame() == null) return;
        SpherePosition at = game.camera.getSpherePosition();
        if (!game.geodesicCollision.blockedStatic(at, XenoGuesser_GLEventListener.PLAYER_RADIUS, 0.0)) return;
        SpherePosition pod = game.landingPod.frame().origin();
        if (SphereMath.distance(at, pod) <= LandingPod.SPAWN_DISTANCE) return;
        SpherePosition back = SphereMath.move(at, SphereMath.heading(at, pod), SETTLE_STEP);
        game.camera.setSpherePosition(SphereMath.atAltitude(back, SphereMath.altitude(at)));
    }

    /**
     * Developer aid: where the named developer view (-Dxenoguesser.view: street, shop, herd,
     * river, mountain, shore...) stands the player, or null if it's not one of them or the world
     * has nothing of the kind. The trailer's shots (TrailerDirector) are found here too.
     */
    Viewpoint devViewpoint(String devView, java.util.Random dynamicRand) {
        // Developer aids: on a town pavement among the inhabitants; across the street from a
        // shop, looking at its front; on a dirt track, looking along it; looking at a raised
        // road's retaining wall from below
        if ("street".equals(devView)) return game.inhabitants.streetViewpoint(dynamicRand);
        if ("shop".equals(devView)) return game.infraManager.viewpoints().shopViewpoint(dynamicRand);
        if ("track".equals(devView)) return game.infraManager.viewpoints().trackViewpoint(dynamicRand);
        if ("raisedroad".equals(devView)) return game.infraManager.viewpoints().raisedRoadViewpoint(dynamicRand);
        // One of every animal side by side; some species' range with one of them just ahead
        if ("zoo".equals(devView)) return game.organismManager.zooViewpoint(dynamicRand);
        if ("bug".equals(devView)) return game.organismManager.showcaseViewpoint(dynamicRand);
        // Deep in the heartland of a species that lives in herds
        if ("herd".equals(devView)) return game.organismManager.herdViewpoint(dynamicRand);
        // On a river's bank (-Dxenoguesser.riverwidth=20: one at least that wide), looking across and up it
        if ("river".equals(devView)) return game.devShots.riverViewpoint(dynamicRand);
        // Where plant species -Dxenoguesser.plant (random by default) grows thickest
        if ("plant".equals(devView)) return game.devShots.plantViewpoint(dynamicRand);
        // On a side road looking at where it meets (at a step it would have had) a greater one
        if ("junction".equals(devView)) return game.infraManager.viewpoints().junctionViewpoint(dynamicRand);
        // Across from one of the richest houses, dressed in all its architecture; or back from a
        // big city's tower, looking up at it
        if ("ornate".equals(devView)) return game.infraManager.viewpoints().ornateHouseViewpoint(dynamicRand);
        if ("tower".equals(devView)) return game.infraManager.viewpoints().towerViewpoint(dynamicRand);
        // In front of one of the famous landmarks (-Dxenoguesser.landmark=spire, stones or ruin for one kind)
        if ("landmark".equals(devView)) return game.infraManager.viewpoints().landmarkViewpoint(dynamicRand);
        // At a road's edge looking along its bollards
        if ("bollard".equals(devView)) return game.infraManager.viewpoints().bollardViewpoint(dynamicRand);
        // In town between two utility poles, looking along the road
        if ("pole".equals(devView)) return game.infraManager.viewpoints().poleViewpoint(dynamicRand);
        // Side on to a pole's wire down to a house
        if ("drop".equals(devView)) return game.infraManager.viewpoints().poleDropViewpoint(dynamicRand);
        // A little before a bridge, looking over it (a plank one with plankbridge)
        if ("bridge".equals(devView) || "plankbridge".equals(devView)) return game.infraManager.viewpoints().bridgeViewpoint(dynamicRand, "plankbridge".equals(devView));
        // Beside one of the bigger lakes, looking out over it
        if ("lake".equals(devView)) return game.devShots.lakeViewpoint(dynamicRand);

        Viewpoint found = null;
        if ("mountain".equals(devView)) {
            // Developer aid: some way off from the highest ground found, looking at it
            SpherePosition best = null;
            float highest = -Float.MAX_VALUE;
            for (int attempt = 0; attempt < 6000; attempt++) {
                SpherePosition p = SphereMath.randomPlace(dynamicRand, MapProjection.CLIP_LATITUDE);
                float h = TerrainMesh.getLayeredHeight(p, game.worldNoise);
                if (h > highest) { highest = h; best = p; }
            }
            // From the lowest dry spot on a ring round it, for a clear view up
            float lowest = Float.MAX_VALUE;
            for (int attempt = 0; attempt < 96; attempt++) {
                SpherePosition p = SphereMath.move(best, attempt * Math.PI * 2 / 96, 1250.0);
                float h = TerrainMesh.getLayeredHeight(p, game.worldNoise);
                if (h > game.seaLevelHeight + 2f && h < lowest) {
                    lowest = h;
                    found = new Viewpoint(p, SphereMath.heading(p, best));
                }
            }
        }
        if ("shore".equals(devView) || "swim".equals(devView)) {
            // Developer aid: stand on a coast facing the open sea, to watch the waves: dry land a
            // couple of hundred units from the sea, looking out where it's sea all the way out
            for (int attempt = 0; attempt < 200000; attempt++) {
                SpherePosition p = SphereMath.randomPlace(dynamicRand, MapProjection.CLIP_LATITUDE);
                if (TerrainMesh.getLayeredHeight(p, game.worldNoise) <= game.seaLevelHeight + 2.0f) continue;
                float shore = game.regionalManager.distanceToWater(p);
                if (shore < 150f || shore > 250f) continue;
                double look = Double.NaN;
                for (int a = 0; a < 16 && Double.isNaN(look); a++) {
                    boolean open = true;
                    for (double out = 600.0; out <= 3000.0 && open; out += 600.0) {
                        open = TerrainMesh.getLayeredHeight(SphereMath.move(p, a * Math.PI / 8, out), game.worldNoise) <= game.seaLevelHeight;
                    }
                    if (open) look = a * Math.PI / 8;
                }
                if (!Double.isNaN(look)) {
                    found = new Viewpoint(p, look);
                    if ("swim".equals(devView)) {
                        // Out in the water, looking back at the waves rolling in
                        SpherePosition out = SphereMath.move(p, look, 600.0);
                        found = new Viewpoint(out, SphereMath.carryHeading(p, out, look) + Math.PI);
                    }
                    break;
                }
            }
        }

        return found;
    }

    private static Viewpoint orElse(Viewpoint view, Viewpoint otherwise) {
        return view != null ? view : otherwise;
    }

    /**
     * The time of day: the round starts in daylight where the pod lands, at a random local
     * hour with the sun at least a little way up, which sets where on the planet it's noon.
     * Walk far enough east or west and it's another time of day there.
     */
    private void chooseTimeOfDay(SpherePosition place, java.util.Random rand) {
        double lat = Planet.latitude(place), lon = Planet.longitude(place);
        double hour = 0.0;
        for (int attempt = 0; attempt < 40; attempt++) {
            double candidate = (rand.nextDouble() * 2 - 1) * Math.PI * 0.42;   // up to about five hours from noon
            double elevation = Math.asin(Math.sin(lat) * Math.sin(game.sunDeclination)
                    + Math.cos(lat) * Math.cos(game.sunDeclination) * Math.cos(candidate));
            if (elevation > Math.toRadians(12.0)) {
                hour = candidate;
                break;
            }
        }
        game.noonLongitude = lon - hour;
        game.timeOfDay = (float) (0.5 + hour / (2 * Math.PI));
    }
}
