import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The shops in the middle of towns: which buildings become shops (and grow along the street),
 * their names, and their fronts: the painted shop front, display windows and the goods
 * stocked on their shelves.
 */
final class ShopBuilder extends InfraPart {

    ShopBuilder(InfrastructureManager infra) {
        super(infra);
    }

    private final Map<Long, MeshData> goodsGeometry = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Shops gather into commercial districts: each town has a shopping centre round its
     * middle, bigger and busier the bigger the town, where most buildings are shops; from
     * there shopping streets run on along the roads, a shop's neighbours on the same road
     * likely shops too; and here and there, a lone corner shop in the suburbs.
     */
    void chooseShops() {
        Map<House, Random> rolls = new HashMap<>();
        for (House house : infra.houses) rolls.put(house, new Random(house.seed ^ 0x5409L));
        // The town centre
        for (House house : infra.houses) {
            if (!canBeShop(house)) continue;
            SettlementManager.Settlement town = infra.settlementManager.dominantSettlementAt(house.frame.origin());
            if (town == null) continue;
            // (along the surface, as the town's radius is)
            float distance = (float) SphereMath.distance(house.frame.origin(), town.centre);
            // Villages have a shop or two at their heart; cities a district a few streets across
            float size = Math.min(1f, town.radius / 1600f);
            float core = town.radius * (0.55f + 0.45f * size);
            float chance = (0.45f + 0.5f * size) * (float) Math.exp(-(distance / core) * (distance / core));
            if (rolls.get(house).nextFloat() < chance) makeShop(house, rolls.get(house));
        }
        // Shopping streets: next door to a shop, on the same road, is likely another
        Map<Integer, List<House>> byRoad = new HashMap<>();
        for (House house : infra.houses) byRoad.computeIfAbsent(house.frontagePath, k -> new ArrayList<>()).add(house);
        for (int pass = 0; pass < 4; pass++) {
            List<House> joining = new ArrayList<>();
            for (List<House> road : byRoad.values()) {
                for (House house : road) {
                    if (house.shop != null || !canBeShop(house)) continue;
                    for (House other : road) {
                        if (other.shop == null) continue;
                        if (nextDoor(house, other)) {
                            if (rolls.get(house).nextFloat() < 0.6f * infra.settlementManager.getUrbanness(house.frame.origin()) + 0.25f) joining.add(house);
                            break;
                        }
                    }
                }
            }
            for (House house : joining) makeShop(house, rolls.get(house));
        }
        // The odd corner shop out in the suburbs
        for (House house : infra.houses) {
            if (house.shop == null && canBeShop(house) && infra.settlementManager.getUrbanness(house.frame.origin()) > 0.25f
                    && rolls.get(house).nextFloat() < 0.012f) {
                makeShop(house, rolls.get(house));
            }
        }
        int lone = 0;
        for (InfrastructureManager.Shop shop : infra.shops) {
            boolean neighbour = false;
            for (House other : byRoad.get(shop.house.frontagePath)) {
                if (other == shop.house || other.shop == null) continue;
                if (nextDoor(shop.house, other)) { neighbour = true; break; }
            }
            if (!neighbour) lone++;
        }
        System.out.printf("[SHOPS] %d shops of %d buildings (%.1f%%), %d on their own%n", infra.shops.size(), infra.houses.size(),
                100f * infra.shops.size() / Math.max(1, infra.houses.size()), lone);
    }

    /** Whether two buildings on the same road are next door to (or straight across from) each other. */
    private static boolean nextDoor(House a, House b) {
        SpherePosition pa = a.frame.origin(), pb = b.frame.origin();
        double dx = pa.x() - pb.x(), dy = pa.y() - pb.y(), dz = pa.z() - pb.z();
        float reach = ((a.plotMaxX - a.plotMinX) + (b.plotMaxX - b.plotMinX)) * 0.5f + 60f;
        return dx * dx + dy * dy + dz * dz < (double) reach * reach;
    }

    /** A building with a front wide enough for a shop window either side of the door. */
    private boolean canBeShop(House house) {
        BuildingStyle style = infra.styleOf(house);
        // (towers are offices and flats, not shops)
        if (style.tower) return false;
        return frontLength(style, house) >= style.doorWidth * house.sizeScale * 2.2f + 10f;
    }

    private void makeShop(House house, Random rand) {
        if (house.shop != null) return;
        growShop(house);
        house.shop = new InfrastructureManager.Shop(house, rand);
        infra.shops.add(house.shop);
        // Open to the street: no fence round a shop, and customers park in the road
        house.fenced = false;
        house.secure = false;
        house.drivewaySide = 0;
    }

    /**
     * A shop where people are well off is bigger: grown (taller too) as far as its plot allows,
     * keeping some front garden and side and back yards.
     */
    private void growShop(House house) {
        if (house.richness < 0.3f) return;
        BuildingStyle style = infra.styleOf(house);
        float overhang = style.roofOverhang * house.sizeScale;
        float span = house.houseMaxX() - house.houseMinX();
        float side = Math.min(house.houseMinX() - house.plotMinX, house.plotMaxX - house.houseMaxX()) - overhang - 3f;
        float front = house.setback - 8f;
        float back = (-house.depth * 0.5f - overhang) - house.plotMinZ - 3f;
        float grow = 1f + 0.6f * (house.richness - 0.3f);
        grow = Math.min(grow, 1f + 2f * Math.max(0f, side) / span);
        grow = Math.min(grow, 1f + 2f * Math.max(0f, Math.min(front, back)) / house.depth);
        if (grow < 1.05f) return;
        float[] before = { house.sizeScale, house.width, house.depth, house.extensionWidth, house.extensionDepth, house.setback };
        house.sizeScale *= grow;
        house.width *= grow;
        house.depth *= grow;
        house.extensionWidth *= grow;
        house.extensionDepth *= grow;
        house.setback -= (grow - 1f) * before[2] * 0.5f;
        if (!infra.housePlacement.settleOnGround(house, style)) {
            house.sizeScale = before[0];
            house.width = before[1];
            house.depth = before[2];
            house.extensionWidth = before[3];
            house.extensionDepth = before[4];
            house.setback = before[5];
            infra.housePlacement.settleOnGround(house, style);
        }
    }

    /** How long the wall facing the road is. */
    private static float frontLength(BuildingStyle style, House house) {
        float[][] outline = footprint(style, house.width, house.depth, 0f, 0f);
        float best = -Float.MAX_VALUE, length = 0f;
        for (int i = 0; i < outline.length; i++) {
            float[] a = outline[i], b = outline[(i + 1) % outline.length];
            float midZ = (a[1] + b[1]) * 0.5f;
            if (midZ > best) {
                best = midZ;
                length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
        }
        return length;
    }

    /** The shop nearest a place within reach, preferring one in the same nation; null if none. */
    public InfrastructureManager.Shop nearestShop(SpherePosition place, float reach, int nationId) {
        InfrastructureManager.Shop best = null;
        double bestScore = (double) reach * reach;
        SpherePosition at = SphereMath.atAltitude(place, 0.0);
        for (InfrastructureManager.Shop shop : infra.shopIndex.near(at, reach)) {
            double d = SphereMath.distance(shop.house.frame.origin(), at);
            double score = d * d * (shop.nationId == nationId ? 1.0 : 4.0);
            if (score < bestScore) { bestScore = score; best = shop; }
        }
        return best;
    }

    /** A shop's name, in its nation's letters: a word or two, short enough for any shop front. */
    public int[] shopName(InfrastructureManager.Shop shop) {
        if (shop.name != null) return shop.name;
        int maxGlyphs = Math.max(2, infra.mainListener != null ? infra.mainListener.getNationAtlasSize(shop.nationId) : 10);
        Random rand = new Random(shop.house.seed ^ 0x4A3EL);
        boolean vertical = infra.scripts.direction(shop.nationId) >= 2;
        List<Integer> letters = new ArrayList<>();
        int words = vertical ? 1 : (rand.nextFloat() < 0.4f ? 2 : 1);
        for (int w = 0; w < words; w++) {
            if (w > 0) letters.add(0);
            int count = vertical ? 2 + rand.nextInt(4) : 3 + rand.nextInt(4);
            for (int i = 0; i < count; i++) letters.add(1 + rand.nextInt(maxGlyphs - 1));
        }
        int[] name = new int[Math.min(InfrastructureObject.TITLE_CAPACITY, letters.size())];
        for (int i = 0; i < name.length; i++) name[i] = letters.get(i);
        shop.name = name;
        return name;
    }

    private static final float DISPLAY_DEPTH = 7f;

    /** The ground floor of a shop's front: two big display windows either side of the door, and its name above. */
    void shopFront(NationBatch batch, House house, BuildingStyle style, HouseWall front, List<InfrastructureObject> objects) {
        float scale = house.sizeScale;
        float floorHeight = style.wallHeight * scale / style.floors;
        float wallTop = front.topA[1];
        float doorHalfW = Math.min(style.doorWidth * scale, front.length * 0.7f) * 0.5f;
        float doorH = style.doorHeight * scale;
        float fasciaH = 4.2f;
        float upperBottom = style.floors > 1 ? house.doorBase + floorHeight * 1.55f - style.windowHeight * scale * 0.5f : wallTop;
        float y0 = house.doorBase + 1.3f;
        float y1 = house.doorBase + Math.max(doorH + 1f, floorHeight * 0.8f);
        y1 = Math.min(y1, Math.min(upperBottom, wallTop) - fasciaH - 1.0f);
        if (y1 - y0 < 4f) y1 = y0 + 4f;
        float gap = (doorHalfW + 1.4f) / front.length, margin = 1.6f / front.length;
        Random rand = new Random(house.seed ^ 0x5D0FL);
        displayWindow(batch, house, style, front, margin, 0.5f - gap, y0, y1, rand);
        displayWindow(batch, house, style, front, 0.5f + gap, 1f - margin, y0, y1, rand);

        // The name: across a board over the windows, or for a script that runs down the page,
        // on a tall board sticking out from the corner into the street
        int[] name = shopName(house.shop);
        boolean vertical = infra.scripts.direction(house.nationId) >= 2;
        float[] n = horizontal(front.normal);
        InfrastructureObject sign;
        if (!vertical) {
            float yc = y1 + 0.5f + fasciaH * 0.5f;
            float[] at = add(wallPoint(front, 0.5f, yc), scale3(n, 0.45f + standOff(front, 0.5f, yc, fasciaH)));
            float width = Math.min(70f, front.length * (1f - 2f * margin) * 0.95f);
            sign = InfrastructureObject.createWallSign(fixedTo(house, at, n), house.nationId,
                    width, fasciaH, true, false, null, 0f, 0f);
        } else {
            float side = rand.nextBoolean() ? 0.06f : 0.94f;
            float bladeW = 5f;
            float bottom = y1 + 0.5f;
            float height = Math.max(8f, Math.min(18f, wallTop - bottom - 1f));
            float[] at = add(wallPoint(front, side, bottom + height * 0.5f), scale3(n, bladeW * 0.5f + 0.6f));
            float[] along = normalise(sub(front.bottomB, front.bottomA));
            sign = InfrastructureObject.createWallSign(fixedTo(house, at, along), house.nationId,
                    bladeW, height, true, true, null, 0f, 0f);
        }
        System.arraycopy(name, 0, sign.titleString, 0, name.length);
        sign.titleLength = name.length;
        infra.roadsideSigns.letter(sign, rand);
        float[] paint = new float[] { infra.shopColours.get(house.nationId)[house.shop.colour].x, infra.shopColours.get(house.nationId)[house.shop.colour].y,
                infra.shopColours.get(house.nationId)[house.shop.colour].z };
        // The board contrasts with the paintwork, the letters with the board
        boolean darkBoard = rand.nextFloat() < 0.45f;
        float[] hsv = WorldPalette.toHsv(paint);
        sign.boardColour = darkBoard ? WorldPalette.hsv(hsv[0] + 0.5f, 0.5f, 0.18f + rand.nextFloat() * 0.12f)
                : WorldPalette.hsv(hsv[0] + 0.1f, 0.08f + rand.nextFloat() * 0.15f, 0.9f + rand.nextFloat() * 0.08f);
        sign.titleColour = darkBoard ? WorldPalette.hsv(rand.nextFloat(), 0.3f + rand.nextFloat() * 0.5f, 0.92f)
                : WorldPalette.hsv(hsv[0] + rand.nextFloat() * 0.3f, 0.7f + rand.nextFloat() * 0.3f, 0.25f + rand.nextFloat() * 0.3f);
        objects.add(sign);
    }

    /** A wide, deep window full of goods on shelves, brightly lit inside, with no curtains. */
    private void displayWindow(NationBatch batch, House house, BuildingStyle style, HouseWall wall, float s0, float s1,
                               float y0, float y1, Random rand) {
        if (s1 - s0 < 0.04f || (s1 - s0) * wall.length < 4f) return;
        wall.openings.add(new float[] { s0, s1, y0, y1 });
        float[] n = wall.normal;
        float[] along = normalise(sub(wallPoint(wall, s1, y0), wallPoint(wall, s0, y0)));
        float[] up = { 0f, 1f, 0f };
        float d = DISPLAY_DEPTH;

        MeshBuilder reveal = batch.builder("frame");
        localQuad(reveal, house, inset(wall, s0, y0, 0), inset(wall, s0, y1, 0), inset(wall, s0, y1, d), inset(wall, s0, y0, d), along);
        localQuad(reveal, house, inset(wall, s1, y0, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, d), inset(wall, s1, y0, d), scale3(along, -1));
        localQuad(reveal, house, inset(wall, s0, y1, 0), inset(wall, s1, y1, 0), inset(wall, s1, y1, d), inset(wall, s0, y1, d), scale3(up, -1));
        localQuad(batch.builder("shelf"), house, inset(wall, s0, y0, 0), inset(wall, s1, y0, 0), inset(wall, s1, y0, d), inset(wall, s0, y0, d), up);
        localQuad(batch.builder("shopinterior"), house, inset(wall, s0, y0, d), inset(wall, s1, y0, d),
                inset(wall, s1, y1, d), inset(wall, s0, y1, d), n);

        // Shelves: one or two, stepping up towards the back
        float height = y1 - y0;
        int shelves = height > 7.5f ? 2 : 1;
        float[] levels = new float[shelves + 1];
        float[] tops = new float[shelves + 1];
        levels[0] = y0;
        for (int i = 1; i <= shelves; i++) levels[i] = y0 + height * i / (shelves + 1f);
        for (int i = 0; i <= shelves; i++) tops[i] = i < shelves ? levels[i + 1] : y1;
        MeshBuilder shelf = batch.builder("shelf");
        for (int i = 1; i <= shelves; i++) {
            float y = levels[i], front = 2.2f;
            localQuad(shelf, house, inset(wall, s0, y, front), inset(wall, s1, y, front), inset(wall, s1, y, d), inset(wall, s0, y, d), up);
            localQuad(shelf, house, inset(wall, s0, y - 0.3f, front), inset(wall, s1, y - 0.3f, front), inset(wall, s1, y, front),
                    inset(wall, s0, y, front), n);
        }
        stockWindow(batch.builder("goods"), house, wall, s0, s1, levels, tops, rand);

        // The glass, and a frame like the house's other windows
        localQuad(batch.builder("glass"), house, inset(wall, s0, y0, HouseBuilder.GLASS_SETBACK), inset(wall, s1, y0, HouseBuilder.GLASS_SETBACK),
                inset(wall, s1, y1, HouseBuilder.GLASS_SETBACK), inset(wall, s0, y1, HouseBuilder.GLASS_SETBACK), n);
        float f = Math.max(0.5f, style.frameSize);
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

    /**
     * Fills each level of a display window with goods: heaps of produce and rows of
     * packets, a few of each kind together, sized to the shelf they stand on.
     */
    // The most goods set out along one shelf of a shop window
    private static final int MOST_GOODS_PER_SHELF = 18;

    private void stockWindow(MeshBuilder goods, House house, HouseWall wall, float s0, float s1, float[] levels, float[] tops, Random rand) {
        int nation = house.nationId;
        boolean grocer = house.shop.grocer;
        List<Products.Produce> crops = infra.products.produce(nation);
        List<Products.Packet> packets = infra.products.packets(nation);
        float width = (s1 - s0) * wall.length;
        float[] outward = horizontal(wall.normal);
        float[] up = { 0f, 1f, 0f };
        float[] across = cross(up, outward);
        for (int level = 0; level < levels.length; level++) {
            float room = tops[level] - levels[level] - 0.4f;
            if (room < 1f) continue;
            float frontDepth = level == 0 ? 1.4f : 2.6f, backDepth = DISPLAY_DEPTH - 0.9f;
            float t = 0.5f;
            int remaining = 0;
            boolean packet = false;
            int index = 0;
            // (a very wide window, as in a big shop, has its goods only so far along each shelf)
            int placed = 0;
            while (t < width - 0.5f && placed++ < MOST_GOODS_PER_SHELF) {
                if (remaining <= 0) {
                    packet = grocer ? rand.nextFloat() < 0.2f : rand.nextFloat() < 0.75f;
                    index = rand.nextInt(packet ? packets.size() : crops.size());
                    remaining = 2 + rand.nextInt(3);
                }
                remaining--;
                float footprint;
                if (packet) {
                    Products.Packet k = packets.get(index);
                    float scale = Math.min(1f, room * 0.92f / (k.height + 0.2f));
                    footprint = k.width * scale + 0.25f;
                    if (t + footprint > width - 0.3f) break;
                    float s = s0 + (t + footprint * 0.5f) / wall.length;
                    for (float depth : new float[] { frontDepth + k.depth * scale * 0.5f, backDepth - k.depth * scale * 0.5f }) {
                        float[] origin = inset(wall, s, levels[level], depth);
                        appendGoods(goods, house, origin, across, up, outward, scale, (rand.nextFloat() - 0.5f) * 0.25f,
                                goodsGeometry(nation, true, index));
                    }
                } else {
                    Products.Produce p = crops.get(index);
                    float tall = p.shape == Products.Shape.DISC ? p.size * 0.45f : p.size;
                    float scale = Math.min(1f, room * 0.85f / tall);
                    float wide = Math.max(p.size * p.width, p.shape == Products.Shape.POD ? p.size * 0.6f : 0f) * scale;
                    footprint = wide + 0.2f;
                    if (t + footprint > width - 0.3f) break;
                    float s = s0 + (t + footprint * 0.5f) / wall.length;
                    // A little heap: a few pieces one behind the other, turned this way and that
                    int pieces = 2 + rand.nextInt(3);
                    for (int i = 0; i < pieces; i++) {
                        float depth = frontDepth + wide * 0.5f + (backDepth - frontDepth - wide) * i / Math.max(1f, pieces - 1f);
                        float[] origin = inset(wall, s + (rand.nextFloat() - 0.5f) * 0.3f / wall.length, levels[level], depth);
                        appendGoods(goods, house, origin, across, up, outward, scale * (0.85f + rand.nextFloat() * 0.25f),
                                rand.nextFloat() * 6.28f, goodsGeometry(nation, false, index));
                    }
                }
                t += footprint;
            }
        }
    }

    /** A product's geometry at unit scale (its vertices in OrganismMesh's layout), built once. */
    private MeshData goodsGeometry(int nationId, boolean packet, int index) {
        long key = nationId * 64L + (packet ? 32 : 0) + index;
        return goodsGeometry.computeIfAbsent(key, k -> {
            OrganismMesh.Builder b = new OrganismMesh.Builder();
            if (packet) Products.buildPacket(b, infra.products.packets(nationId).get(index), 1f, Affine.identity());
            else Products.buildProduce(b, infra.products.produce(nationId).get(index), 1f, Affine.identity());
            return new MeshData(b.vertices(), b.indices());
        });
    }

    /** Copies a product into a builder, standing at a house-local point, its front facing front, turned by yaw. */
    private void appendGoods(MeshBuilder builder, House house, float[] origin, float[] across, float[] up, float[] front,
                             float scale, float yaw, MeshData geometry) {
        float[] v = geometry.vertices();
        int[] idx = geometry.indices();
        float cos = (float) Math.cos(yaw), sin = (float) Math.sin(yaw);
        int stride = OrganismMesh.STRIDE;
        int first = -1;
        for (int i = 0; i + stride <= v.length; i += stride) {
            float x = v[i] * cos + v[i + 2] * sin, y = v[i + 1], z = -v[i] * sin + v[i + 2] * cos;
            float nx = v[i + 3] * cos + v[i + 5] * sin, ny = v[i + 4], nz = -v[i + 3] * sin + v[i + 5] * cos;
            float[] p = add(origin, add(add(scale3(across, x * scale), scale3(up, y * scale)), scale3(front, z * scale)));
            float[] normal = add(add(scale3(across, nx), scale3(up, ny)), scale3(front, nz));
            int index = houseVertex(builder, house, p, normal, v[i + 6], v[i + 7]);
            if (first < 0) first = index;
        }
        if (first < 0) return;
        for (int i = 0; i + 2 < idx.length; i += 3) builder.addTriangle(first + idx[i], first + idx[i + 1], first + idx[i + 2]);
    }

    /** How far a leaning wall's face comes out past where it is at height y, over a board height tall centred there. */
    private static float standOff(HouseWall wall, float s, float y, float height) {
        float[] n = horizontal(wall.normal);
        float[] centre = wallPoint(wall, s, y);
        float below = dot3(sub(wallPoint(wall, s, Math.max(0f, y - height * 0.5f)), centre), n);
        float above = dot3(sub(wallPoint(wall, s, y + height * 0.5f), centre), n);
        return Math.max(0f, Math.max(below, above));
    }

}
