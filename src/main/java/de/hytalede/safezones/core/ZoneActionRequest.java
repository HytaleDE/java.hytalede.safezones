package de.hytalede.safezones.core;

import java.util.Objects;

/**
 * Adapter-agnostic description of an action to evaluate against SafeZones.
 *
 * <p>For environment actions (fire/lava/pistons), {@code actorNameLower} may be null.</p>
 */
public record ZoneActionRequest(
		ZoneActionType type,
		String actorNameLower,
		Role actorRole,
		ChunkPos chunk,
		Integer y,
		ChunkPos fromChunk,
		ChunkPos toChunk
) {
	public ZoneActionRequest {
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(chunk, "chunk must not be null");
		if (actorNameLower != null && actorNameLower.isBlank()) {
			throw new IllegalArgumentException("actorNameLower must not be blank");
		}
		if (actorRole == null) {
			actorRole = Role.PLAYER;
		}
	}

	public static ZoneActionRequest playerAction(ZoneActionType type, String actorNameLower, Role actorRole, ChunkPos chunk, Integer y) {
		return new ZoneActionRequest(type, Objects.requireNonNull(actorNameLower), actorRole, chunk, y, null, null);
	}

	public static ZoneActionRequest environmentMove(ZoneActionType type, ChunkPos fromChunk, ChunkPos toChunk) {
		Objects.requireNonNull(fromChunk, "fromChunk must not be null");
		Objects.requireNonNull(toChunk, "toChunk must not be null");
		return new ZoneActionRequest(type, null, Role.PLAYER, fromChunk, null, fromChunk, toChunk);
	}
}

