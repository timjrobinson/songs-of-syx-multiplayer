package syxmp.client;

import init.sprite.UI.UI;
import snake2d.Renderer;
import syxmp.client.state.ClientPlayingState;
import syxmp.util.MpLog;
import util.gui.misc.GText;

/**
 * Minimal always-on multiplayer HUD drawn from the script render hook:
 * connection status, clock, players, last chat lines. A real UI panel
 * (RW-MP's chat window / player list) comes later.
 */
public final class MpHud {

    private static GText text;
    private static String lastStatus = "";
    private static boolean broken;

    private MpHud() {
    }

    /** Screen rectangle of the status line; clicking it opens the panel. */
    public static final int X1 = 24, Y1 = 4, Y2 = 28;
    public static int x2 = 24;

    public static void render(Renderer r) {
        if (broken)
            return;
        if (!Multiplayer.active()) {
            if (!syxmp.ui.MpStatus.line.isEmpty()
                    && System.currentTimeMillis() - syxmp.ui.MpStatus.changedAt < 15000) {
                UI.FONT().M.render(r, syxmp.ui.MpStatus.line, X1, Y1);
            }
            return;
        }
        try {
            syxmp.ui.MpCursors.render(r);
            StringBuilder sb = new StringBuilder(128);
            MultiplayerSession session = Multiplayer.session;
            sb.append("MP ");
            if (TickController.desynced)
                sb.append("[DESYNCED] ");
            else if (TickController.simulatingTarget >= 0)
                sb.append("[catching up] ");
            else if (TickController.frozen)
                sb.append("[frozen] ");
            sb.append(session.isHost ? "hosting" : "client");
            sb.append(" | t ").append(TickController.timer)
                    .append('/').append(TickController.tickUntil);
            sb.append(" | speed ").append(TickController.speed);
            sb.append(" | players ").append(Math.max(1, session.players.size()));
            synchronized (ClientPlayingState.ChatLog.class) {
                int i = 0;
                for (String line : ClientPlayingState.ChatLog.lines) {
                    if (++i > ClientPlayingState.ChatLog.lines.size() - 3)
                        sb.append("  ||  ").append(line);
                }
            }
            String status = sb.toString();
            if (!status.equals(lastStatus) || text == null) {
                lastStatus = status;
                text = new GText(UI.FONT().M, status);
            }
            text.render(r, X1, X1 + text.width(), Y1, Y2);
            x2 = X1 + text.width();
        } catch (Throwable t) {
            broken = true;
            MpLog.err("HUD render failed, disabling", t);
        }
    }
}
