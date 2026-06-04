import com.jogamp.opengl.*;
import com.jogamp.opengl.util.texture.*;

import gmaths.*;

public class Model {
  
  protected String name;
  protected Mesh mesh;
  protected Mat4 modelMatrix;
  protected Shader shader;
  protected Material material;
  protected Camera camera;
  protected Light[] lights;
  protected Renderer renderer;

  public Model() {
    name = null;
    mesh = null;
    modelMatrix = null;
    material = null;
    shader = null;
    renderer = null;
    lights = null;
    camera = null;
  }
  
  public Model(String name, Mesh mesh, Mat4 modelMatrix, Shader shader, 
    Material material, Renderer renderer,
    Light[] lights, Camera camera) {
    this.name = name;
    this.mesh = mesh;
    this.modelMatrix = modelMatrix;
    this.shader = shader;
    this.material = material;
    this.renderer = renderer;
    this.lights = lights;
    this.camera = camera;
  }

  public void setName(String s) {
    this.name = s;
  }

  public void setMesh(Mesh m) {
    this.mesh = m;
  }

  public Mesh getMesh() {
    return mesh;
  }

  public void setModelMatrix(Mat4 m) {
    modelMatrix = m;
  }
  
  public void setMaterial(Material material) {
    this.material = material;
  }

  public Material getMaterial() {
    return material;
  }

  public void setShader(Shader shader) {
    this.shader = shader;
  }

  public Shader getShader() {
    return shader;
  }
  
  public void setLight(Light[] lights) {
    this.lights = lights;
  }

  public Light[] getLights() {
    return lights;
  }

  public void displayName(GL3 gl) {
    System.out.println("Name = "+name);  
  }

  // UPDATED: Spotlight parameters stripped out to match new Renderer footprint
  public void render(GL3 gl, Vec3 ambientLight, float nightProportion) {
    renderer.render(gl, mesh, modelMatrix, shader, material, lights, ambientLight, nightProportion, camera);
  }

  // UPDATED: Second version with overridden modelMatrix also stripped of spotlights
  public void render(GL3 gl, Mat4 modelMatrix, Vec3 ambientLight, float nightProportion) {
    if (mesh_null()) {
      System.out.println("Error: null in model render");
      return;
    }
    renderer.render(gl, mesh, modelMatrix, shader, material, lights, ambientLight, nightProportion, camera);
  }

  public void renderWithShader(GL3 gl, Shader alternativeShader, Vec3 ambientLight, float nightProportion) {
    if (mesh == null) return;
    
    // Convert your Mat4 modelMatrix into the flat float[] using Dr. Maddock's exact method
    float[] matrixValues = this.modelMatrix.toFloatArrayForGLSL(); 
    
    // Pass the raw float array to your shader's existing method
    alternativeShader.setFloatArray(gl, "model", matrixValues);
    
    // Draw the mesh structure
    mesh.render(gl);
  }
  
  private boolean mesh_null() {
    return (mesh==null);
  }

  public void renderDepthPass(GL3 gl, Shader alternativeShader, Mat4 viewProjection) {
    if (mesh == null) return;
    
    // 1. Calculate the final Model-View-Projection matrix for this specific chunk mesh
    // MVP = Projection * View * Model
    Mat4 mvpMatrix = Mat4.multiply(viewProjection, this.modelMatrix);
    
    // 2. Convert both matrices to GLSL flat arrays using Dr. Maddock's native method
    float[] modelValues = this.modelMatrix.toFloatArrayForGLSL();
    float[] mvpValues = mvpMatrix.toFloatArrayForGLSL();
    
    // 3. Upload them into the uniform variables expected by vs_standard.txt
    alternativeShader.setFloatArray(gl, "model", modelValues);
    alternativeShader.setFloatArray(gl, "mvpMatrix", mvpValues);
    
    // 4. Draw the mesh geometry raw
    mesh.render(gl);
  }
  
}