# Networking architecture (Phase 19)

This document is the canonical reference for how state moves between the
backend simulation and the frontend renderer. Anything contradicting this
file is a bug — open a PR against the doc when reality drifts.

## Goals

1. **Smooth rendering** — vehicles glide between server frames; production
   rings sweep without ever jumping; the in-game clock advances at exactly
   the right rate even at 256× game speed.
2. **Reliability** — a missed, duplicated, or reordered SSE message is
   harmless; an SSE drop reconciles cleanly via a single re-anchor; a server
   restart or game reset is detected by every client without manual
   intervention.
3. **Scalability** — bandwidth is independent of game speed; per-tick state
   broadcasts are forbidden; the wire carries *changes*, not snapshots.

## The single principle

> **Every server → client message is a self-contained anchor plus a payload.
> The client extrapolates state forward from the most recent anchor and
> reconciles by absolute in-game time, never by relative ordering.**

Concretely, every payload is wrapped in an envelope:

```
{
  serverWallClockMs:   long,    // System.currentTimeMillis() at emit
  gameMinutes:         long,    // monotonic, never resets, never wraps
  paused:              boolean,
  speed:               int,     // 0/1/4/16/64/256
  pausedSinceGameMinutes: long?, // null unless paused
  worldEpoch:          long,    // bumped on every game reset
  // … event-specific payload …
}
```

The client recomputes its anchor on every received envelope:

```
localOffsetMs = Date.now() - serverWallClockMs;
liveGameMinutes() =
  paused
    ? gameMinutes
    : gameMinutes
      + (Date.now() - serverWallClockMs - localOffsetMs)
      * speed * GAME_MINUTES_PER_REAL_SECOND_AT_1X / 1000;
```

The fixed network latency cancels in the math (`Date.now()` and
`serverWallClockMs` both shift by it), so the client doesn't need an NTP
sync. It is *not* robust against the local wall-clock drifting **during** a
session (NTP slew, laptop sleep/resume) — those re-anchor on the next
periodic clock SSE frame, which arrives at most once per real second.

## Time

`gameMinutes` is a monotonic positive `long` minutes-since-game-start. It
never resets across day/week/month/year boundaries — `dayOfWeek` and
`minuteOfDay` are pure derivations. This was already true pre-Phase-19; the
only change is that every event payload now carries it explicitly.

A future phase may bump the unit to `gameTimeMs` for sub-minute resolution.
That migration is intentionally a single mechanical PR with no mixed-unit
window — see "Future work" below.

### Pause behaviour

When `paused == true` the client holds `liveGameMinutes()` at
`pausedSinceGameMinutes` instead of extrapolating forward. A subscriber
that joins during a paused state therefore renders the right value without
waiting for the next state-change emit. The server includes
`pausedSinceGameMinutes` in **every** envelope, not just on the
pause-transition emit, so reconnect-on-pause is a no-op.

### Speed change

Every `setSpeed` REST response carries a fresh anchor with the new speed,
and `SimulationEngine.publishClockSnapshot` pushes the same anchor over
SSE. Both subscribers and the requester see one consistent envelope; no
race window where the client lerps at the old speed.

### Reset

`POST /api/game/reset` bumps `GameState.worldEpoch` by 1, restores the
starting balance, clears every building/order/vehicle, and re-anchors the
clock to `gameMinutes = 0`. The reset response is a full snapshot envelope
with the new epoch. A separate `WorldReset` event also fires onto the
events stream so any concurrent subscriber notices.

The client compares each received envelope's `worldEpoch` against its
cached value. On mismatch it drops every cache (vehicles, buildings,
orders, milestones) and re-fetches `/api/game/snapshot`. This is the
single recovery path — there is no per-channel reset signal.

## Cargo dispatch

Cargo lifecycle (the path a unit of stock takes from a producing
building to a consuming building) is a separate topic from this
networking spec; see [`DISPATCH.md`](DISPATCH.md) for the full
end-to-end pipeline (pathfinding, robot→transit→robot chaining,
boarding/alighting state machine, sparse-feed handling, mode-agnostic
rendering). The wire-format details for cargo events are still
described under "Event types" below — `DISPATCH.md` only documents the
domain logic.

## Channels

The wire surface is deliberately small. Production clients open **one** SSE
connection — `GET /api/game/stream` — and rely on the embedded `type`
discriminator to fan out to per-domain handlers. The legacy per-channel
endpoints below are kept for backward compatibility, debug ergonomics, and
integration tests.

| Endpoint                                    | Transport         | Purpose                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
|---------------------------------------------|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/game/snapshot`                    | REST one-shot     | **Cold-boot.** Full state envelope: clock + balance + buildings + vehicles + orders + milestones. Called on first load, on reconnect after a long disconnect, and on `worldEpoch` mismatch.                                                                                                                                                                                                                                                                                                  |
| `GET /api/game/stream`                      | **SSE — primary** | Unified server → client channel. Each frame is `{type: "CLOCK"\|"VEHICLE"\|"ORDER"\|"MILESTONE"\|"GAME"\|"CARGO", payload: {...}}`. The frontend's `ServerEventBusService` decodes the discriminator and pushes payloads onto per-domain RxJS subjects; each domain service subscribes to the subject it cares about. One TCP / HTTP connection regardless of how many event types the client tracks — leaves headroom under the browser's 6-per-origin SSE cap for future debug / presence channels. |
| `GET /api/game/clock`                       | REST              | Read just the clock anchor. Used as a lightweight re-anchor probe.                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `POST /api/game/clock/{speed,pause,resume}` | REST              | Steer the clock. Returns a fresh envelope.                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `POST /api/game/reset`                      | REST              | Returns the post-reset snapshot envelope.                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `GET /api/game/clock/stream`                | SSE — **legacy**  | Replaced by `/api/game/stream` (CLOCK frames). Kept for debug / integration tests.                                                                                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/game/orders/stream`               | SSE — **legacy**  | Replaced by `/api/game/stream` (ORDER frames).                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `GET /api/game/vehicles/stream`             | SSE — **legacy**  | Replaced by `/api/game/stream` (VEHICLE frames).                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `GET /api/game/milestones/stream`           | SSE — **legacy**  | Replaced by `/api/game/stream` (MILESTONE frames).                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/game/events/stream`               | SSE — **legacy**  | Replaced by `/api/game/stream` (GAME frames).                                                                                                                                                                                                                                                                                                                                                                                                                                                |

Browsers cap SSE connections at 6 per origin. The five live SSE channels
above (clock, orders, vehicles, milestones, events) leave one in reserve.
A future phase may consolidate to a single `/api/game/stream` if a sixth
channel is needed; the consolidation is mechanical because every envelope
already carries its own type discriminator.

## Event types

Every event carries the envelope plus event-specific fields. Sealed
hierarchies on both sides guarantee exhaustive handling.

### Clock channel

`ClockSnapshot` — emitted periodically and on every steering action. The
envelope **is** the payload.

### Orders channel (`com.dimsumdetours.sim.model.OrderEvent`)

| Type        | Trigger                                                                                                 | Client extrapolation                                                             |
|-------------|---------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| `Enqueued`  | `GameState.enqueueOrder` (REST) or `OrderGenerator.maybeGenerate` (tick)                                | Append to list. Patience countdown is `deadlineGameMinutes − liveGameMinutes()`. |
| `Fulfilled` | `GameState.fulfillOrder` / `spoilOrder` (REST), or `GameState.advanceVehicles` (tick, on cargo arrival) | Drop from list. Reputation + balance baked into the event payload.               |
| `Expired`   | `GameState.expirePendingOrders` (tick)                                                                  | Drop from list. Reputation hit baked in.                                         |

### Vehicles channel (`com.dimsumdetours.sim.model.vehicle.VehicleEvent`)

| Type      | Trigger                                                                       | Client extrapolation                                                                                                                                                                            |
|-----------|-------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Spawned` | `VehicleDispatcher.dispatch` (orders + restocks) and chained-handoff arrivals | Position at `t` is a pure function of `(t, path, departsAt, arrivesAt)`. Self-contained — a mid-flight subscriber that joins via `/api/game/snapshot` gets the same payload via `listVehicles`. |
| `Arrived` | `GameState.advanceVehicles` (tick) on path completion                         | Remove marker. Cargo effect on inventory / balance fires on the events channel as `BuildingStateChanged` / `BalanceChanged` in the same tick.                                                   |
| `RobotArrivedAtStop` | Phase-21 boarding state machine — first-mile robot reached its boarding stop | Remove marker. Cargo flips into a backend `WaitingCargo` queue keyed by `(boardingStopId, routeId)`. Wire shape ready; emitter wired by the dispatcher rewrite.                            |

`Spawned` is **schedule-anchored**: every render frame is `interpolate(t,
path)`, no per-tick "moved" event ever fires. The frontend never asks the
server "where is robot X now" — it computes the answer locally.

### Milestones channel (`com.dimsumdetours.sim.model.MilestoneEvent`)

`MilestoneUnlocked` — fires once per milestone when the tracker's predicate
flips. Idempotent: replaying the same event has no effect because the
client tracks unlocked-milestone-ids as a set.

### Events channel (`com.dimsumdetours.sim.model.GameEvent`)

The catch-all bucket for state changes that aren't part of the
order/vehicle/milestone lifecycle:

| Type                   | Trigger                                                                                                                            | Client extrapolation                                                                                                                                                                            |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `BalanceChanged`       | wallet mutation: order payout, daily upkeep, refrigeration upgrade, building placement/demolition                                  | Replace `_balance` signal. `delta` and `reason` are surfaced for future toasts.                                                                                                                 |
| `BuildingStateChanged` | farm/factory cycle anchor change, factory stall/unstall, factory stockpile change, restaurant fulfilled-orders / reputation update | Replace the matching `Building` in `_buildings`. The event carries the full updated DTO so the frontend never has to merge partial fields.                                                      |
| `RestaurantClosed`     | reputation crosses below `RESTAURANT_CLOSE_REPUTATION_THRESHOLD`                                                                   | Replace the matching `Building`; the marker recolours to the closed shade. The transition is one-way today — there is no `Reopened` event because the gameplay model doesn't support reopening. |
| `WorldReset`           | `POST /api/game/reset`                                                                                                             | Bump cached `worldEpoch`, drop every cache, re-fetch `/api/game/snapshot`.                                                                                                                      |

### Cargo channel (`com.dimsumdetours.sim.model.vehicle.CargoEvent`) — Phase 21

Lifecycle of a cargo manifest riding a transit run. The frontend uses
these to scale the ambient transit sprite by manifest count without
needing a backend `TransitVehicle` object on the client side. Wire
shape landed; emitter wired by the dispatcher rewrite.

| Type           | Trigger                                                              | Client extrapolation                                                                                          |
|----------------|----------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| `RunStarted`   | first manifest boards a `TransitRunId = (routeId, departureOffset)`  | Mark the run as "carrying cargo"; sprite scale follows the running cargo total.                                |
| `CargoLoaded`  | additional manifest boards an existing run                           | Add manifest to client-side run-totals map; new sprite scale = `1 + min(0.5, totalUnits * 0.05)`.              |
| `CargoUnloaded`| manifest reaches its alighting stop                                  | Subtract manifest from run-totals; spawn the post-transit `Robot` (broadcast as a regular `VehicleEvent.Spawned`). |
| `RunFinished`  | the last manifest unloads                                            | Drop the run from the carrying-cargo set; sprite returns to ambient appearance.                                |

### Ambient transit (Phase 18)

Phase-18 ambient buses are **schedule-deterministic from `gameMinutes = 0`**
— they have no events at all. The frontend's `TransitOverlayLayer` derives
position purely from `liveGameMinutes() mod BUS_HEADWAY_GAME_MINUTES` and
the route polyline from `/api/transit/snapshot` (a one-shot REST endpoint
loaded once at boot). This is the "static schedule" branch of the unified
extrapolation model; cargo `Spawned` is the "event-anchored trajectory"
branch. Both render via `liveGameMinutes()`, which is why a single
re-anchor frame fixes all visual drift simultaneously.

## What the client never does

* **Never poll for state.** The post-arrival `refreshBuildings` /
  `refreshBalance` poll in `VehicleService` was removed in Phase 19; cargo
  arrivals now arrive with their state mutations bundled on the events
  stream in the same tick.
* **Never trust message ordering.** Each event reconciles by its own
  `gameMinutes` timestamp + the `worldEpoch`. Late-arriving events are
  applied iff their epoch matches.
* **Never compute server state locally.** Robot dispatch, order generation,
  production cycles, daily upkeep — all live server-side. The client only
  *renders* state. The lone exception is ambient transit position, which
  is a pure visual derivation from the static snapshot.

## Cold-boot sequence

```
client load
  → GET /api/game/snapshot
      → store envelope (worldEpoch, clock anchor, balance, buildings,
                        vehicles, orders, milestones)
  → open 5 SSE channels (clock, orders, vehicles, milestones, events)
  → on every SSE frame:
      • verify envelope.worldEpoch matches cached
      • if mismatch: cold-boot again
      • if match:    apply event-specific payload, refresh anchor
```

## Reconnect / disconnect

The browser auto-reconnects an SSE stream after a transport drop, with no
backoff configurable from `EventSource`. On reconnect:

* Each SSE channel re-emits its current state on subscribe (clock pushes
  immediately; orders/vehicles/milestones/events do not — they only
  forward future events).
* If the client was offline long enough to miss arrivals, the cached
  `vehicles` map will contain stale entries that the server has already
  reaped. **The mitigation is `GET /api/game/snapshot` on any
  disconnect-longer-than-N-seconds** — the snapshot is the source of truth.

## Future work

Networking-related deferred work (Phase E `gameMinutes → gameTimeMs`
rename, Phase H persistent event log, cargo SSE channel split) lives in
the consolidated **Active backlog** section at the top of
[`docs/ROADMAP.md`](./ROADMAP.md). Phase G (single SSE channel) shipped
in Phase 19 — see the table entry there.

