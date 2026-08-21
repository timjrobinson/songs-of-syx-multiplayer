package syxmp.sync;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;

/**
 * One synced player verb: a method whose UI-context calls are converted into
 * commands and replayed identically everywhere. Port of RW-MP's SyncMethod.
 *
 * The target instance is resolved either through a registered singleton
 * supplier (most Songs of Syx mutation points hang off static roots like
 * SETT/GAME/FACTIONS) or through a SyncSerialization worker for the class.
 */
public final class SyncMethod {

    /** Assigned by Sync.postInit — the index into the handler list IS the wire id. */
    int syncId = -1;

    public final Class<?> targetClass;
    public final String methodName;
    public final Class<?>[] paramTypes;
    final Method method;
    /** Null for static methods or when a serialization worker handles the target. */
    Supplier<?> instanceResolver;
    boolean hostOnly;
    boolean debugOnly;

    SyncMethod(Class<?> targetClass, String methodName, Class<?>... paramTypes) {
        this.targetClass = targetClass;
        this.methodName = methodName;
        this.paramTypes = paramTypes;
        this.method = ReflectUtil.method(targetClass, methodName, paramTypes);
        for (Class<?> p : paramTypes)
            if (!SyncSerialization.canHandle(p))
                throw new IllegalStateException(
                        "cannot serialize param " + p.getName() + " of " + this);
    }

    public SyncMethod instance(Supplier<?> resolver) {
        this.instanceResolver = resolver;
        return this;
    }

    public SyncMethod hostOnly() {
        this.hostOnly = true;
        return this;
    }

    /** Serialize a UI-context call into a SYNC command payload. */
    void writeCall(ByteWriter w, Object self, Object[] args) {
        w.writeInt(syncId);
        if (instanceResolver == null && self != null)
            SyncSerialization.write(w, targetClass, self);
        for (int i = 0; i < paramTypes.length; i++)
            SyncSerialization.write(w, paramTypes[i], args[i]);
    }

    /** Execute a received SYNC command: resolve target, read args, invoke. */
    void handle(ByteReader data) {
        Object self = null;
        if (instanceResolver != null) {
            self = instanceResolver.get();
        } else if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            self = SyncSerialization.read(data, targetClass);
            if (self == null) {
                MpLog.debug("sync target gone for " + this);
                return;
            }
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            args[i] = SyncSerialization.read(data, paramTypes[i]);
        ReflectUtil.call(method, self, args);
    }

    @Override
    public String toString() {
        return "SyncMethod(" + targetClass.getSimpleName() + "." + methodName + ")";
    }
}
