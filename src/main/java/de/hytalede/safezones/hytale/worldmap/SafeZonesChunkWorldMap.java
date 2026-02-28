package de.hytalede.safezones.hytale.worldmap;

import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMapSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.map.WorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.IWorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapSettings;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class SafeZonesChunkWorldMap implements IWorldMap {
	public static final SafeZonesChunkWorldMap INSTANCE = new SafeZonesChunkWorldMap();

	private SafeZonesChunkWorldMap() {
	}

	@Override
	public WorldMapSettings getWorldMapSettings() {
		// Same scale bounds as the default chunk map (slightly tightened).
		UpdateWorldMapSettings settingsPacket = new UpdateWorldMapSettings();
		settingsPacket.defaultScale = 128.0F;
		settingsPacket.minScale = 64.0F;
		settingsPacket.maxScale = 175.0F;
		return new WorldMapSettings(null, 3.0F, 2.0F, 3, 32, settingsPacket);
	}

	@Override
	public CompletableFuture<WorldMap> generate(World world, int imageWidth, int imageHeight, LongSet chunksToGenerate) {
		@SuppressWarnings("unchecked")
		CompletableFuture<SafeZonesMapImageBuilder>[] futures = new CompletableFuture[chunksToGenerate.size()];
		int futureIndex = 0;
		for (LongIterator iterator = chunksToGenerate.iterator(); iterator.hasNext(); ) {
			long idx = iterator.nextLong();
			futures[futureIndex++] = SafeZonesMapImageBuilder.build(idx, imageWidth, imageHeight, world);
		}

		return CompletableFuture.allOf(futures).thenApply(unused -> {
			WorldMap worldMap = new WorldMap(futures.length);
			for (CompletableFuture<SafeZonesMapImageBuilder> future : futures) {
				SafeZonesMapImageBuilder builder = future.getNow(null);
				if (builder != null) {
					worldMap.getChunks().put(builder.getIndex(), builder.getImage());
				}
			}
			return worldMap;
		});
	}

	@Override
	public CompletableFuture<Map<String, MapMarker>> generatePointsOfInterest(World world) {
		return CompletableFuture.completedFuture(Collections.emptyMap());
	}
}

