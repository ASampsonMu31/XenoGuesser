import java.util.List;
import java.util.Random;
import com.xenoguesser.math.Vector3;

/**
 * The plants that aren't branching trees (see Flora.Design.Form): giant mushrooms, cacti, orb
 * trees and palms, and the strands that hang from weeping trees and vines. Each is built of
 * Flora's parts (swept and turned surfaces, orbs, spikes and leaves), drawn from the plant's
 * stream of numbers the same way at every level of detail, so only how finely it's built
 * differs between them.
 */
final class FloraForms {

    private FloraForms() {}

    private static final float[] UP = { 0f, 1f, 0f };

    /**
     * Rings along a part: the same at every level of detail (only the points round it are fewer
     * further off), so its shape, and its markings' place on it, don't change as the player
     * comes near.
     */
    private static int rings(Flora.Geometry g, int near) {
        return near;
    }

    /** A direction out round the plant: so far round (radians), climbing so steeply (radians). */
    private static float[] heading(float round, float climb) {
        float c = (float) Math.cos(climb);
        return new float[] { (float) Math.cos(round) * c, (float) Math.sin(climb), (float) Math.sin(round) * c };
    }

    // ---- Mushrooms ----

    /** A giant mushroom, or (a shrub) a cluster of small ones. */
    static void mushrooms(Flora.Geometry g, Random rand, Flora.Design d, int slices) {
        if (!d.shrub) {
            mushroom(g, rand, d, slices, new float[] { 0f, 0f, 0f }, d.height * (0.85f + rand.nextFloat() * 0.3f), d.width);
            return;
        }
        int count = 3 + rand.nextInt(5);
        for (int k = 0; k < count; k++) {
            float round = rand.nextFloat() * 6.2832f, out = k == 0 ? 0f : d.height * (0.45f + rand.nextFloat() * 0.6f);
            float h = d.height * (k == 0 ? 1f : 0.3f + rand.nextFloat() * 0.5f);
            float[] at = { (float) Math.cos(round) * out, 0f, (float) Math.sin(round) * out };
            mushroom(g, rand, d, slices, at, h, d.width * h / d.height);
        }
    }

    private static void mushroom(Flora.Geometry g, Random rand, Flora.Design d, int slices, float[] base, float h, float width) {
        // The stalk: leaning a little and curving as it goes, swollen at its foot
        float lean = rand.nextFloat() * 0.35f, leanRound = rand.nextFloat() * 6.2832f, bulb = 1.2f + rand.nextFloat() * 0.7f;
        float[] leanWay = { (float) Math.cos(leanRound), 0f, (float) Math.sin(leanRound) };
        float r = width * 0.5f;
        int n = rings(g, d.shrub ? 5 : 10);
        float[][] spine = new float[n][];
        float[] radius = new float[n];
        for (int i = 0; i < n; i++) {
            float s = i / (float) (n - 1);
            spine[i] = Flora.add(Flora.add(base, Flora.scale(UP, h * s)), Flora.scale(leanWay, h * lean * (float) Math.pow(s, 1.6)));
            float foot = 1f - s;
            radius[i] = r * (1f + (bulb - 1f) * foot * foot * foot * foot) * (1f - 0.2f * s);
        }
        Flora.sweep(g, spine, radius, Flora.sides(r, slices), 0, 0f, Flora.FLESH);
        float[] top = spine[n - 1];
        float[] axis = Flora.normalise(Flora.sub(spine[n - 1], spine[n - 2]));
        float[][] cap = Flora.frame(top, axis);

        // A frill hanging round the stalk, below the cap
        if (d.frill) {
            float[] at = spine[(int) ((n - 1) * 0.72f)];
            float rs = r * 0.9f;
            Flora.lathe(g, Flora.frame(at, axis), new float[] { rs, rs * 1.6f, rs * 2.2f }, new float[] { 0f, -h * 0.035f, -h * 0.08f },
                    Flora.sides(rs * 2f, slices), 0, 0f, Flora.FLESH);
        }

        // The cap: its underside (the gills) from the stalk out to the rim, then its top from the rim up and over
        float capR = h * (0.3f + rand.nextFloat() * 0.25f) * (d.capShape == 1 ? 1.25f : 1f) * (d.shrub ? 0.75f : 1f);
        float capH = capR * (d.capShape == 2 ? 1.1f + rand.nextFloat() * 0.6f : d.capShape == 1 ? 0.18f : 0.35f + rand.nextFloat() * 0.35f);
        float rimDrop = d.capShape == 3 ? -capH * 0.9f : capH * (0.15f + rand.nextFloat() * 0.25f);
        // (a small cap, one of a cluster, fewer further off)
        int around = d.shrub ? Math.max(6, Flora.sides(capR, slices) - 3 * g.lod) : Flora.sides(capR, slices);
        Flora.lathe(g, cap, new float[] { r * 0.8f, capR * 0.6f, capR * 0.97f },
                new float[] { -r * 0.1f, -rimDrop * 0.55f - capH * 0.04f, -rimDrop }, around, 0, 0f, Flora.FLESH);
        int m = rings(g, d.shrub ? 5 : 9);
        float[] cr = new float[m + 1], cy = new float[m + 1];
        cr[0] = capR * 0.97f;
        cy[0] = -rimDrop;
        for (int i = 0; i < m; i++) {
            float a = (i + 1) / (float) m;
            double angle = a * Math.PI * 0.5;
            float out = (float) Math.cos(angle), up = (float) Math.sin(angle);
            switch (d.capShape) {
                case 2 -> { out = 1f - a; up = (float) Math.pow(a, 0.8); }
                default -> { }
            }
            cr[i + 1] = capR * out;
            cy[i + 1] = -rimDrop + (capH + rimDrop) * up;
        }
        if (d.capShape == 3) {
            // (a funnel: the rim the highest, its middle dipping to a hollow over the stalk)
            for (int i = 0; i <= m; i++) {
                float a = i / (float) m;
                cr[i] = capR * 0.97f * (1f - a);
                cy[i] = capH * 0.9f - capH * 0.65f * a * a;
            }
            cy[0] = capH * 0.9f;
        }
        Flora.Surface capTop = Flora.lathe(g, cap, cr, cy, around, 0, 0f, Flora.SKIN);

        // Things on the cap: spikes standing up from it, bulbs hanging round its rim, leaves round its edge
        if (d.spikes > 0f) {
            int spikes = (int) (8 + d.spikes * 50f);
            for (int k = 0; k < spikes; k++) {
                float[][] at = capTop.at(0.15f + rand.nextFloat() * 0.75f, rand.nextFloat());
                Flora.spike(g, at[0], at[1], capR * 0.12f, capR * 0.025f);
            }
        }
        float[] rimCentre = Flora.add(top, Flora.scale(axis, -rimDrop));
        if (d.orbs > 0f) {
            int bulbs = 5 + (int) (d.orbs * 9f);
            for (int k = 0; k < bulbs; k++) {
                float t = (k + rand.nextFloat() * 0.5f) / bulbs, drop = h * (0.06f + rand.nextFloat() * 0.18f);
                float[][] rim = capTop.at(0f, t);
                float[] from = Flora.add(rim[0], Flora.scale(Flora.normalise(Flora.sub(rimCentre, rim[0])), capR * 0.05f));
                float[] to = Flora.add(from, new float[] { 0f, -drop, 0f });
                float thread = Math.max(0.06f, capR * 0.008f);
                Flora.sweep(g, new float[][] { from, Flora.add(from, new float[] { 0f, -drop * 0.5f, 0f }), to },
                        new float[] { thread, thread, thread }, 3, 0, 0f, Flora.FLESH);
                float size = capR * 0.05f * d.orbSize;
                Flora.orb(g, Flora.add(to, new float[] { 0f, -size * 0.8f, 0f }), size, Flora.orbSides(g, slices));
            }
        }
        if (d.leaves) {
            int clusters = 7 + rand.nextInt(6);
            for (int k = 0; k < clusters; k++) {
                float[][] rim = capTop.at(0f, (k + rand.nextFloat() * 0.6f) / clusters);
                float[] outward = Flora.normalise(Flora.sub(rim[0], rimCentre));
                Flora.spawnLeaves(rand, new Vector3(rim[0][0], rim[0][1], rim[0][2]),
                        Flora.pointing(Flora.add(outward, new float[] { 0f, -0.6f, 0f })), g, d.leafScale * (d.shrub ? 0.5f : 1f));
            }
        }
    }

    // ---- Cacti ----

    /** A cactus: a tall ribbed column with arms, or (a shrub) barrels or a heap of pads. */
    static void cactus(Flora.Geometry g, Random rand, Flora.Design d, int slices) {
        if (d.shrub && d.pads) {
            pads(g, rand, d, slices);
            return;
        }
        if (d.shrub) {
            int count = 1 + rand.nextInt(4);
            for (int k = 0; k < count; k++) {
                float round = rand.nextFloat() * 6.2832f, out = k == 0 ? 0f : d.width * (0.6f + rand.nextFloat() * 0.6f);
                float h = d.height * (k == 0 ? 1f : 0.45f + rand.nextFloat() * 0.45f);
                float[] at = { (float) Math.cos(round) * out, 0f, (float) Math.sin(round) * out };
                column(g, rand, d, slices, at, UP, h, d.width * 0.5f * h / d.height, 0.45f, null);
            }
            return;
        }
        float h = d.height * (0.85f + rand.nextFloat() * 0.3f), r = d.width * 0.5f;
        column(g, rand, d, slices, new float[] { 0f, 0f, 0f }, UP, h, r, 0.12f, null);
        int arms = 1 + rand.nextInt(4);
        for (int k = 0; k < arms; k++) {
            float round = (k + rand.nextFloat() * 0.6f) / arms * 6.2832f;
            float from = h * (0.25f + rand.nextFloat() * 0.35f), out = r * (1.6f + rand.nextFloat() * 1.2f);
            float up = h * (0.15f + rand.nextFloat() * 0.3f), ra = r * (0.55f + rand.nextFloat() * 0.2f);
            float[] way = { (float) Math.cos(round), 0f, (float) Math.sin(round) };
            // (out sideways, curving up through a quarter circle, then straight up)
            int n = rings(g, 6);
            float[][] elbow = new float[n][];
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 0.5 * i / (n - 1);
                elbow[i] = new float[] { way[0] * out * (float) Math.sin(a), from + out - out * (float) Math.cos(a), way[2] * out * (float) Math.sin(a) };
            }
            column(g, rand, d, slices, elbow[n - 1], UP, up, ra, 0.2f, elbow);
        }
    }

    /**
     * A ribbed column from base along way, so tall and wide (radius), domed at its top (the top
     * share of it): led up to by lead (points to its base: an arm's elbow) if any. With spikes
     * down its ribs, and orbs or leaves on its crown.
     */
    private static void column(Flora.Geometry g, Random rand, Flora.Design d, int slices, float[] base, float[] way, float h, float r, float dome, float[][] lead) {
        int body = rings(g, 8), cap = rings(g, 5);
        int ahead = lead == null ? 0 : lead.length - 1;
        float[][] spine = new float[ahead + body + cap][];
        float[] radius = new float[spine.length];
        for (int i = 0; i < ahead; i++) {
            spine[i] = lead[i];
            radius[i] = r;
        }
        float straight = h * (1f - dome), sway = rand.nextFloat() * 6.2832f;
        for (int i = 0; i < body + cap; i++) {
            float along, rr;
            if (i < body) {
                along = straight * i / (body - 0.5f);
                rr = r * (1f - 0.08f * along / h);
            } else {
                double a = Math.PI * 0.5 * (i - body + 1) / cap;
                along = straight + h * dome * (float) Math.sin(a);
                rr = r * 0.92f * (float) Math.cos(a);
            }
            float wobble = r * 0.08f * (float) Math.sin(along / h * 5f + sway);
            spine[ahead + i] = Flora.add(Flora.add(base, Flora.scale(way, along)), new float[] { wobble, 0f, wobble * 0.5f });
            radius[ahead + i] = rr;
        }
        // (always the ridges and the grooves between, so the ribs stand out at every level of detail)
        int around = Math.max(d.ribs * (g.lod == 0 ? 4 : g.lod == 1 ? 3 : 2), Flora.sides(r, slices));
        Flora.Surface s = Flora.sweep(g, spine, radius, around, d.ribs, d.ribDepth, Flora.SKIN);
        // Spikes down the ribs' ridges, in tufts (from the lead's end on: an arm's elbow is bare)
        float start = ahead / (float) (spine.length - 1);
        if (d.spikes > 0f) {
            int along = Math.max(3, (int) (h / (r * 0.9f) * 2.5f * d.spikes));
            float length = r * (0.18f + rand.nextFloat() * 0.15f);
            for (int rib = 0; rib < d.ribs; rib++) {
                for (int k = 0; k < along; k++) {
                    float sAt = start + (1f - start) * (k + 0.5f + (rand.nextFloat() - 0.5f) * 0.4f) / (along + 0.5f);
                    float[][] at = s.at(sAt, rib / (float) d.ribs);
                    float[] tilt = Flora.normalise(Flora.add(at[1], new float[] { 0f, 0.35f, 0f }));
                    Flora.spike(g, at[0], tilt, length, length * 0.12f);
                    float[] splay = Flora.normalise(Flora.add(tilt, Flora.scale(Flora.perpendicular(tilt), 0.5f)));
                    Flora.spike(g, at[0], splay, length * 0.7f, length * 0.1f);
                }
            }
        }
        float[] crown = spine[spine.length - 1];
        if (d.orbs > 0f) {
            int flowers = 1 + (int) (d.orbs * 4f);
            for (int k = 0; k < flowers; k++) {
                float[][] at = s.at(0.9f + rand.nextFloat() * 0.08f, rand.nextFloat());
                float size = r * 0.22f * d.orbSize;
                Flora.orb(g, Flora.add(at[0], Flora.scale(at[1], size * 0.6f)), size, Flora.orbSides(g, slices));
            }
        }
        if (d.leaves) {
            Flora.spawnLeaves(rand, new Vector3(crown[0], crown[1], crown[2]), Flora.pointing(way), g, d.leafScale * (d.shrub ? 0.6f : 1f));
        }
    }

    /** A heap of flat pads, each on the edge of the last, spiked all over. */
    private static void pads(Flora.Geometry g, Random rand, Flora.Design d, int slices) {
        int count = 3 + rand.nextInt(4);
        float[][] tips = new float[count][];
        float[][] ways = new float[count][];
        for (int k = 0; k < count; k++) {
            float size = d.height * (k == 0 ? 0.45f : 0.28f + rand.nextFloat() * 0.15f);
            int on = k == 0 ? -1 : rand.nextInt(k);
            float lean = (rand.nextFloat() - 0.5f) * 1.4f, round = rand.nextFloat() * 6.2832f;
            float[] base = on < 0 ? new float[] { 0f, 0f, 0f } : tips[on];
            float[] up = on < 0 ? UP : ways[on];
            float[] way = Flora.normalise(Flora.add(up, Flora.scale(new float[] { (float) Math.cos(round), 0f, (float) Math.sin(round) }, lean)));
            if (way[1] < 0.25f) way = Flora.normalise(new float[] { way[0], 0.25f, way[2] });
            float facing = rand.nextFloat() * 6.2832f;
            // A ball squashed flat (thin across its face) and long along way, its foot at base
            float[][] f = Flora.frame(base, way);
            float[] across = Flora.add(Flora.scale(f[1], (float) Math.cos(facing)), Flora.scale(f[3], (float) Math.sin(facing)));
            float[] thin = Flora.cross(across, way);
            int rings = rings(g, 8), around = Flora.sides(size * 0.5f, slices);
            float[][][] p = new float[rings][around + 1][];
            float[] v = new float[rings];
            for (int i = 0; i < rings; i++) {
                double a = Math.PI * i / (rings - 1);
                float along = size * 0.5f * (1f - (float) Math.cos(a)), wide = size * 0.32f * (float) Math.sin(a);
                for (int j = 0; j <= around; j++) {
                    double b = 2.0 * Math.PI * (j % around) / around;
                    float x = wide * (float) Math.cos(b), z = wide * 0.22f * (float) Math.sin(b);
                    p[i][j] = Flora.add(Flora.add(base, Flora.scale(way, along)), Flora.add(Flora.scale(across, x), Flora.scale(thin, z)));
                }
                v[i] = i / (float) (rings - 1) * 0.5f;
            }
            Flora.Surface s = Flora.grid(g, p, Flora.SKIN, v);
            tips[k] = Flora.add(base, Flora.scale(way, size * 0.92f));
            ways[k] = way;
            if (d.spikes > 0f) {
                int spikes = (int) (6 + 18 * d.spikes);
                for (int q = 0; q < spikes; q++) {
                    float[][] at = s.at(0.12f + rand.nextFloat() * 0.8f, rand.nextFloat());
                    Flora.spike(g, at[0], at[1], size * 0.08f, size * 0.01f);
                }
            }
            if (d.orbs > 0f && rand.nextFloat() < d.orbs) {
                float[][] at = s.at(0.97f, rand.nextFloat());
                float r = size * 0.09f * d.orbSize;
                Flora.orb(g, Flora.add(at[0], Flora.scale(way, r * 0.5f)), r, Flora.orbSides(g, slices));
            }
        }
        if (d.leaves) {
            float[] tip = tips[count - 1];
            Flora.spawnLeaves(rand, new Vector3(tip[0], tip[1], tip[2]), Flora.pointing(ways[count - 1]), g, d.leafScale * 0.5f);
        }
    }

    // ---- Orb trees ----

    /** A trunk (from the ground, for a shrub) bursting into stalks that each end in an orb. */
    static void orbs(Flora.Geometry g, Random rand, Flora.Design d, int slices) {
        float h = d.height * (0.85f + rand.nextFloat() * 0.3f);
        float trunkH = d.shrub ? h * 0.12f : h * (0.4f + rand.nextFloat() * 0.25f);
        float r = d.width * 0.5f, lean = rand.nextFloat() * 0.2f, leanRound = rand.nextFloat() * 6.2832f;
        int n = rings(g, 8);
        float[][] spine = new float[n][];
        float[] radius = new float[n];
        for (int i = 0; i < n; i++) {
            float s = i / (float) (n - 1);
            spine[i] = new float[] { (float) Math.cos(leanRound) * lean * trunkH * s * s, trunkH * s, (float) Math.sin(leanRound) * lean * trunkH * s * s };
            radius[i] = r * (1.25f - 0.45f * s);
        }
        Flora.sweep(g, spine, radius, Flora.sides(r, slices), 0, 0f, d.skin ? Flora.SKIN : Flora.BARK);
        float[] top = spine[n - 1];
        // A swollen bulb where the stalks spring from
        float bulb = r * (1.6f + rand.nextFloat() * 0.8f);
        Flora.Surface knot = Flora.lathe(g, Flora.frame(Flora.add(top, new float[] { 0f, bulb * 0.4f, 0f }), UP),
                ballRadii(rings(g, 8), bulb), ballHeights(rings(g, 8), bulb), Flora.sides(bulb, slices), 0, 0f, Flora.SKIN);
        int stalks = d.shrub ? 9 + rand.nextInt(10) : 14 + rand.nextInt(16);
        float reach = h - trunkH;
        for (int k = 0; k < stalks; k++) {
            float round = rand.nextFloat() * 6.2832f, climb = 0.2f + rand.nextFloat() * 1.2f;
            float length = reach * (0.45f + rand.nextFloat() * 0.55f), curl = 0.2f + rand.nextFloat() * 0.5f;
            float[][] at = knot.at(0.35f + rand.nextFloat() * 0.5f, round / 6.2832f);
            float[] out = heading(round, climb);
            int m = rings(g, 6);
            float[][] stalk = new float[m][];
            float[] rr = new float[m];
            float w = Math.max(0.08f, r * 0.14f);
            for (int i = 0; i < m; i++) {
                float s = i / (float) (m - 1);
                // (curling up towards its end)
                stalk[i] = Flora.add(Flora.add(at[0], Flora.scale(out, length * s)), new float[] { 0f, length * curl * s * s, 0f });
                rr[i] = w * (1f - 0.5f * s);
            }
            Flora.sweep(g, stalk, rr, 3, 0, 0f, d.skin ? Flora.SKIN : Flora.FLESH);
            float size = (d.shrub ? h * 0.06f : h * 0.035f) * d.orbSize * (0.7f + rand.nextFloat() * 0.6f);
            float[] end = stalk[m - 1];
            float[] dir = Flora.normalise(Flora.sub(stalk[m - 1], stalk[m - 2]));
            Flora.orb(g, Flora.add(end, Flora.scale(dir, size * 0.8f)), size, Flora.orbSides(g, slices));
            if (d.leaves) {
                float[] mid = stalk[m / 2];
                Flora.spawnLeaves(rand, new Vector3(mid[0], mid[1], mid[2]), Flora.pointing(dir), g, d.leafScale * (d.shrub ? 0.45f : 0.8f));
            }
            if (d.spikes > 0f && rand.nextFloat() < d.spikes) {
                float[] mid = stalk[m / 3];
                Flora.spike(g, mid, Flora.normalise(Flora.add(Flora.perpendicular(dir), dir)), size * 0.9f, size * 0.12f);
            }
        }
    }

    private static float[] ballRadii(int rings, float r) {
        float[] out = new float[rings];
        for (int i = 0; i < rings; i++) out[i] = r * (float) Math.sin(Math.PI * i / (rings - 1));
        return out;
    }

    private static float[] ballHeights(int rings, float r) {
        float[] out = new float[rings];
        for (int i = 0; i < rings; i++) out[i] = -r * (float) Math.cos(Math.PI * i / (rings - 1)) * 0.8f;
        return out;
    }

    // ---- Palms and ferns ----

    /** A tall curved trunk ringed with scars, crowned with drooping fronds (or, a fern, fronds from the ground). */
    static void palm(Flora.Geometry g, Random rand, Flora.Design d, int slices) {
        float h = d.height * (0.85f + rand.nextFloat() * 0.3f);
        float[] crown = { 0f, 0f, 0f };
        float frondLength;
        if (!d.shrub) {
            float r = d.width * 0.5f, lean = 0.1f + rand.nextFloat() * 0.3f, leanRound = rand.nextFloat() * 6.2832f;
            int n = rings(g, 12);
            float[][] spine = new float[n][];
            float[] radius = new float[n];
            for (int i = 0; i < n; i++) {
                float s = i / (float) (n - 1);
                // (bowing out and then back up)
                float bow = lean * h * (float) Math.sin(s * Math.PI * 0.75);
                spine[i] = new float[] { (float) Math.cos(leanRound) * bow, h * 0.88f * s, (float) Math.sin(leanRound) * bow };
                radius[i] = r * (1.3f - 0.45f * s + 0.25f * (1f - s) * (1f - s) * (1f - s));
            }
            Flora.sweep(g, spine, radius, Flora.sides(r, slices), 0, 0f, d.skin ? Flora.SKIN : Flora.BARK);
            crown = spine[n - 1];
            frondLength = h * (0.28f + rand.nextFloat() * 0.14f);
            if (d.orbs > 0f) {
                int fruit = 3 + (int) (d.orbs * 7f);
                for (int k = 0; k < fruit; k++) {
                    float round = rand.nextFloat() * 6.2832f, size = r * 0.7f * d.orbSize;
                    float[] at = Flora.add(crown, new float[] { (float) Math.cos(round) * r * 1.1f, -size * (0.5f + rand.nextFloat()), (float) Math.sin(round) * r * 1.1f });
                    Flora.orb(g, at, size, Flora.orbSides(g, slices));
                }
            }
        } else {
            frondLength = h * (0.8f + rand.nextFloat() * 0.3f);
        }
        int fronds = d.shrub ? 6 + rand.nextInt(7) : 7 + rand.nextInt(6);
        for (int k = 0; k < fronds; k++) {
            float round = (k + rand.nextFloat() * 0.5f) / fronds * 6.2832f;
            float climb = d.shrub ? 0.7f + rand.nextFloat() * 0.6f : 0.2f + rand.nextFloat() * 0.6f;
            frond(g, rand, d, slices, crown, heading(round, climb), frondLength * (0.8f + rand.nextFloat() * 0.4f));
        }
    }

    /** One frond: a stem arching out from at along out and drooping, leaves in pairs along it. */
    private static void frond(Flora.Geometry g, Random rand, Flora.Design d, int slices, float[] at, float[] out, float length) {
        float sag = 0.5f + rand.nextFloat() * 0.6f;
        int n = rings(g, 7);
        float[][] spine = new float[n][];
        float[] radius = new float[n];
        float w = Math.max(0.06f, length * 0.012f);
        for (int i = 0; i < n; i++) {
            float s = i / (float) (n - 1);
            spine[i] = Flora.add(Flora.add(at, Flora.scale(out, length * s)), new float[] { 0f, -length * sag * s * s, 0f });
            radius[i] = w * (1f - 0.7f * s);
        }
        Flora.sweep(g, spine, radius, 3, 0, 0f, d.skin ? Flora.SKIN : Flora.BARK);
        int pairs = 7 + rand.nextInt(4);
        float leaf = length / pairs * 1.4f;
        for (int k = 0; k < pairs; k++) {
            float s = 0.15f + 0.85f * (k + 0.5f) / pairs;
            float[] p = pointOn(at, out, length, sag, s);
            float[] dir = Flora.normalise(Flora.sub(pointOn(at, out, length, sag, s + 0.02f), p));
            float[] side = Flora.normalise(Flora.cross(dir, UP));
            float size = leaf * (1.1f - 0.5f * s) * (0.85f + rand.nextFloat() * 0.3f);
            for (int e = -1; e <= 1; e += 2) {
                float[] y = Flora.normalise(Flora.add(Flora.add(Flora.scale(side, e), Flora.scale(dir, 0.6f)), new float[] { 0f, -0.35f, 0f }));
                if (g.thinning.nextFloat() >= g.leavesKept()) continue;
                float[] face = Flora.normalise(Flora.cross(y, dir));
                Flora.leafCard(g, p, y, face, size * 0.4f, size * 1.5f);
            }
        }
        float[] end = spine[n - 1];
        if (d.spikes > 0f) Flora.spike(g, end, Flora.normalise(Flora.sub(spine[n - 1], spine[n - 2])), length * 0.06f, w);
    }

    private static float[] pointOn(float[] at, float[] out, float length, float sag, float s) {
        return Flora.add(Flora.add(at, Flora.scale(out, length * s)), new float[] { 0f, -length * sag * s * s, 0f });
    }

    // ---- Strands: weeping trees' curtains, and vines ----

    /**
     * So many strands hanging from among anchors (points on the branches, in the order grown),
     * each nearly to the ground: thin, swaying a little, with leaves (or beads) along it.
     */
    static void strands(Flora.Geometry g, Random rand, Flora.Design d, List<float[]> anchors, int count, float width, int part) {
        float floor = d.stoppingDistance * 0.06f;
        for (int k = 0; k < count; k++) {
            float[] a = anchors.get(rand.nextInt(anchors.size()));
            float share = 0.45f + rand.nextFloat() * 0.5f, phase = rand.nextFloat() * 6.2832f, round = rand.nextFloat() * 6.2832f;
            int clusters = 4 + rand.nextInt(4);
            float length = (a[1] - floor) * share;
            float[] side = { (float) Math.cos(round), 0f, (float) Math.sin(round) };
            boolean beaded = d.orbs > 0f;
            // (every strand draws its numbers whether or not it's long enough to hang)
            float[] leafAt = new float[clusters];
            for (int c = 0; c < clusters; c++) leafAt[c] = 0.2f + 0.8f * (c + rand.nextFloat() * 0.8f) / clusters;
            if (length < d.stoppingDistance * 0.08f) continue;
            int n = rings(g, 8);
            float[][] spine = new float[n][];
            float[] radius = new float[n];
            for (int i = 0; i < n; i++) spine[i] = strandPoint(a, side, length, phase, i / (float) (n - 1));
            for (int i = 0; i < n; i++) radius[i] = width * (1f - 0.5f * i / (n - 1));
            Flora.sweep(g, spine, radius, 3, 0, 0f, part);
            for (int c = 0; c < clusters; c++) {
                float[] p = strandPoint(a, side, length, phase, leafAt[c]);
                if (beaded && (c % 2 == 1 || !d.leaves)) {
                    Flora.orb(g, p, width * 3.5f * d.orbSize, Math.max(5, Flora.orbSides(g, 6)));
                } else if (d.leaves) {
                    float[] hang = Flora.normalise(Flora.add(side, new float[] { 0f, -1.4f, 0f }));
                    Flora.spawnLeaves(rand, new Vector3(p[0], p[1], p[2]), Flora.pointing(hang), g, d.leafScale * 0.55f);
                }
            }
        }
    }

    private static float[] strandPoint(float[] a, float[] side, float length, float phase, float s) {
        float out = length * 0.1f * (float) Math.sin(s * Math.PI * 0.5), sway = length * 0.03f * (float) Math.sin(s * 6.2832f + phase);
        return new float[] { a[0] + side[0] * out + side[2] * sway, a[1] - length * s, a[2] + side[2] * out - side[0] * sway };
    }
}
