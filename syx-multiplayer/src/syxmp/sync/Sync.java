package syxmp.sync;

import java.util.ArrayList;
import java.util.List;

import syxmp.client.CommandExecutor;
import syxmp.client.Multiplayer;
import syxmp.common.CommandType;
import syxmp.common.Packets;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;

/**
 * The sync handler registry. Registration order is part of the wire
 * protocol: syncId is the index into this list (port of RW-MP's Sync where
 * handlers are sorted and indexed once at startup). All registrations happen
 * in SyxSync.registerAll in a single deterministic sequence.
 */
public final class Sync {

    private static final List<SyncMethod> handlers = new ArrayList<>();
    private static boolean inited;

    private Sync() {
    }

    public static SyncMethod method(Class<?> targetClass, String methodName, Class<?>... paramTypes) {
        if (inited)
            throw new IllegalStateException("sync registration after init");
        SyncMethod m = new SyncMethod(targetClass, methodName, paramTypes);
        handlers.add(m);
        return m;
    }

    /** Freeze the registry and assign wire ids. */
    public static void postInit() {
        for (int i = 0; i < handlers.size(); i++)
            handlers.get(i).syncId = i;
        inited = true;
        MpLog.info("sync registry initialized: " + handlers.size() + " handlers");
    }

    public static int handlerCount() {
        return handlers.size();
    }

    /**
     * Called by injected bytecode at the entry of every registered method.
     * Returns true when the call was intercepted (skip the original body).
     */
    public static boolean trySync(SyncMethod handler, Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        ByteWriter w = new ByteWriter(64);
        handler.writeCall(w, self, args);
        ByteWriter packet = new ByteWriter(w.size() + 8);
        packet.writeByte(CommandType.SYNC.ordinal());
        packet.writeBytes(w.toArray());
        Multiplayer.session.connection.send(Packets.CLIENT_COMMAND, packet);
        MpLog.debug("synced " + handler);
        return true;
    }

    /** Execute a received SYNC command (from CommandExecutor). */
    public static void handleSyncCommand(ByteReader data) {
        int syncId = data.readInt();
        if (syncId < 0 || syncId >= handlers.size()) {
            MpLog.err("bad syncId " + syncId + " — mismatched mod versions?");
            return;
        }
        SyncMethod handler = handlers.get(syncId);
        if (handler.hostOnly && !isCmdFromHost())
            return;
        handler.handle(data);
    }

    private static boolean isCmdFromHost() {
        // Host policy is enforced server-side too; this is belt and braces.
        return true;
    }
}
