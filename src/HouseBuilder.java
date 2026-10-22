import java.util.List;
import java.util.Random;

/**
 * Builds a house into its nation's batch, in the house's own frame: its walls (shaped by the
 * local architecture, with relief and creases), roof, door, windows with curtains or blinds
 * and the rooms behind them, posters and flags on its walls, and a flagpole in its garden.
 * Ornament is added by HouseDressing.
 */
final class HouseBuilder extends InfraPart {

    HouseBuilder(InfrastructureManager infra) {
        super(infra);
    }

        void bakeHouse(NationBatch batch, House house, List<InfrastructureObject> objects) {
        BuildingStyle style = infra.styleOf(house);
        float scale = house.sizeScale;
        float overhang = style.roofOverhang * scale;
        float[][] footprint = footprint(style, house.width, house.depth, 0.0f, 0.0f);
        boolean smooth = style.footprint == BuildingStyle.Footprint.ROUND;

                BuildingStyle.WallVariant variant = infra.wallVariants.get(house.wallVariant);
        // Shops are painted, standing out from the houses round them
        String wallPart = house.shop != null ? "shopwall" + house.shop.colour : InfrastructureManager.wallVariantTextureName(house.wallVariant);
        // Weathered houses' walls and roofs are drawn stained (see BatchPart.weathering)
        String worn = house.shop == null && house.weathering > 0 ? WORN + house.weathering : "";
        wallPart += worn;
        String roofPart = "roof" + worn;
        HouseWall[] walls = extrudeWalls(batch.builder(wallPart), house, footprint, house.mainWallHeight, style.taper, smooth,
                variant.tileWidth, variant.tileHeight);
        // Dressed in its architecture, as far as the household can afford (see ArchitectureStyle.dress)
        ArchitectureStyle arch = infra.architectureWorld.style(house.architecture);
        ArchitectureStyle.Dress dress = arch.dress(house.seed, house.richness, house.grand, style.tower);
        // (a shop's front is its shop window, kept flat)
        boolean shaped = dress.relief && house.shop == null && !style.tower;
        if (shaped) shapeWalls(walls, house, style, arch);

        if (style.footprint == BuildingStyle.Footprint.BOX) {
                        float[] roofV = infra.roofVertices.get(InfrastructureManager.roofKey(house.nationId, house.form));
            int[] roofIdx = infra.roofIndices.get(InfrastructureManager.roofKey(house.nationId, house.form));
            bake(batch.builder(roofPart), roofV, roofIdx, house, house.width * style.taper + 2.0f * overhang, style.roofHeight * scale,
                    house.depth * style.taper + 2.0f * overhang, 0, house.mainWallHeight, 0, style.roofTileSize);
        } else {
            centredRoof(batch.builder(roofPart), house, footprint, style, house.mainWallHeight, overhang, smooth);
        }

        // The door sits in the middle of the wall that faces the road
        HouseWall front = frontWall(walls);
        float doorHalfW = Math.min(style.doorWidth * scale, front.length * 0.7f) * 0.5f;
        float doorH = style.doorHeight * scale;
        float[][] door = wallPanel(batch.builder("door"), house, front, 0.5f, house.doorBase + doorH * 0.5f, doorHalfW, doorH * 0.5f, DOOR_OFFSET);
        // (in the house's frame, for the people going in and out)
        house.doorCorners = new float[4][];
        for (int i = 0; i < 4; i++) house.doorCorners[i] = door[i].clone();
        if (house.doorway != null) house.doorway.doorCorners = house.doorCorners;
        if (house.drivewaySide != 0 && house.shop == null) infra.gardens.bakeDriveway(batch, house);
        if (house.grand || (house.shop != null && house.richness > 0.6f)) infra.houseDressing.dressUp(batch, house, walls, door, scale);

        if (house.extensionSide != 0) {
            float centreX = house.extensionSide * (house.width + house.extensionWidth) * 0.5f;
            float centreZ = (house.extensionDepth - house.depth) * 0.5f;
            float extensionOverhang = overhang * 0.6f;
            // Nudged outward a hair so the shared wall never z-fights the house
            float[][] wing = footprint(style, house.extensionWidth + 0.2f, house.extensionDepth,
                    centreX + house.extensionSide * 0.1f, centreZ);
                        HouseWall[] wingWalls = extrudeWalls(batch.builder(wallPart), house, wing, house.extensionWallHeight, 1.0f, false,
                    variant.tileWidth, variant.tileHeight);
            if (shaped) shapeWalls(wingWalls, house, style, arch);
            bake(batch.builder(roofPart), infra.roofVertices.get(InfrastructureManager.roofKey(house.nationId, house.form)), infra.roofIndices.get(InfrastructureManager.roofKey(house.nationId, house.form)), house,
                    house.extensionWidth + 2.0f * extensionOverhang, style.roofHeight * scale * style.extensionHeightRatio,
                    house.extensionDepth + 2.0f * extensionOverhang, centreX, house.extensionWallHeight, centreZ, style.roofTileSize);
            HouseWall wingFront = frontWall(wingWalls);
            if (wingFront.length > style.windowWidth * scale * 1.8f) {
                addWindow(batch, house, style, wingFront, 0.5f,
                        house.doorBase + style.wallHeight * scale * style.extensionHeightRatio * 0.45f);
            }
            emitWalls(batch.builder(wallPart), house, wingWalls, variant.tileWidth, variant.tileHeight);
        }
        appendWindows(batch, house, style, walls, front);
        Random signs = new Random(house.seed ^ 0x51C4L);
        if (house.shop != null) infra.shopBuilder.shopFront(batch, house, style, front, objects);
        float urbanHere = infra.settlementManager.getUrbanness(house.frame.origin());
        // Posters: on most shops, and on plenty of houses, more in town and in nations keen on them
        float keen = infra.posterKeenness.getOrDefault(house.nationId, 0.6f);
        if (house.shop != null ? signs.nextFloat() < 0.6f + 0.3f * keen : urbanHere > 0.02f && signs.nextFloat() < keen * (0.6f + 0.4f * urbanHere)) {
            wallPoster(house, walls, front, objects, signs);
        }
        // Now and then a flag flies from a short pole above the door, more often in town
        float urbanness = infra.settlementManager.getUrbanness(house.frame.origin());
        if (new Random(house.seed ^ 0xF1A6L).nextFloat() < infra.patriotismOf(house.nationId) * (0.1f + 0.3f * urbanness)) {
            wallFlag(batch, house, front, house.doorBase + style.doorHeight * scale + 3.5f);
        }
        if (style.tower) infra.houseDressing.dressTower(batch, house, style, walls, arch, dress);
        else infra.houseDressing.dressHouse(batch, house, style, walls, footprint, arch, dress);
        emitWalls(batch.builder(wallPart), house, walls, variant.tileWidth, variant.tileHeight);

        float roofTop = house.baseY + house.mainWallHeight + style.roofHeight * scale;
        batch.include(0f, roofTop, 0f, Math.max(house.plotMaxX - house.plotMinX, house.plotMaxZ - house.plotMinZ));
    }

    // Marks a part drawn weathered: the part's name, this, then how badly (1 to 3)
    static final String WORN = "~worn";

    static RoofOutline roofOutline(House house, BuildingStyle style, float[][] footprint) {
        RoofOutline roof = new RoofOutline();
        float scale = house.sizeScale, overhang = style.roofOverhang * scale;
        float wallTop = house.mainWallHeight, top = wallTop + style.roofHeight * scale;
        BuildingStyle.RoofType type = style.roofType;
        float eaveY = type == BuildingStyle.RoofType.FLAT ? top : wallTop;
        if (style.footprint == BuildingStyle.Footprint.BOX) {
            float hw = house.width * style.taper * 0.5f + overhang, hd = house.depth * style.taper * 0.5f + overhang;
            // (a mono-pitch roof is high along the back)
            float backY = type == BuildingStyle.RoofType.SHED ? top : eaveY;
            roof.eaves = new float[][] { { -hw, eaveY, hd }, { hw, eaveY, hd }, { hw, backY, -hd }, { -hw, backY, -hd } };
            roof.horned = new boolean[] { true, true, true, true };
            roof.centre = new float[] { 0f, eaveY, 0f };
            roof.peak = new float[] { 0f, top, 0f };
            switch (type) {
                case GABLE -> {
                    roof.edges.add(new float[][] { roof.eaves[0], roof.eaves[1] });
                    roof.edges.add(new float[][] { roof.eaves[2], roof.eaves[3] });
                    roof.ridges.add(new float[][] { { -hw, top, 0f }, { hw, top, 0f } });
                }
                case HIP -> {
                    for (int i = 0; i < 4; i++) roof.edges.add(new float[][] { roof.eaves[i], roof.eaves[(i + 1) % 4] });
                    float r = style.hipRidgeHalfLength() * hw * 2f;
                    roof.ridges.add(new float[][] { { -r, top, 0f }, { r, top, 0f } });
                }
                case SHED -> {
                    roof.edges.add(new float[][] { roof.eaves[0], roof.eaves[1] });
                    roof.ridges.add(new float[][] { roof.eaves[3], roof.eaves[2] });
                    roof.peak = new float[] { 0f, top, -hd + 1.5f };
                }
                default -> {
                    for (int i = 0; i < 4; i++) roof.edges.add(new float[][] { roof.eaves[i], roof.eaves[(i + 1) % 4] });
                }
            }
            return roof;
        }
        // Round and polygonal houses: the eaves as centredRoof builds them
        int n = footprint.length;
        float cx = 0, cz = 0, radius = 0;
        for (float[] p : footprint) { cx += p[0]; cz += p[1]; }
        cx /= n; cz /= n;
        for (float[] p : footprint) radius += Math.hypot(p[0] - cx, p[1] - cz);
        radius /= n;
        float grow = style.taper * (1.0f + overhang / Math.max(1.0f, radius * style.taper));
        roof.eaves = new float[n][];
        roof.horned = new boolean[n];
        boolean round = style.footprint == BuildingStyle.Footprint.ROUND;
        for (int i = 0; i < n; i++) {
            roof.eaves[i] = new float[] { cx + (footprint[i][0] - cx) * grow, eaveY, cz + (footprint[i][1] - cz) * grow };
            roof.horned[i] = !round || i % 5 == 2;
        }
        for (int i = 0; i < n; i++) roof.edges.add(new float[][] { roof.eaves[i], roof.eaves[(i + 1) % n] });
        roof.centre = new float[] { cx, eaveY, cz };
        roof.peak = new float[] { cx, top, cz };
        return roof;
    }

    /**
     * Shapes walls' surfaces in an architecture's relief: bulging between the corners, swelling,
     * pinched in or creased across (once up the wall, or floor by floor), or fluted or studded,
     * with the flutes and studs a whole number to a wall so each starts and ends flat. No deeper
     * than a small share of the wall's width.
     */
    private static void shapeWalls(HouseWall[] walls, House house, BuildingStyle style, ArchitectureStyle arch) {
        float height = walls[0].topA[1];
        float rise = Math.max(1f, height - house.doorBase);
        float floor = 0f;
        if (arch.reliefPerFloor && style.floors > 1) {
            // (floors fitted exactly into the wall, so the shape ends where the roof sits)
            int floors = Math.max(1, Math.round(rise / (style.wallHeight * house.sizeScale / style.floors)));
            floor = rise / floors;
        }
        float depth = Math.min(arch.reliefDepth * Math.max(0.7f, house.sizeScale), Math.max(0.6f, Math.min(house.width, house.depth) * 0.07f));
        for (HouseWall wall : walls) {
            wall.relief = arch.relief;
            wall.reliefDepth = depth;
            wall.reliefBase = house.doorBase;
            wall.reliefFloor = floor;
            wall.reliefPitch = arch.reliefPitch * Math.max(0.7f, house.sizeScale);
        }
    }

    /**
     * Where a shaped wall's surface needs cutting so it can bend: s along it (0..1) and heights
     * up it, at every crease and often enough round its curves.
     */
    private static void reliefCuts(HouseWall wall, java.util.TreeSet<Float> cuts, java.util.TreeSet<Float> levels) {
        if (wall.relief == ArchitectureStyle.Relief.NONE) return;
        float height = wall.topA[1];
        float rise = height - wall.reliefBase;
        if (rise <= 0f) return;
        boolean creased = creased(wall.relief);
        float period = wall.reliefFloor > 0f ? wall.reliefFloor : rise;
        int steps = creased ? 2 : 6;
        if (wall.relief == ArchitectureStyle.Relief.STUDDED) {
            int rows = Math.max(1, Math.round(period / Math.max(2f, wall.reliefPitch)));
            steps = rows * 2;
        }
        for (float y = wall.reliefBase; y < height - 1e-3f; y += period) {
            for (int k = 0; k <= steps; k++) levels.add(Math.min(height, y + period * k / steps));
        }
        switch (wall.relief) {
            case PILLOW -> { for (int k = 1; k < 8; k++) cuts.add(k / 8f); }
            case FLUTED, STUDDED -> {
                int ribs = Math.max(1, Math.round(wall.length / Math.max(2f, wall.reliefPitch)));
                for (int k = 1; k < ribs * 2; k++) cuts.add(k / (ribs * 2f));
            }
            default -> { }
        }
    }

    /**
     * Raises walls around a footprint. With taper below 1 the walls lean inward and above 1
     * they flare out; round footprints get smooth normals so they shade as one curved wall.
     * Texture coordinates are in world units divided by the texture's repeat size, so bricks
     * stay the same size on every house however wide or tall it is.
     */
    private HouseWall[] extrudeWalls(MeshBuilder builder, House house, float[][] footprint, float height, float taper,
                                     boolean smooth, float tileWidth, float tileHeight) {
        int n = footprint.length;
        float cx = 0, cz = 0;
        for (float[] p : footprint) { cx += p[0]; cz += p[1]; }
        cx /= n; cz /= n;

        HouseWall[] walls = new HouseWall[n];
        for (int i = 0; i < n; i++) {
            infra.recordSegment(world(house, footprint[i][0], 0f, footprint[i][1]),
                    world(house, footprint[(i + 1) % n][0], 0f, footprint[(i + 1) % n][1]), 0.6f);
        }
        for (int i = 0; i < n; i++) {
            float[] a = footprint[i], b = footprint[(i + 1) % n];
            HouseWall wall = new HouseWall();
            wall.index = i;
            wall.bottomA = new float[] { a[0], 0, a[1] };
            wall.bottomB = new float[] { b[0], 0, b[1] };
            wall.topA = new float[] { cx + (a[0] - cx) * taper, height, cz + (a[1] - cz) * taper };
            wall.topB = new float[] { cx + (b[0] - cx) * taper, height, cz + (b[1] - cz) * taper };
            wall.length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            float[] along = sub(wall.bottomB, wall.bottomA);
            float[] up = sub(mid(wall.topA, wall.topB), mid(wall.bottomA, wall.bottomB));
            float[] normal = normalise(cross(along, up));
            float[] outward = { (a[0] + b[0]) * 0.5f - cx, 0, (a[1] + b[1]) * 0.5f - cz };
            if (dot3(normal, outward) < 0) normal = scale3(normal, -1);
            wall.normal = normal;
            walls[i] = wall;
        }

        float u = 0;
        for (int i = 0; i < n; i++) {
            HouseWall wall = walls[i];
            wall.normalA = wall.normal;
            wall.normalB = wall.normal;
            if (smooth) {
                wall.normalA = normalise(add(wall.normal, walls[(i + n - 1) % n].normal));
                wall.normalB = normalise(add(wall.normal, walls[(i + 1) % n].normal));
            }
            wall.slant = dist(mid(wall.topA, wall.topB), mid(wall.bottomA, wall.bottomB));
            wall.u = u;
            u += wall.length;
            float[] level = { wall.normal[0], 0f, wall.normal[2] };
            float[] before = walls[(i + n - 1) % n].normal, after = walls[(i + 1) % n].normal;
            wall.cornerA = normalise(add(level, new float[] { before[0], 0f, before[2] }));
            wall.cornerB = normalise(add(level, new float[] { after[0], 0f, after[2] }));
        }
        return walls;
    }

    /**
     * Builds the walls' faces, leaving a hole wherever a window was cut: the wall is split
     * into horizontal bands at the windows' tops and bottoms, and every band is cut at the
     * sides of every window (on any floor), the pieces inside a window left out. Cutting each
     * band at the same places means neighbouring bands share their corners exactly: a long
     * piece meeting several short ones along a floor line would leave a hairline crack there.
     */
    private void emitWalls(MeshBuilder builder, House house, HouseWall[] walls, float tileWidth, float tileHeight) {
        // (shaped walls' corners bend: every wall is cut at every other's window heights too, so
        // walls meeting at a corner bend it at the same places and leave no crack along it)
        java.util.TreeSet<Float> shared = new java.util.TreeSet<>();
        for (HouseWall wall : walls) {
            if (wall.relief == ArchitectureStyle.Relief.NONE) continue;
            for (float[] o : wall.openings) {
                shared.add(Math.max(0f, Math.min(wall.topA[1], o[2])));
                shared.add(Math.max(0f, Math.min(wall.topA[1], o[3])));
            }
        }
        for (HouseWall wall : walls) {
            float height = wall.topA[1];
            java.util.TreeSet<Float> levels = new java.util.TreeSet<>();
            java.util.TreeSet<Float> cuts = new java.util.TreeSet<>();
            if (wall.relief != ArchitectureStyle.Relief.NONE) levels.addAll(shared);
            levels.add(0f);
            levels.add(height);
            cuts.add(0f);
            cuts.add(1f);
            for (float[] o : wall.openings) {
                levels.add(Math.max(0f, Math.min(height, o[2])));
                levels.add(Math.max(0f, Math.min(height, o[3])));
                cuts.add(Math.max(0f, Math.min(1f, o[0])));
                cuts.add(Math.max(0f, Math.min(1f, o[1])));
            }
            reliefCuts(wall, cuts, levels);
            Float[] ys = levels.toArray(new Float[0]);
            Float[] ss = cuts.toArray(new Float[0]);
            for (int b = 0; b + 1 < ys.length; b++) {
                float ya = ys[b], yb = ys[b + 1];
                if (yb - ya < 1e-4f) continue;
                for (int c = 0; c + 1 < ss.length; c++) {
                    float sa = ss[c], sb = ss[c + 1];
                    if (sb - sa < 1e-4f) continue;
                    boolean hole = false;
                    for (float[] o : wall.openings) {
                        if (o[2] <= ya + 1e-4f && o[3] >= yb - 1e-4f && o[0] <= sa + 1e-4f && o[1] >= sb - 1e-4f) {
                            hole = true;
                            break;
                        }
                    }
                    if (!hole) wallPiece(builder, house, wall, sa, sb, ya, yb, tileWidth, tileHeight);
                }
            }
        }
    }

    private void wallPiece(MeshBuilder builder, House house, HouseWall wall, float s0, float s1, float y0, float y1,
                           float tileWidth, float tileHeight) {
        if (s1 - s0 < 1e-4f) return;
        float height = wall.topA[1];
        float[][] corners = { wallPoint(wall, s0, y0), wallPoint(wall, s1, y0), wallPoint(wall, s1, y1), wallPoint(wall, s0, y1) };
        float[] ss = { s0, s1, s1, s0 }, yy = { y0, y0, y1, y1 };
        int[] index = new int[4];
        // A shaped wall's piece is tilted as its surface is: by its own face if creased, else
        // smoothly by the surface's slope at each corner
        float[] face = null;
        if (wall.relief != ArchitectureStyle.Relief.NONE && creased(wall.relief)) {
            face = normalise(cross(sub(corners[1], corners[0]), sub(corners[3], corners[0])));
            if (dot3(face, wall.normal) < 0f) face = scale3(face, -1f);
        }
        for (int i = 0; i < 4; i++) {
            float[] n = normalise(lerp(wall.normalA, wall.normalB, ss[i]));
            if (wall.relief != ArchitectureStyle.Relief.NONE) {
                float[] tilted = face != null ? face : surfaceNormal(wall, ss[i], yy[i]);
                n = normalise(add(n, sub(tilted, wall.normal)));
            }
            index[i] = houseVertex(builder, house, corners[i], n, (wall.u + ss[i] * wall.length) / tileWidth,
                    wall.slant * (yy[i] / height) / tileHeight);
        }
        addOutwardQuad(builder, house, index[0], index[1], index[2], index[3], corners[0], corners[1], corners[2], wall.normal);
    }

    /** The outward normal of a shaped wall's surface at s along it and height y (by its slopes either way there). */
    private static float[] surfaceNormal(HouseWall wall, float s, float y) {
        float es = Math.min(0.02f, 1f / Math.max(4f, wall.length)), ey = 0.4f;
        float[] across = sub(wallPoint(wall, Math.min(1f, s + es), y), wallPoint(wall, Math.max(0f, s - es), y));
        float[] up = sub(wallPoint(wall, s, Math.min(wall.topA[1], y + ey)), wallPoint(wall, s, Math.max(0f, y - ey)));
        float[] n = normalise(cross(across, up));
        return dot3(n, wall.normal) < 0f ? scale3(n, -1f) : n;
    }

    /**
     * Roofs for round and polygonal houses, centred over the footprint: a flat slab, a
     * pyramid or cone, a tall spire, or a dome. Texture coordinates run around the eaves and
     * up the slope in world units.
     */
    private void centredRoof(MeshBuilder builder, House house, float[][] footprint, BuildingStyle style, float wallTop,
                             float overhang, boolean smooth) {
        int n = footprint.length;
        float cx = 0, cz = 0, radius = 0;
        for (float[] p : footprint) { cx += p[0]; cz += p[1]; }
        cx /= n; cz /= n;
        for (float[] p : footprint) radius += Math.hypot(p[0] - cx, p[1] - cz);
        radius /= n;
        float grow = style.taper * (1.0f + overhang / Math.max(1.0f, radius * style.taper));
        float roofHeight = style.roofHeight * house.sizeScale;
        float tile = style.roofTileSize;

        float[][] eave = new float[n][];
        for (int i = 0; i < n; i++) {
            eave[i] = new float[] { cx + (footprint[i][0] - cx) * grow, wallTop, cz + (footprint[i][1] - cz) * grow };
        }
        float[] centreBelow = { cx, wallTop, cz };

        if (style.roofType == BuildingStyle.RoofType.DOME) {
            int rings = 6;
            float[][] previous = eave;
            float uRingLength = 0;
            for (int i = 0; i < n; i++) uRingLength += dist(eave[i], eave[(i + 1) % n]);
            for (int r = 1; r <= rings; r++) {
                double phi = Math.PI / 2 * r / rings;
                float ringScale = (float) Math.cos(phi);
                float y = wallTop + roofHeight * (float) Math.sin(phi);
                float[][] ring = new float[n][];
                for (int i = 0; i < n; i++) {
                    ring[i] = new float[] { cx + (eave[i][0] - cx) * ringScale, y, cz + (eave[i][2] - cz) * ringScale };
                }
                float vLow = (r - 1) * (float) (Math.PI / 2 / rings) * radius / tile;
                float vHigh = r * (float) (Math.PI / 2 / rings) * radius / tile;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    float u0 = uRingLength * i / n / tile, u1 = uRingLength * (i + 1) / n / tile;
                    float[] lowA = previous[i], lowB = previous[j], highA = ring[i], highB = ring[j];
                    float[] nLowA = domeNormal(lowA, cx, cz, wallTop, radius * grow, roofHeight);
                    float[] nLowB = domeNormal(lowB, cx, cz, wallTop, radius * grow, roofHeight);
                    float[] nHighA = domeNormal(highA, cx, cz, wallTop, radius * grow, roofHeight);
                    float[] nHighB = domeNormal(highB, cx, cz, wallTop, radius * grow, roofHeight);
                    int a = houseVertex(builder, house, lowA, nLowA, u0, vLow);
                    int b = houseVertex(builder, house, lowB, nLowB, u1, vLow);
                    int c = houseVertex(builder, house, highB, nHighB, u1, vHigh);
                    int d = houseVertex(builder, house, highA, nHighA, u0, vHigh);
                    float[] faceNormal = add(add(nLowA, nLowB), add(nHighA, nHighB));
                    addOutwardQuad(builder, house, a, b, c, d, lowA, lowB, highB, faceNormal);
                }
                previous = ring;
            }
        } else if (style.roofType == BuildingStyle.RoofType.FLAT) {
            float[][] top = new float[n][];
            for (int i = 0; i < n; i++) top[i] = new float[] { eave[i][0], wallTop + roofHeight, eave[i][2] };
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float[] normal = normalise(new float[] { (eave[i][0] + eave[j][0]) * 0.5f - cx, 0, (eave[i][2] + eave[j][2]) * 0.5f - cz });
                float len = dist(eave[i], eave[j]) / tile;
                int a = houseVertex(builder, house, eave[i], normal, 0, 0);
                int b = houseVertex(builder, house, eave[j], normal, len, 0);
                int c = houseVertex(builder, house, top[j], normal, len, roofHeight / tile);
                int d = houseVertex(builder, house, top[i], normal, 0, roofHeight / tile);
                addOutwardQuad(builder, house, a, b, c, d, eave[i], eave[j], top[j], normal);
            }
            flatCap(builder, house, top, new float[] { 0, 1, 0 }, tile);
        } else {
            // Pyramid or spire: every eave edge slopes up to a single apex
            float[] apex = { cx, wallTop + roofHeight, cz };
            float slant = (float) Math.hypot(radius * grow, roofHeight) / tile;
            float u = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float edge = dist(eave[i], eave[j]);
                float[] faceNormal = normalise(cross(sub(eave[j], eave[i]), sub(apex, eave[i])));
                float[] outward = { (eave[i][0] + eave[j][0]) * 0.5f - cx, 0.5f, (eave[i][2] + eave[j][2]) * 0.5f - cz };
                if (dot3(faceNormal, outward) < 0) faceNormal = scale3(faceNormal, -1);
                float[] nA = faceNormal, nB = faceNormal, nApex = faceNormal;
                if (smooth) {
                    nA = normalise(new float[] { eave[i][0] - cx, radius * grow / Math.max(1f, roofHeight) * 0.6f, eave[i][2] - cz });
                    nB = normalise(new float[] { eave[j][0] - cx, radius * grow / Math.max(1f, roofHeight) * 0.6f, eave[j][2] - cz });
                }
                int a = houseVertex(builder, house, eave[i], nA, u / tile, 0);
                int b = houseVertex(builder, house, eave[j], nB, (u + edge) / tile, 0);
                int c = houseVertex(builder, house, apex, nApex, (u + edge * 0.5f) / tile, slant);
                addOutwardTriangle(builder, house, a, b, c, eave[i], eave[j], apex, faceNormal);
                u += edge;
            }
        }
        // Underside of the overhang
        flatCap(builder, house, eave, new float[] { 0, -1, 0 }, tile);
    }

    private static float[] domeNormal(float[] p, float cx, float cz, float baseY, float radius, float height) {
        // Gradient of the ellipsoid ((x - cx)^2 + (z - cz)^2) / r^2 + (y - baseY)^2 / h^2 = 1
        return normalise(new float[] { (p[0] - cx) / (radius * radius), (p[1] - baseY) / Math.max(1f, height * height),
                                       (p[2] - cz) / (radius * radius) });
    }

    /** A convex polygon facing straight up or down, as a triangle fan with planar texture coordinates. */
    private void flatCap(MeshBuilder builder, House house, float[][] ring, float[] normal, float tile) {
        int first = -1;
        for (float[] p : ring) {
            int index = houseVertex(builder, house, p, normal, p[0] / tile, p[2] / tile);
            if (first < 0) first = index;
        }
        for (int i = 1; i < ring.length - 1; i++) {
            addOutwardTriangle(builder, house, first, first + i, first + i + 1, ring[0], ring[i], ring[i + 1], normal);
        }
    }

    /** The wall whose outward normal points most nearly down local +Z, toward the road. */
    private static HouseWall frontWall(HouseWall[] walls) {
        HouseWall best = walls[0];
        for (HouseWall wall : walls) {
            if (wall.normal[2] > best.normal[2]) best = wall;
        }
        return best;
    }

    /** Rows of windows on every wall, one row per floor, leaving room for the front door. */
    private void appendWindows(NationBatch batch, House house, BuildingStyle style, HouseWall[] walls, HouseWall front) {
        float scale = house.sizeScale;
        float windowWidth = style.windowWidth * scale;
        float floorHeight = style.wallHeight * scale / style.floors;
        float doorClearance = style.doorWidth * scale * 0.5f + windowWidth * 0.5f + 3.0f;
        boolean box = style.footprint == BuildingStyle.Footprint.BOX;
        boolean round = style.footprint == BuildingStyle.Footprint.ROUND;

        for (int floor = 0; floor < style.floors; floor++) {
            float centreY = house.doorBase + floorHeight * (floor + 0.55f);
            for (HouseWall wall : walls) {
                if (box && wall.normal[0] * house.extensionSide > 0.5f && floor == 0) {
                    continue;   // the extension covers this wall's ground floor
                }
                int count;
                if (round) {
                    // Curved walls: one window on every other facet, clear of the door
                    int offset = Math.abs(wall.index - front.index);
                    count = (wall.index % 2 == 0 && wall.length > windowWidth * 0.6f && (floor > 0 || offset > 1)) ? 1 : 0;
                } else if (box) {
                    boolean sideWall = Math.abs(wall.normal[0]) > 0.5f;
                    count = sideWall ? style.windowsSide : (wall == front && floor > 0 ? style.windowsFront + 1 : style.windowsFront);
                } else {
                    count = Math.min(3, (int) (wall.length / (windowWidth * 2.4f)));
                }
                if (count <= 0) continue;
                // A shop's ground floor front is its shop window
                if (house.shop != null && wall == front && floor == 0) continue;
                float margin = Math.min(0.45f, (windowWidth * 0.5f + 4.0f) / wall.length);
                if (wall == front && floor == 0) {
                    // Ground-floor front windows sit in two groups either side of the door
                    float gap = doorClearance / wall.length;
                    placeBetween(batch, house, style, wall, margin, 0.5f - gap, (count + 1) / 2, centreY);
                    placeBetween(batch, house, style, wall, 0.5f + gap, 1 - margin, count / 2, centreY);
                } else {
                    placeBetween(batch, house, style, wall, margin, 1 - margin, count, centreY);
                }
            }
        }
    }

    /** Evenly spaces windows between two fractions along a wall, if they fit. */
    private void placeBetween(NationBatch batch, House house, BuildingStyle style, HouseWall wall,
                              float from, float to, int count, float centreY) {
        // A single window just needs its own width (it is clamped to the wall); rows need the span
        if (count <= 0 || (count > 1 && (to - from) * wall.length < style.windowWidth * house.sizeScale * 0.8f)
                || to <= from) {
            return;
        }
        for (int i = 0; i < count; i++) {
            float s = count == 1 ? (from + to) * 0.5f : from + (to - from) * i / (count - 1);
            addWindow(batch, house, style, wall, s, centreY);
        }
    }

    /**
     * A window: a hole through the wall, lined (sides, sill and head) back to a dark panel,
     * the drawn curtains painted on that flat panel, and a frame round the outside. No glass
     * (only shop windows have it): the lining all round hides the inside.
     */
    private void addWindow(NationBatch batch, House house, BuildingStyle style, HouseWall wall, float s, float centreY) {
        float halfW = Math.min(style.windowWidth * house.sizeScale, wall.length * 0.8f) * 0.5f;
        float halfH = style.windowHeight * house.sizeScale * 0.5f;
        float s0 = Math.max(0.02f, s - halfW / wall.length), s1 = Math.min(0.98f, s + halfW / wall.length);
        float y0 = centreY - halfH, y1 = Math.min(centreY + halfH, wall.topA[1] - 0.5f);
        if (s1 - s0 < 0.02f || y1 - y0 < 1f) return;
        wall.openings.add(new float[] { s0, s1, y0, y1 });

        float[] n = wall.normal;
        // Behind the opening, the household's drawn curtains, painted on a panel (see
        // ArchitectureTextures.curtains), with the opening lined from the wall's face back to it
        // all round, so nothing shows through round the curtains
        int colour = (int) Math.floorMod(house.seed, (long) CURTAIN_COLOURS);
        float[] along = normalise(sub(wallPoint(wall, s1, y0), wallPoint(wall, s0, y0)));
        float[] up = { 0f, 1f, 0f };
        MeshBuilder reveal = batch.builder("frame");
        localQuad(reveal, house, inset(wall, s0, y0, 0), inset(wall, s0, y1, 0), inset(wall, s0, y1, CURTAIN_DEPTH), inset(wall, s0, y0, CURTAIN_DEPTH), along);
        localQuad(reveal, house, inset(wall, s1, y0, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, CURTAIN_DEPTH), inset(wall, s1, y0, CURTAIN_DEPTH), scale3(along, -1));
        localQuad(reveal, house, inset(wall, s0, y0, 0), inset(wall, s1, y0, 0), inset(wall, s1, y0, CURTAIN_DEPTH), inset(wall, s0, y0, CURTAIN_DEPTH), up);
        localQuad(reveal, house, inset(wall, s0, y1, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, CURTAIN_DEPTH), inset(wall, s0, y1, CURTAIN_DEPTH), scale3(up, -1));
        localQuad(batch.builder("blind" + colour), house, inset(wall, s0, y0, CURTAIN_DEPTH), inset(wall, s1, y0, CURTAIN_DEPTH),
                inset(wall, s1, y1, CURTAIN_DEPTH), inset(wall, s0, y1, CURTAIN_DEPTH), n);
        // (a tower's hundreds of upper windows have no frames: far too much to build)
        float floorHeight = style.wallHeight * house.sizeScale / style.floors;
        if (style.tower && centreY - house.doorBase > floorHeight * TOWER_DETAILED_FLOORS) return;

        // The frame: a flat surround just proud of the wall
        float f = style.frameSize;
        if (f > 0f) {
            float fs = f / wall.length;
            MeshBuilder frame = batch.builder("frame");
            float[][][] strips = {
                { { s0 - fs, y1 }, { s1 + fs, y1 }, { s1 + fs, y1 + f }, { s0 - fs, y1 + f } },
                { { s0 - fs, y0 - f }, { s1 + fs, y0 - f }, { s1 + fs, y0 }, { s0 - fs, y0 } },
                { { s0 - fs, y0 }, { s0, y0 }, { s0, y1 }, { s0 - fs, y1 } },
                { { s1, y0 }, { s1 + fs, y0 }, { s1 + fs, y1 }, { s1, y1 } } };
            for (float[][] strip : strips) {
                float[][] c = new float[4][];
                for (int i = 0; i < 4; i++) c[i] = inset(wall, Math.max(0f, Math.min(1f, strip[i][0])), strip[i][1], -FRAME_OFFSET);
                localQuad(frame, house, c[0], c[1], c[2], c[3], n);
            }
        }
    }

    // How many of a tower's lowest floors have framed windows
    private static final int TOWER_DETAILED_FLOORS = 2;

    // How deep a window's curtains are set back into the wall
    private static final float CURTAIN_DEPTH = 1.0f;

    // How far back a shop window's glass sits
    static final float GLASS_SETBACK = 0.4f;

    static final int CURTAIN_COLOURS = 4;

    /**
     * Now and then a poster pasted on a wall, often an advert for a shop nearby: on a
     * stretch of wall clear of windows and doors, leaning with the wall if it leans and
     * curving round it if it's round, and above the fence if the house has one, so the
     * fence doesn't hide it.
     */
    private void wallPoster(House house, HouseWall[] walls, HouseWall front, List<InfrastructureObject> objects, Random rand) {
        float scale = 0.5f + rand.nextFloat() * 0.2f;
        float w = 30f * scale, h = 22f * scale;
        // The middle of the footprint, for how round a curved wall is
        float cx = 0f, cz = 0f;
        for (HouseWall wall : walls) { cx += wall.bottomA[0]; cz += wall.bottomA[2]; }
        cx /= walls.length;
        cz /= walls.length;
        FenceStyle fence = house.fenced ? infra.fenceStyles.get(house.nationId) : null;
        float fenceHeight = fence == null ? 0f : house.secure ? fence.securityHeight : fence.height;
        int start = rand.nextInt(walls.length);
        for (int k = 0; k < walls.length; k++) {
            HouseWall wall = walls[(start + k) % walls.length];
            boolean curved = wall.normalA != wall.normal;
            float[] mid = mid(wall.bottomA, wall.bottomB);
            float radius = curved ? (float) Math.hypot(mid[0] - cx, mid[2] - cz) : 0f;
            // A round wall is one curve, made of short straight pieces: the poster may span several
            if (curved ? radius * 2f * (float) Math.PI < w * 3f : wall.length < w + 3f) continue;
            if (wall.topA[1] < house.doorBase + h + 4f) continue;
            float yc = house.doorBase + Math.min(wall.topA[1] - house.doorBase - h * 0.5f - 1.5f, h * 0.5f + 4f);
            // Clear of the ground, wherever it rises against this wall, and of any fence
            float halfAlong = curved ? 0f : (w * 0.5f) / wall.length;
            float ground = -Float.MAX_VALUE;
            for (float s = 0.5f - halfAlong; s <= 0.5f + halfAlong + 1e-4f; s += Math.max(1e-3f, halfAlong)) {
                float[] foot = wallPoint(wall, s, 0f);
                ground = Math.max(ground, groundAt(house, foot[0], foot[2]));
            }
            yc = Math.max(yc, ground + 3f - house.baseY + h * 0.5f);
            if (fenceHeight > 0f) yc = Math.max(yc, ground - house.baseY + fenceHeight + 1.5f + h * 0.5f);
            if (yc + h * 0.5f > wall.topA[1] - 1f) continue;
            boolean clear = true;
            if (curved) {
                // Every piece of the wall it covers, by angle round the middle
                float facing = (float) Math.atan2(mid[2] - cz, mid[0] - cx);
                float halfAngle = (w * 0.5f + 1f) / radius;
                for (HouseWall other : walls) {
                    float[] om = mid(other.bottomA, other.bottomB);
                    float angle = (float) Math.atan2(om[2] - cz, om[0] - cx);
                    float apart = Math.abs((float) Math.atan2(Math.sin(angle - facing), Math.cos(angle - facing)));
                    if (apart > halfAngle + other.length * 0.5f / radius) continue;
                    for (float[] o : other.openings) {
                        if (o[3] > yc - h * 0.5f - 0.8f && o[2] < yc + h * 0.5f + 0.8f) clear = false;
                    }
                    if (other == front && yc - h * 0.5f < house.doorBase + infra.styleOf(house).doorHeight * house.sizeScale + 1f) clear = false;
                }
            } else {
                float halfS = (w * 0.5f + 0.8f) / wall.length;
                for (float[] o : wall.openings) {
                    if (o[1] > 0.5f - halfS && o[0] < 0.5f + halfS && o[3] > yc - h * 0.5f - 0.8f && o[2] < yc + h * 0.5f + 0.8f) {
                        clear = false;
                        break;
                    }
                }
                if (wall == front) {
                    // Beside the door, not over it
                    float doorHalf = infra.styleOf(house).doorWidth * house.sizeScale * 0.5f / wall.length;
                    if (0.5f - halfS < 0.5f + doorHalf + 0.02f && 0.5f + halfS > 0.5f - doorHalf - 0.02f) clear = false;
                }
            }
            if (!clear) continue;
            // Pasted flat against the face, which may lean: the poster leans the same way
            float[] n = horizontal(wall.normal);
            float tilt = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, wall.normal[1]))));
            float[] at = add(wallPoint(wall, 0.5f, yc), scale3(wall.normal, curved ? 0.45f : 0.3f));
            int maxGlyphs = Math.max(1, infra.mainListener != null ? infra.mainListener.getNationAtlasSize(house.nationId) : 10);
            InfrastructureObject poster = InfrastructureObject.createWallSign(fixedTo(house, at, n), house.nationId,
                    w, h, false, false, RoadsideSigns.randomText(rand, maxGlyphs, 40, 120), tilt, radius);
            infra.roadsideSigns.decorateSign(poster, rand, maxGlyphs, true);
            objects.add(poster);
            return;
        }
    }

    /**
     * A tall pole standing in the ground at a point in a house's frame (the batch building in
     * it), with the nation's flag at the top, flying in the world's wind.
     */
    void flagpole(NationBatch batch, House house, float x, float z, float height) {
        float ground = groundAt(house, x, z);
        MeshBuilder pole = batch.builder("flagpole");
        addBox(pole, x, ground - 1f, z, 1f, 0f, 0.45f, 0.45f, height + 1.5f);
        addBox(pole, x, ground + height + 0.4f, z, 1f, 0f, 0.9f, 0.9f, 0.8f);   // the finial
        float flagH = 9f + height * 0.05f, flagW = flagH * FlagDesigner.WIDTH / FlagDesigner.HEIGHT;
        // The wind blows from the same heading (from east towards south) everywhere, put in the house's frame
        SpherePosition foot = world(house, x, 0f, z);
        double[] wind = SphereFrame.at(foot).headingDirection(infra.windAngle);
        double[] local = house.frame.localDirection(wind[0], wind[1], wind[2]);
        float length = (float) Math.max(1e-6, Math.hypot(local[0], local[2]));
        float dirX = (float) local[0] / length, dirZ = (float) local[2] / length;
        flagCloth(batch.builder("flag"), new float[] { x, ground + height - 0.5f, z }, new float[] { dirX, 0f, dirZ },
                new float[] { 0f, -1f, 0f }, flagW, flagH, Math.floorMod(house.seed >> 3, 1000L) * 0.13f);
        batch.include(x, ground + height + 2f, z, flagW + 2f);
        infra.recordCircle(foot, 0.8f);
    }

    /** A short pole angled up and out from a wall, a flag hanging along it. */
    private void wallFlag(NationBatch batch, House house, HouseWall wall, float y) {
        if (y > wall.topA[1] - 2f) return;
        float s = (house.seed & 1) == 0 ? 0.22f : 0.78f;
        float[] base = wallPoint(wall, s, y);
        float[] out = wall.normal;
        float lift = 0.75f;
        float[] dir = normalise(new float[] { out[0], lift, out[2] });
        float length = 11f;
        float[] tip = add(base, scale3(dir, length));
        float[] baseWorld = { base[0], house.baseY + base[1], base[2] }, tipWorld = { tip[0], house.baseY + tip[1], tip[2] };
        MeshBuilder pole = batch.builder("flagpole");
        addRod(pole, baseWorld, tipWorld, 0.3f);
        // The flag hangs from the pole, its top edge along it and its face across the wall
        float[] along = normalise(sub(tipWorld, baseWorld));
        float flagW = length * 0.8f, flagH = flagW * FlagDesigner.HEIGHT / FlagDesigner.WIDTH;
        float[] start = add(baseWorld, scale3(along, length * 0.18f));
        float[] down = { 0f, -1f, 0f };
        flagCloth(batch.builder("flag"), start, along, down, flagW, flagH, Math.floorMod(house.seed, 1000L) * 0.11f);
    }

    /**
     * A flag's cloth: hoist edge at start, flying along fly for width, hanging along down for
     * height, rippling across itself more towards its free end. u runs hoist to fly, v top to bottom.
     */
    private void flagCloth(MeshBuilder builder, float[] start, float[] fly, float[] down, float width, float height, float phase) {
        float[] across = normalise(cross(fly, down));
        int columns = 12, rows = 3;
        int[][] index = new int[columns + 1][rows + 1];
        for (int i = 0; i <= columns; i++) {
            float u = i / (float) columns;
            float wave = (float) Math.sin(u * Math.PI * 3.0 + phase) * 0.9f * u;
            float slope = (float) Math.cos(u * Math.PI * 3.0 + phase) * 0.9f * u * (float) Math.PI * 3.0f / width;
            float[] normal = normalise(sub(across, scale3(fly, slope)));
            for (int j = 0; j <= rows; j++) {
                float v = j / (float) rows;
                // The free end droops a little
                float droop = u * u * height * 0.12f;
                float[] p = add(add(add(start, scale3(fly, u * width)), scale3(down, v * height + droop)), scale3(across, wave));
                // Textures made from images are stored bottom row first, so v counts up from the flag's foot
                index[i][j] = builder.addVertex(p[0], p[1], p[2], normal[0], normal[1], normal[2], u, 1f - v);
            }
        }
        for (int i = 0; i < columns; i++) {
            for (int j = 0; j < rows; j++) {
                builder.addTriangle(index[i][j], index[i + 1][j], index[i + 1][j + 1]);
                builder.addTriangle(index[i][j], index[i + 1][j + 1], index[i][j + 1]);
            }
        }
    }

    /**
     * A flat rectangle lying on a (possibly slanted) wall: centred at fraction s along the
     * wall and at height centreY, following the wall's lean, and lifted off it by offset.
     */
    private float[][] wallPanel(MeshBuilder builder, House house, HouseWall wall, float s, float centreY,
                           float halfW, float halfH, float offset) {
        float wallHeight = wall.topA[1];
        float[] along = normalise(sub(wall.bottomB, wall.bottomA));
        float[][] corners = new float[4][];
        float[][] local = { { -halfW, -halfH }, { halfW, -halfH }, { halfW, halfH }, { -halfW, halfH } };
        // On a shaped wall, stood out as far as its surface does anywhere behind it, so none of
        // it is buried in a bulge
        float relief = -Float.MAX_VALUE;
        if (wall.relief != ArchitectureStyle.Relief.NONE) {
            for (int a = 0; a <= 4; a++) {
                for (int b = 0; b <= 6; b++) {
                    float sa = s + (a / 4f - 0.5f) * 2f * halfW / Math.max(1e-3f, wall.length);
                    relief = Math.max(relief, reliefAt(wall, Math.max(0f, Math.min(1f, sa)), centreY + (b / 6f - 0.5f) * 2f * halfH));
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            float y = centreY + local[i][1];
            float t = Math.max(0f, Math.min(1f, y / wallHeight));
            float[] bottom = lerp(wall.bottomA, wall.bottomB, s);
            float[] top = lerp(wall.topA, wall.topB, s);
            float[] onWall = lerp(bottom, top, t);
            onWall[1] = y;
            float[] p = add(add(onWall, scale3(along, local[i][0])), scale3(wall.normal, offset));
            if (relief != -Float.MAX_VALUE) p = add(p, scale3(wall.normal, relief));
            corners[i] = new float[] { p[0], house.baseY + p[1], p[2] };
        }
        float[] inside = { (wall.bottomA[0] + wall.bottomB[0]) * 0.5f - wall.normal[0] * 5.0f, house.baseY + centreY,
                (wall.bottomA[2] + wall.bottomB[2]) * 0.5f - wall.normal[2] * 5.0f };
        builder.addConvexFace(inside, corners);
        return corners;
    }

    /**
     * Copies a unit mesh into a builder working in the house's frame: scaled by (sx, sy, sz),
     * shifted by the local offset, and stood on the house's base. With a texture repeat size,
     * texture coordinates are remapped to world units: along the eaves and up the slope
     * on sloping faces, and straight down onto flat ones.
     */
    private void bake(MeshBuilder builder, float[] vertices, int[] indices, House house,
                      float sx, float sy, float sz, float ox, float oy, float oz, float tileSize) {
        int first = -1;
        for (int v = 0; v < vertices.length; v += 8) {
            float lx = vertices[v] * sx + ox;
            float ly = vertices[v + 1] * sy + oy;
            float lz = vertices[v + 2] * sz + oz;
            // Normals take the inverse scale so they stay perpendicular to stretched faces
            float nx = vertices[v + 3] / sx;
            float ny = vertices[v + 4] / sy;
            float nz = vertices[v + 5] / sz;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= length;
            ny /= length;
            nz /= length;
            float u = vertices[v + 6], tv = vertices[v + 7];
            if (tileSize > 0) {
                float rx = vertices[v] * sx, rz = vertices[v + 2] * sz, ry = vertices[v + 1] * sy;
                if (Math.abs(ny) > 0.95f) {
                    u = rx / tileSize;
                    tv = rz / tileSize;
                } else if (Math.abs(nx) > Math.abs(nz)) {
                    u = rz / tileSize;
                    tv = (ry + (sx * 0.5f - Math.abs(rx))) / tileSize;
                } else {
                    u = rx / tileSize;
                    tv = (ry + (sz * 0.5f - Math.abs(rz))) / tileSize;
                }
            }
            int index = builder.addVertex(lx, house.baseY + ly, lz, nx, ny, nz, u, tv);
            if (first < 0) {
                first = index;
            }
        }
        for (int i = 0; i + 2 < indices.length; i += 3) {
            builder.addTriangle(first + indices[i], first + indices[i + 1], first + indices[i + 2]);
        }
    }

        static final float DOOR_OFFSET = 0.5f;
}
