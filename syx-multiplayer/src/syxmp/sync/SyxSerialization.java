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

    private static <T> int indexIn(snake2d.util.sets.LIST<T> list, T t) {
        for (int i = 0; i < list.size(); i++)
            if (list.get(i) == t)
                return i;
        return -1;
    }

    /** Linear scan over the entity pool (UI actions only, so the cost is irrelevant). */
    private static int humanoidIdOf(java.util.function.Predicate<settlement.entity.humanoid.Humanoid> p) {
        for (ENTITY e : SETT.ENTITIES().getAllEnts())
            if (e instanceof settlement.entity.humanoid.Humanoid h && p.test(h))
                return h.id();
        return -1;
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

        // --- static registries, by list index (init.type.*) ---------------------
        SyncSerialization.register(init.type.HCLASS.class,
                (w, c) -> w.writeInt(c == null ? -1 : c.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : init.type.HCLASSES.ALL().get(i);
                });
        SyncSerialization.register(init.type.HCLASS_RACE.class,
                (w, c) -> w.writeInt(c == null ? -1 : c.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : init.type.HCLASS_RACE.ALL().get(i);
                });
        SyncSerialization.register(init.type.CRIME_PUNISHMENTS.PUNISHMENT.class,
                (w, p) -> w.writeInt(p == null ? -1 : p.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : init.type.CRIME_PUNISHMENTS.ALL().get(i);
                });
        SyncSerialization.register(init.type.CRIMES.CRIME.class,
                (w, c) -> w.writeInt(c == null ? -1 : c.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : init.type.CRIMES.ALL().get(i);
                });
        SyncSerialization.register(init.type.WGROUP.class,
                (w, g) -> w.writeInt(g == null ? -1 : g.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : init.type.WGROUP.all().get(i);
                });
        SyncSerialization.register(init.type.HTYPE.class,
                (w, t) -> w.writeInt(t == null ? -1 : t.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : init.type.HTYPES.ALL().get(i);
                });
        SyncSerialization.register(init.tech.TECH.class,
                (w, t) -> w.writeInt(t == null ? -1 : t.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : init.tech.TECHS.ALL().get(i);
                });

        // --- per-game indexed objects --------------------------------------------
        SyncSerialization.register(settlement.stats.colls.StatsBattle.StatTraining.class,
                (w, t) -> w.writeInt(t == null ? -1 : indexIn(settlement.stats.STATS.BATTLE().TRAINING_ALL, t)),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : settlement.stats.STATS.BATTLE().TRAINING_ALL.get(i);
                });
        SyncSerialization.register(game.nobility.Noble.class,
                (w, n) -> w.writeInt(n == null ? -1 : n.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : game.GAME.NOBLE().ALL().get(i);
                });
        SyncSerialization.register(game.nobility.NobleOffice.class,
                (w, o) -> w.writeInt(o == null ? -1 : o.index),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : game.GAME.NOBLE().OFFICES.get(i);
                });
        SyncSerialization.register(game.battle.div.Div.class,
                (w, d) -> w.writeInt(d == null ? -1 : d.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : game.GAME.ARMIES().division((short) i);
                });
        SyncSerialization.register(world.entity.army.WArmy.class,
                (w, a) -> w.writeInt(a == null ? -1 : a.armyIndex()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : world.WORLD.ENTITIES().armies.get(i);
                });
        SyncSerialization.register(world.map.regions.Region.class,
                (w, reg) -> w.writeInt(reg == null ? -1 : reg.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : world.WORLD.REGIONS().getByIndex(i);
                });
        SyncSerialization.register(game.faction.Faction.class,
                (w, f) -> w.writeInt(f == null ? -1 : f.index()),
                r -> {
                    int i = r.readInt();
                    return i == -1 ? null : game.faction.FACTIONS.getByIndex(i);
                });

        // --- per-humanoid helpers, addressed through their humanoid's entity id ----
        SyncSerialization.register(settlement.entity.humanoid.ai.main.HAI.class,
                (w, ai) -> w.writeInt(ai == null ? -1 : humanoidIdOf(h -> h.ai() == ai)),
                r -> {
                    int id = r.readInt();
                    ENTITY e = id == -1 ? null : SETT.ENTITIES().getByID(id);
                    return e instanceof settlement.entity.humanoid.Humanoid h ? h.ai() : null;
                });
        SyncSerialization.register(settlement.stats.Induvidual.class,
                (w, in) -> w.writeInt(in == null ? -1 : humanoidIdOf(h -> h.indu() == in)),
                r -> {
                    int id = r.readInt();
                    ENTITY e = id == -1 ? null : SETT.ENTITIES().getByID(id);
                    return e instanceof settlement.entity.humanoid.Humanoid h ? h.indu() : null;
                });

        SyncSerialization.register(RESOURCE.class,
                (w, res) -> w.writeInt(res == null ? -1 : res.index()),
                r -> {
                    int index = r.readInt();
                    return index == -1 ? null : RESOURCES.ALL().get(index);
                });
    }
}
