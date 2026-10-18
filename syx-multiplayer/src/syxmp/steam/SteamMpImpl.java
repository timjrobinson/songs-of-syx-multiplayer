package syxmp.steam;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamAuth;
import com.codedisaster.steamworks.SteamAuthTicket;
import com.codedisaster.steamworks.SteamMatchmakingCallback;
import com.codedisaster.steamworks.SteamNetworkingCallback;
import com.codedisaster.steamworks.SteamUserCallback;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamMatchmaking;
import com.codedisaster.steamworks.SteamNetworking;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;

import syxmp.client.ClientConnect;
import syxmp.client.MainThread;
import syxmp.client.Multiplayer;
import syxmp.common.MpDisconnectReason;
import syxmp.common.MpVersion;
import syxmp.net.ConnectionStateEnum;
import syxmp.server.MultiplayerServer;
import syxmp.server.ServerPlayer;
import syxmp.server.state.ServerJoiningState;
import syxmp.util.MpLog;

/**
 * The steamworks4j side of Steam integration. Everything runs on the game
 * thread: pump() dispatches Steam callbacks and drains P2P packets once per
 * frame (menu and in-game). The game ships steamworks4j + natives in its
 * jar but never initializes SteamAPI in-process (achievements run in a child
 * process), so this is the only SteamAPI user in the JVM.
 *
 * Hosting: a friends-only lobby + rich presence "connect" string, so friends
 * see "Join Game" in Steam; joiners enter the lobby, find its owner and open
 * a P2P connection to the host's in-process server.
 */
final class SteamMpImpl {

    static final String LOBBY_NAME = "name";
    static final String LOBBY_VERSION = "version";
    static final String LOBBY_HOST = "host";

    private static SteamMpImpl instance;
    private static boolean initTried;

    final SteamUser user;
    final SteamFriends friends;
    final SteamMatchmaking matchmaking;
    final SteamNetworking networking;
    final SteamUtils utils;
    final int appId;

    /** Live P2P connections by remote SteamID handle (both roles). */
    private final Map<Long, SteamConnection> connections = new HashMap<>();
    private MultiplayerServer hostedServer;
    private SteamID lobby;
    private String lobbyName = "";
    /** Lobby we are joining (client), pending onLobbyEnter. */
    private SteamID joiningLobby;
    private String joinUsername;
    private final SteamNetworking.P2PSessionState sessionState = new SteamNetworking.P2PSessionState();
    private ByteBuffer readBuf = ByteBuffer.allocateDirect(1 << 16);
    private final int[] sizeOut = new int[1];

    static SteamMpImpl get() {
        if (!initTried) {
            initTried = true;
            instance = tryInit();
        }
        return instance;
    }

    private static SteamMpImpl tryInit() {
        try {
            SteamAPI.loadLibraries();
            if (!SteamAPI.init()) {
                MpLog.info("steam: not available (not started through Steam, or Steam not running)");
                return null;
            }
            SteamMpImpl impl = new SteamMpImpl();
            MpLog.info("steam: initialized as " + impl.friends.getPersonaName() + " (app " + impl.appId + ")");
            return impl;
        } catch (SteamException | UnsatisfiedLinkError | RuntimeException e) {
            MpLog.info("steam: unavailable: " + e);
            return null;
        }
    }

    private SteamMpImpl() {
        user = new SteamUser(new SteamUserCallback() {
            @Override public void onAuthSessionTicket(SteamAuthTicket t, SteamResult r) { }
            @Override public void onValidateAuthTicket(SteamID a, SteamAuth.AuthSessionResponse b, SteamID c) { }
            @Override public void onMicroTxnAuthorization(int a, long b, boolean c) { }
            @Override public void onEncryptedAppTicket(SteamResult r) { }
        });
        friends = new SteamFriends(friendsCallback);
        matchmaking = new SteamMatchmaking(matchmakingCallback);
        networking = new SteamNetworking(networkingCallback);
        utils = new SteamUtils(new SteamUtilsCallback() {
            @Override
            public void onSteamShutdown() {
                MpLog.info("steam: shutdown");
            }
        });
        appId = utils.getAppID();
        networking.allowP2PPacketRelay(true);
    }

    // ---- per frame ----------------------------------------------------------

    void pump() {
        SteamAPI.runCallbacks();
        readPackets();
    }

    private void readPackets() {
        int guard = 0;
        while (networking.isP2PPacketAvailable(SteamConnection.CHANNEL, sizeOut) && guard++ < 10_000) {
            int size = sizeOut[0];
            if (readBuf.capacity() < size)
                readBuf = ByteBuffer.allocateDirect(Integer.highestOneBit(size) << 1);
            readBuf.clear();
            SteamID from = new SteamID();
            int n;
            try {
                n = networking.readP2PPacket(from, readBuf, SteamConnection.CHANNEL);
            } catch (SteamException e) {
                MpLog.err("steam: read failed", e);
                return;
            }
            if (n <= 0)
                return;
            byte[] frame = new byte[n];
            readBuf.limit(n);
            readBuf.get(frame);
            SteamConnection conn = connections.get(SteamID.getNativeHandle(from));
            if (conn == null)
                conn = registerIncoming(from);
            if (conn != null)
                conn.deliver(frame);
        }
    }

    /** First packet from an unknown peer while hosting: a new player. */
    private SteamConnection registerIncoming(SteamID from) {
        MultiplayerServer server = hostedServer;
        if (server == null || !server.isRunning()) {
            networking.closeP2PSessionWithUser(from);
            return null;
        }
        SteamConnection conn = new SteamConnection(networking, from, server.queue::enqueue);
        connections.put(conn.remoteHandle, conn);
        server.queue.enqueue(() -> {
            if (server.playerManager.players().size() >= server.settings.maxPlayers) {
                conn.close(MpDisconnectReason.SERVER_FULL, null);
                return;
            }
            ServerPlayer player = server.playerManager.createPlayer(conn);
            conn.onClosed = (reason, info) -> server.playerManager.onDisconnected(player, reason);
            conn.changeState(ConnectionStateEnum.SERVER_JOINING, new ServerJoiningState(server, player));
        });
        MpLog.info("steam: incoming connection from " + from.getAccountID());
        return conn;
    }

    static void onConnectionClosed(SteamConnection c) {
        if (instance != null)
            instance.connections.remove(c.remoteHandle);
    }

    // ---- hosting --------------------------------------------------------------

    void hostStarted(MultiplayerServer server) {
        hostedServer = server;
        lobbyName = server.settings.gameName;
        if (lobby == null)
            matchmaking.createLobby(SteamMatchmaking.LobbyType.FriendsOnly, server.settings.maxPlayers);
    }

    void sessionEnded() {
        hostedServer = null;
        joiningLobby = null;
        for (SteamConnection c : new ArrayList<>(connections.values()))
            c.close(MpDisconnectReason.SERVER_CLOSED, null);
        connections.clear();
        if (lobby != null) {
            matchmaking.leaveLobby(lobby);
            lobby = null;
        }
        friends.clearRichPresence();
    }

    // ---- joining --------------------------------------------------------------

    void joinLobby(long lobbyId, String username) {
        if (Multiplayer.active()) {
            MpLog.err("steam: already in a session");
            return;
        }
        joiningLobby = SteamID.createFromNativeHandle(lobbyId);
        joinUsername = username;
        syxmp.ui.MpStatus.set("joining steam lobby...");
        matchmaking.joinLobby(joiningLobby);
    }

    private void connectToOwner(SteamID lobbyId) {
        SteamID owner = matchmaking.getLobbyOwner(lobbyId);
        if (!owner.isValid()) {
            syxmp.ui.MpStatus.fail("steam lobby has no owner");
            matchmaking.leaveLobby(lobbyId);
            return;
        }
        if (SteamID.getNativeHandle(owner) == SteamID.getNativeHandle(user.getSteamID())) {
            syxmp.ui.MpStatus.fail("that is your own lobby");
            return;
        }
        lobby = lobbyId;
        SteamConnection conn = new SteamConnection(networking, owner, MainThread::enqueue);
        connections.put(conn.remoteHandle, conn);
        MpLog.info("steam: connecting to host " + friends.getFriendPersonaName(owner));
        ClientConnect.attach(conn, joinUsername, "");
        conn.onClosed = (reason, info) -> ClientConnect.onConnectionLost(conn, reason, info);
    }

    List<SteamMp.FriendLobby> friendLobbies() {
        List<SteamMp.FriendLobby> res = new ArrayList<>();
        int n = friends.getFriendCount(SteamFriends.FriendFlags.Immediate);
        SteamFriends.FriendGameInfo info = new SteamFriends.FriendGameInfo();
        for (int i = 0; i < n; i++) {
            SteamID f = friends.getFriendByIndex(i, SteamFriends.FriendFlags.Immediate);
            if (!friends.getFriendGamePlayed(f, info))
                continue;
            if ((int) info.getGameID() != appId)
                continue;
            SteamID l = info.getSteamIDLobby();
            if (l == null || !l.isValid())
                continue;
            String name = matchmaking.getLobbyData(l, LOBBY_NAME);
            if (name == null || name.isEmpty()) {
                matchmaking.requestLobbyData(l);
                name = "(game)";
            }
            res.add(new SteamMp.FriendLobby(SteamID.getNativeHandle(l), friends.getFriendPersonaName(f), name));
        }
        return res;
    }

    String personaName() {
        return friends.getPersonaName();
    }

    // ---- callbacks ------------------------------------------------------------

    private final SteamMatchmakingCallback matchmakingCallback = new SteamMatchmakingCallback() {
        @Override public void onFavoritesListChanged(int a, int b, int c, int d, int e, boolean f, int g) { }
        @Override public void onLobbyInvite(SteamID a, SteamID b, long c) { }
        @Override public void onLobbyDataUpdate(SteamID a, SteamID b, boolean c) { }
        @Override public void onLobbyChatMessage(SteamID a, SteamID b, SteamMatchmaking.ChatEntryType c, int d) { }
        @Override public void onLobbyGameCreated(SteamID a, SteamID b, int c, short d) { }
        @Override public void onLobbyMatchList(int a) { }
        @Override public void onLobbyKicked(SteamID a, SteamID b, boolean c) { }
        @Override public void onFavoritesListAccountsUpdated(SteamResult r) { }

        @Override
        public void onLobbyCreated(SteamResult result, SteamID steamIDLobby) {
            if (result != SteamResult.OK) {
                MpLog.err("steam: lobby creation failed: " + result);
                return;
            }
            lobby = steamIDLobby;
            matchmaking.setLobbyData(lobby, LOBBY_NAME, lobbyName);
            matchmaking.setLobbyData(lobby, LOBBY_VERSION, MpVersion.VERSION);
            matchmaking.setLobbyData(lobby, LOBBY_HOST, friends.getPersonaName());
            matchmaking.setLobbyJoinable(lobby, true);
            String connect = "+connect_lobby " + SteamID.getNativeHandle(lobby);
            friends.setRichPresence("connect", connect);
            friends.setRichPresence("status", "Hosting: " + lobbyName);
            MpLog.info("steam: lobby created " + SteamID.getNativeHandle(lobby) + " (friends can join)");
        }

        @Override
        public void onLobbyEnter(SteamID steamIDLobby, int chatPermissions, boolean blocked,
                SteamMatchmaking.ChatRoomEnterResponse response) {
            if (joiningLobby == null || SteamID.getNativeHandle(steamIDLobby) != SteamID.getNativeHandle(joiningLobby))
                return; // our own lobby as host
            joiningLobby = null;
            if (response != SteamMatchmaking.ChatRoomEnterResponse.Success) {
                syxmp.ui.MpStatus.fail("could not enter lobby: " + response);
                return;
            }
            connectToOwner(steamIDLobby);
        }

        @Override
        public void onLobbyChatUpdate(SteamID steamIDLobby, SteamID steamIDUserChanged,
                SteamID steamIDMakingChange, SteamMatchmaking.ChatMemberStateChange stateChange) {
            if (stateChange != SteamMatchmaking.ChatMemberStateChange.Entered) {
                SteamConnection c = connections.get(SteamID.getNativeHandle(steamIDUserChanged));
                if (c != null)
                    c.close(MpDisconnectReason.NET_FAILED, "left lobby");
            }
        }
    };

    private final SteamNetworkingCallback networkingCallback = new SteamNetworkingCallback() {
        @Override
        public void onP2PSessionRequest(SteamID steamIDRemote) {
            // Hosts accept anyone (the join handshake validates); clients only the host.
            if (hostedServer != null || connections.containsKey(SteamID.getNativeHandle(steamIDRemote)))
                networking.acceptP2PSessionWithUser(steamIDRemote);
        }

        @Override
        public void onP2PSessionConnectFail(SteamID steamIDRemote, SteamNetworking.P2PSessionError error) {
            SteamConnection c = connections.get(SteamID.getNativeHandle(steamIDRemote));
            if (c != null)
                c.close(MpDisconnectReason.NET_FAILED, "steam p2p: " + error);
        }
    };

    private final SteamFriendsCallback friendsCallback = new SteamFriendsCallback() {
        @Override public void onSetPersonaNameResponse(boolean a, boolean b, SteamResult c) { }
        @Override public void onPersonaStateChange(SteamID a, SteamFriends.PersonaChange b) { }
        @Override public void onGameOverlayActivated(boolean a) { }
        @Override public void onAvatarImageLoaded(SteamID a, int b, int c, int d) { }
        @Override public void onFriendRichPresenceUpdate(SteamID a, int b) { }
        @Override public void onGameServerChangeRequested(String a, String b) { }

        @Override
        public void onGameLobbyJoinRequested(SteamID steamIDLobby, SteamID steamIDFriend) {
            requestJoin(SteamID.getNativeHandle(steamIDLobby));
        }

        @Override
        public void onGameRichPresenceJoinRequested(SteamID steamIDFriend, String connect) {
            if (connect != null && connect.startsWith("+connect_lobby ")) {
                try {
                    requestJoin(Long.parseLong(connect.substring("+connect_lobby ".length()).trim()));
                } catch (NumberFormatException e) {
                    MpLog.err("steam: bad connect string " + connect);
                }
            }
        }
    };

    /** A friend invited us / we clicked Join Game in the overlay. */
    private void requestJoin(long lobbyId) {
        if (Multiplayer.active()) {
            try {
                game.GAME.Notify("SyxMP: leave your current session to join the invite");
            } catch (RuntimeException e) {
                // no game
            }
            return;
        }
        if (view.main.VIEW.existTemp()) {
            // In a single-player game: the join loads the host's world over it.
            MpLog.info("steam: join requested from in-game");
        }
        joinLobby(lobbyId, syxmp.ui.MpSettings.defaultUsername());
    }
}
