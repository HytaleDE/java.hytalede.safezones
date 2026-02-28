package de.hytalede.safezones.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of current zones and overrides.
 */
public record SafeZonesSnapshot(
		List<SafeZone> zones,
		Map<ChunkPos, ChunkOverride> chunkOverrides
) {
	public SafeZonesSnapshot {
		Objects.requireNonNull(zones, "zones must not be null");
		Objects.requireNonNull(chunkOverrides, "chunkOverrides must not be null");
		zones = List.copyOf(zones);
		chunkOverrides = Collections.unmodifiableMap(chunkOverrides);
	}

	public Optional<SafeZone> findZoneFor(ChunkPos chunk) {
		SafeZone best = null;
		int bestArea = Integer.MAX_VALUE;
		for (SafeZone zone : zones) {
			if (zone.rect().contains(chunk)) {
				int area = zone.rect().areaChunks();
				if (best == null || area < bestArea || (area == bestArea && zone.id().compareToIgnoreCase(best.id()) < 0)) {
					best = zone;
					bestArea = area;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	public ChunkOverride chunkOverrideOrNone(ChunkPos chunk) {
		ChunkOverride override = chunkOverrides.get(chunk);
		return override != null ? override : ChunkOverride.none();
	}
}

