package de.hytalede.safezones.hytale.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.matrix.Matrix4d;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.core.ZoneRect;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically sends debug shapes (F7-style) along SafeZone outer borders,
 * visible only to players that toggled /safezone show.
 */
public final class SafeZonesShowBordersSystems {
	private SafeZonesShowBordersSystems() {
	}

	public static void register(SafeZonesHytalePlugin plugin) {
		Objects.requireNonNull(plugin, "plugin");
		plugin.getEntityStoreRegistry().registerSystem(new Tick(plugin));
	}

	private static final Query<EntityStore> QUERY = Archetype.of(
			Player.getComponentType(),
			PlayerRef.getComponentType(),
			TransformComponent.getComponentType()
	);

	private static final class Tick extends EntityTickingSystem<EntityStore> {
		private static final long INTERVAL_NANOS = 600_000_000L; // ~0.6s
		private static final int CELL_SIZE = 16;
		private static final int ZONE_SCAN_RADIUS_CELLS = 24; // 24*16 = 384 blocks
		private static final double RENDER_RADIUS_BLOCKS = 128.0;
		private static final float SHAPE_LIFETIME_SECONDS = 0.8f;
		private static final double EDGE_SCALE_THICK = 0.22;
		private static final double EDGE_SCALE_Y = 4.5;
		private static final int BORDER_RGB = 0x000000; // black
		private static final Vector3f BORDER_COLOR = rgbToVec3f(BORDER_RGB);
		private static final DebugShape BORDER_SHAPE = DebugShape.Cube;

		private final SafeZonesHytalePlugin plugin;
		private final ConcurrentHashMap<UUID, Long> lastRenderAt = new ConcurrentHashMap<>();

		private Tick(SafeZonesHytalePlugin plugin) {
			this.plugin = plugin;
		}

		@Override
		public void tick(float dt, int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
			PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
			if (playerRef == null) {
				return;
			}
			Player player = archetypeChunk.getComponent(index, Player.getComponentType());
			if (player == null) {
				return;
			}
			if (!plugin.isShowEnabledFor(playerRef.getUuid()) || !plugin.canUseShow(player) || !plugin.isShowBorderEnabledFor(playerRef.getUuid())) {
				return;
			}

			long now = System.nanoTime();
			long last = lastRenderAt.getOrDefault(playerRef.getUuid(), 0L);
			if (now - last < INTERVAL_NANOS) {
				return;
			}
			lastRenderAt.put(playerRef.getUuid(), now);

			TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
			if (transform == null) {
				return;
			}

			Vector3d pos = transform.getPosition();
			int playerBlockX = (int) Math.floor(pos.getX());
			int playerBlockZ = (int) Math.floor(pos.getZ());
			int playerChunkX = Math.floorDiv(playerBlockX, CELL_SIZE);
			int playerChunkZ = Math.floorDiv(playerBlockZ, CELL_SIZE);
			double y = clampY(pos.getY() + 1.0);

			SafeZonesSnapshot snapshot = plugin.getSnapshot();
			if (snapshot.zones().isEmpty()) {
				return;
			}

			for (SafeZone zone : snapshot.zones()) {
				ZoneRect rect = zone.rect();
				if (!isNear(playerChunkX, playerChunkZ, rect, ZONE_SCAN_RADIUS_CELLS)) {
					continue;
				}
				renderZoneBorderNearPlayer(playerRef, playerBlockX, playerBlockZ, y, rect, 1);
			}
		}

		private static void renderZoneBorderNearPlayer(PlayerRef onlyPlayer, int playerBlockX, int playerBlockZ, double y, ZoneRect rect, int stepCells) {
			int step = Math.max(1, stepCells);
			// Chunk boundary coordinates in block space.
			int minChunkX = rect.minX();
			int maxChunkX = rect.maxX() + 1;
			int minChunkZ = rect.minZ();
			int maxChunkZ = rect.maxZ() + 1;

			// Only iterate the portion of the perimeter that could possibly be within render radius.
			int minBlockX = (int) Math.floor(playerBlockX - RENDER_RADIUS_BLOCKS) - CELL_SIZE;
			int maxBlockX = (int) Math.floor(playerBlockX + RENDER_RADIUS_BLOCKS) + CELL_SIZE;
			int minBlockZ = (int) Math.floor(playerBlockZ - RENDER_RADIUS_BLOCKS) - CELL_SIZE;
			int maxBlockZ = (int) Math.floor(playerBlockZ + RENDER_RADIUS_BLOCKS) + CELL_SIZE;

			int cxStart = clamp(Math.floorDiv(minBlockX, CELL_SIZE), minChunkX, maxChunkX);
			int cxEnd = clamp(Math.floorDiv(maxBlockX, CELL_SIZE), minChunkX, maxChunkX);
			int czStart = clamp(Math.floorDiv(minBlockZ, CELL_SIZE), minChunkZ, maxChunkZ);
			int czEnd = clamp(Math.floorDiv(maxBlockZ, CELL_SIZE), minChunkZ, maxChunkZ);

			double segmentLength = step * (double) CELL_SIZE;
			// Top and bottom edges (iterate segments along X, but only near the player)
			int cxEndSegment = cxEnd - step;
			for (int cx = cxStart; cx <= cxEndSegment; cx += step) {
				double x = cx * (double) CELL_SIZE + segmentLength * 0.5;
				int zTop = minChunkZ * CELL_SIZE;
				int zBottom = maxChunkZ * CELL_SIZE;
				spawnBorderMarker(onlyPlayer, playerBlockX, playerBlockZ, x, y, zTop + 0.5, segmentLength, EDGE_SCALE_Y, EDGE_SCALE_THICK);
				spawnBorderMarker(onlyPlayer, playerBlockX, playerBlockZ, x, y, zBottom + 0.5, segmentLength, EDGE_SCALE_Y, EDGE_SCALE_THICK);
			}

			// Left and right edges (iterate segments along Z near the player, skipping already-done corners)
			int czStartSegment = Math.max(minChunkZ + 1, czStart);
			int czEndSegment = Math.min(maxChunkZ - 1, czEnd) - step;
			for (int cz = czStartSegment; cz <= czEndSegment; cz += step) {
				double z = cz * (double) CELL_SIZE + segmentLength * 0.5;
				int xLeft = minChunkX * CELL_SIZE;
				int xRight = maxChunkX * CELL_SIZE;
				spawnBorderMarker(onlyPlayer, playerBlockX, playerBlockZ, xLeft + 0.5, y, z, EDGE_SCALE_THICK, EDGE_SCALE_Y, segmentLength);
				spawnBorderMarker(onlyPlayer, playerBlockX, playerBlockZ, xRight + 0.5, y, z, EDGE_SCALE_THICK, EDGE_SCALE_Y, segmentLength);
			}

			// Corners: spawn two segments (one per edge direction).
			double xLeft = minChunkX * (double) CELL_SIZE + 0.5;
			double xRight = maxChunkX * (double) CELL_SIZE + 0.5;
			double zTop = minChunkZ * (double) CELL_SIZE + 0.5;
			double zBottom = maxChunkZ * (double) CELL_SIZE + 0.5;
			spawnCornerSegments(onlyPlayer, playerBlockX, playerBlockZ, xLeft, zTop, segmentLength, y);
			spawnCornerSegments(onlyPlayer, playerBlockX, playerBlockZ, xRight, zTop, segmentLength, y);
			spawnCornerSegments(onlyPlayer, playerBlockX, playerBlockZ, xLeft, zBottom, segmentLength, y);
			spawnCornerSegments(onlyPlayer, playerBlockX, playerBlockZ, xRight, zBottom, segmentLength, y);
		}

		private static int clamp(int v, int min, int max) {
			if (v < min) return min;
			if (v > max) return max;
			return v;
		}

		private static void spawnBorderMarker(PlayerRef onlyPlayer, int playerBlockX, int playerBlockZ, double centerX, double y, double centerZ,
		                                      double scaleX, double scaleY, double scaleZ) {
			// Put the marker slightly above ground for visibility.
			if (!isWithin(playerBlockX, playerBlockZ, centerX, centerZ, RENDER_RADIUS_BLOCKS)) {
				return;
			}

			Matrix4d matrix = new Matrix4d();
			matrix.identity();
			matrix.translate(new Vector3d(centerX, clampY(y), centerZ));
			matrix.scale(scaleX, scaleY, scaleZ);

			DisplayDebug packet = createDisplayDebugPacket(
					BORDER_SHAPE,
					matrix.asFloatData(),
					BORDER_COLOR,
					SHAPE_LIFETIME_SECONDS,
					true,
					null
			);
			if (packet != null) {
				onlyPlayer.getPacketHandler().writeNoCache(packet);
			}
		}

		private static void spawnCornerSegments(PlayerRef onlyPlayer, int playerBlockX, int playerBlockZ,
		                                        double cornerX, double cornerZ, double segmentLength, double y) {
			// Segment along X (horizontal)
			spawnBorderMarker(onlyPlayer, playerBlockX, playerBlockZ,
					cornerX + segmentLength * 0.5, y, cornerZ,
					segmentLength, EDGE_SCALE_Y, EDGE_SCALE_THICK);
			// Segment along Z (vertical)
			spawnBorderMarker(onlyPlayer, playerBlockX, playerBlockZ,
					cornerX, y, cornerZ + segmentLength * 0.5,
					EDGE_SCALE_THICK, EDGE_SCALE_Y, segmentLength);
		}

		private static boolean isNear(int cx, int cz, ZoneRect rect, int radiusChunks) {
			int dx = 0;
			if (cx < rect.minX()) dx = rect.minX() - cx;
			else if (cx > rect.maxX()) dx = cx - rect.maxX();
			int dz = 0;
			if (cz < rect.minZ()) dz = rect.minZ() - cz;
			else if (cz > rect.maxZ()) dz = cz - rect.maxZ();
			return Math.max(dx, dz) <= radiusChunks;
		}

		private static boolean isWithin(int px, int pz, double x, double z, double radius) {
			double dx = x - (px + 0.5);
			double dz = z - (pz + 0.5);
			return dx * dx + dz * dz <= radius * radius;
		}

		private static Vector3f rgbToVec3f(int rgb) {
			float r = (float) ((rgb >> 16) & 255) / 255.0f;
			float g = (float) ((rgb >> 8) & 255) / 255.0f;
			float b = (float) (rgb & 255) / 255.0f;
			return new Vector3f(r, g, b);
		}

		private static double clampY(double y) {
			if (y < 1.0) return 1.0;
			if (y > 319.0) return 319.0;
			return y;
		}

		/**
		 * Runtime-compatible DisplayDebug creation.
		 *
		 * <p>We avoid direct constructor linkage because Hytale updates may change ctor signatures
		 * between protocol versions (causing NoSuchMethodError).</p>
		 */
		private static DisplayDebug createDisplayDebugPacket(
				DebugShape shape,
				float[] matrix,
				Vector3f color,
				float time,
				boolean fade,
				float[] frustumProjection
		) {
			try {
				DisplayDebug packet = new DisplayDebug();
				packet.shape = shape;
				packet.matrix = matrix;
				packet.color = color;
				packet.time = time;
				packet.fade = fade;
				packet.frustumProjection = frustumProjection;
				return packet;
			} catch (Throwable ignored) {
				return null;
			}
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
		}
	}
}

