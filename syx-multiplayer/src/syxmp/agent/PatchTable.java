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

    /** Dev auto-pilot: fires every main-menu frame (handler set in premain). */
    public static final int HOOK_MENU_UPDATE = 4;

    // Sync-method hooks (bound to SyncMethod handlers by SyxSync) start at 100.
    public static final int HOOK_IMMIGRATION_ADMIT = 100;

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

        // Dev auto-pilot frame hook on the main menu.
        t.hooks.add(new EntryHook("menu/Menu", "update", "(FD)V", HOOK_MENU_UPDATE));

        // Synced player verbs (see SyxSync.registerAll for the wire handlers).
        t.hooks.add(new EntryHook("settlement/entry/Immigration", "admit",
                "(Linit/race/Race;I)V", HOOK_IMMIGRATION_ADMIT));

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
