package syxmp.ui;

import snake2d.CORE;
import snake2d.util.color.COLOR;
import syxmp.util.MpLog;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * First-run dialog: the agent was just written into the launcher settings
 * and the game must be restarted before multiplayer works. A modal prompt
 * (IPromtScreen) with "quit now" / "later"; the HUD keeps a one-line
 * reminder while the game continues.
 */
public final class RestartDialog {

    private RestartDialog() {
    }

    public static void show(String message) {
        try {
            GButt quit = new GButt.ButtPanel("quit the game now") {
                @Override
                protected void clickA() {
                    MpLog.info("player chose to quit for the agent restart");
                    CORE.annihilate();
                }
            };
            GButt later = new GButt.ButtPanel("keep playing (singleplayer)") {
                @Override
                protected void clickA() {
                }
            };
            VIEW.inters().fullScreen.activate(message, COLOR.WHITE100, null, quit, later);
        } catch (RuntimeException e) {
            MpLog.err("could not show the restart dialog", e);
            try {
                game.GAME.Notify(message);
            } catch (RuntimeException e2) {
                // no game
            }
        }
    }
}
