import com.xenoguesser.math.*;

/**
 * The player's eyes, on the sphere only: a planet-centred position, and a yaw (degrees from
 * east towards south, D10) and pitch (degrees up) in the local frame there. The world is
 * drawn relative to the camera (the shaders subtract sphereCamera), so the view is a pure
 * rotation. Walking carries the yaw along each step (parallel transport), so walking straight
 * follows a great circle, over a pole too.
 */
public class Camera {

  public final float KEYBOARD_SPEED = 60.0f;
  public final float MOUSE_SPEED = 100f;

  private SpherePosition position;
  private float yaw;
  private float pitch;

  private Matrix4 perspective;
  // The view, kept until the camera turns or moves: many things ask for it each frame
  private Matrix4 cachedView, cachedViewProjection;

  // Stored sea level variable populated by the procedural world values
  private final float seaLevelHeight;

  /** A camera at a place, facing east and level. */
  public Camera(SpherePosition position, float seaLevelHeight) {
    this.seaLevelHeight = seaLevelHeight;
    setSphereView(position, 0f, 0f);
  }

  /** The camera's position in planet-centred world coordinates. */
  public SpherePosition getSpherePosition() {
    return position;
  }

  /** Altitude above the spherical datum. */
  public float getAltitude() {
    return (float) (position.length() - Planet.radius());
  }

  /** The east/up/south frame at the camera. */
  public SphereFrame frame() {
    return SphereFrame.at(position);
  }

  /** Places the camera at a planet-centred position without changing its local heading. */
  public void setSpherePosition(SpherePosition newPosition) {
    double length = newPosition.length();
    if (!(length > 0.0)) throw new IllegalArgumentException("Camera cannot be placed at the planet centre");
    position = newPosition;
    invalidate();
  }

  /**
   * Places the camera at a planet-centred position, facing yaw degrees from east towards
   * south and pitch degrees up, in the local frame there.
   */
  public void setSphereView(SpherePosition newPosition, float yawDegrees, float pitchDegrees) {
    setSpherePosition(newPosition);
    yaw = yawDegrees;
    pitch = Math.max(-85f, Math.min(85f, pitchDegrees));
  }

  /** Turns the camera to look straight at a place. */
  public void lookAt(SpherePosition target) {
    double[] local = frame().toLocal(target);
    double level = Math.hypot(local[0], local[2]);
    if (level < 1e-9 && Math.abs(local[1]) < 1e-9) return;
    yaw = (float) Math.toDegrees(Math.atan2(local[2], local[0]));
    pitch = (float) Math.max(-85.0, Math.min(85.0, Math.toDegrees(Math.atan2(local[1], level))));
    invalidate();
  }

  /** Changes camera altitude radially. */
  public void setAltitude(float newY) {
    double length = position.length();
    double targetRadius = Planet.radius() + newY;
    if (!Float.isFinite(newY) || !(targetRadius > 0.0)) {
      throw new IllegalArgumentException("Camera altitude must be finite and above the planet centre");
    }
    position = position.scale(targetRadius / length);
    invalidate();
  }

  private void invalidate() {
    cachedView = null;
    cachedViewProjection = null;
  }

  /**
   * The camera-relative view: a plain look-at rotation for meshes stored in planet-centred
   * coordinates once the camera's position is taken off them.
   */
  public Matrix4 getSphereView() {
    if (cachedView != null) return cachedView;
    Vector3 forward = getSphereForwardDirection();
    Vector3 localUp = getSphereUpDirection();
    Vector3 right = Vector3.crossProduct(forward, localUp);
    right.normalize();
    Vector3 correctedUp = Vector3.crossProduct(right, forward);
    float[] values = new float[16];
    values[0] = right.x;
    values[1] = correctedUp.x;
    values[2] = -forward.x;
    values[4] = right.y;
    values[5] = correctedUp.y;
    values[6] = -forward.y;
    values[8] = right.z;
    values[9] = correctedUp.z;
    values[10] = -forward.z;
    values[15] = 1f;
    cachedView = Matrix4.fromColumns(values);
    return cachedView;
  }

  /** Projection times the camera-relative view. */
  public Matrix4 getSphereViewProjection() {
    if (cachedViewProjection == null) cachedViewProjection = Matrix4.multiply(perspective, getSphereView());
    return cachedViewProjection;
  }

  /**
   * A view-projection for things posed in the camera's local frame (x east, y up, z south,
   * the eyes at the origin), such as the player's own body, through the given projection.
   */
  public Matrix4 localViewProjection(Matrix4 projection) {
    return Matrix4.multiply(projection, Matrix4.multiply(getSphereView(), frame().rotationMatrix()));
  }

  public Vector3 getSphereForwardDirection() {
    return sphereDirection(yaw, pitch);
  }

  /** The way the view faces in the local frame (x east, y up, z south). */
  public float[] getLocalForward() {
    double yawRadians = Math.toRadians(yaw), pitchRadians = Math.toRadians(pitch);
    double horizontal = Math.cos(pitchRadians);
    return new float[] { (float) (horizontal * Math.cos(yawRadians)), (float) Math.sin(pitchRadians),
        (float) (horizontal * Math.sin(yawRadians)) };
  }

  public Vector3 getSphereUpDirection() {
    double length = position.length();
    return new Vector3((float) (position.x() / length), (float) (position.y() / length), (float) (position.z() / length));
  }

  public void setPerspectiveMatrix(Matrix4 m) {
    perspective = m;
    cachedViewProjection = null;
  }

  public Matrix4 getPerspectiveMatrix() {
    return perspective;
  }

  public void updatePosition(boolean w, boolean a, boolean s, boolean d, float deltaTime) {
    if (w || a || s || d) {
      // The way the keys go, as a heading from the way the view faces
      double forward = (w ? 1 : 0) - (s ? 1 : 0), aside = (d ? 1 : 0) - (a ? 1 : 0);
      if (forward == 0 && aside == 0) return;
      double heading = Math.toRadians(yaw) + Math.atan2(aside, forward);
      float currentSpeed = KEYBOARD_SPEED * deltaTime;
      // DYNAMIC WATER SLOWDOWN
      float groundClearance = getAltitude() - 20f;
      if (groundClearance < seaLevelHeight) {
          float depth = seaLevelHeight - groundClearance;
          float depthFactor = depth / 10.0f;
          if (depthFactor > 1.0f) {
              depthFactor = 1.0f;
          }
          float speedMultiplier = 1.0f - (depthFactor * 0.5f);
          currentSpeed *= speedMultiplier;
      }

      SpherePosition from = position;
      position = SphereMath.move(from, heading, currentSpeed);
      // The facing is carried along the step, so walking straight follows a great circle
      // (over a pole too) rather than holding a compass bearing
      yaw = (float) Math.toDegrees(SphereMath.carryHeading(from, position, Math.toRadians(yaw)));
      invalidate();
    }
  }

  // The tangent of half the view's height (the perspective's field of view, as zoomed)
  private volatile float tanHalfFov = (float) Math.tan(Math.toRadians(22.5));

  public float tanHalfFov() {
    return tanHalfFov;
  }

  public void setTanHalfFov(float tanHalfFov) {
    this.tanHalfFov = tanHalfFov;
  }

  public void updateYawPitch(float deltaX, float deltaY) {
    yaw += (deltaX * MOUSE_SPEED);
    pitch += (deltaY * MOUSE_SPEED);

    if (pitch > 85.0f) pitch = 85.0f;
    else if (pitch < -85.0f) pitch = -85.0f;
    invalidate();
  }

  /** The world direction at a yaw (degrees from east towards south) and pitch (degrees up). */
  private Vector3 sphereDirection(float yawDegrees, float pitchDegrees) {
    SphereFrame frame = frame();
    double yawRadians = Math.toRadians(yawDegrees), pitchRadians = Math.toRadians(pitchDegrees);
    double horizontal = Math.cos(pitchRadians);
    double[] d = frame.worldDirection(horizontal * Math.cos(yawRadians), Math.sin(pitchRadians),
        horizontal * Math.sin(yawRadians));
    return new Vector3((float) d[0], (float) d[1], (float) d[2]);
  }

  /** Which way the view faces across the ground, degrees from east towards south. */
  public float getYaw() {
    return yaw;
  }

  /** How far the view looks up (negative: down), in degrees. */
  public float getPitch() {
    return pitch;
  }
}
