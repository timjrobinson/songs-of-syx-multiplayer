# Syx Multiplayer — project notes

Goal: a co-op multiplayer mod for Songs of Syx that is a faithful architecture
port of the RimWorld Multiplayer mod (rwmt/Multiplayer) — deterministic
lockstep, command sync, dumb-relay server, save-transfer joining, sync-opinion
desync detection. "Syncs and communicates in all the same ways, just syncs
Songs of Syx data."

**Deep reference docs (read these before touching the sync engine):**
- `docs/HANDOFF4.md` — **START HERE**: session 4 (battle orders, world
  battle prompts, raid demand fix) and what is still open
- `docs/HANDOFF3.md` — session 3 results (170 synced verbs,
  determinism fixes, installer, chat commands)
- `docs/sync-coverage.md` — every UI→sim mutation point, how each is synced,
  and the list of still-local actions
- `docs/HANDOFF2.md` — session 2 work list (mostly executed in session 3;
  §1 desync workflow and §5 Steam notes still apply)
- `docs/HANDOFF.md` — session chronology, verified results, the determinism
  catalogue and dev tooling (sessions 1 and 2)
- `docs/rimworld-mp-architecture.md` — the RW-MP blueprint being ported
- `docs/sos-internals.md` — Songs of Syx internals survey: tick loop, save
  system, script runtime, player-verb map, stable IDs, determinism hazards

## Environment

- Game install: `/mnt/games/SteamLibrary/steamapps/common/Songs of Syx/`
  - `SongsOfSyx.jar` — the game (Java 16 bytecode, main class `init.Main`)
  - `info/SongsOfSyx-sources.jar` — **the game ships full Java sources**; extracted
    to `reference/syx-sources/` (gitignored). Always read these instead of guessing.
  - Game version: **V71.44** (`game/VERSION.java`)
  - Bundled JRE: Temurin 21 (Windows build — on Linux the game runs via
    Proton/Steam or system Java)
- User data dir (native Linux): `~/.local/share/songsofsyx/` (`mods/`, `saves/`, `settings/`, `logs/`)
- **Steam runs the game through Proton on this PC** (native menu fails here), so the
  REAL player data lives in the Wine prefix:
  `/mnt/games/SteamLibrary/steamapps/compatdata/1162750/pfx/drive_c/users/steamuser/AppData/Roaming/songsofsyx/`
  (`build.sh` installs the jar there too; its `LauncherSettings.txt` carries the
  `-javaagent:C:/users/steamuser/.../SyxMultiplayer.jar` line with forward slashes).
- Second test machine: the second tester's Ubuntu PC, native Steam (snap) but data in
  `~/.local/share/songsofsyx/`; `ssh user@<second-pc>` (key auth works);
  game dir `~/snap/steam/common/.local/share/Steam/steamapps/common/Songs of Syx`
  with its own bundled JRE 21 (no system java). LAN: this PC <this-pc-ip>.
- The mod writes its own log: `<data>/logs/syxmp.log` on every machine.
- Launcher settings: `~/.local/share/songsofsyx/settings/LauncherSettings.txt`
  (JSON-ish; has user-editable `JVM_ARGS2` array — our path to `-javaagent` if
  needed — plus `MODS` list, `DEVELOPER`/`DEBUG` flags)
- Build JDK: `tools/jdk21` (gitignored Temurin download). No system JDK — only JREs.
- RimWorld Multiplayer clone: `reference/Multiplayer` (gitignored), v0.11.5, protocol 54.

## Build & install

`syx-multiplayer/build.sh` — javac `--release 17` against the game jar,
jars it, installs to `~/.local/share/songsofsyx/mods/SyxMultiplayer/`
(`_Info.txt` + `V71/script/SyxMultiplayer.jar`). Mod folder layout and
`_Info.txt` format are per `info/MAKE_A_MOD.txt`.

## How Songs of Syx mods load (script mods)

- Script jars live in `<mod>/V<major>/script/*.jar`. The **launcher re-execs the
  JVM with all script jars prepended to `-cp`** (`init/Main.java:79-101`,
  `Proccesser.executeLwjgl`). Mod classes share the system classloader with the
  game → direct references + `setAccessible` reflection both work.
  (`ScriptLoad.urlList` is dead code; there is no agent, no custom classloader.)
- `ScriptLoad` scans jars for non-abstract `script.SCRIPT` implementations and
  instantiates them via no-arg constructor. The `SCRIPT` factory is cached
  statically per JVM; **a fresh `SCRIPT_INSTANCE` is created for every new game
  and every load** (`GAME.java:187` → `ScriptEngine.init`).
- Mod classes must NOT collide with game class names/packages. Our package: `syxmp.*`.
- Save key for script data is `jarFileName + "->" + className` — renaming the
  jar orphans script save data.
- `SCRIPT.forceInit() == true` injects the script into every game/save — what a
  multiplayer mod should use eventually (currently `isSelectable`).

## Songs of Syx internals — the load-bearing facts

(All paths relative to `reference/syx-sources/`. Line numbers are V71.44.)

### Threads and the frame pipeline

- GL thread (`main`): GLFW, buffer swap, frame pacing at monitor refresh rate.
- **`updater` thread: runs BOTH sim and render-command generation**, blocking on
  the GL thread once per iteration via `CORE.swapAndPoll()` (`snake2d/Updater.java`).
- Watchdog: if the GL thread sees no swap/job for 5s it dumps stacks; ~8 strikes
  kills the game (`CORE.java:253-274`). **Never block the updater thread on
  network waits** — pump `CORE.swapAndPoll()`/`checkIn()` or wait off-thread.
- Chain per frame: `Updater.run` → `VIEW.update(ds, slowDown)` (`view/main/VIEW.java:189`)
  → interrupter/UI updates (`:221-227`) → hotkeys → **`game.update(ds, slowDown)` at `:257`**.

### The sim tick

- `GAME.update(ds, slowDown)` (`game/GAME.java:241`): `speed = SPEED.update(slowDown)`;
  if `speed == 0` → every `GameResource.update(0)` runs and **`updateI++` anyway**;
  else `ds *= speed`, split into sub-steps of `max = 1/16f` (exactly 0.0625,
  a power of two — float-exact) plus a ragged remainder; `updateI++` per sub-step.
- If a modal `Interrupter` returns false from update, `VIEW` passes `ds = 0`
  with nonzero speed → **zero sub-steps, `updateI` does NOT advance**. Note the
  pause/modal asymmetry of `updateI`.
- `GAME.updateI()` is the tick counter; it is saved/loaded.
- `GameSpeed` (`game/GameSpeed.java`): `slowDown` comes from wall-clock frame
  timing (`Updater.java:61-66`) and throttles `actualSpeed` — **machine-dependent,
  must be bypassed in MP**. `GAME.update(double seconds)` static (`GAME.java:235`,
  used by `VideoMaker`) does `SPEED.speedSet(1.0); game.update(seconds, 0)` —
  the vanilla "manual tick" pattern.
- Battle view active → non-battle resources are skipped (`s.isBattle ||
  !VIEW.b().isActive()`, `GAME.java:260`) — local view state affecting sim; must
  be handled (all clients must agree or battles need special treatment).
- **`GameResource.all` is a fixed `ArrayList(16)` with 15 used — exactly ONE
  free GameResource slot** (`GAME.java:456`; `snake2d` ArrayList throws "I'm
  full!" when over capacity).
- Script hooks: `SCRIPT_INSTANCE.update(ds)` runs **first** among GameResources
  in *every sub-step* (ScriptEngine is the first-constructed GameResource —
  the "called after each tick" javadoc is wrong). `GAME.addAfterUpdate(ACTION)`
  fires once at the end of every `GAME.update` call, even 0-sub-step ones.
  `SCRIPT_INSTANCE.keyPush` is dead code (never called); `mouseClick`, `render`,
  `hover`, `hoverTimer` do fire via `ScriptEngine.callback`.

### RNG

- `snake2d/util/rnd/RND` — one global static `Random`; `RND.setSeed(int)` and
  `RND.seed()` exist. **Seed is not saved**; static-init seeds randomly per process.
- Determinism plan: reseed deterministically (e.g. per-tick from
  `hash(worldSeed, tick)`) and audit UI-thread consumption of `RND` (agent
  survey of `view/`/`menu/` usage — see report summary below).

### Save system (state transfer + desync hashing)

- Format: `Deflate(int32 rawLen || raw buffer)`; no checksums anywhere in the
  codebase. Keys are ASCII (chars truncated to 8 bits).
- `GameSaver` (`game/save/GameSaver.java`): public `save(Path dir, String name,
  boolean minified)`; savables framed as `key + length + bytes`, unknown keys
  skipped on load (self-healing).
- Loading **recreates the whole GAME** (`GameLoader.getState()` →
  `GAME.create(spec)` → `GAME.saver().load(fg)`); canonical programmatic load:
  `new GameLoader(path).set()`. All object references invalidate; our
  SCRIPT_INSTANCE is recreated. Suppress the "CLICK TO CONTINUE" screen
  (`GameLoader.java:57`, `VIEW.inters().load.activate()`) for network joins.
- In-memory save (no disk): reflect `GameSaver.save(FilePutter)` (private) +
  `FilePutter.buffer` field; `FilePutter(path, 120*SETT.TAREA)` — fixed
  ByteBuffer, ~31MB for a 512 map, no bounds checks (BufferOverflowException).
- **Full save touches the render loop** (~22 `CORE.swapAndPoll()` frame swaps
  via `RLoadPrinter`) — fine for join points, NOT usable per-tick. Desync
  hashing must be a narrow hand-rolled hash (updateI, TIME, treasury, entity
  positions...), with rare full-save hashes as deep verification.
- Save/load side-effect hooks: `GAME.saver().onBeforeSave/onAfterSave/
  onBeforeLoad/onAfterLoad`.
- **Autosave must be disabled in MP** (`S.get()` `autoSaveInterval` → 0);
  it fires wall-clock-based on one client only.

### Determinism hazards checklist (from the survey)

1. `slowDown`/`GameSpeed.actualSpeed` ramp — wall-clock; bypass.
2. Variable `ds` sub-steps + ragged remainder — force exact multiples of 1/16.
3. `Updater` drops accumulated time machine-dependently — irrelevant once we
   drive ticks by network budget.
4. Global RND, unseeded at start, not saved — reseed + audit.
5. **`game/battle/thread/BattleThreads` — 5 async worker threads reading/writing
   sim state on their own schedule. Biggest hazard. Likely: pause them and drive
   their work synchronously from the tick.**
6. UI mutates sim timing directly (`SPEED.speedSet/tmpPause/updateOnce` from
   12 view/ call sites) — intercept as TimeSpeed commands.
7. `GAME.Warn` pauses the game when developer/debug — force flags identical.
8. `GAME.Error` throws in release, warns in dev — force flags identical.
9. `VIEW.b().isActive()` gates resource updates (battle view) — see above.
10. `GAME.Cache` staleness is keyed on `updateI` — safe only if updateI
    advances identically everywhere (another reason to own the tick loop).

### Input & UI

- Input: GLFW callbacks buffer events; drained on updater thread via
  `Mouse.poll`/`KeyBoard.poll` → `VIEW.mouseClick`/`keyPush`. `MButt` is a
  global-mutable-state enum with `consumeClick()` — order-dependent.
- Click dispatch order: interrupter stack → script callback → uiManager → view.
- `VIEW.setKeyPoller` can hijack the keyboard; interrupters returning false
  freeze the sim (modal).
- Debug: `IDebugPanel.add(name, ACTION)` — register during game construction
  (e.g. `initBeforeGameInited`); panel opens via `VIEW.inters().debugpanel.show()`;
  cog button gated on DEVELOPER flag; F11 stats overlay.
- Views: `ViewSub.all` index is serialized in saves — never register a ViewSub
  before the vanilla three.

### Networking in the game

None. Zero sockets in sim code (only crash reporter + launcher browser links).
Steam integration is achievements/RPC only, no lobbies/P2P.

## RimWorld Multiplayer — the blueprint (what we're porting)

Full architecture map was produced from `reference/Multiplayer` (v0.11.5).
Essentials to preserve:

1. **Server = relay + clock, never simulates.** 30Hz server thread; `gameTimer`
   advances only if no client is >90 ticks behind; broadcast
   `ServerTimeControl(gameTimer, sentCmdsSnapshot, serverTimePerTick)`;
   `serverTimePerTick = max(client frame times)` — session paces at the slowest
   client.
2. **`tickUntil` + `sentCmds` double interlock** — client only advances into a
   tick window when it has both authorization and every command counted for it.
3. **Nothing mutates game state outside Tick()/ExecuteCmd().** UI actions are
   intercepted (`ShouldSync`/`InInterface` gate), serialized, cancelled locally,
   executed on server echo. Commands carry `(type, ticks, playerId, data)`
   (+factionId in RW).
4. **Sync registry**: handlers (SyncMethod/SyncField/SyncDelegate/SyncWorker
   serializers) registered in deterministic order; **syncId = index** in the
   version-sorted list — registration order is part of the wire protocol.
5. **Field watch pattern** for UI-written fields: prefix records old value,
   postfix rolls back + sends command; buffered (200ms stability) for sliders.
6. **Join = join point**: `CreateJoinPoint` command → every client saves+reloads
   at the same tick (also scrubs cache drift); host uploads; joiner loads the
   blob and fast-forwards through the command log to `tickUntil`.
7. **Desync detection**: 30-tick `ClientSyncOpinion` windows — RNG state (high
   32 bits) per tick + per command, compared between clients; on mismatch,
   collect stack traces & write a desync report bundle.
8. **Feedback separation**: everyone executes a command; only the issuer gets
   sounds/camera jumps/messages (`currentExecutingCmdIssuedBySelf`).
9. **Interface-only object ids**: UI-created ids come from a negative local
   counter so the shared id counter never diverges.
10. **Sessions** for shared dialogs (trade etc.): state object + [SyncMethod]
    mutations + proxy dialog rendering from session state; can pause the game.
11. **Freeze manager**: long blocking ops (map gen, join-point save) freeze the
    global clock for everyone.
12. 1-byte frame header: 6-bit packet id + 2-bit fragment state; 32MiB max
    fragmented payload (world transfer).

Wire protocol (packets/commands) is mirrored in `syxmp.common.Packets` /
`CommandType`. RW's LiteNetLib reliable-ordered UDP ≈ our TCP transport
(+ in-memory LocalConnection for the host loop, like RW's host).

## Port design decisions (settled)

- **The mod jar doubles as a `-javaagent`** (Premain-Class manifest, ASM 9.7.1
  bundled unrelocated under `org/objectweb/asm`). The agent is the Harmony
  equivalent: `MpTransformer` applies a declarative `PatchTable` at class load —
  call redirects and SyncTemplates entry hooks (`if (general(id, this, args))
  return default;`). Hook ids are premain-safe constants in PatchTable;
  handlers attach at game init (`Hooks.installAll`, `SyxSync.bind`) and fall
  through to vanilla when absent. `AgentInstaller` writes the -javaagent line
  into the launcher's JVM_ARGS2 (debug-panel button; restart required).
- **Tick model**: server Timer at nominal 60/s (`TickRate.TIMER_HZ`); each Timer
  unit a client accumulates 1.0 tick-through and runs 1/16s sub-steps per
  `timePerTick(speed)` (RW's ITickable math; SoS speeds 0/0.25/1/5/25/250).
  The agent redirects VIEW.update's `game.update(ds, slowDown)` call to
  `TickController.gameUpdate`; sub-steps go through the vanilla manual-tick
  entry `GAME.update(double)`. GameSpeed is neutralized (speedSet/togglePause
  → synced commands; tmpPause/updateOnce suppressed in MP).
- **RNG discipline**: reseed `RND` per sub-step from (sessionSeed, updateI),
  and per command. This absorbs ALL out-of-substep consumption (minimap,
  menus, hover UI) — no push/pop needed, a big simplification vs RW. UI
  callbacks that consume RND *and* mutate sim must become synced commands.
- **State transfer**: the game's own save pipeline, run in memory via
  reflection (`SaveTransfer.capture` → GameSaver.save(FilePutter) + buffer
  field; .save-compatible bytes). Apply = temp file + `new GameLoader(path)
  .set()`. Join points: every client captures at the same tick, host uploads;
  the raw-buffer FNV hash doubles as a deep desync check (hash raw, not
  deflated — zlib versions differ). Deviation from RW: non-joining clients
  don't reload at join points (documented; revisit if cache drift shows up).
- **Desync detection**: 30-tick `ClientSyncOpinion` windows; each sub-step and
  command fingerprinted by one post-execution `RND.rInt()` draw (with
  reseeding, that draw = f(seed, updateI, #draws consumed)). Host's opinion is
  the reference; server relays; mismatch → CLIENT_DESYNCED → pause.
- SoS has ONE settlement sim (no per-map async time) → no mapId on commands;
  RW's per-map machinery collapses to a single global tickable. Battles need a
  dedicated design pass (BattleThreads must become synchronous first).
- Target: co-op (shared settlement) first, like early RW MP; one faction.
- Sync registration order (SyxSync) and Packets/CommandType ordinals are wire
  protocol — append only, bump `MpVersion.PROTOCOL` on change.
- Java sources: package `syxmp.*`, compiled `--release 17`. Zero external
  deps besides bundled ASM.

## Code layout (syx-multiplayer/)

- `src/syxmp/SyxMultiplayer.java` — SCRIPT entry (forceInit; saves MP clock
  state into game saves; debug-panel buttons: host/join/pause/status/install
  agent; HUD render hook)
- `src/syxmp/common/` — wire protocol: Packets, CommandType, ScheduledCommand,
  TickRate, MpVersion, MpDisconnectReason
- `src/syxmp/net/` — ByteReader/Writer, ConnectionBase (6-bit id + 2-bit
  fragment header, 32MiB fragmented), MpConnectionState, TcpConnection
  (length-prefixed frames), LocalConnection (host's in-memory pair)
- `src/syxmp/server/` — the relay: MultiplayerServer (30Hz clock, stalls >90
  behind, paces at slowest client), CommandHandler (tick stamping + sentCmds),
  PlayerManager, WorldData (join points), FreezeManager, TcpServerTransport,
  state/ (Joining → Loading → Playing)
- `src/syxmp/client/` — Multiplayer (session root + shouldSync gate),
  TickController (the lockstep loop), CommandExecutor, MultiplayerSession
  (tickUntil/sentCmds interlock), MainThread, SaveTransfer, JoinPoints,
  HostUtil, ClientConnect, ClientCommands, Hooks, MpHud, state/
- `src/syxmp/sync/` — Sync (registry, syncId=index), SyncMethod,
  SyncSerialization + SyxSerialization (SoS workers), SyxSync (explicit
  registrations), AutoSync (generic setter sync; the table is
  `agent/AutoSyncTable`), ObjectPaths, ToolSync (map tools), FieldSync
  (PUTFIELD redirects), UiRedirects (generic containers at UI call sites),
  SyncActions (composite commands: events, deals, tribute, region buildings),
  BattleOrderSync (tactical orders by value), WorldBattleSync (world battle
  prompts: deterministic pause + decision commands)
- `src/syxmp/agent/` — SyxAgent (premain), MpTransformer (ASM: entry hooks,
  call redirects, PUTFIELD redirects, <clinit> RND), PatchTable,
  AutoSyncTable (METHODS + FIELDS), SyncTemplates, AgentInstaller
- `src/syxmp/desync/` — ClientSyncOpinion, SyncCoordinator, DesyncReport
- `src/syxmp/ui/` — MpMenuScreen (host/join + host settings), MpPanel (chat,
  players), MpHud, MpCursors, MpSettings, RestartDialog
- `test/HeadlessTest.java` + `test.sh` — no-game validation: every
  AutoSyncTable entry resolves against the shipped jar, transformer against
  real game classes (hooked method skips body), full TCP join handshake +
  fragmented world transfer + command round-trip against a live server. Run
  `./test.sh` after every change.

## Current status (2026-10-19, session 4)

Session 4 (`docs/HANDOFF4.md`): tactical battle orders and world battle
prompts are synced; the raid-demand pay/decline is one synced decision (the
session-3 tribute redirect had never linked — every CallRedirect replacement
is now resolved by the headless suite). Version 0.3.1, protocol 7. Not yet
play-tested by humans; the second test PC was offline (jar not shipped).

## Status at the end of session 3 (2026-10-18)

Verified on two real machines (session 2): join from menu or in-game, world
transfer, lockstep, cursors, chat, auto-resync. Session 3: sync coverage is
now broad (170 AutoSync verbs + 12 field writes + composite commands for
events/deals/tribute/region buildings; `docs/sync-coverage.md` lists what is
still local: tactical battle orders, world battle prompts, recruiting,
autonomy, renames, single-room copy). Battle-view gate, autosave and
Warn/Error are deterministic in MP. The intermittent real-play desync was
gore on the cosmetic RND stream (fixed); a 27-minute pair soak at speed 5
with 205 scripted synced actions then ran clean (paused-step experiment
desynced and is off; see HANDOFF3). Not yet play-tested by humans with the
new table. Steam is opt-in
and untested. Distributable: `syx-multiplayer/package.sh` → `dist/*.zip`;
version 0.3.0, protocol 6.

## Determinism rules (every change must respect these)

- Nothing may consume the shared `RND` outside a sub-step except through the
  per-sub-step/per-command reseed. New cosmetic RND users found by the trace go
  to `VisualRnd` via `PatchTable.VISUAL_RND_CLASSES` (CallRedirect). Audit any
  wildcard against the jar's class list first — `*Anim*` once matched
  `settlement.entity.animal.Animal` and desynced every animal. A class may only
  be cosmetic if NOTHING it randomizes is readable by the sim: gore is a Thing,
  `isGoodTileToStandOn` reads Things per tile, and gore on the cosmetic stream
  caused the intermittent real-play desync of sessions 2-3 (`docs/HANDOFF3.md`).
- Static initializers draw from `ClinitRnd` (automatic for all game classes),
  per-game instance tables from `DetRnd` (reset at every MP load), game
  construction from the two deterministic load seeds (`TickController.
  seedForLoad` at `GameLoader.getState`, `seedAfterCreate` at
  `ILoadScreen.activate`). A client's FIRST game in a process loads twice
  (warm-up) — see HANDOFF2 §3 for why.
- No wall-clock threads may touch sim state: battle AI workers run from the
  sub-step (`BattleSync`), long-range path jobs run inside `prep` (`PathSync`).
  Never "suppress" a worker: the pacing-citizens bug came from that.
- Per-frame sim work (`GAME.afterTick`) runs once per Timer unit from the
  lockstep loop, never per rendered frame.
- `Multiplayer.ticking` must be true around ANY vanilla sim execution
  (sub-steps, afterTick, the vanilla passthrough while connecting); otherwise
  the sync hooks mistake sim-internal setter calls for player input.
- Both machines must run the identical jar (`MpVersion.VERSION` is compared at
  join; bump `PROTOCOL` on any wire change). `CommandType`, `Packets`, the
  SyxSync order are append-only.

## Testing workflow

- `syx-multiplayer/e2e/run-pair.sh [jvm flags]` launches host + joiner windowed
  side by side (both homes are set to windowed 1920x1080; the user wants it
  that way). `JOINER_MODE=menu` tests the main-menu join. `watch.sh`,
  `difftrace.sh`, `diffdesync.sh`, `diffload.sh` do the analysis. Flags:
  `-Dsyxmp.rndtrace=1 -Dsyxmp.noresync=1 -Dsyxmp.autoact=1 -Dsyxmp.autospeed=5
  -Dsyxmp.autohash=1 -Dsyxmp.enttrace=1 -Dsyxmp.trace=1 -Dsyxmp.autounpause=1`.
- `kill-games.sh` waits until instances have exited (port 30599!). Kill the
  pair before the user hosts from Steam on this PC.
- Screenshots of a game window: `xwd -id <wid> -out f.xwd` then decode with
  PIL (header 100 bytes, BGRX); find the window with `xdotool search --name
  "Songs of Syx"` and match the pid's cmdline. The game's PrintScreen key did
  not produce files when sent via xdotool.
- `./test.sh` runs the headless suite AND installs the jar (atomically — safe
  while instances run; the old inode stays mapped). Never copy over the jar
  in place.

## Working agreements

- Commit incrementally at each meaningful milestone.
- When a build is meant for the players, deploy it everywhere: `./test.sh`
  (installs native + Proton prefix + `.mp-test-home`), `./package.sh` (zip),
  and `scp out/SyxMultiplayer.jar` to the second test PC
  (`~/.local/share/songsofsyx/mods/SyxMultiplayer/V71/script/`, via a temp
  name + `mv`). Tell the user both games must be restarted.
- The user is often at the machine using the test instances; their key
  presses show up as synced commands in the logs. Don't launch instances
  while they are playing for real.
- reference/ and tools/ stay out of git.
- When behavior of a game class matters, cite `file.java:line` from
  `reference/syx-sources` in comments/docs — the sources are version-exact.
- Run `syx-multiplayer/test.sh` before every commit.
