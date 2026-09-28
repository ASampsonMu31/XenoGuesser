package gmaths;

/** Builds 4 by 4 matrices for common 3D camera and object transforms. */
public final class Mat4Transform {
  private static final float DEFAULT_NEAR_CLIP = 0.1f;
  private static final float DEFAULT_FAR_CLIP = 100.0f;

  private Mat4Transform() {}

  public static Mat4 translate(Vec3 offset) {
    return translate(offset.x, offset.y, offset.z);
  }

  public static Mat4 translate(float x, float y, float z) {
    Mat4 transform = new Mat4(1.0f);
    transform.set(0, 3, x);
    transform.set(1, 3, y);
    transform.set(2, 3, z);
    return transform;
  }

  public static Mat4 scale(Vec3 factors) {
    return scale(factors.x, factors.y, factors.z);
  }

  public static Mat4 scale(float x, float y, float z) {
    Mat4 transform = new Mat4(1.0f);
    transform.set(0, 0, x);
    transform.set(1, 1, y);
    transform.set(2, 2, z);
    return transform;
  }

  public static Mat4 rotateAroundX(float degrees) {
    float radians = (float) Math.toRadians(degrees);
    float cosine = (float) Math.cos(radians);
    float sine = (float) Math.sin(radians);
    Mat4 transform = new Mat4(1.0f);
    transform.set(1, 1, cosine);
    transform.set(1, 2, -sine);
    transform.set(2, 1, sine);
    transform.set(2, 2, cosine);
    return transform;
  }

  public static Mat4 rotateAroundY(float degrees) {
    float radians = (float) Math.toRadians(degrees);
    float cosine = (float) Math.cos(radians);
    float sine = (float) Math.sin(radians);
    Mat4 transform = new Mat4(1.0f);
    transform.set(0, 0, cosine);
    transform.set(0, 2, sine);
    transform.set(2, 0, -sine);
    transform.set(2, 2, cosine);
    return transform;
  }

  public static Mat4 rotateAroundZ(float degrees) {
    float radians = (float) Math.toRadians(degrees);
    float cosine = (float) Math.cos(radians);
    float sine = (float) Math.sin(radians);
    Mat4 transform = new Mat4(1.0f);
    transform.set(0, 0, cosine);
    transform.set(0, 1, -sine);
    transform.set(1, 0, sine);
    transform.set(1, 1, cosine);
    return transform;
  }

  public static Mat4 perspective(float fieldOfView, float aspectRatio) {
    return perspective(fieldOfView, aspectRatio, DEFAULT_NEAR_CLIP, DEFAULT_FAR_CLIP);
  }

  public static Mat4 perspective(float fieldOfView, float aspectRatio, float near, float far) {
    float halfAngleScale = (float) Math.tan(Math.toRadians(fieldOfView / 2.0f));
    float depthRange = far - near;
    Mat4 projection = new Mat4();
    projection.set(0, 0, 1.0f / (halfAngleScale * aspectRatio));
    projection.set(1, 1, 1.0f / halfAngleScale);
    projection.set(2, 2, -(far + near) / depthRange);
    projection.set(2, 3, -(2.0f * far * near) / depthRange);
    projection.set(3, 2, -1.0f);
    return projection;
  }

  public static Mat4 lookAt(Vec3 from, Vec3 to, Vec3 worldUp) {
    Vec3 forward = Vec3.normalize(Vec3.subtract(to, from));
    Vec3 right = Vec3.normalize(Vec3.crossProduct(forward, worldUp));
    Vec3 correctedUp = Vec3.normalize(Vec3.crossProduct(right, forward));

    Mat4 orientation = new Mat4(1.0f);
    orientation.set(0, 0, right.x);
    orientation.set(0, 1, right.y);
    orientation.set(0, 2, right.z);
    orientation.set(1, 0, correctedUp.x);
    orientation.set(1, 1, correctedUp.y);
    orientation.set(1, 2, correctedUp.z);
    orientation.set(2, 0, -forward.x);
    orientation.set(2, 1, -forward.y);
    orientation.set(2, 2, -forward.z);

    Mat4 cameraOffset = translate(-from.x, -from.y, -from.z);
    return Mat4.multiply(orientation, cameraOffset);
  }
}