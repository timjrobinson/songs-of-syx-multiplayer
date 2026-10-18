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
        testTransformerGameSpeed();
        testTransformerViewRedirect();
        testFieldAndUiRedirects();
        testJoinFlowOverTcp();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
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
