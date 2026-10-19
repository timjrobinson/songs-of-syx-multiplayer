package syxmp.common;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Version constants, mirroring Multiplayer.Common.Version. */
public final class MpVersion {

    /**
     * Mod version, read from the {@code syxmp/version.txt} resource that
     * build.sh writes into the jar: {@code X.Y.Z} for release builds (the
     * release workflow derives it from the git tag) or {@code dev-<sha>} for
     * local builds. Joiners must match the host exactly.
     */
    public static final String VERSION = load();
    /** Bump on ANY wire format change. */
    public static final int PROTOCOL = 13;

    private MpVersion() {
    }

    private static String load() {
        try (InputStream in = MpVersion.class.getResourceAsStream("/syxmp/version.txt")) {
            if (in != null) {
                String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!v.isEmpty())
                    return v;
            }
        } catch (Exception e) {
            // fall through
        }
        return "dev";
    }
}
