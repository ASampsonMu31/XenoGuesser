/**
 * The planet's size, the one definition of its axes (position, latitude, longitude), the sun
 * and the celestial pole.
 * <p>
 * The world is a sphere and nothing in it depends on latitude:
 * <ul>
 *   <li>Places are planet-centred SpherePositions (doubles); distances are true, in world
 *       units (PlayerBody.METRE to a metre), measured along great circles (SphereMath).</li>
 *   <li>Each object is built in a local tangent frame of its own (SphereFrame: x east, y up,
 *       z south) as if on flat ground, and lifted onto the sphere through it; no piece reaches
 *       more than a few hundred metres from its frame's origin. Networks (roads, settlements)
 *       are planned on the sphere and built piece by piece in frames.</li>
 *   <li>Streaming, populations and lookups go by cells of the icosahedral GeodesicChunkGrid
 *       (CellIndex, CellStream), counts by each cell's own area; noise and regional factors
 *       are sampled by 3D direction, and whole-sphere fields are cube maps (SphereRaster).</li>
 *   <li>Everything is drawn camera-relative: planet-centred meshes have the eyes' place taken
 *       off before the view-projection (see vs_standard), so precision holds everywhere.</li>
 *   <li>The Mercator chart (MapProjection) is only for the map: drawing it and turning a
 *       click into a guess.</li>
 * </ul>
 */
public final class Planet {

    /** The sphere's circumference, in world units (also the map's width). */
    private static float width = 150_000f;
    private static double radius = width / (2 * Math.PI);

    private Planet() {
    }

    public static void setWidth(float circumference) {
        width = circumference;
        radius = circumference / (2 * Math.PI);
    }

    public static float width() {
        return width;
    }

    public static double radius() {
        return radius;
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
