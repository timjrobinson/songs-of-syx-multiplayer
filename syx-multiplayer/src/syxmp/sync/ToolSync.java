package syxmp.sync;

import java.lang.reflect.Field;
import java.util.BitSet;

import settlement.job.Job;
import settlement.main.SETT;
import snake2d.util.datatypes.AREA;
import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.datatypes.Rec;
import syxmp.client.Multiplayer;
import syxmp.common.CommandType;
import syxmp.common.Packets;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;
import view.tool.PLACER_TYPE;
import view.tool.PlacableFixed;
import view.tool.PlacableMulti;
import view.tool.PlacableSimple;
import view.tool.PlacableSimpleTile;
import view.tool.PlacableSingle;

/**
 * Map tools (designations, room/item placement brushes) as synced commands.
 * The agent intercepts the tool classes' commit points — PlacableMultiTool's
 * private place() loop and the placable.place(...) calls of the single/
 * simple/fixed tools — and in interface context ships (placable address,
 * tiles/area, placer type, Job.overwrite) instead; every client then runs
 * the identical placement loop at the scheduled tick. Port of RW-MP's
 * designator syncing.
 */
public final class ToolSync {

    private static final int MULTI = 0, SIMPLE = 1, SIMPLE_TILE = 2, FIXED = 3, FIRST = 4, EXPANDED = 5;

    private static Field fPlacable, fArea, fType;

    private ToolSync() {
    }

    // ---- interception (UI thread) ---------------------------------------------

    /** Entry hook of PlacableMultiTool.place(): true = intercepted. */
    public static boolean multiPlace(Object tool) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            if (fPlacable == null) {
                fPlacable = ReflectUtil.field(tool.getClass(), "placable");
                fArea = ReflectUtil.field(tool.getClass(), "a");
                fType = ReflectUtil.field(tool.getClass(), "type");
            }
            PlacableMulti placable = ReflectUtil.get(fPlacable, tool);
            AREA area = ReflectUtil.get(fArea, tool);
            PLACER_TYPE type = ReflectUtil.get(fType, tool);
            String path = ObjectPaths.pathOf(placable);
            if (path == null) {
                unsynced(placable);
                return false;
            }
            ByteWriter w = new ByteWriter(256);
            w.writeByte(MULTI);
            w.writeString(path);
            w.writeByte(typeIndex(type));
            w.writeBool(Job.overwrite);
            writeArea(w, area);
            send(w);
            return true;
        } catch (RuntimeException e) {
            MpLog.err("tool sync failed; placing locally", e);
            return false;
        }
    }

    public static void placeSimple(PlacableSimple p, int x, int y) {
        if (!trySend(SIMPLE, p, x, y, 0, 0))
            p.place(x, y);
    }

    public static void placeSimpleTile(PlacableSimpleTile p, int tx, int ty) {
        if (!trySend(SIMPLE_TILE, p, tx, ty, 0, 0))
            p.place(tx, ty);
    }

    public static void placeFixed(PlacableFixed p, int tx, int ty, int rx, int ry) {
        if (!trySend(FIXED, p, tx, ty, rx, ry))
            p.place(tx, ty, rx, ry);
    }

    public static void placeFirst(PlacableSingle p, int tx, int ty) {
        if (!trySend(FIRST, p, tx, ty, 0, 0))
            p.placeFirst(tx, ty);
    }

    public static void placeExpanded(PlacableSingle p, int tx, int ty) {
        if (!trySend(EXPANDED, p, tx, ty, 0, 0))
            p.placeExpanded(tx, ty);
    }

    private static boolean trySend(int kind, Object placable, int a, int b, int c, int d) {
        if (!Multiplayer.shouldSync())
            return false;
        String path = ObjectPaths.pathOf(placable);
        if (path == null) {
            unsynced(placable);
            return false;
        }
        ByteWriter w = new ByteWriter(96);
        w.writeByte(kind);
        w.writeString(path);
        w.writeBool(Job.overwrite);
        w.writeInt(a);
        w.writeInt(b);
        w.writeInt(c);
        w.writeInt(d);
        send(w);
        return true;
    }

    private static void send(ByteWriter w) {
        syxmp.client.ClientCommands.send(CommandType.TOOL, w);
    }

    private static long lastUnsyncedLog;

    private static void unsynced(Object placable) {
        if (System.currentTimeMillis() - lastUnsyncedLog < 5000)
            return;
        lastUnsyncedLog = System.currentTimeMillis();
        MpLog.err("UNSYNCED tool (local only): no address for " + placable.getClass().getName()
                + " '" + (placable instanceof view.tool.PLACABLE pl ? pl.name() : "?") + "'");
    }

    // ---- execution (every client, scheduled tick) -----------------------------

    public static void execute(ByteReader data) {
        int kind = data.readByte();
        String path = data.readString(4096);
        Object placable = ObjectPaths.resolve(path);
        if (placable == null) {
            MpLog.err("tool target gone: " + path);
            return;
        }
        boolean over = Job.overwrite;
        try {
            if (kind == MULTI) {
                PLACER_TYPE type = PLACER_TYPE.all.get(data.readByte());
                Job.overwrite = data.readBool();
                SyncArea area = readArea(data);
                PlacableMulti p = (PlacableMulti) placable;
                p.finishChecking(area);
                if (p.isPlacable(area, type) != null)
                    return;
                RECTANGLE b = area.body();
                for (int y = b.y1(); y < b.y2(); y++)
                    for (int x = b.x1(); x < b.x2(); x++) {
                        if (!area.is(x, y) || p.isPlacable(x, y, area, type) != null)
                            continue;
                        p.place(x, y, area, type);
                    }
                p.finishPlacing(area);
                return;
            }
            Job.overwrite = data.readBool();
            int a = data.readInt(), b = data.readInt(), c = data.readInt(), d = data.readInt();
            switch (kind) {
                case SIMPLE -> ((PlacableSimple) placable).place(a, b);
                case SIMPLE_TILE -> ((PlacableSimpleTile) placable).place(a, b);
                case FIXED -> ((PlacableFixed) placable).place(a, b, c, d);
                case FIRST -> ((PlacableSingle) placable).placeFirst(a, b);
                case EXPANDED -> ((PlacableSingle) placable).placeExpanded(a, b);
                default -> MpLog.err("bad tool kind " + kind);
            }
        } finally {
            Job.overwrite = over;
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private static int typeIndex(PLACER_TYPE type) {
        for (int i = 0; i < PLACER_TYPE.all.size(); i++)
            if (PLACER_TYPE.all.get(i) == type)
                return i;
        return 0;
    }

    private static void writeArea(ByteWriter w, AREA area) {
        RECTANGLE b = area.body();
        int x1 = b.x1(), y1 = b.y1(), wd = Math.max(0, b.width()), ht = Math.max(0, b.height());
        w.writeInt(x1);
        w.writeInt(y1);
        w.writeInt(wd);
        w.writeInt(ht);
        BitSet bits = new BitSet(wd * ht);
        for (int y = 0; y < ht; y++)
            for (int x = 0; x < wd; x++)
                if (area.is(x1 + x, y1 + y))
                    bits.set(y * wd + x);
        w.writeBytes(bits.toByteArray());
    }

    private static SyncArea readArea(ByteReader r) {
        int x1 = r.readInt(), y1 = r.readInt(), wd = r.readInt(), ht = r.readInt();
        return new SyncArea(x1, y1, wd, ht, BitSet.valueOf(r.readBytes()));
    }

    /** A transferred tool area: rectangle + bitmap, as an AREA for the placers. */
    static final class SyncArea implements AREA {
        private final Rec body;
        private final int x1, y1, w, h;
        private final BitSet bits;
        private final int count;

        SyncArea(int x1, int y1, int w, int h, BitSet bits) {
            this.x1 = x1;
            this.y1 = y1;
            this.w = w;
            this.h = h;
            this.bits = bits;
            this.body = new Rec(x1, x1 + w, y1, y1 + h);
            this.count = bits.cardinality();
        }

        @Override
        public boolean is(int tile) {
            return is(tile % SETT.TWIDTH, tile / SETT.TWIDTH);
        }

        @Override
        public boolean is(int tx, int ty) {
            int x = tx - x1, y = ty - y1;
            return x >= 0 && y >= 0 && x < w && y < h && bits.get(y * w + x);
        }

        @Override
        public RECTANGLE body() {
            return body;
        }

        @Override
        public int area() {
            return count;
        }
    }
}
