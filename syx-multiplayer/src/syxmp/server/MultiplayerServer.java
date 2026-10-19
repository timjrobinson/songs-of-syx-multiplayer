package syxmp.server;

import java.util.ArrayList;
import java.util.List;

import syxmp.common.Packets;
import syxmp.common.TickRate;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;

/**
 * The relay server: a clock plus a command post-office. It NEVER simulates
 * the game — it stamps commands with ticks, stores blobs for joiners, and
 * advances the tick window. Port of Multiplayer.Common.MultiplayerServer.
 *
 * Runs its own thread at NET_HZ. The host runs this in-process and talks to
 * it through a LocalConnection pair, exactly like RimWorld Multiplayer.
 */
public final class MultiplayerServer {

    public static final int NET_HZ = 30;
    /** Stall the clock if any playing client is this many Timer units behind. */
    public static final int MAX_BEHIND = 90;

    public final ServerSettings settings;
    /** Songs of Syx VERSION.VERSION of the hosting game; joiners must match. */
    public int gameVersion;
    /** Canonical mod list string of the hosting game; joiners must match. */
    public String mods = "";
    public final ActionQueue queue = new ActionQueue();
    public final PlayerManager playerManager = new PlayerManager(this);
    public final CommandHandler commands = new CommandHandler(this);
    public final WorldData worldData = new WorldData(this);
    public final FreezeManager freezeManager = new FreezeManager();

    private final List<Runnable> netTickers = new ArrayList<>();

    private volatile boolean running;
    private Thread thread;

    /** The authoritative lockstep clock (Timer units, TickRate.TIMER_HZ nominal). */
    private int gameTimer;
    /** Real milliseconds not yet converted into Timer units (RW's realTime). */
    private double realTimeMs;
    private long lastNetTickNanos;
    /** ms of real time each client should spend per Timer unit (paces the slowest client). */
    private volatile float msPerTimerUnit = 1000f / TickRate.TIMER_HZ;

    public MultiplayerServer(ServerSettings settings) {
        this.settings = settings;
    }

    public int gameTimer() {
        return gameTimer;
    }

    /** Initialize the clock from the hosting game's state. */
    public void startFrom(int timer, byte[] savedGame, long rngSeed) {
        this.gameTimer = timer;
        worldData.savedGame = savedGame;
        worldData.savedGameTimer = timer;
        worldData.rngSeed = rngSeed;
        worldData.hostTitles = syxmp.sync.TitleSync.encode(); // "" in the headless test (no game)
    }

    /** Transports register per-net-tick work (accept polling etc.) here. */
    public void addNetTicker(Runnable r) {
        netTickers.add(r);
    }

    public synchronized void start() {
        if (running)
            return;
        running = true;
        thread = new Thread(this::run, "SyxMP-Server");
        thread.setDaemon(true);
        thread.start();
        MpLog.info("server started (" + settings.gameName + ")");
    }

    public void stop(String why) {
        if (!running)
            return;
        running = false;
        MpLog.info("server stopping: " + why);
    }

    public boolean isRunning() {
        return running;
    }

    private void run() {
        long tickNanos = 1_000_000_000L / NET_HZ;
        long next = System.nanoTime();
        while (running) {
            try {
                netTick();
            } catch (Exception e) {
                MpLog.err("server tick error", e);
            }
            next += tickNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    break;
                }
            } else {
                next = System.nanoTime();
            }
        }
    }

    private void netTick() {
        queue.runQueue();
        for (Runnable r : netTickers)
            r.run();

        long now = System.currentTimeMillis();
        freezeManager.tick(now);

        long nanos = System.nanoTime();
        if (lastNetTickNanos != 0)
            realTimeMs += (nanos - lastNetTickNanos) / 1_000_000.0;
        lastNetTickNanos = nanos;

        // Advance the clock at the pace of the slowest client, up to two
        // Timer units per net tick (port of MultiplayerServer.Run). Stalls
        // when nobody is close to the clock or someone is >MAX_BEHIND.
        // NOTE: the clock must keep running while a join point is being
        // created — the CREATE_JOIN_POINT command is stamped at the current
        // gameTimer and the host can only execute it once tickUntil passes
        // that tick. Stalling here deadlocked every join (live-test finding).
        List<ServerPlayer> playing = playerManager.playingPlayers();
        int ticked = 0;
        while (realTimeMs > 0 && ticked < 2) {
            if (!freezeManager.frozen() && mayAdvance(playing))
                gameTimer++;
            int maxBehind = 0;
            for (ServerPlayer p : playing)
                maxBehind = Math.max(maxBehind, p.extrapolatedTicksBehind(gameTimer));
            double slowdown = Math.min(maxBehind / 60.0, 2.0);
            realTimeMs -= msPerTimerUnit * (1.0 + slowdown);
            ticked++;
        }
        if (realTimeMs > 0)
            realTimeMs = 0;

        broadcastTimeControl();

        // Pace the session at the slowest client's frame time (clamped).
        float standard = 1000f / TickRate.TIMER_HZ;
        float maxFrame = standard;
        for (ServerPlayer p : playing)
            maxFrame = Math.max(maxFrame, p.frameTimeMs);
        msPerTimerUnit = Math.min(maxFrame, standard * 4f);
    }

    /** Someone must be within 40 ticks of the clock and nobody beyond MAX_BEHIND. */
    private boolean mayAdvance(List<ServerPlayer> playing) {
        if (playing.isEmpty())
            return false;
        boolean anyClose = false;
        for (ServerPlayer p : playing) {
            int behind = p.extrapolatedTicksBehind(gameTimer);
            if (behind > MAX_BEHIND)
                return false;
            if (behind < 40)
                anyClose = true;
        }
        return anyClose;
    }

    private void broadcastTimeControl() {
        ByteWriter w = new ByteWriter(20);
        w.writeInt(gameTimer);
        w.writeLong(commands.sentCmds());
        w.writeFloat(msPerTimerUnit);
        byte[] data = w.toArray();
        for (ServerPlayer p : playerManager.playingPlayers())
            p.conn.send(Packets.SERVER_TIME_CONTROL, data);
    }
}
