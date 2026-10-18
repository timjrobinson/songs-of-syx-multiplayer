package syxmp.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import syxmp.client.state.ClientJoiningState;
import syxmp.net.ConnectionStateEnum;
import syxmp.net.TcpConnection;
import syxmp.util.MpLog;

/** Connects to a host and starts the join handshake. */
public final class ClientConnect {

    private ClientConnect() {
    }

    /**
     * Connect asynchronously. Current limitation: join from inside a running
     * game (any game) — packet handling is driven by the game's frame loop.
     */
    public static void connect(String address, int port, String username, String password) {
        if (Multiplayer.active()) {
            MpLog.err("already in a multiplayer session");
            return;
        }
        MultiplayerSession session = new MultiplayerSession();
        session.username = username;
        session.isHost = false;
        Multiplayer.session = session;
        syxmp.ui.MpStatus.set("connecting to " + address + ":" + port + "...");

        Thread t = new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(address, port), 10_000);
                MainThread.enqueue(() -> {
                    try {
                        TcpConnection conn = new TcpConnection(socket, MainThread::enqueue);
                        conn.username = username;
                        session.connection = conn;
                        conn.onClosed = (reason, info) -> {
                            MpLog.err("connection lost: " + reason + (info != null ? " (" + info + ")" : ""));
                            if (Multiplayer.session == session) {
                                Multiplayer.session = null;
                                syxmp.ui.MpStatus.fail("disconnected: " + reason
                                        + (info != null ? " (" + info + ")" : ""));
                            }
                        };
                        conn.changeState(ConnectionStateEnum.CLIENT_JOINING,
                                new ClientJoiningState(conn, session, password));
                        conn.startReading();
                    } catch (IOException e) {
                        MpLog.err("failed to set up connection", e);
                        Multiplayer.session = null;
                        syxmp.ui.MpStatus.fail("connection failed: " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                MpLog.err("failed to connect to " + address + ":" + port, e);
                if (Multiplayer.session == session)
                    Multiplayer.session = null;
                syxmp.ui.MpStatus.fail("could not connect to " + address + ":" + port + ": " + e.getMessage());
            }
        }, "SyxMP-Connect");
        t.setDaemon(true);
        t.start();
    }

    /** Leave the current session (client or host). Safe to call when not connected. */
    public static void disconnect(String why) {
        MultiplayerSession session = Multiplayer.session;
        if (session == null)
            return;
        MpLog.info("leaving session: " + why);
        Multiplayer.session = null;
        if (session.connection != null)
            session.connection.close(syxmp.common.MpDisconnectReason.GENERIC_KEYED, why);
        if (Multiplayer.localServer != null) {
            Multiplayer.localServer.stop(why);
            Multiplayer.localServer = null;
        }
        TickController.simulatingTarget = -1;
        TickController.onSimulatingDone = null;
        Multiplayer.reloading = false;
    }
}
