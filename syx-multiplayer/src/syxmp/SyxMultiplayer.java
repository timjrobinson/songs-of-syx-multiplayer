package syxmp;

import java.io.IOException;

import script.SCRIPT;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import syxmp.util.MpLog;

/**
 * Mod entry point, discovered by the game's ScriptLoad scanner.
 *
 * Architecture is a port of the RimWorld Multiplayer mod: the simulation runs
 * deterministically on every client, and only player commands go over the
 * wire, scheduled for execution at an agreed tick.
 */
public class SyxMultiplayer implements SCRIPT {

    @Override
    public CharSequence name() {
        return "Syx Multiplayer";
    }

    @Override
    public CharSequence desc() {
        return "Co-op multiplayer: deterministic lockstep sync, RimWorld-Multiplayer-style.";
    }

    @Override
    public boolean isSelectable() {
        return true;
    }

    @Override
    public void initBeforeGameCreated() {
        MpLog.info("initBeforeGameCreated");
    }

    @Override
    public void initBeforeGameInited() {
        MpLog.info("initBeforeGameInited");
    }

    @Override
    public SCRIPT_INSTANCE createInstance() {
        MpLog.info("createInstance");
        return new Instance();
    }

    public static final class Instance implements SCRIPT_INSTANCE {

        @Override
        public void update(double ds) {
            // Lockstep tick controller hooks in here.
        }

        @Override
        public void save(FilePutter file) {
        }

        @Override
        public void load(FileGetter file) throws IOException {
        }

        @Override
        public boolean handleBrokenSavedState() {
            return true;
        }
    }
}
