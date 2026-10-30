import java.awt.image.BufferedImage;

/**
 * A nation's flag stood on the flags map (see MapPanel.setNationLabels): where it goes (world x and
 * z as made; u and v across the map once placed there), whether it's on the nation's main land,
 * and the flag itself.
 */
record NationLabel(float x, float z, boolean main, BufferedImage flag) {

    /** The same label placed somewhere else. */
    NationLabel at(float u, float v) {
        return new NationLabel(u, v, main, flag);
    }
}
