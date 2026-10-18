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

## Not done / open

1. Human play-test of everything above (battle orders, a world battle, a
   raid demand with both players clicking).
2. Still-local actions, now from item 3 of `docs/sync-coverage.md`:
   recruiting / mercenaries / create army, grant autonomy, Str renames,
   room copy with UI state, hospital/MoveOrderPull array slots.
3. Generated tactical battles ("engage") — design pass (above).
4. the second test PC was unreachable (ssh timeout); the jar must be shipped per
   CLAUDE.md "Working agreements". Both games must be restarted after any
   install (the running pair keeps the old jar mapped).
5. H3 items 3 (paused-step unsaved state), 4 (Steam) unchanged.
