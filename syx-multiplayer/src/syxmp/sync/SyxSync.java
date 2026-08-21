package syxmp.sync;

import init.race.Race;
import settlement.entry.Immigration;
import settlement.main.SETT;
import syxmp.agent.PatchTable;
import syxmp.agent.SyncTemplates;
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

        bind(PatchTable.HOOK_IMMIGRATION_ADMIT,
                Sync.method(Immigration.class, "admit", Race.class, int.class)
                        .instance(() -> SETT.ENTRY().immi()));

        Sync.postInit();
        MpLog.info("SyxSync registered " + Sync.handlerCount() + " handlers");
    }

    /** Connect an agent entry hook to a sync handler: UI calls become commands. */
    private static void bind(int hookId, SyncMethod method) {
        SyncTemplates.registerAt(hookId, (self, args) -> Sync.trySync(method, self, args));
    }
}
