package syxmp;

import java.io.IOException;

import script.SCRIPT;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import syxmp.agent.AgentInstaller;
import syxmp.agent.SyxAgent;
import syxmp.client.ClientConnect;
import syxmp.client.Hooks;
import syxmp.client.HostUtil;
import syxmp.client.Multiplayer;
import syxmp.client.TickController;
import syxmp.server.ServerSettings;
import syxmp.sync.SyxSync;
import syxmp.util.MpLog;
import view.interrupter.IDebugPanel;

/**
 * Mod entry point, discovered by the game's ScriptLoad scanner.
 *
 * Architecture is a port of the RimWorld Multiplayer mod: the simulation runs
 * deterministically on every client, and only player commands go over the
 * wire, scheduled for execution at an agreed tick. forceInit injects this
 * script into every game and save, so multiplayer state always rides along.
 */
public class SyxMultiplayer implements SCRIPT {

    private static final int SAVE_VERSION = 1;
    private static boolean registered;

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
        return false;
    }

    @Override
    public boolean forceInit() {
        return true;
    }

    @Override
    public void initBeforeGameCreated() {
        if (!registered) {
            registered = true;
            SyxSync.registerAll();
            Hooks.installAll();
            MpLog.info("agent " + (SyxAgent.isActive() ? "active" : "NOT ACTIVE"));
        }
    }

    @Override
    public void initBeforeGameInited() {
        String user = System.getProperty("user.name", "player").replaceAll("[^a-zA-Z0-9_]", "_");
        IDebugPanel.add("MP: host (port 30599)", () ->
                HostUtil.hostServer(user, new ServerSettings()));
        IDebugPanel.add("MP: join localhost", () ->
                ClientConnect.connect("127.0.0.1", 30599, user + "2", ""));
        IDebugPanel.add("MP: pause/resume", () -> {
            if (Multiplayer.active())
                syxmp.client.ClientCommands.requestPauseToggle();
        });
        IDebugPanel.add("MP: status", () -> MpLog.info(status()));
        IDebugPanel.add("MP: install agent", () -> MpLog.info(AgentInstaller.install()));
    }

    public static String status() {
        if (!Multiplayer.active())
            return "not in multiplayer";
        return "timer " + TickController.timer + "/" + TickController.tickUntil
                + " speed " + TickController.speed
                + (Multiplayer.session.isHost ? " (host)" : "")
                + " players " + Multiplayer.session.players.size();
    }

    @Override
    public SCRIPT_INSTANCE createInstance() {
        MpLog.info("createInstance");
        return new Instance();
    }

    public static final class Instance implements SCRIPT_INSTANCE {

        @Override
        public void update(double ds) {
            // The lockstep loop lives in TickController, driven from the
            // patched VIEW.update edge — nothing to do per sub-step here.
        }

        @Override
        public void save(FilePutter file) {
            file.i(SAVE_VERSION);
            file.d(TickController.speed);
            file.l(Multiplayer.active() ? Multiplayer.session.rngSeed : 0L);
            file.i(TickController.timer);
        }

        @Override
        public void load(FileGetter file) throws IOException {
            int version = file.i();
            double speed = file.d();
            long rngSeed = file.l();
            int timer = file.i();
            if (version != SAVE_VERSION)
                return;
            if (Multiplayer.active()) {
                TickController.speed = speed;
                if (rngSeed != 0)
                    Multiplayer.session.rngSeed = rngSeed;
                MpLog.info("loaded MP state: speed " + speed + " timer " + timer
                        + " (clock at " + TickController.timer + ")");
            }
        }

        @Override
        public boolean handleBrokenSavedState() {
            return true;
        }
    }
}
