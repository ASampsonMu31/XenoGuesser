package gmaths;

/** A mutable three-component vector. */
public final class Vec3 {
  public float x;
  public float y;
  public float z;

  public Vec3() {
    this(0.0f, 0.0f, 0.0f);
  }

  public Vec3(float x, float y, float z) {
    this.x = x;
    this.y = y;
    this.z = z;
  }

  public Vec3(Vec3 source) {
    this(source.x, source.y, source.z);
  }

  /* I declare that this code is my own work */
  public record Vec3Pair(Vec3 a, Vec3 b) {}
  /* Author Alexander Sampson, asampson1@sheffield.ac.uk */

  public float length() {
    return magnitude();
  }

  public float magnitude() {
    return magnitude(this);
  }

  public static float magnitude(Vec3 vector) {
    return (float) Math.hypot(Math.hypot(vector.x, vector.y), vector.z);
  }

  public void normalize() {
    float size = magnitude();
    x /= size;
    y /= size;
    z /= size;
  }

  public static Vec3 normalize(Vec3 vector) {
    Vec3 result = new Vec3(vector);
    result.normalize();
    return result;
  }

  public void add(Vec3 other) {
    x += other.x;
    y += other.y;
    z += other.z;
  }

  public static Vec3 add(Vec3 first, Vec3 second) {
    return new Vec3(first.x + second.x, first.y + second.y, first.z + second.z);
  }

  public void subtract(Vec3 other) {
    x -= other.x;
    y -= other.y;
    z -= other.z;
  }

  public static Vec3 subtract(Vec3 first, Vec3 second) {
    return new Vec3(first.x - second.x, first.y - second.y, first.z - second.z);
  }

  public float dotProduct(Vec3 other) {
    return dotProduct(this, other);
  }

  public static float dotProduct(Vec3 first, Vec3 second) {
    return first.x * second.x + first.y * second.y + first.z * second.z;
  }

  public void multiply(float factor) {
    x *= factor;
    y *= factor;
    z *= factor;
  }

  public static Vec3 multiply(Vec3 vector, float factor) {
    return new Vec3(vector.x * factor, vector.y * factor, vector.z * factor);
  }

  public static Vec3 crossProduct(Vec3 first, Vec3 second) {
    float xComponent = first.y * second.z - first.z * second.y;
    float yComponent = first.z * second.x - first.x * second.z;
    float zComponent = first.x * second.y - first.y * second.x;
    return new Vec3(xComponent, yComponent, zComponent);
  }

  @Override
  public String toString() {
    return "(" + x + "," + y + "," + z + ")";
  }
}