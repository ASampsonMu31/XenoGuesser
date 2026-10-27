import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.xenoguesser.math.Vector3;

/**
 * The roads' own geometry: how high each segment's deck stands (level across, meeting greater
 * roads at junctions, on supports or walls where the ground falls away), where a road is as
 * drawn (for walking and lookups), and the meshes: asphalt and dirt tracks, the walls and
 * pillars under raised roads, the painted lines and give-way markings, turning circles, and
 * highway guard rails.
 */
final class RoadGeometry extends InfraPart {

    RoadGeometry(InfrastructureManager infra) {
        super(infra);
    }

    /**
     * The height of the road deck at a point on a road's centreline: level with the highest
     * ground across the road's width (the half-width of the widest road there), so the road
     * is flat across, with no sideways camber: its higher side meets the ground and its lower
     * side is built up on a wall. Small dips are bridged. It depends only on where the point
     * is, so roads meeting at a junction meet at the same height. Roads ride a low causeway
     * wherever they dip below the sea; over a river or a lake they're on a bridge (see Bridge).
     */
    float roadSurfaceY(SpherePosition place) {
        Bridges.Deck bridge = infra.bridges.bridgeDeckAt(place, 1f);
        if (bridge != null) return bridge.top();
        return groundedRoadSurfaceY(place, infra.roadNetworkInitialized);
    }

    /** The road's height at a place as it'd be with no bridge (see roadSurfaceY); widened by the roads there if bySegments. */
    float groundedRoadSurfaceY(SpherePosition place, boolean bySegments) {
        float reach = RoadPath.RoadClass.LANE.width * 0.5f;
        if (bySegments) {
            for (RoadSegment segment : infra.segmentsNear(place, MAX_ROAD_HALF_WIDTH + 1f)) {
                float half = segment.width * 0.5f;
                if (half > reach && distanceSquaredToSegment(place, segment) <= (half + 1f) * (half + 1f)) reach = half;
            }
        }
        // (round the place in its own east/south frame, so it depends on nothing but where it is)
        SphereFrame here = SphereFrame.at(SphereMath.atAltitude(place, 0.0));
        float top = TerrainMesh.getLayeredHeight(place, infra.terrainNoise);
        int around = reach > 16f ? 12 : 8;
        for (int k = 0; k < around; k++) {
            double angle = k * 2 * Math.PI / around;
            for (float r : new float[] { reach * 0.5f, reach }) {
                top = Math.max(top, TerrainMesh.getLayeredHeight(here.toSphere(Math.cos(angle) * r, 0.0, Math.sin(angle) * r), infra.terrainNoise));
            }
        }
        return Math.max(top, infra.seaLevel + 1.0f) + ROAD_SURFACE_OFFSET;
    }

    /** Positions of a line offset sideways from the centreline, one per subdivision, mitred at the ends. */
    float[][] offsetEdge(RoadSegment segment, float offset) {
        return offsetEdge(segment, offset, deckHeights(segment));
    }

    /** The deck's height at each subdivision along a segment's centreline. */
    float[] deckHeights(RoadSegment segment) {
        // Worked out once per segment: the surface, its edges, rails and supports all ask
        float[] known = segment.deck;
        if (known != null) return known;
        float[] own = ownDeckHeights(segment);
        float[] deck = own.clone();
        // Where it meets a greater road (a junction, or crossing it), it comes to that road's
        // height: level with it across that road's width (a hair under, so that road's drawn on
        // top), sloping from there back to its own height over JUNCTION_RAMP beyond its edge.
        // The greater road (the higher rank; of two alike, the one made first) keeps its own,
        // so the two meet with no step and neither runs over the other
        if (segment.roadClass != RoadPath.RoadClass.DIRT) {
            for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
                float t = (float) i / ROAD_SEGMENT_SUBDIVISIONS;
                SpherePosition place = world(segment, segment.start.x + (segment.end.x - segment.start.x) * t,
                        segment.start.z + (segment.end.z - segment.start.z) * t);
                float pull = 0f, target = own[i];
                // (each greater road's height where the place is on it as drawn, by the segment
                // it's on: see drawnRoadY. The nearest end's, for one beyond a segment's end
                // that runs on into the next, is the height further along a climbing road)
                List<GreaterRoad> greaterRoads = new ArrayList<>(4);
                for (RoadSegment other : infra.segmentsNear(place, MAX_ROAD_HALF_WIDTH + JUNCTION_RAMP)) {
                    if (other.pathIndex == segment.pathIndex || other.roadClass == RoadPath.RoadClass.DIRT || !greater(other, segment)) continue;
                    float[] on = onDrawn(other, other.frame.fromSphere(place));
                    if (on[1] - other.width * 0.5f >= JUNCTION_RAMP) continue;
                    int mine = -1;
                    for (int r = 0; r < greaterRoads.size(); r++) if (greaterRoads.get(r).road().pathIndex == other.pathIndex) mine = r;
                    if (mine < 0) {
                        greaterRoads.add(new GreaterRoad(other, on));
                    } else {
                        float[] was = greaterRoads.get(mine).on();
                        if (on[0] > was[0] || (on[0] == was[0] && on[1] < was[1])) {
                            greaterRoads.set(mine, new GreaterRoad(other, on));
                        }
                    }
                }
                for (GreaterRoad road : greaterRoads) {
                    RoadSegment other = road.road();
                    float[] on = road.on();
                    float beyond = on[1] - other.width * 0.5f;
                    float s = beyond <= 0f ? 1f : 1f - beyond / JUNCTION_RAMP;
                    s = s * s * (3f - 2f * s);
                    if (s > pull) {
                        pull = s;
                        float[] theirs = ownDeckHeights(other);
                        float scaled = on[2] * ROAD_SEGMENT_SUBDIVISIONS;
                        int k = Math.min((int) scaled, ROAD_SEGMENT_SUBDIVISIONS - 1);
                        target = theirs[k] + (theirs[k + 1] - theirs[k]) * (scaled - k) - (beyond <= 0f ? JUNCTION_UNDER : 0f);
                    }
                }
                deck[i] = own[i] + (target - own[i]) * pull;
            }
        }
        // (kept only once the bridges are known: a deck asked for before then would miss them)
        if (infra.bridges.bridgesReady || infra.terrainNoise.rivers == null) segment.deck = deck;
        return deck;
    }

    /** A greater road meeting a segment, and where on it (as drawn: see onDrawn) a place is: see deckHeights. */
    private record GreaterRoad(RoadSegment road, float[] on) {
    }

    // A lesser road comes to a greater one's height over this far beyond its edge, and lies this
    // little under it across its width (see deckHeights)
    private static final float JUNCTION_RAMP = 30f, JUNCTION_UNDER = 0.05f;

    /** Whether road a is the greater of two meeting (see deckHeights): the higher rank, or of two alike the one made first. */
    private static boolean greater(RoadSegment a, RoadSegment b) {
        return a.rank() != b.rank() ? a.rank() > b.rank() : a.pathIndex < b.pathIndex;
    }

    /** A segment's deck heights by its own ground alone (no junctions), at each subdivision along its centreline. */
    float[] ownDeckHeights(RoadSegment segment) {
        float[] known = segment.ownDeck;
        if (known != null) return known;
        float[] deck = new float[ROAD_SEGMENT_SUBDIVISIONS + 1];
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            float t = (float) i / ROAD_SEGMENT_SUBDIVISIONS;
            deck[i] = roadSurfaceY(world(segment, segment.start.x + (segment.end.x - segment.start.x) * t,
                    segment.start.z + (segment.end.z - segment.start.z) * t));
        }
        if (infra.bridges.bridgesReady || infra.terrainNoise.rivers == null) segment.ownDeck = deck;
        return deck;
    }

    /** The heights of the made roads a place is on, or within slack of the edge of, as drawn (see drawnRoads). */
    float[] drawnRoadsY(SpherePosition place, float slack) {
        List<float[]> roads = drawnRoads(place, slack);
        float[] heights = new float[roads.size()];
        for (int r = 0; r < heights.length; r++) heights[r] = roads.get(r)[0];
        return heights;
    }

    /**
     * The made roads a place is on, or within slack of the edge of, as their asphalt is drawn
     * (appendRoadSegment's triangles, a cul-de-sac's disc), each as {its height there (at its
     * nearest point, off it), how far outside it the place is (0 on it)}: one each, unless one
     * road passes over the place twice (a hairpin's legs, far apart along it), when each pass
     * is a road of its own. Where the place is on any, only those it's on, and of different
     * roads meeting only the greatest: it's drawn on top of any lesser one, which is level
     * across its own width and so stands above it on one side wherever it climbs (see
     * deckHeights). Empty on none.
     * <p>
     * Walking goes on what's drawn, not on a road's centreline (the deck's height at the
     * nearest point of it, out to its width): a segment's end otherwise reaches on half the
     * road's width at its own height, and on the inside of a bend its next segment's reaches
     * back, both unseen steps as high as the road climbs in that far.
     */
    List<float[]> drawnRoads(SpherePosition place, float slack) {
        // Each road's segment the place is on, and {height, outside, how far along its path}
        List<RoadSegment> roads = new ArrayList<>(4);
        List<float[]> hits = new ArrayList<>(4);
        // (as far out as a mitred corner reaches: see miterOffset)
        for (RoadSegment segment : infra.segmentsNear(place, MAX_ROAD_HALF_WIDTH / 0.35f + slack)) {
            if (segment.roadClass == RoadPath.RoadClass.DIRT) continue;
            double[] p = segment.frame.fromSphere(place);
            float[] hit = drawnHit(segment, p[0], p[2]);
            if (hit[1] > slack) continue;
            float alongPath = segment.startDistance + hit[2] * segment.length;
            // (the same pass of the same road: near enough along it, its next segment or the last)
            int mine = -1;
            for (int r = 0; r < roads.size(); r++) {
                if (roads.get(r).pathIndex == segment.pathIndex && Math.abs(hits.get(r)[2] - alongPath) < 3f * segment.width) mine = r;
            }
            float[] entry = { hit[0], hit[1], alongPath };
            if (mine < 0) {
                roads.add(segment);
                hits.add(entry);
            } else if (hit[1] < hits.get(mine)[1] || (hit[1] == 0f && hits.get(mine)[1] == 0f && hit[0] > hits.get(mine)[0])) {
                roads.set(mine, segment);
                hits.set(mine, entry);
            }
        }
        List<float[]> found = new ArrayList<>(roads.size());
        for (int r = 0; r < roads.size(); r++) {
            if (hits.get(r)[1] > 0f) continue;
            boolean under = false;
            for (int q = 0; q < roads.size(); q++) {
                if (q != r && hits.get(q)[1] == 0f && roads.get(q).pathIndex != roads.get(r).pathIndex
                        && greater(roads.get(q), roads.get(r))) under = true;
            }
            if (!under) found.add(new float[] { hits.get(r)[0], 0f });
        }
        if (!found.isEmpty()) return found;
        for (float[] hit : hits) found.add(new float[] { hit[0], hit[1] });
        return found;
    }

    /**
     * Where a place (x, z in a segment's frame) is on the segment's asphalt as drawn:
     * {its height there (at its nearest point, if off it), how far outside it the place is (0
     * on it), the share along the segment}. Where a sharp bend folds the asphalt over on
     * itself, the higher, as it's seen.
     */
    private float[] drawnHit(RoadSegment segment, double x, double z) {
        if (segment.culDeSac) {
            // (a flat disc round its start: see appendCulDeSac)
            float out = (float) Math.max(0.0, Math.hypot(x - segment.start.x, z - segment.start.z) - segment.width * 0.5f);
            return new float[] { culDeSacLevel(segment), out, 0f };
        }
        float[][][] edges = drawnEdges(segment);
        float[][] left = edges[0], right = edges[1];
        float[] best = null;
        for (int i = 0; i < ROAD_SEGMENT_SUBDIVISIONS; i++) {
            // (the two triangles appendRoadSegment draws between these subdivisions)
            for (float[] hit : new float[][] { triangleHit(left[i], right[i], left[i + 1], x, z), triangleHit(right[i], right[i + 1], left[i + 1], x, z) }) {
                if (best == null || hit[1] < best[1] || (hit[1] == 0f && best[1] == 0f && hit[0] > best[0])) {
                    best = new float[] { hit[0], hit[1], (i + 0.5f) / ROAD_SEGMENT_SUBDIVISIONS };
                }
            }
        }
        return best;
    }

    /** A segment's asphalt's left and right edges at each subdivision, as appendRoadSegment draws them: {left, right}. */
    private float[][][] drawnEdges(RoadSegment segment) {
        float[][][] known = segment.edges;
        if (known != null) return known;
        float[] deck = deckHeights(segment);
        float half = segment.width * 0.5f;
        float[][][] edges = { offsetEdge(segment, -half, deck), offsetEdge(segment, half, deck) };
        // (kept once the deck is: see deckHeights)
        if (segment.deck != null) segment.edges = edges;
        return edges;
    }

    /** The height of a cul-de-sac's disc (see appendCulDeSac). */
    private float culDeSacLevel(RoadSegment segment) {
        float known = segment.discLevel;
        if (!Float.isNaN(known)) return known;
        float level = roadSurfaceY(world(segment, segment.start.x, segment.start.z));
        if (infra.bridges.bridgesReady || infra.terrainNoise.rivers == null) segment.discLevel = level;
        return level;
    }

    /**
     * Where a place (x, z) is on a triangle of points {x, y, z}: {its height there, 0} inside
     * it, otherwise {its height at the nearest point of its edge, how far that is}.
     */
    private static float[] triangleHit(float[] a, float[] b, float[] c, double x, double z) {
        double d = (b[2] - c[2]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[2] - c[2]);
        if (Math.abs(d) > 1e-9) {
            double wa = ((b[2] - c[2]) * (x - c[0]) + (c[0] - b[0]) * (z - c[2])) / d;
            double wb = ((c[2] - a[2]) * (x - c[0]) + (a[0] - c[0]) * (z - c[2])) / d;
            double wc = 1.0 - wa - wb;
            if (wa >= -1e-6 && wb >= -1e-6 && wc >= -1e-6) return new float[] { (float) (wa * a[1] + wb * b[1] + wc * c[1]), 0f };
        }
        float[] best = null;
        for (float[][] edge : new float[][][] { { a, b }, { b, c }, { c, a } }) {
            float[] p = edge[0], q = edge[1];
            double ex = q[0] - p[0], ez = q[2] - p[2], l2 = ex * ex + ez * ez;
            double s = l2 > 0.0 ? Math.max(0.0, Math.min(1.0, ((x - p[0]) * ex + (z - p[2]) * ez) / l2)) : 0.0;
            float away = (float) Math.hypot(x - (p[0] + ex * s), z - (p[2] + ez * s));
            // (never quite 0 off it, so it's told from on it)
            away = Math.max(away, 1e-4f);
            if (best == null || away < best[1]) best = new float[] { (float) (p[1] + (q[1] - p[1]) * s), away };
        }
        return best;
    }

    /**
     * Where a place (p, in a segment's frame) is on the segment as drawn: {1 if alongside it (0
     * if beyond either end), how far out from its centreline, the share along it}; beyond an
     * end, as at that end.
     */
    private float[] onDrawn(RoadSegment segment, double[] p) {
        float[] across = acrossDrawn(segment, p[0], p[2]);
        if (across != null) return new float[] { 1f, across[1], across[0] };
        float ux = segment.end.x - segment.start.x, uz = segment.end.z - segment.start.z, l2 = ux * ux + uz * uz;
        float f = l2 > 0f ? Math.max(0f, Math.min(1f, (float) (((p[0] - segment.start.x) * ux + (p[2] - segment.start.z) * uz) / l2))) : 0f;
        return new float[] { 0f, (float) Math.hypot(p[0] - (segment.start.x + ux * f), p[2] - (segment.start.z + uz * f)), f };
    }

    /**
     * Where on a segment's drawn road a place (x, z in its frame) lies: {the share along it
     * whose cross-section passes through the place, how far out along that the place is from
     * the centreline}; null if no cross-section does. Where the road bends sharply its
     * cross-sections cross on the inside of the bend, and more than one passes through a place
     * there: the nearest the centreline, the one it's on as drawn.
     */
    private float[] acrossDrawn(RoadSegment segment, double x, double z) {
        float[] best = null;
        final int looks = 8;
        float before = aheadOfSection(segment, 0f, x, z);
        for (int j = 1; j <= looks; j++) {
            float t1 = (float) j / looks, after = aheadOfSection(segment, t1, x, z);
            // (a cross-section between these two passes through it: found by halving)
            if (before >= 0f && after <= 0f) {
                float lo = (float) (j - 1) / looks, hi = t1;
                for (int k = 0; k < 11; k++) {
                    float mid = (lo + hi) * 0.5f;
                    if (aheadOfSection(segment, mid, x, z) >= 0f) lo = mid; else hi = mid;
                }
                float t = (lo + hi) * 0.5f;
                Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, t);
                float cx = segment.start.x + (segment.end.x - segment.start.x) * t, cz = segment.start.z + (segment.end.z - segment.start.z) * t;
                float out = (float) Math.abs((x - cx) * tangent.z - (z - cz) * tangent.x);
                if (best == null || out < best[1]) best = new float[] { t, out };
            }
            before = after;
        }
        return best;
    }

    /** How far a place (x, z in a segment's frame) is ahead of the segment's drawn cross-section a share t along it. */
    private float aheadOfSection(RoadSegment segment, float t, double x, double z) {
        Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, t);
        float cx = segment.start.x + (segment.end.x - segment.start.x) * t, cz = segment.start.z + (segment.end.z - segment.start.z) * t;
        return (float) ((x - cx) * tangent.x + (z - cz) * tangent.z);
    }

    float[][] offsetEdge(RoadSegment segment, float offset, float[] deck) {
        float[][] edge = new float[ROAD_SEGMENT_SUBDIVISIONS + 1][];
        float halfOffset = Math.abs(offset);
        float sign = Math.signum(offset);
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            float t = (float) i / ROAD_SEGMENT_SUBDIVISIONS;
            float x = segment.start.x + (segment.end.x - segment.start.x) * t;
            float z = segment.start.z + (segment.end.z - segment.start.z) * t;
            Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, t);
            float sideX = -tangent.z * halfOffset;
            float sideZ = tangent.x * halfOffset;
            if (i == 0) {
                float[] miter = miterOffset(segment.startTangent, tangent, halfOffset);
                sideX = miter[0];
                sideZ = miter[1];
            } else if (i == ROAD_SEGMENT_SUBDIVISIONS) {
                float[] miter = miterOffset(tangent, segment.endTangent, halfOffset);
                sideX = miter[0];
                sideZ = miter[1];
            }
            float edgeX = x + sideX * sign;
            float edgeZ = z + sideZ * sign;
            // Level across the road: the edge is as high as the deck at the centreline
            edge[i] = new float[] { edgeX, deck[i], edgeZ };
        }
        return edge;
    }

    void appendRoadSegment(NationBatch batch, RoadSegment segment) {
        float halfWidth = segment.width * 0.5f;
        float[] deck = deckHeights(segment);
        float[][] leftEdge = offsetEdge(segment, -halfWidth, deck);
        float[][] rightEdge = offsetEdge(segment, halfWidth, deck);
        infra.bridges.appendBridges(batch, segment);
        infra.roadFurniture.appendBollards(batch, segment, deck);
        if (segment.roadClass != RoadPath.RoadClass.DIRT) infra.roadFurniture.appendPoles(batch, segment);
        if (segment.roadClass == RoadPath.RoadClass.DIRT) {
            appendTrack(batch, segment, leftEdge, rightEdge);
            return;
        }
        // Where the deck stands above the ground: a wall down to it, or pillars where it's high
        appendRoadSupports(batch, leftEdge, -1f, segment);
        appendRoadSupports(batch, rightEdge, 1f, segment);
        appendDeckUnderside(batch, segment, leftEdge, rightEdge);

        MeshBuilder asphalt = batch.builder("asphalt");
        int firstVertex = -1;
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            float t = (float) i / ROAD_SEGMENT_SUBDIVISIONS;
            int left = asphalt.addVertex(leftEdge[i][0], leftEdge[i][1], leftEdge[i][2], 0.0f, 1.0f, 0.0f, t, 0.0f);
            asphalt.addVertex(rightEdge[i][0], rightEdge[i][1], rightEdge[i][2], 0.0f, 1.0f, 0.0f, t, 0.0f);
            if (firstVertex < 0) {
                firstVertex = left;
            }
            batch.include(leftEdge[i][0], leftEdge[i][1], leftEdge[i][2], 0.0f);
            batch.include(rightEdge[i][0], rightEdge[i][1], rightEdge[i][2], 0.0f);
        }
        for (int i = 0; i < ROAD_SEGMENT_SUBDIVISIONS; i++) {
            // Wound counter-clockwise seen from above so the surface survives back-face culling
            int left = firstVertex + i * 2;
            asphalt.addTriangle(left, left + 1, left + 2);
            asphalt.addTriangle(left + 1, left + 3, left + 2);
        }

        // Other roads close enough to meet this segment, for junction handling, in this segment's frame
        List<Crossing> crossing = new ArrayList<>();
        for (RoadSegment other : infra.segmentsNear(segment.frame.origin(), segment.length * 0.5f + segment.width + MAX_ROAD_HALF_WIDTH + RAIL_JUNCTION_CLEARANCE)) {
            if (other.pathIndex != segment.pathIndex || other.culDeSac) {
                crossing.add(new Crossing(other, segment));
            }
        }

        appendRoadLines(batch, segment, leftEdge, rightEdge, crossing);
        if (segment.railed) {
            GuardRailStyle style = infra.guardRailStyles.get(segment.nationId);
            appendGuardRail(batch, segment, style, -1.0f, crossing);
            appendGuardRail(batch, segment, style, 1.0f, crossing);
        }
    }

    // Below this the drop is left to the asphalt's own edge; above, a retaining wall; higher
    // still, pillars under a deck slab
    private static final float SUPPORT_MIN_DROP = 1.8f, PILLAR_DROP = 8f;

    /**
     * Holds up one side of a raised stretch of road. Where the ground falls away below the
     * deck a vertical retaining wall runs from the road's edge down into the ground; where
     * it falls a long way, the deck is a slab on square pillars instead.
     */
    private void appendRoadSupports(NationBatch batch, float[][] edge, float side, RoadSegment segment) {
        int n = edge.length;
        float[] ground = new float[n];
        float deepest = 0f;
        for (int i = 0; i < n; i++) {
            ground[i] = groundAt(segment, edge[i][0], edge[i][2]);
            deepest = Math.max(deepest, edge[i][1] - ground[i]);
        }
        if (deepest < SUPPORT_MIN_DROP) return;
        MeshBuilder wall = batch.builder("roadwall");
        for (int i = 0; i + 1 < n; i++) {
            float dropA = edge[i][1] - ground[i], dropB = edge[i + 1][1] - ground[i + 1];
            if (Math.max(dropA, dropB) < SUPPORT_MIN_DROP) continue;
            // (never a wall across a river: a bridge stands over it)
            float alongA = segment.startDistance + segment.length * i / (n - 1), alongB = segment.startDistance + segment.length * (i + 1) / (n - 1);
            if (infra.bridges.bridgeAt(segment, alongA) != null || infra.bridges.bridgeAt(segment, alongB) != null) continue;
            boolean pillars = Math.min(dropA, dropB) > PILLAR_DROP;
            // Outwards from the road, for which way the face points
            float ax = edge[i + 1][0] - edge[i][0], az = edge[i + 1][2] - edge[i][2];
            float length = (float) Math.hypot(ax, az);
            if (length < 1e-3f) continue;
            float outX = -az / length * side, outZ = ax / length * side;
            float bottomA = pillars ? edge[i][1] - 1.6f : ground[i] - 1.5f;
            float bottomB = pillars ? edge[i + 1][1] - 1.6f : ground[i + 1] - 1.5f;
            float[][] quad = { { edge[i][0], edge[i][1] - 0.05f, edge[i][2] }, { edge[i + 1][0], edge[i + 1][1] - 0.05f, edge[i + 1][2] },
                    { edge[i + 1][0], bottomB, edge[i + 1][2] }, { edge[i][0], bottomA, edge[i][2] } };
            int first = -1;
            for (int k = 0; k < 4; k++) {
                float v = k < 2 ? 0f : (quad[k][1] - quad[0][1]) * 0.1f;
                int index = wall.addVertex(quad[k][0], quad[k][1], quad[k][2], outX, 0f, outZ, (k == 1 || k == 2 ? length : 0f) * 0.1f, v);
                if (first < 0) first = index;
            }
            // Counter-clockwise seen from outside
            float[] e1 = { quad[1][0] - quad[0][0], quad[1][1] - quad[0][1], quad[1][2] - quad[0][2] };
            float[] e2 = { quad[3][0] - quad[0][0], quad[3][1] - quad[0][1], quad[3][2] - quad[0][2] };
            float facing = (e1[1] * e2[2] - e1[2] * e2[1]) * outX + (e1[0] * e2[1] - e1[1] * e2[0]) * outZ;
            if (facing > 0f) {
                wall.addTriangle(first, first + 1, first + 3);
                wall.addTriangle(first + 1, first + 2, first + 3);
            } else {
                wall.addTriangle(first, first + 3, first + 1);
                wall.addTriangle(first + 1, first + 3, first + 2);
            }
            if (pillars && i % 2 == 0) {
                // A pillar under the deck's edge, set in a little
                float px = edge[i][0] - outX * 1.5f, pz = edge[i][2] - outZ * 1.5f;
                float base = groundAt(segment, px, pz) - 1f;
                addBox(wall, px, base, pz, ax / length, az / length, 1.1f, 1.1f, edge[i][1] - 1.6f - base);
            }
        }
    }

    /**
     * A dirt track: the ground itself, worn bare and gritty, laid over the ground point by
     * point (following its slope across as well as along) just above it. A fine mesh, so the
     * ground never shows through between its points; textured just as the ground is, so its
     * soil matches what's round it.
     */
    private void appendTrack(NationBatch batch, RoadSegment segment, float[][] leftEdge, float[][] rightEdge) {
        MeshBuilder dirt = batch.builder("dirt");
        int along = Math.max(2, Math.round(segment.length / 3f)), across = Math.max(2, Math.round(segment.width / 3f));
        int first = -1;
        for (int i = 0; i <= along; i++) {
            float t = (float) i / along;
            float[] l = edgePoint(leftEdge, t), r = edgePoint(rightEdge, t);
            // (textured by distance along the path and across it, so one segment runs on into the next)
            float pathDistance = segment.startDistance + t * segment.length;
            for (int j = 0; j <= across; j++) {
                float u = (float) j / across;
                float x = l[0] + (r[0] - l[0]) * u, z = l[2] + (r[2] - l[2]) * u;
                float y = groundAt(segment, x, z);
                // Lit as the ground is: its normal flattened the same way, so the two match
                float nx = (groundAt(segment, x - 1f, z) - groundAt(segment, x + 1f, z)) * 0.1f;
                float nz = (groundAt(segment, x, z - 1f) - groundAt(segment, x, z + 1f)) * 0.1f;
                float length = (float) Math.sqrt(nx * nx + 1f + nz * nz);
                int index = dirt.addVertex(x, y + TRACK_LIFT, z, nx / length, 1f / length, nz / length,
                        ((u - 0.5f) * segment.width + 50f) / 100f, pathDistance / 100f);
                if (first < 0) first = index;
                batch.include(x, y, z, 0f);
            }
        }
        int row = across + 1;
        for (int i = 0; i < along; i++) {
            // (not where it's on a bridge: the planks are the track there)
            float alongFrom = segment.startDistance + segment.length * i / along, alongTo = segment.startDistance + segment.length * (i + 1) / along;
            Bridge bridge = infra.bridges.bridgeAt(segment, alongFrom);
            if (bridge != null && bridge == infra.bridges.bridgeAt(segment, alongTo) && alongFrom > bridge.from + Bridges.PLANK_PITCH) continue;
            for (int j = 0; j < across; j++) {
                int a = first + i * row + j, b = a + 1, c = a + row, d = c + 1;
                dirt.addTriangle(a, b, c);
                dirt.addTriangle(b, d, c);
            }
        }
    }

    // How far a track lies above the ground (with a depth offset as well, so it never flickers into it)
    static final float TRACK_LIFT = 0.45f;

    /** Where the deck stands on pillars, the slab's underside, so it can be seen from beneath. */
    private void appendDeckUnderside(NationBatch batch, RoadSegment segment, float[][] left, float[][] right) {
        MeshBuilder slab = null;
        for (int i = 0; i + 1 < left.length; i++) {
            float drop = Math.max(left[i][1] - groundAt(segment, left[i][0], left[i][2]),
                    right[i][1] - groundAt(segment, right[i][0], right[i][2]));
            float dropNext = Math.max(left[i + 1][1] - groundAt(segment, left[i + 1][0], left[i + 1][2]),
                    right[i + 1][1] - groundAt(segment, right[i + 1][0], right[i + 1][2]));
            // (wherever either edge stands on pillars: on a hillside the downhill edge does, and
            // the road's underside shows between them; where the uphill edge is in the hill, the
            // slab there is hidden in it)
            if (Math.max(drop, dropNext) < PILLAR_DROP * 0.5f) continue;
            if (slab == null) slab = batch.builder("roadwall");
            int a = slab.addVertex(left[i][0], left[i][1] - 1.6f, left[i][2], 0f, -1f, 0f, 0f, 0f);
            slab.addVertex(right[i][0], right[i][1] - 1.6f, right[i][2], 0f, -1f, 0f, 1f, 0f);
            slab.addVertex(left[i + 1][0], left[i + 1][1] - 1.6f, left[i + 1][2], 0f, -1f, 0f, 0f, 1f);
            slab.addVertex(right[i + 1][0], right[i + 1][1] - 1.6f, right[i + 1][2], 0f, -1f, 0f, 1f, 1f);
            slab.addTriangle(a, a + 2, a + 1);
            slab.addTriangle(a + 1, a + 2, a + 3);
        }
    }

    /** Highest junction rank of any other road covering the point (in the frame the crossings are in), or -1. */
    int coveringRank(float x, float z, List<Crossing> crossing, float margin) {
        int rank = -1;
        for (Crossing other : crossing) {
            float limit = other.road.width * 0.5f + margin;
            if (other.road.rank() > rank && distanceSquaredToSegment(x, z, other.start, other.end) <= limit * limit) {
                rank = other.road.rank();
            }
        }
        return rank;
    }

    /**
     * Paints the nation's line style, the inner line and the outer lines each in its own paint,
     * leaving junctions clear the way real roads do: minor roads stop their paint at the edge of
     * a bigger road and get a give-way line, while the bigger road only breaks its edge lines
     * across the mouth of the junction.
     */
    private void appendRoadLines(NationBatch batch, RoadSegment segment, float[][] leftEdge, float[][] rightEdge,
                                 List<Crossing> crossing) {
        RoadLineStyle style = infra.roadLineStyles.get(segment.nationId);
        if (style == null || segment.length <= 0.0f) {
            return;
        }
        if (style.inner != null) {
            appendStripes(batch.builder("line"), segment, style, style.inner, style.innerOffsets(), false, leftEdge, rightEdge, crossing);
        }
        if (style.outer != null) {
            appendStripes(batch.builder(OUTER_LINE_PART), segment, style, style.outer, style.outerOffsets(segment.width), true,
                    leftEdge, rightEdge, crossing);
        }
        appendGiveWayLines(batch.builder("line"), segment, style, leftEdge, rightEdge, crossing);
    }

    /** One line's stripes along the segment, dashed as it is, broken where other roads cover it. */
    private void appendStripes(MeshBuilder builder, RoadSegment segment, RoadLineStyle style, RoadLineStyle.Line line,
                               float[] offsets, boolean edgeStripe, float[][] leftEdge, float[][] rightEdge, List<Crossing> crossing) {
        List<Float> breakpoints = new ArrayList<>();
        for (int i = 0; i <= LINE_SAMPLES_PER_SEGMENT; i++) {
            breakpoints.add((float) i / LINE_SAMPLES_PER_SEGMENT);
        }
        if (line.dashed()) {
            addDashBreakpoints(breakpoints, segment, line.dashLength(), line.dashPeriod(), segment.startDistance + segment.length);
        }
        Collections.sort(breakpoints);

        int ownRank = segment.rank();
        float halfLine = style.lineWidth * 0.5f;
        for (float offset : offsets) {
            float innerAcross = 0.5f + (offset - halfLine) / segment.width;
            float outerAcross = 0.5f + (offset + halfLine) / segment.width;

            for (int k = 0; k < breakpoints.size() - 1; k++) {
                float t0 = breakpoints.get(k);
                float t1 = breakpoints.get(k + 1);
                if (t1 - t0 < 1e-5f) {
                    continue;
                }
                float tMid = (t0 + t1) * 0.5f;
                if (!line.isPaintedAt(segment.startDistance + tMid * segment.length)) {
                    continue;
                }
                float[] point = surfacePoint(leftEdge, rightEdge, tMid, 0.5f + offset / segment.width);
                int cover = coveringRank(point[0], point[2], crossing, 1.0f);
                if (cover >= 0 && (cover >= ownRank || edgeStripe)) {
                    continue;
                }
                addPaintQuad(builder, leftEdge, rightEdge, t0, t1, innerAcross, outerAcross);
            }
        }
    }

    private void addDashBreakpoints(List<Float> breakpoints, RoadSegment segment, float dashLength, float period, float segmentEnd) {
        for (float dashStart = (float) Math.floor(segment.startDistance / period) * period;
             dashStart <= segmentEnd; dashStart += period) {
            addBreakpoint(breakpoints, (dashStart - segment.startDistance) / segment.length);
            addBreakpoint(breakpoints, (dashStart + dashLength - segment.startDistance) / segment.length);
        }
    }

    /** A line across the carriageway wherever this road meets a road it must give way to. */
    private void appendGiveWayLines(MeshBuilder builder, RoadSegment segment, RoadLineStyle style,
                                    float[][] leftEdge, float[][] rightEdge, List<Crossing> crossing) {
        int ownRank = segment.rank();
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        float halfSpan = GIVE_WAY_LINE_LENGTH * 0.5f / segment.length;

        // Samples run one step beyond each end so transitions at segment joins are caught exactly once
        boolean previousInside = false;
        for (int k = -1; k <= LINE_SAMPLES_PER_SEGMENT + 1; k++) {
            float t = (float) k / LINE_SAMPLES_PER_SEGMENT;
            float x = segment.start.x + dirX * segment.length * t;
            float z = segment.start.z + dirZ * segment.length * t;
            boolean inside = false;
            for (Crossing other : crossing) {
                if (other.road.culDeSac || other.road.rank() <= ownRank) {
                    continue;
                }
                float limit = other.road.width * 0.5f + 1.0f;
                if (distanceSquaredToSegment(x, z, other.start, other.end) <= limit * limit) {
                    inside = true;
                    break;
                }
            }
            if (k > -1 && inside != previousInside) {
                float outsideT = inside ? (float) (k - 1) / LINE_SAMPLES_PER_SEGMENT : t;
                if (outsideT >= 0.0f && outsideT <= 1.0f) {
                    float t0 = Math.max(0.0f, outsideT - halfSpan);
                    float t1 = Math.min(1.0f, outsideT + halfSpan);
                    if (style.dashedGiveWay()) {
                        for (int dash = 0; dash < 5; dash++) {
                            float a = 0.08f + dash * 0.18f;
                            addPaintQuad(builder, leftEdge, rightEdge, t0, t1, a, a + 0.1f);
                        }
                    } else {
                        addPaintQuad(builder, leftEdge, rightEdge, t0, t1, 0.06f, 0.94f);
                    }
                }
            }
            previousInside = inside;
        }
    }

    private void addPaintQuad(MeshBuilder builder, float[][] leftEdge, float[][] rightEdge,
                              float t0, float t1, float innerAcross, float outerAcross) {
        int a = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t0, innerAcross);
        int b = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t0, outerAcross);
        int c = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t1, outerAcross);
        int d = addRoadSurfaceVertex(builder, leftEdge, rightEdge, t1, innerAcross);
        builder.addTriangle(a, b, c);
        builder.addTriangle(a, c, d);
    }

    private void addBreakpoint(List<Float> breakpoints, float t) {
        if (t > 0.0f && t < 1.0f) {
            breakpoints.add(t);
        }
    }

    /** t runs along the segment, across runs from the left edge (0) to the right edge (1). */
    private float[] surfacePoint(float[][] leftEdge, float[][] rightEdge, float t, float across) {
        float scaled = Math.max(0.0f, Math.min(1.0f, t)) * ROAD_SEGMENT_SUBDIVISIONS;
        int i = Math.min((int) Math.floor(scaled), ROAD_SEGMENT_SUBDIVISIONS - 1);
        float along = scaled - i;
        float[] point = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            float left = leftEdge[i][axis] + (leftEdge[i + 1][axis] - leftEdge[i][axis]) * along;
            float right = rightEdge[i][axis] + (rightEdge[i + 1][axis] - rightEdge[i][axis]) * along;
            point[axis] = left + (right - left) * across;
        }
        return point;
    }

    private int addRoadSurfaceVertex(MeshBuilder builder, float[][] leftEdge, float[][] rightEdge, float t, float across) {
        float[] point = surfacePoint(leftEdge, rightEdge, t, across);
        return builder.addVertex(point[0], point[1] + ROAD_LINE_SURFACE_OFFSET, point[2], 0.0f, 1.0f, 0.0f, across, t);
    }

    /** A flat disc of asphalt at the closed end of a street. */
    void appendCulDeSac(NationBatch batch, RoadSegment segment) {
        MeshBuilder asphalt = batch.builder("asphalt");
        float radius = segment.width * 0.5f;
        int sides = 20;
        float centreX = segment.start.x;
        float centreZ = segment.start.z;
        float level = roadSurfaceY(world(segment, centreX, centreZ));
        int centre = asphalt.addVertex(centreX, level, centreZ, 0.0f, 1.0f, 0.0f, 0.5f, 0.5f);
        for (int i = 0; i <= sides; i++) {
            double angle = 2.0 * Math.PI * i / sides;
            float x = centreX + (float) Math.cos(angle) * radius;
            float z = centreZ + (float) Math.sin(angle) * radius;
            asphalt.addVertex(x, level, z, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f);
            batch.include(x, 0.0f, z, 0.0f);
        }
        for (int i = 0; i < sides; i++) {
            // Counter-clockwise seen from above
            asphalt.addTriangle(centre, centre + i + 2, centre + i + 1);
        }
    }

    /**
     * Extrudes the nation's rail profile along one side of the highway on
     * regularly spaced posts, leaving gaps where other roads join.
     */
    private void appendGuardRail(NationBatch batch, RoadSegment segment, GuardRailStyle style, float side,
                                 List<Crossing> crossing) {
        float[][] base = offsetEdge(segment, side * (segment.width * 0.5f + RAIL_OFFSET));
        MeshBuilder railBuilder = batch.builder("rail");
        MeshBuilder bandBuilder = batch.builder("band");
        MeshBuilder postBuilder = batch.builder("post");

        List<Float> breakpoints = new ArrayList<>();
        for (int i = 0; i <= ROAD_SEGMENT_SUBDIVISIONS; i++) {
            breakpoints.add((float) i / ROAD_SEGMENT_SUBDIVISIONS);
        }
        if (style.banding == GuardRailStyle.Banding.ALTERNATING) {
            addDashBreakpoints(breakpoints, segment, style.bandLength, style.bandPeriod, segment.startDistance + segment.length);
        }
        Collections.sort(breakpoints);

        List<float[][]> profile = style.crossSection();
        for (int k = 0; k < breakpoints.size() - 1; k++) {
            float t0 = breakpoints.get(k);
            float t1 = breakpoints.get(k + 1);
            if (t1 - t0 < 1e-5f) {
                continue;
            }
            float[] mid = railBase(base, (t0 + t1) * 0.5f);
            if (coveringRank(mid[0], mid[2], crossing, RAIL_JUNCTION_CLEARANCE) >= 0) {
                continue;
            }
            // (nor by a border crossing, where the booth, lights and gates stand)
            if (infra.borderCrossings.borderCheckAt(world(segment, mid[0], mid[2]), BorderCrossings.RAIL_CLEAR_OF_BORDER) != null) continue;
            float[] start = railBase(base, t0);
            float[] end = railBase(base, t1);
            infra.recordSegment(world(segment, start[0], start[2]), world(segment, end[0], end[2]), 0.5f, style.railHeight + style.railSize * 0.5f);
            // Profile "out" points back towards the traffic
            float[] towardRoad0 = towardRoad(segment, t0, side);
            float[] towardRoad1 = towardRoad(segment, t1, side);
            boolean band = style.isBandAt(segment.startDistance + (t0 + t1) * 0.5f * segment.length);

            for (float[][] strip : profile) {
                for (int p = 0; p < strip.length - 1; p++) {
                    boolean topEdge = p == strip.length - 2;
                    MeshBuilder target = band || (style.banding == GuardRailStyle.Banding.TOP_STRIPE && topEdge)
                            ? bandBuilder : railBuilder;
                    float[] a = strip[p];
                    float[] b = strip[p + 1];
                    float edgeOut = b[0] - a[0];
                    float edgeUp = b[1] - a[1];
                    float edgeLength = (float) Math.sqrt(edgeOut * edgeOut + edgeUp * edgeUp);
                    float normalOut = edgeUp / edgeLength;
                    float normalUp = -edgeOut / edgeLength;

                    float[] v0 = profilePoint(start, towardRoad0, style.railHeight, a);
                    float[] v1 = profilePoint(start, towardRoad0, style.railHeight, b);
                    float[] v2 = profilePoint(end, towardRoad1, style.railHeight, b);
                    float[] v3 = profilePoint(end, towardRoad1, style.railHeight, a);
                    float nx = towardRoad0[0] * normalOut;
                    float nz = towardRoad0[1] * normalOut;
                    int i0 = target.addVertex(v0[0], v0[1], v0[2], nx, normalUp, nz, 0, 0);
                    int i1 = target.addVertex(v1[0], v1[1], v1[2], nx, normalUp, nz, 0, 1);
                    int i2 = target.addVertex(v2[0], v2[1], v2[2], nx, normalUp, nz, 1, 1);
                    int i3 = target.addVertex(v3[0], v3[1], v3[2], nx, normalUp, nz, 1, 0);
                    target.addTriangle(i0, i1, i2);
                    target.addTriangle(i0, i2, i3);
                }
            }
            batch.include(start[0], start[1] + style.railHeight, start[2], 2.0f);
        }

        // Posts at a fixed spacing along the whole path so they line up across segments
        float firstPost = (float) Math.ceil(segment.startDistance / style.postSpacing) * style.postSpacing;
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        for (float distance = firstPost; distance < segment.startDistance + segment.length; distance += style.postSpacing) {
            float t = (distance - segment.startDistance) / segment.length;
            float[] position = railBase(base, t);
            if (coveringRank(position[0], position[2], crossing, RAIL_JUNCTION_CLEARANCE) >= 0) {
                continue;
            }
            float[] toward = towardRoad(segment, t, side);
            float postX = position[0] - toward[0] * style.postWidth;
            float postZ = position[2] - toward[1] * style.postWidth;
            float top = style.railHeight + style.railSize * 0.5f;
            addBox(postBuilder, postX, position[1] - 2.0f, postZ, dirX, dirZ,
                    style.postWidth * 0.5f, style.postWidth * 0.5f, top + 2.0f);
        }
    }

    /** Ground point under the rail line (road surface height without the asphalt lift). */
    private float[] railBase(float[][] base, float t) {
        float scaled = Math.max(0.0f, Math.min(1.0f, t)) * ROAD_SEGMENT_SUBDIVISIONS;
        int i = Math.min((int) Math.floor(scaled), ROAD_SEGMENT_SUBDIVISIONS - 1);
        float along = scaled - i;
        return new float[] {
            base[i][0] + (base[i + 1][0] - base[i][0]) * along,
            base[i][1] + (base[i + 1][1] - base[i][1]) * along - ROAD_SURFACE_OFFSET,
            base[i][2] + (base[i + 1][2] - base[i][2]) * along
        };
    }

    /** Horizontal unit vector from the rail back across to the carriageway. */
    private float[] towardRoad(RoadSegment segment, float t, float side) {
        Vector3 tangent = lerpTangent(segment.startTangent, segment.endTangent, Math.max(0.0f, Math.min(1.0f, t)));
        return new float[] { tangent.z * side, -tangent.x * side };
    }

    private static float[] profilePoint(float[] base, float[] towardRoad, float railHeight, float[] profile) {
        return new float[] {
            base[0] + towardRoad[0] * profile[0],
            base[1] + railHeight + profile[1],
            base[2] + towardRoad[1] * profile[0]
        };
    }

    static final float GIVE_WAY_LINE_LENGTH = 2.4f;

    /** The batch part the outer lines are painted in, so they can take a different paint from the inner line. */
    static final String OUTER_LINE_PART = "lineouter";

    static final int LINE_SAMPLES_PER_SEGMENT = 24;

    // How far past the edge of a road joining a guard-railed one its rail stays open, so
    // vehicles turning in or out have room to swing round
    static final float RAIL_JUNCTION_CLEARANCE = 30.0f;

    static final float ROAD_LINE_SURFACE_OFFSET = 0.35f;
}
