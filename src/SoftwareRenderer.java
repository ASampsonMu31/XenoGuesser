import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A small CPU rasteriser for pre-rendering the loading screen's art: triangles with a depth
 * buffer, per-vertex normals and texture coordinates, a few point lights and a camera. Each
 * triangle is coloured by a Shader callback, so surfaces can have procedural detail. Pixels
 * nothing covers stay transparent. Renders at a multiple of the output size and averages
 * down for smooth edges.
 */
public class SoftwareRenderer {

    /** Colours a surface point: given its position, normal and texture coordinates, returns linear RGB. */
    @FunctionalInterface
    public interface SurfaceShader {
        float[] shade(float[] position, float[] normal, float u, float v);
    }

    private final int width, height, supersample;
    private final int bufferWidth, bufferHeight;
    private final float[] depth;
    private final float[] colour;
    private final float[] alpha;
    // Camera
    private float[] eye = { 0, 0, 0 }, forward = { 0, 0, 1 }, right = { 1, 0, 0 }, up = { 0, 1, 0 };
    private float focal;

    public SoftwareRenderer(int width, int height, int supersample) {
        this.width = width;
        this.height = height;
        this.supersample = supersample;
        this.bufferWidth = width * supersample;
        this.bufferHeight = height * supersample;
        depth = new float[bufferWidth * bufferHeight];
        colour = new float[bufferWidth * bufferHeight * 3];
        alpha = new float[bufferWidth * bufferHeight];
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
    }

    /** Places the camera at eye looking towards target, with a vertical field of view in degrees. */
    public void lookAt(float[] eye, float[] target, float verticalFovDegrees) {
        this.eye = eye;
        forward = Affine.normalise(Affine.subtract(target, eye));
        right = Affine.normalise(Affine.cross(new float[] { 0, 1, 0 }, forward));
        up = Affine.cross(forward, right);
        focal = (float) (bufferHeight * 0.5 / Math.tan(Math.toRadians(verticalFovDegrees) * 0.5));
    }

    /** Where a world point lands on the output image, as {x, y, depth}; depth below zero is behind the camera. */
    public float[] project(float[] p) {
        float[] d = Affine.subtract(p, eye);
        float z = Affine.dot(d, forward);
        float x = Affine.dot(d, right), y = Affine.dot(d, up);
        return new float[] { (bufferWidth * 0.5f + x / z * focal) / supersample, (bufferHeight * 0.5f - y / z * focal) / supersample, z };
    }

    private static final float NEAR = 0.5f;

    /** Draws a triangle; vertices are {x, y, z, nx, ny, nz, u, v}. Parts behind the camera are clipped away. */
    public void triangle(float[] a, float[] b, float[] c, SurfaceShader shader) {
        float da = depthOf(a), db = depthOf(b), dc = depthOf(c);
        if (da >= NEAR && db >= NEAR && dc >= NEAR) {
            rasterise(a, b, c, shader);
            return;
        }
        // Clip against the near plane, keeping the part in front
        List<float[]> kept = new java.util.ArrayList<>();
        float[][] verts = { a, b, c };
        float[] depths = { da, db, dc };
        for (int i = 0; i < 3; i++) {
            float[] current = verts[i], next = verts[(i + 1) % 3];
            float dCurrent = depths[i], dNext = depths[(i + 1) % 3];
            if (dCurrent >= NEAR) kept.add(current);
            if ((dCurrent >= NEAR) != (dNext >= NEAR)) {
                float t = (NEAR - dCurrent) / (dNext - dCurrent);
                float[] cut = new float[8];
                for (int k = 0; k < 8; k++) cut[k] = current[k] + (next[k] - current[k]) * t;
                kept.add(cut);
            }
        }
        for (int i = 1; i + 1 < kept.size(); i++) {
            rasterise(kept.get(0), kept.get(i), kept.get(i + 1), shader);
        }
    }

    private float depthOf(float[] v) {
        return (v[0] - eye[0]) * forward[0] + (v[1] - eye[1]) * forward[1] + (v[2] - eye[2]) * forward[2];
    }

    private void rasterise(float[] a, float[] b, float[] c, SurfaceShader shader) {
        float[][] verts = { a, b, c };
        float[][] screen = new float[3][];
        for (int i = 0; i < 3; i++) {
            float[] d = { verts[i][0] - eye[0], verts[i][1] - eye[1], verts[i][2] - eye[2] };
            float z = Affine.dot(d, forward);
            screen[i] = new float[] { bufferWidth * 0.5f + Affine.dot(d, right) / z * focal,
                    bufferHeight * 0.5f - Affine.dot(d, up) / z * focal, z };
        }
        float area = edge(screen[0], screen[1], screen[2][0], screen[2][1]);
        if (Math.abs(area) < 1e-6f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(screen[0][0], Math.min(screen[1][0], screen[2][0]))));
        int maxX = Math.min(bufferWidth - 1, (int) Math.ceil(Math.max(screen[0][0], Math.max(screen[1][0], screen[2][0]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(screen[0][1], Math.min(screen[1][1], screen[2][1]))));
        int maxY = Math.min(bufferHeight - 1, (int) Math.ceil(Math.max(screen[0][1], Math.max(screen[1][1], screen[2][1]))));
        float[] position = new float[3], normal = new float[3];
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float w0 = edge(screen[1], screen[2], px, py) / area;
                float w1 = edge(screen[2], screen[0], px, py) / area;
                float w2 = 1f - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                // Perspective-correct interpolation
                float iz = w0 / screen[0][2] + w1 / screen[1][2] + w2 / screen[2][2];
                float z = 1f / iz;
                int index = y * bufferWidth + x;
                if (z >= depth[index]) continue;
                float p0 = w0 / screen[0][2] * z, p1 = w1 / screen[1][2] * z, p2 = w2 / screen[2][2] * z;
                for (int k = 0; k < 3; k++) {
                    position[k] = a[k] * p0 + b[k] * p1 + c[k] * p2;
                    normal[k] = a[k + 3] * p0 + b[k + 3] * p1 + c[k + 3] * p2;
                }
                float u = a[6] * p0 + b[6] * p1 + c[6] * p2;
                float v = a[7] * p0 + b[7] * p1 + c[7] * p2;
                float[] rgb = shader.shade(position, Affine.normalise(normal), u, v);
                depth[index] = z;
                colour[index * 3] = rgb[0];
                colour[index * 3 + 1] = rgb[1];
                colour[index * 3 + 2] = rgb[2];
                alpha[index] = 1f;
            }
        }
    }

    /** Clears colour and alpha where the given test holds, leaving a hole (used for the window). */
    public void cutOut(java.util.function.BiPredicate<Float, Float> inside) {
        for (int y = 0; y < bufferHeight; y++) {
            for (int x = 0; x < bufferWidth; x++) {
                if (inside.test((x + 0.5f) / supersample, (y + 0.5f) / supersample)) {
                    alpha[y * bufferWidth + x] = 0f;
                    depth[y * bufferWidth + x] = Float.MAX_VALUE;
                }
            }
        }
    }

    private static float edge(float[] a, float[] b, float x, float y) {
        return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
    }

    /** The finished picture, averaged down from the supersampled buffer, with sRGB-ish gamma. */
    public BufferedImage image() {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int s = supersample;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int sy = 0; sy < s; sy++) {
                    for (int sx = 0; sx < s; sx++) {
                        int i = (y * s + sy) * bufferWidth + x * s + sx;
                        float w = alpha[i];
                        r += colour[i * 3] * w;
                        g += colour[i * 3 + 1] * w;
                        b += colour[i * 3 + 2] * w;
                        a += w;
                    }
                }
                if (a <= 0f) continue;
                float inv = 1f / a;
                int ia = Math.round(a / (s * s) * 255f);
                out.setRGB(x, y, (ia << 24) | (gamma(r * inv) << 16) | (gamma(g * inv) << 8) | gamma(b * inv));
            }
        }
        return out;
    }

    private static int gamma(float linear) {
        float mapped = linear / (1f + 0.15f * linear);   // gentle highlight roll-off
        return Math.max(0, Math.min(255, Math.round((float) Math.pow(Math.max(0f, mapped), 1 / 2.2) * 255f)));
    }

    // ==========================================
    //          GEOMETRY HELPERS
    // ==========================================

    /** A flat quad a-b-c-d (any winding), texture coordinates across it scaled to world size. */
    public void quad(float[] a, float[] b, float[] c, float[] d, SurfaceShader shader) {
        float[] n = Affine.normalise(Affine.cross(Affine.subtract(b, a), Affine.subtract(d, a)));
        float lu = Affine.length(Affine.subtract(b, a)), lv = Affine.length(Affine.subtract(d, a));
        float[] va = vertex(a, n, 0, 0), vb = vertex(b, n, lu, 0), vc = vertex(c, n, lu, lv), vd = vertex(d, n, 0, lv);
        triangle(va, vb, vc, shader);
        triangle(va, vc, vd, shader);
    }

    /** An axis-aligned-ish box given its centre, half sizes and a rotation about Y in radians; all six faces. */
    public void box(float[] centre, float hx, float hy, float hz, float yaw, SurfaceShader shader) {
        box(centre, hx, hy, hz, yaw, 0f, shader);
    }

    /** A box tilted back about its X axis by pitch (radians) after turning by yaw. */
    public void box(float[] centre, float hx, float hy, float hz, float yaw, float pitch, SurfaceShader shader) {
        float[] m = Affine.multiply(Affine.translation(centre[0], centre[1], centre[2]),
                Affine.multiply(Affine.rotationY(yaw), Affine.rotationX(pitch)));
        float[][] corners = new float[8][];
        for (int i = 0; i < 8; i++) {
            corners[i] = Affine.transformPoint(m, (i & 1) == 0 ? -hx : hx, (i & 2) == 0 ? -hy : hy, (i & 4) == 0 ? -hz : hz);
        }
        int[][] faces = { { 0, 1, 3, 2 }, { 4, 6, 7, 5 }, { 0, 4, 5, 1 }, { 2, 3, 7, 6 }, { 0, 2, 6, 4 }, { 1, 5, 7, 3 } };
        for (int[] f : faces) {
            quad(corners[f[0]], corners[f[1]], corners[f[2]], corners[f[3]], shader);
        }
    }

    /** A cylinder from a to b of the given radius, with its ends capped. */
    public void cylinder(float[] a, float[] b, float radius, int sides, SurfaceShader shader) {
        float[] axis = Affine.normalise(Affine.subtract(b, a));
        float[] side = Affine.normalise(Affine.cross(axis, Math.abs(axis[1]) < 0.9f ? new float[] { 0, 1, 0 } : new float[] { 1, 0, 0 }));
        float[] other = Affine.cross(axis, side);
        float length = Affine.length(Affine.subtract(b, a));
        for (int i = 0; i < sides; i++) {
            double t0 = i * Math.PI * 2 / sides, t1 = (i + 1) * Math.PI * 2 / sides;
            float[] n0 = Affine.add(Affine.add(new float[3], side, (float) Math.cos(t0)), other, (float) Math.sin(t0));
            float[] n1 = Affine.add(Affine.add(new float[3], side, (float) Math.cos(t1)), other, (float) Math.sin(t1));
            float[] a0 = Affine.add(a, n0, radius), a1 = Affine.add(a, n1, radius);
            float[] b0 = Affine.add(b, n0, radius), b1 = Affine.add(b, n1, radius);
            float u0 = (float) (t0 * radius), u1 = (float) (t1 * radius);
            triangle(vertex(a0, n0, u0, 0), vertex(a1, n1, u1, 0), vertex(b1, n1, u1, length), shader);
            triangle(vertex(a0, n0, u0, 0), vertex(b1, n1, u1, length), vertex(b0, n0, u0, length), shader);
            float[] na = { -axis[0], -axis[1], -axis[2] };
            triangle(vertex(a, na, 0, 0), vertex(a1, na, 0, 0), vertex(a0, na, 0, 0), shader);
            triangle(vertex(b, axis, 0, 0), vertex(b0, axis, 0, 0), vertex(b1, axis, 0, 0), shader);
        }
    }

    public static float[] vertex(float[] p, float[] n, float u, float v) {
        return new float[] { p[0], p[1], p[2], n[0], n[1], n[2], u, v };
    }
}
