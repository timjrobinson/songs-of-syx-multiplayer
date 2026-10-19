package syxmp.client;

import syxmp.server.ServerSettings;
import syxmp.util.MpLog;

/**
 * Development auto-pilot for unattended end-to-end testing, gated behind
 * system properties (all absent = completely inert):
 *
 *   -Dsyxmp.autoload=1            quickload the newest save from the main menu
 *   -Dsyxmp.autohost=1            host a server once in game, then unpause
 *   -Dsyxmp.autojoin=host:port    join a server once in game
 *
 * menuTick is registered from premain (its hook must exist before the menu
 * class loads), so this class must not touch game classes during class init.
 */
public final class DevAuto {

    private static final String AUTOLOAD = System.getProperty("syxmp.autoload");
    private static final String AUTOHOST = System.getProperty("syxmp.autohost");
    private static final String AUTOJOIN = System.getProperty("syxmp.autojoin");
    /** -Dsyxmp.autounpause=1: host sets speed 1 a few seconds after a second player is playing. */
    private static final String AUTOUNPAUSE = System.getProperty("syxmp.autounpause");
    private static int framesWithTwo;
    private static boolean unpausedForTwo;
    /** -Dsyxmp.autohash=1: host requests per-savable state hashes on all clients before unpausing. */
    private static final String AUTOHASH = System.getProperty("syxmp.autohash");
    private static boolean hashed;
    /** -Dsyxmp.autospeed=N: speed to set instead of 1 when auto-unpausing. */
    private static final double AUTOSPEED = Double.parseDouble(System.getProperty("syxmp.autospeed", "1"));
    /** -Dsyxmp.autoact=1: host performs a scripted player action every 20s (exercises the sync paths). */
    private static final String AUTOACT = System.getProperty("syxmp.autoact");
    private static int actFrames;
    private static int actIndex;
    private static int createAtFrame = -1;
    private static int areaAtFrame = -1;
    private static int stockpileStep;

    /** -Dsyxmp.autorefurnish=<room index|-1 first area room|-2 first indoor area room>: the client with -Dsyxmp.autorefurnish.role (host|joiner, default
     *  joiner) refurnishes that room through the room panel's button path (UIRoomPlacer.reconstruct) once both
     *  players are in lockstep, then "constructs" it 100 frames later exactly like SFrame's OK button. */
    private static final String AUTOREFURNISH = System.getProperty("syxmp.autorefurnish");
    private static final String AUTOREFURNISH_ROLE = System.getProperty("syxmp.autorefurnish.role", "joiner");
    private static int refurnishFrames;
    private static int refurnishStep;
    private static int rfX1, rfY1, rfX2, rfY2;

    private static int menuFrames;
    private static int gameFrames;
    private static boolean loaded, hosted, joined, unpaused;

    private DevAuto() {
    }

    /** Scripted player actions, issued from UI context so they go through the sync hooks. */
    private static void act(int i) {
        switch (i % 8) {
            case 7 -> {
                // Home assignment with a UI-local bit set (RoomToolSync): everyone allowed in the first house.
                settlement.room.home.house.HomeInstance h = null;
                for (settlement.room.main.RoomBlueprintIns<?> b : settlement.main.SETT.ROOMS().ins())
                    if (b.instancesSize() > 0 && ((Object) b.getInstance(0)) instanceof settlement.room.home.house.HomeInstance hi) {
                        h = hi;
                        break;
                    }
                if (h == null) {
                    MpLog.info("[auto] action: no house for home assignment");
                    return;
                }
                MpLog.info("[auto] action: home assignment on house at " + h.mX() + "," + h.mY());
                syxmp.net.ByteWriter w = new syxmp.net.ByteWriter(64);
                w.writeByte(16); // SyncActions.HOME_ASSIGN
                w.writeInt(h.mX());
                w.writeInt(h.mY());
                w.writeInt(0); // empty bits = nobody; harmless, exercises the path
                ClientCommands.send(syxmp.common.CommandType.ACTION, w);
            }
            case 6 -> {
                // Map mark at the map centre (NameSync): random colour drawn inside the command.
                int cx = settlement.main.SETT.TWIDTH / 2, cy = settlement.main.SETT.THEIGHT / 2;
                MpLog.info("[auto] action: map mark at " + cx + "," + cy);
                syxmp.sync.NameSync.markPlace(null, new Object[] {cx, cy});
            }
            case 5 -> {
                // Rename the first room through the Str redirect pair (NameSync.ownerOf scan + NAME_SET).
                settlement.room.main.RoomInstance room = null;
                for (settlement.room.main.RoomBlueprintIns<?> b : settlement.main.SETT.ROOMS().ins())
                    if (b.instancesSize() > 0) {
                        room = b.getInstance(0);
                        break;
                    }
                if (room == null) {
                    MpLog.info("[auto] action: no room to rename");
                    return;
                }
                MpLog.info("[auto] action: rename room #" + room.index());
                syxmp.sync.NameSync.strAdd(syxmp.sync.NameSync.strClear(room.name()), "MP room " + i);
            }
            case 4 -> {
                // Tactical battle orders on the first player division (BattleOrderSync): a STOP task and a
                // re-set of its current destination formation go through the Locked.set hook.
                game.battle.div.Div div = game.GAME.ARMIES().player().divisions().get(0);
                MpLog.info("[auto] action: battle order stop + dest on division " + div.index());
                game.battle.thread.order.BattleOrderTask task = new game.battle.thread.order.BattleOrderTask();
                task.stop(div);
                div.order().task.set(task);
                game.battle.formation.DivFormationImp form = new game.battle.formation.DivFormationImp();
                div.order().dest.get(form);
                div.order().dest.set(form);
            }
            case 3 -> {
                // Build a 4x4 stockpile: stage blueprint (AutoSync), area (TOOL), commit (AutoSync).
                settlement.room.main.placement.RoomPlacer placer = settlement.main.SETT.ROOMS().placement.placer;
                settlement.room.main.RoomBlueprintImp blue = settlement.main.SETT.ROOMS().STOCKPILE;
                MpLog.info("[auto] action: stage stockpile blueprint");
                placer.init(blue, 0); // synced command; applied on every client at its tick
                stockpileStep = i;
                areaAtFrame = gameFrames + 90;
            }
            case 0 -> {
                int n = 1 + (i / 3) % 5;
                MpLog.info("[auto] action: immigration setWanted " + n);
                settlement.main.SETT.ENTRY().immi().setWanted(init.race.RACES.all().get(0), n);
            }
            case 1 -> {
                // Designate a 3x3 "clear wood and rock" job near the map centre, on the first spot that accepts it.
                view.tool.PlacableMulti p = (view.tool.PlacableMulti) settlement.main.SETT.JOBS().clearss.woodAndRock;
                int cx = settlement.main.SETT.TWIDTH / 2, cy = settlement.main.SETT.THEIGHT / 2;
                for (int r = 2 + (i / 3) * 3; r < cx - 4; r += 3) {
                    int x = cx + r, y = cy + (i % 7) * 2;
                    if (p.isPlacable(x, y, null, view.tool.PLACER_TYPE.SQUARE) == null) {
                        MpLog.info("[auto] action: clear job at " + x + "," + y);
                        syxmp.sync.ToolSync.devPlaceMulti(p, x, y, 3, 3);
                        return;
                    }
                }
                MpLog.info("[auto] action: no placable tile found for clear job");
            }
            case 2 -> {
                MpLog.info("[auto] action: pause toggle x2 (speed command round trip)");
                ClientCommands.requestSpeed(TickController.speed == 5 ? 1 : AUTOSPEED);
            }
        }
    }

    /** -Dsyxmp.autojoinmenu=host:port — join straight from the main menu (no game loaded first). */
    private static final String AUTOJOIN_MENU = System.getProperty("syxmp.autojoinmenu");
    private static boolean menuJoined;

    /** Called every main-menu frame via the Menu.update entry hook. */
    public static void menuTick() {
        if (AUTOJOIN_MENU != null && !menuJoined) {
            if (++menuFrames < 240)
                return;
            menuJoined = true;
            String[] parts = AUTOJOIN_MENU.split(":");
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 30599;
            MpLog.info("[auto] joining from the main menu: " + parts[0] + ":" + port);
            ClientConnect.connect(parts[0], port, "joiner", "");
            return;
        }
        if (AUTOLOAD == null || loaded)
            return;
        if (++menuFrames < 90)
            return;
        loaded = true;
        MpLog.info("[auto] quickloading newest save");
        if (!game.save.GameLoader.quickload())
            MpLog.err("[auto] quickload failed: no save found");
    }

    /** Called every in-game frame from TickController.gameUpdate. */
    public static void gameTick() {
        if (AUTOLOAD != null && !Multiplayer.reloading) {
            // Unattended runs: dismiss the "CLICK TO CONTINUE" screen ourselves.
            try {
                if (view.main.VIEW.inters().load.isActivated())
                    view.main.VIEW.inters().load.deactivate();
            } catch (RuntimeException e) {
                // no view yet
            }
        }
        if (AUTOHOST == null && AUTOJOIN == null)
            return;
        gameFrames++;
        if (gameFrames < 240)
            return;

        if (AUTOHOST != null) {
            if (!hosted) {
                hosted = true;
                MpLog.info("[auto] hosting");
                HostUtil.hostServer("host", new ServerSettings());
            } else if (!unpaused && gameFrames > 600 && Multiplayer.active()) {
                unpaused = true;
                MpLog.info("[auto] unpausing (speed 1)");
                ClientCommands.requestSpeed(1.0);
            }
        }

        if (AUTOUNPAUSE != null && Multiplayer.active() && Multiplayer.session.isHost
                && Multiplayer.session.players.size() >= 2 && TickController.speed == 0
                && TickController.inLockstep()) {
            if (AUTOHASH != null && framesWithTwo == 120 && !hashed) {
                hashed = true;
                MpLog.info("[auto] requesting state hashes");
                syxmp.net.ByteWriter w = new syxmp.net.ByteWriter(1);
                w.writeByte(0);
                ClientCommands.send(syxmp.common.CommandType.DEBUG_TOOLS, w);
            }
            if (++framesWithTwo >= 300) {
                framesWithTwo = 0;
                MpLog.info("[auto] second player present: speed " + AUTOSPEED);
                ClientCommands.requestSpeed(AUTOSPEED);
            }
        } else {
            framesWithTwo = 0;
        }

        if (AUTOREFURNISH != null && refurnishStep < 3 && Multiplayer.active() && TickController.inLockstep()
                && TickController.simulatingTarget < 0 && !Multiplayer.reloading
                && Multiplayer.session.players.size() >= 2
                && Multiplayer.session.isHost == "host".equals(AUTOREFURNISH_ROLE)) {
            refurnishFrames++;
            if (refurnishStep == 0 && refurnishFrames >= 60) {
                refurnishStep = 1;
                int want = Integer.parseInt(AUTOREFURNISH);
                settlement.room.main.RoomInstance room = null;
                int seen = 0, maxI = -1;
                outer:
                for (settlement.room.main.RoomBlueprintIns<?> b : settlement.main.SETT.ROOMS().ins())
                    for (int k = 0; k < b.instancesSize(); k++) {
                        settlement.room.main.RoomInstance r = b.getInstance(k);
                        seen++;
                        maxI = Math.max(maxI, r.index());
                        boolean area = r.constructor() != null && r.constructor().usesArea();
                        if (r.index() == want || (want == -1 && area)
                                || (want == -2 && area && r.constructor().mustBeIndoors())) {
                            room = r;
                            break outer;
                        }
                    }
                if (room == null && want >= 0 && settlement.main.SETT.ROOMS().map.getByIndex(want) instanceof settlement.room.main.RoomInstance ri)
                    room = ri;
                if (room == null) {
                    MpLog.info("[auto] no room to refurnish (" + want + "): " + seen + " instances, max index " + maxI
                            + ", map slot " + settlement.main.SETT.ROOMS().map.getByIndex(want));
                    refurnishStep = 3;
                    return;
                }
                rfX1 = room.body().x1(); rfY1 = room.body().y1(); rfX2 = room.body().x2(); rfY2 = room.body().y2();
                MpLog.info("[auto] action: refurnish room #" + room.index() + " (" + room.blueprint().key + ") at "
                        + room.mX() + "," + room.mY() + " area " + room.area() + " body " + rfX1 + "," + rfY1 + "-" + rfX2 + "," + rfY2);
                view.main.VIEW.s().ui.placer.reconstruct(room);
            } else if (refurnishStep == 1 && refurnishFrames >= 120) {
                refurnishStep = 2;
                // Furnish like a player would: clear the items, pick an item group, rotate/size it in the
                // tool (UI-local state on the shared placer), place it through the tool hook.
                settlement.room.main.placement.RoomPlacer placer = settlement.main.SETT.ROOMS().placement.placer;
                placer.removeAllItems();
                settlement.room.main.RoomBlueprintImp blue = placer.blueprint();
                int g = 0;
                for (int k = 0; k < blue.constructor().pgroups().size(); k++) {
                    view.tool.PlacableFixed t = placer.item(k);
                    if (t.rotations() > 1 || t.sizes() > 1) {
                        g = k;
                        break;
                    }
                }
                view.tool.PlacableFixed pp = placer.item(g);
                pp.rotSet(1);
                pp.sizeSet(pp.sizes() - 1);
                MpLog.info("[auto] action: furnish group " + g + " rot " + pp.rot() + "/" + pp.rotations() + " size "
                        + pp.size() + "/" + pp.sizes() + " " + pp.width() + "x" + pp.height());
                boolean placed = false;
                for (int y = rfY1; y < rfY2 && !placed; y++)
                    for (int x = rfX1; x < rfX2 && !placed; x++) {
                        if (pp.placableWhole(x, y) == null) {
                            MpLog.info("[auto] action: place item at " + x + "," + y);
                            syxmp.sync.ToolSync.placeFixed(pp, x, y, 0, 0);
                            placed = true;
                        }
                    }
                if (!placed)
                    MpLog.info("[auto] no spot for the item");
            } else if (refurnishStep == 2 && refurnishFrames >= 180) {
                refurnishStep = 3;
                settlement.room.main.placement.RoomPlacer placer = settlement.main.SETT.ROOMS().placement.placer;
                MpLog.info("[auto] action: construct refurnished room; problem=" + placer.createProblem()
                        + " size " + placer.size());
                placer.create();
                view.main.VIEW.s().tools.placer.deactivate();
            }
        }

        if (areaAtFrame > 0 && gameFrames >= areaAtFrame) {
            areaAtFrame = -1;
            settlement.room.main.placement.RoomPlacer placer = settlement.main.SETT.ROOMS().placement.placer;
            view.tool.PlacableMulti area = (view.tool.PlacableMulti) placer.area();
            int cx = settlement.main.SETT.TWIDTH / 2, cy = settlement.main.SETT.THEIGHT / 2;
            boolean placed = false;
            for (int r = 6 + (stockpileStep / 4) * 5; r < cx - 6 && !placed; r += 4) {
                int x = cx - r, y = cy - (stockpileStep % 5) * 4;
                boolean ok = true;
                for (int dy = 0; dy < 4 && ok; dy++)
                    for (int dx = 0; dx < 4 && ok; dx++)
                        ok = area.isPlacable(x + dx, y + dy, syxmp.sync.ToolSync.rect(x, y, 4, 4), view.tool.PLACER_TYPE.SQUARE) == null;
                if (ok) {
                    MpLog.info("[auto] action: stockpile area at " + x + "," + y);
                    syxmp.sync.ToolSync.devPlaceMulti(area, x, y, 4, 4);
                    createAtFrame = gameFrames + 120;
                    placed = true;
                }
            }
            if (!placed)
                MpLog.info("[auto] action: no spot for stockpile");
        }

        if (createAtFrame > 0 && gameFrames >= createAtFrame) {
            createAtFrame = -1;
            settlement.room.main.placement.RoomPlacer placer = settlement.main.SETT.ROOMS().placement.placer;
            if (placer.createProblem() == null) {
                MpLog.info("[auto] action: create room");
                placer.create();
            } else {
                MpLog.info("[auto] stockpile not creatable: " + placer.createProblem());
            }
        }

        if (AUTOACT != null && Multiplayer.active() && Multiplayer.session.isHost
                && TickController.inLockstep() && TickController.speed > 0
                && Multiplayer.session.players.size() >= 2) {
            if (++actFrames >= 600) {
                actFrames = 0;
                try {
                    act(actIndex++);
                } catch (RuntimeException e) {
                    MpLog.err("[auto] action failed", e);
                }
            }
        }

        if (AUTOJOIN != null && !joined) {
            joined = true;
            String[] parts = AUTOJOIN.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 30599;
            MpLog.info("[auto] joining " + host + ":" + port);
            ClientConnect.connect(host, port, "joiner", "");
        }
    }
}
