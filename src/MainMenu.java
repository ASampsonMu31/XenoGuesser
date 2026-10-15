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
 * can be turned round by dragging. Play Singleplayer starts loading a world; beside it, the
 * game mode to play it in (see GameMode) and the kind of map (see MapKind), each one of a few
 * like radio buttons. Multiplayer is not available yet.
 */
public class MainMenu extends JComponent {

    private static final Color BACKDROP = new Color(20, 16, 30);

    private final Runnable onSingleplayer;
    private final Runnable onMultiplayer;
    private final Runnable onQuit;
    // The backgrounds (frames of the game, in assets/art/menu), one chosen at random each time
    // the menu is shown, never the same twice running. Each with where its horizon is and where
    // the spaceman's feet go (on dry ground, below him), as shares of the picture's height, and
    // how tall he stands for how far below the horizon his feet are: measured off the pod, so
    // that beside it the top of his head would be level with the middle of its door
    private static final float[][] BACKGROUNDS = {
        { 0.46f, 0.92f, 1.41f }, { 0.50f, 0.72f, 1.52f }, { 0.53f, 0.70f, 1.48f },
        { 0.56f, 0.92f, 1.70f }, { 0.59f, 0.92f, 1.32f }, { 0.49f, 0.92f, 0.90f },
        // (on a hillside, tilted: a level line through the pod's feet stands in for the horizon)
        { 0.50f, 0.92f, 0.63f } };
    // Where across the picture the spaceman stands (a share of its width)
    private static final float SPACEMAN_ACROSS = 0.78f;
    private static int lastBackground = -1;
    private final int backgroundIndex = chooseBackground();
    private BufferedImage background, spacemanSheet;
    // Where the background is drawn (cropped to fill the window): its offset and scaled size
    private float backgroundX, backgroundY, backgroundWidth, backgroundHeight;
    private Image scaledBackground;
    private int scaledWidth = -1, scaledHeight = -1;

    private final Rectangle singleButton = new Rectangle(), multiButton = new Rectangle(), quitButton = new Rectangle();
    // The game modes' buttons, beside Play Singleplayer, in GameMode's order; the kinds of map's below, in MapKind's
    private final Rectangle[] modeButtons = new Rectangle[GameMode.values().length], mapButtons = new Rectangle[MapKind.values().length];
    private Rectangle hovered;
    private boolean chosen;

    // The spaceman: which way he faces, and dragging him round
    private float spacemanAngle = 200f;
    private int dragStartX;
    private float dragStartAngle;
    private boolean dragging;
    private Rectangle spacemanBounds = new Rectangle();

    public MainMenu(Runnable onSingleplayer, Runnable onMultiplayer, Runnable onQuit) {
        this.onSingleplayer = onSingleplayer;
        this.onMultiplayer = onMultiplayer;
        this.onQuit = onQuit;
        setOpaque(true);
        for (int m = 0; m < modeButtons.length; m++) modeButtons[m] = new Rectangle();
        for (int m = 0; m < mapButtons.length; m++) mapButtons[m] = new Rectangle();
        loadArt();
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || chosen) return;
                if (singleButton.contains(e.getPoint())) {
                    chosen = true;
                    onSingleplayer.run();
                } else if (multiButton.contains(e.getPoint())) {
                    chosen = true;
                    onMultiplayer.run();
                } else if (quitButton.contains(e.getPoint())) {
                    onQuit.run();
                } else if (choiceAt(modeButtons, e.getX(), e.getY()) >= 0) {
                    // (one mode at a time: choosing one unticks the others)
                    GameMode.choose(GameMode.values()[choiceAt(modeButtons, e.getX(), e.getY())]);
                    repaint();
                } else if (choiceAt(mapButtons, e.getX(), e.getY()) >= 0) {
                    MapKind.choose(MapKind.values()[choiceAt(mapButtons, e.getX(), e.getY())]);
                    repaint();
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
                int mode = choiceAt(modeButtons, e.getX(), e.getY()), map = choiceAt(mapButtons, e.getX(), e.getY());
                Rectangle over = singleButton.contains(e.getPoint()) ? singleButton
                        : multiButton.contains(e.getPoint()) ? multiButton
                        : quitButton.contains(e.getPoint()) ? quitButton
                        : mode >= 0 ? modeButtons[mode] : map >= 0 ? mapButtons[map] : null;
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

    /** Which of a row of choices' buttons is at a point, -1 none. */
    private static int choiceAt(Rectangle[] buttons, int x, int y) {
        for (int m = 0; m < buttons.length; m++) if (buttons[m].contains(x, y)) return m;
        return -1;
    }

    private static synchronized int chooseBackground() {
        int pick = new java.util.Random().nextInt(BACKGROUNDS.length - (lastBackground < 0 ? 0 : 1));
        if (lastBackground >= 0 && pick >= lastBackground) pick++;
        lastBackground = pick;
        return pick;
    }

    private void loadArt() {
        Thread loader = new Thread(() -> {
            LoadingArt.ensure();
            try {
                BufferedImage sheet = ImageIO.read(new File(LoadingArt.SPACEMAN));
                File file = new File(LoadingArt.MENU_BACKGROUNDS + "/background_" + (backgroundIndex + 1) + ".png");
                BufferedImage menu = file.exists() ? ImageIO.read(file) : null;
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
                backgroundX = (w - sw) / 2f;
                backgroundY = (h - sh) / 2f;
                backgroundWidth = sw;
                backgroundHeight = sh;
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
        paintButton(g, singleButton, "Play Singleplayer", null, true, true, unit);
        // The expedition's settings below the play buttons, and Quit below them
        String[] modes = java.util.Arrays.stream(GameMode.values()).map(m -> m.label).toArray(String[]::new);
        String[] maps = java.util.Arrays.stream(MapKind.values()).map(m -> m.label).toArray(String[]::new);
        float settingsHeight = paintSettings(g, modes, maps, left, top + (bh + gap) * 2, bw, unit);
        quitButton.setBounds(Math.round(left), Math.round(top + (bh + gap) * 2 + settingsHeight + gap * 1.5f), Math.round(bw * 0.55f), Math.round(bh * 0.8f));
        paintButton(g, multiButton, "Play Multiplayer", null, true, false, unit);
        paintButton(g, quitButton, "Quit", null, true, false, unit);

        // The spaceman standing on the ground in the picture, as tall as he'd look there
        float feetX = w * 0.78f, feetY = h * 0.9f, tall = h * 0.68f;
        if (background != null) {
            float[] place = BACKGROUNDS[backgroundIndex];
            float horizon = backgroundY + place[0] * backgroundHeight;
            feetX = backgroundX + SPACEMAN_ACROSS * backgroundWidth;
            feetY = backgroundY + place[1] * backgroundHeight;
            tall = (feetY - horizon) * place[2];
        }
        HudStyle.label(g, "Drag to turn", feetX - HudStyle.labelWidth(g, "Drag to turn", 12 * unit) / 2f, Math.min(h - 54 * unit, feetY + 30 * unit), 12 * unit,
                new Color(220, 228, 240, 170));
        paintSpaceman(g, feetX, feetY, tall);

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

    // The settings panel: its padding, the height of a row of choices, and the room over each for its title
    private static final float SETTINGS_PAD = 26f, SETTINGS_ROW = 46f, SETTINGS_ROW_TITLE = 30f;

    /**
     * The game's settings on a panel of their own (the game's standard panel, see HudStyle.panel),
     * from x, y: its heading, then the game mode and the kind of map, each a row of choices
     * like radio buttons, set apart from the buttons beside it so they belong to neither.
     */
    /** The expedition settings' panel at (x, y), at least leastWidth wide; returns its height. */
    private float paintSettings(Graphics2D g, String[] modes, String[] maps, float x, float y, float leastWidth, float unit) {
        float pad = SETTINGS_PAD * unit, row = SETTINGS_ROW * unit, rowTitle = SETTINGS_ROW_TITLE * unit;
        float headingHeight = 40 * unit;
        float width = Math.max(leastWidth, Math.max(rowWidth(g, modes, row, unit), rowWidth(g, maps, row, unit)) + pad * 2);
        float height = pad + headingHeight + (rowTitle + row) * 2 + pad * 0.6f;
        HudStyle.panel(g, x, y, width, height, HudStyle.GLASS);
        HudStyle.label(g, "Expedition settings", x + pad, y + pad + 12 * unit, 15 * unit, HudStyle.VALUE);
        g.setColor(new Color(120, 205, 235, 70));
        g.fill(new java.awt.geom.Rectangle2D.Float(x + pad, y + pad + 24 * unit, width - pad * 2, Math.max(1f, unit)));
        float rowTop = y + pad + headingHeight + rowTitle;
        paintChoices(g, "Game mode", modes, GameMode.current().ordinal(), modeButtons, x + pad, rowTop, row, unit);
        paintChoices(g, "Map", maps, MapKind.current().ordinal(), mapButtons, x + pad, rowTop + row + rowTitle, row, unit);
        return height;
    }

    /** How wide a row of choices (labels) is, its buttons as tall as height. */
    private float rowWidth(Graphics2D g, String[] labels, float height, float unit) {
        float h = height * 0.78f;
        g.setFont(HudStyle.font(Font.BOLD, h * 0.36f));
        FontMetrics fm = g.getFontMetrics();
        float width = 0;
        for (String label : labels) width += 18 * unit + h * 0.34f + 10 * unit + fm.stringWidth(label) + 20 * unit + 10 * unit;
        return width - 10 * unit;
    }

    /**
     * A row of choices (labels; the one chosen), like radio buttons (from x, its top at top, as
     * tall as height), their buttons laid out into buttons: each with a round tick, the chosen
     * one's filled in, and over them what's being chosen (title).
     */
    private void paintChoices(Graphics2D g, String title, String[] labels, int chosenIndex, Rectangle[] buttons,
                              float x, float top, float height, float unit) {
        float labelSize = 12 * unit;
        HudStyle.label(g, title, x + 2 * unit, top - 9 * unit, labelSize, HudStyle.ACCENT);
        float h = height * 0.78f, y = top + (height - h) / 2f, gap = 10 * unit;
        g.setFont(HudStyle.font(Font.BOLD, h * 0.36f));
        FontMetrics fm = g.getFontMetrics();
        for (int m = 0; m < buttons.length; m++) {
            float radio = h * 0.34f;
            float w = 18 * unit + radio + 10 * unit + fm.stringWidth(labels[m]) + 20 * unit;
            Rectangle r = buttons[m];
            r.setBounds(Math.round(x), Math.round(y), Math.round(w), Math.round(h));
            boolean on = m == chosenIndex, over = hovered == r;
            Path2D shape = HudStyle.chamfered(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 9 * unit);
            g.setColor(on ? new Color(24, 110, 145, 225) : over ? new Color(70, 80, 96, 235) : new Color(24, 30, 40, 210));
            g.fill(shape);
            g.setColor(over || on ? Color.WHITE : HudStyle.EDGE);
            g.setStroke(new BasicStroke(1.4f * Math.max(1f, unit)));
            g.draw(shape);
            // The radio button: a ring, filled in when it's the one chosen
            float rx = r.x + 18 * unit, ry = r.y + (r.height - radio) / 2f;
            g.setColor(Color.WHITE);
            g.draw(new java.awt.geom.Ellipse2D.Float(rx, ry, radio, radio));
            if (on) {
                float inset = radio * 0.26f;
                g.fill(new java.awt.geom.Ellipse2D.Float(rx + inset, ry + inset, radio - 2 * inset, radio - 2 * inset));
            }
            g.setColor(on || over ? Color.WHITE : new Color(215, 222, 232));
            g.drawString(labels[m], rx + radio + 10 * unit, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2f);
            x += w + gap;
        }
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
