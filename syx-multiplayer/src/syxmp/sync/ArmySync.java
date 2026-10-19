package syxmp.sync;

import game.GAME;
import game.battle.util.DIV_SPEC.DIV_SPECE;
import game.faction.FACTIONS;
import game.faction.FCredits.CTYPE;
import game.faction.FResources.RTYPE;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.npc.FactionNPC;
import game.faction.royalty.opinion.ROPINION;
import init.constant.Config;
import init.race.RACES;
import init.race.Race;
import init.resources.RESOURCES;
import init.resources.ResSupply;
import settlement.main.SETT;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBattle.StatTraining;
import settlement.stats.equip.EquipBattle;
import snake2d.util.misc.CLAMP;
import syxmp.client.ClientCommands;
import syxmp.client.CommandExecutor;
import syxmp.client.Multiplayer;
import syxmp.common.CommandType;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;
import util.data.GETTER;
import view.main.VIEW;
import view.ui.div.UIDivEditor;
import view.world.ui.army.UIArmies;
import world.WORLD;
import world.army.AD;
import world.army.WDivMercenary;
import world.army.WDivRegional;
import world.entity.army.WArmy;
import world.entity.army.WArmyConstructor;
import world.map.regions.Region;
import world.region.RD;

/**
 * World-map army and region actions whose click handlers run several sim
 * mutations from UI-local editors (sync-coverage.md "Still local" item 3/4):
 * recruiting a regional division (ArmyRecruit.java:94, UI-local DIV_SPEC
 * editor + supply withdrawal), hiring mercenaries (UIMerenaries.java:106,
 * card selection), creating an army (List.java:211/356 — the first army's
 * tile is a random pick on the issuer and travels as data), and granting a
 * region autonomy (PlayInfo.java:244, six steps with RND inside, so the whole
 * sequence runs inside the command). Entry hooks on the anonymous handler
 * classes (names are V71.44) read the UI state reflectively, ship it, and
 * every client replays the vanilla body.
 */
public final class ArmySync {

    private ArmySync() {
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        return ReflectUtil.field(o.getClass(), name).get(o);
    }

    // --- recruit (ArmyRecruit$3.clickA) -----------------------------------------

    public static boolean recruitClick(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            Object outer = field(self, "this$0");
            UIDivEditor editor = (UIDivEditor) field(outer, "editor");
            DIV_SPECE div = editor.div();
            WArmy army = (WArmy) ReflectUtil.field(Class.forName("view.world.ui.army.Army"), "army").get(null);
            if (army == null)
                return false;
            ByteWriter w = new ByteWriter(256);
            w.writeByte(SyncActions.RECRUIT);
            SyncSerialization.write(w, WArmy.class, army);
            w.writeInt(div.race().index);
            w.writeInt(div.men());
            w.writeInt(div.bannerI());
            w.writeInt(STATS.BATTLE().TRAINING_ALL.size());
            for (StatTraining t : STATS.BATTLE().TRAINING_ALL)
                w.writeDouble(div.training(t));
            w.writeInt(STATS.EQUIP().BATTLE_ALL().size());
            for (EquipBattle e : STATS.EQUIP().BATTLE_ALL())
                w.writeDouble(div.equip(e));
            ClientCommands.send(CommandType.ACTION, w);
            MpLog.debug("synced recruit: " + div.men() + " " + div.race().key + " into army " + army.armyIndex());
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): recruit", e);
            return false;
        }
    }

    /** Mirror of ArmyRecruit.java:94-114. */
    static void executeRecruit(ByteReader r) {
        WArmy army = (WArmy) SyncSerialization.read(r, WArmy.class);
        Race race = RACES.all().get(r.readInt());
        int men = r.readInt();
        int bannerI = r.readInt();
        int nt = r.readInt();
        double[] tr = new double[nt];
        for (int i = 0; i < nt; i++)
            tr[i] = r.readDouble();
        int ne = r.readInt();
        double[] eq = new double[ne];
        for (int i = 0; i < ne; i++)
            eq[i] = r.readDouble();
        if (army == null || !army.divs().canAdd()) {
            MpLog.info("recruit ignored: army full or gone");
            return;
        }
        WDivRegional d = AD.regional().create(race, (double) men / Config.battle().MEN_PER_DIVISION, army);
        d.bannerSet(bannerI);
        for (ResSupply s : RESOURCES.SUP().ALL) {
            if (s.health <= 0)
                continue;
            int am = s.amount(race, men);
            am = CLAMP.i(am, 0, SETT.ROOMS().STOCKPILE.tally().amountReservable.get(s.resource));
            if (am > 0) {
                s.resource.remove(am, RTYPE.ARMY_SUPPLY);
                AD.supplies().get(s).current().inc(army, am);
            }
        }
        for (int i = 0; i < nt && i < STATS.BATTLE().TRAINING_ALL.size(); i++)
            d.target.trainingSet(STATS.BATTLE().TRAINING_ALL.get(i), tr[i]);
        for (int i = 0; i < ne && i < STATS.EQUIP().BATTLE_ALL().size(); i++)
            d.target.equipSet(STATS.EQUIP().BATTLE_ALL().get(i), eq[i]);
    }

    // --- mercenaries (UIMerenaries$4.clickA) ------------------------------------

    public static boolean mercHireClick(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            Object outer = field(self, "this$0");
            snake2d.util.sets.LIST<?> active = (snake2d.util.sets.LIST<?>) field(outer, "active");
            WArmy army = (WArmy) ReflectUtil.field(Class.forName("view.world.ui.army.Army"), "army").get(null);
            if (army == null)
                return false;
            ByteWriter w = new ByteWriter(64);
            w.writeByte(SyncActions.MERC_HIRE);
            SyncSerialization.write(w, WArmy.class, army);
            int n = 0;
            ByteWriter idx = new ByteWriter(64);
            for (int i = 0; i < active.size(); i++) {
                Object card = active.get(i);
                boolean sel = (Boolean) ReflectUtil.method(card.getClass(), "selectedIs").invoke(card);
                if (sel) {
                    idx.writeInt(ReflectUtil.field(card.getClass(), "ii").getInt(card));
                    n++;
                }
            }
            w.writeInt(n);
            w.writeRaw(idx.toArray());
            ClientCommands.send(CommandType.ACTION, w);
            MpLog.debug("synced mercenary hire: " + n + " companies into army " + army.armyIndex());
            VIEW.inters().popup.close();
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): mercenary hire", e);
            return false;
        }
    }

    /** Mirror of UIMerenaries.java:104-112. */
    static void executeMercHire(ByteReader r) {
        WArmy army = (WArmy) SyncSerialization.read(r, WArmy.class);
        int n = r.readInt();
        for (int k = 0; k < n; k++) {
            int i = r.readInt();
            if (army == null || !army.divs().canAdd())
                continue;
            if (i < 0 || i >= AD.mercenaries().size())
                continue;
            WDivMercenary d = AD.mercenaries().get(i);
            int cost = AD.mercenaries().signingCost(i);
            if (cost < FACTIONS.player().credits().credits()) {
                d.reassign(army);
                GAME.player().credits().inc(-cost, CTYPE.MERCINARIES);
            }
        }
    }

    // --- create army (List.java:211 first army, List$Placer.place) --------------

    /** Redirect of WArmyConstructor.create inside view/world/ui/army/List*. */
    public static WArmy armyCreate(WArmyConstructor c, int tx, int ty, Faction f) {
        if (!Multiplayer.shouldSync())
            return c.create(tx, ty, f);
        ByteWriter w = new ByteWriter(32);
        w.writeByte(SyncActions.ARMY_CREATE);
        w.writeInt(tx);
        w.writeInt(ty);
        SyncSerialization.write(w, Faction.class, f);
        ClientCommands.send(CommandType.ACTION, w);
        MpLog.debug("synced army creation at " + tx + "," + ty);
        return null;
    }

    /** Redirect of UIArmies.openList(WArmy) inside List*: the army does not exist yet when synced. */
    public static void openList(UIArmies ui, WArmy a) {
        if (a != null)
            ui.openList(a);
    }

    static void executeArmyCreate(ByteReader r) {
        int tx = r.readInt(), ty = r.readInt();
        Faction f = (Faction) SyncSerialization.read(r, Faction.class);
        if (f == null || !f.armies().canCreate()) {
            MpLog.info("army creation ignored: limit reached");
            return;
        }
        WArmy e = WORLD.ENTITIES().armies.create(tx, ty, f);
        if (CommandExecutor.currentCmdIssuedBySelf && e != null)
            VIEW.world().UI.armies.openList(e);
    }

    // --- grant autonomy (PlayInfo$9$1.exe) --------------------------------------

    @SuppressWarnings("unchecked")
    public static boolean autonomyExe(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            GETTER<Region> g = (GETTER<Region>) field(self, "val$g");
            Region reg = g.get();
            if (reg == null)
                return false;
            ByteWriter w = new ByteWriter(16);
            w.writeByte(SyncActions.AUTONOMY);
            SyncSerialization.write(w, Region.class, reg);
            ClientCommands.send(CommandType.ACTION, w);
            MpLog.debug("synced autonomy grant for region " + reg.index());
            return true;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): autonomy", e);
            return false;
        }
    }

    /** Mirror of PlayInfo.java:244-249. */
    static void executeAutonomy(ByteReader r) {
        Region reg = (Region) SyncSerialization.read(r, Region.class);
        if (reg == null || reg.faction() != FACTIONS.player() || FACTIONS.frees() <= 5) {
            MpLog.info("autonomy ignored: region not ours or no free faction slot");
            return;
        }
        RD.setFaction(reg, null, true);
        FactionNPC f = FACTIONS.activateNext(reg, null, true);
        f.generate(RD.RACES().get(FACTIONS.player().race()), true);
        ROPINION.OTHER().liberate(f);
        DIP.VASSAL().set(f, FACTIONS.player());
        GAME.events().world.dip.dismissWelcome(f);
    }

    // --- dispatch -----------------------------------------------------------------

    static void execute(int kind, ByteReader r) {
        switch (kind) {
            case SyncActions.RECRUIT -> executeRecruit(r);
            case SyncActions.MERC_HIRE -> executeMercHire(r);
            case SyncActions.ARMY_CREATE -> executeArmyCreate(r);
            case SyncActions.AUTONOMY -> executeAutonomy(r);
            default -> MpLog.err("unknown army action " + kind);
        }
    }
}
