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
    private static int uncached;

    /**
     * Replaces GAME.updateI() inside the lazily refreshed class-level boosters (RoomUpgrades,
     * RoomIrrigated, ROOM_WATER: "if (|updateI - ci| >= 120) recompute", ROOM_POLICE per tick,
     * StatsMultipliers.maxAmount). They refresh on whichever access comes first, a UI panel or the
     * simulation, so each client's cached value had a different age and industry job times
     * (IndustryUtil.calcProductionRate: bonus.get(HCLASS_RACE.clP())) differed for up to 120 ticks
     * after any staffing change — the two-PC desync after building a room. In MP the counter
     * returned here moves by 256 on every call, so the staleness test is always true and the value
     * is a pure function of the shared state (the loops are over one blueprint's rooms).
     */
    public static int uncachedUpdateI() {
        if (!Multiplayer.active())
            return GAME.updateI();
        uncached = (uncached + 256) & 0x3FFFFFFF;
        return uncached;
    }

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
        // In MP the local view state must not reach the sim; a GENERATED
        // battle (BattleState set by the synced engage on every client) is
        // the deterministic equivalent of "the battle view is open".
        if (Multiplayer.active())
            return syxmp.sync.GenBattleSync.inGeneratedBattle();
        return b.isActive();
    }

    /** Replaces {@code VIEW.world().UI.battle.isBusty()} in WBattles.canPoll. */
    public static boolean worldBattlePromptBusy(view.world.ui.battle.UIWBattlePrompt p) {
        // In MP the prompt opens and closes by synced commands (WorldBattleSync),
        // so the local flag is deterministic; the issuer, whose decision is in
        // flight, must not poll the next battle before everyone else does.
        if (Multiplayer.active())
            return p.isBusty() || syxmp.sync.WorldBattleSync.decisionPending();
        return p.isBusty();
    }

    /**
     * Replaces {@code RD.BUILDINGS().tmp().level(bu, reg)} inside RDBuilding
     * (the sim's boost/lock code, RDBuilding.java:328-523). The preview
     * buffer is the local building popup's draft; in MP the sim only ever
     * sees the real level (RDLevelsTmp.java:31 returns the draft while the
     * popup is open on this client).
     */
    public static int rdLevel(world.region.building.RDLevelsTmp tmp, world.region.building.RDBuilding bu,
            world.map.regions.Region reg) {
        if (Multiplayer.active())
            return bu.level.get(reg);
        return tmp.level(bu, reg);
    }

    public static boolean embryoIs(MAP_BOOLEAN map, int tx, int ty) {
        if (Multiplayer.active() && map == SETT.ROOMS().placement.embryo)
            return false;
        return map.is(tx, ty);
    }
}
