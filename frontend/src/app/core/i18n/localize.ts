import {LocalizedString} from "../model/ingredient.model";

/** Mandatory fallback locale, mirrors the backend's {@code Ingredient.FALLBACK_LOCALE}. */
export const FALLBACK_LOCALE = "en";

/**
 * Resolve a {@link LocalizedString} for a given BCP-47 locale tag.
 *
 * Tries the exact tag, then strips region subtags (e.g. {@code zh-Hant-HK} → {@code zh-Hant}
 * → {@code zh}), then falls back to {@link FALLBACK_LOCALE}. Returns an empty string if even
 * the fallback is missing — callers should treat that as a content-loader bug.
 */
export function localize(value: LocalizedString, locale: string): string {
	let tag = locale;
	while (tag.length > 0) {
		const hit = value[tag];
		if (hit !== undefined) {
			return hit;
		}
		const dash = tag.lastIndexOf("-");
		if (dash < 0) {
			break;
		}
		tag = tag.substring(0, dash);
	}
	return value[FALLBACK_LOCALE] ?? "";
}
