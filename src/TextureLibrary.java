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

  // Pictures read ahead of need (see prefetch), by file name, on a background thread
  private static final Map<String, java.util.concurrent.Future<TextureData>> PREFETCHED = new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ExecutorService READER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "texture-reader");
    thread.setDaemon(true);
    thread.setPriority(Thread.NORM_PRIORITY - 1);
    return thread;
  });

  /**
   * Starts reading and decoding these picture files (and making their mipmaps) on a background
   * thread, so loading them later only has to put them on the GPU, not hold up the GL thread.
   */
  public static void prefetch(GLProfile profile, Iterable<String> filenames) {
    for (String filename : filenames) {
      PREFETCHED.computeIfAbsent(filename, f -> READER.submit(() -> TextureIO.newTextureData(profile, new File(f), true, null)));
    }
  }

  /** The texture from a file: from its prefetched picture if it was read ahead, otherwise read now. */
  private static Texture newTexture(String filename) throws Exception {
    java.util.concurrent.Future<TextureData> ahead = PREFETCHED.remove(filename);
    if (ahead != null) {
      try {
        return TextureIO.newTexture(ahead.get());
      } catch (Exception e) {
        // (read again below)
      }
    }
    return TextureIO.newTexture(new File(filename), true);
  }

  // mip-mapping is included in the below example
  public static Texture loadTexture(GL3 gl3, String filename) {
    Texture t = null;
    try {
      t = newTexture(filename);
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
      t = newTexture(filename);
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