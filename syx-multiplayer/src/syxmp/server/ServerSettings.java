package syxmp.server;

/** Host-configurable server behavior, a trimmed port of Multiplayer.Common.ServerSettings. */
public final class ServerSettings {

    public String gameName = "Syx Multiplayer";
    public String password = "";
    public int maxPlayers = 8;
    public int port = 30599;

    public boolean pauseOnJoin = true;
    public boolean pauseOnDesync = true;
    /** Create a fresh join point automatically when someone joins. */
    public boolean autoJoinPoint = true;

    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }
}
