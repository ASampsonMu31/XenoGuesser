import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The shop, between a round's results and the next round: up to three cards, each something
 * to buy (a map for the map's list, or an item to carry), shaped like playing cards and drawn
 * as the HUD's glass panels. The player picks one and buys it with their score, or buys
 * nothing; either way the next round follows, once it's ready.
 */
public class ShopPanel extends JPanel {

    // How many cards each item (the compass, the thermometer) has in the shop's deck, to make
    // items likelier to come up than any one map; only one of them is dealt at a time, and once
    // the item's bought none are
    public static final int ITEM_COPIES = 2;

    /**
     * Something on offer: its name, what sort of thing it is, its picture (an image, or else a
     * painter for a drawn icon), what it does, what it costs and what buying it does.
     */
    public record Card(String name, String kind, Image picture, HudStyle.IconPainter painter, String description, int price, Runnable onBuy) {
    }

    // What things cost: each card's price is drawn at random from a normal distribution round a
    // mean (to the nearest $5, never below the lowest). The mean starts at FIRST_MEAN after the
    // first round and goes up by MEAN_PER_ROUND each round after. The spread is set so that
    // WITHIN_SHARE of prices fall within WITHIN_FRACTION of the mean either side (90% within
    // 30%: a standard deviation of 0.3 / 1.645 of the mean), so it grows with the mean
    private static final float FIRST_MEAN = 100f, MEAN_PER_ROUND = 50f;
    private static final float WITHIN_FRACTION = 0.3f;
    // (1.645 standard deviations either side of the mean hold 90% of a normal distribution)
    private static final float WITHIN_SHARE_DEVIATIONS = 1.645f;
    private static final int LOWEST_PRICE = 10;
    // Items cost this many times what maps do (their mean and spread both), every round
    public static final float ITEM_PRICE_FACTOR = 1.5f;
    // ...and plant and animal heatmaps this many times what other heatmaps do
    public static final float SPECIES_PRICE_FACTOR = 0.5f;

    private static final Color BUY = new Color(40, 150, 90);
    private static final Color DISABLED = new Color(60, 66, 76);
    // A playing card's shape, width to height
    private static final float CARD_ASPECT = 5f / 7f;

    private final GameHUD hud;
    private List<Card> cards = List.of();
    private int selected = -1, hovered = -1;
    private Runnable onLeave;
    // Once a choice is made: waiting for the next round, with a spinner
    private boolean leaving;
    private long dealtAt, leftAt;
    private final Timer animation = new Timer(16, e -> repaint());
    // A message from Xenocorp scrolling along the bottom as it's spoken (null for none)
    private MessageTicker message;

    // Where things are, as last laid out
    private final List<Rectangle> cardBounds = new ArrayList<>();
    private final Rectangle buyButton = new Rectangle(), skipButton = new Rectangle();

    public ShopPanel(GameHUD hud) {
        this.hud = hud;
        setOpaque(true);
        setLayout(null);
        setVisible(false);
        setFocusable(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int over = cardAt(e.getX(), e.getY());
                if (over != hovered) {
                    hovered = over;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = -1;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (leaving || e.getButton() != MouseEvent.BUTTON1) return;
                int card = cardAt(e.getX(), e.getY());
                if (card >= 0) {
                    selected = selected == card ? -1 : card;
                    repaint();
                } else if (buyButton.contains(e.getPoint()) && canBuy()) {
                    buySelected();
                } else if (skipButton.contains(e.getPoint())) {
                    buyNothing();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Opens the shop with these cards; onLeave is told once the player has chosen (bought or not). */
    public void open(List<Card> offers, Runnable onLeave) {
        this.cards = List.copyOf(offers);
        this.onLeave = onLeave;
        this.selected = -1;
        this.hovered = -1;
        this.leaving = false;
        this.dealtAt = System.currentTimeMillis();
        animation.start();
        setVisible(true);
        repaint();
    }

    /** Plays a message from Xenocorp while the shop is open, its words scrolling along the bottom as they're said. */
    public void playMessage(TransmissionMessage message) {
        if (!isOpen() || leaving || message == null) return;
        this.message = new MessageTicker(message);
        animation.start();
    }

    private void stopMessage() {
        if (message != null) message.stop();
        message = null;
    }

    /** Puts the shop away (the next round has begun). */
    public void close() {
        stopMessage();
        animation.stop();
        setVisible(false);
        cards = List.of();
        leaving = false;
    }

    public boolean isOpen() {
        return isVisible();
    }

    private boolean canBuy() {
        return !leaving && selected >= 0 && hud.getScore() >= cards.get(selected).price();
    }

    private void buySelected() {
        Card card = cards.get(selected);
        hud.spend(card.price());
        card.onBuy().run();
        leave();
    }

    /** Leaves without buying anything. */
    public void buyNothing() {
        if (leaving) return;
        selected = -1;
        leave();
    }

    private void leave() {
        stopMessage();
        leaving = true;
        leftAt = System.currentTimeMillis();
        repaint();
        if (onLeave != null) onLeave.run();
    }

    private int cardAt(int x, int y) {
        for (int i = cardBounds.size() - 1; i >= 0; i--) {
            if (cardBounds.get(i).contains(x, y)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            HudStyle.smooth(g);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            int w = getWidth(), h = getHeight();

            // A dark blue-black backdrop, a little lighter in the middle
            g.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.5f, h * 0.45f), Math.max(w, h) * 0.75f,
                    new float[] { 0f, 1f }, new Color[] { new Color(26, 34, 48), new Color(6, 9, 14) }));
            g.fillRect(0, 0, w, h);

            // The cards' size: as big as fits three across, with the heading and buttons round them
            int count = cards.size();
            int cardH = Math.round(Math.min(h * 0.58f, 540f));
            int gap = Math.max(24, Math.round(cardH * 0.12f));
            if (count > 0) cardH = Math.min(cardH, Math.round((w - 80 - gap * (count - 1)) / (float) count / CARD_ASPECT));
            int cardW = Math.round(cardH * CARD_ASPECT);
            int rowW = count * cardW + Math.max(0, count - 1) * gap;
            int top = Math.round(h * 0.17f);
            int rowX = (w - rowW) / 2;

            // The heading
            g.setFont(HudStyle.font(Font.BOLD, Math.min(46f, h * 0.052f)).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.12f)));
            FontMetrics fm = g.getFontMetrics();
            String title = count == 0 ? "SHOP  ·  SOLD OUT" : "SHOP";
            int titleY = Math.round(h * 0.10f);
            g.setColor(HudStyle.VALUE);
            g.drawString(title, (w - fm.stringWidth(title)) / 2, titleY);
            g.setColor(HudStyle.ACCENT);
            g.fill(new java.awt.geom.Rectangle2D.Float(w / 2f - 40, titleY + 12, 80, 2.5f));

            // The cards, dealt in from below one after another
            cardBounds.clear();
            long now = System.currentTimeMillis();
            boolean moving = leaving;
            for (int i = 0; i < count; i++) {
                float t = Math.max(0f, Math.min(1f, (now - dealtAt - i * 110L) / 480f));
                if (t < 1f) moving = true;
                float ease = 1f - (1f - t) * (1f - t) * (1f - t);
                int lift = i == selected ? Math.round(cardH * 0.05f) : i == hovered && !leaving ? Math.round(cardH * 0.018f) : 0;
                int x = rowX + i * (cardW + gap);
                int y = top - lift + Math.round((1f - ease) * h * 0.35f);
                cardBounds.add(new Rectangle(x, y, cardW, cardH));
                java.awt.Composite before = g.getComposite();
                // (once the choice is made, the cards not taken fade back)
                float alpha = ease * (leaving && i != selected ? 0.35f : 1f);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                drawCard(g, cards.get(i), x, y, cardW, cardH, i == selected, i == hovered && !leaving,
                        hud.getScore() >= cards.get(i).price());
                g.setComposite(before);
            }

            // The buttons, or once chosen, the wait for the next round
            int buttonsY = top + cardH + Math.max(28, Math.round(h * 0.05f));
            int bh = Math.max(40, Math.round(h * 0.052f)), bw = Math.max(200, Math.round(cardW * 0.9f));
            if (leaving) {
                buyButton.setBounds(0, 0, 0, 0);
                skipButton.setBounds(0, 0, 0, 0);
                int lw = Math.round(bw * 1.3f);
                drawLoading(g, (w - lw) / 2, buttonsY, lw, bh, now);
            } else if (count == 0) {
                buyButton.setBounds(0, 0, 0, 0);
                skipButton.setBounds((w - bw) / 2, buttonsY, bw, bh);
                drawButton(g, skipButton, "Continue", HudStyle.GLASS_SOLID, true);
            } else {
                int buttonGap = 24;
                buyButton.setBounds(w / 2 - buttonGap / 2 - bw, buttonsY, bw, bh);
                skipButton.setBounds(w / 2 + buttonGap / 2, buttonsY, bw, bh);
                String buyText = selected < 0 ? "Buy" : cards.get(selected).price() == 0 ? "Take it" : String.format("Buy for $%,d", cards.get(selected).price());
                drawButton(g, buyButton, buyText, canBuy() ? BUY : DISABLED, canBuy());
                // (in the shop where everything's free, nothing's bought)
                boolean free = cards.stream().allMatch(c -> c.price() == 0);
                drawButton(g, skipButton, free ? "Take nothing" : "Buy nothing", HudStyle.GLASS_SOLID, true);
                if (selected >= 0 && !canBuy()) {
                    String note = "Not enough money";
                    float noteW = HudStyle.labelWidth(g, note, 11f);
                    HudStyle.label(g, note, buyButton.x + (bw - noteW) / 2f, buttonsY + bh + 22, 11f, HudStyle.GOLD);
                }
            }
            // The message, until it's been said (or the shop's left)
            if (message != null && message.isDone()) message = null;
            if (message != null) {
                int stripH = Math.max(44, h / 20);
                message.draw(g, w, h - stripH, stripH);
                moving = true;
            }
            if (!moving && !leaving) animation.stop();
            else if (!animation.isRunning()) animation.start();
        } finally {
            g.dispose();
        }
    }

    /**
     * A card, as the HUD's panels are drawn: dark glass with cut corners, a cyan edge and an
     * accent bar; the name at the top, the picture enlarged in its slot, what it does beneath
     * and the price at the foot. Chosen, it's edged and glowing in the accent colour.
     */
    private static void drawCard(Graphics2D g, Card card, int x, int y, int w, int h, boolean chosen, boolean hovered, boolean affordable) {
        Graphics2D c = (Graphics2D) g.create();
        try {
            c.translate(x, y);
            float cut = w * 0.09f;
            Path2D shape = HudStyle.chamfered(0.5f, 0.5f, w - 1f, h - 1f, cut);
            // A soft shadow, then the glow when chosen
            for (int k = 6; k >= 1; k--) {
                c.setColor(new Color(0, 0, 0, 18));
                c.setStroke(new BasicStroke(k * 3f));
                c.translate(0, 6);
                c.draw(shape);
                c.translate(0, -6);
            }
            if (chosen) {
                for (int k = 5; k >= 1; k--) {
                    c.setColor(new Color(HudStyle.ACCENT.getRed(), HudStyle.ACCENT.getGreen(), HudStyle.ACCENT.getBlue(), 20 + (5 - k) * 10));
                    c.setStroke(new BasicStroke(k * 2.6f));
                    c.draw(shape);
                }
            }
            c.setColor(HudStyle.GLASS_SOLID);
            c.fill(shape);
            c.setPaint(new java.awt.GradientPaint(0, 0, new Color(255, 255, 255, hovered || chosen ? 30 : 20), 0, h * 0.5f, new Color(255, 255, 255, 0)));
            c.fill(shape);
            c.setColor(chosen ? HudStyle.ACCENT : HudStyle.EDGE);
            c.setStroke(new BasicStroke(chosen ? 2.2f : 1.2f));
            c.draw(shape);
            c.setColor(HudStyle.ACCENT);
            c.fill(new java.awt.geom.Rectangle2D.Float(4f, cut + 6f, 3f, h - cut * 2f - 12f));

            float pad = w * 0.1f;
            // What sort of thing it is, small and spaced out, then the name, as big as fits
            float kindSize = Math.max(9f, h * 0.024f);
            float kindY = h * 0.085f + kindSize;
            String kind = card.kind();
            HudStyle.label(c, kind, (w - HudStyle.labelWidth(c, kind, kindSize)) / 2f, kindY, kindSize, HudStyle.LABEL);
            float nameSize = h * 0.06f;
            while (nameSize > 10f && c.getFontMetrics(HudStyle.font(Font.BOLD, nameSize)).stringWidth(card.name()) > w - pad * 2) nameSize -= 1f;
            c.setFont(HudStyle.font(Font.BOLD, nameSize));
            FontMetrics fm = c.getFontMetrics();
            float nameY = kindY + h * 0.012f + fm.getAscent();
            c.setColor(HudStyle.VALUE);
            c.drawString(card.name(), (w - fm.stringWidth(card.name())) / 2f, nameY);
            // A divider beneath
            float ruleY = nameY + h * 0.03f;
            c.setColor(new Color(120, 205, 235, 60));
            c.fill(new java.awt.geom.Rectangle2D.Float(pad, ruleY, w - pad * 2, 1f));

            // The picture, enlarged, in a slot like the inventory's
            float slot = w * 0.6f, sx = (w - slot) / 2f, sy = ruleY + h * 0.035f;
            RoundRectangle2D slotShape = new RoundRectangle2D.Float(sx, sy, slot, slot, slot * 0.1f, slot * 0.1f);
            c.setColor(new Color(255, 255, 255, 14));
            c.fill(slotShape);
            c.setColor(new Color(120, 205, 235, 90));
            c.setStroke(new BasicStroke(1f));
            c.draw(slotShape);
            float pictureSize = slot * 0.8f, cx = sx + slot / 2f, cy = sy + slot / 2f;
            if (card.picture() != null) {
                Image picture = card.picture();
                int iw = picture.getWidth(null), ih = picture.getHeight(null);
                float fit = iw > 0 && ih > 0 ? pictureSize / Math.max(iw, ih) : 1f;
                float dw = iw * fit, dh = ih * fit;
                c.drawImage(picture, Math.round(cx - dw / 2), Math.round(cy - dh / 2), Math.round(dw), Math.round(dh), null);
            } else if (card.painter() != null) {
                card.painter().paint(c, cx - pictureSize / 2, cy - pictureSize / 2, pictureSize);
            }

            // The price at the foot, in gold (dimmed if it can't be afforded)
            String price = card.price() == 0 ? "FREE" : String.format("$%,d", card.price());
            c.setFont(HudStyle.font(Font.BOLD, h * 0.055f));
            fm = c.getFontMetrics();
            float priceY = h - h * 0.06f;
            c.setColor(affordable ? HudStyle.GOLD : HudStyle.DIM);
            c.drawString(price, (w - fm.stringWidth(price)) / 2f, priceY);

            // What it does, wrapped to fit between the picture and the price
            float descTop = sy + slot + h * 0.035f, descBottom = priceY - fm.getAscent() - h * 0.025f;
            float fontSize = h * 0.033f;
            List<String> lines;
            do {
                c.setFont(HudStyle.font(Font.PLAIN, fontSize));
                lines = wrap(c.getFontMetrics(), card.description(), w - pad * 2);
                fontSize *= 0.93f;
            } while (lines.size() * c.getFontMetrics().getHeight() > descBottom - descTop && fontSize > 6f);
            fm = c.getFontMetrics();
            float lineY = descTop + fm.getAscent();
            c.setColor(new Color(200, 212, 224));
            for (String line : lines) {
                c.drawString(line, (w - fm.stringWidth(line)) / 2f, lineY);
                lineY += fm.getHeight();
            }
        } finally {
            c.dispose();
        }
    }

    /** A button as a HUD panel, its text in spaced-out capitals. */
    private void drawButton(Graphics2D g, Rectangle b, String text, Color body, boolean enabled) {
        HudStyle.panel(g, b.x, b.y, b.width, b.height, body);
        float size = Math.max(12f, b.height * 0.32f);
        float textW = HudStyle.labelWidth(g, text, size);
        HudStyle.label(g, text, b.x + (b.width - textW) / 2f, b.y + b.height / 2f + size * 0.38f, size,
                enabled ? HudStyle.VALUE : HudStyle.DIM);
    }

    /** A panel saying the next round is loading, with a half-circle turning round in it. */
    private void drawLoading(Graphics2D g, int x, int y, int w, int h, long now) {
        HudStyle.panel(g, x, y, w, h, HudStyle.GLASS_SOLID);
        String text = "Loading the next round";
        float size = Math.max(12f, h * 0.32f);
        float textW = HudStyle.labelWidth(g, text, size);
        int spinner = Math.round(h * 0.45f), spinnerGap = 12;
        float textX = x + (w - textW + spinner + spinnerGap) / 2f;
        HudStyle.label(g, text, textX, y + h / 2f + size * 0.38f, size, HudStyle.VALUE);
        int angle = (int) (((now - leftAt) * 0.4) % 360);
        g.setColor(HudStyle.ACCENT);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(Math.round(textX) - spinnerGap - spinner, y + (h - spinner) / 2, spinner, spinner, -angle, 180);
    }

    private static List<String> wrap(FontMetrics fm, String text, float width) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : text.split("\\s+")) {
            String tried = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(tried) > width && !line.isEmpty()) {
                lines.add(line);
                line = word;
            } else {
                line = tried;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    // ------------------------------------------------------------------ the cards' text and prices

    /**
     * A card's price after this round (counting from 1), drawn at random round the round's mean
     * (see FIRST_MEAN), the mean and spread scaled by factor (1 for maps, ITEM_PRICE_FACTOR for items, SPECIES_PRICE_FACTOR for plant and animal heatmaps).
     */
    public static int price(int round, float factor, java.util.Random rand) {
        // (the shop before the first round gives everything away)
        if (round < 1) return 0;
        int rounds = Math.max(0, round - 1);
        float mean = factor * (FIRST_MEAN + MEAN_PER_ROUND * rounds), spread = mean * WITHIN_FRACTION / WITHIN_SHARE_DEVIATIONS;
        float price = mean + (float) rand.nextGaussian() * spread;
        return Math.max(LOWEST_PRICE, 5 * Math.round(price / 5f));
    }

    /**
     * The cards' descriptions by name, from assets/text/shop_descriptions.txt ("Name =
     * description" lines, # for comments; read afresh each time, so it can be edited while the
     * game runs).
     */
    public static java.util.Map<String, String> loadDescriptions() {
        java.util.Map<String, String> found = new java.util.HashMap<>();
        java.io.File file = new java.io.File(GamePaths.HOME + "assets/text/shop_descriptions.txt");
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) continue;
                found.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        } catch (java.io.IOException e) {
            System.err.println("Shop descriptions unavailable: " + e.getMessage());
        }
        return found;
    }
}
