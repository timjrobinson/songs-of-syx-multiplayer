package syxmp.client;

import java.util.Random;

/**
 * A fixed-seed random stream for per-process lookup tables that vanilla
 * builds from RND in static initializers (e.g. TileUpdater's 64x64 update
 * order shuffle). Such tables are built once per JVM at an arbitrary RND
 * state, so without this every client would iterate tiles in a different
 * order. Single-player is unaffected beyond the table being the same every
 * run.
 */
public final class DetRnd {

    private static final Random rnd = new Random(0x5EED5EEDL);

    private DetRnd() {
    }

    public static int rInt(int max) {
        return max <= 0 ? 0 : rnd.nextInt(max);
    }

    public static int rInt() {
        return rnd.nextInt();
    }

    public static float rFloat() {
        return rnd.nextFloat();
    }

    public static boolean rBoolean() {
        return rnd.nextBoolean();
    }
}
