/**
 * Multiplayer's scoring (M58, replacing M13 and M17's quarters), worked out on each PC from both
 * players' guesses (the GUESS messages, and this player's own as sent), so the two PCs always
 * agree. There are no months, quarters, productivity or points: each round is won by whoever's
 * guess was closer to the answer (a guess beats none; equally close, or neither guessing, is a
 * level round that nobody wins), and the first to win 10 rounds wins the game. The round's loser
 * (both, in a level round) takes something from the shop for free.
 *
 * Players are named by role (the host's the same on both PCs), never as "this player", so the
 * same calls give the same answers everywhere. Rounds count from 1.
 */
public final class MultiplayerScore {

    /** The rounds a player must win to win the game. */
    public static final int ROUNDS_TO_WIN = 10;

    /** Where the guesses come from: a player's guess for a round, or null if it isn't in yet. */
    @FunctionalInterface
    public interface Guesses {
        Multiplayer.Guess of(Multiplayer.Role player, int round);
    }

    private final Guesses guesses;

    public MultiplayerScore(Guesses guesses) {
        this.guesses = guesses;
    }

    /** The scoring for a session's game. */
    public static MultiplayerScore of(Multiplayer session) {
        return new MultiplayerScore(session::guessBy);
    }

    /** Whether both players' guesses for a round are in, so it has been decided. */
    public boolean decided(int round) {
        return guesses.of(Multiplayer.Role.HOST, round) != null && guesses.of(Multiplayer.Role.JOIN, round) != null;
    }

    /** How far a player's guess for a round was (unguessed: infinitely far), or NaN until it's in. */
    public double distance(Multiplayer.Role player, int round) {
        Multiplayer.Guess g = guesses.of(player, round);
        if (g == null) return Double.NaN;
        return Double.isNaN(g.distance()) ? Double.POSITIVE_INFINITY : g.distance();
    }

    /** Who won a decided round (the closer guess), or null for a level round (or if it isn't decided). */
    public Multiplayer.Role roundWinner(int round) {
        if (!decided(round)) return null;
        double a = distance(Multiplayer.Role.HOST, round), b = distance(Multiplayer.Role.JOIN, round);
        return a < b ? Multiplayer.Role.HOST : b < a ? Multiplayer.Role.JOIN : null;
    }

    /** The rounds a player has won, of rounds 1 to upTo. */
    public int wins(Multiplayer.Role player, int upTo) {
        int won = 0;
        for (int r = 1; r <= upTo; r++) if (roundWinner(r) == player) won++;
        return won;
    }

    /** Whoever has won ROUNDS_TO_WIN rounds by round upTo, or null if neither has yet. */
    public Multiplayer.Role gameWinner(int upTo) {
        for (Multiplayer.Role player : Multiplayer.Role.values()) {
            if (wins(player, upTo) >= ROUNDS_TO_WIN) return player;
        }
        return null;
    }
}
