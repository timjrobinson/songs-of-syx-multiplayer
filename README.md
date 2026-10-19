# Syx Multiplayer

Co-op multiplayer for [Songs of Syx](https://songsofsyx.com/): several
players run one shared settlement together. Everyone can do everything:
build, manage, fight, trade. Game speed and pause are shared.

The mod is a port of the [RimWorld Multiplayer](https://github.com/rwmt/Multiplayer)
architecture: deterministic lockstep, where every PC runs the full simulation
and only player commands cross the network.

**Status: 0.3.x, playable but young.** Hosting, joining with world transfer,
shared speed, cursors and chat work on real machines. Nearly every action the
UI offers is synced (see `docs/sync-coverage.md`). Expect occasional desyncs;
the mod detects them and the drifted player rejoins automatically. Reports
are very welcome: see [Reporting problems](#reporting-problems).

- Steam: when the game is started through Steam, hosting opens a
  friends-only lobby, friends see "Join Game" and a "friends hosting" list in
  the multiplayer menu, and the connection goes through Steam's relay (no
  port forwarding). Direct IP joins work without Steam. Turn it off with
  `steam=0` in `<data>/settings/SyxMultiplayer.txt`.
- Game version required: **Songs of Syx V71.44** (every player)
- Mod version required: **the same release for every player** (the join is refused otherwise)
- Platforms: Windows, Linux (native, Steam snap/Flatpak, Proton), macOS

---

## Getting started (players)

### 1. Install the mod — every player

1. Download `SyxMultiplayer-<version>.zip` from the
   [latest release](https://github.com/timjrobinson/songs-of-syx-multiplayer/releases/latest).
2. Find your game data folder (`<data>` below). It is where the game keeps
   saves and settings, **not** the install folder:

   | OS | `<data>` |
   |---|---|
   | Windows | `%AppData%\songsofsyx` (paste into the Explorer address bar) |
   | Linux | `~/.local/share/songsofsyx` |
   | Linux, Steam snap | `~/snap/steam/common/.local/share/songsofsyx` |
   | Linux, Steam Flatpak | `~/.var/app/com.valvesoftware.Steam/.local/share/songsofsyx` |
   | Linux, game run through Proton | `<steam library>/steamapps/compatdata/1162750/pfx/drive_c/users/steamuser/AppData/Roaming/songsofsyx` |
   | macOS | `~/Library/Application Support/songsofsyx` |

3. Unzip into `<data>/mods/` so that you end up with exactly:

   ```
   <data>/mods/SyxMultiplayer/_Info.txt
   <data>/mods/SyxMultiplayer/V71/script/SyxMultiplayer.jar
   ```

4. Start the game. In the launcher's **Mods** tab enable **Syx Multiplayer**,
   then launch.
5. **Load any game once.** A dialog asks you to quit and restart. Quit the
   game *and the launcher*, start again. The main menu now has a
   **MULTIPLAYER** entry.

   Why the restart: the mod needs to run as a Java agent so it can take over
   the game's tick loop. The first time it runs it writes
   `-javaagent:<path to SyxMultiplayer.jar>` into `JVM_ARGS2` in
   `<data>/settings/LauncherSettings.txt`. If the MULTIPLAYER entry does not
   appear after the restart, open that file and check the line is there; you
   can add it by hand. `<data>/logs/syxmp.log` has an `agent auto-install:`
   line saying what happened.

To **update**, replace the mod folder with the new zip's content and restart
the game. Every player must update at the same time.

To **uninstall**, remove the `-javaagent:...` line from
`<data>/settings/LauncherSettings.txt` *first*, then delete the mod folder.
If you delete the folder first the game will not start until that line is
gone.

### 2. Host a game

Main menu > **MULTIPLAYER** > set an optional password, the max number of
players and whether to pause while someone joins > **host a saved game** >
pick a save. The game loads and listens on TCP port **30599** (change the
port field first if you need another one). You can also host the game you
are already playing: Esc > multiplayer > **host this game**.

The other players must be able to reach that port:

- **Same LAN**: works out of the box. Allow the game through the firewall
  when asked. Tell the others your LAN address (`ipconfig` on Windows,
  `ip a` on Linux, System Settings > Network on macOS).
- **Over the internet**: forward TCP 30599 on your router to your PC and give
  the others your public address, or use a VPN-style tool such as Tailscale,
  ZeroTier or Hamachi so everyone is on one virtual LAN.

### 3. Join a game

Main menu > **MULTIPLAYER** > enter the host's address (`192.168.1.20` or
`host.example.com:30599`), a username and the password if the host set one >
**join game**. The mod downloads the host's world, loads it, catches up to
the host's tick and you are in. Joining while the host is far into a session
can take a little while; the host can tick "pause when someone joins" to make
it painless.

### 4. Playing

- Everyone sees everyone's cursor.
- **Enter** opens the chat. Commands: `/players`, `/help`; host only:
  `/kick <name>`, `/joinpoint` (take a fresh snapshot for later joiners).
- Esc > multiplayer shows the players and their latency and lets you leave.
- The game runs at the pace of the slowest PC. Speed and pause are shared.
- Autosave is off in multiplayer. Save through the menu as usual; the host's
  save is the authoritative one, and any save can later be hosted again.
- A "DESYNC" message means the simulations drifted apart. The drifted player
  is reconnected automatically (10-20 s). If it keeps happening, please
  report it (below) so the cause can be fixed.

### Troubleshooting

| Symptom | What to do |
|---|---|
| No MULTIPLAYER entry in the main menu | The agent line is missing. Load a game once and restart, or add `-javaagent:<full path to SyxMultiplayer.jar>` to `JVM_ARGS2` in `<data>/settings/LauncherSettings.txt`. Check `<data>/logs/syxmp.log`. |
| "VERSION_MISMATCH" when joining | Host and joiner run different mod or game versions. Everyone installs the same release. |
| Cannot connect | Wrong address/port, firewall, or no port forwarding. Try the LAN first. The host's log shows `hosting on port`. |
| The game does not start at all after removing the mod | Remove the `-javaagent:` line from `LauncherSettings.txt`. |
| Something you did was not seen by the others | Look for `UNSYNCED (local only)` in `syxmp.log` and open a feature request with that line. |

## Reporting problems

Use the issue templates: **Desync report** for drifting games, **Crash / bug
report** for everything else. They explain which files we need; in short,
from **every** machine in the session:

- `<data>/logs/syxmp.log` (the mod's log)
- `<data>/logs/syxmp-desync-<time>.txt` (written when a desync is detected)
- for crashes, the game's own crash log from `<data>/logs/` or `hs_err_pid*.log`

Zip them per machine and attach the zips to the issue together with what the
players were doing at the time.

---

## How it works

- **Deterministic lockstep.** Every client runs the full simulation. Nothing
  about the world is streamed; only *player commands* (place a room, change a
  law, set game speed...) go over the network.
- **The server is a dumb relay and clock.** It never simulates. It stamps
  each command with the tick it must execute on, rebroadcasts it, and
  advances a `tickUntil` window telling clients how far they may simulate.
  Clients refuse to pass a tick boundary until they hold every command the
  server says it sent for that window.
- **Player actions are intercepted, not executed.** A click is serialized
  into a command, cancelled locally, and executed by everyone (the clicker
  included) when the server echoes it back. The simulation only ever changes
  inside a tick or a command.
- **Joining = save transfer.** The host saves in memory, ships the save to
  the joiner, who loads it and fast-forwards through the command log.
- **Desync detection.** Clients exchange rolling RNG fingerprints per tick
  window; a mismatch pauses, writes a report and resyncs the drifted client.

Songs of Syx is a good lockstep target: a fixed 1/16 s sim sub-step, one
global seedable RNG (reseeded per sub-step and per command, which makes UI
RNG use harmless), full shipped sources, index-stable object IDs, and a save
system that can run in memory for state transfer and desync hashing. The mod
jar doubles as a Java agent (bundled ASM) that redirects the frame loop to
the mod's tick controller and injects interception guards at the entry of
synced methods, playing the role Harmony plays for RimWorld.

## Developing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, the PR workflow and how
releases are cut (locally, with `make release`), and `CLAUDE.md` for the technical map (game internals,
port design decisions, determinism rules). Deep-dive docs live in `docs/`:
`HANDOFF4.md` is the latest session summary, `sync-coverage.md` lists every
synced action, `sos-internals.md` and `rimworld-mp-architecture.md` are the
surveys the port is built on.

Quick start (you need your own copy of the game):

```sh
make setup                                                       # ASM + a JDK 21 into tools/
cp syx-multiplayer/local.env.example syx-multiplayer/local.env   # set SYX_GAME_DIR
make test                                                        # build, headless tests, install into your mods folder
```

`make build` builds and installs, `make release` publishes the next version
(see CONTRIBUTING.md), `make help` lists the rest.

Reference targets: Songs of Syx **V71.44** (Java 16 bytecode on the bundled
Temurin 21 JRE); RimWorld Multiplayer **0.11.5**, wire protocol 54.

## License

[MIT](LICENSE). The bundled [ASM](https://asm.ow2.io/) library is BSD-3-Clause.
Songs of Syx itself is not included: you need your own copy of the game.
