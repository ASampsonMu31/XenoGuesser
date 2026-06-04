import java.nio.ByteBuffer;
import com.jogamp.opengl.*;
import com.jogamp.common.nio.Buffers;

public class WorldMap {
    private int textureId;
    // FIXED: Upgraded resolution to your exact 400x400 pixel specification
    private final int MAP_RES = 750;

    public WorldMap(GL3 gl, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        generateMapTexture(gl, totalRegionWidth, seaLevelHeight, noise);
    }

    private void generateMapTexture(GL3 gl, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        int[] textureIds = new int[1];
        gl.glGenTextures(1, textureIds, 0);
        this.textureId = textureIds[0];

        // Allocate memory for a 400x400 RGB texture space
        ByteBuffer buffer = Buffers.newDirectByteBuffer(MAP_RES * MAP_RES * 3);
        float halfRegion = totalRegionWidth / 2.0f;

        // Step through your 400x400 grid lines
        for (int z = 0; z < MAP_RES; z++) {
            for (int x = 0; x < MAP_RES; x++) {
                // MATH: Transform the 0-400 pixel space into your continuous -60000 to +60000 world space
                float worldX = ((float) x / MAP_RES) * totalRegionWidth - halfRegion;
                float worldZ = ((float) z / MAP_RES) * totalRegionWidth - halfRegion;

                // Sample your exact 3-layer terrain formula at this point
                float height = TerrainMesh.getLayeredHeight(worldX, worldZ, noise);

                if (height > seaLevelHeight) {
                    // LAND: Solid earthy brown color pixel
                    buffer.put((byte) 92);  // R
                    buffer.put((byte) 64);  // G
                    buffer.put((byte) 45);  // B
                } else {
                    // WATER: Solid ocean blue color pixel
                    buffer.put((byte) 25);  // R
                    buffer.put((byte) 80);  // G
                    buffer.put((byte) 160); // B
                }
            }
        }
        buffer.flip();

        // Push the static map pixels straight up to VRAM
        gl.glBindTexture(GL.GL_TEXTURE_2D, textureId);
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGB, MAP_RES, MAP_RES, 0, GL.GL_RGB, GL.GL_UNSIGNED_BYTE, buffer);
        
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
    }

    public int getTextureId() {
        return textureId;
    }
}