import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The things in the landing pod's cabin (see PodInterior), built into its mesh: the old box
 * computer on its desk at the back, a console on either side (buttons, lights, knobs, toggle
 * switches, dials and scopes, and on the left's the big lights switch) and the wheeled chair. Also where each stands, for the player to bump into (obstacles).
 *
 * In the cabin's frame: y up, the door towards +z, +x to the left as one faces the door.
 */
final class PodFurniture {

    private static final float FLOOR = PodInterior.FLOOR;

    // The desk at the back, and the computer on it
    static final float DESK_TOP = FLOOR + 9.5f, DESK_FRONT = -8f, DESK_BACK = -21f, DESK_HALF_WIDTH = 9f;
    // The consoles either side: their fronts' distance from the middle, their tops' height,
    // and how far along each reaches either way
    static final float CONSOLE_FRONT = 12.5f, CONSOLE_TOP = FLOOR + 9.5f, CONSOLE_HALF_LENGTH = 8f;
    // Each console's sloping face, as far up and back as it goes
    private static final float FACE_RISE = 4.5f;

    // The computer's screen: its middle, and its half width and height (it faces +z)
    static final float[] SCREEN_MIDDLE = { 0f, DESK_TOP + 3.6f + 4.6f, -9.95f };
    static final float SCREEN_HALF_WIDTH = 3.6f, SCREEN_HALF_HEIGHT = 2.75f;

    // The lights switch: where its lever turns on the left console's face (face coordinates),
    // and the frame of that face, so the lever can be posed (see switchPose)
    private static final float[] SWITCH_AT = { -5f, -0.3f, 0.35f };
    static final float CHAIR_RADIUS = 3.6f;

    private PodFurniture() {
    }

    /** Builds everything into the cabin's mesh. */
    static void build(OrganismMesh.Builder b) {
        Random rand = new Random(7);
        b.bone(PodInterior.CABIN);
        buildComputer(b);
        buildConsole(b, 1f, rand);
        buildConsole(b, -1f, rand);
        b.bone(PodInterior.CHAIR);
        buildChair(b);
        b.bone(PodInterior.CABIN).resetTransform();
    }

    /** What the player bumps into: {minX, minY, minZ, maxX, maxY, maxZ} each. */
    static List<float[]> obstacles() {
        List<float[]> boxes = new ArrayList<>();
        boxes.add(new float[] { -DESK_HALF_WIDTH, FLOOR, DESK_BACK, DESK_HALF_WIDTH, DESK_TOP, DESK_FRONT });
        // (the monitor and the keyboard, over the desk)
        boxes.add(new float[] { -5f, DESK_TOP, -17f, 5f, DESK_TOP + 13f, -9.5f });
        for (float side : new float[] { 1f, -1f }) {
            float near = side * CONSOLE_FRONT, far = side * 24f;
            boxes.add(new float[] { Math.min(near, far), FLOOR, -CONSOLE_HALF_LENGTH, Math.max(near, far), CONSOLE_TOP + FACE_RISE * 0.5f, CONSOLE_HALF_LENGTH });
        }
        return boxes;
    }

    // ==========================================
    //          THE COMPUTER
    // ==========================================

    private static void buildComputer(OrganismMesh.Builder b) {
        b.resetTransform();
        // The desk: a metal top on a cabinet
        b.part(PodInterior.PART_METAL);
        b.box(0f, DESK_TOP - 0.4f, (DESK_FRONT + DESK_BACK) * 0.5f, DESK_HALF_WIDTH * 2f, 0.8f, DESK_FRONT - DESK_BACK);
        b.part(PodInterior.PART_PANEL);
        b.box(0f, (FLOOR + DESK_TOP - 0.8f) * 0.5f, (DESK_FRONT + DESK_BACK) * 0.5f - 0.6f, DESK_HALF_WIDTH * 2f - 1f, DESK_TOP - 0.8f - FLOOR, DESK_FRONT - DESK_BACK - 1.2f);
        b.part(PodInterior.PART_METAL);
        for (float x : new float[] { -4.5f, 4.5f }) {
            b.box(x, FLOOR + 6f, DESK_FRONT - 0.5f, 7f, 0.4f, 0.3f);
        }
        // The base unit: a beige box with a floppy slot and a power light
        float baseY = DESK_TOP + 1.8f, baseZ = -14.5f;
        b.part(PodInterior.PART_BEIGE);
        b.box(0f, baseY, baseZ, 12f, 3.6f, 9f);
        b.part(PodInterior.PART_DARK);
        b.box(3f, baseY + 0.4f, baseZ + 4.52f, 3.2f, 0.35f, 0.1f);
        light(b, -4.5f, baseY - 0.6f, baseZ + 4.55f, 0.4f, PodInterior.GREEN, 0.37f, true);
        // The monitor: a deep beige case, tapering back, its screen set in a dark bezel
        float monitorY = SCREEN_MIDDLE[1], front = SCREEN_MIDDLE[2] - 0.05f;
        b.part(PodInterior.PART_BEIGE);
        b.box(0f, monitorY, front - 2.5f, 9.6f, 7.8f, 5f);
        b.box(0f, monitorY + 0.2f, front - 6f, 7f, 6f, 2.4f);
        b.box(0f, DESK_TOP + 3.6f + 0.25f, front - 3.5f, 5f, 0.5f, 4f);
        b.part(PodInterior.PART_DARK);
        b.box(0f, monitorY, front - 0.02f, SCREEN_HALF_WIDTH * 2f + 0.9f, SCREEN_HALF_HEIGHT * 2f + 0.9f, 0.1f);
        // The screen: the terminal's picture (see PodTerminal), a little forward of the bezel
        b.part(PodInterior.PART_TERMINAL);
        quad(b, new float[] { 0f, monitorY, front + 0.04f }, new float[] { 1f, 0f, 0f }, new float[] { 0f, 1f, 0f },
                SCREEN_HALF_WIDTH, SCREEN_HALF_HEIGHT, 0f, 0f, 1f, 1f);
        // A dial under the screen, and the keyboard in front
        b.part(PodInterior.PART_DARK);
        b.box(3.4f, monitorY - SCREEN_HALF_HEIGHT - 0.9f, front + 0.05f, 0.7f, 0.7f, 0.2f);
        b.part(PodInterior.PART_KEYS);
        b.transform(Affine.multiply(Affine.translation(0f, DESK_TOP + 0.45f, -11.2f), Affine.rotationX(-0.12f)));
        b.box(0f, 0f, 0f, 9.5f, 0.6f, 3.4f);
        b.resetTransform();
        // Cables down the back of the desk
        b.part(PodInterior.PART_RUBBER);
        List<float[]> cable = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            float t = i / 8f;
            cable.add(new float[] { -3f + t * 2f, DESK_TOP + 2f - t * (DESK_TOP - FLOOR - 1f), -19.6f - (float) Math.sin(t * Math.PI) * 0.8f });
        }
        PodInterior.tube(b, cable, 0.3f);
    }

    // ==========================================
    //          THE CONSOLES
    // ==========================================

    /** The frame of a console's sloping face: x across it (to the right as one faces it), y up the slope, z out of it. */
    static float[] faceFrame(float side) {
        float mid = side * (CONSOLE_FRONT + FACE_RISE * 0.5f);
        float[] origin = { mid, CONSOLE_TOP + FACE_RISE * 0.5f, 0f };
        float[] normal = Affine.normalise(new float[] { -side, 1f, 0f });
        return Affine.frame(origin, normal, new float[] { side, 1f, 0f }, 1f, 1f, 1f);
    }

    /** The frame of the panel on the wall above a console: x across, y up, z out into the cabin. */
    private static float[] upperFrame(float side) {
        float y = FLOOR + 18.5f;
        // (flat, so out from the curved wall far enough that its ends don't go into it)
        float r = PodInterior.radiusAt(y) - 0.7f - bulge(CONSOLE_HALF_LENGTH, PodInterior.radiusAt(y));
        float slope = (PodInterior.radiusAt(y + 1f) - PodInterior.radiusAt(y - 1f)) * 0.5f;
        float[] normal = Affine.normalise(new float[] { -side, slope, 0f });
        return Affine.frame(new float[] { side * r, y, 0f }, normal, new float[] { 0f, 1f, 0f }, 1f, 1f, 1f);
    }

    /** How far a curved wall of this radius falls away behind a flat thing halfWidth either side of the middle. */
    private static float bulge(float halfWidth, float radius) {
        return radius - (float) Math.sqrt(Math.max(0.0, radius * radius - halfWidth * halfWidth));
    }

    private static void buildConsole(OrganismMesh.Builder b, float side, Random rand) {
        float length = CONSOLE_HALF_LENGTH * 2f;
        // The body, from the floor up to where its face starts, back to the wall
        b.resetTransform().part(PodInterior.PART_PANEL);
        float near = CONSOLE_FRONT, far = PodInterior.radiusAt(FLOOR) - 0.5f - bulge(CONSOLE_HALF_LENGTH, PodInterior.radiusAt(FLOOR));
        b.box(side * (near + far) * 0.5f, (FLOOR + CONSOLE_TOP) * 0.5f, 0f, far - near, CONSOLE_TOP - FLOOR, length);
        // (behind the face, up to its top)
        float back = CONSOLE_FRONT + FACE_RISE, wall = PodInterior.radiusAt(CONSOLE_TOP + FACE_RISE) - 0.3f
                - bulge(CONSOLE_HALF_LENGTH, PodInterior.radiusAt(CONSOLE_TOP + FACE_RISE));
        b.box(side * (back + wall) * 0.5f, CONSOLE_TOP + FACE_RISE * 0.5f, 0f, Math.max(0.5f, wall - back), FACE_RISE, length);
        // A kick strip and the trim along its top edge
        b.part(PodInterior.PART_DARK);
        b.box(side * (near + 0.6f), FLOOR + 0.6f, 0f, 1.4f, 1.2f, length - 0.4f);
        b.part(PodInterior.PART_TRIM);
        b.box(side * (near - 0.05f), CONSOLE_TOP - 0.25f, 0f, 0.3f, 0.5f, length);

        // The sloping face
        float[] face = faceFrame(side);
        float faceLength = FACE_RISE * (float) Math.sqrt(2.0);
        b.transform(face).part(PodInterior.PART_PANEL);
        b.box(0f, 0f, -0.4f, length, faceLength + 0.4f, 0.8f);
        if (side > 0f) {
            // The lights switch: a red plate, the lever (its own bone) and a lamp to say it's on
            b.part(PodInterior.PART_DARK);
            b.box(SWITCH_AT[0], SWITCH_AT[1], 0.1f, 3.4f, 4.6f, 0.2f);
            b.part(PodInterior.PART_SWITCH);
            b.box(SWITCH_AT[0], SWITCH_AT[1], 0.2f, 2.6f, 3.8f, 0.15f);
            b.part(PodInterior.PART_METAL);
            b.box(SWITCH_AT[0], SWITCH_AT[1], 0.35f, 1.4f, 1f, 0.6f);
            light(b, SWITCH_AT[0] + 1.9f, SWITCH_AT[1] + 2.4f, 0.25f, 0.35f, PodInterior.AMBER, 0f, false);
            b.bone(PodInterior.SWITCH).transform(face).part(PodInterior.PART_METAL);
            b.transform(Affine.multiply(face, Affine.translation(SWITCH_AT[0], SWITCH_AT[1], SWITCH_AT[2])));
            OrganismParts.limb(b, PodInterior.PART_METAL, 3.6f, 0.32f, 0.28f);
            b.transform(Affine.multiply(face, Affine.translation(SWITCH_AT[0], SWITCH_AT[1], SWITCH_AT[2] + 3.6f)));
            OrganismParts.shell(b, PodInterior.PART_SWITCH, 1.4f, 1.4f, 1.4f, 0.5f, 0, 0f, 1f);
            b.bone(PodInterior.CABIN).transform(face);
        }
        // Buttons in a block, each lit in its colour
        float buttonsFrom = side > 0f ? -1.5f : -6.5f;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 5; col++) {
                button(b, buttonsFrom + col * 1.5f, 1.5f - row * 1.3f, rand.nextInt(4), 0.95f);
            }
        }
        // Toggle switches along the bottom, at odd angles
        for (int i = 0; i < 7; i++) {
            float x = (side > 0f ? -1.8f : -7f) + i * 1.2f, y = -2.5f;
            b.part(PodInterior.PART_METAL);
            b.box(x, y, 0.1f, 0.7f, 0.9f, 0.25f);
            float lean = rand.nextBoolean() ? 0.5f : -0.5f;
            b.transform(Affine.multiply(face, Affine.multiply(Affine.translation(x, y, 0.2f), Affine.rotationX(lean))));
            OrganismParts.limb(b, PodInterior.PART_METAL, 1.3f, 0.14f, 0.12f);
            b.transform(face);
        }
        // Knobs; and on the right, a big red button under a guard
        if (side > 0f) {
            knob(b, face, 6.5f, 1.5f, 0.75f, rand.nextFloat() * 6f);
            knob(b, face, 6.5f, -0.5f, 0.75f, rand.nextFloat() * 6f);
        } else {
            knob(b, face, 1.5f, 1.5f, 0.75f, rand.nextFloat() * 6f);
            knob(b, face, 1.5f, -0.6f, 0.6f, rand.nextFloat() * 6f);
            knob(b, face, 3.6f, -2.4f, 0.55f, rand.nextFloat() * 6f);
            button(b, 5.6f, 0.7f, PodInterior.RED, 2.2f);
            b.part(PodInterior.PART_METAL);
            b.box(5.6f, -0.9f, 0.6f, 2.8f, 0.25f, 1.2f);
        }
        // Lights along the top
        for (int i = 0; i < 12; i++) {
            light(b, -7.2f + i * 1.3f, faceLength * 0.5f - 0.5f, 0.15f, 0.35f, rand.nextInt(4), rand.nextFloat(), true);
        }
        b.resetTransform();

        // The panel on the wall above: two scopes side by side, each in a raised frame all round;
        // three dials in a row under them, in the middle; lights either side of the dials
        float[] upper = upperFrame(side);
        b.transform(upper).part(PodInterior.PART_PANEL);
        b.box(0f, 0f, -0.3f, length, 8.2f, 0.6f);
        b.part(PodInterior.PART_TRIM);
        b.box(0f, 4.15f, -0.2f, length, 0.3f, 0.8f);
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? -3.6f : 3.6f;
            int which = (side > 0f ? 0 : 2) + i;
            screen(b, x, 1.05f, 2.7f, 2.05f, which);
        }
        for (int i = 0; i < 3; i++) dial(b, -3.3f + i * 3.3f, -2.75f, 0.15f, 1.05f, (side > 0f ? 0 : 3) + i);
        for (int i = 0; i < 8; i++) {
            float x = (i < 4 ? -1f : 1f) * (6.1f + (i % 2) * 1.1f), y = -2.1f - ((i % 4) / 2) * 1.1f;
            light(b, x, y, 0.12f, 0.3f, rand.nextInt(4), rand.nextFloat(), true);
        }
        b.resetTransform();
    }

    /** A scope's screen on the current face, about (x, y), its half width and height, in a dark frame raised all round it. */
    private static void screen(OrganismMesh.Builder b, float x, float y, float halfWidth, float halfHeight, int which) {
        float rim = 0.4f, depth = 0.45f;
        b.part(PodInterior.PART_DARK);
        b.box(x, y, 0.05f, halfWidth * 2f + rim * 2f, halfHeight * 2f + rim * 2f, 0.1f);
        b.part(PodInterior.PART_METAL);
        b.box(x, y + halfHeight + rim * 0.5f, depth * 0.5f, halfWidth * 2f + rim * 2f, rim, depth);
        b.box(x, y - halfHeight - rim * 0.5f, depth * 0.5f, halfWidth * 2f + rim * 2f, rim, depth);
        b.box(x - halfWidth - rim * 0.5f, y, depth * 0.5f, rim, halfHeight * 2f, depth);
        b.box(x + halfWidth + rim * 0.5f, y, depth * 0.5f, rim, halfHeight * 2f, depth);
        b.part(PodInterior.PART_SCOPE);
        quad(b, new float[] { x, y, 0.12f }, new float[] { 1f, 0f, 0f }, new float[] { 0f, 1f, 0f }, halfWidth, halfHeight,
                which, 0f, which + 0.999f, 1f);
    }

    /** A button in a colour (an index into fs_pod's indicatorColours), in a dark collar, on the current face. */
    private static void button(OrganismMesh.Builder b, float x, float y, int colour, float size) {
        b.part(PodInterior.PART_DARK);
        b.box(x, y, 0.08f, size * 1.15f, size * 1.15f, 0.16f);
        b.part(PodInterior.PART_BUTTON);
        int first = b.vertexCount();
        b.box(x, y, 0.25f, size * 0.85f, size * 0.85f, 0.3f);
        for (int v = first; v < b.vertexCount(); v++) b.setUV(v, 0f, colour);
    }

    /** A small light, blinking (at its own phase) or steady, in a colour, on the current face. */
    private static void light(OrganismMesh.Builder b, float x, float y, float z, float size, int colour, float phase, boolean blinking) {
        b.part(blinking ? PodInterior.PART_LIGHT : PodInterior.PART_BUTTON);
        int first = b.vertexCount();
        b.box(x, y, z, size, size, size * 0.6f);
        for (int v = first; v < b.vertexCount(); v++) b.setUV(v, phase, colour);
    }

    /** A knob on a face: a dark cylinder with a metal cap and a white pointer, turned to angle. */
    private static void knob(OrganismMesh.Builder b, float[] face, float x, float y, float radius, float angle) {
        float[] at = Affine.multiply(face, Affine.multiply(Affine.translation(x, y, 0f), Affine.rotationZ(angle)));
        b.transform(at).part(PodInterior.PART_DARK);
        b.lathe(14, 6, (t, out) -> {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = Math.min(t, 0.8f) * 1.1f;
            // (closed over the top)
            out[3] = out[4] = radius * (t <= 0.6f ? 1f : t <= 0.8f ? 0.85f : 0.85f * (1f - t) / 0.2f);
        });
        b.part(PodInterior.PART_METAL);
        b.box(0f, radius * 0.45f, 0.9f, radius * 0.18f, radius * 0.7f, 0.08f);
        b.transform(face);
    }

    /** A round dial on the current face: a white face (fs_pod swings its needle) in a dark rim. Its texture coordinates
     * are shifted along u by twice its index, so the shader can tell the dials apart (and keep one needle across a face). */
    private static void dial(OrganismMesh.Builder b, float x, float y, float z, float radius, int index) {
        b.part(PodInterior.PART_DARK);
        b.lathe(20, 2, (t, out) -> {
            out[0] = x;
            out[1] = y;
            out[2] = z - 0.2f + t * 0.4f;
            out[3] = out[4] = radius * 1.18f;
        });
        b.part(PodInterior.PART_DIAL);
        float u0 = index * 2f;
        int centre = b.vertex(x, y, z + 0.25f, 0f, 0f, 1f, u0 + 0.5f, 0.5f), previous = -1;
        for (int k = 0; k <= 24; k++) {
            double a = k / 24.0 * Math.PI * 2;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            int v = b.vertex(x + c * radius, y + s * radius, z + 0.25f, 0f, 0f, 1f, u0 + 0.5f + 0.5f * c, 0.5f + 0.5f * s);
            if (previous >= 0) b.triangle(centre, previous, v);
            previous = v;
        }
    }

    /** A flat rectangle about a middle, across and up its half sizes, its texture coordinates from (u0, v0) to (u1, v1). */
    static void quad(OrganismMesh.Builder b, float[] middle, float[] across, float[] up, float halfWidth, float halfHeight,
                     float u0, float v0, float u1, float v1) {
        float[] n = Affine.normalise(Affine.cross(across, up));
        int[] v = new int[4];
        float[][] corners = { { -1f, -1f }, { 1f, -1f }, { 1f, 1f }, { -1f, 1f } };
        for (int i = 0; i < 4; i++) {
            float cx = corners[i][0] * halfWidth, cy = corners[i][1] * halfHeight;
            v[i] = b.vertex(middle[0] + across[0] * cx + up[0] * cy, middle[1] + across[1] * cx + up[1] * cy, middle[2] + across[2] * cx + up[2] * cy,
                    n[0], n[1], n[2], corners[i][0] < 0 ? u0 : u1, corners[i][1] < 0 ? v0 : v1);
        }
        b.triangle(v[0], v[1], v[2]);
        b.triangle(v[0], v[2], v[3]);
    }

    // ==========================================
    //          THE CHAIR
    // ==========================================

    /** The wheeled chair, about its own middle on the floor, facing +z (posed by the CHAIR bone). */
    private static void buildChair(OrganismMesh.Builder b) {
        b.resetTransform();
        float[] upright = Affine.rotationX((float) -Math.PI / 2);
        // Five legs out to casters
        for (int i = 0; i < 5; i++) {
            double a = i / 5.0 * Math.PI * 2;
            float sx = (float) Math.sin(a), sz = (float) Math.cos(a);
            PodInterior.strut(b, PodInterior.PART_METAL, new float[] { 0f, 1.5f, 0f }, new float[] { sx * 3.3f, 1.0f, sz * 3.3f }, 0.32f, 0.26f);
            b.transform(Affine.multiply(Affine.translation(sx * 3.3f, 0.45f, sz * 3.3f), Affine.rotationY((float) a + (float) Math.PI * 0.5f)));
            OrganismParts.shell(b, PodInterior.PART_RUBBER, 0.9f, 0.55f, 0.9f, 0.6f, 0, 0f, 1f);
            b.resetTransform();
        }
        // The column, the seat and its back
        b.transform(upright);
        OrganismParts.limb(b, PodInterior.PART_METAL, 5.4f, 0.45f, 0.42f);
        b.resetTransform().part(PodInterior.PART_DARK);
        b.box(0f, 6.4f, 0f, 3f, 0.6f, 3f);
        b.part(PodInterior.PART_FABRIC);
        b.box(0f, 7.1f, 0.2f, 6.2f, 1.1f, 6f);
        // (the upright to the back: its front kept just behind the back's, not level with it)
        b.part(PodInterior.PART_METAL);
        b.box(0f, 8.6f, -3.1f, 0.9f, 3.4f, 0.5f);
        b.part(PodInterior.PART_FABRIC);
        b.box(0f, 11.6f, -3.2f, 6f, 5f, 1.1f);
        // Arm rests
        b.part(PodInterior.PART_RUBBER);
        for (float x : new float[] { -3.2f, 3.2f }) {
            b.box(x, 8.6f, 0f, 0.35f, 2.4f, 0.5f);
            b.box(x, 9.9f, 0.3f, 0.8f, 0.4f, 3.6f);
        }
        b.resetTransform();
    }

    /** The chair's bone: standing at (x, z) on the floor, turned to angle (radians, +z turning towards +x), tipped by tilt. */
    static float[] chairPose(float x, float z, float angle, float tilt) {
        return Affine.multiply(Affine.translation(x, FLOOR, z), Affine.multiply(Affine.rotationY(angle), Affine.rotationX(tilt)));
    }

    /** The lights switch's bone: its lever turned on (up the face) or off (down it), part way between at 0..1 (1 off). */
    static float[] switchPose(float off) {
        float[] face = faceFrame(1f);
        // (on, it leans back up the face: not so far that it reaches the wall behind)
        float angle = -0.62f + 1.47f * off;
        float[] turn = Affine.multiply(Affine.translation(SWITCH_AT[0], SWITCH_AT[1], SWITCH_AT[2]),
                Affine.multiply(Affine.rotationX(angle), Affine.translation(-SWITCH_AT[0], -SWITCH_AT[1], -SWITCH_AT[2])));
        return Affine.multiply(face, Affine.multiply(turn, invertRigid(face)));
    }

    /** Where the lights switch is in the cabin. */
    static float[] switchPosition() {
        return Affine.transformPoint(faceFrame(1f), SWITCH_AT[0], SWITCH_AT[1], SWITCH_AT[2] + 1.5f);
    }

    /** The inverse of a rotation-and-translation. */
    static float[] invertRigid(float[] m) {
        float[] r = new float[16];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) r[col * 4 + row] = m[row * 4 + col];
        }
        for (int row = 0; row < 3; row++) {
            r[12 + row] = -(r[row] * m[12] + r[4 + row] * m[13] + r[8 + row] * m[14]);
        }
        r[15] = 1f;
        return r;
    }
}
