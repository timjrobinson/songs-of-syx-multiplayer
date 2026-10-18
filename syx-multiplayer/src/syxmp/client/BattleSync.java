package syxmp.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import game.GAME;
import game.battle.thread.BattleThreads;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;

/**
 * Battle AI workers, driven synchronously.
 *
 * Vanilla starts five wall-clock worker threads for EVERY game (battle or
 * not — BattleThreads unpauses in addBeforeGameStarts): status, division
 * centres, trajectories, orders and the strategist. They read and write
 * simulation state on their own schedule, and soldiers' AI reads their
 * output, so two clients can never stay in step while they run. In
 * multiplayer the threads are never started (unpause is intercepted) and
 * their jobs run here, inside the lockstep sub-step, at the same nominal
 * rates: deterministic and identical on every client.
 */
public final class BattleSync {

    private static final String[] FIELDS = {"status", "centres", "trajs", "orders", "strat"};
    private static final double[] INTERVALS = {0.5, 1.0 / 60, 1.0 / 60, 1.0 / 60, 1.0 / 120};

    private static Object[] workers;
    private static Method[] jobs;
    private static final double[] accum = new double[FIELDS.length];
    private static BattleThreads boundTo;
    private static boolean broken;

    private BattleSync() {
    }

    /** Forget the previous game's workers (called when a game is (re)loaded). */
    public static void reset() {
        boundTo = null;
        workers = null;
        for (int i = 0; i < accum.length; i++)
            accum[i] = 0;
    }

    /** Called once per sub-step after GAME.update. */
    public static void step(double ds) {
        if (broken)
            return;
        try {
            BattleThreads bt = GAME.BATTLE_THREADS();
            if (bt == null)
                return;
            if (bt != boundTo)
                bind(bt);
            for (int i = 0; i < workers.length; i++) {
                accum[i] += ds;
                int runs = 0;
                while (accum[i] >= INTERVALS[i] && runs++ < 16) {
                    accum[i] -= INTERVALS[i];
                    jobs[i].invoke(workers[i]);
                }
            }
        } catch (Throwable t) {
            broken = true;
            MpLog.err("battle worker sync failed; disabled", t);
        }
    }

    private static void bind(BattleThreads bt) throws ReflectiveOperationException {
        workers = new Object[FIELDS.length];
        jobs = new Method[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) {
            Field f = ReflectUtil.field(BattleThreads.class, FIELDS[i]);
            workers[i] = f.get(bt);
            Method init = ReflectUtil.method(workers[i].getClass(), "init");
            init.invoke(workers[i]);
            jobs[i] = ReflectUtil.method(workers[i].getClass(), "doThreadJob");
            accum[i] = 0;
        }
        boundTo = bt;
        MpLog.info("battle workers bound synchronously");
    }

    /** Stop the vanilla worker threads of a running game (hosting from single-player). */
    public static void pauseVanillaThreads() {
        try {
            BattleThreads bt = GAME.BATTLE_THREADS();
            if (bt != null)
                bt.pause();
        } catch (RuntimeException e) {
            MpLog.err("could not pause battle threads", e);
        }
    }
}
