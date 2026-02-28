package de.hytalede.safezones.core;

import java.util.Objects;

public record ChunkPos(int x, int z) {
	public ChunkPos {
		// no constraints
	}

	public static ChunkPos parseKey(String key) {
		Objects.requireNonNull(key, "key must not be null");
		String trimmed = key.trim();
		int comma = trimmed.indexOf(',');
		if (comma <= 0 || comma >= trimmed.length() - 1) {
			throw new IllegalArgumentException("Invalid chunk key (expected \"x,z\"): " + key);
		}
		int x = Integer.parseInt(trimmed.substring(0, comma).trim());
		int z = Integer.parseInt(trimmed.substring(comma + 1).trim());
		return new ChunkPos(x, z);
	}

	public String toKey() {
		return x + "," + z;
	}
}
