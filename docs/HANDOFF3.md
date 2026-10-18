# HANDOFF3 — session 3 (2026-10-18): what was done, what is left

Read `CLAUDE.md` first, then this file. `docs/HANDOFF2.md` was the work list
this session executed; its sections are referenced below as "H2 §n".

## Done this session

### Determinism (H2 §1, §3)
- **Battle-view gate**: every sim read of `VIEW.b().isActive()` (GAME,
  GCOUNTS, Humanoid, SENTRY, EntryUpdater, SETT, WeatherMoisture, disease
  Updater, StatsReproduction, WBattles) and `UIWBattlePrompt.isBusty()` in
  WBattles is redirected to `Determinism.battleViewActive/worldBattlePromptBusy`
  → always "closed" in MP. Opening the battle view no longer forks the sim.
- **Autosave** never fires in MP (`GameSaver.autoSave` hook).
- **GAME.Warn / GAME.Error** take one path in MP (log + continue) regardless
  of the local DEVELOPER/DEBUG flags.
- **Enemy speed clamp** (GameSpeed caps at 25 while enemies are on the map)
  reproduced deterministically in `TickController`.
- **Vanilla GameSpeed mirrors the synced speed** after every Timer unit so
  the speed buttons and `isPaused()` readers outside the tick see the truth.
- **Region building popup** draft levels no longer reach the sim
  (`Determinism.rdLevel`).
- **Paused step experiment (negative result)**: running vanilla's paused
  frame (`GameResource.update(0)` + `updateI++`) once per Timer unit while
  speed is 0, to refresh `GAME.Cache` while building paused, desynced the
  pair within ~20 sub-steps of every unpause (maintenance tile updates
  diverged: `MRoom.validate/shouldPlace` differed between host and joiner,
  i.e. some state touched by `update(0)` is not restored identically by the
  join-point reload). Bisected with `-Dsyxmp.nomirror=1` (still desynced) and
  `-Dsyxmp.nopausedstep=1` (5 clean minutes). Left in behind
  `-Dsyxmp.pausedstep=1` for whoever wants to find the unsaved state; the
  UI-staleness-while-paused caveat from H2 §1 stands.

- **The intermittent "real play" desync (session 2's open item) is found.**
  Symptom: minutes into a session a citizen walks a different route on the
  two clients with no RND difference before it. Chased with the entity
  trace (`-Dsyxmp.enttrace=1`, now logging velocity, AI state and path node
  per humanoid plus every short-range (`S`), long-range (`P`) and
  component-flood (`C`) path request/result) and a 400-sub-step ring: the
  first difference is the *destination* of a short-range path request from
  `SFinderRND.get`, with identical component list and identical RND draws.
  `SFINDERS.isGoodTileToStandOn` rejects tiles with a Thing on them
  (`THINGS().getFirst`), and gore is a Thing placed at `cx + RND.rInt0(32)`
  (`ThingsGore.java:84,152`). Session 2 had moved `settlement/thing/Gore*`
  to the per-client cosmetic stream (`VisualRnd`), so blood landed on
  different tiles per client and biased the random pick. Fix: gore is back
  on the shared RND (its construction-time tables draw under the load
  seeds). Rule added to CLAUDE.md: a class may only go on the cosmetic
  stream if nothing it randomizes is readable by the sim — Things are.
- Dev tooling grew for this: `PathProbe` (S/P/C marks), `EntTrace` fields,
  `RndTrace.mark` into the ring, `-Dsyxmp.rndtrace.ring=N`.

### Sync coverage (H2 §2) — the main work
- Three complete surveys of the UI → sim mutation points (rooms, stats/laws/
  military, world/factions/events); condensed in `docs/sync-coverage.md`.
- `AutoSyncTable` grew from 15 to 170 entries + 12 direct field writes; a
  headless test (`testAutoSyncTableResolves`) fails the build if an entry
  does not name a real method/field of the shipped jar.
- New machinery: static-method targets; inherited-method resolution;
  `FieldSync` (PUTFIELD redirects); `UiRedirects` (generic container setters
  redirected only inside the UI classes that use them, keyed by the
  receiver's runtime class); `SyncActions` + `CommandType.ACTION` for the
  composite handlers (event choice, deal execute, tribute, region buildings);
  16 new serialization workers (registries, divisions, world armies, regions,
  factions, tech, nobles, HAI/Induvidual via humanoid id); 6 new object-path
  roots; path depth 12.
- Protocol 6, mod version 0.3.0 / `_Info.txt` 0.3.

### Install / UX (H2 §4, §6)
- `AgentInstaller`: asks the running game for its data dir, falls back to
  native + Steam snap + Flatpak locations; finds the jar in the mods folder
  (any `mods/*/V*/script/SyxMultiplayer.jar`) before the code source; writes
  atomically; appends a `JVM_ARGS2` block if missing; logs every step to
  `syxmp.log`; runs at menu start from the SCRIPT constructor (the menu
  instantiates script factories) and again at game creation; `uninstall()`
  + debug button.
- First-run **restart dialog** (`RestartDialog`, modal IPromtScreen with
  "quit now" / "keep playing").
- **Chat commands** on the server: `/players`, `/help`, host-only `/kick
  <name>` and `/joinpoint`; server notices show in the chat log; kicked
  clients see the reason.
- **Desync report** `logs/syxmp-desync-<time>.txt` (both opinions, clock,
  players, versions) written on detection.
- **Host settings** in the menu: password (also used when joining), max
  players, pause-on-join; persisted in `SyxMultiplayer.txt`; the in-game
  "host this game" button honours them.
- Player README rewritten (install, uninstall, what is synced).

### Verified
- Headless suite green (transformer, join flow, table resolution).
- Pair runs on this PC (`e2e/run-pair.sh -Dsyxmp.autoact=1 -Dsyxmp.autospeed=5`):
  with the paused step off, 5+ clean minutes at speed 5/1 with scripted
  synced actions, 198 classes patched, no patch failures. Before the gore
  fix, longer runs desynced after 3-9 minutes every time; after it, a
  27-minute lockstep soak (timer 97200, mostly speed 5, 205 scripted synced
  actions, 43 speed changes) ran with zero desyncs.

## Not done / open

1. **Human play-test with the new table.** Nobody has clicked through the
   UI with 170 hooks live. Expect: `UNSYNCED (local only)` lines for targets
   without a path (fix by adding a root or a worker), NPEs where a cancelled
   setter's return value is used by the UI (`BOOLEAN_OE.set` chains), and
   AutoSync executions failing on the receiver (`auto-sync execution
   failed`). Run both players with the log, then `grep UNSYNCED\|failed`.
2. **Still-local actions** — see `docs/sync-coverage.md` "Still local". The
   tactical battle orders are the big one for anyone who gets raided.
3. **Paused-step unsaved state** (above) if cache staleness while paused
   bothers players.
4. **Steam** (H2 §5) unchanged: opt-in, untested.
5. the second test PC must receive the new jar (`scp` per CLAUDE.md "Working
   agreements"); both games must be restarted; the agent line in her
   `LauncherSettings.txt` already exists from session 2.
6. The `ObjectPaths` index with depth 12 is rebuilt lazily (2 s cooldown);
   if the "object path index" log line shows TRUNCATED or >300 ms on big
   towns, raise `MAX_OBJECTS` or add anchors.

## Dev switches added

`-Dsyxmp.pausedstep=1` (experimental paused frame), `-Dsyxmp.nomirror=1`
(do not mirror the synced speed into GameSpeed).
