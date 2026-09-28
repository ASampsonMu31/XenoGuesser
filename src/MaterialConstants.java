import com.jogamp.opengl.util.texture.*;

import com.xenoguesser.math.*;

 /**
 * This class stores the Material properties for a Mesh
 *
 * @author    Dr Steve Maddock
 * @version   1.0 (15/10/2017)
 */

public class MaterialConstants {
  
  // Note: These are final and cannot be changed.

  public static final Material red = new Material(
    new Vector3(0.3f,0,0),new Vector3(1,0,0));
    
  public static final Material green = new Material(
    new Vector3(0,0.3f,0),new Vector3(0,1,0));

  public static final Material blue = new Material(
    new Vector3(0,0,0.3f),new Vector3(0,0,1));

  public static final Material dullWhiteLightSource = new Material(
    new Vector3(0.3f,0.3f,0.3f),new Vector3(0.7f,0.7f,0.7f),
    new Vector3(1,1,1),32);

  public static final Material brightWhiteLightSource = new Material(
    new Vector3(1,1,1),new Vector3(1,1,1),
    new Vector3(1,1,1),new Vector3(0.5f,0.5f,0.5f), 32);

  public static final Material pearl = new Material(
    new Vector3(0.25f,0.20725f,0.20725f),
    new Vector3(1,0.829f,0.829f),
    new Vector3(0.296648f,0.296648f,0.296648f),
    0.088f*128f);

  public static final Material cyanPlastic = new Material(
    new Vector3(0.0f, 0.1f, 0.06f),
    new Vector3(0.0f, 0.50980392f, 0.50980392f),
    new Vector3(0.50196078f, 0.50196078f, 0.50196078f),
    0.25f*128f);
    
  public static final Material gold = new Material(
    new Vector3(0.24725f, 0.1995f, 0.0745f),
    new Vector3(0.75164f, 0.60648f, 0.22648f),
    new Vector3(0.628281f,0.555802f, 0.366065f),
    0.4f*128f);   

}