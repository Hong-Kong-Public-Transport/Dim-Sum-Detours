import {computed, Injectable, signal} from "@angular/core";
import {GAME_CONSTANTS} from "../constants/game.constants";
import {getCookie, setCookie} from "../utilities/utilities";

const COOKIE_KEY = "dark_theme";
const HTML_DARK_CLASS = "dark-theme";
const HTML_LIGHT_CLASS = "light-theme";

/**
 * Global UI theme state. Persisted via cookie (so it survives reloads) and exposed as a
 * signal so any component (e.g. the Leaflet map) can react to changes.
 *
 * <p>The active class on {@code <html>} doubles as PrimeNG's dark-mode selector
 * (configured in {@link ../../app.config.ts}) and as the hook for the {@code :root.dark-theme}
 * variable overrides in {@code styles.scss}.
 */
@Injectable({providedIn: "root"})
export class ThemeService {
	private readonly darkTheme = signal<boolean>(getCookie(COOKIE_KEY) === "true");

	/** Read-only public view of the current theme. */
	readonly isDarkTheme = this.darkTheme.asReadonly();

	/** Derived map-tile URL — updates automatically whenever the theme flips. */
	readonly mapTileUrl = computed(() =>
		this.darkTheme() ? GAME_CONSTANTS.map.darkTiles : GAME_CONSTANTS.map.lightTiles,
	);

	constructor() {
		this.applyHtmlClass();
	}

	setTheme(isDarkTheme: boolean): void {
		this.darkTheme.set(isDarkTheme);
		setCookie(COOKIE_KEY, String(isDarkTheme));
		this.applyHtmlClass();
	}

	toggle(): void {
		this.setTheme(!this.darkTheme());
	}

	private applyHtmlClass(): void {
		const root = document.documentElement;
		if (!root) {
			return;
		}
		root.classList.toggle(HTML_DARK_CLASS, this.darkTheme());
		root.classList.toggle(HTML_LIGHT_CLASS, !this.darkTheme());
	}
}
