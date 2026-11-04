import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The metrologist's record of what the player's measured this round: each temperature and
 * height taken (the moment the thermometer's column or the altimeter's needle settles, see
 * PlayerBody.takeSettled) written in the notebook down the left of the screen, newest first,
 * and left behind as a pin standing where they stood, its value written large on it so it can
 * be read from afar. Everything here is on the GL thread; a new round starts a clean page.
 */
final class MeasurementLog extends GamePart {

    MeasurementLog(XenoGuesser_GLEventListener game) {
        super(game);
    }

    /** One reading: the gadget it was taken with (PlayerBody.GADGET_THERMOMETER or GADGET_ALTIMETER), its value, and the ground where it was taken. */
    private static final class Reading {
        final int gadget;
        final float value;
        final SpherePosition at;
        // Its pin's picture once it's been drawn (0 until then)
        int texture;

        Reading(int gadget, float value, SpherePosition at) {
            this.gadget = gadget;
            this.value = value;
            this.at = at;
        }
    }

    // The round's readings, oldest first; past MOST_PINS the oldest go (and their pins with them)
    private final List<Reading> readings = new ArrayList<>();
    private static final int MOST_PINS = 40;
    // How many of the newest readings the notebook shows
    private static final int NOTEBOOK_ROWS = 8;

    // The minus written below zero: a true minus sign, long and plain to see, or where the HUD's
    // font has none an en dash (either far plainer than a hyphen)
    private static final char MINUS = HudStyle.font(Font.BOLD, 12f).canDisplay('−') ? '−' : '–';

    /** The value as written in the notebook and on the pin: tenths of a degree, or whole metres. */
    private static String written(Reading r) {
        String text = r.gadget == PlayerBody.GADGET_THERMOMETER
                ? String.format(java.util.Locale.ROOT, "%.1f°C", r.value)
                : Math.round(r.value) + " m";
        return text.replace('-', MINUS);
    }

    /**
     * Notes a reading that's just settled (none if s is null), taken by the player whose eyes are
     * at eye: its pin goes on what they stand on (the water's surface, swimming).
     */
    void note(PlayerBody.Settled s, SpherePosition eye) {
        if (s == null) return;
        float stood = game.playerMotion.lastStood;
        double ground = game.swimming ? game.waterSurfaceHere
                : Float.isNaN(stood) ? game.groundHeightAt(eye) : stood;
        readings.add(new Reading(s.gadget(), s.value(), SphereMath.atAltitude(eye, ground)));
        if (readings.size() > MOST_PINS) forget(readings.remove(0));
        notebookDirty = true;
    }

    /** A clean page (a new round): every reading forgotten and its pin taken up. */
    void clear() {
        for (Reading r : readings) forget(r);
        readings.clear();
        notebookDirty = true;
    }

    /** Lets go of a reading's pin picture. */
    private void forget(Reading r) {
        if (r.texture != 0 && gl != null) gl.glDeleteTextures(1, new int[] { r.texture }, 0);
        r.texture = 0;
    }

    // ------------------------------------------------------------------ the notebook

    // The notebook's size (before the screen's scaling): its width, heading and each row
    private static final int NOTEBOOK_W = 236, NOTEBOOK_HEAD = 32, NOTEBOOK_ROW = 36;
    // What the notebook last showed (its rows' text, distances and all), so it's drawn again only when that changes
    private String notebookShows = "";
    private boolean notebookDirty = true;

    /**
     * The notebook on the HUD, down the left below the score: the newest readings first, each
     * with its kind's symbol, its value and how far it was taken from here. Hidden with nothing
     * in it.
     */
    void drawNotebook(float scale, boolean rescaled) {
        HudOverlay hud = game.hudRenderer.hud;
        int rows = Math.min(NOTEBOOK_ROWS, readings.size());
        if (rows == 0) {
            if (hud.has("notebook")) hud.setVisible("notebook", false);
            notebookShows = "";
            return;
        }
        SpherePosition here = game.camera.getSpherePosition();
        String[] values = new String[rows], aways = new String[rows];
        boolean[] temperatures = new boolean[rows];
        StringBuilder shows = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            Reading r = readings.get(readings.size() - 1 - i);
            temperatures[i] = r.gadget == PlayerBody.GADGET_THERMOMETER;
            values[i] = written(r);
            aways[i] = away(SphereMath.distance(here, r.at) / PlayerBody.METRE);
            shows.append(values[i]).append('|').append(aways[i]).append('|');
        }
        if (rescaled || notebookDirty || !shows.toString().equals(notebookShows) || !hud.has("notebook")) {
            notebookDirty = false;
            notebookShows = shows.toString();
            int h = NOTEBOOK_HEAD + rows * NOTEBOOK_ROW + 8;
            Graphics2D[] g = new Graphics2D[1];
            BufferedImage image = HudStyle.canvas(NOTEBOOK_W, h, scale, g);
            paintNotebook(g[0], h, rows, temperatures, values, aways);
            g[0].dispose();
            hud.put("notebook", image, 0, 0);
        }
        int margin = Math.round(HudStyle.HUD_MARGIN * scale);
        hud.move("notebook", margin, margin + Math.round((HudStyle.SCORE_H + 18) * scale));
        hud.setVisible("notebook", true);
    }

    /** How far off a reading was taken, said briefly: "here" within a few metres, else metres (in fives, then tens) or kilometres. */
    private static String away(double metres) {
        if (metres < 5.0) return "here";
        if (metres < 100.0) return Math.round(metres / 5.0) * 5 + " m away";
        if (metres < 1000.0) return Math.round(metres / 10.0) * 10 + " m away";
        return String.format(java.util.Locale.ROOT, "%.1f km away", metres / 1000.0);
    }

    /** The notebook's picture: a panel headed "Notebook", a row for each reading, newest at the top. */
    private static void paintNotebook(Graphics2D g, int h, int rows, boolean[] temperatures, String[] values, String[] aways) {
        HudStyle.panel(g, 0, 0, NOTEBOOK_W, h, HudStyle.GLASS);
        HudStyle.label(g, "Metrologist's notebook", 16f, 21f, 11f, HudStyle.LABEL);
        Font value = HudStyle.font(Font.BOLD, 19f), note = HudStyle.font(Font.PLAIN, 13f);
        for (int i = 0; i < rows; i++) {
            float top = NOTEBOOK_HEAD + i * NOTEBOOK_ROW;
            if (i > 0) {
                g.setColor(new Color(120, 205, 235, 40));
                g.fill(new java.awt.geom.Rectangle2D.Float(14f, top, NOTEBOOK_W - 28f, 1f));
            }
            if (temperatures[i]) HudStyle.paintThermometerIcon(g, 14f, top + 5f, 26f);
            else HudStyle.paintAltitudeIcon(g, 14f, top + 5f, 26f);
            // (the newest brightest, the older fading a little)
            g.setFont(value);
            g.setColor(i == 0 ? HudStyle.VALUE : new Color(205, 214, 224));
            g.drawString(values[i], 50f, top + 25f);
            g.setFont(note);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(HudStyle.DIM);
            g.drawString(aways[i], NOTEBOOK_W - 16f - fm.stringWidth(aways[i]), top + 24f);
        }
    }

    // ------------------------------------------------------------------ the pins

    // A pin's height and width up close (world units: about 2 m tall, its head wider than it's
    // tall so the value fits large), and the least it's ever seen at far off (radians tall), so
    // its writing can still be read from a long way away
    private static final double PIN_HEIGHT = 2.0 * PlayerBody.METRE, PIN_WIDTH = 2.8 * PlayerBody.METRE, PIN_LEAST_ANGLE = 0.07;
    // A pin's picture, in proportion to it
    private static final int PIN_W = 560, PIN_H = 400;
    // The pins' colours: the thermometer's red and the altimeter's blue
    private static final Color TEMPERATURE_PIN = new Color(206, 42, 34), ALTITUDE_PIN = new Color(30, 78, 175);

    private GL3 gl;
    private Shader shader;
    private int vao, vbo;
    // Each pin: six corners of {camera-relative x, y, z, u, v}
    private static final int FLOATS = 5;

    /**
     * Draws every pin, turned upright to face the eyes, hidden by anything solid in front:
     * camera-relative, as the pod's beacon, the farthest first so the nearer are drawn over them.
     */
    void renderPins(GL3 gl, Matrix4 viewProjection, SpherePosition eye) {
        if (readings.isEmpty()) return;
        if (shader == null) initialise(gl);
        List<Reading> order = new ArrayList<>(readings);
        order.sort((a, b) -> Double.compare(squaredFrom(eye, b.at), squaredFrom(eye, a.at)));
        float[] vertices = new float[order.size() * 6 * FLOATS];
        int count = 0;
        for (Reading r : order) {
            if (r.texture == 0) r.texture = upload(gl, pinImage(r));
            double bx = r.at.x() - eye.x(), by = r.at.y() - eye.y(), bz = r.at.z() - eye.z();
            double[] up = unit(r.at.x(), r.at.y(), r.at.z());
            // (square to the eyes' line to it, level: the view's right, as seen from here)
            double[] right = unit(by * up[2] - bz * up[1], bz * up[0] - bx * up[2], bx * up[1] - by * up[0]);
            if (right == null) right = new double[] { 1.0, 0.0, 0.0 };
            double distance = Math.sqrt(bx * bx + by * by + bz * bz);
            double grow = Math.max(1.0, distance * PIN_LEAST_ANGLE / PIN_HEIGHT);
            double h = PIN_HEIGHT * grow, half = PIN_WIDTH * 0.5 * grow;
            double[][] corners = { { -1, 0 }, { 1, 0 }, { 1, 1 }, { -1, 0 }, { 1, 1 }, { -1, 1 } };
            for (double[] c : corners) {
                double across = c[0] * half, rise = c[1] * h;
                vertices[count++] = (float) (bx + right[0] * across + up[0] * rise);
                vertices[count++] = (float) (by + right[1] * across + up[1] * rise);
                vertices[count++] = (float) (bz + right[2] * across + up[2] * rise);
                vertices[count++] = (float) (c[0] * 0.5 + 0.5);
                vertices[count++] = (float) (1.0 - c[1]);
            }
        }
        gl.glBindVertexArray(vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, count * 4L, java.nio.FloatBuffer.wrap(vertices, 0, count), GL3.GL_STREAM_DRAW);
        shader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(shader.getID(), "viewProjection"), 1, false, viewProjection.toFloatArrayForGLSL(), 0);
        shader.setInt(gl, "picture", 0);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        gl.glDisable(GL.GL_CULL_FACE);
        for (int i = 0; i < order.size(); i++) {
            gl.glBindTexture(GL.GL_TEXTURE_2D, order.get(i).texture);
            gl.glDrawArrays(GL.GL_TRIANGLES, i * 6, 6);
        }
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    /** The pins' shader and vertex buffer, made the first time there's a pin to draw. */
    private void initialise(GL3 gl) {
        this.gl = gl;
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_reading_pin.txt", GamePaths.HOME + "assets/shaders/fs_reading_pin.txt");
        int[] ids = new int[1];
        gl.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        gl.glGenBuffers(1, ids, 0);
        vbo = ids[0];
        gl.glBindVertexArray(vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, FLOATS * 4, 0);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(1, 2, GL.GL_FLOAT, false, FLOATS * 4, 12);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    /** A pin's picture as a texture, mipmapped so its writing stays clean as it shrinks into the distance. */
    private static int upload(GL3 gl, BufferedImage image) {
        int[] id = new int[1];
        gl.glGenTextures(1, id, 0);
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        gl.glBindTexture(GL.GL_TEXTURE_2D, id[0]);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
        // (ARGB ints read as BGRA bytes on a little-endian machine; rows run top down, v 0 at the top)
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, w, h, 0, GL.GL_BGRA, GL.GL_UNSIGNED_BYTE, IntBuffer.wrap(argb));
        gl.glGenerateMipmap(GL.GL_TEXTURE_2D);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        return id[0];
    }

    /**
     * A pin's picture: a map pin, its broad rounded head in the kind's colour narrowing to a
     * point at the bottom middle (on the ground), edged in white; in the head the kind's symbol
     * on a dark disc and the value in big bold white, as large as fits.
     */
    private static BufferedImage pinImage(Reading r) {
        BufferedImage image = new BufferedImage(PIN_W, PIN_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        HudStyle.smooth(g);
        boolean temperature = r.gadget == PlayerBody.GADGET_THERMOMETER;
        float edge = 12f, headH = PIN_H * 0.66f, cx = PIN_W * 0.5f;
        Area shape = new Area(new RoundRectangle2D.Float(edge, edge, PIN_W - edge * 2f, headH - edge, headH * 0.5f, headH * 0.5f));
        Path2D point = new Path2D.Float();
        point.moveTo(cx - PIN_W * 0.14f, headH - edge * 2f);
        point.lineTo(cx + PIN_W * 0.14f, headH - edge * 2f);
        point.lineTo(cx, PIN_H - edge * 0.5f);
        point.closePath();
        shape.add(new Area(point));
        g.setColor(temperature ? TEMPERATURE_PIN : ALTITUDE_PIN);
        g.fill(shape);
        g.setStroke(new BasicStroke(edge, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        g.draw(shape);

        // The symbol, on a dark disc at the head's left
        float disc = headH - edge * 5f, dx = edge * 3f, dy = edge * 2.5f;
        g.setColor(HudStyle.GLASS_SOLID);
        g.fill(new Ellipse2D.Float(dx, dy, disc, disc));
        if (temperature) HudStyle.paintThermometerIcon(g, dx + disc * 0.14f, dy + disc * 0.14f, disc * 0.72f);
        else HudStyle.paintAltitudeIcon(g, dx + disc * 0.14f, dy + disc * 0.14f, disc * 0.72f);

        // The value, as large as fits beside it
        String text = written(r);
        float left = dx + disc + edge * 1.5f, room = PIN_W - edge * 3f - left, tall = headH - edge * 4f;
        Font font = HudStyle.font(Font.BOLD, 100f);
        g.setFont(font);
        java.awt.geom.Rectangle2D bounds = g.getFontMetrics().getStringBounds(text, g);
        float size = (float) Math.min(100f * room / bounds.getWidth(), 100f * tall / (bounds.getHeight() * 0.8));
        font = font.deriveFont(size);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        float baseline = edge + (headH - edge) * 0.5f + (fm.getAscent() - fm.getDescent()) * 0.5f;
        float x = left + (room - fm.stringWidth(text)) * 0.5f;
        // (a dark shadow under it, so it reads against the colour from anywhere)
        g.setColor(new Color(0, 0, 0, 110));
        g.drawString(text, x + size * 0.04f, baseline + size * 0.04f);
        g.setColor(Color.WHITE);
        g.drawString(text, x, baseline);
        g.dispose();
        return image;
    }

    private static double squaredFrom(SpherePosition eye, SpherePosition p) {
        double x = p.x() - eye.x(), y = p.y() - eye.y(), z = p.z() - eye.z();
        return x * x + y * y + z * z;
    }

    /** (x, y, z) made unit length, or null where it's too short to have a way. */
    private static double[] unit(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-9) return null;
        return new double[] { x / length, y / length, z / length };
    }
}
