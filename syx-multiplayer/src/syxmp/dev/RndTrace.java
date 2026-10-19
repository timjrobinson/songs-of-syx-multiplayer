package syxmp.dev;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import syxmp.client.Multiplayer;

/**
 * Desync pinpointing tool (dev only, -Dsyxmp.rndtrace=1): logs the game-code
 * call site of every RND draw made inside simulation sub-steps, grouped per
 * sub-step. Run it on two clients and diff the files: the first differing
 * line names the nondeterministic code. The RW-MP equivalent is the
 * "desync traces" stack collection.
 *
 * Output: <logs>/syxmp-rnd.txt (first SUBSTEPS sub-steps after the session
 * reaches lockstep, to keep the file bounded).
 */
public final class RndTrace {

    public static final boolean ENABLED = "1".equals(System.getProperty("syxmp.rndtrace"));
    private static final int SUBSTEPS = Integer.getInteger("syxmp.rndtrace.steps", 300);

    private static BufferedWriter out;
    private static int steps;
    private static int drawsInStep;
    private static boolean done;

    /** Rolling window of the most recent sub-steps (dumped on desync, like RW's desync traces). */
    private static final int RING = Integer.getInteger("syxmp.rndtrace.ring", 80);
    private static final java.util.ArrayDeque<StringBuilder> ring = new java.util.ArrayDeque<>();
    private static StringBuilder ringCur;

    private RndTrace() {
    }

    /** A join-point reload: start a fresh, aligned trace on every client. */
    public static synchronized void restart() {
        if (!ENABLED)
            return;
        try {
            if (out != null)
                out.close();
        } catch (IOException e) {
            // ignore
        }
        out = null;
        steps = 0;
        done = false;
    }

    /** Called from TickController at the start of each sub-step. */
    public static void beginSubStep(int updateI, int timer) {
        if (!ENABLED)
            return;
        synchronized (RndTrace.class) {
            ringCur = new StringBuilder(4096);
            ringCur.append("## substep updateI=").append(updateI).append(" timer=").append(timer).append('\n');
            ring.addLast(ringCur);
            while (ring.size() > RING)
                ring.pollFirst();
        }
        if (done)
            return;
        try {
            if (out == null) {
                Path dir = Path.of(System.getProperty("user.home"), ".local", "share", "songsofsyx", "logs");
                Files.createDirectories(dir);
                out = Files.newBufferedWriter(dir.resolve("syxmp-rnd.txt"), StandardCharsets.UTF_8);
            }
            if (++steps > SUBSTEPS) {
                out.write("## trace limit reached\n");
                out.flush();
                out.close();
                done = true;
                return;
            }
            out.write("## substep updateI=" + updateI + " timer=" + timer + "\n");
            drawsInStep = 0;
        } catch (IOException e) {
            done = true;
        }
    }

    public static void endSubStep() {
        if (!ENABLED || done || out == null)
            return;
        try {
            out.write("## draws=" + drawsInStep + "\n");
            out.flush();
        } catch (IOException e) {
            done = true;
        }
    }

    /** True from the MP load seed until the new game's first frame. */
    public static volatile boolean loadPhase;

    /** Called from TickController.seedForLoad: trace draws made while loading. */
    public static synchronized void beginLoad(int timer) {
        if (!ENABLED)
            return;
        restart();
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".local", "share", "songsofsyx", "logs");
            Files.createDirectories(dir);
            out = Files.newBufferedWriter(dir.resolve("syxmp-rnd.txt"), StandardCharsets.UTF_8);
            out.write("## load timer=" + timer + "\n");
            drawsInStep = 0;
            loadPhase = true;
        } catch (IOException e) {
            done = true;
        }
    }

    public static synchronized void mark(String line) {
        if (!ENABLED)
            return;
        // Entity/state marks go into the rolling ring too, so a desync dump
        // shows the first diverging entity before the first diverging draw.
        if (Multiplayer.ticking && ringCur != null && ringCur.length() < 2_000_000)
            ringCur.append(line).append('\n');
        if (out == null)
            return;
        try {
            out.write(line + "\n");
        } catch (IOException e) {
            done = true;
        }
    }

    public static synchronized void endLoad() {
        if (!ENABLED || out == null)
            return;
        loadPhase = false;
        try {
            out.write("## load end draws=" + drawsInStep + "\n");
            out.flush();
        } catch (IOException e) {
            done = true;
        }
        drawsInStep = 0;
    }

    /** Entry hook handler for every RND.r*() method. */
    public static synchronized void onDraw() {
        if (!(Multiplayer.ticking || loadPhase))
            return;
        boolean toFile = !done && out != null;
        boolean toRing = Multiplayer.ticking && ringCur != null;
        if (!toFile && !toRing)
            return;
        drawsInStep++;
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder(160);
        Thread t = Thread.currentThread();
        if (!t.getName().equals("updater"))
            sb.append("[T:").append(t.getName()).append("] ");
        int shown = 0;
        for (int i = 1; i < st.length && shown < 4; i++) {
            String c = st[i].getClassName();
            if (c.startsWith("syxmp.") || c.startsWith("snake2d.util.rnd."))
                continue;
            if (shown > 0)
                sb.append(" < ");
            sb.append(c).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
            shown++;
        }
        sb.append('\n');
        if (toRing && ringCur.length() < 2_000_000)
            ringCur.append(sb);
        if (toFile) {
            try {
                out.write(sb.toString());
            } catch (IOException e) {
                done = true;
            }
        }
    }

    /** Write the rolling window to <logs>/syxmp-rnd-desync.txt (both clients call this on a desync). */
    private static long lastDump;

    public static synchronized void dumpRing(String why) {
        if (!ENABLED)
            return;
        // Keep the first dump of an incident: the host's later dump at the player's disconnect
        // would overwrite the one taken when the desync was reported, whose ring still covers it.
        if (System.currentTimeMillis() - lastDump < 60_000)
            return;
        lastDump = System.currentTimeMillis();
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".local", "share", "songsofsyx", "logs");
            Files.createDirectories(dir);
            try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("syxmp-rnd-desync.txt"), StandardCharsets.UTF_8)) {
                w.write("## dump: " + why + "\n");
                for (StringBuilder s : ring)
                    w.write(s.toString());
            }
            syxmp.util.MpLog.info("desync trace window written (" + ring.size() + " sub-steps)");
        } catch (IOException e) {
            syxmp.util.MpLog.err("could not write desync trace", e);
        }
    }
}
