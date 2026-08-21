package syxmp.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Declarative list of bytecode patches — the analogue of RW-MP's patch
 * registrations. Two kinds:
 *
 * - CallRedirect: inside one class, rewrite calls to a method into a static
 *   call (receiver becomes the first parameter). Used to take over the
 *   VIEW -> game.update edge, and later to reroute RND/view-state reads in
 *   sim code (the Determinism catalogue).
 * - EntryHook: inject the SyncTemplates.general(...) guard at method entry.
 *
 * IMPORTANT: this class runs at premain time — it must not reference any
 * game or syxmp.client class.
 */
public final class PatchTable {

    // Fixed hook ids (see SyncTemplates). Append only.
    public static final int HOOK_SPEED_SET = 0;
    public static final int HOOK_TOGGLE_PAUSE = 1;
    public static final int HOOK_TMP_PAUSE = 2;
    public static final int HOOK_UPDATE_ONCE = 3;

    public record CallRedirect(
            String inClass,        // internal name, e.g. "view/main/VIEW"
            String inMethod,       // method name filter, null = all methods
            String targetOwner,    // owner of the call being redirected
            String targetName,
            String targetDesc,
            String newOwner,       // static replacement: (TargetOwner, args...) same return
            String newName) {
    }

    public record EntryHook(
            String owner,          // internal name, e.g. "game/GameSpeed"
            String name,
            String desc,
            int hookId) {
    }

    public final List<CallRedirect> redirects = new ArrayList<>();
    public final List<EntryHook> hooks = new ArrayList<>();

    public static PatchTable build() {
        PatchTable t = new PatchTable();

        // The lockstep takeover: VIEW.update's game.update(ds, slowDown) call
        // (view/main/VIEW.java:257 in V71.44) becomes TickController.gameUpdate.
        t.redirects.add(new CallRedirect(
                "view/main/VIEW", "update",
                "game/GAME", "update", "(DD)V",
                "syxmp/client/TickController", "gameUpdate"));

        // Speed controls become synced commands (or are suppressed) in MP.
        t.hooks.add(new EntryHook("game/GameSpeed", "speedSet", "(D)V", HOOK_SPEED_SET));
        t.hooks.add(new EntryHook("game/GameSpeed", "togglePause", "()V", HOOK_TOGGLE_PAUSE));
        t.hooks.add(new EntryHook("game/GameSpeed", "tmpPause", "()V", HOOK_TMP_PAUSE));
        t.hooks.add(new EntryHook("game/GameSpeed", "updateOnce", "()V", HOOK_UPDATE_ONCE));

        return t;
    }

    public boolean touches(String internalClassName) {
        for (CallRedirect r : redirects)
            if (r.inClass.equals(internalClassName))
                return true;
        for (EntryHook h : hooks)
            if (h.owner.equals(internalClassName))
                return true;
        return false;
    }
}
