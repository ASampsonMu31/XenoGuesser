import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * The land shaped for the natural landmarks (see Landmarks), from the world's seed: fields of
 * steep pointed hills standing up out of nowhere (one rising from the sea and its shore, one
 * from an inland plain), stacks of rock rising from the planet's most sheltered bay, and a long
 * ribbon of sand built out from a low shore into the shallows, hooking round at its tip.
 * <p>
 * Worked out from the bare land once the sea's level is known (see shape), and laid on it by
 * apply, which TerrainMesh calls for every height before the rivers' channels are cut: so the
 * rivers, roads, houses, plants, maps and the world as it's drawn all see the same land. Each
 * is no wider than the haze lets a visitor see whole, so it can be photographed (see
 * LandmarkPhotos), and where each one is goes to Landmarks as a site.
 */
final class Landforms {

    private static final float M = PlayerBody.METRE;

    /**
     * A steep pointed hill: its middle (a unit vector), how far its foot reaches (world units),
     * how high it rises over the land there, its profile (the height a share u of the way out is
     * rise * (1 - u^shoulder)^sharpness: a needle narrows all the way up to its point, a tower
     * stands sheer to a rounded top), how its foot bulges round (a lobe's size and turn: no two
     * are round), how far its top stands off its middle (leaning one way), and the grooves
     * running down its sides.
     */
    record Peak(double x, double y, double z, float radius, float rise, float shoulder, float sharpness, float lobe, float turn,
                float lean, float flutes) {
    }

    // The forms a hill takes: a needle, a sugarloaf, a sheer-sided tower
    private static final int NEEDLE = 0, SUGARLOAF = 1, TOWER = 2;

    /** A group of hills, with a cap round them (the cosine of its angle) for a quick test of whether a place is near any. */
    private record Field(double x, double y, double z, double cosReach, Peak[] peaks) {
    }

    /**
     * The spit's ribbon: points along its middle (unit vectors, from its root on the shore to its
     * tip), its half width at the root (narrowing to the tip), how high its sand lies above the
     * sea, and a cap round it all.
     */
    private record Ribbon(double[][] points, float halfWidth, float sand, double x, double y, double z, double cosReach) {
    }

    /**
     * A landmark the land was shaped for: its kind (PINNACLES, BAY or SPIT), its place on the
     * datum, the way it runs (a spit's, from its root out to sea; a field's by the sea, from the
     * land to the sea, NaN inland), its size (a field's or a bay's radius, a spit's length, in world units), how many
     * hills or stacks it has, and a measure of it (a bay's shelter, 0 to 1; 1 for a field by the sea).
     */
    record Site(Landmark.Kind kind, SpherePosition place, double heading, float size, int count, float measure) {
    }

    private final List<Field> fields = new ArrayList<>();
    private Ribbon ribbon;
    final List<Site> sites = new ArrayList<>();
    private final float seaLevel;

    // How far apart the landforms are kept (as Landmarks keeps the natural ones)
    private static final double APART = 8000.0;

    private Landforms(float seaLevel) {
        this.seaLevel = seaLevel;
    }

    /**
     * Finds the places for the landforms over the bare land (noise without any landforms yet)
     * and shapes them: quick, a few hundred thousand heights, side by side. The same seed always
     * gives the same.
     */
    static Landforms shape(PerlinNoise noise, long seed, float seaLevel) {
        long start = System.currentTimeMillis();
        Landforms forms = new Landforms(seaLevel);
        Random rand = new Random(seed * 6151L + 29L);
        java.util.function.ToDoubleFunction<SpherePosition> ground = p -> TerrainMesh.getLayeredHeight(p, noise);

        // Points spread evenly over the sphere (short of the map's clipped poles), and which are
        // land by the sea (the sea within 90 m of them)
        int count = 24000;
        double golden = Math.PI * (3 - Math.sqrt(5)), top = Math.sin(MapProjection.CLIP_LATITUDE * 0.85);
        SpherePosition[] points = new SpherePosition[count];
        for (int i = 0; i < count; i++) {
            double y = top * (1 - 2 * (i + 0.5) / count), across = Math.sqrt(1 - y * y), longitude = golden * i;
            points[i] = SphereMath.atAltitude(new GeodesicChunkGrid.Direction(across * Math.cos(longitude), y, -across * Math.sin(longitude)), 0.0);
        }
        float[] heights = new float[count];
        boolean[] coastal = new boolean[count];
        IntStream.range(0, count).parallel().forEach(i -> {
            heights[i] = (float) ground.applyAsDouble(points[i]);
            if (heights[i] <= seaLevel) return;
            SphereFrame f = SphereFrame.at(points[i]);
            for (int k = 0; k < 6; k++) {
                double a = Math.PI * 2 * k / 6;
                if (ground.applyAsDouble(f.toSphere(Math.cos(a) * 900.0, 0.0, Math.sin(a) * 900.0)) < seaLevel) {
                    coastal[i] = true;
                    break;
                }
            }
        });
        List<Integer> coast = new ArrayList<>(), plains = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (coastal[i]) coast.add(i);
            else if (heights[i] > seaLevel + 5f * M && heights[i] < seaLevel + 45f * M) plains.add(i);
        }
        java.util.Collections.shuffle(coast, rand);
        java.util.Collections.shuffle(plains, rand);
        if (coast.size() > 360) coast = coast.subList(0, 360);
        if (plains.size() > 300) plains = plains.subList(0, 300);

        forms.findBay(coast, points, ground, rand);
        forms.findSpit(coast, points, heights, ground, rand);
        forms.findPinnacles(coast, points, heights, ground, rand, true);
        forms.findPinnacles(plains, points, heights, ground, rand, false);
        System.out.printf("[LANDFORMS] %d shaped in %d ms%n", forms.sites.size(), System.currentTimeMillis() - start);
        for (Site s : forms.sites) {
            System.out.printf(java.util.Locale.ROOT, "[LANDFORMS] %s at %.3f, %.3f, %d m across%n", s.kind(),
                    Math.toDegrees(Planet.latitude(s.place())), Math.toDegrees(Planet.longitude(s.place())), Math.round(s.size() * 2f / M));
        }
        return forms;
    }

    /** Whether a place is well away from every landform found so far. */
    private boolean clear(SpherePosition place) {
        for (Site s : sites) if (SphereMath.distance(s.place(), place) < APART) return false;
        return true;
    }

    // ------------------------------------------------------------------ the bay and its stacks

    /**
     * The most sheltered bay (open water closed in by land on every side but its mouth), its
     * water studded with stacks of rock: pointed islets standing sheer from it.
     */
    private void findBay(List<Integer> coast, SpherePosition[] points, java.util.function.ToDoubleFunction<SpherePosition> ground, Random rand) {
        float[] best = new float[coast.size()];
        SpherePosition[] water = new SpherePosition[coast.size()];
        IntStream.range(0, coast.size()).parallel().forEach(c -> {
            SphereFrame f = SphereFrame.at(points[coast.get(c)]);
            for (int k = 0; k < 4; k++) {
                double a = Math.PI * 2 * k / 4 + 0.4;
                SpherePosition w = f.toSphere(Math.cos(a) * 900.0, 0.0, Math.sin(a) * 900.0);
                if (ground.applyAsDouble(w) > seaLevel - 3f * M) continue;
                float shelter = shelter(w, ground);
                if (shelter > best[c]) {
                    best[c] = shelter;
                    water[c] = w;
                }
            }
        });
        int pick = -1;
        for (int c = 0; c < coast.size(); c++) if (water[c] != null && (pick < 0 || best[c] > best[pick])) pick = c;
        if (pick < 0 || best[pick] < 0.5f) return;
        SpherePosition middle = water[pick];
        // The stacks: in open water round its middle, clear of the shore
        SphereFrame f = SphereFrame.at(middle);
        List<Peak> stacks = new ArrayList<>();
        int wanted = 5 + rand.nextInt(4);
        for (int attempt = 0; attempt < 200 && stacks.size() < wanted; attempt++) {
            double a = rand.nextDouble() * Math.PI * 2, d = 60.0 + rand.nextDouble() * 520.0;
            SpherePosition at = f.toSphere(Math.cos(a) * d, 0.0, Math.sin(a) * d);
            float radius = (3.5f + rand.nextFloat() * 4f) * M;
            if (!openWater(at, radius * 1.6f, ground) || crowded(stacks, at, radius, 1.3f)) continue;
            float rise = (9f + rand.nextFloat() * 14f) * M;
            stacks.add(peak(at, radius, seaLevel + rise - (float) ground.applyAsDouble(at), rand.nextFloat() < 0.6f ? TOWER : SUGARLOAF, rand));
        }
        if (stacks.size() < 3) return;
        addField(middle, stacks);
        sites.add(new Site(Landmark.Kind.BAY, middle, 0.0, 600f, stacks.size(), best[pick]));
    }

    /** How closed in by land a stretch of water is: the share of 24 ways out from it that reach land within half a kilometre. */
    private float shelter(SpherePosition water, java.util.function.ToDoubleFunction<SpherePosition> ground) {
        SphereFrame f = SphereFrame.at(water);
        int closed = 0, ways = 24, open = 0, longestOpen = 0;
        boolean[] shut = new boolean[ways];
        for (int k = 0; k < ways; k++) {
            double a = Math.PI * 2 * k / ways;
            for (double d = 300.0; d <= 4800.0; d += 300.0) {
                if (ground.applyAsDouble(f.toSphere(Math.cos(a) * d, 0.0, Math.sin(a) * d)) > seaLevel) {
                    shut[k] = true;
                    closed++;
                    break;
                }
            }
        }
        // (a bay has a mouth: two or more ways out side by side, not a lake shut in all round)
        for (int k = 0; k < ways * 2; k++) {
            if (!shut[k % ways]) longestOpen = Math.max(longestOpen, ++open);
            else open = 0;
        }
        if (longestOpen < 2 || closed == ways) return 0f;
        return closed / (float) ways;
    }

    /** Whether the water's at least a metre deep everywhere within a distance of a place. */
    private boolean openWater(SpherePosition at, float within, java.util.function.ToDoubleFunction<SpherePosition> ground) {
        if (ground.applyAsDouble(at) > seaLevel - M) return false;
        SphereFrame f = SphereFrame.at(at);
        for (int k = 0; k < 8; k++) {
            double a = Math.PI * 2 * k / 8;
            if (ground.applyAsDouble(f.toSphere(Math.cos(a) * within, 0.0, Math.sin(a) * within)) > seaLevel - M) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the spit

    /**
     * The spit: from a low shore out over shallow, open water, a ribbon of sand a little above
     * the sea, narrowing and hooking round at its tip (as the waves bend a real one).
     */
    private void findSpit(List<Integer> coast, SpherePosition[] points, float[] heights, java.util.function.ToDoubleFunction<SpherePosition> ground, Random rand) {
        float[] score = new float[coast.size()];
        double[] heading = new double[coast.size()];
        IntStream.range(0, coast.size()).parallel().forEach(c -> {
            int i = coast.get(c);
            if (heights[i] > seaLevel + 4f * M) return;
            SpherePosition p = points[i];
            for (int k = 0; k < 12; k++) {
                double a = Math.PI * 2 * k / 12;
                // (open water ahead, and either side of the way it runs, all the way out...)
                float deepest = 0f;
                boolean open = true;
                for (double d = 250.0; d <= 1500.0 && open; d += 125.0) {
                    SpherePosition ahead = SphereMath.move(p, a, d);
                    for (double side : new double[] { -260.0, 0.0, 260.0 }) {
                        float h = (float) ground.applyAsDouble(side == 0.0 ? ahead : SphereMath.move(ahead, a + Math.PI * 0.5, side));
                        if (h > seaLevel - 0.5f * M) open = false;
                        deepest = Math.max(deepest, seaLevel - h);
                    }
                }
                // (...and shallow, as sand builds up only where the sea's shallow)
                if (!open || deepest > 14f * M) continue;
                float s = 1f - deepest / (14f * M) + 0.001f * k;
                if (s > score[c]) {
                    score[c] = s;
                    heading[c] = a;
                }
            }
        });
        int pick = -1;
        for (int c = 0; c < coast.size(); c++) {
            if (score[c] > 0f && clear(points[coast.get(c)]) && (pick < 0 || score[c] > score[pick])) pick = c;
        }
        if (pick < 0) return;
        SpherePosition root = points[coast.get(pick)];
        double way = heading[pick];
        float length = (95f + rand.nextFloat() * 35f) * M;
        float hook = (float) Math.toRadians(35 + rand.nextFloat() * 45) * (rand.nextBoolean() ? 1f : -1f);
        // Its middle, from a little behind the shore, straight at first, then bending round
        int steps = 24;
        double[][] line = new double[steps + 1][];
        SpherePosition at = SphereMath.move(root, way + Math.PI, 12f * M);
        double along = way;
        double step = (length + 12f * M) / steps;
        for (int k = 0; k <= steps; k++) {
            GeodesicChunkGrid.Direction d = SphereMath.direction(at);
            line[k] = new double[] { d.x(), d.y(), d.z() };
            if (k == steps) break;
            double t = k / (double) steps;
            double turn = t < 0.55 ? 0.0 : hook * (t - 0.55) / 0.45 / (steps * 0.45);
            SpherePosition next = SphereMath.move(at, along, step);
            along = SphereMath.carryHeading(at, next, along) + turn;
            at = next;
        }
        float halfWidth = (9f + rand.nextFloat() * 5f) * M;
        GeodesicChunkGrid.Direction mid = SphereMath.direction(SphereMath.atAltitude(
                new GeodesicChunkGrid.Direction(line[steps / 2][0], line[steps / 2][1], line[steps / 2][2]), 0.0));
        double reach = (length * 0.5 + halfWidth * 3) / Planet.radius();
        ribbon = new Ribbon(line, halfWidth, 0.6f * M, mid.x(), mid.y(), mid.z(), Math.cos(reach + 0.02));
        sites.add(new Site(Landmark.Kind.SPIT, SphereMath.atAltitude(mid, 0.0), way, length, 0, 0f));
    }

    // ------------------------------------------------------------------ the pinnacles

    /**
     * A field of steep pointed hills: by the sea, a cove's shore with its shallows (some standing
     * as islets in the water); inland, a flat plain they rise sheer out of.
     */
    private void findPinnacles(List<Integer> candidates, SpherePosition[] points, float[] heights,
                               java.util.function.ToDoubleFunction<SpherePosition> ground, Random rand, boolean bySea) {
        float radius = (bySea ? 52f : 45f) * M;
        float[] score = new float[candidates.size()];
        double[] seaward = new double[candidates.size()];
        IntStream.range(0, candidates.size()).parallel().forEach(c -> {
            int i = candidates.get(c);
            SpherePosition p = points[i];
            if (bySea && heights[i] > seaLevel + 8f * M) return;
            // (a ring round it: by the sea, part water and shallow; inland, all of it near level)
            int wet = 0, n = 0;
            float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
            double wx = 0.0, wz = 0.0;
            SphereFrame f = SphereFrame.at(p);
            for (float share : new float[] { 0.5f, 1f }) {
                for (int k = 0; k < 12; k++) {
                    double a = Math.PI * 2 * k / 12;
                    float h = (float) ground.applyAsDouble(f.toSphere(Math.cos(a) * radius * share, 0.0, Math.sin(a) * radius * share));
                    n++;
                    low = Math.min(low, h);
                    high = Math.max(high, h);
                    if (h < seaLevel) {
                        wet++;
                        wx += Math.cos(a);
                        wz += Math.sin(a);
                    }
                }
            }
            float water = wet / (float) n;
            if (bySea) {
                if (water < 0.2f || water > 0.6f || low < seaLevel - 12f * M) return;
                score[c] = 1f - Math.abs(water - 0.4f) + jitter(i) * 0.3f;
                seaward[c] = Math.atan2(wz, wx);
            } else {
                if (wet > 0 || high - low > 9f * M) return;
                score[c] = 1f - (high - low) / (9f * M) + jitter(i) * 0.5f;
            }
        });
        int pick = -1;
        for (int c = 0; c < candidates.size(); c++) {
            if (score[c] > 0f && clear(points[candidates.get(c)]) && (pick < 0 || score[c] > score[pick])) pick = c;
        }
        if (pick < 0) return;
        SpherePosition middle = points[candidates.get(pick)];
        SphereFrame f = SphereFrame.at(middle);
        List<Peak> hills = new ArrayList<>();
        int wanted = (bySea ? 13 : 10) + rand.nextInt(6);
        for (int attempt = 0; attempt < 400 && hills.size() < wanted; attempt++) {
            // (thicker towards the middle, a few strays further out)
            double a = rand.nextDouble() * Math.PI * 2, d = radius * Math.sqrt(rand.nextDouble()) * (hills.isEmpty() ? 0.2 : 1.0);
            SpherePosition at = f.toSphere(Math.cos(a) * d, 0.0, Math.sin(a) * d);
            float r = (bySea ? 6f + rand.nextFloat() * 7f : 7f + rand.nextFloat() * 8f) * M;
            if (crowded(hills, at, r, 0.85f)) continue;
            float h = (float) ground.applyAsDouble(at);
            // (the tallest in the middle; one standing in the water rises from the sea bed to as high as one on land)
            float tall = (bySea ? 16f + rand.nextFloat() * 22f : 18f + rand.nextFloat() * 24f) * M * (1f - 0.35f * (float) (d / radius));
            float rise = Math.max(tall, seaLevel + tall * 0.85f - h);
            float form = rand.nextFloat();
            hills.add(peak(at, r, rise, form < 0.4f ? NEEDLE : form < 0.75f ? SUGARLOAF : TOWER, rand));
        }
        if (hills.size() < 6) return;
        addField(middle, hills);
        // (seen from the land, looking out over them to the sea)
        double toSea = seaward[pick];
        double heading = bySea ? SphereMath.heading(middle, f.toSphere(Math.cos(toSea) * 100.0, 0.0, Math.sin(toSea) * 100.0)) : Double.NaN;
        sites.add(new Site(Landmark.Kind.PINNACLES, middle, heading, radius, hills.size(), bySea ? 1f : 0f));
    }

    /** A steady number from 0 to 1 for a point (so a field's choice doesn't hang on the threads' order). */
    private static float jitter(int i) {
        long h = i * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Whether a hill at a place (of a radius) would stand too close to one already placed. */
    private static boolean crowded(List<Peak> peaks, SpherePosition at, float radius, float overlap) {
        GeodesicChunkGrid.Direction d = SphereMath.direction(at);
        double r = Planet.radius();
        for (Peak p : peaks) {
            double dx = (d.x() - p.x()) * r, dy = (d.y() - p.y()) * r, dz = (d.z() - p.z()) * r;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < (radius + p.radius()) * overlap) return true;
        }
        return false;
    }

    /** A hill of a form, the rest of its shape from rand. */
    private static Peak peak(SpherePosition at, float radius, float rise, int form, Random rand) {
        GeodesicChunkGrid.Direction d = SphereMath.direction(at);
        float shoulder, sharpness;
        switch (form) {
            case NEEDLE -> {
                shoulder = 1f + rand.nextFloat() * 0.3f;
                sharpness = 1.8f + rand.nextFloat() * 0.7f;
            }
            case SUGARLOAF -> {
                shoulder = 1.8f + rand.nextFloat() * 0.7f;
                sharpness = 1f + rand.nextFloat() * 0.45f;
                rise *= 0.85f;
            }
            default -> {
                shoulder = 3f + rand.nextFloat() * 1.5f;
                sharpness = 0.55f + rand.nextFloat() * 0.3f;
                radius *= 0.8f;
            }
        }
        return new Peak(d.x(), d.y(), d.z(), radius, rise, shoulder, sharpness, 0.06f + rand.nextFloat() * 0.12f, rand.nextFloat() * (float) Math.PI * 2f,
                rand.nextFloat() * 0.3f, rand.nextFloat() < 0.6f ? 0.03f + rand.nextFloat() * 0.04f : 0f);
    }

    private void addField(SpherePosition middle, List<Peak> peaks) {
        GeodesicChunkGrid.Direction c = SphereMath.direction(middle);
        double reach = 0.0;
        for (Peak p : peaks) {
            double angle = Math.acos(Math.max(-1.0, Math.min(1.0, p.x() * c.x() + p.y() * c.y() + p.z() * c.z())));
            reach = Math.max(reach, angle + p.radius() * (1.05 + p.lobe() + p.lean() + p.flutes()) / Planet.radius());
        }
        fields.add(new Field(c.x(), c.y(), c.z(), Math.cos(reach), peaks.toArray(new Peak[0])));
    }

    // ------------------------------------------------------------------ on the land

    /** Whether a place is within clearance of the land shaped here: a hill's or a stack's foot, or the spit's sand (where no house is built). */
    boolean covers(SpherePosition place, double clearance) {
        GeodesicChunkGrid.Direction d = SphereMath.direction(place);
        double r = Planet.radius();
        for (Field f : fields) {
            if (d.x() * f.x() + d.y() * f.y() + d.z() * f.z() < Math.cos(Math.acos(f.cosReach()) + clearance / r)) continue;
            for (Peak p : f.peaks()) {
                double dx = (d.x() - p.x()) * r, dy = (d.y() - p.y()) * r, dz = (d.z() - p.z()) * r;
                double reach = p.radius() * (1.0 + p.lobe() + p.lean() + p.flutes()) + clearance;
                if (dx * dx + dy * dy + dz * dz < reach * reach) return true;
            }
        }
        Ribbon s = ribbon;
        if (s == null) return false;
        for (double[] point : s.points()) {
            double dx = (d.x() - point[0]) * r, dy = (d.y() - point[1]) * r, dz = (d.z() - point[2]) * r;
            double reach = s.halfWidth() + 9.0 * M + clearance;
            if (dx * dx + dy * dy + dz * dz < reach * reach) return true;
        }
        return false;
    }

    /** The ground's height (above the datum) at a point on the planet's surface, with the landforms on the bare land's. */
    float apply(float[] surface, float height) {
        double r = Planet.radius();
        double ux = surface[0] / r, uy = surface[1] / r, uz = surface[2] / r;
        for (Field f : fields) {
            if (ux * f.x() + uy * f.y() + uz * f.z() < f.cosReach()) continue;
            for (Peak p : f.peaks()) {
                double dx = surface[0] - p.x() * r, dy = surface[1] - p.y() * r, dz = surface[2] - p.z() * r;
                double d2 = dx * dx + dy * dy + dz * dz, reach = p.radius() * (1.0 + p.lobe() + p.lean() + p.flutes());
                if (d2 >= reach * reach) continue;
                // (its foot bulges out on a few sides and further on the side it leans, grooved
                // all round, turned its own way: by the angle round from a fixed way along the
                // ground, east of its middle)
                double ex = -p.z(), ez = p.x(), el = Math.max(1e-9, Math.sqrt(ex * ex + ez * ez));
                ex /= el;
                ez /= el;
                double nx = p.y() * ez, ny = p.z() * ex - p.x() * ez, nz = -p.y() * ex;
                double around = Math.atan2(dx * nx + dy * ny + dz * nz, dx * ex + dz * ez);
                double edge = p.radius() * (1.0 + p.lobe() * Math.sin(around * 3.0 + p.turn()) + p.lean() * Math.cos(around - p.turn())
                        + p.flutes() * Math.sin(around * 9.0 + p.turn() * 2.0));
                double u = Math.sqrt(d2) / edge;
                if (u >= 1.0) continue;
                height += (float) (p.rise() * Math.pow(1.0 - Math.pow(u, p.shoulder()), p.sharpness()));
            }
        }
        Ribbon s = ribbon;
        if (s != null && ux * s.x() + uy * s.y() + uz * s.z() >= s.cosReach()) height = spit(s, surface, height);
        return height;
    }

    /** The ground near the spit: levelled to its sand (land lowered, sea bed raised), fading back to the land as it was beyond its edges. */
    private float spit(Ribbon s, float[] surface, float height) {
        double r = Planet.radius();
        double best = Double.MAX_VALUE, bestT = 0.0;
        int n = s.points().length - 1;
        for (int k = 0; k < n; k++) {
            double[] a = s.points()[k], b = s.points()[k + 1];
            double ax = a[0] * r, ay = a[1] * r, az = a[2] * r;
            double bx = b[0] * r - ax, by = b[1] * r - ay, bz = b[2] * r - az;
            double px = surface[0] - ax, py = surface[1] - ay, pz = surface[2] - az;
            double t = Math.max(0.0, Math.min(1.0, (px * bx + py * by + pz * bz) / (bx * bx + by * by + bz * bz)));
            double qx = px - bx * t, qy = py - by * t, qz = pz - bz * t;
            double d2 = qx * qx + qy * qy + qz * qz;
            if (d2 < best) {
                best = d2;
                bestT = (k + t) / n;
            }
        }
        // (narrowing to a point at its tip, and a low crest along its middle)
        double half = s.halfWidth() * (1.0 - 0.75 * Math.max(0.0, (bestT - 0.6) / 0.4));
        double d = Math.sqrt(best), fade = 9.0 * M;
        if (d >= half + fade) return height;
        float sand = seaLevel + s.sand() + (float) (0.5f * M * Math.max(0.0, 1.0 - d / half));
        double w = d <= half ? 1.0 : 1.0 - (d - half) / fade;
        w = w * w * (3 - 2 * w);
        return height + (float) ((sand - height) * w);
    }
}
