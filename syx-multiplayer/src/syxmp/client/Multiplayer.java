package syxmp.client;

import syxmp.server.MultiplayerServer;

/**
 * Client root: the current session and the global mode flags every
 * interception decision keys on. Port of Multiplayer.Client.Multiplayer.
 */
public final class Multiplayer {

    /** Null when not in a multiplayer game. */
    public static volatile MultiplayerSession session;
    /** Non-null when this process hosts the in-process server. */
    public static volatile MultiplayerServer localServer;

    /** True while TickController is advancing the simulation. */
    public static boolean ticking;
    /** True while a synced command is being executed. */
    public static boolean executingCmds;
    /** True while (re)loading a game. */
    public static boolean reloading;
    /** Escape hatch: sync interception temporarily disabled. */
    public static boolean dontSync;

    private Multiplayer() {
    }

    public static boolean active() {
        return session != null;
    }

    /**
     * Are we in "interface" context — i.e. a player-initiated call that must
     * become a command instead of mutating state? Port of Multiplayer.InInterface.
     */
    /** The game's updater thread (set on the first lockstep frame). */
    public static volatile Thread gameThread;

    public static boolean inInterface() {
        return session != null && !ticking && !executingCmds && !reloading
                && (gameThread == null || Thread.currentThread() == gameThread);
    }

    public static boolean shouldSync() {
        return inInterface() && !dontSync;
    }

    /**
     * Is a game constructed? A session exists before any game does (a join
     * from the main menu), and hooks on generic widget classes run in the
     * menu too. VIEW is created last in the GAME constructor (GAME.java:185),
     * after FACTIONS/SETT/WORLD/ARMIES, so its existence implies theirs.
     */
    public static boolean gameExists() {
        return view.main.VIEW.existTemp();
    }
}
