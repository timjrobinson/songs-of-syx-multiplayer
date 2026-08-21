package syxmp.client;

import syxmp.common.Packets;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;

/**
 * Executes the CREATE_JOIN_POINT command: every client captures its full
 * state at the same tick; the host uploads it as the new joining baseline.
 *
 * Deviation from RW-MP (documented): RimWorld clients save AND reload at a
 * join point (the reload scrubs cache drift). We capture without reloading,
 * and instead compare state hashes across clients as a deep desync check.
 */
public final class JoinPoints {

    /** Hash of this client's state at the last join point, for sync opinions. */
    public static long lastJoinPointHash;
    public static int lastJoinPointTimer = -1;

    private JoinPoints() {
    }

    public static void createJoinPoint(int atTimer) {
        MultiplayerSession session = Multiplayer.session;
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
    }
}
