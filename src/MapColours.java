/**
 * The plain map's colours, taken from the world itself: the sea as the open ocean looks in
 * the game, and the land as its grass and soil together.
 */
public final class MapColours {

    private MapColours() {}

    /** {sea, land} as RGB for the world with this seed. */
    public static int[] of(long worldSeed) {
        WorldPalette palette = new WorldPalette(worldSeed);
        float[] deep = palette.seaDeepOcean(), shallow = palette.seaShallowTint();
        float[] sea = new float[3], land = new float[3];
        for (int i = 0; i < 3; i++) {
            // Mostly the deep ocean, lit, with a little of the shallows' tint
            sea[i] = (deep[i] * 0.6f + shallow[i] * 0.4f) * 1.45f;
            float grass = (palette.grassDry[i] + palette.grassLush[i]) * 0.5f;
            land[i] = grass * 0.5f + palette.soilBase[i] * 0.5f;
        }
        return new int[] { rgb(sea), rgb(land) };
    }

    private static int rgb(float[] c) {
        int r = Math.max(0, Math.min(255, Math.round(c[0] * 255f)));
        int g = Math.max(0, Math.min(255, Math.round(c[1] * 255f)));
        int b = Math.max(0, Math.min(255, Math.round(c[2] * 255f)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
