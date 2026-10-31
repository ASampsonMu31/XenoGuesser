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
 * can be turned round by dragging. Play Singleplayer opens the choice of game mode and map over
 * the menu (see SingleplayerPanel), and Play Multiplayer the multiplayer panel (see
 * MultiplayerPanel), either dimming it until the panel is closed (unchoose()); the player's name
 * and suit colour, at the top right, open their profile the same way (see ProfilePanel, M58).
 * Under the buttons, the graphics and shadows, each one of a few like radio buttons.
 */
public class MainMenu extends JComponent {

    private static final Color BACKDROP = new Color(20, 16, 30);

    private final Runnable onSingleplayer;
    private final Runnable onMultiplayer;
    private final Runnable onQuit;
    private final Runnable onProfile;
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
    private final Rectangle profileButton = new Rectangle();
    // The graphics' buttons (see GameSettings.Quality) and the shadows' (off, on) below them
    private final Rectangle[] qualityButtons = new Rectangle[GameSettings.Quality.values().length], shadowButtons = new Rectangle[2];
    private Rectangle hovered;
    private boolean chosen;
    // The achievements' panel (M53): each one's row, its Reset button, and while Reset's being
    // confirmed, the question's two buttons
    private final Rectangle[] achievementRows = new Rectangle[Achievements.Achievement.values().length];
    private final Rectangle resetButton = new Rectangle(), confirmReset = new Rectangle(), cancelReset = new Rectangle();
    private boolean confirming;
    // Something to tell the player as the menu comes back (multiplayer: the partner left), or null
    private String notice;

    // The spaceman: which way he faces, and dragging him round
    private float spacemanAngle = 200f;
    private int dragStartX;
    private float dragStartAngle;
    private boolean dragging;
    private Rectangle spacemanBounds = new Rectangle();

    public MainMenu(Runnable onSingleplayer, Runnable onMultiplayer, Runnable onQuit, Runnable onProfile) {
        this.onSingleplayer = onSingleplayer;
        this.onMultiplayer = onMultiplayer;
        this.onQuit = onQuit;
        this.onProfile = onProfile;
        setOpaque(true);
        for (int m = 0; m < qualityButtons.length; m++) qualityButtons[m] = new Rectangle();
        for (int m = 0; m < shadowButtons.length; m++) shadowButtons[m] = new Rectangle();
        for (int a = 0; a < achievementRows.length; a++) achievementRows[a] = new Rectangle();
        Achievements.setOnChange(this::repaint);
        loadArt();
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || chosen) return;
                // (while Reset's being confirmed, only its question's buttons do anything)
                if (confirming) {
                    if (confirmReset.contains(e.getPoint())) Achievements.reset();
                    if (confirmReset.contains(e.getPoint()) || cancelReset.contains(e.getPoint())) {
                        confirming = false;
                        repaint();
                    }
                    return;
                }
                if (resetButton.contains(e.getPoint())) {
                    confirming = true;
                    repaint();
                } else if (singleButton.contains(e.getPoint())) {
                    chosen = true;
                    onSingleplayer.run();
                } else if (multiButton.contains(e.getPoint())) {
                    chosen = true;
                    onMultiplayer.run();
                } else if (quitButton.contains(e.getPoint())) {
                    onQuit.run();
                } else if (profileButton.contains(e.getPoint())) {
                    chosen = true;
                    onProfile.run();
                } else if (choiceAt(qualityButtons, e.getX(), e.getY()) >= 0) {
                    // (the shadows go on or off with the graphics, as in the settings)
                    GameSettings.setQuality(GameSettings.Quality.values()[choiceAt(qualityButtons, e.getX(), e.getY())]);
                    repaint();
                } else if (choiceAt(shadowButtons, e.getX(), e.getY()) >= 0) {
                    GameSettings.setShadows(choiceAt(shadowButtons, e.getX(), e.getY()) == 1);
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
                int quality = choiceAt(qualityButtons, e.getX(), e.getY()), shadow = choiceAt(shadowButtons, e.getX(), e.getY());
                int achievement = choiceAt(achievementRows, e.getX(), e.getY());
                Rectangle over = confirming ? (confirmReset.contains(e.getPoint()) ? confirmReset : cancelReset.contains(e.getPoint()) ? cancelReset : null)
                        : resetButton.contains(e.getPoint()) ? resetButton
                        : achievement >= 0 ? achievementRows[achievement]
                        : singleButton.contains(e.getPoint()) ? singleButton
                        : multiButton.contains(e.getPoint()) ? multiButton
                        : quitButton.contains(e.getPoint()) ? quitButton
                        : profileButton.contains(e.getPoint()) ? profileButton
                        : quality >= 0 ? qualityButtons[quality] : shadow >= 0 ? shadowButtons[shadow] : null;
                if (over != hovered) {
                    hovered = over;
                    repaint();
                }
                boolean hand = (over != null && choiceAt(achievementRows, e.getX(), e.getY()) < 0) || (!confirming && spacemanBounds.contains(e.getPoint()));
                setCursor(hand ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Shows a notice over the menu (multiplayer: why the game didn't go ahead) until a choice is made. */
    public void setNotice(String text) {
        notice = text;
        repaint();
    }

    /** Dims the menu while a panel is over it that it didn't open itself (the profile asked for as the game starts). */
    public void dim() {
        chosen = true;
        repaint();
    }

    /** Makes the menu usable again (undimmed) after the multiplayer panel over it has been closed. */
    public void unchoose() {
        chosen = false;
        hovered = null;
        repaint();
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
        // (high enough for the settings' rows and Quit to fit below)
        float top = 440 * unit;
        singleButton.setBounds(Math.round(left), Math.round(top), Math.round(bw), Math.round(bh));
        multiButton.setBounds(Math.round(left), Math.round(top + bh + gap), Math.round(bw), Math.round(bh));
        paintButton(g, singleButton, "Play Singleplayer", null, true, true, unit);
        // The graphics settings below the play buttons, and Quit below them
        float settingsHeight = paintSettings(g, left, top + (bh + gap) * 2, bw, unit);
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

        paintProfile(g, w, unit);
        paintAchievements(g, w, h, unit);
        if (notice != null && !chosen) paintNotice(g, left, top - 110 * unit, 520 * unit, unit);
        if (confirming) paintResetQuestion(g, w, h, unit);
        if (chosen) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
        }
        g.dispose();
    }

    // The achievements' panel: how wide each of its two columns is, a row's height
    private static final float ACHIEVEMENT_COLUMN = 290f, ACHIEVEMENT_ROW = 25f;

    /**
     * The achievements (M53), at the bottom in the middle: singleplayer (playing, each win, and the pod's chair in the dark) down the left,
     * the collections, multiplayer and Bullseye down the right, each ticked once it's unlocked; how many of
     * them; what the one under the mouse is for; and Reset.
     */
    private void paintAchievements(Graphics2D g, int w, int h, float unit) {
        Achievements.Achievement[] all = Achievements.Achievement.values();
        int leftCount = 8, rows = Math.max(leftCount, all.length - leftCount);
        float pad = 20 * unit, column = ACHIEVEMENT_COLUMN * unit, row = ACHIEVEMENT_ROW * unit;
        float pw = pad * 2 + column * 2, ph = pad + 38 * unit + rows * row + 30 * unit + pad * 0.6f;
        float px = w * 0.53f - pw / 2f, py = h - ph - 24 * unit;
        // (grey, as the HUD's panels are)
        HudStyle.panel(g, px, py, pw, ph, HudStyle.GLASS_SOLID);
        // How many are done: a ring filling round in gold, the count inside it
        float ring = 34 * unit, rx = px + pad, ry = py + pad - 10 * unit;
        float share = Achievements.count() / (float) all.length;
        g.setStroke(new BasicStroke(4f * unit, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 40));
        g.draw(new java.awt.geom.Ellipse2D.Float(rx, ry, ring, ring));
        if (share > 0f) {
            g.setColor(HudStyle.GOLD);
            g.draw(new java.awt.geom.Arc2D.Float(rx, ry, ring, ring, 90f, -360f * share, java.awt.geom.Arc2D.OPEN));
        }
        g.setFont(HudStyle.font(Font.BOLD, 11 * unit));
        String doneCount = String.valueOf(Achievements.count());
        g.setColor(share > 0f ? HudStyle.GOLD : Color.WHITE);
        g.drawString(doneCount, rx + (ring - g.getFontMetrics().stringWidth(doneCount)) / 2f, ry + (ring + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2f);
        HudStyle.label(g, "Achievements", rx + ring + 12 * unit, py + pad + 12 * unit, 15 * unit, Color.WHITE);
        String tally = Math.round(share * 100f) + "% complete";
        HudStyle.label(g, tally, rx + ring + 12 * unit + HudStyle.labelWidth(g, "Achievements", 15 * unit) + 14 * unit, py + pad + 12 * unit, 11 * unit, share > 0f ? HudStyle.GOLD : HudStyle.LABEL);
        // Reset, at the heading's right
        g.setFont(HudStyle.font(Font.BOLD, 12 * unit));
        FontMetrics fm = g.getFontMetrics();
        int rw = Math.round(fm.stringWidth("Reset") + 26 * unit), rh = Math.round(24 * unit);
        resetButton.setBounds(Math.round(px + pw - pad - rw), Math.round(py + pad - 4 * unit), rw, rh);
        boolean overReset = hovered == resetButton;
        g.setColor(overReset ? new Color(150, 60, 55, 235) : new Color(40, 30, 34, 220));
        g.fill(new java.awt.geom.RoundRectangle2D.Float(resetButton.x, resetButton.y, rw, rh, 8 * unit, 8 * unit));
        g.setColor(new Color(235, 140, 130));
        g.draw(new java.awt.geom.RoundRectangle2D.Float(resetButton.x, resetButton.y, rw, rh, 8 * unit, 8 * unit));
        g.setColor(Color.WHITE);
        g.drawString("Reset", resetButton.x + (rw - fm.stringWidth("Reset")) / 2f, resetButton.y + (rh + fm.getAscent() - fm.getDescent()) / 2f);
        g.setColor(new Color(120, 205, 235, 70));
        g.fill(new java.awt.geom.Rectangle2D.Float(px + pad, py + pad + 30 * unit, pw - pad * 2, Math.max(1f, unit)));
        // The rows
        float top = py + pad + 38 * unit;
        Achievements.Achievement shown = null;
        for (int i = 0; i < all.length; i++) {
            Achievements.Achievement a = all[i];
            int col = i < leftCount ? 0 : 1, r = i < leftCount ? i : i - leftCount;
            float x = px + pad + col * column, y = top + r * row;
            achievementRows[i].setBounds(Math.round(x), Math.round(y), Math.round(column - 8 * unit), Math.round(row));
            boolean done = Achievements.isUnlocked(a), over = hovered == achievementRows[i];
            if (over) {
                shown = a;
                g.setColor(new Color(255, 255, 255, 18));
                g.fill(new java.awt.geom.RoundRectangle2D.Float(x - 4 * unit, y + 1, column - 4 * unit, row - 2, 8 * unit, 8 * unit));
            }
            // The tick box: gold and ticked once it's unlocked
            float box = 14 * unit, bx = x + 2 * unit, by = y + (row - box) / 2f;
            g.setStroke(new BasicStroke(1.4f * Math.max(1f, unit)));
            if (done) {
                g.setColor(HudStyle.GOLD);
                g.fill(new java.awt.geom.RoundRectangle2D.Float(bx, by, box, box, 4 * unit, 4 * unit));
                g.setColor(new Color(30, 24, 10));
                g.setStroke(new BasicStroke(2f * Math.max(1f, unit), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D tick = new Path2D.Float();
                tick.moveTo(bx + box * 0.22f, by + box * 0.52f);
                tick.lineTo(bx + box * 0.43f, by + box * 0.74f);
                tick.lineTo(bx + box * 0.8f, by + box * 0.28f);
                g.draw(tick);
            } else {
                g.setColor(new Color(255, 255, 255, 170));
                g.draw(new java.awt.geom.RoundRectangle2D.Float(bx, by, box, box, 4 * unit, 4 * unit));
            }
            g.setFont(HudStyle.font(done ? Font.BOLD : Font.PLAIN, 14 * unit));
            fm = g.getFontMetrics();
            g.setColor(done ? HudStyle.GOLD : Color.WHITE);
            g.drawString(a.title, bx + box + 10 * unit, y + (row + fm.getAscent() - fm.getDescent()) / 2f);
        }
        // What the one under the mouse is for
        String about = shown != null ? shown.description() + (Achievements.isUnlocked(shown) ? "  (unlocked)" : "") : "Point at one to see how it's earned";
        g.setFont(HudStyle.font(Font.PLAIN, 13 * unit));
        g.setColor(shown != null ? Color.WHITE : new Color(200, 206, 216, 170));
        g.drawString(about, px + pad, top + rows * row + 20 * unit);
    }

    /**
     * The player's profile at the top right (M58): their name on a button that opens it, with a
     * swatch of their suit's colour before it, and their rank below.
     */
    private void paintProfile(Graphics2D g, int w, float unit) {
        float bw = 380 * unit, bh = 56 * unit, margin = 40 * unit;
        profileButton.setBounds(Math.round(w - margin - bw), Math.round(margin), Math.round(bw), Math.round(bh));
        paintButton(g, profileButton, Profile.name(), "Edit profile", true, false, unit);
        float swatch = 26 * unit, sx = profileButton.x - swatch - 14 * unit, sy = profileButton.y + (bh - swatch) / 2f;
        g.setColor(Profile.suit().awt());
        g.fill(new java.awt.geom.Ellipse2D.Float(sx, sy, swatch, swatch));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f * Math.max(1f, unit)));
        g.draw(new java.awt.geom.Ellipse2D.Float(sx, sy, swatch, swatch));
        paintRank(g, profileButton.x, profileButton.y + bh + 12 * unit, bw, unit);
    }

    /**
     * The player's rank under their profile: their rating from ranked multiplayer (see Rating),
     * large in gold at the right, and how many ranked games they've won and lost.
     */
    private void paintRank(Graphics2D g, float x, float y, float width, float unit) {
        float h = 64 * unit, pad = 20 * unit;
        HudStyle.panel(g, x, y, width, h, HudStyle.GLASS);
        HudStyle.label(g, "Ranked rating", x + pad, y + 26 * unit, 12 * unit, HudStyle.ACCENT);
        int wins = Rating.wins(), losses = Rating.losses();
        String record = wins + losses == 0 ? "No ranked games yet" : wins + " won, " + losses + " lost";
        HudStyle.label(g, record, x + pad, y + 48 * unit, 12 * unit, HudStyle.LABEL);
        g.setFont(HudStyle.font(Font.BOLD, 30 * unit));
        FontMetrics fm = g.getFontMetrics();
        String rating = Integer.toString(Rating.rating());
        g.setColor(HudStyle.GOLD);
        g.drawString(rating, x + width - pad - fm.stringWidth(rating), y + (h + fm.getAscent() - fm.getDescent()) / 2f);
    }

    /** The question before Reset: are they sure, it can't be undone. */
    private void paintResetQuestion(Graphics2D g, int w, int h, float unit) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, w, h);
        float qw = 560 * unit, qh = 210 * unit, qx = (w - qw) / 2f, qy = (h - qh) / 2f;
        HudStyle.panel(g, qx, qy, qw, qh, HudStyle.GLASS_SOLID);
        g.setFont(HudStyle.font(Font.BOLD, 24 * unit));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(HudStyle.VALUE);
        String ask = "Reset all achievements?";
        g.drawString(ask, qx + (qw - fm.stringWidth(ask)) / 2f, qy + 58 * unit);
        g.setFont(HudStyle.font(Font.PLAIN, 17 * unit));
        fm = g.getFontMetrics();
        g.setColor(new Color(235, 140, 130));
        String warn = "Are you sure? This is an irreversible change.";
        g.drawString(warn, qx + (qw - fm.stringWidth(warn)) / 2f, qy + 98 * unit);
        float bw = 200 * unit, bh = 48 * unit, gap = 20 * unit, by = qy + qh - bh - 30 * unit;
        confirmReset.setBounds(Math.round(qx + qw / 2f - gap / 2f - bw), Math.round(by), Math.round(bw), Math.round(bh));
        cancelReset.setBounds(Math.round(qx + qw / 2f + gap / 2f), Math.round(by), Math.round(bw), Math.round(bh));
        paintButton(g, confirmReset, "Reset", null, true, false, unit);
        paintButton(g, cancelReset, "Cancel", null, true, true, unit);
    }

    /** The notice in the game's panel style, from x, its top at y, width wide. */
    private void paintNotice(Graphics2D g, float x, float y, float width, float unit) {
        float h = 72 * unit;
        HudStyle.panel(g, x, y, width, h, HudStyle.GLASS_SOLID);
        HudStyle.label(g, "Multiplayer", x + 24 * unit, y + 26 * unit, 12 * unit, HudStyle.ACCENT);
        g.setFont(HudStyle.font(Font.PLAIN, 17 * unit));
        g.setColor(HudStyle.VALUE);
        String text = notice;
        FontMetrics fm = g.getFontMetrics();
        while (text.length() > 4 && fm.stringWidth(text) > width - 48 * unit) text = text.substring(0, text.length() - 4) + "...";
        g.drawString(text, x + 24 * unit, y + 54 * unit);
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
     * The graphics settings on a panel of their own (the game's standard panel, see
     * HudStyle.panel) at (x, y), at least leastWidth wide: its heading, then the graphics and the
     * shadows, each a row of choices like radio buttons, set apart from the buttons beside it so
     * they belong to neither. Returns its height.
     */
    private float paintSettings(Graphics2D g, float x, float y, float leastWidth, float unit) {
        float pad = SETTINGS_PAD * unit, row = SETTINGS_ROW * unit, rowTitle = SETTINGS_ROW_TITLE * unit;
        float headingHeight = 40 * unit;
        String[] qualities = java.util.Arrays.stream(GameSettings.Quality.values()).map(q -> q.label).toArray(String[]::new);
        String[] shadows = { "Off", "On" };
        float width = Math.max(leastWidth, rowWidth(g, qualities, row, unit) + pad * 2);
        float height = pad + headingHeight + (rowTitle + row) * 2 + pad * 0.6f;
        HudStyle.panel(g, x, y, width, height, HudStyle.GLASS);
        HudStyle.label(g, "Graphics settings", x + pad, y + pad + 12 * unit, 15 * unit, HudStyle.VALUE);
        g.setColor(new Color(120, 205, 235, 70));
        g.fill(new java.awt.geom.Rectangle2D.Float(x + pad, y + pad + 24 * unit, width - pad * 2, Math.max(1f, unit)));
        float rowTop = y + pad + headingHeight + rowTitle;
        paintChoices(g, "Graphics", qualities, GameSettings.quality().ordinal(), qualityButtons, x + pad, rowTop, row, unit);
        paintChoices(g, "Shadows", shadows, GameSettings.shadows() ? 1 : 0, shadowButtons, x + pad, rowTop + row + rowTitle, row, unit);
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
        HudStyle.button(g, r, text, note, enabled, primary, hovered == r, unit);
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
