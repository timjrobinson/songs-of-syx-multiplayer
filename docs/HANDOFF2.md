# HANDOFF2 — what is NOT done, and exactly how to finish it

Written 2026-10-18 at the end of session 2. Read `CLAUDE.md` first (the
rules), then this file. `docs/HANDOFF.md` has the session chronology and the
determinism catalogue; this file is the forward-looking work list.

The goal, in the user's words: install the mod, pick Multiplayer in the
menu, host a save, friends join (by address or through Steam), everything is
synced, cursors are visible, the town is fully playable co-op without
desyncs.

---

## 0. Where things stand (verified facts, not hopes)

| Area | State |
|---|---|
| Join from main menu / in-game, world transfer, catch-up, lockstep | Works, two real machines (this PC via Proton, the second tester's Ubuntu PC native) |
| Determinism, idle | 23 min at speed 1, zero desyncs |
| Determinism with scripted synced actions | 20 min at speed 5, 22 actions, zero desyncs |
| Determinism with REAL play (two humans) | "desynced a few times" in the first session; causes not yet seen (no log file existed then) |
| Auto-resync after desync | Works (drops + rejoins, ~10–20 s) |
| Cursors, chat, player list, HUD, escape-menu entry, menu screen | Work (visually verified in screenshots) |
| Steam lobbies / friends / P2P | Implemented, OPT-IN, never tested; crashes the JVM on Steam-snap Ubuntu |
| Sync coverage of player actions | THIN — see §2 |
| Battles | Unsupported |
| Packaging | `syx-multiplayer/package.sh` → `dist/SyxMultiplayer-<ver>.zip`; agent auto-install on first run (unreliable, see §4) |

---

## 1. Finding the remaining desyncs (do this first, with the humans' logs)

The mod now writes `<game data>/logs/syxmp.log` on every machine. After a
two-player session, read both files (the second test PC: `ssh user@<second-pc>`,
`~/.local/share/songsofsyx/logs/syxmp.log`; this PC: the Proton prefix, see
CLAUDE.md). Look for:

- `DESYNC: sub-step state #N differs in window T [local: timer/updateI ... | remote: ...]`
  – identical tick lists on both sides = a value divergence (state differs);
  different lists = a sub-step COUNT divergence (clock/accumulator bug).
- `UNSYNCED (local only): <owner method desc> — no address for ...`
  – an AutoSync hook fired in UI context but the target/arg had no object
  path. Each one is a sync gap to close (§2).
- `UNSYNCED tool (local only)` – a map tool whose placer has no object path.
- `auto-synced ...` / `synced ...` lines just before a desync tell you which
  synced action's *execution* diverged (executed on both, different result).
- Anything else from the game that only one side logs (`error executing`, exceptions).

Then reproduce with the pair on this machine and the tracing tools:

```
syx-multiplayer/e2e/run-pair.sh -Dsyxmp.rndtrace=1 -Dsyxmp.noresync=1 -Dsyxmp.autoact=1 -Dsyxmp.autospeed=5
syx-multiplayer/e2e/watch.sh            # reports load probes, desync or 5 clean minutes
syx-multiplayer/e2e/diffdesync.sh       # first divergent RND draw from both rolling windows
syx-multiplayer/e2e/difftrace.sh        # same, from the first-300-sub-steps full trace
syx-multiplayer/e2e/diffload.sh         # divergence during the load itself
```

Rules of thumb that held every single time this session:

1. Identical call-site sequences but different fingerprints ⇒ different RND
   *seeds*, i.e. `updateI`/`timer`/`rngSeed` differ, or an untraced RND user.
2. Extra/missing draws from a class whose name says Sprite/Render/Anim/Sound/
   Light ⇒ cosmetic code on the shared stream ⇒ add the class to
   `PatchTable.VISUAL_RND_CLASSES` (audit the wildcard against the jar class
   list first: `*Anim*` once caught `animal.Animal`).
3. Extra/missing draws from a constructor or `<init>`/`<clinit>` during the
   load ⇒ per-process table or construction-time random state ⇒ `ClinitRnd`
   (static init), `DetRnd` (instance tables, reset per load), or the two load
   seeds (`seedForLoad`, `seedAfterCreate`).
4. Same inputs, different branch, no RND difference before it ⇒ transient
   state: a thread (BattleSync-style synchronous drive), a process-level
   singleton (warm-up load), a view/frame-rate dependency (`VIEW.RI()`,
   `renderSecond`, `VIEW.s().isActive()`), or an unsaved field.

Known but unresolved candidates to check when a real-play desync shows up:

- `settlement/stats/colls/StatsReproduction.java:221` gates births on
  `VIEW.b().isActive() || SPEED.isPaused()` — fine while nobody opens the
  battle view; becomes a divergence as soon as one player does. Redirect
  `VIEW.b().isActive()` in sim classes to `false` in MP (CallRedirect on
  `view/battle/BattleView.isActive()Z` inside the specific classes, or hook
  BattleView.isActive with a thread/context check).
- `settlement/entity/humanoid/Humanoid.java:443` equipment drop gated on
  battle view (same fix).
- `settlement/main/SETT.java:667` rain effect gated on `!SPEED.isPaused()` —
  `SPEED.speed` is 1 during our sub-steps on all clients, so consistent, but
  verify after any change to speed handling.
- `game/time/Gui.update` (UI light flicker) still runs inside `TIME.update`;
  its RND use is redirected, but it reads `VIEW`? Check if a desync points there.
- Autosave is NOT disabled in MP. It does not change sim state, but it stalls
  one client for ~1 s and writes the host-side save; set
  `S.get().autoSaveInterval` to 0 (or intercept `GameSaver.autoSave`) in MP.
- `GAME.Warn` pauses / `GAME.Error` throws depending on DEVELOPER/DEBUG
  launcher flags. Both test homes have DEVELOPER=1; real players have 0.
  Force identical behaviour in MP (hook `GAME.Warn`/`GAME.Error`).
- The paused path: vanilla calls every `GameResource.update(0)` per frame
  while paused and increments `updateI`; MP runs nothing while paused. All
  clients agree, so no desync, but `GAME.Cache` keyed on `updateI` never
  refreshes while paused → UI may look stale when building while paused. If
  it bothers players, run `GAME.update(0)` per Timer unit when speed==0 with
  a deterministic RND seed.

---

## 2. Sync coverage — the main remaining work

### 2.1 How syncing works here (so you add entries correctly)

Three mechanisms, all driven by the agent's bytecode patches
(`syxmp/agent/PatchTable.java`), all executed on every client at the
scheduled tick by `CommandExecutor`:

1. **`SyxSync` / `SyncMethod`** — explicit RW-style registrations with a
   resolver (`Immigration.admit` is the example). Order is wire protocol.
2. **`AutoSync` + `AutoSyncTable`** — the generic path. Add a line
   `"owner/Class method (desc)ret"` and the agent hooks that method's entry;
   in UI context the call is cancelled locally and sent with the target's
   **object path** (`ObjectPaths`: reflective BFS from static roots SETT /
   GAME / FACTIONS / WORLD / RACES / RESOURCES / STATS, anchors `ent:<id>`
   and `room:<index>`). Arguments: primitives/enums/strings, registered
   workers (`SyxSerialization`: ENTITY, Room, RoomBlueprintImp, Race,
   RESOURCE), else by object path. If the target or an argument has no path
   the call runs locally and logs `UNSYNCED`.
3. **`ToolSync`** — map tools. Hooks `PlacableMultiTool.place()` (area
   tools: ships area bitmap + PLACER_TYPE + `Job.overwrite`) and redirects
   the `place/placeFirst/placeExpanded` calls of the simple/tile/fixed/single
   tools. The placer object is addressed by object path.

The hook id space: fixed ids in `PatchTable` (0–17 used), AutoSync ids from
1000. `SyncTemplates.MAX_HOOKS` is 4096.

How to discover what to add: play with `-Dsyxmp.trace=1`; every first call
of a candidate sim method from UI context is written to
`<logs>/syxmp-verbs.txt` with its call chain. (The tracer brackets UI context
as "between the end of our game update and VIEW.render", which includes
mouse-click dispatch.) Nobody has clicked through the UI with the tracer on
yet — that is the quickest way to build the list below properly.

### 2.2 Known gaps (from reading the sources, not yet synced)

In rough priority for a co-op town:

- **Room instance actions**: demolish/remove, upgrade (`RoomInstance`
  methods), copy/paste rooms (`settlement/room/main/copy/*`), room-specific
  settings implemented as *custom* `INTE`/`BOOLEAN_MUTABLE` classes (only
  `IntImp`/`BOOLEANImp` and `RoomEmployment$Priority/GRoupInt` are hooked).
  Grep `implements INTE`/`implements INT.INTE` in `settlement/` and hook each
  `set` (the list: trade PBuyer/PSeller*, RoomTally, RoomEquip,
  StatsMultipliers, ...). Watch for ones that are UI-local.
- **Job designations beyond placement**: cancel/clear job tools go through
  the tool hooks already; priority toggles on jobs may not.
- **Trade** (`PBuyer`, `PSeller`, `settlement/trade`), **export/import
  rooms** (`Gui` classes call instance methods directly).
- **Laws / religion / policies**: `settlement/stats/law`, slider-backed
  (IntImp → already synced if the IntImp is reachable from `STATS`).
- **Research** `PTech.levelSet`, **events** `EVENT_HANDLER.choiceSelect`,
  **diplomacy** `Deal.execute`, **region levels** `RDLevelsTmp.accept`,
  **armies** `WArmy.setDestination/intercept/besiege/raid/stop/disband`,
  **nobles** `NOBLES.setOffice`, **curfew** `Curfew.setForADay`,
  **court** (`view/world/ui/faction/Court.java:539` assassinate consumes RND
  in UI and mutates sim — must become a command).
- **Immigration**: `admit` (SyxSync) and `setWanted/setHigher` (AutoSync)
  done; check the rest of `Immigration`.
- **Room placement**: staging is shared and synced (`RoomPlacer.init`,
  area via ToolSync, item placers via ToolSync, `create/removeArea/
  removeAllItems/popHistory` via AutoSync). Still unsynced: rotation,
  `upgradeSet` on the staging `Instance`, structure/auto-wall toggles if they
  are not `BOOLEANImp` (`RoomPlacer.autoWalls` is a custom class),
  `UtilStructure`. Two players planning at once clobber each other — a
  known v1 limitation; RW solves it with per-player sessions.
- **Debug/dev**: `DEBUG_TOOLS` command only has `/hash`.

### 2.3 Things that are *correctly* local

Camera, zoom, UI panels, hover, minimap, the placement preview before
commit, sound. Everything else that touches sim objects must go through a
command.

---

## 3. Determinism work still open

- **Battles**: `BattleSync` drives the five battle AI workers synchronously
  (status 0.5 s, centres/trajectories/orders 1/60 s, strategist 1/120 s).
  Untested in an actual battle. The battle VIEW gates sim resources
  (`GAME.java:260` `s.isBattle || !VIEW.b().isActive()`), so when one client
  opens the battle view the sims diverge → either sync "battle view open" as
  a command or decouple the gate from the local view.
- **Pathfinding**: long-range jobs now run synchronously in `PathSync`
  (inside `FinderThread.prep`). `SFinderRequest` uses the same mechanism
  with its own job (handled, since the hook is on the 7-arg `prep`). The
  `Pathworker` threads still exist but get no jobs in MP.
- **View-gated sim** items in §1.
- **`GAME.afterTick` timing**: moved into the lockstep unit (once per Timer
  unit, before commands). Vanilla ran it per frame. If some subsystem relies
  on afterTick running *after* the sub-steps of the same unit, this is a
  subtle behavioural change (not a desync).
- **Float determinism across CPUs**: Java float/double math is
  deterministic (strictfp is default since 17), and `Math.*` is used by the
  game; `StrictMath` would be safer but nothing has shown up. If two machines
  with different CPUs desync at a `Math.pow/exp/log` user, that's the place.
- **The warm-up load** (`SaveTransfer`: a client whose first game is the MP
  load loads it twice) is a workaround for process-level singletons created
  by the first game. Finding the actual singleton(s) would remove ~10 s of
  join time. The divergence was in humanoid AI plan continuation
  (`AIModules.getNextPlan` vs `AIPLAN.resume`) at the second sub-step after
  the join; a static-field hasher (walk static fields of all loaded
  `settlement.`/`game.`/`world.` classes and hash primitive contents on both
  machines) would pinpoint it.
- `COUNTS` savable differs between machines by design (achievement
  thresholds from the local profile). Exclude it from any whole-save hash
  check you build.

---

## 4. Installation / packaging gaps

- **Agent auto-install did not fire on the second test PC** (mod enabled, game
  loaded, no `-javaagent` line written, no crash). Suspects: (a) the first
  game was loaded before the mod was enabled in that run; (b) `ScriptLoad`
  copies script jars (`copyAndMakeJarUrl`) and `AgentInstaller.ownJar()` may
  resolve to the copy, or the install threw and only logged to stdout, which
  nobody keeps. Now that `syxmp.log` exists the `agent auto-install: ...`
  line will show the reason. Make the installer robust: compute the jar path
  from the mods folder (`<data>/mods/SyxMultiplayer/V71/script/SyxMultiplayer.jar`)
  rather than the code source, and log loudly.
- **Steam-snap/Flatpak data dirs** on Linux (`~/snap/steam/common/.local/share/songsofsyx`,
  `~/.var/app/com.valvesoftware.Steam/...`) are not handled by
  `AgentInstaller.launcherSettings()`; the second tester's snap Steam happened to use the
  real `~/.local/share`, but don't rely on it.
- The restart notice is drawn by the HUD now; make it a proper dialog
  (`VIEW.inters().fullScreen.activate(...)`) with a "quit" button.
- `_Info.txt` VERSION and `MpVersion.VERSION` must be bumped together;
  `MpVersion.PROTOCOL` on any wire change (currently 5). Joiners with a
  different `MpVersion.VERSION` are rejected with VERSION_MISMATCH.
- Steam Workshop publishing: not looked at.

---

## 5. Steam

`syxmp/steam/SteamMpImpl.java` is complete in design: friends-only lobby +
rich presence `connect` string on host, friends-hosting list in the menu,
join via lobby owner over ISteamNetworking P2P (`SteamConnection`), invites
via `onGameLobbyJoinRequested`. It is **opt-in** (`steam=1` in
`<data>/settings/SyxMultiplayer.txt` or `-Dsyxmp.steam=1`) because
`SteamAPI.runCallbacks()` SIGBUS-crashed the JVM on the second tester's Steam-snap Ubuntu
machine (the game's own achievement helper process crashes the same way
there, so it is the snap's Steam library, not us).

To test it: a machine where Steam is a normal install, launched through
Steam (or `steam_appid.txt` with `1162750` in the game dir for terminal
launches), two Steam accounts that are friends. Expect to debug: P2P session
acceptance timing (first packet before `onP2PSessionRequest`), lobby data
visibility delay (`requestLobbyData`), and the `+connect_lobby` launch
argument being lost by the game's launcher re-exec (we only handle the
callback while the game is running; the menu's friends list is the fallback).

---

## 6. UX polish list

- Desync dialog instead of HUD text; "copy report" (RW writes a zip with
  both sync opinions and traces — we have the rolling `syxmp-rnd-desync.txt`
  when tracing is on; consider always keeping a small ring).
- Chat: Enter opens it (works), but a dedicated key would avoid clashing
  with the game's Enter uses; timestamps; player colours (cursor palette
  exists in `MpCursors`).
- Host settings UI (port, password, max players, pause-on-join) — the
  server supports them, only the port is exposed.
- Join progress bar (MpStatus already carries download %).
- Hide the vanilla "CLICK TO CONTINUE" during network loads for non-dev
  (done for MP loads via `finishReloadIfPending`; verify on slow machines).
- Player list: show `[desynced]` and ping (done), kick (not done).
- Windowed/placement issues seen in screenshots (joiner window rendered at a
  different size than the window) are the game's, not ours.

---

## 7. Code map of what session 2 added

```
syxmp/client/BattleSync.java     battle AI workers driven per sub-step
syxmp/client/PathSync.java       long-range path jobs computed synchronously
syxmp/client/Determinism.java    afterTick redirect, staging-layer guard
syxmp/client/VisualRnd.java      cosmetic RNG stream (CallRedirect targets)
syxmp/client/DetRnd.java         fixed-seed stream for instance tables (RANMAP), reset per load
syxmp/client/ClinitRnd.java      per-class stream for static initializers (all <clinit>)
syxmp/client/MenuDriver.java     per-frame work at the main menu (drain, menu install)
syxmp/sync/ObjectPaths.java      object addressing from static roots
syxmp/sync/AutoSync.java         generic setter sync (SYNC_AUTO command)
syxmp/sync/ToolSync.java         map tools (TOOL command)
syxmp/agent/AutoSyncTable.java   the list of hooked setters
syxmp/ui/*                       menu screen, in-game panel, HUD cursors, settings, status
menu/SyxMpMenu.java              the ONLY class outside syxmp.*: bridge into the vanilla menu
syxmp/steam/*                    Steam (opt-in)
syxmp/dev/*                      VerbTrace, RndTrace, EntTrace, SaveDiff
syx-multiplayer/e2e/*            two-instance harness and diff scripts
syx-multiplayer/package.sh       zip for distribution
```

Wire protocol facts: `Packets` enum (6-bit ids), `CommandType` (append only:
..., SYNC, DEBUG_TOOLS, SYNC_AUTO, TOOL), `ClientSyncOpinion` carries
(fingerprint, updateI, timer) per sub-step, SERVER_WORLD_DATA carries the
session `rngSeed` before the save blob. PROTOCOL = 5.

---

## 8. Suggested order for the next session

1. Read both `syxmp.log` files from the humans' session; fix the
   `UNSYNCED` list and any `DESYNC` with identical tick lists via the tracing
   loop (§1). Expect this to be mostly §2 work.
2. Play-test with `-Dsyxmp.trace=1` yourself (xdotool can drive the UI;
   screenshots via `xwd` + PIL, see CLAUDE.md) to fill `AutoSyncTable`.
3. Battle-view gating (§3) before anyone opens a battle.
4. Agent installer robustness (§4) — it is the first thing a new player hits.
5. Steam, on a machine where it can work (§5).
