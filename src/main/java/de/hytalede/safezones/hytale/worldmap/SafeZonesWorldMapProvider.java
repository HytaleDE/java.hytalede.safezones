package de.hytalede.safezones.hytale.worldmap;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.IWorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapLoadException;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.IWorldMapProvider;

public final class SafeZonesWorldMapProvider implements IWorldMapProvider {
	/**
	 * Provider id used in the world config JSON/BSON.
	 */
	public static final String ID = "HytaleDE_SafeZones";

	public static final BuilderCodec<SafeZonesWorldMapProvider> CODEC = BuilderCodec.builder(SafeZonesWorldMapProvider.class, SafeZonesWorldMapProvider::new).build();

	@Override
	public IWorldMap getGenerator(World world) throws WorldMapLoadException {
		return SafeZonesChunkWorldMap.INSTANCE;
	}
}

