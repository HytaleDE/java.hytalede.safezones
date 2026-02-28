package de.hytalede.safezones.commands;

import de.hytalede.safezones.core.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory command selection store (per player).
 */
public final class SelectionStore {
	private final Map<String, ChunkPos> startChunks = new ConcurrentHashMap<>();
	private final Map<String, String> editingZoneIds = new ConcurrentHashMap<>();

	public void setStartChunk(String playerLower, ChunkPos chunk) {
		startChunks.put(playerLower, chunk);
	}

	public ChunkPos getStartChunk(String playerLower) {
		return startChunks.get(playerLower);
	}

	public void clearStartChunk(String playerLower) {
		startChunks.remove(playerLower);
	}

	public void setEditingZoneId(String playerLower, String zoneId) {
		if (zoneId == null || zoneId.isBlank()) {
			editingZoneIds.remove(playerLower);
			return;
		}
		editingZoneIds.put(playerLower, zoneId);
	}

	public String getEditingZoneId(String playerLower) {
		return editingZoneIds.get(playerLower);
	}

	public void clearEditingZoneId(String playerLower) {
		editingZoneIds.remove(playerLower);
	}
}

