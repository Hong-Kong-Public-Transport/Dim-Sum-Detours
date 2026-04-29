import {bootstrapApplication} from "@angular/platform-browser";
import {AppComponent} from "./app/app.component";
import {GAME_CONSTANTS} from "./app/core/constant/game.constants";
import {definePreset} from "@primeuix/themes";
import Aura from "@primeuix/themes/aura";
import {provideHttpClient} from "@angular/common/http";
import {providePrimeNG} from "primeng/config";
import {provideTransloco} from "@jsverse/transloco";
import {getCookie} from "./app/core/utility/utilities";
import {TranslocoHttpLoader} from "./app/transloco-loader";
import {isDevMode} from "@angular/core";

bootstrapApplication(AppComponent, {
	providers: [
		provideHttpClient(),
		providePrimeNG({
			theme: {
				preset: definePreset(Aura, {
					semantic: {
						primary: {
							50: "{neutral.50}",
							100: "{neutral.100}",
							200: "{neutral.200}",
							300: "{neutral.300}",
							400: "{neutral.400}",
							500: "{neutral.500}",
							600: "{neutral.600}",
							700: "{neutral.700}",
							800: "{neutral.800}",
							900: "{neutral.900}",
							950: "{neutral.950}",
						},
					},
				}),
				options: {darkModeSelector: ".dark-theme"},
			},
		}),
		provideTransloco({
			config: {
				availableLangs: GAME_CONSTANTS.i18n.availableLanguages.slice(),
				defaultLang: getCookie("language") || GAME_CONSTANTS.i18n.defaultLanguage,
				reRenderOnLangChange: true,
				prodMode: !isDevMode(),
			},
			loader: TranslocoHttpLoader,
		}),
	],
}).catch(error => console.error(error));
