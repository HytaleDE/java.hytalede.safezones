package de.hytalede.safezones.commands;

import java.util.Objects;

public record CommandResponse(boolean ok, String messageKey, String message) {
	public static CommandResponse ok(String messageKey, String message) {
		return new CommandResponse(true, messageKey, message);
	}

	public static CommandResponse error(String messageKey, String message) {
		return new CommandResponse(false, messageKey, message);
	}

	public CommandResponse {
		Objects.requireNonNull(messageKey, "messageKey must not be null");
		Objects.requireNonNull(message, "message must not be null");
	}
}

