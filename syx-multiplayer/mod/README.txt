Syx Multiplayer 0.2 — co-op multiplayer for Songs of Syx (V71)
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
        macOS:    ~/Library/Application Support/songsofsyx/mods
  2. Start the game, enable "Syx Multiplayer" in the launcher's Mods tab, play.
  3. Load (or start) any game ONCE. The mod installs itself into the launcher
     settings and shows "please quit and restart". Quit the game completely
     (launcher too) and start it again. From now on the main menu has a
     MULTIPLAYER entry.
     (What happens under the hood: the mod needs a Java agent; step 3 adds
      "-javaagent:<path to SyxMultiplayer.jar>" to JVM_ARGS2 in
      <settings>/LauncherSettings.txt. You can also add that line by hand.)

HOSTING
  Main menu > MULTIPLAYER > "host a saved game" > pick a save. The game loads
  and hosts on TCP port 30599 (change the port field before hosting if needed).
  Or: while playing, Esc > multiplayer > "host this game".
  The other player needs to reach that port: same LAN works out of the box
  (allow the game through the Windows firewall when asked); over the internet,
  forward the port on the host's router.

JOINING
  Main menu > MULTIPLAYER > enter the host's address (e.g. 192.168.1.20 or
  host:port) and a username > "join game". You download the host's world,
  load it, catch up and play. Everyone sees everyone's cursor; Enter opens the
  chat; Esc > multiplayer shows players/latency and lets you leave.

WHAT TO EXPECT (version 0.2, early co-op)
  - One shared settlement, everybody controls everything.
  - Speed/pause is shared. Changing speed is a synced action.
  - Synced so far: map tools (designations, room/item placement brushes),
    room placement (one player should plan a room at a time), room
    employment priorities, immigration settings, most slider/checkbox
    settings backed by the game's generic value objects.
  - NOT synced yet: many menu actions (trade deals, laws, research,
    armies, nobles, diplomacy ...). Using them makes the clients drift apart;
    the mod detects this within a second or two and the drifted client
    automatically rejoins (about 10-20 seconds, the game pauses meanwhile).
    This is expected at this stage — it tells us what to sync next.
  - Battles are not supported yet. Save normally; the host's save is the
    authoritative one.
  - Steam friends list / "Join Game" is implemented but untested.

LOGS / REPORTING
  The game's log (launcher > info > logs, or <settings>/../logs) contains
  lines tagged [SyxMP]. If something goes wrong, send the host's and the
  joiner's logs along with what you were doing.
