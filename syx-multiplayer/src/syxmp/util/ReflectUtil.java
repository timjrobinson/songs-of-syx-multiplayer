package syxmp.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Helpers for reaching private game internals. The game and mod share one
 * classloader (script jars are appended to the system classpath by the
 * launcher), so plain setAccessible reflection works — this is our stand-in
 * for the places where RimWorld Multiplayer uses Harmony field access.
 */
public final class ReflectUtil {

    private ReflectUtil() {
    }

    public static Field field(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // walk up
            }
        }
        throw new IllegalStateException("no field " + name + " on " + cls.getName()
                + " — game version mismatch?");
    }

    public static Method method(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // walk up
            }
        }
        throw new IllegalStateException("no method " + name + " on " + cls.getName()
                + " — game version mismatch?");
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Field f, Object obj) {
        try {
            return (T) f.get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void set(Field f, Object obj, Object value) {
        try {
            f.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Object call(Method m, Object obj, Object... args) {
        try {
            return m.invoke(obj, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
