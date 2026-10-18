package syxmp.util;

import snake2d.LOG;

/** Mod logging, prefixed so lines are findable in the game log. */
public final class MpLog {

    public static volatile boolean verbose = true;

    private MpLog() {
    }

    private static java.io.PrintWriter file;
    private static boolean fileTried;

    public static void info(String msg) {
        LOG.ln("[SyxMP] " + msg);
        toFile("I", msg);
    }

    public static void debug(String msg) {
        if (verbose) {
            LOG.ln("[SyxMP:d] " + msg);
            toFile("D", msg);
        }
    }

    public static void err(String msg) {
        LOG.err("[SyxMP] " + msg);
        toFile("E", msg);
    }

    public static void err(String msg, Throwable t) {
        LOG.err("[SyxMP] " + msg + ": " + t);
        t.printStackTrace();
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        toFile("E", msg + ": " + sw);
    }

    /**
     * Also append to <game data>/logs/syxmp.log (the game itself keeps no
     * stdout log), so players can send desync/unsynced reports.
     */
    private static synchronized void toFile(String level, String msg) {
        if (file == null) {
            if (fileTried)
                return;
            fileTried = true;
            try {
                java.nio.file.Path dir = syxmp.agent.AgentInstaller.launcherSettings().getParent().getParent().resolve("logs");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path f = dir.resolve("syxmp.log");
                if (java.nio.file.Files.exists(f) && java.nio.file.Files.size(f) > 5_000_000)
                    java.nio.file.Files.move(f, dir.resolve("syxmp.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                file = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(f, java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND), true);
                file.println("---- session " + java.time.LocalDateTime.now() + " ----");
            } catch (Exception e) {
                return;
            }
        }
        file.println(java.time.LocalTime.now().toString() + " " + level + " " + msg);
    }
}
