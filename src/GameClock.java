/**
 * The world's time (M46): the wall clock, less however long the game has been paused. Everything
 * that moves in the world (the player, animals, people and their vehicles, the weather, water,
 * the traffic lights) goes by it, so while the game's paused it all stands still.
 */
public final class GameClock {
    private static long pausedAt = -1L, pausedFor;
    // Developer aid (filming the trailer, see TrailerDirector): the time as filmed, moved on by a
    // fixed step each frame however long the frame took to draw; -1 while the wall clock rules
    private static long filmedAt = -1L;

    private GameClock() {
    }

    /** The world's time in milliseconds (only differences between readings mean anything). */
    public static synchronized long millis() {
        if (filmedAt >= 0L) return filmedAt;
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

    /** Developer aid: moves the filmed time on by one frame's step (the first call takes over from the wall clock). */
    public static synchronized void filmStep(long stepMillis) {
        if (filmedAt < 0L) filmedAt = millis();
        filmedAt += stepMillis;
    }

    public static synchronized boolean isPaused() {
        return pausedAt >= 0L;
    }
}
