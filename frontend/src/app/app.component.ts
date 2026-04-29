import {ChangeDetectionStrategy, Component, computed, inject, OnInit} from "@angular/core";
import {TranslocoDirective} from "@jsverse/transloco";
import {ToolbarModule} from "primeng/toolbar";
import {SelectModule} from "primeng/select";
import {ButtonModule} from "primeng/button";
import {ConfirmDialogModule} from "primeng/confirmdialog";
import {ConfirmationService} from "primeng/api";
import {TooltipModule} from "primeng/tooltip";
import {FormsModule} from "@angular/forms";

import {type AvailableLanguage, GAME_CONSTANTS, LANGUAGE_LABELS} from "./core/constant/game.constants";
import {GameService} from "./core/service/game.service";
import {LanguageService} from "./core/service/language.service";
import {ThemeService} from "./core/service/theme.service";
import {formatMoney} from "./core/utility/format-money";
import {ClockControlsComponent} from "./component/clock-controls/clock-controls.component";
import {MapComponent} from "./component/map/map.component";

interface LanguageOption {
	readonly code: AvailableLanguage;
	readonly label: string;
}

@Component({
	selector: "app-root",
	imports: [TranslocoDirective, ToolbarModule, SelectModule, ButtonModule, ConfirmDialogModule, TooltipModule, FormsModule, ClockControlsComponent, MapComponent],
	providers: [ConfirmationService],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./app.component.html",
	styleUrl: "./app.component.scss",
})
export class AppComponent implements OnInit {
	private readonly languageService = inject(LanguageService);
	private readonly themeService = inject(ThemeService);
	private readonly gameService = inject(GameService);
	private readonly confirmationService = inject(ConfirmationService);

	protected readonly activeLanguage = this.languageService.activeLanguage;
	protected readonly isDarkTheme = this.themeService.isDarkTheme;
	protected readonly balance = this.gameService.balance;
	protected readonly formattedBalance = computed(() => formatMoney(this.balance()));

	protected readonly languageOptions: LanguageOption[] =
		GAME_CONSTANTS.i18n.availableLanguages.map((code) => ({
			code,
			label: LANGUAGE_LABELS[code],
		}));

	ngOnInit(): void {
		// Authoritative balance + buildings from server; keep going on failure (offline-friendly).
		this.gameService.refreshBalance().subscribe({error: () => undefined});
	}

	protected setLanguage(code: AvailableLanguage): void {
		this.languageService.setLanguage(code);
	}

	protected toggleTheme(): void {
		this.themeService.toggle();
	}

	protected confirmReset(message: string, header: string, acceptLabel: string, rejectLabel: string): void {
		this.confirmationService.confirm({
			message,
			header,
			icon: "pi pi-exclamation-triangle",
			acceptLabel,
			rejectLabel,
			accept: () => {
				this.gameService.resetGame().subscribe({error: () => undefined});
			},
		});
	}
}
