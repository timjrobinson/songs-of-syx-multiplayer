package syxmp.net;

/** Connection lifecycle states, mirroring Multiplayer.Common.ConnectionStateEnum. */
public enum ConnectionStateEnum {
    CLIENT_JOINING,
    CLIENT_LOADING,
    CLIENT_PLAYING,
    SERVER_JOINING,
    SERVER_LOADING,
    SERVER_PLAYING,
    DISCONNECTED
}
