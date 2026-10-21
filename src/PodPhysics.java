import java.util.ArrayList;
import java.util.List;

/**
 * The player on their feet inside the landing pod: walking, jumping and looking round in the
 * pod's own frame (see PodInterior), kept off its leaning walls, its domed top and the things
 * in it. Gravity is a vector here, not just down: as the pod turns, slows and lands it leans
 * and changes, and the player slides and stumbles with it.
 */
public class PodPhysics {

    // As on the planet (see XenoGuesser_GLEventListener): eye height, the body's radius,
    // walking speed, the jump's speed and the pull of gravity
    static final float EYE_HEIGHT = 20f, RADIUS = 3f, WALK_SPEED = 40f, JUMP_SPEED = 42f, GRAVITY = 90f;
    // How quickly the feet take up the speed wanted, on the floor and in the air
    private static final float GRIP = 12f, AIR_GRIP = 1.5f;
    // From the feet up to the top of the head
    private static final float HEAD = EYE_HEIGHT + 1.5f;

    // The feet's position and velocity; which way the view faces (radians: yaw from +x towards
    // +z, as the planet's camera turns from east towards south; pitch up)
    float x, y = PodInterior.FLOOR, z;
    float vx, vy, vz;
    float yaw = (float) (Math.PI * 0.5), pitch;
    boolean onFloor = true;
    // Whether the head hit the ceiling lamp this step, going up
    boolean hitLamp;
    // Gravity as it is felt just now (see PodTimeline), and how far the feet went last step
    final float[] gravity = { 0f, -GRAVITY, 0f };
    float moved;

    // Boxes in the way, {minX, minY, minZ, maxX, maxY, maxZ}, and round things (the chair, the partner) {x, z, radius}
    private final List<float[]> boxes = new ArrayList<>();
    private final List<float[]> circles = new ArrayList<>();

    /** Stands the player at (x, z) facing yaw. */
    void place(float x, float z, float yaw) {
        this.x = x;
        this.z = z;
        this.y = PodInterior.FLOOR;
        this.yaw = yaw;
        vx = vy = vz = 0f;
    }

    void addBox(float[] box) {
        boxes.add(box);
    }

    /** The round things in the way this step (cleared each step). */
    void setCircles(List<float[]> round) {
        circles.clear();
        circles.addAll(round);
    }

    /** Turns the view: yaw and pitch in degrees, as the planet's camera takes them. */
    void look(float yawDegrees, float pitchDegrees) {
        yaw += (float) Math.toRadians(yawDegrees);
        pitch = (float) Math.max(Math.toRadians(-85), Math.min(Math.toRadians(85), pitch + Math.toRadians(pitchDegrees)));
    }

    /** The way the view faces. */
    float[] forward() {
        float h = (float) Math.cos(pitch);
        return new float[] { h * (float) Math.cos(yaw), (float) Math.sin(pitch), h * (float) Math.sin(yaw) };
    }

    float[] eye() {
        return new float[] { x, y + EYE_HEIGHT, z };
    }

    /** Whether a jump can start now. */
    boolean jump() {
        if (!onFloor) return false;
        vy = JUMP_SPEED;
        onFloor = false;
        return true;
    }

    /** Gives the body a shove (a jolt of the pod), in units a second. */
    void shove(float dx, float dy, float dz) {
        vx += dx;
        vy += dy;
        vz += dz;
        if (dy > 0f) onFloor = false;
    }

    /** One step: forward/aside are the keys held (-1, 0 or 1). */
    void step(float dt, float forwardKey, float asideKey) {
        dt = Math.min(dt, 0.1f);
        float ox = x, oz = z;
        // The speed wanted along the floor, from the keys
        float wantX = 0f, wantZ = 0f;
        if (forwardKey != 0f || asideKey != 0f) {
            float heading = yaw + (float) Math.atan2(asideKey, forwardKey);
            wantX = (float) Math.cos(heading) * WALK_SPEED;
            wantZ = (float) Math.sin(heading) * WALK_SPEED;
        }
        // Gravity pulls down and, as the pod leans, along the floor too
        vx += gravity[0] * dt;
        vz += gravity[2] * dt;
        vy += gravity[1] * dt;
        float grip = Math.min(1f, (onFloor ? GRIP : AIR_GRIP) * dt);
        // On the floor the feet hold against a lean, unless it's steep (the pull along the
        // floor more than half the pull into it)
        float along = (float) Math.hypot(gravity[0], gravity[2]), into = Math.max(0f, -gravity[1]);
        float slip = onFloor ? Math.max(0f, along - into * 0.5f) / Math.max(1f, along) : 1f;
        if (onFloor && slip < 1f) {
            vx -= gravity[0] * dt * (1f - slip);
            vz -= gravity[2] * dt * (1f - slip);
        }
        vx += (wantX - vx) * grip;
        vz += (wantZ - vz) * grip;

        x += vx * dt;
        y += vy * dt;
        z += vz * dt;
        collide();
        moved = (float) Math.hypot(x - ox, z - oz);
    }

    private void collide() {
        // The floor, or the top of something stood on
        onFloor = false;
        float floor = PodInterior.FLOOR;
        for (float[] b : boxes) {
            if (x + RADIUS * 0.5f > b[0] && x - RADIUS * 0.5f < b[3] && z + RADIUS * 0.5f > b[2] && z - RADIUS * 0.5f < b[5]
                    && y >= b[4] - 1.5f) floor = Math.max(floor, b[4]);
        }
        if (y <= floor) {
            y = floor;
            if (vy < 0f) vy = 0f;
            onFloor = true;
        }
        // The top of the dome
        hitLamp = false;
        if (y + HEAD > PodInterior.CEILING) {
            y = PodInterior.CEILING - HEAD;
            if (vy > 0f) {
                hitLamp = Math.hypot(x, z) < PodInterior.LAMP_REACH + RADIUS;
                vy = 0f;
            }
        }
        // The leaning walls: kept in from them at the feet and at the head (where they're nearest)
        float reach = Math.min(PodInterior.radiusAt(y + 2f), PodInterior.radiusAt(y + HEAD)) - RADIUS;
        float r = (float) Math.hypot(x, z);
        if (r > reach && r > 1e-4f) {
            float nx = x / r, nz = z / r;
            x = nx * reach;
            z = nz * reach;
            float out = vx * nx + vz * nz;
            if (out > 0f) {
                vx -= out * nx;
                vz -= out * nz;
            }
        }
        // Things in the way, pushed out from on the floor
        for (float[] b : boxes) {
            if (y >= b[4] - 1.5f || y + HEAD <= b[1]) continue;
            float cx = Math.max(b[0], Math.min(b[3], x)), cz = Math.max(b[2], Math.min(b[5], z));
            float dx = x - cx, dz = z - cz, d = (float) Math.hypot(dx, dz);
            if (d >= RADIUS) continue;
            if (d < 1e-4f) {
                // Inside it: out the nearest side
                float[] push = { b[0] - RADIUS - x, b[3] + RADIUS - x, b[2] - RADIUS - z, b[5] + RADIUS - z };
                int best = 0;
                for (int i = 1; i < 4; i++) if (Math.abs(push[i]) < Math.abs(push[best])) best = i;
                if (best < 2) x += push[best]; else z += push[best];
                continue;
            }
            float nx = dx / d, nz = dz / d;
            x = cx + nx * RADIUS;
            z = cz + nz * RADIUS;
            float in = vx * nx + vz * nz;
            if (in < 0f) {
                vx -= in * nx;
                vz -= in * nz;
            }
        }
        for (float[] c : circles) {
            float dx = x - c[0], dz = z - c[1], d = (float) Math.hypot(dx, dz), least = RADIUS + c[2];
            if (d >= least) continue;
            if (d < 1e-4f) {
                dx = 1f;
                dz = 0f;
                d = 1f;
            }
            x = c[0] + dx / d * least;
            z = c[1] + dz / d * least;
        }
    }
}
