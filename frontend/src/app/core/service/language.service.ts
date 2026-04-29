import {inject, Injectable, signal} from "@angular/core";
import {TranslocoService} from "@jsverse/transloco";

import {AvailableLanguage, GAME_CONSTANTS} from "../constant/game.constants";
import {getCookie, setCookie} from "../utility/utilities";

const COOKIE_KEY = "language";

/**
 * Active language state, persisted via cookie. Wraps Transloco so components don't have
 * to talk to it directly.
 */
@Injectable({providedIn: "root"})
export class LanguageService {
	private readonly translocoService = inject(TranslocoService);

	private readonly active = signal<AvailableLanguage>(this.resolveInitialLanguage());

	/** Read-only public view of the active language. */
	readonly activeLanguage = this.active.asReadonly();

	constructor() {
		// Apply whatever we resolved (cookie or default) to Transloco on startup.
		this.translocoService.setActiveLang(this.active());
	}

	setLanguage(language: AvailableLanguage): void {
		this.active.set(language);
		this.translocoService.setActiveLang(language);
		setCookie(COOKIE_KEY, language);
	}

	/** Available languages, from {@link GAME_CONSTANTS}. */
	availableLanguages(): readonly AvailableLanguage[] {
		return GAME_CONSTANTS.i18n.availableLanguages;
	}

	private resolveInitialLanguage(): AvailableLanguage {
		const fromCookie = getCookie(COOKIE_KEY);
		const allowed = GAME_CONSTANTS.i18n.availableLanguages as readonly string[];
		if (allowed.includes(fromCookie)) {
			return fromCookie as AvailableLanguage;
		}
		return GAME_CONSTANTS.i18n.defaultLanguage as AvailableLanguage;
	}
}
