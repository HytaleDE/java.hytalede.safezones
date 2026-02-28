package de.hytalede.safezones.hytale.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interaction.CancelInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.builtin.crafting.state.BenchState;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockFace;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.fluid.FluidTicker;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportSystems;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.modules.interaction.system.InteractionSystems;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.BlockStateModule;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.RoleSystems;
import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.core.CellType;
import de.hytalede.safezones.core.ClaimType;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.Decision;
import de.hytalede.safezones.core.PlayerNames;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.core.ZoneActionRequest;
import de.hytalede.safezones.core.Role;
import de.hytalede.safezones.core.ZoneActionType;
import de.hytalede.safezones.core.ZoneEngine;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.ZoneRect;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;
import com.hypixel.hytale.component.spatial.SpatialResource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;

public final class SafeZonesProtectionSystems {
	private SafeZonesProtectionSystems() {
	}

	// SafeZones grid resolution: 16x16 cells (a 32x32 Hytale chunk is split into 2x2 cells).
	private static final int CELL_SIZE = 16;
	private static final int WORLD_CHUNK_SIZE_BLOCKS = 32;
	private static final int PLAYER_NEARBY_CHUNKS = 4; // if nobody is close, don't hard-teleport mobs

	// When a player interacts with a portal/teleporter block in a HOUSE cell, we allow staff to open the UI,
	// but must still block any resulting teleport/instance transfer. We mark the interaction and intercept Teleport.
	private static final ConcurrentHashMap<UUID, Long> recentPortalOrTeleporterInteractMillis = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, Long> lastPortalBlockMessageMillis = new ConcurrentHashMap<>();
	private static final long PORTAL_TELEPORT_BLOCK_WINDOW_MS = 3000L;
	private static final long PORTAL_BLOCK_MESSAGE_COOLDOWN_MS = 1500L;

	// Bucket/fluid placement often uses PlaceFluidInteraction and bypasses PlaceBlockEvent.
	// We cancel the interaction packet chain inside SafeZones before it executes.
	private static final ConcurrentHashMap<UUID, Long> lastFluidBucketMessageMillis = new ConcurrentHashMap<>();
	private static final long FLUID_BUCKET_MESSAGE_COOLDOWN_MS = 1500L;

	public static void markPortalOrTeleporterInteract(UUID uuid) {
		if (uuid == null) return;
		recentPortalOrTeleporterInteractMillis.put(uuid, System.currentTimeMillis());
	}

	public static void register(SafeZonesHytalePlugin plugin) {
		plugin.getEntityStoreRegistry().registerSystem(new FluidBucketInteractionPacketGuardSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new BreakBlockProtectionSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new PlaceBlockProtectionSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new UseBlockProtectionSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new CraftRecipeProtectionSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new DropItemProtectionSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new PickupItemProtectionSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new DamageProtectionSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new MobSpawnProtectionSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new MobTargetProtectionSystem(plugin));
		plugin.getEntityStoreRegistry().registerSystem(new PortalHouseTeleportBlockSystem(plugin));
	}

	/**
	 * Buckets/fluids are placed by {@code PlaceFluidInteraction} which writes directly into {@code FluidSection}
	 * and bypasses {@link PlaceBlockEvent}. We therefore cancel the incoming interaction chain packets before
	 * the {@link com.hypixel.hytale.server.core.entity.InteractionManager} processes them.
	 */
	private static final class FluidBucketInteractionPacketGuardSystem extends com.hypixel.hytale.component.system.tick.EntityTickingSystem<EntityStore> {
		private static final Query<EntityStore> QUERY = Query.and(
				Player.getComponentType(),
				PlayerRef.getComponentType(),
				TransformComponent.getComponentType()
		);
		private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
				new SystemDependency<>(Order.BEFORE, InteractionSystems.TickInteractionManagerSystem.class)
		);

		private final SafeZonesHytalePlugin plugin;

		private FluidBucketInteractionPacketGuardSystem(SafeZonesHytalePlugin plugin) {
			this.plugin = plugin;
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
		}

		@Override
		public Set<Dependency<EntityStore>> getDependencies() {
			return DEPENDENCIES;
		}

		@Override
		public void tick(float dt, int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
			try {
				PlayerRef pr = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
				Player player = archetypeChunk.getComponent(index, Player.getComponentType());
				if (pr == null || player == null || pr.getUuid() == null) {
					return;
				}

				if (!(pr.getPacketHandler() instanceof GamePacketHandler gph)) {
					return;
				}
				Deque<SyncInteractionChain> q = gph.getInteractionPacketQueue();
				if (q == null || q.isEmpty()) {
					return;
				}

				var world = store.getExternalData().getWorld();
				if (world == null) {
					return;
				}

				SafeZonesSnapshot snapshot = plugin.getSnapshot();
				if (snapshot == null || snapshot.zones().isEmpty()) {
					return;
				}

				java.util.Iterator<SyncInteractionChain> it = q.iterator();
				while (it.hasNext()) {
					SyncInteractionChain pkt = it.next();
					if (pkt == null) continue;

					InteractionSyncDataWithTarget sync = extractTarget(pkt);
					if (sync == null || sync.blockPos == null) {
						continue;
					}
					// Avoid impacting unrelated chains like pure input/hotbar changes.
					// PlaceFluid is typically a Secondary/Use interaction and always targets a block.
					if (pkt.interactionType == InteractionType.Primary) {
						continue;
					}

					String hitDetail = null;
					try {
						hitDetail = pkt.data != null ? pkt.data.hitDetail : null;
					} catch (Throwable ignored) {
					}

					String itemId = null;
					try {
						var inv = player.getInventory();
						ItemStack inHand = inv != null ? inv.getItemInHand() : null;
						itemId = inHand != null ? inHand.getItemId() : null;
					} catch (Throwable ignored) {
					}

					// Only guard actual PlaceFluid actions (otherwise we can break unrelated actions like hotbar switching).
					if (!isPlaceFluidAction(pkt, sync, itemId, hitDetail)) {
						continue;
					}

					// Mimic PlaceFluidInteraction targeting: if the clicked block is solid, offset by clicked face.
					Vector3i target = new Vector3i(sync.blockPos.x, sync.blockPos.y, sync.blockPos.z);
					try {
						BlockType bt = world.getBlockType(target);
						if (FluidTicker.isSolid(bt)) {
							BlockFace face = BlockFace.fromProtocolFace(sync.blockFace);
							if (face != null) {
								target = target.clone();
								target.add(face.getDirection());
							}
						}
					} catch (Throwable ignored) {
					}

					ChunkPos cell = chunkFromBlock(target);
					if (snapshot.findZoneFor(cell).isEmpty()) {
						continue;
					}

					// Enforce strict water policy in SafeZones (do NOT apply generic BLOCK_PLACE rules here).
					// If this is PlaceFluid, we only care about water/lava policy.
					Decision d = decideWaterPlacement(plugin, snapshot, player, cell, target.getY(), itemId, hitDetail);
					if (d.allowed()) {
						continue;
					}

					// Cancel chain + remove queued packet so core never applies the fluid.
					try {
						gph.writeNoCache(new CancelInteractionChain(pkt.chainId, pkt.forkedId));
					} catch (Throwable ignored) {
					}
					// Important: remove via iterator to avoid concurrent modification / desync issues.
					try {
						it.remove();
					} catch (Throwable ignored) {
						// fallback (best-effort)
						try {
							q.remove(pkt);
						} catch (Throwable ignored2) {
						}
					}

					long now = System.currentTimeMillis();
					Long last = lastFluidBucketMessageMillis.get(pr.getUuid());
					if (last == null || now - last > FLUID_BUCKET_MESSAGE_COOLDOWN_MS) {
						lastFluidBucketMessageMillis.put(pr.getUuid(), now);
						plugin.denyWithMessage(player, d, pr.getLanguage());
					}
				}
			} catch (Throwable ignored) {
			}
		}

		private static boolean isPlaceFluidAction(SyncInteractionChain pkt, InteractionSyncDataWithTarget sync, String itemId, String hitDetail) {
			try {
				String detail = hitDetail != null ? hitDetail.toLowerCase(java.util.Locale.ROOT) : "";
				// Most reliable: hitDetail includes PlaceFluid / FluidToPlace / Water_Source / Lava_Source.
				if (!detail.isBlank()) {
					if (detail.contains("placefluid") || detail.contains("fluidtoplace") || detail.contains("water_source") || detail.contains("lava_source")) {
						return true;
					}
				}
				// No reliable hitDetail → don't touch the interaction chain. This avoids breaking unrelated
				// interactions (e.g. opening containers while holding a bucket).
				return false;
			} catch (Throwable ignored) {
				return false;
			}
		}

		private static InteractionSyncDataWithTarget extractTarget(SyncInteractionChain pkt) {
			try {
				BlockPosition bp = pkt.data != null ? pkt.data.blockPosition : null;
				com.hypixel.hytale.protocol.BlockFace bf = com.hypixel.hytale.protocol.BlockFace.None;

				if (pkt.interactionData != null) {
					for (com.hypixel.hytale.protocol.InteractionSyncData d : pkt.interactionData) {
						if (d == null) continue;
						if (bp == null && d.blockPosition != null) {
							bp = d.blockPosition;
						}
						if (d.blockFace != null) {
							bf = d.blockFace;
						}
					}
				}
				if (bp == null) {
					return null;
				}
				return new InteractionSyncDataWithTarget(bp, bf);
			} catch (Throwable ignored) {
				return null;
			}
		}

		private record InteractionSyncDataWithTarget(BlockPosition blockPos, com.hypixel.hytale.protocol.BlockFace blockFace) {
		}
	}

	private static Decision decideWaterPlacement(
			SafeZonesHytalePlugin plugin,
			SafeZonesSnapshot snapshot,
			Player player,
			ChunkPos cell,
			Integer y,
			String itemId,
			String hitDetail
	) {
		try {
			if (snapshot == null || cell == null) {
				return Decision.allow();
			}
			if (snapshot.findZoneFor(cell).isEmpty()) {
				return Decision.allow();
			}

			boolean water = isWaterAction(itemId, hitDetail);
			boolean lava = isLavaAction(itemId, hitDetail);
			if (!water && !lava) {
				return Decision.allow();
			}

			// Permission/OP bypass: allow staff/admin tools to place fluids in SafeZones.
			// This also ensures OP ("*") can place fluids again.
			if (player != null) {
				try {
					if (player.hasPermission(SafeZonesHytalePlugin.PERM_PLACE_FLUID) || player.hasPermission("*")) {
						return Decision.allow();
					}
				} catch (Throwable ignored) {
				}
			}
			if (lava) {
				// Never allow lava placement in SafeZones.
				return Decision.deny(ZoneEngine.REASON_DENIED_BUILD);
			}

			// Water: only allow plot owners within configured Y band relative to claim groundY.
			ChunkOverride ov = snapshot.chunkOverrideOrNone(cell);
			if (ov == null || ov.claimType() != ClaimType.PLAYER_OWNER || ov.owner() == null || ov.owner().isBlank()) {
				return Decision.deny(ZoneEngine.REASON_DENIED_BUILD);
			}
			String actorLower = PlayerNames.normalize(player != null ? player.getDisplayName() : null);
			if (actorLower == null || !actorLower.equalsIgnoreCase(ov.owner())) {
				return Decision.deny(ZoneEngine.REASON_DENIED_BUILD);
			}
			if (y == null) {
				return Decision.deny(ZoneEngine.REASON_DENIED_HEIGHT);
			}

			SafeZonesConfig cfg = plugin != null ? plugin.getConfig() : null;
			SafeZonesConfig.WaterPlacementConfig wp = cfg != null ? cfg.waterPlacement() : SafeZonesConfig.WaterPlacementConfig.defaults();

			int groundY = resolveGroundY(snapshot, cell, ov);
			int minY = groundY + wp.minOffset();
			int maxY = groundY + wp.maxOffset();
			int yy = y.intValue();
			if (yy < minY || yy > maxY) {
				return Decision.deny(ZoneEngine.REASON_DENIED_HEIGHT);
			}
			return Decision.allow();
		} catch (Throwable ignored) {
			return Decision.allow();
		}
	}

	private static int resolveGroundY(SafeZonesSnapshot snapshot, ChunkPos cell, ChunkOverride ov) {
		try {
			if (ov != null && ov.groundY() != null) {
				return ov.groundY();
			}
			return snapshot.findZoneFor(cell).map(z -> z.settings().groundY()).orElse(0);
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private static boolean isWaterAction(String itemId, String hitDetail) {
		String s1 = itemId != null ? itemId.toLowerCase(java.util.Locale.ROOT) : "";
		String s2 = hitDetail != null ? hitDetail.toLowerCase(java.util.Locale.ROOT) : "";
		return isFluidWaterItemId(itemId) || (s1.contains("water") && (s1.contains("bucket") || s1.contains("eimer"))) || s2.contains("water_source");
	}

	private static boolean isLavaAction(String itemId, String hitDetail) {
		String s1 = itemId != null ? itemId.toLowerCase(java.util.Locale.ROOT) : "";
		String s2 = hitDetail != null ? hitDetail.toLowerCase(java.util.Locale.ROOT) : "";
		return isFluidLavaItemId(itemId) || (s1.contains("lava") && (s1.contains("bucket") || s1.contains("eimer"))) || s2.contains("lava_source");
	}

	private static boolean isFluidWaterItemId(String itemId) {
		if (itemId == null) return false;
		String s = itemId.toLowerCase(java.util.Locale.ROOT);
		return (s.contains("fluid") && s.contains("water")) || s.equals("fluid_water") || s.contains("fluid_water");
	}

	private static boolean isFluidLavaItemId(String itemId) {
		if (itemId == null) return false;
		String s = itemId.toLowerCase(java.util.Locale.ROOT);
		return (s.contains("fluid") && s.contains("lava")) || s.equals("fluid_lava") || s.contains("fluid_lava");
	}

	public static ChunkPos chunkFromBlock(Vector3i block) {
		int cx = Math.floorDiv(block.getX(), CELL_SIZE);
		int cz = Math.floorDiv(block.getZ(), CELL_SIZE);
		return new ChunkPos(cx, cz);
	}

	public static ChunkPos chunkFromPlayer(Player player) {
		if (player.getReference() == null || !player.getReference().isValid()) {
			return new ChunkPos(0, 0);
		}
		Store<EntityStore> store = player.getReference().getStore();
		TransformComponent transform = store.getComponent(player.getReference(), TransformComponent.getComponentType());
		if (transform == null) {
			return new ChunkPos(0, 0);
		}
		Vector3d pos = transform.getPosition();
		int bx = (int) Math.floor(pos.getX());
		int bz = (int) Math.floor(pos.getZ());
		int cx = Math.floorDiv(bx, CELL_SIZE);
		int cz = Math.floorDiv(bz, CELL_SIZE);
		return new ChunkPos(cx, cz);
	}

	/**
	 * Prevent worldgen mobs from spawning inside SafeZones when spawnMobs=false.
	 *
	 * <p>We target NPC entities spawned by worldgen to avoid nuking scripted/prefab NPCs.</p>
	 */
	private static final class MobSpawnProtectionSystem extends RefSystem<EntityStore> {
		private static final Query<EntityStore> QUERY = Query.and(
				NPCEntity.getComponentType(),
				TransformComponent.getComponentType()
		);

		private final SafeZonesHytalePlugin plugin;

		private MobSpawnProtectionSystem(SafeZonesHytalePlugin plugin) {
			this.plugin = plugin;
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
		}

		@Override
		public void onEntityAdded(Ref<EntityStore> ref, AddReason reason, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
			if (reason != AddReason.SPAWN) {
				return;
			}
			NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
			if (npc == null) {
				return;
			}
			SafeZonesConfig cfg = plugin.getConfig();
			String npcTypeId = npc.getNPCTypeId();
			if (cfg != null && cfg.isMobEnterWhitelisted(npcTypeId)) {
				// Whitelisted mobs may spawn in SafeZones regardless of spawnMobs.
				return;
			}
			TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
			if (transform == null) {
				return;
			}
			Vector3d pos = transform.getPosition();
			int bx = (int) Math.floor(pos.getX());
			int bz = (int) Math.floor(pos.getZ());
			ChunkPos cell = new ChunkPos(Math.floorDiv(bx, CELL_SIZE), Math.floorDiv(bz, CELL_SIZE));

			Decision d = plugin.getEngine().decide(plugin.getSnapshot(),
					new ZoneActionRequest(ZoneActionType.MOB_SPAWN, null, Role.PLAYER, cell, null, null, null)
			);
			if (!d.allowed()) {
				// Despawn shortly after spawn to avoid breaking spawn jobs.
				TimeResource time = store.getResource(TimeResource.getResourceType());
				if (time != null) {
					commandBuffer.putComponent(ref, DespawnComponent.getComponentType(),
							DespawnComponent.despawnInMilliseconds(time, 1));
				} else {
					commandBuffer.removeEntity(ref, com.hypixel.hytale.component.RemoveReason.REMOVE);
				}
			}
		}

		@Override
		public void onEntityRemove(Ref<EntityStore> ref, com.hypixel.hytale.component.RemoveReason reason, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
			// no-op
		}
	}

	/**
	 * Repel mobs from SafeZones by teleporting them outwards.
	 *
	 * <p>This avoids the "aggro-init loop" where mobs repeatedly acquire/reset targets near the SafeZone border.</p>
	 */
	private static final class MobTargetProtectionSystem extends com.hypixel.hytale.component.system.tick.EntityTickingSystem<EntityStore> {
		private static final Query<EntityStore> QUERY = Query.and(
				NPCEntity.getComponentType(),
				TransformComponent.getComponentType()
		);
		private static final double TRIGGER_DISTANCE_BLOCKS = 5.0;
		private static final double TELEPORT_DISTANCE_BLOCKS = 18.0;
		private static final double TRIGGER_EPSILON = 0.1; // avoid immediate re-trigger after teleport
		private static final double MIN_DIR_LEN = 1e-6;
		private static final java.util.Set<Dependency<EntityStore>> DEPENDENCIES =
				java.util.Set.of(new SystemDependency(Order.AFTER, RoleSystems.RoleActivateSystem.class));

		private final SafeZonesHytalePlugin plugin;
		private long lastWorldTick = Long.MIN_VALUE;
		private long nextAllowedCheckMillis = 0L;
		private boolean allowThisTick = true;

		private MobTargetProtectionSystem(SafeZonesHytalePlugin plugin) {
			this.plugin = plugin;
		}

		@Override
		public void tick(float dt, int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
			if (!shouldCheckThisTick(store)) {
				return;
			}
			NPCEntity npc = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
			if (npc == null) {
				return;
			}
			SafeZonesConfig cfg = plugin.getConfig();
			String npcTypeId = npc.getNPCTypeId();
			if (cfg != null && cfg.isMobEnterWhitelisted(npcTypeId)) {
				// Whitelisted mobs may move freely inside SafeZones.
				return;
			}
			// Some NPCs exist in a pre-spawn state while the spawn job is still building them.
			// Touching them (teleport, etc.) can trip "non-spawned entity" errors.
			if (npc.getRole() == null || npc.getSpawnInstant() == null) {
				return;
			}
			TransformComponent npcTransform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
			if (npcTransform == null) {
				return;
			}

			SafeZonesSnapshot snapshot = plugin.getSnapshot();
			if (snapshot == null || snapshot.zones().isEmpty()) {
				return;
			}

			Vector3d npcPos = npcTransform.getPosition();
			int npcBx = (int) Math.floor(npcPos.getX());
			int npcBz = (int) Math.floor(npcPos.getZ());

			// Find the closest SafeZone (by distance-to-rect) and teleport away if too close.
			SafeZone bestZone = null;
			ZoneRect bestRect = null;
			double bestDistSq = Double.POSITIVE_INFINITY;
			for (SafeZone z : snapshot.zones()) {
				ZoneRect rect = z.rect();
				int minX = rect.minX() * CELL_SIZE;
				int maxX = (rect.maxX() + 1) * CELL_SIZE - 1;
				int minZ = rect.minZ() * CELL_SIZE;
				int maxZ = (rect.maxZ() + 1) * CELL_SIZE - 1;

				double cx = clamp(npcPos.getX(), minX, maxX);
				double cz = clamp(npcPos.getZ(), minZ, maxZ);
				double dx = npcPos.getX() - cx;
				double dz = npcPos.getZ() - cz;
				double distSq = dx * dx + dz * dz;
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					bestZone = z;
					bestRect = rect;
				}
			}

			if (bestZone == null || bestRect == null) {
				return;
			}
			// Only repel if this SafeZone actually denies mob enter.
			if (!bestZone.settings().denyMobEnter()) {
				return;
			}
			double triggerSq = (TRIGGER_DISTANCE_BLOCKS - TRIGGER_EPSILON) * (TRIGGER_DISTANCE_BLOCKS - TRIGGER_EPSILON);
			if (bestDistSq > triggerSq) {
				return;
			}

			// Compute an outward direction away from the SafeZone rect and teleport the NPC outward.
			// If the NPC is inside the rect, we teleport it to (nearest edge distance + TELEPORT_DISTANCE) so it's guaranteed outside.
			int minX = bestRect.minX() * CELL_SIZE;
			int maxX = (bestRect.maxX() + 1) * CELL_SIZE - 1;
			int minZ = bestRect.minZ() * CELL_SIZE;
			int maxZ = (bestRect.maxZ() + 1) * CELL_SIZE - 1;

			double closestX = clamp(npcPos.getX(), minX, maxX);
			double closestZ = clamp(npcPos.getZ(), minZ, maxZ);
			double dx = npcPos.getX() - closestX;
			double dz = npcPos.getZ() - closestZ;

			boolean inside = (dx == 0.0 && dz == 0.0);
			double dirX;
			double dirZ;
			double pushDistance;

			if (inside) {
				// Choose outward normal by nearest edge, then push beyond the edge by TELEPORT_DISTANCE.
				double distToMinX = Math.abs(npcPos.getX() - minX);
				double distToMaxX = Math.abs(maxX - npcPos.getX());
				double distToMinZ = Math.abs(npcPos.getZ() - minZ);
				double distToMaxZ = Math.abs(maxZ - npcPos.getZ());
				double bestEdge = distToMinX;
				dirX = -1.0;
				dirZ = 0.0;
				if (distToMaxX < bestEdge) {
					bestEdge = distToMaxX;
					dirX = 1.0;
					dirZ = 0.0;
				}
				if (distToMinZ < bestEdge) {
					bestEdge = distToMinZ;
					dirX = 0.0;
					dirZ = -1.0;
				}
				if (distToMaxZ < bestEdge) {
					bestEdge = distToMaxZ;
					dirX = 0.0;
					dirZ = 1.0;
				}
				pushDistance = bestEdge + TELEPORT_DISTANCE_BLOCKS;
			} else {
				// Outside: move further along the shortest direction away from the rect.
				double len = Math.sqrt(dx * dx + dz * dz);
				if (len < MIN_DIR_LEN) {
					return;
				}
				dirX = dx / len;
				dirZ = dz / len;
				pushDistance = TELEPORT_DISTANCE_BLOCKS;
			}

			Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
			if (ref == null || !ref.isValid()) {
				return;
			}

			// If no player is nearby, don't teleport/despawn mobs. Instead, rotate them to face away so their
			// AI/wander continues in the opposite direction without forcing chunk loads.
			if (!isAnyPlayerNearby(store, npcPos, PLAYER_NEARBY_CHUNKS)) {
				TransformComponent liveTransform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
				if (liveTransform != null && (Math.abs(dirX) > MIN_DIR_LEN || Math.abs(dirZ) > MIN_DIR_LEN)) {
					float yaw = (float) Math.atan2(-dirX, -dirZ); // radians; yaw=0 faces -Z
					liveTransform.getRotation().setYaw(yaw);
					HeadRotation head = commandBuffer.getComponent(ref, HeadRotation.getComponentType());
					if (head != null && head.getRotation() != null) {
						head.getRotation().setYaw(yaw);
					}
				}
				return;
			}

			// Safety: never teleport NPCs into an unloaded chunk.
			// We assume the NPC's current world chunk is loaded (it's being ticked),
			// and clamp/limit the push so the destination stays within that same chunk.
			int curChunkX = Math.floorDiv(npcBx, WORLD_CHUNK_SIZE_BLOCKS);
			int curChunkZ = Math.floorDiv(npcBz, WORLD_CHUNK_SIZE_BLOCKS);
			double minChunkX = curChunkX * (double) WORLD_CHUNK_SIZE_BLOCKS + 0.5;
			double maxChunkX = (curChunkX + 1) * (double) WORLD_CHUNK_SIZE_BLOCKS - 0.5;
			double minChunkZ = curChunkZ * (double) WORLD_CHUNK_SIZE_BLOCKS + 0.5;
			double maxChunkZ = (curChunkZ + 1) * (double) WORLD_CHUNK_SIZE_BLOCKS - 0.5;

			// Limit the push distance to stay inside the current chunk bounds.
			double margin = 0.25;
			double maxAllowed = pushDistance;
			if (Math.abs(dirX) > MIN_DIR_LEN) {
				if (dirX > 0.0) {
					maxAllowed = Math.min(maxAllowed, (maxChunkX - npcPos.getX() - margin) / dirX);
				} else {
					maxAllowed = Math.min(maxAllowed, (npcPos.getX() - minChunkX - margin) / (-dirX));
				}
			}
			if (Math.abs(dirZ) > MIN_DIR_LEN) {
				if (dirZ > 0.0) {
					maxAllowed = Math.min(maxAllowed, (maxChunkZ - npcPos.getZ() - margin) / dirZ);
				} else {
					maxAllowed = Math.min(maxAllowed, (npcPos.getZ() - minChunkZ - margin) / (-dirZ));
				}
			}
			if (maxAllowed < 0.0) {
				maxAllowed = 0.0;
			}

			double destX = npcPos.getX() + dirX * maxAllowed;
			double destZ = npcPos.getZ() + dirZ * maxAllowed;
			double destY = npcPos.getY();

			// Final clamp for numeric safety.
			destX = clamp(destX, minChunkX, maxChunkX);
			destZ = clamp(destZ, minChunkZ, maxChunkZ);

			// If we still can't get the mob outside the SafeZone safely (because the zone sits at the edge of loaded chunks),
			// despawn it instead of repeatedly teleporting (prevents engine warnings/crashes).
			boolean destInsideRect = destX >= minX && destX <= maxX && destZ >= minZ && destZ <= maxZ;
			if (destInsideRect) {
				TimeResource time = store.getResource(TimeResource.getResourceType());
				if (time != null) {
					commandBuffer.putComponent(ref, DespawnComponent.getComponentType(),
							DespawnComponent.despawnInMilliseconds(time, 1));
				} else {
					commandBuffer.removeEntity(ref, com.hypixel.hytale.component.RemoveReason.REMOVE);
				}
				return;
			}

			// Use the built-in teleport component system (works for non-player entities too).
			try {
				var world = store.getExternalData().getWorld();
				boolean chunkLoaded = false;
				if (world != null) {
					int bx = (int) Math.floor(destX);
					int bz = (int) Math.floor(destZ);
					chunkLoaded = world.getChunkIfLoaded(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(bx, bz)) != null;
				}
				String name = "Entity#" + ref.getIndex();
				try {
					com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent dn =
							commandBuffer.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent.getComponentType());
					if (dn != null && dn.getDisplayName() != null && dn.getDisplayName().getRawText() != null) {
						String t = dn.getDisplayName().getRawText().trim();
						if (!t.isBlank()) name = t;
					}
				} catch (Throwable ignored) {
				}
				String type = "Entity";
				try {
					NPCEntity npcEntity = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
					if (npcEntity != null && npcEntity.getNPCTypeId() != null && !npcEntity.getNPCTypeId().isBlank()) {
						type = npcEntity.getNPCTypeId();
					}
				} catch (Throwable ignored) {
				}
				plugin.getLogger().at(java.util.logging.Level.INFO).log("[SafeZones] Moved %s (%s) [chunkLoaded=%s]",
						name, type, String.valueOf(chunkLoaded));
			} catch (Throwable ignored) {
			}
			commandBuffer.addComponent(ref, Teleport.getComponentType(), new Teleport(new Vector3d(destX, destY, destZ), npcTransform.getRotation()));
		}

		@Override
		public java.util.Set<Dependency<EntityStore>> getDependencies() {
			return DEPENDENCIES;
		}

		private boolean shouldCheckThisTick(Store<EntityStore> store) {
			if (store == null) {
				return false;
			}
			com.hypixel.hytale.server.core.universe.world.World world = store.getExternalData().getWorld();
			if (world == null) {
				return false;
			}
			long tick = world.getTick();
			if (tick != lastWorldTick) {
				lastWorldTick = tick;
				long now = System.currentTimeMillis();
				int intervalMs = getIntervalMs();
				if (now >= nextAllowedCheckMillis) {
					allowThisTick = true;
					nextAllowedCheckMillis = now + intervalMs;
				} else {
					allowThisTick = false;
				}
			}
			return allowThisTick;
		}

		private int getIntervalMs() {
			SafeZonesConfig cfg = plugin.getConfig();
			int raw = cfg != null ? cfg.mobEnterCheckIntervalMs() : 1000;
			if (raw < 500) {
				return 500;
			}
			if (raw > 10_000) {
				return 10_000;
			}
			return raw;
		}

		private static double clamp(double v, double min, double max) {
			return Math.max(min, Math.min(max, v));
		}

		private static boolean isAnyPlayerNearby(Store<EntityStore> store, Vector3d pos, int chunks) {
			if (store == null || pos == null || chunks <= 0) {
				return true; // be conservative: if we can't check, behave like "player nearby"
			}
			var world = store.getExternalData().getWorld();
			if (world == null) {
				return true;
			}
			double radius = (double) chunks * (double) WORLD_CHUNK_SIZE_BLOCKS;
			double radiusSq = radius * radius;
			for (Player p : world.getPlayers()) {
				if (p == null) continue;
				Ref<EntityStore> pref = p.getReference();
				if (pref == null || !pref.isValid()) continue;
				TransformComponent t = store.getComponent(pref, TransformComponent.getComponentType());
				if (t == null) continue;
				Vector3d pp = t.getPosition();
				double dx = pp.getX() - pos.getX();
				double dz = pp.getZ() - pos.getZ();
				if ((dx * dx + dz * dz) <= radiusSq) {
					return true;
				}
			}
			return false;
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
		}
	}

	private abstract static class PlayerEventSystem<EventType extends com.hypixel.hytale.component.system.EcsEvent>
			extends EntityEventSystem<EntityStore, EventType> {
		protected final SafeZonesHytalePlugin plugin;

		protected PlayerEventSystem(SafeZonesHytalePlugin plugin, Class<EventType> eventType) {
			super(eventType);
			this.plugin = plugin;
		}

		protected final String languageTag(ArchetypeChunk<EntityStore> archetypeChunk, int index) {
			PlayerRef pr = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
			return pr != null ? pr.getLanguage() : null;
		}

		@Override
		public Query<EntityStore> getQuery() {
			// Only run for player entities.
			return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
		}
	}

	private static final class BreakBlockProtectionSystem extends PlayerEventSystem<BreakBlockEvent> {
		public BreakBlockProtectionSystem(SafeZonesHytalePlugin plugin) {
			super(plugin, BreakBlockEvent.class);
		}

		@Override
		public void handle(int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store,
		                   CommandBuffer<EntityStore> commandBuffer, BreakBlockEvent event) {
			Player player = archetypeChunk.getComponent(index, Player.getComponentType());
			if (player == null) {
				return;
			}
			Vector3i block = event.getTargetBlock();
			ChunkPos chunk = chunkFromBlock(block);
			Decision d = plugin.decidePlayerAction(player, ZoneActionType.BLOCK_BREAK, chunk, block.getY());
			if (!d.allowed()) {
				event.setCancelled(true);
				plugin.denyWithMessage(player, d, languageTag(archetypeChunk, index));
			}
		}
	}

	private static final class PlaceBlockProtectionSystem extends PlayerEventSystem<PlaceBlockEvent> {
		public PlaceBlockProtectionSystem(SafeZonesHytalePlugin plugin) {
			super(plugin, PlaceBlockEvent.class);
		}

		@Override
		public void handle(int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store,
		                   CommandBuffer<EntityStore> commandBuffer, PlaceBlockEvent event) {
			Player player = archetypeChunk.getComponent(index, Player.getComponentType());
			if (player == null) {
				return;
			}
			Vector3i block = event.getTargetBlock();
			ChunkPos chunk = chunkFromBlock(block);
			Decision d = plugin.decidePlayerAction(player, ZoneActionType.BLOCK_PLACE, chunk, block.getY());
			if (!d.allowed()) {
				event.setCancelled(true);
				plugin.denyWithMessage(player, d, languageTag(archetypeChunk, index));
				// Some runtimes fire PlaceBlockEvent after placement; ensure the block doesn't remain placed.
				undoPlacementIfNeeded(store, event);
				return;
			}

			// Fluids are special: some packs expose Fluid_Water / Fluid_Lava as placeable blocks/items.
			// Deny water placement everywhere in SafeZones except for plot owners within the configured Y band.
			try {
				SafeZonesSnapshot snapshot = plugin.getSnapshot();
				if (snapshot != null && snapshot.findZoneFor(chunk).isPresent()) {
					String itemId = null;
					var inHand = event.getItemInHand();
					if (inHand != null && !inHand.isEmpty()) {
						itemId = inHand.getItemId();
					}
					if (isFluidWaterItemId(itemId) || isFluidLavaItemId(itemId)) {
						Decision wd = decideWaterPlacement(plugin, snapshot, player, chunk, block.getY(), itemId, null);
						if (!wd.allowed()) {
							event.setCancelled(true);
							plugin.denyWithMessage(player, wd, languageTag(archetypeChunk, index));
							undoPlacementIfNeeded(store, event);
							return;
						}
					}
				}
			} catch (Throwable ignored) {
			}

			// House item blacklist: deny placing certain items in house cells (H), even for owners.
			SafeZonesSnapshot snapshot = plugin.getSnapshot();
			if (snapshot != null && snapshot.chunkOverrideOrNone(chunk).type() == CellType.HOUSE) {
				SafeZonesConfig cfg = plugin.getConfig();
				if (cfg != null) {
					var inHand = event.getItemInHand();
					if (inHand != null && !inHand.isEmpty() && cfg.isBlockedInHouse(inHand.getItemId())) {
						event.setCancelled(true);
						plugin.denyWithMessage(player, Decision.deny("houseItemBlocked"), languageTag(archetypeChunk, index));
						undoPlacementIfNeeded(store, event);
					}
				}
			}

			// Placement distance rules: certain blocks must be placed with a minimum distance to the SafeZone outer border.
			// This only affects newly placed blocks (not existing ones).
			try {
				SafeZonesConfig cfg = plugin.getConfig();
				SafeZonesSnapshot snap = plugin.getSnapshot();
				if (cfg != null && snap != null) {
					var inHand = event.getItemInHand();
					String itemId = (inHand != null && !inHand.isEmpty()) ? inHand.getItemId() : null;
					String blockId = null;
					try {
						var item = inHand != null ? inHand.getItem() : null;
						if (item != null && item.hasBlockType()) {
							blockId = item.getBlockId();
						}
					} catch (Throwable ignored) {
					}
					// Support both ItemId and BlockId keys (users often know the block id, e.g. "bench_workbench").
					Integer dist = cfg.placeDistanceForItem(itemId);
					if (dist == null) {
						dist = cfg.placeDistanceForItem(blockId);
					}
					if (dist != null && dist.intValue() > 0) {
						SafeZone zone = snap.findZoneFor(chunk).orElse(null);
						if (zone != null) {
							int required = Math.min(4, Math.max(0, dist.intValue()));
							if (required > 0 && violatesPlaceDistance(store, event, zone.rect(), required)) {
								event.setCancelled(true);
								plugin.denyWithMessage(player, Decision.deny("placeDistance"), languageTag(archetypeChunk, index));
								undoPlacementIfNeeded(store, event);
								return;
							}
						}
					}
				}
			} catch (Throwable ignored) {
			}
		}
	}

	private static void undoPlacementIfNeeded(Store<EntityStore> store, PlaceBlockEvent event) {
		try {
			if (store == null || event == null) return;
			var world = store.getExternalData().getWorld();
			if (world == null) return;
			Vector3i pos = event.getTargetBlock();
			if (pos == null) return;

			var inHand = event.getItemInHand();
			if (inHand == null || inHand.isEmpty()) return;
			var item = inHand.getItem();
			if (item == null || !item.hasBlockType()) return;
			String blockId = item.getBlockId();
			if (blockId == null || blockId.isBlank()) return;
			BlockType bt = BlockType.getAssetMap().getAsset(blockId);
			if (bt == null) return;

			RotationTuple rot = null;
			try { rot = event.getRotation(); } catch (Throwable ignored) {}
			int rotIdx = 0;
			try { if (rot != null) rotIdx = rot.index(); } catch (Throwable ignored) {}

			Box bb = null;
			try {
				BlockBoundingBoxes hitboxAsset = BlockBoundingBoxes.getAssetMap().getAsset(bt.getHitboxTypeIndex());
				if (hitboxAsset != null) {
					BlockBoundingBoxes.RotatedVariantBoxes rotated = hitboxAsset.get(rotIdx);
					if (rotated != null) {
						bb = rotated.getBoundingBox();
					}
				}
			} catch (Throwable ignored) {
			}
			if (bb == null) {
				bb = new Box(new Vector3d(0, 0, 0), new Vector3d(1, 1, 1));
			}

			int minX = (int) Math.floor(pos.x + bb.min.x);
			int maxX = (int) Math.ceil(pos.x + bb.max.x) - 1;
			int minY = (int) Math.floor(pos.y + bb.min.y);
			int maxY = (int) Math.ceil(pos.y + bb.max.y) - 1;
			int minZ = (int) Math.floor(pos.z + bb.min.z);
			int maxZ = (int) Math.ceil(pos.z + bb.max.z) - 1;

			// Best-effort: only clear blocks that match the block type we attempted to place.
			for (int x = minX; x <= maxX; x++) {
				for (int y = minY; y <= maxY; y++) {
					for (int z = minZ; z <= maxZ; z++) {
						try {
							BlockType cur = world.getBlockType(x, y, z);
							if (cur == null || cur != bt) {
								continue;
							}
							long idx = ChunkUtil.indexChunkFromBlock(x, z);
							BlockAccessor accessor = world.getChunkIfLoaded(idx);
							if (accessor == null) {
								continue;
							}
							accessor.setBlock(x, y, z, BlockType.EMPTY);
						} catch (Throwable ignored) {
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private static boolean violatesPlaceDistance(Store<EntityStore> store, PlaceBlockEvent event, ZoneRect rect, int distBlocks) {
		if (store == null || event == null || rect == null || distBlocks <= 0) {
			return false;
		}
		Vector3i pos = event.getTargetBlock();
		if (pos == null) {
			return false;
		}
		var inHand = event.getItemInHand();
		if (inHand == null || inHand.isEmpty()) {
			return false;
		}
		var item = inHand.getItem();
		if (item == null || !item.hasBlockType()) {
			return false;
		}
		String blockId = item.getBlockId();
		if (blockId == null || blockId.isBlank()) {
			return false;
		}
		BlockType bt = BlockType.getAssetMap().getAsset(blockId);
		if (bt == null) {
			return false;
		}

		RotationTuple rot = null;
		try {
			rot = event.getRotation();
		} catch (Throwable ignored) {
		}
		int rotIdx = 0;
		try {
			if (rot != null) rotIdx = rot.index();
		} catch (Throwable ignored) {
		}

		Box bb = null;
		try {
			BlockBoundingBoxes hitboxAsset = BlockBoundingBoxes.getAssetMap().getAsset(bt.getHitboxTypeIndex());
			if (hitboxAsset != null) {
				BlockBoundingBoxes.RotatedVariantBoxes rotated = hitboxAsset.get(rotIdx);
				if (rotated != null) {
					bb = rotated.getBoundingBox();
				}
			}
		} catch (Throwable ignored) {
		}
		// Fallback to 1x1x1 in block space.
		if (bb == null) {
			bb = new Box(new Vector3d(0, 0, 0), new Vector3d(1, 1, 1));
		}

		double minX = pos.x + bb.min.x;
		double maxX = pos.x + bb.max.x;
		double minZ = pos.z + bb.min.z;
		double maxZ = pos.z + bb.max.z;

		int minTouchedX = (int) Math.floor(minX);
		int maxTouchedX = (int) Math.ceil(maxX) - 1;
		int minTouchedZ = (int) Math.floor(minZ);
		int maxTouchedZ = (int) Math.ceil(maxZ) - 1;

		int zoneMinX = rect.minX() * CELL_SIZE;
		int zoneMaxX = (rect.maxX() + 1) * CELL_SIZE - 1;
		int zoneMinZ = rect.minZ() * CELL_SIZE;
		int zoneMaxZ = (rect.maxZ() + 1) * CELL_SIZE - 1;

		// Must stay at least distBlocks away from each outer edge.
		if ((minTouchedX - zoneMinX) < distBlocks) return true;
		if ((zoneMaxX - maxTouchedX) < distBlocks) return true;
		if ((minTouchedZ - zoneMinZ) < distBlocks) return true;
		if ((zoneMaxZ - maxTouchedZ) < distBlocks) return true;

		return false;
	}

	private static final class UseBlockProtectionSystem extends PlayerEventSystem<UseBlockEvent.Pre> {
		public UseBlockProtectionSystem(SafeZonesHytalePlugin plugin) {
			super(plugin, UseBlockEvent.Pre.class);
		}

		@Override
		public void handle(int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store,
		                   CommandBuffer<EntityStore> commandBuffer, UseBlockEvent.Pre event) {
			Player player = archetypeChunk.getComponent(index, Player.getComponentType());
			if (player == null) {
				return;
			}
			Vector3i block = event.getTargetBlock();
			ChunkPos chunk = chunkFromBlock(block);

			// Special-case: block Teleporter/Portal configuration UIs inside ANY SafeZone.
			// These menus are typically opened via Interaction="OpenCustomUI" and would otherwise be allowed
			// for house owners (allowInteract=true).
			try {
				SafeZonesSnapshot snapshot = plugin.getSnapshot();
				if (snapshot != null && snapshot.findZoneFor(chunk).isPresent()) {
					BlockType bt = event.getBlockType();
					if (bt != null && isPortalOrTeleporterBlockType(bt)) {
						// Block ALL use interactions on portal/teleporter blocks inside SafeZones.
						// This prevents both:
						// - opening configuration UIs (OpenCustomUI nested in the interaction chain)
						// - entering / using the portal/teleporter
						event.setCancelled(true);
						plugin.denyWithMessage(player, Decision.deny(ZoneEngine.REASON_DENIED_INTERACT), languageTag(archetypeChunk, index));
						return;
					}
				}
			} catch (Throwable ignored) {
			}

			Decision d = plugin.decidePlayerAction(player, ZoneActionType.INTERACT, chunk, null);
			if (!d.allowed()) {
				event.setCancelled(true);
				plugin.denyWithMessage(player, d, languageTag(archetypeChunk, index));
			}
		}
	}

	private static boolean isPortalOrTeleporterBlockType(BlockType bt) {
		try {
			String id = bt.getId();
			if (id == null) return false;
			String lower = id.toLowerCase(java.util.Locale.ROOT);
			return lower.contains("teleporter") || lower.contains("portal");
		} catch (Throwable ignored) {
			return false;
		}
	}

	// Note: we intentionally do not try to inspect interaction chains here; blocking is done by block-type.

	/**
	 * If a portal/teleporter interaction in a HOUSE cell triggers a teleport (including instance transfer),
	 * neutralize it before {@link TeleportSystems.PlayerMoveSystem} runs.
	 */
	private static final class PortalHouseTeleportBlockSystem extends RefChangeSystem<EntityStore, Teleport> {
		private final SafeZonesHytalePlugin plugin;

		private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
				new SystemDependency<>(Order.BEFORE, TeleportSystems.PlayerMoveSystem.class)
		);

		private static final Query<EntityStore> QUERY = Query.and(
				Teleport.getComponentType(),
				PlayerRef.getComponentType(),
				Player.getComponentType(),
				TransformComponent.getComponentType()
		);

		private PortalHouseTeleportBlockSystem(SafeZonesHytalePlugin plugin) {
			this.plugin = plugin;
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
		}

		@Override
		public Set<Dependency<EntityStore>> getDependencies() {
			return DEPENDENCIES;
		}

		@Override
		public com.hypixel.hytale.component.ComponentType<EntityStore, Teleport> componentType() {
			return Teleport.getComponentType();
		}

		@Override
		public void onComponentAdded(Ref<EntityStore> ref, Teleport teleport, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
			if (teleport == null) return;
			PlayerRef pr = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
			Player p = commandBuffer.getComponent(ref, Player.getComponentType());
			TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
			if (pr == null || pr.getUuid() == null || p == null || transform == null) {
				return;
			}

			Long lastInteract = recentPortalOrTeleporterInteractMillis.get(pr.getUuid());
			if (lastInteract == null) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now - lastInteract > PORTAL_TELEPORT_BLOCK_WINDOW_MS) {
				recentPortalOrTeleporterInteractMillis.remove(pr.getUuid());
				return;
			}

			// Only block if player is currently standing in a HOUSE cell that is inside a SafeZone.
			Vector3d pos = transform.getPosition();
			int bx = (int) Math.floor(pos.getX());
			int bz = (int) Math.floor(pos.getZ());
			ChunkPos cell = new ChunkPos(Math.floorDiv(bx, CELL_SIZE), Math.floorDiv(bz, CELL_SIZE));
			SafeZonesSnapshot snapshot = plugin.getSnapshot();
			if (snapshot == null || snapshot.findZoneFor(cell).isEmpty()) {
				return;
			}
			if (snapshot.chunkOverrideOrNone(cell).type() != CellType.HOUSE) {
				return;
			}

			// Replace the Teleport with a no-op teleport in the CURRENT world.
			Teleport replacement = new Teleport(store.getExternalData().getWorld(), transform.getPosition(), transform.getRotation());
			commandBuffer.putComponent(ref, Teleport.getComponentType(), replacement);

			// Consume marker & rate-limit feedback.
			recentPortalOrTeleporterInteractMillis.remove(pr.getUuid());
			Long lastMsg = lastPortalBlockMessageMillis.get(pr.getUuid());
			if (lastMsg == null || now - lastMsg > PORTAL_BLOCK_MESSAGE_COOLDOWN_MS) {
				lastPortalBlockMessageMillis.put(pr.getUuid(), now);
				plugin.denyWithMessage(p, Decision.deny(ZoneEngine.REASON_DENIED_INTERACT), pr.getLanguage());
			}
		}

		@Override public void onComponentRemoved(Ref<EntityStore> ref, Teleport component, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {}
		@Override public void onComponentSet(Ref<EntityStore> ref, Teleport oldComponent, Teleport newComponent, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {}
	}

	/**
	 * Prevents crafting benches from pulling materials out of foreign containers.
	 *
	 * <p>Hytale benches can consume materials from nearby containers. We cancel the craft if any
	 * nearby container belongs to a different SafeZone claim where the player is not allowed to open containers.</p>
	 */
	private static final class CraftRecipeProtectionSystem extends PlayerEventSystem<CraftRecipeEvent.Pre> {
		private static final int CELL_SIZE = 16;
		private final SafeZonesHytalePlugin plugin;
		private final BenchAccessor benchAccessor = new BenchAccessor();
		private static final ConcurrentHashMap<Class<?>, ContainerPosExtractor> CONTAINER_POS_EXTRACTORS = new ConcurrentHashMap<>();

		private CraftRecipeProtectionSystem(SafeZonesHytalePlugin plugin) {
			super(plugin, CraftRecipeEvent.Pre.class);
			this.plugin = plugin;
		}

		@Override
		public void handle(int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store,
		                   CommandBuffer<EntityStore> commandBuffer, CraftRecipeEvent.Pre event) {
			Player player = archetypeChunk.getComponent(index, Player.getComponentType());
			if (player == null) {
				return;
			}
			Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
			if (ref == null || !ref.isValid()) {
				return;
			}
			CraftingManager craftingManager = store.getComponent(ref, CraftingManager.getComponentType());
			if (craftingManager == null || !craftingManager.hasBenchSet()) {
				return;
			}
			Vector3i benchPos = benchAccessor.getBenchPosition(craftingManager);
			if (benchPos == null) {
				return;
			}
			BlockState state = store.getExternalData().getWorld().getState(benchPos.x, benchPos.y, benchPos.z, true);
			if (!(state instanceof BenchState)) {
				return;
			}
			BenchState benchState = (BenchState) state;
			if (shouldBlockCraftBecauseForeignContainersWouldBeUsed(player, benchState, event)) {
				event.setCancelled(true);
				plugin.denyWithMessage(player, Decision.deny(ZoneEngine.REASON_DENIED_CONTAINER), languageTag(archetypeChunk, index));
			}
		}

		private boolean shouldBlockCraftBecauseForeignContainersWouldBeUsed(Player player, BenchState benchState, CraftRecipeEvent event) {
			// If the recipe can be satisfied from the player's own inventory, allow crafting even if foreign
			// containers are nearby (prevents unnecessary blocking).
			if (event == null || event.getCraftedRecipe() == null) {
				return false;
			}
			int quantity = Math.max(1, event.getQuantity());
			List<MaterialQuantity> required = CraftingManager.getInputMaterials(event.getCraftedRecipe(), quantity);
			if (required == null || required.isEmpty()) {
				return false;
			}

			ItemContainer playerInventory = player.getInventory().getCombinedBackpackStorageHotbar();
			if (playerInventory != null && playerInventory.canRemoveMaterials(required)) {
				return false;
			}

			var world = benchState.getChunk().getWorld();
			Store<ChunkStore> store = world.getChunkStore().getStore();
			int limit = world.getGameplayConfig().getCraftingConfig().getBenchMaterialChestLimit();
			double horizontalRadius = world.getGameplayConfig().getCraftingConfig().getBenchMaterialHorizontalChestSearchRadius();
			double verticalRadius = world.getGameplayConfig().getCraftingConfig().getBenchMaterialVerticalChestSearchRadius();
			Vector3d blockPos = benchState.getBlockPosition().toVector3d();

			BlockBoundingBoxes hitboxAsset = BlockBoundingBoxes.getAssetMap().getAsset(benchState.getBlockType().getHitboxTypeIndex());
			BlockBoundingBoxes.RotatedVariantBoxes rotatedHitbox = hitboxAsset.get(benchState.getRotationIndex());
			var boundingBox = rotatedHitbox.getBoundingBox();
			double benchWidth = boundingBox.width();
			double benchHeight = boundingBox.height();
			double benchDepth = boundingBox.depth();
			double extraSearchRadius = Math.max(benchWidth, Math.max(benchDepth, benchHeight)) - 1.0;

			SpatialResource<Ref<ChunkStore>, ChunkStore> blockStateSpatialStructure =
					store.getResource(BlockStateModule.get().getItemContainerSpatialResourceType());
			ObjectList<Ref<ChunkStore>> results = SpatialResource.getThreadLocalReferenceList();
			blockStateSpatialStructure.getSpatialStructure().ordered3DAxis(
					blockPos,
					horizontalRadius + extraSearchRadius,
					verticalRadius + extraSearchRadius,
					horizontalRadius + extraSearchRadius,
					results
			);
			if (results.isEmpty()) {
				return false;
			}

			double minX = blockPos.x + boundingBox.min.x - horizontalRadius;
			double minY = blockPos.y + boundingBox.min.y - verticalRadius;
			double minZ = blockPos.z + boundingBox.min.z - horizontalRadius;
			double maxX = blockPos.x + boundingBox.max.x + horizontalRadius;
			double maxY = blockPos.y + boundingBox.max.y + verticalRadius;
			double maxZ = blockPos.z + boundingBox.max.z + horizontalRadius;

			int checked = 0;
			boolean hasForeign = false;
			java.util.ArrayList<ItemContainer> allowedContainers = new java.util.ArrayList<>();
			for (Ref<ChunkStore> ref : results) {
				BlockState state = BlockState.getBlockState(ref, ref.getStore());
				Vector3d chestPos = containerCenteredBlockPosition(state);
				if (chestPos == null) continue;
				if (!(chestPos.x >= minX) || !(chestPos.x <= maxX) ||
						!(chestPos.y >= minY) || !(chestPos.y <= maxY) ||
						!(chestPos.z >= minZ) || !(chestPos.z <= maxZ)) {
					continue;
				}
				int bx = (int) Math.floor(chestPos.x);
				int bz = (int) Math.floor(chestPos.z);
				ChunkPos cell = new ChunkPos(Math.floorDiv(bx, CELL_SIZE), Math.floorDiv(bz, CELL_SIZE));
				Decision d = plugin.decideBenchContainerAccess(player, cell);
				if (!d.allowed()) hasForeign = true;
				else if (state instanceof ItemContainerState) {
					ItemContainerState chest = (ItemContainerState) state;
					ItemContainer chestContainer = chest.getItemContainer();
					if (chestContainer != null) {
						allowedContainers.add(chestContainer);
					}
				}
				if (++checked >= limit) {
					break;
				}
			}
			if (!hasForeign) {
				return false;
			}

			// Player inventory alone couldn't satisfy the recipe; see if inventory + allowed containers can.
			if (playerInventory == null) {
				return true;
			}
			if (allowedContainers.isEmpty()) {
				return true;
			}
			ItemContainer[] containers = new ItemContainer[allowedContainers.size() + 1];
			containers[0] = playerInventory;
			for (int i = 0; i < allowedContainers.size(); i++) {
				containers[i + 1] = allowedContainers.get(i);
			}
			CombinedItemContainer combined = new CombinedItemContainer(containers);
			return !combined.canRemoveMaterials(required);
		}

		/**
		 * The crafting system indexes "nearby containers" via an item-container spatial resource, but not all
		 * container block-states necessarily share the same concrete Java type. We therefore resolve positions
		 * via a cached reflective accessor to reliably cover all container state classes.
		 */
		private static Vector3d containerCenteredBlockPosition(BlockState state) {
			if (state == null) return null;
			Class<?> cls = state.getClass();
			ContainerPosExtractor extractor = CONTAINER_POS_EXTRACTORS.get(cls);
			if (extractor == null) {
				extractor = buildContainerPosExtractor(cls);
				CONTAINER_POS_EXTRACTORS.put(cls, extractor);
			}
			return extractor.extract(state);
		}

		private static ContainerPosExtractor buildContainerPosExtractor(Class<?> cls) {
			// Fast-path for the known API type.
			if (ItemContainerState.class.isAssignableFrom(cls)) {
				return (BlockState s) -> ((ItemContainerState) s).getCenteredBlockPosition();
			}

			// Try common accessors used by many block-state implementations.
			ContainerPosExtractor viaCentered = tryBuildMethodExtractor(cls, "getCenteredBlockPosition", true);
			if (viaCentered != null) return viaCentered;

			ContainerPosExtractor viaBlockPos = tryBuildMethodExtractor(cls, "getBlockPosition", false);
			if (viaBlockPos != null) return viaBlockPos;

			// Fallback: some states store coords in fields (similar to CraftingManager).
			Field fx = null, fy = null, fz = null;
			try {
				fx = cls.getDeclaredField("x");
				fy = cls.getDeclaredField("y");
				fz = cls.getDeclaredField("z");
				fx.setAccessible(true);
				fy.setAccessible(true);
				fz.setAccessible(true);
				if (fx.getType() == int.class && fy.getType() == int.class && fz.getType() == int.class) {
					final Field finalFx = fx, finalFy = fy, finalFz = fz;
					return (BlockState s) -> {
						try {
							int x = finalFx.getInt(s);
							int y = finalFy.getInt(s);
							int z = finalFz.getInt(s);
							return new Vector3d(x + 0.5, y + 0.5, z + 0.5);
						} catch (Exception ignored) {
							return null;
						}
					};
				}
			} catch (Exception ignored) {
				// fall through
			}

			return (BlockState s) -> null;
		}

		private static ContainerPosExtractor tryBuildMethodExtractor(Class<?> cls, String methodName, boolean alreadyCentered) {
			Method m = null;
			try {
				m = cls.getMethod(methodName);
			} catch (Exception ignored) {
			}
			if (m == null) {
				try {
					m = cls.getDeclaredMethod(methodName);
					m.setAccessible(true);
				} catch (Exception ignored) {
				}
			}
			if (m == null) return null;

			Class<?> ret = m.getReturnType();
			if (Vector3d.class.isAssignableFrom(ret)) {
				final Method finalM = m;
				return (BlockState s) -> {
					try {
						return (Vector3d) finalM.invoke(s);
					} catch (Exception ignored) {
						return null;
					}
				};
			}

			if (Vector3i.class.isAssignableFrom(ret)) {
				final Method finalM = m;
				return (BlockState s) -> {
					try {
						Vector3i v = (Vector3i) finalM.invoke(s);
						if (v == null) return null;
						return alreadyCentered ? v.toVector3d() : new Vector3d(v.x + 0.5, v.y + 0.5, v.z + 0.5);
					} catch (Exception ignored) {
						return null;
					}
				};
			}

			if (BlockPosition.class.isAssignableFrom(ret)) {
				final Method finalM = m;
				return (BlockState s) -> {
					try {
						Object o = finalM.invoke(s);
						if (!(o instanceof BlockPosition)) return null;
						BlockPosition bp = (BlockPosition) o;

						// Use reflection to remain compatible with different protocol representations.
						Integer x = reflectInt(bp, "getX", "x");
						Integer y = reflectInt(bp, "getY", "y");
						Integer z = reflectInt(bp, "getZ", "z");
						if (x == null || y == null || z == null) return null;
						return alreadyCentered ? new Vector3d(x, y, z) : new Vector3d(x + 0.5, y + 0.5, z + 0.5);
					} catch (Exception ignored) {
						return null;
					}
				};
			}

			return null;
		}

		private static Integer reflectInt(Object obj, String getterName, String fieldName) {
			try {
				Method getter = obj.getClass().getMethod(getterName);
				Object v = getter.invoke(obj);
				if (v instanceof Number) return ((Number) v).intValue();
			} catch (Exception ignored) {
			}
			try {
				Field f = obj.getClass().getDeclaredField(fieldName);
				f.setAccessible(true);
				Object v = f.get(obj);
				if (v instanceof Number) return ((Number) v).intValue();
			} catch (Exception ignored) {
			}
			return null;
		}

		@FunctionalInterface
		private interface ContainerPosExtractor {
			Vector3d extract(BlockState state);
		}
	}

	private static final class BenchAccessor {
		private final Field xField;
		private final Field yField;
		private final Field zField;

		private BenchAccessor() {
			Field x = null;
			Field y = null;
			Field z = null;
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
				int x = xField.getInt(manager);
				int y = yField.getInt(manager);
				int z = zField.getInt(manager);
				return new Vector3i(x, y, z);
			} catch (Exception ignored) {
				return null;
			}
		}
	}

	private static final class DropItemProtectionSystem extends PlayerEventSystem<DropItemEvent.PlayerRequest> {
		private static final Query<EntityStore> QUERY = Query.and(Player.getComponentType(), PlayerRef.getComponentType(), TransformComponent.getComponentType());

		public DropItemProtectionSystem(SafeZonesHytalePlugin plugin) {
			super(plugin, DropItemEvent.PlayerRequest.class);
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
		}

		@Override
		public void handle(int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store,
		                   CommandBuffer<EntityStore> commandBuffer, DropItemEvent.PlayerRequest event) {
			Player player = archetypeChunk.getComponent(index, Player.getComponentType());
			if (player == null) {
				return;
			}
			TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
			if (transform == null) {
				return;
			}
			Vector3d pos = transform.getPosition();
			int bx = (int) Math.floor(pos.getX());
			int bz = (int) Math.floor(pos.getZ());
			ChunkPos chunk = new ChunkPos(Math.floorDiv(bx, CELL_SIZE), Math.floorDiv(bz, CELL_SIZE));
			Decision d = plugin.decidePlayerAction(player, ZoneActionType.ITEM_DROP, chunk, null);
			if (!d.allowed()) {
				event.setCancelled(true);
				plugin.denyWithMessage(player, d, languageTag(archetypeChunk, index));
			}
		}
	}

	private static final class PickupItemProtectionSystem extends PlayerEventSystem<InteractivelyPickupItemEvent> {
		private static final Query<EntityStore> QUERY = Query.and(Player.getComponentType(), PlayerRef.getComponentType(), TransformComponent.getComponentType());

		public PickupItemProtectionSystem(SafeZonesHytalePlugin plugin) {
			super(plugin, InteractivelyPickupItemEvent.class);
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
		}

		@Override
		public void handle(int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store,
		                   CommandBuffer<EntityStore> commandBuffer, InteractivelyPickupItemEvent event) {
			Player player = archetypeChunk.getComponent(index, Player.getComponentType());
			if (player == null) {
				return;
			}
			TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
			if (transform == null) {
				return;
			}
			Vector3d pos = transform.getPosition();
			int bx = (int) Math.floor(pos.getX());
			int bz = (int) Math.floor(pos.getZ());
			ChunkPos chunk = new ChunkPos(Math.floorDiv(bx, CELL_SIZE), Math.floorDiv(bz, CELL_SIZE));
			Decision d = plugin.decidePlayerAction(player, ZoneActionType.ITEM_PICKUP, chunk, null);
			if (!d.allowed()) {
				event.setCancelled(true);
				plugin.denyWithMessage(player, d, languageTag(archetypeChunk, index));
			}
		}
	}

	/**
	 * Prevent damage inside SafeZones, regardless of claims (owners can build/mine, but PvP stays off).
	 */
	private static final class DamageProtectionSystem extends PlayerEventSystem<Damage> {
		private static final Query<EntityStore> QUERY = Query.and(
				Player.getComponentType(),
				PlayerRef.getComponentType(),
				TransformComponent.getComponentType()
		);

		public DamageProtectionSystem(SafeZonesHytalePlugin plugin) {
			super(plugin, Damage.class);
		}

		@Override
		public SystemGroup<EntityStore> getGroup() {
			// Must run in the DamageModule filter phase; otherwise cancelling is too late.
			return DamageModule.get().getFilterDamageGroup();
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
		}

		@Override
		public void handle(int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store,
		                   CommandBuffer<EntityStore> commandBuffer, Damage event) {
			Player targetPlayer = archetypeChunk.getComponent(index, Player.getComponentType());
			if (targetPlayer == null) {
				return;
			}
			TransformComponent targetTransform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
			if (targetTransform == null) {
				return;
			}

			// Target cell is what defines the SafeZone rules.
			Vector3d pos = targetTransform.getPosition();
			int bx = (int) Math.floor(pos.getX());
			int bz = (int) Math.floor(pos.getZ());
			ChunkPos cell = new ChunkPos(Math.floorDiv(bx, CELL_SIZE), Math.floorDiv(bz, CELL_SIZE));

			// For now: inside ANY SafeZone, players take no damage from any source.
			// (This matches "keinen Schaden von allen Sourcen" and is independent of claim ownership.)
			if (plugin.getSnapshot().findZoneFor(cell).isPresent()) {
				event.setCancelled(true);
				event.setAmount(0.0f);
			}
		}
	}
}

