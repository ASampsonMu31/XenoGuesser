import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * Lays the houses out along the roads, once for the whole world: rolls candidates along every
 * segment (and a house at the end of each dead end, cutting the road back where none fits),
 * settles each on the ground clear of roads, rivers and its neighbours, and decides its form,
 * walls, driveway, fence and look. Deterministic for a seed.
 */
final class HousePlacement extends InfraPart {

    HousePlacement(InfrastructureManager infra) {
        super(infra);
    }

    /**
     * Picks a house's form from its nation's mix, tilted by its town's favourites; taller
     * forms are likelier towards the middle of a town, so centres rise above the suburbs, and
     * where people are richer, whose houses are taller and less like everyone else's (the
     * nation's rarer forms come up more often).
     */
    private int chooseForm(SpherePosition place, int nationId, long houseSeed, float richness) {
        BuildingStyle[] forms = infra.formStyles.get(nationId);
        float[] weights = forms[0].formWeights;
        float urbanness = infra.settlementManager.getUrbanness(place);
        // In the heart of a big city, most buildings are towers, the more so the richer it is
        float big = smoothstep(BIG_CITY_FROM, BIG_CITY_TO, infra.settlementManager.getCitySize(SphereMath.direction(place)));
        float towerChance = Math.min(0.92f, big * smoothstep(0.3f, 0.75f, urbanness) * (0.55f + 0.6f * richness));
        Random towerRoll = new Random(houseSeed ^ 0x5BD1E995L);
        if (towerRoll.nextFloat() < towerChance) {
            List<Integer> towers = new ArrayList<>();
            for (int f = 0; f < forms.length; f++) if (forms[f].tower) towers.add(f);
            if (!towers.isEmpty()) return towers.get(towerRoll.nextInt(towers.size()));
        }
        SettlementManager.Settlement town = urbanness > 0.05f ? infra.settlementManager.dominantSettlementAt(place) : null;
        Random townRand = town == null ? null : new Random(infra.worldSeed ^ (town.rank * 0xC2B2AE3D27D4EB4FL));
        float[] tilted = new float[weights.length];
        float total = 0;
        for (int f = 0; f < weights.length; f++) {
            float townBias = townRand == null ? 1.0f : (float) Math.exp(townRand.nextGaussian() * 0.7);
            float lift = Math.max(0.0f, forms[f].wallHeight / forms[0].wallHeight - 1.0f);
            float usual = (float) Math.pow(Math.max(1e-4f, weights[f]), 1.0f - 0.35f * richness);
            tilted[f] = forms[f].tower ? 0f : usual * townBias * (1.0f + 3.0f * urbanness * lift + 4.0f * richness * richness * lift);
            total += tilted[f];
        }
        float pick = new Random(houseSeed ^ 0x3C6EF372FE94F82AL).nextFloat() * total;
        for (int f = 0; f < tilted.length; f++) {
            pick -= tilted[f];
            if (pick <= 0) return f;
        }
        return tilted.length - 1;
    }

    /**
     * Picks a house's wall variety from its nation's mix, tilted by its town's own favourites,
     * so a town has a recognisable look without every house matching; but most walls are built
     * in the local architecture's favourite masonry (LIKED_MASONRY of them), as the map shows it.
     */
    private static final float LIKED_MASONRY = 0.8f;

    int chooseWallVariant(SpherePosition place, int nationId, long houseSeed, BuildingStyle.Pattern liked) {
        float[] weights = infra.buildingStyles.get(nationId).wallVariantWeights;
        SettlementManager.Settlement town = infra.settlementManager.getUrbanness(place) > 0.05f
                ? infra.settlementManager.dominantSettlementAt(place) : null;
        float[] tilted = new float[weights.length];
        float likedTotal = 0, otherTotal = 0;
        Random townRand = town == null ? null : new Random(infra.worldSeed ^ (town.rank * 0x9E3779B97F4A7C15L));
        for (int v = 0; v < weights.length; v++) {
            float townBias = townRand == null ? 1.0f : (float) Math.exp(townRand.nextGaussian() * 0.6);
            tilted[v] = weights[v] * townBias;
            if (infra.wallVariants.get(v).pattern == liked) likedTotal += tilted[v]; else otherTotal += tilted[v];
        }
        // (the favourite masonry's varieties scaled up or down to their share)
        float likedScale = likedTotal > 0 && otherTotal > 0 ? LIKED_MASONRY / (1f - LIKED_MASONRY) * otherTotal / likedTotal : 1f;
        float total = 0;
        for (int v = 0; v < tilted.length; v++) {
            if (infra.wallVariants.get(v).pattern == liked) tilted[v] *= likedScale;
            total += tilted[v];
        }
        float pick = new Random(houseSeed).nextFloat() * total;
        for (int v = 0; v < tilted.length; v++) {
            pick -= tilted[v];
            if (pick <= 0) return v;
        }
        return tilted.length - 1;
    }

    /**
     * Rolls houses along every road segment, then keeps them in priority order
     * so no two plots overlap. Deterministic for a given seed.
     */
    void placeHouses() {
        List<House> candidates = new ArrayList<>();
        // Dead ends first: their houses outrank every roadside house, and trimming a road
        // back must happen before houses are rolled along it
        List<House> endHouses = new ArrayList<>();
        for (RoadPath.DeadEnd deadEnd : infra.deadEnds) {
            House endHouse = finishDeadEnd(deadEnd, endHouses);
            if (endHouse != null) {
                endHouses.add(endHouse);
            }
        }
        candidates.addAll(endHouses);
        System.out.printf("[ROADS] %d dead ends: %d finished with a house, %d cut back to a junction%n",
                infra.deadEnds.size(), endHouses.size(), infra.deadEnds.size() - endHouses.size());
        for (List<RoadSegment> bucket : infra.roadSegmentsByCell.values()) {
            for (RoadSegment segment : bucket) {
                // (dirt tracks have no houses, except on islands the main roads never reached)
                if (!segment.culDeSac && !segment.railed && (segment.roadClass != RoadPath.RoadClass.DIRT || infra.roadNetwork.get(segment.pathIndex).island)) {
                    rollHousesAlong(segment, candidates);
                }
            }
        }
        // Bucket iteration order is not stable, so sort fully by priority then seed for a deterministic result
        candidates.sort((a, b) -> a.priority != b.priority ? Float.compare(b.priority, a.priority) : Long.compare(a.seed, b.seed));

        // The plots kept so far, filed at the middle of each plot reaching its corners
        for (House candidate : candidates) {
            SpherePosition centre = world(candidate, candidate.plotCentreLocalX(), 0f, candidate.plotCentreLocalZ());
            // (nor on the land shaped for a natural landmark: a spit's sand, a field of pinnacles, see Landforms)
            Landforms shaped = infra.terrain() == null ? null : infra.terrain().landforms;
            boolean blocked = infra.borderCrossings.straddlesFencedBorder(candidate) || infra.landmarks.covers(centre, candidate.plotRadius() + PLOT_GAP)
                    || shaped != null && shaped.covers(centre, candidate.plotRadius() + PLOT_GAP);
            if (!blocked) {
                for (House other : plotIndex.near(centre, candidate.plotRadius() + PLOT_GAP)) {
                    if (plotsOverlap(candidate, other)) {
                        blocked = true;
                        break;
                    }
                }
            }
            if (blocked) {
                continue;
            }
            plotIndex.add(centre, candidate.plotRadius(), candidate);
            infra.houses.add(candidate);
            infra.housesByCell.computeIfAbsent(infra.batchCell(candidate.frame.origin()), ignored -> new ArrayList<>()).add(candidate);
        }
    }

    // The plots by their middles while they are laid out and fenced (they may still grow a
    // little: see connectNeighbourFences), before houseIndex takes over
    private final CellIndex<House> plotIndex = new CellIndex<>(LOOKUP_LEVEL);

    // How much more closely houses line the roads of islands the main roads never reached
    private static final float ISLAND_HOUSING = 3f;

    private void rollHousesAlong(RoadSegment segment, List<House> candidates) {
        long segmentSeed = infra.worldSeed ^ ((long) segment.pathIndex * 0x9E3779B97F4A7C15L)
                ^ (Float.floatToIntBits(segment.startDistance) * 0xC2B2AE3D27D4EB4FL);
        Random rand = new Random(segmentSeed);
        // Laid out in the segment's frame (D4), each house then given a frame of its own
        float urbanness = infra.settlementManager.getUrbanness(segment.frame.origin());
        float habitability = infra.settlementManager.getHabitability(segment.frame.origin());

        Double ruralProbability = infra.nationBuildingProbabilities.get(segment.nationId);
        Float urbanDensity = infra.nationUrbanDensities.get(segment.nationId);
        if (ruralProbability == null || urbanDensity == null) {
            return;
        }
        float perHundred = (float) (ruralProbability * RURAL_FRONTAGE_SCALE) * (0.3f + 0.7f * habitability)
                + urbanDensity * (float) Math.pow(urbanness, URBAN_DENSITY_EXPONENT);
        // An island's few roads are where all its people live, so they're built up more closely
        if (infra.roadNetwork.get(segment.pathIndex).island) perHundred *= ISLAND_HOUSING;
        float attemptsExact = perHundred * segment.length / 100.0f * 2.0f * PLACEMENT_OVERSAMPLE;
        int attempts = (int) attemptsExact;
        if (rand.nextFloat() < attemptsExact - attempts) {
            attempts++;
        }

        // Gardens shrink to nothing as the countryside gives way to town
        float gardenScale = 1.0f - smoothstep(0.1f, 0.45f, urbanness);
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;

        for (int attempt = 0; attempt < attempts; attempt++) {
            House house = new House();
            house.seed = segmentSeed * 31L + attempt;
            house.frontagePath = segment.pathIndex;
            float t = rand.nextFloat();
            float side = rand.nextBoolean() ? 1.0f : -1.0f;
            house.sizeScale = 0.9f + rand.nextFloat() * 0.2f;
            house.setback = KERB_SETBACK_MIN + rand.nextFloat() * KERB_SETBACK_RANGE + rand.nextFloat() * MAX_FRONT_GARDEN * gardenScale;
            float extensionRoll = rand.nextFloat();
            boolean extensionLeft = rand.nextBoolean();
            float fenceRoll = rand.nextFloat();
            float sideYard = 6.0f + rand.nextFloat() * 8.0f;
            float backYard = 8.0f + rand.nextFloat() * (6.0f + 30.0f * gardenScale);
            house.priority = rand.nextFloat();

            float outwardX = -dirZ * side;
            float outwardZ = dirX * side;
            float roadX = segment.start.x + (segment.end.x - segment.start.x) * t;
            float roadZ = segment.start.z + (segment.end.z - segment.start.z) * t;

            // The house takes the nation of the ground it stands on, a little back from the road
            float probe = segment.width + house.setback + 40.0f;
            int nationId = infra.nationManager.getNationAt(world(segment, roadX + outwardX * probe, roadZ + outwardZ * probe));
            FenceStyle fenceStyle = infra.fenceStyles.get(nationId);
            if (!infra.formStyles.containsKey(nationId)) {
                continue;
            }
            house.nationId = nationId;
            SpherePosition roadPlace = world(segment, roadX, roadZ);
            house.richness = infra.areaRichness(nationId, infra.settlementManager.getUrbanness(roadPlace), roadPlace);
            house.sizeScale *= 1f + 0.35f * house.richness * house.richness;
            house.form = chooseForm(roadPlace, nationId, house.seed, house.richness);
            BuildingStyle style = infra.styleOf(house);
            house.width = style.width * house.sizeScale;
            house.depth = style.depth * house.sizeScale;
            if (extensionRoll < style.extensionChance * (1f + house.richness)) {
                house.extensionSide = extensionLeft ? -1 : 1;
                house.extensionWidth = house.width * style.extensionWidthRatio;
                house.extensionDepth = house.depth * style.extensionDepthRatio;
            }
            house.fenced = fenceStyle != null && fenceRoll < fenceStyle.fenceChance;

            float offset = segment.width * 0.5f + house.setback + house.depth * 0.5f;
            placeHouse(house, segment, roadX + outwardX * offset, roadZ + outwardZ * offset, -outwardX, -outwardZ);

            float overhang = style.roofOverhang * house.sizeScale;
            house.plotMinX = house.houseMinX() - overhang - sideYard;
            house.plotMaxX = house.houseMaxX() + overhang + sideYard;
            decideDriveway(house, overhang);
            house.plotMinZ = -house.depth * 0.5f - overhang - backYard;
            house.plotMaxZ = house.depth * 0.5f + house.setback - FRONT_FENCE_GAP;

            if (!settleOnGround(house, style) || !isPlotClearOfRoads(house)) {
                continue;
            }
            house.architecture = Math.max(0, infra.architectureWorld.at(house.nationId, SphereMath.direction(house.frame.origin())));
            house.wallVariant = chooseWallVariant(house.frame.origin(), house.nationId, house.seed,
                    infra.architectureWorld.style(house.architecture).pattern);
        decideFence(house);
        decideLook(house);
            candidates.add(house);
        }
    }

    /**
     * Gives a dead-end road a house at its end, facing back down the road. Where the
     * ground there can't take a house, the road is cut back a segment at a time until one
     * fits or the road's end reaches a junction, so no road simply stops in the open.
     */
    private House finishDeadEnd(RoadPath.DeadEnd deadEnd, List<House> otherEndHouses) {
        List<RoadSegment> pathSegments = infra.segmentsByPath.get(deadEnd.pathIndex);
        RoadPath path = infra.roadNetwork.get(deadEnd.pathIndex);
        Random rand = new Random(infra.worldSeed ^ (deadEnd.pathIndex * 0x9E3779B97F4A7C15L) ^ (deadEnd.atStart ? 0x5A5AL : 0xA5A5L));
        for (int trims = 0; pathSegments != null && !pathSegments.isEmpty(); trims++) {
            RoadSegment terminal = deadEnd.atStart ? pathSegments.get(0) : pathSegments.get(pathSegments.size() - 1);
            Vector3 end = deadEnd.atStart ? terminal.start : terminal.end;
            Vector3 inner = deadEnd.atStart ? terminal.end : terminal.start;
            SpherePosition endPoint = deadEnd.atStart ? terminal.from : terminal.to;
            if (trims > 0 && meetsAnotherRoad(endPoint, deadEnd.pathIndex, terminal.width)) {
                return null;
            }
            float dirX = (end.x - inner.x) / terminal.length;
            float dirZ = (end.z - inner.z) / terminal.length;
            for (int attempt = 0; attempt < 3; attempt++) {
                House house = endHouse(terminal, end, dirX, dirZ, rand, attempt);
                if (house == null) {
                    continue;
                }
                boolean overlaps = false;
                for (House other : otherEndHouses) {
                    overlaps |= plotsOverlap(house, other);
                }
                if (!overlaps) {
                    return house;
                }
            }
            // Nothing fits here: cut the last stretch of road off and try further back
            pathSegments.remove(terminal);
            List<RoadSegment> bucket = infra.roadSegmentsByCell.get(infra.batchCell(terminal.frame.origin()));
            if (bucket != null) {
                bucket.remove(terminal);
            }
            infra.segmentIndex.remove(terminal.frame.origin(), terminal);
            for (int i = 0; i < path.points.size(); i++) {
                if (path.points.get(i) == endPoint) {
                    path.points.remove(i);
                    break;
                }
            }
        }
        return null;
    }

    private boolean meetsAnotherRoad(SpherePosition point, int pathIndex, float width) {
        for (RoadSegment segment : infra.segmentsNear(point, width + MAX_ROAD_HALF_WIDTH)) {
            if (segment.pathIndex == pathIndex) {
                continue;
            }
            float limit = (segment.width + width) * 0.5f + 2.0f;
            if (distanceSquaredToSegment(point, segment) <= limit * limit) {
                return true;
            }
        }
        return false;
    }

    /** A house on the line of the road just past its end (in the terminal segment's frame); later attempts are smaller and closer. */
    private House endHouse(RoadSegment terminal, Vector3 end, float dirX, float dirZ, Random rand, int attempt) {
        House house = new House();
        house.seed = rand.nextLong();
        house.frontagePath = terminal.pathIndex;
        house.priority = 2.0f + rand.nextFloat();
        house.sizeScale = (0.9f + rand.nextFloat() * 0.2f) * (1.0f - 0.12f * attempt);
        house.setback = KERB_SETBACK_MIN + rand.nextFloat() * KERB_SETBACK_RANGE * (1.0f - 0.4f * attempt);
        float extensionRoll = rand.nextFloat();
        boolean extensionLeft = rand.nextBoolean();
        float fenceRoll = rand.nextFloat();
        float sideYard = 6.0f + rand.nextFloat() * 8.0f;
        float backYard = 8.0f + rand.nextFloat() * 14.0f;

        float probe = terminal.width + house.setback + 40.0f;
        int nationId = infra.nationManager.getNationAt(world(terminal, end.x + dirX * probe, end.z + dirZ * probe));
        FenceStyle fenceStyle = infra.fenceStyles.get(nationId);
        if (!infra.formStyles.containsKey(nationId)) {
            return null;
        }
        house.nationId = nationId;
        SpherePosition endPlace = world(terminal, end.x, end.z);
        house.richness = infra.areaRichness(nationId, infra.settlementManager.getUrbanness(endPlace), endPlace);
        house.sizeScale *= 1f + 0.35f * house.richness * house.richness;
        house.form = chooseForm(endPlace, nationId, house.seed, house.richness);
        BuildingStyle style = infra.styleOf(house);
        house.width = style.width * house.sizeScale;
        house.depth = style.depth * house.sizeScale;
        if (attempt == 0 && extensionRoll < style.extensionChance * (1f + house.richness)) {
            house.extensionSide = extensionLeft ? -1 : 1;
            house.extensionWidth = house.width * style.extensionWidthRatio;
            house.extensionDepth = house.depth * style.extensionDepthRatio;
        }
        house.fenced = fenceStyle != null && fenceRoll < fenceStyle.fenceChance;

        float offset = terminal.width * 0.5f + house.setback + house.depth * 0.5f;
        placeHouse(house, terminal, end.x + dirX * offset, end.z + dirZ * offset, -dirX, -dirZ);

        float overhang = style.roofOverhang * house.sizeScale;
        house.plotMinX = house.houseMinX() - overhang - sideYard;
        house.plotMaxX = house.houseMaxX() + overhang + sideYard;
        decideDriveway(house, overhang);
        house.plotMinZ = -house.depth * 0.5f - overhang - backYard;
        house.plotMaxZ = house.depth * 0.5f + house.setback - FRONT_FENCE_GAP;

        if (!settleOnGround(house, style) || !isPlotClearOfRoads(house)) {
            return null;
        }
        house.architecture = Math.max(0, infra.architectureWorld.at(house.nationId, SphereMath.direction(house.frame.origin())));
        house.wallVariant = chooseWallVariant(house.frame.origin(), house.nationId, house.seed,
                infra.architectureWorld.style(house.architecture).pattern);
        decideFence(house);
        decideLook(house);
        return house;
    }

    /** Sinks the house into the slope and sizes its walls; false if the ground is too steep or wet. */
    boolean settleOnGround(House house, BuildingStyle style) {
        float halfWidth = house.width * 0.5f;
        float halfDepth = house.depth * 0.5f;
        float[] mainRange = groundRange(house, -halfWidth, halfWidth, -halfDepth, halfDepth);
        float minGround = mainRange[0];
        float maxGround = mainRange[1];
        float[] extensionRange = null;
        if (house.extensionSide != 0) {
            float innerX = house.extensionSide * halfWidth;
            float outerX = house.extensionSide * (halfWidth + house.extensionWidth);
            extensionRange = groundRange(house, Math.min(innerX, outerX), Math.max(innerX, outerX),
                    -halfDepth, -halfDepth + house.extensionDepth);
            minGround = Math.min(minGround, extensionRange[0]);
            maxGround = Math.max(maxGround, extensionRange[1]);
        }
        if (minGround <= infra.seaLevel + 0.5f || maxGround - minGround > BUILDING_MAX_GROUND_DROP) {
            return false;
        }
        if (groundAt(house, house.plotMinX, house.plotMinZ) <= infra.seaLevel || groundAt(house, house.plotMaxX, house.plotMinZ) <= infra.seaLevel) {
            return false;
        }
        // Nor in or over a river or lake, nor on its channel's banks: looked at all over its plot
        // (a lattice fine enough that no stream slips between its points), each point kept
        // RIVER_CLEARANCE clear of any channel
        Rivers rivers = infra.terrainNoise.rivers;
        boolean riverAbout = false;
        if (rivers != null) {
            // (first a quick look: whether any channel reaches the texels its middle and corners are
            // in, which are wider than any plot; most plots are nowhere near one)
            float[][] corners = { { 0f, 0f }, { house.plotMinX, house.plotMinZ }, { house.plotMaxX, house.plotMinZ },
                    { house.plotMinX, house.plotMaxZ }, { house.plotMaxX, house.plotMaxZ } };
            for (float[] c : corners) {
                GeodesicChunkGrid.Direction d = SphereMath.direction(world(house, c[0], 0f, c[1]));
                if (!Float.isNaN(rivers.waterAt(d, groundAt(house, c[0], c[1]) - 1f))) return false;
                riverAbout |= rivers.channelsAbout(d);
            }
        }
        if (riverAbout) {
            int across = Math.max(2, (int) Math.ceil((house.plotMaxX - house.plotMinX) / RIVER_LOOK_STEP));
            int deep = Math.max(2, (int) Math.ceil((house.plotMaxZ - house.plotMinZ) / RIVER_LOOK_STEP));
            for (int i = 0; i <= across; i++) {
                for (int j = 0; j <= deep; j++) {
                    float x = house.plotMinX + (house.plotMaxX - house.plotMinX) * i / across;
                    float z = house.plotMinZ + (house.plotMaxZ - house.plotMinZ) * j / deep;
                    GeodesicChunkGrid.Direction d = SphereMath.direction(world(house, x, 0f, z));
                    if (rivers.channelNear(d, RIVER_CLEARANCE)) return false;
                }
            }
        }

        // Sink the base below the lowest corner and grow the walls so slopes never expose a gap
        house.baseY = minGround - BUILDING_FOUNDATION_DEPTH;
        house.mainWallHeight = (mainRange[1] - house.baseY) + style.wallHeight * house.sizeScale;
        if (extensionRange != null) {
            house.extensionWallHeight = (extensionRange[1] - house.baseY)
                    + style.wallHeight * house.sizeScale * style.extensionHeightRatio;
        }
        house.doorBase = groundAt(house, 0.0f, halfDepth) - house.baseY;
        return true;
    }

    private float[] groundRange(House house, float minX, float maxX, float minZ, float maxZ) {
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            float ground = groundAt(house, (corner & 1) == 0 ? minX : maxX, (corner & 2) == 0 ? minZ : maxZ);
            low = Math.min(low, ground);
            high = Math.max(high, ground);
        }
        return new float[] { low, high };
    }

    /**
     * Exact test of the rotated plot against nearby carriageways and turning
     * circles. The road the house fronts only has to clear the house itself,
     * since it may bend into the corners of the front garden; the fence simply
     * stops where it does.
     */
        private boolean isPlotClearOfRoads(House house) {
        BuildingStyle style = infra.styleOf(house);
        float overhang = style.roofOverhang * house.sizeScale;
        float plotHalfX = (house.plotMaxX - house.plotMinX) * 0.5f;
        float plotHalfZ = (house.plotMaxZ - house.plotMinZ) * 0.5f;
        float houseHalfX = (house.houseMaxX() - house.houseMinX()) * 0.5f + overhang;
        float houseHalfZ = house.depth * 0.5f + overhang;
        float[] plotCentre = { house.plotCentreLocalX(), house.plotCentreLocalZ() };
        float[] houseCentre = { (house.houseMinX() + house.houseMaxX()) * 0.5f, 0.0f };
        float reach = (float) Math.sqrt(plotHalfX * plotHalfX + plotHalfZ * plotHalfZ) + PLOT_HIGHWAY_MARGIN + MAX_ROAD_HALF_WIDTH;

        for (RoadSegment segment : infra.segmentsNear(world(house, plotCentre[0], 0f, plotCentre[1]), reach)) {
            boolean ownRoad = segment.pathIndex == house.frontagePath;
            float[] boxCentre = ownRoad ? houseCentre : plotCentre;
            float halfX = ownRoad ? houseHalfX : plotHalfX;
            float halfZ = ownRoad ? houseHalfZ : plotHalfZ;

            // Segment end points in the house's frame
            float[] a = local(house, segment.from);
            float[] b = local(house, segment.to);
            float margin = segment.roadClass == RoadPath.RoadClass.HIGHWAY ? PLOT_HIGHWAY_MARGIN : PLOT_ROAD_MARGIN;
            float limit = segment.width * 0.5f + margin;
            if (segmentToBoxDistanceSquared(a[0] - boxCentre[0], a[1] - boxCentre[1], b[0] - boxCentre[0], b[1] - boxCentre[1],
                    halfX, halfZ) < limit * limit) {
                return false;
            }
        }
        return true;
    }

    /** Separating-axis test between two rotated plot rectangles, worked in a's frame. */
    private static boolean plotsOverlap(House a, House b) {
        float reach = a.plotRadius() + b.plotRadius() + PLOT_GAP;
        SpherePosition centreA = world(a, a.plotCentreLocalX(), 0f, a.plotCentreLocalZ());
        SpherePosition centreB = world(b, b.plotCentreLocalX(), 0f, b.plotCentreLocalZ());
        // (a quick look first: straight through the ground is never further than along it)
        double cx = centreB.x() - centreA.x(), cy = centreB.y() - centreA.y(), cz = centreB.z() - centreA.z();
        if (cx * cx + cy * cy + cz * cz > (double) reach * reach) {
            return false;
        }
        float[] centre = local(a, centreB);
        float dx = centre[0] - a.plotCentreLocalX();
        float dz = centre[1] - a.plotCentreLocalZ();
        if (dx * dx + dz * dz > reach * reach) {
            return false;
        }

        float[][] axesA = { { 1f, 0f }, { 0f, 1f } };
        float[][] axesB = plotAxes(a, b);
        float[] halfA = { (a.plotMaxX - a.plotMinX) * 0.5f, (a.plotMaxZ - a.plotMinZ) * 0.5f };
        float[] halfB = { (b.plotMaxX - b.plotMinX) * 0.5f, (b.plotMaxZ - b.plotMinZ) * 0.5f };
        float[][] testAxes = { axesA[0], axesA[1], axesB[0], axesB[1] };
        for (float[] axis : testAxes) {
            float radiusA = halfA[0] * Math.abs(dot(axesA[0], axis)) + halfA[1] * Math.abs(dot(axesA[1], axis));
            float radiusB = halfB[0] * Math.abs(dot(axesB[0], axis)) + halfB[1] * Math.abs(dot(axesB[1], axis));
            if (Math.abs(dx * axis[0] + dz * axis[1]) > radiusA + radiusB + PLOT_GAP) {
                return false;
            }
        }
        return true;
    }

    /** House b's local X and Z axes as level directions in a's frame. */
    private static float[][] plotAxes(House a, House b) {
        double[] x = b.frame.xAxis(), z = b.frame.zAxis();
        double[] bx = a.frame.localDirection(x[0], x[1], x[2]), bz = a.frame.localDirection(z[0], z[1], z[2]);
        double lx = Math.hypot(bx[0], bx[2]), lz = Math.hypot(bz[0], bz[2]);
        return new float[][] { { (float) (bx[0] / lx), (float) (bx[2] / lx) }, { (float) (bz[0] / lz), (float) (bz[2] / lz) } };
    }

    /**
     * Poorer households' houses are weathered, streaked and stained, more so the poorer; the
     * richest are dressed up with pillars round the door and a cornice under the roof.
     */
    private void decideLook(House house) {
        Random rand = new Random(house.seed ^ 0x3EA7E2L);
        float poor = Math.max(0f, Math.min(1f, (0.5f - house.richness) / 0.4f));
        house.weathering = rand.nextFloat() < 0.2f + 0.8f * poor ? Math.min(3, Math.round(poor * (0.6f + 0.6f * rand.nextFloat()) * 3f)) : 0;
        house.grand = house.richness > 0.5f && rand.nextFloat() < (house.richness - 0.5f) * 2.2f;
    }

    /**
     * Whether the house has a driveway down one side for a car, the likelier the richer the
     * area; the plot is widened to take it. It goes on the side away from any extension.
     */
    private void decideDriveway(House house, float overhang) {
        house.drivewaySide = 0;
        Random rand = new Random(house.seed ^ 0xD71E3AL);
        float urbanness = infra.settlementManager.getUrbanness(house.frame.origin());
        if (rand.nextFloat() >= 0.85f * infra.areaRichness(house.nationId, urbanness, house.frame.origin())) return;
        int side = house.extensionSide != 0 ? -house.extensionSide : (rand.nextBoolean() ? 1 : -1);
        house.drivewaySide = side;
        if (side > 0) {
            house.driveMinX = house.houseMaxX() + overhang + 1f;
            house.driveMaxX = house.driveMinX + DRIVEWAY_WIDTH;
            house.plotMaxX = Math.max(house.plotMaxX, house.driveMaxX + 3f);
        } else {
            house.driveMaxX = house.houseMinX() - overhang - 1f;
            house.driveMinX = house.driveMaxX - DRIVEWAY_WIDTH;
            house.plotMinX = Math.min(house.plotMinX, house.driveMinX - 3f);
        }
    }

    /**
     * Whether and how a house is fenced. Security-minded nations put tall fences round many
     * of their houses, most of all in built-up areas; otherwise the nation's usual share of
     * plots are fenced, with the outline varying from house to house.
     */
    private void decideFence(House house) {
        FenceStyle style = infra.fenceStyles.get(house.nationId);
        if (style == null) {
            house.fenced = false;
            return;
        }
        Random rand = new Random(house.seed ^ 0x5EC0F3A5L);
        float urbanness = infra.settlementManager.getUrbanness(house.frame.origin());
        float secureChance = style.securityLevel * (0.06f + 0.9f * smoothstep(0.05f, 0.45f, urbanness));
        house.secure = rand.nextFloat() < secureChance;
        house.fenced = house.fenced || house.secure;
        float shape = rand.nextFloat();
        house.fenceShape = house.secure || shape < 0.55f ? FENCE_PLOT : shape < 0.8f ? FENCE_IRREGULAR : FENCE_FRONT_ONLY;
    }

    /**
     * Fenced neighbours along the same road close the gap between them so one fence runs
     * between their gardens, and some country houses fence off an extra paddock behind.
     */
    void connectNeighbourFences() {
        for (House house : infra.houses) {
            if (!house.fenced) continue;
            // (other plots may have grown since they were filed, by up to half the gap a side)
            SpherePosition centre = world(house, house.plotCentreLocalX(), 0f, house.plotCentreLocalZ());
            for (House other : plotIndex.near(centre, house.plotRadius() + FENCE_JOIN_GAP * 2f)) {
                if (other == house || !other.fenced || other.frontagePath != house.frontagePath) continue;
                float[] otherX = plotAxes(house, other)[0];
                float turn = (float) Math.abs(Math.toDegrees(Math.atan2(otherX[1], otherX[0])));
                if (turn > 25f) continue;
                float[] local = local(house, other.frame.origin());
                if (Math.abs(local[1]) > house.depth) continue;
                if (local[0] > 0) {
                    float gap = (local[0] + other.plotMinX) - house.plotMaxX;
                    if (gap > -1f && gap < FENCE_JOIN_GAP) {
                        house.plotMaxX += Math.max(0f, gap * 0.5f);
                        house.sharedRight = true;
                    }
                } else {
                    float gap = house.plotMinX - (local[0] + other.plotMaxX);
                    if (gap > -1f && gap < FENCE_JOIN_GAP) {
                        house.plotMinX -= Math.max(0f, gap * 0.5f);
                    }
                }
            }
        }
        for (House house : infra.houses) {
            if (!house.fenced || house.secure || house.fenceShape != FENCE_PLOT) continue;
            Random rand = new Random(house.seed ^ 0x9ADD0CL);
            if (rand.nextFloat() > 0.3f || infra.settlementManager.getUrbanness(house.frame.origin()) > 0.2f) continue;
            // A paddock behind the garden, as wide as the plot or narrower
            float length = 15f + rand.nextFloat() * 35f;
            float inset = rand.nextFloat() * (house.plotMaxX - house.plotMinX) * 0.3f;
            float[] paddock = { house.plotMinX + (rand.nextBoolean() ? inset : 0f), house.plotMaxX - (rand.nextBoolean() ? inset : 0f),
                    house.plotMinZ - length, house.plotMinZ };
            if (isAreaFree(house, paddock)) house.paddock = paddock;
        }
    }

    /** True if a local rectangle of a house's land touches no road and no other plot. */
    private boolean isAreaFree(House house, float[] area) {
        for (int i = 0; i <= 4; i++) {
            for (int j = 0; j <= 4; j++) {
                float lx = area[0] + (area[1] - area[0]) * i / 4f;
                float lz = area[2] + (area[3] - area[2]) * j / 4f;
                SpherePosition place = world(house, lx, 0f, lz);
                if (infra.isRoadLocation(place, 4f)) return false;
                if (TerrainMesh.getLayeredHeight(place, infra.terrainNoise) <= infra.seaLevel) return false;
                if (infra.terrainNoise.rivers != null && infra.terrainNoise.rivers.channelNear(SphereMath.direction(place), RIVER_CLEARANCE)) return false;
                // (plots may have grown since they were filed, by up to half the join gap a side)
                for (House other : plotIndex.near(place, 2f + FENCE_JOIN_GAP)) {
                    if (other == house) continue;
                    float[] local = local(other, place);
                    if (local[0] >= other.plotMinX - 2f && local[0] <= other.plotMaxX + 2f
                            && local[1] >= other.plotMinZ - 2f && local[1] <= other.plotMaxZ + 2f) return false;
                }
            }
        }
        return true;
    }

    static final float BUILDING_FOUNDATION_DEPTH = 3.0f;

    static final float BUILDING_MAX_GROUND_DROP = 25.0f;

    // Neighbouring fenced plots closer than this join up, sharing one fence between them
    static final float FENCE_JOIN_GAP = 26.0f;

    // Town houses sit right at the kerb; out in the country they stand back behind a front garden
    static final float KERB_SETBACK_MIN = 10.0f;

    static final float KERB_SETBACK_RANGE = 6.0f;

    static final float MAX_FRONT_GARDEN = 70.0f;

    // More placement attempts than wanted houses, since some land on slopes, roads or neighbours
    static final float PLACEMENT_OVERSAMPLE = 2.5f;

    static final float PLOT_GAP = 2.0f;

    static final float PLOT_HIGHWAY_MARGIN = 7.0f;

    static final float PLOT_ROAD_MARGIN = 2.0f;

    // Houses' plots are looked over for rivers at points this far apart at most, each kept this clear of a channel
    static final float RIVER_LOOK_STEP = 6f, RIVER_CLEARANCE = 6f;

    // Houses per 100 units of road per side: a rural trickle per nation, rising steeply with urbanness
    static final double RURAL_FRONTAGE_SCALE = 200.0;

    static final float URBAN_DENSITY_EXPONENT = 1.3f;
}
