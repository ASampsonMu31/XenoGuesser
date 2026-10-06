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
                g.drawImage(frames[f], 0, 0, size, size, cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side, null);
                g.dispose();
            }
            cropped[f] = image;
        }
        return cropped;
    }
}
