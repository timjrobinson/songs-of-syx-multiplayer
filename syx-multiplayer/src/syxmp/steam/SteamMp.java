package syxmp.steam;

import java.util.Collections;
import java.util.List;

/**
 * Steam integration facade (lobbies, rich presence, friends, P2P transport).
 * Everything here degrades to "not available" when the game was not started
 * through Steam. Implementation lands in SteamMpImpl; this facade keeps the
 * rest of the mod free of steamworks4j types.
 */
public final class SteamMp {

    public static final class FriendLobby {
        public final long lobbyId;
        public final String friendName;
        public final String gameName;

        public FriendLobby(long lobbyId, String friendName, String gameName) {
            this.lobbyId = lobbyId;
            this.friendName = friendName;
            this.gameName = gameName;
        }
    }

    private SteamMp() {
    }

    public static boolean available() {
        return false;
    }

    public static String personaName() {
        return null;
    }

    /** Pump Steam callbacks; call once per frame from the game thread. */
    public static void pump() {
    }

    public static List<FriendLobby> friendLobbies() {
        return Collections.emptyList();
    }

    public static void joinLobby(long lobbyId, String username) {
        syxmp.util.MpLog.err("steam not available");
    }
}
