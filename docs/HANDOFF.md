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
