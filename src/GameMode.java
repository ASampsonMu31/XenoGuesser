/**
 * How the player may get about in a game, chosen on the title screen before it starts: walking
 * (and jumping) and looking round freely, or standing where they landed, only looking round.
 * The harder mode has lower quarterly productivity targets.
 */
public enum GameMode {
    MOVE("Move", true, 1.0f),
    NO_MOVE("No move", false, 0.75f);

    // The game's mode: the one chosen on the title screen (one only, as with radio buttons)
    private static volatile GameMode current = MOVE;

    public final String label;
    // Whether the player can walk (W, A, S, D) and jump
    public final boolean moving;
    // The share of the usual productivity targets (GameHUD.TARGETS) the player must reach
    public final float targetShare;

    GameMode(String label, boolean moving, float targetShare) {
        this.label = label;
        this.moving = moving;
        this.targetShare = targetShare;
    }

    public static GameMode current() {
        return current;
    }

    public static void choose(GameMode mode) {
        current = mode;
    }
}
