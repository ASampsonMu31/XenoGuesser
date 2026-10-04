import com.jogamp.opengl.*;
import com.jogamp.opengl.util.texture.*;

import com.xenoguesser.math.*;

public class Renderer {

  public Vector2 scale = new Vector2(1, 1);

  public Renderer() {}

  private void doVertexShaderMatrices(GL3 gl, Shader shader, Matrix4 modelMatrix, Camera camera) { 
    shader.setFloatArray(gl, "model", modelMatrix.toFloatArrayForGLSL());
    Matrix4 mvpMatrix = Matrix4.multiply(camera.getViewProjection(), modelMatrix);
    shader.setFloatArray(gl, "mvpMatrix", mvpMatrix.toFloatArrayForGLSL());
    shader.setVec3(gl, "viewPos", camera.getPosition());
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

  public void render(GL3 gl, Mesh mesh, Matrix4 modelMatrix, Shader shader, 
                     Material material, Light[] lights, Vector3 ambientLight, 
                     float nightProportion, Camera camera) {
    shader.use(gl);
    doVertexShaderMatrices(gl, shader, modelMatrix, camera);
    doAmbientLight(gl, shader, ambientLight);
    doLights(gl, shader, lights);
    doBasicMaterial(gl, shader, material);
    
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