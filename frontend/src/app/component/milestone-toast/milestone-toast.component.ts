import {ChangeDetectionStrategy, Component, effect, inject} from "@angular/core";

import {TranslocoService} from "@jsverse/transloco";
import {MessageService} from "primeng/api";
import {ToastModule} from "primeng/toast";

import type {MilestoneId} from "../../core/model/milestone.model";
import {MilestoneService} from "../../core/service/milestone.service";

/**
 * Phase-8 task 7 milestone toast. Subscribes to {@link MilestoneService#lastEvent} and pops
 * a celebratory PrimeNG toast (severity {@code success}) the moment the backend announces
 * an unlock. The trophy icon glyph + the i18n keys for the milestone title give the player
 * a clear, locale-aware "you just achieved X" notification — the README's "soft win"
 * teaser for {@code CITY_BUILDER} bumps the toast life to 8s so it lingers long enough to
 * read.
 *
 * <p>OnPush + signals — the {@link MessageService} call inside the {@link effect} is the
 * only side-effect, and PrimeNG handles the toast queueing internally. Standalone +
 * three-file per the project's component contract; the SCSS file holds nothing of
 * substance because PrimeNG provides every visual treatment we need.
 */
@Component({
	selector: "app-milestone-toast",
	imports: [ToastModule],
	providers: [MessageService],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./milestone-toast.component.html",
	styleUrl: "./milestone-toast.component.scss",
})
export class MilestoneToastComponent {
	private readonly milestoneService = inject(MilestoneService);
	private readonly messageService = inject(MessageService);
	private readonly translocoService = inject(TranslocoService);

	constructor() {
		effect(() => {
			const event = this.milestoneService.lastEvent();
			if (!event) {
				return;
			}
			const titleKey = MilestoneToastComponent.titleKey(event.milestone);
			const detailKey = MilestoneToastComponent.detailKey(event.milestone);
			this.messageService.add({
				severity: "success",
				summary: this.translocoService.translate(titleKey),
				detail: this.translocoService.translate(detailKey),
				icon: "pi pi-trophy",
				life: event.milestone === "CITY_BUILDER" ? 8000 : 5000,
			});
		});
	}

	private static titleKey(id: MilestoneId): string {
		return `milestone.${MilestoneToastComponent.suffix(id)}.title`;
	}

	private static detailKey(id: MilestoneId): string {
		return `milestone.${MilestoneToastComponent.suffix(id)}.detail`;
	}

	private static suffix(id: MilestoneId): string {
		switch (id) {
			case "FIRST_DELIVERY": return "firstDelivery";
			case "COLD_CHAIN": return "coldChain";
			case "NEIGHBORHOOD_HERO": return "neighbourhoodHero";
			case "VERTICAL_INTEGRATION": return "verticalIntegration";
			case "CUISINE_MASTER": return "cuisineMaster";
			case "TRANSIT_TYCOON": return "transitTycoon";
			case "CITY_BUILDER": return "cityBuilder";
		}
	}
}

