package syxmp.client;

import syxmp.steam.SteamMp;
import syxmp.util.MpLog;

/**
 * Per-frame work while the game is at the main menu (no GAME, no VIEW):
 * drains the client main-thread queue so a join started from the menu can
 * progress, pumps Steam callbacks, and installs the menu entry once per
 * Menu instance. Driven by the agent's Menu.update entry hook.
 */
public final class MenuDriver {

    private static Object lastMenu;
    private static boolean failed;

    private MenuDriver() {
    }

    public static void menuTick(Object menuSelf) {
        DevAuto.menuTick();
        MainThread.drain();
        SteamMp.pump();
        if (menuSelf != lastMenu && !failed) {
            lastMenu = menuSelf;
            try {
                menu.SyxMpMenu.install((menu.Menu) menuSelf);
            } catch (Throwable t) {
                failed = true;
                MpLog.err("menu integration failed; multiplayer menu unavailable", t);
            }
        }
    }
}
