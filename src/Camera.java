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
    
    front = Vector3.subtract(target, position);
    front.normalize();
    
    calculateYawPitch(front);
    updateCameraVectors();
  }
  
  public Vector3 getPosition() {
    return new Vector3(position);
  }

  public Vector3 getForwardDirection() {
    return new Vector3(front);
  }
  
  // FIX: Directly updates positions and recomputes matrices without touching or corrupting worldUp
  public void setPosition(Vector3 p) {
    this.position = new Vector3(p);
    front = Vector3.subtract(target, position);
    front.normalize();
    calculateYawPitch(front);
    updateCameraVectors();
  }
  
  // FIX: Directly updates target and recomputes matrices without touching or corrupting worldUp
  public void setTarget(Vector3 t) {
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

  public Matrix4 getViewMatrix() {
    target = Vector3.add(position, front);
    return Matrix4Transform.lookAt(position, target, up);
  }
  
  public void setPerspectiveMatrix(Matrix4 m) {
    perspective = m;
  }
  
  public Matrix4 getPerspectiveMatrix() {
    return perspective;
  }
 
  public void updatePosition(boolean w, boolean a, boolean s, boolean d, float deltaTime) {
    Vector3 movementDirection = new Vector3(0, 0, 0);

    Vector3 flatFront = new Vector3(front.x, 0.0f, front.z);
    flatFront.normalize();
    
    Vector3 flatRight = new Vector3(right.x, 0.0f, right.z);
    flatRight.normalize();

    if (w) movementDirection.add(flatFront);
    if (s) movementDirection.add(Vector3.multiply(flatFront, -1.0f));
    if (d) movementDirection.add(flatRight);
    if (a) movementDirection.add(Vector3.multiply(flatRight, -1.0f));

    if (w || a || s || d) {
      movementDirection.normalize();
      float currentSpeed = KEYBOARD_SPEED * deltaTime; 
      // DYNAMIC WATER SLOWDOWN
      if (this.position.y - 20 < seaLevelHeight) {
          float depth = seaLevelHeight - (this.position.y - 20);
          float depthFactor = depth / 10.0f;
          if (depthFactor > 1.0f) {
              depthFactor = 1.0f;
          }
          float speedMultiplier = 1.0f - (depthFactor * 0.5f);
          currentSpeed *= speedMultiplier;
      }
      
      Vector3 velocity = Vector3.multiply(movementDirection, currentSpeed);
      position.add(velocity);
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

  /** Moves across the ground without turning the view. */
  public void setGroundPosition(float x, float z) {
    this.position.x = x;
    this.position.z = z;
    this.target = Vector3.add(this.position, this.front);
  }

  public void setHeight(float newY) {
    this.position.y = newY;
    this.target = Vector3.add(this.position, this.front);
  }
}