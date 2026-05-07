# Base Game Recipes

Recipes are JSON-defined under `backend/src/main/resources/content/recipes/<id>.json`,
mod-overridable from `data/mods/<mod-name>/recipes/`.

## Schema

```jsonc
{
	"id": "cha_siu_bao",
	"displayName": {
		"en": "Steam Cha Siu Bao",
		"zh": "蒸叉燒包"
	},
	"inputs": [
		{ "ingredientId": "pork",      "quantity": 1 },
		{ "ingredientId": "flour",     "quantity": 1 },
		{ "ingredientId": "soy_sauce", "quantity": 1 }
	],
	"operations": ["mix", "steam"],     // ordered list of operation ids (lower_snake_case)
	"outputs": [
		{ "ingredientId": "cha_siu_bao", "quantity": 1 }
	],
	"minimumFactoryTier": 1,            // 1 = T1 (3 op-slots)
	"operationDurationMinutes": 25,     // total game-minutes per execution
	"tags": ["dish", "dim_sum"]
}
```

The content loader **rejects** a recipe when:

- the `en` displayName is missing
- any `ingredientId` does not resolve in the ingredient registry
- any operation id does not resolve in the operation registry
- `outputs` is empty (a recipe must produce something)
- `operations` is empty (a recipe must run at least one operation)

`inputs` **may be empty** — that's how farm/harvest recipes work: they take nothing from the
world and produce a raw ingredient (e.g. `raise_pork`, `mill_flour`, `harvest_salt`). Such
recipes should also carry the `"farm"` tag so the placement UI offers them under "Place
farm" rather than "Place factory".

Failures log a warning and the recipe is skipped — the game still starts.

## Recipe catalogue

### Farm / harvest recipes (no inputs, tagged `farm`)

| ID                    | en                 | Operation | Output         | Min. tier | Duration |
|-----------------------|--------------------|-----------|----------------|:---------:|---------:|
| `raise_pork`          | Raise Pork         | grow      | 1× pork        |     1     |   75 min |
| `catch_shrimp`        | Catch Shrimp       | harvest   | 1× shrimp      |     1     |   60 min |
| `mill_flour`          | Mill Flour         | grow      | 2× flour       |     1     |   50 min |
| `grow_rice`           | Grow Rice          | grow      | 1× rice        |     1     |   90 min |
| `harvest_salt`        | Harvest Salt       | harvest   | 1× salt        |     1     |   45 min |
| `harvest_soy_sauce`   | Harvest Soy Sauce  | grow      | 2× soy_sauce   |     1     |   45 min |
| `press_chili_oil`     | Press Chili Oil    | grow      | 2× chili_oil   |     1     |   50 min |
| `grind_white_pepper`  | Grind White Pepper | grow      | 2× white_pepper|     1     |   40 min |

### Factory recipes

| ID            | en                  | Inputs                                                    | Operations         | Output             | Min. tier | Duration |
|---------------|---------------------|-----------------------------------------------------------|--------------------|--------------------|:---------:|---------:|
| `cooked_rice` | Cooked Rice         | 1× rice                                                   | steam              | 1× cooked_rice     |     1     |   45 min |
| `cha_siu_bao` | Steam Cha Siu Bao   | 1× pork  + 1× flour + 1× soy_sauce                        | mix, steam         | 1× cha_siu_bao     |     1     |   25 min |
| `siu_mai`     | Steam Siu Mai       | 1× pork  + 1× flour + 1× chili_oil                        | mix, steam         | 1× siu_mai         |     1     |   28 min |
| `har_gow`     | Steam Har Gow       | 1× shrimp + 1× flour + 1× white_pepper                    | mix, steam         | 1× har_gow         |     1     |   30 min |

## Operations (also JSON-defined)

Located under `backend/src/main/resources/content/operations/`. IDs:
`grow`, `harvest`, `chop`, `cook`, `steam`, `boil`, `grill`, `mix`, `filter`.

### Reordering on placed factories (Phase 5)

Once a factory is placed, the player can **reorder** its operation chain — the multiset of
operation ids stays equal to the recipe's, but the execution order can be tweaked from
the factory side panel (click any factory marker on the map). Backed by
`PUT /api/game/buildings/{id}/operations`. Adding or removing operations from a placed
factory is not supported in Phase 5; that's the upgrade-tier story for Phase 7+.

A factory's tier determines how many operations it can host on its op-graph:

| Tier | Op slots |
|:----:|---------:|
|  T1  |        3 |
|  T2  |        6 |
|  T3  |       12 |
|  T4  |       24 |

## Chained recipes form a tree

```
pork ─────┐
          ├──► cha_siu_bao  (mix + steam)
flour ────┤
          ├──► siu_mai      (mix + steam)
shrimp ───┤
          └──► har_gow      (mix + steam)
soy_sauce ──► cha_siu_bao
chili_oil ──► siu_mai
white_pepper ► har_gow

rice ──► cooked_rice    (orphan side-tier; future congee dish)
salt                    (orphan; reserved for upcoming dough recipe)
```

The cooking-tree progression unlocks descendant recipes once you have produced an ancestor
recipe N times (configured in a future `unlocks/` content folder).

