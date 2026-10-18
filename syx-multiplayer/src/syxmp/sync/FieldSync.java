package syxmp.sync;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import syxmp.client.Multiplayer;
import syxmp.util.MpLog;

/**
 * Target of the agent's PUTFIELD redirects (PatchTable.FieldRedirect): UI
 * code assigning a simulation field directly. In interface context the write
 * is sent as a SYNC_AUTO "field" command and skipped locally; otherwise (and
 * on every client at the scheduled tick) the field is written reflectively.
 * Key format: "owner/Class fieldName desc".
 */
public final class FieldSync {

    private static final Map<String, Field> fields = new HashMap<>();

    private FieldSync() {
    }

    public static void putI(Object target, int v, String key) {
        char t = key.charAt(key.length() - 1);
        Object boxed = switch (t) {
            case 'Z' -> v != 0;
            case 'B' -> (byte) v;
            case 'C' -> (char) v;
            case 'S' -> (short) v;
            default -> v;
        };
        put(target, boxed, key);
    }

    public static void putJ(Object target, long v, String key) {
        put(target, v, key);
    }

    public static void putF(Object target, float v, String key) {
        put(target, v, key);
    }

    public static void putD(Object target, double v, String key) {
        put(target, v, key);
    }

    public static void putA(Object target, Object v, String key) {
        put(target, v, key);
    }

    private static void put(Object target, Object value, String key) {
        if (Multiplayer.shouldSync() && AutoSync.sendField(key, target, value))
            return;
        write(target, value, key);
    }

    /** Reflective assignment (local fallback and command execution). */
    public static void write(Object target, Object value, String key) {
        try {
            field(key).set(target, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("field write failed for " + key, e);
        }
    }

    public static Field field(String key) throws ReflectiveOperationException {
        Field f = fields.get(key);
        if (f != null)
            return f;
        String[] parts = key.split(" ");
        Class<?> c = Class.forName(parts[0].replace('/', '.'));
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                f = k.getDeclaredField(parts[1]);
                break;
            } catch (NoSuchFieldException e) {
                // walk up
            }
        }
        if (f == null)
            throw new NoSuchFieldException(key);
        f.setAccessible(true);
        fields.put(key, f);
        return f;
    }
}
