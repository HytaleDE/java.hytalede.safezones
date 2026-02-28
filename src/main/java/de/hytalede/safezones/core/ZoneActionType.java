package de.hytalede.safezones.core;

public enum ZoneActionType {
	// Combat/Damage
	DAMAGE_ANY,
	DAMAGE_PVP,
	DAMAGE_PROJECTILE,
	DAMAGE_EXPLOSION,

	// Mobs
	MOB_TARGET_PLAYER,
	MOB_SPAWN,

	// Build/Mine
	BLOCK_BREAK,
	BLOCK_PLACE,

	// Interact/Inventory
	INTERACT,
	CONTAINER_OPEN,
	USE_ENTITY,
	ITEM_DROP,
	ITEM_PICKUP,

	// World/Physics
	FIRE_SPREAD,
	LIQUID_FLOW,
	PISTON_MOVE
}

