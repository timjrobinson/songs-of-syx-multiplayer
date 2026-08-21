package syxmp.common;

/** Reasons carried by SERVER_DISCONNECT, mirroring Multiplayer.Common.MpDisconnectReason. */
public enum MpDisconnectReason {
    GENERIC_KEYED,
    PROTOCOL,
    USERNAME_LENGTH,
    USERNAME_CHARS,
    USERNAME_ALREADY_ONLINE,
    SERVER_CLOSED,
    SERVER_FULL,
    KICKED,
    BAD_GAME_PASSWORD,
    VERSION_MISMATCH,
    SAVE_MISMATCH,
    NET_FAILED,
    CONNECTION_TIMED_OUT,
    DESYNC_KICK;

    private static final MpDisconnectReason[] VALUES = values();

    public static MpDisconnectReason of(int id) {
        if (id < 0 || id >= VALUES.length)
            return GENERIC_KEYED;
        return VALUES[id];
    }
}
