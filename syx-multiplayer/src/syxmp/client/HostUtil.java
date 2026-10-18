package syxmp.client;

import java.io.IOException;
import java.security.SecureRandom;

import game.VERSION;
import syxmp.client.state.ClientPlayingState;
import syxmp.net.ConnectionStateEnum;
import syxmp.net.LocalConnection;
import syxmp.server.MultiplayerServer;
import syxmp.server.ServerPlayer;
import syxmp.server.ServerSettings;
import syxmp.server.TcpServerTransport;
import syxmp.server.state.ServerPlayingState;
import syxmp.util.MpLog;

/**
 * Turns the current singleplayer game into a hosted multiplayer session:
 * in-process server + LocalConnection pair, with this client installed
 * directly in the playing state (the host never downloads the world).
 * Port of Multiplayer.Client.HostUtil.
 */
public final class HostUtil {

    private HostUtil() {
    }

    /** Call on the updater thread, with a game loaded. */
    public static void hostServer(String username, ServerSettings settings) {
        if (Multiplayer.active()) {
            MpLog.err("already in a multiplayer session");
            return;
        }
        MpLog.info("hosting as " + username);

        MultiplayerSession session = new MultiplayerSession();
        session.username = username;
        session.isHost = true;
        session.playerId = 0;
        session.rngSeed = new SecureRandom().nextLong();

        // Session must exist before capture so the script savable records
        // the multiplayer clock state into the save.
        Multiplayer.session = session;
        TickController.setClock(0, 0);
        TickController.speed = 0; // start paused; unpausing is a synced command
        syxmp.desync.SyncCoordinator.reset();

        SaveTransfer.Capture capture;
        try {
            capture = SaveTransfer.capture();
        } catch (RuntimeException e) {
            Multiplayer.session = null;
            throw e;
        }

        MultiplayerServer server = new MultiplayerServer(settings);
        server.gameVersion = VERSION.VERSION;
        server.startFrom(0, capture.saveBytes);

        LocalConnection[] pair = LocalConnection.paired(MainThread::enqueue, server.queue::enqueue);
        LocalConnection clientEnd = pair[0];
        LocalConnection serverEnd = pair[1];
        clientEnd.username = username;
        serverEnd.username = username;
        session.connection = clientEnd;

        // Install both ends directly into the playing state — the host skips
        // the join handshake, like RW-MP's in-memory host connection.
        clientEnd.changeState(ConnectionStateEnum.CLIENT_PLAYING,
                new ClientPlayingState(clientEnd, session));
        server.queue.enqueue(() -> {
            ServerPlayer hostPlayer = server.playerManager.createPlayer(serverEnd);
            hostPlayer.type = ServerPlayer.Type.HOST;
            serverEnd.changeState(ConnectionStateEnum.SERVER_PLAYING,
                    new ServerPlayingState(server, hostPlayer));
            server.playerManager.onPlaying(hostPlayer);
        });

        try {
            TcpServerTransport transport = new TcpServerTransport(server);
            transport.start();
        } catch (IOException e) {
            MpLog.err("failed to open server port " + settings.port, e);
            Multiplayer.session = null;
            return;
        }

        Multiplayer.localServer = server;
        server.start();
        MpLog.info("hosting on port " + settings.port);
        syxmp.steam.SteamMp.hostStarted(server);
    }
}
