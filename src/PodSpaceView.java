import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Random;

/**
 * What's seen through the landing pod's window (see PodSequence): drawn first, behind the
 * cabin, which hides it everywhere but the door's glass. Stars streaming past as the pod
 * cruises, slowing to points; the planet's star growing ahead and then the planet itself, its
 * land and sea as on the map (PlanetGlobeTexture); and, once the pod's in the air with its
 * window fogged, just a bright sky above and the ground below.
 *
 * The timeline (PodTimeline) sets what's shown each frame through the public fields. Space
 * things are placed in the frame the pod flies in (towards +z, the planet ahead), turned into
 * the pod's own frame by its orientation.
 */
public class PodSpaceView {

    // The stars streaming past as the pod cruises, and the still ones all round once it's slowed
    private static final int STREAKERS = 800, DOME = 1300;
    // How wide a star's streak is, as a share of its distance (a few pixels, near or far)
    private static final float STREAK_WIDTH = 0.0042f;
    // The streaking stars fill a box this wide either way and this deep ahead
    private static final float SPREAD = 900f, DEPTH = 3200f;
    // How far off the planet (and the star behind it) are drawn
    private static final float PLANET_DISTANCE = 4000f, STAR_DISTANCE = 20000f;

    // ---- Set each frame by the timeline
    /** How fast the pod's going, for the streaks (units a second; 0 still). */
    float speed;
    /** How much the streaking stars show (0..1), and the still dome of stars (0..1). */
    float streaming = 1f, dome;
    /** The way the star's light falls on the planet. */
    final float[] light = { 0f, 0f, 1f };
    /** How wide the star looks (its disc's half angle, radians; 0 not shown) and which way it is. */
    float starAngle;
    final float[] starDirection = { 0.13f, 0.09f, 1f };
    /** How wide the planet looks (half angle, radians; 0 not shown), which way it is, and how far it's turned. */
    float planetAngle, planetSpin;
    final float[] planetDirection = { 0f, 0f, 1f };
    /** How much of space shows (1), against the fogged sky and ground (0). */
    float space = 1f;
    /** Which way is up through the fog, in the pod's own frame. */
    final float[] skyUp = { 0f, 1f, 0f };
    /** The pod's orientation: its own frame into the frame it flies in. */
    float[] podToSpace = Affine.identity();
    /**
     * How far (radians) the pod has turned aside from facing the planet (swerving past it at the
     * end of its approach, before swinging round to face it in orbit). Only what's out of the
     * window turns, not which way's up (see PodTimeline).
     */
    float heading;
    /**
     * How far round the planet (radians) the pod has gone in orbit, always facing it: everything
     * else (the stars, the star, the planet's surface and the light on it) turned that far about
     * the planet's middle. The star's place, already turned, is set in starDirection and light.
     */
    float orbitAngle;

    private Shader shader;
    private final int[] vao = new int[4], vbo = new int[4];
    private int sphereIndices, sphereIndexCount;
    private int starTexture, globeTexture;
    private volatile BufferedImage pendingGlobe;
    private boolean globeReady;
    private final float[] streakers = new float[STREAKERS * 3];
    private final float[] streakBrightness = new float[STREAKERS];
    private final float[] lineData = new float[STREAKERS * 6 * 6];
    private final Random rand = new Random(11);
    private float[] sunTint = { 1f, 1f, 1f }, skyColour = { 0.6f, 0.7f, 0.85f }, groundColour = { 0.25f, 0.24f, 0.2f };
    private final long worldSeed;

    public PodSpaceView(long worldSeed) {
        this.worldSeed = worldSeed;
        for (int i = 0; i < STREAKERS; i++) {
            respawn(i, true);
        }
    }

    /** The planet's globe, from any thread, once it's made. */
    void setGlobe(BufferedImage globe) {
        pendingGlobe = globe;
    }

    boolean hasGlobe() {
        return globeReady || pendingGlobe != null;
    }

    private void respawn(int i, boolean anywhere) {
        // (none right down the middle, where they'd hang still)
        float x, y;
        do {
            x = (rand.nextFloat() - 0.5f) * 2f * SPREAD;
            y = (rand.nextFloat() - 0.5f) * 2f * SPREAD;
        } while (x * x + y * y < 2500f);
        streakers[i * 3] = x;
        streakers[i * 3 + 1] = y;
        streakers[i * 3 + 2] = anywhere ? rand.nextFloat() * DEPTH : DEPTH;
        streakBrightness[i] = 0.45f + 0.55f * rand.nextFloat();
    }

    public void initialise(GL3 gl) {
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_pod_space.txt", GamePaths.HOME + "assets/shaders/fs_pod_space.txt");
        gl.glGenVertexArrays(4, vao, 0);
        gl.glGenBuffers(4, vbo, 0);
        // 0: the streaking stars (a thin quad each, bright at its head), filled every frame
        setUp(gl, 0, new float[lineData.length], GL.GL_DYNAMIC_DRAW);
        // 1: the still stars, all round, far off
        float[] still = new float[DOME * 6];
        for (int i = 0; i < DOME; i++) {
            float[] d = Affine.normalise(new float[] { (float) rand.nextGaussian(), (float) rand.nextGaussian(), (float) rand.nextGaussian() });
            still[i * 6] = d[0] * 50000f;
            still[i * 6 + 1] = d[1] * 50000f;
            still[i * 6 + 2] = d[2] * 50000f;
            still[i * 6 + 5] = 0.25f + 0.75f * (float) Math.pow(rand.nextFloat(), 2.5);
        }
        setUp(gl, 1, still, GL.GL_STATIC_DRAW);
        // 2: a square, for the star
        setUp(gl, 2, new float[] { -1, -1, 0, 0, 0, 0, 1, -1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, -1, 1, 0, 0, 0, 0 }, GL.GL_STATIC_DRAW);
        // 3: a globe, its axes as the planet's (y north; longitude 0 along +x, 90 east along -z)
        int across = 96, down = 48;
        float[] sphere = new float[(across + 1) * (down + 1) * 6];
        int k = 0;
        for (int row = 0; row <= down; row++) {
            double latitude = Math.PI * 0.5 - row / (double) down * Math.PI;
            for (int col = 0; col <= across; col++) {
                double longitude = col / (double) across * Math.PI * 2.0 - Math.PI;
                sphere[k++] = (float) (Math.cos(latitude) * Math.cos(longitude));
                sphere[k++] = (float) Math.sin(latitude);
                sphere[k++] = (float) (-Math.cos(latitude) * Math.sin(longitude));
                sphere[k++] = col / (float) across;
                sphere[k++] = row / (float) down;
                sphere[k++] = 1f;
            }
        }
        setUp(gl, 3, sphere, GL.GL_STATIC_DRAW);
        int[] indices = new int[across * down * 6];
        k = 0;
        for (int row = 0; row < down; row++) {
            for (int col = 0; col < across; col++) {
                int a = row * (across + 1) + col, b = a + 1, c = a + across + 1, d = c + 1;
                indices[k++] = a;
                indices[k++] = c;
                indices[k++] = b;
                indices[k++] = b;
                indices[k++] = c;
                indices[k++] = d;
            }
        }
        int[] id = new int[1];
        gl.glBindVertexArray(vao[3]);
        gl.glGenBuffers(1, id, 0);
        sphereIndices = id[0];
        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, sphereIndices);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) indices.length * Integer.BYTES, IntBuffer.wrap(indices), GL.GL_STATIC_DRAW);
        sphereIndexCount = indices.length;
        gl.glBindVertexArray(0);

        // The star as the sky shows it in the game, and the fogged sky's colours, from the world's palette
        WorldPalette palette = new WorldPalette(worldSeed);
        starTexture = upload(gl, 0, ProceduralTextures.sunGlow(palette), false);
        float[] zenith = palette.skyZenith;
        skyColour = new float[] { 0.55f + 0.35f * zenith[0], 0.55f + 0.35f * zenith[1], 0.55f + 0.35f * zenith[2] };
        int land = MapColours.of(worldSeed)[1];
        groundColour = new float[] { ((land >> 16) & 255) / 255f * 0.45f, ((land >> 8) & 255) / 255f * 0.45f, (land & 255) / 255f * 0.45f };
    }

    private void setUp(GL3 gl, int which, float[] data, int usage) {
        gl.glBindVertexArray(vao[which]);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[which]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) data.length * Float.BYTES, FloatBuffer.wrap(data), usage);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 6 * Float.BYTES, 0);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
        gl.glEnableVertexAttribArray(1);
        gl.glBindVertexArray(0);
    }

    /** A picture as a texture (into texture, or a new one if 0), mipmapped if wanted. */
    private static int upload(GL3 gl, int texture, BufferedImage image, boolean mipmaps) {
        if (texture == 0) {
            int[] id = new int[1];
            gl.glGenTextures(1, id, 0);
            texture = id[0];
        }
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        gl.glBindTexture(GL.GL_TEXTURE_2D, texture);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, w, h, 0, GL.GL_BGRA, GL.GL_UNSIGNED_BYTE, IntBuffer.wrap(argb));
        if (mipmaps) gl.glGenerateMipmap(GL.GL_TEXTURE_2D);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, mipmaps ? GL.GL_LINEAR_MIPMAP_LINEAR : GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, mipmaps ? GL.GL_REPEAT : GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        return texture;
    }

    public void dispose(GL3 gl) {
        gl.glDeleteBuffers(4, vbo, 0);
        gl.glDeleteBuffers(1, new int[] { sphereIndices }, 0);
        gl.glDeleteVertexArrays(4, vao, 0);
        gl.glDeleteTextures(2, new int[] { starTexture, globeTexture }, 0);
    }

    /** Moves the streaking stars on (towards the pod, at speed). */
    void update(float dt) {
        float travelled = speed * dt;
        for (int i = 0; i < STREAKERS; i++) {
            streakers[i * 3 + 2] -= travelled;
            if (streakers[i * 3 + 2] < -400f) respawn(i, false);
        }
    }

    /**
     * Draws it all, behind the cabin: rotation is the view's turn in the pod's frame, projection
     * the cabin's own (so the window lines up); nothing's written to the depth buffer.
     */
    void render(GL3 gl, Matrix4 projection, Matrix4 rotation) {
        BufferedImage globe = pendingGlobe;
        if (globe != null) {
            pendingGlobe = null;
            globeTexture = upload(gl, globeTexture, globe, true);
            globeReady = true;
        }
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(false);
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_BLEND);
        shader.use(gl);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        shader.setInt(gl, "image", 0);

        Matrix4 inPod = Matrix4.multiply(projection, rotation);
        Matrix4 inSpace = Matrix4.multiply(inPod, Matrix4.multiply(Matrix4.fromColumns(transpose(podToSpace)),
                Matrix4.fromColumns(Affine.rotationY(heading))));
        // (the still stars, so far off they turn with the orbit as if about the pod itself)
        Matrix4 inStars = Matrix4.multiply(inSpace, Matrix4.fromColumns(Affine.rotationY(orbitAngle)));
        float visible = space;
        if (visible > 0.01f) {
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE);
            shader.setFloatArray(gl, "viewProjection", inSpace.toFloatArrayForGLSL());
            // The stars streaming past, and the still ones all round
            shader.setInt(gl, "mode", 0);
            shader.setFloat(gl, "tint", 0.9f, 0.92f, 1f);
            if (streaming > 0.01f) drawStreakers(gl, visible * streaming);
            if (dome > 0.01f) {
                gl.glEnable(GL3.GL_PROGRAM_POINT_SIZE);
                shader.setInt(gl, "asPoints", 1);
                shader.setFloat(gl, "pointSize", 3f);
                shader.setFloat(gl, "alpha", visible * dome);
                shader.setFloatArray(gl, "viewProjection", inStars.toFloatArrayForGLSL());
                gl.glBindVertexArray(vao[1]);
                gl.glDrawArrays(GL.GL_POINTS, 0, DOME);
                shader.setInt(gl, "asPoints", 0);
                shader.setFloatArray(gl, "viewProjection", inSpace.toFloatArrayForGLSL());
                gl.glDisable(GL3.GL_PROGRAM_POINT_SIZE);
            }
            // The star, then the planet in front of it
            if (starAngle > 0f) {
                shader.setInt(gl, "mode", 1);
                float[] d = Affine.normalise(starDirection);
                shader.setFloat(gl, "billboardCentre", d[0] * STAR_DISTANCE, d[1] * STAR_DISTANCE, d[2] * STAR_DISTANCE);
                // (the glow picture is wider than the disc it's of: see ProceduralTextures.SUN_DISC)
                shader.setFloat(gl, "billboardSize", (float) Math.tan(starAngle) * STAR_DISTANCE / ProceduralTextures.SUN_DISC);
                shader.setFloat(gl, "tint", 1f, 1f, 1f);
                gl.glBindTexture(GL.GL_TEXTURE_2D, starTexture);
                gl.glBindVertexArray(vao[2]);
                gl.glDrawArrays(GL.GL_TRIANGLES, 0, 6);
            }
            if (planetAngle > 0f && globeReady) {
                gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
                shader.setInt(gl, "mode", 2);
                float[] d = Affine.normalise(planetDirection);
                float radius = (float) Math.sin(planetAngle) * PLANET_DISTANCE;
                shader.setFloat(gl, "planetCentre", d[0] * PLANET_DISTANCE, d[1] * PLANET_DISTANCE, d[2] * PLANET_DISTANCE);
                shader.setFloat(gl, "planetRadius", radius);
                // Its north a little tipped towards the pod, turning slowly
                float[] turn = Affine.multiply(Affine.rotationY(orbitAngle),
                        Affine.multiply(Affine.rotationX(-0.35f), Affine.multiply(Affine.rotationZ(0.3f), Affine.rotationY(planetSpin))));
                gl.glUniformMatrix3fv(gl.glGetUniformLocation(shader.getID(), "planetTurn"), 1, false,
                        new float[] { turn[0], turn[1], turn[2], turn[4], turn[5], turn[6], turn[8], turn[9], turn[10] }, 0);
                // Lit by the star, beyond it and to one side
                float[] lit = Affine.normalise(light);
                shader.setFloat(gl, "lightDirection", lit[0], lit[1], lit[2]);
                shader.setFloat(gl, "atmosphere", 0.35f * sunTint[0] + 0.15f, 0.5f, 0.85f);
                shader.setFloat(gl, "alpha", Math.min(1f, visible));
                gl.glBindTexture(GL.GL_TEXTURE_2D, globeTexture);
                gl.glBindVertexArray(vao[3]);
                gl.glDrawElements(GL.GL_TRIANGLES, sphereIndexCount, GL.GL_UNSIGNED_INT, 0);
            }
        }
        if (visible < 0.99f) {
            // The sky and the ground through the fog, in the pod's own frame
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            shader.setFloatArray(gl, "viewProjection", Matrix4.multiply(inPod, Matrix4.fromColumns(Affine.scale(100f, 100f, 100f))).toFloatArrayForGLSL());
            shader.setInt(gl, "mode", 3);
            shader.setFloat(gl, "alpha", 1f - visible);
            float[] up = Affine.normalise(skyUp);
            shader.setFloat(gl, "skyUp", up[0], up[1], up[2]);
            shader.setFloat(gl, "skyColour", skyColour[0], skyColour[1], skyColour[2]);
            shader.setFloat(gl, "groundColour", groundColour[0], groundColour[1], groundColour[2]);
            gl.glBindVertexArray(vao[3]);
            gl.glDrawElements(GL.GL_TRIANGLES, sphereIndexCount, GL.GL_UNSIGNED_INT, 0);
        }
        gl.glBindVertexArray(0);
        gl.glDisable(GL.GL_BLEND);
        gl.glDepthMask(true);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_CULL_FACE);
    }

    /** The stars streaming past: each a streak as long as the pod goes in a moment, a few pixels wide, brightest at its head. */
    private void drawStreakers(GL3 gl, float visible) {
        float trail = Math.min(900f, speed * 0.06f);
        int o = 0;
        for (int i = 0; i < STREAKERS; i++) {
            float x = streakers[i * 3], y = streakers[i * 3 + 1], z = streakers[i * 3 + 2];
            // (fading in from far off)
            float b = streakBrightness[i] * Math.min(1f, (DEPTH - z) / 600f);
            // Across the streak, square to it (along z) and to the way it's seen from
            float distance = (float) Math.sqrt(x * x + y * y + z * z);
            float across = (float) Math.hypot(x, y);
            float half = Math.max(0.4f, distance * STREAK_WIDTH) * 0.5f;
            float sx = -y / across * half, sy = x / across * half;
            float length = Math.max(trail, half * 2f);
            float[][] corners = { { x - sx, y - sy, z, b }, { x + sx, y + sy, z, b }, { x + sx, y + sy, z + length, 0f },
                    { x - sx, y - sy, z, b }, { x + sx, y + sy, z + length, 0f }, { x - sx, y - sy, z + length, 0f } };
            for (float[] c : corners) {
                lineData[o] = c[0];
                lineData[o + 1] = c[1];
                lineData[o + 2] = c[2];
                lineData[o + 3] = 0f;
                lineData[o + 4] = 0f;
                lineData[o + 5] = c[3];
                o += 6;
            }
        }
        gl.glBindVertexArray(vao[0]);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0]);
        gl.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, (long) lineData.length * Float.BYTES, FloatBuffer.wrap(lineData));
        shader.setInt(gl, "asPoints", 0);
        shader.setFloat(gl, "alpha", visible);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, STREAKERS * 6);
    }

    /** The transpose of a rotation (its inverse). */
    private static float[] transpose(float[] m) {
        float[] t = Affine.identity();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) t[col * 4 + row] = m[row * 4 + col];
        }
        return t;
    }
}
