package syxmp.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import game.GAME;
import game.event.actions.EventAction;
import game.event.engine.EContext;
import game.event.engine.EVENT_HANDLER;
import game.event.engine.Event;
import game.faction.FACTIONS;
import game.faction.FCredits.CTYPE;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.deal.Deal;
import game.faction.diplomacy.deal.DealSave;
import init.trade.TR;
import init.trade.TRADABLE;
import init.trade.TRADE_TYPE;
import syxmp.client.ClientCommands;
import syxmp.client.Multiplayer;
import syxmp.common.CommandType;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;
import world.WORLD;
import world.map.regions.Region;
import world.region.RD;
import world.region.building.RDBuilding;
import world.region.building.RDLevelsTmp;

/**
 * Composite player actions whose UI handlers run several sim mutations on
 * UI-local objects (a message, a draft deal, a preview buffer). The agent
 * redirects the key call inside the UI class to the methods here; in
 * interface context the whole action is shipped as one ACTION command and
 * replayed from sim-addressable data on every client. RW-MP's equivalent is
 * the hand-written SyncDelegates for dialogs.
 */
public final class SyncActions {

    private static final byte EVENT_CHOICE = 0, DEAL_EXECUTE = 1, TRIBUTE_PAY = 2, REGION_BUILD = 3;

    private SyncActions() {
    }

    // =========================================================================
    // Event choice (game/event/engine/EventMessage.java:105-126)
    //
    // clickA: choiceSelect(e,k) -> for each action a.exe(e,data) -> set(null).
    // choiceSelect is redirected to record the pending choice; the first
    // exe(e,data) carries the context and sends the command; everything
    // else in that click is skipped locally.
    // =========================================================================

    private static Event pendingEvent;
    private static int pendingChoice = -1;
    private static boolean pendingSent;
    /** (ei, key, iteration) already chosen in this session: a second client's click is ignored. */
    private static final Set<String> chosen = new HashSet<>();

    public static void eventChoiceSelect(EVENT_HANDLER h, Event e, int k) {
        if (!Multiplayer.shouldSync()) {
            callChoiceSelect(h, e, k);
            return;
        }
        pendingEvent = e;
        pendingChoice = k;
        pendingSent = false;
        if (e.choices.get(k).actions.size() == 0)
            sendEventChoice(e, k, null);
    }

    public static void eventActionExe(EventAction a, Event e, EContext data) {
        if (pendingEvent == e && Multiplayer.shouldSync()) {
            if (!pendingSent)
                sendEventChoice(e, pendingChoice, data);
            return;
        }
        a.exe(e, data);
    }

    public static void eventSet(EVENT_HANDLER h, Event e, boolean keepInfo, boolean keepTime, boolean clearContext,
            boolean message) {
        if (pendingEvent != null && Multiplayer.shouldSync()) {
            // end of the intercepted click
            pendingEvent = null;
            pendingChoice = -1;
            return;
        }
        h.set(e, keepInfo, keepTime, clearContext, message);
    }

    /** Called at the start of every frame: an intercepted click is over by then. */
    public static void frame() {
        pendingEvent = null;
        pendingChoice = -1;
    }

    private static void sendEventChoice(Event e, int k, EContext data) {
        pendingSent = true;
        byte[] ctx;
        try {
            ctx = data == null ? new byte[0] : serialize(data);
        } catch (Exception ex) {
            MpLog.err("UNSYNCED (local only): event choice — context not serializable", ex);
            // run the vanilla click locally
            pendingEvent = null;
            callChoiceSelect(GAME.EVENT(), e, k);
            for (EventAction a : e.choices.get(k).actions)
                a.exe(e, data);
            if (GAME.EVENT().current() == e)
                GAME.EVENT().set(null, false, false, false, false);
            return;
        }
        ByteWriter w = new ByteWriter(ctx.length + 64);
        w.writeByte(EVENT_CHOICE);
        w.writeInt(e.allIndex);
        w.writeString(e.key);
        w.writeInt(GAME.EVENT().occ(e));
        w.writeInt(k);
        w.writeBytes(ctx);
        ClientCommands.send(CommandType.ACTION, w);
        MpLog.debug("synced event choice " + e.key + " #" + k);
    }

    private static void executeEventChoice(ByteReader r) throws Exception {
        int ei = r.readInt();
        String key = r.readString(256);
        int iteration = r.readInt();
        int k = r.readInt();
        byte[] ctx = r.readBytes();
        Event e = GAME.EVENT().read(ei, key);
        if (e == null) {
            MpLog.err("event choice: unknown event " + key);
            return;
        }
        if (GAME.EVENT().occ(e) != iteration) {
            MpLog.err("event choice for " + key + " ignored: iteration " + iteration + " != " + GAME.EVENT().occ(e));
            return;
        }
        String id = ei + "/" + key + "/" + iteration;
        if (!chosen.add(id)) {
            MpLog.info("event choice for " + key + " already made; ignoring");
            return;
        }
        EContext data = ctx.length == 0 ? new EContext() : (EContext) deserialize(ctx);
        callChoiceSelect(GAME.EVENT(), e, k);
        for (EventAction a : e.choices.get(k).actions)
            a.exe(e, data);
        if (GAME.EVENT().current() == e)
            GAME.EVENT().set(null, false, false, false, false);
        markLocalMessage(ei, key, k);
    }

    private static Method choiceSelect;

    private static void callChoiceSelect(EVENT_HANDLER h, Event e, int k) {
        try {
            if (choiceSelect == null)
                choiceSelect = ReflectUtil.method(EVENT_HANDLER.class, "choiceSelect", Event.class, int.class);
            choiceSelect.invoke(h, e, k);
        } catch (ReflectiveOperationException ex) {
            MpLog.err("choiceSelect failed", ex);
        }
    }

    /** Non-issuing clients: show the choice as taken in their copy of the message. */
    private static void markLocalMessage(int ei, String key, int k) {
        try {
            Object messages = view.main.VIEW.messages();
            Field fAll = findListField(messages.getClass());
            if (fAll == null)
                return;
            Object list = fAll.get(messages);
            if (!(list instanceof snake2d.util.sets.LIST<?> l))
                return;
            Class<?> em = Class.forName("game.event.engine.EventMessage");
            Field fEi = ReflectUtil.field(em, "ei"), fKey = ReflectUtil.field(em, "eKey"), fChoice = ReflectUtil.field(em, "choice");
            for (int i = 0; i < l.size(); i++) {
                Object m = l.get(i);
                if (em.isInstance(m) && fEi.getInt(m) == ei && key.equals(fKey.get(m)))
                    fChoice.setInt(m, k);
            }
        } catch (Throwable t) {
            // cosmetic only
        }
    }

    private static Field findListField(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields())
                if (snake2d.util.sets.LIST.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
        return null;
    }

    // =========================================================================
    // Deal execution (UIDiplomacy.java:101 accept, UIDipMessDeal.java:125)
    // The draft Deal is UI-local; DealSave is the game's own serializable
    // snapshot of it (factions by index, bools, parties).
    // =========================================================================

    public static double dealExecute(Deal deal, boolean changeOpinion) {
        if (!Multiplayer.shouldSync())
            return deal.execute(changeOpinion);
        try {
            byte[] save = serialize(new DealSave(deal));
            ByteWriter w = new ByteWriter(save.length + 16);
            w.writeByte(DEAL_EXECUTE);
            w.writeBool(changeOpinion);
            w.writeBytes(save);
            ClientCommands.send(CommandType.ACTION, w);
            MpLog.debug("synced deal execute");
            return 0;
        } catch (Exception e) {
            MpLog.err("UNSYNCED (local only): deal — " + e);
            return deal.execute(changeOpinion);
        }
    }

    private static void executeDeal(ByteReader r) throws Exception {
        boolean changeOpinion = r.readBool();
        DealSave save = (DealSave) deserialize(r.readBytes());
        Deal d = DIP.TMP();
        CharSequence problem = save.set(d);
        if (problem != null) {
            MpLog.err("deal not executable here: " + problem);
            return;
        }
        d.execute(changeOpinion);
    }

    // =========================================================================
    // Tribute (game/raiding/MessDemand.java Demand.pay)
    // =========================================================================

    public static void tributePay(Object demand) {
        int credits;
        int[] resources;
        try {
            credits = ReflectUtil.field(demand.getClass(), "credits").getInt(demand);
            resources = (int[]) ReflectUtil.field(demand.getClass(), "resources").get(demand);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("tribute: cannot read demand", e);
            pay(demand);
            return;
        }
        if (!Multiplayer.shouldSync()) {
            pay(demand);
            return;
        }
        ByteWriter w = new ByteWriter(64 + resources.length * 4);
        w.writeByte(TRIBUTE_PAY);
        w.writeInt(credits);
        w.writeInt(resources.length);
        for (int v : resources)
            w.writeInt(v);
        ClientCommands.send(CommandType.ACTION, w);
        MpLog.debug("synced tribute payment");
    }

    private static void pay(Object demand) {
        try {
            ReflectUtil.method(demand.getClass(), "pay").invoke(demand);
        } catch (ReflectiveOperationException e) {
            MpLog.err("tribute pay failed", e);
        }
    }

    /** Mirror of MessDemand.Demand.pay() (MessDemand.java:182). */
    private static void executeTribute(ByteReader r) {
        int credits = r.readInt();
        int n = r.readInt();
        int[] resources = new int[n];
        for (int i = 0; i < n; i++)
            resources[i] = r.readInt();
        FACTIONS.player().credits().inc(-credits, CTYPE.TRIBUTE);
        for (TRADABLE res : TR.ALL()) {
            if (res.index() >= resources.length)
                continue;
            FACTIONS.player().seller(res).remove(resources[res.index()], TRADE_TYPE.spoils, 0, null);
        }
    }

    // =========================================================================
    // Region buildings (view/world/ui/region/PlayBuildingsPop.java:637)
    // The preview buffer RD.BUILDINGS().tmp() is local; ship its levels.
    // =========================================================================

    public static void regionBuildAccept(RDLevelsTmp tmp) {
        if (!Multiplayer.shouldSync()) {
            tmp.accept();
            return;
        }
        Region reg;
        try {
            reg = (Region) ReflectUtil.field(RDLevelsTmp.class, "reg").get(tmp);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("region build: cannot read preview region", e);
            tmp.accept();
            return;
        }
        int n = RD.BUILDINGS().all.size();
        ByteWriter w = new ByteWriter(16 + n * 4);
        w.writeByte(REGION_BUILD);
        w.writeInt(reg.index());
        w.writeInt(n);
        for (int i = 0; i < n; i++)
            w.writeInt(tmp.level(RD.BUILDINGS().all.get(i), reg));
        ClientCommands.send(CommandType.ACTION, w);
        MpLog.debug("synced region buildings for region " + reg.index());
    }

    private static void executeRegionBuild(ByteReader r) {
        Region reg = WORLD.REGIONS().getByIndex(r.readInt());
        int n = r.readInt();
        RDLevelsTmp tmp = RD.BUILDINGS().tmp(true, reg);
        for (int i = 0; i < n && i < RD.BUILDINGS().all.size(); i++) {
            RDBuilding b = RD.BUILDINGS().all.get(i);
            tmp.levelSet(b, r.readInt());
        }
        tmp.accept();
    }

    // =========================================================================

    public static void execute(ByteReader data) throws Exception {
        int kind = data.readByte();
        switch (kind) {
            case EVENT_CHOICE -> executeEventChoice(data);
            case DEAL_EXECUTE -> executeDeal(data);
            case TRIBUTE_PAY -> executeTribute(data);
            case REGION_BUILD -> executeRegionBuild(data);
            default -> MpLog.err("unknown action kind " + kind);
        }
    }

    private static byte[] serialize(Object o) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(o);
        }
        return bos.toByteArray();
    }

    private static Object deserialize(byte[] b) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(b))) {
            return in.readObject();
        }
    }
}
