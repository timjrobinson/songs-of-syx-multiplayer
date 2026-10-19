package syxmp.sync;

import snake2d.util.sets.ArrayInt;
import util.data.DataO;
import util.data.INT_O.INT_OE;
import util.keymap.MAPPED;
import util.keymap.RMapInt;

/**
 * Targets of the UI call-site redirects in PatchTable.UI_GENERIC_SETTER_SITES:
 * generic container setters (used all over the sim, so never hooked at
 * entry) that specific UI screens use to write player settings. The call
 * becomes a SYNC_AUTO command keyed by the receiver's runtime class, or runs
 * vanilla when not in interface context / not addressable.
 */
public final class UiRedirects {

    private UiRedirects() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void intOeSet(INT_OE target, Object key, int v) {
        if (!AutoSync.sendRuntime(target, "set", "(Ljava/lang/Object;I)V", key, v))
            target.set(key, v);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void intOeInc(INT_OE target, Object key, int v) {
        if (!AutoSync.sendRuntime(target, "inc", "(Ljava/lang/Object;I)V", key, v))
            target.inc(key, v);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void dataAbsSet(DataO.DataAbs target, Object key, int v) {
        if (!AutoSync.sendRuntime(target, "set", "(Ljava/lang/Object;I)V", key, v))
            target.set(key, v);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void dataBitSet(DataO.DataBit target, Object key, int v) {
        if (!AutoSync.sendRuntime(target, "set", "(Ljava/lang/Object;I)V", key, v))
            target.set(key, v);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void rMapIntSet(RMapInt target, MAPPED key, int v) {
        if (!AutoSync.sendRuntime(target, "set", "(Lutil/keymap/MAPPED;I)V", key, v))
            target.set(key, v);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void rMapIntSetO(RMapInt target, Object key, int v) {
        if (!AutoSync.sendRuntime(target, "set", "(Ljava/lang/Object;I)V", key, v))
            target.set(key, v);
    }

    public static ArrayInt arrayIntSet(ArrayInt target, int i, int v) {
        if (!AutoSync.sendRuntime(target, "set", "(II)Lsnake2d/util/sets/ArrayInt;", i, v))
            return target.set(i, v);
        return target;
    }

    /** MoveOrderPullUI: resbits.or(RBIT.ALL) — a static RBIT argument, serialized by the RBIT worker. */
    public static init.resources.RBIT.RBITImp rbitOr(init.resources.RBIT.RBITImp target, init.resources.RBIT other) {
        if (!AutoSync.sendRuntime(target, "or", "(Linit/resources/RBIT;)Linit/resources/RBIT$RBITImp;", other))
            return target.or(other);
        return target;
    }
}
