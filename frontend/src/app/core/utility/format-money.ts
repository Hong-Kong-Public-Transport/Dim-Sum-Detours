import {GAME_CONSTANTS} from "../constant/game.constants";

/**
 * Format a money amount (smallest currency unit; Phase 3 = whole dollars) for display.
 * Locale-aware thousand separators; no fractional digits since Phase 3 stores whole units.
 */
export function formatMoney(amount: number, locale = "en-GB"): string {
	const formatted = new Intl.NumberFormat(locale, {
		maximumFractionDigits: 0,
	}).format(amount);
	return `${GAME_CONSTANTS.economy.currencySymbol}${formatted}`;
}

