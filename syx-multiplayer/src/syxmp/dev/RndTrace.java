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
        if (!ENABLED || done)
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
            loadPhase = true;
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
    public static void onDraw() {
        if (done || out == null || !(Multiplayer.ticking || loadPhase))
            return;
        drawsInStep++;
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder(160);
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
        try {
            out.write(sb.toString());
        } catch (IOException e) {
            done = true;
        }
    }
}
