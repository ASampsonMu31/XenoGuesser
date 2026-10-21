import com.jogamp.opengl.*;
import com.jogamp.opengl.util.texture.*;

import com.xenoguesser.math.*;

public class Renderer {

  public Vector2 scale = new Vector2(1, 1);

  // The lights the world is lit by (the sun), as the camera sees it: their positions relative to the eyes
  private Light[] lights = new Light[0];

  public Renderer() {}

  // While the sun's shadows are drawn (see ShadowMaps): the shaders things are drawn with instead
  // of their own (only their depth matters), and the one leafy things' shaders stand for
  private static Shader casterShader, cutoutCasterShader;
  private static java.util.Set<Shader> cutoutShaders = java.util.Set.of();

  /**
   * From now on, until endCasting, every mesh drawn through a Renderer is drawn with caster (only
   * its depth), or, if it's drawn with one of cutouts, with cutout (its see-through parts left out).
   */
  public static void beginCasting(Shader caster, Shader cutout, java.util.Set<Shader> cutouts) {
    casterShader = caster;
    cutoutCasterShader = cutout;
    cutoutShaders = cutouts;
  }

  public static void endCasting() {
    casterShader = null;
  }

  public void setLights(Light[] lights) {
    this.lights = lights;
  }

  // Uniform names for each light, made once
  private static final String[][] LIGHT_NAMES = new String[64][3];
  static {
    for (int i = 0; i < 64; i++) {
      LIGHT_NAMES[i][0] = "lights[" + i + "].position";
      LIGHT_NAMES[i][1] = "lights[" + i + "].diffuse";
      LIGHT_NAMES[i][2] = "lights[" + i + "].specular";
    }
  }

  private void doLights(GL3 gl, Shader shader, Light[] lights) {
    shader.setInt(gl, "numLights", lights.length);

    for (int i = 0; i < lights.length; i++) {
        Light l = lights[i];
        shader.setVec3(gl, LIGHT_NAMES[i][0],  l.getPosition());
        Vector3 diffuse;
        Vector3 specular;
        if (!l.getIsSun()) {
          diffuse = l.getMaterial().getDiffuse();
          specular = l.getMaterial().getSpecular();
        }
        else {
          float brightnessProportion = l.getBrightnessProportion();
          diffuse = new Vector3(0.8f * brightnessProportion, 0.8f * brightnessProportion, 0.8f * brightnessProportion);
          specular = new Vector3(0.8f * brightnessProportion, 0.8f * brightnessProportion, 0.8f * brightnessProportion);
        }
        shader.setVec3(gl, LIGHT_NAMES[i][1], diffuse);
        shader.setVec3(gl, LIGHT_NAMES[i][2], specular);
    }
  }
  
  private void doAmbientLight(GL3 gl, Shader shader, Vector3 ambientLight) {
    shader.setVec3(gl, "ambientLight", ambientLight);
  }

  private void doBasicMaterial(GL3 gl, Shader shader, Material material) {
    shader.setVec3(gl, "material.ambient", material.getAmbient());
    shader.setVec3(gl, "material.diffuse", material.getDiffuse());
    shader.setVec3(gl, "material.specular", material.getSpecular());
    shader.setVec3(gl, "material.emission", material.getEmission());
    shader.setFloat(gl, "material.shininess", material.getShininess());
    shader.setVec2(gl, "scale", scale);
  }

  private void doDiffuseMap(GL3 gl, Shader shader, Texture dm) {
    shader.setInt(gl, "diffuse_texture", 0);  
    gl.glActiveTexture(GL.GL_TEXTURE0);
    dm.bind(gl);
  }

  private void doDiffuseMap2(GL3 gl, Shader shader, Texture dm) {
    shader.setInt(gl, "diffuse_texture2", 3);  
    gl.glActiveTexture(GL.GL_TEXTURE3);
    dm.bind(gl);
  }

  private void doDiffuseMap3(GL3 gl, Shader shader, Texture dm) {
    shader.setInt(gl, "diffuse_texture3", 4);  
    gl.glActiveTexture(GL.GL_TEXTURE4);
    dm.bind(gl);
  }

  private void doDiffuseMap4(GL3 gl, Shader shader, Texture dm) {
    shader.setInt(gl, "diffuse_texture4", 5);  
    gl.glActiveTexture(GL.GL_TEXTURE5);
    dm.bind(gl);
  }

  private void doNightProportion(GL3 gl, Shader shader, float nightProportion) {
    shader.setFloat(gl, "nightProportion", nightProportion); 
  }

  private void doOverlayOffset(GL3 gl, Shader shader, Vector2 offset) {
    shader.setVec2(gl, "overlayOffset", offset);  
  }

  private void doOverlayOffset2(GL3 gl, Shader shader, Vector2 offset) {
    shader.setVec2(gl, "overlayOffset2", offset);  
  }

  private void doOverlayScale(GL3 gl, Shader shader, float scale) {
    shader.setFloat(gl, "overlayScale", scale); 
  }

  private void doOverlayScale2(GL3 gl, Shader shader, float scale) {
    shader.setFloat(gl, "overlayScale2", scale); 
  }

  private void doSpecularMap(GL3 gl, Shader shader, Texture sm) {
    shader.setInt(gl, "specular_texture", 1);  
    gl.glActiveTexture(GL.GL_TEXTURE1);
    sm.bind(gl);
  }

  private void doEmissionMap(GL3 gl, Shader shader, Texture em) {
    shader.setInt(gl, "emission_texture", 2);  
    gl.glActiveTexture(GL.GL_TEXTURE2);
    em.bind(gl);
  }

  public void setScale(Vector2 scale) {
    this.scale = scale;
  }

  /**
   * Draws a mesh whose model matrix places it on the planet (planet-centred), seen by the
   * camera: camera-relative, the eyes' place taken off in the vertex shader (see vs_standard).
   */
  public void render(GL3 gl, Mesh mesh, Matrix4 modelMatrix, Shader shader, Material material,
                     Vector3 ambientLight, float nightProportion, Camera camera) {
    SpherePosition eye = camera.getSpherePosition();
    draw(gl, mesh, modelMatrix, shader, material, ambientLight, nightProportion, camera.getSphereViewProjection(),
            new Vector3((float) eye.x(), (float) eye.y(), (float) eye.z()), new Vector3(0f, 0f, 0f), lights);
  }

  /** Draws a mesh through a view-projection of its own (a picture of something on its own), seen from viewPosition. */
  public void render(GL3 gl, Mesh mesh, Matrix4 modelMatrix, Shader shader, Material material, Vector3 ambientLight,
                     Matrix4 viewProjection, Vector3 viewPosition) {
    draw(gl, mesh, modelMatrix, shader, material, ambientLight, 0f, viewProjection, new Vector3(0f, 0f, 0f), viewPosition, new Light[0]);
  }

  /** Sets the vertex shader's matrices (see vs_standard): the eyes at origin, the view-projection from there. */
  public static void setView(GL3 gl, Shader shader, Matrix4 modelMatrix, Matrix4 viewProjection, Vector3 origin) {
    shader.setFloatArray(gl, "model", modelMatrix.toFloatArrayForGLSL());
    shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
    shader.setVec3(gl, "sphereCamera", origin);
  }

  private void draw(GL3 gl, Mesh mesh, Matrix4 modelMatrix, Shader shader, Material material,
                    Vector3 ambientLight, float nightProportion, Matrix4 viewProjection,
                    Vector3 origin, Vector3 viewPosition, Light[] lights) {
    if (casterShader != null) {
      // Into the sun's shadows: only where it is (and, if leafy, where its picture's solid)
      boolean cutout = cutoutShaders.contains(shader) && material.diffuseMapExists();
      Shader caster = cutout ? cutoutCasterShader : casterShader;
      caster.use(gl);
      setView(gl, caster, modelMatrix, viewProjection, origin);
      if (cutout) doDiffuseMap(gl, caster, material.getDiffuseMap());
      mesh.render(gl);
      // (its own shader in use again, as after drawing it normally: whatever's set on it next, such
      // as a run-down wall's grime being put back to none, must go to it, not to the caster)
      shader.use(gl);
      return;
    }
    shader.use(gl);
    setView(gl, shader, modelMatrix, viewProjection, origin);
    shader.setVec3(gl, "viewPos", viewPosition);
    shader.setFloat(gl, "planetRadius", (float) Planet.radius());
    doAmbientLight(gl, shader, ambientLight);
    doLights(gl, shader, lights);
    doBasicMaterial(gl, shader, material);
    shader.setInt(gl, "useDiffuseTexture", material.diffuseMapExists() ? 1 : 0);
    
    if (material.diffuseMapExists()) {
      Texture dm = material.getDiffuseMap();
      doDiffuseMap(gl, shader, dm);
    }
    if (material.diffuseMap2Exists()) {
      Texture dm = material.getDiffuseMap2();
      doDiffuseMap2(gl, shader, dm);
      doOverlayOffset(gl, shader, material.getOverlayOffset());
      doOverlayScale(gl, shader, material.getOverlayScale());
    }

    if (material.diffuseMap3Exists()) {
      Texture dm = material.getDiffuseMap3();
      doDiffuseMap3(gl, shader, dm);
      doOverlayOffset2(gl, shader, material.getOverlayOffset2());
      doOverlayScale2(gl, shader, material.getOverlayScale2());
    }

    if (material.diffuseMap4Exists()) {
      Texture dm = material.getDiffuseMap4();
      doDiffuseMap4(gl, shader, dm);
      doNightProportion(gl, shader, nightProportion);
    }
    
    if (material.specularMapExists()) {
      Texture sm = material.getSpecularMap();
      doSpecularMap(gl, shader, sm);
    }
    if (material.emissionMapExists()) {
      Texture em = material.getEmissionMap();
      doEmissionMap(gl, shader, em);
    }
    mesh.render(gl);
  }
}
