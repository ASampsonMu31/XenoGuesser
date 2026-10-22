import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dresses houses up by their architecture and wealth: pillars, cornices and crests, roof
 * toppers, spikes along the walls, and a tower's fittings (see ArchitectureStyle).
 */
final class HouseDressing extends InfraPart {

    HouseDressing(InfrastructureManager infra) {
        super(infra);
    }

    /**
     * A well-off house's finery, in the nation's dressing stone: a cornice running round
     * under the roof, and a pillar either side of the door holding up a beam over it.
     */
    void dressUp(NationBatch batch, House house, HouseWall[] walls, float[][] door, float scale) {
        MeshBuilder stone = batch.builder("finery");
        float out = 1.8f * scale, tall = 2.6f * scale;
        for (HouseWall wall : walls) {
            float[] n = wall.normal;
            float y = wall.topA[1], yB = wall.topB[1];
            float[][] local = {
                    { wall.topA[0], y - tall, wall.topA[2] }, { wall.topB[0], yB - tall, wall.topB[2] },
                    { wall.topB[0] + n[0] * out, yB - tall, wall.topB[2] + n[2] * out }, { wall.topA[0] + n[0] * out, y - tall, wall.topA[2] + n[2] * out },
                    { wall.topA[0], y, wall.topA[2] }, { wall.topB[0], yB, wall.topB[2] },
                    { wall.topB[0] + n[0] * out, yB, wall.topB[2] + n[2] * out }, { wall.topA[0] + n[0] * out, y, wall.topA[2] + n[2] * out } };
            float[][] w = new float[8][];
            for (int i = 0; i < 8; i++) {
                w[i] = new float[] { local[i][0], house.baseY + local[i][1], local[i][2] };
            }
            solidBox(stone, w);
        }
        float[][] c = door;
        if (c == null) return;
        float ax = c[1][0] - c[0][0], az = c[1][2] - c[0][2], width = (float) Math.hypot(ax, az);
        if (width < 1e-3f) return;
        ax /= width;
        az /= width;
        float[] ahead = { 0f, house.depth * 0.5f + 10f };
        float nx = -az, nz = ax;
        float midX = (c[0][0] + c[1][0]) * 0.5f, midZ = (c[0][2] + c[1][2]) * 0.5f;
        if (nx * (ahead[0] - midX) + nz * (ahead[1] - midZ) < 0f) { nx = -nx; nz = -nz; }
        float r = 1.4f * scale, top = c[3][1] + 3.5f * scale, bottom = house.baseY;
        float[][] posts = { { c[0][0] - ax * (r + 1f), c[0][2] - az * (r + 1f) }, { c[1][0] + ax * (r + 1f), c[1][2] + az * (r + 1f) } };
        for (float[] at : posts) {
            float px = at[0] + nx * (r + 0.6f), pz = at[1] + nz * (r + 0.6f);
            solidBox(stone, boxCorners(px, pz, ax, az, nx, nz, r, r, bottom, top));
        }
        // The beam across the tops
        float bx = midX + nx * (r + 0.6f), bz = midZ + nz * (r + 0.6f);
        solidBox(stone, boxCorners(bx, bz, ax, az, nx, nz, width * 0.5f + 2f * r + 1.5f, r * 1.1f, top, top + 2.2f * scale));
    }

    /**
     * A house dressed in its architecture, as much as it wears (see ArchitectureStyle.dress):
     * the roof's corners swept up, drooping or curled into horns; cresting along its eaves and
     * ridge; something standing on its top; spikes from its walls; neon under its eaves.
     */
    void dressHouse(NationBatch batch, House house, BuildingStyle style, HouseWall[] walls, float[][] footprint,
                            ArchitectureStyle arch, ArchitectureStyle.Dress dress) {
        if (!(dress.horns || dress.crest || dress.topper || dress.spikes || dress.neonEaves)) return;
        RoofOutline roof = HouseBuilder.roofOutline(house, style, footprint);
        String design = "@" + arch.design;
        MeshBuilder ornament = batch.builder("orn" + design), spikes = batch.builder("spike" + design);
        float scale = Math.max(0.7f, house.sizeScale);
        float highest = roof.peak[1];
        float across = 0f;
        for (float[] e : roof.eaves) across = Math.max(across, dist(e, roof.centre));

        if (dress.horns) {
            // Each in three pieces, bending as it goes, thinning to a point
            float length = arch.hornLength * across * 2f, thick = arch.hornThickness * scale;
            float[] bend = switch (arch.horns) {
                case UPTURNED -> new float[] { 12f, 32f, 58f };
                case DROOPING -> new float[] { -8f, -26f, -48f };
                default -> new float[] { 8f, 62f, 125f };
            };
            for (int i = 0; i < roof.eaves.length; i++) {
                if (!roof.horned[i]) continue;
                float[] out = levelDirection(roof.centre, roof.eaves[i]);
                float[] at = framePoint(house, sub(roof.eaves[i], scale3(out, thick)));
                for (int k = 0; k < 3; k++) {
                    double angle = Math.toRadians(bend[k]);
                    float[] dir = { out[0] * (float) Math.cos(angle), (float) Math.sin(angle), out[2] * (float) Math.cos(angle) };
                    float[] next = add(at, scale3(dir, length / 3f + (k == 0 ? thick : 0f)));
                    float r0 = thick * (1f - k / 3f), r1 = thick * (1f - (k + 1) / 3f);
                    addTube(ornament, at, next, r0, k == 2 ? 0f : r1, 6);
                    at = next;
                }
                highest = Math.max(highest, at[1] - house.baseY);
            }
        }

        if (dress.crest) {
            float size = arch.crestSize * scale, gap = size * arch.crestSpacing;
            List<float[][]> runs = new ArrayList<>(roof.edges);
            if (dress.ridgeCrest) runs.addAll(roof.ridges);
            for (float[][] edge : runs) {
                float length = dist(edge[0], edge[1]);
                if (length < 1f) continue;
                float[] along = normalise(sub(edge[1], edge[0]));
                float[] out = normalise(new float[] { along[2], 0f, -along[0] });
                float[] middle = mid(edge[0], edge[1]);
                if (dot3(out, sub(middle, roof.centre)) < 0f) out = scale3(out, -1f);
                int count = Math.max(1, Math.round(length / gap));
                for (int k = 0; k < count; k++) {
                    float[] p = framePoint(house, lerp(edge[0], edge[1], (k + 0.5f) / count));
                    crestItem(arch.crest == ArchitectureStyle.Crest.SPIKES ? spikes : ornament, arch.crest, p, along, out, size);
                }
            }
            highest = Math.max(highest, roof.peak[1] + size * 2f);
        }

        if (dress.topper) {
            float height = arch.topperHeight * scale;
            topper(ornament, spikes, arch.topper, framePoint(house, roof.peak), height, house.seed);
            highest = Math.max(highest, roof.peak[1] + height * 1.15f);
        }

        if (dress.spikes && house.shop == null) wallSpikes(spikes, house, style, walls, arch);

        if (dress.neonEaves) {
            MeshBuilder neon = batch.builder("neon" + design);
            for (HouseWall wall : walls) {
                float y = wall.topA[1] - 1.2f;
                float[] a = add(wallPoint(wall, 0f, y), scale3(wall.cornerA, 0.6f));
                float[] b = add(wallPoint(wall, 1f, y), scale3(wall.cornerB, 0.6f));
                addRod(neon, framePoint(house, a), framePoint(house, b), 0.35f);
            }
        }
        batch.include(0f, house.baseY + highest, 0f, across + 4f);
    }

    /** One piece of cresting standing on p (in the frame), along an edge running along, out facing away from the roof. */
    private void crestItem(MeshBuilder builder, ArchitectureStyle.Crest crest, float[] p, float[] along, float[] out, float size) {
        float[] up = { 0f, 1f, 0f };
        switch (crest) {
            case ORBS -> addOrb(builder, add(p, scale3(up, size * 0.55f)), size * 0.5f);
            case TEETH -> {
                // A pointed plate along the edge
                float[][] outline = { add(p, scale3(along, -size * 0.45f)), add(p, scale3(along, size * 0.45f)), add(p, scale3(up, size * 1.4f)) };
                addSlab(builder, outline, out, Math.max(0.3f, size * 0.18f));
            }
            case BLOCKS -> {
                float[] c = add(p, scale3(up, size * 0.5f));
                float half = size * 0.4f, deep = size * 0.25f;
                float[][] corners = new float[8][];
                float[][] round = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
                for (int i = 0; i < 4; i++) {
                    float[] side = add(scale3(along, half * round[i][0]), scale3(out, deep * round[i][1]));
                    corners[i] = add(add(p, side), scale3(up, -0.2f));
                    corners[i + 4] = add(add(p, side), scale3(up, size));
                }
                solidBox(builder, corners);
            }
            case SPIKES -> addTube(builder, p, add(p, scale3(up, size * 2f)), size * 0.22f, 0f, 5);
            case RINGS -> addLoop(builder, add(p, scale3(up, size * 0.6f)), out, size * 0.5f, Math.max(0.15f, size * 0.1f), 10);
            default -> { }
        }
    }

    /** What stands on a roof's top (p, in the frame): on a thin mast, height tall. */
    private void topper(MeshBuilder ornament, MeshBuilder rod, ArchitectureStyle.Topper kind, float[] p, float height, long seed) {
        float thin = Math.max(0.25f, height * 0.022f);
        float[] up = { 0f, 1f, 0f };
        float[] top = add(p, scale3(up, height));
        Random rand = new Random(seed ^ 0x70FFE5L);
        double heading = rand.nextDouble() * Math.PI * 2;
        float[] facing = { (float) Math.cos(heading), 0f, (float) Math.sin(heading) };
        float[] across = { -facing[2], 0f, facing[0] };
        float r = height * 0.2f;
        // (the mast stops short where something round sits on it)
        float mastTop = kind == ArchitectureStyle.Topper.ORB ? height - r : kind == ArchitectureStyle.Topper.HOOP ? height - 2.2f * r : height;
        addRod(rod, sub(p, scale3(up, 1f)), add(p, scale3(up, mastTop)), thin);
        switch (kind) {
            case MAST -> {
                for (int k = 0; k < 3; k++) {
                    float y = height * (0.88f - 0.2f * k), half = height * (0.1f + 0.07f * k);
                    float[] dir = k % 2 == 0 ? facing : across;
                    float[] c = add(p, scale3(up, y));
                    addRod(rod, add(c, scale3(dir, -half)), add(c, scale3(dir, half)), thin * 0.8f);
                }
                addOrb(ornament, top, thin * 2.2f);
            }
            case ORB -> addOrb(ornament, add(p, scale3(up, height - r * 0.2f)), r);
            case TRIDENT -> {
                float[] bar = add(p, scale3(up, height * 0.7f));
                addRod(ornament, add(bar, scale3(across, -r)), add(bar, scale3(across, r)), thin * 1.2f);
                for (float side : new float[] { -1f, 0f, 1f }) {
                    float[] foot = add(bar, scale3(across, side * r));
                    float tall = height * (side == 0f ? 0.42f : 0.32f);
                    addRod(ornament, foot, add(foot, scale3(up, tall * 0.7f)), thin * 1.1f);
                    addTube(ornament, add(foot, scale3(up, tall * 0.7f)), add(foot, scale3(up, tall)), thin * 2.2f, 0f, 4);
                }
            }
            case VANE -> {
                // An arrow on a pivot, a tall fin at its tail
                float[] pivot = add(p, scale3(up, height * 0.8f));
                float[] head = add(pivot, scale3(facing, r * 1.8f)), tail = add(pivot, scale3(facing, -r * 1.8f));
                addRod(ornament, tail, head, thin);
                addTube(ornament, head, add(head, scale3(facing, r * 0.8f)), thin * 3f, 0f, 4);
                float[][] fin = { tail, add(tail, scale3(facing, r * 1.1f)), add(add(tail, scale3(up, r * 1.2f)), scale3(facing, -r * 0.4f)) };
                addSlab(ornament, fin, across, thin * 0.8f);
            }
            case HOOP -> addLoop(ornament, add(p, scale3(up, height - r * 1.1f)), facing, r * 1.1f, thin * 1.3f, 16);
            case DISH -> {
                // A dish on the mast's top, tipped up towards a heading
                float[] aim = normalise(add(facing, scale3(up, 0.8f)));
                float[] centre = add(p, scale3(up, height * 0.9f));
                addTube(ornament, add(centre, scale3(aim, -r * 0.35f)), centre, r * 0.15f, r * 1.1f, 10);
                addTube(ornament, centre, add(centre, scale3(aim, 0.2f)), r * 1.1f, r * 1.1f, 10);
                addRod(rod, centre, add(centre, scale3(aim, r * 1.1f)), thin * 0.6f);
            }
            case SPIRAL -> {
                float[] last = null;
                for (int k = 0; k <= 36; k++) {
                    float t = k / 36f;
                    double a = t * Math.PI * 5 + heading;
                    float radius = r * (1f - 0.6f * t);
                    float[] q = add(add(p, scale3(up, height * (0.2f + 0.8f * t))),
                            new float[] { (float) Math.cos(a) * radius, 0f, (float) Math.sin(a) * radius });
                    if (last != null) addRod(ornament, last, q, thin * 0.9f);
                    last = q;
                }
            }
            case CRESCENT -> {
                // A crescent moon on the mast, turned to a heading: an arc thickest in its middle
                float[] centre = add(p, scale3(up, height - r));
                float[] last = null;
                for (int k = 0; k <= 12; k++) {
                    double a = Math.toRadians(-140 + 280 * k / 12.0);
                    float[] q = add(centre, add(scale3(up, -(float) Math.cos(a) * r), scale3(facing, (float) Math.sin(a) * r)));
                    if (last != null) addRod(ornament, last, q, thin * (1f + 2.2f * (float) Math.sin(Math.PI * (k - 0.5) / 12.0)));
                    last = q;
                }
            }
            default -> { }
        }
    }

    /** Spikes from a house's walls, kept above head height and clear of its windows. */
    private void wallSpikes(MeshBuilder builder, House house, BuildingStyle style, HouseWall[] walls, ArchitectureStyle arch) {
        float scale = Math.max(0.7f, house.sizeScale);
        float length = arch.spikeLength * scale;
        float lowest = house.doorBase + style.doorHeight * house.sizeScale + 3f;
        Random rand = new Random(house.seed ^ 0x5B1CE5L);
        float floorH = style.wallHeight * house.sizeScale / Math.max(1, style.floors);
        boolean round = style.footprint == BuildingStyle.Footprint.ROUND;
        for (HouseWall wall : walls) {
            float height = wall.topA[1];
            if (height - lowest < 2f) continue;
            switch (arch.wallSpikes) {
                case ROW -> {
                    List<Float> heights = new ArrayList<>();
                    for (int f = 1; f < style.floors; f++) heights.add(house.doorBase + floorH * f);
                    heights.add(Math.max(lowest, height - 3.5f));
                    int count = Math.max(1, Math.round(wall.length / (9f * scale)));
                    for (float y : heights) {
                        if (y < lowest) continue;
                        for (int k = 0; k < count; k++) {
                            float s = (k + 0.5f) / count;
                            if (inOpening(wall, s, y, length * 0.3f)) continue;
                            float[] out = normalise(add(wall.normal, new float[] { 0f, -0.12f, 0f }));
                            wallSpike(builder, house, wall, s, y, out, length);
                        }
                    }
                }
                case CORNERS -> {
                    if (round && wall.index % 5 != 0) continue;
                    for (float t : new float[] { 0.45f, 0.7f, 0.95f }) {
                        float y = Math.max(lowest, height * t);
                        float[] out = normalise(add(wall.cornerA, new float[] { 0f, 0.5f, 0f }));
                        wallSpike(builder, house, wall, 0f, y, out, length * 1.3f);
                    }
                }
                default -> {
                    // Thorns: short spikes scattered over the walls
                    int count = 3 + Math.round(wall.length * (height - lowest) / (120f * scale * scale));
                    for (int k = 0; k < Math.min(count, 14); k++) {
                        float s = 0.05f + 0.9f * rand.nextFloat(), y = lowest + (height - lowest - 1f) * rand.nextFloat();
                        if (inOpening(wall, s, y, length * 0.4f)) continue;
                        float[] out = normalise(add(wall.normal, new float[] { (rand.nextFloat() - 0.5f) * 0.5f, (rand.nextFloat() - 0.6f) * 0.6f,
                                (rand.nextFloat() - 0.5f) * 0.5f }));
                        wallSpike(builder, house, wall, s, y, out, length * (0.45f + 0.3f * rand.nextFloat()));
                    }
                }
            }
        }
    }

    /** Whether a point on a wall (s along it, height y) is in or within margin of one of its windows. */
    private static boolean inOpening(HouseWall wall, float s, float y, float margin) {
        float ms = margin / Math.max(1f, wall.length);
        for (float[] o : wall.openings) {
            if (s > o[0] - ms && s < o[1] + ms && y > o[2] - margin && y < o[3] + margin) return true;
        }
        return false;
    }

    private void wallSpike(MeshBuilder builder, House house, HouseWall wall, float s, float y, float[] out, float length) {
        float[] base = wallPoint(wall, s, y);
        float[] from = framePoint(house, sub(base, scale3(out, 0.4f)));
        float[] to = framePoint(house, add(base, scale3(out, length)));
        addTube(builder, from, to, Math.max(0.3f, length * 0.16f), 0f, 5);
    }

    /**
     * A tower in a big city dressed in its architecture's high tech, as far as the area is well
     * off (see ArchitectureStyle.dress): neon up its corners and round it every few floors, blades
     * down its corners, rings round its middle and a crown on its roof.
     */
    void dressTower(NationBatch batch, House house, BuildingStyle style, HouseWall[] walls,
                            ArchitectureStyle arch, ArchitectureStyle.Dress dress) {
        if (!(dress.neonCorners || dress.neonBands || dress.blades || dress.rings || dress.crown)) return;
        String design = "@" + arch.design;
        MeshBuilder metal = batch.builder("techmetal"), neonA = batch.builder("neon" + design), neonB = batch.builder("neonb" + design);
        float height = walls[0].topA[1];
        float floorH = style.wallHeight * house.sizeScale / Math.max(1, style.floors);
        boolean round = style.footprint == BuildingStyle.Footprint.ROUND;
        float radius = 0f;
        for (HouseWall wall : walls) {
            radius = Math.max(radius, (float) Math.hypot(wall.bottomA[0], wall.bottomA[2]));
        }
        float topY = height + style.roofHeight * house.sizeScale;
        float highest = topY;

        for (HouseWall wall : walls) {
            boolean corner = !round || wall.index % 5 == 0;
            if (dress.neonCorners && corner) {
                float[] a = add(wallPoint(wall, 0f, house.doorBase + floorH), scale3(wall.cornerA, 0.6f));
                float[] b = add(wallPoint(wall, 0f, height - 0.5f), scale3(wall.cornerA, 0.6f));
                addRod(neonA, framePoint(house, a), framePoint(house, b), 0.45f);
            }
            if (dress.blades && corner && !round) {
                // A thin blade standing out from the corner, from the second floor to above the roof
                float y0 = house.doorBase + floorH * 2f, y1 = height + 6f;
                float depth = Math.min(12f, radius * 0.12f + 3f), half = 0.45f;
                float[] out = wall.cornerA, flat = { -out[2], 0f, out[0] };
                float[] bottom = wallPoint(wall, 0f, y0), lean = sub(wallPoint(wall, 0f, height), bottom);
                lean = scale3(lean, (y1 - y0) / Math.max(1f, height - y0));
                float[][] corners = new float[8][];
                float[][] base = { add(bottom, scale3(flat, -half)), add(add(bottom, scale3(out, depth)), scale3(flat, -half)),
                        add(add(bottom, scale3(out, depth)), scale3(flat, half)), add(bottom, scale3(flat, half)) };
                for (int i = 0; i < 4; i++) {
                    corners[i] = framePoint(house, base[i]);
                    corners[i + 4] = framePoint(house, add(base[i], lean));
                }
                solidBox(metal, corners);
            }
            if (dress.neonBands) {
                for (int f = arch.bandEvery; f < style.floors; f += arch.bandEvery) {
                    float y = house.doorBase + floorH * f;
                    float[] a = add(wallPoint(wall, 0f, y), scale3(wall.cornerA, 0.5f));
                    float[] b = add(wallPoint(wall, 1f, y), scale3(wall.cornerB, 0.5f));
                    addRod(neonB, framePoint(house, a), framePoint(house, b), 0.35f);
                }
            }
        }

        float[] centre = { 0f, 0f, 0f }, up = { 0f, 1f, 0f };
        if (dress.rings) {
            // Rings round the tower's middle, held out on struts, lit underneath
            float[] heights = height > 260f ? new float[] { 0.42f, 0.7f } : new float[] { 0.6f };
            for (float t : heights) {
                float y = house.doorBase + (height - house.doorBase) * t;
                float ringR = radius * 1.25f + 8f;
                float[] c = framePoint(house, new float[] { 0f, y, 0f });
                addLoop(metal, c, up, ringR, 1.1f, 28);
                addLoop(neonA, add(c, scale3(up, -1.6f)), up, ringR - 0.4f, 0.4f, 28);
                for (HouseWall wall : walls) {
                    if (round && wall.index % 5 != 0) continue;
                    float[] from = wallPoint(wall, 0.5f, y);
                    float[] out = levelDirection(centre, from);
                    addRod(metal, framePoint(house, from), framePoint(house, new float[] { out[0] * ringR, y, out[2] * ringR }), 0.6f);
                }
            }
        }

        if (dress.crown) {
            float[] roofTop = framePoint(house, new float[] { 0f, topY, 0f });
            switch (arch.crown) {
                case SPIRE -> {
                    float tall = Math.min(180f, height * 0.35f);
                    addTube(metal, roofTop, add(roofTop, scale3(up, tall)), radius * 0.16f, 0f, 8);
                    addOrb(neonA, add(roofTop, scale3(up, tall * 0.55f)), radius * 0.09f + 0.8f);
                    highest = topY + tall;
                }
                case HALO -> {
                    float lift = radius * 0.6f + 6f, ringR = radius * 0.75f;
                    for (int k = 0; k < 3; k++) {
                        double a = k * Math.PI * 2 / 3;
                        float[] foot = add(roofTop, new float[] { (float) Math.cos(a) * ringR * 0.5f, 0f, (float) Math.sin(a) * ringR * 0.5f });
                        float[] head = add(roofTop, new float[] { (float) Math.cos(a) * ringR, lift, (float) Math.sin(a) * ringR });
                        addRod(metal, foot, head, 0.8f);
                    }
                    addLoop(neonA, add(roofTop, scale3(up, lift)), up, ringR, 0.9f, 32);
                    addLoop(metal, add(roofTop, scale3(up, lift - 1.6f)), up, ringR, 0.7f, 32);
                    highest = topY + lift + 2f;
                }
                case FINS -> {
                    int fins = round ? 6 : Math.min(8, walls.length);
                    float tall = radius * 0.9f + 8f;
                    for (int k = 0; k < fins; k++) {
                        float[] dir;
                        if (round) {
                            double a = k * Math.PI * 2 / fins;
                            dir = new float[] { (float) Math.cos(a), 0f, (float) Math.sin(a) };
                        } else {
                            dir = walls[k % walls.length].cornerA;
                        }
                        float[] inner = add(roofTop, scale3(dir, radius * 0.55f)), outer = add(roofTop, scale3(dir, radius * 0.95f));
                        float[] tip = add(add(roofTop, scale3(dir, radius * 1.05f)), scale3(up, tall));
                        addSlab(metal, new float[][] { inner, outer, tip }, new float[] { -dir[2], 0f, dir[0] }, 1.2f);
                    }
                    highest = topY + tall;
                }
                case DISHES -> {
                    float mast = radius * 0.8f + 10f;
                    addRod(metal, roofTop, add(roofTop, scale3(up, mast)), 0.7f);
                    addOrb(neonA, add(roofTop, scale3(up, mast)), 1.2f);
                    for (int k = 0; k < 3; k++) {
                        double a = k * Math.PI * 2 / 3 + 0.4;
                        float[] dir = { (float) Math.cos(a), 0f, (float) Math.sin(a) };
                        float[] foot = add(roofTop, scale3(dir, radius * 0.45f)), head = add(foot, scale3(up, 5f));
                        addRod(metal, foot, head, 0.5f);
                        float[] aim = normalise(add(dir, scale3(up, 0.7f)));
                        float dish = radius * 0.16f + 2f;
                        addTube(metal, add(head, scale3(aim, -dish * 0.3f)), head, dish * 0.15f, dish, 10);
                        addTube(metal, head, add(head, scale3(aim, 0.2f)), dish, dish, 10);
                    }
                    highest = topY + mast + 2f;
                }
                case ORB -> {
                    float column = radius * 0.3f + 4f, orb = radius * 0.35f + 2f;
                    addTube(metal, roofTop, add(roofTop, scale3(up, column)), radius * 0.12f + 1f, radius * 0.08f + 0.6f, 8);
                    addOrb(neonB, add(roofTop, scale3(up, column + orb * 0.9f)), orb);
                    highest = topY + column + orb * 2f;
                }
                default -> { }
            }
        }
        batch.include(0f, house.baseY + highest, 0f, radius * 1.5f + 10f);
    }

    /**
     * A closed tube from p0 to p1 (in the frame), radius r0 at one end and r1 at the other, sides
     * round; a cone, ending in a point, when r1 is 0.
     */
    private static void addTube(MeshBuilder builder, float[] p0, float[] p1, float r0, float r1, int sides) {
        float[] axis = sub(p1, p0);
        float length = (float) Math.sqrt(dot3(axis, axis));
        if (length < 1e-4f || r0 < 1e-4f) return;
        axis = scale3(axis, 1f / length);
        float[] side = normalise(cross(axis, Math.abs(axis[1]) > 0.9f ? new float[] { 1f, 0f, 0f } : new float[] { 0f, 1f, 0f }));
        float[] other = normalise(cross(side, axis));
        float[][] ring0 = new float[sides][], ring1 = new float[sides][];
        for (int k = 0; k < sides; k++) {
            double a = k * Math.PI * 2 / sides;
            float[] round = add(scale3(side, (float) Math.cos(a)), scale3(other, (float) Math.sin(a)));
            ring0[k] = add(p0, scale3(round, r0));
            ring1[k] = add(p1, scale3(round, r1));
        }
        float[] inside = add(p0, scale3(axis, length * (r1 < 1e-4f ? 0.25f : 0.5f)));
        for (int k = 0; k < sides; k++) {
            int j = (k + 1) % sides;
            if (r1 < 1e-4f) builder.addConvexFace(inside, ring0[k], ring0[j], p1);
            else builder.addConvexFace(inside, ring0[k], ring0[j], ring1[j], ring1[k]);
        }
        builder.addConvexFace(inside, ring0);
        if (r1 >= 1e-4f) builder.addConvexFace(inside, ring1);
    }

    /** A ball (in the frame), in bands of facets. */
    private static void addOrb(MeshBuilder builder, float[] centre, float radius) {
        int bands = 5, around = 10;
        float[][][] rings = new float[bands + 1][][];
        for (int b = 0; b <= bands; b++) {
            double phi = Math.PI * b / bands - Math.PI / 2;
            rings[b] = new float[around][];
            for (int k = 0; k < around; k++) {
                double a = k * Math.PI * 2 / around;
                rings[b][k] = new float[] { centre[0] + radius * (float) (Math.cos(phi) * Math.cos(a)), centre[1] + radius * (float) Math.sin(phi),
                        centre[2] + radius * (float) (Math.cos(phi) * Math.sin(a)) };
            }
        }
        for (int b = 0; b < bands; b++) {
            for (int k = 0; k < around; k++) {
                int j = (k + 1) % around;
                if (b == 0) builder.addConvexFace(centre, rings[0][0], rings[1][j], rings[1][k]);
                else if (b == bands - 1) builder.addConvexFace(centre, rings[b][k], rings[b][j], rings[bands][0]);
                else builder.addConvexFace(centre, rings[b][k], rings[b][j], rings[b + 1][j], rings[b + 1][k]);
            }
        }
    }

    /** A hoop (in the frame) round centre, square in section, its face square to axis. */
    private void addLoop(MeshBuilder builder, float[] centre, float[] axis, float radius, float half, int segments) {
        float[] n = normalise(axis);
        float[] side = normalise(cross(n, Math.abs(n[1]) > 0.9f ? new float[] { 1f, 0f, 0f } : new float[] { 0f, 1f, 0f }));
        float[] other = normalise(cross(side, n));
        float[] last = null;
        for (int k = 0; k <= segments; k++) {
            double a = k * Math.PI * 2 / segments;
            float[] q = add(centre, add(scale3(side, (float) Math.cos(a) * radius), scale3(other, (float) Math.sin(a) * radius)));
            if (last != null) addRod(builder, last, q, half);
            last = q;
        }
    }

    /** A flat plate (in the frame): a convex outline given thickness across it, along across. */
    private static void addSlab(MeshBuilder builder, float[][] outline, float[] across, float thickness) {
        int n = outline.length;
        float[] off = scale3(normalise(across), thickness * 0.5f);
        float[][] front = new float[n][], back = new float[n][];
        float[] inside = new float[3];
        for (int i = 0; i < n; i++) {
            front[i] = add(outline[i], off);
            back[i] = sub(outline[i], off);
            for (int k = 0; k < 3; k++) inside[k] += outline[i][k] / n;
        }
        builder.addConvexFace(inside, front);
        builder.addConvexFace(inside, back);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            builder.addConvexFace(inside, front[i], front[j], back[j], back[i]);
        }
    }

    /** The eight corners of an upright box: bottom four then top four, in order round. */
    private static float[][] boxCorners(float cx, float cz, float ax, float az, float nx, float nz, float halfAlong, float halfOut, float y0, float y1) {
        float[][] corners = new float[8][];
        float[][] round = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (int i = 0; i < 4; i++) {
            float x = cx + ax * halfAlong * round[i][0] + nx * halfOut * round[i][1];
            float z = cz + az * halfAlong * round[i][0] + nz * halfOut * round[i][1];
            corners[i] = new float[] { x, y0, z };
            corners[i + 4] = new float[] { x, y1, z };
        }
        return corners;
    }

    /** A closed box from eight corners (bottom four then top four, in order round), faces lit outward. */
    private static void solidBox(MeshBuilder builder, float[][] w) {
        float[] centre = new float[3];
        for (float[] p : w) for (int k = 0; k < 3; k++) centre[k] += p[k] / 8f;
        builder.addConvexFace(centre, w[0], w[1], w[2], w[3]);
        builder.addConvexFace(centre, w[4], w[5], w[6], w[7]);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            builder.addConvexFace(centre, w[i], w[j], w[j + 4], w[i + 4]);
        }
    }
}
