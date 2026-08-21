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
        System.out.println("[SyxMP] agent installed");
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    public static boolean isActive() {
        return instrumentation != null;
    }
}
