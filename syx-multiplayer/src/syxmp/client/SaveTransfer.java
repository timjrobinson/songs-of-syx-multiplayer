package syxmp.client;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import game.GAME;
import game.save.GameLoader;
import game.save.GameSaver;
import settlement.main.SETT;
import snake2d.util.file.FilePutter;
import syxmp.util.MpLog;
import syxmp.util.ReflectUtil;

/**
 * Captures the full game state to bytes in memory and applies received state,
 * using the game's own save pipeline. The byte format matches the game's
 * .save files — Deflate(int32 BE rawLength || raw buffer) — so a received
 * blob is loaded by writing it to a temp file and running GameLoader.
 *
 * The reflective route into GameSaver.save(FilePutter) skips the outer
 * GameSaver.save(Path,...) frame-swapping/progress-printing wrapper
 * (GameSaver.java:60-99 in V71.44) but runs the same savable pipeline.
 */
public final class SaveTransfer {

    private static final Method SAVE = ReflectUtil.method(GameSaver.class, "save", FilePutter.class);
    private static final Field BUFFER = ReflectUtil.field(FilePutter.class, "buffer");

    private SaveTransfer() {
    }

    /** A captured state: .save-format bytes plus a hash of the RAW buffer.
     *  The hash is computed pre-deflate because compressed bytes may differ
     *  across JVM zlib versions even for identical state. */
    public static final class Capture {
        public final byte[] saveBytes;
        public final long rawHash;
        public final int rawLength;

        Capture(byte[] saveBytes, long rawHash, int rawLength) {
            this.saveBytes = saveBytes;
            this.rawHash = rawHash;
            this.rawLength = rawLength;
        }
    }

    /** Serialize the running game to .save-format bytes, entirely in memory. */
    public static Capture capture() {
        long start = System.currentTimeMillis();
        try {
            Path dummy = Files.createTempDirectory("syxmp").resolve("capture.save");
            FilePutter fp = new FilePutter(dummy, 120 * SETT.TAREA);
            ReflectUtil.call(SAVE, GAME.saver(), fp);
            ByteBuffer buf = ReflectUtil.get(BUFFER, fp);
            int len = fp.getPosition();
            long hash = fnv(buf.array(), len);

            ByteArrayOutputStream bos = new ByteArrayOutputStream(len / 4);
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream out = new DeflaterOutputStream(bos, deflater, 1 << 16)) {
                // FileGetter expects a big-endian raw length first
                out.write((len >>> 24) & 0xFF);
                out.write((len >>> 16) & 0xFF);
                out.write((len >>> 8) & 0xFF);
                out.write(len & 0xFF);
                out.write(buf.array(), 0, len);
            } finally {
                deflater.end();
            }
            byte[] result = bos.toByteArray();
            MpLog.info("captured state: " + len + "b raw -> " + result.length + "b in "
                    + (System.currentTimeMillis() - start) + "ms");
            return new Capture(result, hash, len);
        } catch (Exception e) {
            throw new IllegalStateException("state capture failed", e);
        }
    }

    static long fnv(byte[] data, int len) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < len; i++) {
            hash ^= data[i] & 0xFF;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * Load received state. Recreates the whole GAME (GameLoader semantics) —
     * must be called from the updater thread; the switch happens through
     * CORE.setCurrentState on the next frame.
     */
    public static void apply(byte[] saveBytes, Runnable afterLoad) {
        try {
            Path dir = Files.createTempDirectory("syxmp");
            Path file = dir.resolve("network.save");
            Files.write(file, saveBytes);
            Multiplayer.reloading = true;
            GAME.saver().onAfterLoad(p -> {
                Multiplayer.reloading = false;
                if (afterLoad != null)
                    afterLoad.run();
            });
            new GameLoader(file).set();
            MpLog.info("loading received state (" + saveBytes.length + "b)");
        } catch (Exception e) {
            throw new IllegalStateException("state apply failed", e);
        }
    }

}
