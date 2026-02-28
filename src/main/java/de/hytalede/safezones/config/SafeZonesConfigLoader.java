package de.hytalede.safezones.config;

import de.hytalede.safezones.core.ZoneSettings;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SafeZonesConfigLoader {
	private SafeZonesConfigLoader() {
	}

	public static SafeZonesConfig load(Path configPath) throws IOException {
		if (configPath == null) {
			throw new IllegalArgumentException("configPath must not be null");
		}
		if (!Files.exists(configPath)) {
			throw new IllegalArgumentException("Config file does not exist: " + configPath);
		}

		try (InputStream inputStream = Files.newInputStream(configPath)) {
			ObjectMapper mapper = new ObjectMapper();
			Object raw = mapper.readValue(inputStream, Object.class);
			if (!(raw instanceof Map<?, ?> root)) {
				throw new IllegalArgumentException("Invalid JSON root (expected object)");
			}
			Map<String, Object> rootMap = JsonUtil.castStringObjectMap(root, "root");

			Map<String, Object> rolesMap = JsonUtil.getMap(rootMap, "roles", true);
			Set<String> admins = JsonUtil.getStringSet(rolesMap, "admins", false);
			Set<String> mods = JsonUtil.getStringSet(rolesMap, "mods", false);
			Set<String> communityBuilders = JsonUtil.getStringSet(rolesMap, "communityBuilders", false);
			RolesConfig roles = new RolesConfig(admins, mods, communityBuilders);

			Map<String, Object> defaultsMap = JsonUtil.getMap(rootMap, "defaults", false);
			ZoneSettings defaults = defaultsFromMap(defaultsMap);

			Map<String, Object> messages = JsonUtil.getMap(rootMap, "messages", true);
			Map<String, String> de = JsonUtil.getStringMap(messages, "de", true);
			Map<String, String> en = JsonUtil.getStringMap(messages, "en", true);
			MessagesConfig msg = new MessagesConfig(de, en);

			Map<String, Object> eventTitleMap = JsonUtil.getMap(rootMap, "eventTitle", false);
			float fadeIn = toFloatOrDefault(JsonUtil.getDoubleBoxed(eventTitleMap, "fadeIn"), 0.2f);
			float duration = toFloatOrDefault(JsonUtil.getDoubleBoxed(eventTitleMap, "duration"), -1.0f);
			float fadeOut = toFloatOrDefault(JsonUtil.getDoubleBoxed(eventTitleMap, "fadeOut"), 0.2f);
			SafeZonesConfig.EventTitleConfig eventTitle = new SafeZonesConfig.EventTitleConfig(fadeIn, duration, fadeOut).normalized();

			Map<String, Object> waterPlacementMap = JsonUtil.getMap(rootMap, "waterPlacement", false);
			Integer waterMinRaw = JsonUtil.getIntBoxed(waterPlacementMap, "minOffset");
			Integer waterMaxRaw = JsonUtil.getIntBoxed(waterPlacementMap, "maxOffset");
			int waterMin = waterMinRaw != null ? waterMinRaw.intValue() : -3;
			int waterMax = waterMaxRaw != null ? waterMaxRaw.intValue() : 2;
			SafeZonesConfig.WaterPlacementConfig waterPlacement = new SafeZonesConfig.WaterPlacementConfig(waterMin, waterMax).normalized();

			int mobEnterCheckIntervalMs = clampMobEnterInterval(JsonUtil.getIntBoxed(rootMap, "mobEnterCheckIntervalMs"));
			double streetSpeedMultiplier = clampStreetSpeedMultiplier(JsonUtil.getDoubleBoxed(rootMap, "streetSpeedMultiplier"));
			int streetSpeedApplyDelayMs = clampStreetSpeedApplyDelay(JsonUtil.getIntBoxed(rootMap, "streetSpeedApplyDelayMs"));
			Set<String> houseBlockedItemIds = JsonUtil.getStringSet(rootMap, "houseBlockedItemIds", false);
			Set<String> mobEnterWhitelistNpcTypeIds = JsonUtil.getStringSet(rootMap, "mobEnterWhitelistNpcTypeIds", false);
			Map<String, Integer> placeDistanceByItemId = parsePlaceDistance(rootMap);
			SafeZonesConfig.BenchRadiusConfig benchRadius = parseBenchRadius(rootMap);
			SafeZonesConfig.ClaimLimitsConfig claimLimits = parseClaimLimits(rootMap);
			SafeZonesConfig config = new SafeZonesConfig(roles, defaults, msg, eventTitle, waterPlacement, mobEnterCheckIntervalMs, streetSpeedMultiplier, streetSpeedApplyDelayMs, houseBlockedItemIds, mobEnterWhitelistNpcTypeIds, placeDistanceByItemId, benchRadius, claimLimits);
			validateMessages(config);
			return config;
		}
	}

	public static void save(Path configPath, SafeZonesConfig config) throws IOException {
		if (configPath == null) {
			throw new IllegalArgumentException("configPath must not be null");
		}
		if (config == null) {
			throw new IllegalArgumentException("config must not be null");
		}
		Files.createDirectories(configPath.getParent());

		// Keep a stable, human-friendly config format.
		Map<String, Object> root = new java.util.LinkedHashMap<>();
		root.put("_comment", "SafeZones config. Player names are stored as lowercase.");

		Map<String, Object> roles = new java.util.LinkedHashMap<>();
		roles.put("admins", config.roles().admins());
		roles.put("mods", config.roles().mods());
		roles.put("communityBuilders", config.roles().communityBuilders());
		root.put("roles", roles);

		// House item blacklist: empty means everything allowed.
		root.put("houseBlockedItemIds", config.houseBlockedItemIds());

		// Mob enter whitelist: empty means no exceptions.
		root.put("mobEnterWhitelistNpcTypeIds", config.mobEnterWhitelistNpcTypeIds());

		// Placement distance rules: empty means no special restrictions.
		java.util.ArrayList<java.util.Map<String, Object>> placeDistance = new java.util.ArrayList<>();
		for (Map.Entry<String, Integer> e : config.placeDistanceByItemId().entrySet()) {
			java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
			row.put("itemId", e.getKey());
			row.put("distance", e.getValue());
			placeDistance.add(row);
		}
		root.put("placeDistance", placeDistance);

		// Bench radius override (optional).
		java.util.LinkedHashMap<String, Object> benchRadius = new java.util.LinkedHashMap<>();
		SafeZonesConfig.BenchRadiusConfig br = config.benchRadius();
		if (br != null) {
			benchRadius.put("horizontal", br.horizontal());
			benchRadius.put("vertical", br.vertical());
			benchRadius.put("limit", br.limit());
		}
		root.put("benchRadius", benchRadius);

		// Claim limits (optional, but we still write defaults for stable config format).
		java.util.LinkedHashMap<String, Object> claimLimits = new java.util.LinkedHashMap<>();
		SafeZonesConfig.ClaimLimitsConfig cl = config.claimLimits();
		claimLimits.put("globalPerPlayer", cl != null ? cl.globalPerPlayer() : 0);
		java.util.LinkedHashMap<String, Object> perZone = new java.util.LinkedHashMap<>();
		if (cl != null && cl.perZone() != null && !cl.perZone().isEmpty()) {
			for (Map.Entry<String, Integer> e : cl.perZone().entrySet()) {
				perZone.put(e.getKey(), e.getValue());
			}
		}
		claimLimits.put("perZone", perZone);
		root.put("claimLimits", claimLimits);

		Map<String, Object> eventTitle = new java.util.LinkedHashMap<>();
		eventTitle.put("fadeIn", config.eventTitle().fadeIn());
		eventTitle.put("duration", config.eventTitle().duration());
		eventTitle.put("fadeOut", config.eventTitle().fadeOut());
		root.put("eventTitle", eventTitle);

		Map<String, Object> waterPlacement = new java.util.LinkedHashMap<>();
		waterPlacement.put("minOffset", config.waterPlacement().minOffset());
		waterPlacement.put("maxOffset", config.waterPlacement().maxOffset());
		root.put("waterPlacement", waterPlacement);

		Map<String, Object> defaults = new java.util.LinkedHashMap<>();
		defaults.put("pvp", config.defaults().pvp());
		defaults.put("takeDamage", config.defaults().takeDamage());
		defaults.put("allowProjectiles", config.defaults().allowProjectiles());
		defaults.put("allowExplosionDamage", config.defaults().allowExplosionDamage());
		defaults.put("spawnMobs", config.defaults().spawnMobs());
		defaults.put("allowMobTargetPlayers", config.defaults().allowMobTargetPlayers());
		defaults.put("denyMobEnter", config.defaults().denyMobEnter());
		defaults.put("canBuild", config.defaults().canBuild());
		defaults.put("canMine", config.defaults().canMine());
		defaults.put("groundY", config.defaults().groundY());
		defaults.put("uniformGroundY", config.defaults().uniformGroundY());
		defaults.put("buildHeight", config.defaults().buildHeight());
		defaults.put("digDepth", config.defaults().digDepth());
		defaults.put("allowInteract", config.defaults().allowInteract());
		defaults.put("allowContainers", config.defaults().allowContainers());
		defaults.put("allowUseEntities", config.defaults().allowUseEntities());
		defaults.put("allowItemDrop", config.defaults().allowItemDrop());
		defaults.put("allowItemPickup", config.defaults().allowItemPickup());
		defaults.put("preventFireSpread", config.defaults().preventFireSpread());
		defaults.put("denyLiquidFlow", config.defaults().denyLiquidFlow());
		defaults.put("denyPistons", config.defaults().denyPistons());
		root.put("defaults", defaults);

		root.put("mobEnterCheckIntervalMs", config.mobEnterCheckIntervalMs());
		root.put("streetSpeedMultiplier", config.streetSpeedMultiplier());
		root.put("streetSpeedApplyDelayMs", config.streetSpeedApplyDelayMs());

		Map<String, Object> messages = new java.util.LinkedHashMap<>();
		messages.put("de", config.messages().de());
		messages.put("en", config.messages().en());
		root.put("messages", messages);

		ObjectMapper mapper = new ObjectMapper();
		mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), root);
	}

	private static Map<String, Integer> parsePlaceDistance(Map<String, Object> rootMap) {
		Object value = rootMap.get("placeDistance");
		if (value == null) {
			return Map.of();
		}
		if (!(value instanceof java.util.List<?> list)) {
			throw new IllegalArgumentException("Key 'placeDistance' must be an array");
		}
		java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
		for (Object o : list) {
			if (!(o instanceof Map<?, ?> m)) {
				throw new IllegalArgumentException("Key 'placeDistance' must contain objects");
			}
			Map<String, Object> row = JsonUtil.castStringObjectMap(m, "placeDistance[]");
			String itemId = null;
			Object rawItem = row.get("itemId");
			if (rawItem instanceof String s) {
				itemId = s;
			}
			if (itemId == null || itemId.isBlank()) {
				continue;
			}
			Integer dist = JsonUtil.getIntBoxed(row, "distance");
			if (dist == null) {
				continue;
			}
			out.put(itemId.trim().toLowerCase(java.util.Locale.ROOT), dist.intValue());
		}
		return java.util.Collections.unmodifiableMap(out);
	}

	private static SafeZonesConfig.BenchRadiusConfig parseBenchRadius(Map<String, Object> rootMap) {
		Map<String, Object> map = JsonUtil.getMap(rootMap, "benchRadius", false);
		if (map == null || map.isEmpty()) {
			return null;
		}
		Integer h = JsonUtil.getIntBoxed(map, "horizontal");
		Integer v = JsonUtil.getIntBoxed(map, "vertical");
		Integer l = JsonUtil.getIntBoxed(map, "limit");
		return new SafeZonesConfig.BenchRadiusConfig(h, v, l).normalizedOrNull();
	}

	private static SafeZonesConfig.ClaimLimitsConfig parseClaimLimits(Map<String, Object> rootMap) {
		Map<String, Object> map = JsonUtil.getMap(rootMap, "claimLimits", false);
		if (map == null || map.isEmpty()) {
			return SafeZonesConfig.ClaimLimitsConfig.defaults();
		}
		Integer global = JsonUtil.getIntBoxed(map, "globalPerPlayer");
		int g = global != null ? global.intValue() : 0;
		Map<String, Integer> perZoneOut = Map.of();
		Map<String, Object> perZone = JsonUtil.getMap(map, "perZone", false);
		if (perZone != null && !perZone.isEmpty()) {
			java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
			for (Map.Entry<String, Object> e : perZone.entrySet()) {
				String key = e.getKey();
				Object v = e.getValue();
				if (key == null || key.isBlank()) continue;
				if (v instanceof Integer i) {
					out.put(key.trim().toLowerCase(java.util.Locale.ROOT), i.intValue());
				} else if (v instanceof Number n) {
					out.put(key.trim().toLowerCase(java.util.Locale.ROOT), n.intValue());
				} else if (v instanceof String s) {
					try {
						out.put(key.trim().toLowerCase(java.util.Locale.ROOT), Integer.parseInt(s.trim()));
					} catch (Exception ignored) {
					}
				}
			}
			perZoneOut = java.util.Collections.unmodifiableMap(out);
		}
		return new SafeZonesConfig.ClaimLimitsConfig(g, perZoneOut).normalized();
	}

	private static void validateMessages(SafeZonesConfig config) {
		requireMessageKey(config, Locale.ENGLISH, "noPermission");
		requireMessageKey(config, Locale.ENGLISH, "actionDenied");
		requireMessageKey(config, Locale.ENGLISH, "zoneNotFound");
	}

	private static void requireMessageKey(SafeZonesConfig config, Locale locale, String key) {
		String value = config.message(locale, key);
		if (value == null || value.isBlank() || value.equals(key)) {
			throw new IllegalArgumentException("Missing message key: " + key);
		}
	}

	private static ZoneSettings defaultsFromMap(Map<String, Object> map) {
		ZoneSettings base = ZoneSettings.defaults();
		if (map == null || map.isEmpty()) {
			return base;
		}
		return new de.hytalede.safezones.core.ZoneSettingsPatch(
				JsonUtil.getBooleanBoxed(map, "pvp"),
				JsonUtil.getBooleanBoxed(map, "takeDamage"),
				JsonUtil.getBooleanBoxed(map, "allowProjectiles"),
				JsonUtil.getBooleanBoxed(map, "allowExplosionDamage"),
				JsonUtil.getBooleanBoxed(map, "spawnMobs"),
				JsonUtil.getBooleanBoxed(map, "allowMobTargetPlayers"),
				JsonUtil.getBooleanBoxed(map, "denyMobEnter"),
				JsonUtil.getBooleanBoxed(map, "canBuild"),
				JsonUtil.getBooleanBoxed(map, "canMine"),
				JsonUtil.getIntBoxed(map, "groundY"),
				JsonUtil.getIntBoxed(map, "uniformGroundY"),
				JsonUtil.getIntBoxed(map, "buildHeight"),
				JsonUtil.getIntBoxed(map, "digDepth"),
				JsonUtil.getBooleanBoxed(map, "allowInteract"),
				JsonUtil.getBooleanBoxed(map, "allowContainers"),
				JsonUtil.getBooleanBoxed(map, "allowUseEntities"),
				JsonUtil.getBooleanBoxed(map, "allowItemDrop"),
				JsonUtil.getBooleanBoxed(map, "allowItemPickup"),
				JsonUtil.getBooleanBoxed(map, "preventFireSpread"),
				JsonUtil.getBooleanBoxed(map, "denyLiquidFlow"),
				JsonUtil.getBooleanBoxed(map, "denyPistons")
		).applyOn(base);
	}

	private static int clampMobEnterInterval(Integer rawMs) {
		int value = rawMs != null ? rawMs.intValue() : 2500;
		if (value < 500) {
			return 500;
		}
		if (value > 10_000) {
			return 10_000;
		}
		return value;
	}

	private static double clampStreetSpeedMultiplier(Double raw) {
		double value = raw != null ? raw.doubleValue() : 2.0d;
		if (!Double.isFinite(value)) {
			return 2.0d;
		}
		// Keep within sane bounds to avoid client/server issues.
		if (value < 0.1d) {
			return 0.1d;
		}
		if (value > 10.0d) {
			return 10.0d;
		}
		return value;
	}

	private static int clampStreetSpeedApplyDelay(Integer rawMs) {
		int value = rawMs != null ? rawMs.intValue() : 5000;
		if (value < 0) {
			return 0;
		}
		if (value > 60_000) {
			return 60_000;
		}
		return value;
	}

	private static float toFloatOrDefault(Double raw, float def) {
		if (raw == null) return def;
		double d = raw.doubleValue();
		if (!Double.isFinite(d)) return def;
		return (float) d;
	}
}

