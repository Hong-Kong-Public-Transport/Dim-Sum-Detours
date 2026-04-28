# Base Game Ingredients

The Phase 1 ship includes 8 ingredients that demonstrate the full
**garlic + salt → garlic salt → garlic rice** supply chain.

Each ingredient is defined as a JSON file under
`backend/src/main/resources/content/ingredients/<id>.json`. Mods can override any of
them by placing a same-id file under `data/mods/<mod-name>/ingredients/`.

## Schema

```jsonc
{
	"id": "garlic",                    // lower_snake_case, globally unique
	"displayName": {                   // BCP-47 locale tag → translation
		"en": "Garlic",                  // mandatory; loaders reject content missing it
		"zh": "蒜頭"                     // optional translations
	},
	"category": "vegetable",           // reference to a category id (lower_snake_case)
	"shelfLifeMinutes": 14400,         // -1 means non-perishable
	"refrigeratable": true,            // refrigerated factories pause the spoilage timer
	"baseValue": 8,                    // reference market value
	"tags": ["aromatic", "perishable", "farmable"]   // free-form, lower_snake_case
}
```

## Phase 1 ingredient catalogue

| ID                  | en                | zh   | Category  | Shelf life (min) | Refrigeratable | Base value |
|---------------------|-------------------|------|-----------|-----------------:|:--------------:|-----------:|
| `garlic`            | Garlic            | 蒜頭   | vegetable |           14 400 |       ✓        |          8 |
| `salt`              | Salt              | 鹽    | spice     |               -1 |                |          2 |
| `rice`              | Rice              | 米    | grain     |               -1 |                |          5 |
| `dehydrated_garlic` | Dehydrated Garlic | 脫水蒜頭 | processed |           43 200 |                |         12 |
| `garlic_powder`     | Garlic Powder     | 蒜粉   | spice     |           43 200 |                |         18 |
| `garlic_salt`       | Garlic Salt       | 蒜鹽   | spice     |           43 200 |                |         15 |
| `cooked_rice`       | Cooked Rice       | 白飯   | processed |              720 |       ✓        |          9 |
| `garlic_rice`       | Garlic Rice       | 蒜香飯  | processed |              480 |       ✓        |         28 |

## Categories (also JSON-defined)

Located under `backend/src/main/resources/content/categories/`. IDs:
`vegetable`, `fruit`, `grain`, `meat`, `poultry`, `seafood`, `dairy`, `spice`,
`condiment`, `processed`, `other`.

## Tag conventions

Tags are descriptive metadata used by the simulation and UI for filtering, pricing
modifiers, and unlock conditions. All tags are lower_snake_case.

Common tags so far: `aromatic`, `perishable`, `non_perishable`, `farmable`,
`staple`, `seasoning`, `dish`, `intermediate`.
