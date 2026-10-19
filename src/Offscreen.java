import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;

/**
 * Pictures drawn by the GPU off the screen: something drawn into a square of its own, on a
 * clear background, and read back as an image (for the map's pictures of animals and plants).
 */
public final class Offscreen {

    private Offscreen() {
    }

    /**
     * Runs draw with a size-pixel square framebuffer of its own bound (cleared to transparent,
     * depth testing on), and returns what it drew. The screen's framebuffer, viewport and clear
     * colour are as they were afterwards.
     */
    public static BufferedImage capture(GL3 gl, int size, Runnable draw) {
        int[] viewport = new int[4];
        gl.glGetIntegerv(GL.GL_VIEWPORT, viewport, 0);
        float[] clear = new float[4];
        gl.glGetFloatv(GL.GL_COLOR_CLEAR_VALUE, clear, 0);
        int[] ids = new int[1];
        gl.glGenFramebuffers(1, ids, 0);
        int fbo = ids[0];
        gl.glGenTextures(1, ids, 0);
        int colour = ids[0];
        gl.glGenRenderbuffers(1, ids, 0);
        int depth = ids[0];
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, colour);
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, size, size, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null);
        gl.glBindRenderbuffer(GL.GL_RENDERBUFFER, depth);
        gl.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_DEPTH_COMPONENT24, size, size);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo);
        gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, colour, 0);
        gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, depth);
        gl.glViewport(0, 0, size, size);
        gl.glClearColor(0f, 0f, 0f, 0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_BLEND);
        draw.run();
        java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(size * size * 4);
        gl.glReadPixels(0, 0, size, size, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0);
        gl.glDeleteFramebuffers(1, new int[] { fbo }, 0);
        gl.glDeleteTextures(1, new int[] { colour }, 0);
        gl.glDeleteRenderbuffers(1, new int[] { depth }, 0);
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        gl.glClearColor(clear[0], clear[1], clear[2], clear[3]);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int k = ((size - 1 - y) * size + x) * 4;
                int r = pixels.get(k) & 0xFF, g = pixels.get(k + 1) & 0xFF, b = pixels.get(k + 2) & 0xFF, a = pixels.get(k + 3) & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    /**
     * An image made smaller to size (square) a half at a time, then the last step bicubic, so
     * fine detail averages away cleanly (M54: the map's pictures of plants and animals were blurred).
     */
    public static BufferedImage shrink(BufferedImage image, int size) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= size) {
            int half = current.getWidth() / 2;
            BufferedImage next = new BufferedImage(half, half, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(current, 0, 0, half, half, null);
            g.dispose();
            current = next;
        }
        if (current.getWidth() == size) return current;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(current, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /**
     * As capture, but framed to what's drawn (M54): drawn once small to find where it is in the
     * picture, then again with the view zoomed (a scale and shift in clip space, given to draw to
     * put before its view-projection) so it fills the picture with a little room round it.
     */
    public static BufferedImage captureFitted(GL3 gl, int size, java.util.function.Consumer<com.xenoguesser.math.Matrix4> draw) {
        com.xenoguesser.math.Matrix4 none = new com.xenoguesser.math.Matrix4(1f);
        int probe = Math.max(64, size / 4);
        BufferedImage first = capture(gl, probe, () -> draw.accept(none));
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < probe; y++) {
            for (int x = 0; x < probe; x++) {
                if ((first.getRGB(x, y) >>> 24) > 8) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) return capture(gl, size, () -> draw.accept(none));
        // Its box in clip space (y up), a pixel's slack each side, made square and given room
        float x0 = (minX - 1f) / probe * 2f - 1f, x1 = (maxX + 2f) / probe * 2f - 1f;
        float y0 = 1f - (maxY + 2f) / probe * 2f, y1 = 1f - (minY - 1f) / probe * 2f;
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f, extent = Math.max(x1 - x0, y1 - y0) * 1.12f;
        float s = 2f / Math.max(1e-3f, extent);
        com.xenoguesser.math.Matrix4 zoom = com.xenoguesser.math.Matrix4.multiply(
                com.xenoguesser.math.Matrix4Transform.translate(-s * cx, -s * cy, 0f), com.xenoguesser.math.Matrix4Transform.scale(s, s, 1f));
        return capture(gl, size, () -> draw.accept(zoom));
    }

    /**
     * The frames cut down to the one square that holds what's drawn in all of them (so a
     * spinning thing stays put and the same size), with a little room, each scaled to size.
     */
    public static BufferedImage[] cropTogether(BufferedImage[] frames, int size) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (BufferedImage frame : frames) {
            for (int y = 0; y < frame.getHeight(); y++) {
                for (int x = 0; x < frame.getWidth(); x++) {
                    if ((frame.getRGB(x, y) >>> 24) > 8) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
        }
        BufferedImage[] cropped = new BufferedImage[frames.length];
        int side = maxX < 0 ? 1 : Math.max(maxX - minX, maxY - minY) + 1;
        side += side / 8 + 2;
        int cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        for (int f = 0; f < frames.length; f++) {
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            if (maxX >= 0) {
                Graphics2D g = image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                // (cut out at full size, then made smaller in halves: one big step down blurs and jags it)
                BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
                Graphics2D sg = square.createGraphics();
                sg.drawImage(frames[f], 0, 0, side, side, cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side, null);
                sg.dispose();
                g.drawImage(shrink(square, size), 0, 0, size, size, null);
                g.dispose();
            }
            cropped[f] = image;
        }
        return cropped;
    }
}
