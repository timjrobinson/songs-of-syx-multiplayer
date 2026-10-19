package syxmp.sync;

import java.lang.reflect.Field;

import game.GAME;
import game.faction.FACTIONS;
import snake2d.util.misc.ACTION;
import syxmp.client.ClientCommands;
import syxmp.client.Multiplayer;
import syxmp.client.TickController;
import syxmp.common.CommandType;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;
import util.data.DOUBLE_O.DOUBLE_OE;
import view.world.ui.battle.UIWBattlePrompt;
import world.army.AD;
import world.battle.spec.WBattleResult;
import world.battle.spec.WBattleSide;
import world.battle.spec.WBattleSiege;
import world.battle.spec.WBattleSpec;

/**
 * World-map battle prompts (view/world/ui/battle/*): field battles, allied
 * assists, sallies, sieges, battle results and conquests.
 *
 * The sim creates the spec/result object inside WORLD.afterTick
 * (WBattles.poll, WORLD.java:323) or inside an earlier decision, and hands
 * it to UIWBattlePrompt — on every client, at the same Timer unit, so each
 * client holds its own copy of "the pending prompt". Only one prompt can be
 * open at a time (UIWBattlePrompt.Inter.set throws otherwise), so a
 * decision command addresses "the current prompt" plus a content key as a
 * sanity check. The vanilla prompt is modal (freezes the sim); in MP the
 * lockstep loop ignores the modal ds, so the hook pauses the synced clock
 * deterministically when a prompt opens and the close restores it.
 *
 * Every button first runs close.exe() and then the sim call; both are
 * redirected: CLOSE is its own command (the "Wait" button has no sim call),
 * so UIWBattlePrompt.isBusty(), which gates WBattles.canPoll, flips at the
 * same tick on every client (Determinism.worldBattlePromptBusy also keeps
 * the issuer "busy" until its decision executes). Without this, canPoll
 * stays true on the issuer and PFieldBattle.poll re-creates the same battle
 * until WBattles.poll throws after 1000 rounds (WBattles.java:70).
 *
 * Engage (a generated tactical battle in a separate saved game,
 * BattleState.setGenerate) is not supported in MP: it auto-resolves with a
 * notification. Mercy (AD.stats().mercy().incD, a default interface method)
 * is folded into the accept/occupy command.
 */
public final class WorldBattleSync {

    private static final byte CLOSE = 0, AUTO = 1, RETREAT = 2, ACCEPT = 3, OCCUPY = 4, ABANDON = 5, PUPPET = 6, ENGAGE = 7;

    private static UIWBattlePrompt prompt;
    /** The spec/result handed to the UI by the sim; stays addressable after CLOSE until a decision executes. */
    private static Object current;
    private static boolean paused;
    private static double speedBefore;
    private static volatile boolean decisionPending;
    private static double pendingMercy;
    private static boolean hasPendingMercy;
    private static Field fInter;

    private WorldBattleSync() {
    }

    public static boolean decisionPending() {
        return decisionPending;
    }

    /** A new GAME: forget prompts of the old one — unless the recorded prompt belongs to the new VIEW
     *  (a battle result opened inside GameLoader.doAfterSet, before the first frame of the new game). */
    public static void reset() {
        boolean keep = false;
        try {
            keep = prompt != null && prompt == view.main.VIEW.world().UI.battle && prompt.isBusty();
        } catch (RuntimeException e) {
            // no view yet
        }
        if (!keep) {
            prompt = null;
            current = null;
            paused = false;
        }
        decisionPending = false;
        hasPendingMercy = false;
    }

    // --- prompt opened (sim context on every client) -------------------------

    /** Entry hook for UIWBattlePrompt.battle/assist/lastStand/battleSally/siege/result(..): never intercepts. */
    public static boolean promptOpened(Object self, Object[] args) {
        if (!Multiplayer.active())
            return false;
        UIWBattlePrompt p = (UIWBattlePrompt) self;
        if (p.isBusty()) {
            MpLog.err("world battle prompt opened while another is open (vanilla drops it)");
            return false;
        }
        if (Multiplayer.shouldSync())
            MpLog.err("world battle prompt opened in interface context — not deterministic", new Throwable("origin"));
        prompt = p;
        current = args[0];
        if (!paused) {
            paused = true;
            speedBefore = TickController.speed;
        }
        TickController.speed = 0;
        MpLog.info("world battle prompt " + current.getClass().getName() + " key=" + Integer.toHexString(key(current))
                + " at timer " + TickController.timer + ": clock paused (was " + speedBefore + ")");
        return false;
    }

    /** Content key of a prompt object, equal on every client for the same battle. */
    static int key(Object o) {
        int h = o.getClass().getName().hashCode();
        if (o instanceof WBattleSpec s) {
            h = h * 31 + side(s.player);
            h = h * 31 + side(s.enemy);
            if (o instanceof WBattleSiege ws && ws.besiged != null)
                h = h * 31 + ws.besiged.index();
        } else if (o instanceof WBattleResult r) {
            h = h * 31 + (r.result == null ? -1 : r.result.ordinal());
            h = h * 31 + side(r.player);
            h = h * 31 + side(r.enemy);
        } else if (o instanceof WBattleSiege.Result r) {
            h = h * 31 + (r.besiged == null ? -1 : r.besiged.index());
        }
        return h;
    }

    private static int side(WBattleSide s) {
        if (s == null)
            return 0;
        int h = s.men();
        if (s.coo() != null)
            h = h * 31 + s.coo().x() * 7919 + s.coo().y();
        return h;
    }

    private static ACTION inter(UIWBattlePrompt p) {
        try {
            if (fInter == null) {
                fInter = UIWBattlePrompt.class.getDeclaredField("inter");
                fInter.setAccessible(true);
            }
            return (ACTION) fInter.get(p);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("cannot reach the battle prompt interrupter", e);
            return null;
        }
    }

    // --- UI redirects (interface side) ----------------------------------------

    private static boolean intercept(Object target) {
        if (!Multiplayer.shouldSync())
            return false;
        if (target != current) {
            MpLog.err("UNSYNCED (local only): world battle decision on an object that is not the current prompt");
            return false;
        }
        return true;
    }

    private static ByteWriter begin(byte kind) {
        ByteWriter w = new ByteWriter(256);
        w.writeByte(SyncActions.WORLD_BATTLE);
        w.writeByte(kind);
        w.writeInt(key(current));
        return w;
    }

    private static void send(ByteWriter w, String what) {
        ClientCommands.send(CommandType.ACTION, w);
        decisionPending = true;
        MpLog.debug("synced world battle decision: " + what);
    }

    /** close.exe() inside the prompt classes: the prompt's own interrupter closes by command. */
    public static void close(ACTION a) {
        if (Multiplayer.shouldSync() && prompt != null && current != null && a == inter(prompt)) {
            send(begin(CLOSE), "close");
            return;
        }
        a.exe();
    }

    public static void engage(WBattleSpec s) {
        if (!intercept(s)) {
            s.engage();
            return;
        }
        send(begin(ENGAGE), "engage");
    }

    public static void auto(WBattleSpec s) {
        if (!intercept(s)) {
            s.auto();
            return;
        }
        send(begin(AUTO), "auto");
    }

    public static void retreat(WBattleSpec s) {
        if (!intercept(s)) {
            s.retreat();
            return;
        }
        send(begin(RETREAT), "retreat");
    }

    public static void engage(WBattleSiege s) {
        engage((WBattleSpec) s);
    }

    public static void auto(WBattleSiege s) {
        auto((WBattleSpec) s);
    }

    public static void retreat(WBattleSiege s) {
        retreat((WBattleSpec) s);
    }

    /** AD.stats().mercy().incD(player, m) precedes accept/occupy in the same click: folded into that command. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DOUBLE_OE mercyInc(DOUBLE_OE o, Object key, double d) {
        if (Multiplayer.shouldSync() && current != null) {
            pendingMercy = d;
            hasPendingMercy = true;
            return o;
        }
        return o.incD(key, d);
    }

    public static void accept(WBattleResult r, int[] enslave, int[] resources) {
        if (!intercept(r)) {
            r.accept(enslave, resources);
            return;
        }
        ByteWriter w = begin(ACCEPT);
        writeMercy(w);
        writeArr(w, enslave);
        writeArr(w, resources);
        send(w, "accept");
    }

    public static void occupy(WBattleSiege.Result r, double devastation, double death, int[] enslave, int[] resources) {
        siege(r, OCCUPY, "occupy", devastation, death, enslave, resources);
    }

    public static void abandon(WBattleSiege.Result r, double devastation, double death, int[] enslave, int[] resources) {
        siege(r, ABANDON, "abandon", devastation, death, enslave, resources);
    }

    public static void puppet(WBattleSiege.Result r, double devastation, double death, int[] enslave, int[] resources) {
        siege(r, PUPPET, "puppet", devastation, death, enslave, resources);
    }

    private static void siege(WBattleSiege.Result r, byte kind, String what, double devastation, double death,
            int[] enslave, int[] resources) {
        if (!intercept(r)) {
            callSiege(r, kind, devastation, death, enslave, resources);
            return;
        }
        ByteWriter w = begin(kind);
        writeMercy(w);
        w.writeDouble(devastation);
        w.writeDouble(death);
        writeArr(w, enslave);
        writeArr(w, resources);
        send(w, what);
    }

    private static void callSiege(WBattleSiege.Result r, byte kind, double devastation, double death, int[] enslave,
            int[] resources) {
        switch (kind) {
            case OCCUPY -> r.occupy(devastation, death, enslave, resources);
            case ABANDON -> r.abandon(devastation, death, enslave, resources);
            case PUPPET -> r.puppet(devastation, death, enslave, resources);
            default -> MpLog.err("unknown siege decision " + kind);
        }
    }

    private static void writeMercy(ByteWriter w) {
        w.writeBool(hasPendingMercy);
        w.writeDouble(hasPendingMercy ? pendingMercy : 0);
        hasPendingMercy = false;
    }

    private static void writeArr(ByteWriter w, int[] a) {
        if (a == null) {
            w.writeInt(-1);
            return;
        }
        w.writeInt(a.length);
        for (int v : a)
            w.writeInt(v);
    }

    private static int[] readArr(ByteReader r) {
        int n = r.readInt();
        if (n < 0)
            return null;
        int[] a = new int[n];
        for (int i = 0; i < n; i++)
            a[i] = r.readInt();
        return a;
    }

    // --- execution (every client, inside the command) ------------------------

    static void execute(ByteReader r) {
        int kind = r.readByte();
        int key = r.readInt();
        decisionPending = false;
        if (current == null) {
            MpLog.err("world battle decision " + kind + " ignored: no prompt pending");
            return;
        }
        if (key(current) != key) {
            MpLog.err("world battle decision " + kind + " ignored: key " + Integer.toHexString(key) + " != "
                    + Integer.toHexString(key(current)) + " (already decided?)");
            return;
        }
        if (kind == CLOSE) {
            closeCurrent();
            return;
        }
        Object target = current;
        current = null; // a decision is final; a dup must not re-ship spoils
        closeCurrent();
        boolean mercy = r.readBool();
        double m = r.readDouble();
        if (mercy)
            AD.stats().mercy().incD(FACTIONS.player(), m);
        switch (kind) {
            case AUTO -> ((WBattleSpec) target).auto();
            case ENGAGE -> {
                // BattleState.setGenerate: save __beforeBattle, generate the battle map in place,
                // save __battle, open the battle view (BattleState.java:54). Vanilla then
                // holds the game with a modal Start button; the synced clock pauses instead.
                MpLog.info("engage: generating tactical battle at timer " + TickController.timer);
                ((WBattleSpec) target).engage();
                TickController.speed = 0;
                paused = false;
            }
            case RETREAT -> ((WBattleSpec) target).retreat();
            case ACCEPT -> ((WBattleResult) target).accept(readArr(r), readArr(r));
            case OCCUPY, ABANDON, PUPPET -> {
                double devastation = r.readDouble(), death = r.readDouble();
                callSiege((WBattleSiege.Result) target, (byte) kind, devastation, death, readArr(r), readArr(r));
            }
            default -> MpLog.err("unknown world battle decision " + kind);
        }
    }

    private static void closeCurrent() {
        if (prompt != null && prompt.isBusty()) {
            ACTION a = inter(prompt);
            if (a != null)
                a.exe();
        }
        if (paused && (prompt == null || !prompt.isBusty())) {
            paused = false;
            TickController.speed = speedBefore;
            MpLog.info("world battle prompt closed at timer " + TickController.timer + ": clock restored to " + speedBefore);
        }
    }
}
