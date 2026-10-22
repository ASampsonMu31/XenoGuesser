import java.awt.image.BufferedImage;

/**
 * A picture stood on the map (see MapPanel.setPictureOverlay): where its foot stands (world x
 * and z as made; u and v across the map once placed there), and for a landmark (described) its
 * name, the line about it and its kind, shown in its popup.
 */
record MapPicture(BufferedImage picture, float x, float z, boolean described, String name, String caption, String kind) {

    MapPicture(BufferedImage picture, float x, float z) {
        this(picture, x, z, false, null, null, null);
    }

    /** The same picture stood somewhere else. */
    MapPicture at(float u, float v) {
        return new MapPicture(picture, u, v, described, name, caption, kind);
    }
}
