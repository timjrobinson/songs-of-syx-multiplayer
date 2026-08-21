package syxmp.server;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import syxmp.common.MpDisconnectReason;
import syxmp.common.Packets;
import syxmp.net.ByteWriter;
import syxmp.net.ConnectionBase;
import syxmp.util.MpLog;

/** Tracks connected players and broadcasts the roster. Port of Multiplayer.Common.PlayerManager. */
public final class PlayerManager {

    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
    public static final int USERNAME_MIN = 3;
    public static final int USERNAME_MAX = 15;

    private final MultiplayerServer server;
    private final List<ServerPlayer> players = new ArrayList<>();
    private int nextPlayerId;

    public PlayerManager(MultiplayerServer server) {
        this.server = server;
    }

    public List<ServerPlayer> players() {
        return players;
    }

    public List<ServerPlayer> playingPlayers() {
        List<ServerPlayer> res = new ArrayList<>();
        for (ServerPlayer p : players)
            if (p.status == ServerPlayer.Status.PLAYING)
                res.add(p);
        return res;
    }

    public ServerPlayer host() {
        for (ServerPlayer p : players)
            if (p.isHost())
                return p;
        return null;
    }

    public String validateUsername(String username) {
        if (username == null || username.length() < USERNAME_MIN || username.length() > USERNAME_MAX)
            return "username length";
        if (!USERNAME_PATTERN.matcher(username).matches())
            return "username characters";
        for (ServerPlayer p : players)
            if (username.equals(p.username()))
                return "already online";
        return null;
    }

    public ServerPlayer createPlayer(ConnectionBase conn) {
        ServerPlayer p = new ServerPlayer(nextPlayerId++, conn);
        p.lastKeepAliveMs = System.currentTimeMillis();
        players.add(p);
        return p;
    }

    /** Called when a player finishes joining (world ready). */
    public void onPlaying(ServerPlayer p) {
        p.status = ServerPlayer.Status.PLAYING;
        MpLog.info("player " + p.username() + " is now playing");
        sendPlayerList();
    }

    public void onDisconnected(ServerPlayer p, MpDisconnectReason reason) {
        if (!players.remove(p))
            return;
        MpLog.info("player " + p.username() + " disconnected: " + reason);
        server.freezeManager.onPlayerLeft();
        sendPlayerList();
        if (p.isHost())
            server.stop("host left");
    }

    public void sendPlayerList() {
        ByteWriter w = new ByteWriter();
        List<ServerPlayer> playing = playingPlayers();
        w.writeInt(playing.size());
        for (ServerPlayer p : playing)
            p.writeListEntry(w);
        byte[] data = w.toArray();
        for (ServerPlayer p : playing)
            p.conn.send(Packets.SERVER_PLAYER_LIST, data);
    }
}
