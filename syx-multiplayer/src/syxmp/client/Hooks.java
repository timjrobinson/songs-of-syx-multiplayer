package syxmp.client;

import syxmp.agent.PatchTable;
import syxmp.agent.SyncTemplates;
import syxmp.agent.SyxAgent;
import syxmp.util.MpLog;

/**
 * Attaches runtime behavior to the agent's injected entry hooks. Installed
 * once during game init; before that (or without the agent) every hook
 * falls through to vanilla behavior.
 */
public final class Hooks {

    private static boolean installed;

    private Hooks() {
    }

    public static void installAll() {
        if (installed)
            return;
        installed = true;
        if (!SyxAgent.isActive()) {
            MpLog.err("agent not active — multiplayer disabled. "
                    + "Use 'MP: install agent' in the debug panel, then restart.");
            return;
        }

        // UI speed controls become synced commands; the game's own internal
        // speedSet calls (e.g. GAME.update(double)'s speedSet(1.0)) run
        // vanilla because shouldSync is false during ticking.
        SyncTemplates.registerAt(PatchTable.HOOK_SPEED_SET, (self, args) -> {
            if (!Multiplayer.active())
                return false;
            if (Multiplayer.shouldSync()) {
                ClientCommands.requestSpeed((Double) args[0]);
                return true;
            }
            return false;
        });

        SyncTemplates.registerAt(PatchTable.HOOK_TOGGLE_PAUSE, (self, args) -> {
            if (!Multiplayer.active())
                return false;
            if (Multiplayer.shouldSync())
                ClientCommands.requestPauseToggle();
            return true; // never run vanilla toggle while in MP
        });

        // Local one-frame pauses would silently drop sub-steps on one client
        // (GameSpeed.update checks tmpPaused/updateOnce first) — suppress.
        SyncTemplates.EntryHandler suppressInMp = (self, args) -> Multiplayer.active();
        SyncTemplates.registerAt(PatchTable.HOOK_TMP_PAUSE, suppressInMp);
        SyncTemplates.registerAt(PatchTable.HOOK_UPDATE_ONCE, suppressInMp);

        // Battle workers: never threaded while in multiplayer.
        SyncTemplates.registerAt(PatchTable.HOOK_BATTLE_UNPAUSE, (self, args) -> Multiplayer.active());

        // Autosave is wall-clock driven and would stall one client (and write
        // a host-side save) — never in MP. Players save through the menu.
        SyncTemplates.registerAt(PatchTable.HOOK_AUTOSAVE, (self, args) -> Multiplayer.active());

        // GAME.Warn pauses the game and GAME.Error throws depending on the
        // local DEVELOPER/DEBUG flags (GAME.java:412-435). In MP every client
        // takes the same path: log with origin, continue.
        SyncTemplates.registerAt(PatchTable.HOOK_GAME_WARN, (self, args) -> {
            if (!Multiplayer.active())
                return false;
            MpLog.err("game warning: " + args[0], new Throwable("origin"));
            return true;
        });
        SyncTemplates.registerAt(PatchTable.HOOK_GAME_ERROR, (self, args) -> {
            if (!Multiplayer.active())
                return false;
            MpLog.err("game error (continuing, as in developer mode): " + args[0], new Throwable("origin"));
            return true;
        });

        // Pathfinder: long-range jobs computed synchronously while in multiplayer.
        SyncTemplates.registerAt(PatchTable.HOOK_PATH_PREP, (self, args) -> PathSync.prep(self, args));

        // Leaving to the main menu ends the session (host: stops the server).
        SyncTemplates.registerAt(PatchTable.HOOK_EXIT_TO_MENU, (self, args) -> {
            ClientConnect.disconnect("left to main menu");
            return false;
        });

        syxmp.sync.AutoSync.install();
        // Tactical battle orders (BattleOrderSync).
        SyncTemplates.registerAt(PatchTable.HOOK_BATTLE_ORDER_SET,
                (self, args) -> syxmp.sync.BattleOrderSync.lockedSet(self, args));
        SyncTemplates.registerAt(PatchTable.HOOK_ARMIES_TELEPORT,
                (self, args) -> syxmp.sync.BattleOrderSync.initAndTeleport(self, args));
        // World battle prompts: record + pause when the sim opens one (WorldBattleSync).
        SyncTemplates.registerAt(PatchTable.HOOK_WBATTLE_PROMPT,
                (self, args) -> syxmp.sync.WorldBattleSync.promptOpened(self, args));
        // World army/region handlers (ArmySync).
        SyncTemplates.registerAt(PatchTable.HOOK_RECRUIT, syxmp.sync.ArmySync::recruitClick);
        SyncTemplates.registerAt(PatchTable.HOOK_MERC_HIRE, syxmp.sync.ArmySync::mercHireClick);
        SyncTemplates.registerAt(PatchTable.HOOK_AUTONOMY, syxmp.sync.ArmySync::autonomyExe);
        // Settlement tools with UI-local state (RoomToolSync) and map marks (NameSync).
        SyncTemplates.registerAt(PatchTable.HOOK_HOME_ASSIGN, syxmp.sync.RoomToolSync::homeAssignPlace);
        SyncTemplates.registerAt(PatchTable.HOOK_RACE_ASSIGN, syxmp.sync.RoomToolSync::raceAssignPlace);
        SyncTemplates.registerAt(PatchTable.HOOK_HOME_ODD, syxmp.sync.RoomToolSync::homeOddPlace);
        SyncTemplates.registerAt(PatchTable.HOOK_HOSPITAL_FETCH, syxmp.sync.RoomToolSync::hospitalFetchClick);
        SyncTemplates.registerAt(PatchTable.HOOK_MOVE_AUTO, syxmp.sync.RoomToolSync::moveAutoClick);
        SyncTemplates.registerAt(PatchTable.HOOK_MOVE_SET, syxmp.sync.RoomToolSync::moveSetPlace);
        SyncTemplates.registerAt(PatchTable.HOOK_MOVE_REMOVE, syxmp.sync.RoomToolSync::moveRemoveClick);
        SyncTemplates.registerAt(PatchTable.HOOK_MOVE_PASTE, syxmp.sync.RoomToolSync::movePastePlace);
        SyncTemplates.registerAt(PatchTable.HOOK_MARK_ADD, syxmp.sync.NameSync::markPlace);
        SyncTemplates.registerAt(PatchTable.HOOK_REFURNISH, syxmp.sync.RoomToolSync::refurnishInit);
        // Generated battles (GenBattleSync) and in-session vanilla loads.
        SyncTemplates.registerAt(PatchTable.HOOK_GAME_LOADER_SET, (self, args) -> {
            if (Multiplayer.active() && Multiplayer.executingCmds && TickController.inLockstep()) {
                MpLog.info("game load scheduled by a command at timer " + TickController.timer + ": reloading");
                Multiplayer.reloading = true;
                TickController.yieldForReload = true;
            } else if (Multiplayer.active() && !Multiplayer.reloading) {
                MpLog.err("game load outside a command while in a session (quickload?) — this client will desync");
            }
            return false;
        });
        SyncTemplates.registerAt(PatchTable.HOOK_IDEPLOY_UPDATE, syxmp.sync.GenBattleSync::deployUpdate);
        SyncTemplates.registerAt(PatchTable.HOOK_CONCLUDE_UPDATE, syxmp.sync.GenBattleSync::concludeUpdate);
        SyncTemplates.registerAt(PatchTable.HOOK_CONCLUDE_CLOSE, syxmp.sync.GenBattleSync::concludeClose);
        SyncTemplates.registerAt(PatchTable.HOOK_TOOL_MULTI_PLACE,
                (self, args) -> syxmp.sync.ToolSync.multiPlace(self));

        MpLog.info("entry hooks installed");
    }
}
