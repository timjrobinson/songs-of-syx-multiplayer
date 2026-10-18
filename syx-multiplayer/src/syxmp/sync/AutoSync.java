package syxmp.sync;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.Type;

import syxmp.agent.AutoSyncTable;
import syxmp.agent.SyncTemplates;
import syxmp.client.Multiplayer;
import syxmp.common.CommandType;
import syxmp.common.Packets;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;

/**
 * Generic sync for the setters in AutoSyncTable: a UI-context call is
 * cancelled locally and sent as a SYNC_AUTO command carrying the method key,
 * the target's object path and the arguments; every client resolves the
 * path and invokes the method at the scheduled tick.
 *
 * Arguments: primitives/strings/enums via SyncSerialization, registered game
 * types (entities, rooms, races...) via their workers, anything else by
 * object path. A call whose target or argument has no path runs vanilla
 * (local only) and is logged — those are the gaps to close next.
 */
public final class AutoSync {

    private static final Map<String, Method> methods = new HashMap<>();
    private static final Map<String, Long> unsyncableLogged = new HashMap<>();

    private static final byte ARG_NULL = 0, ARG_PRIMITIVE = 1, ARG_WORKER = 2, ARG_PATH = 3;

    private AutoSync() {
    }

    public static void install() {
        for (int i = 0; i < AutoSyncTable.METHODS.length; i++) {
            final int index = i;
            SyncTemplates.registerAt(AutoSyncTable.FIRST_HOOK_ID + i,
                    (self, args) -> onCall(index, self, args));
        }
        MpLog.info("auto-sync hooks: " + AutoSyncTable.METHODS.length);
    }

    private static boolean onCall(int index, Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        String key = AutoSyncTable.METHODS[index];
        String target = ObjectPaths.pathOf(self);
        if (target == null) {
            unsyncable(key, "target " + self.getClass().getName());
            return false;
        }
        ByteWriter w = new ByteWriter(128);
        w.writeString(key);
        w.writeString(target);
        Class<?>[] types = paramTypes(index);
        for (int i = 0; i < args.length; i++) {
            if (!writeArg(w, types[i], args[i])) {
                unsyncable(key, "arg " + i + " " + (args[i] == null ? "null" : args[i].getClass().getName()));
                return false;
            }
        }
        ByteWriter packet = new ByteWriter(w.size() + 4);
        packet.writeByte(CommandType.SYNC_AUTO.ordinal());
        packet.writeBytes(w.toArray());
        Multiplayer.session.connection.send(Packets.CLIENT_COMMAND, packet);
        MpLog.debug("auto-synced " + key + " on " + target);
        return true;
    }

    private static void unsyncable(String key, String why) {
        long now = System.currentTimeMillis();
        Long last = unsyncableLogged.get(key);
        if (last != null && now - last < 10_000)
            return;
        unsyncableLogged.put(key, now);
        MpLog.err("UNSYNCED (local only): " + key + " — no address for " + why);
    }

    private static boolean writeArg(ByteWriter w, Class<?> type, Object value) {
        if (value == null) {
            w.writeByte(ARG_NULL);
            return true;
        }
        if (type.isPrimitive() || SyncSerialization.canHandle(type)) {
            w.writeByte(ARG_PRIMITIVE);
            SyncSerialization.write(w, type, value);
            return true;
        }
        Class<?> worker = SyncSerialization.workerFor(value.getClass());
        if (worker != null) {
            w.writeByte(ARG_WORKER);
            w.writeString(worker.getName());
            SyncSerialization.write(w, worker, value);
            return true;
        }
        String path = ObjectPaths.pathOf(value);
        if (path == null)
            return false;
        w.writeByte(ARG_PATH);
        w.writeString(path);
        return true;
    }

    private static Object readArg(ByteReader r, Class<?> type) throws ClassNotFoundException {
        int kind = r.readByte();
        switch (kind) {
            case ARG_NULL:
                return null;
            case ARG_PRIMITIVE:
                return SyncSerialization.read(r, type);
            case ARG_WORKER:
                return SyncSerialization.read(r, Class.forName(r.readString(256)));
            case ARG_PATH:
                return ObjectPaths.resolve(r.readString(4096));
            default:
                throw new IllegalStateException("bad arg kind " + kind);
        }
    }

    /** Execute a received SYNC_AUTO command. */
    public static void execute(ByteReader data) {
        String key = data.readString(512);
        String target = data.readString(4096);
        try {
            Method m = method(key);
            Class<?>[] types = m.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++)
                args[i] = readArg(data, types[i]);
            Object self = ObjectPaths.resolve(target);
            if (self == null) {
                MpLog.err("auto-sync target gone: " + target + " for " + key);
                return;
            }
            m.setAccessible(true);
            m.invoke(self, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("auto-sync execution failed for " + key + " on " + target, e);
        }
    }

    private static Method method(String key) throws ReflectiveOperationException {
        Method m = methods.get(key);
        if (m != null)
            return m;
        String[] parts = key.split(" ");
        Class<?> owner = Class.forName(parts[0].replace('/', '.'));
        Type[] argTypes = Type.getArgumentTypes(parts[2]);
        Class<?>[] params = new Class<?>[argTypes.length];
        for (int i = 0; i < argTypes.length; i++)
            params[i] = classOf(argTypes[i]);
        m = owner.getDeclaredMethod(parts[1], params);
        methods.put(key, m);
        return m;
    }

    private static Class<?>[] paramTypes(int index) {
        try {
            return method(AutoSyncTable.METHODS[index]).getParameterTypes();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> classOf(Type t) throws ClassNotFoundException {
        switch (t.getSort()) {
            case Type.BOOLEAN: return boolean.class;
            case Type.BYTE: return byte.class;
            case Type.CHAR: return char.class;
            case Type.SHORT: return short.class;
            case Type.INT: return int.class;
            case Type.LONG: return long.class;
            case Type.FLOAT: return float.class;
            case Type.DOUBLE: return double.class;
            case Type.ARRAY: return Class.forName(t.getDescriptor().replace('/', '.'));
            default: return Class.forName(t.getClassName());
        }
    }
}
