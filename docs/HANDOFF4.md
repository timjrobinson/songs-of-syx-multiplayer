# HANDOFF4 — session 4 (2026-10-19): what was done, what is left

Read `CLAUDE.md` first, then this file; `docs/HANDOFF3.md` is the previous
state. Version 0.3.1, protocol 7 (ACTION payload gained kinds 4-7).

## Done this session

### Tactical battle orders (`sync/BattleOrderSync`) — H3 open item 2.1
- Every order from the in-city battle view ends in `BattleOrder.Locked.set`
  (`dest` = `DivFormationImp`, `task` = `BattleOrderTask`; BattleOrder.java:60)
  or, while a generated battle is deploying, `Armies.initAndTeleport`
  (Armies.java:236). Both are entry-hooked (`HOOK_BATTLE_ORDER_SET`,
  `HOOK_ARMIES_TELEPORT`); in interface context the call is cancelled and
  shipped by value as ACTION kind 4/5; every client replays it into the
  division addressed by index. The battle AI workers (BattleSync, inside the
  sub-step) call `Locked.set` too and pass through.
- Payload codec is reflective over the V71.44 field layouts
  (DivPositionImp/DivFormationImp/BattleOrderTask, Rec x/y/width/height,
  Coo X/Y); it ships only the deployed coordinates plus the full dirMasks,
  mirroring what `DivFormationImp.copy` transfers. Headless test
  `testBattleOrderCodec` round-trips both payloads; `testBattleOrderHooks`
  proves both hooks are injected.
- `DevAuto` action 4 (every fifth scripted action) issues a STOP task and a
  dest re-set on division 0; a 5-minute pair run at speed 5 with it ran
  clean (probes equal, no desync).

### World battle prompts (`sync/WorldBattleSync`) — H3 open item 2.2
- Finding: with `Determinism.worldBattlePromptBusy` forced false (session 3),
  `WBattles.poll` (WBattles.java:70, from WORLD.afterTick) would re-create a
  player battle 1000 times and throw the moment one occurred in MP.
- The sim opens the prompt (`UIWBattlePrompt.battle/assist/lastStand/
  battleSally/siege/result×2`) inside afterTick or inside an earlier decision
  → same Timer unit on every client. Entry hook `HOOK_WBATTLE_PROMPT` records
  the pending object and sets `TickController.speed = 0` deterministically
  (vanilla's prompt is modal; the lockstep loop ignores modal ds). Buttons:
  `close.exe()` → CLOSE command; `engage/auto/retreat`, `accept`,
  `occupy/abandon/puppet` and the preceding mercy `incD` → one decision
  command (ACTION kind 6) carrying a content key (coords/men/result/region)
  of the current prompt; executed on all clients against their own copy,
  which hides the prompt and restores the clock. Dups and stale decisions
  are rejected by key / by "already decided". `worldBattlePromptBusy` is
  now `isBusty() || decisionPending()` so the issuer cannot poll the next
  battle before the others.
- **Engage auto-resolves in MP** with a GAME.Notify line: a generated
  tactical battle is a separate saved game (`BattleState.setGenerate`) and
  needs its own design pass (both clients would have to save/generate/
  reload in lockstep; the MP session must survive a vanilla GameLoader).
- Not play-tested: no world battle happened in the runs. The transformer
  test `testWorldBattleRedirects` checks the 7 hooks and the redirects in
  BattleBattle$1, Res$1, BattleSiege$3, Conquer$7.

### Raid demand (user-reported during the session)
- Symptom: one player declines, the other accepts → desync; the message
  stayed open on the other player. Joiner log: `NoSuchMethodError:
  SyncActions.tributePay(MessDemand$Demand)` — session 3's redirect
  replacement took `Object`, the transformer emits the package-private
  receiver type, so "Pay Up" threw on every click in MP. The click's
  `GAME.raiders().current.clear()` was also local-only.
- Fix: `CallRedirect.objectReceiver` (replacement takes `Object`),
  `testCallRedirectsResolve` (every redirect replacement must exist with the
  emitted descriptor — 61 checked), and the pay click's three redirected
  calls (`pay`, `RaidingCurrent.clear`, `Messages.hide`) become one
  TRIBUTE_PAY command (iteration, credits, resources) that pays, clears,
  marks `payed` on each client's copy of the message, closes it if shown
  and sends the thank-you message on non-issuers; decline becomes
  TRIBUTE_DECLINE (iteration) marking `declined` and closing the message.
  Second decisions are rejected by `RaidingCurrent.canPay(iteration)`.

### "Finish ALL still-local actions" (second half of the session)
Version 0.3.2, protocol 8 (ACTION kinds 8-24, TOOL FIXED payload carries a
state blob). `docs/sync-coverage.md` items 3-7 are now synced:
- `sync/ArmySync`: recruit (`ArmyRecruit$3.clickA` hook: UI editor values →
  RECRUIT, body replayed incl. supply withdrawal), mercenaries
  (`UIMerenaries$4.clickA` → MERC_HIRE with the selected card indices),
  create army (`WArmyConstructor.create` redirect in `List*` → ARMY_CREATE;
  `openList` redirect swallows the null and the issuer opens the list when
  the command executes), autonomy (`PlayInfo$9$1.exe` → AUTONOMY, six steps
  inside the command).
- `sync/NameSync`: `Str.clear()/add()` redirected only in the five name
  input widget classes, owner found by scanning armies/regions/rooms/marks
  → NAME_SET; `ColorImp.setRed/Green/Blue` in `GColorPicker*` → COLOR_SET
  (marks, player banner, division banners; the minimap realm colour is
  refreshed like UIFactionBanner.change does); map marks MARK_ADD (hook on
  `UIMiniHotSpots$1.placeFirst`; random colour inside the command) and
  MARK_REMOVE. The faction name Str (edited inside StringInputSprite) stays
  local — cosmetic.
- `sync/RoomToolSync`: per-tile hooks for `UIHomeAssign`, `UIRoomRaceAssign`
  (both carry their `HTypeBitsImp.data`; HGROUP and WGROUP variants share
  the layout), `UIHomeOdd` (entity cursor shipped, written back on the
  issuer); `hospital/Gui$1` fetch toggle; move orders (auto-assign flood,
  source placer, remove, paste — hooks on `MoveOrderPullUI$1/$Placer/
  $Detail$8/$Paster`), `RBITImp.or(RBIT.ALL)` via a new RBIT serialization
  worker; refurnish (`UIRoomPlacer.init(RoomInstanceImp)` hook →
  REFURNISH); copier / saved-print state: `ToolSync` FIXED commands now
  carry `RoomToolSync.captureState()` (source room, structure, walls,
  blueprint swap, rotation; for prints the whole SavedPrint since prints
  live in the player profile) applied before `place()`. `ToolSync.
  HOOKED_TOOLS` keeps the hooked tools off the TOOL path (they were
  unaddressable view objects → "UNSYNCED tool" before); `Copier.placeFirst`
  is local-only (it only sets up the tool and activated it on every client).
- Headless: `testUiStateHooks` checks all 13 hooked classes and the
  redirected call sites; the redirect-resolution test covers the new
  replacements. DevAuto actions 5-7 (rename room, map mark, home
  assignment) run in the pair soak.
- Still local, by design: developer buttons; the faction name; generated
  tactical battles (engage auto-resolves); profile colour masks (ColorPop,
  local profile data).

### Manual (generated) battles, faction name, division name (third part)
Version 0.3.3, protocol 9. "No desyncs for anything real players use":
- `sync/GenBattleSync`: the world prompt's **Engage** now really engages.
  ENGAGE executes `spec.engage()` on every client (BattleState.setGenerate:
  save `__beforeBattle`, generate in place, save `__battle`, open the battle
  view) and sets the synced speed to 0 (vanilla holds the game with the
  modal IDeploy). `BattleState.update` is redirected out of BattleView
  (per frame, wall-clock ds) and run from the lockstep sub-step. Start
  (IDeploy → `deploy()`), the result screen's close (`ILiveConclude$1.close`
  → `liveResolve`), retreat and restart are GEN_BATTLE commands; the
  IDeploy/ILiveConclude interrupters are recorded by hooks on their update()
  so non-issuers hide theirs (`ILiveConclude.update`'s per-frame
  `speedSet(1)` is skipped in MP — it would flood speed commands).
- In-session vanilla loads (`BattleStateExiter.exit` → `GameLoader(
  __beforeBattle).set()`, `reloadBattle` → `__battle`): hook on
  `GameLoader.set()` sets `Multiplayer.reloading` + `yieldForReload` when
  called from a command, so every client stops at the same point and
  `SaveTransfer.finishReloadIfPending` resumes at the same timer (same path
  as join points). The "CLICK TO CONTINUE" screen is skipped in a session.
  `SaveTransfer.afterVanillaLoad` re-pauses after a battle restart. The
  MP clock savable restores speed/accumulator from the loaded file (all
  clients wrote identical ones at the same tick) and never the timer.
  `WorldBattleSync.reset` keeps a prompt that was opened inside
  `doAfterSet` (the post-battle result) across the new-game reset.
- `Determinism.battleViewActive` now returns "a generated battle is
  running" (`VIEW.b().state() != null`, set by the command on every client)
  so the world/settlement resources are skipped during a generated battle
  exactly as vanilla does, deterministically.
- Faction name: `StringInputSprite` edits `FACTIONS.player().name` directly;
  its `Str.add(char)/add(CharSequence)/clear()` are redirected (apply
  locally, then NAME_SET with the full text when the Str is sim-owned).
  City division names: `UIDivEditor$3` clear/add → NAME_SET (OWNER_DIV).
- Not exercised by a run: no world battle happens in the soak save. The
  headless suite checks every hook and redirect (`testGeneratedBattleHooks`).
  First human test of engage: watch both logs for "engage: generating",
  "game load scheduled by a command", "state loaded; game recreated" and
  equal rndProbe values after the battle exit.

## Not done / open

1. Human play-test of everything above (battle orders, a world battle
   including engage → deploy → fight → result → accept, a raid demand with
   both players clicking).
2. Human play-test of the second-half items (recruit, mercenaries, create
   army, autonomy, renames, colours, marks, home/workplace assignment,
   oddjobber mover, hospital fetch, move orders, refurnish, room copy, saved
   prints). Most could only be exercised by hooks/redirect tests; grep both
   logs for `UNSYNCED|error executing|gone|ignored` after a session.
3. Generated tactical battles ("engage") — design pass (above).
4. The second test PC received 0.3.1 and 0.3.2; both games must be
   restarted after any install (a running game keeps the old jar mapped).
5. H3 items 3 (paused-step unsaved state), 4 (Steam) unchanged.

## Session 5 fix (2026-10-19): menu join crashed on 0.3.3

First real two-PC run of 0.3.3: the joiner crashed ~0.1s after clicking
Join from the main menu (`NullPointerException ... FACTIONS.self is null`
in `NameSync.ownerOf`). The StringInputSprite redirects (session 4, faction
name typed straight into the sim Str) run for *every* input widget,
including the MP menu's own address/name fields, and
`StringInputSprite.render` clears its scratch Str every frame. From the menu
a session exists before any game does, so `shouldSync()` was true and the
owner scan dereferenced FACTIONS/SETT/WORLD. Fix: `Multiplayer.gameExists()`
(`VIEW.existTemp()` — VIEW is built last in the GAME constructor,
`GAME.java:185`) gates both receiver scans, and the sprite's static `tmp`
buffer is skipped so rendering an input field no longer walks every room
instance per frame. `HeadlessTest.testNameSyncWithoutGame` reproduces the
crash (session set, no game). Lesson: any redirect placed on a generic
snake2d/util widget class runs in the main menu — guard with
`gameExists()`, not just `shouldSync()`.
