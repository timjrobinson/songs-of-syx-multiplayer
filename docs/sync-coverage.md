# Sync coverage — what the UI can do, and how each action reaches the sim

Written 2026-10-18 (session 3) from a complete read of `view/**`, every room
`Gui` class, `game/event`, `game/faction`, `game/raiding`, `world/**` in the
V71.44 sources (`reference/syx-sources`). Descriptors were verified with
`javap -s` against the shipped jar. The table itself lives in
`syx-multiplayer/src/syxmp/agent/AutoSyncTable.java`; this file is the map
behind it and the list of what is still local.

## Mechanisms (all applied by the agent, executed by `CommandExecutor`)

| Mechanism | Hook | Wire | Use |
|---|---|---|---|
| `AutoSync` entry hook | method entry in the declaring class (`AutoSyncTable.METHODS`) | `SYNC_AUTO key targetPath args` | any sim method called from UI whose target has an object path / id |
| `AutoSync` runtime-keyed | `CallRedirect` inside specific UI classes → `UiRedirects` | same, key = receiver's runtime class | generic containers (`RMapInt`, `ArrayInt`, `DataO`, `INT_OE`) used everywhere in the sim |
| `FieldSync` | `PUTFIELD` redirect inside UI classes (`AutoSyncTable.FIELDS`) | `SYNC_AUTO "field owner name desc"` | UI assigning a sim field directly |
| `ToolSync` | `PlacableMultiTool.place` + `place*` redirects | `TOOL` | every map tool |
| `SyncActions` | `CallRedirect` of the committing call in a UI handler | `ACTION kind payload` | composite handlers on UI-local objects |
| `SyxSync`/`SyncMethod` | explicit registration | `SYNC id args` | `Immigration.admit` (the RW-style path; order is protocol) |

Argument/target codecs (`SyxSerialization`): ENTITY (id), Room (index),
RoomBlueprintImp, Race, RESOURCE, HCLASS, HCLASS_RACE, PUNISHMENT, CRIME,
WGROUP, HTYPE, TECH, StatTraining, Noble, NobleOffice, Div, WArmy, Region,
Faction (incl. FactionNPC), HAI and Induvidual (via their humanoid's id).
Anything else is addressed by object path from the roots in
`ObjectPaths.ROOTS` (SETT, GAME, FACTIONS, WORLD, RACES, RESOURCES, STATS,
AIModule_Prisoner, DIP, ROPINION, RD, EVENTS, NOBLES; depth 12).

## Synced (by area)

- **Rooms**: `RoomInstance.activate/remove/upgradeSet`, `RESOURCE.remove`
  (upgrade cost), `MAINTENANCE.maintain` (downgrade), 19 `ROOM_*.autoEmploy`
  overrides, `*Instance.radiusRawSet`, `setIndustry` (refiner, workshop,
  hunter, pasture animal), `JobIterator.resetResourceSearch`,
  `RoomConsumption.enabledToggle`, `FarmInstance.changeTo`,
  `PastureInstance.slaughterAll`, export/hauler/import/station/transport/
  stockpile/supply settings, `MoveOrderPull.destSet`, `RBITImp.toggle/clear`,
  canteen/eatery/market/tavern `usesToggle`, punish permission
  (`BooleanOEImp.set`), prison punishments (`PrisonerData$1.set` +
  `Humanoid.interrupt`), guard duty (`ROOM_GUARD$1.set`), `ROOM_GATE.lock`,
  artillery orders, `CopierMass.copy`, placement (`RoomPlacer.init/create/
  popHistory/removeAllItems/removeArea`, `AutoWalls.set`, `UtilStructure.set`).
  Field writes: transport `prio`, breeder `prosecute/limitTotal/limitSpecies`,
  trainer `trainingLimit`, move order `pullLimit`.
- **Employment**: `RoomEmployment$Priority/$GRoupInt/$2.set`,
  `setPrioOnSkill/Fullfillment`, `RoomEmployments.setTargets`,
  `RoomEmploymentIns.neededSet`, `RoomEquip$Target.set`, all `IntImp`/
  `BOOLEANImp` setters reachable from a root.
- **Stats/laws**: `Curfew.setForADay`, `StatCrime.punishmentSet`, all five
  `StatsMultipliers$*` `mark/unmark` overrides, `PERMISSION$Permission.set`,
  `StatsFood$3.set`, `StatServiceImp$Perm.set`, `StatsHome.targetSet`,
  `EquipCivic.targetSet`, `StatsEducation.policySet` (both), `AgeType.limitSet`
  (both), `StatDecree$2.set` (rations, retirement), reproduction limit/
  settings (UI redirect), favourite (UI redirect), `setCustomName`, `nobleSet`.
- **Nobles**: `NOBLES.setOffice/ranksAllocate`.
- **Military (city)**: `DivInfo.menSet/raceSet/trainingSet/equipSet/
  bannerISet`, `EquipBattle.targetSet`, `Army.setDivAtOrderedIndex`,
  `WDivStoredAll.attach`, `ArmyTrainingInfo.sendOutWithoutTraining`,
  `DivSettings.musteringSet/moppingSet` + field writes from `view/battle/*`
  (formation, guard, running, fireAtWill, shouldNotMoveToFire, ammoI).
- **World**: `WArmy.setDestination/intercept/besiege/stop/raid(Z)/disband`,
  `ADDiv.disband/reassign`, `ADDivs.insert`, `WDiv*.bannerSet`,
  `WDivRegional$1` editor setters, artillery supply target, `RDOutputs.squeze`,
  `RD.setFaction` (abandon), `WBattles.regAttack`, `DipStance.set` (both),
  `EmiType.set` (court, emissaries), region edicts (UI redirect),
  `NPCRequest.expire/clear`, `OpsGifts.makeDeal`, `FCredits.inc`,
  `RaidingCurrent.clear/setAllyFight`, `PTech.levelSet`,
  `EventKiller.setSuspect`, `FBanner.bannerTypeSet`.
- **Composite (`SyncActions`)**: event choice (choiceSelect + actions + set,
  with the issuer's `EContext` serialized), `Deal.execute` from the diplomacy
  screen and from incoming proposals (`DealSave` snapshot replayed into
  `DIP.TMP()`), tribute payment (mirror of `Demand.pay`), region building
  accept (levels of the local preview buffer).

## Still local (will desync if used) — in priority order

1. ~~Tactical battle orders~~ — synced in session 4 (`BattleOrderSync`):
   `BattleOrder.Locked.set` (dest/task payloads shipped by value) and
   `Armies.initAndTeleport`. See `docs/HANDOFF4.md`.
2. ~~World battle prompts~~ — synced in session 4 (`WorldBattleSync`): the
   prompt opens in afterTick on every client and pauses the synced clock;
   close/auto/retreat/accept/occupy/abandon/puppet (+mercy) are commands
   addressed to the current prompt. **Engage** runs the generated tactical
   battle in lockstep (`GenBattleSync`): generation inside the command on
   every client, `BattleState.update` from the sub-step, Start / result
   close / retreat / restart as commands, and the `__beforeBattle` /
   `__battle` loads handled like join-point reloads. Auto-resolve RND is
   consumed inside the command (reseeded).
3. ~~Recruiting / mercenaries / create army~~ — synced in session 4
   (`ArmySync`): entry hooks on the click handlers (`ArmyRecruit$3`,
   `UIMerenaries$4`) ship the UI editor's values / the selected cards as
   RECRUIT / MERC_HIRE commands; `WArmyConstructor.create` inside
   `view/world/ui/army/List*` becomes ARMY_CREATE (the first army's random
   tile is picked on the issuer and travels as data; `openList` runs on the
   issuer when the command executes).
4. ~~Grant autonomy~~ — synced (`PlayInfo$9$1.exe` hook → AUTONOMY; the six
   steps incl. RND run inside the command). Abandon was already synced
   (`RD.setFaction` static AutoSync target).
5. ~~Str mutations / banner colours / map marks~~ — synced (`NameSync`):
   `Str.clear()/add()` are redirected only inside the five name-input widget
   classes (Army$2, PlayInfo$11, UIProfile$3, UIRoom$Detail$1,
   UIMiniHotSpots$Panel$1); the Str's owner is found by scanning player
   armies, regions, room instances and marks → NAME_SET(kind, index, text).
   `ColorImp.setRed/Green/Blue` inside `GColorPicker*` → COLOR_SET for
   marks, the player banner (BG/FG/border/pole) and division banners;
   unknown colours (profile colour masks) stay local. Marks: `UIMiniHotSpots$1
   .placeFirst` hook → MARK_ADD (random colour inside the command),
   `SettMark.remove` → MARK_REMOVE. The faction name (edited inside
   `StringInputSprite` itself) and the city division name copied by the
   division editor are synced too (`inputAdd*`/`inputClear` redirects send
   the full text; `UIDivEditor$3` uses the clear/add pair).
6. ~~Room copy with UI state~~ — synced (`RoomToolSync` + `ToolSync`):
   FIXED tool commands for `Copier$7` / `SavedPrintsPlacer$5` carry the
   source room, structure, walls flag, blueprint swap, rotation (and for
   prints the whole SavedPrint, which lives in the player's profile, not the
   save) and apply it before `place()`; `Copier.placeFirst` is local-only
   (it only sets up the tool). `UIHomeAssign` / `UIRoomRaceAssign` /
   `UIHomeOdd` (view-owned, unaddressable) are hooked per tile with their
   bit set / entity cursor (`ToolSync.HOOKED_TOOLS` keeps them off the TOOL
   path). Refurnish: `UIRoomPlacer.init(RoomInstanceImp)` hook → REFURNISH
   (room removal + `RoomPlacer.reconstruct` on every client; the tool opens
   on the issuer when the command executes).
7. ~~Hospital `fetch[]`, `RBITImp.or(RBIT.ALL)`, move-order slots~~ —
   synced: `hospital/Gui$1.clickA` hook → HOSPITAL_FETCH; `MoveOrderPullUI`
   auto-assign ($1), source placer (`$Placer.placeFirst`), remove
   (`$Detail$8`) and paste (`$Paster.place`) hooks → MOVE_*; `RBITImp.or`
   inside `MoveOrderPullUI*` → SYNC_AUTO with a new RBIT worker.
8. Developer-only buttons (never synced on purpose).

## Hazards found by the survey (fixed or noted)

- `RDBuilding` read the local building popup's draft levels
  (`RDLevelsTmp.level`) → redirected to the real level in MP (`Determinism.rdLevel`).
- `UIDipMessDeal.pactive()` rewrites `DIP.TMP()` during render on one client;
  the sim resets it before use — watch desync reports pointing at diplomacy.
- `GAME.SPEED.tmpPause()` from several popups is suppressed in MP (already).
- The raid demand message (`MessDemand`): session 3's `tributePay` redirect
  never linked (`NoSuchMethodError`: the replacement took `Object`, the
  transformer emitted the package-private `MessDemand$Demand` type) and the
  click's `GAME.raiders().current.clear()` ran on the issuer only. Session 4:
  `CallRedirect.objectReceiver`, a headless test that resolves every
  redirect replacement, and pay/decline as one TRIBUTE_PAY/TRIBUTE_DECLINE
  command that marks and closes the message on every client.
- `ToolMove.isPlacable` writes shared path scratch on hover (not sim state).
- `DealDrawfter.draft` consumes RND in UI context: harmless under per-sub-step
  reseeding (the draw happens outside the tick).
