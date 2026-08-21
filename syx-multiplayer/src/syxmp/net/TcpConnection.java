package syxmp.net;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import syxmp.common.MpDisconnectReason;
import syxmp.util.MpLog;

/**
 * TCP transport: reliable-ordered like RimWorld Multiplayer's LiteNetLib
 * ReliableOrdered channel. Wire format per frame: int32 LE length + frame.
 * A reader thread parses frames and delivers them on the supplied executor
 * (the server's action queue, or the client's main-thread queue), so all
 * packet handling happens on the owner's thread.
 */
public final class TcpConnection extends ConnectionBase {

    public static final int MAX_FRAME = ConnectionBase.MAX_SINGLE_PACKET_SIZE + 64;

    private final Socket socket;
    private final OutputStream out;
    private final Executor deliveryExecutor;
    /** Invoked (on the delivery executor) once when the connection dies. */
    public BiConsumer<MpDisconnectReason, String> onClosed;

    private volatile boolean closed;

    public TcpConnection(Socket socket, Executor deliveryExecutor) throws IOException {
        this.socket = socket;
        this.deliveryExecutor = deliveryExecutor;
        socket.setTcpNoDelay(true);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
    }

    /** Start the reader thread; call after the initial state is installed. */
    public void startReading() {
        Thread t = new Thread(this::readLoop, "SyxMP-TcpRead-" + socket.getRemoteSocketAddress());
        t.setDaemon(true);
        t.start();
    }

    private void readLoop() {
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
            while (!closed) {
                int len = Integer.reverseBytes(in.readInt());
                if (len <= 0 || len > MAX_FRAME)
                    throw new IOException("bad frame length " + len);
                byte[] frame = new byte[len];
                in.readFully(frame);
                deliveryExecutor.execute(() -> handleReceiveRaw(frame));
            }
        } catch (IOException e) {
            if (!closed)
                closeInternal(MpDisconnectReason.NET_FAILED, e.getMessage());
        }
    }

    @Override
    protected void sendRaw(byte[] frame, boolean reliable) {
        try {
            synchronized (out) {
                int n = frame.length;
                out.write(n & 0xFF);
                out.write((n >>> 8) & 0xFF);
                out.write((n >>> 16) & 0xFF);
                out.write((n >>> 24) & 0xFF);
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            if (!closed)
                closeInternal(MpDisconnectReason.NET_FAILED, e.getMessage());
        }
    }

    @Override
    public void close(MpDisconnectReason reason, String info) {
        closeInternal(reason, info);
    }

    private void closeInternal(MpDisconnectReason reason, String info) {
        if (closed)
            return;
        closed = true;
        try {
            socket.close();
        } catch (IOException e) {
            // best effort
        }
        MpLog.info("connection closed (" + reason + (info != null ? ": " + info : "") + ")");
        changeState(ConnectionStateEnum.DISCONNECTED, null);
        if (onClosed != null)
            deliveryExecutor.execute(() -> onClosed.accept(reason, info));
    }
}
