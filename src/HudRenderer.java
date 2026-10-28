import java.util.List;
import java.awt.image.BufferedImage;
import com.jogamp.opengl.*;
import java.awt.Graphics2D;

/**
 * The HUD drawn over the 3D view each frame: score, clock, items, stamina, the map's size and
 * achievements' toasts, each picture redrawn only when what it shows changes.
 */
final class HudRenderer extends GamePart {

    HudRenderer(XenoGuesser_GLEventListener game) {
        super(game);
    }

    // The HUD drawn over the 3D view, and what each picture last showed
    final HudOverlay hud = new HudOverlay();

    private final DiagnosticsPanel diagnostics = new DiagnosticsPanel();
    private int hudScoreVersion = -1, hudItemsVersion = -1, hudHeldItem = -2, hudStaminaStep = -1;

    private long hudClockTenth = -1L;

    private Achievements.Achievement hudToast;

    // How far the inventory stands from the window's left edge and from the message strip below it
    private static final int INVENTORY_GAP = 4;

    // Under this long left on the round's clock, the screen's edges pulse red (M45)
    private static final long LOW_CLOCK_MS = 5000L;

    private float hudScale = -1f;

    private MapPanel.MapSize hudMapSize;

    /**
     * The HUD over the finished frame: the round and score at the top left, the diagnostics
     * (frame rate and memory) in the top right corner, the inventory at the bottom left and the map's keys above the map.
     * Each picture is repainted only when what it shows changes.
     */
    void drawHud(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        // Pictures are painted at the screen's pixel density so they stay sharp when Windows scales the display
        float scale = drawable instanceof java.awt.Component c && c.getWidth() > 0 ? game.screenWidth / (float) c.getWidth() : 1f;
        boolean rescaled = scale != hudScale;
        hudScale = scale;
        int margin = Math.round(HudStyle.HUD_MARGIN * scale);
        java.awt.Graphics2D[] g = new java.awt.Graphics2D[1];

        if (game.gameHUD != null && (rescaled || game.gameHUD.getVersion() != hudScoreVersion)) {
            hudScoreVersion = game.gameHUD.getVersion();
            BufferedImage image = HudStyle.canvas(HudStyle.SCORE_W, HudStyle.SCORE_H, scale, g);
            game.gameHUD.paintPanel(g[0], true);
            g[0].dispose();
            hud.put("score", image, margin, margin);
        }

        // The diagnostics: the frame rate, and the PC's memory as it's shared out
        int fps = game.gameHUD != null ? game.gameHUD.getGameFps() : 0;
        diagnostics.show(hud, game.gameHUD != null, fps, scale, rescaled, game.screenWidth);

        // The sprint's stamina, in the top right (under the diagnostics, when they're shown), wherever the player can walk
        boolean staminaShown = GameMode.current().moving;
        if (staminaShown) {
            int step = Math.round(game.playerMotion.stamina * 200f) * 2 + (game.playerMotion.winded ? 1 : 0);
            if (rescaled || step != hudStaminaStep || !hud.has("stamina")) {
                hudStaminaStep = step;
                BufferedImage image = HudStyle.canvas(HudStyle.STAMINA_W, HudStyle.STAMINA_H, scale, g);
                HudStyle.paintStamina(g[0], game.playerMotion.stamina, game.playerMotion.winded);
                g[0].dispose();
                hud.put("stamina", image, 0, 0);
            }
            int below = GameSettings.showDiagnostics() ? margin + Math.round((HudStyle.DIAGNOSTICS_H + 6) * scale) : margin;
            hud.move("stamina", game.screenWidth - hud.width("stamina") - margin, below);
        }
        hud.setVisible("stamina", staminaShown);

        // The round's clock (M41), at the top in the middle, while it's running
        long[] clock = game.minimap != null ? game.minimap.guessClock() : null;
        if (clock != null) {
            long tenth = clock[0] / 100L;
            String note = clock[2] == 1L && Multiplayer.active() != null
                    ? Multiplayer.active().remoteName() + " guessed!" : "Time to guess";
            if (rescaled || tenth != hudClockTenth || !hud.has("clock")) {
                hudClockTenth = tenth;
                BufferedImage image = HudStyle.canvas(HudStyle.TIMER_W, HudStyle.TIMER_H, scale, g);
                HudStyle.paintTimer(g[0], clock[0], clock[1], note);
                g[0].dispose();
                hud.put("clock", image, (game.screenWidth - Math.round(HudStyle.TIMER_W * scale)) / 2, margin);
            }
        }
        hud.setVisible("clock", clock != null);
        // Under 5 s left (M45), the screen's edges pulse red, about once a second and stronger
        // as it runs out
        if (clock != null && clock[0] > 0L && clock[0] < LOW_CLOCK_MS) {
            float urgency = 1f - clock[0] / (float) LOW_CLOCK_MS;
            float beat = 0.5f + 0.5f * (float) Math.cos(System.nanoTime() / 1e9 * Math.PI * 2.0 * 1.25);
            hud.setEdgeGlow(Math.min(0.9f, (0.6f + 0.3f * urgency) * (0.3f + 0.7f * beat)));
        } else {
            hud.setEdgeGlow(0f);
        }

        // The inventory: only what the player has bought, and nothing at all until they've bought
        // something, the item in hand lit up; and above it, how to take items out and switch them
        java.util.List<HudStyle.Item> items = game.inventoryItems();
        int held = game.heldItem();
        if (rescaled || game.itemsVersion != hudItemsVersion || held != hudHeldItem || !hud.has("inventory")) {
            hudItemsVersion = game.itemsVersion;
            hudHeldItem = held;
            int h = HudStyle.inventoryHeight(Math.max(1, items.size()));
            BufferedImage image = HudStyle.canvas(HudStyle.INVENTORY_W, h, scale, g);
            HudStyle.paintInventory(g[0], items, held);
            g[0].dispose();
            hud.put("inventory", image, 0, 0);
            String[] keys = held < 0 ? new String[] { "Middle mouse", "Use gadgets" }
                    : new String[] { "Scroll", "Switch", "Middle mouse", "Put away" };
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D pg = probe.createGraphics();
            HudStyle.smooth(pg);
            int w = HudStyle.hintWidth(pg, keys);
            pg.dispose();
            BufferedImage hint = HudStyle.canvas(w, HudStyle.HINT_H, scale, g);
            HudStyle.paintHint(g[0], w, keys);
            g[0].dispose();
            hud.put("inventoryHint", hint, 0, 0);
        }
        hud.setVisible("inventory", !items.isEmpty());
        if (hud.has("inventoryHint")) hud.setVisible("inventoryHint", !items.isEmpty() && !GameClock.isPaused());
        // (kept at the bottom however the window is resized, clear of the message strip along it)
        if (hud.has("inventory")) {
            int h = Math.round(HudStyle.inventoryHeight(Math.max(1, items.size())) * scale);
            java.awt.Component view = drawable instanceof java.awt.Component c ? c : null;
            int strip = view == null ? 0 : Math.round(XenoGuesser.messageStripHeight(view.getHeight() + 2) * scale);
            // (tucked into the corner, just clear of the window's edge and the strip)
            int gap = Math.round(INVENTORY_GAP * scale);
            int top = game.screenHeight - h - gap - strip;
            hud.move("inventory", gap, top);
            hud.move("inventoryHint", gap, top - Math.round((HudStyle.HINT_H + 6) * scale));
        }

        if (game.minimap != null) {
            MapPanel.MapSize size = game.minimap.getMapSize();
            if (rescaled || size != hudMapSize) {
                hudMapSize = size;
                String[] keys = switch (size) {
                    case SMALL -> new String[] { "M", "Expand map" };
                    case LARGE -> new String[] { "M", "Contract map", "", "Scroll to zoom", "", "Click the map to place your marker" };
                };
                BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D pg = probe.createGraphics();
                HudStyle.smooth(pg);
                int w = HudStyle.hintWidth(pg, keys);
                pg.dispose();
                BufferedImage image = HudStyle.canvas(w, HudStyle.HINT_H, scale, g);
                HudStyle.paintHint(g[0], w, keys);
                g[0].dispose();
                hud.put("mapHint", image, 0, 0);
            }
            // Just above the map, lined up with its right-hand edge
            java.awt.Rectangle map = game.minimap.getBounds();
            java.awt.Component view = drawable instanceof java.awt.Component c ? c : null;
            if (view != null) map.translate(-view.getX(), -view.getY());
            hud.move("mapHint", Math.round((map.x + map.width - 12) * scale) - hud.width("mapHint"),
                    Math.round((map.y - 8) * scale) - Math.round(HudStyle.HINT_H * scale));
        }

        game.identificationLens.drawLensFinds(scale);

        // An achievement just unlocked (M53): at the bottom right, just above the map
        Object[] toast = Achievements.toast();
        if (toast != null && game.minimap != null) {
            Achievements.Achievement a = (Achievements.Achievement) toast[0];
            if (rescaled || a != hudToast || !hud.has("achievement")) {
                hudToast = a;
                BufferedImage image = HudStyle.canvas(Achievements.TOAST_W, Achievements.TOAST_H, scale, g);
                Achievements.paintToast(g[0], a);
                g[0].dispose();
                hud.put("achievement", image, 0, 0);
            }
            java.awt.Rectangle map = game.minimap.getBounds();
            java.awt.Component view = drawable instanceof java.awt.Component c ? c : null;
            if (view != null) map.translate(-view.getX(), -view.getY());
            hud.move("achievement", game.screenWidth - hud.width("achievement") - margin,
                    Math.round((map.y - 16) * scale) - Math.round((Achievements.TOAST_H + HudStyle.HINT_H + 8) * scale));
            hud.setAlpha("achievement", (Float) toast[1]);
        }
        if (hud.has("achievement")) hud.setVisible("achievement", toast != null);
        // (the map's hint goes with the map while the game's paused: M46)
        if (hud.has("mapHint")) hud.setVisible("mapHint", !GameClock.isPaused());

        // After January's message: the offer of the controls, low in the middle (Enter opens them, X skips)
        boolean offered = game.controlsOffered;
        if (offered && (rescaled || !hud.has("controlsOffer"))) {
            String[] keys = { "Enter", "Open the controls", "X", "Skip" };
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D pg = probe.createGraphics();
            HudStyle.smooth(pg);
            int w = HudStyle.hintWidth(pg, keys);
            pg.dispose();
            BufferedImage image = HudStyle.canvas(w, HudStyle.HINT_H, scale * 1.4f, g);
            HudStyle.paintHint(g[0], w, keys);
            g[0].dispose();
            hud.put("controlsOffer", image, 0, 0);
        }
        if (hud.has("controlsOffer")) {
            hud.setVisible("controlsOffer", offered && !GameClock.isPaused());
            hud.move("controlsOffer", (game.screenWidth - hud.width("controlsOffer")) / 2, Math.round(game.screenHeight * 0.7f));
        }

        hud.setDim(!game.menuOpen ? 0f : GameClock.isPaused() ? XenoGuesser_GLEventListener.PAUSED_DIM : 0.45f);
        hud.draw(gl, game.screenWidth, game.screenHeight);
    }
}
