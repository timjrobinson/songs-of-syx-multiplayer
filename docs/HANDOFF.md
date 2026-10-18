# Session handoff — 2026-10-18 (session 2)

State of the Syx Multiplayer project. Read `CLAUDE.md` first, then this.
(The 2026-08-21 session-1 notes follow below; everything in this section
supersedes them where they conflict.)

## What works now (verified live, two real game instances)

- **Join end to end**: host from a loaded save (menu or in-game), joiner over
  TCP: handshake → join point (every client, host included, saves+reloads at
  the same tick, clock frozen meanwhile — RW semantics) → world download
  (4MB) → load → catch-up → `world ready` → lockstep. Speed changes from
  either side are synced commands executed at the same tick on both.
- **UI**: main-menu "multiplayer" screen (host a saved game / join by
  address+port / Steam friends list), escape-menu "multiplayer" entry,
  in-game panel (status, players+latency, chat, host/leave/pause), HUD status
  line (click to open panel), Enter opens chat, remote player cursors on the
  map, join/leave notices.
- **Steam** (implemented, NOT yet testable here — Steam isn't running on the
  dev box): friends-only lobby + rich presence `connect` when hosting,
  friends-hosting list in the menu, join via lobby owner over ISteamNetworking
  P2P (`syxmp.steam.*`). steamworks4j + natives ship in the game jar; the game
  never inits SteamAPI in-process, so the mod owns it. App id 1162750. To
  test from a terminal launch, Steam must run and `steam_appid.txt`
  (1162750) must exist in the game dir.
- **Determinism catalogue so far**: fixed 1/16s sub-steps driven by the
  lockstep clock; RND reseeded per sub-step and per command; per-frame
  `GAME.afterTick()` moved into the lockstep loop (it does real sim work:
  maintenance consumption grid, world battle polling); pathfinder forced to
  its synchronous branch; room-placement staging layer (`PLACEMENT.embryo`)
  hidden from sim readers; sub-step accumulator saved; RND seeded
  deterministically at every MP game load (unsaved cosmetic state such as
  `TForest` sprite animators is initialized from RND and keeps drawing inside
  the sim tick — first desync found by the RND trace).
- **Generic setter sync (`AutoSync`)**: `AutoSyncTable` lists sim setters
  (IntImp.set, BOOLEANImp.set, room employment priorities, immigration...);
  UI-context calls are sent as `SYNC_AUTO` commands addressing the target by
  its **object path** from static roots (`ObjectPaths`, anchors for
  entities/rooms). Unsyncable calls are logged `UNSYNCED (local only)`.

## Determinism milestone (2026-10-18 19:26)

Two real instances (host + joiner, Elote example save, speed 1, nobody
touching the UI): **23 minutes, zero desyncs, identical `updateI` at every
clock line** (`timer=82200 updateI=54797693` on both). Per-savable state
hashes taken at the same tick match except `COUNTS` (per-user achievement
thresholds read from the local profile; nothing in the sim reads it).
The last two causes were: vanilla's five battle AI worker threads, which run
in every game (now driven synchronously from the sub-step, `BattleSync`), and
a too-broad visual-RNG wildcard (`*Anim*` caught `animal.Animal`).

## Load-phase determinism (the big lesson of session 2)

A joiner starts from LOADED state, so every client reloads at join points.
But a load is not deterministic by itself: GAME/SETT/VIEW construction and
the save load draw thousands of times from the global RND, and anything not
saved keeps its construction-time random value (Intervals.rans, TForest
sprite speeds, entity pool fields, FBoost.randomize...). Measures, in order
of the trace evidence (`e2e/diffload.sh` diffs the load-phase draws):

1. RND seeded from (sessionSeed, timer) at `GameLoader.getState` entry; the
   session seed is carried in SERVER_WORLD_DATA so the first load is right.
2. Reseeded again at `ILoadScreen.activate` (between GAME+VIEW creation and
   the save load): VIEW construction draws vary per client (battle editor
   generates random armies through sim constructors).
3. Per-process random tables built in static initializers get a fixed seed
   (`DetRnd`): TileUpdater's 64x64 update order, RANMAP.
4. Cosmetic classes that draw inside the sim tick or during load use a
   separate stream (`VisualRnd` via CallRedirect of RND.* in those classes):
   minimap, light flicker, audio, sprite/renderer/particle/anim classes,
   gore pools, whole view/menu/gui layers.
5. First-frame vanilla `afterTick` is skipped while reloading.

## Dev tooling (all off by default)

- `syx-multiplayer/e2e/run-pair.sh [jvm args]` — host + joiner, windowed side
  by side on the 4K monitor, autoload/autohost/autojoin/autounpause, logs in
  `e2e/logs/`. `kill-games.sh` waits for instances to exit (ports!).
- `-Dsyxmp.rndtrace=1` → `<logs>/syxmp-rnd.txt` per client: every RND draw's
  call site per sub-step (restarted at each join-point reload so both files
  align). `e2e/difftrace.sh` diffs host vs joiner from the first common
  sub-step — the first differing line IS the desync cause.
- `-Dsyxmp.trace=1` → `<logs>/syxmp-verbs.txt`: first UI-context call of
  every candidate sim method with call chain (player-verb discovery).
- Chat `/hash` → every client logs per-savable state hashes
  (`[SyxMP:hash]`), diff to find the diverged subsystem.
- Launcher settings in both homes set to windowed 1920x1080
  (SCREEN_MODE 2, WINDOW_WIDTH/HEIGHT 10, WIN_AUTO_ICONIFY 0).
- `build.sh` installs the jar atomically (rename) into both the real mods dir
  and `.mp-test-home` — safe while instances run.

## Next steps, in order

1. Desync loop: run the pair with `-Dsyxmp.rndtrace=1`, `difftrace.sh`,
   patch the cause (route cosmetic RND to a separate stream via CallRedirect
   of `RND.*` in that class, or seed/save state), repeat until a 10-minute
   soak at speed 1 and 5 stays in sync.
2. Verb coverage: play with `-Dsyxmp.trace=1`, add discovered setters to
   `AutoSyncTable`; tools/placement (`PlacableMultiTool.place` with area
   bitmap + `Placer` identity via object path), `RoomPlacer.create()` with a
   staging snapshot, one-offs (Deal.execute, PTech.levelSet, army orders...).
3. Steam live test (needs Steam running), then desync dialog/report bundle,
   battles (BattleThreads), protocol/version discipline, workshop packaging.

---

# Session handoff — 2026-08-21

State of the Syx Multiplayer project at the end of the first build session, for
whoever (human or agent) picks this up. Read `CLAUDE.md` first, then this.

## What exists and works

All committed on `main` (see `git log`). Everything below is **verified**, not
aspirational:

1. **Headless-verified** (run `syx-multiplayer/test.sh` — all passing):
   - Bytecode transformer against the real game jar: a hooked method
     (GameSpeed.speedSet) skips its body when the handler intercepts, runs
     vanilla otherwise; VIEW.update's `game.update(ds, slowDown)` call is
     rewritten to `TickController.gameUpdate`.
   - Full TCP join against a live relay server: protocol/username/join-data
     handshake, fragmented 300KB world transfer with progress, playing state +
     player list, command round-trip with server tick-stamping, advancing
     tickUntil window.

2. **Verified live in the real game** (Songs of Syx V71.44, two instances):
   - Agent loads via `-javaagent` in launcher `JVM_ARGS2`; patches apply on
     class load (`menu/Menu`, `view/main/VIEW`, `game/GameSpeed`,
     `settlement/entry/Immigration`).
   - Mod script discovered and instantiated ("script available: Syx
     Multiplayer"); sync registry + hooks install at game init.
   - Auto-quickload of a shipped example settlement (Elote) works.
   - **Hosting**: in-memory state capture of a real settlement — 32.3MB raw →
     4.2MB deflated in ~700ms — server up on :30599, host in playing state.
   - **The lockstep pipeline**: auto-unpause sent GLOBAL_TIME_SPEED through
     client → server stamp (timer 360) → echo → scheduled → executed at
     exactly that tick.
   - **The clock**: `timer=600/610 … 8400/8418` with a healthy ~10-16 tick
     buffer; updateI advanced exactly 160 sub-steps per 600 timer units =
     16 sub-steps/s at speed 1 (the design). Two separate runs produced
     IDENTICAL updateI at the same timer — load+tick is reproducibly
     deterministic so far.
   - **Join over TCP between two real instances**: connection accepted,
     username validated, join-data check `ok=true`.

## Where the last E2E run stopped

Run 3 (host + joiner, both real game instances) ended with BOTH processes
exiting **cleanly** (normal `[SNAKE2D] DISPOSING` shutdown, exit code 0)
immediately after the joiner's successful handshake. That is the
window-close/quit path — almost certainly the game windows being closed on the
desktop (they're 4K borderless on the user's screen; the user was at the
machine and about to leave; a real crash writes an error log and a non-zero
exit). **No evidence of a code failure at the world-transfer step — it has
simply never been reached in-game yet.** The next session should re-run the
same test when the desktop is free.

What HAS been proven about the transfer: the world blob path (fragmentation,
reassembly, apply) passes headless with 300KB; the real 4.2MB save capture
works; the missing piece is the live joiner receiving it, `GameLoader`-loading
it, and fast-forwarding (`TickController.simulate`) to the live tick — then
watching sync opinions for the first real desync report.

## How to run the two-instance E2E test

Host (uses the real user data dir; expects the Elote example save present in
`~/.local/share/songsofsyx/saves/saves/` — already copied there):

```sh
cd "/mnt/games/SteamLibrary/steamapps/common/Songs of Syx"
DISPLAY=:1 java \
  "-javaagent:$HOME/.local/share/songsofsyx/mods/SyxMultiplayer/V71/script/SyxMultiplayer.jar" \
  -Xms512m -Xmx4096m -Dfile.encoding=UTF-8 \
  -Dsyxmp.autoload=1 -Dsyxmp.autohost=1 \
  -cp "$HOME/.local/share/songsofsyx/mods/SyxMultiplayer/V71/script/SyxMultiplayer.jar:base/script/000_Tutorial.jar:base/script/001_Tutorial.jar:SongsOfSyx.jar" \
  init.MainProcess
```

Joiner (isolated data dir at the gitignored `.mp-test-home/`, already set up
with settings/mod/save/cache; launch AFTER the host logs "hosting on port"):

```sh
# same command plus:
  -Duser.home=<repo>/.mp-test-home \
  -Dsyxmp.autoload=1 -Dsyxmp.autojoin=127.0.0.1:30599 \
```

Timing observed: host boot+load ≈ 2.5min, hosting ≈ +1min; joiner boot+load ≈
5min. Use no/large timeouts and grep stdout for `SyxMP` lines. The launcher-
bypass requires the tutorial jars on -cp (ScriptLoad loads every class in
every script jar; missing jars = ClassNotFoundException crash).

Success criteria for the next run: joiner logs `world data: save …`,
`caught up to timer …`, `world ready`; both sides then log `clock:` lines with
the SAME updateI at the same timer; watch for `DESYNC` lines (the detector
works; real desyncs are EXPECTED until the determinism catalogue grows —
that's the next work front, not a failure of the harness).

## Machine state changed this session (intentional, documented)

- `~/.local/share/songsofsyx/settings/LauncherSettings.txt`: added
  `-javaagent` line to JVM_ARGS2, added `"SyxMultiplayer"` to MODS,
  set `DEVELOPER: 1`. Backup: `LauncherSettings.txt.bak-syxmp` beside it.
- `~/.local/share/songsofsyx/saves/saves/Elote-….save`: copied from the game's
  `base/data.zip` examples for quickload testing.
- `~/.local/share/songsofsyx/mods/SyxMultiplayer/`: the installed mod
  (recreated by `build.sh` any time).
- `.mp-test-home/` in the repo (gitignored): the joiner's isolated home.
- `tools/` (gitignored): Temurin JDK 21 + asm-9.7.1.jar (re-download: see
  build.sh / git history).
- `reference/` (gitignored): clone of rwmt/Multiplayer + extracted game
  sources. Recreate with:
  `git clone https://github.com/rwmt/Multiplayer reference/Multiplayer` and
  `unzip "<game>/info/SongsOfSyx-sources.jar" -d reference/syx-sources`.

## Next steps, in order

1. Re-run the E2E test undisturbed; get the joiner through world transfer +
   catch-up; confirm `world ready` and matching clock lines. Fix what breaks
   (likely candidates: GameLoader on the received save with the load-screen
   interrupter — may need suppressing `VIEW.inters().load.activate()`;
   sub-step counting during catch-up while the "CLICK TO CONTINUE" modal
   forces ds=0 — see docs/sos-internals.md on the interrupter gate; note
   TickController bypasses VIEW's ds so this needs verifying).
2. First determinism soak: both instances at speed 1 for ~10 min; collect
   DESYNC reports; start the determinism catalogue in PatchTable
   (docs/sos-internals.md hazards list — pathfinder sync branch first:
   `SPath.request` must always take the synchronous fallback in MP).
3. Grow synced verbs in SyxSync (pattern established with Immigration.admit):
   tools/placement first (docs/sos-internals.md §Player verbs), INTE settings
   second. Unsynced verbs desync when used — this is the long grind, same as
   RW-MP's tables.
4. UI: replace debug-panel buttons with a proper host/join menu + chat window
   + player cursors; join-from-main-menu (needs a MainThread drain driver at
   the menu — the Menu.update hook can host it).
5. Battles: pause/serialize BattleThreads (biggest hazard; see survey).
6. `AgentInstaller` path handling for Steam-workshop installs, protocol
   version discipline (`MpVersion.PROTOCOL`), and a proper desync report
   bundle (RW writes a zip; we log + pause).

## Gotchas discovered the hard way

- `pkill -f init.MainProcess` from a Claude Bash call kills the calling shell
  (pattern matches the parent bash -c). Filter by comm: see git history.
- ClientConnect sets `Multiplayer.session` before the socket exists —
  TickController must gate on `CLIENT_PLAYING` state (fixed, commit 9f1013b).
- `pauseOnJoin` injects a PAUSE_ALL before the joiner's own commands — tests
  must not assume queue head.
- Hash raw save bytes, never deflated (zlib output differs across JVMs).
- Launcher passes JVM_ARGS2 verbatim to the child JVM — that's the agent path;
  `-Duser.home` isolates a second instance (Linux data dir is
  `user.home/.local/share/songsofsyx`, `init/paths/Util.getLocal`).
