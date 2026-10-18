/**
 * Multiplayer's scoring (M13, M17), worked out on each PC from both players' guesses (the
 * GUESS messages, and this player's own as sent), so the two PCs always agree. There are no
 * productivity targets. At the end of each quarter the player whose productivity that quarter
 * was higher wins it and gains quarter points equal to its number (Q1 1, Q2 2, Q3 3, Q4 4: 10 in
 * all); a tie gives nobody the points. Winning all of Q1, Q2 and Q3 (6 against 0, more than the
 * 4 left) wins the game at once. Otherwise, after Q4 the player with more quarter points wins;
 * level points go to final decider rounds, one at a time, each won by the higher round score,
 * another played after a tie.
 *
 * Players are named by role (the host is 119-A on both PCs), never as "this player", so the
 * same calls give the same answers everywhere. Quarters count from 0 here (0 is Q1).
 */
public final class MultiplayerScore {

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

    /** The quarter's points for winning it: its number. */
    public static int pointsFor(int quarter) {
        return quarter + 1;
    }

    /** The round that ends a quarter (counting from 1). */
    private static int lastRound(int quarter) {
        return (quarter + 1) * GameHUD.ROUNDS_PER_QUARTER;
    }

    /** A player's productivity over a quarter, once their last guess of it is in; -1 before. */
    public int productivity(Multiplayer.Role player, int quarter) {
        Multiplayer.Guess last = guesses.of(player, lastRound(quarter));
        return last == null ? -1 : last.quarterProductivity();
    }

    /**
     * A player's productivity so far in the quarter that round is in, as of their latest guess in
     * it up to round (0 before any).
     */
    public int productivitySoFar(Multiplayer.Role player, int round) {
        int first = (round - 1) / GameHUD.ROUNDS_PER_QUARTER * GameHUD.ROUNDS_PER_QUARTER + 1;
        for (int r = round; r >= first; r--) {
            Multiplayer.Guess g = guesses.of(player, r);
            if (g != null) return g.quarterProductivity();
        }
        return 0;
    }

    /** Whether both players' last guesses of the quarter are in, so it has been decided. */
    public boolean decided(int quarter) {
        return productivity(Multiplayer.Role.HOST, quarter) >= 0 && productivity(Multiplayer.Role.JOIN, quarter) >= 0;
    }

    /** Who won a decided quarter (the higher productivity), or null for a tie (or if it isn't decided). */
    public Multiplayer.Role quarterWinner(int quarter) {
        if (!decided(quarter)) return null;
        int a = productivity(Multiplayer.Role.HOST, quarter), b = productivity(Multiplayer.Role.JOIN, quarter);
        return a > b ? Multiplayer.Role.HOST : b > a ? Multiplayer.Role.JOIN : null;
    }

    /** A player's quarter points from every quarter decided so far. */
    public int points(Multiplayer.Role player) {
        int total = 0;
        for (int q = 0; q < GameHUD.QUARTERS; q++) {
            if (quarterWinner(q) == player) total += pointsFor(q);
        }
        return total;
    }

    /** The player who won all of Q1, Q2 and Q3 (an irrecoverable lead), or null. */
    public Multiplayer.Role sweepWinner() {
        Multiplayer.Role first = quarterWinner(0);
        if (first == null) return null;
        for (int q = 1; q < GameHUD.QUARTERS - 1; q++) {
            if (quarterWinner(q) != first) return null;
        }
        return first;
    }

    /** After Q4: the player with more quarter points, or null if they're level (or the year isn't over). */
    public Multiplayer.Role yearWinner() {
        if (!decided(GameHUD.QUARTERS - 1)) return null;
        int a = points(Multiplayer.Role.HOST), b = points(Multiplayer.Role.JOIN);
        return a > b ? Multiplayer.Role.HOST : b > a ? Multiplayer.Role.JOIN : null;
    }

    /** A final decider round (after the year): who scored higher in it, or null for a tie (or until both have guessed). */
    public Multiplayer.Role deciderWinner(int round) {
        Multiplayer.Guess a = guesses.of(Multiplayer.Role.HOST, round), b = guesses.of(Multiplayer.Role.JOIN, round);
        if (a == null || b == null) return null;
        return a.score() > b.score() ? Multiplayer.Role.HOST : b.score() > a.score() ? Multiplayer.Role.JOIN : null;
    }

    /** A round's score for a player, or -1 until their guess is in. */
    public int roundScore(Multiplayer.Role player, int round) {
        Multiplayer.Guess g = guesses.of(player, round);
        return g == null ? -1 : g.score();
    }
}
