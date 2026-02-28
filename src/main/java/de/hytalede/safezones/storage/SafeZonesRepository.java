package de.hytalede.safezones.storage;

import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.core.ZoneSettings;
import de.hytalede.safezones.core.ZoneSettingsPatch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SafeZonesRepository {
	private final SafeZonesConfig config;
	private final Path zonesPath;

	public SafeZonesRepository(SafeZonesConfig config, Path zonesPath) {
		this.config = Objects.requireNonNull(config, "config must not be null");
		this.zonesPath = Objects.requireNonNull(zonesPath, "zonesPath must not be null");
	}

	public SafeZonesSnapshot loadSnapshot() throws IOException {
		ZonesFileData data = ZonesJsonStorage.load(zonesPath);
		if (data.migrated()) {
			// Persist migrated format so future loads are fast and consistent.
			ZonesJsonStorage.save(zonesPath, new ZonesFileData(data.zones(), data.chunkOverrides(), 16, false));
		}
		List<SafeZone> zones = new ArrayList<>();
		for (ZonesFileEntry entry : data.zones()) {
			ZoneSettingsPatch patch = entry.settings();
			ZoneSettings settings = (patch != null ? patch.applyOn(config.defaults()) : config.defaults());
			zones.add(new SafeZone(entry.id(), entry.title(), entry.rect(), settings));
		}

		// Deterministic ordering: smaller zones first, then id.
		zones.sort(Comparator
				.comparingInt((SafeZone z) -> z.rect().areaChunks())
				.thenComparing(SafeZone::id, String.CASE_INSENSITIVE_ORDER));

		return new SafeZonesSnapshot(zones, data.chunkOverrides());
	}

	public void saveSnapshot(SafeZonesSnapshot snapshot) throws IOException {
		Objects.requireNonNull(snapshot, "snapshot must not be null");

		List<ZonesFileEntry> zones = snapshot.zones().stream()
				.sorted(Comparator
						.comparingInt((SafeZone z) -> z.rect().areaChunks())
						.thenComparing(SafeZone::id, String.CASE_INSENSITIVE_ORDER))
				.map(z -> new ZonesFileEntry(z.id(), z.title(), z.rect(), ZoneSettingsDiff.diff(config.defaults(), z.settings())))
				.toList();

		// Preserve stable key ordering for YAML output
		Map<ChunkPos, ChunkOverride> overrides = new LinkedHashMap<>();
		snapshot.chunkOverrides().entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z)))
				.forEach(e -> overrides.put(e.getKey(), e.getValue()));

		ZonesJsonStorage.save(zonesPath, new ZonesFileData(zones, overrides, 16, false));
	}
}

