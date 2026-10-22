import java.awt.image.BufferedImage;

/**
 * A nation's name written on the map (see MapPanel.setNationLabels): the name in its own writing,
 * where it goes (world x and z as made; u and v across the map once placed there), whether it's
 * on the nation's main land, and its flag (or null).
 */
record NationLabel(BufferedImage name, float x, float z, boolean main, BufferedImage flag) {

    /** The same label placed somewhere else. */
    NationLabel at(float u, float v) {
        return new NationLabel(name, u, v, main, flag);
    }
}
