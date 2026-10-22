import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import com.jogamp.opengl.*;
import com.xenoguesser.math.*;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;

/**
 * Pictures of the plants and animals, drawn with the GL context: each species' portrait for
 * the map's choices and the identification kit's book, the chosen one turning round beside
 * the map (a few frames at a time between the game's), and a plant's leaf, orb or spikes.
 */
final class SpeciesPictures extends GamePart {

    SpeciesPictures(XenoGuesser_GLEventListener game) {
        super(game);
    }

    volatile java.awt.Image[] speciesPortraits;

    // Each plant species' picture for the map, once drawn, and those being drawn (see finishSteps)
    volatile java.awt.Image[] floraPortraits;

    java.awt.Image[] floraMade;

    /** A plant species' picture, for the map's and the shop's choices. */
    java.awt.Image floraPortrait(GL3 gl, int species) {
        BufferedImage view = floraView(gl, species, PREVIEW_RENDER, (float) Math.toRadians(30), true);
        return view == null ? null : Offscreen.cropTogether(new BufferedImage[] { view }, 96)[0];
    }

    /** The plants' pictures, drawn: for the book and the map. */
    void showFloraPortraits(java.awt.Image[] portraits) {
        floraPortraits = portraits;
        giveBookPictures();
        if (game.minimap == null) return;
        // (a plant's range already chosen gets its picture now)
        MapOverlay shown = game.mapOverlays.currentDebugFactor;
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (shown != null && shown.factor() == FactorName.FLORA && shown == game.mapOverlays.currentDebugFactor) game.minimap.setOverlayPicture(portraits[shown.species()]);
            game.minimap.repaint();
        });
    }

    // The animal or plant to show turning beside the map: wanted, being drawn (its frames so far)
    volatile MapOverlay previewWanted;

    private MapOverlay previewBuilding;

    private BufferedImage[] previewRaw;

    private int previewNext;

    // The turning picture's frames (all the way round), their size, and how many drawn a frame
    private static final int PREVIEW_FRAMES = 36, PREVIEW_SIZE = 256, PREVIEW_RENDER = 512, PREVIEWS_PER_FRAME = 4;

    /**
     * Draws, a few at a time between frames, the plants' pictures for the map's choices (once),
     * and the frames of the chosen animal or plant turning round, handing them to the map when done.
     */
    void advancePreviews(GL3 gl) {
        if (!game.worldReady || game.roundLoading || game.minimap == null) return;
        // (normally drawn while loading: see finishSteps)
        if (floraPortraits == null) {
            java.awt.Image[] portraits = new java.awt.Image[game.NUM_SPECIES];
            for (int s = 0; s < game.NUM_SPECIES; s++) portraits[s] = floraPortrait(gl, s);
            showFloraPortraits(portraits);
        }
        MapOverlay wanted = previewWanted;
        if (wanted == null) return;
        if (previewBuilding != wanted) {
            previewBuilding = wanted;
            previewRaw = new BufferedImage[PREVIEW_FRAMES];
            previewNext = 0;
        }
        SpherePosition here = game.camera.getSpherePosition();
        for (int k = 0; k < PREVIEWS_PER_FRAME && previewNext < PREVIEW_FRAMES; k++, previewNext++) {
            float angle = (float) (Math.PI * 2 * previewNext / PREVIEW_FRAMES);
            previewRaw[previewNext] = wanted.factor() == FactorName.FLORA ? floraView(gl, wanted.species(), PREVIEW_RENDER, angle)
                    : game.organismManager.view(gl, wanted.species(), PREVIEW_RENDER, angle, here);
            if (previewRaw[previewNext] == null) previewRaw[previewNext] = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
        if (previewNext < PREVIEW_FRAMES) return;
        BufferedImage[] cropped = Offscreen.cropTogether(previewRaw, PREVIEW_SIZE);
        // (shrubs shown half as tall as trees, standing on the same ground)
        BufferedImage[] frames = wanted.factor() == FactorName.FLORA && wanted.species() >= 4 ? shrunkToGround(cropped, 0.5f) : cropped;
        previewRaw = null;
        previewWanted = null;
        previewBuilding = null;
        String name = game.mapOverlays.overlayLabel(wanted);
        String kind = wanted.factor() == FactorName.FLORA ? game.speciesConfigs[wanted.species()].design.kind() : "Animal";
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (game.mapOverlays.currentDebugFactor != null && game.mapOverlays.currentDebugFactor.equals(wanted)) {
                game.minimap.setPreview(name, kind, frames, wanted.factor() == FactorName.FLORA ? detailPicture(wanted.species()) : null);
            }
        });
    }

    /** Each picture drawn smaller by scale, in a picture of the same size, at the middle of its foot. */
    private static BufferedImage[] shrunkToGround(BufferedImage[] frames, float scale) {
        BufferedImage[] out = new BufferedImage[frames.length];
        for (int i = 0; i < frames.length; i++) {
            int w = frames[i].getWidth(), h = frames[i].getHeight();
            int sw = Math.round(w * scale), sh = Math.round(h * scale);
            out[i] = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out[i].createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(frames[i], (w - sw) / 2, h - sh, sw, sh, null);
            g.dispose();
        }
        return out;
    }

    /** Plant species s's leaf, in its own colours (its texture runs from the dark shade to the light). */
    /**
     * What's shown beside a plant on the map to compare: its leaf, or if it has none, one of its
     * orbs or its spikes; nothing if it bears none of them (a bare mushroom or cactus).
     */
    BufferedImage detailPicture(int s) {
        Flora.Design d = game.speciesConfigs[s].design;
        if (d.leaves) return leafPicture(s);
        if (d.orbs > 0f) return orbPicture(XenoGuesser_GLEventListener.leafLightColour(game.speciesConfigs[s].healthyColor));
        if (d.spikes > 0f) return spikePicture();
        return null;
    }

    // The detail pictures' size (pixels square), as the leaves'
    private static final int DETAIL_PICTURE = 128;

    /** One of a plant's orbs: a glossy ball of its colour, lit from the upper left. */
    private static BufferedImage orbPicture(Vector3 colour) {
        BufferedImage image = new BufferedImage(DETAIL_PICTURE, DETAIL_PICTURE, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        int size = DETAIL_PICTURE * 3 / 4, at = (DETAIL_PICTURE - size) / 2;
        java.awt.Color base = awtColour(colour, 1f), dark = awtColour(colour, 0.35f), light = awtColour(colour, 1.5f);
        g.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(at + size * 0.38f, at + size * 0.36f), size * 0.7f,
                new float[] { 0f, 0.45f, 1f }, new java.awt.Color[] { light, base, dark }));
        g.fillOval(at, at, size, size);
        // (its shine)
        g.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(at + size * 0.33f, at + size * 0.3f), size * 0.16f,
                new float[] { 0f, 1f }, new java.awt.Color[] { new java.awt.Color(255, 255, 255, 220), new java.awt.Color(255, 255, 255, 0) }));
        g.fillOval(at, at, size, size);
        g.dispose();
        return image;
    }

    /** A plant's spikes: a tuft of three, pale at the root and dark at the tip (as fs_flora draws them). */
    private static BufferedImage spikePicture() {
        BufferedImage image = new BufferedImage(DETAIL_PICTURE, DETAIL_PICTURE, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        float n = DETAIL_PICTURE;
        float rootX = n * 0.5f, rootY = n * 0.88f;
        java.awt.Color pale = new java.awt.Color(230, 219, 189), tip = new java.awt.Color(56, 46, 41);
        float[][] spikes = { { -0.42f, 0.62f, 0.07f }, { 0.42f, 0.62f, 0.07f }, { 0f, 0.8f, 0.09f } };
        for (float[] spike : spikes) {
            double angle = spike[0];
            float length = n * spike[1], width = n * spike[2];
            float tx = rootX + (float) Math.sin(angle) * length, ty = rootY - (float) Math.cos(angle) * length;
            float sx = (float) Math.cos(angle) * width, sy = (float) Math.sin(angle) * width;
            java.awt.geom.Path2D.Float shape = new java.awt.geom.Path2D.Float();
            shape.moveTo(rootX - sx, rootY - sy);
            shape.lineTo(tx, ty);
            shape.lineTo(rootX + sx, rootY + sy);
            shape.closePath();
            g.setPaint(new java.awt.GradientPaint(rootX + (tx - rootX) * 0.3f, rootY + (ty - rootY) * 0.3f, pale, rootX + (tx - rootX) * 0.85f, rootY + (ty - rootY) * 0.85f, tip));
            g.fill(shape);
        }
        g.dispose();
        return image;
    }

    private static java.awt.Color awtColour(Vector3 c, float brightness) {
        return new java.awt.Color(Math.min(1f, c.x * brightness), Math.min(1f, c.y * brightness), Math.min(1f, c.z * brightness));
    }

    private BufferedImage leafPicture(int s) {
        BufferedImage leaf = game.worldArt.leaf(game.speciesConfigs[s].leafTexNum);
        if (leaf == null) return null;
        Vector3 dark = XenoGuesser_GLEventListener.leafDarkColour(game.speciesConfigs[s].healthyColor), light = XenoGuesser_GLEventListener.leafLightColour(game.speciesConfigs[s].healthyColor);
        BufferedImage coloured = new BufferedImage(leaf.getWidth(), leaf.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < leaf.getHeight(); y++) {
            for (int x = 0; x < leaf.getWidth(); x++) {
                int argb = leaf.getRGB(x, y);
                float t = ((argb >> 16) & 0xFF) / 255f;
                int r = Math.round((dark.x + (light.x - dark.x) * t) * 255f), g = Math.round((dark.y + (light.y - dark.y) * t) * 255f),
                        b = Math.round((dark.z + (light.z - dark.z) * t) * 255f);
                coloured.setRGB(x, y, (argb & 0xFF000000) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b));
            }
        }
        return coloured;
    }

    /**
     * A plant of species s turned to angle, seen from three-quarters on in a wide view (render
     * pixels square, clear round it); null if its model isn't made yet. Drawn just under where
     * the viewer is (so the planet's curve leaves it be) by pointing the camera at it a moment.
     */
    private BufferedImage floraView(GL3 gl, int s, int render, float angle) {
        return floraView(gl, s, render, angle, false);
    }

    /** As floraView; fitted, framed to the plant itself (its picture for the map: see Offscreen.captureFitted). */
    private BufferedImage floraView(GL3 gl, int s, int render, float angle, boolean fitted) {
        Model branch = null, leaves = null;
        for (int lod = 0; lod < game.floraBranchModelsLOD[s].length && branch == null; lod++) {
            for (int v = 0; v < game.FLORA_VARIATIONS; v++) {
                if (game.floraBranchModelsLOD[s][lod][v] != null && game.floraLeafModelsLOD[s][lod][v] != null) {
                    branch = game.floraBranchModelsLOD[s][lod][v];
                    leaves = game.floraLeafModelsLOD[s][lod][v];
                    break;
                }
            }
        }
        if (branch == null) return null;
        SpeciesConfig sc = game.speciesConfigs[s];
        // Drawn on its own, at the origin of its own space, through a view of its own
        // (framed generously, the tallest trees whole: the picture is cut down to the plant afterwards)
        // (shrubs are much smaller than trees, so framed closer: otherwise they come out tiny and blurred)
        float reach = XenoGuesser_GLEventListener.FLORA_CULL_RADIUS * (s < 4 ? 1.0f : 0.2f);
        Matrix4 model = Matrix4Transform.rotateAroundY((float) Math.toDegrees(angle));
        Vector3 centre = new Vector3(0f, reach * 0.7f, 0f);
        float distance = reach * 3.6f;
        Vector3 eye = new Vector3(centre.x + distance * 0.8f, centre.y + distance * 0.25f, centre.z + distance * 0.55f);
        Matrix4 viewProjection = Matrix4.multiply(Matrix4Transform.perspective(40f, 1f, 1f, distance * 4f),
                Matrix4Transform.lookAt(eye, centre, new Vector3(0f, 1f, 0f)));
        final Model trunk = branch, foliage = leaves;
        Vector3 light = new Vector3(0.62f, 0.62f, 0.66f);
        java.util.function.Consumer<Matrix4> draw = zoom -> {
            Matrix4 shown = Matrix4.multiply(zoom, viewProjection);
            gl.glDisable(GL3.GL_CULL_FACE);
            game.floraShader.use(gl);
            game.floraBodyUniforms(gl, sc, XenoGuesser_GLEventListener.leafLightColour(sc.healthyColor));
            trunk.setModelMatrix(model);
            trunk.renderWith(gl, light, shown, eye);
            game.leafShader.use(gl);
            game.leafShader.setVec3(gl, "u_LeafDarkColor", XenoGuesser_GLEventListener.leafDarkColour(sc.healthyColor));
            game.leafShader.setVec3(gl, "u_LeafLightColor", XenoGuesser_GLEventListener.leafLightColour(sc.healthyColor));
            foliage.setModelMatrix(model);
            foliage.renderWith(gl, light, shown, eye);
            gl.glEnable(GL3.GL_CULL_FACE);
        };
        return fitted ? Offscreen.captureFitted(gl, render, draw) : Offscreen.capture(gl, render, () -> draw.accept(new Matrix4(1f)));
    }

    /** Developer aid: saves the map's pictures of the plants and animals, each as prefix_plant3.png and the like. */
    public void devSavePortraits(String prefix) {
        java.awt.Image[][] sets = { floraPortraits, speciesPortraits };
        String[] names = { "plant", "animal" };
        for (int k = 0; k < 2; k++) {
            if (sets[k] == null) continue;
            for (int i = 0; i < sets[k].length; i++) {
                if (!(sets[k][i] instanceof BufferedImage image)) continue;
                try {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(prefix + "_" + names[k] + i + ".png"));
                } catch (java.io.IOException e) {
                    System.err.println("Couldn't save a portrait: " + e.getMessage());
                }
            }
        }
        System.out.println("[SHOT] Portraits saved to " + prefix + "_*.png");
    }

    /** Draws each species' picture for the map (with the GL context, once its meshes are made). */
    // The species' pictures as they're drawn (see finishSteps)
    java.awt.Image[] portraitsMade;

    /** The species' pictures, drawn: for the book and the map. */
    void showSpeciesPortraits(java.awt.Image[] portraits) {
        speciesPortraits = portraits;
        giveBookPictures();
        if (game.minimap == null) return;
        // (a species' overlay already chosen gets its picture now)
        MapOverlay shown = game.mapOverlays.currentDebugFactor;
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (shown != null && shown.species() >= 0 && shown.factor() == FactorName.ANIMAL_POPULATION && shown == game.mapOverlays.currentDebugFactor) {
                game.minimap.setOverlayPicture(portraits[shown.species()]);
            }
            game.minimap.repaint();
        });
    }

    private boolean bookPicturesGiven;

    /** Once both are drawn, two of the animals' and two of the plants' pictures for the identification kit's book. */
    private void giveBookPictures() {
        java.awt.Image[] animals = speciesPortraits, plants = floraPortraits;
        if (bookPicturesGiven || animals == null || plants == null) return;
        bookPicturesGiven = true;
        java.util.Random rand = new java.util.Random(game.worldSeed * 977L + 3L);
        List<java.awt.Image> pictures = new ArrayList<>();
        for (java.awt.Image[] kind : new java.awt.Image[][] { animals, plants }) {
            List<java.awt.Image> some = new ArrayList<>();
            for (java.awt.Image picture : kind) if (picture != null) some.add(picture);
            java.util.Collections.shuffle(some, rand);
            for (int i = 0; i < 2; i++) pictures.add(i < some.size() ? some.get(i) : null);
        }
        game.playerBody.setBookPictures(pictures, game.worldSeed);
    }
}
