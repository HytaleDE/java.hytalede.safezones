package de.hytalede.safezones.core;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Per-chunk claim + per-player permissions.
 *
 * <p>Player keys must be lowercase.</p>
 */
public record ChunkOverride(
		CellType type,
		ClaimType claimType,
		String owner, // lowercase, only for PLAYER_OWNER
		Integer groundY, // reference ground Y for height limits (nullable)
		Map<String, PermissionOverride> playerOverrides
) {
	public ChunkOverride {
		if (type == null) {
			type = CellType.NONE;
		}
		Objects.requireNonNull(claimType, "claimType must not be null");
		if (claimType == ClaimType.PLAYER_OWNER) {
			if (owner == null || owner.isBlank()) {
				throw new IllegalArgumentException("owner must be set for PLAYER_OWNER");
			}
		}
		if (claimType != ClaimType.PLAYER_OWNER && owner != null && !owner.isBlank()) {
			throw new IllegalArgumentException("owner must be null/blank unless claimType is PLAYER_OWNER");
		}
		if (playerOverrides == null) {
			playerOverrides = Map.of();
		}
		playerOverrides = Collections.unmodifiableMap(playerOverrides);
	}

	public static ChunkOverride none() {
		return new ChunkOverride(CellType.NONE, ClaimType.NONE, null, null, Map.of());
	}
}
