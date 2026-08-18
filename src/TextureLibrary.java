import java.io.File;
import java.io.FileInputStream;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import com.jogamp.opengl.*;

import com.jogamp.opengl.util.texture.*;
import com.jogamp.opengl.util.texture.awt.AWTTextureIO;

public class TextureLibrary {
  
  private Map<String,Texture> textures;

  public TextureLibrary() {
    textures = new HashMap<String, Texture>();
  }

  public void add(GL3 gl, String name, String filename) {
    Texture texture = loadTexture(gl, filename);
    textures.put(name, texture);
  }

  public void addWrap(GL3 gl, String name, String filename) {
    Texture texture = loadTextureWrap(gl, filename);
    textures.put(name, texture);
  }

  public Texture get(String name) {
    return textures.get(name);
  }
  
  // Method to create an OpenGL Texture directly from an in-memory BufferedImage
  public static Texture createTextureFromBufferedImage(GL3 gl3, BufferedImage bufferedImage) {
    Texture t = null;
    try {
      t = AWTTextureIO.newTexture(gl3.getGLProfile(), bufferedImage, true);
      t.bind(gl3);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR_MIPMAP_LINEAR);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
      gl3.glGenerateMipmap(GL3.GL_TEXTURE_2D);
    } catch (Exception e) {
      System.out.println("Error creating texture from BufferedImage: " + e.getMessage());
    }
    return t;
  }

  // mip-mapping is included in the below example
  public static Texture loadTexture(GL3 gl3, String filename) {
    Texture t = null; 
    try {
      File f = new File(filename);
      t = (Texture)TextureIO.newTexture(f, true);
      t.bind(gl3);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE); 
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR_MIPMAP_LINEAR);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
      gl3.glGenerateMipmap(GL3.GL_TEXTURE_2D);
    }
    catch(Exception e) {
      System.out.println("Error loading texture " + filename); 
    }
    return t;
  }

  public static Texture loadTextureWrap(GL3 gl3, String filename) {
    Texture t = null; 
    try {
      File f = new File(filename);
      t = (Texture)TextureIO.newTexture(f, true);
      t.bind(gl3);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_WRAP_S, GL3.GL_REPEAT);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_WRAP_T, GL3.GL_REPEAT); 
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR_MIPMAP_LINEAR);
      t.setTexParameteri(gl3, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
      gl3.glGenerateMipmap(GL3.GL_TEXTURE_2D);
    }
    catch(Exception e) {
      System.out.println("Error loading texture " + filename); 
    }
    return t;
  }
  
  public void destroy(GL3 gl3) {
    for (var entry : textures.entrySet()) {
      entry.getValue().destroy(gl3);
    }
  }
}