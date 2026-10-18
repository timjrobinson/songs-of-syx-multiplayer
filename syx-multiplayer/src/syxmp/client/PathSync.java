package syxmp.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import settlement.main.SETT;
import settlement.path.thread.FinderThread;
import snake2d.PathUtilOnline;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;

/**
 * Long-range pathfinding, synchronous. Vanilla offloads long paths to a
 * worker thread (FinderThread) and the walker consumes the result when it
 * happens to be ready — machine-timing dependent, so two clients route
 * differently. Suppressing the thread was wrong too: the synchronous fallback
 * in SPath is short-range only, so citizens with far destinations never got a
 * path and paced on the spot (live report). In multiplayer the worker's job
 * runs right here on the game thread, with the same finder classes, and the
 * result is marked processed exactly like the worker would: same semantics,
 * deterministic.
 */
public final class PathSync {

    private static Object pather;         // snake2d.PathUtilOnline
    private static Object finder;         // settlement.path.thread.SPathFinderThread (package-private)
    private static Object boundComps;
    private static Field fStatus, fSx, fSy, fDx, fDy, fFull;
    private static Method mDoJob;
    private static boolean broken;

    private PathSync() {
    }

    /**
     * Entry hook of FinderThread.prep(SPath, ThreadPathJob, sx, sy, dx, dy, full).
     * @return true when handled here (vanilla body skipped).
     */
    public static boolean prep(Object finderThread, Object[] args) {
        if (!Multiplayer.active() || broken)
            return false;
        try {
            Object spath = args[0];
            Object job = args[1];
            int sx = (Integer) args[2], sy = (Integer) args[3], dx = (Integer) args[4], dy = (Integer) args[5];
            boolean full = (Boolean) args[6];
            FinderThread.ThreadPath t = (FinderThread.ThreadPath) ReflectUtil.field(spath.getClass(), "thread").get(spath);
            bind(job.getClass());
            if ((Byte) fStatus.get(t) == 1)
                return true;
            fStatus.set(t, (byte) 1);
            fSx.set(t, (short) sx);
            fSy.set(t, (short) sy);
            fDx.set(t, (short) dx);
            fDy.set(t, (short) dy);
            fFull.set(t, full);
            boolean ok = (Boolean) mDoJob.invoke(job, pather, finder, t);
            fStatus.set(t, (byte) (ok ? 3 : 2));
            if (syxmp.dev.RndTrace.ENABLED)
                syxmp.dev.RndTrace.mark(describe(t, sx, sy, dx, dy, full, ok));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            MpLog.err("synchronous pathfinding failed; falling back to vanilla thread", e);
            return false;
        }
    }

    /** Dev trace: request + result path (length, destination, node hash). */
    private static String describe(FinderThread.ThreadPath t, int sx, int sy, int dx, int dy, boolean full, boolean ok) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("P ").append(sx).append(',').append(sy).append("->").append(dx).append(',').append(dy)
          .append(full ? " full" : "").append(ok ? " ok" : " FAIL");
        try {
            snake2d.PathGame.PathFancy path = t.path;
            int old = path.getCurrentI();
            path.setCurrentI(0);
            int h = 17, n = 0;
            while (path.hasNext()) {
                h = h * 31 + path.x() * 7919 + path.y();
                n++;
                path.setNext();
            }
            path.setCurrentI(old);
            sb.append(" len=").append(path.length()).append(" nodes=").append(n).append(" dest=")
              .append(t.destX).append(',').append(t.destY).append(" h=").append(Integer.toHexString(h));
        } catch (RuntimeException e) {
            sb.append(" ?");
        }
        return sb.toString();
    }

    private static void bind(Class<?> jobClass) throws ReflectiveOperationException {
        Object comps = SETT.PATH().comps;
        if (finder == null || boundComps != comps) {
            pather = new PathUtilOnline(SETT.TWIDTH);
            Class<?> finderCls = Class.forName("settlement.path.thread.SPathFinderThread");
            Constructor<?> c = finderCls.getDeclaredConstructor(
                    Class.forName("settlement.path.components.SCOMPONENTS"), PathUtilOnline.class, int.class);
            c.setAccessible(true);
            finder = c.newInstance(comps, pather, 13); // same arguments as FinderThread.Worker
            boundComps = comps;
            Class<?> tp = FinderThread.ThreadPath.class;
            fStatus = ReflectUtil.field(tp, "status");
            fSx = ReflectUtil.field(tp, "sx");
            fSy = ReflectUtil.field(tp, "sy");
            fDx = ReflectUtil.field(tp, "dx");
            fDy = ReflectUtil.field(tp, "dy");
            fFull = ReflectUtil.field(tp, "full");
            MpLog.info("pathfinder bound synchronously");
        }
        if (mDoJob == null || !mDoJob.getDeclaringClass().isAssignableFrom(jobClass)) {
            Class<?> jobIface = Class.forName("settlement.path.thread.FinderThread$ThreadPathJob");
            mDoJob = jobIface.getDeclaredMethod("doJob", PathUtilOnline.class,
                    Class.forName("settlement.path.thread.SPathFinderThread"), FinderThread.ThreadPath.class);
            mDoJob.setAccessible(true);
        }
    }
}
