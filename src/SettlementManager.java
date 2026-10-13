import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides where settlements sit and how urban every point of the planet is.
 * Sites are chosen by habitability (close to water, low lying, not hot and
 * dry), ranked so the best sites tend to become the largest cities, and each
 * settlement spreads a Gaussian bump of urbanness around its centre, measured
 * along the surface (great-circle distance), so a town is the same size at the
 * equator as at 70 degrees. Everything outside those bumps is countryside.
 * <p>
 * Lookups go by direction: the settlements whose influence reaches each geodesic
 * cell (LOOKUP_LEVEL) are listed once, and urbanness is worked out from them exactly.
 */
public class SettlementManager {

    public static final class Settlement {
        /** The middle of the settlement, on the ground. */
        public final SpherePosition centre;
        public final GeodesicChunkGrid.Direction direction;
        /** 0 is the largest settlement on the planet. */
        public final int rank;
        /** Gaussian sigma of the urban footprint, in world units along the surface. */
        public final float radius;
        /** Urbanness at the centre, between 0 and 1. */
        public final float intensity;

        private Settlement(SpherePosition centre, int rank, float radius, float intensity) {
            this.centre = centre;
            this.direction = SphereMath.direction(centre);
            this.rank = rank;
            this.radius = radius;
            this.intensity = intensity;
        }

        public float influenceAt(GeodesicChunkGrid.Direction place) {
            double d = SphereMath.angle(direction, place) * Planet.radius();
            return intensity * (float) Math.exp(-(d * d) / (2.0 * radius * radius));
        }

        /** Distance from the centre at which this settlement's own urbanness falls to the given level. */
        public float radiusAtUrbanness(float urbanness) {
            if (urbanness >= intensity) {
                return 0.0f;
            }
            return radius * (float) Math.sqrt(2.0 * Math.log(intensity / urbanness));
        }
    }

        // A few cities, many towns and a long tail of small villages
    private static final int SETTLEMENT_COUNT = 340;
    private static final float SMALLEST_RADIUS = 190.0f;
    private static final int PLACEMENT_DRAWS = 600;
    private static final int CANDIDATE_SAMPLES = 14000;
    private static final float LARGEST_RADIUS = 3200.0f;
        private static final float RADIUS_RANK_FALLOFF = 0.5f;
    // Settlements keep this many (summed) sigmas apart so they read as separate places
    private static final float SPACING_FACTOR = 2.1f;
    private static final float SITE_PREFERENCE_POWER = 2.0f;
    private static final float INFLUENCE_CUTOFF_SIGMAS = 3.5f;
    // Towns are founded no further than this from the equator (the map stops at 75 degrees)
    private static final double FOUNDING_LATITUDE = Math.toRadians(73.0);

    // The settlements reaching each cell of this level are listed for lookups
    private static final int LOOKUP_LEVEL = 5;
    // Habitability is kept per cell of this level (about 200 world units across)
    private static final int HABITABILITY_LEVEL = 7;

    // Habitability shaping
    // (world units from the sea)
    private static final float COAST_DISTANCE = 1500.0f;
    private static final float MOISTURE_DISTANCE = 9000.0f;
    private static final float HOT_TEMPERATURE_START = 0.6f;
    private static final float HOT_TEMPERATURE_FULL = 0.97f;
    private static final float HIGHLAND_START = 250.0f;
    private static final float HIGHLAND_FULL = 900.0f;
    // How much of the habitability each harsh condition takes away at its worst
    private static final float HEAT_PENALTY = 0.55f;
    private static final float DESERT_PENALTY = 0.6f;
    private static final float HIGHLAND_PENALTY = 0.85f;
    private static final float RUGGED_PENALTY = 0.95f;
    // Where the terrain turns to ridged mountains (see TerrainMesh)
    private static final float MOUNTAIN_START = 140.0f;
    private static final float MOUNTAIN_FULL = 450.0f;

    private final float seaLevelHeight;
    private final PerlinNoise terrainNoise;
        private final RegionalGenerationManager regionalManager;
    private final NationGenerationManager nations;
    // A settlement's whole built-up area, out to this many sigmas, must lie in one nation
    private static final float ONE_NATION_SIGMAS = 2.2f;

    private final List<Settlement> settlements;
    private final GeodesicChunkGrid lookupGrid = new GeodesicChunkGrid(LOOKUP_LEVEL);
    // For each lookup cell (by id), the settlements whose influence reaches into it
    private final Settlement[][] reaching;
    private final GeodesicChunkGrid habitabilityGrid = new GeodesicChunkGrid(HABITABILITY_LEVEL);
    private final Map<Long, Float> habitabilityCache = new ConcurrentHashMap<>();

        public SettlementManager(long seed, float seaLevelHeight,
                             PerlinNoise terrainNoise, RegionalGenerationManager regionalManager,
                             NationGenerationManager nations) {
        this.nations = nations;
        this.seaLevelHeight = seaLevelHeight;
        this.terrainNoise = terrainNoise;
        this.regionalManager = regionalManager;

        this.settlements = Collections.unmodifiableList(placeSettlements(new Random(seed + 24680L)));

        List<List<Settlement>> lists = new ArrayList<>();
        for (long id = 0; id < lookupGrid.cellCount(); id++) lists.add(null);
        for (Settlement settlement : settlements) {
            double reach = settlement.radius * INFLUENCE_CUTOFF_SIGMAS / Planet.radius();
            for (GeodesicChunkGrid.Cell cell : lookupGrid.cellsWithin(settlement.direction, reach)) {
                int id = (int) cell.id();
                if (lists.get(id) == null) lists.set(id, new ArrayList<>(2));
                lists.get(id).add(settlement);
            }
        }
        this.reaching = new Settlement[lists.size()][];
        for (int id = 0; id < lists.size(); id++) {
            reaching[id] = lists.get(id) == null ? new Settlement[0] : lists.get(id).toArray(new Settlement[0]);
        }

        System.out.printf("[SETTLEMENTS] Placed %d settlements (largest radius %.0f)%n",
                settlements.size(), settlements.isEmpty() ? 0.0f : settlements.get(0).radius);
    }

    public List<Settlement> getSettlements() {
        return settlements;
    }

    /** 0 for open countryside up to 1 in the heart of a large city. Overlapping settlements combine as a probabilistic union. */
    public float getUrbanness(GeodesicChunkGrid.Direction place) {
        float open = 1.0f;
        for (Settlement settlement : reachingAt(place)) {
            open *= 1.0f - settlement.influenceAt(place);
        }
        return 1.0f - open;
    }

    public float getUrbanness(SpherePosition place) {
        return getUrbanness(SphereMath.direction(place));
    }

    /**
     * How big a city a place is in: near 1 in the largest city on the planet, smaller for
     * smaller towns, fading to 0 out in the country.
     */
    public float getCitySize(GeodesicChunkGrid.Direction place) {
        float size = 0.0f;
        for (Settlement settlement : reachingAt(place)) {
            size = Math.max(size, settlement.radius / LARGEST_RADIUS * settlement.influenceAt(place) / settlement.intensity);
        }
        return size;
    }

    /** How suitable the land is for people to live on, 0 (sea, hot desert, high mountains) to 1. */
    public float getHabitability(SpherePosition place) {
        GeodesicChunkGrid.Cell cell = habitabilityGrid.cellAt(place);
        return habitabilityCache.computeIfAbsent(cell.id(), id -> computeHabitability(SphereMath.atAltitude(cell.centre(), 0.0)));
    }

    /** The settlement contributing the most urbanness at a place, or null if none reaches it. */
    public Settlement dominantSettlementAt(GeodesicChunkGrid.Direction place) {
        Settlement best = null;
        float bestInfluence = 0.0f;
        for (Settlement settlement : reachingAt(place)) {
            float influence = settlement.influenceAt(place);
            if (influence > bestInfluence) {
                bestInfluence = influence;
                best = settlement;
            }
        }
        return best;
    }

    public Settlement dominantSettlementAt(SpherePosition place) {
        return dominantSettlementAt(SphereMath.direction(place));
    }

    private Settlement[] reachingAt(GeodesicChunkGrid.Direction place) {
        return reaching[(int) lookupGrid.cellAt(place).id()];
    }

    // ------------------------------------------------------------------ placing

    private float computeHabitability(SpherePosition place) {
        float height = TerrainMesh.getLayeredHeight(place, terrainNoise);
        if (height <= seaLevelHeight) {
            return 0.0f;
        }

        float waterDistance = regionalManager.distanceToWater(place);
        float coast = gaussianFalloff(waterDistance, COAST_DISTANCE);
        float moisture = gaussianFalloff(waterDistance, MOISTURE_DISTANCE);

        float temperature = regionalManager.temperatureMap.evaluateAt(place);
        float heat = smoothstep(HOT_TEMPERATURE_START, HOT_TEMPERATURE_FULL, temperature);
        // Deserts are where it is both hot and far from water
        float desert = heat * (1.0f - moisture);
        float highland = smoothstep(HIGHLAND_START, HIGHLAND_FULL, height - seaLevelHeight);

        // Rugged, mountainous ground: steep slopes nearby, or the heights where the terrain
        // breaks into ridges, are hardly settled at all
        SphereFrame frame = SphereFrame.at(place);
        float step = 200.0f;
        float slope = slopeAt(frame, step);
        // ...and closer in, so a steep hillside counts even on a gentle range
        float localSlope = slopeAt(frame, 60.0f);
        float rugged = Math.max(Math.max(smoothstep(0.05f, 0.22f, slope), smoothstep(0.08f, 0.3f, localSlope)),
                smoothstep(MOUNTAIN_START, MOUNTAIN_FULL, height));

        float waterAppeal = 0.2f + 0.5f * coast + 0.3f * moisture;
        return waterAppeal
                * (1.0f - HEAT_PENALTY * heat)
                * (1.0f - DESERT_PENALTY * desert)
                * (1.0f - HIGHLAND_PENALTY * highland)
                * (1.0f - RUGGED_PENALTY * rugged) * (1.0f - RUGGED_PENALTY * rugged);
    }

    /** The ground's slope (rise over run) at a frame's origin, from heights step either way east and south. */
    private float slopeAt(SphereFrame frame, float step) {
        float dx = heightAt(frame, step, 0f) - heightAt(frame, -step, 0f);
        float dz = heightAt(frame, 0f, step) - heightAt(frame, 0f, -step);
        return (float) Math.hypot(dx, dz) / (2.0f * step);
    }

    private float heightAt(SphereFrame frame, float x, float z) {
        return TerrainMesh.getLayeredHeight(frame.toSphere(x, 0.0, z), terrainNoise);
    }

    /**
     * Ranks are handed out in selection order, so the first (largest)
     * settlements claim the most habitable sites and force the widest spacing.
     */
    private List<Settlement> placeSettlements(Random rand) {
        // Candidate sites spread evenly over the planet's surface, so no latitude is favoured
        List<SpherePosition> sites = new ArrayList<>();
        List<Float> appeal = new ArrayList<>();
        for (int i = 0; i < CANDIDATE_SAMPLES; i++) {
            SpherePosition place = SphereMath.randomPlace(rand, FOUNDING_LATITUDE);
            float habitability = computeHabitability(place);
            if (habitability > 0.02f) {
                sites.add(TerrainMesh.groundAt(place, terrainNoise));
                appeal.add((float) Math.pow(habitability, SITE_PREFERENCE_POWER));
            }
        }

        // Sites are drawn at random in proportion to how appealing they are, rejecting any too
        // close to an existing settlement; scanning every candidate for every settlement
        // grew too slow once there were hundreds of villages
        float[] cumulative = new float[sites.size()];
        float running = 0.0f;
        for (int c = 0; c < sites.size(); c++) {
            running += appeal.get(c);
            cumulative[c] = running;
        }
        List<Settlement> placed = new ArrayList<>();
        boolean[] used = new boolean[sites.size()];
        if (sites.isEmpty()) {
            return placed;
        }

        for (int rank = 0; rank < SETTLEMENT_COUNT; rank++) {
            float radius = Math.max(SMALLEST_RADIUS, LARGEST_RADIUS * (float) Math.pow(rank + 1, -RADIUS_RANK_FALLOFF));
            float intensity = 0.55f + 0.45f * (float) Math.pow(rank + 1, -0.35);

            int chosen = -1;
            for (int draw = 0; draw < PLACEMENT_DRAWS && chosen < 0; draw++) {
                float pick = rand.nextFloat() * running;
                int lo = 0, hi = cumulative.length - 1;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (cumulative[mid] < pick) lo = mid + 1; else hi = mid;
                }
                SpherePosition site = sites.get(lo);
                                if (!used[lo] && isClearOfSettlements(site, radius, placed)
                        && liesWithinOneNation(site, radius)) {
                    chosen = lo;
                }
            }
            if (chosen < 0) {
                continue;
            }
            used[chosen] = true;
            placed.add(new Settlement(sites.get(chosen), placed.size(), radius, intensity));
        }
        return placed;
    }

        /**
     * True if every bit of land the settlement would build on belongs to the nation at its
     * centre, so cities never straddle a border. Sea inside the footprint doesn't matter.
     */
    private boolean liesWithinOneNation(SpherePosition centre, float radius) {
        int home = nations.getNationAt(centre);
        SphereFrame frame = SphereFrame.at(centre);
        float reach = radius * ONE_NATION_SIGMAS;
        int rings = 4;
        for (int ring = 1; ring <= rings; ring++) {
            float r = reach * ring / rings;
            int spokes = 8 * ring;
            for (int s = 0; s < spokes; s++) {
                double angle = (s + 0.5 * (ring & 1)) * Math.PI * 2.0 / spokes;
                SpherePosition p = frame.toSphere(r * Math.cos(angle), 0.0, r * Math.sin(angle));
                if (nations.getNationAt(p) != home
                        && TerrainMesh.getLayeredHeight(p, terrainNoise) > seaLevelHeight) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isClearOfSettlements(SpherePosition site, float radius, List<Settlement> placed) {
        for (Settlement other : placed) {
            float spacing = SPACING_FACTOR * (radius + other.radius);
            if (SphereMath.distance(site, other.centre) < spacing) {
                return false;
            }
        }
        return true;
    }

    private static float gaussianFalloff(float distance, float scale) {
        float ratio = distance / scale;
        return (float) Math.exp(-ratio * ratio);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }
}
