package syxmp.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import syxmp.util.MpLog;

/**
 * Process-wide caches keyed on GAME.updateI() that any caller fills, UI panels included:
 * RoomEmploymentIns.employees(ins) (employeesI/employeesO, RoomEmploymentIns.java:80, read by
 * IndustryUtil.calcProductionRate and the work AI), EUpdater.aI, and the world-region supply/race
 * caches. A UI read fills them at the render's updateI; a command executed before the next
 * sub-step (same updateI: commands run at the start of the Timer unit, and updateI never moves
 * while paused) leaves a stale entry that only the client whose panel was open then uses in the
 * sim — the two-PC workshop desync. Resetting the keys at every synced moment (before each
 * sub-step, after each command) makes the sim rebuild them from shared state everywhere.
 */
public final class CacheReset {
    private static final String[][] FIELDS = {
            {"settlement.room.main.employment.RoomEmploymentIns", "employeesI"},
            {"settlement.environment.EUpdater", "aI"},
            {"world.region.RDMilitaryGar$WDivsCapitol", "upI"},
            {"world.region.pop.RDRace$RDRacePopulation", "upI"},
    };
    private static List<Field> fields;

    private CacheReset() {
    }

    private static void resolve() {
        fields = new ArrayList<>();
        for (String[] f : FIELDS)
            add(f[0], f[1]);
        MpLog.info("cache reset: " + fields.size() + " tick-keyed static caches");
    }

    private static void add(String cls, String name) {
        try {
            Field f = Class.forName(cls).getDeclaredField(name);
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                f.setAccessible(true);
                fields.add(f);
            }
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            MpLog.err("cache reset: " + cls + "." + name + " not found");
        }
    }

    /** Invalidate every tick-keyed static cache (called at synced moments only). */
    public static void invalidate() {
        if (fields == null)
            resolve();
        try {
            for (Field f : fields)
                f.setInt(null, -1);
        } catch (IllegalAccessException e) {
            MpLog.err("cache reset failed", e);
        }
    }
}
