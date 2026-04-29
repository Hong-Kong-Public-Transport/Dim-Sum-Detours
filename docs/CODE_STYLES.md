# Code Styles

> Conventions enforced across the Dim Sum Detours codebase. New rules land here as they're agreed.

These rules apply to **both** the Java backend and the TypeScript / SCSS / HTML frontend unless
otherwise noted. Anything not listed here defers to the project's linter / formatter
(Angular ESLint flat config; Java compiler warnings).

## 1. Naming

### 1.1 Package / folder names are singular

- ✅ `core/service/`, `core/model/`, `core/utility/`, `core/constant/`, `app/component/<name>/`
- ❌ `services/`, `models/`, `utilities/`, `features/`

Backend Java packages follow the same rule (`sim.model`, `sim.content`, `sim.state`,
`api`, `gtfs`, `osm`).

### 1.2 No abbreviations in identifiers

Package names, class / interface names, method names, variable names, parameter names, and
field names must spell out their meaning in full.

- ✅ `recipes.forEach((recipe) => { … })`
- ❌ `recipes.forEach((r) => { … })`
- ✅ `for (const ingredient of ingredients) { … }`
- ❌ `for (const ing of ingredients) { … }`

**Allowed exceptions:**

| Context           | Allowed                                 | Example                                       |
|-------------------|-----------------------------------------|-----------------------------------------------|
| Caught exceptions | `e`, `ex`                               | `} catch (Exception e) { … }`                 |
| Transloco let     | `t`                                     | `*transloco="let t"`                          |
| Loop indices      | `i`, `j`, `k`                           | `for (int i = 0; i < length; i++) { … }`      |
| Common idioms     | `id`, `lat`, `lon`, `min`, `max`, `dto` | These are de-facto whole words in our domain. |
| Library type echo | match the imported class                | `FileRef fileRef = new FileRef();`            |

The "library type echo" rule: when a local needs to hold a value of an imported type, prefer
the type's own name in lowerCamelCase as the variable name (`Path path`, `WebClient webClient`,
`GtfsReader gtfsReader`, `BoundingBox boundingBox`). It removes a class of bikeshedding and
makes refactor-rename across the file trivial.

### 1.3 Casing

- **TypeScript / Java classes / records**: `PascalCase`.
- **TypeScript / Java methods, fields, locals, parameters**: `camelCase`.
- **TypeScript constants exported from `*.constants.ts`**: `SCREAMING_SNAKE_CASE`.
- **JSON content `id` fields, category refs, operation refs, tag values**: `lower_snake_case`
  (e.g. `garlic_powder`, `non_perishable`).
- **CSS / SCSS class names**: `kebab-case`, prefixed `app-` for app-local utility classes.
- **Transloco i18n keys**: `camelCase`. Same no-abbreviation rule as identifiers
  (`sidebar.build.placeFactory`, **not** `sidebar.build.placeFct`).

## 2. Formatting

### 2.1 Indentation

- **Tabs**, not spaces. Both Java and TypeScript / HTML / SCSS / JSON.
- One tab per nesting level.
- **Exception — `application.yml`**: YAML forbids tabs at indentation. Use **2 spaces** per
  level there. This is the only file in the repo that uses spaces.

### 2.2 No mid-expression line wrapping

Lines stay on a single line. Don't pre-emptively wrap arguments or method bodies.

**Allowed exceptions** — chains and many-argument calls.

#### Chained streams / pipes

```ts
this.httpClient.get<Building[]>("/api/game/buildings")
	.pipe(
		tap((list) => this.buildings.set(list)),
		map((list) => list.length),
	)
	.subscribe();
```

```java
return Mono.fromCallable(() ->registry.

findRecipe(recipeId))
	.

subscribeOn(Schedulers.boundedElastic())
	.

map(ResponseEntity::ok)
	.

defaultIfEmpty(ResponseEntity.notFound().

build());
```

Each chained call gets its own line, indented one tab beyond the receiver.

#### Method calls with many parameters

```ts
this.gameService.placeBuilding(
	kind,
	latitude,
	longitude,
	recipeId,
);
```

```java
return new BuildingDto(
	building.id(),
	building.

kind().

name(),
	building.

lat(),
	building.

lon(),
	building.

recipeId(),
	building.

outputIngredientId(),

operations
);
```

The opening paren is on the call line; arguments are indented one tab; the closing paren
sits at the original indent level on its own line.

### 2.3 Trailing newline

Every file ends with **exactly one** trailing newline (one blank line at EOF, no more).

### 2.4 String quotes

- **TypeScript**: double quotes `"…"` for ordinary strings; backticks for template literals
  only when interpolating.
- **Java**: standard `"…"` — text blocks (`"""…"""`) for multi-line SQL / JSON / GraphQL.
- **HTML attributes**: double quotes only.
- **JSON**: double quotes (RFC 8259 — there is no choice).

### 2.5 Imports

- Group external packages first, then internal modules, separated by a blank line.
- Within a group, alphabetical by module path.
- No re-export barrels (`index.ts`) unless absolutely necessary; prefer direct imports so
  refactor tools can move files cleanly.

## 3. Architecture

### 3.1 Framework-agnostic simulation core

Anything under `backend/src/main/java/com/dimsumdetours/sim/` MUST NOT import from Spring,
Jackson, JPA, or any other framework. The package is treated as a portable C# / Unity target.
Spring beans go in `api/`, `engine/`, `osm/`, `gtfs/`, `config/`, `content/`.

### 3.2 Constants live in their own files

- Backend: `com.dimsumdetours.config.GameConstants` (and any sibling `*Constants` class for
  area-specific tunables — clock, economy, persistence, …).
- Frontend: `frontend/src/app/core/constant/<area>.constants.ts`.

Magic numbers in component / service code are forbidden. Lift them to a constants file —
the rule of thumb: "if a designer might tweak this, it goes in a constants file." If a tunable
lives on both sides (e.g. starting balance, build costs), the comment must say so on both ends.

### 3.3 Reusable UI

Common UI shapes (icon + title cards, badges, etc.) live in their own component under
`frontend/src/app/component/<name>/`. Build features by composing these — don't copy markup.
Modularise aggressively, **even if a piece is only used once**: it keeps the parent component's
TypeScript focused on logic and lets the template stay declarative.

### 3.4 Angular component file layout

Always split an Angular component into **three sibling files**:

```
component/<name>/
├── <name>.component.ts
├── <name>.component.html
└── <name>.component.scss
```

No `template:` or `styles:` inline blocks in `@Component(...)` — even one-liners. Always use
`templateUrl` and `styleUrl`. This keeps diffs clean and makes IDE template tooling work.

### 3.5 PrimeNG severities for status colour

Use PrimeNG's `severity` palette (`info`, `success`, `warn`, `error`, `secondary`) and
design tokens (`var(--p-text-muted-color)`, `var(--p-content-border-color)`, …) for status /
chrome colour. Custom hex colours are reserved for **domain colour codes** (OSM zone categories,
ingredient category badges).

### 3.6 Tooltips on icon-only affordances

Every icon-only button (no visible label) MUST have a `pTooltip` describing its action. Same
for any icon plotted on the map without a textual label (building markers, shipment glyphs).
The tooltip text must come from the i18n bundle, not be hardcoded.

### 3.7 Lombok everywhere it cleans things up

Use Lombok aggressively on Spring-side Java:

- `@RequiredArgsConstructor` on `@Service` / `@RestController` / `@Configuration` classes.
- `@Slf4j` instead of hand-rolled loggers.
- `@Getter` / `@Setter` / `@Value` / `@Data` on POJOs and properties classes.
- `@Builder` for many-field DTOs.

The `sim/` package is exempt — it stays framework-free, and adding Lombok would force every
port target to pull it in.

### 3.8 JSpecify nullness

- Each Java package has a `package-info.java` annotated `@NullMarked`. **Everything is
  non-null by default**; mark nullable values explicitly with `@Nullable`.
- Do not use `Optional` for fields or method parameters — `Optional` is for return values
  only (Java's official guidance). Use `@Nullable` instead.
- The frontend's TypeScript counterpart is `strictNullChecks: true` (already on by default
  in our `tsconfig`).

### 3.9 fastutil over JDK collections

For the simulation hot path, prefer fastutil's primitive-keyed and primitive-valued
collections:

- `Object2ObjectOpenHashMap` instead of `HashMap`
- `Long2ObjectOpenHashMap`, `Object2IntOpenHashMap`, etc. when keys/values are primitives
- `ObjectArrayList`, `IntArrayList` for typed lists
- `ObjectLists.emptyList()` instead of `Collections.emptyList()`

Use JDK collections only when crossing a public API boundary that already commits to JDK
types (e.g. records returned to JSON serialisers, where Jackson handles `List` cleanly but
not fastutil's `ObjectList`).

### 3.10 Internationalised strings

- All player-visible text lives in `frontend/src/assets/i18n/<lang>.json`.
- Hardcoding English strings in templates / TypeScript is forbidden. **Single exception**:
  `LANGUAGE_LABELS` in `core/constant/game.constants.ts` — language names always render in
  their own native script regardless of the active locale, so they're code, not translatable
  text.
- Adding a key in `en.json` without adding it to `zh.json` is a bug — the resolver falls
  back to English at runtime, but the build should still ship both.

### 3.11 Style guides

- Angular: follow https://angular.dev/style-guide — standalone components, signals over
  RxJS where possible, `input()` / `output()` instead of `@Input` / `@Output` decorators,
  `inject()` over constructor parameters, `OnPush` change detection by default.
- Java: idiomatic Java 21 — records for value types, sealed interfaces for ADTs, pattern
  matching in `switch`, virtual threads via `Thread.ofVirtual()` for I/O-bound work.
- TypeScript: idiomatic ES2024+ — `const` by default, `readonly` on every interface field,
  template literal types where they buy clarity, `satisfies` over type assertion.

### 3.12 Resolve all IDE warnings

Treat compiler / IntelliJ / TS-Server / ESLint warnings as errors. If a warning is genuinely
not applicable, suppress it locally with the narrowest possible annotation
(`@SuppressWarnings("…")`, `// eslint-disable-next-line …`) and a one-line comment explaining
why. Don't broad-suppress at the file or module level.

## 4. Testing

- **Backend**: JUnit 5 (`@Test` from `org.junit.jupiter.api`). One test class per production
  class, named `<Class>Test`, located at the mirror path under `backend/src/test/java/`.
  Use AssertJ (`assertThat(...)`) for fluency. Reactor publishers go through `StepVerifier`.
- **Frontend**: Jasmine + Karma (Angular's defaults). One spec per component / service,
  named `<thing>.<kind>.spec.ts` next to the production file. Use `TestBed` + Angular's
  `provideHttpClientTesting()` for HTTP service tests.
- Update tests whenever you change the production code — a green test that no longer asserts
  anything meaningful is worse than no test.
- Aim for tests on:
	- All branches of pure utility functions (`placement-validator`, `format-money`,
	  `localize`, …).
	- Every reducer / state-mutating method on framework-agnostic objects (`GameState`,
	  `GameClock`, …).
	- Each REST endpoint's happy path + at least one failure path.

## 5. Documentation

- Every gameplay change updates the relevant doc — `README.md` (roadmap + project structure),
  `docs/INGREDIENTS.md`, `docs/RECIPES.md`.
- New JSON content goes alongside the existing examples and is mentioned in `RECIPES.md`
  (recipes) or `INGREDIENTS.md` (ingredients / categories / operations).
- Public Java types use Javadoc; public TS types use JSDoc / TSDoc.
- Mod / content rules are described in `README.md § Modding via JSON`.

