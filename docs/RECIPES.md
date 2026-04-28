# Base Game Recipes

Recipes are JSON-defined under `backend/src/main/resources/content/recipes/<id>.json`,
mod-overridable from `data/mods/<mod-name>/recipes/`.

## Schema

```jsonc
{
	"id": "garlic_salt",
	"displayName": {
		"en": "Garlic Salt",
		"zh": "蒜鹽"
	},
	"inputs": [
		{ "ingredientId": "garlic_powder", "quantity": 1 },
		{ "ingredientId": "salt",          "quantity": 2 }
	],
	"operations": ["mix"],              // ordered list of operation ids (lower_snake_case)
	"outputs": [
		{ "ingredientId": "garlic_salt", "quantity": 3 }
	],
	"minimumFactoryTier": 1,            // 1 = T1 (3 op-slots)
	"operationDurationMinutes": 15,     // total game-minutes per execution
	"tags": ["seasoning"]
}
```

The content loader **rejects** a recipe when:

- the `en` displayName is missing
- any `ingredientId` does not resolve in the ingredient registry
- any operation id does not resolve in the operation registry
- `inputs` or `outputs` is empty

Failures log a warning and the recipe is skipped — the game still starts.

## Phase 1 recipe catalogue

| ID                  | en                 | Inputs                          | Operations | Output               | Min. tier | Duration |
|---------------------|--------------------|---------------------------------|------------|----------------------|:---------:|---------:|
| `dehydrated_garlic` | Dehydrated Garlic  | 2× garlic                       | dehydrate  | 1× dehydrated_garlic |     1     |   60 min |
| `garlic_powder`     | Garlic Powder      | 1× dehydrated_garlic            | powderize  | 1× garlic_powder     |     1     |   20 min |
| `garlic_salt`       | Garlic Salt        | 1× garlic_powder + 2× salt      | mix        | 3× garlic_salt       |     1     |   15 min |
| `cooked_rice`       | Cooked Rice        | 1× rice                         | steam      | 1× cooked_rice       |     1     |   30 min |
| `garlic_rice`       | Garlic Rice (dish) | 1× cooked_rice + 1× garlic_salt | mix        | 1× garlic_rice       |     1     |   10 min |

## Operations (also JSON-defined)

Located under `backend/src/main/resources/content/operations/`. IDs:
`chop`, `cook`, `steam`, `boil`, `grill`, `dehydrate`, `powderize`, `mix`, `filter`.

A factory's tier determines how many operations it can host on its op-graph:

| Tier | Op slots |
|:----:|---------:|
|  T1  |        3 |
|  T2  |        6 |
|  T3  |       12 |
|  T4  |       24 |

## Chained recipes form a tree

```
garlic ──► dehydrated_garlic ──► garlic_powder ──┐
                                                 ├──► garlic_salt ──┐
salt ────────────────────────────────────────────┘                  │
                                                                    ├──► garlic_rice (dish)
rice ──► cooked_rice ───────────────────────────────────────────────┘
```

The cooking-tree progression unlocks descendant recipes once you have produced an ancestor
recipe N times (configured in a future `unlocks/` content folder).
