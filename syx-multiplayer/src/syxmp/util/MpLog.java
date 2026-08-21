package syxmp.util;

import snake2d.LOG;

/** Mod logging, prefixed so lines are findable in the game log. */
public final class MpLog {

    public static volatile boolean verbose = true;

    private MpLog() {
    }

    public static void info(String msg) {
        LOG.ln("[SyxMP] " + msg);
    }

    public static void debug(String msg) {
        if (verbose)
            LOG.ln("[SyxMP:d] " + msg);
    }

    public static void err(String msg) {
        LOG.err("[SyxMP] " + msg);
    }

    public static void err(String msg, Throwable t) {
        LOG.err("[SyxMP] " + msg + ": " + t);
        t.printStackTrace();
    }
}
