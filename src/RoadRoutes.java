import com.xenoguesser.math.Vector3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The road network as a graph for finding a way along the roads from one place to another,
 * as drawn on adverts: "the shop is this way". Nodes are the points of every road's line;
 * roads are joined where their points meet, and a road's loose end is joined to the nearest
 * point of whatever road it runs into.
 */
public final class RoadRoutes {

    private static final float CELL = 40f;
    private final float[] xs, zs;
    private final int[][] links;
    // Which road each point is on, so a place can join the road it's beside rather than any road near
    private final int[] pathOf;
    private final Map<Long, List<Integer>> grid = new HashMap<>();

    public RoadRoutes(InfrastructureManager infrastructure) {
        List<float[]> points = new ArrayList<>();
        List<int[]> pathRanges = new ArrayList<>();
        infrastructure.forEachRoadPath(path -> {
            int first = points.size();
            for (Vector3 p : path.points) points.add(new float[] { p.x, p.z });
            pathRanges.add(new int[] { first, points.size() });
        });
        int n = points.size();
        xs = new float[n];
        zs = new float[n];
        pathOf = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = points.get(i)[0];
            zs[i] = points.get(i)[1];
            grid.computeIfAbsent(key(xs[i], zs[i]), k -> new ArrayList<>()).add(i);
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
                float reach = end ? 45f : 4f;
                int best = -1;
                float bestDistance = reach * reach;
                for (int j : near(xs[i], zs[i], reach)) {
                    if (pathOf[j] == p) continue;
                    float dx = xs[j] - xs[i], dz = zs[j] - zs[i], d = dx * dx + dz * dz;
                    if (!end && d <= reach * reach) link(adjacency, i, j);
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

    private static long key(float x, float z) {
        return ((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL);
    }

    private List<Integer> near(float x, float z, float reach) {
        List<Integer> found = new ArrayList<>();
        int r = (int) Math.ceil(reach / CELL);
        long cx = (long) Math.floor(x / CELL), cz = (long) Math.floor(z / CELL);
        for (long i = cx - r; i <= cx + r; i++) {
            for (long j = cz - r; j <= cz + r; j++) {
                List<Integer> bucket = grid.get((i << 32) ^ (j & 0xFFFFFFFFL));
                if (bucket != null) found.addAll(bucket);
            }
        }
        return found;
    }

    /**
     * Where a place joins the roads: the nearest point on any road's line (not merely its nearest
     * point, which on a long straight stretch may be on some other road altogether, across a
     * crash barrier or someone's garden), as {point before, point after, x, z}; null if none is near.
     */
    private float[] onRoad(float x, float z) {
        for (float reach = CELL * 2; reach <= CELL * 16; reach *= 2) {
            float[] best = null;
            float bestDistance = Float.MAX_VALUE;
            for (int i : near(x, z, reach)) {
                for (int j : links[i]) {
                    if (pathOf[j] != pathOf[i] || Math.abs(j - i) != 1) continue;
                    float ex = xs[j] - xs[i], ez = zs[j] - zs[i], len2 = ex * ex + ez * ez;
                    float t = len2 < 1e-6f ? 0f : Math.max(0f, Math.min(1f, ((x - xs[i]) * ex + (z - zs[i]) * ez) / len2));
                    float px = xs[i] + ex * t, pz = zs[i] + ez * t;
                    float d = (px - x) * (px - x) + (pz - z) * (pz - z);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = new float[] { i, j, px, pz };
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /**
     * The way along the roads between two places, as {x, z} points from a to b; empty if there's
     * none nearby. Each place joins the road it's on where it's nearest, and sets off along it
     * whichever way is shorter.
     */
    public List<float[]> route(float ax, float az, float bx, float bz) {
        List<float[]> way = new ArrayList<>();
        float[] from = onRoad(ax, az), to = onRoad(bx, bz);
        if (from == null || to == null) return way;
        int[] starts = { (int) from[0], (int) from[1] }, goals = { (int) to[0], (int) to[1] };
        // On the same stretch: straight along it
        if (Math.min(starts[0], starts[1]) == Math.min(goals[0], goals[1]) && Math.max(starts[0], starts[1]) == Math.max(goals[0], goals[1])) {
            way.add(new float[] { ax, az });
            way.add(new float[] { from[2], from[3] });
            way.add(new float[] { to[2], to[3] });
            way.add(new float[] { bx, bz });
            return way;
        }
        float straight = (float) Math.hypot(bx - ax, bz - az);
        float limit = straight * 3f + 400f;
        float[] cost = new float[xs.length];
        Arrays.fill(cost, Float.MAX_VALUE);
        int[] previous = new int[xs.length];
        Arrays.fill(previous, -1);
        PriorityQueue<float[]> open = new PriorityQueue<>((p, q) -> Float.compare(p[0], q[0]));
        for (int s : starts) {
            cost[s] = (float) Math.hypot(xs[s] - from[2], zs[s] - from[3]);
            open.add(new float[] { cost[s] + heuristic(s, to[2], to[3]), s });
        }
        int goal = -1;
        float bestTotal = Float.MAX_VALUE;
        while (!open.isEmpty()) {
            float[] top = open.poll();
            if (top[0] >= bestTotal) break;
            int at = (int) top[1];
            if (top[0] - heuristic(at, to[2], to[3]) > cost[at] + 1e-3f) continue;
            for (int g : goals) {
                if (at == g) {
                    float total = cost[at] + (float) Math.hypot(xs[at] - to[2], zs[at] - to[3]);
                    if (total < bestTotal) { bestTotal = total; goal = at; }
                }
            }
            for (int next : links[at]) {
                float c = cost[at] + (float) Math.hypot(xs[next] - xs[at], zs[next] - zs[at]);
                if (c < cost[next] && c < limit) {
                    cost[next] = c;
                    previous[next] = at;
                    open.add(new float[] { c + heuristic(next, to[2], to[3]), next });
                }
            }
        }
        if (goal < 0) return way;
        way.add(new float[] { bx, bz });
        way.add(0, new float[] { to[2], to[3] });
        for (int at = goal; at >= 0; at = previous[at]) way.add(0, new float[] { xs[at], zs[at] });
        way.add(0, new float[] { from[2], from[3] });
        way.add(0, new float[] { ax, az });
        return way;
    }

    private float heuristic(int a, float x, float z) {
        return (float) Math.hypot(xs[a] - x, zs[a] - z);
    }
}
