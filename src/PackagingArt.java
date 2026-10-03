import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

/**
 * Paints a nation's packaging texture: eight packet designs (a brand name in the nation's
 * own script, small print, a picture of what's inside or a maker's emblem, now and then the
 * flag) and the skins of its eight kinds of produce. See Products for the layout.
 */
public final class PackagingArt {

    public static final int SIZE = 512;
    private static final int CELL = SIZE / Products.GRID;

    private PackagingArt() {
    }

    public static BufferedImage render(long seed, int nationId, Products products, List<BufferedImage> glyphs, int direction,
                                       BufferedImage flag, float patriotism) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        List<Products.Produce> crops = products.produce(nationId);
        for (Products.Packet packet : products.packets(nationId)) {
            Random rand = new Random(seed * 7121L + nationId * 131L + packet.cell * 17L);
            int x = (packet.cell % Products.GRID) * CELL, y = (packet.cell / Products.GRID) * CELL;
            g.setClip(x, y, CELL, CELL);
            drawPacket(g, x, y, rand, packet, packet.produce >= 0 ? crops.get(packet.produce) : null, glyphs, direction,
                    rand.nextFloat() < patriotism * 0.5f ? flag : null);
        }
        for (Products.Produce p : crops) {
            int x = (p.cell % Products.GRID) * CELL, y = (p.cell / Products.GRID) * CELL;
            g.setClip(x, y, CELL, CELL);
            drawSkin(g, x, y, p, new Random(seed + p.cell * 977L + nationId));
        }
        g.dispose();
        return image;
    }

    private static void drawPacket(Graphics2D g, int x, int y, Random rand, Products.Packet packet, Products.Produce pictured,
                                   List<BufferedImage> glyphs, int direction, BufferedImage flag) {
        float hue = rand.nextFloat();
        boolean pale = rand.nextFloat() < 0.35f;
        Color ground = colour(WorldPalette.hsv(hue, pale ? 0.15f + rand.nextFloat() * 0.2f : 0.55f + rand.nextFloat() * 0.4f,
                pale ? 0.88f + rand.nextFloat() * 0.1f : 0.35f + rand.nextFloat() * 0.5f));
        Color accent = colour(WorldPalette.hsv(hue + 0.3f + rand.nextFloat() * 0.4f, 0.6f + rand.nextFloat() * 0.35f, 0.5f + rand.nextFloat() * 0.45f));
        boolean darkGround = brightness(ground) < 0.5f;
        Color ink = rand.nextFloat() < 0.4f ? accent : darkGround ? new Color(245, 240, 228) : new Color(20, 18, 22);

        // Ground: flat, two-tone or graded
        int style = rand.nextInt(3);
        if (style == 0) {
            g.setColor(ground);
            g.fillRect(x, y, CELL, CELL);
        } else if (style == 1) {
            g.setPaint(new GradientPaint(x, y, ground, x, y + CELL, ground.darker()));
            g.fillRect(x, y, CELL, CELL);
        } else {
            g.setColor(ground);
            g.fillRect(x, y, CELL, CELL);
            g.setColor(accent);
            Path2D.Float sweep = new Path2D.Float();
            float cut = 0.55f + rand.nextFloat() * 0.25f;
            sweep.moveTo(x, y + CELL * cut);
            sweep.curveTo(x + CELL * 0.4f, y + CELL * (cut - 0.2f), x + CELL * 0.7f, y + CELL * (cut + 0.15f), x + CELL, y + CELL * (cut - 0.1f));
            sweep.lineTo(x + CELL, y + CELL);
            sweep.lineTo(x, y + CELL);
            sweep.closePath();
            g.fill(sweep);
        }
        // The edges (and the sides of the box, which sample the corner) in the accent
        g.setColor(accent.darker());
        g.fillRect(x, y, 6, 6);
        g.setStroke(new BasicStroke(3f));
        g.drawRect(x + 2, y + 2, CELL - 5, CELL - 5);

        boolean vertical = direction >= 2;
        // The picture: what's inside, or the maker's emblem
        float px = x + CELL * (vertical ? 0.58f : 0.5f), py = y + CELL * (vertical ? 0.5f : 0.56f);
        float size = CELL * (0.3f + rand.nextFloat() * 0.12f);
        if (pictured != null) {
            drawProduce(g, pictured, px, py, size, rand);
        } else {
            drawEmblem(g, px, py, size * 0.8f, accent, ink, rand);
        }
        if (flag != null) {
            int fw = CELL / 4, fh = fw * 2 / 3;
            int fx = vertical ? x + CELL - fw - 8 : x + 8, fy = y + CELL - fh - 8;
            g.drawImage(flag, fx, fy, fw, fh, null);
            g.setColor(Color.DARK_GRAY);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(fx, fy, fw, fh);
        }

        if (glyphs.size() > 1) {
            // The brand, big, at the beginning of the writing; small print at the other end
            int brand = 3 + rand.nextInt(3);
            int[] name = new int[brand];
            for (int i = 0; i < brand; i++) name[i] = 1 + rand.nextInt(glyphs.size() - 1);
            int small = 7 + rand.nextInt(6);
            int[] print = new int[small];
            for (int i = 0; i < small; i++) print[i] = rand.nextFloat() < 0.18f ? 0 : 1 + rand.nextInt(glyphs.size() - 1);
            if (!vertical) {
                float big = CELL * 0.8f / brand;
                writeLine(g, glyphs, name, x + CELL * 0.1f, y + CELL * 0.07f, Math.min(big, CELL * 0.22f), true, direction == 1, ink);
                writeLine(g, glyphs, print, x + CELL * 0.08f, y + CELL * 0.86f, CELL * 0.84f / small, true, direction == 1, ink);
            } else {
                float big = CELL * 0.8f / brand;
                writeLine(g, glyphs, name, x + CELL * 0.07f, y + CELL * 0.1f, Math.min(big, CELL * 0.22f), false, direction == 3, ink);
                writeLine(g, glyphs, print, x + CELL * 0.86f, y + CELL * 0.08f, CELL * 0.84f / small, false, direction == 3, ink);
            }
        }
    }

    /** A row (or column) of glyphs of the given size, from start; reversed lays them out from the far end. */
    private static void writeLine(Graphics2D g, List<BufferedImage> glyphs, int[] text, float x, float y, float size,
                                  boolean horizontal, boolean reversed, Color ink) {
        for (int i = 0; i < text.length; i++) {
            if (text[i] <= 0 || text[i] >= glyphs.size()) continue;
            int k = reversed ? text.length - 1 - i : i;
            float gx = horizontal ? x + k * size : x;
            float gy = horizontal ? y : y + k * size;
            g.drawImage(tinted(glyphs.get(text[i]), ink), Math.round(gx), Math.round(gy), Math.round(size), Math.round(size), null);
        }
    }

    /** A glyph's ink as the given colour on a clear ground. */
    private static BufferedImage tinted(BufferedImage glyph, Color ink) {
        BufferedImage out = new BufferedImage(glyph.getWidth(), glyph.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int rgbInk = ink.getRGB() & 0xFFFFFF;
        for (int j = 0; j < glyph.getHeight(); j++) {
            for (int i = 0; i < glyph.getWidth(); i++) {
                int argb = glyph.getRGB(i, j);
                int alpha = (argb >>> 24);
                float bright = (((argb >> 16) & 255) * 0.299f + ((argb >> 8) & 255) * 0.587f + (argb & 255) * 0.114f) / 255f;
                float darkness = alpha < 128 ? 0f : 1f - bright;
                int a = Math.max(0, Math.min(255, Math.round((darkness - 0.35f) / 0.3f * 255f)));
                out.setRGB(i, j, (a << 24) | rgbInk);
            }
        }
        return out;
    }

    /** A drawing of a piece of produce, or a little heap of them. */
    private static void drawProduce(Graphics2D g, Products.Produce p, float cx, float cy, float size, Random rand) {
        Color main = colour(p.colour), second = colour(p.colour2), leaf = colour(p.leafColour);
        int count = p.size < 2f && rand.nextBoolean() ? 3 : 1;
        for (int k = 0; k < count; k++) {
            float ox = count == 1 ? 0f : (k - 1) * size * 0.45f, oy = count == 1 ? 0f : (k == 1 ? -size * 0.15f : size * 0.1f);
            float s = count == 1 ? size : size * 0.65f;
            float w = s * Math.min(1.4f, Math.max(0.35f, p.width)), h = s;
            if (p.shape == Products.Shape.DISC) { w = s; h = s * 0.55f; }
            float x0 = cx + ox - w * 0.5f, y0 = cy + oy - h * 0.5f;
            if (p.shape == Products.Shape.CLUSTER) {
                for (int i = 0; i < 7; i++) {
                    float bx = cx + ox + (i % 3 - 1) * w * 0.28f, by = y0 + h * (0.2f + 0.2f * (i / 2));
                    g.setColor(i % 2 == 0 ? main : main.darker());
                    g.fill(new Ellipse2D.Float(bx - s * 0.13f, by - s * 0.13f, s * 0.26f, s * 0.26f));
                }
            } else {
                g.setColor(main);
                g.fill(new Ellipse2D.Float(x0, y0, w, h));
                g.setColor(second);
                g.setStroke(new BasicStroke(Math.max(1.5f, s * 0.04f)));
                if (p.pattern == 1 || p.shape == Products.Shape.RIBBED || p.shape == Products.Shape.LOBED) {
                    for (int i = 1; i < 4; i++) g.draw(new Ellipse2D.Float(x0 + w * 0.5f - w * i / 8f, y0, w * i / 4f, h));
                } else if (p.pattern == 2 || p.pattern == 3) {
                    for (int i = 0; i < 6; i++) {
                        float sx = x0 + w * (0.25f + 0.5f * ((i * 0.37f) % 1f)), sy = y0 + h * (0.25f + 0.5f * ((i * 0.61f) % 1f));
                        g.fill(new Ellipse2D.Float(sx, sy, s * 0.08f, s * 0.08f));
                    }
                }
                if (p.shape == Products.Shape.SPIKY) {
                    g.setColor(leaf);
                    for (int i = 0; i < 10; i++) {
                        double a = i * Math.PI / 5;
                        float ex = cx + ox + (float) Math.cos(a) * w * 0.5f, ey = cy + oy + (float) Math.sin(a) * h * 0.5f;
                        g.draw(new java.awt.geom.Line2D.Float(ex, ey, ex + (float) Math.cos(a) * s * 0.12f, ey + (float) Math.sin(a) * s * 0.12f));
                    }
                }
                // A highlight
                g.setColor(new Color(255, 255, 255, 90));
                g.fill(new Ellipse2D.Float(x0 + w * 0.2f, y0 + h * 0.15f, w * 0.25f, h * 0.2f));
            }
            if (p.leaves) {
                g.setColor(leaf);
                for (int i = -1; i <= 1; i++) {
                    Path2D.Float frond = new Path2D.Float();
                    frond.moveTo(cx + ox, y0 + h * 0.05f);
                    frond.quadTo(cx + ox + i * s * 0.3f, y0 - s * 0.2f, cx + ox + i * s * 0.2f, y0 - s * 0.32f);
                    frond.quadTo(cx + ox + i * s * 0.05f, y0 - s * 0.1f, cx + ox, y0 + h * 0.05f);
                    g.fill(frond);
                }
            }
        }
    }

    /** A maker's mark: rings, a many-pointed star or nested shapes. */
    private static void drawEmblem(Graphics2D g, float cx, float cy, float size, Color accent, Color ink, Random rand) {
        int kind = rand.nextInt(4);
        g.setStroke(new BasicStroke(Math.max(2f, size * 0.07f)));
        switch (kind) {
            case 0 -> {
                g.setColor(accent);
                g.fill(new Ellipse2D.Float(cx - size * 0.5f, cy - size * 0.5f, size, size));
                g.setColor(ink);
                g.draw(new Ellipse2D.Float(cx - size * 0.35f, cy - size * 0.35f, size * 0.7f, size * 0.7f));
                g.fill(new Ellipse2D.Float(cx - size * 0.12f, cy - size * 0.12f, size * 0.24f, size * 0.24f));
            }
            case 1 -> {
                int points = 5 + rand.nextInt(6);
                Path2D.Float star = new Path2D.Float();
                for (int i = 0; i < points * 2; i++) {
                    double a = Math.PI * i / points;
                    float r = (i % 2 == 0 ? 0.5f : 0.22f) * size;
                    float px = cx + (float) Math.cos(a) * r, py = cy + (float) Math.sin(a) * r;
                    if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
                }
                star.closePath();
                g.setColor(accent);
                g.fill(star);
                g.setColor(ink);
                g.draw(star);
            }
            case 2 -> {
                g.setColor(ink);
                g.fill(new RoundRectangle2D.Float(cx - size * 0.45f, cy - size * 0.3f, size * 0.9f, size * 0.6f, size * 0.3f, size * 0.3f));
                g.setColor(accent);
                Path2D.Float tri = new Path2D.Float();
                tri.moveTo(cx, cy - size * 0.22f);
                tri.lineTo(cx + size * 0.25f, cy + size * 0.2f);
                tri.lineTo(cx - size * 0.25f, cy + size * 0.2f);
                tri.closePath();
                g.fill(tri);
            }
            default -> {
                g.setColor(accent);
                for (int i = 0; i < 3; i++) {
                    double a = Math.PI * 2 * i / 3 - Math.PI / 2;
                    float r = size * 0.22f;
                    g.fill(new Ellipse2D.Float(cx + (float) Math.cos(a) * r - r, cy + (float) Math.sin(a) * r - r, r * 2, r * 2));
                }
                g.setColor(ink);
                g.fill(new Ellipse2D.Float(cx - size * 0.1f, cy - size * 0.1f, size * 0.2f, size * 0.2f));
            }
        }
    }

    /** A produce skin: its colour and markings, with a strip of leaf colour along the foot. */
    private static void drawSkin(Graphics2D g, int x, int y, Products.Produce p, Random rand) {
        Color main = colour(p.colour), second = colour(p.colour2);
        g.setColor(main);
        g.fillRect(x, y, CELL, CELL);
        g.setColor(second);
        switch (p.pattern) {
            case 1 -> {
                // Stripes along its length: across u, which runs round it
                int stripes = 4 + rand.nextInt(6);
                for (int i = 0; i < stripes; i++) g.fillRect(x + i * CELL / stripes, y, Math.max(2, CELL / stripes / 3), CELL);
            }
            case 2 -> {
                for (int i = 0; i < 26; i++) {
                    float r = CELL * (0.02f + rand.nextFloat() * 0.04f);
                    g.fill(new Ellipse2D.Float(x + rand.nextFloat() * CELL, y + rand.nextFloat() * CELL * 0.8f, r, r));
                }
            }
            case 3 -> {
                for (int i = 0; i < 400; i++) g.fillRect(x + rand.nextInt(CELL), y + rand.nextInt(CELL * 4 / 5), 1, 1);
            }
            case 4 -> {
                g.setPaint(new GradientPaint(x, y, main, x, y + CELL * 0.8f, second));
                g.fillRect(x, y, CELL, CELL);
            }
            default -> { }
        }
        g.setColor(colour(p.leafColour));
        g.fillRect(x, y + CELL * 84 / 100, CELL, CELL - CELL * 84 / 100);
    }

    private static float brightness(Color c) {
        return (c.getRed() * 0.299f + c.getGreen() * 0.587f + c.getBlue() * 0.114f) / 255f;
    }

    private static Color colour(float[] c) {
        return new Color(Math.max(0, Math.min(255, Math.round(c[0] * 255))), Math.max(0, Math.min(255, Math.round(c[1] * 255))),
                Math.max(0, Math.min(255, Math.round(c[2] * 255))));
    }
}
