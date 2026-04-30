import {ChangeDetectionStrategy, Component, input, model} from "@angular/core";
import {FormsModule} from "@angular/forms";

import {IconFieldModule} from "primeng/iconfield";
import {InputIconModule} from "primeng/inputicon";
import {InputTextModule} from "primeng/inputtext";

/**
 * Reusable search/filter input. Wraps PrimeNG's icon field + input text so the same chrome
 * is used for the sidebar ingredients/recipes panels and the placement recipe-picker dialog.
 *
 * <p>Pure presentation: the parent owns the source list and applies the filter using the
 * two-way-bound {@code value} model. The component intentionally does no autocomplete /
 * suggestions — see the README "Phase 7" note for the design rationale.
 */
@Component({
	selector: "app-search-box",
	imports: [FormsModule, IconFieldModule, InputIconModule, InputTextModule],
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./search-box.component.html",
	styleUrl: "./search-box.component.scss",
})
export class SearchBoxComponent {
	readonly placeholder = input<string>("Search…");
	readonly ariaLabel = input<string>("");
	readonly value = model<string>("");
}

