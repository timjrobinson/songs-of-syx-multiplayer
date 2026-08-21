package syxmp.common;

/**
 * Wire packet ids, mirroring Multiplayer.Common.Packets. The 1-byte frame
 * header spends 6 bits on the packet id, so at most 63 packets (MAX).
 */
public enum Packets {
    // Special — ids must stay stable across protocol versions
    CLIENT_PROTOCOL, // must be 0

    // Joining
    CLIENT_USERNAME,
    CLIENT_JOIN_DATA,
    CLIENT_WORLD_REQUEST,

    // Playing
    CLIENT_WORLD_READY,
    CLIENT_COMMAND,
    CLIENT_WORLD_DATA_UPLOAD,
    CLIENT_CHAT,
    CLIENT_KEEP_ALIVE,
    CLIENT_SYNC_INFO,
    CLIENT_CURSOR,
    CLIENT_DESYNCED,
    CLIENT_FREEZE,
    CLIENT_PAUSE,
    CLIENT_FRAME_TIME,

    // Server → client
    SERVER_PROTOCOL_OK,
    SERVER_USERNAME_OK,
    SERVER_JOIN_DATA,
    SERVER_WORLD_DATA_START,
    SERVER_WORLD_DATA,
    SERVER_COMMAND,
    SERVER_TIME_CONTROL,
    SERVER_NOTIFICATION,
    SERVER_CHAT,
    SERVER_PLAYER_LIST,
    SERVER_KEEP_ALIVE,
    SERVER_SYNC_INFO,
    SERVER_CURSOR,
    SERVER_FREEZE,
    SERVER_DISCONNECT;

    public static final int MAX = 63;

    private static final Packets[] VALUES = values();

    static {
        if (VALUES.length > MAX)
            throw new AssertionError("too many packet ids for 6-bit header");
    }

    public static Packets of(int id) {
        if (id < 0 || id >= VALUES.length)
            return null;
        return VALUES[id];
    }
}
