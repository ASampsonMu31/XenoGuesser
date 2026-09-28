package gmaths;

/** A mutable two-component vector. */
public final class Vec2 {
  public float x;
  public float y;

  public Vec2() {
    this(0.0f, 0.0f);
  }

  public Vec2(float x, float y) {
    this.x = x;
    this.y = y;
  }

  public Vec2(Vec2 source) {
    this(source.x, source.y);
  }

  public float length() {
    return magnitude();
  }

  public float magnitude() {
    return magnitude(this);
  }

  public static float magnitude(Vec2 vector) {
    return (float) Math.hypot(vector.x, vector.y);
  }

  public void normalize() {
    float size = magnitude();
    x /= size;
    y /= size;
  }

  public static Vec2 normalize(Vec2 vector) {
    Vec2 result = new Vec2(vector);
    result.normalize();
    return result;
  }

  public void add(Vec2 other) {
    x += other.x;
    y += other.y;
  }

  public static Vec2 add(Vec2 first, Vec2 second) {
    return new Vec2(first.x + second.x, first.y + second.y);
  }

  public void subtract(Vec2 other) {
    x -= other.x;
    y -= other.y;
  }

  public static Vec2 subtract(Vec2 first, Vec2 second) {
    return new Vec2(first.x - second.x, first.y - second.y);
  }

  public float dotProduct(Vec2 other) {
    return dotProduct(this, other);
  }

  public static float dotProduct(Vec2 first, Vec2 second) {
    return first.x * second.x + first.y * second.y;
  }

  public void multiply(float factor) {
    x *= factor;
    y *= factor;
  }

  public static Vec2 multiply(Vec2 vector, float factor) {
    return new Vec2(vector.x * factor, vector.y * factor);
  }

  @Override
  public String toString() {
    return "(" + x + "," + y + ")";
  }
}