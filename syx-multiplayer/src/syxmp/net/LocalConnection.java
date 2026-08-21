package syxmp.net;

import java.util.concurrent.Executor;

import syxmp.common.MpDisconnectReason;
import syxmp.util.MpLog;

/**
 * In-memory connection pair: the host is a normal client talking to the
 * in-process server thread through one of these. Port of RimWorld
 * Multiplayer's LocalConnection/NetworkingInMemory.
 */
public final class LocalConnection extends ConnectionBase {

    private LocalConnection peer;
    private final Executor deliveryExecutor;
    public Runnable onClosed;
    private volatile boolean closed;

    private LocalConnection(Executor deliveryExecutor) {
        this.deliveryExecutor = deliveryExecutor;
    }

    /**
     * Create a paired client/server connection.
     *
     * @param clientExecutor runs packet handling for the client end (game main thread queue)
     * @param serverExecutor runs packet handling for the server end (server action queue)
     */
    public static LocalConnection[] paired(Executor clientExecutor, Executor serverExecutor) {
        LocalConnection clientEnd = new LocalConnection(clientExecutor);
        LocalConnection serverEnd = new LocalConnection(serverExecutor);
        clientEnd.peer = serverEnd;
        serverEnd.peer = clientEnd;
        return new LocalConnection[] { clientEnd, serverEnd };
    }

    @Override
    protected void sendRaw(byte[] frame, boolean reliable) {
        if (closed)
            return;
        LocalConnection p = peer;
        // Delivered on the PEER's owner thread, like a real transport would
        p.deliveryExecutor.execute(() -> p.handleReceiveRaw(frame));
    }

    @Override
    public void close(MpDisconnectReason reason, String info) {
        if (closed)
            return;
        closed = true;
        MpLog.info("local connection closed (" + reason + ")");
        changeState(ConnectionStateEnum.DISCONNECTED, null);
        if (onClosed != null)
            deliveryExecutor.execute(onClosed);
        peer.close(reason, info);
    }
}
