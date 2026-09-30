import java.util.ArrayList;
import java.util.List;
import com.xenoguesser.math.Vector3;

/** One continuous polyline of road, sampled at roughly even spacing along the ground. */
public class RoadPath {
    public enum RoadClass {
        // Rank orders who has priority at junctions: minor roads give way to higher ranks
        HIGHWAY(56.0f, 2),
        STREET(28.0f, 1),
        LANE(24.0f, 0);

        public final float width;
        public final int rank;

        RoadClass(float width, int rank) {
            this.width = width;
            this.rank = rank;
        }
    }

    public final RoadClass roadClass;
    public final List<Vector3> points = new ArrayList<>();

    public RoadPath(RoadClass roadClass) {
        this.roadClass = roadClass;
    }

    /** A turning circle at the closed end of a road in a built-up area. */
    public static final class CulDeSac {
        public final float x;
        public final float z;
        public final float radius;

        public CulDeSac(float x, float z, float radius) {
            this.x = x;
            this.z = z;
            this.radius = radius;
        }
    }
}
