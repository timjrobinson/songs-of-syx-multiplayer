package syxmp.common;

/**
 * Command kinds, mirroring Multiplayer.Common.CommandType. Commands are the
 * only inputs to the deterministic simulation; everything else is derived.
 */
public enum CommandType {
    /** byte speed — set global game speed (Songs of Syx has one settlement clock). */
    GLOBAL_TIME_SPEED,
    /** empty — pause for everyone. */
    PAUSE_ALL,
    /** empty — every client saves+reloads at this tick, host uploads the result. */
    CREATE_JOIN_POINT,
    /** int playerId, ... — announce per-player data (permissions etc.) to the sim. */
    INIT_PLAYER_DATA,
    /** int syncId + serialized args — the workhorse: a synced player action. */
    SYNC,
    /** dev-mode tool invocation. */
    DEBUG_TOOLS,
    /** string methodKey + string targetPath + args — generic setter sync (AutoSync). */
    SYNC_AUTO,
    /** byte kind + placable path + tiles/area — map tool placement (ToolSync). */
    TOOL,
    /** byte kind + payload — composite UI actions: event choice, deal, tribute, region buildings (SyncActions). */
    ACTION;

    private static final CommandType[] VALUES = values();

    public static CommandType of(int id) {
        return VALUES[id];
    }
}
