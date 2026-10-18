package menu;

import java.lang.reflect.Field;
import java.nio.file.Path;

import game.save.GameLoader;
import snake2d.KeyBoard.KeyEvent;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.misc.ACTION;
import snake2d.util.sets.LIST;
import syxmp.client.HostUtil;
import syxmp.client.MainThread;
import syxmp.server.ServerSettings;
import syxmp.ui.MpMenuScreen;
import syxmp.util.MpLog;

/**
 * Bridge between the mod's multiplayer menu screen and the vanilla main menu.
 *
 * It lives in the game's {@code menu} package on purpose: the screen
 * interface {@code SC}, {@code Menu.switchScreen} and {@code Menu.start} are
 * package-private, and sharing the runtime package (same name, same system
 * class loader, jar not sealed) gives compile-time-checked access instead of
 * a pile of reflection. This is the ONLY class outside {@code syxmp.*}; it
 * holds no logic beyond plumbing.
 */
public final class SyxMpMenu implements SC {

    private static CharSequence ¤¤multiplayer = "multiplayer";

    private final Menu menu;
    private final MpMenuScreen screen;

    private SyxMpMenu(Menu menu) {
        this.menu = menu;
        this.screen = new MpMenuScreen(this::hostSave, () -> menu.switchScreen(menu.main));
    }

    /** Add the "multiplayer" entry to the main menu's first column. Idempotent per Menu. */
    public static void install(Menu menu) {
        try {
            SyxMpMenu sc = new SyxMpMenu(menu);
            Field f = ScMain.class.getDeclaredField("first");
            f.setAccessible(true);
            GuiSection first = (GuiSection) f.get(menu.main);

            LIST<RENDEROBJ> old = first.elements();
            RENDEROBJ[] items = new RENDEROBJ[old.size()];
            for (int i = 0; i < items.length; i++)
                items[i] = old.get(i);

            CLICKABLE butt = GUI.getNavButt(¤¤multiplayer);
            butt.clickActionSet(new ACTION() {
                @Override
                public void exe() {
                    menu.switchScreen(sc);
                }
            });

            // Rebuild the column: play, multiplayer, then the rest (ScMain.getFirst layout).
            first.clear();
            for (int i = 0; i < items.length; i++) {
                if (i == 0)
                    first.addDown(0, items[i]);
                else
                    first.addDown(8, items[i]);
                if (i == 0)
                    first.addDown(8, butt);
            }
            first.body().moveX1(GUI.right.x1());
            first.body().centerY(GUI.right.y1(), GUI.right.y2());
            MpLog.info("multiplayer entry added to main menu");
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("could not add multiplayer entry to the main menu", e);
        }
    }

    private void hostSave(Path save) {
        String user = screen.username();
        ServerSettings settings = screen.serverSettings();
        MpLog.info("hosting save " + save + " on port " + settings.port + " as " + user
                + (settings.hasPassword() ? " (password)" : "") + " max " + settings.maxPlayers
                + " pauseOnJoin " + settings.pauseOnJoin);
        menu.start(new GameLoader(save) {
            @Override
            public void doAfterSet() {
                // Host on the first frame of the loaded game (updater thread,
                // game fully constructed), not inside the state switch.
                MainThread.enqueue(() -> HostUtil.hostServer(user, settings));
            }
        });
    }

    @Override
    public boolean hover(COORDINATE mCoo) {
        return screen.hover(mCoo);
    }

    @Override
    public boolean click() {
        return screen.click();
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        screen.render(r, ds);
        GUI.Shadower.ren(r, ds);
    }

    @Override
    public boolean back(Menu menu) {
        if (screen.back())
            return true;
        menu.switchScreen(menu.main);
        return true;
    }

    @Override
    public void poll(KeyEvent e) {
        // text inputs listen through the global keyboard char listener
    }
}
