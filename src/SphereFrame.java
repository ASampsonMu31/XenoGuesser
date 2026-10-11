import com.xenoguesser.math.Matrix4;

/**
 * A local tangent frame on the planet (D3 in GEODESIC_MIGRATION_PLAN.txt): an origin and three
 * unit axes. Local coordinates keep the chart's convention, x east, y up and z south, so flat
 * code written for the chart runs unchanged in local (x, z) round the origin and its results are
 * lifted onto the sphere with toWorld. A frame turned about its up axis (rotated) has x and z
 * axes that are no longer east and south, but they stay a right-handed set with up.
 * <p>
 * East is the direction round the polar axis (y cross up), so it follows from Planet's axes
 * without naming a longitude. Within a hair of a pole, where that vanishes, it is longitude 0's
 * east (-z) laid level instead, which is what it tends to along that meridian.
 * <p>
 * toWorld/toLocal are rigid (a flat plane touching the sphere at the origin), right for one
 * house or sign: over 10 m the sphere falls away from the plane by about 2 cm. toSphere/
 * fromSphere follow the curve instead (local x, z are distances along the surface), for things
 * that lie on the ground over longer reaches, such as a road segment.
 */
public final class SphereFrame {

    private static final double POLE_EPSILON = 1e-12;

    private final SpherePosition origin;
    // The local x (east), y (up) and z (south) axes as unit vectors in the world
    private final double xx, xy, xz, ux, uy, uz, zx, zy, zz;

    private SphereFrame(SpherePosition origin, double xx, double xy, double xz,
            double ux, double uy, double uz, double zx, double zy, double zz) {
        this.origin = origin;
        this.xx = xx; this.xy = xy; this.xz = xz;
        this.ux = ux; this.uy = uy; this.uz = uz;
        this.zx = zx; this.zy = zy; this.zz = zz;
    }

    /** The east/up/south frame at a position; the origin is the position itself, at its altitude. */
    public static SphereFrame at(SpherePosition origin) {
        double length = origin.length();
        if (!(length > 0.0)) throw new IllegalArgumentException("The planet centre has no tangent frame");
        double ux = origin.x() / length, uy = origin.y() / length, uz = origin.z() / length;
        // East: the polar axis (y) cross up
        double ex = uz, ey = 0.0, ez = -ux;
        double horizontal = Math.sqrt(ex * ex + ez * ez);
        if (horizontal < POLE_EPSILON) {
            // At a pole: -z with its upward part taken out
            ex = ux * uz;
            ey = uy * uz;
            ez = uz * uz - 1.0;
            horizontal = Math.sqrt(ex * ex + ey * ey + ez * ez);
        }
        ex /= horizontal;
        ey /= horizontal;
        ez /= horizontal;
        // South: east cross up
        double sx = ey * uz - ez * uy, sy = ez * ux - ex * uz, sz = ex * uy - ey * ux;
        return new SphereFrame(origin, ex, ey, ez, ux, uy, uz, sx, sy, sz);
    }

    /** The frame at a position turned so its local x points along heading (see rotated). */
    public static SphereFrame at(SpherePosition origin, double heading) {
        return at(origin).rotated(heading);
    }

    /**
     * This frame turned about its up axis by angle radians, from local x towards local z
     * (from east towards south for a frame from at(), as the camera's yaw turns): the new
     * local x is the old frame's direction at that heading.
     */
    public SphereFrame rotated(double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new SphereFrame(origin,
                xx * c + zx * s, xy * c + zy * s, xz * c + zz * s,
                ux, uy, uz,
                zx * c - xx * s, zy * c - xy * s, zz * c - xz * s);
    }

    /** The same axes about another origin (for a nearby point, where the tilt between them doesn't matter). */
    public SphereFrame movedTo(SpherePosition newOrigin) {
        return new SphereFrame(newOrigin, xx, xy, xz, ux, uy, uz, zx, zy, zz);
    }

    public SpherePosition origin() {
        return origin;
    }

    /** Local x as a world unit vector (east, unless rotated). */
    public double[] xAxis() {
        return new double[] { xx, xy, xz };
    }

    /** Local y, straight up from the planet's centre through the origin. */
    public double[] up() {
        return new double[] { ux, uy, uz };
    }

    /** Local z as a world unit vector (south, unless rotated). */
    public double[] zAxis() {
        return new double[] { zx, zy, zz };
    }

    // ------------------------------------------------------------------ rigid (flat) lifting

    /** Local (x, y, z) in the flat plane touching the sphere at the origin, as a world position. */
    public SpherePosition toWorld(double x, double y, double z) {
        return new SpherePosition(origin.x() + xx * x + ux * y + zx * z,
                origin.y() + xy * x + uy * y + zy * z,
                origin.z() + xz * x + uz * y + zz * z);
    }

    /** A world position in this frame's local (x, y, z): the inverse of toWorld. */
    public double[] toLocal(SpherePosition position) {
        double dx = position.x() - origin.x(), dy = position.y() - origin.y(), dz = position.z() - origin.z();
        return new double[] { dx * xx + dy * xy + dz * xz, dx * ux + dy * uy + dz * uz, dx * zx + dy * zy + dz * zz };
    }

    /** A local direction (or offset) as a world one. */
    public double[] worldDirection(double x, double y, double z) {
        return new double[] { xx * x + ux * y + zx * z, xy * x + uy * y + zy * z, xz * x + uz * y + zz * z };
    }

    /** A world direction (or offset) in local terms. */
    public double[] localDirection(double x, double y, double z) {
        return new double[] { x * xx + y * xy + z * xz, x * ux + y * uy + z * uz, x * zx + y * zy + z * zz };
    }

    // ------------------------------------------------------------------ curved lifting

    /**
     * Local (x, z), taken as distances along the surface from the point under the origin, and
     * an altitude above the planet's datum (not above the origin), as a world position.
     * Distances along the bearing are exact; across it they are off by about one part in
     * 6 (d / R)^2 (half a millimetre a metre at 300 m).
     */
    public SpherePosition toSphere(double x, double altitude, double z) {
        double distance = Math.hypot(x, z), radius = Planet.radius() + altitude;
        if (distance == 0.0) return new SpherePosition(ux * radius, uy * radius, uz * radius);
        double angle = distance / Planet.radius();
        double along = Math.sin(angle) / distance, c = Math.cos(angle);
        return new SpherePosition(radius * (ux * c + (xx * x + zx * z) * along),
                radius * (uy * c + (xy * x + zy * z) * along),
                radius * (uz * c + (xz * x + zz * z) * along));
    }

    /** A world position as {local x, altitude above the datum, local z}: the inverse of toSphere. */
    public double[] fromSphere(SpherePosition position) {
        double length = position.length();
        if (!(length > 0.0)) throw new IllegalArgumentException("The planet centre has no place in a frame");
        double px = position.x() / length, py = position.y() / length, pz = position.z() / length;
        double cosine = px * ux + py * uy + pz * uz;
        double x = px * xx + py * xy + pz * xz, z = px * zx + py * zy + pz * zz;
        double sine = Math.hypot(x, z);
        double altitude = length - Planet.radius();
        if (sine == 0.0) return new double[] { 0.0, altitude, 0.0 };
        double distance = Math.atan2(sine, cosine) * Planet.radius();
        return new double[] { x / sine * distance, altitude, z / sine * distance };
    }

    // ------------------------------------------------------------------ headings

    /** The world unit tangent at heading angle (radians from local x towards local z). */
    public double[] headingDirection(double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new double[] { xx * c + zx * s, xy * c + zy * s, xz * c + zz * s };
    }

    /** The heading (radians from local x towards local z) of a world direction's level part. */
    public double headingOf(double x, double y, double z) {
        return Math.atan2(x * zx + y * zy + z * zz, x * xx + y * xy + z * xz);
    }

    /** The heading from the origin towards another place, along the great circle between them. */
    public double headingTo(SpherePosition target) {
        return headingOf(target.x(), target.y(), target.z());
    }

    /**
     * Column-major model matrix taking local coordinates (scaled) to world coordinates, for
     * a mesh drawn with the camera-relative sphere shaders.
     */
    public Matrix4 modelMatrix(float scale) {
        return modelMatrix(scale, 0.0, 0.0, 0.0);
    }

    /**
     * Column-major model matrix taking local coordinates (scaled) to world coordinates less
     * eye's: for camera-relative drawing, with the subtraction done in double precision so
     * nothing far from the planet's centre is rounded.
     */
    public Matrix4 modelMatrix(float scale, SpherePosition eye) {
        return modelMatrix(scale, eye.x(), eye.y(), eye.z());
    }

    /** The frame's turn alone (local directions to world directions), with no translation. */
    public Matrix4 rotationMatrix() {
        return modelMatrix(1f, origin);
    }

    private Matrix4 modelMatrix(float scale, double ex, double ey, double ez) {
        return Matrix4.fromColumns(new float[] {
            (float) (xx * scale), (float) (xy * scale), (float) (xz * scale), 0f,
            (float) (ux * scale), (float) (uy * scale), (float) (uz * scale), 0f,
            (float) (zx * scale), (float) (zy * scale), (float) (zz * scale), 0f,
            (float) (origin.x() - ex), (float) (origin.y() - ey), (float) (origin.z() - ez), 1f
        });
    }
}
