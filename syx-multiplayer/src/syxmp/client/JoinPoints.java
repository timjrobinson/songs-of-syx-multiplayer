package syxmp.client;

import syxmp.common.Packets;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;

/**
 * Executes the CREATE_JOIN_POINT command: every client captures its full
 * state at the same tick, the host uploads it as the new joining baseline,
 * and then EVERY client (host included) reloads from its own capture — as in
 * RW-MP. The reload is what makes joining deterministic: a joiner starts from
 * loaded state, so everyone else must be in loaded state too (unsaved
 * accumulators, caches and thread-local leftovers are scrubbed for all).
 *
 * The tick loop yields immediately after this command (no sub-steps, no
 * timer advance) so the reload resumes exactly at the capture tick; the
 * clock is frozen for everyone meanwhile.
 */
public final class JoinPoints {

    /** Hash of this client's state at the last join point, for sync opinions. */
    public static long lastJoinPointHash;
    public static int lastJoinPointTimer = -1;

    private JoinPoints() {
    }

    public static void createJoinPoint(int atTimer) {
        MultiplayerSession session = Multiplayer.session;
        setFrozen(true);
        SaveTransfer.Capture capture = SaveTransfer.capture();
        lastJoinPointTimer = atTimer;
        lastJoinPointHash = capture.rawHash;
        MpLog.info("join point at " + atTimer + ": " + capture.saveBytes.length
                + "b, raw hash " + Long.toHexString(capture.rawHash));

        if (session.isHost) {
            ByteWriter w = new ByteWriter(capture.saveBytes.length + 16);
            w.writeInt(atTimer);
            w.writeBytes(capture.saveBytes);
            session.connection.send(Packets.CLIENT_WORLD_DATA_UPLOAD, w);
        }

        syxmp.ui.MpStatus.set("creating join point...");
        TickController.yieldForReload = true;
        SaveTransfer.apply(capture.saveBytes, () -> {
            setFrozen(false);
            syxmp.ui.MpStatus.set("in game");
            MpLog.info("join point reload complete at timer " + TickController.timer);
        });
    }

    private static void setFrozen(boolean frozen) {
        MultiplayerSession session = Multiplayer.session;
        if (session == null || session.connection == null)
            return;
        ByteWriter w = new ByteWriter(1);
        w.writeBool(frozen);
        session.connection.send(Packets.CLIENT_FREEZE, w);
    }
}
