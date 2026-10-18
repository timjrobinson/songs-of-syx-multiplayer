package syxmp.agent;

import java.lang.instrument.Instrumentation;

/**
 * The Java agent — our stand-in for Harmony. Loaded via
 * -javaagent:<path-to-SyxMultiplayer.jar> in the launcher's JVM_ARGS2
 * (init/Main.java passes those to the game process; AgentInstaller can add
 * the line automatically). premain runs before the game's main, so the
 * transformer sees every game class as it loads — no retransform needed.
 */
public final class SyxAgent {

    public static volatile Instrumentation instrumentation;

    private SyxAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        inst.addTransformer(new MpTransformer(PatchTable.build()), true);
        if (syxmp.dev.VerbTrace.ENABLED) {
            inst.addTransformer(new TraceTransformer(), true);
            System.out.println("[SyxMP] verb tracing enabled");
        }
        SyncTemplates.registerAt(PatchTable.HOOK_VIEW_UPDATE, (self, a) -> {
            syxmp.dev.VerbTrace.uiContext = true;
            if (syxmp.dev.VerbTrace.updaterThread == null)
                syxmp.dev.VerbTrace.updaterThread = Thread.currentThread();
            return false;
        });
        SyncTemplates.registerAt(PatchTable.HOOK_VIEW_RENDER, (self, a) -> {
            syxmp.dev.VerbTrace.uiContext = false;
            return false;
        });
        // The menu hook needs a handler before the game reaches the menu;
        // DevAuto is safe to load here (no game classes in its class init).
        SyncTemplates.registerAt(PatchTable.HOOK_MENU_UPDATE, (self, a) -> {
            syxmp.client.MenuDriver.menuTick(self);
            return false;
        });
        SyncTemplates.registerAt(PatchTable.HOOK_CORE_ANNIHILATE, (self, a) -> {
            new Throwable("[SyxMP] CORE.annihilate() called from").printStackTrace(System.out);
            return false;
        });
        SyncTemplates.registerAt(PatchTable.HOOK_CORE_ANNIHILATE_T, (self, a) -> {
            new Throwable("[SyxMP] CORE.annihilate(Throwable) called from").printStackTrace(System.out);
            if (a[0] instanceof Throwable t)
                t.printStackTrace(System.out);
            return false;
        });
        SyncTemplates.registerAt(PatchTable.HOOK_GAME_LOAD, (self, a) -> {
            syxmp.client.TickController.seedForLoad();
            return false;
        });
        SyncTemplates.registerAt(PatchTable.HOOK_LOAD_SCREEN, (self, a) -> {
            syxmp.client.TickController.seedAfterCreate();
            return false;
        });
        if ("1".equals(System.getProperty("syxmp.enttrace")))
            SyncTemplates.registerAt(PatchTable.HOOK_ENT_UPDATE, (self, a) -> {
                syxmp.dev.EntTrace.onUpdate(self, a);
                return false;
            });
        if (syxmp.dev.RndTrace.ENABLED)
            SyncTemplates.registerAt(PatchTable.HOOK_RND_DRAW, (self, a) -> {
                syxmp.dev.RndTrace.onDraw();
                return false;
            });
        Runtime.getRuntime().addShutdownHook(new Thread(SyxAgent::dumpShutdown, "SyxMP-ShutdownDump"));
        System.out.println("[SyxMP] agent installed");
    }

    /** Diagnostics: on JVM shutdown, print where the engine threads are. */
    private static void dumpShutdown() {
        StringBuilder sb = new StringBuilder("[SyxMP] JVM shutting down; thread stacks:\n");
        for (var e : Thread.getAllStackTraces().entrySet()) {
            String n = e.getKey().getName();
            if (!(n.equals("main") || n.equals("updater") || n.startsWith("SyxMP")))
                continue;
            sb.append("  thread ").append(n).append(" state ").append(e.getKey().getState()).append('\n');
            for (StackTraceElement el : e.getValue())
                sb.append("    at ").append(el).append('\n');
        }
        System.out.println(sb);
        System.out.flush();
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    public static boolean isActive() {
        return instrumentation != null;
    }
}
