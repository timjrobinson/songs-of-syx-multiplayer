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
