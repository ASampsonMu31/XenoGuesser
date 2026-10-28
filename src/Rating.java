/**
 * The player's rating for ranked multiplayer (M59), kept with the settings: only games found
 * by matchmaking in the ranked queue change it, never casual ones or games with a friend's code.
 * It's chess's Elo: everyone starts at 1000; a player rated R against one rated O was expected
 * to win E = 1 / (1 + 10^((O - R) / 400)) of the time, and after the game their rating moves by
 * K_FACTOR * (S - E), S being 1 for a win and 0 for a loss. So beating someone rated far above
 * you (E small) gains nearly all of K, and beating someone far below you (E near 1) little;
 * what one player gains the other loses.
 *
 * A ranked game counts as lost the moment it begins, and is put right if it's won: so quitting,
 * or the game closing, part way through a game going badly still loses it.
 */
public final class Rating {

    /** Every player's rating before their first ranked game. */
    public static final int START = 1000;
    /** The most a single game can move a rating (chess's K): the whole of it for a win nobody expected. */
    public static final double K_FACTOR = 32;

    private Rating() {
    }

    public static int rating() {
        return GameSettings.STORE.getInt("rating", START);
    }

    /** Ranked games won. */
    public static int wins() {
        return GameSettings.STORE.getInt("rankedWins", 0);
    }

    /** Ranked games lost (a game being played counts as lost until it's won). */
    public static int losses() {
        return GameSettings.STORE.getInt("rankedLosses", 0);
    }

    /** The chance a player rated `rating` was expected to beat one rated `opponent`, 0 to 1. */
    public static double expected(int rating, int opponent) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponent - rating) / 400.0));
    }

    /** A rating after a game against an opponent so rated, won or lost. */
    public static int after(int rating, int opponent, boolean won) {
        return (int) Math.round(rating + K_FACTOR * ((won ? 1 : 0) - expected(rating, opponent)));
    }

    /** A ranked game begun against an opponent so rated: it's counted as lost now, until it ends. */
    public static Game begin(int opponent) {
        Game game = new Game(rating(), opponent);
        store(after(game.before, opponent, false), wins(), losses() + 1);
        System.out.println("[MP] Ranked game begun: " + game.before + " against " + opponent
                + " (counted as lost, " + game.after(false) + ", until it's won)");
        return game;
    }

    private static void store(int rating, int wins, int losses) {
        try {
            GameSettings.STORE.putInt("rating", rating);
            GameSettings.STORE.putInt("rankedWins", wins);
            GameSettings.STORE.putInt("rankedLosses", Math.max(0, losses));
        } catch (Exception e) {
            System.err.println("[MP] Rating not saved: " + e);
        }
    }

    /** A ranked game being played: its rating before, and settled once, as won, lost or void. */
    public static final class Game {
        public final int before, opponent;
        private boolean settled;

        private Game(int before, int opponent) {
            this.before = before;
            this.opponent = opponent;
        }

        /** This player's rating after the game, won or lost. */
        public int after(boolean won) {
            return Rating.after(before, opponent, won);
        }

        /** Won: the loss counted as it began is taken back and the win counted instead. */
        public void won() {
            if (settled) return;
            settled = true;
            store(after(true), wins() + 1, losses() - 1);
            System.out.println("[MP] Ranked game won: " + before + " -> " + after(true));
        }

        /** Lost (or forfeited): as counted when it began. */
        public void lost() {
            settled = true;
        }

        /** Void (the connection lost, so nobody can say who left): as if it never was. */
        public void voided() {
            if (settled) return;
            settled = true;
            store(before, wins(), losses() - 1);
            System.out.println("[MP] Ranked game void: " + before + " kept");
        }
    }
}
