import java.nio.*;
import com.jogamp.common.nio.*;
import com.jogamp.opengl.*;
import com.jogamp.opengl.util.texture.Texture;

import com.xenoguesser.math.*;
  
public class Light {
  
  private Material material;
  private Vector3 position;
  private Matrix4 modelMatrix;
  private Shader shader;
  private Camera camera;
  private Mesh mesh;
  private boolean isSun;
  private float size;
  private float brightnessProportion;
  private Texture glowTexture; 
  
  public Light(GL3 gl, Camera camera, boolean isSun, Vector3 position, float size) {
    this(gl, MaterialConstants.dullWhiteLightSource, position, camera, isSun, size, null);
  }

  public Light(GL3 gl, Camera camera, boolean isSun, Vector3 position, float size, Texture glowTexture) {
    this(gl, MaterialConstants.dullWhiteLightSource, position, camera, isSun, size, glowTexture);
  }

  public Light(GL3 gl, Material material, Vector3 position, Camera camera, boolean isSun, float size, Texture glowTexture) {
    this.material = material;
    this.position = position;
    this.camera = camera;
    this.isSun = isSun;
    this.size = size;
    this.brightnessProportion = 1;
    this.glowTexture = glowTexture;

    modelMatrix = new Matrix4(1);

    shader = new Shader(gl, "assets/shaders/vs_light_01.txt", "assets/shaders/fs_light_01.txt");
    
    // Using TwoTriangles quad to completely eliminate polar pinching artifacting
    mesh = new Mesh(gl, TwoTriangles.vertices, TwoTriangles.indices);
  }
  
  public void setPosition(Vector3 v) {
    position.x = v.x;
    position.y = v.y;
    position.z = v.z;
  }
  
  public void setPosition(float x, float y, float z) {
    position.x = x;
    position.y = y;
    position.z = z;
  }
  
  public Vector3 getPosition() {
    return position;
  }

  public boolean getIsSun() {
    return isSun;
  }
  
  public void setMaterial(Material m) {
    material = m;
  }
  
  public Material getMaterial() {
    return material;
  }

  public void setBrightnessProportion(float brightnessProportion) {
    this.brightnessProportion = brightnessProportion;
  }

  public float getBrightnessProportion() {
    return brightnessProportion;
  }
  
  public void setCamera(Camera camera) {
    this.camera = camera;
  }

  public Vector3 scaleUpColour(Vector3 colour) {
    Vector3 materialFullDiffuse = material.getFullDiffuse();
    float sf = 1 / Math.max(Math.max(materialFullDiffuse.x, materialFullDiffuse.y), materialFullDiffuse.z);
    return new Vector3(Math.min(colour.x * sf , 1),
                    Math.min(colour.y * sf , 1),
                    Math.min(colour.z * sf , 1));
  }
  
  public void render(GL3 gl) { 
    shader.use(gl);

    if (glowTexture != null) {
        gl.glActiveTexture(GL.GL_TEXTURE0);
        glowTexture.bind(gl);
        shader.setInt(gl, "sun_texture", 0);
    }

    // 1. Compute the direct vector from the Player Camera position to the Sun position
    // FIX: Reversing this direction vectors solves the backface culling problem!
    Vector3 camPos = camera.getPosition();
    float lookX = position.x - camPos.x;
    float lookY = position.y - camPos.y;
    float lookZ = position.z - camPos.z;
    
    // Normalize the look vector
    float lookLen = (float)Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
    if (lookLen > 0.0f) {
        lookX /= lookLen; lookY /= lookLen; lookZ /= lookLen;
    }

    // 2. Establish a world reference vector
    float refX = 0.0f; float refY = 1.0f; float refZ = 0.0f;
    if (Math.abs(lookY) > 0.9f) {
        refY = 0.0f; refZ = 1.0f; 
    }

    // 3. Compute local X direction vector (cross product)
    float sideX = refY * lookZ - refZ * lookY;
    float sideY = refZ * lookX - refX * lookZ;
    float sideZ = refX * lookY - refY * lookX;
    float sideLen = (float)Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
    if (sideLen > 0.0f) {
        sideX /= sideLen; sideY /= sideLen; sideZ /= sideLen;
    }

    // 4. Compute local Z direction vector (cross product)
    float upX = lookY * sideZ - lookZ * sideY;
    float upY = lookZ * sideX - lookX * sideZ;
    float upZ = lookX * sideY - lookY * sideX;

    // 5. Build position-aligned orientation matrix
    Matrix4 positionBillboard = new Matrix4(1);
    // Column 0 maps local X 
    positionBillboard.set(0, 0, sideX);
    positionBillboard.set(1, 0, sideY);
    positionBillboard.set(2, 0, sideZ);
    // Column 1 maps local Y (The quad's natural upward normal vector)
    positionBillboard.set(0, 1, lookX);
    positionBillboard.set(1, 1, lookY);
    positionBillboard.set(2, 1, lookZ);
    // Column 2 maps local Z
    positionBillboard.set(0, 2, upX);
    positionBillboard.set(1, 2, upY);
    positionBillboard.set(2, 2, upZ);

    // 6. Combine transformations
    Matrix4 localMM = Matrix4Transform.translate(position);
    localMM = Matrix4.multiply(localMM, positionBillboard);
    localMM = Matrix4.multiply(localMM, Matrix4Transform.scale(this.size, 1.0f, this.size));
    
    // Calculate final matrices
    Matrix4 mvMatrix = Matrix4.multiply(camera.getViewMatrix(), localMM);
    Matrix4 mvpMatrix = Matrix4.multiply(camera.getPerspectiveMatrix(), mvMatrix);
    
    shader.setFloatArray(gl, "mvpMatrix", mvpMatrix.toFloatArrayForGLSL());
    
    mesh.render(gl);
  }
}