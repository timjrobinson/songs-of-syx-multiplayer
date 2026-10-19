# Handoff 5 — first real two-PC play-test of 0.3.5 (2026-10-19)

Start with `HANDOFF4.md` for the state before this session. This session was
driven by the first human play-test with the full sync table: the joiner
refurnished a room and clicked "construct"; 20 s after the host unpaused the
joiner desynced (sub-step value divergence, sparse RND differences = one
construction/citizen behaving differently). Reproducing it on the local pair
exposed a cluster of bugs in the room construction flow and one in the
diplomacy proposal message. All of them are fixed here; none needed a wire
format change beyond the FIXED tool state (protocol 10).

## What was wrong

1. **Furniture never synced correctly.** A FIXED tool command carried only
   tile coordinates (state only for the room copier / saved prints). Execution
   read the executing client's own shared item placer: item *group*
   (`RoomPlacer.item(int)`, set by the issuer's panel), *rotation* and *size*
   (`PlacableFixedTool` buttons, `UIRoomPlacer.init` from the hovered item).
   The host's placer had no group staged → `NullPointerException: this.group`
   on the other client, or a different item/rotation → divergent room. Verified
   by hand (hunters' room with furniture → joiner NPE → desync) before the fix.
   Fix: every FIXED command carries rot/size + a kind byte + the item group
   (`RoomToolSync.captureState/applyState`), applied via `placer.item(g)` then
   `rotSet/sizeSet` (after `PlacerItemSingle.set` restored its remembered size).
2. **Placer statistics are a UI-refreshed cache.** `UtilStats.update` is keyed
   on `updateI` and refreshed only by its accessors, i.e. by the issuer's open
   construction panel. `removeAllItems` reads the raw count (RoomPlacer.java:448)
   and `createProblem` the groups → the joiner removed the stockpile's crates,
   the host did not, the joiner's `StockpileInstance` constructor threw
   (`ArrayCooShort.set: 0 0`), command fingerprint differed. Fix:
   `RoomToolSync.refreshPlacer` (reflective `UtilStats.updatee`) before every
   `RoomPlacer` verb executes and after `reconstruct`.
3. **Wall material is a per-player profile default.** `PLACEMENT.load` calls
   `UtilStructure.read()` (profile key ROOM_CONSTRUCTION.STRUCTURE); only an
   explicit pick was synced. Indoor rooms therefore built with different
   materials per client. Fix: staging an indoor blueprint sends the issuer's
   structure (`UtilStructure.set` is a synced verb), and REFURNISH carries it
   (applied before `reconstruct`, which overrides it with the real building).
4. **Build menu crashed the issuer** (`NullPointerException` in `SShape.get`):
   `UIRoomPlacer.init` calls `placer.init(b, 0)` (deferred in MP) and opens the
   tool whose panel reads `placer.blueprint()` in the same click. Also the
   pool/canal placable re-stages the placer on every hover. Fix: `AutoSync.
   stagePlacer` runs the staging locally at once (nested setter hooks
   suppressed) *and* as a command (idempotent); re-staging an unchanged empty
   placer sends nothing. The construct button's second `create` (Config.java:
   140) is skipped on every client when nothing is staged. Pools/canals:
   `UIRoomPlacer$PlacerItemSingleArea` is a hooked tool (per-tile FIXED).
5. **Hovering another player's plan crashed** (`UIRoom.hover`: the placer's
   `Instance` has no icon). `ToolDefault`'s `UIRooms.hover/click` are redirected
   to `UiRedirects.roomsHover/roomsClick`, which ignore planning instances.
6. **Repeated fixed placements threw** (`MapDataF.java:175`): the tool keeps
   calling `place()` while the button is held and the local call is cancelled.
   Execution now skips when `placableWhole` fails — same check on every client.
7. **Deal proposal message.** Accept executed the deal everywhere but closed
   the message on the issuer only; the other clients' copies stayed open and
   kept rewriting `DIP.TMP()` every frame through the generic IntImp setter
   hook — 185,792 commands from one client in a few minutes, then a desync.
   Fix: `DIP#s.dealTmp.*` writes are local-only (`AutoSync.LOCAL_TARGETS`);
   accept/decline are one DEAL_ACCEPT/DEAL_DECLINE command (DealSave,
   happiness, faction + creation second) that executes the sim part and marks
   + closes every client's copy of the message.

8. **Construction panel showed 0 workers / efficiency / output while paused**
   and refused to construct. The placer caches (`UtilStats`, `UtilExtraCost`,
   `PlacerDoor`, `UtilStructure.roofs`) are keyed on `GAME.updateI()`, which
   vanilla advances every frame even while paused (`GAME.update(0)`) and MP
   never advances while paused. `RoomToolSync.invalidatePlacerCaches` resets
   the four tick keys once per rendered frame in MP (same recompute rate as
   vanilla running); the synced verbs still force-refresh at execution.

9. **Auto-walls flood with odd parity.** `PlacerItemArea.renderPlaceHolder`
   brackets its preview with `autoWalls.set(false)` / `set(b)`: two synced
   commands per hovered frame (1,289 between staging and construct in the
   second two-PC test, an odd number — the last one was `set(false)`), while
   the issuer's own calls were cancelled. The host built the hunters' lodge
   without walls, the joiner with them → desync 6 s after unpausing. The
   two calls inside `renderPlaceHolder` are redirected to
   `UiRedirects.autoWallsLocal` (local only); the toggle button stays synced.

## The two-PC desync (found after eleven traced runs)

Every local pair run was clean while the two real PCs desynced ~7 s after
unpausing, always as one worker's AI decision. The chase, in order, each
step a real bug: plan mode (10), the UI-refreshed boosters (11), the
process-wide employee-list cache (12), and finally the cause (13).

10. **Plan mode is per client.** `JOBS().planMode` is a plain boolean
    toggled by the Planning button (Options.java:82), never saved or synced;
    a new construction reads it for its `active` flag (ConstructionInstance.
    java:145) and `Placer.place` for the job state. Carried with every TOOL
    and SYNC_AUTO command next to `Job.overwrite` (also newly carried: job
    placement consults it, Placer.java:56) and executed with the issuer's
    values. Protocol 11/12.
11. **Lazily refreshed class-level boosters.** `RoomUpgrades`, `RoomIrrigated`,
    `ROOM_WATER` (`vGet(HCLASS_RACE)`, 120-tick `ci` cache), `ROOM_POLICE`
    (per tick) and `StatsMultipliers.maxAmount` refresh on whichever access
    comes first, a UI panel or the sim; each client's cached value had a
    different age. `GAME.updateI()` inside those classes is redirected to
    `Determinism.uncachedUpdateI` (always stale in MP → pure recompute).
12. **Process-wide tick-keyed caches.** `RoomEmploymentIns.employees(ins)`
    (static `employeesI/employeesO`), `EUpdater.aI`, the world-region supply
    and race caches: a UI read fills them at the render's updateI and the
    next sub-step (same updateI; commands run in between, and updateI never
    moves while paused) trusts them. `CacheReset.invalidate()` before every
    sub-step and after every command.
13. **Titles: per-player meta-progression in the simulation.** `PTitles`
    reads `saves/profile/Titles2.txt` (PTitles.java:100-130): the races each
    title was unlocked with. Potency `0.5 + 0.5*raceValue/races`
    (PTitles.java:140) multiplies every worker's production and consumption
    through the "Titles" booster (`IndustryUtil.calcProductionRate`). The
    host had ARTISAN with two races, the joiner none: the same worker
    produced at 0.133 vs 0.100, a workshop input pile crossed its next whole
    unit one cycle earlier on one PC, and the fetch-or-work decision
    diverged. `TitleSync`: the host's title data travels with the world data
    (`WorldData.hostTitles`, protocol 13) and is applied after every load in
    the session. Verified: the first clean two-PC session of the day.

Diagnostics that made this possible (all dev-only, behind flags):
`-Dsyxmp.enttrace=1` now logs the full AI state/module/plan bytes, work
activations and cycles (`WA`/`WR`), the deposit job's tile data (`DB`/`DR`),
and every booster value in the production rate (`PR`); `-Dsyxmp.hashevery=N`
hashes every savable (and each SETT city resource, TMAP layer and two unsaved
maps) every N timer units on the host; the host dumps its trace ring when a
desync is reported or a player disconnects; the resync waits a second so the
report survives the Steam close. `DevAuto`: `-Dsyxmp.autobuild=1` (joiner
builds a hunters' lodge: stage, area, items, doors, construct),
`-Dsyxmp.autorefurnish`, `-Dsyxmp.autocamera=x,y`, `-Dsyxmp.autounpause.delay`,
`-Dsyxmp.autohost.nounpause`. Lesson for the next one: when the local pair is
clean and the two PCs are not, the difference is per-machine input, and the
hash/entity/booster traces find it in a few runs.

## Verification

- `test.sh` headless suite (all redirect replacements resolve, hooks apply).
- Local pair, joiner-side scripted refurnish (`-Dsyxmp.autorefurnish=<index|
  -1 area room|-2 indoor room>`, `DevAuto`): refurnish → removeAllItems → place
  a rotated/resized item → construct, then 5 minutes at speed 25. Before the
  fixes: desync at the create (bug 2) or on the item (bug 1); after: clean.
- By hand on the pair during the runs: farm from the build menu (bug 4),
  hunters' room with furniture (bug 1/6), deal proposal accept (bug 7).

## Still open / watch for

- Other "UI reads what MP deferred" sites: any panel built right after a
  synced staging verb. The pattern of bug 4 (local-now + command, idempotent)
  is the template.
- Other profile-default placer settings: `AutoWalls.on` is a plain field
  (default true, synced on toggle) — fine; `buildOnWalls` likewise.
- `DevAuto` autorefurnish needs a room whose items fit; the stockpile's 7x2
  crate found no spot in the test save (harmless: logs "no spot").
- The joiner's room lookup by `SETT.ROOMS().ins()` sees only 10 instances in
  this save (`map.getByIndex` is the reliable lookup; indices are restored by
  Java serialization on load, RoomsMap.java:89, so they are stable).
