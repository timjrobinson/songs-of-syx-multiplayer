package syxmp.net;

import syxmp.common.Packets;

/**
 * A per-state packet handler, mirroring Multiplayer.Common.MpConnectionState.
 * The C# original dispatches through a [state, packetId] table of compiled
 * invokers; in the port each state implements a switch — same contract, an
 * unhandled packet is a protocol error and drops the connection.
 */
public abstract class MpConnectionState {

    public final ConnectionBase connection;

    protected MpConnectionState(ConnectionBase connection) {
        this.connection = connection;
    }

    /** Called once when the connection enters this state. */
    public void onEnter() {
    }

    /** Handle one packet; return false if this state does not accept it. */
    public abstract boolean handle(Packets packet, ByteReader data);
}
