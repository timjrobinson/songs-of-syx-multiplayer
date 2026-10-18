package syxmp.ui;

import java.util.HashMap;
import java.util.Map;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;
import syxmp.client.Multiplayer;
import syxmp.client.MultiplayerSession;
import syxmp.common.Packets;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import view.main.VIEW;
import view.subview.GameWindow;

/**
 * Other players' mouse cursors on the settlement map (port of RW-MP's
 * cursor sync). Each client sends its cursor in world pixel coordinates at
 * ~10Hz; the server relays with the sender's id; receivers draw a tinted
 * cursor sprite with the player's name wherever that world point is on their
 * own screen (zoom/scroll independent).
 */
public final class MpCursors {

    public static final class Remote {
        public int px, py;
        public boolean onMap;
        public long lastSeen;
        public String name = "";
    }

    private static final Map<Integer, Remote> remote = new HashMap<>();
    private static final COLOR[] PALETTE = {
        new ColorImp(90, 200, 255), new ColorImp(255, 170, 60), new ColorImp(140, 255, 120),
        new ColorImp(255, 110, 200), new ColorImp(255, 240, 90), new ColorImp(180, 140, 255),
        new ColorImp(255, 120, 120), new ColorImp(120, 255, 230),
    };
    private static final long SEND_INTERVAL_MS = 100;
    private static final long STALE_MS = 3000;
    private static long lastSent;
    private static int lastPx = Integer.MIN_VALUE, lastPy;

    private MpCursors() {
    }

    public static COLOR color(int playerId) {
        return PALETTE[Math.floorMod(playerId, PALETTE.length)];
    }

    /** Called every game frame from the client UI driver. */
    public static void sendIfDue() {
        MultiplayerSession session = Multiplayer.session;
        if (session == null || session.connection == null)
            return;
        long now = System.currentTimeMillis();
        if (now - lastSent < SEND_INTERVAL_MS)
            return;
        boolean onMap = false;
        int px = 0, py = 0;
        if (VIEW.s().isActive()) {
            GameWindow w = VIEW.s().getWindow();
            onMap = w.view().holdsPoint(VIEW.mouse().x(), VIEW.mouse().y());
            px = w.pixel().x();
            py = w.pixel().y();
        }
        if (px == lastPx && py == lastPy && now - lastSent < 1000)
            return;
        lastSent = now;
        lastPx = px;
        lastPy = py;
        ByteWriter w = new ByteWriter(12);
        w.writeBool(onMap);
        w.writeInt(px);
        w.writeInt(py);
        session.connection.send(Packets.CLIENT_CURSOR, w);
    }

    /** SERVER_CURSOR payload: int playerId, bool onMap, int px, int py. */
    public static void receive(ByteReader data) {
        int id = data.readInt();
        Remote r = remote.computeIfAbsent(id, k -> new Remote());
        r.onMap = data.readBool();
        r.px = data.readInt();
        r.py = data.readInt();
        r.lastSeen = System.currentTimeMillis();
        MultiplayerSession session = Multiplayer.session;
        if (session != null)
            for (MultiplayerSession.PlayerListEntry e : session.players)
                if (e.id == id)
                    r.name = e.username;
    }

    public static void clear() {
        remote.clear();
    }

    /** Draw remote cursors; called from the script render hook (screen space, above the map). */
    public static void render(SPRITE_RENDERER r) {
        if (remote.isEmpty() || !VIEW.s().isActive())
            return;
        GameWindow win = VIEW.s().getWindow();
        int zoom = win.zoomout();
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Remote> e : remote.entrySet()) {
            Remote c = e.getValue();
            if (!c.onMap || now - c.lastSeen > STALE_MS)
                continue;
            int sx = win.pixels().screenX(c.px) >> zoom;
            int sy = win.pixels().screenY(c.py) >> zoom;
            if (!win.view().holdsPoint(sx, sy))
                continue;
            COLOR col = color(e.getKey());
            col.bind();
            UI.decor().mouse.render(r, sx, sy);
            COLOR.unbind();
            UI.FONT().S.render(r, c.name, sx + 18, sy + 18);
        }
    }
}
