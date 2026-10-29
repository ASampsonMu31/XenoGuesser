/**
 * Where on the world the player is set down each round, chosen before a game starts (see
 * SingleplayerPanel and MultiplayerPanel): out in the country (beside dirt tracks far from
 * towns and houses, up in the hills and mountains where it can be), anywhere along the roads as
 * usual, or in town among the houses.
 * The shop's maps of what people have built (roads, buildings, shops, wealth, nations) are cheaper
 * in the country, where they're less use, and dearer in town, where they give the place away.
 */
public enum MapKind {
    RURAL("Rural", 0.6f),
    BALANCED("Balanced", 1.0f),
    URBAN("Urban", 1.6f);

    // The game's map: the one chosen before the game (or, matchmade, picked from those both players ticked)
    private static volatile MapKind current = BALANCED;

    public final String label;
    // How many times their usual price the shop's maps of what people have built cost
    public final float builtPriceFactor;

    MapKind(String label, float builtPriceFactor) {
        this.label = label;
        this.builtPriceFactor = builtPriceFactor;
    }

    public static MapKind current() {
        return current;
    }

    public static void choose(MapKind kind) {
        current = kind;
    }
}
