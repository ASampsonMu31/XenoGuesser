/**
 * The map: a Mercator chart of the planet, cut off at CLIP_LATITUDE north and south. Map x runs
 * east with longitude across the map's width (the planet's circumference, Planet.width) and map
 * z runs south, both in world units from the map's middle; a map image's (u, v) is
 * (x / width + 1/2, z / width + 1/2). The chart's join (its east and west edges, the same
 * meridian) is put where it crosses the least land (chooseJoin).
 * <p>
 * The one conversion pair between the world and the map is toMap (a place or direction to its
 * map point) and direction (a map point to the direction from the planet's centre). Only the
 * map (BirdsEyeMaps, MapPanel, MapTiles and what draws for them), guess placement and the
 * nation map (the authority for borders, see D12 in GEODESIC_MIGRATION_PLAN.txt) use them:
 * nothing in the live world is laid out on the chart.
 */
public final class MapProjection {

    /** The latitude where the map is cut off, north and south. */
    public static final double CLIP_LATITUDE = Math.toRadians(75.0);

    /** The longitude at the map's middle (where map x is 0); see chooseJoin. */
    private static double centreLongitude = 0.0;

    private MapProjection() {
    }

    // ------------------------------------------------------------------ the conversion pair

    /** The map point {x, z} of a direction from the planet's centre (or of a place: its height makes no difference). */
    public static double[] toMap(GeodesicChunkGrid.Direction direction) {
        double lat = Math.asin(Math.max(-1.0, Math.min(1.0, direction.y())));
        double lon = Math.atan2(-direction.z(), direction.x());
        return new double[] { x(lon), z(lat) };
    }

    /** The map point {x, z} of a place. */
    public static double[] toMap(SpherePosition place) {
        return toMap(SphereMath.direction(place));
    }

    /** The direction from the planet's centre to map point (x, z). */
    public static GeodesicChunkGrid.Direction direction(double x, double z) {
        double lat = latitude(z), lon = longitude(x), cosLat = Math.cos(lat);
        return new GeodesicChunkGrid.Direction(cosLat * Math.cos(lon), Math.sin(lat), -cosLat * Math.sin(lon));
    }

    // ------------------------------------------------------------------ for the map's own drawing

    /** The place at map point (x, z), at an altitude above the datum. */
    public static SpherePosition place(double x, double z, double altitude) {
        return SphereMath.atAltitude(direction(x, z), altitude);
    }

    /** The place on the datum under map point (x, z), {x, y, z} in world units (for sampling noise there). */
    public static float[] surface(double x, double z) {
        GeodesicChunkGrid.Direction d = direction(x, z);
        double r = Planet.radius();
        return new float[] { (float) (d.x() * r), (float) (d.y() * r), (float) (d.z() * r) };
    }

    /** The latitude (radians) at map z. */
    public static double latitude(double z) {
        return 2 * Math.atan(Math.exp(-z / Planet.radius())) - Math.PI / 2;
    }

    /** The longitude (radians) at map x. */
    public static double longitude(double x) {
        return x / Planet.radius() + centreLongitude;
    }

    /** Map z at a latitude (radians). */
    public static double z(double latitude) {
        return -Planet.radius() * Math.log(Math.tan(Math.PI / 4 + latitude / 2));
    }

    /** Map x at a longitude (radians), on the map (-width/2 to width/2). */
    public static double x(double longitude) {
        return wrapX((longitude - centreLongitude) * Planet.radius());
    }

    /** x brought back onto the map, -width/2 to width/2. */
    public static double wrapX(double x) {
        double w = Planet.width();
        return x - w * Math.floor((x + w / 2) / w);
    }

    /** How far the map reaches north and south of the equator, in map units. */
    public static float clipHalfHeight() {
        return (float) -z(CLIP_LATITUDE);
    }

    /** The map's height over its width. */
    public static float aspect() {
        return 2f * clipHalfHeight() / Planet.width();
    }

    /** The longitude at the map's middle. */
    public static double centreLongitude() {
        return centreLongitude;
    }

    /**
     * Puts the map's join on the meridian, of a few hundred tried, that crosses the least land
     * between the clipped latitudes, so it mostly runs through the sea. Land is ground above
     * seaLevel by height(direction). Returns the fraction of that meridian on land.
     */
    public static float chooseJoin(java.util.function.ToDoubleFunction<GeodesicChunkGrid.Direction> height, float seaLevel) {
        int meridians = 360, samples = 160;
        int[] land = new int[meridians];
        for (int m = 0; m < meridians; m++) {
            double lon = (m + 0.5) / meridians * 2 * Math.PI - Math.PI;
            for (int k = 0; k < samples; k++) {
                // Evenly in latitude, so the stretched far north and south don't count extra
                double lat = -CLIP_LATITUDE + (k + 0.5) / samples * 2 * CLIP_LATITUDE;
                GeodesicChunkGrid.Direction d = SphereMath.direction(Planet.position(lat, lon, 0.0));
                if (height.applyAsDouble(d) > seaLevel) land[m]++;
            }
        }
        int best = 0;
        for (int m = 1; m < meridians; m++) {
            // A little smoothing, so a narrow gap in a big landmass isn't chosen over open sea
            int here = land[m] * 2 + land[(m + 1) % meridians] + land[(m + meridians - 1) % meridians];
            int then = land[best] * 2 + land[(best + 1) % meridians] + land[(best + meridians - 1) % meridians];
            if (here < then) best = m;
        }
        double joinLongitude = (best + 0.5) / meridians * 2 * Math.PI - Math.PI;
        // The join sits at the map's edges, half the way round from its middle
        centreLongitude = Math.atan2(Math.sin(joinLongitude + Math.PI), Math.cos(joinLongitude + Math.PI));
        return land[best] / (float) samples;
    }
}
