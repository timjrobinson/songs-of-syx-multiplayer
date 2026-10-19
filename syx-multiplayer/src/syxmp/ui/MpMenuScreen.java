package syxmp.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import game.save.SaveFile;
import init.paths.PATHS;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.sprite.text.StringInputSprite;
import syxmp.client.ClientConnect;
import syxmp.client.Multiplayer;
import syxmp.steam.SteamMp;
import syxmp.util.MpLog;
import util.gui.misc.GInput;
import util.gui.misc.GText;
import view.menu.MenuScreen;
import view.menu.MenuScreenLoad;

/**
 * The main-menu "multiplayer" screen, built only from the game's public menu
 * widgets (view.menu.MenuScreen & friends, the same ones the vanilla load
 * screens use). Three ways in:
 *   host game      -> pick a save (vanilla save list), load it, host it
 *   join           -> address:port + username, TCP
 *   steam friends  -> friends currently hosting, one click to join via Steam
 *
 * Wiring to the vanilla Menu (switching screens, starting a game) is done by
 * the small menu.SyxMpMenu bridge; this class knows nothing about Menu.
 */
public final class MpMenuScreen {

    public static final COLOR LABEL = new ColorImp(127, 127, 80);

    /** Host the given save file (bridge starts the GameLoader). */
    public final Consumer<Path> onHost;
    /** Leave the multiplayer screen. */
    public final Runnable onBack;

    private final GuiSection main = new GuiSection();
    private final MenuScreenLoad hostScreen;
    private final GInput nameInput;
    private final GInput addressInput;
    private final GInput portInput;
    private final GInput passwordInput;
    private final GInput maxPlayersInput;
    private boolean pauseOnJoin = true;
    private final GuiSection friends = new GuiSection();
    /** Where the layout put the friends section. GuiSection.clear() resets
     *  the section's bounds to the screen origin (GuiSection.java:65-74), so
     *  every refresh must move it back before adding rows, or the rows are
     *  drawn in the top-left corner of the screen. */
    private int friendsX, friendsY;
    private final GText status = new GText(UI.FONT().M, 160);
    private final GText hint = new GText(UI.FONT().M, 200);
    private boolean showingHost;
    private long lastFriendsRefresh;

    public MpMenuScreen(Consumer<Path> onHost, Runnable onBack) {
        this.onHost = onHost;
        this.onBack = onBack;

        MenuScreen frame = new MenuScreen("multiplayer", LABEL) {
            @Override
            protected void back() {
                onBack.run();
            }
        };

        // --- inputs -------------------------------------------------------
        StringInputSprite name = new StringInputSprite(15, UI.FONT().M).placeHolder("username");
        name.text().add(MpSettings.defaultUsername());
        nameInput = new GInput(name);

        StringInputSprite addr = new StringInputSprite(40, UI.FONT().M).placeHolder("host address");
        addr.text().add(MpSettings.lastAddress());
        addressInput = new GInput(addr);

        StringInputSprite port = new StringInputSprite(5, UI.FONT().M).placeHolder("port");
        port.text().add(Integer.toString(MpSettings.port()));
        portInput = new GInput(port);

        StringInputSprite pw = new StringInputSprite(32, UI.FONT().M).placeHolder("password (optional)");
        pw.text().add(MpSettings.get("password", ""));
        passwordInput = new GInput(pw);

        StringInputSprite maxp = new StringInputSprite(2, UI.FONT().M).placeHolder("8");
        maxp.text().add(Integer.toString(MpSettings.getInt("maxPlayers", 8)));
        maxPlayersInput = new GInput(maxp);
        pauseOnJoin = MpSettings.getInt("pauseOnJoin", 1) != 0;

        GuiSection form = new GuiSection();
        form.add(label("username"), 0, 0);
        form.addRightC(12, nameInput);
        GuiSection row2 = new GuiSection();
        row2.add(label("address"), 0, 0);
        row2.addRightC(12, addressInput);
        row2.addRightC(16, label("port"));
        row2.addRightC(12, portInput);
        form.addDown(12, row2);
        // Host settings (the password doubles as the join password).
        GuiSection row3 = new GuiSection();
        row3.add(label("password"), 0, 0);
        row3.addRightC(12, passwordInput);
        row3.addRightC(16, label("max players"));
        row3.addRightC(12, maxPlayersInput);
        CLICKABLE pause = new util.gui.misc.GButt.Checkbox("pause when someone joins") {
            @Override
            protected void renAction() {
                selectedSet(pauseOnJoin);
            }

            @Override
            protected void clickA() {
                pauseOnJoin = !pauseOnJoin;
            }
        };
        row3.addRightC(16, pause);
        form.addDown(12, row3);

        // --- actions ------------------------------------------------------
        GuiSection buttons = new GuiSection();
        CLICKABLE join = new MenuScreen.ScreenButton("join game") {
            @Override
            protected void renAction() {
                activeSet(!Multiplayer.active());
            }

            @Override
            protected void clickA() {
                joinTcp();
            }
        };
        CLICKABLE host = new MenuScreen.ScreenButton("host a saved game") {
            @Override
            protected void renAction() {
                activeSet(!Multiplayer.active());
            }

            @Override
            protected void clickA() {
                hostScreen().populateSaves();
                showingHost = true;
            }
        };
        CLICKABLE cancel = new MenuScreen.ScreenButton("cancel") {
            @Override
            protected void renAction() {
                visableSet(Multiplayer.active() && !Multiplayer.session.isHost);
            }

            @Override
            protected void clickA() {
                ClientConnect.disconnect("cancelled");
                MpStatus.set("cancelled");
            }
        };
        buttons.add(host, 0, 0);
        buttons.addRightC(48, join);
        buttons.addRightC(48, cancel);
        form.addDown(24, buttons);

        form.addDown(24, label("steam friends hosting a game"));
        friends.body().setDim(MenuScreen.inner.width() - 40, 24);
        form.addDown(8, friends);

        form.body().moveX1Y1(MenuScreen.inner.x1() + 20, MenuScreen.inner.y1() + 20);
        friendsX = friends.body().x1();
        friendsY = friends.body().y1();
        main.add(form);
        main.add(frame);
        main.moveLastToBack();

        hostScreen = new MenuScreenLoad("host a saved game", LABEL, false, PATHS.local().save()) {
            @Override
            protected void load(SaveFile f) {
                saveInputs();
                showingHost = false;
                onHost.accept(f.path);
            }

            @Override
            protected void back() {
                showingHost = false;
            }
        };
    }

    private static GText label(CharSequence s) {
        GText t = new GText(UI.FONT().H2, s);
        t.color(LABEL);
        return t;
    }

    private MenuScreenLoad hostScreen() {
        return hostScreen;
    }

    public String username() {
        return MpSettings.sanitizeUsername(nameInput.text().toString());
    }

    public int port() {
        try {
            return Integer.parseInt(portInput.text().toString().trim());
        } catch (NumberFormatException e) {
            return 30599;
        }
    }

    public String password() {
        return passwordInput.text().toString().trim();
    }

    public int maxPlayers() {
        try {
            return Math.max(2, Math.min(64, Integer.parseInt(maxPlayersInput.text().toString().trim())));
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    public boolean pauseOnJoin() {
        return pauseOnJoin;
    }

    /** Server settings from the form (host side). */
    public syxmp.server.ServerSettings serverSettings() {
        syxmp.server.ServerSettings settings = new syxmp.server.ServerSettings();
        settings.port = port();
        settings.password = password();
        settings.maxPlayers = maxPlayers();
        settings.pauseOnJoin = pauseOnJoin();
        settings.gameName = MpSettings.get("gameName", username() + "'s game");
        return settings;
    }

    public void saveInputs() {
        MpSettings.set("username", username());
        MpSettings.set("address", addressInput.text().toString().trim());
        MpSettings.set("port", Integer.toString(port()));
        MpSettings.set("password", password());
        MpSettings.set("maxPlayers", Integer.toString(maxPlayers()));
        MpSettings.set("pauseOnJoin", pauseOnJoin ? "1" : "0");
        MpSettings.save();
    }

    private void joinTcp() {
        saveInputs();
        String address = addressInput.text().toString().trim();
        int port = port();
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(address.substring(colon + 1));
                address = address.substring(0, colon);
            } catch (NumberFormatException e) {
                // keep the port field
            }
        }
        if (address.isEmpty()) {
            MpStatus.fail("enter a host address");
            return;
        }
        MpLog.info("menu join " + address + ":" + port + " as " + username());
        ClientConnect.connect(address, port, username(), password());
    }

    // --- friends list -----------------------------------------------------

    private void refreshFriends() {
        long now = System.currentTimeMillis();
        if (now - lastFriendsRefresh < 2000)
            return;
        lastFriendsRefresh = now;
        friends.clear();
        friends.body().moveX1Y1(friendsX, friendsY);
        if (!SteamMp.enabled()) {
            friendsRow(new GText(UI.FONT().M, "steam integration is off (steam=0 in settings/SyxMultiplayer.txt)"));
            return;
        }
        if (!SteamMp.available()) {
            friendsRow(new GText(UI.FONT().M, "steam is not running, or the game was not started through steam"));
            return;
        }
        java.util.List<SteamMp.FriendLobby> lobbies = SteamMp.friendLobbies();
        if (lobbies.isEmpty()) {
            friendsRow(new GText(UI.FONT().M, "no friends are hosting right now"));
            return;
        }
        for (SteamMp.FriendLobby fl : lobbies) {
            CLICKABLE b = new MenuScreen.ScreenButton(fl.friendName + "  -  " + fl.gameName, UI.FONT().H2) {
                @Override
                protected void renAction() {
                    activeSet(!Multiplayer.active());
                }

                @Override
                protected void clickA() {
                    saveInputs();
                    SteamMp.joinLobby(fl.lobbyId, username());
                }
            };
            friendsRow(b);
        }
    }

    /** Adds a row below the previous one, starting at the section's own
     *  top-left (after clear() "previous" is the empty bounds rectangle). */
    private void friendsRow(snake2d.util.gui.renderable.RENDEROBJ r) {
        friends.addDown(friends.elements().size() == 0 ? 0 : 4, r);
    }

    private void friendsRow(snake2d.util.sprite.SPRITE s) {
        friends.addDown(friends.elements().size() == 0 ? 0 : 4, s);
    }

    // --- SC-like surface used by the bridge --------------------------------

    public boolean hover(COORDINATE mCoo) {
        if (showingHost)
            return hostScreen.hover(mCoo);
        return main.hover(mCoo);
    }

    public boolean click() {
        if (showingHost)
            return hostScreen.click();
        return main.click();
    }

    public void render(SPRITE_RENDERER r, float ds) {
        if (showingHost) {
            hostScreen.render(r, ds);
            return;
        }
        refreshFriends();
        main.render(r, ds);
        status.clear();
        if (!MpStatus.line.isEmpty()) {
            status.add(MpStatus.line);
            if (MpStatus.error)
                status.errorify();
            else
                status.normalify();
            status.render(r, MenuScreen.inner.x1() + 20, MenuScreen.inner.y2() - 60);
        }
        hint.clear().add("direct joins need the port open or forwarded; in game: Enter opens chat, "
                + "/help lists host commands (/kick, /joinpoint)");
        hint.color(LABEL);
        hint.render(r, MenuScreen.inner.x1() + 20, MenuScreen.inner.y2() - 32);
    }

    /** @return true if the back press was consumed (sub-screen closed). */
    public boolean back() {
        if (showingHost) {
            showingHost = false;
            return true;
        }
        return false;
    }
}
