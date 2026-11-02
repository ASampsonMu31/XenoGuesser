import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.xenoguesser.math.Matrix4;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * What's seen through the landing pod's window (see PodSequence): drawn first, behind the
 * cabin, which hides it everywhere but the door's glass. Stars streaming past as the pod
 * cruises, slowing to points; on some worlds the rocks of an asteroid belt tumbling past (a few
 * small ones striking the glass) or a gas giant, banded and perhaps ringed, swung round close;
 * the planet's star growing ahead and then the planet itself, its land and sea as on the map,
 * snow where it lies, its mountains standing up from it to scale and clouds over it
 * (PlanetGlobeTexture), with its moon if it has one; and, once the pod's in the air with its
 * window fogged, just a bright sky above and the ground below. What's met on the way, the star's
 * size and colour and the moon are the world's own (StarSystem), as the game's sky shows them.
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
    // The gas giant's radius as drawn (where it is is given in its radii)
    private static final float GIANT_UNIT = 1000f;
    // The belt's rocks: how many, the box they tumble through (either way, and ahead), the
    // nearest any passes the pod's line and how big they are (units)
    private static final int ROCKS = 90, ROCK_SHAPES = 3;
    private static final float ROCK_SPREAD = 520f, ROCK_DEPTH = 3000f, ROCK_CLEAR = 45f, ROCK_SMALLEST = 3f, ROCK_LARGEST = 26f;
    // A pebble that strikes the glass: how fast it comes (units a second, in the cabin's own units)
    // and how long it's seen coming, and how big it is
    private static final float STRIKE_SPEED = 70f, STRIKE_SEEN = 1.6f, PEBBLE = 0.35f;

    // ---- Set each frame by the timeline
    /** How fast the pod's going, for the streaks (units a second; 0 still). */
    float speed;
    /** How much the streaking stars show (0..1), and the still dome of stars (0..1). */
    float streaming = 1f, dome;
    /** The streaks' colour: starlight, or the belt's dust. */
    final float[] streakTint = { 0.9f, 0.92f, 1f };
    /** The way the star's light falls on the planet. */
    final float[] light = { 0f, 0f, 1f };
    /** How wide the star looks (its disc's half angle, radians; 0 not shown) and which way it is. */
    float starAngle;
    final float[] starDirection = { 0.13f, 0.09f, 1f };
    /** How wide the planet looks (half angle, radians; 0 not shown), which way it is, and how far it's turned. */
    float planetAngle, planetSpin;
    final float[] planetDirection = { 0f, 0f, 1f };
    /** The moon: how wide it looks (half angle; 0 not shown), which way it is, and how far off against the planet. */
    float moonAngle, moonFarther;
    final float[] moonDirection = { -1f, 0f, 1f };
    /** The gas giant: where its middle is (in its radii; not shown when giantShown is false) and how far it's turned. */
    boolean giantShown;
    final float[] giantCentre = { 0f, 0f, 10f };
    float giantSpin;
    /** How thick the belt's rocks are (0 none, 1 thickest), and how fast they come past. */
    float belt, rockSpeed;
    /** How much of space shows (1), against the fogged sky and ground (0). */
    float space = 1f;
    /** Which way is up through the fog, in the pod's own frame. */
    final float[] skyUp = { 0f, 1f, 0f };
    /** The pod's orientation: its own frame into the frame it flies in. */
    float[] podToSpace = Affine.identity();
    /**
     * How far (radians) the pod has turned aside from facing the planet (swerving past it at the
     * end of its approach, before swinging round to face it in orbit; or, swinging round a gas
     * giant, turned to keep it in view). Only what's out of the window turns, not which way's up
     * (see PodTimeline).
     */
    float heading;
    /**
     * How far (radians) the pod's course is still off the way to the planet, before a gas giant
     * swings it round: the star, the planet and the stars turned that far, the giant not.
     */
    float course;
    /**
     * How far round the planet (radians) the pod has gone in orbit, always facing it: everything
     * else (the stars, the star, the planet's surface and the light on it) turned that far about
     * the planet's middle. The star's place, already turned, is set in starDirection and light.
     */
    float orbitAngle;

    private Shader shader;
    private final int[] vao = new int[6], vbo = new int[6], globeIndices = new int[2];
    private int sphereIndexCount, reliefIndexCount;
    private int starTexture, globeTexture, moonTexture, giantTexture, ringTexture, heightTexture, cloudTexture;
    private final int[] rockFirst = new int[ROCK_SHAPES], rockCount = new int[ROCK_SHAPES];
    private volatile PlanetGlobeTexture.Relief pendingGlobe;
    private boolean globeReady;
    private final float[] streakers = new float[STREAKERS * 3];
    private final float[] streakBrightness = new float[STREAKERS];
    private final float[] lineData = new float[STREAKERS * 6 * 6];
    private final Random rand = new Random(11);
    private float[] sunTint = { 1f, 1f, 1f }, skyColour = { 0.6f, 0.7f, 0.85f }, groundColour = { 0.25f, 0.24f, 0.2f };
    private final long worldSeed;
    private final StarSystem system;

    /** One of the belt's rocks: where it is, the way it turns and how far, how big, which shape, and whether it's there. */
    private static final class Rock {
        float x, y, z, size, angle, spin;
        float[] axis;
        int shape;
        boolean there;
    }

    private final Rock[] rocks = new Rock[ROCKS];
    private final Random rockRand;

    /** A pebble on its way to the door's glass: where it'll strike (the cabin's frame, and u, v on the glass), the way it comes, how long till it does, and whether it cracks it. */
    record Strike(float[] at, float u, float v, float[] from, boolean cracks, float seed) {
    }

    private final List<Strike> coming = new ArrayList<>();
    private final List<float[]> comingLeft = new ArrayList<>();
    private final Deque<Strike> struck = new ArrayDeque<>();
    /** Where the eyes are in the cabin (for the pebbles, which come right up to the glass). */
    final float[] eye = { 0f, 0f, 0f };

    public PodSpaceView(long worldSeed) {
        this.worldSeed = worldSeed;
        system = StarSystem.of(worldSeed);
        rockRand = WorldPalette.rng(worldSeed, 0x20C5L);
        for (int i = 0; i < STREAKERS; i++) {
            respawn(i, true);
        }
        for (int i = 0; i < ROCKS; i++) {
            rocks[i] = new Rock();
            respawnRock(rocks[i], true);
        }
    }

    /** The planet's globe, from any thread, once it's made. */
    void setGlobe(PlanetGlobeTexture.Relief globe) {
        pendingGlobe = globe;
    }

    boolean hasGlobe() {
        return globeReady || pendingGlobe != null;
    }

    /** Sends a pebble at the door's glass, to strike it at (the cabin's frame; u, v across the glass) STRIKE_SEEN seconds from now. */
    void strike(float[] at, float u, float v, boolean cracks, float seed) {
        // From ahead, the way the rocks come, a little to one side
        float[] ahead = Affine.transformDirection(PodFurniture.invertRigid(podToSpace), 0f, 0f, 1f);
        float[] from = Affine.normalise(new float[] { ahead[0] + 0.25f * (seed - 0.5f), ahead[1] + 0.15f * (0.5f - seed), ahead[2] });
        coming.add(new Strike(at, u, v, from, cracks, seed));
        comingLeft.add(new float[] { STRIKE_SEEN });
    }

    /** The next pebble that's struck the glass, or null. */
    Strike nextStruck() {
        return struck.poll();
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

    /** A rock put back far ahead (or anywhere, at the start), there or not as thick as the belt is. */
    private void respawnRock(Rock r, boolean anywhere) {
        float x, y;
        do {
            x = (rockRand.nextFloat() - 0.5f) * 2f * ROCK_SPREAD;
            y = (rockRand.nextFloat() - 0.5f) * 2f * ROCK_SPREAD;
        } while (x * x + y * y < ROCK_CLEAR * ROCK_CLEAR);
        // (now and then one comes close by the window)
        if (rockRand.nextFloat() < 0.08f) {
            float s = ROCK_CLEAR * (1f + rockRand.nextFloat()) / (float) Math.max(1e-3, Math.hypot(x, y));
            x *= s;
            y *= s;
        }
        r.x = x;
        r.y = y;
        r.z = anywhere ? rockRand.nextFloat() * ROCK_DEPTH : ROCK_DEPTH;
        float k = rockRand.nextFloat();
        r.size = ROCK_SMALLEST + (ROCK_LARGEST - ROCK_SMALLEST) * k * k * k;
        r.axis = Affine.normalise(new float[] { (float) rockRand.nextGaussian(), (float) rockRand.nextGaussian(), (float) rockRand.nextGaussian() });
        r.angle = rockRand.nextFloat() * 6.28f;
        r.spin = (0.2f + rockRand.nextFloat()) * (rockRand.nextBoolean() ? 1f : -1f);
        r.shape = rockRand.nextInt(ROCK_SHAPES);
        r.there = !anywhere && rockRand.nextFloat() < belt;
    }

    public void initialise(GL3 gl) {
        shader = new Shader(gl, GamePaths.HOME + "assets/shaders/vs_pod_space.txt", GamePaths.HOME + "assets/shaders/fs_pod_space.txt");
        gl.glGenVertexArrays(6, vao, 0);
        gl.glGenBuffers(6, vbo, 0);
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
        // 2: a square, for the star and the giant's rings
        setUp(gl, 2, new float[] { -1, -1, 0, 0, 0, 0, 1, -1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, -1, 1, 0, 0, 0, 0 }, GL.GL_STATIC_DRAW);
        // 3: a globe, its axes as the planet's (y north; longitude 0 along +x, 90 east along -z)
        sphereIndexCount = globe(gl, 3, 96, 48, 0);
        // 5: the planet's own globe, finer, for its surface to be raised by the ground's height
        reliefIndexCount = globe(gl, 5, 512, 256, 1);
        // 4: the belt's rocks, a few lumpy shapes one after another (position, then the face's normal)
        setUp(gl, 4, rockShapes(), GL.GL_STATIC_DRAW);

        // The star as the sky shows it in the game, and the fogged sky's colours, from the world's palette
        WorldPalette palette = new WorldPalette(worldSeed);
        sunTint = palette.sunTint;
        starTexture = upload(gl, 0, ProceduralTextures.sunGlow(palette), false);
        float[] zenith = palette.skyZenith;
        skyColour = new float[] { 0.55f + 0.35f * zenith[0], 0.55f + 0.35f * zenith[1], 0.55f + 0.35f * zenith[2] };
        int land = MapColours.of(worldSeed)[1];
        groundColour = new float[] { ((land >> 16) & 255) / 255f * 0.45f, ((land >> 8) & 255) / 255f * 0.45f, (land & 255) / 255f * 0.45f };
        // The moon and the giant, only for the worlds that have them
        if (system.hasMoon) moonTexture = upload(gl, 0, system.moonSurface(), true);
        if (system.giant) {
            giantTexture = upload(gl, 0, system.giantSurface(), true);
            if (system.giantRings) ringTexture = upload(gl, 0, system.ringProfile(), false);
        }
    }

    /**
     * The rocks' shapes: each a ball (an icosahedron, twice divided) dented and lumped, squashed
     * a little one way and another, with a flat normal to each face so it reads as broken rock.
     */
    private float[] rockShapes() {
        Random r = WorldPalette.rng(worldSeed, 0x20C5_5A9EL);
        List<float[]> out = new ArrayList<>();
        for (int s = 0; s < ROCK_SHAPES; s++) {
            List<float[]> points = new ArrayList<>();
            List<int[]> faces = new ArrayList<>();
            icosahedron(points, faces);
            for (int d = 0; d < 2; d++) faces = divide(points, faces);
            float[][] dents = new float[7][];
            for (int i = 0; i < dents.length; i++) {
                dents[i] = new float[] { (float) r.nextGaussian(), (float) r.nextGaussian(), (float) r.nextGaussian(), 0.25f + 0.5f * r.nextFloat(),
                        (r.nextFloat() - 0.6f) * 0.5f };
                float[] n = Affine.normalise(new float[] { dents[i][0], dents[i][1], dents[i][2] });
                System.arraycopy(n, 0, dents[i], 0, 3);
            }
            float sx = 0.65f + 0.35f * r.nextFloat(), sy = 0.55f + 0.35f * r.nextFloat(), sz = 0.8f + 0.2f * r.nextFloat();
            for (float[] p : points) {
                float lift = 1f + 0.06f * (r.nextFloat() - 0.5f);
                for (float[] dent : dents) {
                    float a = Affine.dot(p, dent);
                    float share = Math.max(0f, (a - (1f - dent[3])) / dent[3]);
                    lift += dent[4] * share * share;
                }
                p[0] *= lift * sx;
                p[1] *= lift * sy;
                p[2] *= lift * sz;
            }
            // (two entries, the point and its normal, to a vertex)
            rockFirst[s] = out.size() / 2;
            for (int[] f : faces) {
                float[] a = points.get(f[0]), b = points.get(f[1]), c = points.get(f[2]);
                float[] n = Affine.normalise(Affine.cross(Affine.subtract(b, a), Affine.subtract(c, a)));
                // (outwards, whatever the winding)
                if (Affine.dot(n, a) < 0f) n = new float[] { -n[0], -n[1], -n[2] };
                for (float[] p : new float[][] { a, b, c }) {
                    out.add(p);
                    out.add(n);
                }
            }
            rockCount[s] = faces.size() * 3;
        }
        float[] data = new float[out.size() * 3];
        for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, data, i * 3, 3);
        return data;
    }

    private static void icosahedron(List<float[]> points, List<int[]> faces) {
        float t = (float) ((1.0 + Math.sqrt(5.0)) / 2.0);
        float[][] v = { { -1, t, 0 }, { 1, t, 0 }, { -1, -t, 0 }, { 1, -t, 0 }, { 0, -1, t }, { 0, 1, t }, { 0, -1, -t }, { 0, 1, -t },
                { t, 0, -1 }, { t, 0, 1 }, { -t, 0, -1 }, { -t, 0, 1 } };
        for (float[] p : v) points.add(Affine.normalise(p));
        int[][] f = { { 0, 11, 5 }, { 0, 5, 1 }, { 0, 1, 7 }, { 0, 7, 10 }, { 0, 10, 11 }, { 1, 5, 9 }, { 5, 11, 4 }, { 11, 10, 2 }, { 10, 7, 6 },
                { 7, 1, 8 }, { 3, 9, 4 }, { 3, 4, 2 }, { 3, 2, 6 }, { 3, 6, 8 }, { 3, 8, 9 }, { 4, 9, 5 }, { 2, 4, 11 }, { 6, 2, 10 }, { 8, 6, 7 },
                { 9, 8, 1 } };
        for (int[] face : f) faces.add(face);
    }

    /** Each face split in four, the new corners pushed out onto the ball. */
    private static List<int[]> divide(List<float[]> points, List<int[]> faces) {
        java.util.Map<Long, Integer> middles = new java.util.HashMap<>();
        List<int[]> out = new ArrayList<>();
        for (int[] f : faces) {
            int ab = middle(points, middles, f[0], f[1]), bc = middle(points, middles, f[1], f[2]), ca = middle(points, middles, f[2], f[0]);
            out.add(new int[] { f[0], ab, ca });
            out.add(new int[] { f[1], bc, ab });
            out.add(new int[] { f[2], ca, bc });
            out.add(new int[] { ab, bc, ca });
        }
        return out;
    }

    private static int middle(List<float[]> points, java.util.Map<Long, Integer> middles, int a, int b) {
        long key = Math.min(a, b) * 100000L + Math.max(a, b);
        Integer known = middles.get(key);
        if (known != null) return known;
        float[] p = points.get(a), q = points.get(b);
        points.add(Affine.normalise(new float[] { p[0] + q[0], p[1] + q[1], p[2] + q[2] }));
        middles.put(key, points.size() - 1);
        return points.size() - 1;
    }

    /**
     * A globe into vertex array which, across by down quads, its axes as the planet's (y north;
     * longitude 0 along +x, 90 east along -z), u, v across its surface picture; how many indices
     * it has. Its index buffer is the slot'th kept.
     */
    private int globe(GL3 gl, int which, int across, int down, int slot) {
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
        setUp(gl, which, sphere, GL.GL_STATIC_DRAW);
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
        gl.glBindVertexArray(vao[which]);
        gl.glGenBuffers(1, id, 0);
        globeIndices[slot] = id[0];
        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, id[0]);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) indices.length * Integer.BYTES, IntBuffer.wrap(indices), GL.GL_STATIC_DRAW);
        gl.glBindVertexArray(0);
        return indices.length;
    }

    /** The ground's heights (PlanetGlobeTexture.WIDTH by HEIGHT, units above the sea) as a texture of floats. */
    private int uploadHeights(GL3 gl, float[] heights) {
        int texture = heightTexture;
        if (texture == 0) {
            int[] id = new int[1];
            gl.glGenTextures(1, id, 0);
            texture = id[0];
        }
        gl.glBindTexture(GL.GL_TEXTURE_2D, texture);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4);
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL3.GL_R32F, PlanetGlobeTexture.WIDTH, PlanetGlobeTexture.HEIGHT, 0, GL3.GL_RED, GL.GL_FLOAT,
                FloatBuffer.wrap(heights));
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        return texture;
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
    static int upload(GL3 gl, int texture, BufferedImage image, boolean mipmaps) {
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
        gl.glDeleteBuffers(6, vbo, 0);
        gl.glDeleteBuffers(2, globeIndices, 0);
        gl.glDeleteVertexArrays(6, vao, 0);
        gl.glDeleteTextures(7, new int[] { starTexture, globeTexture, moonTexture, giantTexture, ringTexture, heightTexture, cloudTexture }, 0);
    }

    /** Moves the streaking stars and the belt's rocks on (towards the pod, at speed), and the pebbles to the glass. */
    void update(float dt) {
        float travelled = speed * dt;
        for (int i = 0; i < STREAKERS; i++) {
            streakers[i * 3 + 2] -= travelled;
            if (streakers[i * 3 + 2] < -400f) respawn(i, false);
        }
        for (Rock r : rocks) {
            r.z -= rockSpeed * dt;
            r.angle += r.spin * dt;
            if (r.z < -ROCK_LARGEST * 4f) respawnRock(r, false);
            // (as the belt thickens, rocks still far off enough to be dark come into it)
            else if (!r.there && r.z > ROCK_DEPTH * 0.7f && rockRand.nextFloat() < belt * dt * 1.5f) r.there = true;
        }
        for (int i = coming.size() - 1; i >= 0; i--) {
            float[] left = comingLeft.get(i);
            left[0] -= dt;
            if (left[0] <= 0f) {
                struck.add(coming.remove(i));
                comingLeft.remove(i);
            }
        }
    }

    /**
     * Draws it all, behind the cabin: rotation is the view's turn in the pod's frame, projection
     * the cabin's own (so the window lines up); the depth it uses is cleared after, for the cabin.
     */
    void render(GL3 gl, Matrix4 projection, Matrix4 rotation) {
        PlanetGlobeTexture.Relief globe = pendingGlobe;
        if (globe != null) {
            pendingGlobe = null;
            globeTexture = upload(gl, globeTexture, globe.colours(), true);
            heightTexture = uploadHeights(gl, globe.heights());
            cloudTexture = upload(gl, cloudTexture, globe.clouds(), true);
            globeReady = true;
        }
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDepthMask(false);
        gl.glDisable(GL.GL_CULL_FACE);
        gl.glEnable(GL.GL_BLEND);
        shader.use(gl);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        shader.setInt(gl, "image", 0);
        shader.setFloat(gl, "lightColour", 0.75f + 0.25f * sunTint[0], 0.75f + 0.25f * sunTint[1], 0.75f + 0.25f * sunTint[2]);

        Matrix4 inPod = Matrix4.multiply(projection, rotation);
        Matrix4 inSpace = Matrix4.multiply(inPod, Matrix4.multiply(Matrix4.fromColumns(transpose(podToSpace)),
                Matrix4.fromColumns(Affine.rotationY(heading))));
        // (the star, the planet and its moon, and the stars, all where the pod's course is yet to bring ahead)
        Matrix4 inCourse = Matrix4.multiply(inSpace, Matrix4.fromColumns(Affine.rotationY(course)));
        // (the still stars, so far off they turn with the orbit as if about the pod itself)
        Matrix4 inStars = Matrix4.multiply(inCourse, Matrix4.fromColumns(Affine.rotationY(orbitAngle)));
        float visible = space;
        if (visible > 0.01f) {
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE);
            shader.setFloatArray(gl, "viewProjection", inSpace.toFloatArrayForGLSL());
            // The stars streaming past, and the still ones all round
            shader.setInt(gl, "mode", 0);
            shader.setFloat(gl, "tint", streakTint[0], streakTint[1], streakTint[2]);
            if (streaming > 0.01f) drawStreakers(gl, visible * streaming);
            shader.setFloat(gl, "tint", 0.9f, 0.92f, 1f);
            if (dome > 0.01f) {
                gl.glEnable(GL3.GL_PROGRAM_POINT_SIZE);
                shader.setInt(gl, "asPoints", 1);
                shader.setFloat(gl, "pointSize", 3f);
                shader.setFloat(gl, "alpha", visible * dome);
                shader.setFloatArray(gl, "viewProjection", inStars.toFloatArrayForGLSL());
                gl.glBindVertexArray(vao[1]);
                gl.glDrawArrays(GL.GL_POINTS, 0, DOME);
                shader.setInt(gl, "asPoints", 0);
                gl.glDisable(GL3.GL_PROGRAM_POINT_SIZE);
            }
            shader.setFloatArray(gl, "viewProjection", inCourse.toFloatArrayForGLSL());
            // The star, then the planet and its moon in front of it, the further first
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
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            boolean moon = moonAngle > 0f && moonTexture != 0;
            if (moon && moonFarther > 1f) drawMoon(gl, visible);
            if (planetAngle > 0f && globeReady) {
                shader.setInt(gl, "mode", 2);
                float[] d = Affine.normalise(planetDirection);
                float radius = (float) Math.sin(planetAngle) * PLANET_DISTANCE;
                shader.setFloat(gl, "planetCentre", d[0] * PLANET_DISTANCE, d[1] * PLANET_DISTANCE, d[2] * PLANET_DISTANCE);
                shader.setFloat(gl, "planetRadius", radius);
                // Its north a little tipped towards the pod, turning slowly
                turn(gl, Affine.multiply(Affine.rotationY(orbitAngle),
                        Affine.multiply(Affine.rotationX(-0.35f), Affine.multiply(Affine.rotationZ(0.3f), Affine.rotationY(planetSpin)))));
                // Lit by the star, beyond it and to one side
                float[] lit = Affine.normalise(light);
                shader.setFloat(gl, "lightDirection", lit[0], lit[1], lit[2]);
                shader.setFloat(gl, "atmosphere", 0.35f * sunTint[0] + 0.15f, 0.5f, 0.85f);
                shader.setFloat(gl, "alpha", Math.min(1f, visible));
                // Its ground raised by its height, to scale (and shaded by it), then its clouds above
                shader.setInt(gl, "surface", 1);
                shader.setFloat(gl, "worldRadius", (float) Planet.radius());
                gl.glActiveTexture(GL.GL_TEXTURE1);
                gl.glBindTexture(GL.GL_TEXTURE_2D, heightTexture);
                shader.setInt(gl, "heightMap", 1);
                gl.glActiveTexture(GL.GL_TEXTURE0);
                gl.glBindTexture(GL.GL_TEXTURE_2D, globeTexture);
                gl.glBindVertexArray(vao[5]);
                gl.glDrawElements(GL.GL_TRIANGLES, reliefIndexCount, GL.GL_UNSIGNED_INT, 0);
                shader.setInt(gl, "surface", 2);
                // (drifting a little against the ground beneath)
                turn(gl, Affine.multiply(Affine.rotationY(orbitAngle),
                        Affine.multiply(Affine.rotationX(-0.35f), Affine.multiply(Affine.rotationZ(0.3f), Affine.rotationY(planetSpin * 1.6f)))));
                shader.setFloat(gl, "atmosphere", 0f, 0f, 0f);
                gl.glBindTexture(GL.GL_TEXTURE_2D, cloudTexture);
                gl.glBindVertexArray(vao[3]);
                gl.glDrawElements(GL.GL_TRIANGLES, sphereIndexCount, GL.GL_UNSIGNED_INT, 0);
                shader.setInt(gl, "surface", 0);
            }
            if (moon && moonFarther <= 1f) drawMoon(gl, visible);
            // Nearer than all that: the giant (and its rings) and the belt's rocks, each hiding its own far side
            gl.glClear(GL.GL_DEPTH_BUFFER_BIT);
            gl.glEnable(GL.GL_DEPTH_TEST);
            gl.glDepthFunc(GL.GL_LESS);
            if (giantShown && giantTexture != 0) drawGiant(gl, inSpace, visible);
            if (belt > 0f || !coming.isEmpty()) drawRocks(gl, inSpace, inPod);
            gl.glDisable(GL.GL_DEPTH_TEST);
            gl.glDepthMask(false);
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
        // (what depth the giant and the rocks left is nothing to the cabin, drawn with its own)
        gl.glClear(GL.GL_DEPTH_BUFFER_BIT);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_CULL_FACE);
    }

    /** A globe's turn (its own axes into the frame it's drawn in). */
    private void turn(GL3 gl, float[] m) {
        gl.glUniformMatrix3fv(gl.glGetUniformLocation(shader.getID(), "planetTurn"), 1, false,
                new float[] { m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10] }, 0);
    }

    /** The planet's moon: its rock lit by the star (no air), the same face always to the planet. */
    private void drawMoon(GL3 gl, float visible) {
        shader.setInt(gl, "mode", 2);
        float[] d = Affine.normalise(moonDirection);
        float distance = PLANET_DISTANCE * Math.max(0.05f, moonFarther);
        shader.setFloat(gl, "planetCentre", d[0] * distance, d[1] * distance, d[2] * distance);
        shader.setFloat(gl, "planetRadius", (float) Math.sin(moonAngle) * distance);
        turn(gl, Affine.multiply(Affine.rotationY(orbitAngle), Affine.rotationY((float) Math.atan2(-d[0], -d[2]))));
        float[] lit = Affine.normalise(light);
        shader.setFloat(gl, "lightDirection", lit[0], lit[1], lit[2]);
        shader.setFloat(gl, "atmosphere", 0f, 0f, 0f);
        shader.setFloat(gl, "alpha", Math.min(1f, visible));
        gl.glBindTexture(GL.GL_TEXTURE_2D, moonTexture);
        gl.glBindVertexArray(vao[3]);
        gl.glDrawElements(GL.GL_TRIANGLES, sphereIndexCount, GL.GL_UNSIGNED_INT, 0);
    }

    /** The gas giant, lit by the star where the course has it, and its rings (behind it hidden, in front over it, its shadow across them). */
    private void drawGiant(GL3 gl, Matrix4 inSpace, float visible) {
        shader.setFloatArray(gl, "viewProjection", inSpace.toFloatArrayForGLSL());
        float[] centre = { giantCentre[0] * GIANT_UNIT, giantCentre[1] * GIANT_UNIT, giantCentre[2] * GIANT_UNIT };
        // (the star's light, as the course turns it)
        float[] lit = Affine.transformDirection(Affine.rotationY(course), light[0], light[1], light[2]);
        lit = Affine.normalise(lit);
        float[] tilt = Affine.multiply(Affine.rotationZ(system.giantTilt * system.giantSide), Affine.rotationX(0.25f));
        gl.glDepthMask(true);
        shader.setInt(gl, "mode", 2);
        shader.setFloat(gl, "planetCentre", centre[0], centre[1], centre[2]);
        shader.setFloat(gl, "planetRadius", GIANT_UNIT);
        turn(gl, Affine.multiply(tilt, Affine.rotationY(giantSpin)));
        shader.setFloat(gl, "lightDirection", lit[0], lit[1], lit[2]);
        float[] haze = WorldPalette.hsv(system.giantHue, 0.35f, 0.9f);
        shader.setFloat(gl, "atmosphere", haze[0] * 0.5f, haze[1] * 0.5f, haze[2] * 0.5f);
        shader.setFloat(gl, "alpha", Math.min(1f, visible));
        gl.glBindTexture(GL.GL_TEXTURE_2D, giantTexture);
        gl.glBindVertexArray(vao[3]);
        gl.glDrawElements(GL.GL_TRIANGLES, sphereIndexCount, GL.GL_UNSIGNED_INT, 0);
        if (ringTexture != 0) {
            gl.glDepthMask(false);
            shader.setInt(gl, "mode", 5);
            float[] u = Affine.transformDirection(tilt, 1f, 0f, 0f), v = Affine.transformDirection(tilt, 0f, 0f, 1f);
            shader.setFloat(gl, "ringU", u[0], u[1], u[2]);
            shader.setFloat(gl, "ringV", v[0], v[1], v[2]);
            shader.setFloat(gl, "ringOuter", GIANT_UNIT * 2.3f);
            shader.setFloat(gl, "ringInner", 1.35f / 2.3f);
            gl.glBindTexture(GL.GL_TEXTURE_2D, ringTexture);
            gl.glBindVertexArray(vao[2]);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 6);
        }
    }

    /** The belt's rocks (and the pebbles coming at the glass), the far ones first, fading in out of the dark as they come. */
    private void drawRocks(GL3 gl, Matrix4 inSpace, Matrix4 inPod) {
        gl.glDisable(GL.GL_BLEND);
        gl.glDepthMask(true);
        shader.setInt(gl, "mode", 4);
        float[] lit = Affine.normalise(Affine.transformDirection(Affine.rotationY(course), light[0], light[1], light[2]));
        shader.setFloat(gl, "lightDirection", lit[0], lit[1], lit[2]);
        gl.glBindVertexArray(vao[4]);
        shader.setFloatArray(gl, "viewProjection", inSpace.toFloatArrayForGLSL());
        for (Rock r : rocks) {
            if (!r.there) continue;
            float fade = Math.max(0f, Math.min(1f, (ROCK_DEPTH - r.z) / (ROCK_DEPTH * 0.35f)));
            drawRock(gl, r.x, r.y, r.z, r.size, r.axis, r.angle, r.shape, fade);
        }
        // The pebbles, right up to the glass: placed in the cabin's frame round the eyes
        if (!coming.isEmpty()) {
            float[] spaceToPod = PodFurniture.invertRigid(podToSpace);
            float[] litInPod = Affine.normalise(Affine.transformDirection(spaceToPod, lit[0], lit[1], lit[2]));
            shader.setFloat(gl, "lightDirection", litInPod[0], litInPod[1], litInPod[2]);
            shader.setFloatArray(gl, "viewProjection", inPod.toFloatArrayForGLSL());
            for (int i = 0; i < coming.size(); i++) {
                Strike s = coming.get(i);
                float away = comingLeft.get(i)[0] * STRIKE_SPEED + PEBBLE;
                float x = s.at()[0] - eye[0] + s.from()[0] * away, y = s.at()[1] - eye[1] + s.from()[1] * away, z = s.at()[2] - eye[2] + s.from()[2] * away;
                drawRock(gl, x, y, z, PEBBLE, s.from(), comingLeft.get(i)[0] * 9f, (int) (s.seed() * ROCK_SHAPES) % ROCK_SHAPES, 1f);
            }
        }
        gl.glEnable(GL.GL_BLEND);
    }

    private void drawRock(GL3 gl, float x, float y, float z, float size, float[] axis, float angle, int shape, float fade) {
        float[] m = rotationAbout(axis, angle);
        gl.glUniformMatrix3fv(gl.glGetUniformLocation(shader.getID(), "rockTurn"), 1, false,
                new float[] { m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10] }, 0);
        shader.setFloat(gl, "rockCentre", x, y, z);
        shader.setFloat(gl, "rockSize", size);
        shader.setFloat(gl, "alpha", fade);
        gl.glDrawArrays(GL.GL_TRIANGLES, rockFirst[shape], rockCount[shape]);
    }

    /** A rotation by angle about a unit axis (Rodrigues). */
    private static float[] rotationAbout(float[] a, float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle), t = 1f - c;
        float[] m = Affine.identity();
        m[0] = t * a[0] * a[0] + c;
        m[1] = t * a[0] * a[1] + s * a[2];
        m[2] = t * a[0] * a[2] - s * a[1];
        m[4] = t * a[0] * a[1] - s * a[2];
        m[5] = t * a[1] * a[1] + c;
        m[6] = t * a[1] * a[2] + s * a[0];
        m[8] = t * a[0] * a[2] + s * a[1];
        m[9] = t * a[1] * a[2] - s * a[0];
        m[10] = t * a[2] * a[2] + c;
        return m;
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
