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
    /** GameSaver.autoSave: wall-clock autosave never fires in MP (stalls one client, writes host-side). */
    public static final int HOOK_AUTOSAVE = 18;
    /** GAME.Warn / GAME.Error branch on the local DEVELOPER/DEBUG launcher flags: uniform in MP. */
    public static final int HOOK_GAME_WARN = 19;
    public static final int HOOK_GAME_ERROR = 20;
    /** Dev: short-range pathfinder requests (with -Dsyxmp.enttrace=1). */
    public static final int HOOK_SPATH_FIND = 21;
    /** Tactical battle orders: BattleOrder.Locked.set and Armies.initAndTeleport become ACTION commands (BattleOrderSync). */
    public static final int HOOK_BATTLE_ORDER_SET = 22;
    public static final int HOOK_ARMIES_TELEPORT = 23;
    /** UIWBattlePrompt.* entry: records the pending world-battle prompt, pauses the clock (WorldBattleSync). */
    public static final int HOOK_WBATTLE_PROMPT = 24;
    /** World army/region click handlers with UI-local editors (ArmySync). */
    public static final int HOOK_RECRUIT = 25;
    public static final int HOOK_MERC_HIRE = 26;
    public static final int HOOK_AUTONOMY = 27;
    /** Settlement tools / buttons with UI-local state (RoomToolSync). */
    public static final int HOOK_HOME_ASSIGN = 28;
    public static final int HOOK_RACE_ASSIGN = 29;
    public static final int HOOK_HOME_ODD = 30;
    public static final int HOOK_HOSPITAL_FETCH = 31;
    public static final int HOOK_MOVE_AUTO = 32;
    public static final int HOOK_MOVE_SET = 33;
    public static final int HOOK_MOVE_REMOVE = 34;
    public static final int HOOK_MOVE_PASTE = 35;
    /** Map mark placement (NameSync): random colour inside the command. */
    public static final int HOOK_MARK_ADD = 36;
    /** UIRoomPlacer.init(RoomInstanceImp): refurnish removes the room and re-stages the shared placer. */
    public static final int HOOK_REFURNISH = 37;
    /** GameLoader.set() inside a session: a vanilla load (battle exit/restart) is handled like a join-point reload. */
    public static final int HOOK_GAME_LOADER_SET = 38;
    /** Generated battles (GenBattleSync): IDeploy / ILiveConclude bookkeeping and the result screen's close. */
    public static final int HOOK_IDEPLOY_UPDATE = 39;
    public static final int HOOK_CONCLUDE_UPDATE = 40;
    public static final int HOOK_CONCLUDE_CLOSE = 41;

    // Sync-method hooks (bound to SyncMethod handlers by SyxSync) start at 100.
    public static final int HOOK_IMMIGRATION_ADMIT = 100;

    public record CallRedirect(
            String inClass,        // internal name, e.g. "view/main/VIEW"
            String inMethod,       // method name filter, null = all methods
            String targetOwner,    // owner of the call being redirected
            String targetName,
            String targetDesc,
            String newOwner,       // static replacement: (TargetOwner, args...) same return
            String newName,
            boolean objectReceiver // the replacement takes the receiver as java.lang.Object
                                   // (for package-private game classes the mod cannot name)
    ) {
        public CallRedirect(String inClass, String inMethod, String targetOwner, String targetName,
                String targetDesc, String newOwner, String newName) {
            this(inClass, inMethod, targetOwner, targetName, targetDesc, newOwner, newName, false);
        }
    }

    /**
     * Inside classes matching inClass, rewrite PUTFIELD owner.name:desc into
     * FieldSync.put*(receiver, value, "owner name desc") — UI code that
     * assigns sim fields directly (no setter to hook).
     */
    public record FieldRedirect(String inClass, String owner, String name, String desc) {
    }

    public record EntryHook(
            String owner,          // internal name, e.g. "game/GameSpeed"
            String name,
            String desc,
            int hookId) {
    }

    public final List<CallRedirect> redirects = new ArrayList<>();
    public final List<EntryHook> hooks = new ArrayList<>();
    public final List<FieldRedirect> fieldRedirects = new ArrayList<>();

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
        // dependent. In MP the job runs synchronously inside prep (PathSync),
        // so the result is always ready on the next resume, identically.
        t.hooks.add(new EntryHook("settlement/path/thread/FinderThread", "prep",
                "(Lsettlement/path/path/SPath;Lsettlement/path/thread/FinderThread$ThreadPathJob;IIIIZ)V",
                HOOK_PATH_PREP));
        // Battle AI worker threads run for every game in vanilla; in MP their
        // jobs run synchronously from the sub-step instead (BattleSync).
        t.hooks.add(new EntryHook("game/battle/thread/BattleThreads", "unpause", "(Z)V", HOOK_BATTLE_UNPAUSE));

        // Local-only state the sim must not read: autosave (wall-clock, one
        // client), GAME.Warn/Error (pause/throw depending on the local
        // launcher flags; both test homes run DEVELOPER=1, players run 0).
        t.hooks.add(new EntryHook("game/save/GameSaver", "autoSave", "(D)V", HOOK_AUTOSAVE));
        t.hooks.add(new EntryHook("game/GAME", "Warn", "(Ljava/lang/String;)V", HOOK_GAME_WARN));
        t.hooks.add(new EntryHook("game/GAME", "Error", "(Ljava/lang/String;)V", HOOK_GAME_ERROR));

        // The battle VIEW gates simulation: GAME.java:260 skips every
        // non-battle GameResource while the local battle view is open, and
        // births, equipment drops, entry, disease and weather consult it too
        // (StatsReproduction.java:221, Humanoid.java:443, SENTRY.java:42,
        // EntryUpdater.java:48/97, disease/Updater.java:27, WeatherMoisture
        // .java:87, SETT.java:706/730, GCOUNTS.java:220). One player opening
        // the view would fork the sims; in MP the sim always sees it closed.
        for (String cls : new String[] {
                "game/GAME", "game/values/GCOUNTS", "settlement/entity/humanoid/Humanoid",
                "settlement/entry/SENTRY", "settlement/entry/EntryUpdater", "settlement/main/SETT",
                "settlement/weather/WeatherMoisture", "settlement/stats/disease/Updater",
                "settlement/stats/colls/StatsReproduction", "world/battle/WBattles"})
            t.redirects.add(new CallRedirect(cls, null,
                    "view/battle/BattleView", "isActive", "()Z",
                    "syxmp/client/Determinism", "battleViewActive"));
        // WBattles.canPoll (WBattles.java:105) also waits for the local
        // world-battle prompt to close; with the prompt decisions synced
        // (WorldBattleSync) it is identical everywhere, and the issuer stays
        // "busy" until its decision executes.
        t.redirects.add(new CallRedirect("world/battle/WBattles", null,
                "view/world/ui/battle/UIWBattlePrompt", "isBusty", "()Z",
                "syxmp/client/Determinism", "worldBattlePromptBusy"));

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
        // (TileUpdater's table is built in <clinit> and handled by ClinitRnd.)
        for (String cls : new String[] {"util/data/RANMAP"})
            for (String[] m : new String[][] {{"rInt", "(I)I"}, {"rInt", "()I"}, {"rFloat", "()F"}, {"rBoolean", "()Z"}})
                t.redirects.add(new CallRedirect(cls, null, "snake2d/util/rnd/RND", m[0], m[1],
                        "syxmp/client/DetRnd", "*"));

        if ("1".equals(System.getProperty("syxmp.enttrace"))) {
            t.hooks.add(new EntryHook("settlement/entity/humanoid/Humanoid", "update", "(D)Z", HOOK_ENT_UPDATE));
            t.hooks.add(new EntryHook("settlement/entity/animal/Animal", "update", "(D)Z", HOOK_ENT_UPDATE));
            t.redirects.add(new CallRedirect("settlement/path/path/SPath", null,
                    "settlement/path/finders/SPathFinder", "find", "(IIIIZ)Lsnake2d/PathTile;",
                    "syxmp/dev/PathProbe", "find"));
            t.redirects.add(new CallRedirect("settlement/path/finders/SFinderRND", null,
                    "settlement/path/components/finder/SCompFinder", "fill",
                    "(III)Lsettlement/path/components/finder/SCompFinder$SCompPath;",
                    "syxmp/dev/PathProbe", "fill"));
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

        // UI call sites that write sim state through generic containers
        // (RMapInt/ArrayInt/DataO/INT_OE setters are used everywhere in the
        // sim, so they are redirected only inside the UI classes that use
        // them for player settings; UiRedirects sends the call as SYNC_AUTO).
        for (String ui : UI_GENERIC_SETTER_SITES) {
            t.redirects.add(new CallRedirect(ui, null, "util/data/INT_O$INT_OE", "set", "(Ljava/lang/Object;I)V",
                    "syxmp/sync/UiRedirects", "intOeSet"));
            t.redirects.add(new CallRedirect(ui, null, "util/data/INT_O$INT_OE", "inc", "(Ljava/lang/Object;I)V",
                    "syxmp/sync/UiRedirects", "intOeInc"));
            t.redirects.add(new CallRedirect(ui, null, "util/data/DataO$DataAbs", "set", "(Ljava/lang/Object;I)V",
                    "syxmp/sync/UiRedirects", "dataAbsSet"));
            t.redirects.add(new CallRedirect(ui, null, "util/data/DataO$DataBit", "set", "(Ljava/lang/Object;I)V",
                    "syxmp/sync/UiRedirects", "dataBitSet"));
            t.redirects.add(new CallRedirect(ui, null, "util/keymap/RMapInt", "set", "(Lutil/keymap/MAPPED;I)V",
                    "syxmp/sync/UiRedirects", "rMapIntSet"));
            t.redirects.add(new CallRedirect(ui, null, "util/keymap/RMapInt", "set", "(Ljava/lang/Object;I)V",
                    "syxmp/sync/UiRedirects", "rMapIntSetO"));
            t.redirects.add(new CallRedirect(ui, null, "snake2d/util/sets/ArrayInt", "set", "(II)Lsnake2d/util/sets/ArrayInt;",
                    "syxmp/sync/UiRedirects", "arrayIntSet"));
        }

        // Composite UI actions (SyncActions): the one call in the handler
        // that commits the action is redirected and the rest is skipped.
        t.redirects.add(new CallRedirect("game/event/engine/EventMessage*", null,
                "game/event/engine/EVENT_HANDLER", "choiceSelect", "(Lgame/event/engine/Event;I)V",
                "syxmp/sync/SyncActions", "eventChoiceSelect"));
        t.redirects.add(new CallRedirect("game/event/engine/EventMessage*", null,
                "game/event/actions/EventAction", "exe", "(Lgame/event/engine/Event;Lgame/event/engine/EContext;)V",
                "syxmp/sync/SyncActions", "eventActionExe"));
        t.redirects.add(new CallRedirect("game/event/engine/EventMessage*", null,
                "game/event/engine/EVENT_HANDLER", "set", "(Lgame/event/engine/Event;ZZZZ)V",
                "syxmp/sync/SyncActions", "eventSet"));
        for (String ui : new String[] {"view/world/ui/faction/UIDiplomacy*", "view/ui/diplomacy/UIDipMessDeal*"})
            t.redirects.add(new CallRedirect(ui, null,
                    "game/faction/diplomacy/deal/Deal", "execute", "(Z)D",
                    "syxmp/sync/SyncActions", "dealExecute"));
        // The raid demand (MessDemand.java:182 pay, :206 decline): pay(),
        // raiders.current.clear() and the message hide are one decision,
        // executed on every client; Demand is package-private (Object receiver).
        t.redirects.add(new CallRedirect("game/raiding/MessDemand*", null,
                "game/raiding/MessDemand$Demand", "pay", "()V",
                "syxmp/sync/SyncActions", "tributePay", true));
        t.redirects.add(new CallRedirect("game/raiding/MessDemand$Demand$*", null,
                "game/raiding/RaidingCurrent", "clear", "()V",
                "syxmp/sync/SyncActions", "raidersClear"));
        t.redirects.add(new CallRedirect("game/raiding/MessDemand$Demand$*", null,
                "view/ui/message/Messages", "hide", "()V",
                "syxmp/sync/SyncActions", "tributeHide"));
        t.redirects.add(new CallRedirect("view/world/ui/region/PlayBuildingsPop*", null,
                "world/region/building/RDLevelsTmp", "accept", "()V",
                "syxmp/sync/SyncActions", "regionBuildAccept"));
        // The building popup's draft levels must never reach the sim.
        t.redirects.add(new CallRedirect("world/region/building/RDBuilding*", null,
                "world/region/building/RDLevelsTmp", "level", "(Lworld/region/building/RDBuilding;Lworld/map/regions/Region;)I",
                "syxmp/client/Determinism", "rdLevel"));

        // Tactical battle orders: every order from the battle UI ends in
        // div.order().dest/task.set(payload) (BattleOrder.java:60, called
        // from UISelection, BattlePlacer*, DivDeployerUser) or, while a
        // generated battle is deploying, Armies.initAndTeleport
        // (Armies.java:236). Both are shipped by value (BattleOrderSync).
        t.hooks.add(new EntryHook("game/battle/thread/order/BattleOrder$Locked", "set",
                "(Lgame/battle/util/Copyable;)V", HOOK_BATTLE_ORDER_SET));
        t.hooks.add(new EntryHook("game/battle/Armies", "initAndTeleport",
                "(Lsnake2d/util/sets/LIST;)V", HOOK_ARMIES_TELEPORT));

        // World battle prompts (WorldBattleSync): the sim opens them in
        // afterTick on every client; every button's close + decision call is
        // a command addressed to the current prompt.
        String wbp = "view/world/ui/battle/UIWBattlePrompt";
        for (String m : new String[] {"battle", "assist", "lastStand", "battleSally"})
            t.hooks.add(new EntryHook(wbp, m, "(Lworld/battle/spec/WBattleSpec;)V", HOOK_WBATTLE_PROMPT));
        t.hooks.add(new EntryHook(wbp, "siege", "(Lworld/battle/spec/WBattleSiege;)V", HOOK_WBATTLE_PROMPT));
        t.hooks.add(new EntryHook(wbp, "result", "(Lworld/battle/spec/WBattleSiege$Result;)V", HOOK_WBATTLE_PROMPT));
        t.hooks.add(new EntryHook(wbp, "result", "(Lworld/battle/spec/WBattleResult;Z)V", HOOK_WBATTLE_PROMPT));
        String wbUi = "view/world/ui/battle/*", wbs = "syxmp/sync/WorldBattleSync";
        for (String owner : new String[] {"world/battle/spec/WBattleSpec", "world/battle/spec/WBattleSiege"})
            for (String m : new String[] {"engage", "auto", "retreat"})
                t.redirects.add(new CallRedirect(wbUi, null, owner, m, "()V", wbs, m));
        t.redirects.add(new CallRedirect(wbUi, null, "world/battle/spec/WBattleResult", "accept", "([I[I)V", wbs, "accept"));
        for (String m : new String[] {"occupy", "abandon", "puppet"})
            t.redirects.add(new CallRedirect(wbUi, null, "world/battle/spec/WBattleSiege$Result", m, "(DD[I[I)V", wbs, m));
        t.redirects.add(new CallRedirect(wbUi, null, "util/data/DOUBLE_O$DOUBLE_OE", "incD",
                "(Ljava/lang/Object;D)Lutil/data/DOUBLE_O$DOUBLE_OE;", wbs, "mercyInc"));
        t.redirects.add(new CallRedirect(wbUi, null, "snake2d/util/misc/ACTION", "exe", "()V", wbs, "close"));

        // World army / region handlers with UI-local editors (ArmySync).
        t.hooks.add(new EntryHook("view/world/ui/army/ArmyRecruit$3", "clickA", "()V", HOOK_RECRUIT));
        t.hooks.add(new EntryHook("view/world/ui/army/UIMerenaries$4", "clickA", "()V", HOOK_MERC_HIRE));
        t.hooks.add(new EntryHook("view/world/ui/region/PlayInfo$9$1", "exe", "()V", HOOK_AUTONOMY));
        t.redirects.add(new CallRedirect("view/world/ui/army/List*", null,
                "world/entity/army/WArmyConstructor", "create", "(IILgame/faction/Faction;)Lworld/entity/army/WArmy;",
                "syxmp/sync/ArmySync", "armyCreate"));
        t.redirects.add(new CallRedirect("view/world/ui/army/List*", null,
                "view/world/ui/army/UIArmies", "openList", "(Lworld/entity/army/WArmy;)V",
                "syxmp/sync/ArmySync", "openList"));

        // Names typed into sim-owned Str objects (NameSync): the exact input
        // widget classes — Army$2 army name, PlayInfo$11 region name,
        // UIProfile$3 capital name, UIRoom$Detail$1 room name,
        // UIMiniHotSpots$Panel$1 map mark name.
        String strOwner = "snake2d/util/sprite/text/Str";
        for (String ui : new String[] {"view/world/ui/army/Army$2", "view/world/ui/region/PlayInfo$11",
                "view/ui/profile/UIProfile$3", "view/sett/ui/room/UIRoom$Detail$1", "view/sett/ui/right/UIMiniHotSpots$Panel$1"}) {
            t.redirects.add(new CallRedirect(ui, null, strOwner, "clear", "()L" + strOwner + ";", "syxmp/sync/NameSync", "strClear"));
            t.redirects.add(new CallRedirect(ui, null, strOwner, "add", "(Ljava/lang/CharSequence;)L" + strOwner + ";",
                    "syxmp/sync/NameSync", "strAdd"));
        }
        // Colour pickers editing sim colours in place (marks, faction banner, division banners).
        for (String m : new String[] {"setRed", "setGreen", "setBlue"})
            t.redirects.add(new CallRedirect("util/gui/misc/GColorPicker*", null,
                    "snake2d/util/color/ColorImp", m, "(I)Lsnake2d/util/color/ColorImp;", "syxmp/sync/NameSync", m));
        // Map marks: placement (random colour) and removal.
        t.hooks.add(new EntryHook("view/sett/ui/right/UIMiniHotSpots$1", "placeFirst", "(II)V", HOOK_MARK_ADD));
        t.redirects.add(new CallRedirect("view/sett/ui/right/UIMiniHotSpots$Panel*", null,
                "settlement/tilemap/SettMarks$SettMark", "remove", "()V", "syxmp/sync/NameSync", "markRemove"));

        // Settlement tools with UI-local state (RoomToolSync); ToolSync.HOOKED_TOOLS keeps them off the TOOL path.
        String multiPlace = "(IILsnake2d/util/datatypes/AREA;Lview/tool/PLACER_TYPE;)V";
        t.hooks.add(new EntryHook("view/sett/ui/home/UIHomeAssign", "place", multiPlace, HOOK_HOME_ASSIGN));
        t.hooks.add(new EntryHook("view/sett/ui/room/priority/UIRoomRaceAssign", "place", multiPlace, HOOK_RACE_ASSIGN));
        t.hooks.add(new EntryHook("view/sett/ui/home/UIHomeOdd", "place", multiPlace, HOOK_HOME_ODD));
        t.hooks.add(new EntryHook("settlement/room/health/hospital/Gui$1", "clickA", "()V", HOOK_HOSPITAL_FETCH));
        String mop = "settlement/room/infra/logistics/MoveOrderPullUI";
        t.hooks.add(new EntryHook(mop + "$1", "clickA", "()V", HOOK_MOVE_AUTO));
        t.hooks.add(new EntryHook(mop + "$Placer", "placeFirst", "(II)V", HOOK_MOVE_SET));
        t.hooks.add(new EntryHook(mop + "$Detail$8", "clickA", "()V", HOOK_MOVE_REMOVE));
        t.hooks.add(new EntryHook(mop + "$Paster", "place", multiPlace, HOOK_MOVE_PASTE));
        t.hooks.add(new EntryHook("view/sett/ui/room/construction/UIRoomPlacer", "init",
                "(Lsettlement/room/main/Room$RoomInstanceImp;)V", HOOK_REFURNISH));
        t.redirects.add(new CallRedirect(mop + "*", null, "init/resources/RBIT$RBITImp", "or",
                "(Linit/resources/RBIT;)Linit/resources/RBIT$RBITImp;", "syxmp/sync/UiRedirects", "rbitOr"));

        // Generated tactical battles (GenBattleSync).
        t.hooks.add(new EntryHook("game/save/GameLoader", "set", "()V", HOOK_GAME_LOADER_SET));
        t.hooks.add(new EntryHook("view/battle/IDeploy", "update", "(F)Z", HOOK_IDEPLOY_UPDATE));
        t.hooks.add(new EntryHook("game/battle/state/BattleState$ILiveConclude", "update", "(F)Z", HOOK_CONCLUDE_UPDATE));
        t.hooks.add(new EntryHook("game/battle/state/BattleState$ILiveConclude$1", "close", "()V", HOOK_CONCLUDE_CLOSE));
        String bs = "game/battle/state/BattleState", gbs = "syxmp/sync/GenBattleSync";
        t.redirects.add(new CallRedirect("view/battle/BattleView", "update", bs, "update", "(D)V", gbs, "viewUpdate"));
        t.redirects.add(new CallRedirect("view/battle/IDeploy*", null, bs, "deploy", "()V", gbs, "deploy"));
        t.redirects.add(new CallRedirect("view/battle/*", null, bs, "liveRetreat", "()V", gbs, "liveRetreat"));
        t.redirects.add(new CallRedirect("view/battle/*", null, bs, "reloadBattle", "()V", gbs, "reloadBattle"));

        // The faction name is edited inside the input sprite itself (UIProfile.java:84 text() returns the
        // sim Str), and the city division editor copies its name into DivInfo (UIDivEditor.java:150).
        String sis = "snake2d/util/sprite/text/StringInputSprite";
        t.redirects.add(new CallRedirect(sis, null, strOwner, "add", "(C)L" + strOwner + ";", "syxmp/sync/NameSync", "inputAddChar"));
        t.redirects.add(new CallRedirect(sis, null, strOwner, "add", "(Ljava/lang/CharSequence;)L" + strOwner + ";",
                "syxmp/sync/NameSync", "inputAdd"));
        t.redirects.add(new CallRedirect(sis, null, strOwner, "clear", "()L" + strOwner + ";", "syxmp/sync/NameSync", "inputClear"));
        t.redirects.add(new CallRedirect("view/ui/div/UIDivEditor$3", null, strOwner, "clear", "()L" + strOwner + ";",
                "syxmp/sync/NameSync", "strClear"));
        t.redirects.add(new CallRedirect("view/ui/div/UIDivEditor$3", null, strOwner, "add", "(Ljava/lang/CharSequence;)L" + strOwner + ";",
                "syxmp/sync/NameSync", "strAdd"));

        // Direct field assignments from UI code (AutoSyncTable.FIELDS).
        for (String[] f : AutoSyncTable.FIELDS)
            t.fieldRedirects.add(new FieldRedirect(f[0], f[1], f[2], f[3]));

        // Synced player verbs (see SyxSync.registerAll for the wire handlers).
        t.hooks.add(new EntryHook("settlement/entry/Immigration", "admit",
                "(Linit/race/Race;I)V", HOOK_IMMIGRATION_ADMIT));

        return t;
    }

    /** UI classes (prefix wildcards cover their anonymous inner classes) using generic setters on sim data. */
    private static final String[] UI_GENERIC_SETTER_SITES = {
        "view/sett/ui/standing/CatPopulationGrowth*",   // birth limit (RMapInt), forced breeding (ArrayInt)
        "view/sett/ui/subject/UISubjectType*",          // favourite toggle (DataAbs)
        "view/world/ui/region/PlayPop*",                // race edicts (DataBit via INT_OE)
        "view/world/ui/region/Other*",                  // emissary assimilation (EmiType via INT_OE)
        "view/world/ui/faction/Court*",                 // court flatter/sabotage/assassinate targets
        "view/ui/UIEmissaries*",
        "view/world/ui/army/Army*",                     // artillery supply target
    };

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
        // NOT gore: ThingsGore places blood/flesh at RND tile offsets
        // (ThingsGore.java:84,152) and SFINDERS.isGoodTileToStandOn reads
        // things per tile — gore on a per-client stream desynced random
        // destinations (session 3 finding). Its construction-time tables draw
        // under the deterministic load seeds, which is fine.
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
        for (FieldRedirect r : fieldRedirects)
            if (classMatches(r.inClass, internalClassName))
                return true;
        for (EntryHook h : hooks)
            if (h.owner.equals(internalClassName))
                return true;
        return false;
    }
}
