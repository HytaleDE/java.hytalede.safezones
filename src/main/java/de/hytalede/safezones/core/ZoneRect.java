package de.hytalede.safezones.core;

import java.util.Objects;

public record ZoneRect(int minX, int maxX, int minZ, int maxZ) {
	public ZoneRect {
		if (minX > maxX) {
			throw new IllegalArgumentException("minX must be <= maxX");
		}
		if (minZ > maxZ) {
			throw new IllegalArgumentException("minZ must be <= maxZ");
		}
	}

	public static ZoneRect fromCorners(ChunkPos a, ChunkPos b) {
		Objects.requireNonNull(a, "a must not be null");
		Objects.requireNonNull(b, "b must not be null");
		int minX = Math.min(a.x(), b.x());
		int maxX = Math.max(a.x(), b.x());
		int minZ = Math.min(a.z(), b.z());
		int maxZ = Math.max(a.z(), b.z());
		return new ZoneRect(minX, maxX, minZ, maxZ);
	}

	public boolean contains(ChunkPos chunk) {
		return chunk.x() >= minX && chunk.x() <= maxX && chunk.z() >= minZ && chunk.z() <= maxZ;
	}

	public int areaChunks() {
		long dx = (long) maxX - minX + 1;
		long dz = (long) maxZ - minZ + 1;
		long area = dx * dz;
		if (area > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return (int) area;
	}
}
