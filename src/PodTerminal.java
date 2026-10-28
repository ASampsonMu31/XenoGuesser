import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * What the old computer at the back of the pod shows as the world loads: green phosphor text
 * with the steps done so far, the one under way, a progress bar (in multiplayer one for each
 * player) and the time left, and a line for each stage of the pod's way down. Drawn again only
 * when something on it changes (see picture).
 */
public class PodTerminal {

    private static final Color PHOSPHOR = new Color(110, 255, 150);
    private static final Color PHOSPHOR_DIM = new Color(60, 170, 90);
    private static final Color PHOSPHOR_FAIL = new Color(255, 190, 80);
    private static final int COLS = 30, ROWS = 11;

    private final long worldSeed;
    private final Multiplayer.Role role;
    private final List<String> finishedSteps = new ArrayList<>();
    private String lastLabel;
    private float smoothed, partnerSmoothed;
    private String key = "";
    private BufferedImage picture;

    /** role is this PC's player in multiplayer, null in singleplayer. */
    public PodTerminal(long worldSeed, Multiplayer.Role role) {
        this.worldSeed = worldSeed;
        this.role = role;
        // The link to the partner's PC is already made; the loading stages follow it
        if (role != null) finishedSteps.add("Partner pod linked: " + name(role.other()));
    }

    /**
     * The screen as it should be now, or null if it's as it was last time: partner is the
     * partner's share loaded (multiplayer), status a line for the pod's way down (null for none).
     */
    public BufferedImage picture(LoadingProgress progress, float partner, String status) {
        String label = progress.currentLabel();
        float target = progress.overallFraction();
        float secondsLeft = progress.estimatedSecondsLeft();
        boolean failed = label.startsWith("Loading failed");
        if (lastLabel != null && !lastLabel.equals(label) && !failed) finishedSteps.add(lastLabel);
        lastLabel = label;
        smoothed += (target - smoothed) * 0.18f;
        partnerSmoothed += (partner - partnerSmoothed) * 0.18f;
        int percent = Math.round(smoothed * 100), partnerPercent = Math.round(partnerSmoothed * 100);
        boolean blink = (System.currentTimeMillis() / 450) % 2 == 0;
        String now = label + "|" + finishedSteps.size() + "|" + percent + "|" + partnerPercent + "|" + Math.round(secondsLeft) + "|" + blink + "|" + status;
        if (now.equals(key) && picture != null) return null;
        key = now;
        picture = render(label, failed, percent, partnerPercent, secondsLeft, blink, status);
        return picture;
    }

    private BufferedImage render(String label, boolean failed, int percent, int partnerPercent, float secondsLeft, boolean blink, String status) {
        // Drawn small, so the text has the chunky look of an old screen when it's shown large
        BufferedImage image = new BufferedImage(COLS * 9, ROWS * 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D t = image.createGraphics();
        t.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        t.setColor(new Color(4, 20, 9));
        t.fillRect(0, 0, image.getWidth(), image.getHeight());
        t.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        Color text = failed ? PHOSPHOR_FAIL : PHOSPHOR;

        List<String> lines = new ArrayList<>();
        if (role != null) {
            lines.add("XENO-NAV 2.7   MULTIPLAYER");
            lines.add(fit("LINK " + name(Multiplayer.Role.HOST) + " <> " + name(Multiplayer.Role.JOIN), COLS));
        } else {
            lines.add("XENO-NAV 2.7   PLANETFALL");
            lines.add("WORLD SEED " + worldSeed);
        }
        lines.add("");
        // (fewer steps shown in multiplayer, to leave room for the second bar)
        int keep = role != null ? 2 : 3;
        if (status != null) keep--;
        int shown = Math.max(0, finishedSteps.size() - keep);
        for (int i = shown; i < finishedSteps.size(); i++) {
            lines.add(fit("> " + finishedSteps.get(i).toUpperCase(), COLS - 4) + " OK");
        }
        lines.add(fit("> " + label.toUpperCase(), COLS - 2) + (blink && !failed ? "_" : ""));
        if (status != null) lines.add(fit("* " + status.toUpperCase(), COLS));
        int bottom = role != null ? 3 : 2;
        while (lines.size() < ROWS - bottom) lines.add("");
        if (role != null) {
            // This player's bar and the partner's, each by the first letters of their name
            Multiplayer.Role a = Multiplayer.Role.HOST;
            int aPercent = role == a ? percent : partnerPercent, bPercent = role == a ? partnerPercent : percent;
            lines.add(bar(shortName(a), aPercent, COLS - 13));
            lines.add(bar(shortName(a.other()), bPercent, COLS - 13));
        } else {
            lines.add(bar(null, percent, COLS - 8));
        }
        lines.add(failed ? "SYSTEM HALTED" : secondsLeft >= 1f ? String.format("ETA %d S", Math.round(secondsLeft)) : "STAND BY");

        for (int i = 0; i < lines.size() && i < ROWS; i++) {
            // A soft glow behind each line, then the line itself
            t.setColor(new Color(text.getRed(), text.getGreen(), text.getBlue(), 50));
            t.drawString(lines.get(i), 5, 15 + i * 16 + 1);
            t.drawString(lines.get(i), 7, 15 + i * 16 - 1);
            t.setColor(i < 2 ? PHOSPHOR_DIM : text);
            t.drawString(lines.get(i), 6, 15 + i * 16);
        }
        // Scanlines, and a faint glare across the curved glass
        t.setColor(new Color(0, 0, 0, 70));
        for (int y = 0; y < image.getHeight(); y += 3) t.drawLine(0, y, image.getWidth(), y);
        t.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 34), image.getWidth() * 0.6f, image.getHeight() * 0.6f, new Color(255, 255, 255, 0)));
        t.fillRect(0, 0, image.getWidth(), image.getHeight());
        t.dispose();
        return image;
    }

    /** "[#####.....]  54%", after a label if there is one. */
    private static String bar(String label, int percent, int cells) {
        int filled = Math.round(percent / 100f * cells);
        StringBuilder bar = new StringBuilder(label == null ? "" : label + " ");
        bar.append('[');
        for (int i = 0; i < cells; i++) bar.append(i < filled ? '#' : '.');
        bar.append("] ").append(String.format("%3d%%", percent));
        return bar.toString();
    }

    /** A player's name (M58), upper case as the terminal writes everything. */
    private static String name(Multiplayer.Role player) {
        Multiplayer session = Multiplayer.active();
        return (session != null ? session.name(player) : player == Multiplayer.Role.HOST ? "Host" : "Partner").toUpperCase();
    }

    /** A player's name cut or padded to five letters, so both bars line up. */
    private static String shortName(Multiplayer.Role player) {
        return String.format("%-5s", fit(name(player), 5));
    }

    private static String fit(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "~";
    }
}
