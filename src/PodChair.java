import java.util.List;

/**
 * The wheeled chair in the landing pod's cabin: pushed (E) it rolls away from whoever pushed
 * it, about a metre, slowing as its casters drag; it bumps off the walls, the furniture and the
 * players, rolls downhill as the pod leans, and hops when the pod lands.
 */
public class PodChair {

    // A push's speed, and how quickly the casters' drag slows it: v² / 2a ≈ 10 units (a metre)
    static final float PUSH_SPEED = 24.5f, DRAG = 30f;
    // How much of its speed it keeps bouncing off something
    private static final float BOUNCE = 0.35f;

    float x = -8.5f, y, z = 3.5f, angle = (float) (-Math.PI * 0.5);
    float vx, vy, vz, spin;

    /** Pushed away from (fromX, fromZ). */
    void push(float fromX, float fromZ, float strength) {
        float dx = x - fromX, dz = z - fromZ, d = (float) Math.hypot(dx, dz);
        if (d < 1e-3f) {
            dx = 1f;
            dz = 0f;
            d = 1f;
        }
        vx += dx / d * PUSH_SPEED * strength;
        vz += dz / d * PUSH_SPEED * strength;
        spin += (float) ((Math.random() - 0.5) * 4.0);
    }

    /** Sets its motion outright (from the host's PC, in multiplayer). */
    void set(float x, float z, float vx, float vz, float angle) {
        this.x = x;
        this.z = z;
        this.vx = vx;
        this.vz = vz;
        this.angle = angle;
    }

    boolean moving() {
        return Math.abs(vx) + Math.abs(vz) > 0.05f || y > 0f || Math.abs(spin) > 0.05f;
    }

    /** One step: gravity as it is felt in the pod; the boxes and round things (the players) it can hit. */
    void step(float dt, float[] gravity, List<float[]> boxes, List<float[]> round) {
        dt = Math.min(dt, 0.1f);
        // The casters roll freely, so any lean starts it rolling
        vx += gravity[0] * 0.6f * dt;
        vz += gravity[2] * 0.6f * dt;
        // Up and down: off the floor in a jolt, back down under gravity
        vy += gravity[1] * dt;
        y += vy * dt;
        if (y <= 0f) {
            y = 0f;
            vy = vy < -15f ? -vy * 0.3f : 0f;
        }
        // The drag, only while its wheels are on the floor
        float speed = (float) Math.hypot(vx, vz);
        if (y == 0f && speed > 0f) {
            float slower = Math.max(0f, speed - DRAG * dt);
            vx *= slower / speed;
            vz *= slower / speed;
        }
        spin *= Math.max(0f, 1f - 2.5f * dt);
        angle += spin * dt;
        x += vx * dt;
        z += vz * dt;
        collide(boxes, round);
    }

    private void collide(List<float[]> boxes, List<float[]> round) {
        float r = PodFurniture.CHAIR_RADIUS;
        // The walls, nearest at the top of its back
        float reach = PodInterior.radiusAt(PodInterior.FLOOR + y + 14f) - r;
        float d = (float) Math.hypot(x, z);
        if (d > reach) {
            float nx = x / d, nz = z / d;
            x = nx * reach;
            z = nz * reach;
            bounce(nx, nz);
        }
        for (float[] b : boxes) {
            if (PodInterior.FLOOR + y >= b[4]) continue;
            float cx = Math.max(b[0], Math.min(b[3], x)), cz = Math.max(b[2], Math.min(b[5], z));
            float dx = x - cx, dz = z - cz, dist = (float) Math.hypot(dx, dz);
            if (dist >= r || dist < 1e-4f) continue;
            x = cx + dx / dist * r;
            z = cz + dz / dist * r;
            bounce(-dx / dist, -dz / dist);
        }
        for (float[] c : round) {
            float dx = x - c[0], dz = z - c[1], dist = (float) Math.hypot(dx, dz), least = r + c[2];
            if (dist >= least || dist < 1e-4f) continue;
            x = c[0] + dx / dist * least;
            z = c[1] + dz / dist * least;
            bounce(-dx / dist, -dz / dist);
        }
    }

    /** Off something in the direction (nx, nz) from it, losing most of its speed that way. */
    private void bounce(float nx, float nz) {
        float into = vx * nx + vz * nz;
        if (into <= 0f) return;
        vx -= (1f + BOUNCE) * into * nx;
        vz -= (1f + BOUNCE) * into * nz;
        spin += (float) ((Math.random() - 0.5) * 2.0) * Math.min(1f, into / 10f);
    }

    /** Its bone in the cabin. */
    float[] pose() {
        return Affine.multiply(Affine.translation(0f, y, 0f), PodFurniture.chairPose(x, z, angle, 0f));
    }
}
