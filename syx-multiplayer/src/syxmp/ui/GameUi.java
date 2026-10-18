package syxmp.ui;

import java.lang.reflect.Field;

import init.constant.C;
import init.sprite.UI.UI;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.misc.ACTION;
import snake2d.util.sets.LIST;
import syxmp.client.Multiplayer;
import syxmp.util.MpLog;
import util.gui.misc.GButt;
import view.keyboard.KEYS;
import view.main.VIEW;
import view.menu.IMenu;

/**
 * Per-frame in-game UI driver (runs at the top of every game frame, before
 * the lockstep logic): installs the escape-menu entry once per game, sends
 * our cursor, and opens the chat on Enter.
 */
public final class GameUi {

    private static Object installedFor;

    private GameUi() {
    }

    public static void frame() {
        ensureMenuEntry();
        if (Multiplayer.active()) {
            MpCursors.sendIfDue();
            if (!MpPanel.get().isActivated() && KEYS.MAIN().ENTER.consumeClick())
                MpPanel.get().open(true);
        }
    }

    /** New GAME/VIEW → new interrupters; re-add our button to the escape menu. */
    private static void ensureMenuEntry() {
        IMenu menu;
        try {
            menu = VIEW.inters().menu;
        } catch (RuntimeException e) {
            return;
        }
        if (menu == null || menu == installedFor)
            return;
        installedFor = menu;
        MpPanel.reset();
        MpCursors.clear();
        try {
            Field fMain = IMenu.class.getDeclaredField("main");
            fMain.setAccessible(true);
            GuiSection main = (GuiSection) fMain.get(menu);
            LIST<RENDEROBJ> old = main.elements();
            // Layout in IMenu's constructor: [frame][resume][quicksave][save]
            // [load][options][keys][quit to menu][exit][title]
            RENDEROBJ[] items = new RENDEROBJ[old.size()];
            for (int i = 0; i < items.length; i++)
                items[i] = old.get(i);
            if (items.length < 4) {
                MpLog.err("escape menu layout unexpected; skipping multiplayer entry");
                return;
            }
            GButt mp = new GButt.Glow(UI.FONT().H1.getText("multiplayer"));
            mp.clickActionSet(new ACTION() {
                @Override
                public void exe() {
                    VIEW.inters().manager.remove(menu);
                    MpPanel.get().open(false);
                }
            });
            RENDEROBJ title = items[items.length - 1];
            main.clear();
            int insertBefore = items.length - 3; // before "quit to menu"
            boolean first = true;
            for (int i = 1; i < items.length - 1; i++) {
                if (i == insertBefore)
                    main.addDownC(6, mp);
                main.addDownC(first ? 10 : 6, items[i]);
                first = false;
            }
            main.body().centerIn(C.DIM());
            main.add(UI.decor().frame(main.body()));
            main.moveLastToBack();
            title.body().centerX(main);
            title.body().moveY2(main.body().y1());
            main.add(title);
            MpLog.info("multiplayer entry added to escape menu");
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("could not add multiplayer entry to the escape menu", e);
        }
    }
}
