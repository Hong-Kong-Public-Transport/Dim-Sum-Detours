# Base Game Ingredients

The current ship is a Cantonese / dim-sum tree: three raw proteins / staples
(`pork`, `shrimp`, `flour`) feed three steamed dishes (`cha_siu_bao`,
`siu_mai`, `har_gow`), with three condiments (`soy_sauce`, `chili_oil`,
`white_pepper`) plus `salt` and a side `rice → cooked_rice` chain reserved
for a future congee tier.

Each ingredient is defined as a JSON file under
`backend/src/main/resources/content/ingredients/<id>.json`. Mods can override any of
them by placing a same-id file under `data/mods/<mod-name>/ingredients/`.

## Schema

```jsonc
{
	"id": "pork",                      // lower_snake_case, globally unique
	"displayName": {                   // BCP-47 locale tag → translation
		"en": "Pork",                    // mandatory; loaders reject content missing it
		"zh": "豬肉"                     // optional translations
	},
	"category": "meat",                // reference to a category id (lower_snake_case)
	"shelfLifeMinutes": 360,           // -1 means non-perishable
	"refrigeratable": true,            // refrigerated factories pause the spoilage timer
	"baseValue": 18,                   // reference market value
	"tags": ["protein", "perishable", "farmable"]   // free-form, lower_snake_case
}
```

## Ingredient catalogue

| ID             | en                  | zh   | Category   | Shelf life (min) | Refrigeratable | Base value |
|----------------|---------------------|------|------------|-----------------:|:--------------:|-----------:|
| `pork`         | Pork                | 豬肉  | meat       |              360 |       ✓        |         18 |
| `shrimp`       | Shrimp              | 蝦   | seafood    |              240 |       ✓        |         22 |
| `flour`        | Wheat Flour         | 麵粉  | grain      |               -1 |                |          6 |
| `rice`         | Rice                | 米   | grain      |               -1 |                |          5 |
| `salt`         | Salt                | 鹽   | spice      |               -1 |                |          3 |
| `soy_sauce`    | Soy Sauce           | 醬油  | condiment  |               -1 |                |         12 |
| `chili_oil`    | Chili Oil           | 辣油  | condiment  |               -1 |                |         18 |
| `white_pepper` | Ground White Pepper | 白胡椒 | spice      |               -1 |                |         15 |
| `cooked_rice`  | Cooked Rice         | 白飯  | processed  |              720 |       ✓        |         12 |
| `cha_siu_bao`  | Cha Siu Bao         | 叉燒包 | processed  |              360 |       ✓        |         60 |
| `siu_mai`      | Siu Mai             | 燒賣  | processed  |              240 |       ✓        |         65 |
| `har_gow`      | Har Gow             | 蝦餃  | processed  |              240 |       ✓        |         70 |

## Categories (also JSON-defined)

Located under `backend/src/main/resources/content/categories/`. IDs:
`vegetable`, `fruit`, `grain`, `meat`, `poultry`, `seafood`, `dairy`, `spice`,
`condiment`, `processed`, `other`.

## Tag conventions

Tags are descriptive metadata used by the simulation and UI for filtering, pricing
modifiers, and unlock conditions. All tags are lower_snake_case.

Common tags so far: `protein`, `perishable`, `non_perishable`, `farmable`,
`staple`, `seasoning`, `dish`, `dim_sum`, `steamed`, `intermediate`.

