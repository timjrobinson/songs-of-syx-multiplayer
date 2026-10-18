# Syx Multiplayer

A co-op multiplayer mod for [Songs of Syx](https://songsofsyx.com/), built as a
faithful port of the [RimWorld Multiplayer mod](https://github.com/rwmt/Multiplayer)'s
architecture: it syncs and communicates the same ways, it just syncs Songs of
Syx data instead of RimWorld data.

## How it works (the RimWorld Multiplayer model)

- **Deterministic lockstep.** Every client runs the full simulation locally.
  Nothing about the world is streamed — only *player commands* (place a room,
  change a law, set game speed…) go over the network.
- **The server is a dumb relay and clock.** It never simulates. It stamps each
  command with the tick it must execute on, rebroadcasts it to everyone, and
  advances a `tickUntil` window that tells clients how far they may simulate.
  Clients refuse to pass a tick boundary until they hold every command the
  server says it sent for that window (the `tickUntil` + `sentCmds` interlock).
- **Player actions are intercepted, not executed.** When you click something in
  the UI, the mod serializes the action into a command, cancels the local call,
  and sends it. It executes for everyone (including you) when the server echoes
  it back — so the simulation only ever changes inside a tick or a command.
- **Joining = save transfer.** The host saves, the save is shipped to the
  joining client, which loads it and fast-forwards through the command log to
  the current tick.
- **Desync detection.** Clients exchange rolling "sync opinions" — RNG-state
  fingerprints per tick window — and compare them to detect divergence early.

## Repository layout

| Path | What |
|---|---|
| `syx-multiplayer/` | The mod: Java sources, build script, mod metadata |
| `syx-multiplayer/build.sh` | Compile against the game jar + install into the game's mods folder |
| `CLAUDE.md` | Deep technical notes: game internals, port design, gotchas |
| `reference/` *(gitignored)* | Clone of rwmt/Multiplayer + the game's shipped Java sources, for study |
| `tools/` *(gitignored)* | Local Temurin JDK 21 used to build |

## Building

Requirements: the game installed (currently expected at
`/mnt/games/SteamLibrary/steamapps/common/Songs of Syx/`, game version V71),
and a JDK 21 at `tools/jdk21` (any Temurin 21 works — see `build.sh` to adjust
paths).

```sh
cd syx-multiplayer
./build.sh
```

This compiles the mod against `SongsOfSyx.jar` and installs it to
`~/.local/share/songsofsyx/mods/SyxMultiplayer/` (Info file + `V71/script/SyxMultiplayer.jar`).
Enable it in the game's launcher mod list.

## How the port maps onto Songs of Syx

- The mod jar is also a **Java agent** (`-javaagent`, added to the launcher's
  `JVM_ARGS2` — the in-game "MP: install agent" button does it for you). The
  agent is our Harmony: it rewrites the game's frame loop to hand tick control
  to the mod, and injects interception guards at the entry of synced methods.
- Songs of Syx turns out to be a *better* lockstep target than RimWorld: a
  fixed 1/16s sim sub-step, one global seedable RNG (we reseed it per sub-step,
  which makes UI/menu RNG consumption harmless), full shipped sources, index-
  stable object IDs, and a save system we can run in memory for state transfer
  and desync hashing.

## Status (0.3.0)

Playable co-op: hosting from the main menu or in-game, joining by address
with world transfer and catch-up, shared speed, cursors, chat with host
commands, automatic resync after a desync, desync reports. Nearly every
settlement-management action the UI offers is synced (170 hooked verbs plus
composite commands for events, diplomacy deals, tribute and region
buildings); tactical battle orders, world battle prompts, army recruiting and
a few renames are still local — see `docs/sync-coverage.md`. Steam
friends/invites are implemented but off by default and untested. Player
instructions: `syx-multiplayer/mod/README.txt`; developer map: `CLAUDE.md`
and `docs/HANDOFF3.md`.

Run the headless test suite with `syx-multiplayer/test.sh`.

## Reference targets

- Game: Songs of Syx **V71.44** (Java 16 bytecode, runs on the bundled Temurin 21 JRE)
- Blueprint: RimWorld Multiplayer **0.11.5**, wire protocol 54
