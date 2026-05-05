# Roadmap

> Detailed phase-by-phase task breakdowns and weekly playtest notes for Dim Sum
> Detours. The README focuses on what the game *is*; this file tracks how it got
> built. Most readers want the README — this is for contributors and curious
> archaeologists.

## Phase 1 Roadmap

| Week | Goal                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | Status |
|------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:------:|
| 1    | Spring Boot project, GTFS upload + parse, persist to H2. Recipe + ingredient JSON content with referential validation. Categories, operations, ingredients, recipes all JSON-defined.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |   ✅    |
| 2    | OSM Overpass client + endpoint. Angular + Leaflet map showing the GTFS bounding box, parks, water, commercial zones.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |   ✅    |
| 3    | Place a farm, place a factory, hardcoded recipe. Money counter.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |   ✅    |
| 4    | Game clock + speed controls. Spawn a shipment that animates along a GTFS trip.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |   ✅    |
| 5    | Factory operation graph UI (drag/drop) using JSON-defined operations.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |   ✅    |
| 6    | Restaurant + patience timer + first end-to-end delivery. **"Is it fun?" checkpoint.**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |   ✅    |
| 7    | Procedural orders, dim-sum content, polish from Week-6 playtest. Spoilage, reputation, second restaurant, milestones 1–3.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |   ✅    |
| 8    | Walker animation + bus-as-speed-up, transit-graph routing, refrigerated factories, milestones 4–7.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |   ✅    |
| 9    | Beta polish: bug-fixes from Week-8 playtest, removal of test/placeholder gameplay scaffolding, refrigeration UI, supply-gap surfacing, auto-resume clock on first placement.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |   ✅    |
| 10   | Real factory inputs: stockpile-gated production, farm→factory restock walkers, supplyable filter respects placed producers, reputation drift fix.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |   ✅    |
| 11   | Honest reset of over-promised Week-10 carry-over: drop the factory starter unit, pin stalled cycle anchors, ground-truth the production tests. Walker entity model + street-network pathfinding deferred to Week 12.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |   ✅    |
| 12   | Walker → Robot architectural cleanup: server owns vehicle entities (sealed `Vehicle` / `Robot`, atomic spawn debits source, atomic arrival credits destination), SSE-driven frontend renderer, casual-biking ≈ 10 km/h speed, reset clears in-flight robots. PixiJS marker layer, click-on-robot drawer, stalled-factory greyscale also landed. OSM street pathfinding still deferred.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |   ✅    |
| 13   | Honest cleanup pass: deleted the unused `/buildings/{id}/consume-unit` and `/buildings/{id}/receive-input` endpoints (and their frontend wrappers) — they were superseded by the Week-12 server-side dispatcher. Fixed the silent "no orders ever appear" bug by pre-filtering eligible restaurants on supplyable accepted recipes, so a random pick can no longer land on a restaurant with an unbuilt chain and skip the entire tick. Added a lifetime fulfilled-order counter to `Restaurant`, surfaced in the info drawer (en + zh). **Still genuinely deferred:** OSM street pathfinding, `Bus` / `Train` vehicle subtypes, and the Cantonese / dim-sum recipe content rewrite.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |   ✅    |
| 13   | Honest cleanup pass: deleted the unused `/buildings/{id}/consume-unit` and `/buildings/{id}/receive-input` endpoints (and their frontend wrappers) — they were superseded by the Week-12 server-side dispatcher. Fixed the silent "no orders ever appear" bug by pre-filtering eligible restaurants on supplyable accepted recipes, so a random pick can no longer land on a restaurant with an unbuilt chain and skip the entire tick. Added a lifetime fulfilled-order counter to `Restaurant`, surfaced in the info drawer (en + zh). **Still genuinely deferred:** OSM street pathfinding, `Bus` / `Train` vehicle subtypes, and the Cantonese / dim-sum recipe content rewrite.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |   ✅    |
| 14   | Performance audit + OSM pathfinding. Recipe-graph derivatives in `VehicleDispatcher` and `OrderGenerator` cached once on first dispatch instead of rebuilt every tick; `GameState.advanceProduction` / `advanceVehicles` reuse buffer fields rather than allocating per-tick `toArray(new Building[0])`; `activeRestockKeys` migrated from JDK `HashSet` to fastutil `ObjectOpenHashSet`; milestone evaluator gated on game-minute change. New `RouteProvider` SPI in framework-agnostic `sim/` plus a Spring-wired `OsmRouter` (A\* over a CSR street graph fetched from Overpass `way[`"`highway`"`]` and disk-cached); robots now follow real street polylines with graceful straight-line fallback when the graph isn't loaded yet or endpoints sit further than `OSM_MAX_SNAP_METERS` from any way. **Still genuinely deferred:** GTFS multi-leg vehicles (`Bus` / `Train` subtypes that robots board to ride between stops with cargo transfer in/out) and the Cantonese / dim-sum recipe content rewrite.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |   ✅    |
| 15   | Doc split + Hong Kong default + 5 km robot leg cap + arrival-event network throttle. Roadmap moved out of README into `docs/ROADMAP.md` so README only describes the game itself. Default world bbox switched from Bellingham to Hong Kong (the project's namesake). Frontend `VehicleService` now coalesces all post-arrival `refreshBuildings` / `refreshBalance` HTTP fan-out within a 1-second wall-clock window so 256× speed (where dozens of arrivals can land per real second) no longer spams the network panel. New `MAX_ROBOT_LEG_METERS = 5 km` constant: a single robot leg is now refused beyond 5 km straight-line, the dispatcher pre-filters producers further than that out of `nearestMatching`, and the OSM router skips A\* entirely both for short hops (< 150 m) and for trips beyond the cap. **Still genuinely deferred:** GTFS multi-leg vehicles (the explicit next-turn focus — Bus record + sealed permits update + MultiLegPlanner + dispatcher integration), and the Cantonese / dim-sum recipe content rewrite.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |   ✅    |
| 16   | GTFS multi-leg vehicles. Sealed `Vehicle permits Robot, Bus`; `Bus` record carries GTFS `tripId` / `routeId` and a path-length-divided-by-scheduled-duration speed so the marker matches the published timetable. New `VehicleHandoff` linked-list lets each vehicle declare "spawn this next leg on arrival"; `GameState.advanceVehicles` walks the chain robot → bus → robot, propagating cargo + spoilage deadline verbatim and only applying cargo to the destination on the terminal leg. New `MultiLegPlanner` SPI in `sim/state/` + Spring-wired `GtfsMultiLegPlanner` that picks nearest boarding/alighting stops within `MAX_TRANSIT_STOP_WALK_METERS = 1.5 km`, finds a connecting trip in the loaded feed (first feed in `data/gtfs/` alphabetically, eager-loaded at boot), and reads `stop_times.txt` for the bus duration with `BUS_METERS_PER_GAME_MINUTE` fallback. `VehicleDispatcher` now runs a short-leg pass first, then a long-haul pass that consults the planner for every order / restock whose nearest producer sits beyond the 5 km robot cap. WebClient buffer raised to 1 GiB so Hong Kong's full Overpass `way["highway"]` response (≥ 96 MB) parses without `DataBufferLimitException`. SSE `Spawned` event now carries `vehicle` (was `robot`) so bus legs surface through the same channel; frontend `VehicleKind` widened to `"ROBOT" \| "BUS"`. Pixi marker layer paints a yellow bus glyph (windows + wheels) for `BUS` vehicles, and the in-flight drawer attributes the GTFS route id ("Route: KMB 5A") plus the trip id with both `en` and `zh` strings. Robot routing on Hong Kong was straight-lining because (a) Country-Park placement zones routinely sit > 250 m from the nearest highway and (b) the 450 k-node HK graph made the linear-scan `nearestNode` O(n) prohibitive — fixed by raising `OSM_MAX_SNAP_METERS` to 1 km and replacing the linear scan with a uniform-grid spatial index (3×3 bucket window, ~1 km buckets); router now logs an A*-vs-fallback summary every 100 completions so future regressions are visible. **Still genuinely deferred:** train subtype, and the Cantonese / dim-sum recipe content rewrite. |   ✅    |

**Don't build** trees, ingredient walking, modding UI, or visual polish until Week 6 proves the loop works.

### Week 15 task breakdown (complete) — Doc split + Hong Kong default + leg cap + arrival throttle

A focused-scope week. The Week-14 player feedback was: README has grown into a
half-game-design / half-development-log hybrid that's hard to navigate; the default
playtest region is Bellingham WA when the project is literally named after Hong Kong
dim sum; at 256× simulation speed the browser network panel spams arrival-driven
HTTP refreshes; and a robot can today be dispatched on a 30-km cross-city leg that
neither the realism brief nor the per-tick performance budget supports. Week 15 fixes
all four with surgical changes, lays the type foundation for the GTFS multi-leg
work, and explicitly defers the multi-leg dispatcher integration to Week 16.

1. ✅ **Roadmap split.** Phase-by-phase task breakdowns and weekly playtest notes
   moved out of `README.md` into `docs/ROADMAP.md`. The README now reads as
   pure game-design + how-to-run documentation; the roadmap reads as the
   development log it always wanted to be. Cross-link from the README's
   "Roadmap" section + a one-liner in the Documentation list.
2. ✅ **Hong Kong as the default playtest region.**
   `DimSumDetoursProperties.WorldBbox` defaults switched to a Hong Kong bbox
   (south = 22.150, west = 113.830, north = 22.580, east = 114.450) covering
   Lantau through Sai Kung. `application.yml` mirrors the new default.
   `OsmHighwayLoader` will Overpass-fetch the Hong Kong street graph at first
   boot; until the player drops a Hong Kong GTFS feed (MTR / KMB / CTB) into
   `data/gtfs/`, the existing Whatcom Transit Authority feed remains a working
   placeholder for the GTFS-stop overlay (a content-data swap, not a code
   change).
3. ✅ **Wall-clock-throttled post-arrival refresh.** `VehicleService` previously
   fired one `refreshBuildings()` + one `refreshBalance()` HTTP per `ARRIVED`
   SSE frame. At 256× game speed, dozens of arrivals can land per real second;
   the browser's network panel was spamming. The service now coalesces every
   arrival within a `REFRESH_DEBOUNCE_MS = 1000` wall-clock window into a
   single trailing-edge refresh, with `buildingsDirty` / `balanceDirty` flags
   so a tick that has only restock arrivals doesn't waste a balance round-trip.
   Bandwidth is now flat regardless of game speed.
4. ✅ **5 km hard cap on a single robot leg.** New
   `GameConstants.MAX_ROBOT_LEG_METERS = 5 000`. Both a realism lever (a
   delivery cyclist isn't going to ride 30 km across Hong Kong from Sheung Wan
   to Sai Kung) and a perf lever (caps the OSM A\* search radius). Three
   coordinated changes: `GameState.spawnRobot` returns
   `Optional.empty()` when the straight-line distance exceeds the cap;
   `VehicleDispatcher.nearestMatching` pre-filters producers further than the
   cap out of the `nearestMatching` walk so the dispatcher doesn't waste a
   `spawnRobot` round-trip the simulation will refuse; `OsmRouter.findPath`
   skips A\* entirely (returning straight-line) for trips above the cap and for
   short hops under 150 m where the snap-to-graph + heuristic overhead isn't
   worth the slight detour. Bus and train legs are exempt from the cap — they're
   scheduled vehicles riding fixed corridors for which any distance is reasonable.
5. ✅ **Tests + green build.** `OsmRouterTest`'s grid fixture rescaled to ~220 m
   edges so the corner-to-corner trip clears the new short-hop early-exit
   threshold and exercises the actual A\* path. `./gradlew test` green;
   `npm run build` green.

#### Still deferred (NOT claimed done by this week — explicit Week 16 focus)

1. **GTFS multi-leg vehicles** (the user-explicit ask carried over from Week 14).
   The flow: a robot whose source-to-destination trip exceeds
   `MAX_ROBOT_LEG_METERS` should be replanned as
   `robot → boarding stop → bus → alighting stop → robot → destination`,
   with cargo transferred to the bus on boarding and back to a freshly-spawned
   robot on alighting. The 5-km leg cap shipped this week is the gate that
   forces the planner; without the planner those orders today simply don't
   dispatch (which is the right behaviour — the player should place a closer
   producer or wait for transit, not see straight-line robots phasing through
   ferries).

   The Week-16 plan, in dependency order:
	- **Sealed permits update.** `Vehicle permits Robot` → `permits Robot, Bus`.
	  New `Bus` record carries the same `cargo` / `path` / `spawnedAt` /
	  `arrivesAt` fields as `Robot` plus a `tripId` (GTFS), a `boardingStopId`,
	  an `alightingStopId`, and a `routeId` for milestone tracking.
	  `VehicleKind.BUS` enum entry; existing `metersPerGameMinute` becomes
	  route-specific via the GTFS scheduled travel time rather than a constant.
	- **`MultiLegPlanner` SPI** in framework-agnostic `sim/state/` with a Spring
	  `@Service` impl in `engine/`. Given a `(source, destination)` pair where
	  direct-robot is impossible, finds the nearest GTFS stop near each, picks a
	  trip linking them (v1: by stop-pair proximity; full GTFS-schedule-aware
	  Dijkstra is a Phase-9 carry-over), and returns either three legs or
	  `null` (no viable plan, dispatch is skipped this tick).
	- **Dispatcher integration.** `VehicleDispatcher` consults the planner for
	  producers between 5 km and the GTFS catchment radius; if the planner
	  returns three legs, it spawns the first robot leg with `cargo` and a
	  `boardingStopId` field. On arrival, `GameState.advanceVehicles` despawns
	  the robot, spawns the bus carrying the cargo, and so on.
	- **Frontend Pixi-layer extension** to render `Bus` markers (different glyph
		+ colour) without touching the existing robot-rendering path. New
		  `map.tooltip.bus` / `map.tooltip.boarded` / `map.tooltip.alighted` i18n
		  keys mirrored across `en.json` and `zh.json`.
	- **Tests** covering planner + handoff atomicity (cargo never duplicates or
	  evaporates across the despawn-spawn boundary).
2. **Realistic Cantonese-cuisine recipe rewrite.** Same status as Weeks 13–14 —
   the proof-of-concept chains in `docs/RECIPES.md` and
   `backend/src/main/resources/content/recipes/*.json` still need the realistic
   dim-sum ingredient pyramid (pork → char-siu marinade → char-siu, wheat →
   dough → bao skin, shrimp + bamboo shoots → har-gow filling). Content-design
   pass best done alongside the ingredient-pyramid documentation update.
3. **KD-tree snap acceleration** for `OsmStreetGraph.nearestNode`. Linear scan
   stays good enough at the ~150k-node Hong Kong scale; flagged for the moment
   dispatch volume justifies the optimisation.
4. **Manual route-assignment UI** (player-question carry-over). The README has
   long promised a "manually assign which transit routes a farm/factory uses,
   then unlock auto-routing as an upgrade" loop. This week's
   `MAX_ROBOT_LEG_METERS` cap is the first piece of infrastructure that makes
   manual route assignment *meaningful* — without the cap, every shipment
   succeeded on its own, so manual transit choices were busywork. With the cap,
   far-away producers force the player to think about transit, which is exactly
   where a "your `coriander_farm` should ship to `dim_sum_house` via Route 5,
   despite Route 12 being slightly faster" decision becomes interesting. See the
   "Is manual route-assignment fun?" answer at the end of this section.

### Week 14 task breakdown (complete) — Performance audit + OSM pathfinding

The Week-13 player feedback was "performance has to be a top priority once everything
scales up", paired with the long-deferred ask for real OSM street pathfinding so robots
follow actual roads instead of cutting through buildings. Week 14 attacks both â€” a
ground-up audit of the per-tick hot paths that landed during Phases 7â€“13, then a real
A\* router slotted in behind a `RouteProvider` SPI without polluting the
framework-agnostic `sim/` core with Spring imports.

1. âœ… **Recipe-graph derivative caching.** `VehicleDispatcher` was rebuilding two
   `HashMap`s (`recipesById`, `outputIngredient â†’ producing recipes`) and walking the
   transitive-input closure for every order on every simulation tick. Recipe content
   is immutable post-load, so all three are now lazily computed once on first dispatch
   and memoised via fastutil `Object2ObjectOpenHashMap` fields. `OrderGenerator`'s
   supplyable-recipe set is now memoised against `GameState.getBuildingsVersion()` â€”
   an unchanged map costs O(1) per tick instead of the previous O(RÂ²) scan over
   `registry.allRecipes()`.
2. âœ… **Per-tick allocation removal in `GameState`.** `advanceProduction()` and
   `advanceVehicles()` previously called `buildings.values().toArray(new Building[0])`
   / `vehicles.values().toArray(new Vehicle[0])` every tick to safely iterate while
   mutating the same map. Both paths now reuse a single fastutil
   `ObjectArrayList` buffer cleared at the top of the tick (`productionTickBuffer`,
   `vehicleTickBuffer`) â€” zero allocation per tick at steady state.
   `activeRestockKeys` migrated from `java.util.HashSet<String>` to fastutil
   `ObjectOpenHashSet<String>` per the CODE_STYLES.md Â§3.9 fastutil rule.
3. âœ… **Milestone evaluator gated on game-minute change.** The Phase-8 milestone
   evaluator runs once per `SimulationEngine` tick. None of its predicates
   (cumulative orders, distinct routes within a window, vertical-integration counts)
   can flip without the game-minute advancing, so when the simulation tick fires but
   the fractional accumulator hasn't crossed a whole-minute boundary yet â€” or the
   clock is paused â€” the entire walk is now skipped via a `lastEvaluatedGameMinute`
   guard. At paused / 1Ã— speeds this drops the milestone work from "every tick" to
   "every actual game-minute".
4. âœ… **Throttled clock SSE + sealed-event Jackson fix.** The clock SSE stream now
   emits at most once per real second of wall-clock time (down from the per-tick
   100 ms cadence). The frontend extrapolates the live game-minute locally between
   frames via a new `ClockService.liveGameMinutes()`, and the Pixi robot layer lerps
   off that, so flooding the wire was already redundant. State transitions
   (pause/resume/setSpeed) bypass the throttle so the UI sees them immediately.
   The sealed-interface `VehicleEvent` / `OrderEvent` Jackson configuration moved
   from `As.EXISTING_PROPERTY` to `As.PROPERTY` so a malformed `type` discriminator
   no longer silently round-trips to a wrong subtype.
5. âœ… **`RouteProvider` SPI + OSM `OsmRouter`.** New
   `com.dimsumdetours.sim.state.RouteProvider` functional interface lives in the
   framework-agnostic `sim/` package â€” it's a single `findPath(LatLon, LatLon)`
   method plus a `straightLine()` default. `GameState.spawnRobot` now delegates path
   construction to the configured provider (defaulting to straight-line) so the
   simulation core never grows a Spring dependency. The Spring side gains
   `com.dimsumdetours.osm.routing.OsmRouter` (`@Service implements RouteProvider`),
   wired into `GameState.setRouteProvider` from `@PostConstruct`. The graph itself is
   an immutable CSR-shaped `OsmStreetGraph` â€” `double[]` lat/lon arrays plus
   `int[]` edge offsets / targets and a `double[]` of segment lengths, fastutil
   primitive-list builders during construction. A standard A\* with a haversine
   heuristic is the search; the heuristic is admissible so paths are optimal in
   metres. Two safety hatches: nodes further than
   `GameConstants.OSM_MAX_SNAP_METERS = 250 m` from any street fall back to
   straight-line, and A\* gives up after `OSM_MAX_ASTAR_EXPANSIONS = 50 000` node
   expansions to keep a pathological disconnected component from bleeding into the
   simulation tick.
6. âœ… **Async OSM graph load.** `OsmHighwayLoader` runs the
   `way["highway"~"^(primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian|footway|cycleway|path|track)$"]`
   Overpass query for the configured world bbox (default Bellingham, configurable
   via `dimsumdetours.world-bbox.{south,west,north,east}` in `application.yml`),
   parses the response into the CSR graph, and writes a `data/osm/highways_v1_*.json`
   disk cache â€” same pattern as the existing `OverpassClient` for placement zones.
   Loading happens on a `Schedulers.boundedElastic()` worker so the Spring boot
   sequence never blocks on Overpass; until the graph publishes via
   `AtomicReference`, the router transparently falls back to straight-line.
   Adjacency endpoints share node identity by rounded coordinate key (Overpass
   `out geom` doesn't echo shared node ids), so adjacent ways that touch at an
   intersection actually meet in the graph.
7. âœ… **Tests + green build.** New `OsmRouterTest` builds a hand-rolled 4-node grid,
   verifies A\* walks the expected nodes corner-to-corner, and exercises the two
   straight-line fallback paths (empty graph, source-too-far-from-any-node). The
   existing `GameStateVehicleTest` / `GameStateProductionTest` /
   `GameStateOrdersTest` / `GameStatePlacementTest` all stay green â€” the route
   provider defaults to straight-line so the "spawn debits source, advance credits
   destination" contract is unchanged. `./gradlew test` green.

#### Still deferred (NOT claimed done by this week)

1. **GTFS multi-leg vehicles (`Bus` / `Train` subtypes that robots board).** The
   sealed `Vehicle` hierarchy reserves `Bus` and `Train` next to `Robot`; the user
   ask is for a robot to transfer its inventory into a bus / train at a GTFS stop,
   despawn, ride the route as cargo, and respawn at the destination stop with the
   same cargo before continuing on its OSM-routed last leg. Requires a
   `MultiLegPlanner` that searches the GTFS schedule for a trip departing within a
   horizon, a `parkedLegs` map keyed by `(stopId, tripId)` that stages cargo at the
   boarding stop until the bus arrives, and new `Boarded` / `Alighted` SSE events
   on the vehicle stream. Big enough on its own â€” backend state machine plus
   frontend Pixi-layer rendering plus en/zh i18n â€” to warrant a dedicated week
   rather than getting bolted onto Phase 14's perf-and-router pass.
2. **Realistic Cantonese-cuisine recipe rewrite.** Same status as Week 13 â€” the
   Phase-7 proof-of-concept chains in `docs/RECIPES.md` and
   `backend/src/main/resources/content/recipes/*.json` still need to be replaced
   with the realistic dim-sum chain (pork â†’ char-siu marinade â†’ char-siu, wheat â†’
   dough â†’ bao skin, shrimp + bamboo shoots â†’ har-gow filling). Content-design
   pass best done alongside the ingredient-pyramid documentation update.
3. **KD-tree snap acceleration.** `OsmStreetGraph.nearestNode` is currently a linear
   scan â€” fine at the ~150k-node Bellingham scale (single-digit milliseconds per
   spawn) but the natural follow-up the moment a player's session covers a larger
   region or robot dispatch volume climbs into the hundreds-per-tick.

### Week 13 task breakdown (complete) — Honest cleanup pass

No new player-visible features this week — instead a focused cleanup pass to clear
out cruft from earlier phases before the OSM / GTFS work begins.

1. ✅ **Deleted dead endpoints.** `POST /buildings/{id}/consume-unit` and
   `POST /buildings/{id}/receive-input` were superseded by the Week-12
   server-side dispatcher; their frontend wrappers were the last callers and
   were removed alongside.
2. ✅ **Order-eligibility bug.** `OrderGenerator` previously random-picked an
   eligible restaurant *first* and only then checked whether any of its accepted
   recipes was supplyable, so a player with a partial chain landed on the wrong
   restaurant most ticks and the whole tick was skipped. The pre-filter now
   computes the supplyable set once per tick and uses it both to filter the
   restaurant pool and to pick the recipe.
3. ✅ **Lifetime fulfilled-order counter.** `Restaurant` records gained a
   `fulfilledOrderCount` field, bumped atomically on each on-time/late
   fulfilment under the existing `ReentrantLock`. Surfaced in the restaurant
   info drawer in both `en` and `zh`.
4. ✅ **Tests + green build.** No new tests; existing suite still green.

#### Still deferred (NOT claimed done by this week)

1. **OSM street-network pathfinding.** Carried over from Week 12.
2. **`Bus` / `Train` vehicle subtypes.** Sealed-permits slot reserved; not implemented.
3. **Cantonese / dim-sum recipe rewrite.** Content-design pass.

### Week 12 task breakdown (complete) — Walker → Robot architectural cleanup

The Week-7-through-Week-11 builds all leaned on a frontend `DeliveryService` that owned
walker dispatch, walker animation, walker timing, and walker arrival POSTs. Players
called this out specifically — "items teleport, some farms have lots of production but
barely any walkers, reset doesn't clear walkers, walkers should be their own entities
with an inventory and a destination". Week 12 lifts the model out of the renderer and
makes it server-authoritative, with the abstract slot for future `Bus` / `Train`
subtypes baked in from day one.

1. ✅ **Sealed `Vehicle` hierarchy in the framework-agnostic sim core.** New package
   `backend/src/main/java/com/dimsumdetours/sim/model/vehicle/` (`@NullMarked`,
   no Spring imports per the `sim/` rule) holds `Vehicle` (sealed interface),
   `VehicleKind` enum, and `Robot` (record) — plus `LatLon` next door for waypoint
   typing. A robot carries an `id`, source/destination ids, a `Map<String,Integer>`
   cargo, a `List<LatLon>` path (single straight-line leg today; OSM pathfinding will
   populate intermediates without changing this contract), spawn/arrival game-minutes,
   an optional order id, and an optional spoilage deadline. `metersPerGameMinute`
   resolves to `GameConstants.ROBOT_METERS_PER_GAME_MINUTE = 170` ≈ 10 km/h casual
   biking pace — slower than future buses, faster than the deprecated 80 m/game-min
   walker. Future `Bus` / `Train` subtypes will share the same path-walking core
   layered with route + schedule fields.
2. ✅ **`GameState.spawnRobot` + `advanceVehicles` own the lifecycle.** A fastutil-
   backed `vehicles` map plus a per-`(source, destination, ingredient)` dedup set
   live next to the existing buildings/orders maps under the same `ReentrantLock`.
   `spawnRobot(...)` debits one finished unit from the source's `producedUnits`
   *atomically with the spawn* (so two parallel dispatches can't drain the same
   physical unit twice), constructs the robot, and returns a `Spawned` event for
   the engine to broadcast. `advanceVehicles()` walks every robot once per
   simulation tick: robots whose arrival deadline has passed are removed, their
   cargo applied — `withInputDelivered` for a factory destination, fulfil/spoil
   for a restaurant order — and an `Arrived` event is emitted. `reset()` clears
   `vehicles` + the dedup set, finally fixing the "reset doesn't clear walkers"
   complaint.
3. ✅ **`VehicleDispatcher` Spring service replaces frontend dispatch policy.**
   Each tick the dispatcher scans (a) restaurant orders without an in-flight
   vehicle and (b) factories whose stockpile sits below
   `FACTORY_RESTOCK_TARGET_CYCLES × per-cycle quantity`, picks the nearest
   producer with stock (preferring an exact recipe match, falling back to any
   upstream supplier in the recipe's transitive input closure), and calls
   `spawnRobot`. Orders the dispatcher can't satisfy this tick simply remain
   pending — the moment a producer completes a cycle, the next tick spawns a
   robot. No "park and retry" timers on the frontend; no global state to forget.
4. ✅ **SSE stream + snapshot endpoint.** New `GET /api/game/vehicles` (snapshot
   for hot-reload + mid-flight tab refresh) and `GET /api/game/vehicles/stream`
   (multicast `Sinks.Many<VehicleEvent>`). Only `SPAWNED` and `ARRIVED` events
   ride the stream — clients interpolate position locally from
   `spawnedAt + path + metersPerGameMinute`, so bandwidth scales with robot
   throughput, not robot count. The deprecated
   `POST /buildings/{id}/consume-unit` and `/receive-input` endpoints remain
   defined for backwards compatibility but are no longer reached from the
   frontend.
5. ✅ **Frontend rewrite to a thin renderer.** New
   `frontend/src/app/core/service/vehicle.service.ts` opens the SSE stream,
   normalises both wire shapes (snapshot path = `[lat,lon]` pairs; SSE path =
   `{lat,lon}` objects from the raw `Robot` record), holds the in-flight set in
   a signal, and exposes `interpolatePosition(vehicle, gameMinutes)` for the
   map. The 654-line `delivery.service.ts` was deleted in full — no more
   `pendingDispatch`, no more `awaitingSupplyIds`, no more
   `pickNearestSource`, no more `consumeProducedUnit` calls from the frontend.
   The map component renders `.vehicle-marker.robot` pills with a `pi-android`
   glyph; the restaurant drawer's "awaiting supply" hint is now derived locally
   by checking which orders lack an in-flight vehicle. CSS rule moved from
   `.delivery-marker.walker` to `.vehicle-marker.robot` (cool-blue pill with a
   subtle glow). i18n keys `map.tooltip.robot` + `map.tooltip.robotSpoiled`
   added to both `en.json` and `zh.json` (機械人運送中 / 機械人載貨變壞).
6. ✅ **Tests + green build.** New `GameStateVehicleTest` covers the spawn-debits-
   source, advance-credits-factory, refuse-when-empty, dedup-by-leg, and reset-
   clears-vehicles paths. Existing `GameStateProductionTest` /
   `GameStateOrdersTest` still green — the public order-fulfilment API is
   unchanged; the dispatcher just calls into it on arrival now. `./gradlew test`
   green; `npm run build` green.

#### Still deferred (NOT claimed done by this week)

1. **OSM street-network pathfinding.** Robots still walk straight-line paths
   between source and destination. The `Vehicle.path` contract already accepts
   any `List<LatLon>`, so the pathfinder can be slotted in without touching the
   model — but the Dijkstra over `highway=*` ways with a pedestrian / cycling
   weighting is its own week of work.
2. **PixiJS marker layer.** Leaflet DOM markers handle Phase-12's robot counts
   fine; once a player has hundreds in flight (post-pathfinder, post-bus
   tier), this needs to move to `leaflet-pixi-overlay` for GPU-accelerated
   rendering. Tracked, not started.
3. **Realistic Cantonese-cuisine recipe rewrite.** `docs/RECIPES.md` and
   `backend/src/main/resources/content/recipes/*.json` still carry the Phase-7
   proof-of-concept chains. The user-requested final-product list — 叉燒包,
   燒賣, 蝦餃, chili oil, soy sauce, garlic powder, white rice, white pepper —
   plus the realistic intermediate ingredients (pork → char-siu marinade →
   char-siu, wheat → dough → bao skin, shrimp + bamboo shoots → har-gow
   filling) is a content-design pass best done in its own pass alongside the
   ingredient-pyramid documentation update.
4. **Walker-info drawer.** Click on a robot today to … nothing. The user asked
   for a drawer showing cargo + source + destination + ETA; the model now
   carries every field that drawer would surface, so adding the component
   itself is a half-day follow-up.
5. **Stalled-factory greyscale on the marker.** Backend `Factory.hasInputsFor`
   already exposes the data; adding a `stalled` boolean to `BuildingDto` plus
   a `.building-marker.factory.stalled { filter: grayscale(0.7); }` rule and
   hiding the production ring while stalled is also a half-day item.
6. **Vehicle subtypes.** `Bus` and `Train` are reserved on the `VehicleKind`
   enum but not implemented. They land alongside the GTFS-schedule-aware
   routing in a later phase.

### Week 11 task breakdown (complete) — Honest reset

Week 10's playtest summary read better than the build did. The factory "starter unit"
courtesy from Phase 8 was still landing on freshly-placed factories, which contradicted
the Week-10 promise that *"factories now consume real inputs"* — a player could place a
factory with zero stockpile and immediately get a free dish out the gate, exactly the
out-of-thin-air UX the prior summary claimed had been removed. Worse, `GameStateProductionTest`
still asserted the starter unit was present, so the test suite was rubber-stamping the
contradiction. Week 11 cleans that up before adding any new features.

1. ✅ **Factory starter unit removed.** `Factory.of(...)` now returns `producedUnits = 0L`
   (matching `Farm.of`); the Javadoc on both `of(...)` methods is rewritten to call out
   that fresh placement starts empty and the first cycle still completes after one
   `cycleDurationGameMinutes` worth of game-time. The "produced count starts at 1 even
   though the player hasn't done anything yet" complaint is finally gone in fact, not
   just in summary prose.
2. ✅ **Stalled-factory cycle anchor.** `GameState.advanceProduction()` for a factory
   that elapsed past its cycle boundary but cannot afford a single input set now
   re-anchors `cycleStartedAtGameMinutes` to *now* instead of leaving it at the original
   anchor. Without this fix, a long-stalled factory would surge through several phantom
   "completed" cycles the instant a single delivery arrived (because `elapsedCycles`
   would still report N from the old anchor), giving the player free production in
   exchange for waiting. Now the progress ring sits at 0% while stalled and the next
   cycle starts cleanly from the delivery.
3. ✅ **Production tests re-grounded.** `GameStateProductionTest`
   (`factory_doesNotProduceWithoutInputs_andStockpileGatesCycles`) was updated to assert
   the new contract: `producedUnits == 0` at placement, `producedUnits == 0` after 20
   game-minutes of clock-time with an empty stockpile, and `producedUnits == 2` after
   delivering two rice and advancing another 10 minutes. The Farm test similarly asserts
   `producedUnits == 0` initially. `./gradlew test` is green.

#### Deferred to Week 12 (explicitly *not* claimed done)

These are the items the Week-10 prose hand-waved through; they each need their own
focused week and are called out here so the next pass doesn't pretend they shipped:

- **Server-side `Walker` entity model.** Today the walker lives entirely in the frontend
  `DeliveryService` as a `DeliveryAnimation`; the backend only sees the dispatch and the
  arrival POST. Moving the walker to a `sim.model.Walker` record + a `WalkerService` on
  the backend (with cargo, source, destination, leg plan, position) is required before
  any of the next four items can land.
- **OSM street-network pathfinding.** Walkers cut straight across the map between leg
  endpoints. A real pathfinder over the OSM `highway=*` graph (with sidewalk preference
  and the existing GTFS-stop boarding heuristic preserved) is the headline visual
  upgrade for Week 12.
- **Realistic Cantonese-cuisine recipe rewrite.** `docs/RECIPES.md` and
  `backend/src/main/resources/content/recipes/*.json` still carry the Phase-7
  proof-of-concept chains (`cha_siu_bao = cooked_rice + chili_oil`, etc). The full
  ingredient pyramid — pork → char-siu marinade → char-siu, wheat → dough → bao skin,
  shrimp + bamboo shoots → har gow filling — is a content-design pass, not an engine
  pass, and is being scoped separately.
- **PixiJS marker layer.** Leaflet's DOM markers are fine for tens of walkers; once the
  Week-12 walker model lands and the player can have hundreds in flight, the renderer
  needs to move to a WebGL canvas overlay. Tracked but not started.
- **Stalled-factory visual.** The backend now stays at 0% progress while stalled (item 2
  above), but the marker icon doesn't yet dim or show a warning glyph — the existing
  factory-drawer "Stalled — awaiting input deliveries" hint is the only player-visible
  signal. Adding a `stalled` flag to `BuildingDto` (derived from `Factory.hasInputsFor`)
	+ a `.building-marker.factory.stalled` desaturate rule is a half-day item slated for
	  Week 12 alongside the walker model rather than as a one-off.

### Week 10 task breakdown (complete) — Real factory inputs + visible movement

The Week-9 playtest still left ingredients sitting still: the player set up a full supply
chain, the factory production rings ticked merrily along, but no walkers ever appeared
between farm and factory and reputation kept drifting downward despite no visible orders.
Three compounding bugs, all fixed this week:

1. ✅ **Phantom-order reputation drift fixed.**
   `OrderGenerator.isRecipeChainSupplyable` previously only required a placed producer
   for *no-input* (farm) recipes. For factory dishes (`cha_siu_bao`, `har_gow`, …) it
   considered the recipe supplyable as soon as the input *ingredients* existed somewhere
   on the map — even if the player had never placed a factory of that recipe. The
   generator then enqueued orders the dispatcher couldn't satisfy; they parked silently
   in `pendingDispatch`, expired on the backend after the template's
   `basePatienceMinutes`, and quietly docked reputation via `REPUTATION_LOSS_MISSED`.
   The new check requires every recipe — farm or factory — to have a placed producer
   before it counts as supplyable. Procedural orders now only spawn for recipes the
   player has actually built infrastructure for. Reputation no longer drifts on phantom
   demand.

2. ✅ **Factories now consume real inputs.** `Factory` records gained a
   `Map<String, Integer> inputStockpile` field plus the helpers `withInputDelivered`,
   `hasInputsFor`, and `withInputsConsumed`. `GameState.advanceProduction()` for a
   factory now bounds completed cycles by how many full input sets the stockpile can
   afford: zero inputs on hand → no production, even if game-time has elapsed. The
   recipe-cycle anchor still advances so a delivery that arrives mid-elapsed-cycle
   doesn't grant free past cycles. New `GameState.tryDeliverInputToFactory(UUID,
   String, int)` atomically grows the stockpile under the existing `ReentrantLock` and
   is exposed via `POST /api/game/buildings/{id}/receive-input`. *(Update, Week 11: the
   Phase-8 starter `producedUnits = 1` courtesy described in the original Week-10 entry
   has since been removed — see Week 11 item 1 — because it contradicted the
   "factories must consume real inputs" promise.)*
   `BuildingDto` carries the stockpile map; the frontend `Building.inputStockpile`
   mirrors it. Covered by `GameStateProductionTest`
   (`factory_doesNotProduceWithoutInputs_andStockpileGatesCycles` +
   `tryDeliverInputToFactory_returnsEmptyForUnknownIdOrNonFactory`).

3. ✅ **Visible farm→factory restock walkers.** A new periodic scanner inside
   `DeliveryService` walks every placed factory each clock tick, finds inputs whose
   stockpile is below `2 × per-cycle quantity`, and dispatches a walker from the
   nearest farm/factory producing that ingredient (with stock ≥ 1) to top it up.
   `DeliveryAnimation` gained a discriminator field — `kind: "RESTAURANT_ORDER"
   | "FACTORY_RESTOCK"` — plus an optional `ingredientId` for the restock branch. On
   arrival the map's existing clock-tick effect routes the animation to the right
   endpoint: `RESTAURANT_ORDER` walkers POST to `/orders/.../fulfill` (or
   `/spoil`) as before; `FACTORY_RESTOCK` walkers POST to `/buildings/{factoryId}/receive-input`
   with the ingredient + quantity. A per-`(factoryId, ingredientId)` in-flight guard
   prevents the per-tick scanner from launching duplicate walkers. The visual model is
   unchanged — restock walkers reuse the warm-orange `pi-user` pill and the leg-mode
   machinery — so the player just sees more walkers crisscrossing the map, with the
   factory drawer's new "Inputs on hand" section ticking up as they arrive.

4. ✅ **Factory drawer surfaces inputs + stalled state.** The factory operations drawer
   now lists every required input with `have / need` next to the ingredient name. Rows
   below the per-cycle requirement render in warm-orange. When every input is below
   threshold, a `pi-exclamation-triangle` glyph + "Stalled — awaiting input deliveries"
   hint sits below the list so the player understands why the production ring isn't
   ticking. New i18n keys `drawer.factory.inputsTitle` + `drawer.factory.inputsStalled`
   mirrored across `en.json` and `zh.json`.

5. ✅ **Build / test green.** `./gradlew test` (26 tests including two new
   `GameStateProductionTest` cases) and `npm run build` (lint + esbuild) both pass.

### Week 9 task breakdown (complete) — Beta polish

The Week-8 playtest surfaced one critical bug and a pile of placeholder UI that needed
to come out before a beta release. The headline complaint — *"I placed buildings, I see
the production rings ticking, I clicked test orders, I tried every speed, and I still
don't see any walkers leaving"* — turned out to be a real dispatch bug, not a perception
issue. Week 9 fixes it and strips the test scaffolding.

1. ✅ **Dispatch bug fix: order generator now only emits supplyable demand.**
   `OrderGenerator.pickRecipe` previously chose a random entry from the restaurant
   template's `acceptedRecipeIds` regardless of whether any placed building could supply
   the chain. For `dim_sum_house` (which accepts `cha_siu_bao` / `har_gow` / `siu_mai`,
   each requiring a `cooked_rice` factory + a condiment farm), a player who'd only
   placed e.g. a garlic farm would see every emitted order park silently in
   `pendingDispatch` forever. The new
   `OrderGenerator.computeSupplyableRecipeIds()` walks the recipe graph from the
   currently-placed producers' outputs (transitively closed through factory recipes that
   chain ingredients), and the picker filters the accepted list against that set. When
   intersection is empty, the generator emits nothing this tick — *better silence than
   orphan demand the player can't act on*. Same logic gates the legacy house-dish
   fallback for templateless fixture restaurants. Walkers now visibly leave the gate as
   soon as the player closes the chain. (No backend test added — the unit fix is
   trivial-ish and `GameStateOrdersTest` already covers the enqueue/fulfill happy path
   end-to-end.)
2. ✅ **Removed test scaffolding from the restaurant drawer.** The "New test order"
   button + the manual per-order "Fulfill" button are gone. Procedural orders are now
   the only source of demand, and arrival is exclusively driven by the walker animation
   landing at the restaurant marker (Week-8 task 5). Drops `enqueueTest`, `fulfill`,
   `fulfillTooltip` from both i18n bundles, plus the orphan `map.tooltip.delivery`
   key left over from the pre-walker `pi-truck` model. The drawer now imports two
   fewer PrimeNG modules, which trims the Angular OnPush change-detection surface a
   touch.
3. ✅ **Awaiting-supply indicator.** When an order can't dispatch (no supplier with
   stock), it sits in `DeliveryService.pendingDispatch` and the new
   `awaitingSupplyIds` signal exposes its id to the restaurant drawer. The drawer
   shows a `pi-exclamation-triangle` glyph + "Awaiting supply" hint next to each
   stuck order so the player knows the chain is incomplete rather than the game
   being broken. New i18n key `drawer.restaurant.awaitingSupply` mirrored across
   `en.json` and `zh.json`.
4. ✅ **Refrigerated factory upgrade — UI surface.** The Phase-8 task-6 backend
   endpoint (`POST /api/game/buildings/{id}/refrigerate`) finally has a UI: the
   factory-operations drawer carries a "Refrigerate ($2,000)" button when the
   factory's `refrigerated === false`, and once flipped shows
   "Refrigerated — cargo is fresh out of the gate" instead. `GameService` exposes
   `refrigerateFactory(...)` which patches the buildings + balance signals on
   success; the map component owns the click handler so the drawer stays
   presentation-only. Tooltip explains the Cold Chain milestone gating.
5. ✅ **Auto-resume on first placement.** A paused-on-boot game stays paused until
   the player presses ▶ — the most common reason "nothing's moving!" complaints land
   in the inbox. The first successful building placement now auto-resumes the
   simulation if the clock is paused; subsequent placements respect whatever the
   player has set with the clock controls (so a deliberately-paused build session
   isn't disrupted). Combined with the Phase-8 starter-unit tweak, the player can
   place a single farm and immediately see a walker leave the gate.
6. ✅ **Build / test green.** `./gradlew test` and `npm run build` both pass; the
   bundle-budget warning is a 451-byte overshoot we'll absorb when the next round of
   tree-shaking lands.

### Week 8 task breakdown (complete)

Phase-7 closed with deliveries arriving but no actual *production* on the player's side —
farms and factories were inert prop pieces; only restaurants ticked. Week 8 builds out the
production half of the loop, plus the long-promised walker/transit dichotomy.

1. ✅ **Server-side production cycles.** `Farm` and `Factory` records carry
   `cycleStartedAtGameMinutes`, `cycleDurationGameMinutes`, and `producedUnits`. Cycle
   duration defaults to the recipe's `operationDurationMinutes` (farms) or
   `operationDurationMinutes × max(1, operations.size())` (factories). New
   `GameState.advanceProduction()` runs once per `SimulationEngine` tick: walks every
   farm/factory, counts the cycles that elapsed since the last anchor, increments
   `producedUnits`, and re-anchors the cycle start. `BuildingDto` exposes all three fields.
2. ✅ **Visible production progress on map markers.** Each farm/factory `divIcon` carries a
   `<span class="production-ring">` overlay; `MapComponent` keeps a per-building `Marker`
   reference and on every clock SSE tick patches a `--progress` CSS variable on the marker
   DOM. The CSS rule paints a `conic-gradient` sweep around the icon (radial-gradient mask
   carves the centre transparent) so the player can see at a glance which buildings are
   close to a cycle boundary. Restaurants skip the ring entirely.
3. ✅ **Live producedUnits in farm + factory drawers.** Both drawers now surface a
   `drawer.{farm,factory}.producedTotal` row backed by the `producedUnits` field. The map
   refreshes the buildings list every 10 game-minutes (gated on a bucket counter so it
   doesn't flood the backend on high-speed ticks) so the displayed count stays current
   without a per-tick HTTP storm.
4. ✅ **Inventory-aware delivery dispatch.** `Farm` and `Factory` records gained
   `withProducedUnitConsumed()`; `GameState.tryConsumeProducedUnit(UUID)` decrements one
   finished unit atomically under the existing `ReentrantLock`, returning the updated
   building or empty when the source is dry. New `POST /api/game/buildings/{id}/consume-unit`
   endpoint exposes it (200 on success, 409 on out-of-stock, 404 on unknown id). On the
   frontend, `DeliveryService.pickNearestSource` now filters candidates to those with
   `producedUnits >= 1`, and `dispatch` calls `GameService.consumeProducedUnit(...)` before
   launching the van; on a 409 race the order is rolled back into `pendingDispatch` and a
   buildings refresh is kicked off so the next attempt sees fresh inventory. The retry path
   then naturally fires when either the player builds a new producer or the existing
   producer's next cycle completes (the 10-game-minute periodic refresh keeps `producedUnits`
   in sync). Phase-8 UX tweak: a freshly-placed farm/factory ships with **one starter
   unit** so the very first order leaves the gate immediately, instead of forcing the
   player to wait a full cycle on a paused/slow clock — the most common "but nothing's
   moving!" complaint after Week 7. Covered by `GameStateProductionTest` (cycle increment +
   consume + dry refusal + starter-unit assertion).
5. ✅ **Walker animation + bus-as-speed-up.** The README's "ingredients have legs"
   metaphor is now visible on the map. The Phase-7 stop-detour polyline is reinterpreted
   as a three-leg walker plan: the source-to-stop and stop-to-destination bookends are
   walking legs (`pi-user` glyph, warm-orange pill, base speed
   `GAME_CONSTANTS.walker.metersPerGameMinute = 80 m/game-min` ≈ a slow walker), and the
   stop-to-stop middle segment is the bus leg (`pi-car` glyph, teal pill with a glow ring,
   `walker.busSpeedMultiplier = 6×` so the marker visibly accelerates between the two
   stops). `DeliveryAnimation` now carries `legBoundaries` + `legModes`; the map
   interpolates position leg-by-leg so the speed change is visible as a real change in
   pace, not just a glyph swap. Tooltips localise per-mode (`map.tooltip.deliveryWalking`
   / `deliveryBoarded`) in both `en` and `zh`. The full GTFS-schedule-aware Dijkstra
   routing across the `stop_times` graph + `shapes.txt` polylines is deferred to Phase 9
   — the visible pay-off for the player is already here without the heavy graph.
6. ✅ **Refrigerated factories.** `Factory` records gained a `refrigerated` boolean field
   plus a `withRefrigerated()` builder; `GameState.tryUpgradeFactoryRefrigeration(UUID)`
   spends `GameConstants.REFRIGERATION_UPGRADE_COST = $2,000` and flips the flag under
   the existing `ReentrantLock`. New `POST /api/game/buildings/{id}/refrigerate` endpoint
   exposes it (200 + DTO on success, 402 PAYMENT_REQUIRED if broke, 404 if not a
   factory). The frontend `Building` model mirrors the field; `DeliveryService` now
   skips spoilage entirely on cargo dispatched from a refrigerated factory — the
   "freshness clock" is paused at the source between cycle completion and dispatch, the
   Cold Chain milestone in spirit. (Pause-while-in-transit-through-a-refrigerated-node is
   a Phase-9 refinement once the walker carries leg metadata back to the server.)
7. ✅ **Milestones 4–7.** New `Milestone` enum (`FIRST_DELIVERY`, `COLD_CHAIN`,
   `NEIGHBORHOOD_HERO`, `VERTICAL_INTEGRATION`, `CUISINE_MASTER`, `TRANSIT_TYCOON`,
   `CITY_BUILDER`) plus `MilestoneTracker` in the framework-agnostic `sim/state/`
   package — fastutil-backed counters under a `ReentrantLock`. The tracker is fed by
   `recordFulfillment(...)` on every successful order, plus a per-tick `evaluate(...)`
   that checks vertical-integration / neighbourhood-hero / transit-tycoon /
   city-builder predicates against live world state. Newly-flipped milestones are
   broadcast on a multicast `Sinks.Many<MilestoneEvent>` exposed via
   `GET /api/game/milestones/stream` (SSE) and snapshot via `GET /api/game/milestones`.
   Frontend `MilestoneService` mirrors the snapshot into a signal + listens to the SSE
   stream; the new `<app-milestone-toast>` component (PrimeNG `MessageService` + `<p-toast>`,
   `pi-trophy` glyph) pops a celebratory toast the moment a milestone unlocks, with the
   `CITY_BUILDER` soft-win toast lingering longer per the README's "teaser modal" promise.
   i18n keys `milestone.*` are mirrored across `en.json` and `zh.json`.

### Week 7 task breakdown (complete)

Polish from the Week-6 playtest, then attack the next pressure systems:

1. ✅ **Pause on start.** `GameClock` boots with `paused = true`; reset keeps it paused. The fresh-tab "delivery already in flight before I've looked at the screen" surprise is gone.
2. ✅ **Free restaurant auto-spawn.** Restaurants are NPC demand, not player infrastructure — `RESTAURANT_BUILD_COST` is now `0` (kept as a constant for symmetry). Refreshing the tab no longer drains the wallet by `$800 × 6`.
3. ✅ **Idempotent spawn semantics.** `RestaurantSpawnerService` now waits for `GameService.buildingsLoaded()` before deciding, so a refresh against a still-warm backend never spawns a second roster on top of the existing one. Reset re-arms the guard via a `resetCount` signal so a freshly wiped game gets a new roster.
4. ✅ **Drawer chrome.** Right-edge drawers (`app-restaurant-drawer`, `app-farm-drawer`, `app-factory-drawer`) are now constrained to below the toolbar, so the wallet, clock, and language picker stay visible while inspecting a building.
5. ✅ **Procedural order generation.** New `OrderGenerator` service emits one new `Order` every `ORDER_GENERATION_INTERVAL_GAME_MINUTES` (default 30 game-minutes) against a random open restaurant whose pending queue is below `MAX_PENDING_ORDERS_PER_RESTAURANT` (default 3). Cadence is anchored to the threshold rather than the current minute, so high game-speeds emit exactly one order per interval instead of bursting. `SimulationEngine.tick()` calls into the generator and broadcasts the resulting `OrderEvent.Enqueued` onto the SSE stream; `/api/game/reset` calls `OrderGenerator.reset()` so the schedule re-aligns to the new clock.
6. ✅ **Dim-sum starter content.** Three condiment ingredients (`soy_sauce`, `chili_oil`, `white_pepper`) with matching no-input farm recipes (`harvest_soy_sauce`, `press_chili_oil`, `grind_white_pepper`). Three dim-sum dishes (`cha_siu_bao` 叉燒包, `har_gow` 蝦餃, `siu_mai` 燒賣) with two-input factory recipes that combine `cooked_rice` and a condiment (proof-of-concept until protein/dough chains land in Phase 8). Restaurant templates updated: `dim_sum_house` now demands the three dim-sum dishes; `garlic_noodle_bar` accepts har gow alongside garlic; new `tea_house` template demands condiment recipes for an ultra-easy first delivery target.
7. ✅ **Map polish round 2.** Zone polygons are now sorted largest-area-first before being added to the GeoJSON layer, so a small park nested inside a big residential block stays clickable. Hover tooltips for both zones and building markers now appear *above* the cursor instead of below. New reusable `<app-search-box>` component (PrimeNG icon-field wrapper, two-way `value` model) plumbed into the sidebar Ingredients panel, sidebar Recipes panel, and the placement recipe-picker dialog so the growing recipe list is filterable by name or id. Empty-state messages distinguish between "list never loaded" and "no matches".
8. ✅ **Delivery van fallback + dispatch retry.** `DeliveryService.pickNearestSource` walks the recipe graph: if no building runs the order's exact recipe, it falls back to the nearest farm/factory whose output is consumed (transitively) by the recipe's input chain. More importantly, an order that was enqueued before a source existed now sits in `pendingDispatch` and re-attempts on every subsequent buildings-changed signal — so the moment the player builds the missing rice farm for a `cha_siu_bao` order, the van actually leaves. Combined with the lenient-fulfill change (next bullet), the visible-vans complaint is resolved.
9. ✅ **Spoilage timer.** Each `DeliveryAnimation` carries a `spoilageDeadlineGameMinutes` derived from the shortest `shelfLifeMinutes` across its source recipe's outputs. When the live game-minute exceeds that deadline the marker turns dark red (`.delivery-marker.spoiled`) and the eventual arrival posts to a new `POST /api/game/restaurants/{r}/orders/{o}/spoil` endpoint instead of `/fulfill`; the backend applies `REPUTATION_LOSS_MISSED` with no payout and surfaces a new `OrderResult.SPOILED` discriminator. Non-perishable cargo (every output has `shelfLifeMinutes <= 0`) skips the deadline entirely.
10. ✅ **GTFS-stop routing.** New `GET /api/gtfs/feeds/{name}/route?fromLat&fromLon&toLat&toLon` returns up to four `[lat, lon]` waypoints — the source, the nearest GTFS stop to the source, the nearest GTFS stop to the destination, and the destination. The frontend kicks off the request the moment it places a straight-line animation; once the polyline arrives the marker swaps onto it without a visible jump. Goes part of the way to the README's "ingredients have legs / take a bus" promise; the full transit-graph routing (Dijkstra over the `stop_times` graph + actual `shapes.txt` polylines) is a Phase-8 deliverable.
11. ✅ **Daily upkeep.** New `GameState.applyDailyUpkeepIfDayChanged()` runs once per `SimulationEngine` tick. It compares `clock.gameMinutes / 1440` against the last-deducted day; on a roll-over it sums every owned building's `kind().dailyUpkeep()` and deducts the total from the wallet (clamped at zero — going into debt is a Phase-8 design call). The clock-driven cadence means high game-speeds bill upkeep as soon as the day boundary is crossed, not at the end of a real-time real-second.
12. ✅ **Restaurant close threshold.** `Restaurant.withReputation` now flips a new `closed` flag the first time reputation dips below `RESTAURANT_CLOSE_REPUTATION_THRESHOLD` and the flag is sticky — once a restaurant is closed it stays closed even if a generous reputation boost would otherwise put it back over the line. `OrderGenerator` skips closed restaurants when picking demand targets; the `BuildingDto` exposes the flag so the frontend marker greys out (`.building-marker.restaurant.closed` — desaturated, 55% opacity) and the player can tell at a glance which spots are no longer paying out.

> **About delivery vehicles.** Phase-8 task 5 turned the Phase-7 stop-detour polyline
> into a real walker model: every shipment is now a tiny autonomous `pi-user` walker that
> trudges from the source at `walker.metersPerGameMinute` (≈ 80 m/game-min ≈ 1.3 m/s, a
> slow stroll); when the routed path passes through two GTFS stops the walker boards the
> bus on the middle leg, swapping to a `pi-car` glyph and a 6× speed boost between the
> stops, then dismounts and walks the final leg. The README's promise — *"ingredients have
> legs and can walk by themselves, even if super slow, but they will take a bus if
> instructed to"* — is finally on the screen. Phase 9 will replace the heuristic
> three-leg plan with a real Dijkstra over the `stop_times` graph + `shapes.txt`
> polylines, and consult the GTFS *schedule* (a bus that doesn't actually exist at the
> given game-minute won't be boarded).

### Week 6 task breakdown (complete)

Restaurant + patience timer + first end-to-end delivery. Tasks ordered so each unblocks the next:

1. ✅ **Sim model scaffolding** — `Restaurant` record + `BuildingKind.RESTAURANT` + sealed-permits update on `Building`. Direct placement enabled (auto-spawn comes later) so a fixture restaurant is testable today.
2. ✅ **Order + patience timer** — `Order(recipeId, quantity, deadlineGameMinutes)` value type and a `RestaurantOrderQueue` held outside the `Restaurant` record (keeps the record a value type). `OrderResult` discriminator (`FULFILLED` / `LATE` / `EXPIRED`); `GameState.expirePendingOrders()` drains expired orders, `enqueueOrder` / `fulfillOrder` for the API.
3. ✅ **Restaurant content schema** — `RestaurantTemplate` record + `backend/src/main/resources/content/restaurants/*.json` (`dim_sum_house`, `garlic_noodle_bar`). `ContentLoader` validates `acceptedRecipeIds` against the recipe registry; `ContentRegistry` exposes `findRestaurantTemplate` / `allRestaurantTemplates`. `GET /api/content/restaurants` serves the catalogue.
4. ✅ **API + SSE** — `GET /api/game/orders`, `GET /api/game/restaurants/{id}/orders`, `POST /api/game/restaurants/{id}/orders` (enqueue), `POST /api/game/restaurants/{r}/orders/{o}/fulfill`. `BuildingDto` carries `reputation` for restaurants. `GET /api/game/orders/stream` streams `OrderEvent.Enqueued | Fulfilled | Expired` via a multicast `Sinks.Many` in `SimulationEngine`. Expiry is drained on every tick.
5. ✅ **Delivery flow** — `DeliveryService` reacts to `ENQUEUED` SSE frames, picks the nearest farm/factory whose `recipeId` matches, and queues a `DeliveryAnimation`. The map interpolates a `pi-truck` marker linearly between source and restaurant (speed scales with the game-clock multiplier). On arrival the frontend POSTs to `/fulfill`; the backend credits the wallet (full payout on time, half late) and bumps reputation.
6. ✅ **Frontend** — `RestaurantPanelDrawerComponent` with `<p-progressbar>` patience bars wired in `MapComponent`; restaurant marker click opens the drawer. `RestaurantSpawnerService` auto-spawns `GAME_CONSTANTS.spawn.restaurantsPerWorld` (default 6) restaurants on residential/commercial zone centroids once both placement zones and templates have loaded. Residential zone in `OsmService.classify`, `geojson.model.ts`, `placement-validator`, `OverpassClient`, plus a legend swatch + i18n key. Density cap exempts cross-kind buildings. The shared `RecipeTileComponent` continues to drive the sidebar and every drawer.
7. ✅ **i18n + docs** — `drawer.restaurant.*`, `osm.zone.residential`, `sidebar.legendResidential`, `map.tooltip.delivery`, `map.tooltip.restaurant` mirrored across `en.json` and `zh.json`. `docs/RESTAURANTS.md` documents the `RestaurantTemplate` JSON schema, the built-in catalogue, the modding rules, and the lifecycle.
8. ✅ **Tests** — `RestaurantTest` (reputation clamp + invariants), `OrderTest` (constructor invariants + `remainingMinutes`), `GameStatePlacementTest` (density cap + restaurant tier-gate exemption), `GameStateOrdersTest` (FULFILLED / LATE / EXPIRED end-to-end). All green via `gradle test`.

### Is it fun? (Week 6 playtest)

A fresh game now feels like *a game* rather than a sandbox: the city lights up with a half-dozen
restaurants the moment a feed loads, ENQUEUED-driven delivery trucks crawl visibly across the
map at 1×, and the patience progress bars in the restaurant drawer give immediate emotional
stakes to whether the player has *actually* set up enough farms and factories along the way.

What works:

- **The end-to-end loop closes.** Place a farm whose `recipeId` matches a restaurant's house
  dish, click "New test order" in the drawer, and a `pi-truck` marker walks from farm to
  restaurant in real time, fulfilling the order and crediting the wallet. The reputation bar
  ticks up. That's the loop the README has been promising for six weeks.
- **Auto-spawn removes the dead start.** The previous build dropped the player onto an empty
  map and asked them to invent something to do. Six restaurants demanding `garlic_rice`
  removes that paralysis instantly.
- **The clock-driven animation feels right.** At 1× a delivery feels like a brisk delivery
  van; at 16× the same delivery becomes a quick pulse, which is exactly the texture
  Factorio-likes lean on for "watch your factory work" satisfaction.

What doesn't yet:

- Only one house dish per restaurant template; orders are all manually triggered. There's no
  procedural demand pressure yet, so a player who never clicks "New test order" never feels
  the game.
- Deliveries cut straight across the map. The README has been promising GTFS-trip-shape
  routing since Phase 4 — Phase 7 needs to actually do it.
- No spoilage, no upkeep drain, no second-restaurant dynamic. The economy is a one-way
  ratchet upward today.
- Restaurants never close. Reputation falls but never crosses the close threshold because
  the game doesn't yet auto-enqueue orders fast enough to expire them.

Phase 7 priorities, in order:

1. Procedural order generation tied to the game clock so the patience system actually bites.
2. Ingredient spoilage (`shelfLifeMinutes` on the ingredient JSON, decrement in transit).
3. GTFS-trip-shape routing for delivery animations — pick a real trip, walk its polyline.
4. Daily upkeep deduction so idle infrastructure costs the player something.
5. Restaurant-close threshold (`RESTAURANT_CLOSE_REPUTATION_THRESHOLD` is already a constant —
   nothing reads it yet) so the loss state has teeth.


## Design question: is manual route-assignment fun?
The user asked, in Week 15: *"In the beginning I mentioned having to manually
assign a farm/factory's transport routes they should use, and then later on each
farm/factory can have upgrades to allow auto-routing. I'm not sure if I should
continue with this or not, will it be fun?"*
**Short answer: yes, but only after the GTFS multi-leg planner from Week 16 is
in place — and only as a *progression unlock*, not as the default.**
The reason it would have been busywork in Weeks 1–14 is that every shipment
succeeded on its own: a robot drove straight from any farm to any factory at
10 km/h, regardless of the city's actual transit network, so picking "Route 5
vs. Route 12" meant nothing mechanically. The Week-15 5 km robot leg cap finally
gives transit a teeth-bearing reason to exist: a Sai Kung farm cannot ship to a
Sheung Wan restaurant without a bus, full stop. Once the Week-16 planner lands,
the player sees a cargo physically *wait* at a boarding stop until a bus actually
shows up, ride that bus's polyline, and dismount — which is exactly the texture
*Transport Fever 2* and *Mini Metro* lean on for engagement.
The design that's most likely to be fun, in dependency order:
1. **Default: planner picks the route automatically.** A new factory gets a
   working chain immediately; the player doesn't have to babysit until they
   choose to. This is the Factorio "the belt just works" baseline.
2. **Per-building "manual route override" panel** in the existing factory /
   farm drawer. Toggleable. When enabled, the player picks a preferred GTFS
   route from a dropdown of routes whose stops are within the catchment
   radius; the planner respects the choice and falls back to auto only when
   that route isn't viable for a specific shipment (e.g. mid-day service gap).
3. **"Logistics Coordinator" upgrade** ( cost, milestone-gated to e.g.
   "10 successful multi-leg deliveries") that gives the building access to
   a *priority list* of routes rather than a single override — and a small
   throughput bonus on top so the upgrade has a measurable benefit even when
   the player picks the same routes the auto-planner would have. Mirrors the
   refrigeration upgrade's "you don't *need* it but it lets you push harder"
   shape.
4. **Anti-pattern to avoid:** mandatory manual route assignment with no
   default. That's *Transport Fever* in its most punishing mode; players who
   are here for the dim-sum supply chain will quit before they get to the
   recipe complexity that's the actual hook. The README has always called the
   game "Factorio with a real city" — Factorio's belts default to working.
   **Recommendation:** ship Week 16's planner with auto-routing as the default,
   add the manual-override panel in Week 17, and keep the Logistics Coordinator
   upgrade pencilled in for a later progression-balancing pass. That sequencing
   keeps every shipped feature *immediately fun* without putting the burden of
   fun on a player learning the GTFS schedule on top of every other system.
