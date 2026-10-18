package syxmp.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The server's authoritative blobs: the last join-point save plus every
 * command issued since it. Port of Multiplayer.Common.WorldData (Songs of Syx
 * has a single simulation, so RW's per-map cmd log collapses to one list).
 * The server never deserializes any of this — it only stores and relays.
 */
public final class WorldData {

    private final MultiplayerServer server;

    /** Deflated game save from the last join point (the game's .save bytes). */
    public byte[] savedGame;
    /** Serialized ScheduledCommands issued since savedGame was captured. */
    public final List<byte[]> cmds = new ArrayList<>();

    /** While a join point is being created, new commands also buffer here. */
    private List<byte[]> tmpCmds;
    private CompletableFuture<Void> joinPointFuture;

    /** Timer value at which savedGame was captured. */
    public int savedGameTimer;
    /** The session's RND seed (host-chosen); joiners must have it BEFORE loading. */
    public long rngSeed;

    public WorldData(MultiplayerServer server) {
        this.server = server;
    }

    public boolean creatingJoinPoint() {
        return tmpCmds != null;
    }

    /**
     * Issue a CREATE_JOIN_POINT command into the normal stream (every client
     * saves+reloads at that tick; the host uploads the result) and start
     * buffering. Port of WorldData.TryStartJoinPointCreation.
     */
    public CompletableFuture<Void> tryStartJoinPointCreation() {
        if (joinPointFuture != null)
            return joinPointFuture;
        joinPointFuture = new CompletableFuture<>();
        // Send first, then start buffering: the join-point command itself is
        // executed by the host BEFORE capturing, so it must not be replayed
        // by joiners starting from the capture (as in RW's WorldData).
        server.commands.sendJoinPointCommand();
        tmpCmds = new ArrayList<>();
        return joinPointFuture;
    }

    public void addCommand(byte[] serializedCmd) {
        cmds.add(serializedCmd);
        if (tmpCmds != null)
            tmpCmds.add(serializedCmd);
    }

    /** The host uploaded the join-point save: swap in the new baseline. */
    public void endJoinPointCreation(byte[] newSavedGame, int atTimer) {
        savedGame = newSavedGame;
        savedGameTimer = atTimer;
        cmds.clear();
        if (tmpCmds != null) {
            cmds.addAll(tmpCmds);
            tmpCmds = null;
        }
        CompletableFuture<Void> f = joinPointFuture;
        joinPointFuture = null;
        if (f != null)
            f.complete(null);
    }

    public CompletableFuture<Void> waitJoinPoint() {
        if (joinPointFuture != null)
            return joinPointFuture;
        if (savedGame != null)
            return CompletableFuture.completedFuture(null);
        return tryStartJoinPointCreation();
    }
}
