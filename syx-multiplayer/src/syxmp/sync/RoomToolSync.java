package syxmp.sync;

import java.lang.reflect.Field;

import init.type.HGROUP.HTypeBits;
import init.type.HGROUP.HTypeBitsImp;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.room.home.house.HomeInstance;
import settlement.room.infra.logistics.MoveJob.ROOM_MOVE_SOURCE;
import settlement.room.infra.logistics.MoveOrderPull;
import settlement.room.infra.logistics.MoveOrderPull.MoveOrderPullInstance;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomInstance;
import settlement.room.main.util.RoomAreaWrapper;
import settlement.room.main.util.RoomState;
import settlement.stats.STATS;
import settlement.tilemap.terrain.TBuilding;
import snake2d.PathTile;
import snake2d.util.datatypes.DIR;
import syxmp.client.ClientCommands;
import syxmp.client.CommandExecutor;
import syxmp.client.Multiplayer;
import syxmp.common.CommandType;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;
import util.GUTIL;
import util.data.GETTER;
import view.main.VIEW;
import view.tool.PlacableFixed;

/**
 * Settlement tools whose placement reads state that lives only in the local
 * UI object (sync-coverage.md items 6/7). These tools are not addressable by
 * ObjectPaths (view.* / GUI-owned), or are addressable but carry local
 * state, so instead of shipping the tool, the per-tile sim effect is shipped
 * with the state it needs:
 *
 * - UIHomeAssign / UIRoomRaceAssign: a UI-local HTypeBitsImp applied to the
 *   house / workplace under the tile (UIHomeAssign.java:136/176,
 *   UIRoomRaceAssign.java:141).
 * - UIHomeOdd: moves oddjobbers into a house with a UI-local entity cursor
 *   (UIHomeOdd.java:116).
 * - Hospital Gui$1: {@code fetch[k] = !fetch[k]} (array store).
 * - MoveOrderPullUI: slot writes of moveOrdersPull() (auto-assign $1, Placer
 *   .placeFirst, Detail$8 remove, Paster.place).
 * - Copier$7 / SavedPrintsPlacer$5 (PlacableFixed, addressable): the TOOL
 *   command carries the copier's source room / print, structure, walls flag,
 *   blueprint swap and rotation, applied before place() (captureState/
 *   applyState from ToolSync).
 */
public final class RoomToolSync {

    private RoomToolSync() {
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        return ReflectUtil.field(o.getClass(), name).get(o);
    }

    private static ByteWriter begin(byte kind) {
        ByteWriter w = new ByteWriter(128);
        w.writeByte(kind);
        return w;
    }

    private static void send(ByteWriter w, String what) {
        ClientCommands.send(CommandType.ACTION, w);
        MpLog.debug("synced " + what);
    }

    // --- HTypeBits codec ---------------------------------------------------------

    /** HGROUP.HTypeBitsImp (homes) and WGROUP.HTypeBitsImp (workplaces) share the layout: private int[] data. */
    static void writeBits(ByteWriter w, Object bits) throws ReflectiveOperationException {
        int[] data = bits == null ? null : (int[]) ReflectUtil.field(bits.getClass(), "data").get(bits);
        if (data == null) {
            w.writeInt(0);
            return;
        }
        w.writeInt(data.length);
        for (int v : data)
            w.writeInt(v);
    }

    static <T> T readBits(ByteReader r, T into) throws ReflectiveOperationException {
        int n = r.readInt();
        if (n > 0) {
            int[] data = new int[n];
            for (int i = 0; i < n; i++)
                data[i] = r.readInt();
            ReflectUtil.field(into.getClass(), "data").set(into, data);
        }
        return into;
    }

    // --- home / workplace assignment --------------------------------------------

    private static boolean bitsTool(Object self, Object[] args, byte kind, String what) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            Object bits = field(self, "data");
            ByteWriter w = begin(kind);
            w.writeInt((Integer) args[0]);
            w.writeInt((Integer) args[1]);
            writeBits(w, bits);
            send(w, what);
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): " + what, e);
            return false;
        }
    }

    public static boolean homeAssignPlace(Object self, Object[] args) {
        return bitsTool(self, args, SyncActions.HOME_ASSIGN, "home assignment");
    }

    public static boolean raceAssignPlace(Object self, Object[] args) {
        return bitsTool(self, args, SyncActions.RACE_ASSIGN, "workplace group assignment");
    }

    /** UIHomeAssign.settingSet(bits, tx, ty) (UIHomeAssign.java:176). */
    static void executeHomeAssign(ByteReader r) throws ReflectiveOperationException {
        int tx = r.readInt(), ty = r.readInt();
        HTypeBitsImp bits = readBits(r, new HTypeBitsImp(false));
        HomeInstance h = SETT.ROOMS().HOME.getter.get(tx, ty);
        if (h != null) {
            h.settingSet(bits);
            return;
        }
        RoomState state = SETT.ROOMS().construction.state(tx, ty);
        if (state instanceof HomeInstance.State s)
            s.egroup.copy(bits);
    }

    /** UIRoomRaceAssign.place (UIRoomRaceAssign.java:141). */
    static void executeRaceAssign(ByteReader r) throws ReflectiveOperationException {
        int tx = r.readInt(), ty = r.readInt();
        init.type.WGROUP.HTypeBitsImp bits = readBits(r, new init.type.WGROUP.HTypeBitsImp(false));
        RoomInstance ins = SETT.ROOMS().map.instance.get(tx, ty);
        if (ins != null && ins.blueprintI().employment() != null)
            ins.employees().prefferedSet(bits);
    }

    // --- oddjobber mover -----------------------------------------------------------

    private static Object oddTool;

    public static boolean homeOddPlace(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            int ie = ReflectUtil.field(self.getClass(), "ie").getInt(self);
            oddTool = self;
            ByteWriter w = begin(SyncActions.HOME_ODD);
            w.writeInt((Integer) args[0]);
            w.writeInt((Integer) args[1]);
            w.writeInt(ie);
            send(w, "oddjobber move");
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): oddjobber move", e);
            return false;
        }
    }

    /** Mirror of UIHomeOdd.place (UIHomeOdd.java:116-143) with the issuer's entity cursor. */
    static void executeHomeOdd(ByteReader r) throws ReflectiveOperationException {
        int tx = r.readInt(), ty = r.readInt(), ie = r.readInt();
        HomeInstance h = SETT.ROOMS().HOME.getter.get(tx, ty);
        if (h == null)
            return;
        if (tx != h.serviceX() || ty != h.serviceY())
            return;
        HTypeBits t = h.availability();
        if (t == null || h.occupants() >= h.occupantsMax())
            return;
        ENTITY[] ee = SETT.ENTITIES().getAllEnts();
        for (int i = 0; i < ee.length; i++) {
            if (ie >= ee.length || ie < 0)
                ie = 0;
            ENTITY e = ee[ie];
            if (e instanceof Humanoid a) {
                if (STATS.WORK().EMPLOYED.get(a) == null && t.is(a)) {
                    STATS.HOME().GETTER.set(a, h);
                    t = h.availability();
                    if (t == null || h.occupants() >= h.occupantsMax())
                        break;
                }
            }
            ie++;
        }
        if (CommandExecutor.currentCmdIssuedBySelf && oddTool != null)
            ReflectUtil.field(oddTool.getClass(), "ie").setInt(oddTool, ie);
    }

    // --- hospital fetch[] ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static boolean hospitalFetchClick(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            int k = ReflectUtil.field(self.getClass(), "val$k").getInt(self);
            GETTER<RoomInstance> getter = (GETTER<RoomInstance>) field(self, "val$getter");
            RoomInstance ins = getter.get();
            ByteWriter w = begin(SyncActions.HOSPITAL_FETCH);
            SyncSerialization.write(w, Room.class, ins);
            w.writeInt(k);
            send(w, "hospital fetch toggle");
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): hospital fetch", e);
            return false;
        }
    }

    static void executeHospitalFetch(ByteReader r) throws ReflectiveOperationException {
        Object ins = SyncSerialization.read(r, Room.class);
        int k = r.readInt();
        if (ins == null)
            return;
        boolean[] fetch = (boolean[]) ReflectUtil.field(ins.getClass(), "fetch").get(ins);
        if (k >= 0 && k < fetch.length)
            fetch[k] = !fetch[k];
    }

    // --- move orders ---------------------------------------------------------------

    private static MoveOrderPullInstance sourceOf(Object ui) throws ReflectiveOperationException {
        GETTER<?> g = (GETTER<?>) field(ui, "source");
        return (MoveOrderPullInstance) g.get();
    }

    private static RoomInstance roomOf(Object ui) throws ReflectiveOperationException {
        GETTER<?> g = (GETTER<?>) field(ui, "room");
        return (RoomInstance) g.get();
    }

    /** MoveOrderPullUI$1.clickA: auto-assign the next slot to the closest source (flood fill). */
    public static boolean moveAutoClick(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            Object ui = field(self, "this$0");
            ByteWriter w = begin(SyncActions.MOVE_AUTO);
            SyncSerialization.write(w, Room.class, (Room) sourceOf(ui));
            SyncSerialization.write(w, Room.class, roomOf(ui));
            send(w, "move order auto-assign");
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): move order auto-assign", e);
            return false;
        }
    }

    /** Mirror of MoveOrderPullUI.java:84-113. */
    static void executeMoveAuto(ByteReader r) {
        Object s = SyncSerialization.read(r, Room.class);
        RoomInstance room = (RoomInstance) SyncSerialization.read(r, Room.class);
        if (!(s instanceof MoveOrderPullInstance source) || room == null)
            return;
        GUTIL.flooder().init(room);
        GUTIL.flooder().pushSloppy(room.mX(), room.mY(), 0);
        while (GUTIL.flooder().hasMore()) {
            PathTile t = GUTIL.flooder().pollSmallest();
            Room rr = SETT.ROOMS().map.get(t);
            if (rr != null && rr != source && rr.mX(t.x(), t.y()) == t.x() && rr.mY(t.x(), t.y()) == t.y()) {
                if (rr instanceof ROOM_MOVE_SOURCE ss) {
                    if (ss.moveCapacity().has(source.moveOrderPullAccepted())) {
                        for (int ii = 0; ii < source.moveOrdersPull().length; ii++) {
                            MoveOrderPull o = source.moveOrdersPull()[ii];
                            if (o == null || o.source() == null || o.source() == ss) {
                                source.moveOrdersPull()[ii] = new MoveOrderPull((RoomInstance) rr, source.moveOrderPullAccepted());
                                source.moveOrdersPull()[ii].pullLimit = 0;
                                break;
                            }
                        }
                        GUTIL.flooder().done();
                    }
                }
            }
            for (DIR d : DIR.ALL) {
                if (SETT.IN_BOUNDS(t, d) && SETT.PATH().coster.player.getCost(t.x(), t.y(), t.x() + d.x(), t.y() + d.y()) > 0)
                    GUTIL.flooder().pushSmaller(t.x() + d.x(), t.y() + d.y(), t.getValue() + d.tileDistance());
            }
        }
        GUTIL.flooder().done();
    }

    /** MoveOrderPullUI$Placer.placeFirst(tx, ty): set slot placerII to the clicked room. */
    public static boolean moveSetPlace(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            Object ui = field(self, "this$0");
            int slot = ReflectUtil.field(ui.getClass(), "placerII").getInt(ui);
            ByteWriter w = begin(SyncActions.MOVE_SET);
            SyncSerialization.write(w, Room.class, (Room) sourceOf(ui));
            w.writeInt(slot);
            w.writeInt((Integer) args[0]);
            w.writeInt((Integer) args[1]);
            send(w, "move order source");
            VIEW.s().tools.place(null);
            VIEW.s().ui.rooms.open(roomOf(ui));
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): move order source", e);
            return false;
        }
    }

    static void executeMoveSet(ByteReader r) {
        Object s = SyncSerialization.read(r, Room.class);
        int slot = r.readInt(), tx = r.readInt(), ty = r.readInt();
        if (!(s instanceof MoveOrderPullInstance source))
            return;
        Room rr = SETT.ROOMS().map.get(tx, ty);
        if (!(rr instanceof RoomInstance dest) || slot < 0 || slot >= source.moveOrdersPull().length)
            return;
        if (source.moveOrdersPull()[slot] == null)
            source.moveOrdersPull()[slot] = new MoveOrderPull(dest, source.moveOrderPullAccepted());
        else
            source.moveOrdersPull()[slot].destSet(dest);
    }

    /** MoveOrderPullUI$Detail$8.clickA: remove slot oi. */
    public static boolean moveRemoveClick(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            Object detail = field(self, "this$1");
            Object ui = field(detail, "this$0");
            int slot = ReflectUtil.field(self.getClass(), "val$oi").getInt(self);
            ByteWriter w = begin(SyncActions.MOVE_REMOVE);
            SyncSerialization.write(w, Room.class, (Room) sourceOf(ui));
            w.writeInt(slot);
            send(w, "move order remove");
            VIEW.inters().popup.close();
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): move order remove", e);
            return false;
        }
    }

    static void executeMoveRemove(ByteReader r) {
        Object s = SyncSerialization.read(r, Room.class);
        int slot = r.readInt();
        if (s instanceof MoveOrderPullInstance source && slot >= 0 && slot < source.moveOrdersPull().length)
            source.moveOrdersPull()[slot] = null;
    }

    /** MoveOrderPullUI$Paster.place(tx, ty, area, type): copy orders from the UI-local 'current' room. */
    public static boolean movePastePlace(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            Object current = field(self, "current");
            if (current == null)
                return false;
            ByteWriter w = begin(SyncActions.MOVE_PASTE);
            SyncSerialization.write(w, Room.class, (Room) current);
            w.writeInt((Integer) args[0]);
            w.writeInt((Integer) args[1]);
            send(w, "move order paste");
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): move order paste", e);
            return false;
        }
    }

    /** Mirror of MoveOrderPullUI.java:686-706. */
    static void executeMovePaste(ByteReader r) {
        Object c = SyncSerialization.read(r, Room.class);
        int tx = r.readInt(), ty = r.readInt();
        if (!(c instanceof MoveOrderPullInstance current))
            return;
        Room rr = SETT.ROOMS().map.get(tx, ty);
        if (!(rr instanceof MoveOrderPullInstance i) || rr == c || rr.blueprint() != ((Room) c).blueprint())
            return;
        i.copyFrom(current);
        if (rr.mX(tx, ty) != tx && rr.mY(tx, ty) != ty)
            return;
        for (int oi = 0; oi < current.moveOrdersPull().length && oi < i.moveOrdersPull().length; oi++) {
            i.moveOrdersPull()[oi] = null;
            MoveOrderPull o = current.moveOrdersPull()[oi];
            if (o != null && o.source() != null) {
                i.moveOrdersPull()[oi] = new MoveOrderPull(o.sourceI(), i.moveOrderPullAccepted());
                i.moveOrdersPull()[oi].pullLimit = o.pullLimit;
            }
        }
    }

    // --- refurnish (UIRoomPlacer.init(RoomInstanceImp), UIRoomPlacer.java:93) ----------

    private static Object uiPlacer;

    /** Entry hook: the room is removed and the shared placer re-staged on every client; the tool opens on the issuer. */
    public static boolean refurnishInit(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        if (!(args[0] instanceof Room ins))
            return false;
        uiPlacer = self;
        ByteWriter w = begin(SyncActions.REFURNISH);
        SyncSerialization.write(w, Room.class, ins);
        // The issuer's wall material: each client's placer starts with its own profile default
        // (PLACEMENT.java:62 reads ROOM_CONSTRUCTION.STRUCTURE); reconstruct overrides it with the
        // room's actual building when there is one.
        w.writeInt(buildingIndex(SETT.ROOMS().placement.placer.structure.get()));
        send(w, "refurnish of room #" + ins.index());
        return true;
    }

    static void executeRefurnish(ByteReader r) throws ReflectiveOperationException {
        Object o = SyncSerialization.read(r, Room.class);
        TBuilding structure = building(r.readInt());
        if (!(o instanceof settlement.room.main.Room.RoomInstanceImp ins))
            return;
        RoomBlueprintImp b = ins.constructor().blue();
        RoomState stat = ins.makeState(ins.mX(), ins.mY(), false);
        int up = ins.upgrade();
        int deg = ins.degrader(ins.mX(), ins.mY()) == null ? 0 : ins.degrader(ins.mX(), ins.mY()).getData();
        Object user = VIEW.s().ui.placer;
        settlement.room.main.TmpArea a = ins.remove(ins.mX(), ins.mY(), false, user, false);
        if (a == null)
            return;
        if (a.area() <= 0) {
            a.clear();
            return;
        }
        if (structure != null)
            SETT.ROOMS().placement.placer.structure.set(structure);
        SETT.ROOMS().placement.placer.reconstruct(a, up, deg, stat, b);
        refreshPlacer(SETT.ROOMS().placement.placer);
        if (CommandExecutor.currentCmdIssuedBySelf && uiPlacer != null) {
            Object state = field(uiPlacer, "state");
            ReflectUtil.method(state.getClass(), "init", RoomBlueprintImp.class, boolean.class).invoke(state, b, true);
            view.tool.ToolConfig config = (view.tool.ToolConfig) field(state, "config");
            VIEW.s().tools.place(SETT.ROOMS().placement.placer.area(), config);
        }
    }

    /**
     * The placer's item/resource statistics are a cache keyed on updateI that only the accessors
     * refresh (UtilStats.update, UtilStats.java:81) — on the issuer the open construction panel
     * refreshes them every frame, on the other clients nobody does, yet the synced verbs read them
     * (removeAllItems reads the raw count, RoomPlacer.java:448; createProblem reads the groups).
     * Recomputing from the map right before every placer verb makes all clients read the same values.
     */
    public static void refreshPlacer(settlement.room.main.placement.RoomPlacer placer) {
        try {
            Object stats = ReflectUtil.field(placer.getClass(), "resources").get(placer);
            ReflectUtil.method(stats.getClass(), "updatee").invoke(stats);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("cannot refresh placer stats", e);
        }
    }

    // --- copier / saved print state (carried by the TOOL command) ---------------------

    private static final String COPIER = "settlement.room.main.copy.Copier$7";
    private static final String PRINT = "settlement.room.main.copy.SavedPrintsPlacer$5";

    private static int buildingIndex(TBuilding b) {
        if (b == null)
            return -1;
        for (int i = 0; i < SETT.TERRAIN().BUILDINGS.all().size(); i++)
            if (SETT.TERRAIN().BUILDINGS.all().get(i) == b)
                return i;
        return -1;
    }

    private static TBuilding building(int i) {
        return i < 0 || i >= SETT.TERRAIN().BUILDINGS.all().size() ? null : SETT.TERRAIN().BUILDINGS.all().get(i);
    }

    private static RoomBlueprintImp swapCurrent(Object swap) throws ReflectiveOperationException {
        return (RoomBlueprintImp) ReflectUtil.method(swap.getClass(), "current").invoke(swap);
    }

    private static void swapInit(Object swap, RoomBlueprintImp b) throws ReflectiveOperationException {
        ReflectUtil.method(swap.getClass(), "init", RoomBlueprintImp.class).invoke(swap, b);
    }

    private static final String ITEM_SINGLE = "settlement.room.main.placement.PlacerItemSingle";
    private static final String ITEM_AREA = "settlement.room.main.placement.PlacerItemArea";
    private static final byte ST_NONE = 0, ST_COPIER = 1, ST_PRINT = 2, ST_ITEM = 3;

    private static Object fieldUp(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * The state bytes a FIXED tool command carries: every PlacableFixed's rotation and size (the tool's
     * rotate/size buttons set them on the issuer only, PlacableFixedTool.java:36-84), then a kind byte
     * and the placer-specific UI state — the room copier's source/structure/swap, the saved print, or
     * for the shared room placer's item placers the item group RoomPlacer.item(int) selected on the
     * issuer (SItems.java:193, UIRoomPlacer.java:62-90; PlacerItemSingle.place reads group/size/rot).
     */
    public static byte[] captureState(Object placable) {
        String cls = placable.getClass().getName();
        ByteWriter w = new ByteWriter(64);
        PlacableFixed fixed = (PlacableFixed) placable;
        w.writeInt(fixed.rot());
        w.writeInt(fixed.size());
        try {
            if (cls.equals(COPIER)) {
                Object copier = field(placable, "this$0");
                settlement.room.main.ROOMA room = (settlement.room.main.ROOMA) field(copier, "room");
                if (room == null) {
                    w.writeByte(ST_NONE);
                    return w.toArray();
                }
                w.writeByte(ST_COPIER);
                w.writeInt(room.mX());
                w.writeInt(room.mY());
                w.writeInt(buildingIndex((TBuilding) field(copier, "structure")));
                w.writeBool(ReflectUtil.field(copier.getClass(), "w").getBoolean(copier));
                SyncSerialization.write(w, RoomBlueprintImp.class, swapCurrent(field(copier, "swap")));
                return w.toArray();
            }
            if (cls.equals(PRINT)) {
                Object placer = field(placable, "this$0");
                Object print = field(placer, "print");
                if (print == null) {
                    w.writeByte(ST_NONE);
                    return w.toArray();
                }
                w.writeByte(ST_PRINT);
                w.writeString(String.valueOf(field(print, "name")));
                SyncSerialization.write(w, RoomBlueprintImp.class, (RoomBlueprintImp) field(print, "blue"));
                w.writeString(String.valueOf(field(print, "check")));
                w.writeInt(ReflectUtil.field(print.getClass(), "width").getInt(print));
                w.writeInt(ReflectUtil.field(print.getClass(), "height").getInt(print));
                int[] data = (int[]) field(print, "data");
                w.writeInt(data.length);
                for (int v : data)
                    w.writeInt(v);
                w.writeBool(ReflectUtil.field(placer.getClass(), "w").getBoolean(placer));
                SyncSerialization.write(w, RoomBlueprintImp.class, swapCurrent(field(placer, "swap")));
                w.writeInt(buildingIndex(SETT.ROOMS().placement.placer.structure.get()));
                return w.toArray();
            }
            if (cls.equals(ITEM_SINGLE) || cls.equals(ITEM_AREA)) {
                settlement.room.main.furnisher.FurnisherItemGroup group =
                        (settlement.room.main.furnisher.FurnisherItemGroup) fieldUp(placable, "group");
                w.writeByte(ST_ITEM);
                w.writeInt(group == null ? -1 : group.index());
                return w.toArray();
            }
        } catch (Exception e) {
            MpLog.err("cannot capture tool state of " + cls, e);
        }
        w.writeByte(ST_NONE);
        return w.toArray();
    }

    public static void applyState(Object placable, byte[] state) {
        if (state.length == 0)
            return;
        String cls = placable.getClass().getName();
        ByteReader r = new ByteReader(state);
        try {
            PlacableFixed fixed = (PlacableFixed) placable;
            int rot = r.readInt(), size = r.readInt();
            byte kind = (byte) r.readByte();
            if (kind == ST_COPIER) {
                Object copier = field(placable, "this$0");
                int mx = r.readInt(), my = r.readInt();
                Room src = SETT.ROOMS().map.get(mx, my);
                if (src == null)
                    throw new IllegalStateException("copy source room gone at " + mx + "," + my);
                RoomAreaWrapper wrap = (RoomAreaWrapper) ReflectUtil.field(copier.getClass(), "wrap").get(null);
                ReflectUtil.field(copier.getClass(), "room").set(copier, wrap.init(src, mx, my));
                ReflectUtil.field(copier.getClass(), "structure").set(copier, building(r.readInt()));
                ReflectUtil.field(copier.getClass(), "w").setBoolean(copier, r.readBool());
                swapInit(field(copier, "swap"), (RoomBlueprintImp) SyncSerialization.read(r, RoomBlueprintImp.class));
            } else if (kind == ST_PRINT) {
                Object placer = field(placable, "this$0");
                Class<?> printC = Class.forName("settlement.room.main.copy.SavedPrints$SavedPrint");
                Object print = allocate(printC);
                ReflectUtil.field(printC, "name").set(print, r.readString(256));
                ReflectUtil.field(printC, "blue").set(print, SyncSerialization.read(r, RoomBlueprintImp.class));
                ReflectUtil.field(printC, "check").set(print, r.readString(4096));
                ReflectUtil.field(printC, "width").setInt(print, r.readInt());
                ReflectUtil.field(printC, "height").setInt(print, r.readInt());
                int n = r.readInt();
                int[] data = new int[n];
                for (int i = 0; i < n; i++)
                    data[i] = r.readInt();
                ReflectUtil.field(printC, "data").set(print, data);
                ReflectUtil.field(placer.getClass(), "print").set(placer, print);
                ReflectUtil.field(placer.getClass(), "w").setBoolean(placer, r.readBool());
                swapInit(field(placer, "swap"), (RoomBlueprintImp) SyncSerialization.read(r, RoomBlueprintImp.class));
                TBuilding b = building(r.readInt());
                if (b != null)
                    SETT.ROOMS().placement.placer.structure.set(b);
            } else if (kind == ST_ITEM) {
                // RoomPlacer.item(group) stages the group (and blueprint/upgrade) on the shared item placer
                // exactly as the issuer's UI did; the placer's blueprint itself arrived with the synced init.
                int g = r.readInt();
                settlement.room.main.placement.RoomPlacer placer = SETT.ROOMS().placement.placer;
                if (g < 0 || placer.blueprint() == null)
                    throw new IllegalStateException("item placement without a staged blueprint/group (" + g + ")");
                Object staged = placer.item(g);
                if (staged != placable)
                    throw new IllegalStateException("item placer mismatch: " + staged.getClass().getName()
                            + " staged for " + cls);
            }
            // Last: PlacerItemSingle.set() restores a remembered size per blueprint (PlacerItemSingle.java:97).
            fixed.rotSet(rot);
            fixed.sizeSet(size);
        } catch (Exception e) {
            throw new IllegalStateException("cannot apply tool state of " + cls, e);
        }
    }

    /** Instance without running a constructor (SavedPrint only has file/room constructors). */
    private static Object allocate(Class<?> c) throws ReflectiveOperationException {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        return ReflectUtil.method(unsafe.getClass(), "allocateInstance", Class.class).invoke(unsafe, c);
    }

    // --- dispatch -------------------------------------------------------------------

    static void execute(int kind, ByteReader r) throws Exception {
        switch (kind) {
            case SyncActions.HOME_ASSIGN -> executeHomeAssign(r);
            case SyncActions.RACE_ASSIGN -> executeRaceAssign(r);
            case SyncActions.HOME_ODD -> executeHomeOdd(r);
            case SyncActions.HOSPITAL_FETCH -> executeHospitalFetch(r);
            case SyncActions.MOVE_AUTO -> executeMoveAuto(r);
            case SyncActions.MOVE_SET -> executeMoveSet(r);
            case SyncActions.MOVE_REMOVE -> executeMoveRemove(r);
            case SyncActions.MOVE_PASTE -> executeMovePaste(r);
            case SyncActions.REFURNISH -> executeRefurnish(r);
            default -> MpLog.err("unknown room tool action " + kind);
        }
    }
}
