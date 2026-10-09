/**
 * A position in the planet's world-space Cartesian coordinate system.
 * The distance from the origin is the planet radius plus the position's altitude.
 */
public record SpherePosition(double x, double y, double z) {

    public SpherePosition {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Sphere position components must be finite");
        }
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public SpherePosition add(SpherePosition other) {
        return new SpherePosition(x + other.x, y + other.y, z + other.z);
    }

    public SpherePosition subtract(SpherePosition other) {
        return new SpherePosition(x - other.x, y - other.y, z - other.z);
    }

    public SpherePosition scale(double factor) {
        return new SpherePosition(x * factor, y * factor, z * factor);
    }
}
