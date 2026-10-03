import java.util.Random;

/**
 * Seamlessly tiling noise fields for procedural textures.
 *
 * Spectral noise is built in the frequency domain: random complex coefficients are
 * scaled by a power law of the wave number and inverse-FFT'd. Every frequency on the
 * grid is periodic over the image, so the result wraps perfectly in both directions.
 * The power-law exponent sets the "roughness" (higher = smoother, larger features).
 */
public final class TileableNoise {

    private TileableNoise() {}

    /**
     * @param size     power of two
     * @param slope    amplitude falls off as k^-slope (0 = white noise, ~1.8 = cloud-like)
     * @param aniso    > 1 squashes features across the given angle, stretching them along it
     * @param angle    stretch direction in radians
     * @param minFreq  wave numbers below this (in cycles per image) are removed; 0 keeps everything
     * @return field with mean 0 and standard deviation 1, row-major size*size
     */
    public static float[] spectral(Random rng, int size, double slope, double aniso, double angle, double minFreq) {
        return spectral(rng, size, slope, aniso, angle, minFreq, Double.POSITIVE_INFINITY);
    }

    /** As above, with detail beyond maxFreq (cycles per image) rolled off smoothly. */
    public static float[] spectral(Random rng, int size, double slope, double aniso, double angle, double minFreq, double maxFreq) {
        double[] re = new double[size * size];
        double[] im = new double[size * size];
        double c = Math.cos(angle), s = Math.sin(angle);
        for (int y = 0; y < size; y++) {
            int fy = y <= size / 2 ? y : y - size;
            for (int x = 0; x < size; x++) {
                int fx = x <= size / 2 ? x : x - size;
                double rx = fx * c + fy * s;
                double ry = (-fx * s + fy * c) * aniso;
                double k = Math.sqrt(rx * rx + ry * ry);
                double radial = Math.sqrt(fx * fx + fy * fy);
                int i = y * size + x;
                if (k == 0 || radial < minFreq) continue;
                double amp = Math.pow(k, -slope);
                if (maxFreq != Double.POSITIVE_INFINITY) amp *= Math.exp(-(radial * radial) / (maxFreq * maxFreq));
                re[i] = rng.nextGaussian() * amp;
                im[i] = rng.nextGaussian() * amp;
            }
        }
        fft2D(re, im, size, true);
        return normalise(re);
    }

    public static float[] spectral(Random rng, int size, double slope) {
        return spectral(rng, size, slope, 1.0, 0.0, 0.0);
    }

    /** Per-pixel independent gaussian values: the finest possible grain, trivially tileable. */
    public static float[] white(Random rng, int size) {
        float[] out = new float[size * size];
        for (int i = 0; i < out.length; i++) out[i] = (float) rng.nextGaussian();
        return out;
    }

    /**
     * Periodic Worley (cellular) noise: distance to the nearest and second-nearest feature
     * point, with points wrapping across the edges. Returns {F1, F2} in pixel units.
     */
    public static float[][] worley(Random rng, int size, int cells) {
        float cellSize = (float) size / cells;
        float[] px = new float[cells * cells];
        float[] py = new float[cells * cells];
        for (int i = 0; i < cells * cells; i++) {
            px[i] = ((i % cells) + rng.nextFloat()) * cellSize;
            py[i] = ((i / cells) + rng.nextFloat()) * cellSize;
        }
        float[] f1 = new float[size * size];
        float[] f2 = new float[size * size];
        for (int y = 0; y < size; y++) {
            int cy = (int) (y / cellSize);
            for (int x = 0; x < size; x++) {
                int cx = (int) (x / cellSize);
                float d1 = Float.MAX_VALUE, d2 = Float.MAX_VALUE;
                for (int oy = -2; oy <= 2; oy++) {
                    for (int ox = -2; ox <= 2; ox++) {
                        int gx = Math.floorMod(cx + ox, cells);
                        int gy = Math.floorMod(cy + oy, cells);
                        int p = gy * cells + gx;
                        float dx = wrapDelta(px[p] - x, size);
                        float dy = wrapDelta(py[p] - y, size);
                        float d = (float) Math.sqrt(dx * dx + dy * dy);
                        if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
                    }
                }
                f1[y * size + x] = d1;
                f2[y * size + x] = d2;
            }
        }
        return new float[][] {f1, f2};
    }

    /** Bilinear sample with wrap-around, so warped lookups stay tileable. */
    public static float sampleWrap(float[] field, int size, float x, float y) {
        float fx = (float) Math.floor(x), fy = (float) Math.floor(y);
        float tx = x - fx, ty = y - fy;
        int x0 = Math.floorMod((int) fx, size), y0 = Math.floorMod((int) fy, size);
        int x1 = (x0 + 1) % size, y1 = (y0 + 1) % size;
        float a = field[y0 * size + x0], b = field[y0 * size + x1];
        float c = field[y1 * size + x0], d = field[y1 * size + x1];
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty;
    }

    /** Box blur with wrap-around edges (keeps tiling); radius 0 returns a copy. */
    public static float[] blurWrap(float[] field, int size, int radius) {
        float[] tmp = new float[field.length];
        float[] out = new float[field.length];
        float norm = 1f / (2 * radius + 1);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float sum = 0;
                for (int k = -radius; k <= radius; k++) sum += field[y * size + Math.floorMod(x + k, size)];
                tmp[y * size + x] = sum * norm;
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float sum = 0;
                for (int k = -radius; k <= radius; k++) sum += tmp[Math.floorMod(y + k, size) * size + x];
                out[y * size + x] = sum * norm;
            }
        }
        return out;
    }

    private static float wrapDelta(float d, int size) {
        if (d > size / 2f) return d - size;
        if (d < -size / 2f) return d + size;
        return d;
    }

    private static float[] normalise(double[] values) {
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        double inv = 1.0 / (Math.sqrt(var / values.length) + 1e-12);
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (float) ((values[i] - mean) * inv);
        return out;
    }

    /** In-place 2D FFT (rows then columns); size must be a power of two. */
    private static void fft2D(double[] re, double[] im, int size, boolean inverse) {
        double[] rowRe = new double[size], rowIm = new double[size];
        for (int y = 0; y < size; y++) {
            System.arraycopy(re, y * size, rowRe, 0, size);
            System.arraycopy(im, y * size, rowIm, 0, size);
            fft1D(rowRe, rowIm, inverse);
            System.arraycopy(rowRe, 0, re, y * size, size);
            System.arraycopy(rowIm, 0, im, y * size, size);
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) { rowRe[y] = re[y * size + x]; rowIm[y] = im[y * size + x]; }
            fft1D(rowRe, rowIm, inverse);
            for (int y = 0; y < size; y++) { re[y * size + x] = rowRe[y]; im[y * size + x] = rowIm[y]; }
        }
    }

    private static void fft1D(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double br = re[b] * cr - im[b] * ci;
                    double bi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - br; im[b] = im[a] - bi;
                    re[a] += br; im[a] += bi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}
