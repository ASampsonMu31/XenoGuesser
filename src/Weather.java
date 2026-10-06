import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;

/**
 * The clouds overhead and the rain falling from them.
 *
 * The clouds are one layer high above the ground, drawn over the sky on every pixel that
 * looks up at them: noise fixed to the world (so walking under them, they pass overhead) and
 * drifting with the wind, stretched along it and streaked across it so they look fibrous.
 * How much of the sky they cover, and how dark they are, follow the rainfall where the player
 * is (see RegionalGenerationManager.rainfallMap): wetter places have more cloud and darker.
 *
 * The noise is worked out the same way here as in the shader (fs_cloud.txt), so the cloud
 * straight overhead is known here too: rain falls only beneath a dark cloud, harder the
 * darker it is. Harder rain doesn't fall faster, but there are more drops and each is a
 * longer streak. The drops are the colour of the planet's oceans and splash on the ground.
 *
 * Where it's colder than the freezing point of the planet's water, it snows instead: larger
 * flakes, paler (most of the way to white), drifting down slowly and swaying, gone as they
 * land (on the ground, a roof or a rock).
 *
 * Drops and flakes fall in a column round the player: those the player walks away from are
 * moved round to the far side at the height they were, so walking into fresh ground there's
 * already rain or snow falling all the way down to it.
 */
public class Weather {

    /** The height of the ground (or whatever the rain lands on) at a point. */
    @FunctionalInterface
    public interface Ground {
        float heightAt(float x, float z);
    }

    // The cloud layer: how far above the sea it is, the size of its features, and how fast it drifts
    private static final float CLOUD_ALTITUDE = 1500f, CLOUD_SCALE = 900f, CLOUD_DRIFT = 0.004f;
    // Rain: the most drops at once, how fast they fall, their streaks' length in the lightest and
    // heaviest rain, how far round the player they fall, and how high above them they start
    private static final int MAX_DROPS = 2600;
    private static final float FALL_SPEED = 150f, SHORTEST_STREAK = 2.5f, LONGEST_STREAK = 13f;
    private static final float RAIN_REACH = 75f, RAIN_TOP = 70f, SPLASH_TIME = 0.14f;
    // How dark the cloud overhead must be to rain, and how much darker for the heaviest rain
    private static final float RAIN_DARKNESS = 0.24f, RAIN_DARKNESS_RANGE = 0.36f;
    // The clouds as drawn are lighter than they are dark for the rain: how far towards their
    // darkest grey the thickest are shaded, and how much of the sky's colour the distance takes
    // from them (see hazeColour)
    private static final float CLOUD_SHADING = 0.6f, HAZE = 0.55f;
    // How much of the fog lies over the sky right at the horizon
    private static final float HORIZON_FOG = 0.85f;

    private float windX, windZ;
    private final float seaLevel;
    // Today's weather: where in the cloud noise today's clouds are taken from, and how much
    // wetter or drier than usual today is (see newDay)
    private float dayOffsetX, dayOffsetZ, dayWetness;

    // How much of the sky is clouded (0 to 1) and how dark the clouds are, eased towards the
    // rainfall where the player is
    private float coverage = -1f, darkness;
    private float rainfallTimer;
    private float intensity;

    // The drops: where each is (its top), the ground under it, and its splash's time left
    private final float[] dropX = new float[MAX_DROPS], dropY = new float[MAX_DROPS], dropZ = new float[MAX_DROPS];
    private final float[] dropGround = new float[MAX_DROPS], splash = new float[MAX_DROPS];

    // Snow: how fast it falls, how far flakes sway, and how big a flake is
    private static final float SNOW_SPEED = 28f, SNOW_SWAY = 2.2f, FLAKE_SIZE = 0.9f;
    private boolean snowing;
    // Fresh snow over the land: how much there is (0 to 1), how much settles as soon as it
    // starts, how long (seconds) heavy snow takes to cover everything, and how long it takes to
    // melt (longer for each degree below freezing)
    private float freshSnow;
    private static final float FRESH_AT_ONCE = 0.3f, FRESH_COVERING_TIME = 45f, FRESH_MELT_TIME = 30f;
    private float coldness;   // degrees below freezing
    private float swayTime;
    private final boolean[] dropLive = new boolean[MAX_DROPS];
    private final java.util.Random rand = new java.util.Random();

    private Shader cloudShader, rainShader, snowShader;
    private int emptyVao, rainVao, rainVbo;
    private float[] rainVertices = new float[0];
    private int rainVertexCount;

    public Weather(long seed, float seaLevel) {
        this.seaLevel = seaLevel;
        newDay(new java.util.Random(seed * 89L + 3L));
    }

    // How much today's cloud cover can differ from the usual for a place (the spread of dayWetness)
    private static final float DAY_TO_DAY = 0.25f;

    /**
     * A new day (each spawn): new clouds, a new wind, and a day that may be wetter or drier
     * than usual. The sky is set at once for wherever the player is next, rather than easing.
     */
    public void newDay(java.util.Random rand) {
        double angle = rand.nextDouble() * Math.PI * 2;
        windX = (float) Math.cos(angle);
        windZ = (float) Math.sin(angle);
        dayOffsetX = rand.nextFloat() * 5000f;
        dayOffsetZ = rand.nextFloat() * 5000f;
        dayWetness = (float) rand.nextGaussian() * DAY_TO_DAY;
        coverage = -1f;
        intensity = 0f;
        freshSnow = 0f;
        java.util.Arrays.fill(dropLive, false);
    }

    // ------------------------------------------------------------------ the cloud noise (as in fs_cloud.txt)

    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777216f;
    }

    private static float noise(float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float ux = fx * fx * (3f - 2f * fx), uy = fy * fy * (3f - 2f * fy);
        float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        float bottom = a + (b - a) * ux, top = c + (d - c) * ux;
        return bottom + (top - bottom) * uy;
    }

    private static float fbm(float x, float y, int octaves) {
        float value = 0f, amplitude = 0.5f, total = 0f;
        for (int i = 0; i < octaves; i++) {
            value += amplitude * noise(x, y);
            total += amplitude;
            x = x * 2.03f + 17.3f;
            y = y * 2.03f + 9.1f;
            amplitude *= 0.5f;
        }
        return value / total;
    }

    /** The cloud layer's raw noise at a point (about 0.5 on average). */
    private float cloudNoise(float worldX, float worldZ, float time) {
        float px = worldX / CLOUD_SCALE + windX * time * CLOUD_DRIFT + dayOffsetX;
        float pz = worldZ / CLOUD_SCALE + windZ * time * CLOUD_DRIFT + dayOffsetZ;
        // Along the wind and across it: stretched along, streaked across
        float a = px * windX + pz * windZ, b = -px * windZ + pz * windX;
        float qx = a * 0.45f, qy = b * 1.6f;
        float w = fbm(qx * 0.7f + 3.1f, qy * 0.7f + 7.7f, 3);
        float n = fbm(qx + (w - 0.5f) * 2.2f, qy + (w - 0.5f) * 2.2f, 5);
        float f = fbm(a * 0.9f + w * 1.5f, b * 6.0f + w * 1.5f, 3);
        return n * 0.8f + f * 0.2f;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    /** How dark the cloud is straight above a point: 0 clear sky, towards 1 a thick dark cloud. */
    public float darknessOverhead(float x, float z, float time) {
        float n = cloudNoise(x, z, time);
        float threshold = 0.72f - 0.50f * coverage;
        float cover = smoothstep(threshold, threshold + 0.18f, n);
        float thickness = smoothstep(threshold, threshold + 0.4f, n);
        return cover * thickness * (0.2f + 0.8f * darkness);
    }

    // How far round the point overhead the clouds count towards rain (as a fraction of the cloud scale)
    private static final float RAIN_CATCHMENT = 0.6f;

    /**
     * How dark the clouds are over the sky round about overhead: the point straight above and
     * two rings round it, the nearer counting the more.
     */
    private float darknessAround(float x, float z, float time) {
        float total = 2f * darknessOverhead(x, z, time), weight = 2f;
        for (int ring = 1; ring <= 2; ring++) {
            float radius = CLOUD_SCALE * RAIN_CATCHMENT * ring / 2f, w = ring == 1 ? 1f : 0.6f;
            for (int k = 0; k < 6; k++) {
                double a = k * Math.PI / 3 + ring * 0.5;
                total += w * darknessOverhead(x + radius * (float) Math.cos(a), z + radius * (float) Math.sin(a), time);
                weight += w;
            }
        }
        return total / weight;
    }

    /** How much fresh snow has settled over the land round about, 0 none to 1 a full covering. */
    public float freshSnow() {
        return freshSnow;
    }

    /** How hard it's raining where the player is, 0 to 1. */
    public float intensity() {
        return intensity;
    }

    // ------------------------------------------------------------------ each frame

    /**
     * Moves the weather on and lets the rain fall. Today's cloud cover is drawn at random about
     * the place's average rainfall (the rainfall map, 0 to 1, looked up now and then rather than
     * every frame): the wetter the place, the likelier a cloudy day, though any place can have
     * a clear day or an overcast one. The more cloud there is, the darker it is, and so the
     * more it rains.
     */
    public void update(float dt, float time, Vector3 eye, java.util.function.DoubleSupplier rainfallHere, Ground ground, float degreesBelowFreezing) {
        // Snow where it's below freezing, rain where it's above; the falling drops start afresh when it turns
        boolean snowNow = degreesBelowFreezing > 0f;
        if (snowNow != snowing) {
            snowing = snowNow;
            java.util.Arrays.fill(dropLive, false);
        }
        coldness = Math.max(0f, degreesBelowFreezing);
        swayTime += dt;
        rainfallTimer -= dt;
        if (coverage < 0f || rainfallTimer <= 0f) {
            rainfallTimer = 0.5f;
            float rainfall = (float) rainfallHere.getAsDouble();
            float wantCoverage = Math.max(0.03f, Math.min(1f, 0.05f + 0.9f * rainfall + dayWetness));
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
        // Rain (or snow) falls when the clouds round about overhead are dark, not just the very
        // spot above, and eases in and out rather than flicking on and off as the player moves
        float wanted = Math.max(0f, Math.min(1f, (darknessAround(eye.x, eye.z, time) - RAIN_DARKNESS) / RAIN_DARKNESS_RANGE));
        intensity += (wanted - intensity) * Math.min(1f, dt * 0.5f);
        if (intensity < 0.01f && wanted == 0f) intensity = 0f;
        // Fresh snow piles up while it snows (some straight away), and melts off when it stops,
        // the slower the colder
        if (snowing && intensity > 0f) {
            freshSnow = Math.max(freshSnow, FRESH_AT_ONCE);
            freshSnow = Math.min(1f, freshSnow + dt * intensity / FRESH_COVERING_TIME);
        } else {
            freshSnow = Math.max(0f, freshSnow - dt / (FRESH_MELT_TIME * (1f + coldness)));
        }
        updateDrops(dt, eye, ground);
    }

    /** Whether it's snowing rather than raining (when anything falls at all). */
    public boolean snowing() {
        return snowing;
    }

    private void updateDrops(float dt, Vector3 eye, Ground ground) {
        int wanted = Math.round(MAX_DROPS * intensity);
        for (int i = 0; i < MAX_DROPS; i++) {
            if (!dropLive[i]) {
                // A new drop starts anywhere in the column at first, then from the top
                if (i < wanted) spawn(i, eye, ground, true);
                continue;
            }
            if (splash[i] > 0f) {
                splash[i] -= dt;
                if (splash[i] <= 0f) {
                    if (i < wanted) spawn(i, eye, ground, false);
                    else dropLive[i] = false;
                }
                continue;
            }
            dropY[i] -= (snowing ? SNOW_SPEED : FALL_SPEED) * dt;
            // Left behind as the player walks on: brought round to the far side, ahead of them, at
            // the height it had, so the rain or snow there is already falling all the way down
            float dx = dropX[i] - eye.x, dz = dropZ[i] - eye.z;
            if (Math.abs(dx) > RAIN_REACH || Math.abs(dz) > RAIN_REACH) {
                if (i >= wanted) {
                    dropLive[i] = false;
                    continue;
                }
                float span = 2f * RAIN_REACH;
                dropX[i] = eye.x + (dx - span * (float) Math.floor((dx + RAIN_REACH) / span));
                dropZ[i] = eye.z + (dz - span * (float) Math.floor((dz + RAIN_REACH) / span));
                dropGround[i] = Math.max(ground.heightAt(dropX[i], dropZ[i]), seaLevel);
                if (dropY[i] <= dropGround[i]) spawn(i, eye, ground, false);
                continue;
            }
            if (dropY[i] <= dropGround[i]) {
                if (snowing) {
                    // The flake is gone as it lands, and another starts at the top
                    if (i < wanted) spawn(i, eye, ground, false);
                    else dropLive[i] = false;
                    continue;
                }
                dropY[i] = dropGround[i];
                splash[i] = SPLASH_TIME;
            }
        }
    }

    /** A flake's sideways drift as it falls (each flake its own, from its number). */
    private float sway(int i) {
        return SNOW_SWAY * (float) Math.sin(swayTime * (0.8f + (i % 5) * 0.15f) + i * 1.7f);
    }

    private void spawn(int i, Vector3 eye, Ground ground, boolean anywhere) {
        float x = eye.x + (rand.nextFloat() * 2f - 1f) * RAIN_REACH, z = eye.z + (rand.nextFloat() * 2f - 1f) * RAIN_REACH;
        float g = Math.max(ground.heightAt(x, z), seaLevel);
        float top = eye.y + RAIN_TOP;
        dropX[i] = x;
        dropZ[i] = z;
        dropGround[i] = g;
        dropY[i] = anywhere ? g + rand.nextFloat() * Math.max(1f, top - g) : top + rand.nextFloat() * 20f;
        splash[i] = 0f;
        dropLive[i] = true;
    }

    // ------------------------------------------------------------------ drawing

    private void ensureGl(GL3 gl) {
        if (cloudShader != null) return;
        cloudShader = new Shader(gl, "assets/shaders/vs_cloud.txt", "assets/shaders/fs_cloud.txt").flat();
        rainShader = new Shader(gl, "assets/shaders/vs_rain.txt", "assets/shaders/fs_rain.txt").flat();
        snowShader = new Shader(gl, "assets/shaders/vs_snow.txt", "assets/shaders/fs_snow.txt").flat();
        int[] ids = new int[2];
        gl.glGenVertexArrays(2, ids, 0);
        emptyVao = ids[0];
        rainVao = ids[1];
        gl.glGenBuffers(1, ids, 0);
        rainVbo = ids[0];
        gl.glBindVertexArray(rainVao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, rainVbo);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 4, GL.GL_FLOAT, false, 16, 0);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    /**
     * The clouds, over the sky (drawn straight after it, before anything that stands in
     * front). skyColour tints them at dusk and by night; daylight is 0 at night to 1 by day.
     */
    public void renderClouds(GL3 gl, Camera camera, float aspect, Vector3 skyColour, float daylight, float time, float[] sunDirection, float[] sunTint, float[] fog) {
        ensureGl(gl);
        if (coverage < 0f) return;
        Vector3 eye = camera.getPosition();
        Vector3 forward = camera.getForwardDirection();
        float fx = forward.x, fy = forward.y, fz = forward.z;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl; fy /= fl; fz /= fl;
        // Right = forward x up, up = right x forward
        float rx = -fz, rz = fx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-4f) { rx = 1f; rz = 0f; rl = 1f; }
        rx /= rl; rz /= rl;
        float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;

        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        cloudShader.use(gl);
        cloudShader.setFloat(gl, "forward", fx, fy, fz);
        cloudShader.setFloat(gl, "right", rx, 0f, rz);
        cloudShader.setFloat(gl, "up", ux, uy, uz);
        cloudShader.setFloat(gl, "lens", (float) Math.tan(Math.toRadians(45.0 / 2.0)), aspect);
        cloudShader.setFloat(gl, "eye", eye.x, eye.y, eye.z);
        cloudShader.setFloat(gl, "cloudHeight", Math.max(seaLevel + CLOUD_ALTITUDE, eye.y + 400f));
        cloudShader.setFloat(gl, "wind", windX, windZ);
        cloudShader.setFloat(gl, "scale", CLOUD_SCALE);
        cloudShader.setFloat(gl, "drift", time * CLOUD_DRIFT);
        cloudShader.setFloat(gl, "coverage", coverage);
        cloudShader.setFloat(gl, "darkness", darkness);
        cloudShader.setFloat(gl, "daylight", daylight);
        cloudShader.setFloat(gl, "skyColour", skyColour.x, skyColour.y, skyColour.z);
        cloudShader.setFloat(gl, "dayOffset", dayOffsetX, dayOffsetZ);
        cloudShader.setFloat(gl, "sunDirection", sunDirection[0], sunDirection[1], sunDirection[2]);
        cloudShader.setFloat(gl, "sunColour", 0.8f + 0.2f * sunTint[0], 0.8f + 0.2f * sunTint[1], 0.8f + 0.2f * sunTint[2]);
        cloudShader.setFloat(gl, "shading", CLOUD_SHADING);
        // (low in the sky, the clouds and sky fade into the fog the distant land fades into)
        cloudShader.setFloat(gl, "haze", fog[0], fog[1], fog[2], HORIZON_FOG);
        gl.glBindVertexArray(emptyVao);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
        gl.glBindVertexArray(0);
        gl.glDisable(GL.GL_BLEND);
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_DEPTH_TEST);
    }

    /**
     * The rain: each falling drop a streak (longer the harder it rains), each landed one a
     * little splash, in the oceans' colour, hidden behind anything solid.
     */
    public void renderRain(GL3 gl, Matrix4 viewProjection, float[] colour, float daylight, float pointScale) {
        ensureGl(gl);
        renderSnow(gl, viewProjection, colour, daylight, pointScale);
        if (snowing) return;
        if (intensity <= 0f && !anyLive()) return;
        float streak = SHORTEST_STREAK + (LONGEST_STREAK - SHORTEST_STREAK) * intensity;
        int needed = MAX_DROPS * 4 * 4;
        if (rainVertices.length < needed) rainVertices = new float[needed];
        int v = 0;
        for (int i = 0; i < MAX_DROPS; i++) {
            if (!dropLive[i]) continue;
            float x = dropX[i], y = dropY[i], z = dropZ[i];
            if (splash[i] > 0f) {
                // A splash: two drops kicked up and out, fading
                float t = 1f - splash[i] / SPLASH_TIME;
                float spread = 0.6f + 1.4f * t, rise = 1.2f * (float) Math.sin(t * Math.PI);
                float sx = ((i * 7919) % 13 - 6) / 6f, sz = ((i * 104729) % 11 - 5) / 5f;
                float alpha = 0.8f * (1f - t);
                v = put(rainVertices, v, x, y, z, alpha);
                v = put(rainVertices, v, x + sx * spread, y + rise, z + sz * spread, alpha);
                v = put(rainVertices, v, x, y, z, alpha);
                v = put(rainVertices, v, x - sx * spread, y + rise, z - sz * spread, alpha);
                continue;
            }
            float bottom = Math.max(y - streak, dropGround[i]);
            v = put(rainVertices, v, x, bottom, z, 0.75f);
            v = put(rainVertices, v, x, y, z, 0.05f);
        }
        rainVertexCount = v / 4;
        if (rainVertexCount == 0) return;
        gl.glBindVertexArray(rainVao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, rainVbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, v * 4L, java.nio.FloatBuffer.wrap(rainVertices, 0, v), GL3.GL_STREAM_DRAW);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        rainShader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(rainShader.getID(), "viewProjection"), 1, false, viewProjection.toFloatArrayForGLSL(), 0);
        float light = 0.45f + 0.55f * daylight;
        rainShader.setFloat(gl, "colour", colour[0] * light, colour[1] * light, colour[2] * light);
        gl.glDrawArrays(GL.GL_LINES, 0, rainVertexCount);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    /**
     * What the distance fades into besides the sky: the clouds' usual colour (as the shader
     * shades them) and how much of it, the cloudier the more ({r, g, b, amount}).
     */
    public float[] haze(Vector3 skyColour, float daylight) {
        float c = Math.max(0f, coverage);
        float[] lit = {
            mix(skyColour.x * 0.35f + 0.06f, 0.97f, daylight), mix(skyColour.y * 0.35f + 0.06f, 0.97f, daylight), mix(skyColour.z * 0.35f + 0.06f, 0.99f, daylight)
        };
        float shade = CLOUD_SHADING * 0.5f * (0.2f + 0.8f * darkness);
        float[] dark = { 0.30f, 0.31f, 0.34f };
        return new float[] { lit[0] * (1f - shade + shade * dark[0]), lit[1] * (1f - shade + shade * dark[1]),
                lit[2] * (1f - shade + shade * dark[2]), HAZE * c };
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * The falling snow, each a soft round flake, paler than the oceans' colour. pointScale
     * turns a size in the world into pixels at unit distance.
     */
    private void renderSnow(GL3 gl, Matrix4 viewProjection, float[] colour, float daylight, float pointScale) {
        if (!snowing) return;
        int needed = MAX_DROPS * 4;
        if (rainVertices.length < needed) rainVertices = new float[needed];
        int v = 0;
        for (int i = 0; i < MAX_DROPS; i++) {
            if (!dropLive[i]) continue;
            v = put(rainVertices, v, dropX[i] + sway(i), dropY[i], dropZ[i] + sway(i + 7), 0.9f);
        }
        int count = v / 4;
        if (count == 0) return;
        gl.glBindVertexArray(rainVao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, rainVbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, v * 4L, java.nio.FloatBuffer.wrap(rainVertices, 0, v), GL3.GL_STREAM_DRAW);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glEnable(GL3.GL_PROGRAM_POINT_SIZE);
        gl.glDepthMask(false);
        snowShader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(snowShader.getID(), "viewProjection"), 1, false, viewProjection.toFloatArrayForGLSL(), 0);
        float light = 0.5f + 0.5f * daylight;
        // Mostly white, with something of the oceans' colour in it
        snowShader.setFloat(gl, "colour", (0.45f * colour[0] + 0.55f) * light, (0.45f * colour[1] + 0.55f) * light, (0.45f * colour[2] + 0.55f) * light);
        snowShader.setFloat(gl, "flakePixels", FLAKE_SIZE * pointScale);
        gl.glDrawArrays(GL.GL_POINTS, 0, count);
        gl.glDepthMask(true);
        gl.glDisable(GL3.GL_PROGRAM_POINT_SIZE);
        gl.glDisable(GL.GL_BLEND);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    private boolean anyLive() {
        for (boolean live : dropLive) if (live) return true;
        return false;
    }

    private static int put(float[] into, int at, float x, float y, float z, float alpha) {
        into[at] = x;
        into[at + 1] = y;
        into[at + 2] = z;
        into[at + 3] = alpha;
        return at + 4;
    }
}
