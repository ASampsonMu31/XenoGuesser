import gmaths.*;

public class Camera {
  
  public enum CameraType {X, Z};
  public enum Movement {
    NO_MOVEMENT, FORWARD, BACK, LEFT, RIGHT, UP, DOWN
  };
  
  public static final Vec3 DEFAULT_POSITION = new Vec3(0, 0, 25);
  public static final Vec3 DEFAULT_TARGET = new Vec3(0, 0, 0);
  public static final Vec3 DEFAULT_UP = new Vec3(0, 1, 0);

  public final float KEYBOARD_SPEED;
  
  // Preserved at 100f to match your drag inputs scaling factor
  public final float MOUSE_SPEED = 100f;
  
  private Vec3 position;
  private Vec3 target;
  private Vec3 up;
  private Vec3 worldUp; // Stays permanently locked as your global sky axis
  private Vec3 front;
  private Vec3 right;
  
  private float yaw;
  private float pitch;
  
  private Mat4 perspective;

  public Camera(Vec3 position, Vec3 target, Vec3 up, boolean IS_DEVELOPMENT_MODE) {
    // Lock down worldUp immediately upon creation so it never drifts
    this.worldUp = new Vec3(up);
    this.worldUp.normalize();
    setupCamera(position, target);
    if (IS_DEVELOPMENT_MODE) {
      KEYBOARD_SPEED = 60.0f;
    }
    else {
      KEYBOARD_SPEED = 15.0f;
    }
  }
  
  private void setupCamera(Vec3 position, Vec3 target) {
    this.position = new Vec3(position);
    this.target = new Vec3(target);
    
    front = Vec3.subtract(target, position);
    front.normalize();
    
    calculateYawPitch(front);
    updateCameraVectors();
  }
  
  public Vec3 getPosition() {
    return new Vec3(position);
  }

  public Vec3 getForwardDirection() {
    return new Vec3(front);
  }
  
  // FIX: Directly updates positions and recomputes matrices without touching or corrupting worldUp
  public void setPosition(Vec3 p) {
    this.position = new Vec3(p);
    front = Vec3.subtract(target, position);
    front.normalize();
    calculateYawPitch(front);
    updateCameraVectors();
  }
  
  // FIX: Directly updates target and recomputes matrices without touching or corrupting worldUp
  public void setTarget(Vec3 t) {
    this.target = new Vec3(t);
    front = Vec3.subtract(t, position);
    front.normalize();
    calculateYawPitch(front);
    updateCameraVectors();
  }

  private void calculateYawPitch(Vec3 v) {
    yaw = (float) Math.toDegrees(Math.atan2(v.z, v.x));
    pitch = (float) Math.toDegrees(Math.asin(v.y));
  }

  public Mat4 getViewMatrix() {
    target = Vec3.add(position, front);
    return Mat4Transform.lookAt(position, target, up);
  }
  
  public void setPerspectiveMatrix(Mat4 m) {
    perspective = m;
  }
  
  public Mat4 getPerspectiveMatrix() {
    return perspective;
  }
 
  public void updatePosition(boolean w, boolean a, boolean s, boolean d, float deltaTime) {
    Vec3 movementDirection = new Vec3(0, 0, 0);

    Vec3 flatFront = new Vec3(front.x, 0.0f, front.z);
    flatFront.normalize();
    
    Vec3 flatRight = new Vec3(right.x, 0.0f, right.z);
    flatRight.normalize();

    if (w) movementDirection.add(flatFront);
    if (s) movementDirection.add(Vec3.multiply(flatFront, -1.0f));
    if (d) movementDirection.add(flatRight);
    if (a) movementDirection.add(Vec3.multiply(flatRight, -1.0f));

    if (w || a || s || d) {
      movementDirection.normalize();
      float currentSpeed = KEYBOARD_SPEED * deltaTime; 
      Vec3 velocity = Vec3.multiply(movementDirection, currentSpeed);
      
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
    target = Vec3.add(position, front);
  }
  
  private void updateCameraVectors() {  
    right = Vec3.crossProduct(front, worldUp);
    right.normalize();
    up = Vec3.crossProduct(right, front);
    up.normalize();
  }

  public void setHeight(float newY) {
    this.position.y = newY;
    this.target = Vec3.add(this.position, this.front);
}

}