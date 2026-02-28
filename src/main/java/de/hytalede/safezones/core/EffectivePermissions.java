package de.hytalede.safezones.core;

/**
 * Effective permissions for a player in a specific chunk/zone.
 */
public record EffectivePermissions(
		boolean canBuild,
		boolean canMine,
		int buildHeight,
		int digDepth,
		boolean allowInteract,
		boolean allowContainers,
		boolean allowUseEntities,
		boolean allowItemDrop,
		boolean allowItemPickup,
		boolean unlimitedHeight
) {
	public static EffectivePermissions fromZone(ZoneSettings settings) {
		return new EffectivePermissions(
				settings.canBuild(),
				settings.canMine(),
				settings.buildHeight(),
				settings.digDepth(),
				settings.allowInteract(),
				settings.allowContainers(),
				settings.allowUseEntities(),
				settings.allowItemDrop(),
				settings.allowItemPickup(),
				false
		);
	}

	public static EffectivePermissions unlimitedAll() {
		return new EffectivePermissions(
				true,
				true,
				Integer.MAX_VALUE,
				Integer.MAX_VALUE,
				true,
				true,
				true,
				true,
				true,
				true
		);
	}

	public EffectivePermissions withCanBuild(boolean value) {
		return new EffectivePermissions(value, canMine, buildHeight, digDepth, allowInteract, allowContainers, allowUseEntities, allowItemDrop, allowItemPickup, unlimitedHeight);
	}

	public EffectivePermissions withCanMine(boolean value) {
		return new EffectivePermissions(canBuild, value, buildHeight, digDepth, allowInteract, allowContainers, allowUseEntities, allowItemDrop, allowItemPickup, unlimitedHeight);
	}

	public EffectivePermissions apply(PermissionOverride po) {
		boolean newCanBuild = po.canBuild() != null ? po.canBuild() : canBuild;
		boolean newCanMine = po.canMine() != null ? po.canMine() : canMine;
		int newBuildHeight = po.buildHeight() != null ? po.buildHeight() : buildHeight;
		int newDigDepth = po.digDepth() != null ? po.digDepth() : digDepth;
		boolean newAllowInteract = po.allowInteract() != null ? po.allowInteract() : allowInteract;
		boolean newAllowContainers = po.allowContainers() != null ? po.allowContainers() : allowContainers;
		boolean newAllowUseEntities = po.allowUseEntities() != null ? po.allowUseEntities() : allowUseEntities;
		boolean newAllowItemDrop = po.allowItemDrop() != null ? po.allowItemDrop() : allowItemDrop;
		boolean newAllowItemPickup = po.allowItemPickup() != null ? po.allowItemPickup() : allowItemPickup;
		boolean newUnlimitedHeight = unlimitedHeight
				|| (po.buildHeight() != null && po.buildHeight() == Integer.MAX_VALUE)
				|| (po.digDepth() != null && po.digDepth() == Integer.MAX_VALUE);

		return new EffectivePermissions(
				newCanBuild,
				newCanMine,
				newBuildHeight,
				newDigDepth,
				newAllowInteract,
				newAllowContainers,
				newAllowUseEntities,
				newAllowItemDrop,
				newAllowItemPickup,
				newUnlimitedHeight
		);
	}
}

