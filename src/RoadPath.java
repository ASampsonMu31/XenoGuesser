import java.util.ArrayList;
import java.util.List;
import com.xenoguesser.math.Vector3;

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

    /**
     * Chart bridge (D13): the same points on the Mercator chart as {x, height, z}, for the
     * systems that still build and draw roads there (InfrastructureManager, the maps, signs,
     * routes). Made by toChart once the plan is final; goes in Phase 3b (the maps' use in
     * Phase 5).
     */
    public final List<Vector3> chart = new ArrayList<>();

    public RoadPath(RoadClass roadClass) {
        this.roadClass = roadClass;
    }

    /**
     * Fills chart from points. The chart's x runs on unbroken along the road (from its middle
     * point, which lies on the map), so a road over the map's join carries on into the
     * margin past the edge rather than jumping across the map.
     */
    public void toChart() {
        chart.clear();
        if (points.isEmpty()) return;
        int middle = points.size() / 2;
        float[] x = new float[points.size()], height = new float[points.size()], z = new float[points.size()];
        for (int i = 0; i < points.size(); i++) {
            double[] m = Planet.mercatorPosition(points.get(i));
            x[i] = (float) m[0];
            height[i] = (float) m[1];
            z[i] = (float) m[2];
        }
        for (int i = middle + 1; i < x.length; i++) x[i] = x[i - 1] + (float) Planet.wrapX(x[i] - x[i - 1]);
        for (int i = middle - 1; i >= 0; i--) x[i] = x[i + 1] + (float) Planet.wrapX(x[i] - x[i + 1]);
        for (int i = 0; i < x.length; i++) chart.add(new Vector3(x[i], height[i], z[i]));
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
