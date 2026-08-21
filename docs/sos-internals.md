# Songs of Syx internals — multiplayer-relevant survey (V71.44)

Source: `reference/syx-sources` (gitignored unzip of the game's shipped
`SongsOfSyx-sources.jar`). All file:line references are V71.44. 2443 Java files.

## Threads & frame pipeline

- GL thread (`main`): GLFW, swap, pacing at monitor refresh (`Sleeper.sync`).
- **`updater` thread runs sim AND render-command generation**, lockstepped to the GL
  thread once per iteration via `CORE.swapAndPoll()` (spin-wait).
- Watchdog: GL thread kills the game after ~8×5s stalls of the updater
  (CORE.java:253-274). Never block the updater on network waits.
- Chain: `Updater.run` → fixed-step accumulator (1/32 max step, 1/1024 min, surplus
  time DROPPED) → `VIEW.update(ds, slowDown)` (view/main/VIEW.java:189) →
  interrupters/UI (:221-227; any modal returning false forces `ds=0`) → hotkeys →
  **`game.update(ds, slowDown)` at :257 — the edge our agent patches**.
- `slowDown` = wall-clock frame time / 25ms — a performance governor feeding
  `GameSpeed.update`, which ramps `actualSpeed` machine-dependently. **Hard
  determinism blocker; bypassed in MP.**

## The sim tick

- `GAME.update(ds, slowDown)` (game/GAME.java:241-294): `speed=SPEED.update(slowDown)`;
  paused → all resources `update(0)` and **`updateI++` anyway**; else ds×speed split
  into 1/16s sub-steps (0.0625 — float-exact) + a ragged remainder; `updateI++` per
  sub-step. Modal-open (`ds=0`, speed≠0) → zero sub-steps, no `updateI++`. Note the
  pause/modal asymmetry.
- `GAME.update(double seconds)` static (:235) = `SPEED.speedSet(1.0);
  game.update(seconds, 0)` — the vanilla manual-tick entry (used by VideoMaker); our
  TickController drives sub-steps through it.
- Battle view active → resources with `isBattle=false` are skipped entirely
  (GAME.java:260,272,280; SETT.java:729-735): WORLD, FACTIONS, EVENTS, RAIDING,
  TOURISM, NOBLES + 9 settlement resources. **Local view state gates the sim** — must
  become a synced flag before battles are supported.
- **`GameResource.all` is a fixed ArrayList(16), 15 used — ONE free slot**
  (GAME.java:456). `add()` throws "I'm full!" beyond that.
- `GAME.addAfterUpdate/addOnInit/addOnViewInit/addBeforeGameStarts` are public
  lifecycle hooks. `GAME.Cache` staleness keys on `updateI`.

## Script mods

- Jars in `<mod>/V<major>/script/*.jar`; the **launcher re-execs the JVM with script
  jars prepended to -cp** (init/Main.java:79-101). Same classloader as the game;
  reflection works; no agent, no custom loader (`ScriptLoad.urlList` is dead code).
- `SCRIPT_INSTANCE.update(ds)` runs **first among GameResources in every sub-step**
  (ScriptEngine is the first-constructed resource; the "after each tick" javadoc is
  wrong). `keyPush` is never called; `mouseClick/render/hover/hoverTimer` fire via
  `ScriptEngine.callback` from VIEW.
- Fresh `SCRIPT_INSTANCE` per new game AND per load; statics survive return-to-menu.
- Script save key = `jarFileName + "->" + className` — renaming the jar orphans data.
- `forceInit()=true` injects the script into every game/save (what we use).
- `JVM_ARGS2` in `~/.local/share/songsofsyx/settings/LauncherSettings.txt` is passed
  verbatim to the game JVM → carries our `-javaagent`.

## Save system

- Format: `Deflate(int32 BE rawLen || raw buffer)`. No checksums anywhere. Keys are
  8-bit chars. Unknown keys skipped on load (self-healing framing: key + length).
- `GameSaver.save(Path dir, String name, boolean minified)` public;
  `save(FilePutter)` private (we reflect it); `load(FileGetter)` package-private.
- `FilePutter(path, 120*SETT.TAREA)` — fixed ByteBuffer (~31MB @512 map, ~126MB
  @1024), no bounds checks. Buffer reachable only via reflection.
- **Loading recreates GAME entirely** (`GameLoader.getState()` → `GAME.create` →
  `saver().load`). Canonical: `new GameLoader(path).set()`. Invalidates every object
  reference; recreates SCRIPT_INSTANCE; shows a "CLICK TO CONTINUE" interrupter
  (GameLoader.java:57) we'll want to suppress on network joins.
- Full save touches the render loop (~22 `CORE.swapAndPoll()` via `RLoadPrinter`) —
  fine at join points, never per-tick. Per-tick desync checks must be narrow.
- Autosave is wall-clock driven on each client (`AutoSaver`) — disable in MP.

## RNG

- `snake2d.util.rnd.RND`: ONE global static `java.util.Random`. `setSeed(int)` /
  `seed()` exist; **state is never saved**; seeded randomly at class init; reseeded
  only by world gen (`Generator.java:49`).
- Consumers outside the sim: minimap regeneration **per rendered frame** (192
  draws/frame when dirty — util/rendering/Minimap.java:82 via
  TileMap.afterTick), menu logo (6/frame), UI hover boxes, static initializers
  (TileUpdater: 8192 draws on first class touch; RANMAP: 16384/instance).
- **Our fix: reseed per sub-step from (sessionSeed, updateI)** — this re-anchors the
  stream before sim code runs, so ALL out-of-substep consumption (render, menu,
  hover) is absorbed. UI callbacks that consume RND *and* mutate sim (e.g.
  Court.java:539 assassinate) must become synced commands.

## Player verbs (interception map)

No command layer exists — UI callbacks call sim setters directly. ~90% of verbs
funnel through:

1. **Tools** (map-targeted): `view/tool/PlacableMultiTool.place` (:312-326, entry
   :272/:306/:170), `PlacableFixedTool` (:190,194), `PlacableSimpleTool` (:24,67),
   `PlacableSingleTool` (:87,94), `PlacableSimpleTileTool` (:56). Hidden command
   params read from globals: `PLACER_TYPE`+brush size, `Job.overwrite` (static
   bool!), `JOBS().planMode`, MOD-key state.
2. **`INT.INTE.set` / `BOOLEAN_MUTABLE.set`** settings objects bound to sliders and
   toggles — every room/trade/immigration/priority setting. GSliderInt's
   held-button repeat is FPS-dependent (util/gui/slider/GSliderInt.java:70-80) —
   intercept absolute values, never deltas.
3. **Explicit one-offs**: `RoomPlacer.create()` (:327 — the room-build commit),
   `settlement/job/Placer.place(tx,ty,Job)` (:64-92 — all designations),
   `Deal.execute` (diplomacy), `PTech.levelSet`, `EVENT_HANDLER.choiceSelect`,
   `RDLevelsTmp.accept`, `WArmy.setDestination/intercept/besiege/raid/stop/disband`,
   `BattleOrder.Locked.set`, `Immigration.admit/setWanted`, `NOBLES.setOffice`,
   `Curfew.setForADay`, `GameSpeed.*` (done).

Room placement staging (`PLACEMENT.instance`, `view/tool/PlacerArea.self`) is a
process-global singleton — the whole stage+commit must ship as ONE atomic command
(blueprint id, upgrade, area bitmap, structure, items), or staging stays local and
only `create()` syncs.

## Stable IDs

| Type | ID | Lookup | Notes |
|---|---|---|---|
| ENTITY | `id()` = index(18b)+generation(13b) | `SETT.ENTITIES().getByID(int)` | generation-guarded, stale→null. Excellent |
| Room | `index()` | `SETT.ROOMS().map.getByIndex` | slot recycled, NO generation — pair with a tile crosscheck |
| RoomBlueprint | `index()` | `SETT.ROOMS().all()` | deterministic (sorted files) |
| Region | `index()` | `WORLD.REGIONS().getByIndex` | stable |
| Faction | `index()` | `FACTIONS.getByIndex` | stable |
| WArmy | `armyIndex()` (short) | armies list | stable slot |
| WEntity | `index()` | — | **NOT stable (swap-remove) — never reference** |
| Race/RESOURCE/TECH/... | `index()` | ordered LISTs | deterministic from sorted data |
| Tile | `tx + ty*TWIDTH` | — | perfect |

## Determinism hazards (ranked)

1. 🔴 Variable timestep + wall-clock speed governor → replaced by TickController.
2. 🔴 **Battle AI threads** (game/battle/thread/BattleThreads: 5 SlaveThreads on
   wall-clock intervals writing div orders/positions read by the sim each tick —
   Div.java:282-296). Must be paused and driven synchronously per tick
   (`BattleThreads.pause()` + manual job execution) before battles can be synced.
3. 🔴 **Pathfinder thread** (settlement/path/thread/FinderThread): SPath.request
   (SPath.java:243-263) uses the async result IF READY else computes synchronously —
   machine-dependent route choice; plus an unsynchronized shared cost-map
   (PlayerHuristics) read/write race. Force the synchronous branch in MP.
4. 🔴 View-gated sim: `VIEW.b().isActive()` in GAME.update/SETT + births
   (StatsReproduction.java:221 — also gated on `SPEED.isPaused()`!), disease
   (disease/Updater.java:27), equipment drops (Humanoid.java:443), immigration
   branches. Replace with a synced battle/pause flag when battles land.
5. 🟠 `VIEW.RI()` (render-frame counter) keyed caches that WRITE region data
   (RDRaces.java:355-379, EUpdater, Emissaries.penaltyMul, RDLevelsTmp.cost).
6. 🟠 `GAME.Warn` pauses, `GAME.Error` throws-vs-warns depending on
   developer/debug flags — force flags identical across clients.
7. 🟠 `Intervals` never saves its accumulators and burns 128 RND draws at
   construction (sprite-only consumers, but shifts the stream — absorbed by our
   reseeding).
8. 🟢 Sim collections are index/array-based (snake2d sets) — essentially no
   hash-iteration nondeterminism. No `System.currentTimeMillis` in sim logic.

## Existing networking

None. Zero sockets in sim code (crash reporter + launcher browser links only).
Steam integration is achievements/RPC — no lobbies/P2P.
