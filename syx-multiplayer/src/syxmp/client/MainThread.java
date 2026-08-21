package syxmp.client;

import java.util.concurrent.ConcurrentLinkedQueue;

import syxmp.util.MpLog;

/**
 * Work queue drained at a fixed point in the game's frame (the start of
 * TickController.gameUpdate, on the updater thread). All network packet
 * handling for the client runs through here, so game state is only ever
 * touched from the game's own thread. Port of RW-MP's OnMainThread.
 */
public final class MainThread {

    private static final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

    private MainThread() {
    }

    public static void enqueue(Runnable r) {
        queue.add(r);
    }

    public static void drain() {
        Runnable r;
        while ((r = queue.poll()) != null) {
            try {
                r.run();
            } catch (Exception e) {
                MpLog.err("exception in main-thread action", e);
            }
        }
    }
}
