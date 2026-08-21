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
                            MpLog.err("connection lost: " + reason);
                            if (Multiplayer.session == session)
                                Multiplayer.session = null;
                        };
                        conn.changeState(ConnectionStateEnum.CLIENT_JOINING,
                                new ClientJoiningState(conn, session, password));
                        conn.startReading();
                    } catch (IOException e) {
                        MpLog.err("failed to set up connection", e);
                        Multiplayer.session = null;
                    }
                });
            } catch (IOException e) {
                MpLog.err("failed to connect to " + address + ":" + port, e);
                Multiplayer.session = null;
            }
        }, "SyxMP-Connect");
        t.setDaemon(true);
        t.start();
    }
}
