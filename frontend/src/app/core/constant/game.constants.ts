/**
 * All UI-side game constants. ONE place to look, ONE place to tweak.
 *
 * Mirrors backend `com.dimsumdetours.config.GameConstants` where appropriate.
 * Kept as a frozen object so accidental mutation is rejected at runtime in dev.
 */
export const GAME_CONSTANTS = Object.freeze({
	i18n: Object.freeze({
		/** Default language: British English. */
		defaultLanguage: "en",
		/** Available languages. Add the matching JSON in src/assets/i18n/. */
		availableLanguages: ["en", "zh"] as const,
	}),

	map: Object.freeze({
		/** Default Leaflet zoom level on game start. */
		defaultZoom: 13,
		minZoom: 10,
		maxZoom: 19,
		/** Tile provider URL — swap for a custom OSM mirror or styled tile server later. */
		tileAttribution: `&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>`,
		lightTiles: "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png",
		darkTiles: "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png",
		/** Fallback bounding box used until a GTFS feed is loaded (Seattle). */
		fallbackCenter: {lat: 47.608013, lng: -122.335167},
		/** Per-category Leaflet path styling for the placement-zone overlays. */
		layerStyles: Object.freeze({
			park: Object.freeze({color: "#3fa34d", weight: 1, fillColor: "#3fa34d", fillOpacity: 0.35}),
			farmland: Object.freeze({color: "#d4a373", weight: 1, fillColor: "#d4a373", fillOpacity: 0.35}),
			water: Object.freeze({color: "#3a86ff", weight: 1, fillColor: "#3a86ff", fillOpacity: 0.45}),
			coastline: Object.freeze({color: "#3a86ff", weight: 2, fillOpacity: 0}),
			commercial: Object.freeze({color: "#9aa0a6", weight: 1, fillColor: "#9aa0a6", fillOpacity: 0.3}),
			residential: Object.freeze({color: "#e07a5f", weight: 1, fillColor: "#e07a5f", fillOpacity: 0.25}),
		}),
	}),

	clock: Object.freeze({
		/** Speed multipliers exposed in the UI. 0 = paused. */
		speeds: [0, 1, 4, 16, 64, 256] as const,
		defaultSpeed: 1,
	}),

	ui: Object.freeze({
		/** Milliseconds of debounce on mouse-driven map interactions before recomputing overlays. */
		mapDebounceMilliseconds: 120,
	}),

	economy: Object.freeze({
		/** Starting wallet — UI default until {@code /api/game/balance} resolves. */
		startingBalance: 10_000,
		/** Display costs (must mirror backend {@code GameConstants.FARM_BUILD_COST} / {@code FACTORY_BUILD_COST}). */
		farmBuildCost: 500,
		factoryBuildCost: 1_500,
		/**
		 * Restaurants are NPC-spawned by {@code RestaurantSpawnerService}, not built by the
		 * player. Cost stays at 0 so a tab refresh that re-runs the spawner doesn't drain the
		 * wallet. Mirrors backend {@code GameConstants.RESTAURANT_BUILD_COST}.
		 */
		restaurantBuildCost: 0,
		/** Currency prefix shown next to the formatted amount. Phase 3 = whole units. */
		currencySymbol: "$",
	}),

	placement: Object.freeze({
		/**
		 * Minimum geodesic spacing between any two same-kind buildings, in metres. Mirrors
		 * backend {@code GameConstants.MIN_BUILDING_SPACING_METERS}; drives the cursor preview
		 * in {@code placement-validator} so the player sees a "no" cursor before clicking.
		 */
		minBuildingSpacingMeters: 100,
	}),

	spawn: Object.freeze({
		/** Number of restaurants the auto-spawner drops onto the map once zones load. */
		restaurantsPerWorld: 6,
		/** Zone kinds eligible for restaurant auto-spawn. */
		restaurantZoneKinds: ["residential", "commercial"] as const,
	}),

	delivery: Object.freeze({
		/**
		 * Cargo speed in real-world metres per game-minute. At 1× game speed (1 game-minute
		 * per real second) this works out to ~10 m/s ≈ a brisk delivery van — fast enough that
		 * the marker visibly moves at default speed, slow enough that you can still watch it.
		 */
		metersPerGameMinute: 600,
		/** Minimum on-screen lifetime of a delivery marker, in real milliseconds. */
		minimumDurationMilliseconds: 500,
	}),

	walker: Object.freeze({
		/**
		 * @deprecated Phase-12 superseded by {@link GAME_CONSTANTS.robot.metersPerGameMinute}.
		 * Kept for one cycle so any in-flight branch finishes — slated for removal once the
		 * Phase-13 walker-leg metadata cleanup lands.
		 */
		metersPerGameMinute: 80,
		/** @deprecated see above. */
		busSpeedMultiplier: 6,
	}),

	robot: Object.freeze({
		/**
		 * Phase-12 robot model. Casual-biking pace ≈ 10 km/h. Mirrors backend
		 * {@code GameConstants.ROBOT_METERS_PER_GAME_MINUTE}; consumed by
		 * {@code VehicleService.interpolatePosition} to walk a robot along its server-supplied
		 * path. Slower than buses (added in a future phase), faster than the deprecated
		 * walker — robots feel like couriers, not pedestrians.
		 */
		metersPerGameMinute: 170,
	}),
});

export type GameSpeed = (typeof GAME_CONSTANTS.clock.speeds)[number];
export type AvailableLanguage = (typeof GAME_CONSTANTS.i18n.availableLanguages)[number];

/**
 * Native-script display label for each available language. Hardcoded (not in the translation
 * JSON files) because a language picker should always show each language in its own script —
 * regardless of the currently active language.
 */
export const LANGUAGE_LABELS: Readonly<Record<AvailableLanguage, string>> = Object.freeze({
	en: "English",
	zh: "繁體中文 (香港)",
});
