package syxmp.server;

import syxmp.common.CommandType;
import syxmp.common.Packets;
import syxmp.common.ScheduledCommand;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;

/**
 * Stamps incoming client commands with the execution tick and rebroadcasts
 * them reliably to all playing players. Port of Multiplayer.Common.CommandHandler.
 * sentCmds is half of the lockstep interlock: clients refuse to advance past
 * a tick window until they've received this many commands.
 */
public final class CommandHandler {

    private final MultiplayerServer server;
    private long sentCmds;

    public CommandHandler(MultiplayerServer server) {
        this.server = server;
    }

    public long sentCmds() {
        return sentCmds;
    }

    /** Called from a playing player's CLIENT_COMMAND packet. */
    public void handleClientCommand(ServerPlayer source, CommandType type, byte[] data) {
        if (type == CommandType.CREATE_JOIN_POINT || type == CommandType.INIT_PLAYER_DATA) {
            // Server-issued only
            MpLog.err("player " + source.username() + " sent restricted command " + type);
            return;
        }
        send(type, source.id, data);
    }

    /** Stamp with the current gameTimer and broadcast. Server thread only. */
    public void send(CommandType type, int playerId, byte[] data) {
        ScheduledCommand cmd = new ScheduledCommand(type, server.gameTimer(), playerId, data);
        byte[] serialized = cmd.serialize();
        server.worldData.addCommand(serialized);
        sentCmds++;

        ByteWriter w = new ByteWriter(serialized.length + 4);
        w.writeRaw(serialized);
        for (ServerPlayer p : server.playerManager.playingPlayers())
            p.conn.send(Packets.SERVER_COMMAND, w.toArray());
    }

    public void sendJoinPointCommand() {
        send(CommandType.CREATE_JOIN_POINT, ScheduledCommand.NO_PLAYER, new byte[0]);
    }

    public void pauseAll() {
        send(CommandType.PAUSE_ALL, ScheduledCommand.NO_PLAYER, new byte[0]);
    }
}
