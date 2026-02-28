package de.hytalede.safezones.commands;

import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.PlayerNames;
import de.hytalede.safezones.core.Role;

import java.util.Locale;
import java.util.Objects;

public record CommandContext(
		String senderNameLower,
		Role senderRole,
		ChunkPos currentChunk,
		int currentY,
		Locale locale
) {
	public CommandContext {
		Objects.requireNonNull(senderNameLower, "senderNameLower must not be null");
		senderNameLower = PlayerNames.normalize(senderNameLower);
		Objects.requireNonNull(senderRole, "senderRole must not be null");
		Objects.requireNonNull(currentChunk, "currentChunk must not be null");
		if (locale == null) {
			locale = Locale.ENGLISH;
		}
	}
}

