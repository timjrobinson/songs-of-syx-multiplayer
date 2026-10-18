package syxmp.agent;

/**
 * Simulation setters whose UI-context calls become synced commands
 * generically (see syxmp.sync.AutoSync): the target instance is addressed
 * by its path in the object graph from the game's static roots (or by
 * entity id / room index), so no per-method resolver code is needed. Built
 * from a full survey of the V71.44 view/ code (docs/sync-coverage.md) and
 * grown by the verb tracer (-Dsyxmp.trace=1).
 *
 * Format: "owner/internal/Name methodName (desc)Ret" — the DECLARING class
 * (the bytecode hook is per class; overrides are listed separately). The
 * wire carries the string key, not the index, so order is not protocol.
 * Premain-safe (no game classes).
 */
public final class AutoSyncTable {

    public static final int FIRST_HOOK_ID = 1000;

    public static final String[] METHODS = {
        // ---- Generic value holders bound to sliders/checkboxes all over the UI
        "util/data/INT$IntImp set (I)V",
        "util/data/BOOLEAN$BOOLEANImp set (Z)Lutil/data/BOOLEAN$BOOLEAN_MUTABLE;",
        // ---- Room employment (per room type): priority, group caps
        "settlement/room/main/employment/RoomEmployment$Priority set (I)V",
        "settlement/room/main/employment/RoomEmployment$GRoupInt set (Linit/type/WGROUP;I)V",
        "settlement/room/main/employment/RoomEmployment$2 set (Linit/type/WGROUP;I)V",
        "settlement/room/main/employment/RoomEmployment setPrioOnSkill ()V",
        "settlement/room/main/employment/RoomEmployment setPrioOnFullfillment ()V",
        "settlement/room/main/employment/RoomEmployment setPrioOnSkill (Linit/type/WGROUP;)V",
        "settlement/room/main/employment/RoomEmployment setPrioOnFullfillment (Linit/type/WGROUP;)V",
        "settlement/room/main/employment/RoomEmployments setTargets ()V",
        "settlement/room/main/employment/RoomEmploymentIns neededSet (I)V",
        "settlement/room/main/employment/RoomEquip$Target set (I)V",
        // ---- Room placement staging (shared, one planner at a time) and commit
        "settlement/room/main/placement/RoomPlacer init (Lsettlement/room/main/RoomBlueprintImp;I)V",
        "settlement/room/main/placement/RoomPlacer create ()Lsnake2d/util/datatypes/COORDINATE;",
        "settlement/room/main/placement/RoomPlacer popHistory ()Z",
        "settlement/room/main/placement/RoomPlacer removeAllItems ()Z",
        "settlement/room/main/placement/RoomPlacer removeArea ()Z",
        "settlement/room/main/placement/RoomPlacer$AutoWalls set (Z)Lutil/data/BOOLEAN$BOOLEAN_MUTABLE;",
        "settlement/room/main/placement/UtilStructure set (Lsettlement/tilemap/terrain/TBuilding;)V",
        "settlement/room/main/placement/UtilStructure set (II)V",
        "settlement/room/main/copy/CopierMass copy (IIIII)V",
        // ---- Immigration
        "settlement/entry/Immigration setWanted (Linit/race/Race;I)V",
        "settlement/entry/Immigration setHigher (Linit/race/Race;I)V",
        // ---- Room instances: lock, delete, upgrade/downgrade, auto-employ, radius
        "settlement/room/main/RoomInstance activate (Z)V",
        "settlement/room/main/RoomInstance remove (IIZLjava/lang/Object;Z)Lsettlement/room/main/TmpArea;",
        "settlement/room/main/RoomInstance upgradeSet (I)V",
        "init/resources/RESOURCE remove (ILgame/faction/FResources$RTYPE;)I",
        "settlement/maintenance/MAINTENANCE maintain (II)V",
        "settlement/room/food/pasture/ROOM_PASTURE autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/health/physician/ROOM_PHYSICIAN autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/service/breeder/ROOM_BREEDER autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/service/hygine/bath/ROOM_BATH autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/service/lavatory/ROOM_LAVATORY autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/service/food/eatery/ROOM_EATERY autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/service/food/tavern/ROOM_TAVERN autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/service/pleasure/ROOM_PLEASURE autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/service/food/canteen/ROOM_CANTEEN autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/service/barber/ROOM_BARBER autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/service/market/ROOM_MARKET autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/military/supply/ROOM_SUPPLY autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/infra/export/ROOM_EXPORT autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/infra/janitor/ROOM_JANITOR autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/infra/station/ROOM_STATION autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/infra/hauler/ROOM_HAULER autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/infra/inn/ROOM_INN autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/infra/transport/ROOM_TRANSPORT autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/infra/stockpile/ROOM_STOCKPILE autoEmploy (Lsettlement/room/main/Room;Z)V",
        "settlement/room/infra/export/ExportInstance radiusRawSet (B)V",
        "settlement/room/infra/hauler/HaulerInstance radiusRawSet (B)V",
        "settlement/room/infra/builder/BuilderInstance radiusRawSet (B)V",
        "settlement/room/infra/transport/TransportInstance radiusRawSet (B)V",
        "settlement/room/infra/stockpile/StockpileInstance radiusRawSet (B)V",
        "settlement/room/military/supply/SupplyInstance radiusRawSet (B)V",
        // ---- Industry / recipe / consumption
        "settlement/room/industry/refiner/RefinerInstance setIndustry (I)V",
        "settlement/room/industry/workshop/WorkshopInstance setIndustry (I)V",
        "settlement/room/food/hunter/HunterInstance setIndustry (I)V",
        "settlement/room/food/pasture/PastureInstance setIndustry (I)V",
        "settlement/room/main/job/JobIterator resetResourceSearch ()V",
        "settlement/room/industry/module/consumption/RoomConsumption enabledToggle (Lsettlement/room/industry/module/IndustryResource;Lsettlement/room/industry/module/ROOM_IDATA_INSTANCE;Lsettlement/room/main/RoomInstance;)V",
        // ---- Food rooms
        "settlement/room/food/farm/FarmInstance changeTo (Lsettlement/room/food/farm/ROOM_FARM;)V",
        "settlement/room/food/pasture/PastureInstance slaughterAll ()V",
        // ---- Logistics rooms
        "settlement/room/infra/export/ExportInstance resourceSet (Linit/resources/RESOURCE;)V",
        "settlement/room/infra/export/ExportInstance fetchingSet (Z)V",
        "settlement/room/infra/export/ExportInstance prioSet ()V",
        "settlement/room/infra/hauler/HaulerInstance setResource (Linit/resources/RESOURCE;)V",
        "settlement/room/infra/hauler/HaulerInstance fetchingSet (Z)V",
        "settlement/room/infra/hauler/HaulerInstance prioSet ()V",
        "settlement/room/infra/hauler/HaulerInstance storingSet (Z)V",
        "settlement/room/infra/importt/ImportInstance allocate (Linit/resources/RESOURCE;)V",
        "settlement/room/infra/station/StationInstance allocate (Linit/resources/RESOURCE;I)V",
        "settlement/room/infra/transport/TransportInstance fetchingSet (Z)V",
        "settlement/room/infra/transport/Cart resourceSet (Linit/resources/RESOURCE;Lsettlement/room/infra/transport/TransportInstance;)V",
        "settlement/room/infra/stockpile/StockpileInstance fetchingSet (Z)V",
        "settlement/room/infra/stockpile/StockpileInstance prioritizeToggle ()V",
        "settlement/room/infra/stockpile/StockpileInstance storingSet (Z)V",
        "settlement/room/infra/stockpile/StockpileInstance allocateCrate (Linit/resources/RESOURCE;I)V",
        "settlement/room/infra/stockpile/StockpileInstance setSpecialAmount (Linit/resources/RESOURCE;I)V",
        "settlement/room/military/supply/SupplyInstance fetchingToggle ()V",
        "settlement/room/military/supply/SupplyInstance prioritizeToggle ()V",
        "settlement/room/military/supply/SupplyInstance allowedToggle (Linit/resources/RESOURCE;)V",
        "settlement/room/military/supply/SupplyInstance reset ()V",
        "settlement/room/infra/logistics/MoveOrderPull destSet (Lsettlement/room/main/RoomInstance;)V",
        "init/resources/RBIT$RBITImp toggle (Linit/resources/RESOURCE;)V",
        "init/resources/RBIT$RBITImp clear ()V",
        // ---- Service rooms
        "settlement/room/service/food/canteen/CanteenInstance usesToggle (Linit/resources/ResG;)V",
        "settlement/room/service/food/eatery/RoomDistribution usesToggle (Linit/resources/RESOURCE;Lsettlement/room/main/RoomInstance;)V",
        "util/data/BOOLEANO$BooleanOEImp set (Lsnake2d/util/sets/INDEXED;Z)Lutil/data/BOOLEANO$BOOLEAN_OE;",
        // ---- Law rooms / prisoners / guards / gate
        "settlement/entity/humanoid/ai/types/prisoner/PrisonerData$1 set (Lsettlement/entity/humanoid/ai/main/HAI;Linit/type/CRIME_PUNISHMENTS$PUNISHMENT;)V",
        "settlement/entity/humanoid/Humanoid interrupt ()V",
        "settlement/room/law/guard/ROOM_GUARD$1 set (Lgame/battle/div/Div;Z)Lutil/data/BOOLEANO$BOOLEAN_OE;",
        "settlement/room/infra/gate/ROOM_GATE lock (IIZ)V",
        // ---- Artillery (room instances commanded from the battle UI)
        "settlement/room/military/artillery/ArtilleryInstance muster (Z)V",
        "settlement/room/military/artillery/ArtilleryInstance fireAtWill (Z)V",
        "settlement/room/military/artillery/ArtilleryInstance clearTarget ()V",
        "settlement/room/military/artillery/ArtilleryInstance targetCooSet (IIZZ)V",
        "settlement/room/military/artillery/ArtilleryInstance targetDivSet (Lgame/battle/div/Div;Z)V",
        // ---- Jobs
        "settlement/job/JOBS$1 set (Z)Lutil/data/BOOLEAN$BOOLEAN_MUTABLE;",
        // ---- Laws / crime / curfew
        "settlement/stats/law/Curfew setForADay (Z)V",
        "settlement/stats/law/StatCrime punishmentSet (Linit/type/HCLASS;Linit/race/Race;Linit/type/CRIME_PUNISHMENTS$PUNISHMENT;)V",
        // ---- Decrees (slavery, persecution, handouts, day off, overtime)
        "settlement/stats/muls/StatsMultipliers$Emancipate mark (Linit/type/HCLASS;Linit/race/Race;I)V",
        "settlement/stats/muls/StatsMultipliers$Emancipate mark (Lsettlement/entity/humanoid/Humanoid;Z)V",
        "settlement/stats/muls/StatsMultipliers$Emancipate unmark (Linit/type/HCLASS;Linit/race/Race;)V",
        "settlement/stats/muls/StatsMultipliers$Handout mark (Lsettlement/entity/humanoid/Humanoid;Z)V",
        "settlement/stats/muls/StatsMultipliers$Prosecution mark (Linit/type/HCLASS;Linit/race/Race;I)V",
        "settlement/stats/muls/StatsMultipliers$Prosecution mark (Lsettlement/entity/humanoid/Humanoid;Z)V",
        "settlement/stats/muls/StatsMultipliers$Prosecution unmark (Linit/type/HCLASS;Linit/race/Race;)V",
        "settlement/stats/muls/StatsMultipliers$StatMultiplierActionImp mark (Linit/type/HCLASS;Linit/race/Race;I)V",
        "settlement/stats/muls/StatsMultipliers$StatMultiplierActionImp mark (Lsettlement/entity/humanoid/Humanoid;Z)V",
        "settlement/stats/muls/StatsMultipliers$StatMultiplierActionImp unmark (Linit/type/HCLASS;Linit/race/Race;)V",
        "settlement/stats/muls/StatsMultipliers$StatMultiplierWork mark (Linit/type/HCLASS;Linit/race/Race;I)V",
        "settlement/stats/muls/StatsMultipliers$StatMultiplierWork mark (Lsettlement/entity/humanoid/Humanoid;Z)V",
        "settlement/stats/muls/StatsMultipliers$StatMultiplierWork unmark (Linit/type/HCLASS;Linit/race/Race;)V",
        // ---- Standing / access / policies
        "util/race/PERMISSION$Permission set (Linit/type/HCLASS;Linit/race/Race;Z)V",
        "settlement/stats/colls/StatsFood$3 set (Linit/type/HCLASS;Linit/race/Race;Z)V",
        "settlement/stats/service/StatServiceImp$Perm set (Linit/type/HCLASS_RACE;Z)Lutil/data/BOOLEANO$BOOLEAN_OE;",
        "settlement/stats/colls/StatsHome targetSet (ILinit/type/HCLASS;Linit/race/Race;Linit/resources/RESOURCE;)V",
        "settlement/stats/equip/EquipCivic targetSet (ILinit/type/HCLASS;Linit/race/Race;)V",
        "settlement/stats/colls/StatsEducation policySet (Linit/type/HCLASS;Linit/race/Race;Lsettlement/stats/colls/StatsEducation$StatEducation;)V",
        "settlement/stats/colls/StatsEducation policySet (Linit/type/HCLASS_RACE;Lsettlement/stats/colls/StatsEducation$StatEducation;)V",
        "settlement/stats/colls/StatsEducation$AgeType limitSet (Linit/type/HCLASS;Linit/race/Race;I)V",
        "settlement/stats/colls/StatsEducation$AgeType limitSet (Linit/type/HCLASS_RACE;I)V",
        "settlement/stats/stat/StatDecree$2 set (Linit/race/Race;I)V",
        // ---- Individuals
        "settlement/stats/colls/StatsAppearance setCustomName (Lsettlement/stats/Induvidual;Ljava/lang/String;)V",
        "settlement/entity/humanoid/Humanoid nobleSet ()V",
        // ---- Nobles
        "game/nobility/NOBLES setOffice (Lgame/nobility/Noble;Lgame/nobility/NobleOffice;)V",
        "game/nobility/NOBLES ranksAllocate (Lgame/nobility/Noble;)V",
        // ---- Divisions (city side)
        "game/battle/div/DivInfo menSet (I)V",
        "game/battle/div/DivInfo raceSet (Linit/race/Race;)V",
        "game/battle/div/DivInfo trainingSet (Lsettlement/stats/colls/StatsBattle$StatTraining;D)V",
        "game/battle/div/DivInfo equipSet (Lsettlement/stats/equip/EquipBattle;D)V",
        "game/battle/div/DivInfo bannerISet (I)V",
        "settlement/stats/equip/EquipBattle targetSet (Lgame/battle/div/Div;I)V",
        "game/battle/Army setDivAtOrderedIndex (Lgame/battle/div/Div;Lgame/battle/div/Div;)V",
        "world/army/WDivStoredAll attach (Lworld/entity/army/WArmy;Lgame/battle/div/Div;)V",
        "settlement/battle/ArmyTrainingInfo sendOutWithoutTraining (Z)V",
        "game/battle/setting/DivSettings musteringSet (Z)V",
        "game/battle/setting/DivSettings moppingSet (Z)V",
        // ---- World armies
        "world/entity/army/WArmy setDestination (II)V",
        "world/entity/army/WArmy intercept (Lworld/entity/army/WArmy;)V",
        "world/entity/army/WArmy besiege (Lworld/map/regions/Region;)V",
        "world/entity/army/WArmy stop ()V",
        "world/entity/army/WArmy raid (Z)V",
        "world/entity/army/WArmy disband ()V",
        "world/army/ADDiv disband ()V",
        "world/army/ADDiv reassign (Lworld/entity/army/WArmy;)V",
        "world/army/ADDivs insert (II)V",
        "world/army/WDivRegional bannerSet (I)V",
        "world/army/WDivStored bannerSet (I)V",
        "world/army/WDivMercenary bannerSet (I)V",
        "world/army/WDivRegional$1 trainingSet (Lsettlement/stats/colls/StatsBattle$StatTraining;D)V",
        "world/army/WDivRegional$1 equipSet (Lsettlement/stats/equip/EquipBattle;D)V",
        "world/army/WDivRegional$1 menSet (I)V",
        "world/army/WDivRegional$1 raceSet (Linit/race/Race;)V",
        "world/army/WDivRegional$1 bannerISet (I)V",
        "world/army/ADSupplies$ADArtillery$1 set (Lworld/entity/army/WArmy;I)V",
        // ---- Regions
        "world/region/RDOutputs squeze (Lworld/map/regions/Region;)V",
        "world/region/RD setFaction (Lworld/map/regions/Region;Lgame/faction/Faction;Z)V",
        "world/battle/WBattles regAttack (Lworld/map/regions/Region;Lworld/entity/army/WArmy;)V",
        "game/faction/diplomacy/DipStance set (Lgame/faction/Faction;Lgame/faction/Faction;)V",
        "game/faction/diplomacy/DipStance set (Lgame/faction/npc/FactionNPC;)V",
        "game/faction/player/emmi/EmiType set (Ljava/lang/Object;I)V",
        // ---- Diplomacy messages
        "game/faction/npc/NPCRequest expire ()V",
        // ---- Research
        "game/faction/player/PTech levelSet (Linit/tech/TECH;I)V",
        // ---- Events / raiders
        "game/events/killer/EventKiller setSuspect (I)V",
        "game/raiding/RaidingCurrent setAllyFight ()V",
        // ---- Player profile
        "game/faction/FBanner bannerTypeSet (I)V",
    };

    /**
     * Direct field assignments from UI code: {uiClassPattern, owner, field, desc}.
     * The PUTFIELD inside the UI class is rewritten into FieldSync.put*.
     */
    public static final String[][] FIELDS = {
        {"settlement/room/infra/transport/Gui*", "settlement/room/infra/transport/TransportInstance", "prio", "Z"},
        {"settlement/room/service/breeder/Gui*", "settlement/room/service/breeder/ROOM_BREEDER", "prosecute", "Z"},
        {"settlement/room/service/breeder/Gui*", "settlement/room/service/breeder/ROOM_BREEDER", "limitTotal", "I"},
        {"settlement/room/service/breeder/Gui*", "settlement/room/service/breeder/ROOM_BREEDER", "limitSpecies", "I"},
        {"settlement/room/military/training/Gui*", "settlement/room/military/training/ROOM_M_TRAINER", "trainingLimit", "I"},
        {"settlement/room/infra/logistics/MoveOrderPullUI*", "settlement/room/infra/logistics/MoveOrderPull", "pullLimit", "B"},
        // Tactical division settings written by the battle UI
        {"view/battle/*", "game/battle/setting/DivSettings", "formation", "Lgame/battle/formation/DIV_FORMATION;"},
        {"view/battle/*", "game/battle/setting/DivSettings", "guard", "Z"},
        {"view/battle/*", "game/battle/setting/DivSettings", "running", "Z"},
        {"view/battle/*", "game/battle/setting/DivSettings", "fireAtWill", "Z"},
        {"view/battle/*", "game/battle/setting/DivSettings", "shouldNotMoveToFire", "Z"},
        {"view/battle/*", "game/battle/setting/DivSettings", "ammoI", "S"},
    };

    private AutoSyncTable() {
    }

    public static String owner(int i) {
        return METHODS[i].split(" ")[0];
    }

    public static String name(int i) {
        return METHODS[i].split(" ")[1];
    }

    public static String desc(int i) {
        return METHODS[i].split(" ")[2];
    }
}
