package syxmp.sync;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;

/**
 * Type-driven argument serialization, the game-glue analogue of RW-MP's
 * SyncSerialization + SyncWorker dictionaries. Primitives are built in;
 * game types are registered by SyxSerialization.
 */
public final class SyncSerialization {

    public interface Writer<T> extends BiConsumer<ByteWriter, T> {
    }

    public interface Reader<T> extends Function<ByteReader, T> {
    }

    private static final class Entry<T> {
        final Writer<T> writer;
        final Reader<T> reader;

        Entry(Writer<T> writer, Reader<T> reader) {
            this.writer = writer;
            this.reader = reader;
        }
    }

    private static final Map<Class<?>, Entry<?>> workers = new HashMap<>();

    private SyncSerialization() {
    }

    public static <T> void register(Class<T> type, Writer<T> writer, Reader<T> reader) {
        workers.put(type, new Entry<>(writer, reader));
    }

    static {
        register(int.class, (w, v) -> w.writeInt(v), ByteReader::readInt);
        register(Integer.class, (w, v) -> w.writeInt(v), ByteReader::readInt);
        register(long.class, (w, v) -> w.writeLong(v), ByteReader::readLong);
        register(Long.class, (w, v) -> w.writeLong(v), ByteReader::readLong);
        register(short.class, (w, v) -> w.writeShort(v), r -> (short) r.readShort());
        register(byte.class, (w, v) -> w.writeByte(v), r -> (byte) r.readByte());
        register(boolean.class, (w, v) -> w.writeBool(v), ByteReader::readBool);
        register(Boolean.class, (w, v) -> w.writeBool(v), ByteReader::readBool);
        register(float.class, (w, v) -> w.writeFloat(v), ByteReader::readFloat);
        register(Float.class, (w, v) -> w.writeFloat(v), ByteReader::readFloat);
        register(double.class, (w, v) -> w.writeDouble(v), ByteReader::readDouble);
        register(Double.class, (w, v) -> w.writeDouble(v), ByteReader::readDouble);
        register(String.class, ByteWriter::writeString, ByteReader::readString);
        register(byte[].class, (w, v) -> w.writeBytes(v), ByteReader::readBytes);
        register(int[].class, (w, v) -> {
            if (v == null) {
                w.writeInt(-1);
                return;
            }
            w.writeInt(v.length);
            for (int i : v)
                w.writeInt(i);
        }, r -> {
            int n = r.readInt();
            if (n == -1)
                return null;
            int[] a = new int[n];
            for (int i = 0; i < n; i++)
                a[i] = r.readInt();
            return a;
        });
    }

    /** The registered worker type for a runtime class (itself or a supertype/interface), or null. */
    public static Class<?> workerFor(Class<?> runtime) {
        for (Class<?> c = runtime; c != null; c = c.getSuperclass()) {
            if (workers.containsKey(c))
                return c;
            for (Class<?> i : c.getInterfaces())
                if (workers.containsKey(i))
                    return i;
        }
        return null;
    }

    public static boolean canHandle(Class<?> type) {
        return workers.containsKey(type) || type.isEnum();
    }

    @SuppressWarnings("unchecked")
    public static void write(ByteWriter w, Class<?> type, Object value) {
        if (type.isEnum()) {
            w.writeShort(((Enum<?>) value).ordinal());
            return;
        }
        Entry<Object> e = (Entry<Object>) workers.get(type);
        if (e == null)
            throw new IllegalStateException("no sync worker for " + type.getName());
        e.writer.accept(w, value);
    }

    @SuppressWarnings("unchecked")
    public static Object read(ByteReader r, Class<?> type) {
        if (type.isEnum()) {
            return type.getEnumConstants()[r.readShort()];
        }
        Entry<Object> e = (Entry<Object>) workers.get(type);
        if (e == null)
            throw new IllegalStateException("no sync worker for " + type.getName());
        return e.reader.apply(r);
    }
}
