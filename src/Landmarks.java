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
 * natural ones are found from the land once it's known: the planet's highest summit and the
 * groves where two of its plants grow thickest; and where the land was shaped for them (see
 * Landforms), its most sheltered bay with its stacks of rock, a spit of sand built out into the
 * shallows, and fields of steep pointed hills.
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
     * Finds the natural landforms: the highest summit over the land (points spread evenly over
     * it, see SpeciesRange.land), and the landforms the land was shaped for (see Landforms), each
     * where it was shaped.
     */
    public void findLandforms(long seed, List<GeodesicChunkGrid.Direction> land, ToDoubleFunction<SpherePosition> ground, float seaLevel,
                              java.util.function.ToIntFunction<SpherePosition> nationAt, Landforms shaped) {
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

        // The bay with its stacks, the spit and the fields of pinnacles, where the land was shaped for them
        int fields = 0;
        for (Landforms.Site site : shaped == null ? List.<Landforms.Site>of() : shaped.sites) {
            Landmark lm = natural(site.kind(), site.place(), nationAt, seed + 2 + found.size(), site.size() * 2.4f, ground, seaLevel);
            lm.count = site.count();
            lm.way = site.heading();
            switch (site.kind()) {
                case BAY -> {
                    lm.name = PlanetName.forSeed(seed * 211L + 3L).spelling + " Bay";
                    lm.caption = String.format(java.util.Locale.ROOT, "A natural harbour, %d%% ringed by land, %d stacks of rock standing sheer from its water.",
                            Math.round(site.measure() * 100f), site.count());
                }
                case SPIT -> {
                    lm.name = PlanetName.forSeed(seed * 307L + 5L).spelling + " Sands";
                    lm.caption = String.format(java.util.Locale.ROOT, "A ribbon of sand %d m long, the sea on either side, hooking round at its tip.",
                            Math.round(site.size() / M));
                }
                case PINNACLES -> {
                    String word = PlanetName.forSeed(seed * 401L + 13L + fields++ * 7919L).spelling;
                    boolean bySea = site.measure() > 0.5f;
                    lm.name = "The " + word + (bySea ? " Needles" : " Pinnacles");
                    lm.caption = bySea ? String.format(java.util.Locale.ROOT, "%d steep spires of rock rising from the shore and out of the sea.", site.count())
                            : String.format(java.util.Locale.ROOT, "%d steep pointed hills rising sheer out of a level plain.", site.count());
                }
                default -> {
                }
            }
            found.add(lm);
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
