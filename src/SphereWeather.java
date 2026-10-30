import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import com.xenoguesser.math.Vector3;

/**
 * The clouds overhead and the rain or snow falling from them (the pre-migration Weather,
 * on the sphere).
 *
 * The clouds are one layer high above the ground, drawn over the sky on every pixel that
 * looks up at them: noise fixed to the world (so walking under them, they pass overhead) and
 * drifting with the wind, stretched along it and streaked across it so they look fibrous.
 * How much of the sky they cover, and how dark they are, follow the rainfall where the player
 * is: wetter places have more cloud and darker.
 * <p>
 * The cloud noise is laid out on the day's wind circle: the great circle through where the
 * round starts, running along the wind. A place's cloud coordinates are its distance along
 * that circle (u, with the wind) and its distance off it (v), both along the ground, so round
 * the player (always near the circle) the clouds are the same shape at every latitude, the
 * wind's drift is a turn of the whole layer about the circle's axis, and the only seam is on
 * the far side of the planet. The noise is worked out the same way here as in the shader
 * (fs_sphere_cloud.txt), so the cloud overhead is known here too: rain falls only beneath a
 * dark cloud, harder the darker it is. Harder rain doesn't fall faster, but there are more
 * drops and each is a longer streak. The drops are the colour of the planet's oceans and splash
 * on the ground.
 * <p>
 * Where it's colder than the freezing point of the planet's water, it snows instead: larger
 * flakes, paler (most of the way to white), drifting down slowly and swaying, gone as they land.
 * Drops and flakes fall in a column round the player, kept in the same (u, v) and their height:
 * those the player walks away from are moved round to the far side at the height they were, so
 * walking into fresh ground there's already rain or snow falling all the way down to it.
 */
public final class SphereWeather {

    /** The height of what rain lands on (the ground, a road, the pod) at a place, above the datum. */
    @FunctionalInterface
    public interface Ground {
        double heightAt(SpherePosition place);
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
    // from them (see haze)
    private static final float CLOUD_SHADING = 0.6f, HAZE = 0.55f;
    // How much of the fog lies over the sky right at the horizon
    private static final float HORIZON_FOG = 0.85f;
    // Snow: how fast it falls, how far flakes sway, and how big a flake is
    private static final float SNOW_SPEED = 28f, SNOW_SWAY = 2.2f, FLAKE_SIZE = 0.9f;
    // How much today's cloud cover can differ from the usual for a place (the spread of dayWetness)
    private static final float DAY_TO_DAY = 0.25f;
    // Fresh snow: how much settles as soon as it starts, how long (seconds) heavy snow takes to
    // cover everything, and how long it takes to melt (longer for each degree below freezing)
    private static final float FRESH_AT_ONCE = 0.3f, FRESH_COVERING_TIME = 45f, FRESH_MELT_TIME = 30f;
    // How far round the point overhead the clouds count towards rain (as a fraction of the cloud scale)
    private static final float RAIN_CATCHMENT = 0.6f;

    // The wind circle: a, the up where the day began (u = 0 there); b, the wind's way there
    // (u grows along it); w = a x b, the circle's axis (v grows towards it)
    private double[] axisA = { 1, 0, 0 }, axisB = { 0, 0, -1 }, axisW = { 0, 1, 0 };
    // Today's weather: where in the cloud noise today's clouds are taken from, how much wetter or
    // drier than usual today is, and how streaked along the wind the clouds are
    private float dayOffsetX, dayOffsetZ, dayWetness, streak = 1f;

    // How much of the sky is clouded (0 to 1) and how dark the clouds are, from the rainfall where
    // the player is (-1 until the day's first update)
    private float coverage = -1f, darkness;
    private float rainfallTimer;
    private float intensity;

    // The drops: where each is (u, v and its top's height above the datum), the ground under it,
    // and its splash's time left
    private final float[] dropU = new float[MAX_DROPS], dropV = new float[MAX_DROPS], dropY = new float[MAX_DROPS];
    private final float[] dropGround = new float[MAX_DROPS], splash = new float[MAX_DROPS];
    private final boolean[] dropLive = new boolean[MAX_DROPS];
    // How much of what falls is snow, 0 all rain to 1 all snow (between, sleet: some of each, the
    // flakes small and quick); -1 before it's first known
    private float snowShare = -1f;
    private float freshSnow, coldness, swayTime;
    private final java.util.Random rand;

    private final Shader cloudShader, rainShader, snowShader;
    private final int emptyVao, rainVao, rainVbo;
    private float[] rainVertices = new float[0];

    public SphereWeather(GL3 gl, long seed) {
        rand = new java.util.Random(seed * 0x6A09E667F3BCC909L);
        cloudShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_sphere_sky.txt", GamePaths.HOME + "assets/shaders/fs_sphere_cloud.txt");
        rainShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_sphere_rain.txt", GamePaths.HOME + "assets/shaders/fs_sphere_rain.txt");
        snowShader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_sphere_snow.txt", GamePaths.HOME + "assets/shaders/fs_sphere_snow.txt");
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
     * A new day (each spawn, at the place the round starts): new clouds, a new wind (its
     * circle through here), and a day that may be wetter or drier than usual. The sky is set at
     * once for wherever the player is next, rather than easing.
     */
    public void newDay(java.util.Random dayRand, SpherePosition here) {
        groundCache.clear();
        SphereFrame frame = SphereFrame.at(here, dayRand.nextDouble() * Math.PI * 2);
        axisA = frame.up();
        axisB = frame.xAxis();
        axisW = cross(axisA, axisB);
        dayOffsetX = dayRand.nextFloat() * 5000f;
        dayOffsetZ = dayRand.nextFloat() * 5000f;
        dayWetness = (float) dayRand.nextGaussian() * DAY_TO_DAY;
        // (the main menu's picture, -Dxenoguesser.menushot, is taken on a fine day)
        if (Dev.property("xenoguesser.menushot") != null) dayWetness = -3f * DAY_TO_DAY;
        // How streaked along the wind today's clouds are: some days long fibrous streaks, some
        // days rounder heaps with hardly any direction to them
        streak = (float) Math.pow(dayRand.nextFloat(), 0.7);
        coverage = -1f;
        intensity = 0f;
        freshSnow = 0f;
        // (the mix of snow and rain unknown until the new place's first update: M54, snow was once
        // seen falling at 30 degrees for a while after a snowy round)
        snowShare = -1f;
        java.util.Arrays.fill(dropLive, false);
    }

    /** Developer aid (the trailer's shots): today's cloud cover set, -3 a clear day to 3 a wet one. */
    void devWetness(float wetness) {
        dayWetness = wetness * DAY_TO_DAY;
        coverage = -1f;
    }

    /** How wet the day is (as newDay chose it), to put back after a photograph's clear sky (see LandmarkPhotos). */
    float dayWetness() {
        return dayWetness;
    }

    void setDayWetness(float wetness) {
        dayWetness = wetness;
        coverage = -1f;
        // (whatever was falling worked out afresh, from none)
        intensity = 0f;
    }

    // ------------------------------------------------------------------ the wind circle

    /** A place's cloud coordinates {u, v}: along the wind circle and off it, along the ground. */
    private float[] uv(SpherePosition place) {
        double length = place.length();
        double x = place.x() / length, y = place.y() / length, z = place.z() / length;
        double a = x * axisA[0] + y * axisA[1] + z * axisA[2];
        double b = x * axisB[0] + y * axisB[1] + z * axisB[2];
        double w = x * axisW[0] + y * axisW[1] + z * axisW[2];
        double r = Planet.radius();
        return new float[] { (float) (Math.atan2(b, a) * r), (float) (Math.asin(Math.max(-1, Math.min(1, w))) * r) };
    }

    /** The place at cloud coordinates (u, v), height above the datum, less the eye (camera-relative, for drawing). */
    private void place(double u, double v, double height, SpherePosition eye, float[] out, int at) {
        double r = Planet.radius();
        double lon = u / r, lat = v / r, c = Math.cos(lat);
        double ca = c * Math.cos(lon), cb = c * Math.sin(lon), cw = Math.sin(lat);
        double radius = r + height;
        out[at] = (float) ((axisA[0] * ca + axisB[0] * cb + axisW[0] * cw) * radius - eye.x());
        out[at + 1] = (float) ((axisA[1] * ca + axisB[1] * cb + axisW[1] * cw) * radius - eye.y());
        out[at + 2] = (float) ((axisA[2] * ca + axisB[2] * cb + axisW[2] * cw) * radius - eye.z());
    }

    private SpherePosition placeAt(double u, double v) {
        double r = Planet.radius(), lon = u / r, lat = v / r, c = Math.cos(lat);
        double ca = c * Math.cos(lon), cb = c * Math.sin(lon), cw = Math.sin(lat);
        return new SpherePosition((axisA[0] * ca + axisB[0] * cb + axisW[0] * cw) * r,
                (axisA[1] * ca + axisB[1] * cb + axisW[1] * cw) * r, (axisA[2] * ca + axisB[2] * cb + axisW[2] * cw) * r);
    }

    private static double[] cross(double[] p, double[] q) {
        return new double[] { p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0] };
    }

    private static double dot(double[] p, double[] q) {
        return p[0] * q[0] + p[1] * q[1] + p[2] * q[2];
    }

    // ------------------------------------------------------------------ the cloud noise (as in fs_sphere_cloud.txt)

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

    /** The cloud layer's raw noise at cloud coordinates (u, v) (about 0.5 on average). */
    private float cloudNoise(float u, float v, float time) {
        // Along the wind (u) and across it (v): stretched along, streaked across
        float a = u / CLOUD_SCALE + time * CLOUD_DRIFT + dayOffsetX, b = v / CLOUD_SCALE + dayOffsetZ;
        float qx = a * (1f + (0.45f - 1f) * streak), qy = b * (1f + (1.6f - 1f) * streak);
        float w = fbm(qx * 0.7f + 3.1f, qy * 0.7f + 7.7f, 3);
        float n = fbm(qx + (w - 0.5f) * 2.2f, qy + (w - 0.5f) * 2.2f, 5);
        float f = fbm(a * 0.9f + w * 1.5f, b * 6.0f + w * 1.5f, 3);
        return n * (1f - 0.2f * streak) + f * 0.2f * streak;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    /** How dark the cloud is straight above cloud coordinates (u, v): 0 clear sky, towards 1 a thick dark cloud. */
    private float darknessOverhead(float u, float v, float time) {
        float n = cloudNoise(u, v, time);
        float threshold = 0.72f - 0.50f * coverage;
        float cover = smoothstep(threshold, threshold + 0.18f, n);
        float thickness = smoothstep(threshold, threshold + 0.4f, n);
        return cover * thickness * (0.2f + 0.8f * darkness);
    }

    /** How dark the clouds are over the sky round about overhead: the point straight above and two rings round it, the nearer counting the more. */
    private float darknessAround(float u, float v, float time) {
        float total = 2f * darknessOverhead(u, v, time), weight = 2f;
        for (int ring = 1; ring <= 2; ring++) {
            float radius = CLOUD_SCALE * RAIN_CATCHMENT * ring / 2f, w = ring == 1 ? 1f : 0.6f;
            for (int k = 0; k < 6; k++) {
                double a = k * Math.PI / 3 + ring * 0.5;
                total += w * darknessOverhead(u + radius * (float) Math.cos(a), v + radius * (float) Math.sin(a), time);
                weight += w;
            }
        }
        return total / weight;
    }

    /** How much fresh snow has settled over the land round about, 0 none to 1 a full covering. */
    public float freshSnow() {
        return freshSnow;
    }

    /** How hard it's raining (or snowing) where the player is, 0 to 1. */
    public float intensity() {
        return intensity;
    }

    /** Whether it's more snowing than raining (when anything falls at all). */
    public boolean snowing() {
        return snowShare > 0.5f;
    }

    /** How much of what falls is snow, 0 all rain to 1 all snow (between, sleet). */
    public float snowShare() {
        return Math.max(0f, snowShare);
    }

    // Snow and rain mix over this many degrees either side of freezing (so walking from snow into
    // rain there's a stretch of sleet, snow and rain together, and back)
    private static final float SLEET_DEGREES = 3f;
    // The fresh snow at a place is worked out from what fell on it over this many seconds before
    // now, in steps of this many seconds
    private static final float FRESH_HISTORY = 240f, FRESH_STEP = 3f;

    /** Whether drop i falls as a flake (rather than rain) now: the first snowShare of them, in an order of their own. */
    private boolean flake(int i) {
        return ((i * 2654435761L >>> 8) & 0xFFFF) / 65536f < snowShare;
    }

    // ------------------------------------------------------------------ each frame

    /**
     * Moves the weather on and lets the rain fall. Today's cloud cover is drawn at random about
     * the place's average rainfall (rainfallHere, 0 to 1, looked up now and then rather than
     * every frame): the wetter the place, the likelier a cloudy day, though any place can have
     * a clear day or an overcast one. The more cloud there is, the darker it is, and so the
     * more it rains.
     *
     * Everything here is set by the day, the place and the time alone, never eased from where
     * the player has been: in multiplayer the two PCs share the day and the round's clock, so
     * two players standing in the same place see the same sky, rain and snow (M19).
     */
    public void update(float dt, float time, SpherePosition eye, java.util.function.DoubleSupplier rainfallHere, Ground ground,
                       float degreesBelowFreezing) {
        dt = Math.max(0f, Math.min(dt, 0.1f));
        // Snow where it's below freezing, rain where it's above, and a mix of the two near freezing
        snowShare = smoothstep(-SLEET_DEGREES, SLEET_DEGREES, degreesBelowFreezing);
        coldness = Math.max(0f, degreesBelowFreezing);
        swayTime += dt;
        float[] here = uv(eye);
        rainfallTimer -= dt;
        if (coverage < 0f || rainfallTimer <= 0f) {
            rainfallTimer = 0.5f;
            // (the rainfall map is smooth enough that the cover changes over tens of seconds as
            // the player walks, without easing)
            float rainfall = (float) rainfallHere.getAsDouble();
            coverage = Math.max(0.03f, Math.min(1f, 0.05f + 0.9f * rainfall + dayWetness));
            darkness = smoothstep(0.3f, 0.95f, coverage);
            freshSnow = freshSnowAt(here[0], here[1], time);
        }
        // Rain (or snow) falls when the clouds round about overhead are dark, not just the very
        // spot above, so it comes and goes gradually rather than flicking on and off as the
        // player moves or the clouds drift over
        intensity = fallingAt(here[0], here[1], time);
        updateDrops(dt, here, (float) SphereMath.altitude(eye), ground);
    }

    /** How hard it rains (or snows) at cloud coordinates (u, v) at the given time, 0 to 1. */
    private float fallingAt(float u, float v, float time) {
        return Math.max(0f, Math.min(1f, (darknessAround(u, v, time) - RAIN_DARKNESS) / RAIN_DARKNESS_RANGE));
    }

    /**
     * How much fresh snow lies at cloud coordinates (u, v) by the given time (with today's
     * cover and the snow share and coldness just set, which a place keeps all round, the sun
     * standing still). It's worked out afresh from what fell on that very spot over the last
     * FRESH_HISTORY seconds, rather than piled up as the player went, so it belongs to the place:
     * it piles up while it snows (some straight away), and melts off when it stops, the slower
     * the colder. The steps fall on whole multiples of FRESH_STEP, so it doesn't shimmer as the
     * time moves on.
     */
    private float freshSnowAt(float u, float v, float time) {
        if (snowShare <= 0.5f) return 0f;
        float fresh = 0f;
        long first = (long) Math.floor((time - FRESH_HISTORY) / FRESH_STEP), last = (long) Math.floor(time / FRESH_STEP);
        for (long k = first; k <= last; k++) {
            float end = k == last ? time : (k + 1) * FRESH_STEP, step = end - Math.max(k * FRESH_STEP, time - FRESH_HISTORY);
            if (step <= 0f) continue;
            float falling = fallingAt(u, v, end);
            if (falling > 0f) {
                fresh = Math.max(fresh, FRESH_AT_ONCE * snowShare);
                fresh = Math.min(1f, fresh + step * falling * snowShare / FRESH_COVERING_TIME);
            } else {
                fresh = Math.max(0f, fresh - step / (FRESH_MELT_TIME * (1f + coldness)));
            }
        }
        return fresh;
    }

    private void updateDrops(float dt, float[] eye, float eyeHeight, Ground ground) {
        int wanted = Math.round(MAX_DROPS * intensity);
        for (int i = 0; i < MAX_DROPS; i++) {
            if (!dropLive[i]) {
                // A new drop starts anywhere in the column at first, then from the top
                if (i < wanted) spawn(i, eye, eyeHeight, ground, true);
                continue;
            }
            if (splash[i] > 0f) {
                splash[i] -= dt;
                if (splash[i] <= 0f) {
                    if (i < wanted) spawn(i, eye, eyeHeight, ground, false);
                    else dropLive[i] = false;
                }
                continue;
            }
            // (a flake falls slowly; in sleet, when little of it's snow, quicker, nearly as rain)
            dropY[i] -= (flake(i) ? FALL_SPEED + (SNOW_SPEED - FALL_SPEED) * smoothstep(0f, 1f, snowShare) : FALL_SPEED) * dt;
            // Left behind as the player walks on: brought round to the far side, ahead of them, at
            // the height it had, so the rain or snow there is already falling all the way down
            float du = dropU[i] - eye[0], dv = dropV[i] - eye[1];
            if (Math.abs(du) > RAIN_REACH || Math.abs(dv) > RAIN_REACH) {
                if (i >= wanted) {
                    dropLive[i] = false;
                    continue;
                }
                float span = 2f * RAIN_REACH;
                dropU[i] = eye[0] + (du - span * (float) Math.floor((du + RAIN_REACH) / span));
                dropV[i] = eye[1] + (dv - span * (float) Math.floor((dv + RAIN_REACH) / span));
                dropGround[i] = groundAt(ground, dropU[i], dropV[i]);
                if (dropY[i] <= dropGround[i]) spawn(i, eye, eyeHeight, ground, false);
                continue;
            }
            if (dropY[i] <= dropGround[i]) {
                if (flake(i)) {
                    // The flake is gone as it lands, and another starts at the top
                    if (i < wanted) spawn(i, eye, eyeHeight, ground, false);
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

    // What the drops land on, kept for small squares of the ground this many units across (finding
    // it can mean searching for a road's deck: far too slow for thousands of drops a second), and
    // how many squares are kept before starting afresh
    private static final float GROUND_CELL = 1.5f;
    private static final int GROUND_CACHE_LIMIT = 40_000;
    private final java.util.HashMap<Long, Float> groundCache = new java.util.HashMap<>();

    /** The height a drop at (u, v) lands at: what's under the middle of its square. */
    private float groundAt(Ground ground, float u, float v) {
        long iu = (long) Math.floor(u / GROUND_CELL), iv = (long) Math.floor(v / GROUND_CELL);
        Long key = (iu << 32) ^ (iv & 0xFFFFFFFFL);
        Float known = groundCache.get(key);
        if (known != null) return known;
        if (groundCache.size() >= GROUND_CACHE_LIMIT) groundCache.clear();
        float g = (float) ground.heightAt(placeAt((iu + 0.5) * GROUND_CELL, (iv + 0.5) * GROUND_CELL));
        groundCache.put(key, g);
        return g;
    }

    private void spawn(int i, float[] eye, float eyeHeight, Ground ground, boolean anywhere) {
        float u = eye[0] + (rand.nextFloat() * 2f - 1f) * RAIN_REACH, v = eye[1] + (rand.nextFloat() * 2f - 1f) * RAIN_REACH;
        float g = groundAt(ground, u, v);
        float top = eyeHeight + RAIN_TOP;
        dropU[i] = u;
        dropV[i] = v;
        dropGround[i] = g;
        dropY[i] = anywhere ? g + rand.nextFloat() * Math.max(1f, top - g) : top + rand.nextFloat() * 20f;
        splash[i] = 0f;
        dropLive[i] = true;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * The clouds, over the sky and the sun (drawn after everything solid, only where nothing
     * stands in front, and before anything see-through). skyColour tints them at dusk and by
     * night; daylight is 0 at night to 1 by day; sunDirection is towards the sun in the world;
     * fog is what the distance fades into.
     */
    public void renderClouds(GL3 gl, Camera camera, float aspect, Vector3 skyColour, float daylight, float time,
                             Vector3 sunDirection, float[] sunTint, float[] fog, float seaLevel) {
        if (coverage < 0f) return;
        SpherePosition eye = camera.getSpherePosition();
        Vector3 forward = camera.getSphereForwardDirection();
        Vector3 up = camera.getSphereUpDirection();
        Vector3 right = Vector3.crossProduct(forward, up);
        right.normalize();
        Vector3 correctedUp = Vector3.crossProduct(right, forward);
        correctedUp.normalize();
        double eyeRadius = eye.length();
        double[] eyeUp = { eye.x() / eyeRadius, eye.y() / eyeRadius, eye.z() / eyeRadius };
        // The layer's height above the datum (always well above the eyes), and what the ray to it needs
        double cloudRadius = Planet.radius() + Math.max(seaLevel + CLOUD_ALTITUDE, SphereMath.altitude(eye) + 400.0);
        double rise = cloudRadius - eyeRadius;
        // The way along the wind circle, at the eyes, and the sun's way there in (u, v)
        double[] along = cross(axisW, eyeUp);
        double alongLength = Math.sqrt(dot(along, along));
        double[] sun = { sunDirection.x, sunDirection.y, sunDirection.z };
        float sunU = alongLength > 1e-9 ? (float) (dot(sun, along) / alongLength) : 0f, sunV = (float) dot(sun, axisW);

        // (drawn at the far plane, after everything solid, so only where the sky still shows:
        // the noise is costly, and most of the screen is usually in front of it)
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(GL.GL_LEQUAL);
        gl.glDepthMask(false);
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        Shader s = cloudShader;
        s.use(gl);
        s.setVec3(gl, "cameraForward", forward);
        s.setVec3(gl, "cameraRight", right);
        s.setVec3(gl, "cameraUp", correctedUp);
        s.setFloat(gl, "aspectRatio", aspect);
        s.setFloat(gl, "tanHalfFov", camera.tanHalfFov());
        s.setVec3(gl, "eyeUp", new Vector3((float) eyeUp[0], (float) eyeUp[1], (float) eyeUp[2]));
        s.setVec3(gl, "eyePosition", new Vector3((float) eye.x(), (float) eye.y(), (float) eye.z()));
        s.setFloat(gl, "eyeRadius", (float) eyeRadius);
        // (cloudRadius squared less eyeRadius squared, worked out here in double)
        s.setFloat(gl, "riseSquared", (float) (rise * (cloudRadius + eyeRadius)));
        s.setFloat(gl, "planetRadius", (float) Planet.radius());
        s.setVec3(gl, "axisA", new Vector3((float) axisA[0], (float) axisA[1], (float) axisA[2]));
        s.setVec3(gl, "axisB", new Vector3((float) axisB[0], (float) axisB[1], (float) axisB[2]));
        s.setVec3(gl, "axisW", new Vector3((float) axisW[0], (float) axisW[1], (float) axisW[2]));
        s.setFloat(gl, "scale", CLOUD_SCALE);
        s.setFloat(gl, "drift", time * CLOUD_DRIFT);
        s.setFloat(gl, "coverage", coverage);
        s.setFloat(gl, "darkness", darkness);
        s.setFloat(gl, "daylight", daylight);
        s.setVec3(gl, "skyColour", skyColour);
        s.setFloat(gl, "dayOffset", dayOffsetX, dayOffsetZ);
        s.setFloat(gl, "streak", streak);
        s.setVec3(gl, "sunDirection", sunDirection);
        s.setFloat(gl, "sunAlong", sunU, sunV);
        s.setFloat(gl, "sunColour", 0.8f + 0.2f * sunTint[0], 0.8f + 0.2f * sunTint[1], 0.8f + 0.2f * sunTint[2]);
        s.setFloat(gl, "shading", CLOUD_SHADING);
        // (low in the sky, the clouds and sky fade into the fog the distant land fades into)
        s.setFloat(gl, "haze", fog[0], fog[1], fog[2], HORIZON_FOG);
        // (where the sea's horizon is: cos of its dip = the sea's radius over the eyes')
        double seaRadius = Planet.radius() + seaLevel;
        s.setFloat(gl, "horizonDip", eyeRadius > seaRadius ? (float) Math.sqrt(1.0 - (seaRadius / eyeRadius) * (seaRadius / eyeRadius)) : 0f);
        gl.glBindVertexArray(emptyVao);
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
        gl.glBindVertexArray(0);
        gl.glDisable(GL.GL_BLEND);
        gl.glDepthMask(true);
        gl.glDepthFunc(GL.GL_LESS);
        gl.glEnable(GL.GL_CULL_FACE);
    }

    /**
     * The rain: each falling drop a streak (longer the harder it rains), each landed one a
     * little splash, in the oceans' colour, hidden behind anything solid; or the snow.
     * viewProjection is the camera-relative one; pointScale turns a size in the world into
     * pixels at unit distance.
     */
    public void renderRain(GL3 gl, Matrix4 viewProjection, SpherePosition eye, float[] colour, float daylight, float pointScale) {
        // (the flakes, then the rain: in sleet some of each)
        if (snowShare > 0f) renderSnow(gl, viewProjection, eye, colour, daylight, pointScale);
        if (snowShare >= 1f) return;
        if (intensity <= 0f && !anyLive()) return;
        float streakLength = SHORTEST_STREAK + (LONGEST_STREAK - SHORTEST_STREAK) * intensity;
        int needed = MAX_DROPS * 4 * 4;
        if (rainVertices.length < needed) rainVertices = new float[needed];
        int v = 0;
        for (int i = 0; i < MAX_DROPS; i++) {
            if (!dropLive[i] || (flake(i) && splash[i] <= 0f)) continue;
            float u = dropU[i], w = dropV[i], y = dropY[i];
            if (splash[i] > 0f) {
                // A splash: two drops kicked up and out, fading
                float t = 1f - splash[i] / SPLASH_TIME;
                float spread = 0.6f + 1.4f * t, rise = 1.2f * (float) Math.sin(t * Math.PI);
                float su = ((i * 7919) % 13 - 6) / 6f, sv = ((i * 104729) % 11 - 5) / 5f;
                float alpha = 0.8f * (1f - t);
                v = put(eye, u, w, y, alpha, v);
                v = put(eye, u + su * spread, w + sv * spread, y + rise, alpha, v);
                v = put(eye, u, w, y, alpha, v);
                v = put(eye, u - su * spread, w - sv * spread, y + rise, alpha, v);
                continue;
            }
            float bottom = Math.max(y - streakLength, dropGround[i]);
            v = put(eye, u, w, bottom, 0.75f, v);
            v = put(eye, u, w, y, 0.05f, v);
        }
        int count = v / 4;
        if (count == 0) return;
        upload(gl, v);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDepthMask(false);
        rainShader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(rainShader.getID(), "viewProjection"), 1, false, viewProjection.toFloatArrayForGLSL(), 0);
        float light = 0.45f + 0.55f * daylight;
        rainShader.setFloat(gl, "colour", colour[0] * light, colour[1] * light, colour[2] * light);
        gl.glDrawArrays(GL.GL_LINES, 0, count);
        gl.glDepthMask(true);
        gl.glDisable(GL.GL_BLEND);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    /** The falling snow, each a soft round flake, paler than the oceans' colour. */
    private void renderSnow(GL3 gl, Matrix4 viewProjection, SpherePosition eye, float[] colour, float daylight, float pointScale) {
        int needed = MAX_DROPS * 4;
        if (rainVertices.length < needed) rainVertices = new float[needed];
        int v = 0;
        // (in sleet the flakes are small, wet and sway less)
        float sway = smoothstep(0.3f, 1f, snowShare);
        for (int i = 0; i < MAX_DROPS; i++) {
            if (!dropLive[i] || !flake(i) || splash[i] > 0f) continue;
            v = put(eye, dropU[i] + sway(i) * sway, dropV[i] + sway(i + 7) * sway, dropY[i], 0.9f, v);
        }
        int count = v / 4;
        if (count == 0) return;
        upload(gl, v);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glEnable(GL3.GL_PROGRAM_POINT_SIZE);
        gl.glDepthMask(false);
        snowShader.use(gl);
        gl.glUniformMatrix4fv(gl.glGetUniformLocation(snowShader.getID(), "viewProjection"), 1, false, viewProjection.toFloatArrayForGLSL(), 0);
        float light = 0.5f + 0.5f * daylight;
        // Mostly white, with something of the oceans' colour in it
        snowShader.setFloat(gl, "colour", (0.45f * colour[0] + 0.55f) * light, (0.45f * colour[1] + 0.55f) * light, (0.45f * colour[2] + 0.55f) * light);
        snowShader.setFloat(gl, "flakePixels", FLAKE_SIZE * (0.45f + 0.55f * smoothstep(0.2f, 1f, snowShare)) * pointScale);
        gl.glDrawArrays(GL.GL_POINTS, 0, count);
        gl.glDepthMask(true);
        gl.glDisable(GL3.GL_PROGRAM_POINT_SIZE);
        gl.glDisable(GL.GL_BLEND);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    private void upload(GL3 gl, int floats) {
        gl.glBindVertexArray(rainVao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, rainVbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, floats * 4L, java.nio.FloatBuffer.wrap(rainVertices, 0, floats), GL3.GL_STREAM_DRAW);
    }

    /** A drop's point (camera-relative) and opacity into rainVertices at at. */
    private int put(SpherePosition eye, float u, float v, float height, float alpha, int at) {
        place(u, v, height, eye, rainVertices, at);
        rainVertices[at + 3] = alpha;
        return at + 4;
    }

    private boolean anyLive() {
        for (boolean live : dropLive) if (live) return true;
        return false;
    }

    /**
     * What the distance fades into besides the sky: the clouds' usual colour (as the shader
     * shades them) and how much of it, the cloudier the more ({r, g, b, amount}).
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

    public void dispose(GL3 gl) {
        gl.glDeleteVertexArrays(2, new int[] { emptyVao, rainVao }, 0);
        gl.glDeleteBuffers(1, new int[] { rainVbo }, 0);
    }
}
