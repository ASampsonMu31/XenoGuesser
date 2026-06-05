import gmaths.*;

public class Camera {
  
  public enum CameraType {X, Z};
  public enum Movement {
    NO_MOVEMENT, FORWARD, BACK, LEFT, RIGHT, UP, DOWN
  };
  
  public static final Vec3 DEFAULT_POSITION = new Vec3(0, 0, 25);
  public static final Vec3 DEFAULT_TARGET = new Vec3(0, 0, 0);
  public static final Vec3 DEFAULT_UP = new Vec3(0, 1, 0);

  public final float KEYBOARD_SPEED = 1.0f;
  public final float MOUSE_SPEED = 100.0f;
  
  private Vec3 position;
  private Vec3 target;
  private Vec3 up;
  private Vec3 worldUp; // Dynamically updates to match the current planet surface normal
  private Vec3 front;
  private Vec3 right;
  
  private Mat4 perspective;

  public Camera(Vec3 position, Vec3 target, Vec3 up) {
    this.worldUp = new Vec3(up);
    this.worldUp.normalize();
    setupCamera(position, target);
  }
  
  private void setupCamera(Vec3 position, Vec3 target) {
    this.position = new Vec3(position);
    this.target = new Vec3(target);
    
    front = Vec3.subtract(target, position);
    front.normalize();
    
    updateCameraVectors();
  }
  
  public Vec3 getPosition() {
    return new Vec3(position);
  }
  
  public Vec3 getTarget() {
    return new Vec3(target);
  }

  public Vec3 getFront() {
    return new Vec3(front);
  }
  
  // Safely moves position while preserving current look vectors across the sphere
  public void setPosition(Vec3 p) {
    this.position = new Vec3(p);
    this.target = Vec3.add(this.position, this.front);
    updateCameraVectors();
  }
  
  // Safely sets look targets and re-aligns local space frame
  public void setTarget(Vec3 t) {
    this.target = new Vec3(t);
    front = Vec3.subtract(t, position);
    front.normalize();
    updateCameraVectors();
  }

  public void setWorldUp(Vec3 newWorldUp) {
    newWorldUp.normalize();
    
    // 1. If this is the first frame or worldUp isn't initialized, baseline it
    if (this.worldUp == null) {
        this.worldUp = new Vec3(newWorldUp);
        return;
    }

    // 2. Calculate the actual axis of curvature
    Vec3 rotationAxis = Vec3.crossProduct(this.worldUp, newWorldUp);
    float axisLen = rotationAxis.length();
    
    // CRITICAL: Lower the threshold to 1e-7 to capture microscopic frame steps
    if (axisLen > 0.0000001f) {
        rotationAxis.normalize();
        float dot = Vec3.dotProduct(this.worldUp, newWorldUp);
        dot = Math.max(-1.0f, Math.min(1.0f, dot)); 
        float angleDegrees = (float) Math.toDegrees(Math.acos(dot));
        
        // Tilt look direction and local frame downward over the planet edge
        this.front = rotateVectorAroundAxis(this.front, rotationAxis, angleDegrees);
        this.up = rotateVectorAroundAxis(this.up, rotationAxis, angleDegrees);
    }
    
    // 3. Update the persistent reference state for the next frame's comparison
    this.worldUp = new Vec3(newWorldUp);
    
    // Recompute local horizontal spaces cleanly
    right = Vec3.crossProduct(front, worldUp);
    right.normalize();
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

    // Project front/right vectors onto the localized planet horizon surface plane
    float frontDot = Vec3.dotProduct(front, worldUp);
    Vec3 flatFront = Vec3.subtract(front, Vec3.multiply(worldUp, frontDot));
    flatFront.normalize();
    
    float rightDot = Vec3.dotProduct(right, worldUp);
    Vec3 flatRight = Vec3.subtract(right, Vec3.multiply(worldUp, rightDot));
    flatRight.normalize();

    if (w) movementDirection.add(flatFront);
    if (s) movementDirection.add(Vec3.multiply(flatFront, -1.0f));
    if (d) movementDirection.add(flatRight);
    if (a) movementDirection.add(Vec3.multiply(flatRight, -1.0f));

    if (w || a || s || d) {
      movementDirection.normalize();
      float currentSpeed = 15.0f * deltaTime; 
      Vec3 velocity = Vec3.multiply(movementDirection, currentSpeed);
      
      position.add(velocity);
    }
  }

  // Pure vector-space mouse updates avoiding flat Euler angles/gimbal lock entirely
  public void updateYawPitch(float deltaX, float deltaY) {
    // 1. Look Left/Right: Rotate front vector around current ground normal
    front = rotateVectorAroundAxis(front, worldUp, -deltaX * MOUSE_SPEED);
    
    // CRITICAL FIX: Recompute the right vector IMMEDIATELY after yawing
    // so the pitch rotation happens around the fresh, updated horizon axis!
    right = Vec3.crossProduct(front, worldUp);
    right.normalize();
    
    // 2. Look Up/Down: Rotate front vector around local horizon side-axis (right)
    Vec3 proposedFront = rotateVectorAroundAxis(front, right, -deltaY * MOUSE_SPEED);
    
    // Safety check: Clamp pitch to avoid full inversion or camera flipping
    float dot = Vec3.dotProduct(proposedFront, worldUp);
    if (Math.abs(dot) < 0.95f) { 
        front = proposedFront;
    }
    
    updateCameraVectors();
  }
  
  private void updateCameraVectors() {  
    right = Vec3.crossProduct(front, worldUp);
    right.normalize();
    up = Vec3.crossProduct(right, front);
    up.normalize();
  }

  // Rodrigues' Rotation Formula implementation for safe vector transformations
  private Vec3 rotateVectorAroundAxis(Vec3 v, Vec3 axis, float angleDegrees) {
    float angleRadians = (float) Math.toRadians(angleDegrees);
    float cosTheta = (float) Math.cos(angleRadians);
    float sinTheta = (float) Math.sin(angleRadians);
    
    Vec3 term1 = Vec3.multiply(v, cosTheta);
    Vec3 term2 = Vec3.multiply(Vec3.crossProduct(axis, v), sinTheta);
    Vec3 term3 = Vec3.multiply(axis, Vec3.dotProduct(axis, v) * (1.0f - cosTheta));
    
    Vec3 sum = Vec3.add(term1, term2);
    return Vec3.normalize(Vec3.add(sum, term3));
  }

  // Changes the camera's absolute position on the sphere without altering look vectors 
  public void setRawPosition(Vec3 newPos) {
      this.position = new Vec3(newPos);
      // Do NOT recalculate target or front here; let the existing front vector remain intact.
  }
}