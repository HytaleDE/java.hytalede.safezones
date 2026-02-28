package de.hytalede.safezones.hytale.systems;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.builtin.crafting.state.BenchState;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.matrix.Matrix4d;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows a debug box representing the effective "bench material container search" volume (horizontal/vertical)
 * for the currently opened bench (CraftingManager's active bench).
 *
 * Toggle via: /safezone show bench
 */
public final class SafeZonesShowBenchSystems {
	private SafeZonesShowBenchSystems() {}

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
		// Keep the shape effectively "persistent" by giving it a long lifetime and refreshing occasionally.
		private static final long INTERVAL_NANOS = 1_500_000_000L; // 1.5s
		private static final float SHAPE_LIFETIME_SECONDS = 4.0f;
		private static final DebugShape SHAPE = DebugShape.Cube;
		private static final Vector3f COLOR = new Vector3f(0.1f, 0.8f, 0.95f); // cyan-ish

		private final SafeZonesHytalePlugin plugin;
		private final ConcurrentHashMap<UUID, Long> lastRenderAt = new ConcurrentHashMap<>();
		private final BenchAccessor benchAccessor = new BenchAccessor();

		private Tick(SafeZonesHytalePlugin plugin) {
			this.plugin = plugin;
		}

		@Override
		public void tick(float dt, int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
			PlayerRef pr = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
			Player player = archetypeChunk.getComponent(index, Player.getComponentType());
			TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
			if (pr == null || pr.getUuid() == null || player == null || transform == null) {
				return;
			}
			if (!plugin.isShowEnabledFor(pr.getUuid()) || !plugin.canUseShow(player) || !plugin.isShowBenchEnabledFor(pr.getUuid())) {
				return;
			}

			long now = System.nanoTime();
			long last = lastRenderAt.getOrDefault(pr.getUuid(), 0L);
			if (now - last < INTERVAL_NANOS) {
				return;
			}
			lastRenderAt.put(pr.getUuid(), now);

			var world = store.getExternalData().getWorld();
			if (world == null) {
				return;
			}

			Vector3i benchPos = resolveBenchPosition(archetypeChunk, index, store, transform);
			if (benchPos == null) {
				return;
			}
			BlockState bs = world.getState(benchPos.x, benchPos.y, benchPos.z, true);
			if (!(bs instanceof BenchState benchState)) {
				return;
			}

			int h = world.getGameplayConfig().getCraftingConfig().getBenchMaterialHorizontalChestSearchRadius();
			int v = world.getGameplayConfig().getCraftingConfig().getBenchMaterialVerticalChestSearchRadius();

			// Visualize the configured radii directly (h/v). We intentionally do NOT add the internal
			// "extraSearchRadius" here, because that makes radius=0 look like "+1 block".
			Vector3d blockPos = benchState.getBlockPosition().toVector3d();
			BlockBoundingBoxes hitboxAsset = BlockBoundingBoxes.getAssetMap().getAsset(benchState.getBlockType().getHitboxTypeIndex());
			BlockBoundingBoxes.RotatedVariantBoxes rotatedHitbox = hitboxAsset.get(benchState.getRotationIndex());
			Box boundingBox = rotatedHitbox.getBoundingBox();
			double horizontalRadius = h;
			double verticalRadius = v;

			double minX = blockPos.x + boundingBox.min.x - horizontalRadius;
			double minY = blockPos.y + boundingBox.min.y - verticalRadius;
			double minZ = blockPos.z + boundingBox.min.z - horizontalRadius;
			double maxX = blockPos.x + boundingBox.max.x + horizontalRadius;
			double maxY = blockPos.y + boundingBox.max.y + verticalRadius;
			double maxZ = blockPos.z + boundingBox.max.z + horizontalRadius;

			double centerX = (minX + maxX) * 0.5;
			double centerY = (minY + maxY) * 0.5;
			double centerZ = (minZ + maxZ) * 0.5;
			double scaleX = Math.max(0.01, maxX - minX);
			double scaleY = Math.max(0.01, maxY - minY);
			double scaleZ = Math.max(0.01, maxZ - minZ);

			Matrix4d matrix = new Matrix4d();
			matrix.identity();
			matrix.translate(new Vector3d(centerX, clampY(centerY), centerZ));
			matrix.scale(scaleX, scaleY, scaleZ);

			DisplayDebug packet = createDisplayDebugPacket(
					SHAPE,
					matrix.asFloatData(),
					COLOR,
					SHAPE_LIFETIME_SECONDS,
					true,
					null
			);
			if (packet != null) {
				pr.getPacketHandler().writeNoCache(packet);
			}
		}

		private Vector3i resolveBenchPosition(ArchetypeChunk<EntityStore> archetypeChunk, int index, Store<EntityStore> store, TransformComponent transform) {
			// 1) Prefer the currently opened bench (CraftingManager).
			try {
				Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
				if (ref != null && ref.isValid()) {
					CraftingManager cm = store.getComponent(ref, CraftingManager.getComponentType());
					if (cm != null && cm.hasBenchSet()) {
						Vector3i pos = benchAccessor.getBenchPosition(cm);
						if (pos != null) return pos;
					}
				}
			} catch (Throwable ignored) {
			}

			// 2) Fallback: scan a small cube around the player for any BenchState.
			// This is helpful if the bench UI isn't open yet but the user still wants a visualization nearby.
			try {
				Vector3d p = transform.getPosition();
				int px = (int) Math.floor(p.getX());
				int py = (int) Math.floor(p.getY());
				int pz = (int) Math.floor(p.getZ());
				int r = 6; // keep small to avoid heavy scans
				Vector3i best = null;
				double bestDist2 = Double.POSITIVE_INFINITY;
				var world = store.getExternalData().getWorld();
				for (int x = px - r; x <= px + r; x++) {
					for (int y = Math.max(1, py - r); y <= py + r; y++) {
						for (int z = pz - r; z <= pz + r; z++) {
							BlockState s = world.getState(x, y, z, true);
							if (!(s instanceof BenchState)) continue;
							double dx = (x + 0.5) - (p.getX());
							double dy = (y + 0.5) - (p.getY());
							double dz = (z + 0.5) - (p.getZ());
							double d2 = dx * dx + dy * dy + dz * dz;
							if (d2 < bestDist2) {
								bestDist2 = d2;
								best = new Vector3i(x, y, z);
							}
						}
					}
				}
				return best;
			} catch (Throwable ignored) {
				return null;
			}
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
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
	}

	private static final class BenchAccessor {
		private final Field xField;
		private final Field yField;
		private final Field zField;

		private BenchAccessor() {
			Field x = null, y = null, z = null;
			try {
				x = CraftingManager.class.getDeclaredField("x");
				y = CraftingManager.class.getDeclaredField("y");
				z = CraftingManager.class.getDeclaredField("z");
				x.setAccessible(true);
				y.setAccessible(true);
				z.setAccessible(true);
			} catch (Exception ignored) {
			}
			this.xField = x;
			this.yField = y;
			this.zField = z;
		}

		private Vector3i getBenchPosition(CraftingManager manager) {
			if (manager == null || xField == null || yField == null || zField == null) {
				return null;
			}
			try {
				return new Vector3i(xField.getInt(manager), yField.getInt(manager), zField.getInt(manager));
			} catch (Exception ignored) {
				return null;
			}
		}
	}
}

