package syxmp.sync;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import settlement.entity.ENTITY;
import settlement.main.SETT;
import settlement.room.main.Room;
import snake2d.util.sets.LIST;
import syxmp.util.MpLog;

/**
 * Stable, cross-client addresses for simulation objects: the path from a
 * static root (SETT, GAME, FACTIONS, WORLD...) through instance fields,
 * arrays and LISTs to the object. Because the simulation is deterministic,
 * identical paths resolve to the "same" object on every client. Objects with
 * intrinsic ids (entities, rooms) are anchors: paths below them start from
 * the anchor instead of the root, so entity/room sub-objects are addressable
 * without indexing every entity's fields from the root.
 *
 * The index is built lazily by a bounded breadth-first walk and rebuilt on
 * demand (new rooms, new game). This is the Songs of Syx stand-in for
 * RW-MP's SyncWorkers for context objects.
 */
public final class ObjectPaths {

    private static final int MAX_DEPTH = 12;
    private static final int MAX_OBJECTS = 400_000;
    private static final int MAX_ELEMENTS = 512;
    private static final String[] ROOTS = {
        "settlement.main.SETT", "game.GAME", "game.faction.FACTIONS", "world.WORLD",
        "init.race.RACES", "init.resources.RESOURCES", "settlement.stats.STATS",
        // singletons kept in private statics outside the big roots
        "settlement.entity.humanoid.ai.types.prisoner.AIModule_Prisoner",
        "game.faction.diplomacy.DIP", "game.faction.royalty.opinion.ROPINION",
        "world.region.RD", "game.events.EVENTS", "game.nobility.NOBLES",
    };
    private static final String[] SKIP_PACKAGES = {
        "java.", "javax.", "sun.", "jdk.", "org.lwjgl", "snake2d.", "syxmp.", "init.sprite",
        "util.gui", "util.rendering", "view.", "menu.", "util.spritecomposer",
    };
    private static final String[] SKIP_NAME_PARTS = {
        "Sprite", "Render", "Texture", "Sound", "Shader", "Particle",
    };

    private static final IdentityHashMap<Object, String> paths = new IdentityHashMap<>();
    private static final Map<Class<?>, List<Field>> fieldCache = new HashMap<>();
    private static long lastBuildMs;
    private static int builds;

    private ObjectPaths() {
    }

    public static synchronized void invalidate() {
        paths.clear();
    }

    /** @return the path of {@code o}, or null if it is not reachable. */
    public static synchronized String pathOf(Object o) {
        if (o == null)
            return null;
        String anchor = anchorOf(o);
        if (anchor != null)
            return anchor;
        String p = paths.get(o);
        if (p == null && System.currentTimeMillis() - lastBuildMs > 2000) {
            build();
            p = paths.get(o);
        }
        return p;
    }

    private static String anchorOf(Object o) {
        if (o instanceof ENTITY e)
            return "ent:" + e.id();
        if (o instanceof Room r && !(o instanceof settlement.room.main.RoomBlueprintImp))
            return "room:" + r.index();
        return null;
    }

    /** Resolve a path produced by {@link #pathOf} on another client. */
    public static Object resolve(String path) {
        try {
            Object cur;
            int i;
            if (path.startsWith("ent:")) {
                int end = segEnd(path, 4);
                cur = SETT.ENTITIES().getByID(Integer.parseInt(path.substring(4, end)));
                i = end;
            } else if (path.startsWith("room:")) {
                int end = segEnd(path, 5);
                cur = SETT.ROOMS().map.getByIndex(Integer.parseInt(path.substring(5, end)));
                i = end;
            } else {
                int hash = path.indexOf('#');
                Class<?> root = Class.forName(path.substring(0, hash));
                int end = segEnd(path, hash + 1);
                cur = staticField(root, path.substring(hash + 1, end));
                i = end;
            }
            while (cur != null && i < path.length()) {
                char c = path.charAt(i);
                if (c == '.') {
                    int end = segEnd(path, i + 1);
                    cur = fieldValue(cur, path.substring(i + 1, end));
                    i = end;
                } else if (c == '[') {
                    int end = path.indexOf(']', i);
                    int idx = Integer.parseInt(path.substring(i + 1, end));
                    cur = element(cur, idx);
                    i = end + 1;
                } else {
                    throw new IllegalStateException("bad path char at " + i + " in " + path);
                }
            }
            return cur;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("cannot resolve object path " + path + ": " + e);
            return null;
        }
    }

    private static int segEnd(String path, int from) {
        int i = from;
        while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[')
            i++;
        return i;
    }

    // ---- building ---------------------------------------------------------------

    private static void build() {
        long start = System.currentTimeMillis();
        lastBuildMs = start;
        paths.clear();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        for (String rootName : ROOTS) {
            Class<?> root;
            try {
                root = Class.forName(rootName);
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Field f : root.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive())
                    continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null && !paths.containsKey(v))
                        enqueue(queue, v, rootName + "#" + f.getName(), 0);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // inaccessible root field
                }
            }
        }
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_OBJECTS) {
            Object[] item = queue.poll();
            Object o = item[0];
            String path = (String) item[1];
            int depth = (Integer) item[2];
            visited++;
            if (depth >= MAX_DEPTH)
                continue;
            // Children of an anchor are addressed relative to the anchor.
            String anchor = anchorOf(o);
            String base = anchor != null ? anchor : path;
            Class<?> cls = o.getClass();
            if (cls.isArray()) {
                int n = Math.min(Array.getLength(o), MAX_ELEMENTS);
                for (int k = 0; k < n; k++) {
                    Object v = Array.get(o, k);
                    if (v != null && !paths.containsKey(v))
                        enqueue(queue, v, base + "[" + k + "]", depth + 1);
                }
                continue;
            }
            if (o instanceof LIST<?> list) {
                int n = Math.min(list.size(), MAX_ELEMENTS);
                for (int k = 0; k < n; k++) {
                    Object v = list.get(k);
                    if (v != null && !paths.containsKey(v))
                        enqueue(queue, v, base + "[" + k + "]", depth + 1);
                }
            }
            for (Field f : fields(cls)) {
                Object v;
                try {
                    v = f.get(o);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    continue;
                }
                if (v != null && !paths.containsKey(v))
                    enqueue(queue, v, base + "." + f.getName(), depth + 1);
            }
        }
        builds++;
        MpLog.info("object path index: " + paths.size() + " objects in "
                + (System.currentTimeMillis() - start) + "ms (build " + builds + ")"
                + (visited >= MAX_OBJECTS ? " TRUNCATED" : ""));
        if (builds == 1 || visited >= MAX_OBJECTS)
            logPrefixStats();
    }

    /** Which subtrees dominate the index (for tuning the skip lists). */
    private static void logPrefixStats() {
        Map<String, Integer> counts = new HashMap<>();
        for (String p : paths.values()) {
            int cut = p.length();
            int segs = 0;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c == '.' || c == '[') {
                    if (++segs == 3) {
                        cut = i;
                        break;
                    }
                }
            }
            counts.merge(p.substring(0, cut), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder("object path index, largest subtrees:");
        for (int i = 0; i < Math.min(12, top.size()); i++)
            sb.append("\n    ").append(top.get(i).getValue()).append("  ").append(top.get(i).getKey());
        MpLog.info(sb.toString());
    }

    private static void enqueue(ArrayDeque<Object[]> queue, Object v, String path, int depth) {
        Class<?> c = v.getClass();
        if (c.isArray()) {
            if (c.getComponentType().isPrimitive())
                return;
        } else if (!interesting(c)) {
            return;
        }
        paths.put(v, path);
        queue.add(new Object[] {v, path, depth});
    }

    private static boolean interesting(Class<?> c) {
        if (c.isPrimitive() || c.isEnum() || c == String.class)
            return false;
        String n = c.getName();
        for (String p : SKIP_PACKAGES)
            if (n.startsWith(p))
                return n.startsWith("snake2d.util.sets.") || n.startsWith("snake2d.util.map.");
        for (String p : SKIP_NAME_PARTS)
            if (n.contains(p))
                return false;
        return true;
    }

    private static List<Field> fields(Class<?> cls) {
        List<Field> res = fieldCache.get(cls);
        if (res != null)
            return res;
        res = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive())
                    continue;
                if (f.getType() == String.class)
                    continue;
                try {
                    f.setAccessible(true);
                } catch (RuntimeException e) {
                    continue;
                }
                res.add(f);
            }
        }
        fieldCache.put(cls, res);
        return res;
    }

    private static Object staticField(Class<?> cls, String name) throws ReflectiveOperationException {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object fieldValue(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name + " on " + o.getClass().getName());
    }

    private static Object element(Object o, int idx) {
        if (o.getClass().isArray())
            return idx < Array.getLength(o) ? Array.get(o, idx) : null;
        if (o instanceof LIST<?> list)
            return idx < list.size() ? list.get(idx) : null;
        throw new IllegalStateException("not indexable: " + o.getClass().getName());
    }
}
