package de.hytalede.safezones.storage;

import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.ClaimType;
import de.hytalede.safezones.core.PermissionOverride;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.core.ZoneSettings;
import de.hytalede.safezones.core.ZoneSettingsPatch;

import java.io.IOException;
import java.util.Optional;
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

		SafeZonesSnapshot snapshot = new SafeZonesSnapshot(zones, data.chunkOverrides());
		SafeZonesSnapshot migrated = migrateClaimHeightsAndOverrides(snapshot);
		if (!migrated.chunkOverrides().equals(snapshot.chunkOverrides())) {
			saveSnapshot(migrated);
			return migrated;
		}
		return snapshot;
	}

	/**
	 * Migration: align all claim buildHeight/digDepth with zone defaults (owner and trusted),
	 * and set claim groundY to zone uniformGroundY when zone has uniform mode (uniformGroundY != 0).
	 */
	private static SafeZonesSnapshot migrateClaimHeightsAndOverrides(SafeZonesSnapshot snapshot) {
		Map<ChunkPos, ChunkOverride> out = new LinkedHashMap<>();
		for (Map.Entry<ChunkPos, ChunkOverride> e : snapshot.chunkOverrides().entrySet()) {
			ChunkPos chunk = e.getKey();
			ChunkOverride ov = e.getValue();
			Optional<SafeZone> zoneOpt = snapshot.findZoneFor(chunk);
			boolean isClaimOrTrusted = ov.claimType() == ClaimType.PLAYER_OWNER
					|| (ov.playerOverrides() != null && !ov.playerOverrides().isEmpty());
			if (zoneOpt.isEmpty() || !isClaimOrTrusted) {
				out.put(chunk, ov);
				continue;
			}
			ZoneSettings zoneSettings = zoneOpt.get().settings();
			Integer newGroundY = ov.groundY();
			if (zoneSettings.uniformGroundY() != 0) {
				newGroundY = zoneSettings.uniformGroundY();
			}
			Map<String, PermissionOverride> newPo = new LinkedHashMap<>();
			for (Map.Entry<String, PermissionOverride> pe : ov.playerOverrides().entrySet()) {
				PermissionOverride po = pe.getValue();
				// Clear buildHeight/digDepth so zone defaults apply (owner and trusted match).
				newPo.put(pe.getKey(), new PermissionOverride(
						po.canBuild(), po.canMine(), null, null,
						po.allowInteract(), po.allowContainers(), po.allowUseEntities(),
						po.allowItemDrop(), po.allowItemPickup()));
			}
			out.put(chunk, new ChunkOverride(ov.type(), ov.claimType(), ov.owner(), newGroundY, newPo));
		}
		return new SafeZonesSnapshot(snapshot.zones(), out);
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

