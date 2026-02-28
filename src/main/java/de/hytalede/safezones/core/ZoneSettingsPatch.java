package de.hytalede.safezones.core;

/**
 * Optional zone settings overrides (nullable boxed fields).
 */
public record ZoneSettingsPatch(
		Boolean pvp,
		Boolean takeDamage,
		Boolean allowProjectiles,
		Boolean allowExplosionDamage,
		Boolean spawnMobs,
		Boolean allowMobTargetPlayers,
		Boolean denyMobEnter,
		Boolean canBuild,
		Boolean canMine,
		Integer groundY,
		/** If non-null and non-zero, use this Y for all claims in the zone. null/0 = dynamic. */
		Integer uniformGroundY,
		Integer buildHeight,
		Integer digDepth,
		Boolean allowInteract,
		Boolean allowContainers,
		Boolean allowUseEntities,
		Boolean allowItemDrop,
		Boolean allowItemPickup,
		Boolean preventFireSpread,
		Boolean denyLiquidFlow,
		Boolean denyPistons
) {
	public ZoneSettings applyOn(ZoneSettings base) {
		if (base == null) {
			throw new IllegalArgumentException("base must not be null");
		}
		return new ZoneSettings(
				pvp != null ? pvp : base.pvp(),
				takeDamage != null ? takeDamage : base.takeDamage(),
				allowProjectiles != null ? allowProjectiles : base.allowProjectiles(),
				allowExplosionDamage != null ? allowExplosionDamage : base.allowExplosionDamage(),
				spawnMobs != null ? spawnMobs : base.spawnMobs(),
				allowMobTargetPlayers != null ? allowMobTargetPlayers : base.allowMobTargetPlayers(),
				denyMobEnter != null ? denyMobEnter : base.denyMobEnter(),
				canBuild != null ? canBuild : base.canBuild(),
				canMine != null ? canMine : base.canMine(),
				groundY != null ? groundY : base.groundY(),
				uniformGroundY != null ? uniformGroundY : base.uniformGroundY(),
				buildHeight != null ? buildHeight : base.buildHeight(),
				digDepth != null ? digDepth : base.digDepth(),
				allowInteract != null ? allowInteract : base.allowInteract(),
				allowContainers != null ? allowContainers : base.allowContainers(),
				allowUseEntities != null ? allowUseEntities : base.allowUseEntities(),
				allowItemDrop != null ? allowItemDrop : base.allowItemDrop(),
				allowItemPickup != null ? allowItemPickup : base.allowItemPickup(),
				preventFireSpread != null ? preventFireSpread : base.preventFireSpread(),
				denyLiquidFlow != null ? denyLiquidFlow : base.denyLiquidFlow(),
				denyPistons != null ? denyPistons : base.denyPistons()
		);
	}
}

