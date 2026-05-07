# Cargo dispatch, pathfinding & vehicle boarding (Phase 20)

This document is the canonical reference for how a unit of stock travels
from a producing building (farm or factory) to a consumer (factory input
stockpile or restaurant order). Anything contradicting this file is a bug
— open a PR against the doc when reality drifts.

## Pipeline overview

The full dispatch pipeline, end-to-end, is exactly ten steps:

1. **Identify a destination.** A farm or factory tick scans for a valid
   downstream consumer (factory restock target, or a restaurant order line
   that needs this ingredient).
2. **Pathfind.** Combine OSM streets and the active GTFS feed.
   - Pathfinding **does not** consider headways or wait times — it only
     decides geometry and distances. The actual boarding minute is
     resolved at boarding time.
   - At most one transit leg is allowed: `robot → transit → robot`. No
     transfers.
   - The `MAX_ROBOT_LEG_METERS = 5 km` cap applies to **every** robot
     leg in the simulation, including the two last-mile legs of a
     transit chain. The planner rejects any candidate stop pair whose
     OSM-routed source→boarding or alighting→destination leg exceeds
     the cap.
   - If no transit-backed chain satisfying the per-leg cap is found AND
     the great-circle distance between source and destination is
     `≤ MAX_ROBOT_LEG_METERS`, fall back to a direct OSM-streets robot
     path.
   - Transit is **always preferred** when both a transit plan and a
     direct robot plan exist (the dispatcher does not compare durations
     — it picks transit unconditionally when the planner returns a
     chain).
3. **No-path failure.** If neither a transit plan nor a direct fallback
   exists, the dispatch is counted as a failure and **no inventory is
   debited**. The dispatcher will retry on the next tick.
4. **Spawn the robot.** With a path in hand, the dispatcher debits source
   inventory and spawns a `Robot` whose `spawnedAt = now`,
   `departsAt = now + ROBOT_LOADING_GAME_MINUTES`. The robot stays
   stationary at the source for the loading window.
5. **Move the robot.** After `departsAt` the robot follows its OSM path
   to either the destination (direct) or the boarding stop (transit).
6. **Wait at stop.** If the robot's path ends at a transit stop, the
   robot becomes a queued `WaitingCargo` keyed by `(boardingStopId,
   routeId)`. The path stores **only the route id**, never a specific
   departure — boarding picks the next available run on that route.
7. **Board the next vehicle.** When the next ambient run on the planned
   route reaches the boarding stop, the robot's cargo is appended to a
   `CargoManifest` on that vehicle's run, tagged with the alighting stop
   and the remaining post-transit OSM path. If no backend `TransitVehicle`
   object exists for `(routeId, departureOffset)` it's lazily created;
   otherwise the manifest is appended to the existing one. The robot is
   destroyed.
8. **Alight.** When the run reaches the alighting stop, the manifest is
   removed from the vehicle. If the vehicle has no remaining manifests,
   the backend object is destroyed (the on-screen sprite continues — see
   "Rendering" below). Otherwise the vehicle continues to the next
   manifest's alighting stop.
9. **Spawn the connecting robot.** A new `Robot` materialises at the
   alighting stop carrying the manifest's cargo and the remaining
   post-transit OSM path. Loading time on this connection is zero.
10. **Deliver.** When the final-leg robot arrives at the destination
    building, cargo is transferred to that building's input stockpile (or
    completes the matching order line). The robot is destroyed.

## Why "vehicle objects only exist when carrying cargo"

Ambient transit vehicles are **purely client-side** sprites. Their
position at any wall-clock instant is fully determined by:

- the GTFS feed's per-stop arrival times (`stopArrivalGameMinutes[]`),
- the route's shape polyline (`shape[]`, `stopShapeIndices[]`),
- the run's `departureOffset = floor((now − k·H) / runTime) · runTime + k·H`
  where `H = BUS_HEADWAY_GAME_MINUTES = 5`, `k ∈ [0, ceil(runTime/H))`.

Every input is in the transit snapshot the client downloads at boot. The
server therefore does not need to simulate or broadcast ambient vehicle
positions — a 1k-stop feed with hundreds of concurrent runs is rendered
at zero per-tick server cost.

A backend `TransitVehicle` object is only allocated when at least one
`CargoManifest` boards a specific `(routeId, departureOffset)` run, and
deallocated when its last manifest unloads. This is what the user spec
means by "vehicles on the map are only rendered clientside, it doesn't
need to also be simulated by backend objects unless they have a tracked
inventory."

## Pathfinding

Implemented today in
[`GtfsMultiLegPlanner`](../backend/src/main/java/com/dimsumdetours/gtfs/GtfsMultiLegPlanner.java).
The planner's contract:

- **Input**: `(LatLon source, LatLon destination, long departureGameMinutes)`.
  The `departureGameMinutes` value is currently used only for diagnostic
  logging — pathfinding does not consult it for headway alignment.
- **Output**: `Optional<VehicleChain>`. Empty means "no transit chain
  found" — the dispatcher then falls back to a direct robot when
  great-circle distance is `< 5 km`.
- **Stop search**: top-K nearest stops on each side
  (`CANDIDATE_STOPS_PER_SIDE = 5`) within
  `MAX_ROBOT_LEG_METERS = 5000`, scanned by linear distance. Each
  candidate pair is then validated by routing both robot legs through
  OSM and rejecting the pair if either OSM-routed leg length exceeds
  `MAX_ROBOT_LEG_METERS`.
- **Trip selection**: first trip in
  `tripsByStopId[boardingStopId]` whose `stop_sequence` visits the
  alighting stop *after* the boarding stop.
- **Bus polyline**: the shape between the two stops, sliced by nearest-
  vertex projection. Falls back to ordered intermediate stop coordinates
  if the trip has no shape.
- **Bus duration** (Phase 20):
  1. Use GTFS `stop_times.txt` directly when both endpoints are scheduled.
  2. Otherwise interpolate from neighbouring anchored stops by cumulative
     shape distance — see "Sparse-feed handling" below.
  3. Last resort: synthesise from `busMeters /
     BUS_METERS_PER_GAME_MINUTE`.
- **Both robot legs must be OSM-routable**. No straight-line fallback.
  If the OSM router can't route either leg the chain is rejected.

## Boarding alignment

Cargo's committed boarding minute is aligned to the same phase as the
ambient bus animation on the same route, so the cargo bus visibly
matches a bus that's at the stop:

```
phaseOffset       = (boardingStopArrivalSec - tripFirstStopSec) / 60
boardingArrival   = departureGameMinutes + LOADING + firstLegDuration
committedDepartsAt = boardingArrival + ((phaseOffset - boardingArrival) mod H + H) mod H
```

Where `H = BUS_HEADWAY_GAME_MINUTES = 5`. The result is propagated via
`VehicleHandoff.nextDepartsAtGameMinutes`; `GameState.buildNextLeg`'s BUS
branch uses it verbatim and clamps `spawnedAt = min(now, committedDepartsAt)`
to satisfy the `spawnedAt ≤ departsAt < arrivesAt` invariant when a tick
overshoots the planned minute (which happens at high game speeds — one
tick at 256× covers ~25 game-min).

## Sparse-feed handling

The user spec is explicit that a feed missing `stop_times` for some stops
must not silently drop those stops. Two layers of interpolation:

1. **Snapshot-time** (in [`TransitSnapshotService`](../backend/src/main/java/com/dimsumdetours/gtfs/TransitSnapshotService.java)).
   On feed load, missing per-stop arrival/departure cells are filled by
   piecewise-linear interpolation against cumulative shape distance:
   - Find the nearest anchored stop before and after the missing cell.
   - Interpolate in shape-meters between those anchors.
   - Leading missing → 0; trailing missing → extrapolate at last
     segment's pace.
   - If the entire trip has zero anchored stops, synthesise times by
     `cumulativeMeters / BUS_METERS_PER_GAME_MINUTE`.
   - Final pass: force monotonic non-decreasing (degenerate feeds can
     have anchors that disagree with stop order).
2. **Per-plan** (in `GtfsMultiLegPlanner.interpolatedBusDuration`). For a
   specific `(boardingIdx, alightingIdx)` pair whose endpoints lack
   scheduled times, the planner derives the boarding→alighting duration
   by interpolating against any anchored stops on the same trip, scaled
   by cumulative shape-meters.

After Phase 20, the snapshot's `stopArrivalGameMinutes[]` and
`stopDepartureGameMinutes[]` arrays should never contain `-1` sentinels
in production. The frontend `TransitOverlayLayer.precomputeRoute` keeps
its own NaN-patching loop as defence-in-depth, but in practice it's a
no-op.

## Mode unification

The pipeline is **mode-agnostic**. Behaviour (animation, boarding, cargo
scaling, capacity) is identical across every GTFS `route_type`:

| route_type | Mode             | Icon glyph                |
|-----------:|------------------|---------------------------|
| 0          | Tram / streetcar | Slim rounded body         |
| 1          | Subway / metro   | Long carriage, 3 windows  |
| 2          | Rail             | Long carriage, 3 windows  |
| 3          | Bus *(default)*  | Two-window rectangle      |
| 4          | Ferry            | Boat hull silhouette      |
| 5,6,7      | Cable / lift     | Small cab                 |
| 11         | Trolleybus       | Long carriage, 3 windows  |
| 12         | Monorail         | Slim rounded body         |
| *other*    | Unknown          | Falls through to bus glyph |

Today every mode shares one capacity value
(`STARTING_VEHICLE_CAPACITY = 5`). Mode-specific capacity is a future
concern — when added, it will live as metadata on the route, not as a
behavioural switch in the dispatch / boarding code.

## Rendering (frontend)

Two Pixi overlay layers split the work:

- [`TransitOverlayLayer`](../frontend/src/app/core/service/transit-overlay-layer.ts):
  ambient transit vehicles + stop markers. Animates by GTFS stop times,
  not constant cruise speed. The same sprite that renders an ambient
  vehicle **also** renders cargo presence on that run — the sprite grows
  proportional to cargo units aboard (`scale = 1 + min(0.5, units * 0.05)`)
  and brightens (`alpha 0.7 → 0.95`) when carrying cargo. Multiple
  manifests can ride the same run, loaded at different stops, unloaded
  at different stops; the user sees one sprite that swells and shrinks.
- [`RobotPixiLayer`](../frontend/src/app/core/service/robot-pixi-layer.ts):
  cargo robots only. Buses are explicitly skipped here (the BUS branch
  early-returns) so they never draw a separate sprite — the
  TransitOverlayLayer's enlarged ambient sprite is the canonical
  cargo-bus rendering.

The cargo-units-per-run lookup is wired through
`TransitOverlayLayer.callbacks.cargoUnitsForRun(routeId, departureOffset)`,
implemented in [`map.component.ts`](../frontend/src/app/component/map/map.component.ts)
by scanning live cargo BUS vehicles whose
`bus.departsAt - boardingStop.stopArrivalGameMinutes ≈ departureOffset`.

## Wire format

Cargo lifecycle events ride the unified
[`/api/game/stream`](NETWORKING.md#sse-channels) SSE channel under the
existing vehicle-event types — no new SSE channel was added for Phase 20.
The fields a client reconstructs cargo presence from:

- `Vehicle.kind = "BUS"`, `Vehicle.routeId`, `Vehicle.departsAt` —
  enough to compute `(routeId, departureOffset)` and look up the cargo
  count per run on the frontend.
- `Vehicle.cargo: Map<ingredientId, quantity>` — sums up to total
  units aboard for the sprite-scaling math.

## Test coverage

| Test                             | Concern                                          |
|----------------------------------|--------------------------------------------------|
| `GameStateVehicleTest`           | Multi-leg chain construction + arrival fan-out.  |
| `GameStateOrdersTest`            | Order → restock → delivery happy path.           |
| `GameStateProductionTest`        | Production cycles, cycle anchors.                |
| `GameStatePlacementTest`         | Building placement validation.                   |
| `TransitSnapshotInterpolationTest` | Sparse-feed `-1` cells filled by shape distance; all-missing synthesis from `BUS_METERS_PER_GAME_MINUTE`; monotonic-sweep. |
| `GtfsMultiLegPlannerInterpolationTest` | Planner-side `interpolatedBusDuration`: full-schedule passthrough, sparse-anchor shape-interp, no-anchor distance fallback, ≥ 1 game-min clamp. |
| `RoutePlannerTest`               | Phase-21 `RoutePlanner` three-way branching: transit preferred, direct-robot fallback, `NoPath` for haversine ≥ 5 km, `NoPath` when router unreachable. |
| `VehicleDispatcherFailedDispatchTest` | `RoutePlan.NoPath` (no chain + haversine ≥ 5 km) leaves producer `producedUnits` and factory input stockpile unchanged; sanity counter-test for in-range direct-robot path. |
| `TransitDomainTypesTest`         | Phase-21 record invariants for `TransitRunId` / `CargoManifest` / `WaitingCargo` / `TransitVehicle` (immutable-update helpers) / `CargoEvent` (sealed discriminator). |
| `GameStateCargoEventsTest`       | Cargo SSE lifecycle: single-manifest `RUN_STARTED` → `CARGO_LOADED` → `CARGO_UNLOADED` → `RUN_FINISHED`; multi-manifest run with shared `TransitRunId` emits exactly one `RUN_STARTED` / `RUN_FINISHED`, with `RUN_FINISHED` strictly after the final unload. |

Filling those gaps is tracked in [ROADMAP.md](ROADMAP.md).

## Future work

The full backend redesign (replacing recursive `VehicleHandoff` with
`RoutePlan` / `CargoManifest` / `WaitingCargo` / `TransitVehicle`,
three-step boarding state machine, vehicles-only-when-cargo, full
deletion of `VehicleKind` / `Bus` / `VehicleHandoff`, frontend
`cargo-transit.service.ts`, Pixi `RenderTexture` optimisations, test-
coverage gaps) is tracked under **Active backlog** in
[`docs/ROADMAP.md`](ROADMAP.md). The current Phase 20 implementation
keeps the recursive `VehicleHandoff` model and is behaviourally
equivalent for the single-transit-leg case.

