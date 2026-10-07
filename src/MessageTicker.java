import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * A message from Xenocorp being played, its transcript scrolling along a strip in step with the
 * voice (as on the loading screen): the word being said at the middle, what's been said bright,
 * what's to come dim, the source's light blinking at the left.
 */
public final class MessageTicker {
    private final TransmissionMessage message;
    // Where each character of the transcript starts, in the font it's shown in
    private float[] charX;
    private Font font;
    private String laidOut;

    /** Starts the message playing. */
    public MessageTicker(TransmissionMessage message) {
        this.message = message;
        message.play();
    }

    /** Whether it's been said (or stopped). */
    public boolean isDone() {
        return message.isFinished();
    }

    public void stop() {
        message.stop();
    }

    /** The strip: w across, stripH tall, its top at y. */
    public void draw(Graphics2D target, int w, int y, int stripH) {
        Rectangle box = new Rectangle(0, y, w, stripH);
        Graphics2D g = (Graphics2D) target.create();
        HudStyle.smooth(g);
        g.clip(box);
        g.setColor(new Color(6, 9, 14, 215));
        g.fill(box);
        g.setColor(new Color(120, 205, 235, 120));
        g.fillRect(0, y, w, 1);
        String text = message.transcript();
        // (laid out again whenever the text changes: when what goes in its slot is settled)
        if (charX == null || font.getSize2D() != stripH * 0.42f || text != laidOut) {
            laidOut = text;
            font = HudStyle.font(Font.PLAIN, stripH * 0.42f);
            charX = new float[text.length() + 1];
            java.awt.font.FontRenderContext frc = g.getFontRenderContext();
            for (int i = 1; i <= text.length(); i++) charX[i] = (float) font.getStringBounds(text, 0, i, frc).getWidth();
        }
        float spoken = message.spokenCharacters();
        int whole = Math.min(text.length(), (int) spoken);
        float at = whole >= text.length() ? charX[text.length()]
                : charX[whole] + (charX[Math.min(text.length(), whole + 1)] - charX[whole]) * (spoken - whole);
        float anchor = w * 0.5f, x = anchor - at, baseline = y + stripH * 0.64f;
        // What goes in the slot is about to scroll into view: settled now, said or not
        int slotAt = message.nameChar();
        if (!message.nameSettled() && slotAt >= 0 && x + charX[Math.min(slotAt, text.length())] < w + stripH * 4f) message.settleName();
        g.setFont(font);
        g.setColor(new Color(150, 165, 180, 140));
        g.drawString(text, x, baseline);
        Graphics2D said = (Graphics2D) g.create();
        said.clipRect(0, y, Math.round(anchor), stripH);
        said.setColor(new Color(235, 245, 255));
        said.drawString(text, x, baseline);
        said.dispose();
        int dot = Math.round(stripH * 0.22f);
        float labelX = stripH * 0.4f + dot + 10;
        int labelW = Math.round(labelX + HudStyle.labelWidth(g, "Xenocorp transmission", stripH * 0.25f) + stripH * 1.6f);
        g.setPaint(new java.awt.GradientPaint(labelW - stripH * 1.4f, 0, new Color(6, 9, 14, 255), labelW, 0, new Color(6, 9, 14, 0)));
        g.fillRect(0, y + 1, labelW, stripH);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        g.setColor(blink ? new Color(245, 90, 70) : new Color(110, 40, 35));
        g.fillOval(Math.round(stripH * 0.4f), y + (stripH - dot) / 2, dot, dot);
        HudStyle.label(g, "Xenocorp transmission", labelX, y + stripH * 0.6f, stripH * 0.25f, HudStyle.LABEL);
        g.dispose();
    }
}
