package syxmp.server;

import syxmp.net.ByteWriter;
import syxmp.net.ConnectionBase;

/** Server-side view of one connected player. Port of Multiplayer.Common.ServerPlayer. */
public final class ServerPlayer {

    public enum Status {
        JOINING, PLAYING, DESYNCED
    }

    public enum Type {
        NORMAL, HOST
    }

    public final int id;
    public final ConnectionBase conn;
    public Type type = Type.NORMAL;
    public Status status = Status.JOINING;

    /** Client-reported average frame time in ms (CLIENT_FRAME_TIME). */
    public volatile float frameTimeMs = 1000f / 60f;
    /** Timer value the client last reported being at. */
    public volatile int lastReportedTimer;
    /** Server gameTimer when the client last reported. */
    public volatile int lastReportAtTimer;

    public long lastKeepAliveMs;
    public int keepAliveId;

    public ServerPlayer(int id, ConnectionBase conn) {
        this.id = id;
        this.conn = conn;
    }

    public String username() {
        return conn.username;
    }

    public boolean isHost() {
        return type == Type.HOST;
    }

    /**
     * How far behind the server clock this client is, extrapolated from its
     * last report (port of ServerPlayer.ExtrapolatedTicksBehind).
     */
    public int extrapolatedTicksBehind(int gameTimer) {
        // Clients report every frame (~16-33ms); assume consumption keeps pace
        // with production since the last report, so behind-ness is unchanged.
        return Math.max(0, lastReportAtTimer - lastReportedTimer);
    }

    public void writeListEntry(ByteWriter w) {
        w.writeInt(id);
        w.writeString(username());
        w.writeByte(type.ordinal());
        w.writeByte(status.ordinal());
        w.writeInt(conn.latencyMs);
    }
}
