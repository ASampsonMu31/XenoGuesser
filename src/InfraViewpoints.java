import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Developer aids: places to stand to look at one kind of thing (a house, a tower, a shop, a
 * bridge, a junction, a pole...), for the -Dxenoguesser.view switch (see Dev). Each returns
 * null if the world has none.
 */
final class InfraViewpoints extends InfraPart {

    InfraViewpoints(InfrastructureManager infra) {
        super(infra);
    }

        /**
     * A spot in the road in front of a random house, looking at it. A developer aid for
     * inspecting buildings; null if the world has no houses.
     */
    public Viewpoint randomHouseViewpoint(Random rand) {
        if (infra.houses.isEmpty()) {
            return null;
        }
        House house = infra.houses.get(rand.nextInt(infra.houses.size()));
        for (int attempt = 0; attempt < 50 && Math.abs(Planet.latitude(house.frame.origin())) > MapProjection.CLIP_LATITUDE; attempt++) {
            house = infra.houses.get(rand.nextInt(infra.houses.size()));
        }
        float distance = house.depth * 0.5f + house.setback + Math.max(house.width, house.depth) * 1.6f;
        float x = house.width * 0.35f;
        return viewpoint(house.frame, x, distance, -x, -distance);
    }

    /** Developer aid: across from one of the richest tenth of houses (not towers), looking at it; null if none. */
    public Viewpoint ornateHouseViewpoint(Random rand) {
        List<House> rich = new ArrayList<>();
        for (House house : infra.houses) if (!infra.styleOf(house).tower && house.shop == null) rich.add(house);
        if (rich.isEmpty()) return null;
        rich.sort((a, b) -> Float.compare(b.richness, a.richness));
        House house = rich.get(rand.nextInt(Math.max(1, rich.size() / 10)));
        float distance = house.depth * 0.5f + house.setback + Math.max(house.width, house.depth) * 1.3f;
        float x = house.width * 0.45f;
        return viewpoint(house.frame, x, distance, -x, -distance);
    }

    /** Developer aid: well back from one of the richest towers, facing it; null if none. */
    public Viewpoint towerViewpoint(Random rand) {
        List<House> towers = new ArrayList<>();
        for (House house : infra.houses) if (infra.styleOf(house).tower) towers.add(house);
        if (towers.isEmpty()) return null;
        towers.sort((a, b) -> Float.compare(b.richness, a.richness));
        House house = towers.get(rand.nextInt(Math.max(1, towers.size() / 4)));
        float distance = house.depth * 0.5f + house.mainWallHeight * 0.9f;
        return viewpoint(house.frame, house.width * 0.6f, distance, -house.width * 0.6f, -distance);
    }

    /**
     * Developer aid: in front of one of the built landmarks (of the kind -Dxenoguesser.landmark
     * names, spire, stones or ruin, if any), far enough back to see it whole; null if there's none.
     */
    public Viewpoint landmarkViewpoint(Random rand) {
        String wanted = Dev.property("xenoguesser.landmark");
        List<Landmark> built = new ArrayList<>();
        for (Landmark lm : infra.landmarks.all()) {
            if (lm.kind.built && (wanted == null || lm.kind.name().equalsIgnoreCase(wanted))) built.add(lm);
        }
        if (built.isEmpty()) return null;
        Landmark lm = built.get(rand.nextInt(built.size()));
        float distance = lm.kind == Landmark.Kind.SPIRE ? lm.tallest() * 0.55f : lm.reach * 1.25f;
        return viewpoint(lm.frame, distance * 0.08f, distance, -distance * 0.08f, -distance);
    }

    /** Developer aid: side on to a pole in town and the wire it drops to its nearest house, 30 off; null if none is found. */
    public Viewpoint poleDropViewpoint(Random rand) {
        infra.roadFurniture.preparePoles();
        for (int attempt = 0; attempt < 5000 && !infra.roadFurniture.poles.isEmpty(); attempt++) {
            int id = rand.nextInt(infra.roadFurniture.poles.size());
            RoadFurniture.PoleSite site = infra.roadFurniture.poles.get(id);
            if (site.urban() < 0.15f || infra.roadFurniture.poleHouses.get(id).size() < 2) continue;
            SpherePosition foot = RoadFurniture.poleFoot(site);
            House nearest = infra.roadFurniture.poleHouses.get(id).get(0);
            // (a low house, well under the pole's top)
            if (nearest == null || nearest.baseY + nearest.mainWallHeight - site.ground() > site.style().height * 0.6f
                    || SphereMath.distance(foot, nearest.frame.origin()) > RoadFurniture.DROP_REACH) continue;
            System.out.printf(java.util.Locale.ROOT, "[POLES] Drop view: pole foot %.1f, its drop at %.1f; the house's base %.1f, walls' top %.1f, %.1f off%n",
                    site.ground(), site.ground() + site.style().dropAttachment()[2], nearest.baseY, nearest.baseY + nearest.mainWallHeight,
                    SphereMath.distance(foot, nearest.frame.origin()));
            float[] h = inFrame(site.segment(), new PlaceAt(nearest.frame.origin(), 0f));
            float dx = h[0] - site.x(), dz = h[2] - site.z(), l = (float) Math.hypot(dx, dz);
            if (l < 5f) continue;
            // (from the middle of its road, 30 back along it, looking at the wire's middle)
            float back = site.segment().width * 0.5f + site.style().out;
            float cx = site.x() - site.sx() * back - site.fx() * 30f, cz = site.z() - site.sz() * back - site.fz() * 30f;
            float mx = (h[0] + site.x()) * 0.5f, mz = (h[2] + site.z()) * 0.5f;
            return viewpoint(site.segment().frame, cx, cz, mx - cx, mz - cz);
        }
        return null;
    }

    /** Developer aid: on a road where poles stand in town, between two of them, looking along it; null if none is found. */
    public Viewpoint poleViewpoint(Random rand) {
        for (int attempt = 0; attempt < 20000 && !infra.landSegments.isEmpty(); attempt++) {
            RoadSegment segment = infra.landSegments.get(rand.nextInt(infra.landSegments.size()));
            if (segment.roadClass == RoadPath.RoadClass.DIRT || segment.length < 40f || infra.settlementManager.getUrbanness(segment.from) < 0.2f) continue;
            UtilityPoleStyle style = infra.poleWorld.main(infra.segmentsByPath.get(segment.pathIndex).get(0).nationId);
            if (style == null) continue;
            long slot = (long) Math.ceil((segment.startDistance - style.spacing * 0.5f) / style.spacing);
            if (infra.roadFurniture.poleSite(segment.pathIndex, slot) == null) continue;
            float dx = (segment.end.x - segment.start.x) / segment.length, dz = (segment.end.z - segment.start.z) / segment.length;
            return viewpoint(segment.frame, segment.start.x, segment.start.z, dx, dz);
        }
        return null;
    }

    /** A developer aid: out in the road in front of a random shop, facing it (in its frame, door towards +z); null if none. */
    public Viewpoint shopViewpoint(Random rand) {
        if (infra.shops.isEmpty()) return null;
        InfrastructureManager.Shop shop = infra.shops.get(rand.nextInt(infra.shops.size()));
        House house = shop.house;
        float back = Float.parseFloat(Dev.property("xenoguesser.shopdistance", "50"));
        float side = Float.parseFloat(Dev.property("xenoguesser.shopside", "0"));
        return viewpoint(house.frame, side, house.depth * 0.5f + house.setback + back, 0.0, -1.0);
    }

    /**
     * Developer aid: beside a raised stretch of road, on its lower side some way off,
     * looking at its retaining wall; null if there's none.
     */
    public Viewpoint raisedRoadViewpoint(Random rand) {
        Viewpoint best = null;
        float bestDrop = 3f;
        for (int attempt = 0; attempt < 4000 && !infra.landSegments.isEmpty(); attempt++) {
            RoadSegment segment = infra.landSegments.get(rand.nextInt(infra.landSegments.size()));
            // (in the segment's frame, whose x and z run east and south as the chart's do)
            float mx = (segment.start.x + segment.end.x) * 0.5f, mz = (segment.start.z + segment.end.z) * 0.5f;
            float dx = segment.end.x - segment.start.x, dz = segment.end.z - segment.start.z;
            float length = (float) Math.hypot(dx, dz);
            if (length < 1f) continue;
            float sideX = -dz / length, sideZ = dx / length, half = segment.width * 0.5f;
            float deck = infra.roadGeometry.roadSurfaceY(world(segment, mx, mz));
            for (float sign = -1f; sign <= 1f; sign += 2f) {
                float ex = mx + sideX * half * sign, ez = mz + sideZ * half * sign;
                float drop = deck - groundAt(segment, ex, ez);
                if (drop > bestDrop && drop < 12f) {
                    bestDrop = drop;
                    float out = 70f;
                    best = viewpoint(segment.frame, ex + sideX * sign * out, ez + sideZ * sign * out, -sideX * sign, -sideZ * sign);
                }
            }
        }
        return best;
    }

    /** Developer aid: standing on a dirt track looking along it; null if there's none. */
    public Viewpoint trackViewpoint(Random rand) {
        for (int attempt = 0; attempt < 20000 && !infra.landSegments.isEmpty(); attempt++) {
            RoadSegment segment = infra.landSegments.get(rand.nextInt(infra.landSegments.size()));
            if (segment.roadClass != RoadPath.RoadClass.DIRT || segment.length < 1f) continue;
            return viewpoint(segment.frame, segment.start.x, segment.start.z, segment.end.x - segment.start.x, segment.end.z - segment.start.z);
        }
        return null;
    }

    /**
     * Developer aid: on a road a little before a bridge, looking along the road over it (a plank
     * bridge if planks is set, else any kind); null if none is found.
     */
    public Viewpoint bridgeViewpoint(Random rand, boolean planks) {
        if (!infra.bridges.bridgesReady || infra.bridges.bridgesByPath.isEmpty()) return null;
        List<Integer> paths = new ArrayList<>(infra.bridges.bridgesByPath.keySet());
        for (int attempt = 0; attempt < 2000; attempt++) {
            int path = paths.get(rand.nextInt(paths.size()));
            List<Bridge> bridges = infra.bridges.bridgesByPath.get(path);
            Bridge bridge = bridges.get(rand.nextInt(bridges.size()));
            if (planks && bridge.kind != BridgeKind.PLANKS) continue;
            float along = Math.max(0f, bridge.from - 20f);
            for (RoadSegment segment : infra.segmentsByPath.get(path)) {
                if (along > segment.startDistance + segment.length) continue;
                float t = Math.max(0f, Math.min(1f, (along - segment.startDistance) / segment.length));
                return viewpoint(segment.frame, segment.start.x + (segment.end.x - segment.start.x) * t, segment.start.z + (segment.end.z - segment.start.z) * t,
                        segment.end.x - segment.start.x, segment.end.z - segment.start.z);
            }
        }
        return null;
    }

    /**
     * Developer aid: at the edge of a road whose nation lines it with bollards (an open road,
     * without guard rails), just inside them, looking along it; null if none is found.
     */
    public Viewpoint bollardViewpoint(Random rand) {
        for (int attempt = 0; attempt < 20000 && !infra.landSegments.isEmpty(); attempt++) {
            RoadSegment segment = infra.landSegments.get(rand.nextInt(infra.landSegments.size()));
            BollardStyle style = infra.bollardWorld.main(segment.nationId);
            if (style == null || segment.railed|| !style.along(segment.roadClass) || segment.length < 30f || style.spacing > 150f) continue;
            float dx = (segment.end.x - segment.start.x) / segment.length, dz = (segment.end.z - segment.start.z) / segment.length;
            float side = segment.width * 0.5f - 1f;
            return viewpoint(segment.frame, segment.start.x - dz * side, segment.start.z + dx * side, dx, dz);
        }
        return null;
    }

    /**
     * Developer aid: on a lesser road a little short of where it meets a greater one, looking at
     * the junction, at one where the two would have met with a step (its own height there more
     * than a unit and a half off the greater road's); null if none is found.
     */
    public Viewpoint junctionViewpoint(Random rand) {
        for (int attempt = 0; attempt < 20000 && !infra.landSegments.isEmpty(); attempt++) {
            RoadSegment segment = infra.landSegments.get(rand.nextInt(infra.landSegments.size()));
            if (segment.roadClass == RoadPath.RoadClass.DIRT || segment.length < 40f) continue;
            float[] own = infra.roadGeometry.ownDeckHeights(segment), drawn = infra.roadGeometry.deckHeights(segment);
            int end = ROAD_SEGMENT_SUBDIVISIONS;
            if (Math.abs(own[end] - drawn[end]) < 1.5f) continue;
            float dx = (segment.end.x - segment.start.x) / segment.length, dz = (segment.end.z - segment.start.z) / segment.length;
            return viewpoint(segment.frame, segment.end.x - dx * 35f, segment.end.z - dz * 35f, dx, dz);
        }
        return null;
    }
}
