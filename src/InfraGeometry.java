import com.xenoguesser.math.Vector3;

/**
 * What InfrastructureManager and its parts share: the frames everything is built in (D3) and
 * how points move between them and the planet, distances in those frames, small vector maths
 * on float[3]s, the mesh primitives (boxes, prisms, rods, quads) the builders put together,
 * and the constants more than one part uses.
 */
abstract class InfraGeometry {

    /** The terrain the infrastructure stands on (set once the road network is prepared). */
    abstract PerlinNoise terrain();

    static final float ROAD_SURFACE_OFFSET = 0.6f;

    static final int ROAD_SEGMENT_SUBDIVISIONS = 8;

    static final float MAX_ROAD_HALF_WIDTH = RoadPath.RoadClass.HIGHWAY.width * 0.5f;

    /**
     * Standing at (x, z) in a frame, on the ground, looking along (lookX, lookZ) in it: the
     * place and its heading there (D10).
     */
    static Viewpoint viewpoint(SphereFrame frame, double x, double z, double lookX, double lookZ) {
        SpherePosition at = frame.toSphere(x, 0.0, z);
        double[] world = frame.worldDirection(lookX, 0.0, lookZ);
        double[] local = SphereFrame.at(at).localDirection(world[0], world[1], world[2]);
        return new Viewpoint(at, Math.atan2(local[2], local[0]));
    }

    /** A place on the planet and an altitude there. */
    record PlaceAt(SpherePosition place, float height) {
    }

    /** A place and an altitude in a segment's frame, {x, y, z}. */
    static float[] inFrame(RoadSegment segment, PlaceAt placeAndHeight) {
        double[] p = segment.frame.fromSphere(SphereMath.atAltitude(placeAndHeight.place(), 0.0));
        return new float[] { (float) p[0], placeAndHeight.height(), (float) p[2] };
    }

    /** A house-local point (y above the base) in the frame the batch is building in. */
    static float[] framePoint(House house, float[] p) {
        return new float[] { p[0], house.baseY + p[1], p[2] };
    }

    /** The level unit direction from a to b, or from the middle out through a point. */
    static float[] levelDirection(float[] from, float[] to) {
        return normalise(new float[] { to[0] - from[0], 0f, to[2] - from[2] });
    }

    /** A square rod between two world points. */
    void addRod(MeshBuilder builder, float[] a, float[] b, float half) {
        float[] axis = normalise(sub(b, a));
        float[] side = normalise(cross(axis, Math.abs(axis[1]) > 0.9f ? new float[] { 1f, 0f, 0f } : new float[] { 0f, 1f, 0f }));
        float[] up = normalise(cross(side, axis));
        float[][] corners = { add(scale3(side, half), scale3(up, half)), add(scale3(side, -half), scale3(up, half)),
                add(scale3(side, -half), scale3(up, -half)), add(scale3(side, half), scale3(up, -half)) };
        for (int k = 0; k < 4; k++) {
            float[] c0 = corners[k], c1 = corners[(k + 1) % 4];
            float[] n = normalise(add(c0, c1));
            float[][] q = { add(a, c0), add(a, c1), add(b, c1), add(b, c0) };
            int i0 = builder.addVertex(q[0][0], q[0][1], q[0][2], n[0], n[1], n[2], 0, 0);
            int i1 = builder.addVertex(q[1][0], q[1][1], q[1][2], n[0], n[1], n[2], 1, 0);
            int i2 = builder.addVertex(q[2][0], q[2][1], q[2][2], n[0], n[1], n[2], 1, 1);
            int i3 = builder.addVertex(q[3][0], q[3][1], q[3][2], n[0], n[1], n[2], 0, 1);
            builder.addTriangle(i0, i1, i2);
            builder.addTriangle(i0, i2, i3);
            builder.addTriangle(i0, i2, i1);
            builder.addTriangle(i0, i3, i2);
        }
    }

    /**
     * A frame for something fixed to a house (a sign on its wall): at a house-local point (y
     * above the house's base), with the house's axes turned so local +z faces (face x, z) in the
     * house's frame. Rigid, as the house is, so it lies flush with the wall.
     */
    static SphereFrame fixedTo(House house, float[] at, float[] face) {
        double turn = Math.atan2(face[2], face[0]) - Math.PI * 0.5;
        return house.frame.rotated(turn).movedTo(house.frame.toWorld(at[0], house.baseY + at[1], at[2]));
    }

    /** A flat four-cornered face in house-local space, wound to face along outward. */
    void localQuad(MeshBuilder builder, House house, float[] a, float[] b, float[] c, float[] d, float[] outward) {
        float[] normal = normalise(outward);
        int ia = houseVertex(builder, house, a, normal, 0f, 0f);
        int ib = houseVertex(builder, house, b, normal, 1f, 0f);
        int ic = houseVertex(builder, house, c, normal, 1f, 1f);
        int id = houseVertex(builder, house, d, normal, 0f, 1f);
        addOutwardQuad(builder, house, ia, ib, ic, id, a, b, c, normal);
    }

    /** Adds a point given above the house's base to a builder working in the house's frame (y above the datum). */
    int houseVertex(MeshBuilder builder, House house, float[] p, float[] n, float u, float v) {
        return builder.addVertex(p[0], house.baseY + p[1], p[2], n[0], n[1], n[2], u, v);
    }

    /** Two triangles for a quad, wound counter-clockwise as seen from the side its normal faces. */
    void addOutwardQuad(MeshBuilder builder, House house, int a, int b, int c, int d,
                                float[] pa, float[] pb, float[] pc, float[] outward) {
        if (dot3(cross(sub(pb, pa), sub(pc, pa)), outward) >= 0) {
            builder.addTriangle(a, b, c);
            builder.addTriangle(a, c, d);
        } else {
            builder.addTriangle(a, c, b);
            builder.addTriangle(a, d, c);
        }
    }

    void addOutwardTriangle(MeshBuilder builder, House house, int a, int b, int c,
                                    float[] pa, float[] pb, float[] pc, float[] outward) {
        if (dot3(cross(sub(pb, pa), sub(pc, pa)), outward) >= 0) {
            builder.addTriangle(a, b, c);
        } else {
            builder.addTriangle(a, c, b);
        }
    }

    static float[] sub(float[] a, float[] b) { return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] }; }

    static float[] add(float[] a, float[] b) { return new float[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] }; }

    static float[] scale3(float[] a, float s) { return new float[] { a[0] * s, a[1] * s, a[2] * s }; }

    static float[] mid(float[] a, float[] b) { return lerp(a, b, 0.5f); }

    static float[] lerp(float[] a, float[] b, float t) {
        return new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }

    static float dot3(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    static float dist(float[] a, float[] b) { float[] d = sub(a, b); return (float) Math.sqrt(dot3(d, d)); }

    static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    static float[] normalise(float[] a) {
        float len = (float) Math.sqrt(dot3(a, a));
        return len < 1e-6f ? new float[] { 0, 1, 0 } : scale3(a, 1f / len);
    }

    /** The point on the line from a to b (each {x, z}) nearest (x, z). */
    static float[] nearestOnLine(float x, float z, float[] a, float[] b) {
        float ax = a[0], az = a[1];
        float bx = b[0] - ax, bz = b[1] - az;
        float lengthSquared = bx * bx + bz * bz;
        float t = lengthSquared < 1e-6f ? 0f : Math.max(0f, Math.min(1f, ((x - ax) * bx + (z - az) * bz) / lengthSquared));
        return new float[] { ax + bx * t, az + bz * t };
    }

    /** Evenly spaced thin uprights (pickets, bars, spikes) between two ground points. */
    void uprights(MeshBuilder builder, float[] a, float[] b, float dirX, float dirZ,
                          float spacing, float halfWidth, float bottom, float height) {
        float pieceLength = (float) Math.hypot(b[0] - a[0], b[2] - a[2]);
        int count = Math.max(1, Math.round(pieceLength / spacing));
        for (int p = 0; p < count; p++) {
            float t = (p + 0.5f) / count;
            float x = a[0] + (b[0] - a[0]) * t;
            float z = a[2] + (b[2] - a[2]) * t;
            float ground = a[1] + (b[1] - a[1]) * t;
            addBox(builder, x, ground + bottom, z, dirX, dirZ, halfWidth * 1.4f, halfWidth, height - bottom);
        }
    }

    /** A sloped slab between two ground points, from bottom to top above the ground at each end. */
    void addPrism(MeshBuilder builder, float[] a, float[] b, float thickness, float bottom, float top) {
        float dx = b[0] - a[0];
        float dz = b[2] - a[2];
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-3f) {
            return;
        }
        float sideX = -dz / length * thickness * 0.5f;
        float sideZ = dx / length * thickness * 0.5f;
        float[] a0 = { a[0] - sideX, a[1] + bottom, a[2] - sideZ };
        float[] a1 = { a[0] + sideX, a[1] + bottom, a[2] + sideZ };
        float[] a2 = { a[0] + sideX, a[1] + top, a[2] + sideZ };
        float[] a3 = { a[0] - sideX, a[1] + top, a[2] - sideZ };
        float[] b0 = { b[0] - sideX, b[1] + bottom, b[2] - sideZ };
        float[] b1 = { b[0] + sideX, b[1] + bottom, b[2] + sideZ };
        float[] b2 = { b[0] + sideX, b[1] + top, b[2] + sideZ };
        float[] b3 = { b[0] - sideX, b[1] + top, b[2] - sideZ };
        float[] centre = { (a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f + (bottom + top) * 0.5f, (a[2] + b[2]) * 0.5f };
        builder.addConvexFace(centre, a0, b0, b3, a3);
        builder.addConvexFace(centre, a1, b1, b2, a2);
        builder.addConvexFace(centre, a3, b3, b2, a2);
        builder.addConvexFace(centre, a0, a1, a2, a3);
        builder.addConvexFace(centre, b0, b1, b2, b3);
    }

    /** An upright box standing at a point, aligned with the given direction. */
    void addBox(MeshBuilder builder, float x, float y, float z, float dirX, float dirZ,
                        float halfAlong, float halfAcross, float height) {
        float sideX = -dirZ;
        float sideZ = dirX;
        float[][] base = new float[4][];
        float[][] top = new float[4][];
        float[][] signs = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
        for (int i = 0; i < 4; i++) {
            float px = x + dirX * halfAlong * signs[i][0] + sideX * halfAcross * signs[i][1];
            float pz = z + dirZ * halfAlong * signs[i][0] + sideZ * halfAcross * signs[i][1];
            base[i] = new float[] { px, y, pz };
            top[i] = new float[] { px, y + height, pz };
        }
        float[] centre = { x, y + height * 0.5f, z };
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            builder.addConvexFace(centre, base[i], base[next], top[next], top[i]);
        }
        builder.addConvexFace(centre, top[0], top[1], top[2], top[3]);
    }

    float distanceSquaredToSegment(float x, float z, Vector3 start, Vector3 end) {
        float dx = end.x - start.x;
        float dz = end.z - start.z;
        float lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0.0f) {
            float pointDx = x - start.x;
            float pointDz = z - start.z;
            return pointDx * pointDx + pointDz * pointDz;
        }

        float projection = ((x - start.x) * dx + (z - start.z) * dz) / lengthSquared;
        projection = Math.max(0.0f, Math.min(1.0f, projection));
        float closestX = start.x + projection * dx;
        float closestZ = start.z + projection * dz;
        float distanceX = x - closestX;
        float distanceZ = z - closestZ;
        return distanceX * distanceX + distanceZ * distanceZ;
    }

    /** The level direction from one place to another in a frame, as a unit {x, 0, z}. */
    static Vector3 roadTangent(SphereFrame frame, SpherePosition fromPlace, SpherePosition toPlace) {
        Vector3 from = inFrame(frame, fromPlace);
        Vector3 to = inFrame(frame, toPlace);
        float dx = to.x - from.x;
        float dz = to.z - from.z;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-4f) {
            return new Vector3(1.0f, 0.0f, 0.0f);
        }
        return new Vector3(dx / length, 0.0f, dz / length);
    }

    /** Squared distance between segment AB and the axis-aligned box [-hx, hx] x [-hz, hz]. */
    static float segmentToBoxDistanceSquared(float ax, float az, float bx, float bz, float hx, float hz) {
        if (segmentIntersectsBox(ax, az, bx, bz, hx, hz)) {
            return 0.0f;
        }
        float best = Math.min(pointToBoxDistanceSquared(ax, az, hx, hz), pointToBoxDistanceSquared(bx, bz, hx, hz));
        best = Math.min(best, pointToSegmentDistanceSquared(-hx, -hz, ax, az, bx, bz));
        best = Math.min(best, pointToSegmentDistanceSquared(hx, -hz, ax, az, bx, bz));
        best = Math.min(best, pointToSegmentDistanceSquared(hx, hz, ax, az, bx, bz));
        best = Math.min(best, pointToSegmentDistanceSquared(-hx, hz, ax, az, bx, bz));
        return best;
    }

    /** Liang-Barsky clip of the segment against the box. */
    static boolean segmentIntersectsBox(float ax, float az, float bx, float bz, float hx, float hz) {
        float dx = bx - ax;
        float dz = bz - az;
        float[] p = { -dx, dx, -dz, dz };
        float[] q = { ax + hx, hx - ax, az + hz, hz - az };
        float enter = 0.0f;
        float exit = 1.0f;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0f) {
                if (q[i] < 0.0f) {
                    return false;
                }
            } else {
                float r = q[i] / p[i];
                if (p[i] < 0.0f) {
                    enter = Math.max(enter, r);
                } else {
                    exit = Math.min(exit, r);
                }
                if (enter > exit) {
                    return false;
                }
            }
        }
        return true;
    }

    static float pointToBoxDistanceSquared(float x, float z, float hx, float hz) {
        float dx = Math.max(0.0f, Math.abs(x) - hx);
        float dz = Math.max(0.0f, Math.abs(z) - hz);
        return dx * dx + dz * dz;
    }

    static float pointToSegmentDistanceSquared(float x, float z, float ax, float az, float bx, float bz) {
        float dx = bx - ax;
        float dz = bz - az;
        float lengthSquared = dx * dx + dz * dz;
        float t = lengthSquared > 0.0f ? ((x - ax) * dx + (z - az) * dz) / lengthSquared : 0.0f;
        t = Math.max(0.0f, Math.min(1.0f, t));
        float offsetX = x - (ax + dx * t);
        float offsetZ = z - (az + dz * t);
        return offsetX * offsetX + offsetZ * offsetZ;
    }

    /** A place in a frame as {x, height above the datum, z}, x and z along the ground (see SphereFrame.fromSphere). */
    static Vector3 inFrame(SphereFrame frame, SpherePosition place) {
        double[] local = frame.fromSphere(place);
        return new Vector3((float) local[0], (float) local[1], (float) local[2]);
    }

    /** A point in a house's frame (y its height above the datum) on the planet. */
    static SpherePosition world(House house, float x, float y, float z) {
        return house.frame.toWorld(x, y, z);
    }

    /** Where a place is in a house's frame, {x, z} (its height left out). */
    static float[] local(House house, SpherePosition place) {
        double[] local = house.frame.fromSphere(place);
        return new float[] { (float) local[0], (float) local[2] };
    }

    /** A point in a road segment's frame, on the datum. */
    static SpherePosition world(RoadSegment segment, float x, float z) {
        return segment.frame.toSphere(x, 0.0, z);
    }

    /** The ground's height at a point in a house's frame. */
    float groundAt(House house, float x, float z) {
        return TerrainMesh.getLayeredHeight(world(house, x, 0f, z), terrain());
    }

    /** The ground's height at a point in a road segment's frame. */
    float groundAt(RoadSegment segment, float x, float z) {
        return TerrainMesh.getLayeredHeight(world(segment, x, z), terrain());
    }

    /** Squared distance along the ground from a place to a segment's centreline, worked in the segment's frame. */
    static float distanceSquaredToSegment(SpherePosition place, RoadSegment segment) {
        double[] p = segment.frame.fromSphere(place);
        return pointToSegmentDistanceSquared((float) p[0], (float) p[2], segment.start.x, segment.start.z, segment.end.x, segment.end.z);
    }

    /**
     * Gives a house its frame: at a point in a road segment's frame, its door side facing
     * (faceX, faceZ) there, that heading carried along the ground to the house.
     */
    static void placeHouse(House house, RoadSegment segment, float x, float z, float faceX, float faceZ) {
        SpherePosition at = world(segment, x, z);
        double facing = SphereMath.carryHeading(segment.frame.origin(), at, Math.atan2(faceZ, faceX));
        // (turned so local +z, the door side, points along facing)
        house.frame = SphereFrame.at(at, facing - Math.PI * 0.5);
    }

    static MeshBuilder.Placement inWorld(House house) {
        return inWorld(house.frame, false);
    }

    static MeshBuilder.Placement inWorld(RoadSegment segment) {
        return inWorld(segment.frame, true);
    }

    // What a batch holds between pieces: anything built then would have no frame to be in
    static final MeshBuilder.Placement OUTSIDE_FRAME = (position, normal) -> {
        throw new IllegalStateException("Infrastructure built outside a frame");
    };

    /**
     * Places what is built in a frame in the world (D3): rigidly for a house or a booth, or
     * along the ground for a road or fence (x and z follow the planet's curve, y is the height
     * above the datum). Normals turn with the frame.
     */
    static MeshBuilder.Placement inWorld(SphereFrame frame, boolean alongGround) {
        return (position, normal) -> {
            SpherePosition at = alongGround ? frame.toSphere(position[0], position[1], position[2])
                    : frame.toWorld(position[0], position[1], position[2]);
            position[0] = (float) at.x();
            position[1] = (float) at.y();
            position[2] = (float) at.z();
            double[] world = frame.worldDirection(normal[0], normal[1], normal[2]);
            double length = Math.sqrt(world[0] * world[0] + world[1] * world[1] + world[2] * world[2]);
            if (length > 1e-12) {
                normal[0] = (float) (world[0] / length);
                normal[1] = (float) (world[1] / length);
                normal[2] = (float) (world[2] / length);
            }
        };
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1];
    }

    static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    Vector3 lerpTangent(Vector3 first, Vector3 second, float amount) {
        float x = first.x + (second.x - first.x) * amount;
        float z = first.z + (second.z - first.z) * amount;
        float length = (float) Math.sqrt(x * x + z * z);
        if (length < 1e-4f) {
            return second;
        }
        return new Vector3(x / length, 0.0f, z / length);
    }

    float[] miterOffset(Vector3 incoming, Vector3 outgoing, float halfWidth) {
        float incomingSideX = -incoming.z;
        float incomingSideZ = incoming.x;
        float outgoingSideX = -outgoing.z;
        float outgoingSideZ = outgoing.x;
        float miterX = incomingSideX + outgoingSideX;
        float miterZ = incomingSideZ + outgoingSideZ;
        float miterLength = (float) Math.sqrt(miterX * miterX + miterZ * miterZ);

        if (miterLength < 0.001f) {
            return new float[] { outgoingSideX * halfWidth, outgoingSideZ * halfWidth };
        }

        miterX /= miterLength;
        miterZ /= miterLength;
        float scale = halfWidth / Math.max(0.35f, miterX * outgoingSideX + miterZ * outgoingSideZ);
        return new float[] { miterX * scale, miterZ * scale };
    }

    /**
     * How well off the people at a point are: the nation's richness, more so in town, and far
     * less near a fenced border (see borderBlight), on both sides of it.
     */
    // A city counts as big from this size to fully big at this (1 the planet's largest), and the
    // biggest are this much richer than the nation's usual towns
    static final float BIG_CITY_FROM = 0.3f, BIG_CITY_TO = 0.75f, BIG_CITY_WEALTH = 0.8f;

    /**
     * Generators for every nation's wall, roof and fence textures, keyed by texture name,
     * for WorldArtGenerator to run alongside the world's other textures.
     */
    // The curtains' pictures, one for each of this world's curtain colours (then 0 to 3)
    static final String CURTAIN_TEXTURE = "curtains_";

    // A driveway beside the house: how wide, and how far it keeps from the walls
    static final float DRIVEWAY_WIDTH = 22.0f;

    // Fence outlines: the plain plot, an irregular outline, the front boundary only
    static final int FENCE_PLOT = 0, FENCE_IRREGULAR = 1, FENCE_FRONT_ONLY = 2;

    static final float FRAME_OFFSET = 0.2f;

    static final float FRONT_FENCE_GAP = 3.0f;

    static final float GATE_HALF_WIDTH = 7.0f;

    static final String LANDMARK_PART = "landmark:";

    // Lookups by place on the sphere (D5): segments filed at their middle reaching half their
    // length; houses at their middle reaching the far corner of their plot (once the plots are
    // final); driveways and shops at their house's middle
    static final int LOOKUP_LEVEL = 7;

    static final float RAIL_OFFSET = 3.0f;

    /** A point on the wall's face pushed straight in (or out, if depth is negative) from it. */
    static float[] inset(HouseWall wall, float s, float y, float depth) {
        return sub(wallPoint(wall, s, y), scale3(wall.normal, depth));
    }

    /** A point on a wall's face: s along it from A to B (0..1) at height y, house-local. */
    static float[] wallPoint(HouseWall wall, float s, float y) {
        float[] bottom = lerp(wall.bottomA, wall.bottomB, s);
        float[] top = lerp(wall.topA, wall.topB, s);
        float[] p = lerp(bottom, top, Math.max(0f, Math.min(1f, y / wall.topA[1])));
        p[1] = y;
        // (a shaped wall's surface stands out or in from there: see reliefAt)
        float relief = reliefAt(wall, s, y);
        if (relief != 0f) {
            float[] out = normalise(lerp(wall.cornerA, wall.cornerB, s));
            p[0] += out[0] * relief;
            p[2] += out[2] * relief;
        }
        return p;
    }

    /**
     * The footprint outline in house-local XZ, counter-clockwise seen from above, fitted
     * to a width by depth rectangle centred on (offsetX, offsetZ). Non-box footprints are
     * turned so one edge faces straight down local +Z, where the door goes.
     */
    static float[][] footprint(BuildingStyle style, float width, float depth, float offsetX, float offsetZ) {
        float hw = width * 0.5f, hd = depth * 0.5f;
        if (style.footprint == BuildingStyle.Footprint.BOX) {
            return new float[][] { { offsetX - hw, offsetZ + hd }, { offsetX + hw, offsetZ + hd },
                                   { offsetX + hw, offsetZ - hd }, { offsetX - hw, offsetZ - hd } };
        }
        int n = style.sides;
        float[][] unit = new float[n][];
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            // Clockwise in (x, z) here equals counter-clockwise seen from above (+Y), as the box is listed
            double angle = Math.PI / 2 + Math.PI / n - 2 * Math.PI * i / n;
            unit[i] = new float[] { (float) Math.cos(angle), (float) Math.sin(angle) };
            minX = Math.min(minX, unit[i][0]); maxX = Math.max(maxX, unit[i][0]);
            minZ = Math.min(minZ, unit[i][1]); maxZ = Math.max(maxZ, unit[i][1]);
        }
        float[][] points = new float[n][];
        for (int i = 0; i < n; i++) {
            float x = (unit[i][0] - (minX + maxX) * 0.5f) / ((maxX - minX) * 0.5f) * hw;
            float z = (unit[i][1] - (minZ + maxZ) * 0.5f) / ((maxZ - minZ) * 0.5f) * hd;
            points[i] = new float[] { offsetX + x, offsetZ + z };
        }
        return points;
    }

    static float[] horizontal(float[] v) {
        return normalise(new float[] { v[0], 0f, v[2] });
    }

    /** A point t of the way along an edge's subdivisions. */
    static float[] edgePoint(float[][] edge, float t) {
        float scaled = Math.max(0f, Math.min(1f, t)) * (edge.length - 1);
        int i = Math.min((int) Math.floor(scaled), edge.length - 2);
        float f = scaled - i;
        return new float[] { edge[i][0] + (edge[i + 1][0] - edge[i][0]) * f, 0f, edge[i][2] + (edge[i + 1][2] - edge[i][2]) * f };
    }

    /** How far a shaped wall's surface stands out from its flat face at s along it (0..1) and height y. */
    static float reliefAt(HouseWall wall, float s, float y) {
        if (wall.relief == ArchitectureStyle.Relief.NONE) return 0f;
        float height = wall.topA[1];
        float up = y - wall.reliefBase;
        if (up <= 0f || y >= height) return 0f;
        float t = wall.reliefFloor > 0f ? up / wall.reliefFloor - (float) Math.floor(up / wall.reliefFloor)
                : up / (height - wall.reliefBase);
        float d = wall.reliefDepth;
        float sin = (float) Math.sin(Math.PI * t), tent = 1f - Math.abs(2f * t - 1f);
        int ribs = Math.max(1, Math.round(wall.length / Math.max(2f, wall.reliefPitch)));
        float across = s * ribs - (float) Math.floor(s * ribs), rib = 1f - Math.abs(2f * across - 1f);
        return switch (wall.relief) {
            case PILLOW -> d * (float) Math.sin(Math.PI * s) * sin;
            case BARREL -> d * sin;
            case PINCH -> -d * 0.8f * sin;
            case CHEVRON -> d * tent;
            case FLUTED -> d * 0.6f * rib;
            case STUDDED -> {
                // (rows of studs as tall as they're wide, a whole number up the wall)
                float rise = wall.reliefFloor > 0f ? wall.reliefFloor : height - wall.reliefBase;
                int rows = Math.max(1, Math.round(rise / Math.max(2f, wall.reliefPitch)));
                float v = t * rows - (float) Math.floor(t * rows);
                yield d * 0.7f * rib * (1f - Math.abs(2f * v - 1f));
            }
            default -> 0f;
        };
    }

    /** Whether a relief's surface is creased (flat faces meeting at edges) rather than smoothly curved. */
    static boolean creased(ArchitectureStyle.Relief relief) {
        return relief == ArchitectureStyle.Relief.CHEVRON || relief == ArchitectureStyle.Relief.FLUTED
                || relief == ArchitectureStyle.Relief.STUDDED;
    }
}
