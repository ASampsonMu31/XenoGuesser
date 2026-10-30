import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where a plant or animal species is common, as a scale from 0 (outside its range) to 1 (its
 * heartland), and how many of it there are along that scale. Spawning and the species' heatmap
 * both use it, so the map shows exactly where they are.
 *
 * The scale is set from where the species' population actually is (its "raw" abundance, as the
 * world's climate, water, patches and home region make it, summed over the land): 0 where the
 * poorest LOW_SHARE of its population lives, 1 from where its richest (1 - HIGH_SHARE) begins.
 * A straight line from the lowest raw value anywhere to the highest would be thrown off by a few
 * hot spots, painting most of the range red while most of the species lives there.
 *
 * How many there are rises steeply along the scale, peak * scale^POWER: the heartland (green on
 * the map, above two thirds) has most of them, PEAK_BOOST times as many as it had before these
 * ranges; the red third has under 4% of the peak, so few stragglers. The population's own spread
 * decides the scale, so the area where a species is concentrated stays where it was; it thins
 * out beyond. The shares are set so a species' heartland and the reach round it cover a fair
 * area, rather than a few hot spots a player could hardly find.
 *
 * Worked out once per world from the same land points on every PC, so both players in
 * multiplayer have the same.
 */
public final class SpeciesRange {

    public static final float POWER = 3f, PEAK_BOOST = 3f;
    public static final float LOW_SHARE = 0.06f, HIGH_SHARE = 0.82f;
    // About how many points over the land the scale is measured from
    public static final int SAMPLES = 12_000;

    /** The raw abundance at the scale's 0 and 1, and the density (per chunk's area) at 1. */
    public final float low, high, peak;

    private SpeciesRange(float low, float high, float peak) {
        this.low = low;
        this.high = high;
        this.peak = peak;
    }

    /**
     * The scale from a species' raw abundance (per chunk's area) at many points over the land;
     * its peak density no more than cap.
     */
    public static SpeciesRange of(float[] raw, float cap) {
        float[] sorted = raw.clone();
        for (int i = 0; i < sorted.length; i++) sorted[i] = Math.max(0f, sorted[i]);
        Arrays.sort(sorted);
        double total = 0;
        for (float v : sorted) total += v;
        if (total <= 0 || sorted.length == 0) return new SpeciesRange(0f, 1f, 0f);
        float low = sorted[0], high = sorted[sorted.length - 1];
        double running = 0;
        boolean lowFound = false;
        for (float v : sorted) {
            running += v;
            if (!lowFound && running >= total * LOW_SHARE) {
                low = v;
                lowFound = true;
            }
            if (running >= total * HIGH_SHARE) {
                high = v;
                break;
            }
        }
        if (high <= low) high = low + 1e-6f;
        // The peak: enough that its heartland (the top third of the scale) holds PEAK_BOOST times
        // as many as it did before (when the raw abundance was spawned as it was)
        double before = 0, shape = 0;
        for (float v : sorted) {
            float t = Math.max(0f, Math.min(1f, (v - low) / (high - low)));
            if (t > 2f / 3f) {
                before += v;
                shape += Math.pow(t, POWER);
            }
        }
        float peak = shape > 0 ? (float) (PEAK_BOOST * before / shape) : high * PEAK_BOOST;
        return new SpeciesRange(low, high, Math.min(cap, peak));
    }

    // A species is there to be found if at least this share of the land is out of its red (a
    // third of the way up its scale) and its peak at least MIN_PEAK a chunk; one that isn't has
    // its range drawn again (see OrganismManager.measureRanges and the plants' ranges)
    public static final float MIN_LAND_SHARE = 0.02f, MIN_PEAK = 0.004f;
    // How many times a range is drawn again at most before it's taken as it is
    public static final int MOST_REDRAWS = 24;

    /** Whether a species with this range and raw abundance over the land points is there to be found (see MIN_LAND_SHARE). */
    public boolean findable(float[] raw) {
        if (peak < MIN_PEAK || raw.length == 0) return false;
        int out = 0;
        for (float v : raw) if (share(v) > 1f / 3f) out++;
        return out >= raw.length * MIN_LAND_SHARE;
    }

    /** Where a raw abundance comes on the scale, 0 to 1 (what the heatmap colours by). */
    public float share(float raw) {
        return Math.max(0f, Math.min(1f, (raw - low) / (high - low)));
    }

    /** The density as a share of the peak for a raw abundance: the chance a candidate there is kept. */
    public float keep(float raw) {
        float t = share(raw);
        return t * t * t;
    }

    /** How many are expected in a chunk's area at a raw abundance. */
    public float density(float raw) {
        return peak * keep(raw);
    }

    /** How many are expected in a chunk's area at a point on the scale (for the heatmap's key). */
    public float densityAtShare(float t) {
        return peak * (float) Math.pow(Math.max(0f, Math.min(1f, t)), POWER);
    }

    /** Points over the land (between the map's top and bottom), evenly spread, the same on every PC. */
    public static List<GeodesicChunkGrid.Direction> land(PerlinNoise terrain, float seaLevel) {
        List<GeodesicChunkGrid.Direction> points = new ArrayList<>();
        // (every land point of these, north to south: about SAMPLES with the usual land share)
        int count = SAMPLES * 3;
        double golden = Math.PI * (3 - Math.sqrt(5)), top = Math.sin(MapProjection.CLIP_LATITUDE);
        for (int i = 0; i < count; i++) {
            double y = top * (1 - 2 * (i + 0.5) / count), across = Math.sqrt(1 - y * y), longitude = golden * i;
            GeodesicChunkGrid.Direction d = new GeodesicChunkGrid.Direction(across * Math.cos(longitude), y, -across * Math.sin(longitude));
            if (TerrainMesh.getLayeredHeight(d, terrain) > seaLevel) points.add(d);
        }
        return points;
    }
}
