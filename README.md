# Dim Sum Detours

> A real-world cooking-supply-chain simulation game built on OpenStreetMap and static GTFS transit data.

Dim Sum Detours is a top-down 2D simulation game inspired by **Factorio**, **Transport Fever 2**,
**Cities: Skylines**, and **Widget Inc.**. You build farms, design factory recipes, and feed a
real-world city by shipping ingredients along its actual public transit network — using the
real GTFS schedule of any city you provide.

## Table of Contents

1. [Game Pitch](#game-pitch)
2. [The Core Loop](#the-core-loop)
3. [Game Time Model](#game-time-model)
4. [The Three Pressure Systems](#the-three-pressure-systems)
5. [Three Progression Trees](#three-progression-trees)
6. [A Typical 30-Minute Session](#a-typical-30-minute-session)
7. [Customizing the Map (GTFS + OSM)](#customizing-the-map-gtfs--osm)
8. [Where to put GTFS zip files](#where-to-put-gtfs-zip-files)
9. [Modding via JSON](#modding-via-json)
10. [Tech Stack](#tech-stack)
11. [Project Structure](#project-structure)
12. [Running the Project](#running-the-project)
13. [Phase 1 Roadmap](#phase-1-roadmap)

## Game Pitch

> **Place a farm → route its output via real transit to a factory → process it into a dish →
> deliver it to a restaurant before its patience runs out → earn money, grow the city, unlock
> the next tier.**

You start with a small balance, an empty map (just nature, parks, water, and streets pulled from
OpenStreetMap), and a handful of base recipes. Restaurants begin to spawn around the city as you
feed it. Each restaurant only accepts specific dishes. Public transit is your logistics network —
buses, trams, and trains run on the **real GTFS schedule** of the city you loaded, and you assign
each shipment to a specific route. The main puzzle is **spatial**: where do you place farms and
factories so the existing transit network actually serves them well?

## The Core Loop

Five tiers of activity, nested by time scale. A healthy session touches all of them.

| Scale      | Duration | What the player does                                                         |
|------------|----------|------------------------------------------------------------------------------|
| **Micro**  | seconds  | Watch a shipment travel; react to a spoilage warning; reroute a bus.         |
| **Short**  | minutes  | Build a farm/factory; configure a factory's operation graph; assign a route. |
| **Medium** | hours    | Satisfy a restaurant's recipe demand for N days; unlock a new vehicle tier.  |
| **Long**   | sessions | Unlock a district; complete a cuisine tree; grow city population.            |

```
                ┌─────────────────────────────────────┐
                │        PLAYER HAS MONEY             │
                └──────────────┬──────────────────────┘
                               │ buys
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
          [FARM]          [FACTORY]         [routes]
        produces raw    op-graph chains    GTFS-timed
              │                ▲                │
              │ ships via      │ ships via      │
              └────► [GTFS] ───┴────► [GTFS] ───┘
                               │
                               ▼
                          [RESTAURANT]
                       has patience timer
                               │
                          fulfilled?
                          ┌────┴────┐
                       yes│         │no
                          ▼         ▼
                     +money     -reputation
                     +growth    restaurant may close
                          │         │
                          └────┬────┘
                               ▼
                       [CITY GROWS]
                  unlock districts, routes,
                       recipes, tiers
                               │
                               └──── (loop) ────► more money, harder demand
```

## Game Time Model

- Game time is **its own clock**, decoupled from real time.
- It maps to a GTFS service week (Mon–Sun) on a loop, compressed.
- **Default speed: 1× = 1 game-minute per real-second.** A 30-minute bus headway = 30 real seconds.
- **Speeds available:** Pause, 1×, 4×, 16×, 64×, 256×.
- The clock **auto-drops to 1×** on critical alerts (a restaurant about to close, a shipment about to spoil).
- **GTFS service exceptions** (`calendar_dates.txt`) become in-game events — holidays, special schedules, tourist surges — without needing real-time data.

## The Three Pressure Systems

These are the forces that prevent the game from becoming a money printer. **All three operate simultaneously.**

### 1. Spoilage (per-ingredient freshness)

- Every ingredient has a `shelfLifeMinutes` defined in JSON.
- The timer pauses inside refrigerated factories (an upgrade) and resumes in transit.
- A spoiled ingredient is wasted money — and you still paid the transit fare.
- **Why it matters:** this ties directly to GTFS schedule quality. A 60-minute headway is fine for flour, fatal for fresh fish.

### 2. Restaurant Patience (demand timer)

- Each restaurant has an active order queue (e.g. *"3× garlic ramen needed in next 4 game-hours"*).
- Fulfill on time → full price + reputation up.
- Fulfill late → discounted price + reputation down.
- Miss entirely → reputation hit. Below threshold, the restaurant **closes**, and its surrounding residential block stops growing.

### 3. Operating Costs (the slow drain)

- Farms, factories, and restaurants all pay **upkeep per game-day** regardless of output.
- Transit fares are deducted per shipment.
- Idle infrastructure bleeds money. Forces *use it or demolish it*.
- **Why it matters:** prevents the "build everywhere" exploit and creates the core spatial puzzle.

### 4. Optional (later): Route Congestion

- Each GTFS trip has a capacity (a horse cart starts at 5 units; a modern bus carries far more).
- NPC city demand also consumes transit capacity (simulated abstractly).
- Pay a priority surcharge or unlock dedicated cargo runs.

## Three Progression Trees

Run **in parallel** so the player always has multiple goals.

### A. Cuisine / Recipe Tree (JSON-defined, moddable)

- Start with 5 base recipes (bread, soup, salad, rice bowl, grilled fish).
- Unlock descendants by *cooking* — produce a recipe N times to unlock its children.
- Branches by cuisine (Japanese, Italian, Mexican, …). Modders extend this.
- Endgame: prestige dishes requiring 8+ ingredients across 3+ factory chains.

### B. Infrastructure Tree (vehicles + buildings)

- **Vehicle tiers** gate by in-game year + money: horse cart → early bus → modern bus → articulated → rail freight → cargo tram → drone.
- Each tier increases capacity, speed, and refrigeration availability.
- **Factory tiers:** T1 (3 op slots) → T2 (6) → T3 (12) → T4 (24, parallel lanes).
- **Restaurant tiers:** street stall → diner → restaurant → chain.

### C. City Growth (the win-condition surrogate)

- Each fulfilled order generates **growth points** in its neighborhood.
- Growth unlocks: more restaurants spawn → new districts open → new GTFS routes go online.
- The player literally watches the city light up.

### Concrete Milestones

1. **First Delivery** — deliver any dish to any restaurant. Tutorial complete.
2. **Cold Chain** — deliver a perishable across 2+ transfers without spoilage. Unlocks refrigerated factories.
3. **Neighborhood Hero** — every restaurant in one neighborhood at 80%+ reputation for 7 game-days. Unlocks a new district.
4. **Vertical Integration** — own the full chain (farm → factory → restaurant) for one recipe. Unlocks bulk discounts.
5. **Cuisine Master** — unlock all base recipes in one cuisine tree. Unlocks prestige dishes.
6. **Transit Tycoon** — use 10 distinct GTFS routes simultaneously. Unlocks priority cargo.
7. **City Builder** — grow population to a target threshold. Soft win condition.

## A Typical 30-Minute Session

1. **Open** — check overnight earnings and 1–2 alerts.
2. **React** (~5 min) — reroute, rebuild, or accept a loss.
3. **Plan** (~10 min) — a new restaurant spawned in Ballard; design a supply chain.
4. **Build** (~10 min) — place farm, configure factory operation graph, assign GTFS routes.
5. **Watch** (~5 min, high speed) — see it run, tweak, hit a milestone.
6. **Close** — a partial milestone or a teased district pulls you back next time.

## Customizing the Map (GTFS + OSM)

The game is **fully customizable per city**. On startup, the player picks a GTFS feed; the game does the rest.

From the GTFS feed, the game extracts:

- **Bounding box** from `stops.txt` lat/lon → defines map area.
- **Service calendar** from `calendar.txt` / `calendar_dates.txt` → defines in-game week + events.
- **Routes & trips** → the unlockable transit network.
- **Agency** → flavor text and branding.

Then it pulls **OSM data** (Overpass API) for that bounding box:

- Parks (`leisure=park`) — placement zones for **community gardens** (small farms).
- Farmland (`landuse=farmland`) — placement zones for **real farms**.
- Coastline / water (`natural=water`, `natural=coastline`) — fishing ports, fish farms, salt collectors.
- Commercial (`landuse=commercial`) — factories.
- Residential / commercial mix — restaurants spawn here automatically.
- Streets — visual base layer.

OSM and GTFS responses are **cached locally** so you're not hammering APIs every launch.

### Placement rules

Buildings can only be placed on zones that fit their kind:

| Building    | Allowed OSM zones                  |
|-------------|------------------------------------|
| **Farm**    | `leisure=park`, `landuse=farmland` |
| **Factory** | `landuse=commercial`               |
| Restaurant  | spawned automatically (Phase 6+)   |

The frontend previews validity live: the cursor turns into a "no" symbol when hovering an
invalid zone, and the **Confirm** button is disabled. The same rule will be enforced
server-side once the OSM zone cache is moved to the backend (`INVALID_PLACEMENT_LOCATION`
error code is already wired through the API).

### GTFS `shapes.txt` is optional

The GTFS spec marks `shapes.txt` as conditionally required, and many real feeds omit it.
When a trip has no shape, we fall back to **straight-line geometry between consecutive
`stop_times`** (in `stop_sequence` order). This is correct enough for cargo-shipment
animation purposes — the visual artefact is the line cutting across blocks rather than
following streets, but timing is still driven by the actual GTFS schedule.

## Where to put GTFS zip files

Place your static GTFS `.zip` files in:

```
data/gtfs/
```

Each zip is loaded by name. To start a new game with a city, upload its zip via the in-game UI
or drop it into this folder before launch — the backend will discover it on startup.

You can find feeds at:

- [Mobility Database](https://mobilitydatabase.org/)
- [transit.land](https://www.transit.land/)
- [OpenMobilityData](https://transitfeeds.com/)

Recommended starter feeds:

- **King County Metro (Seattle)** — small, mixed urban/water/parks, great for prototyping.
- **TfL (London)**, **MTA (NYC)**, **Toei (Tokyo)** for variety later.

> **Note:** the `data/gtfs/` folder is git-ignored. Don't commit transit feeds.

## Modding via JSON

All game content lives in JSON and can be added/overridden by players. Phase 1 ships
with example **categories**, **operations**, **ingredients**, and **recipes** that
demonstrate a complete supply chain: `garlic + salt → garlic salt → garlic rice`.

```
backend/src/main/resources/content/
├── categories/    (vegetable, grain, spice, …)
├── operations/    (chop, cook, steam, dehydrate, powderize, mix, …)
├── ingredients/   (garlic, salt, rice + processed/dish examples)
└── recipes/       (dehydrated_garlic, garlic_powder, garlic_salt, cooked_rice, garlic_rice)
```

> 📚 **Full catalogue + JSON schema**:
> see [`docs/INGREDIENTS.md`](docs/INGREDIENTS.md) and [`docs/RECIPES.md`](docs/RECIPES.md).

### Naming conventions

- All `id` fields, category references, operation references, and tags are **lower_snake_case**
  (e.g. `garlic_powder`, `dehydrate`, `non_perishable`).
- Indentation in JSON is tabs, with a single trailing newline.
- Built-in content lives on the classpath; mod content lives at `data/mods/<mod-name>/<subfolder>/`
  and overrides any built-in entry sharing the same `id`.

### Localisation in content JSON

Translations live **inline in each content file** so mods are self-contained — drop a folder
in `data/mods/`, get translations for free, no separate i18n bundle required:

```json
{
	"id": "garlic",
	"displayName": {
		"en": "Garlic",
		"zh": "蒜頭"
	}
}
```

**Rules**:

- The `en` entry is **mandatory** — the loader rejects content missing it.
- Other locales are optional. Resolution order: exact tag → strip region subtags
  (e.g. `zh-Hant-HK` → `zh-Hant` → `zh`) → fall back to `en`.
- UI chrome (buttons, headings, error messages) lives in `frontend/src/assets/i18n/<lang>.json`,
  *not* in content JSON. That's app code, not data.

## Tech Stack

### Locked in

| Layer       | Choice                                                               | Why                                                              |
|-------------|----------------------------------------------------------------------|------------------------------------------------------------------|
| Backend     | **Spring Boot 3 (WebFlux + JPA)** on **JDK 21**                      | Reactive APIs, async repos via `boundedElastic`.                 |
| Build       | **Gradle (Groovy DSL)**                                              | Faster incremental builds; nicer config than XML.                |
| Boilerplate | **Lombok** (`@RequiredArgsConstructor`, `@Slf4j`, `@Getter/@Setter`) | Less ceremony in services and properties.                        |
| Nullness    | **JSpecify** (`@NullMarked` per package, `@Nullable` where needed)   | Static null analysis without Kotlin.                             |
| Persistence | **JPA / Hibernate** + H2 (dev), Postgres (prod)                      | Familiar; reactive wrappers over blocking JPA.                   |
| Collections | **fastutil** (`it.unimi.dsi:fastutil`)                               | Lower allocation overhead, primitive-keyed maps for hot paths.   |
| Utilities   | **Apache Commons Lang3** + **Commons IO**                            | StringUtils, FileUtils, FilenameUtils — fewer reinvented wheels. |
| GTFS        | **OneBusAway `onebusaway-gtfs`**                                     | Battle-tested GTFS parser.                                       |
| OSM         | **Overpass API** via WebClient                                       | Pull only what's in the bounding box.                            |
| Frontend    | **Angular 21 standalone components**                                 | Familiar; signals + `input()`/`output()`.                        |
| UI          | **PrimeNG 19** + **PrimeIcons**                                      | Rich component set without Material's look.                      |
| Map         | **Leaflet**                                                          | Best-in-class 2D map rendering for OSM.                          |
| Styling     | **SCSS** everywhere (incl. inline component styles)                  | Variables, nesting, theming.                                     |
| i18n        | **Transloco** — `en` (British) and `zh` (繁體中文 香港)                    | Per-language JSON, lazy-loadable.                                |
| Lint        | **Angular ESLint (flat config)**                                     | Standard.                                                        |

### Considered and rejected

- **three.js** — overkill for 2D top-down. Adds complexity without benefit. If you later need
  GPU-accelerated 2D for thousands of moving shipments, swap in **PixiJS** via
  `leaflet-pixi-overlay` instead.
- **R2DBC** — more idiomatic for WebFlux, but you wanted JPA. We use JPA wrapped in
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` — a well-known pattern.
- **Java backend + Unity frontend over HTTP** — latency-prohibitive for a tick-based sim.

## Project Structure

```
Dim Sum Detours/
├── README.md
├── .gitignore
├── data/
│   └── gtfs/                          ← drop GTFS .zip files here (git-ignored)
├── backend/
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/
│       ├── java/com/dimsumdetours/
│       │   ├── DimSumDetoursApplication.java
│       │   ├── config/
│       │   │   └── GameConstants.java          ← all tunables in ONE place
│       │   ├── sim/                            ← framework-agnostic simulation core
│       │   │   ├── model/                      (Ingredient, Farm, Factory, …)
│       │   │   ├── content/                    (ContentRegistry — loads JSON)
│       │   │   └── engine/                     (SimulationEngine — tick loop)
│       │   ├── gtfs/                           (GtfsLoader)
│       │   ├── osm/                            (OverpassClient)
│       │   ├── persistence/                    (JPA entities + repos)
│       │   └── api/                            (WebFlux controllers)
│       └── resources/
│           ├── application.yml
│           └── content/
│               └── ingredients/
│                   ├── garlic.json
│                   ├── salt.json
│                   └── rice.json
└── frontend/
    ├── package.json
    ├── angular.json
    ├── eslint.config.js
    ├── tsconfig.json
    └── src/
        ├── index.html
        ├── main.ts
        ├── styles.scss
        ├── app/
        │   ├── app.config.ts
        │   ├── app.routes.ts
        │   ├── app.component.ts
        │   ├── component/
        │   │   ├── clock-controls/         (toolbar clock + speed buttons)
        │   │   ├── map/                    (Leaflet map + sidebar)
        │   │   └── panel/                  (icon + title card — reused everywhere)
        │   ├── core/
        │   │   ├── constant/game.constants.ts ← all UI tunables in ONE place
        │   │   ├── i18n/                   (locale resolution helpers)
        │   │   ├── model/                  (Building, Recipe, GeoJSON, …)
        │   │   ├── service/                (GameService, ContentService, …)
        │   │   └── utility/                (formatMoney, placement-validator, …)
        │   └── transloco-loader.ts
        ├── assets/i18n/
        │   ├── en.json
        │   └── zh.json
        └── environments/
```

> The `sim/` package contains **zero Spring imports**. This is intentional — if we ever port to
> Unity (C#), it's a near-mechanical translation. Treat it as the most valuable code in the repo.

## Running the Project

### Prerequisites

- **JDK 21**
- **Node 20+** and **npm 10+**

### Backend

```pwsh
cd backend
# First time only — generate the Gradle wrapper using a system Gradle install:
gradle wrapper
# After that:
./gradlew bootRun
```

Backend listens on `http://localhost:8080`.

### Frontend

```pwsh
cd frontend
npm install
npm start
```

Frontend serves at `http://localhost:4200` and proxies `/api` to the backend.

## Phase 1 Roadmap

| Week | Goal                                                                                                                                                                                  |     Status     |
|------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:--------------:|
| 1    | Spring Boot project, GTFS upload + parse, persist to H2. Recipe + ingredient JSON content with referential validation. Categories, operations, ingredients, recipes all JSON-defined. |       ✅        |
| 2    | OSM Overpass client + endpoint. Angular + Leaflet map showing the GTFS bounding box, parks, water, commercial zones.                                                                  |       ✅        |
| 3    | Place a farm, place a factory, hardcoded recipe. Money counter.                                                                                                                       |       ✅        |
| 4    | Game clock + speed controls. Spawn a shipment that animates along a GTFS trip.                                                                                                        |       ✅        |
| 5    | Factory operation graph UI (drag/drop) using JSON-defined operations.                                                                                                                 |       ✅        |
| 6    | Restaurant + patience timer + first end-to-end delivery. **"Is it fun?" checkpoint.**                                                                                                 | 🚧 in progress |
| 7+   | Spoilage, reputation, second restaurant, milestones 1–3.                                                                                                                              |       ⬜        |

**Don't build** trees, ingredient walking, modding UI, or visual polish until Week 6 proves the loop works.

## Documentation

- [`docs/CODE_STYLES.md`](docs/CODE_STYLES.md) — naming, formatting, and architecture rules
- [`docs/INGREDIENTS.md`](docs/INGREDIENTS.md) — full ingredient catalogue + JSON schema
- [`docs/RECIPES.md`](docs/RECIPES.md) — full recipe catalogue + JSON schema
