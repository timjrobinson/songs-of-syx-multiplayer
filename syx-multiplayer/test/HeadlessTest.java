import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import syxmp.agent.MpTransformer;
import syxmp.agent.PatchTable;
import syxmp.agent.SyncTemplates;
import syxmp.client.ClientCommands;
import syxmp.client.MainThread;
import syxmp.client.Multiplayer;
import syxmp.client.MultiplayerSession;
import syxmp.client.SaveTransfer;
import syxmp.client.state.ClientJoiningState;
import syxmp.net.ConnectionStateEnum;
import syxmp.net.TcpConnection;
import syxmp.server.MultiplayerServer;
import syxmp.server.ServerSettings;
import syxmp.server.TcpServerTransport;

/**
 * Headless validation of the machinery that doesn't need the running game:
 * the bytecode transformer against real game classes, and the full TCP join
 * handshake + command round-trip against a live relay server.
 */
public final class HeadlessTest {

    static int failures;

    public static void main(String[] args) throws Exception {
        testAutoSyncTableResolves();
        testCallRedirectsResolve();
        testTransformerGameSpeed();
        testTransformerViewRedirect();
        testFieldAndUiRedirects();
        testBattleOrderHooks();
        testWorldBattleRedirects();
        testUiStateHooks();
        testBattleOrderCodec();
        testJoinFlowOverTcp();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Every CallRedirect's replacement must exist as a static method with the descriptor the transformer emits
     *  (receiver prepended for virtual/interface calls, unchanged for static ones) — a NoSuchMethodError
     *  otherwise surfaces only when the player clicks (session 3's tribute redirect never linked). */
    static void testCallRedirectsResolve() throws Exception {
        System.out.println("CallRedirect resolution:");
        int bad = 0, n = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PatchTable.CallRedirect r : PatchTable.build().redirects) {
            String name = r.newName().equals("*") ? r.targetName() : r.newName();
            String recv = r.objectReceiver() ? "java/lang/Object" : r.targetOwner();
            String virt = "(L" + recv + ";" + r.targetDesc().substring(1);
            String key = r.newOwner() + "." + name + virt;
            if (!seen.add(key))
                continue;
            n++;
            try {
                Class<?> owner = Class.forName(r.newOwner().replace('/', '.'), false, HeadlessTest.class.getClassLoader());
                boolean found = false;
                for (java.lang.reflect.Method m : owner.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                        continue;
                    String d = org.objectweb.asm.Type.getMethodDescriptor(m);
                    if (d.equals(virt) || d.equals(r.targetDesc()))
                        found = true;
                }
                if (!found) {
                    bad++;
                    System.out.println("  MISSING replacement: " + key + " for " + r.targetOwner() + "." + r.targetName() + r.targetDesc());
                }
            } catch (Throwable t) {
                bad++;
                System.out.println("  MISSING class: " + key + " (" + t + ")");
            }
        }
        check(bad == 0, "all " + n + " call redirect replacements resolve");
    }

    /** Every AutoSyncTable entry must name a real declared method / field of the shipped game. */
    static void testAutoSyncTableResolves() throws Exception {
        System.out.println("AutoSyncTable resolution:");
        int bad = 0;
        for (String key : syxmp.agent.AutoSyncTable.METHODS) {
            String[] parts = key.split(" ");
            try {
                Class<?> owner = Class.forName(parts[0].replace('/', '.'), false, HeadlessTest.class.getClassLoader());
                boolean found = false;
                for (java.lang.reflect.Method m : owner.getDeclaredMethods())
                    if (m.getName().equals(parts[1]) && org.objectweb.asm.Type.getMethodDescriptor(m).equals(parts[2]))
                        found = true;
                if (!found) {
                    bad++;
                    System.out.println("  MISSING method: " + key);
                }
            } catch (Throwable t) {
                bad++;
                System.out.println("  MISSING class: " + key + " (" + t + ")");
            }
        }
        for (String[] f : syxmp.agent.AutoSyncTable.FIELDS) {
            try {
                Class<?> owner = Class.forName(f[1].replace('/', '.'), false, HeadlessTest.class.getClassLoader());
                java.lang.reflect.Field fld = owner.getDeclaredField(f[2]);
                if (!org.objectweb.asm.Type.getDescriptor(fld.getType()).equals(f[3])) {
                    bad++;
                    System.out.println("  WRONG field desc: " + String.join(" ", f) + " is " + fld.getType());
                }
            } catch (Throwable t) {
                bad++;
                System.out.println("  MISSING field: " + String.join(" ", f) + " (" + t + ")");
            }
        }
        check(bad == 0, "all " + syxmp.agent.AutoSyncTable.METHODS.length + " methods and "
                + syxmp.agent.AutoSyncTable.FIELDS.length + " fields resolve");
    }

    static void check(boolean cond, String what) {
        if (cond) {
            System.out.println("  ok: " + what);
        } else {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    static byte[] transform(String internalName) throws Exception {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
            byte[] original = in.readAllBytes();
            MpTransformer t = new MpTransformer(PatchTable.build());
            byte[] out = t.transform(null, internalName, null, null, original);
            check(out != null, internalName + " was transformed");
            return out;
        }
    }

    /** Load the transformed GameSpeed and prove the injected hook gates the body. */
    static void testTransformerGameSpeed() throws Exception {
        System.out.println("testTransformerGameSpeed");
        byte[] patched = transform("game/GameSpeed");

        ClassLoader loader = new ClassLoader(HeadlessTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("game.GameSpeed"))
                    return defineClass(name, patched, 0, patched.length);
                throw new ClassNotFoundException(name);
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("game.GameSpeed")) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null)
                        c = findClass(name);
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        };

        Class<?> cls = loader.loadClass("game.GameSpeed");
        Constructor<?> ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object speed = ctor.newInstance();
        Method speedSet = cls.getMethod("speedSet", double.class);
        Method speedTarget = cls.getMethod("speedTarget");

        // No handler registered: original body must run
        speedSet.invoke(speed, 5.0);
        check((Double) speedTarget.invoke(speed) == 5.0, "unhooked speedSet runs original");

        // Handler intercepts: body must be skipped
        boolean[] called = { false };
        SyncTemplates.registerAt(PatchTable.HOOK_SPEED_SET, (self, a) -> {
            called[0] = true;
            check(self == speed, "hook receives the instance");
            check((Double) a[0] == 3.0, "hook receives boxed args");
            return true;
        });
        speedSet.invoke(speed, 3.0);
        check(called[0], "hook was invoked");
        check((Double) speedTarget.invoke(speed) == 5.0, "hooked speedSet skips original");
    }

    /** Verify VIEW.update's game.update call was rewritten to TickController. */
    /** Count INVOKESTATIC calls into {@code owner} across all methods of the transformed class. */
    static int countCallsInto(byte[] patched, String ownerPrefix) {
        int[] n = { 0 };
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC && owner.startsWith(ownerPrefix))
                            n[0]++;
                    }
                };
            }
        }, 0);
        return n[0];
    }

    /** PUTFIELD redirects (FieldSync) and UI call-site redirects (UiRedirects) reach the bytecode. */
    static void testFieldAndUiRedirects() throws Exception {
        System.out.println("testFieldAndUiRedirects");
        byte[] sel = transform("view/battle/UISelection");
        check(countCallsInto(sel, "syxmp/sync/FieldSync") >= 5,
                "UISelection division-setting writes go through FieldSync");
        byte[] breeder = transform("settlement/room/service/breeder/Gui$2");
        check(countCallsInto(breeder, "syxmp/sync/FieldSync") >= 1,
                "breeder Gui$2 prosecute write goes through FieldSync");
        byte[] pop = transform("view/sett/ui/standing/CatPopulationGrowth$8");
        check(countCallsInto(pop, "syxmp/sync/UiRedirects") >= 1,
                "CatPopulationGrowth$8 birth-limit RMapInt.set goes through UiRedirects");
        byte[] pop2 = transform("view/sett/ui/standing/CatPopulationGrowth$9");
        check(countCallsInto(pop2, "syxmp/sync/UiRedirects") >= 1,
                "CatPopulationGrowth$9 ArrayInt.set goes through UiRedirects");
        byte[] rdb = transform("world/region/building/RDBuilding");
        check(countCallsInto(rdb, "syxmp/client/Determinism") >= 1,
                "RDBuilding preview-level reads go through Determinism.rdLevel");
    }

    static void testTransformerViewRedirect() throws Exception {
        System.out.println("testTransformerViewRedirect");
        byte[] patched = transform("view/main/VIEW");
        boolean[] found = { false };
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String sig, String[] ex) {
                if (!name.equals("update"))
                    return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mname,
                            String mdesc, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC
                                && owner.equals("syxmp/client/TickController")
                                && mname.equals("gameUpdate")
                                && mdesc.equals("(Lgame/GAME;DD)V"))
                            found[0] = true;
                        if (owner.equals("game/GAME") && mname.equals("update")
                                && mdesc.equals("(DD)V"))
                            check(false, "unredirected game.update call remains");
                    }
                };
            }
        }, 0);
        check(found[0], "VIEW.update redirected to TickController.gameUpdate");
    }

    /** Full join over real TCP: handshake, fragmented world transfer, command echo. */
    /** The two tactical-battle entry points carry the SyncTemplates guard. */
    static void testBattleOrderHooks() throws Exception {
        System.out.println("testBattleOrderHooks");
        byte[] locked = transform("game/battle/thread/order/BattleOrder$Locked");
        check(countCallsInto(locked, "syxmp/agent/SyncTemplates") == 1, "BattleOrder.Locked.set is hooked");
        byte[] armies = transform("game/battle/Armies");
        check(countCallsInto(armies, "syxmp/agent/SyncTemplates") == 1, "Armies.initAndTeleport is hooked");
    }

    /** World battle prompts: the prompt entry hooks and every button's close + decision redirects. */
    static void testWorldBattleRedirects() throws Exception {
        System.out.println("testWorldBattleRedirects");
        byte[] prompt = transform("view/world/ui/battle/UIWBattlePrompt");
        check(countCallsInto(prompt, "syxmp/agent/SyncTemplates") == 7, "all 7 UIWBattlePrompt entry points hooked");
        byte[] engage = transform("view/world/ui/battle/BattleBattle$1"); // close.exe(); g.engage();
        check(countCallsInto(engage, "syxmp/sync/WorldBattleSync") == 2, "BattleBattle engage button: close + engage redirected");
        byte[] res = transform("view/world/ui/battle/Res$1"); // close; mercy incD; accept x2
        check(countCallsInto(res, "syxmp/sync/WorldBattleSync") == 4, "Res accept button: close + mercy + accept redirected");
        byte[] siege = transform("view/world/ui/battle/BattleSiege$3"); // close; spec.retreat() (lift siege)
        check(countCallsInto(siege, "syxmp/sync/WorldBattleSync") >= 1, "BattleSiege buttons redirected");
        byte[] conq = transform("view/world/ui/battle/Conquer$7"); // close; mercy; occupy
        check(countCallsInto(conq, "syxmp/sync/WorldBattleSync") == 3, "Conquer occupy button: close + mercy + occupy redirected");
    }

    /** Session 4 "still local" handlers: every hooked class carries the guard, every redirected call site is rewritten. */
    static void testUiStateHooks() throws Exception {
        System.out.println("testUiStateHooks");
        String[] hooked = {"view/world/ui/army/ArmyRecruit$3", "view/world/ui/army/UIMerenaries$4",
                "view/world/ui/region/PlayInfo$9$1", "view/sett/ui/home/UIHomeAssign",
                "view/sett/ui/room/priority/UIRoomRaceAssign", "view/sett/ui/home/UIHomeOdd",
                "settlement/room/health/hospital/Gui$1", "settlement/room/infra/logistics/MoveOrderPullUI$1",
                "settlement/room/infra/logistics/MoveOrderPullUI$Placer", "settlement/room/infra/logistics/MoveOrderPullUI$Detail$8",
                "settlement/room/infra/logistics/MoveOrderPullUI$Paster", "view/sett/ui/right/UIMiniHotSpots$1",
                "view/sett/ui/room/construction/UIRoomPlacer"};
        for (String c : hooked)
            check(countCallsInto(transform(c), "syxmp/agent/SyncTemplates") >= 1, c + " is hooked");
        check(countCallsInto(transform("view/world/ui/army/List$Placer"), "syxmp/sync/ArmySync") == 2,
                "List$Placer: create + openList redirected");
        check(countCallsInto(transform("view/world/ui/army/Army$2"), "syxmp/sync/NameSync") == 2,
                "Army$2: Str.clear + add redirected");
        check(countCallsInto(transform("view/sett/ui/room/UIRoom$Detail$1"), "syxmp/sync/NameSync") == 2,
                "UIRoom$Detail$1: Str.clear + add redirected");
        int colour = 0;
        for (int i = 1; i <= 3; i++)
            colour += countCallsInto(transform("util/gui/misc/GColorPicker$" + i), "syxmp/sync/NameSync");
        check(colour == 3, "GColorPicker: setRed/Green/Blue redirected (" + colour + ")");
        check(countCallsInto(transform("view/sett/ui/right/UIMiniHotSpots$Panel$2"), "syxmp/sync/NameSync") == 1,
                "UIMiniHotSpots: mark remove redirected");
    }

    /** The by-value codec for DivFormationImp / BattleOrderTask round-trips every field Locked.set copies. */
    static void testBattleOrderCodec() throws Exception {
        System.out.println("testBattleOrderCodec");
        Class<?> form = Class.forName("game.battle.formation.DivFormationImp");
        Constructor<?> ctor = form.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        Object a = ctor.newInstance(8), b = ctor.newInstance(8);
        // public position API (DivPositionImp) + reflective formation fields; clear()/deployInit touch SETT, so avoid them
        Method set = form.getMethod("set", int.class, int.class, int.class), init = form.getMethod("init", int.class);
        set.invoke(a, 0, 100, 200);
        set.invoke(a, 1, 116, 200);
        set.invoke(a, 2, 132, 216);
        init.invoke(a, 3);
        form.getMethod("setDir", int.class, snake2d.util.datatypes.DIR.class).invoke(a, 1, snake2d.util.datatypes.DIR.SE);
        form.getMethod("setHasExtraRoom").invoke(a);
        form.getMethod("coherentSetNot").invoke(a);
        setField(a, "faceDir", snake2d.util.datatypes.DIR.W);
        setField(a, "dx", 0.6);
        setField(a, "dy", -0.8);
        setField(a, "width", 96);
        setField(a, "ts", game.battle.formation.DIV_FORMATION.LOOSE);
        setField(a, "centreI", 1);
        ((snake2d.util.datatypes.Rec) getField(a, "bounds")).set(90, 140, 190, 230);
        ((snake2d.util.datatypes.Coo) getField(a, "start")).set(100, 200);

        Method enc = Class.forName("syxmp.sync.BattleOrderSync").getDeclaredMethod("encodeFormation",
                syxmp.net.ByteWriter.class, form);
        Method dec = Class.forName("syxmp.sync.BattleOrderSync").getDeclaredMethod("decodeFormation",
                syxmp.net.ByteReader.class, form);
        enc.setAccessible(true);
        dec.setAccessible(true);
        syxmp.net.ByteWriter w = new syxmp.net.ByteWriter(256);
        enc.invoke(null, w, a);
        byte[] bytes = w.toArray();
        dec.invoke(null, new syxmp.net.ByteReader(bytes), b);
        syxmp.net.ByteWriter w2 = new syxmp.net.ByteWriter(256);
        enc.invoke(null, w2, b);
        check(java.util.Arrays.equals(bytes, w2.toArray()), "formation re-encodes identically after decode");
        check((Boolean) form.getMethod("isSameAs", form).invoke(a, b), "decoded formation isSameAs the original");
        check(form.getMethod("dir", int.class).invoke(b, 1) == snake2d.util.datatypes.DIR.SE, "dir masks copied");
        check((Boolean) form.getMethod("hasExtraRoom").invoke(b) && !(Boolean) form.getMethod("isCoherent").invoke(b),
                "flags copied");
        check(((snake2d.util.datatypes.Rec) getField(b, "bounds")).x2() == 140, "bounds copied");

        Class<?> taskC = Class.forName("game.battle.thread.order.BattleOrderTask");
        Object t = taskC.getConstructor().newInstance(), t2 = taskC.getConstructor().newInstance();
        setField(t, "task", game.battle.thread.order.BattleOrderTask.DIVTASK.ATTACK_BUILDING);
        setField(t, "target", 0x10 | 0x20 << 16);
        setField(t, "orderedWhenFighting", true);
        Method encT = Class.forName("syxmp.sync.BattleOrderSync").getDeclaredMethod("encodeTask",
                syxmp.net.ByteWriter.class, taskC);
        Method decT = Class.forName("syxmp.sync.BattleOrderSync").getDeclaredMethod("decodeTask",
                syxmp.net.ByteReader.class, taskC);
        encT.setAccessible(true);
        decT.setAccessible(true);
        w = new syxmp.net.ByteWriter(32);
        encT.invoke(null, w, t);
        decT.invoke(null, new syxmp.net.ByteReader(w.toArray()), t2);
        check(taskC.getMethod("task").invoke(t2) == game.battle.thread.order.BattleOrderTask.DIVTASK.ATTACK_BUILDING
                && (Integer) taskC.getMethod("targetTileX").invoke(t2) == 0x10
                && (Integer) taskC.getMethod("targetTileY").invoke(t2) == 0x20
                && (Boolean) taskC.getMethod("orderedWhenFighting").invoke(t2), "task round-trips");
    }

    static void setField(Object o, String name, Object v) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    static Object getField(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static void testJoinFlowOverTcp() throws Exception {
        System.out.println("testJoinFlowOverTcp");

        byte[] fakeSave = new byte[300_000];
        new SecureRandom().nextBytes(fakeSave);

        ServerSettings settings = new ServerSettings();
        settings.port = 35999;
        MultiplayerServer server = new MultiplayerServer(settings);
        server.gameVersion = game.VERSION.VERSION;
        server.startFrom(1234, fakeSave, 42L);
        TcpServerTransport transport = new TcpServerTransport(server);
        transport.start();
        server.start();

        MultiplayerSession session = new MultiplayerSession();
        session.username = "tester";
        Multiplayer.session = session;

        AtomicReference<byte[]> received = new AtomicReference<>();
        SaveTransfer.applyOverride = received::set;

        java.net.Socket socket = new java.net.Socket("127.0.0.1", settings.port);
        TcpConnection conn = new TcpConnection(socket, MainThread::enqueue);
        conn.username = session.username;
        session.connection = conn;
        conn.changeState(ConnectionStateEnum.CLIENT_JOINING,
                new ClientJoiningState(conn, session, ""));
        conn.startReading();

        waitFor(() -> received.get() != null, 5000, "world data received");
        check(java.util.Arrays.equals(received.get(), fakeSave),
                "world blob survived fragmentation (" + fakeSave.length + "b)");
        check(session.playerId >= 0, "player id assigned: " + session.playerId);
        check(syxmp.client.TickController.timer == 1234, "clock set to savedGameTimer");

        // The loading state set simulating mode; without a game we finish it manually
        syxmp.client.TickController.simulatingTarget = -1;
        conn.send(syxmp.common.Packets.CLIENT_WORLD_READY);
        waitFor(() -> conn.stateEnum() == ConnectionStateEnum.CLIENT_PLAYING
                && !session.players.isEmpty(), 5000, "reached playing state + player list");

        // Command round-trip: client sends, server stamps + echoes.
        // (pauseOnJoin means a PAUSE_ALL from the server may precede ours.)
        ClientCommands.requestSpeed(5.0);
        waitFor(() -> session.cmds.stream()
                .anyMatch(c -> c.type == syxmp.common.CommandType.GLOBAL_TIME_SPEED),
                5000, "command echoed back");
        syxmp.common.ScheduledCommand cmd = session.cmds.stream()
                .filter(c -> c.type == syxmp.common.CommandType.GLOBAL_TIME_SPEED)
                .findFirst().orElseThrow();
        check(cmd.playerId == session.playerId, "command stamped with our playerId");
        check(cmd.ticks >= 1234, "command scheduled at/after server clock: " + cmd.ticks);

        // Time control flows and the interlock exposes the window
        int before = syxmp.client.TickController.tickUntil;
        waitFor(() -> syxmp.client.TickController.tickUntil > before, 5000,
                "tickUntil advances (server clock running)");

        server.stop("test done");
        transport.close();
    }

    static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            MainThread.drain();
            if (cond.getAsBoolean()) {
                check(true, what);
                return;
            }
            Thread.sleep(5);
        }
        check(false, what + " (timeout)");
    }
}
