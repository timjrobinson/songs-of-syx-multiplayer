package syxmp.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Player-verb discovery tool (dev only, enabled with -Dsyxmp.trace=1).
 *
 * TraceTransformer instruments the entry of every candidate simulation
 * method; this class logs the first time each one is called from UI context
 * (the part of the frame outside the sim tick and outside rendering, on the
 * updater thread) together with the game-code call chain. Clicking around
 * the game then produces the list of UI→sim mutation points that need to be
 * synced — the empirical input for SyxSync, like RW-MP's desync reports.
 *
 * Output: <logs>/syxmp-verbs.txt, one line per unique signature.
 */
public final class VerbTrace {

    public static final boolean ENABLED = "1".equals(System.getProperty("syxmp.trace"));

    /** Read by injected code: cheap global switch. */
    public static volatile boolean on = ENABLED;
    /** True between VIEW.update entry and the sim tick / render. */
    public static volatile boolean uiContext;
    public static volatile Thread updaterThread;

    private static final List<String> sigs = new ArrayList<>();
    private static final BitSet seen = new BitSet();
    private static Path out;
    private static boolean reentrant;

    private VerbTrace() {
    }

    /** Transform-time registration; returns the id baked into the bytecode. */
    public static synchronized int register(String sig) {
        sigs.add(sig);
        return sigs.size() - 1;
    }

    /** Called from injected code at method entry. Must be cheap and never throw. */
    public static void enter(int id) {
        if (!on || !uiContext || reentrant)
            return;
        Thread t = Thread.currentThread();
        if (updaterThread != null && t != updaterThread)
            return;
        synchronized (VerbTrace.class) {
            if (seen.get(id))
                return;
            seen.set(id);
            reentrant = true;
            try {
                record(id);
            } catch (Throwable e) {
                System.err.println("[SyxMP:trace] " + e);
            } finally {
                reentrant = false;
            }
        }
    }

    private static void record(int id) throws IOException {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder(256);
        sb.append(sigs.get(id)).append("    <-");
        int shown = 0;
        for (int i = 2; i < st.length && shown < 7; i++) {
            String c = st[i].getClassName();
            if (c.startsWith("syxmp.") || c.startsWith("java."))
                continue;
            sb.append(' ').append(c).append('.').append(st[i].getMethodName())
              .append(':').append(st[i].getLineNumber());
            shown++;
            if (c.startsWith("snake2d.") || c.startsWith("view.main."))
                break;
        }
        sb.append('\n');
        System.out.print("[SyxMP:verb] " + sb);
        if (out == null) {
            String home = System.getProperty("user.home");
            Path dir = Path.of(home, ".local", "share", "songsofsyx", "logs");
            Files.createDirectories(dir);
            out = dir.resolve("syxmp-verbs.txt");
        }
        Files.writeString(out, sb, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ---- predicate shared with the transformer ------------------------------

    private static final String[] CLASS_PREFIXES = {
        "settlement/", "game/", "world/", "init/race/", "init/resources/", "init/tech/",
        "init/religion/", "init/boostable/", "init/type/",
    };
    private static final String[] CLASS_EXCLUDES = {
        "game/battle/thread/", "settlement/path/", "settlement/thing/", "settlement/tilemap/",
        "settlement/weather/", "settlement/env/", "game/time/", "game/audio",
    };
    private static final String[] NAME_EXCLUDES = {
        "get", "is", "has", "render", "update", "hover", "can", "should", "to", "equals",
        "hashCode", "compareTo", "iterator", "index", "name", "desc", "info", "size", "width",
        "height", "body", "color", "sprite", "coo", "next", "prev", "min", "max", "value",
        "clear", "save", "load", "init", "dispose", "create", "make", "find", "count", "all",
        "random", "rnd", "tick", "after", "before", "check", "calc", "collect", "read",
        "write", "print", "log", "tryGet", "fetch", "lookup", "contains", "cost", "total",
        "amount", "rate", "ratio", "tile", "tx", "ty", "x", "y", "cX", "cY", "key",
    };

    public static boolean classCandidate(String internalName) {
        boolean ok = false;
        for (String p : CLASS_PREFIXES)
            if (internalName.startsWith(p)) {
                ok = true;
                break;
            }
        if (!ok)
            return false;
        for (String p : CLASS_EXCLUDES)
            if (internalName.startsWith(p))
                return false;
        return !internalName.contains("Render") && !internalName.contains("/ui/")
                && !internalName.contains("Sprite") && !internalName.contains("Tally");
    }

    public static boolean methodCandidate(String name, String desc, boolean zeroArgs,
            boolean voidReturn) {
        if (name.startsWith("<") || name.startsWith("lambda$"))
            return false;
        if (zeroArgs && !voidReturn)
            return false;
        for (String p : NAME_EXCLUDES) {
            if (name.equals(p))
                return false;
            if (name.startsWith(p) && name.length() > p.length()
                    && Character.isUpperCase(name.charAt(p.length())))
                return false;
        }
        return true;
    }
}
