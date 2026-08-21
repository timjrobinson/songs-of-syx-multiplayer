# RimWorld Multiplayer — architecture map (port blueprint)

Source: `reference/Multiplayer` (gitignored clone of https://github.com/rwmt/Multiplayer),
v0.11.5, wire protocol 54. ~57k LOC: Client 46.8k, Common 7.8k, Server 164 (headless
wrapper), MultiplayerLoader 504, Tests 1.3k.

This is the distilled study that drives the Songs of Syx port. File references are into
the clone.

## Sync model — deterministic lockstep, server-driven tick window

**Core invariant: no game state changes outside `Tick()` or `ExecuteCmd()`.**
The gate is `Multiplayer.InInterface` / `ShouldSync` (Client/Multiplayer.cs:66-73):
in-interface calls get intercepted → serialized → sent → cancelled locally; the same
call replays on all clients when the server echoes it.

- `TickPatch` (Client/Patches/TickPatch.cs) prefixes `TickManager.TickManagerUpdate`
  and returns false — it replaces the game's whole tick driver. State: `Timer` (the
  lockstep clock), `tickUntil` (server-authorized ceiling), `serverTimePerTick`,
  `simulating` (fast-forward for join/catch-up), `serverFrozen`.
- Normally exactly 1 tick per frame; adaptive pacing ±20% based on buffer depth
  (3–7 ticks). Every 32ms clients send `ClientFrameTimePacket(avgFrameTime)`.
- `ITickable` (AsyncTime/ITickable.cs): world (`TickableId -1`) + one per map.
  `TimeToTickThrough += 1` per Timer unit, tick while `>= TimePerTick(speed)` —
  that's how one Timer unit yields speed-multiplied game ticks.
- Per-map async time: each map has own tick lists, own `TimeSpeed`, **own
  `ulong randState`**. `PreContext/PostContext` swap Rand state + faction + map time
  around every tick and command (AsyncTimeComp.cs:179-204).
- `ConstantTicker` runs even when paused: autosave counters, and every 30 ticks the
  sync-opinion exchange.
- Server clock (Common/MultiplayerServer.cs:95-201): thread at 30Hz. `gameTimer++`
  unless frozen / any playing client >90 ticks behind (extrapolated). Broadcasts
  `ServerTimeControlPacket(gameTimer, sentCmdsSnapshot, serverTimePerTick)`;
  `serverTimePerTick = max(clients' frame time)` clamped [16.67, 66.7]ms — **the
  session paces at the slowest client**.
- **Double interlock** (Client/Session/MultiplayerSession.cs:123-127):
  `if (receivedCmds >= remoteSentCmds) tickUntil = remoteTickUntil;` — a client never
  enters a tick window until every command the server counted for it has arrived.

## Command flow

`ScheduledCommand(type, ticks, factionId, mapId, playerId, byte[] data)`.
`CommandType`: GlobalTimeSpeed, TimeSpeedVote, PauseAll, CreateJoinPoint,
InitPlayerData, **Sync** (the workhorse), DebugTools, MapTimeSpeed, Designator,
PlayerCount.

Path of a click: Harmony interception → `SyncMethod.DoSync` serializes
(syncId, context, target, args) → `ClientCommandPacket` → server policy check
(dev-only/host-only ids, time-control policy) → stamps `ticks = gameTimer`, playerId →
appends to `worldData.mapCmds` → broadcasts `ServerCommandPacket` reliably →
clients enqueue on ONE global queue (ordering!) → `ExecuteCmd` at `cmd.ticks == Timer`
with full context swap: faction push, RNG push, selection emptied, issuing player's
dev-mode/god-mode applied, `currentExecutingCmdIssuedBySelf` set for feedback gating.

## Syncing subsystem

- Registry: one list; **syncId = index after a stable sort — registration order is
  wire protocol** (Client/Syncing/Sync.cs:23-29).
- Handler types: `SyncMethod` (310 registrations), `SyncDelegate` (109; serializes
  closure captures, lambdas addressed by ordinal), `SyncField` (60 + ~100 watch
  sites), `SyncAction` (float-menu/gizmo actions matched by method description hash).
- Interception: transpiler at Priority.First injects
  `if (SyncTemplates.General(id, this, args)) return default;` at method entry.
- **SyncField watch pattern**: prefix records old value → UI mutates → postfix
  compares, ROLLS BACK, sends command. Buffered fields (sliders) send after 200ms of
  stability; UI shows the local value meanwhile.
- Serialization (Common/Syncing/SyncSerialization.cs): primitives → enums → arrays →
  generics (List/Nullable/Dictionary/HashSet/tuples) → registered hooks (IExposable
  via game save system) → polymorphic impl-index (12 interfaces, ordered impl lists =
  hidden protocol) → SyncWorker tree (explicit/implicit/interface buckets, parent
  workers write shared prefix).
- Object references: `Thing` = thingIDNumber + holder-kind chain, backed by a live
  `ThingsById` dictionary (spawn/despawn postfixes). Trackers/comps = serialize the
  owning Pawn + navigate. Defs = ushort type index + shortHash. Delegates gated by an
  allowlist of declaring types (RCE mitigation).

## Networking

- 1-byte header: 6-bit packet id (max 63), 2-bit fragment state (none/more/end).
  Fragments: 1KB each, first carries count+total, 32MiB max, one in flight.
- `MpConnectionState` = `[state, packetId]` handler table; states: Joining, Loading,
  Playing (client+server sides) + Steam/Bootstrap/Disconnected. Server join/load
  states are async/await scripts (`AsyncConnectionState`).
- Transports behind `ConnectionBase`: LiteNetLib (ReliableOrdered UDP), Steam P2P,
  **in-memory `LocalConnection.Paired()` for the host** (host is a normal client of
  the in-process server), replay (null transport).
- ~46 packet ids; typed packet structs bind via a `PacketBuffer` DSL with per-field
  `maxLength` (DoS boundary).
- Server never simulates: stores blobs, stamps commands, counts ticks. Standalone
  headless server is 164 LOC. Optional **arbiter**: an extra headless game instance
  joins as referee so "host is right" isn't assumed.

## Joining

- `CreateJoinPoint` command → **every client saves+reloads at that tick** (also
  scrubs cache drift); host/arbiter uploads; server swaps `savedGame` + truncates the
  command log to the buffered tail.
- Join handshake: protocol → username/password → init data (mod list, configs,
  dev-only sync ids, FP round modes, per-def counts+hashes) → def-by-def diff check →
  world request → fragmented blob: factionId, gameTimer→tickUntil, sentCmds, frozen,
  gzip'd save (maps stripped, streamed separately), session data, per-map command
  logs, retained sync opinions.
- Client loads, then **fast-forwards through the whole command log to tickUntil**
  before sending WorldReady.
- Three persistence tiers: caches (reset), session data (binary, survives reload,
  dies with server), save XML (permanent).

## Determinism (the patch catalogue — expect an SoS analogue)

- RNG: three streams (world / per-map / commands), all bracketed by push/pop +
  persisted `randState`; every entry from non-deterministic context seeds explicitly
  (map gen, long events capture a seed and wrap the callback, name generation costs a
  fixed number of draws, GenSpawn wraps Rot4.Random...).
- Hash codes: process-varying `GetHashCode` replaced wherever iteration/keys touch sim
  (`System.HashCode.Combine` prefixed globally!).
- FPU rounding mode probed and compared at join; mismatch = disconnect.
- `Determinism.cs` (754 LOC): tweened positions, zone cell shuffles, cache
  invalidation keyed on frame → tick, camera-visibility must not affect sim,
  single-batch job execution, constant deltas.
- **Interface-only unique IDs are negative** from a local counter; fixed up if they
  leak into a save.
- Feedback gating: everyone simulates a command; only the issuer gets
  sounds/messages/camera jumps.
- Long events freeze the global clock for everyone (FreezeManager, 10s escape hatch).

## Desync detection

`ClientSyncOpinion` per 30-tick window: cmd rand states, world rand states, per-map
rand states (high 32 bits only), stack-trace hashes per RNG call site.
`SyncCoordinator` keeps ≤30 opinions, compares host/arbiter's vs local, first
mismatch → desynced: report bundle (local+host traces, JIT-timing lists, replay,
logs) zipped for diagnosis. `DeferredStackTracing` walks the native stack (too hot
for managed traces); top-6 frames hashed.

## What's reusable vs game glue

Reusable architecture (~10k LOC): Common networking + serialization core, server
core, desync opinion model, TickPatch/ITickable loop, snapshot/join-point machinery,
session manager. Game glue (~35k LOC): the sync registration tables, the determinism
patch catalogue, dialog-proxy sessions, all UI. **The tables and the catalogue are
the ongoing cost** — they scale with the target game's UI surface.
