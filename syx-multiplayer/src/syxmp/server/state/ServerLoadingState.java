package syxmp.server.state;

import syxmp.common.Packets;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.net.ConnectionStateEnum;
import syxmp.net.MpConnectionState;
import syxmp.server.MultiplayerServer;
import syxmp.server.ServerPlayer;
import syxmp.util.MpLog;

/**
 * Waits for a usable join point, ships the world blob + command log, then
 * waits for the client to finish loading. Port of ServerLoadingState.
 */
public final class ServerLoadingState extends MpConnectionState {

    private final MultiplayerServer server;
    private final ServerPlayer player;

    public ServerLoadingState(MultiplayerServer server, ServerPlayer player) {
        super(player.conn);
        this.server = server;
        this.player = player;
    }

    @Override
    public void onEnter() {
        // Runs on the server thread. When the join point is ready (possibly
        // immediately), send the world; the completion is queued back onto
        // the server thread.
        server.worldData.waitJoinPoint().thenRun(
                () -> server.queue.enqueue(this::sendWorldData));
    }

    private void sendWorldData() {
        if (connection.stateEnum() != ConnectionStateEnum.SERVER_LOADING)
            return; // disconnected meanwhile
        connection.send(Packets.SERVER_WORLD_DATA_START);

        ByteWriter w = new ByteWriter(1 << 20);
        w.writeInt(server.gameTimer());
        w.writeLong(server.commands.sentCmds());
        w.writeBool(server.freezeManager.frozen());
        w.writeInt(server.worldData.savedGameTimer);
        w.writeBytes(server.worldData.savedGame);
        w.writeInt(server.worldData.cmds.size());
        for (byte[] cmd : server.worldData.cmds)
            w.writeBytes(cmd);
        connection.send(Packets.SERVER_WORLD_DATA, w);
        MpLog.info("sent world data to " + player.username()
                + " (" + server.worldData.savedGame.length + "b save, "
                + server.worldData.cmds.size() + " cmds)");
    }

    @Override
    public boolean handle(Packets packet, ByteReader data) {
        switch (packet) {
            case CLIENT_WORLD_READY -> {
                connection.changeState(ConnectionStateEnum.SERVER_PLAYING,
                        new ServerPlayingState(server, player));
                server.playerManager.onPlaying(player);
            }
            // The client enters its playing state as soon as the world blob
            // arrives (its clock runs during catch-up), so these can show up
            // before WORLD_READY — they must not be fatal (live-test finding).
            case CLIENT_KEEP_ALIVE -> {
                int id = data.readInt();
                ByteWriter w = new ByteWriter(4);
                w.writeInt(id);
                connection.send(Packets.SERVER_KEEP_ALIVE, w);
                player.lastKeepAliveMs = System.currentTimeMillis();
            }
            case CLIENT_FRAME_TIME -> {
                player.frameTimeMs = data.readFloat();
                player.lastReportedTimer = data.readInt();
                player.lastReportAtTimer = server.gameTimer();
            }
            case CLIENT_SYNC_INFO, CLIENT_CURSOR -> data.readRemaining();
            default -> {
                return false;
            }
        }
        return true;
    }
}
