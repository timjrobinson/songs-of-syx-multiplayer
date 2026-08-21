package syxmp.desync;

import java.util.ArrayDeque;
import java.util.Deque;

import syxmp.client.JoinPoints;
import syxmp.client.Multiplayer;
import syxmp.client.TickController;
import syxmp.common.Packets;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;

/**
 * Collects local sync opinions, exchanges them (host is the reference, like
 * RW-MP without an arbiter), and flags desyncs. Port of SyncCoordinator.
 */
public final class SyncCoordinator {

    private static final int KEEP = 30;

    private static ClientSyncOpinion current;
    private static final Deque<ClientSyncOpinion> local = new ArrayDeque<>();
    private static final Deque<ClientSyncOpinion> remote = new ArrayDeque<>();

    private SyncCoordinator() {
    }

    public static void reset() {
        current = null;
        local.clear();
        remote.clear();
    }

    /** Called at every Timer unit boundary from TickController. */
    public static void onTimerUnit(int timer) {
        if (current != null && timer - current.startTimer >= ClientSyncOpinion.WINDOW)
            finishWindow();
        if (current == null) {
            current = new ClientSyncOpinion(timer - timer % ClientSyncOpinion.WINDOW);
            current.simulating = TickController.simulatingTarget >= 0;
            if (JoinPoints.lastJoinPointTimer >= current.startTimer) {
                current.joinPointTimer = JoinPoints.lastJoinPointTimer;
                current.joinPointHash = JoinPoints.lastJoinPointHash;
            }
        }
    }

    public static void recordSubStep(int fingerprint) {
        if (current != null)
            current.subStepStates.add(fingerprint);
    }

    public static void recordCommand(int fingerprint) {
        if (current != null)
            current.commandStates.add(fingerprint);
    }

    private static void finishWindow() {
        ClientSyncOpinion finished = current;
        current = null;
        local.add(finished);
        while (local.size() > KEEP)
            local.poll();

        // The host's opinion is the reference everyone compares against
        if (Multiplayer.session != null && Multiplayer.session.isHost) {
            ByteWriter w = new ByteWriter(finished.subStepStates.size() * 4 + 64);
            finished.serialize(w);
            Multiplayer.session.connection.send(Packets.CLIENT_SYNC_INFO, w);
        }
        tryCompare();
    }

    public static void addRemoteOpinion(ClientSyncOpinion opinion) {
        remote.add(opinion);
        while (remote.size() > KEEP)
            remote.poll();
        tryCompare();
    }

    private static void tryCompare() {
        java.util.List<ClientSyncOpinion> matched = new java.util.ArrayList<>();
        for (ClientSyncOpinion r : remote) {
            for (ClientSyncOpinion l : local) {
                if (l.startTimer != r.startTimer)
                    continue;
                // Fast-forwarding clients replay commands but their sub-step
                // trace can be incomplete; only equal-footing windows compare.
                if (l.simulating != r.simulating)
                    continue;
                String diff = l.compareTo(r);
                if (diff != null) {
                    onDesync(l.startTimer, diff);
                    return;
                }
                matched.add(r);
                break;
            }
        }
        remote.removeAll(matched);
    }

    private static void onDesync(int atTimer, String what) {
        if (TickController.desynced)
            return;
        TickController.desynced = true;
        MpLog.err("DESYNC: " + what);
        ByteWriter w = new ByteWriter(8);
        w.writeInt(atTimer);
        Multiplayer.session.connection.send(Packets.CLIENT_DESYNCED, w);
        game.GAME.Notify("SyxMP DESYNCED: " + what);
    }
}
