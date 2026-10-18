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
    /** Exit diagnostics: who asked the engine to shut down (handler set in premain). */
    public static final int HOOK_CORE_ANNIHILATE = 5;
    public static final int HOOK_CORE_ANNIHILATE_T = 6;
    /** IMenu.exit2Menu: leaving a game ends the multiplayer session. */
    public static final int HOOK_EXIT_TO_MENU = 7;
    /** VIEW.update / VIEW.render entry: UI-context bracketing for VerbTrace. */
    public static final int HOOK_VIEW_UPDATE = 8;
    public static final int HOOK_VIEW_RENDER = 9;
    /** Pathfinder: never consume async results / never queue async jobs in MP. */
    public static final int HOOK_PATH_IS_PROCESSED = 10;
    public static final int HOOK_PATH_PREP = 11;
    /** Dev: RND draw tracing (hooks only installed with -Dsyxmp.rndtrace=1). */
    public static final int HOOK_RND_DRAW = 12;

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

        // Determinism catalogue ------------------------------------------
        // Per-frame game.afterTick() (VIEW.java:193) runs from the lockstep
        // loop in MP instead of once per rendered frame.
        t.redirects.add(new CallRedirect(
                "view/main/VIEW", "update",
                "game/GAME", "afterTick", "()V",
                "syxmp/client/Determinism", "afterTick"));
        // Pathfinder: SPath.resumeThreaded takes the async result if the
        // finder thread happens to be done (SPath.java:243) — timing
        // dependent. Force the synchronous branch and stop queueing jobs.
        t.hooks.add(new EntryHook("settlement/path/thread/FinderThread$ThreadPath",
                "isProcessed", "(IIII)Z", HOOK_PATH_IS_PROCESSED));
        t.hooks.add(new EntryHook("settlement/path/thread/FinderThread", "prep",
                "(Lsettlement/path/path/SPath;IIIIZ)V", HOOK_PATH_PREP));
        // Sim code that reads the local room-placement staging layer.
        for (String cls : new String[] {
                "settlement/tilemap/floor/Snow", "settlement/overlay/Addable",
                "settlement/room/industry/woodcutter/Constructor",
                "settlement/room/food/pasture/ConstructorOutdoor",
                "settlement/room/law/stockade/Constructor",
                "settlement/room/food/fish/Constructor",
                "settlement/room/food/farm/Constructor"}) {
            t.redirects.add(new CallRedirect(cls, null,
                    "snake2d/util/map/MAP_BOOLEAN", "is", "(I)Z",
                    "syxmp/client/Determinism", "embryoIs"));
            t.redirects.add(new CallRedirect(cls, null,
                    "snake2d/util/map/MAP_BOOLEAN", "is", "(II)Z",
                    "syxmp/client/Determinism", "embryoIs"));
        }

        // Dev-only RND call-site tracing (zero cost unless enabled: hooks absent).
        if ("1".equals(System.getProperty("syxmp.rndtrace"))) {
            String[][] rnd = {
                {"rInt", "()I"}, {"rBoolean", "()Z"}, {"rInt", "(I)I"}, {"rInt0", "(I)I"},
                {"rFloat", "()F"}, {"rFloatP", "(F)F"}, {"rFloat", "(D)F"}, {"rFloat1", "(D)F"},
                {"rFloat0", "(D)F"}, {"oneIn", "(I)Z"}, {"oneIn", "(D)Z"}, {"oneInD", "(D)Z"},
                {"rShort", "(I)S"}, {"rShort", "()S"}, {"rLong", "()J"}, {"rExpo", "()F"},
                {"rSign", "()D"},
            };
            for (String[] m : rnd)
                t.hooks.add(new EntryHook("snake2d/util/rnd/RND", m[0], m[1], HOOK_RND_DRAW));
        }

        // Speed controls become synced commands (or are suppressed) in MP.
        t.hooks.add(new EntryHook("game/GameSpeed", "speedSet", "(D)V", HOOK_SPEED_SET));
        t.hooks.add(new EntryHook("game/GameSpeed", "togglePause", "()V", HOOK_TOGGLE_PAUSE));
        t.hooks.add(new EntryHook("game/GameSpeed", "tmpPause", "()V", HOOK_TMP_PAUSE));
        t.hooks.add(new EntryHook("game/GameSpeed", "updateOnce", "()V", HOOK_UPDATE_ONCE));

        // Dev auto-pilot frame hook on the main menu.
        t.hooks.add(new EntryHook("menu/Menu", "update", "(FD)V", HOOK_MENU_UPDATE));

        // Exit diagnostics (logs the caller; never intercepts).
        t.hooks.add(new EntryHook("snake2d/CORE", "annihilate", "()V", HOOK_CORE_ANNIHILATE));
        t.hooks.add(new EntryHook("snake2d/CORE", "annihilate", "(Ljava/lang/Throwable;)V",
                HOOK_CORE_ANNIHILATE_T));

        t.hooks.add(new EntryHook("view/menu/IMenu", "exit2Menu", "()V", HOOK_EXIT_TO_MENU));
        t.hooks.add(new EntryHook("view/main/VIEW", "update", "(FD)V", HOOK_VIEW_UPDATE));
        t.hooks.add(new EntryHook("view/main/VIEW", "render", "(Lsnake2d/Renderer;F)V", HOOK_VIEW_RENDER));

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
