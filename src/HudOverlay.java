import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws HUD pictures over the finished 3D frame with real transparency, which Swing
 * components laid over the GL canvas can't have. Each element is a picture painted with
 * Java2D, uploaded only when it changes, and placed in screen pixels.
 */
public class HudOverlay {

    private static final class Element {
        BufferedImage image;
        BufferedImage uploaded;
        int texture;
        int x, y;
        boolean visible = true;
    }

    private final Map<String, Element> elements = new LinkedHashMap<>();
    private Shader shader;
    private final int[] vao = new int[1], vbo = new int[1];
    private float dim;

    public void initialise(GL3 gl) {
        shader = new Shader(gl, "assets/shaders/vs_hud.txt", "assets/shaders/fs_hud.txt");
        gl.glGenVertexArrays(1, vao, 0);
        gl.glBindVertexArray(vao[0]);
        gl.glGenBuffers(1, vbo, 0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0]);
        float[] corners = { 0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1 };
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) corners.length * Float.BYTES, Buffers.newDirectFloatBuffer(corners), GL.GL_STATIC_DRAW);
        gl.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 2 * Float.BYTES, 0);
        gl.glEnableVertexAttribArray(0);
        gl.glBindVertexArray(0);
    }

    /** Shows image with its top-left corner at (x, y) in screen pixels. */
    public void put(String key, BufferedImage image, int x, int y) {
        Element e = elements.computeIfAbsent(key, k -> new Element());
        e.image = image;
        e.x = x;
        e.y = y;
        e.visible = true;
    }

    public void move(String key, int x, int y) {
        Element e = elements.get(key);
        if (e != null) {
            e.x = x;
            e.y = y;
        }
    }

    public void setVisible(String key, boolean visible) {
        Element e = elements.get(key);
        if (e != null) e.visible = visible;
    }

    /** The width in pixels of the element's picture, or 0. */
    public int width(String key) {
        Element e = elements.get(key);
        return e == null || e.image == null ? 0 : e.image.getWidth();
    }

    public boolean has(String key) {
        return elements.containsKey(key);
    }

    /** Darkens the whole view behind the HUD, e.g. while a menu is open; 0 for none. */
    public void setDim(float dim) {
        this.dim = dim;
    }

    public void draw(GL3 gl, int screenW, int screenH) {
        if (shader == null) return;
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        shader.use(gl);
        shader.setFloat(gl, "screenSize", screenW, screenH);
        gl.glBindVertexArray(vao[0]);
        if (dim > 0f) {
            shader.setInt(gl, "useImage", 0);
            shader.setFloat(gl, "tint", 0.02f, 0.03f, 0.05f, dim);
            shader.setFloat(gl, "rect", 0, 0, screenW, screenH);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 6);
        }
        shader.setInt(gl, "useImage", 1);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        shader.setInt(gl, "image", 0);
        for (Element e : elements.values()) {
            if (!e.visible || e.image == null) continue;
            if (e.uploaded != e.image) upload(gl, e);
            gl.glBindTexture(GL.GL_TEXTURE_2D, e.texture);
            shader.setFloat(gl, "rect", e.x, e.y, e.uploaded.getWidth(), e.uploaded.getHeight());
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 6);
        }
        gl.glBindVertexArray(0);
        gl.glDisable(GL.GL_BLEND);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_CULL_FACE);
    }

    private void upload(GL3 gl, Element e) {
        BufferedImage image = e.image;
        if (e.texture == 0) {
            int[] id = new int[1];
            gl.glGenTextures(1, id, 0);
            e.texture = id[0];
        }
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        gl.glBindTexture(GL.GL_TEXTURE_2D, e.texture);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
        // ARGB ints read as BGRA bytes on a little-endian machine; rows run top down, as the quad expects
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, w, h, 0, GL.GL_BGRA, GL.GL_UNSIGNED_BYTE, IntBuffer.wrap(argb));
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        e.uploaded = image;
    }

    public void dispose(GL3 gl) {
        for (Element e : elements.values()) {
            if (e.texture != 0) gl.glDeleteTextures(1, new int[] { e.texture }, 0);
        }
        elements.clear();
        if (vbo[0] != 0) gl.glDeleteBuffers(1, vbo, 0);
        if (vao[0] != 0) gl.glDeleteVertexArrays(1, vao, 0);
    }
}
