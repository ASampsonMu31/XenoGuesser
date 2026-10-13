import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * The weather round the player: how cloudy it is, rain or snow falling (its drops placed and
 * moved on the sphere round the eye), fresh snow settling on the ground, and the haze cloud
 * puts in the distance. Today's cloud cover is drawn at random about the place's average
 * rainfall (the rainfall map, looked up now and then): the wetter the place, the likelier a
 * cloudy day, though any place can have a clear day or an overcast one. The more cloud, the
 * darker, and where the clouds round about are dark enough it rains, or snows below freezing.
 */
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
    // Today: how much wetter (or drier) than usual, and the cloud cover and its darkness now
    private float dayWetness, coverage = -1f, darkness, rainfallTimer;
    // How cloudy the sky looks overhead now (the cover, broken into patches as the eye moves)
    private float cloudiness;
    // Fresh snow over the land: how much there is (0 to 1), how much settles as soon as it
    // starts, how long (seconds) heavy snow takes to cover everything, and how long it takes to
    // melt (longer for each degree below freezing)
    private float freshSnow, coldness;
    private static final float FRESH_AT_ONCE = 0.3f, FRESH_COVERING_TIME = 45f, FRESH_MELT_TIME = 30f;
    // How far today's cloud cover can differ from the usual for a place; how dark the clouds
    // must be for rain to start, and over how much more it gets to its heaviest
    private static final float DAY_TO_DAY = 0.25f, RAIN_DARKNESS = 0.24f, RAIN_DARKNESS_RANGE = 0.36f;
    private static final float CLOUD_SHADING = 0.6f, HAZE = 0.55f;

    private static final class Particle {
        SpherePosition position;
        double altitude;
        double sway;
    }

    public SphereWeather(GL3 gl, long seed) {
        random = new Random(seed * 0x6A09E667F3BCC909L);
        for (int i = 0; i < particles.length; i++) particles[i] = new Particle();
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_sphere_precipitation.txt",
                GamePaths.HOME + "assets/shaders/fs_sphere_precipitation.txt");
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

    /** How hard it's raining (or snowing) where the player is, 0 to 1. */
    public float intensity() {
        return intensity;
    }

    public boolean snowing() {
        return snowing && intensity > 0.02f;
    }

    /** How cloudy the sky overhead looks, 0 to 1 (for the sky's clouds). */
    public float cloudiness() {
        return cloudiness;
    }

    /** How much fresh snow has settled over the land round about, 0 none to 1 a full covering. */
    public float freshSnow() {
        return freshSnow;
    }

    /**
     * A new day (each spawn): a new wind, and a day that may be wetter or drier than usual.
     * The sky is set at once for wherever the player is next, rather than easing.
     */
    public void newDay(Random rand) {
        double direction = rand.nextDouble() * Math.PI * 2.0;
        windEast = Math.cos(direction);
        windSouth = Math.sin(direction);
        windSpeed = 2.0 + rand.nextDouble() * 5.0;
        dayWetness = (float) rand.nextGaussian() * DAY_TO_DAY;
        coverage = -1f;
        intensity = 0f;
        freshSnow = 0f;
        for (Particle particle : particles) particle.position = null;
    }

    /**
     * The colour cloud hazes the distance towards, and how much ({r, g, b, amount}): lit cloud
     * by day, shaded the darker the clouds are.
     */
    public float[] haze(Vector3 skyColour, float daylight) {
        float c = Math.max(0f, coverage);
        float[] lit = { mix(skyColour.x * 0.35f + 0.06f, 0.97f, daylight), mix(skyColour.y * 0.35f + 0.06f, 0.97f, daylight),
                mix(skyColour.z * 0.35f + 0.06f, 0.99f, daylight) };
        float shade = CLOUD_SHADING * 0.5f * (0.2f + 0.8f * darkness);
        float[] dark = { 0.30f, 0.31f, 0.34f };
        return new float[] { lit[0] * (1f - shade + shade * dark[0]), lit[1] * (1f - shade + shade * dark[1]),
                lit[2] * (1f - shade + shade * dark[2]), HAZE * c };
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    /**
     * Moves the weather on and lets the rain fall, given how much rain the place usually gets
     * (0 driest to 1 wettest: the rainfall map) and how many degrees below freezing it is.
     */
    public void update(float deltaTime, float time, Camera camera, PerlinNoise noise,
            float seaLevel, java.util.function.DoubleSupplier rainfallHere, float degreesBelowFreezing) {
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
        // Today's cover, eased towards what suits the place (looked up now and then)
        rainfallTimer -= dt;
        if (coverage < 0f || rainfallTimer <= 0f) {
            rainfallTimer = 0.5f;
            float wantCoverage = Math.max(0.03f, Math.min(1f, 0.05f + 0.9f * (float) rainfallHere.getAsDouble() + dayWetness));
            float wantDarkness = smoothstep(0.3f, 0.95f, wantCoverage);
            if (coverage < 0f) {
                coverage = wantCoverage;
                darkness = wantDarkness;
            } else {
                // Weather changes over tens of seconds, not at once
                coverage += (wantCoverage - coverage) * 0.04f;
                darkness += (wantDarkness - darkness) * 0.04f;
            }
        }
        // The cloud comes in patches, some darker, some breaking up, a few kilometres across
        float patch = Math.max(0f, Math.min(1f, 0.5f + noise.onSphere(surface, 0.0007f, 19.3f, -7.1f) * 1.4f));
        cloudiness = Math.max(0f, Math.min(1f, coverage * (0.7f + 0.6f * patch)));
        // Rain (or snow) falls where the clouds overhead are dark, easing in and out rather than
        // flicking on and off as the player moves
        float darkHere = darkness * (0.55f + 0.9f * patch);
        float wanted = Math.max(0f, Math.min(1f, (darkHere - RAIN_DARKNESS) / RAIN_DARKNESS_RANGE));
        intensity += (wanted - intensity) * Math.min(1f, dt * 0.5f);
        if (intensity < 0.01f && wanted == 0f) intensity = 0f;
        // Snow where it's below freezing, rain where it's above
        snowing = degreesBelowFreezing > 0f;
        coldness = Math.max(0f, degreesBelowFreezing);
        // Fresh snow piles up while it snows (some straight away), and melts off when it stops,
        // the slower the colder
        if (snowing && intensity > 0f) {
            freshSnow = Math.max(freshSnow, FRESH_AT_ONCE);
            freshSnow = Math.min(1f, freshSnow + dt * intensity / FRESH_COVERING_TIME);
        } else {
            freshSnow = Math.max(0f, freshSnow - dt / (FRESH_MELT_TIME * (1f + coldness)));
        }

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
                SpherePosition moved = SphereMath.moveToward(particle.position,
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
        SpherePosition nearby = SphereMath.moveToward(eye, Math.cos(angle), Math.sin(angle), distance);
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
