package syxmp.dev;

import java.lang.reflect.Field;

import settlement.entity.ENTITY;
import syxmp.client.Multiplayer;
import syxmp.util.ReflectUtil;

/**
 * Dev-only (-Dsyxmp.enttrace=1, with -Dsyxmp.rndtrace=1): logs every entity
 * update inside sub-steps into the RND trace (id, ds, position, animal state)
 * so a transient-state divergence shows up BEFORE the first differing RND
 * draw when the two clients' files are diffed.
 */
public final class EntTrace {

    public static final boolean ENABLED = "1".equals(System.getProperty("syxmp.enttrace"));
    private static Field fState, fStateTimer;
    private static final StringBuilder sb = new StringBuilder(96);

    private EntTrace() {
    }

    private static boolean fetchErrLogged;

    /** IndustryUtil.calcProductionRate(base, h, rate, bonus, ins): every booster's value for the worker. */
    public static void onProdRate(Object[] args) {
        if (!Multiplayer.ticking)
            return;
        try {
            double base = (Double) args[0];
            settlement.entity.humanoid.Humanoid h = (settlement.entity.humanoid.Humanoid) args[1];
            settlement.room.industry.module.IndustryRate rate = (settlement.room.industry.module.IndustryRate) args[2];
            game.boosting.Boostable bonus = (game.boosting.Boostable) args[3];
            settlement.room.main.RoomInstance ins = (settlement.room.main.RoomInstance) args[4];
            StringBuilder sb = new StringBuilder(256);
            sb.append("PR ").append(h.id()).append(" room=").append(ins.index()).append(" base=").append((float) base)
              .append(" roomBonus=").append((float) settlement.room.industry.module.IndustryUtil.roomBonus(ins, rate));
            if (bonus != null) {
                sb.append(" total=").append(bonus.get(h.indu())).append(" [");
                snake2d.util.sets.LIST<game.boosting.Booster> all = bonus.all();
                for (int i = 0; i < all.size(); i++) {
                    game.boosting.Booster b = all.get(i);
                    String name;
                    try {
                        Object info = ReflectUtil.field(game.boosting.BoosterAbs.class, "info").get(b);
                        name = String.valueOf(ReflectUtil.field(info.getClass(), "name").get(info));
                    } catch (RuntimeException | ReflectiveOperationException e) {
                        name = b.getClass().getSimpleName();
                    }
                    sb.append(name).append('=').append((float) b.get(h.indu())).append(i + 1 < all.size() ? " " : "");
                }
                sb.append(']');
            }
            RndTrace.mark(sb.toString());
        } catch (RuntimeException ex) {
            if (!fetchErrLogged) {
                fetchErrLogged = true;
                syxmp.util.MpLog.err("rate trace failed", ex);
            }
        }
    }
    private static Field fCoo, fData, fInsi;

    /** RoomResDeposit.jobResourceBitToFetch() / jobReserve(res): the deposit tile's cached data. */
    public static void onDeposit(Object self, Object[] args) {
        if (!Multiplayer.ticking)
            return;
        try {
            settlement.room.main.job.RoomResDeposit d = (settlement.room.main.job.RoomResDeposit) self;
            if (fCoo == null) {
                fCoo = ReflectUtil.field(settlement.room.main.job.RoomResDeposit.class, "coo");
                fData = ReflectUtil.field(settlement.room.main.job.RoomResDeposit.class, "data");
                fInsi = ReflectUtil.field(settlement.room.main.job.RoomResDeposit.class, "insi");
            }
            Object coo = fCoo.get(d);
            int data = fData.getInt(d);
            Object insi = fInsi.get(d);
            StringBuilder sb = new StringBuilder(96);
            sb.append(args.length == 0 ? "DB " : "DR ").append(coo).append(" room=")
              .append(insi instanceof settlement.room.main.Room r ? r.index() : -1)
              .append(" data=").append(Integer.toHexString(data)).append(" am=");
            for (int i = 0; i < d.resAm(); i++)
                sb.append(d.amount(i)).append(i + 1 < d.resAm() ? "," : "");
            if (insi != null) {
                try {
                    sb.append(" hasStorage=").append(ReflectUtil.field(insi.getClass(), "hasStorage").get(insi));
                } catch (RuntimeException e) {
                    // not a workshop
                }
            }
            if (args.length == 1)
                sb.append(" res=").append(args[0] == null ? "null" : ((init.resources.RESOURCE) args[0]).key);
            RndTrace.mark(sb.toString());
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (!fetchErrLogged) {
                fetchErrLogged = true;
                syxmp.util.MpLog.err("deposit trace failed", ex);
            }
        }
    }

    /** RoomConsumption.shouldFecth(res, ins, emp): every term of the fetch-or-work decision. */
    public static void onFetchCheck(Object self, Object[] args) {
        if (!Multiplayer.ticking)
            return;
        try {
            settlement.room.industry.module.consumption.RoomConsumption c =
                    (settlement.room.industry.module.consumption.RoomConsumption) self;
            settlement.room.industry.module.IndustryResource r = (settlement.room.industry.module.IndustryResource) args[0];
            settlement.room.industry.module.ROOM_IDATA_INSTANCE ins = (settlement.room.industry.module.ROOM_IDATA_INSTANCE) args[1];
            settlement.room.main.RoomInstance emp = (settlement.room.main.RoomInstance) args[2];
            settlement.misc.job.JOB_MANAGER jm = ins.getWork();
            String search = "?";
            try {
                Object bits = ReflectUtil.field(jm.getClass(), "resSearch").get(jm);
                Object all = ReflectUtil.field(jm.getClass(), "hasSearchedAll").get(jm);
                search = jm.resourceShouldSearch(r.resource) + "/" + bits + "/" + all;
            } catch (RuntimeException | ReflectiveOperationException e) {
                search = String.valueOf(jm.resourceShouldSearch(r.resource));
            }
            RndTrace.mark("SF " + emp.index() + " " + r.resource.key + " en=" + c.enabled(r, ins) + " search=" + search
                    + " am=" + c.stored(r).get(ins) + " res=" + c.reseved(r).get(ins)
                    + " employed=" + emp.employees().employed() + " rate=" + r.rate);
        } catch (RuntimeException ex) {
            if (!fetchErrLogged) {
                fetchErrLogged = true;
                syxmp.util.MpLog.err("fetch trace failed", ex);
            }
        }
    }

    /** SubWork$*.activate(a, d, job): the job and its duration, the inputs of the cycle counter. */
    public static void onWorkActivate(Object self, Object[] args) {
        if (!Multiplayer.ticking)
            return;
        try {
            settlement.entity.humanoid.Humanoid a = (settlement.entity.humanoid.Humanoid) args[0];
            settlement.misc.job.SETT_JOB j = (settlement.misc.job.SETT_JOB) args[2];
            RndTrace.mark("WA " + a.id() + " " + self.getClass().getName().replace("settlement.entity.humanoid.ai.", "")
                    + " job=" + (j == null ? "null" : j.getClass().getName().replace("settlement.", ""))
                    + " time=" + (j == null ? "-" : j.jobPerformTime(a)));
        } catch (RuntimeException ex) {
            // diagnostics only
        }
    }

    /** SubWork$*.resume(a, d): the cycle counter and whether the job is still there. */
    public static void onWorkResume(Object self, Object[] args) {
        if (!Multiplayer.ticking)
            return;
        try {
            settlement.entity.humanoid.Humanoid a = (settlement.entity.humanoid.Humanoid) args[0];
            settlement.entity.humanoid.ai.main.AIManager d = (settlement.entity.humanoid.ai.main.AIManager) args[1];
            Object job = null;
            for (Class<?> c = self.getClass(); c != null && job == null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod("getJob",
                            settlement.entity.humanoid.Humanoid.class, settlement.entity.humanoid.ai.main.AIManager.class);
                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers()))
                        continue;
                    m.setAccessible(true);
                    job = m.invoke(self, a, d);
                    if (job == null)
                        job = "null";
                } catch (NoSuchMethodException e) {
                    // look up
                }
            }
            RndTrace.mark("WR " + a.id() + " sub=" + d.subByte + " job=" + (job == null ? "?" : job instanceof String ? job
                    : job.getClass().getName().replace("settlement.", "")));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // diagnostics only
        }
    }

    /** Entry hook handler for Humanoid.update / Animal.update. */
    public static void onUpdate(Object self, Object[] args) {
        if (!Multiplayer.ticking || !(self instanceof ENTITY e))
            return;
        try {
            sb.setLength(0);
            sb.append("E ").append(e.id()).append(' ').append(args[0])
              .append(" @").append(e.body().cX()).append(',').append(e.body().cY());
            if (self instanceof settlement.entity.humanoid.Humanoid h) {
                // velocity, AI state and current path node: the inputs of the next move
                sb.append(" v=").append((float) h.speed.x()).append(',').append((float) h.speed.y());
                if (h.ai() instanceof settlement.entity.humanoid.ai.main.AIManager m) {
                    Object st = m.state();
                    // full names: the states are anonymous classes (simple name is empty)
                    sb.append(" s=").append(st == null ? "-" : st.getClass().getName().replace("settlement.entity.humanoid.ai.", ""));
                    Object mod = settlement.entity.humanoid.ai.main.AIModules.current(m);
                    sb.append(" m=").append(mod == null ? "-" : mod.getClass().getName().replace("settlement.entity.humanoid.ai.", ""));
                    sb.append(" b=").append(m.planByte1).append(',').append(m.planByte2).append(',').append(m.planByte3)
                      .append(',').append(m.planByte4).append(',').append(m.subPathByte).append(',').append(m.subPathByte2);
                    sb.append(" t=").append(m.stateTimer);
                    settlement.path.path.SPath p = m.path();
                    sb.append(" p=").append(p.getCurrentI()).append('/').append(p.length())
                      .append(p.isSuccessful() ? "" : "!").append('@').append(p.x()).append(',').append(p.y());
                }
            }
            if (self instanceof settlement.entity.animal.Animal) {
                if (fState == null) {
                    fState = ReflectUtil.field(self.getClass(), "state");
                    fStateTimer = ReflectUtil.field(self.getClass(), "stateTimer");
                }
                sb.append(" st=").append(fState.get(self)).append(" t=").append(fStateTimer.get(self));
            }
            RndTrace.mark(sb.toString());
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // diagnostics only
        }
    }
}
