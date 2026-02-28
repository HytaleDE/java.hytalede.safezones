package de.hytalede.safezones.core;

import java.util.Objects;

public record SafeZone(
		String id,
		String title,
		ZoneRect rect,
		ZoneSettings settings
) {
	public SafeZone {
		Objects.requireNonNull(id, "id must not be null");
		if (id.isBlank()) {
			throw new IllegalArgumentException("id must not be blank");
		}
		if (title != null && title.isBlank()) {
			title = null;
		}
		Objects.requireNonNull(rect, "rect must not be null");
		Objects.requireNonNull(settings, "settings must not be null");
	}

	public String titleOrDefault(String defaultTitle) {
		return title != null ? title : defaultTitle;
	}
}

