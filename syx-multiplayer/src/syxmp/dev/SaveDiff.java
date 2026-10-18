package syxmp.dev;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import game.GAME;
import game.save.GameSaver;
import game.save.Savable;
import settlement.main.SETT;
import snake2d.util.file.FilePutter;
import snake2d.util.sets.LIST;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;

/**
 * Per-subsystem state hashes: runs every registered Savable's save() into a
 * scratch buffer and hashes each one's bytes separately. Logged on every
 * client at the same tick (DEBUG_TOOLS "hash" command, chat "/hash"), the
 * tables are diffed offline to see WHICH subsystem diverged — far more
 * useful than one whole-save hash. Also usable to find save/load
 * round-trip instabilities.
 */
public final class SaveDiff {

    private static final Field BEFORE = ReflectUtil.field(GameSaver.class, "before");
    private static final Field ALL = ReflectUtil.field(GameSaver.class, "all");
    private static final Field BUFFER = ReflectUtil.field(FilePutter.class, "buffer");
    private static final Method SAVE = ReflectUtil.method(Savable.class, "save", FilePutter.class);

    public record Entry(String key, int length, long hash) {
    }

    private SaveDiff() {
    }

    public static List<Entry> hashes() {
        List<Entry> res = new ArrayList<>();
        try {
            Path dummy = Files.createTempDirectory("syxmp").resolve("hash.save");
            FilePutter fp = new FilePutter(dummy, 120 * SETT.TAREA);
            ByteBuffer buf = ReflectUtil.get(BUFFER, fp);
            GameSaver saver = GAME.saver();
            for (Field f : new Field[] {BEFORE, ALL}) {
                LIST<Savable> list = ReflectUtil.get(f, saver);
                for (int i = 0; i < list.size(); i++) {
                    Savable s = list.get(i);
                    int start = fp.getPosition();
                    try {
                        ReflectUtil.call(SAVE, s, fp);
                    } catch (RuntimeException e) {
                        res.add(new Entry(s.key, -1, 0));
                        continue;
                    }
                    int end = fp.getPosition();
                    long h = 0xcbf29ce484222325L;
                    for (int p = start; p < end; p++) {
                        h ^= buf.get(p) & 0xFF;
                        h *= 0x100000001b3L;
                    }
                    res.add(new Entry(s.key, end - start, h));
                }
            }
        } catch (Exception e) {
            MpLog.err("state hashing failed", e);
        }
        return res;
    }

    /** Log the table; grep "[SyxMP:hash]" in two clients' logs and diff. */
    public static void log(String tag) {
        long start = System.currentTimeMillis();
        List<Entry> entries = hashes();
        StringBuilder sb = new StringBuilder();
        sb.append("[SyxMP:hash] ").append(tag).append(" updateI=").append(GAME.updateI()).append('\n');
        for (Entry e : entries)
            sb.append("[SyxMP:hash] ").append(e.key()).append(' ').append(e.length()).append(' ')
              .append(Long.toHexString(e.hash())).append('\n');
        sb.append("[SyxMP:hash] end (").append(System.currentTimeMillis() - start).append("ms)");
        System.out.println(sb);
    }
}
