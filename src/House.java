/**
 * A house on its plot, built in its own frame (D3): frame's origin is on the datum under
 * the house's middle, local +Z (the door side) faces the road it fronts, and every house
 * measurement (plot, walls, fence) is in that frame, y being the height above the datum.
 */
final class House {
    SphereFrame frame;
    int nationId;
    float sizeScale;
    float width;
    float depth;
    float setback;
    float baseY;
    float mainWallHeight;
    float doorBase;
    int extensionSide;
    float extensionWidth;
    float extensionDepth;
    float extensionWallHeight;
    boolean fenced;
    float plotMinX;
    float plotMaxX;
    float plotMinZ;
    float plotMaxZ;
    float priority;
            long seed;
    int frontagePath;
            int wallVariant;
    int form;
    // Its architecture: which of the world's designs it's dressed in (see ArchitectureStyle.World)
    int architecture;
    // Fence: a tall security boundary, the outline's shape, whether a neighbour's fence
    // already runs along a side, and any extra fenced paddock (local minX, maxX, minZ, maxZ)
    boolean secure;
    int fenceShape;
    boolean sharedRight;
    float[] paddock;
    InfrastructureManager.Doorway doorway;
    InfrastructureManager.Shop shop;
    // How well off the household is (0 to 1, see areaRichness); how weathered its walls and
    // roof are (0 none to 3 badly); whether it's dressed up with pillars and a cornice
    float richness;
    int weathering;
    boolean grand;
    // A driveway down one side (-1 left, 1 right, 0 none), between these local x
    int drivewaySide;
    float driveMinX, driveMaxX;
    // How far towards the road the driveway runs (local z), once known
    float driveFrontZ;
    // The front door's corners in world space {x, y, z}: bottom left, bottom right, top right, top left
    float[][] doorCorners;


    float plotCentreLocalX() {
        return (plotMinX + plotMaxX) * 0.5f;
    }

    float plotCentreLocalZ() {
        return (plotMinZ + plotMaxZ) * 0.5f;
    }

    float plotRadius() {
        float hx = (plotMaxX - plotMinX) * 0.5f;
        float hz = (plotMaxZ - plotMinZ) * 0.5f;
        return (float) Math.sqrt(hx * hx + hz * hz);
    }

    /** Bounds of the building itself (house plus any extension) in local space. */
    float houseMinX() {
        return -width * 0.5f - (extensionSide < 0 ? extensionWidth : 0.0f);
    }

    float houseMaxX() {
        return width * 0.5f + (extensionSide > 0 ? extensionWidth : 0.0f);
    }
}
