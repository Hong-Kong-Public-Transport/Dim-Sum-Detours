# Restaurants

Restaurants are the demand side of the supply chain. They auto-spawn on residential and
commercial OSM zones and accept deliveries of a "house dish" recipe — fulfilling an order
before its patience timer runs out pays out money and bumps reputation; missing it docks
reputation. The roster of available restaurants ships as JSON content under
`backend/src/main/resources/content/restaurants/` and is fully moddable.

## Lifecycle

1. **Auto-spawn.** When the frontend finishes loading the OSM placement zones for a feed,
   `RestaurantSpawnerService` picks `GAME_CONSTANTS.spawn.restaurantsPerWorld` (default **6**)
   centroids from the eligible zones (`residential`, `commercial`) and posts each as a
   `RESTAURANT` building. Templates are assigned round-robin from the loaded catalogue.
2. **Order enqueue.** A pending `Order` is created with `createdAtGameMinutes`,
   `deadlineGameMinutes` (driven by the template's `basePatienceMinutes`), and a target
   recipe id. Phase 6 ships a "New test order" button on the restaurant drawer that issues
   one manually; later phases spawn orders procedurally.
3. **Delivery animation.** `DeliveryService` reacts to the SSE `ENQUEUED` event, picks the
   nearest farm/factory whose `recipeId` matches the order, and queues a
   `DeliveryAnimation`. The map component interpolates a delivery marker linearly between
   source and restaurant; speed scales automatically with the game-clock multiplier.
4. **Fulfillment.** When the marker reaches the restaurant, the frontend POSTs to
   `/api/game/restaurants/{r}/orders/{o}/fulfill`. The backend credits the wallet
   (full payout if before the deadline, half payout if late), bumps or docks reputation,
   and broadcasts a `FULFILLED` event onto the SSE stream.
5. **Expiry.** Orders not fulfilled by their deadline are drained on the next simulation
   tick; the restaurant takes a `REPUTATION_LOSS_MISSED` hit and an `EXPIRED` event is
   broadcast.

## JSON schema

```json
{
	"id": "dim_sum_house",
	"displayName": {
		"en": "Dim Sum House",
		"zh": "點心居"
	},
	"acceptedRecipeIds": ["cha_siu_bao", "har_gow", "siu_mai"],
	"basePatienceMinutes": 240,
	"basePayout": 900,
	"tags": ["cantonese", "tier_1", "dim_sum"]
}
```

| Field                 | Type                                | Required | Notes                                                                                                                       |
|-----------------------|-------------------------------------|:--------:|-----------------------------------------------------------------------------------------------------------------------------|
| `id`                  | `string` (lower\_snake\_case)       |    ✓     | Unique identifier. Mod content overrides built-in entries with the same id.                                                 |
| `displayName`         | `{ [locale: string]: string }`      |    ✓     | Inline localisation; `en` is mandatory, others optional. Resolution falls back through region subtags down to `en`.         |
| `acceptedRecipeIds`   | `string[]`                          |    ✓     | Recipes this restaurant will order. **First entry is the house dish** and the only one used at spawn time today.            |
| `basePatienceMinutes` | `integer`                           |    ✓     | Default order patience window in game minutes (drives `Order.deadlineGameMinutes - createdAtGameMinutes`).                  |
| `basePayout`          | `integer` (game currency)           |    ✓     | Cash credited on a fulfilled-on-time delivery. A late delivery receives `basePayout * LATE_DELIVERY_PAYOUT_MULTIPLIER`.     |
| `tags`                | `string[]`                          |    ✓     | Free-form labels, e.g. `cantonese`, `dim_sum`, `tier_1`. Reserved for the cuisine progression tree (Phase 7+).              |

`ContentLoader` validates that every `acceptedRecipeIds` entry exists in the recipe registry
and rejects the file outright if it doesn't — same rule as ingredient → recipe references.

## Built-in catalogue

| Id              | Display name (en) | House dish     | Other accepted recipes                              | Patience (game min) | Base payout |
|-----------------|-------------------|----------------|-----------------------------------------------------|--------------------:|------------:|
| `dim_sum_house` | Dim Sum House     | `cha_siu_bao`  | `har_gow`, `siu_mai`                                |                 240 |         900 |
| `tea_house`     | Corner Tea House  | `harvest_soy_sauce` | `press_chili_oil`, `grind_white_pepper`, `cha_siu_bao` |             300 |         350 |

> Phase 20 dropped the placeholder `garlic_noodle_bar` template. The two remaining
> templates differ in payout / patience / menu so the auto-spawner's round-robin
> assignment produces a visibly varied world.

## Modding

Drop a folder at `data/mods/<mod-name>/restaurants/` containing one JSON file per
restaurant. Files there override built-in entries that share an `id`, exactly like
ingredients and recipes. Locales beyond `en` are optional; the loader falls back through
the region-subtag resolution chain.

## Related code

- `backend/src/main/java/com/dimsumdetours/sim/model/RestaurantTemplate.java` — record + invariants
- `backend/src/main/java/com/dimsumdetours/sim/model/Restaurant.java` — placed-restaurant record (reputation 0–1, clamped on `withReputation`)
- `backend/src/main/java/com/dimsumdetours/sim/state/GameState.java` — `enqueueOrder` / `fulfillOrder` / `expirePendingOrders`
- `backend/src/main/java/com/dimsumdetours/sim/state/RestaurantOrderQueue.java` — fastutil-backed per-restaurant order queue
- `backend/src/main/java/com/dimsumdetours/api/GameController.java` — REST + SSE endpoints
- `frontend/src/app/core/service/restaurant.service.ts` — order signal + SSE subscription
- `frontend/src/app/core/service/restaurant-spawner.service.ts` — auto-spawn pass over OSM zones
- `frontend/src/app/core/service/delivery.service.ts` — order → animation → fulfilment glue
- `frontend/src/app/component/restaurant-panel-drawer/` — right-edge drawer with patience progress bars

