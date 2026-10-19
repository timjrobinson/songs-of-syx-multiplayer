package syxmp.steam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import com.codedisaster.steamworks.SteamAPI;

import syxmp.util.MpLog;

/**
 * Loads the Steamworks natives the game ships (steamworks4j 1.9.0 + SDK
 * libs at the jar root) from a private, per-process directory instead of
 * {@code SteamAPI.loadLibraries()}.
 *
 * steamworks4j's own loader extracts to the shared path
 * {@code <java.io.tmpdir>/steamworks4j/1.9.0/} and rewrites the files IN
 * PLACE (truncating FileOutputStream, no exists/CRC check) on every call in
 * every process. The game spawns a helper JVM that calls it at startup and
 * every time the main menu opens ({@code MainProcess.java:35},
 * {@code Menu.java:110} -> {@code PTitles.achieve()} -> SteamAchieve child).
 * A process that has the shared files mmapped when another one rewrites
 * them dies: SIGBUS while the file is truncated, or a jump to an
 * unrelocated PLT stub (pc = 0x210a6 / 0x19f06, "SteamAPI.runCallbacks()V+0")
 * once the loader re-reads the half-written image. That was the Steam
 * "crash on Steam snap" of HANDOFF2 §5 - reproduced on a normal install by
 * running two JVMs through the shared loader. Private files that nothing
 * else ever rewrites (and that are unlinked right after dlopen on POSIX)
 * make the mod immune; the game's own helper children still race each
 * other, which is the game's bug.
 */
public final class SteamNatives {

    private static final String PREFIX = "syxmp-steam-";
    private static boolean loaded;

    private SteamNatives() {
    }

    public static synchronized void load() throws IOException {
        if (loaded)
            return;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "");
        boolean win = os.contains("win");
        boolean mac = os.contains("mac") || os.contains("darwin");
        if (!arch.contains("64"))
            throw new IOException("steam natives need a 64-bit JVM (os.arch=" + arch + ")");
        String[] names = win ? new String[] {"steam_api64.dll", "steamworks4j64.dll"}
                : mac ? new String[] {"libsteam_api.dylib", "libsteamworks4j.dylib"}
                : new String[] {"libsteam_api.so", "libsteamworks4j.so"};

        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        sweepStale(tmp);
        Path dir = Files.createTempDirectory(tmp, PREFIX);
        for (String n : names) {
            Path f = dir.resolve(n);
            try (InputStream in = SteamAPI.class.getResourceAsStream("/" + n)) {
                if (in == null)
                    throw new IOException(n + " not found in the game jar");
                Files.copy(in, f);
            }
            System.load(f.toAbsolutePath().toString()); // steam_api first: steamworks4j links against it
        }
        SteamAPI.skipLoadLibraries();
        loaded = true;
        MpLog.info("steam: natives loaded from " + dir);
        if (win) {
            // a mapped DLL cannot be deleted; the next start sweeps it
            for (String n : names)
                dir.resolve(n).toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
        } else {
            // the mapping survives the unlink, and now nothing can ever rewrite it
            deleteTree(dir);
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    private static void sweepStale(Path tmp) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmp, PREFIX + "*")) {
            for (Path p : ds)
                deleteTree(p);
        } catch (IOException | RuntimeException e) {
            // best effort
        }
    }

    private static void deleteTree(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds)
                Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            return; // still in use (Windows) or not ours
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException | RuntimeException e) {
            // best effort
        }
    }
}
