package de.hytalede.safezones.core;

/**
 * Optional per-player override for a specific chunk.
 *
 * <p>Null means \"not set\" and falls back to zone-level settings.</p>
 */
public record PermissionOverride(
		Boolean canBuild,
		Boolean canMine,
		Integer buildHeight,
		Integer digDepth,
		Boolean allowInteract,
		Boolean allowContainers,
		Boolean allowUseEntities,
		Boolean allowItemDrop,
		Boolean allowItemPickup
) {
	public PermissionOverride {
		if (buildHeight != null && buildHeight < 0) {
			throw new IllegalArgumentException("buildHeight must be >= 0");
		}
		if (digDepth != null && digDepth < 0) {
			throw new IllegalArgumentException("digDepth must be >= 0");
		}
	}

	public static PermissionOverride allowBuildAndMineUnlimited() {
		return new PermissionOverride(true, true, Integer.MAX_VALUE, Integer.MAX_VALUE, true, true, true, true, true);
	}
}
