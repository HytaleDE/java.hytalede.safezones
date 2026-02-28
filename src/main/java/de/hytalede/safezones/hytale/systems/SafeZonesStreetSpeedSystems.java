package de.hytalede.safezones.hytale.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hytalede.safezones.core.CellType;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adjusts player movement speed while walking on STREET cells.
 */
public final class SafeZonesStreetSpeedSystems {
	private SafeZonesStreetSpeedSystems() {
	}

	// Fallback only: if we never observed PlayerReadyEvent, assume ready after this.
	private static final long FALLBACK_ASSUME_READY_MS = 30_000L;

	public static void register(SafeZonesHytalePlugin plugin) {
		Objects.requireNonNull(plugin, "plugin");
		State state = new State(plugin);
		plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
			try {
				Ref<EntityStore> ref = event.getPlayerRef();
				if (ref == null || !ref.isValid()) {
					return;
				}
				Store<EntityStore> store = ref.getStore();
				PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
				if (pr == null || pr.getUuid() == null) {
					return;
				}
				state.readyAtMillisByPlayer.put(pr.getUuid(), System.currentTimeMillis());
			} catch (Throwable ignored) {
			}
		});
		plugin.getEntityStoreRegistry().registerSystem(new Tick(state));
		plugin.getEntityStoreRegistry().registerSystem(new Cleanup(state));
	}

	private static final class State {
		private final SafeZonesHytalePlugin plugin;
		private final ConcurrentHashMap<UUID, Boolean> streetEnabledByPlayer = new ConcurrentHashMap<>();
		private final ConcurrentHashMap<UUID, Long> firstSeenMillisByPlayer = new ConcurrentHashMap<>();
		private final ConcurrentHashMap<UUID, Long> readyAtMillisByPlayer = new ConcurrentHashMap<>();

		private State(SafeZonesHytalePlugin plugin) {
			this.plugin = plugin;
		}
	}

	private static final Query<EntityStore> QUERY = Archetype.of(
			PlayerRef.getComponentType(),
			TransformComponent.getComponentType(),
			MovementManager.getComponentType()
	);

	private static final class Tick extends EntityTickingSystem<EntityStore> {
		private final State state;

		private Tick(State state) {
			this.state = state;
		}

		@Override
		public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
			PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
			TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
			MovementManager movementManager = chunk.getComponent(index, MovementManager.getComponentType());
			if (playerRef == null || transform == null || movementManager == null) {
				return;
			}
			if (movementManager.getSettings() == null || movementManager.getDefaultSettings() == null) {
				return;
			}

			UUID uuid = playerRef.getUuid();
			if (uuid == null) {
				return;
			}

			long now = System.currentTimeMillis();
			Long readyAt = state.readyAtMillisByPlayer.get(uuid);
			if (readyAt == null) {
				Long firstSeen = state.firstSeenMillisByPlayer.get(uuid);
				if (firstSeen == null) {
					// If we missed the add hook for some reason, start the timer now.
					state.firstSeenMillisByPlayer.put(uuid, now);
					return;
				}
				// Avoid crash loops: if we never saw PlayerReadyEvent, only assume "ready" after a long delay.
				if (now - firstSeen.longValue() >= FALLBACK_ASSUME_READY_MS) {
					readyAt = firstSeen;
					state.readyAtMillisByPlayer.put(uuid, readyAt);
				} else {
					return;
				}
			}

			int applyDelayMs = 5000;
			try {
				if (state.plugin.getConfig() != null) {
					applyDelayMs = state.plugin.getConfig().streetSpeedApplyDelayMs();
				}
			} catch (Throwable ignored) {
			}
			if (applyDelayMs < 0) {
				applyDelayMs = 0;
			}
			if (now - readyAt.longValue() < (long) applyDelayMs) {
				return;
			}

			Vector3d pos = transform.getPosition();
			int bx = (int) Math.floor(pos.getX());
			int bz = (int) Math.floor(pos.getZ());
			ChunkPos cell = new ChunkPos(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));

			SafeZonesSnapshot snapshot = state.plugin.getSnapshot();
			ChunkOverride ov = snapshot.chunkOverrides().get(cell);
			boolean inStreet = ov != null && ov.type() == CellType.STREET;

			boolean wasStreet = Boolean.TRUE.equals(state.streetEnabledByPlayer.get(uuid));
			if (inStreet != wasStreet) {
				if (inStreet) {
					state.streetEnabledByPlayer.put(uuid, true);
				} else {
					state.streetEnabledByPlayer.remove(uuid);
				}
			}

			double mult = 2.0d;
			try {
				if (state.plugin.getConfig() != null) {
					mult = state.plugin.getConfig().streetSpeedMultiplier();
				}
			} catch (Throwable ignored) {
			}
			if (!Double.isFinite(mult) || mult <= 0) {
				mult = 1.0d;
			}
			float desiredBaseSpeed = movementManager.getDefaultSettings().baseSpeed * (float) (inStreet ? mult : 1.0d);
			float currentBaseSpeed = movementManager.getSettings().baseSpeed;
			if (Math.abs(currentBaseSpeed - desiredBaseSpeed) > 0.0001f) {
				movementManager.getSettings().baseSpeed = desiredBaseSpeed;
				movementManager.update(playerRef.getPacketHandler());
			}
		}

		@Override
		public Query<EntityStore> getQuery() {
			return QUERY;
		}
	}

	private static final class Cleanup extends HolderSystem<EntityStore> {
		private final State state;

		private Cleanup(State state) {
			this.state = state;
		}

		@Override
		public void onEntityAdd(Holder<EntityStore> holder, AddReason reason, Store<EntityStore> store) {
			PlayerRef pr = holder.getComponent(PlayerRef.getComponentType());
			if (pr != null) {
				UUID uuid = pr.getUuid();
				state.streetEnabledByPlayer.remove(uuid);
				if (uuid != null) {
					state.firstSeenMillisByPlayer.put(uuid, System.currentTimeMillis());
					state.readyAtMillisByPlayer.remove(uuid);
				}
			}
		}

		@Override
		public void onEntityRemoved(Holder<EntityStore> holder, RemoveReason reason, Store<EntityStore> store) {
			PlayerRef pr = holder.getComponent(PlayerRef.getComponentType());
			if (pr != null) {
				UUID uuid = pr.getUuid();
				state.streetEnabledByPlayer.remove(uuid);
				if (uuid != null) {
					state.firstSeenMillisByPlayer.remove(uuid);
					state.readyAtMillisByPlayer.remove(uuid);
				}
			}
		}

		@Override
		public Query<EntityStore> getQuery() {
			return PlayerRef.getComponentType();
		}
	}
}

