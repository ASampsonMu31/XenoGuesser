/**
 * The world's time (M46): the wall clock, less however long the game has been paused. Everything
 * that moves in the world (the player, animals, people and their vehicles, the weather, water,
 * the traffic lights) goes by it, so while the game's paused it all stands still.
 */
public final class GameClock {
    private static long pausedAt = -1L, pausedFor;

    private GameClock() {
    }

    /** The world's time in milliseconds (only differences between readings mean anything). */
    public static synchronized long millis() {
        return (pausedAt >= 0L ? pausedAt : System.currentTimeMillis()) - pausedFor;
    }

    /** Stops the world's time (or starts it again where it stopped). */
    public static synchronized void setPaused(boolean paused) {
        if (paused && pausedAt < 0L) {
            pausedAt = System.currentTimeMillis();
        } else if (!paused && pausedAt >= 0L) {
            pausedFor += System.currentTimeMillis() - pausedAt;
            pausedAt = -1L;
        }
    }

    public static synchronized boolean isPaused() {
        return pausedAt >= 0L;
    }
}
