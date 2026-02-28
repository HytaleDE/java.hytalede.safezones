package de.hytalede.safezones.config;

import de.hytalede.safezones.core.ZoneSettings;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record SafeZonesConfig(
		RolesConfig roles,
		ZoneSettings defaults,
		MessagesConfig messages,
		EventTitleConfig eventTitle,
		WaterPlacementConfig waterPlacement,
		int mobEnterCheckIntervalMs,
		double streetSpeedMultiplier,
		int streetSpeedApplyDelayMs,
		Set<String> houseBlockedItemIds,
		Set<String> mobEnterWhitelistNpcTypeIds,
		Map<String, Integer> placeDistanceByItemId,
		BenchRadiusConfig benchRadius,
		ClaimLimitsConfig claimLimits
) {
	public SafeZonesConfig {
		Objects.requireNonNull(roles, "roles must not be null");
		Objects.requireNonNull(defaults, "defaults must not be null");
		Objects.requireNonNull(messages, "messages must not be null");
		if (eventTitle == null) {
			eventTitle = EventTitleConfig.defaults();
		} else {
			eventTitle = eventTitle.normalized();
		}
		if (waterPlacement == null) {
			waterPlacement = WaterPlacementConfig.defaults();
		} else {
			waterPlacement = waterPlacement.normalized();
		}
		if (houseBlockedItemIds == null || houseBlockedItemIds.isEmpty()) {
			houseBlockedItemIds = Set.of();
		} else {
			houseBlockedItemIds = houseBlockedItemIds.stream()
					.filter(s -> s != null && !s.isBlank())
					.map(s -> s.trim().toLowerCase(java.util.Locale.ROOT))
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
		if (mobEnterWhitelistNpcTypeIds == null || mobEnterWhitelistNpcTypeIds.isEmpty()) {
			mobEnterWhitelistNpcTypeIds = Set.of();
		} else {
			mobEnterWhitelistNpcTypeIds = mobEnterWhitelistNpcTypeIds.stream()
					.filter(s -> s != null && !s.isBlank())
					.map(s -> s.trim().toLowerCase(java.util.Locale.ROOT))
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}

		if (placeDistanceByItemId == null || placeDistanceByItemId.isEmpty()) {
			placeDistanceByItemId = Map.of();
		} else {
			final java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
			for (Map.Entry<String, Integer> e : placeDistanceByItemId.entrySet()) {
				String key = e.getKey();
				Integer raw = e.getValue();
				if (key == null || key.isBlank() || raw == null) continue;
				String id = key.trim().toLowerCase(java.util.Locale.ROOT);
				int d = raw.intValue();
				if (d <= 0) continue;
				// Limit to max 4 as requested; larger values can break placement logic for large/rotated blocks.
				if (d > 4) d = 4;
				out.put(id, d);
			}
			placeDistanceByItemId = java.util.Collections.unmodifiableMap(out);
		}

		if (benchRadius != null) {
			benchRadius = benchRadius.normalizedOrNull();
		}

		if (claimLimits == null) {
			claimLimits = ClaimLimitsConfig.defaults();
		} else {
			claimLimits = claimLimits.normalized();
		}
	}

	public String message(Locale locale, String key) {
		Map<String, String> lang = messages.forLocale(locale);
		String value = lang.get(key);
		if (value != null) {
			return value;
		}
		value = messages.en().get(key);
		if (value != null) {
			return value;
		}
		return key;
	}

	public boolean isBlockedInHouse(String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return false;
		}
		return houseBlockedItemIds.contains(itemId.trim().toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * Whitelisted mobs may spawn inside SafeZones and are exempt from "denyMobEnter" repelling.
	 *
	 * <p>Uses NPC type id / role name (e.g. what {@code NPCEntity#getNPCTypeId()} returns).</p>
	 */
	public boolean isMobEnterWhitelisted(String npcTypeId) {
		if (npcTypeId == null || npcTypeId.isBlank()) {
			return false;
		}
		return mobEnterWhitelistNpcTypeIds.contains(npcTypeId.trim().toLowerCase(java.util.Locale.ROOT));
	}

	public Integer placeDistanceForItem(String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return null;
		}
		String id = itemId.trim().toLowerCase(java.util.Locale.ROOT);
		Integer v = placeDistanceByItemId.get(id);
		return v != null ? v : null;
	}

	/**
	 * Optional override for Hytale crafting bench container-search radius.
	 *
	 * <p>If null, SafeZones does not modify the server's default crafting config.</p>
	 */
	public record BenchRadiusConfig(Integer horizontal, Integer vertical, Integer limit) {
		public BenchRadiusConfig normalizedOrNull() {
			Integer h = horizontal;
			Integer v = vertical;
			Integer l = limit;
			if (h == null && v == null && l == null) {
				return null;
			}
			int hh = h != null ? h.intValue() : 7;
			int vv = v != null ? v.intValue() : 3;
			int ll = l != null ? l.intValue() : 100;
			if (hh < 0) hh = 0;
			if (hh > 7) hh = 7;
			if (vv < 0) vv = 0;
			if (vv > 7) vv = 7;
			if (ll < 0) ll = 0;
			if (ll > 200) ll = 200;
			return new BenchRadiusConfig(hh, vv, ll);
		}
	}

	/**
	 * Timing for the SafeZones "event title" (zone enter title).
	 *
	 * <p>Units are seconds.</p>
	 */
	public record EventTitleConfig(float fadeIn, float duration, float fadeOut) {
		public static final float DURATION_LONG = 99999.0f;

		public static EventTitleConfig defaults() {
			// Matches previous hardcoded timings.
			return new EventTitleConfig(0.2f, -1.0f, 0.2f).normalized();
		}

		public EventTitleConfig normalized() {
			float fi = Float.isFinite(fadeIn) ? fadeIn : 0.2f;
			float fo = Float.isFinite(fadeOut) ? fadeOut : 0.2f;
			float d = Float.isFinite(duration) ? duration : -1.0f;
			if (fi < 0.0f) fi = 0.0f;
			if (fo < 0.0f) fo = 0.0f;

			// -1 is treated as "instant/invalid" by the client; use a very large duration instead.
			if (d == -1.0f) {
				d = DURATION_LONG;
			} else if (d < 0.0f) {
				d = 0.0f;
			}
			return new EventTitleConfig(fi, d, fo);
		}
	}

	/**
	 * Rule for water placement inside SafeZones.
	 *
	 * <p>Offsets are relative to the claim groundY (claim point). Units are blocks.</p>
	 */
	public record WaterPlacementConfig(int minOffset, int maxOffset) {
		public static WaterPlacementConfig defaults() {
			// Matches the requested default: allow only between -3 and +2 (inclusive) for plot owners.
			return new WaterPlacementConfig(-3, 2).normalized();
		}

		public WaterPlacementConfig normalized() {
			int min = minOffset;
			int max = maxOffset;
			if (min > max) {
				int tmp = min;
				min = max;
				max = tmp;
			}
			// Keep within sane bounds.
			if (min < -512) min = -512;
			if (max > 512) max = 512;
			return new WaterPlacementConfig(min, max);
		}
	}

	/**
	 * Limits for how many HOUSE claims a player may own.
	 *
	 * <p>Rules:</p>
	 * <ul>
	 *   <li>{@code globalPerPlayer}: always applies (0 = unlimited)</li>
	 *   <li>{@code perZone}: optional per-zone override (0/absent = unlimited for that zone), but global still applies</li>
	 * </ul>
	 */
	public record ClaimLimitsConfig(int globalPerPlayer, Map<String, Integer> perZone) {
		public static ClaimLimitsConfig defaults() {
			return new ClaimLimitsConfig(0, Map.of());
		}

		public ClaimLimitsConfig normalized() {
			int g = globalPerPlayer;
			if (g < 0) g = 0;
			if (g > 100_000) g = 100_000;

			Map<String, Integer> pz;
			if (perZone == null || perZone.isEmpty()) {
				pz = Map.of();
			} else {
				java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
				for (Map.Entry<String, Integer> e : perZone.entrySet()) {
					String key = e.getKey();
					Integer raw = e.getValue();
					if (key == null || key.isBlank() || raw == null) continue;
					String id = key.trim().toLowerCase(java.util.Locale.ROOT);
					int v = raw.intValue();
					if (v <= 0) continue; // treat 0/neg as "unset"
					if (v > 100_000) v = 100_000;
					out.put(id, v);
				}
				pz = java.util.Collections.unmodifiableMap(out);
			}
			return new ClaimLimitsConfig(g, pz);
		}

		public Integer perZoneLimit(String zoneId) {
			if (zoneId == null || zoneId.isBlank()) return null;
			String id = zoneId.trim().toLowerCase(java.util.Locale.ROOT);
			Integer v = perZone.get(id);
			return v != null ? v : null;
		}
	}
}
