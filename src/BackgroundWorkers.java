import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * The threads of the pool every parallel stream shares (making the world, its maps and
 * overlays), named here by XenoGuesser.main before it's first used: below the usual priority,
 * so on a PC with few cores the landing pod's frames and the window still come first.
 */
public class BackgroundWorkers implements ForkJoinPool.ForkJoinWorkerThreadFactory {

    @Override
    public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
        ForkJoinWorkerThread thread = new ForkJoinWorkerThread(pool) {
        };
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    }
}
