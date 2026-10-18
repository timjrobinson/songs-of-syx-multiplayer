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
        MultiplayerSession session = newSession(username);
        session.joinAddress = address;
        session.joinPort = port;
        session.joinPassword = password;
        syxmp.ui.MpStatus.set("connecting to " + address + ":" + port + "...");
        MpLog.info("connecting to " + address + ":" + port + " as " + username);

        Thread t = new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(address, port), 10_000);
                MainThread.enqueue(() -> {
                    if (Multiplayer.session != session)
                        return; // cancelled meanwhile
                    try {
                        TcpConnection conn = new TcpConnection(socket, MainThread::enqueue);
                        conn.onClosed = (reason, info) -> onConnectionLost(conn, reason, info);
                        start(conn, session, password);
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

    /** Join through an already-open transport connection (Steam P2P). Game thread. */
    public static void attach(syxmp.net.ConnectionBase conn, String username, String password) {
        if (Multiplayer.active()) {
            MpLog.err("already in a multiplayer session");
            return;
        }
        start(conn, newSession(username), password);
    }

    private static MultiplayerSession newSession(String username) {
        MultiplayerSession session = new MultiplayerSession();
        session.username = username;
        session.isHost = false;
        Multiplayer.session = session;
        return session;
    }

    private static void start(syxmp.net.ConnectionBase conn, MultiplayerSession session, String password) {
        conn.username = session.username;
        session.connection = conn;
        conn.changeState(ConnectionStateEnum.CLIENT_JOINING,
                new ClientJoiningState(conn, session, password));
    }

    /** Transport callback: the connection died. */
    public static void onConnectionLost(syxmp.net.ConnectionBase conn, syxmp.common.MpDisconnectReason reason, String info) {
        MpLog.err("connection lost: " + reason + (info != null ? " (" + info + ")" : ""));
        MultiplayerSession session = Multiplayer.session;
        if (session != null && session.connection == conn) {
            Multiplayer.session = null;
            syxmp.ui.MpStatus.fail("disconnected: " + reason + (info != null ? " (" + info + ")" : ""));
            syxmp.steam.SteamMp.sessionEnded();
        }
    }

    /**
     * Desync recovery (clients only): drop and rejoin the same host. The rejoin
     * creates a fresh join point, so everyone is back in lockstep from loaded
     * state within seconds — no manual action needed.
     */
    public static void resync(String why) {
        MultiplayerSession old = Multiplayer.session;
        if (old == null || old.isHost)
            return;
        String user = old.username;
        String addr = old.joinAddress;
        int port = old.joinPort;
        long lobby = old.joinLobby;
        String pw = old.joinPassword;
        disconnect(why);
        syxmp.ui.MpStatus.set("resyncing...");
        MpLog.info("resync: rejoining after " + why);
        if (lobby != 0)
            syxmp.steam.SteamMp.joinLobby(lobby, user);
        else if (addr != null)
            connect(addr, port, user, pw);
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
        syxmp.steam.SteamMp.sessionEnded();
    }
}
