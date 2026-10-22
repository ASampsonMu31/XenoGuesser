import java.util.ArrayList;
import java.util.List;

/** One flat face of a house's walls, from its bottom edge to its (possibly slanted) top edge, in house-local space. */
final class HouseWall {
    float[] bottomA, bottomB, topA, topB;   // {x, y, z}
    float[] normal;                          // outward, house-local
    float length;
    int index;
    // For building the face once its windows are known: normals at each end (smoothed
    // on round buildings), where its texture starts, its slanted height, and the window
    // openings cut through it as {s0, s1, y0, y1} (s along the wall from A to B, 0..1)
    float[] normalA, normalB;
    float u;
    float slant;
    final List<float[]> openings = new ArrayList<>();
    // Outward and level at each end, halfway between this wall's and its neighbour's
    // normals, so walls meeting at a corner move it alike when their surface is shaped
    float[] cornerA, cornerB;
    // The surface's shape (see ArchitectureStyle.Relief, NONE for flat): how far it stands
    // out at most, from what height up (the ground floor's level) to the top, how tall the
    // floors it repeats on are (0 for once up the whole wall), and how far apart its flutes or studs
    ArchitectureStyle.Relief relief = ArchitectureStyle.Relief.NONE;
    float reliefDepth, reliefBase, reliefFloor, reliefPitch;
}
