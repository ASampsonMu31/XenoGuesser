import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.xenoguesser.math.Vector3;

/**
 * Bridges wherever a road crosses a river or a lake: found once along every path (bank to
 * bank), then their decks (which the road's height follows), and their meshes: a dirt
 * track's plank bridge, a made road's concrete beam on piers, or over wide water steel arches.
 */
final class Bridges extends InfraPart {

    Bridges(InfrastructureManager infra) {
        super(infra);
    }

    /** A bridge's deck under a place: the bridge, and its deck's height (its top) there (see bridgeDeckAt). */
    record Deck(Bridge bridge, float top) {
    }

    /** The height of the underside of a bridge's deck (as bridgeDeckAt gives it): its planks' logs, or its slab. */
    static float bridgeUnderside(Deck deck) {
        return deck.top() - (deck.bridge().kind == BridgeKind.PLANKS ? PLANK_THICK + LOG : SLAB);
    }

    // Each path's bridges, in order along it (worked out once the paths are laid)
    final Map<Integer, List<Bridge>> bridgesByPath = new HashMap<>();

    volatile boolean bridgesReady;

    // Along each path, looked at this often for water under it (world units); runs of it this close
    // together are one bridge, which reaches on to the banks: to where the ground stands BANK_ABOVE
    // over the water, and is either out of the river's channel (cut no deeper than BANK_CUT) or
    // BANK_HIGH over the water anyway (a valley's side), no more than BANK_SEARCH from the water,
    // and BRIDGE_MARGIN beyond that, so each end is well up on dry land
    private static final float BRIDGE_LOOK_STEP = 4f, BRIDGE_JOIN = 30f, BRIDGE_MARGIN = 10f;

    private static final float BANK_ABOVE = 4f, BANK_HIGH = 7f, BANK_CUT = 1.5f, BANK_SEARCH = 100f;

    // A plank bridge only joins dirt track to dirt track: it stops at least this far short of
    // either end of its track (where the track meets another road), else it's built as a beam
    private static final float PLANKS_FROM_JUNCTION = 15f;

    // How far over the water each kind's deck stands at least, and how much it humps for its length
    private static final float PLANK_CLEARANCE = 4f, BEAM_CLEARANCE = 8f, ARCH_CLEARANCE = 12f;

    private static final float PLANK_HUMP = 0.04f, BEAM_HUMP = 0.02f, ARCH_HUMP = 0.025f;

    // A bridge up to this long humps over the water in one curve; a longer one climbs from each
    // bank no steeper than BRIDGE_GRADE and runs level between
    private static final float HUMPED_BRIDGE = 250f, BRIDGE_GRADE = 0.12f;

    // A deck rises no more than this over the higher of its banks (or this share of its length, if more)
    private static final float MOST_OVER_BANKS = 20f, MOST_OVER_BANKS_SHARE = 0.1f;

    // A made road's bridge over this much water or more (or over a lake) is a steel arch bridge
    private static final float WIDE_BRIDGE = 200f;

    /** Finds where each path crosses a river or a lake, and lays a bridge over each, bank to bank. */
    void findBridges() {
        Rivers rivers = infra.terrainNoise.rivers;
        if (rivers == null) return;
        long started = System.currentTimeMillis();
        Map<BridgeKind, Integer> kinds = new java.util.EnumMap<>(BridgeKind.class);
        List<Map.Entry<Integer, List<RoadSegment>>> paths = new ArrayList<>(infra.segmentsByPath.entrySet());
        // (each path on its own, side by side)
        List<List<Bridge>> found = paths.parallelStream().map(entry -> bridgesAlong(entry.getValue(), rivers)).toList();
        int count = 0;
        for (int i = 0; i < paths.size(); i++) {
            List<Bridge> bridges = found.get(i);
            if (bridges.isEmpty()) continue;
            bridgesByPath.put(paths.get(i).getKey(), bridges);
            for (Bridge bridge : bridges) kinds.merge(bridge.kind, 1, Integer::sum);
            count += bridges.size();
        }
        bridgesReady = true;
        System.out.printf("[ROADS] %d bridges %s in %d ms (%d crossings with no land to reach, %d of them over a river or lake, left unbridged; %d too long left out; the longest %.0f, %d over 1500)%n", count, kinds,
                System.currentTimeMillis() - started, unanchoredBridges.get(), lostBridges.get(), tooLongBridges.get(), longestBridge, longBridges.get());
    }

    /** The bridges along one path (its segments in order). */
    private List<Bridge> bridgesAlong(List<RoadSegment> segments, Rivers rivers) {
        if (segments.isEmpty()) return List.of();
        boolean dirt = segments.get(0).roadClass == RoadPath.RoadClass.DIRT;
        // Along it every BRIDGE_LOOK_STEP or so: {how far along, the water under it (NaN none),
        // whether a lake's, the ground under its middle, how deep a river's cut it there}
        List<float[]> samples = new ArrayList<>();
        boolean any = false;
        for (RoadSegment segment : segments) {
            int steps = Math.max(1, (int) Math.ceil(segment.length / BRIDGE_LOOK_STEP));
            for (int s = samples.isEmpty() ? 0 : 1; s <= steps; s++) {
                float t = (float) s / steps;
                float x = segment.start.x + (segment.end.x - segment.start.x) * t, z = segment.start.z + (segment.end.z - segment.start.z) * t;
                SpherePosition middle = world(segment, x, z);
                float ground = TerrainMesh.getLayeredHeight(middle, infra.terrainNoise);
                float[] under = waterUnder(segment, t, rivers);
                samples.add(new float[] { segment.startDistance + t * segment.length, under == null ? Float.NaN : under[0],
                        under != null && under[1] > 0f ? 1f : 0f, ground, TerrainMesh.getBareHeight(middle, infra.terrainNoise) - ground });
                any |= under != null;
            }
        }
        if (!any) return List.of();
        // The wet stretches (close ones joined): {first sample, last sample, whether a lake's, the water's height}
        List<float[]> wet = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            float[] sample = samples.get(i);
            if (Float.isNaN(sample[1])) continue;
            float[] open = wet.isEmpty() ? null : wet.get(wet.size() - 1);
            if (open != null && sample[0] - samples.get((int) open[1])[0] <= BRIDGE_JOIN) {
                open[1] = i;
                open[2] = Math.max(open[2], sample[2]);
                open[3] = Math.max(open[3], sample[1]);
            } else {
                wet.add(new float[] { i, i, sample[2], sample[1] });
            }
        }
        // Each from bank to bank: out from its water to where the land is (those that come to meet made one)
        List<float[]> spans = new ArrayList<>();
        for (float[] w : wet) {
            int first = (int) w[0], last = (int) w[1];
            float water = w[3];
            float waterFrom = samples.get(first)[0], waterTo = samples.get(last)[0];
            while (first > 0 && waterFrom - samples.get(first - 1)[0] <= BANK_SEARCH && !isBank(samples.get(first - 1), water)) first--;
            while (last + 1 < samples.size() && samples.get(last + 1)[0] - waterTo <= BANK_SEARCH && !isBank(samples.get(last + 1), water)) last++;
            // (M54) No firm bank that near: the first dry ground beside the water within the same
            // reach will do (M55: no further, or a bridge runs on along low ground by the river
            // high in the air)
            if (first > 0 && !isBank(samples.get(first - 1), water)) {
                int k = (int) w[0];
                while (k > 0 && waterFrom - samples.get(k - 1)[0] <= BANK_SEARCH && !isDryAbove(samples.get(k - 1), water)) k--;
                if (k > 0 && isDryAbove(samples.get(k - 1), water)) first = k;
            }
            if (last + 1 < samples.size() && !isBank(samples.get(last + 1), water)) {
                int k = (int) w[1];
                while (k + 1 < samples.size() && samples.get(k + 1)[0] - waterTo <= BANK_SEARCH && !isDryAbove(samples.get(k + 1), water)) k++;
                if (k + 1 < samples.size() && isDryAbove(samples.get(k + 1), water)) last = k;
            }
            // A bridge must reach dry land at both ends (or the end of its path, a junction where
            // the next path's bridge carries on): where no land's found, none, rather than one
            // stopping short over the water
            boolean startsOnLand = first == 0 || isDryAbove(samples.get(first - 1), water);
            boolean endsOnLand = last + 1 >= samples.size() || isDryAbove(samples.get(last + 1), water);
            if (!startsOnLand || !endsOnLand) {
                unanchoredBridges.incrementAndGet();
                float f0 = Math.max(0f, samples.get(Math.max(0, first - 1))[0] - BRIDGE_MARGIN);
                float t0 = Math.min(samples.get(samples.size() - 1)[0], samples.get(Math.min(samples.size() - 1, last + 1))[0] + BRIDGE_MARGIN);
                if (w[2] > 0f || crossesRiver(segments, f0, t0, rivers)) lostBridges.incrementAndGet();
                continue;
            }
            float from = Math.max(0f, samples.get(Math.max(0, first - 1))[0] - BRIDGE_MARGIN);
            float to = Math.min(samples.get(samples.size() - 1)[0], samples.get(Math.min(samples.size() - 1, last + 1))[0] + BRIDGE_MARGIN);
            float[] span = { from, to, w[2], waterTo - waterFrom };
            float[] previous = spans.isEmpty() ? null : spans.get(spans.size() - 1);
            if (previous != null && span[0] <= previous[1]) {
                previous[1] = Math.max(previous[1], span[1]);
                previous[2] = Math.max(previous[2], span[2]);
                previous[3] += span[3];
            } else {
                spans.add(span);
            }
        }
        List<Bridge> bridges = new ArrayList<>();
        for (float[] span : spans) {
            float from = span[0], to = span[1], length = to - from;
            if (length < 1f) continue;
            // (M55) A bridge is a crossing: over the water and straight down on the far side. One
            // that would run on far longer than the water's width (a road along the river, not
            // across it) isn't built
            if (length > Math.max(MOST_BRIDGE, span[3] * 2f + 2f * (BANK_SEARCH + BRIDGE_MARGIN))) {
                tooLongBridges.incrementAndGet();
                continue;
            }
            // A river's bridge goes from one bank to the other: where the road only runs along
            // beside the water (not over a lake) and never crosses the river's middle, none
            if (span[2] <= 0f && !crossesRiver(segments, from, to, rivers)) continue;
            // (planks only between stretches of dirt track; anything touching a made road is built for it)
            float pathLength = samples.get(samples.size() - 1)[0];
            BridgeKind kind = dirt && dirtBetween(segments, from, to, pathLength) ? BridgeKind.PLANKS
                    : span[3] >= WIDE_BRIDGE || span[2] > 0f ? BridgeKind.ARCHES : BridgeKind.BEAM;
            float hump = length * (kind == BridgeKind.PLANKS ? PLANK_HUMP : kind == BridgeKind.BEAM ? BEAM_HUMP : ARCH_HUMP);
            float deckFrom = groundedDeck(segments, from), deckTo = groundedDeck(segments, to);
            float rise, ramp;
            if (length <= HUMPED_BRIDGE) {
                // One curve over the water, as high as it needs
                ramp = 0.5f;
                rise = 0f;
                hump = Math.max(hump, neededRise(samples, from, to, deckFrom, deckTo, -1f, kind));
            } else {
                // Up from each bank to a level run over the water
                ramp = 0.25f;
                rise = 0f;
                for (int pass = 0; pass < 3; pass++) {
                    rise = Math.max(0f, neededRise(samples, from, to, deckFrom, deckTo, ramp, kind) - hump);
                    ramp = Math.max(0.05f, Math.min(0.5f, rise / BRIDGE_GRADE / length));
                }
            }
            // (never far over its banks: water that seems to need it isn't really between them)
            float most = Math.max(MOST_OVER_BANKS, length * MOST_OVER_BANKS_SHARE);
            float over = rise + hump + Math.abs(deckTo - deckFrom) * 0.5f;
            if (over > most) {
                float scale = Math.max(0f, most - Math.abs(deckTo - deckFrom) * 0.5f) / Math.max(1e-3f, rise + hump);
                rise *= scale;
                hump *= scale;
            }
            bridges.add(new Bridge(from, to, deckFrom, deckTo, rise, ramp, hump, kind));
            longestBridge = Math.max(longestBridge, length);
            if (length > 1500f) longBridges.incrementAndGet();
        }
        return bridges;
    }

    /** Whether a path between two distances along it crosses a river's middle (from one bank to the other), looked at in short steps along the road itself. */
    private boolean crossesRiver(List<RoadSegment> segments, float from, float to, Rivers rivers) {
        int steps = Math.max(1, (int) Math.ceil((to - from) / RIVER_CROSS_STEP));
        GeodesicChunkGrid.Direction previous = SphereMath.direction(pointAlong(segments, from));
        for (int k = 1; k <= steps; k++) {
            GeodesicChunkGrid.Direction next = SphereMath.direction(pointAlong(segments, from + (to - from) * k / steps));
            float[] crossed = rivers.crossing(previous, next);
            if (crossed != null && crossed[0] > 0f) return true;
            previous = next;
        }
        return false;
    }

    // How finely a would-be bridge is looked along for the river's middle
    private static final float RIVER_CROSS_STEP = 6f;

    /** The place a distance along a path (its segments in order), on its centreline. */
    private SpherePosition pointAlong(List<RoadSegment> segments, float along) {
        for (RoadSegment segment : segments) {
            if (along > segment.startDistance + segment.length + 1e-3f && segment != segments.get(segments.size() - 1)) continue;
            float t = Math.max(0f, Math.min(1f, (along - segment.startDistance) / segment.length));
            return world(segment, segment.start.x + (segment.end.x - segment.start.x) * t, segment.start.z + (segment.end.z - segment.start.z) * t);
        }
        return world(segments.get(0), segments.get(0).start.x, segments.get(0).start.z);
    }

    // How many crossings had no land to reach, and how long the longest bridge is
    private final java.util.concurrent.atomic.AtomicInteger unanchoredBridges = new java.util.concurrent.atomic.AtomicInteger(), lostBridges = new java.util.concurrent.atomic.AtomicInteger();

    private volatile float longestBridge;

    // The longest a bridge may be over a narrow river (a wide one's may be as long as it needs)
    private static final float MOST_BRIDGE = 450f;

    private final java.util.concurrent.atomic.AtomicInteger tooLongBridges = new java.util.concurrent.atomic.AtomicInteger();

    private final java.util.concurrent.atomic.AtomicInteger longBridges = new java.util.concurrent.atomic.AtomicInteger();

    /** Whether a sample along a road is dry ground beside the water (not under it, nor below it). */
    private static boolean isDryAbove(float[] sample, float water) {
        return Float.isNaN(sample[1]) && sample[3] >= water;
    }

    /** Whether a sample along a road (see bridgesAlong) is firm land beside water: well above it, and out of the river's cut or higher still. */
    private static boolean isBank(float[] sample, float water) {
        return Float.isNaN(sample[1]) && sample[3] >= water + BANK_ABOVE && (sample[4] <= BANK_CUT || sample[3] >= water + BANK_HIGH);
    }

    /** Whether a stretch of a path (from and to along it) is dirt track all the way, ending short of either end of the path. */
    private static boolean dirtBetween(List<RoadSegment> segments, float from, float to, float pathLength) {
        if (from < PLANKS_FROM_JUNCTION || to > pathLength - PLANKS_FROM_JUNCTION) return false;
        for (RoadSegment segment : segments) {
            if (segment.startDistance + segment.length < from || segment.startDistance > to) continue;
            if (segment.roadClass != RoadPath.RoadClass.DIRT) return false;
        }
        return true;
    }

    /**
     * How high a bridge between from and to along a path (its deck from deckFrom to deckTo)
     * must rise to clear the water under it (samples, see bridgesAlong) by its kind's
     * clearance: humped in one curve if ramp is negative, else over all but ramp (a share of its
     * length) at each end.
     */
    private float neededRise(List<float[]> samples, float from, float to, float deckFrom, float deckTo, float ramp, BridgeKind kind) {
        float clearance = kind == BridgeKind.PLANKS ? PLANK_CLEARANCE : kind == BridgeKind.BEAM ? BEAM_CLEARANCE : ARCH_CLEARANCE;
        float rise = 0f;
        for (float[] sample : samples) {
            if (sample[0] < from || sample[0] > to || Float.isNaN(sample[1])) continue;
            float f = (sample[0] - from) / (to - from);
            float lift = ramp < 0f ? 4f * f * (1f - f) : Bridge.lift(f, ramp);
            float excess = sample[1] + clearance - (deckFrom + (deckTo - deckFrom) * f);
            if (excess > 0f) rise = Math.max(rise, excess / Math.max(0.15f, lift));
        }
        return rise;
    }

    /**
     * Whether there's water (a river's or a lake's) under a road a share t along a segment,
     * anywhere across its width: {the water's height, 1 if it's a lake's else 0}; null if not.
     */
    private float[] waterUnder(RoadSegment segment, float t, Rivers rivers) {
        float x = segment.start.x + (segment.end.x - segment.start.x) * t, z = segment.start.z + (segment.end.z - segment.start.z) * t;
        Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, t);
        float half = segment.width * 0.5f;
        float[] found = null;
        for (float across : new float[] { 0f, -half, half, -half * 0.5f, half * 0.5f }) {
            SpherePosition place = world(segment, x - tangent.z * across, z + tangent.x * across);
            float ground = TerrainMesh.getLayeredHeight(place, infra.terrainNoise);
            float water = rivers.waterAt(SphereMath.direction(place), ground);
            boolean lake = !Float.isNaN(water) && rivers.lakeAt(SphereMath.direction(place).x(), SphereMath.direction(place).y(),
                    SphereMath.direction(place).z()) >= 0;
            if (Float.isNaN(water)) continue;
            if (found == null) found = new float[] { water, lake ? 1f : 0f };
            else {
                found[0] = Math.max(found[0], water);
                if (lake) found[1] = 1f;
            }
        }
        return found;
    }

    /** The road's height a distance along a path as it'd be with no bridge: a track's on the ground, a made road's deck. */
    private float groundedDeck(List<RoadSegment> segments, float along) {
        for (RoadSegment segment : segments) {
            if (along > segment.startDistance + segment.length + 1e-3f && segment != segments.get(segments.size() - 1)) continue;
            float t = Math.max(0f, Math.min(1f, (along - segment.startDistance) / segment.length));
            float x = segment.start.x + (segment.end.x - segment.start.x) * t, z = segment.start.z + (segment.end.z - segment.start.z) * t;
            return segment.roadClass == RoadPath.RoadClass.DIRT ? groundAt(segment, x, z) + RoadGeometry.TRACK_LIFT : infra.roadGeometry.groundedRoadSurfaceY(world(segment, x, z), true);
        }
        return 0f;
    }

    /** The bridge of a segment's path a distance along it, if any. */
    Bridge bridgeAt(RoadSegment segment, float along) {
        if (!bridgesReady) return null;
        List<Bridge> bridges = bridgesByPath.get(segment.pathIndex);
        if (bridges == null) return null;
        for (Bridge bridge : bridges) if (bridge.holds(along)) return bridge;
        return null;
    }

    /** A bridge's deck under a place on it (within its road's width); null if it's on none. */
    Deck bridgeDeckAt(SpherePosition place, float margin) {
        if (!bridgesReady) return null;
        // (of the segments it's within reach of, the nearest: the one it's on as drawn, see drawnRoadY)
        Deck nearest = null;
        float nearestDistance2 = Float.MAX_VALUE;
        for (RoadSegment segment : infra.segmentsNear(place, MAX_ROAD_HALF_WIDTH + margin + BRIDGE_WIDER)) {
            if (!bridgesByPath.containsKey(segment.pathIndex)) continue;
            double[] p = segment.frame.fromSphere(place);
            float ux = segment.end.x - segment.start.x, uz = segment.end.z - segment.start.z;
            float length2 = ux * ux + uz * uz;
            float raw = length2 > 0f ? (float) (((p[0] - segment.start.x) * ux + (p[2] - segment.start.z) * uz) / length2) : 0f;
            float t = Math.max(0f, Math.min(1f, raw));
            float dx = (float) p[0] - (segment.start.x + ux * t), dz = (float) p[2] - (segment.start.z + uz * t);
            // (as wide as the bridge is built: its planks' overhang, its parapets or its ribs past
            // the road's edge, and a little more for the corners where its segments meet)
            float half = segment.width * 0.5f + margin + BRIDGE_WIDER;
            if (dx * dx + dz * dz > half * half) continue;
            // (how far along the path the place is, not the nearest end's distance: a place
            // beyond a segment's end, as a wide road's next segment reaches back half its width
            // along the road, would otherwise take the deck's height there, a step on a hump)
            float along = segment.startDistance + raw * segment.length;
            Bridge bridge = bridgeAt(segment, along);
            if (bridge == null) continue;
            float wide = segment.width * 0.5f + margin + bridgeOverhang(bridge.kind) + 1f;
            float distance2 = dx * dx + dz * dz;
            if (distance2 <= wide * wide && distance2 < nearestDistance2) {
                nearest = new Deck(bridge, bridge.deck(along));
                nearestDistance2 = distance2;
            }
        }
        return nearest;
    }

    // The most any bridge reaches past its road's edge (see bridgeOverhang), and a little more
    private static final float BRIDGE_WIDER = 5f;

    /** How far a bridge's deck reaches past its road's edge: a plank bridge's planks, a beam bridge's parapets, an arch bridge's ribs. */
    private static float bridgeOverhang(BridgeKind kind) {
        return switch (kind) {
            case PLANKS -> PLANK_OVERHANG;
            case BEAM -> PARAPET_THICK;
            case ARCHES -> RIB;
        };
    }

    /** Where a segment's centreline is a distance along its path, in its frame: {x, z, way along x, way along z}. */
    float[] centreAt(RoadSegment segment, float along) {
        float t = Math.max(0f, Math.min(1f, (along - segment.startDistance) / segment.length));
        Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, t);
        float tl = (float) Math.sqrt(tangent.x * tangent.x + tangent.z * tangent.z);
        return new float[] { segment.start.x + (segment.end.x - segment.start.x) * t, segment.start.z + (segment.end.z - segment.start.z) * t,
                tangent.x / tl, tangent.z / tl };
    }

    /** The bridges (or their parts) on a segment. */
    void appendBridges(NationBatch batch, RoadSegment segment) {
        List<Bridge> bridges = bridgesReady ? bridgesByPath.get(segment.pathIndex) : null;
        if (bridges == null) return;
        float segFrom = segment.startDistance, segTo = segment.startDistance + segment.length;
        for (Bridge bridge : bridges) {
            float from = Math.max(bridge.from, segFrom), to = Math.min(bridge.to, segTo);
            if (to <= from) continue;
            switch (bridge.kind) {
                case PLANKS -> appendPlankBridge(batch, segment, bridge, from, to);
                case BEAM -> appendBeamBridge(batch, segment, bridge, from, to);
                case ARCHES -> appendArchBridge(batch, segment, bridge, from, to);
            }
        }
    }

    /** Points along a bridge's stretch of a segment from from to to, about step apart (both ends included). */
    private static float[] stations(float from, float to, float step) {
        int n = Math.max(1, (int) Math.ceil((to - from) / step));
        float[] at = new float[n + 1];
        for (int i = 0; i <= n; i++) at[i] = from + (to - from) * i / n;
        return at;
    }

    /** A point beside the centreline (side: + right, - left, world units out) at a distance along, at height y: {x, y, z}. */
    private float[] beside(RoadSegment segment, float along, float side, float y) {
        float[] c = centreAt(segment, along);
        return new float[] { c[0] - c[3] * side, y, c[1] + c[2] * side };
    }

    // A plank bridge: planks this far apart, this thick, a little wider than the track, on two
    // logs beneath, with posts and a rail along each side
    static final float PLANK_PITCH = 3.2f, PLANK_GAP = 0.5f, PLANK_THICK = 0.8f, PLANK_OVERHANG = 2f;

    private static final float LOG = 3f, POST_SPACING = 30f, POST = 1.2f, HANDRAIL = 9f;

    /** A dirt track's bridge: planks across two logs, posts down to the ground and a rough rail. */
    private void appendPlankBridge(NationBatch batch, RoadSegment segment, Bridge bridge, float from, float to) {
        MeshBuilder wood = batch.builder("wood");
        float half = segment.width * 0.5f;
        // Planks, each at its own place along the path (so they run on from segment to segment)
        for (float along = bridge.from + (float) Math.ceil((from - bridge.from) / PLANK_PITCH) * PLANK_PITCH; along < to; along += PLANK_PITCH) {
            float[] c = centreAt(segment, along);
            float top = bridge.deck(along);
            // (each a little askew and of its own length, as rough planks are)
            long seed = Float.floatToIntBits(along) * 31L + segment.pathIndex;
            float jitter = ((seed * 0x9E3779B9L >>> 20) & 255) / 255f - 0.5f;
            addBox(wood, c[0] - c[3] * jitter * 1.2f, top - PLANK_THICK, c[1] + c[2] * jitter * 1.2f, c[3], -c[2],
                    half + PLANK_OVERHANG + jitter, (PLANK_PITCH - PLANK_GAP) * 0.5f, PLANK_THICK);
            batch.include(c[0], top, c[1], half + PLANK_OVERHANG + 1f);
        }
        float[] at = stations(from, to, 6f);
        for (float side : new float[] { -1f, 1f }) {
            float out = (half - 1f) * side;
            for (int i = 0; i + 1 < at.length; i++) {
                // The log under the planks
                float[] a = beside(segment, at[i], out, bridge.deck(at[i]) - PLANK_THICK);
                float[] b = beside(segment, at[i + 1], out, bridge.deck(at[i + 1]) - PLANK_THICK);
                addPrism(wood, a, b, LOG, -LOG, 0f);
                // The rail along the top of the posts (not across another road meeting it)
                if (bridge.length() > 2f * POST_SPACING && !railOverRoad(segment, at[i], at[i + 1], (half + PLANK_OVERHANG * 0.5f) * side)) {
                    float[] ra = beside(segment, at[i], (half + PLANK_OVERHANG * 0.5f) * side, bridge.deck(at[i]) + HANDRAIL);
                    float[] rb = beside(segment, at[i + 1], (half + PLANK_OVERHANG * 0.5f) * side, bridge.deck(at[i + 1]) + HANDRAIL);
                    addPrism(wood, ra, rb, 0.8f, -0.8f, 0f);
                }
            }
            if (bridge.length() > 2f * POST_SPACING) recordRailing(segment, bridge, at, (half + PLANK_OVERHANG * 0.5f) * side, HANDRAIL, 1f);
            // Posts: down to the ground under the logs, and up to the rail
            for (float along = bridge.from + (float) Math.ceil((from - bridge.from) / POST_SPACING) * POST_SPACING; along <= to; along += POST_SPACING) {
                float[] p = beside(segment, along, (half + PLANK_OVERHANG * 0.5f) * side, 0f);
                if (railOverRoad(segment, along, along, (half + PLANK_OVERHANG * 0.5f) * side)) continue;
                float ground = groundAt(segment, p[0], p[2]);
                float top = bridge.deck(along) + (bridge.length() > 2f * POST_SPACING ? HANDRAIL : 0f);
                if (top - ground > 0.5f) addBox(wood, p[0], ground - 2f, p[2], 1f, 0f, POST, POST, top - ground + 2f);
            }
        }
    }

    /**
     * A bridge's railing (its handrail, parapet or ribs) as solid, along one side between stations
     * along the path, a distance across from the middle: from just under the deck up to height over
     * it, so it stops those on the deck and lets those walking beneath it pass under.
     */
    private void recordRailing(RoadSegment segment, Bridge bridge, float[] at, float across, float height, float halfThick) {
        for (int i = 0; i + 1 < at.length; i++) {
            if (railOverRoad(segment, at[i], at[i + 1], across)) continue;
            float[] a = beside(segment, at[i], across, 0f), b = beside(segment, at[i + 1], across, 0f);
            float middle = (at[i] + at[i + 1]) * 0.5f;
            float[] m = beside(segment, middle, across, 0f);
            float ground = groundAt(segment, m[0], m[2]), deck = Math.max(bridge.deck(at[i]), bridge.deck(at[i + 1]));
            infra.recordRaised(world(segment, a[0], a[2]), world(segment, b[0], b[2]), halfThick + 0.3f, deck + height - ground, deck - 2f - ground);
        }
    }

    /**
     * Whether a piece of a bridge's railing, between two distances along its path a distance
     * across from its middle, would stand on another road (one meeting the bridge from the side):
     * there it's left out, so the road isn't barred.
     */
    private boolean railOverRoad(RoadSegment segment, float fromAlong, float toAlong, float across) {
        for (float t : new float[] { 0f, 0.5f, 1f }) {
            float[] p = beside(segment, fromAlong + (toAlong - fromAlong) * t, across, 0f);
            SpherePosition place = world(segment, p[0], p[2]);
            for (RoadSegment other : infra.segmentsNear(place, MAX_ROAD_HALF_WIDTH + RAIL_ROAD_CLEAR)) {
                if (other.pathIndex == segment.pathIndex) continue;
                float limit = other.width * 0.5f + RAIL_ROAD_CLEAR;
                if (distanceSquaredToSegment(place, other) <= limit * limit) return true;
            }
        }
        return false;
    }

    // A bridge's railing stops this far short of the edge of another road meeting it
    private static final float RAIL_ROAD_CLEAR = 2f;

    /** The height of the deck of a bridge at a place (anywhere on it, as walked on), NaN where there's none. */
    public float bridgeDeckY(SpherePosition place) {
        Deck deck = bridgeDeckAt(SphereMath.atAltitude(place, 0.0), 0f);
        return deck == null ? Float.NaN : deck.top();
    }

    // A beam bridge: a slab this deep under the deck, with parapets this high and thick along its
    // edges, on piers no further apart than this
    private static final float SLAB = 12f, PARAPET = 9f, PARAPET_THICK = 2.5f, PIER_SPACING = 150f;

    /** A made road's short bridge: a concrete slab with parapets, on piers where it's long. */
    private void appendBeamBridge(NationBatch batch, RoadSegment segment, Bridge bridge, float from, float to) {
        MeshBuilder concrete = batch.builder("roadwall");
        float half = segment.width * 0.5f;
        float[] at = stations(from, to, 6f);
        appendSlab(batch, concrete, segment, bridge, at, half);
        for (float side : new float[] { -1f, 1f }) {
            for (int i = 0; i + 1 < at.length; i++) {
                if (railOverRoad(segment, at[i], at[i + 1], (half + PARAPET_THICK * 0.5f) * side)) continue;
                float[] a = beside(segment, at[i], (half + PARAPET_THICK * 0.5f) * side, bridge.deck(at[i]));
                float[] b = beside(segment, at[i + 1], (half + PARAPET_THICK * 0.5f) * side, bridge.deck(at[i + 1]));
                addPrism(concrete, a, b, PARAPET_THICK, -SLAB, PARAPET);
                // (a coping along its top)
                addPrism(concrete, a, b, PARAPET_THICK + 1f, PARAPET, PARAPET + 1f);
            }
            recordRailing(segment, bridge, at, (half + PARAPET_THICK * 0.5f) * side, PARAPET + 1f, PARAPET_THICK * 0.5f);
        }
        appendPiers(concrete, segment, bridge, from, to, half * 0.8f, 5f);
    }

    /** The slab under a bridge's deck: its underside and, along each edge, its face. */
    private void appendSlab(NationBatch batch, MeshBuilder builder, RoadSegment segment, Bridge bridge, float[] at, float half) {
        for (int i = 0; i + 1 < at.length; i++) {
            float ya = bridge.deck(at[i]) - SLAB, yb = bridge.deck(at[i + 1]) - SLAB;
            float[] la = beside(segment, at[i], -half, ya), ra = beside(segment, at[i], half, ya);
            float[] lb = beside(segment, at[i + 1], -half, yb), rb = beside(segment, at[i + 1], half, yb);
            int v = builder.addVertex(la[0], la[1], la[2], 0f, -1f, 0f, 0f, 0f);
            builder.addVertex(ra[0], ra[1], ra[2], 0f, -1f, 0f, 1f, 0f);
            builder.addVertex(lb[0], lb[1], lb[2], 0f, -1f, 0f, 0f, 1f);
            builder.addVertex(rb[0], rb[1], rb[2], 0f, -1f, 0f, 1f, 1f);
            builder.addTriangle(v, v + 2, v + 1);
            builder.addTriangle(v + 1, v + 2, v + 3);
            // Its top, just under where the road's surface goes (M54: so the deck's always seen
            // where it's walked on, the road over it or not)
            float ta = bridge.deck(at[i]) - ROAD_SURFACE_OFFSET * 1.5f, tb = bridge.deck(at[i + 1]) - ROAD_SURFACE_OFFSET * 1.5f;
            float[] lta = beside(segment, at[i], -half, ta), rta = beside(segment, at[i], half, ta);
            float[] ltb = beside(segment, at[i + 1], -half, tb), rtb = beside(segment, at[i + 1], half, tb);
            int t = builder.addVertex(lta[0], lta[1], lta[2], 0f, 1f, 0f, 0f, 0f);
            builder.addVertex(rta[0], rta[1], rta[2], 0f, 1f, 0f, 1f, 0f);
            builder.addVertex(ltb[0], ltb[1], ltb[2], 0f, 1f, 0f, 0f, 1f);
            builder.addVertex(rtb[0], rtb[1], rtb[2], 0f, 1f, 0f, 1f, 1f);
            builder.addTriangle(t, t + 1, t + 2);
            builder.addTriangle(t + 1, t + 3, t + 2);
            for (float side : new float[] { -1f, 1f }) {
                float[] a = beside(segment, at[i], half * side, bridge.deck(at[i]));
                float[] b = beside(segment, at[i + 1], half * side, bridge.deck(at[i + 1]));
                addPrism(builder, a, b, 1f, -SLAB, -ROAD_SURFACE_OFFSET);
            }
            batch.include(la[0], la[1], la[2], half + 4f);
        }
    }

    /** Piers under a bridge, evenly between its ends no further apart than PIER_SPACING, those on this stretch of it. */
    private void appendPiers(MeshBuilder builder, RoadSegment segment, Bridge bridge, float from, float to, float halfAcross, float halfAlong) {
        int bays = Math.max(1, (int) Math.ceil(bridge.length() / PIER_SPACING));
        for (int p = 1; p < bays; p++) {
            float along = bridge.from + bridge.length() * p / bays;
            if (along < from || along >= to) continue;
            float[] c = centreAt(segment, along);
            float ground = Math.min(groundAt(segment, c[0], c[1]), Math.min(groundAt(segment, c[0] - c[3] * halfAcross, c[1] + c[2] * halfAcross),
                    groundAt(segment, c[0] + c[3] * halfAcross, c[1] - c[2] * halfAcross)));
            float top = bridge.deck(along) - SLAB;
            if (top - ground < 1f) continue;
            addBox(builder, c[0], ground - 4f, c[1], c[2], c[3], halfAlong, halfAcross, top - ground + 4f);
        }
    }

    // A steel arch bridge: arches over bays about this long, this high for their length (between
    // these), their ribs this thick; hangers this far apart; lamps this far apart and this tall
    private static final float ARCH_BAY = 400f, ARCH_RISE = 0.2f, LEAST_ARCH = 40f, MOST_ARCH = 140f, RIB = 4f;

    private static final float HANGER_SPACING = 16f, LAMP_SPACING = 120f, LAMP_HEIGHT = 45f;

    /**
     * A made road's wide bridge: a steel deck between two steel arches, one over each bay, the
     * deck hung from them, braced across their crowns and trussed between rib and deck; stone
     * piers between bays and towers at each end, with lamps along it.
     */
    private void appendArchBridge(NationBatch batch, RoadSegment segment, Bridge bridge, float from, float to) {
        MeshBuilder steel = batch.builder("steel"), stone = batch.builder("finery"), lamp = batch.builder("lamp");
        float half = segment.width * 0.5f, ribOut = half + RIB;
        int bays = Math.max(1, Math.round(bridge.length() / ARCH_BAY));
        float bay = bridge.length() / bays;
        float rise = Math.max(LEAST_ARCH, Math.min(MOST_ARCH, bay * ARCH_RISE));
        float[] at = stations(from, to, 6f);
        appendSlab(batch, steel, segment, bridge, at, half + RIB);
        java.util.function.DoubleUnaryOperator arch = along -> {
            double f = ((along - bridge.from) % bay) / bay;
            return bridge.deck((float) along) + rise * Math.sin(Math.PI * f);
        };
        for (float side : new float[] { -1f, 1f }) {
            float out = ribOut * side;
            for (int i = 0; i + 1 < at.length; i++) {
                // The rib, and a lighter chord along the deck's edge
                if (railOverRoad(segment, at[i], at[i + 1], out)) continue;
                float[] a = beside(segment, at[i], out, (float) arch.applyAsDouble(at[i]));
                float[] b = beside(segment, at[i + 1], out, (float) arch.applyAsDouble(at[i + 1]));
                addPrism(steel, a, b, RIB, -RIB, RIB * 0.5f);
                float[] da = beside(segment, at[i], out, bridge.deck(at[i]));
                float[] db = beside(segment, at[i + 1], out, bridge.deck(at[i + 1]));
                addPrism(steel, da, db, RIB * 0.6f, -1f, 4f);
                batch.include(a[0], a[1], a[2], RIB * 2f);
            }
            // (the ribs and their hangers fence the deck in, as high as anyone)
            recordRailing(segment, bridge, at, out, 25f, RIB * 0.5f);
            // Hangers from the rib down to the deck, braced corner to corner between them
            float first = bridge.from + (float) Math.ceil((from - bridge.from) / HANGER_SPACING) * HANGER_SPACING;
            for (float along = first; along <= to; along += HANGER_SPACING) {
                float deck = bridge.deck(along), top = (float) arch.applyAsDouble(along);
                if (top - deck < 3f || railOverRoad(segment, along, along, out)) continue;
                float[] base = beside(segment, along, out, deck);
                float[] c = centreAt(segment, along);
                addBox(steel, base[0], deck, base[2], c[2], c[3], 0.8f, 0.8f, top - deck);
                float next = along + HANGER_SPACING;
                if (next <= bridge.to && ((int) Math.round((along - bridge.from) / HANGER_SPACING)) % 2 == 0) {
                    float nextTop = (float) arch.applyAsDouble(next);
                    if (nextTop - bridge.deck(next) > 3f) {
                        addPrism(steel, new float[] { base[0], deck + 3f, base[2] }, beside(segment, next, out, nextTop - 1f), 0.8f, -0.8f, 0.8f);
                    }
                }
            }
            // Lamps along the parapet
            for (float along = bridge.from + LAMP_SPACING * 0.5f; along < bridge.to; along += LAMP_SPACING) {
                if (along < from || along >= to) continue;
                if (railOverRoad(segment, along, along, (half + RIB * 2.2f) * side)) continue;
                float[] p = beside(segment, along, (half + RIB * 2.2f) * side, bridge.deck(along));
                appendLamp(steel, lamp, p, LAMP_HEIGHT);
            }
        }
        // Across between the ribs at each crown, a brace with a crest on it
        for (int b = 0; b < bays; b++) {
            float crown = bridge.from + bay * (b + 0.5f);
            if (crown < from || crown >= to || rise < 25f) continue;
            float top = (float) arch.applyAsDouble(crown);
            float[] l = beside(segment, crown, -ribOut, top), r = beside(segment, crown, ribOut, top);
            addPrism(steel, l, r, RIB, -RIB, RIB * 0.5f);
            float[] c = centreAt(segment, crown);
            addBox(stone, c[0], top + RIB * 0.5f, c[1], c[2], c[3], 3f, 3f, 8f);
            addBox(lamp, c[0], top + RIB * 0.5f + 8f, c[1], c[2], c[3], 1.5f, 1.5f, 3f);
            // (and lighter braces out to the quarter points)
            for (float q : new float[] { 0.25f, 0.75f }) {
                float along = bridge.from + bay * (b + q);
                if (along < from || along >= to) continue;
                float y = (float) arch.applyAsDouble(along);
                if (y - bridge.deck(along) < 25f) continue;
                addPrism(steel, beside(segment, along, -ribOut, y), beside(segment, along, ribOut, y), 1.6f, -1.6f, 1.6f);
            }
        }
        // Stone piers between the bays, and a tower at each end of the bridge on either side
        appendPiers(stone, segment, bridge, from, to, half + RIB, 8f);
        for (float end : new float[] { bridge.from, bridge.to }) {
            if (end < from || end > to || (end == to && to < bridge.to)) continue;
            for (float side : new float[] { -1f, 1f }) {
                float[] p = beside(segment, end, (ribOut + 2f) * side, 0f);
                float[] c = centreAt(segment, end);
                float ground = Math.min(groundAt(segment, p[0], p[2]), bridge.deck(end)) - 4f;
                float top = bridge.deck(end) + 30f;
                addBox(stone, p[0], ground, p[2], c[2], c[3], 5f, 5f, top - ground);
                addBox(stone, p[0], top, p[2], c[2], c[3], 6.5f, 6.5f, 2f);
                appendLamp(steel, lamp, new float[] { p[0], top + 2f, p[2] }, 18f);
                batch.include(p[0], top, p[2], 20f);
            }
        }
    }

    /** A lamp post standing at a point: a slender post with a lantern and a cap. */
    private void appendLamp(MeshBuilder post, MeshBuilder light, float[] at, float height) {
        addBox(post, at[0], at[1], at[2], 1f, 0f, 1.6f, 1.6f, 2f);
        addBox(post, at[0], at[1], at[2], 1f, 0f, 0.7f, 0.7f, height);
        addBox(light, at[0], at[1] + height, at[2], 1f, 0f, 1.8f, 1.8f, 4f);
        addBox(post, at[0], at[1] + height + 4f, at[2], 1f, 0f, 2.4f, 2.4f, 1f);
    }
}
