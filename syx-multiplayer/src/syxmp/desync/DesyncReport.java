package syxmp.desync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

import syxmp.client.Multiplayer;
import syxmp.client.MultiplayerSession;
import syxmp.client.TickController;
import syxmp.common.MpVersion;
import syxmp.util.MpLog;

/**
 * Writes <data>/logs/syxmp-desync-<time>.txt when a desync is detected: both
 * sync opinions of the mismatching window with per-sub-step fingerprints,
 * the session clock, players and versions. The stand-in for RW-MP's desync
 * report zip; the rolling RND trace (dev flag) is dumped beside it.
 */
public final class DesyncReport {

    private DesyncReport() {
    }

    public static Path write(String what, Collection<ClientSyncOpinion> local, Collection<ClientSyncOpinion> remote) {
        try {
            Path dir = syxmp.agent.AgentInstaller.dataDir().resolve("logs");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path f = dir.resolve("syxmp-desync-" + stamp + ".txt");
            StringBuilder sb = new StringBuilder(8192);
            sb.append("Syx Multiplayer desync report ").append(stamp).append('\n');
            sb.append("mod ").append(MpVersion.VERSION).append(" protocol ").append(MpVersion.PROTOCOL);
            try {
                sb.append(" game ").append(game.VERSION.VERSION_STRING);
            } catch (Throwable t) {
                // no game
            }
            sb.append('\n');
            sb.append("what: ").append(what).append('\n');
            MultiplayerSession session = Multiplayer.session;
            if (session != null) {
                sb.append("role: ").append(session.isHost ? "host" : "client").append(" player ")
                  .append(session.playerId).append(" (").append(session.username).append(")\n");
                sb.append("players:");
                for (MultiplayerSession.PlayerListEntry e : session.players)
                    sb.append(' ').append(e.username).append('#').append(e.id);
                sb.append('\n');
                sb.append("rngSeed ").append(Long.toHexString(session.rngSeed)).append('\n');
            }
            sb.append("timer ").append(TickController.timer).append('/').append(TickController.tickUntil)
              .append(" speed ").append(TickController.speed)
              .append(" accum ").append(TickController.timeToTickThrough).append('\n');
            try {
                sb.append("updateI ").append(game.GAME.updateI()).append('\n');
            } catch (Throwable t) {
                // no game
            }
            sb.append("os ").append(System.getProperty("os.name")).append(" java ")
              .append(System.getProperty("java.version")).append('\n');
            sb.append("\n== local windows ==\n");
            for (ClientSyncOpinion o : local)
                dump(sb, o);
            sb.append("\n== remote (reference) windows ==\n");
            for (ClientSyncOpinion o : remote)
                dump(sb, o);
            Files.writeString(f, sb, StandardCharsets.UTF_8);
            MpLog.info("desync report written: " + f);
            return f;
        } catch (IOException | RuntimeException e) {
            MpLog.err("could not write desync report", e);
            return null;
        }
    }

    private static void dump(StringBuilder sb, ClientSyncOpinion o) {
        sb.append("window ").append(o.startTimer).append(o.simulating ? " (simulating)" : "")
          .append(" substeps ").append(o.subStepStates.size())
          .append(" commands ").append(o.commandStates.size());
        if (o.joinPointTimer >= 0)
            sb.append(" joinpoint ").append(o.joinPointTimer).append(':').append(Long.toHexString(o.joinPointHash));
        sb.append('\n');
        for (int i = 0; i < o.subStepStates.size(); i++)
            sb.append("  t").append(o.subStepTimer.get(i)).append(" u").append(o.subStepUpdateI.get(i))
              .append(' ').append(Integer.toHexString(o.subStepStates.get(i))).append('\n');
        for (int i = 0; i < o.commandStates.size(); i++)
            sb.append("  cmd#").append(i).append(' ').append(Integer.toHexString(o.commandStates.get(i))).append('\n');
    }
}
