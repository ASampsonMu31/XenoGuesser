/**
 * The world is a sphere. Everything in the game is laid out on a Mercator chart of it: x
 * running east with longitude (-180 to 180 degrees across the chart's WIDTH) and z running
 * south, cut off before Mercator runs away to infinity at the poles, at 75 degrees north
 * and south, so the chart is wider than it is tall. Being conformal, the chart is
 * locally just the sphere at a scale (cos latitude true units to one chart unit), so the
 * world can be built, walked and drawn flat in chart units round the player while:
 * <ul>
 *   <li>the horizon curves away with the sphere (see curvature, used by every vertex shader);</li>
 *   <li>walking straight follows a great circle, the view turning very slightly as you go;</li>
 *   <li>walking off the east edge brings you in from the west, and walking over a pole (past
 *       the chart's top or bottom) brings you out on the far side heading the other way;</li>
 *   <li>distances for scoring are along the sphere's surface.</li>
 * </ul>
 * Everything about the land (its height, climate, plants, rocks, nations) is worked out
 * from places on the sphere rather than on the chart, so the east and west edges of the
 * chart, being the same place, match exactly.
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

    /** The place on the sphere under chart point (x, z), in world units from its centre. */
    public static float[] surface(float x, float z) {
        double lat = latitude(z), lon = longitude(x);
        double c = Math.cos(lat) * radius;
        return new float[] { (float) (c * Math.cos(lon)), (float) (Math.sin(lat) * radius), (float) (c * Math.sin(lon)) };
    }

    /** A chunk index brought round into the chart, so the same place always has the same chunk. */
    public static int wrapChunk(int index, float chunkSize) {
        int count = Math.max(1, Math.round(width / chunkSize));
        return Math.floorMod(index + count / 2, count) - count / 2;
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
     * Where the sun is in the sky at chart point (x, z), as a unit direction in the world
     * (x east, y up, z south): from its declination (the season, from the planet's tilt)
     * and the longitude where it's noon (the time of day).
     */
    public static float[] sunDirection(double x, double z, double declination, double noonLongitude) {
        double[] sun = point(declination, noonLongitude);
        double lat = latitude(z), lon = longitude(x);
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

    /** The direction in the world of the planet's north pole star (the celestial pole) seen from (x, z). */
    public static float[] celestialPole(double z) {
        double lat = latitude(z);
        return new float[] { 0f, (float) Math.sin(lat), (float) -Math.cos(lat) };
    }

    /**
     * How strongly a compass is pulled round to north here, 1 at the equator falling to
     * nothing at the poles, where the planet's field points straight down.
     */
    public static float compassPull(double z) {
        return (float) Math.cos(latitude(z));
    }

    /** x brought back into the chart, -width/2 to width/2. */
    public static double wrapX(double x) {
        double w = width;
        return x - w * Math.floor((x + w / 2) / w);
    }

    /** True units (along the sphere) per chart unit here. */
    public static double scale(double z) {
        return Math.cos(latitude(z));
    }

    /**
     * How far the ground drops below the flat chart per square unit of horizontal distance
     * from the viewer, so the horizon curves away as on a sphere of this size (in chart units
     * the sphere's radius here is radius / scale).
     */
    public static float curvature(double z) {
        return (float) (scale(z) / (2 * radius));
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

    /** A chart direction (angle from +x towards +z) as a direction along the sphere at (x, z). */
    private static double[] tangent(double x, double z, double angle) {
        double lat = latitude(z), lon = longitude(x);
        double[] e = east(lon), n = north(lat, lon);
        double dx = Math.cos(angle), dz = Math.sin(angle);
        return new double[] { dx * e[0] - dz * n[0], dx * e[1] - dz * n[1], dx * e[2] - dz * n[2] };
    }

    /** ...and back: a direction along the sphere at (x, z) as a chart angle. */
    private static double chartAngle(double x, double z, double[] t) {
        double lat = latitude(z), lon = longitude(x);
        double dx = dot(t, east(lon)), dz = -dot(t, north(lat, lon));
        return Math.atan2(dz, dx);
    }

    /**
     * Walking straight: someone facing along chart angle at (ax, az) who steps to (bx, bz)
     * is facing this much further round (radians, towards +z) on arriving, as their facing
     * is carried along the great circle between the two. Tiny for any one step, but it's
     * what makes a straight walk a great circle rather than a line on the chart.
     */
    public static double turnAlong(double ax, double az, double bx, double bz, double angle) {
        double[] p = point(latitude(az), longitude(ax)), q = point(latitude(bz), longitude(bx));
        double[] t = tangent(ax, az, angle);
        double k = dot(q, t) / (1 + dot(p, q));
        double[] carried = { t[0] - k * (p[0] + q[0]), t[1] - k * (p[1] + q[1]), t[2] - k * (p[2] + q[2]) };
        double turned = chartAngle(bx, bz, carried) - angle;
        return Math.atan2(Math.sin(turned), Math.cos(turned));
    }

    /**
     * Past the chart's top or bottom (over a polar cap the chart can't show): where someone
     * at (x, z) facing along chart angle comes out on the far side of the cap if they keep
     * going straight, as {x, z, new angle}. Null if (x, z) is still on the chart.
     */
    public static double[] acrossPole(double x, double z, double angle) {
        double half = clipHalfHeight();
        if (Math.abs(z) <= half) return null;
        double sign = z < 0 ? 1 : -1;   // north is -z
        double[] p = point(latitude(z), longitude(x));
        double[] t = tangent(x, z, angle);
        // Height above the equator along the great circle: sign * (p.z cos s + t.z sin s)
        double a = sign * p[2], b = sign * t[2];
        double c = Math.sqrt(a * a + b * b);
        double limit = Math.sin(CLIP_LATITUDE) - 1e-4;
        if (c < 1e-9) return null;
        double s = Math.atan2(b, a) + Math.acos(Math.max(-1, Math.min(1, limit / c))) + 1e-5;
        double cs = Math.cos(s), sn = Math.sin(s);
        double[] q = { p[0] * cs + t[0] * sn, p[1] * cs + t[1] * sn, p[2] * cs + t[2] * sn };
        double[] u = { -p[0] * sn + t[0] * cs, -p[1] * sn + t[1] * cs, -p[2] * sn + t[2] * cs };
        double lat = Math.asin(Math.max(-1, Math.min(1, q[2])));
        double lon = Math.atan2(q[1], q[0]);
        double nx = chartX(lon), nz = chartZ(lat);
        return new double[] { nx, nz, chartAngle(nx, nz, u) };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
