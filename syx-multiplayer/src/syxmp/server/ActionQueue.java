package syxmp.server;

import java.util.concurrent.ConcurrentLinkedQueue;

import syxmp.util.MpLog;

/** Cross-thread work queue drained by one owner thread. Port of Multiplayer.Common.ActionQueue. */
public final class ActionQueue {

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(Runnable r) {
        queue.add(r);
    }

    public void runQueue() {
        Runnable r;
        while ((r = queue.poll()) != null) {
            try {
                r.run();
            } catch (Exception e) {
                MpLog.err("exception in queued action", e);
            }
        }
    }
}
