package syxmp.client.state;

import game.VERSION;
import syxmp.client.Multiplayer;
import syxmp.client.MultiplayerSession;
import syxmp.common.MpVersion;
import syxmp.common.Packets;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.net.ConnectionBase;
import syxmp.net.ConnectionStateEnum;
import syxmp.net.MpConnectionState;
import syxmp.util.MpLog;

/** Client half of the join handshake. Port of ClientJoiningState. */
public final class ClientJoiningState extends MpConnectionState {

    private final MultiplayerSession session;
    private final String password;

    public ClientJoiningState(ConnectionBase connection, MultiplayerSession session, String password) {
        super(connection);
        this.session = session;
        this.password = password;
    }

    @Override
    public void onEnter() {
        ByteWriter w = new ByteWriter(4);
        w.writeInt(MpVersion.PROTOCOL);
        connection.send(Packets.CLIENT_PROTOCOL, w);
        syxmp.ui.MpStatus.set("connected, joining...");
    }

    @Override
    public boolean handle(Packets packet, ByteReader data) {
        switch (packet) {
            case SERVER_PROTOCOL_OK -> {
                data.readBool(); // hasPassword
                ByteWriter w = new ByteWriter();
                w.writeString(session.username);
                w.writeString(password == null ? "" : password);
                connection.send(Packets.CLIENT_USERNAME, w);
            }
            case SERVER_USERNAME_OK -> {
                ByteWriter w = new ByteWriter();
                w.writeInt(VERSION.VERSION);
                w.writeString(MpVersion.VERSION);
                w.writeString(""); // mod list; filled in once mod enumeration is wired
                connection.send(Packets.CLIENT_JOIN_DATA, w);
            }
            case SERVER_JOIN_DATA -> {
                String gameName = data.readString(256);
                session.playerId = data.readInt();
                int gameVersion = data.readInt();
                String modVersion = data.readString(64);
                String mods = data.readString(1 << 16);
                boolean ok = data.readBool();
                MpLog.info("join data: game=" + gameName + " v" + gameVersion
                        + " mod " + modVersion + " ok=" + ok);
                if (!ok) {
                    syxmp.ui.MpStatus.fail("version mismatch: host runs game " + gameVersion
                            + " / mod " + modVersion);
                    return true; // server will disconnect us with the reason
                }
                syxmp.ui.MpStatus.set("waiting for the host's world...");
                connection.send(Packets.CLIENT_WORLD_REQUEST);
                connection.changeState(ConnectionStateEnum.CLIENT_LOADING,
                        new ClientLoadingState(connection, session));
            }
            case SERVER_DISCONNECT -> {
                int reason = data.readByte();
                MpLog.err("disconnected during join: " + syxmp.common.MpDisconnectReason.of(reason));
                syxmp.ui.MpStatus.fail("rejected by host: " + syxmp.common.MpDisconnectReason.of(reason));
                Multiplayer.session = null;
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
