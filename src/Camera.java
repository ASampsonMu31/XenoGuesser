import com.xenoguesser.math.*;

public class Camera {
  
  public enum CameraType {X, Z};
  public enum Movement {
    NO_MOVEMENT, FORWARD, BACK, LEFT, RIGHT, UP, DOWN
  };
  
  public static final Vector3 DEFAULT_POSITION = new Vector3(0, 0, 25);
  public static final Vector3 DEFAULT_TARGET = new Vector3(0, 0, 0);
  public static final Vector3 DEFAULT_UP = new Vector3(0, 1, 0);

  public final float KEYBOARD_SPEED;
  
  // Preserved at 100f to match your drag inputs scaling factor
  public final float MOUSE_SPEED = 100f;
  
  private Vector3 position;
  private SpherePosition sphericalPosition;
  private boolean chartPositionDirty;
  private Vector3 target;
  private Vector3 up;
  private Vector3 worldUp; // Stays permanently locked as your global sky axis
  private Vector3 front;
  private Vector3 right;
  
  private float yaw;
  private float pitch;
  
  private Matrix4 perspective;
  
  // Stored sea level variable populated by the procedural world values
  private final float seaLevelHeight;

  public Camera(Vector3 position, Vector3 target, Vector3 up, float seaLevelHeight) {
    // Lock down worldUp immediately upon creation so it never drifts
    this.worldUp = new Vector3(up);
    this.worldUp.normalize();
    this.seaLevelHeight = seaLevelHeight; // Save the dynamic sea level map constraint
    setupCamera(position, target);
    KEYBOARD_SPEED = 60.0f;
  }

  
  private void setupCamera(Vector3 position, Vector3 target) {
    this.position = new Vector3(position);
    this.target = new Vector3(target);
    syncSphericalPosition();
    
    front = Vector3.subtract(target, position);
    front.normalize();
    
    calculateYawPitch(front);
    updateCameraVectors();
  }
  
  /** Mercator projection for legacy UI and systems; sphere-native simulation uses getSpherePosition(). */
  public Vector3 getPosition() {
    syncChartPosition();
    return new Vector3(position);
  }

  /** The camera's authoritative position in planet-centred world coordinates. */
  public SpherePosition getSpherePosition() {
    return sphericalPosition;
  }

  /** Altitude above the spherical datum, without projecting the position onto a map chart. */
  public float getAltitude() {
    return (float) (sphericalPosition.length() - Planet.radius());
  }

  /** Places the camera at a planet-centred position without changing its local heading. */
  public void setSpherePosition(SpherePosition position) {
    double length = position.length();
    if (!(length > 0.0)) throw new IllegalArgumentException("Camera cannot be placed at the planet centre");
    sphericalPosition = position;
    chartPositionDirty = true;
    cachedView = null;
    cachedViewProjection = null;
  }

  /** Camera view-projection for meshes stored in planet-centred Cartesian coordinates. */
  public Matrix4 getSphereViewProjection() {
    Vector3 forward = getSphereForwardDirection();
    Vector3 localUp = getSphereUpDirection();
    Vector3 right = Vector3.crossProduct(localUp, forward);
    right.normalize();
    Vector3 correctedUp = Vector3.crossProduct(forward, right);
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
    Matrix4 view = Matrix4.fromColumns(values);
    return Matrix4.multiply(perspective, view);
  }

  public Vector3 getSphereForwardDirection() {
    return sphereDirection(yaw, pitch);
  }

  public Vector3 getSphereUpDirection() {
    return sphereUp();
  }

  public Vector3 getForwardDirection() {
    return new Vector3(front);
  }
  
  // FIX: Directly updates positions and recomputes matrices without touching or corrupting worldUp
  public void setPosition(Vector3 p) {
    this.position = new Vector3(p);
    chartPositionDirty = false;
    syncSphericalPosition();
    front = Vector3.subtract(target, position);
    front.normalize();
    calculateYawPitch(front);
    updateCameraVectors();
  }
  
  // FIX: Directly updates target and recomputes matrices without touching or corrupting worldUp
  public void setTarget(Vector3 t) {
    syncChartPosition();
    this.target = new Vector3(t);
    front = Vector3.subtract(t, position);
    front.normalize();
    calculateYawPitch(front);
    updateCameraVectors();
  }

  private void calculateYawPitch(Vector3 v) {
    yaw = (float) Math.toDegrees(Math.atan2(v.z, v.x));
    pitch = (float) Math.toDegrees(Math.asin(v.y));
  }

  // The view and view-projection, kept until the camera or its lens changes: thousands of
  // things are drawn each frame and each asked for them
  private Matrix4 cachedView, cachedViewProjection, cachedFor;
  private final float[] cachedKey = new float[9];

  public Matrix4 getViewMatrix() {
    syncChartPosition();
    refreshCache();
    return cachedView;
  }

  /** Projection times view, for this frame. */
  public Matrix4 getViewProjection() {
    syncChartPosition();
    refreshCache();
    if (cachedViewProjection == null || cachedFor != perspective) {
      cachedViewProjection = Matrix4.multiply(perspective, cachedView);
      cachedFor = perspective;
    }
    return cachedViewProjection;
  }

  private void refreshCache() {
    float[] key = { position.x, position.y, position.z, front.x, front.y, front.z, up.x, up.y, up.z };
    if (cachedView != null && java.util.Arrays.equals(key, cachedKey)) return;
    System.arraycopy(key, 0, cachedKey, 0, 9);
    target = Vector3.add(position, front);
    cachedView = Matrix4Transform.lookAt(position, target, up);
    cachedViewProjection = null;
  }
  
  public void setPerspectiveMatrix(Matrix4 m) {
    perspective = m;
    cachedViewProjection = null;
  }
  
  public Matrix4 getPerspectiveMatrix() {
    return perspective;
  }
 
  public void updatePosition(boolean w, boolean a, boolean s, boolean d, float deltaTime) {
    Vector3 movementDirection = new Vector3(0, 0, 0);

    if (w || a || s || d) {
    double heading = Math.toRadians(yaw);
    double east = Math.cos(heading);
    double south = Math.sin(heading);
    double rightEast = -Math.sin(heading);
    double rightSouth = Math.cos(heading);
    if (w) movementDirection.add(new Vector3((float) east, 0f, (float) south));
    if (s) movementDirection.add(new Vector3((float) -east, 0f, (float) -south));
    if (d) movementDirection.add(new Vector3((float) rightEast, 0f, (float) rightSouth));
    if (a) movementDirection.add(new Vector3((float) -rightEast, 0f, (float) -rightSouth));
    movementDirection.normalize();
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
      
      sphericalPosition = Planet.moveOnSurface(sphericalPosition,
          movementDirection.x, movementDirection.z, currentSpeed);
      chartPositionDirty = true;
      cachedView = null;
      cachedViewProjection = null;
    }
  }

  public void updateYawPitch(float deltaX, float deltaY) {
    yaw += (deltaX * MOUSE_SPEED);
    pitch += (deltaY * MOUSE_SPEED);
    
    if (pitch > 85.0f) pitch = 85.0f;
    else if (pitch < -85.0f) pitch = -85.0f;
    
    updateFront();
    updateCameraVectors();
  }
  
  private void updateFront() {
    double cy, cp, sy, sp;
    cy = Math.cos(Math.toRadians(yaw));
    sy = Math.sin(Math.toRadians(yaw));
    cp = Math.cos(Math.toRadians(pitch));
    sp = Math.sin(Math.toRadians(pitch));
    
    front.x = (float) (cy * cp);
    front.y = (float) (sp);
    front.z = (float) (sy * cp);
    front.normalize();
    target = Vector3.add(position, front);
  }
  
  private void updateCameraVectors() {  
    right = Vector3.crossProduct(front, worldUp);
    right.normalize();
    up = Vector3.crossProduct(right, front);
    up.normalize();
  }

  private Vector3 sphereUp() {
    double length = sphericalPosition.length();
    return new Vector3((float) (sphericalPosition.x() / length),
            (float) (sphericalPosition.y() / length),
            (float) (sphericalPosition.z() / length));
  }

  private Vector3 sphereDirection(float yawDegrees, float pitchDegrees) {
    double length = sphericalPosition.length();
    double lat = Math.asin(sphericalPosition.y() / length);
    double lon = Math.atan2(sphericalPosition.z(), sphericalPosition.x());
    double eastX = -Math.sin(lon), eastZ = Math.cos(lon);
    double northX = -Math.sin(lat) * Math.cos(lon);
    double northY = Math.cos(lat);
    double northZ = -Math.sin(lat) * Math.sin(lon);
    double upX = Math.cos(lat) * Math.cos(lon), upY = Math.sin(lat), upZ = Math.cos(lat) * Math.sin(lon);
    double yawRadians = Math.toRadians(yawDegrees), pitchRadians = Math.toRadians(pitchDegrees);
    double horizontal = Math.cos(pitchRadians);
    double eastAmount = horizontal * Math.cos(yawRadians);
    double northAmount = -horizontal * Math.sin(yawRadians);
    return new Vector3((float) (eastAmount * eastX + northAmount * northX + Math.sin(pitchRadians) * upX),
            (float) (northAmount * northY + Math.sin(pitchRadians) * upY),
            (float) (eastAmount * eastZ + northAmount * northZ + Math.sin(pitchRadians) * upZ));
  }

  /** Which way the view faces across the ground, degrees from +x towards +z. */
  public float getYaw() {
    return yaw;
  }

  /** Turns the view about the vertical by this many degrees (towards +z). */
  public void turn(float degrees) {
    yaw += degrees;
    updateFront();
    updateCameraVectors();
  }

  /** Moves across the ground without turning the view. */
  public void setGroundPosition(float x, float z) {
    syncChartPosition();
    this.position.x = x;
    this.position.z = z;
    this.target = Vector3.add(this.position, this.front);
    chartPositionDirty = false;
    syncSphericalPosition();
  }

  public void setHeight(float newY) {
    setAltitude(newY);
  }

  /** Changes camera altitude radially without computing a Mercator chart position. */
  public void setAltitude(float newY) {
    double length = sphericalPosition.length();
    double targetRadius = Planet.radius() + newY;
    if (!Float.isFinite(newY) || !(targetRadius > 0.0)) {
      throw new IllegalArgumentException("Camera altitude must be finite and above the planet centre");
    }
    sphericalPosition = sphericalPosition.scale(targetRadius / length);
    chartPositionDirty = true;
    cachedView = null;
    cachedViewProjection = null;
  }

  private void syncSphericalPosition() {
    sphericalPosition = Planet.spherePosition(position.x, position.z, position.y);
  }

  private void syncChartPosition() {
    if (!chartPositionDirty) return;
    double[] mapPosition = Planet.mercatorPosition(sphericalPosition);
    position = new Vector3((float) mapPosition[0], (float) mapPosition[1], (float) mapPosition[2]);
    target = Vector3.add(position, front);
    chartPositionDirty = false;
  }
}