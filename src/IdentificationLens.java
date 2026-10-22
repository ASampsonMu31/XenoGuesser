import java.util.Map;
import java.util.ArrayList;
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

    private String lensSignature = "";

    // Something seen through the lens stays named (where it is now) for this many frames after
    // last passing the test, so it doesn't flicker as the view moves; and when each last passed
    private static final int LENS_HOLD_FRAMES = 8;

    private final Map<Object, Integer> lensLastSeen = new java.util.IdentityHashMap<>();

    private int lensFrame;

    /** One plant or animal considered through the lens: its name, something that stays the same for it, and points round it. */
    @FunctionalInterface
    private interface LensCandidate {
        void consider(String name, Object key, float[] points);
    }

    /**
     * While the identification kit's magnifying glass is held up: each animal and (non grass)
     * plant seen through it, big enough in it to make out, boxed (round what's seen of it on
     * the screen) and named on the HUD.
     */
    void drawLensFinds(float scale) {
        float[] lens = game.playerBody.lensView();
        float[] vp = game.lastSceneViewProjection;
        game.organismManager.setKeepOutlines(lens != null);
        if (lens == null || vp == null) {
            game.hudRenderer.hud.setVisible("lens", false);
            lensSignature = "";
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

        // Every candidate: {distance, left, top, right, bottom} on screen, and its name
        List<float[]> boxes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        // (its name, something to know it by, and points round it in the local frame {x, y, z, ...})
        lensFrame++;
        LensCandidate consider = (name, key, points) -> {
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
            boolean passes = overlap >= needed && overlap * unhiddenShare(vp, points, centre, lensR) >= needed;
            // ...and not hidden behind the land
            for (int i = 1; i < 16 && passes; i++) {
                float t = i / 16f;
                SpherePosition along = here.toWorld(mx * t, my * t, mz * t);
                if (TerrainMesh.getLayeredHeight(along, game.worldNoise) > SphereMath.altitude(along) + 1f) passes = false;
            }
            if (passes) lensLastSeen.put(key, lensFrame);
            Integer last = lensLastSeen.get(key);
            if (last == null || lensFrame - last > LENS_HOLD_FRAMES) return;
            boxes.add(new float[] { (float) Math.hypot(mx, mz), minX, minY, maxX, maxY });
            names.add(name);
        };
        game.organismManager.forEachOutline((s, points) -> {
            float[] local = new float[points.length];
            for (int k = 0; k < points.length; k += 3) {
                double[] l = here.toLocal(new SpherePosition(points[k], points[k + 1], points[k + 2]));
                local[k] = (float) l[0];
                local[k + 1] = (float) l[1];
                local[k + 2] = (float) l[2];
            }
            consider.consider(game.organismManager.speciesName(s), points, local);
        });
        if (game.floraOutlines != null) {
            game.trees.forEachNear(here.origin(), 2500.0, tree -> {
                float[] outline = game.floraOutlines[tree.speciesIndex()][tree.modelIndex()];
                if (outline == null) return;
                // Placed as it's drawn: turned, scaled and stood where it grows (see plant)
                SphereFrame stands = SphereFrame.at(tree.position(), Math.toRadians(tree.rotationY()));
                float[] local = new float[outline.length];
                for (int k = 0; k < outline.length; k += 3) {
                    double[] l = here.toLocal(stands.toWorld(outline[k] * tree.scale(), outline[k + 1] * tree.scale(), outline[k + 2] * tree.scale()));
                    local[k] = (float) l[0];
                    local[k + 1] = (float) l[1];
                    local[k + 2] = (float) l[2];
                }
                consider.consider(game.floraName(tree.speciesIndex()), tree, local);
            });
        }
        // (forgetting what's not been seen for a while)
        lensLastSeen.values().removeIf(last -> lensFrame - last > LENS_HOLD_FRAMES);
        // The nearest few
        Integer[] order = new Integer[boxes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(boxes.get(a)[0], boxes.get(b)[0]));
        int count = Math.min(MOST_IDENTIFIED, order.length);

        StringBuilder signature = new StringBuilder();
        for (int k = 0; k < count; k++) {
            float[] b = boxes.get(order[k]);
            signature.append('|').append(names.get(order[k])).append(Math.round(b[1])).append(',').append(Math.round(b[2]))
                    .append(',').append(Math.round(b[3])).append(',').append(Math.round(b[4]));
        }
        if (signature.toString().equals(lensSignature)) {
            game.hudRenderer.hud.setVisible("lens", count > 0);
            return;
        }
        lensSignature = signature.toString();
        if (count == 0) {
            game.hudRenderer.hud.setVisible("lens", false);
            return;
        }
        // Each box with its name: on a tab just above the box, or inside its top should there be
        // no room above (so the whole of both is always on the screen)
        java.awt.Font font = HudStyle.font(java.awt.Font.BOLD, 14f * scale);
        java.awt.FontMetrics fm = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontMetrics(font);
        float th = fm.getAscent() + 6 * scale;
        float[][] tabs = new float[count][];
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, rightEdge = 0f, bottom = 0f;
        for (int k = 0; k < count; k++) {
            float[] b = boxes.get(order[k]);
            float tw = fm.stringWidth(names.get(order[k])) + 10 * scale;
            float tx = Math.min(b[1], game.screenWidth - tw), ty = b[2] - th - 2 * scale;
            if (ty < 0f) ty = b[2] + 2 * scale;
            tabs[k] = new float[] { Math.max(0f, tx), ty, tw };
            left = Math.min(left, Math.min(b[1], tabs[k][0]));
            top = Math.min(top, Math.min(b[2], ty));
            rightEdge = Math.max(rightEdge, Math.max(b[3], tabs[k][0] + tw));
            bottom = Math.max(bottom, b[4]);
        }
        int ox = (int) Math.floor(left) - 4, oy = (int) Math.floor(top) - 4;
        int w = (int) Math.ceil(rightEdge) - ox + 5, h = (int) Math.ceil(bottom) - oy + 5;
        BufferedImage image = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        HudStyle.smooth(g);
        g.translate(-ox, -oy);
        g.setFont(font);
        for (int k = 0; k < count; k++) {
            float[] b = boxes.get(order[k]);
            java.awt.geom.Rectangle2D.Float box = new java.awt.geom.Rectangle2D.Float(b[1], b[2], b[3] - b[1], b[4] - b[2]);
            g.setStroke(new java.awt.BasicStroke(4f * scale));
            g.setColor(new java.awt.Color(0, 0, 0, 150));
            g.draw(box);
            g.setStroke(new java.awt.BasicStroke(2f * scale));
            g.setColor(HudStyle.ACCENT);
            g.draw(box);
            float[] tab = tabs[k];
            g.setColor(new java.awt.Color(10, 14, 22, 205));
            g.fill(new java.awt.geom.RoundRectangle2D.Float(tab[0], tab[1], tab[2], th, 6 * scale, 6 * scale));
            g.setColor(HudStyle.VALUE);
            g.drawString(names.get(order[k]), tab[0] + 5 * scale, tab[1] + th - 4 * scale);
        }
        g.dispose();
        game.hudRenderer.hud.put("lens", image, ox, oy);
    }

    // The scene's depth over the magnifying glass's lens, read each frame while it's held up (in
    // the scene's own pixels, rows from the bottom), where that patch is, and the planes then
    private float[] lensDepth;

    private int lensDepthX, lensDepthY, lensDepthW, lensDepthH;

    private float lensDepthNear;

    private java.nio.FloatBuffer lensDepthBuffer;

    /** Reads the scene's depth over the lens (the body isn't drawn yet), or forgets it if the glass isn't held up. */
    void captureLensDepth(GL3 gl) {
        float[] lens = game.playerBody.lensView();
        float[] vp = game.lastSceneViewProjection;
        if (lens == null || vp == null || game.screenWidth <= 0) {
            lensDepth = null;
            return;
        }
        float[] centre = project(vp, lens[0], lens[1], lens[2]);
        if (centre == null) {
            lensDepth = null;
            return;
        }
        float toScene = game.currentWidth / (float) game.screenWidth;
        // (generously round the lens: it's taken from last frame's pose)
        int radius = Math.round(game.currentHeight * 0.4f);
        int cx = Math.round(centre[0] * toScene), cy = game.currentHeight - Math.round(centre[1] * game.currentHeight / (float) game.screenHeight);
        int x0 = Math.max(0, cx - radius), y0 = Math.max(0, cy - radius);
        int w = Math.min(game.currentWidth, cx + radius) - x0, h = Math.min(game.currentHeight, cy + radius) - y0;
        if (w <= 0 || h <= 0) {
            lensDepth = null;
            return;
        }
        if (lensDepthBuffer == null || lensDepthBuffer.capacity() < w * h) lensDepthBuffer = com.jogamp.common.nio.Buffers.newDirectFloatBuffer(w * h);
        lensDepthBuffer.clear();
        gl.glPixelStorei(GL3.GL_PACK_ALIGNMENT, 4);
        gl.glReadPixels(x0, y0, w, h, GL3.GL_DEPTH_COMPONENT, GL3.GL_FLOAT, lensDepthBuffer);
        float[] depth = lensDepth != null && lensDepth.length == w * h ? lensDepth : new float[w * h];
        lensDepthBuffer.get(depth, 0, w * h);
        lensDepth = depth;
        lensDepthX = x0;
        lensDepthY = y0;
        lensDepthW = w;
        lensDepthH = h;
        lensDepthNear = game.currentNearPlane;
    }

    /**
     * Of something's points that fall within the lens, the share nothing nearer hides: each
     * point's distance from the eyes against the scene's depth there, allowing for the thing's
     * own size (so its far side isn't counted as hidden by its near side). 1 if the depth isn't known.
     */
    private float unhiddenShare(float[] vp, float[] points, float[] lensCentre, float lensR) {
        float[] depth = lensDepth;
        if (depth == null) return 1f;
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
        float near = lensDepthNear, far = XenoGuesser_GLEventListener.FAR_PLANE;
        float toSceneX = game.currentWidth / (float) game.screenWidth, toSceneY = game.currentHeight / (float) game.screenHeight;
        int inLens = 0, unhidden = 0;
        for (int k = 0; k < points.length; k += 3) {
            float px = points[k], py = points[k + 1], pz = points[k + 2];
            float w = vp[3] * px + vp[7] * py + vp[11] * pz + vp[15];
            if (w <= 0.01f) continue;
            float sx = ((vp[0] * px + vp[4] * py + vp[8] * pz + vp[12]) / w + 1f) * 0.5f * game.screenWidth;
            float sy = (1f - (vp[1] * px + vp[5] * py + vp[9] * pz + vp[13]) / w) * 0.5f * game.screenHeight;
            if (Math.hypot(sx - lensCentre[0], sy - lensCentre[1]) > lensR) continue;
            int rx = Math.round(sx * toSceneX) - lensDepthX, ry = game.currentHeight - 1 - Math.round(sy * toSceneY) - lensDepthY;
            if (rx < 0 || ry < 0 || rx >= lensDepthW || ry >= lensDepthH) continue;
            inLens++;
            // The scene's depth there, as a distance ahead of the eyes (as w is)
            float ndc = depth[ry * lensDepthW + rx] * 2f - 1f;
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
