package syxmp.sync;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.FBanner;
import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.main.RoomInstance;
import settlement.tilemap.SettMarks.SettMark;
import snake2d.util.color.ColorImp;
import snake2d.util.sprite.text.Str;
import syxmp.client.ClientCommands;
import syxmp.client.CommandExecutor;
import syxmp.client.Multiplayer;
import syxmp.common.CommandType;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;
import view.main.VIEW;
import world.WORLD;
import world.entity.army.WArmy;
import world.map.regions.Region;

/**
 * Sim-owned text and colours edited in place by UI widgets (sync-coverage.md
 * item 5): army/region/capital/room names and map-mark names are written by
 * {@code x.name.clear().add(text())} from a StringInputSprite.change();
 * banner and mark colours by ColorImp.setRed/Green/Blue from GColorPicker;
 * map marks are made/removed from UIMiniHotSpots. The Str/ColorImp receiver
 * is identified by scanning the few sim collections that own editable ones
 * (player armies, regions, room instances, marks, the player banner, the
 * division banners); unknown receivers (UI scratch strings) pass through.
 * Redirects are limited to the exact input-widget classes (V71.44 names) so
 * the scan never runs on render-time string building.
 */
public final class NameSync {

    static final byte OWNER_ARMY = 0, OWNER_REGION = 1, OWNER_ROOM = 2, OWNER_MARK = 3;
    static final byte COLOR_MARK = 0, COLOR_BANNER = 1, COLOR_DIV_BANNER = 2;

    private NameSync() {
    }

    private static Str pendingClear;

    /** Start of a frame: an intercepted clear().add() pair is over by then. */
    public static void frame() {
        pendingClear = null;
    }

    // --- Str ------------------------------------------------------------------

    private static long ownerOf(Str s) {
        for (WArmy a : FACTIONS.player().armies().all())
            if (a.name == s)
                return key(OWNER_ARMY, a.armyIndex());
        for (Region r : WORLD.REGIONS().all())
            if (r.info.name() == s)
                return key(OWNER_REGION, r.index());
        for (int i = 0; i < SETT.TILE_MAP().marks.all.size(); i++)
            if (SETT.TILE_MAP().marks.all.get(i).name == s)
                return key(OWNER_MARK, i);
        for (RoomBlueprintIns<?> b : SETT.ROOMS().ins())
            for (int i = 0; i < b.instancesSize(); i++) {
                RoomInstance ins = b.getInstance(i);
                if (ins != null && ins.name() == s)
                    return key(OWNER_ROOM, ins.index());
            }
        return -1;
    }

    private static long key(int kind, int index) {
        return ((long) kind << 32) | (index & 0xFFFFFFFFL);
    }

    private static Str resolveStr(int kind, int index) {
        switch (kind) {
            case OWNER_ARMY -> {
                WArmy a = WORLD.ENTITIES().armies.get(index);
                return a == null ? null : a.name;
            }
            case OWNER_REGION -> {
                Region r = WORLD.REGIONS().getByIndex(index);
                return r == null ? null : r.info.name();
            }
            case OWNER_MARK -> {
                return index >= 0 && index < SETT.TILE_MAP().marks.all.size() ? SETT.TILE_MAP().marks.all.get(index).name : null;
            }
            case OWNER_ROOM -> {
                Room room = SETT.ROOMS().map.getByIndex(index);
                return room instanceof RoomInstance ins ? ins.name() : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Redirect of Str.clear() in the name-input classes. */
    public static Str strClear(Str s) {
        if (Multiplayer.shouldSync() && ownerOf(s) >= 0) {
            pendingClear = s;
            return s;
        }
        return s.clear();
    }

    /** Redirect of Str.add(CharSequence) in the name-input classes. */
    public static Str strAdd(Str s, CharSequence text) {
        if (Multiplayer.shouldSync() && s == pendingClear) {
            pendingClear = null;
            long owner = ownerOf(s);
            if (owner >= 0) {
                ByteWriter w = new ByteWriter(64);
                w.writeByte(SyncActions.NAME_SET);
                w.writeByte((int) (owner >> 32));
                w.writeInt((int) owner);
                w.writeString(text.toString());
                ClientCommands.send(CommandType.ACTION, w);
                return s;
            }
        }
        return s.add(text);
    }

    static void executeNameSet(ByteReader r) {
        int kind = r.readByte();
        int index = r.readInt();
        String text = r.readString(256);
        Str s = resolveStr(kind, index);
        if (s == null) {
            MpLog.err("name target gone: kind " + kind + " #" + index);
            return;
        }
        s.clear().add(text);
    }

    // --- ColorImp ---------------------------------------------------------------

    private static ColorImp bannerColor(FBanner b, int sub) {
        return switch (sub) {
            case 0 -> b.colorBG();
            case 1 -> b.colorFG();
            case 2 -> b.colorBorder();
            case 3 -> b.colorPole();
            default -> null;
        };
    }

    /** @return {kind, index, sub} or null */
    private static int[] colorOwner(ColorImp c) {
        for (int i = 0; i < SETT.TILE_MAP().marks.all.size(); i++)
            if (SETT.TILE_MAP().marks.all.get(i).color == c)
                return new int[] {COLOR_MARK, i, 0};
        FBanner b = FACTIONS.player().banner();
        for (int sub = 0; sub < 4; sub++)
            if (bannerColor(b, sub) == c)
                return new int[] {COLOR_BANNER, FACTIONS.player().index(), sub};
        for (int i = 0; i < GAME.ARMIES().banners.size(); i++) {
            if (GAME.ARMIES().banners.get(i).col == c)
                return new int[] {COLOR_DIV_BANNER, i, 0};
            if (GAME.ARMIES().banners.get(i).bg == c)
                return new int[] {COLOR_DIV_BANNER, i, 1};
        }
        return null;
    }

    private static ColorImp resolveColor(int kind, int index, int sub) {
        switch (kind) {
            case COLOR_MARK -> {
                return index < SETT.TILE_MAP().marks.all.size() ? SETT.TILE_MAP().marks.all.get(index).color : null;
            }
            case COLOR_BANNER -> {
                game.faction.Faction f = FACTIONS.getByIndex(index);
                return f == null ? null : bannerColor(f.banner(), sub);
            }
            case COLOR_DIV_BANNER -> {
                if (index >= GAME.ARMIES().banners.size())
                    return null;
                return sub == 0 ? GAME.ARMIES().banners.get(index).col : GAME.ARMIES().banners.get(index).bg;
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean sendColor(ColorImp c, int channel, int v) {
        if (!Multiplayer.shouldSync())
            return false;
        int[] o = colorOwner(c);
        if (o == null)
            return false;
        ByteWriter w = new ByteWriter(16);
        w.writeByte(SyncActions.COLOR_SET);
        w.writeByte(o[0]);
        w.writeInt(o[1]);
        w.writeByte(o[2]);
        w.writeByte(channel);
        w.writeInt(v);
        ClientCommands.send(CommandType.ACTION, w);
        return true;
    }

    public static ColorImp setRed(ColorImp c, int v) {
        return sendColor(c, 0, v) ? c : c.setRed(v);
    }

    public static ColorImp setGreen(ColorImp c, int v) {
        return sendColor(c, 1, v) ? c : c.setGreen(v);
    }

    public static ColorImp setBlue(ColorImp c, int v) {
        return sendColor(c, 2, v) ? c : c.setBlue(v);
    }

    static void executeColorSet(ByteReader r) {
        int kind = r.readByte(), index = r.readInt(), sub = r.readByte(), channel = r.readByte(), v = r.readInt();
        ColorImp c = resolveColor(kind, index, sub);
        if (c == null) {
            MpLog.err("colour target gone: kind " + kind + " #" + index);
            return;
        }
        switch (channel) {
            case 0 -> c.setRed(v);
            case 1 -> c.setGreen(v);
            default -> c.setBlue(v);
        }
        if (kind == COLOR_BANNER) {
            // UIFactionBanner.change(): the minimap caches the realm colour
            game.faction.Faction f = FACTIONS.getByIndex(index);
            if (f != null)
                for (Region reg : f.realm().all())
                    WORLD.MINIMAP().updateRegion(reg);
        }
    }

    // --- map marks ----------------------------------------------------------------

    /** Entry hook for UIMiniHotSpots$1.placeFirst(tx, ty): the mark's colour is random (SettMarks.java:142). */
    public static boolean markPlace(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        ByteWriter w = new ByteWriter(16);
        w.writeByte(SyncActions.MARK_ADD);
        w.writeInt((Integer) args[0]);
        w.writeInt((Integer) args[1]);
        ClientCommands.send(CommandType.ACTION, w);
        VIEW.s().tools.placer.deactivate();
        return true;
    }

    /** Redirect of SettMark.remove() inside the hotspot panel. */
    public static void markRemove(SettMark m) {
        if (Multiplayer.shouldSync()) {
            int i = -1;
            for (int k = 0; k < SETT.TILE_MAP().marks.all.size(); k++)
                if (SETT.TILE_MAP().marks.all.get(k) == m)
                    i = k;
            if (i >= 0) {
                ByteWriter w = new ByteWriter(8);
                w.writeByte(SyncActions.MARK_REMOVE);
                w.writeInt(i);
                ClientCommands.send(CommandType.ACTION, w);
                return;
            }
        }
        m.remove();
    }

    static void executeMarkAdd(ByteReader r) {
        int tx = r.readInt(), ty = r.readInt();
        SettMark d = SETT.TILE_MAP().marks.make();
        if (d != null)
            d.set(tx, ty);
        else if (CommandExecutor.currentCmdIssuedBySelf)
            MpLog.info("no free map mark slot");
    }

    static void executeMarkRemove(ByteReader r) {
        int i = r.readInt();
        if (i >= 0 && i < SETT.TILE_MAP().marks.all.size())
            SETT.TILE_MAP().marks.all.get(i).remove();
    }

    static void execute(int kind, ByteReader r) {
        switch (kind) {
            case SyncActions.NAME_SET -> executeNameSet(r);
            case SyncActions.COLOR_SET -> executeColorSet(r);
            case SyncActions.MARK_ADD -> executeMarkAdd(r);
            case SyncActions.MARK_REMOVE -> executeMarkRemove(r);
            default -> MpLog.err("unknown name action " + kind);
        }
    }
}
