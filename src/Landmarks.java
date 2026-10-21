import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import com.xenoguesser.math.Vector3;

/**
 * The world's famous landmarks (see Landmark), for its map of them (under Infrastructure) and,
 * for the built ones, the world itself.
 * <p>
 * The built ones are planned with the roads, before the houses are (which keep off them), all
 * from the world's seed so every PC has the same: a great spire in the busiest, richest streets
 * of a few of the richest nations; grand ruins out in the country, each in its region's
 * architecture; and circles of standing stones in the wilds, well away from the towns. The
 * natural ones are found from the land once it's known: the planet's highest summit, its most
 * sheltered bay, its narrowest spit of sand with the sea either side, and the groves where two
 * of its plants grow thickest.
 */
public final class Landmarks {

    private static final float M = PlayerBody.METRE;
    // No two landmarks closer than this
    private static final float APART = 4000f;

    /** What the built ones need to know about the world they're planned in. */
    public interface World {
        float ground(SpherePosition place);

        int nationAt(SpherePosition place);

        float urbanness(SpherePosition place);

        float richness(int nation, SpherePosition place);

        float nationRichness(int nation);

        /** Whether a place is within clearance of a road's edge. */
        boolean nearRoad(SpherePosition place, float clearance);

        /** The architecture a nation builds in at a place (null for none). */
        ArchitectureStyle architecture(int nation, SpherePosition place);

        /** Whether a river or lake is over a place (the ground there being so high). */
        boolean freshWater(SpherePosition place, float ground);

        /** A nation's dressing stone, and its commonest walls' colour. */
        Vector3 stone(int nation);

        Vector3 wall(int nation);
    }

    /** A road's middle, the way it runs (a heading at its place), how wide it is, whose and what kind. */
    public record RoadSpot(SpherePosition place, double heading, float halfWidth, int nation, boolean dirt) {
    }

    // The built ones (fixed once planned), and the natural ones (once found)
    private final List<Landmark> built = new ArrayList<>();
    private volatile List<Landmark> natural = List.of();
    private final CellIndex<Landmark> index = new CellIndex<>();
    // Which batch cell each built one is drawn with (see InfrastructureManager.generateForCell)
    private final Map<Long, List<Integer>> byCell = new HashMap<>();
    // The world's sea level
    private float seaLevel;

    // ------------------------------------------------------------------ the built ones

    /**
     * Plans the built ones beside the roads (sorted so the same seed always gives the same),
     * each on firm, fairly level land in one nation, clear of every road.
     */
    public void planBuilt(long seed, int nations, List<RoadSpot> spots, World world, float seaLevel,
                          ToDoubleFunction<SpherePosition> ground, java.util.function.ToLongFunction<SpherePosition> cellOf) {
        this.seaLevel = seaLevel;
        long start = System.currentTimeMillis();
        Random rand = new Random(seed * 7919L + 17L);
        Map<Integer, List<RoadSpot>> byNation = new HashMap<>();
        for (RoadSpot spot : spots) byNation.computeIfAbsent(spot.nation(), k -> new ArrayList<>()).add(spot);

        // Spires: in a few of the richest nations, by their busiest, richest streets
        List<Integer> rich = new ArrayList<>();
        for (int n = 1; n <= nations; n++) if (byNation.containsKey(n)) rich.add(n);
        rich.sort(Comparator.comparingDouble(n -> -world.nationRichness(n)));
        int spires = Math.max(1, Math.min(3, Math.round(nations / 4f)));
        for (int k = 0, made = 0; k < rich.size() && made < spires; k++) {
            int nation = rich.get(k);
            List<RoadSpot> streets = new ArrayList<>();
            for (RoadSpot s : byNation.get(nation)) if (!s.dirt() && world.urbanness(s.place()) > 0.2f) streets.add(s);
            streets.sort(Comparator.comparingDouble(s -> -world.urbanness(s.place()) * (0.5f + world.richness(nation, s.place()))));
            if (place(Landmark.Kind.SPIRE, nation, streets.subList(0, Math.min(400, streets.size())), rand, world, ground, seaLevel, 1f * M, 25f * M, 9f * M)) made++;
        }
        // Ruins: out in the country of two to four nations, the most ornate builders first
        List<Integer> ornate = new ArrayList<>(byNation.keySet());
        ornate.sort(Integer::compare);
        java.util.Collections.shuffle(ornate, rand);
        int ruins = 2 + rand.nextInt(3);
        for (int k = 0, made = 0; k < ornate.size() && made < ruins; k++) {
            int nation = ornate.get(k);
            List<RoadSpot> country = new ArrayList<>();
            for (RoadSpot s : byNation.get(nation)) if (world.urbanness(s.place()) < 0.2f) country.add(s);
            java.util.Collections.shuffle(country, rand);
            if (place(Landmark.Kind.RUIN, nation, country.subList(0, Math.min(120, country.size())), rand, world, ground, seaLevel, 12f * M, 60f * M, 3.5f * M)) made++;
        }
        // Standing stones: in the wilds, by the tracks and lanes, one to three of them
        List<RoadSpot> wild = new ArrayList<>();
        for (RoadSpot s : spots) if (world.urbanness(s.place()) < 0.08f) wild.add(s);
        java.util.Collections.shuffle(wild, rand);
        int circles = 1 + rand.nextInt(3);
        for (int made = 0, from = 0; made < circles && from < wild.size(); from += 60) {
            List<RoadSpot> some = wild.subList(from, Math.min(wild.size(), from + 60));
            if (place(Landmark.Kind.STONES, some.get(0).nation(), some, rand, world, ground, seaLevel, 8f * M, 40f * M, 6f * M)) made++;
        }

        for (int i = 0; i < built.size(); i++) {
            Landmark lm = built.get(i);
            index.add(lm.place(), lm.reach, lm);
            byCell.computeIfAbsent(cellOf.applyAsLong(lm.place()), c -> new ArrayList<>()).add(i);
            System.out.printf(java.util.Locale.ROOT, "[LANDMARKS] %s in nation %d at %s%n", lm.name, lm.nation, lm.place());
        }
        System.out.printf("[LANDMARKS] %d built landmarks planned in %d ms%n", built.size(), System.currentTimeMillis() - start);
    }

    /**
     * Puts one of a kind beside the first of these roads where it fits: on either side of it,
     * from near to far off it, its front towards it. False if it fits by none.
     */
    private boolean place(Landmark.Kind kind, int nation, List<RoadSpot> spots, Random rand, World world,
                          ToDoubleFunction<SpherePosition> ground, float seaLevel, float nearest, float farthest, float relief) {
        for (RoadSpot spot : spots) {
            // (the stones belong to whichever nation's wilds they're in)
            if (kind == Landmark.Kind.STONES) nation = spot.nation();
            if (spot.nation() != nation) continue;
            SphereFrame road = SphereFrame.at(spot.place(), spot.heading());
            float side = rand.nextBoolean() ? 1f : -1f;
            for (int attempt = 0; attempt < 4; attempt++) {
                float offset = nearest + rand.nextFloat() * (farthest - nearest);
                if (attempt >= 2) side = -side;
                long seed = rand.nextLong();
                // (sized first, to know how far off the road its middle has to be)
                Landmark trial = new Landmark(kind, SphereFrame.at(spot.place()), nation, seed, null, new Vector3(0.8f, 0.8f, 0.8f), new Vector3(0.8f, 0.8f, 0.8f));
                float out = spot.halfWidth() + (kind == Landmark.Kind.RUIN ? trial.halfDepth + 12f * M : kind == Landmark.Kind.SPIRE ? trial.halfWidth : trial.reach) + offset;
                SpherePosition site = road.toSphere(0.0, 0.0, side * out);
                if (Math.abs(Planet.latitude(site)) > MapProjection.CLIP_LATITUDE * 0.9) continue;
                if (world.nationAt(site) != nation) continue;
                boolean crowded = false;
                for (Landmark other : built) crowded |= SphereMath.distance(other.place(), site) < APART;
                if (crowded) continue;
                double facing = SphereFrame.at(site).headingTo(spot.place());
                SphereFrame frame = SphereFrame.at(site, facing - Math.PI * 0.5);
                Landmark lm = new Landmark(kind, frame, nation, seed, world.architecture(nation, site), world.stone(nation), world.wall(nation));
                if (!fits(lm, world, ground, seaLevel, relief)) continue;
                lm.settle(ground, seaLevel);
                lm.name = name(lm, seed);
                lm.caption = caption(lm);
                built.add(lm);
                return true;
            }
        }
        return false;
    }

    /** Whether a landmark's ground is all dry, in its nation, clear of the roads and level enough. */
    private static boolean fits(Landmark lm, World world, ToDoubleFunction<SpherePosition> ground, float seaLevel, float relief) {
        if (world.nearRoad(lm.place(), lm.reach + 2f * M)) return false;
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (int k = 0; k < 24; k++) {
            // (and no river or lake running just past it)
            double a = Math.PI * 2 * k / 24, r = lm.reach + 15f * M;
            SpherePosition near = lm.frame.toSphere(Math.sin(a) * r, 0.0, Math.cos(a) * r);
            if (world.freshWater(near, (float) ground.applyAsDouble(near))) return false;
        }
        for (float[] p : lm.footprint()) {
            SpherePosition at = lm.frame.toSphere(p[0], 0.0, p[1]);
            float h = (float) ground.applyAsDouble(at);
            if (h < seaLevel + 3f * M || world.nationAt(at) != lm.nation || world.freshWater(at, h)) return false;
            low = Math.min(low, h);
            high = Math.max(high, h);
            if (high - low > relief) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the natural ones

    /** What the natural ones need to know about the plants. */
    public interface Plants {
        int count();

        /** A plant's abundance before its range is applied (higher is thicker). */
        float raw(int species, GeodesicChunkGrid.Direction place);

        /** Where on its range's scale a raw abundance comes, 0 to 1. */
        float share(int species, float raw);

        String name(int species);

        Vector3 leaves(int species);

        Vector3 trunk(int species);
    }

    /**
     * Finds the groves, over the land (points spread evenly over it, see SpeciesRange.land):
     * quick, and done before any plant is placed, as the groves thicken them.
     */
    public void findGroves(long seed, List<GeodesicChunkGrid.Direction> land, ToDoubleFunction<SpherePosition> ground, float seaLevel,
                           java.util.function.ToIntFunction<SpherePosition> nationAt, Plants plants) {
        this.seaLevel = seaLevel;
        List<Landmark> found = new ArrayList<>(natural);
        java.util.function.Function<GeodesicChunkGrid.Direction, Float> height = d -> (float) ground.applyAsDouble(SphereMath.atAltitude(d, 0.0));
        // The groves: where two of the plants grow thicker than anywhere else
        if (plants != null && plants.count() > 0) {
            float[] best = new float[plants.count()];
            GeodesicChunkGrid.Direction[] where = new GeodesicChunkGrid.Direction[plants.count()];
            for (int s = 0; s < plants.count(); s++) {
                best[s] = -Float.MAX_VALUE;
                for (int i = 0; i < land.size(); i += 3) {
                    float raw = plants.raw(s, land.get(i));
                    if (raw > best[s] && height.apply(land.get(i)) > seaLevel + 3f * M) {
                        best[s] = raw;
                        where[s] = land.get(i);
                    }
                }
            }
            List<Integer> order = new ArrayList<>();
            for (int s = 0; s < plants.count(); s++) if (where[s] != null) order.add(s);
            // (the most outstanding first: thickest beyond even its heartland)
            order.sort(Comparator.comparingDouble(s -> -plants.share(s, best[s]) - best[s] * 1e-3f));
            int groves = 0;
            for (int s : order) {
                SpherePosition p = SphereMath.atAltitude(where[s], 0.0);
                if (groves >= 2 || !clearOf(found, p)) continue;
                Landmark grove = natural(Landmark.Kind.GROVE, p, nationAt, seed + 10 + s, 900f, ground, seaLevel);
                grove.species = s;
                grove.leaves = plants.leaves(s);
                grove.trunk = plants.trunk(s);
                grove.name = "The " + plants.name(s) + " Grove";
                grove.caption = "Where the " + plants.name(s) + " grows thicker than anywhere else on the planet.";
                found.add(grove);
                groves++;
            }
        }
        natural = List.copyOf(found);
    }

    /**
     * Finds the natural landforms over the land (points spread evenly over it, see
     * SpeciesRange.land): slow-ish, so done once, in the background.
     */
    public void findLandforms(long seed, List<GeodesicChunkGrid.Direction> land, ToDoubleFunction<SpherePosition> ground, float seaLevel,
                              java.util.function.ToIntFunction<SpherePosition> nationAt) {
        this.seaLevel = seaLevel;
        long start = System.currentTimeMillis();
        List<Landmark> found = new ArrayList<>(natural);
        if (land.isEmpty()) return;
        java.util.function.Function<GeodesicChunkGrid.Direction, Float> height = d -> (float) ground.applyAsDouble(SphereMath.atAltitude(d, 0.0));

        // The highest summit: the highest of the land points, climbed to its very top
        GeodesicChunkGrid.Direction top = land.get(0);
        float topHeight = -Float.MAX_VALUE;
        for (GeodesicChunkGrid.Direction d : land) {
            float h = height.apply(d);
            if (h > topHeight) {
                topHeight = h;
                top = d;
            }
        }
        SpherePosition summit = climb(SphereMath.atAltitude(top, 0.0), ground);
        Landmark peak = natural(Landmark.Kind.SUMMIT, summit, nationAt, seed + 1, 9000f, ground, seaLevel);
        float metres = (peak.groundAt(0f, 0f) - seaLevel) / M;
        peak.name = "Mount " + PlanetName.forSeed(seed * 101L + 7L).spelling;
        peak.caption = String.format(java.util.Locale.ROOT, "The planet's highest summit, %,d m above the sea.", Math.round(metres));
        found.add(peak);

        // The coast: land points with the sea near them
        Random rand = new Random(seed * 337L + 11L);
        List<SpherePosition> coast = new ArrayList<>();
        for (int i = 0; i < land.size(); i += 2) {
            SpherePosition p = SphereMath.atAltitude(land.get(i), 0.0);
            if (Math.abs(Planet.latitude(p)) > MapProjection.CLIP_LATITUDE * 0.85) continue;
            SphereFrame f = SphereFrame.at(p);
            for (int k = 0; k < 6; k++) {
                double a = Math.PI * 2 * k / 6;
                if (ground.applyAsDouble(f.toSphere(Math.cos(a) * 900.0, 0.0, Math.sin(a) * 900.0)) < seaLevel) {
                    coast.add(p);
                    break;
                }
            }
        }
        java.util.Collections.shuffle(coast, rand);
        if (coast.size() > 260) coast = coast.subList(0, 260);

        // The most sheltered bay: open water closed in by land on every side but its mouth
        Landmark bay = null;
        float bestShelter = 0f;
        for (SpherePosition p : coast) {
            SphereFrame f = SphereFrame.at(p);
            for (int k = 0; k < 4; k++) {
                double a = Math.PI * 2 * k / 4 + 0.4;
                SpherePosition water = f.toSphere(Math.cos(a) * 1500.0, 0.0, Math.sin(a) * 1500.0);
                if (ground.applyAsDouble(water) > seaLevel - 2f * M) continue;
                float shelter = shelter(water, ground, seaLevel);
                if (shelter > bestShelter && clearOf(found, water)) {
                    bestShelter = shelter;
                    bay = natural(Landmark.Kind.BAY, water, nationAt, seed + 2, 11000f, ground, seaLevel);
                }
            }
        }
        if (bay != null && bestShelter >= 0.55f) {
            bay.name = PlanetName.forSeed(seed * 211L + 3L).spelling + " Bay";
            bay.caption = String.format(java.util.Locale.ROOT, "A natural harbour, %d%% ringed by land.", Math.round(bestShelter * 100f));
            found.add(bay);
        }

        // The finest spit of sand: low land, the sea close on both sides, running on either way
        Landmark spit = null;
        float bestSpit = 0f;
        for (SpherePosition p : coast) {
            float h = (float) ground.applyAsDouble(p);
            if (h > seaLevel + 6f * M) continue;
            SphereFrame f = SphereFrame.at(p);
            for (int k = 0; k < 4; k++) {
                double a = Math.PI * k / 4;
                double ca = Math.cos(a), sa = Math.sin(a);
                // (sea both ways across it, near...)
                float across = 0f;
                for (float d = 150f; d <= 900f; d += 150f) {
                    boolean left = ground.applyAsDouble(f.toSphere(-sa * d, 0.0, ca * d)) < seaLevel;
                    boolean right = ground.applyAsDouble(f.toSphere(sa * d, 0.0, -ca * d)) < seaLevel;
                    if (left && right) {
                        across = d;
                        break;
                    }
                }
                if (across == 0f) continue;
                // (...and low land running on along it)
                float run = 0f;
                for (float d = 200f; d <= 2400f; d += 200f) {
                    float ahead = (float) ground.applyAsDouble(f.toSphere(ca * d, 0.0, sa * d));
                    if (ahead < seaLevel || ahead > seaLevel + 12f * M) break;
                    run = d;
                }
                float score = run / across;
                if (run >= 600f && score > bestSpit && clearOf(found, p)) {
                    bestSpit = score;
                    spit = natural(Landmark.Kind.SPIT, p, nationAt, seed + 3, 4200f, ground, seaLevel);
                }
            }
        }
        if (spit != null) {
            spit.name = PlanetName.forSeed(seed * 307L + 5L).spelling + " Sands";
            spit.caption = "A long ribbon of sand with the sea on either side.";
            found.add(spit);
        }

        natural = List.copyOf(found);
        System.out.printf("[LANDMARKS] %d natural landmarks found in %d ms%n", found.size(), System.currentTimeMillis() - start);
        for (Landmark lm : found) System.out.printf(java.util.Locale.ROOT, "[LANDMARKS] %s at %s%n", lm.name, lm.place());
    }

    private static Landmark natural(Landmark.Kind kind, SpherePosition place, java.util.function.ToIntFunction<SpherePosition> nationAt,
                                    long seed, float span, ToDoubleFunction<SpherePosition> ground, float seaLevel) {
        Landmark lm = new Landmark(kind, place, nationAt.applyAsInt(place), seed, span);
        lm.ground = ground;
        lm.seaLevel = seaLevel;
        lm.base = seaLevel;
        return lm;
    }

    /** Whether a place is well away from every landmark found so far. */
    private boolean clearOf(List<Landmark> found, SpherePosition place) {
        for (Landmark lm : found) if (SphereMath.distance(lm.place(), place) < APART * 2) return false;
        for (Landmark lm : built) if (SphereMath.distance(lm.place(), place) < APART) return false;
        return true;
    }

    /** Uphill from a place, in ever shorter steps, to the top of its hill. */
    private static SpherePosition climb(SpherePosition from, ToDoubleFunction<SpherePosition> ground) {
        SpherePosition here = from;
        double h = ground.applyAsDouble(here);
        for (double step = 1600.0; step >= 6.0; ) {
            SphereFrame f = SphereFrame.at(here);
            SpherePosition better = null;
            for (int k = 0; k < 8; k++) {
                double a = Math.PI * 2 * k / 8;
                SpherePosition p = f.toSphere(Math.cos(a) * step, 0.0, Math.sin(a) * step);
                double ph = ground.applyAsDouble(p);
                if (ph > h) {
                    h = ph;
                    better = p;
                }
            }
            if (better == null) step *= 0.5;
            else here = better;
        }
        return here;
    }

    /** How closed in by land a stretch of water is: the share of 24 ways out from it that reach land within a few kilometres. */
    private static float shelter(SpherePosition water, ToDoubleFunction<SpherePosition> ground, float seaLevel) {
        SphereFrame f = SphereFrame.at(water);
        int closed = 0, ways = 24, open = 0, longestOpen = 0;
        boolean[] shut = new boolean[ways];
        for (int k = 0; k < ways; k++) {
            double a = Math.PI * 2 * k / ways;
            for (double d = 400.0; d <= 5200.0; d += 400.0) {
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

    // ------------------------------------------------------------------ names and captions

    private static String name(Landmark lm, long seed) {
        String word = PlanetName.forSeed(seed).spelling;
        return switch (lm.kind) {
            case SPIRE -> "The " + word + " Spire";
            case STONES -> "The " + word + " Monoliths";
            case RUIN -> "The Ruins of " + word;
            default -> word;
        };
    }

    private static String caption(Landmark lm) {
        return switch (lm.kind) {
            case SPIRE -> String.format(java.util.Locale.ROOT, "%s, soaring %d m over the city, far above its tallest towers.", lm.looks, Math.round(lm.height / M));
            case STONES -> lm.looks + (lm.looks2.isEmpty() ? "" : ", " + lm.looks2) + ", raised by hands long forgotten.";
            case RUIN -> lm.looks + ", left by a people long gone.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ queries

    /** Draws every landmark's picture in the world's own colours. */
    public void setLook(Landmark.Look look) {
        for (Landmark lm : all()) lm.look = look;
    }

    /** Every landmark: the built ones, then the natural ones once found. */
    public List<Landmark> all() {
        List<Landmark> list = new ArrayList<>(built);
        list.addAll(natural);
        return list;
    }

    /** The built ones drawn with a batch cell. */
    public List<Landmark> inCell(long cell) {
        List<Integer> ids = byCell.get(cell);
        if (ids == null) return List.of();
        List<Landmark> list = new ArrayList<>(ids.size());
        for (int i : ids) list.add(built.get(i));
        return list;
    }

    /** A built one by its number (see part). */
    public Landmark get(int i) {
        return built.get(i);
    }

    public int indexOf(Landmark lm) {
        return built.indexOf(lm);
    }

    /** Whether a place is within clearance of a built one's ground. */
    public boolean covers(SpherePosition place, float clearance) {
        if (built.isEmpty()) return false;
        SpherePosition at = SphereMath.atAltitude(place, 0.0);
        for (Landmark lm : index.near(at, clearance)) {
            if (SphereMath.distance(lm.place(), at) <= lm.reach + clearance) return true;
        }
        return false;
    }

    /** The height above the datum of a built one's floor (its steps and platform) at a place; NaN if none is there. */
    public float floorAt(SpherePosition place) {
        if (built.isEmpty()) return Float.NaN;
        SpherePosition at = SphereMath.atAltitude(place, 0.0);
        for (Landmark lm : index.near(at, 0.0)) {
            if (lm.floors.isEmpty()) continue;
            double[] local = lm.frame.fromSphere(at);
            float floor = lm.floorAt((float) local[0], (float) local[2]);
            if (!Float.isNaN(floor)) return floor;
        }
        return Float.NaN;
    }

    /** Whether a plant's grove comes within reach of a place. */
    public boolean groveNear(int species, SpherePosition place, double reach) {
        for (Landmark lm : natural) {
            if (lm.kind == Landmark.Kind.GROVE && lm.species == species && SphereMath.distance(lm.place(), SphereMath.atAltitude(place, 0.0)) < lm.reach + reach) return true;
        }
        return false;
    }

    /**
     * How much a grove thickens its plant at a place: 1 in its middle, fading to 0 at its edge
     * (and beyond, or for another plant).
     */
    public float groveBoost(int species, SpherePosition place) {
        for (Landmark lm : natural) {
            if (lm.kind != Landmark.Kind.GROVE || lm.species != species) continue;
            double d = SphereMath.distance(lm.place(), SphereMath.atAltitude(place, 0.0));
            if (d < lm.reach) return (float) (1.0 - d / lm.reach);
        }
        return 0f;
    }
}
