package syxmp.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import syxmp.common.ScheduledCommand;
import syxmp.net.ConnectionBase;

/**
 * Client-side session state for one multiplayer game. Port of
 * Multiplayer.Client.MultiplayerSession (single-sim: one command queue).
 */
public final class MultiplayerSession {

    public ConnectionBase connection;
    /** How this client joined, for automatic resync after a desync. */
    public String joinAddress;
    public int joinPort;
    public long joinLobby;
    public String joinPassword = "";
    public int playerId = -1;
    public String username;
    public boolean isHost;

    /** Deterministic base seed for the session's RNG discipline. */
    public long rngSeed;

    /** Commands scheduled for future ticks, in arrival (= execution) order. */
    public final Queue<ScheduledCommand> cmds = new ArrayDeque<>();

    /** Count of commands received; must reach remoteSentCmds before advancing. */
    public long receivedCmds;
    /** Latest time-control data from the server. */
    public long remoteSentCmds;
    public int remoteTickUntil;

    public final List<PlayerListEntry> players = new ArrayList<>();

    public static final class PlayerListEntry {
        public int id;
        public String username;
        public int type;
        public int status;
        public int latency;
    }

    // Keep-alive bookkeeping for latency measurement
    private final long[] keepAliveTimes = new long[16];
    private int keepAliveCounter;

    public int nextKeepAlive() {
        int id = keepAliveCounter++;
        keepAliveTimes[id & 15] = System.currentTimeMillis();
        return id;
    }

    public long keepAliveSentAt(int id) {
        return keepAliveTimes[id & 15];
    }

    public void scheduleCommand(ScheduledCommand cmd) {
        cmds.add(cmd);
        receivedCmds++;
        processTimeControl();
    }

    /**
     * The lockstep interlock (port of MultiplayerSession.ProcessTimeControl):
     * only expose the server's tick window once every command it counted has
     * arrived, so a command can never be missed for a tick we already ran.
     */
    public void processTimeControl() {
        if (receivedCmds >= remoteSentCmds)
            TickController.tickUntil = remoteTickUntil;
    }
}
