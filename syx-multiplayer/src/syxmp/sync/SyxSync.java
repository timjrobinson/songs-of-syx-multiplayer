package syxmp.sync;

import syxmp.util.MpLog;

/**
 * The Songs of Syx sync registration table — the analogue of RW-MP's
 * SyncMethods.cs/SyncFields.cs/SyncDelegates.cs. Registration order is part
 * of the wire protocol: ONLY APPEND, never reorder or remove (bump
 * MpVersion.PROTOCOL on any change here).
 *
 * Interception of these methods in UI context is done by the agent's
 * bytecode patches (syxmp.agent); this class defines what goes on the wire
 * and how targets/arguments are resolved.
 */
public final class SyxSync {

    private SyxSync() {
    }

    public static void registerAll() {
        SyxSerialization.registerAll();

        // --- Handlers append below this line, never above ---

        Sync.postInit();
        MpLog.info("SyxSync registered " + Sync.handlerCount() + " handlers");
    }
}
