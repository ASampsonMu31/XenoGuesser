import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The diagnostics in the top right corner (see HudStyle.paintDiagnostics): the frame rate and the
 * PC's memory, kept on an overlay, the landing pod's or the game's. The memory's read only twice
 * a second, and the picture's repainted only when that or the frame rate has changed.
 */
final class DiagnosticsPanel {

    private static final String KEY = "diagnostics";
    private static final long MEMORY_READ_MILLIS = 500L;

    private MemoryBudget.Reading memory;
    private long memoryAt;
    private int shownFps = -1;

    /** Shows the panel on hud (wanted, and the setting on) with this frame rate, or hides it. */
    void show(HudOverlay hud, boolean wanted, int fps, float scale, boolean rescaled, int screenWidth) {
        if (!wanted || !GameSettings.showDiagnostics()) {
            hud.setVisible(KEY, false);
            shownFps = -1;
            return;
        }
        long now = System.currentTimeMillis();
        boolean read = now - memoryAt >= MEMORY_READ_MILLIS;
        if (read) {
            memoryAt = now;
            memory = MemoryBudget.reading();
        }
        int margin = Math.round(HudStyle.HUD_MARGIN * scale);
        if (rescaled || read || fps != shownFps || !hud.has(KEY)) {
            shownFps = fps;
            Graphics2D[] g = new Graphics2D[1];
            BufferedImage image = HudStyle.canvas(HudStyle.DIAGNOSTICS_W, HudStyle.DIAGNOSTICS_H, scale, g);
            HudStyle.paintDiagnostics(g[0], fps, memory);
            g[0].dispose();
            hud.put(KEY, image, 0, 0);
        }
        // (placed every frame: the screen's width can change under a picture that hasn't)
        hud.move(KEY, screenWidth - hud.width(KEY) - margin, margin);
        hud.setVisible(KEY, true);
    }
}
