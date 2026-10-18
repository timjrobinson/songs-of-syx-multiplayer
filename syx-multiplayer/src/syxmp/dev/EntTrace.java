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

    /** Entry hook handler for Humanoid.update / Animal.update. */
    public static void onUpdate(Object self, Object[] args) {
        if (!Multiplayer.ticking || !(self instanceof ENTITY e))
            return;
        try {
            sb.setLength(0);
            sb.append("E ").append(e.id()).append(' ').append(args[0])
              .append(" @").append(e.body().cX()).append(',').append(e.body().cY());
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
