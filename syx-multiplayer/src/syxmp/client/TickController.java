package syxmp.client;

import game.GAME;
import snake2d.util.rnd.RND;
import syxmp.common.Packets;
import syxmp.common.ScheduledCommand;
import syxmp.common.TickRate;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;

/**
 * The lockstep tick driver, port of Multiplayer.Client's TickPatch.
 *
 * The agent rewrites the game.update(ds, slowDown) call inside
 * view.main.VIEW.update (VIEW.java:257 in V71.44) into a call to
 * {@link #gameUpdate}. Outside multiplayer this passes straight through to
 * vanilla. In multiplayer it replaces the frame-rate-coupled, wall-clock-
 * throttled vanilla logic with: advance the Timer toward the server's
 * tickUntil, execute scheduled commands at their exact Timer values, and run
 * fixed 1/16s sub-steps according to the synced game speed. Nothing about
 * the simulation depends on local frame rate anymore.
 */
public final class TickController {

    /** This client's lockstep clock position. */
    public static int timer;
    /** Authorized ceiling from the server (via MultiplayerSession.processTimeControl). */
    public static volatile int tickUntil;
    /** ms of real time per Timer unit — the server paces this at the slowest client. */
    public static volatile float msPerTimerUnit = 1000f / TickRate.TIMER_HZ;

    /** Synced game speed (Songs of Syx speeds: 0, 0.25, 1, 5, 25, 250). Changed only by commands. */
    public static double speed = 1.0;
    /** Sub-step accumulator (RW's TimeToTickThrough). */
    public static float timeToTickThrough;

    public static volatile boolean frozen;
    public static boolean desynced;
    /** Fast-forward mode: catch up to simulatingTarget as fast as possible. */
    public static int simulatingTarget = -1;
    public static Runnable onSimulatingDone;

    private static long lastFrameNanos;
    private static double accumMs;
    private static long lastFrameReportMs;
    private static float avgFrameMs = 1000f / 60f;

    /** Max Timer units to run per frame in normal play (RW builds a small buffer). */
    private static final int MAX_TICKS_PER_FRAME = 6;
    /** Wall-clock budget per frame while fast-forwarding. */
    private static final long SIMULATE_BUDGET_MS = 40;

    private TickController() {
    }

    /** Injected in place of {@code game.update(ds, slowDown)} — updater thread. */
    public static void gameUpdate(GAME game, double ds, double slowDown) {
        MainThread.drain();

        if (!Multiplayer.active()) {
            game.update(ds, slowDown);
            return;
        }

        long now = System.nanoTime();
        float frameMs = lastFrameNanos == 0 ? 16.7f
                : Math.min(200f, (now - lastFrameNanos) / 1_000_000f);
        lastFrameNanos = now;
        avgFrameMs = avgFrameMs * 0.9f + frameMs * 0.1f;
        reportFrameTime();

        if (desynced)
            return;

        if (simulatingTarget >= 0) {
            simulate();
            return;
        }

        if (frozen)
            return;

        accumMs += frameMs;
        int budget = (int) (accumMs / Math.max(1f, msPerTimerUnit));
        int ticks = Math.min(Math.min(budget, MAX_TICKS_PER_FRAME), tickUntil - timer);
        if (ticks > 0) {
            accumMs -= ticks * (double) msPerTimerUnit;
            for (int i = 0; i < ticks; i++)
                runOneTimerUnit();
        }
        // Never bank more than a couple of ticks of real time
        accumMs = Math.min(accumMs, msPerTimerUnit * 2.0);
    }

    private static void simulate() {
        long start = System.currentTimeMillis();
        int target = Math.min(simulatingTarget, tickUntil);
        while (timer < target && System.currentTimeMillis() - start < SIMULATE_BUDGET_MS)
            runOneTimerUnit();
        if (timer >= simulatingTarget) {
            simulatingTarget = -1;
            Runnable done = onSimulatingDone;
            onSimulatingDone = null;
            MpLog.info("caught up to timer " + timer);
            if (done != null)
                done.run();
        }
    }

    /** One Timer unit: due commands, then as many sub-steps as the speed calls for. */
    private static void runOneTimerUnit() {
        MultiplayerSession session = Multiplayer.session;
        while (!session.cmds.isEmpty() && session.cmds.peek().ticks <= timer) {
            ScheduledCommand cmd = session.cmds.poll();
            if (cmd.ticks < timer)
                MpLog.err("late command " + cmd + " at timer " + timer);
            CommandExecutor.execute(cmd);
        }

        float timePerTick = TickRate.timePerTick(speed);
        if (timePerTick > 0) {
            timeToTickThrough += 1f;
            while (timeToTickThrough >= timePerTick) {
                timeToTickThrough -= timePerTick;
                runSubStep();
            }
        }
        timer++;
    }

    /**
     * One deterministic 1/16s simulation sub-step (one GAME.updateI increment).
     *
     * RND is reseeded from (sessionSeed, updateI) at every sub-step: unlike
     * RimWorld's Rand push/pop discipline, this makes any RND consumption
     * outside the sub-step (menus, minimap redraws, hover UI) harmless —
     * the stream position is re-anchored before simulation code runs.
     */
    private static void runSubStep() {
        Multiplayer.ticking = true;
        try {
            RND.setSeed(mixSeed(Multiplayer.session.rngSeed, GAME.updateI()));
            // GAME.update(double) is the vanilla manual-tick entry
            // (GAME.java:235: SPEED.speedSet(1.0); game.update(seconds, 0)).
            GAME.update(TickRate.SUBSTEP_DS);
        } finally {
            Multiplayer.ticking = false;
        }
    }

    public static int mixSeed(long sessionSeed, int tick) {
        long z = sessionSeed + 0x9E3779B97F4A7C15L * (tick + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (int) (z ^ (z >>> 31));
    }

    private static long lastKeepAliveMs;

    private static void reportFrameTime() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastKeepAliveMs > 2000) {
            lastKeepAliveMs = nowMs;
            ByteWriter ka = new ByteWriter(4);
            ka.writeInt(Multiplayer.session.nextKeepAlive());
            Multiplayer.session.connection.send(Packets.CLIENT_KEEP_ALIVE, ka);
        }
        if (nowMs - lastFrameReportMs < 100)
            return;
        lastFrameReportMs = nowMs;
        ByteWriter w = new ByteWriter(8);
        w.writeFloat(avgFrameMs);
        w.writeInt(timer);
        Multiplayer.session.connection.send(Packets.CLIENT_FRAME_TIME, w);
    }

    /** Reset all clock state when a session starts or the world is (re)loaded. */
    public static void setClock(int atTimer, int until) {
        timer = atTimer;
        tickUntil = until;
        timeToTickThrough = 0;
        accumMs = 0;
        lastFrameNanos = 0;
        desynced = false;
    }
}
