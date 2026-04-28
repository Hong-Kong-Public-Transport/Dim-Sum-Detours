import {ChangeDetectionStrategy, Component, computed, inject} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {ToolbarModule} from "primeng/toolbar";
import {SelectModule} from "primeng/select";
import {ButtonModule} from "primeng/button";
import {FormsModule} from "@angular/forms";

import {type AvailableLanguage, GAME_CONSTANTS} from "./core/constants/game.constants";
import {LanguageService} from "./core/services/language.service";
import {ThemeService} from "./core/services/theme.service";
import {MapComponent} from "./features/map/map.component";

interface LanguageOption {
	readonly code: AvailableLanguage;
	readonly labelKey: string;
}

@Component({
	selector: "app-root",
	imports: [TranslocoDirective, ToolbarModule, SelectModule, ButtonModule, FormsModule, MapComponent],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./app.component.html",
	styleUrl: "./app.component.scss",
})
export class AppComponent {
	private readonly languageService = inject(LanguageService);
	private readonly themeService = inject(ThemeService);

	protected readonly activeLanguage = this.languageService.activeLanguage;
	protected readonly isDarkTheme = this.themeService.isDarkTheme;

	protected readonly languageOptions = computed<LanguageOption[]>(() =>
		GAME_CONSTANTS.i18n.availableLanguages.map((code) => ({
			code,
			labelKey: `app.lang.${code}`,
		})),
	);

	protected setLanguage(code: AvailableLanguage): void {
		this.languageService.setLanguage(code);
	}

	protected toggleTheme(): void {
		this.themeService.toggle();
	}
}
