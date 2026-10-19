package syxmp.agent;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds -javaagent:<mod jar> to the launcher's JVM_ARGS2 in
 * LauncherSettings.txt so the game process loads the agent on next start
 * (init/Main.java:84 passes those args to the game JVM verbatim).
 *
 * Runs at the earliest no-agent entry point we have (the SCRIPT factory's
 * constructor, which the main menu triggers through ScriptEngine.getAll)
 * and again at game creation. Idempotent; every outcome is logged to
 * syxmp.log so a failed install on a player's machine is diagnosable.
 */
public final class AgentInstaller {

    private static String lastResult;

    private AgentInstaller() {
    }

    /**
     * The game's per-user data folder (init/paths/Util.java:getLocal). When the
     * game is up we ask it; otherwise the native location, then the Linux
     * Steam snap/flatpak sandboxes (a Steam launched from a snap sees its
     * own HOME).
     */
    public static Path dataDir() {
        try {
            return init.paths.PATHS.local().SETTINGS.get().toAbsolutePath().getParent();
        } catch (Throwable t) {
            // no game paths yet (premain, headless tests): fall through
        }
        List<Path> candidates = candidateDataDirs();
        for (Path p : candidates)
            if (Files.isRegularFile(p.resolve("settings").resolve("LauncherSettings.txt")))
                return p;
        for (Path p : candidates)
            if (Files.isDirectory(p))
                return p;
        return candidates.get(0);
    }

    static List<Path> candidateDataDirs() {
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase();
        List<Path> c = new ArrayList<>();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData == null)
                appData = System.getenv("AppData");
            c.add(Paths.get(appData != null ? appData : home + "/AppData/Roaming", "songsofsyx"));
        } else if (os.contains("mac") || os.contains("darwin")) {
            c.add(Paths.get(home, "Library", "Application Support", "songsofsyx"));
        } else {
            c.add(Paths.get(home, ".local", "share", "songsofsyx"));
            c.add(Paths.get(home, "snap", "steam", "common", ".local", "share", "songsofsyx"));
            c.add(Paths.get(home, ".var", "app", "com.valvesoftware.Steam", ".local", "share", "songsofsyx"));
            String xdg = System.getenv("XDG_DATA_HOME");
            if (xdg != null && !xdg.isEmpty())
                c.add(0, Paths.get(xdg, "songsofsyx"));
        }
        return c;
    }

    public static Path launcherSettings() {
        return dataDir().resolve("settings").resolve("LauncherSettings.txt");
    }

    /** The jar this class was loaded from (the launcher puts mod jars on -cp directly). */
    public static Path ownJar() {
        try {
            File f = new File(SyxAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return f.toPath();
        } catch (URISyntaxException | RuntimeException e) {
            throw new IllegalStateException("cannot locate own jar", e);
        }
    }

    /**
     * The installed mod jar: <data>/mods/SyxMultiplayer/V<major>/script/
     * SyxMultiplayer.jar, else any mods/<x>/V<n>/script/SyxMultiplayer.jar,
     * else the code source (a Steam Workshop folder, for instance).
     */
    public static Path modJar(Path dataDir) {
        Path mods = dataDir.resolve("mods");
        String major = null;
        try {
            major = Integer.toString(game.VERSION.VERSION_MAJOR);
        } catch (Throwable t) {
            // not in the game JVM
        }
        if (major != null) {
            Path direct = mods.resolve("SyxMultiplayer").resolve("V" + major).resolve("script")
                    .resolve("SyxMultiplayer.jar");
            if (Files.isRegularFile(direct))
                return direct;
        }
        Path found = scanMods(mods);
        if (found != null)
            return found;
        Path own = ownJar();
        if (own.toString().endsWith(".jar") && Files.isRegularFile(own))
            return own;
        return null;
    }

    private static Path scanMods(Path mods) {
        if (!Files.isDirectory(mods))
            return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path mod : ds) {
                if (!Files.isDirectory(mod))
                    continue;
                try (DirectoryStream<Path> vs = Files.newDirectoryStream(mod, "V*")) {
                    for (Path v : vs) {
                        Path jar = v.resolve("script").resolve("SyxMultiplayer.jar");
                        if (Files.isRegularFile(jar))
                            return jar;
                    }
                }
            }
        } catch (Exception e) {
            // unreadable mods folder
        }
        return null;
    }

    /** Install once per process (no-op if the agent is already running). */
    public static synchronized String ensureInstalled() {
        if (lastResult != null)
            return lastResult;
        if (SyxAgent.isActive())
            return lastResult = "agent active";
        lastResult = install();
        log("agent auto-install: " + lastResult);
        return lastResult;
    }

    /** @return true if a restart is what the player needs now. */
    public static boolean restartNeeded() {
        return !SyxAgent.isActive() && lastResult != null
                && (lastResult.startsWith("agent installed") || lastResult.startsWith("agent already installed"));
    }

    /** @return a status/instruction message for the user. */
    public static String install() {
        try {
            Path dataDir = dataDir();
            Path settings = dataDir.resolve("settings").resolve("LauncherSettings.txt");
            log("data dir " + dataDir + "; launcher settings " + (Files.exists(settings) ? "found" : "MISSING"));
            if (!Files.exists(settings))
                return "LauncherSettings.txt not found at " + settings;
            Path jar = modJar(dataDir);
            if (jar == null)
                return "mod jar not found under " + dataDir.resolve("mods") + " nor at " + ownJar()
                        + " — add -javaagent manually";
            log("mod jar " + jar);

            String content = Files.readString(settings, StandardCharsets.UTF_8);
            String agentArg = "-javaagent:" + jar.toAbsolutePath().toString().replace('\\', '/');
            if (content.contains("\"" + agentArg + "\""))
                return "agent already installed in launcher settings; RESTART the game (launcher included)";
            if (content.contains("-javaagent:")) {
                // Stale path from an old install — drop that entry
                content = content.replaceAll("[ \\t]*\"-javaagent:[^\"]*\",?[ \\t]*\\r?\\n?", "");
            }
            // Leftover diagnostic flags from a traced session (desync hunting) slow the game down:
            // they are never part of a player install.
            content = content.replaceAll("[ \\t]*\"-Dsyxmp\\.(rndtrace|enttrace|hashevery)[^\"]*\",?[ \\t]*\\r?\\n?", "");
            int idx = content.indexOf("JVM_ARGS2:");
            if (idx < 0) {
                // No JVM_ARGS2 block at all: append one (the parser accepts any key order).
                if (!content.endsWith("\n"))
                    content += "\n";
                content += "JVM_ARGS2: [\n\t\"" + agentArg + "\",\n],\n";
            } else {
                int insertAt = content.indexOf('[', idx);
                if (insertAt < 0)
                    return "JVM_ARGS2 is malformed in " + settings;
                insertAt++;
                content = content.substring(0, insertAt)
                        + "\n\t\"" + agentArg + "\","
                        + content.substring(insertAt);
            }
            Path tmp = settings.resolveSibling("LauncherSettings.txt.syxmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, settings, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "agent installed into launcher settings — RESTART the game (launcher included)";
        } catch (Exception e) {
            return "agent install failed: " + e;
        }
    }

    /** Remove the -javaagent line (for players uninstalling the mod). */
    public static String uninstall() {
        try {
            Path settings = launcherSettings();
            if (!Files.exists(settings))
                return "LauncherSettings.txt not found at " + settings;
            String content = Files.readString(settings, StandardCharsets.UTF_8);
            if (!content.contains("-javaagent:"))
                return "no agent line present";
            content = content.replaceAll("[ \\t]*\"-javaagent:[^\"]*\",?[ \\t]*\\r?\\n?", "");
            Files.writeString(settings, content, StandardCharsets.UTF_8);
            return "agent line removed; the mod is inert after the next restart";
        } catch (Exception e) {
            return "agent uninstall failed: " + e;
        }
    }

    private static void log(String s) {
        try {
            syxmp.util.MpLog.info(s);
        } catch (Throwable t) {
            System.out.println("[SyxMP] " + s);
        }
    }
}
