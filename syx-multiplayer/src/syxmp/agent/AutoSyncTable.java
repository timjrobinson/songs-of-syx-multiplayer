package syxmp.agent;

/**
 * Simulation setters whose UI-context calls become synced commands
 * generically (see syxmp.sync.AutoSync): the target instance is addressed
 * by its path in the object graph from the game's static roots, so no
 * per-method resolver code is needed. Grows as the verb tracer
 * (-Dsyxmp.trace=1) finds more UI->sim mutation points.
 *
 * Format: "owner/internal/Name methodName (desc)V". Append only; the wire
 * carries the string key, not the index, so order is not protocol.
 * Premain-safe (no game classes).
 */
public final class AutoSyncTable {

    public static final int FIRST_HOOK_ID = 1000;

    public static final String[] METHODS = {
        // Generic value holders bound to sliders/checkboxes all over the UI
        "util/data/INT$IntImp set (I)V",
        "util/data/BOOLEAN$BOOLEANImp set (Z)Lutil/data/BOOLEAN$BOOLEAN_MUTABLE;",
        // Room employment (per room type): priority, group caps
        "settlement/room/main/employment/RoomEmployment$Priority set (I)V",
        "settlement/room/main/employment/RoomEmployment$GRoupInt set (Linit/type/WGROUP;I)V",
        "settlement/room/main/employment/RoomEmployment setPrioOnSkill ()V",
        "settlement/room/main/employment/RoomEmployment setPrioOnFullfillment ()V",
        "settlement/room/main/employment/RoomEmployment setPrioOnSkill (Linit/type/WGROUP;)V",
        "settlement/room/main/employment/RoomEmployment setPrioOnFullfillment (Linit/type/WGROUP;)V",
        // Immigration
        "settlement/entry/Immigration setWanted (Linit/race/Race;I)V",
        "settlement/entry/Immigration setHigher (Linit/race/Race;I)V",
    };

    private AutoSyncTable() {
    }

    public static String owner(int i) {
        return METHODS[i].split(" ")[0];
    }

    public static String name(int i) {
        return METHODS[i].split(" ")[1];
    }

    public static String desc(int i) {
        return METHODS[i].split(" ")[2];
    }
}
