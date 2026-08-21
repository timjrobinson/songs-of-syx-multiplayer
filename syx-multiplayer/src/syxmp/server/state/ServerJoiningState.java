package syxmp.server.state;

import syxmp.common.MpDisconnectReason;
import syxmp.common.MpVersion;
import syxmp.common.Packets;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.net.ConnectionStateEnum;
import syxmp.net.MpConnectionState;
import syxmp.server.MultiplayerServer;
import syxmp.server.ServerPlayer;
import syxmp.util.MpLog;

/**
 * Handshake: protocol -> username -> join data -> world request.
 * Port of Multiplayer.Common ServerJoiningState (the async/await script
 * becomes an explicit expected-packet sequence).
 */
public final class ServerJoiningState extends MpConnectionState {

    private final MultiplayerServer server;
    private final ServerPlayer player;
    private Packets expecting = Packets.CLIENT_PROTOCOL;

    public ServerJoiningState(MultiplayerServer server, ServerPlayer player) {
        super(player.conn);
        this.server = server;
        this.player = player;
    }

    @Override
    public boolean handle(Packets packet, ByteReader data) {
        if (packet != expecting) {
            connection.close(MpDisconnectReason.GENERIC_KEYED, "unexpected " + packet);
            return true;
        }
        switch (packet) {
            case CLIENT_PROTOCOL -> {
                int protocol = data.readInt();
                if (protocol != MpVersion.PROTOCOL) {
                    ByteWriter w = new ByteWriter();
                    w.writeInt(MpVersion.PROTOCOL);
                    w.writeString(MpVersion.VERSION);
                    connection.send(Packets.SERVER_DISCONNECT, disconnect(MpDisconnectReason.PROTOCOL, w));
                    connection.close(MpDisconnectReason.PROTOCOL, "protocol " + protocol);
                    return true;
                }
                ByteWriter w = new ByteWriter();
                w.writeBool(server.settings.hasPassword());
                connection.send(Packets.SERVER_PROTOCOL_OK, w);
                expecting = Packets.CLIENT_USERNAME;
            }
            case CLIENT_USERNAME -> {
                String username = data.readString(64);
                String password = data.readString(256);
                if (server.settings.hasPassword() && !server.settings.password.equals(password)) {
                    connection.close(MpDisconnectReason.BAD_GAME_PASSWORD, null);
                    return true;
                }
                String problem = server.playerManager.validateUsername(username);
                if (problem != null) {
                    connection.close(MpDisconnectReason.USERNAME_CHARS, problem);
                    return true;
                }
                connection.username = username;
                MpLog.info("player joining: " + username);
                connection.send(Packets.SERVER_USERNAME_OK);
                expecting = Packets.CLIENT_JOIN_DATA;
            }
            case CLIENT_JOIN_DATA -> {
                int gameVersion = data.readInt();
                String modVersion = data.readString(64);
                String mods = data.readString(1 << 16);
                boolean ok = gameVersion == server.gameVersion && MpVersion.VERSION.equals(modVersion);
                ByteWriter w = new ByteWriter();
                w.writeString(server.settings.gameName);
                w.writeInt(player.id);
                w.writeInt(server.gameVersion);
                w.writeString(MpVersion.VERSION);
                w.writeString(server.mods);
                w.writeBool(ok);
                connection.send(Packets.SERVER_JOIN_DATA, w);
                if (!ok) {
                    connection.close(MpDisconnectReason.VERSION_MISMATCH,
                            "game " + gameVersion + " mod " + modVersion + " mods " + mods);
                    return true;
                }
                expecting = Packets.CLIENT_WORLD_REQUEST;
            }
            case CLIENT_WORLD_REQUEST -> {
                connection.changeState(ConnectionStateEnum.SERVER_LOADING,
                        new ServerLoadingState(server, player));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    static byte[] disconnect(MpDisconnectReason reason, ByteWriter info) {
        ByteWriter w = new ByteWriter();
        w.writeByte(reason.ordinal());
        w.writeBytes(info == null ? new byte[0] : info.toArray());
        return w.toArray();
    }
}
