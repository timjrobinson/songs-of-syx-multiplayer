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
    /** GameLoader.getState entry: deterministic RND seed for game construction in MP. */
    public static final int HOOK_GAME_LOAD = 13;
    /** PlacableMultiTool.place(): area tools commit here. */
    public static final int HOOK_TOOL_MULTI_PLACE = 14;
    /** ILoadScreen.activate: called by GameLoader between GAME/VIEW creation and the save load. */
    public static final int HOOK_LOAD_SCREEN = 15;
    /** BattleThreads.unpause: never start the wall-clock battle workers in MP (BattleSync drives them). */
    public static final int HOOK_BATTLE_UNPAUSE = 16;
    /** Dev: entity update tracing (hooks only with -Dsyxmp.enttrace=1). */
    public static final int HOOK_ENT_UPDATE = 17;

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
        // Battle AI worker threads run for every game in vanilla; in MP their
        // jobs run synchronously from the sub-step instead (BattleSync).
        t.hooks.add(new EntryHook("game/battle/thread/BattleThreads", "unpause", "(Z)V", HOOK_BATTLE_UNPAUSE));

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

        // Game construction/loading consumes RND for unsaved cosmetic state
        // (e.g. TForest sprite animators seed speeds from RND and keep
        // drawing inside the sim tick). Seeding before every MP load makes
        // that state identical on all clients (they all load the same blob
        // at the same timer).
        t.hooks.add(new EntryHook("game/save/GameLoader", "getState", "()Lsnake2d/CORE_STATE;",
                HOOK_GAME_LOAD));
        // ...and again after GAME+VIEW construction, right before the save
        // data is loaded (GameLoader.java:57): UI construction in between
        // draws from RND in locally varying amounts (e.g. the battle editor
        // generates random armies through sim constructors), which would
        // shift every RND-initialized field of the entity pools built during
        // the load.
        t.hooks.add(new EntryHook("view/interrupter/ILoadScreen", "activate", "()V", HOOK_LOAD_SCREEN));

        // Cosmetic RND users inside the sim tick get their own stream.
        addVisualRnd(t);
        // Per-process random lookup tables built in static initializers get a
        // fixed seed so every client builds the same table.
        for (String cls : new String[] {"util/updating/TileUpdater", "util/data/RANMAP"})
            for (String[] m : new String[][] {{"rInt", "(I)I"}, {"rInt", "()I"}, {"rFloat", "()F"}, {"rBoolean", "()Z"}})
                t.redirects.add(new CallRedirect(cls, null, "snake2d/util/rnd/RND", m[0], m[1],
                        "syxmp/client/DetRnd", "*"));

        if ("1".equals(System.getProperty("syxmp.enttrace"))) {
            t.hooks.add(new EntryHook("settlement/entity/humanoid/Humanoid", "update", "(D)Z", HOOK_ENT_UPDATE));
            t.hooks.add(new EntryHook("settlement/entity/animal/Animal", "update", "(D)Z", HOOK_ENT_UPDATE));
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

        // Map tools: commit points become TOOL commands (ToolSync).
        t.hooks.add(new EntryHook("view/tool/PlacableMultiTool", "place", "()V", HOOK_TOOL_MULTI_PLACE));
        t.redirects.add(new CallRedirect("view/tool/PlacableSimpleTool", null,
                "view/tool/PlacableSimple", "place", "(II)V", "syxmp/sync/ToolSync", "placeSimple"));
        t.redirects.add(new CallRedirect("view/tool/PlacableSimpleTileTool", null,
                "view/tool/PlacableSimpleTile", "place", "(II)V", "syxmp/sync/ToolSync", "placeSimpleTile"));
        t.redirects.add(new CallRedirect("view/tool/PlacableFixedTool", null,
                "view/tool/PlacableFixed", "place", "(IIII)V", "syxmp/sync/ToolSync", "placeFixed"));
        t.redirects.add(new CallRedirect("view/tool/PlacableSingleTool", null,
                "view/tool/PlacableSingle", "placeFirst", "(II)V", "syxmp/sync/ToolSync", "placeFirst"));
        t.redirects.add(new CallRedirect("view/tool/PlacableSingleTool", null,
                "view/tool/PlacableSingle", "placeExpanded", "(II)V", "syxmp/sync/ToolSync", "placeExpanded"));

        // Audio picks random variants through LIST.rnd(): keep it off the sim stream.
        t.redirects.add(new CallRedirect("game/audio/*", null,
                "snake2d/util/sets/LIST", "rnd", "()Ljava/lang/Object;", "syxmp/client/VisualRnd", "listRnd"));

        // Generic setter sync (AutoSyncTable): one hook id per entry.
        for (int i = 0; i < AutoSyncTable.METHODS.length; i++)
            t.hooks.add(new EntryHook(AutoSyncTable.owner(i), AutoSyncTable.name(i),
                    AutoSyncTable.desc(i), AutoSyncTable.FIRST_HOOK_ID + i));

        // Synced player verbs (see SyxSync.registerAll for the wire handlers).
        t.hooks.add(new EntryHook("settlement/entry/Immigration", "admit",
                "(Linit/race/Race;I)V", HOOK_IMMIGRATION_ADMIT));

        return t;
    }

    /** Classes whose RND use is purely cosmetic but runs inside the sim tick. */
    private static final String[] VISUAL_RND_CLASSES = {
        "settlement/tilemap/terrain/TForest$Sprites$TForestTop",
        "util/rendering/Minimap",          // minimap regeneration (192 draws/tile row) in TileMap.afterTick
        "snake2d/util/light/Fire",         // light flicker (UI light cycle, torches)
        "game/audio/*",                    // random sound variants, only drawn when audible
        // Whole visual layers: sprite composition at load, UI, rendering, menus.
        "util/spritecomposer/*", "init/sprite/*", "util/rendering/*", "snake2d/util/light/*",
        "util/gui/*", "menu/*", "view/*",
        // Render helpers constructed lazily (first game in the process only)
        // and sprite classes: e.g. settlement.battle.BannerRenderer draws 4
        // times in its constructor — only on the client whose first game it is.
        "*Renderer*", "*Sprite*", "*Particle*", "*Animation*",
        // Gore pools: 5000 cosmetic objects randomized at SETT construction.
        "settlement/thing/Gore*", "settlement/thing/ThingsGore",
    };

    private static final String[][] RND_METHODS = {
        {"rInt", "()I"}, {"rBoolean", "()Z"}, {"rInt", "(I)I"}, {"rInt0", "(I)I"},
        {"rFloat", "()F"}, {"rFloat", "(D)F"}, {"rFloat1", "(D)F"},
        {"rFloat0", "(D)F"}, {"oneIn", "(I)Z"}, {"oneIn", "(D)Z"}, {"oneInD", "(D)Z"},
        {"rShort", "(I)S"}, {"rShort", "()S"}, {"rLong", "()J"}, {"rExpo", "()F"},
        {"rSign", "()D"},
    };

    private static void addVisualRnd(PatchTable t) {
        for (String cls : VISUAL_RND_CLASSES) {
            for (String[] m : RND_METHODS)
                t.redirects.add(new CallRedirect(cls, null, "snake2d/util/rnd/RND", m[0], m[1],
                        "syxmp/client/VisualRnd", "*"));
            // DEG.setRandom() draws from RND internally.
            t.redirects.add(new CallRedirect(cls, null, "snake2d/util/datatypes/DEG", "setRandom", "()V",
                    "syxmp/client/VisualRnd", "degSetRandom"));
        }
    }

    static boolean classMatches(String pattern, String internalClassName) {
        if (pattern.startsWith("*") && pattern.endsWith("*"))
            return internalClassName.contains(pattern.substring(1, pattern.length() - 1));
        if (pattern.endsWith("*"))
            return internalClassName.startsWith(pattern.substring(0, pattern.length() - 1));
        return pattern.equals(internalClassName);
    }

    public boolean touches(String internalClassName) {
        for (CallRedirect r : redirects)
            if (classMatches(r.inClass, internalClassName))
                return true;
        for (EntryHook h : hooks)
            if (h.owner.equals(internalClassName))
                return true;
        return false;
    }
}
