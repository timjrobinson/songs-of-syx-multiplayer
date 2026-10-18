package syxmp.client;

import syxmp.server.ServerSettings;
import syxmp.util.MpLog;

/**
 * Development auto-pilot for unattended end-to-end testing, gated behind
 * system properties (all absent = completely inert):
 *
 *   -Dsyxmp.autoload=1            quickload the newest save from the main menu
 *   -Dsyxmp.autohost=1            host a server once in game, then unpause
 *   -Dsyxmp.autojoin=host:port    join a server once in game
 *
 * menuTick is registered from premain (its hook must exist before the menu
 * class loads), so this class must not touch game classes during class init.
 */
public final class DevAuto {

    private static final String AUTOLOAD = System.getProperty("syxmp.autoload");
    private static final String AUTOHOST = System.getProperty("syxmp.autohost");
    private static final String AUTOJOIN = System.getProperty("syxmp.autojoin");
    /** -Dsyxmp.autounpause=1: host sets speed 1 a few seconds after a second player is playing. */
    private static final String AUTOUNPAUSE = System.getProperty("syxmp.autounpause");
    private static int framesWithTwo;
    private static boolean unpausedForTwo;

    private static int menuFrames;
    private static int gameFrames;
    private static boolean loaded, hosted, joined, unpaused;

    private DevAuto() {
    }

    /** Called every main-menu frame via the Menu.update entry hook. */
    public static void menuTick() {
        if (AUTOLOAD == null || loaded)
            return;
        if (++menuFrames < 90)
            return;
        loaded = true;
        MpLog.info("[auto] quickloading newest save");
        if (!game.save.GameLoader.quickload())
            MpLog.err("[auto] quickload failed: no save found");
    }

    /** Called every in-game frame from TickController.gameUpdate. */
    public static void gameTick() {
        if (AUTOLOAD != null && !Multiplayer.reloading) {
            // Unattended runs: dismiss the "CLICK TO CONTINUE" screen ourselves.
            try {
                if (view.main.VIEW.inters().load.isActivated())
                    view.main.VIEW.inters().load.deactivate();
            } catch (RuntimeException e) {
                // no view yet
            }
        }
        if (AUTOHOST == null && AUTOJOIN == null)
            return;
        gameFrames++;
        if (gameFrames < 240)
            return;

        if (AUTOHOST != null) {
            if (!hosted) {
                hosted = true;
                MpLog.info("[auto] hosting");
                HostUtil.hostServer("host", new ServerSettings());
            } else if (!unpaused && gameFrames > 600 && Multiplayer.active()) {
                unpaused = true;
                MpLog.info("[auto] unpausing (speed 1)");
                ClientCommands.requestSpeed(1.0);
            }
        }

        if (AUTOUNPAUSE != null && Multiplayer.active() && Multiplayer.session.isHost
                && Multiplayer.session.players.size() >= 2 && TickController.speed == 0
                && TickController.inLockstep()) {
            if (++framesWithTwo >= 300) {
                framesWithTwo = 0;
                MpLog.info("[auto] second player present: speed 1");
                ClientCommands.requestSpeed(1.0);
            }
        } else {
            framesWithTwo = 0;
        }

        if (AUTOJOIN != null && !joined) {
            joined = true;
            String[] parts = AUTOJOIN.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 30599;
            MpLog.info("[auto] joining " + host + ":" + port);
            ClientConnect.connect(host, port, "joiner", "");
        }
    }
}
