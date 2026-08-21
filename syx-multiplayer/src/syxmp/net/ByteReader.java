package syxmp.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reader over a received packet's bytes, matching {@link ByteWriter}'s format.
 * Java port of RimWorld Multiplayer's {@code Multiplayer.Common.ByteReader}.
 */
public class ByteReader {

    /** Thrown on malformed/truncated packets; treated as a protocol error, not a crash. */
    public static class ReaderException extends RuntimeException {
        public ReaderException(String msg) {
            super(msg);
        }
    }

    private final byte[] buf;
    private int pos;
    private final int end;

    public ByteReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public ByteReader(byte[] buf, int off, int len) {
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
    }

    private void need(int n) {
        if (pos + n > end)
            throw new ReaderException("packet truncated: need " + n + " bytes at " + pos + "/" + end);
    }

    public int readByte() {
        need(1);
        return buf[pos++] & 0xFF;
    }

    public boolean readBool() {
        return readByte() != 0;
    }

    public int readShort() {
        need(2);
        int v = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8);
        pos += 2;
        return (short) v;
    }

    public int readInt() {
        need(4);
        int v = (buf[pos] & 0xFF)
                | ((buf[pos + 1] & 0xFF) << 8)
                | ((buf[pos + 2] & 0xFF) << 16)
                | ((buf[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    public long readLong() {
        long lo = readInt() & 0xFFFFFFFFL;
        long hi = readInt() & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public String readString() {
        return readString(4 * 1024 * 1024);
    }

    public String readString(int maxLen) {
        int len = readInt();
        if (len == -1)
            return null;
        if (len < 0 || len > maxLen)
            throw new ReaderException("bad string length " + len);
        need(len);
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    public byte[] readBytes() {
        return readBytes(Integer.MAX_VALUE);
    }

    public byte[] readBytes(int maxLen) {
        int len = readInt();
        if (len == -1)
            return null;
        if (len < 0 || len > maxLen)
            throw new ReaderException("bad byte[] length " + len);
        need(len);
        byte[] b = Arrays.copyOfRange(buf, pos, pos + len);
        pos += len;
        return b;
    }

    public int remaining() {
        return end - pos;
    }

    public byte[] readRemaining() {
        byte[] b = Arrays.copyOfRange(buf, pos, end);
        pos = end;
        return b;
    }
}
