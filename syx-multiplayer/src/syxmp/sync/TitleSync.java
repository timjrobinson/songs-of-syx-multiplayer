package syxmp.sync;

import java.lang.reflect.Field;

import game.faction.FACTIONS;
import game.faction.player.PTitles;
import snake2d.util.sets.LIST;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;

/**
 * Player titles are meta-progression read from the local profile (PTitles.java:100-130:
 * saves/profile/Titles2.txt, the races each title was unlocked with) and their boost potency
 * (0.5 + 0.5 * raceValue / races, PTitles.java:140) multiplies production and consumption for
 * every worker (IndustryUtil.calcProductionRate via the "Titles" booster). Two players have
 * different profiles, so the same worker produced at different rates on the two PCs: the
 * workshop's input pile diverged by one unit a few seconds after unpausing, then the AI.
 * The host's title data travels with the world data and is applied after every load in the
 * session, so all clients simulate with the host's titles.
 */
public final class TitleSync {
    private TitleSync() {
    }

    private static Field f(String name) {
        return ReflectUtil.field(PTitles.PTitle.class, name);
    }

    @SuppressWarnings("unchecked")
    private static LIST<PTitles.PTitle> titles(PTitles t) {
        return (LIST<PTitles.PTitle>) ReflectUtil.get(ReflectUtil.field(PTitles.class, "titles"), t);
    }

    /** "KEY:raceValue:races;..." for the local (host) game, or "" without a game. */
    public static String encode() {
        try {
            StringBuilder sb = new StringBuilder(256);
            Field fKey = f("key"), fVal = f("raceValue"), fRaces = f("races"), fUnl = f("unlocked");
            for (PTitles.PTitle t : titles(FACTIONS.player().titles)) {
                boolean[] races = (boolean[]) fRaces.get(t);
                StringBuilder r = new StringBuilder(races.length);
                for (boolean b : races)
                    r.append(b ? '1' : '0');
                sb.append(fKey.get(t)).append(':').append(fVal.getDouble(t)).append(':').append(r)
                  .append(':').append(fUnl.getBoolean(t)).append(';');
            }
            return sb.toString();
        } catch (RuntimeException | ReflectiveOperationException e) {
            MpLog.err("cannot encode titles", e);
            return "";
        }
    }

    /** Overwrite the local game's title unlock data with the host's. */
    public static void apply(String data) {
        if (data == null || data.isEmpty())
            return;
        try {
            Field fKey = f("key"), fVal = f("raceValue"), fRaces = f("races"), fUnl = f("unlocked");
            java.util.Map<String, String[]> byKey = new java.util.HashMap<>();
            for (String e : data.split(";")) {
                String[] p = e.split(":");
                if (p.length == 4)
                    byKey.put(p[0], p);
            }
            int applied = 0;
            for (PTitles.PTitle t : titles(FACTIONS.player().titles)) {
                String[] p = byKey.get((String) fKey.get(t));
                boolean[] races = (boolean[]) fRaces.get(t);
                if (p == null) {
                    fVal.setDouble(t, 0);
                    java.util.Arrays.fill(races, false);
                    fUnl.setBoolean(t, false);
                    continue;
                }
                fVal.setDouble(t, Double.parseDouble(p[1]));
                for (int i = 0; i < races.length; i++)
                    races[i] = i < p[2].length() && p[2].charAt(i) == '1';
                fUnl.setBoolean(t, Boolean.parseBoolean(p[3]));
                applied++;
            }
            MpLog.info("applied the host's titles (" + applied + " unlocked)");
        } catch (RuntimeException | ReflectiveOperationException e) {
            MpLog.err("cannot apply titles", e);
        }
    }
}
