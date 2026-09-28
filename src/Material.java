import com.jogamp.opengl.util.texture.*;

import com.xenoguesser.math.*;

 /**
 * This class stores the Material properties for a Mesh
 *
 * @author    Dr Steve Maddock
 * @version   1.0 (15/10/2017)
 */

public class Material implements Cloneable {
  
  // Note: if assigned, these cannot be changed. For a changeable value need to create
  // a new Vector3 using one of these.
  // Default is a fairly bright white colour with some specular.
  public static final Vector3 DEFAULT_AMBIENT = new Vector3(0.2f, 0.2f, 0.2f);
  public static final Vector3 DEFAULT_DIFFUSE = new Vector3(0.8f, 0.8f, 0.8f);
  public static final Vector3 DEFAULT_SPECULAR = new Vector3(0.5f, 0.5f, 0.5f);
  public static final Vector3 DEFAULT_EMISSION = new Vector3(0.0f, 0.0f, 0.0f);

  public static final float DEFAULT_SHININESS = 32;

  private Vector3 ambient;
  private Vector3 diffuse;
  private Vector3 specular;
  private Vector3 emission;
  private float shininess;
  private Texture diffuseMap;
  private Texture specularMap;
  private Texture emissionMap;
  /* I declare that this code is my own work */
  private Texture diffuseMap2;
  private Texture diffuseMap3;
  private Texture diffuseMap4;
  private Vector2 overlayOffset;
  private float overlayScale;
  private Vector2 overlayOffset2;
  private float overlayScale2;
  private Vector3 fullDiffuse;
  private Vector3 fullSpecular;
  /* Author Alexander Sampson, asampson1@sheffield.ac.uk */


  /**
   * Constructor. Sets attributes to default initial values.
   */    
  public Material() {
    this(new Vector3(DEFAULT_AMBIENT), new Vector3(DEFAULT_DIFFUSE), 
         new Vector3(DEFAULT_SPECULAR), new Vector3(DEFAULT_EMISSION), 
         null, null, null, DEFAULT_SHININESS);
  }

   /**
   * Constructor. Sets the ambient, diffuse, specular, emission and shininess values
   * 
   * @param  ambient    vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  diffuse    vector of 3 values: red, green and blue, in the range 0.0..1.0.
   */  
   
  public Material(Vector3 ambient, Vector3 diffuse) {
    this(ambient, diffuse, new Vector3(DEFAULT_SPECULAR), new Vector3(DEFAULT_EMISSION), 
         null, null, null, DEFAULT_SHININESS);
  }

  /**
   * Constructor. Sets the ambient, diffuse, specular and shininess values
   * 
   * @param  ambient    vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  diffuse    vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  specular   vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  shininess   float value in the range 0.0..1.0.
   */  
   
  public Material(Vector3 ambient, Vector3 diffuse, Vector3 specular, float shininess) {
    this(ambient, diffuse, specular, new Vector3(DEFAULT_EMISSION), 
         null, null, null, shininess);
  }

  /**
   * Constructor. Sets the ambient, diffuse, specular, emission and shininess values
   * 
   * @param  ambient    vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  diffuse    vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  specular   vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  emission   vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  shininess   float value in the range 0.0..1.0.
   */  
   
   public Material(Vector3 ambient, Vector3 diffuse, Vector3 specular, Vector3 emission, float shininess) {
    this(ambient, diffuse, specular, emission, 
         null, null, null, shininess);
  }

  /**
   * Constructor. Sets the diffuseMap, specularMap, emissionMap and shininess values
   * 
   * @param  diffuseMap    texture map of diffuse values
   * @param  specularMap   texture map of specular values
   * @param  emissionMap   texture map of emission values
   * @param  shininess     float value in the range 0.0..1.0.
   */  
   
  public Material(Texture diffuseMap, Texture specularMap, Texture emissionMap, float shininess) {
    this(new Vector3(DEFAULT_AMBIENT), new Vector3(DEFAULT_DIFFUSE), 
         new Vector3(DEFAULT_SPECULAR), new Vector3(DEFAULT_EMISSION), 
         diffuseMap, specularMap, emissionMap, shininess);
  }
  
  /**
   * Constructor. Sets the ambient, diffuse, specular, emission and shininess values
   * 
   * @param  ambient    vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  diffuse    vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  specular   vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  emission   vector of 3 values: red, green and blue, in the range 0.0..1.0.
   * @param  diffuseMap    texture map of diffuse values
   * @param  specularMap   texture map of specular values
   * @param  emissionMap   texture map of emission values
   * @param  shininess   float value in the range 0.0..1.0.
   */

  public Material(Vector3 ambient, Vector3 diffuse, Vector3 specular, Vector3 emission,
                  Texture diffuseMap, Texture specularMap, Texture emissionMap, 
                  float shininess) {
    this.ambient = ambient;
    this.diffuse = diffuse;
    this.specular = specular;
    this.emission = emission;
    this.shininess = shininess;
    this.diffuseMap = diffuseMap;
    this.specularMap = specularMap;
    this.emissionMap = emissionMap;
    /* I declare that this code is my own work */
    this.fullDiffuse = new Vector3(diffuse.x, diffuse.y, diffuse.z);
    this.fullSpecular = new Vector3(specular.x, specular.y, specular.z);
    /* Author Alexander Sampson, asampson1@sheffield.ac.uk */
  }

  /**
   * Sets the ambient value (as used in Phong local reflection model)
   * 
   * @param  red    the red value in the range 0.0..1.0
   * @param  green  the green value in the range 0.0..1.0
   * @param  blue   the blue value in the range 0.0..1.0
   */    
  public void setAmbient(float red, float green, float blue) {
    ambient.x = red;
    ambient.y = green;
    ambient.z = blue;
  }  
  
  /**
   * Sets the ambient value (as used in Phong local reflection model)
   * 
   * @param  rgb  vector of 3 values, where the  3 values are the values for red, green and blue, 
                   in the range 0.0..1.0.
   */    
  public void setAmbient(Vector3 rgb) {
    setAmbient(rgb.x, rgb.y, rgb.z);
  }
  
  /**
   * Gets the ambient value (as a clone)
   * 
   * @return  vector of 3 values, where the  3 values are the values for red, green and blue.
   */  
  public Vector3 getAmbient() {
    return new Vector3(ambient);
  }

  /**
   * Sets the diffuse value (as used in Phong local reflection model)
   * 
   * @param  red    the red value in the range 0.0..1.0
   * @param  green  the green value in the range 0.0..1.0
   * @param  blue   the blue value in the range 0.0..1.0
  */  
  public void setDiffuse(float red, float green, float blue) {
    diffuse.x = red;
    diffuse.y = green;
    diffuse.z = blue;
  }
  
  /**
   * Sets the diffuse value (as used in Phong local reflection model)
   * 
   * @param  rgb  vector of 3 values, where the  3 values are the values for red, green and blue, 
                   in the range 0.0..1.0.
   */      
  public void setDiffuse(Vector3 rgb) {
    setDiffuse(rgb.x, rgb.y, rgb.z);
  }

  /* I declare that this code is my own work */
  public void setFullDiffuse(float red, float green, float blue) {
    fullDiffuse.x = red;
    fullDiffuse.y = green;
    fullDiffuse.z = blue;
  }

  public void setFullSpecular(float red, float green, float blue) {
    fullSpecular.x = red;
    fullSpecular.y = green;
    fullSpecular.z = blue;
  }

  public Vector3 getFullDiffuse() {
    return fullDiffuse;
  }

  public void setDimmedDiffuseSpecular(float proportion) {
    diffuse.x = fullDiffuse.x * proportion;
    diffuse.y = fullDiffuse.y * proportion;
    diffuse.z = fullDiffuse.z * proportion;
    specular.x = fullSpecular.x * proportion;
    specular.y = fullSpecular.y * proportion;
    specular.z = fullSpecular.z * proportion;
  }
  /* Author Alexander Sampson, asampson1@sheffield.ac.uk */

  /**
   * Gets the diffuse value (clone) (as used in Phong local reflection model)
   * 
   * @return  vector of 3 values, where the  3 values are the values for red, green and blue
   */    
  public Vector3 getDiffuse() {
    return new Vector3(diffuse);
  }

  /**
   * Sets the specular value (as used in Phong local reflection model)
   * 
   * @param  red    the red value in the range 0.0..1.0
   * @param  green  the green value in the range 0.0..1.0
   * @param  blue   the blue value in the range 0.0..1.0
  */    
  public void setSpecular(float red, float green, float blue) {
    specular.x = red;
    specular.y = green;
    specular.z = blue;
  }

  /**
   * Sets the specular value (as used in Phong local reflection model)
   * 
   * @param  rgb  vector of 3 values, where the first 3 values are the values for red, green and blue, 
                   in the range 0.0..1.0, and the last value is an alpha term, which is always 1.
   */    
  public void setSpecular(Vector3 rgb) {
    setSpecular(rgb.x, rgb.y, rgb.z);
  }
    
  /**
   * Gets the specular value (clone) (as used in Phong local reflection model)
   * 
   * @return  vector of 3 values, where the  3 values are the values for red, green and blue.
   */  
  public Vector3 getSpecular() {
    return new Vector3(specular);
  }

  /**
   * Sets the emission value (as used in OpenGL lighting model)
   * 
   * @param  red    the red value in the range 0.0..1.0
   * @param  green  the green value in the range 0.0..1.0
   * @param  blue   the blue value in the range 0.0..1.0
   */    
  public void setEmission(float red, float green, float blue) {
    emission.x = red;
    emission.y = green;
    emission.z = blue;
  }
  
  /**
   * Sets the emission value (as used in OpenGL lighting model)
   * 
   * @param  rgb  vector of 3 values, where the 3 values are the values for red, green and blue, 
                   in the range 0.0..1.0.
   */    
  public void setEmission(Vector3 rgb) {
    setEmission(rgb.x, rgb.y, rgb.z);
  }

  /**
   * Gets the emission value (clone) (as used in OpenGL lighting model)
   * 
   * @return  vector of 3 values, where the  3 values are the values for red, green and blue.
   */ 
  public Vector3 getEmission() {
    return new Vector3(emission);
  }
    
  /**
   * Sets the shininess value (as used in Phong local reflection model)
   * 
   * @param  shininess  the shininess value.
   */   
  public void setShininess(float shininess) {
    this.shininess = shininess;
  }
  
  /**
   * Gets the shininess value (as used in Phong local reflection model)
   * 
   * @return  the shininess value.
   */   
  public float getShininess() {
    return shininess;
  }

  public boolean diffuseMapExists() {
    return (diffuseMap!=null);
  }

  /* I declare that the modifications here are my own work, based on diffuseMapExists */
  public boolean diffuseMap2Exists() {
    return (diffuseMap2!=null);
  }

  public boolean diffuseMap3Exists() {
    return (diffuseMap3!=null);
  }

  public boolean diffuseMap4Exists() {
    return (diffuseMap4!=null);
  }
  /* Modified by Alexander Sampson, asampson1@sheffield.ac.uk */

  public void setDiffuseMap(Texture diffuseMap) {
    this.diffuseMap = diffuseMap;
  }

  /* I declare that the modifications here are my own work, based on setDiffuseMap */
  public void setDiffuseMap2(Texture diffuseMap) {
    this.diffuseMap2 = diffuseMap;
  }

  public void setDiffuseMap3(Texture diffuseMap) {
    this.diffuseMap3 = diffuseMap;
  }

  public void setDiffuseMap4(Texture diffuseMap) {
    this.diffuseMap4 = diffuseMap;
  }
  /* Modified by Alexander Sampson, asampson1@sheffield.ac.uk */

  public Texture getDiffuseMap() {
    return diffuseMap;
  }

  /* I declare that the modifications here are my own work, based on getDiffuseMap */
  public Texture getDiffuseMap2() {
    return diffuseMap2;
  }

  public Texture getDiffuseMap3() {
    return diffuseMap3;
  }

  public Texture getDiffuseMap4() {
    return diffuseMap4;
  }
  /* Modified by Alexander Sampson, asampson1@sheffield.ac.uk */

  public boolean specularMapExists() {
    return (specularMap!=null);
  }

  public void setSpecularMap(Texture specularMap) {
    this.specularMap = specularMap;
  }

  public Texture getSpecularMap() {
    return specularMap;
  }

  public boolean emissionMapExists() {
    return (emissionMap!=null);
  }

  public void setEmissionMap(Texture emissionMap) {
    this.emissionMap = emissionMap;
  }

  public Texture getEmissionMap() {
    return emissionMap;
  }

  /* I declare that this code is my own work */
  public void setOverlayOffset(Vector2 overlayOffset) {
    this.overlayOffset = overlayOffset;
  }

  public void setOverlayScale(float overlayScale) {
    this.overlayScale = overlayScale;
  }

  public void setOverlayOffset2(Vector2 overlayOffset2) {
    this.overlayOffset2 = overlayOffset2;
  }

  public void setOverlayScale2(float overlayScale2) {
    this.overlayScale2 = overlayScale2;
  }

  public Vector2 getOverlayOffset() {
    return overlayOffset;
  }

  public float getOverlayScale() {
    return  overlayScale;
  }

  public Vector2 getOverlayOffset2() {
    return overlayOffset2;
  }

  public float getOverlayScale2() {
    return  overlayScale2;
  }
   /* Author Alexander Sampson, asampson1@sheffield.ac.uk */

  public Material clone() {
    Material cloned = new Material();
    cloned.ambient = new Vector3(this.ambient);
    cloned.diffuse = new Vector3(this.diffuse);
    cloned.specular = new Vector3(this.specular);
    cloned.emission = new Vector3(this.emission);
    cloned.shininess = this.shininess;
    cloned.diffuseMap = this.diffuseMap;
    cloned.specularMap = this.specularMap;
    cloned.emissionMap = this.emissionMap;
    return cloned;
  }

  public String toString() {
    return "a:"+ambient+", d:"+diffuse+", s:"+specular+", e:"+emission
            +", dm:"+diffuseMap+", sm:"+specularMap+", em:"+emissionMap
            +", shininess:"+shininess;
  }  

}