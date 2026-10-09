import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;
import java.nio.FloatBuffer;
import java.util.Random;

/** Local rain and snow whose positions and motion follow the sphere rather than the map chart. */
public final class SphereWeather {
    private static final int PARTICLE_COUNT = 520;
    private static final double PARTICLE_REACH = 90.0;
    private static final double RAIN_TOP = 85.0;
    private static final double RAIN_SPEED = 145.0;
    private static final double SNOW_SPEED = 27.0;
    private final Random random;
    private final Particle[] particles = new Particle[PARTICLE_COUNT];
    private final Shader shader;
    private final int[] vao = new int[1];
    private final int[] vbo = new int[1];
    private float intensity;
    private boolean snowing;
    private double windEast;
    private double windSouth;
    private double windSpeed;
    private double windChangeTime;

    private static final class Particle {
        SpherePosition position;
        double altitude;
        double sway;
    }

    public SphereWeather(GL3 gl, long seed) {
        random = new Random(seed * 0x6A09E667F3BCC909L);
        for (int i = 0; i < particles.length; i++) particles[i] = new Particle();
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_sphere_precipitation.txt",
                GamePaths.HOME + "assets/shaders/fs_sphere_precipitation.txt").flat();
        gl.glGenVertexArrays(1, vao, 0);
        gl.glGenBuffers(1, vbo, 0);
        gl.glBindVertexArray(vao[0]);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0]);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 3 * Float.BYTES, 0);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        chooseWind();
    }

    public float intensity() {
        return intensity;
    }

    public boolean snowing() {
        return snowing && intensity > 0.02f;
    }

    public void update(float deltaTime, float time, Camera camera, PerlinNoise noise,
            float seaLevel, float freezingPoint) {
        float dt = Math.max(0f, Math.min(deltaTime, 0.1f));
        if (time >= windChangeTime) {
            chooseWind();
            windChangeTime = time + 900f + random.nextFloat() * 900f;
        }

        SpherePosition eye = camera.getSpherePosition();
        double eyeRadius = eye.length();
        GeodesicChunkGrid.Direction local = new GeodesicChunkGrid.Direction(
                eye.x() / eyeRadius, eye.y() / eyeRadius, eye.z() / eyeRadius);
        float[] surface = { (float) (local.x() * Planet.radius()),
                (float) (local.y() * Planet.radius()), (float) (local.z() * Planet.radius()) };
        float weatherNoise = noise.onSphere(surface, 0.0007f, 19.3f, -7.1f);
        intensity = Math.max(0f, Math.min(1f, (weatherNoise + 0.16f) * 1.7f));
        double latitude = Math.asin(local.y());
        float temperature = 24f - (float) Math.abs(Math.toDegrees(latitude)) * 0.72f
                - Math.max(0f, TerrainMesh.getLayeredHeight(
                        new SpherePosition(surface[0], surface[1], surface[2]), noise)) * 0.006f;
        snowing = temperature < freezingPoint;

        int activeCount = Math.round(PARTICLE_COUNT * intensity);
        for (int i = 0; i < particles.length; i++) {
            Particle particle = particles[i];
            if (i >= activeCount) {
                particle.position = null;
                continue;
            }
            if (particle.position == null || outsideReach(particle.position, eye)) {
                spawn(particle, eye, noise, seaLevel, true);
            } else {
                double speed = snowing ? SNOW_SPEED : RAIN_SPEED;
                particle.altitude -= speed * dt;
                double swayDistance = snowing
                        ? Math.sin(time * 1.7 + particle.sway) * 1.8 * dt : 0.0;
                double eastMotion = windEast * windSpeed * dt + swayDistance;
                double southMotion = windSouth * windSpeed * dt;
                double travel = Math.hypot(eastMotion, southMotion);
                SpherePosition moved = Planet.moveOnSurface(particle.position,
                        eastMotion / Math.max(1e-12, travel), southMotion / Math.max(1e-12, travel), travel);
                double movedRadius = moved.length();
                double ground = groundHeight(moved, noise, seaLevel);
                particle.position = moved.scale((Planet.radius() + ground + Math.max(0.0, particle.altitude))
                        / movedRadius);
                if (particle.altitude <= 0.0) spawn(particle, eye, noise, seaLevel, false);
            }
        }
    }

    public void render(GL3 gl, Camera camera, Matrix4 viewProjection, float daylight) {
        if (intensity <= 0.02f) return;
        int activeCount = Math.round(PARTICLE_COUNT * intensity);
        int verticesPerParticle = snowing ? 1 : 2;
        float[] data = new float[activeCount * verticesPerParticle * 3];
        int offset = 0;
        for (int i = 0; i < activeCount; i++) {
            Particle particle = particles[i];
            if (particle.position == null) continue;
            offset = putPosition(data, offset, particle.position);
            if (!snowing) {
                double length = particle.position.length();
                SpherePosition lower = particle.position.scale((length - 5.5) / length);
                offset = putPosition(data, offset, lower);
            }
        }
        if (offset == 0) return;
        FloatBuffer buffer = Buffers.newDirectFloatBuffer(java.util.Arrays.copyOf(data, offset));
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        shader.use(gl);
        shader.setFloatArray(gl, "viewProjection", viewProjection.toFloatArrayForGLSL());
        SpherePosition cameraPosition = camera.getSpherePosition();
        shader.setVec3(gl, "sphereCamera", new Vector3((float) cameraPosition.x(),
                (float) cameraPosition.y(), (float) cameraPosition.z()));
        shader.setFloat(gl, "pointSize", snowing ? 3.2f : 1f);
        float brightness = 0.55f + Math.max(0f, Math.min(1f, daylight)) * 0.4f;
        shader.setFloat(gl, "precipitationColour", snowing ? 0.9f : 0.55f * brightness,
                snowing ? 0.94f : 0.68f * brightness, snowing ? 1f : 0.82f * brightness,
                snowing ? 0.85f : 0.55f);
        shader.setInt(gl, "flakes", snowing ? 1 : 0);
        gl.glBindVertexArray(vao[0]);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) offset * Float.BYTES, buffer, GL.GL_DYNAMIC_DRAW);
        gl.glDrawArrays(snowing ? GL.GL_POINTS : GL.GL_LINES, 0, offset / 3);
        gl.glBindVertexArray(0);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
    }

    public void dispose(GL3 gl) {
        if (vao[0] != 0) gl.glDeleteVertexArrays(1, vao, 0);
        if (vbo[0] != 0) gl.glDeleteBuffers(1, vbo, 0);
        vao[0] = 0;
        vbo[0] = 0;
    }

    private void spawn(Particle particle, SpherePosition eye, PerlinNoise noise,
            float seaLevel, boolean anywhere) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = Math.sqrt(random.nextDouble()) * PARTICLE_REACH;
        SpherePosition nearby = Planet.moveOnSurface(eye, Math.cos(angle), Math.sin(angle), distance);
        double ground = groundHeight(nearby, noise, seaLevel);
        particle.altitude = anywhere ? random.nextDouble() * RAIN_TOP : RAIN_TOP;
        particle.sway = random.nextDouble() * Math.PI * 2.0;
        particle.position = nearby.scale((Planet.radius() + ground + particle.altitude) / nearby.length());
    }

    private static double groundHeight(SpherePosition position, PerlinNoise noise, float seaLevel) {
        float terrain = TerrainMesh.getLayeredHeight(position, noise);
        return Math.max(terrain, seaLevel);
    }

    private static boolean outsideReach(SpherePosition position, SpherePosition eye) {
        double dot = position.x() * eye.x() + position.y() * eye.y() + position.z() * eye.z();
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0,
                dot / (position.length() * eye.length()))));
        return angle * Planet.radius() > PARTICLE_REACH * 1.6;
    }

    private static int putPosition(float[] data, int offset, SpherePosition position) {
        data[offset++] = (float) position.x();
        data[offset++] = (float) position.y();
        data[offset++] = (float) position.z();
        return offset;
    }

    private void chooseWind() {
        double direction = random.nextDouble() * Math.PI * 2.0;
        windEast = Math.cos(direction);
        windSouth = Math.sin(direction);
        windSpeed = 2.0 + random.nextDouble() * 5.0;
    }
}
