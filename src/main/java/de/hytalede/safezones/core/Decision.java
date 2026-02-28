package de.hytalede.safezones.core;

import java.util.Objects;

public record Decision(boolean allowed, String reasonKey) {
	public static Decision allow() {
		return new Decision(true, "");
	}

	public static Decision deny(String reasonKey) {
		Objects.requireNonNull(reasonKey, "reasonKey must not be null");
		if (reasonKey.isBlank()) {
			throw new IllegalArgumentException("reasonKey must not be blank");
		}
		return new Decision(false, reasonKey);
	}
}
