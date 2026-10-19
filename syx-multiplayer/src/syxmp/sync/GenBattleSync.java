package syxmp.sync;

import java.lang.reflect.Method;

import game.battle.state.BattleState;
import syxmp.client.ClientCommands;
import syxmp.client.Multiplayer;
import syxmp.client.SaveTransfer;
import syxmp.client.TickController;
import syxmp.common.CommandType;
import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;
import view.interrupter.Interrupter;
import view.main.VIEW;

/**
 * Generated tactical battles ("engage" on a world battle prompt).
 *
 * Vanilla (BattleState.java): engage saves the game to __beforeBattle,
 * generates the battle map in place, saves __battle and opens the battle
 * view with a modal "Start" (IDeploy). BattleView.update then ticks
 * BattleState.update(ds) per FRAME with wall-clock ds (BattleView.java:113),
 * which decides victory/defeat/throne timer and opens the modal result
 * (ILiveConclude); closing it calls liveResolve → BattleStateExiter.exit →
 * GameLoader(__beforeBattle).set() and, after that load, the world result
 * prompt.
 *
 * In MP: the ENGAGE decision (WorldBattleSync) runs setGenerate inside the
 * command on every client (same tick, same RND), then pauses the synced
 * clock like vanilla's modal does. BattleState.update runs from the lockstep
 * sub-step instead of the frame (viewUpdate is a no-op). Start, the result
 * screen's close, retreat and restart are commands addressed to
 * VIEW.b().state(), which every client created at the same tick; the
 * IDeploy / ILiveConclude interrupters are recorded by hooks on their
 * update() so the non-issuing clients can hide theirs. The loads
 * (__beforeBattle / __battle) are scheduled inside the command and handled
 * like a join-point reload (Hooks: GameLoader.set → reloading + yield;
 * SaveTransfer.finishReloadIfPending resumes at the same timer).
 */
public final class GenBattleSync {

    static final byte DEPLOY = 0, CONCLUDE = 1, RETREAT = 2, RELOAD = 3;

    private static Object deployInter;
    private static Object conclude;

    private GenBattleSync() {
    }

    public static void reset() {
        deployInter = null;
        conclude = null;
    }

    /** Deterministic "is a generated battle running" for the battle-view sim gate (Determinism.battleViewActive). */
    public static boolean inGeneratedBattle() {
        try {
            return VIEW.b() != null && VIEW.b().state() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Redirect of BattleState.update(ds) inside BattleView.update: per frame in vanilla, per sub-step in MP. */
    public static void viewUpdate(BattleState s, double ds) {
        if (!Multiplayer.active())
            s.update(ds);
    }

    /** From TickController.runSubStep. */
    public static void step(double ds) {
        if (!Multiplayer.active())
            return;
        try {
            BattleState s = VIEW.b().state();
            if (s != null)
                s.update(ds);
        } catch (RuntimeException e) {
            MpLog.err("battle state step failed", e);
        }
    }

    // --- interrupter bookkeeping (entry hooks on update(F)) --------------------------

    public static boolean deployUpdate(Object self, Object[] args) {
        deployInter = self;
        return false;
    }

    /** ILiveConclude.update calls GAME.SPEED.speedSet(1) every frame: skipped in MP (it would flood speed commands). */
    public static boolean concludeUpdate(Object self, Object[] args) {
        conclude = self;
        return Multiplayer.active();
    }

    private static void hide(Object inter) {
        if (!(inter instanceof Interrupter i) || !i.isActivated())
            return;
        try {
            Method m = ReflectUtil.method(Interrupter.class, "hide");
            m.invoke(i);
        } catch (ReflectiveOperationException e) {
            MpLog.err("cannot hide interrupter", e);
        }
    }

    // --- UI side -----------------------------------------------------------------------

    private static ByteWriter begin(byte kind) {
        ByteWriter w = new ByteWriter(16);
        w.writeByte(SyncActions.GEN_BATTLE);
        w.writeByte(kind);
        return w;
    }

    private static void send(ByteWriter w, String what) {
        ClientCommands.send(CommandType.ACTION, w);
        MpLog.debug("synced generated battle: " + what);
    }

    /** Redirect of BattleState.deploy() inside IDeploy (the Start button). */
    public static void deploy(BattleState s) {
        if (!Multiplayer.shouldSync()) {
            s.deploy();
            return;
        }
        send(begin(DEPLOY), "start");
    }

    /** Entry hook on ILiveConclude$1.close(): hide(); liveResolve(retreat, win). */
    public static boolean concludeClose(Object self, Object[] args) {
        if (!Multiplayer.shouldSync())
            return false;
        try {
            boolean retreat = ReflectUtil.field(self.getClass(), "val$retreat").getBoolean(self);
            boolean win = ReflectUtil.field(self.getClass(), "val$win").getBoolean(self);
            ByteWriter w = begin(CONCLUDE);
            w.writeBool(retreat);
            w.writeBool(win);
            send(w, "conclude (retreat=" + retreat + ", win=" + win + ")");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MpLog.err("UNSYNCED (local only): battle conclude", e);
            return false;
        }
    }

    /** Redirect of BattleState.liveRetreat() inside view/battle/*. */
    public static void liveRetreat(BattleState s) {
        if (!Multiplayer.shouldSync()) {
            s.liveRetreat();
            return;
        }
        send(begin(RETREAT), "retreat");
    }

    /** Redirect of BattleState.reloadBattle() inside view/battle/*. */
    public static void reloadBattle(BattleState s) {
        if (!Multiplayer.shouldSync()) {
            s.reloadBattle();
            return;
        }
        send(begin(RELOAD), "restart");
    }

    // --- execution ---------------------------------------------------------------------

    static void execute(ByteReader r) throws Exception {
        int kind = r.readByte();
        BattleState s = VIEW.b().state();
        if (s == null) {
            MpLog.err("generated battle command " + kind + " ignored: no battle running");
            return;
        }
        switch (kind) {
            case DEPLOY -> {
                if (!s.deploying())
                    return;
                s.deploy();
                hide(deployInter);
            }
            case CONCLUDE -> {
                boolean retreat = r.readBool(), win = r.readBool();
                hide(conclude);
                conclude = null;
                Method m = ReflectUtil.method(BattleState.class, "liveResolve", boolean.class, boolean.class);
                m.invoke(s, retreat, win);
            }
            case RETREAT -> s.liveRetreat();
            case RELOAD -> {
                // __battle was saved before BattleState.view() paused the game: pause again once it is loaded
                SaveTransfer.afterVanillaLoad = () -> TickController.speed = 0;
                s.reloadBattle();
            }
            default -> MpLog.err("unknown generated battle command " + kind);
        }
    }
}
