import java.util.ArrayList;
import java.util.List;

/**
 * One continuous polyline of road on the sphere, sampled at roughly even spacing along the
 * ground (great-circle distance); each point is on the ground, at the terrain's height.
 */
public class RoadPath {
    public enum RoadClass {
        // Rank orders who has priority at junctions: minor roads give way to higher ranks
        HIGHWAY(56.0f, 2),
        STREET(28.0f, 1),
        LANE(24.0f, 0),
        // Unmade tracks out into the country: cleared earth, no paint
        DIRT(15.0f, 0);

        public final float width;
        public final int rank;

        RoadClass(float width, int rank) {
            this.width = width;
            this.rank = rank;
        }
    }

    public final RoadClass roadClass;
    public final List<SpherePosition> points = new ArrayList<>();
    // A road on an island the main network never reached (see RoadNetworkBuilder.buildIslandTracks):
    // houses are built along it even when it's a dirt track
    public boolean island;

    public RoadPath(RoadClass roadClass) {
        this.roadClass = roadClass;
    }

    /**
     * A road end that meets neither another road nor the sea. InfrastructureManager
     * finishes every one of these with a house facing back down the road.
     */
    public static final class DeadEnd {
        public final int pathIndex;
        public final boolean atStart;

        public DeadEnd(int pathIndex, boolean atStart) {
            this.pathIndex = pathIndex;
            this.atStart = atStart;
        }
    }
}
