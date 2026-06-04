import gmaths.*;
import com.jogamp.opengl.*;
import com.jogamp.opengl.util.texture.*;

public class Renderer {

  public Vec2 scale = new Vec2(1, 1);

  public Renderer() {}

  private void doVertexShaderMatrices(GL3 gl, Shader shader, Mat4 modelMatrix, Camera camera) { 
    shader.setFloatArray(gl, "model", modelMatrix.toFloatArrayForGLSL());
    Mat4 mvpMatrix = Mat4.multiply(camera.getPerspectiveMatrix(), 
                                   Mat4.multiply(camera.getViewMatrix(), modelMatrix));
    shader.setFloatArray(gl, "mvpMatrix", mvpMatrix.toFloatArrayForGLSL());
    shader.setVec3(gl, "viewPos", camera.getPosition());
  }

  private void doLights(GL3 gl, Shader shader, Light[] lights) {
    shader.setInt(gl, "numLights", lights.length);

    for (int i = 0; i < lights.length; i++) {
        Light l = lights[i];
        shader.setVec3(gl, "lights[" + i + "].position",  l.getPosition());
        Vec3 diffuse;
        Vec3 specular;
        if (!l.getIsSun()) {
          diffuse = l.getMaterial().getDiffuse();
          specular = l.getMaterial().getSpecular();
        }
        else {
          float brightnessProportion = l.getBrightnessProportion();
          diffuse = new Vec3(0.8f * brightnessProportion, 0.8f * brightnessProportion, 0.8f * brightnessProportion);
          specular = new Vec3(0.8f * brightnessProportion, 0.8f * brightnessProportion, 0.8f * brightnessProportion);
        }
        shader.setVec3(gl, "lights[" + i + "].diffuse", diffuse);
        shader.setVec3(gl, "lights[" + i + "].specular", specular);
    }
  }
  
  private void doAmbientLight(GL3 gl, Shader shader, Vec3 ambientLight) {
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

  private void doOverlayOffset(GL3 gl, Shader shader, Vec2 offset) {
    shader.setVec2(gl, "overlayOffset", offset);  
  }

  private void doOverlayOffset2(GL3 gl, Shader shader, Vec2 offset) {
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

  public void setScale(Vec2 scale) {
    this.scale = scale;
  }

  public void render(GL3 gl, Mesh mesh, Mat4 modelMatrix, Shader shader, 
                     Material material, Light[] lights, Vec3 ambientLight, 
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