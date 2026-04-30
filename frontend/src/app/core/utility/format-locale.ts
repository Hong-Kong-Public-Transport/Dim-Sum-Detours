/**
 * Locale-aware formatting helpers. The browser's locale (`navigator.language`) drives
 * thousand separators, decimal points, date order, and the 12/24-hour clock. The currency
 * symbol stays a literal `$` (per design — game currency is dollars regardless of region).
 */

import {GAME_CONSTANTS} from "../constant/game.constants";

/** Best-effort browser locale; falls back to {@code en-GB} when SSR / tests don't expose one. */
export function browserLocale(): string {
	if (typeof navigator !== "undefined" && navigator.language) {
		return navigator.language;
	}
	return "en-GB";
}

/** Format an integer money amount with locale-aware thousand separators, prefixed with {@code $}. */
export function formatMoney(amount: number, locale: string = browserLocale()): string {
	const formatted = new Intl.NumberFormat(locale, {maximumFractionDigits: 0}).format(amount);
	return `${GAME_CONSTANTS.economy.currencySymbol}${formatted}`;
}

/** Locale-aware number formatting; defaults to whatever the browser advertises. */
export function formatNumber(
	value: number,
	options: Intl.NumberFormatOptions = {},
	locale: string = browserLocale(),
): string {
	return new Intl.NumberFormat(locale, options).format(value);
}

/** Locale-aware date formatting (e.g. {@code dd/MM/yyyy} in HK, {@code M/d/yyyy} in US). */
export function formatDate(
	date: Date,
	options: Intl.DateTimeFormatOptions = {dateStyle: "short"},
	locale: string = browserLocale(),
): string {
	return new Intl.DateTimeFormat(locale, options).format(date);
}

/** Locale-aware time formatting (e.g. 24h in HK, 12h with AM/PM in US). */
export function formatTime(
	date: Date,
	options: Intl.DateTimeFormatOptions = {timeStyle: "short"},
	locale: string = browserLocale(),
): string {
	return new Intl.DateTimeFormat(locale, options).format(date);
}

