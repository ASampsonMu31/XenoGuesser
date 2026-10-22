/**
 * One part of XenoGuesser_GLEventListener: the code for one side of the running game (the map's
 * overlays, the HUD, the player's movement...), working on the listener's state through game.
 * The listener makes one of each and keeps it.
 */
abstract class GamePart {

    final XenoGuesser_GLEventListener game;

    GamePart(XenoGuesser_GLEventListener game) {
        this.game = game;
    }
}
