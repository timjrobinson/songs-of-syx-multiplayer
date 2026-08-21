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

    public static void render(Renderer r) {
        if (!Multiplayer.active() || broken)
            return;
        try {
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
            text.render(r, 24, 1200, 4, 28);
        } catch (Throwable t) {
            broken = true;
            MpLog.err("HUD render failed, disabling", t);
        }
    }
}
