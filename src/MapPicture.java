import java.awt.image.BufferedImage;

/**
 * A picture stood on the map (see MapPanel.setPictureOverlay): where it stands (a landmark's foot,
 * a design's middle: world x and z as made; u and v across the map once placed there), and for a landmark (described) its
 * name, the line about it and its kind, shown in its popup. A design's picture (a bollard, a
 * pole) may have a larger one drawn too, shown in the card above the map (see PreviewCard)
 * while the pointer is over it, with its kind.
 */
record MapPicture(BufferedImage picture, float x, float z, boolean described, String name, String caption, String kind, BufferedImage detail,
                  java.awt.Color ring) {

    MapPicture(BufferedImage picture, float x, float z) {
        this(picture, x, z, false, null, null, null, null, null);
    }

    MapPicture(BufferedImage picture, float x, float z, boolean described, String name, String caption, String kind) {
        this(picture, x, z, described, name, caption, kind, null, null);
    }

    /** A design's picture with a larger one for its popup, and what it is. */
    static MapPicture withDetail(BufferedImage picture, float x, float z, String kind, BufferedImage detail) {
        return new MapPicture(picture, x, z, false, null, null, kind, detail, null);
    }

    /** The same picture ringed in its region's outline colour (see BirdsEyeMaps.regionColour), to match it to its region's islands. */
    MapPicture ringed(java.awt.Color colour) {
        return new MapPicture(picture, x, z, described, name, caption, kind, detail, colour);
    }

    /** The same picture stood somewhere else. */
    MapPicture at(float u, float v) {
        return new MapPicture(picture, u, v, described, name, caption, kind, detail, ring);
    }
}
