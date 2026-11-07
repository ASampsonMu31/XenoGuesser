import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.awt.image.BufferedImage;
import com.jogamp.opengl.*;
import java.awt.Graphics2D;

/**
 * The identification kit's magnifying glass: what's seen through its lens (plants, animals,
 * people, landmarks), found by projecting each into the view and checking it isn't hidden
 * (against the depth captured just before the player's body is drawn), and named.
 */
final class IdentificationLens extends GamePart {

    IdentificationLens(XenoGuesser_GLEventListener game) {
        super(game);
    }

    // The least share of the magnifying glass's lens something must fill (as a circle round
    // what's seen of it, on screen) to be made out and named: far things, too small in it, aren't
    private static final float IDENTIFY_SHARE = 0.05f;

    // At most this many named at once, nearest first
    private static final int MOST_IDENTIFIED = 6;

    // Something seen through the lens stays named (where it is now) for this many frames after
    // last passing the test, so it doesn't flicker as the view moves; and when each last passed
    private static final int LENS_HOLD_FRAMES = 8;

    private final Map<Object, Integer> lensLastSeen = new java.util.IdentityHashMap<>();

    // When each thing now named was first named (its marker fades in over the frames after)
    private final Map<Object, Integer> lensFirstSeen = new java.util.IdentityHashMap<>();

    // Over how many frames a new marker fades in
    private static final int FADE_IN_FRAMES = 6;

    private int lensFrame;

    // The screen radius of the lens last frame, so its depth is read over little more than it
    private float lastLensR;

    /** One thing named through the lens: how far off it is, where its middle is on the screen, its name and kind, and what it is. */
    private record Find(float distance, float x, float y, String name, boolean animal, Object key) {
    }

    /**
     * One plant or animal considered through the lens: its name, whether it's an animal, its
     * species, something that stays the same for it, and points round it.
     */
    @FunctionalInterface
    private interface LensCandidate {
        void consider(String name, boolean animal, int species, Object key, float[] points);
    }

    // Each plant model's outline as a sphere {height of its middle, radius} round its upright axis
    // (in the model's own units), so a tree can be ruled out before its every point is placed
    private final Map<float[], float[]> outlineBounds = new java.util.IdentityHashMap<>();

    /**
     * While the identification kit's magnifying glass is held up: each animal and (non grass)
     * plant seen through it, big enough in it to make out, marked with a target at its middle
     * and named beneath it on the HUD.
     */
    void drawLensFinds(float scale) {
        float[] lens = game.playerBody.lensView();
        float[] vp = game.lastSceneViewProjection;
        game.organismManager.setKeepOutlines(lens != null);
        if (lens == null || vp == null) {
            hideMarkers(0);
            lastLensR = 0f;
            lensLastSeen.clear();
            lensFirstSeen.clear();
            return;
        }
        // Everything here is in the camera's local frame (the eyes at the origin, x east, y up,
        // z south), as the body holding the lens is posed and as vp projects
        SphereFrame here = game.camera.frame();
        float[] centre = project(vp, lens[0], lens[1], lens[2]);
        float[] forward = game.camera.getLocalForward();
        float[] right = Affine.normalise(Affine.cross(forward, new float[] { 0f, 1f, 0f }));
        float[] edge = project(vp, lens[0] + right[0] * lens[3], lens[1] + right[1] * lens[3], lens[2] + right[2] * lens[3]);
        if (centre == null || edge == null) return;
        float lensR = (float) Math.hypot(edge[0] - centre[0], edge[1] - centre[1]);
        lastLensR = lensR;
        // How many pixels a metre spans a metre ahead (for sizing things up before placing them point by point)
        float[] ahead = project(vp, forward[0] * 100f, forward[1] * 100f, forward[2] * 100f);
        float[] aside = project(vp, forward[0] * 100f + right[0], forward[1] * 100f + right[1], forward[2] * 100f + right[2]);
        float focal = ahead == null || aside == null ? Float.MAX_VALUE : (float) Math.hypot(aside[0] - ahead[0], aside[1] - ahead[1]) * 100f;

        List<Find> finds = new ArrayList<>();
        // (its name, something to know it by, and points round it in the local frame {x, y, z, ...})
        lensFrame++;
        LensCandidate consider = (name, animal, species, key, points) -> {
            if (points[0] * points[0] + points[2] * points[2] > 2500f * 2500f) return;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float sumX = 0f, sumY = 0f, sumZ = 0f;
            int n = points.length / 3;
            for (int k = 0; k < points.length; k += 3) {
                float px = points[k], py = points[k + 1], pz = points[k + 2];
                float[] p = project(vp, px, py, pz);
                if (p == null) return;
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
                sumX += px;
                sumY += py;
                sumZ += pz;
            }
            // Only what's on the screen
            minX = Math.max(0f, minX);
            minY = Math.max(0f, minY);
            maxX = Math.min(game.screenWidth - 1f, maxX);
            maxY = Math.min(game.screenHeight - 1f, maxY);
            if (maxX - minX < 2f || maxY - minY < 2f) return;
            // Seen as a circle round it: how much of the lens it fills, counting only the share of it
            // in the lens that nothing nearer hides (anything at all: buildings, trees, the land)
            float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f;
            float r = (float) Math.hypot(maxX - minX, maxY - minY) * 0.5f;
            float needed = IDENTIFY_SHARE * (float) Math.PI * lensR * lensR;
            float overlap = circleOverlap(cx - centre[0], cy - centre[1], r, lensR);
            float mx = sumX / n, my = sumY / n, mz = sumZ / n;
            boolean passes = overlap >= needed && overlap * unhiddenShare(points, centre, lensR) >= needed;
            // ...and not hidden behind the land
            for (int i = 1; i < 16 && passes; i++) {
                float t = i / 16f;
                SpherePosition along = here.toWorld(mx * t, my * t, mz * t);
                if (TerrainMesh.getLayeredHeight(along, game.worldNoise) > SphereMath.altitude(along) + 1f) passes = false;
            }
            if (passes) {
                lensLastSeen.put(key, lensFrame);
                lensFirstSeen.putIfAbsent(key, lensFrame);
            }
            // (the tutorial counts what's been named, each plant and animal once)
            if (passes && Tutorial.active) game.tutorial.identified(animal, species, key, here.toWorld(mx, my, mz));
            Integer last = lensLastSeen.get(key);
            if (last == null || lensFrame - last > LENS_HOLD_FRAMES) return;
            // The target goes on its middle (its points' mean, which its limbs swinging hardly
            // move), or the middle of what's on the screen of it should that be off it
            float[] middle = project(vp, mx, my, mz);
            float tx = middle != null && middle[0] >= minX && middle[0] <= maxX ? middle[0] : cx;
            float ty = middle != null && middle[1] >= minY && middle[1] <= maxY ? middle[1] : cy;
            finds.add(new Find((float) Math.hypot(mx, mz), tx, ty, name, animal, key));
        };
        game.organismManager.forEachOutline((s, points) -> {
            // Sized up as a sphere round its points first: most are too far off or out of the lens
            if (!lensLastSeen.containsKey(points)) {
                double ax = 0, ay = 0, az = 0;
                int n = points.length / 3;
                for (int k = 0; k < points.length; k += 3) {
                    ax += points[k];
                    ay += points[k + 1];
                    az += points[k + 2];
                }
                ax /= n;
                ay /= n;
                az /= n;
                double reach = 0;
                for (int k = 0; k < points.length; k += 3) {
                    double dx = points[k] - ax, dy = points[k + 1] - ay, dz = points[k + 2] - az;
                    reach = Math.max(reach, dx * dx + dy * dy + dz * dz);
                }
                double[] m = here.toLocal(new SpherePosition(ax, ay, az));
                if (!couldFill(vp, (float) m[0], (float) m[1], (float) m[2], (float) Math.sqrt(reach), focal, centre, lensR)) return;
            }
            float[] local = new float[points.length];
            for (int k = 0; k < points.length; k += 3) {
                double[] l = here.toLocal(new SpherePosition(points[k], points[k + 1], points[k + 2]));
                local[k] = (float) l[0];
                local[k + 1] = (float) l[1];
                local[k + 2] = (float) l[2];
            }
            consider.consider(game.organismManager.speciesName(s), true, s, points, local);
        });
        if (game.floraOutlines != null) {
            game.trees.forEachNear(here.origin(), 2500.0, tree -> {
                float[] outline = game.floraOutlines[tree.speciesIndex()][tree.modelIndex()];
                if (outline == null) return;
                if (!lensLastSeen.containsKey(tree)) {
                    // (its middle taken straight up from its foot in the camera's frame: the
                    // planet's curve over a couple of kilometres tilts it far less than the margin)
                    float[] bounds = outlineBounds.computeIfAbsent(outline, IdentificationLens::uprightBounds);
                    double[] foot = here.toLocal(tree.position());
                    float size = tree.scale();
                    if (!couldFill(vp, (float) foot[0], (float) foot[1] + bounds[0] * size, (float) foot[2], bounds[1] * size * 1.1f + 0.5f,
                            focal, centre, lensR)) return;
                }
                // Placed as it's drawn: turned, scaled and stood where it grows (see plant)
                SphereFrame stands = SphereFrame.at(tree.position(), Math.toRadians(tree.rotationY()));
                float[] local = new float[outline.length];
                for (int k = 0; k < outline.length; k += 3) {
                    double[] l = here.toLocal(stands.toWorld(outline[k] * tree.scale(), outline[k + 1] * tree.scale(), outline[k + 2] * tree.scale()));
                    local[k] = (float) l[0];
                    local[k + 1] = (float) l[1];
                    local[k + 2] = (float) l[2];
                }
                consider.consider(game.floraName(tree.speciesIndex()), false, tree.speciesIndex(), tree, local);
            });
        }
        // (forgetting what's not been seen for a while)
        lensLastSeen.values().removeIf(last -> lensFrame - last > LENS_HOLD_FRAMES);
        lensFirstSeen.keySet().retainAll(lensLastSeen.keySet());
        // The nearest few
        finds.sort((a, b) -> Float.compare(a.distance(), b.distance()));
        int count = Math.min(MOST_IDENTIFIED, finds.size());
        showMarkers(finds.subList(0, count), scale);
    }

    // The targets' and names' pictures (painted once each, then only moved), and the scale they're painted at
    private final Map<String, BufferedImage> markerPictures = new HashMap<>();

    private float markerScale = -1f;

    // What each of the HUD's marker slots (a target and a name for each find) is showing
    private final BufferedImage[] slotPictures = new BufferedImage[MOST_IDENTIFIED * 2];

    // The target's size, and the gap between it and the name under it, before scaling
    private static final float TARGET_SIZE = 44f, NAME_GAP = 3f;

    // The ring's colour for plants (animals take the HUD's accent)
    private static final java.awt.Color PLANT_ACCENT = new java.awt.Color(150, 232, 140);

    /**
     * Each find marked on the HUD: a target over its middle and its name centred under it,
     * nearest first. A name that would cover one already placed is left off if it's the same
     * name (a herd needs naming once) or moved down clear of it otherwise.
     */
    private void showMarkers(List<Find> shown, float scale) {
        if (scale != markerScale) {
            markerPictures.clear();
            java.util.Arrays.fill(slotPictures, null);
            markerScale = scale;
        }
        List<java.awt.Rectangle> placed = new ArrayList<>();
        List<String> placedNames = new ArrayList<>();
        for (int k = 0; k < shown.size(); k++) {
            Find find = shown.get(k);
            String kind = find.animal() ? "a:" : "p:";
            BufferedImage target = markerPictures.computeIfAbsent(kind, n -> paintTarget(find.animal(), scale));
            BufferedImage label = markerPictures.computeIfAbsent(kind + find.name(), n -> paintName(find.name(), find.animal(), scale));
            Integer first = lensFirstSeen.get(find.key());
            float fade = first == null ? 1f : Math.min(1f, (lensFrame - first + 1) / (float) FADE_IN_FRAMES);
            int tx = Math.round(find.x() - target.getWidth() * 0.5f), ty = Math.round(find.y() - target.getHeight() * 0.5f);
            place(k * 2, target, tx, ty, fade);
            // (the name kept wholly on the screen, and clear of those already placed)
            java.awt.Rectangle at = new java.awt.Rectangle(Math.round(find.x() - label.getWidth() * 0.5f),
                    ty + target.getHeight() + Math.round(NAME_GAP * scale), label.getWidth(), label.getHeight());
            boolean named = true;
            for (int tries = 0; tries < 4; tries++) {
                at.x = Math.max(0, Math.min(game.screenWidth - at.width, at.x));
                at.y = Math.max(0, Math.min(game.screenHeight - at.height, at.y));
                int covered = -1;
                for (int p = 0; p < placed.size() && covered < 0; p++) if (placed.get(p).intersects(at)) covered = p;
                if (covered < 0) break;
                if (placedNames.get(covered).equals(find.name()) || tries == 3) {
                    named = false;
                    break;
                }
                at.y = placed.get(covered).y + placed.get(covered).height + Math.round(2 * scale);
            }
            if (named) {
                placed.add(at);
                placedNames.add(find.name());
                place(k * 2 + 1, label, at.x, at.y, fade);
            } else {
                game.hudRenderer.hud.setVisible("lens" + (k * 2 + 1), false);
            }
        }
        hideMarkers(shown.size());
    }

    /** Puts a picture in one of the HUD's marker slots, uploading it only if the slot showed another. */
    private void place(int slot, BufferedImage picture, int x, int y, float alpha) {
        String key = "lens" + slot;
        if (slotPictures[slot] != picture) {
            slotPictures[slot] = picture;
            game.hudRenderer.hud.put(key, picture, x, y);
        } else {
            game.hudRenderer.hud.move(key, x, y);
            game.hudRenderer.hud.setVisible(key, true);
        }
        game.hudRenderer.hud.setAlpha(key, alpha);
    }

    /** Hides the HUD's marker slots for the finds from the given one on. */
    private void hideMarkers(int from) {
        for (int k = from * 2; k < MOST_IDENTIFIED * 2; k++) game.hudRenderer.hud.setVisible("lens" + k, false);
    }

    /**
     * A target: a ring broken in four, ticks pointing in through the breaks and a dot in the
     * middle, all edged dark so it shows on snow or shade alike.
     */
    private static BufferedImage paintTarget(boolean animal, float scale) {
        java.awt.Color colour = animal ? HudStyle.ACCENT : PLANT_ACCENT;
        java.awt.Color shade = new java.awt.Color(0, 0, 0, 150);
        int size = (int) Math.ceil(TARGET_SIZE * scale + 6f * scale);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        HudStyle.smooth(g);
        float c = size * 0.5f;
        float ring = TARGET_SIZE * scale * 0.34f, outer = TARGET_SIZE * scale * 0.5f - scale, in = ring * 0.62f;
        for (int pass = 0; pass < 2; pass++) {
            g.setStroke(new java.awt.BasicStroke((pass == 0 ? 4.5f : 2.2f) * scale, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.setColor(pass == 0 ? shade : colour);
            // The ring, in four arcs with breaks at the top, bottom and sides
            for (int q = 0; q < 4; q++) {
                g.draw(new java.awt.geom.Arc2D.Float(c - ring, c - ring, ring * 2f, ring * 2f, q * 90f + 18f, 54f, java.awt.geom.Arc2D.OPEN));
            }
            // ...and the ticks through the breaks, from beyond the ring to just inside it
            g.draw(new java.awt.geom.Line2D.Float(c, c - outer, c, c - in));
            g.draw(new java.awt.geom.Line2D.Float(c, c + outer, c, c + in));
            g.draw(new java.awt.geom.Line2D.Float(c - outer, c, c - in, c));
            g.draw(new java.awt.geom.Line2D.Float(c + outer, c, c + in, c));
        }
        float dot = 2.6f * scale;
        g.setColor(shade);
        g.fill(new java.awt.geom.Ellipse2D.Float(c - dot - scale, c - dot - scale, (dot + scale) * 2f, (dot + scale) * 2f));
        g.setColor(colour);
        g.fill(new java.awt.geom.Ellipse2D.Float(c - dot, c - dot, dot * 2f, dot * 2f));
        g.dispose();
        return image;
    }

    /** A name on a dark glass pill, edged in its kind's colour. */
    private static BufferedImage paintName(String name, boolean animal, float scale) {
        java.awt.Color colour = animal ? HudStyle.ACCENT : PLANT_ACCENT;
        java.awt.Font font = HudStyle.font(java.awt.Font.BOLD, 19f * scale);
        java.awt.FontMetrics fm = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontMetrics(font);
        float edge = 1.5f * scale;
        float pillW = fm.stringWidth(name) + 24f * scale, pillH = fm.getAscent() + fm.getDescent() + 9f * scale;
        BufferedImage image = new BufferedImage((int) Math.ceil(pillW + edge * 2f), (int) Math.ceil(pillH + edge * 2f), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        HudStyle.smooth(g);
        java.awt.geom.RoundRectangle2D.Float pill = new java.awt.geom.RoundRectangle2D.Float(edge, edge, pillW, pillH, pillH, pillH);
        g.setColor(new java.awt.Color(10, 14, 22, 210));
        g.fill(pill);
        g.setStroke(new java.awt.BasicStroke(1.3f * scale));
        g.setColor(new java.awt.Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 170));
        g.draw(pill);
        g.setFont(font);
        g.setColor(HudStyle.VALUE);
        g.drawString(name, edge + (pillW - fm.stringWidth(name)) * 0.5f, edge + (pillH + fm.getAscent() - fm.getDescent()) * 0.5f);
        g.dispose();
        return image;
    }

    /** An outline's points as a sphere round its upright axis: {the height of its middle, its radius}. */
    private static float[] uprightBounds(float[] outline) {
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (int k = 1; k < outline.length; k += 3) {
            low = Math.min(low, outline[k]);
            high = Math.max(high, outline[k]);
        }
        float middle = (low + high) * 0.5f, reach = 0f;
        for (int k = 0; k < outline.length; k += 3) {
            float dy = outline[k + 1] - middle;
            reach = Math.max(reach, outline[k] * outline[k] + dy * dy + outline[k + 2] * outline[k + 2]);
        }
        return new float[] { middle, (float) Math.sqrt(reach) };
    }

    /**
     * Whether something within radius of a point (in the camera's local frame) could, at most,
     * fill enough of the lens to be named: generous, so only what plainly can't is ruled out.
     */
    private boolean couldFill(float[] vp, float x, float y, float z, float radius, float focal, float[] lensCentre, float lensR) {
        float w = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
        if (w < -radius) return false;
        // (round the eyes, or across their plane, it's left to the full test)
        if (w <= radius + 0.01f) return true;
        float sx = ((vp[0] * x + vp[4] * y + vp[8] * z + vp[12]) / w + 1f) * 0.5f * game.screenWidth;
        float sy = (1f - (vp[1] * x + vp[5] * y + vp[9] * z + vp[13]) / w) * 0.5f * game.screenHeight;
        // Its circle on the screen at most (a box round a disc reaches half as far again, and more)
        float most = 1.6f * radius * focal / (w - radius);
        if (most < (float) Math.sqrt(IDENTIFY_SHARE) * lensR) return false;
        return Math.hypot(sx - lensCentre[0], sy - lensCentre[1]) < most + lensR;
    }

    // The scene's depth over the magnifying glass's lens, as last read back (in the scene's own
    // pixels, rows from the bottom), with where that patch was and how the view was then
    private float[] lensDepth;

    private DepthPatch lensDepthPatch;

    /** Where a patch of depth was read from and the view it was read under. */
    private record DepthPatch(int x, int y, int w, int h, float near, float[] vp, int screenW, int screenH, int sceneW, int sceneH) {
    }

    // The depth's read back through two pixel buffers in turn, each collected a frame after it's
    // asked for (so the GPU never has to stop and wait for the CPU), with what each holds
    private final int[] depthBuffers = new int[2];

    private final long[] depthBufferBytes = new long[2];

    private final DepthPatch[] pendingPatch = new DepthPatch[2];

    private int nextDepthBuffer;

    /**
     * Collects the scene's depth over the lens asked for last frame, and asks for this frame's
     * (the body isn't drawn yet), or forgets it if the glass isn't held up.
     */
    void captureLensDepth(GL3 gl) {
        float[] lens = game.playerBody.lensView();
        float[] vp = game.lastSceneViewProjection;
        if (lens == null || vp == null || game.screenWidth <= 0) {
            lensDepth = null;
            lensDepthPatch = null;
            pendingPatch[0] = pendingPatch[1] = null;
            return;
        }
        if (depthBuffers[0] == 0) gl.glGenBuffers(2, depthBuffers, 0);
        // Last frame's, read back now that it's surely done
        int ready = 1 - nextDepthBuffer;
        DepthPatch patch = pendingPatch[ready];
        if (patch != null) {
            int n = patch.w() * patch.h();
            gl.glBindBuffer(GL3.GL_PIXEL_PACK_BUFFER, depthBuffers[ready]);
            java.nio.ByteBuffer mapped = gl.glMapBufferRange(GL3.GL_PIXEL_PACK_BUFFER, 0, (long) n * Float.BYTES, GL3.GL_MAP_READ_BIT);
            if (mapped != null) {
                float[] depth = lensDepth != null && lensDepth.length == n ? lensDepth : new float[n];
                mapped.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().get(depth, 0, n);
                gl.glUnmapBuffer(GL3.GL_PIXEL_PACK_BUFFER);
                lensDepth = depth;
                lensDepthPatch = patch;
            }
            gl.glBindBuffer(GL3.GL_PIXEL_PACK_BUFFER, 0);
            pendingPatch[ready] = null;
        }

        float[] centre = project(vp, lens[0], lens[1], lens[2]);
        if (centre == null) return;
        float toScene = game.currentWidth / (float) game.screenWidth;
        // Round the lens as it was last seen, with room for it to move (it's taken from last
        // frame's pose); generously round where it's held until it's been seen
        int most = Math.round(game.currentHeight * 0.4f);
        int radius = lastLensR > 0f ? Math.min(most, Math.round(lastLensR * toScene * 1.25f + game.currentHeight * 0.04f)) : most;
        int cx = Math.round(centre[0] * toScene), cy = game.currentHeight - Math.round(centre[1] * game.currentHeight / (float) game.screenHeight);
        int x0 = Math.max(0, cx - radius), y0 = Math.max(0, cy - radius);
        int w = Math.min(game.currentWidth, cx + radius) - x0, h = Math.min(game.currentHeight, cy + radius) - y0;
        if (w <= 0 || h <= 0) return;
        int slot = nextDepthBuffer;
        long bytes = (long) w * h * Float.BYTES;
        gl.glBindBuffer(GL3.GL_PIXEL_PACK_BUFFER, depthBuffers[slot]);
        if (depthBufferBytes[slot] < bytes) {
            gl.glBufferData(GL3.GL_PIXEL_PACK_BUFFER, bytes, null, GL3.GL_STREAM_READ);
            depthBufferBytes[slot] = bytes;
        }
        gl.glPixelStorei(GL3.GL_PACK_ALIGNMENT, 4);
        gl.glReadPixels(x0, y0, w, h, GL3.GL_DEPTH_COMPONENT, GL3.GL_FLOAT, 0L);
        gl.glBindBuffer(GL3.GL_PIXEL_PACK_BUFFER, 0);
        pendingPatch[slot] = new DepthPatch(x0, y0, w, h, game.currentNearPlane, vp.clone(), game.screenWidth, game.screenHeight,
                game.currentWidth, game.currentHeight);
        nextDepthBuffer = 1 - slot;
    }

    /**
     * Of something's points that fall within the lens, the share nothing nearer hides: each
     * point's distance from the eyes against the scene's depth there (as last read back, so
     * placed with the view it was read under), allowing for the thing's own size (so its far
     * side isn't counted as hidden by its near side). 0 until the depth's been read.
     */
    private float unhiddenShare(float[] points, float[] lensCentre, float lensR) {
        float[] depth = lensDepth;
        DepthPatch patch = lensDepthPatch;
        if (depth == null || patch == null) return 0f;
        float[] vp = patch.vp();
        // How big it is: its points' spread
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int k = 0; k < points.length; k += 3) {
            minX = Math.min(minX, points[k]);
            maxX = Math.max(maxX, points[k]);
            minY = Math.min(minY, points[k + 1]);
            maxY = Math.max(maxY, points[k + 1]);
            minZ = Math.min(minZ, points[k + 2]);
            maxZ = Math.max(maxZ, points[k + 2]);
        }
        float size = (float) Math.sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY) + (maxZ - minZ) * (maxZ - minZ));
        float near = patch.near(), far = XenoGuesser_GLEventListener.FAR_PLANE;
        float toSceneX = patch.sceneW() / (float) patch.screenW(), toSceneY = patch.sceneH() / (float) patch.screenH();
        int inLens = 0, unhidden = 0;
        for (int k = 0; k < points.length; k += 3) {
            float px = points[k], py = points[k + 1], pz = points[k + 2];
            float w = vp[3] * px + vp[7] * py + vp[11] * pz + vp[15];
            if (w <= 0.01f) continue;
            float sx = ((vp[0] * px + vp[4] * py + vp[8] * pz + vp[12]) / w + 1f) * 0.5f * patch.screenW();
            float sy = (1f - (vp[1] * px + vp[5] * py + vp[9] * pz + vp[13]) / w) * 0.5f * patch.screenH();
            if (Math.hypot(sx - lensCentre[0], sy - lensCentre[1]) > lensR) continue;
            int rx = Math.round(sx * toSceneX) - patch.x(), ry = patch.sceneH() - 1 - Math.round(sy * toSceneY) - patch.y();
            if (rx < 0 || ry < 0 || rx >= patch.w() || ry >= patch.h()) continue;
            inLens++;
            // The scene's depth there, as a distance ahead of the eyes (as w is)
            float ndc = depth[ry * patch.w() + rx] * 2f - 1f;
            float sceneDistance = 2f * near * far / (far + near - ndc * (far - near));
            if (sceneDistance >= w - size * 0.5f - 2f) unhidden++;
        }
        return inLens == 0 ? 0f : unhidden / (float) inLens;
    }

    /** Where a point in the world lands on the screen, in its pixels from the top left; null if it's behind the eyes. */
    private float[] project(float[] vp, float x, float y, float z) {
        float cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12];
        float cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13];
        float w = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
        if (w <= 0.01f) return null;
        return new float[] { (cx / w + 1f) * 0.5f * game.screenWidth, (1f - cy / w) * 0.5f * game.screenHeight };
    }

    /** The area two circles share: one of radius r with its middle (dx, dy) from the other's, of radius big. */
    private static float circleOverlap(float dx, float dy, float r, float big) {
        double d = Math.hypot(dx, dy);
        if (d >= r + big) return 0f;
        if (d <= Math.abs(big - r)) return (float) (Math.PI * Math.min(r, big) * Math.min(r, big));
        double a = r * r * Math.acos((d * d + r * r - big * big) / (2 * d * r));
        double b = big * big * Math.acos((d * d + big * big - r * r) / (2 * d * big));
        double c = 0.5 * Math.sqrt((-d + r + big) * (d + r - big) * (d - r + big) * (d + r + big));
        return (float) (a + b - c);
    }
}
