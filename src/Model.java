import com.jogamp.opengl.*;
import com.xenoguesser.math.*;

public class Model {

  protected String name;
  protected Mesh mesh;
  protected Matrix4 modelMatrix;
  protected Shader shader;
  protected Material material;
  protected Camera camera;
  protected Renderer renderer;

  public Model() {
    name = null;
    mesh = null;
    modelMatrix = null;
    material = null;
    shader = null;
    renderer = null;
    camera = null;
  }

  public Model(String name, Mesh mesh, Matrix4 modelMatrix, Shader shader,
    Material material, Renderer renderer, Camera camera) {
    this.name = name;
    this.mesh = mesh;
    this.modelMatrix = modelMatrix;
    this.shader = shader;
    this.material = material;
    this.renderer = renderer;
    this.camera = camera;
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

  /** Draws the model on the planet as the camera sees it (its model matrix planet-centred; see Renderer). */
  public void render(GL3 gl, Vector3 ambientLight, float nightProportion) {
    renderer.render(gl, mesh, modelMatrix, shader, material, ambientLight, nightProportion, camera);
  }

  /** Draws the model through a view-projection of its own (a picture of it on its own), seen from viewPosition. */
  public void renderWith(GL3 gl, Vector3 ambientLight, Matrix4 viewProjection, Vector3 viewPosition) {
    renderer.render(gl, mesh, modelMatrix, shader, material, ambientLight, viewProjection, viewPosition);
  }

  /** Draws only the model's depth as the camera sees it, with a depth shader of its own (in use). */
  public void renderDepthPass(GL3 gl, Shader depthShader) {
    if (mesh == null) return;
    SpherePosition eye = camera.getSpherePosition();
    Renderer.setView(gl, depthShader, modelMatrix, camera.getSphereViewProjection(),
            new Vector3((float) eye.x(), (float) eye.y(), (float) eye.z()));
    mesh.render(gl);
  }
}
