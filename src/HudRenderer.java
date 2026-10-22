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

    private int hudScoreVersion = -1, hudFps = -1, hudItemsVersion = -1, hudStaminaStep = -1;

    private long hudClockTenth = -1L;

    private Achievements.Achievement hudToast;

    // Under this long left on the round's clock, the screen's edges pulse red (M45)
    private static final long LOW_CLOCK_MS = 5000L;

    private float hudScale = -1f;

    private MapPanel.MapSize hudMapSize;

    /**
     * The HUD over the finished frame: the round and score at the top left, the FPS in the
     * top right corner, the inventory at the bottom left and the map's keys above the map.
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

        if (GameSettings.showFps() && game.gameHUD != null) {
            int fps = game.gameHUD.getGameFps();
            if (rescaled || fps != hudFps || !hud.has("fps")) {
                hudFps = fps;
                BufferedImage image = HudStyle.canvas(HudStyle.FPS_W, HudStyle.FPS_H, scale, g);
                HudStyle.paintFps(g[0], fps);
                g[0].dispose();
                hud.put("fps", image, game.screenWidth - image.getWidth() - Math.round(8 * scale), Math.round(6 * scale));
            }
        } else {
            hud.setVisible("fps", false);
            hudFps = -1;
        }

        // The sprint's stamina, in the top right (under the FPS, when that's shown), wherever the player can walk
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
            int below = GameSettings.showFps() ? Math.round((6 + HudStyle.FPS_H) * scale) : margin;
            hud.move("stamina", game.screenWidth - hud.width("stamina") - margin, below);
        }
        hud.setVisible("stamina", staminaShown);

        // The round's clock (M41), at the top in the middle, while it's running
        long[] clock = game.minimap != null ? game.minimap.guessClock() : null;
        if (clock != null) {
            long tenth = clock[0] / 100L;
            String note = clock[2] == 1L && Multiplayer.active() != null
                    ? "Employee " + Multiplayer.active().remoteLabel() + " guessed!" : "Time to guess";
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

        // The inventory: only what the player has bought, and nothing at all until they've bought something
        java.util.List<HudStyle.Item> items = game.inventoryItems();
        if (rescaled || game.itemsVersion != hudItemsVersion || !hud.has("inventory")) {
            hudItemsVersion = game.itemsVersion;
            int h = HudStyle.inventoryHeight(Math.max(1, items.size()));
            BufferedImage image = HudStyle.canvas(HudStyle.INVENTORY_W, h, scale, g);
            HudStyle.paintInventory(g[0], items);
            g[0].dispose();
            hud.put("inventory", image, margin, 0);
        }
        hud.setVisible("inventory", !items.isEmpty());
        // (kept at the bottom however the window is resized)
        if (hud.has("inventory")) {
            int h = Math.round(HudStyle.inventoryHeight(Math.max(1, items.size())) * scale);
            hud.move("inventory", margin, game.screenHeight - h - margin);
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
