package syxmp.sync;

import init.race.RACES;
import init.race.Race;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import settlement.entity.ENTITY;
import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprintImp;

/**
 * Sync workers for Songs of Syx game types — the analogue of RW-MP's
 * SyncDictRimWorld. References go over the wire as stable ids:
 * entities by generation-guarded handler id, rooms by slot index, static
 * content (races, resources, blueprints) by list index.
 */
public final class SyxSerialization {

    private SyxSerialization() {
    }

    public static void registerAll() {
        SyncSerialization.register(ENTITY.class,
                (w, e) -> w.writeInt(e == null ? -1 : e.id()),
                r -> {
                    int id = r.readInt();
                    return id == -1 ? null : SETT.ENTITIES().getByID(id);
                });

        SyncSerialization.register(Room.class,
                (w, room) -> w.writeInt(room == null ? -1 : room.index()),
                r -> {
                    int index = r.readInt();
                    return index == -1 ? null : SETT.ROOMS().map.getByIndex(index);
                });

        SyncSerialization.register(RoomBlueprintImp.class,
                (w, b) -> w.writeInt(b == null ? -1 : b.index()),
                r -> {
                    int index = r.readInt();
                    return index == -1 ? null : (RoomBlueprintImp) SETT.ROOMS().all().get(index);
                });

        SyncSerialization.register(Race.class,
                (w, race) -> w.writeInt(race == null ? -1 : race.index),
                r -> {
                    int index = r.readInt();
                    return index == -1 ? null : RACES.all().get(index);
                });

        SyncSerialization.register(RESOURCE.class,
                (w, res) -> w.writeInt(res == null ? -1 : res.index()),
                r -> {
                    int index = r.readInt();
                    return index == -1 ? null : RESOURCES.ALL().get(index);
                });
    }
}
