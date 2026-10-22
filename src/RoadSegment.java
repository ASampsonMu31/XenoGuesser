import com.xenoguesser.math.Vector3;

/**
 * A straight piece of road between two of its path's points, built in a frame of its own
 * (D3): east/up/south at its middle, on the datum. start, end and the tangents are in that
 * frame as {x, height above the datum, z}, x and z measured along the ground (see
 * SphereFrame.fromSphere), so the flat road-building code runs on them as it stands.
 */
final class RoadSegment {
    final SphereFrame frame;
    // Its ends on the planet: the path's own points
    final SpherePosition from, to;
    final Vector3 start;
    final Vector3 end;
    final Vector3 startTangent;
    final Vector3 endTangent;
    final int nationId;
    final int pathIndex;
    final RoadPath.RoadClass roadClass;
    final float startDistance;
    final float length;
    final float width;
    final boolean culDeSac;
    // The deck's height at each subdivision, once worked out: as drawn (meeting greater roads
    // at their height), and by its own ground alone
    volatile float[] deck, ownDeck;
    // Its asphalt's edges as drawn, and a cul-de-sac's disc's height, once worked out
    volatile float[][][] edges;
    volatile float discLevel = Float.NaN;
    boolean railed;

    RoadSegment(SphereFrame frame, SpherePosition from, SpherePosition to, Vector3 start, Vector3 end,
                        Vector3 startTangent, Vector3 endTangent, int nationId,
                        int pathIndex, RoadPath.RoadClass roadClass, float startDistance, float length,
                        float width, boolean culDeSac) {
        this.frame = frame;
        this.from = from;
        this.to = to;
        this.start = start;
        this.end = end;
        this.startTangent = startTangent;
        this.endTangent = endTangent;
        this.nationId = nationId;
        this.pathIndex = pathIndex;
        this.roadClass = roadClass;
        this.startDistance = startDistance;
        this.length = length;
        this.width = width;
        this.culDeSac = culDeSac;
    }

    /** Junction priority; turning circles always swallow any paint that reaches them. */
    int rank() {
        return culDeSac ? 3 : roadClass.rank;
    }
}
