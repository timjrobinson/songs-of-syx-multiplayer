package syxmp.common;

import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;

/**
 * A player command stamped by the server with the tick it must execute on.
 * Port of Multiplayer.Common.ScheduledCommand. Songs of Syx has a single
 * settlement simulation, so there is no mapId; global scope is implied.
 */
public final class ScheduledCommand {

    public static final int NO_PLAYER = -1;

    public final CommandType type;
    /** Global tick (GAME.updateI-domain) at which every client executes this. */
    public final int ticks;
    public final int playerId;
    public final byte[] data;

    /** Set client-side when this command was issued by the local player. */
    public transient boolean issuedBySelf;

    public ScheduledCommand(CommandType type, int ticks, int playerId, byte[] data) {
        this.type = type;
        this.ticks = ticks;
        this.playerId = playerId;
        this.data = data;
    }

    public void serialize(ByteWriter w) {
        w.writeByte(type.ordinal());
        w.writeInt(ticks);
        w.writeInt(playerId);
        w.writeBytes(data);
    }

    public byte[] serialize() {
        ByteWriter w = new ByteWriter(data.length + 16);
        serialize(w);
        return w.toArray();
    }

    public static ScheduledCommand deserialize(ByteReader r) {
        CommandType type = CommandType.of(r.readByte());
        int ticks = r.readInt();
        int playerId = r.readInt();
        byte[] data = r.readBytes();
        return new ScheduledCommand(type, ticks, playerId, data);
    }

    @Override
    public String toString() {
        return "Cmd(" + type + " @" + ticks + " by " + playerId + ", " + data.length + "b)";
    }
}
