import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The road network as a graph for finding a way along the roads from one place to another,
 * as drawn on adverts: "the shop is this way". Nodes are the points of every road's line, on
 * the datum; roads are joined where their points meet, and a road's loose end is joined to
 * the nearest point of whatever road it runs into. Distances are straight lines between
 * neighbouring points (a few hundred units apart at most, so the same as along the ground).
 */
public final class RoadRoutes {

    // (planet-centred, on the datum)
    private final double[] xs, ys, zs;
    private final SpherePosition[] places;
    private final int[][] links;
    // Which road each point is on, so a place can join the road it's beside rather than any road near
    private final int[] pathOf;
    private final CellIndex<Integer> index = new CellIndex<>();

    public RoadRoutes(InfrastructureManager infrastructure) {
        List<SpherePosition> points = new ArrayList<>();
        List<int[]> pathRanges = new ArrayList<>();
        infrastructure.forEachRoadPath(path -> {
            int first = points.size();
            for (SpherePosition p : path.points) points.add(SphereMath.atAltitude(p, 0.0));
            pathRanges.add(new int[] { first, points.size() });
        });
        int n = points.size();
        xs = new double[n];
        ys = new double[n];
        zs = new double[n];
        places = points.toArray(new SpherePosition[0]);
        pathOf = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = places[i].x();
            ys[i] = places[i].y();
            zs[i] = places[i].z();
            index.add(places[i], i);
        }
        List<List<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adjacency.add(new ArrayList<>(3));
        for (int p = 0; p < pathRanges.size(); p++) {
            int[] range = pathRanges.get(p);
            for (int i = range[0]; i < range[1]; i++) {
                pathOf[i] = p;
                if (i + 1 < range[1]) link(adjacency, i, i + 1);
            }
        }
        for (int p = 0; p < pathRanges.size(); p++) {
            int[] range = pathRanges.get(p);
            for (int i = range[0]; i < range[1]; i++) {
                boolean end = i == range[0] || i == range[1] - 1;
                double reach = end ? 45.0 : 4.0;
                int best = -1;
                double bestDistance = reach;
                for (int j : index.near(places[i], reach)) {
                    if (pathOf[j] == p) continue;
                    double d = between(i, j);
                    if (!end && d <= reach) link(adjacency, i, j);
                    if (d < bestDistance) { bestDistance = d; best = j; }
                }
                if (end && best >= 0) link(adjacency, i, best);
            }
        }
        links = new int[n][];
        for (int i = 0; i < n; i++) links[i] = adjacency.get(i).stream().mapToInt(Integer::intValue).toArray();
    }

    private static void link(List<List<Integer>> adjacency, int a, int b) {
        if (!adjacency.get(a).contains(b)) adjacency.get(a).add(b);
        if (!adjacency.get(b).contains(a)) adjacency.get(b).add(a);
    }

    private double between(int a, int b) {
        double dx = xs[b] - xs[a], dy = ys[b] - ys[a], dz = zs[b] - zs[a];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double to(int a, SpherePosition p) {
        double dx = p.x() - xs[a], dy = p.y() - ys[a], dz = p.z() - zs[a];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final double SEARCH = 80.0;

    /**
     * Where a place joins the roads: the nearest point on any road's line (not merely its nearest
     * point, which on a long straight stretch may be on some other road altogether, across a
     * crash barrier or someone's garden), with the points of the line either side of it; null if
     * none is near.
     */
    private Join onRoad(SpherePosition place) {
        for (double reach = SEARCH; reach <= SEARCH * 8; reach *= 2) {
            Join best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int i : index.near(place, reach)) {
                for (int j : links[i]) {
                    if (pathOf[j] != pathOf[i] || Math.abs(j - i) != 1) continue;
                    double ex = xs[j] - xs[i], ey = ys[j] - ys[i], ez = zs[j] - zs[i];
                    double len2 = ex * ex + ey * ey + ez * ez;
                    double t = len2 < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0,
                            ((place.x() - xs[i]) * ex + (place.y() - ys[i]) * ey + (place.z() - zs[i]) * ez) / len2));
                    double px = xs[i] + ex * t, py = ys[i] + ey * t, pz = zs[i] + ez * t;
                    double d = (px - place.x()) * (px - place.x()) + (py - place.y()) * (py - place.y()) + (pz - place.z()) * (pz - place.z());
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = new Join(i, j, SphereMath.atAltitude(new SpherePosition(px, py, pz), 0.0));
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private record Join(int before, int after, SpherePosition at) {
    }

    /**
     * The way along the roads between two places, as points on the datum from a to b; empty if
     * there's none nearby. Each place joins the road it's on where it's nearest, and sets off
     * along it whichever way is shorter.
     */
    public List<SpherePosition> route(SpherePosition a, SpherePosition b) {
        List<SpherePosition> way = new ArrayList<>();
        a = SphereMath.atAltitude(a, 0.0);
        b = SphereMath.atAltitude(b, 0.0);
        Join from = onRoad(a), to = onRoad(b);
        if (from == null || to == null) return way;
        int[] starts = { from.before(), from.after() }, goals = { to.before(), to.after() };
        // On the same stretch: straight along it
        if (Math.min(starts[0], starts[1]) == Math.min(goals[0], goals[1]) && Math.max(starts[0], starts[1]) == Math.max(goals[0], goals[1])) {
            way.add(a);
            way.add(from.at());
            way.add(to.at());
            way.add(b);
            return way;
        }
        double limit = SphereMath.distance(a, b) * 3.0 + 400.0;
        double[] cost = new double[xs.length];
        Arrays.fill(cost, Double.MAX_VALUE);
        int[] previous = new int[xs.length];
        Arrays.fill(previous, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        for (int s : starts) {
            cost[s] = to(s, from.at());
            open.add(new double[] { cost[s] + to(s, to.at()), s });
        }
        int goal = -1;
        double bestTotal = Double.MAX_VALUE;
        while (!open.isEmpty()) {
            double[] top = open.poll();
            if (top[0] >= bestTotal) break;
            int at = (int) top[1];
            if (top[0] - to(at, to.at()) > cost[at] + 1e-3) continue;
            for (int g : goals) {
                if (at == g) {
                    double total = cost[at] + to(at, to.at());
                    if (total < bestTotal) { bestTotal = total; goal = at; }
                }
            }
            for (int next : links[at]) {
                double c = cost[at] + between(at, next);
                if (c < cost[next] && c < limit) {
                    cost[next] = c;
                    previous[next] = at;
                    open.add(new double[] { c + to(next, to.at()), next });
                }
            }
        }
        if (goal < 0) return way;
        way.add(b);
        way.add(0, to.at());
        for (int at = goal; at >= 0; at = previous[at]) way.add(0, places[at]);
        way.add(0, from.at());
        way.add(0, a);
        return way;
    }
}
