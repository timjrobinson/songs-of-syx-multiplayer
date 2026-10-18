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

1. **Tactical battle orders**: `BattleOrder$Locked.set(Copyable)` with
   UI-owned `BattleOrderTask`/`DivFormationImp` payloads (UISelection,
   BattlePlacer*), `DivDeployerUser.deploy(LIST<Div>,IIII)` and
   `deploy(Div,II)`, `Armies.initAndTeleport(LIST)`. Needs a codec for the
   payloads (`DivPositionImp` is SAVABLE) and a LIST<Div> codec. Division
   settings fields are already synced.
2. **World battle prompts** (`view/world/ui/battle/*`): `WBattleSpec.engage/
   auto/retreat`, `WBattleResult.accept([I[I)`, `WBattleSiege$Result.occupy/
   abandon/puppet(DD[I[I)`, mercy `incD`. The spec objects are created by the
   sim and pushed into the UI only; a "battle decision" command keyed by the
   pending battle is needed. Auto-resolve consumes RND.
3. **Recruiting** (`ArmyRecruit.java:94`, composite with RND via
   `WDivRegional.init` → `bannerSet(RND)`), **mercenaries** (`UIMerenaries:106`
   = `ADDiv.reassign` + `FCredits.inc`, both synced individually — verify),
   **create army** (`WArmyConstructor.create` from a placer; first-army
   location uses `WPATHING.rnd`).
4. **Grant autonomy** (`PlayInfo.java:244`): six-step sequence creating a
   faction, RND inside.
5. **Str mutations**: army/region/capital/division names (`Str.clear().add`),
   banner colours (`ColorImp` edited in place), map marks.
6. **Room copy with UI state**: `Copier$7.place` and `SavedPrintsPlacer$5.place`
   read the UI-local source/print/structure/walls/swap; `UIRoomRaceAssign`,
   `UIHomeAssign` carry a UI-local bitset; `UIHomeOdd` picks humanoids with a
   UI iterator. The tool place goes over the wire but the local state does not.
   `RoomPlacer.reconstruct` (refurnish) needs a custom command.
7. Hospital `fetch[]` (array store), `RBITImp.or(RBIT.ALL)` (static arg),
   `MoveOrderPull` array slots, `RDRaceEdict` double set is fine.
8. Developer-only buttons (never synced on purpose).

## Hazards found by the survey (fixed or noted)

- `RDBuilding` read the local building popup's draft levels
  (`RDLevelsTmp.level`) → redirected to the real level in MP (`Determinism.rdLevel`).
- `UIDipMessDeal.pactive()` rewrites `DIP.TMP()` during render on one client;
  the sim resets it before use — watch desync reports pointing at diplomacy.
- `GAME.SPEED.tmpPause()` from several popups is suppressed in MP (already).
- `ToolMove.isPlacable` writes shared path scratch on hover (not sim state).
- `DealDrawfter.draft` consumes RND in UI context: harmless under per-sub-step
  reseeding (the draw happens outside the tick).
