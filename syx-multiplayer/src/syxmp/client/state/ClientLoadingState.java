package syxmp.client.state;

import syxmp.client.MultiplayerSession;
import syxmp.client.SaveTransfer;
import syxmp.client.TickController;
import syxmp.common.Packets;
import syxmp.common.ScheduledCommand;
import syxmp.net.ByteReader;
import syxmp.net.ConnectionBase;
import syxmp.net.ConnectionStateEnum;
import syxmp.net.MpConnectionState;
import syxmp.util.MpLog;

/**
 * Receives the world blob, loads it, replays the command log to the current
 * tick, then reports ready. Port of ClientLoadingState.HandleWorldData.
 */
public final class ClientLoadingState extends MpConnectionState {

    private final MultiplayerSession session;

    public ClientLoadingState(ConnectionBase connection, MultiplayerSession session) {
        super(connection);
        this.session = session;
    }

    @Override
    public boolean handle(Packets packet, ByteReader data) {
        switch (packet) {
            case SERVER_WORLD_DATA_START -> {
                MpLog.info("world download starting");
                connection.fragmentProgress = pct -> MpLog.debug("world download " + pct + "%");
            }
            case SERVER_WORLD_DATA -> {
                connection.fragmentProgress = null;
                int tickUntil = data.readInt();
                long sentCmds = data.readLong();
                boolean frozen = data.readBool();
                int savedGameTimer = data.readInt();
                byte[] save = data.readBytes();
                int cmdCount = data.readInt();
                MpLog.info("world data: save " + save.length + "b at timer " + savedGameTimer
                        + ", " + cmdCount + " cmds, tickUntil " + tickUntil);

                session.cmds.clear();
                for (int i = 0; i < cmdCount; i++) {
                    byte[] cmdBytes = data.readBytes();
                    session.cmds.add(ScheduledCommand.deserialize(new ByteReader(cmdBytes)));
                }
                session.receivedCmds = sentCmds;
                session.remoteSentCmds = sentCmds;
                session.remoteTickUntil = tickUntil;

                TickController.setClock(savedGameTimer, tickUntil);
                TickController.frozen = frozen;
                syxmp.desync.SyncCoordinator.reset();
                TickController.simulatingTarget = tickUntil;
                TickController.onSimulatingDone = () -> {
                    connection.send(Packets.CLIENT_WORLD_READY);
                    MpLog.info("world ready");
                };
                connection.changeState(ConnectionStateEnum.CLIENT_PLAYING,
                        new ClientPlayingState(connection, session));

                // Kicks off GameLoader; the fast-forward runs frame by frame
                // once the loaded game's VIEW starts updating.
                SaveTransfer.apply(save, null);
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
