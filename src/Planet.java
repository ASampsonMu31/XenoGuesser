/**
 * The world is a sphere, and the live world is built on it directly (SpherePosition,
 * SphereFrame, SphereMath, the geodesic grid; see GEODESIC_MIGRATION_PLAN.txt). This class
 * holds the planet's size, the one definition of its axes (position, latitude, longitude), the
 * sun and the celestial pole, and the Mercator chart: x running east with longitude across
 * the chart's WIDTH and z running south, cut off at 75 degrees north and south. The chart is
 * for the map and for turning a map click into a place; systems not yet moved onto the sphere
 * (buildings and roads, people and vehicles) still lay themselves out on it, and are lifted
 * onto the sphere until they are ported.
 * <p>
 * Everything about the land (its height, climate, plants, rocks, nations) is worked out from
 * places on the sphere rather than on the chart, so the map and the world agree, and the
 * chart's east and west edges, being the same place, match exactly.
 */
public final class Planet {

    /** The chart's side, in world units; the sphere's circumference at the equator. */
    private static float width = 150_000f;
    private static double radius = width / (2 * Math.PI);
    /** The latitude where the chart is cut off, north and south. */
    public static final double CLIP_LATITUDE = Math.toRadians(75.0);

    private Planet() {
    }

    public static void setWidth(float chartWidth) {
        width = chartWidth;
        radius = chartWidth / (2 * Math.PI);
    }

    public static float width() {
        return width;
    }

    public static double radius() {
        return radius;
    }

    /** How far the chart reaches north and south of the equator, in chart units. */
    public static float clipHalfHeight() {
        return (float) -chartZ(CLIP_LATITUDE);
    }

    /** The chart's height over its width. */
    public static float aspect() {
        return 2f * clipHalfHeight() / width;
    }

    // ------------------------------------------------------------------ the sphere's axes

    /*
     * The one place the sphere's axes are fixed; everything else goes through these three or
     * through SphereFrame (whose east follows from them without naming a longitude). y runs
     * to the north pole, longitude 0 lies along +x and longitude 90 east along -z, so east
     * cross up is south, as on the chart (x east, y up, z south): the sphere has the chart's
     * own handedness rather than its mirror image, and anything built on the chart and lifted
     * onto the sphere keeps its winding and reads the right way round.
     */

    /** The place at a latitude and longitude (radians) and an altitude above the datum. */
    public static SpherePosition position(double latitude, double longitude, double altitude) {
        double r = radius + altitude, cosLat = Math.cos(latitude);
        return new SpherePosition(r * cosLat * Math.cos(longitude), r * Math.sin(latitude), -r * cosLat * Math.sin(longitude));
    }

    /** A position's latitude, radians north of the equator. */
    public static double latitude(SpherePosition position) {
        double r = position.length();
        if (!(r > 0.0)) throw new IllegalArgumentException("The planet centre has no latitude");
        return Math.asin(Math.max(-1.0, Math.min(1.0, position.y() / r)));
    }

    /** A position's longitude, radians east of longitude 0 (-pi to pi). */
    public static double longitude(SpherePosition position) {
        return Math.atan2(-position.z(), position.x());
    }

    /** The longitude at the chart's middle (where chart x is 0). */
    public static double centreLongitude() {
        return centreLongitude;
    }

    /** The place on the sphere under chart point (x, z), in world units from its centre. */
    public static float[] surface(float x, float z) {
        SpherePosition p = position(latitude(z), longitude(x), 0.0);
        return new float[] { (float) p.x(), (float) p.y(), (float) p.z() };
    }

    /** Converts a Mercator map coordinate and altitude to its spherical world-space position. */
    public static SpherePosition spherePosition(double chartX, double chartZ, double altitude) {
        return position(latitude(chartZ), longitude(chartX), altitude);
    }

    /** Projects a spherical world-space position to {Mercator x, altitude, Mercator z}. */
    public static double[] mercatorPosition(SpherePosition position) {
        double r = position.length();
        if (r == 0.0) throw new IllegalArgumentException("The planet centre has no map coordinate");
        return new double[] { chartX(longitude(position)), r - radius, chartZ(latitude(position)) };
    }

    // ------------------------------------------------------------------ chart and sphere

    public static double latitude(double z) {
        return 2 * Math.atan(Math.exp(-z / radius)) - Math.PI / 2;
    }

    /**
     * The longitude at the chart's middle. The chart's join (its left and right edges) is
     * put on the meridian that crosses the least land, so it mostly runs through the sea.
     */
    private static double centreLongitude = 0.0;

    public static double longitude(double x) {
        return x / radius + centreLongitude;
    }

    public static double chartZ(double latitude) {
        return -radius * Math.log(Math.tan(Math.PI / 4 + latitude / 2));
    }

    public static double chartX(double longitude) {
        double x = (longitude - centreLongitude) * radius;
        return wrapX(x);
    }

    /**
     * Puts the chart's join on the meridian, of a few hundred tried, that crosses the least
     * land between the clipped latitudes. Land is ground above seaLevel by height(x, z),
     * sampled with the join where it is now. Returns the fraction of that meridian on land.
     */
    public static float chooseJoin(java.util.function.BiFunction<Float, Float, Float> height, float seaLevel) {
        int meridians = 360, samples = 160;
        double half = clipHalfHeight();
        int[] land = new int[meridians];
        for (int m = 0; m < meridians; m++) {
            float x = (float) ((m + 0.5) / meridians * width - width / 2.0);
            for (int k = 0; k < samples; k++) {
                // Evenly in latitude, so the stretched far north and south don't count extra
                double lat = -CLIP_LATITUDE + (k + 0.5) / samples * 2 * CLIP_LATITUDE;
                if (height.apply(x, (float) chartZ(lat)) > seaLevel) land[m]++;
            }
        }
        int best = 0;
        for (int m = 1; m < meridians; m++) {
            // A little smoothing, so a narrow gap in a big landmass isn't chosen over open sea
            int here = land[m] * 2 + land[(m + 1) % meridians] + land[(m + meridians - 1) % meridians];
            int then = land[best] * 2 + land[(best + 1) % meridians] + land[(best + meridians - 1) % meridians];
            if (here < then) best = m;
        }
        double joinLongitude = longitude((best + 0.5) / meridians * width - width / 2.0);
        // The join sits at the chart's edges, half the way round from its middle
        centreLongitude = joinLongitude + Math.PI;
        centreLongitude = Math.atan2(Math.sin(centreLongitude), Math.cos(centreLongitude));
        return land[best] / (float) samples;
    }

    // ------------------------------------------------------------------ the sun

    /**
     * Where the sun is in the sky at a place, as a unit direction in the local east/up/south
     * frame there: from its declination (the season, from the planet's tilt) and the longitude
     * where it's noon (the time of day).
     */
    public static float[] sunDirection(SpherePosition position, double declination, double noonLongitude) {
        return localSunDirection(point(declination, noonLongitude), latitude(position), longitude(position));
    }

    private static float[] localSunDirection(double[] sun, double lat, double lon) {
        double[] up = point(lat, lon), e = east(lon), n = north(lat, lon);
        float[] d = { (float) dot(sun, e), (float) dot(sun, up), (float) -dot(sun, n) };
        // It's always day wherever the player is: the sun is never let below MIN_SUN_ELEVATION
        // (held at that height in the same bearing), however far they walk
        if (d[1] < SIN_MIN_SUN) {
            float flat = (float) Math.sqrt(d[0] * d[0] + d[2] * d[2]);
            if (flat < 1e-5f) { d[0] = 1f; flat = 1f; }
            float across = (float) Math.sqrt(1.0 - SIN_MIN_SUN * SIN_MIN_SUN) / flat;
            d = new float[] { d[0] * across, (float) SIN_MIN_SUN, d[2] * across };
        }
        return d;
    }

    // The lowest the sun is ever seen, in degrees above the horizon
    public static final double MIN_SUN_ELEVATION = 10.0;
    private static final double SIN_MIN_SUN = Math.sin(Math.toRadians(MIN_SUN_ELEVATION));

    /** The direction of the planet's north pole star (the celestial pole) in the local east/up/south frame at a place. */
    public static float[] celestialPole(SpherePosition position) {
        double lat = latitude(position);
        return new float[] { 0f, (float) Math.sin(lat), (float) -Math.cos(lat) };
    }

    /** x brought back into the chart, -width/2 to width/2. */
    public static double wrapX(double x) {
        double w = width;
        return x - w * Math.floor((x + w / 2) / w);
    }

    /** The shortest distance between two places along the sphere's surface, in true units. */
    public static double surfaceDistance(double x1, double z1, double x2, double z2) {
        double lat1 = latitude(z1), lat2 = latitude(z2);
        double dLat = lat2 - lat1, dLon = longitude(x2) - longitude(x1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * radius * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Half the way round: the furthest two places can be apart. */
    public static double greatestDistance() {
        return Math.PI * radius;
    }

    private static double[] point(double lat, double lon) {
        return new double[] { Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat) };
    }

    private static double[] east(double lon) {
        return new double[] { -Math.sin(lon), Math.cos(lon), 0 };
    }

    private static double[] north(double lat, double lon) {
        return new double[] { -Math.sin(lat) * Math.cos(lon), -Math.sin(lat) * Math.sin(lon), Math.cos(lat) };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
