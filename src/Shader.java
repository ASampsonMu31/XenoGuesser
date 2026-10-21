import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.Charset;
import com.jogamp.opengl.*;
import com.jogamp.opengl.util.glsl.*;

import com.xenoguesser.math.*;  
  
public class Shader {
  
  private static final boolean DISPLAY_SHADERS = false;
  
  private int ID;
  // glGetUniformLocation is a slow driver call, and set* runs thousands of times a frame
  private final java.util.Map<String, Integer> uniformLocations = new java.util.HashMap<>();
  private String vertexShaderSource;
  private String fragmentShaderSource;
  
  /* The constructor */
  public Shader(GL3 gl, String vertexPath, String fragmentPath) {
    try {
      vertexShaderSource = withIncludes(new String(Files.readAllBytes(Paths.get(vertexPath)), Charset.defaultCharset()));
      fragmentShaderSource = withIncludes(new String(Files.readAllBytes(Paths.get(fragmentPath)), Charset.defaultCharset()));
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    if (DISPLAY_SHADERS) display();
    ID = compileAndLink(gl);
    synchronized (ALL) {
      ALL.add(this);
    }
  }

  /**
   * A shader's source with each #include "name" line replaced by assets/shaders/name.txt (code
   * shared by many shaders, such as the sun's shadows).
   */
  private static String withIncludes(String source) throws IOException {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^[ \\t]*#include \"(\\w+)\"[ \\t]*\\r?$").matcher(source);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String included = new String(Files.readAllBytes(Paths.get(GamePaths.HOME + "assets/shaders/" + m.group(1) + ".txt")), Charset.defaultCharset());
      m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(included));
    }
    m.appendTail(out);
    return out.toString();
  }

  // The texture units the sun's shadow maps are always bound to (see setShadows)
  public static final int SHADOW_NEAR_UNIT = 13, SHADOW_FAR_UNIT = 14;

  /**
   * The sun's shadows (see ShadowMaps) for every shader that draws them: the maps (texture ids,
   * bound to their units here), their views from the sun (relative to the eyes), how dark the
   * shadows are (0: none), how far each map reaches, a texel of each, the bias against a surface
   * shading itself in each, and the way to the sun.
   */
  public static void setShadows(GL3 gl, int nearMap, int farMap, float[] nearMatrix, float[] farMatrix, float strength,
                                float nearReach, float farReach, float nearTexel, float farTexel,
                                float nearBias, float farBias, float[] sunDirection) {
    gl.glActiveTexture(GL3.GL_TEXTURE0 + SHADOW_NEAR_UNIT);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, nearMap);
    gl.glActiveTexture(GL3.GL_TEXTURE0 + SHADOW_FAR_UNIT);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, farMap);
    gl.glActiveTexture(GL3.GL_TEXTURE0);
    synchronized (ALL) {
      for (Shader shader : ALL) {
        if (shader.ID == 0 || shader.location(gl, "shadowStrength") == -1) continue;
        gl.glUseProgram(shader.ID);
        shader.setFloat(gl, "shadowStrength", strength);
        if (strength <= 0f) continue;
        shader.setInt(gl, "shadowMapNear", SHADOW_NEAR_UNIT);
        shader.setInt(gl, "shadowMapFar", SHADOW_FAR_UNIT);
        shader.setFloatArray(gl, "shadowMatrixNear", nearMatrix);
        shader.setFloatArray(gl, "shadowMatrixFar", farMatrix);
        shader.setFloat(gl, "shadowReach", nearReach, farReach);
        shader.setFloat(gl, "shadowTexel", nearTexel, farTexel);
        shader.setFloat(gl, "shadowBias", nearBias, farBias);
        shader.setFloat(gl, "shadowSunDir", sunDirection[0], sunDirection[1], sunDirection[2]);
      }
    }
  }

  /** No shadows drawn by any shader (and the maps unbound, so they can be drawn into). */
  public static void clearShadows(GL3 gl) {
    synchronized (ALL) {
      for (Shader shader : ALL) {
        if (shader.ID == 0 || shader.location(gl, "shadowStrength") == -1) continue;
        gl.glUseProgram(shader.ID);
        shader.setFloat(gl, "shadowStrength", 0f);
      }
    }
    gl.glActiveTexture(GL3.GL_TEXTURE0 + SHADOW_NEAR_UNIT);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, 0);
    gl.glActiveTexture(GL3.GL_TEXTURE0 + SHADOW_FAR_UNIT);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, 0);
    gl.glActiveTexture(GL3.GL_TEXTURE0);
  }

  // Every shader made, so the fog and snow can be handed to all of them at once
  private static final java.util.List<Shader> ALL = new java.util.ArrayList<>();

  /** Forgets every shader made so far (their GL context has gone, e.g. back at the main menu). */
  public static void forgetAll() {
    synchronized (ALL) {
      ALL.clear();
    }
  }

  /** What the distance fades into, for every shader with fog (see fogHaze in those shaders). */
  public static void setFog(GL3 gl, float r, float g, float b) {
    synchronized (ALL) {
      for (Shader shader : ALL) {
        if (shader.ID == 0) continue;
        int c = shader.location(gl, "fogColour");
        if (c == -1) continue;
        gl.glUseProgram(shader.ID);
        shader.setFloat(gl, "fogColour", r, g, b);
        shader.setFloat(gl, "fogReady", 1f);
      }
    }
  }

  /**
   * The lying snow (see XenoGuesser_GLEventListener.bindSnowCover) for every shader with a
   * snowCover uniform: its cube map's texture unit, how much fresh snow has settled, how much
   * warmer today is than usual, the climate it lies in ({freezing point, degrees below it for
   * thick snow, degrees colder per unit up, sea level}), the snow's colour and the planet's radius.
   */
  /** The cube map samplers and the texture units they are always bound to. */
  public static final int SNOW_UNIT = 11, SOIL_REGION_UNIT = 6, WAVE_UNIT = 12;
  private static final Object[][] CUBE_UNITS = { { "snowCover", SNOW_UNIT }, { "soilRegionMap", SOIL_REGION_UNIT },
      { "waveMap", WAVE_UNIT }, { "shadowMapNear", 13 }, { "shadowMapFar", 14 } };

  public static void setSnowCover(GL3 gl, int unit, float fresh, float today, float[] climate, float[] colour, float planetRadius) {
    synchronized (ALL) {
      for (Shader shader : ALL) {
        if (shader.ID == 0) continue;
        int c = shader.location(gl, "snowCover");
        if (c == -1) continue;
        gl.glUseProgram(shader.ID);
        shader.setInt(gl, "snowCover", unit);
        shader.setFloat(gl, "snowState", 1f, fresh);
        shader.setFloat(gl, "snowClimate", climate[0], climate[1], climate[2], climate[3]);
        shader.setFloat(gl, "snowColour", colour[0], colour[1], colour[2]);
        shader.setFloat(gl, "snowToday", today);
        shader.setFloat(gl, "planetRadius", planetRadius);
      }
    }
  }

  public int getID() {
    return ID;
  }
  
  private int location(GL3 gl, String name) {
    Integer cached = uniformLocations.get(name);
    if (cached == null) {
      cached = gl.glGetUniformLocation(ID, name);
      uniformLocations.put(name, cached);
    }
    return cached;
  }

  public void use(GL3 gl) {
    gl.glUseProgram(ID);
  }
  
  // The values last given to each uniform (by location), so setting one to what it already
  // holds costs no driver call: the same lights, material and matrices are set for every part
  // of every building, a thousand times a frame. Only the set* methods are remembered, so a
  // uniform must always be set through them or never (as the bones and sky rotation are not).
  private float[][] lastValues = new float[64][];

  /** Whether a uniform already holds these values; if not, they're remembered as what it will hold. */
  private boolean unchanged(int location, float a, float b, float c, float d, int count) {
    if (location < 0) return true;
    if (location >= 4096) return false;
    if (location >= lastValues.length) lastValues = java.util.Arrays.copyOf(lastValues, Math.max(location + 1, lastValues.length * 2));
    float[] last = lastValues[location];
    if (last != null && last.length == count
        && Float.floatToRawIntBits(last[0]) == Float.floatToRawIntBits(a)
        && (count < 2 || Float.floatToRawIntBits(last[1]) == Float.floatToRawIntBits(b))
        && (count < 3 || Float.floatToRawIntBits(last[2]) == Float.floatToRawIntBits(c))
        && (count < 4 || Float.floatToRawIntBits(last[3]) == Float.floatToRawIntBits(d))) return true;
    if (last == null || last.length != count) lastValues[location] = last = new float[count];
    last[0] = a;
    if (count > 1) last[1] = b;
    if (count > 2) last[2] = c;
    if (count > 3) last[3] = d;
    return false;
  }

  public void setInt(GL3 gl, String name, int value) {
    int location = location(gl, name);
    if (unchanged(location, Float.intBitsToFloat(value), 0f, 0f, 0f, 1)) return;
    gl.glUniform1i(location, value);
  }
  
  public void setFloat(GL3 gl, String name, float value) {
    int location = location(gl, name);
    if (unchanged(location, value, 0f, 0f, 0f, 1)) return;
    gl.glUniform1f(location, value);
  }
  
  public void setFloat(GL3 gl, String name, float f1, float f2) {
    int location = location(gl, name);
    if (unchanged(location, f1, f2, 0f, 0f, 2)) return;
    gl.glUniform2f(location, f1, f2);
  }
  
  public void setFloat(GL3 gl, String name, float f1, float f2, float f3) {
    int location = location(gl, name);
    if (unchanged(location, f1, f2, f3, 0f, 3)) return;
    gl.glUniform3f(location, f1, f2, f3);
  }
  
  public void setFloat(GL3 gl, String name, float f1, float f2, float f3, float f4) {
    int location = location(gl, name);
    if (unchanged(location, f1, f2, f3, f4, 4)) return;
    gl.glUniform4f(location, f1, f2, f3, f4);
  }
  
  public void setFloatArray(GL3 gl, String name, float[] f) {
    int location = location(gl, name);
    if (location < 0) return;
    if (location < 4096) {
      if (location >= lastValues.length) lastValues = java.util.Arrays.copyOf(lastValues, Math.max(location + 1, lastValues.length * 2));
      float[] last = lastValues[location];
      if (last != null && java.util.Arrays.equals(last, 0, 16, f, 0, 16)) return;
      lastValues[location] = java.util.Arrays.copyOf(f, 16);
    }
    gl.glUniformMatrix4fv(location, 1, false, f, 0);
  }
  
  /* I declare that the modifications here are my own work based on setVec3 */
  public void setVec2(GL3 gl, String name, Vector2 v) {
    setFloat(gl, name, v.x, v.y);
  }
  /* Modified by Alexander Sampson, asampson1@sheffield.ac.uk */

  public void setVec3(GL3 gl, String name, Vector3 v) {
    setFloat(gl, name, v.x, v.y, v.z);
  }
  
  private void display() {
    System.out.println("***Vertex shader***");
    System.out.println(vertexShaderSource);
    System.out.println("\n***Fragment shader***");
    System.out.println(fragmentShaderSource);
  }
  
  private int compileAndLink(GL3 gl) {
    String[][] sources = new String[1][1];
    sources[0] = new String[]{ vertexShaderSource };
    ShaderCode vertexShaderCode = new ShaderCode(GL3.GL_VERTEX_SHADER, sources.length, sources);
    boolean compiled = vertexShaderCode.compile(gl, System.err);
    if (!compiled)
      System.err.println("[error] Unable to compile vertex shader: " + sources);
    sources[0] = new String[]{ fragmentShaderSource };
    ShaderCode fragmentShaderCode = new ShaderCode(GL3.GL_FRAGMENT_SHADER, sources.length, sources);
    compiled = fragmentShaderCode.compile(gl, System.err);
    if (!compiled)
      System.err.println("[error] Unable to compile fragment shader: " + sources);
    ShaderProgram program = new ShaderProgram();
    program.init(gl);
    program.add(vertexShaderCode);
    program.add(fragmentShaderCode);
    program.link(gl, System.out);
    // The cube maps get texture units of their own from the start (every sampler begins on unit
    // 0, and a cube and a 2D sampler may not share one): the snow's and the soil colour's; and
    // the sun's shadow maps, for the same reason (a shadow sampler may not share one either)
    int[] current = new int[1];
    gl.glGetIntegerv(GL3.GL_CURRENT_PROGRAM, current, 0);
    gl.glUseProgram(program.program());
    for (Object[] cube : CUBE_UNITS) {
      int at = gl.glGetUniformLocation(program.program(), (String) cube[0]);
      if (at != -1) gl.glUniform1i(at, (Integer) cube[1]);
    }
    gl.glUseProgram(current[0]);
    if (!program.validateProgram(gl, System.out))
      System.err.println("[error] Unable to link program");
    return program.program();
  }

}