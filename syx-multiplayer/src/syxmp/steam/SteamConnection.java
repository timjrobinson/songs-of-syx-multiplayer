package syxmp.steam;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNetworking;

import syxmp.common.MpDisconnectReason;
import syxmp.net.ConnectionBase;
import syxmp.net.ConnectionStateEnum;
import syxmp.util.MpLog;

/**
 * Steam P2P transport (ISteamNetworking, reliable channel): Steam relays
 * through its network when NAT blocks a direct route, so friends can join
 * without port forwarding. Frames are delivered by SteamMpImpl.pump() on the
 * game thread and handed to the owner's executor (server queue or client
 * main-thread queue), like TcpConnection.
 */
final class SteamConnection extends ConnectionBase {

    static final int CHANNEL = 0;

    final SteamID remote;
    final long remoteHandle;
    private final SteamNetworking networking;
    private final Executor deliveryExecutor;
    BiConsumer<MpDisconnectReason, String> onClosed;
    private volatile boolean closed;
    private ByteBuffer sendBuf = ByteBuffer.allocateDirect(1 << 16);

    SteamConnection(SteamNetworking networking, SteamID remote, Executor deliveryExecutor) {
        this.networking = networking;
        this.remote = remote;
        this.remoteHandle = SteamID.getNativeHandle(remote);
        this.deliveryExecutor = deliveryExecutor;
    }

    /** Called on the game thread by the pump. */
    void deliver(byte[] frame) {
        if (!closed)
            deliveryExecutor.execute(() -> handleReceiveRaw(frame));
    }

    @Override
    protected void sendRaw(byte[] frame, boolean reliable) {
        if (closed)
            return;
        try {
            if (sendBuf.capacity() < frame.length)
                sendBuf = ByteBuffer.allocateDirect(Integer.highestOneBit(frame.length) << 1);
            sendBuf.clear();
            sendBuf.put(frame);
            sendBuf.flip();
            if (!networking.sendP2PPacket(remote, sendBuf, SteamNetworking.P2PSend.Reliable, CHANNEL))
                closeInternal(MpDisconnectReason.NET_FAILED, "steam send failed");
        } catch (SteamException e) {
            closeInternal(MpDisconnectReason.NET_FAILED, e.getMessage());
        }
    }

    @Override
    public void close(MpDisconnectReason reason, String info) {
        closeInternal(reason, info);
    }

    void closeInternal(MpDisconnectReason reason, String info) {
        if (closed)
            return;
        closed = true;
        MpLog.info("steam connection to " + remote.getAccountID() + " closed (" + reason
                + (info != null ? ": " + info : "") + ")");
        changeState(ConnectionStateEnum.DISCONNECTED, null);
        networking.closeP2PSessionWithUser(remote);
        SteamMpImpl.onConnectionClosed(this);
        if (onClosed != null)
            deliveryExecutor.execute(() -> onClosed.accept(reason, info));
    }

    boolean isClosed() {
        return closed;
    }
}
