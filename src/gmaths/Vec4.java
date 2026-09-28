package gmaths;

/** A mutable four-component vector. */
public final class Vec4 {
  public float x;
  public float y;
  public float z;
  public float w;

  public Vec4() {
    this(0.0f, 0.0f, 0.0f, 1.0f);
  }

  public Vec4(Vec3 xyz) {
    this(xyz, 1.0f);
  }

  public Vec4(Vec3 xyz, float w) {
    this(xyz.x, xyz.y, xyz.z, w);
  }

  public Vec4(float x, float y, float z, float w) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.w = w;
  }

  public Vec3 toVec3() {
    return new Vec3(x, y, z);
  }

  @Override
  public String toString() {
    return "(" + x + "," + y + "," + z + "," + w + ")";
  }
}