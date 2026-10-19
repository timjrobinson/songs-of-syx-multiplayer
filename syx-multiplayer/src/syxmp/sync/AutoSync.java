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
    /** Target string for static methods. */
    private static final String STATIC_TARGET = "";

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

    private static final String PLACER_INIT =
            "settlement/room/main/placement/RoomPlacer init (Lsettlement/room/main/RoomBlueprintImp;I)V";
    private static final String PLACER_CREATE =
            "settlement/room/main/placement/RoomPlacer create ()Lsnake2d/util/datatypes/COORDINATE;";

    private static boolean onCall(int index, Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        String key = AutoSyncTable.METHODS[index];
        if (key.equals(PLACER_INIT) && args[0] != null)
            return stagePlacer(key, self, args, paramTypes(index));
        return send(key, self, args, paramTypes(index));
    }

    /**
     * RoomPlacer.init(blueprint, upgrade): the construction UI reads the staged blueprint in the same
     * click (UIRoomPlacer.java:46 opens the tool whose panel calls placer.blueprint().constructor(),
     * SShape.java:127), and the single-item placable re-stages on every hover (UIRoomPlacer.java:229),
     * so a deferred-only staging crashed the issuer. The staging runs locally at once (nested setter
     * hooks suppressed) and again, idempotently, when the command executes on every client; re-staging
     * the already staged, still empty placer sends nothing.
     */
    private static boolean stagePlacer(String key, Object self, Object[] args, Class<?>[] types) {
        settlement.room.main.placement.RoomPlacer p = (settlement.room.main.placement.RoomPlacer) self;
        boolean unchanged = p.blueprint() == args[0] && p.size() == 0 && !p.hasHistory()
                && args[1] instanceof Integer up && up == 0;
        if (!unchanged && !send(key, self, args, types))
            return false; // unsyncable: vanilla runs locally as before
        boolean prev = Multiplayer.dontSync;
        Multiplayer.dontSync = true;
        try {
            Method m = method(key);
            m.setAccessible(true);
            m.invoke(self, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("local placer staging failed", e);
        } finally {
            Multiplayer.dontSync = prev;
        }
        // Indoor rooms build with the placer's wall material, which starts as each client's own
        // profile default (PLACEMENT.java:62): stage the issuer's on everyone (UtilStructure.set is
        // a synced verb; the hook skips the local call, the value is already ours).
        if (!unchanged && args[0] instanceof settlement.room.main.RoomBlueprintImp b
                && b.constructor() != null && b.constructor().mustBeIndoors())
            p.structure.set(p.structure.get());
        return true;
    }

    /**
     * Redirected generic-setter call (UiRedirects): the key names the
     * receiver's runtime class, which is what the executing side resolves.
     * @return true if sent (caller must skip the local call).
     */
    public static boolean sendRuntime(Object self, String name, String desc, Object... args) {
        if (!Multiplayer.shouldSync() || self == null)
            return false;
        String key = self.getClass().getName().replace('.', '/') + " " + name + " " + desc;
        Class<?>[] types;
        try {
            types = method(key).getParameterTypes();
        } catch (ReflectiveOperationException e) {
            unsyncable(key, "method not found: " + e);
            return false;
        }
        return send(key, self, args, types);
    }

    /** Field assignment from UI code (FieldSync). @return true if sent. */
    public static boolean sendField(String fieldKey, Object self, Object value) {
        Class<?> type;
        try {
            type = FieldSync.field(fieldKey).getType();
        } catch (ReflectiveOperationException e) {
            unsyncable(fieldKey, "field not found: " + e);
            return false;
        }
        return send(FIELD_PREFIX + fieldKey, self, new Object[] {value}, new Class<?>[] {type});
    }

    private static final String FIELD_PREFIX = "field ";

    /**
     * Addressable sim objects that are nevertheless UI scratch: the diplomacy draft deal
     * (DIP.TMP()) is rewritten by the proposal message every frame (UIDipMessDeal.java:202) and
     * edited in the diplomacy screen; the deal commands carry its content as a DealSave instead.
     * Writes under these paths run locally only.
     */
    private static final String[] LOCAL_TARGETS = {"game.faction.diplomacy.DIP#s.dealTmp"};

    private static boolean send(String key, Object self, Object[] args, Class<?>[] types) {
        String target;
        if (self == null) {
            target = STATIC_TARGET; // static method: no receiver to address
        } else {
            target = ObjectPaths.pathOf(self);
            if (target == null) {
                unsyncable(key, "target " + self.getClass().getName());
                return false;
            }
            for (String local : LOCAL_TARGETS)
                if (target.startsWith(local))
                    return false;
        }
        ByteWriter w = new ByteWriter(128);
        w.writeString(key);
        w.writeString(target);
        for (int i = 0; i < args.length; i++) {
            if (!writeArg(w, types[i], args[i])) {
                // A java.lang.Object parameter carrying a UI token (e.g. the
                // button that RoomInstance.remove records as the area user)
                // has no sim meaning; the receiver gets null.
                if (types[i] == Object.class && args[i] != null
                        && args[i].getClass().getName().startsWith("view.")) {
                    w.writeByte(ARG_NULL);
                    continue;
                }
                unsyncable(key, "arg " + i + " " + (args[i] == null ? "null" : args[i].getClass().getName()));
                return false;
            }
        }
        // The issuer's overwrite tool toggle: job placement consults it (Placer.java:56 ->
        // Job.problem(tx, ty, Job.overwrite)); every client executes with the issuer's value.
        w.writeBool(settlement.job.Job.overwrite);
        w.writeBool(settlement.main.SETT.JOBS().planMode.is()); // per-client too (ConstructionInstance.java:145)
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
            if (key.startsWith(FIELD_PREFIX)) {
                String fieldKey = key.substring(FIELD_PREFIX.length());
                Class<?> type = FieldSync.field(fieldKey).getType();
                Object value = readArg(data, type);
                Object self = ObjectPaths.resolve(target);
                if (self == null) {
                    MpLog.err("auto-sync field target gone: " + target + " for " + key);
                    return;
                }
                FieldSync.write(self, value, fieldKey);
                return;
            }
            Method m = method(key);
            Class<?>[] types = m.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++)
                args[i] = readArg(data, types[i]);
            boolean overwrite = data.readBool();
            boolean plan = data.readBool();
            Object self = null;
            if (!target.equals(STATIC_TARGET)) {
                self = ObjectPaths.resolve(target);
                if (self == null) {
                    MpLog.err("auto-sync target gone: " + target + " for " + key);
                    return;
                }
            }
            m.setAccessible(true);
            boolean prevOverwrite = settlement.job.Job.overwrite;
            boolean prevPlan = settlement.main.SETT.JOBS().planMode.is();
            settlement.job.Job.overwrite = overwrite;
            settlement.main.SETT.JOBS().planMode.set(plan);
            try {
                invoke(key, m, self, args);
            } finally {
                settlement.job.Job.overwrite = prevOverwrite;
                settlement.main.SETT.JOBS().planMode.set(prevPlan);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("auto-sync execution failed for " + key + " on " + target, e);
        }
    }

    private static void invoke(String key, Method m, Object self, Object[] args) throws ReflectiveOperationException {
        if (self instanceof settlement.room.main.placement.RoomPlacer placer)
            RoomToolSync.refreshPlacer(placer);
        // The construct button's tool deactivation issues a second create (Config.java:140: in vanilla
        // the first one had already cleared the placer); with nothing staged it is a no-op everywhere.
        if (key.equals(PLACER_CREATE) && ((settlement.room.main.placement.RoomPlacer) self).createProblem() != null) {
            MpLog.debug("skipped create: nothing staged on the placer");
            return;
        }
        Object ret = m.invoke(self, args);
        // RoomInstance.remove hands back the shared TmpArea for the caller
        // to release (UIRoom.java:347); the UI's own call was cancelled.
        if (ret instanceof settlement.room.main.TmpArea area)
            area.clear();
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
        NoSuchMethodException missing = null;
        for (Class<?> c = owner; c != null && m == null; c = c.getSuperclass()) {
            try {
                m = c.getDeclaredMethod(parts[1], params);
            } catch (NoSuchMethodException e) {
                missing = e;
            }
        }
        if (m == null)
            throw missing != null ? missing : new NoSuchMethodException(key);
        m.setAccessible(true);
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
