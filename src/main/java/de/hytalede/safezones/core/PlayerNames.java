package de.hytalede.safezones.core;

import java.util.Locale;

public final class PlayerNames {
	private PlayerNames() {
	}

	public static String normalize(String playerName) {
		if (playerName == null) {
			throw new IllegalArgumentException("playerName must not be null");
		}
		String trimmed = playerName.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("playerName must not be blank");
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}
}

