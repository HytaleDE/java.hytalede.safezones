package de.hytalede.safezones.core;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ZoneEngine {
	public static final String REASON_NO_ZONE = "noZone";
	public static final String REASON_DENIED = "actionDenied";
	public static final String REASON_DENIED_BUILD = "denyBuild";
	public static final String REASON_DENIED_MINE = "denyMine";
	public static final String REASON_DENIED_INTERACT = "denyInteract";
	public static final String REASON_DENIED_CONTAINER = "denyContainer";
	public static final String REASON_DENIED_ENTITY_USE = "denyUseEntity";
	public static final String REASON_DENIED_DROP = "denyItemDrop";
	public static final String REASON_DENIED_PICKUP = "denyItemPickup";
	public static final String REASON_DENIED_PVP = "denyPvp";
	public static final String REASON_DENIED_DAMAGE = "denyDamage";
	public static final String REASON_DENIED_PROJECTILE = "denyProjectiles";
	public static final String REASON_DENIED_EXPLOSION = "denyExplosions";
	public static final String REASON_DENIED_MOB_TARGET = "denyMobTarget";
	public static final String REASON_DENIED_MOB_SPAWN = "denyMobSpawn";
	public static final String REASON_DENIED_FIRE = "denyFireSpread";
	public static final String REASON_DENIED_LIQUID = "denyLiquidFlow";
	public static final String REASON_DENIED_PISTON = "denyPistons";
	public static final String REASON_DENIED_HEIGHT = "denyHeightLimit";

	public Decision decide(SafeZonesSnapshot snapshot, ZoneActionRequest request) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Objects.requireNonNull(request, "request must not be null");

		return switch (request.type()) {
			case PISTON_MOVE -> decidePiston(snapshot, request);
			case LIQUID_FLOW -> decideLiquid(snapshot, request);
			default -> decideSingleChunk(snapshot, request);
		};
	}

	private Decision decideSingleChunk(SafeZonesSnapshot snapshot, ZoneActionRequest request) {
		Optional<SafeZone> zoneOpt = snapshot.findZoneFor(request.chunk());
		if (zoneOpt.isEmpty()) {
			return Decision.allow();
		}

		SafeZone zone = zoneOpt.get();
		ZoneSettings settings = zone.settings();

		return switch (request.type()) {
			// Combat/Damage
			case DAMAGE_ANY -> settings.takeDamage() ? Decision.allow() : Decision.deny(REASON_DENIED_DAMAGE);
			case DAMAGE_PVP -> {
				if (!settings.takeDamage()) {
					yield Decision.deny(REASON_DENIED_DAMAGE);
				}
				yield settings.pvp() ? Decision.allow() : Decision.deny(REASON_DENIED_PVP);
			}
			case DAMAGE_PROJECTILE -> {
				if (!settings.takeDamage()) {
					yield Decision.deny(REASON_DENIED_DAMAGE);
				}
				yield settings.allowProjectiles() ? Decision.allow() : Decision.deny(REASON_DENIED_PROJECTILE);
			}
			case DAMAGE_EXPLOSION -> {
				if (!settings.takeDamage()) {
					yield Decision.deny(REASON_DENIED_DAMAGE);
				}
				yield settings.allowExplosionDamage() ? Decision.allow() : Decision.deny(REASON_DENIED_EXPLOSION);
			}

			// Mobs
			case MOB_TARGET_PLAYER -> settings.allowMobTargetPlayers() ? Decision.allow() : Decision.deny(REASON_DENIED_MOB_TARGET);
			case MOB_SPAWN -> settings.spawnMobs() ? Decision.allow() : Decision.deny(REASON_DENIED_MOB_SPAWN);

			// Build/Mine
			case BLOCK_BREAK -> decideBlockBreak(snapshot, request, settings);
			case BLOCK_PLACE -> decideBlockPlace(snapshot, request, settings);

			// Interact/Inventory
			case INTERACT -> decideBlockInteract(snapshot, request, settings, InteractKind.INTERACT, REASON_DENIED_INTERACT);
			case CONTAINER_OPEN -> decideBlockInteract(snapshot, request, settings, InteractKind.CONTAINERS, REASON_DENIED_CONTAINER);
			case USE_ENTITY -> decideBlockInteract(snapshot, request, settings, InteractKind.USE_ENTITIES, REASON_DENIED_ENTITY_USE);
			case ITEM_DROP -> decideSimplePerm(snapshot, request, settings, EffectivePermissions::allowItemDrop, REASON_DENIED_DROP);
			case ITEM_PICKUP -> decideSimplePerm(snapshot, request, settings, EffectivePermissions::allowItemPickup, REASON_DENIED_PICKUP);

			// World/Physics
			case FIRE_SPREAD -> settings.preventFireSpread() ? Decision.deny(REASON_DENIED_FIRE) : Decision.allow();
			default -> Decision.deny(REASON_DENIED);
		};
	}

	private Decision decidePiston(SafeZonesSnapshot snapshot, ZoneActionRequest request) {
		ChunkPos from = request.fromChunk();
		ChunkPos to = request.toChunk();
		if (from == null || to == null) {
			throw new IllegalArgumentException("PISTON_MOVE requires fromChunk and toChunk");
		}

		ZoneSettings fromSettings = snapshot.findZoneFor(from).map(SafeZone::settings).orElse(null);
		ZoneSettings toSettings = snapshot.findZoneFor(to).map(SafeZone::settings).orElse(null);
		boolean denied = (fromSettings != null && fromSettings.denyPistons()) || (toSettings != null && toSettings.denyPistons());
		return denied ? Decision.deny(REASON_DENIED_PISTON) : Decision.allow();
	}

	private Decision decideLiquid(SafeZonesSnapshot snapshot, ZoneActionRequest request) {
		ChunkPos from = request.fromChunk();
		ChunkPos to = request.toChunk();
		if (from == null || to == null) {
			throw new IllegalArgumentException("LIQUID_FLOW requires fromChunk and toChunk");
		}

		ZoneSettings fromSettings = snapshot.findZoneFor(from).map(SafeZone::settings).orElse(null);
		ZoneSettings toSettings = snapshot.findZoneFor(to).map(SafeZone::settings).orElse(null);
		boolean denied = (fromSettings != null && fromSettings.denyLiquidFlow()) || (toSettings != null && toSettings.denyLiquidFlow());
		return denied ? Decision.deny(REASON_DENIED_LIQUID) : Decision.allow();
	}

	private Decision decideBlockBreak(SafeZonesSnapshot snapshot, ZoneActionRequest request, ZoneSettings settings) {
		EffectivePermissions perms = effectivePermissions(snapshot, request, settings);
		if (!perms.canMine()) {
			return Decision.deny(REASON_DENIED_MINE);
		}
		Integer y = request.y();
		// digDepth == 0 means "no depth limit" (defaults should not block mining/placing).
		if (y != null && !perms.unlimitedHeight() && perms.digDepth() > 0) {
			int groundY = effectiveGroundY(snapshot, request, settings);
			int minY = groundY - perms.digDepth();
			if (y < minY) {
				return Decision.deny(REASON_DENIED_HEIGHT);
			}
		}
		return Decision.allow();
	}

	private Decision decideBlockPlace(SafeZonesSnapshot snapshot, ZoneActionRequest request, ZoneSettings settings) {
		EffectivePermissions perms = effectivePermissions(snapshot, request, settings);
		if (!perms.canBuild()) {
			return Decision.deny(REASON_DENIED_BUILD);
		}
		Integer y = request.y();
		// buildHeight == 0 means "no height limit" (defaults should not block mining/placing).
		if (y != null && !perms.unlimitedHeight() && perms.buildHeight() > 0) {
			int groundY = effectiveGroundY(snapshot, request, settings);
			int maxY = groundY + perms.buildHeight();
			if (y > maxY) {
				return Decision.deny(REASON_DENIED_HEIGHT);
			}
		}
		return Decision.allow();
	}

	private static int effectiveGroundY(SafeZonesSnapshot snapshot, ZoneActionRequest request, ZoneSettings settings) {
		ChunkOverride ov = snapshot.chunkOverrides().get(request.chunk());
		if (ov != null && ov.groundY() != null) {
			return ov.groundY();
		}
		return settings.groundY();
	}

	private interface PermExtractor {
		boolean get(EffectivePermissions perms);
	}

	private enum InteractKind {
		INTERACT,
		CONTAINERS,
		USE_ENTITIES
	}

	private Decision decideSimplePerm(
			SafeZonesSnapshot snapshot,
			ZoneActionRequest request,
			ZoneSettings settings,
			PermExtractor extractor,
			String denyReason
	) {
		if (request.actorNameLower() == null) {
			return Decision.deny(denyReason);
		}
		EffectivePermissions perms = effectivePermissions(snapshot, request, settings);
		return extractor.get(perms) ? Decision.allow() : Decision.deny(denyReason);
	}

	/**
	 * Block interactions are only restricted in PLAYER_OWNER-claimed cells.
	 *
	 * <p>In all other cells (unclaimed/community/free/roads/etc.), interactions are allowed.</p>
	 */
	private Decision decideBlockInteract(
			SafeZonesSnapshot snapshot,
			ZoneActionRequest request,
			ZoneSettings settings,
			InteractKind kind,
			String denyReason
	) {
		ChunkOverride ov = snapshot.chunkOverrideOrNone(request.chunk());
		// Only restrict in player-owned claims.
		if (ov.claimType() != ClaimType.PLAYER_OWNER) {
			return Decision.allow();
		}

		// Staff override (useful for moderation/admin work).
		Role role = request.actorRole();
		if (role == Role.ADMIN || role == Role.MOD) {
			return Decision.allow();
		}

		String actor = request.actorNameLower();
		if (actor == null) {
			return Decision.deny(denyReason);
		}

		// Owner can always interact in their own claim.
		if (ov.owner() != null && actor.equalsIgnoreCase(ov.owner())) {
			return Decision.allow();
		}

		// Everyone else must have an explicit trusted override for this action.
		PermissionOverride po = findPlayerOverrideIgnoreCase(ov.playerOverrides(), actor);
		if (po == null) {
			return Decision.deny(denyReason);
		}
		boolean allowed = switch (kind) {
			case INTERACT -> Boolean.TRUE.equals(po.allowInteract());
			case CONTAINERS -> Boolean.TRUE.equals(po.allowContainers());
			case USE_ENTITIES -> Boolean.TRUE.equals(po.allowUseEntities());
		};
		return allowed ? Decision.allow() : Decision.deny(denyReason);
	}

	private static PermissionOverride findPlayerOverrideIgnoreCase(Map<String, PermissionOverride> overrides, String actorLowerOrRaw) {
		if (overrides == null || overrides.isEmpty() || actorLowerOrRaw == null) {
			return null;
		}
		PermissionOverride po = overrides.get(actorLowerOrRaw);
		if (po != null) {
			return po;
		}
		for (Map.Entry<String, PermissionOverride> entry : overrides.entrySet()) {
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(actorLowerOrRaw)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private EffectivePermissions effectivePermissions(SafeZonesSnapshot snapshot, ZoneActionRequest request, ZoneSettings settings) {
		String actor = request.actorNameLower();
		Role role = request.actorRole();
		ChunkOverride override = snapshot.chunkOverrideOrNone(request.chunk());

		// CommunityBuilder in community chunk: unlimited building/mining (and allow interactions).
		if (role == Role.COMMUNITY_BUILDER && override.claimType() == ClaimType.COMMUNITY) {
			return EffectivePermissions.unlimitedAll();
		}

		EffectivePermissions base = EffectivePermissions.fromZone(settings);

		// Owner gets at least build+mine (limits from zone unless overridden).
		if (actor != null && override.claimType() == ClaimType.PLAYER_OWNER && actor.equalsIgnoreCase(override.owner())) {
			base = base.withCanBuild(true).withCanMine(true);
		}

		// Per-player override
		if (actor != null) {
			PermissionOverride po = findPlayerOverrideIgnoreCase(override.playerOverrides(), actor);
			if (po != null) {
				base = base.apply(po);
			}
		}

		// Admin/Mod: allow all actions (including unlimited height).
		// This prevents staff/OPs from being blocked by default groundY-based build limits (e.g. in STREET cells).
		if (role == Role.ADMIN || role == Role.MOD) {
			return EffectivePermissions.unlimitedAll();
		}

		return base;
	}
}

