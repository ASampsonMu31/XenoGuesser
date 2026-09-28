import com.jogamp.opengl.*;
import com.jogamp.opengl.util.texture.*;
import com.xenoguesser.math.*;

public class Model {
  
  protected String name;
  protected Mesh mesh;
  protected Matrix4 modelMatrix;
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
  
  public Model(String name, Mesh mesh, Matrix4 modelMatrix, Shader shader, 
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

  public void setModelMatrix(Matrix4 m) {
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

  public void render(GL3 gl, Vector3 ambientLight, float nightProportion) {
    renderer.render(gl, mesh, modelMatrix, shader, material, lights, ambientLight, nightProportion, camera);
  }

  public void render(GL3 gl, Matrix4 modelMatrix, Vector3 ambientLight, float nightProportion) {
    if (mesh_null()) {
      System.out.println("Error: null in model render");
      return;
    }
    renderer.render(gl, mesh, modelMatrix, shader, material, lights, ambientLight, nightProportion, camera);
  }

  public void renderWithShader(GL3 gl, Shader alternativeShader, Vector3 ambientLight, float nightProportion) {
    if (mesh == null) return;
    
    float[] matrixValues = this.modelMatrix.toFloatArrayForGLSL(); 
    alternativeShader.setFloatArray(gl, "model", matrixValues);
    mesh.render(gl);
  }
  
  private boolean mesh_null() {
    return (mesh==null);
  }

  public void renderDepthPass(GL3 gl, Shader alternativeShader, Matrix4 viewProjection) {
    if (mesh == null) return;
    
    Matrix4 mvpMatrix = Matrix4.multiply(viewProjection, this.modelMatrix);
    
    float[] modelValues = this.modelMatrix.toFloatArrayForGLSL();
    float[] mvpValues = mvpMatrix.toFloatArrayForGLSL();
    
    alternativeShader.setFloatArray(gl, "model", modelValues);
    alternativeShader.setFloatArray(gl, "mvpMatrix", mvpValues);
    
    // NEW: Bind texture for alpha testing in the depth pass
    if (material != null && material.getDiffuseMap() != null) {
        gl.glActiveTexture(GL3.GL_TEXTURE0);
        material.getDiffuseMap().bind(gl);
        alternativeShader.setInt(gl, "diffuseMap", 0);
    }
    
    mesh.render(gl);
  }
}