Syx Multiplayer 0.3 — co-op multiplayer for Songs of Syx (V71)
================================================================

Every player needs: the same game version (V71.44), this exact mod version,
and the mod ENABLED in the launcher's Mods tab.

INSTALL (every PC)
  1. Unzip so that you get this folder layout inside the game's mods folder:
        <mods>/SyxMultiplayer/_Info.txt
        <mods>/SyxMultiplayer/V71/script/SyxMultiplayer.jar
     <mods> is:
        Windows:  %AppData%\songsofsyx\mods
        Linux:    ~/.local/share/songsofsyx/mods
                  (Steam snap: ~/snap/steam/common/.local/share/songsofsyx/mods
                   Flatpak:    ~/.var/app/com.valvesoftware.Steam/.local/share/songsofsyx/mods)
        macOS:    ~/Library/Application Support/songsofsyx/mods
  2. Start the game and enable "Syx Multiplayer" in the launcher's Mods tab.
  3. The first time the game starts with the mod enabled, the mod writes
     itself into the launcher settings. Load any game once: a dialog asks
     you to QUIT and RESTART (quit the launcher too). After the restart the
     main menu has a MULTIPLAYER entry.
     (Under the hood: the mod needs a Java agent; it adds
      "-javaagent:<path to SyxMultiplayer.jar>" to JVM_ARGS2 in
      <data>/settings/LauncherSettings.txt. You can add that line by hand.
      Check <data>/logs/syxmp.log for "agent auto-install:" if it does not work.)

UNINSTALL
  Remove the "-javaagent:..." line from <data>/settings/LauncherSettings.txt
  FIRST (or use "MP: uninstall agent" in the developer debug panel), then
  delete the mod folder. If you delete the folder first the game will not
  start until that line is removed by hand.

HOSTING
  Main menu > MULTIPLAYER > set a password (optional), max players, whether
  to pause when someone joins > "host a saved game" > pick a save. The game
  loads and hosts on TCP port 30599 (change the port field first if needed).
  Or: while playing, Esc > multiplayer > "host this game".
  The other players need to reach that port: same LAN works out of the box
  (allow the game through the firewall when asked); over the internet,
  forward the port on the host's router.

JOINING
  Main menu > MULTIPLAYER > enter the host's address (e.g. 192.168.1.20 or
  host:port), a username and the password if the host set one > "join game".
  You download the host's world, load it, catch up and play.

IN GAME
  Everyone sees everyone's cursor. Enter opens the chat; Esc > multiplayer
  shows players, latency and lets you leave. Chat commands: /players,
  /help; host only: /kick <name>, /joinpoint (fresh join baseline).

WHAT TO EXPECT (version 0.3)
  - One shared settlement, everybody controls everything. Speed/pause is
    shared. Autosave is off while in multiplayer: save through the menu;
    the host's save is the authoritative one.
  - Synced: all map tools (designations, rooms, items, jobs), room placement
    (one player plans a room at a time), room settings (lock, delete,
    upgrade/downgrade, workers, auto-employ, radius, recipes, crops,
    animals, stockpile/hauler/export/import/transport/supply settings,
    food/drink choices), work priorities, immigration, laws, curfew,
    punishments, decrees (slavery, persecution, handouts, day off,
    overtime), standing/access/policies, education, rations, rename and
    ennoble people, nobles' offices, divisions (create/edit/send out/
    recall/guard duty), world army orders (move, intercept, besiege, stop,
    raid, disband, reorganize), region buildings, region squeeze/abandon,
    garrison attack, diplomacy deals (accept/incoming), court emissaries,
    research, event choices, tribute payments, player banner.
  - NOT synced yet (using them will desync; the mod detects it and the
    drifted client rejoins automatically, 10-20 s): tactical battle orders
    (move/attack orders to divisions during a fight), world battle prompts
    (fight/auto-resolve/retreat), recruiting world armies and mercenaries,
    granting autonomy, renaming divisions/regions/armies, copying a single
    room or placing saved blueprints, the "assign race/home" tools.
  - Steam friends list / "Join Game" exists but is off by default
    (steam=1 in <data>/settings/SyxMultiplayer.txt) and untested.

LOGS / REPORTING
  <data>/logs/syxmp.log on every machine. On a desync a report
  syxmp-desync-<time>.txt is written next to it. Send the host's and the
  joiner's files along with what you were doing. Lines "UNSYNCED (local
  only)" name an action that still needs syncing.
