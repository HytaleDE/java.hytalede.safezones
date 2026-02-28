package de.hytalede.safezones.hytale.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows an event title when a player enters a SafeZone.
 */
public final class SafeZonesEnterTitleSystems {
	private SafeZonesEnterTitleSystems() {
	}

	public static void register(SafeZonesHytalePlugin plugin) {
		Objects.requireNonNull(plugin, "plugin");
		State state = new State(plugin);
		plugin.getEntityStoreRegistry().registerSystem(new Tick(state));
		plugin.getEntityStoreRegistry().registerSystem(new Cleanup(state));
	}

	private static final class State {
		private final SafeZonesHytalePlugin plugin;
		private final ConcurrentHashMap<UUID, String> lastZoneByPlayer = new ConcurrentHashMap<>();

		private State(SafeZonesHytalePlugin plugin) {
			this.plugin = plugin;
		}
	}

	private static final Query<EntityStore> QUERY = Archetype.of(
			Player.getComponentType(),
			PlayerRef.getComponentType(),
			TransformComponent.getComponentType()
	);

	private static final class Tick extends EntityTickingSystem<EntityStore> {
		private final State state;

		private Tick(State state) {
			this.state = state;
		}

		@Override
		public void tick(float dt, int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
			PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
			if (playerRef == null) {
				return;
			}
			TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
			if (transform == null) {
				return;
			}

			Vector3d pos = transform.getPosition();
			int bx = (int) Math.floor(pos.getX());
			int bz = (int) Math.floor(pos.getZ());
			// 16x16 cells
			ChunkPos chunk = new ChunkPos(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));

			SafeZonesSnapshot snapshot = state.plugin.getSnapshot();
			SafeZone zone = snapshot.findZoneFor(chunk).orElse(null);
			String currentZone = zone != null ? zone.id() : null;

			UUID uuid = playerRef.getUuid();
			String last = state.lastZoneByPlayer.get(uuid);

			// Entering: zone changed and we are now inside a zone.
			if (currentZone != null && !currentZone.equalsIgnoreCase(last == null ? "" : last)) {
				SafeZonesConfig.EventTitleConfig tt = timing(state.plugin);
				// If we were previously in a different zone, clear the old title first to force refresh.
				if (last != null) {
					EventTitleUtil.hideEventTitleFromPlayer(playerRef, 0.0f);
				}
				EventTitleUtil.showEventTitleToPlayer(
						playerRef,
						Message.raw(zone != null ? zone.titleOrDefault("SafeZone") : "SafeZone"),
						Message.raw(currentZone),
						false,
						null,
						tt.duration(),
						tt.fadeIn(),
						tt.fadeOut()
				);
			}

			// Leaving: we were in a zone, now we're not -> hide.
			if (currentZone == null && last != null) {
				SafeZonesConfig.EventTitleConfig tt = timing(state.plugin);
				EventTitleUtil.hideEventTitleFromPlayer(playerRef, tt.fadeOut());
			}

			// Update state
			if (currentZone == null) {
				state.lastZoneByPlayer.remove(uuid);
			} else {
				state.lastZoneByPlayer.put(uuid, currentZone);
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
			if (pr == null) {
				return;
			}
			UUID uuid = pr.getUuid();

			// Important: entering a world via spawn/portal/teleport can reuse the same player UUID.
			// Clear any stale "last zone" so the title can be shown even if the player spawns inside a zone.
			state.lastZoneByPlayer.remove(uuid);

			// If we already have a transform at add time, show immediately (covers spawning inside zone).
			TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
			if (transform == null) {
				return;
			}
			Vector3d pos = transform.getPosition();
			int bx = (int) Math.floor(pos.getX());
			int bz = (int) Math.floor(pos.getZ());
			ChunkPos chunk = new ChunkPos(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));
			SafeZonesSnapshot snapshot = state.plugin.getSnapshot();
			SafeZone zone = snapshot.findZoneFor(chunk).orElse(null);
			String currentZone = zone != null ? zone.id() : null;
			if (currentZone != null) {
				SafeZonesConfig.EventTitleConfig tt = timing(state.plugin);
				EventTitleUtil.showEventTitleToPlayer(
						pr,
						Message.raw(zone != null ? zone.titleOrDefault("SafeZone") : "SafeZone"),
						Message.raw(currentZone),
						false,
						null,
						tt.duration(),
						tt.fadeIn(),
						tt.fadeOut()
				);
				state.lastZoneByPlayer.put(uuid, currentZone);
			}
		}

		@Override
		public void onEntityRemoved(Holder<EntityStore> holder, RemoveReason reason, Store<EntityStore> store) {
			PlayerRef pr = holder.getComponent(PlayerRef.getComponentType());
			if (pr != null) {
				state.lastZoneByPlayer.remove(pr.getUuid());
			}
		}

		@Override
		public Query<EntityStore> getQuery() {
			return PlayerRef.getComponentType();
		}
	}

	private static SafeZonesConfig.EventTitleConfig timing(SafeZonesHytalePlugin plugin) {
		try {
			SafeZonesConfig cfg = plugin != null ? plugin.getConfig() : null;
			return cfg != null ? cfg.eventTitle() : SafeZonesConfig.EventTitleConfig.defaults();
		} catch (Throwable ignored) {
			return SafeZonesConfig.EventTitleConfig.defaults();
		}
	}
}

