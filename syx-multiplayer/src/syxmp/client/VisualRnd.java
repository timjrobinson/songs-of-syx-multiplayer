package syxmp.client;

import java.util.Random;

/**
 * A separate random stream for purely cosmetic code that vanilla runs inside
 * the simulation tick (sprite animators, particle jitter). Redirecting such
 * call sites here (PatchTable CallRedirect of RND.* within the listed
 * classes) keeps them from consuming the shared, synced RND stream, so
 * unsaved visual state can never desync the simulation. Only used for
 * classes whose RND use provably has no simulation effect.
 */
public final class VisualRnd {

    private static final Random rnd = new Random();

    private VisualRnd() {
    }

    public static int rInt() {
        return rnd.nextInt();
    }

    public static int rInt(int max) {
        return max <= 0 ? 0 : rnd.nextInt(max);
    }

    public static int rInt0(int dist) {
        return rnd.nextInt(2 * dist + 1) - dist;
    }

    public static boolean rBoolean() {
        return rnd.nextBoolean();
    }

    public static float rFloat() {
        return rnd.nextFloat();
    }

    public static float rFloat(double d) {
        return (float) (rnd.nextFloat() * d);
    }

    public static float rFloat1(double d) {
        return (float) (1.0 - rnd.nextFloat() * d);
    }

    public static float rFloat0(double d) {
        return (float) ((rnd.nextFloat() * 2 - 1) * d);
    }

    public static boolean oneIn(int what) {
        return what <= 1 || rnd.nextInt(what) == 0;
    }

    public static boolean oneIn(double what) {
        return what <= 1 || rnd.nextDouble() * what < 1;
    }

    public static boolean oneInD(double what) {
        return oneIn(what);
    }

    public static short rShort(int upperBound) {
        return (short) rInt(upperBound);
    }

    public static short rShort() {
        return (short) rnd.nextInt(Short.MAX_VALUE);
    }

    public static long rLong() {
        return rnd.nextLong();
    }

    public static float rExpo() {
        return (float) -Math.log(1 - rnd.nextFloat());
    }

    /** Replacement for LIST.rnd() in cosmetic callers. */
    public static Object listRnd(snake2d.util.sets.LIST<?> list) {
        return list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
    }

    public static double rSign() {
        return rnd.nextBoolean() ? 1 : -1;
    }
}
