package syxmp.sync;

import java.lang.reflect.Field;

import game.GAME;
import game.battle.div.Div;
import game.battle.formation.DIV_FORMATION;
import game.battle.formation.DivFormationImp;
import game.battle.formation.DivPositionImp;
import game.battle.thread.order.BattleOrder;
import game.battle.thread.order.BattleOrderTask;
import game.battle.thread.order.BattleOrderTask.DIVTASK;
import snake2d.util.datatypes.Coo;
import snake2d.util.datatypes.DIR;
import snake2d.util.datatypes.Rec;
import snake2d.util.sets.ArrayList;
import snake2d.util.sets.LIST;
import syxmp.client.ClientCommands;
import syxmp.client.Multiplayer;
import syxmp.common.CommandType;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;

/**
 * Tactical battle orders (the in-city battle view: raids, sieges, riots).
 *
 * Every order the battle UI gives a division ends in one of two sim calls:
 * {@code div.order().dest.set(DivFormationImp)} / {@code .task.set(
 * BattleOrderTask)} (BattleOrder.java:60 — position, move, stop, charge,
 * attack; from UISelection, BattlePlacer*, DivDeployerUser) and
 * {@code Armies.initAndTeleport(LIST<Div>)} (Armies.java:236 — the
 * deployment phase of generated battles). The payload objects are UI-owned
 * scratch instances that Locked.set copies into the division's order, so
 * the order is shipped by value: the agent hooks both entry points, in
 * interface context the call is cancelled and sent as an ACTION command,
 * and every client replays it into the same division at the same tick (the
 * battle AI workers, which also call Locked.set, run inside the sub-step
 * and pass through). The battle thread's own BattleOrderPath is never set
 * by the UI. Division settings (formation, running, guard, fire-at-will,
 * ammo) and artillery commands are synced by AutoSyncTable.
 *
 * Field layouts are V71.44 (DivPositionImp.java, DivFormationImp.java,
 * BattleOrderTask.java, Rec.java:365, Coo.java:16).
 */
public final class BattleOrderSync {

    static final byte KIND_DEST = 1, KIND_TASK = 2;

    private BattleOrderSync() {
    }

    // --- field handles ------------------------------------------------------

    private static Field fDeployed, fCoos, fHalf;
    private static Field fFaceDir, fBounds, fStart, fDx, fDy, fWidth, fTs, fCentreI, fExtraRoom, fNotCoherent, fDirMasks;
    private static Field fRecX, fRecY, fRecW, fRecH, fCooX, fCooY;
    private static Field fTask, fTarget, fOrderedFighting;
    private static boolean bound;

    private static Field f(Class<?> c, String n) throws NoSuchFieldException {
        Field f = c.getDeclaredField(n);
        f.setAccessible(true);
        return f;
    }

    static synchronized void bind() throws NoSuchFieldException {
        if (bound)
            return;
        fDeployed = f(DivPositionImp.class, "deployed");
        fCoos = f(DivPositionImp.class, "coos");
        fHalf = f(DivPositionImp.class, "half");
        fFaceDir = f(DivFormationImp.class, "faceDir");
        fBounds = f(DivFormationImp.class, "bounds");
        fStart = f(DivFormationImp.class, "start");
        fDx = f(DivFormationImp.class, "dx");
        fDy = f(DivFormationImp.class, "dy");
        fWidth = f(DivFormationImp.class, "width");
        fTs = f(DivFormationImp.class, "ts");
        fCentreI = f(DivFormationImp.class, "centreI");
        fExtraRoom = f(DivFormationImp.class, "hasExtraRoom");
        fNotCoherent = f(DivFormationImp.class, "isNotCoherent");
        fDirMasks = f(DivFormationImp.class, "dirMasks");
        fRecX = f(Rec.class, "x");
        fRecY = f(Rec.class, "y");
        fRecW = f(Rec.class, "width");
        fRecH = f(Rec.class, "height");
        fCooX = f(Coo.class, "X");
        fCooY = f(Coo.class, "Y");
        fTask = f(BattleOrderTask.class, "task");
        fTarget = f(BattleOrderTask.class, "target");
        fOrderedFighting = f(BattleOrderTask.class, "orderedWhenFighting");
        bound = true;
    }

    // --- codecs -------------------------------------------------------------

    /** Mirrors what DivFormationImp.copy (DivFormationImp.java:175) transfers: deployed coordinates + formation fields. */
    static void encodeFormation(ByteWriter w, DivFormationImp o) throws Exception {
        bind();
        int deployed = fDeployed.getInt(o);
        int half = fHalf.getInt(o);
        int[] coos = (int[]) fCoos.get(o);
        Rec bounds = (Rec) fBounds.get(o);
        Coo start = (Coo) fStart.get(o);
        w.writeByte(((DIR) fFaceDir.get(o)).id());
        w.writeDouble(fRecX.getDouble(bounds));
        w.writeDouble(fRecY.getDouble(bounds));
        w.writeDouble(fRecW.getDouble(bounds));
        w.writeDouble(fRecH.getDouble(bounds));
        w.writeDouble(fCooX.getDouble(start));
        w.writeDouble(fCooY.getDouble(start));
        w.writeDouble(fDx.getDouble(o));
        w.writeDouble(fDy.getDouble(o));
        w.writeInt(fWidth.getInt(o));
        w.writeByte(((DIV_FORMATION) fTs.get(o)).ordinal());
        w.writeInt(fCentreI.getInt(o));
        w.writeBool(fExtraRoom.getBoolean(o));
        w.writeBool(fNotCoherent.getBoolean(o));
        w.writeInt(deployed);
        for (int i = 0; i < deployed; i++) {
            w.writeInt(coos[i]);
            w.writeInt(coos[i + half]);
        }
        w.writeBytes((byte[]) fDirMasks.get(o));
    }

    static void decodeFormation(ByteReader r, DivFormationImp into) throws Exception {
        bind();
        int half = fHalf.getInt(into);
        int[] coos = (int[]) fCoos.get(into);
        Rec bounds = (Rec) fBounds.get(into);
        Coo start = (Coo) fStart.get(into);
        fFaceDir.set(into, DIR.ALL.get(r.readByte()));
        fRecX.setDouble(bounds, r.readDouble());
        fRecY.setDouble(bounds, r.readDouble());
        fRecW.setDouble(bounds, r.readDouble());
        fRecH.setDouble(bounds, r.readDouble());
        fCooX.setDouble(start, r.readDouble());
        fCooY.setDouble(start, r.readDouble());
        fDx.setDouble(into, r.readDouble());
        fDy.setDouble(into, r.readDouble());
        fWidth.setInt(into, r.readInt());
        fTs.set(into, DIV_FORMATION.all.get(r.readByte()));
        fCentreI.setInt(into, r.readInt());
        fExtraRoom.setBoolean(into, r.readBool());
        fNotCoherent.setBoolean(into, r.readBool());
        int deployed = r.readInt();
        if (deployed > half)
            throw new IllegalStateException("formation has " + deployed + " men, this client allows " + half);
        fDeployed.setInt(into, deployed);
        for (int i = 0; i < deployed; i++) {
            coos[i] = r.readInt();
            coos[i + half] = r.readInt();
        }
        byte[] masks = r.readBytes();
        byte[] mine = (byte[]) fDirMasks.get(into);
        System.arraycopy(masks, 0, mine, 0, Math.min(masks.length, mine.length));
    }

    static void encodeTask(ByteWriter w, BattleOrderTask t) throws Exception {
        bind();
        w.writeByte(((DIVTASK) fTask.get(t)).ordinal());
        w.writeInt(fTarget.getInt(t));
        w.writeBool(fOrderedFighting.getBoolean(t));
    }

    static void decodeTask(ByteReader r, BattleOrderTask into) throws Exception {
        bind();
        fTask.set(into, DIVTASK.all.get(r.readByte()));
        fTarget.setInt(into, r.readInt());
        fOrderedFighting.setBoolean(into, r.readBool());
    }

    // --- hooks (interface side) ---------------------------------------------

    /** Entry hook for BattleOrder.Locked.set(Copyable): true = cancelled locally, sent as a command. */
    public static boolean lockedSet(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        Object payload = args[0];
        try {
            Div div = null;
            byte kind = 0;
            for (Div d : GAME.ARMIES().divisions()) {
                BattleOrder o = d.order();
                if (o == null)
                    continue;
                if (o.dest == self) {
                    div = d;
                    kind = KIND_DEST;
                    break;
                }
                if (o.task == self) {
                    div = d;
                    kind = KIND_TASK;
                    break;
                }
            }
            if (div == null) {
                MpLog.err("UNSYNCED (local only): battle order of type "
                        + (payload == null ? "null" : payload.getClass().getSimpleName()) + " on an unknown order slot");
                return false;
            }
            ByteWriter w = new ByteWriter(512);
            w.writeByte(SyncActions.BATTLE_ORDER);
            w.writeShort(div.index());
            w.writeByte(kind);
            if (kind == KIND_DEST)
                encodeFormation(w, (DivFormationImp) payload);
            else
                encodeTask(w, (BattleOrderTask) payload);
            ClientCommands.send(CommandType.ACTION, w);
            MpLog.debug("synced battle order " + (kind == KIND_DEST ? "dest" : "task " + ((BattleOrderTask) payload).task())
                    + " for division " + div.index());
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): battle order", e);
            return false;
        }
    }

    /** Entry hook for Armies.initAndTeleport(LIST<Div>). */
    @SuppressWarnings("unchecked")
    public static boolean initAndTeleport(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            LIST<Div> divs = (LIST<Div>) args[0];
            ByteWriter w = new ByteWriter(16 + divs.size() * 2);
            w.writeByte(SyncActions.BATTLE_TELEPORT);
            w.writeShort(divs.size());
            for (Div d : divs)
                w.writeShort(d.index());
            ClientCommands.send(CommandType.ACTION, w);
            MpLog.debug("synced deployment teleport of " + divs.size() + " divisions");
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): deployment teleport", e);
            return false;
        }
    }

    // --- execution (every client, inside the command) -----------------------

    private static DivFormationImp scratchFormation;
    private static BattleOrderTask scratchTask;

    static void executeOrder(ByteReader r) throws Exception {
        int di = r.readShort();
        int kind = r.readByte();
        Div div = GAME.ARMIES().division((short) di);
        if (div == null || div.order() == null) {
            MpLog.err("battle order for unknown division " + di);
            return;
        }
        if (kind == KIND_DEST) {
            if (scratchFormation == null)
                scratchFormation = new DivFormationImp();
            decodeFormation(r, scratchFormation);
            div.order().dest.set(scratchFormation);
        } else if (kind == KIND_TASK) {
            if (scratchTask == null)
                scratchTask = new BattleOrderTask();
            decodeTask(r, scratchTask);
            div.order().task.set(scratchTask);
        } else {
            MpLog.err("unknown battle order kind " + kind);
        }
    }

    static void executeTeleport(ByteReader r) {
        int n = r.readShort();
        ArrayList<Div> divs = new ArrayList<>(Math.max(1, n));
        for (int i = 0; i < n; i++) {
            Div d = GAME.ARMIES().division((short) r.readShort());
            if (d != null)
                divs.add(d);
        }
        GAME.ARMIES().initAndTeleport(divs);
    }

    /** A new game: drop scratch objects sized for the previous one. */
    public static void reset() {
        scratchFormation = null;
        scratchTask = null;
    }
}
