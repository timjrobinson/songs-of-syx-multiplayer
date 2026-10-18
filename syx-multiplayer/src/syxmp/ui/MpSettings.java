package syxmp.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import init.paths.PATHS;
import syxmp.util.MpLog;

/**
 * The mod's own persisted preferences (username, last address, ports),
 * stored beside the game's settings as SyxMultiplayer.txt. Kept separate from
 * the launcher settings so the game never sees unknown keys.
 */
public final class MpSettings {

    private static final Properties props = new Properties();
    private static boolean loaded;

    private MpSettings() {
    }

    private static Path file() {
        return PATHS.local().SETTINGS.get().resolve("SyxMultiplayer.txt");
    }

    private static void ensureLoaded() {
        if (loaded)
            return;
        loaded = true;
        Path f = file();
        if (Files.exists(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                props.load(in);
            } catch (IOException e) {
                MpLog.err("could not read " + f, e);
            }
        }
    }

    public static void save() {
        Path f = file();
        try (OutputStream out = Files.newOutputStream(f)) {
            props.store(out, "Syx Multiplayer settings");
        } catch (IOException e) {
            MpLog.err("could not write " + f, e);
        }
    }

    public static String get(String key, String def) {
        ensureLoaded();
        return props.getProperty(key, def);
    }

    public static void set(String key, String value) {
        ensureLoaded();
        props.setProperty(key, value);
    }

    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Default username: Steam persona if available, else the OS user, sanitized. */
    public static String defaultUsername() {
        String name = get("username", "");
        if (name.isEmpty())
            name = syxmp.steam.SteamMp.personaName();
        if (name == null || name.isEmpty())
            name = System.getProperty("user.name", "player");
        return sanitizeUsername(name);
    }

    public static String sanitizeUsername(String name) {
        String s = name.replaceAll("[^a-zA-Z0-9_]", "");
        if (s.length() > 15)
            s = s.substring(0, 15);
        if (s.length() < 3)
            s = (s + "player").substring(0, Math.min(15, Math.max(3, s.length() + 6)));
        return s;
    }

    public static String lastAddress() {
        return get("address", "127.0.0.1");
    }

    public static int port() {
        return getInt("port", 30599);
    }
}
