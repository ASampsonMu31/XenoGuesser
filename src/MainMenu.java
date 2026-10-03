import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JComponent;

/**
 * The title screen: a view of a landing pod on an alien world (a frame of the game itself),
 * the game's name and its options on the left, and the player's spaceman on the right, who
 * can be turned round by dragging. Play Singleplayer starts loading a world; multiplayer is
 * not available yet.
 */
public class MainMenu extends JComponent {

    private static final Color BACKDROP = new Color(20, 16, 30);

    private final Runnable onSingleplayer;
    private final Runnable onQuit;
    private BufferedImage background, spacemanSheet;
    private Image scaledBackground;
    private int scaledWidth = -1, scaledHeight = -1;

    private final Rectangle singleButton = new Rectangle(), multiButton = new Rectangle(), quitButton = new Rectangle();
    private Rectangle hovered;
    private boolean chosen;

    // The spaceman: which way he faces, and dragging him round
    private float spacemanAngle = 200f;
    private int dragStartX;
    private float dragStartAngle;
    private boolean dragging;
    private Rectangle spacemanBounds = new Rectangle();

    public MainMenu(Runnable onSingleplayer, Runnable onQuit) {
        this.onSingleplayer = onSingleplayer;
        this.onQuit = onQuit;
        setOpaque(true);
        loadArt();
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || chosen) return;
                if (singleButton.contains(e.getPoint())) {
                    chosen = true;
                    onSingleplayer.run();
                } else if (quitButton.contains(e.getPoint())) {
                    onQuit.run();
                } else if (spacemanBounds.contains(e.getPoint())) {
                    dragging = true;
                    dragStartX = e.getX();
                    dragStartAngle = spacemanAngle;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragging) {
                    // Dragging right turns him to his left, as if spinning him by the arm
                    spacemanAngle = dragStartAngle - (e.getX() - dragStartX) * 0.6f;
                    repaint(spacemanBounds.x - 60, spacemanBounds.y - 20, spacemanBounds.width + 120, spacemanBounds.height + 80);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                Rectangle over = singleButton.contains(e.getPoint()) ? singleButton
                        : quitButton.contains(e.getPoint()) ? quitButton : null;
                if (over != hovered) {
                    hovered = over;
                    repaint();
                }
                boolean hand = over != null || spacemanBounds.contains(e.getPoint());
                setCursor(hand ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    private void loadArt() {
        Thread loader = new Thread(() -> {
            LoadingArt.ensure();
            try {
                BufferedImage sheet = ImageIO.read(new File(LoadingArt.SPACEMAN));
                BufferedImage menu = new File(LoadingArt.MENU).exists() ? ImageIO.read(new File(LoadingArt.MENU)) : null;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    spacemanSheet = sheet;
                    background = menu;
                    scaledWidth = -1;
                    repaint();
                });
            } catch (Exception e) {
                System.err.println("Main menu art unavailable: " + e.getMessage());
            }
        }, "menu-art");
        loader.setDaemon(true);
        loader.start();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth(), h = getHeight();
        Graphics2D g = (Graphics2D) graphics.create();
        HudStyle.smooth(g);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // The world, cropped to fill the window
        if (background != null) {
            if (scaledWidth != w || scaledHeight != h) {
                float s = Math.max(w / (float) background.getWidth(), h / (float) background.getHeight());
                int sw = Math.round(background.getWidth() * s), sh = Math.round(background.getHeight() * s);
                BufferedImage scaled = getGraphicsConfiguration() != null
                        ? getGraphicsConfiguration().createCompatibleImage(w, h)
                        : new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D sg = scaled.createGraphics();
                sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                sg.drawImage(background, (w - sw) / 2, (h - sh) / 2, sw, sh, null);
                sg.dispose();
                scaledBackground = scaled;
                scaledWidth = w;
                scaledHeight = h;
            }
            g.drawImage(scaledBackground, 0, 0, null);
        } else {
            g.setPaint(new GradientPaint(0, 0, new Color(60, 30, 90), 0, h, BACKDROP));
            g.fillRect(0, 0, w, h);
        }
        // Darken the left so the menu reads clearly, and a little at the top and bottom
        g.setPaint(new GradientPaint(0, 0, new Color(6, 8, 14, 225), w * 0.48f, 0, new Color(6, 8, 14, 0)));
        g.fillRect(0, 0, w, h);
        g.setPaint(new GradientPaint(0, h * 0.75f, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 120)));
        g.fillRect(0, Math.round(h * 0.75f), w, h - Math.round(h * 0.75f));

        float unit = h / 1080f;
        float left = 110 * unit;
        paintTitle(g, left, 250 * unit, unit);

        float bw = 420 * unit, bh = 64 * unit, gap = 18 * unit;
        float top = 470 * unit;
        singleButton.setBounds(Math.round(left), Math.round(top), Math.round(bw), Math.round(bh));
        multiButton.setBounds(Math.round(left), Math.round(top + bh + gap), Math.round(bw), Math.round(bh));
        quitButton.setBounds(Math.round(left), Math.round(top + (bh + gap) * 2 + gap * 1.5f), Math.round(bw * 0.55f), Math.round(bh * 0.8f));
        paintButton(g, singleButton, "Play Singleplayer", null, true, true, unit);
        paintButton(g, multiButton, "Play Multiplayer", "Coming soon", false, false, unit);
        paintButton(g, quitButton, "Quit", null, true, false, unit);

        HudStyle.label(g, "Drag to turn", w * 0.78f - HudStyle.labelWidth(g, "Drag to turn", 12 * unit) / 2f, h - 54 * unit, 12 * unit,
                new Color(220, 228, 240, 170));
        paintSpaceman(g, w * 0.78f, h * 0.9f, h * 0.68f);

        if (chosen) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
        }
        g.dispose();
    }

    private void paintTitle(Graphics2D g, float x, float baseline, float unit) {
        HudStyle.label(g, "Xenocorp planetary survey programme", x + 4 * unit, baseline - 118 * unit, 15 * unit, HudStyle.ACCENT);
        Font titleFont = HudStyle.font(Font.BOLD, 112 * unit).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.04f));
        g.setFont(titleFont);
        // A soft shadow, then the name in two tones
        g.setColor(new Color(0, 0, 0, 140));
        g.drawString("XENO", x + 4 * unit, baseline + 5 * unit);
        float xenoWidth = g.getFontMetrics().stringWidth("XENO");
        g.drawString("GUESSER", x + xenoWidth + 4 * unit, baseline + 5 * unit);
        g.setColor(HudStyle.ACCENT);
        g.drawString("XENO", x, baseline);
        g.setColor(Color.WHITE);
        g.drawString("GUESSER", x + xenoWidth, baseline);
        g.setColor(new Color(120, 205, 235, 160));
        g.fill(new java.awt.geom.Rectangle2D.Float(x, baseline + 26 * unit, 300 * unit, 2.5f * unit));
        g.setFont(HudStyle.font(Font.PLAIN, 22 * unit));
        g.setColor(new Color(225, 232, 242));
        g.drawString("You have been dropped somewhere on an alien world. Find out where.", x, baseline + 70 * unit);
    }

    private void paintButton(Graphics2D g, Rectangle r, String text, String note, boolean enabled, boolean primary, float unit) {
        boolean over = enabled && hovered == r;
        Path2D shape = HudStyle.chamfered(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 12 * unit);
        Color fill = !enabled ? new Color(40, 44, 52, 170)
                : primary ? (over ? new Color(45, 165, 205, 240) : new Color(24, 110, 145, 225))
                : (over ? new Color(70, 80, 96, 235) : new Color(24, 30, 40, 210));
        g.setColor(fill);
        g.fill(shape);
        g.setColor(!enabled ? new Color(120, 130, 140, 110) : over ? Color.WHITE : HudStyle.EDGE);
        g.setStroke(new BasicStroke(1.4f * Math.max(1f, unit)));
        g.draw(shape);
        g.setFont(HudStyle.font(Font.BOLD, r.height * 0.36f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(enabled ? Color.WHITE : new Color(150, 156, 166));
        float tx = r.x + 26 * unit;
        float ty = r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2f;
        g.drawString(text, tx, ty);
        if (note != null) {
            float noteSize = 11 * unit;
            HudStyle.label(g, note, r.x + r.width - 22 * unit - HudStyle.labelWidth(g, note, noteSize), ty - 2 * unit, noteSize,
                    new Color(150, 156, 166));
        }
        if (enabled && primary) {
            // An arrow at the right-hand end
            float ax = r.x + r.width - 34 * unit, cy = r.y + r.height / 2f, s = r.height * 0.14f;
            Path2D tri = new Path2D.Float();
            tri.moveTo(ax, cy - s);
            tri.lineTo(ax + s * 1.2f, cy);
            tri.lineTo(ax, cy + s);
            tri.closePath();
            g.setColor(Color.WHITE);
            g.fill(tri);
        }
    }

    /** The spaceman standing with his feet at (feetX, feetY), heightPx tall. */
    private void paintSpaceman(Graphics2D g, float feetX, float feetY, float heightPx) {
        if (spacemanSheet == null) return;
        // The turntable frame spans a little more than his height, with his feet this far down it
        float frameSpan = 2f * 62f * (float) Math.tan(Math.toRadians(11));
        float drawHeight = heightPx * frameSpan / 22.5f;
        float drawWidth = drawHeight * LoadingArt.FRAME_WIDTH / LoadingArt.FRAME_HEIGHT;
        float feetFraction = 0.5f + 11.4f / frameSpan;
        int x = Math.round(feetX - drawWidth / 2f), y = Math.round(feetY - drawHeight * feetFraction);
        spacemanBounds = new Rectangle(x + Math.round(drawWidth * 0.15f), y, Math.round(drawWidth * 0.7f), Math.round(drawHeight));

        float step = 360f / LoadingArt.FRAMES;
        int frame = Math.floorMod(Math.round(spacemanAngle / step), LoadingArt.FRAMES);
        int sx = (frame % LoadingArt.FRAME_COLUMNS) * LoadingArt.FRAME_WIDTH;
        int sy = (frame / LoadingArt.FRAME_COLUMNS) * LoadingArt.FRAME_HEIGHT;
        Graphics2D s = (Graphics2D) g.create();
        s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        s.setColor(new Color(0, 0, 0, 110));
        s.fillOval(Math.round(feetX - drawWidth * 0.32f), Math.round(feetY - drawWidth * 0.06f), Math.round(drawWidth * 0.64f), Math.round(drawWidth * 0.14f));
        s.drawImage(spacemanSheet, x, y, x + Math.round(drawWidth), y + Math.round(drawHeight),
                sx, sy, sx + LoadingArt.FRAME_WIDTH, sy + LoadingArt.FRAME_HEIGHT, null);
        s.dispose();
    }
}
