package syxmp.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import syxmp.common.MpDisconnectReason;
import syxmp.net.ConnectionStateEnum;
import syxmp.net.TcpConnection;
import syxmp.server.state.ServerJoiningState;
import syxmp.util.MpLog;

/** Accepts TCP clients and registers them with the server. */
public final class TcpServerTransport {

    private final MultiplayerServer server;
    private ServerSocket serverSocket;
    private volatile boolean closed;

    public TcpServerTransport(MultiplayerServer server) {
        this.server = server;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(server.settings.port);
        Thread t = new Thread(this::acceptLoop, "SyxMP-Accept");
        t.setDaemon(true);
        t.start();
        MpLog.info("listening on tcp port " + server.settings.port);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                server.queue.enqueue(() -> register(socket));
            } catch (IOException e) {
                if (!closed)
                    MpLog.err("accept failed", e);
                return;
            }
        }
    }

    /** Server thread. */
    private void register(Socket socket) {
        try {
            if (server.playerManager.players().size() >= server.settings.maxPlayers) {
                socket.close();
                return;
            }
            TcpConnection conn = new TcpConnection(socket, server.queue::enqueue);
            ServerPlayer player = server.playerManager.createPlayer(conn);
            conn.onClosed = (reason, info) ->
                    server.playerManager.onDisconnected(player, reason);
            conn.changeState(ConnectionStateEnum.SERVER_JOINING,
                    new ServerJoiningState(server, player));
            conn.startReading();
            MpLog.info("accepted connection from " + socket.getRemoteSocketAddress());
        } catch (IOException e) {
            MpLog.err("failed to register connection", e);
        }
    }

    public void close() {
        closed = true;
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (IOException e) {
            // best effort
        }
    }
}
