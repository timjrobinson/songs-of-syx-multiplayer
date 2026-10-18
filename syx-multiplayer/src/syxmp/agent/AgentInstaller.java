package syxmp.agent;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Adds -javaagent:<this jar> to the launcher's JVM_ARGS2 in
 * LauncherSettings.txt so the game process loads the agent on next start
 * (init/Main.java passes those args to the game JVM verbatim).
 */
public final class AgentInstaller {

    private AgentInstaller() {
    }

    public static Path launcherSettings() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win"))
            return Paths.get(System.getenv().getOrDefault("APPDATA", home),
                    "songsofsyx", "settings", "LauncherSettings.txt");
        if (os.contains("mac"))
            return Paths.get(home, "Library", "Application Support",
                    "songsofsyx", "settings", "LauncherSettings.txt");
        return Paths.get(home, ".local", "share", "songsofsyx", "settings",
                "LauncherSettings.txt");
    }

    public static Path ownJar() {
        try {
            File f = new File(SyxAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return f.toPath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate own jar", e);
        }
    }

    /** @return a status/instruction message for the user. */
    public static String install() {
        try {
            Path settings = launcherSettings();
            if (!Files.exists(settings))
                return "LauncherSettings.txt not found at " + settings;
            Path jar = ownJar();
            if (!jar.toString().endsWith(".jar"))
                return "not running from a jar (" + jar + "); dev setup — add -javaagent manually";

            String content = Files.readString(settings, StandardCharsets.UTF_8);
            String agentArg = "-javaagent:" + jar.toAbsolutePath().toString().replace('\\', '/');
            if (content.contains(agentArg))
                return "agent already installed; restart the game if it isn't active";
            if (content.contains("-javaagent:")) {
                // Stale path from an old install — replace the whole line
                content = content.replaceAll("\\s*\"-javaagent:[^\"]*\",", "");
            }
            int idx = content.indexOf("JVM_ARGS2: [");
            if (idx < 0)
                return "JVM_ARGS2 not found in " + settings;
            int insertAt = content.indexOf('[', idx) + 1;
            content = content.substring(0, insertAt)
                    + "\n\t\"" + agentArg + "\","
                    + content.substring(insertAt);
            Files.writeString(settings, content, StandardCharsets.UTF_8);
            return "agent installed into launcher settings — RESTART the game (launcher included)";
        } catch (Exception e) {
            return "agent install failed: " + e;
        }
    }
}
