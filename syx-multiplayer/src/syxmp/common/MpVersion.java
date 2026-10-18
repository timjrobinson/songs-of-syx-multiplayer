package syxmp.common;

/** Version constants, mirroring Multiplayer.Common.Version. */
public final class MpVersion {

    public static final String VERSION = "0.3.0";
    /** Bump on ANY wire format change. */
    public static final int PROTOCOL = 6;

    private MpVersion() {
    }
}
