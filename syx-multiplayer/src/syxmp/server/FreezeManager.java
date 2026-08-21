package syxmp.server;

/**
 * Global stall while a client performs a blocking long operation (save+reload
 * at a join point, world load). Port of Multiplayer.Common.FreezeManager.
 * While frozen the server's gameTimer does not advance, so no client may tick.
 */
public final class FreezeManager {

    /** Escape hatch: auto-unfreeze after this many ms in case a client dies mid-freeze. */
    private static final long MAX_FREEZE_MS = 30_000;

    private int freezingPlayers;
    private long frozenAtMs;

    public boolean frozen() {
        return freezingPlayers > 0;
    }

    public void setFrozen(int playerId, boolean frozen, long nowMs) {
        if (frozen) {
            freezingPlayers++;
            if (freezingPlayers == 1)
                frozenAtMs = nowMs;
        } else if (freezingPlayers > 0) {
            freezingPlayers--;
        }
    }

    public void onPlayerLeft() {
        // Conservative: a leaving client that froze us should not stall the session
        if (freezingPlayers > 0)
            freezingPlayers--;
    }

    public void tick(long nowMs) {
        if (freezingPlayers > 0 && nowMs - frozenAtMs > MAX_FREEZE_MS)
            freezingPlayers = 0;
    }
}
