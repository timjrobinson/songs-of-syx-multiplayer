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

        // Pathfinder: synchronous only while in multiplayer.
        SyncTemplates.registerAt(PatchTable.HOOK_PATH_IS_PROCESSED, (self, args) -> Multiplayer.active());
        SyncTemplates.registerAt(PatchTable.HOOK_PATH_PREP, (self, args) -> Multiplayer.active());

        // Leaving to the main menu ends the session (host: stops the server).
        SyncTemplates.registerAt(PatchTable.HOOK_EXIT_TO_MENU, (self, args) -> {
            ClientConnect.disconnect("left to main menu");
            return false;
        });

        MpLog.info("entry hooks installed");
    }
}
