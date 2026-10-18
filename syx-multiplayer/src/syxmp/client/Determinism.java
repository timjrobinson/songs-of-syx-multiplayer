package syxmp.client;

import game.GAME;
import snake2d.util.map.MAP_BOOLEAN;
import settlement.main.SETT;

/**
 * Targets of the agent's determinism patches (the "Determinism catalogue"):
 * places where vanilla simulation depends on frame rate, wall-clock threads
 * or local UI state. Each is a no-op outside multiplayer.
 */
public final class Determinism {

    private Determinism() {
    }

    /**
     * Replaces the per-frame {@code game.afterTick()} call in VIEW.update
     * (VIEW.java:193). Vanilla runs it once per rendered frame, and it does
     * real simulation work (maintenance consumption grid, world battle
     * polling) — frame-rate dependent. In multiplayer it runs once per Timer
     * unit from TickController instead.
     */
    public static void afterTick(GAME game) {
        if (Multiplayer.active() && (Multiplayer.reloading || TickController.inLockstep()))
            return;
        syxmp.dev.VerbTrace.uiContext = false;
        Multiplayer.ticking = true;
        try {
            game.afterTick();
        } finally {
            Multiplayer.ticking = false;
            syxmp.dev.VerbTrace.uiContext = true;
        }
    }

    /**
     * Replaces {@code MAP_BOOLEAN.is(tile)} calls inside sim classes that
     * consult the room-placement staging layer (PLACEMENT.embryo). Staging
     * is local UI state in multiplayer, so the shared simulation must not see
     * it (woodcutter/farm/pasture/fish constructors, snow, overlays).
     */
    public static boolean embryoIs(MAP_BOOLEAN map, int tile) {
        if (Multiplayer.active() && map == SETT.ROOMS().placement.embryo)
            return false;
        return map.is(tile);
    }

    /**
     * Replaces {@code VIEW.b().isActive()} inside simulation classes (see
     * PatchTable). The battle view is local UI; in multiplayer the shared
     * simulation always behaves as if it were closed, so a player opening it
     * does not pause the settlement resources on their machine only.
     */
    public static boolean battleViewActive(view.battle.BattleView b) {
        if (Multiplayer.active())
            return false;
        return b.isActive();
    }

    /** Replaces {@code VIEW.world().UI.battle.isBusty()} in WBattles.canPoll. */
    public static boolean worldBattlePromptBusy(view.world.ui.battle.UIWBattlePrompt p) {
        if (Multiplayer.active())
            return false;
        return p.isBusty();
    }

    public static boolean embryoIs(MAP_BOOLEAN map, int tx, int ty) {
        if (Multiplayer.active() && map == SETT.ROOMS().placement.embryo)
            return false;
        return map.is(tx, ty);
    }
}
