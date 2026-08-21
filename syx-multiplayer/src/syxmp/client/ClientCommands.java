package syxmp.client;

import syxmp.common.CommandType;
import syxmp.common.Packets;
import syxmp.net.ByteWriter;

/** Client-side helpers for sending non-SYNC commands to the server. */
public final class ClientCommands {

    private ClientCommands() {
    }

    public static void send(CommandType type, ByteWriter payload) {
        ByteWriter packet = new ByteWriter(payload.size() + 8);
        packet.writeByte(type.ordinal());
        packet.writeBytes(payload.toArray());
        Multiplayer.session.connection.send(Packets.CLIENT_COMMAND, packet);
    }

    public static void requestSpeed(double speed) {
        ByteWriter w = new ByteWriter(8);
        w.writeDouble(speed);
        send(CommandType.GLOBAL_TIME_SPEED, w);
    }

    public static void requestPauseToggle() {
        requestSpeed(TickController.speed == 0 ? TickController.prevNonZeroSpeed : 0);
    }
}
