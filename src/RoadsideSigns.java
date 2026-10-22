import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Signs: on houses' walls and beside the roads, in their nation's writing, with pictures
 * (flags, maps of the area drawn in the background, portraits) laid out on the board.
 */
final class RoadsideSigns extends InfraPart {

    RoadsideSigns(InfrastructureManager infra) {
        super(infra);
    }

    /** A sign in the front garden beside the path, text facing the road (as the house does), in a frame of its own. */
    InfrastructureObject createHouseSign(House house, Random rand) {
        float lateral = (rand.nextBoolean() ? 1.0f : -1.0f) * (house.width * 0.3f + 8.0f);
        float forward = house.depth * 0.5f + Math.max(3.0f, (house.setback - FRONT_FENCE_GAP) * 0.5f);
        SpherePosition foot = world(house, lateral, 0f, forward);
        if (infra.isRoadLocation(foot, 2.0f)) {
            return null;
        }
        float signY = groundAt(house, lateral, forward);
        if (signY <= infra.seaLevel + 0.5f) {
            return null;
        }
        SpherePosition at = SphereMath.atAltitude(foot, signY);
        return createSign(house.frame.movedTo(at), house.nationId, rand);
    }

    /** A sign beside the road, text facing across the carriageway, laid out in the segment's frame. More likely the more urban the street. */
    InfrastructureObject tryCreateRoadsideSign(RoadSegment segment, Random rand, List<SpherePosition> existingSigns) {
        Double probability = infra.nationSignProbabilities.get(segment.nationId);
        if (probability == null) {
            return null;
        }
        double urbanBoost = 1.0 + URBAN_SIGN_BOOST * infra.settlementManager.getUrbanness(segment.frame.origin());
        if (rand.nextDouble() >= probability * ROADSIDE_SIGN_CHANCE_SCALE * urbanBoost) {
            return null;
        }

        float t = 0.2f + rand.nextFloat() * 0.6f;
        float side = rand.nextBoolean() ? 1.0f : -1.0f;
        float dirX = (segment.end.x - segment.start.x) / segment.length;
        float dirZ = (segment.end.z - segment.start.z) / segment.length;
        float outwardX = -dirZ * side;
        float outwardZ = dirX * side;
        float offset = segment.width * 0.5f + ROADSIDE_SIGN_MARGIN;

        float signX = segment.start.x + (segment.end.x - segment.start.x) * t + outwardX * offset;
        float signZ = segment.start.z + (segment.end.z - segment.start.z) * t + outwardZ * offset;
        SpherePosition foot = world(segment, signX, signZ);
        if (infra.isRoadLocation(foot, 2.0f) || infra.isPlotLocation(foot, 2.0f)) {
            return null;
        }
        for (SpherePosition existing : existingSigns) {
            if (SphereMath.distance(existing, foot) < SIGN_SPACING) {
                return null;
            }
        }
        float signY = TerrainMesh.getLayeredHeight(foot, infra.terrainNoise);
        if (signY <= infra.seaLevel + 0.5f) {
            return null;
        }
        SpherePosition at = SphereMath.atAltitude(foot, signY);
        // Its face (local +z) back towards the road, that heading carried from the segment's middle
        double facing = SphereMath.carryHeading(segment.frame.origin(), foot, Math.atan2(-outwardZ, -outwardX));
        return createSign(SphereFrame.at(at, facing - Math.PI * 0.5), segment.nationId, rand);
    }

    /** A standing sign in its own frame (its foot at the origin, text facing local +z). */
    private InfrastructureObject createSign(SphereFrame frame, int nationId, Random rand) {
        int maxGlyphs = infra.mainListener != null ? infra.mainListener.getNationAtlasSize(nationId) : 10;
        if (maxGlyphs <= 0) maxGlyphs = 1;
        int[] textString = randomText(rand, maxGlyphs, 80, 200);
        InfrastructureObject sign = InfrastructureObject.createSign(frame, nationId, textString);
        // Separate streams, so the pictures and title don't change what else the cell holds
        Random design = new Random(rand.nextLong());
        decorateSign(sign, design, maxGlyphs, false);
        return sign;
    }

    /** Words of random letters, separated by spaces. */
    static int[] randomText(Random rand, int maxGlyphs, int minLen, int maxLen) {
        int stringLength = minLen + rand.nextInt(maxLen - minLen + 1);
        int[] textString = new int[stringLength];
        int wordLengthCounter = 0;
        int targetWordLength = 3 + rand.nextInt(6);
        for (int c = 0; c < stringLength; c++) {
            if (wordLengthCounter >= targetWordLength) {
                textString[c] = 0;
                wordLengthCounter = 0;
                targetWordLength = 3 + rand.nextInt(6);
            } else {
                textString[c] = 1 + rand.nextInt(maxGlyphs);
                wordLengthCounter++;
            }
        }
        return textString;
    }

    /**
     * Gives a sign its pictures (none to three: the flag, a map, the locals, their goods)
     * and perhaps a title. Some signs are adverts for a shop nearby: titled with the shop's
     * name, showing its goods, people holding them, or a map of the way there.
     */
    void decorateSign(InfrastructureObject sign, Random rand, int maxGlyphs, boolean poster) {
        int nation = sign.nationId;
        boolean vertical = infra.scripts.direction(nation) >= 2;
        InfrastructureManager.Shop shop = infra.shopBuilder.nearestShop(sign.frame.origin(), 2600f, nation);
        boolean advert = shop != null && rand.nextFloat() < (poster ? 0.8f : 0.4f);
        float patriotism = infra.patriotismOf(nation);

        if (advert) {
            int[] name = infra.shopBuilder.shopName(shop);
            System.arraycopy(name, 0, sign.titleString, 0, name.length);
            sign.titleLength = name.length;
        } else if (rand.nextFloat() < 0.55f) {
            // A title of a word or two, short enough to fit its line whole: a line of a
            // vertical script holds six of its big letters, a horizontal one fourteen
            int most = vertical ? 6 : 12;
            int length = 0;
            int words = rand.nextFloat() < (vertical ? 0.2f : 0.6f) ? 2 : 1;
            for (int word = 0; word < words; word++) {
                int letters = 2 + rand.nextInt(vertical ? 3 : 5);
                if (length + (word > 0 ? 1 : 0) + letters > most) break;
                if (word > 0) sign.titleString[length++] = 0;
                for (int i = 0; i < letters; i++) sign.titleString[length++] = 1 + rand.nextInt(maxGlyphs);
            }
            sign.titleLength = length;
        }
        if (sign.titleLength > 0) letter(sign, rand);
        if (advert && rand.nextFloat() < 0.5f) {
            // Adverts are often printed on a coloured ground
            sign.boardColour = WorldPalette.hsv(rand.nextFloat(), 0.12f + rand.nextFloat() * 0.18f, 0.9f + rand.nextFloat() * 0.08f);
        }

        List<Integer> kinds = new ArrayList<>();
        if (advert) {
            if (rand.nextFloat() < 0.55f) kinds.add(InfrastructureObject.PICTURE_MAP);
            if (rand.nextFloat() < 0.65f) kinds.add(InfrastructureObject.PICTURE_ADVERT);
            if (rand.nextFloat() < 0.45f) kinds.add(InfrastructureObject.PICTURE_PRODUCT);
            if (rand.nextFloat() < 0.1f * patriotism) kinds.add(InfrastructureObject.PICTURE_FLAG);
            if (kinds.isEmpty()) kinds.add(rand.nextBoolean() ? InfrastructureObject.PICTURE_ADVERT : InfrastructureObject.PICTURE_PRODUCT);
        } else {
            float roll = rand.nextFloat();
            int count = roll < 0.45f ? 0 : roll < 0.8f ? 1 : roll < 0.95f ? 2 : 3;
            for (int i = 0; i < count; i++) {
                float flagWeight = 0.3f * patriotism;
                boolean hasMap = kinds.contains(InfrastructureObject.PICTURE_MAP);
                float mapWeight = hasMap ? 0f : 0.3f;
                float total = flagWeight + mapWeight + 0.25f + 0.18f + 0.07f;
                float pick = rand.nextFloat() * total;
                int kind;
                if ((pick -= flagWeight) < 0f) kind = InfrastructureObject.PICTURE_FLAG;
                else if ((pick -= mapWeight) < 0f) kind = InfrastructureObject.PICTURE_MAP;
                else if ((pick -= 0.25f) < 0f) kind = InfrastructureObject.PICTURE_PORTRAIT;
                else if ((pick -= 0.18f) < 0f) kind = InfrastructureObject.PICTURE_FIGURE;
                else kind = InfrastructureObject.PICTURE_PRODUCT;
                kinds.add(kind);
            }
        }
        while (kinds.size() > InfrastructureObject.MAX_PICTURES) kinds.remove(kinds.size() - 1);
        java.util.Collections.shuffle(kinds, rand);
        float[][] rects = layoutPictures(kinds, vertical, sign.titleLength > 0, rand);
        for (int i = 0; i < kinds.size(); i++) {
            sign.addPicture(kinds.get(i), rects[i], rand.nextInt(1 << 16));
            if (kinds.get(i) == InfrastructureObject.PICTURE_MAP) scheduleMap(sign, rects[i], advert ? shop : null, rand);
        }
    }

    /** A title's lettering: weight, slant, width, perhaps outlined or shadowed, and an ink of its own. */
    void letter(InfrastructureObject sign, Random rand) {
        float weight = new float[] { 0.25f, 0.35f, 0.5f, 0.62f }[rand.nextInt(4)];
        float slant = rand.nextFloat() < 0.35f ? (rand.nextBoolean() ? 0.22f : -0.18f) : 0f;
        float width = 0.75f + rand.nextFloat() * 0.4f;
        boolean outline = rand.nextFloat() < 0.15f;
        sign.titleFont = new float[] { weight, slant, width, outline ? 1f : 0f };
        sign.titleShadow = !outline && rand.nextFloat() < 0.2f;
        float inkRoll = rand.nextFloat();
        if (inkRoll < 0.4f) {
            sign.titleColour = new float[] { 0.05f, 0.05f, 0.06f };
        } else if (inkRoll < 0.65f && infra.flags.containsKey(sign.nationId)) {
            // One of the flag's colours, if it shows on white
            List<float[]> colours = infra.flags.get(sign.nationId).colours;
            float[] c = colours.get(rand.nextInt(colours.size()));
            boolean pale = c[0] + c[1] + c[2] > 2.2f;
            sign.titleColour = pale ? new float[] { 0.05f, 0.05f, 0.06f } : c;
        } else {
            sign.titleColour = WorldPalette.hsv(rand.nextFloat(), 0.6f + rand.nextFloat() * 0.3f, 0.3f + rand.nextFloat() * 0.3f);
        }
    }

    /**
     * Where each picture goes, as fractions of the text area: in different corners (or, a
     * lone picture, now and then taking up most of the board), clear of the title's lines.
     */
    private static float[][] layoutPictures(List<Integer> kinds, boolean vertical, boolean title, Random rand) {
        int count = kinds.size();
        float[][] rects = new float[count][];
        // The title takes the first two of twelve lines: rows for a horizontal script, columns for a vertical one
        float ax = title && vertical ? 2f / 12f + 0.01f : 0f;
        float ay = title && !vertical ? 2f / 12f + 0.02f : 0f;
        float availW = 1f - ax, availH = 1f - ay;
        boolean large = count == 1 && rand.nextFloat() < 0.3f;
        float maxW = count == 1 ? (large ? Math.min(0.72f, availW) : 0.45f) : count == 2 ? 0.44f : 0.38f;
        float maxH = count == 1 ? (large ? availH : Math.min(0.62f, availH)) : (availH - 0.1f) / 2f;
        List<Integer> corners = new ArrayList<>(List.of(0, 1, 2, 3));
        java.util.Collections.shuffle(corners, rand);
        for (int i = 0; i < count; i++) {
            int kind = kinds.get(i);
            // Width over height in world units
            float aspect = switch (kind) {
                case InfrastructureObject.PICTURE_FLAG -> 1.5f;
                case InfrastructureObject.PICTURE_FIGURE -> 0.6f;
                case InfrastructureObject.PICTURE_MAP -> 0.8f + rand.nextFloat() * 1.0f;
                default -> 0.75f;
            };
            float f = large ? 1f : 0.75f + rand.nextFloat() * 0.25f;
            float w = maxW * f;
            float h = w * 27.6f / 19.36f / aspect;
            if (h > maxH * f) {
                h = maxH * f;
                w = h * aspect * 19.36f / 27.6f;
            }
            int corner = corners.get(i);
            float x0 = corner % 2 == 0 ? ax : 1f - w;
            float y0 = corner < 2 ? ay : 1f - h;
            rects[i] = new float[] { x0, y0, x0 + w, y0 + h };
        }
        return rects;
    }

    /**
     * Draws a sign's map off the GL thread: of the area round the sign at any scale, or, on
     * an advert, of the way from the sign to the shop.
     */
    private void scheduleMap(InfrastructureObject sign, float[] rect, InfrastructureManager.Shop shop, Random rand) {
        float worldW = (rect[2] - rect[0]) * sign.boardWidth * 0.92f, worldH = (rect[3] - rect[1]) * sign.boardHeight * 0.88f;
        int mapW = worldW >= worldH ? 256 : Math.max(64, Math.round(256 * worldW / worldH));
        int mapH = worldW >= worldH ? Math.max(64, Math.round(256 * worldH / worldW)) : 256;
        sign.mapWidth = mapW;
        sign.mapHeight = mapH;
        // Laid out north up round the sign, in true distances along the ground (x east, z south)
        SpherePosition foot = SphereMath.atAltitude(sign.frame.origin(), 0.0);
        SphereFrame round = SphereFrame.at(foot);
        float aspect = mapW / (float) mapH;
        float cx, cz, halfWidth;
        SpherePosition here, destination = null;
        boolean route = false;
        java.awt.image.BufferedImage badge = null;
        if (shop != null) {
            destination = infra.doorwayOf(shop.house).door;
            double[] door = round.fromSphere(destination);
            cx = (float) door[0] * 0.5f;
            cz = (float) door[2] * 0.5f;
            halfWidth = Math.max(Math.abs(cx), Math.abs(cz) * aspect) * 1.45f + 90f;
            here = rand.nextFloat() < 0.75f ? foot : null;
            route = here != null && rand.nextFloat() < 0.85f;
            int[] name = infra.shopBuilder.shopName(shop);
            List<java.awt.image.BufferedImage> glyphs = infra.scripts.glyphs(shop.nationId);
            for (int letter : name) {
                if (letter > 0 && letter < glyphs.size()) { badge = glyphs.get(letter); break; }
            }
        } else {
            halfWidth = (float) (150.0 * Math.pow(25.0, rand.nextFloat())) * (mapW / (float) Math.max(mapW, mapH));
            float halfHeight = halfWidth / aspect;
            cx = (rand.nextFloat() - 0.5f) * halfWidth * 0.8f;
            cz = (rand.nextFloat() - 0.5f) * halfHeight * 0.8f;
            here = rand.nextFloat() < 0.6f ? foot : null;
        }
        // (the map's own north-up frame at its middle)
        SphereFrame centre = SphereFrame.at(round.toSphere(cx, 0.0, cz));
        long style = infra.worldSeed * 61L + sign.nationId * 17L;
        PerlinNoise noise = infra.terrainNoise;
        float sea = infra.seaLevel;
        SpherePosition hereMark = here, shopMark = destination;
        boolean withRoute = route;
        java.awt.image.BufferedImage letter = badge;
        float half = halfWidth;
        InfrastructureManager.SIGN_MAP_ARTIST.submit(() -> {
            try {
                List<SpherePosition> way = withRoute ? infra.routes().route(hereMark, shopMark) : null;
                sign.mapPixels = SignMap.render(infra, noise, sea, style, centre, half, mapW, mapH, hereMark, shopMark, way, letter);
            } catch (RuntimeException e) {
                System.err.println("[SIGNS] Map failed: " + e);
            }
        });
    }

    // Roadside signs roll once per road segment rather than per attempt, so scale the nation's sign chance up
    static final double ROADSIDE_SIGN_CHANCE_SCALE = 40.0;

    static final float ROADSIDE_SIGN_MARGIN = 8.0f;

    static final float SIGN_SPACING = 36.0f;

    // Busy streets carry far more signage than country roads
    static final double URBAN_SIGN_BOOST = 3.0;
}
