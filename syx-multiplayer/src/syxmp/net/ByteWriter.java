package syxmp.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Growable little-endian byte buffer for packet/command serialization.
 * Java port of RimWorld Multiplayer's {@code Multiplayer.Common.ByteWriter}.
 */
public class ByteWriter {

    private byte[] buf;
    private int pos;

    public ByteWriter() {
        this(64);
    }

    public ByteWriter(int capacity) {
        buf = new byte[Math.max(8, capacity)];
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length)
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + extra));
    }

    public ByteWriter writeByte(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
        return this;
    }

    public ByteWriter writeBool(boolean v) {
        return writeByte(v ? 1 : 0);
    }

    public ByteWriter writeShort(int v) {
        ensure(2);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >> 8);
        return this;
    }

    public ByteWriter writeInt(int v) {
        ensure(4);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >> 8);
        buf[pos++] = (byte) (v >> 16);
        buf[pos++] = (byte) (v >> 24);
        return this;
    }

    public ByteWriter writeLong(long v) {
        writeInt((int) v);
        writeInt((int) (v >> 32));
        return this;
    }

    public ByteWriter writeFloat(float v) {
        return writeInt(Float.floatToRawIntBits(v));
    }

    public ByteWriter writeDouble(double v) {
        return writeLong(Double.doubleToRawLongBits(v));
    }

    /** Length-prefixed UTF-8 string. -1 length means null. */
    public ByteWriter writeString(CharSequence s) {
        if (s == null)
            return writeInt(-1);
        byte[] b = s.toString().getBytes(StandardCharsets.UTF_8);
        writeInt(b.length);
        ensure(b.length);
        System.arraycopy(b, 0, buf, pos, b.length);
        pos += b.length;
        return this;
    }

    /** Length-prefixed byte array. -1 length means null. */
    public ByteWriter writeBytes(byte[] b) {
        if (b == null)
            return writeInt(-1);
        return writeBytes(b, 0, b.length);
    }

    public ByteWriter writeBytes(byte[] b, int off, int len) {
        writeInt(len);
        ensure(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
        return this;
    }

    /** Raw bytes, no length prefix. */
    public ByteWriter writeRaw(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, buf, pos, b.length);
        pos += b.length;
        return this;
    }

    public int size() {
        return pos;
    }

    public byte[] toArray() {
        return Arrays.copyOf(buf, pos);
    }
}
