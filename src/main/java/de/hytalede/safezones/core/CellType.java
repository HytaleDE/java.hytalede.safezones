package de.hytalede.safezones.core;

import java.util.Locale;

/**
 * Cell type for the 16x16 SafeZones grid.
 *
 * <p>Stored per cell (ChunkPos in 16x16 coordinates).</p>
 */
public enum CellType {
	NONE,
	FREE,
	HOUSE,
	STREET,
	WALL,
	GATE,
	SPAWN,
	MARKET;

	public static CellType parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return NONE;
		}
		String v = raw.trim().toLowerCase(Locale.ROOT);
		return switch (v) {
			case "none", "clear" -> NONE;
			case "free" -> FREE;
			case "house" -> HOUSE;
			case "street" -> STREET;
			case "wall" -> WALL;
			case "gate" -> GATE;
			case "spawn" -> SPAWN;
			case "market" -> MARKET;
			default -> throw new IllegalArgumentException("Unknown cell type: " + raw);
		};
	}
}

