import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import com.jogamp.opengl.*;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Developer aids (see Dev): saved frames, the main menu's picture (and the picturesque site
 * it's taken at), pictures of signs and vehicles, and places to stand to look at a river,
 * lake, plant, herd or the landing pod.
 */
final class DevShots extends GamePart {

    DevShots(XenoGuesser_GLEventListener game) {
        super(game);
    }

    /**
     * Developer aid for the main menu's background: a minute after the round starts, the
     * view moves out to look back at the landing pod, and a frame without the HUD or the
     * player's body is saved as the menu art.
     */
    static final boolean SIGN_SHOT = Dev.property("xenoguesser.signshot") != null;

    static final String VEHICLE_SHOT = Dev.property("xenoguesser.vehicleshot");

    // (after this many frames: -Dxenoguesser.vehicleshotframe)
    static final int VEHICLE_SHOT_FRAME = Dev.integer("xenoguesser.vehicleshotframe", 120);

    int vehicleShotFrames;

    private final java.util.Set<InfrastructureObject> signsShot = new java.util.HashSet<>();

    private SpherePosition signShotEye, signShotTarget;

    /**
     * Developer aid: from a little after the round starts, stands in front of one nearby sign
     * after another (those with pictures or titles first) and saves a frame of each.
     */
    void signShot(GL3 gl) {
        int frame = game.framesRenderedSinceReady - 40;
        if (frame < 0 || frame > 40 * 8) return;
        if (signShotEye != null) lookFrom(signShotEye, signShotTarget);
        if (frame % 40 == 0) {
            game.hideFirstPerson = true;
            InfrastructureObject best = null;
            double bestScore = Double.MAX_VALUE;
            SpherePosition at = game.camera.getSpherePosition();
            for (List<InfrastructureObject> objects : game.infraCache.values()) {
                for (InfrastructureObject obj : objects) {
                    if (obj.type != InfrastructureObject.Type.SIGN || signsShot.contains(obj)) continue;
                    // -Dxenoguesser.signshot=shaped: only posters on leaning or round walls
                    if ("shaped".equals(Dev.property("xenoguesser.signshot")) && !obj.shaped) continue;
                    double score = SphereMath.distance(obj.frame.origin(), at);
                    if (obj.pictureCount == 0 && !obj.wallMounted) score += 1e9;
                    if (score < bestScore) { bestScore = score; best = obj; }
                }
            }
            if (best == null) return;
            signsShot.add(best);
            // (in the sign's own frame, whose local +z its face looks along)
            float side = Float.parseFloat(Dev.property("xenoguesser.signside", "1"));
            float back = best.wallMounted ? Math.min(30f, Math.max(best.boardWidth * 0.75f, best.boardHeight * 1.6f) + 6f) : 40f;
            float lift = best.wallMounted ? 0f : 32f;
            // -Dxenoguesser.signslant=0.8 views it from off to one side, by that much of the distance
            float slant = Float.parseFloat(Dev.property("xenoguesser.signslant", "0"));
            signShotEye = best.frame.toWorld(-back * slant * side, lift, back * side);
            signShotTarget = best.frame.toWorld(0.0, lift, 0.0);
            lookFrom(signShotEye, signShotTarget);
            System.out.println("[SIGNSHOT] sign " + signsShot.size() + ": pictures " + best.pictureCount + (best.wallMounted ? (best.banner ? " banner" : " poster") : "") + ", title " + best.titleLength
                    + ", direction " + game.nationDirections.getOrDefault(best.nationId, 0) + ", nation " + best.nationId
                    + ", patriotism " + game.infraManager.patriotismOf(best.nationId));
        } else if (frame % 40 == 30) {
            saveFrame(gl, "sign_" + signsShot.size() + ".png");
        }
    }

    /** Developer aid: stands the camera at a place looking at another. */
    private void lookFrom(SpherePosition eye, SpherePosition target) {
        game.camera.setSphereView(eye, 0f, 0f);
        game.camera.lookAt(target);
    }

    // The main menu's picture: of this many landing sites, the one whose view (as menuShot frames
    // it) has the most of water, mountains behind and houses about, each counted up to its own
    // fill: water over this share of the ground seen, the hills this steep (degrees up), this many
    // houses near the pod
    private static final int PICTURESQUE_SITES = 1500;

    private static final double VIEW_WATER = 0.3, VIEW_MOUNTAINS = 5.0, VIEW_HOUSES = 12.0;

    /** Developer aid: the landing site with the most picturesque view for the main menu's picture (see menuShot). */
    Viewpoint picturesqueSite(java.util.Random rand) {
        Viewpoint best = null;
        double bestScore = -1;
        for (int i = 0; i < PICTURESQUE_SITES; i++) {
            Viewpoint site = game.roundSpawner.landingSite(rand);
            if (site == null) continue;
            LandingPod pod = new LandingPod();
            pod.place(site.place(), site.heading(), p -> TerrainMesh.getLayeredHeight(p, game.worldNoise));
            SpherePosition[] view = menuView(pod);
            // (on fairly level ground, so the view looks out over the land, not up a hillside)
            double flatness = 0;
            for (int k = 0; k < 8; k++) {
                SpherePosition p = SphereMath.move(site.place(), k * Math.PI / 4, 250);
                flatness = Math.max(flatness, Math.abs(TerrainMesh.getLayeredHeight(p, game.worldNoise) - pod.ground()));
            }
            if (flatness > 25) continue;
            double eyeAltitude = SphereMath.altitude(view[0]);
            double look = SphereMath.heading(view[0], view[1]);
            // Water, mountains and houses across the view, near to far, counting only what the
            // land nearer the camera doesn't hide
            int seen = 0, wet = 0, houses = 0;
            double steepest = -90;
            SphereFrame frame = pod.frame();
            for (double turn = -0.6; turn <= 0.601; turn += 0.15) {
                double horizon = -90, nearHorizon = -90;
                for (double out = 60; out <= 9000; out += out < 1500 ? 30 : out < 3000 ? 100 : 400) {
                    SpherePosition p = SphereMath.move(view[0], look + turn, out);
                    float ground = TerrainMesh.getLayeredHeight(p, game.worldNoise);
                    boolean water = ground <= game.seaLevelHeight || game.underFreshWater(p, ground);
                    double top = Math.max(ground, game.seaLevelHeight);
                    double angle = Math.toDegrees(Math.atan2(top - eyeAltitude, out));
                    boolean visible = angle >= horizon - 0.05;
                    if (out <= 3000) {
                        seen++;
                        if (water && visible) wet++;
                    }
                    if (out <= 1200 && game.infraManager.isBuildingLocation(p, 0f)
                            && Math.toDegrees(Math.atan2(ground + 60 - eyeAltitude, out)) > horizon) houses++;
                    if (out < 1500) nearHorizon = Math.max(nearHorizon, angle);
                    else if (angle > nearHorizon + 1.0) steepest = Math.max(steepest, angle);
                    horizon = Math.max(horizon, angle);
                }
            }
            double water = (double) wet / Math.max(1, seen);
            // (each there at all, and nothing built between the camera and the pod)
            boolean blocked = game.infraManager.isPrivateGround(view[0], 40f) || game.infraManager.isBuildingLocation(view[0], 40f)
                    || game.infraManager.isRoadLocation(view[0], 15f);
            for (double t = 0.15; t < 0.9; t += 0.15) {
                SpherePosition p = SphereMath.move(view[0], SphereMath.heading(view[0], frame.toSphere(0, 0, 0)), 165 * t);
                if (game.infraManager.isPrivateGround(p, 10f) || game.infraManager.isBuildingLocation(p, 12f) || game.infraManager.isRoadLocation(p, 6f)) blocked = true;
            }
            if (blocked) continue;
            double waterScore = Math.min(water, VIEW_WATER) / VIEW_WATER - Math.max(0, water - 0.6);
            double mountainScore = Math.max(0, Math.min(steepest, VIEW_MOUNTAINS)) / VIEW_MOUNTAINS;
            double houseScore = Math.min(houses, VIEW_HOUSES) / VIEW_HOUSES;
            // (some of each, not all of one)
            double score = waterScore + mountainScore + houseScore + 2 * Math.min(waterScore, Math.min(mountainScore, houseScore));
            if (score > bestScore) {
                bestScore = score;
                System.out.printf("[MENU] better site: water %.2f, hills %.1f degrees, houses %d%n", water, steepest, houses);
                best = site;
            }
        }
        if (best != null) System.out.printf("[MENU] Picturesque site chosen (score %.2f of 5)%n", bestScore);
        return best;
    }

    /** Where the main menu's picture is taken from and what it looks at, round a pod (see menuShot): {eye, target}. */
    private SpherePosition[] menuView(LandingPod landed) {
        SphereFrame pod = landed.frame();
        double side = Math.toRadians(Double.parseDouble(Dev.property("xenoguesser.menuangle", "38")));
        double distance = Double.parseDouble(Dev.property("xenoguesser.menudistance", "165"));
        double ex = Math.sin(side) * distance, ez = Math.cos(side) * distance;
        SpherePosition standing = pod.toSphere(ex, 0.0, ez);
        double eye = Math.max(TerrainMesh.getLayeredHeight(standing, game.worldNoise), game.seaLevelHeight) + 26.0;
        // The pod sits just right of the middle: the menu takes the left, the spaceman the far right
        double lookSide = side + Math.PI + Math.toRadians(Double.parseDouble(Dev.property("xenoguesser.menuoffset", "1.5")));
        return new SpherePosition[] { SphereMath.atAltitude(standing, eye),
            pod.toSphere(ex + Math.sin(lookSide) * 100.0, landed.ground() + 24.0, ez + Math.cos(lookSide) * 100.0) };
    }

    void menuShot(GL3 gl) {
        if (game.framesRenderedSinceReady == 60) {
            game.hideFirstPerson = true;
            // In the pod's own space (x across, z the way its stairs run): off to one side of
            // the stairs, a little above head height, looking at the pod (see menuView)
            SpherePosition[] view = menuView(game.landingPod);
            game.camera.setSphereView(view[0], 0f, 0f);
            game.camera.lookAt(view[1]);
            menuShotHold = true;
        } else if (game.framesRenderedSinceReady == 140) {
            saveFrameTo(gl, new File(LoadingArt.MENU));
            System.out.println("[MENU] Background saved to " + LoadingArt.MENU);
        }
    }

    boolean menuShotHold;

    /** Debug aid: writes the current frame next to this run's generated textures. */
    void saveFrame(GL3 gl, String fileName) {
        saveFrameTo(gl, new File(WorldArtGenerator.OUTPUT_DIR, fileName));
    }

    private void saveFrameTo(GL3 gl, File file) {
        int w = game.screenWidth, h = game.screenHeight;
        java.nio.ByteBuffer pixels = com.jogamp.common.nio.Buffers.newDirectByteBuffer(w * h * 4);
        gl.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels);
        Thread writer = new Thread(() -> {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    int rgb = ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255);
                    img.setRGB(x, h - 1 - y, rgb);
                }
            }
            try {
                ImageIO.write(img, "png", file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "first-frame-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** Developer aid: on the bank of a river at least -Dxenoguesser.riverwidth wide (default 12), looking upstream across it. */
    Viewpoint riverViewpoint(java.util.Random rand) {
        Rivers rivers = game.worldNoise.rivers;
        if (rivers == null || rivers.reaches().isEmpty()) return null;
        float wide = Float.parseFloat(Dev.property("xenoguesser.riverwidth", "12"));
        for (int attempt = 0; attempt < 2000; attempt++) {
            Rivers.Reach r = rivers.reaches().get(rand.nextInt(rivers.reaches().size()));
            int k = 2 + rand.nextInt(Math.max(1, r.size() - 4));
            if (k + 1 >= r.size() || r.inLake[k] || r.halfWidth[k] * 2f < wide) continue;
            double[] p = r.points[k], q = r.points[k + 1];
            SpherePosition here = SphereMath.atAltitude(new GeodesicChunkGrid.Direction(p[0], p[1], p[2]), 0.0);
            SpherePosition down = SphereMath.atAltitude(new GeodesicChunkGrid.Direction(q[0], q[1], q[2]), 0.0);
            double downstream = SphereMath.heading(here, down);
            SpherePosition bank = SphereMath.move(here, downstream + Math.PI / 2, r.halfWidth[k] + Double.parseDouble(Dev.property("xenoguesser.riverback", "14")));
            if (TerrainMesh.getLayeredHeight(bank, game.worldNoise) <= game.seaLevelHeight + 1f) continue;
            // (back down the bank a little, looking up the river and across it)
            return new Viewpoint(bank, downstream - Math.PI * 0.62);
        }
        return null;
    }

    /** Developer aid: where a plant species (-Dxenoguesser.plant, or any) grows thickest of a few thousand places tried. */
    Viewpoint plantViewpoint(java.util.Random rand) {
        int s = Dev.integer("xenoguesser.plant", rand.nextInt(game.NUM_SPECIES));
        SpherePosition best = null;
        float most = 0f;
        for (int attempt = 0; attempt < 4000; attempt++) {
            double x = rand.nextGaussian(), y = rand.nextGaussian(), z = rand.nextGaussian(), l = Math.sqrt(x * x + y * y + z * z);
            GeodesicChunkGrid.Direction d = new GeodesicChunkGrid.Direction(x / l, y / l, z / l);
            SpherePosition at = SphereMath.atAltitude(d, 0.0);
            if (TerrainMesh.getLayeredHeight(at, game.worldNoise) <= game.seaLevelHeight + 2f) continue;
            float here = game.floraSpecies.floraRanges[s].keep(game.floraSpecies.floraRaw(s, d));
            if (here > most) {
                most = here;
                best = at;
            }
        }
        System.out.println("[DEV] Plant " + s + " (" + game.speciesConfigs[s].design.kind() + ") thickest found: " + most);
        return best == null ? null : new Viewpoint(best, rand.nextDouble() * Math.PI * 2);
    }

    /** Developer aid: on the shore of a lake of a dozen texels or more, looking across it. */
    Viewpoint lakeViewpoint(java.util.Random rand) {
        Rivers rivers = game.worldNoise.rivers;
        if (rivers == null) return null;
        List<Rivers.Lake> big = new ArrayList<>();
        for (Rivers.Lake lake : rivers.lakes()) if (lake.texels.size() >= 12) big.add(lake);
        if (big.isEmpty()) return null;
        Rivers.Lake lake = big.get(rand.nextInt(big.size()));
        double[] c = lake.texels.get(0);
        SpherePosition middle = SphereMath.atAltitude(new GeodesicChunkGrid.Direction(c[0], c[1], c[2]), 0.0);
        // Out from the middle until the ground rises above the water, then a little further
        for (int attempt = 0; attempt < 32; attempt++) {
            double heading = rand.nextDouble() * Math.PI * 2;
            for (double out = 10; out < 2000; out += 10) {
                SpherePosition p = SphereMath.move(middle, heading, out);
                if (TerrainMesh.getLayeredHeight(p, game.worldNoise) > lake.level + 1f) {
                    return new Viewpoint(SphereMath.move(middle, heading, out + 25), heading + Math.PI);
                }
            }
        }
        return null;
    }

    // Developer aid: -Dxenoguesser.framesequence=6 saves that many frames in a row after later_frame.png
    // (sequence_1.png on), to see what changes from one frame to the next
    static final int FRAME_SEQUENCE = Dev.integer("xenoguesser.framesequence", 0);

    /** Developer aid: turns the view to face the partner, level (or pitchDegrees up), for checking how they're drawn. */
    /** Developer aid: faces the nearest herd (stepping in to about 90 away if it's further), a little down, and logs how far it was. */
    public void lookAtHerd() {
        SpherePosition here = game.camera.getSpherePosition();
        SpherePosition herd = game.organismManager.nearestHerd(here);
        if (herd == null) {
            System.out.println("[HERD] No herd near");
            return;
        }
        double distance = SphereMath.distance(here, herd);
        System.out.printf(java.util.Locale.ROOT, "[HERD] Nearest herd %.0f units away%n", distance);
        if (distance > 120.0) {
            // (stepping in to about 90 away, on the ground there)
            SpherePosition near = SphereMath.move(herd, SphereMath.heading(herd, here), 90.0);
            here = SphereMath.atAltitude(near, Math.max(game.seaLevelHeight, game.groundHeightAt(near)) + game.playerEyeHeight);
            distance = 90.0;
        }
        game.camera.setSphereView(here, (float) Math.toDegrees(SphereMath.heading(here, herd)), (float) -Math.toDegrees(Math.atan2(15.0, Math.max(20.0, distance))));
    }

    /**
     * Developer aid: stands the view distance out from the pod's middle, round from straight
     * down its stairs by aroundDegrees, raised by up, looking back at it (for checking how it's built).
     */
    public void viewPod(double distance, double aroundDegrees, double up) {
        SphereFrame pod = game.landingPod.frame();
        if (pod == null) return;
        SpherePosition middle = pod.origin();
        double[] stairs = pod.worldDirection(0, 0, 1);
        SpherePosition ahead = SphereMath.atAltitude(new SpherePosition(middle.x() + stairs[0], middle.y() + stairs[1], middle.z() + stairs[2]), SphereMath.altitude(middle));
        double down = SphereMath.heading(middle, ahead) + Math.toRadians(aroundDegrees);
        SpherePosition at = SphereMath.move(middle, down, distance);
        // (from above whichever is higher, the ground here or the pod's, aimed at its hatch)
        double eyeAt = Math.max(game.groundHeightAt(at), game.landingPod.ground()) + game.playerEyeHeight + up;
        at = SphereMath.atAltitude(at, eyeAt);
        double back = SphereMath.heading(at, middle);
        game.camera.setSphereView(at, (float) Math.toDegrees(back), (float) Math.toDegrees(Math.atan2(game.landingPod.ground() + 25.0 - eyeAt, distance)));
    }

    // Developer aid: a picture of the next frame as drawn (read back from the GL, so another
    // window over this one doesn't spoil it) is saved here, then this is cleared
    volatile java.io.File frameShot;

    public void saveNextFrame(java.io.File file) {
        frameShot = file;
    }

    void writeFrameShot(GL3 gl, int width, int height) {
        java.io.File file = frameShot;
        if (file == null) return;
        frameShot = null;
        java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(width * height * 4);
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl.glReadPixels(0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = ((height - 1 - y) * width + x) * 4;
                image.setRGB(x, y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
            }
        }
        try {
            file.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(image, "png", file);
            System.out.println("[SHOT] Frame saved to " + file);
        } catch (java.io.IOException e) {
            System.err.println("Couldn't save the frame: " + e.getMessage());
        }
    }
}
