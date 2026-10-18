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

    private static final int SAVE_VERSION = 2;
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

    /** Set when this run installed the agent and the game must be restarted. */
    public static String restartNotice;
    private static boolean constructed;

    /**
     * The main menu instantiates every script factory at startup
     * (menu/ScRandom.java:260 via ScriptEngine.getAll), so this runs before
     * any game exists: the earliest point at which a no-agent process can
     * wire the agent into the launcher settings for the next start.
     */
    public SyxMultiplayer() {
        if (constructed)
            return;
        constructed = true;
        MpLog.info("Syx Multiplayer " + syxmp.common.MpVersion.VERSION + " loaded; agent "
                + (SyxAgent.isActive() ? "active" : "NOT ACTIVE"));
        if (!SyxAgent.isActive())
            noteInstall(AgentInstaller.ensureInstalled());
    }

    private static void noteInstall(String result) {
        if (AgentInstaller.restartNeeded())
            restartNotice = "Syx Multiplayer is installed. QUIT and RESTART the game "
                    + "(launcher included) to enable multiplayer.";
        else
            restartNotice = "Syx Multiplayer could not enable itself: " + result
                    + "\nSee logs/syxmp.log and the mod's README.";
    }

    @Override
    public void initBeforeGameCreated() {
        if (!registered) {
            registered = true;
            SyxSync.registerAll();
            Hooks.installAll();
            if (!SyxAgent.isActive())
                noteInstall(AgentInstaller.ensureInstalled());
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
        IDebugPanel.add("MP: uninstall agent", () -> MpLog.info(AgentInstaller.uninstall()));
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
        syxmp.client.SaveTransfer.newGameCreated = true;
        syxmp.client.SaveTransfer.gamesCreated++;
        return new Instance();
    }

    public static final class Instance implements SCRIPT_INSTANCE {

        private boolean noticeShown;

        @Override
        public void update(double ds) {
            // The lockstep loop lives in TickController, driven from the
            // patched VIEW.update edge — nothing to do per sub-step here.
            if (restartNotice != null && !noticeShown) {
                noticeShown = true;
                syxmp.ui.RestartDialog.show(restartNotice);
            }
        }

        @Override
        public void render(snake2d.Renderer r, float ds) {
            syxmp.client.MpHud.render(r);
        }

        @Override
        public void mouseClick(snake2d.MButt button) {
            if (button != snake2d.MButt.LEFT || !Multiplayer.active())
                return;
            int mx = view.main.VIEW.mouse().x(), my = view.main.VIEW.mouse().y();
            if (mx >= syxmp.client.MpHud.X1 && mx <= syxmp.client.MpHud.x2
                    && my >= syxmp.client.MpHud.Y1 && my <= syxmp.client.MpHud.Y2)
                syxmp.ui.MpPanel.get().toggle();
        }

        @Override
        public void save(FilePutter file) {
            file.i(SAVE_VERSION);
            file.d(TickController.speed);
            file.l(Multiplayer.active() ? Multiplayer.session.rngSeed : 0L);
            file.i(TickController.timer);
            // v2: the sub-step accumulator is sim state — a joiner that starts
            // from 0 while the host carries a fraction splits sub-steps
            // differently after the next speed change (live-test desync).
            file.f(TickController.timeToTickThrough);
            file.d(TickController.prevNonZeroSpeed);
        }

        @Override
        public void load(FileGetter file) throws IOException {
            int version = file.i();
            if (version < 1 || version > SAVE_VERSION)
                return;
            double speed = file.d();
            long rngSeed = file.l();
            int timer = file.i();
            float accum = 0;
            double prevSpeed = 1.0;
            if (version >= 2) {
                accum = file.f();
                prevSpeed = file.d();
            }
            if (Multiplayer.active()) {
                TickController.speed = speed;
                TickController.timeToTickThrough = accum;
                TickController.prevNonZeroSpeed = prevSpeed;
                if (rngSeed != 0)
                    Multiplayer.session.rngSeed = rngSeed;
                MpLog.info("loaded MP state: speed " + speed + " timer " + timer
                        + " accum " + accum + " (clock at " + TickController.timer + ")");
            }
        }

        @Override
        public boolean handleBrokenSavedState() {
            return true;
        }
    }
}
