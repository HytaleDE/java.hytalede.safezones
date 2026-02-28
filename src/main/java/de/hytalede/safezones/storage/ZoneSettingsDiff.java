package de.hytalede.safezones.storage;

import de.hytalede.safezones.core.ZoneSettings;
import de.hytalede.safezones.core.ZoneSettingsPatch;

import java.util.Objects;

final class ZoneSettingsDiff {
	private ZoneSettingsDiff() {
	}

	static ZoneSettingsPatch diff(ZoneSettings defaults, ZoneSettings effective) {
		Objects.requireNonNull(defaults, "defaults must not be null");
		Objects.requireNonNull(effective, "effective must not be null");

		return new ZoneSettingsPatch(
				effective.pvp() != defaults.pvp() ? effective.pvp() : null,
				effective.takeDamage() != defaults.takeDamage() ? effective.takeDamage() : null,
				effective.allowProjectiles() != defaults.allowProjectiles() ? effective.allowProjectiles() : null,
				effective.allowExplosionDamage() != defaults.allowExplosionDamage() ? effective.allowExplosionDamage() : null,
				effective.spawnMobs() != defaults.spawnMobs() ? effective.spawnMobs() : null,
				effective.allowMobTargetPlayers() != defaults.allowMobTargetPlayers() ? effective.allowMobTargetPlayers() : null,
				effective.denyMobEnter() != defaults.denyMobEnter() ? effective.denyMobEnter() : null,
				effective.canBuild() != defaults.canBuild() ? effective.canBuild() : null,
				effective.canMine() != defaults.canMine() ? effective.canMine() : null,
				effective.groundY() != defaults.groundY() ? effective.groundY() : null,
				effective.buildHeight() != defaults.buildHeight() ? effective.buildHeight() : null,
				effective.digDepth() != defaults.digDepth() ? effective.digDepth() : null,
				effective.allowInteract() != defaults.allowInteract() ? effective.allowInteract() : null,
				effective.allowContainers() != defaults.allowContainers() ? effective.allowContainers() : null,
				effective.allowUseEntities() != defaults.allowUseEntities() ? effective.allowUseEntities() : null,
				effective.allowItemDrop() != defaults.allowItemDrop() ? effective.allowItemDrop() : null,
				effective.allowItemPickup() != defaults.allowItemPickup() ? effective.allowItemPickup() : null,
				effective.preventFireSpread() != defaults.preventFireSpread() ? effective.preventFireSpread() : null,
				effective.denyLiquidFlow() != defaults.denyLiquidFlow() ? effective.denyLiquidFlow() : null,
				effective.denyPistons() != defaults.denyPistons() ? effective.denyPistons() : null
		);
	}
}

