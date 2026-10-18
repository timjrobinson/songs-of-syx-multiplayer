package syxmp.ui;

import init.constant.C;
import init.sprite.UI.UI;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.misc.ACTION;
import snake2d.util.sprite.text.StringInputSprite;
import syxmp.client.ClientConnect;
import syxmp.client.HostUtil;
import syxmp.client.Multiplayer;
import syxmp.client.MultiplayerSession;
import syxmp.client.TickController;
import syxmp.client.state.ClientPlayingState;
import syxmp.common.Packets;
import syxmp.net.ByteWriter;
import syxmp.server.ServerSettings;
import util.gui.misc.GButt;
import util.gui.misc.GInput;
import util.gui.misc.GText;
import util.gui.panel.GPanel;
import view.interrupter.InterGui;
import view.keyboard.KEYS;
import view.main.VIEW;

/**
 * The in-game multiplayer window: session status, player list with latency,
 * chat log + input, and host/disconnect controls. Opened from the escape
 * menu, the HUD, or Enter (chat). Port of RW-MP's ChatWindow + player list.
 */
public final class MpPanel extends InterGui {

    private static MpPanel instance;

    private static final int WIDTH = 640;
    private static final int CHAT_LINES = 12;

    private final GInput chatInput;
    private final StringInputSprite chatText;
    private final GText tmp = new GText(UI.FONT().M, 256);
    private int playersY, chatY;
    private boolean focusChat;

    private MpPanel() {
        chatText = new StringInputSprite(200, UI.FONT().M).placeHolder("say something...");
        chatInput = new GInput(chatText);

        GuiSection s = section;
        s.body().setDim(WIDTH, 100);

        GuiSection buttons = new GuiSection();
        CLICKABLE host = new GButt.ButtPanel("host this game") {
            @Override
            protected void renAction() {
                visableSet(!Multiplayer.active());
                activeSet(!Multiplayer.active());
            }

            @Override
            protected void clickA() {
                ServerSettings settings = new ServerSettings();
                settings.port = MpSettings.port();
                settings.password = MpSettings.get("password", "");
                settings.maxPlayers = MpSettings.getInt("maxPlayers", 8);
                settings.pauseOnJoin = MpSettings.getInt("pauseOnJoin", 1) != 0;
                settings.gameName = MpSettings.get("gameName", MpSettings.defaultUsername() + "'s game");
                HostUtil.hostServer(MpSettings.defaultUsername(), settings);
            }
        };
        CLICKABLE leave = new GButt.ButtPanel("leave session") {
            @Override
            protected void renAction() {
                visableSet(Multiplayer.active());
                activeSet(Multiplayer.active());
            }

            @Override
            protected void clickA() {
                ClientConnect.disconnect(Multiplayer.session != null && Multiplayer.session.isHost
                        ? "host stopped hosting" : "left");
                hide();
            }
        };
        CLICKABLE pause = new GButt.ButtPanel("pause / resume") {
            @Override
            protected void renAction() {
                visableSet(Multiplayer.active());
            }

            @Override
            protected void clickA() {
                if (Multiplayer.active())
                    syxmp.client.ClientCommands.requestPauseToggle();
            }
        };
        buttons.add(host, 0, 0);
        buttons.addRightC(8, leave);
        buttons.addRightC(8, pause);
        s.add(buttons, 0, 0);

        // Reserve space; the dynamic text is drawn in render().
        playersY = buttons.body().y2() + 12;
        int playersH = UI.FONT().M.height() * 2 + 8;
        chatY = playersY + playersH + 8;
        int chatH = UI.FONT().M.height() * CHAT_LINES + 8;

        GuiSection spacer = new GuiSection();
        spacer.body().setDim(WIDTH, playersH + 8 + chatH);
        s.add(spacer, 0, playersY);
        s.add(chatInput, 0, chatY + chatH + 8);

        CLICKABLE send = new GButt.ButtPanel(UI.icons().m.ok) {
            @Override
            protected void clickA() {
                sendChat();
            }
        };
        s.addRightC(8, send);
        s.pad(12, 12);

        GPanel p = new GPanel(s.body());
        p.setBig();
        p.setTitle("multiplayer");
        p.setCloseAction(new ACTION() {
            @Override
            public void exe() {
                hide();
            }
        });
        s.add(p);
        s.moveLastToBack();
        s.body().moveX1Y1(C.DIM().x2() - WIDTH - 48, 96);
        // The text regions sit relative to the section origin
        playersY += 12;
        chatY += 12;
    }

    public static MpPanel get() {
        if (instance == null)
            instance = new MpPanel();
        return instance;
    }

    /** Drop the cached panel when the game (and its UI) is recreated. */
    public static void reset() {
        instance = null;
    }

    public void open(boolean chat) {
        focusChat = chat;
        if (!isActivated())
            show(VIEW.inters().manager);
        if (chat)
            chatInput.focus();
    }

    public void toggle() {
        if (isActivated())
            hide();
        else
            open(false);
    }

    private void sendChat() {
        String msg = chatText.text().toString().trim();
        chatText.text().clear();
        MultiplayerSession session = Multiplayer.session;
        if (msg.isEmpty() || session == null || session.connection == null)
            return;
        if (msg.equalsIgnoreCase("/paths")) {
            syxmp.sync.ObjectPaths.invalidate();
            syxmp.sync.ObjectPaths.pathOf(new Object()); // forces a rebuild + stats log
            ClientPlayingState.ChatLog.add("*", "object path index rebuilt (see log)");
            return;
        }
        if (msg.equalsIgnoreCase("/hash")) {
            ByteWriter w = new ByteWriter(1);
            w.writeByte(0);
            syxmp.client.ClientCommands.send(syxmp.common.CommandType.DEBUG_TOOLS, w);
            return;
        }
        ByteWriter w = new ByteWriter();
        w.writeString(msg);
        session.connection.send(Packets.CLIENT_CHAT, w);
    }

    @Override
    protected boolean update(float ds) {
        if (KEYS.MAIN().ENTER.consumeClick()) {
            if (chatText.listening())
                sendChat();
            else
                chatInput.focus();
        }
        if (KEYS.MAIN().ESCAPE.consumeClick())
            hide();
        return true;
    }

    @Override
    protected boolean render(Renderer r, float ds) {
        section.render(r, ds);
        int x = section.body().x1() + 12;
        int y = section.body().y1() + playersY;
        drawStatus(r, x, y);
        drawChat(r, x, section.body().y1() + chatY);
        return true;
    }

    private void drawStatus(SPRITE_RENDERER r, int x, int y) {
        tmp.clear();
        if (!Multiplayer.active()) {
            tmp.add("not in a multiplayer session");
            if (!MpStatus.line.isEmpty())
                tmp.add("  -  ").add(MpStatus.line);
            UI.FONT().M.render(r, tmp, x, y);
            return;
        }
        MultiplayerSession session = Multiplayer.session;
        tmp.add(session.isHost ? "hosting" : "connected");
        if (session.isHost && Multiplayer.localServer != null)
            tmp.add(" on port ").add(Multiplayer.localServer.settings.port);
        tmp.add("   tick ").add(TickController.timer).add("   speed ").add(TickController.speed);
        if (TickController.desynced)
            tmp.add("   DESYNCED");
        else if (TickController.simulatingTarget >= 0)
            tmp.add("   catching up");
        UI.FONT().M.render(r, tmp, x, y);
        y += UI.FONT().M.height() + 4;
        tmp.clear().add("players: ");
        boolean first = true;
        for (MultiplayerSession.PlayerListEntry e : session.players) {
            if (!first)
                tmp.add(",  ");
            first = false;
            tmp.add(e.username);
            if (e.type == 1)
                tmp.add(" (host)");
            if (e.id != session.playerId)
                tmp.add(' ').add(e.latency).add("ms");
            if (e.status == 2)
                tmp.add(" [desynced]");
        }
        if (first)
            tmp.add(session.username);
        UI.FONT().M.render(r, tmp, x, y);
    }

    private void drawChat(SPRITE_RENDERER r, int x, int y) {
        COLOR.WHITE15.render(r, x - 4, x + WIDTH - 8, y - 4, y + UI.FONT().M.height() * CHAT_LINES + 4);
        synchronized (ClientPlayingState.ChatLog.class) {
            int n = ClientPlayingState.ChatLog.lines.size();
            int skip = Math.max(0, n - CHAT_LINES);
            int i = 0;
            for (String line : ClientPlayingState.ChatLog.lines) {
                if (i++ < skip)
                    continue;
                UI.FONT().M.render(r, line, x, y);
                y += UI.FONT().M.height();
            }
        }
    }

    @Override
    protected boolean back() {
        return true;
    }

    @Override
    protected void deactivateAction() {
        focusChat = false;
    }

    @Override
    public boolean canSave() {
        return true;
    }
}
