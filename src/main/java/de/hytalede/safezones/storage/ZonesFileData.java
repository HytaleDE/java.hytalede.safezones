package de.hytalede.safezones.storage;

import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ZonesFileData(
		List<ZonesFileEntry> zones,
		Map<ChunkPos, ChunkOverride> chunkOverrides,
		int cellSize,
		boolean migrated
) {
	public ZonesFileData {
		Objects.requireNonNull(zones, "zones must not be null");
		Objects.requireNonNull(chunkOverrides, "chunkOverrides must not be null");
		zones = List.copyOf(zones);
		chunkOverrides = Collections.unmodifiableMap(chunkOverrides);
		if (cellSize <= 0) {
			cellSize = 16;
		}
	}
}

