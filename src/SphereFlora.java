import com.xenoguesser.math.*;

/**
 * A tree or shrub on the sphere: where it stands, which model, and what's worked out once
 * for it (its model matrix, its leaves' colours there and its trunk's radius).
 */
record SphereFlora(SpherePosition position, int speciesIndex, int modelIndex, float scale,
                           float rotationY, Matrix4 model, Vector3 leafDark, Vector3 leafLight, float trunk) {}
