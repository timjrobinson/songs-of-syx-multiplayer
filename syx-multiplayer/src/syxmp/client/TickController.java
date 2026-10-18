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
    /** Last non-zero speed, for pause toggling. */
    public static double prevNonZeroSpeed = 1.0;
    /** Sub-step accumulator (RW's TimeToTickThrough). */
    public static float timeToTickThrough;

    public static volatile boolean frozen;
    public static boolean desynced;
    /** Set by JoinPoints: stop the current Timer unit right after the command (RW's yield). */
    public static boolean yieldForReload;
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

    /**
     * The vanilla paused frame (every GameResource.update(0) + updateI++)
     * run per Timer unit while the synced speed is 0. OFF by default: pair
     * runs with it desynced within ~20 sub-steps of the next unpause every
     * time (maintenance tile updates diverged; see docs/HANDOFF3.md), runs
     * without it stayed in sync. Kept for investigation.
     */
    private static final boolean PAUSED_STEP = "1".equals(System.getProperty("syxmp.pausedstep"));
    private static final boolean NO_MIRROR = "1".equals(System.getProperty("syxmp.nomirror"));

    private TickController() {
    }

    /** Injected in place of {@code game.update(ds, slowDown)} — updater thread. */
    public static void gameUpdate(GAME game, double ds, double slowDown) {
        // Frame order (snake2d.Updater): update -> input poll (clicks) -> render.
        // UI context = everything outside this sim phase and outside render.
        syxmp.dev.VerbTrace.uiContext = false;
        try {
            gameUpdateInner(game, ds, slowDown);
        } finally {
            syxmp.dev.VerbTrace.uiContext = true;
        }
    }

    private static void gameUpdateInner(GAME game, double ds, double slowDown) {
        currentGame = game;
        Multiplayer.gameThread = Thread.currentThread();
        syxmp.steam.SteamMp.pump();
        MainThread.drain();
        syxmp.sync.SyncActions.frame();
        DevAuto.gameTick();

        if (!Multiplayer.active()) {
            vanillaUpdate(game, ds, slowDown);
            return;
        }

        // Session exists but we're still connecting/handshaking: the local
        // game keeps running vanilla until the world transfer starts.
        MultiplayerSession session = Multiplayer.session;
        if (session.connection == null
                || session.connection.stateEnum() != syxmp.net.ConnectionStateEnum.CLIENT_PLAYING) {
            vanillaUpdate(game, ds, slowDown);
            return;
        }
        // World blob received and loading: never tick the outgoing game.
        // The first frame of the NEW game completes the reload.
        SaveTransfer.finishReloadIfPending();
        if (Multiplayer.reloading)
            return;
        syxmp.ui.GameUi.frame();

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
                if (!runOneTimerUnit())
                    break;
        }
        // Never bank more than a couple of ticks of real time
        accumMs = Math.min(accumMs, msPerTimerUnit * 2.0);
    }

    private static void simulate() {
        long start = System.currentTimeMillis();
        int target = Math.min(simulatingTarget, tickUntil);
        while (timer < target && System.currentTimeMillis() - start < SIMULATE_BUDGET_MS)
            if (!runOneTimerUnit())
                return;
        syxmp.ui.MpStatus.set("catching up " + timer + "/" + simulatingTarget);
        if (timer >= simulatingTarget) {
            simulatingTarget = -1;
            Runnable done = onSimulatingDone;
            onSimulatingDone = null;
            MpLog.info("caught up to timer " + timer);
            if (done != null)
                done.run();
        }
    }

    private static GAME currentGame;

    /**
     * Vanilla frame update while no lockstep session owns the sim. Marked as
     * ticking so the sync hooks never mistake the game's own internal setter
     * calls for player input (they would be cancelled locally and sent as
     * commands — seen while a client was reconnecting).
     */
    private static void vanillaUpdate(GAME game, double ds, double slowDown) {
        Multiplayer.ticking = true;
        try {
            game.update(ds, slowDown);
        } finally {
            Multiplayer.ticking = false;
        }
    }

    /** True once the lockstep loop owns the simulation (connection playing, not reloading). */
    public static boolean inLockstep() {
        MultiplayerSession session = Multiplayer.session;
        return session != null && session.connection != null && !Multiplayer.reloading
                && session.connection.stateEnum() == syxmp.net.ConnectionStateEnum.CLIENT_PLAYING;
    }

    /** One Timer unit: due commands, then as many sub-steps as the speed calls for.
     *  @return false if the unit was aborted for a reload (timer not advanced). */
    private static boolean runOneTimerUnit() {
        MultiplayerSession session = Multiplayer.session;
        syxmp.desync.SyncCoordinator.onTimerUnit(timer);
        // Vanilla's per-frame post-tick work, at a deterministic rate (see Determinism.afterTick).
        if (currentGame != null) {
            Multiplayer.ticking = true;
            try {
                RND.setSeed(mixSeed(session.rngSeed ^ 0xAF7E27C4L, timer));
                currentGame.afterTick();
            } finally {
                Multiplayer.ticking = false;
            }
        }
        while (!session.cmds.isEmpty() && session.cmds.peek().ticks <= timer) {
            ScheduledCommand cmd = session.cmds.poll();
            if (cmd.ticks < timer)
                MpLog.err("late command " + cmd + " at timer " + timer);
            CommandExecutor.execute(cmd);
            if (yieldForReload) {
                // The game is about to be replaced by its own capture taken
                // at this exact point; the remaining commands of this unit
                // and its sub-steps run after the reload, at the same timer.
                yieldForReload = false;
                return false;
            }
        }

        // GameSpeed.update clamps the speed to 25 while enemies are on the map
        // (GameSpeed.java:38); the enemy count is sim state, so this is
        // identical on every client.
        double effective = speed;
        if (effective > 25 && GAME.ARMIES().enemy().men() > 0)
            effective = 25;
        float timePerTick = TickRate.timePerTick(effective);
        if (timePerTick > 0) {
            timeToTickThrough += 1f;
            while (timeToTickThrough >= timePerTick) {
                timeToTickThrough -= timePerTick;
                runSubStep();
            }
        } else if (PAUSED_STEP) {
            runPausedStep();
        }
        if (!NO_MIRROR)
            mirrorVanillaSpeed();
        timer++;
        if (timer % 600 == 0)
            MpLog.info("clock: timer=" + timer + "/" + tickUntil
                    + " updateI=" + GAME.updateI() + " speed=" + speed);
        return true;
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
        syxmp.dev.RndTrace.beginSubStep(GAME.updateI(), timer);
        try {
            RND.setSeed(mixSeed(Multiplayer.session.rngSeed, GAME.updateI()));
            // GAME.update(double) is the vanilla manual-tick entry
            // (GAME.java:235: SPEED.speedSet(1.0); game.update(seconds, 0)).
            GAME.update(TickRate.SUBSTEP_DS);
            BattleSync.step(TickRate.SUBSTEP_DS);
            // One post-step draw fingerprints (seed, updateI, draws consumed):
            // it diverges as soon as any client's sim used the RNG differently.
            syxmp.desync.SyncCoordinator.recordSubStep(RND.rInt(), GAME.updateI(), timer);
        } finally {
            Multiplayer.ticking = false;
            syxmp.dev.RndTrace.endSubStep();
        }
    }

    /**
     * Vanilla's paused frame (GAME.java:258-265): every GameResource's
     * update(0) runs and updateI advances, which is what refreshes the
     * updateI-keyed caches (GAME.Cache) and the tile/overlay updaters while
     * the player builds in pause. In MP it runs once per Timer unit when the
     * synced speed is 0, reseeded, so every client does the same.
     */
    private static void runPausedStep() {
        if (currentGame == null)
            return;
        Multiplayer.ticking = true;
        try {
            RND.setSeed(mixSeed(Multiplayer.session.rngSeed ^ 0x7A05EDL, timer));
            GAME.SPEED.speedSet(0); // GameSpeed.update returns 0 -> paused branch
            currentGame.update(0, 0);
            syxmp.desync.SyncCoordinator.recordSubStep(RND.rInt(), GAME.updateI(), timer);
        } finally {
            Multiplayer.ticking = false;
        }
    }

    /**
     * Keep the vanilla GameSpeed showing the synced speed so the HUD speed
     * buttons and SPEED.isPaused() readers outside the tick see the truth.
     * GAME.update(double) sets it to 1.0 before every sub-step anyway
     * (GAME.java:237), so the sim never observes this value.
     */
    private static void mirrorVanillaSpeed() {
        Multiplayer.ticking = true;
        try {
            if (GAME.SPEED.speedTarget() != speed)
                GAME.SPEED.speedSet(speed);
        } finally {
            Multiplayer.ticking = false;
        }
    }

    /** Deterministic RND state for game construction during a multiplayer load. */
    public static void seedForLoad() {
        MultiplayerSession session = Multiplayer.session;
        if (session == null)
            return;
        int seed = mixSeed(session.rngSeed ^ 0x6C0AD5EEDL, timer);
        syxmp.dev.RndTrace.beginLoad(timer);
        DetRnd.reset();
        RND.setSeed(seed);
        MpLog.info("seeded RND for load at timer " + timer + " seed=" + Integer.toHexString(seed)
                + " session=" + Long.toHexString(session.rngSeed));
    }

    /** Second deterministic seed: after GAME+VIEW construction, before the save data loads. */
    public static void seedAfterCreate() {
        MultiplayerSession session = Multiplayer.session;
        if (session == null || !Multiplayer.reloading)
            return;
        int seed = mixSeed(session.rngSeed ^ 0x4F7E12C3L, timer);
        RND.setSeed(seed);
        syxmp.dev.RndTrace.mark("## after-create reseed");
        MpLog.info("reseeded RND after game creation at timer " + timer + " seed=" + Integer.toHexString(seed)
                + " session=" + Long.toHexString(session.rngSeed) + " probe2=" + Integer.toHexString(RND.rInt()));
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
