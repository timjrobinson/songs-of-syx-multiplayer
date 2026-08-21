package syxmp.client;

import game.GAME;
import snake2d.util.rnd.RND;
import syxmp.common.ScheduledCommand;
import syxmp.net.ByteReader;
import syxmp.sync.Sync;
import syxmp.util.MpLog;

/**
 * Executes a scheduled command at its designated tick, identically on every
 * client. Port of the ExecuteCmd halves of AsyncWorldTimeComp/AsyncTimeComp.
 */
public final class CommandExecutor {

    /** True while the currently executing command was issued by this client. */
    public static boolean currentCmdIssuedBySelf;

    private CommandExecutor() {
    }

    public static void execute(ScheduledCommand cmd) {
        MultiplayerSession session = Multiplayer.session;
        Multiplayer.executingCmds = true;
        currentCmdIssuedBySelf = cmd.playerId == session.playerId && TickController.simulatingTarget < 0;
        try {
            // Commands consume RND deterministically no matter what ran before
            RND.setSeed(TickController.mixSeed(session.rngSeed ^ 0x5DEECE66DL, cmd.ticks));
            ByteReader data = new ByteReader(cmd.data);
            switch (cmd.type) {
                case GLOBAL_TIME_SPEED -> {
                    double speed = data.readDouble();
                    TickController.speed = speed;
                    if (speed != 0)
                        TickController.prevNonZeroSpeed = speed;
                    MpLog.debug("speed set to " + speed + " at " + cmd.ticks);
                }
                case PAUSE_ALL -> TickController.speed = 0;
                case CREATE_JOIN_POINT -> JoinPoints.createJoinPoint(cmd.ticks);
                case INIT_PLAYER_DATA -> {
                    // Per-player permissions etc.; nothing synced yet
                }
                case SYNC -> Sync.handleSyncCommand(data);
                case DEBUG_TOOLS -> {
                    // Not implemented yet
                }
            }
        } catch (Exception e) {
            MpLog.err("error executing " + cmd, e);
            GAME.Notify("SyxMP: error executing command " + cmd.type);
        } finally {
            currentCmdIssuedBySelf = false;
            Multiplayer.executingCmds = false;
        }
    }
}
