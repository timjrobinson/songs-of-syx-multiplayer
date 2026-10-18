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
    private boolean desyncDumped;

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
                java.util.Set<Integer> before = new java.util.HashSet<>();
                java.util.Map<Integer, String> names = new java.util.HashMap<>();
                for (MultiplayerSession.PlayerListEntry e : session.players) {
                    before.add(e.id);
                    names.put(e.id, e.username);
                }
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
                    if (e.status == 2 && e.id != session.playerId && !desyncDumped) {
                        desyncDumped = true;
                        syxmp.dev.RndTrace.dumpRing("player " + e.username + " reported desync");
                    }
                    if (!before.remove(e.id) && e.id != session.playerId && !before.isEmpty())
                        notify(e.username + " joined the game");
                }
                for (int gone : before)
                    notify(names.getOrDefault(gone, "a player") + " left the game");
            }
            case SERVER_SYNC_INFO -> syxmp.desync.SyncCoordinator.addRemoteOpinion(
                    syxmp.desync.ClientSyncOpinion.deserialize(data));
            case SERVER_CURSOR -> syxmp.ui.MpCursors.receive(data);
            case SERVER_NOTIFICATION -> {
                String text = data.readString(2048);
                MpLog.info("[server] " + text);
                ChatLog.add("server", text);
            }
            case SERVER_DISCONNECT -> {
                int reason = data.readByte();
                MpLog.err("disconnected by server: " + MpDisconnectReason.of(reason));
                Multiplayer.session = null;
                syxmp.ui.MpStatus.fail("disconnected by host: " + MpDisconnectReason.of(reason));
                notify("disconnected: " + MpDisconnectReason.of(reason));
                connection.close(MpDisconnectReason.of(reason), null);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void notify(String s) {
        MpLog.info(s);
        ChatLog.add("*", s);
        try {
            game.GAME.Notify(s);
        } catch (RuntimeException e) {
            // no game yet
        }
    }

    /** Chat history shown by the in-game panel and HUD. */
    public static final class ChatLog {
        public static final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();

        public static synchronized void add(String from, String msg) {
            lines.add(from + ": " + msg);
            while (lines.size() > 100)
                lines.poll();
        }
    }
}
