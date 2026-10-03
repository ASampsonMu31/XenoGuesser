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
      vertexShaderSource = new String(Files.readAllBytes(Paths.get(vertexPath)), Charset.defaultCharset());
      fragmentShaderSource = new String(Files.readAllBytes(Paths.get(fragmentPath)), Charset.defaultCharset());
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    if (DISPLAY_SHADERS) display();
    ID = compileAndLink(gl);
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
  
  public void setInt(GL3 gl, String name, int value) {
    int location = location(gl, name);
    gl.glUniform1i(location, value);
  }
  
  public void setFloat(GL3 gl, String name, float value) {
    int location = location(gl, name);
    gl.glUniform1f(location, value);
  }
  
  public void setFloat(GL3 gl, String name, float f1, float f2) {
    int location = location(gl, name);
    gl.glUniform2f(location, f1, f2);
  }
  
  public void setFloat(GL3 gl, String name, float f1, float f2, float f3) {
    int location = location(gl, name);
    gl.glUniform3f(location, f1, f2, f3);
  }
  
  public void setFloat(GL3 gl, String name, float f1, float f2, float f3, float f4) {
    int location = location(gl, name);
    gl.glUniform4f(location, f1, f2, f3, f4);
  }
  
  public void setFloatArray(GL3 gl, String name, float[] f) {
    int location = location(gl, name);
    gl.glUniformMatrix4fv(location, 1, false, f, 0);
  }
  
  /* I declare that the modifications here are my own work based on setVec3 */
  public void setVec2(GL3 gl, String name, Vector2 v) {
    int location = location(gl, name);
    gl.glUniform2f(location, v.x, v.y);
  }
  /* Modified by Alexander Sampson, asampson1@sheffield.ac.uk */

  public void setVec3(GL3 gl, String name, Vector3 v) {
    int location = location(gl, name);
    gl.glUniform3f(location, v.x, v.y, v.z);
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
    if (!program.validateProgram(gl, System.out))
      System.err.println("[error] Unable to link program");
    return program.program();
  }

}