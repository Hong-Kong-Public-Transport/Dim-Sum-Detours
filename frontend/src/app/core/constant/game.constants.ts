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
		/** Currency prefix shown next to the formatted amount. Phase 3 = whole units. */
		currencySymbol: "$",
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
