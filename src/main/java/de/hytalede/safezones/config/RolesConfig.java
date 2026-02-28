package de.hytalede.safezones.config;

import de.hytalede.safezones.core.PlayerNames;
import de.hytalede.safezones.core.Role;

import java.util.Set;

public record RolesConfig(
		Set<String> admins,
		Set<String> mods,
		Set<String> communityBuilders
) {
	public RolesConfig {
		admins = normalize(admins);
		mods = normalize(mods);
		communityBuilders = normalize(communityBuilders);
	}

	private static Set<String> normalize(Set<String> names) {
		if (names == null || names.isEmpty()) {
			return Set.of();
		}
		return names.stream().map(PlayerNames::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	public Role roleOf(String playerNameLower) {
		if (playerNameLower == null || playerNameLower.isBlank()) {
			return Role.PLAYER;
		}
		String n = PlayerNames.normalize(playerNameLower);
		if (admins.contains(n)) {
			return Role.ADMIN;
		}
		if (mods.contains(n)) {
			return Role.MOD;
		}
		if (communityBuilders.contains(n)) {
			return Role.COMMUNITY_BUILDER;
		}
		return Role.PLAYER;
	}
}
