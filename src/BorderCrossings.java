import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Fenced borders between hostile nations: the fence traced along the border (with a gate
 * where a road crosses), the check at each crossing (booths, guards, traffic lights, the
 * road straightened through it), and dirt tracks stopped short of the fence.
 */
final class BorderCrossings extends InfraPart {

    BorderCrossings(InfrastructureManager infra) {
        super(infra);
    }

    /**
     * Where a tarmac road crosses a border that gets a check or traffic lights, in a frame of
     * its own (D3): on the datum at the crossing, local x along the road the way its path runs,
     * local z across it towards the side the near booth stands (the near side's nation's).
     */
    private static final class BorderCheck {
        private final int index;
        private final SphereFrame frame;
        // The frame's turn from east, towards south (its local x's heading there)
        private final double heading;
        // The road's half width
        private final float half;
        private final int nearNation, farNation;
        // Traffic lights (the two sides drive on different sides of the road), and a fenced
        // border's checks and booths
        private final boolean lights, fenced;

        private BorderCheck(int index, SphereFrame frame, double heading, float half, int nearNation, int farNation,
                            boolean lights, boolean fenced) {
            this.index = index;
            this.frame = frame;
            this.heading = heading;
            this.half = half;
            this.nearNation = nearNation;
            this.farNation = farNation;
            this.lights = lights;
            this.fenced = fenced;
        }

        /** A place and a heading there (D10) in this check's frame, as {x, z, the way's x, z}. */
        private float[] localWay(SpherePosition place, double facing) {
            double[] local = frame.fromSphere(place);
            double way = SphereMath.carryHeading(place, frame.origin(), facing) - heading;
            return new float[] { (float) local[0], (float) local[2], (float) Math.cos(way), (float) Math.sin(way) };
        }
    }

    /** A border check's booth, in a frame of its own: local x along the road, its guard looking along local z by guard (1 or -1). */
    private static final class Booth {
        private final SphereFrame frame;
        private final double heading;
        private final int nation;
        private final float guard;

        private Booth(SphereFrame frame, double heading, int nation, float guard) {
            this.frame = frame;
            this.heading = heading;
            this.nation = nation;
            this.guard = guard;
        }
    }

    private final List<BorderCheck> borderChecks = new ArrayList<>();

    private final CellIndex<BorderCheck> checkIndex = new CellIndex<>(LOOKUP_LEVEL);

    // The booths, filed reaching as far as the room the border fence leaves them
    private final List<Booth> booths = new ArrayList<>();

    private final CellIndex<Booth> boothIndex = new CellIndex<>(LOOKUP_LEVEL);

    // How far back from the border the booth stands, how far out from the road's edge, and
    // how close a vehicle comes before it stops to be checked
    private static final float BOOTH_BACK = 14f, BOOTH_OUT = 14f;

    // A booth's part: its wall variant's material, drawn from both sides
    static final String BOOTH_PART = "booth_variant";

    // The booth's half width, wall and roof heights; and how far round its middle the border
    // fence is pushed back to leave it room
    private static final float BOOTH_HALF = 10f, BOOTH_WALL = 9f, BOOTH_ROOF = 34f, BOOTH_ROOM = 19f;

    // How far either side of a border crossing the road is made straight
    private static final float STRAIGHT_REACH = 75f;

    /**
     * Where a tarmac road crosses a border that gets a check or traffic lights, the road runs
     * dead straight for a way either side (along the way it was heading there), so the booths,
     * lights and markings all line up with it and nothing stands in the road.
     */
    // How finely a dirt track is looked along for a fenced border, and how far short of one it stops
    private static final float TRACK_FENCE_STEP = 2f, TRACK_FENCE_SHORT = 8f;

    /**
     * Dirt tracks never cross a fenced border (only made roads do, through a border check): the
     * builder stops a track growing into a hostile nation, but its last hop on to a road it meets,
     * or a step clipping a border's corner, can still get across; so each track is looked along
     * closely and ends TRACK_FENCE_SHORT before the first fenced border on it, wherever that is.
     * (Cut short in place, never removed, so every other road keeps its index.)
     */
    void stopTracksAtFences() {
        int cut = 0;
        for (RoadPath path : infra.roadNetwork) {
            if (path.roadClass != RoadPath.RoadClass.DIRT || path.points.size() < 2) continue;
            List<SpherePosition> pts = path.points;
            int nation = nationAt(pts.get(0));
            float along = 0f;
            float crossing = Float.NaN;
            for (int i = 0; i + 1 < pts.size() && Float.isNaN(crossing); i++) {
                SpherePosition a = pts.get(i), b = pts.get(i + 1);
                float length = (float) SphereMath.distance(a, b);
                int steps = Math.max(1, (int) Math.ceil(length / TRACK_FENCE_STEP));
                for (int k = 1; k <= steps; k++) {
                    int next = nationAt(SphereMath.slerp(a, b, k / (double) steps));
                    // (from one nation to the next, so a border by way of a third is caught too)
                    if (infra.kinship.hostile(nation, next)) {
                        crossing = along + length * (k - 1) / steps;
                        break;
                    }
                    if (next >= 1) nation = next;
                }
                along += length;
            }
            if (Float.isNaN(crossing)) continue;
            cut++;
            // Kept up to TRACK_FENCE_SHORT before the border
            float end = crossing - TRACK_FENCE_SHORT;
            List<SpherePosition> kept = new ArrayList<>();
            kept.add(pts.get(0));
            float walked = 0f;
            for (int i = 0; i + 1 < pts.size() && end > 0f; i++) {
                SpherePosition a = pts.get(i), b = pts.get(i + 1);
                float length = (float) SphereMath.distance(a, b);
                if (walked + length <= end) {
                    kept.add(b);
                    walked += length;
                    continue;
                }
                float t = (end - walked) / Math.max(1e-3f, length);
                if (t * length > 1f) kept.add(TerrainMesh.groundAt(SphereMath.slerp(a, b, t), infra.terrainNoise));
                break;
            }
            pts.clear();
            pts.addAll(kept);
        }
        System.out.printf("[ROADS] %d dirt tracks cut short of fenced borders%n", cut);
    }

    void straightenCrossings() {
        for (RoadPath path : infra.roadNetwork) {
            if (path.roadClass == RoadPath.RoadClass.DIRT) continue;
            List<SpherePosition> pts = path.points;
            for (int i = 0; i + 1 < pts.size(); i++) {
                SpherePosition a = pts.get(i), b = pts.get(i + 1);
                int na = nationAt(a), nb = nationAt(b);
                if (na == nb || na < 1 || nb < 1) continue;
                if (!infra.kinship.hostile(na, nb) && infra.drivingSide(na) == infra.drivingSide(nb)) continue;
                SpherePosition p = borderCrossing(a, b, na);
                // Its heading there, from a little way either side
                SpherePosition before = pts.get(Math.max(0, i - 1)), after = pts.get(Math.min(pts.size() - 1, i + 2));
                if (SphereMath.distance(before, after) < 1e-3) continue;
                SphereFrame frame = SphereFrame.at(p);
                double heading = frame.headingOf(after.x() - before.x(), after.y() - before.y(), after.z() - before.z());
                // The points within reach either side go; a straight run (along the great
                // circle through the crossing) takes their place
                int first = i, last = i + 1;
                while (first > 0 && SphereMath.distance(pts.get(first), p) < STRAIGHT_REACH) first--;
                while (last < pts.size() - 1 && SphereMath.distance(pts.get(last), p) < STRAIGHT_REACH) last++;
                List<SpherePosition> run = new ArrayList<>();
                for (float t = -STRAIGHT_REACH; t <= STRAIGHT_REACH + 0.01f; t += 15f) {
                    run.add(TerrainMesh.groundAt(SphereMath.move(p, heading, t), infra.terrainNoise));
                }
                // (the run goes the path's own way)
                List<SpherePosition> keepHead = new ArrayList<>(pts.subList(0, first + 1));
                List<SpherePosition> keepTail = new ArrayList<>(pts.subList(last, pts.size()));
                double[] way = frame.headingDirection(heading);
                boolean forward = (b.x() - a.x()) * way[0] + (b.y() - a.y()) * way[1] + (b.z() - a.z()) * way[2] >= 0.0;
                if (!forward) java.util.Collections.reverse(run);
                pts.clear();
                pts.addAll(keepHead);
                pts.addAll(run);
                pts.addAll(keepTail);
                i = keepHead.size() + run.size() - 1;
            }
        }
    }

    /** Finds every place a tarmac road crosses a fenced border, and where its booths stand (D4: planned on the sphere). */
    void findBorderChecks() {
        for (RoadPath path : infra.roadNetwork) {
            if (path.roadClass == RoadPath.RoadClass.DIRT) continue;
            List<SpherePosition> pts = path.points;
            for (int i = 0; i + 1 < pts.size(); i++) {
                SpherePosition a = pts.get(i), b = pts.get(i + 1);
                int na = nationAt(a), nb = nationAt(b);
                // Fenced borders get a check; open ones (alike cultures) only if the two drive on
                // different sides of the road, for the traffic lights
                if (na == nb || na < 1 || nb < 1) continue;
                boolean fenced = infra.kinship.hostile(na, nb);
                if (!fenced && infra.drivingSide(na) == infra.drivingSide(nb)) continue;
                if (SphereMath.distance(a, b) < 1e-3) continue;
                SpherePosition p = SphereMath.atAltitude(borderCrossing(a, b, na), 0.0);
                // (a road wandering back and forth across the border gets one check, its crossover
                // and guard rails clear of the next)
                if (checkIndex.nearest(p, 2.0 * RAIL_CLEAR_OF_BORDER) != null) continue;
                double heading = SphereFrame.at(p).headingOf(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
                float half = path.roadClass.width * 0.5f;
                // Where the two sides drive on different sides of the road, a crossover: traffic
                // lights at each end, taking turns, and (at a fenced border) a booth at each end
                boolean lights = infra.drivingSide(na) != infra.drivingSide(nb);
                BorderCheck check = new BorderCheck(borderChecks.size(), SphereFrame.at(p, heading), heading, half, na, nb, lights, fenced);
                if (fenced) {
                    // On the near side of the border (at a crossover, its near end), beside the
                    // road, its guard looking out over it; and the far end's, across the road
                    float back = lights ? CROSS_HALF : BOOTH_BACK;
                    addBooth(check, -back, half + BOOTH_OUT, na, -1f);
                    if (lights) addBooth(check, back, -(half + BOOTH_OUT), nb, 1f);
                }
                borderChecks.add(check);
                checkIndex.add(p, check);
            }
        }
        int lit = 0;
        for (BorderCheck c : borderChecks) if (c.lights) lit++;
        System.out.printf("[BORDERS] %d border checks on the roads, %d with traffic lights%n", borderChecks.size(), lit);
    }

    /** A booth at a point in a check's frame, its axes carried over from the check's. */
    private void addBooth(BorderCheck check, float along, float across, int nation, float guard) {
        SpherePosition at = check.frame.toSphere(along, 0.0, across);
        double heading = SphereMath.carryHeading(check.frame.origin(), at, check.heading);
        Booth booth = new Booth(SphereFrame.at(at, heading), heading, nation, guard);
        booths.add(booth);
        boothIndex.add(at, BOOTH_ROOM, booth);
    }

    // How far either side of the border a crossover's ends (its lights and booths) are
    private static final float CROSS_HALF = 45f;

    // Traffic lights at a crossing: how long each side's traffic has to cross, and the pause
    // between (both lights off) while the last across clear the crossing; how far back from the
    // border each side's cars wait at a red light; how far from a crossing the guard rails stop
    static final float LIGHT_GO = 14f, LIGHT_CLEAR = 7f, LIGHT_STOP_BACK = CROSS_HALF, RAIL_CLEAR_OF_BORDER = CROSS_HALF + 30f;

    /**
     * Whose turn it is to cross at border check i: 1 the traffic coming from the booth's side,
     * 2 that from the other side, 0 neither (the pause between); always 3 (anyone) where there
     * are no lights.
     */
    public int borderTurn(int i) {
        if (!borderChecks.get(i).lights) return 3;
        float cycle = 2f * (LIGHT_GO + LIGHT_CLEAR);
        // (each crossing a little out of step with the others)
        double t = (GameClock.millis() / 1000.0 + i * 7.3) % cycle;
        if (t < LIGHT_GO) return 1;
        if (t < LIGHT_GO + LIGHT_CLEAR) return 0;
        if (t < 2f * LIGHT_GO + LIGHT_CLEAR) return 2;
        return 0;
    }

    /**
     * How far ahead the line is where a vehicle at a place, facing heading (D10), must wait at a
     * border's traffic light that isn't its turn; NaN if it may go on (or there's none within
     * reach). Worked in each check's frame.
     */
    public float borderHoldAhead(SpherePosition place, double heading, float reach) {
        place = SphereMath.atAltitude(place, 0.0);
        float nearest = Float.NaN;
        for (BorderCheck c : checkIndex.near(place, reach + LIGHT_STOP_BACK)) {
            if (!c.lights) continue;
            float[] v = c.localWay(place, heading);
            // (the way along the road: with it, from the booth's side, or against it)
            float with = v[2];
            if (Math.abs(with) < 0.3f) continue;
            int side = with > 0f ? 1 : 2;
            if (borderTurn(c.index) == side) continue;
            float lx = (with > 0f ? -LIGHT_STOP_BACK : LIGHT_STOP_BACK) - v[0], lz = -v[1];
            float ahead = lx * v[2] + lz * v[3];
            if (ahead < -1.5f || ahead > reach || Math.abs(lz) > c.half + 6f) continue;
            if (Float.isNaN(nearest) || ahead < nearest) nearest = ahead;
        }
        return nearest;
    }

    // Each world's two traffic light colours (one for each side's turn), and how they're arranged
    private float[][] lightColours;

    /** The traffic lights at the crossings, one at each end, across the road from that end's booth; placed in each check's frame. */
    public List<InfrastructureManager.TrafficLight> borderLights() {
        if (lightColours == null) {
            Random rand = new Random(infra.worldSeed * 211L + 5L);
            float hue = rand.nextFloat();
            lightColours = new float[][] { WorldPalette.hsv(hue, 0.85f, 1f), WorldPalette.hsv(hue + 0.3f + rand.nextFloat() * 0.4f, 0.85f, 1f) };
        }
        List<InfrastructureManager.TrafficLight> lights = new ArrayList<>();
        for (BorderCheck c : borderChecks) {
            if (!c.lights) continue;
            boolean vertical = new Random(infra.worldSeed * 7L + c.index).nextBoolean();
            for (float end = -1f; end <= 1f; end += 2f) {
                SpherePosition foot = c.frame.toSphere(CROSS_HALF * end, 0.0, (c.half + 5f) * end);
                lights.add(new InfrastructureManager.TrafficLight(foot, SphereMath.carryHeading(c.frame.origin(), foot, c.heading), vertical, c.index,
                        lightColours[0], lightColours[1]));
            }
        }
        return lights;
    }

    /** The border checks' booths' guards. */
    public List<InfrastructureManager.Guard> borderGuards() {
        List<InfrastructureManager.Guard> list = new ArrayList<>();
        for (Booth b : booths) list.add(new InfrastructureManager.Guard(b.frame.origin(), b.heading + b.guard * Math.PI * 0.5, b.nation));
        return list;
    }

    /**
     * The next border check on the road ahead of a vehicle at a place facing heading (D10), as
     * {its number, how far ahead the place to stop beside its booth is}; null if none within
     * reach. Vehicles from either way stop level with the booth. Worked in each check's frame.
     */
    public float[] borderStopAhead(SpherePosition place, double heading, float reach) {
        place = SphereMath.atAltitude(place, 0.0);
        float[] best = null;
        for (BorderCheck c : checkIndex.near(place, reach + CROSS_HALF)) {
            if (!c.fenced) continue;   // (an open border: no check)
            float[] v = c.localWay(place, heading);
            // Level with the booth, on the road's middle line: at a crossover, the booth at the
            // end the vehicle comes in by
            float back = c.lights ? (v[2] >= 0f ? -CROSS_HALF : CROSS_HALF) : -BOOTH_BACK;
            float ox = back - v[0], oz = -v[1];
            float ahead = ox * v[2] + oz * v[3];
            // (how far off the road's line it is: within the road)
            if (ahead < -2f || ahead > reach || Math.abs(oz) > c.half + 6f || Math.abs(ox) > reach) continue;
            if (best == null || ahead < best[1]) best = new float[] { c.index, ahead };
        }
        return best;
    }

    /** Where the border fence must keep clear of a booth: pushed out round its middle. */
    private SpherePosition clearOfBooths(SpherePosition p) {
        for (Booth booth : boothIndex.near(p, 0.0)) {
            double[] local = booth.frame.fromSphere(p);
            double d = Math.hypot(local[0], local[2]);
            if (d >= BOOTH_ROOM) continue;
            if (d < 1e-3) return booth.frame.toSphere(BOOTH_ROOM, 0.0, 0.0);
            return booth.frame.toSphere(local[0] / d * BOOTH_ROOM, 0.0, local[2] / d * BOOTH_ROOM);
        }
        return p;
    }

    /** Whether a sign would stand at a border crossing (its booths, lights and gates). */
    boolean nearBorderCheck(InfrastructureObject sign) {
        return borderCheckAt(sign.frame.origin(), CROSS_HALF + 35f) != null;
    }

    /** The border check within reach of a place (the nearest), or null. */
    BorderCheck borderCheckAt(SpherePosition place, float reach) {
        return checkIndex.nearest(SphereMath.atAltitude(place, 0.0), reach);
    }

    /**
     * The booths of any border checks in this cell, each built in its own frame: a small
     * concrete box beside the road, waist high, with a pillar at each corner holding up a flat
     * roof (its guard stands in it: see Inhabitants).
     */
    void borderBooths(Map<Integer, NationBatch> batches, long cell) {
        for (Booth booth : booths) {
            if (infra.batchCell(booth.frame.origin()) != cell) continue;
            NationBatch batch = batches.computeIfAbsent(booth.nation, nation -> new NationBatch(infra, nation));
            SpherePosition origin = booth.frame.origin();
            // Built of the plain local material (the nation's walls there), seen from inside and out
            int variant = infra.housePlacement.chooseWallVariant(origin, booth.nation, (long) (origin.x() * 31.0) ^ (long) (origin.z() * 17.0),
                    BuildingStyle.Pattern.COURSED);
            MeshBuilder concrete = batch.builder(BOOTH_PART + variant);
            concrete.setPlanarTextureScale(1.0f / infra.wallVariants.get(variant).tileWidth);
            batch.place(inWorld(booth.frame, false));
            float ground = TerrainMesh.getLayeredHeight(origin, infra.terrainNoise);
            float half = BOOTH_HALF, wall = BOOTH_WALL, roof = BOOTH_ROOF;
            // Four thin walls round its floor, chest high to the guard standing inside (local x
            // along the road, z across it)
            float thick = 0.8f;
            for (float sign = -1f; sign <= 1f; sign += 2f) {
                float o = sign * (half - thick);
                // (front and back, running across; then the sides, running along)
                addBox(concrete, o, ground - 2f, 0f, 0f, 1f, half, thick, wall + 2f);
                addBox(concrete, 0f, ground - 2f, o, 1f, 0f, half, thick, wall + 2f);
            }
            for (int k = 0; k < 4; k++) {
                float sa = (k & 1) == 0 ? -1f : 1f, sb = (k & 2) == 0 ? -1f : 1f;
                addBox(concrete, sa * (half - 1.2f), ground + wall, sb * (half - 1.2f), 1f, 0f, 1.1f, 1.1f, roof - wall);
            }
            float r = half + 3.2f;
            addBox(concrete, 0f, ground + roof, 0f, 1f, 0f, r, r, 2.6f);
            // (the roof's underside, which a box leaves open)
            float[][] under = new float[4][];
            float[][] signs = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
            for (int k = 0; k < 4; k++) {
                under[k] = new float[] { r * signs[k][0], ground + roof, r * signs[k][1] };
            }
            concrete.addConvexFace(new float[] { 0f, ground + roof + 2f, 0f }, under);
            batch.include(0f, ground + roof + 2f, 0f, half + 3f);
            batch.place(null);
            infra.recordCircle(origin, half + 0.5f);
        }
    }

    /**
     * The crossovers' road markings, in the check's frame: two lines crossing in an X over the
     * middle, from each side's lane at one end to the other side of the road at the other.
     */
    void crossoverLines(Map<Integer, NationBatch> batches, long cell) {
        for (BorderCheck c : borderChecks) {
            if (!c.lights || infra.batchCell(c.frame.origin()) != cell) continue;
            NationBatch batch = batches.computeIfAbsent(c.nearNation, nation -> new NationBatch(infra, nation));
            MeshBuilder paint = batch.builder("line");
            // (laid along the ground: local x along the road, z across it)
            batch.place(inWorld(c.frame, true));
            float lane = c.half * 0.5f, reach = CROSS_HALF - 8f, w = 0.9f;
            int steps = 16;
            for (float flip = -1f; flip <= 1f; flip += 2f) {
                for (int k = 0; k < steps; k++) {
                    float t0 = k / (float) steps, t1 = (k + 1) / (float) steps;
                    float[][] q = new float[4][];
                    for (int e = 0; e < 2; e++) {
                        float t = e == 0 ? t0 : t1;
                        float along = -reach + 2f * reach * t, lat = flip * lane * (1f - 2f * t);
                        // (across the line, square to the road)
                        float y = infra.roadGeometry.roadSurfaceY(c.frame.toSphere(along, 0.0, lat)) + 0.15f;
                        q[e * 2] = new float[] { along, y, lat - w };
                        q[e * 2 + 1] = new float[] { along, y, lat + w };
                    }
                    int a = paint.addVertex(q[0][0], q[0][1], q[0][2], 0f, 1f, 0f, 0f, 0f);
                    int b = paint.addVertex(q[1][0], q[1][1], q[1][2], 0f, 1f, 0f, 1f, 0f);
                    int d = paint.addVertex(q[2][0], q[2][1], q[2][2], 0f, 1f, 0f, 0f, 1f);
                    int e = paint.addVertex(q[3][0], q[3][1], q[3][2], 0f, 1f, 0f, 1f, 1f);
                    // (both windings: whichever faces up is drawn)
                    paint.addTriangle(a, b, e);
                    paint.addTriangle(a, e, d);
                    paint.addTriangle(a, e, b);
                    paint.addTriangle(a, d, e);
                }
            }
            batch.include(0f, infra.roadGeometry.roadSurfaceY(c.frame.origin()) + 1f, 0f, CROSS_HALF + 4f);
            batch.place(null);
        }
    }

    // Border fences: the lattice the border is traced on (BORDER_LEVEL geodesic triangles, about
    // 7 units a side, BORDER_LEVEL - BATCH_LEVEL levels below the batch cells so each batch cell
    // holds exactly its own), how tall the fence is, the gap for a road through it, and how far
    // the gateposts stand from the road's middle
    private static final int BORDER_LEVEL = InfrastructureManager.BATCH_LEVEL + 5;

    private static final float BORDER_FENCE_HEIGHT = 26f, BORDER_GATE_CLEARANCE = 4f;

    private final GeodesicChunkGrid borderGrid = new GeodesicChunkGrid(BORDER_LEVEL);

    /**
     * The fence along any border in this cell between nations at odds (see
     * NationKinship.hostile): tall chain-link on steel posts, barbed wire strung along arms
     * leaning out at the top, and where a road crosses, a gap between heavy gateposts with
     * the gates swung open back along the fence. Where the border runs is
     * NationGenerationManager.getNationAt's; it is traced on the geodesic lattice at BORDER_LEVEL (marching triangles, each
     * crossing found closely by halving along the triangle's side), the same size everywhere.
     * The lattice's triangles nest in the batch cells, and a side two cells share is crossed at
     * the same place from both, so neighbouring cells meet. Under water there is none.
     */
    void borderFences(Map<Integer, NationBatch> batches, long cellId) {
        GeodesicChunkGrid.Cell cell = infra.batchGrid.cell(cellId);
        SpherePosition middle = SphereMath.atAltitude(cell.centre(), 0.0);
        // Far from any such border (the borders wander up to a couple of territory cells from
        // the raster's)
        double reach = cell.angularRadius() * Planet.radius() / NationGenerationManager.cellSize();
        if (infra.borderGapAt(middle) > 3.5 + reach) return;
        long first = cellId << (2 * (BORDER_LEVEL - InfrastructureManager.BATCH_LEVEL));
        int count = 1 << (2 * (BORDER_LEVEL - InfrastructureManager.BATCH_LEVEL));
        // Each lattice point's nation, looked up once (points are shared by up to six triangles)
        Map<GeodesicChunkGrid.Direction, Integer> owners = new HashMap<>();
        for (int k = 0; k < count; k++) {
            GeodesicChunkGrid.Cell t = borderGrid.cell(first + k);
            GeodesicChunkGrid.Direction[] corner = { t.a(), t.b(), t.c() };
            int[] id = new int[3];
            for (int c = 0; c < 3; c++) id[c] = owners.computeIfAbsent(corner[c], d -> nationAt(SphereMath.atAltitude(d, 0.0)));
            List<SpherePosition> crossings = new ArrayList<>();
            int a = 0, b = 0;
            for (int c = 0; c < 3; c++) {
                int n = (c + 1) % 3;
                if (!infra.kinship.hostile(id[c], id[n])) continue;
                crossings.add(borderCrossing(corner[c], id[c], corner[n], id[n]));
                a = id[c];
                b = id[n];
            }
            if (crossings.isEmpty()) continue;
            NationBatch batch = batches.computeIfAbsent(Math.min(a, b), nation -> new NationBatch(infra, nation));
            // (a post every other triangle or so, by the triangle, so the same from any cell)
            boolean post = Long.hashCode((first + k) * 0x9E3779B97F4A7C15L) % 2 == 0;
            if (crossings.size() == 2) {
                borderFencePiece(batch, crossings.get(0), crossings.get(1), post);
            } else {
                // Three nations meet: each crossing runs in to the middle
                SpherePosition meet = SphereMath.atAltitude(t.centre(), 0.0);
                for (SpherePosition crossing : crossings) borderFencePiece(batch, crossing, meet, post);
            }
        }
    }

    /** Whether a house's plot (with a margin) reaches across a fenced border, where the fence would run through it. */
    boolean straddlesFencedBorder(House house) {
        // (nor on a border check's booth)
        if (!boothIndex.near(house.frame.origin(), house.plotRadius() + 6f).isEmpty()) return true;
        if (infra.borderGapAt(house.frame.origin()) > 3.5f) return false;
        float margin = 4f;
        float[][] corners = { { house.plotMinX - margin, house.plotMinZ - margin }, { house.plotMaxX + margin, house.plotMinZ - margin },
                { house.plotMaxX + margin, house.plotMaxZ + margin }, { house.plotMinX - margin, house.plotMaxZ + margin } };
        int own = house.nationId;
        for (float[] c : corners) {
            if (infra.kinship.hostile(own, nationAt(world(house, c[0], 0f, c[1])))) return true;
        }
        return false;
    }

    private int nationAt(SpherePosition place) {
        return infra.nationManager.getNationAt(place);
    }

    /** Where along the great circle between two places the border lies, found by halving (a belongs to nation first). */
    private SpherePosition borderCrossing(SpherePosition a, SpherePosition b, int first) {
        double lo = 0.0, hi = 1.0;
        for (int k = 0; k < 4; k++) {
            double mid = (lo + hi) * 0.5;
            if (nationAt(SphereMath.slerp(a, b, mid)) == first) lo = mid; else hi = mid;
        }
        return SphereMath.slerp(a, b, (lo + hi) * 0.5);
    }

    /**
     * Where the border crosses a side of the lattice between p (nation np's) and q (nq's), on
     * the datum: the side is always taken from the same end, so the two triangles either side
     * of it find the same place.
     */
    private SpherePosition borderCrossing(GeodesicChunkGrid.Direction p, int np, GeodesicChunkGrid.Direction q, int nq) {
        boolean swap = p.x() != q.x() ? p.x() > q.x() : p.y() != q.y() ? p.y() > q.y() : p.z() > q.z();
        return swap ? borderCrossing(SphereMath.atAltitude(q, 0.0), SphereMath.atAltitude(p, 0.0), nq)
                : borderCrossing(SphereMath.atAltitude(p, 0.0), SphereMath.atAltitude(q, 0.0), np);
    }

    /**
     * One stretch of border fence between two points on the border (with a gatepost where a
     * road begins), built in a frame of its own at its middle (D4), with a post at its start if
     * withPost.
     */
    private void borderFencePiece(NationBatch batch, SpherePosition start, SpherePosition end, boolean withPost) {
        // (bending back round any booth beside the gateway)
        SpherePosition from = clearOfBooths(start);
        SpherePosition to = clearOfBooths(end);
        if (SphereMath.distance(from, to) < 0.05) return;
        SphereFrame frame = SphereFrame.at(SphereMath.slerp(from, to, 0.5));
        double[] fa = frame.fromSphere(from), fb = frame.fromSphere(to);
        float length = (float) Math.hypot(fb[0] - fa[0], fb[2] - fa[2]);
        if (length < 0.05f) return;
        float dirX = (float) (fb[0] - fa[0]) / length, dirZ = (float) (fb[2] - fa[2]) / length;
        float[] a = { (float) fa[0], TerrainMesh.getLayeredHeight(from, infra.terrainNoise), (float) fa[2] };
        float[] b = { (float) fb[0], TerrainMesh.getLayeredHeight(to, infra.terrainNoise), (float) fb[2] };
        if (Math.min(a[1], b[1]) < infra.seaLevel + 0.5f) return;
        boolean roadA = infra.isRoadLocation(from, BORDER_GATE_CLEARANCE), roadB = infra.isRoadLocation(to, BORDER_GATE_CLEARANCE);
        float hgt = BORDER_FENCE_HEIGHT;
        if (roadA && roadB) return;
        // (laid along the ground: y is the height above the datum)
        batch.place(inWorld(frame, true));
        if (roadA || roadB) {
            // The gateway: a heavy post at the road's side, and the gate swung back along the fence
            float[] post = roadA ? b : a;
            float awayX = roadA ? dirX : -dirX, awayZ = roadA ? dirZ : -dirZ;
            MeshBuilder posts = batch.builder("borderpost");
            posts.setPlanarTextureScale(0.1f);
            addBox(posts, post[0], post[1] - 2f, post[2], dirX, dirZ, 1.6f, 1.6f, hgt + 8f);
            batch.include(post[0], post[1] + hgt + 8f, post[2], 4f);
            infra.recordCircle(roadA ? to : from, 2.2f);
            MeshBuilder gate = batch.builder("borderfence");
            float side = 2.4f;
            float[] hinge = { post[0] - awayZ * side, post[1], post[2] + awayX * side };
            float freeX = hinge[0] + awayX * 12f, freeZ = hinge[2] + awayZ * 12f;
            float[] free = { freeX, TerrainMesh.getLayeredHeight(frame.toSphere(freeX, 0.0, freeZ), infra.terrainNoise), freeZ };
            addPrism(gate, hinge, free, 0.5f, 0.8f, 1.6f);
            addPrism(gate, hinge, free, 0.5f, hgt * 0.5f - 0.4f, hgt * 0.5f + 0.4f);
            addPrism(gate, hinge, free, 0.5f, hgt - 1.6f, hgt - 0.8f);
            uprights(gate, hinge, free, awayX, awayZ, 1.5f, 0.12f, 0.8f, hgt - 0.8f);
            addBox(gate, free[0], free[1] + 0.2f, free[2], awayX, awayZ, 0.4f, 0.4f, hgt - 0.6f);
            batch.include(free[0], free[1] + hgt, free[2], 2f);
            batch.place(null);
            return;
        }
        batch.include(a[0], a[1] + hgt + 4f, a[2], 4f);
        batch.include(b[0], b[1] + hgt + 4f, b[2], 4f);
        infra.recordSegment(from, to, 0.5f, hgt + 4f);
        MeshBuilder mesh = batch.builder("borderfence");
        mesh.setPlanarTextureScale(0.1f);
        // Chain-link: rails top, middle and bottom, and close-set wires between
        addPrism(mesh, a, b, 0.3f, 0.2f, 0.8f);
        addPrism(mesh, a, b, 0.3f, hgt * 0.5f - 0.3f, hgt * 0.5f + 0.3f);
        addPrism(mesh, a, b, 0.3f, hgt - 0.6f, hgt);
        uprights(mesh, a, b, dirX, dirZ, 0.9f, 0.06f, 0.2f, hgt);
        if (withPost) {
            addBox(mesh, a[0], a[1] - 1.5f, a[2], dirX, dirZ, 0.45f, 0.45f, hgt + 1.5f);
            // Its arm leaning out at the top, carrying the wire
            float[] foot = { a[0], a[1] + hgt, a[2] };
            float[] tip = { a[0] - dirZ * 2.4f, a[1] + hgt + 3.6f, a[2] + dirX * 2.4f };
            addRod(batch.builder("barbed"), foot, tip, 0.22f);
            float[] other = { a[0] + dirZ * 2.4f, a[1] + hgt + 3.6f, a[2] - dirX * 2.4f };
            addRod(batch.builder("barbed"), foot, other, 0.22f);
        }
        // Barbed wire: strands along the top of the arms on both sides, and a coil between them
        MeshBuilder wire = batch.builder("barbed");
        for (int strand = 0; strand < 3; strand++) {
            float out = 0.8f + strand * 0.8f, up = hgt + 1.2f + strand * 1.2f;
            for (float sideSign = -1f; sideSign <= 1f; sideSign += 2f) {
                float sx = -dirZ * out * sideSign, sz = dirX * out * sideSign;
                addRod(wire, new float[] { a[0] + sx, a[1] + up, a[2] + sz }, new float[] { b[0] + sx, b[1] + up, b[2] + sz }, 0.07f);
            }
        }
        // The coil: loops round the top of the fence, a few to each stretch
        int loops = Math.max(1, Math.round(length / 1.6f));
        float radius = 1.3f;
        float[] previous = null;
        int steps = loops * 8;
        for (int k = 0; k <= steps; k++) {
            float t = (float) k / steps;
            float angle = t * loops * (float) Math.PI * 2f;
            float cx = a[0] + (b[0] - a[0]) * t, cy = a[1] + (b[1] - a[1]) * t + hgt + 1.4f, cz = a[2] + (b[2] - a[2]) * t;
            float across = (float) Math.cos(angle) * radius, up = (float) Math.sin(angle) * radius;
            float[] p = { cx - dirZ * across, cy + up, cz + dirX * across };
            if (previous != null) addRod(wire, previous, p, 0.06f);
            previous = p;
        }
        batch.place(null);
    }
}
