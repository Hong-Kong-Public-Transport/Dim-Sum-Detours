import {ChangeDetectionStrategy, Component, input} from "@angular/core";
import {CardModule} from "primeng/card";

/**
 * Sidebar / inline card with a PrimeIcon glyph + title in the header. The body is a single
 * content-projection slot. Pure chrome — no business logic.
 */
@Component({
	selector: "app-panel",
	imports: [CardModule],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./panel.component.html",
	styleUrl: "./panel.component.scss",
})
export class PanelComponent {
	readonly icon = input.required<string>();
	readonly title = input.required<string>();
}
