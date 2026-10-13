/**
 * Great-circle geometry on the planet for SpherePositions (planet-centred, in world units).
 * Distances are along the planet's datum surface (radius Planet.radius()), whatever the
 * altitude of the points; moving keeps a point's altitude. A heading is in radians from east
 * towards south in the local frame (SphereFrame) where it applies, as the camera's yaw is.
 */
public final class SphereMath {

    private SphereMath() {
    }

    // ------------------------------------------------------------------ directions and heights

    /** The unit direction from the planet's centre through a position. */
    public static GeodesicChunkGrid.Direction direction(SpherePosition position) {
        return new GeodesicChunkGrid.Direction(position.x(), position.y(), position.z()).normalized();
    }

    /** The place in a direction at an altitude above the datum. */
    public static SpherePosition atAltitude(GeodesicChunkGrid.Direction direction, double altitude) {
        GeodesicChunkGrid.Direction unit = direction.normalized();
        double radius = Planet.radius() + altitude;
        return new SpherePosition(unit.x() * radius, unit.y() * radius, unit.z() * radius);
    }

    /** The place straight above or below a position at an altitude above the datum. */
    public static SpherePosition atAltitude(SpherePosition position, double altitude) {
        double length = position.length();
        if (!(length > 0.0)) throw new IllegalArgumentException("The planet centre has no direction");
        return position.scale((Planet.radius() + altitude) / length);
    }

    /** Height above the planet's datum. */
    public static double altitude(SpherePosition position) {
        return position.length() - Planet.radius();
    }

    // ------------------------------------------------------------------ distances and headings

    /** The angle between two positions at the planet's centre, in radians (precise when tiny). */
    public static double angle(SpherePosition a, SpherePosition b) {
        double cx = a.y() * b.z() - a.z() * b.y();
        double cy = a.z() * b.x() - a.x() * b.z();
        double cz = a.x() * b.y() - a.y() * b.x();
        return Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), a.x() * b.x() + a.y() * b.y() + a.z() * b.z());
    }

    /** The angle between two directions at the planet's centre, in radians. */
    public static double angle(GeodesicChunkGrid.Direction a, GeodesicChunkGrid.Direction b) {
        double cx = a.y() * b.z() - a.z() * b.y();
        double cy = a.z() * b.x() - a.x() * b.z();
        double cz = a.x() * b.y() - a.y() * b.x();
        return Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), a.dot(b));
    }

    /** The shortest distance between two places along the datum surface, in world units. */
    public static double distance(SpherePosition a, SpherePosition b) {
        if (!(a.length() > 0.0) || !(b.length() > 0.0)) {
            throw new IllegalArgumentException("Cannot measure a surface distance from the planet centre");
        }
        return angle(a, b) * Planet.radius();
    }

    /** The heading at from of the great circle to to. */
    public static double heading(SpherePosition from, SpherePosition to) {
        return SphereFrame.at(from).headingTo(to);
    }

    // ------------------------------------------------------------------ moving

    /** Goes distance along the surface from a place at heading, along a great circle, keeping its altitude. */
    public static SpherePosition move(SpherePosition from, double heading, double distance) {
        return along(from, SphereFrame.at(from).headingDirection(heading), distance);
    }

    /**
     * Goes distance along the surface towards the local (east, south) direction, along a great
     * circle, keeping its altitude. A zero direction or distance stays put.
     */
    public static SpherePosition moveToward(SpherePosition from, double east, double south, double distance) {
        if (!Double.isFinite(east) || !Double.isFinite(south) || !Double.isFinite(distance) || distance < 0.0) {
            throw new IllegalArgumentException("Movement direction and distance must be finite, with non-negative distance");
        }
        if (distance == 0.0 || (east == 0.0 && south == 0.0)) return from;
        return move(from, Math.atan2(south, east), distance);
    }

    private static SpherePosition along(SpherePosition from, double[] tangent, double distance) {
        double radius = from.length();
        if (!(radius > 0.0)) throw new IllegalArgumentException("Cannot move from the planet centre");
        double arc = distance / Planet.radius(), c = Math.cos(arc), s = Math.sin(arc);
        return new SpherePosition(from.x() * c + tangent[0] * radius * s,
                from.y() * c + tangent[1] * radius * s,
                from.z() * c + tangent[2] * radius * s);
    }

    /**
     * Someone at from facing heading who goes straight (along the great circle) to to: the
     * heading they face on arriving, in to's frame. Walking straight over a pole, they come
     * out facing south having gone in facing north.
     */
    public static double carryHeading(SpherePosition from, SpherePosition to, double heading) {
        double fromLength = from.length(), toLength = to.length();
        double px = from.x() / fromLength, py = from.y() / fromLength, pz = from.z() / fromLength;
        double qx = to.x() / toLength, qy = to.y() / toLength, qz = to.z() / toLength;
        double[] t = SphereFrame.at(from).headingDirection(heading);
        double together = 1.0 + px * qx + py * qy + pz * qz;
        if (together < 1e-9) return heading;   // antipodes: no one great circle between them
        // Parallel transport along the great circle from p to q
        double k = (qx * t[0] + qy * t[1] + qz * t[2]) / together;
        return SphereFrame.at(to).headingOf(t[0] - k * (px + qx), t[1] - k * (py + qy), t[2] - k * (pz + qz));
    }

    /** The place fraction of the way from a to b along the shorter great circle, its radius in between. */
    public static SpherePosition slerp(SpherePosition from, SpherePosition to, double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0.0 || fraction > 1.0) {
            throw new IllegalArgumentException("Interpolation fraction must be between zero and one");
        }
        double fromRadius = from.length(), toRadius = to.length();
        if (!(fromRadius > 0.0) || !(toRadius > 0.0)) {
            throw new IllegalArgumentException("Cannot interpolate a position at the planet centre");
        }
        double ax = from.x() / fromRadius, ay = from.y() / fromRadius, az = from.z() / fromRadius;
        double bx = to.x() / toRadius, by = to.y() / toRadius, bz = to.z() / toRadius;
        double angle = angle(from, to);
        double wa, wb;
        if (angle < 1e-9) {
            wa = 1.0 - fraction;
            wb = fraction;
        } else {
            double sine = Math.sin(angle);
            wa = Math.sin((1.0 - fraction) * angle) / sine;
            wb = Math.sin(fraction * angle) / sine;
        }
        double x = wa * ax + wb * bx, y = wa * ay + wb * by, z = wa * az + wb * bz;
        double length = Math.sqrt(x * x + y * y + z * z);
        double radius = fromRadius + (toRadius - fromRadius) * fraction;
        return new SpherePosition(radius * x / length, radius * y / length, radius * z / length);
    }

    /** A place on the datum picked evenly over the planet's surface, no further than maxLatitude (radians) from the equator. */
    public static SpherePosition randomPlace(java.util.Random rand, double maxLatitude) {
        double limit = Math.sin(Math.min(Math.PI * 0.5, Math.abs(maxLatitude)));
        // (evenly in the sine of the latitude is evenly over the surface)
        double y = (rand.nextDouble() * 2.0 - 1.0) * limit, angle = rand.nextDouble() * Math.PI * 2.0;
        double across = Math.sqrt(1.0 - y * y);
        return new SpherePosition(across * Math.cos(angle) * Planet.radius(), y * Planet.radius(),
                across * Math.sin(angle) * Planet.radius());
    }

    // ------------------------------------------------------------------ arcs

    /**
     * How far along the great-circle arc from a to b (0 at a, 1 at b) is the point of it nearest
     * a place, at its foot if that falls on the arc, otherwise the nearer end.
     */
    public static double arcFraction(SpherePosition point, SpherePosition a, SpherePosition b) {
        double total = angle(a, b);
        if (total < 1e-12) return 0.0;
        // The arc's pole, and the point brought into the arc's plane
        double nx = a.y() * b.z() - a.z() * b.y(), ny = a.z() * b.x() - a.x() * b.z(), nz = a.x() * b.y() - a.y() * b.x();
        double nLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= nLength; ny /= nLength; nz /= nLength;
        double out = point.x() * nx + point.y() * ny + point.z() * nz;
        SpherePosition flat = new SpherePosition(point.x() - nx * out, point.y() - ny * out, point.z() - nz * out);
        if (flat.length() < 1e-9 * point.length()) return 0.0;   // at the arc's pole: every point is as near
        // Signed angle from a to the foot, round the arc's pole
        double cx = a.y() * flat.z() - a.z() * flat.y(), cy = a.z() * flat.x() - a.x() * flat.z(),
                cz = a.x() * flat.y() - a.y() * flat.x();
        double along = Math.atan2(cx * nx + cy * ny + cz * nz,
                a.x() * flat.x() + a.y() * flat.y() + a.z() * flat.z());
        if (along >= 0.0 && along <= total) return along / total;
        return angle(point, a) <= angle(point, b) ? 0.0 : 1.0;
    }

    /** The point of the great-circle arc from a to b nearest a place. */
    public static SpherePosition nearestOnArc(SpherePosition point, SpherePosition a, SpherePosition b) {
        return slerp(a, b, arcFraction(point, a, b));
    }

    /** Distance along the surface from a place to the nearest point of the arc from a to b. */
    public static double distanceToArc(SpherePosition point, SpherePosition a, SpherePosition b) {
        return distance(point, nearestOnArc(point, a, b));
    }
}
