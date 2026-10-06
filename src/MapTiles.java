import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The map drawn in tiles once it's zoomed in past what its whole images can show sharply:
 * each tile a square of the map at a level of detail (level L has 2^L tiles across), drawn in
 * the background when first wanted and kept for a while. Tiles still in view when a worker
 * gets to them are drawn first, the most recently asked for first; ones scrolled away from
 * are skipped.
 */
public final class MapTiles {

    /** Draws one tile of something on the map: a size-square image of tile (tx, ty) at a level. */
    @FunctionalInterface
    public interface Renderer {
        BufferedImage render(int level, int tx, int ty, int size);
    }

    public static final int SIZE = 256;
    private static final int KEPT = 260;

    private final Map<String, BufferedImage> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > KEPT;
        }
    };
    private final Set<String> pending = new HashSet<>();
    private volatile Set<String> wanted = new HashSet<>();
    private Set<String> wanting = new HashSet<>();
    private final ThreadPoolExecutor workers;
    private final Runnable onReady;

    public MapTiles(Runnable onReady) {
        this.onReady = onReady;
        // Newest first: a queue used as a stack
        LinkedBlockingDeque<Runnable> stack = new LinkedBlockingDeque<>() {
            @Override
            public boolean offer(Runnable r) {
                return offerFirst(r);
            }
        };
        workers = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, stack, r -> {
            Thread t = new Thread(r, "map-tiles");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    /** Before a painting asks for its tiles. */
    public void beginFrame() {
        wanting = new HashSet<>();
    }

    /** After it: tiles not asked for this time needn't be drawn any more. */
    public void endFrame() {
        wanted = wanting;
    }

    /** A tile if it's ready, else null (and it's set to be drawn). */
    public BufferedImage get(String source, Renderer renderer, int level, int tx, int ty) {
        String key = source + "/" + level + "/" + tx + "/" + ty;
        wanting.add(key);
        synchronized (cache) {
            BufferedImage ready = cache.get(key);
            if (ready != null || pending.contains(key)) return ready;
            pending.add(key);
        }
        workers.execute(() -> {
            BufferedImage image = null;
            try {
                if (wanted.contains(key) || wanting.contains(key)) image = renderer.render(level, tx, ty, SIZE);
            } catch (Exception e) {
                System.err.println("Map tile failed: " + e);
            }
            synchronized (cache) {
                pending.remove(key);
                if (image != null) cache.put(key, image);
            }
            if (image != null) onReady.run();
        });
        return null;
    }

    /** Forgets every tile of a source (when what it shows has changed). */
    public void forget(String source) {
        synchronized (cache) {
            cache.keySet().removeIf(k -> k.startsWith(source + "/"));
        }
    }
}
