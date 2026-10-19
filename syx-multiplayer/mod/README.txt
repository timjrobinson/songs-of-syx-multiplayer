Syx Multiplayer — co-op multiplayer for Songs of Syx (V71)
============================================================

Every player needs: the same game version (V71.44), this exact mod version,
and the mod ENABLED in the launcher's Mods tab.

Full guide, troubleshooting and issue reporting:
  https://github.com/timjrobinson/songs-of-syx-multiplayer

INSTALL (every PC)
  1. Unzip so that you get this folder layout inside the game's mods folder:
        <mods>/SyxMultiplayer/_Info.txt
        <mods>/SyxMultiplayer/V71/script/SyxMultiplayer.jar
     <mods> is:
        Windows:  %AppData%\songsofsyx\mods
        Linux:    ~/.local/share/songsofsyx/mods
                  (Steam snap: ~/snap/steam/common/.local/share/songsofsyx/mods
                   Flatpak:    ~/.var/app/com.valvesoftware.Steam/.local/share/songsofsyx/mods
                   Proton:     <steam library>/steamapps/compatdata/1162750/pfx/drive_c/
                               users/steamuser/AppData/Roaming/songsofsyx/mods)
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
  forward the port on the host's router or use a VPN-style tool.

JOINING
  Main menu > MULTIPLAYER > enter the host's address (e.g. 192.168.1.20 or
  host:port), a username and the password if the host set one > "join game".
  You download the host's world, load it, catch up and play.

IN GAME
  Everyone sees everyone's cursor. Enter opens the chat; Esc > multiplayer
  shows players, latency and lets you leave. Chat commands: /players,
  /help; host only: /kick <name>, /joinpoint (fresh join baseline).

WHAT TO EXPECT
  - One shared settlement, everybody controls everything. Speed/pause is
    shared. Autosave is off while in multiplayer: save through the menu;
    the host's save is the authoritative one.
  - Nearly every action the UI offers is synced: map tools, rooms and all
    room settings, work priorities, laws, decrees, policies, research,
    events, diplomacy and tribute, divisions and world armies, recruiting,
    tactical battle orders, world battle prompts (including generated
    tactical battles), renames, colours, map marks, home/workplace
    assignment, move orders, room copy and saved prints.
  - Still local by design: developer/debug buttons and profile colour masks.
  - A desync (the mod detects it; the drifted client rejoins automatically,
    10-20 s) means a bug. Please report it with the logs (below).
  - Steam friends list / "Join Game" exists but is off by default
    (steam=1 in <data>/settings/SyxMultiplayer.txt) and untested.

LOGS / REPORTING
  <data>/logs/syxmp.log on every machine. On a desync a report
  syxmp-desync-<time>.txt is written next to it. Open an issue at
  https://github.com/timjrobinson/songs-of-syx-multiplayer/issues using the
  "Desync report" or "Crash / bug report" template and attach the host's and
  the joiner's files along with what you were doing. Lines "UNSYNCED (local
  only)" name an action that still needs syncing.
