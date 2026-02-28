package de.hytalede.safezones.hytale.worldmap;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Periodically invalidates only the affected world-map chunks when SafeZones data changes.
 */
public final class SafeZonesWorldMapUpdateTickingSystem extends TickingSystem<ChunkStore> {
	private final SafeZonesHytalePlugin plugin;
	private static final int MAX_CHUNKS_PER_TICK = 64;
	private final LongOpenHashSet pending = new LongOpenHashSet();

	public SafeZonesWorldMapUpdateTickingSystem(SafeZonesHytalePlugin plugin) {
		this.plugin = Objects.requireNonNull(plugin, "plugin");
	}

	@Override
	public void tick(float dt, int i, Store<ChunkStore> store) {
		// Accumulate newly-dirtied chunks.
		LongSet newlyDirty = plugin.drainDirtyWorldMapChunks();
		if (newlyDirty != null && !newlyDirty.isEmpty()) {
			pending.addAll(newlyDirty);
		}
		if (pending.isEmpty()) {
			return;
		}

		// Process a small batch to avoid multi-second stalls.
		LongOpenHashSet batch = new LongOpenHashSet(Math.min(MAX_CHUNKS_PER_TICK, pending.size()));
		int taken = 0;
		var it = pending.iterator();
		while (it.hasNext() && taken < MAX_CHUNKS_PER_TICK) {
			long idx = it.nextLong();
			it.remove();
			batch.add(idx);
			taken++;
		}

		if (batch.isEmpty()) {
			return;
		}

		for (World world : Universe.get().getWorlds().values()) {
			if (world == null || world.getWorldConfig().isDeleteOnRemove()) {
				continue;
			}
			// Must touch map manager + trackers on the world thread.
			world.execute(() -> {
				try {
					world.getWorldMapManager().clearImagesInChunks(batch);
					for (Player player : world.getPlayers()) {
						if (player != null) {
							player.getWorldMapTracker().clearChunks(batch);
						}
					}
				} catch (Throwable t) {
					plugin.getLogger().at(Level.WARNING).withCause(t).log("Unhandled exception while invalidating world map chunks");
				}
			});
		}
	}
}

