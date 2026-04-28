package com.dimsumdetours.sim.model;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Helpers for resolving inline-localised strings (the {@code displayName} maps that all
 * JSON-defined content carries).
 *
 * <p>Uses BCP-47 subtag fallback: {@code zh-Hant-HK} → {@code zh-Hant} → {@code zh} → {@code en}.
 */
public final class LocalizedText {

	/**
	 * Mandatory fallback locale; loaders reject content lacking this entry.
	 */
	public static final String FALLBACK_LOCALE = "en";

	private LocalizedText() {
		// utility class
	}

	/**
	 * Resolve the most-appropriate translation from {@code entries} for the given {@code locale},
	 * stripping region subtags as needed and falling back to {@link #FALLBACK_LOCALE}.
	 *
	 * @return the translation, or {@code null} only when even the {@code en} entry is absent
	 * (which the loader treats as invalid content).
	 */
	public static @Nullable String resolve(Map<String, String> entries, String locale) {
		String tag = locale;
		while (!tag.isEmpty()) {
			String hit = entries.get(tag);
			if (hit != null) {
				return hit;
			}
			int dashIndex = tag.lastIndexOf('-');
			if (dashIndex < 0) {
				break;
			}
			tag = tag.substring(0, dashIndex);
		}
		return entries.get(FALLBACK_LOCALE);
	}
}
