import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The world's rivers and lakes, worked out once from the bare terrain when the world is made.
 * <p>
 * On a raster of the whole sphere (SphereRaster, a few tens of world units a texel) the land is
 * filled from the sea inwards, lowest first (a priority flood), so every texel has a way down
 * to the sea over the filled surface: hollows fill to their brims. A small hollow is a lake (its
 * water at the brim, spilling out at its lowest edge, but no higher than the ground as drawn
 * holds it); a large one, or a small or shallow one, is cut through instead.
 * Streams rise at high places and run downhill, steepest first but drawn towards other streams
 * near by, and where they meet they join: the further down, the more they carry and the wider
 * they are. Every stream ends in the sea.
 * <p>
 * Each run between sources, meetings, and the sea (a reach) is smoothed into a gentle curve
 * with a little meander, and its water level is the lowest the ground has been along it so far
 * (never rising downstream). Under it the ground is cut away (carve, called by TerrainMesh for
 * every height) into a rounded channel about as deep as it is wide, with banks sloping up to
 * the land round it. Into the sea, it runs on until it's under the sea, its channel shallowing
 * so the sea never reaches up it. Where it touches a lake's water it's in the lake, at its level;
 * where it leaves or joins it the lake's water is cut off square across it (a Cut), and kept
 * out of its channel; and near a lake its banks are never cut below the lake's level, so the
 * lake never spills into them (a lake's outlet is a notch in its lip as wide as the water).
 */
public final class Rivers {

    // The raster worked on: texels a side of each cube face
    private static final int SIDE = 512;
    // How much higher each texel filled from a neighbour is than it (so the filled surface always falls to the sea)
    private static final float FLOOD_STEP = 0.01f;
    // A hollow is a lake if filling it covers no more than this many texels and is at least this deep somewhere
    private static final int LAKE_MOST_TEXELS = 160, LAKE_LEAST_TEXELS = 12;
    private static final float LAKE_DEPTH = 1.5f, LAKE_DEEPEST = 7f;
    // How far a lake's water may reach past its texels' middles (texels, and grid squares), and the
    // grid its water is laid on (world units; see RiverRenderer): its level is lowered until it no
    // longer spills over the ground that far out, by at least SPILL_MARGIN
    static final float LAKE_SHORE_TEXELS = 2.5f, LAKE_GRID = 16f;
    static final int LAKE_SHORE_SQUARES = 2;
    private static final float SPILL_MARGIN = 0.3f;
    // How far (points) down (or up) a river from where it leaves (or joins) a lake the lake's water
    // is kept out of its channel, and how much wider than the channel's cut below the lake's level
    private static final int CUT_CORRIDOR_POINTS = 40;
    private static final float CUT_SPARE = 4f;
    // A river's taken to be in a lake where the lake's water is at any of these shares of the way
    // out to its water's edge (NOTCH_WIDTH of its half width, and CUT_SPARE more) either side, and
    // to stay in it over stretches of up to SHORE_GAP points out of its water
    private static final float[] ACROSS_SAMPLES = { 0f, -0.33f, 0.33f, -0.67f, 0.67f, -1f, 1f };
    private static final int SHORE_GAP = 8;
    // (and going out, it leaves at the lake's lip: the highest ground over its last this many
    // points in the lake, if it's no lower than this below the lake's level)
    private static final int LIP_SEARCH = 12;
    private static final float LIP_BELOW = 1f;
    // How far apart it looks round a river's points for a lake's water near (world units)
    private static final float FLOOR_STEP = 12f;
    // Streams rise this high at least (above the sea, or this share of the way to the highest land)
    private static final float SOURCE_LEAST_HEIGHT = 40f, SOURCE_SHARE = 0.12f;
    // How far apart sources are at least (texels), and how many there are for so many high texels
    private static final int SOURCE_SPACING = 5, HIGH_TEXELS_PER_SOURCE = 50, FEWEST_SOURCES = 40, MOST_SOURCES = 600;
    // A high place is a stream's source with this chance where it's driest, rising to 1 where it's wettest
    private static final float DRIEST_SOURCE_CHANCE = 0.2f;
    // Each texel a stream runs through adds this much to what it carries, and this much more where it's wettest
    private static final float RAIN_CARRIED = 0.3f, WET_CARRIED = 1.4f;
    // How strongly a stream is drawn towards another near by (its steepness counts this much more)
    private static final float ATTRACTION = 2.0f;
    // Points along a reach this far apart (world units), and how far it meanders either way
    private static final float POINT_STEP = 8f, MEANDER = 9f, MEANDER_WAVELENGTH = 140f;
    // Half the water's width at a source, and how it grows with what has run into it (texels' worth)
    private static final float SOURCE_HALF_WIDTH = 2.5f, HALF_WIDTH_GAIN = 1.8f;
    // (and with how far it has run from its furthest source: this much more for each such length)
    private static final float LENGTH_GAIN = 1.2f, LENGTH_UNIT = 600f;
    // A stream rises as a trickle: over this far from its source it widens from this share of its
    // width (and its channel deepens from nothing), its water fading in
    private static final float SOURCE_TAPER = 90f, SOURCE_TRICKLE = 0.2f;
    // How deep the channel is for the water's width
    private static final float DEPTH_PER_WIDTH = 0.6f;
    // The channel cut (a rounded trough, its sides easing in) reaches this much further either
    // side than the water, and never less than LEAST_TROUGH (so the ground's mesh can draw it).
    // (Snug round the water, so it stands high in its channel, filling it: at 2.4 it lay low in
    // a wide trench, two thirds of the channel's depth below its rim; at 1.5 it's a third.)
    private static final float TROUGH_PER_HALF_WIDTH = 1.5f, LEAST_TROUGH = 7f;
    // The banks beyond: how steeply they rise, how far they reach, and over how much of that they
    // ease back into the land
    private static final float BANK_SLOPE = 0.35f, BANK_REACH = 70f, BANK_EASE = 20f;
    // Over how far a river eases to a lake's level, coming into it or going out of it
    private static final float LAKE_EASE = 60f;
    // Near a lake (out of it) the channel's banks are cut no lower than the lake's level by its
    // water, so the lake never spills into them (see profile): the lake's lip is notched only as
    // wide as the water spilling over it, a waterfall, out to this share of its half width, its
    // sides this steep; and NO_FLOOR where there's no lake near
    private static final float NOTCH_WIDTH = 1.3f, NOTCH_WALL = 2f, NO_FLOOR = -1e6f;
    // (kept this much above the lake's level, so what's cut down to it stays dry, for at least
    // this far from its water, wider than its water grid's squares, so it never steps over it)
    private static final float FLOOR_ABOVE = 0.6f, LEVEE = 2 * LAKE_GRID;
    // How often along a way it looks to see whether it's in a river's water (world units)
    private static final float IN_RIVER_STEP = 6f;
    // Ground this far below a river's bed under it isn't under its water (it's below a fall's lip)
    private static final float BED_SLACK = 3f;
    // How far below the land beside it the water lies
    private static final float LEVEL_MARGIN = 0.4f;
    // At the sea: how far the river runs on past its last texel (points this far apart) to be sure
    // of reaching it, and its channel's brim and bed never lower than these above the sea's level
    // (so the sea never reaches in under the river)
    // (and it ends once this far under the sea)
    private static final int MOUTH_POINTS = 8;
    private static final float MOUTH_STEP = 20f, MOUTH_RIM = 0.5f, MOUTH_BED = 0.1f, MOUTH_END = 1.5f;
    // How fast the water flows (world units a second) on the level, and how much faster for its slope
    private static final float SLOWEST = 1.2f, FLOW_PER_SLOPE = 60f, FASTEST = 18f;

    /** One reach: points along it from upstream down, each {x, y, z} a unit direction. */
    public static final class Reach {
        public final double[][] points;
        // At each point: the water's surface, half its width, how far it has come (world units),
        // how fast it flows, and whether it's out in a lake (not drawn as a river there; which lake)
        public final float[] surface, halfWidth, along, speed;
        public final boolean[] inLake;
        public final int[] lake;
        // (and how far the water has come up out of the ground: 0 at a source, rising to 1)
        public final float[] rises;
        // Where it begins and ends, if at a meeting of reaches (its texel; -1 a source, or the sea);
        // and whether it's the main one of those running into the meeting at its end (the most
        // water: the river it carries on as), not a stream joining it
        public int startsAt = -1, endsAt = -1;
        public boolean mainInflow;
        float inflow;
        // (and for the channel cut under it: the brim's height, the trough's half width, its depth,
        // and near a lake, the lake's level and how far off its water is: see profile)
        final float[] rim, trough, depth, floor, floorReach;

        Reach(int n) {
            points = new double[n][];
            surface = new float[n];
            halfWidth = new float[n];
            along = new float[n];
            speed = new float[n];
            inLake = new boolean[n];
            lake = new int[n];
            rises = new float[n];
            rim = new float[n];
            trough = new float[n];
            depth = new float[n];
            floor = new float[n];
            floorReach = new float[n];
            Arrays.fill(floor, NO_FLOOR);
        }

        public int size() {
            return points.length;
        }
    }

    /**
     * Where a river leaves a lake (an outlet) or comes into one: the lake's water stops at the
     * line across the river through point (square to tangent, the way downstream), and on the
     * river's side of it keeps out of the river's channel (within widths, either side of the
     * points of corridor along it: as far as its channel and banks are cut below the lake's
     * level), so the river's own water carries on from there and the two never overlap.
     */
    public static final class Cut {
        public final double[] point, tangent;
        public final boolean outlet;
        public final double[][] corridor;
        public final float[] widths;

        Cut(double[] point, double[] tangent, boolean outlet, double[][] corridor, float[] widths) {
            this.point = point;
            this.tangent = tangent;
            this.outlet = outlet;
            this.corridor = corridor;
            this.widths = widths;
        }
    }

    /** A lake: its surface's height, its texels' middles (unit directions) with how far each reaches round (world units), and where rivers leave or join it. */
    public static final class Lake {
        public final float level;
        public final List<double[]> texels = new ArrayList<>();
        public final List<Float> radii = new ArrayList<>();
        public final List<Cut> cuts = new ArrayList<>();
        // Its water on the grid it's drawn on (LAKE_GRID apart, on the flat frame at middle, east
        // and north, reaching extent either way, size points a side): how deep it is at each
        // point, below zero where it's dry or the water there isn't joined to the lake's
        final double[] middle, east, north;
        final double extent;
        final int size;
        final float[] water;

        Lake(float level, double[] middle, double[] east, double[] north, double extent, int size, float[] water) {
            this.level = level;
            this.middle = middle;
            this.east = east;
            this.north = north;
            this.extent = extent;
            this.size = size;
            this.water = water;
        }

        /** Where a grid point is on the planet (a unit direction). */
        double[] gridPoint(int i, int j, double radius) {
            double x = -extent + i * LAKE_GRID, z = -extent + j * LAKE_GRID;
            return unit(middle[0] + (east[0] * x + north[0] * z) / radius, middle[1] + (east[1] * x + north[1] * z) / radius,
                    middle[2] + (east[2] * x + north[2] * z) / radius);
        }

        /** How deep its water is at a direction from the planet's centre (between the grid's points; below zero, none). */
        float depthAt(double[] p, double radius) {
            double d = p[0] * middle[0] + p[1] * middle[1] + p[2] * middle[2];
            if (d <= 0) return -1f;
            double x = radius * (p[0] * east[0] + p[1] * east[1] + p[2] * east[2]) / d;
            double z = radius * (p[0] * north[0] + p[1] * north[1] + p[2] * north[2]) / d;
            double gi = (x + extent) / LAKE_GRID, gj = (z + extent) / LAKE_GRID;
            int i = (int) Math.floor(gi), j = (int) Math.floor(gj);
            if (i < 0 || j < 0 || i + 1 >= size || j + 1 >= size) return -1f;
            double fi = gi - i, fj = gj - j;
            int g = j * size + i;
            double top = water[g] + (water[g + 1] - water[g]) * fi;
            double bottom = water[g + size] + (water[g + size + 1] - water[g + size]) * fi;
            return (float) (top + (bottom - top) * fj);
        }
    }

    private final SphereRaster raster = new SphereRaster(SIDE);
    private final float radius = (float) Planet.radius();
    private final float seaLevel;
    private final List<Reach> reaches = new ArrayList<>();
    private final List<Lake> lakes = new ArrayList<>();
    // Which lake each texel is in (-1 none), and which lakes' grids each reaches into (up to
    // NEAR_LAKES of them, texel t's at nearLakes[t * NEAR_LAKES], -1 after the last)
    private final int[] lakeOf, nearLakes;
    private static final int NEAR_LAKES = 4;
    // The hollows the land holds (each texel's, -1 none): those a stream runs into become lakes
    private int[] hollowOf;
    private final List<int[]> hollows = new ArrayList<>();
    private final List<float[]> hollowShape = new ArrayList<>();   // {level, deepest}

    // The channel's pieces, between consecutive points: SEGMENT floats each (ends a and b on the
    // datum, world units from the planet's centre; at each end the brim, trough half width,
    // depth and water surface; how far round the piece reaches; and at each end the level of a
    // lake near, how far off its water is, and the water's half width)
    private static final int SEGMENT = 21;
    private float[] segments = new float[0];
    private int segmentCount;
    // The pieces reaching each texel (texel t's are cellPieces[cellStart[t]] up to cellStart[t + 1])
    private int[] cellStart, cellPieces;
    // The cells of the drawn ground (TerrainMesh.DRAWN_LEVEL) a channel reaches into
    private final Set<Long> channelCells = new HashSet<>();
    // Each drawn cell's edges' neighbours' fineness, as worked out (see TerrainMesh.segmentsFor)
    final Map<Long, boolean[]> coarseEdges = new java.util.concurrent.ConcurrentHashMap<>();

    public List<Reach> reaches() {
        return reaches;
    }

    public List<Lake> lakes() {
        return lakes;
    }

    /** Which lake a direction from the planet's centre is in (by texel), -1 none. */
    public int lakeAt(double x, double y, double z) {
        return lakeOf[raster.index(x, y, z)];
    }

    /** The sea's level the rivers run down to. */
    public float seaLevel() {
        return seaLevel;
    }

    /** About how far across a texel of the rivers' raster is, in world units. */
    public float texelSize() {
        return (float) (2.0 / SIDE * radius);
    }

    /** Whether a cell of the drawn ground has a channel cut into it. */
    public boolean cuts(long drawnCellId) {
        return channelCells.contains(drawnCellId);
    }

    // ------------------------------------------------------------------ making them

    /** Works out a world's rivers from its bare terrain (no rivers yet on noise) and sea level, rain falling evenly. */
    public static Rivers generate(PerlinNoise noise, float seaLevel) {
        return new Rivers(noise, seaLevel, d -> 0.5);
    }

    /**
     * Works out a world's rivers from its bare terrain and sea level, with rainfall (0 driest to
     * 1 wettest, by direction): streams rise more readily where it's wet and carry more of it.
     */
    public static Rivers generate(PerlinNoise noise, float seaLevel, java.util.function.ToDoubleFunction<GeodesicChunkGrid.Direction> rainfall) {
        return new Rivers(noise, seaLevel, rainfall);
    }

    // How much rain falls on each texel (worked out as it's wanted, NaN till then), from rainfall
    private final java.util.function.ToDoubleFunction<GeodesicChunkGrid.Direction> rainfall;
    private float[] rain;

    private float rainAt(int t) {
        if (Float.isNaN(rain[t])) rain[t] = (float) Math.max(0.0, Math.min(1.0, rainfall.applyAsDouble(raster.direction(t))));
        return rain[t];
    }

    private Rivers(PerlinNoise noise, float seaLevel, java.util.function.ToDoubleFunction<GeodesicChunkGrid.Direction> rainfall) {
        this.seaLevel = seaLevel;
        this.rainfall = rainfall;
        this.rain = new float[raster.size()];
        Arrays.fill(rain, Float.NaN);
        int size = raster.size();
        float[] height = new float[size];
        java.util.stream.IntStream.range(0, size).parallel().forEach(t -> height[t] = TerrainMesh.getLayeredHeight(raster.direction(t), noise));
        boolean[] sea = new boolean[size];
        for (int t = 0; t < size; t++) sea[t] = height[t] <= seaLevel;

        // The priority flood: from the sea inwards, lowest first
        float[] filled = new float[size];
        int[] parent = new int[size];
        Arrays.fill(parent, -1);
        boolean[] done = new boolean[size];
        Heap heap = new Heap(size / 4);
        for (int t = 0; t < size; t++) {
            if (!sea[t]) continue;
            filled[t] = height[t];
            done[t] = true;
            for (int k = 0; k < 4; k++) {
                if (!sea[raster.neighbour(t, k)]) {
                    heap.push(seaLevel, t);
                    break;
                }
            }
        }
        while (heap.size() > 0) {
            int c = heap.pop();
            for (int k = 0; k < 4; k++) {
                int n = raster.neighbour(c, k);
                if (done[n]) continue;
                done[n] = true;
                filled[n] = Math.max(height[n], filled[c] + FLOOD_STEP);
                parent[n] = c;
                heap.push(filled[n], n);
            }
        }

        findHollows(height, filled, sea);
        lakeOf = new int[size];
        Arrays.fill(lakeOf, -1);


        List<Integer> sources = chooseSources(height, sea, seaLevel, new Random(noise.seed * 0x2545F491L + 0x51EDL));
        int[] receiver = new int[size], riverOf = new int[size];
        Arrays.fill(receiver, -1);
        Arrays.fill(riverOf, -1);
        for (int s = 0; s < sources.size(); s++) trace(sources.get(s), s, filled, sea, parent, receiver, riverOf);
        // The hollows the streams run into fill up: lakes, where they're small enough
        boolean[] reached = new boolean[hollows.size()];
        for (int t = 0; t < size; t++) if (riverOf[t] >= 0 && hollowOf[t] >= 0) reached[hollowOf[t]] = true;
        // (each one's level lowered to where it would spill over the ground as it really is, not
        // as the raster has it: so its water never reaches out over lower ground and ends in the air)
        Lake[] made = new Lake[hollows.size()];
        java.util.stream.IntStream.range(0, hollows.size()).parallel().forEach(h -> {
            int[] members = hollows.get(h);
            if (!reached[h] || members.length < LAKE_LEAST_TEXELS || members.length > LAKE_MOST_TEXELS || hollowShape.get(h)[1] < LAKE_DEEPEST) return;
            made[h] = makeLake(noise, h, members, hollowShape.get(h)[0], height);
        });
        nearLakes = new int[size * NEAR_LAKES];
        Arrays.fill(nearLakes, -1);
        int[] around = new int[9];
        for (int h = 0; h < hollows.size(); h++) {
            int[] members = hollows.get(h);
            Lake lake = made[h];
            if (lake == null) continue;
            for (int j = 0; j < lake.size; j++) {
                for (int i = 0; i < lake.size; i++) {
                    double[] d = lake.gridPoint(i, j, radius);
                    // (and the texels round it, as a river's banks reach that far)
                    int t = raster.index(d[0], d[1], d[2]);
                    int count = neighbours8(t, around);
                    around[count++] = t;
                    for (int a = 0; a < count; a++) {
                        int o = around[a] * NEAR_LAKES;
                        for (int s = 0; s < NEAR_LAKES; s++) {
                            if (nearLakes[o + s] == lakes.size()) break;
                            if (nearLakes[o + s] < 0) {
                                nearLakes[o + s] = lakes.size();
                                break;
                            }
                        }
                    }
                }
            }
            for (int c : members) {
                lakeOf[c] = lakes.size();
                double[] d = new double[3];
                raster.direction(c, d);
                lake.texels.add(d);
                lake.radii.add(texelSize(c) * 0.8f);
            }
            lakes.add(lake);
        }

        buildReaches(noise, seaLevel, filled, sea, receiver, riverOf);
        buildIndex();
        System.out.printf("[RIVERS] %d sources, %d reaches, %d lakes, %d channel pieces%n", sources.size(), reaches.size(), lakes.size(), segmentCount);
    }

    /** The hollows: the land the filling raises more than LAKE_DEPTH, joined up, each with its brim's level and its depth. */
    private void findHollows(float[] height, float[] filled, boolean[] sea) {
        int size = raster.size();
        hollowOf = new int[size];
        Arrays.fill(hollowOf, -1);
        int[] queue = new int[size];
        for (int t = 0; t < size; t++) {
            if (hollowOf[t] >= 0 || sea[t] || filled[t] - height[t] <= LAKE_DEPTH) continue;
            int id = hollows.size();
            int head = 0, tail = 0;
            queue[tail++] = t;
            hollowOf[t] = id;
            float deepest = 0f, level = -Float.MAX_VALUE;
            while (head < tail) {
                int c = queue[head++];
                deepest = Math.max(deepest, filled[c] - height[c]);
                level = Math.max(level, filled[c]);
                for (int k = 0; k < 4; k++) {
                    int n = raster.neighbour(c, k);
                    if (hollowOf[n] >= 0 || sea[n] || filled[n] - height[n] <= LAKE_DEPTH) continue;
                    hollowOf[n] = id;
                    queue[tail++] = n;
                }
            }
            hollows.add(Arrays.copyOf(queue, tail));
            hollowShape.add(new float[] { level, deepest });
        }
    }

    /**
     * The lake in hollow h (null if it would be too shallow): as high as it can stand (no higher
     * than level) without its water reaching past where RiverRenderer lets it (LAKE_SHORE_TEXELS
     * and LAKE_SHORE_SQUARES past its texels' middles), so its edge is never left in the air (a
     * priority flood of the ground as drawn, on the very grid its water is laid on, inwards from
     * that far out, read at its lowest place), and its water on that grid.
     */
    private Lake makeLake(PerlinNoise noise, int h, int[] members, float level, float[] height) {
        double[][] at = new double[members.length][];
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < members.length; i++) {
            at[i] = new double[3];
            raster.direction(members[i], at[i]);
            cx += at[i][0];
            cy += at[i][1];
            cz += at[i][2];
        }
        double[] c = unit(cx, cy, cz);
        double[] east = Math.abs(c[1]) > 0.999 ? new double[] { 1, 0, 0 } : unit(-c[2], 0.0, c[0]);
        double[] north = { c[1] * east[2] - c[2] * east[1], c[2] * east[0] - c[0] * east[2], c[0] * east[1] - c[1] * east[0] };
        // The texels' middles on the flat frame there
        double[] mx = new double[members.length], mz = new double[members.length];
        double extent = 0;
        for (int i = 0; i < members.length; i++) {
            double d = at[i][0] * c[0] + at[i][1] * c[1] + at[i][2] * c[2];
            mx[i] = radius * (at[i][0] * east[0] + at[i][1] * east[1] + at[i][2] * east[2]) / d;
            mz[i] = radius * (at[i][0] * north[0] + at[i][1] * north[1] + at[i][2] * north[2]) / d;
            extent = Math.max(extent, Math.hypot(mx[i], mz[i]));
        }
        double shore = LAKE_SHORE_TEXELS * texelSize() + LAKE_SHORE_SQUARES * LAKE_GRID;
        extent += shore + LAKE_GRID;
        int n = (int) Math.ceil(2 * extent / LAKE_GRID) + 1;
        Map<Long, Float> heights = new HashMap<>();
        float[] ground = new float[n * n], filled = new float[n * n];
        boolean[] done = new boolean[n * n];
        Heap heap = new Heap(n * 4);
        int lowest = -1;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int g = j * n + i;
                double x = -extent + i * LAKE_GRID, z = -extent + j * LAKE_GRID, nearest = Double.MAX_VALUE;
                for (int m = 0; m < members.length; m++) nearest = Math.min(nearest, (x - mx[m]) * (x - mx[m]) + (z - mz[m]) * (z - mz[m]));
                nearest = Math.sqrt(nearest);
                if (nearest >= shore || i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                    // (out of reach: where the water would run away)
                    done[g] = true;
                    continue;
                }
                double[] d = unit(c[0] + (east[0] * x + north[0] * z) / radius, c[1] + (east[1] * x + north[1] * z) / radius,
                        c[2] + (east[2] * x + north[2] * z) / radius);
                ground[g] = TerrainMesh.drawnHeight(new GeodesicChunkGrid.Direction(d[0], d[1], d[2]), noise, heights);
                if (hollowOf[raster.index(d[0], d[1], d[2])] == h && (lowest < 0 || ground[g] < ground[lowest])) lowest = g;
            }
        }
        if (lowest < 0) return null;
        // Inwards from the edge of reach, lowest first
        boolean[] edge = done.clone();
        for (int g = 0; g < n * n; g++) {
            if (edge[g]) continue;
            if (edge[g - 1] || edge[g + 1] || edge[g - n] || edge[g + n]) {
                done[g] = true;
                filled[g] = ground[g];
                heap.push(ground[g], g);
            }
        }
        while (heap.size() > 0) {
            int g = heap.pop();
            int[] next = { g - 1, g + 1, g - n, g + n };
            for (int m : next) {
                if (done[m]) continue;
                done[m] = true;
                filled[m] = Math.max(ground[m], filled[g]);
                heap.push(filled[m], m);
            }
        }
        level = Math.min(level, filled[lowest] - SPILL_MARGIN);
        float deepest = Float.MAX_VALUE;
        for (int t : members) deepest = Math.min(deepest, height[t]);
        if (level - deepest < LAKE_DEEPEST) return null;
        // Its water: below its level, joined to its lowest place
        float[] water = new float[n * n];
        Arrays.fill(water, -1f);
        int[] queue = new int[n * n];
        int head = 0, tail = 0;
        if (level > ground[lowest]) {
            queue[tail++] = lowest;
            water[lowest] = level - ground[lowest];
        }
        boolean[] outside = edge;
        while (head < tail) {
            int g = queue[head++];
            int[] next = { g - 1, g + 1, g - n, g + n };
            for (int m : next) {
                if (outside[m] || water[m] > 0f || ground[m] >= level) continue;
                water[m] = level - ground[m];
                queue[tail++] = m;
            }
        }
        for (int g = 0; g < n * n; g++) if (!outside[g] && water[g] <= 0f) water[g] = Math.min(-0.01f, level - ground[g]);
        return new Lake(level, c, east, north, extent, n, water);
    }

    /** About how far across a texel is, in world units. */
    private float texelSize(int t) {
        return (float) Math.sqrt(raster.solidAngle(t)) * radius;
    }

    /** High places for streams to rise, spaced out, more of them where it's wet, highest first. */
    private List<Integer> chooseSources(float[] height, boolean[] sea, float seaLevel, Random rand) {
        float highest = seaLevel;
        for (int t = 0; t < height.length; t++) if (!sea[t]) highest = Math.max(highest, height[t]);
        float least = seaLevel + Math.max(SOURCE_LEAST_HEIGHT, SOURCE_SHARE * (highest - seaLevel));
        List<Integer> high = new ArrayList<>();
        for (int t = 0; t < height.length; t++) if (!sea[t] && hollowOf[t] < 0 && height[t] > least) high.add(t);
        java.util.Collections.shuffle(high, rand);
        int wanted = Math.max(FEWEST_SOURCES, Math.min(MOST_SOURCES, high.size() / HIGH_TEXELS_PER_SOURCE));
        double apart = Math.cos(SOURCE_SPACING * 2.0 / SIDE);
        List<Integer> chosen = new ArrayList<>();
        List<double[]> places = new ArrayList<>();
        for (int t : high) {
            if (chosen.size() >= wanted) break;
            // (more readily where it's wet)
            if (rand.nextFloat() > DRIEST_SOURCE_CHANCE + (1f - DRIEST_SOURCE_CHANCE) * rainAt(t)) continue;
            double[] d = new double[3];
            raster.direction(t, d);
            boolean ok = true;
            for (double[] p : places) {
                if (d[0] * p[0] + d[1] * p[1] + d[2] * p[2] > apart) { ok = false; break; }
            }
            if (!ok) continue;
            chosen.add(t);
            places.add(d);
        }
        chosen.sort((a, b) -> Float.compare(height[b], height[a]));
        return chosen;
    }

    /**
     * Runs a stream down from a source over the filled surface: each step to the neighbour it
     * falls to most steeply, more readily one beside another stream; it stops on reaching the
     * sea or another stream (joining it).
     */
    private void trace(int source, int id, float[] filled, boolean[] sea, int[] parent, int[] receiver, int[] riverOf) {
        if (riverOf[source] >= 0) return;
        int t = source;
        double[] here = new double[3], there = new double[3];
        int[] around = new int[8];
        while (true) {
            riverOf[t] = id;
            if (sea[t]) return;
            raster.direction(t, here);
            int count = neighbours8(t, around);
            int best = -1;
            double bestScore = -1.0;
            for (int i = 0; i < count; i++) {
                int n = around[i];
                if (filled[n] >= filled[t]) continue;
                raster.direction(n, there);
                double step = Math.sqrt(sq(here[0] - there[0]) + sq(here[1] - there[1]) + sq(here[2] - there[2]));
                double score = (filled[t] - filled[n]) / Math.max(1e-9, step) * (1.0 + ATTRACTION * nearOther(n, id, riverOf));
                if (score > bestScore) {
                    bestScore = score;
                    best = n;
                }
            }
            if (best < 0) best = parent[t];
            if (best < 0) return;
            receiver[t] = best;
            if (riverOf[best] >= 0 && riverOf[best] != id) return;
            t = best;
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Whether another stream than id runs through a texel or beside it. */
    private float nearOther(int t, int id, int[] riverOf) {
        if (riverOf[t] >= 0 && riverOf[t] != id) return 1f;
        for (int k = 0; k < 4; k++) {
            int n = raster.neighbour(t, k);
            if (riverOf[n] >= 0 && riverOf[n] != id) return 1f;
        }
        return 0f;
    }

    /** The texels round one (its four edge neighbours and those across its corners), into out; how many. */
    private int neighbours8(int t, int[] out) {
        int count = 0;
        for (int k = 0; k < 4; k++) {
            int n = raster.neighbour(t, k);
            count = addUnique(out, count, n, t);
            // (across the corner: on along the other axis from the edge neighbour)
            count = addUnique(out, count, raster.neighbour(n, k < 2 ? 2 : 0), t);
        }
        return count;
    }

    private static int addUnique(int[] out, int count, int n, int self) {
        if (n == self) return count;
        for (int i = 0; i < count; i++) if (out[i] == n) return count;
        out[count] = n;
        return count + 1;
    }

    /** Joins the traced streams into reaches, smoothed, with their widths and water levels. */
    private void buildReaches(PerlinNoise noise, float seaLevel, float[] filled, boolean[] sea, int[] receiver, int[] riverOf) {
        int size = raster.size();
        // How much has run into each texel of the streams (its own rain's worth included, so more
        // where it's wet): in order from the highest of the filled surface down, each passing its share on
        List<Integer> network = new ArrayList<>();
        for (int t = 0; t < size; t++) if (riverOf[t] >= 0) network.add(t);
        network.sort((a, b) -> Float.compare(filled[b], filled[a]));
        float[] carried = new float[size];
        int[] inflows = new int[size];
        for (int t : network) {
            carried[t] += RAIN_CARRIED + WET_CARRIED * rainAt(t);
            if (receiver[t] >= 0) {
                carried[receiver[t]] += carried[t];
                inflows[receiver[t]]++;
            }
        }
        // Reaches begin at sources and at meetings, and run down to the next meeting or the sea
        // (worked out from upstream down, so each knows the level the water reaches it at)
        Map<Integer, Float> meetingLevel = new HashMap<>();
        // (and how far the water has run there from its furthest source)
        Map<Integer, Float> lengthAt = new HashMap<>();
        // Lakes' outlets whose rivers reach a meeting (by its texel) before their water's kept far
        // enough out of their channels, to carry on down the reach from there: {lake, its cut, points left}
        Map<Integer, List<int[]>> waiting = new HashMap<>();
        // (and lakes' inlets whose rivers begin at a meeting close by, to carry on up the reaches
        // meeting there once all are made: {meeting's texel, lake, its cut, points left}; and the
        // reaches ending at each meeting, and where each begins)
        List<int[]> upstream = new ArrayList<>();
        Map<Integer, List<Reach>> endingAt = new HashMap<>();
        Map<Reach, Integer> startOf = new java.util.IdentityHashMap<>();
        for (int start : network) {
            if (inflows[start] == 1 || receiver[start] < 0) continue;
            List<Integer> chain = new ArrayList<>();
            chain.add(start);
            int c = start;
            while (receiver[c] >= 0) {
                c = receiver[c];
                chain.add(c);
                if (inflows[c] != 1 || sea[c]) break;
            }
            boolean source = inflows[start] == 0;
            float length = lengthAt.getOrDefault(start, 0f);
            Reach reach = smoothReach(noise, seaLevel, chain, carried, meetingLevel.getOrDefault(start, Float.MAX_VALUE), sea[c], source, length);
            if (reach == null) continue;
            reaches.add(reach);
            int end = chain.get(chain.size() - 1);
            endingAt.computeIfAbsent(end, e -> new ArrayList<>()).add(reach);
            startOf.put(reach, start);
            reach.startsAt = source ? -1 : start;
            reach.endsAt = sea[c] ? -1 : end;
            reach.inflow = carried[chain.get(Math.max(0, chain.size() - 2))];
            for (int[] p : arriving) upstream.add(new int[] { start, p[0], p[1], p[2] });
            arriving.clear();
            // Lakes' outlets just upstream: their water kept out of this reach's channel too
            List<int[]> outlets = new ArrayList<>(leaving);
            leaving.clear();
            for (int[] p : waiting.getOrDefault(start, List.of())) outlets.add(p);
            waiting.remove(start);
            for (int[] p : outlets) {
                if (p[3] == 0) {
                    // (one of this reach's own: already along it)
                    if (p[2] > 0) waiting.computeIfAbsent(end, e -> new ArrayList<>()).add(new int[] { p[0], p[1], p[2], 1 });
                    continue;
                }
                int left = extendCorridor(reach, p[0], p[1], p[2]);
                if (left > 0) waiting.computeIfAbsent(end, e -> new ArrayList<>()).add(new int[] { p[0], p[1], left, 1 });
            }
            float last = reach.rim[reach.size() - 1];
            meetingLevel.merge(end, last, Math::min);
            lengthAt.merge(end, length + reach.along[reach.size() - 1], Math::max);
        }
        // At each meeting, the reach bringing the most water in carries on as the river below it
        for (List<Reach> in : endingAt.values()) {
            Reach main = null;
            for (Reach r : in) if (r.endsAt >= 0 && (main == null || r.inflow > main.inflow)) main = r;
            if (main != null) main.mainInflow = true;
        }
        // Inlets' corridors carried on up the reaches meeting just above them (each its own cut)
        for (int q = 0; q < upstream.size(); q++) {
            int[] p = upstream.get(q);
            Lake l = lakes.get(p[1]);
            Cut cut = l.cuts.get(p[2]);
            for (Reach r : endingAt.getOrDefault(p[0], List.of())) {
                // (its last point is the meeting, already in the corridor)
                int to = r.size() - 2, from = to;
                if (to < 0 || r.lake[to] == p[1]) continue;
                while (from - 1 >= 0 && to - from + 1 < p[3] && r.lake[from - 1] != p[1]) from--;
                l.cuts.add(new Cut(cut.point, cut.tangent, false, Arrays.copyOfRange(r.points, from, to + 1),
                        corridorWidths(r, from, to, l.level)));
                int left = p[3] - (to - from + 1);
                if (from == 0 && left > 0) upstream.add(new int[] { startOf.get(r), p[1], p[2], left });
            }
        }
    }

    /**
     * A reach through the texels of chain, as a smooth meandering curve of points POINT_STEP
     * apart, each with its width (from what's carried there) and water level (never above
     * startLevel, nor above any it has had upstream, nor in a lake above the lake's).
     */
    private Reach smoothReach(PerlinNoise noise, float seaLevel, List<Integer> chain, float[] carried, float startLevel, boolean toSea,
            boolean source, float length) {
        // The texels' middles, smoothed (corners cut, three times over, the ends kept)
        List<double[]> line = new ArrayList<>();
        List<Float> flow = new ArrayList<>();
        for (int t : chain) {
            double[] d = new double[3];
            raster.direction(t, d);
            line.add(d);
            flow.add(carried[t]);
        }
        for (int pass = 0; pass < 3 && line.size() > 2; pass++) {
            List<double[]> cut = new ArrayList<>();
            List<Float> cutFlow = new ArrayList<>();
            cut.add(line.get(0));
            cutFlow.add(flow.get(0));
            for (int i = 0; i + 1 < line.size(); i++) {
                double[] p = line.get(i), q = line.get(i + 1);
                cut.add(unit(p[0] * 0.75 + q[0] * 0.25, p[1] * 0.75 + q[1] * 0.25, p[2] * 0.75 + q[2] * 0.25));
                cut.add(unit(p[0] * 0.25 + q[0] * 0.75, p[1] * 0.25 + q[1] * 0.75, p[2] * 0.25 + q[2] * 0.75));
                cutFlow.add(flow.get(i));
                cutFlow.add(flow.get(i + 1));
            }
            cut.add(line.get(line.size() - 1));
            cutFlow.add(flow.get(flow.size() - 1));
            line = cut;
            flow = cutFlow;
        }
        // Into the sea: on past the last texel's middle a little, the way it was going, so it's sure
        // to reach the sea as the ground's drawn (cut back below to just past the coast)
        if (toSea && line.size() >= 2) {
            double[] last = line.get(line.size() - 1), before = line.get(line.size() - 2);
            double tx = last[0] - before[0], ty = last[1] - before[1], tz = last[2] - before[2];
            double up = tx * last[0] + ty * last[1] + tz * last[2];
            tx -= up * last[0];
            ty -= up * last[1];
            tz -= up * last[2];
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl > 1e-15) {
                float carriedLast = flow.get(flow.size() - 1);
                for (int e = 1; e <= MOUTH_POINTS; e++) {
                    double off = e * MOUTH_STEP / radius / tl;
                    line.add(unit(last[0] + tx * off, last[1] + ty * off, last[2] + tz * off));
                    flow.add(carriedLast);
                }
            }
        }
        // Along it every POINT_STEP
        double[] lengths = new double[line.size()];
        for (int i = 1; i < line.size(); i++) lengths[i] = lengths[i - 1] + chord(line.get(i - 1), line.get(i)) * radius;
        double total = lengths[lengths.length - 1];
        if (total < POINT_STEP) return null;
        int n = (int) Math.ceil(total / POINT_STEP) + 1;
        double[][] centre = new double[n][];
        float[] carriedAt = new float[n];
        int seg = 0;
        for (int k = 0; k < n; k++) {
            double s = Math.min(total, k * total / (n - 1));
            while (seg < line.size() - 2 && lengths[seg + 1] < s) seg++;
            double span = lengths[seg + 1] - lengths[seg];
            double f = span > 0 ? (s - lengths[seg]) / span : 0.0;
            double[] p = line.get(seg), q = line.get(seg + 1);
            centre[k] = unit(p[0] + (q[0] - p[0]) * f, p[1] + (q[1] - p[1]) * f, p[2] + (q[2] - p[2]) * f);
            carriedAt[k] = (float) (flow.get(seg) + (flow.get(seg + 1) - flow.get(seg)) * f);
        }
        // A gentle meander either side (none at the ends, where reaches meet)
        double phase = (chain.get(0) * 0.6180339887) % 1.0 * 100.0;
        double[][] across = new double[n][];
        for (int k = 0; k < n; k++) across[k] = side(centre, k);
        double[][] points = new double[n][];
        for (int k = 0; k < n; k++) {
            double s = k * total / (n - 1);
            double taper = smooth(s / 60.0) * smooth((total - s) / 60.0);
            double wander = MEANDER * taper * (noise1(s / MEANDER_WAVELENGTH + phase) * 2.0 - 1.0);
            double[] c = centre[k], a = across[k];
            points[k] = unit(c[0] + a[0] * wander / radius, c[1] + a[1] * wander / radius, c[2] + a[2] * wander / radius);
        }
        // It ends once well out in the sea (its water dipping under the sea's from the coast on)
        float[] ground = new float[n];
        int kept = n;
        for (int k = 0; k < kept; k++) {
            ground[k] = groundAt(noise, points[k]);
            if (ground[k] < seaLevel - MOUTH_END) kept = k + 1;
        }
        if (kept < 2) return null;
        // Each point's width, which lake it's out in, then its depth and water level
        float[] halves = new float[kept], troughs = new float[kept];
        // (wider for its length where it's wetter)
        float wetness = 0f;
        for (int t : chain) wetness += rainAt(t) / chain.size();
        for (int k = 0; k < kept; k++) {
            float run = (float) (k * total / (n - 1));
            halves[k] = SOURCE_HALF_WIDTH + HALF_WIDTH_GAIN * (float) Math.sqrt(Math.max(0f, carriedAt[k] - 1f))
                    + LENGTH_GAIN * (0.5f + wetness) * (float) Math.sqrt((length + run) / LENGTH_UNIT);
            if (source) halves[k] *= SOURCE_TRICKLE + (1f - SOURCE_TRICKLE) * (float) smooth(run / SOURCE_TAPER);
            troughs[k] = Math.max(halves[k] * TROUGH_PER_HALF_WIDTH, LEAST_TROUGH);
        }
        int[] lake = new int[kept];
        List<int[]> shores = settleShores(points, halves, ground, lake, kept);
        Reach reach = new Reach(kept);
        float running = startLevel;
        for (int k = 0; k < kept; k++) {
            double[] p = points[k];
            float half = halves[k], trough = troughs[k];
            float depth = DEPTH_PER_WIDTH * 2f * half;
            if (source) depth *= (float) smooth(k * total / (n - 1) / SOURCE_TAPER);
            if (lake[k] >= 0) {
                running = Math.min(running, lakes.get(lake[k]).level);
            } else {
                double[] a = side(points, k);
                float beside = Math.min(ground[k], Math.min(groundAt(noise, offset(p, a, trough * 1.1)),
                        groundAt(noise, offset(p, a, -trough * 1.1))));
                running = Math.min(running, beside - LEVEL_MARGIN);
            }
            running = Math.max(running, seaLevel);
            // (nearing the sea, the channel shallower, so its bed stays above the sea's level)
            float rim = Math.max(running, seaLevel + MOUTH_RIM);
            depth = Math.min(depth, rim - seaLevel - MOUTH_BED);
            reach.points[k] = p;
            reach.lake[k] = lake[k];
            reach.inLake[k] = lake[k] >= 0;
            reach.rim[k] = rim;
            reach.halfWidth[k] = half;
            reach.trough[k] = trough;
            reach.depth[k] = depth;
            reach.surface[k] = reach.inLake[k] ? running : rim - depth * bed(half / trough);
            reach.along[k] = (float) (k * total / (n - 1));
            reach.rises[k] = source ? (float) smooth(reach.along[k] / SOURCE_TAPER) : 1f;
        }
        meetLakes(reach);
        for (int k = 0; k < reach.size(); k++) {
            int a = Math.max(0, k - 1), b = Math.min(reach.size() - 1, k + 1);
            float run = Math.max(1e-3f, reach.along[b] - reach.along[a]);
            float slope = Math.max(0f, (reach.surface[a] - reach.surface[b]) / run);
            reach.speed[k] = reach.inLake[k] ? 0f : Math.min(FASTEST, SLOWEST + FLOW_PER_SLOPE * slope);
        }
        // Near the lakes, its banks kept out of their water
        settleFloors(reach);
        // Where it crosses a lake's shore, the lake's water is cut off at it
        for (int[] shore : shores) {
            int k = shore[1];
            boolean outlet = shore[2] == 1;
            // (along it until it's back in the lake, no further than CUT_CORRIDOR_POINTS)
            int from = k, to = k;
            if (outlet) {
                while (to + 1 < kept && to - k < CUT_CORRIDOR_POINTS && reach.lake[to + 1] != shore[0]) to++;
            } else {
                while (from - 1 >= 0 && k - from < CUT_CORRIDOR_POINTS && reach.lake[from - 1] != shore[0]) from--;
            }
            List<Cut> cuts = lakes.get(shore[0]).cuts;
            cuts.add(new Cut(reach.points[k], tangent(reach.points, k), outlet, Arrays.copyOfRange(reach.points, from, to + 1),
                    corridorWidths(reach, from, to, lakes.get(shore[0]).level)));
            // (an outlet's corridor reaching the end of the reach carries on down the next, an
            // inlet's reaching its start up those before)
            if (outlet && to == kept - 1 && to - k < CUT_CORRIDOR_POINTS) leaving.add(new int[] { shore[0], cuts.size() - 1, CUT_CORRIDOR_POINTS - (to - k), 0 });
            if (!outlet && from == 0 && k < CUT_CORRIDOR_POINTS) arriving.add(new int[] { shore[0], cuts.size() - 1, CUT_CORRIDOR_POINTS - k });
        }
        return reach;
    }

    // Outlets' corridors reaching the end of the reach just made, for buildReaches: {lake, its cut,
    // points left, 0}; and inlets' reaching its start: {lake, its cut, points left}
    private final List<int[]> leaving = new ArrayList<>(), arriving = new ArrayList<>();

    /** How far either side of a reach's points from to to its channel's cut below a lake's level (and CUT_SPARE more). */
    private static float[] corridorWidths(Reach r, int from, int to, float level) {
        float[] widths = new float[to - from + 1];
        for (int j = from; j <= to; j++) {
            float out = 0f;
            for (float d = 0f; d < r.trough[j] + BANK_REACH; d += 1f) {
                if (profile(r.rim[j], r.trough[j], r.depth[j], r.floor[j], r.floorReach[j], r.halfWidth[j], d) < level) out = d + 1f;
            }
            widths[j - from] = out + CUT_SPARE;
        }
        return widths;
    }

    /**
     * Carries a lake's outlet's corridor (lake's cut c) on down a reach starting where the last
     * ended, for up to left points or until it's back in the lake; how many points are left after.
     */
    private int extendCorridor(Reach r, int lake, int c, int left) {
        // (its first point is the last one's end)
        int to = 0;
        while (to + 1 < r.size() && to < left && r.lake[to + 1] != lake) to++;
        if (to == 0) return 0;
        Lake l = lakes.get(lake);
        Cut cut = l.cuts.get(c);
        double[][] corridor = Arrays.copyOf(cut.corridor, cut.corridor.length + to);
        System.arraycopy(r.points, 1, corridor, cut.corridor.length, to);
        float[] widths = Arrays.copyOf(cut.widths, cut.widths.length + to);
        System.arraycopy(corridorWidths(r, 1, to, l.level), 0, widths, cut.widths.length, to);
        l.cuts.set(c, new Cut(cut.point, cut.tangent, cut.outlet, corridor, widths));
        return to == r.size() - 1 && r.lake[to] != lake ? left - to : 0;
    }

    /**
     * Which lake, if any, each of a line of points is out in (into lake, -1 none): where a lake's
     * water is under it, or beside its water (halves its half widths, a little more), as the
     * lake's own water grid has it. The texels only roughly follow a lake's shore, and a river may run
     * along its edge, so it's taken to be in it wherever it touches it. (Gaps of up to
     * SHORE_GAP points are closed.) Where it goes in or comes out, the first and last points in it, where the
     * river meets the lake, are kept as {lake, point, 1 if going out else 0}.
     */
    private List<int[]> settleShores(double[][] points, float[] halves, float[] ground, int[] lake, int n) {
        for (int k = 0; k < n; k++) {
            double[] p = points[k];
            int t = raster.index(p[0], p[1], p[2]) * NEAR_LAKES;
            lake[k] = -1;
            double[] a = side(points, k);
            double[][] across = new double[ACROSS_SAMPLES.length][];
            for (int i = 0; i < across.length; i++) across[i] = offset(p, a, ACROSS_SAMPLES[i] * (halves[k] * NOTCH_WIDTH + CUT_SPARE));
            for (int s = 0; s < NEAR_LAKES && nearLakes[t + s] >= 0 && lake[k] < 0; s++) {
                Lake l = lakes.get(nearLakes[t + s]);
                for (double[] q : across) {
                    if (l.depthAt(q, radius) > 0f) {
                        lake[k] = nearLakes[t + s];
                        break;
                    }
                }
            }
        }
        for (int k = 1; k + 1 < n; k++) {
            if (lake[k] >= 0 || lake[k - 1] < 0) continue;
            for (int gap = 1; gap <= SHORE_GAP && k + gap < n; gap++) {
                if (lake[k + gap] != lake[k - 1]) continue;
                for (int j = k; j < k + gap; j++) lake[j] = lake[k - 1];
                break;
            }
        }
        // Going out, the lake ends at its lip: the highest of the ground along its last few points
        // in it (if that's about at its level), so its water never reaches out past the lip over
        // the river falling away below
        for (int k = 0; k + 1 < n; k++) {
            if (lake[k] < 0 || lake[k + 1] == lake[k]) continue;
            int lip = k;
            for (int j = k - 1; j >= Math.max(0, k - LIP_SEARCH) && lake[j] == lake[k]; j--) if (ground[j] > ground[lip]) lip = j;
            if (lip < k && ground[lip] >= lakes.get(lake[k]).level - LIP_BELOW) {
                for (int j = lip + 1; j <= k; j++) lake[j] = -1;
            }
        }
        List<int[]> shores = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            if (lake[k] < 0) continue;
            if (k > 0 && lake[k - 1] != lake[k]) shores.add(new int[] { lake[k], k, 0 });
            if (k + 1 < n && lake[k + 1] != lake[k]) shores.add(new int[] { lake[k], k, 1 });
        }
        return shores;
    }

    /** The way along a line of points at point k, along the ground (square to up). */
    private static double[] tangent(double[][] points, int k) {
        double[] a = points[Math.max(0, k - 1)], b = points[Math.min(points.length - 1, k + 1)], p = points[k];
        double tx = b[0] - a[0], ty = b[1] - a[1], tz = b[2] - a[2];
        double up = tx * p[0] + ty * p[1] + tz * p[2];
        tx -= up * p[0];
        ty -= up * p[1];
        tz -= up * p[2];
        double l = Math.sqrt(tx * tx + ty * ty + tz * tz);
        return l < 1e-15 ? new double[] { 1, 0, 0 } : new double[] { tx / l, ty / l, tz / l };
    }

    /** How far down a trough's bed is a share x (0 middle, 1 edge) of the way out: rounded, its sides easing in to the banks. */
    private static float bed(float x) {
        float f = 1f - Math.min(1f, x * x);
        return f * f;
    }

    /**
     * Where a reach runs into or out of a lake, its water meets the lake's at the lake's own
     * level: out in the lake it's the lake's; coming in, it's never below it, easing down to it
     * over LAKE_EASE; going out, it starts at it and eases away. (The channel's brim moves with
     * the water, so the channel keeps its shape.)
     */
    private void meetLakes(Reach r) {
        int n = r.size();
        for (int k = 0; k < n; k++) {
            if (!r.inLake[k]) continue;
            float level = lakes.get(r.lake[k]).level;
            r.surface[k] = level;
            r.rim[k] = level + r.depth[k] * bed(r.halfWidth[k] / r.trough[k]);
            // Coming in (the points upstream of where it enters)
            if (k > 0 && !r.inLake[k - 1]) {
                for (int j = k - 1; j >= 0 && !r.inLake[j]; j--) {
                    float offset = r.rim[j] - r.surface[j];
                    float surface = Math.max(r.surface[j], level);
                    float t = (r.along[k] - r.along[j]) / LAKE_EASE;
                    if (t < 1f) surface = level + (surface - level) * (float) smooth(t);
                    r.surface[j] = surface;
                    r.rim[j] = surface + offset;
                }
            }
            // Going out (the points downstream of where it leaves)
            if (k + 1 < n && !r.inLake[k + 1]) {
                for (int j = k + 1; j < n && !r.inLake[j]; j++) {
                    float t = (r.along[j] - r.along[k]) / LAKE_EASE;
                    if (t >= 1f) break;
                    float offset = r.rim[j] - r.surface[j];
                    float surface = Math.min(level, level + (r.surface[j] - level) * (float) smooth(t));
                    r.surface[j] = surface;
                    r.rim[j] = surface + offset;
                }
            }
        }
    }

    /** The bare ground's height at a point. */
    private static float groundAt(PerlinNoise noise, double[] p) {
        return TerrainMesh.getLayeredHeight(new GeodesicChunkGrid.Direction(p[0], p[1], p[2]), noise);
    }

    /** A point moved a distance (world units) along a direction square to it. */
    private double[] offset(double[] p, double[] a, double distance) {
        return unit(p[0] + a[0] * distance / radius, p[1] + a[1] * distance / radius, p[2] + a[2] * distance / radius);
    }

    /** The way across a line of points at point k, along the ground (square to the way along and to up). */
    private static double[] side(double[][] points, int k) {
        double[] a = points[Math.max(0, k - 1)], b = points[Math.min(points.length - 1, k + 1)], p = points[k];
        double tx = b[0] - a[0], ty = b[1] - a[1], tz = b[2] - a[2];
        double sx = p[1] * tz - p[2] * ty, sy = p[2] * tx - p[0] * tz, sz = p[0] * ty - p[1] * tx;
        double l = Math.sqrt(sx * sx + sy * sy + sz * sz);
        return l < 1e-15 ? new double[] { 0, 0, 0 } : new double[] { sx / l, sy / l, sz / l };
    }

    private static double chord(double[] a, double[] b) {
        return Math.sqrt(sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2]));
    }

    private static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[] { x / l, y / l, z / l };
    }

    private static double smooth(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    /** Smooth noise along a line, 0 to 1. */
    private static double noise1(double x) {
        long i = (long) Math.floor(x);
        double f = x - i;
        f = f * f * (3.0 - 2.0 * f);
        return hash(i) + (hash(i + 1) - hash(i)) * f;
    }

    private static double hash(long i) {
        long h = i * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    // ------------------------------------------------------------------ finding them again

    /** Files each piece of channel under the texels it reaches. */
    private void buildIndex() {
        List<float[]> pieces = new ArrayList<>();
        for (Reach r : reaches) {
            for (int k = 0; k + 1 < r.size(); k++) {
                double[] a = r.points[k], b = r.points[k + 1];
                float reach = Math.max(r.trough[k], r.trough[k + 1]) + BANK_REACH;
                pieces.add(new float[] {
                    (float) (a[0] * radius), (float) (a[1] * radius), (float) (a[2] * radius),
                    (float) (b[0] * radius), (float) (b[1] * radius), (float) (b[2] * radius),
                    r.rim[k], r.rim[k + 1], r.trough[k], r.trough[k + 1], r.depth[k], r.depth[k + 1],
                    r.surface[k], r.surface[k + 1], reach, r.floor[k], r.floor[k + 1], r.floorReach[k], r.floorReach[k + 1],
                    r.halfWidth[k], r.halfWidth[k + 1] });
            }
        }
        segmentCount = pieces.size();
        segments = new float[segmentCount * SEGMENT];
        for (int i = 0; i < segmentCount; i++) System.arraycopy(pieces.get(i), 0, segments, i * SEGMENT, SEGMENT);

        // Each piece under every texel within its reach (searching out from its middle's texel;
        // the pieces side by side on every thread)
        int size = raster.size();
        int[][] reached = new int[segmentCount][];
        java.util.stream.IntStream.range(0, segmentCount).parallel().forEach(i -> reached[i] = texelsReached(i));
        int[] counts = new int[size + 1];
        for (int[] texels : reached) for (int t : texels) counts[t]++;

        // The cells of the drawn ground the channels reach: those under points across each piece,
        // out to its reach either side (the cells are many times the reach across, so none is missed)
        GeodesicChunkGrid drawn = new GeodesicChunkGrid(TerrainMesh.DRAWN_LEVEL);
        Set<Long> cells = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.stream.IntStream.range(0, segmentCount).parallel().forEach(i -> {
            int o = i * SEGMENT;
            double[] a = { segments[o], segments[o + 1], segments[o + 2] }, b = { segments[o + 3], segments[o + 4], segments[o + 5] };
            double[] middle = unit(a[0] + b[0], a[1] + b[1], a[2] + b[2]);
            double[] side = unit(middle[1] * (b[2] - a[2]) - middle[2] * (b[1] - a[1]),
                    middle[2] * (b[0] - a[0]) - middle[0] * (b[2] - a[2]), middle[0] * (b[1] - a[1]) - middle[1] * (b[0] - a[0]));
            for (int s = -2; s <= 2; s++) {
                double off = s * 0.5 * segments[o + 14] / radius;
                cells.add(drawn.cellAt(new GeodesicChunkGrid.Direction(middle[0] + side[0] * off, middle[1] + side[1] * off,
                        middle[2] + side[2] * off)).id());
            }
        });
        channelCells.addAll(cells);
        cellStart = new int[size + 1];
        for (int t = 0; t < size; t++) cellStart[t + 1] = cellStart[t] + counts[t];
        cellPieces = new int[cellStart[size]];
        int[] fill = Arrays.copyOf(cellStart, size);
        for (int i = 0; i < segmentCount; i++) for (int t : reached[i]) cellPieces[fill[t]++] = i;
    }

    /** The texels within a piece's reach: searching out from its middle's texel to the first ring clear of it. */
    private int[] texelsReached(int i) {
        int o = i * SEGMENT;
        double[] middle = unit(segments[o] + segments[o + 3], segments[o + 1] + segments[o + 4], segments[o + 2] + segments[o + 5]);
        double half = 0.5 * Math.sqrt(sq(segments[o] - segments[o + 3]) + sq(segments[o + 1] - segments[o + 4]) + sq(segments[o + 2] - segments[o + 5]));
        double reach = half + segments[o + 14];
        int start = raster.index(middle[0], middle[1], middle[2]);
        // (a few tens at most: kept in plain arrays, searched straight through)
        int[] queue = new int[64], kept = new int[32];
        int tail = 0, count = 0;
        queue[tail++] = start;
        double[] d = new double[3];
        for (int q = 0; q < tail; q++) {
            int t = queue[q];
            raster.direction(t, d);
            if (t != start && chord(d, middle) * radius > reach + texelSize(t)) continue;
            if (count == kept.length) kept = Arrays.copyOf(kept, count * 2);
            kept[count++] = t;
            for (int k = 0; k < 4; k++) {
                int n = raster.neighbour(t, k);
                boolean seen = false;
                for (int j = 0; j < tail && !seen; j++) seen = queue[j] == n;
                if (seen) continue;
                if (tail == queue.length) queue = Arrays.copyOf(queue, tail * 2);
                queue[tail++] = n;
            }
        }
        return Arrays.copyOf(kept, count);
    }

    /**
     * The ground's height h at a place on the datum (x, y, z, world units from the planet's
     * centre) with any channel cut under it: a rounded bed below the water, banks rising from
     * the water's edge and easing back into the land.
     */
    public float carve(float x, float y, float z, float h) {
        int t = raster.index(x, y, z);
        int from = cellStart[t], to = cellStart[t + 1];
        float lowest = h;
        for (int i = from; i < to; i++) {
            int o = cellPieces[i] * SEGMENT;
            float[] s = segments;
            float ax = s[o], ay = s[o + 1], az = s[o + 2];
            float ux = s[o + 3] - ax, uy = s[o + 4] - ay, uz = s[o + 5] - az;
            float length2 = ux * ux + uy * uy + uz * uz;
            float f = length2 > 0f ? ((x - ax) * ux + (y - ay) * uy + (z - az) * uz) / length2 : 0f;
            f = Math.max(0f, Math.min(1f, f));
            float dx = x - (ax + ux * f), dy = y - (ay + uy * f), dz = z - (az + uz * f);
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float reach = s[o + 14];
            if (distance >= reach) continue;
            float rim = s[o + 6] + (s[o + 7] - s[o + 6]) * f;
            float trough = s[o + 8] + (s[o + 9] - s[o + 8]) * f;
            float depth = s[o + 10] + (s[o + 11] - s[o + 10]) * f;
            float floor = s[o + 15] + (s[o + 16] - s[o + 15]) * f, floorReach = s[o + 17] + (s[o + 18] - s[o + 17]) * f;
            float profile = profile(rim, trough, depth, floor, floorReach, s[o + 19] + (s[o + 20] - s[o + 19]) * f, distance);
            if (profile >= h) continue;
            float ease = Math.min(1f, (reach - distance) / BANK_EASE);
            float cut = h + (profile - h) * ease * ease * (3f - 2f * ease);
            lowest = Math.min(lowest, cut);
        }
        return lowest;
    }

    /**
     * How high the channel's cut is a distance from its middle (before easing into the land): a
     * rounded trough under its brim, banks rising from it; and near a lake (floor its level, its
     * water floorReach off), never below the lake's level within LEVEE of its water, sloping down
     * from there towards the river as its banks would, and steeply into the notch the water runs
     * through.
     */
    private static float profile(float rim, float trough, float depth, float floor, float floorReach, float half, float distance) {
        float profile = distance < trough ? rim - depth * bed(distance / trough) : rim + (distance - trough) * BANK_SLOPE;
        if (floor <= NO_FLOOR) return profile;
        return Math.max(profile, floor + FLOOR_ABOVE - Math.max(0f, floorReach - LEVEE - distance) * BANK_SLOPE
                - Math.max(0f, half * NOTCH_WIDTH - distance) * NOTCH_WALL);
    }

    /**
     * For each point of a reach whose banks reach a lake's water: that lake's level and how far
     * off its water is (looking round it on the lakes' water grids; none, out in a lake, so its
     * channel there is notched only as wide as its water into the lake's lip).
     */
    private void settleFloors(Reach r) {
        for (int k = 0; k < r.size(); k++) {
            if (r.inLake[k]) {
                r.floor[k] = lakes.get(r.lake[k]).level;
                r.floorReach[k] = 0f;
                continue;
            }
            double[] p = r.points[k];
            int t = raster.index(p[0], p[1], p[2]) * NEAR_LAKES;
            if (nearLakes[t] < 0) continue;
            double[] a = side(r.points, k), along = tangent(r.points, k);
            float reach = r.trough[k] + BANK_REACH;
            for (int s = 0; s < NEAR_LAKES && nearLakes[t + s] >= 0; s++) {
                Lake l = lakes.get(nearLakes[t + s]);
                float nearest = Float.MAX_VALUE;
                for (float u = -reach; u <= reach; u += FLOOR_STEP) {
                    for (float v = -reach; v <= reach; v += FLOOR_STEP) {
                        float d = (float) Math.sqrt(u * u + v * v);
                        if (d > reach || d >= nearest) continue;
                        double[] q = unit(p[0] + (a[0] * u + along[0] * v) / radius, p[1] + (a[1] * u + along[1] * v) / radius,
                                p[2] + (a[2] * u + along[2] * v) / radius);
                        if (l.depthAt(q, radius) > 0f) nearest = d;
                    }
                }
                if (nearest == Float.MAX_VALUE) continue;
                if (r.floor[k] <= NO_FLOOR || nearest < r.floorReach[k]) {
                    r.floor[k] = l.level;
                    r.floorReach[k] = nearest;
                }
            }
        }
    }

    /**
     * Whether the way straight from a to b (directions from the planet's centre, not far apart)
     * crosses a river or runs over a lake: {the widest river's water it crosses (world units
     * across), how squarely it crosses the rivers (the largest |cos| of the angle between the
     * way and a river it crosses: 0 straight across, 1 along it), how far it runs over lakes
     * (world units), how far it runs in rivers' water}; null if it does none of these.
     */
    public float[] crossing(GeodesicChunkGrid.Direction a, GeodesicChunkGrid.Direction b) {
        double[] p = unit(a.x(), a.y(), a.z()), q = unit(b.x(), b.y(), b.z());
        double length = chord(p, q) * radius;
        if (length < 1e-6) return null;
        // A flat frame at p: the way to q, and square to it
        double[] along = unit(q[0] - p[0], q[1] - p[1], q[2] - p[2]);
        double[] side = { p[1] * along[2] - p[2] * along[1], p[2] * along[0] - p[0] * along[2], p[0] * along[1] - p[1] * along[0] };
        float widest = 0f, squareness = 0f, overLakes = 0f, inRivers = 0f;
        boolean river = false;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        // (the pieces near the way, in the frame: {ax, az, bx, bz, half width at a, at b})
        List<double[]> near = new ArrayList<>();
        int steps = Math.max(1, (int) Math.ceil(length / (texelSize() * 0.5)));
        for (int s = 0; s <= steps; s++) {
            double f = (double) s / steps;
            double[] d = unit(p[0] + (q[0] - p[0]) * f, p[1] + (q[1] - p[1]) * f, p[2] + (q[2] - p[2]) * f);
            int t = raster.index(d[0], d[1], d[2]);
            // (over a lake's water)
            int lake = lakeOf[t];
            if (lake >= 0 && lakes.get(lake).depthAt(d, radius) > 0f && s < steps) overLakes += (float) (length / steps);
            for (int i = cellStart[t]; i < cellStart[t + 1]; i++) {
                int piece = cellPieces[i];
                if (!seen.add(piece)) continue;
                int o = piece * SEGMENT;
                // The piece's ends in the frame (world units from p)
                double ax = 0, az = 0, bx = 0, bz = 0;
                for (int e = 0; e < 2; e++) {
                    double wx = segments[o + e * 3] / radius - p[0], wy = segments[o + e * 3 + 1] / radius - p[1], wz = segments[o + e * 3 + 2] / radius - p[2];
                    double x = (wx * along[0] + wy * along[1] + wz * along[2]) * radius, z = (wx * side[0] + wy * side[1] + wz * side[2]) * radius;
                    if (e == 0) { ax = x; az = z; } else { bx = x; bz = z; }
                }
                // Where the way (z = 0, x from 0 to length) meets the piece
                near.add(new double[] { ax, az, bx, bz, segments[o + 19], segments[o + 20] });
                if ((az > 0) == (bz > 0) || Math.abs(bz - az) < 1e-9) continue;
                double u = az / (az - bz);
                double x = ax + (bx - ax) * u;
                if (x < 0 || x > length) continue;
                float half = segments[o + 19] + (segments[o + 20] - segments[o + 19]) * (float) u;
                double pieceLength = Math.hypot(bx - ax, bz - az);
                if (pieceLength < 1e-9) continue;
                river = true;
                widest = Math.max(widest, 2f * half);
                squareness = Math.max(squareness, (float) Math.abs((bx - ax) / pieceLength));
            }
        }
        // How far along it is in a river's water (within a piece's half width of its line)
        int looks = Math.max(1, (int) Math.ceil(length / IN_RIVER_STEP));
        for (int k = 0; k < looks; k++) {
            double x = (k + 0.5) * length / looks;
            for (double[] piece : near) {
                double ux = piece[2] - piece[0], uz = piece[3] - piece[1], l2 = ux * ux + uz * uz;
                double f = l2 > 0 ? Math.max(0, Math.min(1, ((x - piece[0]) * ux + (0 - piece[1]) * uz) / l2)) : 0;
                double dx = x - piece[0] - ux * f, dz = -piece[1] - uz * f;
                if (dx * dx + dz * dz <= Math.pow(piece[4] + (piece[5] - piece[4]) * f, 2)) {
                    inRivers += (float) (length / looks);
                    break;
                }
            }
        }
        if (!river && overLakes <= 0f && inRivers <= 0f) return null;
        return new float[] { widest, squareness, overLakes, inRivers };
    }

    /**
     * The height of the fresh water's surface (a river's or a lake's) at a direction from the
     * planet's centre, where the ground there is groundHeight; NaN if it's not under fresh water.
     */
    /** Whether any channel reaches the texel a place is in (with its banks: much wider than the water), a quick first look. */
    public boolean channelsAbout(GeodesicChunkGrid.Direction direction) {
        double x = direction.x(), y = direction.y(), z = direction.z();
        double l = Math.sqrt(x * x + y * y + z * z);
        int t = raster.index((float) (x / l * radius), (float) (y / l * radius), (float) (z / l * radius));
        return cellStart[t + 1] > cellStart[t];
    }

    /**
     * Whether a place on the datum is within margin (world units) of a river's channel (its
     * trough, the water's bed and the banks just over it), whatever the ground's height there.
     */
    public boolean channelNear(GeodesicChunkGrid.Direction direction, float margin) {
        double x = direction.x(), y = direction.y(), z = direction.z();
        double l = Math.sqrt(x * x + y * y + z * z);
        float px = (float) (x / l * radius), py = (float) (y / l * radius), pz = (float) (z / l * radius);
        int t = raster.index(px, py, pz);
        for (int i = cellStart[t]; i < cellStart[t + 1]; i++) {
            int o = cellPieces[i] * SEGMENT;
            float[] s = segments;
            float ax = s[o], ay = s[o + 1], az = s[o + 2];
            float ux = s[o + 3] - ax, uy = s[o + 4] - ay, uz = s[o + 5] - az;
            float length2 = ux * ux + uy * uy + uz * uz;
            float f = length2 > 0f ? ((px - ax) * ux + (py - ay) * uy + (pz - az) * uz) / length2 : 0f;
            f = Math.max(0f, Math.min(1f, f));
            float dx = px - (ax + ux * f), dy = py - (ay + uy * f), dz = pz - (az + uz * f);
            float trough = s[o + 8] + (s[o + 9] - s[o + 8]) * f;
            if (dx * dx + dy * dy + dz * dz < (trough + margin) * (trough + margin)) return true;
        }
        return false;
    }

    public float waterAt(GeodesicChunkGrid.Direction direction, float groundHeight) {
        double x = direction.x(), y = direction.y(), z = direction.z();
        double l = Math.sqrt(x * x + y * y + z * z);
        float px = (float) (x / l * radius), py = (float) (y / l * radius), pz = (float) (z / l * radius);
        int t = raster.index(px, py, pz);
        float water = Float.NaN;
        // (in a lake where its own water is, as its grid has it, not just anywhere in its texels below its level)
        int lake = lakeOf[t];
        if (lake >= 0 && groundHeight < lakes.get(lake).level && lakes.get(lake).depthAt(new double[] { x / l, y / l, z / l }, radius) > 0f) {
            water = lakes.get(lake).level;
        }
        for (int i = cellStart[t]; i < cellStart[t + 1]; i++) {
            int o = cellPieces[i] * SEGMENT;
            float[] s = segments;
            float ax = s[o], ay = s[o + 1], az = s[o + 2];
            float ux = s[o + 3] - ax, uy = s[o + 4] - ay, uz = s[o + 5] - az;
            float length2 = ux * ux + uy * uy + uz * uz;
            float f = length2 > 0f ? ((px - ax) * ux + (py - ay) * uy + (pz - az) * uz) / length2 : 0f;
            f = Math.max(0f, Math.min(1f, f));
            float dx = px - (ax + ux * f), dy = py - (ay + uy * f), dz = pz - (az + uz * f);
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float surface = s[o + 12] + (s[o + 13] - s[o + 12]) * f;
            if (distance > s[o + 8] || groundHeight >= surface) continue;
            // (and no lower than its own bed there: not ground far below it, under a fall's lip)
            float trough = s[o + 8] + (s[o + 9] - s[o + 8]) * f;
            float bedHeight = s[o + 6] + (s[o + 7] - s[o + 6]) * f - (s[o + 10] + (s[o + 11] - s[o + 10]) * f) * bed(distance / trough);
            if (groundHeight < bedHeight - BED_SLACK) continue;
            if (Float.isNaN(water) || surface > water) water = surface;
        }
        return water;
    }

    /** A binary heap of texels by height, lowest first. */
    private static final class Heap {
        private float[] keys;
        private int[] values;
        private int count;

        Heap(int capacity) {
            keys = new float[Math.max(16, capacity)];
            values = new int[keys.length];
        }

        int size() {
            return count;
        }

        void push(float key, int value) {
            if (count == keys.length) {
                keys = Arrays.copyOf(keys, count * 2);
                values = Arrays.copyOf(values, count * 2);
            }
            int i = count++;
            while (i > 0) {
                int up = (i - 1) >> 1;
                if (keys[up] <= key) break;
                keys[i] = keys[up];
                values[i] = values[up];
                i = up;
            }
            keys[i] = key;
            values[i] = value;
        }

        int pop() {
            int top = values[0];
            float key = keys[--count];
            int value = values[count];
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= count) break;
                if (child + 1 < count && keys[child + 1] < keys[child]) child++;
                if (keys[child] >= key) break;
                keys[i] = keys[child];
                values[i] = values[child];
                i = child;
            }
            keys[i] = key;
            values[i] = value;
            return top;
        }
    }
}
