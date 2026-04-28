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
});

export type GameSpeed = (typeof GAME_CONSTANTS.clock.speeds)[number];
export type AvailableLanguage = (typeof GAME_CONSTANTS.i18n.availableLanguages)[number];
