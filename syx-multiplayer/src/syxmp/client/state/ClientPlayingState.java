package syxmp.client.state;

import syxmp.client.Multiplayer;
import syxmp.client.MultiplayerSession;
import syxmp.client.TickController;
import syxmp.common.MpDisconnectReason;
import syxmp.common.Packets;
import syxmp.common.ScheduledCommand;
import syxmp.net.ByteReader;
import syxmp.net.ConnectionBase;
import syxmp.net.MpConnectionState;
import syxmp.util.MpLog;

/** Steady-state client packet handling. Port of ClientPlayingState. */
public final class ClientPlayingState extends MpConnectionState {

    private final MultiplayerSession session;

    public ClientPlayingState(ConnectionBase connection, MultiplayerSession session) {
        super(connection);
        this.session = session;
    }

    @Override
    public boolean handle(Packets packet, ByteReader data) {
        switch (packet) {
            case SERVER_COMMAND -> {
                ScheduledCommand cmd = ScheduledCommand.deserialize(data);
                cmd.issuedBySelf = cmd.playerId == session.playerId;
                session.scheduleCommand(cmd);
            }
            case SERVER_TIME_CONTROL -> {
                session.remoteTickUntil = data.readInt();
                session.remoteSentCmds = data.readLong();
                TickController.msPerTimerUnit = data.readFloat();
                session.processTimeControl();
            }
            case SERVER_KEEP_ALIVE -> {
                int id = data.readInt();
                connection.latencyMs = (int) (System.currentTimeMillis() - session.keepAliveSentAt(id));
            }
            case SERVER_FREEZE -> {
                boolean frozen = data.readBool();
                data.readInt(); // atTimer
                TickController.frozen = frozen;
            }
            case SERVER_CHAT -> {
                String from = data.readString(64);
                String msg = data.readString(2048);
                MpLog.info("[chat] " + from + ": " + msg);
                ChatLog.add(from, msg);
            }
            case SERVER_PLAYER_LIST -> {
                session.players.clear();
                int n = data.readInt();
                for (int i = 0; i < n; i++) {
                    MultiplayerSession.PlayerListEntry e = new MultiplayerSession.PlayerListEntry();
                    e.id = data.readInt();
                    e.username = data.readString(64);
                    e.type = data.readByte();
                    e.status = data.readByte();
                    e.latency = data.readInt();
                    session.players.add(e);
                }
            }
            case SERVER_SYNC_INFO -> syxmp.desync.SyncCoordinator.addRemoteOpinion(
                    syxmp.desync.ClientSyncOpinion.deserialize(data));
            case SERVER_CURSOR -> {
                // Other players' cursors; UI overlay not implemented yet
                data.readRemaining();
            }
            case SERVER_NOTIFICATION -> MpLog.info("[server] " + data.readString(2048));
            case SERVER_DISCONNECT -> {
                int reason = data.readByte();
                MpLog.err("disconnected by server: " + MpDisconnectReason.of(reason));
                Multiplayer.session = null;
                connection.close(MpDisconnectReason.of(reason), null);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Minimal chat buffer until a real UI exists. */
    public static final class ChatLog {
        public static final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();

        static synchronized void add(String from, String msg) {
            lines.add(from + ": " + msg);
            while (lines.size() > 100)
                lines.poll();
        }
    }
}
