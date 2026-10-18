package syxmp.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * RND replacement for static initializers. A class's {@code <clinit>} runs
 * once per process, at whatever moment the class is first touched, so its
 * draws would both shift the shared stream unpredictably and produce
 * per-process lookup tables. Every static initializer instead gets its own
 * stream seeded from the class name: identical tables on every client, and
 * nothing consumed from RND. (The agent redirects RND.* calls inside every
 * game class's <clinit> here.)
 */
public final class ClinitRnd {

    private static final Map<String, Random> streams = new HashMap<>();

    private ClinitRnd() {
    }

    private static synchronized Random stream() {
        String owner = "?";
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (!c.startsWith("syxmp.")) {
                owner = c;
                break;
            }
        }
        Random r = streams.get(owner);
        if (r == null) {
            r = new Random(owner.hashCode() * 0x9E3779B97F4A7C15L);
            streams.put(owner, r);
        }
        return r;
    }

    public static int rInt() {
        return stream().nextInt();
    }

    public static int rInt(int max) {
        return max <= 0 ? 0 : stream().nextInt(max);
    }

    public static int rInt0(int dist) {
        return dist == 0 ? 0 : -dist + stream().nextInt(dist * 2 + 1);
    }

    public static boolean rBoolean() {
        return stream().nextBoolean();
    }

    public static float rFloat() {
        return stream().nextFloat();
    }

    public static float rFloatP(float exponent) {
        float res = rFloat();
        while (exponent > 1) {
            res *= res;
            exponent--;
        }
        return res;
    }

    public static float rFloat(double d) {
        return (float) (stream().nextFloat() * d);
    }

    public static float rFloat1(double d) {
        return (float) (1.0 - stream().nextFloat() * d);
    }

    public static float rFloat0(double d) {
        return (float) ((stream().nextFloat() * 2 - 1) * d);
    }

    public static boolean oneIn(int what) {
        return what <= 1 || stream().nextInt(what) == 0;
    }

    public static boolean oneIn(double what) {
        return what <= 1 || stream().nextDouble() * what < 1;
    }

    public static boolean oneInD(double what) {
        return oneIn(what);
    }

    public static short rShort(int upperBound) {
        return (short) rInt(upperBound);
    }

    public static short rShort() {
        return (short) stream().nextInt(Short.MAX_VALUE);
    }

    public static long rLong() {
        return stream().nextLong();
    }

    public static float rExpo() {
        return (float) -Math.log(1 - stream().nextFloat());
    }

    public static double rSign() {
        return stream().nextBoolean() ? 1 : -1;
    }
}
