package de.hytalede.safezones.storage;

import de.hytalede.safezones.core.ZoneRect;
import de.hytalede.safezones.core.ZoneSettingsPatch;

import java.util.Objects;

public record ZonesFileEntry(
		String id,
		String title,
		ZoneRect rect,
		ZoneSettingsPatch settings
) {
	public ZonesFileEntry {
		Objects.requireNonNull(id, "id must not be null");
		if (id.isBlank()) {
			throw new IllegalArgumentException("id must not be blank");
		}
		if (title != null && title.isBlank()) {
			title = null;
		}
		Objects.requireNonNull(rect, "rect must not be null");
		// settings may be null -> means \"use defaults\"
	}
}

