import java.nio.ByteBuffer;
import com.jogamp.opengl.*;
import com.jogamp.common.nio.Buffers;
import gmaths.Vec3;

public class WorldMap {
    private int textureId;
    private final int MAP_RES = 750;

    // Mercator limits at roughly +/- 85.0511 degrees to keep the map a perfect square
    private final float MAX_MERCATOR_Y = (float) Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(85.05113) / 2.0));

    public WorldMap(GL3 gl, float seaLevelHeight, PerlinNoise noise, float seedX, float seedY, float seedZ) {
        generateMapTexture(gl, seaLevelHeight, noise, seedX, seedY, seedZ);
    }

    private void generateMapTexture(GL3 gl, float seaLevelHeight, PerlinNoise noise, float seedX, float seedY, float seedZ) {
        int[] textureIds = new int[1];
        gl.glGenTextures(1, textureIds, 0);
        this.textureId = textureIds[0];

        ByteBuffer buffer = Buffers.newDirectByteBuffer(MAP_RES * MAP_RES * 3);

        for (int z = 0; z < MAP_RES; z++) {
            // 1. Normalize vertical texture coordinate V to range [-1, 1]
            float v = ((float) z / MAP_RES) * 2.0f - 1.0f;
            
            // 2. Linear step through Mercator Y space
            float mercatorY = v * MAX_MERCATOR_Y;
            
            // 3. Inverse Mercator transform: Get the actual Latitude (phi) from Mercator Y
            float phi = (float) (2.0 * Math.atan(Math.exp(mercatorY)) - Math.PI / 2.0);

            for (int x = 0; x < MAP_RES; x++) {
                // 4. Normalize horizontal texture coordinate U to range [-1, 1]
                float u = ((float) x / MAP_RES) * 2.0f - 1.0f;
                
                // 5. Map U directly to Longitude (theta) running from -PI to PI
                float theta = u * (float) Math.PI;

                // 6. Convert spherical angles (theta, phi) into a 3D unit direction vector
                float cosPhi = (float) Math.cos(phi);
                float rx = cosPhi * (float) Math.cos(theta);
                float ry = (float) Math.sin(phi); // Y is the vertical pole axis
                float rz = cosPhi * (float) Math.sin(theta);
                
                Vec3 radialDir = new Vec3(rx, ry, rz);

                // 7. Sample your exact 3D spherical planet noise formula using the seed offsets
                float height = TerrainMesh.getLayeredHeight3D(radialDir, seedX, seedY, seedZ);

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