package de.hytalede.safezones.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.CellType;
import de.hytalede.safezones.core.ClaimType;
import de.hytalede.safezones.core.PermissionOverride;
import de.hytalede.safezones.core.PlayerNames;
import de.hytalede.safezones.core.ZoneRect;
import de.hytalede.safezones.core.ZoneSettingsPatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static de.hytalede.safezones.storage.ZonesJsonUtil.*;

public final class ZonesJsonStorage {
	private ZonesJsonStorage() {
	}

	private static final int CURRENT_CELL_SIZE = 16;
	private static final int LEGACY_CELL_SIZE = 32;

	public static ZonesFileData load(Path zonesPath) throws IOException {
		if (zonesPath == null) {
			throw new IllegalArgumentException("zonesPath must not be null");
		}
		if (!Files.exists(zonesPath)) {
			return new ZonesFileData(List.of(), Map.of(), CURRENT_CELL_SIZE, false);
		}

		try (InputStream inputStream = Files.newInputStream(zonesPath)) {
			ObjectMapper mapper = new ObjectMapper();
			Object raw = mapper.readValue(inputStream, Object.class);
			if (raw == null) {
				return new ZonesFileData(List.of(), Map.of(), CURRENT_CELL_SIZE, false);
			}
			if (!(raw instanceof Map<?, ?> root)) {
				throw new IllegalArgumentException("Invalid JSON root (expected object)");
			}

			Map<String, Object> rootMap = castStringObjectMap(root, "root");
			int cellSize = getInt(rootMap, "cellSize", false);
			if (cellSize <= 0) {
				// Legacy files had no cellSize field; they were 32x32 chunk-based.
				cellSize = LEGACY_CELL_SIZE;
			}

			List<ZonesFileEntry> zones = new ArrayList<>();
			Object zonesObj = rootMap.get("zones");
			if (zonesObj instanceof List<?> zonesList) {
				for (Object item : zonesList) {
					if (!(item instanceof Map<?, ?> zoneMapRaw)) {
						throw new IllegalArgumentException("zones entries must be objects");
					}
					Map<String, Object> zoneMap = castStringObjectMap(zoneMapRaw, "zone");

					String id = getString(zoneMap, "id", true);
					String title = getString(zoneMap, "title", false);
					if (title != null && title.isBlank()) {
						title = null;
					}
					Map<String, Object> rectMap = getMap(zoneMap, "rect", true);
					int minX = getInt(rectMap, "minX", true);
					int maxX = getInt(rectMap, "maxX", true);
					int minZ = getInt(rectMap, "minZ", true);
					int maxZ = getInt(rectMap, "maxZ", true);
					ZoneRect rect = new ZoneRect(minX, maxX, minZ, maxZ);

					ZoneSettingsPatch patch = null;
					Object settingsObj = zoneMap.get("settings");
					if (settingsObj != null) {
						if (!(settingsObj instanceof Map<?, ?> sm)) {
							throw new IllegalArgumentException("zone.settings must be an object");
						}
						Map<String, Object> settingsMap = castStringObjectMap(sm, "settings");
						patch = patchFromMap(settingsMap);
					}

					zones.add(new ZonesFileEntry(id, title, rect, patch));
				}
			} else if (zonesObj != null) {
				throw new IllegalArgumentException("zones must be an array");
			}

			Map<ChunkPos, ChunkOverride> overrides = new LinkedHashMap<>();
			Object overridesObj = rootMap.get("chunkOverrides");
			if (overridesObj instanceof Map<?, ?> overridesRaw) {
				Map<String, Object> overridesMap = castStringObjectMap(overridesRaw, "chunkOverrides");
				for (Map.Entry<String, Object> entry : overridesMap.entrySet()) {
					ChunkPos chunk = ChunkPos.parseKey(entry.getKey());
					if (!(entry.getValue() instanceof Map<?, ?> ovRaw)) {
						throw new IllegalArgumentException("chunkOverrides." + entry.getKey() + " must be an object");
					}
					Map<String, Object> ovMap = castStringObjectMap(ovRaw, "chunkOverride");

					CellType type = CellType.parse(getString(ovMap, "type", false));
					Integer groundY = null;
					try {
						int gy = getInt(ovMap, "groundY", false);
						if (gy != 0 || ovMap.containsKey("groundY")) {
							groundY = gy;
						}
					} catch (Exception ignored) {
						groundY = null;
					}
					String claimTypeRaw = getString(ovMap, "claimType", false);
					ClaimType claimType = claimTypeRaw.isBlank() ? ClaimType.NONE : ClaimType.valueOf(claimTypeRaw);
					String owner = getString(ovMap, "owner", false);
					if (!owner.isBlank()) {
						owner = PlayerNames.normalize(owner);
					} else {
						owner = null;
					}

					Map<String, PermissionOverride> playerOverrides = new LinkedHashMap<>();
					Object poObj = ovMap.get("playerOverrides");
					if (poObj instanceof Map<?, ?> poRaw) {
						Map<String, Object> poMap = castStringObjectMap(poRaw, "playerOverrides");
						for (Map.Entry<String, Object> pe : poMap.entrySet()) {
							String playerLower = PlayerNames.normalize(pe.getKey());
							if (!(pe.getValue() instanceof Map<?, ?> prmRaw)) {
								throw new IllegalArgumentException("playerOverrides." + pe.getKey() + " must be an object");
							}
							Map<String, Object> prm = castStringObjectMap(prmRaw, "permissionOverride");
							playerOverrides.put(playerLower, permissionOverrideFromMap(prm));
						}
					} else if (poObj != null) {
						throw new IllegalArgumentException("playerOverrides must be an object");
					}

					overrides.put(chunk, new ChunkOverride(type, claimType, owner, groundY, playerOverrides));
				}
			} else if (overridesObj != null) {
				throw new IllegalArgumentException("chunkOverrides must be an object");
			}

			boolean migrated = false;
			if (cellSize == LEGACY_CELL_SIZE) {
				// Migrate legacy 32x32-chunk coordinate system to 16x16-cells:
				// - zone rects: [min,max] -> [min*2, max*2+1]
				// - overrides: each old chunk -> 4 new cells
				zones = migrateZonesTo16(zones);
				overrides = migrateOverridesTo16(overrides);
				cellSize = CURRENT_CELL_SIZE;
				migrated = true;
			} else if (cellSize != CURRENT_CELL_SIZE) {
				throw new IllegalArgumentException("Unsupported cellSize: " + cellSize + " (expected " + CURRENT_CELL_SIZE + ")");
			}

			return new ZonesFileData(zones, overrides, cellSize, migrated);
		}
	}

	public static void save(Path zonesPath, ZonesFileData data) throws IOException {
		if (zonesPath == null) {
			throw new IllegalArgumentException("zonesPath must not be null");
		}
		if (data == null) {
			throw new IllegalArgumentException("data must not be null");
		}

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("cellSize", CURRENT_CELL_SIZE);

		List<Object> zones = new ArrayList<>();
		for (ZonesFileEntry entry : data.zones()) {
			Map<String, Object> z = new LinkedHashMap<>();
			z.put("id", entry.id());
			if (entry.title() != null && !entry.title().isBlank()) {
				z.put("title", entry.title());
			}
			z.put("rect", Map.of(
					"minX", entry.rect().minX(),
					"maxX", entry.rect().maxX(),
					"minZ", entry.rect().minZ(),
					"maxZ", entry.rect().maxZ()
			));
			if (entry.settings() != null) {
				Map<String, Object> settings = mapFromPatch(entry.settings());
				if (!settings.isEmpty()) {
					z.put("settings", settings);
				}
			}
			zones.add(z);
		}
		root.put("zones", zones);

		Map<String, Object> chunkOverrides = new LinkedHashMap<>();
		for (Map.Entry<ChunkPos, ChunkOverride> e : data.chunkOverrides().entrySet()) {
			ChunkOverride ov = e.getValue();
			Map<String, Object> m = new LinkedHashMap<>();
			if (ov.type() != null && ov.type() != CellType.NONE) {
				m.put("type", ov.type().name().toLowerCase(java.util.Locale.ROOT));
			}
			if (ov.groundY() != null) {
				m.put("groundY", ov.groundY());
			}
			if (ov.claimType() != null && ov.claimType() != ClaimType.NONE) {
				m.put("claimType", ov.claimType().name());
			}
			if (ov.owner() != null && !ov.owner().isBlank()) {
				m.put("owner", ov.owner());
			}
			if (!ov.playerOverrides().isEmpty()) {
				Map<String, Object> po = new LinkedHashMap<>();
				for (Map.Entry<String, PermissionOverride> pe : ov.playerOverrides().entrySet()) {
					po.put(pe.getKey(), mapFromPermissionOverride(pe.getValue()));
				}
				m.put("playerOverrides", po);
			}
			if (!m.isEmpty()) {
				chunkOverrides.put(e.getKey().toKey(), m);
			}
		}
		if (!chunkOverrides.isEmpty()) {
			root.put("chunkOverrides", chunkOverrides);
		}

		if (zonesPath.getParent() != null) {
			Files.createDirectories(zonesPath.getParent());
		}

		ObjectMapper mapper = new ObjectMapper();
		mapper.writerWithDefaultPrettyPrinter().writeValue(zonesPath.toFile(), root);
	}

	private static List<ZonesFileEntry> migrateZonesTo16(List<ZonesFileEntry> zones) {
		List<ZonesFileEntry> out = new ArrayList<>(zones.size());
		for (ZonesFileEntry e : zones) {
			ZoneRect r = e.rect();
			ZoneRect rr = new ZoneRect(
					r.minX() * 2,
					r.maxX() * 2 + 1,
					r.minZ() * 2,
					r.maxZ() * 2 + 1
			);
			out.add(new ZonesFileEntry(e.id(), e.title(), rr, e.settings()));
		}
		return out;
	}

	private static Map<ChunkPos, ChunkOverride> migrateOverridesTo16(Map<ChunkPos, ChunkOverride> overrides) {
		Map<ChunkPos, ChunkOverride> out = new LinkedHashMap<>();
		for (Map.Entry<ChunkPos, ChunkOverride> e : overrides.entrySet()) {
			ChunkPos p = e.getKey();
			ChunkOverride ov = e.getValue();
			int baseX = p.x() * 2;
			int baseZ = p.z() * 2;
			out.put(new ChunkPos(baseX, baseZ), ov);
			out.put(new ChunkPos(baseX + 1, baseZ), ov);
			out.put(new ChunkPos(baseX, baseZ + 1), ov);
			out.put(new ChunkPos(baseX + 1, baseZ + 1), ov);
		}
		return out;
	}

	private static ZoneSettingsPatch patchFromMap(Map<String, Object> map) {
		return new ZoneSettingsPatch(
				getBooleanBoxed(map, "pvp"),
				getBooleanBoxed(map, "takeDamage"),
				getBooleanBoxed(map, "allowProjectiles"),
				getBooleanBoxed(map, "allowExplosionDamage"),
				getBooleanBoxed(map, "spawnMobs"),
				getBooleanBoxed(map, "allowMobTargetPlayers"),
				getBooleanBoxed(map, "denyMobEnter"),
				getBooleanBoxed(map, "canBuild"),
				getBooleanBoxed(map, "canMine"),
				getIntBoxed(map, "groundY"),
				getIntBoxed(map, "buildHeight"),
				getIntBoxed(map, "digDepth"),
				getBooleanBoxed(map, "allowInteract"),
				getBooleanBoxed(map, "allowContainers"),
				getBooleanBoxed(map, "allowUseEntities"),
				getBooleanBoxed(map, "allowItemDrop"),
				getBooleanBoxed(map, "allowItemPickup"),
				getBooleanBoxed(map, "preventFireSpread"),
				getBooleanBoxed(map, "denyLiquidFlow"),
				getBooleanBoxed(map, "denyPistons")
		);
	}

	private static PermissionOverride permissionOverrideFromMap(Map<String, Object> map) {
		return new PermissionOverride(
				getBooleanBoxed(map, "canBuild"),
				getBooleanBoxed(map, "canMine"),
				getIntBoxed(map, "buildHeight"),
				getIntBoxed(map, "digDepth"),
				getBooleanBoxed(map, "allowInteract"),
				getBooleanBoxed(map, "allowContainers"),
				getBooleanBoxed(map, "allowUseEntities"),
				getBooleanBoxed(map, "allowItemDrop"),
				getBooleanBoxed(map, "allowItemPickup")
		);
	}

	private static Map<String, Object> mapFromPatch(ZoneSettingsPatch patch) {
		Map<String, Object> out = new LinkedHashMap<>();
		putIfNonNull(out, "pvp", patch.pvp());
		putIfNonNull(out, "takeDamage", patch.takeDamage());
		putIfNonNull(out, "allowProjectiles", patch.allowProjectiles());
		putIfNonNull(out, "allowExplosionDamage", patch.allowExplosionDamage());
		putIfNonNull(out, "spawnMobs", patch.spawnMobs());
		putIfNonNull(out, "allowMobTargetPlayers", patch.allowMobTargetPlayers());
		putIfNonNull(out, "denyMobEnter", patch.denyMobEnter());
		putIfNonNull(out, "canBuild", patch.canBuild());
		putIfNonNull(out, "canMine", patch.canMine());
		putIfNonNull(out, "groundY", patch.groundY());
		putIfNonNull(out, "buildHeight", patch.buildHeight());
		putIfNonNull(out, "digDepth", patch.digDepth());
		putIfNonNull(out, "allowInteract", patch.allowInteract());
		putIfNonNull(out, "allowContainers", patch.allowContainers());
		putIfNonNull(out, "allowUseEntities", patch.allowUseEntities());
		putIfNonNull(out, "allowItemDrop", patch.allowItemDrop());
		putIfNonNull(out, "allowItemPickup", patch.allowItemPickup());
		putIfNonNull(out, "preventFireSpread", patch.preventFireSpread());
		putIfNonNull(out, "denyLiquidFlow", patch.denyLiquidFlow());
		putIfNonNull(out, "denyPistons", patch.denyPistons());
		return out;
	}

	private static Map<String, Object> mapFromPermissionOverride(PermissionOverride po) {
		Map<String, Object> out = new LinkedHashMap<>();
		putIfNonNull(out, "canBuild", po.canBuild());
		putIfNonNull(out, "canMine", po.canMine());
		putIfNonNull(out, "buildHeight", po.buildHeight());
		putIfNonNull(out, "digDepth", po.digDepth());
		putIfNonNull(out, "allowInteract", po.allowInteract());
		putIfNonNull(out, "allowContainers", po.allowContainers());
		putIfNonNull(out, "allowUseEntities", po.allowUseEntities());
		putIfNonNull(out, "allowItemDrop", po.allowItemDrop());
		putIfNonNull(out, "allowItemPickup", po.allowItemPickup());
		return out;
	}

	private static void putIfNonNull(Map<String, Object> out, String key, Object value) {
		if (value != null) {
			out.put(key, value);
		}
	}
}

