package de.hytalede.safezones.config;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record MessagesConfig(
		Map<String, String> de,
		Map<String, String> en
) {
	public MessagesConfig {
		Objects.requireNonNull(de, "messages.de must not be null");
		Objects.requireNonNull(en, "messages.en must not be null");
		de = Collections.unmodifiableMap(de);
		en = Collections.unmodifiableMap(en);
	}

	public Map<String, String> forLocale(Locale locale) {
		if (locale == null) {
			return en;
		}
		String language = locale.getLanguage();
		if (language != null && language.equalsIgnoreCase("de")) {
			return de;
		}
		return en;
	}
}

