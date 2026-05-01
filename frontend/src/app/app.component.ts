import {ChangeDetectionStrategy, Component, computed, inject, OnInit} from "@angular/core";
import {FormsModule} from "@angular/forms";

import {TranslocoDirective} from "@jsverse/transloco";
import {ConfirmationService} from "primeng/api";
import {ButtonModule} from "primeng/button";
import {ConfirmDialogModule} from "primeng/confirmdialog";
import {DialogModule} from "primeng/dialog";
import {ProgressSpinnerModule} from "primeng/progressspinner";
import {SelectModule} from "primeng/select";
import {ToolbarModule} from "primeng/toolbar";
import {TooltipModule} from "primeng/tooltip";

import {ClockControlsComponent} from "./component/clock-controls/clock-controls.component";
import {MapComponent} from "./component/map/map.component";
import {MilestoneToastComponent} from "./component/milestone-toast/milestone-toast.component";
import {type AvailableLanguage, GAME_CONSTANTS, LANGUAGE_LABELS} from "./core/constant/game.constants";
import {GameService} from "./core/service/game.service";
import {GtfsService} from "./core/service/gtfs.service";
import {LanguageService} from "./core/service/language.service";
import {OsmService} from "./core/service/osm.service";
import {ThemeService} from "./core/service/theme.service";
import {formatMoney} from "./core/utility/format-locale";

interface LanguageOption {
	readonly code: AvailableLanguage;
	readonly label: string;
}

@Component({
	selector: "app-root",
	imports: [
		ButtonModule,
		ClockControlsComponent,
		ConfirmDialogModule,
		DialogModule,
		FormsModule,
		MapComponent,
		MilestoneToastComponent,
		ProgressSpinnerModule,
		SelectModule,
		ToolbarModule,
		TooltipModule,
		TranslocoDirective,
	],
	providers: [ConfirmationService],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./app.component.html",
	styleUrl: "./app.component.scss",
})
export class AppComponent implements OnInit {
	private readonly languageService = inject(LanguageService);
	private readonly themeService = inject(ThemeService);
	private readonly gameService = inject(GameService);
	private readonly gtfsService = inject(GtfsService);
	private readonly osmService = inject(OsmService);
	private readonly confirmationService = inject(ConfirmationService);

	protected readonly activeLanguage = this.languageService.activeLanguage;
	protected readonly isDarkTheme = this.themeService.isDarkTheme;
	protected readonly balance = this.gameService.balance;
	protected readonly formattedBalance = computed(() => formatMoney(this.balance()));
	/** Combined initial-load gate: covers both the GTFS feed listing and Overpass placement zones. */
	protected readonly loadingWorld = computed(() => this.gtfsService.loading() || this.osmService.loading());

	protected readonly loadingMessage = computed(() => {
		// While GTFS is still being scanned, the headline message is GTFS-specific; once that
		// resolves and we're waiting on Overpass, switch to the placement-zones message.
		return this.gtfsService.loading() ? "app.loading.gtfs" : "app.loading.zones";
	});

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
