import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What stands beside the roads: each nation's bollards along them, and its utility poles with
 * the wires strung between them and dropped to the houses (worked out once, in the
 * background, as the network is prepared; see preparePoles).
 */
final class RoadFurniture extends InfraPart {

    RoadFurniture(InfrastructureManager infra) {
        super(infra);
    }

    // Bollards stand clear of junctions by this much past the other road's edge, and only where
    // the road is near the ground (not up on a bank or a deck more than this above it)
    private static final float BOLLARD_JUNCTION_CLEARANCE = 6f, BOLLARD_MOST_RAISED = 2f;

    /**
     * The nation's bollards along both sides of a segment (see BollardStyle): at its spacing,
     * counted from the path's start so they run on evenly from one segment to the next; not
     * where a guard rail runs, on a bridge or a raised stretch, at a junction or a border check,
     * on anyone's driveway or garden, nor in water.
     */
    void appendBollards(NationBatch batch, RoadSegment segment, float[] deck) {
        BollardStyle style = infra.bollardWorld.main(batch.nationId);
        if (style == null || segment.railed || !style.along(segment.roadClass)) return;
        List<Crossing> crossing = new ArrayList<>();
        for (RoadSegment other : infra.segmentsNear(segment.frame.origin(), segment.length * 0.5f + segment.width + MAX_ROAD_HALF_WIDTH + BOLLARD_JUNCTION_CLEARANCE)) {
            if (other.pathIndex != segment.pathIndex || other.culDeSac) crossing.add(new Crossing(other, segment));
        }
        float dirX = (segment.end.x - segment.start.x) / segment.length, dirZ = (segment.end.z - segment.start.z) / segment.length;
        float drivingSide = infra.drivingSide(segment.nationId);
        Rivers rivers = infra.terrainNoise.rivers;
        for (float side = -1f; side <= 1f; side += 2f) {
            float[][] edge = infra.roadGeometry.offsetEdge(segment, side * (segment.width * 0.5f + style.out + style.reach()), deck);
            float phase = style.staggered && side > 0f ? style.spacing * 0.5f : 0f;
            float first = (float) Math.ceil((segment.startDistance - phase) / style.spacing) * style.spacing + phase;
            for (float along = first; along < segment.startDistance + segment.length; along += style.spacing) {
                float t = (along - segment.startDistance) / segment.length;
                if (infra.bridges.bridgeAt(segment, along) != null) continue;
                float[] p = edgePoint(edge, t);
                float x = p[0], z = p[2];
                float scaled = Math.max(0f, Math.min(1f, t)) * (edge.length - 1);
                int i = Math.min((int) Math.floor(scaled), edge.length - 2);
                float roadY = edge[i][1] + (edge[i + 1][1] - edge[i][1]) * (scaled - i);
                float ground = groundAt(segment, x, z);
                if (roadY - ground > BOLLARD_MOST_RAISED || ground <= infra.seaLevel + 0.3f) continue;
                if (infra.roadGeometry.coveringRank(x, z, crossing, BOLLARD_JUNCTION_CLEARANCE) >= 0) continue;
                SpherePosition place = world(segment, x, z);
                if (infra.borderCrossings.borderCheckAt(place, BorderCrossings.RAIL_CLEAR_OF_BORDER) != null || infra.isPrivateGround(place, 1f)) continue;
                if (rivers != null && !Float.isNaN(rivers.waterAt(SphereMath.direction(place), ground))) continue;
                // (its front, and any reflectors on one side only, facing the traffic coming towards
                // it on its own side of the road: that traffic runs start to end on the right of
                // the way the segment runs (positive side) where they drive on the right)
                float facing = -side * drivingSide;
                // (the design built just here: a nation may build two or three, in different parts)
                BollardStyle here = infra.bollardWorld.at(batch.nationId, SphereMath.direction(place));
                if (here == null) here = style;
                String d = "#" + here.design;
                here.build(batch.builder("bollard" + d), batch.builder("bollardband" + d), batch.builder("reflectora" + d), batch.builder("reflectorb" + d),
                        x, ground, z, dirX * facing, dirZ * facing);
                batch.include(x, ground + here.height, z, 2f);
                infra.recordCircle(place, here.reach() + 0.3f);
            }
        }
    }

    /**
     * Where a pole stands along a path, the slot'th of its nation's spacing from the path's start:
     * on the path's own side (the same all along it, chosen by the path), its nation's way out
     * from the edge. Null where none stands: past the path's ends, in the country where its
     * nation leaves most out (fewer the further from town), or where a bollard wouldn't stand
     * either (a bridge, a raised stretch, a junction, a border check, anyone's land, water).
     * Worked out the same whichever segment asks, so the wire between two poles can be strung
     * from either.
     */
    PoleSite poleSite(int pathIndex, long slot) {
        List<RoadSegment> segments = infra.segmentsByPath.get(pathIndex);
        if (segments == null || segments.isEmpty() || slot < 0) return null;
        RoadSegment first = segments.get(0), last = segments.get(segments.size() - 1);
        // (spaced as the path's first nation spaces them, all along it, so the slots line up)
        UtilityPoleStyle spacer = infra.poleWorld.main(first.nationId);
        if (spacer == null) return null;
        float along = slot * spacer.spacing+ spacer.spacing * 0.5f;
        if (along >= last.startDistance + last.length) return null;
        // (the segment it's on: some paths' segments needn't start at nought, nor come in order)
        RoadSegment segment = null;
        for (RoadSegment s : segments) {
            if (along >= s.startDistance && along <= s.startDistance + s.length) segment = s;
        }
        if (segment == null || segment.roadClass == RoadPath.RoadClass.DIRT || infra.bridges.bridgeAt(segment, along) != null) return null;
        // (as the nation it stands in places them, built in the design for just there)
        UtilityPoleStyle placer = infra.poleWorld.main(segment.nationId);
        if (placer == null) return null;
        float side = (Long.hashCode(pathIndex * 0x9E3779B97F4A7C15L) & 1) == 0 ? 1f : -1f;
        float[] c = infra.bridges.centreAt(segment, along);
        // (beyond a guard rail, where there is one)
        float out = segment.width * 0.5f + (segment.railed ? Math.max(placer.out, RAIL_OFFSET + 2.5f) : placer.out);
        float x = c[0] - c[3] * out * side, z = c[1] + c[2] * out * side;
        SpherePosition place = world(segment, x, z);
        UtilityPoleStyle style = infra.poleWorld.at(segment.nationId, SphereMath.direction(place));
        if (style == null) style = placer;
        // (in town every road has them, and every highway all along it, carrying the lines out of
        // the towns and on to the next; in the country only some other roads, all along them, so
        // the lines run on unbroken: as many roads as the nation's rural share)
        float urban = infra.settlementManager.getUrbanness(place);
        double roll = new Random(infra.worldSeed * 0x2545F4914F6CDD1DL + pathIndex * 1_000_003L).nextDouble();
        if (urban < POLE_TOWN && segment.roadClass != RoadPath.RoadClass.HIGHWAY && roll >= style.ruralShare) return null;
        float ground = TerrainMesh.getLayeredHeight(place, infra.terrainNoise);
        float roadY = infra.roadGeometry.deckHeights(segment)[Math.max(0, Math.min(ROAD_SEGMENT_SUBDIVISIONS, Math.round((along - segment.startDistance) / segment.length * ROAD_SEGMENT_SUBDIVISIONS)))];
        if (roadY - ground > BOLLARD_MOST_RAISED || ground <= infra.seaLevel + 0.3f) return null;
        for (RoadSegment other : infra.segmentsNear(place, MAX_ROAD_HALF_WIDTH + 3f)) {
            if (other.pathIndex != pathIndex && distanceSquaredToSegment(place, other) <= Math.pow(other.width * 0.5f + 3f, 2)) return null;
        }
        if (infra.borderCrossings.borderCheckAt(place, BorderCrossings.RAIL_CLEAR_OF_BORDER) != null || infra.isPrivateGround(place, 0.3f)) return null;
        Rivers rivers = infra.terrainNoise.rivers;
        if (rivers != null && !Float.isNaN(rivers.waterAt(SphereMath.direction(place), ground))) return null;
        // Its way along the road and straight out from it, at its foot
        return new PoleSite(segment, x, z, ground, c[2], c[3], -c[3] * side, c[2] * side, urban, style);
    }

    // Every pole in the world (see poleSite), by path and slot ((path << 24) + slot), filed by
    // where it stands, and each one's wired neighbours (see preparePoles); null until worked out
    volatile List<PoleSite> poles;

    private Map<Long, Integer> poleBySlot;

    private int[][] poleLinks;

    // Whether each pole is at the end of a line (see preparePoles), and the houses each wires to
    private boolean[] poleTerminal;

    List<List<House>> poleHouses;

    private final CellIndex<Integer> poleIndex = new CellIndex<>(LOOKUP_LEVEL);

    // A pole is wired to its nearest two others no further off than this many of its spacings; it's
    // at a line's end unless two of its wires leave it further apart than this (degrees)
    private static final float POLE_LINK_SPACINGS = 1.4f, TERMINAL_ANGLE = 100f;

    /**
     * Works out every pole in the world once, and how they're wired: each to its two nearest
     * others within POLE_LINK_SPACINGS of its spacing, whatever road they stand on (so the lines
     * run on round corners and over junctions), each pair once.
     */
    void preparePoles() {
        // (locked on the manager, as routes() is, so the two never run at once)
        synchronized (infra) {
            if (poles != null) return;
            long started = System.currentTimeMillis();
            List<PoleSite> all = new ArrayList<>();
            Map<Long, Integer> bySlot = new HashMap<>();
            for (Map.Entry<Integer, List<RoadSegment>> entry : infra.segmentsByPath.entrySet()) {
                List<RoadSegment> path = entry.getValue();
                if (path.isEmpty()) continue;
                UtilityPoleStyle spacer = infra.poleWorld.main(path.get(0).nationId);
                if (spacer == null) continue;
                float length = 0f;
                for (RoadSegment s : path) length = Math.max(length, s.startDistance + s.length);
                for (long slot = 0; slot * spacer.spacing < length; slot++) {
                    PoleSite site = poleSite(entry.getKey(), slot);
                    if (site == null) continue;
                    bySlot.put(((long) entry.getKey() << 24) + slot, all.size());
                    poleIndex.add(world(site.segment(), site.x(), site.z()), all.size());
                    all.add(site);
                }
            }
            java.util.Set<Long> pairs = new java.util.HashSet<>();
            int blockedSpans = 0;
            for (int i = 0; i < all.size(); i++) {
                PoleSite site = all.get(i);
                SpherePosition here = world(site.segment(), site.x(), site.z());
                double reach = site.style().spacing * POLE_LINK_SPACINGS;
                List<Integer> near = new ArrayList<>(poleIndex.near(here, reach));
                near.remove((Integer) i);
                near.sort((a, b) -> Double.compare(SphereMath.distance(here, poleFoot(all.get(a))), SphereMath.distance(here, poleFoot(all.get(b)))));
                // (its two nearest whose span runs clear of every building, over or round it)
                int wired = 0;
                for (int k = 0; k < near.size() && wired < 2; k++) {
                    int j = near.get(k);
                    if (SphereMath.distance(here, poleFoot(all.get(j))) > reach) break;
                    if (spanHitsBuilding(site, all.get(j))) {
                        blockedSpans++;
                        continue;
                    }
                    pairs.add(((long) Math.min(i, j) << 32) | Math.max(i, j));
                    wired++;
                }
            }
            List<List<Integer>> links = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) links.add(new ArrayList<>());
            for (long pair : pairs) {
                int a = (int) (pair >>> 32), b = (int) pair;
                links.get(a).add(b);
                links.get(b).add(a);
            }
            int[][] linked = new int[all.size()][];
            for (int i = 0; i < all.size(); i++) linked[i] = links.get(i).stream().mapToInt(Integer::intValue).toArray();
            // The ends of the lines: a pole not wired on two sides (to two poles at least, two of them
            // more than TERMINAL_ANGLE apart as seen from it); these drop the wires to the houses
            boolean[] terminal = new boolean[all.size()];
            int ends = 0;
            for (int i = 0; i < all.size(); i++) {
                SpherePosition here = poleFoot(all.get(i));
                boolean through = false;
                for (int a = 0; a < linked[i].length && !through; a++) {
                    for (int b = a + 1; b < linked[i].length && !through; b++) {
                        SpherePosition pa = poleFoot(all.get(linked[i][a])), pb = poleFoot(all.get(linked[i][b]));
                        double ax = pa.x() - here.x(), ay = pa.y() - here.y(), az = pa.z() - here.z();
                        double bx = pb.x() - here.x(), by = pb.y() - here.y(), bz = pb.z() - here.z();
                        double cos = (ax * bx + ay * by + az * bz) / Math.max(1e-9, Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz)));
                        through = cos < Math.cos(Math.toRadians(TERMINAL_ANGLE));
                    }
                }
                terminal[i] = !through;
                if (terminal[i]) ends++;
            }
            // Every house with a pole near it (within DROP_REACH of its walls) is wired to the nearest
            int wiredHouses = 0;
            List<List<House>> housesOf = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) housesOf.add(new ArrayList<>());
            for (House house : infra.houses) {
                SpherePosition middle = house.frame.origin();
                int nearest = -1;
                double best = Double.MAX_VALUE;
                for (int p : poleIndex.near(middle, DROP_REACH + Math.max(house.width, house.depth) * 0.5)) {
                    double d = SphereMath.distance(middle, poleFoot(all.get(p)));
                    if (d < best) {
                        best = d;
                        nearest = p;
                    }
                }
                if (nearest < 0 || best > DROP_REACH + Math.max(house.width, house.depth) * 0.5) continue;
                housesOf.get(nearest).add(house);
                wiredHouses++;
            }
            poleBySlot = bySlot;
            poleLinks = linked;
            poleTerminal = terminal;
            poleHouses = housesOf;
            System.out.printf("[POLES] %d houses wired to their nearest pole; %d spans left out for running through a building%n", wiredHouses, blockedSpans);
            poles = all;
            System.out.printf("[POLES] %d utility poles, %d wired spans between them, %d at the ends of lines, in %d ms%n", all.size(), pairs.size(), ends,
                    System.currentTimeMillis() - started);
        }
    }

    /**
     * Whether the wires between two poles would run through a building: across its walls'
     * footprint (with a little room) lower than its roof's top (its walls' top and as much again
     * as half its width), the wires taken at their lowest (the lower pole's lowest insulator, less
     * their sag).
     */
    private boolean spanHitsBuilding(PoleSite a, PoleSite b) {
        SpherePosition pa = poleFoot(a), pb = poleFoot(b);
        double length = SphereMath.distance(pa, pb);
        float lowest = Float.MAX_VALUE;
        for (PoleSite p : new PoleSite[] { a, b }) {
            for (float[] at : p.style().lineAttachments()) lowest = Math.min(lowest, p.ground() + at[2]);
        }
        lowest -= (float) (Math.max(a.style().sag, b.style().sag) * length);
        SpherePosition middle = SphereMath.slerp(pa, pb, 0.5);
        for (House house : infra.houseIndex.near(middle, length * 0.5 + 10.0)) {
            float roof = house.baseY + house.mainWallHeight + Math.max(house.width, house.depth) * 0.5f;
            if (roof < lowest) continue;
            float[] la = local(house, pa), lb = local(house, pb);
            float room = 2f;
            if (segmentCrossesBox(la, lb, house.houseMinX() - room, house.houseMaxX() + room, -house.depth * 0.5f - room, house.depth * 0.5f + room)) return true;
        }
        return false;
    }

    /** Whether a line from a to b ({x, z}) crosses a box (min and max x and z), by clipping it to the box. */
    private static boolean segmentCrossesBox(float[] a, float[] b, float minX, float maxX, float minZ, float maxZ) {
        float t0 = 0f, t1 = 1f, dx = b[0] - a[0], dz = b[1] - a[1];
        float[][] edges = { { -dx, a[0] - minX }, { dx, maxX - a[0] }, { -dz, a[1] - minZ }, { dz, maxZ - a[1] } };
        for (float[] e : edges) {
            if (e[0] == 0f) {
                if (e[1] < 0f) return false;
                continue;
            }
            float t = e[1] / e[0];
            if (e[0] < 0f) t0 = Math.max(t0, t); else t1 = Math.min(t1, t);
            if (t0 > t1) return false;
        }
        return true;
    }

    static SpherePosition poleFoot(PoleSite site) {
        return world(site.segment(), site.x(), site.z());
    }

    /** A pole's place: its segment, its foot in that segment's frame (x, z) and its ground, its way along the road and out across it there, and how built up it is about it. */
    record PoleSite(RoadSegment segment, float x, float z, float ground, float fx, float fz, float sx, float sz, float urban, UtilityPoleStyle style) {
        /** A point on the pole, {along, across, up} in its own terms, as a place and an altitude. */
        PlaceAt at(float[] p) {
            float px = x + fx * p[0] + sx * p[1], pz = z + fz * p[0] + sz * p[1];
            return new PlaceAt(segment.frame.toSphere(px, 0.0, pz), ground + p[2]);
        }
    }

    // Built up enough for every road to have poles
    private static final float POLE_TOWN = 0.12f;

    // How far a line's end pole drops wires to houses, and the most it drops
    static final float DROP_REACH = 200f;

    private static final int MOST_DROPS = 12;

    /**
     * The poles standing on a segment (see poleSite), and their wires: along to the last pole on
     * the path from each of its insulators, and down to the tops of the walls of houses about it
     * (its nation's number, many more in town, the nearest first), each drop in its strands.
     */
    void appendPoles(NationBatch batch, RoadSegment segment) {
        List<RoadSegment> path = infra.segmentsByPath.get(segment.pathIndex);
        UtilityPoleStyle style = path == null || path.isEmpty() ? null : infra.poleWorld.main(path.get(0).nationId);
        if (style == null) return;
        float pathStart = segment.startDistance - style.spacing * 0.5f;
        long firstSlot = (long) Math.ceil(pathStart / style.spacing), lastSlot = (long) Math.floor((pathStart + segment.length) / style.spacing);
        preparePoles();
        for (long slot = firstSlot; slot <= lastSlot; slot++) {
            Integer id = poleBySlot.get(((long) segment.pathIndex << 24) + slot);
            if (id == null) continue;
            PoleSite site = poles.get(id);
            if (site.segment() != segment) continue;
            // (the site's own style: the path's first segment's nation's, the same all along it)
            UtilityPoleStyle ps = site.style();
            String d = "#" + ps.design;
            ps.build(batch.builder("pole" + d), batch.builder("polearm" + d), batch.builder("insulator" + d), site.x(), site.ground(), site.z(),
                    new float[] { site.fx(), site.fz() }, new float[] { site.sx(), site.sz() });
            batch.include(site.x(), site.ground() + ps.height, site.z(), ps.armReach + 2f);
            infra.recordCircle(world(segment, site.x(), site.z()), ps.radius + 0.4f);
            MeshBuilder wires = batch.builder("wire");
            // To the poles it's wired to (each span strung once, from the later of its two poles),
            // insulator to insulator: as many wires as the fewer of the two has places for, each
            // to the other's on the same side of its road where it can
            for (int other : poleLinks[id]) {
                if (other > id) continue;
                PoleSite them = poles.get(other);
                List<float[]> mine = ps.lineAttachments(), theirs = them.style().lineAttachments();
                for (int w = 0; w < Math.min(mine.size(), theirs.size()); w++) {
                    float[] a = inFrame(segment, them.at(theirs.get(w))), b = inFrame(segment, site.at(mine.get(w)));
                    UtilityPoleStyle.wire(wires, a, b, ps.sag);
                    // (the batch reaching out to the far pole too: otherwise, its own pole out of
                    // sight, it's left undrawn and the wire vanishes though it's in view)
                    batch.include(a[0], a[1], a[2], 2f);
                }
            }
            // Down to each house wired to it (see preparePoles), a wire to each
            SpherePosition foot = world(segment, site.x(), site.z());
            float[] from = inFrame(segment, site.at(ps.dropAttachment()));
            float poleTop = site.ground() + ps.height;
            for (House house : poleHouses.get(id)) {
                // The nearest point of its walls (its own outline, box or many-sided), a hair into
                // them, at their top (no higher than a little over the pole's top): the wire
                // slopes down from the pole to a house lower than it
                float[] wall = nearestWall(house, local(house, foot));
                // (a little under the walls' top, below the eaves, so it's seen to go into the wall)
                float wallTop = Math.min(house.baseY + house.mainWallHeight - 2.5f, poleTop + 2f);
                float[] to = inFrame(segment, new PlaceAt(world(house, wall[0], 0f, wall[1]), wallTop));
                UtilityPoleStyle.wire(wires, from, to, ps.sag);
                batch.include(to[0], to[1], to[2], 1f);
            }
        }
    }

    /** The nearest point of a house's walls (its main block's outline) to a point in its frame {x, z}, a hair inside them. */
    private float[] nearestWall(House house, float[] point) {
        float[][] outline = footprint(infra.styleOf(house), house.width, house.depth, 0f, 0f);
        float bestX = 0f, bestZ = 0f, best = Float.MAX_VALUE;
        for (int i = 0; i < outline.length; i++) {
            float[] a = outline[i], b = outline[(i + 1) % outline.length];
            float ux = b[0] - a[0], uz = b[1] - a[1], l2 = ux * ux + uz * uz;
            float t = l2 > 0f ? Math.max(0f, Math.min(1f, ((point[0] - a[0]) * ux + (point[1] - a[1]) * uz) / l2)) : 0f;
            float x = a[0] + ux * t, z = a[1] + uz * t, d = (x - point[0]) * (x - point[0]) + (z - point[1]) * (z - point[1]);
            if (d < best) {
                best = d;
                bestX = x;
                bestZ = z;
            }
        }
        float inward = 0.98f;
        return new float[] { bestX * inward, bestZ * inward };
    }
}
