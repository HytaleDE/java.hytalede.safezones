package de.hytalede.safezones.core;

/**
 * Full, effective zone settings (no nulls).
 *
 * <p>These are typically derived from global defaults + optional per-zone overrides.</p>
 */
public record ZoneSettings(
		boolean pvp,
		boolean takeDamage,
		boolean allowProjectiles,
		boolean allowExplosionDamage,
		boolean spawnMobs,
		boolean allowMobTargetPlayers,
		boolean denyMobEnter,
		boolean canBuild,
		boolean canMine,
		int groundY,
		int buildHeight,
		int digDepth,
		boolean allowInteract,
		boolean allowContainers,
		boolean allowUseEntities,
		boolean allowItemDrop,
		boolean allowItemPickup,
		boolean preventFireSpread,
		boolean denyLiquidFlow,
		boolean denyPistons
) {
	public ZoneSettings {
		if (buildHeight < 0) {
			throw new IllegalArgumentException("buildHeight must be >= 0");
		}
		if (digDepth < 0) {
			throw new IllegalArgumentException("digDepth must be >= 0");
		}
	}

	public static ZoneSettings defaults() {
		return new ZoneSettings(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				0,
				24,
				12,
				false,
				false,
				false,
				true,
				true,
				true,
				true,
				true
		);
	}
}
