import java.awt.image.BufferedImage;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Hashes of what the game makes, for RegressionCheck: a refactor that changes no behaviour
 * leaves every one the same.
 *
 * Two kinds. ordered(): values added in turn, where order matters (a lookup's answers along a
 * grid). deep(): everything reachable from an object by its fields (numbers, strings, images,
 * primitive arrays in order), hashed as an unordered multiset, so moving fields between
 * classes, splitting a class or turning an Object[] into a record changes nothing, while any
 * value made differently does. Statics, JDK internals, library objects, lambdas and Randoms
 * are not followed; fields named in IGNORED (timings) are skipped.
 */
public final class Fingerprint {

    // Fields holding clock readings or other run-to-run noise, by name
    private static final Set<String> IGNORED = Set.of(
            "startTime", "lastElapsedTime", "fpsWindowStart", "loadingMapsFrom", "teleportStarted",
            "roundLoadingStarted", "roundChunkDeadline", "geodesicTerrainRetryAfterNanos", "clockTicked");

    // What's been added, in turn: numbers, and objects whose deep hash is only taken when the
    // fingerprint is read (taking it hashes objects by identity, which would otherwise change the
    // identity hashes, and so the behaviour, of whatever the game runs after)
    private final List<Object> items = new ArrayList<>();

    private record Deep(Object value) {
    }

    /** Adds a value in turn. */
    public Fingerprint add(long value) {
        items.add(value);
        return this;
    }

    public Fingerprint add(double value) {
        return add(Double.doubleToLongBits(value));
    }

    public Fingerprint add(boolean value) {
        return add(value ? 1L : 2L);
    }

    public Fingerprint add(String value) {
        return add(value == null ? 0L : leaf(value));
    }

    /** Adds an object's deep hash (see deep) in turn, taken when the fingerprint is read. */
    public Fingerprint addDeep(Object value) {
        items.add(new Deep(value));
        return this;
    }

    @Override
    public String toString() {
        long hash = 0xCBF29CE484222325L, count = 0;
        for (Object item : items) {
            long[] values = item instanceof Deep d ? deep(d.value()) : new long[] { (Long) item };
            for (long value : values) {
                hash = mix(hash ^ mix(value)) * 0x100000001B3L;
                count++;
            }
        }
        return String.format("%016x/%d", hash, count);
    }

    /**
     * The multiset hash of every value reachable from root, and how many there were: {hash, count}.
     * If perField isn't null, each field name's own multiset hash is added to it (for finding
     * which part differs).
     */
    public static long[] deep(Object root) {
        return deep(root, null);
    }

    public static long[] deep(Object root, Map<String, long[]> perField) {
        long sum = 0L, leaves = 0L;
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[] { root, "<root>" });
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            Object o = item[0];
            String field = (String) item[1];
            if (o == null) continue;
            Class<?> c = o.getClass();
            Long value = null;
            if (o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum<?>
                    || c.isArray() && c.getComponentType().isPrimitive() || o instanceof BufferedImage) {
                if (!(o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum<?>)) {
                    if (seen.put(o, Boolean.TRUE) != null) continue;
                }
                value = leaf(o);
            }
            if (value != null) {
                long m = mix(value);
                sum += m;
                leaves++;
                if (perField != null) {
                    long[] f = perField.computeIfAbsent(field, k -> new long[2]);
                    f[0] += m;
                    f[1]++;
                }
                continue;
            }
            if (seen.put(o, Boolean.TRUE) != null) continue;
            if (c.isArray()) {
                int n = Array.getLength(o);
                for (int i = 0; i < n; i++) queue.add(new Object[] { Array.get(o, i), field });
            } else if (o instanceof Collection<?> collection) {
                for (Object e : snapshot(collection)) queue.add(new Object[] { e, field });
            } else if (o instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : snapshot(map.entrySet())) {
                    queue.add(new Object[] { e.getKey(), field });
                    queue.add(new Object[] { e.getValue(), field });
                }
            } else if (o instanceof java.util.concurrent.atomic.AtomicInteger a) {
                queue.add(new Object[] { a.get(), field });
            } else if (o instanceof java.util.concurrent.atomic.AtomicLong a) {
                queue.add(new Object[] { a.get(), field });
            } else if (followed(c)) {
                for (Class<?> k = c; k != null && followed(k); k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || IGNORED.contains(f.getName())) continue;
                        try {
                            f.setAccessible(true);
                            queue.add(new Object[] { f.get(o), f.getName() });
                        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
                            // (left out)
                        }
                    }
                }
            }
        }
        return new long[] { sum, leaves };
    }

    /** The game's own classes (the default package and com.xenoguesser), not lambdas. */
    private static boolean followed(Class<?> c) {
        String name = c.getName();
        if (c.isSynthetic() || c.isHidden() || name.contains("$$Lambda")) return false;
        return !name.contains(".") || name.startsWith("com.xenoguesser.");
    }

    private static <T> List<T> snapshot(Collection<T> collection) {
        // (a background thread may still be adding: try again rather than fail)
        for (int attempt = 0; ; attempt++) {
            try {
                return new ArrayList<>(collection);
            } catch (java.util.ConcurrentModificationException busy) {
                if (attempt > 20) throw busy;
            }
        }
    }

    private static long leaf(Object o) {
        long h;
        if (o instanceof String s) {
            h = 1;
            for (int i = 0; i < s.length(); i++) h = h * 1_000_003L + s.charAt(i);
        } else if (o instanceof Float f) {
            h = 2 + 31L * Float.floatToIntBits(f);
        } else if (o instanceof Double d) {
            h = 3 + 31L * Double.doubleToLongBits(d);
        } else if (o instanceof Number n) {
            h = 4 + 31L * n.longValue();
        } else if (o instanceof Boolean b) {
            h = b ? 5 : 6;
        } else if (o instanceof Character ch) {
            h = 7 + 31L * ch;
        } else if (o instanceof Enum<?> e) {
            // (by its simple name: an enum moved out of the class it was nested in is the same enum)
            h = 8 + 31L * leaf(e.getDeclaringClass().getSimpleName() + "." + e.name());
        } else if (o instanceof BufferedImage image) {
            h = 9 + 31L * image.getWidth() + 961L * image.getHeight();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) h = mix(h ^ image.getRGB(x, y));
            }
        } else {
            h = 10 + 31L * o.getClass().getComponentType().getName().hashCode();
            int n = Array.getLength(o);
            for (int i = 0; i < n; i++) {
                Object e = Array.get(o, i);
                long v = e instanceof Float f ? Float.floatToIntBits(f) : e instanceof Double d ? Double.doubleToLongBits(d)
                        : e instanceof Boolean b ? (b ? 1 : 2) : e instanceof Character ch ? ch : ((Number) e).longValue();
                h = mix(h ^ v) + i;
            }
        }
        return h;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The per-field breakdown of deep(), as "name hash/count" lines sorted by name. */
    public static List<String> breakdown(Object root) {
        Map<String, long[]> perField = new TreeMap<>();
        deep(root, perField);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, long[]> e : perField.entrySet()) {
            lines.add(String.format("%s %016x/%d", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return lines;
    }
}
