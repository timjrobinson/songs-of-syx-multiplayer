package syxmp.net;

import java.util.function.Consumer;

import syxmp.common.MpDisconnectReason;
import syxmp.common.Packets;
import syxmp.util.MpLog;

/**
 * Transport-agnostic connection with framing and fragmentation, ported from
 * Multiplayer.Common.ConnectionBase.
 *
 * Frame layout (inside a transport frame): 1 header byte — low 6 bits packet
 * id, high 2 bits fragment state — followed by the payload. Large packets are
 * split into fragments; the first fragment carries a sub-header with the part
 * count and total length. Only one fragmented packet may be in flight at a
 * time per connection.
 */
public abstract class ConnectionBase {

    public static final int MAX_SINGLE_PACKET_SIZE = 65_536;
    public static final int FRAGMENT_SIZE = 16_384;
    public static final int MAX_FRAGMENT_TOTAL_SIZE = 32 * 1024 * 1024;

    private static final int FRAG_NONE = 0x00;
    private static final int FRAG_MORE = 0x40;
    private static final int FRAG_END = 0x80;
    private static final int PACKET_ID_MASK = 0x3F;

    public String username;
    public volatile int latencyMs;

    private volatile MpConnectionState state;
    private volatile ConnectionStateEnum stateEnum = ConnectionStateEnum.DISCONNECTED;

    // Reassembly state for the (single) in-flight fragmented packet
    private ByteWriter fragmented;
    private int fragPacketId = -1;
    private int fragExpectedTotal;
    /** Optional progress observer for fragmented downloads (world data UI). */
    public Consumer<Integer> fragmentProgress;

    public ConnectionStateEnum stateEnum() {
        return stateEnum;
    }

    public MpConnectionState state() {
        return state;
    }

    public void changeState(ConnectionStateEnum e, MpConnectionState impl) {
        this.stateEnum = e;
        this.state = impl;
        if (impl != null)
            impl.onEnter();
    }

    public void send(Packets id) {
        send(id, new byte[0]);
    }

    public void send(Packets id, ByteWriter w) {
        send(id, w.toArray());
    }

    public void send(Packets id, byte[] payload) {
        if (stateEnum == ConnectionStateEnum.DISCONNECTED)
            return;
        if (payload.length <= MAX_SINGLE_PACKET_SIZE) {
            byte[] frame = new byte[1 + payload.length];
            frame[0] = (byte) (id.ordinal() | FRAG_NONE);
            System.arraycopy(payload, 0, frame, 1, payload.length);
            sendRaw(frame, true);
        } else {
            sendFragmented(id, payload);
        }
    }

    private void sendFragmented(Packets id, byte[] payload) {
        if (payload.length > MAX_FRAGMENT_TOTAL_SIZE)
            throw new IllegalArgumentException("packet too large: " + payload.length);
        int parts = (payload.length + FRAGMENT_SIZE - 1) / FRAGMENT_SIZE;
        for (int i = 0; i < parts; i++) {
            int off = i * FRAGMENT_SIZE;
            int len = Math.min(FRAGMENT_SIZE, payload.length - off);
            boolean last = i == parts - 1;
            ByteWriter w = new ByteWriter(len + 16);
            w.writeByte(id.ordinal() | (last ? FRAG_END : FRAG_MORE));
            if (i == 0) {
                w.writeShort(parts);
                w.writeInt(payload.length);
            }
            w.writeRaw(java.util.Arrays.copyOfRange(payload, off, off + len));
            sendRaw(w.toArray(), true);
        }
    }

    /** Called by the transport with one complete frame (header byte + payload). */
    public void handleReceiveRaw(byte[] frame) {
        if (stateEnum == ConnectionStateEnum.DISCONNECTED || frame.length == 0)
            return;
        try {
            int header = frame[0] & 0xFF;
            int packetId = header & PACKET_ID_MASK;
            int fragState = header & ~PACKET_ID_MASK;
            Packets packet = Packets.of(packetId);
            if (packet == null)
                throw new ByteReader.ReaderException("bad packet id " + packetId);

            if (fragState == FRAG_NONE) {
                dispatch(packet, new ByteReader(frame, 1, frame.length - 1));
                return;
            }

            ByteReader r = new ByteReader(frame, 1, frame.length - 1);
            if (fragmented == null) {
                fragPacketId = packetId;
                r.readShort(); // part count (informational; END flag terminates)
                fragExpectedTotal = r.readInt();
                if (fragExpectedTotal < 0 || fragExpectedTotal > MAX_FRAGMENT_TOTAL_SIZE)
                    throw new ByteReader.ReaderException("bad fragmented total " + fragExpectedTotal);
                fragmented = new ByteWriter(Math.min(fragExpectedTotal, 1 << 20));
            } else if (packetId != fragPacketId) {
                throw new ByteReader.ReaderException("interleaved fragmented packets");
            }

            fragmented.writeRaw(r.readRemaining());
            if (fragmented.size() > fragExpectedTotal)
                throw new ByteReader.ReaderException("fragmented packet overflow");
            if (fragmentProgress != null && fragExpectedTotal > 0)
                fragmentProgress.accept(100 * fragmented.size() / fragExpectedTotal);

            if (fragState == FRAG_END) {
                byte[] whole = fragmented.toArray();
                fragmented = null;
                fragPacketId = -1;
                if (whole.length != fragExpectedTotal)
                    throw new ByteReader.ReaderException("fragmented packet size mismatch");
                dispatch(packet, new ByteReader(whole));
            }
        } catch (Exception e) {
            MpLog.err("error handling packet on " + this, e);
            close(MpDisconnectReason.NET_FAILED, e.getMessage());
        }
    }

    private void dispatch(Packets packet, ByteReader data) {
        MpConnectionState s = state;
        if (s == null || !s.handle(packet, data))
            throw new ByteReader.ReaderException(
                    "unhandled packet " + packet + " in state " + stateEnum);
    }

    /** Push one frame onto the wire. Transports must preserve frame boundaries and order. */
    protected abstract void sendRaw(byte[] frame, boolean reliable);

    public abstract void close(MpDisconnectReason reason, String info);

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + username + ", " + stateEnum + ")";
    }
}
