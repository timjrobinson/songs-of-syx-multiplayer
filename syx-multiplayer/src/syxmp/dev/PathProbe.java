package syxmp.dev;

import settlement.path.components.SComponent;
import settlement.path.components.finder.SCompFinder;
import settlement.path.components.finder.SCompFinder.SCompPath;

/**
 * Dev-only (-Dsyxmp.enttrace=1): logs the component flood that
 * SFinderRND.get uses to pick a random destination, so a diff of two
 * clients' traces shows whether the component list (count, order, indices)
 * differs before the pick.
 */
public final class PathProbe {

    private PathProbe() {
    }

    /** Redirect target for SPathFinder.find(IIIIZ) inside SPath: request + result chain hash. */
    public static snake2d.PathTile find(settlement.path.finders.SPathFinder f, int sx, int sy, int dx, int dy, boolean full) {
        snake2d.PathTile t = f.find(sx, sy, dx, dy, full);
        if (RndTrace.ENABLED) {
            StringBuilder sb = new StringBuilder(96);
            sb.append("S ").append(sx).append(',').append(sy).append("->").append(dx).append(',').append(dy)
              .append(full ? " full" : "");
            if (t == null) {
                sb.append(" null");
            } else {
                int h = 17, n = 0;
                try {
                    java.lang.reflect.Field fp = snake2d.PathTile.class.getDeclaredField("parent");
                    fp.setAccessible(true);
                    for (snake2d.PathTile c = t; c != null && n < 4096; c = (snake2d.PathTile) fp.get(c)) {
                        h = h * 31 + c.x() * 7919 + c.y();
                        n++;
                    }
                } catch (ReflectiveOperationException e) {
                    sb.append(" ?");
                }
                sb.append(" end=").append(t.x()).append(',').append(t.y()).append(" n=").append(n)
                  .append(" h=").append(Integer.toHexString(h));
            }
            RndTrace.mark(sb.toString());
        }
        return t;
    }

    /** Redirect target for SCompFinder.fill(III) inside SFinderRND. */
    public static SCompPath fill(SCompFinder f, int sx, int sy, int dist) {
        SCompPath p = f.fill(sx, sy, dist);
        if (RndTrace.ENABLED) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("C ").append(sx).append(',').append(sy).append(" d=").append(dist);
            if (p == null) {
                sb.append(" null");
            } else {
                sb.append(" n=").append(p.path().size()).append(" [");
                for (int i = 0; i < p.path().size() && i < 48; i++) {
                    SComponent c = p.path().get(i);
                    sb.append(c.index()).append('@').append(c.centreX()).append(',').append(c.centreY()).append(' ');
                }
                sb.append(']');
            }
            RndTrace.mark(sb.toString());
        }
        return p;
    }
}
