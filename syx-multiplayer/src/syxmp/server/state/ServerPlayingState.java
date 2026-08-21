package syxmp.server.state;

import syxmp.common.CommandType;
import syxmp.common.Packets;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.net.MpConnectionState;
import syxmp.server.MultiplayerServer;
import syxmp.server.ServerPlayer;
import syxmp.util.MpLog;

/** Steady-state packet handling for a playing client. Port of ServerPlayingState. */
public final class ServerPlayingState extends MpConnectionState {

    private final MultiplayerServer server;
    private final ServerPlayer player;

    public ServerPlayingState(MultiplayerServer server, ServerPlayer player) {
        super(player.conn);
        this.server = server;
        this.player = player;
    }

    @Override
    public boolean handle(Packets packet, ByteReader data) {
        switch (packet) {
            case CLIENT_COMMAND -> {
                CommandType type = CommandType.of(data.readByte());
                byte[] payload = data.readBytes(1 << 16);
                server.commands.handleClientCommand(player, type, payload);
            }
            case CLIENT_FRAME_TIME -> {
                player.frameTimeMs = data.readFloat();
                player.lastReportedTimer = data.readInt();
                player.lastReportAtTimer = server.gameTimer();
            }
            case CLIENT_KEEP_ALIVE -> {
                int id = data.readInt();
                ByteWriter w = new ByteWriter(4);
                w.writeInt(id);
                connection.send(Packets.SERVER_KEEP_ALIVE, w);
                player.lastKeepAliveMs = System.currentTimeMillis();
            }
            case CLIENT_CHAT -> {
                String msg = data.readString(2048);
                if (msg == null || msg.isBlank())
                    return true;
                ByteWriter w = new ByteWriter();
                w.writeString(player.username());
                w.writeString(msg);
                byte[] out = w.toArray();
                for (ServerPlayer p : server.playerManager.playingPlayers())
                    p.conn.send(Packets.SERVER_CHAT, out);
            }
            case CLIENT_FREEZE -> {
                boolean frozen = data.readBool();
                server.freezeManager.setFrozen(player.id, frozen, System.currentTimeMillis());
                broadcastFreeze();
            }
            case CLIENT_WORLD_DATA_UPLOAD -> {
                // Host uploading the join-point save
                if (!player.isHost()) {
                    MpLog.err("non-host " + player.username() + " tried to upload world data");
                    return true;
                }
                int atTimer = data.readInt();
                byte[] save = data.readBytes();
                server.worldData.endJoinPointCreation(save, atTimer);
                MpLog.info("join point updated by host at timer " + atTimer
                        + " (" + save.length + "b)");
            }
            case CLIENT_SYNC_INFO -> {
                // Relay the reference client's opinion to everyone else
                byte[] blob = data.readRemaining();
                for (ServerPlayer p : server.playerManager.playingPlayers())
                    if (p != player)
                        p.conn.send(Packets.SERVER_SYNC_INFO, blob);
            }
            case CLIENT_DESYNCED -> {
                int atTimer = data.readInt();
                player.status = ServerPlayer.Status.DESYNCED;
                MpLog.err("player " + player.username() + " desynced at " + atTimer);
                if (server.settings.pauseOnDesync)
                    server.commands.pauseAll();
                server.playerManager.sendPlayerList();
            }
            case CLIENT_CURSOR -> {
                byte[] blob = data.readRemaining();
                ByteWriter w = new ByteWriter(blob.length + 8);
                w.writeInt(player.id);
                w.writeRaw(blob);
                byte[] out = w.toArray();
                for (ServerPlayer p : server.playerManager.playingPlayers())
                    if (p != player)
                        p.conn.send(Packets.SERVER_CURSOR, out);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void broadcastFreeze() {
        ByteWriter w = new ByteWriter(8);
        w.writeBool(server.freezeManager.frozen());
        w.writeInt(server.gameTimer());
        byte[] out = w.toArray();
        for (ServerPlayer p : server.playerManager.playingPlayers())
            p.conn.send(Packets.SERVER_FREEZE, out);
    }
}
