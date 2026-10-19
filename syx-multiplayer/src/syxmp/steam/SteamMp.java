package syxmp.steam;

import java.util.Collections;
import java.util.List;

import syxmp.server.MultiplayerServer;
import syxmp.util.MpLog;

/**
 * Steam integration facade (lobbies, rich presence, friends, P2P transport).
 * Everything degrades to "not available" when the game was not started
 * through Steam. steamworks4j types stay inside SteamMpImpl so the rest of
 * the mod never loads them (and never fails if the natives are missing).
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

    private static boolean broken;
    private static Boolean enabled;

    private SteamMp() {
    }

    /**
     * On by default; {@code steam=0} in SyxMultiplayer.txt or
     * {@code -Dsyxmp.steam=0} turns it off. (It was opt-in while the JVM
     * crashed in runCallbacks on one test machine - that was the shared
     * native-library path steamworks4j extracts to, see {@link SteamNatives}.)
     */
    public static boolean enabled() {
        if (enabled == null)
            enabled = !"0".equals(syxmp.ui.MpSettings.get("steam", "1"))
                    && !"0".equals(System.getProperty("syxmp.steam"));
        return enabled;
    }

    private static SteamMpImpl impl() {
        if (broken || !enabled())
            return null;
        try {
            return SteamMpImpl.get();
        } catch (Throwable t) {
            broken = true;
            MpLog.err("steam integration disabled", t);
            return null;
        }
    }

    public static boolean available() {
        return impl() != null;
    }

    public static String personaName() {
        SteamMpImpl i = impl();
        return i == null ? null : i.personaName();
    }

    /** Pump Steam callbacks and P2P packets; call once per frame from the game thread. */
    public static void pump() {
        SteamMpImpl i = impl();
        if (i == null)
            return;
        try {
            i.pump();
        } catch (Throwable t) {
            broken = true;
            MpLog.err("steam pump failed; steam integration disabled", t);
        }
    }

    public static List<FriendLobby> friendLobbies() {
        SteamMpImpl i = impl();
        return i == null ? Collections.emptyList() : i.friendLobbies();
    }

    public static void joinLobby(long lobbyId, String username) {
        SteamMpImpl i = impl();
        if (i == null) {
            MpLog.err("steam not available");
            return;
        }
        i.joinLobby(lobbyId, username);
    }

    /** The in-process server is up: open a friends-only lobby and advertise it. */
    public static void hostStarted(MultiplayerServer server) {
        SteamMpImpl i = impl();
        if (i != null)
            i.hostStarted(server);
    }

    /** Left or stopped hosting: close lobby, connections and rich presence. */
    public static void sessionEnded() {
        SteamMpImpl i = impl();
        if (i != null)
            i.sessionEnded();
    }
}
