package syxmp.agent;

/**
 * Runtime dispatch for injected method-entry hooks, port of RW-MP's
 * SyncTemplates. The transformer emits, at the entry of each patched method:
 *
 *   if (SyncTemplates.general(hookId, this, new Object[]{args...}))
 *       return <default>;
 *
 * Hook ids are fixed constants (PatchTable.HOOK_*) so the transformer can
 * reference them at premain time; handlers attach later during game init
 * (Hooks.installAll). An id with no handler falls through to the original
 * body, so a partially initialized mod degrades to vanilla behavior.
 * Hook ids are local constants, not part of the wire protocol.
 */
public final class SyncTemplates {

    public interface EntryHandler {
        /** @return true to skip the original method body. */
        boolean invoke(Object self, Object[] args);
    }

    private static final int MAX_HOOKS = 4096;
    private static final EntryHandler[] handlers = new EntryHandler[MAX_HOOKS];

    private SyncTemplates() {
    }

    public static void registerAt(int id, EntryHandler handler) {
        if (handlers[id] != null)
            throw new IllegalStateException("hook id " + id + " already registered");
        handlers[id] = handler;
    }

    /** Called from injected bytecode. Must never throw. */
    public static boolean general(int id, Object self, Object[] args) {
        try {
            EntryHandler h = id >= 0 && id < MAX_HOOKS ? handlers[id] : null;
            return h != null && h.invoke(self, args);
        } catch (Throwable t) {
            System.err.println("[SyxMP] entry hook " + id + " failed: " + t);
            t.printStackTrace();
            return false;
        }
    }
}
